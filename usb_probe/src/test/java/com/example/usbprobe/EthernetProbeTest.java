package com.example.usbprobe;

import static org.junit.Assert.*;

import org.junit.Test;
import java.util.Arrays;

public class EthernetProbeTest {
    private static final byte[] LOCAL_MAC = hex("02:11:22:33:44:55");
    private static final byte[] PEER_MAC = hex("00:01:23:26:80:0C");
    private static final byte[] LOCAL_IP = ip("192.168.1.2");
    private static final byte[] PEER_IP = ip("192.168.1.12");

    @Test public void parsesOnlyUsableIpv4AndMacAddresses() {
        assertArrayEquals(LOCAL_IP, EthernetProbe.parseIpv4("192.168.1.2"));
        assertNull(EthernetProbe.parseIpv4("192.168.1.256"));
        assertNull(EthernetProbe.parseIpv4("192.168.1"));
        assertNull(EthernetProbe.parseIpv4("0.0.0.0"));
        assertNull(EthernetProbe.parseIpv4("255.255.255.255"));
        assertNull(EthernetProbe.parseIpv4("127.0.0.1"));
        assertNull(EthernetProbe.parseIpv4("224.1.2.3"));
        assertNull(EthernetProbe.parseIpv4("240.0.0.1"));
        assertArrayEquals(LOCAL_MAC, EthernetProbe.parseMac("021122334455"));
        assertNull(EthernetProbe.parseMac("FF:FF:FF:FF:FF:FF"));
        assertNull(EthernetProbe.parseMac("00:00:00:00:00:00"));
    }

    @Test public void buildsArpWhoHasAndAcceptsOnlyMatchingReply() {
        byte[] request = EthernetProbe.buildArpRequest(LOCAL_MAC, LOCAL_IP, PEER_IP);
        assertEquals(60, request.length);
        assertEquals(0x0806, u16(request, 12));
        assertEquals(1, u16(request, 20));
        assertArrayEquals(LOCAL_IP, Arrays.copyOfRange(request, 28, 32));
        assertArrayEquals(PEER_IP, Arrays.copyOfRange(request, 38, 42));

        byte[] reply = arpReply();
        assertArrayEquals(PEER_MAC,
                EthernetProbe.parseArpReply(reply, LOCAL_MAC, LOCAL_IP, PEER_IP));

        byte[] wrongTarget = reply.clone();
        wrongTarget[41] = 3; // ARP target protocol address no longer equals local IP.
        assertNull(EthernetProbe.parseArpReply(wrongTarget, LOCAL_MAC, LOCAL_IP, PEER_IP));
    }

    @Test public void buildsValidIpv4IcmpEchoAndRecognizesMatchingReply() {
        int identifier = 0x1234;
        int sequence = 7;
        byte[] request = EthernetProbe.buildIcmpEchoRequest(
                LOCAL_MAC, PEER_MAC, LOCAL_IP, PEER_IP, identifier, sequence);
        assertEquals(74, request.length);
        assertEquals(0x0800, u16(request, 12));
        assertEquals(0, checksum(request, 14, 20));
        assertEquals(0, checksum(request, 34, request.length - 34));
        assertEquals(8, request[34] & 255);

        byte[] reply = icmpReplyFrom(request);
        assertTrue(EthernetProbe.isIcmpEchoReply(
                reply, LOCAL_MAC, PEER_MAC, LOCAL_IP, PEER_IP, identifier, sequence));
        assertFalse(EthernetProbe.isIcmpEchoReply(
                reply, LOCAL_MAC, PEER_MAC, LOCAL_IP, PEER_IP, identifier, sequence + 1));

        reply[reply.length - 1] ^= 0x01;
        assertFalse(EthernetProbe.isIcmpEchoReply(
                reply, LOCAL_MAC, PEER_MAC, LOCAL_IP, PEER_IP, identifier, sequence));
    }

    private static byte[] arpReply() {
        byte[] f = new byte[60];
        System.arraycopy(LOCAL_MAC, 0, f, 0, 6);
        System.arraycopy(PEER_MAC, 0, f, 6, 6);
        put16(f, 12, 0x0806);
        put16(f, 14, 1);
        put16(f, 16, 0x0800);
        f[18] = 6; f[19] = 4;
        put16(f, 20, 2);
        System.arraycopy(PEER_MAC, 0, f, 22, 6);
        System.arraycopy(PEER_IP, 0, f, 28, 4);
        System.arraycopy(LOCAL_MAC, 0, f, 32, 6);
        System.arraycopy(LOCAL_IP, 0, f, 38, 4);
        return f;
    }

    private static byte[] icmpReplyFrom(byte[] request) {
        byte[] reply = request.clone();
        System.arraycopy(request, 6, reply, 0, 6);
        System.arraycopy(request, 0, reply, 6, 6);
        System.arraycopy(PEER_IP, 0, reply, 26, 4);
        System.arraycopy(LOCAL_IP, 0, reply, 30, 4);
        reply[24] = 0; reply[25] = 0;
        put16(reply, 24, checksum(reply, 14, 20));
        reply[34] = 0;
        reply[36] = 0; reply[37] = 0;
        put16(reply, 36, checksum(reply, 34, reply.length - 34));
        return reply;
    }

    private static int checksum(byte[] data, int offset, int length) {
        long sum = 0;
        int end = offset + length;
        int p = offset;
        while (p + 1 < end) {
            sum += ((data[p] & 255) << 8) | (data[p + 1] & 255);
            p += 2;
        }
        if (p < end) sum += (data[p] & 255) << 8;
        while ((sum >>> 16) != 0) sum = (sum & 0xffff) + (sum >>> 16);
        return (int) (~sum) & 0xffff;
    }

    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 255) << 8) | (data[offset + 1] & 255);
    }

    private static void put16(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 8);
        data[offset + 1] = (byte) value;
    }

    private static byte[] hex(String value) {
        String normalized = value.replace(":", "");
        byte[] out = new byte[normalized.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(normalized.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static byte[] ip(String value) {
        return EthernetProbe.parseIpv4(value);
    }
}
