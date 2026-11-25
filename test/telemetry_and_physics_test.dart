import 'dart:typed_data';

import 'package:dashboard_app/models/app_models.dart';
import 'package:dashboard_app/models/navigation_models.dart';
import 'package:dashboard_app/models/telemetry_models.dart';
import 'package:dashboard_app/services/route_physics.dart';
import 'package:dashboard_app/services/telemetry_engine.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('TelemetryEngine', () {
    test('parses Motor AT lines and ignores corrupt ODO >= 400000', () {
      var m = const MotorData();
      m = TelemetryEngine.parseMotorAtLine('+SPD=48.5', m)!;
      m = TelemetryEngine.parseMotorAtLine('+VBAT=73.4', m)!;
      m = TelemetryEngine.parseMotorAtLine('+IBAT=-12.0', m)!;
      m = TelemetryEngine.parseMotorAtLine('+ERR=0x09', m)!;

      expect(m.speedKmh, 48.5);
      expect(m.vbat, 73.4);
      expect(m.ibat, -12.0);
      expect(m.errMask, 9);

      double? backedUpOdo;
      m = TelemetryEngine.parseMotorAtLine(
        '+ODO=1250',
        m,
        onValidOdo: (v) => backedUpOdo = v,
      )!;
      expect(backedUpOdo, 1250);

      String? corruptMsg;
      m = TelemetryEngine.parseMotorAtLine(
        '+ODO=410000',
        m,
        onCorruptOdo: (msg) => corruptMsg = msg,
      )!;
      expect(m.odoKm, 1250);
      expect(corruptMsg, isNotNull);
    });

    test('builds and parses JK-BMS 300-byte binary frame', () {
      final cmd = TelemetryEngine.buildJkCommandFrame(0x96);
      expect(cmd.length, 20);
      expect(cmd.sublist(0, 4), [0xAA, 0x55, 0x90, 0xEB]);

      final frame = Uint8List(300);
      frame[0] = 0x55;
      frame[1] = 0xAA;
      frame[2] = 0xEB;
      frame[3] = 0x90;
      frame[4] = 0x02;

      // 20 cells @ 3800 mV
      for (int i = 0; i < 20; i++) {
        frame[6 + i * 2] = 3800 & 0xFF;
        frame[6 + i * 2 + 1] = (3800 >> 8) & 0xFF;
      }
      // 24S vpack = 76000 mV @ offset 118
      const vpackMv = 76000;
      frame[118] = vpackMv & 0xFF;
      frame[119] = (vpackMv >> 8) & 0xFF;
      frame[120] = (vpackMv >> 16) & 0xFF;
      frame[121] = (vpackMv >> 24) & 0xFF;

      // SOC = 82% @ 141
      frame[141] = 82;

      // CRC byte 299
      int crc = 0;
      for (int i = 0; i < 299; i++) {
        crc += frame[i];
      }
      frame[299] = crc & 0xFF;

      final parsed = TelemetryEngine.parseJkBms300ByteFrame(frame);
      expect(parsed, isNotNull);
      expect(parsed!.cellCount, 20);
      expect(parsed.vpack, closeTo(76.0, 0.01));
      expect(parsed.socPct, 82.0);
    });

    test('merges dual battery weighted SOC and integrates energy', () {
      const now = 1_700_000_000_000;
      const settings = AppSettings(
        b1NominalV: 72,
        b1CapacityAh: 20,
        b2NominalV: 72,
        b2CapacityAh: 20,
      );
      final eff = TelemetryEngine.computeEffective(
        motor: const MotorData(
          speedKmh: 36,
          vbat: 72,
          ibat: -10,
          socPct: 80,
          lastUpdatedMs: now,
        ),
        motorState: ConnectionStateX.connected,
        jk: const JkBmsData(),
        jkState: ConnectionStateX.disconnected,
        esp1: const Esp32Data(),
        esp1State: ConnectionStateX.disconnected,
        b2: const Battery2Data(
          vbat: 72,
          ibat: -10,
          socPct: 60,
          lastUpdatedMs: now,
        ),
        esp2State: ConnectionStateX.connected,
        gpsSpeedKmh: null,
        trip: const LiveTripMetrics(),
        settings: settings,
        nowMs: now,
      );

      expect(eff.combinedSocPct, closeTo(70.0, 0.1));
      expect(eff.combinedPowerW, closeTo(1440.0, 0.1));

      final res = TelemetryEngine.integrateSample(
        prevTrip: const LiveTripMetrics(
          startTimeMs: now - 1000,
          lastSampleMs: now - 1000,
        ),
        prevScore: const RidingScoreState(),
        eff: eff,
        nowMs: now,
        wasMoving: false,
      );
      expect(res.trip.distanceKm, closeTo(0.01, 0.001));
      expect(res.trip.energyWh, closeTo(0.4, 0.02));
      expect(res.wasMoving, true);
    });
  });

  group('RoutePhysics', () {
    test('parses Google Maps coordinates and computes elevation energy', () {
      final gp = RoutePhysics.parseCoordinatesOrGoogleMapsUrl(
          'https://maps.google.com/?q=@-6.917464,107.619125,15z');
      expect(gp, isNotNull);
      expect(gp!.lat, closeTo(-6.917464, 0.0001));
      expect(gp.lng, closeTo(107.619125, 0.0001));

      final res = RoutePhysics.buildElevationAndEnergy(
        sampledPoints: const [
          GeoPointX(-6.917, 107.619),
          GeoPointX(-6.907, 107.619),
          GeoPointX(-6.897, 107.619),
        ],
        elevations: const [700, 760, 720],
        totalDistanceKm: 2.2,
        durationMin: 6.0,
        bikeKg: 95,
        riderKg: 70,
        loadKg: 5,
        calFactor: 1.0,
        totalPackWh: 1440,
        currentSocPct: 80,
      );
      expect(res.samples.length, 3);
      expect(res.estimate.totalAscentM, closeTo(60, 0.1));
      expect(res.estimate.totalDescentM, closeTo(40, 0.1));
      expect(res.estimate.socAtArrivalPct, lessThan(80));
    });
  });
}
