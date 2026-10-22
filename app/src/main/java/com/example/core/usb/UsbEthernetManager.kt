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
    private var activeInterface: UsbInterface? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null
    
    var realtekDriver: RealtekDriver? = null
        private set

    val isConnected: Boolean
        get() = activeConnection != null && inEndpoint != null && outEndpoint != null

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
     * Connects to a USB Ethernet adapter, claims interface, and configures endpoints.
     */
    @Synchronized
    fun connectToDevice(device: UsbDevice): Boolean {
        // Nie otwieraj drugi raz tego samego urządzenia.
        if (activeConnection != null &&
            activeDevice?.deviceName == device.deviceName &&
            activeInterface != null &&
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

        // Locate data interface and endpoints
        var foundInterface: UsbInterface? = null
        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            var currentEpIn: UsbEndpoint? = null
            var currentEpOut: UsbEndpoint? = null

            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) {
                        currentEpIn = ep
                    } else if (ep.direction == UsbConstants.USB_DIR_OUT) {
                        currentEpOut = ep
                    }
                }
            }

            if (currentEpIn != null && currentEpOut != null) {
                foundInterface = iface
                epIn = currentEpIn
                epOut = currentEpOut
                break
            }
        }

        if (foundInterface == null || epIn == null || epOut == null) {
            Log.e(TAG, "Could not find Bulk IN and Bulk OUT endpoints on device")
            conn.close()
            return false
        }

        // Claim interface
        val claimed = conn.claimInterface(foundInterface, true)
        if (!claimed) {
            Log.e(TAG, "Failed to claim interface on device")
            conn.close()
            return false
        }

        activeDevice = device
        activeConnection = conn
        activeInterface = foundInterface
        inEndpoint = epIn
        outEndpoint = epOut

        // Initialize chipset if Realtek
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

        Log.i(TAG, "USB Ethernet Adapter connected successfully! Native FD: ${conn.fileDescriptor}")
        return true
    }

    /**
     * Transmits raw Ethernet frame to the USB adapter.
     */
    fun sendFrame(frame: ByteArray, length: Int): Int {
        val conn = activeConnection ?: return -1
        val ep = outEndpoint ?: return -1
        return conn.bulkTransfer(ep, frame, length, 100)
    }

    /**
     * Receives raw Ethernet frame from the USB adapter.
     */
    fun receiveFrame(targetBuffer: ByteArray, timeoutMs: Int = 20): Int {
        val conn = activeConnection ?: return -1
        val ep = inEndpoint ?: return -1
        return conn.bulkTransfer(ep, targetBuffer, targetBuffer.size, timeoutMs)
    }

    @Synchronized
    fun disconnect() {
        val conn = activeConnection
        val iface = activeInterface

        try {
            if (conn != null && iface != null) {
                conn.releaseInterface(iface)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing USB interface", e)
        } finally {
            try {
                conn?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing USB connection", e)
            }

            activeDevice = null
            activeConnection = null
            activeInterface = null
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
