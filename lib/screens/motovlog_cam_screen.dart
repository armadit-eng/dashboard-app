import 'package:camera/camera.dart';
import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:provider/provider.dart';

import '../state/dashboard_controller.dart';
import '../theme/app_theme.dart';

class MotovlogCamScreen extends StatefulWidget {
  const MotovlogCamScreen({super.key});

  @override
  State<MotovlogCamScreen> createState() => _MotovlogCamScreenState();
}

class _MotovlogCamScreenState extends State<MotovlogCamScreen> {
  CameraController? _camCtrl;
  bool _recording = false;
  String? _errorMsg;

  @override
  void initState() {
    super.initState();
    _initCamera();
  }

  Future<void> _initCamera() async {
    try {
      final cams = await availableCameras();
      if (cams.isEmpty) {
        setState(() => _errorMsg = 'Kamera tidak tersedia di perangkat ini.');
        return;
      }
      final back = cams.firstWhere(
        (c) => c.lensDirection == CameraLensDirection.back,
        orElse: () => cams.first,
      );
      final ctrl = CameraController(back, ResolutionPreset.high, enableAudio: true);
      await ctrl.initialize();
      if (mounted) setState(() => _camCtrl = ctrl);
    } catch (e) {
      if (mounted) setState(() => _errorMsg = 'Izin atau akses kamera ditolak: $e');
    }
  }

  @override
  void dispose() {
    _camCtrl?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final dash = context.watch<DashboardController>();
    final eff = dash.effective;
    final c = context.cockpit;

    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        fit: StackFit.expand,
        children: [
          if (_camCtrl != null && _camCtrl!.value.isInitialized)
            CameraPreview(_camCtrl!)
          else
            Center(
              child: Text(
                _errorMsg ?? 'Membuka kamera Motovlog HUD…',
                style: const TextStyle(color: Colors.white70),
              ),
            ),

          // Live HUD Telemetry Overlay
          SafeArea(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Row(
                    children: [
                      IconButton.filled(
                        onPressed: () => Navigator.pop(context),
                        icon: const Icon(Icons.arrow_back_rounded),
                      ),
                      const SizedBox(width: 10),
                      Container(
                        padding: const EdgeInsets.symmetric(
                            horizontal: 12, vertical: 6),
                        decoration: BoxDecoration(
                          color: Colors.black54,
                          borderRadius: BorderRadius.circular(12),
                          border: Border.all(color: Colors.white24),
                        ),
                        child: Text(
                          '${DateFormat('HH:mm:ss').format(DateTime.now())} • ${dash.gpsState.areaName.isNotEmpty ? dash.gpsState.areaName : "GPS Aktif"}',
                          style: const TextStyle(
                              color: Colors.white,
                              fontWeight: FontWeight.w600,
                              fontSize: 12),
                        ),
                      ),
                    ],
                  ),
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.end,
                    children: [
                      Container(
                        padding: const EdgeInsets.symmetric(
                            horizontal: 16, vertical: 12),
                        decoration: BoxDecoration(
                          color: Colors.black.withOpacity(0.65),
                          borderRadius: BorderRadius.circular(18),
                          border: Border.all(color: c.accent.withOpacity(0.7)),
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Text(
                                  '${(eff.speedKmh ?? 0).round()}',
                                  style: TextStyle(
                                    color: c.accent,
                                    fontSize: 38,
                                    fontWeight: FontWeight.w800,
                                    height: 1,
                                  ),
                                ),
                                const Text('KM/H',
                                    style: TextStyle(
                                        color: Colors.white70, fontSize: 10)),
                              ],
                            ),
                            const SizedBox(width: 16),
                            Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Text(
                                  'SOC ${eff.combinedSocPct?.round() ?? "--"}%',
                                  style: const TextStyle(
                                      color: Colors.white,
                                      fontWeight: FontWeight.w700),
                                ),
                                Text(
                                  '${eff.combinedPowerW?.round() ?? 0} W • ${dash.liveTrip.distanceKm.toStringAsFixed(1)} km',
                                  style: const TextStyle(
                                      color: Colors.white70, fontSize: 12),
                                ),
                              ],
                            ),
                          ],
                        ),
                      ),
                      const Spacer(),
                      FloatingActionButton(
                        backgroundColor: _recording ? c.danger : c.accent,
                        foregroundColor: Colors.black,
                        onPressed: () async {
                          final cam = _camCtrl;
                          if (cam == null || !cam.value.isInitialized) return;
                          if (_recording) {
                            final file = await cam.stopVideoRecording();
                            if (mounted) {
                              setState(() => _recording = false);
                              ScaffoldMessenger.of(context).showSnackBar(
                                SnackBar(
                                    content:
                                        Text('Video tersimpan: ${file.name}')),
                              );
                            }
                          } else {
                            await cam.startVideoRecording();
                            if (mounted) setState(() => _recording = true);
                          }
                        },
                        child: Icon(_recording
                            ? Icons.stop_rounded
                            : Icons.fiber_manual_record_rounded),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
