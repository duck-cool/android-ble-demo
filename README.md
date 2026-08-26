# BlueDebugTool

一个基于 Android 原生 BLE API 的蓝牙调试工具，用于扫描附近 BLE 设备、建立 GATT 连接并查看服务特征。

## 功能特性

- 📡 BLE 设备扫描（`BluetoothLeScanner`）
- 🔗 GATT 连接与断开（`BluetoothGattCallback`）
- 🧩 服务发现：连接后自动列出服务的 Service UUID
- ⏱️ GATT 操作队列（`GattOperationQueue`）：
  - 读写 characteristic / descriptor 操作按队列串行执行，避免并发冲突
  - 单次操作超时保护（10s）
  - 写操作失败自动重试（最多 5 次）

## 技术栈

- 语言：Kotlin
- 构建：Gradle（Android Gradle Plugin）
- 最低 SDK：见 `app/build.gradle`

## 项目结构

```
app/src/main/java/com/demo/bluedebug/
├── MainActivity.kt                 # 主界面：扫描、连接、服务列表
├── adpater/
│   └── BluetoothDeviceAdapter.kt   # 扫描结果列表适配器
└── data/
    ├── BluetoothDeviceItem.kt      # 设备数据模型
    ├── GattOperation.kt            # GATT 操作模型（读/写/通知）
    ├── GattOperationQueue.kt       # GATT 操作队列（串行 + 超时 + 重试）
    └── GattResult.kt               # 操作结果封装
```

## 使用说明

1. 用 Android Studio 打开本项目
2. 连接一台 Android 真机（BLE 需要真机，模拟器不支持）
3. 运行时授予定位权限（Android 6.0+ 扫描 BLE 需要）
4. 打开应用 → 开始扫描 → 点击设备连接

