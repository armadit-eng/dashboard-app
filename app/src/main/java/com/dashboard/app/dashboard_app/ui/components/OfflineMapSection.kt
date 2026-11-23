package com.dashboard.app.dashboard_app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dashboard.app.dashboard_app.model.GeoPointX
import com.dashboard.app.dashboard_app.service.TileCacheService
import com.dashboard.app.dashboard_app.ui.theme.LocalCockpitColors
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun OfflineMapSection(
    styleId: String,
    urlTemplate: String,
    centerProvider: () -> GeoPointX,
    canBulkDownload: Boolean
) {
    val context = LocalContext.current
    val c = LocalCockpitColors.current
    val scope = rememberCoroutineScope()

    var radiusKm by remember { mutableDoubleStateOf(5.0) }
    var offline by remember { mutableStateOf(TileCacheService.offlineOnly) }
    var busy by remember { mutableStateOf(false) }
    var cancelled by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var msg by remember { mutableStateOf("") }
    var statsText by remember { mutableStateOf("Menghitung cache...") }

    suspend fun refreshStats() {
        val s = TileCacheService.stats(context)
        val mb = String.format(Locale.US, "%.1f", s.bytes / (1024.0 * 1024.0))
        statsText = "Cache: ${s.files} tile • $mb MB"
    }

    LaunchedEffect(Unit) {
        refreshStats()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = c.borderSubtle)
        Text("Peta Offline", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(statsText, fontSize = 12.sp, color = c.textMuted)
        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Mode offline (tanpa internet)", fontSize = 14.sp)
                Text("Hanya memakai tile yang sudah tersimpan", fontSize = 12.sp, color = c.textMuted)
            }
            Switch(
                checked = offline,
                onCheckedChange = { v ->
                    TileCacheService.offlineOnly = v
                    offline = v
                }
            )
        }

        Spacer(Modifier.height(8.dp))
        if (canBulkDownload) {
            Text("Unduh area di sekitar pusat peta (zoom 10–16):", fontSize = 12.sp, color = c.textMuted)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3.0, 5.0, 10.0).forEach { r ->
                    FilterChip(
                        selected = radiusKm == r,
                        enabled = !busy,
                        onClick = { radiusKm = r },
                        label = { Text("${r.toInt()} km") }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val center = centerProvider()
                        busy = true
                        cancelled = false
                        done = 0
                        total = 0
                        msg = ""
                        scope.launch {
                            msg = try {
                                val ok = TileCacheService.downloadArea(
                                    context = context,
                                    styleId = styleId,
                                    urlTemplate = urlTemplate,
                                    lat = center.lat,
                                    lng = center.lng,
                                    radiusKm = radiusKm,
                                    onProgress = { d, t ->
                                        done = d
                                        total = t
                                    },
                                    isCancelled = { cancelled }
                                )
                                if (cancelled) "Dibatalkan ($ok tile tersimpan)." else "Selesai: $ok dari $total tile tersimpan."
                            } catch (e: IllegalStateException) {
                                e.message ?: "Area terlalu besar"
                            } catch (e: Exception) {
                                "Gagal mengunduh: ${e.message}"
                            }
                            busy = false
                            refreshStats()
                        }
                    }
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Unduh area")
                }
                if (busy) {
                    OutlinedButton(onClick = { cancelled = true }) {
                        Text("Batal")
                    }
                }
            }
            if (busy || total > 0) {
                Spacer(Modifier.height(8.dp))
                if (total == 0) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text("$done / $total tile", fontSize = 12.sp, color = c.textMuted)
            }
            if (msg.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(msg, fontSize = 12.sp)
            }
        } else {
            Text(
                "Unduh area hanya tersedia untuk gaya \"Cockpit Gelap\" (CARTO). Gaya lain tetap disimpan otomatis ke cache saat dilihat.",
                fontSize = 12.sp,
                color = c.textMuted
            )
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
            Text("Hapus cache")
        }
        Text("© OpenStreetMap contributors © CARTO", fontSize = 10.sp, color = c.textMuted)
    }
}
