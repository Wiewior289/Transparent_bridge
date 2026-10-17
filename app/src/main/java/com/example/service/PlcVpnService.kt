package com.example.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
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
import java.io.IOException

class PlcVpnService : VpnService() {

    companion object {
        const val NOTIFICATION_ID = 1002
        const val ACTION_START_VPN = "com.example.service.action.START_VPN"
        const val ACTION_STOP_VPN = "com.example.service.action.STOP_VPN"
        private const val TAG = "PlcVpnService"

        @Volatile
        var instance: PlcVpnService? = null
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnOutputStream: FileOutputStream? = null
    private val vpnScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tunnelReadJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_VPN
        when (action) {
            ACTION_START_VPN -> {
                startForegroundNotification()
                startVpnTunnel()
            }
            ACTION_STOP_VPN -> {
                stopVpnTunnel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
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
            .setContentTitle("PLC Userland L3 TUN Active (Zero-Root)")
            .setContentText("Routing subnet 192.168.1.0/24 ⇄ USB Ethernet (NAT Masquerade)")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startVpnTunnel() {
        stopVpnTunnel()

        vpnScope.launch {
            try {
                // Establish virtual TUN interface for subnet 192.168.1.0/24
                val builder = Builder()
                    .setSession("PlcBridgeL3")
                    .addAddress("192.168.1.99", 24)
                    .addRoute("192.168.1.0", 24) // Captures all traffic for HMI / PLC
                    .setMtu(1400)
                    .setBlocking(true)

                val pfd = builder.establish() ?: run {
                    Log.e(TAG, "Failed to establish VpnService TUN interface")
                    return@launch
                }
                vpnInterface = pfd
                vpnOutputStream = FileOutputStream(pfd.fileDescriptor)
                val inputStream = FileInputStream(pfd.fileDescriptor)

                Log.i(TAG, "VPN TUN interface established! Subnet 192.168.1.0/24 bound. FD: ${pfd.fd}")

                val buffer = ByteArray(1600)
                tunnelReadJob = launch {
                    while (isActive) {
                        try {
                            val bytesRead = inputStream.read(buffer)
                            if (bytesRead > 0) {
                                // Raw IPv4 packet destined to 192.168.1.X from local or Tailscale subnet routing
                                PlcBridgeService.instance?.handleOutgoingIpPacketFromTun(buffer, bytesRead)
                            }
                        } catch (e: IOException) {
                            if (isActive) Log.w(TAG, "TUN read error: ${e.message}")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting VpnService TUN: ${e.message}", e)
            }
        }
    }

    /**
     * Injects a de-masqueraded IPv4 packet returning from HMI into the TUN interface.
     */
    fun injectPacketToTun(ipPacket: ByteArray, length: Int) {
        val out = vpnOutputStream ?: return
        try {
            synchronized(out) {
                out.write(ipPacket, 0, length)
                out.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error injecting packet into TUN: ${e.message}")
        }
    }

    private fun stopVpnTunnel() {
        tunnelReadJob?.cancel()
        try {
            vpnOutputStream?.close()
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnOutputStream = null
        vpnInterface = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpnTunnel()
        vpnScope.cancel()
        instance = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
