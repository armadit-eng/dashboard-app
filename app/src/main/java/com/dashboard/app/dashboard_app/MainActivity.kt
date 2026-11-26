package com.dashboard.app.dashboard_app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Garage
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Garage
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dashboard.app.dashboard_app.model.TripSummaryCardData
import com.dashboard.app.dashboard_app.ui.components.DeviceConnectSheet
import com.dashboard.app.dashboard_app.ui.screens.DiagnosisScreen
import com.dashboard.app.dashboard_app.ui.screens.GarageAccountScreen
import com.dashboard.app.dashboard_app.ui.screens.HomeCockpitScreen
import com.dashboard.app.dashboard_app.ui.screens.MotovlogCamScreen
import com.dashboard.app.dashboard_app.ui.screens.NavigationScreen
import com.dashboard.app.dashboard_app.ui.screens.RidingCockpitScreen
import com.dashboard.app.dashboard_app.ui.screens.StatsScreen
import com.dashboard.app.dashboard_app.ui.theme.DashboardAppTheme
import com.dashboard.app.dashboard_app.ui.theme.LocalCockpitColors
import com.dashboard.app.dashboard_app.viewmodel.DashboardUiState
import com.dashboard.app.dashboard_app.viewmodel.DashboardViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Serializable
object RouteMainTabs

@Serializable
object RouteRidingCockpit

@Serializable
object RouteMotovlogCam

private data class NavTabDef(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val testTag: String
)

private val MAIN_TABS = listOf(
    NavTabDef("Kokpit", Icons.Outlined.Speed, Icons.Rounded.Speed, "nav_tab_kokpit"),
    NavTabDef("Diagnostik", Icons.Outlined.MonitorHeart, Icons.Rounded.MonitorHeart, "nav_tab_diagnostik"),
    NavTabDef("Navigasi", Icons.Outlined.Navigation, Icons.Rounded.Navigation, "nav_tab_navigasi"),
    NavTabDef("Statistik", Icons.Outlined.BarChart, Icons.Rounded.BarChart, "nav_tab_statistik"),
    NavTabDef("Garasi", Icons.Outlined.Garage, Icons.Rounded.Garage, "nav_tab_garasi")
)

private enum class PhotoPickTarget { AVATAR, WALLPAPER }

class MainActivity : ComponentActivity() {
    private var pendingSharedNavText by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        extractSharedNavText(intent)?.let { pendingSharedNavText = it }
        enableEdgeToEdge()
        setContent {
            val vm: DashboardViewModel = viewModel()
            val state by vm.uiState.collectAsStateWithLifecycle()

            LaunchedEffect(state.wakelockActive) {
                if (state.wakelockActive) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            DashboardAppTheme(
                isDark = state.settings.isDarkTheme,
                accentId = state.settings.accentId
            ) {
                DashboardRootApp(
                    viewModel = vm,
                    state = state,
                    sharedNavText = pendingSharedNavText,
                    onConsumeSharedNavText = { pendingSharedNavText = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractSharedNavText(intent)?.let { pendingSharedNavText = it }
    }

    private fun extractSharedNavText(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() }
            Intent.ACTION_VIEW -> intent.dataString?.trim()?.takeIf { it.isNotEmpty() }
            else -> null
        }
    }
}

@Composable
private fun DashboardRootApp(
    viewModel: DashboardViewModel,
    state: DashboardUiState,
    sharedNavText: String?,
    onConsumeSharedNavText: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    var currentTab by rememberSaveable { mutableIntStateOf(0) }
    var showDeviceSheet by remember { mutableStateOf(false) }
    var pickTarget by remember { mutableStateOf(PhotoPickTarget.AVATAR) }

    LaunchedEffect(sharedNavText) {
        val txt = sharedNavText ?: return@LaunchedEffect
        currentTab = 2
        viewModel.handleSharedNavigationText(txt) { msg ->
            scope.launch { snackbarHostState.showSnackbar(msg) }
        }
        onConsumeSharedNavText()
    }

    fun isGranted(perm: String): Boolean =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    val singleGroupPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.startGpsAndWatchdog()
    }

    // Sequential step state triggers next dialog cleanly after Compose recomposes
    var startupPermIdx by rememberSaveable { mutableIntStateOf(0) }
    val startupBatches = remember {
        buildList {
            val loc = arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ).filterNot(::isGranted).toTypedArray()
            if (loc.isNotEmpty()) add(loc)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val bt = arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                ).filterNot(::isGranted).toTypedArray()
                if (bt.isNotEmpty()) add(bt)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (!isGranted(Manifest.permission.POST_NOTIFICATIONS)) {
                    add(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
            }
        }
    }

    val sequentialPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.startGpsAndWatchdog()
        startupPermIdx++
    }

    LaunchedEffect(startupPermIdx) {
        if (startupPermIdx in startupBatches.indices) {
            sequentialPermLauncher.launch(startupBatches[startupPermIdx])
        } else {
            viewModel.startGpsAndWatchdog()
        }
    }

    fun requestLocationOrRetryGps() {
        val hasAnyLoc = isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!hasAnyLoc) {
            singleGroupPermLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            viewModel.startGpsAndWatchdog()
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val path = viewModel.copyPickedImage(uri)
                if (path != null) {
                    when (pickTarget) {
                        PhotoPickTarget.AVATAR -> {
                            viewModel.updateSettings(state.settings.copy(avatarPath = path))
                            snackbarHostState.showSnackbar("Foto pengendara diperbarui.")
                        }
                        PhotoPickTarget.WALLPAPER -> {
                            viewModel.updateSettings(
                                state.settings.copy(
                                    customWallpaperPath = path,
                                    customWallpaperEnabled = true
                                )
                            )
                            snackbarHostState.showSnackbar("Wallpaper MID diperbarui.")
                        }
                    }
                }
            }
        }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val data = res.data
        if (res.resultCode == Activity.RESULT_OK && data != null) {
            viewModel.startScreenMirrorWithGrant(res.resultCode, data)
            scope.launch { snackbarHostState.showSnackbar("Screen mirror ke MID dimulai.") }
        } else {
            scope.launch { snackbarHostState.showSnackbar("Izin screen mirror dibatalkan.") }
        }
    }

    fun launchShare(text: String, subject: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, subject))
    }

    NavHost(
        navController = navController,
        startDestination = RouteMainTabs
    ) {
        composable<RouteMainTabs> {
            val c = LocalCockpitColors.current
            Scaffold(
                containerColor = c.background,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                contentWindowInsets = WindowInsets(0, 0, 0, 0)
            ) { innerPadding ->
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    val useRail = maxWidth >= 720.dp
                    val screenMod = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars)

                    if (useRail) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            NavigationRail(
                                containerColor = c.surfaceCard,
                                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)
                            ) {
                                MAIN_TABS.forEachIndexed { idx, tab ->
                                    val selected = currentTab == idx
                                    NavigationRailItem(
                                        selected = selected,
                                        onClick = { currentTab = idx },
                                        icon = {
                                            Icon(
                                                if (selected) tab.selectedIcon else tab.icon,
                                                contentDescription = tab.label,
                                                tint = if (selected) c.accent else c.textMuted
                                            )
                                        },
                                        label = { Text(tab.label, fontSize = 11.sp) },
                                        modifier = Modifier.testTag(tab.testTag)
                                    )
                                }
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                MainTabContent(
                                    currentTab = currentTab,
                                    screenMod = screenMod,
                                    state = state,
                                    viewModel = viewModel,
                                    isGranted = ::isGranted,
                                    onLaunchPerms = { singleGroupPermLauncher.launch(it) },
                                    onShowDeviceSheet = { showDeviceSheet = true },
                                    onChangeTab = { currentTab = it },
                                    onOpenRidingCockpit = { navController.navigate(RouteRidingCockpit) },
                                    onOpenMotovlogCam = { navController.navigate(RouteMotovlogCam) },
                                    onShowSnackbar = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                                    onRequestScreenMirror = {
                                        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                        projectionLauncher.launch(mpm.createScreenCaptureIntent())
                                    },
                                    onPickWallpaperImage = {
                                        pickTarget = PhotoPickTarget.WALLPAPER
                                        photoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    onPickAvatarImage = {
                                        pickTarget = PhotoPickTarget.AVATAR
                                        photoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    onRetryGps = { requestLocationOrRetryGps() },
                                    onShareText = ::launchShare
                                )
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize()) {
                            MainTabContent(
                                currentTab = currentTab,
                                screenMod = screenMod,
                                state = state,
                                viewModel = viewModel,
                                isGranted = ::isGranted,
                                onLaunchPerms = { singleGroupPermLauncher.launch(it) },
                                onShowDeviceSheet = { showDeviceSheet = true },
                                onChangeTab = { currentTab = it },
                                onOpenRidingCockpit = { navController.navigate(RouteRidingCockpit) },
                                onOpenMotovlogCam = { navController.navigate(RouteMotovlogCam) },
                                onShowSnackbar = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                                onRequestScreenMirror = {
                                    val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                    projectionLauncher.launch(mpm.createScreenCaptureIntent())
                                },
                                onPickWallpaperImage = {
                                    pickTarget = PhotoPickTarget.WALLPAPER
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                onPickAvatarImage = {
                                    pickTarget = PhotoPickTarget.AVATAR
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                onRetryGps = { requestLocationOrRetryGps() },
                                onShareText = ::launchShare
                            )

                            // Floating Bottom Navigation Pill
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .windowInsetsPadding(WindowInsets.navigationBars)
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                                    .fillMaxWidth()
                                    .height(66.dp),
                                shape = RoundedCornerShape(22.dp),
                                color = c.surfaceCard.copy(alpha = 0.96f),
                                border = BorderStroke(1.dp, c.borderSubtle),
                                shadowElevation = 10.dp
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    MAIN_TABS.forEachIndexed { idx, tab ->
                                        val selected = currentTab == idx
                                        NavigationBarItem(
                                            selected = selected,
                                            onClick = { currentTab = idx },
                                            icon = {
                                                Icon(
                                                    if (selected) tab.selectedIcon else tab.icon,
                                                    contentDescription = tab.label,
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            },
                                            label = {
                                                Text(
                                                    tab.label,
                                                    fontSize = 10.5.sp,
                                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                    maxLines = 1
                                                )
                                            },
                                            alwaysShowLabel = true,
                                            modifier = Modifier.testTag(tab.testTag)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (showDeviceSheet) {
                DeviceConnectSheet(
                    state = state,
                    onDismiss = { showDeviceSheet = false },
                    onScanDevices = { viewModel.loadBondedAndScanBle() },
                    onConnectMotor = { addr, blePref -> viewModel.connectMotor(addr, blePref) },
                    onDisconnectMotor = { viewModel.disconnectMotor() },
                    onConnectJkBms = { addr -> viewModel.connectJkBms(addr) },
                    onDisconnectJkBms = { viewModel.disconnectJkBms() },
                    onConnectEspSlot = { slot, addr -> viewModel.connectEspSlot(slot, addr) },
                    onDisconnectEspSlot = { slot -> viewModel.disconnectEspSlot(slot) },
                    onChangeBatteryPriority = { prio ->
                        viewModel.updateSettings(state.settings.copy(batteryPriorityId = prio.id))
                    },
                    onSelectControllerProtocol = { proto ->
                        viewModel.selectControllerProtocol(proto)
                    },
                    onSelectBmsProtocol = { proto ->
                        viewModel.selectBmsProtocol(proto)
                    }
                )
            }

            state.tripSummaryCard?.let { sum ->
                TripSummaryDialog(
                    summary = sum,
                    onDismiss = { viewModel.dismissTripSummaryCard() }
                )
            }
        }

        composable<RouteRidingCockpit> {
            RidingCockpitScreen(
                state = state,
                onUpdateLayout = { layout ->
                    viewModel.updateSettings(state.settings.copy(cockpitLayout = layout))
                },
                onSetWakelock = { viewModel.setWakelock(it) },
                onClose = { navController.popBackStack() }
            )
        }

        composable<RouteMotovlogCam> {
            MotovlogCamScreen(
                state = state,
                onClose = { navController.popBackStack() }
            )
        }
    }
}

@Composable
private fun MainTabContent(
    currentTab: Int,
    screenMod: Modifier,
    state: DashboardUiState,
    viewModel: DashboardViewModel,
    isGranted: (String) -> Boolean,
    onLaunchPerms: (Array<String>) -> Unit,
    onShowDeviceSheet: () -> Unit,
    onChangeTab: (Int) -> Unit,
    onOpenRidingCockpit: () -> Unit,
    onOpenMotovlogCam: () -> Unit,
    onShowSnackbar: (String) -> Unit,
    onRequestScreenMirror: () -> Unit,
    onPickWallpaperImage: () -> Unit,
    onPickAvatarImage: () -> Unit,
    onRetryGps: () -> Unit,
    onShareText: (String, String) -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        val boundedMod = if (currentTab == 2) {
            screenMod
        } else {
            screenMod.widthIn(max = 760.dp)
        }
        when (currentTab) {
            0 -> HomeCockpitScreen(
                state = state,
                onOpenDeviceSheet = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val missingBt = arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ).filterNot(isGranted).toTypedArray()
                        if (missingBt.isNotEmpty()) {
                            onLaunchPerms(missingBt)
                        }
                    }
                    onShowDeviceSheet()
                },
                onRefreshWeather = { viewModel.refreshWeatherAndLocation() },
                onOpenTab = onChangeTab,
                onOpenRidingCockpit = onOpenRidingCockpit,
                onOpenMotovlogCam = onOpenMotovlogCam,
                onSaveTrip = {
                    viewModel.saveCurrentTripAndReset()
                    onShowSnackbar("Trip disimpan ke riwayat.")
                },
                onResetTrip = { viewModel.resetLiveTrip() },
                onRouteToParked = { stop ->
                    viewModel.setSingleDestination(stop)
                    onChangeTab(2)
                },
                onAddMaintenanceLog = { odo, type ->
                    viewModel.addMaintenanceLog(odo, type)
                    onShowSnackbar("Catatan servis tersimpan.")
                },
                onToggleDevSim = { viewModel.toggleDevTelemetrySimulation(it) },
                onSendMotorAt = { viewModel.sendMotorCommand(it) },
                onDismissIdleDrain = { viewModel.dismissIdleDrainWarning() },
                modifier = boundedMod
            )
            1 -> DiagnosisScreen(
                state = state,
                onRestoreOdo = { viewModel.restoreMotorOdo(it) },
                onRefreshMidBattery = {
                    viewModel.refreshMidBatteryTelemetry()
                    onShowSnackbar("Mengirim ulang permintaan AT+CBAT, SOC, SOH ke MID…")
                },
                onSetInvertB2Current = {
                    viewModel.updateSettings(state.settings.copy(invertB2Current = it))
                },
                onSendEsp2Command = { viewModel.sendEspCommand(2, it) },
                onLogChargeSession = { sSoc, eSoc, wh, dur ->
                    viewModel.logManualChargeSession(sSoc, eSoc, wh, dur)
                    onShowSnackbar("Sesi pengisian dicatat.")
                },
                onUpdateSettings = { updated -> viewModel.updateSettings(updated) },
                modifier = boundedMod
            )
            2 -> NavigationScreen(
                state = state,
                onSetSingleDestination = { viewModel.setSingleDestination(it) },
                onAddStop = { viewModel.addStopAndRoute(it) },
                onRemoveStopAt = { viewModel.removeStopAt(it) },
                onReorderStop = { from, to -> viewModel.reorderStop(from, to) },
                onSelectRouteAlternative = { idx -> viewModel.selectRouteAlternative(idx) },
                onClearRoute = { viewModel.clearRoute() },
                onStartNavigation = { viewModel.startNavigation() },
                onStopNavigation = { viewModel.stopNavigation() },
                onRefreshSpklu = { done -> viewModel.refreshNearbySpklu(done) },
                onSyncSpkluUrl = { url, cb ->
                    viewModel.syncCustomSpkluUrl(url) { count, msg ->
                        onShowSnackbar(msg)
                        cb(count, msg)
                    }
                },
                onAddUserSpklu = { n, sock, lat, lng, kw, notes, isMolis ->
                    viewModel.addUserSpklu(n, sock, lat, lng, kw, notes, isMolis)
                    onShowSnackbar("Titik charger disimpan: $n")
                },
                onRemoveUserSpklu = { id ->
                    viewModel.removeUserSpklu(id)
                    onShowSnackbar("Titik charger dihapus.")
                },
                onImportSpkluUri = { uri, fileName, cb ->
                    viewModel.importSpkluFromUri(uri, fileName) { count, msg ->
                        onShowSnackbar(msg)
                        cb(count, msg)
                    }
                },
                onAddFieldNoteAt = { n, cat, note, lat, lng ->
                    viewModel.addFieldNoteAt(n, cat, note, lat, lng)
                    onShowSnackbar("Pin disimpan: $n")
                },
                onSavePlace = {
                    viewModel.savePlace(it)
                    onShowSnackbar("Tempat disimpan: ${it.name}")
                },
                onRemoveSavedPlace = { id -> viewModel.removeSavedPlace(id) },
                onStartMidStreaming = { viewModel.startMidStreaming() },
                onStopMidStreaming = { viewModel.stopMidStreaming() },
                onRequestScreenMirror = onRequestScreenMirror,
                onStopScreenMirror = { viewModel.stopScreenMirror() },
                onPickWallpaperImage = onPickWallpaperImage,
                onUpdateSettings = { viewModel.updateSettings(it) },
                onSetRideLocked = { viewModel.setRideLocked(it) },
                onToggleOfflineMode = { viewModel.setOfflineMode(it) },
                onRefreshOfflineRegions = { viewModel.refreshOfflineRegions() },
                onDeleteOfflineRegion = { viewModel.deleteOfflineRegion(it) },
                onRetryGps = onRetryGps,
                gpsDiagTextProvider = { viewModel.gpsDiagText() },
                onShareText = onShareText,
                modifier = screenMod
            )
            3 -> StatsScreen(
                state = state,
                onApplyCalFactor = { factor ->
                    viewModel.updateSettings(state.settings.copy(routeCalFactor = factor))
                    onShowSnackbar("Faktor kalibrasi diterapkan ($factor)")
                },
                onTogglePhysAutoCal = { enabled ->
                    viewModel.updateSettings(state.settings.copy(physAutoCal = enabled))
                },
                onDeleteTrip = { viewModel.deleteTrip(it) },
                onShareText = onShareText,
                modifier = boundedMod
            )
            else -> GarageAccountScreen(
                state = state,
                onUpdateSettings = { viewModel.updateSettings(it) },
                onOpenMotovlogCam = onOpenMotovlogCam,
                onPickAvatarImage = onPickAvatarImage,
                onSetContactRelay = { viewModel.setContactRelay(it) },
                onShowMessage = onShowSnackbar,
                onSetMatrixPower = { viewModel.setMatrixPower(it) },
                onToggleMatrixAutoRotate = { viewModel.toggleMatrixAutoRotate(it) },
                onAddMatrixItem = { text, effectId -> viewModel.addMatrixItem(text, 45, effectId) },
                onSendMatrixItemNow = { viewModel.sendMatrixItemNow(it) },
                onDeleteMatrixItem = { viewModel.deleteMatrixItem(it) },
                onSendEsp1Command = { viewModel.sendEspCommand(1, it) },
                modifier = boundedMod
            )
        }
    }
}

@Composable
private fun TripSummaryDialog(
    summary: TripSummaryCardData,
    onDismiss: () -> Unit
) {
    val c = LocalCockpitColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceCard,
        title = {
            Text(
                summary.title.ifBlank { "Ringkasan Perjalanan" },
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TripSummaryStat("Jarak", String.format(Locale.US, "%.2f km", summary.distanceKm))
                    TripSummaryStat("Durasi", "${summary.durationMin} mnt")
                    TripSummaryStat("Energi", "${summary.energyWh.roundToInt()} Wh")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TripSummaryStat("Efisiensi", String.format(Locale.US, "%.1f Wh/km", summary.whPerKm))
                    TripSummaryStat("Regen", "${summary.regenWh.roundToInt()} Wh")
                    TripSummaryStat("Skor", summary.ridingScore?.toString() ?: "—")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TripSummaryStat("Max Speed", "${summary.maxSpeedKmh.roundToInt()} km/j")
                    TripSummaryStat("Avg Speed", "${summary.avgSpeedKmh.roundToInt()} km/j")
                    TripSummaryStat("Max Power", "${summary.maxPowerW.roundToInt()} W")
                }
                if (summary.predictedWh != null) {
                    HorizontalDivider(color = c.borderSubtle)
                    val errLabel = summary.errorPct?.let { err ->
                        val sign = if (err >= 0) "+" else ""
                        " ($sign${err.roundToInt()}%)"
                    }.orEmpty()
                    Text(
                        "Prediksi vs Aktual: ${summary.predictedWh.roundToInt()} Wh → ${summary.energyWh.roundToInt()} Wh$errLabel",
                        fontSize = 12.sp,
                        color = if ((summary.errorPct?.let { abs(it) } ?: 0.0) <= 15.0) c.accent else c.warning
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.testTag("trip_summary_dismiss_button")
            ) {
                Text("Tutup")
            }
        }
    )
}

@Composable
private fun TripSummaryStat(label: String, value: String) {
    val c = LocalCockpitColors.current
    Column {
        Text(label, fontSize = 11.sp, color = c.textMuted)
        Text(value, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
    }
}

