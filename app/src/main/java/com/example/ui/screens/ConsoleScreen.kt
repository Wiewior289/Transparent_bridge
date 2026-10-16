package com.example.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.model.PacketLog
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ConsoleScreen(viewModel: PlcBridgeViewModel) {
    val packetLogs by viewModel.packetLogs.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()

    val filteredLogs = remember(packetLogs, selectedFilter) {
        if (selectedFilter == "ALL") packetLogs
        else packetLogs.filter {
            if (selectedFilter == "CLAMPED") it.isClamped
            else it.protocol.contains(selectedFilter, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Console Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "LIVE PACKET INSPECTOR",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = IndustrialCyan,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Raw Frame & Stream Monitor",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextPrimary
                )
            }

            Row {
                IconButton(onClick = { viewModel.probePlc() }) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Probe",
                        tint = IndustrialCyan
                    )
                }
                IconButton(onClick = { viewModel.clearPacketLogs() }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear Logs",
                        tint = IndustrialCoral
                    )
                }
            }
        }

        // Protocol Filter Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("ALL", "TCP", "ARP", "FTPS", "CLAMPED").forEach { filter ->
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { viewModel.setFilter(filter) },
                    label = { Text(filter, fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = IndustrialCyan.copy(alpha = 0.2f),
                        selectedLabelColor = IndustrialCyan
                    )
                )
            }
        }

        // Packet List
        if (filteredLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceDark)
                    .border(1.dp, OutlineDark, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Listening for network packets...",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Start bridge or plug USB adapter to capture telemetry",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceDark)
                    .border(1.dp, OutlineDark, RoundedCornerShape(12.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filteredLogs, key = { it.id }) { log ->
                    PacketLogRow(log = log)
                }
            }
        }
    }
}

@Composable
fun PacketLogRow(log: PacketLog) {
    var expanded by remember { mutableStateOf(false) }

    val protoColor = when {
        log.protocol.contains("FTPS") -> IndustrialCoral
        log.protocol.contains("ARP") -> IndustrialAmber
        log.protocol.contains("TCP") -> IndustrialCyan
        log.protocol.contains("UDP") -> IndustrialEmerald
        else -> TextSecondary
    }

    val timeStr = remember(log.timestamp) {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(log.timestamp))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(protoColor.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = log.protocol,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = protoColor
                        )
                    }

                    if (log.isClamped) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(IndustrialAmber.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "MSS CLAMP",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = IndustrialAmber
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = timeStr,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted
                    )
                }

                Text(
                    text = "${log.length} B",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${log.src} ➔ ${log.dst}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = TextPrimary
                )
            }

            if (log.info.isNotEmpty()) {
                Text(
                    text = log.info,
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }

            if (expanded && log.hexPreview.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(SurfaceDark)
                        .padding(8.dp)
                ) {
                    Text(
                        text = log.hexPreview,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = IndustrialCyan,
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }
}
