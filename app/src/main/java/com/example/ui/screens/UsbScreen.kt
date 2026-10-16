package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ElectricalServices
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.example.core.model.ArpEntry
import com.example.core.model.UsbDeviceInfo
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
fun UsbScreen(viewModel: PlcBridgeViewModel) {
    val usbDevices by viewModel.usbDevices.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val arpTable by viewModel.arpTable.collectAsState()
    val ethConfig by viewModel.ethernetConfig.collectAsState()

    var showAddArpDialog by remember { mutableStateOf(false) }
    var adapterIpInput by remember(ethConfig.adapterIp) { mutableStateOf(ethConfig.adapterIp) }
    var virtualMacInput by remember(ethConfig.virtualMac) { mutableStateOf(ethConfig.virtualMac) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "HARDWARE INTERFACE (STOCK ROM)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = IndustrialCyan,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "USB Ethernet & L2 Framing",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimary
                    )
                }

                IconButton(onClick = { viewModel.refreshUsbDevices() }) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh USB",
                        tint = IndustrialCyan
                    )
                }
            }
        }

        // USB Hardware Detection Card
        item {
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
                            text = "DETECTED USB NIC CONTROLLER",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary,
                            letterSpacing = 1.sp
                        )

                        if (selectedDevice != null) {
                            val hasFd = selectedDevice?.hasActiveFd == true
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (hasFd) IndustrialEmerald.copy(alpha = 0.2f) else IndustrialAmber.copy(alpha = 0.2f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (hasFd) "FD: ${selectedDevice?.fileDescriptor ?: "OPEN"}" else "NO FD",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (hasFd) IndustrialEmerald else IndustrialAmber
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (usbDevices.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(SurfaceVariantDark)
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Usb,
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "No USB Ethernet adapter detected via OTG",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Connect Realtek RTL8152/8153 or ASIX adapter via USB-C OTG",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                        }
                    } else {
                        usbDevices.forEach { dev ->
                            UsbDeviceItem(
                                device = dev,
                                isSelected = dev.deviceName == selectedDevice?.deviceName,
                                onSelect = { viewModel.selectUsbDevice(dev) }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }

        // L2 Adapter Configuration
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = androidx.compose.foundation.BorderStroke(1.dp, OutlineDark),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "VIRTUAL L2 ETHERNET PARAMETERS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = IndustrialCyan,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = adapterIpInput,
                        onValueChange = { adapterIpInput = it },
                        label = { Text("Adapter IP (dev eth0)") },
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

                    OutlinedTextField(
                        value = virtualMacInput,
                        onValueChange = { virtualMacInput = it },
                        label = { Text("Virtual MAC Address") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = IndustrialCyan,
                            unfocusedBorderColor = OutlineDark,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            viewModel.updateEthernetConfig(
                                ethConfig.copy(
                                    adapterIp = adapterIpInput,
                                    virtualMac = virtualMacInput
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = IndustrialCyan,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Save Network Parameters", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ARP Table Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "ARP TABLE CACHE (RESOLVED L2 MACs)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = IndustrialAmber,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "${arpTable.size} Entries Resolved",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { showAddArpDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Static ARP",
                            tint = IndustrialAmber
                        )
                    }
                    IconButton(onClick = { viewModel.sendGratuitousArp() }) {
                        Icon(
                            imageVector = Icons.Default.ElectricalServices,
                            contentDescription = "Probe ARP",
                            tint = IndustrialCyan
                        )
                    }
                }
            }
        }

        if (arpTable.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Box(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "No ARP entries yet. Tap 'Probe ARP' to broadcast who-has query to HMI.",
                            fontSize = 12.sp,
                            color = TextMuted
                        )
                    }
                }
            }
        } else {
            items(arpTable) { entry ->
                ArpEntryRow(entry = entry)
            }
        }
    }

    // Static ARP Dialog
    if (showAddArpDialog) {
        var ip by remember { mutableStateOf("192.168.1.12") }
        var mac by remember { mutableStateOf("00:0B:E0:12:34:56") }

        AlertDialog(
            onDismissRequest = { showAddArpDialog = false },
            title = { Text("Add Static ARP Entry", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Manual MAC binding for PLC/HMI (useful when HMI ignores ARP broadcasts):", fontSize = 12.sp, color = TextSecondary)
                    OutlinedTextField(
                        value = ip,
                        onValueChange = { ip = it },
                        label = { Text("Target IP") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = mac,
                        onValueChange = { mac = it },
                        label = { Text("Hardware MAC") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.setStaticArp(ip, mac)
                        showAddArpDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = IndustrialCyan, contentColor = Color.Black)
                ) {
                    Text("Add Entry")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddArpDialog = false }) {
                    Text("Cancel", color = TextMuted)
                }
            },
            containerColor = SurfaceDark
        )
    }
}

@Composable
fun UsbDeviceItem(
    device: UsbDeviceInfo,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) SurfaceElevated else SurfaceVariantDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) IndustrialCyan else OutlineDark),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Usb,
                contentDescription = null,
                tint = if (isSelected) IndustrialCyan else TextMuted,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.chipset,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "VID: 0x${device.vendorId.toString(16).uppercase()} | PID: 0x${device.productId.toString(16).uppercase()}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary
                )
                Text(
                    text = device.productName ?: device.deviceName,
                    fontSize = 10.sp,
                    color = TextMuted
                )
            }

            Icon(
                imageVector = if (device.isPermissionGranted) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                tint = if (device.isPermissionGranted) IndustrialEmerald else IndustrialAmber,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun ArpEntryRow(entry: ArpEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, OutlineDark),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = entry.ip,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = TextPrimary
                )
                Text(
                    text = entry.mac,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = IndustrialCyan
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (entry.isStatic) IndustrialAmber.copy(alpha = 0.2f) else IndustrialEmerald.copy(alpha = 0.2f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = if (entry.isStatic) "STATIC" else "DYNAMIC",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (entry.isStatic) IndustrialAmber else IndustrialEmerald
                )
            }
        }
    }
}
