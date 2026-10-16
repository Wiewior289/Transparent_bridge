package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class PlcBridgeApplication : Application() {

    companion object {
        const val CHANNEL_ID = "plc_bridge_service_channel"
        const val CHANNEL_NAME = "Industrial Bridge Service"
        lateinit var instance: PlcBridgeApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Running transparent USB-Ethernet VPN bridge for PLC/HMI (GP-Pro EX)"
                setShowBadge(true)
                enableVibration(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
