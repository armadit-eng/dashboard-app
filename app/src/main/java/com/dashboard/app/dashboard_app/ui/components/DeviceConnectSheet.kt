package com.dashboard.app.dashboard_app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SettingsBluetooth
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dashboard.app.dashboard_app.model.BatterySourcePriority
import com.dashboard.app.dashboard_app.model.ConnectionStateX
import com.dashboard.app.dashboard_app.ui.theme.LocalCockpitColors
import com.dashboard.app.dashboard_app.viewmodel.DashboardUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceConnectSheet(
    state: DashboardUiState,
    onDismiss: () -> Unit,
    onScanDevices: () -> Unit,
    onConnectMotor: (address: String, preferBle: Boolean) -> Unit,
    onDisconnectMotor: () -> Unit,
    onConnectJkBms: (address: String) -> Unit,
    onDisconnectJkBms: () -> Unit,
    onConnectEspSlot: (slot: Int, address: String) -> Unit,
    onDisconnectEspSlot: (slot: Int) -> Unit,
    onChangeBatteryPriority: (BatterySourcePriority) -> Unit
) {
    val c = LocalCockpitColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedSlot by remember { mutableIntStateOf(0) } // 0=Motor, 1=JK-BMS, 2=ESP1, 3=ESP2
    var preferBleMotor by remember { mutableStateOf(false) }
    var showSerialLog by remember { mutableStateOf(false) }
    var priorityMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        onScanDevices()
    }

    val devices = remember(selectedSlot, state.pairedBtDevices, state.scannedBleDevices) {
        if (selectedSlot == 0) {
            val pairedAddrs = state.pairedBtDevices.map { it.address }.toSet()
            state.pairedBtDevices + state.scannedBleDevices.filterNot { it.address in pairedAddrs }
        } else {
            state.scannedBleDevices
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.surfaceCard,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Rounded.BluetoothSearching,
                    contentDescription = "Pemindaian Bluetooth",
                    tint = c.accent
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Koneksi Perangkat EV",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { showSerialLog = !showSerialLog },
                    modifier = Modifier.testTag("toggle_serial_monitor_button")
                ) {
                    Icon(
                        if (showSerialLog) Icons.Rounded.Terminal else Icons.AutoMirrored.Rounded.ListAlt,
                        contentDescription = "Serial Monitor",
                        tint = if (showSerialLog) c.accent else c.textMuted
                    )
                }
                IconButton(
                    onClick = onScanDevices,
                    enabled = !state.isScanningBle,
                    modifier = Modifier.testTag("rescan_bt_button")
                ) {
                    if (state.isScanningBle) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = c.accent
                        )
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Pindai ulang")
                    }
                }
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    SlotPill(
                        selected = selectedSlot == 0,
                        label = "Motor MID",
                        state = state.motorConnState,
                        sub = state.motorConnName.ifEmpty { "SPP / BLE" },
                        onSelect = { selectedSlot = 0 },
                        onDisconnect = onDisconnectMotor
                    )
                }
                item {
                    SlotPill(
                        selected = selectedSlot == 1,
                        label = "JK-BMS",
                        state = state.jkConnState,
                        sub = state.jkConnName.ifEmpty { "Baterai 1 BLE" },
                        onSelect = { selectedSlot = 1 },
                        onDisconnect = onDisconnectJkBms
                    )
                }
                item {
                    SlotPill(
                        selected = selectedSlot == 2,
                        label = "ESP32 #1",
                        state = state.esp1ConnState,
                        sub = state.esp1ConnName.ifEmpty { "Kontak/Matrix" },
                        onSelect = { selectedSlot = 2 },
                        onDisconnect = { onDisconnectEspSlot(1) }
                    )
                }
                item {
                    SlotPill(
                        selected = selectedSlot == 3,
                        label = "ESP32 #2",
                        state = state.esp2ConnState,
                        sub = state.esp2ConnName.ifEmpty { "Baterai 2 CAN" },
                        onSelect = { selectedSlot = 3 },
                        onDisconnect = { onDisconnectEspSlot(2) }
                    )
                }
            }

            if (selectedSlot == 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = !preferBleMotor,
                        onClick = { preferBleMotor = false },
                        label = { Text("Classic SPP") },
                        modifier = Modifier.testTag("motor_spp_chip")
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = preferBleMotor,
                        onClick = { preferBleMotor = true },
                        label = { Text("BLE GATT") },
                        modifier = Modifier.testTag("motor_ble_chip")
                    )
                    Spacer(Modifier.weight(1f))
                    Box {
                        TextButton(
                            onClick = { priorityMenuExpanded = true },
                            modifier = Modifier.testTag("battery_priority_button")
                        ) {
                            Text("Prioritas: ${state.settings.batteryPriority.label}")
                        }
                        DropdownMenu(
                            expanded = priorityMenuExpanded,
                            onDismissRequest = { priorityMenuExpanded = false }
                        ) {
                            BatterySourcePriority.entries.forEach { prio ->
                                DropdownMenuItem(
                                    text = { Text("Prioritas: ${prio.label}") },
                                    onClick = {
                                        priorityMenuExpanded = false
                                        onChangeBatteryPriority(prio)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = c.borderSubtle)

            if (showSerialLog) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    items(state.serialLogs) { entry ->
                        Text(
                            text = "[${entry.source}] ${entry.message}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp,
                            color = if (entry.isError) c.danger else c.accent
                        )
                    }
                }
            } else if (devices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Rounded.BluetoothDisabled,
                            contentDescription = "Tidak ada perangkat",
                            modifier = Modifier.size(40.dp),
                            tint = c.textMuted
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = if (state.isScanningBle) {
                                "Memindai perangkat di sekitar…"
                            } else {
                                "Belum ada perangkat ditemukan.\nPastikan Bluetooth & Lokasi aktif."
                            },
                            textAlign = TextAlign.Center,
                            color = c.textMuted
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(devices, key = { "${it.address}_${it.isPaired}" }) { d ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = c.surfaceElevated,
                            border = BorderStroke(1.dp, c.borderSubtle)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(c.accent.copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        if (d.isBle) Icons.Rounded.Bluetooth else Icons.Rounded.SettingsBluetooth,
                                        contentDescription = d.name,
                                        tint = c.accent
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        d.name,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${d.address} • ${if (d.isPaired) "Paired" else "BLE ${d.rssi} dBm"}",
                                        fontSize = 12.sp,
                                        color = c.textMuted
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        when (selectedSlot) {
                                            0 -> onConnectMotor(d.address, preferBleMotor || d.isBle)
                                            1 -> onConnectJkBms(d.address)
                                            2 -> onConnectEspSlot(1, d.address)
                                            3 -> onConnectEspSlot(2, d.address)
                                        }
                                    },
                                    modifier = Modifier.testTag("connect_device_btn_${d.address}")
                                ) {
                                    Text("Hubung")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotPill(
    selected: Boolean,
    label: String,
    state: ConnectionStateX,
    sub: String,
    onSelect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val c = LocalCockpitColors.current
    val isConn = state == ConnectionStateX.CONNECTED
    val isBusy = state == ConnectionStateX.CONNECTING

    Surface(
        modifier = Modifier
            .width(156.dp)
            .height(58.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) c.accent.copy(alpha = 0.14f) else c.surfaceElevated,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) c.accent else c.borderSubtle)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isConn -> c.accent
                            isBusy -> c.warning
                            else -> c.textMuted.copy(alpha = 0.5f)
                        }
                    )
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.5.sp
                )
                Text(
                    sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 10.5.sp,
                    color = c.textMuted
                )
            }
            if (isConn) {
                IconButton(
                    onClick = onDisconnect,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Rounded.LinkOff,
                        contentDescription = "Putus $label",
                        tint = c.danger,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
