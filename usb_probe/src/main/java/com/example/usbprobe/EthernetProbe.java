package com.example.usbprobe;

/**
 * Small, deliberately bounded Ethernet/IPv4 diagnostic.
 *
 * This is not a general-purpose IP stack. It only builds and validates the
 * ARP and ICMP Echo frames needed to prove that CDC-ECM bulk TX/RX carries
 * real Ethernet and IPv4 traffic to a directly attached peer.
 */
final class EthernetProbe {
    static final int ETHERTYPE_ARP = 0x0806;
    static final int ETHERTYPE_IPV4 = 0x0800;
    private static final int ETH_HLEN = 14;
    private static final int ARP_LEN = 28;
    private static final int IPV4_MIN_HLEN = 20;
    private static final int ICMP_HLEN = 8;
    private static final int ICMP_PAYLOAD_LEN = 32;

    private EthernetProbe() {}

    static byte[] parseMac(String value) {
        if (value == null) return null;
        String normalized = value.replace(":", "").replace("-", "");
        if (!normalized.matches("(?i)[0-9a-f]{12}")) return null;
        byte[] out = new byte[6];
        try {
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) Integer.parseInt(normalized.substring(i * 2, i * 2 + 2), 16);
            }
        } catch (NumberFormatException error) {
            return null;
        }
        if (!isUnicastMac(out)) return null;
        return out;
    }

    static byte[] parseIpv4(String value) {
        if (value == null) return null;
        String[] parts = value.trim().split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] out = new byte[4];
        try {
            for (int i = 0; i < 4; i++) {
                if (parts[i].isEmpty() || !parts[i].matches("[0-9]{1,3}")) return null;
                int octet = Integer.parseInt(parts[i]);
                if (octet < 0 || octet > 255) return null;
                out[i] = (byte) octet;
            }
        } catch (NumberFormatException error) {
            return null;
        }
        if (!isUsableUnicastIpv4(out)) return null;
        return out;
    }

    static String formatIpv4(byte[] ip) {
        if (ip == null || ip.length != 4) return "(invalid)";
        return (ip[0] & 255) + "." + (ip[1] & 255) + "." +
                (ip[2] & 255) + "." + (ip[3] & 255);
    }

    static byte[] buildArpRequest(byte[] localMac, byte[] localIp, byte[] targetIp) {
        requireLength(localMac, 6, "local MAC");
        requireLength(localIp, 4, "local IPv4");
        requireLength(targetIp, 4, "target IPv4");
        if (!isUnicastMac(localMac) || !isUsableUnicastIpv4(localIp) ||
                !isUsableUnicastIpv4(targetIp) || equals(localIp, targetIp)) {
            throw new IllegalArgumentException("Invalid ARP endpoint address");
        }

        // 60 bytes is the minimum Ethernet frame without FCS.
        byte[] frame = new byte[60];
        for (int i = 0; i < 6; i++) frame[i] = (byte) 0xFF;
        System.arraycopy(localMac, 0, frame, 6, 6);
        putU16(frame, 12, ETHERTYPE_ARP);

        int arp = ETH_HLEN;
        putU16(frame, arp, 1);           // Ethernet hardware type
        putU16(frame, arp + 2, ETHERTYPE_IPV4);
        frame[arp + 4] = 6;              // hardware address length
        frame[arp + 5] = 4;              // protocol address length
        putU16(frame, arp + 6, 1);        // ARP request
        System.arraycopy(localMac, 0, frame, arp + 8, 6);
        System.arraycopy(localIp, 0, frame, arp + 14, 4);
        // Target hardware address remains zero for who-has.
        System.arraycopy(targetIp, 0, frame, arp + 24, 4);
        return frame;
    }

    /**
     * Returns the peer MAC only for a valid ARP reply addressed to this probe.
     * Unrelated ARP traffic is ignored.
     */
    static byte[] parseArpReply(byte[] frame, byte[] localMac,
                                byte[] localIp, byte[] targetIp) {
        if (frame == null || frame.length < ETH_HLEN + ARP_LEN) return null;
        if (u16(frame, 12) != ETHERTYPE_ARP) return null;
        if (!equalsRange(frame, 0, localMac) || !isUnicastMacRange(frame, 6)) return null;

        int arp = ETH_HLEN;
        if (u16(frame, arp) != 1 || u16(frame, arp + 2) != ETHERTYPE_IPV4 ||
                u8(frame, arp + 4) != 6 || u8(frame, arp + 5) != 4 ||
                u16(frame, arp + 6) != 2) return null;

        byte[] senderMac = copyRange(frame, arp + 8, 6);
        byte[] senderIp = copyRange(frame, arp + 14, 4);
        byte[] targetMac = copyRange(frame, arp + 18, 6);
        byte[] receiverIp = copyRange(frame, arp + 24, 4);

        if (!equals(senderMac, copyRange(frame, 6, 6)) ||
                !isUnicastMac(senderMac) ||
                !equals(targetMac, localMac) ||
                !equals(senderIp, targetIp) ||
                !equals(receiverIp, localIp)) return null;
        return senderMac;
    }

    static byte[] buildIcmpEchoRequest(byte[] localMac, byte[] peerMac,
                                       byte[] localIp, byte[] targetIp,
                                       int identifier, int sequence) {
        requireLength(localMac, 6, "local MAC");
        requireLength(peerMac, 6, "peer MAC");
        requireLength(localIp, 4, "local IPv4");
        requireLength(targetIp, 4, "target IPv4");
        if (!isUnicastMac(localMac) || !isUnicastMac(peerMac) ||
                !isUsableUnicastIpv4(localIp) || !isUsableUnicastIpv4(targetIp)) {
            throw new IllegalArgumentException("Invalid ICMP endpoint address");
        }

        int ipLength = IPV4_MIN_HLEN + ICMP_HLEN + ICMP_PAYLOAD_LEN;
        byte[] frame = new byte[ETH_HLEN + ipLength];
        System.arraycopy(peerMac, 0, frame, 0, 6);
        System.arraycopy(localMac, 0, frame, 6, 6);
        putU16(frame, 12, ETHERTYPE_IPV4);

        int ip = ETH_HLEN;
        frame[ip] = 0x45;                // IPv4, IHL=5
        frame[ip + 1] = 0;               // DSCP/ECN
        putU16(frame, ip + 2, ipLength);
        putU16(frame, ip + 4, identifier);
        putU16(frame, ip + 6, 0x4000);   // Don't Fragment
        frame[ip + 8] = 64;              // TTL
        frame[ip + 9] = 1;               // ICMP
        System.arraycopy(localIp, 0, frame, ip + 12, 4);
        System.arraycopy(targetIp, 0, frame, ip + 16, 4);
        putU16(frame, ip + 10, checksum(frame, ip, IPV4_MIN_HLEN));

        int icmp = ip + IPV4_MIN_HLEN;
        frame[icmp] = 8;                 // Echo Request
        frame[icmp + 1] = 0;
        putU16(frame, icmp + 4, identifier);
        putU16(frame, icmp + 6, sequence);
        byte[] marker = "USB-ECM-ICMP-PROBE".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(marker, 0, frame, icmp + ICMP_HLEN, marker.length);
        for (int i = marker.length; i < ICMP_PAYLOAD_LEN; i++) {
            frame[icmp + ICMP_HLEN + i] = (byte) (0xA0 + (i & 0x1F));
        }
        putU16(frame, icmp + 2, checksum(frame, icmp, ICMP_HLEN + ICMP_PAYLOAD_LEN));
        return frame;
    }

    /** True only for the matching, checksum-valid IPv4 ICMP Echo Reply. */
    static boolean isIcmpEchoReply(byte[] frame, byte[] localMac, byte[] peerMac,
                                   byte[] localIp, byte[] targetIp,
                                   int identifier, int sequence) {
        if (frame == null || frame.length < ETH_HLEN + IPV4_MIN_HLEN + ICMP_HLEN) return false;
        if (u16(frame, 12) != ETHERTYPE_IPV4 ||
                !equalsRange(frame, 0, localMac) ||
                !equalsRange(frame, 6, peerMac)) return false;

        int ip = ETH_HLEN;
        int version = (u8(frame, ip) >>> 4) & 0x0F;
        int ihl = (u8(frame, ip) & 0x0F) * 4;
        if (version != 4 || ihl < IPV4_MIN_HLEN || frame.length < ETH_HLEN + ihl) return false;
        int totalLength = u16(frame, ip + 2);
        if (totalLength < ihl + ICMP_HLEN ||
                totalLength > frame.length - ETH_HLEN ||
                u8(frame, ip + 9) != 1) return false;
        if (!equalsRange(frame, ip + 12, targetIp) ||
                !equalsRange(frame, ip + 16, localIp)) return false;
        if (checksum(frame, ip, ihl) != 0) return false;

        int fragment = u16(frame, ip + 6);
        if ((fragment & 0x3FFF) != 0) return false; // no fragments in this one-frame probe

        int icmp = ip + ihl;
        int icmpLength = totalLength - ihl;
        if (u8(frame, icmp) != 0 || u8(frame, icmp + 1) != 0) return false;
        if (u16(frame, icmp + 4) != (identifier & 0xFFFF) ||
                u16(frame, icmp + 6) != (sequence & 0xFFFF)) return false;
        return checksum(frame, icmp, icmpLength) == 0;
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
        while ((sum >>> 16) != 0) sum = (sum & 0xFFFF) + (sum >>> 16);
        return (int) (~sum) & 0xFFFF;
    }

    private static void putU16(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 8);
        data[offset + 1] = (byte) value;
    }

    private static int u8(byte[] data, int offset) { return data[offset] & 255; }
    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 255) << 8) | (data[offset + 1] & 255);
    }

    private static byte[] copyRange(byte[] data, int offset, int length) {
        return java.util.Arrays.copyOfRange(data, offset, offset + length);
    }

    private static boolean equalsRange(byte[] data, int offset, byte[] expected) {
        if (expected == null || offset < 0 || offset + expected.length > data.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (data[offset + i] != expected[i]) return false;
        }
        return true;
    }

    private static boolean equals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        return java.util.Arrays.equals(a, b);
    }

    private static boolean isUnicastMac(byte[] mac) {
        return mac != null && mac.length == 6 && (mac[0] & 1) == 0 &&
                !(mac[0] == 0 && mac[1] == 0 && mac[2] == 0 &&
                  mac[3] == 0 && mac[4] == 0 && mac[5] == 0);
    }

    private static boolean isUnicastMacRange(byte[] data, int offset) {
        return offset >= 0 && offset + 6 <= data.length &&
                (data[offset] & 1) == 0 &&
                !(data[offset] == 0 && data[offset + 1] == 0 &&
                  data[offset + 2] == 0 && data[offset + 3] == 0 &&
                  data[offset + 4] == 0 && data[offset + 5] == 0);
    }

    private static boolean isUsableUnicastIpv4(byte[] ip) {
        if (ip == null || ip.length != 4) return false;
        int first = ip[0] & 255;
        int allZero = (ip[0] | ip[1] | ip[2] | ip[3]) & 255;
        int allOnes = (ip[0] & ip[1] & ip[2] & ip[3]) & 255;
        return allZero != 0 && allOnes != 255 &&
                first != 0 && first != 127 && first < 224;
    }

    private static void requireLength(byte[] value, int length, String label) {
        if (value == null || value.length != length) {
            throw new IllegalArgumentException(label + " must contain " + length + " bytes");
        }
    }
}
