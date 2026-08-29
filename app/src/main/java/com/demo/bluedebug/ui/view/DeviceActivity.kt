package com.demo.bluedebug.ui.view

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.viewModels
import androidx.annotation.RequiresPermission
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.demo.bluedebug.MainActivity.Companion.MAX_RETRY_MILLIS
import com.demo.bluedebug.MainActivity.Companion.TAG
import com.demo.bluedebug.R
import com.demo.bluedebug.adpater.DeviceServiceAdapter
import com.demo.bluedebug.data.BleInfoItem
import com.demo.bluedebug.data.ConnState
import com.demo.bluedebug.data.GattOperationQueue
import com.demo.bluedebug.data.GattResult
import com.demo.bluedebug.data.OperationType
import com.demo.bluedebug.ui.BaseActivity
import com.demo.bluedebug.ui.view.model.ExpandableViewModel
import com.demo.bluedebug.utils.getCharacteristicName
import com.demo.bluedebug.utils.getDisplayName
import kotlinx.coroutines.Runnable

class DeviceActivity: BaseActivity() {

    private val viewModel: ExpandableViewModel by viewModels()
    private lateinit var bleDevice: BluetoothDevice

    private var queue: GattOperationQueue? = null

    private val connState: ConnState = ConnState()

    private var curDevice: BluetoothDevice? = null

    private val retryHandler = Handler(Looper.getMainLooper())

    private var retryMillis: Long = 1000

    private var curGatt: BluetoothGatt? = null
    
    private lateinit var serviceRv: RecyclerView
    
    private lateinit var serviceAdapter: DeviceServiceAdapter


    private val retryRunnable : Runnable = object: Runnable {

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun run() {
            if (connState.getCurState() == ConnState.CONNECTED || connState.getCurState() == ConnState.CONNECTING) return
            Log.i(TAG, "onConnectionStateChange: 开始尝试重连，retryMillis = $retryMillis ms")
            connState.updateState(ConnState.CONNECTING)
            curGatt?.disconnect()
            curGatt?.close()
            curGatt = curDevice?.connectGatt(applicationContext,false,gattCallback)
        }

    }


    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device)
        serviceRv = findViewById<RecyclerView>(R.id.service_info)
        serviceAdapter = DeviceServiceAdapter(
            onItemClick = { info ->
            Log.i(TAG, "onCreate: info = ${info.toString()}")
                          },
            onExpandClick = { uuid ->
                viewModel.toggleParent(uuid)
            }
        )
        serviceRv?.apply { 
            adapter = serviceAdapter
            layoutManager = LinearLayoutManager(applicationContext, RecyclerView.VERTICAL,false)
            (itemAnimator as? DefaultItemAnimator)?.apply {
                addDuration = 250
                removeDuration = 250
                moveDuration = 250
            }
        }

        viewModel.flatList.observe(this){list ->
            serviceAdapter.submitList(list)
        }
        
        bleDevice = intent.extras?.get("ble_device") as BluetoothDevice

        bleDevice?.connectGatt(this,false,gattCallback)

    }

    private val gattCallback = object : BluetoothGattCallback(){
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            Log.i(TAG, "onConnectionStateChange: status = $status; newStatus = $newState")

            if (newState == BluetoothGatt.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS){
                if (curGatt != null && gatt !== curGatt) { Log.w(TAG, "⚠️ 不是当前连接的连接回调，忽略"); return }
                connState.updateState(ConnState.CONNECTED)
                retryMillis = 1000
                gatt?.discoverServices()
            }else if (newState == BluetoothGatt.STATE_DISCONNECTED){
                if (gatt !== curGatt) { Log.w(TAG, "⚠️ 旧连接的断开回调，忽略"); return }
                Log.w(TAG, "❌ 连接失败/断开，status=$status")
                connState.updateState(ConnState.DISCONNECTED)
                queue?.clearQueue()
                gatt?.close()
                curGatt = null
                if (retryMillis >= MAX_RETRY_MILLIS){
                    connState.updateState(ConnState.IDLE)
//                    TODO appendLog("设备识别不到")
                    return
                }
                retryHandler.postDelayed(retryRunnable,retryMillis)
                retryMillis *= 2
            }
        }


        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status == BluetoothGatt.GATT_SUCCESS){
                queue = GattOperationQueue({ gatt })
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
                            props,
                            characteristic.value
                        )
                    }
                    BleInfoItem.ServiceUiModel(
                        service.uuid.toString(),
                        service.getDisplayName(),
                        charList
                    )
                }
                runOnUiThread {
                    viewModel.loadDate(serviceList!!)
                    serviceAdapter.submitList(serviceList)
                }
            }else {
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
            runOnUiThread {
                val success = status == BluetoothGatt.GATT_SUCCESS
                queue?.onOperationCompleted(OperationType.READ, success,
                    GattResult(success, characteristic?.value),characteristic.uuid
                )
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, status)
            runOnUiThread {
                val success = status == BluetoothGatt.GATT_SUCCESS
                queue?.onOperationCompleted(OperationType.READ, success,
                    GattResult(success, characteristic?.value),characteristic?.uuid
                )
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

            runOnUiThread {
                val success = status == BluetoothGatt.GATT_SUCCESS
                queue?.onOperationCompleted(OperationType.WRITE, success,
                    GattResult(success, characteristic?.value),characteristic?.uuid
                )
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            Log.i(TAG, "onMtuChanged: \uD83D\uDCE6 MTU 协商结果: $mtu 字节")
            runOnUiThread {
                val success = status == BluetoothGatt.GATT_SUCCESS
                queue?.onOperationCompleted(OperationType.MTU, success,
                    GattResult(success, null),null
                )
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
            runOnUiThread {
                val success = status == BluetoothGatt.GATT_SUCCESS
                queue?.onOperationCompleted(OperationType.WRITE_DESCRIPTOR, success,
                    GattResult(success, descriptor?.value),descriptor?.uuid
                )
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
        private fun handleHeartRate(value: ByteArray) {
            if (value.isNotEmpty()) {
                val hr = value[0].toInt() and 0xFF
                Log.i(TAG, "❤️ 心率: $hr bpm")
            }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN])
    override fun onDestroy() {
        super.onDestroy()
        queue?.clearQueue()
        curGatt?.disconnect()
        curGatt?.close()
        curGatt = null
        retryHandler.removeCallbacks(retryRunnable)
    }
}