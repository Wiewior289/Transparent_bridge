package com.example

import com.example.core.net.ArpEngine
import com.example.core.net.ChecksumUtils
import com.example.core.net.FtpsAlg
import com.example.core.net.MssClamper
import com.example.core.net.RingBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun testRingBufferReadWrite() {
        val ring = RingBuffer(capacity = 16, blockSize = 64)
        val testData = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        
        val written = ring.write(testData, 0, testData.size)
        assertTrue(written)
        assertEquals(1, ring.currentSize)

        val target = ByteArray(64)
        val readLen = ring.read(target)
        assertEquals(testData.size, readLen)
        assertEquals(1.toByte(), target[0])
        assertEquals(8.toByte(), target[7])
        assertEquals(0, ring.currentSize)
    }

    @Test
    fun testArpEngineSynthesis() {
        val arp = ArpEngine(
            virtualMac = ArpEngine.parseMac("02:00:00:00:00:01"),
            adapterIp = ArpEngine.parseIp("192.168.1.100")
        )

        // Synthesize an ARP Request from HMI (192.168.1.12, MAC 00:0B:E0:12:34:56) for 192.168.1.100
        val requestFrame = ByteArray(42)
        // EtherType ARP (0x0806)
        requestFrame[12] = 0x08; requestFrame[13] = 0x06
        // HW Type 1, Proto 0x0800, HW len 6, Proto len 4, Op 1 (Request)
        requestFrame[14] = 0x00; requestFrame[15] = 0x01
        requestFrame[16] = 0x08; requestFrame[17] = 0x00
        requestFrame[18] = 0x06; requestFrame[19] = 0x04
        requestFrame[20] = 0x00; requestFrame[21] = 0x01

        // Sender MAC: 00:0B:E0:12:34:56
        requestFrame[22] = 0x00; requestFrame[23] = 0x0B.toByte(); requestFrame[24] = 0xE0.toByte()
        requestFrame[25] = 0x12; requestFrame[26] = 0x34; requestFrame[27] = 0x56

        // Sender IP: 192.168.1.12
        requestFrame[28] = 192.toByte(); requestFrame[29] = 168.toByte(); requestFrame[30] = 1; requestFrame[31] = 12

        // Target IP: 192.168.1.100
        requestFrame[38] = 192.toByte(); requestFrame[39] = 168.toByte(); requestFrame[40] = 1; requestFrame[41] = 100

        val reply = arp.processIncomingFrame(requestFrame, 42)
        assertNotNull(reply)
        assertEquals(42, reply!!.size)

        // Target MAC in reply should match HMI's MAC
        assertEquals(0x00.toByte(), reply[0])
        assertEquals(0x0B.toByte(), reply[1])
        assertEquals(0xE0.toByte(), reply[2])

        // Verify ARP table cache has HMI
        val cachedMac = arp.getMacForIp("192.168.1.12")
        assertNotNull(cachedMac)
        assertEquals("00:0B:E0:12:34:56", ArpEngine.formatMac(cachedMac!!))
    }

    @Test
    fun testFtpsAlgPassivePortDetection() {
        val alg = FtpsAlg()

        // Typical response from Pro-face HMI on FTPS port:
        // 227 Entering Passive Mode (192,168,1,12,195,80) -> port 195 * 256 + 80 = 50000
        val pasvReply = "227 Entering Passive Mode (192,168,1,12,195,80)\r\n".toByteArray(Charsets.US_ASCII)
        val port = alg.inspectPayload(pasvReply, 0, pasvReply.size)
        
        assertEquals(50000, port)
        assertTrue(alg.isDynamicPortActive(50000))
    }

    @Test
    fun testMssClamping() {
        // Build mock IPv4 + TCP SYN packet with MSS Option Kind=2, Len=4, Value=1460 (0x05B4)
        val packet = ByteArray(44)
        // IPv4 Header (20 bytes)
        packet[0] = 0x45.toByte() // Version 4, IHL 5 (20 bytes)
        packet[9] = 6             // Protocol TCP
        
        // TCP Header (24 bytes with options)
        val tcpOffset = 20
        packet[tcpOffset + 12] = 0x60.toByte() // Data offset = 6 (24 bytes)
        packet[tcpOffset + 13] = 0x02.toByte() // SYN flag = 1
        
        // TCP MSS Option at tcpOffset + 20
        packet[tcpOffset + 20] = 0x02 // Kind = 2 (MSS)
        packet[tcpOffset + 21] = 0x04 // Len = 4
        packet[tcpOffset + 22] = 0x05 // MSS high byte = 0x05
        packet[tcpOffset + 23] = 0xB4.toByte() // MSS low byte = 0xB4 (1460)

        // Clamping to 1360 (0x0550)
        val clamped = MssClamper.clampMssInPlace(packet, 0, 44, targetMss = 1360)
        assertTrue(clamped)
        
        val newMss = ((packet[tcpOffset + 22].toInt() and 0xFF) shl 8) or (packet[tcpOffset + 23].toInt() and 0xFF)
        assertEquals(1360, newMss)
    }

    @Test
    fun testUserlandNatSnatAndDnat() {
        val nat = com.example.core.net.UserlandNatEngine()
        val packet = ByteArray(44)
        packet[0] = 0x45.toByte() // IPv4, IHL=5
        packet[9] = 6             // TCP
        // Orig Src IP: 100.64.15.22
        packet[12] = 100; packet[13] = 64; packet[14] = 15; packet[15] = 22
        // Dst IP: 192.168.1.12
        packet[16] = 192.toByte(); packet[17] = 168.toByte(); packet[18] = 1; packet[19] = 12

        // TCP Header at offset 20
        // Src Port: 54321 (0xD431)
        packet[20] = 0xD4.toByte(); packet[21] = 0x31
        // Dst Port: 21 (0x0015)
        packet[22] = 0x00; packet[23] = 0x15
        packet[32] = 0x50.toByte() // TCP Header len = 20

        // Process Outgoing SNAT
        val snatResult = nat.processOutgoingPacket(packet, 0, 44)
        assertTrue(snatResult)

        // Check rewritten Source IP is 192.168.1.100
        assertEquals(192.toByte(), packet[12])
        assertEquals(168.toByte(), packet[13])
        assertEquals(1.toByte(), packet[14])
        assertEquals(100.toByte(), packet[15])

        val masqPort = ((packet[20].toInt() and 0xFF) shl 8) or (packet[21].toInt() and 0xFF)

        // Now simulate HMI response:
        // Src IP: 192.168.1.12, Dst IP: 192.168.1.100
        val response = ByteArray(44)
        response[0] = 0x45.toByte()
        response[9] = 6
        response[12] = 192.toByte(); response[13] = 168.toByte(); response[14] = 1; response[15] = 12
        response[16] = 192.toByte(); response[17] = 168.toByte(); response[18] = 1; response[19] = 100

        // Src Port: 21, Dst Port: masqPort
        response[20] = 0x00; response[21] = 0x15
        response[22] = ((masqPort shr 8) and 0xFF).toByte()
        response[23] = (masqPort and 0xFF).toByte()
        response[32] = 0x50.toByte()

        // Process Incoming DNAT
        val dnatResult = nat.processIncomingPacket(response, 0, 44)
        assertTrue(dnatResult)

        // Destination IP should be restored to 100.64.15.22!
        assertEquals(100.toByte(), response[16])
        assertEquals(64.toByte(), response[17])
        assertEquals(15.toByte(), response[18])
        assertEquals(22.toByte(), response[19])

        // Destination Port should be restored to 54321!
        val restoredPort = ((response[22].toInt() and 0xFF) shl 8) or (response[23].toInt() and 0xFF)
        assertEquals(54321, restoredPort)
    }

    @Test
    fun testPendingPacketQueue() {
        val arp = ArpEngine()
        val dummyPacket = byteArrayOf(0x45, 0x00, 0x00, 0x28)

        arp.queuePendingPacket("192.168.1.12", dummyPacket)
        val pending = arp.getAndClearPendingPackets("192.168.1.12")
        assertEquals(1, pending.size)
        assertEquals(dummyPacket.size, pending[0].size)

        // Queue should now be empty
        val emptyList = arp.getAndClearPendingPackets("192.168.1.12")
        assertTrue(emptyList.isEmpty())
    }
}
