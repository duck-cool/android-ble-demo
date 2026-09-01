package com.demo.bluedebug.ui.view

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
import com.demo.bluedebug.data.GattOperation
import com.demo.bluedebug.data.GattOperationQueue
import com.demo.bluedebug.data.GattResult
import com.demo.bluedebug.data.OperationType
import com.demo.bluedebug.ui.BaseActivity
import com.demo.bluedebug.ui.view.model.ExpandableViewModel
import com.demo.bluedebug.utils.getCharacteristicName
import com.demo.bluedebug.utils.getDisplayName
import com.demo.bluedebug.utils.parseHex
import kotlinx.coroutines.Runnable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

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

    private lateinit var tvLog: TextView


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
        tvLog = findViewById(R.id.tvLog)
        tvLog.movementMethod = ScrollingMovementMethod()
        appendLog("页面已打开，正在连接设备…")
        serviceAdapter = DeviceServiceAdapter(
            onItemClick = { info ->
                Log.i(TAG, "onCreate: info = ${info.toString()}")
                if (info is BleInfoItem.CharacteristicUiModel){
                    runOnUiThread {
                        val builder = AlertDialog.Builder(this@DeviceActivity)
                        builder.setTitle("请选择请求类型")
                        builder.setItems(info.properties.toTypedArray()){dialog, which ->
                            val selectedDevice = info.properties.get(which)
                            when(selectedDevice){
                                "Read" -> info.operationType = OperationType.READ
                                "Write" -> info.operationType = OperationType.WRITE
                                "Notify" -> info.operationType = OperationType.WRITE_DESCRIPTOR
                                else -> info.operationType = null
                            }
                            Toast.makeText(this, "选择了: $selectedDevice", Toast.LENGTH_SHORT).show()
                            handleClickInfo(info)
                            Log.i(TAG, "onCreate: 选择了: [${info.displayName}]$selectedDevice")
                        }
                        builder.setNegativeButton("取消",null)
                        builder.show()

                    }

                }
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

    // 方向 3（Q6）：日志区最后一行是不是 [NOTIFY]，是则下一条推送到来时先删掉它（推送折叠）
    private var lastLineIsNotify = false

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun appendLog(msg: String) {
        // Q5-3：onResult 可能在 Binder 线程（结构性判死）/主线程（超时）回调，
        // 自己包 runOnUiThread，别依赖调用方 —— Q2 结论落地
        runOnUiThread {
            val line = "[${SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())}] $msg\n"
            val text = tvLog.text
            if (lastLineIsNotify && text.isNotEmpty()) {
                // 删掉上一条 [NOTIFY] 行（倒数第二行末尾 \n 之后的部分），再写新的
                val lastNl = text.lastIndexOf("\n")
                val prevNl = text.lastIndexOf("\n", lastNl - 1)
                tvLog.text = text.subSequence(0, prevNl + 1).toString() + line
            } else {
                tvLog.append(line)
            }
            // 自动滚动到底部（TextView 需先设 ScrollingMovementMethod）
            val scrollAmount = tvLog.layout?.let { it.getLineTop(tvLog.lineCount) - tvLog.height } ?: 0
            tvLog.scrollTo(0, if (scrollAmount > 0) scrollAmount else 0)
            lastLineIsNotify = msg.startsWith("[NOTIFY]")
        }
    }

    // 机器原样 hex：调试工具的通用语言（0x58 而不是 88）
    private fun toHex(value: ByteArray?): String {
        return value?.joinToString(" ") { "%02X".format(it) } ?: "-"
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun handleClickInfo(info: BleInfoItem){
        if (info is BleInfoItem.CharacteristicUiModel){
            when(info.operationType){
                OperationType.READ -> {
                    val characteristic =
                        curGatt?.getService(UUID.fromString(info.service_uuid))
                            ?.getCharacteristic(UUID.fromString(info.uuid))?: run { Log.w(TAG, "OperationType.READ特征不存在"); return}
                    queue?.enQueue(GattOperation(OperationType.READ,characteristic,null,data = null, onResult = { result ->
                        appendLog("[READ] ${info.displayName} → ${if (result.success) "成功" else "失败"} value=${toHex(result.value)}")
                    }))

                }
                OperationType.WRITE_DESCRIPTOR ->{
                    val characteristic =
                        curGatt?.getService(UUID.fromString(info.service_uuid))
                            ?.getCharacteristic(
                                UUID.fromString(info.uuid)
                            )?: run { Log.w(TAG, "OperationType.WRITE_DESCRIPTOR特征不存在"); return }

                    curGatt?.setCharacteristicNotification(characteristic,true)
                    val cccd =
                        characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                    queue?.enQueue(GattOperation(OperationType.WRITE_DESCRIPTOR,null,cccd,byteArrayOf(0x01, 0x00)){
                        appendLog("[订阅] ${info.displayName} → ${if (it.success) "成功，等待推送" else "失败"}")
                    })
                }
                OperationType.WRITE -> {
                    val characteristic =
                        curGatt?.getService(UUID.fromString(info.service_uuid))
                            ?.getCharacteristic(
                                UUID.fromString(info.uuid)
                            )?:run { Log.w(TAG, "OperationType.WRITE特征不存在"); return }
                    showWriteDialog(info, characteristic)
                }
                else -> {}
            }
        }
    }

    // Q7：Write 数据输入框——hex 输入 + 实时错误标注（TextWatcher 边输边校验）
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun showWriteDialog(info: BleInfoItem.CharacteristicUiModel, characteristic: BluetoothGattCharacteristic) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            hint = "hex 数据，空格分隔，如 01 02 FF（关灯输 00）"
            setSingleLine(true)
        }
        val errorTv = TextView(this).apply {
            text = "⚠ 格式错误：仅支持 hex（0-9 A-F），每段 2 位，空格分隔"
            setTextColor(Color.RED)
            textSize = 12f
            visibility = View.GONE
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
            addView(errorTv)
        }

        // 实时校验：每次文本变化都重新解析，错了立刻标红
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val ok = parseHex(s?.toString().orEmpty()) != null
                errorTv.visibility = if (ok || s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
        })

        AlertDialog.Builder(this)
            .setTitle("发送数据 - ${info.displayName}")
            .setView(container)
            .setPositiveButton("发送") { _, _ ->
                val bytes = parseHex(input.text.toString())
                if (bytes == null) {
                    // 兜底：标注已显示，这里再拦一次（双保险，不发垃圾数据）
                    errorTv.visibility = View.VISIBLE
                    Toast.makeText(this, "数据格式错误，未发送", Toast.LENGTH_SHORT).show()
                } else {
                    queue?.enQueue(GattOperation(OperationType.WRITE, characteristic, null, bytes) {
                        appendLog("[WRITE] ${info.displayName} → ${if (it.success) "成功，已发送 ${toHex(bytes)}" else "失败"}")
                    })
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
                appendLog("✅ 连接成功，正在发现服务…")
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
                    appendLog("❌ 重连次数用尽，放弃（设备识别不到）")
//                    TODO appendLog("设备识别不到")
                    return
                }
                appendLog("❌ 连接断开 status=$status，${retryMillis}ms 后重连…")
                retryHandler.postDelayed(retryRunnable,retryMillis)
                retryMillis *= 2
            }
        }


        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status == BluetoothGatt.GATT_SUCCESS){
                queue = GattOperationQueue({ gatt })
                curGatt = gatt
                appendLog("📡 服务发现完成，共 ${gatt?.services?.size ?: 0} 个服务")
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
                // Step 2 第一版：推送全量进日志区（先看到问题，Q6 再优化）
                appendLog("[NOTIFY] 心率测量值 → ${toHex(value)} ($hr bpm)")
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