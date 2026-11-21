import 'dart:convert';
import 'dart:io';

import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/app_models.dart';
import '../models/navigation_models.dart';

class PersistenceService {
  static const _kSettings = 'dash_settings_v1';
  static const _kParked = 'dash_parked_loc_v1';
  static const _kLastGeo = 'dash_last_geocode_v1';
  static const _kTripsFile = 'trips_history.json';
  static const _kPlacesFile = 'saved_places.json';
  static const _kSpkluFile = 'user_spklu.json';
  static const _kNotesFile = 'field_notes.json';
  static const _kMaintFile = 'maint_logs.json';
  static const _kCalibFile = 'calib_logs.json';
  static const _kMatrixFile = 'matrix_queue.json';

  SharedPreferences? _prefs;
  Directory? _docDir;

  Future<void> init() async {
    _prefs ??= await SharedPreferences.getInstance();
    _docDir ??= await getApplicationDocumentsDirectory();
  }

  Future<AppSettings> loadSettings() async {
    await init();
    final raw = _prefs?.getString(_kSettings);
    if (raw == null || raw.isEmpty) return const AppSettings();
    try {
      return AppSettings.fromJson(jsonDecode(raw) as Map<String, dynamic>);
    } catch (_) {
      return const AppSettings();
    }
  }

  Future<void> saveSettings(AppSettings settings) async {
    await init();
    await _prefs?.setString(_kSettings, jsonEncode(settings.toJson()));
  }

  Future<ParkedLocation?> loadParkedLocation() async {
    await init();
    final raw = _prefs?.getString(_kParked);
    if (raw == null || raw.isEmpty) return null;
    try {
      return ParkedLocation.fromJson(jsonDecode(raw) as Map<String, dynamic>);
    } catch (_) {
      return null;
    }
  }

  Future<void> saveParkedLocation(ParkedLocation loc) async {
    await init();
    await _prefs?.setString(_kParked, jsonEncode(loc.toJson()));
  }

  Future<({String road, String area, double lat, double lng})?>
      loadCachedGeocode() async {
    await init();
    final raw = _prefs?.getString(_kLastGeo);
    if (raw == null || raw.isEmpty) return null;
    try {
      final m = jsonDecode(raw) as Map<String, dynamic>;
      return (
        road: m['road']?.toString() ?? '',
        area: m['area']?.toString() ?? '',
        lat: (m['lat'] as num).toDouble(),
        lng: (m['lng'] as num).toDouble(),
      );
    } catch (_) {
      return null;
    }
  }

  Future<void> saveCachedGeocode(
      String road, String area, double lat, double lng) async {
    await init();
    await _prefs?.setString(
      _kLastGeo,
      jsonEncode({'road': road, 'area': area, 'lat': lat, 'lng': lng}),
    );
  }

  Future<List<T>> _readJsonList<T>(
      String filename, T Function(Map<String, dynamic>) fromJson) async {
    await init();
    final file = File('${_docDir!.path}/$filename');
    if (!await file.exists()) return [];
    try {
      final raw = await file.readAsString();
      final arr = jsonDecode(raw) as List<dynamic>;
      return arr
          .whereType<Map>()
          .map((e) => fromJson(Map<String, dynamic>.from(e)))
          .toList();
    } catch (_) {
      return [];
    }
  }

  Future<void> _writeJsonList(
      String filename, List<Map<String, dynamic>> list) async {
    await init();
    final file = File('${_docDir!.path}/$filename');
    await file.writeAsString(jsonEncode(list));
  }

  Future<List<TripRecord>> loadTrips() =>
      _readJsonList(_kTripsFile, TripRecord.fromJson);

  Future<void> saveTrips(List<TripRecord> trips) =>
      _writeJsonList(_kTripsFile, trips.map((e) => e.toJson()).toList());

  Future<List<SavedPlace>> loadPlaces() =>
      _readJsonList(_kPlacesFile, SavedPlace.fromJson);

  Future<void> savePlaces(List<SavedPlace> places) =>
      _writeJsonList(_kPlacesFile, places.map((e) => e.toJson()).toList());

  Future<List<SpkluStation>> loadUserSpklu() =>
      _readJsonList(_kSpkluFile, SpkluStation.fromJson);

  Future<void> saveUserSpklu(List<SpkluStation> list) =>
      _writeJsonList(_kSpkluFile, list.map((e) => e.toJson()).toList());

  Future<List<FieldNotePin>> loadFieldNotes() =>
      _readJsonList(_kNotesFile, FieldNotePin.fromJson);

  Future<void> saveFieldNotes(List<FieldNotePin> list) =>
      _writeJsonList(_kNotesFile, list.map((e) => e.toJson()).toList());

  Future<List<MaintenanceLogEntry>> loadMaintLogs() =>
      _readJsonList(_kMaintFile, MaintenanceLogEntry.fromJson);

  Future<void> saveMaintLogs(List<MaintenanceLogEntry> list) =>
      _writeJsonList(_kMaintFile, list.map((e) => e.toJson()).toList());

  Future<List<CalibrationEntry>> loadCalibrationLogs() =>
      _readJsonList(_kCalibFile, CalibrationEntry.fromJson);

  Future<void> saveCalibrationLogs(List<CalibrationEntry> list) =>
      _writeJsonList(_kCalibFile, list.map((e) => e.toJson()).toList());

  Future<List<MatrixQueueItem>> loadMatrixQueue() async {
    final loaded = await _readJsonList(_kMatrixFile, MatrixQueueItem.fromJson);
    if (loaded.isNotEmpty) return loaded;
    return const [
      MatrixQueueItem(id: 'm1', text: 'GESITS EV READY', enabled: true, speedMs: 45),
      MatrixQueueItem(id: 'm2', text: 'STAY SAFE ON ROAD', enabled: true, speedMs: 50),
    ];
  }

  Future<void> saveMatrixQueue(List<MatrixQueueItem> list) =>
      _writeJsonList(_kMatrixFile, list.map((e) => e.toJson()).toList());
}
