package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.PlcBridgeViewModel
import com.example.ui.theme.IndustrialAmber
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
fun SettingsScreen(viewModel: PlcBridgeViewModel) {
    val context = LocalContext.current
    var autoStartUsb by remember { mutableStateOf(true) }
    var ringBufferSize by remember { mutableIntStateOf(2048) }
    var keepCpuAwake by remember { mutableStateOf(true) }

    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    val isIgnoringBattery = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Column {
            Text(
                text = "SYSTEM ENGINE & STABILITY",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = IndustrialCyan,
                letterSpacing = 1.sp
            )
            Text(
                text = "Kernel Protection & Memory Settings",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = TextPrimary
            )
        }

        // Battery Optimization Exemption Card
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "BATTERY OPTIMIZATION EXEMPTION",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isIgnoringBattery) IndustrialEmerald else IndustrialAmber,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = if (isIgnoringBattery) "Unrestricted 24/7 background operation active" else "Aggressive Samsung / Android doze may kill service",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.BatteryChargingFull,
                        contentDescription = null,
                        tint = if (isIgnoringBattery) IndustrialEmerald else IndustrialAmber
                    )
                }

                if (!isIgnoringBattery) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                try {
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    context.startActivity(fallback)
                                }
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = IndustrialAmber),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Disable Battery Optimization", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Ring Buffer Memory Allocation
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
                            text = "PRE-ALLOCATED RING BUFFER",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = IndustrialCyan,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Zero-Copy RAM Pool Size",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        tint = IndustrialCyan
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(512, 1024, 2048, 4096).forEach { size ->
                        FilterChip(
                            selected = ringBufferSize == size,
                            onClick = { ringBufferSize = size },
                            label = { Text("$size pkts", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = IndustrialCyan.copy(alpha = 0.2f),
                                selectedLabelColor = IndustrialCyan
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                val ramMb = "%.2f".format((ringBufferSize * 1600.0) / (1024 * 1024))
                Text(
                    text = "Allocated RAM: $ramMb MB (Flat, static, zero GC pressure)",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = IndustrialEmerald
                )
            }
        }

        // Foreground Service Wakelock
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "PARTIAL_WAKE_LOCK & PRIORITY_MAX",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Prevents CPU sleep when screen turns off during long GP-Pro EX transfers",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }

                    Switch(
                        checked = keepCpuAwake,
                        onCheckedChange = { keepCpuAwake = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = IndustrialCyan,
                            checkedTrackColor = IndustrialCyan.copy(alpha = 0.3f)
                        )
                    )
                }
            }
        }

        // About / Industrial Specs
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            border = androidx.compose.foundation.BorderStroke(1.dp, OutlineDark),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "SPECIFICATIONS & COMPLIANCE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(10.dp))

                SpecRow("Target Environment", "Stock ROM (Zero Root)")
                SpecRow("Supported HMI / PLC", "Pro-face GP4000, Siemens S7, Modbus, Omron")
                SpecRow("Transfer Software", "Pro-face GP-Pro EX 4.09 (LAN / FTPS)")
                SpecRow("Transport Layer", "Tailscale tsnet / WireGuard (TLS 1.3)")
                SpecRow("USB Chipsets", "Realtek RTL8152/8153, ASIX AX88179, CDC-ECM")
            }
        }
    }
}

@Composable
fun SpecRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 11.sp, color = TextSecondary)
        Text(text = value, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
    }
}
