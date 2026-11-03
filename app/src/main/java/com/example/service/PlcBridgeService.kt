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
import androidx.annotation.Keep
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.PlcBridgeApplication
import com.example.R
import com.example.core.model.ArpEntry
import com.example.core.model.BridgeState
import com.example.core.model.BridgeStats
import com.example.core.model.EthernetConfig
import com.example.core.model.PacketLog
import com.example.core.net.ArpEngine
import com.example.core.net.UserlandNatEngine
import com.example.core.usb.UsbEthernetManager
import com.example.core.vpn.TailscaleBridgeJni
import com.example.core.vpn.TailscaleSubnetManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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

        @Volatile
        var instance: PlcBridgeService? = null
            private set
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
    lateinit var natEngine: UserlandNatEngine
        private set
    lateinit var tailscaleManager: TailscaleSubnetManager
        private set


    private val _bridgeState = MutableStateFlow(BridgeState.STOPPED)
    val bridgeState: StateFlow<BridgeState> = _bridgeState.asStateFlow()

    private val _stats = MutableStateFlow(BridgeStats())
    val stats: StateFlow<BridgeStats> = _stats.asStateFlow()

    private val _packetLogs = MutableStateFlow<List<PacketLog>>(emptyList())
    val packetLogs: StateFlow<List<PacketLog>> = _packetLogs.asStateFlow()

    var ethernetConfig = EthernetConfig()
        set(value) {
            field = value
            if (::natEngine.isInitialized) natEngine.config = value
            if (::arpEngine.isInitialized) {
                arpEngine.setAdapterIp(ArpEngine.parseIp(value.adapterIp))
            }
        }

    private val packetIdCounter = AtomicLong(0L)
    val totalRxBytes = AtomicLong(0L)
    val totalTxBytes = AtomicLong(0L)
    val totalRxPackets = AtomicLong(0L)
    val totalTxPackets = AtomicLong(0L)
    val totalDroppedPackets = AtomicLong(0L)

    private var rxJob: Job? = null
    private var statsJob: Job? = null

    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var lastRxPackets = 0L
    private var lastTxPackets = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        usbManager = UsbEthernetManager(this)
        arpEngine = ArpEngine(
            virtualMac = ArpEngine.parseMac(ethernetConfig.virtualMac),
            adapterIp = ArpEngine.parseIp(ethernetConfig.adapterIp)
        )
        natEngine = UserlandNatEngine(ethernetConfig)
        tailscaleManager = TailscaleSubnetManager(this)

        if (TailscaleBridgeJni.isLibraryLoaded) {
            val result = TailscaleBridgeJni.registerPacketListener(this)
            if (result != 0) {
                Log.e(TAG, "Failed to register Tailscale packet listener: $result")
            } else {
                Log.i(TAG, "Successfully registered Tailscale packet listener with native Go core")
            }
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
            Log.i(TAG, "Acquired PARTIAL_WAKE_LOCK to prevent CPU sleep")
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
            .setContentTitle("PlcBridge Active (Zero-Root NAT)")
            .setContentText("Masquerade SNAT: ${ethernetConfig.adapterIp} ⇄ HMI ${ethernetConfig.targetPlcIp}")
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

    private var startJob: Job? = null

    @Keep
    fun onTailscalePacketReceived(packet: ByteArray, length: Int) {
        if (length < 20 || length > packet.size) return
        handleOutgoingIpPacketFromTun(packet, length)
    }

    @Synchronized
    fun startBridge(device: UsbDevice? = null) {
        if (_bridgeState.value == BridgeState.RUNNING ||
            _bridgeState.value == BridgeState.STARTING
        ) {
            Log.i(TAG, "Ignoring duplicate start: ${_bridgeState.value}")
            return
        }

        _bridgeState.value = BridgeState.STARTING

        startJob = serviceScope.launch {
            try {
                val targetDevice = device
                    ?: usbManager.getFirstEthernetDevice()
                    ?: throw IllegalStateException("No USB Ethernet device found")

                if (!usbManager.connectToDevice(targetDevice)) {
                    throw IllegalStateException(
                        "Failed to connect USB device: ${targetDevice.deviceName}"
                    )
                }

                // For CDC-ECM, Ethernet frames must use the MAC advertised by
                // the adapter's CDC iMACAddress descriptor, not a synthetic UI default.
                usbManager.adapterMacAddress?.let { mac ->
                    arpEngine.setVirtualMac(ArpEngine.parseMac(mac))
                    Log.i(TAG, "Using adapter hardware MAC for Ethernet/ARP: $mac")
                } ?: Log.w(TAG, "USB backend did not expose a hardware MAC; configured fallback MAC remains active.")

                currentCoroutineContext().ensureActive()

                if (TailscaleBridgeJni.isLibraryLoaded) {
                    val regRes = TailscaleBridgeJni.registerPacketListener(this@PlcBridgeService)
                    Log.i(TAG, "Registered JNI packet listener (code $regRes)")
                }

                val config = tailscaleManager.config.value
                if (config.authKey.trim().isEmpty()) {
                    throw IllegalStateException("Tailscale AuthKey is required to connect to Tailnet. Please provide tskey-auth-... in Tailscale settings.")
                }

                tailscaleManager.startSubnetRouter(
                    config.authKey,
                    config.nodeName,
                    config.advertisedRoutes
                )

                currentCoroutineContext().ensureActive()

                if (!tailscaleManager.config.value.isSubnetRoutingActive) {
                    throw IllegalStateException(
                        "Tailscale backend did not start successfully"
                    )
                }

                startRxLoop()
                startStatsLoop()

                _bridgeState.value = BridgeState.RUNNING

                logPacket(
                    "SYS",
                    "0.0.0.0",
                    ethernetConfig.targetPlcIp,
                    0,
                    "Bridge Started",
                    "",
                    false
                )

                sendGratuitousArp()
            } catch (e: CancellationException) {
                usbManager.disconnect()
                tailscaleManager.stopSubnetRouter()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Bridge startup failed", e)

                rxJob?.cancel()
                statsJob?.cancel()
                usbManager.disconnect()
                tailscaleManager.stopSubnetRouter()

                _bridgeState.value = BridgeState.ERROR
            }
        }
    }

    /**
     * Handles outgoing IPv4 packets from TUN / Tailscale / Host.
     * Applies validated Userland SNAT (Masquerade) to the USB adapter address and clamps TCP MSS to the effective MTU,
     * encapsulates in 14-byte Ethernet frame, and sends to USB Bulk OUT.
     */
    fun handleOutgoingIpPacketFromTun(packet: ByteArray, length: Int) {
        if (length < 20 || length > packet.size) {
            totalDroppedPackets.incrementAndGet()
            return
        }
        val origSrc = ArpEngine.formatIp(packet, 12)
        val targetDst = ArpEngine.formatIp(packet, 16)

        // SNAT validates IPv4 and the supported transport before any frame is emitted.
        if (!natEngine.processOutgoingPacket(packet, 0, length)) {
            totalDroppedPackets.incrementAndGet()
            logPacket("IP-DROP", origSrc, targetDst, length, "Unsupported or invalid IPv4 flow", "", false)
            return
        }

        val protoNum = packet[9].toInt() and 0xFF
        val protoStr = when (protoNum) {
            6 -> "TCP-OUT"
            17 -> "UDP-OUT"
            1 -> "ICMP-OUT"
            else -> "IP-OUT"
        }
        logPacket(protoStr, origSrc, targetDst, length,
            "SNAT -> ${ethernetConfig.adapterIp}", "", false)

        val dstMac = arpEngine.getMacForIp(targetDst)
        if (dstMac != null) {
            val frame = arpEngine.wrapInEthernetHeader(packet, 0, length, dstMac)
            transmitEthernetFrame(frame, "IPv4 -> $targetDst")
        } else {
            Log.i(TAG, "ARP cache miss for $targetDst. Queueing packet and resolving peer MAC.")
            if (!arpEngine.queuePendingPacket(targetDst, packet.copyOfRange(0, length))) {
                totalDroppedPackets.incrementAndGet()
                logPacket("IP-DROP", origSrc, targetDst, length, "ARP pending queue full", "", false)
                return
            }
            sendArpRequestFor(targetDst)
        }
    }

    private fun transmitEthernetFrame(frame: ByteArray, description: String): Boolean {
        if (!usbManager.isConnected) {
            totalDroppedPackets.incrementAndGet()
            Log.w(TAG, "TX drop: USB transport is not connected ($description)")
            return false
        }
        val result = usbManager.sendFrame(frame, frame.size)
        if (result != frame.size) {
            totalDroppedPackets.incrementAndGet()
            Log.w(TAG, "TX incomplete: result=$result expected=${frame.size} ($description)")
            return false
        }
        totalTxBytes.addAndGet(frame.size.toLong())
        totalTxPackets.incrementAndGet()
        return true
    }

    private fun startRxLoop() {
        rxJob?.cancel()
        rxJob = serviceScope.launch {
            val rxBuffer = ByteArray(1600)
            while (isActive) {
                if (usbManager.isConnected) {
                    val bytesRead = usbManager.receiveFrame(rxBuffer, 20)
                    if (bytesRead > 0) {
                        totalRxBytes.addAndGet(bytesRead.toLong())
                        totalRxPackets.incrementAndGet()
                        handleIncomingEthernetFrame(rxBuffer, bytesRead)
                    }
                } else {
                    delay(300)
                }
            }
        }
    }

    private fun handleIncomingEthernetFrame(frame: ByteArray, length: Int) {
        if (length < 14) {
            totalDroppedPackets.incrementAndGet()
            return
        }
        val etherType = ((frame[12].toInt() and 0xFF) shl 8) or (frame[13].toInt() and 0xFF)

        when (etherType) {
            0x0806 -> { // ARP Frame
                val senderIp = ArpEngine.formatIp(frame, 28)
                val targetIp = ArpEngine.formatIp(frame, 38)
                val replyFrame = arpEngine.processIncomingFrame(frame, length)
                logPacket("ARP", senderIp, targetIp, length, "Who-has $targetIp", bytesToHex(frame, 0, minOf(length, 32)), false)

                // Respond to an ARP request for our configured adapter IP.
                if (replyFrame != null) {
                    if (transmitEthernetFrame(replyFrame, "ARP reply to $senderIp")) {
                        logPacket("ARP-REP", ethernetConfig.adapterIp, senderIp, replyFrame.size,
                            "Is-at ${ethernetConfig.virtualMac}", "", false)
                    }
                }

                // Check if any pending packets were waiting for senderIp's MAC!
                val senderMac = arpEngine.getMacForIp(senderIp)
                if (senderMac != null) {
                    val pending = arpEngine.getAndClearPendingPackets(senderIp)
                    for (pendingIpPacket in pending) {
                        val outFrame = arpEngine.wrapInEthernetHeader(pendingIpPacket, 0, pendingIpPacket.size, senderMac)
                        if (transmitEthernetFrame(outFrame, "queued IPv4 -> $senderIp")) {
                            Log.i(TAG, "Flushed queued IP packet (${pendingIpPacket.size}B) to $senderIp")
                        }
                    }
                }
            }
            0x0800 -> { // IPv4 Packet
                val ipOffset = 14
                val frameIpBytes = length - ipOffset
                if (frameIpBytes < 20) {
                    totalDroppedPackets.incrementAndGet()
                    return
                }

                // Ethernet frames may contain padding. The TUN receives exactly IPv4 Total Length.
                val ipTotalLength = ((frame[ipOffset + 2].toInt() and 0xFF) shl 8) or
                    (frame[ipOffset + 3].toInt() and 0xFF)
                if (ipTotalLength < 20 || ipTotalLength > frameIpBytes) {
                    totalDroppedPackets.incrementAndGet()
                    logPacket("IP-DROP", "0.0.0.0", "0.0.0.0", frameIpBytes,
                        "Invalid IPv4 Total Length=$ipTotalLength", "", false)
                    return
                }
                val ipPacket = ByteArray(ipTotalLength)
                System.arraycopy(frame, ipOffset, ipPacket, 0, ipTotalLength)

                val srcIp = ArpEngine.formatIp(ipPacket, 12)
                val dstIp = ArpEngine.formatIp(ipPacket, 16)

                if (!natEngine.processIncomingPacket(ipPacket, 0, ipTotalLength)) {
                    totalDroppedPackets.incrementAndGet()
                    logPacket("IP-DROP", srcIp, dstIp, ipTotalLength,
                        "No exact reverse NAT flow or invalid IPv4 packet", "", false)
                    return
                }

                val restoredDst = ArpEngine.formatIp(ipPacket, 16)
                val protoNum = ipPacket[9].toInt() and 0xFF
                val protoStr = when (protoNum) {
                    6 -> "TCP-IN"
                    17 -> "UDP-IN"
                    1 -> "ICMP-IN"
                    else -> "IP-IN"
                }
                logPacket(protoStr, srcIp, restoredDst, ipTotalLength,
                    "DNAT -> $restoredDst", "", false)

                val injected = tailscaleManager.injectPacketFromUsb(ipPacket, ipTotalLength)
                if (injected != ipTotalLength) {
                    totalDroppedPackets.incrementAndGet()
                    Log.w(TAG, "Tailscale rejected USB packet: result=$injected, length=$ipTotalLength")
                }
            }
            else -> totalDroppedPackets.incrementAndGet()
        }
    }

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                val rx = totalRxBytes.get()
                val tx = totalTxBytes.get()
                val rxPackets = totalRxPackets.get()
                val txPackets = totalTxPackets.get()
                val dropped = totalDroppedPackets.get()
                val activeConns = natEngine.activeSessionCount

                val rxDelta = (rx - lastRxBytes).coerceAtLeast(0L)
                val txDelta = (tx - lastTxBytes).coerceAtLeast(0L)
                val rxPacketDelta = (rxPackets - lastRxPackets).coerceAtLeast(0L)
                val txPacketDelta = (txPackets - lastTxPackets).coerceAtLeast(0L)
                val pps = (rxPacketDelta + txPacketDelta).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

                lastRxBytes = rx
                lastTxBytes = tx
                lastRxPackets = rxPackets
                lastTxPackets = txPackets

                _stats.value = BridgeStats(
                    rxBytes = rx,
                    txBytes = tx,
                    rxPackets = rxPackets,
                    txPackets = txPackets,
                    droppedPackets = dropped,
                    currentRxKBs = rxDelta / 1024.0,
                    currentTxKBs = txDelta / 1024.0,
                    currentPps = pps,
                    activeConnections = activeConns,
                    lastPacketTimestamp = System.currentTimeMillis()
                )
            }
        }
    }

    // Compatibility name retained for the UI; this is an ARP who-has probe, not gratuitous ARP.
    fun sendGratuitousArp() {
        sendArpRequestFor(ethernetConfig.targetPlcIp)
    }

    fun sendArpRequestFor(targetIp: String) {
        serviceScope.launch {
            val req = arpEngine.buildArpRequest(ArpEngine.parseIp(targetIp))
            transmitEthernetFrame(req, "ARP who-has $targetIp")
            logPacket("ARP-REQ", ethernetConfig.adapterIp, targetIp, req.size, "Who-has $targetIp", "", false)
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
        totalRxBytes.set(0L)
        totalTxBytes.set(0L)
        totalRxPackets.set(0L)
        totalTxPackets.set(0L)
        totalDroppedPackets.set(0L)
        lastRxBytes = 0L
        lastTxBytes = 0L
        lastRxPackets = 0L
        lastTxPackets = 0L
        _stats.value = BridgeStats()
    }

    @Synchronized
    fun stopBridge() {
        startJob?.cancel()
        startJob = null

        rxJob?.cancel()
        statsJob?.cancel()

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
        instance = null
    }

    private fun bytesToHex(bytes: ByteArray, offset: Int, length: Int): String {
        val sb = StringBuilder()
        for (i in offset until offset + length) {
            sb.append("%02X ".format(bytes[i]))
        }
        return sb.toString().trim()
    }
}
