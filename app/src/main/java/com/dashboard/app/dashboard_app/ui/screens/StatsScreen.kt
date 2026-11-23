package com.dashboard.app.dashboard_app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dashboard.app.dashboard_app.engine.RoutePhysics
import com.dashboard.app.dashboard_app.engine.TelemetryEngine
import com.dashboard.app.dashboard_app.model.TripRecord
import com.dashboard.app.dashboard_app.ui.components.ChartSeries
import com.dashboard.app.dashboard_app.ui.components.CockpitCard
import com.dashboard.app.dashboard_app.ui.components.MultiSeriesChart
import com.dashboard.app.dashboard_app.ui.theme.LocalCockpitColors
import com.dashboard.app.dashboard_app.viewmodel.DashboardUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    state: DashboardUiState,
    onApplyCalFactor: (Double) -> Unit,
    onDeleteTrip: (String) -> Unit,
    onShareText: (text: String, subject: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalCockpitColors.current
    val trips = state.tripHistory
    val totalKm = trips.sumOf { it.distanceKm } + state.liveTrip.distanceKm
    val totalWh = trips.sumOf { it.energyWh } + state.liveTrip.energyWh
    val savings = TelemetryEngine.computeFuelSavings(totalKm, totalWh)
    val goal = state.settings.weeklyGoalKm.coerceAtLeast(10.0)
    val dateFmt = remember { SimpleDateFormat("dd MMM • HH:mm", Locale.getDefault()) }

    var selectedTrip by remember { mutableStateOf<TripRecord?>(null) }

    selectedTrip?.let { t ->
        ModalBottomSheet(
            onDismissRequest = { selectedTrip = null },
            containerColor = c.surfaceCard
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    "${t.startArea.ifEmpty { "Mulai" }} → ${t.endArea.ifEmpty { "Selesai" }}",
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Jarak ${String.format(Locale.US, "%.2f", t.distanceKm)} km • ${t.energyWh.roundToInt()} Wh • ${String.format(Locale.US, "%.1f", t.whPerKm)} Wh/km",
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "Kecepatan maks ${t.maxSpeedKmh.roundToInt()} km/h • Rata-rata ${t.avgSpeedKmh.roundToInt()} km/h",
                    fontSize = 12.5.sp,
                    color = c.textMuted
                )
                if (t.samples.size >= 2) {
                    Spacer(Modifier.height(14.dp))
                    MultiSeriesChart(
                        series = listOf(
                            ChartSeries("Speed", t.samples.map { it.speedKmh }, c.accent),
                            ChartSeries("Power/100", t.samples.map { it.powerW / 100.0 }, c.warning)
                        ),
                        gridColor = c.borderSubtle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        enabled = t.pathPoints.isNotEmpty(),
                        onClick = {
                            onShareText(RoutePhysics.exportTripGpx(t), "Trip GPX ${t.id}")
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("export_trip_gpx_button")
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Ekspor GPX")
                    }
                    OutlinedButton(
                        onClick = {
                            onDeleteTrip(t.id)
                            selectedTrip = null
                        },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("delete_trip_button")
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = c.danger)
                        Spacer(Modifier.width(4.dp))
                        Text("Hapus", color = c.danger)
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Statistik & Penghematan",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f)
                )
                if (trips.isNotEmpty()) {
                    FilledTonalButton(
                        onClick = {
                            onShareText(RoutePhysics.exportTripsCsv(trips), "Riwayat Trip EV (CSV)")
                        },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("export_trips_csv_button")
                    ) {
                        Icon(Icons.Rounded.TableChart, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Ekspor CSV")
                    }
                }
            }
        }

        // Lifetime KPIs
        item {
            CockpitCard {
                Text("RINGKASAN SEUMUR HIDUP", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    KpiCol("Total Jarak", "${String.format(Locale.US, "%.1f", totalKm)} km", c.accent, Modifier.weight(1f))
                    KpiCol("Hemat BBM", "Rp ${savings.netSavingsRp}", c.accentSecondary, Modifier.weight(1f))
                    KpiCol("CO₂ Tersimpan", "${String.format(Locale.US, "%.1f", savings.co2SavedKg)} kg", c.regen, Modifier.weight(1f))
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "Target Mingguan: ${String.format(Locale.US, "%.1f", totalKm)} / ${goal.roundToInt()} km",
                    fontSize = 12.sp,
                    color = c.textMuted
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (totalKm / goal).coerceIn(0.0, 1.0).toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    color = c.accent,
                    trackColor = c.surfaceElevated
                )
            }
        }

        // Rider Badges
        item {
            CockpitCard {
                Text("LENCANA PENGENDARA", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.badges.chunked(2).forEach { rowBadges ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowBadges.forEach { b ->
                                Surface(
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp),
                                    color = c.surfaceElevated,
                                    border = BorderStroke(
                                        1.dp,
                                        if (b.unlocked) c.accent else c.borderSubtle
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Rounded.EmojiEvents,
                                            contentDescription = b.title,
                                            tint = if (b.unlocked) c.accent else c.textMuted,
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text(b.title, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                b.description,
                                                fontSize = 10.5.sp,
                                                color = c.textMuted,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                            if (rowBadges.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Calibration suggestions
        if (state.calibLogs.isNotEmpty()) {
            item {
                CockpitCard {
                    Text("KALIBRASI RUTE OTOMATIS", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
                    Spacer(Modifier.height(8.dp))
                    state.calibLogs.take(3).forEach { cal ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Tune, contentDescription = null, tint = c.accentSecondary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "${String.format(Locale.US, "%.1f", cal.distanceKm)} km: Prediksi ${cal.predictedWh.roundToInt()} Wh vs Aktual ${cal.actualWh.roundToInt()} Wh",
                                    fontSize = 12.5.sp
                                )
                                Text(
                                    "Saran faktor kalibrasi: ${String.format(Locale.US, "%.3f", cal.suggestedFactor)}",
                                    fontSize = 11.5.sp,
                                    color = c.textMuted
                                )
                            }
                            TextButton(
                                onClick = { onApplyCalFactor(cal.suggestedFactor) },
                                modifier = Modifier.testTag("apply_calib_${cal.id}")
                            ) {
                                Text("Terapkan")
                            }
                        }
                    }
                }
            }
        }

        item {
            Text("RIWAYAT PERJALANAN", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
        }

        if (trips.isEmpty()) {
            item {
                CockpitCard {
                    Text(
                        "Belum ada trip tersimpan. Tekan \"Simpan Trip\" di Kokpit setelah berkendara.",
                        color = c.textMuted
                    )
                }
            }
        } else {
            items(trips, key = { it.id }) { t ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { selectedTrip = t }
                        .testTag("trip_item_${t.id}"),
                    shape = RoundedCornerShape(18.dp),
                    color = c.surfaceCard,
                    border = BorderStroke(1.dp, c.borderSubtle)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Route, contentDescription = null, tint = c.accent)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${t.startArea.ifEmpty { "Mulai" }} → ${t.endArea.ifEmpty { "Selesai" }}",
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${dateFmt.format(Date(t.endTimeMs))} • ${String.format(Locale.US, "%.2f", t.distanceKm)} km • ${t.energyWh.roundToInt()} Wh",
                                fontSize = 12.sp,
                                color = c.textMuted
                            )
                        }
                        if (t.ridingScore != null) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = c.accent.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    "Skor ${t.ridingScore}",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = c.accent,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
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
private fun KpiCol(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    val c = LocalCockpitColors.current
    Column(modifier = modifier) {
        Text(label, fontSize = 11.5.sp, color = c.textMuted)
        Spacer(Modifier.height(2.dp))
        Text(value, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = color)
    }
}
