package com.example.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.PlcBridgeApplication
import com.example.R
import com.example.core.model.ArpEntry
import com.example.core.model.BridgeState
import com.example.core.model.BridgeStats
import com.example.core.model.EthernetConfig
import com.example.core.model.PacketLog
import com.example.core.model.TailscaleConfig
import com.example.core.net.ArpEngine
import com.example.core.net.FtpsAlg
import com.example.core.net.MssClamper
import com.example.core.net.RingBuffer
import com.example.core.usb.UsbEthernetManager
import com.example.core.vpn.TailscaleSubnetManager
import com.example.core.vpn.UserlandIndustrialProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class PlcBridgeService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.example.service.action.START"
        const val ACTION_STOP = "com.example.service.action.STOP"
        private const val TAG = "PlcBridgeService"
    }

    inner class LocalBinder : Binder() {
        val service: PlcBridgeService get() = this@PlcBridgeService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var wakeLock: PowerManager.WakeLock? = null
    lateinit var usbManager: UsbEthernetManager
        private set
    lateinit var arpEngine: ArpEngine
        private set
    lateinit var ftpsAlg: FtpsAlg
        private set
    lateinit var tailscaleManager: TailscaleSubnetManager
        private set
    private lateinit var industrialProxy: UserlandIndustrialProxy
    private val ringBuffer = RingBuffer(capacity = 2048, blockSize = 1600)

    private val _bridgeState = MutableStateFlow(BridgeState.STOPPED)
    val bridgeState: StateFlow<BridgeState> = _bridgeState.asStateFlow()

    private val _stats = MutableStateFlow(BridgeStats())
    val stats: StateFlow<BridgeStats> = _stats.asStateFlow()

    private val _packetLogs = MutableStateFlow<List<PacketLog>>(emptyList())
    val packetLogs: StateFlow<List<PacketLog>> = _packetLogs.asStateFlow()

    var ethernetConfig = EthernetConfig()
    private val packetIdCounter = AtomicLong(0L)

    private var rxJob: Job? = null
    private var txJob: Job? = null
    private var statsJob: Job? = null

    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var lastPackets = 0L

    override fun onCreate() {
        super.onCreate()
        usbManager = UsbEthernetManager(this)
        arpEngine = ArpEngine(
            virtualMac = ArpEngine.parseMac(ethernetConfig.virtualMac),
            adapterIp = ArpEngine.parseIp(ethernetConfig.adapterIp)
        )
        ftpsAlg = FtpsAlg()
        tailscaleManager = TailscaleSubnetManager()
        industrialProxy = UserlandIndustrialProxy(ftpsAlg) { proto, src, dst, len, info ->
            logPacket(proto, src, dst, len, info, "", false)
        }

        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_START -> {
                startForegroundNotification()
                startBridge()
            }
            ACTION_STOP -> {
                stopBridge()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PlcBridge:HighPriorityService").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "Acquired PARTIAL_WAKE_LOCK to prevent CPU throttling")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "Released PARTIAL_WAKE_LOCK")
            }
        }
        wakeLock = null
    }

    private fun startForegroundNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, PlcBridgeApplication.CHANNEL_ID)
            .setContentTitle("PLC Bridge Active (Zero-Root)")
            .setContentText("Transparent tunnel: ${ethernetConfig.adapterIp} ⇄ ${ethernetConfig.targetPlcIp}")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun startBridge(device: UsbDevice? = null) {
        if (_bridgeState.value == BridgeState.RUNNING) return
        _bridgeState.value = BridgeState.STARTING

        serviceScope.launch {
            val targetDevice = device ?: usbManager.getFirstEthernetDevice()
            if (targetDevice != null) {
                val connected = usbManager.connectToDevice(targetDevice)
                if (connected) {
                    Log.i(TAG, "USB Adapter connected: ${targetDevice.deviceName}")
                }
            }

            // Start Userland Industrial Proxy
            industrialProxy.start(ethernetConfig)

            // Start Tailscale Subnet Router simulation/status
            tailscaleManager.startSubnetRouter(
                tailscaleManager.config.value.authKey,
                tailscaleManager.config.value.nodeName,
                tailscaleManager.config.value.advertisedRoutes
            )

            // Start USB Packet RX Loop
            startRxLoop()

            // Start Stats Calculator Loop
            startStatsLoop()

            _bridgeState.value = BridgeState.RUNNING
            logPacket("SYS", "0.0.0.0", ethernetConfig.targetPlcIp, 0, "Transparent Bridge Started", "", false)

            // Send initial Gratuitous ARP to announce our presence on the cable
            sendGratuitousArp()
        }
    }

    private fun startRxLoop() {
        rxJob?.cancel()
        rxJob = serviceScope.launch {
            val rxBuffer = ByteArray(1600)
            while (isActive) {
                if (usbManager.isConnected) {
                    val bytesRead = usbManager.receiveFrame(rxBuffer, 25)
                    if (bytesRead > 0) {
                        handleIncomingEthernetFrame(rxBuffer, bytesRead)
                    }
                } else {
                    delay(500)
                }
            }
        }
    }

    private fun handleIncomingEthernetFrame(frame: ByteArray, length: Int) {
        if (length < 14) return
        val etherType = ((frame[12].toInt() and 0xFF) shl 8) or (frame[13].toInt() and 0xFF)

        when (etherType) {
            0x0806 -> { // ARP Frame
                val replyFrame = arpEngine.processIncomingFrame(frame, length)
                val senderIp = ArpEngine.formatIp(frame, 28)
                val targetIp = ArpEngine.formatIp(frame, 38)
                logPacket("ARP", senderIp, targetIp, length, "Who-has $targetIp", bytesToHex(frame, 0, minOf(length, 32)), false)

                // If ARP request targeted us, send synthesized reply back to USB
                if (replyFrame != null) {
                    usbManager.sendFrame(replyFrame, replyFrame.size)
                    logPacket("ARP-REP", ethernetConfig.adapterIp, senderIp, replyFrame.size, "Is-at ${ethernetConfig.virtualMac}", "", false)
                }
            }
            0x0800 -> { // IPv4 Packet
                val ipOffset = 14
                val ipLen = length - 14
                var isClamped = false

                if (ethernetConfig.isMssClampingEnabled) {
                    isClamped = MssClamper.clampMssInPlace(frame, ipOffset, ipLen, ethernetConfig.targetMss)
                }

                val protoNum = frame[ipOffset + 9].toInt() and 0xFF
                val srcIp = ArpEngine.formatIp(frame, ipOffset + 12)
                val dstIp = ArpEngine.formatIp(frame, ipOffset + 16)
                val protoStr = if (protoNum == 6) "TCP" else if (protoNum == 17) "UDP" else "IPv4"

                logPacket(protoStr, srcIp, dstIp, length, if (isClamped) "MSS clamped to ${ethernetConfig.targetMss}" else "Data Frame", bytesToHex(frame, 0, minOf(length, 32)), isClamped)
                
                // Write into pre-allocated ring buffer
                ringBuffer.write(frame, 0, length)
            }
        }
    }

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                val rx = industrialProxy.totalRxBytes.get()
                val tx = industrialProxy.totalTxBytes.get()
                val pkts = industrialProxy.totalPackets.get()
                val activeConns = industrialProxy.getActiveConnectionCount()

                val rxDelta = (rx - lastRxBytes).coerceAtLeast(0L)
                val txDelta = (tx - lastTxBytes).coerceAtLeast(0L)
                val pps = (pkts - lastPackets).coerceAtLeast(0L).toInt()

                lastRxBytes = rx
                lastTxBytes = tx
                lastPackets = pkts

                _stats.value = BridgeStats(
                    rxBytes = rx,
                    txBytes = tx,
                    rxPackets = pkts,
                    txPackets = pkts,
                    droppedPackets = 0L,
                    currentRxKBs = rxDelta / 1024.0,
                    currentTxKBs = txDelta / 1024.0,
                    currentPps = pps,
                    activeConnections = activeConns,
                    lastPacketTimestamp = System.currentTimeMillis()
                )
            }
        }
    }

    fun sendGratuitousArp() {
        serviceScope.launch {
            val req = arpEngine.buildArpRequest(ArpEngine.parseIp(ethernetConfig.targetPlcIp))
            if (usbManager.isConnected) {
                usbManager.sendFrame(req, req.size)
                logPacket("ARP-PROBE", ethernetConfig.adapterIp, ethernetConfig.targetPlcIp, req.size, "Gratuitous ARP broadcast", "", false)
            }
        }
    }

    fun probePlc() {
        sendGratuitousArp()
    }

    fun logPacket(protocol: String, src: String, dst: String, length: Int, info: String, hexPreview: String, isClamped: Boolean) {
        val entry = PacketLog(
            id = packetIdCounter.incrementAndGet(),
            timestamp = System.currentTimeMillis(),
            protocol = protocol,
            src = src,
            dst = dst,
            length = length,
            info = info,
            hexPreview = hexPreview,
            isClamped = isClamped
        )
        val current = _packetLogs.value
        _packetLogs.value = (listOf(entry) + current).take(200)
    }

    fun clearLogs() {
        _packetLogs.value = emptyList()
    }

    fun resetStats() {
        industrialProxy.totalRxBytes.set(0L)
        industrialProxy.totalTxBytes.set(0L)
        industrialProxy.totalPackets.set(0L)
        ringBuffer.clear()
        _stats.value = BridgeStats()
    }

    fun stopBridge() {
        rxJob?.cancel()
        txJob?.cancel()
        statsJob?.cancel()
        industrialProxy.stop()
        usbManager.disconnect()
        tailscaleManager.stopSubnetRouter()
        _bridgeState.value = BridgeState.STOPPED
        logPacket("SYS", "0.0.0.0", "0.0.0.0", 0, "Bridge Stopped", "", false)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBridge()
        releaseWakeLock()
        serviceScope.cancel()
    }

    private fun bytesToHex(bytes: ByteArray, offset: Int, length: Int): String {
        val sb = StringBuilder()
        for (i in offset until offset + length) {
            sb.append("%02X ".format(bytes[i]))
        }
        return sb.toString().trim()
    }
}
