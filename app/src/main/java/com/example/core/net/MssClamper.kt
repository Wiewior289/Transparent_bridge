package com.example.core.net

/**
 * TCP MSS Clamping implementation for Userland (Stock ROM without root).
 * Matches iptables: `TCPMSS --clamp-mss-to-pmtu`
 * Prevents TCP segmentation fragmentation when crossing Tailscale WireGuard tunnels (MTU 1280..1420).
 */
object MssClamper {

    /**
     * Inspects and clamps MSS in an IPv4 packet buffer in-place.
     * @param buffer Raw IPv4 packet buffer
     * @param offset Offset where IPv4 header starts
     * @param length Total length of the IPv4 packet
     * @param targetMss Maximum MSS derived from the effective path MTU (e.g. 1320 for MTU 1360 IPv4/TCP)
     * @return true if MSS was found and clamped
     */
    fun clampMssInPlace(buffer: ByteArray, offset: Int, length: Int, targetMss: Int = 1360): Boolean {
        if (offset < 0 || length < 40 || offset > buffer.size - length) return false

        val versionAndIhl = buffer[offset].toInt() and 0xFF
        if ((versionAndIhl shr 4) != 4) return false // Not IPv4
        val ipHeaderLen = (versionAndIhl and 0x0F) * 4
        if (ipHeaderLen < 20 || offset + ipHeaderLen > offset + length) return false
        val ipTotalLength = ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
            (buffer[offset + 3].toInt() and 0xFF)
        if (ipTotalLength < ipHeaderLen + 20 || ipTotalLength > length) return false

        val protocol = buffer[offset + 9].toInt() and 0xFF
        if (protocol != 6) return false // Not TCP
        
        val tcpOffset = offset + ipHeaderLen
        if (tcpOffset + 20 > offset + ipTotalLength) return false
        
        val tcpFlags = buffer[tcpOffset + 13].toInt() and 0xFF
        val isSyn = (tcpFlags and 0x02) != 0
        val isRst = (tcpFlags and 0x04) != 0
        if (!isSyn || isRst) return false // Only clamp SYN packets without RST
        
        val dataOffsetBytes = ((buffer[tcpOffset + 12].toInt() and 0xF0) shr 4) * 4
        if (dataOffsetBytes <= 20) return false // No TCP options present
        
        val optionsEnd = tcpOffset + dataOffsetBytes
        if (optionsEnd > offset + ipTotalLength) return false
        
        var optIdx = tcpOffset + 20
        var modified = false
        
        while (optIdx < optionsEnd) {
            val kind = buffer[optIdx].toInt() and 0xFF
            if (kind == 0) break // EOL
            if (kind == 1) { // NOP
                optIdx++
                continue
            }
            if (optIdx + 1 >= optionsEnd) break
            val optLen = buffer[optIdx + 1].toInt() and 0xFF
            if (optLen < 2 || optIdx + optLen > optionsEnd) break
            
            if (kind == 2 && optLen == 4) { // MSS Option
                val oldMss = ((buffer[optIdx + 2].toInt() and 0xFF) shl 8) or (buffer[optIdx + 3].toInt() and 0xFF)
                if (oldMss > targetMss) {
                    val newMss = targetMss.coerceIn(1, 0xFFFF)
                    val oldWord = oldMss
                    val newWord = newMss
                    
                    // Write new MSS
                    buffer[optIdx + 2] = ((newMss shr 8) and 0xFF).toByte()
                    buffer[optIdx + 3] = (newMss and 0xFF).toByte()
                    
                    // Update TCP Checksum (located at tcpOffset + 16)
                    val oldChecksum = ((buffer[tcpOffset + 16].toInt() and 0xFF) shl 8) or (buffer[tcpOffset + 17].toInt() and 0xFF)
                    val newChecksum = ChecksumUtils.updateChecksum16(oldChecksum, oldWord, newWord)
                    
                    buffer[tcpOffset + 16] = ((newChecksum shr 8) and 0xFF).toByte()
                    buffer[tcpOffset + 17] = (newChecksum and 0xFF).toByte()
                    
                    modified = true
                }
                break
            }
            optIdx += optLen
        }
        return modified
    }
}
