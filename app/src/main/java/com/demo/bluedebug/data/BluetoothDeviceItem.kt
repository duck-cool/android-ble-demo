package com.demo.bluedebug.data

import android.bluetooth.BluetoothDevice

class BluetoothDeviceItem(private val name: String, private val mac: String,private val rssi: Int,private val device:BluetoothDevice) {


    override fun toString(): String {
        return "BluetoothDeviceItem(name='$name', mac='$mac', rssi=$rssi, device=$device)"
    }

    fun getDevice(): BluetoothDevice = device

    fun getDeviceInfo(): String{
        return "📡 $name | $mac | 信号: $rssi dBm\n"
    }
}