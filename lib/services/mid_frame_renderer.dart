import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:image/image.dart' as img;

import '../models/app_models.dart';
import '../models/navigation_models.dart';
import '../models/telemetry_models.dart';

class MidFrameRenderer {
  static Future<String> renderFrameBase64({
    required AppSettings settings,
    required GpsState gps,
    required EffectiveTelemetry eff,
    required NavRouteX? activeRoute,
    required bool isNavigating,
    required int activeStepIndex,
    required double distToNextStepM,
    required double etaMin,
    int maxChars = 11500,
  }) async {
    final w = settings.mapsSnapWidth.clamp(160, 480);
    final h = settings.mapsSnapHeight.clamp(120, 360);

    if (settings.customWallpaperEnabled &&
        settings.customWallpaperPath.isNotEmpty) {
      final file = File(settings.customWallpaperPath);
      if (await file.exists()) {
        final raw = await file.readAsBytes();
        final decoded = img.decodeImage(raw);
        if (decoded != null) {
          final resized = img.copyResize(decoded, width: w, height: h);
          return _encodeWithinBudget(resized, maxChars);
        }
      }
    }

    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder, Rect.fromLTWH(0, 0, w.toDouble(), h.toDouble()));
    canvas.drawRect(
      Rect.fromLTWH(0, 0, w.toDouble(), h.toDouble()),
      Paint()..color = const Color(0xFF0A131C),
    );

    final isSplit = settings.mapsLayout == 'split';
    final mapW = isSplit ? (w * 0.54).roundToDouble() : w.toDouble();

    _drawMiniMapArea(
      canvas: canvas,
      mapW: mapW,
      h: h.toDouble(),
      gps: gps,
      route: activeRoute,
      isNavigating: isNavigating,
      riderLow: settings.mapsRiderPos == 'low',
    );

    if (isSplit) {
      _drawSplitTelemetryPanel(
        canvas: canvas,
        x0: mapW,
        w: w.toDouble(),
        h: h.toDouble(),
        gps: gps,
        eff: eff,
        route: activeRoute,
        isNavigating: isNavigating,
        activeStepIndex: activeStepIndex,
        distToNextStepM: distToNextStepM,
        etaMin: etaMin,
      );
    } else if (isNavigating && activeRoute != null) {
      final step = activeStepIndex < activeRoute.steps.length
          ? activeRoute.steps[activeStepIndex]
          : null;
      canvas.drawRRect(
        RRect.fromRectAndRadius(
            Rect.fromLTWH(6, 6, w - 12.0, 30), const Radius.circular(6)),
        Paint()..color = const Color(0xDD0B1620),
      );
      final distTxt = distToNextStepM >= 1000
          ? '${(distToNextStepM / 1000).toStringAsFixed(1)}km'
          : '${distToNextStepM.round()}m';
      _drawText(
        canvas,
        '$distTxt ${step?.instruction ?? 'Ikuti rute'}',
        const Offset(12, 12),
        12,
        const Color(0xFF00E5A3),
        bold: true,
        maxWidth: w - 24.0,
      );
    }

    final pic = recorder.endRecording();
    final uiImage = await pic.toImage(w, h);
    final byteData = await uiImage.toByteData(format: ui.ImageByteFormat.rawRgba);
    uiImage.dispose();
    if (byteData == null) return '';

    final image = img.Image.fromBytes(
      width: w,
      height: h,
      bytes: byteData.buffer,
      numChannels: 4,
      order: img.ChannelOrder.rgba,
    );
    return _encodeWithinBudget(image, maxChars);
  }

  static String _encodeWithinBudget(img.Image image, int maxChars) {
    const qualities = [72, 58, 46, 36, 28, 20];
    String last = '';
    for (final q in qualities) {
      final jpg = img.encodeJpg(image, quality: q);
      final b64 = base64Encode(jpg);
      last = b64;
      if (b64.length <= maxChars) return b64;
    }
    return last;
  }

  static void _drawMiniMapArea({
    required Canvas canvas,
    required double mapW,
    required double h,
    required GpsState gps,
    required NavRouteX? route,
    required bool isNavigating,
    required bool riderLow,
  }) {
    canvas.save();
    canvas.clipRect(Rect.fromLTWH(0, 0, mapW, h));

    // Tactical vector grid background for crisp readability on small MID LCD
    final gridP = Paint()
      ..color = const Color(0xFF152433)
      ..strokeWidth = 1;
    for (double x = 0; x < mapW; x += 24) {
      canvas.drawLine(Offset(x, 0), Offset(x, h), gridP);
    }
    for (double y = 0; y < h; y += 24) {
      canvas.drawLine(Offset(0, y), Offset(mapW, y), gridP);
    }

    final origin = gps.location ??
        (route != null && route.geometry.isNotEmpty
            ? route.geometry.first
            : const GeoPointX(-6.1754, 106.8272));

    final riderX = mapW * 0.5;
    final riderY = (isNavigating && riderLow) ? h * 0.70 : h * 0.50;
    const zoom = 16;
    final scale = 256.0 * (1 << zoom);

    Offset toScreen(GeoPointX gp) {
      double worldX(double lng) => (lng + 180.0) / 360.0 * scale;
      double worldY(double lat) {
        final rad = lat.clamp(-85.0511, 85.0511) * math.pi / 180.0;
        final sinLat = math.sin(rad);
        return (0.5 - math.log((1 + sinLat) / (1 - sinLat)) / (4 * math.pi)) *
            scale;
      }

      final ox = worldX(origin.lng);
      final oy = worldY(origin.lat);
      return Offset(
        riderX + (worldX(gp.lng) - ox),
        riderY + (worldY(gp.lat) - oy),
      );
    }

    if (route != null && route.geometry.length >= 2) {
      final path = Path();
      for (int i = 0; i < route.geometry.length; i++) {
        final pt = toScreen(route.geometry[i]);
        if (i == 0) {
          path.moveTo(pt.dx, pt.dy);
        } else {
          path.lineTo(pt.dx, pt.dy);
        }
      }
      canvas.drawPath(
        path,
        Paint()
          ..color = const Color(0xFF041418)
          ..style = PaintingStyle.stroke
          ..strokeWidth = 8
          ..strokeCap = StrokeCap.round,
      );
      canvas.drawPath(
        path,
        Paint()
          ..color = const Color(0xFF00E5A3)
          ..style = PaintingStyle.stroke
          ..strokeWidth = 4.5
          ..strokeCap = StrokeCap.round,
      );
    }

    // Rider arrow
    canvas.save();
    canvas.translate(riderX, riderY);
    canvas.rotate((gps.headingDeg ?? 0.0) * math.pi / 180.0);
    final arrow = Path()
      ..moveTo(0, -10)
      ..lineTo(7, 8)
      ..lineTo(0, 4)
      ..lineTo(-7, 8)
      ..close();
    canvas.drawPath(arrow, Paint()..color = const Color(0xFF00E5A3));
    canvas.drawPath(
      arrow,
      Paint()
        ..color = Colors.white
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.6,
    );
    canvas.restore();
    canvas.restore();
  }

  static void _drawSplitTelemetryPanel({
    required Canvas canvas,
    required double x0,
    required double w,
    required double h,
    required GpsState gps,
    required EffectiveTelemetry eff,
    required NavRouteX? route,
    required bool isNavigating,
    required int activeStepIndex,
    required double distToNextStepM,
    required double etaMin,
  }) {
    canvas.drawRect(
      Rect.fromLTWH(x0, 0, w - x0, h),
      Paint()..color = const Color(0xFF0B141E),
    );
    canvas.drawRect(
      Rect.fromLTWH(x0, 0, 1.5, h),
      Paint()..color = const Color(0xFF334B61),
    );
    final left = x0 + 8;
    final panelW = w - left - 6;

    if (isNavigating && route != null) {
      final step = activeStepIndex < route.steps.length
          ? route.steps[activeStepIndex]
          : null;
      final distTxt = distToNextStepM >= 1000
          ? '${(distToNextStepM / 1000).toStringAsFixed(1)} km'
          : '${distToNextStepM.round()} m';
      _drawText(canvas, distTxt, Offset(left, 10), 17, Colors.white,
          bold: true, maxWidth: panelW);
      _drawText(
        canvas,
        step?.instruction ?? 'Ikuti rute',
        Offset(left, 34),
        11,
        const Color(0xFF00E5A3),
        bold: true,
        maxWidth: panelW,
      );
      _drawText(
        canvas,
        'ETA ${etaMin.round()}m  •  TIBA ${route.energyEstimate?.socAtArrivalPct?.round() ?? "--"}%',
        Offset(left, 56),
        10,
        const Color(0xFF9FB4C8),
        maxWidth: panelW,
      );

      final samples = route.elevationSamples;
      if (samples.length >= 3) {
        final top = h - 54;
        canvas.drawRRect(
          RRect.fromRectAndRadius(
              Rect.fromLTWH(left, top, panelW, 46), const Radius.circular(5)),
          Paint()..color = const Color(0xFF132230),
        );
        final minE = samples.map((s) => s.elevationM).reduce(math.min);
        final maxERaw = samples.map((s) => s.elevationM).reduce(math.max);
        final maxE = (maxERaw - minE) < 5 ? minE + 5 : maxERaw;

        for (int i = 1; i < samples.length; i++) {
          final xA = left + 4 + ((i - 1) / (samples.length - 1)) * (panelW - 8);
          final xB = left + 4 + (i / (samples.length - 1)) * (panelW - 8);
          final yA = (h - 12) -
              ((samples[i - 1].elevationM - minE) / (maxE - minE)) * 32;
          final yB =
              (h - 12) - ((samples[i].elevationM - minE) / (maxE - minE)) * 32;
          final col = switch (samples[i].gradeType) {
            HillGrade.uphillSteep => const Color(0xFFEF4444),
            HillGrade.uphillMild => const Color(0xFFF59E0B),
            HillGrade.downhill => const Color(0xFF38BDF8),
            HillGrade.flat => const Color(0xFF00E5A3),
          };
          canvas.drawLine(
            Offset(xA, yA),
            Offset(xB, yB),
            Paint()
              ..color = col
              ..strokeWidth = 2.2,
          );
        }
      }
    } else {
      const dirs = [
        'UTARA',
        'TIMUR LAUT',
        'TIMUR',
        'TENGGARA',
        'SELATAN',
        'BARAT DAYA',
        'BARAT',
        'BARAT LAUT'
      ];
      final hdg = ((gps.headingDeg ?? 0.0) % 360.0 + 360.0) % 360.0;
      final card = dirs[(hdg / 45.0).round() % 8];
      _drawText(canvas, card, Offset(left, 12), 16, Colors.white,
          bold: true, maxWidth: panelW);
      _drawText(canvas, '${hdg.round()}°', Offset(left, 34), 12,
          const Color(0xFF9FB4C8),
          maxWidth: panelW);
      _drawText(
        canvas,
        '${eff.blendedWhPerKm.round()} Wh/km',
        Offset(left, 64),
        13,
        const Color(0xFF00E5A3),
        bold: true,
        maxWidth: panelW,
      );
      if (gps.roadName.isNotEmpty) {
        _drawText(canvas, gps.roadName, Offset(left, 88), 11, Colors.white70,
            maxWidth: panelW);
      }
      if (gps.location?.altM != null) {
        _drawText(
          canvas,
          'ELEV ${gps.location!.altM!.round()} m',
          Offset(left, 114),
          12,
          const Color(0xFF38BDF8),
          bold: true,
          maxWidth: panelW,
        );
      }
    }
  }

  static void _drawText(
    Canvas canvas,
    String text,
    Offset offset,
    double size,
    Color color, {
    bool bold = false,
    double maxWidth = 180,
  }) {
    final tp = TextPainter(
      text: TextSpan(
        text: text,
        style: TextStyle(
          color: color,
          fontSize: size,
          fontWeight: bold ? FontWeight.w700 : FontWeight.w500,
        ),
      ),
      maxLines: 2,
      ellipsis: '…',
      textDirection: TextDirection.ltr,
    )..layout(maxWidth: maxWidth);
    tp.paint(canvas, offset);
  }
}
