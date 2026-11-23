package com.dashboard.app.dashboard_app.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dashboard.app.dashboard_app.GesitsWidgetProvider
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
import com.dashboard.app.dashboard_app.model.ChargingLogEntry
import com.dashboard.app.dashboard_app.model.ConnectionStateX
import com.dashboard.app.dashboard_app.model.DailyChallenge
import com.dashboard.app.dashboard_app.model.DiscoveredDeviceX
import com.dashboard.app.dashboard_app.model.EffectiveTelemetry
import com.dashboard.app.dashboard_app.model.EfficiencyTrend
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
import com.dashboard.app.dashboard_app.model.PersonalRecords
import com.dashboard.app.dashboard_app.model.PlannedStop
import com.dashboard.app.dashboard_app.model.RiderBadge
import com.dashboard.app.dashboard_app.model.RidingScoreState
import com.dashboard.app.dashboard_app.model.SavedPlace
import com.dashboard.app.dashboard_app.model.SerialLogEntry
import com.dashboard.app.dashboard_app.model.SohSample
import com.dashboard.app.dashboard_app.model.SpkluStation
import com.dashboard.app.dashboard_app.model.TelemetryPoint
import com.dashboard.app.dashboard_app.model.TripRecord
import com.dashboard.app.dashboard_app.model.TripSummaryCardData
import com.dashboard.app.dashboard_app.model.WeatherData
import com.dashboard.app.dashboard_app.service.MidFrameRenderer
import com.dashboard.app.dashboard_app.service.PersistenceService
import com.dashboard.app.dashboard_app.service.RemoteApisService
import com.dashboard.app.dashboard_app.service.SpkluImportParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
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
    val wakelockActive: Boolean = false,
    val motorRssiDbm: Int? = null,
    val jkRssiDbm: Int? = null,
    val esp1RssiDbm: Int? = null,
    val esp2RssiDbm: Int? = null,
    val chargingLogs: List<ChargingLogEntry> = emptyList(),
    val sohHistory: List<SohSample> = emptyList(),
    val tripBaselineArmed: Boolean = false,
    val idleDrainWarningPct: Double? = null,
    val spkluAheadKm: Double? = null,
    val tripSummaryCard: TripSummaryCardData? = null,
    val personalRecords: PersonalRecords = PersonalRecords(),
    val efficiencyTrend: EfficiencyTrend? = null,
    val dailyChallenge: DailyChallenge = DailyChallenge("Target Jarak Harian (10 km)", "0.0 / 10.0 km", 0.0, false)
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
    private var lastWidgetUpdateMs = 0L
    private var lastWeatherFetchMs = 0L
    private var lastGeoFetchMs = 0L
    private var lastOffRouteCheckMs = 0L
    private var tripStartArea = ""
    private var activeRouteInitialSoc: Double? = null
    private var hillAlertSpokenForRoute = false
    private var idleDrainCheckedOnConnect = false

    private var chargeSessionStartMs = 0L
    private var chargeSessionStartSoc: Double? = null
    private var chargeSessionWh = 0.0

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
            val chargeLogs = storage.loadChargingLogs()
            val sohHist = storage.loadSohHistory()

            ble.invertB2Current = settings.invertB2Current
            ScreenMirrorService.overlayStripEnabled = settings.mirrorOverlayStrip

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
                    nearbySpklu = if (cur.nearbySpklu.isEmpty()) uSpklu else cur.nearbySpklu,
                    fieldNotes = notes,
                    maintLogs = maint,
                    calibLogs = calib,
                    matrixQueue = matrix,
                    chargingLogs = chargeLogs,
                    sohHistory = sohHist,
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
        val pr = TelemetryEngine.computePersonalRecords(tripHistory)
        val trend = TelemetryEngine.computeEfficiencyTrend(tripHistory)
        val challenge = TelemetryEngine.computeDailyChallenge(tripHistory)
        return copy(
            effective = eff,
            badges = computedBadges,
            personalRecords = pr,
            efficiencyTrend = trend,
            dailyChallenge = challenge
        )
    }

    private fun onMotorConnected() {
        idleDrainCheckedOnConnect = false
        val st = _uiState.value
        if (st.settings.mapsAutoStream && !st.screenMirrorActive && !st.midStreaming) {
            startMidStreaming()
        }
    }

    private fun wireHardwareCallbacks() {
        btClassic.onLog = ::addLog
        btClassic.isNavOrMapsBusy = { _uiState.value.isNavigating || _uiState.value.midStreaming }
        btClassic.onLineReceived = ::handleMotorLine
        btClassic.onStateChanged = { st, name, _ ->
            if (!_uiState.value.motorViaBle) {
                val wasConnected = _uiState.value.motorConnState == ConnectionStateX.CONNECTED
                _uiState.update { cur ->
                    cur.copy(
                        motorConnState = st,
                        motorConnName = name.ifEmpty { if (st == ConnectionStateX.DISCONNECTED) "" else cur.motorConnName }
                    ).recomputeDerived()
                }
                if (!wasConnected && st == ConnectionStateX.CONNECTED) {
                    onMotorConnected()
                } else if (wasConnected && st == ConnectionStateX.DISCONNECTED) {
                    onMotorDisconnected()
                }
            }
        }

        ScreenMirrorService.onMirrorStateChanged = { running ->
            _uiState.update { it.copy(screenMirrorActive = running) }
        }
        ScreenMirrorService.onExternalNavLine = { line ->
            val st = _uiState.value
            if (st.motorConnState == ConnectionStateX.CONNECTED) {
                if (st.motorViaBle) {
                    val ascii = line.padEnd(20, ' ').take(20)
                    ble.writeMotorBle("AT+NAV=\"$ascii\"\r\n")
                } else {
                    btClassic.sendNavText(line)
                }
            }
        }
        ScreenMirrorService.onFrameReady = { b64 ->
            viewModelScope.launch {
                val st = _uiState.value
                if (st.motorConnState != ConnectionStateX.CONNECTED) return@launch
                if (!st.motorViaBle && btClassic.isMapsSending) return@launch
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
        ble.onSlotRssi = { slot, rssi ->
            _uiState.update { cur ->
                when (slot) {
                    "motor" -> cur.copy(motorRssiDbm = rssi)
                    "jk" -> cur.copy(jkRssiDbm = rssi)
                    "esp1" -> cur.copy(esp1RssiDbm = rssi)
                    "esp2" -> cur.copy(esp2RssiDbm = rssi)
                    else -> cur
                }
            }
        }
        ble.onJkStateChanged = { st, name ->
            _uiState.update { cur ->
                cur.copy(
                    jkConnState = st,
                    jkConnName = name.ifEmpty { if (st == ConnectionStateX.DISCONNECTED) "" else cur.jkConnName },
                    jkRssiDbm = if (st == ConnectionStateX.DISCONNECTED) null else cur.jkRssiDbm
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
                    esp1ConnName = name.ifEmpty { if (st == ConnectionStateX.DISCONNECTED) "" else cur.esp1ConnName },
                    esp1RssiDbm = if (st == ConnectionStateX.DISCONNECTED) null else cur.esp1RssiDbm
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
                    esp2ConnName = name.ifEmpty { if (st == ConnectionStateX.DISCONNECTED) "" else cur.esp2ConnName },
                    esp2RssiDbm = if (st == ConnectionStateX.DISCONNECTED) null else cur.esp2RssiDbm
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
                        motorConnName = name.ifEmpty { if (st == ConnectionStateX.DISCONNECTED) "" else cur.motorConnName },
                        motorRssiDbm = if (st == ConnectionStateX.DISCONNECTED) null else cur.motorRssiDbm
                    ).recomputeDerived()
                }
                if (!wasConnected && st == ConnectionStateX.CONNECTED) {
                    onMotorConnected()
                } else if (wasConnected && st == ConnectionStateX.DISCONNECTED) {
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
        val now = System.currentTimeMillis()
        val socNow = st.effective.combinedSocPct
        if (socNow != null && socNow in 1.0..100.0) {
            updateSettings(st.settings.copy(lastParkSocPct = socNow, lastParkTsMs = now))
        }
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
                timestampMs = now
            )
            _uiState.update { it.copy(parkedLocation = parked) }
            viewModelScope.launch { storage.saveParkedLocation(parked) }
        }
        if (st.settings.autoSaveTrip && st.liveTrip.distanceKm >= 0.2) {
            saveCurrentTripAndReset(isPartial = false)
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
                var nextArmed = cur.tripBaselineArmed
                var nextIdleWarn = cur.idleDrainWarningPct
                var nextSummaryCard = cur.tripSummaryCard

                // Auto-dismiss trip summary MID overlay once rider moves again
                if (nextSummaryCard != null && ((eff.speedKmh ?: 0.0) > 8.0 || now > nextSummaryCard.expiresAtMs)) {
                    nextSummaryCard = null
                }

                // Check phantom idle drain once on connect when SOC is valid
                if (!idleDrainCheckedOnConnect &&
                    cur.motorConnState == ConnectionStateX.CONNECTED &&
                    eff.combinedSocPct != null
                ) {
                    idleDrainCheckedOnConnect = true
                    val drain = TelemetryEngine.computeIdleDrainPct(
                        lastParkSocPct = cur.settings.lastParkSocPct,
                        lastParkTsMs = cur.settings.lastParkTsMs,
                        currentSocPct = eff.combinedSocPct,
                        nowMs = now
                    )
                    if (drain != null) {
                        nextIdleWarn = drain
                    }
                }

                // Park-to-Drive trip baseline arming
                if (nextArmed && (eff.isPark == false || (eff.speedKmh ?: 0.0) > 2.0)) {
                    nextArmed = false
                    nextTrip = nextTrip.copy(
                        startTimeMs = now,
                        startSocPct = eff.combinedSocPct,
                        lastSampleMs = now
                    )
                }

                val hasLiveSource = cur.motorConnState == ConnectionStateX.CONNECTED ||
                    cur.jkConnState == ConnectionStateX.CONNECTED ||
                    cur.esp2ConnState == ConnectionStateX.CONNECTED ||
                    cur.devSimActive ||
                    (cur.gpsState.speedKmh ?: 0.0) > 2.0

                if (hasLiveSource && !nextArmed) {
                    val res = TelemetryEngine.integrateSample(
                        prevTrip = nextTrip,
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

                // Charging session tracker (stationary + positive current)
                val chgCur = eff.combinedCurrentA ?: 0.0
                val isStationaryCharging = eff.isCharging && (eff.speedKmh ?: 0.0) <= 1.5
                if (isStationaryCharging) {
                    if (chargeSessionStartMs == 0L) {
                        chargeSessionStartMs = now
                        chargeSessionStartSoc = eff.combinedSocPct
                        chargeSessionWh = 0.0
                    } else {
                        val pW = abs((eff.avgVoltageV ?: 72.0) * chgCur)
                        chargeSessionWh += pW / 3600.0
                    }
                } else if (chargeSessionStartMs > 0L) {
                    val durMin = ((now - chargeSessionStartMs) / 60_000L).toInt()
                    val endSoc = eff.combinedSocPct ?: chargeSessionStartSoc ?: 0.0
                    val startSoc = chargeSessionStartSoc ?: endSoc
                    if (durMin >= 2 || (endSoc - startSoc) >= 1.0) {
                        val locLbl = listOf(cur.gpsState.roadName, cur.gpsState.areaName)
                            .filter { it.isNotBlank() }
                            .joinToString(", ")
                        logManualChargeSession(
                            startSocPct = startSoc,
                            endSocPct = endSoc,
                            energyAddedWh = chargeSessionWh,
                            durationMin = max(1, durMin),
                            locationLabel = locLbl
                        )
                    }
                    chargeSessionStartMs = 0L
                    chargeSessionStartSoc = null
                    chargeSessionWh = 0.0
                }

                // Periodic SOH history sampling
                val curSoh = eff.combinedSohPct
                if (curSoh != null && curSoh in 20.0..100.0) {
                    val lastSohSample = cur.sohHistory.lastOrNull()
                    val sohChanged = lastSohSample == null ||
                        abs(lastSohSample.sohPct - curSoh) >= 0.5 ||
                        (now - lastSohSample.timestampMs) > 24 * 3600_000L
                    if (sohChanged) {
                        val sample = SohSample(
                            timestampMs = now,
                            sohPct = curSoh,
                            odoKm = eff.odoKm,
                            cycles = cur.jkData.cycles ?: cur.motorData.cbatCycles
                        )
                        val nextSohList = (cur.sohHistory + sample).takeLast(30)
                        _uiState.update { it.copy(sohHistory = nextSohList) }
                        viewModelScope.launch { storage.saveSohHistory(nextSohList) }
                    }
                }

                // Compute nearest SPKLU ahead when route arrival SOC < 20%
                val route = cur.activeRoute
                val arrSoc = route?.energyEstimate?.socAtArrivalPct
                val spkluAhead = if (route != null && arrSoc != null && arrSoc < 20.0 && cur.nearbySpklu.isNotEmpty()) {
                    cur.nearbySpklu.mapNotNull { it.distanceKm }.minOrNull()
                } else null

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

                if (now - lastWidgetUpdateMs >= 8_000L) {
                    lastWidgetUpdateMs = now
                    GesitsWidgetProvider.updateSnapshot(
                        context = getApplication(),
                        socPct = eff.combinedSocPct,
                        rangeKm = eff.rangeKm,
                        bikeName = cur.settings.bikeName,
                        connected = cur.motorConnState == ConnectionStateX.CONNECTED
                    )
                }

                _uiState.update { s ->
                    s.copy(
                        liveTrip = nextTrip,
                        ridingScore = nextScore,
                        liveChartPoints = nextChart,
                        activeAlerts = alerts,
                        tripBaselineArmed = nextArmed,
                        idleDrainWarningPct = nextIdleWarn,
                        tripSummaryCard = nextSummaryCard,
                        spkluAheadKm = spkluAhead
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

    fun addMatrixItem(text: String, speedMs: Int = 45, effectId: Int = 0) {
        val trimmed = text.trim().uppercase(Locale.US)
        if (trimmed.isEmpty()) return
        val item = MatrixQueueItem(
            id = "mx_${System.currentTimeMillis()}",
            text = trimmed,
            enabled = true,
            speedMs = speedMs,
            effectId = effectId.coerceIn(0, 2)
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

    fun dismissIdleDrainWarning() {
        _uiState.update { it.copy(idleDrainWarningPct = null) }
    }

    fun logManualChargeSession(
        startSocPct: Double,
        endSocPct: Double,
        energyAddedWh: Double,
        durationMin: Int,
        locationLabel: String = ""
    ) {
        val dur = durationMin.coerceAtLeast(1)
        val avgPower = if (dur > 0) energyAddedWh / (dur / 60.0) else 0.0
        val entry = ChargingLogEntry(
            id = "chg_${System.currentTimeMillis()}",
            timestampMs = System.currentTimeMillis(),
            startSocPct = startSocPct.coerceIn(0.0, 100.0),
            endSocPct = endSocPct.coerceIn(0.0, 100.0),
            energyAddedWh = energyAddedWh.coerceAtLeast(0.0),
            durationMin = dur,
            avgPowerW = avgPower,
            locationLabel = locationLabel
        )
        val next = (listOf(entry) + _uiState.value.chargingLogs).take(30)
        _uiState.update { it.copy(chargingLogs = next) }
        viewModelScope.launch { storage.saveChargingLogs(next) }
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
        hillAlertSpokenForRoute = false
        _uiState.update {
            it.copy(
                plannedStops = emptyList(),
                activeRoute = null,
                isNavigating = false,
                isRideLocked = false,
                wakelockActive = false,
                spkluAheadKm = null
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

    fun reorderStop(fromIndex: Int, toIndex: Int) {
        val cur = _uiState.value.plannedStops.toMutableList()
        if (fromIndex !in cur.indices || toIndex !in cur.indices || fromIndex == toIndex) return
        val item = cur.removeAt(fromIndex)
        cur.add(toIndex, item)
        _uiState.update { it.copy(plannedStops = cur) }
        calculateRoute()
    }

    fun selectRouteAlternative(index: Int) {
        val route = _uiState.value.activeRoute ?: return
        val alt = route.alternativeOptions.getOrNull(index) ?: return
        val updated = route.copy(
            geometry = alt.geometry,
            steps = alt.steps,
            distanceM = alt.distanceM,
            durationSec = alt.durationSec,
            elevationSamples = alt.elevationSamples,
            energyEstimate = alt.energyEstimate,
            hillSegments = alt.hillSegments,
            legSummaries = alt.legSummaries,
            selectedAlternativeIndex = index
        )
        _uiState.update {
            it.copy(
                activeRoute = updated,
                activeStepIndex = 0,
                remainingDistKm = updated.distanceM / 1000.0,
                etaMin = updated.durationSec / 60.0,
                distToNextStepM = updated.steps.firstOrNull()?.distanceM ?: 0.0
            )
        }
    }

    fun calculateRoute() {
        val cur = _uiState.value
        if (cur.plannedStops.isEmpty()) return
        val origin = cur.gpsState.location ?: GeoPointX(-6.1754, 106.8272)
        val cacheKey = buildString {
            append(String.format(Locale.US, "%.3f,%.3f", origin.lat, origin.lng))
            cur.plannedStops.forEach {
                append("|${String.format(Locale.US, "%.3f,%.3f", it.lat, it.lng)}")
            }
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingRoute = true) }
            hillAlertSpokenForRoute = false
            var route = RemoteApisService.fetchOsrmRoute(
                origin = origin,
                stops = _uiState.value.plannedStops,
                settings = _uiState.value.settings,
                currentSocPct = _uiState.value.effective.combinedSocPct
            )
            if (route != null && _uiState.value.settings.routeCacheEnabled) {
                storage.saveCachedRoute(cacheKey, route)
            } else if (route == null && _uiState.value.settings.routeCacheEnabled) {
                route = storage.loadCachedRoute(cacheKey)
                if (route != null) {
                    addLog("NAV", "Memuat rute dari cache offline.", false)
                }
            }
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
        val est = route.energyEstimate
        if (st.settings.hillAlertVoice && !hillAlertSpokenForRoute && est != null && est.maxClimbPct >= 6.0) {
            hillAlertSpokenForRoute = true
            locationTts.speak(
                "Perhatian rute menanjak hingga ${est.maxClimbPct.roundToInt()} persen, kelola tenaga motor.",
                force = true
            )
        } else if (st.settings.voiceNavEnabled && route.steps.isNotEmpty()) {
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
            if (cur.settings.physAutoCal) {
                val blended = String.format(
                    Locale.US,
                    "%.3f",
                    (cur.settings.routeCalFactor * 0.65 + suggested * 0.35).coerceIn(0.6, 1.5)
                ).toDouble()
                val nextSettings = cur.settings.copy(routeCalFactor = blended)
                _uiState.update { it.copy(calibLogs = nextCalib, settings = nextSettings).recomputeDerived() }
                viewModelScope.launch {
                    storage.saveCalibrationLogs(nextCalib)
                    storage.saveSettings(nextSettings)
                }
            } else {
                _uiState.update { it.copy(calibLogs = nextCalib) }
                viewModelScope.launch { storage.saveCalibrationLogs(nextCalib) }
            }
        }
        saveCurrentTripAndReset(isPartial = false, predictedWh = est?.netWh)
    }

    fun refreshNearbySpklu(onDone: (() -> Unit)? = null) {
        viewModelScope.launch {
            val cur = _uiState.value
            val origin = cur.gpsState.location ?: GeoPointX(-6.1754, 106.8272)
            val apiFound = RemoteApisService.fetchNearbySpklu(
                lat = origin.lat,
                lng = origin.lng,
                ocmApiKey = cur.settings.ocmApiKey,
                customFeedUrl = cur.settings.customSpkluFeedUrl
            )
            val updatedUser = cur.userSpklu.map { u ->
                val d = RoutePhysics.haversineKm(origin, GeoPointX(u.lat, u.lng))
                u.copy(
                    distanceKm = d,
                    sourceLabel = u.sourceLabel.ifBlank { "Pribadi" },
                    isMolisFriendly = u.isMolisFriendly || SpkluImportParser.inferMolisFriendly(u.name, u.socketType, u.notes, u.powerKw)
                )
            }
            val merged = (updatedUser + apiFound).sortedBy { it.distanceKm ?: 999.0 }
            _uiState.update { it.copy(nearbySpklu = merged) }
            onDone?.invoke()
        }
    }

    fun addUserSpklu(
        name: String,
        socket: String,
        lat: Double,
        lng: Double,
        powerKw: Double? = null,
        notes: String = "",
        isMolisFriendly: Boolean = true
    ) {
        val origin = _uiState.value.gpsState.location
        val dist = origin?.let { RoutePhysics.haversineKm(it, GeoPointX(lat, lng)) }
        val s = SpkluStation(
            id = "usr_${System.currentTimeMillis()}",
            name = name,
            operatorName = if (isMolisFriendly) "Komunitas / Pribadi" else "Catatan Pengguna",
            lat = lat,
            lng = lng,
            socketType = socket,
            powerKw = powerKw,
            isUserAdded = true,
            distanceKm = dist,
            notes = notes,
            sourceLabel = "Pribadi",
            isMolisFriendly = isMolisFriendly
        )
        val next = listOf(s) + _uiState.value.userSpklu
        _uiState.update { cur ->
            val merged = (listOf(s) + cur.nearbySpklu.filterNot { it.id == s.id })
                .sortedBy { it.distanceKm ?: 999.0 }
            cur.copy(userSpklu = next, nearbySpklu = merged)
        }
        viewModelScope.launch { storage.saveUserSpklu(next) }
    }

    fun removeUserSpklu(id: String) {
        val next = _uiState.value.userSpklu.filterNot { it.id == id }
        _uiState.update { cur ->
            cur.copy(
                userSpklu = next,
                nearbySpklu = cur.nearbySpklu.filterNot { it.id == id }
            )
        }
        viewModelScope.launch { storage.saveUserSpklu(next) }
    }

    fun importSpkluFromUri(uri: Uri, fileName: String?, onResult: (Int, String) -> Unit) {
        viewModelScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null) SpkluImportParser.parseBytes(bytes, fileName) else emptyList()
                }.getOrDefault(emptyList())
            }
            if (parsed.isEmpty()) {
                onResult(0, "Tidak ada titik SPKLU/charger valid ditemukan di file tersebut.")
                return@launch
            }
            val cur = _uiState.value
            val existing = cur.userSpklu.toMutableList()
            var addedCount = 0
            for (item in parsed) {
                val dup = existing.any { e ->
                    RoutePhysics.haversineKm(GeoPointX(e.lat, e.lng), GeoPointX(item.lat, item.lng)) < 0.03
                }
                if (!dup) {
                    existing.add(0, item)
                    addedCount++
                }
            }
            val origin = cur.gpsState.location
            val updatedUser = existing.map { u ->
                val d = origin?.let { RoutePhysics.haversineKm(it, GeoPointX(u.lat, u.lng)) } ?: u.distanceKm
                u.copy(distanceKm = d)
            }
            val apiOnly = cur.nearbySpklu.filterNot { it.isUserAdded }
            val merged = (updatedUser + apiOnly).sortedBy { it.distanceKm ?: 999.0 }
            _uiState.update { it.copy(userSpklu = updatedUser, nearbySpklu = merged) }
            storage.saveUserSpklu(updatedUser)
            val msg = if (addedCount > 0) {
                "$addedCount titik charger berhasil diimpor (${parsed.size - addedCount} duplikat dilewati)."
            } else {
                "Semua ${parsed.size} titik sudah tersimpan sebelumnya."
            }
            onResult(addedCount, msg)
        }
    }

    fun addFieldNote(name: String, category: String, note: String) {
        val loc = _uiState.value.gpsState.location ?: GeoPointX(-6.1754, 106.8272)
        addFieldNoteAt(name, category, note, loc.lat, loc.lng)
    }

    fun addFieldNoteAt(name: String, category: String, note: String, lat: Double, lng: Double) {
        val pin = FieldNotePin(
            id = "fn_${System.currentTimeMillis()}",
            name = name,
            category = category,
            note = note,
            lat = lat,
            lng = lng,
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

    fun dismissTripSummaryCard() {
        _uiState.update { it.copy(tripSummaryCard = null) }
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
        if (_uiState.value.screenMirrorActive) stopScreenMirror()
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
        if (!st.motorViaBle && btClassic.isMapsSending) return
        if (st.motorViaBle && ble.isMotorBleMapsSending) return

        val b64 = try {
            MidFrameRenderer.renderFrameBase64(
                context = getApplication(),
                settings = st.settings,
                gps = st.gpsState,
                eff = st.effective,
                activeRoute = st.activeRoute,
                isNavigating = st.isNavigating,
                activeStepIndex = st.activeStepIndex,
                distToNextStepM = st.distToNextStepM,
                etaMin = st.etaMin,
                spkluAheadKm = st.spkluAheadKm,
                tripSummary = st.tripSummaryCard
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
        if (_uiState.value.midStreaming) stopMidStreaming()
        val s = _uiState.value.settings
        ScreenMirrorService.startService(
            context = getApplication(),
            resultCode = resultCode,
            dataIntent = dataIntent,
            width = s.mapsSnapWidth.coerceIn(160, 480),
            height = s.mapsSnapHeight.coerceIn(120, 360),
            quality = s.mapsQualityPct.coerceIn(30, 95),
            intervalMs = max(450L, s.mapsIntervalMs.toLong()),
            overlayStrip = s.mirrorOverlayStrip
        )
        _uiState.update { it.copy(screenMirrorActive = true) }
    }

    fun stopScreenMirror() {
        ScreenMirrorService.stopService(getApplication())
        _uiState.update { it.copy(screenMirrorActive = false) }
    }

    fun saveCurrentTripAndReset(isPartial: Boolean = false, predictedWh: Double? = null) {
        val st = _uiState.value
        val trip = st.liveTrip
        if (trip.distanceKm < 0.05 && trip.energyWh < 1.0) {
            resetLiveTrip()
            return
        }
        val now = System.currentTimeMillis()
        val startMs = if (trip.startTimeMs > 0L) trip.startTimeMs else now - 60_000L
        val rec = TripRecord(
            id = "trp_$now",
            startTimeMs = startMs,
            endTimeMs = now,
            distanceKm = trip.distanceKm,
            energyWh = trip.energyWh,
            regenWh = trip.regenWh,
            maxSpeedKmh = trip.maxSpeedKmh,
            avgSpeedKmh = trip.avgSpeedKmh,
            maxPowerW = trip.maxPowerW,
            maxCurrentA = trip.maxCurrentA,
            minVbat = trip.minVbat,
            maxVbat = trip.maxVbat,
            maxTbatC = trip.maxTbatC,
            isPartial = isPartial,
            ridingScore = st.ridingScore.score,
            startArea = tripStartArea.ifEmpty { st.gpsState.areaName },
            endArea = st.gpsState.areaName,
            pathPoints = livePathPoints.toList(),
            samples = st.liveChartPoints.toList()
        )
        val durMin = ((now - startMs) / 60_000L).toInt().coerceAtLeast(1)
        val errPct = if (predictedWh != null && predictedWh > 5.0) {
            ((rec.energyWh - predictedWh) / predictedWh) * 100.0
        } else null
        val summaryCard = TripSummaryCardData(
            title = if (isPartial) "RINGKASAN TRIP (JEDA)" else "RINGKASAN PERJALANAN",
            distanceKm = rec.distanceKm,
            durationMin = durMin,
            energyWh = rec.energyWh,
            regenWh = rec.regenWh,
            whPerKm = rec.whPerKm,
            maxSpeedKmh = rec.maxSpeedKmh,
            avgSpeedKmh = rec.avgSpeedKmh,
            maxPowerW = rec.maxPowerW,
            ridingScore = rec.ridingScore,
            predictedWh = predictedWh,
            errorPct = errPct,
            expiresAtMs = now + 25_000L
        )
        val nextHistory = listOf(rec) + st.tripHistory
        _uiState.update {
            it.copy(
                tripHistory = nextHistory,
                tripSummaryCard = summaryCard
            ).recomputeDerived()
        }
        viewModelScope.launch { storage.saveTrips(nextHistory) }
        resetLiveTrip()
    }

    fun resetLiveTrip() {
        wasMoving = false
        tripStartArea = ""
        livePathPoints.clear()
        val parked = _uiState.value.effective.isPark == true
        _uiState.update {
            it.copy(
                liveTrip = LiveTripMetrics(),
                ridingScore = RidingScoreState(),
                liveChartPoints = emptyList(),
                tripBaselineArmed = parked
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
        ScreenMirrorService.overlayStripEnabled = updated.mirrorOverlayStrip
        _uiState.update { it.copy(settings = updated).recomputeDerived() }
        viewModelScope.launch { storage.saveSettings(updated) }
    }

    fun setRideLocked(locked: Boolean) {
        _uiState.update { it.copy(isRideLocked = locked) }
    }

    fun setWakelock(enabled: Boolean) {
        _uiState.update { it.copy(wakelockActive = enabled) }
    }

    fun handleSharedNavigationText(rawText: String, onMessage: (String) -> Unit = {}) {
        val text = rawText.trim()
        if (text.isEmpty()) return
        val direct = RoutePhysics.parseCoordinatesOrGoogleMapsUrl(text)
        if (direct != null) {
            setSingleDestination(
                PlannedStop(
                    id = "shared_${System.currentTimeMillis()}",
                    label = "Tujuan Link Maps",
                    lat = direct.lat,
                    lng = direct.lng
                )
            )
            onMessage("Koordinat tujuan dimuat dari tautan.")
            return
        }
        viewModelScope.launch {
            if (RemoteApisService.isMapsShortUrl(text)) {
                val resolved = RemoteApisService.resolveGoogleMapsShortUrl(text)
                if (resolved != null) {
                    setSingleDestination(
                        PlannedStop(
                            id = "shared_${System.currentTimeMillis()}",
                            label = "Tujuan Link Maps",
                            lat = resolved.lat,
                            lng = resolved.lng
                        )
                    )
                    onMessage("Koordinat dari short-link Maps dimuat.")
                    return@launch
                }
            }
            val cleaned = text.replace(Regex("https?://\\S+"), "").trim().ifEmpty { text }
            val places = RemoteApisService.searchPlaces(cleaned)
            val first = places.firstOrNull()
            if (first != null) {
                setSingleDestination(
                    PlannedStop(
                        id = "shared_${System.currentTimeMillis()}",
                        label = first.name,
                        lat = first.lat,
                        lng = first.lng
                    )
                )
                onMessage("Tujuan ditemukan: ${first.name}")
            } else {
                onMessage("Koordinat/lokasi dari tautan tidak dapat diparse.")
            }
        }
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
