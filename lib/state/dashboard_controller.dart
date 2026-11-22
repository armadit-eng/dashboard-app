import 'dart:async';
import 'dart:collection';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';

import '../models/app_models.dart';
import '../models/navigation_models.dart';
import '../models/telemetry_models.dart';
import '../services/ble_devices_service.dart';
import '../services/bt_classic_channel.dart';
import '../services/mid_frame_renderer.dart';
import '../services/persistence_service.dart';
import '../services/platform_tools_channel.dart';
import '../services/remote_apis_service.dart';
import '../services/route_physics.dart';
import '../services/telemetry_engine.dart';
import '../services/voice_alert_service.dart';

class DashboardController extends ChangeNotifier {
  // --- Throttled notifications (kurangi rebuild UI) ---
  static const int _minNotifyGapMs = 200;
  int _lastNotifyMs = 0;
  Timer? _notifyTimer;
  bool _disposed = false;

  @override
  void notifyListeners() {
    if (_disposed) return;
    final now = DateTime.now().millisecondsSinceEpoch;
    final elapsed = now - _lastNotifyMs;
    if (elapsed >= _minNotifyGapMs) {
      _notifyTimer?.cancel();
      _notifyTimer = null;
      _lastNotifyMs = now;
      super.notifyListeners();
      return;
    }
    if (_notifyTimer != null) return; // sudah ada notifikasi tertunda
    _notifyTimer = Timer(Duration(milliseconds: _minNotifyGapMs - elapsed), () {
      _notifyTimer = null;
      if (_disposed) return;
      _lastNotifyMs = DateTime.now().millisecondsSinceEpoch;
      super.notifyListeners();
    });
  }

  final PersistenceService _storage = PersistenceService();
  final BtClassicChannelService _btClassic = BtClassicChannelService();
  final BleDevicesService _ble = BleDevicesService();
  final VoiceAlertService _voice = VoiceAlertService();

  AppSettings settings = const AppSettings();
  MotorData motorData = const MotorData();
  JkBmsData jkData = const JkBmsData();
  Battery2Data battery2Data = const Battery2Data();
  Esp32Data esp1Data = const Esp32Data();

  ConnectionStateX motorConnState = ConnectionStateX.disconnected;
  String motorConnName = '';
  bool motorViaBle = false;

  ConnectionStateX jkConnState = ConnectionStateX.disconnected;
  String jkConnName = '';

  ConnectionStateX esp1ConnState = ConnectionStateX.disconnected;
  String esp1ConnName = '';

  ConnectionStateX esp2ConnState = ConnectionStateX.disconnected;
  String esp2ConnName = '';

  List<DiscoveredDeviceX> pairedBtDevices = [];
  List<DiscoveredDeviceX> scannedBleDevices = [];
  bool isScanningBle = false;

  LiveTripMetrics liveTrip = const LiveTripMetrics();
  RidingScoreState ridingScore = const RidingScoreState();
  bool _wasMoving = false;
  double? _lastIntegrableSoc;
  final List<TelemetryPoint> liveChartPoints = [];
  final List<GeoPointX> _livePathPoints = [];
  final Queue<SerialLogEntry> serialLogs = Queue<SerialLogEntry>();
  List<AppAlert> activeAlerts = const [];

  GpsState gpsState = const GpsState();
  WeatherData? weather;
  ParkedLocation? parkedLocation;

  List<TripRecord> tripHistory = [];
  List<SavedPlace> savedPlaces = [];
  List<SpkluStation> nearbySpklu = [];
  List<SpkluStation> userSpklu = [];
  List<FieldNotePin> fieldNotes = [];
  List<MaintenanceLogEntry> maintLogs = [];
  List<CalibrationEntry> calibLogs = [];
  List<MatrixQueueItem> matrixQueue = [];
  bool matrixAutoRotate = false;
  int _matrixIdx = 0;

  // Navigation & Route state
  List<PlannedStop> plannedStops = [];
  NavRouteX? activeRoute;
  bool isLoadingRoute = false;
  bool isNavigating = false;
  int activeStepIndex = 0;
  double distToNextStepM = 0;
  double remainingDistKm = 0;
  double etaMin = 0;
  bool isRideLocked = false;
  bool devSimActive = false;

  // MID Streaming & Screen Mirror
  bool midStreaming = false;
  bool screenMirrorActive = false;
  int mapsFramesSent = 0;
  int mapsLastMs = 0;
  int mapsLastChars = 0;
  int _atNavCursor = 0;
  int _atNavLastSentSec = 0;

  // Internal Timers & Subs
  Timer? _tick1HzTimer;
  Timer? _mapsTimer;
  Timer? _devSimTimer;
  StreamSubscription<NativeGpsFix>? _gpsSub;
  int _lastGpsFixMs = 0;
  Timer? _gpsWatchdog;
  String mapsStatus = 'Belum ada frame';
  int _lastWeatherFetchMs = 0;
  int _lastGeoFetchMs = 0;
  int _lastOffRouteCheckMs = 0;
  String _tripStartArea = '';
  double? _activeRouteInitialSoc;

  EffectiveTelemetry get effective => TelemetryEngine.computeEffective(
        motor: motorData,
        motorState: motorConnState,
        jk: jkData,
        jkState: jkConnState,
        esp1: esp1Data,
        esp1State: esp1ConnState,
        b2: battery2Data,
        esp2State: esp2ConnState,
        gpsSpeedKmh: gpsState.speedKmh,
        trip: liveTrip,
        settings: settings,
      );

  Future<void> initialize() async {
    settings = await _storage.loadSettings();
    parkedLocation = await _storage.loadParkedLocation();
    tripHistory = await _storage.loadTrips();
    savedPlaces = await _storage.loadPlaces();
    userSpklu = await _storage.loadUserSpklu();
    fieldNotes = await _storage.loadFieldNotes();
    maintLogs = await _storage.loadMaintLogs();
    calibLogs = await _storage.loadCalibrationLogs();
    matrixQueue = await _storage.loadMatrixQueue();

    final cachedGeo = await _storage.loadCachedGeocode();
    if (cachedGeo != null) {
      gpsState = gpsState.copyWith(
        roadName: cachedGeo.road,
        areaName: cachedGeo.area,
        isFromCache: true,
      );
    }

    _wireHardwareCallbacks();
    _btClassic.init();
    _ble.invertB2Current = settings.invertB2Current;
    _start1HzEngine();
    notifyListeners();

    unawaited(_initLocationStream());
    if (settings.autoReconnect) {
      unawaited(_attemptAutoReconnect());
    }
  }

  void _wireHardwareCallbacks() {
    _btClassic.onLog = _addLog;
    _btClassic.onLineReceived = _handleMotorLine;
    _btClassic.onStateChanged = (st, name, reason) {
      if (!motorViaBle) {
        final wasConnected = motorConnState == ConnectionStateX.connected;
        motorConnState = st;
        if (name.isNotEmpty) motorConnName = name;
        if (wasConnected && st == ConnectionStateX.disconnected) {
          _onMotorDisconnected();
        }
        notifyListeners();
      }
    };

    _btClassic.onMirrorState = (running) {
      screenMirrorActive = running;
      notifyListeners();
    };

    _btClassic.onMirrorFrame = (b64) async {
      if (motorConnState != ConnectionStateX.connected) return;
      if (motorViaBle) {
        await _ble.sendMotorBleMapsFrame(
          b64,
          chunkSize: settings.mapsLeChunkSize,
          delayMs: settings.mapsLeDelayMs,
        );
      } else {
        final ms = await _btClassic.sendMapsFrame(b64);
        if (ms != null) {
          mapsFramesSent++;
          mapsLastMs = ms;
          mapsLastChars = b64.length;
        }
      }
      notifyListeners();
    };

    _ble.onLog = _addLog;
    _ble.onScanResults = (list) {
      scannedBleDevices = list;
      notifyListeners();
    };
    _ble.onJkStateChanged = (st, name) {
      jkConnState = st;
      if (name.isNotEmpty) jkConnName = name;
      notifyListeners();
    };
    _ble.onJkData = (d) {
      jkData = d;
      notifyListeners();
    };
    _ble.onEsp1StateChanged = (st, name) {
      esp1ConnState = st;
      if (name.isNotEmpty) esp1ConnName = name;
      notifyListeners();
    };
    _ble.onEsp1Data = (d) {
      esp1Data = d;
      notifyListeners();
    };
    _ble.onMatrixDone = () {
      if (matrixAutoRotate) {
        _advanceMatrixQueue();
      }
    };
    _ble.onEsp2StateChanged = (st, name) {
      esp2ConnState = st;
      if (name.isNotEmpty) esp2ConnName = name;
      notifyListeners();
    };
    _ble.onBattery2Data = (d) {
      battery2Data = d;
      notifyListeners();
    };
    _ble.onMotorBleStateChanged = (st, name) {
      if (motorViaBle) {
        final wasConnected = motorConnState == ConnectionStateX.connected;
        motorConnState = st;
        if (name.isNotEmpty) motorConnName = name;
        if (wasConnected && st == ConnectionStateX.disconnected) {
          _onMotorDisconnected();
        }
        notifyListeners();
      }
    };
    _ble.onMotorBleLine = _handleMotorLine;
  }

  void _handleMotorLine(String line) {
    _addLog('MOTOR_RX', line, false);
    final updated = TelemetryEngine.parseMotorAtLine(
      line,
      motorData,
      onValidOdo: (odo) {
        if ((odo - settings.odoBackupKm).abs() > 0.5) {
          updateSettings(settings.copyWith(odoBackupKm: odo));
        }
      },
      onCorruptOdo: (msg) => _addLog('MOTOR', msg, true),
    );
    if (updated != null) {
      motorData = updated;
      notifyListeners();
    }
  }

  void _onMotorDisconnected() {
    final loc = gpsState.location;
    if (loc != null) {
      final label = [gpsState.roadName, gpsState.areaName]
          .where((s) => s.isNotEmpty)
          .join(', ');
      parkedLocation = ParkedLocation(
        lat: loc.lat,
        lng: loc.lng,
        label: label.isNotEmpty ? label : 'Lokasi Parkir Terakhir',
        timestampMs: DateTime.now().millisecondsSinceEpoch,
      );
      unawaited(_storage.saveParkedLocation(parkedLocation!));
    }
    if (settings.autoSaveTrip && liveTrip.distanceKm >= 0.2) {
      unawaited(saveCurrentTripAndReset());
    }
  }

  void _addLog(String source, String msg, bool isError) {
    serialLogs.addFirst(SerialLogEntry(
      timestampMs: DateTime.now().millisecondsSinceEpoch,
      source: source,
      message: msg,
      isError: isError,
    ));
    while (serialLogs.length > 150) {
      serialLogs.removeLast();
    }
    notifyListeners();
  }

  void _start1HzEngine() {
    _tick1HzTimer?.cancel();
    _tick1HzTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      final now = DateTime.now().millisecondsSinceEpoch;
      final eff = effective;

      if (motorConnState == ConnectionStateX.connected ||
          jkConnState == ConnectionStateX.connected ||
          esp2ConnState == ConnectionStateX.connected ||
          devSimActive ||
          (gpsState.speedKmh ?? 0) > 2.0) {
        final res = TelemetryEngine.integrateSample(
          prevTrip: liveTrip,
          prevScore: ridingScore,
          eff: eff,
          nowMs: now,
          wasMoving: _wasMoving,
        );
        liveTrip = res.trip;
        ridingScore = res.score;
        _wasMoving = res.wasMoving;
        _lastIntegrableSoc ??= eff.combinedSocPct;

        if (_tripStartArea.isEmpty && gpsState.areaName.isNotEmpty) {
          _tripStartArea = gpsState.areaName;
        }

        if (liveChartPoints.length >= 240) {
          liveChartPoints.removeAt(0);
        }
        liveChartPoints.add(TelemetryPoint(
          timestampMs: now,
          speedKmh: eff.speedKmh ?? 0,
          throttlePct: eff.throttlePct ?? 0,
          powerW: eff.combinedPowerW ?? 0,
          socPct: eff.combinedSocPct ?? 0,
          voltageV: eff.avgVoltageV ?? 0,
          currentA: eff.combinedCurrentA ?? 0,
          tempC: eff.b1TempC ?? eff.b2TempC ?? motorData.tempMotorC ?? 0,
          lat: gpsState.location?.lat,
          lng: gpsState.location?.lng,
          altM: gpsState.location?.altM,
        ));
      }

      activeAlerts = TelemetryEngine.evaluateAlarms(
        eff: eff,
        jk: jkData,
        b2: battery2Data,
        settings: settings,
      );

      // Push AT+TRIP & AT+NAV slot rotation to Motor MID when connected
      if (motorConnState == ConnectionStateX.connected) {
        final nowSec = now ~/ 1000;
        if (nowSec % 2 == 0) {
          sendMotorTripRange(eff.rangeKm);
        }
        if ((nowSec - _atNavLastSentSec) >= settings.atNavSlots.intervalSec) {
          _atNavLastSentSec = nowSec;
          final slots = TelemetryEngine.buildAtNavSlots(
            eff: eff,
            trip: liveTrip,
            roadName: gpsState.roadName,
            areaName: gpsState.areaName,
            weatherMood: weather?.moodTag ?? '',
            slots: settings.atNavSlots,
          );
          if (slots.isNotEmpty) {
            final msg = slots[_atNavCursor % slots.length];
            _atNavCursor++;
            sendMotorNavText(msg);
          }
        }
      }

      notifyListeners();
    });
  }

  Future<void> requestRuntimePermissions() async {
    await PlatformToolsChannel.requestPermissions();
  }

  Future<void> _initLocationStream() async {
    await PlatformToolsChannel.requestPermissions();
    await PlatformToolsChannel.startGps();
    _gpsSub?.cancel();
    _gpsSub = PlatformToolsChannel.gpsStream().listen(
      _onGpsFix,
      onError: (_) {},
    );
    // Watchdog: kalau 12 detik tanpa fix, minta native mendaftar ulang GPS.
    _gpsWatchdog?.cancel();
    _gpsWatchdog = Timer.periodic(const Duration(seconds: 12), (_) {
      final now = DateTime.now().millisecondsSinceEpoch;
      if (now - _lastGpsFixMs > 12000) {
        unawaited(PlatformToolsChannel.requestPermissions());
        unawaited(PlatformToolsChannel.startGps());
      }
    });
  }

  void _onGpsFix(NativeGpsFix fix) {
    _lastGpsFixMs = DateTime.now().millisecondsSinceEpoch;
    final gp = GeoPointX(fix.lat, fix.lng, altM: fix.altM);
    final spdKmh = fix.speedMs > 0.3 ? (fix.speedMs * 3.6) : 0.0;
    gpsState = gpsState.copyWith(
      location: gp,
      speedKmh: spdKmh,
      headingDeg: (fix.bearingDeg != null && fix.bearingDeg! >= 0)
          ? fix.bearingDeg
          : gpsState.headingDeg,
      accuracyM: fix.accuracyM,
    );

    if (spdKmh > 2.0) {
      if (_livePathPoints.isEmpty ||
          RoutePhysics.haversineKm(_livePathPoints.last, gp) >= 0.025) {
        _livePathPoints.add(gp);
      }
    }

    final now = DateTime.now().millisecondsSinceEpoch;
    if (now - _lastWeatherFetchMs > 600000) {
      _lastWeatherFetchMs = now;
      unawaited(refreshWeatherAndLocation(fix.lat, fix.lng));
    } else if (now - _lastGeoFetchMs > 30000) {
      _lastGeoFetchMs = now;
      unawaited(_refreshReverseGeo(fix.lat, fix.lng));
    }

    if (isNavigating && activeRoute != null) {
      _updateNavigationProgress(gp);
    }

    notifyListeners();
  }

  Future<void> refreshWeatherAndLocation([double? lat, double? lng]) async {
    final useLat = lat ?? gpsState.location?.lat ?? -6.1754;
    final useLng = lng ?? gpsState.location?.lng ?? 106.8272;
    final w = await RemoteApisService.fetchWeather(useLat, useLng);
    if (w != null) {
      weather = w;
      notifyListeners();
    }
    await _refreshReverseGeo(useLat, useLng);
  }

  Future<void> _refreshReverseGeo(double lat, double lng) async {
    final geo = await RemoteApisService.reverseGeocode(lat, lng);
    if (geo != null && (geo.road.isNotEmpty || geo.area.isNotEmpty)) {
      gpsState = gpsState.copyWith(
        roadName: geo.road,
        areaName: geo.area,
        isFromCache: false,
      );
      await _storage.saveCachedGeocode(geo.road, geo.area, lat, lng);
      notifyListeners();
    }
  }

  Future<void> _attemptAutoReconnect() async {
    await requestRuntimePermissions();
    await loadBondedAndScanBle();
    if (settings.savedMotorAddress != null &&
        settings.savedMotorAddress!.isNotEmpty) {
      await connectMotor(settings.savedMotorAddress!);
    }
  }

  Future<void> loadBondedAndScanBle() async {
    await requestRuntimePermissions();
    pairedBtDevices = await _btClassic.getBondedDevices();
    isScanningBle = true;
    notifyListeners();
    await _ble.startScan(timeout: const Duration(seconds: 7));
    isScanningBle = false;
    notifyListeners();
  }

  Future<void> connectMotor(String address, {bool preferBle = false}) async {
    final addr = address.trim();
    if (addr.isEmpty) return;
    await disconnectMotor();
    motorViaBle = preferBle;
    motorConnState = ConnectionStateX.connecting;
    motorConnName = addr;
    notifyListeners();

    if (!preferBle) {
      final ok = await _btClassic.connect(addr);
      if (ok) {
        await updateSettings(settings.copyWith(
          savedMotorAddress: addr,
          savedMotorName: motorConnName,
        ));
        return;
      }
      _addLog('MOTOR', 'SPP gagal, mencoba fallback BLE…', true);
    }

    motorViaBle = true;
    await _ble.connectMotorBle(addr);
    await updateSettings(settings.copyWith(savedMotorAddress: addr));
  }

  Future<void> disconnectMotor() async {
    stopMidStreaming();
    if (screenMirrorActive) await stopScreenMirror();
    await _btClassic.disconnect();
    await _ble.disconnectMotorBle();
    motorConnState = ConnectionStateX.disconnected;
    notifyListeners();
  }

  void sendMotorCommand(String cmd) {
    if (motorViaBle) {
      unawaited(_ble.writeMotorBle(cmd.endsWith('\r\n') ? cmd : '$cmd\r\n'));
    } else {
      _btClassic.sendCommand(cmd);
    }
  }

  void sendMotorTripRange(double rangeKm) {
    if (motorViaBle) {
      sendMotorCommand('AT+TRIP="${rangeKm.toStringAsFixed(1)}"');
    } else {
      _btClassic.sendTripRange(rangeKm);
    }
  }

  void sendMotorNavText(String txt) {
    if (motorViaBle) {
      final clean = txt.replaceAll(RegExp(r'[^\x20-\x7E]'), ' ').trim();
      final padded = (clean.length > 20 ? clean.substring(0, 20) : clean)
          .padRight(20, ' ');
      sendMotorCommand('AT+NAV="$padded"');
    } else {
      _btClassic.sendNavText(txt);
    }
  }

  void restoreMotorOdo(double odoKm) {
    sendMotorCommand('AT+ODO="${odoKm.round()}"');
    updateSettings(settings.copyWith(odoBackupKm: odoKm));
  }

  Future<void> connectJkBms(String address) async {
    await updateSettings(settings.copyWith(savedJkAddress: address));
    await _ble.connectJkBms(address);
  }

  Future<void> disconnectJkBms() => _ble.disconnectJkBms();

  Future<void> connectEspSlot(int slot, String address) async {
    if (slot == 1) {
      await updateSettings(settings.copyWith(savedEsp1Address: address));
    } else {
      await updateSettings(settings.copyWith(savedEsp2Address: address));
    }
    await _ble.connectEspSlot(slot, address);
  }

  Future<void> disconnectEspSlot(int slot) => _ble.disconnectEspSlot(slot);

  Future<void> sendEspCommand(int slot, String cmd) =>
      _ble.sendEspCommand(slot, cmd);

  // ESP32 #1 Accessories
  Future<String?> setContactRelay(bool turnOn, {bool bypassSafety = false}) async {
    final spd = effective.speedKmh ?? 0;
    final trt = effective.throttlePct ?? 0;
    if (!bypassSafety && (spd > 2.0 || trt > 5.0)) {
      return 'Keamanan: Motor masih bergerak (${spd.toStringAsFixed(0)} km/h) atau throttle aktif!';
    }
    await sendEspCommand(1, turnOn ? 'CONTACT_ON' : 'CONTACT_OFF');
    return null;
  }

  Future<void> setMatrixPower(bool on) async {
    await sendEspCommand(1, on ? 'MATRIX_ON' : 'MATRIX_OFF');
    if (!on) {
      matrixAutoRotate = false;
      notifyListeners();
    }
  }

  Future<void> sendMatrixItemNow(MatrixQueueItem item) async {
    esp1Data = esp1Data.copyWith(matrixBusy: true);
    notifyListeners();
    await sendEspCommand(
        1, 'MATRIX_TEXT:${item.speedMs}:${item.effectId}:${item.text}');
  }

  void toggleMatrixAutoRotate(bool enabled) {
    matrixAutoRotate = enabled;
    if (enabled) {
      _advanceMatrixQueue();
    }
    notifyListeners();
  }

  void _advanceMatrixQueue() {
    final active = matrixQueue.where((m) => m.enabled).toList();
    if (active.isEmpty) return;
    final item = active[_matrixIdx % active.length];
    _matrixIdx++;
    unawaited(sendMatrixItemNow(item));
  }

  Future<void> addMatrixItem(String text, {int speedMs = 45}) async {
    final item = MatrixQueueItem(
      id: 'mx_${DateTime.now().millisecondsSinceEpoch}',
      text: text.trim().toUpperCase(),
      enabled: true,
      speedMs: speedMs,
    );
    matrixQueue = [...matrixQueue, item];
    await _storage.saveMatrixQueue(matrixQueue);
    notifyListeners();
  }

  Future<void> deleteMatrixItem(String id) async {
    matrixQueue = matrixQueue.where((e) => e.id != id).toList();
    await _storage.saveMatrixQueue(matrixQueue);
    notifyListeners();
  }

  // Navigation & Route Planning
  Future<void> addStopAndRoute(PlannedStop stop) async {
    plannedStops = [...plannedStops, stop];
    notifyListeners();
    await calculateRoute();
  }

  Future<void> setSingleDestination(PlannedStop stop) async {
    plannedStops = [stop];
    notifyListeners();
    await calculateRoute();
  }

  Future<void> removeStopAt(int index) async {
    if (index < 0 || index >= plannedStops.length) return;
    final copy = [...plannedStops]..removeAt(index);
    plannedStops = copy;
    if (plannedStops.isEmpty) {
      activeRoute = null;
      isNavigating = false;
    } else {
      await calculateRoute();
    }
    notifyListeners();
  }

  Future<void> calculateRoute() async {
    if (plannedStops.isEmpty) return;
    final origin =
        gpsState.location ?? const GeoPointX(-6.1754, 106.8272); // Fallback Monas
    isLoadingRoute = true;
    notifyListeners();

    final eff = effective;
    final route = await RemoteApisService.fetchOsrmRoute(
      origin: origin,
      stops: plannedStops,
      settings: settings,
      currentSocPct: eff.combinedSocPct,
    );

    isLoadingRoute = false;
    if (route != null) {
      activeRoute = route;
      activeStepIndex = 0;
      remainingDistKm = route.distanceM / 1000.0;
      etaMin = route.durationSec / 60.0;
      if (route.steps.isNotEmpty) {
        distToNextStepM = route.steps.first.distanceM;
      }
    }
    notifyListeners();
  }

  void startNavigation() {
    if (activeRoute == null) return;
    isNavigating = true;
    activeStepIndex = 0;
    _activeRouteInitialSoc = effective.combinedSocPct;
    unawaited(PlatformToolsChannel.setWakelock(true));
    if (settings.voiceNavEnabled && activeRoute!.steps.isNotEmpty) {
      final s0 = activeRoute!.steps.first;
      unawaited(_voice.speak('Navigasi dimulai. ${s0.instruction}', force: true));
      final code =
          VoiceAlertService.navVoiceCodeForManeuver(s0.maneuverType, s0.modifier);
      if (esp1ConnState == ConnectionStateX.connected) {
        unawaited(sendEspCommand(1, 'NAV_VOICE:$code'));
      }
    }
    notifyListeners();
  }

  void stopNavigation() {
    isNavigating = false;
    isRideLocked = false;
    unawaited(PlatformToolsChannel.setWakelock(false));
    notifyListeners();
  }

  void _updateNavigationProgress(GeoPointX pos) {
    final route = activeRoute;
    if (route == null || route.steps.isEmpty) return;

    final int stepIdx = activeStepIndex.clamp(0, route.steps.length - 1).toInt();
    final step = route.steps[stepIdx];
    distToNextStepM = RoutePhysics.haversineKm(pos, step.location) * 1000.0;

    if (distToNextStepM < 30.0 && activeStepIndex < route.steps.length - 1) {
      activeStepIndex++;
      final next = route.steps[activeStepIndex];
      distToNextStepM = RoutePhysics.haversineKm(pos, next.location) * 1000.0;
      if (settings.voiceNavEnabled) {
        unawaited(_voice.speak(next.instruction));
        final code = VoiceAlertService.navVoiceCodeForManeuver(
            next.maneuverType, next.modifier);
        if (esp1ConnState == ConnectionStateX.connected) {
          unawaited(sendEspCommand(1, 'NAV_VOICE:$code'));
        }
      }
    }

    // Arrival check
    final dest = plannedStops.lastOrNull;
    if (dest != null) {
      final remKm = RoutePhysics.haversineKm(
          pos, GeoPointX(dest.lat, dest.lng));
      remainingDistKm = remKm;
      final spd = math.max(20.0, effective.speedKmh ?? 32.0);
      etaMin = (remKm / spd) * 60.0;

      if (remKm < 0.05 && activeStepIndex >= route.steps.length - 2) {
        unawaited(_onArrivedAtDestination(route));
      }
    }

    // Off-route check
    final now = DateTime.now().millisecondsSinceEpoch;
    if (now - _lastOffRouteCheckMs > 8000 && route.geometry.isNotEmpty) {
      _lastOffRouteCheckMs = now;
      double minDistKm = 999.0;
      for (int i = 0; i < route.geometry.length; i += 2) {
        final d = RoutePhysics.haversineKm(pos, route.geometry[i]);
        if (d < minDistKm) minDistKm = d;
      }
      if (minDistKm > 0.09) {
        _addLog('NAV', 'Keluar jalur (${(minDistKm * 1000).round()}m), hitung ulang…', false);
        unawaited(calculateRoute());
      }
    }
  }

  Future<void> _onArrivedAtDestination(NavRouteX route) async {
    if (!isNavigating) return;
    isNavigating = false;
    if (settings.voiceNavEnabled) {
      unawaited(_voice.speak('Anda telah tiba di tujuan.', force: true));
    }
    final est = route.energyEstimate;
    if (est != null && liveTrip.energyWh > 5.0 && route.distanceM > 300) {
      final ratio = (liveTrip.energyWh / est.netWh).clamp(0.6, 1.5);
      final entry = CalibrationEntry(
        id: 'cal_${DateTime.now().millisecondsSinceEpoch}',
        distanceKm: route.distanceM / 1000.0,
        predictedWh: est.netWh,
        actualWh: liveTrip.energyWh,
        suggestedFactor: double.parse((settings.routeCalFactor * ratio).toStringAsFixed(3)),
        timestampMs: DateTime.now().millisecondsSinceEpoch,
      );
      calibLogs = [entry, ...calibLogs].take(30).toList();
      await _storage.saveCalibrationLogs(calibLogs);
      if (_activeRouteInitialSoc != null) {
        _activeRouteInitialSoc = null;
      }
    }
    notifyListeners();
  }

  Future<void> refreshNearbySpklu() async {
    final origin = gpsState.location ?? const GeoPointX(-6.1754, 106.8272);
    final apiFound = await RemoteApisService.fetchNearbySpklu(
      lat: origin.lat,
      lng: origin.lng,
      ocmApiKey: settings.ocmApiKey,
    );
    final updatedUser = userSpklu.map((u) {
      final d = RoutePhysics.haversineKm(origin, GeoPointX(u.lat, u.lng));
      return u.withDistance(d);
    }).toList();
    nearbySpklu = [...updatedUser, ...apiFound]
      ..sort((a, b) => (a.distanceKm ?? 999).compareTo(b.distanceKm ?? 999));
    notifyListeners();
  }

  Future<void> addUserSpklu(String name, String socket, double lat, double lng) async {
    final s = SpkluStation(
      id: 'usr_${DateTime.now().millisecondsSinceEpoch}',
      name: name,
      operatorName: 'Catatan Pengguna',
      lat: lat,
      lng: lng,
      socketType: socket,
      isUserAdded: true,
    );
    userSpklu = [s, ...userSpklu];
    await _storage.saveUserSpklu(userSpklu);
    await refreshNearbySpklu();
  }

  Future<void> addFieldNote({
    required String name,
    required String category,
    required String note,
  }) async {
    final loc = gpsState.location ?? const GeoPointX(-6.1754, 106.8272);
    final pin = FieldNotePin(
      id: 'fn_${DateTime.now().millisecondsSinceEpoch}',
      name: name,
      category: category,
      note: note,
      lat: loc.lat,
      lng: loc.lng,
      timestampMs: DateTime.now().millisecondsSinceEpoch,
    );
    fieldNotes = [pin, ...fieldNotes];
    await _storage.saveFieldNotes(fieldNotes);
    notifyListeners();
  }

  Future<void> deleteFieldNote(String id) async {
    fieldNotes = fieldNotes.where((e) => e.id != id).toList();
    await _storage.saveFieldNotes(fieldNotes);
    notifyListeners();
  }

  Future<void> savePlace(SavedPlace place) async {
    savedPlaces = [place, ...savedPlaces.where((p) => p.id != place.id)];
    await _storage.savePlaces(savedPlaces);
    notifyListeners();
  }

  Future<void> removeSavedPlace(String id) async {
    savedPlaces = savedPlaces.where((p) => p.id != id).toList();
    await _storage.savePlaces(savedPlaces);
    notifyListeners();
  }

  // MID Streaming (AT+MAPS)
  void startMidStreaming() {
    if (midStreaming) return;
    midStreaming = true;
    _mapsTimer?.cancel();
    _mapsTimer = Timer.periodic(
      Duration(milliseconds: settings.mapsIntervalMs.clamp(500, 10000)),
      (_) => sendSingleMidFrame(),
    );
    unawaited(sendSingleMidFrame());
    notifyListeners();
  }

  void stopMidStreaming() {
    midStreaming = false;
    _mapsTimer?.cancel();
    _mapsTimer = null;
    notifyListeners();
  }

  Future<void> sendSingleMidFrame() async {
    if (motorConnState != ConnectionStateX.connected) {
      mapsStatus = 'Motor belum terhubung (hubungkan dulu di tab Diagnosis)';
      notifyListeners();
      return;
    }
    if (_btClassic.isMapsSending) return;
    String b64;
    try {
      b64 = await MidFrameRenderer.renderFrameBase64(
        settings: settings,
        gps: gpsState,
        eff: effective,
        activeRoute: activeRoute,
        isNavigating: isNavigating,
        activeStepIndex: activeStepIndex,
        distToNextStepM: distToNextStepM,
        etaMin: etaMin,
      );
    } catch (e) {
      mapsStatus = 'Gagal membuat frame: $e';
      notifyListeners();
      return;
    }
    if (b64.isEmpty) {
      mapsStatus =
          'Frame melebihi ${MidFrameRenderer.maxMapsChars} karakter '
          '(terkecil ${MidFrameRenderer.lastMinChars}). Kecilkan resolusi.';
      notifyListeners();
      return;
    }

    try {
      if (motorViaBle) {
        final t0 = DateTime.now().millisecondsSinceEpoch;
        await _ble.sendMotorBleMapsFrame(
          b64,
          chunkSize: settings.mapsLeChunkSize,
          delayMs: settings.mapsLeDelayMs,
        );
        mapsFramesSent++;
        mapsLastMs = DateTime.now().millisecondsSinceEpoch - t0;
        mapsLastChars = b64.length;
        mapsStatus = 'OK (BLE)';
      } else {
        final ms = await _btClassic.sendMapsFrame(b64);
        if (ms != null) {
          mapsFramesSent++;
          mapsLastMs = ms;
          mapsLastChars = b64.length;
          mapsStatus = 'OK (SPP)';
        } else {
          mapsStatus = 'Gagal kirim ke motor (cek koneksi Bluetooth SPP)';
        }
      }
    } catch (e) {
      mapsStatus = 'Gagal kirim: $e';
    }
    notifyListeners();
  }

  Future<void> startScreenMirror() async {
    final ok = await _btClassic.startScreenMirror(
      width: settings.mapsSnapWidth.clamp(64, 350).toInt(),
      height: settings.mapsSnapHeight.clamp(64, 500).toInt(),
      intervalMs: math.max(350, settings.mapsIntervalMs ~/ 2),
    );
    screenMirrorActive = ok;
    notifyListeners();
  }

  Future<void> stopScreenMirror() async {
    await _btClassic.stopScreenMirror();
    screenMirrorActive = false;
    notifyListeners();
  }

  // Trip Persistence & Maintenance & Settings
  Future<void> saveCurrentTripAndReset() async {
    if (liveTrip.distanceKm < 0.05 && liveTrip.energyWh < 1.0) {
      resetLiveTrip();
      return;
    }
    final now = DateTime.now().millisecondsSinceEpoch;
    final rec = TripRecord(
      id: 'trp_$now',
      startTimeMs: liveTrip.startTimeMs > 0 ? liveTrip.startTimeMs : now - 60000,
      endTimeMs: now,
      distanceKm: liveTrip.distanceKm,
      energyWh: liveTrip.energyWh,
      regenWh: liveTrip.regenWh,
      maxSpeedKmh: liveTrip.maxSpeedKmh,
      avgSpeedKmh: liveTrip.avgSpeedKmh,
      maxPowerW: liveTrip.maxPowerW,
      ridingScore: ridingScore.score,
      startArea: _tripStartArea.isNotEmpty ? _tripStartArea : gpsState.areaName,
      endArea: gpsState.areaName,
      pathPoints: List<GeoPointX>.from(_livePathPoints),
      samples: List<TelemetryPoint>.from(liveChartPoints),
    );
    tripHistory = [rec, ...tripHistory];
    await _storage.saveTrips(tripHistory);
    resetLiveTrip();
  }

  void resetLiveTrip() {
    liveTrip = const LiveTripMetrics();
    ridingScore = const RidingScoreState();
    _wasMoving = false;
    _tripStartArea = '';
    _livePathPoints.clear();
    liveChartPoints.clear();
    notifyListeners();
  }

  Future<void> deleteTrip(String id) async {
    tripHistory = tripHistory.where((t) => t.id != id).toList();
    await _storage.saveTrips(tripHistory);
    notifyListeners();
  }

  Future<void> addMaintenanceLog({
    required double odoKm,
    required String serviceType,
    String notes = '',
    int costRp = 0,
  }) async {
    final entry = MaintenanceLogEntry(
      id: 'mnt_${DateTime.now().millisecondsSinceEpoch}',
      odoKm: odoKm,
      serviceType: serviceType,
      notes: notes,
      costRp: costRp,
      timestampMs: DateTime.now().millisecondsSinceEpoch,
    );
    maintLogs = [entry, ...maintLogs];
    await _storage.saveMaintLogs(maintLogs);
    await updateSettings(settings.copyWith(maintLastOdoKm: odoKm));
    notifyListeners();
  }

  Future<void> updateSettings(AppSettings updated) async {
    settings = updated;
    _ble.invertB2Current = updated.invertB2Current;
    await _storage.saveSettings(updated);
    notifyListeners();
  }

  void setRideLocked(bool locked) {
    isRideLocked = locked;
    notifyListeners();
  }

  // Developer Telemetry Signal Simulator (strictly off by default, user-toggled in Dev Accordion)
  void toggleDevTelemetrySimulation(bool enable) {
    devSimActive = enable;
    _devSimTimer?.cancel();
    if (!enable) {
      if (motorConnName == 'DEV-SIM MOTOR') {
        motorConnState = ConnectionStateX.disconnected;
        motorConnName = '';
      }
      notifyListeners();
      return;
    }
    int step = 0;
    motorConnState = ConnectionStateX.connected;
    motorConnName = 'DEV-SIM MOTOR';
    _devSimTimer = Timer.periodic(const Duration(milliseconds: 500), (_) {
      step++;
      final now = DateTime.now().millisecondsSinceEpoch;
      final spd = (32.0 + 22.0 * math.sin(step * 0.14)).clamp(0.0, 78.0);
      final ibat = spd > 42 ? -24.0 : (spd < 15 ? 4.2 : -11.5);
      final vbat = 74.2 + (ibat > 0 ? 1.1 : ibat * 0.04);
      motorData = MotorData(
        speedKmh: spd,
        vbat: vbat,
        ibat: ibat,
        throttlePct: spd > 15 ? (spd * 1.1).clamp(0.0, 100.0).toDouble() : 0.0,
        socPct: (84.0 - step * 0.03).clamp(15.0, 100.0),
        sohPct: 96.0,
        odoKm: settings.odoBackupKm > 0 ? settings.odoBackupKm + 1.2 : 1842.5,
        modeIndex: 1,
        isPark: false,
        tempInverterC: 39.5,
        tempMotorC: 44.2,
        tempBatteryC: 33.4,
        lastUpdatedMs: now,
      );
      notifyListeners();
    });
    notifyListeners();
  }

  List<RiderBadge> computeBadges() {
    final totalKm = tripHistory.fold<double>(0.0, (a, b) => a + b.distanceKm) +
        liveTrip.distanceKm;
    final totalRegen = tripHistory.fold<double>(0.0, (a, b) => a + b.regenWh) +
        liveTrip.regenWh;
    final bestScore = tripHistory
        .map((t) => t.ridingScore ?? 0)
        .fold<int>(ridingScore.score ?? 0, math.max);

    return [
      RiderBadge(
        id: 'b_10km',
        title: 'First Spark',
        description: 'Tempuh jarak total 10 km pertama',
        unlocked: totalKm >= 10,
        progress: (totalKm / 10.0).clamp(0.0, 1.0),
      ),
      RiderBadge(
        id: 'b_100km',
        title: 'Century Rider',
        description: 'Tempuh akumulasi 100 km',
        unlocked: totalKm >= 100,
        progress: (totalKm / 100.0).clamp(0.0, 1.0),
      ),
      RiderBadge(
        id: 'b_regen',
        title: 'Regen Master',
        description: 'Pulihkan energi 50 Wh dari pengereman',
        unlocked: totalRegen >= 50,
        progress: (totalRegen / 50.0).clamp(0.0, 1.0),
      ),
      RiderBadge(
        id: 'b_eco',
        title: 'Eco Maestro',
        description: 'Raih Riding Score >= 90 pada perjalanan',
        unlocked: bestScore >= 90,
        progress: (bestScore / 90.0).clamp(0.0, 1.0),
      ),
    ];
  }

  @override
  void dispose() {
    _disposed = true;
    _gpsWatchdog?.cancel();
    _notifyTimer?.cancel();
    _tick1HzTimer?.cancel();
    _mapsTimer?.cancel();
    _devSimTimer?.cancel();
    _gpsSub?.cancel();
    _btClassic.dispose();
    super.dispose();
  }
}

extension _IterableLastOrNull<T> on List<T> {
  T? get lastOrNull => isEmpty ? null : last;
}
