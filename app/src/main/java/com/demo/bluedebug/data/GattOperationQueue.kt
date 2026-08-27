package com.demo.bluedebug.data

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import androidx.annotation.RequiresPermission
import java.util.UUID


class GattOperationQueue(
    private val gattProvider: () -> BluetoothGatt?
) {

    companion object{
        val TAG = GattOperationQueue::class.simpleName
        private val WRITE_CHARACTERISTIC_MAX_RETRIES : Int = 5
        private val WRITE_CHARACTERISTIC_TIME_TO_WAIT : Long = 100
    }

    private val queue = ArrayDeque<GattOperation>()
    private var isExecuting = false

    private val handler = Handler(Looper.getMainLooper())


    private val timeoutRunnable = object : Runnable {

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun run() {
            val op = queue.firstOrNull() ?: return
            Log.w(TAG, "⏰ 操作超时: type=${op.type}")
            completeAsFailure(op)

        }

    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun startTimeout(op: GattOperation){
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable,op.timeoutMs)
    }

    private fun cancelTimeout() = handler.removeCallbacks(timeoutRunnable)

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun enQueue(op: GattOperation){
        queue.addLast(op)
        if (!isExecuting) execute()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun execute(){
        if (queue.isEmpty()) {
            isExecuting = false
            return
        }
        isExecuting = true
        val op = queue.first()
        val status = handleOperation(op)
        if (!status){
            sendWithBusyRetry(op)
        }

    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun handleOperation(op: GattOperation): Boolean{
        startTimeout(op)
        when(op.type){
            OperationType.READ -> {
                val char = op.characteristic
                if (char == null) completeAsFailure(op)
                else return gattProvider()?.readCharacteristic(char)?:false
            }

            OperationType.WRITE -> {
                val char = op.characteristic
                if (char == null || op.data == null) completeAsFailure(op)
                else{
                    char.value = op.data
                    return gattProvider()?.writeCharacteristic(char) ?: false
                }
            }

            OperationType.WRITE_DESCRIPTOR -> {
                val descriptor = op.descriptor
                if (descriptor == null || op.data == null) completeAsFailure(op)
                else{
                    descriptor.value = op.data
                    return gattProvider()?.writeDescriptor(descriptor) ?: false
                }
            }

            OperationType.MTU -> {
                return gattProvider()?.requestMtu(op.mtu) ?: false
            }
            else -> {}
        }
        return true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun onOperationCompleted(type: OperationType, isSuccess: Boolean, result: GattResult,uuid: UUID?){
        if (queue.isEmpty()) return
        val op = queue.first()

        val expectedUuid = getUuid(op)

        if (uuid != null && expectedUuid != null && uuid != expectedUuid){
            Log.i(TAG, "onOperationCompleted: ⚠\uFE0F 迟到的回执 uuid=$uuid，当前在途=${op.type}，丢弃！")
            return
        }
        cancelTimeout()

        if (!isSuccess && op.retryCount > 0){
            op.retryCount -= 1;
        }else{
            queue.removeFirst()
            op.onResult(result)
        }
        execute()

    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendWithBusyRetry(op: GattOperation,busyRetry: Int = 0){
        cancelTimeout()
        if (op != queue.firstOrNull()) return
        if (busyRetry >= WRITE_CHARACTERISTIC_MAX_RETRIES){
            completeAsFailure(op)
            return
        }
        try {
            handler.postDelayed({
                val status = handleOperation(op)
                if (!status){
                    sendWithBusyRetry(op,busyRetry+1)
                }
            },WRITE_CHARACTERISTIC_TIME_TO_WAIT)
        }catch (e: RemoteException){
            Log.i(TAG, "sendWithBusyRetry: 丢弃这次任务: ${op.type}")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun completeAsFailure(op: GattOperation){
        cancelTimeout()
        queue.removeFirst()
        op.onResult(GattResult(false,null))
        execute()
    }

    private fun getUuid(op: GattOperation): UUID? = when(op.type){
        OperationType.READ,
        OperationType.WRITE -> op.characteristic?.uuid

        OperationType.WRITE_DESCRIPTOR -> op.descriptor?.uuid
        OperationType.MTU -> null
    }

    fun clearQueue(){
        cancelTimeout()
        while (queue.isNotEmpty()){
            queue.removeFirst().onResult(GattResult(false,null))
        }
        queue.clear()
        isExecuting = false
    }
}