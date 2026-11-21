import 'navigation_models.dart';
import 'telemetry_models.dart';

class AtNavSlotConfig {
  final bool showRange;
  final bool showTrip;
  final bool showEnergy;
  final bool showEfficiency;
  final bool showPower;
  final bool showCo2;
  final bool showLocation;
  final bool showWeather;
  final int intervalSec;

  const AtNavSlotConfig({
    this.showRange = true,
    this.showTrip = true,
    this.showEnergy = true,
    this.showEfficiency = true,
    this.showPower = true,
    this.showCo2 = false,
    this.showLocation = true,
    this.showWeather = true,
    this.intervalSec = 4,
  });

  AtNavSlotConfig copyWith({
    bool? showRange,
    bool? showTrip,
    bool? showEnergy,
    bool? showEfficiency,
    bool? showPower,
    bool? showCo2,
    bool? showLocation,
    bool? showWeather,
    int? intervalSec,
  }) {
    return AtNavSlotConfig(
      showRange: showRange ?? this.showRange,
      showTrip: showTrip ?? this.showTrip,
      showEnergy: showEnergy ?? this.showEnergy,
      showEfficiency: showEfficiency ?? this.showEfficiency,
      showPower: showPower ?? this.showPower,
      showCo2: showCo2 ?? this.showCo2,
      showLocation: showLocation ?? this.showLocation,
      showWeather: showWeather ?? this.showWeather,
      intervalSec: intervalSec ?? this.intervalSec,
    );
  }

  Map<String, dynamic> toJson() => {
        'showRange': showRange,
        'showTrip': showTrip,
        'showEnergy': showEnergy,
        'showEfficiency': showEfficiency,
        'showPower': showPower,
        'showCo2': showCo2,
        'showLocation': showLocation,
        'showWeather': showWeather,
        'intervalSec': intervalSec,
      };

  factory AtNavSlotConfig.fromJson(Map<String, dynamic>? m) {
    if (m == null) return const AtNavSlotConfig();
    return AtNavSlotConfig(
      showRange: m['showRange'] ?? true,
      showTrip: m['showTrip'] ?? true,
      showEnergy: m['showEnergy'] ?? true,
      showEfficiency: m['showEfficiency'] ?? true,
      showPower: m['showPower'] ?? true,
      showCo2: m['showCo2'] ?? false,
      showLocation: m['showLocation'] ?? true,
      showWeather: m['showWeather'] ?? true,
      intervalSec: (m['intervalSec'] as num?)?.toInt() ?? 4,
    );
  }
}

class MatrixQueueItem {
  final String id;
  final String text;
  final bool enabled;
  final int speedMs;
  final int effectId;

  const MatrixQueueItem({
    required this.id,
    required this.text,
    this.enabled = true,
    this.speedMs = 45,
    this.effectId = 0,
  });

  MatrixQueueItem copyWith({
    String? text,
    bool? enabled,
    int? speedMs,
    int? effectId,
  }) {
    return MatrixQueueItem(
      id: id,
      text: text ?? this.text,
      enabled: enabled ?? this.enabled,
      speedMs: speedMs ?? this.speedMs,
      effectId: effectId ?? this.effectId,
    );
  }

  Map<String, dynamic> toJson() => {
        'id': id,
        'text': text,
        'enabled': enabled,
        'speedMs': speedMs,
        'effectId': effectId,
      };

  factory MatrixQueueItem.fromJson(Map<String, dynamic> m) => MatrixQueueItem(
        id: m['id']?.toString() ?? '',
        text: m['text']?.toString() ?? 'GESITS EV',
        enabled: m['enabled'] ?? true,
        speedMs: (m['speedMs'] as num?)?.toInt() ?? 45,
        effectId: (m['effectId'] as num?)?.toInt() ?? 0,
      );
}

class AppSettings {
  final String riderName;
  final String bikeName;
  final String plateNumber;
  final String avatarPath;
  final bool isDarkTheme;
  final String accentId;
  final String garageStyleId;
  final String cockpitLayout; // 'complete' | 'compact'
  final BatterySourcePriority batteryPriority;
  final double b1NominalV;
  final double b1CapacityAh;
  final double b2NominalV;
  final double b2CapacityAh;
  final bool invertB2Current;
  final double refWhPerKm;
  final double bikeWeightKg;
  final double riderWeightKg;
  final double loadWeightKg;
  final double routeCalFactor;
  final double odoBackupKm;
  final double weeklyGoalKm;
  final double maintLastOdoKm;
  final double maintIntervalKm;
  final bool alarmMasterOn;
  final bool alarmSocLowOn;
  final int alarmSocThreshold;
  final bool alarmTempHighOn;
  final int alarmTempThresholdC;
  final bool alarmSohLowOn;
  final bool alarmDeltaCellOn;
  final bool alarmChargeLimitOn;
  final int alarmChargeLimitPct;
  final bool autoSaveTrip;
  final bool autoReconnect;
  final bool voiceNavEnabled;
  final bool hillAlertVoice;
  final String mapStyleId;
  final bool dataSaverMap;
  final String mapsLayout; // 'full' | 'split'
  final String mapsRiderPos; // 'center' | 'low'
  final int mapsSnapWidth;
  final int mapsSnapHeight;
  final int mapsLeChunkSize;
  final int mapsLeDelayMs;
  final int mapsIntervalMs;
  final bool customWallpaperEnabled;
  final String customWallpaperPath;
  final String customMarkerPath;
  final AtNavSlotConfig atNavSlots;
  final String? savedMotorAddress;
  final String? savedMotorName;
  final String? savedJkAddress;
  final String? savedEsp1Address;
  final String? savedEsp2Address;
  final String ocmApiKey;

  const AppSettings({
    this.riderName = 'Pengendara EV',
    this.bikeName = 'GESITS G1 Smart',
    this.plateNumber = 'B 4042 EVX',
    this.avatarPath = '',
    this.isDarkTheme = true,
    this.accentId = 'emerald',
    this.garageStyleId = 'stealth_obsidian',
    this.cockpitLayout = 'complete',
    this.batteryPriority = BatterySourcePriority.motor,
    this.b1NominalV = 72.0,
    this.b1CapacityAh = 20.0,
    this.b2NominalV = 72.0,
    this.b2CapacityAh = 20.0,
    this.invertB2Current = false,
    this.refWhPerKm = 28.0,
    this.bikeWeightKg = 95.0,
    this.riderWeightKg = 70.0,
    this.loadWeightKg = 5.0,
    this.routeCalFactor = 1.0,
    this.odoBackupKm = 0.0,
    this.weeklyGoalKm = 120.0,
    this.maintLastOdoKm = 0.0,
    this.maintIntervalKm = 2000.0,
    this.alarmMasterOn = true,
    this.alarmSocLowOn = true,
    this.alarmSocThreshold = 20,
    this.alarmTempHighOn = true,
    this.alarmTempThresholdC = 55,
    this.alarmSohLowOn = true,
    this.alarmDeltaCellOn = true,
    this.alarmChargeLimitOn = false,
    this.alarmChargeLimitPct = 90,
    this.autoSaveTrip = true,
    this.autoReconnect = true,
    this.voiceNavEnabled = true,
    this.hillAlertVoice = true,
    this.mapStyleId = 'dark',
    this.dataSaverMap = false,
    this.mapsLayout = 'split',
    this.mapsRiderPos = 'low',
    this.mapsSnapWidth = 240,
    this.mapsSnapHeight = 180,
    this.mapsLeChunkSize = 100,
    this.mapsLeDelayMs = 30,
    this.mapsIntervalMs = 1000,
    this.customWallpaperEnabled = false,
    this.customWallpaperPath = '',
    this.customMarkerPath = '',
    this.atNavSlots = const AtNavSlotConfig(),
    this.savedMotorAddress,
    this.savedMotorName,
    this.savedJkAddress,
    this.savedEsp1Address,
    this.savedEsp2Address,
    this.ocmApiKey = '',
  });

  AppSettings copyWith({
    String? riderName,
    String? bikeName,
    String? plateNumber,
    String? avatarPath,
    bool? isDarkTheme,
    String? accentId,
    String? garageStyleId,
    String? cockpitLayout,
    BatterySourcePriority? batteryPriority,
    double? b1NominalV,
    double? b1CapacityAh,
    double? b2NominalV,
    double? b2CapacityAh,
    bool? invertB2Current,
    double? refWhPerKm,
    double? bikeWeightKg,
    double? riderWeightKg,
    double? loadWeightKg,
    double? routeCalFactor,
    double? odoBackupKm,
    double? weeklyGoalKm,
    double? maintLastOdoKm,
    double? maintIntervalKm,
    bool? alarmMasterOn,
    bool? alarmSocLowOn,
    int? alarmSocThreshold,
    bool? alarmTempHighOn,
    int? alarmTempThresholdC,
    bool? alarmSohLowOn,
    bool? alarmDeltaCellOn,
    bool? alarmChargeLimitOn,
    int? alarmChargeLimitPct,
    bool? autoSaveTrip,
    bool? autoReconnect,
    bool? voiceNavEnabled,
    bool? hillAlertVoice,
    String? mapStyleId,
    bool? dataSaverMap,
    String? mapsLayout,
    String? mapsRiderPos,
    int? mapsSnapWidth,
    int? mapsSnapHeight,
    int? mapsLeChunkSize,
    int? mapsLeDelayMs,
    int? mapsIntervalMs,
    bool? customWallpaperEnabled,
    String? customWallpaperPath,
    String? customMarkerPath,
    AtNavSlotConfig? atNavSlots,
    String? savedMotorAddress,
    String? savedMotorName,
    String? savedJkAddress,
    String? savedEsp1Address,
    String? savedEsp2Address,
    String? ocmApiKey,
  }) {
    return AppSettings(
      riderName: riderName ?? this.riderName,
      bikeName: bikeName ?? this.bikeName,
      plateNumber: plateNumber ?? this.plateNumber,
      avatarPath: avatarPath ?? this.avatarPath,
      isDarkTheme: isDarkTheme ?? this.isDarkTheme,
      accentId: accentId ?? this.accentId,
      garageStyleId: garageStyleId ?? this.garageStyleId,
      cockpitLayout: cockpitLayout ?? this.cockpitLayout,
      batteryPriority: batteryPriority ?? this.batteryPriority,
      b1NominalV: b1NominalV ?? this.b1NominalV,
      b1CapacityAh: b1CapacityAh ?? this.b1CapacityAh,
      b2NominalV: b2NominalV ?? this.b2NominalV,
      b2CapacityAh: b2CapacityAh ?? this.b2CapacityAh,
      invertB2Current: invertB2Current ?? this.invertB2Current,
      refWhPerKm: refWhPerKm ?? this.refWhPerKm,
      bikeWeightKg: bikeWeightKg ?? this.bikeWeightKg,
      riderWeightKg: riderWeightKg ?? this.riderWeightKg,
      loadWeightKg: loadWeightKg ?? this.loadWeightKg,
      routeCalFactor: routeCalFactor ?? this.routeCalFactor,
      odoBackupKm: odoBackupKm ?? this.odoBackupKm,
      weeklyGoalKm: weeklyGoalKm ?? this.weeklyGoalKm,
      maintLastOdoKm: maintLastOdoKm ?? this.maintLastOdoKm,
      maintIntervalKm: maintIntervalKm ?? this.maintIntervalKm,
      alarmMasterOn: alarmMasterOn ?? this.alarmMasterOn,
      alarmSocLowOn: alarmSocLowOn ?? this.alarmSocLowOn,
      alarmSocThreshold: alarmSocThreshold ?? this.alarmSocThreshold,
      alarmTempHighOn: alarmTempHighOn ?? this.alarmTempHighOn,
      alarmTempThresholdC: alarmTempThresholdC ?? this.alarmTempThresholdC,
      alarmSohLowOn: alarmSohLowOn ?? this.alarmSohLowOn,
      alarmDeltaCellOn: alarmDeltaCellOn ?? this.alarmDeltaCellOn,
      alarmChargeLimitOn: alarmChargeLimitOn ?? this.alarmChargeLimitOn,
      alarmChargeLimitPct: alarmChargeLimitPct ?? this.alarmChargeLimitPct,
      autoSaveTrip: autoSaveTrip ?? this.autoSaveTrip,
      autoReconnect: autoReconnect ?? this.autoReconnect,
      voiceNavEnabled: voiceNavEnabled ?? this.voiceNavEnabled,
      hillAlertVoice: hillAlertVoice ?? this.hillAlertVoice,
      mapStyleId: mapStyleId ?? this.mapStyleId,
      dataSaverMap: dataSaverMap ?? this.dataSaverMap,
      mapsLayout: mapsLayout ?? this.mapsLayout,
      mapsRiderPos: mapsRiderPos ?? this.mapsRiderPos,
      mapsSnapWidth: mapsSnapWidth ?? this.mapsSnapWidth,
      mapsSnapHeight: mapsSnapHeight ?? this.mapsSnapHeight,
      mapsLeChunkSize: mapsLeChunkSize ?? this.mapsLeChunkSize,
      mapsLeDelayMs: mapsLeDelayMs ?? this.mapsLeDelayMs,
      mapsIntervalMs: mapsIntervalMs ?? this.mapsIntervalMs,
      customWallpaperEnabled:
          customWallpaperEnabled ?? this.customWallpaperEnabled,
      customWallpaperPath: customWallpaperPath ?? this.customWallpaperPath,
      customMarkerPath: customMarkerPath ?? this.customMarkerPath,
      atNavSlots: atNavSlots ?? this.atNavSlots,
      savedMotorAddress: savedMotorAddress ?? this.savedMotorAddress,
      savedMotorName: savedMotorName ?? this.savedMotorName,
      savedJkAddress: savedJkAddress ?? this.savedJkAddress,
      savedEsp1Address: savedEsp1Address ?? this.savedEsp1Address,
      savedEsp2Address: savedEsp2Address ?? this.savedEsp2Address,
      ocmApiKey: ocmApiKey ?? this.ocmApiKey,
    );
  }

  Map<String, dynamic> toJson() => {
        'riderName': riderName,
        'bikeName': bikeName,
        'plateNumber': plateNumber,
        'avatarPath': avatarPath,
        'isDarkTheme': isDarkTheme,
        'accentId': accentId,
        'garageStyleId': garageStyleId,
        'cockpitLayout': cockpitLayout,
        'batteryPriority': batteryPriority.id,
        'b1NominalV': b1NominalV,
        'b1CapacityAh': b1CapacityAh,
        'b2NominalV': b2NominalV,
        'b2CapacityAh': b2CapacityAh,
        'invertB2Current': invertB2Current,
        'refWhPerKm': refWhPerKm,
        'bikeWeightKg': bikeWeightKg,
        'riderWeightKg': riderWeightKg,
        'loadWeightKg': loadWeightKg,
        'routeCalFactor': routeCalFactor,
        'odoBackupKm': odoBackupKm,
        'weeklyGoalKm': weeklyGoalKm,
        'maintLastOdoKm': maintLastOdoKm,
        'maintIntervalKm': maintIntervalKm,
        'alarmMasterOn': alarmMasterOn,
        'alarmSocLowOn': alarmSocLowOn,
        'alarmSocThreshold': alarmSocThreshold,
        'alarmTempHighOn': alarmTempHighOn,
        'alarmTempThresholdC': alarmTempThresholdC,
        'alarmSohLowOn': alarmSohLowOn,
        'alarmDeltaCellOn': alarmDeltaCellOn,
        'alarmChargeLimitOn': alarmChargeLimitOn,
        'alarmChargeLimitPct': alarmChargeLimitPct,
        'autoSaveTrip': autoSaveTrip,
        'autoReconnect': autoReconnect,
        'voiceNavEnabled': voiceNavEnabled,
        'hillAlertVoice': hillAlertVoice,
        'mapStyleId': mapStyleId,
        'dataSaverMap': dataSaverMap,
        'mapsLayout': mapsLayout,
        'mapsRiderPos': mapsRiderPos,
        'mapsSnapWidth': mapsSnapWidth,
        'mapsSnapHeight': mapsSnapHeight,
        'mapsLeChunkSize': mapsLeChunkSize,
        'mapsLeDelayMs': mapsLeDelayMs,
        'mapsIntervalMs': mapsIntervalMs,
        'customWallpaperEnabled': customWallpaperEnabled,
        'customWallpaperPath': customWallpaperPath,
        'customMarkerPath': customMarkerPath,
        'atNavSlots': atNavSlots.toJson(),
        'savedMotorAddress': savedMotorAddress,
        'savedMotorName': savedMotorName,
        'savedJkAddress': savedJkAddress,
        'savedEsp1Address': savedEsp1Address,
        'savedEsp2Address': savedEsp2Address,
        'ocmApiKey': ocmApiKey,
      };

  factory AppSettings.fromJson(Map<String, dynamic> m) => AppSettings(
        riderName: m['riderName']?.toString() ?? 'Pengendara EV',
        bikeName: m['bikeName']?.toString() ?? 'GESITS G1 Smart',
        plateNumber: m['plateNumber']?.toString() ?? 'B 4042 EVX',
        avatarPath: m['avatarPath']?.toString() ?? '',
        isDarkTheme: m['isDarkTheme'] ?? true,
        accentId: m['accentId']?.toString() ?? 'emerald',
        garageStyleId: m['garageStyleId']?.toString() ?? 'stealth_obsidian',
        cockpitLayout: m['cockpitLayout']?.toString() ?? 'complete',
        batteryPriority:
            BatterySourcePriority.fromId(m['batteryPriority']?.toString()),
        b1NominalV: (m['b1NominalV'] as num?)?.toDouble() ?? 72.0,
        b1CapacityAh: (m['b1CapacityAh'] as num?)?.toDouble() ?? 20.0,
        b2NominalV: (m['b2NominalV'] as num?)?.toDouble() ?? 72.0,
        b2CapacityAh: (m['b2CapacityAh'] as num?)?.toDouble() ?? 20.0,
        invertB2Current: m['invertB2Current'] ?? false,
        refWhPerKm: (m['refWhPerKm'] as num?)?.toDouble() ?? 28.0,
        bikeWeightKg: (m['bikeWeightKg'] as num?)?.toDouble() ?? 95.0,
        riderWeightKg: (m['riderWeightKg'] as num?)?.toDouble() ?? 70.0,
        loadWeightKg: (m['loadWeightKg'] as num?)?.toDouble() ?? 5.0,
        routeCalFactor: (m['routeCalFactor'] as num?)?.toDouble() ?? 1.0,
        odoBackupKm: (m['odoBackupKm'] as num?)?.toDouble() ?? 0.0,
        weeklyGoalKm: (m['weeklyGoalKm'] as num?)?.toDouble() ?? 120.0,
        maintLastOdoKm: (m['maintLastOdoKm'] as num?)?.toDouble() ?? 0.0,
        maintIntervalKm: (m['maintIntervalKm'] as num?)?.toDouble() ?? 2000.0,
        alarmMasterOn: m['alarmMasterOn'] ?? true,
        alarmSocLowOn: m['alarmSocLowOn'] ?? true,
        alarmSocThreshold: (m['alarmSocThreshold'] as num?)?.toInt() ?? 20,
        alarmTempHighOn: m['alarmTempHighOn'] ?? true,
        alarmTempThresholdC: (m['alarmTempThresholdC'] as num?)?.toInt() ?? 55,
        alarmSohLowOn: m['alarmSohLowOn'] ?? true,
        alarmDeltaCellOn: m['alarmDeltaCellOn'] ?? true,
        alarmChargeLimitOn: m['alarmChargeLimitOn'] ?? false,
        alarmChargeLimitPct: (m['alarmChargeLimitPct'] as num?)?.toInt() ?? 90,
        autoSaveTrip: m['autoSaveTrip'] ?? true,
        autoReconnect: m['autoReconnect'] ?? true,
        voiceNavEnabled: m['voiceNavEnabled'] ?? true,
        hillAlertVoice: m['hillAlertVoice'] ?? true,
        mapStyleId: m['mapStyleId']?.toString() ?? 'dark',
        dataSaverMap: m['dataSaverMap'] ?? false,
        mapsLayout: m['mapsLayout']?.toString() ?? 'split',
        mapsRiderPos: m['mapsRiderPos']?.toString() ?? 'low',
        mapsSnapWidth: (m['mapsSnapWidth'] as num?)?.toInt() ?? 240,
        mapsSnapHeight: (m['mapsSnapHeight'] as num?)?.toInt() ?? 180,
        mapsLeChunkSize: (m['mapsLeChunkSize'] as num?)?.toInt() ?? 100,
        mapsLeDelayMs: (m['mapsLeDelayMs'] as num?)?.toInt() ?? 30,
        mapsIntervalMs: (m['mapsIntervalMs'] as num?)?.toInt() ?? 1000,
        customWallpaperEnabled: m['customWallpaperEnabled'] ?? false,
        customWallpaperPath: m['customWallpaperPath']?.toString() ?? '',
        customMarkerPath: m['customMarkerPath']?.toString() ?? '',
        atNavSlots: AtNavSlotConfig.fromJson(
            m['atNavSlots'] as Map<String, dynamic>?),
        savedMotorAddress: m['savedMotorAddress']?.toString(),
        savedMotorName: m['savedMotorName']?.toString(),
        savedJkAddress: m['savedJkAddress']?.toString(),
        savedEsp1Address: m['savedEsp1Address']?.toString(),
        savedEsp2Address: m['savedEsp2Address']?.toString(),
        ocmApiKey: m['ocmApiKey']?.toString() ?? '',
      );
}

class TripRecord {
  final String id;
  final int startTimeMs;
  final int endTimeMs;
  final double distanceKm;
  final double energyWh;
  final double regenWh;
  final double maxSpeedKmh;
  final double avgSpeedKmh;
  final double maxPowerW;
  final int? ridingScore;
  final String startArea;
  final String endArea;
  final List<GeoPointX> pathPoints;
  final List<TelemetryPoint> samples;

  const TripRecord({
    required this.id,
    required this.startTimeMs,
    required this.endTimeMs,
    required this.distanceKm,
    required this.energyWh,
    required this.regenWh,
    required this.maxSpeedKmh,
    required this.avgSpeedKmh,
    required this.maxPowerW,
    this.ridingScore,
    this.startArea = '',
    this.endArea = '',
    this.pathPoints = const [],
    this.samples = const [],
  });

  double get whPerKm => distanceKm > 0.05 ? energyWh / distanceKm : 0;

  Map<String, dynamic> toJson() => {
        'id': id,
        'startTimeMs': startTimeMs,
        'endTimeMs': endTimeMs,
        'distanceKm': distanceKm,
        'energyWh': energyWh,
        'regenWh': regenWh,
        'maxSpeedKmh': maxSpeedKmh,
        'avgSpeedKmh': avgSpeedKmh,
        'maxPowerW': maxPowerW,
        'ridingScore': ridingScore,
        'startArea': startArea,
        'endArea': endArea,
        'pathPoints': pathPoints.map((e) => e.toJson()).toList(),
        'samples': samples.map((e) => e.toJson()).toList(),
      };

  factory TripRecord.fromJson(Map<String, dynamic> m) => TripRecord(
        id: m['id']?.toString() ?? '',
        startTimeMs: (m['startTimeMs'] as num?)?.toInt() ?? 0,
        endTimeMs: (m['endTimeMs'] as num?)?.toInt() ?? 0,
        distanceKm: (m['distanceKm'] as num?)?.toDouble() ?? 0,
        energyWh: (m['energyWh'] as num?)?.toDouble() ?? 0,
        regenWh: (m['regenWh'] as num?)?.toDouble() ?? 0,
        maxSpeedKmh: (m['maxSpeedKmh'] as num?)?.toDouble() ?? 0,
        avgSpeedKmh: (m['avgSpeedKmh'] as num?)?.toDouble() ?? 0,
        maxPowerW: (m['maxPowerW'] as num?)?.toDouble() ?? 0,
        ridingScore: (m['ridingScore'] as num?)?.toInt(),
        startArea: m['startArea']?.toString() ?? '',
        endArea: m['endArea']?.toString() ?? '',
        pathPoints: (m['pathPoints'] as List?)
                ?.map((e) => GeoPointX.fromJson(Map<String, dynamic>.from(e)))
                .toList() ??
            const [],
        samples: (m['samples'] as List?)
                ?.map((e) =>
                    TelemetryPoint.fromJson(Map<String, dynamic>.from(e)))
                .toList() ??
            const [],
      );
}

class MaintenanceLogEntry {
  final String id;
  final double odoKm;
  final String serviceType;
  final String notes;
  final int costRp;
  final int timestampMs;

  const MaintenanceLogEntry({
    required this.id,
    required this.odoKm,
    required this.serviceType,
    this.notes = '',
    this.costRp = 0,
    required this.timestampMs,
  });

  Map<String, dynamic> toJson() => {
        'id': id,
        'odoKm': odoKm,
        'serviceType': serviceType,
        'notes': notes,
        'costRp': costRp,
        'timestampMs': timestampMs,
      };

  factory MaintenanceLogEntry.fromJson(Map<String, dynamic> m) =>
      MaintenanceLogEntry(
        id: m['id']?.toString() ?? '',
        odoKm: (m['odoKm'] as num?)?.toDouble() ?? 0,
        serviceType: m['serviceType']?.toString() ?? 'Servis Berkala',
        notes: m['notes']?.toString() ?? '',
        costRp: (m['costRp'] as num?)?.toInt() ?? 0,
        timestampMs: (m['timestampMs'] as num?)?.toInt() ?? 0,
      );
}

enum MaintHealthLevel { ok, warn, due }

class MaintenanceStatus {
  final double lastServiceOdoKm;
  final double intervalKm;
  final double nextServiceOdoKm;
  final double remainingKm;
  final double overdueKm;
  final double progressPct;
  final MaintHealthLevel level;

  const MaintenanceStatus({
    required this.lastServiceOdoKm,
    required this.intervalKm,
    required this.nextServiceOdoKm,
    required this.remainingKm,
    required this.overdueKm,
    required this.progressPct,
    required this.level,
  });
}

class FuelSavingsSummary {
  final double totalKm;
  final int petrolCostRp;
  final int electricityCostRp;
  final int netSavingsRp;
  final double co2SavedKg;

  const FuelSavingsSummary({
    required this.totalKm,
    required this.petrolCostRp,
    required this.electricityCostRp,
    required this.netSavingsRp,
    required this.co2SavedKg,
  });
}

class CalibrationEntry {
  final String id;
  final double distanceKm;
  final double predictedWh;
  final double actualWh;
  final double suggestedFactor;
  final int timestampMs;

  const CalibrationEntry({
    required this.id,
    required this.distanceKm,
    required this.predictedWh,
    required this.actualWh,
    required this.suggestedFactor,
    required this.timestampMs,
  });

  double get errorPct =>
      predictedWh > 1 ? ((actualWh - predictedWh) / predictedWh) * 100.0 : 0;

  Map<String, dynamic> toJson() => {
        'id': id,
        'distanceKm': distanceKm,
        'predictedWh': predictedWh,
        'actualWh': actualWh,
        'suggestedFactor': suggestedFactor,
        'timestampMs': timestampMs,
      };

  factory CalibrationEntry.fromJson(Map<String, dynamic> m) =>
      CalibrationEntry(
        id: m['id']?.toString() ?? '',
        distanceKm: (m['distanceKm'] as num?)?.toDouble() ?? 0,
        predictedWh: (m['predictedWh'] as num?)?.toDouble() ?? 0,
        actualWh: (m['actualWh'] as num?)?.toDouble() ?? 0,
        suggestedFactor: (m['suggestedFactor'] as num?)?.toDouble() ?? 1.0,
        timestampMs: (m['timestampMs'] as num?)?.toInt() ?? 0,
      );
}

class RiderBadge {
  final String id;
  final String title;
  final String description;
  final bool unlocked;
  final double progress;

  const RiderBadge({
    required this.id,
    required this.title,
    required this.description,
    required this.unlocked,
    required this.progress,
  });
}

class ParkedLocation {
  final double lat;
  final double lng;
  final String label;
  final int timestampMs;

  const ParkedLocation({
    required this.lat,
    required this.lng,
    required this.label,
    required this.timestampMs,
  });

  Map<String, dynamic> toJson() => {
        'lat': lat,
        'lng': lng,
        'label': label,
        'timestampMs': timestampMs,
      };

  factory ParkedLocation.fromJson(Map<String, dynamic> m) => ParkedLocation(
        lat: (m['lat'] as num).toDouble(),
        lng: (m['lng'] as num).toDouble(),
        label: m['label']?.toString() ?? 'Lokasi Terakhir Motor',
        timestampMs: (m['timestampMs'] as num?)?.toInt() ?? 0,
      );
}
