package com.example.core.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import com.example.core.model.UsbDeviceInfo

class UsbEthernetManager(private val context: Context) {

    companion object {
        const val ACTION_USB_PERMISSION = "com.example.USB_PERMISSION"
        private const val TAG = "UsbEthernetManager"
        private val attemptCounter = AtomicLong(0L)
    }

    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    @Volatile var diagnosticListener: ((String) -> Unit)? = null
    @Volatile private var currentAttemptId: Long = 0L
    @Volatile private var pendingSelectedDeviceName: String? = null
    private var rxSamples = 0
    private var txSamples = 0
    private var rxNoDataCount = 0

    private fun diag(message: String, error: Boolean = false) {
        val line = "[USB#" + currentAttemptId + "] " + message
        if (error) Log.e(TAG, line) else Log.i(TAG, line)
        try { diagnosticListener?.invoke(line) } catch (e: Exception) { Log.w(TAG, "Diagnostic listener failed", e) }
    }

    private fun dumpDevice(device: UsbDevice) {
        diag("DEVICE name=" + device.deviceName + " VID=0x" + device.vendorId.toString(16).padStart(4,'0') +
            " PID=0x" + device.productId.toString(16).padStart(4,'0') + " class=" + device.deviceClass + "/" +
            device.deviceSubclass + "/" + device.deviceProtocol + " interfaces=" + device.interfaceCount +
            " configurations=" + device.configurationCount + " manufacturer=" + (device.manufacturerName ?: "?") +
            " product=" + (device.productName ?: "?") + " permission=" + usbManager.hasPermission(device))
        for (ci in 0 until device.configurationCount) {
            val cfg = device.getConfiguration(ci)
            diag("CONFIG index=" + ci + " id=" + cfg.id +
                " name=" + (cfg.name ?: "?") + " interfaces=" + cfg.interfaceCount +
                " selfPowered=" + cfg.isSelfPowered + " remoteWakeup=" + cfg.isRemoteWakeup + " maxPower=" + cfg.maxPower)
        }
        // UsbDevice.getInterface() lists interfaces exposed by Android for the active configuration.
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            diag("INTERFACE index=" + i + " id=" + iface.id + " alt=" + iface.alternateSetting +
                " class=" + iface.interfaceClass + "/" + iface.interfaceSubclass + "/" + iface.interfaceProtocol +
                " name=" + (iface.name ?: "?") + " endpoints=" + iface.endpointCount)
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                val type = when(ep.type) { UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"; UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISO"; UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"; UsbConstants.USB_ENDPOINT_XFER_INT -> "INT"; else -> "UNKNOWN(" + ep.type + ")" }
                val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                diag("ENDPOINT ifaceId=" + iface.id + " index=" + e + " addr=0x" + ep.address.toString(16) +
                    " number=" + ep.endpointNumber + " direction=" + dir + " type=" + type +
                    " maxPacketSize=" + ep.maxPacketSize + " interval=" + ep.interval)
            }
        }
    }

    private fun logTransfer(kind: String, ep: UsbEndpoint, result: Int, requested: Int) {
        if (result <= 0) {
            if (kind == "RX" && result < 0) {
                rxNoDataCount++
                if (rxNoDataCount <= 3 || rxNoDataCount % 100 == 0) diag("RX BULK result=" + result + " requested=" + requested + " endpoint=0x" + ep.address.toString(16) + " no-data/timeout-or-error count=" + rxNoDataCount)
            } else diag(kind + " BULK result=" + result + " requested=" + requested + " endpoint=0x" + ep.address.toString(16) + (if (result < 0) " error" else " zero-bytes"), result < 0)
        }
        else if (kind == "RX" && rxSamples++ < 5) diag("RX BULK positive result=" + result + " requested=" + requested + " endpoint=0x" + ep.address.toString(16) + " sample=" + rxSamples)
        else if (kind == "TX" && txSamples++ < 5) diag("TX BULK positive result=" + result + " requested=" + requested + " endpoint=0x" + ep.address.toString(16) + " sample=" + txSamples)
    }
    
    private var activeDevice: UsbDevice? = null
    private var activeConnection: UsbDeviceConnection? = null
    private val claimedInterfaces = mutableListOf<UsbInterface>()
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null
    
    var realtekDriver: RealtekDriver? = null
        private set

    val isConnected: Boolean
        get() = activeConnection != null && inEndpoint != null && outEndpoint != null && claimedInterfaces.isNotEmpty()

    val fileDescriptor: Int?
        get() = try {
            activeConnection?.fileDescriptor
        } catch (_: Exception) {
            null
        }

    /**
     * Lists all connected USB devices and identifies whether they are Ethernet adapters.
     */
    fun scanDevices(): List<UsbDeviceInfo> {
        val deviceList = usbManager.deviceList
        val results = mutableListOf<UsbDeviceInfo>()

        for ((_, device) in deviceList) {
            val chipset = identifyChipset(device.vendorId, device.productId, device)
            val hasPerm = usbManager.hasPermission(device)
            val isOpen = activeDevice == device && activeConnection != null

            results.add(
                UsbDeviceInfo(
                    deviceName = device.deviceName,
                    vendorId = device.vendorId,
                    productId = device.productId,
                    manufacturer = device.manufacturerName,
                    productName = device.productName,
                    chipset = chipset,
                    isPermissionGranted = hasPerm,
                    hasActiveFd = isOpen,
                    fileDescriptor = if (isOpen) activeConnection?.fileDescriptor else null
                )
            )
        }
        return results
    }

    fun requestDevicePermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        if (usbManager.hasPermission(device)) {
            onResult(true)
            return
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val intent = Intent(ACTION_USB_PERMISSION)
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(device, pendingIntent)
    }

    /**
     * Opens the chosen USB device, enumerates its exposed descriptors, claims the
     * one interface owning the selected bulk endpoints, and records transfer results.
     */
    @Synchronized
    fun connectToDevice(device: UsbDevice): Boolean {
        if (pendingSelectedDeviceName != device.deviceName) currentAttemptId = attemptCounter.incrementAndGet()
        pendingSelectedDeviceName = null
        rxSamples = 0; txSamples = 0; rxNoDataCount = 0
        diag("CONNECT ATTEMPT START device=" + device.deviceName); dumpDevice(device)
        // Nie otwieraj drugi raz tego samego urządzenia.
        if (activeConnection != null &&
            activeDevice?.deviceName == device.deviceName &&
            claimedInterfaces.isNotEmpty() &&
            inEndpoint != null &&
            outEndpoint != null
        ) {
            Log.d(TAG, "Ignoring duplicate USB connect: ${device.deviceName}")
            return true
        }

        disconnect()

        if (!usbManager.hasPermission(device)) {
            diag("OPEN ABORT: USB permission missing", true)
            return false
        }

        val conn = usbManager.openDevice(device) ?: run {
            diag("OPEN FAILED: openDevice returned null", true)
            return false
        }

        diag("OPEN OK fd=" + conn.fileDescriptor)

        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null

        // Select a single USB interface for Bulk I/O. Do not mix endpoints from
        // different configurations or interfaces: Android UsbDevice exposes the
        // current configuration's interfaces, while composite adapters may expose
        // CDC control/data and vendor-specific alternatives.
        var selectedInterface: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            var candidateIn: UsbEndpoint? = null
            var candidateOut: UsbEndpoint? = null
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN && candidateIn == null) {
                    candidateIn = ep
                } else if (ep.direction == UsbConstants.USB_DIR_OUT && candidateOut == null) {
                    candidateOut = ep
                }
            }
            if (candidateIn != null && candidateOut != null) {
                selectedInterface = iface
                epIn = candidateIn
                epOut = candidateOut
                diag("ENDPOINT PAIR SELECTED ifaceId=" + iface.id + " alt=" + iface.alternateSetting + " class=" + iface.interfaceClass + "/" + iface.interfaceSubclass + "/" + iface.interfaceProtocol + " IN=0x" + epIn.address.toString(16) + " OUT=0x" + epOut.address.toString(16))
                break
            }
        }

        if (selectedInterface == null || epIn == null || epOut == null) {
            diag("ENDPOINT VALIDATION FAILED: no single exposed interface has both Bulk IN and OUT", true)
            conn.close()
            return false
        }

        // Claim only the interface owning the selected endpoints. Claiming all
        // interfaces caused duplicate claims and collisions with cdc_ether.
        diag("CLAIM BEGIN ifaceId=" + selectedInterface.id + " alt=" + selectedInterface.alternateSetting + " force=true")
        val claimOk = conn.claimInterface(selectedInterface, true)
        diag("CLAIM RESULT ifaceId=" + selectedInterface.id + " success=" + claimOk)
        if (!claimOk) { diag("CLAIM FAILED; closing connection", true); conn.close(); return false }
        claimedInterfaces.clear()
        claimedInterfaces.add(selectedInterface)

        activeDevice = device
        activeConnection = conn
        inEndpoint = epIn
        outEndpoint = epOut

        // Realtek-specific register initialization must never run for unrelated
        // multi-function adapters (e.g. WCH 1a86:5394 CDC-ECM).
        if (device.vendorId == 0x0BDA) {
            val rtl = RealtekDriver(conn)
            if (!rtl.initializeChip()) {
                Log.e(TAG, "Realtek chip initialization failed")
                disconnect()
                return false
            }
            realtekDriver = rtl
        }

        diag("STATE=INTERFACE_CLAIMED fd=" + conn.fileDescriptor + " ifaceId=" + selectedInterface.id + " IN=0x" + epIn.address.toString(16) + " OUT=0x" + epOut.address.toString(16) + "; payload/protocol not yet validated")
        return true
    }

    /**
     * Transmits raw Ethernet frame to the USB adapter.
     * Encapsulates with Realtek 8-byte tx_desc header if Realtek controller.
     */
    fun sendFrame(frame: ByteArray, length: Int): Int {
        val conn = activeConnection ?: return -1
        val ep = outEndpoint ?: return -1

        return if (realtekDriver != null) {
            // Realtek RTL8152/RTL8153 tx_desc (8 bytes):
            // opts1 = TX_FS (bit 31) | TX_LS (bit 30) | (len & 0x7FFFF)
            // opts2 = 0
            val txBuf = ByteArray(8 + length)
            val opts1 = (0xC0000000.toInt()) or (length and 0x7FFFF)
            txBuf[0] = (opts1 and 0xFF).toByte()
            txBuf[1] = ((opts1 ushr 8) and 0xFF).toByte()
            txBuf[2] = ((opts1 ushr 16) and 0xFF).toByte()
            txBuf[3] = ((opts1 ushr 24) and 0xFF).toByte()
            System.arraycopy(frame, 0, txBuf, 8, length)
            val res = conn.bulkTransfer(ep, txBuf, txBuf.size, 100)
            logTransfer("TX", ep, res, txBuf.size)
            if (res >= 8) res - 8 else res
        } else {
            val res = conn.bulkTransfer(ep, frame, length, 100)
            logTransfer("TX", ep, res, length)
            res
        }
    }

    /**
     * Receives raw Ethernet frame from the USB adapter.
     * Automatically strips Realtek rx_desc header if present.
     */
    fun receiveFrame(targetBuffer: ByteArray, timeoutMs: Int = 20): Int {
        val conn = activeConnection ?: return -1
        val ep = inEndpoint ?: return -1

        return if (realtekDriver != null) {
            val rawBuf = ByteArray(targetBuffer.size + 64)
            val res = conn.bulkTransfer(ep, rawBuf, rawBuf.size, timeoutMs)
            logTransfer("RX", ep, res, rawBuf.size)
            if (res <= 0) return res

            // Check if frame starts directly at offset 0 (EtherType at offset 12)
            if (res >= 14 && rawBuf[12] == 0x08.toByte() && (rawBuf[13] == 0x00.toByte() || rawBuf[13] == 0x06.toByte())) {
                val copyLen = minOf(res, targetBuffer.size)
                System.arraycopy(rawBuf, 0, targetBuffer, 0, copyLen)
                copyLen
            } else if (res >= 22 && rawBuf[20] == 0x08.toByte() && (rawBuf[21] == 0x00.toByte() || rawBuf[21] == 0x06.toByte())) {
                // RTL8152 header (8 bytes rx_desc)
                val pktLen = ((rawBuf[0].toInt() and 0xFF) or ((rawBuf[1].toInt() and 0x3F) shl 8)) - 4
                val actualLen = if (pktLen in 14..1514) pktLen else res - 8
                val copyLen = minOf(actualLen, targetBuffer.size, res - 8)
                System.arraycopy(rawBuf, 8, targetBuffer, 0, copyLen)
                copyLen
            } else if (res >= 38 && rawBuf[36] == 0x08.toByte() && (rawBuf[37] == 0x00.toByte() || rawBuf[37] == 0x06.toByte())) {
                // RTL8153 header (24 bytes rx_desc)
                val pktLen = ((rawBuf[0].toInt() and 0xFF) or ((rawBuf[1].toInt() and 0x3F) shl 8)) - 4
                val actualLen = if (pktLen in 14..1514) pktLen else res - 24
                val copyLen = minOf(actualLen, targetBuffer.size, res - 24)
                System.arraycopy(rawBuf, 24, targetBuffer, 0, copyLen)
                copyLen
            } else {
                val copyLen = minOf(res, targetBuffer.size)
                System.arraycopy(rawBuf, 0, targetBuffer, 0, copyLen)
                copyLen
            }
        } else {
            val res = conn.bulkTransfer(ep, targetBuffer, targetBuffer.size, timeoutMs)
            logTransfer("RX", ep, res, targetBuffer.size)
            res
        }
    }

    @Synchronized
    fun disconnect() {
        val conn = activeConnection

        try {
            for (iface in claimedInterfaces) {
                try {
                    val released = conn?.releaseInterface(iface) ?: false
                    diag("RELEASE ifaceId=" + iface.id + " result=" + released)
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing USB interface #${iface.id}", e)
                }
            }
        } finally {
            claimedInterfaces.clear()
            try {
                conn?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing USB connection", e)
            }

            activeDevice = null
            activeConnection = null
            inEndpoint = null
            outEndpoint = null
            realtekDriver = null
        }
    }

    fun getDeviceByVidPid(vid: Int, pid: Int): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { it.vendorId == vid && it.productId == pid }
    }

    fun getFirstEthernetDevice(): UsbDevice? {
        val list = usbManager.deviceList.values
        currentAttemptId = attemptCounter.incrementAndGet()
        diag("DEVICE SELECTION START discoveredCount=" + list.size)
        list.forEach { d ->
            diag("DISCOVERED name=" + d.deviceName + " VID=0x" + d.vendorId.toString(16) + " PID=0x" + d.productId.toString(16) + " class=" + d.deviceClass + "/" + d.deviceSubclass + "/" + d.deviceProtocol + " interfaces=" + d.interfaceCount + " permission=" + usbManager.hasPermission(d))
            for (i in 0 until d.interfaceCount) {
                val candidateInterface = d.getInterface(i)
                diag("DISCOVERED-INTERFACE device=" + d.deviceName + " index=" + i + " id=" + candidateInterface.id +
                    " alt=" + candidateInterface.alternateSetting + " class=" + candidateInterface.interfaceClass + "/" +
                    candidateInterface.interfaceSubclass + "/" + candidateInterface.interfaceProtocol + " endpoints=" + candidateInterface.endpointCount)
            }
        }
        // 1. Check known Ethernet Vendor IDs: Realtek (0x0BDA), ASIX (0x0B95), Microchip (0x0424), Davicom (0x0A46)
        val knownVendor = list.firstOrNull {
            it.vendorId == 0x0BDA || it.vendorId == 0x0B95 || it.vendorId == 0x0424 || it.vendorId == 0x0A46
        }
        if (knownVendor != null) { pendingSelectedDeviceName = knownVendor.deviceName; diag("DEVICE SELECTED known VID: " + knownVendor.deviceName + " VID=0x" + knownVendor.vendorId.toString(16) + " PID=0x" + knownVendor.productId.toString(16)); return knownVendor }

        // 2. Check standard CDC-ECM / CDC-NCM USB class at device or interface level
        val cdcDevice = list.firstOrNull { device ->
            val isCdcDevice = device.deviceClass == UsbConstants.USB_CLASS_COMM &&
                (device.deviceSubclass == 6 || device.deviceSubclass == 13)
            if (isCdcDevice) return@firstOrNull true

            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == UsbConstants.USB_CLASS_COMM &&
                    (iface.interfaceSubclass == 6 || iface.interfaceSubclass == 13)
                ) {
                    return@firstOrNull true
                }
            }
            false
        }
        if (cdcDevice != null) { pendingSelectedDeviceName = cdcDevice.deviceName; diag("DEVICE SELECTED CDC: " + cdcDevice.deviceName + " VID=0x" + cdcDevice.vendorId.toString(16) + " PID=0x" + cdcDevice.productId.toString(16)) } else diag("DEVICE SELECTION FAILED: no known vendor or CDC ECM/NCM candidate", true)
        return cdcDevice
    }

    private fun identifyChipset(vid: Int, pid: Int, device: UsbDevice): String {
        return when (vid) {
            0x0BDA -> when (pid) {
                0x8152 -> "Realtek RTL8152 (10/100M Fast Ethernet)"
                0x8153 -> "Realtek RTL8153 (Gigabit Ethernet)"
                0x8155 -> "Realtek RTL8155 (2.5G Ethernet)"
                0x8156 -> "Realtek RTL8156 (2.5G Ethernet)"
                else -> "Realtek USB Controller (0x${vid.toString(16)}:0x${pid.toString(16)})"
            }
            0x0B95 -> when (pid) {
                0x1790 -> "ASIX AX88179 (Gigabit USB 3.0)"
                0x7720, 0x772A, 0x772B -> "ASIX AX88772 (10/100M USB 2.0)"
                else -> "ASIX Electronics (0x${vid.toString(16)}:0x${pid.toString(16)})"
            }
            else -> {
                if (device.deviceClass == 2 && (device.deviceSubclass == 6 || device.deviceSubclass == 13)) {
                    "CDC-ECM / CDC-NCM Standard Ethernet"
                } else {
                    "Standard USB Adapter (0x${vid.toString(16)}:0x${pid.toString(16)})"
                }
            }
        }
    }
}
