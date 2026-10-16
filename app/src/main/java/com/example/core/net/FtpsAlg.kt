package com.example.core.net

import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * FTPS/FTP Application Layer Gateway (ALG) for GP-Pro EX and industrial HMI/PLC transfers.
 * Detects dynamic passive ports in FTP control channels (PASV / EPSV)
 * so GP-Pro EX transfers succeed seamlessly through the transparent userland tunnel.
 */
class FtpsAlg {

    // Active dynamically opened passive ports: Port -> Registered Timestamp
    private val dynamicPassivePorts = ConcurrentHashMap<Int, Long>()

    // Regex for PASV: 227 Entering Passive Mode (h1,h2,h3,h4,p1,p2)
    private val pasvPattern = Pattern.compile("227.*\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)")
    
    // Regex for EPSV: 229 Entering Extended Passive Mode (|||port|)
    private val epsvPattern = Pattern.compile("229.*\\(\\|\\|\\|(\\d+)\\|\\)")

    fun getActivePorts(): List<Int> {
        val now = System.currentTimeMillis()
        // Cleanup ports older than 5 minutes
        dynamicPassivePorts.entries.removeIf { now - it.value > 300_000 }
        return dynamicPassivePorts.keys.toList()
    }

    /**
     * Inspects a TCP payload stream (typically port 21 / 8000) for FTP passive responses.
     * Returns newly detected port, or null.
     */
    fun inspectPayload(payload: ByteArray, offset: Int, length: Int): Int? {
        if (length < 10) return null
        
        // Quick check: standard FTP response codes start with numbers (e.g. 227 or 229)
        val text = try {
            String(payload, offset, minOf(length, 256), Charsets.US_ASCII)
        } catch (_: Exception) {
            return null
        }

        if (text.startsWith("227")) {
            val matcher = pasvPattern.matcher(text)
            if (matcher.find()) {
                val p1 = matcher.group(5)?.toIntOrNull() ?: 0
                val p2 = matcher.group(6)?.toIntOrNull() ?: 0
                val port = (p1 shl 8) or p2
                if (port in 1024..65535) {
                    dynamicPassivePorts[port] = System.currentTimeMillis()
                    return port
                }
            }
        } else if (text.startsWith("229")) {
            val matcher = epsvPattern.matcher(text)
            if (matcher.find()) {
                val port = matcher.group(1)?.toIntOrNull()
                if (port != null && port in 1024..65535) {
                    dynamicPassivePorts[port] = System.currentTimeMillis()
                    return port
                }
            }
        }
        return null
    }

    fun isDynamicPortActive(port: Int): Boolean {
        val ts = dynamicPassivePorts[port] ?: return false
        if (System.currentTimeMillis() - ts > 300_000) {
            dynamicPassivePorts.remove(port)
            return false
        }
        return true
    }

    fun registerPortManually(port: Int) {
        dynamicPassivePorts[port] = System.currentTimeMillis()
    }
}
