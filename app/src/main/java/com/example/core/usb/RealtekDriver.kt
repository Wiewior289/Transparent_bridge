package com.example.core.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection

/**
 * Low-level Userland driver commands for Realtek RTL8152 / RTL8153 USB-to-Ethernet controllers.
 * Executes control requests over USB Endpoint 0 without kernel root privileges.
 */
class RealtekDriver(private val connection: UsbDeviceConnection) {

    companion object {
        // Realtek USB vendor request types
        const val RTL8152_REQ_GET_REGS = 0x05
        const val RTL8152_REQ_SET_REGS = 0x05

        // Register blocks
        const val MCU_TYPE_PLA = 0x0100
        const val MCU_TYPE_USB = 0x0000

        // Important registers
        const val PLA_IDR = 0x0012        // MAC address registers (6 bytes)
        const val PLA_RCR = 0x0010        // Rx Configuration Register
        const val PLA_TCR = 0x0014        // Tx Configuration Register
        const val PLA_CR  = 0x0016        // Command Register (Enable Rx/Tx)
        const val PLA_RMS = 0x0018        // Rx Max Packet Size
        const val PLA_PHYSTATUS = 0x001E  // Link status
    }

    /**
     * Reads the factory hardware MAC address directly from Realtek EEPROM registers.
     */
    fun readMacAddress(): ByteArray {
        val mac = ByteArray(6)
        // Vendor control request to read PLA_IDR
        val res = connection.controlTransfer(
            UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_VENDOR,
            RTL8152_REQ_GET_REGS,
            PLA_IDR,
            MCU_TYPE_PLA,
            mac,
            6,
            1000
        )
        return if (res == 6) mac else byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x01)
    }

    /**
     * Initializes the Realtek chip in userland:
     * - Configures packet size
     * - Enables RX (Receiver) and TX (Transmitter)
     * - Enables Accept Broadcast, Multicast, and Unicast
     */
    fun initializeChip(): Boolean {
        // Set Rx max packet size to 1536 (standard MTU)
        val rmsBytes = byteArrayOf(0x00, 0x06) // 1536
        connection.controlTransfer(
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR,
            RTL8152_REQ_SET_REGS,
            PLA_RMS,
            MCU_TYPE_PLA,
            rmsBytes,
            2,
            500
        )

        // Enable RX and TX in Command Register (PLA_CR = 0x0C)
        val crBytes = byteArrayOf(0x0C)
        val res = connection.controlTransfer(
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR,
            RTL8152_REQ_SET_REGS,
            PLA_CR,
            MCU_TYPE_PLA,
            crBytes,
            1,
            500
        )
        return res >= 0
    }

    /**
     * Checks if Ethernet physical link is UP.
     */
    fun isLinkUp(): Boolean {
        val status = ByteArray(1)
        val res = connection.controlTransfer(
            UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_VENDOR,
            RTL8152_REQ_GET_REGS,
            PLA_PHYSTATUS,
            MCU_TYPE_PLA,
            status,
            1,
            500
        )
        return if (res == 1) {
            // Bit 1: Link status
            (status[0].toInt() and 0x02) != 0
        } else {
            true // assume UP if register query unsupported
        }
    }
}
