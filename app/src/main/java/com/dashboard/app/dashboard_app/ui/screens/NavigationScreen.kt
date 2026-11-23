package com.dashboard.app.dashboard_app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.GpsOff
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dashboard.app.dashboard_app.MapsNotifListener
import com.dashboard.app.dashboard_app.MirrorRegionOverlay
import com.dashboard.app.dashboard_app.engine.RoutePhysics
import com.dashboard.app.dashboard_app.model.AppSettings
import com.dashboard.app.dashboard_app.model.GeoPointX
import com.dashboard.app.dashboard_app.model.MapStyleOption
import com.dashboard.app.dashboard_app.model.PlannedStop
import com.dashboard.app.dashboard_app.model.SavedPlace
import com.dashboard.app.dashboard_app.model.SpkluStation
import com.dashboard.app.dashboard_app.service.RemoteApisService
import com.dashboard.app.dashboard_app.ui.components.CockpitCard
import com.dashboard.app.dashboard_app.ui.components.ElevationProfileChart
import com.dashboard.app.dashboard_app.ui.components.OfflineMapSection
import com.dashboard.app.dashboard_app.ui.components.SlippyMapView
import com.dashboard.app.dashboard_app.ui.theme.LocalCockpitColors
import com.dashboard.app.dashboard_app.viewmodel.DashboardUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NavigationScreen(
    state: DashboardUiState,
    onSetSingleDestination: (PlannedStop) -> Unit,
    onAddStop: (PlannedStop) -> Unit,
    onRemoveStopAt: (Int) -> Unit,
    onClearRoute: () -> Unit,
    onStartNavigation: () -> Unit,
    onStopNavigation: () -> Unit,
    onRefreshSpklu: ((() -> Unit)?) -> Unit,
    onAddUserSpklu: (name: String, socket: String, lat: Double, lng: Double) -> Unit,
    onAddFieldNote: (name: String, category: String, note: String) -> Unit,
    onSavePlace: (SavedPlace) -> Unit,
    onStartMidStreaming: () -> Unit,
    onStopMidStreaming: () -> Unit,
    onRequestScreenMirror: () -> Unit,
    onStopScreenMirror: () -> Unit,
    onPickWallpaperImage: () -> Unit,
    onUpdateSettings: (AppSettings) -> Unit,
    onSetRideLocked: (Boolean) -> Unit,
    onRetryGps: () -> Unit,
    gpsDiagTextProvider: () -> String,
    onShareText: (text: String, subject: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalCockpitColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var followRider by remember { mutableStateOf(true) }
    var mapCenter by remember { mutableStateOf(state.gpsState.location ?: GeoPointX(-6.1754, 106.8272)) }
    var mapZoom by remember { mutableDoubleStateOf(15.0) }

    LaunchedEffect(state.gpsState.location, followRider) {
        val loc = state.gpsState.location
        if (followRider && loc != null) {
            mapCenter = loc
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SavedPlace>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    var showStyleSheet by remember { mutableStateOf(false) }
    var showSpkluSheet by remember { mutableStateOf(false) }
    var showNotesSheet by remember { mutableStateOf(false) }
    var showMidSheet by remember { mutableStateOf(false) }
    var showMultiStopSheet by remember { mutableStateOf(false) }
    var showGpsDiag by remember { mutableStateOf(false) }
    var spkluDialogItem by remember { mutableStateOf<SpkluStation?>(null) }

    var longPressPoint by remember { mutableStateOf<GeoPointX?>(null) }

    fun runSearch() {
        val q = searchQuery.trim()
        if (q.isEmpty()) return
        val coords = RoutePhysics.parseCoordinatesOrGoogleMapsUrl(q)
        if (coords != null) {
            searchResults = emptyList()
            onSetSingleDestination(
                PlannedStop(
                    id = "dst_${System.currentTimeMillis()}",
                    label = "Pin Koordinat",
                    lat = coords.lat,
                    lng = coords.lng
                )
            )
            followRider = false
            mapCenter = coords
            return
        }
        scope.launch {
            searching = true
            val found = RemoteApisService.searchPlaces(q)
            searchResults = found
            searching = false
        }
    }

    val style = MapStyleOption.fromId(state.settings.mapStyleId)
    val route = state.activeRoute

    // Dialogs & Bottom Sheets
    if (showGpsDiag) {
        AlertDialog(
            onDismissRequest = { showGpsDiag = false },
            title = { Text("Diagnosis GPS Perangkat") },
            text = {
                Text(
                    gpsDiagTextProvider(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                Button(onClick = {
                    showGpsDiag = false
                    onRetryGps()
                }) {
                    Text("Coba Ulang GPS")
                }
            },
            dismissButton = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        showGpsDiag = false
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                        )
                    }) {
                        Text("Izin App")
                    }
                    TextButton(onClick = {
                        showGpsDiag = false
                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }) {
                        Text("Lokasi HP")
                    }
                    TextButton(onClick = { showGpsDiag = false }) { Text("Tutup") }
                }
            }
        )
    }

    spkluDialogItem?.let { sp ->
        AlertDialog(
            onDismissRequest = { spkluDialogItem = null },
            title = { Text(sp.name) },
            text = { Text("${sp.operatorName}\nSoket: ${sp.socketType}") },
            confirmButton = {
                Button(onClick = {
                    spkluDialogItem = null
                    onAddStop(
                        PlannedStop(
                            id = "sp_${sp.id}",
                            label = sp.name,
                            lat = sp.lat,
                            lng = sp.lng,
                            isSpklu = true
                        )
                    )
                }) {
                    Text("Tambahkan ke Rute")
                }
            },
            dismissButton = {
                TextButton(onClick = { spkluDialogItem = null }) { Text("Tutup") }
            }
        )
    }

    longPressPoint?.let { gp ->
        AlertDialog(
            onDismissRequest = { longPressPoint = null },
            title = { Text("Titik Peta Dipilih") },
            text = {
                Text(String.format(Locale.US, "Koordinat: %.5f, %.5f", gp.lat, gp.lng))
            },
            confirmButton = {
                Button(onClick = {
                    longPressPoint = null
                    onSetSingleDestination(
                        PlannedStop(
                            id = "pin_${System.currentTimeMillis()}",
                            label = "Pin Peta",
                            lat = gp.lat,
                            lng = gp.lng
                        )
                    )
                }) {
                    Text("Navigasi ke Sini")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        longPressPoint = null
                        onAddStop(
                            PlannedStop(
                                id = "stop_${System.currentTimeMillis()}",
                                label = "Stop Peta",
                                lat = gp.lat,
                                lng = gp.lng
                            )
                        )
                    }) {
                        Text("Tambah Stop")
                    }
                    TextButton(onClick = { longPressPoint = null }) { Text("Batal") }
                }
            }
        )
    }

    if (showStyleSheet) {
        ModalBottomSheet(
            onDismissRequest = { showStyleSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = c.surfaceCard
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text("Gaya Peta & Peta Offline", style = MaterialTheme.typography.headlineSmall)
                }
                items(MapStyleOption.entries) { opt ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clickable {
                                onUpdateSettings(state.settings.copy(mapStyleId = opt.id))
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(opt.label, modifier = Modifier.weight(1f))
                        if (opt == style) {
                            Icon(Icons.Rounded.GpsFixed, contentDescription = "Terpilih", tint = c.accent)
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Mode Hemat Data Peta")
                            Text("Batasi max zoom tile di jaringan seluler", fontSize = 12.sp, color = c.textMuted)
                        }
                        Switch(
                            checked = state.settings.dataSaverMap,
                            onCheckedChange = {
                                onUpdateSettings(state.settings.copy(dataSaverMap = it))
                            }
                        )
                    }
                }
                item {
                    OfflineMapSection(
                        styleId = style.id,
                        urlTemplate = style.tileUrlTemplate,
                        centerProvider = { mapCenter },
                        canBulkDownload = style == MapStyleOption.DARK_CARTO
                    )
                }
            }
        }
    }

    if (showSpkluSheet) {
        var loadingSpklu by remember { mutableStateOf(false) }
        var showAddSpklu by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            if (state.nearbySpklu.isEmpty()) {
                loadingSpklu = true
                onRefreshSpklu { loadingSpklu = false }
            }
        }
        if (showAddSpklu) {
            var name by remember { mutableStateOf("") }
            var socket by remember { mutableStateOf("Type 2 / Colokan Rumah 16A") }
            AlertDialog(
                onDismissRequest = { showAddSpklu = false },
                title = { Text("Tandai Titik Charger / SPKLU") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nama Lokasi") })
                        OutlinedTextField(value = socket, onValueChange = { socket = it }, label = { Text("Jenis Soket") })
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val loc = state.gpsState.location ?: mapCenter
                        if (name.isNotBlank()) {
                            onAddUserSpklu(name.trim(), socket.trim(), loc.lat, loc.lng)
                        }
                        showAddSpklu = false
                    }) {
                        Text("Simpan")
                    }
                },
                dismissButton = { TextButton(onClick = { showAddSpklu = false }) { Text("Batal") } }
            )
        }
        ModalBottomSheet(
            onDismissRequest = { showSpkluSheet = false },
            containerColor = c.surfaceCard
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("SPKLU & Charger Terdekat", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showAddSpklu = true }) {
                        Text("+ Tambah")
                    }
                    IconButton(onClick = {
                        loadingSpklu = true
                        onRefreshSpklu { loadingSpklu = false }
                    }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Muat ulang SPKLU")
                    }
                }
                if (loadingSpklu) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (state.nearbySpklu.isEmpty()) {
                    Text(
                        "Belum ada titik ditemukan dalam radius 20 km.",
                        color = c.textMuted,
                        modifier = Modifier.padding(vertical = 20.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 380.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(state.nearbySpklu.take(15)) { s ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = c.surfaceElevated,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showSpkluSheet = false
                                        onAddStop(
                                            PlannedStop(
                                                id = "sp_${s.id}",
                                                label = s.name,
                                                lat = s.lat,
                                                lng = s.lng,
                                                isSpklu = true
                                            )
                                        )
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.EvStation, contentDescription = null, tint = c.regen)
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(s.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${s.operatorName} • ${s.socketType}", fontSize = 12.sp, color = c.textMuted)
                                    }
                                    if (s.distanceKm != null) {
                                        Text(String.format(Locale.US, "%.1f km", s.distanceKm), fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNotesSheet) {
        var noteTitle by remember { mutableStateOf("") }
        var noteBody by remember { mutableStateOf("") }
        var cat by remember { mutableStateOf("Charger / Colokan") }
        var showAddNoteDialog by remember { mutableStateOf(false) }

        if (showAddNoteDialog) {
            AlertDialog(
                onDismissRequest = { showAddNoteDialog = false },
                title = { Text("Pin Lokasi Lapangan") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = noteTitle, onValueChange = { noteTitle = it }, label = { Text("Nama Lokasi") })
                        OutlinedTextField(value = noteBody, onValueChange = { noteBody = it }, label = { Text("Catatan") })
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Charger / Colokan", "Bengkel EV", "Rute Favorit").forEach { itemCat ->
                                FilterChip(selected = cat == itemCat, onClick = { cat = itemCat }, label = { Text(itemCat) })
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        if (noteTitle.isNotBlank()) {
                            onAddFieldNote(noteTitle.trim(), cat, noteBody.trim())
                            noteTitle = ""
                            noteBody = ""
                        }
                        showAddNoteDialog = false
                    }) {
                        Text("Simpan Pin")
                    }
                },
                dismissButton = { TextButton(onClick = { showAddNoteDialog = false }) { Text("Batal") } }
            )
        }

        ModalBottomSheet(
            onDismissRequest = { showNotesSheet = false },
            containerColor = c.surfaceCard
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Catatan Lapangan", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showAddNoteDialog = true }) {
                        Text("+ Tambah Pin")
                    }
                    if (state.fieldNotes.isNotEmpty()) {
                        IconButton(onClick = {
                            onShareText(RoutePhysics.exportFieldNotesGpx(state.fieldNotes), "Catatan Lapangan EV (GPX)")
                        }) {
                            Icon(Icons.Rounded.Download, contentDescription = "Ekspor Catatan GPX")
                        }
                    }
                }
                if (state.fieldNotes.isEmpty()) {
                    Text("Belum ada pin lapangan tersimpan.", color = c.textMuted, modifier = Modifier.padding(vertical = 20.dp))
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(state.fieldNotes) { n ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = c.surfaceElevated,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showNotesSheet = false
                                        onSetSingleDestination(PlannedStop(id = n.id, label = n.name, lat = n.lat, lng = n.lng))
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.Bookmark, contentDescription = null, tint = c.warning)
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(n.name, fontWeight = FontWeight.SemiBold)
                                        Text("${n.category} • ${n.note}", fontSize = 12.sp, color = c.textMuted)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showMidSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMidSheet = false },
            containerColor = c.surfaceCard
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("MID Display & AT+MAPS", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Status: ${state.mapsStatus} (${state.mapsFramesSent} frame • ${state.mapsLastChars} chars)",
                    fontSize = 12.5.sp,
                    color = c.textMuted
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = state.settings.mapsLayout == "split",
                        onClick = { onUpdateSettings(state.settings.copy(mapsLayout = "split")) },
                        label = { Text("Split + Turn") }
                    )
                    FilterChip(
                        selected = state.settings.mapsLayout == "full",
                        onClick = { onUpdateSettings(state.settings.copy(mapsLayout = "full")) },
                        label = { Text("Full Peta") }
                    )
                    FilterChip(
                        selected = state.settings.customWallpaperEnabled,
                        onClick = {
                            onUpdateSettings(state.settings.copy(customWallpaperEnabled = !state.settings.customWallpaperEnabled))
                        },
                        label = { Text("Wallpaper") }
                    )
                    FilterChip(
                        selected = state.settings.mirrorOverlayStrip,
                        onClick = {
                            onUpdateSettings(state.settings.copy(mirrorOverlayStrip = !state.settings.mirrorOverlayStrip))
                        },
                        label = { Text("Strip Info Maps") }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("toggle_mid_stream_button"),
                        onClick = {
                            if (state.midStreaming) onStopMidStreaming() else onStartMidStreaming()
                        }
                    ) {
                        Text(if (state.midStreaming) "Stop Stream MID" else "Mulai Stream MID")
                    }
                    OutlinedButton(
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("toggle_screen_mirror_button"),
                        onClick = {
                            if (state.screenMirrorActive) {
                                onStopScreenMirror()
                            } else {
                                showMidSheet = false
                                onRequestScreenMirror()
                            }
                        }
                    ) {
                        Text(if (state.screenMirrorActive) "Stop Mirror" else "Mirror Layar")
                    }
                }
                val notifGranted = MapsNotifListener.isPermissionGranted(context)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("preview_mirror_region_button"),
                        onClick = {
                            if (MirrorRegionOverlay.canDraw(context)) {
                                showMidSheet = false
                                MirrorRegionOverlay.show(context)
                            } else {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }
                        }
                    ) {
                        Text("Area Mirror")
                    }
                    OutlinedButton(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("maps_notif_access_button"),
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                    ) {
                        Text(if (notifGranted) "Notif Maps: Aktif" else "Izin Notif Maps")
                    }
                }
                OutlinedButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("pick_wallpaper_button"),
                    onClick = onPickWallpaperImage
                ) {
                    Icon(Icons.Rounded.Image, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Pilih Wallpaper Custom MID")
                }
                Spacer(Modifier.height(14.dp))
            }
        }
    }

    if (showMultiStopSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMultiStopSheet = false },
            containerColor = c.surfaceCard
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text("Perencana Multi-Stop", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(10.dp))
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(state.plannedStops) { idx, st ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(c.accent),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("${idx + 1}", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(st.label, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onRemoveStopAt(idx) }) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Hapus titik", tint = c.danger)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        showMultiStopSheet = false
                        onClearRoute()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Hapus Semua Rute")
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }

    // Main Map + Floating HUD Stack
    Box(modifier = modifier.fillMaxSize()) {
        SlippyMapView(
            center = mapCenter,
            zoom = mapZoom,
            style = style,
            dataSaverMap = state.settings.dataSaverMap,
            riderLocation = state.gpsState.location,
            riderHeadingDeg = state.gpsState.headingDeg ?: 0.0,
            customMarkerPath = state.settings.customMarkerPath,
            routeGeometry = route?.geometry ?: emptyList(),
            plannedStops = state.plannedStops,
            nearbySpklu = state.nearbySpklu,
            fieldNotes = state.fieldNotes,
            onMapPanZoom = { newCenter, newZoom ->
                followRider = false
                mapCenter = newCenter
                mapZoom = newZoom
            },
            onMapLongPress = { gp ->
                longPressPoint = gp
            },
            onSpkluClick = { s ->
                spkluDialogItem = s
            },
            modifier = Modifier.fillMaxSize()
        )

        // Top Search or Turn Card + GPS Diagnostic Banner
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.isNavigating && route != null && route.steps.isNotEmpty()) {
                val curStep = route.steps[state.activeStepIndex.coerceIn(0, route.steps.lastIndex)]
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = c.surfaceCard.copy(alpha = 0.94f),
                    border = BorderStroke(1.5.dp, c.accent)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Navigation, contentDescription = null, tint = c.accent, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${state.distToNextStepM.roundToInt()} m", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.accent)
                            Text(curStep.instruction, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(
                            onClick = { onSetRideLocked(true) },
                            modifier = Modifier.testTag("lock_screen_nav_button")
                        ) {
                            Icon(Icons.Rounded.Lock, contentDescription = "Kunci Layar Berkendara", tint = c.warning)
                        }
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = c.surfaceCard.copy(alpha = 0.94f),
                    border = BorderStroke(1.dp, c.borderSubtle)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = null, tint = c.textMuted, modifier = Modifier.padding(start = 4.dp))
                        Spacer(Modifier.width(6.dp))
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Cari tujuan, lat,lng atau tautan Maps", fontSize = 13.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = Color.Transparent,
                                focusedBorderColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("nav_search_input")
                        )
                        if (searching) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = c.accent)
                        } else if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = {
                                searchQuery = ""
                                searchResults = emptyList()
                            }) {
                                Icon(Icons.Rounded.Clear, contentDescription = "Bersihkan pencarian")
                            }
                        } else {
                            IconButton(
                                onClick = { runSearch() },
                                modifier = Modifier.testTag("nav_search_button")
                            ) {
                                Icon(Icons.Rounded.Search, contentDescription = "Cari", tint = c.accent)
                            }
                        }
                    }
                }

                if (searchResults.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = c.surfaceCard,
                        border = BorderStroke(1.dp, c.borderSubtle),
                        modifier = Modifier.heightIn(max = 230.dp)
                    ) {
                        LazyColumn {
                            items(searchResults) { p ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            searchResults = emptyList()
                                            onSetSingleDestination(
                                                PlannedStop(id = p.id, label = p.name, lat = p.lat, lng = p.lng)
                                            )
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        p.name,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 12.5.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = { onSavePlace(p) }) {
                                        Icon(Icons.Rounded.BookmarkAdd, contentDescription = "Simpan tempat")
                                    }
                                    IconButton(onClick = {
                                        searchResults = emptyList()
                                        onAddStop(PlannedStop(id = p.id, label = p.name, lat = p.lat, lng = p.lng))
                                    }) {
                                        Icon(Icons.Rounded.AddLocationAlt, contentDescription = "Tambah stop")
                                    }
                                }
                                HorizontalDivider(color = c.borderSubtle)
                            }
                        }
                    }
                }
            }

            if (state.gpsState.location == null) {
                Surface(
                    shape = CircleShape,
                    color = c.warning.copy(alpha = 0.9f),
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clip(CircleShape)
                        .clickable { showGpsDiag = true }
                        .testTag("gps_diag_pill")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.GpsOff, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Menunggu GPS… ketuk untuk diagnosis", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
            }
        }

        // Right Map Floating Buttons (>=48dp touch targets)
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = if (route != null) 250.dp else 108.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MapFabButton(
                icon = Icons.Rounded.MyLocation,
                desc = "Ikuti Posisi Pengendara",
                tint = if (followRider) c.accent else Color.White,
                testTag = "map_follow_fab"
            ) {
                followRider = true
                state.gpsState.location?.let { mapCenter = it }
            }
            MapFabButton(
                icon = Icons.Rounded.Layers,
                desc = "Gaya Peta & Offline",
                tint = Color.White,
                testTag = "map_style_fab"
            ) { showStyleSheet = true }
            MapFabButton(
                icon = Icons.Rounded.EvStation,
                desc = "SPKLU Terdekat",
                tint = c.regen,
                testTag = "map_spklu_fab"
            ) { showSpkluSheet = true }
            MapFabButton(
                icon = Icons.Rounded.BookmarkAdd,
                desc = "Catatan Lapangan",
                tint = c.warning,
                testTag = "map_notes_fab"
            ) { showNotesSheet = true }
            MapFabButton(
                icon = Icons.Rounded.Cast,
                desc = "Stream ke MID Motor",
                tint = if (state.midStreaming || state.screenMirrorActive) c.accent else Color.White,
                testTag = "map_mid_stream_fab"
            ) { showMidSheet = true }
        }

        // Bottom Route Card
        if (route != null) {
            val est = route.energyEstimate
            CockpitCard(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 92.dp),
                contentPadding = 14.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${String.format(Locale.US, "%.1f", state.remainingDistKm)} km • ${state.etaMin.roundToInt()} mnt",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (est != null) {
                            Text(
                                "Prediksi ${est.netWh.roundToInt()} Wh (${String.format(Locale.US, "%.0f", est.whPerKm)} Wh/km) • Tiba SOC ${est.socAtArrivalPct?.roundToInt()?.toString() ?: "—"}%",
                                fontSize = 12.sp,
                                color = if ((est.socAtArrivalPct ?: 50.0) < 12.0) c.danger else c.accent
                            )
                        }
                    }
                    if (state.plannedStops.size > 1) {
                        TextButton(onClick = { showMultiStopSheet = true }) {
                            Text("${state.plannedStops.size} Stop")
                        }
                    }
                    IconButton(onClick = onClearRoute) {
                        Icon(Icons.Rounded.Close, contentDescription = "Tutup Rute")
                    }
                }
                if (route.elevationSamples.size >= 2) {
                    Spacer(Modifier.height(6.dp))
                    ElevationProfileChart(
                        samples = route.elevationSamples,
                        trackColor = c.borderSubtle,
                        labelColor = c.textMuted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(66.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("start_stop_nav_button"),
                        onClick = {
                            if (state.isNavigating) onStopNavigation() else onStartNavigation()
                        }
                    ) {
                        Text(if (state.isNavigating) "Selesai Navigasi" else "Mulai Navigasi")
                    }
                    OutlinedButton(
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("open_multistop_button"),
                        onClick = { showMultiStopSheet = true }
                    ) {
                        Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Rute")
                    }
                }
            }
        }

        // Fullscreen Ride Lock Overlay (Hold 1.5s to unlock)
        if (state.isRideLocked) {
            var holding by remember { mutableStateOf(false) }
            LaunchedEffect(holding) {
                if (holding) {
                    delay(1500L)
                    onSetRideLocked(false)
                    holding = false
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xEE05080D))
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                holding = true
                                tryAwaitRelease()
                                holding = false
                            }
                        )
                    }
                    .testTag("ride_lock_overlay"),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Lock, contentDescription = "Terkunci", tint = c.accent, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "${state.effective.speedKmh?.roundToInt()?.toString() ?: "—"} km/h",
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (holding) "Melepaskan kunci…" else "Tahan layar 1.5 detik untuk membuka kunci",
                        color = c.textMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun MapFabButton(
    icon: ImageVector,
    desc: String,
    tint: Color,
    testTag: String,
    onClick: () -> Unit
) {
    val c = LocalCockpitColors.current
    Surface(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = CircleShape,
        color = c.surfaceCard.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, c.borderSubtle),
        shadowElevation = 4.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(22.dp))
        }
    }
}
