package com.example.core.model

enum class BridgeState {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR,
    PAUSED
}

enum class BridgeMode {
    USB_HOST_L2_L3,   // Direct Userland USB Host CDC-ECM/RTL8152 + Tailscale/L3 Bridge
    VPN_SERVICE_TUN   // Android Stock VpnService TUN + Socket Proxy
}

data class BridgeStats(
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val rxPackets: Long = 0L,
    val txPackets: Long = 0L,
    val droppedPackets: Long = 0L,
    val currentRxKBs: Double = 0.0,
    val currentTxKBs: Double = 0.0,
    val currentPps: Int = 0,
    val activeConnections: Int = 0,
    val lastPacketTimestamp: Long = 0L
)

data class EthernetConfig(
    val adapterIp: String = "192.168.1.100",
    val netmask: String = "255.255.255.0",
    val targetPlcIp: String = "192.168.1.12",
    val virtualMac: String = "02:00:00:00:00:01",
    val targetMac: String = "00:00:00:00:00:00",
    val isAutoArp: Boolean = true,
    val isMssClampingEnabled: Boolean = true,
    val targetMss: Int = 1360,
    val mtu: Int = 1500,
    val isFtpsAlgEnabled: Boolean = true,
    val dynamicPasvRangeStart: Int = 50000,
    val dynamicPasvRangeEnd: Int = 50100
)

data class TailscaleConfig(
    val nodeName: String = "galaxy-s9-plc-bridge",
    val advertisedRoutes: String = "192.168.1.0/24",
    val authKey: String = "",
    val controlUrl: String = "https://controlplane.tailscale.com",
    val isConnected: Boolean = false,
    val tailnetIp: String = "0.0.0.0",
    val isEphemeral: Boolean = false,
    val isSubnetRoutingActive: Boolean = false
)

data class UsbDeviceInfo(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val manufacturer: String?,
    val productName: String?,
    val chipset: String,
    val isPermissionGranted: Boolean,
    val hasActiveFd: Boolean,
    val fileDescriptor: Int? = null
)

data class ArpEntry(
    val ip: String,
    val mac: String,
    val isStatic: Boolean = false,
    val lastSeenMs: Long = System.currentTimeMillis(),
    val isResolved: Boolean = true
)

data class PacketLog(
    val id: Long,
    val timestamp: Long,
    val protocol: String, // "ARP", "IPv4", "TCP-SYN", "TCP-DATA", "UDP", "ICMP", "FTPS-PASS"
    val src: String,
    val dst: String,
    val length: Int,
    val info: String,
    val hexPreview: String,
    val isClamped: Boolean = false
)

data class PlcProfile(
    val id: String,
    val name: String,
    val targetIp: String,
    val vendor: String,
    val defaultPorts: List<Int>,
    val description: String,
    val isFtpsPasvNeeded: Boolean
)
