package com.example.core.net

import com.example.core.model.EthernetConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * IPv4 user-space SNAT/DNAT with exact forward and reverse flow keys.
 *
 * Supported transports: TCP, UDP and ICMP Echo only.
 * IPv4 fragments and unsupported protocols are rejected explicitly.
 * FTP/FTPS payload inspection is intentionally absent: PASV data sockets are
 * ordinary independent TCP flows and receive normal conntrack entries.
 */
class UserlandNatEngine(
    var config: EthernetConfig = EthernetConfig()
) {
    companion object {
        private const val SESSION_TIMEOUT_MS = 180_000L
        private const val FIRST_DYNAMIC_PORT = 45_000
        private const val LAST_DYNAMIC_PORT = 65_000
        private const val PROTO_ICMP = 1
        private const val PROTO_TCP = 6
        private const val PROTO_UDP = 17
        private const val ICMP_ECHO_REPLY = 0
        private const val ICMP_ECHO_REQUEST = 8
    }

    data class NatSession(
        val protocol: Int,
        val origSrcIp: ByteArray,
        val origSrcPort: Int,
        val targetDstIp: ByteArray,
        val targetDstPort: Int,
        val masqPort: Int,
        @Volatile var lastSeenMs: Long = System.currentTimeMillis()
    )

    private data class ForwardKey(
        val protocol: Int,
        val sourceIp: Long,
        val sourcePortOrId: Int,
        val destinationIp: Long,
        val destinationPort: Int
    )

    private data class ReverseKey(
        val protocol: Int,
        val remoteIp: Long,
        val remotePort: Int,
        val localIp: Long,
        val translatedPortOrId: Int
    )

    private data class Ipv4View(
        val offset: Int,
        val headerLength: Int,
        val totalLength: Int,
        val protocol: Int,
        val sourceIp: Long,
        val destinationIp: Long,
        val transportOffset: Int,
        val transportLength: Int
    )

    private val forward = ConcurrentHashMap<ForwardKey, NatSession>()
    private val reverse = ConcurrentHashMap<ReverseKey, NatSession>()
    private val allocationLock = Any()
    private val nextPort = AtomicInteger(FIRST_DYNAMIC_PORT - 1)

    val activeSessionCount: Int
        get() {
            cleanExpiredSessions()
            return forward.size
        }

    fun getActiveSessions(): List<NatSession> {
        cleanExpiredSessions()
        return forward.values.toList()
    }

    /**
     * Tailscale/UserlandTun -> PLC. Mutates the packet in place.
     * Returns false for malformed, fragmented, unsupported or unmapped traffic.
     */
    fun processOutgoingPacket(packet: ByteArray, offset: Int, length: Int): Boolean {
        val ip = parseIpv4(packet, offset, length) ?: return false
        if (ip.protocol != PROTO_TCP && ip.protocol != PROTO_UDP && ip.protocol != PROTO_ICMP) return false
        if (!validTransport(packet, ip, outbound = true)) return false
        if (ip.ttl(packet) <= 1) return false

        val adapterIpBytes = parseIpv4Address(config.adapterIp) ?: return false
        val adapterIp = ipToLong(adapterIpBytes, 0)
        if (ip.sourceIp == adapterIp) return false

        when (ip.protocol) {
            PROTO_TCP, PROTO_UDP -> {
                val sourcePort = readU16(packet, ip.transportOffset)
                val destinationPort = readU16(packet, ip.transportOffset + 2)
                val key = ForwardKey(ip.protocol, ip.sourceIp, sourcePort, ip.destinationIp, destinationPort)
                val session = findOrCreateSession(
                    key, ip.protocol, ip.sourceIp, sourcePort,
                    ip.destinationIp, destinationPort, adapterIp, sourcePort
                ) ?: return false

                if (ip.protocol == PROTO_TCP && config.isMssClampingEnabled) {
                    val effectiveMss = minOf(config.targetMss, config.mtu - 40).coerceAtLeast(536)
                    MssClamper.clampMssInPlace(packet, offset, ip.totalLength, effectiveMss)
                }

                if (!rewriteTransportAddressAndPort(
                        packet, ip, oldSourceIp = ip.sourceIp, newSourceIp = adapterIp,
                        oldDestinationIp = ip.destinationIp, newDestinationIp = ip.destinationIp,
                        portOffset = ip.transportOffset, portOld = sourcePort, portNew = session.masqPort,
                        rewriteSourcePort = true
                    )) return false

                if (!decrementTtlAndRefreshIpChecksum(packet, ip)) return false
                session.lastSeenMs = System.currentTimeMillis()
                return true
            }

            PROTO_ICMP -> {
                val identifier = readU16(packet, ip.transportOffset + 4)
                val key = ForwardKey(PROTO_ICMP, ip.sourceIp, identifier, ip.destinationIp, 0)
                val session = findOrCreateSession(
                    key, PROTO_ICMP, ip.sourceIp, identifier,
                    ip.destinationIp, 0, adapterIp, identifier
                ) ?: return false

                rewriteIpv4AddressAndTransportChecksum(
                    packet, ip, source = true, oldIp = ip.sourceIp, newIp = adapterIp,
                    transportWordOffset = ip.transportOffset + 4,
                    oldTransportWord = identifier, newTransportWord = session.masqPort
                )
                if (!decrementTtlAndRefreshIpChecksum(packet, ip)) return false
                session.lastSeenMs = System.currentTimeMillis()
                return true
            }
        }
        return false
    }

    /**
     * PLC -> Tailscale/UserlandTun. Accepts only a reply matching the remote
     * endpoint and translated local endpoint from an existing flow.
     */
    fun processIncomingPacket(packet: ByteArray, offset: Int, length: Int): Boolean {
        val ip = parseIpv4(packet, offset, length) ?: return false
        if (ip.protocol != PROTO_TCP && ip.protocol != PROTO_UDP && ip.protocol != PROTO_ICMP) return false
        if (!validTransport(packet, ip, outbound = false)) return false
        if (ip.ttl(packet) <= 1) return false

        val adapterIpBytes = parseIpv4Address(config.adapterIp) ?: return false
        val adapterIp = ipToLong(adapterIpBytes, 0)
        if (ip.destinationIp != adapterIp) return false

        when (ip.protocol) {
            PROTO_TCP, PROTO_UDP -> {
                val sourcePort = readU16(packet, ip.transportOffset)
                val translatedDestinationPort = readU16(packet, ip.transportOffset + 2)
                val key = ReverseKey(
                    ip.protocol, ip.sourceIp, sourcePort, adapterIp, translatedDestinationPort
                )
                val session = reverse[key] ?: return false

                if (!rewriteTransportAddressAndPort(
                        packet, ip, oldSourceIp = ip.sourceIp, newSourceIp = ip.sourceIp,
                        oldDestinationIp = adapterIp, newDestinationIp = ipToLong(session.origSrcIp, 0),
                        portOffset = ip.transportOffset + 2,
                        portOld = translatedDestinationPort, portNew = session.origSrcPort,
                        rewriteSourcePort = false
                    )) return false

                if (!decrementTtlAndRefreshIpChecksum(packet, ip)) return false
                session.lastSeenMs = System.currentTimeMillis()
                return true
            }

            PROTO_ICMP -> {
                val type = u8(packet, ip.transportOffset)
                val code = u8(packet, ip.transportOffset + 1)
                if (type != ICMP_ECHO_REPLY || code != 0) return false
                val translatedId = readU16(packet, ip.transportOffset + 4)
                val key = ReverseKey(PROTO_ICMP, ip.sourceIp, 0, adapterIp, translatedId)
                val session = reverse[key] ?: return false

                rewriteIpv4AddressAndTransportChecksum(
                    packet, ip, source = false,
                    oldIp = adapterIp, newIp = ipToLong(session.origSrcIp, 0),
                    transportWordOffset = ip.transportOffset + 4,
                    oldTransportWord = translatedId, newTransportWord = session.origSrcPort
                )
                if (!decrementTtlAndRefreshIpChecksum(packet, ip)) return false
                session.lastSeenMs = System.currentTimeMillis()
                return true
            }
        }
        return false
    }

    private fun findOrCreateSession(
        forwardKey: ForwardKey,
        protocol: Int,
        originalSourceIp: Long,
        originalSourcePortOrId: Int,
        remoteIp: Long,
        remotePort: Int,
        adapterIp: Long,
        preferredTranslatedPort: Int
    ): NatSession? = synchronized(allocationLock) {
        val existing = forward[forwardKey]
        if (existing != null) {
            existing.lastSeenMs = System.currentTimeMillis()
            return@synchronized existing
        }

        val translated = allocateTranslatedPort(
            protocol, remoteIp, remotePort, adapterIp, preferredTranslatedPort
        ) ?: return@synchronized null

        val session = NatSession(
            protocol = protocol,
            origSrcIp = longToIp(originalSourceIp),
            origSrcPort = originalSourcePortOrId,
            targetDstIp = longToIp(remoteIp),
            targetDstPort = remotePort,
            masqPort = translated
        )
        val reverseKey = ReverseKey(protocol, remoteIp, remotePort, adapterIp, translated)
        forward[forwardKey] = session
        reverse[reverseKey] = session
        session
    }

    private fun allocateTranslatedPort(
        protocol: Int,
        remoteIp: Long,
        remotePort: Int,
        adapterIp: Long,
        preferred: Int
    ): Int? {
        fun available(candidate: Int): Boolean =
            candidate in 0..65535 &&
                !reverse.containsKey(ReverseKey(protocol, remoteIp, remotePort, adapterIp, candidate))

        if (available(preferred)) return preferred

        val span = LAST_DYNAMIC_PORT - FIRST_DYNAMIC_PORT + 1
        repeat(span) {
            var candidate = nextPort.incrementAndGet()
            if (candidate > LAST_DYNAMIC_PORT) {
                nextPort.set(FIRST_DYNAMIC_PORT)
                candidate = FIRST_DYNAMIC_PORT
            }
            if (available(candidate)) return candidate
        }
        return null
    }

    private fun cleanExpiredSessions() {
        val now = System.currentTimeMillis()
        for ((key, session) in forward.entries) {
            if (now - session.lastSeenMs <= SESSION_TIMEOUT_MS) continue
            if (forward.remove(key, session)) {
                val adapterIp = parseIpv4Address(config.adapterIp)?.let { ipToLong(it, 0) } ?: continue
                val remoteIp = ipToLong(session.targetDstIp, 0)
                val remotePort = if (session.protocol == PROTO_ICMP) 0 else session.targetDstPort
                reverse.remove(
                    ReverseKey(session.protocol, remoteIp, remotePort, adapterIp, session.masqPort),
                    session
                )
            }
        }
    }

    private fun parseIpv4(packet: ByteArray, offset: Int, length: Int): Ipv4View? {
        if (offset < 0 || length < 20 || offset > packet.size - length) return null
        val versionAndIhl = u8(packet, offset)
        if ((versionAndIhl ushr 4) != 4) return null
        val headerLength = (versionAndIhl and 0x0F) * 4
        if (headerLength < 20 || headerLength > length) return null

        val totalLength = readU16(packet, offset + 2)
        if (totalLength < headerLength || totalLength > length) return null

        // Fragment offset or MF means this packet cannot be translated safely here.
        // DF is allowed; the reserved flag is rejected.
        val flagsAndOffset = readU16(packet, offset + 6)
        if ((flagsAndOffset and 0xBFFF) != 0) return null

        if (ChecksumUtils.computeIpChecksum(packet, offset, headerLength) != 0) return null
        val transportLength = totalLength - headerLength
        val protocol = u8(packet, offset + 9)
        return Ipv4View(
            offset = offset,
            headerLength = headerLength,
            totalLength = totalLength,
            protocol = protocol,
            sourceIp = ipToLong(packet, offset + 12),
            destinationIp = ipToLong(packet, offset + 16),
            transportOffset = offset + headerLength,
            transportLength = transportLength
        )
    }

    private fun validTransport(packet: ByteArray, ip: Ipv4View, outbound: Boolean): Boolean {
        val l4 = ip.transportOffset
        val length = ip.transportLength
        return when (ip.protocol) {
            PROTO_TCP -> {
                if (length < 20) return false
                val tcpHeaderLength = (u8(packet, l4 + 12) ushr 4) * 4
                if (tcpHeaderLength < 20 || tcpHeaderLength > length) return false
                transportChecksumValid(packet, ip, length, 16, false)
            }
            PROTO_UDP -> {
                if (length < 8) return false
                val udpLength = readU16(packet, l4 + 4)
                if (udpLength != length || udpLength < 8) return false
                val checksum = readU16(packet, l4 + 6)
                checksum == 0 || transportChecksumValid(packet, ip, udpLength, 6, true)
            }
            PROTO_ICMP -> {
                if (length < 8) return false
                val type = u8(packet, l4)
                val code = u8(packet, l4 + 1)
                val expectedType = if (outbound) ICMP_ECHO_REQUEST else ICMP_ECHO_REPLY
                type == expectedType && code == 0 &&
                    ChecksumUtils.computeIpChecksum(packet, l4, length) == 0
            }
            else -> false
        }
    }

    private fun transportChecksumValid(
        packet: ByteArray,
        ip: Ipv4View,
        transportLength: Int,
        checksumOffset: Int,
        udp: Boolean
    ): Boolean {
        val checksum = readU16(packet, ip.transportOffset + checksumOffset)
        if (udp && checksum == 0) return true
        var sum = 0L
        sum += (ip.sourceIp ushr 16) and 0xFFFF
        sum += ip.sourceIp and 0xFFFF
        sum += (ip.destinationIp ushr 16) and 0xFFFF
        sum += ip.destinationIp and 0xFFFF
        sum += ip.protocol.toLong()
        sum += transportLength.toLong()

        var p = ip.transportOffset
        val end = p + transportLength
        while (p + 1 < end) {
            sum += ((u8(packet, p) shl 8) or u8(packet, p + 1)).toLong()
            p += 2
        }
        if (p < end) sum += (u8(packet, p) shl 8).toLong()
        while ((sum ushr 16) != 0L) sum = (sum and 0xFFFFL) + (sum ushr 16)
        return sum == 0xFFFFL
    }

    private fun rewriteTransportAddressAndPort(
        packet: ByteArray,
        ip: Ipv4View,
        oldSourceIp: Long,
        newSourceIp: Long,
        oldDestinationIp: Long,
        newDestinationIp: Long,
        portOffset: Int,
        portOld: Int,
        portNew: Int,
        rewriteSourcePort: Boolean
    ): Boolean {
        val checksumOffset = when (ip.protocol) {
            PROTO_TCP -> ip.transportOffset + 16
            PROTO_UDP -> ip.transportOffset + 6
            else -> return false
        }
        var checksum = readU16(packet, checksumOffset)
        val isUdpZero = ip.protocol == PROTO_UDP && checksum == 0
        if (!isUdpZero) {
            checksum = updateChecksumForAddress(checksum, oldSourceIp, newSourceIp)
            checksum = updateChecksumForAddress(checksum, oldDestinationIp, newDestinationIp)
            checksum = ChecksumUtils.updateChecksum16(checksum, portOld, portNew)
            if (ip.protocol == PROTO_UDP && checksum == 0) checksum = 0xFFFF
            writeU16(packet, checksumOffset, checksum)
        }
        if (rewriteSourcePort) writeU16(packet, portOffset, portNew)
        else writeU16(packet, portOffset, portNew)

        if (newSourceIp != oldSourceIp) writeIp(packet, ip.offset + 12, newSourceIp)
        if (newDestinationIp != oldDestinationIp) writeIp(packet, ip.offset + 16, newDestinationIp)
        return true
    }

    private fun rewriteIpv4AddressAndTransportChecksum(
        packet: ByteArray,
        ip: Ipv4View,
        source: Boolean,
        oldIp: Long,
        newIp: Long,
        transportWordOffset: Int,
        oldTransportWord: Int,
        newTransportWord: Int
    ) {
        val checksumOffset = ip.transportOffset + 2
        var checksum = readU16(packet, checksumOffset)
        checksum = ChecksumUtils.updateChecksum16(checksum, oldTransportWord, newTransportWord)
        writeU16(packet, checksumOffset, checksum)
        writeU16(packet, transportWordOffset, newTransportWord)
        writeIp(packet, ip.offset + if (source) 12 else 16, newIp)
    }

    private fun updateChecksumForAddress(checksum: Int, oldIp: Long, newIp: Long): Int {
        var updated = ChecksumUtils.updateChecksum16(checksum, ((oldIp ushr 16) and 0xFFFF).toInt(),
            ((newIp ushr 16) and 0xFFFF).toInt())
        updated = ChecksumUtils.updateChecksum16(updated, (oldIp and 0xFFFF).toInt(),
            (newIp and 0xFFFF).toInt())
        return updated
    }

    private fun decrementTtlAndRefreshIpChecksum(packet: ByteArray, ip: Ipv4View): Boolean {
        val ttl = u8(packet, ip.offset + 8)
        if (ttl <= 1) return false
        packet[ip.offset + 8] = (ttl - 1).toByte()
        writeU16(packet, ip.offset + 10, 0)
        val checksum = ChecksumUtils.computeIpChecksum(packet, ip.offset, ip.headerLength)
        writeU16(packet, ip.offset + 10, checksum)
        return true
    }

    private fun writeIp(packet: ByteArray, offset: Int, ip: Long) {
        packet[offset] = (ip ushr 24).toByte()
        packet[offset + 1] = (ip ushr 16).toByte()
        packet[offset + 2] = (ip ushr 8).toByte()
        packet[offset + 3] = ip.toByte()
    }

    private fun readU16(packet: ByteArray, offset: Int): Int =
        (u8(packet, offset) shl 8) or u8(packet, offset + 1)

    private fun writeU16(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = (value ushr 8).toByte()
        packet[offset + 1] = value.toByte()
    }

    private fun u8(packet: ByteArray, offset: Int): Int = packet[offset].toInt() and 0xFF

    private fun ipToLong(bytes: ByteArray, offset: Int): Long =
        ((u8(bytes, offset).toLong() shl 24) or
            (u8(bytes, offset + 1).toLong() shl 16) or
            (u8(bytes, offset + 2).toLong() shl 8) or
            u8(bytes, offset + 3).toLong())

    private fun longToIp(ip: Long): ByteArray = byteArrayOf(
        (ip ushr 24).toByte(), (ip ushr 16).toByte(), (ip ushr 8).toByte(), ip.toByte()
    )

    private fun parseIpv4Address(value: String): ByteArray? {
        val parts = value.split(".")
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0..3) {
            val part = parts[i].toIntOrNull() ?: return null
            if (part !in 0..255) return null
            bytes[i] = part.toByte()
        }
        return bytes
    }

    private fun ipTtl(packet: ByteArray, offset: Int): Int = u8(packet, offset + 8)
    private fun Ipv4View.ttl(packet: ByteArray): Int = ipTtl(packet, offset)
}
