package com.dashboard.app.dashboard_app.ui.screens

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.ElectricMoped
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.dashboard.app.dashboard_app.engine.TelemetryEngine
import com.dashboard.app.dashboard_app.model.AlertSeverity
import com.dashboard.app.dashboard_app.model.ChargingIndicatorState
import com.dashboard.app.dashboard_app.model.ConnectionStateX
import com.dashboard.app.dashboard_app.model.ControllerProtocol
import com.dashboard.app.dashboard_app.model.MaintHealthLevel
import com.dashboard.app.dashboard_app.model.PlannedStop
import com.dashboard.app.dashboard_app.ui.components.CockpitCard
import com.dashboard.app.dashboard_app.ui.components.SpeedoGauge
import com.dashboard.app.dashboard_app.ui.theme.LocalCockpitColors
import com.dashboard.app.dashboard_app.viewmodel.DashboardUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeCockpitScreen(
    state: DashboardUiState,
    onOpenDeviceSheet: () -> Unit,
    onRefreshWeather: () -> Unit,
    onOpenTab: (Int) -> Unit,
    onOpenRidingCockpit: () -> Unit,
    onOpenMotovlogCam: () -> Unit,
    onSaveTrip: () -> Unit,
    onResetTrip: () -> Unit,
    onRouteToParked: (PlannedStop) -> Unit,
    onAddMaintenanceLog: (odoKm: Double, serviceType: String) -> Unit,
    onToggleDevSim: (Boolean) -> Unit,
    onSendMotorAt: (String) -> Unit,
    onDismissIdleDrain: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalCockpitColors.current
    val eff = state.effective
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    var showMaintDialog by remember { mutableStateOf(false) }
    var devExpanded by remember { mutableStateOf(false) }
    var atCmdText by remember { mutableStateOf("AT+ODO?") }

    if (showMaintDialog) {
        var odoStr by remember(eff.odoKm) {
            mutableStateOf(String.format(Locale.US, "%.0f", eff.odoKm ?: 0.0))
        }
        var typeStr by remember { mutableStateOf("Servis Berkala / Kampas Rem") }
        AlertDialog(
            onDismissRequest = { showMaintDialog = false },
            title = { Text("Catat Servis Baru") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = odoStr,
                        onValueChange = { odoStr = it },
                        label = { Text("Odometer (km)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = typeStr,
                        onValueChange = { typeStr = it },
                        label = { Text("Jenis Servis") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val odo = odoStr.toDoubleOrNull() ?: (eff.odoKm ?: 0.0)
                        onAddMaintenanceLog(odo, typeStr.ifBlank { "Servis Berkala" })
                        showMaintDialog = false
                    },
                    modifier = Modifier.testTag("confirm_maint_log_button")
                ) {
                    Text("Simpan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMaintDialog = false }) { Text("Batal") }
            }
        )
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            scope.launch {
                isRefreshing = true
                onRefreshWeather()
                delay(600L)
                isRefreshing = false
            }
        },
        modifier = modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Rider Header
            item {
                val avatarFile = state.settings.avatarPath.takeIf { it.isNotEmpty() && File(it).exists() }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(c.accent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (avatarFile != null) {
                            Image(
                                painter = rememberAsyncImagePainter(Uri.fromFile(File(avatarFile))),
                                contentDescription = "Foto Pengendara",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                Icons.Rounded.ElectricMoped,
                                contentDescription = "Ikon Pengendara",
                                tint = c.accent
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Halo, ${state.settings.riderName}",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${state.settings.bikeName} • ${state.settings.plateNumber}",
                            fontSize = 12.5.sp,
                            color = c.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    val anyConnected = state.motorConnState == ConnectionStateX.CONNECTED ||
                        state.jkConnState == ConnectionStateX.CONNECTED ||
                        state.esp1ConnState == ConnectionStateX.CONNECTED ||
                        state.esp2ConnState == ConnectionStateX.CONNECTED
                    FilledTonalButton(
                        onClick = onOpenDeviceSheet,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier
                            .height(40.dp)
                            .testTag("open_device_sheet_button")
                    ) {
                        Icon(Icons.Rounded.Bluetooth, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (anyConnected) "Terhubung" else "Hubung", fontSize = 13.sp, maxLines = 1, softWrap = false)
                    }
                }
            }

            // 2. Connection Badges Row
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { ConnBadge(state.settings.controllerProtocol.shortLabel, state.motorConnState, c.accent, c.warning, c.textMuted, onOpenDeviceSheet) }
                    item { ConnBadge(state.settings.bmsProtocol.shortLabel, state.jkConnState, c.accent, c.warning, c.textMuted, onOpenDeviceSheet) }
                    item { ConnBadge("ESP32 #1", state.esp1ConnState, c.accent, c.warning, c.textMuted, onOpenDeviceSheet) }
                    item { ConnBadge("ESP32 #2", state.esp2ConnState, c.accent, c.warning, c.textMuted, onOpenDeviceSheet) }
                    if (state.devSimActive) {
                        item {
                            Surface(
                                shape = CircleShape,
                                color = c.warning.copy(alpha = 0.16f),
                                border = BorderStroke(1.dp, c.warning.copy(alpha = 0.6f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Rounded.Science,
                                        contentDescription = null,
                                        tint = c.warning,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "SIMULASI DEV",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = c.warning
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Active Alarms
            if (state.activeAlerts.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.activeAlerts.take(3).forEach { al ->
                            val col = when (al.severity) {
                                AlertSeverity.CRITICAL -> c.danger
                                AlertSeverity.WARNING -> c.warning
                                AlertSeverity.INFO -> c.accentSecondary
                            }
                            CockpitCard(
                                backgroundColor = col.copy(alpha = 0.12f),
                                borderColor = col.copy(alpha = 0.55f),
                                contentPadding = 12.dp
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Warning, contentDescription = null, tint = col)
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(al.title, fontWeight = FontWeight.Bold, color = col)
                                        Text(al.message, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3b. Phantom Idle Drain Warning
            if (state.idleDrainWarningPct != null) {
                item {
                    CockpitCard(
                        backgroundColor = c.warning.copy(alpha = 0.12f),
                        borderColor = c.warning.copy(alpha = 0.55f),
                        contentPadding = 12.dp
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Warning, contentDescription = null, tint = c.warning)
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Indikasi Idle Drain (-${String.format(Locale.US, "%.1f", state.idleDrainWarningPct)}%)", fontWeight = FontWeight.Bold, color = c.warning)
                                Text("SOC turun sejak parkir terakhir. Periksa beban standby motor/BMS.", fontSize = 12.sp)
                            }
                            TextButton(
                                onClick = onDismissIdleDrain,
                                modifier = Modifier.testTag("dismiss_idle_drain_button")
                            ) {
                                Text("Tutup")
                            }
                        }
                    }
                }
            }

            // 4. Hero Dual-Battery & Speed/Range Card
            item {
                val chg = TelemetryEngine.chargingIndicator(eff)
                val soc = eff.combinedSocPct
                val chgColor = when (chg) {
                    ChargingIndicatorState.CHARGING -> c.regen
                    ChargingIndicatorState.REGEN -> c.accentSecondary
                    ChargingIndicatorState.FULL -> c.accent
                    ChargingIndicatorState.NOT_CHARGING -> c.textMuted
                }

                CockpitCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "ENERGI & JARAK TEMPUH",
                                style = MaterialTheme.typography.labelMedium,
                                color = c.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(6.dp))
                            Surface(
                                shape = CircleShape,
                                color = chgColor.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    chg.label,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = chgColor,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = eff.rangeKm?.let { "${String.format(Locale.US, "%.1f", it)} km" } ?: "— km",
                                style = MaterialTheme.typography.displayLarge.copy(fontSize = 36.sp),
                                color = c.accent
                            )
                            Text(
                                text = "SOC ${soc?.roundToInt()?.toString() ?: "—"}% • ${String.format(Locale.US, "%.0f", eff.blendedWhPerKm)} Wh/km",
                                color = c.textMuted,
                                fontSize = 13.sp
                            )
                            if (eff.chargeEtaHours != null) {
                                val mins = (eff.chargeEtaHours * 60.0).roundToInt()
                                Text(
                                    text = "Estimasi penuh: ${mins / 60}j ${mins % 60}m",
                                    fontSize = 12.sp,
                                    color = c.regen,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        Box(
                            modifier = Modifier.size(116.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            SpeedoGauge(
                                speedKmh = eff.speedKmh ?: 0.0,
                                powerW = eff.combinedPowerW,
                                socPct = soc,
                                accentColor = c.accent,
                                secondaryColor = c.accentSecondary,
                                dangerColor = c.danger,
                                trackColor = c.borderSubtle,
                                isRegen = eff.isRegen,
                                strokeWidth = 10f,
                                modifier = Modifier.fillMaxSize()
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = eff.speedKmh?.roundToInt()?.toString() ?: "—",
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text("km/h", fontSize = 10.sp, color = c.textMuted)
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = c.borderSubtle)
                    Spacer(Modifier.height(12.dp))

                    val m = state.motorData
                    val fd = state.fardriverData
                    val jk = state.jkData
                    val useFardriver = state.settings.controllerProtocol != ControllerProtocol.MID_AT
                    val bmsShort = state.settings.bmsProtocol.shortLabel
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (useFardriver) {
                            MiniMetric(
                                title = "Fardriver",
                                value = "${fd.voltageV?.let { String.format(Locale.US, "%.1f", it) } ?: "—"} V",
                                sub = if (fd.framesCount > 0) {
                                    "${fd.lineCurrentA?.let { String.format(Locale.US, "%.1f", it) } ?: "—"}A • ${fd.rpm ?: "—"} RPM"
                                } else "Belum terhubung",
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            MiniMetric(
                                title = "Baterai ORI (MID)",
                                value = "${m.socPct?.roundToInt()?.toString() ?: "—"}%",
                                sub = "${m.vbat?.let { String.format(Locale.US, "%.1f", it) } ?: "—"}V • ${m.ibat?.let { String.format(Locale.US, "%.1f", it) } ?: "—"}A" +
                                    (m.cbatCycles?.let { " • ${it}x" } ?: ""),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        MiniMetric(
                            title = bmsShort,
                            value = "${jk.socPct?.roundToInt()?.toString() ?: "—"}%",
                            sub = if (jk.vpack != null || jk.ibat != null) {
                                "${jk.vpack?.let { String.format(Locale.US, "%.1f", it) } ?: "—"}V • ${jk.ibat?.let { String.format(Locale.US, "%.1f", it) } ?: "—"}A" +
                                    (jk.cycles?.let { " • ${it}x" } ?: "")
                            } else "Belum terhubung",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (eff.b2Active) {
                        Spacer(Modifier.height(8.dp))
                        MiniMetric(
                            title = "Baterai 2 (CAN/ESP32 #2)",
                            value = "${eff.b2SocPct?.roundToInt()?.toString() ?: "—"}%",
                            sub = "${eff.b2Vbat?.let { String.format(Locale.US, "%.1f", it) } ?: "—"}V • ${eff.b2Ibat?.let { String.format(Locale.US, "%.1f", it) } ?: "—"}A",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MiniMetric(
                            title = "Daya Total (${eff.b1SourceLabel})",
                            value = "${eff.combinedPowerW?.roundToInt()?.toString() ?: "—"} W",
                            sub = "Mode: ${eff.modeLabel}",
                            modifier = Modifier.weight(1f)
                        )
                        MiniMetric(
                            title = "Odometer",
                            value = "${eff.odoKm?.let { String.format(Locale.US, "%.1f", it) } ?: "—"} km",
                            sub = "SOH ${eff.combinedSohPct?.roundToInt()?.toString() ?: "—"}%",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 5. Quick Actions Row
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickActionTile(
                        icon = Icons.Rounded.Speed,
                        label = "Mode Kokpit",
                        color = c.accent,
                        testTag = "quick_riding_cockpit",
                        onClick = onOpenRidingCockpit,
                        modifier = Modifier.weight(1f)
                    )
                    QuickActionTile(
                        icon = Icons.Rounded.Navigation,
                        label = "Navigasi",
                        color = c.accentSecondary,
                        testTag = "quick_navigation",
                        onClick = { onOpenTab(2) },
                        modifier = Modifier.weight(1f)
                    )
                    QuickActionTile(
                        icon = Icons.Rounded.Videocam,
                        label = "Motovlog",
                        color = c.warning,
                        testTag = "quick_motovlog",
                        onClick = onOpenMotovlogCam,
                        modifier = Modifier.weight(1f)
                    )
                    QuickActionTile(
                        icon = Icons.Rounded.BatteryChargingFull,
                        label = "Diagnostik",
                        color = c.regen,
                        testTag = "quick_diagnostik",
                        onClick = { onOpenTab(1) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 6. Live Trip & Riding Score Card
            item {
                val trip = state.liveTrip
                val score = state.ridingScore
                val scoreVal = score.score
                val scoreCol = when {
                    scoreVal == null -> c.textMuted
                    scoreVal >= 80 -> c.accent
                    scoreVal >= 60 -> c.warning
                    else -> c.danger
                }

                CockpitCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Route, contentDescription = null, tint = c.accent)
                        Spacer(Modifier.width(8.dp))
                        Text("PERJALANAN SAAT INI", style = MaterialTheme.typography.labelMedium, color = c.textMuted, modifier = Modifier.weight(1f))
                        if (state.tripBaselineArmed) {
                            Surface(
                                shape = CircleShape,
                                color = c.warning.copy(alpha = 0.16f),
                                modifier = Modifier.padding(end = 6.dp)
                            ) {
                                Text(
                                    text = "Siaga Start",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = c.warning,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                        Surface(
                            shape = CircleShape,
                            color = scoreCol.copy(alpha = 0.14f)
                        ) {
                            Text(
                                text = "Skor ${scoreVal ?: "—"} • ${score.label}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = scoreCol,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TripCol("Jarak", "${String.format(Locale.US, "%.2f", trip.distanceKm)} km", Modifier.weight(1f))
                        TripCol("Konsumsi", "${trip.energyWh.roundToInt()} Wh", Modifier.weight(1f))
                        TripCol("Regen", "+${trip.regenWh.roundToInt()} Wh", Modifier.weight(1f))
                        TripCol("Maks", "${trip.maxSpeedKmh.roundToInt()} km/h", Modifier.weight(1f))
                    }
                    if (trip.maxCurrentA > 0.5 || trip.minVbat != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Puncak Arus: ${String.format(Locale.US, "%.1f", trip.maxCurrentA)} A" +
                                (trip.minVbat?.let { " • Vbat Min ${String.format(Locale.US, "%.1f", it)} V" } ?: ""),
                            fontSize = 11.5.sp,
                            color = c.textMuted
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = onSaveTrip,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("save_trip_button")
                        ) {
                            Text("Simpan Trip")
                        }
                        OutlinedButton(
                            onClick = onResetTrip,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("reset_trip_button")
                        ) {
                            Text("Reset")
                        }
                    }
                }
            }

            // 7. Weather + Location Card
            item {
                val w = state.weather
                val g = state.gpsState
                val locText = listOf(g.roadName, g.areaName).filter { it.isNotBlank() }.joinToString(", ")

                CockpitCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (w != null && w.rainProbPct >= 40) Icons.Rounded.WaterDrop else Icons.Rounded.WbSunny,
                            contentDescription = null,
                            tint = if (w != null && w.rainProbPct >= 40) c.regen else c.warning,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (w != null) "${w.tempC.roundToInt()}°C • ${w.description}" else "Memuat cuaca…",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = if (locText.isNotEmpty()) {
                                    "$locText${if (g.isFromCache) " (cache)" else ""}"
                                } else "Mencari koordinat GPS…",
                                fontSize = 12.sp,
                                color = c.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (w != null) {
                            Surface(
                                shape = CircleShape,
                                color = c.accentSecondary.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    w.moodTag,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = c.accentSecondary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                    if (w != null) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            WeatherChip(Icons.Rounded.WaterDrop, "Hujan ${w.rainProbPct}%")
                            WeatherChip(Icons.Rounded.Air, "${w.windKmh.roundToInt()} km/h")
                            WeatherChip(Icons.Rounded.LightMode, w.sunrise)
                            WeatherChip(Icons.Rounded.NightsStay, w.sunset)
                        }
                    }
                }
            }

            // 8. Parked Motor Finder Card
            if (state.parkedLocation != null) {
                item {
                    val p = state.parkedLocation
                    CockpitCard(borderColor = c.accentSecondary.copy(alpha = 0.4f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = c.accentSecondary)
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Posisi Parkir Terakhir", fontWeight = FontWeight.Bold)
                                Text(p.label, fontSize = 12.sp, color = c.textMuted)
                            }
                            Button(
                                onClick = {
                                    onRouteToParked(
                                        PlannedStop(
                                            id = "park",
                                            label = "Parkir Motor (${p.label})",
                                            lat = p.lat,
                                            lng = p.lng
                                        )
                                    )
                                },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("find_parked_motor_button")
                            ) {
                                Icon(Icons.Rounded.Directions, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Cari")
                            }
                        }
                    }
                }
            }

            // 9. Maintenance Reminder Card
            item {
                val maint = TelemetryEngine.computeMaintenanceStatus(eff.odoKm, state.settings)
                val barCol = when (maint?.level) {
                    MaintHealthLevel.DUE -> c.danger
                    MaintHealthLevel.WARN -> c.warning
                    else -> c.accent
                }

                CockpitCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Build, contentDescription = null, tint = c.warning, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("PENGINGAT SERVIS", style = MaterialTheme.typography.labelMedium, color = c.textMuted, modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = { showMaintDialog = true },
                            modifier = Modifier.testTag("open_maint_dialog_button")
                        ) {
                            Text("Catat")
                        }
                    }
                    if (maint == null) {
                        Text(
                            "Hubungkan motor atau catat ODO servis terakhir untuk mengaktifkan pengingat interval ${state.settings.maintIntervalKm.roundToInt()} km.",
                            fontSize = 12.5.sp,
                            color = c.textMuted
                        )
                    } else {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { maint.progressPct.toFloat() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            color = barCol,
                            trackColor = c.surfaceElevated
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (maint.overdueKm > 0) {
                                "Terlewat ${maint.overdueKm.roundToInt()} km dari jadwal servis ${maint.nextServiceOdoKm.roundToInt()} km!"
                            } else {
                                "Sisa ${maint.remainingKm.roundToInt()} km menuju servis berikutnya (${maint.nextServiceOdoKm.roundToInt()} km)"
                            },
                            fontSize = 12.5.sp,
                            color = if (maint.overdueKm > 0) c.danger else Color.Unspecified
                        )
                    }
                }
            }

            // 10. Developer Tools & AT Terminal
            item {
                CockpitCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clickable { devExpanded = !devExpanded },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.DeveloperMode, contentDescription = null, tint = c.accent)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Dev Tools & AT Terminal", fontWeight = FontWeight.SemiBold)
                            Text("Simulasi telemetri & kirim perintah AT+", fontSize = 11.5.sp, color = c.textMuted)
                        }
                        Icon(
                            if (devExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = "Buka Dev Tools",
                            tint = c.textMuted
                        )
                    }
                    if (devExpanded) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = c.borderSubtle)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Mode Simulasi Dev (DEV-SIM MOTOR)", fontWeight = FontWeight.SemiBold)
                                Text("Injeksi speed, arus, voltase & suhu sintetis", fontSize = 12.sp, color = c.textMuted)
                            }
                            Switch(
                                checked = state.devSimActive,
                                onCheckedChange = onToggleDevSim,
                                modifier = Modifier.testTag("dev_sim_switch")
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = atCmdText,
                                onValueChange = { atCmdText = it },
                                label = { Text("Perintah AT+") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    if (atCmdText.isNotBlank()) onSendMotorAt(atCmdText.trim())
                                },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("send_at_command_button")
                            ) {
                                Icon(Icons.Rounded.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Kirim")
                            }
                        }
                        if (state.serialLogs.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            state.serialLogs.take(6).forEach { log ->
                                Text(
                                    text = "[${log.source}] ${log.message}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = if (log.isError) c.danger else c.accent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnBadge(
    label: String,
    state: ConnectionStateX,
    accent: Color,
    warning: Color,
    muted: Color,
    onClick: () -> Unit
) {
    val c = LocalCockpitColors.current
    val col = when (state) {
        ConnectionStateX.CONNECTED -> accent
        ConnectionStateX.CONNECTING, ConnectionStateX.SCANNING -> warning
        ConnectionStateX.DISCONNECTED -> muted
    }
    Surface(
        modifier = Modifier
            .height(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = c.surfaceCard,
        border = BorderStroke(
            1.dp,
            if (state == ConnectionStateX.CONNECTED) col.copy(alpha = 0.6f) else c.borderSubtle
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(col)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = if (state == ConnectionStateX.CONNECTED) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

@Composable
private fun MiniMetric(
    title: String,
    value: String,
    sub: String,
    modifier: Modifier = Modifier
) {
    val c = LocalCockpitColors.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = c.surfaceElevated
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(title, fontSize = 11.sp, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(sub, fontSize = 11.sp, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun QuickActionTile(
    icon: ImageVector,
    label: String,
    color: Color,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalCockpitColors.current
    Surface(
        modifier = modifier
            .height(82.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = RoundedCornerShape(16.dp),
        color = c.surfaceCard,
        border = BorderStroke(1.dp, c.borderSubtle)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TripCol(label: String, value: String, modifier: Modifier = Modifier) {
    val c = LocalCockpitColors.current
    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        Text(label, fontSize = 11.sp, color = c.textMuted)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun WeatherChip(icon: ImageVector, label: String) {
    val c = LocalCockpitColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        WeatherMetricIcon(icon, contentDescription = null, size = 14.dp, tint = c.textMuted)
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 12.sp, color = c.textMuted)
    }
}

@Composable
private fun WeatherMetricIcon(
    icon: ImageVector,
    contentDescription: String?,
    size: androidx.compose.ui.unit.Dp,
    tint: Color
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = Modifier.size(size),
        tint = tint
    )
}
