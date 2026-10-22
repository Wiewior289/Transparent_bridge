package com.example.core.vpn

import android.content.Context
import android.util.Log
import com.example.core.model.TailscaleConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tailscale Subnet Router Controller.
 * 
 * Direct JNI interface to the Go tsnet + gVisor netstack core in /app/src/main/go/bridge.go.
 * No HTTP client emulation or random IP generation.
 */
class TailscaleSubnetManager(private val context: Context? = null) {

    companion object {
        private const val TAG = "TailscaleSubnetManager"
    }

    private val _config = MutableStateFlow(TailscaleConfig())
    val config: StateFlow<TailscaleConfig> = _config.asStateFlow()

    private val _connectionLog = MutableStateFlow<List<String>>(emptyList())
    val connectionLog: StateFlow<List<String>> = _connectionLog.asStateFlow()

    fun updateConfig(newConfig: TailscaleConfig) {
        _config.value = newConfig
    }

    fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        _connectionLog.value = (_connectionLog.value + "[$timestamp] $message").takeLast(100)
        Log.i(TAG, message)
    }

    /**
     * Starts the native Go tsnet daemon via JNI with gVisor netstack and subnet routes.
     */
    fun startSubnetRouter(authKey: String, nodeName: String, routes: String) {
        val trimmedKey = authKey.trim()
        val dataDir = context?.filesDir?.absolutePath ?: "/data/data/com.aistudio.plcbridge.vpexth/files"

        log("══════════════════════════════════════════════")
        log("Invoking native Go tsnet daemon (bridge.go) via JNI...")
        log("Node: $nodeName | Subnet: $routes")
        log("Data directory: $dataDir")

        _config.value = _config.value.copy(
            nodeName = nodeName,
            advertisedRoutes = routes,
            authKey = trimmedKey
        )

        if (trimmedKey.isEmpty()) {
            log("WARNING: Tailscale AuthKey is empty. Please provide tskey-auth-...")
            return
        }

        if (TailscaleBridgeJni.isLibraryLoaded) {
            try {
                log("Calling TailscaleBridgeJni.startTsnet() in Go runtime...")
                val res = TailscaleBridgeJni.startTsnet(trimmedKey, nodeName, routes, dataDir)
                if (res > 0) {
                    val tailnetIp = TailscaleBridgeJni.getAssignedTailnetIp()
                    _config.value = _config.value.copy(
                        isConnected = true,
                        tailnetIp = tailnetIp,
                        isSubnetRoutingActive = true
                    )
                    log("SUCCESS: tsnet.Server running in RAM via gVisor netstack!")
                    log("Assigned Tailnet IP: $tailnetIp")
                } else if (res == 0) {
                    log("tsnet.Server is already running in RAM.")
                    _config.value = _config.value.copy(isConnected = true, isSubnetRoutingActive = true)
                } else {
                    log("ERROR: tsnet.Server.Start() returned error code $res")
                }
            } catch (e: UnsatisfiedLinkError) {
                log("JNI Linkage error: ${e.message}")
            }
        } else {
            log("ERROR: tsnetbridge native library is not loaded; start aborted")
            _config.value = _config.value.copy(
                isConnected = false,
                isSubnetRoutingActive = false
            )
            return
        }

        log("══════════════════════════════════════════════")
        log("REMINDER (Tailscale Admin Console):")
        log("1. Open https://login.tailscale.com/admin/machines")
        log("2. Find machine '$nodeName' ➔ Edit route settings")
        log("3. Approve route '$routes'")
        log("══════════════════════════════════════════════")
    }

    /**
     * Injects a raw packet into the gVisor/tsnet stack via JNI.
     */
    fun injectPacketFromUsb(packet: ByteArray, length: Int): Int {
        return if (TailscaleBridgeJni.isLibraryLoaded) {
            try {
                TailscaleBridgeJni.injectPacket(packet, length)
            } catch (_: Exception) {
                -1
            }
        } else {
            -1
        }
    }

    fun stopSubnetRouter() {
        if (TailscaleBridgeJni.isLibraryLoaded) {
            try {
                TailscaleBridgeJni.stopTsnet()
            } catch (e: Exception) {
                log("Error stopping tsnet daemon: ${e.message}")
            }
        }
        log("Tailscale tsnet daemon stopped.")
        _config.value = _config.value.copy(
            isConnected = false,
            isSubnetRoutingActive = false
        )
    }

    fun getWindowsSetupCommand(): String {
        val tailIp = _config.value.tailnetIp
        return "tailscale up --accept-routes\n" +
               "# Direct ping to HMI via Subnet Router:\n" +
               "ping 192.168.1.12"
    }
}
