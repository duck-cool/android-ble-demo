package com.demo.bluedebug.ui.view.model

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.content.Intent
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import com.demo.bluedebug.MainActivity.Companion.MAX_RETRY_MILLIS
import com.demo.bluedebug.data.BleInfoItem
import com.demo.bluedebug.data.ConnState
import com.demo.bluedebug.data.GattResult
import com.demo.bluedebug.data.LogLevel
import com.demo.bluedebug.data.MsgLevel
import com.demo.bluedebug.data.UiMessage
import com.demo.bluedebug.model.GattClient
import com.demo.bluedebug.service.BleForegroundService
import com.demo.bluedebug.utils.getCharacteristicName
import com.demo.bluedebug.utils.getDisplayName
import com.demo.bluedebug.utils.toHex
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

class BleDeviceViewModel(application: Application): AndroidViewModel(application) {

    companion object{
        val TAG = BleDeviceViewModel::class.simpleName
    }

    //BLE connect State
    private val _connState: MutableLiveData<ConnState> = MutableLiveData(ConnState.IDLE)
    val connState: LiveData<ConnState> = _connState
    private val _serviceData: MutableLiveData<List<BleInfoItem.ServiceUiModel>> = MutableLiveData()
    val serviceData: LiveData<List<BleInfoItem.ServiceUiModel>> = _serviceData

    private val _message = MutableLiveData<UiMessage>()
    val message: LiveData<UiMessage> = _message
    private var bleDevice: BluetoothDevice? = null
    private var curGatt: BluetoothGatt? = null
    private var gattClient: GattClient? = null
    private var retryMillis: Long = 1000
    private var reconnectJob: Job? = null


    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun start(device: BluetoothDevice){
        if (_connState.value == ConnState.CONNECTING || _connState.value == ConnState.CONNECTED) return
        if (reconnectJob?.isActive == true) {
            Log.i(TAG, "重连循环进行中，跳过 start")
            return
        }
        this.bleDevice = device
        _message.postValue(UiMessage(
            "正在连接设备…",
            MsgLevel.LOG,
            LogLevel.INFO
        ))
        _connState.postValue(ConnState.CONNECTING)
        device.connectGatt(application,false,gattCallback)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun read(serviceUuid:String,charUuid:String,displayName: String){
        if (_connState.value != ConnState.CONNECTED) {
            _message.postValue(UiMessage("⚠\uFE0F 设备未就绪…", MsgLevel.BOTH, LogLevel.INFO))
            return
        }
        val char = gattClient?.getCharacteristic(serviceUuid,charUuid)?: return
        viewModelScope.launch {
            val result = gattClient?.read(char) ?: GattResult.FAILURE
            _message.postValue(UiMessage(
                "[READ] $displayName → ${if (result.success) "[成功]" else "[失败]: ${result.msg}"} value=${toHex(result.value)}",
                MsgLevel.BOTH,
                if (result.success) LogLevel.INFO else LogLevel.ERROR
            ))   // 结果消息
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun write(serviceUuid:String,charUuid:String,displayName: String,data: ByteArray){
        if (_connState.value != ConnState.CONNECTED) {
            _message.postValue(UiMessage("⚠\uFE0F 设备未就绪…", MsgLevel.BOTH, LogLevel.INFO))
            return
        }
        val char = gattClient?.getCharacteristic(serviceUuid,charUuid)?: return
        viewModelScope.launch {
            val result = gattClient?.write(char,data) ?: GattResult.FAILURE
            _message.postValue(UiMessage(
                "[WRITE] $displayName → ${if (result.success) "[成功]" else "[失败]: ${result.msg}"}" ,
                MsgLevel.BOTH,
                if (result.success) LogLevel.INFO else LogLevel.ERROR
            ))   // 结果消息
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun notify(serviceUuid:String,charUuid:String,displayName: String,enable: Boolean){
        if (_connState.value != ConnState.CONNECTED) {
            _message.postValue(UiMessage("⚠\uFE0F 设备未就绪…", MsgLevel.BOTH, LogLevel.INFO))
            return
        }
        val char = gattClient?.getCharacteristic(serviceUuid,charUuid)?: return
        val cccd =
            char.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))?:return
        viewModelScope.launch {
            val result = gattClient?.subscribe(char,cccd,enable) ?: GattResult.FAILURE
            _message.postValue(UiMessage(
                "[订阅] $displayName → ${if (result.success) "[成功]" else "[失败]: ${result.msg}"}" ,
                MsgLevel.BOTH,
                if (result.success) LogLevel.INFO else LogLevel.ERROR
            ))   // 结果消息
        }
    }

    /** 安排下一次重连（定时自驱动 + 指数退避 + 上限 + 去重） */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun scheduleReconnect() {
        if (_connState.value == ConnState.CONNECTED) return  // 连上了，停
        if (reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch {
            while (_connState.value != ConnState.CONNECTED){
                attemptConnect()
                val delayMs = retryMillis
                retryMillis *= 2
                if (retryMillis >= MAX_RETRY_MILLIS) {                  // 退避到上限：不再放弃，封顶 30s 持续低频重试
                    retryMillis = MAX_RETRY_MILLIS                       // 设备随时可能回来，永久低频尝试比永久放弃好
                    _message.postValue(UiMessage(
                        "⏳ 设备仍不可达，每 30s 持续尝试…",
                        MsgLevel.LOG,
                        LogLevel.WARN
                    ))
                }
                delay(delayMs)               // 指数退避：1s→2s→4s→8s→16s
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun attemptConnect(){
        _connState.postValue(ConnState.CONNECTING)
        _message.postValue(UiMessage(
            "正在连接设备…",
            MsgLevel.LOG,
            LogLevel.INFO
        ))
        curGatt?.disconnect()
        curGatt?.close()
        curGatt = null
        val newGatt = bleDevice?.connectGatt(application, false, gattCallback)
        if (newGatt == null) {
            // connectGatt 返回 null = 连接发起失败（空安全 ?. 会静默吞掉，必须显式检查！）
            _message.postValue(UiMessage(
                "⚠️ connectGatt 返回 null，本次重连发起失败",
                MsgLevel.LOG,
                LogLevel.ERROR
            ))
            Log.w(TAG, "⚠️ connectGatt 返回 null，本次重连发起失败")
        } else {
            curGatt = newGatt
        }
    }


    private val gattCallback = object : BluetoothGattCallback(){
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            Log.i(TAG, "onConnectionStateChange: status = $status; newStatus = $newState")

            if (newState == BluetoothGatt.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS){
                if (curGatt != null && gatt !== curGatt) { Log.w(TAG, "⚠️ 不是当前连接的连接回调，忽略"); return }
                _connState.postValue(ConnState.CONNECTED)
                _message.postValue(UiMessage(
                    "✅ 连接成功，正在发现服务…",
                    MsgLevel.LOG,
                    LogLevel.INFO
                ))
                retryMillis = 1000
                gatt?.discoverServices()
            }else if (newState == BluetoothGatt.STATE_DISCONNECTED){
                if (gatt !== curGatt) { Log.w(TAG, "⚠️ 旧连接的断开回调，忽略"); return }
                _message.postValue(UiMessage(
                    "❌ 连接失败/断开，status=$status,准备重连…",
                    MsgLevel.LOG,
                    LogLevel.WARN
                ))
                Log.w(TAG, "❌ 连接失败/断开，status=$status")
                _connState.postValue(ConnState.DISCONNECTED)
                gatt?.close()
                gattClient = null
                scheduleReconnect()   // 统一入口：内部做退避/上限/去重判断
            }
        }


        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status == BluetoothGatt.GATT_SUCCESS){
                gattClient = gatt?.let { GattClient(it) }
                curGatt = gatt

                _message.postValue(UiMessage(
                    "📡 服务发现完成，共 ${gatt?.services?.size ?: 0} 个服务",
                    MsgLevel.LOG,
                    LogLevel.INFO
                ))
                val serviceList = gatt?.services?.map { service ->
                    Log.i(TAG, "onServicesDiscovered: Service UUID = ${service.uuid}")

                    val charList = service.characteristics.map { characteristic ->
                        val props = mutableListOf<String>()
                        val properties = characteristic.properties
                        // 1. 判断是否支持读取 (Read)
                        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) props.add("Read")
                        // 2. 判断是否支持写入 (Write)
                        // 注意：写入分为带响应(WRITE)和无响应(WRITE_NO_RESPONSE)，通常只要支持其一即可
                        if ((properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) ||
                            (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0))
                            props.add("Write")
                        // 3. 判断是否支持通知 (Notify) 或 指示 (Indicate)
                        // 这两者都允许设备主动推送数据给手机，通常只要支持其一就能开启监听
                        if ((properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) ||
                            (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0))
                            props.add("Notify")

                        BleInfoItem.CharacteristicUiModel(
                            characteristic.uuid.toString(),
                            service.uuid.toString(),
                            characteristic.getCharacteristicName(),
                            props,null,
                            characteristic.value
                        )
                    }
                    BleInfoItem.ServiceUiModel(
                        service.uuid.toString(),
                        service.getDisplayName(),
                        charList
                    )
                } as List<BleInfoItem.ServiceUiModel>

                _serviceData.postValue(serviceList)
            }else {
                _message.postValue(UiMessage(
                    "服务发现失败 status=$status",
                    MsgLevel.LOG,
                    LogLevel.ERROR
                ))
                Log.w(TAG, "服务发现失败 status=$status")
            }

        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, value, status)
            gattClient?.onCharacteristicRead(characteristic,status,value)
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, status)
            characteristic?.let {
                gattClient?.onCharacteristicRead(it,status,it.value)
            }

        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            Log.i(TAG, "onCharacteristicWrite: LED 写回执: status = $status")
            characteristic?.let {
                gattClient?.onCharacteristicWrite(it,status)
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
            Log.i(TAG, "CCCD 写入回执: status=$status（0=订阅成功，设备将开始推数据）")
            descriptor?.let {
                gattClient?.onDescriptorWrite(it,status)
            }
        }

        // ① 两参版本：Android 12 及以下（含你的 Android 10）走这个！
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleHeartRate(characteristic.value ?: ByteArray(0))
        }

        // ② 三参版本：Android 13+ 走这个
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            Log.i(TAG, "📥 收到推送! uuid=${characteristic.uuid} value=${characteristic.value?.joinToString() ?: "null"}")
            handleHeartRate(value)
        }

        // ③ 统一处理
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        private fun handleHeartRate(value: ByteArray) {
            if (value.isNotEmpty()) {
                val hr = value[0].toInt() and 0xFF
                Log.i(TAG, "❤️ 心率: $hr bpm")
                // Step 2 第一版：推送全量进日志区（先看到问题，Q6 再优化）
                _message.postValue(UiMessage(
                    "[NOTIFY] 心率测量值 → ${toHex(value)} ($hr bpm)",
                    MsgLevel.LOG,
                    LogLevel.INFO
                ))
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCleared() {
        Log.i(TAG, "onCleared: viewModel destory")
        super.onCleared()

        // 先摘壳再断连：壳保护连接，连接要死了壳先退（语义自洽）；stopService 触发 Service.onDestroy 是异步的，不挡断连收尾
        val intent = Intent(application, BleForegroundService::class.java)
        application.stopService(intent)

        curGatt?.disconnect()
        curGatt?.close()
        curGatt = null
        gattClient = null
        reconnectJob?.cancel()
    }
}