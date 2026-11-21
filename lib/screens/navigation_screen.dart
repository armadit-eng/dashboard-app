import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:latlong2/latlong.dart';
import 'package:provider/provider.dart';

import '../models/navigation_models.dart';
import '../models/telemetry_models.dart';
import '../services/platform_tools_channel.dart';
import '../services/remote_apis_service.dart';
import '../services/route_physics.dart';
import '../state/dashboard_controller.dart';
import '../theme/app_theme.dart';
import '../widgets/cockpit_painters.dart';

class NavigationScreen extends StatefulWidget {
  const NavigationScreen({super.key});

  @override
  State<NavigationScreen> createState() => _NavigationScreenState();
}

class _NavigationScreenState extends State<NavigationScreen> {
  final MapController _mapCtrl = MapController();
  final TextEditingController _searchCtrl = TextEditingController();
  List<SavedPlace> _searchResults = [];
  bool _searching = false;
  bool _followRider = true;
  double _unlockProgress = 0.0;
  Timer? _unlockTimer;

  @override
  void dispose() {
    _searchCtrl.dispose();
    _unlockTimer?.cancel();
    super.dispose();
  }

  String _tileUrl(MapStyleOption style) {
    return switch (style) {
      MapStyleOption.osmStandard =>
        'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
      MapStyleOption.darkCarto =>
        'https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png',
      MapStyleOption.satellite =>
        'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
      MapStyleOption.topo => 'https://a.tile.opentopomap.org/{z}/{x}/{y}.png',
    };
  }

  @override
  Widget build(BuildContext context) {
    final ctrl = context.watch<DashboardController>();
    final eff = ctrl.effective;
    final c = context.cockpit;
    final style = MapStyleOption.fromId(ctrl.settings.mapStyleId);
    final riderPos = ctrl.gpsState.location;
    final center = riderPos != null
        ? LatLng(riderPos.lat, riderPos.lng)
        : const LatLng(-6.1754, 106.8272);

    if (_followRider && riderPos != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        try {
          _mapCtrl.move(center, _mapCtrl.camera.zoom);
        } catch (_) {}
      });
    }

    return Stack(
      children: [
        // 1. Live Interactive Map
        RepaintBoundary(child: FlutterMap(
          mapController: _mapCtrl,
          options: MapOptions(
            initialCenter: center,
            initialZoom: 15,
            onPositionChanged: (_, hasGesture) {
              if (hasGesture && _followRider) {
                setState(() => _followRider = false);
              }
            },
            onLongPress: (_, latLng) {
              ctrl.addStopAndRoute(PlannedStop(
                id: 'pin_${DateTime.now().millisecondsSinceEpoch}',
                label:
                    'Pin (${latLng.latitude.toStringAsFixed(4)}, ${latLng.longitude.toStringAsFixed(4)})',
                lat: latLng.latitude,
                lng: latLng.longitude,
              ));
            },
          ),
          children: [
            TileLayer(
              urlTemplate: _tileUrl(style),
              userAgentPackageName: 'com.dashboard.app.dashboard_app',
              maxZoom: ctrl.settings.dataSaverMap ? 16 : 19,
            ),
            if (ctrl.activeRoute != null &&
                ctrl.activeRoute!.geometry.isNotEmpty)
              PolylineLayer(
                polylines: [
                  Polyline(
                    points: ctrl.activeRoute!.geometry
                        .map((p) => LatLng(p.lat, p.lng))
                        .toList(),
                    color: c.accent,
                    strokeWidth: 5.5,
                  ),
                ],
              ),
            MarkerLayer(
              markers: [
                // Rider marker
                Marker(
                  point: center,
                  width: 44,
                  height: 44,
                  child: _riderMarkerWidget(
                      ctrl.settings.customMarkerPath,
                      ctrl.gpsState.headingDeg ?? 0,
                      c.accent),
                ),
                // Planned stops
                ...ctrl.plannedStops.asMap().entries.map(
                      (e) => Marker(
                        point: LatLng(e.value.lat, e.value.lng),
                        width: 36,
                        height: 36,
                        child: CircleAvatar(
                          backgroundColor:
                              e.value.isSpklu ? c.regen : c.danger,
                          child: Text(
                            e.value.isSpklu ? '⚡' : '${e.key + 1}',
                            style: const TextStyle(
                                color: Colors.white,
                                fontWeight: FontWeight.w800,
                                fontSize: 12),
                          ),
                        ),
                      ),
                    ),
                // SPKLU markers
                ...ctrl.nearbySpklu.take(20).map(
                      (s) => Marker(
                        point: LatLng(s.lat, s.lng),
                        width: 32,
                        height: 32,
                        child: GestureDetector(
                          onTap: () => _showSpkluDetail(context, ctrl, s),
                          child: Container(
                            decoration: BoxDecoration(
                              color: c.surfaceCard,
                              shape: BoxShape.circle,
                              border: Border.all(color: c.regen, width: 2),
                            ),
                            child: Icon(Icons.ev_station_rounded,
                                size: 17, color: c.regen),
                          ),
                        ),
                      ),
                    ),
                // Field notes markers
                ...ctrl.fieldNotes.map(
                  (n) => Marker(
                    point: LatLng(n.lat, n.lng),
                    width: 28,
                    height: 28,
                    child: Tooltip(
                      message: '${n.name}: ${n.note}',
                      child: Icon(Icons.bookmark_rounded,
                          color: c.warning, size: 24),
                    ),
                  ),
                ),
              ],
            ),
          ],
        )),

        // 2. Top Search & Turn Banner
        SafeArea(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (ctrl.isNavigating && ctrl.activeRoute != null)
                  _activeTurnCard(context, ctrl)
                else
                  _searchBar(context, ctrl),
                if (_searchResults.isNotEmpty)
                  Container(
                    margin: const EdgeInsets.only(top: 6),
                    constraints: const BoxConstraints(maxHeight: 210),
                    decoration: BoxDecoration(
                      color: c.surfaceCard.withOpacity(0.96),
                      borderRadius: BorderRadius.circular(16),
                      border: Border.all(color: c.borderSubtle),
                    ),
                    child: ListView.separated(
                      shrinkWrap: true,
                      itemCount: _searchResults.length,
                      separatorBuilder: (_, __) => const Divider(height: 1),
                      itemBuilder: (_, i) {
                        final p = _searchResults[i];
                        return ListTile(
                          dense: true,
                          leading: Icon(Icons.place_rounded, color: c.accent),
                          title: Text(p.name,
                              maxLines: 1, overflow: TextOverflow.ellipsis),
                          subtitle: Text(p.category,
                              style: const TextStyle(fontSize: 11)),
                          trailing: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              IconButton(
                                tooltip: 'Simpan Favorit',
                                icon: const Icon(Icons.star_border_rounded,
                                    size: 18),
                                onPressed: () => ctrl.savePlace(p),
                              ),
                              IconButton(
                                tooltip: 'Tambah ke Rute',
                                icon: Icon(Icons.add_location_alt_rounded,
                                    color: c.accent, size: 20),
                                onPressed: () {
                                  setState(() => _searchResults = []);
                                  _searchCtrl.clear();
                                  ctrl.addStopAndRoute(PlannedStop(
                                    id: p.id,
                                    label: p.name,
                                    lat: p.lat,
                                    lng: p.lng,
                                  ));
                                },
                              ),
                            ],
                          ),
                          onTap: () {
                            setState(() => _searchResults = []);
                            _searchCtrl.clear();
                            ctrl.setSingleDestination(PlannedStop(
                              id: p.id,
                              label: p.name,
                              lat: p.lat,
                              lng: p.lng,
                            ));
                          },
                        );
                      },
                    ),
                  ),
              ],
            ),
          ),
        ),

        // 3. Right Floating Map Tools (Style, Follow, SPKLU, Catat Nama, MID Stream)
        Positioned(
          right: 12,
          bottom: ctrl.activeRoute != null ? 215 : 108,
          child: Column(
            children: [
              _mapFab(
                icon: _followRider
                    ? Icons.my_location_rounded
                    : Icons.location_searching_rounded,
                active: _followRider,
                tooltip: 'Pusatkan ke pengendara',
                onTap: () {
                  setState(() => _followRider = true);
                  _mapCtrl.move(center, 16);
                },
              ),
              const SizedBox(height: 8),
              _mapFab(
                icon: Icons.layers_rounded,
                tooltip: 'Gaya Peta & Hemat Data',
                onTap: () => _openMapStyleSheet(context, ctrl),
              ),
              const SizedBox(height: 8),
              _mapFab(
                icon: Icons.ev_station_rounded,
                active: ctrl.nearbySpklu.isNotEmpty,
                tooltip: 'Cari SPKLU Terdekat',
                onTap: () async {
                  await ctrl.refreshNearbySpklu();
                  if (context.mounted) _openSpkluListSheet(context, ctrl);
                },
              ),
              const SizedBox(height: 8),
              _mapFab(
                icon: Icons.bookmark_add_rounded,
                tooltip: 'Catat Nama Lokasi',
                onTap: () => _openFieldNotesSheet(context, ctrl),
              ),
              const SizedBox(height: 8),
              _mapFab(
                icon: Icons.cast_connected_rounded,
                active: ctrl.midStreaming || ctrl.screenMirrorActive,
                tooltip: 'Streaming MID AT+MAPS',
                onTap: () => _openMidStreamSheet(context, ctrl),
              ),
            ],
          ),
        ),

        // 4. Bottom Route / Elevation / Planner Sheet Summary
        if (ctrl.activeRoute != null)
          Positioned(
            left: 12,
            right: 12,
            bottom: 94,
            child: _routeBottomCard(context, ctrl),
          ),

        // 5. Ride Lock Overlay (Hold to unlock)
        if (ctrl.isRideLocked)
          Positioned.fill(
            child: Container(
              color: const Color(0xF0070B10),
              padding: const EdgeInsets.all(28),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(Icons.lock_outline_rounded, size: 42, color: c.accent),
                  const SizedBox(height: 12),
                  const Text('MODE KUNCI BERKENDARA',
                      style: TextStyle(
                          letterSpacing: 1.5,
                          fontWeight: FontWeight.w700,
                          color: Colors.white70)),
                  const SizedBox(height: 24),
                  Text(
                    '${(eff.speedKmh ?? 0).round()}',
                    style: const TextStyle(
                        fontSize: 82,
                        fontWeight: FontWeight.w800,
                        color: Colors.white),
                  ),
                  const Text('KM/H', style: TextStyle(color: Colors.white54)),
                  const SizedBox(height: 24),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                    children: [
                      Text('SOC ${eff.combinedSocPct?.round() ?? "--"}%',
                          style: TextStyle(
                              fontSize: 18,
                              fontWeight: FontWeight.w700,
                              color: c.accent)),
                      Text('Jarak ${eff.rangeKm.toStringAsFixed(1)} km',
                          style: const TextStyle(
                              fontSize: 18, color: Colors.white)),
                    ],
                  ),
                  const SizedBox(height: 40),
                  GestureDetector(
                    onLongPressStart: (_) {
                      _unlockTimer?.cancel();
                      _unlockTimer = Timer.periodic(
                          const Duration(milliseconds: 50), (t) {
                        setState(() {
                          _unlockProgress += 0.04;
                          if (_unlockProgress >= 1.0) {
                            _unlockProgress = 0;
                            t.cancel();
                            ctrl.setRideLocked(false);
                          }
                        });
                      });
                    },
                    onLongPressEnd: (_) {
                      _unlockTimer?.cancel();
                      setState(() => _unlockProgress = 0);
                    },
                    child: Column(
                      children: [
                        SizedBox(
                          width: 220,
                          child: LinearProgressIndicator(
                            value: _unlockProgress,
                            minHeight: 8,
                            borderRadius: BorderRadius.circular(4),
                          ),
                        ),
                        const SizedBox(height: 10),
                        const Text(
                          'Tahan 1.5 detik untuk membuka kunci',
                          style: TextStyle(color: Colors.white70),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
      ],
    );
  }

  Widget _riderMarkerWidget(String customPath, double heading, Color accent) {
    if (customPath.isNotEmpty && File(customPath).existsSync()) {
      return Transform.rotate(
        angle: heading * 3.1415926 / 180.0,
        child: ClipOval(
          child: Image.file(File(customPath), fit: BoxFit.cover),
        ),
      );
    }
    return Transform.rotate(
      angle: heading * 3.1415926 / 180.0,
      child: Icon(Icons.navigation_rounded, color: accent, size: 34),
    );
  }

  Widget _searchBar(BuildContext context, DashboardController ctrl) {
    final c = context.cockpit;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
      decoration: BoxDecoration(
        color: c.surfaceCard.withOpacity(0.95),
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: c.borderSubtle),
      ),
      child: Row(
        children: [
          Icon(Icons.search_rounded, color: c.accent),
          const SizedBox(width: 8),
          Expanded(
            child: TextField(
              controller: _searchCtrl,
              textInputAction: TextInputAction.search,
              onSubmitted: (_) => _runSearch(),
              decoration: const InputDecoration(
                hintText: 'Cari tujuan, koordinat, atau link Maps…',
                border: InputBorder.none,
                isDense: true,
              ),
            ),
          ),
          if (_searching)
            const SizedBox(
                width: 18,
                height: 18,
                child: CircularProgressIndicator(strokeWidth: 2))
          else
            IconButton(
              icon: const Icon(Icons.star_rounded, size: 20),
              tooltip: 'Tempat Tersimpan',
              onPressed: () => _openSavedPlacesSheet(context, ctrl),
            ),
        ],
      ),
    );
  }

  Future<void> _runSearch() async {
    final q = _searchCtrl.text.trim();
    if (q.isEmpty) return;
    setState(() => _searching = true);
    final res = await RemoteApisService.searchPlaces(q);
    if (mounted) {
      setState(() {
        _searching = false;
        _searchResults = res;
      });
    }
  }

  Widget _activeTurnCard(BuildContext context, DashboardController ctrl) {
    final c = context.cockpit;
    final route = ctrl.activeRoute!;
    final step = ctrl.activeStepIndex < route.steps.length
        ? route.steps[ctrl.activeStepIndex]
        : null;
    final distTxt = ctrl.distToNextStepM >= 1000
        ? '${(ctrl.distToNextStepM / 1000).toStringAsFixed(1)} km'
        : '${ctrl.distToNextStepM.round()} m';

    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: c.surfaceCard.withOpacity(0.96),
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: c.accent),
      ),
      child: Row(
        children: [
          CircleAvatar(
            backgroundColor: c.accent.withOpacity(0.16),
            child: Icon(Icons.turn_right_rounded, color: c.accent),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(distTxt,
                    style: TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.w800,
                        color: c.accent)),
                Text(
                  step?.instruction ?? 'Ikuti jalur rute',
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
              ],
            ),
          ),
          IconButton(
            tooltip: 'Kunci Layar Berkendara',
            onPressed: () => ctrl.setRideLocked(true),
            icon: const Icon(Icons.lock_rounded),
          ),
          IconButton(
            tooltip: 'Hentikan Navigasi',
            onPressed: () => ctrl.stopNavigation(),
            icon: Icon(Icons.close_rounded, color: c.danger),
          ),
        ],
      ),
    );
  }

  Widget _routeBottomCard(BuildContext context, DashboardController ctrl) {
    final c = context.cockpit;
    final route = ctrl.activeRoute!;
    final est = route.energyEstimate;

    return Card(
      color: c.surfaceCard.withOpacity(0.96),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '${(route.distanceM / 1000).toStringAsFixed(1)} km • ${(route.durationSec / 60).round()} mnt',
                        style: const TextStyle(
                            fontWeight: FontWeight.w800, fontSize: 15),
                      ),
                      if (est != null)
                        Text(
                          'Net ${est.netWh.round()} Wh • Tiba ${est.socAtArrivalPct?.round() ?? "--"}% SOC • Tanjak +${est.totalAscentM.round()}m',
                          style: TextStyle(fontSize: 11.5, color: c.textMuted),
                        ),
                    ],
                  ),
                ),
                if (ctrl.plannedStops.length > 1)
                  IconButton(
                    tooltip: 'Lihat Multi-Stop',
                    onPressed: () => _openMultiStopSheet(context, ctrl),
                    icon: const Icon(Icons.format_list_numbered_rounded),
                  ),
                if (!ctrl.isNavigating)
                  FilledButton.icon(
                    onPressed: () => ctrl.startNavigation(),
                    icon: const Icon(Icons.navigation_rounded, size: 17),
                    label: const Text('Mulai'),
                  )
                else
                  OutlinedButton(
                    onPressed: () => ctrl.stopNavigation(),
                    child: const Text('Stop'),
                  ),
              ],
            ),
            if (route.elevationSamples.length >= 3) ...[
              const SizedBox(height: 8),
              SizedBox(
                height: 62,
                width: double.infinity,
                child: RepaintBoundary(child: CustomPaint(
                  painter: ElevationProfilePainter(
                    samples: route.elevationSamples,
                    trackColor: c.borderSubtle,
                    labelColor: c.textMuted,
                  ),
                )),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _mapFab({
    required IconData icon,
    required String tooltip,
    required VoidCallback onTap,
    bool active = false,
  }) {
    final c = context.cockpit;
    return Tooltip(
      message: tooltip,
      child: Material(
        color: active ? c.accent : c.surfaceCard.withOpacity(0.95),
        shape: const CircleBorder(),
        elevation: 3,
        child: InkWell(
          onTap: onTap,
          customBorder: const CircleBorder(),
          child: SizedBox(
            width: 46,
            height: 46,
            child: Icon(
              icon,
              size: 21,
              color: active ? Colors.black : null,
            ),
          ),
        ),
      ),
    );
  }

  void _openMapStyleSheet(BuildContext context, DashboardController ctrl) {
    showModalBottomSheet(
      context: context,
      builder: (ctx) => Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Tampilan Peta',
                style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: MapStyleOption.values.map((m) {
                return ChoiceChip(
                  label: Text(m.label),
                  selected: ctrl.settings.mapStyleId == m.id,
                  onSelected: (_) {
                    ctrl.updateSettings(
                        ctrl.settings.copyWith(mapStyleId: m.id));
                    Navigator.pop(ctx);
                  },
                );
              }).toList(),
            ),
            const SizedBox(height: 10),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Mode Hemat Data Peta'),
              value: ctrl.settings.dataSaverMap,
              onChanged: (v) =>
                  ctrl.updateSettings(ctrl.settings.copyWith(dataSaverMap: v)),
            ),
          ],
        ),
      ),
    );
  }

  void _openSavedPlacesSheet(BuildContext context, DashboardController ctrl) {
    showModalBottomSheet(
      context: context,
      builder: (ctx) => Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('Tempat Tersimpan',
                style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            if (ctrl.savedPlaces.isEmpty)
              const Padding(
                padding: EdgeInsets.all(16),
                child: Text('Belum ada lokasi favorit tersimpan.'),
              )
            else
              ...ctrl.savedPlaces.map((p) => ListTile(
                    title: Text(p.name),
                    subtitle: Text(p.category),
                    trailing: IconButton(
                      icon: const Icon(Icons.delete_outline_rounded),
                      onPressed: () {
                        ctrl.removeSavedPlace(p.id);
                        Navigator.pop(ctx);
                      },
                    ),
                    onTap: () {
                      Navigator.pop(ctx);
                      ctrl.setSingleDestination(PlannedStop(
                        id: p.id,
                        label: p.name,
                        lat: p.lat,
                        lng: p.lng,
                      ));
                    },
                  )),
          ],
        ),
      ),
    );
  }

  void _openSpkluListSheet(BuildContext context, DashboardController ctrl) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      builder: (ctx) => DraggableScrollableSheet(
        initialChildSize: 0.65,
        expand: false,
        builder: (_, sc) => Column(
          children: [
            Padding(
              padding: const EdgeInsets.all(16),
              child: Row(
                children: [
                  const Icon(Icons.ev_station_rounded),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      'SPKLU Terdekat (${ctrl.nearbySpklu.length})',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                  ),
                  TextButton.icon(
                    onPressed: () {
                      final loc = ctrl.gpsState.location;
                      if (loc != null) {
                        ctrl.addUserSpklu(
                            'SPKLU Catatan', 'AC/Type2', loc.lat, loc.lng);
                      }
                    },
                    icon: const Icon(Icons.add_rounded, size: 18),
                    label: const Text('Tambah Disini'),
                  ),
                ],
              ),
            ),
            Expanded(
              child: ListView.builder(
                controller: sc,
                itemCount: ctrl.nearbySpklu.length,
                itemBuilder: (_, i) {
                  final s = ctrl.nearbySpklu[i];
                  return ListTile(
                    leading: const Icon(Icons.bolt_rounded),
                    title: Text(s.name),
                    subtitle: Text(
                      '${s.operatorName} • ${s.socketType}'
                      '${s.distanceKm != null ? " • ${s.distanceKm!.toStringAsFixed(1)} km" : ""}',
                    ),
                    trailing: FilledButton.tonal(
                      onPressed: () {
                        Navigator.pop(ctx);
                        ctrl.addStopAndRoute(PlannedStop(
                          id: s.id,
                          label: s.name,
                          lat: s.lat,
                          lng: s.lng,
                          isSpklu: true,
                        ));
                      },
                      child: const Text('Rute'),
                    ),
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }

  void _showSpkluDetail(
      BuildContext context, DashboardController ctrl, SpkluStation s) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(s.name),
        content: Text(
          'Operator: ${s.operatorName}\nKonektor: ${s.socketType}\n'
          'Daya: ${s.powerKw != null ? "${s.powerKw} kW" : "Standar"}\n'
          'Jarak: ${s.distanceKm?.toStringAsFixed(2) ?? "--"} km',
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx), child: const Text('Tutup')),
          FilledButton(
            onPressed: () {
              Navigator.pop(ctx);
              ctrl.addStopAndRoute(PlannedStop(
                id: s.id,
                label: s.name,
                lat: s.lat,
                lng: s.lng,
                isSpklu: true,
              ));
            },
            child: const Text('Sisipkan Pemberhentian'),
          ),
        ],
      ),
    );
  }

  void _openMultiStopSheet(BuildContext context, DashboardController ctrl) {
    showModalBottomSheet(
      context: context,
      builder: (ctx) => Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('Perencana Multi-Stop',
                style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            ...ctrl.plannedStops.asMap().entries.map((e) {
              final leg = (ctrl.activeRoute != null &&
                      e.key < ctrl.activeRoute!.legSummaries.length)
                  ? ctrl.activeRoute!.legSummaries[e.key]
                  : null;
              return ListTile(
                leading: CircleAvatar(child: Text('${e.key + 1}')),
                title: Text(e.value.label),
                subtitle: leg != null
                    ? Text(
                        '${leg.legDistKm.toStringAsFixed(1)} km • Tiba SOC ${leg.arrivalSocPct?.round() ?? "--"}%')
                    : null,
                trailing: IconButton(
                  icon: const Icon(Icons.close_rounded),
                  onPressed: () {
                    ctrl.removeStopAt(e.key);
                    Navigator.pop(ctx);
                  },
                ),
              );
            }),
          ],
        ),
      ),
    );
  }

  void _openFieldNotesSheet(BuildContext context, DashboardController ctrl) {
    final nameCtrl = TextEditingController();
    final noteCtrl = TextEditingController();
    String cat = 'SPKLU';
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setS) => Padding(
          padding: EdgeInsets.fromLTRB(
              20, 20, 20, MediaQuery.of(ctx).viewInsets.bottom + 20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text('Catat Nama Lokasi (Field Notes)',
                        style: Theme.of(context).textTheme.titleMedium),
                  ),
                  TextButton.icon(
                    onPressed: ctrl.fieldNotes.isEmpty
                        ? null
                        : () {
                            final gpx =
                                RoutePhysics.exportFieldNotesGpx(ctrl.fieldNotes);
                            PlatformToolsChannel.shareText(
                              gpx,
                              subject: 'FieldNotes_EV.gpx',
                            );
                          },
                    icon: const Icon(Icons.share_rounded, size: 16),
                    label: const Text('GPX'),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              TextField(
                controller: nameCtrl,
                decoration: const InputDecoration(
                    labelText: 'Nama Lokasi / Charger', isDense: true),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: noteCtrl,
                decoration: const InputDecoration(
                    labelText: 'Catatan (tarif, tipe soket, patokan)',
                    isDense: true),
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                children: ['SPKLU', 'Bengkel', 'Spot Istirahat', 'Tanjakan']
                    .map((c) => ChoiceChip(
                          label: Text(c),
                          selected: cat == c,
                          onSelected: (_) => setS(() => cat = c),
                        ))
                    .toList(),
              ),
              const SizedBox(height: 10),
              SizedBox(
                width: double.infinity,
                child: FilledButton(
                  onPressed: () {
                    if (nameCtrl.text.trim().isNotEmpty) {
                      ctrl.addFieldNote(
                        name: nameCtrl.text.trim(),
                        category: cat,
                        note: noteCtrl.text.trim(),
                      );
                      Navigator.pop(ctx);
                    }
                  },
                  child: const Text('Simpan Pin GPS Sekarang'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  void _openMidStreamSheet(BuildContext context, DashboardController ctrl) {
    showModalBottomSheet(
      context: context,
      builder: (ctx) => Consumer<DashboardController>(
        builder: (_, cCtrl, __) => Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('MID LCD Streaming (AT+MAPS)',
                  style: Theme.of(context).textTheme.titleMedium),
              Text(
                'Terkirim: ${cCtrl.mapsFramesSent} frame • ${cCtrl.mapsLastMs} ms (${cCtrl.mapsLastChars} chr)',
                style: const TextStyle(fontSize: 12),
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: FilledButton.icon(
                      onPressed: () {
                        if (cCtrl.midStreaming) {
                          cCtrl.stopMidStreaming();
                        } else {
                          cCtrl.startMidStreaming();
                        }
                      },
                      icon: Icon(cCtrl.midStreaming
                          ? Icons.stop_rounded
                          : Icons.play_arrow_rounded),
                      label: Text(cCtrl.midStreaming
                          ? 'Hentikan Peta MID'
                          : 'Mulai Peta MID'),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: OutlinedButton.icon(
                      onPressed: () {
                        if (cCtrl.screenMirrorActive) {
                          cCtrl.stopScreenMirror();
                        } else {
                          cCtrl.startScreenMirror();
                        }
                      },
                      icon: Icon(cCtrl.screenMirrorActive
                          ? Icons.cast_rounded
                          : Icons.screen_share_rounded),
                      label: Text(cCtrl.screenMirrorActive
                          ? 'Stop Mirror'
                          : 'Screen Mirror'),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 10),
              SegmentedButton<String>(
                segments: const [
                  ButtonSegment(value: 'full', label: Text('Peta Penuh')),
                  ButtonSegment(value: 'split', label: Text('Split Panel')),
                ],
                selected: {cCtrl.settings.mapsLayout},
                onSelectionChanged: (sel) => cCtrl.updateSettings(
                    cCtrl.settings.copyWith(mapsLayout: sel.first)),
              ),
              const SizedBox(height: 8),
              Row(
                children: [
                  Expanded(
                    child: SwitchListTile(
                      contentPadding: EdgeInsets.zero,
                      title: const Text('Wallpaper Kustom MID'),
                      value: cCtrl.settings.customWallpaperEnabled,
                      onChanged: (v) => cCtrl.updateSettings(cCtrl.settings
                          .copyWith(customWallpaperEnabled: v)),
                    ),
                  ),
                  TextButton(
                    onPressed: () async {
                      final path = await PlatformToolsChannel.pickImage();
                      if (path != null && path.isNotEmpty) {
                        await cCtrl.updateSettings(cCtrl.settings.copyWith(
                          customWallpaperPath: path,
                          customWallpaperEnabled: true,
                        ));
                      }
                    },
                    child: const Text('Pilih Foto'),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
