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
import com.example.core.model.UsbDeviceInfo

class UsbEthernetManager(private val context: Context) {

    companion object {
        const val ACTION_USB_PERMISSION = "com.example.USB_PERMISSION"
        private const val TAG = "UsbEthernetManager"
    }

    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    
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
     * Connects to a USB Ethernet adapter, claims ALL interfaces (control and data),
     * detaches kernel drivers, and configures endpoints.
     */
    @Synchronized
    fun connectToDevice(device: UsbDevice): Boolean {
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
            Log.e(TAG, "No USB permission for device: ${device.deviceName}")
            return false
        }

        val conn = usbManager.openDevice(device) ?: run {
            Log.e(TAG, "Failed to open USB device: ${device.deviceName}")
            return false
        }

        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null

        // 1. Locate Bulk IN and Bulk OUT endpoints
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            var currentEpIn: UsbEndpoint? = null
            var currentEpOut: UsbEndpoint? = null

            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN && currentEpIn == null) {
                        currentEpIn = ep
                    } else if (ep.direction == UsbConstants.USB_DIR_OUT && currentEpOut == null) {
                        currentEpOut = ep
                    }
                }
            }

            if (currentEpIn != null && currentEpOut != null) {
                epIn = currentEpIn
                epOut = currentEpOut
                Log.i(TAG, "Found Bulk IN (${epIn.endpointNumber}) & OUT (${epOut.endpointNumber}) on interface #${iface.id}")
                break
            }
        }

        // Secondary search if endpoints are on distinct interfaces
        if (epIn == null || epOut == null) {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN && epIn == null) {
                            epIn = ep
                        } else if (ep.direction == UsbConstants.USB_DIR_OUT && epOut == null) {
                            epOut = ep
                        }
                    }
                }
            }
        }

        if (epIn == null || epOut == null) {
            Log.e(TAG, "Could not find Bulk IN and Bulk OUT endpoints on device")
            conn.close()
            return false
        }

        // 2. CRITICAL: Claim ALL interfaces on the device with force = true.
        // Multi-interface CDC-ECM/NCM and Realtek dongles have Interface 0 (Control) and Interface 1 (CDC Data).
        // Claiming all interfaces detaches in-kernel drivers (cdc_ether, r8152) and prevents kernel warnings
        // "did not claim interface 1 before use" and ret=-22 (EINVAL).
        var claimedCount = 0
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val claimed = conn.claimInterface(iface, true)
            Log.i(TAG, "Claiming USB interface #${iface.id} (class=${iface.interfaceClass}, sub=${iface.interfaceSubclass}): claimed=$claimed")
            if (claimed) {
                claimedInterfaces.add(iface)
                claimedCount++
            } else {
                Log.w(TAG, "Could not claim interface #${iface.id}")
            }
        }

        if (claimedCount == 0) {
            Log.e(TAG, "Failed to claim any interface on device")
            conn.close()
            return false
        }

        activeDevice = device
        activeConnection = conn
        inEndpoint = epIn
        outEndpoint = epOut

        // 3. Initialize chipset if Realtek (VID 0x0BDA)
        val isRtl = device.vendorId == 0x0BDA
        if (isRtl) {
            val rtl = RealtekDriver(conn)
            if (!rtl.initializeChip()) {
                Log.e(TAG, "Realtek chip initialization failed")
                disconnect()
                return false
            }
            realtekDriver = rtl
        }

        Log.i(TAG, "USB Ethernet Adapter connected successfully! Native FD: ${conn.fileDescriptor}, Interfaces claimed: ${claimedInterfaces.size}")
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
            if (res >= 8) res - 8 else res
        } else {
            conn.bulkTransfer(ep, frame, length, 100)
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
            conn.bulkTransfer(ep, targetBuffer, targetBuffer.size, timeoutMs)
        }
    }

    @Synchronized
    fun disconnect() {
        val conn = activeConnection

        try {
            for (iface in claimedInterfaces) {
                try {
                    conn?.releaseInterface(iface)
                    Log.d(TAG, "Released USB interface #${iface.id}")
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
        // 1. Check known Ethernet Vendor IDs: Realtek (0x0BDA), ASIX (0x0B95), Microchip (0x0424), Davicom (0x0A46)
        val knownVendor = list.firstOrNull {
            it.vendorId == 0x0BDA || it.vendorId == 0x0B95 || it.vendorId == 0x0424 || it.vendorId == 0x0A46
        }
        if (knownVendor != null) return knownVendor

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
