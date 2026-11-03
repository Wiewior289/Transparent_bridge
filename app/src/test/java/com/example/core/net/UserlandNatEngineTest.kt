package com.example.core.net

import com.example.core.model.EthernetConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserlandNatEngineTest {
    private val clientIp = byteArrayOf(100.toByte(), 64, 1, 2)
    private val plcIp = byteArrayOf(192.toByte(), 168.toByte(), 1, 12)
    private val adapterIp = byteArrayOf(192.toByte(), 168.toByte(), 1, 100)

    @Test
    fun udpFlowUsesExactReverseTuple() {
        val engine = UserlandNatEngine(EthernetConfig(adapterIp = "192.168.1.100", mtu = 1360, targetMss = 1320))
        val outgoing = buildUdp(clientIp, plcIp, 43122, 502, checksumEnabled = false)

        assertTrue(engine.processOutgoingPacket(outgoing, 0, outgoing.size))
        assertIp(adapterIp, outgoing, 12)
        val translatedPort = u16(outgoing, 20)
        assertEquals(43122, translatedPort)

        val validReply = buildUdp(plcIp, adapterIp, 502, translatedPort, checksumEnabled = false)
        assertTrue(engine.processIncomingPacket(validReply, 0, validReply.size))
        assertIp(clientIp, validReply, 16)
        assertEquals(43122, u16(validReply, 22))

        val foreignPeer = buildUdp(
            byteArrayOf(192.toByte(), 168.toByte(), 1, 99),
            adapterIp,
            502,
            translatedPort,
            checksumEnabled = false
        )
        assertFalse(engine.processIncomingPacket(foreignPeer, 0, foreignPeer.size))
    }

    @Test
    fun icmpEchoIdentifierIsTranslatedAndRestored() {
        val engine = UserlandNatEngine(EthernetConfig(adapterIp = "192.168.1.100", mtu = 1360, targetMss = 1320))
        val request = buildIcmp(clientIp, plcIp, type = 8, identifier = 0x1234)

        assertTrue(engine.processOutgoingPacket(request, 0, request.size))
        assertIp(adapterIp, request, 12)
        val translatedId = u16(request, 24)

        val reply = buildIcmp(plcIp, adapterIp, type = 0, identifier = translatedId)
        assertTrue(engine.processIncomingPacket(reply, 0, reply.size))
        assertIp(clientIp, reply, 16)
        assertEquals(0x1234, u16(reply, 24))
        assertEquals(0, checksum(reply, 20, reply.size - 20))
    }

    @Test
    fun fragmentedIpv4IsRejected() {
        val engine = UserlandNatEngine(EthernetConfig())
        val packet = buildUdp(clientIp, plcIp, 43122, 502, checksumEnabled = false)
        putU16(packet, 6, 0x2000) // More Fragments.
        refreshIpChecksum(packet)
        assertFalse(engine.processOutgoingPacket(packet, 0, packet.size))
    }

    @Test
    fun malformedIpLengthIsRejected() {
        val engine = UserlandNatEngine(EthernetConfig())
        val packet = buildUdp(clientIp, plcIp, 43122, 502, checksumEnabled = false)
        putU16(packet, 2, packet.size + 1)
        refreshIpChecksum(packet)
        assertFalse(engine.processOutgoingPacket(packet, 0, packet.size))
    }

    private fun buildUdp(
        source: ByteArray,
        destination: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        checksumEnabled: Boolean
    ): ByteArray {
        val payload = byteArrayOf(0x31, 0x32, 0x33, 0x34)
        val packet = ByteArray(20 + 8 + payload.size)
        packet[0] = 0x45
        putU16(packet, 2, packet.size)
        putU16(packet, 4, 0x1201)
        putU16(packet, 6, 0x4000)
        packet[8] = 64
        packet[9] = 17
        System.arraycopy(source, 0, packet, 12, 4)
        System.arraycopy(destination, 0, packet, 16, 4)
        putU16(packet, 20, sourcePort)
        putU16(packet, 22, destinationPort)
        putU16(packet, 24, 8 + payload.size)
        System.arraycopy(payload, 0, packet, 28, payload.size)
        putU16(packet, 10, checksum(packet, 0, 20))
        if (checksumEnabled) putU16(packet, 26, udpChecksum(packet))
        return packet
    }

    private fun buildIcmp(source: ByteArray, destination: ByteArray, type: Int, identifier: Int): ByteArray {
        val packet = ByteArray(20 + 8 + 4)
        packet[0] = 0x45
        putU16(packet, 2, packet.size)
        putU16(packet, 4, 0x2211)
        putU16(packet, 6, 0x4000)
        packet[8] = 64
        packet[9] = 1
        System.arraycopy(source, 0, packet, 12, 4)
        System.arraycopy(destination, 0, packet, 16, 4)
        packet[20] = type.toByte()
        packet[21] = 0
        putU16(packet, 24, identifier)
        putU16(packet, 26, 1)
        packet[28] = 1
        packet[29] = 2
        packet[30] = 3
        packet[31] = 4
        putU16(packet, 22, checksum(packet, 20, packet.size - 20))
        putU16(packet, 10, checksum(packet, 0, 20))
        return packet
    }

    private fun udpChecksum(packet: ByteArray): Int {
        var sum = 0L
        sum += u16(packet, 12).toLong()
        sum += u16(packet, 14).toLong()
        sum += u16(packet, 16).toLong()
        sum += u16(packet, 18).toLong()
        sum += 17
        sum += (packet.size - 20).toLong()
        var p = 20
        while (p + 1 < packet.size) {
            sum += u16(packet, p).toLong()
            p += 2
        }
        while ((sum ushr 16) != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        val result = sum.inv().toInt() and 0xFFFF
        return if (result == 0) 0xFFFF else result
    }

    private fun refreshIpChecksum(packet: ByteArray) {
        putU16(packet, 10, 0)
        putU16(packet, 10, checksum(packet, 0, 20))
    }

    private fun checksum(packet: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var p = offset
        val end = offset + length
        while (p + 1 < end) {
            sum += u16(packet, p).toLong()
            p += 2
        }
        if (p < end) sum += ((packet[p].toInt() and 0xFF) shl 8).toLong()
        while ((sum ushr 16) != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv().toInt() and 0xFFFF
    }

    private fun assertIp(expected: ByteArray, packet: ByteArray, offset: Int) {
        for (i in 0 until 4) assertEquals(expected[i].toInt() and 0xFF, packet[offset + i].toInt() and 0xFF)
    }

    private fun u16(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)

    private fun putU16(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = (value ushr 8).toByte()
        packet[offset + 1] = value.toByte()
    }
}
