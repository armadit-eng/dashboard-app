import 'dart:math' as math;
import 'dart:typed_data';

import '../models/app_models.dart';
import '../models/telemetry_models.dart';

class TelemetryEngine {
  static const int staleMs = 10000;
  static const int integrateMaxGapMs = 4500;

  static MotorData? parseMotorAtLine(
    String rawLine,
    MotorData current, {
    int? nowMs,
    void Function(double validOdo)? onValidOdo,
    void Function(String msg)? onCorruptOdo,
  }) {
    final line = rawLine.trim();
    if (line.isEmpty || line == 'OK') return null;
    final eq = line.indexOf('=');
    if (eq <= 0) return null;

    final key = line.substring(0, eq).trim().toUpperCase();
    final valStr = line.substring(eq + 1).trim().replaceAll('"', '');
    final numVal = double.tryParse(valStr);
    final ts = nowMs ?? DateTime.now().millisecondsSinceEpoch;

    if (key.contains('SPD') && numVal != null) {
      return current.copyWith(
          speedKmh: math.max(0.0, numVal), lastUpdatedMs: ts);
    } else if (key.contains('VBAT') && numVal != null) {
      return current.copyWith(vbat: numVal, lastUpdatedMs: ts);
    } else if (key.contains('IBAT') && numVal != null) {
      return current.copyWith(ibat: numVal, lastUpdatedMs: ts);
    } else if (key.contains('TRT') && numVal != null) {
      return current.copyWith(
          throttlePct: numVal.clamp(0.0, 100.0), lastUpdatedMs: ts);
    } else if (key.contains('SOC') && numVal != null) {
      return current.copyWith(
          socPct: numVal.clamp(0.0, 100.0), lastUpdatedMs: ts);
    } else if (key.contains('SOH') && numVal != null) {
      return current.copyWith(
          sohPct: numVal.clamp(0.0, 100.0), lastUpdatedMs: ts);
    } else if (key.contains('CBAT') && numVal != null) {
      return current.copyWith(cbatCycles: numVal.round(), lastUpdatedMs: ts);
    } else if (key.contains('ODO') && numVal != null) {
      if (numVal >= 0 && numVal < 400000) {
        onValidOdo?.call(numVal);
        return current.copyWith(odoKm: numVal, lastUpdatedMs: ts);
      } else {
        onCorruptOdo?.call('ODO korup diabaikan: $numVal');
        return current;
      }
    } else if (key.contains('ERR')) {
      final cleaned = valStr.replaceFirst(RegExp(r'^0x', caseSensitive: false), '');
      final parsedMask = int.tryParse(valStr) ?? int.tryParse(cleaned, radix: 16) ?? 0;
      return current.copyWith(errMask: parsedMask, lastUpdatedMs: ts);
    } else if (key.contains('MODE') && numVal != null) {
      return current.copyWith(modeIndex: numVal.round(), lastUpdatedMs: ts);
    } else if (key.contains('PARK') && numVal != null) {
      return current.copyWith(isPark: numVal.round() == 1, lastUpdatedMs: ts);
    } else if (key.contains('TINV') && numVal != null) {
      return current.copyWith(tempInverterC: numVal, lastUpdatedMs: ts);
    } else if (key.contains('TMOT') && numVal != null) {
      return current.copyWith(tempMotorC: numVal, lastUpdatedMs: ts);
    } else if (key.contains('TBAT') && numVal != null) {
      return current.copyWith(tempBatteryC: numVal, lastUpdatedMs: ts);
    }
    return null;
  }

  static List<int> buildJkCommandFrame(int reg) {
    final cmd = List<int>.filled(20, 0);
    cmd[0] = 0xAA;
    cmd[1] = 0x55;
    cmd[2] = 0x90;
    cmd[3] = 0xEB;
    cmd[4] = reg & 0xFF;
    cmd[5] = 0x00;
    int sum = 0;
    for (int i = 0; i < 19; i++) {
      sum += cmd[i] & 0xFF;
    }
    cmd[19] = sum & 0xFF;
    return cmd;
  }

  static JkBmsData? parseJkBms300ByteFrame(
    Uint8List buf, {
    int? nowMs,
    void Function(String err)? onError,
  }) {
    if (buf.length < 300) return null;
    if (buf[0] != 0x55 || buf[1] != 0xAA || buf[2] != 0xEB || buf[3] != 0x90) {
      return null;
    }
    if (buf[4] != 0x02) return null;

    int crc = 0;
    for (int i = 0; i < 299; i++) {
      crc += buf[i] & 0xFF;
    }
    if ((crc & 0xFF) != (buf[299] & 0xFF)) {
      onError?.call('Checksum frame JK-BMS tidak cocok');
      return null;
    }

    int u16le(int off) => (buf[off] & 0xFF) | ((buf[off + 1] & 0xFF) << 8);
    int i16le(int off) {
      final v = u16le(off);
      return v >= 0x8000 ? v - 0x10000 : v;
    }

    int u32le(int off) =>
        (buf[off] & 0xFF) |
        ((buf[off + 1] & 0xFF) << 8) |
        ((buf[off + 2] & 0xFF) << 16) |
        ((buf[off + 3] & 0xFF) << 24);

    int i32le(int off) {
      final v = u32le(off);
      return v >= 0x80000000 ? v - 0x100000000 : v;
    }

    final cells = <double>[];
    for (int i = 0; i < 32; i++) {
      final mv = u16le(6 + i * 2);
      if (mv >= 1500 && mv <= 5000) {
        cells.add(mv / 1000.0);
      }
    }

    final vpack24 = u32le(118) * 0.001;
    final vpack32 = u32le(134) * 0.001;
    final cellSum = cells.fold<double>(0.0, (a, b) => a + b);
    final use32Offset = (cellSum - vpack32).abs() < (cellSum - vpack24).abs();
    final off = use32Offset ? 16 : 0;

    final vpack = u32le(118 + off) * 0.001;
    final ibat = i32le(126 + off) * 0.001;
    final temp1 = i16le(130 + off) * 0.1;
    final temp2 = i16le(132 + off) * 0.1;
    final tempMos = i16le(112 + off) * 0.1;
    final remainAh = u32le(142 + off) * 0.001;
    final capAh = u32le(146 + off) * 0.001;
    final cycles = u32le(150 + off);
    final soc = (buf[141 + off] & 0xFF).toDouble().clamp(0.0, 100.0);
    final sohRaw = buf[158 + off] & 0xFF;
    final soh = (sohRaw >= 1 && sohRaw <= 100) ? sohRaw.toDouble() : null;
    final balAction = buf[140 + off] != 0;

    double? minCell;
    double? maxCell;
    int minIdx = -1;
    int maxIdx = -1;
    for (int i = 0; i < cells.length; i++) {
      final c = cells[i];
      if (minCell == null || c < minCell) {
        minCell = c;
        minIdx = i;
      }
      if (maxCell == null || c > maxCell) {
        maxCell = c;
        maxIdx = i;
      }
    }
    final delta =
        (minCell != null && maxCell != null) ? (maxCell - minCell) : null;

    return JkBmsData(
      vpack: vpack,
      ibat: ibat,
      socPct: soc,
      sohPct: soh,
      temp1C: temp1,
      temp2C: temp2,
      tempMosC: tempMos,
      deltaCellV: delta,
      minCellV: minCell,
      maxCellV: maxCell,
      minCellIndex: minIdx,
      maxCellIndex: maxIdx,
      cellCount: cells.length,
      cellVoltages: cells,
      cycles: cycles,
      remainAh: remainAh,
      capacityAh: capAh,
      balancingActive: balAction,
      lastUpdatedMs: nowMs ?? DateTime.now().millisecondsSinceEpoch,
    );
  }

  static EffectiveTelemetry computeEffective({
    required MotorData motor,
    required ConnectionStateX motorState,
    required JkBmsData jk,
    required ConnectionStateX jkState,
    required Esp32Data esp1,
    required ConnectionStateX esp1State,
    required Battery2Data b2,
    required ConnectionStateX esp2State,
    required double? gpsSpeedKmh,
    required LiveTripMetrics trip,
    required AppSettings settings,
    int? nowMs,
  }) {
    final now = nowMs ?? DateTime.now().millisecondsSinceEpoch;
    final motorAlive = motorState == ConnectionStateX.connected &&
        (now - motor.lastUpdatedMs) < staleMs;
    final jkAlive = jkState == ConnectionStateX.connected &&
        (now - jk.lastUpdatedMs) < staleMs;
    final esp1Alive = esp1State == ConnectionStateX.connected &&
        (now - esp1.lastUpdatedMs) < staleMs;
    final esp2Alive = esp2State == ConnectionStateX.connected &&
        (now - b2.lastUpdatedMs) < staleMs;

    final double? speedKmh = (motorAlive && motor.speedKmh != null)
        ? motor.speedKmh
        : (esp1Alive && esp1.speedKmh != null)
            ? esp1.speedKmh
            : gpsSpeedKmh;

    final double? throttlePct = (motorAlive && motor.throttlePct != null)
        ? motor.throttlePct
        : (esp1Alive && esp1.throttlePct != null)
            ? esp1.throttlePct
            : null;

    final preferJk = settings.batteryPriority == BatterySourcePriority.jikong;
    late final String b1Source;
    double? b1Soc;
    double? b1Vbat;
    double? b1Ibat;
    double? b1Temp;
    double? b1Soh;

    if ((preferJk && jkAlive) || (!motorAlive && jkAlive)) {
      b1Source = 'JK-BMS';
      b1Soc = jk.socPct ?? motor.socPct;
      b1Vbat = jk.vpack ?? motor.vbat;
      b1Ibat = jk.ibat ?? motor.ibat;
      b1Temp = jk.temp1C ?? motor.tempBatteryC;
      b1Soh = jk.sohPct ?? motor.sohPct;
    } else if (motorAlive) {
      b1Source = 'Motor MID';
      b1Soc = motor.socPct;
      b1Vbat = motor.vbat;
      b1Ibat = motor.ibat;
      b1Temp = motor.tempBatteryC;
      b1Soh = motor.sohPct;
    } else {
      b1Source = '—';
    }

    final b2Active = esp2Alive && (b2.socPct != null || b2.vbat != null);
    final b2Soc = b2Active ? b2.socPct : null;
    final b2Vbat = b2Active ? b2.vbat : null;
    final b2Ibat = b2Active ? b2.ibat : null;
    final b2Temp = b2Active ? b2.tempC : null;
    final b2Soh = b2Active ? b2.sohPct : null;

    final w1 = settings.b1NominalV * settings.b1CapacityAh;
    final w2 = settings.b2NominalV * settings.b2CapacityAh;

    final double? combinedSoc = (b1Soc != null && b2Soc != null)
        ? (b1Soc * w1 + b2Soc * w2) / (w1 + w2)
        : (b1Soc ?? b2Soc);

    final double? combinedSoh = (b1Soh != null && b2Soh != null)
        ? (b1Soh * w1 + b2Soh * w2) / (w1 + w2)
        : (b1Soh ?? b2Soh);

    final double? p1 =
        (b1Vbat != null && b1Ibat != null) ? -(b1Vbat * b1Ibat) : null;
    final double? p2 = b2Active
        ? (b2.powerW ??
            ((b2Vbat != null && b2Ibat != null) ? -(b2Vbat * b2Ibat) : null))
        : null;

    final double? combinedPowerW =
        (p1 != null && p2 != null) ? (p1 + p2) : (p1 ?? p2);

    final double? combinedCurrentA = (b1Ibat != null && b2Ibat != null)
        ? (b1Ibat + b2Ibat)
        : (b1Ibat ?? b2Ibat);

    final double? avgVoltageV = (b1Vbat != null && b2Vbat != null)
        ? (b1Vbat + b2Vbat) / 2.0
        : (b1Vbat ?? b2Vbat);

    final spd = speedKmh ?? 0.0;
    final curr = combinedCurrentA ?? 0.0;
    final isRegen = curr > 0.3 && spd > 1.0;
    final isCharging = curr > 0.3 && spd <= 1.0;

    final refWhKm = settings.refWhPerKm.clamp(10.0, 90.0);
    final measuredWhKm = trip.rawWhPerKm;
    final double blendedWhKm;
    if (trip.distanceKm >= 1.5 && measuredWhKm > 1.0) {
      blendedWhKm = measuredWhKm;
    } else if (trip.distanceKm > 0.1 && measuredWhKm > 1.0) {
      final k0 = 0.3 * (1.0 - trip.distanceKm / 1.5);
      blendedWhKm = k0 * refWhKm + (1.0 - k0) * measuredWhKm;
    } else {
      blendedWhKm = refWhKm;
    }

    final double totalCapWh;
    final double maxPackWh;
    if (b1Soc != null && b2Soc != null) {
      totalCapWh = (w1 * (b1Soc / 100.0)) + (w2 * (b2Soc / 100.0));
      maxPackWh = w1 + w2;
    } else if (b1Soc != null) {
      totalCapWh = w1 * (b1Soc / 100.0);
      maxPackWh = w1;
    } else if (b2Soc != null) {
      totalCapWh = w2 * (b2Soc / 100.0);
      maxPackWh = w2;
    } else {
      totalCapWh = 0.0;
      maxPackWh = w1;
    }

    final rangeKm =
        blendedWhKm > 1.0 ? math.max(0.0, totalCapWh / blendedWhKm) : 0.0;

    double? chargeEtaHours;
    if (isCharging && combinedPowerW != null && combinedPowerW < -20.0) {
      final remainingWhToFull = math.max(0.0, maxPackWh - totalCapWh);
      chargeEtaHours = remainingWhToFull / combinedPowerW.abs();
    }

    final activeErrors =
        kMotorErrorDefs.where((d) => ((motor.errMask >> d.bit) & 1) == 1).toList();
    final odoKm = motor.odoKm ?? settings.odoBackupKm;
    final modeIdx = esp1.modeIndex ?? motor.modeIndex;
    final modeLabel = switch (modeIdx) {
      0 => 'ECO',
      1 => 'URBAN',
      2 => 'SPORT',
      _ => '—',
    };

    return EffectiveTelemetry(
      speedKmh: speedKmh,
      rpm: speedKmh != null ? (speedKmh * 51.45).round() : null,
      throttlePct: throttlePct,
      b1SocPct: b1Soc,
      b1Vbat: b1Vbat,
      b1Ibat: b1Ibat,
      b1TempC: b1Temp,
      b1SohPct: b1Soh,
      b1SourceLabel: b1Source,
      b2Active: b2Active,
      b2SocPct: b2Soc,
      b2Vbat: b2Vbat,
      b2Ibat: b2Ibat,
      b2TempC: b2Temp,
      combinedSocPct: combinedSoc,
      combinedSohPct: combinedSoh,
      combinedPowerW: combinedPowerW,
      combinedCurrentA: combinedCurrentA,
      avgVoltageV: avgVoltageV,
      isCharging: isCharging,
      isRegen: isRegen,
      rangeKm: rangeKm,
      blendedWhPerKm: blendedWhKm,
      totalAvailableWh: totalCapWh,
      chargeEtaHours: chargeEtaHours,
      odoKm: odoKm,
      modeLabel: modeLabel,
      isPark: esp1.isPark ?? motor.isPark,
      sideStandDown: esp1Alive && esp1.sideStandDown,
      cruiseActive: esp1Alive && esp1.cruiseActive,
      reverseActive: esp1Alive && esp1.reverseActive,
      activeErrors: activeErrors,
    );
  }

  static ChargingIndicatorState chargingIndicator(EffectiveTelemetry eff) {
    final soc = eff.combinedSocPct;
    if (eff.isRegen) return ChargingIndicatorState.regen;
    if (eff.isCharging) return ChargingIndicatorState.charging;
    if (soc != null && soc >= 99.5) return ChargingIndicatorState.full;
    return ChargingIndicatorState.notCharging;
  }

  static ({LiveTripMetrics trip, RidingScoreState score, bool wasMoving})
      integrateSample({
    required LiveTripMetrics prevTrip,
    required RidingScoreState prevScore,
    required EffectiveTelemetry eff,
    required int nowMs,
    required bool wasMoving,
  }) {
    final trip = prevTrip.startTimeMs == 0
        ? prevTrip.copyWith(
            startTimeMs: nowMs,
            startSocPct: eff.combinedSocPct,
            lastSampleMs: nowMs,
          )
        : prevTrip;

    final dtMs = trip.lastSampleMs > 0 ? nowMs - trip.lastSampleMs : 0;
    if (dtMs <= 0 || dtMs > integrateMaxGapMs) {
      return (
        trip: trip.copyWith(lastSampleMs: nowMs),
        score: prevScore,
        wasMoving: wasMoving,
      );
    }

    final dtSec = dtMs / 1000.0;
    final dtHr = dtMs / 3600000.0;
    final spd = eff.speedKmh ?? 0.0;
    final nowMoving = spd > 1.5;
    final distDeltaKm = nowMoving ? spd * dtHr : 0.0;

    final p1 = (eff.b1Vbat != null && eff.b1Ibat != null)
        ? eff.b1Vbat! * eff.b1Ibat!
        : 0.0;
    final p2 = (eff.b2Vbat != null && eff.b2Ibat != null)
        ? eff.b2Vbat! * eff.b2Ibat!
        : 0.0;
    final totalI = eff.combinedCurrentA ?? 0.0;
    final totalP = -(p1 + p2);

    double energyAdd = 0.0;
    double ahAdd = 0.0;
    double regenAdd = 0.0;
    double chargeAdd = 0.0;

    if (totalI < 0.0) {
      energyAdd = totalP.abs() * dtHr;
      ahAdd = totalI.abs() * dtHr;
    } else if (totalI > 0.0 && spd > 1.0) {
      regenAdd = totalP.abs() * dtHr;
    } else if (totalI > 0.0 && spd <= 1.0) {
      chargeAdd = totalP.abs() * dtHr;
    }

    final stopInc = (wasMoving && !nowMoving && trip.distanceKm > 0.05) ? 1 : 0;
    final moveIncSec = nowMoving ? math.max(1, dtSec.round()) : 0;

    final nextTrip = trip.copyWith(
      distanceKm: trip.distanceKm + distDeltaKm,
      energyWh: trip.energyWh + energyAdd,
      consumedAh: trip.consumedAh + ahAdd,
      regenWh: trip.regenWh + regenAdd,
      chargeWh: trip.chargeWh + chargeAdd,
      maxSpeedKmh: math.max(trip.maxSpeedKmh, spd),
      maxPowerW: math.max(trip.maxPowerW, totalP > 0 ? totalP : 0.0),
      maxRegenW: math.max(trip.maxRegenW, eff.isRegen ? totalP.abs() : 0.0),
      movingSeconds: trip.movingSeconds + moveIncSec,
      stopCount: trip.stopCount + stopInc,
      lastSampleMs: nowMs,
      startSocPct: trip.startSocPct ?? eff.combinedSocPct,
    );

    final nextScore =
        (eff.speedKmh != null && (nowMoving || totalP.abs() > 50.0))
            ? prevScore.copyWith(
                samples: prevScore.samples + 1,
                ecoViolations:
                    prevScore.ecoViolations + (totalP > 2500.0 ? 1 : 0),
                hardBrakes: prevScore.hardBrakes +
                    ((prevScore.lastSpeedKmh - spd) > 15.0 ? 1 : 0),
                regenEvents: prevScore.regenEvents +
                    ((totalI > 0.0 && spd > 5.0) ? 1 : 0),
                lastSpeedKmh: spd,
              )
            : prevScore.copyWith(lastSpeedKmh: spd);

    return (trip: nextTrip, score: nextScore, wasMoving: nowMoving);
  }

  static List<AppAlert> evaluateAlarms({
    required EffectiveTelemetry eff,
    required JkBmsData jk,
    required Battery2Data b2,
    required AppSettings settings,
  }) {
    if (!settings.alarmMasterOn) return const [];
    final now = DateTime.now().millisecondsSinceEpoch;
    final alerts = <AppAlert>[];

    final soc = eff.combinedSocPct;
    if (settings.alarmSocLowOn &&
        soc != null &&
        soc < settings.alarmSocThreshold) {
      alerts.add(AppAlert(
        id: 'soc_low',
        title: 'Baterai Lemah (${soc.round()}%)',
        message:
            'SOC di bawah ${settings.alarmSocThreshold}%. Segera cari SPKLU terdekat.',
        severity:
            soc <= 10 ? AlertSeverity.critical : AlertSeverity.warning,
        timestampMs: now,
      ));
    }

    if (settings.alarmChargeLimitOn &&
        eff.isCharging &&
        soc != null &&
        soc >= settings.alarmChargeLimitPct) {
      alerts.add(AppAlert(
        id: 'charge_limit',
        title: 'Batas Pengisian Tercapai (${soc.round()}%)',
        message:
            'SOC mencapai target ${settings.alarmChargeLimitPct}%. Anda dapat mencabut charger untuk umur baterai optimal.',
        severity: AlertSeverity.info,
        timestampMs: now,
      ));
    }

    final temps = [
      if (eff.b1TempC != null) eff.b1TempC!,
      if (eff.b2TempC != null) eff.b2TempC!,
      if (jk.tempMosC != null) jk.tempMosC!,
    ];
    if (temps.isNotEmpty) {
      final maxT = temps.reduce(math.max);
      if (settings.alarmTempHighOn && maxT > settings.alarmTempThresholdC) {
        alerts.add(AppAlert(
          id: 'temp_high',
          title: 'Suhu Baterai/MOS Tinggi (${maxT.round()}°C)',
          message:
              'Melebihi ambang ${settings.alarmTempThresholdC}°C. Kurangi beban akselerasi.',
          severity: AlertSeverity.warning,
          timestampMs: now,
        ));
      }
    }

    final sohs = [
      if (eff.b1SohPct != null) eff.b1SohPct!,
      if (b2.sohPct != null) b2.sohPct!,
    ];
    if (sohs.isNotEmpty) {
      final minSoh = sohs.reduce(math.min);
      if (settings.alarmSohLowOn && minSoh < 75.0) {
        alerts.add(AppAlert(
          id: 'soh_low',
          title: 'Kesehatan Baterai Menurun (${minSoh.round()}%)',
          message: 'SOH di bawah 75%. Pertimbangkan kalibrasi atau servis sel.',
          severity: AlertSeverity.info,
          timestampMs: now,
        ));
      }
    }

    final deltas = [
      if (jk.deltaCellV != null) jk.deltaCellV!,
      if (b2.deltaCellV != null) b2.deltaCellV!,
    ];
    if (deltas.isNotEmpty) {
      final maxDelta = deltas.reduce(math.max);
      if (settings.alarmDeltaCellOn && maxDelta > 0.080) {
        alerts.add(AppAlert(
          id: 'delta_cell',
          title: 'Ketidakseimbangan Sel (${maxDelta.toStringAsFixed(3)} V)',
          message:
              'Delta sel > 80 mV. Lakukan balancing pengisian hingga penuh.',
          severity: AlertSeverity.warning,
          timestampMs: now,
        ));
      }
    }

    if (eff.activeErrors.isNotEmpty) {
      alerts.add(AppAlert(
        id: 'motor_err',
        title: '${eff.activeErrors.length} Error Motor Aktif',
        message: eff.activeErrors.map((e) => e.label).join(', '),
        severity: AlertSeverity.critical,
        timestampMs: now,
      ));
    }

    return alerts;
  }

  static MaintenanceStatus? computeMaintenanceStatus(
      double? odoKm, AppSettings settings) {
    if (settings.maintLastOdoKm <= 0 && (odoKm ?? 0) <= 0) return null;
    final cur = odoKm ?? settings.odoBackupKm;
    final interval = math.max(100.0, settings.maintIntervalKm);
    final next = settings.maintLastOdoKm + interval;
    final remaining = math.max(0.0, next - cur);
    final overdue = math.max(0.0, cur - next);
    final pct = ((cur - settings.maintLastOdoKm) / interval).clamp(0.0, 1.0);
    final level = pct >= 1.0
        ? MaintHealthLevel.due
        : pct >= 0.85
            ? MaintHealthLevel.warn
            : MaintHealthLevel.ok;
    return MaintenanceStatus(
      lastServiceOdoKm: settings.maintLastOdoKm,
      intervalKm: interval,
      nextServiceOdoKm: next,
      remainingKm: remaining,
      overdueKm: overdue,
      progressPct: pct,
      level: level,
    );
  }

  static FuelSavingsSummary computeFuelSavings(
      double totalKm, double totalEnergyWh) {
    final petrolLiters = totalKm / 40.0;
    final petrolCost = (petrolLiters * 10000.0).round();
    final elecKwh = totalEnergyWh / 1000.0;
    final elecCost = (elecKwh * 1447.0).round();
    final petrolCo2Kg = petrolLiters * 2.31;
    final gridCo2Kg = elecKwh * 0.724;
    return FuelSavingsSummary(
      totalKm: totalKm,
      petrolCostRp: petrolCost,
      electricityCostRp: elecCost,
      netSavingsRp: math.max(0, petrolCost - elecCost),
      co2SavedKg: math.max(0.0, petrolCo2Kg - gridCo2Kg),
    );
  }

  static List<String> buildAtNavSlots({
    required EffectiveTelemetry eff,
    required LiveTripMetrics trip,
    required String roadName,
    required String areaName,
    required String weatherMood,
    required AtNavSlotConfig slots,
  }) {
    final out = <String>[];
    if (slots.showRange) out.add('RNG ${eff.rangeKm.toStringAsFixed(1)} km');
    if (slots.showTrip) out.add('TRP ${trip.distanceKm.toStringAsFixed(1)} km');
    if (slots.showEnergy) out.add('ENG ${trip.energyWh.round()} Wh');
    if (slots.showEfficiency) {
      out.add('EFF ${eff.blendedWhPerKm.round()}Wh/km');
    }
    if (slots.showPower && eff.combinedPowerW != null) {
      out.add('PWR ${eff.combinedPowerW!.round()} W');
    }
    if (slots.showCo2) {
      final savedKg =
          (trip.distanceKm / 40.0 * 2.31) - (trip.energyWh / 1000.0 * 0.724);
      out.add('CO2 -${math.max(0.0, savedKg).toStringAsFixed(2)}kg');
    }
    if (slots.showLocation &&
        (roadName.trim().isNotEmpty || areaName.trim().isNotEmpty)) {
      final loc = [roadName, areaName]
          .where((s) => s.trim().isNotEmpty)
          .join(', ');
      out.add(loc.length > 20 ? loc.substring(0, 20) : loc);
    }
    if (slots.showWeather && weatherMood.trim().isNotEmpty) {
      out.add(weatherMood.length > 20
          ? weatherMood.substring(0, 20)
          : weatherMood);
    }
    return out;
  }
}
