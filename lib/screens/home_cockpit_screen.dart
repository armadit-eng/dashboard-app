import 'dart:io';

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:provider/provider.dart';

import '../models/app_models.dart';
import '../models/navigation_models.dart';
import '../models/telemetry_models.dart';
import '../services/route_physics.dart';
import '../services/telemetry_engine.dart';
import '../state/dashboard_controller.dart';
import '../theme/app_theme.dart';
import '../widgets/cockpit_painters.dart';
import '../widgets/device_connect_sheet.dart';
import 'motovlog_cam_screen.dart';
import 'riding_cockpit_screen.dart';

class HomeCockpitScreen extends StatefulWidget {
  final void Function(int tabIndex) onNavigateTab;

  const HomeCockpitScreen({super.key, required this.onNavigateTab});

  @override
  State<HomeCockpitScreen> createState() => _HomeCockpitScreenState();
}

class _HomeCockpitScreenState extends State<HomeCockpitScreen> {
  final TextEditingController _atCmdCtrl = TextEditingController();
  bool _devExpanded = false;

  @override
  void dispose() {
    _atCmdCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final ctrl = context.watch<DashboardController>();
    final eff = ctrl.effective;
    final c = context.cockpit;
    final chargeState = TelemetryEngine.chargingIndicator(eff);
    final maint = TelemetryEngine.computeMaintenanceStatus(eff.odoKm, ctrl.settings);
    final score = ctrl.ridingScore.score;

    return RefreshIndicator(
      onRefresh: () => ctrl.refreshWeatherAndLocation(),
      child: ListView(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 110),
        children: [
          // 1. Asymmetric Rider Header + Device Strip
          Row(
            children: [
              _buildAvatar(ctrl.settings.avatarPath, c.accent),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      _greeting(),
                      style: TextStyle(fontSize: 12, color: c.textMuted),
                    ),
                    Text(
                      ctrl.settings.riderName,
                      style: Theme.of(context).textTheme.headlineSmall,
                    ),
                    Text(
                      '${ctrl.settings.bikeName} • ${ctrl.settings.plateNumber}',
                      style: TextStyle(fontSize: 12, color: c.textMuted),
                    ),
                  ],
                ),
              ),
              FilledButton.tonalIcon(
                onPressed: () => _openConnectSheet(context),
                icon: Icon(
                  ctrl.motorConnState == ConnectionStateX.connected
                      ? Icons.bluetooth_connected_rounded
                      : Icons.bluetooth_rounded,
                  size: 18,
                  color: ctrl.motorConnState == ConnectionStateX.connected
                      ? c.accent
                      : null,
                ),
                label: Text(
                  ctrl.motorConnState == ConnectionStateX.connected
                      ? 'Terhubung'
                      : 'Hubung',
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),

          // Connectivity Status Chips
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: Row(
              children: [
                _connBadge('MID', ctrl.motorConnState, Icons.electric_moped_rounded),
                const SizedBox(width: 8),
                _connBadge('JK-BMS', ctrl.jkConnState, Icons.battery_charging_full_rounded),
                const SizedBox(width: 8),
                _connBadge('ESP1', ctrl.esp1ConnState, Icons.memory_rounded),
                const SizedBox(width: 8),
                _connBadge('ESP2', ctrl.esp2ConnState, Icons.battery_std_rounded),
                if (ctrl.devSimActive) ...[
                  const SizedBox(width: 8),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                    decoration: BoxDecoration(
                      color: c.warning.withOpacity(0.18),
                      borderRadius: BorderRadius.circular(20),
                      border: Border.all(color: c.warning),
                    ),
                    child: Text(
                      'SIMULASI DEV',
                      style: TextStyle(
                        fontSize: 11,
                        fontWeight: FontWeight.w700,
                        color: c.warning,
                      ),
                    ),
                  ),
                ],
              ],
            ),
          ),

          if (ctrl.activeAlerts.isNotEmpty) ...[
            const SizedBox(height: 12),
            ...ctrl.activeAlerts.map((a) {
              final col = a.severity == AlertSeverity.critical
                  ? c.danger
                  : a.severity == AlertSeverity.warning
                      ? c.warning
                      : c.regen;
              return Container(
                margin: const EdgeInsets.only(bottom: 8),
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: col.withOpacity(0.12),
                  borderRadius: BorderRadius.circular(14),
                  border: Border.all(color: col.withOpacity(0.55)),
                ),
                child: Row(
                  children: [
                    Icon(Icons.warning_amber_rounded, color: col),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(a.title,
                              style: TextStyle(
                                  fontWeight: FontWeight.w700, color: col)),
                          Text(a.message, style: const TextStyle(fontSize: 12)),
                        ],
                      ),
                    ),
                  ],
                ),
              );
            }),
          ],

          const SizedBox(height: 14),

          // 2. Signature Hero Card: Dual Battery & Range Cockpit
          Container(
            padding: const EdgeInsets.all(18),
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [
                  c.surfaceCard,
                  c.accent.withOpacity(0.08),
                ],
              ),
              borderRadius: BorderRadius.circular(24),
              border: Border.all(color: c.borderSubtle),
            ),
            child: Column(
              children: [
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: [
                              Container(
                                padding: const EdgeInsets.symmetric(
                                    horizontal: 8, vertical: 3),
                                decoration: BoxDecoration(
                                  color: c.accent.withOpacity(0.14),
                                  borderRadius: BorderRadius.circular(8),
                                ),
                                child: Text(
                                  eff.modeLabel == '—'
                                      ? 'MODE STANDBY'
                                      : 'MODE ${eff.modeLabel}',
                                  style: TextStyle(
                                    fontSize: 11,
                                    fontWeight: FontWeight.w700,
                                    color: c.accent,
                                  ),
                                ),
                              ),
                              const SizedBox(width: 8),
                              Text(
                                chargeState.label,
                                style: TextStyle(
                                  fontSize: 11.5,
                                  color: eff.isRegen
                                      ? c.regen
                                      : eff.isCharging
                                          ? c.accent
                                          : c.textMuted,
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 10),
                          Row(
                            crossAxisAlignment: CrossAxisAlignment.baseline,
                            textBaseline: TextBaseline.alphabetic,
                            children: [
                              Text(
                                eff.combinedSocPct != null
                                    ? '${eff.combinedSocPct!.round()}'
                                    : '--',
                                style: Theme.of(context)
                                    .textTheme
                                    .displayLarge
                                    ?.copyWith(
                                  fontFeatures: const [
                                    FontFeature.tabularFigures()
                                  ],
                                ),
                              ),
                              const SizedBox(width: 4),
                              Text(
                                '% SOC',
                                style: TextStyle(
                                  fontSize: 14,
                                  fontWeight: FontWeight.w700,
                                  color: c.accent,
                                ),
                              ),
                            ],
                          ),
                          Text(
                            'Estimasi Jarak ${eff.rangeKm.toStringAsFixed(1)} km • ${eff.blendedWhPerKm.round()} Wh/km',
                            style: TextStyle(fontSize: 12.5, color: c.textMuted),
                          ),
                          if (eff.chargeEtaHours != null)
                            Padding(
                              padding: const EdgeInsets.only(top: 4),
                              child: Text(
                                'Penuh dalam ~${(eff.chargeEtaHours! * 60).round()} menit',
                                style: TextStyle(
                                  fontSize: 12,
                                  fontWeight: FontWeight.w600,
                                  color: c.accent,
                                ),
                              ),
                            ),
                        ],
                      ),
                    ),
                    SizedBox(
                      width: 112,
                      height: 112,
                      child: Stack(
                        alignment: Alignment.center,
                        children: [
                          CustomPaint(
                            size: const Size(112, 112),
                            painter: SpeedoGaugePainter(
                              speedKmh: eff.speedKmh ?? 0,
                              powerW: eff.combinedPowerW,
                              socPct: eff.combinedSocPct,
                              accentColor: c.accent,
                              secondaryColor: c.regen,
                              dangerColor: c.danger,
                              trackColor: c.borderSubtle,
                              isRegen: eff.isRegen,
                            ),
                          ),
                          Column(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Text(
                                eff.speedKmh != null
                                    ? eff.speedKmh!.round().toString()
                                    : '0',
                                style: Theme.of(context)
                                    .textTheme
                                    .titleMedium
                                    ?.copyWith(fontSize: 22),
                              ),
                              Text('km/h',
                                  style: TextStyle(
                                      fontSize: 10, color: c.textMuted)),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 14),
                Row(
                  children: [
                    _miniBatteryBox(
                      title: 'Baterai 1 (${eff.b1SourceLabel})',
                      soc: eff.b1SocPct,
                      volt: eff.b1Vbat,
                      amp: eff.b1Ibat,
                      temp: eff.b1TempC,
                    ),
                    const SizedBox(width: 10),
                    _miniBatteryBox(
                      title: eff.b2Active ? 'Baterai 2 (ORI)' : 'Baterai 2 (Off)',
                      soc: eff.b2SocPct,
                      volt: eff.b2Vbat,
                      amp: eff.b2Ibat,
                      temp: eff.b2TempC,
                    ),
                  ],
                ),
              ],
            ),
          ),

          const SizedBox(height: 14),

          // 3. Quick Actions Row
          GridView.count(
            crossAxisCount: 4,
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(),
            mainAxisSpacing: 8,
            crossAxisSpacing: 8,
            childAspectRatio: 1.05,
            children: [
              _quickAction(
                icon: Icons.speed_rounded,
                label: 'Mode Kokpit',
                color: c.accent,
                onTap: () => Navigator.of(context).push(
                  MaterialPageRoute(
                    builder: (_) => const RidingCockpitScreen(),
                  ),
                ),
              ),
              _quickAction(
                icon: Icons.explore_rounded,
                label: 'Navigasi',
                color: c.accentSecondary,
                onTap: () => widget.onNavigateTab(2),
              ),
              _quickAction(
                icon: Icons.videocam_rounded,
                label: 'Motovlog',
                color: c.warning,
                onTap: () => Navigator.of(context).push(
                  MaterialPageRoute(
                    builder: (_) => const MotovlogCamScreen(),
                  ),
                ),
              ),
              _quickAction(
                icon: Icons.monitor_heart_rounded,
                label: 'Diagnostik',
                color: c.regen,
                onTap: () => widget.onNavigateTab(1),
              ),
            ],
          ),

          const SizedBox(height: 14),

          // 4. Today's Live Trip & Regen Insight
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Icon(Icons.route_rounded, size: 18, color: c.accent),
                      const SizedBox(width: 8),
                      Text('Perjalanan Aktif',
                          style: Theme.of(context).textTheme.titleMedium),
                      const Spacer(),
                      if (score != null)
                        Container(
                          padding: const EdgeInsets.symmetric(
                              horizontal: 8, vertical: 3),
                          decoration: BoxDecoration(
                            color: c.accent.withOpacity(0.14),
                            borderRadius: BorderRadius.circular(10),
                          ),
                          child: Text(
                            'Skor $score • ${ctrl.ridingScore.label}',
                            style: TextStyle(
                              fontSize: 11,
                              fontWeight: FontWeight.w700,
                              color: c.accent,
                            ),
                          ),
                        ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  Row(
                    children: [
                      _statTile('Jarak',
                          '${ctrl.liveTrip.distanceKm.toStringAsFixed(2)} km'),
                      _statTile('Energi',
                          '${ctrl.liveTrip.energyWh.toStringAsFixed(1)} Wh'),
                      _statTile('Regen',
                          '+${ctrl.liveTrip.regenWh.toStringAsFixed(1)} Wh'),
                      _statTile('Maks',
                          '${ctrl.liveTrip.maxSpeedKmh.round()} km/h'),
                    ],
                  ),
                  const SizedBox(height: 12),
                  Row(
                    children: [
                      Expanded(
                        child: OutlinedButton.icon(
                          onPressed: () => ctrl.saveCurrentTripAndReset(),
                          icon: const Icon(Icons.save_rounded, size: 16),
                          label: const Text('Simpan Trip'),
                        ),
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: OutlinedButton.icon(
                          onPressed: () => ctrl.resetLiveTrip(),
                          icon: const Icon(Icons.restart_alt_rounded, size: 16),
                          label: const Text('Reset'),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),

          const SizedBox(height: 14),

          // 5. Weather + Reverse Geocode Card
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Row(
                children: [
                  Container(
                    width: 48,
                    height: 48,
                    decoration: BoxDecoration(
                      color: c.accentSecondary.withOpacity(0.14),
                      borderRadius: BorderRadius.circular(14),
                    ),
                    child: Icon(Icons.wb_sunny_rounded,
                        color: c.accentSecondary),
                  ),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          ctrl.weather != null
                              ? '${ctrl.weather!.tempC.round()}°C • ${ctrl.weather!.description} (Hujan ${ctrl.weather!.rainProbPct}%)'
                              : 'Memuat cuaca & lokasi…',
                          style: const TextStyle(fontWeight: FontWeight.w700),
                        ),
                        const SizedBox(height: 2),
                        Text(
                          [ctrl.gpsState.roadName, ctrl.gpsState.areaName]
                                  .where((s) => s.isNotEmpty)
                                  .join(', ')
                                  .isNotEmpty
                              ? [ctrl.gpsState.roadName, ctrl.gpsState.areaName]
                                  .where((s) => s.isNotEmpty)
                                  .join(', ')
                              : 'Menunggu sinyal GPS…',
                          style: TextStyle(fontSize: 12, color: c.textMuted),
                        ),
                        if (ctrl.weather != null)
                          Text(
                            'Terasa ${ctrl.weather!.feelsLikeC.round()}°C • Angin ${ctrl.weather!.windKmh.round()} km/h • UV ${ctrl.weather!.uvIndex?.toStringAsFixed(1) ?? "-"}',
                            style: TextStyle(fontSize: 11, color: c.textMuted),
                          ),
                      ],
                    ),
                  ),
                  IconButton(
                    tooltip: 'Segarkan cuaca',
                    onPressed: () => ctrl.refreshWeatherAndLocation(),
                    icon: const Icon(Icons.refresh_rounded),
                  ),
                ],
              ),
            ),
          ),

          // 6. Parked Motor Finder
          if (ctrl.parkedLocation != null) ...[
            const SizedBox(height: 14),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Row(
                  children: [
                    Container(
                      width: 44,
                      height: 44,
                      decoration: BoxDecoration(
                        color: c.accent.withOpacity(0.14),
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: Icon(Icons.local_parking_rounded, color: c.accent),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const Text('Lokasi Parkir Terakhir',
                              style: TextStyle(fontWeight: FontWeight.w700)),
                          Text(
                            ctrl.parkedLocation!.label,
                            style: TextStyle(fontSize: 12, color: c.textMuted),
                          ),
                          if (ctrl.gpsState.location != null)
                            Text(
                              'Jarak ${(RoutePhysics.haversineKm(ctrl.gpsState.location!, GeoPointX(ctrl.parkedLocation!.lat, ctrl.parkedLocation!.lng)) * 1000).round()} m dari Anda • ${DateFormat('HH:mm').format(DateTime.fromMillisecondsSinceEpoch(ctrl.parkedLocation!.timestampMs))}',
                              style:
                                  TextStyle(fontSize: 11, color: c.textMuted),
                            ),
                        ],
                      ),
                    ),
                    FilledButton.tonal(
                      onPressed: () async {
                        final p = ctrl.parkedLocation!;
                        await ctrl.setSingleDestination(
                          PlannedStop(
                            id: 'parked',
                            label: p.label,
                            lat: p.lat,
                            lng: p.lng,
                          ),
                        );
                        widget.onNavigateTab(2);
                      },
                      child: const Text('Cari'),
                    ),
                  ],
                ),
              ),
            ),
          ],

          // 7. Maintenance Reminder
          if (maint != null) ...[
            const SizedBox(height: 14),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Icon(Icons.build_circle_outlined,
                            size: 18, color: c.accent),
                        const SizedBox(width: 8),
                        Text('Pengingat Servis (ODO ${(eff.odoKm ?? 0).round()} km)',
                            style: Theme.of(context).textTheme.titleMedium),
                        const Spacer(),
                        TextButton(
                          onPressed: () => _showAddServiceDialog(
                              context, ctrl, eff.odoKm ?? 0),
                          child: const Text('Catat'),
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    LinearProgressIndicator(
                      value: maint.progressPct,
                      minHeight: 8,
                      borderRadius: BorderRadius.circular(4),
                      color: maint.level == MaintHealthLevel.due
                          ? c.danger
                          : maint.level == MaintHealthLevel.warn
                              ? c.warning
                              : c.accent,
                    ),
                    const SizedBox(height: 6),
                    Text(
                      maint.overdueKm > 0
                          ? 'Terlambat servis ${maint.overdueKm.round()} km!'
                          : 'Sisa ${maint.remainingKm.round()} km menuju servis berikutnya (${maint.nextServiceOdoKm.round()} km)',
                      style: TextStyle(fontSize: 12, color: c.textMuted),
                    ),
                  ],
                ),
              ),
            ),
          ],

          const SizedBox(height: 14),

          // 8. Developer Controls Accordion
          Card(
            child: ExpansionTile(
              initiallyExpanded: _devExpanded,
              onExpansionChanged: (v) => setState(() => _devExpanded = v),
              leading: const Icon(Icons.developer_mode_rounded),
              title: const Text('Alat Pengembang & Terminal AT'),
              subtitle: const Text('Kirim perintah AT, cek ODO, atau simulasi dev'),
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
                  child: Column(
                    children: [
                      SwitchListTile(
                        contentPadding: EdgeInsets.zero,
                        title: const Text('Mode Simulasi Dev (Tanpa Motor)'),
                        subtitle: const Text(
                            'Aktifkan sinyal uji internal hanya untuk pengujian UI'),
                        value: ctrl.devSimActive,
                        onChanged: ctrl.toggleDevTelemetrySimulation,
                      ),
                      Row(
                        children: [
                          Expanded(
                            child: TextField(
                              controller: _atCmdCtrl,
                              decoration: const InputDecoration(
                                hintText: 'Contoh: AT+SOC?',
                                isDense: true,
                                border: OutlineInputBorder(),
                              ),
                            ),
                          ),
                          const SizedBox(width: 8),
                          FilledButton(
                            onPressed: () {
                              if (_atCmdCtrl.text.trim().isNotEmpty) {
                                ctrl.sendMotorCommand(_atCmdCtrl.text.trim());
                                _atCmdCtrl.clear();
                              }
                            },
                            child: const Text('Kirim'),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  String _greeting() {
    final h = DateTime.now().hour;
    if (h < 11) return 'Selamat Pagi';
    if (h < 15) return 'Selamat Siang';
    if (h < 19) return 'Selamat Sore';
    return 'Selamat Malam';
  }

  Widget _buildAvatar(String path, Color accent) {
    if (path.isNotEmpty && File(path).existsSync()) {
      return CircleAvatar(
        radius: 24,
        backgroundImage: FileImage(File(path)),
      );
    }
    return CircleAvatar(
      radius: 24,
      backgroundColor: accent.withOpacity(0.18),
      child: Icon(Icons.person_rounded, color: accent),
    );
  }

  Widget _connBadge(String label, ConnectionStateX st, IconData icon) {
    final c = context.cockpit;
    final on = st == ConnectionStateX.connected;
    final busy = st == ConnectionStateX.connecting;
    final col = on ? c.accent : busy ? c.warning : c.textMuted;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: on ? c.accent.withOpacity(0.14) : c.surfaceCard,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: on ? c.accent : c.borderSubtle),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 14, color: col),
          const SizedBox(width: 5),
          Text(
            label,
            style: TextStyle(
              fontSize: 11.5,
              fontWeight: FontWeight.w700,
              color: col,
            ),
          ),
        ],
      ),
    );
  }

  Widget _miniBatteryBox({
    required String title,
    required double? soc,
    required double? volt,
    required double? amp,
    required double? temp,
  }) {
    final c = context.cockpit;
    return Expanded(
      child: Container(
        padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(
          color: c.surfaceElevated,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: c.borderSubtle),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(fontSize: 11, color: c.textMuted)),
            const SizedBox(height: 4),
            Text(
              '${soc != null ? "${soc.round()}%" : "--"} • ${volt != null ? "${volt.toStringAsFixed(1)}V" : "--"}',
              style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 14),
            ),
            Text(
              '${amp != null ? "${amp.toStringAsFixed(1)}A" : "--"} • ${temp != null ? "${temp.round()}°C" : "--"}',
              style: TextStyle(fontSize: 11, color: c.textMuted),
            ),
          ],
        ),
      ),
    );
  }

  Widget _quickAction({
    required IconData icon,
    required String label,
    required Color color,
    required VoidCallback onTap,
  }) {
    final c = context.cockpit;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(18),
      child: Container(
        padding: const EdgeInsets.all(8),
        decoration: BoxDecoration(
          color: c.surfaceCard,
          borderRadius: BorderRadius.circular(18),
          border: Border.all(color: c.borderSubtle),
        ),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Container(
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(
                color: color.withOpacity(0.14),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Icon(icon, size: 20, color: color),
            ),
            const SizedBox(height: 6),
            Text(
              label,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w600),
            ),
          ],
        ),
      ),
    );
  }

  Widget _statTile(String label, String value) {
    final c = context.cockpit;
    return Expanded(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: TextStyle(fontSize: 11, color: c.textMuted)),
          const SizedBox(height: 2),
          Text(
            value,
            style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13.5),
          ),
        ],
      ),
    );
  }

  void _openConnectSheet(BuildContext context) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (_) => const DeviceConnectSheet(),
    );
  }

  void _showAddServiceDialog(
      BuildContext context, DashboardController ctrl, double curOdo) {
    final typeCtrl = TextEditingController(text: 'Servis Rutin & Cek Rem');
    final odoCtrl = TextEditingController(text: curOdo.round().toString());
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Catat Servis Motor'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: odoCtrl,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: 'ODO Saat Servis (km)'),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: typeCtrl,
              decoration: const InputDecoration(labelText: 'Jenis Servis'),
            ),
          ],
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx), child: const Text('Batal')),
          FilledButton(
            onPressed: () {
              final odo = double.tryParse(odoCtrl.text) ?? curOdo;
              ctrl.addMaintenanceLog(odoKm: odo, serviceType: typeCtrl.text);
              Navigator.pop(ctx);
            },
            child: const Text('Simpan'),
          ),
        ],
      ),
    );
  }

}
