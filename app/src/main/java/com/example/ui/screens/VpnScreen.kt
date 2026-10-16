package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Terminal
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
fun VpnScreen(viewModel: PlcBridgeViewModel) {
    val context = LocalContext.current
    val tailscaleConfig by viewModel.tailscaleConfig.collectAsState()

    var nodeNameInput by remember(tailscaleConfig.nodeName) { mutableStateOf(tailscaleConfig.nodeName) }
    var routesInput by remember(tailscaleConfig.advertisedRoutes) { mutableStateOf(tailscaleConfig.advertisedRoutes) }
    var authKeyInput by remember(tailscaleConfig.authKey) { mutableStateOf(tailscaleConfig.authKey) }

    val windowsCmd = "tailscale up --accept-routes\nroute print 192.168.1.*"

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
                text = "TAILSCALE / WIREGUARD SUBNET ROUTING",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = IndustrialCyan,
                letterSpacing = 1.sp
            )
            Text(
                text = "Encrypted L3 Industrial Tunnel",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = TextPrimary
            )
        }

        // Tailscale Node Configuration Card
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
                        text = "TSNET IN-MEMORY NODE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary,
                        letterSpacing = 1.sp
                    )

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (tailscaleConfig.isConnected) IndustrialEmerald.copy(alpha = 0.2f) else TextMuted.copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (tailscaleConfig.isConnected) "SUBNET ROUTING ON" else "READY",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (tailscaleConfig.isConnected) IndustrialEmerald else TextMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = nodeNameInput,
                    onValueChange = { nodeNameInput = it },
                    label = { Text("Tailscale Node Name") },
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
                    value = routesInput,
                    onValueChange = { routesInput = it },
                    label = { Text("Advertised Subnet Routes") },
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
                    value = authKeyInput,
                    onValueChange = { authKeyInput = it },
                    label = { Text("Tailscale AuthKey (optional for auto-join)") },
                    placeholder = { Text("tskey-auth-...", color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = IndustrialCyan,
                        unfocusedBorderColor = OutlineDark,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = {
                        viewModel.updateTailscaleConfig(
                            tailscaleConfig.copy(
                                nodeName = nodeNameInput,
                                advertisedRoutes = routesInput,
                                authKey = authKeyInput
                            )
                        )
                        Toast.makeText(context, "Tailscale settings updated", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = IndustrialCyan,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Apply Subnet Configuration", fontWeight = FontWeight.Bold)
                }
            }
        }

        // Windows Host (GP-Pro EX) Instructions Card
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
                        text = "WINDOWS HOST CONFIGURATION (GP-PRO EX)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = IndustrialAmber,
                        letterSpacing = 1.sp
                    )

                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Tailscale Windows Command", windowsCmd)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Command copied to clipboard", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Command",
                            tint = IndustrialAmber,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "On the Windows PC running GP-Pro EX 4.09, enable route acceptance in Tailscale:",
                    fontSize = 12.sp,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceElevated)
                        .padding(10.dp)
                ) {
                    Text(
                        text = windowsCmd,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = IndustrialCyan
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "GP-Pro EX Settings:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "1. In 'Project' -> 'Transfer' -> 'Transfer Settings':\n" +
                           "2. Select 'LAN'\n" +
                           "3. Target IP: 192.168.1.12 (Direct HMI IP, no NAT translation needed!)\n" +
                           "4. Port: 21 (FTPS/FTP) with PASV enabled\n" +
                           "5. Click 'Send Project' or 'Receive Project'",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    lineHeight = 18.sp
                )
            }
        }

        // Zero-Root Equivalence Architecture Table
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            border = androidx.compose.foundation.BorderStroke(1.dp, OutlineDark),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "IPTABLES (ROOT) ➔ USERLAND (STOCK ROM) MAPPING",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = IndustrialEmerald,
                    letterSpacing = 1.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                MappingRow(
                    rootCmd = "ip link set eth0 up",
                    userlandEquiv = "UsbDeviceConnection.claimInterface() via USB Host"
                )
                MappingRow(
                    rootCmd = "ip rule add iif tun1 lookup main",
                    userlandEquiv = "Zero-copy Ring Buffer in-memory stream"
                )
                MappingRow(
                    rootCmd = "iptables -t nat -j MASQUERADE",
                    userlandEquiv = "Userland Ethernet L2 encapsulation with synthetic ARP"
                )
                MappingRow(
                    rootCmd = "TCPMSS --clamp-mss-to-pmtu",
                    userlandEquiv = "Inline SYN TCP Option Kind=2 modifier (1360B)"
                )
            }
        }
    }
}

@Composable
fun MappingRow(rootCmd: String, userlandEquiv: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = rootCmd,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = IndustrialAmber
        )
        Text(
            text = "➔ $userlandEquiv",
            fontSize = 11.sp,
            color = TextSecondary
        )
    }
}
