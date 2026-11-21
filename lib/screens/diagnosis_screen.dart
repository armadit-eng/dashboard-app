import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/telemetry_models.dart';
import '../state/dashboard_controller.dart';
import '../theme/app_theme.dart';
import '../widgets/cockpit_painters.dart';

class DiagnosisScreen extends StatefulWidget {
  const DiagnosisScreen({super.key});

  @override
  State<DiagnosisScreen> createState() => _DiagnosisScreenState();
}

class _DiagnosisScreenState extends State<DiagnosisScreen>
    with SingleTickerProviderStateMixin {
  late final TabController _tabCtrl;
  int _batteryTab = 0; // 0 = Baterai 1 (JK/MID), 1 = Baterai 2 (ESP32 #2)
  bool _sortCellsLowToHigh = false;

  @override
  void initState() {
    super.initState();
    _tabCtrl = TabController(length: 3, vsync: this);
  }

  @override
  void dispose() {
    _tabCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final c = context.cockpit;
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 10, 16, 6),
          child: Row(
            children: [
              Icon(Icons.monitor_heart_rounded, color: c.accent),
              const SizedBox(width: 8),
              Text('Diagnostik EV & BMS',
                  style: Theme.of(context).textTheme.headlineSmall),
            ],
          ),
        ),
        TabBar(
          controller: _tabCtrl,
          tabs: const [
            Tab(text: 'Motor & DTC'),
            Tab(text: 'Dual Baterai'),
            Tab(text: 'Grafik Live'),
          ],
        ),
        Expanded(
          child: TabBarView(
            controller: _tabCtrl,
            children: [
              _buildMotorTab(context),
              _buildBatteryTab(context),
              _buildTrendTab(context),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildMotorTab(BuildContext context) {
    final ctrl = context.watch<DashboardController>();
    final eff = ctrl.effective;
    final motor = ctrl.motorData;
    final c = context.cockpit;

    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 14, 16, 110),
      children: [
        // Live Throttle + RPM + Temps
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Status Penggerak & Sensor',
                    style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 12),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text('Throttle Sensor (${(eff.throttlePct ?? 0).round()}%)'),
                    Text('${eff.rpm ?? 0} RPM • Mode ${eff.modeLabel}',
                        style: TextStyle(
                            color: c.accent, fontWeight: FontWeight.w700)),
                  ],
                ),
                const SizedBox(height: 6),
                LinearProgressIndicator(
                  value: ((eff.throttlePct ?? 0) / 100.0).clamp(0.0, 1.0),
                  minHeight: 10,
                  borderRadius: BorderRadius.circular(5),
                ),
                const SizedBox(height: 14),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    _statusChip('Parkir', eff.isPark == true, c.warning),
                    _statusChip('Standar Samping', eff.sideStandDown, c.danger),
                    _statusChip('Cruise', eff.cruiseActive, c.regen),
                    _statusChip('Mundur', eff.reverseActive, c.warning),
                  ],
                ),
                const SizedBox(height: 14),
                Row(
                  children: [
                    _tempCard('T_MOTOR', motor.tempMotorC),
                    const SizedBox(width: 8),
                    _tempCard('T_INVERTER', motor.tempInverterC),
                    const SizedBox(width: 8),
                    _tempCard('T_BATERAI', eff.b1TempC),
                  ],
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 14),

        // ODO Sync & Guard Card
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text('Odometer MID & Backup',
                          style: TextStyle(fontWeight: FontWeight.w700)),
                      Text(
                        'MID: ${motor.odoKm?.toStringAsFixed(1) ?? "--"} km • Backup App: ${ctrl.settings.odoBackupKm.toStringAsFixed(1)} km',
                        style: TextStyle(fontSize: 12, color: c.textMuted),
                      ),
                    ],
                  ),
                ),
                OutlinedButton(
                  onPressed: ctrl.settings.odoBackupKm > 0
                      ? () => ctrl.restoreMotorOdo(ctrl.settings.odoBackupKm)
                      : null,
                  child: const Text('Sinkron AT+ODO'),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 14),

        // DTC Error Bitmask Analyzer
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Icon(
                      eff.activeErrors.isEmpty
                          ? Icons.verified_rounded
                          : Icons.error_rounded,
                      color: eff.activeErrors.isEmpty ? c.accent : c.danger,
                    ),
                    const SizedBox(width: 8),
                    Text(
                      eff.activeErrors.isEmpty
                          ? 'Tidak Ada Kode Fault (ERR=0x00)'
                          : '${eff.activeErrors.length} Kode Fault Terdeteksi',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                  ],
                ),
                const SizedBox(height: 10),
                if (eff.activeErrors.isEmpty)
                  Text(
                    'Sistem controller, hall sensor, throttle, dan proteksi suhu dalam kondisi sehat.',
                    style: TextStyle(fontSize: 12.5, color: c.textMuted),
                  )
                else
                  ...eff.activeErrors.map(
                    (e) => Container(
                      margin: const EdgeInsets.only(bottom: 8),
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: c.danger.withOpacity(0.1),
                        borderRadius: BorderRadius.circular(12),
                        border: Border.all(color: c.danger.withOpacity(0.4)),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text('Bit ${e.bit}: ${e.label}',
                              style: TextStyle(
                                  fontWeight: FontWeight.w700,
                                  color: c.danger)),
                          const SizedBox(height: 4),
                          Text('Solusi: ${e.fix}',
                              style: const TextStyle(fontSize: 12)),
                        ],
                      ),
                    ),
                  ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildBatteryTab(BuildContext context) {
    final ctrl = context.watch<DashboardController>();
    final eff = ctrl.effective;
    final jk = ctrl.jkData;
    final b2 = ctrl.battery2Data;
    final c = context.cockpit;

    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 14, 16, 110),
      children: [
        SegmentedButton<int>(
          segments: const [
            ButtonSegment(value: 0, label: Text('Baterai 1 (JK-BMS/MID)')),
            ButtonSegment(value: 1, label: Text('Baterai 2 (ESP32 #2)')),
          ],
          selected: {_batteryTab},
          onSelectionChanged: (s) => setState(() => _batteryTab = s.first),
        ),
        const SizedBox(height: 14),
        if (_batteryTab == 0) ...[
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text('Sumber Aktif: ${eff.b1SourceLabel}',
                          style: Theme.of(context).textTheme.titleMedium),
                      Text(
                        '${eff.b1SocPct?.round() ?? "--"}%',
                        style: TextStyle(
                            fontSize: 24,
                            fontWeight: FontWeight.w800,
                            color: c.accent),
                      ),
                    ],
                  ),
                  const SizedBox(height: 10),
                  Row(
                    children: [
                      _kv('Volt Pack',
                          '${eff.b1Vbat?.toStringAsFixed(2) ?? "--"} V'),
                      _kv('Arus',
                          '${eff.b1Ibat?.toStringAsFixed(2) ?? "--"} A'),
                      _kv('SOH / Siklus',
                          '${eff.b1SohPct?.round() ?? "--"}% / ${jk.cycles ?? ctrl.motorData.cbatCycles ?? "--"}'),
                    ],
                  ),
                  const SizedBox(height: 10),
                  Row(
                    children: [
                      _kv('Delta Sel',
                          jk.deltaCellV != null ? '${(jk.deltaCellV! * 1000).round()} mV' : '--'),
                      _kv('T1 / T2',
                          '${jk.temp1C?.toStringAsFixed(1) ?? "--"} / ${jk.temp2C?.toStringAsFixed(1) ?? "--"}°C'),
                      _kv('MOSFET',
                          '${jk.tempMosC?.toStringAsFixed(1) ?? "--"}°C'),
                    ],
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 14),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          'Tegangan Per-Sel (${jk.cellCount}S)',
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                      ),
                      FilterChip(
                        label: const Text('Urutkan Min'),
                        selected: _sortCellsLowToHigh,
                        onSelected: (v) =>
                            setState(() => _sortCellsLowToHigh = v),
                      ),
                    ],
                  ),
                  const SizedBox(height: 10),
                  if (jk.cellVoltages.isEmpty)
                    Text(
                      'Hubungkan JK-BMS via Bluetooth LE untuk melihat detail voltase setiap sel.',
                      style: TextStyle(fontSize: 12.5, color: c.textMuted),
                    )
                  else
                    _buildCellGrid(
                      jk.cellVoltages,
                      jk.minCellIndex,
                      jk.maxCellIndex,
                    ),
                ],
              ),
            ),
          ),
        ] else ...[
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Baterai 2 • CAN Mode: ${b2.canMode}',
                      style: Theme.of(context).textTheme.titleMedium),
                  const SizedBox(height: 10),
                  Row(
                    children: [
                      _kv('SOC2', '${b2.socPct?.round() ?? "--"}%'),
                      _kv('Voltase', '${b2.vbat?.toStringAsFixed(2) ?? "--"} V'),
                      _kv('Arus', '${b2.ibat?.toStringAsFixed(2) ?? "--"} A'),
                    ],
                  ),
                  const SizedBox(height: 10),
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('Balik Polaritas Arus Baterai 2'),
                    value: ctrl.settings.invertB2Current,
                    onChanged: (v) => ctrl.updateSettings(
                        ctrl.settings.copyWith(invertB2Current: v)),
                  ),
                  const Divider(),
                  const Text('Kontrol Mode CAN & Emulator ESP32 #2',
                      style: TextStyle(fontWeight: FontWeight.w700)),
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      OutlinedButton(
                        onPressed: () =>
                            ctrl.sendEspCommand(2, 'CAN_MODE:LISTEN'),
                        child: const Text('Mode LISTEN'),
                      ),
                      OutlinedButton(
                        onPressed: () => ctrl.sendEspCommand(2, 'MID:ON'),
                        child: const Text('Kirim MID:ON'),
                      ),
                      OutlinedButton(
                        onPressed: () =>
                            ctrl.sendEspCommand(2, 'SIM:CONTROLLER'),
                        child: const Text('SIM Controller'),
                      ),
                      OutlinedButton(
                        onPressed: () => ctrl.sendEspCommand(2, 'SIM:BATTERY'),
                        child: const Text('SIM Battery'),
                      ),
                      OutlinedButton(
                        onPressed: () => ctrl.sendEspCommand(2, 'SIM:OFF'),
                        child: const Text('SIM OFF'),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ],
      ],
    );
  }

  Widget _buildCellGrid(List<double> cells, int minIdx, int maxIdx) {
    final c = context.cockpit;
    final indexed = List.generate(cells.length, (i) => (idx: i, v: cells[i]));
    if (_sortCellsLowToHigh) {
      indexed.sort((a, b) => a.v.compareTo(b.v));
    }

    return GridView.builder(
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      itemCount: indexed.length,
      gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 4,
        mainAxisSpacing: 6,
        crossAxisSpacing: 6,
        childAspectRatio: 1.85,
      ),
      itemBuilder: (_, i) {
        final item = indexed[i];
        final isMin = item.idx == minIdx;
        final isMax = item.idx == maxIdx;
        final borderCol = isMin
            ? c.danger
            : isMax
                ? c.regen
                : c.borderSubtle;
        return Container(
          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
          decoration: BoxDecoration(
            color: c.surfaceElevated,
            borderRadius: BorderRadius.circular(10),
            border: Border.all(color: borderCol, width: (isMin || isMax) ? 1.5 : 1),
          ),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Text('C${item.idx + 1}',
                  style: TextStyle(fontSize: 9.5, color: c.textMuted)),
              Text(
                '${item.v.toStringAsFixed(3)}V',
                style:
                    const TextStyle(fontSize: 11.5, fontWeight: FontWeight.w700),
              ),
            ],
          ),
        );
      },
    );
  }

  Widget _buildTrendTab(BuildContext context) {
    final ctrl = context.watch<DashboardController>();
    final pts = ctrl.liveChartPoints;
    final c = context.cockpit;

    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 14, 16, 110),
      children: [
        _trendCard(
          title: 'Kecepatan (km/h) & Daya (W)',
          series: [
            ChartSeries(
              name: 'Speed',
              values: pts.map((e) => e.speedKmh).toList(),
              color: c.accent,
              fixedMin: 0,
            ),
            ChartSeries(
              name: 'Daya',
              values: pts.map((e) => e.powerW / 40.0).toList(),
              color: c.warning,
            ),
          ],
          legend: 'Hijau: Kecepatan • Oranye: Daya (skala)',
        ),
        const SizedBox(height: 14),
        _trendCard(
          title: 'Tegangan (V) & Suhu (°C)',
          series: [
            ChartSeries(
              name: 'Volt',
              values: pts.map((e) => e.voltageV).toList(),
              color: c.accentSecondary,
            ),
            ChartSeries(
              name: 'Suhu',
              values: pts.map((e) => e.tempC).toList(),
              color: c.danger,
            ),
          ],
          legend: 'Biru: Voltase Pack • Merah: Suhu (°C)',
        ),
      ],
    );
  }

  Widget _trendCard({
    required String title,
    required List<ChartSeries> series,
    required String legend,
  }) {
    final c = context.cockpit;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: Theme.of(context).textTheme.titleMedium),
            Text(legend, style: TextStyle(fontSize: 11, color: c.textMuted)),
            const SizedBox(height: 12),
            SizedBox(
              height: 150,
              width: double.infinity,
              child: series.first.values.length < 2
                  ? Center(
                      child: Text(
                        'Menunggu sampel telemetri berjalan…',
                        style: TextStyle(color: c.textMuted),
                      ),
                    )
                  : CustomPaint(
                      painter: MultiSeriesChartPainter(
                        series: series,
                        gridColor: c.borderSubtle,
                      ),
                    ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _statusChip(String label, bool active, Color col) {
    final c = context.cockpit;
    return Chip(
      avatar: Icon(
        active ? Icons.check_circle_rounded : Icons.radio_button_unchecked,
        size: 16,
        color: active ? col : c.textMuted,
      ),
      label: Text(label),
    );
  }

  Widget _tempCard(String title, double? val) {
    final c = context.cockpit;
    final hot = (val ?? 0) >= 60;
    return Expanded(
      child: Container(
        padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(
          color: c.surfaceElevated,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: hot ? c.danger : c.borderSubtle),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: TextStyle(fontSize: 10, color: c.textMuted)),
            const SizedBox(height: 2),
            Text(
              val != null ? '${val.toStringAsFixed(1)}°C' : '--',
              style: TextStyle(
                fontWeight: FontWeight.w700,
                color: hot ? c.danger : null,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _kv(String k, String v) {
    final c = context.cockpit;
    return Expanded(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(k, style: TextStyle(fontSize: 11, color: c.textMuted)),
          const SizedBox(height: 2),
          Text(v, style: const TextStyle(fontWeight: FontWeight.w700)),
        ],
      ),
    );
  }
}
