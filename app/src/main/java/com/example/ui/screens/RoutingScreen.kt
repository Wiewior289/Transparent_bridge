package com.example.ui.screens

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
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
fun RoutingScreen(viewModel: PlcBridgeViewModel) {
    val ethConfig by viewModel.ethernetConfig.collectAsState()
    val activeProfile by viewModel.activeProfile.collectAsState()

    var targetIpInput by remember(ethConfig.targetPlcIp) { mutableStateOf(ethConfig.targetPlcIp) }
    var mssValue by remember(ethConfig.targetMss) { mutableFloatStateOf(ethConfig.targetMss.toFloat()) }
    var isMssEnabled by remember(ethConfig.isMssClampingEnabled) { mutableStateOf(ethConfig.isMssClampingEnabled) }
    var isFtpsEnabled by remember(ethConfig.isFtpsAlgEnabled) { mutableStateOf(ethConfig.isFtpsAlgEnabled) }

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
                text = "PACKET PIPELINE & TRANSFORMATION",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = IndustrialCyan,
                letterSpacing = 1.sp
            )
            Text(
                text = "TCP MSS Clamping & ALG",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = TextPrimary
            )
        }

        // Target PLC / HMI IP configuration
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            border = androidx.compose.foundation.BorderStroke(1.dp, OutlineDark),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "TARGET PLC / HMI DESTINATION",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = IndustrialCyan,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = targetIpInput,
                    onValueChange = { targetIpInput = it },
                    label = { Text("Target IP (e.g. Pro-face HMI)") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = IndustrialCyan,
                        unfocusedBorderColor = OutlineDark,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = {
                        viewModel.updateEthernetConfig(ethConfig.copy(targetPlcIp = targetIpInput))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = IndustrialCyan,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Update Target Destination", fontWeight = FontWeight.Bold)
                }
            }
        }

        // TCP MSS Clamping Card
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
                            text = "TCP MSS CLAMPING (TCPMSS)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = IndustrialAmber,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Clamp TCP SYN packets to tunnel PMTU",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }

                    Switch(
                        checked = isMssEnabled,
                        onCheckedChange = {
                            isMssEnabled = it
                            viewModel.updateEthernetConfig(ethConfig.copy(isMssClampingEnabled = it))
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = IndustrialCyan,
                            checkedTrackColor = IndustrialCyan.copy(alpha = 0.3f)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Clamped MSS Target:",
                        fontSize = 13.sp,
                        color = TextPrimary
                    )
                    Text(
                        text = "${mssValue.toInt()} Bytes",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = IndustrialAmber
                    )
                }

                Slider(
                    value = mssValue,
                    onValueChange = { mssValue = it },
                    onValueChangeFinished = {
                        viewModel.updateEthernetConfig(ethConfig.copy(targetMss = mssValue.toInt()))
                    },
                    valueRange = 1200f..1460f,
                    colors = SliderDefaults.colors(
                        thumbColor = IndustrialAmber,
                        activeTrackColor = IndustrialAmber
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(1360, 1400, 1460).forEach { preset ->
                        FilterChip(
                            selected = mssValue.toInt() == preset,
                            onClick = {
                                mssValue = preset.toFloat()
                                viewModel.updateEthernetConfig(ethConfig.copy(targetMss = preset))
                            },
                            label = { Text("${preset}B", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = IndustrialAmber.copy(alpha = 0.2f),
                                selectedLabelColor = IndustrialAmber
                            )
                        )
                    }
                }
            }
        }

        // FTPS Dynamic Passive Port ALG Card
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
                            text = "FTPS / FTP DYNAMIC PASV ALG",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = IndustrialEmerald,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Auto-track dynamic ports in 227 replies",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }

                    Switch(
                        checked = isFtpsEnabled,
                        onCheckedChange = {
                            isFtpsEnabled = it
                            viewModel.updateEthernetConfig(ethConfig.copy(isFtpsAlgEnabled = it))
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = IndustrialEmerald,
                            checkedTrackColor = IndustrialEmerald.copy(alpha = 0.3f)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "GP-Pro EX uses PASV data connections. The ALG inspects the 227 responses on port 21 / 8000 and automatically creates userland listeners so download to HMI, receive to PC, and monitor run smoothly.",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    lineHeight = 18.sp
                )
            }
        }

        // Industrial Port Forwarding Table
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            border = androidx.compose.foundation.BorderStroke(1.dp, OutlineDark),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "SUPPORTED INDUSTRIAL PORTS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(10.dp))

                PortRow(port = 21, name = "FTP / FTPS Control", desc = "GP-Pro EX project transfer & backup")
                PortRow(port = 8000, name = "GP-Pro EX Runtime", desc = "Screen transfer & online pass-through")
                PortRow(port = 502, name = "Modbus TCP", desc = "Schneider, Fatek, Weintek registers")
                PortRow(port = 102, name = "ISO-on-TCP (RFC1006)", desc = "Siemens S7-300/400/1200/1500")
                PortRow(port = 44818, name = "EtherNet/IP CIP", desc = "Rockwell Allen-Bradley, Omron")
            }
        }
    }
}

@Composable
fun PortRow(port: Int, name: String, desc: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = desc,
                fontSize = 11.sp,
                color = TextSecondary
            )
        }

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
