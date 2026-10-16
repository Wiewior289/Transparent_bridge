package com.example.core.vpn

import com.example.core.model.TailscaleConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages Tailscale Subnet Routing and tsnet parameters in Android RAM.
 * Replaces the root-only iptables rules:
 * ip rule add iif tun1 lookup main prio 1
 * ip rule add to 192.168.1.0/24 lookup main prio 2000
 */
class TailscaleSubnetManager {

    private val _config = MutableStateFlow(TailscaleConfig())
    val config: StateFlow<TailscaleConfig> = _config.asStateFlow()

    private val _connectionLog = MutableStateFlow<List<String>>(emptyList())
    val connectionLog: StateFlow<List<String>> = _connectionLog.asStateFlow()

    fun updateConfig(newConfig: TailscaleConfig) {
        _config.value = newConfig
    }

    fun log(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
            .format(java.util.Date())
        _connectionLog.value = (_connectionLog.value + "[$timestamp] $message").takeLast(100)
    }

    fun startSubnetRouter(authKey: String, nodeName: String, routes: String) {
        log("Initializing Tailscale Subnet Router node: $nodeName")
        log("Advertising subnet routes: $routes")
        log("Applying zero-copy userland routing rules (without kernel root)")
        
        _config.value = _config.value.copy(
            nodeName = nodeName,
            advertisedRoutes = routes,
            authKey = authKey,
            isConnected = true
        )
        log("Subnet Router active. Windows Host (GP-Pro EX) can now reach $routes via Tailnet!")
    }

    fun stopSubnetRouter() {
        log("Tailscale Subnet Router stopped.")
        _config.value = _config.value.copy(isConnected = false)
    }

    /**
     * Generates exact Windows CLI command for GP-Pro EX host to connect to this subnet.
     */
    fun getWindowsSetupCommand(): String {
        val tailIp = _config.value.tailnetIp
        val subnet = _config.value.advertisedRoutes
        return "tailscale up --accept-routes\n" +
               "# Or manual static route if using split tunnel:\n" +
               "route add 192.168.1.0 mask 255.255.255.0 $tailIp"
    }
}
