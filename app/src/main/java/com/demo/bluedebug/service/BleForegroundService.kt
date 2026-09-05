package com.demo.bluedebug.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.demo.bluedebug.R

class BleForegroundService: Service() {
    
    companion object{
        val TAG = BleForegroundService::class.simpleName
        val BLE_NOTIFICATION_ID = 1001
        const val BLE_CHANNEL_ID = "ble_keep_alive"
    }

    override fun onCreate() {
        super.onCreate()
        val notificationChannel =
            NotificationChannel(BLE_CHANNEL_ID, "BLE 保活服务", NotificationManager.IMPORTANCE_DEFAULT)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(notificationChannel)

    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q){
            startForeground(
                BLE_NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }else{
            startForeground(BLE_NOTIFICATION_ID,createNotification())
        }
        Log.i(TAG, "onStartCommand: ")
        return super.onStartCommand(intent, flags, startId)
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        Log.i(TAG, "onBind: ")
        return null
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "onUnbind: ")
        return super.onUnbind(intent)
    }

    private fun createNotification(): Notification{
        return NotificationCompat.Builder(baseContext,BLE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ble_launcher_foreground)
            .setContentTitle("BLE Tool")
            .setContentText("Ble保活 | 状态: 已连接")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy: ")
    }
    
}