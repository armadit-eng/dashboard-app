import 'dart:math' as math;

enum ConnectionStateX {
  disconnected,
  scanning,
  connecting,
  connected,
}

enum BatterySourcePriority {
  motor('motor', 'Motor MID'),
  jikong('jikong', 'JK-BMS');

  const BatterySourcePriority(this.id, this.label);
  final String id;
  final String label;

  static BatterySourcePriority fromId(String? id) =>
      id == 'jikong' ? BatterySourcePriority.jikong : BatterySourcePriority.motor;
}

enum ChargingIndicatorState {
  charging('Mengisi Daya'),
  full('Terisi Penuh'),
  notCharging('Tidak Mengisi'),
  regen('Regeneratif');

  const ChargingIndicatorState(this.label);
  final String label;
}

class DiscoveredDeviceX {
  final String name;
  final String address;
  final bool isBle;
  final int rssi;
  final bool isPaired;

  const DiscoveredDeviceX({
    required this.name,
    required this.address,
    this.isBle = false,
    this.rssi = 0,
    this.isPaired = false,
  });
}

class ErrorDefinition {
  final int bit;
  final String label;
  final String fix;

  const ErrorDefinition({
    required this.bit,
    required this.label,
    required this.fix,
  });
}

const List<ErrorDefinition> kMotorErrorDefs = [
  ErrorDefinition(
    bit: 0,
    label: 'Inverter Over Temperature',
    fix: 'Diamkan motor hingga suhu inverter normal, hindari beban berat terus-menerus.',
  ),
  ErrorDefinition(
    bit: 1,
    label: 'Motor Over Temperature',
    fix: 'Istirahatkan motor sampai suhu turun ke rentang normal.',
  ),
  ErrorDefinition(
    bit: 2,
    label: 'Motor Fault',
    fix: 'Periksa kabel & konektor fasa motor; bawa ke bengkel jika berulang.',
  ),
  ErrorDefinition(
    bit: 3,
    label: 'Hall Sensor Fault',
    fix: 'Cek konektor sensor hall motor, pastikan tidak longgar atau kotor.',
  ),
  ErrorDefinition(
    bit: 4,
    label: 'Throttle Fault',
    fix: 'Periksa posisi & kabel gas (throttle), pastikan kembali ke nol.',
  ),
  ErrorDefinition(
    bit: 5,
    label: 'Controller Fault',
    fix: 'Restart sistem (kontak OFF lalu ON). Jika menetap, cek di bengkel resmi.',
  ),
  ErrorDefinition(
    bit: 6,
    label: 'Over Voltage',
    fix: 'Periksa charger & sumber listrik, pastikan tegangan pack tidak melebihi batas.',
  ),
  ErrorDefinition(
    bit: 7,
    label: 'Under Voltage',
    fix: 'Segera isi daya baterai, tegangan baterai terlalu rendah.',
  ),
  ErrorDefinition(
    bit: 11,
    label: 'Throttle Signal Abnormal',
    fix: 'Cek soket sinyal throttle dan kabel bodi depan.',
  ),
];

class MotorData {
  final double? speedKmh;
  final double? vbat;
  final double? ibat;
  final double? throttlePct;
  final double? socPct;
  final double? sohPct;
  final int? cbatCycles;
  final double? odoKm;
  final int errMask;
  final int? modeIndex;
  final bool? isPark;
  final double? tempInverterC;
  final double? tempMotorC;
  final double? tempBatteryC;
  final int lastUpdatedMs;

  const MotorData({
    this.speedKmh,
    this.vbat,
    this.ibat,
    this.throttlePct,
    this.socPct,
    this.sohPct,
    this.cbatCycles,
    this.odoKm,
    this.errMask = 0,
    this.modeIndex,
    this.isPark,
    this.tempInverterC,
    this.tempMotorC,
    this.tempBatteryC,
    this.lastUpdatedMs = 0,
  });

  MotorData copyWith({
    double? speedKmh,
    double? vbat,
    double? ibat,
    double? throttlePct,
    double? socPct,
    double? sohPct,
    int? cbatCycles,
    double? odoKm,
    int? errMask,
    int? modeIndex,
    bool? isPark,
    double? tempInverterC,
    double? tempMotorC,
    double? tempBatteryC,
    int? lastUpdatedMs,
  }) {
    return MotorData(
      speedKmh: speedKmh ?? this.speedKmh,
      vbat: vbat ?? this.vbat,
      ibat: ibat ?? this.ibat,
      throttlePct: throttlePct ?? this.throttlePct,
      socPct: socPct ?? this.socPct,
      sohPct: sohPct ?? this.sohPct,
      cbatCycles: cbatCycles ?? this.cbatCycles,
      odoKm: odoKm ?? this.odoKm,
      errMask: errMask ?? this.errMask,
      modeIndex: modeIndex ?? this.modeIndex,
      isPark: isPark ?? this.isPark,
      tempInverterC: tempInverterC ?? this.tempInverterC,
      tempMotorC: tempMotorC ?? this.tempMotorC,
      tempBatteryC: tempBatteryC ?? this.tempBatteryC,
      lastUpdatedMs: lastUpdatedMs ?? this.lastUpdatedMs,
    );
  }
}

class JkBmsData {
  final double? vpack;
  final double? ibat;
  final double? socPct;
  final double? sohPct;
  final double? temp1C;
  final double? temp2C;
  final double? tempMosC;
  final double? deltaCellV;
  final double? minCellV;
  final double? maxCellV;
  final int minCellIndex;
  final int maxCellIndex;
  final int cellCount;
  final List<double> cellVoltages;
  final int? cycles;
  final double? remainAh;
  final double? capacityAh;
  final bool? balancingActive;
  final int lastUpdatedMs;

  const JkBmsData({
    this.vpack,
    this.ibat,
    this.socPct,
    this.sohPct,
    this.temp1C,
    this.temp2C,
    this.tempMosC,
    this.deltaCellV,
    this.minCellV,
    this.maxCellV,
    this.minCellIndex = -1,
    this.maxCellIndex = -1,
    this.cellCount = 0,
    this.cellVoltages = const [],
    this.cycles,
    this.remainAh,
    this.capacityAh,
    this.balancingActive,
    this.lastUpdatedMs = 0,
  });
}

class Battery2Data {
  final double? vbat;
  final double? ibat;
  final double? powerW;
  final double? socPct;
  final double? sohPct;
  final double? tempC;
  final double? deltaCellV;
  final int cellCount;
  final List<double> cellVoltages;
  final int? cycles;
  final double? remainAh;
  final String canMode;
  final String? simMode;
  final int lastUpdatedMs;

  const Battery2Data({
    this.vbat,
    this.ibat,
    this.powerW,
    this.socPct,
    this.sohPct,
    this.tempC,
    this.deltaCellV,
    this.cellCount = 0,
    this.cellVoltages = const [],
    this.cycles,
    this.remainAh,
    this.canMode = 'LISTEN',
    this.simMode,
    this.lastUpdatedMs = 0,
  });

  Battery2Data copyWith({
    double? vbat,
    double? ibat,
    double? powerW,
    double? socPct,
    double? sohPct,
    double? tempC,
    double? deltaCellV,
    int? cellCount,
    List<double>? cellVoltages,
    int? cycles,
    double? remainAh,
    String? canMode,
    String? simMode,
    int? lastUpdatedMs,
  }) {
    return Battery2Data(
      vbat: vbat ?? this.vbat,
      ibat: ibat ?? this.ibat,
      powerW: powerW ?? this.powerW,
      socPct: socPct ?? this.socPct,
      sohPct: sohPct ?? this.sohPct,
      tempC: tempC ?? this.tempC,
      deltaCellV: deltaCellV ?? this.deltaCellV,
      cellCount: cellCount ?? this.cellCount,
      cellVoltages: cellVoltages ?? this.cellVoltages,
      cycles: cycles ?? this.cycles,
      remainAh: remainAh ?? this.remainAh,
      canMode: canMode ?? this.canMode,
      simMode: simMode ?? this.simMode,
      lastUpdatedMs: lastUpdatedMs ?? this.lastUpdatedMs,
    );
  }
}

class Esp32Data {
  final double? speedKmh;
  final double? throttlePct;
  final bool cruiseActive;
  final bool reverseActive;
  final bool sideStandDown;
  final int? modeIndex;
  final bool? isPark;
  final bool? contactOn;
  final bool? matrixOn;
  final bool matrixBusy;
  final int musicTrack;
  final String musicState;
  final int musicVol;
  final int lastUpdatedMs;

  const Esp32Data({
    this.speedKmh,
    this.throttlePct,
    this.cruiseActive = false,
    this.reverseActive = false,
    this.sideStandDown = false,
    this.modeIndex,
    this.isPark,
    this.contactOn,
    this.matrixOn,
    this.matrixBusy = false,
    this.musicTrack = 1,
    this.musicState = 'STOP',
    this.musicVol = 20,
    this.lastUpdatedMs = 0,
  });

  Esp32Data copyWith({
    double? speedKmh,
    double? throttlePct,
    bool? cruiseActive,
    bool? reverseActive,
    bool? sideStandDown,
    int? modeIndex,
    bool? isPark,
    bool? contactOn,
    bool? matrixOn,
    bool? matrixBusy,
    int? musicTrack,
    String? musicState,
    int? musicVol,
    int? lastUpdatedMs,
  }) {
    return Esp32Data(
      speedKmh: speedKmh ?? this.speedKmh,
      throttlePct: throttlePct ?? this.throttlePct,
      cruiseActive: cruiseActive ?? this.cruiseActive,
      reverseActive: reverseActive ?? this.reverseActive,
      sideStandDown: sideStandDown ?? this.sideStandDown,
      modeIndex: modeIndex ?? this.modeIndex,
      isPark: isPark ?? this.isPark,
      contactOn: contactOn ?? this.contactOn,
      matrixOn: matrixOn ?? this.matrixOn,
      matrixBusy: matrixBusy ?? this.matrixBusy,
      musicTrack: musicTrack ?? this.musicTrack,
      musicState: musicState ?? this.musicState,
      musicVol: musicVol ?? this.musicVol,
      lastUpdatedMs: lastUpdatedMs ?? this.lastUpdatedMs,
    );
  }
}

class LiveTripMetrics {
  final int startTimeMs;
  final double distanceKm;
  final double energyWh;
  final double consumedAh;
  final double regenWh;
  final double chargeWh;
  final double maxSpeedKmh;
  final double maxPowerW;
  final double maxRegenW;
  final int movingSeconds;
  final int stopCount;
  final int lastSampleMs;
  final double? startSocPct;

  const LiveTripMetrics({
    this.startTimeMs = 0,
    this.distanceKm = 0,
    this.energyWh = 0,
    this.consumedAh = 0,
    this.regenWh = 0,
    this.chargeWh = 0,
    this.maxSpeedKmh = 0,
    this.maxPowerW = 0,
    this.maxRegenW = 0,
    this.movingSeconds = 0,
    this.stopCount = 0,
    this.lastSampleMs = 0,
    this.startSocPct,
  });

  double get rawWhPerKm => distanceKm > 0.05 ? energyWh / distanceKm : 0;
  double get avgSpeedKmh =>
      movingSeconds > 0 ? distanceKm / (movingSeconds / 3600.0) : 0;
  double get netWhPerKm =>
      distanceKm > 0.05 ? math.max(0.0, energyWh - regenWh) / distanceKm : 0;

  LiveTripMetrics copyWith({
    int? startTimeMs,
    double? distanceKm,
    double? energyWh,
    double? consumedAh,
    double? regenWh,
    double? chargeWh,
    double? maxSpeedKmh,
    double? maxPowerW,
    double? maxRegenW,
    int? movingSeconds,
    int? stopCount,
    int? lastSampleMs,
    double? startSocPct,
  }) {
    return LiveTripMetrics(
      startTimeMs: startTimeMs ?? this.startTimeMs,
      distanceKm: distanceKm ?? this.distanceKm,
      energyWh: energyWh ?? this.energyWh,
      consumedAh: consumedAh ?? this.consumedAh,
      regenWh: regenWh ?? this.regenWh,
      chargeWh: chargeWh ?? this.chargeWh,
      maxSpeedKmh: maxSpeedKmh ?? this.maxSpeedKmh,
      maxPowerW: maxPowerW ?? this.maxPowerW,
      maxRegenW: maxRegenW ?? this.maxRegenW,
      movingSeconds: movingSeconds ?? this.movingSeconds,
      stopCount: stopCount ?? this.stopCount,
      lastSampleMs: lastSampleMs ?? this.lastSampleMs,
      startSocPct: startSocPct ?? this.startSocPct,
    );
  }
}

class RidingScoreState {
  final int samples;
  final int ecoViolations;
  final int hardBrakes;
  final int regenEvents;
  final double lastSpeedKmh;

  const RidingScoreState({
    this.samples = 0,
    this.ecoViolations = 0,
    this.hardBrakes = 0,
    this.regenEvents = 0,
    this.lastSpeedKmh = 0,
  });

  int? get score {
    if (samples < 3) return null;
    final ecoRatio = 1.0 - (ecoViolations / samples);
    final brakeRatio = 1.0 - math.min(1.0, hardBrakes / (samples * 0.05));
    final regenRatio = math.min(1.0, regenEvents / (samples * 0.10));
    return (ecoRatio * 50.0 + brakeRatio * 30.0 + regenRatio * 20.0)
        .round()
        .clamp(0, 100);
  }

  String get label {
    final s = score;
    if (s == null) return 'Belum ada data';
    if (s >= 80) return 'Efisien';
    if (s >= 60) return 'Normal';
    return 'Boros';
  }

  RidingScoreState copyWith({
    int? samples,
    int? ecoViolations,
    int? hardBrakes,
    int? regenEvents,
    double? lastSpeedKmh,
  }) {
    return RidingScoreState(
      samples: samples ?? this.samples,
      ecoViolations: ecoViolations ?? this.ecoViolations,
      hardBrakes: hardBrakes ?? this.hardBrakes,
      regenEvents: regenEvents ?? this.regenEvents,
      lastSpeedKmh: lastSpeedKmh ?? this.lastSpeedKmh,
    );
  }
}

class EffectiveTelemetry {
  final double? speedKmh;
  final int? rpm;
  final double? throttlePct;
  final double? b1SocPct;
  final double? b1Vbat;
  final double? b1Ibat;
  final double? b1TempC;
  final double? b1SohPct;
  final String b1SourceLabel;
  final bool b2Active;
  final double? b2SocPct;
  final double? b2Vbat;
  final double? b2Ibat;
  final double? b2TempC;
  final double? combinedSocPct;
  final double? combinedSohPct;
  final double? combinedPowerW;
  final double? combinedCurrentA;
  final double? avgVoltageV;
  final bool isCharging;
  final bool isRegen;
  final double rangeKm;
  final double blendedWhPerKm;
  final double totalAvailableWh;
  final double? chargeEtaHours;
  final double? odoKm;
  final String modeLabel;
  final bool? isPark;
  final bool sideStandDown;
  final bool cruiseActive;
  final bool reverseActive;
  final List<ErrorDefinition> activeErrors;

  const EffectiveTelemetry({
    this.speedKmh,
    this.rpm,
    this.throttlePct,
    this.b1SocPct,
    this.b1Vbat,
    this.b1Ibat,
    this.b1TempC,
    this.b1SohPct,
    this.b1SourceLabel = '—',
    this.b2Active = false,
    this.b2SocPct,
    this.b2Vbat,
    this.b2Ibat,
    this.b2TempC,
    this.combinedSocPct,
    this.combinedSohPct,
    this.combinedPowerW,
    this.combinedCurrentA,
    this.avgVoltageV,
    this.isCharging = false,
    this.isRegen = false,
    this.rangeKm = 0,
    this.blendedWhPerKm = 28,
    this.totalAvailableWh = 0,
    this.chargeEtaHours,
    this.odoKm,
    this.modeLabel = '—',
    this.isPark,
    this.sideStandDown = false,
    this.cruiseActive = false,
    this.reverseActive = false,
    this.activeErrors = const [],
  });
}

enum AlertSeverity { info, warning, critical }

class AppAlert {
  final String id;
  final String title;
  final String message;
  final AlertSeverity severity;
  final int timestampMs;

  const AppAlert({
    required this.id,
    required this.title,
    required this.message,
    required this.severity,
    required this.timestampMs,
  });
}

class TelemetryPoint {
  final int timestampMs;
  final double speedKmh;
  final double throttlePct;
  final double powerW;
  final double socPct;
  final double voltageV;
  final double currentA;
  final double tempC;
  final double? lat;
  final double? lng;
  final double? altM;

  const TelemetryPoint({
    required this.timestampMs,
    required this.speedKmh,
    required this.throttlePct,
    required this.powerW,
    required this.socPct,
    required this.voltageV,
    required this.currentA,
    required this.tempC,
    this.lat,
    this.lng,
    this.altM,
  });

  Map<String, dynamic> toJson() => {
        't': timestampMs,
        'spd': speedKmh,
        'trt': throttlePct,
        'pwr': powerW,
        'soc': socPct,
        'v': voltageV,
        'i': currentA,
        'tmp': tempC,
        'lat': lat,
        'lng': lng,
        'alt': altM,
      };

  factory TelemetryPoint.fromJson(Map<String, dynamic> m) => TelemetryPoint(
        timestampMs: (m['t'] as num?)?.toInt() ?? 0,
        speedKmh: (m['spd'] as num?)?.toDouble() ?? 0,
        throttlePct: (m['trt'] as num?)?.toDouble() ?? 0,
        powerW: (m['pwr'] as num?)?.toDouble() ?? 0,
        socPct: (m['soc'] as num?)?.toDouble() ?? 0,
        voltageV: (m['v'] as num?)?.toDouble() ?? 0,
        currentA: (m['i'] as num?)?.toDouble() ?? 0,
        tempC: (m['tmp'] as num?)?.toDouble() ?? 0,
        lat: (m['lat'] as num?)?.toDouble(),
        lng: (m['lng'] as num?)?.toDouble(),
        altM: (m['alt'] as num?)?.toDouble(),
      );
}

class SerialLogEntry {
  final int timestampMs;
  final String source;
  final String message;
  final bool isError;

  const SerialLogEntry({
    required this.timestampMs,
    required this.source,
    required this.message,
    this.isError = false,
  });
}
