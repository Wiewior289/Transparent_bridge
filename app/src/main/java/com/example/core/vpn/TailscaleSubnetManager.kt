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
        private const val PREFS_NAME = "tailscale_prefs"
    }

    private val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(
        TailscaleConfig(
            nodeName = prefs?.getString("node_name", "galaxy-s9-plc-bridge") ?: "galaxy-s9-plc-bridge",
            advertisedRoutes = prefs?.getString("advertised_routes", "192.168.1.0/24") ?: "192.168.1.0/24",
            authKey = prefs?.getString("auth_key", "") ?: "",
            isConnected = false,
            tailnetIp = "0.0.0.0",
            isSubnetRoutingActive = false
        )
    )
    val config: StateFlow<TailscaleConfig> = _config.asStateFlow()

    private val _connectionLog = MutableStateFlow<List<String>>(emptyList())
    val connectionLog: StateFlow<List<String>> = _connectionLog.asStateFlow()

    fun updateConfig(newConfig: TailscaleConfig) {
        _config.value = newConfig
        prefs?.edit()
            ?.putString("node_name", newConfig.nodeName)
            ?.putString("advertised_routes", newConfig.advertisedRoutes)
            ?.putString("auth_key", newConfig.authKey)
            ?.apply()
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
        val trimmedKey = authKey.trim().ifEmpty { prefs?.getString("auth_key", "") ?: "" }

        prefs?.edit()
            ?.putString("node_name", nodeName)
            ?.putString("advertised_routes", routes)
            ?.putString("auth_key", trimmedKey)
            ?.apply()

        log("══════════════════════════════════════════════")
        log("Invoking native Go tsnet daemon (bridge.go) via JNI...")
        log("Node: $nodeName | Subnet: $routes")

        _config.value = _config.value.copy(
            nodeName = nodeName,
            advertisedRoutes = routes,
            authKey = trimmedKey,
            isConnected = false,
            isSubnetRoutingActive = false,
            tailnetIp = "0.0.0.0"
        )

        if (trimmedKey.isEmpty()) {
            log("ERROR: Tailscale AuthKey is empty. Please enter your tskey-auth-... in Tailscale settings.")
            return
        }

        if (TailscaleBridgeJni.isLibraryLoaded) {
            try {
                log("Calling TailscaleBridgeJni.startTsnet() in Go runtime...")
                val res = TailscaleBridgeJni.startTsnet(trimmedKey, nodeName, routes)
                if (res > 0) {
                    val tailnetIp = TailscaleBridgeJni.getAssignedTailnetIp()
                    val hasTailnetIp = tailnetIp != "0.0.0.0" && tailnetIp.isNotBlank()
                    _config.value = _config.value.copy(
                        isConnected = hasTailnetIp,
                        tailnetIp = tailnetIp,
                        isSubnetRoutingActive = hasTailnetIp
                    )
                    if (hasTailnetIp) {
                        log("Backend started; assigned Tailnet IP: $tailnetIp")
                    } else {
                        log("Backend start returned, but Tailnet IP is not available yet.")
                    }
                } else if (res == 0) {
                    log("Native Tailscale backend reports it is already running.")
                    val tailnetIp = TailscaleBridgeJni.getAssignedTailnetIp()
                    _config.value = _config.value.copy(
                        isConnected = tailnetIp != "0.0.0.0",
                        tailnetIp = tailnetIp,
                        isSubnetRoutingActive = tailnetIp != "0.0.0.0"
                    )
                } else {
                    log("ERROR: native Tailscale backend startup failed, code=$res")
                    _config.value = _config.value.copy(
                        isConnected = false,
                        isSubnetRoutingActive = false,
                        tailnetIp = "0.0.0.0"
                    )
                    return
                }
            } catch (e: UnsatisfiedLinkError) {
                log("JNI Linkage error: ${e.message}")
            _config.value = _config.value.copy(
                isConnected = false,
                isSubnetRoutingActive = false,
                tailnetIp = "0.0.0.0"
            )
            }
        } else {
            log("ERROR: tsnetbridge native library is not loaded; start aborted")
            _config.value = _config.value.copy(
                isConnected = false,
                isSubnetRoutingActive = false
            )
            return
        }

        if (_config.value.isSubnetRoutingActive) {
            log("Backend initialization returned successfully. Check the admin console for device registration and approve the advertised route if it appears.")
            log("Admin console: https://login.tailscale.com/admin/machines")
        }
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
