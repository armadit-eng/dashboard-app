import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import '../services/platform_tools_channel.dart';
import '../state/dashboard_controller.dart';
import '../theme/app_theme.dart';
import '../widgets/cockpit_painters.dart';

class RidingCockpitScreen extends StatefulWidget {
  const RidingCockpitScreen({super.key});

  @override
  State<RidingCockpitScreen> createState() => _RidingCockpitScreenState();
}

class _RidingCockpitScreenState extends State<RidingCockpitScreen>
    with SingleTickerProviderStateMixin {
  late final AnimationController _flowCtrl;

  @override
  void initState() {
    super.initState();
    _flowCtrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1400),
    )..repeat();
    unawaited(PlatformToolsChannel.setWakelock(true));
    unawaited(SystemChrome.setPreferredOrientations([
      DeviceOrientation.landscapeLeft,
      DeviceOrientation.landscapeRight,
    ]));
    unawaited(SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky));
  }

  @override
  void dispose() {
    _flowCtrl.dispose();
    unawaited(PlatformToolsChannel.setWakelock(false));
    unawaited(SystemChrome.setPreferredOrientations([
      DeviceOrientation.portraitUp,
      DeviceOrientation.portraitDown,
      DeviceOrientation.landscapeLeft,
      DeviceOrientation.landscapeRight,
    ]));
    unawaited(SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge));
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final ctrl = context.watch<DashboardController>();
    final eff = ctrl.effective;
    final c = context.cockpit;
    final isCompact = ctrl.settings.cockpitLayout == 'compact';

    return Scaffold(
      backgroundColor: const Color(0xFF070A0F),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 10),
          child: Column(
            children: [
              // Top bar: Mode, Compass, Nav Turn Banner, Layout switch, Exit
              Row(
                children: [
                  _pill(eff.modeLabel, c.accent),
                  const SizedBox(width: 8),
                  if (eff.cruiseActive) _pill('CRUISE', c.regen),
                  if (eff.reverseActive) _pill('MUNDUR', c.warning),
                  if (eff.sideStandDown) _pill('STANDAR', c.danger),
                  if (eff.isPark == true) _pill('PARK', c.warning),
                  const SizedBox(width: 12),
                  Expanded(
                    child: ctrl.isNavigating && ctrl.activeRoute != null
                        ? Container(
                            padding: const EdgeInsets.symmetric(
                                horizontal: 14, vertical: 6),
                            decoration: BoxDecoration(
                              color: c.accent.withOpacity(0.14),
                              borderRadius: BorderRadius.circular(12),
                              border:
                                  Border.all(color: c.accent.withOpacity(0.5)),
                            ),
                            child: Row(
                              children: [
                                Icon(Icons.turn_right_rounded,
                                    color: c.accent, size: 18),
                                const SizedBox(width: 8),
                                Expanded(
                                  child: Text(
                                    ctrl.activeStepIndex <
                                            ctrl.activeRoute!.steps.length
                                        ? '${ctrl.distToNextStepM.round()}m • ${ctrl.activeRoute!.steps[ctrl.activeStepIndex].instruction}'
                                        : 'Ikuti rute',
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: TextStyle(
                                      color: c.accent,
                                      fontWeight: FontWeight.w700,
                                    ),
                                  ),
                                ),
                              ],
                            ),
                          )
                        : Text(
                            _compassHeading(ctrl.gpsState.headingDeg),
                            style: TextStyle(
                              color: c.textMuted,
                              fontWeight: FontWeight.w600,
                              fontSize: 13,
                            ),
                          ),
                  ),
                  const SizedBox(width: 10),
                  SegmentedButton<String>(
                    segments: const [
                      ButtonSegment(value: 'compact', label: Text('Ringkas')),
                      ButtonSegment(value: 'complete', label: Text('Lengkap')),
                    ],
                    selected: {ctrl.settings.cockpitLayout},
                    onSelectionChanged: (sel) => ctrl.updateSettings(
                        ctrl.settings.copyWith(cockpitLayout: sel.first)),
                  ),
                  const SizedBox(width: 8),
                  IconButton(
                    onPressed: () => Navigator.of(context).pop(),
                    icon: const Icon(Icons.close_fullscreen_rounded,
                        color: Colors.white),
                  ),
                ],
              ),
              const SizedBox(height: 10),
              // Main Landscape Cockpit Stage
              Expanded(
                child: Row(
                  children: [
                    // Left: Central Speedo Gauge
                    Expanded(
                      flex: 5,
                      child: Center(
                        child: AspectRatio(
                          aspectRatio: 1,
                          child: Stack(
                            alignment: Alignment.center,
                            children: [
                              RepaintBoundary(child: CustomPaint(
                                size: const Size.square(260),
                                painter: SpeedoGaugePainter(
                                  speedKmh: eff.speedKmh ?? 0,
                                  powerW: eff.combinedPowerW,
                                  socPct: eff.combinedSocPct,
                                  accentColor: c.accent,
                                  secondaryColor: c.regen,
                                  dangerColor: c.danger,
                                  trackColor: const Color(0xFF1F2A37),
                                  isRegen: eff.isRegen,
                                ),
                              )),
                              Column(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  Text(
                                    '${(eff.speedKmh ?? 0).round()}',
                                    style: const TextStyle(
                                      fontSize: 64,
                                      fontWeight: FontWeight.w800,
                                      color: Colors.white,
                                      height: 1.0,
                                      fontFeatures: [
                                        FontFeature.tabularFigures()
                                      ],
                                    ),
                                  ),
                                  const Text('KM/H',
                                      style: TextStyle(
                                          color: Colors.white54,
                                          letterSpacing: 2,
                                          fontSize: 12)),
                                  const SizedBox(height: 6),
                                  Text(
                                    '${eff.rpm ?? 0} RPM',
                                    style: TextStyle(
                                      color: c.accent,
                                      fontWeight: FontWeight.w700,
                                      fontSize: 13,
                                    ),
                                  ),
                                ],
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(width: 14),
                    // Right: Energy Flow + Dual Battery + Trip Metrics
                    Expanded(
                      flex: 6,
                      child: Column(
                        children: [
                          // Energy Flow Card
                          Container(
                            padding: const EdgeInsets.symmetric(
                                horizontal: 16, vertical: 12),
                            decoration: BoxDecoration(
                              color: const Color(0xFF101722),
                              borderRadius: BorderRadius.circular(18),
                              border: Border.all(
                                  color: const Color(0xFF233042)),
                            ),
                            child: Column(
                              children: [
                                Row(
                                  mainAxisAlignment:
                                      MainAxisAlignment.spaceBetween,
                                  children: [
                                    const Text('BATERAI',
                                        style: TextStyle(
                                            fontSize: 10,
                                            color: Colors.white54)),
                                    Text(
                                      eff.combinedPowerW != null
                                          ? '${eff.combinedPowerW!.round()} W'
                                          : '0 W',
                                      style: TextStyle(
                                        fontSize: 16,
                                        fontWeight: FontWeight.w800,
                                        color: eff.isRegen
                                            ? c.regen
                                            : c.accent,
                                      ),
                                    ),
                                    const Text('MOTOR',
                                        style: TextStyle(
                                            fontSize: 10,
                                            color: Colors.white54)),
                                  ],
                                ),
                                const SizedBox(height: 6),
                                SizedBox(
                                  height: 32,
                                  width: double.infinity,
                                  child: AnimatedBuilder(
                                    animation: _flowCtrl,
                                    builder: (_, __) => RepaintBoundary(child: CustomPaint(
                                      painter: EnergyFlowPainter(
                                        phase: _flowCtrl.value,
                                        powerW: eff.combinedPowerW ?? 0,
                                        isRegen: eff.isRegen,
                                        isCharging: eff.isCharging,
                                        dischargeColor: c.accent,
                                        regenColor: c.regen,
                                        idleColor: const Color(0xFF334155),
                                      ),
                                    )),
                                  ),
                                ),
                              ],
                            ),
                          ),
                          const SizedBox(height: 10),
                          Expanded(
                            child: Row(
                              children: [
                                _metricBlock(
                                  'SOC TOTAL',
                                  eff.combinedSocPct != null
                                      ? '${eff.combinedSocPct!.round()}%'
                                      : '--',
                                  'Sisa ${eff.rangeKm.toStringAsFixed(1)} km',
                                  c.accent,
                                ),
                                const SizedBox(width: 10),
                                _metricBlock(
                                  'ARUS / TEGANGAN',
                                  eff.avgVoltageV != null
                                      ? '${eff.avgVoltageV!.toStringAsFixed(1)} V'
                                      : '--',
                                  eff.combinedCurrentA != null
                                      ? '${eff.combinedCurrentA!.toStringAsFixed(1)} A'
                                      : '--',
                                  c.accentSecondary,
                                ),
                                if (!isCompact) ...[
                                  const SizedBox(width: 10),
                                  _metricBlock(
                                    'TRIP & EFISIENSI',
                                    '${ctrl.liveTrip.distanceKm.toStringAsFixed(2)} km',
                                    '${eff.blendedWhPerKm.round()} Wh/km',
                                    c.warning,
                                  ),
                                ],
                              ],
                            ),
                          ),
                          if (!isCompact) ...[
                            const SizedBox(height: 10),
                            Row(
                              mainAxisAlignment: MainAxisAlignment.spaceBetween,
                              children: [
                                _tempTag('Motor', eff.b1TempC ?? ctrl.motorData.tempMotorC),
                                _tempTag('Inverter', ctrl.motorData.tempInverterC),
                                _tempTag('B1', eff.b1TempC),
                                _tempTag('B2', eff.b2TempC),
                                Text(
                                  'ODO ${(eff.odoKm ?? 0).round()} km',
                                  style: const TextStyle(
                                      color: Colors.white60, fontSize: 12),
                                ),
                              ],
                            ),
                          ],
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _pill(String txt, Color col) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: col.withOpacity(0.16),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: col.withOpacity(0.6)),
      ),
      child: Text(
        txt,
        style:
            TextStyle(color: col, fontWeight: FontWeight.w800, fontSize: 11.5),
      ),
    );
  }

  Widget _metricBlock(String title, String val, String sub, Color col) {
    return Expanded(
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: const Color(0xFF101722),
          borderRadius: BorderRadius.circular(18),
          border: Border.all(color: const Color(0xFF233042)),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(title,
                style: const TextStyle(fontSize: 10.5, color: Colors.white54)),
            const SizedBox(height: 4),
            Text(
              val,
              style: TextStyle(
                  fontSize: 26, fontWeight: FontWeight.w800, color: col),
            ),
            Text(sub, style: const TextStyle(fontSize: 12, color: Colors.white70)),
          ],
        ),
      ),
    );
  }

  Widget _tempTag(String label, double? val) {
    return Text(
      '$label: ${val != null ? "${val.round()}°C" : "--"}',
      style: const TextStyle(color: Colors.white70, fontSize: 12),
    );
  }

  String _compassHeading(double? deg) {
    if (deg == null) return 'KOMPAS MENUNGGU GPS';
    const dirs = ['U', 'TL', 'T', 'TG', 'S', 'BD', 'B', 'BL'];
    final norm = (deg % 360 + 360) % 360;
    final label = dirs[(norm / 45.0).round() % 8];
    return '$label ${norm.round()}°';
  }
}
