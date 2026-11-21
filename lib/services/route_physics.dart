import 'dart:math' as math;

import '../models/app_models.dart';
import '../models/navigation_models.dart';

class RoutePhysics {
  static const double gravity = 9.81;
  static const double crr = 0.013;
  static const double cda = 0.55;
  static const double rho = 1.18;
  static const double etaDrive = 0.80;
  static const double etaRegen = 0.45;
  static const double auxW = 25.0;

  static double _degToRad(double deg) => deg * (math.pi / 180.0);

  static double haversineKm(GeoPointX a, GeoPointX b) {
    const r = 6371.0;
    final dLat = _degToRad(b.lat - a.lat);
    final dLon = _degToRad(b.lng - a.lng);
    final sinLat = math.sin(dLat / 2.0);
    final sinLon = math.sin(dLon / 2.0);
    final h = sinLat * sinLat +
        math.cos(_degToRad(a.lat)) *
            math.cos(_degToRad(b.lat)) *
            sinLon *
            sinLon;
    return 2.0 * r * math.atan2(math.sqrt(h), math.sqrt(math.max(0.0, 1.0 - h)));
  }

  static GeoPointX? parseCoordinatesOrGoogleMapsUrl(String input) {
    final trimmed = input.trim();
    final direct =
        RegExp(r'(-?\d{1,2}\.\d+)\s*,\s*(-?\d{1,3}\.\d+)').firstMatch(trimmed);
    if (direct != null) {
      final lat = double.tryParse(direct.group(1)!);
      final lng = double.tryParse(direct.group(2)!);
      if (lat != null &&
          lng != null &&
          lat >= -90 &&
          lat <= 90 &&
          lng >= -180 &&
          lng <= 180) {
        return GeoPointX(lat, lng);
      }
    }
    final gmaps =
        RegExp(r'[@/](-?\d{1,2}\.\d+),(-?\d{1,3}\.\d+)').firstMatch(trimmed);
    if (gmaps != null) {
      final lat = double.tryParse(gmaps.group(1)!);
      final lng = double.tryParse(gmaps.group(2)!);
      if (lat != null &&
          lng != null &&
          lat >= -90 &&
          lat <= 90 &&
          lng >= -180 &&
          lng <= 180) {
        return GeoPointX(lat, lng);
      }
    }
    return null;
  }

  static List<GeoPointX> downsamplePoints(List<GeoPointX> points,
      {int maxSamples = 36}) {
    if (points.length <= maxSamples) return points;
    final step = (points.length - 1) / (maxSamples - 1);
    return List.generate(maxSamples, (i) {
      final idx = (i * step).floor().clamp(0, points.length - 1);
      return points[idx];
    });
  }

  static ({List<ElevationSample> samples, RouteEnergyEstimate estimate})
      buildElevationAndEnergy({
    required List<GeoPointX> sampledPoints,
    required List<double> elevations,
    required double totalDistanceKm,
    required double durationMin,
    required double bikeKg,
    required double riderKg,
    required double loadKg,
    required double calFactor,
    required double totalPackWh,
    required double? currentSocPct,
  }) {
    final n = math.min(sampledPoints.length, elevations.length);
    if (n < 2) {
      final fallbackWh = totalDistanceKm * 28.0 * calFactor;
      final socUsed =
          totalPackWh > 0 ? (fallbackWh / totalPackWh) * 100.0 : 0.0;
      return (
        samples: const <ElevationSample>[],
        estimate: RouteEnergyEstimate(
          totalWh: fallbackWh,
          regenWh: 0,
          netWh: fallbackWh,
          whPerKm: totalDistanceKm > 0.1 ? fallbackWh / totalDistanceKm : 28.0,
          socUsedPct: socUsed,
          socAtArrivalPct: currentSocPct != null
              ? (currentSocPct - socUsed).clamp(0.0, 100.0)
              : null,
          maxClimbPct: 0,
          totalAscentM: 0,
          totalDescentM: 0,
        ),
      );
    }

    final totalMassKg = bikeKg + riderKg + loadKg;
    final avgSpeedMs = durationMin > 0.1
        ? ((totalDistanceKm * 1000.0) / (durationMin * 60.0)).clamp(5.0, 22.0)
        : 10.0;

    final samples = <ElevationSample>[];
    double cumDistKm = 0.0;
    double driveWh = 0.0;
    double regenWh = 0.0;
    double ascent = 0.0;
    double descent = 0.0;
    double maxClimb = 0.0;

    samples.add(ElevationSample(
      distanceKm: 0.0,
      elevationM: elevations[0],
      gradePct: 0.0,
      gradeType: HillGrade.flat,
      point: sampledPoints[0],
    ));

    for (int i = 1; i < n; i++) {
      final segKm =
          math.max(0.02, haversineKm(sampledPoints[i - 1], sampledPoints[i]));
      cumDistKm += segKm;
      final segM = segKm * 1000.0;
      final dh = elevations[i] - elevations[i - 1];
      if (dh > 0) {
        ascent += dh;
      } else {
        descent += -dh;
      }

      final gradePct = ((dh / segM) * 100.0).clamp(-35.0, 35.0);
      if (gradePct > maxClimb) maxClimb = gradePct;

      final theta = math.atan2(dh, segM);
      final force = totalMassKg * gravity * math.sin(theta) +
          crr * totalMassKg * gravity * math.cos(theta) +
          0.5 * rho * cda * math.pow(avgSpeedMs, 2.0);

      final mechEnergyJ = force * segM;
      final segTimeHr = (segM / avgSpeedMs) / 3600.0;
      final auxSegWh = auxW * segTimeHr;

      if (mechEnergyJ >= 0) {
        driveWh += (mechEnergyJ / 3600.0) / etaDrive + auxSegWh;
      } else {
        regenWh += ((-mechEnergyJ) / 3600.0) * etaRegen;
        driveWh += auxSegWh;
      }

      final gradeType = gradePct >= 6.0
          ? HillGrade.uphillSteep
          : gradePct >= 2.5
              ? HillGrade.uphillMild
              : gradePct <= -2.5
                  ? HillGrade.downhill
                  : HillGrade.flat;

      samples.add(ElevationSample(
        distanceKm: cumDistKm,
        elevationM: elevations[i],
        gradePct: gradePct,
        gradeType: gradeType,
        point: sampledPoints[i],
      ));
    }

    final calDriveWh = driveWh * calFactor;
    final netWh = math.max(totalDistanceKm * 10.0, calDriveWh - regenWh);
    final socUsed = totalPackWh > 0.0 ? (netWh / totalPackWh) * 100.0 : 0.0;

    return (
      samples: samples,
      estimate: RouteEnergyEstimate(
        totalWh: calDriveWh,
        regenWh: regenWh,
        netWh: netWh,
        whPerKm: totalDistanceKm > 0.1 ? netWh / totalDistanceKm : 28.0,
        socUsedPct: socUsed,
        socAtArrivalPct: currentSocPct != null
            ? (currentSocPct - socUsed).clamp(0.0, 100.0)
            : null,
        maxClimbPct: maxClimb,
        totalAscentM: ascent,
        totalDescentM: descent,
      ),
    );
  }

  static String translateOsrmManeuver(
      String? type, String? modifier, String? roadName) {
    final road =
        (roadName != null && roadName.trim().isNotEmpty) ? ' ke $roadName' : '';
    switch (type) {
      case 'depart':
        return 'Mulai perjalanan$road';
      case 'arrive':
        return 'Tiba di tujuan';
      case 'roundabout':
      case 'rotary':
        return 'Masuk bundaran$road';
      case 'merge':
        return 'Gabung jalur$road';
      case 'fork':
        if (modifier == 'left' || modifier == 'slight left') {
          return 'Ambil cabang kiri$road';
        }
        if (modifier == 'right' || modifier == 'slight right') {
          return 'Ambil cabang kanan$road';
        }
        return 'Tetap di jalur$road';
      default:
        switch (modifier) {
          case 'left':
            return 'Belok kiri$road';
          case 'sharp left':
            return 'Belok kiri tajam$road';
          case 'slight left':
            return 'Serong kiri$road';
          case 'right':
            return 'Belok kanan$road';
          case 'sharp right':
            return 'Belok kanan tajam$road';
          case 'slight right':
            return 'Serong kanan$road';
          case 'uturn':
            return 'Putar balik$road';
          default:
            return 'Lurus terus$road';
        }
    }
  }

  static ({String desc, String moodTag}) decodeWeatherCode(int? code) {
    return switch (code) {
      0 => (desc: 'Cerah', moodTag: 'CERAH'),
      1 || 2 => (desc: 'Cerah Berawan', moodTag: 'BERAWAN'),
      3 => (desc: 'Mendung', moodTag: 'MENDUNG'),
      45 || 48 => (desc: 'Berkabut', moodTag: 'KABUT'),
      51 || 53 || 55 => (desc: 'Gerimis', moodTag: 'GERIMIS'),
      61 || 63 || 65 || 80 || 81 || 82 => (desc: 'Hujan', moodTag: 'HUJAN'),
      95 || 96 || 99 => (desc: 'Badai Petir', moodTag: 'BADAI'),
      _ => (desc: 'Berawan', moodTag: 'BERAWAN'),
    };
  }

  static String exportTripsCsv(List<TripRecord> trips) {
    final buf = StringBuffer(
        'id,start_ms,end_ms,distance_km,energy_wh,regen_wh,wh_per_km,max_speed_kmh,avg_speed_kmh,max_power_w,score,start_area,end_area\n');
    for (final t in trips) {
      buf.writeln(
          '${t.id},${t.startTimeMs},${t.endTimeMs},${t.distanceKm.toStringAsFixed(2)},${t.energyWh.toStringAsFixed(1)},${t.regenWh.toStringAsFixed(1)},${t.whPerKm.toStringAsFixed(1)},${t.maxSpeedKmh.toStringAsFixed(1)},${t.avgSpeedKmh.toStringAsFixed(1)},${t.maxPowerW.toStringAsFixed(0)},${t.ridingScore ?? ""},'
          '"${t.startArea.replaceAll('"', "'")}","${t.endArea.replaceAll('"', "'")}"');
    }
    return buf.toString();
  }

  static String exportTripGpx(TripRecord trip) {
    final buf = StringBuffer();
    buf.writeln('<?xml version="1.0" encoding="UTF-8"?>');
    buf.writeln('<gpx version="1.1" creator="Dashboard App EV">');
    buf.writeln('<trk><name>Trip ${trip.id}</name><trkseg>');
    for (final p in trip.pathPoints) {
      buf.writeln(
          '  <trkpt lat="${p.lat.toStringAsFixed(6)}" lon="${p.lng.toStringAsFixed(6)}">'
          '${p.altM != null ? '<ele>${p.altM!.toStringAsFixed(1)}</ele>' : ''}</trkpt>');
    }
    buf.writeln('</trkseg></trk></gpx>');
    return buf.toString();
  }

  static String exportFieldNotesGpx(List<FieldNotePin> notes) {
    final buf = StringBuffer();
    buf.writeln('<?xml version="1.0" encoding="UTF-8"?>');
    buf.writeln('<gpx version="1.1" creator="Dashboard App EV Field Notes">');
    for (final n in notes) {
      buf.writeln(
          '<wpt lat="${n.lat.toStringAsFixed(6)}" lon="${n.lng.toStringAsFixed(6)}">'
          '<name>${_xmlEscape(n.name)}</name>'
          '<type>${_xmlEscape(n.category)}</type>'
          '<desc>${_xmlEscape(n.note)}</desc></wpt>');
    }
    buf.writeln('</gpx>');
    return buf.toString();
  }

  static String _xmlEscape(String s) => s
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;');
}
