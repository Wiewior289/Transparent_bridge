package com.example.core.net

import com.example.core.model.ArpEntry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Handles Layer 2 / Layer 3 ARP operations without Linux kernel bridging.
 * Synthesizes ARP Replies and ARP Requests in userland.
 * Manages pending packet queues waiting for ARP resolution so initial TCP SYNs are never dropped!
 */
class ArpEngine(
    val virtualMac: ByteArray = parseMac("02:00:00:00:00:01"),
    var adapterIp: ByteArray = parseIp("192.168.1.100")
) {
    // Dynamic ARP Table: IP -> ArpEntry
    private val arpTable = ConcurrentHashMap<String, ArpEntry>()

    // Queued IP packets waiting for MAC resolution: TargetIp -> Queue of raw IP packets
    private val pendingPackets = ConcurrentHashMap<String, ConcurrentLinkedQueue<ByteArray>>()

    init {
        // Initial static entry for bridge
        val ipStr = formatIp(adapterIp)
        arpTable[ipStr] = ArpEntry(ipStr, formatMac(virtualMac), isStatic = true)
    }

    fun getArpTable(): List<ArpEntry> = arpTable.values.toList()

    fun setStaticArp(ip: String, mac: String) {
        arpTable[ip] = ArpEntry(ip, mac, isStatic = true, isResolved = true)
    }

    fun getMacForIp(ip: String): ByteArray? {
        val entry = arpTable[ip]
        return if (entry != null && entry.isResolved) {
            parseMac(entry.mac)
        } else {
            null
        }
    }

    fun queuePendingPacket(targetIp: String, ipPacket: ByteArray) {
        val queue = pendingPackets.computeIfAbsent(targetIp) { ConcurrentLinkedQueue() }
        if (queue.size < 64) { // Bound queue to prevent memory leak
            queue.add(ipPacket.copyOf())
        }
    }

    fun getAndClearPendingPackets(targetIp: String): List<ByteArray> {
        val queue = pendingPackets.remove(targetIp) ?: return emptyList()
        val list = mutableListOf<ByteArray>()
        while (true) {
            val pkt = queue.poll() ?: break
            list.add(pkt)
        }
        return list
    }

    /**
     * Wraps raw IPv4 packet in a 14-byte Ethernet frame.
     */
    fun wrapInEthernetHeader(ipPacket: ByteArray, ipOffset: Int, ipLen: Int, dstMac: ByteArray): ByteArray {
        val frame = ByteArray(14 + ipLen)
        System.arraycopy(dstMac, 0, frame, 0, 6)
        System.arraycopy(virtualMac, 0, frame, 6, 6)
        frame[12] = 0x08.toByte() // EtherType IPv4 (0x0800)
        frame[13] = 0x00.toByte()
        System.arraycopy(ipPacket, ipOffset, frame, 14, ipLen)
        return frame
    }

    /**
     * Processes an incoming raw Ethernet frame from the USB Ethernet adapter.
     * Returns an ARP Reply frame if an ARP Request was made for our IP, or null.
     */
    fun processIncomingFrame(frame: ByteArray, length: Int): ByteArray? {
        if (length < 14) return null
        
        val etherType = ((frame[12].toInt() and 0xFF) shl 8) or (frame[13].toInt() and 0xFF)
        if (etherType != 0x0806) return null // Not ARP
        
        if (length < 42) return null // Minimum ARP over Ethernet length
        
        val hwType = ((frame[14].toInt() and 0xFF) shl 8) or (frame[15].toInt() and 0xFF)
        val protoType = ((frame[16].toInt() and 0xFF) shl 8) or (frame[17].toInt() and 0xFF)
        val hwLen = frame[18].toInt() and 0xFF
        val protoLen = frame[19].toInt() and 0xFF
        val op = ((frame[20].toInt() and 0xFF) shl 8) or (frame[21].toInt() and 0xFF)
        
        if (hwType != 1 || protoType != 0x0800 || hwLen != 6 || protoLen != 4) {
            return null
        }
        
        val senderMac = ByteArray(6)
        System.arraycopy(frame, 22, senderMac, 0, 6)
        val senderIp = formatIp(frame, 28)
        
        val targetMac = ByteArray(6)
        System.arraycopy(frame, 32, targetMac, 0, 6)
        val targetIp = formatIp(frame, 38)
        
        // Cache sender's MAC in our ARP table
        val existing = arpTable[senderIp]
        if (existing == null || !existing.isStatic) {
            arpTable[senderIp] = ArpEntry(
                ip = senderIp,
                mac = formatMac(senderMac),
                isStatic = false,
                lastSeenMs = System.currentTimeMillis(),
                isResolved = true
            )
        }
        
        // If someone is asking "Who has 192.168.1.100?" (our adapter IP)
        if (op == 1 && targetIp == formatIp(adapterIp)) {
            return buildArpReply(senderMac, parseIp(senderIp))
        }
        
        return null
    }

    /**
     * Builds an ARP Reply (42 bytes minimum Ethernet frame):
     * "192.168.1.100 is at 02:00:00:00:00:01"
     */
    fun buildArpReply(targetMac: ByteArray, targetIp: ByteArray): ByteArray {
        val reply = ByteArray(42) // 14 Ethernet + 28 ARP
        
        // Ethernet Header
        System.arraycopy(targetMac, 0, reply, 0, 6)    // Dst MAC
        System.arraycopy(virtualMac, 0, reply, 6, 6)   // Src MAC
        reply[12] = 0x08.toByte()                     // EtherType ARP (0x0806)
        reply[13] = 0x06.toByte()
        
        // ARP Header
        reply[14] = 0x00.toByte(); reply[15] = 0x01.toByte() // HW Type = Ethernet (1)
        reply[16] = 0x08.toByte(); reply[17] = 0x00.toByte() // Proto Type = IPv4 (0x0800)
        reply[18] = 0x06.toByte()                            // HW Len = 6
        reply[19] = 0x04.toByte()                            // Proto Len = 4
        reply[20] = 0x00.toByte(); reply[21] = 0x02.toByte() // Op = Reply (2)
        
        // Sender MAC & IP (ours)
        System.arraycopy(virtualMac, 0, reply, 22, 6)
        System.arraycopy(adapterIp, 0, reply, 28, 4)
        
        // Target MAC & IP (requester)
        System.arraycopy(targetMac, 0, reply, 32, 6)
        System.arraycopy(targetIp, 0, reply, 38, 4)
        
        return reply
    }

    /**
     * Builds an ARP Request broadcast:
     * "Who has targetIp? Tell adapterIp"
     */
    fun buildArpRequest(targetIp: ByteArray): ByteArray {
        val request = ByteArray(42)
        val broadcastMac = byteArrayOf(
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()
        )
        
        // Ethernet Header
        System.arraycopy(broadcastMac, 0, request, 0, 6) // Dst MAC: Broadcast
        System.arraycopy(virtualMac, 0, request, 6, 6)   // Src MAC: Virtual MAC
        request[12] = 0x08.toByte()                     // EtherType ARP (0x0806)
        request[13] = 0x06.toByte()
        
        // ARP Header
        request[14] = 0x00.toByte(); request[15] = 0x01.toByte() // HW Type = Ethernet (1)
        request[16] = 0x08.toByte(); request[17] = 0x00.toByte() // Proto Type = IPv4 (0x0800)
        request[18] = 0x06.toByte()                            // HW Len = 6
        request[19] = 0x04.toByte()                            // Proto Len = 4
        request[20] = 0x00.toByte(); request[21] = 0x01.toByte() // Op = Request (1)
        
        // Sender MAC & IP
        System.arraycopy(virtualMac, 0, request, 22, 6)
        System.arraycopy(adapterIp, 0, request, 28, 4)
        
        // Target MAC (zero for request) & Target IP
        val zeroMac = ByteArray(6)
        System.arraycopy(zeroMac, 0, request, 32, 6)
        System.arraycopy(targetIp, 0, request, 38, 4)
        
        return request
    }

    companion object {
        fun parseMac(macStr: String): ByteArray {
            val parts = macStr.split(":", "-")
            val bytes = ByteArray(6)
            for (i in 0 until minOf(6, parts.size)) {
                bytes[i] = parts[i].toInt(16).toByte()
            }
            return bytes
        }

        fun formatMac(mac: ByteArray): String {
            return mac.joinToString(":") { "%02X".format(it) }
        }

        fun parseIp(ipStr: String): ByteArray {
            val parts = ipStr.split(".")
            val bytes = ByteArray(4)
            for (i in 0 until minOf(4, parts.size)) {
                bytes[i] = (parts[i].toIntOrNull() ?: 0).toByte()
            }
            return bytes
        }

        fun formatIp(bytes: ByteArray, offset: Int = 0): String {
            return "${bytes[offset].toInt() and 0xFF}.${bytes[offset + 1].toInt() and 0xFF}.${bytes[offset + 2].toInt() and 0xFF}.${bytes[offset + 3].toInt() and 0xFF}"
        }
    }
}
