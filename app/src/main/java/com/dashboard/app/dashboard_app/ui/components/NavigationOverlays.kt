package com.dashboard.app.dashboard_app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dashboard.app.dashboard_app.engine.RoutePhysics
import com.dashboard.app.dashboard_app.model.AppSettings
import com.dashboard.app.dashboard_app.model.GeoPointX
import com.dashboard.app.dashboard_app.model.HillSegment
import com.dashboard.app.dashboard_app.model.NavRouteX
import com.dashboard.app.dashboard_app.model.PlannedStop
import com.dashboard.app.dashboard_app.model.SavedPlace
import com.dashboard.app.dashboard_app.ui.theme.LocalCockpitColors
import com.dashboard.app.dashboard_app.viewmodel.DashboardUiState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class TappedMapPlace(
    val point: GeoPointX,
    val name: String = "Titik Peta",
    val address: String = "",
    val loading: Boolean = true,
    val straightKm: Double? = null,
    val routeKm: Double? = null,
    val routeMin: Double? = null
)

@Composable
fun MapPlacePopupCard(
    place: TappedMapPlace,
    currentSocPct: Double?,
    refWhPerKm: Double,
    totalPackWh: Double,
    hasActiveStops: Boolean,
    onRouteHere: () -> Unit,
    onAddStopHere: () -> Unit,
    onSavePlace: () -> Unit,
    onSharePlace: () -> Unit,
    onOpenInExternalMaps: () -> Unit,
    onOpenCatatNama: () -> Unit,
    onReportToOsm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalCockpitColors.current
    CockpitCard(
        modifier = modifier.fillMaxWidth(),
        borderColor = c.accent.copy(alpha = 0.55f),
        contentPadding = 14.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Rounded.LocationOn,
                contentDescription = null,
                tint = c.danger,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    place.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val addrLine = when {
                    place.address.isNotBlank() -> place.address
                    place.loading -> "Memuat alamat lokasi…"
                    else -> "Koordinat terpilih di peta"
                }
                Text(
                    addrLine,
                    fontSize = 12.sp,
                    color = c.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("dismiss_place_card_button")
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "Tutup popup lokasi", tint = c.textMuted)
            }
        }

        Spacer(Modifier.height(4.dp))
        if (place.routeKm != null && place.routeMin != null) {
            val estWh = place.routeKm * refWhPerKm
            val arrSoc = if (currentSocPct != null && totalPackWh > 0.0) {
                (currentSocPct - (estWh / totalPackWh) * 100.0).coerceIn(0.0, 100.0)
            } else null
            Text(
                "${place.routeMin.roundToInt()} mnt • ${String.format(Locale.US, "%.1f", place.routeKm)} km" +
                    if (arrSoc != null) " • SOC tiba ±${arrSoc.roundToInt()}%" else "",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = if ((arrSoc ?: 50.0) < 15.0) c.danger else c.accent
            )
        } else if (place.straightKm != null) {
            Text(
                "±${String.format(Locale.US, "%.1f", place.straightKm)} km (garis lurus)" +
                    if (place.loading) " • menghitung rute…" else "",
                fontSize = 12.sp,
                color = c.textMuted
            )
        }

        Text(
            String.format(Locale.US, "%.5f, %.5f", place.point.lat, place.point.lng),
            fontSize = 11.sp,
            color = c.textMuted
        )

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onRouteHere,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("place_route_button")
            ) {
                Icon(Icons.Rounded.Directions, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Rute", maxLines = 1)
            }
            OutlinedButton(
                onClick = onSavePlace,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("place_save_button")
            ) {
                Icon(Icons.Rounded.StarBorder, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Simpan", maxLines = 1)
            }
        }
        if (hasActiveStops) {
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = onAddStopHere,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("place_add_stop_button")
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Tambahkan sebagai Stop Rute")
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onSharePlace,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("place_share_button")
            ) {
                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("Bagikan", fontSize = 12.5.sp, maxLines = 1)
            }
            OutlinedButton(
                onClick = onOpenInExternalMaps,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("place_open_in_button")
            ) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("Buka di…", fontSize = 12.5.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onOpenCatatNama,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("place_catat_button")
            ) {
                Icon(Icons.Rounded.EditNote, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("Catat nama", fontSize = 12.5.sp, maxLines = 1)
            }
            OutlinedButton(
                onClick = onReportToOsm,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("place_osm_button")
            ) {
                Icon(Icons.Rounded.Public, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("Lapor OSM", fontSize = 12.5.sp, maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoutePreviewSheetCard(
    state: DashboardUiState,
    route: NavRouteX,
    destLabel: String,
    isFavorite: Boolean,
    detailsExpanded: Boolean,
    scrubSampleIndex: Int?,
    selectedHill: HillSegment?,
    showAllHills: Boolean,
    onToggleFavorite: () -> Unit,
    onToggleDetails: () -> Unit,
    onClosePreview: () -> Unit,
    onSelectAlternative: (Int) -> Unit,
    onScrubSample: (Int?) -> Unit,
    onSelectHill: (HillSegment) -> Unit,
    onToggleShowAllHills: () -> Unit,
    onStartOrPrepareNav: () -> Unit,
    onStopNavigation: () -> Unit,
    onOpenMultiStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalCockpitColors.current
    val est = route.energyEstimate
    val scrollState = rememberScrollState()

    CockpitCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = 14.dp
    ) {
        // Drag handle indicator
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(c.borderSubtle)
            )
        }

        // Top destination row + Favorit + Detail toggle + Close X
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = c.accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    destLabel,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${String.format(Locale.US, "%.1f", state.remainingDistKm)} km • ${state.etaMin.roundToInt()} mnt",
                    fontSize = 12.5.sp,
                    color = c.textMuted
                )
            }
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("route_preview_fav_button")
            ) {
                Icon(
                    if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = "Simpan Favorit",
                    tint = if (isFavorite) Color(0xFFFACC15) else c.textMuted
                )
            }
            TextButton(
                onClick = onToggleDetails,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("toggle_preview_detail_button")
            ) {
                Text("Detail", fontSize = 12.5.sp)
                Icon(
                    if (detailsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(
                onClick = onClosePreview,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("close_route_preview_button")
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "Tutup Preview")
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = if (detailsExpanded) 340.dp else 185.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Distance & ETA metric tiles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    color = c.surfaceElevated,
                    border = BorderStroke(1.dp, c.borderSubtle)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "${String.format(Locale.US, "%.1f", route.distanceM / 1000.0)} km",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = c.accentSecondary
                        )
                        Text("JARAK", fontSize = 10.sp, color = c.textMuted)
                    }
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    color = c.surfaceElevated,
                    border = BorderStroke(1.dp, c.borderSubtle)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "${(route.durationSec / 60.0).roundToInt()} mnt",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = c.accentSecondary
                        )
                        Text("WAKTU", fontSize = 10.sp, color = c.textMuted)
                    }
                }
            }

            // Alternative Route Chips
            if (route.alternativeOptions.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    route.alternativeOptions.forEachIndexed { idx, alt ->
                        val sel = idx == route.selectedAlternativeIndex
                        val altSoc = alt.energyEstimate?.socAtArrivalPct
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (sel) c.accent.copy(alpha = 0.18f) else c.surfaceElevated,
                            border = BorderStroke(1.dp, if (sel) c.accent else c.borderSubtle),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelectAlternative(idx) }
                                .testTag("route_alt_chip_$idx")
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(
                                    "Rute ${idx + 1} • ${(alt.durationSec / 60.0).roundToInt()} mnt",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.5.sp,
                                    color = if (sel) c.accent else Color.White
                                )
                                Text(
                                    "${String.format(Locale.US, "%.1f", alt.distanceM / 1000.0)} km" +
                                        if (altSoc != null) " • Tiba ${altSoc.roundToInt()}%" else "",
                                    fontSize = 11.sp,
                                    color = c.textMuted
                                )
                            }
                        }
                    }
                }
            }

            // Energy Prediction Strip
            if (est != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = c.surfaceElevated,
                    border = BorderStroke(1.dp, c.borderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Energi ${est.netWh.roundToInt()} Wh (${String.format(Locale.US, "%.0f", est.whPerKm)} Wh/km)" +
                            " • Tiba SOC ${est.socAtArrivalPct?.roundToInt()?.toString() ?: "—"}%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if ((est.socAtArrivalPct ?: 50.0) < 15.0) c.danger else c.accent,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }

            // Destination Weather Strip
            route.destWeather?.let { dw ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = c.surfaceElevated,
                    border = BorderStroke(1.dp, c.borderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val rainPart = dw.rainProbAtArrivalPct?.let { " • Hujan ${it}% saat tiba" } ?: ""
                    Text(
                        "${dw.tempC.roundToInt()}°C ${dw.description} • Angin ${dw.windKmh.roundToInt()} km/h (${dw.windDirLabel})$rainPart",
                        fontSize = 11.5.sp,
                        color = if ((dw.rainProbAtArrivalPct ?: 0) >= 50) c.warning else c.textMuted,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
            }

            // Detailed Interactive Elevation & Hills & Steps
            if (detailsExpanded) {
                if (route.elevationSamples.size >= 2) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = c.surfaceElevated,
                        border = BorderStroke(1.dp, c.borderSubtle),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val scrubbed = scrubSampleIndex?.let { route.elevationSamples.getOrNull(it) }
                            Text(
                                if (scrubbed != null) {
                                    val arrow = if (scrubbed.gradePct >= 0.5) "▲" else if (scrubbed.gradePct <= -0.5) "▼" else "•"
                                    String.format(
                                        Locale.US,
                                        "Km %.1f • %d m • %s %.1f%%",
                                        scrubbed.distanceKm,
                                        scrubbed.elevationM.roundToInt(),
                                        arrow,
                                        scrubbed.gradePct
                                    )
                                } else {
                                    "Geser di grafik untuk lihat ketinggian & kemiringan"
                                },
                                fontSize = 11.5.sp,
                                fontWeight = if (scrubbed != null) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (scrubbed != null) c.accentSecondary else c.textMuted
                            )
                            ElevationProfileChart(
                                samples = route.elevationSamples,
                                trackColor = c.borderSubtle,
                                labelColor = c.textMuted,
                                selectedSampleIndex = scrubSampleIndex,
                                highlightStartDistM = selectedHill?.startDistM,
                                highlightEndDistM = selectedHill?.endDistM,
                                onScrubIndex = onScrubSample,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(100.dp)
                            )
                            if (est != null) {
                                val peakM = route.elevationSamples.maxOf { it.elevationM }.roundToInt()
                                Text(
                                    "Naik ${est.totalAscentM.roundToInt()} m • Turun ${est.totalDescentM.roundToInt()} m • Puncak $peakM m",
                                    fontSize = 11.5.sp,
                                    color = Color.White
                                )
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                GradeLegendDot(Color(0xFFFACC15), "▲ <4%")
                                GradeLegendDot(Color(0xFFF59E0B), "4–7%")
                                GradeLegendDot(Color(0xFFEF4444), ">7%")
                                GradeLegendDot(Color(0xFFC084FC), "▼ <4%")
                                GradeLegendDot(Color(0xFFA855F7), "4–7%")
                                GradeLegendDot(Color(0xFF6366F1), ">7%")
                            }

                            val hills = route.hillSegments
                            if (hills.isEmpty()) {
                                Text(
                                    "Rute relatif datar — tidak ada tanjakan/turunan berarti.",
                                    fontSize = 11.5.sp,
                                    color = c.textMuted
                                )
                            } else {
                                val upCount = hills.count { it.isUphill }
                                val steepest = hills.maxByOrNull { abs(it.avgGradePct) }
                                Text(
                                    "$upCount tanjakan • ${hills.size - upCount} turunan" +
                                        if (steepest != null) {
                                            " • terberat ${abs(steepest.avgGradePct).roundToInt()}% di km ${String.format(Locale.US, "%.1f", steepest.startDistM / 1000.0)}"
                                        } else "",
                                    fontSize = 11.5.sp,
                                    color = c.textMuted
                                )
                                val shownHills = if (showAllHills || hills.size <= 5) {
                                    hills
                                } else {
                                    val topIds = hills.sortedByDescending { abs(it.gainM) }.take(5).map { it.id }.toSet()
                                    hills.filter { it.id in topIds }
                                }
                                shownHills.forEach { h ->
                                    val isSel = selectedHill?.id == h.id
                                    val badgeColor = when {
                                        h.isUphill && abs(h.avgGradePct) >= 7.0 -> Color(0xFFEF4444)
                                        h.isUphill && abs(h.avgGradePct) >= 4.0 -> Color(0xFFF59E0B)
                                        h.isUphill -> Color(0xFFFACC15)
                                        abs(h.avgGradePct) >= 7.0 -> Color(0xFF6366F1)
                                        abs(h.avgGradePct) >= 4.0 -> Color(0xFFA855F7)
                                        else -> Color(0xFFC084FC)
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSel) c.accent.copy(alpha = 0.14f) else c.surfaceCard,
                                        border = BorderStroke(1.dp, if (isSel) c.accent else c.borderSubtle),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable { onSelectHill(h) }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = badgeColor
                                            ) {
                                                Text(
                                                    "${if (h.isUphill) "▲" else "▼"} ${abs(h.avgGradePct).roundToInt()}%",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.Black,
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                                                )
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    "${if (h.isUphill) "Tanjakan" else "Turunan"} ${RoutePhysics.gradeLabel(h.avgGradePct)} • km ${String.format(Locale.US, "%.1f", h.startDistM / 1000.0)}",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Text(
                                                    "${h.lengthM.roundToInt()} m • ${if (h.gainM >= 0) "+" else "−"}${abs(h.gainM).roundToInt()} m • maks ${abs(h.maxGradePct).roundToInt()}%",
                                                    fontSize = 11.sp,
                                                    color = c.textMuted
                                                )
                                            }
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(
                                                    "${if (h.dWh >= 0) "+" else "−"}${abs(h.dWh).roundToInt()} Wh",
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (h.dWh >= 0) c.warning else c.accent
                                                )
                                                h.socPct?.let { socD ->
                                                    Text(
                                                        "SOC ${if (socD >= 0) "+" else "−"}${String.format(Locale.US, "%.1f", abs(socD))}%",
                                                        fontSize = 10.sp,
                                                        color = c.textMuted
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                if (hills.size > 5) {
                                    TextButton(
                                        onClick = onToggleShowAllHills,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            if (showAllHills) "Ringkas (5 terbesar)" else "Lihat semua (${hills.size})"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Route turn-by-turn preview list
                if (route.steps.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = c.surfaceElevated,
                        border = BorderStroke(1.dp, c.borderSubtle),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("LANGKAH RUTE (${route.steps.size})", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
                            route.steps.take(8).forEachIndexed { i, st ->
                                Text(
                                    "${i + 1}. ${st.instruction} (${st.distanceM.roundToInt()} m)",
                                    fontSize = 11.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (route.steps.size > 8) {
                                Text("+ ${route.steps.size - 8} langkah berikutnya…", fontSize = 11.sp, color = c.textMuted)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.isNavigating) {
                OutlinedButton(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("collapse_preview_button"),
                    onClick = onClosePreview
                ) {
                    Text("Tutup Preview")
                }
                Button(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("start_stop_nav_button"),
                    onClick = onStopNavigation
                ) {
                    Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stop Navigasi")
                }
            } else {
                Button(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("start_stop_nav_button"),
                    onClick = onStartOrPrepareNav
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Mulai Navigasi")
                }
                OutlinedButton(
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("open_multistop_button"),
                    onClick = onOpenMultiStop
                ) {
                    Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Rute")
                }
            }
        }
    }
}

@Composable
private fun GradeLegendDot(color: Color, label: String) {
    val c = LocalCockpitColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(3.dp))
        Text(label, fontSize = 10.sp, color = c.textMuted)
    }
}

@Composable
fun TripPrepDialog(
    settings: AppSettings,
    route: NavRouteX,
    currentSocPct: Double?,
    onUpdateSettings: (AppSettings) -> Unit,
    onSkip: () -> Unit,
    onStartWithWeights: (riderKg: Double, loadKg: Double, noAskAgain: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val c = LocalCockpitColors.current
    var riderStr by remember { mutableStateOf(settings.riderWeightKg.roundToInt().toString()) }
    var loadStr by remember { mutableStateOf(settings.loadWeightKg.roundToInt().toString()) }
    var noAsk by remember { mutableStateOf(false) }

    val riderKg = riderStr.toDoubleOrNull()?.coerceIn(25.0, 220.0) ?: settings.riderWeightKg
    val loadKg = loadStr.toDoubleOrNull()?.coerceIn(0.0, 150.0) ?: settings.loadWeightKg
    val totalKg = (settings.bikeWeightKg + riderKg + loadKg).roundToInt()

    val totalPackWh = settings.b1NominalV * settings.b1CapacityAh +
        if (settings.savedEsp2Address != null) settings.b2NominalV * settings.b2CapacityAh else 0.0
    val recalc = remember(riderKg, loadKg, route) {
        if (route.elevationSamples.size >= 2) {
            RoutePhysics.buildElevationAndEnergy(
                sampledPoints = route.elevationSamples.map { it.point },
                elevations = route.elevationSamples.map { it.elevationM },
                totalDistanceKm = route.distanceM / 1000.0,
                durationMin = route.durationSec / 60.0,
                bikeKg = settings.bikeWeightKg,
                riderKg = riderKg,
                loadKg = loadKg,
                calFactor = settings.routeCalFactor,
                totalPackWh = totalPackWh,
                currentSocPct = currentSocPct
            ).estimate
        } else route.energyEstimate
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Siapkan Perjalanan") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Berat memengaruhi perkiraan SOC saat tiba, terutama di rute menanjak.",
                    fontSize = 12.5.sp,
                    color = c.textMuted
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = riderStr,
                        onValueChange = { riderStr = it },
                        label = { Text("Pengendara (kg)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("trip_prep_rider_input")
                    )
                    OutlinedTextField(
                        value = loadStr,
                        onValueChange = { loadStr = it },
                        label = { Text("Bawaan (kg)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("trip_prep_load_input")
                    )
                }
                Text(
                    "Massa total $totalKg kg" +
                        if (recalc?.socAtArrivalPct != null) {
                            " • Perkiraan SOC tiba ${recalc.socAtArrivalPct.roundToInt()}%"
                        } else "",
                    fontWeight = FontWeight.SemiBold,
                    color = if ((recalc?.socAtArrivalPct ?: 50.0) < 15.0) c.danger else c.accent
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { noAsk = !noAsk },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = noAsk, onCheckedChange = { noAsk = it })
                    Text("Jangan tanya lagi saat mulai navigasi", fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onStartWithWeights(riderKg, loadKg, noAsk) },
                modifier = Modifier
                    .height(48.dp)
                    .testTag("trip_prep_start_button")
            ) {
                Text("▶ Mulai")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (noAsk) onUpdateSettings(settings.copy(tripPrepAsk = false))
                    onSkip()
                },
                modifier = Modifier.height(48.dp)
            ) {
                Text("Lewati")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesBottomSheet(
    places: List<SavedPlace>,
    onSelectPlace: (SavedPlace) -> Unit,
    onDeletePlace: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val c = LocalCockpitColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceCard
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Text("Lokasi Favorit Tersimpan", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(10.dp))
            if (places.isEmpty()) {
                Text(
                    "Belum ada lokasi favorit. Ketuk peta atau cari tujuan lalu tekan Simpan.",
                    color = c.textMuted,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(places, key = { it.id }) { p ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = c.surfaceElevated,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectPlace(p) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Star, contentDescription = null, tint = Color(0xFFFACC15))
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        String.format(Locale.US, "%s • %.5f, %.5f", p.category, p.lat, p.lng),
                                        fontSize = 11.5.sp,
                                        color = c.textMuted
                                    )
                                }
                                IconButton(
                                    onClick = { onDeletePlace(p.id) },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Hapus favorit", tint = c.danger)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiStopPlannerSheet(
    stops: List<PlannedStop>,
    hasGps: Boolean,
    onAddCurrentLocationStop: () -> Unit,
    onPickOnMap: () -> Unit,
    onMoveStop: (Int, Int) -> Unit,
    onRemoveStopAt: (Int) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val c = LocalCockpitColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceCard
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text("Trip Planner & Multi-Stop", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onPickOnMap()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("planner_pick_on_map_button")
                ) {
                    Icon(Icons.Rounded.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Pilih di Peta")
                }
                if (hasGps) {
                    OutlinedButton(
                        onClick = onAddCurrentLocationStop,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(Icons.Rounded.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Lokasi Saya")
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (stops.isEmpty()) {
                Text(
                    "Belum ada titik tujuan. Pilih di peta atau cari alamat.",
                    color = c.textMuted,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(stops) { idx, st ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(if (st.isSpklu) c.regen else c.accent),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (st.isSpklu) "⚡" else "${idx + 1}",
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(st.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(
                                enabled = idx > 0,
                                onClick = { onMoveStop(idx, max(0, idx - 1)) },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Rounded.ArrowUpward, contentDescription = "Naikkan titik")
                            }
                            IconButton(
                                enabled = idx < stops.lastIndex,
                                onClick = { onMoveStop(idx, idx + 1) },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Rounded.ArrowDownward, contentDescription = "Turunkan titik")
                            }
                            IconButton(
                                onClick = { onRemoveStopAt(idx) },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Hapus titik", tint = c.danger)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onClearAll()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("Hapus Semua Rute")
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CatatNamaDialog(
    initialLat: Double,
    initialLng: Double,
    gpsLocation: GeoPointX?,
    onSaveNoteAt: (name: String, category: String, note: String, lat: Double, lng: Double) -> Unit,
    onDismiss: () -> Unit
) {
    val c = LocalCockpitColors.current
    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("Bangunan") }
    var lat by remember { mutableStateOf(initialLat) }
    var lng by remember { mutableStateOf(initialLng) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Catat Nama Lokasi (OSM)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Jalan", "Bangunan", "Kampung", "Charger / SPKLU").forEach { itemCat ->
                        FilterChip(
                            selected = cat == itemCat,
                            onClick = { cat = itemCat },
                            label = { Text(itemCat, fontSize = 11.5.sp) }
                        )
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nama $cat") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("catat_nama_input")
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Catatan (opsional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        String.format(Locale.US, "%.5f, %.5f", lat, lng),
                        fontSize = 11.5.sp,
                        color = c.textMuted,
                        modifier = Modifier.weight(1f)
                    )
                    if (gpsLocation != null) {
                        TextButton(onClick = {
                            lat = gpsLocation.lat
                            lng = gpsLocation.lng
                        }) {
                            Text("Pakai GPSku", fontSize = 11.5.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onSaveNoteAt(name.trim(), cat, note.trim(), lat, lng)
                        onDismiss()
                    }
                },
                modifier = Modifier
                    .height(48.dp)
                    .testTag("save_catat_nama_button")
            ) {
                Text("Simpan Pin")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.height(48.dp)) {
                Text("Batal")
            }
        }
    )
}
