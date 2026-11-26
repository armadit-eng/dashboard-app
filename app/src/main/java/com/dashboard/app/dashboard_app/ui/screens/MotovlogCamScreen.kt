package com.dashboard.app.dashboard_app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.dashboard.app.dashboard_app.engine.RoutePhysics
import com.dashboard.app.dashboard_app.hardware.MotovlogCameraController
import com.dashboard.app.dashboard_app.hardware.RecordedVideoResult
import com.dashboard.app.dashboard_app.model.GeoPointX
import com.dashboard.app.dashboard_app.model.MapStyleOption
import com.dashboard.app.dashboard_app.ui.components.NfsRadarMinimap
import com.dashboard.app.dashboard_app.ui.theme.LocalCockpitColors
import com.dashboard.app.dashboard_app.viewmodel.DashboardUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun MotovlogCamScreen(
    state: DashboardUiState,
    onClose: () -> Unit,
    onEnsureGps: () -> Unit = {}
) {
    val c = LocalCockpitColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val controller = remember { MotovlogCameraController(context) }
    var isRecording by remember { mutableStateOf(false) }
    var camError by remember { mutableStateOf<String?>(null) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lastSavedVideo by remember { mutableStateOf<RecordedVideoResult?>(null) }
    var radarExpanded by remember { mutableStateOf(false) }

    val gpsBreadcrumbs = remember { mutableStateListOf<GeoPointX>() }

    var hasCamPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasCamPermission = grants[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        hasLocationPermission = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (hasLocationPermission) {
            onEnsureGps()
        }
        if (hasCamPermission) {
            camError = null
            controller.openCamera()
        } else {
            camError = "Izin kamera ditolak. Berikan izin kamera agar mode Motovlog berfungsi."
        }
    }

    val locationOnlyLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasLocationPermission = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasLocationPermission) {
            onEnsureGps()
        }
    }

    LaunchedEffect(state.gpsState.location) {
        val loc = state.gpsState.location ?: return@LaunchedEffect
        val prev = gpsBreadcrumbs.lastOrNull()
        if (prev == null || RoutePhysics.haversineKm(prev, loc) >= 0.008) {
            gpsBreadcrumbs.add(loc)
            if (gpsBreadcrumbs.size > 90) {
                gpsBreadcrumbs.removeAt(0)
            }
        }
    }

    LaunchedEffect(Unit) {
        controller.onRecordingChanged = { rec -> isRecording = rec }
        controller.onError = { err -> camError = err }
        if (hasLocationPermission) {
            onEnsureGps()
        }
        if (!hasCamPermission) {
            permLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
        while (isActive) {
            nowMs = System.currentTimeMillis()
            delay(1000L)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            controller.closeCamera()
        }
    }

    BackHandler {
        controller.closeCamera()
        onClose()
    }

    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val eff = state.effective
    val allChargers = remember(state.nearbySpklu, state.userSpklu) {
        state.nearbySpklu + state.userSpklu
    }

    Scaffold(
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black)
        ) {
            val isShortLandscape = maxHeight < 460.dp
            val radarCompact = if (isShortLandscape) 118.dp else 150.dp
            val radarExpandedSize = if (isShortLandscape) 160.dp else 198.dp

            if (hasCamPermission) {
                AndroidView(
                    factory = { controller.textureView },
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (camError != null || !hasCamPermission) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xCC000000))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.VideocamOff, contentDescription = null, tint = c.warning, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = camError ?: "Izin kamera diperlukan untuk merekam Motovlog HUD.",
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                permLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.CAMERA,
                                        Manifest.permission.RECORD_AUDIO,
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            },
                            modifier = Modifier.testTag("grant_camera_perm_button")
                        ) {
                            Text("Izinkan Kamera & Lokasi")
                        }
                    }
                }
            }

            // Top HUD Overlay
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0x99000000)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            timeFmt.format(Date(nowMs)),
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        if (state.gpsState.areaName.isNotEmpty()) {
                            Text(" • ${state.gpsState.areaName}", color = Color(0xFFE2E8F0), fontSize = 12.sp)
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                if (isRecording) {
                    Surface(
                        shape = CircleShape,
                        color = c.danger
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(Color.White, CircleShape)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("REC", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }

                Surface(
                    shape = CircleShape,
                    color = Color(0x99000000)
                ) {
                    IconButton(
                        onClick = {
                            controller.closeCamera()
                            onClose()
                        },
                        modifier = Modifier.testTag("close_motovlog_button")
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "Tutup Motovlog", tint = Color.White)
                    }
                }
            }

            // Bottom HUD Stack: NFS Most Wanted Radar Minimap + Telemetry Bar & Record Controls
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                NfsRadarMinimap(
                    riderLocation = state.gpsState.location,
                    riderHeadingDeg = state.gpsState.headingDeg ?: 0.0,
                    speedKmh = eff.speedKmh ?: state.gpsState.speedKmh,
                    roadName = state.gpsState.roadName,
                    areaName = state.gpsState.areaName,
                    mapStyle = MapStyleOption.fromId(state.settings.mapStyleId),
                    cartoApiKey = state.settings.cartoApiKey,
                    routeGeometry = state.activeRoute?.geometry ?: emptyList(),
                    plannedStops = state.plannedStops,
                    spkluStations = allChargers,
                    breadcrumbPoints = gpsBreadcrumbs,
                    hasLocationPermission = hasLocationPermission,
                    compactSize = radarCompact,
                    expandedSize = radarExpandedSize,
                    isExpanded = radarExpanded,
                    onToggleExpand = { radarExpanded = !radarExpanded },
                    onRequestLocationPermission = {
                        locationOnlyLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xB3070B12),
                        border = BorderStroke(1.dp, c.accent.copy(alpha = 0.6f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            HudBlock(
                                value = eff.speedKmh?.roundToInt()?.toString() ?: "—",
                                unit = "km/h",
                                color = c.accent
                            )
                            HudBlock(
                                value = "${eff.combinedSocPct?.roundToInt()?.toString() ?: "—"}%",
                                unit = "SOC",
                                color = Color.White
                            )
                            HudBlock(
                                value = "${eff.combinedPowerW?.roundToInt()?.toString() ?: "—"}W",
                                unit = "Daya",
                                color = c.accentSecondary
                            )
                            HudBlock(
                                value = String.format(Locale.US, "%.1f", state.liveTrip.distanceKm),
                                unit = "km Trip",
                                color = Color.White
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (lastSavedVideo != null && !isRecording) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xB3070B12),
                                border = BorderStroke(1.dp, c.accent.copy(alpha = 0.6f))
                            ) {
                                IconButton(
                                    onClick = {
                                        lastSavedVideo?.let { saved ->
                                            val intent = controller.buildOpenVideoIntent(saved)
                                            runCatching { context.startActivity(intent) }.onFailure {
                                                scope.launch {
                                                    snackbarHostState.showSnackbar("Video tersimpan di ${saved.userReadableLocation}")
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.testTag("motovlog_open_gallery_button")
                                ) {
                                    Icon(
                                        Icons.Rounded.VideoLibrary,
                                        contentDescription = "Buka video motovlog di Galeri",
                                        tint = Color.White
                                    )
                                }
                            }
                        }

                        ExtendedFloatingActionButton(
                            onClick = {
                                if (!hasCamPermission) {
                                    permLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.CAMERA,
                                            Manifest.permission.RECORD_AUDIO,
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                    return@ExtendedFloatingActionButton
                                }
                                if (isRecording) {
                                    val saved = controller.stopRecording()
                                    if (saved != null) {
                                        lastSavedVideo = saved
                                    }
                                    scope.launch {
                                        if (saved != null) {
                                            val res = snackbarHostState.showSnackbar(
                                                message = "Disimpan ke ${saved.userReadableLocation}",
                                                actionLabel = "Buka",
                                                duration = SnackbarDuration.Long
                                            )
                                            if (res == SnackbarResult.ActionPerformed) {
                                                val intent = controller.buildOpenVideoIntent(saved)
                                                runCatching { context.startActivity(intent) }
                                            }
                                        } else {
                                            snackbarHostState.showSnackbar("Rekaman dihentikan.")
                                        }
                                    }
                                } else {
                                    val started = controller.startRecording()
                                    if (!started) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Gagal memulai perekaman video.")
                                        }
                                    }
                                }
                            },
                            containerColor = if (isRecording) c.danger else c.accent,
                            contentColor = if (isRecording) Color.White else Color.Black,
                            modifier = Modifier.testTag("motovlog_record_button")
                        ) {
                            Icon(
                                if (isRecording) Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord,
                                contentDescription = null
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (isRecording) "Stop" else "Rekam",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HudBlock(value: String, unit: String, color: Color) {
    Column {
        Text(value, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(unit, color = Color(0xFF94A3B8), fontSize = 10.sp)
    }
}
