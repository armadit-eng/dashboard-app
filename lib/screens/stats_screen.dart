import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:provider/provider.dart';

import '../models/app_models.dart';
import '../services/platform_tools_channel.dart';
import '../services/route_physics.dart';
import '../services/telemetry_engine.dart';
import '../state/dashboard_controller.dart';
import '../theme/app_theme.dart';
import '../widgets/cockpit_painters.dart';

class StatsScreen extends StatelessWidget {
  const StatsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final ctrl = context.watch<DashboardController>();
    final c = context.cockpit;
    final trips = ctrl.tripHistory;
    final totalKm = trips.fold<double>(0.0, (a, b) => a + b.distanceKm) +
        ctrl.liveTrip.distanceKm;
    final totalWh = trips.fold<double>(0.0, (a, b) => a + b.energyWh) +
        ctrl.liveTrip.energyWh;
    final totalRegen = trips.fold<double>(0.0, (a, b) => a + b.regenWh) +
        ctrl.liveTrip.regenWh;
    final maxSpd = trips
        .map((t) => t.maxSpeedKmh)
        .fold<double>(ctrl.liveTrip.maxSpeedKmh, math.max);
    final savings = TelemetryEngine.computeFuelSavings(totalKm, totalWh);
    final badges = ctrl.computeBadges();

    // Rolling 7-day km
    final now = DateTime.now();
    final weekAgoMs =
        now.subtract(const Duration(days: 7)).millisecondsSinceEpoch;
    final weeklyKm = trips
            .where((t) => t.startTimeMs >= weekAgoMs)
            .fold<double>(0.0, (a, b) => a + b.distanceKm) +
        ctrl.liveTrip.distanceKm;
    final goalPct =
        (weeklyKm / math.max(1.0, ctrl.settings.weeklyGoalKm)).clamp(0.0, 1.0);

    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 110),
      children: [
        Row(
          children: [
            Icon(Icons.analytics_rounded, color: c.accent),
            const SizedBox(width: 8),
            Expanded(
              child: Text('Statistik & Riwayat Trip',
                  style: Theme.of(context).textTheme.headlineSmall),
            ),
            IconButton(
              tooltip: 'Ekspor Semua CSV',
              onPressed: trips.isEmpty
                  ? null
                  : () {
                      final csv = RoutePhysics.exportTripsCsv(trips);
                      PlatformToolsChannel.shareText(
                        csv,
                        subject: 'Riwayat_Trip_EV.csv',
                      );
                    },
              icon: const Icon(Icons.ios_share_rounded),
            ),
          ],
        ),
        const SizedBox(height: 12),

        // 1. Lifetime KPI Grid + Weekly Goal
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    _kpi('Total Jarak', '${totalKm.toStringAsFixed(1)} km',
                        c.accent),
                    _kpi('Hemat BBM',
                        'Rp ${NumberFormat('#,###', 'id_ID').format(savings.netSavingsRp)}',
                        c.accentSecondary),
                  ],
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    _kpi('CO₂ Dihemat',
                        '${savings.co2SavedKg.toStringAsFixed(1)} kg', c.regen),
                    _kpi('Puncak / Regen',
                        '${maxSpd.round()} km/h • +${totalRegen.round()} Wh',
                        c.warning),
                  ],
                ),
                const SizedBox(height: 14),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      'Target Mingguan (${weeklyKm.toStringAsFixed(1)} / ${ctrl.settings.weeklyGoalKm.round()} km)',
                      style: const TextStyle(
                          fontWeight: FontWeight.w600, fontSize: 12.5),
                    ),
                    Text('${(goalPct * 100).round()}%',
                        style: TextStyle(
                            fontWeight: FontWeight.w800, color: c.accent)),
                  ],
                ),
                const SizedBox(height: 6),
                LinearProgressIndicator(
                  value: goalPct,
                  minHeight: 8,
                  borderRadius: BorderRadius.circular(4),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 14),

        // 2. Gamification Badges
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Pencapaian Pengendara',
                    style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 10),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: badges.map((b) {
                    return Container(
                      width: (MediaQuery.of(context).size.width - 72) / 2,
                      padding: const EdgeInsets.all(10),
                      decoration: BoxDecoration(
                        color: b.unlocked
                            ? c.accent.withOpacity(0.14)
                            : c.surfaceElevated,
                        borderRadius: BorderRadius.circular(14),
                        border: Border.all(
                            color: b.unlocked ? c.accent : c.borderSubtle),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: [
                              Icon(
                                b.unlocked
                                    ? Icons.emoji_events_rounded
                                    : Icons.lock_clock_rounded,
                                size: 16,
                                color: b.unlocked ? c.accent : c.textMuted,
                              ),
                              const SizedBox(width: 6),
                              Expanded(
                                child: Text(
                                  b.title,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: const TextStyle(
                                      fontWeight: FontWeight.w700,
                                      fontSize: 12),
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 4),
                          Text(
                            b.description,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(fontSize: 10.5, color: c.textMuted),
                          ),
                        ],
                      ),
                    );
                  }).toList(),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 14),

        // 3. Prediction vs Actual Calibration Log
        if (ctrl.calibLogs.isNotEmpty) ...[
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Kalibrasi Rute vs Aktual',
                      style: Theme.of(context).textTheme.titleMedium),
                  const SizedBox(height: 8),
                  ...ctrl.calibLogs.take(4).map((cal) => ListTile(
                        dense: true,
                        contentPadding: EdgeInsets.zero,
                        title: Text(
                            '${cal.distanceKm.toStringAsFixed(1)} km • Prediksi ${cal.predictedWh.round()} Wh vs Aktual ${cal.actualWh.round()} Wh'),
                        subtitle: Text(
                            'Selisih ${cal.errorPct.toStringAsFixed(1)}% • Saran faktor ${cal.suggestedFactor}'),
                        trailing: TextButton(
                          onPressed: () => ctrl.updateSettings(ctrl.settings
                              .copyWith(routeCalFactor: cal.suggestedFactor)),
                          child: const Text('Terapkan'),
                        ),
                      )),
                ],
              ),
            ),
          ),
          const SizedBox(height: 14),
        ],

        // 4. Trip History List
        Text('Riwayat Perjalanan (${trips.length})',
            style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        if (trips.isEmpty)
          Card(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Center(
                child: Text(
                  'Belum ada riwayat perjalanan tersimpan.\nMulai berkendara atau tekan Simpan Trip di Beranda.',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: c.textMuted),
                ),
              ),
            ),
          )
        else
          ...trips.map((t) {
            final dt = DateTime.fromMillisecondsSinceEpoch(t.startTimeMs);
            return Container(
              margin: const EdgeInsets.only(bottom: 10),
              child: Card(
                child: ListTile(
                  onTap: () => _showTripDetailSheet(context, ctrl, t),
                  leading: CircleAvatar(
                    backgroundColor: c.accent.withOpacity(0.14),
                    child: Icon(Icons.route_rounded, color: c.accent),
                  ),
                  title: Text(
                    '${t.distanceKm.toStringAsFixed(2)} km • ${t.whPerKm.round()} Wh/km',
                    style: const TextStyle(fontWeight: FontWeight.w700),
                  ),
                  subtitle: Text(
                    '${DateFormat('dd MMM HH:mm').format(dt)} • '
                    '${t.startArea.isNotEmpty ? t.startArea : "Perjalanan"} '
                    '${t.endArea.isNotEmpty ? "→ ${t.endArea}" : ""}',
                  ),
                  trailing: IconButton(
                    icon: const Icon(Icons.delete_outline_rounded, size: 19),
                    onPressed: () => ctrl.deleteTrip(t.id),
                  ),
                ),
              ),
            );
          }),
      ],
    );
  }

  Widget _kpi(String label, String value, Color col) {
    return Expanded(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: const TextStyle(fontSize: 11.5)),
          const SizedBox(height: 2),
          Text(
            value,
            style: TextStyle(
                fontSize: 17, fontWeight: FontWeight.w800, color: col),
          ),
        ],
      ),
    );
  }

  void _showTripDetailSheet(
      BuildContext context, DashboardController ctrl, TripRecord t) {
    final c = context.cockpit;
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      builder: (ctx) => Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Laporan & Replay Perjalanan',
                style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 10),
            Row(
              children: [
                _kpi('Jarak', '${t.distanceKm.toStringAsFixed(2)} km', c.accent),
                _kpi('Energi', '${t.energyWh.toStringAsFixed(1)} Wh', c.warning),
                _kpi('Skor', '${t.ridingScore ?? "--"}', c.regen),
              ],
            ),
            const SizedBox(height: 12),
            if (t.samples.length >= 2)
              SizedBox(
                height: 120,
                width: double.infinity,
                child: CustomPaint(
                  painter: MultiSeriesChartPainter(
                    gridColor: c.borderSubtle,
                    series: [
                      ChartSeries(
                        name: 'Speed',
                        values: t.samples.map((e) => e.speedKmh).toList(),
                        color: c.accent,
                      ),
                      ChartSeries(
                        name: 'SOC',
                        values: t.samples.map((e) => e.socPct).toList(),
                        color: c.regen,
                      ),
                    ],
                  ),
                ),
              ),
            const SizedBox(height: 14),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: () {
                      final gpx = RoutePhysics.exportTripGpx(t);
                      PlatformToolsChannel.shareText(
                        gpx,
                        subject: 'Trip_${t.id}.gpx',
                      );
                    },
                    icon: const Icon(Icons.share_rounded, size: 17),
                    label: const Text('Ekspor GPX'),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: FilledButton(
                    onPressed: () => Navigator.pop(ctx),
                    child: const Text('Tutup'),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
