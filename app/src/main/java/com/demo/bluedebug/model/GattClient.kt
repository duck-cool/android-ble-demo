package com.demo.bluedebug.model

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.util.Log
import androidx.annotation.RequiresPermission
import com.demo.bluedebug.data.GattResult
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

class GattClient(private val gatt: BluetoothGatt) {

    companion object{
        private val TAG = GattClient::class.simpleName
    }
    private val mutex = Mutex()

    private var pendingRead: Pair<BluetoothGattCharacteristic, CancellableContinuation<GattResult>>? = null
    private var pendingWrite: Pair<BluetoothGattCharacteristic, CancellableContinuation<GattResult>>? = null
    private var pendingNotify: Pair<BluetoothGattDescriptor, CancellableContinuation<GattResult>>? = null

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun read(char: BluetoothGattCharacteristic): GattResult = mutex.withLock {
        (withTimeoutOrNull(5000) {
            if (!sendWithBusyRetry { gatt.readCharacteristic(char) }) {
                return@withTimeoutOrNull GattResult.Companion.BUSY_FAILED
            }

            suspendCancellableCoroutine { cont ->
                pendingRead = char to cont
                cont.invokeOnCancellation {
                    if (pendingRead?.second === cont) pendingRead = null
                }
            }

        } ?: GattResult.Companion.TIMEOUT) as GattResult
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun write(char: BluetoothGattCharacteristic, data: ByteArray): GattResult = mutex.withLock {
        (withTimeoutOrNull(5000) {
            if (!sendWithBusyRetry(send = {
                    char.value = data
                    gatt.writeCharacteristic(char)
                })) {
                return@withTimeoutOrNull GattResult.Companion.BUSY_FAILED
            }

            suspendCancellableCoroutine { cont ->
                pendingWrite = char to cont
                cont.invokeOnCancellation {
                    if (pendingWrite?.second === cont) pendingWrite = null
                }
            }
        } ?: GattResult.Companion.TIMEOUT) as GattResult
    }

    /***
     * 0x0001/0x0000:开关
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun subscribe(char: BluetoothGattCharacteristic, cccd: BluetoothGattDescriptor, enable: Boolean): GattResult = mutex.withLock {
        (withTimeoutOrNull(5000) {
            if (!gatt.setCharacteristicNotification(char, enable)) {
                return@withTimeoutOrNull GattResult.Companion.BUSY_FAILED
            }
            if (!sendWithBusyRetry {
                    cccd.value = if (enable) byteArrayOf(0x01, 0x00) else byteArrayOf(0x00, 0x00)
                    gatt.writeDescriptor(cccd)
                }) {
                return@withTimeoutOrNull GattResult.Companion.BUSY_FAILED
            }

            suspendCancellableCoroutine { cont ->
                pendingNotify = cccd to cont

                cont.invokeOnCancellation {
                    if (pendingNotify?.second === cont) pendingNotify = null
                }
            }
        } ?: GattResult.Companion.TIMEOUT) as GattResult
    }

    private suspend fun sendWithBusyRetry(send: () -> Boolean): Boolean{
        var attempts = 0
        while(attempts < 5){
            Log.i(TAG, "sendWithBusyRetry: attempts = $attempts")
            if (send()) return true
            attempts++
            delay(100)
        }
        return false
    }

    fun onCharacteristicRead(char: BluetoothGattCharacteristic, status: Int, value: ByteArray?){
        val (expectedChar,cont) = pendingRead?:return
        if (expectedChar.uuid != char.uuid) return
        pendingRead = null
        val ret = status == BluetoothGatt.GATT_SUCCESS
        cont.resume(GattResult(ret, value, if (ret) "Success" else "Failure"))
    }

    fun onCharacteristicWrite(
        char: BluetoothGattCharacteristic,
        status: Int
    ) {
        val (expectedChar,cont) = pendingWrite?:return
        if (expectedChar.uuid != char.uuid) return
        pendingWrite = null
        val ret = status == BluetoothGatt.GATT_SUCCESS
        cont.resume(GattResult(ret, null, if (ret) "Success" else "Failure"))
    }

    fun onDescriptorWrite(
        descriptor: BluetoothGattDescriptor,
        status: Int
    ) {
        val (expectedDes,cont) = pendingNotify?:return
        if (expectedDes.characteristic.uuid != descriptor.characteristic.uuid) return
        pendingNotify = null
        val ret = status == BluetoothGatt.GATT_SUCCESS
        cont.resume(GattResult(ret, null, if (ret) "Success" else "Failure"))
    }

    fun getCharacteristic(serviceUuid:String,charUuid:String): BluetoothGattCharacteristic?{
        return gatt.getService(UUID.fromString(serviceUuid))?.getCharacteristic(UUID.fromString(charUuid))
    }
}