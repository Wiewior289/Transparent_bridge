package com.example

import com.example.core.net.ArpEngine
import com.example.core.net.MssClamper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPrimitivesTest {

    @Test
    fun arpRequestForLocalAddressProducesPaddedReplyAndLearnsPeer() {
        val localMac = ArpEngine.parseMac("02:00:00:00:00:01")
        val peerMac = ArpEngine.parseMac("00:0B:E0:12:34:56")
        val arp = ArpEngine(
            virtualMac = localMac,
            adapterIp = ArpEngine.parseIp("192.168.1.100")
        )

        val request = ByteArray(60)
        for (i in 0 until 6) request[i] = 0xFF.toByte()
        System.arraycopy(peerMac, 0, request, 6, 6)
        request[12] = 0x08
        request[13] = 0x06

        // Ethernet/IPv4 ARP request.
        request[14] = 0x00
        request[15] = 0x01
        request[16] = 0x08.toByte()
        request[17] = 0x00
        request[18] = 6
        request[19] = 4
        request[20] = 0
        request[21] = 1
        System.arraycopy(peerMac, 0, request, 22, 6)
        System.arraycopy(ArpEngine.parseIp("192.168.1.12"), 0, request, 28, 4)
        // Target MAC is zero in a who-has request.
        System.arraycopy(ArpEngine.parseIp("192.168.1.100"), 0, request, 38, 4)

        val reply = arp.processIncomingFrame(request, request.size)
        assertNotNull(reply)
        assertEquals(60, reply!!.size)
        assertEquals("00:0B:E0:12:34:56", ArpEngine.formatMac(reply.copyOfRange(0, 6)))
        assertEquals("02:00:00:00:00:01", ArpEngine.formatMac(reply.copyOfRange(6, 12)))

        val cachedPeer = arp.getMacForIp("192.168.1.12")
        assertNotNull(cachedPeer)
        assertEquals("00:0B:E0:12:34:56", ArpEngine.formatMac(cachedPeer!!))
    }

    @Test
    fun ethernetIpv4FrameIsPaddedToMinimumFrameLength() {
        val arp = ArpEngine(
            virtualMac = ArpEngine.parseMac("E4:66:E5:6D:C3:08"),
            adapterIp = ArpEngine.parseIp("192.168.1.100")
        )
        val ipPacket = ByteArray(28)
        ipPacket[0] = 0x45
        val peerMac = ArpEngine.parseMac("00:01:23:26:80:0C")

        val frame = arp.wrapInEthernetHeader(ipPacket, 0, ipPacket.size, peerMac)
        assertEquals(60, frame.size)
        assertEquals(0x0800, ((frame[12].toInt() and 0xFF) shl 8) or (frame[13].toInt() and 0xFF))
        assertEquals("00:01:23:26:80:0C", ArpEngine.formatMac(frame.copyOfRange(0, 6)))
        assertEquals("E4:66:E5:6D:C3:08", ArpEngine.formatMac(frame.copyOfRange(6, 12)))
    }

    @Test
    fun pendingArpQueueReturnsQueuedPacketsAndDrainsOnce() {
        val arp = ArpEngine()
        val packet = ByteArray(28)
        packet[0] = 0x45

        assertTrue(arp.queuePendingPacket("192.168.1.12", packet))
        val pending = arp.getAndClearPendingPackets("192.168.1.12")
        assertEquals(1, pending.size)
        assertEquals(packet.size, pending.single().size)
        assertTrue(arp.getAndClearPendingPackets("192.168.1.12").isEmpty())
    }

    @Test
    fun tcpSynMssIsClampedToEffectiveTunnelValue() {
        // IPv4 (20 B) + TCP (20 B) + MSS option (4 B).
        val packet = ByteArray(44)
        packet[0] = 0x45
        packet[2] = 0
        packet[3] = 44
        packet[9] = 6

        val tcp = 20
        packet[tcp + 12] = 0x60 // TCP data offset: 24 bytes.
        packet[tcp + 13] = 0x02 // SYN.
        packet[tcp + 20] = 0x02 // MSS option kind.
        packet[tcp + 21] = 0x04 // MSS option length.
        packet[tcp + 22] = 0x05
        packet[tcp + 23] = 0xB4.toByte() // 1460.

        assertTrue(MssClamper.clampMssInPlace(packet, 0, packet.size, targetMss = 1320))
        val newMss = ((packet[tcp + 22].toInt() and 0xFF) shl 8) or
            (packet[tcp + 23].toInt() and 0xFF)
        assertEquals(1320, newMss)
    }
}
