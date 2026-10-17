package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.model.BridgeState
import com.example.ui.PlcBridgeViewModel
import com.example.ui.theme.IndustrialAmber
import com.example.ui.theme.IndustrialCoral
import com.example.ui.theme.IndustrialCyan
import com.example.ui.theme.IndustrialEmerald
import com.example.ui.theme.OutlineDark
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.SurfaceVariantDark
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun DashboardScreen(
    viewModel: PlcBridgeViewModel,
    onToggleBridge: () -> Unit = { viewModel.toggleBridge() }
) {
    val state by viewModel.bridgeState.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val ethConfig by viewModel.ethernetConfig.collectAsState()
    val activeProfile by viewModel.activeProfile.collectAsState()
    val ftpsPorts by viewModel.activeFtpsPorts.collectAsState()
    val usbDevices by viewModel.usbDevices.collectAsState()

    val isRunning = state == BridgeState.RUNNING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Industrial Banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            border = androidx.compose.foundation.BorderStroke(1.dp, OutlineDark),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "INDUSTRIAL VPN BRIDGE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = IndustrialCyan,
                            letterSpacing = 1.2.sp
                        )
                        Text(
                            text = "Transparent L2/L3 Gateway",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = TextPrimary
                        )
                    }

                    // Status Pill
                    val statusColor by animateColorAsState(
                        targetValue = when (state) {
                            BridgeState.RUNNING -> IndustrialEmerald
                            BridgeState.STARTING -> IndustrialAmber
                            BridgeState.STOPPED -> TextMuted
                            BridgeState.ERROR -> IndustrialCoral
                            BridgeState.PAUSED -> IndustrialAmber
                        }, label = "statusColor"
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(SurfaceVariantDark)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = state.name,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = statusColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Network Topology Flow
                NetworkTopologyCard(
                    isRunning = isRunning,
                    adapterIp = ethConfig.adapterIp,
                    targetPlcIp = ethConfig.targetPlcIp
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Primary Start/Stop Toggle
                Button(
                    onClick = { onToggleBridge() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("bridge_toggle_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) IndustrialCoral else IndustrialCyan,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isRunning) "STOP TRANSPARENT BRIDGE" else "START TRANSPARENT BRIDGE",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }

        // Live Telemetry Speedometers & Counters
        Text(
            text = "LIVE TELEMETRY (RING BUFFER ZERO-COPY)",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = TextSecondary,
            letterSpacing = 1.sp
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TelemetryTile(
                modifier = Modifier.weight(1f),
                title = "TRAFFIC RX",
                value = "%.1f".format(stats.currentRxKBs),
                unit = "KB/s",
                icon = Icons.Default.Speed,
                accentColor = IndustrialCyan
            )
            TelemetryTile(
                modifier = Modifier.weight(1f),
                title = "TRAFFIC TX",
                value = "%.1f".format(stats.currentTxKBs),
                unit = "KB/s",
                icon = Icons.Default.Speed,
                accentColor = IndustrialEmerald
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TelemetryTile(
                modifier = Modifier.weight(1f),
                title = "PACKETS / SEC",
                value = stats.currentPps.toString(),
                unit = "PPS",
                icon = Icons.Default.Sync,
                accentColor = IndustrialAmber
            )
            TelemetryTile(
                modifier = Modifier.weight(1f),
                title = "ACTIVE SOCKETS",
                value = stats.activeConnections.toString(),
                unit = "CONNS",
                icon = Icons.Default.Cable,
                accentColor = IndustrialCyan
            )
        }

        // PLC Profile Presets
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            border = androidx.compose.foundation.BorderStroke(1.dp, OutlineDark),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "TARGET PLC / HMI PROTOCOL PROFILE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = IndustrialCyan,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PlcBridgeViewModel.DEFAULT_PROFILES.take(3).forEach { profile ->
                        val isSelected = activeProfile.id == profile.id
                        FilterChip(
                            selected = isSelected,
                            onClick = { viewModel.applyProfile(profile) },
                            label = { Text(profile.name.substringBefore("(").trim(), fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = IndustrialCyan.copy(alpha = 0.2f),
                                selectedLabelColor = IndustrialCyan
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = activeProfile.description,
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
        }

        // FTPS Dynamic Passive Port Indicator
        AnimatedVisibility(visible = ethConfig.isFtpsAlgEnabled) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = androidx.compose.foundation.BorderStroke(1.dp, OutlineDark),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "FTPS ALG DYNAMIC PASSIVE PORTS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = IndustrialAmber,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = if (ftpsPorts.isEmpty()) "IDLE" else "${ftpsPorts.size} OPEN",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (ftpsPorts.isEmpty()) TextMuted else IndustrialEmerald
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (ftpsPorts.isEmpty()) {
                        Text(
                            text = "GP-Pro EX uses PASV for transfer. When transfer begins, dynamic ports are auto-mapped without root iptables conntrack.",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ftpsPorts.forEach { port ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(IndustrialCyan.copy(alpha = 0.15f))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = ":$port",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = IndustrialCyan
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Quick Hardware Diagnostics Action Bar
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            border = androidx.compose.foundation.BorderStroke(1.dp, OutlineDark),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "QUICK DIAGNOSTIC ACTIONS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.sendGratuitousArp() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = IndustrialCyan)
                    ) {
                        Text("Send ARP", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = { viewModel.probePlc() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = IndustrialEmerald)
                    ) {
                        Text("Probe HMI", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = { viewModel.resetStats() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted)
                    ) {
                        Text("Reset", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun NetworkTopologyCard(
    isRunning: Boolean,
    adapterIp: String,
    targetPlcIp: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceVariantDark)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Node 1: Windows Host (GP-Pro EX)
            TopologyNode(
                icon = Icons.Default.Computer,
                label = "GP-Pro EX",
                sublabel = "Windows Host",
                isActive = isRunning
            )

            // Link 1: Tailscale Subnet
            LinkConnector(
                label = "TLS 1.3 / VPN",
                isActive = isRunning,
                color = IndustrialCyan
            )

            // Node 2: Android Gateway
            TopologyNode(
                icon = Icons.Default.Security,
                label = "Galaxy S9+",
                sublabel = adapterIp,
                isActive = isRunning
            )

            // Link 2: USB Cable
            LinkConnector(
                label = "USB-ETH",
                isActive = isRunning,
                color = IndustrialEmerald
            )

            // Node 3: HMI / PLC
            TopologyNode(
                icon = Icons.Default.DeveloperBoard,
                label = "Pro-face HMI",
                sublabel = targetPlcIp,
                isActive = isRunning
            )
        }
    }
}

@Composable
fun TopologyNode(
    icon: ImageVector,
    label: String,
    sublabel: String,
    isActive: Boolean
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (isActive) IndustrialCyan.copy(alpha = 0.2f) else SurfaceDark)
                .border(1.dp, if (isActive) IndustrialCyan else OutlineDark, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isActive) IndustrialCyan else TextMuted,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = if (isActive) TextPrimary else TextMuted
        )
        Text(
            text = sublabel,
            fontSize = 9.sp,
            color = TextMuted,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun LinkConnector(label: String, isActive: Boolean, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 8.sp,
            color = if (isActive) color else TextMuted,
            fontWeight = FontWeight.Bold
        )
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(2.dp)
                .background(if (isActive) color else OutlineDark)
        )
    }
}

@Composable
fun TelemetryTile(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    unit: String,
    icon: ImageVector,
    accentColor: Color
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, OutlineDark),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary,
                    letterSpacing = 0.8.sp
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextPrimary,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = unit,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
        }
    }
}
