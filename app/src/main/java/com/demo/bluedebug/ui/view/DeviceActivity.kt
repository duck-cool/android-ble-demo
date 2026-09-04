package com.demo.bluedebug.ui.view

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.graphics.Color
import android.os.Bundle
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
import com.demo.bluedebug.MainActivity.Companion.TAG
import com.demo.bluedebug.R
import com.demo.bluedebug.adpater.DeviceServiceAdapter
import com.demo.bluedebug.data.BleInfoItem
import com.demo.bluedebug.data.ConnState
import com.demo.bluedebug.data.MsgLevel
import com.demo.bluedebug.ui.BaseActivity
import com.demo.bluedebug.ui.view.model.BleDeviceViewModel
import com.demo.bluedebug.ui.view.model.ExpandableViewModel
import com.demo.bluedebug.utils.parseHex
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeviceActivity: BaseActivity() {

    private val viewModel: ExpandableViewModel by viewModels()

    private val bleViewModel: BleDeviceViewModel by viewModels()

    private lateinit var serviceRv: RecyclerView
    
    private lateinit var serviceAdapter: DeviceServiceAdapter

    private lateinit var tvLog: TextView

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate: DeviceActivity")
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
                                "Read" -> bleViewModel.read(info.service_uuid,info.uuid,info.displayName)
                                "Write" -> showWriteDialog(info)
                                "Notify" -> bleViewModel.notify(info.service_uuid, info.uuid, info.displayName,true)
                                else -> info.operationType = null
                            }
                            Toast.makeText(this, "选择了: $selectedDevice", Toast.LENGTH_SHORT).show()
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
        
        val bleDevice = intent.extras?.get("ble_device") as BluetoothDevice
        bleViewModel.connState.observe(this){state ->
            when(state){
                ConnState.CONNECTED -> appendLog("✅ 连接成功，正在发现服务…")
                ConnState.DISCONNECTED -> appendLog("❌ 连接断开，准备重连…")
                ConnState.CONNECTING -> appendLog("✅ 正在连接设备…")
                else -> {}
            }
        }
        bleViewModel.serviceData.observe(this){ list->
            list?.let {
                viewModel.loadDate(it)
                serviceAdapter.submitList(it)
            }
        }

        bleViewModel.message.observe(this){ msg ->
            when(msg.level){
                MsgLevel.LOG -> appendLog(msg.text)
                MsgLevel.TOAST -> Toast.makeText(this,msg.text, Toast.LENGTH_SHORT).show()
                MsgLevel.BOTH -> {
                    appendLog(msg.text)
                    Toast.makeText(this,msg.text, Toast.LENGTH_SHORT).show()
                }
            }
        }
        bleViewModel.start(bleDevice)

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

    // Q7：Write 数据输入框——hex 输入 + 实时错误标注（TextWatcher 边输边校验）
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun showWriteDialog(info: BleInfoItem.CharacteristicUiModel) {
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
                    bleViewModel.write(info.service_uuid,info.uuid,info.displayName,bytes)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}