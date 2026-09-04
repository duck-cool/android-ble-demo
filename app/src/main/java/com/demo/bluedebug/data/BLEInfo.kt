package com.demo.bluedebug.data

enum class OperationType{
    READ,
    WRITE,
    WRITE_DESCRIPTOR,
    MTU
}

class GattResult(val success: Boolean, val value: ByteArray?, val msg: String, val mtu: Int = 517){
    companion object {
        /** 默认的成功结果（无数据） */
        val SUCCESS = GattResult(true, null,"SUCCESS")

        /** 默认的失败结果 */
        val FAILURE = GattResult(false, null,"FAILURE")

        /** 蓝牙设备繁忙/发送指令失败的默认结果 */
        val BUSY_FAILED = GattResult(false, null,"BUSY_FAILED")

        /** 超时导致的默认失败结果 */
        val TIMEOUT = GattResult(false, null,"TIMEOUT")
    }
}

sealed class BleInfoItem{
    data class ServiceUiModel(
        val uuid: String,
        val displayName: String,
        val characteristics: List<CharacteristicUiModel>,
        val isExpanded: Boolean = false
    ): BleInfoItem()

    data class CharacteristicUiModel(
        val uuid: String,
        val service_uuid:String,
        val displayName:String,
        val properties: List<String>,
        var operationType: OperationType?,
        val value: ByteArray? = null
    ): BleInfoItem()
}
