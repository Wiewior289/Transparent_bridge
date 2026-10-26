package com.example.core.vpn

import android.content.Context
import android.util.Log
import com.example.core.model.TailscaleConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var stateMonitorJob: Job? = null

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
        log("Tailscale runtime request: node=$nodeName routes=$routes")
        log("AuthKey diagnostics: present=${trimmedKey.isNotEmpty()}, length=${trimmedKey.length}, tailscalePrefix=${trimmedKey.startsWith("tskey-")}")

        if (trimmedKey.isEmpty()) {
            log("ERROR: AuthKey is empty; native start was not called.")
            _config.value = _config.value.copy(
                nodeName = nodeName,
                advertisedRoutes = routes,
                authKey = "",
                isConnected = false,
                isSubnetRoutingActive = false,
                tailnetIp = "0.0.0.0",
                backendState = if (_config.value.isRuntimeStarted) _config.value.backendState else "STOPPED"
            )
            return
        }

        if (!TailscaleBridgeJni.isLibraryLoaded) {
            log("ERROR: tsnetbridge native library is not loaded; start aborted.")
            return
        }

        try {
            val previousState = TailscaleBridgeJni.getBackendState()
            val wasRunning = previousState != "STOPPED"
            _config.value = _config.value.copy(
                nodeName = nodeName,
                advertisedRoutes = routes,
                authKey = trimmedKey,
                backendState = previousState,
                isRuntimeStarted = wasRunning,
                isConnected = if (wasRunning) _config.value.isConnected else false,
                isSubnetRoutingActive = if (wasRunning) _config.value.isSubnetRoutingActive else false,
                tailnetIp = if (wasRunning) _config.value.tailnetIp else "0.0.0.0"
            )

            val logStateBaseDir = context?.filesDir?.absolutePath
                ?: throw IllegalStateException("Android app files directory is unavailable for Tailscale state")
            log("Starting or reusing the single native runtime; app-private state directory is available.")
            val result = TailscaleBridgeJni.startTsnet(trimmedKey, nodeName, routes, logStateBaseDir)
            if (result < 0) {
                log("ERROR: native Tailscale startup failed, code=$result")
                val stateAfterFailure = TailscaleBridgeJni.getBackendState()
                _config.value = _config.value.copy(
                    backendState = stateAfterFailure,
                    isRuntimeStarted = stateAfterFailure != "STOPPED",
                    isConnected = false,
                    isSubnetRoutingActive = false,
                    tailnetIp = "0.0.0.0"
                )
                return
            }

            val backendState = TailscaleBridgeJni.getBackendState()
            val tailnetIp = TailscaleBridgeJni.getAssignedTailnetIp()
            val hasTailnetIp = tailnetIp != "0.0.0.0" && tailnetIp.isNotBlank()
            val connected = backendState == "Running" && hasTailnetIp
            _config.value = _config.value.copy(
                backendState = backendState,
                isRuntimeStarted = true,
                isConnected = connected,
                tailnetIp = tailnetIp,
                isSubnetRoutingActive = connected
            )

            log("Native start result=$result; LocalBackend state=$backendState; Tailnet IP assigned=$hasTailnetIp.")
            if (!connected) {
                log("Tailscale runtime exists but is not connected yet. USB/bridge state remains independent.")
            } else {
                log("Tailscale connected with assigned Tailnet IP: $tailnetIp")
            }
            startStateMonitor()
        } catch (e: UnsatisfiedLinkError) {
            log("JNI linkage error: ${e.message}")
        } catch (e: Exception) {
            log("Tailscale startup error: ${e.message}")
        }
    }

    private fun startStateMonitor() {
        stateMonitorJob?.cancel()
        stateMonitorJob = monitorScope.launch {
            var lastState = ""
            while (isActive && TailscaleBridgeJni.isLibraryLoaded) {
                val state = try {
                    TailscaleBridgeJni.getBackendState()
                } catch (e: Exception) {
                    log("State monitor error: ${e.message}")
                    break
                }
                if (state == "STOPPED") {
                    _config.value = _config.value.copy(
                        backendState = "STOPPED",
                        isRuntimeStarted = false,
                        isConnected = false,
                        isSubnetRoutingActive = false,
                        tailnetIp = "0.0.0.0"
                    )
                    log("Native Tailscale runtime stopped.")
                    break
                }

                val ip = try {
                    TailscaleBridgeJni.getAssignedTailnetIp()
                } catch (_: Exception) {
                    "0.0.0.0"
                }
                val hasIp = ip != "0.0.0.0" && ip.isNotBlank()
                val connected = state == "Running" && hasIp
                val old = _config.value
                _config.value = old.copy(
                    backendState = state,
                    isRuntimeStarted = true,
                    isConnected = connected,
                    isSubnetRoutingActive = connected,
                    tailnetIp = ip
                )
                if (state != lastState) {
                    log("LocalBackend state: $state")
                    lastState = state
                }
                if (connected && !old.isConnected) {
                    log("Tailnet connection established; IP=$ip")
                } else if (!connected && old.isConnected) {
                    log("Tailnet connection is no longer active; backend state=$state")
                }
                delay(1000)
            }
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
        stateMonitorJob?.cancel()
        stateMonitorJob = null
        if (TailscaleBridgeJni.isLibraryLoaded) {
            try {
                val result = TailscaleBridgeJni.stopTsnet()
                log("Explicit Tailscale runtime stop requested; native result=$result")
            } catch (e: Exception) {
                log("Error stopping tsnet daemon: ${e.message}")
            }
        }
        _config.value = _config.value.copy(
            isConnected = false,
            isSubnetRoutingActive = false,
            isRuntimeStarted = false,
            backendState = "STOPPED",
            tailnetIp = "0.0.0.0"
        )
    }

    fun getWindowsSetupCommand(): String {
        val tailIp = _config.value.tailnetIp
        return "tailscale up --accept-routes\n" +
               "# Direct ping to HMI via Subnet Router:\n" +
               "ping 192.168.1.12"
    }
}
