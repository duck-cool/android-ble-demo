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
        private val WRITE_CHARACTERISTIC_TIME_TO_WAIT : Int = 10
    }

    private val queue = ArrayDeque<GattOperation>()
    private var isExecuting = false

    private val handler = Handler(Looper.getMainLooper())


    private val timeoutRunnable = object : Runnable {

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun run() {
            val op = queue.firstOrNull() ?: return
            Log.w(TAG, "⏰ 操作超时: type=${op.type}")
            val removeFirstOrNull = queue.removeFirstOrNull()
            Log.w(TAG, "⏰ 操作超时: type=${op.type},$removeFirstOrNull")
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
            onRetryOperation(op)
        }

    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun handleOperation(op: GattOperation): Boolean{
        startTimeout(op)
        var status = false
        when(op.type){
            OperationType.READ -> {
                val char = op.characteristic
                if (char == null) completeAsFailure(op)
                else{
                    status = gattProvider()?.readCharacteristic(char)?:false
                    if (!status){
                        onOperationCompleted(op.type,false, GattResult(false,null),getUuid(op))
                    }
                }
            }

            OperationType.WRITE -> {
                val char = op.characteristic
                if (char == null || op.data == null) completeAsFailure(op)
                else{
                    char.value = op.data
                    status = gattProvider()?.writeCharacteristic(char) ?: false
                    if (!status){
                        onOperationCompleted(op.type,false, GattResult(false,null),getUuid(op))
                    }
                }
            }

            OperationType.WRITE_DESCRIPTOR -> {
                val descriptor = op.descriptor
                if (descriptor == null || op.data == null) completeAsFailure(op)
                else{
                    descriptor.value = op.data
                    status = gattProvider()?.writeDescriptor(descriptor) ?: false
                    if (!status){
                        onOperationCompleted(op.type,false, GattResult(false,null),getUuid(op))
                    }
                }
            }

            OperationType.MTU -> {
                status = gattProvider()?.requestMtu(op.mtu) ?: false
                if (!status){
                    onOperationCompleted(op.type,false, GattResult(false,null),getUuid(op))
                }
            }
            else -> {
                status = false
            }
        }
        return status
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
    fun onRetryOperation(op: GattOperation){
        cancelTimeout()
        try {
            repeat(WRITE_CHARACTERISTIC_MAX_RETRIES) {
                handleOperation(op)
            }
        }catch (e: RemoteException){

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


    fun clear(){
        cancelTimeout()
        queue.clear()
        isExecuting = false
    }
}