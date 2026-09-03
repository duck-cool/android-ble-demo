package com.demo.bluedebug

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.demo.bluedebug.adpater.BluetoothDeviceAdapter
import com.demo.bluedebug.data.BluetoothDeviceItem
import com.demo.bluedebug.ui.BaseActivity
import com.demo.bluedebug.ui.view.DeviceActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

class MainActivity : BaseActivity() {
    companion object{
        val TAG = MainActivity::class.simpleName
        val MAX_RETRY_MILLIS: Long = 30000
    }

    private lateinit var btnScan: Button
    private lateinit var tvResult: TextView

    private lateinit var rvDevice: RecyclerView
    private lateinit var bluetoothAdapter: BluetoothDeviceAdapter

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

    @SuppressLint("MissingInflatedId", "MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnScan = findViewById(R.id.btnScan)
        tvResult = findViewById(R.id.tvResult)
        rvDevice = findViewById(R.id.rv_device)
        bluetoothAdapter = BluetoothDeviceAdapter(null){ item ->
            val intent = Intent(this, DeviceActivity::class.java)
            intent.putExtra("ble_device",item)
            startActivity(intent)

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
        scanner?.startScan(scanCallback)
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
            Log.i(TAG, "onScanResult: name='$name', mac='$mac', rssi=$rssi, device=$device")
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

    private fun appendLog(msg: String) {
        sb.append("💬 ").append(msg).append("\n")
        tvResult.text = sb.toString()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    override fun onStop() {
        super.onStop()
        stopScan()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            waitForAnswer(3000)
        }
    }

    suspend fun waitForAnswer(ms: Long) : String = suspendCancellableCoroutine { cout ->
        Handler(Looper.getMainLooper()).postDelayed({
            cout.resume("答案来了",{})
            Log.i(TAG, "onResume: $ms")
        },ms)

    }

}

