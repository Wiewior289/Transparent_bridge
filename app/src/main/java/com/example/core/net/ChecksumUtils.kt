package com.example.core.net

/**
 * High-performance Internet Checksum utilities (RFC 1071 / RFC 1624).
 * Performs inline incremental checksum recalculations without memory allocation.
 */
object ChecksumUtils {

    /**
     * Incremental 16-bit 1's complement checksum update (RFC 1624):
     * ~C' = ~(~C + ~m + m')
     * where C is old checksum, m is old 16-bit word, m' is new 16-bit word.
     */
    fun updateChecksum16(oldChecksum: Int, oldWord: Int, newWord: Int): Int {
        val notC = (oldChecksum.inv()) and 0xFFFF
        val notM = (oldWord.inv()) and 0xFFFF
        val mPrime = newWord and 0xFFFF
        
        var sum = notC + notM + mPrime
        while ((sum shr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv()) and 0xFFFF
    }

    /**
     * Computes IPv4 header checksum over given byte range.
     */
    fun computeIpChecksum(packet: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        
        while (i < end - 1) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += ((packet[i].toInt() and 0xFF) shl 8)
        }
        while ((sum shr 16) > 0L) {
            sum = (sum and 0xFFFFL) + (sum shr 16)
        }
        return ((sum.inv()) and 0xFFFFL).toInt()
    }
}
