package com.example.core.vpn

import android.util.Log
import com.example.core.model.EthernetConfig
import com.example.core.net.FtpsAlg
import com.example.core.net.MssClamper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class UserlandIndustrialProxy(
    private val ftpsAlg: FtpsAlg,
    private val onPacketLogged: (protocol: String, src: String, dst: String, len: Int, info: String) -> Unit
) {
    companion object {
        private const val TAG = "IndustrialProxy"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val serverSockets = ConcurrentHashMap<Int, ServerSocket>()
    private val activeConnections = AtomicInteger(0)
    
    val totalRxBytes = AtomicLong(0L)
    val totalTxBytes = AtomicLong(0L)
    val totalPackets = AtomicLong(0L)

    var config: EthernetConfig = EthernetConfig()

    fun start(targetConfig: EthernetConfig, ports: List<Int> = listOf(21, 8000, 502, 102, 44818)) {
        this.config = targetConfig
        stop()

        for (port in ports) {
            startListener(port)
        }
    }

    fun startListener(port: Int) {
        if (serverSockets.containsKey(port)) return

        scope.launch {
            try {
                val server = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))
                serverSockets[port] = server
                Log.i(TAG, "Proxy listening on port $port -> forwarding to ${config.targetPlcIp}:$port")

                while (isActive && !server.isClosed) {
                    val clientSocket = server.accept()
                    handleClient(clientSocket, port)
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.w(TAG, "Listener on port $port encountered error: ${e.message}")
                }
            }
        }
    }

    private fun handleClient(clientSocket: Socket, port: Int) {
        scope.launch {
            activeConnections.incrementAndGet()
            var targetSocket: Socket? = null
            try {
                val clientIp = clientSocket.inetAddress.hostAddress ?: "unknown"
                val clientPort = clientSocket.port
                Log.i(TAG, "New connection on port $port from $clientIp:$clientPort")
                
                onPacketLogged(
                    "TCP-SYN",
                    "$clientIp:$clientPort",
                    "${config.targetPlcIp}:$port",
                    0,
                    "Proxy Connect (GP-Pro EX / Host)"
                )

                targetSocket = Socket(config.targetPlcIp, port)
                targetSocket.tcpNoDelay = true
                clientSocket.tcpNoDelay = true

                // Bidirectional pipe
                val jobA = launch { pipe(clientSocket.getInputStream(), targetSocket.getOutputStream(), isClientToServer = true, port) }
                val jobB = launch { pipe(targetSocket.getInputStream(), clientSocket.getOutputStream(), isClientToServer = false, port) }

                jobA.join()
                jobB.join()
            } catch (e: Exception) {
                Log.w(TAG, "Proxy pipe error on port $port: ${e.message}")
            } finally {
                activeConnections.decrementAndGet()
                try { clientSocket.close() } catch (_: Exception) {}
                try { targetSocket?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun pipe(input: InputStream, output: OutputStream, isClientToServer: Boolean, port: Int) {
        val buffer = ByteArray(1600)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break

                if (isClientToServer) {
                    totalRxBytes.addAndGet(read.toLong())
                } else {
                    totalTxBytes.addAndGet(read.toLong())
                }
                totalPackets.incrementAndGet()

                // Check FTPS ALG for passive mode response
                if (!isClientToServer && (port == 21 || port == 8000)) {
                    val detectedPasvPort = ftpsAlg.inspectPayload(buffer, 0, read)
                    if (detectedPasvPort != null) {
                        Log.i(TAG, "FTPS ALG: Detected dynamic passive port $detectedPasvPort! Auto-spawning listener.")
                        onPacketLogged(
                            "FTPS-PASS",
                            "${config.targetPlcIp}:$port",
                            "HOST",
                            read,
                            "Dynamic Passive Port $detectedPasvPort Opened"
                        )
                        startListener(detectedPasvPort)
                    }
                }

                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (_: Exception) {
            // Connection closed normally
        }
    }

    fun getActiveConnectionCount(): Int = activeConnections.get()

    fun stop() {
        for ((port, server) in serverSockets) {
            try {
                server.close()
            } catch (_: Exception) {}
        }
        serverSockets.clear()
    }
}
