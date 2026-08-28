package com.demo.bluedebug

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.demo.bluedebug.adpater.BluetoothDeviceAdapter
import com.demo.bluedebug.data.BluetoothDeviceItem
import com.demo.bluedebug.data.ConnState
import com.demo.bluedebug.data.GattOperation
import com.demo.bluedebug.data.GattOperationQueue
import com.demo.bluedebug.data.GattResult
import com.demo.bluedebug.data.OperationType
import kotlinx.coroutines.Runnable
import java.util.UUID

class MainActivity : AppCompatActivity() {
    companion object{
        val TAG = MainActivity::class.simpleName
        val MAX_RETRY_MILLIS: Long = 30000
    }

    private lateinit var btnScan: Button
    private lateinit var tvResult: TextView

    private lateinit var rvDevice: RecyclerView
    private lateinit var bluetoothAdapter: BluetoothDeviceAdapter

    private var queue: GattOperationQueue? = null

    private val connState: ConnState = ConnState()

    private var curDevice: BluetoothDevice? = null

    private val retryHandler = Handler(Looper.getMainLooper())

    private var retryMillis: Long = 1000

    private var curGatt: BluetoothGatt? = null

    // 扫描器 = 前面比喻里的"收音机"
    private var scanner: BluetoothLeScanner? = null
    private var isScanning = false
    private val sb = StringBuilder()

    private val bluetoothDeviceMap = mutableMapOf<String, BluetoothDeviceItem>()

    // ── 运行时权限申请器（Activity Result API，官方推荐写法）──
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                result[Manifest.permission.BLUETOOTH_SCAN] == true
            } else {
                result[Manifest.permission.ACCESS_FINE_LOCATION] == true
            }
            appendLog(if (ok) "权限已授予，点按钮开始扫描" else "权限被拒绝，无法扫描")
        }

    private val retryRunnable : Runnable = object: Runnable{

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
    @SuppressLint("MissingInflatedId", "MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        btnScan = findViewById(R.id.btnScan)
        tvResult = findViewById(R.id.tvResult)
        rvDevice = findViewById(R.id.rv_device)
        bluetoothAdapter = BluetoothDeviceAdapter(null){ item ->
            if (connState.getCurState() == ConnState.CONNECTED || connState.getCurState() == ConnState.CONNECTING) return@BluetoothDeviceAdapter
            connState.updateState(ConnState.CONNECTING)
            curDevice = item
            curGatt?.disconnect()
            curGatt?.close()
            curGatt = item.connectGatt(this,false,gattCallback)

        }
        rvDevice.adapter = bluetoothAdapter
        rvDevice.layoutManager = LinearLayoutManager(applicationContext, LinearLayoutManager.VERTICAL,false)

        // 拿到系统蓝牙服务的"收音机"
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = bluetoothManager.adapter
        scanner = adapter.bluetoothLeScanner



        btnScan.setOnClickListener {
            if (!hasPermission()) {
                // 权限不够 → 弹窗申请；申请完会在回调里提示重新点按钮
                requestPermissions()
            } else {
                toggleScan()
            }
        }
    }

    // 判断当前系统版本下需要哪个权限（API 31+ 用 BLUETOOTH_SCAN，老系统用定位）
    private fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        permissionLauncher.launch(permissions)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun toggleScan() {
        if (isScanning) stopScan() else startScan()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun startScan() {
        sb.clear()
        tvResult.text = "正在扫描...\n"
        isScanning = true
        btnScan.text = "停止扫描"
        // ★ 真正开始"听广播"。回调在 Binder 线程，不能直接改 UI！
        val filter = listOf(ScanFilter.Builder()
            .setServiceUuid(ParcelUuid.fromString("0000180D-0000-1000-8000-00805F9B34FB"))
            .build())
        // ✅ 正确做法：传入默认的 ScanSettings
        val scanSettings = ScanSettings.Builder().build()
        scanner?.startScan(filter,scanSettings,scanCallback)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun stopScan() {
        isScanning = false
        btnScan.text = "开始扫描"
        scanner?.stopScan(scanCallback)
        appendLog("—— 扫描已停止 ——")
    }

    // ★ 扫描回调：附近每个正在广播的设备，每广播一次就回调一次
    private val scanCallback = object : ScanCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device: BluetoothDevice = result.device
            val name = device.name ?: "(未命名)"
            val mac = device.address
            val rssi = result.rssi
            if (!bluetoothDeviceMap.containsKey(mac)){
                val deviceItem = BluetoothDeviceItem(name, mac, rssi, device)
                bluetoothDeviceMap[mac] = deviceItem
                bluetoothAdapter.setItem(deviceItem)
            }

        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                appendLog("扫描失败，错误码: $errorCode（1=扫描冲突 2=系统繁忙 3=蓝牙关闭 4=内部错误）")
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback(){
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            Log.i(TAG, "onConnectionStateChange: status = $status; newStatus = $newState")

            if (newState == BluetoothGatt.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS){
                if (gatt !== curGatt) { Log.w(TAG, "⚠️ 不是当前连接的连接回调，忽略"); return }
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
                    appendLog("设备识别不到")
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
                gatt?.services?.forEach { service ->
                    Log.i(TAG, "onServicesDiscovered: Service UUID = ${service.uuid}")
                }
                gatt?.let {
                    val characteristic =
                        it.getService(UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb"))
                            ?.getCharacteristic(
                                UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
                            )?: run { Log.w(TAG, "特征不存在"); return@let }

                    it.setCharacteristicNotification(characteristic,true)
                    val cccd =
                        characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                    queue?.enQueue(GattOperation(OperationType.WRITE_DESCRIPTOR,null,cccd,byteArrayOf(0x01, 0x00)){})


                    val battery_chr = it.getService(UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"))
                        ?.getCharacteristic(
                            UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb"))?: run { Log.w(TAG, "特征不存在"); return@let }
                    queue?.enQueue(GattOperation(OperationType.READ,battery_chr,null,data = null, onResult = { result ->
                        if (result.success){
                            val battery = result.value?.getOrNull(0)?.toInt() ?: -1
                            Log.i(TAG, "🔋 电量: $battery%")
                        }
                    }))
                    
                    val led_char =
                        it.getService(UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"))
                            ?.getCharacteristic(
                                UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
                            )?:run { Log.w(TAG, "特征不存在"); return@let }
                    queue?.enQueue(GattOperation(OperationType.WRITE,led_char,null,byteArrayOf(0x01)){})
                    queue?.enQueue(GattOperation(OperationType.MTU,null,null, null,mtu = 517, onResult = {}))
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

    private fun appendLog(msg: String) {
        sb.append("💬 ").append(msg).append("\n")
        tvResult.text = sb.toString()
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN])
    override fun onDestroy() {
        super.onDestroy()
        queue?.clearQueue()
        stopScan()
        curGatt?.disconnect()
        curGatt?.close()
        curGatt = null
        retryHandler.removeCallbacks(retryRunnable)
    }
}

