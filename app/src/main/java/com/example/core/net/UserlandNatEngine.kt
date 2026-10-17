package com.example.core.net

import android.util.Log
import com.example.core.model.EthernetConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Userland NAT (Masquerade / SNAT & DNAT) and Connection Tracker (Conntrack in RAM).
 * 
 * Replaces the Linux root command:
 *   iptables -t nat -I POSTROUTING 1 -o eth0 -j MASQUERADE
 * 
 * Without this masquerade, the HMI (192.168.1.12 / 255.255.255.0) drops packets from 
 * Windows Host (100.64.X.X) because it has no route or gateway to the 100.64.0.0/10 subnet.
 * 
 * This engine rewrites:
 *   Outgoing:  SrcIP 100.64.X.X:srcPort  ➔  SrcIP 192.168.1.100:masqPort
 *   Incoming:  DstIP 192.168.1.100:masqPort ➔ DstIP 100.64.X.X:srcPort
 * 
 * And incrementally recalculates IPv4 Header Checksum & TCP/UDP Checksum (RFC 1624).
 */
class UserlandNatEngine(
    var config: EthernetConfig = EthernetConfig(),
    private val ftpsAlg: FtpsAlg = FtpsAlg()
) {
    companion object {
        private const val TAG = "UserlandNatEngine"
        private const val SESSION_TIMEOUT_MS = 180_000L // 3 minutes idle timeout
    }

    data class NatSession(
        val protocol: Int,          // 6 = TCP, 17 = UDP, 1 = ICMP
        val origSrcIp: ByteArray,   // e.g. 100.64.15.22
        val origSrcPort: Int,       // e.g. 54321
        val targetDstIp: ByteArray, // e.g. 192.168.1.12
        val targetDstPort: Int,     // e.g. 21
        val masqPort: Int,          // Port on 192.168.1.100
        var lastSeenMs: Long = System.currentTimeMillis()
    )

    // Lookup: Key (Protocol, MasqPort) ➔ NatSession
    private val natByMasqPort = ConcurrentHashMap<Int, NatSession>()
    
    // Lookup: Key (Protocol, OrigSrcIpStr, OrigSrcPort, DstPort) ➔ MasqPort
    private val natByClient = ConcurrentHashMap<String, Int>()

    private val portCounter = AtomicInteger(45000)

    val activeSessionCount: Int
        get() = natByMasqPort.size

    fun getActiveSessions(): List<NatSession> {
        cleanExpiredSessions()
        return natByMasqPort.values.toList()
    }

    private fun cleanExpiredSessions() {
        val now = System.currentTimeMillis()
        val expired = natByMasqPort.filterValues { now - it.lastSeenMs > SESSION_TIMEOUT_MS }
        for ((port, session) in expired) {
            natByMasqPort.remove(port)
            val clientKey = "${session.protocol}:${ArpEngine.formatIp(session.origSrcIp)}:${session.origSrcPort}:${session.targetDstPort}"
            natByClient.remove(clientKey)
        }
    }

    /**
     * Outgoing: Host / VPN / Tailscale ➔ HMI (192.168.1.12)
     * Performs Masquerade (SNAT): rewrites Source IP to 192.168.1.100.
     * Also clamps TCP MSS to target (e.g. 1360 B).
     * 
     * @param packet Raw IPv4 packet
     * @param offset Offset where IPv4 header starts
     * @param length Length of IPv4 packet
     * @return true if packet was successfully masqueraded and ready for Ethernet encapsulation
     */
    fun processOutgoingPacket(packet: ByteArray, offset: Int, length: Int): Boolean {
        if (length < 20) return false
        val versionAndIhl = packet[offset].toInt() and 0xFF
        if ((versionAndIhl shr 4) != 4) return false
        val ihl = (versionAndIhl and 0x0F) * 4
        if (ihl < 20 || offset + ihl > offset + length) return false

        val protocol = packet[offset + 9].toInt() and 0xFF
        val origSrcIp = ByteArray(4)
        System.arraycopy(packet, offset + 12, origSrcIp, 0, 4)
        val dstIp = ByteArray(4)
        System.arraycopy(packet, offset + 16, dstIp, 0, 4)

        val adapterIp = ArpEngine.parseIp(config.adapterIp)

        // If packet is already from 192.168.1.100, no SNAT needed (just MSS clamp if TCP)
        if (origSrcIp.contentEquals(adapterIp)) {
            if (protocol == 6 && config.isMssClampingEnabled) {
                MssClamper.clampMssInPlace(packet, offset, length, config.targetMss)
            }
            return true
        }

        // Handle TCP and UDP
        if (protocol == 6 || protocol == 17) {
            val l4Offset = offset + ihl
            if (l4Offset + 4 > offset + length) return false

            val origSrcPort = ((packet[l4Offset].toInt() and 0xFF) shl 8) or (packet[l4Offset + 1].toInt() and 0xFF)
            val dstPort = ((packet[l4Offset + 2].toInt() and 0xFF) shl 8) or (packet[l4Offset + 3].toInt() and 0xFF)

            val clientKey = "$protocol:${ArpEngine.formatIp(origSrcIp)}:$origSrcPort:$dstPort"
            var masqPort = natByClient[clientKey]

            if (masqPort == null || !natByMasqPort.containsKey(masqPort)) {
                // Allocate a masquerade port (try preserving original port first)
                masqPort = if (!natByMasqPort.containsKey(origSrcPort) && origSrcPort in 1024..65535) {
                    origSrcPort
                } else {
                    allocatePort()
                }

                val session = NatSession(
                    protocol = protocol,
                    origSrcIp = origSrcIp,
                    origSrcPort = origSrcPort,
                    targetDstIp = dstIp,
                    targetDstPort = dstPort,
                    masqPort = masqPort,
                    lastSeenMs = System.currentTimeMillis()
                )
                natByMasqPort[masqPort] = session
                natByClient[clientKey] = masqPort
            } else {
                natByMasqPort[masqPort]?.lastSeenMs = System.currentTimeMillis()
            }

            // 1. Clamping MSS on TCP SYN before checksum modification
            if (protocol == 6 && config.isMssClampingEnabled) {
                MssClamper.clampMssInPlace(packet, offset, length, config.targetMss)
            }

            // 2. Perform Source IP rewrite: origSrcIp ➔ adapterIp (192.168.1.100)
            applySnat(packet, offset, ihl, l4Offset, origSrcIp, adapterIp, origSrcPort, masqPort, protocol)
            return true
        } else if (protocol == 1) { // ICMP Echo
            // Rewrite Source IP to 192.168.1.100 and update IP checksum
            applyIpOnlySnat(packet, offset, ihl, origSrcIp, adapterIp)
            return true
        }

        return true
    }

    /**
     * Incoming: HMI (192.168.1.12) ➔ Host / VPN / Tailscale
     * Performs Reverse NAT (DNAT): rewrites Destination IP from 192.168.1.100 back to origSrcIp (100.64.X.X).
     * 
     * @param packet Raw IPv4 packet (after Ethernet header is stripped)
     * @param offset Offset where IPv4 header starts
     * @param length Length of IPv4 packet
     * @return true if packet was successfully de-masqueraded and ready for injection into tunnel
     */
    fun processIncomingPacket(packet: ByteArray, offset: Int, length: Int): Boolean {
        if (length < 20) return false
        val versionAndIhl = packet[offset].toInt() and 0xFF
        if ((versionAndIhl shr 4) != 4) return false
        val ihl = (versionAndIhl and 0x0F) * 4
        if (ihl < 20 || offset + ihl > offset + length) return false

        val protocol = packet[offset + 9].toInt() and 0xFF
        val dstIp = ByteArray(4)
        System.arraycopy(packet, offset + 16, dstIp, 0, 4)
        val adapterIp = ArpEngine.parseIp(config.adapterIp)

        // Only de-masquerade packets targeting our virtual adapter IP (192.168.1.100)
        if (!dstIp.contentEquals(adapterIp)) return false

        if (protocol == 6 || protocol == 17) {
            val l4Offset = offset + ihl
            if (l4Offset + 4 > offset + length) return false

            val srcPort = ((packet[l4Offset].toInt() and 0xFF) shl 8) or (packet[l4Offset + 1].toInt() and 0xFF)
            val masqDstPort = ((packet[l4Offset + 2].toInt() and 0xFF) shl 8) or (packet[l4Offset + 3].toInt() and 0xFF)

            val session = natByMasqPort[masqDstPort] ?: run {
                // If FTPS dynamic passive port was pre-registered or matches active ALG
                if (ftpsAlg.isDynamicPortActive(masqDstPort)) {
                    // Fallback to most recent session for HMI
                    val fallback = natByMasqPort.values.firstOrNull { it.targetDstPort == 21 || it.targetDstPort == 8000 }
                    fallback?.copy(masqPort = masqDstPort, origSrcPort = masqDstPort)
                } else {
                    null
                }
            }

            if (session == null) {
                Log.w(TAG, "No NAT session for incoming packet on port $masqDstPort (protocol=$protocol)")
                return false
            }

            session.lastSeenMs = System.currentTimeMillis()

            // Check if HMI is sending FTPS passive reply (227 Entering Passive Mode)
            if (protocol == 6 && (srcPort == 21 || srcPort == 8000)) {
                val tcpDataOffset = ((packet[l4Offset + 12].toInt() and 0xF0) shr 4) * 4
                val payloadOffset = l4Offset + tcpDataOffset
                val payloadLen = length - (ihl + tcpDataOffset)
                if (payloadLen > 0 && payloadOffset + payloadLen <= offset + length) {
                    val pasvPort = ftpsAlg.inspectPayload(packet, payloadOffset, payloadLen)
                    if (pasvPort != null) {
                        Log.i(TAG, "FTPS ALG registered dynamic passive port: $pasvPort for GP-Pro EX")
                        // Pre-register dynamic NAT mapping for GP-Pro EX host
                        registerDynamicPasvSession(session.origSrcIp, pasvPort)
                    }
                }
            }

            // Perform Reverse DNAT: 192.168.1.100:masqDstPort ➔ origSrcIp:origSrcPort
            applyDnat(packet, offset, ihl, l4Offset, adapterIp, session.origSrcIp, masqDstPort, session.origSrcPort, protocol)
            return true
        }

        return false
    }

    private fun registerDynamicPasvSession(clientIp: ByteArray, port: Int) {
        val session = NatSession(
            protocol = 6,
            origSrcIp = clientIp,
            origSrcPort = port,
            targetDstIp = ArpEngine.parseIp(config.targetPlcIp),
            targetDstPort = port,
            masqPort = port,
            lastSeenMs = System.currentTimeMillis()
        )
        natByMasqPort[port] = session
        val clientKey = "6:${ArpEngine.formatIp(clientIp)}:$port:$port"
        natByClient[clientKey] = port
    }

    private fun allocatePort(): Int {
        var p = portCounter.incrementAndGet()
        if (p > 65000) {
            portCounter.set(45000)
            p = 45000
        }
        return p
    }

    /**
     * SNAT: Replaces Source IP (and optionally Source Port) in IPv4 + TCP/UDP header.
     * Updates checksums incrementally (RFC 1624).
     */
    private fun applySnat(
        packet: ByteArray,
        ipOffset: Int,
        ihl: Int,
        l4Offset: Int,
        oldIp: ByteArray,
        newIp: ByteArray,
        oldPort: Int,
        newPort: Int,
        protocol: Int
    ) {
        // 1. Update IPv4 Header Checksum
        val oldIpChecksum = ((packet[ipOffset + 10].toInt() and 0xFF) shl 8) or (packet[ipOffset + 11].toInt() and 0xFF)
        val oldIpWord1 = ((oldIp[0].toInt() and 0xFF) shl 8) or (oldIp[1].toInt() and 0xFF)
        val oldIpWord2 = ((oldIp[2].toInt() and 0xFF) shl 8) or (oldIp[3].toInt() and 0xFF)
        val newIpWord1 = ((newIp[0].toInt() and 0xFF) shl 8) or (newIp[1].toInt() and 0xFF)
        val newIpWord2 = ((newIp[2].toInt() and 0xFF) shl 8) or (newIp[3].toInt() and 0xFF)

        var newIpChecksum = ChecksumUtils.updateChecksum16(oldIpChecksum, oldIpWord1, newIpWord1)
        newIpChecksum = ChecksumUtils.updateChecksum16(newIpChecksum, oldIpWord2, newIpWord2)

        packet[ipOffset + 10] = ((newIpChecksum shr 8) and 0xFF).toByte()
        packet[ipOffset + 11] = (newIpChecksum and 0xFF).toByte()

        // Write new Source IP
        System.arraycopy(newIp, 0, packet, ipOffset + 12, 4)

        // 2. Update TCP or UDP Checksum
        val csumOffset = if (protocol == 6) l4Offset + 16 else l4Offset + 6
        var l4Checksum = ((packet[csumOffset].toInt() and 0xFF) shl 8) or (packet[csumOffset + 1].toInt() and 0xFF)

        if (protocol != 17 || l4Checksum != 0) { // If UDP checksum is 0, it means disabled
            // Update for Source IP change in pseudo-header
            l4Checksum = ChecksumUtils.updateChecksum16(l4Checksum, oldIpWord1, newIpWord1)
            l4Checksum = ChecksumUtils.updateChecksum16(l4Checksum, oldIpWord2, newIpWord2)

            // Update for Source Port change if needed
            if (oldPort != newPort) {
                l4Checksum = ChecksumUtils.updateChecksum16(l4Checksum, oldPort, newPort)
                packet[l4Offset] = ((newPort shr 8) and 0xFF).toByte()
                packet[l4Offset + 1] = (newPort and 0xFF).toByte()
            }

            packet[csumOffset] = ((l4Checksum shr 8) and 0xFF).toByte()
            packet[csumOffset + 1] = (l4Checksum and 0xFF).toByte()
        }
    }

    /**
     * DNAT: Replaces Destination IP (and optionally Destination Port) in IPv4 + TCP/UDP header.
     * Updates checksums incrementally (RFC 1624).
     */
    private fun applyDnat(
        packet: ByteArray,
        ipOffset: Int,
        ihl: Int,
        l4Offset: Int,
        oldIp: ByteArray,
        newIp: ByteArray,
        oldPort: Int,
        newPort: Int,
        protocol: Int
    ) {
        // 1. Update IPv4 Header Checksum
        val oldIpChecksum = ((packet[ipOffset + 10].toInt() and 0xFF) shl 8) or (packet[ipOffset + 11].toInt() and 0xFF)
        val oldIpWord1 = ((oldIp[0].toInt() and 0xFF) shl 8) or (oldIp[1].toInt() and 0xFF)
        val oldIpWord2 = ((oldIp[2].toInt() and 0xFF) shl 8) or (oldIp[3].toInt() and 0xFF)
        val newIpWord1 = ((newIp[0].toInt() and 0xFF) shl 8) or (newIp[1].toInt() and 0xFF)
        val newIpWord2 = ((newIp[2].toInt() and 0xFF) shl 8) or (newIp[3].toInt() and 0xFF)

        var newIpChecksum = ChecksumUtils.updateChecksum16(oldIpChecksum, oldIpWord1, newIpWord1)
        newIpChecksum = ChecksumUtils.updateChecksum16(newIpChecksum, oldIpWord2, newIpWord2)

        packet[ipOffset + 10] = ((newIpChecksum shr 8) and 0xFF).toByte()
        packet[ipOffset + 11] = (newIpChecksum and 0xFF).toByte()

        // Write new Destination IP
        System.arraycopy(newIp, 0, packet, ipOffset + 16, 4)

        // 2. Update TCP or UDP Checksum
        val csumOffset = if (protocol == 6) l4Offset + 16 else l4Offset + 6
        var l4Checksum = ((packet[csumOffset].toInt() and 0xFF) shl 8) or (packet[csumOffset + 1].toInt() and 0xFF)

        if (protocol != 17 || l4Checksum != 0) {
            // Update for Destination IP change in pseudo-header
            l4Checksum = ChecksumUtils.updateChecksum16(l4Checksum, oldIpWord1, newIpWord1)
            l4Checksum = ChecksumUtils.updateChecksum16(l4Checksum, oldIpWord2, newIpWord2)

            // Update for Destination Port change if needed
            if (oldPort != newPort) {
                l4Checksum = ChecksumUtils.updateChecksum16(l4Checksum, oldPort, newPort)
                packet[l4Offset + 2] = ((newPort shr 8) and 0xFF).toByte()
                packet[l4Offset + 3] = (newPort and 0xFF).toByte()
            }

            packet[csumOffset] = ((l4Checksum shr 8) and 0xFF).toByte()
            packet[csumOffset + 1] = (l4Checksum and 0xFF).toByte()
        }
    }

    private fun applyIpOnlySnat(packet: ByteArray, ipOffset: Int, ihl: Int, oldIp: ByteArray, newIp: ByteArray) {
        val oldIpChecksum = ((packet[ipOffset + 10].toInt() and 0xFF) shl 8) or (packet[ipOffset + 11].toInt() and 0xFF)
        val oldIpWord1 = ((oldIp[0].toInt() and 0xFF) shl 8) or (oldIp[1].toInt() and 0xFF)
        val oldIpWord2 = ((oldIp[2].toInt() and 0xFF) shl 8) or (oldIp[3].toInt() and 0xFF)
        val newIpWord1 = ((newIp[0].toInt() and 0xFF) shl 8) or (newIp[1].toInt() and 0xFF)
        val newIpWord2 = ((newIp[2].toInt() and 0xFF) shl 8) or (newIp[3].toInt() and 0xFF)

        var newIpChecksum = ChecksumUtils.updateChecksum16(oldIpChecksum, oldIpWord1, newIpWord1)
        newIpChecksum = ChecksumUtils.updateChecksum16(newIpChecksum, oldIpWord2, newIpWord2)

        packet[ipOffset + 10] = ((newIpChecksum shr 8) and 0xFF).toByte()
        packet[ipOffset + 11] = (newIpChecksum and 0xFF).toByte()
        System.arraycopy(newIp, 0, packet, ipOffset + 12, 4)
    }
}
