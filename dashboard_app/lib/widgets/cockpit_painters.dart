import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../models/navigation_models.dart';

class SpeedoGaugePainter extends CustomPainter {
  final double speedKmh;
  final double maxSpeedKmh;
  final double? powerW;
  final double? socPct;
  final Color accentColor;
  final Color secondaryColor;
  final Color dangerColor;
  final Color trackColor;
  final bool isRegen;

  SpeedoGaugePainter({
    required this.speedKmh,
    this.maxSpeedKmh = 100.0,
    this.powerW,
    this.socPct,
    required this.accentColor,
    required this.secondaryColor,
    required this.dangerColor,
    required this.trackColor,
    this.isRegen = false,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final radius = math.min(size.width, size.height) * 0.42;
    const startAngle = 135.0 * math.pi / 180.0;
    const sweepTotal = 270.0 * math.pi / 180.0;

    // Background arc
    canvas.drawArc(
      Rect.fromCircle(center: center, radius: radius),
      startAngle,
      sweepTotal,
      false,
      Paint()
        ..color = trackColor
        ..style = PaintingStyle.stroke
        ..strokeWidth = 14
        ..strokeCap = StrokeCap.round,
    );

    // Tick marks
    for (int i = 0; i <= 20; i++) {
      final frac = i / 20.0;
      final ang = startAngle + frac * sweepTotal;
      final isMajor = i % 4 == 0;
      final rIn = radius - (isMajor ? 22 : 16);
      final rOut = radius - 10;
      canvas.drawLine(
        Offset(center.dx + rIn * math.cos(ang), center.dy + rIn * math.sin(ang)),
        Offset(center.dx + rOut * math.cos(ang), center.dy + rOut * math.sin(ang)),
        Paint()
          ..color = isMajor ? accentColor.withOpacity(0.55) : trackColor
          ..strokeWidth = isMajor ? 2.2 : 1.2,
      );
    }

    // Active speed arc
    final spdFrac = (speedKmh / maxSpeedKmh).clamp(0.0, 1.0);
    if (spdFrac > 0.005) {
      final arcColor = spdFrac > 0.82 ? dangerColor : accentColor;
      canvas.drawArc(
        Rect.fromCircle(center: center, radius: radius),
        startAngle,
        sweepTotal * spdFrac,
        false,
        Paint()
          ..color = arcColor
          ..style = PaintingStyle.stroke
          ..strokeWidth = 14
          ..strokeCap = StrokeCap.round,
      );
    }

    // Inner Power / Regen ring
    final innerR = radius - 28;
    canvas.drawArc(
      Rect.fromCircle(center: center, radius: innerR),
      startAngle,
      sweepTotal,
      false,
      Paint()
        ..color = trackColor.withOpacity(0.6)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 5
        ..strokeCap = StrokeCap.round,
    );

    if (powerW != null) {
      final pFrac = (powerW!.abs() / 4500.0).clamp(0.0, 1.0);
      canvas.drawArc(
        Rect.fromCircle(center: center, radius: innerR),
        startAngle,
        sweepTotal * pFrac,
        false,
        Paint()
          ..color = isRegen ? secondaryColor : accentColor.withOpacity(0.85)
          ..style = PaintingStyle.stroke
          ..strokeWidth = 5
          ..strokeCap = StrokeCap.round,
      );
    }
  }

  @override
  bool shouldRepaint(covariant SpeedoGaugePainter oldDelegate) =>
      oldDelegate.speedKmh != speedKmh ||
      oldDelegate.powerW != powerW ||
      oldDelegate.socPct != socPct ||
      oldDelegate.isRegen != isRegen ||
      oldDelegate.accentColor != accentColor;
}

class EnergyFlowPainter extends CustomPainter {
  final double phase; // 0..1 animated
  final double powerW;
  final bool isRegen;
  final bool isCharging;
  final Color dischargeColor;
  final Color regenColor;
  final Color idleColor;

  EnergyFlowPainter({
    required this.phase,
    required this.powerW,
    required this.isRegen,
    required this.isCharging,
    required this.dischargeColor,
    required this.regenColor,
    required this.idleColor,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final cy = size.height / 2;
    final x1 = 22.0;
    final xMid = size.width / 2;
    final x2 = size.width - 22.0;

    final active = powerW.abs() > 15.0;
    final lineCol = !active
        ? idleColor
        : (isRegen || isCharging)
            ? regenColor
            : dischargeColor;

    final trackPaint = Paint()
      ..color = idleColor.withOpacity(0.45)
      ..strokeWidth = 3
      ..style = PaintingStyle.stroke;

    canvas.drawLine(Offset(x1, cy), Offset(x2, cy), trackPaint);

    // Draw moving pulses
    if (active) {
      final dir = (isRegen || isCharging) ? -1.0 : 1.0;
      const dots = 6;
      for (int i = 0; i < dots; i++) {
        final rawT = ((i / dots) + (phase * dir)) % 1.0;
        final t = rawT < 0 ? rawT + 1.0 : rawT;
        final px = x1 + t * (x2 - x1);
        canvas.drawCircle(
          Offset(px, cy),
          4.5,
          Paint()..color = lineCol,
        );
      }
    }

    void drawNode(double x, Color border) {
      canvas.drawCircle(Offset(x, cy), 12, Paint()..color = border.withOpacity(0.2));
      canvas.drawCircle(
        Offset(x, cy),
        12,
        Paint()
          ..color = border
          ..style = PaintingStyle.stroke
          ..strokeWidth = 2.2,
      );
      canvas.drawCircle(Offset(x, cy), 4.5, Paint()..color = border);
    }

    drawNode(x1, lineCol);
    drawNode(xMid, active ? lineCol : idleColor);
    drawNode(x2, isCharging ? idleColor : lineCol);
  }

  @override
  bool shouldRepaint(covariant EnergyFlowPainter oldDelegate) =>
      oldDelegate.phase != phase ||
      oldDelegate.powerW != powerW ||
      oldDelegate.isRegen != isRegen ||
      oldDelegate.isCharging != isCharging;
}

class ElevationProfilePainter extends CustomPainter {
  final List<ElevationSample> samples;
  final Color trackColor;
  final Color labelColor;

  ElevationProfilePainter({
    required this.samples,
    required this.trackColor,
    required this.labelColor,
  });

  @override
  void paint(Canvas canvas, Size size) {
    if (samples.length < 2) return;
    final minE = samples.map((s) => s.elevationM).reduce(math.min);
    final maxRaw = samples.map((s) => s.elevationM).reduce(math.max);
    final maxE = (maxRaw - minE) < 8 ? minE + 8 : maxRaw;

    const padL = 8.0;
    const padR = 8.0;
    const padT = 14.0;
    const padB = 18.0;
    final w = size.width - padL - padR;
    final h = size.height - padT - padB;

    // Grid horizontal lines
    for (int g = 0; g <= 2; g++) {
      final y = padT + (h * g / 2);
      canvas.drawLine(
        Offset(padL, y),
        Offset(padL + w, y),
        Paint()
          ..color = trackColor
          ..strokeWidth = 1,
      );
    }

    Offset pt(int i) {
      final x = padL + (i / (samples.length - 1)) * w;
      final normY = (samples[i].elevationM - minE) / (maxE - minE);
      final y = padT + h - (normY * h);
      return Offset(x, y);
    }

    final fillPath = Path()..moveTo(padL, padT + h);
    for (int i = 0; i < samples.length; i++) {
      final p = pt(i);
      fillPath.lineTo(p.dx, p.dy);
    }
    fillPath.lineTo(padL + w, padT + h);
    fillPath.close();
    canvas.drawPath(
      fillPath,
      Paint()..color = const Color(0xFF00E5A3).withOpacity(0.12),
    );

    for (int i = 1; i < samples.length; i++) {
      final col = switch (samples[i].gradeType) {
        HillGrade.uphillSteep => const Color(0xFFEF4444),
        HillGrade.uphillMild => const Color(0xFFF59E0B),
        HillGrade.downhill => const Color(0xFF38BDF8),
        HillGrade.flat => const Color(0xFF00E5A3),
      };
      canvas.drawLine(
        pt(i - 1),
        pt(i),
        Paint()
          ..color = col
          ..strokeWidth = 3.2
          ..strokeCap = StrokeCap.round,
      );
    }

    final tpMin = TextPainter(
      text: TextSpan(
        text: '${minE.round()}m',
        style: TextStyle(color: labelColor, fontSize: 10),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    tpMin.paint(canvas, Offset(padL, padT + h + 2));

    final tpMax = TextPainter(
      text: TextSpan(
        text: 'Puncak ${maxRaw.round()}m',
        style: TextStyle(color: labelColor, fontSize: 10),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    tpMax.paint(canvas, Offset(padL + w - tpMax.width, 0));
  }

  @override
  bool shouldRepaint(covariant ElevationProfilePainter oldDelegate) =>
      oldDelegate.samples != samples;
}

class ChartSeries {
  final String name;
  final List<double> values;
  final Color color;
  final double? fixedMin;
  final double? fixedMax;

  const ChartSeries({
    required this.name,
    required this.values,
    required this.color,
    this.fixedMin,
    this.fixedMax,
  });
}

class MultiSeriesChartPainter extends CustomPainter {
  final List<ChartSeries> series;
  final Color gridColor;
  final bool fillFirstSeries;

  MultiSeriesChartPainter({
    required this.series,
    required this.gridColor,
    this.fillFirstSeries = true,
  });

  @override
  void paint(Canvas canvas, Size size) {
    for (int g = 0; g <= 3; g++) {
      final y = (size.height - 8) * g / 3 + 4;
      canvas.drawLine(
        Offset(0, y),
        Offset(size.width, y),
        Paint()
          ..color = gridColor
          ..strokeWidth = 1,
      );
    }

    for (int sIdx = 0; sIdx < series.length; sIdx++) {
      final s = series[sIdx];
      if (s.values.length < 2) continue;
      final minV = s.fixedMin ?? s.values.reduce(math.min);
      final maxRaw = s.fixedMax ?? s.values.reduce(math.max);
      final span = (maxRaw - minV).abs() < 0.001 ? 1.0 : (maxRaw - minV);

      final path = Path();
      final fill = Path();
      for (int i = 0; i < s.values.length; i++) {
        final x = (i / (s.values.length - 1)) * size.width;
        final norm = ((s.values[i] - minV) / span).clamp(0.0, 1.0);
        final y = size.height - 4 - norm * (size.height - 8);
        if (i == 0) {
          path.moveTo(x, y);
          fill.moveTo(x, size.height - 4);
          fill.lineTo(x, y);
        } else {
          path.lineTo(x, y);
          fill.lineTo(x, y);
        }
      }
      if (fillFirstSeries && sIdx == 0) {
        fill.lineTo(size.width, size.height - 4);
        fill.close();
        canvas.drawPath(
          fill,
          Paint()..color = s.color.withOpacity(0.14),
        );
      }
      canvas.drawPath(
        path,
        Paint()
          ..color = s.color
          ..style = PaintingStyle.stroke
          ..strokeWidth = 2.4
          ..strokeCap = StrokeCap.round
          ..strokeJoin = StrokeJoin.round,
      );
    }
  }

  @override
  bool shouldRepaint(covariant MultiSeriesChartPainter oldDelegate) =>
      oldDelegate.series != series;
}
