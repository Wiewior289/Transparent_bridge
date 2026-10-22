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
import com.example.core.net.FtpsAlg
import com.example.core.net.RingBuffer
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
    lateinit var ftpsAlg: FtpsAlg
        private set
    lateinit var natEngine: UserlandNatEngine
        private set
    lateinit var tailscaleManager: TailscaleSubnetManager
        private set

    private val ringBuffer = RingBuffer(capacity = 2048, blockSize = 1600)

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
                arpEngine.adapterIp = ArpEngine.parseIp(value.adapterIp)
            }
        }

    private val packetIdCounter = AtomicLong(0L)
    val totalRxBytes = AtomicLong(0L)
    val totalTxBytes = AtomicLong(0L)
    val totalPackets = AtomicLong(0L)

    private var rxJob: Job? = null
    private var statsJob: Job? = null

    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var lastPackets = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        usbManager = UsbEthernetManager(this)
        arpEngine = ArpEngine(
            virtualMac = ArpEngine.parseMac(ethernetConfig.virtualMac),
            adapterIp = ArpEngine.parseIp(ethernetConfig.adapterIp)
        )
        ftpsAlg = FtpsAlg()
        natEngine = UserlandNatEngine(ethernetConfig, ftpsAlg)
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

                currentCoroutineContext().ensureActive()

                val config = tailscaleManager.config.value

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
     * Applies Userland SNAT (Masquerade) to 192.168.1.100, clamps MSS to 1360,
     * encapsulates in 14-byte Ethernet frame, and sends to USB Bulk OUT.
     */
    fun handleOutgoingIpPacketFromTun(packet: ByteArray, length: Int) {
        if (length < 20) return
        val origSrc = ArpEngine.formatIp(packet, 12)
        val targetDst = ArpEngine.formatIp(packet, 16)

        // 1. Apply Userland SNAT Masquerade (rewrite source IP to 192.168.1.100)
        val snatSuccess = natEngine.processOutgoingPacket(packet, 0, length)
        if (!snatSuccess) return

        totalTxBytes.addAndGet(length.toLong())
        totalPackets.incrementAndGet()

        val protoNum = packet[9].toInt() and 0xFF
        val protoStr = if (protoNum == 6) "TCP-OUT" else if (protoNum == 17) "UDP-OUT" else "IP-OUT"
        logPacket(protoStr, origSrc, targetDst, length, "SNAT Masquerade ➔ ${ethernetConfig.adapterIp}", "", false)

        // 2. Lookup MAC in ARP table
        val dstMac = arpEngine.getMacForIp(targetDst)
        if (dstMac != null) {
            // Target MAC is known: wrap and send immediately
            val frame = arpEngine.wrapInEthernetHeader(packet, 0, length, dstMac)
            if (usbManager.isConnected) {
                usbManager.sendFrame(frame, frame.size)
            }
        } else {
            // Target MAC unknown: queue packet and broadcast ARP Request
            Log.i(TAG, "ARP cache miss for $targetDst. Queuing packet and sending who-has query...")
            arpEngine.queuePendingPacket(targetDst, packet.copyOfRange(0, length))
            sendArpRequestFor(targetDst)
        }
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
                        totalPackets.incrementAndGet()
                        handleIncomingEthernetFrame(rxBuffer, bytesRead)
                    }
                } else {
                    delay(300)
                }
            }
        }
    }

    private fun handleIncomingEthernetFrame(frame: ByteArray, length: Int) {
        if (length < 14) return
        val etherType = ((frame[12].toInt() and 0xFF) shl 8) or (frame[13].toInt() and 0xFF)

        when (etherType) {
            0x0806 -> { // ARP Frame
                val senderIp = ArpEngine.formatIp(frame, 28)
                val targetIp = ArpEngine.formatIp(frame, 38)
                val replyFrame = arpEngine.processIncomingFrame(frame, length)
                logPacket("ARP", senderIp, targetIp, length, "Who-has $targetIp", bytesToHex(frame, 0, minOf(length, 32)), false)

                // If someone asked for 192.168.1.100, send synthetic reply
                if (replyFrame != null && usbManager.isConnected) {
                    usbManager.sendFrame(replyFrame, replyFrame.size)
                    logPacket("ARP-REP", ethernetConfig.adapterIp, senderIp, replyFrame.size, "Is-at ${ethernetConfig.virtualMac}", "", false)
                }

                // Check if any pending packets were waiting for senderIp's MAC!
                val senderMac = arpEngine.getMacForIp(senderIp)
                if (senderMac != null) {
                    val pending = arpEngine.getAndClearPendingPackets(senderIp)
                    for (pendingIpPacket in pending) {
                        val outFrame = arpEngine.wrapInEthernetHeader(pendingIpPacket, 0, pendingIpPacket.size, senderMac)
                        if (usbManager.isConnected) {
                            usbManager.sendFrame(outFrame, outFrame.size)
                            Log.i(TAG, "Flushed queued IP packet (${pendingIpPacket.size}B) to $senderIp")
                        }
                    }
                }
            }
            0x0800 -> { // IPv4 Packet
                val ipOffset = 14
                val ipLen = length - 14
                val ipPacket = ByteArray(ipLen)
                System.arraycopy(frame, ipOffset, ipPacket, 0, ipLen)

                val srcIp = ArpEngine.formatIp(ipPacket, 12)
                val dstIp = ArpEngine.formatIp(ipPacket, 16)

                // Apply Reverse Userland DNAT (Demangle destination 192.168.1.100 back to host IP)
                val dnatSuccess = natEngine.processIncomingPacket(ipPacket, 0, ipLen)
                if (dnatSuccess) {
                    val restoredDst = ArpEngine.formatIp(ipPacket, 16)
                    val protoNum = ipPacket[9].toInt() and 0xFF
                    val protoStr = if (protoNum == 6) "TCP-IN" else if (protoNum == 17) "UDP-IN" else "IP-IN"
                    logPacket(protoStr, srcIp, restoredDst, ipLen, "DNAT De-masquerade ➔ $restoredDst", "", false)

                    // Inject de-masqueraded packet directly to Tailscale Go daemon (single authoritative path)
                    val injected = tailscaleManager.injectPacketFromUsb(ipPacket, ipLen)
                    if (injected != ipLen) {
                        Log.w(TAG, "Tailscale rejected USB packet: result=$injected, length=$ipLen")
                    }
                } else {
                    logPacket("IP-DROP", srcIp, dstIp, ipLen, "No NAT session mapping found", "", false)
                }

                // Also store in zero-copy ring buffer
                ringBuffer.write(frame, 0, length)
            }
        }
    }

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                val rx = totalRxBytes.get()
                val tx = totalTxBytes.get()
                val pkts = totalPackets.get()
                val activeConns = natEngine.activeSessionCount

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

    fun sendArpRequestFor(targetIp: String) {
        serviceScope.launch {
            val req = arpEngine.buildArpRequest(ArpEngine.parseIp(targetIp))
            if (usbManager.isConnected) {
                usbManager.sendFrame(req, req.size)
                logPacket("ARP-REQ", ethernetConfig.adapterIp, targetIp, req.size, "Who-has $targetIp", "", false)
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
        totalRxBytes.set(0L)
        totalTxBytes.set(0L)
        totalPackets.set(0L)
        ringBuffer.clear()
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
