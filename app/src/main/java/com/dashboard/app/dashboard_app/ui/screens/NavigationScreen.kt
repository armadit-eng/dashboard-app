package com.dashboard.app.dashboard_app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ElectricMoped
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.GpsOff
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Stop
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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.dashboard.app.dashboard_app.model.HillSegment
import com.dashboard.app.dashboard_app.model.MapStyleOption
import com.dashboard.app.dashboard_app.model.PlannedStop
import com.dashboard.app.dashboard_app.model.SavedPlace
import com.dashboard.app.dashboard_app.model.SpkluStation
import com.dashboard.app.dashboard_app.service.OfflineMapPackageService
import com.dashboard.app.dashboard_app.service.RemoteApisService
import com.dashboard.app.dashboard_app.service.SpkluExportFormat
import com.dashboard.app.dashboard_app.service.SpkluImportParser
import com.dashboard.app.dashboard_app.service.TileCacheService
import com.dashboard.app.dashboard_app.ui.components.AddSpkluDialog
import com.dashboard.app.dashboard_app.ui.components.CatatNamaDialog
import com.dashboard.app.dashboard_app.ui.components.FavoritesBottomSheet
import com.dashboard.app.dashboard_app.ui.components.MapPlacePopupCard
import com.dashboard.app.dashboard_app.ui.components.MultiStopPlannerSheet
import com.dashboard.app.dashboard_app.ui.components.OfflineMapSection
import com.dashboard.app.dashboard_app.ui.components.RoutePreviewSheetCard
import com.dashboard.app.dashboard_app.ui.components.SlippyMapView
import com.dashboard.app.dashboard_app.ui.components.SpkluBottomSheet
import com.dashboard.app.dashboard_app.ui.components.SpkluDetailDialog
import com.dashboard.app.dashboard_app.ui.components.SpkluExportDialog
import com.dashboard.app.dashboard_app.ui.components.TappedMapPlace
import com.dashboard.app.dashboard_app.ui.components.TripPrepDialog
import com.dashboard.app.dashboard_app.ui.theme.LocalCockpitColors
import com.dashboard.app.dashboard_app.viewmodel.DashboardUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NavigationScreen(
    state: DashboardUiState,
    onSetSingleDestination: (PlannedStop) -> Unit,
    onAddStop: (PlannedStop) -> Unit,
    onRemoveStopAt: (Int) -> Unit,
    onReorderStop: (Int, Int) -> Unit,
    onSelectRouteAlternative: (Int) -> Unit,
    onClearRoute: () -> Unit,
    onStartNavigation: () -> Unit,
    onStopNavigation: () -> Unit,
    onRefreshSpklu: ((() -> Unit)?) -> Unit,
    onSyncSpkluUrl: (String, (Int, String) -> Unit) -> Unit,
    onAddUserSpklu: (name: String, socket: String, lat: Double, lng: Double, powerKw: Double?, notes: String, isMolis: Boolean) -> Unit,
    onRemoveUserSpklu: (String) -> Unit,
    onImportSpkluUri: (Uri, String?, (Int, String) -> Unit) -> Unit,
    onAddFieldNoteAt: (name: String, category: String, note: String, lat: Double, lng: Double) -> Unit,
    onSavePlace: (SavedPlace) -> Unit,
    onRemoveSavedPlace: (String) -> Unit,
    onStartMidStreaming: () -> Unit,
    onStopMidStreaming: () -> Unit,
    onRequestScreenMirror: () -> Unit,
    onStopScreenMirror: () -> Unit,
    onPickWallpaperImage: () -> Unit,
    onUpdateSettings: (AppSettings) -> Unit,
    onSetRideLocked: (Boolean) -> Unit,
    onToggleOfflineMode: (Boolean) -> Unit,
    onRefreshOfflineRegions: () -> Unit,
    onDeleteOfflineRegion: (String) -> Unit,
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
    var showMirrorAreaDialog by remember { mutableStateOf(false) }
    var showMultiStopSheet by remember { mutableStateOf(false) }
    var showFavoritesSheet by remember { mutableStateOf(false) }
    var showGpsDiag by remember { mutableStateOf(false) }
    var spkluDialogItem by remember { mutableStateOf<SpkluStation?>(null) }
    var addSpkluTarget by remember { mutableStateOf<Pair<String, GeoPointX>?>(null) }
    var spkluImportFeedback by remember { mutableStateOf<String?>(null) }
    var showSpkluExportDialog by remember { mutableStateOf(false) }
    var exportFilteredStations by remember { mutableStateOf<List<SpkluStation>>(emptyList()) }
    var pendingExportFormat by remember { mutableStateOf<SpkluExportFormat?>(null) }
    var pendingExportStations by remember { mutableStateOf<List<SpkluStation>>(emptyList()) }

    val spkluImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val fileName = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
            }.getOrNull() ?: uri.lastPathSegment
            onImportSpkluUri(uri, fileName) { _, msg ->
                spkluImportFeedback = msg
            }
        }
    }

    val spkluExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val fmt = pendingExportFormat
        val list = pendingExportStations
        pendingExportFormat = null
        pendingExportStations = emptyList()
        if (uri != null && fmt != null && list.isNotEmpty()) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val content = SpkluImportParser.exportStations(list, fmt)
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(content.toByteArray(Charsets.UTF_8))
                            out.flush()
                        } ?: throw IllegalStateException("Stream kosong")
                        true
                    }.getOrDefault(false)
                }
                spkluImportFeedback = if (ok) {
                    "Berhasil menyimpan ${list.size} titik (${fmt.label})."
                } else {
                    "Gagal menyimpan file ekspor."
                }
            }
        }
    }

    var showRoutePreview by remember { mutableStateOf(false) }
    var previewDetailsExpanded by remember { mutableStateOf(true) }
    var showTripPrepDialog by remember { mutableStateOf(false) }
    var tappedPlace by remember { mutableStateOf<TappedMapPlace?>(null) }
    var catatTargetPoint by remember { mutableStateOf<GeoPointX?>(null) }
    var plannerPickMode by remember { mutableStateOf(false) }
    var scrubSampleIdx by remember { mutableStateOf<Int?>(null) }
    var selectedHill by remember { mutableStateOf<HillSegment?>(null) }
    var showAllHills by remember { mutableStateOf(false) }

    val route = state.activeRoute
    LaunchedEffect(route) {
        scrubSampleIdx = null
        selectedHill = null
        showAllHills = false
        if (route == null) {
            showRoutePreview = false
        } else if (!state.isNavigating) {
            showRoutePreview = true
            tappedPlace = null
        }
    }
    LaunchedEffect(state.isNavigating) {
        if (state.isNavigating) {
            showRoutePreview = false
            tappedPlace = null
            followRider = true
            state.gpsState.location?.let { mapCenter = it }
            if (mapZoom < 16.0) mapZoom = 16.5
        }
    }

    fun selectPointOnMap(gp: GeoPointX) {
        if (state.isRideLocked) return
        if (plannerPickMode) {
            plannerPickMode = false
            onAddStop(
                PlannedStop(
                    id = "pick_${System.currentTimeMillis()}",
                    label = String.format(Locale.US, "Titik %.4f, %.4f", gp.lat, gp.lng),
                    lat = gp.lat,
                    lng = gp.lng
                )
            )
            return
        }
        searchResults = emptyList()
        val origin = state.gpsState.location
        val straightKm = origin?.let { RoutePhysics.haversineKm(it, gp) }
        tappedPlace = TappedMapPlace(
            point = gp,
            name = "Titik Peta",
            address = "",
            loading = true,
            straightKm = straightKm
        )
        scope.launch {
            val isOfflineOnly = TileCacheService.offlineOnly
            val geoDeferred = async {
                val online = if (!isOfflineOnly) RemoteApisService.reverseGeocode(gp.lat, gp.lng) else null
                online ?: OfflineMapPackageService.reverseGeocodeOffline(context, gp.lat, gp.lng)
            }
            val routeDeferred = async {
                if (origin != null) {
                    val onlineRt = if (!isOfflineOnly) RemoteApisService.fetchQuickRouteSummary(origin, gp) else null
                    onlineRt ?: OfflineMapPackageService.quickRouteSummaryOffline(context, origin, gp)
                } else null
            }
            val geo = geoDeferred.await()
            val rt = routeDeferred.await()
            val cur = tappedPlace
            if (cur != null && cur.point == gp) {
                val placeName = geo?.road?.takeIf { it.isNotBlank() }
                    ?: geo?.area?.takeIf { it.isNotBlank() }
                    ?: "Titik Peta"
                val addr = listOfNotNull(
                    geo?.road?.takeIf { it.isNotBlank() },
                    geo?.area?.takeIf { it.isNotBlank() }
                ).distinct().joinToString(", ")
                tappedPlace = cur.copy(
                    name = placeName,
                    address = addr,
                    loading = false,
                    routeKm = rt?.first,
                    routeMin = rt?.second
                )
            }
        }
    }

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
            val isOfflineOnly = TileCacheService.offlineOnly
            if (!isOfflineOnly && RemoteApisService.isMapsShortUrl(q)) {
                val resolved = RemoteApisService.resolveGoogleMapsShortUrl(q)
                if (resolved != null) {
                    searching = false
                    searchResults = emptyList()
                    onSetSingleDestination(
                        PlannedStop(
                            id = "dst_${System.currentTimeMillis()}",
                            label = "Pin Link Maps",
                            lat = resolved.lat,
                            lng = resolved.lng
                        )
                    )
                    followRider = false
                    mapCenter = resolved
                    return@launch
                }
            }
            val offlineHits = OfflineMapPackageService.searchPlacesOffline(
                context = context,
                query = q,
                biasOrigin = state.gpsState.location
            )
            val savedHits = state.savedPlaces.filter {
                it.name.contains(q, ignoreCase = true) || it.category.contains(q, ignoreCase = true)
            }
            val spkluHits = state.nearbySpklu
                .filter {
                    it.name.contains(q, ignoreCase = true) ||
                        it.operatorName.contains(q, ignoreCase = true) ||
                        it.socketType.contains(q, ignoreCase = true) ||
                        it.notes.contains(q, ignoreCase = true)
                }
                .take(8)
                .map {
                    val distPart = it.distanceKm?.let { d -> " • ${String.format(Locale.US, "%.1f km", d)}" }.orEmpty()
                    SavedPlace(
                        id = "sp_${it.id}",
                        name = "${it.name} (Charger Molis$distPart)",
                        category = it.socketType,
                        lat = it.lat,
                        lng = it.lng,
                        createdAtMs = 0L
                    )
                }
            val localCombined = (savedHits + spkluHits + offlineHits)
                .distinctBy { "${it.name.lowercase(Locale.US)}|${(it.lat * 1000).roundToInt()}" }

            val onlineHits = if (!isOfflineOnly) RemoteApisService.searchPlaces(q) else emptyList()
            searchResults = (onlineHits + localCombined)
                .distinctBy { "${it.name.lowercase(Locale.US)}|${(it.lat * 1000).roundToInt()}" }
                .take(15)
            searching = false
        }
    }

    val style = MapStyleOption.fromId(state.settings.mapStyleId)

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
        SpkluDetailDialog(
            station = sp,
            hasActiveRoute = state.plannedStops.isNotEmpty(),
            onRouteDirect = {
                spkluDialogItem = null
                onSetSingleDestination(
                    PlannedStop(
                        id = "sp_${sp.id}",
                        label = sp.name,
                        lat = sp.lat,
                        lng = sp.lng,
                        isSpklu = true
                    )
                )
            },
            onAddAsStop = {
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
            },
            onOpenExternalMaps = {
                val uri = Uri.parse(
                    "geo:${sp.lat},${sp.lng}?q=${sp.lat},${sp.lng}(${Uri.encode(sp.name)})"
                )
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    .onFailure {
                        onShareText("https://www.google.com/maps?q=${sp.lat},${sp.lng}", sp.name)
                    }
            },
            onDismiss = { spkluDialogItem = null }
        )
    }

    addSpkluTarget?.let { (initialName, targetPt) ->
        AddSpkluDialog(
            initialName = initialName,
            targetPoint = targetPt,
            onSave = { n, sock, lat, lng, kw, notes, isMolis ->
                onAddUserSpklu(n, sock, lat, lng, kw, notes, isMolis)
                spkluImportFeedback = "Tersimpan: $n"
            },
            onDismiss = { addSpkluTarget = null }
        )
    }

    catatTargetPoint?.let { cPt ->
        CatatNamaDialog(
            initialLat = cPt.lat,
            initialLng = cPt.lng,
            gpsLocation = state.gpsState.location,
            onSaveNoteAt = onAddFieldNoteAt,
            onDismiss = { catatTargetPoint = null }
        )
    }

    if (showTripPrepDialog && route != null) {
        TripPrepDialog(
            settings = state.settings,
            route = route,
            currentSocPct = state.effective.combinedSocPct,
            onUpdateSettings = onUpdateSettings,
            onSkip = {
                showTripPrepDialog = false
                showRoutePreview = false
                onStartNavigation()
            },
            onStartWithWeights = { rKg, lKg, noAsk ->
                onUpdateSettings(
                    state.settings.copy(
                        riderWeightKg = rKg,
                        loadWeightKg = lKg,
                        tripPrepAsk = if (noAsk) false else state.settings.tripPrepAsk
                    )
                )
                showTripPrepDialog = false
                showRoutePreview = false
                onStartNavigation()
            },
            onDismiss = { showTripPrepDialog = false }
        )
    }

    if (showFavoritesSheet) {
        FavoritesBottomSheet(
            places = state.savedPlaces,
            onSelectPlace = { p ->
                showFavoritesSheet = false
                onSetSingleDestination(PlannedStop(id = p.id, label = p.name, lat = p.lat, lng = p.lng))
                followRider = false
                mapCenter = GeoPointX(p.lat, p.lng)
            },
            onDeletePlace = onRemoveSavedPlace,
            onDismiss = { showFavoritesSheet = false }
        )
    }

    if (showStyleSheet) {
        var cartoKeyDraft by remember(state.settings.cartoApiKey) {
            mutableStateOf(state.settings.cartoApiKey)
        }
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
                    val styleErr = if (opt.usesCartoKey) TileCacheService.lastErrorForStyle(opt.id) else null
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .clickable {
                                onUpdateSettings(state.settings.copy(mapStyleId = opt.id))
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(opt.label)
                            if (opt.usesCartoKey) {
                                Text(
                                    text = when {
                                        styleErr != null -> styleErr
                                        state.settings.cartoApiKey.isNotBlank() -> "CARTO • API Key aktif"
                                        else -> "CARTO Basemaps (butuh API Key)"
                                    },
                                    fontSize = 11.sp,
                                    color = if (styleErr != null) c.danger else c.textMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            } else if (opt == MapStyleOption.DARK_OSM) {
                                Text(
                                    "Mode gelap OpenStreetMap tanpa API Key",
                                    fontSize = 11.sp,
                                    color = c.textMuted
                                )
                            }
                        }
                        if (opt == style) {
                            Icon(Icons.Rounded.GpsFixed, contentDescription = "Terpilih", tint = c.accent)
                        }
                    }
                }
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = c.borderSubtle)
                    Text("Carto Basemaps API Key (carto.com/basemaps-api-key)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = cartoKeyDraft,
                            onValueChange = { cartoKeyDraft = it },
                            label = { Text("Carto API Key") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("carto_api_key_input")
                        )
                        Button(
                            onClick = {
                                val cleaned = TileCacheService.cleanCartoApiKey(cartoKeyDraft)
                                scope.launch {
                                    TileCacheService.clearStyles(
                                        context,
                                        listOf("dark", "carto_voyager", "carto_positron")
                                    )
                                    onUpdateSettings(state.settings.copy(cartoApiKey = cleaned))
                                }
                            },
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("save_carto_key_button")
                        ) {
                            Text("Simpan")
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
                        styleOption = style,
                        cartoApiKey = state.settings.cartoApiKey,
                        offlineModeOnly = state.settings.offlineModeOnly,
                        offlineRegions = state.offlineRegions,
                        centerProvider = { mapCenter },
                        canBulkTileDownload = style.usesCartoKey ||
                            style == MapStyleOption.OSM_HOT ||
                            style == MapStyleOption.TOPO,
                        onToggleOfflineMode = onToggleOfflineMode,
                        onRegionsChanged = onRefreshOfflineRegions,
                        onDeleteRegion = onDeleteOfflineRegion,
                        onFocusRegion = { pt ->
                            followRider = false
                            mapCenter = pt
                            if (mapZoom < 13.5) mapZoom = 14.0
                            showStyleSheet = false
                        }
                    )
                }
            }
        }
    }

    if (showSpkluSheet) {
        var loadingSpklu by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            if (!state.settings.offlineModeOnly && state.nearbySpklu.none { it.sourceLabel == "OSM" || it.sourceLabel == "OCM" }) {
                loadingSpklu = true
                onRefreshSpklu { loadingSpklu = false }
            }
        }
        SpkluBottomSheet(
            stations = state.nearbySpklu,
            settings = state.settings,
            loading = loadingSpklu,
            importFeedback = spkluImportFeedback,
            onDismissImportFeedback = { spkluImportFeedback = null },
            onRefresh = {
                loadingSpklu = true
                onRefreshSpklu { loadingSpklu = false }
            },
            onSyncFeedUrl = { url ->
                loadingSpklu = true
                onSyncSpkluUrl(url) { _, msg ->
                    loadingSpklu = false
                    spkluImportFeedback = msg
                }
            },
            onOpenAddDialog = {
                addSpkluTarget = "" to (state.gpsState.location ?: mapCenter)
            },
            onOpenImportPicker = {
                spkluImportLauncher.launch(arrayOf("*/*"))
            },
            onOpenExport = { filteredList ->
                exportFilteredStations = filteredList
                showSpkluExportDialog = true
            },
            onSearchInGoogleMaps = {
                val center = state.gpsState.location ?: mapCenter
                val uri = Uri.parse(
                    String.format(
                        Locale.US,
                        "geo:%.5f,%.5f?q=%s",
                        center.lat,
                        center.lng,
                        Uri.encode("charger molis OR SPLU OR SPKLU")
                    )
                )
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    .onFailure {
                        val web = Uri.parse("https://www.google.com/maps/search/?api=1&query=charger+molis+SPKLU")
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, web)) }
                    }
            },
            onUpdateSettings = onUpdateSettings,
            onSelectStation = { s ->
                showSpkluSheet = false
                spkluDialogItem = s
                followRider = false
                mapCenter = GeoPointX(s.lat, s.lng)
                if (mapZoom < 14.5) mapZoom = 15.0
            },
            onDeleteUserStation = onRemoveUserSpklu,
            onDismiss = { showSpkluSheet = false }
        )
    }

    if (showSpkluExportDialog) {
        SpkluExportDialog(
            totalCount = state.nearbySpklu.size,
            filteredCount = exportFilteredStations.size,
            onExport = { format, onlyFiltered ->
                val listToWrite = if (onlyFiltered && exportFilteredStations.isNotEmpty()) {
                    exportFilteredStations
                } else {
                    state.nearbySpklu
                }
                showSpkluExportDialog = false
                pendingExportFormat = format
                pendingExportStations = listToWrite
                spkluExportLauncher.launch(format.fileName)
            },
            onDismiss = { showSpkluExportDialog = false }
        )
    }

    if (showNotesSheet) {
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
                    TextButton(onClick = {
                        showNotesSheet = false
                        catatTargetPoint = state.gpsState.location ?: mapCenter
                    }) {
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
                    Text(
                        "Belum ada pin lapangan. Ketuk peta lalu pilih \"Catat nama\" atau tekan + Tambah Pin.",
                        color = c.textMuted,
                        modifier = Modifier.padding(vertical = 20.dp)
                    )
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
                    .verticalScroll(rememberScrollState())
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
                        selected = state.settings.mapsAutoStream,
                        onClick = {
                            onUpdateSettings(state.settings.copy(mapsAutoStream = !state.settings.mapsAutoStream))
                        },
                        label = { Text("Auto-Stream") },
                        modifier = Modifier.testTag("mid_autostream_chip")
                    )
                    FilterChip(
                        selected = state.settings.mapsCompassOn,
                        onClick = {
                            onUpdateSettings(state.settings.copy(mapsCompassOn = !state.settings.mapsCompassOn))
                        },
                        label = { Text("Kompas") },
                        modifier = Modifier.testTag("mid_compass_chip")
                    )
                    FilterChip(
                        selected = state.settings.routeCacheEnabled,
                        onClick = {
                            onUpdateSettings(state.settings.copy(routeCacheEnabled = !state.settings.routeCacheEnabled))
                        },
                        label = { Text("Cache Rute") },
                        modifier = Modifier.testTag("mid_route_cache_chip")
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
                Text(
                    "Kualitas JPEG MID: ${state.settings.mapsQualityPct}%",
                    fontSize = 12.sp,
                    color = c.textMuted
                )
                Slider(
                    value = state.settings.mapsQualityPct.coerceIn(30, 95).toFloat(),
                    onValueChange = {
                        onUpdateSettings(state.settings.copy(mapsQualityPct = it.roundToInt()))
                    },
                    valueRange = 30f..95f,
                    modifier = Modifier.testTag("mid_quality_slider")
                )
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
                            showMirrorAreaDialog = true
                        }
                    ) {
                        val xPct = (state.settings.mirrorOffsetX * 100f).roundToInt()
                        val yPct = (state.settings.mirrorOffsetY * 100f).roundToInt()
                        Text("Area Mirror ($xPct,$yPct)", maxLines = 1, overflow = TextOverflow.Ellipsis)
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

    if (showMirrorAreaDialog) {
        val s = state.settings
        val xPct = (s.mirrorOffsetX.coerceIn(0f, 1f) * 100f).roundToInt()
        val yPct = (s.mirrorOffsetY.coerceIn(0f, 1f) * 100f).roundToInt()
        val scalePct = s.mirrorScalePct.coerceIn(35, 100)
        AlertDialog(
            onDismissRequest = { showMirrorAreaDialog = false },
            containerColor = c.surfaceCard,
            title = { Text("Custom Area Mirror MID") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(
                            modifier = Modifier
                                .width(112.dp)
                                .aspectRatio(9f / 18f)
                                .testTag("mirror_area_preview_canvas")
                        ) {
                            val fw = 900
                            val fh = 1800
                            val sx = size.width / fw.toFloat()
                            val sy = size.height / fh.toFloat()
                            drawRoundRect(
                                color = Color(0xFF0E1621),
                                cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx())
                            )
                            drawRoundRect(
                                color = Color(0xFF2B3A4A),
                                cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx()),
                                style = Stroke(width = 1.5.dp.toPx())
                            )
                            // Center guide lines
                            drawLine(
                                color = Color(0x28FFFFFF),
                                start = Offset(size.width / 2f, 0f),
                                end = Offset(size.width / 2f, size.height),
                                strokeWidth = 1.dp.toPx()
                            )
                            drawLine(
                                color = Color(0x28FFFFFF),
                                start = Offset(0f, size.height / 2f),
                                end = Offset(size.width, size.height / 2f),
                                strokeWidth = 1.dp.toPx()
                            )
                            val rect = MirrorRegionOverlay.computeCaptureRect(
                                fw = fw,
                                fh = fh,
                                outW = s.mapsSnapWidth,
                                outH = s.mapsSnapHeight,
                                offsetX = s.mirrorOffsetX,
                                offsetY = s.mirrorOffsetY,
                                scalePct = scalePct
                            )
                            val rl = rect.left * sx
                            val rt = rect.top * sy
                            val rw = rect.width() * sx
                            val rh = rect.height() * sy
                            drawRect(
                                color = Color(0x3800E5A3),
                                topLeft = Offset(rl, rt),
                                size = Size(rw, rh)
                            )
                            drawRect(
                                color = Color(0xFF00E5A3),
                                topLeft = Offset(rl, rt),
                                size = Size(rw, rh),
                                style = Stroke(width = 2.dp.toPx())
                            )
                            drawCircle(
                                color = Color.White,
                                radius = 3.dp.toPx(),
                                center = Offset(rl + rw / 2f, rt + rh / 2f)
                            )
                        }
                    }

                    Text(
                        "Geser Kiri ↔ Kanan: $xPct%",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Slider(
                        value = s.mirrorOffsetX.coerceIn(0f, 1f),
                        onValueChange = { v ->
                            val nextScale = if (s.mirrorScalePct >= 98) 80 else s.mirrorScalePct
                            onUpdateSettings(s.copy(mirrorOffsetX = v, mirrorScalePct = nextScale))
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.testTag("mirror_offset_x_slider")
                    )
                    if (scalePct >= 98) {
                        Text(
                            "Tips: menggeser Kiri/Kanan otomatis menyesuaikan lebar area ke 80% agar posisi bisa bergeser.",
                            fontSize = 11.sp,
                            color = c.textMuted
                        )
                    }

                    Text(
                        "Geser Atas ↕ Bawah: $yPct%",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Slider(
                        value = s.mirrorOffsetY.coerceIn(0f, 1f),
                        onValueChange = { v ->
                            onUpdateSettings(s.copy(mirrorOffsetY = v))
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.testTag("mirror_offset_y_slider")
                    )

                    Text(
                        "Lebar Area Tangkapan: $scalePct%",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Slider(
                        value = scalePct.toFloat(),
                        onValueChange = { v ->
                            onUpdateSettings(s.copy(mirrorScalePct = v.roundToInt().coerceIn(35, 100)))
                        },
                        valueRange = 35f..100f,
                        modifier = Modifier.testTag("mirror_scale_slider")
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                onUpdateSettings(
                                    s.copy(
                                        mirrorOffsetX = 0.5f,
                                        mirrorOffsetY = 0.44f,
                                        mirrorScalePct = 100
                                    )
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("mirror_reset_button")
                        ) {
                            Text("Reset Tengah")
                        }
                        Button(
                            onClick = {
                                if (MirrorRegionOverlay.canDraw(context)) {
                                    showMirrorAreaDialog = false
                                    showMidSheet = false
                                    MirrorRegionOverlay.show(
                                        context = context,
                                        offsetX = s.mirrorOffsetX,
                                        offsetY = s.mirrorOffsetY,
                                        scalePct = s.mirrorScalePct
                                    )
                                } else {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                    )
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("mirror_show_overlay_button")
                        ) {
                            Text("Lihat di Layar")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showMirrorAreaDialog = false },
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Selesai")
                }
            }
        )
    }

    if (showMultiStopSheet) {
        MultiStopPlannerSheet(
            stops = state.plannedStops,
            hasGps = state.gpsState.location != null,
            onAddCurrentLocationStop = {
                val loc = state.gpsState.location ?: return@MultiStopPlannerSheet
                onAddStop(
                    PlannedStop(
                        id = "cur_${System.currentTimeMillis()}",
                        label = "Lokasi Saat Ini",
                        lat = loc.lat,
                        lng = loc.lng
                    )
                )
            },
            onPickOnMap = { plannerPickMode = true },
            onMoveStop = onReorderStop,
            onRemoveStopAt = onRemoveStopAt,
            onClearAll = onClearRoute,
            onDismiss = { showMultiStopSheet = false }
        )
    }

    val scrubPoint = scrubSampleIdx?.let { route?.elevationSamples?.getOrNull(it)?.point }
    val highlightedHillPts = remember(route, selectedHill) {
        val h = selectedHill
        if (route != null && h != null && route.elevationSamples.size >= 2) {
            route.elevationSamples.filter {
                val dM = it.distanceKm * 1000.0
                dM >= h.startDistM - 80.0 && dM <= h.endDistM + 80.0
            }.map { it.point }
        } else emptyList()
    }

    // Main Map + Floating HUD Stack
    Box(modifier = modifier.fillMaxSize()) {
        SlippyMapView(
            center = mapCenter,
            zoom = mapZoom,
            style = style,
            cartoApiKey = state.settings.cartoApiKey,
            dataSaverMap = state.settings.dataSaverMap,
            riderLocation = state.gpsState.location,
            riderHeadingDeg = state.gpsState.headingDeg ?: 0.0,
            customMarkerPath = state.settings.customMarkerPath,
            routeGeometry = route?.geometry ?: emptyList(),
            plannedStops = state.plannedStops,
            nearbySpklu = state.nearbySpklu,
            fieldNotes = state.fieldNotes,
            selectedPin = tappedPlace?.point,
            highlightCursorPoint = scrubPoint,
            highlightedHillPoints = highlightedHillPts,
            onMapPanZoom = { newCenter, newZoom ->
                followRider = false
                mapCenter = newCenter
                mapZoom = newZoom
            },
            onMapTap = { gp -> selectPointOnMap(gp) },
            onMapLongPress = { gp -> selectPointOnMap(gp) },
            onSpkluClick = { s ->
                spkluDialogItem = s
            },
            onFieldNoteClick = { n ->
                selectPointOnMap(GeoPointX(n.lat, n.lng))
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
                val arriveTimeStr = remember(state.etaMin) {
                    val etaMs = System.currentTimeMillis() + (state.etaMin.coerceAtLeast(1.0) * 60_000.0).toLong()
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(etaMs))
                }
                val arrSoc = route.energyEstimate?.socAtArrivalPct
                val steepAhead = route.hillSegments.firstOrNull { it.steep }
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = c.surfaceCard.copy(alpha = 0.94f),
                    border = BorderStroke(1.5.dp, c.accent)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Navigation, contentDescription = null, tint = c.accent, modifier = Modifier.size(30.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${state.distToNextStepM.roundToInt()} m", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.accent)
                                Text(curStep.instruction, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(
                                onClick = { onSetRideLocked(true) },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("lock_screen_nav_button")
                            ) {
                                Icon(Icons.Rounded.Lock, contentDescription = "Kunci Layar Berkendara", tint = c.warning)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${state.etaMin.roundToInt()} mnt • tiba $arriveTimeStr • ${String.format(Locale.US, "%.1f", state.remainingDistKm)} km",
                                fontSize = 11.5.sp,
                                color = c.textMuted,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (arrSoc != null) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (arrSoc < 15.0) c.danger.copy(alpha = 0.18f) else c.accent.copy(alpha = 0.16f),
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable { showSpkluSheet = true }
                                ) {
                                    Text(
                                        "Tiba ${arrSoc.roundToInt()}%" +
                                            if (state.spkluAheadKm != null) " • SPKLU ${String.format(Locale.US, "%.1f", state.spkluAheadKm)}km" else "",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (arrSoc < 15.0) c.danger else c.accent,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                        if (steepAhead != null) {
                            Text(
                                "${if (steepAhead.isUphill) "▲ Tanjakan" else "▼ Turunan"} maks ${abs(steepAhead.maxGradePct).roundToInt()}% di km ${String.format(Locale.US, "%.1f", steepAhead.startDistM / 1000.0)}",
                                fontSize = 11.sp,
                                color = c.warning
                            )
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
                // Quick action pills below search bar (Charger Molis, Favorit, Simpan, Trip Planner)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val molisTotal = remember(state.nearbySpklu) {
                        state.nearbySpklu.count { it.isMolisFriendly }.takeIf { it > 0 } ?: state.nearbySpklu.size
                    }
                    Surface(
                        shape = CircleShape,
                        color = c.surfaceCard.copy(alpha = 0.96f),
                        border = BorderStroke(1.2.dp, c.accent.copy(alpha = 0.7f)),
                        modifier = Modifier
                            .height(48.dp)
                            .clip(CircleShape)
                            .clickable { showSpkluSheet = true }
                            .testTag("quick_molis_chip")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.ElectricMoped, contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (molisTotal > 0) "Charger Molis ($molisTotal)" else "Charger Molis / SPKLU",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = c.accent
                            )
                        }
                    }
                    Surface(
                        shape = CircleShape,
                        color = c.surfaceCard.copy(alpha = 0.94f),
                        border = BorderStroke(1.dp, c.borderSubtle),
                        modifier = Modifier
                            .height(48.dp)
                            .clip(CircleShape)
                            .clickable { showFavoritesSheet = true }
                            .testTag("quick_favorites_chip")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Star, contentDescription = null, tint = Color(0xFFFACC15), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Favorit", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Surface(
                        shape = CircleShape,
                        color = c.surfaceCard.copy(alpha = 0.94f),
                        border = BorderStroke(1.dp, c.borderSubtle),
                        modifier = Modifier
                            .height(38.dp)
                            .clip(CircleShape)
                            .clickable {
                                val target = state.plannedStops.lastOrNull()?.let {
                                    SavedPlace(it.id, it.label, "Disimpan", it.lat, it.lng, System.currentTimeMillis())
                                } ?: state.gpsState.location?.let { gp ->
                                    SavedPlace(
                                        id = "loc_${System.currentTimeMillis()}",
                                        name = state.gpsState.roadName.ifBlank { "Lokasi Tersimpan" },
                                        category = "Lokasi Saya",
                                        lat = gp.lat,
                                        lng = gp.lng,
                                        createdAtMs = System.currentTimeMillis()
                                    )
                                }
                                if (target != null) {
                                    onSavePlace(target)
                                } else {
                                    showFavoritesSheet = true
                                }
                            }
                            .testTag("quick_save_chip")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, tint = c.accent, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Simpan", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Surface(
                        shape = CircleShape,
                        color = c.surfaceCard.copy(alpha = 0.94f),
                        border = BorderStroke(1.dp, c.borderSubtle),
                        modifier = Modifier
                            .height(38.dp)
                            .clip(CircleShape)
                            .clickable { showMultiStopSheet = true }
                            .testTag("quick_trip_planner_chip")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Route, contentDescription = null, tint = c.accentSecondary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Trip Planner", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            if (plannerPickMode) {
                Surface(
                    shape = CircleShape,
                    color = c.accent,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Ketuk di peta untuk memilih titik tujuan",
                            color = Color.Black,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Batal pilih peta",
                            tint = Color.Black,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { plannerPickMode = false }
                        )
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

            // Compact live speed/telemetry pill on top-left when preview & popup aren't covering map
            if (!showRoutePreview && tappedPlace == null) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = c.surfaceCard.copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, c.borderSubtle),
                    modifier = Modifier.align(Alignment.Start)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "${state.effective.speedKmh?.roundToInt()?.toString() ?: "0"} km/h",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = c.accent
                        )
                        Text(
                            "${state.effective.combinedSocPct?.roundToInt()?.toString() ?: "—"}%",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "${state.effective.combinedPowerW?.roundToInt()?.toString() ?: "—"}W",
                            fontSize = 11.5.sp,
                            color = c.textMuted
                        )
                        if (state.settings.offlineModeOnly) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFF22C55E).copy(alpha = 0.22f)
                            ) {
                                Text(
                                    "OFFLINE",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF22C55E),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        } else if (state.offlineRouteActive) {
                            Surface(
                                shape = CircleShape,
                                color = c.accent.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    "RUTE OFFLINE",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = c.accent,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (state.isRerouting) {
                            Surface(
                                shape = CircleShape,
                                color = c.warning.copy(alpha = 0.25f)
                            ) {
                                Text(
                                    "REROUTE...",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = c.warning,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Right Map Floating Buttons (>=48dp touch targets)
        val rightButtonsBottom = when {
            showRoutePreview && route != null -> 310.dp
            tappedPlace != null -> 295.dp
            state.isNavigating -> 166.dp
            else -> 108.dp
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = rightButtonsBottom),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MapFabButton(
                icon = Icons.Rounded.Add,
                desc = "Perbesar Peta",
                tint = Color.White,
                testTag = "map_zoom_in_fab"
            ) {
                val maxZ = if (state.settings.dataSaverMap) 16.0 else 18.0
                mapZoom = (mapZoom + 1.0).coerceAtMost(maxZ)
            }
            MapFabButton(
                icon = Icons.Rounded.Remove,
                desc = "Perkecil Peta",
                tint = Color.White,
                testTag = "map_zoom_out_fab"
            ) {
                mapZoom = (mapZoom - 1.0).coerceAtLeast(5.0)
            }
            if (route != null && route.geometry.size >= 2) {
                MapFabButton(
                    icon = Icons.Rounded.FitScreen,
                    desc = "Lihat Seluruh Rute",
                    tint = c.accentSecondary,
                    testTag = "map_fit_route_fab"
                ) {
                    followRider = false
                    val lats = route.geometry.map { it.lat }
                    val lngs = route.geometry.map { it.lng }
                    mapCenter = GeoPointX((lats.min() + lats.max()) / 2.0, (lngs.min() + lngs.max()) / 2.0)
                    mapZoom = 12.8
                }
            }
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

        // Bottom Stack: Tapped Place Popup OR Route Preview OR Compact Active Navigation Bar
        val tp = tappedPlace
        val totalPackWh = state.settings.b1NominalV * state.settings.b1CapacityAh +
            if (state.settings.savedEsp2Address != null) state.settings.b2NominalV * state.settings.b2CapacityAh else 0.0

        if (tp != null) {
            MapPlacePopupCard(
                place = tp,
                currentSocPct = state.effective.combinedSocPct,
                refWhPerKm = state.settings.refWhPerKm,
                totalPackWh = totalPackWh,
                hasActiveStops = state.plannedStops.isNotEmpty(),
                onRouteHere = {
                    tappedPlace = null
                    onSetSingleDestination(
                        PlannedStop(
                            id = "tap_${System.currentTimeMillis()}",
                            label = tp.name,
                            lat = tp.point.lat,
                            lng = tp.point.lng
                        )
                    )
                },
                onAddStopHere = {
                    tappedPlace = null
                    onAddStop(
                        PlannedStop(
                            id = "stop_${System.currentTimeMillis()}",
                            label = tp.name,
                            lat = tp.point.lat,
                            lng = tp.point.lng
                        )
                    )
                },
                onSavePlace = {
                    onSavePlace(
                        SavedPlace(
                            id = "fav_${System.currentTimeMillis()}",
                            name = tp.name,
                            category = tp.address.ifBlank { "Pin Peta" },
                            lat = tp.point.lat,
                            lng = tp.point.lng,
                            createdAtMs = System.currentTimeMillis()
                        )
                    )
                    tappedPlace = null
                },
                onSharePlace = {
                    val shareStr = "${tp.name}\n${tp.address}\nhttps://www.google.com/maps?q=${tp.point.lat},${tp.point.lng}"
                    onShareText(shareStr.trim(), tp.name)
                },
                onOpenInExternalMaps = {
                    val uri = Uri.parse(
                        "geo:${tp.point.lat},${tp.point.lng}?q=${tp.point.lat},${tp.point.lng}(${Uri.encode(tp.name)})"
                    )
                    val geoIntent = Intent(Intent.ACTION_VIEW, uri)
                    runCatching { context.startActivity(geoIntent) }
                        .onFailure {
                            onShareText("https://www.google.com/maps?q=${tp.point.lat},${tp.point.lng}", tp.name)
                        }
                },
                onOpenCatatNama = {
                    catatTargetPoint = tp.point
                    tappedPlace = null
                },
                onReportToOsm = {
                    val osmUrl = String.format(
                        Locale.US,
                        "https://www.openstreetmap.org/note/new#map=19/%.6f/%.6f",
                        tp.point.lat,
                        tp.point.lng
                    )
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(osmUrl)))
                    }
                },
                onSaveAsSpklu = {
                    addSpkluTarget = tp.name to tp.point
                    tappedPlace = null
                },
                onDismiss = { tappedPlace = null },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 12.dp, bottom = 92.dp)
            )
        } else if (route != null && showRoutePreview) {
            val destStop = state.plannedStops.lastOrNull()
            val destLabel = destStop?.label ?: "Tujuan"
            val isFav = destStop != null && state.savedPlaces.any {
                abs(it.lat - destStop.lat) < 0.0005 && abs(it.lng - destStop.lng) < 0.0005
            }
            RoutePreviewSheetCard(
                state = state,
                route = route,
                destLabel = destLabel,
                isFavorite = isFav,
                detailsExpanded = previewDetailsExpanded,
                scrubSampleIndex = scrubSampleIdx,
                selectedHill = selectedHill,
                showAllHills = showAllHills,
                onToggleFavorite = {
                    if (destStop != null) {
                        val existing = state.savedPlaces.firstOrNull {
                            abs(it.lat - destStop.lat) < 0.0005 && abs(it.lng - destStop.lng) < 0.0005
                        }
                        if (existing != null) {
                            onRemoveSavedPlace(existing.id)
                        } else {
                            onSavePlace(
                                SavedPlace(
                                    id = "fav_${System.currentTimeMillis()}",
                                    name = destLabel,
                                    category = "Favorit Rute",
                                    lat = destStop.lat,
                                    lng = destStop.lng,
                                    createdAtMs = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                },
                onToggleDetails = { previewDetailsExpanded = !previewDetailsExpanded },
                onClosePreview = {
                    // CRITICAL FIX: During active navigation, X ONLY closes preview without stopping navigation!
                    showRoutePreview = false
                    if (!state.isNavigating) {
                        onClearRoute()
                    }
                },
                onSelectAlternative = { idx -> onSelectRouteAlternative(idx) },
                onScrubSample = { idx -> scrubSampleIdx = idx },
                onSelectHill = { h ->
                    selectedHill = if (selectedHill?.id == h.id) null else h
                    val midDist = (h.startDistM + h.endDistM) / 2.0 / 1000.0
                    val targetPt = route.elevationSamples.minByOrNull { abs(it.distanceKm - midDist) }?.point
                    if (targetPt != null) {
                        followRider = false
                        mapCenter = targetPt
                    }
                },
                onToggleShowAllHills = { showAllHills = !showAllHills },
                onStartOrPrepareNav = {
                    if (state.settings.tripPrepAsk) {
                        showTripPrepDialog = true
                    } else {
                        showRoutePreview = false
                        onStartNavigation()
                    }
                },
                onStopNavigation = {
                    showRoutePreview = false
                    onStopNavigation()
                },
                onOpenMultiStop = { showMultiStopSheet = true },
                onSaveDestAsSpklu = {
                    if (destStop != null) {
                        addSpkluTarget = destLabel to GeoPointX(destStop.lat, destStop.lng)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 12.dp, bottom = 92.dp)
            )
        } else if (route != null && state.isNavigating) {
            // Compact unobstructed bottom nav control bar during active navigation
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 92.dp),
                shape = RoundedCornerShape(18.dp),
                color = c.surfaceCard.copy(alpha = 0.95f),
                border = BorderStroke(1.dp, c.borderSubtle),
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${String.format(Locale.US, "%.1f", state.remainingDistKm)} km • ${state.etaMin.roundToInt()} mnt",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        val arrSoc = route.energyEstimate?.socAtArrivalPct
                        Text(
                            "Trip ${String.format(Locale.US, "%.1f", state.liveTrip.distanceKm)} km" +
                                if (arrSoc != null) " • Tiba ${arrSoc.roundToInt()}%" else "",
                            fontSize = 11.5.sp,
                            color = c.textMuted,
                            maxLines = 1
                        )
                    }
                    OutlinedButton(
                        onClick = { showRoutePreview = true },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("reopen_route_preview_button")
                    ) {
                        Text("Detail", fontSize = 12.sp)
                    }
                    Button(
                        onClick = onStopNavigation,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("stop_active_nav_button")
                    ) {
                        Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Stop", fontSize = 12.5.sp)
                    }
                }
            }
        } else if (route != null && !showRoutePreview) {
            // Collapsed pre-nav route pill so user can reopen preview anytime
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 12.dp, bottom = 92.dp)
                    .clickable { showRoutePreview = true },
                shape = CircleShape,
                color = c.surfaceCard.copy(alpha = 0.95f),
                border = BorderStroke(1.dp, c.accent)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Rounded.Route, contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp))
                    Text(
                        "Preview Rute (${String.format(Locale.US, "%.1f", state.remainingDistKm)} km)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
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
