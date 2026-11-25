import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';
import 'package:provider/provider.dart';

import '../services/platform_tools_channel.dart';
import '../state/dashboard_controller.dart';
import '../theme/app_theme.dart';

class MotovlogCamScreen extends StatefulWidget {
  const MotovlogCamScreen({super.key});

  @override
  State<MotovlogCamScreen> createState() => _MotovlogCamScreenState();
}

class _MotovlogCamScreenState extends State<MotovlogCamScreen> {
  static const _camChannel = MethodChannel('com.dashboard.app/camera_controls');

  bool _camReady = false;
  bool _recording = false;
  String? _errorMsg;

  @override
  void initState() {
    super.initState();
    _initCamera();
  }

  Future<void> _initCamera() async {
    try {
      await PlatformToolsChannel.requestPermissions();
      if (mounted) setState(() => _camReady = true);
    } catch (e) {
      if (mounted) {
        setState(() => _errorMsg = 'Izin atau akses kamera ditolak: $e');
      }
    }
  }

  @override
  void dispose() {
    if (_recording) {
      _camChannel.invokeMethod('stopRecording').catchError((_) {});
    }
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
          if (_camReady)
            const AndroidView(viewType: 'com.dashboard.app/camera_preview')
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
                          if (!_camReady) return;
                          if (_recording) {
                            final path = await _camChannel
                                .invokeMethod<String?>('stopRecording');
                            if (mounted) {
                              setState(() => _recording = false);
                              final fileName = path?.split('/').last ?? 'motovlog.mp4';
                              ScaffoldMessenger.of(context).showSnackBar(
                                SnackBar(
                                  content: Text('Video tersimpan: $fileName'),
                                ),
                              );
                            }
                          } else {
                            final started = await _camChannel
                                    .invokeMethod<bool>('startRecording') ??
                                false;
                            if (mounted) {
                              setState(() => _recording = started);
                              if (!started) {
                                ScaffoldMessenger.of(context).showSnackBar(
                                  const SnackBar(
                                    content: Text(
                                        'Gagal memulai rekam video. Cek izin kamera & mikrofon.'),
                                  ),
                                );
                              }
                            }
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
