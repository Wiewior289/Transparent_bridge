package com.example.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.PlcBridgeApplication
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream

class PlcVpnService : VpnService() {

    companion object {
        const val NOTIFICATION_ID = 1002
        private const val TAG = "PlcVpnService"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val vpnScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tunnelJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        startVpnTunnel()
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, PlcBridgeApplication.CHANNEL_ID)
            .setContentTitle("PLC Virtual L3 TUN Active")
            .setContentText("Subnet 192.168.1.0/24 routed to USB Ethernet adapter")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startVpnTunnel() {
        vpnScope.launch {
            try {
                val builder = Builder()
                    .setSession("PlcBridgeL3")
                    .addAddress("192.168.1.99", 24)
                    .addRoute("192.168.1.0", 24)
                    .setMtu(1400)
                    .setBlocking(true)

                vpnInterface = builder.establish()
                Log.i(TAG, "VPN TUN interface established without root! FD: ${vpnInterface?.fd}")

                val pfd = vpnInterface ?: return@launch
                val inputStream = FileInputStream(pfd.fileDescriptor)
                val outputStream = FileOutputStream(pfd.fileDescriptor)

                val buffer = ByteArray(1600)
                tunnelJob = launch {
                    while (isActive) {
                        val read = inputStream.read(buffer)
                        if (read > 0) {
                            // Packets from local apps destined to 192.168.1.0/24
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VpnService: ${e.message}", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tunnelJob?.cancel()
        vpnScope.cancel()
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
