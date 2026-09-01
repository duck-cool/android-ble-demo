package com.demo.bluedebug.utils

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService

// 定义标准服务 UUID 到名称的映射
private val standardServiceNames = mapOf(
    "0000180f-0000-1000-8000-00805f9b34fb" to "电池服务 (Battery)",
    "0000180d-0000-1000-8000-00805f9b34fb" to "心率服务 (Heart Rate)",
    "0000180a-0000-1000-8000-00805f9b34fb" to "设备信息 (Device Info)",
    "00001800-0000-1000-8000-00805f9b34fb" to "通用访问 (Generic Access)",
    "00001801-0000-1000-8000-00805f9b34fb" to "通用属性 (Generic Attribute)"
)

// 获取服务显示名称的扩展函数
fun BluetoothGattService.getDisplayName(): String {
    // 优先查找标准服务名称，找不到则显示原始 UUID
    return standardServiceNames[this.uuid.toString().lowercase()] ?: "自定义服务 (${this.uuid})"
}

// 标准 Characteristic UUID 到中文名称的映射
private val characteristicNameMap = mapOf(
    "00002a00-0000-1000-8000-00805f9b34fb" to "设备名称 (Device Name)",
    "00002a01-0000-1000-8000-00805f9b34fb" to "设备外观 (Appearance)",
    "00002a19-0000-1000-8000-00805f9b34fb" to "电池电量 (Battery Level)",
    "00002a37-0000-1000-8000-00805f9b34fb" to "心率测量值 (Heart Rate Measurement)",
    "00002a29-0000-1000-8000-00805f9b34fb" to "制造商名称 (Manufacturer Name)",
    "00002a24-0000-1000-8000-00805f9b34fb" to "型号编号 (Model Number)",
    "00002a26-0000-1000-8000-00805f9b34fb" to "固件版本 (Firmware Revision)"
)


/**
 * 获取特征值的友好显示名称
 */
fun BluetoothGattCharacteristic.getCharacteristicName(): String {
    // 如果字典中有对应中文则返回，否则返回原始 UUID 并标记为自定义
    return characteristicNameMap[uuid.toString().lowercase()] ?: "自定义特征 ($uuid)"

}

/**
 * 解析 hex 字符串为 ByteArray（Write 数据输入用，Q7）
 * 规则：
 *  - 空格分隔，每段 2 个 hex 字符 = 1 个字节，如 "01 02 FF"
 *  - 容忍 "0x"/"0X" 前缀（"0x01" == "01"）
 *  - 大小写不敏感（"ff" == "FF"）
 *  - 非法输入（非 hex 字符 / 段长度≠2 / 空输入）→ 返回 null，由调用方提示错误
 */
fun parseHex(input: String): ByteArray? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null                     // 空输入
    val parts = trimmed.split("\\s+".toRegex())            // 按空格切段
    val bytes = mutableListOf<Byte>()
    for (part in parts) {
        var hex = part.trim()
        if (hex.startsWith("0x", ignoreCase = true)) hex = hex.substring(2)   // 剥 0x 前缀
        if (hex.length != 2) return null                   // 每段必须正好 2 位
        val v = hex.toIntOrNull(16) ?: return null         // 非 hex 字符 → null
        bytes.add(v.toByte())
    }
    return bytes.toByteArray()
}