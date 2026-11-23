package com.dashboard.app.dashboard_app.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dashboard.app.dashboard_app.ScreenMirrorService
import com.dashboard.app.dashboard_app.engine.RoutePhysics
import com.dashboard.app.dashboard_app.engine.TelemetryEngine
import com.dashboard.app.dashboard_app.hardware.BleGattManager
import com.dashboard.app.dashboard_app.hardware.BtClassicManager
import com.dashboard.app.dashboard_app.hardware.LocationAndTtsManager
import com.dashboard.app.dashboard_app.hardware.NativeGpsFix
import com.dashboard.app.dashboard_app.model.AppAlert
import com.dashboard.app.dashboard_app.model.AppSettings
import com.dashboard.app.dashboard_app.model.Battery2Data
import com.dashboard.app.dashboard_app.model.CalibrationEntry
import com.dashboard.app.dashboard_app.model.ConnectionStateX
import com.dashboard.app.dashboard_app.model.DiscoveredDeviceX
import com.dashboard.app.dashboard_app.model.EffectiveTelemetry
import com.dashboard.app.dashboard_app.model.Esp32Data
import com.dashboard.app.dashboard_app.model.FieldNotePin
import com.dashboard.app.dashboard_app.model.GeoPointX
import com.dashboard.app.dashboard_app.model.GpsState
import com.dashboard.app.dashboard_app.model.JkBmsData
import com.dashboard.app.dashboard_app.model.LiveTripMetrics
import com.dashboard.app.dashboard_app.model.MaintenanceLogEntry
import com.dashboard.app.dashboard_app.model.MatrixQueueItem
import com.dashboard.app.dashboard_app.model.MotorData
import com.dashboard.app.dashboard_app.model.NavRouteX
import com.dashboard.app.dashboard_app.model.ParkedLocation
import com.dashboard.app.dashboard_app.model.PlannedStop
import com.dashboard.app.dashboard_app.model.RiderBadge
import com.dashboard.app.dashboard_app.model.RidingScoreState
import com.dashboard.app.dashboard_app.model.SavedPlace
import com.dashboard.app.dashboard_app.model.SerialLogEntry
import com.dashboard.app.dashboard_app.model.SpkluStation
import com.dashboard.app.dashboard_app.model.TelemetryPoint
import com.dashboard.app.dashboard_app.model.TripRecord
import com.dashboard.app.dashboard_app.model.WeatherData
import com.dashboard.app.dashboard_app.service.MidFrameRenderer
import com.dashboard.app.dashboard_app.service.PersistenceService
import com.dashboard.app.dashboard_app.service.RemoteApisService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

data class DashboardUiState(
    val settings: AppSettings = AppSettings(),
    val motorData: MotorData = MotorData(),
    val jkData: JkBmsData = JkBmsData(),
    val battery2Data: Battery2Data = Battery2Data(),
    val esp1Data: Esp32Data = Esp32Data(),
    val motorConnState: ConnectionStateX = ConnectionStateX.DISCONNECTED,
    val motorConnName: String = "",
    val motorViaBle: Boolean = false,
    val jkConnState: ConnectionStateX = ConnectionStateX.DISCONNECTED,
    val jkConnName: String = "",
    val esp1ConnState: ConnectionStateX = ConnectionStateX.DISCONNECTED,
    val esp1ConnName: String = "",
    val esp2ConnState: ConnectionStateX = ConnectionStateX.DISCONNECTED,
    val esp2ConnName: String = "",
    val pairedBtDevices: List<DiscoveredDeviceX> = emptyList(),
    val scannedBleDevices: List<DiscoveredDeviceX> = emptyList(),
    val isScanningBle: Boolean = false,
    val liveTrip: LiveTripMetrics = LiveTripMetrics(),
    val ridingScore: RidingScoreState = RidingScoreState(),
    val liveChartPoints: List<TelemetryPoint> = emptyList(),
    val serialLogs: List<SerialLogEntry> = emptyList(),
    val activeAlerts: List<AppAlert> = emptyList(),
    val gpsState: GpsState = GpsState(),
    val weather: WeatherData? = null,
    val parkedLocation: ParkedLocation? = null,
    val tripHistory: List<TripRecord> = emptyList(),
    val savedPlaces: List<SavedPlace> = emptyList(),
    val nearbySpklu: List<SpkluStation> = emptyList(),
    val userSpklu: List<SpkluStation> = emptyList(),
    val fieldNotes: List<FieldNotePin> = emptyList(),
    val maintLogs: List<MaintenanceLogEntry> = emptyList(),
    val calibLogs: List<CalibrationEntry> = emptyList(),
    val matrixQueue: List<MatrixQueueItem> = emptyList(),
    val matrixAutoRotate: Boolean = false,
    val plannedStops: List<PlannedStop> = emptyList(),
    val activeRoute: NavRouteX? = null,
    val isLoadingRoute: Boolean = false,
    val isNavigating: Boolean = false,
    val activeStepIndex: Int = 0,
    val distToNextStepM: Double = 0.0,
    val remainingDistKm: Double = 0.0,
    val etaMin: Double = 0.0,
    val isRideLocked: Boolean = false,
    val devSimActive: Boolean = false,
    val midStreaming: Boolean = false,
    val screenMirrorActive: Boolean = false,
    val mapsFramesSent: Int = 0,
    val mapsLastMs: Int = 0,
    val mapsLastChars: Int = 0,
    val mapsStatus: String = "Belum ada frame",
    val effective: EffectiveTelemetry = EffectiveTelemetry(),
    val badges: List<RiderBadge> = emptyList(),
    val wakelockActive: Boolean = false
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val storage = PersistenceService(application)
    private val btClassic = BtClassicManager(application)
    private val ble = BleGattManager(application)
    private val locationTts = LocationAndTtsManager(application)

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var wasMoving = false
    private var lastIntegrableSoc: Double? = null
    private val livePathPoints = mutableListOf<GeoPointX>()
    private var matrixIdx = 0
    private var atNavCursor = 0
    private var atNavLastSentSec = 0L
    private var lastGpsFixMs = 0L
    private var lastWeatherFetchMs = 0L
    private var lastGeoFetchMs = 0L
    private var lastOffRouteCheckMs = 0L
    private var tripStartArea = ""
    private var activeRouteInitialSoc: Double? = null

    private var tick1HzJob: Job? = null
    private var gpsWatchdogJob: Job? = null
    private var mapsStreamJob: Job? = null
    private var devSimJob: Job? = null

    init {
        wireHardwareCallbacks()
        initialize()
    }

    private fun initialize() {
        viewModelScope.launch {
            val settings = storage.loadSettings()
            val parked = storage.loadParkedLocation()
            val trips = storage.loadTrips()
            val places = storage.loadPlaces()
            val uSpklu = storage.loadUserSpklu()
            val notes = storage.loadFieldNotes()
            val maint = storage.loadMaintLogs()
            val calib = storage.loadCalibrationLogs()
            val matrix = storage.loadMatrixQueue()
            val cachedGeo = storage.loadCachedGeocode()

            ble.invertB2Current = settings.invertB2Current

            _uiState.update { cur ->
                val gps = if (cachedGeo != null) {
                    cur.gpsState.copy(
                        roadName = cachedGeo.road,
                        areaName = cachedGeo.area,
                        isFromCache = true
                    )
                } else cur.gpsState

                cur.copy(
                    settings = settings,
                    parkedLocation = parked,
                    tripHistory = trips,
                    savedPlaces = places,
                    userSpklu = uSpklu,
                    fieldNotes = notes,
                    maintLogs = maint,
                    calibLogs = calib,
                    matrixQueue = matrix,
                    gpsState = gps
                ).recomputeDerived()
            }

            start1HzEngine()
            startGpsAndWatchdog()
            refreshWeatherAndLocation()

            if (settings.autoReconnect) {
                attemptAutoReconnect()
            }
        }
    }

    private fun DashboardUiState.recomputeDerived(): DashboardUiState {
        val eff = TelemetryEngine.computeEffective(
            motor = motorData,
            motorState = motorConnState,
            jk = jkData,
            jkState = jkConnState,
            esp1 = esp1Data,
            esp1State = esp1ConnState,
            b2 = battery2Data,
            esp2State = esp2ConnState,
            gpsSpeedKmh = gpsState.speedKmh,
            trip = liveTrip,
            settings = settings
        )
        val totalKm = tripHistory.sumOf { it.distanceKm } + liveTrip.distanceKm
        val totalRegen = tripHistory.sumOf { it.regenWh } + liveTrip.regenWh
        val bestScore = tripHistory.mapNotNull { it.ridingScore }.fold(ridingScore.score ?: 0, ::max)
        val computedBadges = listOf(
            RiderBadge(
                id = "b_10km",
                title = "First Spark",
                description = "Tempuh jarak total 10 km pertama",
                unlocked = totalKm >= 10.0,
                progress = (totalKm / 10.0).coerceIn(0.0, 1.0)
            ),
            RiderBadge(
                id = "b_100km",
                title = "Century Rider",
                description = "Tempuh akumulasi 100 km",
                unlocked = totalKm >= 100.0,
                progress = (totalKm / 100.0).coerceIn(0.0, 1.0)
            ),
            RiderBadge(
                id = "b_regen",
                title = "Regen Master",
                description = "Pulihkan energi 50 Wh dari pengereman",
                unlocked = totalRegen >= 50.0,
                progress = (totalRegen / 50.0).coerceIn(0.0, 1.0)
            ),
            RiderBadge(
                id = "b_eco",
                title = "Eco Maestro",
                description = "Raih Riding Score >= 90 pada perjalanan",
                unlocked = bestScore >= 90,
                progress = (bestScore / 90.0).coerceIn(0.0, 1.0)
            )
        )
        return copy(effective = eff, badges = computedBadges)
    }

    private fun wireHardwareCallbacks() {
        btClassic.onLog = ::addLog
        btClassic.onLineReceived = ::handleMotorLine
        btClassic.onStateChanged = { st, name, _ ->
            if (!_uiState.value.motorViaBle) {
                val wasConnected = _uiState.value.motorConnState == ConnectionStateX.CONNECTED
                _uiState.update { cur ->
                    cur.copy(
                        motorConnState = st,
                        motorConnName = name.ifEmpty { cur.motorConnName }
                    ).recomputeDerived()
                }
                if (wasConnected && st == ConnectionStateX.DISCONNECTED) {
                    onMotorDisconnected()
                }
            }
        }

        ScreenMirrorService.onMirrorStateChanged = { running ->
            _uiState.update { it.copy(screenMirrorActive = running) }
        }
        ScreenMirrorService.onFrameReady = { b64 ->
            viewModelScope.launch {
                val st = _uiState.value
                if (st.motorConnState != ConnectionStateX.CONNECTED) return@launch
                if (st.motorViaBle) {
                    ble.sendMotorBleMapsFrame(
                        b64,
                        chunkSize = st.settings.mapsLeChunkSize,
                        delayMs = st.settings.mapsLeDelayMs
                    )
                    _uiState.update {
                        it.copy(
                            mapsFramesSent = it.mapsFramesSent + 1,
                            mapsLastChars = b64.length,
                            mapsStatus = "Mirror OK (BLE)"
                        )
                    }
                } else {
                    val ms = btClassic.sendMapsFrame(b64)
                    if (ms != null) {
                        _uiState.update {
                            it.copy(
                                mapsFramesSent = it.mapsFramesSent + 1,
                                mapsLastMs = ms,
                                mapsLastChars = b64.length,
                                mapsStatus = "Mirror OK (SPP)"
                            )
                        }
                    }
                }
            }
        }

        ble.onLog = ::addLog
        ble.onScanResults = { list ->
            _uiState.update { it.copy(scannedBleDevices = list) }
        }
        ble.onScanStateChanged = { scanning ->
            _uiState.update { it.copy(isScanningBle = scanning) }
        }
        ble.onJkStateChanged = { st, name ->
            _uiState.update { cur ->
                cur.copy(
                    jkConnState = st,
                    jkConnName = name.ifEmpty { if (st == ConnectionStateX.DISCONNECTED) "" else cur.jkConnName }
                ).recomputeDerived()
            }
        }
        ble.onJkData = { d ->
            _uiState.update { it.copy(jkData = d).recomputeDerived() }
        }
        ble.onEsp1StateChanged = { st, name ->
            _uiState.update { cur ->
                cur.copy(
                    esp1ConnState = st,
                    esp1ConnName = name.ifEmpty { if (st == ConnectionStateX.DISCONNECTED) "" else cur.esp1ConnName }
                ).recomputeDerived()
            }
        }
        ble.onEsp1Data = { d ->
            _uiState.update { it.copy(esp1Data = d).recomputeDerived() }
        }
        ble.onMatrixDone = {
            if (_uiState.value.matrixAutoRotate) {
                advanceMatrixQueue()
            }
        }
        ble.onEsp2StateChanged = { st, name ->
            _uiState.update { cur ->
                cur.copy(
                    esp2ConnState = st,
                    esp2ConnName = name.ifEmpty { if (st == ConnectionStateX.DISCONNECTED) "" else cur.esp2ConnName }
                ).recomputeDerived()
            }
        }
        ble.onBattery2Data = { d ->
            _uiState.update { it.copy(battery2Data = d).recomputeDerived() }
        }
        ble.onMotorBleStateChanged = { st, name ->
            if (_uiState.value.motorViaBle) {
                val wasConnected = _uiState.value.motorConnState == ConnectionStateX.CONNECTED
                _uiState.update { cur ->
                    cur.copy(
                        motorConnState = st,
                        motorConnName = name.ifEmpty { if (st == ConnectionStateX.DISCONNECTED) "" else cur.motorConnName }
                    ).recomputeDerived()
                }
                if (wasConnected && st == ConnectionStateX.DISCONNECTED) {
                    onMotorDisconnected()
                }
            }
        }
        ble.onMotorBleLine = ::handleMotorLine
        locationTts.onGpsFix = ::onGpsFix
    }

    private fun handleMotorLine(line: String) {
        addLog("MOTOR_RX", line, false)
        val cur = _uiState.value
        val updated = TelemetryEngine.parseMotorAtLine(
            rawLine = line,
            current = cur.motorData,
            onValidOdo = { odo ->
                if (abs(odo - _uiState.value.settings.odoBackupKm) > 0.5) {
                    updateSettings(_uiState.value.settings.copy(odoBackupKm = odo))
                }
            },
            onCorruptOdo = { msg -> addLog("MOTOR", msg, true) }
        )
        if (updated != null) {
            _uiState.update { it.copy(motorData = updated).recomputeDerived() }
        }
    }

    private fun onMotorDisconnected() {
        val st = _uiState.value
        val loc = st.gpsState.location
        if (loc != null) {
            val label = listOf(st.gpsState.roadName, st.gpsState.areaName)
                .filter { it.isNotBlank() }
                .joinToString(", ")
                .ifEmpty { "Lokasi Parkir Terakhir" }
            val parked = ParkedLocation(
                lat = loc.lat,
                lng = loc.lng,
                label = label,
                timestampMs = System.currentTimeMillis()
            )
            _uiState.update { it.copy(parkedLocation = parked) }
            viewModelScope.launch { storage.saveParkedLocation(parked) }
        }
        if (st.settings.autoSaveTrip && st.liveTrip.distanceKm >= 0.2) {
            saveCurrentTripAndReset()
        }
    }

    private fun addLog(source: String, msg: String, isError: Boolean) {
        val entry = SerialLogEntry(
            timestampMs = System.currentTimeMillis(),
            source = source,
            message = msg,
            isError = isError
        )
        _uiState.update { cur ->
            val next = (listOf(entry) + cur.serialLogs).take(150)
            cur.copy(serialLogs = next)
        }
    }

    private fun start1HzEngine() {
        tick1HzJob?.cancel()
        tick1HzJob = viewModelScope.launch {
            while (isActive) {
                delay(1000L)
                val now = System.currentTimeMillis()
                val cur = _uiState.value
                val eff = TelemetryEngine.computeEffective(
                    motor = cur.motorData,
                    motorState = cur.motorConnState,
                    jk = cur.jkData,
                    jkState = cur.jkConnState,
                    esp1 = cur.esp1Data,
                    esp1State = cur.esp1ConnState,
                    b2 = cur.battery2Data,
                    esp2State = cur.esp2ConnState,
                    gpsSpeedKmh = cur.gpsState.speedKmh,
                    trip = cur.liveTrip,
                    settings = cur.settings,
                    nowMs = now
                )

                var nextTrip = cur.liveTrip
                var nextScore = cur.ridingScore
                var nextChart = cur.liveChartPoints

                if (cur.motorConnState == ConnectionStateX.CONNECTED ||
                    cur.jkConnState == ConnectionStateX.CONNECTED ||
                    cur.esp2ConnState == ConnectionStateX.CONNECTED ||
                    cur.devSimActive ||
                    (cur.gpsState.speedKmh ?: 0.0) > 2.0
                ) {
                    val res = TelemetryEngine.integrateSample(
                        prevTrip = cur.liveTrip,
                        prevScore = cur.ridingScore,
                        eff = eff,
                        nowMs = now,
                        wasMoving = wasMoving
                    )
                    nextTrip = res.trip
                    nextScore = res.score
                    wasMoving = res.wasMoving
                    if (lastIntegrableSoc == null) lastIntegrableSoc = eff.combinedSocPct
                    if (tripStartArea.isEmpty() && cur.gpsState.areaName.isNotEmpty()) {
                        tripStartArea = cur.gpsState.areaName
                    }

                    val pt = TelemetryPoint(
                        timestampMs = now,
                        speedKmh = eff.speedKmh ?: 0.0,
                        throttlePct = eff.throttlePct ?: 0.0,
                        powerW = eff.combinedPowerW ?: 0.0,
                        socPct = eff.combinedSocPct ?: 0.0,
                        voltageV = eff.avgVoltageV ?: 0.0,
                        currentA = eff.combinedCurrentA ?: 0.0,
                        tempC = eff.b1TempC ?: eff.b2TempC ?: cur.motorData.tempMotorC ?: 0.0,
                        lat = cur.gpsState.location?.lat,
                        lng = cur.gpsState.location?.lng,
                        altM = cur.gpsState.location?.altM
                    )
                    nextChart = (cur.liveChartPoints + pt).takeLast(240)
                }

                val alerts = TelemetryEngine.evaluateAlarms(
                    eff = eff,
                    jk = cur.jkData,
                    b2 = cur.battery2Data,
                    settings = cur.settings
                )

                if (cur.motorConnState == ConnectionStateX.CONNECTED) {
                    val nowSec = now / 1000L
                    if (nowSec % 2L == 0L) {
                        sendMotorTripRange(eff.rangeKm)
                    }
                    if ((nowSec - atNavLastSentSec) >= cur.settings.atNavSlots.intervalSec) {
                        atNavLastSentSec = nowSec
                        val slots = TelemetryEngine.buildAtNavSlots(
                            eff = eff,
                            trip = nextTrip,
                            roadName = cur.gpsState.roadName,
                            areaName = cur.gpsState.areaName,
                            weatherMood = cur.weather?.moodTag ?: "",
                            slots = cur.settings.atNavSlots
                        )
                        if (slots.isNotEmpty()) {
                            val msg = slots[atNavCursor % slots.size]
                            atNavCursor++
                            sendMotorNavText(msg)
                        }
                    }
                }

                _uiState.update { s ->
                    s.copy(
                        liveTrip = nextTrip,
                        ridingScore = nextScore,
                        liveChartPoints = nextChart,
                        activeAlerts = alerts
                    ).recomputeDerived()
                }
            }
        }
    }

    fun startGpsAndWatchdog() {
        locationTts.startGps()
        if (gpsWatchdogJob == null) {
            gpsWatchdogJob = viewModelScope.launch {
                while (isActive) {
                    delay(12_000L)
                    if (System.currentTimeMillis() - lastGpsFixMs > 12_000L) {
                        locationTts.startGps()
                    }
                }
            }
        }
    }

    fun gpsDiagText(): String = locationTts.gpsDiagText()

    private fun onGpsFix(fix: NativeGpsFix) {
        lastGpsFixMs = System.currentTimeMillis()
        val gp = GeoPointX(fix.lat, fix.lng, altM = fix.altM)
        val spdKmh = if (fix.speedMs > 0.3) fix.speedMs * 3.6 else 0.0

        if (spdKmh > 2.0) {
            val lastPt = livePathPoints.lastOrNull()
            if (lastPt == null || RoutePhysics.haversineKm(lastPt, gp) >= 0.025) {
                livePathPoints.add(gp)
            }
        }

        _uiState.update { cur ->
            val hdg = if (fix.bearingDeg != null && fix.bearingDeg >= 0.0) fix.bearingDeg else cur.gpsState.headingDeg
            cur.copy(
                gpsState = cur.gpsState.copy(
                    location = gp,
                    speedKmh = spdKmh,
                    headingDeg = hdg,
                    accuracyM = fix.accuracyM
                )
            ).recomputeDerived()
        }

        val now = System.currentTimeMillis()
        if (now - lastWeatherFetchMs > 600_000L) {
            lastWeatherFetchMs = now
            refreshWeatherAndLocation(fix.lat, fix.lng)
        } else if (now - lastGeoFetchMs > 30_000L) {
            lastGeoFetchMs = now
            viewModelScope.launch { refreshReverseGeo(fix.lat, fix.lng) }
        }

        if (_uiState.value.isNavigating && _uiState.value.activeRoute != null) {
            updateNavigationProgress(gp)
        }
    }

    fun refreshWeatherAndLocation(lat: Double? = null, lng: Double? = null) {
        viewModelScope.launch {
            val loc = _uiState.value.gpsState.location
            val useLat = lat ?: loc?.lat ?: -6.1754
            val useLng = lng ?: loc?.lng ?: 106.8272
            val w = RemoteApisService.fetchWeather(useLat, useLng)
            if (w != null) {
                _uiState.update { it.copy(weather = w) }
            }
            refreshReverseGeo(useLat, useLng)
        }
    }

    private suspend fun refreshReverseGeo(lat: Double, lng: Double) {
        val geo = RemoteApisService.reverseGeocode(lat, lng)
        if (geo != null && (geo.road.isNotEmpty() || geo.area.isNotEmpty())) {
            _uiState.update { cur ->
                cur.copy(
                    gpsState = cur.gpsState.copy(
                        roadName = geo.road,
                        areaName = geo.area,
                        isFromCache = false
                    )
                )
            }
            storage.saveCachedGeocode(geo.road, geo.area, lat, lng)
        }
    }

    private fun attemptAutoReconnect() {
        loadBondedAndScanBle()
        val savedAddr = _uiState.value.settings.savedMotorAddress
        if (!savedAddr.isNullOrBlank()) {
            connectMotor(savedAddr)
        }
    }

    fun loadBondedAndScanBle() {
        val bonded = btClassic.getBondedDevices()
        _uiState.update { it.copy(pairedBtDevices = bonded) }
        ble.startScan(timeoutMs = 7000L)
    }

    fun connectMotor(address: String, preferBle: Boolean = false) {
        val addr = address.trim()
        if (addr.isEmpty()) return
        viewModelScope.launch {
            disconnectMotor()
            _uiState.update {
                it.copy(
                    motorViaBle = preferBle,
                    motorConnState = ConnectionStateX.CONNECTING,
                    motorConnName = addr
                ).recomputeDerived()
            }
            if (!preferBle) {
                val ok = btClassic.connect(addr)
                if (ok) {
                    val cur = _uiState.value
                    updateSettings(
                        cur.settings.copy(
                            savedMotorAddress = addr,
                            savedMotorName = cur.motorConnName.ifBlank { addr }
                        )
                    )
                    return@launch
                }
                addLog("MOTOR", "SPP gagal, mencoba fallback BLE…", true)
            }
            _uiState.update { it.copy(motorViaBle = true) }
            ble.connectMotorBle(addr)
            updateSettings(_uiState.value.settings.copy(savedMotorAddress = addr))
        }
    }

    fun disconnectMotor() {
        stopMidStreaming()
        if (_uiState.value.screenMirrorActive) {
            stopScreenMirror()
        }
        btClassic.disconnect()
        ble.disconnectMotorBle()
        _uiState.update {
            it.copy(motorConnState = ConnectionStateX.DISCONNECTED).recomputeDerived()
        }
    }

    fun sendMotorCommand(cmd: String) {
        val norm = if (cmd.endsWith("\r\n")) cmd else "$cmd\r\n"
        if (_uiState.value.motorViaBle) {
            ble.writeMotorBle(norm)
        } else {
            btClassic.sendCommand(norm)
        }
    }

    private fun sendMotorTripRange(rangeKm: Double) {
        if (_uiState.value.motorViaBle) {
            sendMotorCommand("AT+TRIP=\"${String.format(Locale.US, "%.1f", rangeKm)}\"")
        } else {
            btClassic.sendTripRange(rangeKm)
        }
    }

    private fun sendMotorNavText(txt: String) {
        if (_uiState.value.motorViaBle) {
            val clean = txt.replace(Regex("[^\\x20-\\x7E]"), " ").trim()
            val clipped = if (clean.length > 20) clean.substring(0, 20) else clean
            sendMotorCommand("AT+NAV=\"${clipped.padEnd(20, ' ')}\"")
        } else {
            btClassic.sendNavText(txt)
        }
    }

    fun restoreMotorOdo(odoKm: Double) {
        sendMotorCommand("AT+ODO=\"${Math.round(odoKm)}\"")
        updateSettings(_uiState.value.settings.copy(odoBackupKm = odoKm))
    }

    fun connectJkBms(address: String) {
        updateSettings(_uiState.value.settings.copy(savedJkAddress = address))
        ble.connectJkBms(address)
    }

    fun disconnectJkBms() = ble.disconnectJkBms()

    fun connectEspSlot(slot: Int, address: String) {
        val s = _uiState.value.settings
        updateSettings(
            if (slot == 1) s.copy(savedEsp1Address = address)
            else s.copy(savedEsp2Address = address)
        )
        ble.connectEspSlot(slot, address)
    }

    fun disconnectEspSlot(slot: Int) = ble.disconnectEspSlot(slot)

    fun sendEspCommand(slot: Int, cmd: String) = ble.sendEspCommand(slot, cmd)

    fun setContactRelay(turnOn: Boolean, bypassSafety: Boolean = false): String? {
        val eff = _uiState.value.effective
        val spd = eff.speedKmh ?: 0.0
        val trt = eff.throttlePct ?: 0.0
        if (!bypassSafety && (spd > 2.0 || trt > 5.0)) {
            return "Keamanan: Motor masih bergerak (${spd.toInt()} km/h) atau throttle aktif!"
        }
        sendEspCommand(1, if (turnOn) "CONTACT_ON" else "CONTACT_OFF")
        return null
    }

    fun setMatrixPower(on: Boolean) {
        sendEspCommand(1, if (on) "MATRIX_ON" else "MATRIX_OFF")
        if (!on) {
            _uiState.update { it.copy(matrixAutoRotate = false) }
        }
    }

    fun sendMatrixItemNow(item: MatrixQueueItem) {
        _uiState.update { it.copy(esp1Data = it.esp1Data.copy(matrixBusy = true)) }
        sendEspCommand(1, "MATRIX_TEXT:${item.speedMs}:${item.effectId}:${item.text}")
    }

    fun toggleMatrixAutoRotate(enabled: Boolean) {
        _uiState.update { it.copy(matrixAutoRotate = enabled) }
        if (enabled) advanceMatrixQueue()
    }

    private fun advanceMatrixQueue() {
        val active = _uiState.value.matrixQueue.filter { it.enabled }
        if (active.isEmpty()) return
        val item = active[matrixIdx % active.size]
        matrixIdx++
        sendMatrixItemNow(item)
    }

    fun addMatrixItem(text: String, speedMs: Int = 45) {
        val trimmed = text.trim().uppercase(Locale.US)
        if (trimmed.isEmpty()) return
        val item = MatrixQueueItem(
            id = "mx_${System.currentTimeMillis()}",
            text = trimmed,
            enabled = true,
            speedMs = speedMs
        )
        val next = _uiState.value.matrixQueue + item
        _uiState.update { it.copy(matrixQueue = next) }
        viewModelScope.launch { storage.saveMatrixQueue(next) }
    }

    fun deleteMatrixItem(id: String) {
        val next = _uiState.value.matrixQueue.filterNot { it.id == id }
        _uiState.update { it.copy(matrixQueue = next) }
        viewModelScope.launch { storage.saveMatrixQueue(next) }
    }

    // Navigation & Route Planning
    fun addStopAndRoute(stop: PlannedStop) {
        _uiState.update { it.copy(plannedStops = it.plannedStops + stop) }
        calculateRoute()
    }

    fun setSingleDestination(stop: PlannedStop) {
        _uiState.update { it.copy(plannedStops = listOf(stop)) }
        calculateRoute()
    }

    fun clearRoute() {
        _uiState.update {
            it.copy(
                plannedStops = emptyList(),
                activeRoute = null,
                isNavigating = false,
                isRideLocked = false,
                wakelockActive = false
            )
        }
    }

    fun removeStopAt(index: Int) {
        val curStops = _uiState.value.plannedStops
        if (index !in curStops.indices) return
        val nextStops = curStops.toMutableList().apply { removeAt(index) }
        if (nextStops.isEmpty()) {
            clearRoute()
        } else {
            _uiState.update { it.copy(plannedStops = nextStops) }
            calculateRoute()
        }
    }

    fun calculateRoute() {
        val cur = _uiState.value
        if (cur.plannedStops.isEmpty()) return
        val origin = cur.gpsState.location ?: GeoPointX(-6.1754, 106.8272)
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingRoute = true) }
            val route = RemoteApisService.fetchOsrmRoute(
                origin = origin,
                stops = _uiState.value.plannedStops,
                settings = _uiState.value.settings,
                currentSocPct = _uiState.value.effective.combinedSocPct
            )
            _uiState.update { state ->
                if (route != null) {
                    state.copy(
                        isLoadingRoute = false,
                        activeRoute = route,
                        activeStepIndex = 0,
                        remainingDistKm = route.distanceM / 1000.0,
                        etaMin = route.durationSec / 60.0,
                        distToNextStepM = route.steps.firstOrNull()?.distanceM ?: 0.0
                    )
                } else {
                    state.copy(isLoadingRoute = false)
                }
            }
        }
    }

    fun startNavigation() {
        val route = _uiState.value.activeRoute ?: return
        activeRouteInitialSoc = _uiState.value.effective.combinedSocPct
        _uiState.update {
            it.copy(
                isNavigating = true,
                activeStepIndex = 0,
                wakelockActive = true
            )
        }
        val st = _uiState.value
        if (st.settings.voiceNavEnabled && route.steps.isNotEmpty()) {
            val s0 = route.steps.first()
            locationTts.speak("Navigasi dimulai. ${s0.instruction}", force = true)
            val code = LocationAndTtsManager.navVoiceCodeForManeuver(s0.maneuverType, s0.modifier)
            if (st.esp1ConnState == ConnectionStateX.CONNECTED) {
                sendEspCommand(1, "NAV_VOICE:$code")
            }
        }
    }

    fun stopNavigation() {
        _uiState.update {
            it.copy(isNavigating = false, isRideLocked = false, wakelockActive = false)
        }
    }

    private fun updateNavigationProgress(pos: GeoPointX) {
        val cur = _uiState.value
        val route = cur.activeRoute ?: return
        if (route.steps.isEmpty()) return

        var stepIdx = cur.activeStepIndex.coerceIn(0, route.steps.lastIndex)
        var distNext = RoutePhysics.haversineKm(pos, route.steps[stepIdx].location) * 1000.0

        if (distNext < 30.0 && stepIdx < route.steps.lastIndex) {
            stepIdx++
            val next = route.steps[stepIdx]
            distNext = RoutePhysics.haversineKm(pos, next.location) * 1000.0
            if (cur.settings.voiceNavEnabled) {
                locationTts.speak(next.instruction)
                val code = LocationAndTtsManager.navVoiceCodeForManeuver(next.maneuverType, next.modifier)
                if (cur.esp1ConnState == ConnectionStateX.CONNECTED) {
                    sendEspCommand(1, "NAV_VOICE:$code")
                }
            }
        }

        val dest = cur.plannedStops.lastOrNull()
        var remKm = cur.remainingDistKm
        var eta = cur.etaMin
        if (dest != null) {
            remKm = RoutePhysics.haversineKm(pos, GeoPointX(dest.lat, dest.lng))
            val spd = max(20.0, cur.effective.speedKmh ?: 32.0)
            eta = (remKm / spd) * 60.0
            if (remKm < 0.05 && stepIdx >= route.steps.size - 2) {
                onArrivedAtDestination(route)
            }
        }

        _uiState.update {
            it.copy(
                activeStepIndex = stepIdx,
                distToNextStepM = distNext,
                remainingDistKm = remKm,
                etaMin = eta
            )
        }

        val now = System.currentTimeMillis()
        if (now - lastOffRouteCheckMs > 8000L && route.geometry.isNotEmpty()) {
            lastOffRouteCheckMs = now
            var minDistKm = 999.0
            var i = 0
            while (i < route.geometry.size) {
                val d = RoutePhysics.haversineKm(pos, route.geometry[i])
                if (d < minDistKm) minDistKm = d
                i += 2
            }
            if (minDistKm > 0.09) {
                addLog("NAV", "Keluar jalur (${(minDistKm * 1000).toInt()}m), hitung ulang…", false)
                calculateRoute()
            }
        }
    }

    private fun onArrivedAtDestination(route: NavRouteX) {
        val cur = _uiState.value
        if (!cur.isNavigating) return
        _uiState.update { it.copy(isNavigating = false, wakelockActive = false) }
        if (cur.settings.voiceNavEnabled) {
            locationTts.speak("Anda telah tiba di tujuan.", force = true)
        }
        val est = route.energyEstimate
        if (est != null && cur.liveTrip.energyWh > 5.0 && route.distanceM > 300.0) {
            val ratio = (cur.liveTrip.energyWh / est.netWh).coerceIn(0.6, 1.5)
            val suggested = String.format(Locale.US, "%.3f", cur.settings.routeCalFactor * ratio).toDouble()
            val entry = CalibrationEntry(
                id = "cal_${System.currentTimeMillis()}",
                distanceKm = route.distanceM / 1000.0,
                predictedWh = est.netWh,
                actualWh = cur.liveTrip.energyWh,
                suggestedFactor = suggested,
                timestampMs = System.currentTimeMillis()
            )
            val nextCalib = (listOf(entry) + cur.calibLogs).take(30)
            activeRouteInitialSoc = null
            _uiState.update { it.copy(calibLogs = nextCalib) }
            viewModelScope.launch { storage.saveCalibrationLogs(nextCalib) }
        }
    }

    fun refreshNearbySpklu(onDone: (() -> Unit)? = null) {
        viewModelScope.launch {
            val cur = _uiState.value
            val origin = cur.gpsState.location ?: GeoPointX(-6.1754, 106.8272)
            val apiFound = RemoteApisService.fetchNearbySpklu(
                lat = origin.lat,
                lng = origin.lng,
                ocmApiKey = cur.settings.ocmApiKey
            )
            val updatedUser = cur.userSpklu.map { u ->
                val d = RoutePhysics.haversineKm(origin, GeoPointX(u.lat, u.lng))
                u.copy(distanceKm = d)
            }
            val merged = (updatedUser + apiFound).sortedBy { it.distanceKm ?: 999.0 }
            _uiState.update { it.copy(nearbySpklu = merged) }
            onDone?.invoke()
        }
    }

    fun addUserSpklu(name: String, socket: String, lat: Double, lng: Double) {
        val s = SpkluStation(
            id = "usr_${System.currentTimeMillis()}",
            name = name,
            operatorName = "Catatan Pengguna",
            lat = lat,
            lng = lng,
            socketType = socket,
            isUserAdded = true
        )
        val next = listOf(s) + _uiState.value.userSpklu
        _uiState.update { it.copy(userSpklu = next) }
        viewModelScope.launch { storage.saveUserSpklu(next) }
        refreshNearbySpklu()
    }

    fun addFieldNote(name: String, category: String, note: String) {
        val loc = _uiState.value.gpsState.location ?: GeoPointX(-6.1754, 106.8272)
        val pin = FieldNotePin(
            id = "fn_${System.currentTimeMillis()}",
            name = name,
            category = category,
            note = note,
            lat = loc.lat,
            lng = loc.lng,
            timestampMs = System.currentTimeMillis()
        )
        val next = listOf(pin) + _uiState.value.fieldNotes
        _uiState.update { it.copy(fieldNotes = next) }
        viewModelScope.launch { storage.saveFieldNotes(next) }
    }

    fun deleteFieldNote(id: String) {
        val next = _uiState.value.fieldNotes.filterNot { it.id == id }
        _uiState.update { it.copy(fieldNotes = next) }
        viewModelScope.launch { storage.saveFieldNotes(next) }
    }

    fun savePlace(place: SavedPlace) {
        val next = listOf(place) + _uiState.value.savedPlaces.filterNot { it.id == place.id }
        _uiState.update { it.copy(savedPlaces = next) }
        viewModelScope.launch { storage.savePlaces(next) }
    }

    fun removeSavedPlace(id: String) {
        val next = _uiState.value.savedPlaces.filterNot { it.id == id }
        _uiState.update { it.copy(savedPlaces = next) }
        viewModelScope.launch { storage.savePlaces(next) }
    }

    // MID Streaming (AT+MAPS)
    fun startMidStreaming() {
        if (_uiState.value.midStreaming) return
        _uiState.update { it.copy(midStreaming = true) }
        mapsStreamJob?.cancel()
        mapsStreamJob = viewModelScope.launch {
            while (isActive && _uiState.value.midStreaming) {
                sendSingleMidFrame()
                val interval = _uiState.value.settings.mapsIntervalMs.coerceIn(500, 10_000).toLong()
                delay(interval)
            }
        }
    }

    fun stopMidStreaming() {
        mapsStreamJob?.cancel()
        mapsStreamJob = null
        _uiState.update { it.copy(midStreaming = false) }
    }

    suspend fun sendSingleMidFrame() {
        val st = _uiState.value
        if (st.motorConnState != ConnectionStateX.CONNECTED) {
            _uiState.update {
                it.copy(mapsStatus = "Motor belum terhubung (hubungkan dulu via tombol Hubung)")
            }
            return
        }
        if (btClassic.isMapsSending) return

        val b64 = try {
            MidFrameRenderer.renderFrameBase64(
                settings = st.settings,
                gps = st.gpsState,
                eff = st.effective,
                activeRoute = st.activeRoute,
                isNavigating = st.isNavigating,
                activeStepIndex = st.activeStepIndex,
                distToNextStepM = st.distToNextStepM,
                etaMin = st.etaMin
            )
        } catch (e: Exception) {
            _uiState.update { it.copy(mapsStatus = "Gagal membuat frame: ${e.message}") }
            return
        }

        if (b64.isEmpty()) {
            _uiState.update {
                it.copy(
                    mapsStatus = "Frame melebihi ${MidFrameRenderer.MAX_MAPS_CHARS} karakter " +
                        "(terkecil ${MidFrameRenderer.lastMinChars}). Kecilkan resolusi."
                )
            }
            return
        }

        try {
            if (st.motorViaBle) {
                val t0 = System.currentTimeMillis()
                ble.sendMotorBleMapsFrame(
                    b64,
                    chunkSize = st.settings.mapsLeChunkSize,
                    delayMs = st.settings.mapsLeDelayMs
                )
                val dt = (System.currentTimeMillis() - t0).toInt()
                _uiState.update {
                    it.copy(
                        mapsFramesSent = it.mapsFramesSent + 1,
                        mapsLastMs = dt,
                        mapsLastChars = b64.length,
                        mapsStatus = "OK (BLE)"
                    )
                }
            } else {
                val ms = btClassic.sendMapsFrame(b64)
                if (ms != null) {
                    _uiState.update {
                        it.copy(
                            mapsFramesSent = it.mapsFramesSent + 1,
                            mapsLastMs = ms,
                            mapsLastChars = b64.length,
                            mapsStatus = "OK (SPP)"
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(mapsStatus = "Gagal kirim ke motor (cek koneksi Bluetooth SPP)")
                    }
                }
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(mapsStatus = "Gagal kirim: ${e.message}") }
        }
    }

    fun startScreenMirrorWithGrant(resultCode: Int, dataIntent: Intent) {
        val s = _uiState.value.settings
        ScreenMirrorService.startService(
            context = getApplication(),
            resultCode = resultCode,
            dataIntent = dataIntent,
            width = s.mapsSnapWidth.coerceIn(64, 350),
            height = s.mapsSnapHeight.coerceIn(64, 500),
            intervalMs = max(350L, (s.mapsIntervalMs / 2).toLong())
        )
        _uiState.update { it.copy(screenMirrorActive = true) }
    }

    fun stopScreenMirror() {
        ScreenMirrorService.stopService(getApplication())
        _uiState.update { it.copy(screenMirrorActive = false) }
    }

    fun saveCurrentTripAndReset() {
        val st = _uiState.value
        val trip = st.liveTrip
        if (trip.distanceKm < 0.05 && trip.energyWh < 1.0) {
            resetLiveTrip()
            return
        }
        val now = System.currentTimeMillis()
        val rec = TripRecord(
            id = "trp_$now",
            startTimeMs = if (trip.startTimeMs > 0L) trip.startTimeMs else now - 60_000L,
            endTimeMs = now,
            distanceKm = trip.distanceKm,
            energyWh = trip.energyWh,
            regenWh = trip.regenWh,
            maxSpeedKmh = trip.maxSpeedKmh,
            avgSpeedKmh = trip.avgSpeedKmh,
            maxPowerW = trip.maxPowerW,
            ridingScore = st.ridingScore.score,
            startArea = tripStartArea.ifEmpty { st.gpsState.areaName },
            endArea = st.gpsState.areaName,
            pathPoints = livePathPoints.toList(),
            samples = st.liveChartPoints.toList()
        )
        val nextHistory = listOf(rec) + st.tripHistory
        _uiState.update { it.copy(tripHistory = nextHistory).recomputeDerived() }
        viewModelScope.launch { storage.saveTrips(nextHistory) }
        resetLiveTrip()
    }

    fun resetLiveTrip() {
        wasMoving = false
        tripStartArea = ""
        livePathPoints.clear()
        _uiState.update {
            it.copy(
                liveTrip = LiveTripMetrics(),
                ridingScore = RidingScoreState(),
                liveChartPoints = emptyList()
            ).recomputeDerived()
        }
    }

    fun deleteTrip(id: String) {
        val next = _uiState.value.tripHistory.filterNot { it.id == id }
        _uiState.update { it.copy(tripHistory = next).recomputeDerived() }
        viewModelScope.launch { storage.saveTrips(next) }
    }

    fun addMaintenanceLog(odoKm: Double, serviceType: String, notes: String = "", costRp: Int = 0) {
        val entry = MaintenanceLogEntry(
            id = "mnt_${System.currentTimeMillis()}",
            odoKm = odoKm,
            serviceType = serviceType,
            notes = notes,
            costRp = costRp,
            timestampMs = System.currentTimeMillis()
        )
        val next = listOf(entry) + _uiState.value.maintLogs
        val updatedSettings = _uiState.value.settings.copy(maintLastOdoKm = odoKm)
        _uiState.update { it.copy(maintLogs = next, settings = updatedSettings).recomputeDerived() }
        viewModelScope.launch {
            storage.saveMaintLogs(next)
            storage.saveSettings(updatedSettings)
        }
    }

    fun updateSettings(updated: AppSettings) {
        ble.invertB2Current = updated.invertB2Current
        _uiState.update { it.copy(settings = updated).recomputeDerived() }
        viewModelScope.launch { storage.saveSettings(updated) }
    }

    fun setRideLocked(locked: Boolean) {
        _uiState.update { it.copy(isRideLocked = locked) }
    }

    fun setWakelock(enabled: Boolean) {
        _uiState.update { it.copy(wakelockActive = enabled) }
    }

    suspend fun copyPickedImage(uri: Uri): String? = storage.copyPickedImage(uri)

    fun toggleDevTelemetrySimulation(enable: Boolean) {
        devSimJob?.cancel()
        if (!enable) {
            _uiState.update { cur ->
                val resetConn = cur.motorConnName == "DEV-SIM MOTOR"
                cur.copy(
                    devSimActive = false,
                    motorConnState = if (resetConn) ConnectionStateX.DISCONNECTED else cur.motorConnState,
                    motorConnName = if (resetConn) "" else cur.motorConnName
                ).recomputeDerived()
            }
            return
        }
        _uiState.update {
            it.copy(
                devSimActive = true,
                motorConnState = ConnectionStateX.CONNECTED,
                motorConnName = "DEV-SIM MOTOR"
            ).recomputeDerived()
        }
        devSimJob = viewModelScope.launch {
            var step = 0
            while (isActive && _uiState.value.devSimActive) {
                step++
                val now = System.currentTimeMillis()
                val spd = (32.0 + 22.0 * sin(step * 0.14)).coerceIn(0.0, 78.0)
                val ibat = if (spd > 42.0) -24.0 else if (spd < 15.0) 4.2 else -11.5
                val vbat = 74.2 + if (ibat > 0.0) 1.1 else ibat * 0.04
                val odoBase = _uiState.value.settings.odoBackupKm
                val data = MotorData(
                    speedKmh = spd,
                    vbat = vbat,
                    ibat = ibat,
                    throttlePct = if (spd > 15.0) (spd * 1.1).coerceIn(0.0, 100.0) else 0.0,
                    socPct = (84.0 - step * 0.03).coerceIn(15.0, 100.0),
                    sohPct = 96.0,
                    odoKm = if (odoBase > 0.0) odoBase + 1.2 else 1842.5,
                    modeIndex = 1,
                    isPark = false,
                    tempInverterC = 39.5,
                    tempMotorC = 44.2,
                    tempBatteryC = 33.4,
                    lastUpdatedMs = now
                )
                _uiState.update { it.copy(motorData = data).recomputeDerived() }
                delay(500L)
            }
        }
    }

    override fun onCleared() {
        tick1HzJob?.cancel()
        gpsWatchdogJob?.cancel()
        mapsStreamJob?.cancel()
        devSimJob?.cancel()
        locationTts.dispose()
        btClassic.dispose()
        ble.dispose()
        super.onCleared()
    }
}
