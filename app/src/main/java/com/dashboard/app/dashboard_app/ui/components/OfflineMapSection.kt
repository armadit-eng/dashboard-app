package com.dashboard.app.dashboard_app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dashboard.app.dashboard_app.engine.OfflineRegionMeta
import com.dashboard.app.dashboard_app.model.GeoPointX
import com.dashboard.app.dashboard_app.model.MapStyleOption
import com.dashboard.app.dashboard_app.service.OfflineMapPackageService
import com.dashboard.app.dashboard_app.service.TileCacheService
import com.dashboard.app.dashboard_app.ui.theme.LocalCockpitColors
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OfflineMapSection(
    styleOption: MapStyleOption,
    cartoApiKey: String = "",
    offlineModeOnly: Boolean,
    offlineRegions: List<OfflineRegionMeta>,
    centerProvider: () -> GeoPointX,
    canBulkTileDownload: Boolean,
    onToggleOfflineMode: (Boolean) -> Unit,
    onRegionsChanged: () -> Unit,
    onDeleteRegion: (String) -> Unit,
    onFocusRegion: (GeoPointX) -> Unit
) {
    val context = LocalContext.current
    val c = LocalCockpitColors.current
    val scope = rememberCoroutineScope()

    var radiusKm by remember { mutableDoubleStateOf(5.0) }
    var includeTiles by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var cancelled by remember { mutableStateOf(false) }
    var phaseText by remember { mutableStateOf("") }
    var done by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var msg by remember { mutableStateOf("") }
    var statsText by remember { mutableStateOf("Menghitung cache tile...") }

    suspend fun refreshStats() {
        val s = TileCacheService.stats(context)
        val mb = String.format(Locale.US, "%.1f", s.bytes / (1024.0 * 1024.0))
        statsText = "Cache Tile: ${s.files} tile • $mb MB"
    }

    LaunchedEffect(Unit) {
        refreshStats()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = c.borderSubtle)
        Text("Navigasi & Peta Full Offline", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Unduh jaringan jalan, nama jalan, nama area/POI, dan tile peta agar navigasi & rerute berjalan tanpa internet.",
            fontSize = 12.sp,
            color = c.textMuted
        )
        Spacer(Modifier.height(4.dp))
        Text(statsText, fontSize = 11.5.sp, color = c.textMuted)
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Mode Full Offline (Tanpa Internet)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Rute, cari jalan/area, rerute & peta memakai data tersimpan di HP",
                    fontSize = 11.5.sp,
                    color = c.textMuted
                )
            }
            Switch(
                checked = offlineModeOnly,
                onCheckedChange = onToggleOfflineMode,
                modifier = Modifier.testTag("offline_mode_switch")
            )
        }

        Spacer(Modifier.height(10.dp))
        Text("Radius paket area di sekitar pusat peta:", fontSize = 12.sp, color = c.textMuted)
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(3.0, 5.0, 8.0, 12.0).forEach { r ->
                FilterChip(
                    selected = radiusKm == r,
                    enabled = !busy,
                    onClick = { radiusKm = r },
                    label = { Text("${r.toInt()} km") }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Sertakan Tile Peta (Zoom 11–16)", fontSize = 13.sp)
                Text(
                    if (styleOption == MapStyleOption.OSM_STANDARD || styleOption == MapStyleOption.SATELLITE)
                        "Otomatis unduh tile Carto Voyager untuk tampilan peta offline"
                    else
                        "Mengunduh tile ${styleOption.label} bersama data jalan",
                    fontSize = 11.sp,
                    color = c.textMuted
                )
            }
            Switch(
                checked = includeTiles,
                enabled = !busy,
                onCheckedChange = { includeTiles = it },
                modifier = Modifier.testTag("offline_include_tiles_switch")
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("download_offline_package_button"),
                onClick = {
                    val center = centerProvider()
                    busy = true
                    cancelled = false
                    phaseText = "Memulai unduhan..."
                    done = 0
                    total = 0
                    msg = ""
                    scope.launch {
                        msg = try {
                            val meta = OfflineMapPackageService.downloadFullOfflinePackage(
                                context = context,
                                centerLat = center.lat,
                                centerLng = center.lng,
                                radiusKm = radiusKm,
                                styleOption = styleOption,
                                cartoApiKey = cartoApiKey,
                                includeTiles = includeTiles,
                                onProgress = { p ->
                                    phaseText = p.phaseLabel
                                    done = p.done
                                    total = p.total
                                },
                                isCancelled = { cancelled }
                            )
                            onRegionsChanged()
                            val mb = String.format(Locale.US, "%.2f", meta.totalBytes / (1024.0 * 1024.0))
                            "Siap offline: ${meta.label} (${meta.edgeCount} ruas jalan, ${meta.placeCount} tempat/jalan, ${meta.tileCount} tile, $mb MB)."
                        } catch (e: Exception) {
                            e.message ?: "Gagal mengunduh paket offline."
                        }
                        busy = false
                        phaseText = ""
                        refreshStats()
                    }
                }
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Unduh Paket Full Offline")
            }
            if (busy) {
                OutlinedButton(
                    onClick = { cancelled = true },
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Batal")
                }
            }
        }

        if (canBulkTileDownload && !busy) {
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("download_tiles_only_button"),
                onClick = {
                    val center = centerProvider()
                    busy = true
                    cancelled = false
                    phaseText = "Mengunduh tile peta..."
                    done = 0
                    total = 0
                    msg = ""
                    scope.launch {
                        msg = try {
                            val ok = TileCacheService.downloadArea(
                                context = context,
                                styleId = styleOption.id,
                                urlTemplate = styleOption.tileUrlTemplate,
                                lat = center.lat,
                                lng = center.lng,
                                radiusKm = radiusKm,
                                cartoApiKey = cartoApiKey,
                                onProgress = { d, t ->
                                    done = d
                                    total = t
                                    phaseText = "Mengunduh tile ($d/$t)..."
                                },
                                isCancelled = { cancelled }
                            )
                            if (cancelled) "Dibatalkan ($ok tile tersimpan)." else "Selesai: $ok dari $total tile tersimpan."
                        } catch (e: Exception) {
                            "Gagal mengunduh tile: ${e.message}"
                        }
                        busy = false
                        phaseText = ""
                        refreshStats()
                    }
                }
            ) {
                Text("Unduh Tile Peta Saja (${styleOption.label})")
            }
        }

        if (busy) {
            Spacer(Modifier.height(8.dp))
            if (phaseText.isNotEmpty()) {
                Text(phaseText, fontSize = 12.sp, color = c.accent)
                Spacer(Modifier.height(4.dp))
            }
            if (total == 0) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (msg.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                msg,
                fontSize = 12.sp,
                color = if (msg.startsWith("Gagal")) c.danger else MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Wilayah Offline Tersimpan (${offlineRegions.size})",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        if (offlineRegions.isEmpty()) {
            Text(
                "Belum ada paket jalan offline. Ketuk \"Unduh Paket Full Offline\" di atas saat masih online.",
                fontSize = 12.sp,
                color = c.textMuted
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                offlineRegions.forEachIndexed { idx, reg ->
                    val mb = String.format(Locale.US, "%.2f", reg.totalBytes / (1024.0 * 1024.0))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onFocusRegion(GeoPointX(reg.centerLat, reg.centerLng)) }
                            .testTag("offline_region_item_$idx"),
                        shape = RoundedCornerShape(12.dp),
                        color = c.surfaceElevated,
                        border = BorderStroke(1.dp, c.borderSubtle)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.OfflinePin,
                                contentDescription = null,
                                tint = Color(0xFF22C55E),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(reg.label, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                                Text(
                                    "${reg.edgeCount} jalan • ${reg.placeCount} nama/POI • ${reg.tileCount} tile • $mb MB",
                                    fontSize = 11.5.sp,
                                    color = c.textMuted
                                )
                            }
                            IconButton(
                                onClick = { onDeleteRegion(reg.id) },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("delete_offline_region_$idx")
                            ) {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = "Hapus wilayah offline",
                                    tint = c.danger
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        TextButton(
            enabled = !busy,
            onClick = {
                scope.launch {
                    TileCacheService.clear(context)
                    refreshStats()
                }
            }
        ) {
            Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Hapus cache tile")
        }
        Text("© OpenStreetMap contributors © CARTO", fontSize = 10.sp, color = c.textMuted)
    }
}
