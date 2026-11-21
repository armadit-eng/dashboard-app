import 'dart:io';

import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:provider/provider.dart';

import '../models/telemetry_models.dart';
import '../state/dashboard_controller.dart';
import '../theme/app_theme.dart';
import 'motovlog_cam_screen.dart';

class GarageAccountScreen extends StatefulWidget {
  const GarageAccountScreen({super.key});

  @override
  State<GarageAccountScreen> createState() => _GarageAccountScreenState();
}

class _GarageAccountScreenState extends State<GarageAccountScreen> {
  final TextEditingController _matrixCtrl = TextEditingController();
  double _relaySlide = 0.0;

  @override
  void dispose() {
    _matrixCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final ctrl = context.watch<DashboardController>();
    final s = ctrl.settings;
    final c = context.cockpit;
    final livery = kGarageLiveries.firstWhere(
      (g) => g.id == s.garageStyleId,
      orElse: () => kGarageLiveries.first,
    );

    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 110),
      children: [
        Row(
          children: [
            Icon(Icons.garage_rounded, color: c.accent),
            const SizedBox(width: 8),
            Expanded(
              child: Text('Garasi, Kontrol & Akun',
                  style: Theme.of(context).textTheme.headlineSmall),
            ),
            FilledButton.tonalIcon(
              onPressed: () => Navigator.of(context).push(
                MaterialPageRoute(builder: (_) => const MotovlogCamScreen()),
              ),
              icon: const Icon(Icons.videocam_rounded, size: 18),
              label: const Text('Motovlog'),
            ),
          ],
        ),
        const SizedBox(height: 12),

        // 1. Garasi Virtual + Profile Card
        Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            gradient: LinearGradient(
              colors: [
                livery.bodyColor.withOpacity(0.85),
                c.surfaceCard,
              ],
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
            ),
            borderRadius: BorderRadius.circular(22),
            border: Border.all(color: livery.trimColor.withOpacity(0.7)),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  GestureDetector(
                    onTap: () async {
                      final img = await ImagePicker()
                          .pickImage(source: ImageSource.gallery);
                      if (img != null) {
                        await ctrl.updateSettings(
                            s.copyWith(avatarPath: img.path));
                      }
                    },
                    child: CircleAvatar(
                      radius: 28,
                      backgroundColor: livery.trimColor.withOpacity(0.25),
                      backgroundImage: s.avatarPath.isNotEmpty &&
                              File(s.avatarPath).existsSync()
                          ? FileImage(File(s.avatarPath))
                          : null,
                      child: s.avatarPath.isEmpty
                          ? Icon(Icons.electric_moped_rounded,
                              color: livery.trimColor, size: 28)
                          : null,
                    ),
                  ),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(s.riderName,
                            style: const TextStyle(
                                fontSize: 17,
                                fontWeight: FontWeight.w800,
                                color: Colors.white)),
                        Text('${s.bikeName} • ${s.plateNumber}',
                            style: const TextStyle(
                                fontSize: 12.5, color: Colors.white70)),
                        Text('Livery: ${livery.name}',
                            style: TextStyle(
                                fontSize: 11.5, color: livery.trimColor)),
                      ],
                    ),
                  ),
                  IconButton(
                    icon: const Icon(Icons.edit_rounded, color: Colors.white),
                    onPressed: () => _editProfileDialog(context, ctrl),
                  ),
                ],
              ),
              const SizedBox(height: 14),
              SizedBox(
                height: 36,
                child: ListView.separated(
                  scrollDirection: Axis.horizontal,
                  itemCount: kGarageLiveries.length,
                  separatorBuilder: (_, __) => const SizedBox(width: 8),
                  itemBuilder: (_, i) {
                    final l = kGarageLiveries[i];
                    final sel = l.id == s.garageStyleId;
                    return ChoiceChip(
                      label: Text(l.name),
                      selected: sel,
                      onSelected: (_) =>
                          ctrl.updateSettings(s.copyWith(garageStyleId: l.id)),
                    );
                  },
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 14),

        // 2. Theme & Accent Palettes
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Text('Tema & Aksen Kokpit',
                        style: Theme.of(context).textTheme.titleMedium),
                    const Spacer(),
                    Switch(
                      value: s.isDarkTheme,
                      onChanged: (v) =>
                          ctrl.updateSettings(s.copyWith(isDarkTheme: v)),
                    ),
                    Text(s.isDarkTheme ? 'Gelap' : 'Terang',
                        style: const TextStyle(fontSize: 12)),
                  ],
                ),
                const SizedBox(height: 10),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: kAccentPalettes.map((p) {
                    final sel = p.id == s.accentId;
                    return InkWell(
                      onTap: () =>
                          ctrl.updateSettings(s.copyWith(accentId: p.id)),
                      borderRadius: BorderRadius.circular(12),
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                            horizontal: 10, vertical: 6),
                        decoration: BoxDecoration(
                          color: sel
                              ? p.primary.withOpacity(0.18)
                              : c.surfaceElevated,
                          borderRadius: BorderRadius.circular(12),
                          border: Border.all(
                              color: sel ? p.primary : c.borderSubtle,
                              width: sel ? 1.5 : 1),
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            CircleAvatar(radius: 6, backgroundColor: p.primary),
                            const SizedBox(width: 6),
                            Text(p.name, style: const TextStyle(fontSize: 12)),
                          ],
                        ),
                      ),
                    );
                  }).toList(),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 14),

        // 3. ESP32 #1 Accessories: Contact Relay, LED Matrix, DFPlayer Music
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Icon(Icons.memory_rounded, size: 18, color: c.accent),
                    const SizedBox(width: 8),
                    Text('Aksesori ESP32 #1',
                        style: Theme.of(context).textTheme.titleMedium),
                    const Spacer(),
                    Text(
                      ctrl.esp1ConnState == ConnectionStateX.connected
                          ? 'TERHUBUNG'
                          : 'TERPUTUS',
                      style: TextStyle(
                        fontSize: 11,
                        fontWeight: FontWeight.w800,
                        color: ctrl.esp1ConnState == ConnectionStateX.connected
                            ? c.accent
                            : c.textMuted,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                // Experimental Contact Relay with 2-step slide safety interlock
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: c.surfaceElevated,
                    borderRadius: BorderRadius.circular(14),
                    border: Border.all(color: c.warning.withOpacity(0.45)),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Kontak Relay Motor (${ctrl.esp1Data.contactOn == true ? "ON" : "OFF"})',
                        style: const TextStyle(fontWeight: FontWeight.w700),
                      ),
                      Text(
                        'Geser penuh ke kanan untuk toggle kontak secara aman (interlock kecepatan aktif).',
                        style: TextStyle(fontSize: 11, color: c.textMuted),
                      ),
                      Slider(
                        value: _relaySlide,
                        onChanged: (v) => setState(() => _relaySlide = v),
                        onChangeEnd: (v) async {
                          if (v > 0.88) {
                            final nextOn = !(ctrl.esp1Data.contactOn ?? false);
                            final err = await ctrl.setContactRelay(nextOn);
                            if (err != null && context.mounted) {
                              ScaffoldMessenger.of(context).showSnackBar(
                                SnackBar(content: Text(err)),
                              );
                            }
                          }
                          setState(() => _relaySlide = 0.0);
                        },
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 12),

                // MAX7219 LED Matrix Queue
                Row(
                  children: [
                    const Expanded(
                      child: Text('LED Matrix MAX7219',
                          style: TextStyle(fontWeight: FontWeight.w700)),
                    ),
                    Switch(
                      value: ctrl.esp1Data.matrixOn ?? false,
                      onChanged: (v) => ctrl.setMatrixPower(v),
                    ),
                    FilterChip(
                      label: const Text('Rotasi'),
                      selected: ctrl.matrixAutoRotate,
                      onSelected: ctrl.toggleMatrixAutoRotate,
                    ),
                  ],
                ),
                const SizedBox(height: 6),
                Row(
                  children: [
                    Expanded(
                      child: TextField(
                        controller: _matrixCtrl,
                        decoration: const InputDecoration(
                          hintText: 'Teks baru untuk LED Matrix…',
                          isDense: true,
                          border: OutlineInputBorder(),
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    FilledButton(
                      onPressed: () {
                        if (_matrixCtrl.text.trim().isNotEmpty) {
                          ctrl.addMatrixItem(_matrixCtrl.text.trim());
                          _matrixCtrl.clear();
                        }
                      },
                      child: const Text('Tambah'),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                ...ctrl.matrixQueue.map((item) => ListTile(
                      dense: true,
                      contentPadding: EdgeInsets.zero,
                      title: Text(item.text),
                      subtitle: Text('Speed ${item.speedMs} ms'),
                      trailing: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          IconButton(
                            icon: const Icon(Icons.send_rounded, size: 18),
                            onPressed: () => ctrl.sendMatrixItemNow(item),
                          ),
                          IconButton(
                            icon: const Icon(Icons.delete_outline_rounded,
                                size: 18),
                            onPressed: () => ctrl.deleteMatrixItem(item.id),
                          ),
                        ],
                      ),
                    )),
                const Divider(),

                // DFPlayer Music Controls
                Row(
                  children: [
                    const Icon(Icons.music_note_rounded, size: 18),
                    const SizedBox(width: 6),
                    Text(
                      'DFPlayer Track #${ctrl.esp1Data.musicTrack} (${ctrl.esp1Data.musicState})',
                      style: const TextStyle(fontWeight: FontWeight.w700),
                    ),
                  ],
                ),
                const SizedBox(height: 6),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                  children: [
                    IconButton(
                      onPressed: () => ctrl.sendEspCommand(1, 'MUSIC_PREV'),
                      icon: const Icon(Icons.skip_previous_rounded),
                    ),
                    FilledButton.tonalIcon(
                      onPressed: () => ctrl.sendEspCommand(1, 'MUSIC_TOGGLE'),
                      icon: Icon(ctrl.esp1Data.musicState == 'PLAY'
                          ? Icons.pause_rounded
                          : Icons.play_arrow_rounded),
                      label: Text(ctrl.esp1Data.musicState == 'PLAY'
                          ? 'Jeda'
                          : 'Putar'),
                    ),
                    IconButton(
                      onPressed: () => ctrl.sendEspCommand(1, 'MUSIC_STOP'),
                      icon: const Icon(Icons.stop_rounded),
                    ),
                    IconButton(
                      onPressed: () => ctrl.sendEspCommand(1, 'MUSIC_NEXT'),
                      icon: const Icon(Icons.skip_next_rounded),
                    ),
                  ],
                ),
                Row(
                  children: [
                    const Icon(Icons.volume_up_rounded, size: 18),
                    Expanded(
                      child: Slider(
                        min: 0,
                        max: 30,
                        divisions: 30,
                        value: ctrl.esp1Data.musicVol.toDouble().clamp(0, 30),
                        onChanged: (v) =>
                            ctrl.sendEspCommand(1, 'MUSIC_VOL:${v.round()}'),
                      ),
                    ),
                    Text('${ctrl.esp1Data.musicVol}'),
                  ],
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 14),

        // 4. Dual Battery & Route Physics Calibration
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Kalibrasi Baterai, Fisika & Alarm',
                    style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 10),
                _sliderSetting(
                  label: 'Kapasitas Baterai 1',
                  valueText: '${s.b1CapacityAh.round()} Ah (${(s.b1NominalV * s.b1CapacityAh).round()} Wh)',
                  value: s.b1CapacityAh,
                  min: 10,
                  max: 60,
                  onChanged: (v) =>
                      ctrl.updateSettings(s.copyWith(b1CapacityAh: v)),
                ),
                _sliderSetting(
                  label: 'Kapasitas Baterai 2',
                  valueText: '${s.b2CapacityAh.round()} Ah (${(s.b2NominalV * s.b2CapacityAh).round()} Wh)',
                  value: s.b2CapacityAh,
                  min: 10,
                  max: 60,
                  onChanged: (v) =>
                      ctrl.updateSettings(s.copyWith(b2CapacityAh: v)),
                ),
                _sliderSetting(
                  label: 'Referensi Konsumsi',
                  valueText: '${s.refWhPerKm.round()} Wh/km',
                  value: s.refWhPerKm,
                  min: 15,
                  max: 60,
                  onChanged: (v) =>
                      ctrl.updateSettings(s.copyWith(refWhPerKm: v)),
                ),
                _sliderSetting(
                  label: 'Ambang Alarm SOC Rendah',
                  valueText: '${s.alarmSocThreshold}%',
                  value: s.alarmSocThreshold.toDouble(),
                  min: 5,
                  max: 40,
                  onChanged: (v) => ctrl.updateSettings(
                      s.copyWith(alarmSocThreshold: v.round())),
                ),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Panduan Suara Navigasi (TTS)'),
                  value: s.voiceNavEnabled,
                  onChanged: (v) =>
                      ctrl.updateSettings(s.copyWith(voiceNavEnabled: v)),
                ),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Auto-Simpan Trip Saat Motor Putus'),
                  value: s.autoSaveTrip,
                  onChanged: (v) =>
                      ctrl.updateSettings(s.copyWith(autoSaveTrip: v)),
                ),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Auto-Reconnect Motor Saat Buka App'),
                  value: s.autoReconnect,
                  onChanged: (v) =>
                      ctrl.updateSettings(s.copyWith(autoReconnect: v)),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 14),

        // 5. AT+NAV Rotating Slots on MID LCD
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Rotasi Teks MID (AT+NAV)',
                    style: Theme.of(context).textTheme.titleMedium),
                Text(
                  'Pilih data yang dikirim bergantian ke layar motor setiap ${s.atNavSlots.intervalSec} detik.',
                  style: TextStyle(fontSize: 12, color: c.textMuted),
                ),
                const SizedBox(height: 10),
                Wrap(
                  spacing: 8,
                  runSpacing: 6,
                  children: [
                    _slotToggle('Range', s.atNavSlots.showRange,
                        (v) => s.atNavSlots.copyWith(showRange: v), ctrl),
                    _slotToggle('Trip', s.atNavSlots.showTrip,
                        (v) => s.atNavSlots.copyWith(showTrip: v), ctrl),
                    _slotToggle('Energi Wh', s.atNavSlots.showEnergy,
                        (v) => s.atNavSlots.copyWith(showEnergy: v), ctrl),
                    _slotToggle('Wh/km', s.atNavSlots.showEfficiency,
                        (v) => s.atNavSlots.copyWith(showEfficiency: v), ctrl),
                    _slotToggle('Daya W', s.atNavSlots.showPower,
                        (v) => s.atNavSlots.copyWith(showPower: v), ctrl),
                    _slotToggle('CO₂', s.atNavSlots.showCo2,
                        (v) => s.atNavSlots.copyWith(showCo2: v), ctrl),
                    _slotToggle('Lokasi', s.atNavSlots.showLocation,
                        (v) => s.atNavSlots.copyWith(showLocation: v), ctrl),
                    _slotToggle('Cuaca', s.atNavSlots.showWeather,
                        (v) => s.atNavSlots.copyWith(showWeather: v), ctrl),
                  ],
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget _sliderSetting({
    required String label,
    required String valueText,
    required double value,
    required double min,
    required double max,
    required ValueChanged<double> onChanged,
  }) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(label, style: const TextStyle(fontSize: 13)),
            Text(valueText,
                style: const TextStyle(
                    fontWeight: FontWeight.w700, fontSize: 12.5)),
          ],
        ),
        Slider(
          value: value.clamp(min, max),
          min: min,
          max: max,
          onChanged: onChanged,
        ),
      ],
    );
  }

  Widget _slotToggle(
    String label,
    bool selected,
    dynamic Function(bool) updater,
    DashboardController ctrl,
  ) {
    return FilterChip(
      label: Text(label),
      selected: selected,
      onSelected: (v) => ctrl.updateSettings(
        ctrl.settings.copyWith(atNavSlots: updater(v)),
      ),
    );
  }

  void _editProfileDialog(BuildContext context, DashboardController ctrl) {
    final nameC = TextEditingController(text: ctrl.settings.riderName);
    final bikeC = TextEditingController(text: ctrl.settings.bikeName);
    final plateC = TextEditingController(text: ctrl.settings.plateNumber);
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Profil Pengendara & Motor'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
                controller: nameC,
                decoration: const InputDecoration(labelText: 'Nama Rider')),
            TextField(
                controller: bikeC,
                decoration: const InputDecoration(labelText: 'Nama Motor')),
            TextField(
                controller: plateC,
                decoration: const InputDecoration(labelText: 'Plat Nomor')),
          ],
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx), child: const Text('Batal')),
          FilledButton(
            onPressed: () {
              ctrl.updateSettings(ctrl.settings.copyWith(
                riderName: nameC.text.trim(),
                bikeName: bikeC.text.trim(),
                plateNumber: plateC.text.trim(),
              ));
              Navigator.pop(ctx);
            },
            child: const Text('Simpan'),
          ),
        ],
      ),
    );
  }
}
