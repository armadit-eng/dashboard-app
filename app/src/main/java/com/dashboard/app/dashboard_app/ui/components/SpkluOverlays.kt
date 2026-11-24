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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.ElectricMoped
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import com.dashboard.app.dashboard_app.model.AppSettings
import com.dashboard.app.dashboard_app.model.DEFAULT_MOLIS_MAPS_LIST_URL
import com.dashboard.app.dashboard_app.model.GeoPointX
import com.dashboard.app.dashboard_app.model.SpkluStation
import com.dashboard.app.dashboard_app.ui.theme.LocalCockpitColors
import java.util.Locale

private enum class SpkluFilter(val label: String) {
    ALL("Semua"),
    MOLIS("Molis / SPLU"),
    GENERAL("SPKLU Mobil"),
    MINE("Koleksi Saya")
}

private val SOCKET_PRESETS = listOf(
    "Schuko 16A (Molis)",
    "SPLU PLN",
    "Swap Baterai / SPBKLU",
    "Type 2 AC",
    "DC Fast CCS2"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SpkluBottomSheet(
    stations: List<SpkluStation>,
    settings: AppSettings,
    loading: Boolean,
    importFeedback: String?,
    onDismissImportFeedback: () -> Unit,
    onRefresh: () -> Unit,
    onSyncFeedUrl: (String) -> Unit,
    onOpenAddDialog: () -> Unit,
    onOpenImportPicker: () -> Unit,
    onSearchInGoogleMaps: () -> Unit,
    onUpdateSettings: (AppSettings) -> Unit,
    onSelectStation: (SpkluStation) -> Unit,
    onDeleteUserStation: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val c = LocalCockpitColors.current
    var filter by remember { mutableStateOf(SpkluFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var showSourcesPanel by remember { mutableStateOf(false) }
    var ocmKeyDraft by remember(settings.ocmApiKey) { mutableStateOf(settings.ocmApiKey) }
    var feedUrlDraft by remember(settings.customSpkluFeedUrl) {
        mutableStateOf(settings.customSpkluFeedUrl.ifBlank { DEFAULT_MOLIS_MAPS_LIST_URL })
    }

    val molisCount = remember(stations) { stations.count { it.isMolisFriendly } }
    val mineCount = remember(stations) { stations.count { it.isUserAdded } }
    val filtered = remember(stations, filter, searchQuery) {
        val byTab = when (filter) {
            SpkluFilter.ALL -> stations
            SpkluFilter.MOLIS -> stations.filter { it.isMolisFriendly }
            SpkluFilter.GENERAL -> stations.filter { !it.isMolisFriendly }
            SpkluFilter.MINE -> stations.filter { it.isUserAdded }
        }
        val q = searchQuery.trim()
        if (q.isEmpty()) byTab else byTab.filter { s ->
            s.name.contains(q, ignoreCase = true) ||
                s.notes.contains(q, ignoreCase = true) ||
                s.operatorName.contains(q, ignoreCase = true) ||
                s.socketType.contains(q, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceCard
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("SPKLU & Charger Molis", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${stations.size} titik • $molisCount ramah molis • $mineCount koleksi Anda",
                        fontSize = 11.5.sp,
                        color = c.textMuted
                    )
                }
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("spklu_refresh_button")
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Muat ulang SPKLU")
                }
            }

            Spacer(Modifier.height(6.dp))

            // Easy quick action buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpenAddDialog,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("spklu_add_button")
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Tambah", fontSize = 12.5.sp)
                }
                OutlinedButton(
                    onClick = onOpenImportPicker,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("spklu_import_button")
                ) {
                    Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Impor KML/GPX/CSV", fontSize = 12.5.sp)
                }
                OutlinedButton(
                    onClick = onSearchInGoogleMaps,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("spklu_gmaps_search_button")
                ) {
                    Icon(Icons.Rounded.TravelExplore, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Cari di Maps", fontSize = 12.5.sp)
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "Daftar Molis Komunitas (PEVR/Indonesia) aktif • Anda juga bisa menempel link Google Maps List (maps.app.goo.gl) atau impor file KML/GPX/CSV.",
                fontSize = 11.sp,
                color = c.textMuted
            )

            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Cari kota, nama lokasi, PLN, Polytron, SPLU...", fontSize = 12.5.sp) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = "Hapus filter", modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("spklu_search_input")
            )

            if (!importFeedback.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = c.accent.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, c.accent.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDismissImportFeedback() }
                ) {
                    Text(
                        importFeedback,
                        fontSize = 12.sp,
                        color = c.accent,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SpkluFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(f.label, fontSize = 12.sp) },
                        modifier = Modifier.testTag("spklu_filter_${f.name.lowercase()}")
                    )
                }
            }

            // Collapsible Data Sources & API Keys
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = c.surfaceElevated,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showSourcesPanel = !showSourcesPanel },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Sumber Data: Google Maps Molis • OSM • OpenChargeMap",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (showSourcesPanel) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = "Buka pengaturan sumber data"
                        )
                    }
                    if (showSourcesPanel) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Daftar Molis Google Maps", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Sinkron otomatis titik charger motor listrik komunitas",
                                    fontSize = 11.sp,
                                    color = c.textMuted
                                )
                            }
                            Switch(
                                checked = settings.molisCommunityEnabled,
                                onCheckedChange = { enabled ->
                                    onUpdateSettings(settings.copy(molisCommunityEnabled = enabled))
                                    onRefresh()
                                },
                                modifier = Modifier.testTag("toggle_molis_community_switch")
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = feedUrlDraft,
                            onValueChange = { feedUrlDraft = it },
                            label = { Text("Link Google Maps List / My Maps / JSON") },
                            placeholder = { Text(DEFAULT_MOLIS_MAPS_LIST_URL) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("custom_spklu_feed_input")
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onSyncFeedUrl(feedUrlDraft) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("sync_spklu_feed_button")
                            ) {
                                Icon(Icons.Rounded.CloudSync, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Sinkron Link Maps", fontSize = 12.5.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    feedUrlDraft = DEFAULT_MOLIS_MAPS_LIST_URL
                                    onSyncFeedUrl(DEFAULT_MOLIS_MAPS_LIST_URL)
                                },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("reset_spklu_feed_button")
                            ) {
                                Text("Default", fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = ocmKeyDraft,
                                onValueChange = { ocmKeyDraft = it },
                                label = { Text("OpenChargeMap API Key") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("ocm_api_key_input")
                            )
                            Button(
                                onClick = {
                                    onUpdateSettings(
                                        settings.copy(
                                            ocmApiKey = ocmKeyDraft.trim(),
                                            customSpkluFeedUrl = feedUrlDraft.trim().ifBlank { DEFAULT_MOLIS_MAPS_LIST_URL }
                                        )
                                    )
                                    onRefresh()
                                },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("save_ocm_key_button")
                            ) {
                                Text("Muat")
                            }
                        }
                    }
                }
            }

            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(4.dp))

            if (filtered.isEmpty()) {
                Text(
                    when {
                        searchQuery.isNotBlank() -> "Tidak ada titik yang cocok dengan \"${searchQuery.trim()}\"."
                        filter == SpkluFilter.MOLIS -> "Belum ada titik khusus Molis / SPLU. Aktifkan Daftar Komunitas atau tambahkan manual."
                        filter == SpkluFilter.MINE -> "Belum ada koleksi SPKLU pribadi atau hasil impor."
                        else -> "Belum ada titik ditemukan."
                    },
                    color = c.textMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 20.dp)
                )
            } else {
                if (filtered.size > 50) {
                    Text(
                        "Menampilkan 50 terdekat dari ${filtered.size} titik • pakai pencarian untuk kota lain",
                        fontSize = 11.sp,
                        color = c.textMuted,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered.take(50), key = { it.id }) { s ->
                        SpkluStationCard(
                            station = s,
                            onClick = { onSelectStation(s) },
                            onDelete = if (s.isUserAdded) ({ onDeleteUserStation(s.id) }) else null
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpkluStationCard(
    station: SpkluStation,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?
) {
    val c = LocalCockpitColors.current
    val badgeColor = if (station.isMolisFriendly) c.accent else c.regen
    val srcLabel = station.sourceLabel.ifBlank { if (station.isUserAdded) "Pribadi" else "OSM" }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = c.surfaceElevated,
        border = BorderStroke(
            1.dp,
            if (station.isMolisFriendly) c.accent.copy(alpha = 0.35f) else c.borderSubtle
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(badgeColor.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (station.isMolisFriendly) Icons.Rounded.ElectricMoped else Icons.Rounded.EvStation,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        station.name,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = badgeColor.copy(alpha = 0.18f)
                    ) {
                        Text(
                            if (station.isMolisFriendly) "MOLIS" else "SPKLU",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = badgeColor,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = c.borderSubtle
                    ) {
                        Text(
                            srcLabel,
                            fontSize = 9.5.sp,
                            color = c.textMuted,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
                val powerPart = station.powerKw?.let { " • ${String.format(Locale.US, "%.1f", it)} kW" }.orEmpty()
                Text(
                    "${station.operatorName} • ${station.socketType}$powerPart",
                    fontSize = 12.sp,
                    color = c.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (station.notes.isNotBlank()) {
                    Text(
                        station.notes,
                        fontSize = 11.sp,
                        color = c.accentSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (station.distanceKm != null) {
                    Text(
                        String.format(Locale.US, "%.1f km", station.distanceKm),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = c.accent
                    )
                }
                if (onDelete != null) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("delete_user_spklu_${station.id}")
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Hapus SPKLU", tint = c.danger)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddSpkluDialog(
    initialName: String = "",
    targetPoint: GeoPointX,
    onSave: (name: String, socket: String, lat: Double, lng: Double, powerKw: Double?, notes: String, isMolis: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val c = LocalCockpitColors.current
    var name by remember(initialName) { mutableStateOf(initialName) }
    var socket by remember { mutableStateOf(SOCKET_PRESETS.first()) }
    var powerStr by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var isMolis by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceCard,
        title = { Text("Simpan Titik Charger / SPKLU") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nama Lokasi (mis. Warung Pak Budi / SPLU)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_spklu_name_input")
                )
                Text("Pilih Tipe Soket", fontSize = 12.sp, color = c.textMuted)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SOCKET_PRESETS.forEach { preset ->
                        FilterChip(
                            selected = socket == preset,
                            onClick = {
                                socket = preset
                                isMolis = preset.contains("Molis", true) ||
                                    preset.contains("SPLU", true) ||
                                    preset.contains("Swap", true)
                            },
                            label = { Text(preset, fontSize = 11.sp) }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = socket,
                        onValueChange = { socket = it },
                        label = { Text("Jenis Soket") },
                        singleLine = true,
                        modifier = Modifier.weight(1.6f)
                    )
                    OutlinedTextField(
                        value = powerStr,
                        onValueChange = { powerStr = it },
                        label = { Text("kW") },
                        placeholder = { Text("2.2") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(0.8f)
                    )
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Catatan (jam buka, biaya, parkir, aman hujan)") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isMolis = !isMolis }
                ) {
                    Checkbox(checked = isMolis, onCheckedChange = { isMolis = it })
                    Text("Ramah Motor Listrik (Schuko / SPLU / Swap)", fontSize = 12.sp)
                }
                Text(
                    String.format(Locale.US, "Koordinat: %.5f, %.5f", targetPoint.lat, targetPoint.lng),
                    fontSize = 11.sp,
                    color = c.textMuted
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanName = name.trim().ifBlank { "Charger Molis" }
                    val kw = powerStr.replace(",", ".").toDoubleOrNull()?.takeIf { it > 0.0 }
                    onSave(cleanName, socket.trim().ifBlank { "Schuko 16A" }, targetPoint.lat, targetPoint.lng, kw, notes.trim(), isMolis)
                    onDismiss()
                },
                modifier = Modifier
                    .height(48.dp)
                    .testTag("confirm_add_spklu_button")
            ) {
                Text("Simpan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.height(48.dp)) {
                Text("Batal")
            }
        }
    )
}

@Composable
fun SpkluDetailDialog(
    station: SpkluStation,
    hasActiveRoute: Boolean,
    onRouteDirect: () -> Unit,
    onAddAsStop: () -> Unit,
    onOpenExternalMaps: () -> Unit,
    onDismiss: () -> Unit
) {
    val c = LocalCockpitColors.current
    val badgeColor = if (station.isMolisFriendly) c.accent else c.regen
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceCard,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (station.isMolisFriendly) Icons.Rounded.ElectricMoped else Icons.Rounded.EvStation,
                    contentDescription = null,
                    tint = badgeColor
                )
                Text(station.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(shape = RoundedCornerShape(6.dp), color = badgeColor.copy(alpha = 0.18f)) {
                        Text(
                            if (station.isMolisFriendly) "Ramah Molis / SPLU" else "SPKLU Umum",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = badgeColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    if (station.sourceLabel.isNotBlank()) {
                        Surface(shape = RoundedCornerShape(6.dp), color = c.borderSubtle) {
                            Text(
                                "Sumber: ${station.sourceLabel}",
                                fontSize = 11.sp,
                                color = c.textMuted,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text("Operator: ${station.operatorName}", fontSize = 13.sp)
                Text(
                    "Soket: ${station.socketType}" +
                        (station.powerKw?.let { " (${String.format(Locale.US, "%.1f", it)} kW)" } ?: ""),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                station.distanceKm?.let {
                    Text(
                        "Jarak garis lurus: ${String.format(Locale.US, "%.1f", it)} km",
                        fontSize = 12.5.sp,
                        color = c.accent
                    )
                }
                if (station.notes.isNotBlank()) {
                    HorizontalDivider(color = c.borderSubtle)
                    Text(station.notes, fontSize = 12.5.sp, color = c.accentSecondary)
                }
                Text(
                    String.format(Locale.US, "%.5f, %.5f", station.lat, station.lng),
                    fontSize = 11.sp,
                    color = c.textMuted
                )
                Spacer(Modifier.height(4.dp))
                if (hasActiveRoute) {
                    OutlinedButton(
                        onClick = onAddAsStop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("spklu_dialog_add_stop_button")
                    ) {
                        Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Sisipkan ke Rute Aktif")
                    }
                }
                OutlinedButton(
                    onClick = onOpenExternalMaps,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("spklu_dialog_open_maps_button")
                ) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Lihat Ulasan / Foto di Maps")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onRouteDirect,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("spklu_dialog_route_button")
            ) {
                Icon(Icons.Rounded.Directions, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Rute ke Sini")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.height(48.dp)) {
                Text("Tutup")
            }
        }
    )
}
