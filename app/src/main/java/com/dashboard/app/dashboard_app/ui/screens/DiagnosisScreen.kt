package com.dashboard.app.dashboard_app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dashboard.app.dashboard_app.model.Battery2Data
import com.dashboard.app.dashboard_app.model.EffectiveTelemetry
import com.dashboard.app.dashboard_app.model.JkBmsData
import com.dashboard.app.dashboard_app.ui.components.ChartSeries
import com.dashboard.app.dashboard_app.ui.components.CockpitCard
import com.dashboard.app.dashboard_app.ui.components.MultiSeriesChart
import com.dashboard.app.dashboard_app.ui.theme.LocalCockpitColors
import com.dashboard.app.dashboard_app.viewmodel.DashboardUiState
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun DiagnosisScreen(
    state: DashboardUiState,
    onRestoreOdo: (Double) -> Unit,
    onSetInvertB2Current: (Boolean) -> Unit,
    onSendEsp2Command: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalCockpitColors.current
    var tabIndex by remember { mutableIntStateOf(0) }
    var batTab by remember { mutableIntStateOf(0) }
    var sortLowFirst by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        PrimaryTabRow(
            selectedTabIndex = tabIndex,
            containerColor = c.surfaceCard,
            contentColor = c.accent
        ) {
            Tab(
                selected = tabIndex == 0,
                onClick = { tabIndex = 0 },
                text = { Text("Motor & DTC") },
                modifier = Modifier.testTag("diag_tab_motor")
            )
            Tab(
                selected = tabIndex == 1,
                onClick = { tabIndex = 1 },
                text = { Text("Dual Baterai") },
                modifier = Modifier.testTag("diag_tab_battery")
            )
            Tab(
                selected = tabIndex == 2,
                onClick = { tabIndex = 2 },
                text = { Text("Grafik Live") },
                modifier = Modifier.testTag("diag_tab_charts")
            )
        }

        when (tabIndex) {
            0 -> MotorDiagnosisTab(state, onRestoreOdo)
            1 -> DualBatteryTab(
                state = state,
                batTab = batTab,
                onBatTabChange = { batTab = it },
                sortLowFirst = sortLowFirst,
                onToggleSort = { sortLowFirst = !sortLowFirst },
                onSetInvertB2Current = onSetInvertB2Current,
                onSendEsp2Command = onSendEsp2Command
            )
            else -> LiveChartsTab(state)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MotorDiagnosisTab(
    state: DashboardUiState,
    onRestoreOdo: (Double) -> Unit
) {
    val c = LocalCockpitColors.current
    val eff = state.effective
    val m = state.motorData
    val esp1 = state.esp1Data

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            CockpitCard {
                Text("THROTTLE & KONTROL", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Bukaan Gas", color = c.textMuted, fontSize = 12.sp)
                        Text(
                            "${eff.throttlePct?.roundToInt()?.toString() ?: "—"}%",
                            style = MaterialTheme.typography.displayMedium,
                            color = c.accent
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("RPM Estimasi", color = c.textMuted, fontSize = 12.sp)
                        Text(
                            "${eff.rpm ?: "—"}",
                            style = MaterialTheme.typography.displayMedium
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Mode Berkendara", color = c.textMuted, fontSize = 12.sp)
                        Text(
                            eff.modeLabel,
                            style = MaterialTheme.typography.headlineSmall,
                            color = c.accentSecondary
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { ((eff.throttlePct ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    color = c.accent,
                    trackColor = c.surfaceElevated
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusChip("Park: ${if (eff.isPark == true) "YA" else "TIDAK"}", eff.isPark == true, c.warning)
                    StatusChip("Standar: ${if (esp1.sideStandDown) "TURUN" else "NAIK"}", esp1.sideStandDown, c.danger)
                    StatusChip("Cruise: ${if (esp1.cruiseActive) "ON" else "OFF"}", esp1.cruiseActive, c.accent)
                    StatusChip("Mundur: ${if (esp1.reverseActive) "ON" else "OFF"}", esp1.reverseActive, c.accentSecondary)
                }
            }
        }

        item {
            CockpitCard {
                Text("TEMPERATUR & ODOMETER", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TempCard("Inverter", m.tempInverterC, Modifier.weight(1f))
                    TempCard("Motor BLDC", m.tempMotorC, Modifier.weight(1f))
                    TempCard("Baterai 1", eff.b1TempC, Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Odometer Motor: ${eff.odoKm?.let { String.format(Locale.US, "%.1f", it) } ?: "—"} km",
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Backup lokal: ${String.format(Locale.US, "%.1f", state.settings.odoBackupKm)} km",
                            fontSize = 12.sp,
                            color = c.textMuted
                        )
                    }
                    OutlinedButton(
                        onClick = { onRestoreOdo(state.settings.odoBackupKm) },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("sync_odo_button")
                    ) {
                        Text("Sinkron AT+ODO")
                    }
                }
            }
        }

        item {
            CockpitCard {
                Text("DTC / FAULT ANALYZER", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
                Spacer(Modifier.height(10.dp))
                if (eff.activeErrors.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = c.accent)
                        Spacer(Modifier.width(10.dp))
                        Text("Tidak ada kode fault motor (errMask = ${m.errMask}). Sistem normal.")
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        eff.activeErrors.forEach { err ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = c.danger.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, c.danger.copy(alpha = 0.5f))
                            ) {
                                Row(modifier = Modifier.padding(10.dp)) {
                                    Icon(Icons.Rounded.Warning, contentDescription = null, tint = c.danger)
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text("Bit ${err.bit}: ${err.label}", fontWeight = FontWeight.Bold, color = c.danger)
                                        Text(err.fix, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DualBatteryTab(
    state: DashboardUiState,
    batTab: Int,
    onBatTabChange: (Int) -> Unit,
    sortLowFirst: Boolean,
    onToggleSort: () -> Unit,
    onSetInvertB2Current: (Boolean) -> Unit,
    onSendEsp2Command: (String) -> Unit
) {
    val c = LocalCockpitColors.current
    val jk = state.jkData
    val b2 = state.battery2Data
    val eff = state.effective

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = batTab == 0,
                    onClick = { onBatTabChange(0) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    modifier = Modifier.testTag("bat_segment_1")
                ) {
                    Text("Baterai 1 (JK/MID)")
                }
                SegmentedButton(
                    selected = batTab == 1,
                    onClick = { onBatTabChange(1) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    modifier = Modifier.testTag("bat_segment_2")
                ) {
                    Text("Baterai 2 (ESP32 #2)")
                }
            }
        }

        if (batTab == 0) {
            item {
                CockpitCard {
                    Text("RINGKASAN BATERAI 1", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
                    Spacer(Modifier.height(10.dp))
                    BmsGrid(
                        listOf(
                            "SOC" to "${eff.b1SocPct?.roundToInt()?.toString() ?: "—"}%",
                            "SOH" to "${eff.b1SohPct?.roundToInt()?.toString() ?: "—"}%",
                            "Tegangan" to "${eff.b1Vbat?.let { String.format(Locale.US, "%.2f", it) } ?: "—"} V",
                            "Arus" to "${eff.b1Ibat?.let { String.format(Locale.US, "%.1f", it) } ?: "—"} A",
                            "Delta Sel" to "${jk.deltaCellV?.let { String.format(Locale.US, "%.3f", it) } ?: "—"} V",
                            "Siklus" to "${jk.cycles ?: state.motorData.cbatCycles ?: "—"}",
                            "T1 / T2" to "${jk.temp1C?.let { String.format(Locale.US, "%.1f", it) } ?: "—"} / ${jk.temp2C?.let { String.format(Locale.US, "%.1f", it) } ?: "—"} °C",
                            "MOSFET" to "${jk.tempMosC?.let { String.format(Locale.US, "%.1f", it) } ?: "—"} °C"
                        )
                    )
                }
            }

            if (jk.cellVoltages.isNotEmpty()) {
                item {
                    CellVoltagesCard(
                        title = "TEGANGAN SEL JK-BMS (${jk.cellCount}S)",
                        cells = jk.cellVoltages,
                        sortLowFirst = sortLowFirst,
                        onToggleSort = onToggleSort
                    )
                }
            }
        } else {
            item {
                CockpitCard {
                    Text("RINGKASAN BATERAI 2 (ESP32 CAN / SIM)", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
                    Spacer(Modifier.height(10.dp))
                    BmsGrid(
                        listOf(
                            "SOC" to "${b2.socPct?.roundToInt()?.toString() ?: "—"}%",
                            "SOH" to "${b2.sohPct?.roundToInt()?.toString() ?: "—"}%",
                            "Tegangan" to "${b2.vbat?.let { String.format(Locale.US, "%.2f", it) } ?: "—"} V",
                            "Arus" to "${b2.ibat?.let { String.format(Locale.US, "%.1f", it) } ?: "—"} A",
                            "Delta Sel" to "${b2.deltaCellV?.let { String.format(Locale.US, "%.3f", it) } ?: "—"} V",
                            "Suhu" to "${b2.tempC?.let { String.format(Locale.US, "%.1f", it) } ?: "—"} °C",
                            "Mode CAN" to b2.canMode,
                            "Simulasi" to (b2.simMode ?: "OFF")
                        )
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Balik Polaritas Arus (invertB2Current)", fontSize = 14.sp)
                            Text("Aktifkan jika tanda +/- arus CAN terbalik", fontSize = 12.sp, color = c.textMuted)
                        }
                        Switch(
                            checked = state.settings.invertB2Current,
                            onCheckedChange = onSetInvertB2Current,
                            modifier = Modifier.testTag("invert_b2_current_switch")
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = { onSendEsp2Command("CAN_LISTEN") }) { Text("CAN Listen") }
                        Button(onClick = { onSendEsp2Command("CAN_POLL") }) { Text("CAN Poll") }
                        Button(onClick = { onSendEsp2Command("SIM_CHARGE") }) { Text("Sim Charge") }
                        Button(onClick = { onSendEsp2Command("SIM_DISCHARGE") }) { Text("Sim Discharge") }
                        OutlinedButton(onClick = { onSendEsp2Command("SIM_OFF") }) { Text("Sim OFF") }
                    }
                }
            }

            if (b2.cellVoltages.isNotEmpty()) {
                item {
                    CellVoltagesCard(
                        title = "TEGANGAN SEL BATERAI 2 (${b2.cellCount}S)",
                        cells = b2.cellVoltages,
                        sortLowFirst = sortLowFirst,
                        onToggleSort = onToggleSort
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveChartsTab(state: DashboardUiState) {
    val c = LocalCockpitColors.current
    val pts = state.liveChartPoints

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (pts.size < 2) {
            item {
                CockpitCard {
                    Text(
                        "Belum cukup titik sampel live.\nHubungkan perangkat atau aktifkan Mode Simulasi Dev di Kokpit.",
                        color = c.textMuted
                    )
                }
            }
        } else {
            item {
                CockpitCard {
                    Text("KECEPATAN (km/h) & DAYA (W/100)", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
                    Spacer(Modifier.height(10.dp))
                    MultiSeriesChart(
                        series = listOf(
                            ChartSeries("Speed", pts.map { it.speedKmh }, c.accent, fixedMin = 0.0, fixedMax = 100.0),
                            ChartSeries("Power/100", pts.map { it.powerW / 100.0 }, c.warning)
                        ),
                        gridColor = c.borderSubtle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(175.dp)
                    )
                }
            }
            item {
                CockpitCard {
                    Text("TEGANGAN (V) & SUHU (°C)", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
                    Spacer(Modifier.height(10.dp))
                    MultiSeriesChart(
                        series = listOf(
                            ChartSeries("Volt", pts.map { it.voltageV }, c.accentSecondary),
                            ChartSeries("Suhu", pts.map { it.tempC }, c.danger)
                        ),
                        gridColor = c.borderSubtle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(175.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CellVoltagesCard(
    title: String,
    cells: List<Double>,
    sortLowFirst: Boolean,
    onToggleSort: () -> Unit
) {
    val c = LocalCockpitColors.current
    val minV = cells.min()
    val maxV = cells.max()
    val indexed = cells.mapIndexed { idx, v -> (idx + 1) to v }
    val ordered = if (sortLowFirst) indexed.sortedBy { it.second } else indexed

    CockpitCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = c.textMuted, modifier = Modifier.weight(1f))
            TextButton(onClick = onToggleSort) {
                Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (sortLowFirst) "Urut Nomor" else "Urutkan Min")
            }
        }
        Spacer(Modifier.height(8.dp))
        ordered.chunked(4).forEach { rowCells ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rowCells.forEach { (cellNum, v) ->
                    val isMin = v <= minV + 0.0005
                    val isMax = v >= maxV - 0.0005
                    val col = when {
                        isMin -> c.danger
                        isMax -> c.accent
                        else -> Color.Unspecified
                    }
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        color = c.surfaceElevated,
                        border = BorderStroke(
                            1.dp,
                            when {
                                isMin -> c.danger
                                isMax -> c.accent
                                else -> c.borderSubtle
                            }
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("C$cellNum", fontSize = 10.sp, color = c.textMuted)
                            Text(
                                String.format(Locale.US, "%.3fV", v),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = col
                            )
                        }
                    }
                }
                repeat(4 - rowCells.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, active: Boolean, color: Color) {
    val c = LocalCockpitColors.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (active) color.copy(alpha = 0.16f) else c.surfaceElevated,
        border = BorderStroke(1.dp, if (active) color else c.borderSubtle)
    ) {
        Text(
            label,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (active) color else Color.Unspecified,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun TempCard(label: String, tempC: Double?, modifier: Modifier = Modifier) {
    val c = LocalCockpitColors.current
    val hot = (tempC ?: 0.0) > 60.0
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = c.surfaceElevated
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(label, fontSize = 11.sp, color = c.textMuted)
            Text(
                text = "${tempC?.let { String.format(Locale.US, "%.1f", it) } ?: "—"} °C",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = if (hot) c.danger else Color.Unspecified
            )
        }
    }
}

@Composable
private fun BmsGrid(items: List<Pair<String, String>>) {
    val c = LocalCockpitColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pair.forEach { (k, v) ->
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = c.surfaceElevated
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(k, fontSize = 11.sp, color = c.textMuted)
                            Text(v, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
