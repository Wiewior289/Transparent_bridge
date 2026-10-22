package com.example.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.model.ArpEntry
import com.example.core.model.BridgeState
import com.example.core.model.BridgeStats
import com.example.core.model.EthernetConfig
import com.example.core.model.PacketLog
import com.example.core.model.PlcProfile
import com.example.core.model.TailscaleConfig
import com.example.core.model.UsbDeviceInfo
import com.example.service.PlcBridgeService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlcBridgeViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext

    private var bridgeService: PlcBridgeService? = null
    private var isBound = false

    private val _bridgeState = MutableStateFlow(BridgeState.STOPPED)
    val bridgeState: StateFlow<BridgeState> = _bridgeState.asStateFlow()

    private val _stats = MutableStateFlow(BridgeStats())
    val stats: StateFlow<BridgeStats> = _stats.asStateFlow()

    private val _ethernetConfig = MutableStateFlow(EthernetConfig())
    val ethernetConfig: StateFlow<EthernetConfig> = _ethernetConfig.asStateFlow()

    private val _tailscaleConfig = MutableStateFlow(TailscaleConfig())
    val tailscaleConfig: StateFlow<TailscaleConfig> = _tailscaleConfig.asStateFlow()

    private val _usbDevices = MutableStateFlow<List<UsbDeviceInfo>>(emptyList())
    val usbDevices: StateFlow<List<UsbDeviceInfo>> = _usbDevices.asStateFlow()

    private val _selectedDevice = MutableStateFlow<UsbDeviceInfo?>(null)
    val selectedDevice: StateFlow<UsbDeviceInfo?> = _selectedDevice.asStateFlow()

    private val _arpTable = MutableStateFlow<List<ArpEntry>>(emptyList())
    val arpTable: StateFlow<List<ArpEntry>> = _arpTable.asStateFlow()

    private val _packetLogs = MutableStateFlow<List<PacketLog>>(emptyList())
    val packetLogs: StateFlow<List<PacketLog>> = _packetLogs.asStateFlow()

    private val _selectedFilter = MutableStateFlow("ALL")
    val selectedFilter: StateFlow<String> = _selectedFilter.asStateFlow()

    private val _activeFtpsPorts = MutableStateFlow<List<Int>>(emptyList())
    val activeFtpsPorts: StateFlow<List<Int>> = _activeFtpsPorts.asStateFlow()

    private val _activeProfile = MutableStateFlow(DEFAULT_PROFILES[0])
    val activeProfile: StateFlow<PlcProfile> = _activeProfile.asStateFlow()

    private val _tailscaleLogs = MutableStateFlow<List<String>>(emptyList())
    val tailscaleLogs: StateFlow<List<String>> = _tailscaleLogs.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as PlcBridgeService.LocalBinder
            bridgeService = binder.service
            isBound = true

            // Sync configurations
            binder.service.ethernetConfig = _ethernetConfig.value

            viewModelScope.launch {
                binder.service.bridgeState.collect { _bridgeState.value = it }
            }
            viewModelScope.launch {
                binder.service.stats.collect { _stats.value = it }
            }
            viewModelScope.launch {
                binder.service.packetLogs.collect { _packetLogs.value = it }
            }
            viewModelScope.launch {
                binder.service.tailscaleManager.connectionLog.collect { _tailscaleLogs.value = it }
            }
            viewModelScope.launch {
                binder.service.tailscaleManager.config.collect { _tailscaleConfig.value = it }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bridgeService = null
            isBound = false
            _bridgeState.value = BridgeState.STOPPED
        }
    }

    init {
        bindService()
        refreshUsbDevices()
        startPeriodicSync()
    }

    private fun bindService() {
        val intent = Intent(context, PlcBridgeService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun startPeriodicSync() {
        viewModelScope.launch {
            while (isActive) {
                delay(1500)
                refreshUsbDevices()
                bridgeService?.let { s ->
                    _arpTable.value = s.arpEngine.getArpTable()
                    _activeFtpsPorts.value = s.ftpsAlg.getActivePorts()
                }
            }
        }
    }

    fun refreshUsbDevices() {
        bridgeService?.let { s ->
            val list = s.usbManager.scanDevices()
            _usbDevices.value = list
            if (_selectedDevice.value == null || !list.any { it.deviceName == _selectedDevice.value?.deviceName }) {
                _selectedDevice.value = list.firstOrNull()
            }
        }
    }

    fun selectUsbDevice(deviceInfo: UsbDeviceInfo) {
        _selectedDevice.value = deviceInfo
    }

    fun startBridge() {
        val intent = Intent(context, PlcBridgeService::class.java).apply {
            action = PlcBridgeService.ACTION_START
        }
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }

    fun stopBridge() {
        val intent = Intent(context, PlcBridgeService::class.java).apply {
            action = PlcBridgeService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun toggleBridge() {
        if (_bridgeState.value == BridgeState.RUNNING) {
            stopBridge()
        } else {
            startBridge()
        }
    }

    fun probePlc() {
        bridgeService?.probePlc()
    }

    fun sendGratuitousArp() {
        bridgeService?.sendGratuitousArp()
    }

    fun resetStats() {
        bridgeService?.resetStats()
    }

    fun clearPacketLogs() {
        bridgeService?.clearLogs()
    }

    fun setFilter(filter: String) {
        _selectedFilter.value = filter
    }

    fun updateEthernetConfig(config: EthernetConfig) {
        _ethernetConfig.value = config
        bridgeService?.ethernetConfig = config
    }

    fun updateTailscaleConfig(config: TailscaleConfig) {
        _tailscaleConfig.value = config
        bridgeService?.tailscaleManager?.updateConfig(config)
    }

    fun connectTailscale(authKey: String, nodeName: String, routes: String) {
        _tailscaleConfig.value = _tailscaleConfig.value.copy(
            authKey = authKey,
            nodeName = nodeName,
            advertisedRoutes = routes
        )
        bridgeService?.tailscaleManager?.startSubnetRouter(authKey, nodeName, routes)
    }

    fun applyProfile(profile: PlcProfile) {
        _activeProfile.value = profile
        val updated = _ethernetConfig.value.copy(
            targetPlcIp = profile.targetIp,
            isFtpsAlgEnabled = profile.isFtpsPasvNeeded
        )
        updateEthernetConfig(updated)
    }

    fun setStaticArp(ip: String, mac: String) {
        bridgeService?.arpEngine?.setStaticArp(ip, mac)
        bridgeService?.let { _arpTable.value = it.arpEngine.getArpTable() }
    }

    override fun onCleared() {
        super.onCleared()
        if (isBound) {
            context.unbindService(serviceConnection)
            isBound = false
        }
    }

    companion object {
        val DEFAULT_PROFILES = listOf(
            PlcProfile(
                id = "proface_gp4000",
                name = "Pro-face GP4000 (GP-Pro EX)",
                targetIp = "192.168.1.12",
                vendor = "Schneider / Pro-face",
                defaultPorts = listOf(21, 8000),
                description = "FTPS / FTP dynamic PASV data ports & GP-Pro EX Project Download/Receive/Monitor",
                isFtpsPasvNeeded = true
            ),
            PlcProfile(
                id = "siemens_s7",
                name = "Siemens S7-1200 / S7-1500 (TIA Portal)",
                targetIp = "192.168.1.200",
                vendor = "Siemens",
                defaultPorts = listOf(102),
                description = "ISO-on-TCP RFC1006 communication for TIA Portal PG/PC diagnostics & program transfer",
                isFtpsPasvNeeded = false
            ),
            PlcProfile(
                id = "modbus_tcp",
                name = "Modbus TCP Universal Gateway",
                targetIp = "192.168.1.50",
                vendor = "Universal",
                defaultPorts = listOf(502),
                description = "Standard Modbus TCP master/slave communication port 502 with low latency framing",
                isFtpsPasvNeeded = false
            ),
            PlcProfile(
                id = "omron_sysmac",
                name = "Omron Sysmac NJ/NX / CJ2",
                targetIp = "192.168.1.10",
                vendor = "Omron",
                defaultPorts = listOf(9600, 44818),
                description = "FINS Protocol UDP/TCP 9600 and EtherNet/IP CIP 44818 for Sysmac Studio / CX-Programmer",
                isFtpsPasvNeeded = false
            ),
            PlcProfile(
                id = "mitsubishi_melsec",
                name = "Mitsubishi MELSEC iQ-R/Q/FX",
                targetIp = "192.168.1.30",
                vendor = "Mitsubishi Electric",
                defaultPorts = listOf(5007, 1025),
                description = "SLMP / MC Protocol 3E/4E binary frame communications for GX Works3",
                isFtpsPasvNeeded = false
            )
        )
    }
}
