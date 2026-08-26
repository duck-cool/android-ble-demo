package com.demo.bluedebug.data

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor

class GattOperation (
    val type: OperationType,        // 干什么：READ / WRITE / WRITE_DESCRIPTOR / MTU
    val characteristic: BluetoothGattCharacteristic?,   // 对谁干
    val descriptor: BluetoothGattDescriptor?,           // 或对描述符干
    val data: ByteArray?,           // 带不带数据（write 用）
    val mtu: Int = 517,
    val timeoutMs:Long = 5000,
    var retryCount: Int = 2,        // 失败重试几次
    val onResult: (GattResult) -> Unit   // 干完怎么知道（结果回调）
)

enum class OperationType{
    READ,
    WRITE,
    WRITE_DESCRIPTOR,
    MTU
}

class GattResult(val success: Boolean, val value: ByteArray?)