import 'package:flutter/material.dart';
import 'package:latlong2/latlong.dart';

import '../services/tile_cache.dart';

/// Bagian "Peta Offline" di sheet pengaturan peta.
class OfflineMapSection extends StatefulWidget {
  const OfflineMapSection({
    super.key,
    required this.styleId,
    required this.urlTemplate,
    required this.center,
    required this.canBulkDownload,
  });

  final String styleId;
  final String urlTemplate;
  final LatLng Function() center;
  final bool canBulkDownload;

  @override
  State<OfflineMapSection> createState() => _OfflineMapSectionState();
}

class _OfflineMapSectionState extends State<OfflineMapSection> {
  double _radiusKm = 5;
  bool _offline = TileCacheService.offlineOnly;
  bool _busy = false;
  bool _cancel = false;
  int _done = 0;
  int _total = 0;
  String _msg = '';
  String _stats = 'Menghitung cache...';

  @override
  void initState() {
    super.initState();
    _refreshStats();
  }

  Future<void> _refreshStats() async {
    final s = await TileCacheService.stats();
    if (!mounted) return;
    setState(() {
      _stats =
          'Cache: ${s.files} tile • ${(s.bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
    });
  }

  Future<void> _download() async {
    final c = widget.center();
    setState(() {
      _busy = true;
      _cancel = false;
      _done = 0;
      _total = 0;
      _msg = '';
    });
    try {
      final ok = await TileCacheService.downloadArea(
        styleId: widget.styleId,
        urlTemplate: widget.urlTemplate,
        lat: c.latitude,
        lng: c.longitude,
        radiusKm: _radiusKm,
        onProgress: (d, t) {
          if (!mounted) return;
          setState(() {
            _done = d;
            _total = t;
          });
        },
        isCancelled: () => _cancel,
      );
      _msg = _cancel
          ? 'Dibatalkan ($ok tile tersimpan).'
          : 'Selesai: $ok dari $_total tile tersimpan.';
    } on StateError catch (e) {
      _msg = e.message;
    } catch (e) {
      _msg = 'Gagal mengunduh: $e';
    }
    if (!mounted) return;
    setState(() => _busy = false);
    _refreshStats();
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Divider(height: 24),
        Text('Peta Offline', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 4),
        Text(_stats, style: const TextStyle(fontSize: 12)),
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('Mode offline (tanpa internet)'),
          subtitle: const Text('Hanya memakai tile yang sudah tersimpan'),
          value: _offline,
          onChanged: (v) {
            TileCacheService.offlineOnly = v;
            setState(() => _offline = v);
          },
        ),
        if (widget.canBulkDownload) ...[
          const Text('Unduh area di sekitar pusat peta (zoom 10–16):',
              style: TextStyle(fontSize: 12)),
          const SizedBox(height: 6),
          Wrap(
            spacing: 8,
            children: [
              for (final r in const [3.0, 5.0, 10.0])
                ChoiceChip(
                  label: Text('${r.toStringAsFixed(0)} km'),
                  selected: _radiusKm == r,
                  onSelected:
                      _busy ? null : (_) => setState(() => _radiusKm = r),
                ),
            ],
          ),
          const SizedBox(height: 8),
          Row(
            children: [
              Expanded(
                child: FilledButton.icon(
                  onPressed: _busy ? null : _download,
                  icon: const Icon(Icons.download_rounded),
                  label: const Text('Unduh area'),
                ),
              ),
              if (_busy) ...[
                const SizedBox(width: 8),
                OutlinedButton(
                  onPressed: () => setState(() => _cancel = true),
                  child: const Text('Batal'),
                ),
              ],
            ],
          ),
          if (_busy || _total > 0) ...[
            const SizedBox(height: 8),
            LinearProgressIndicator(
                value: _total == 0 ? null : _done / _total),
            Text('$_done / $_total tile', style: const TextStyle(fontSize: 12)),
          ],
          if (_msg.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Text(_msg, style: const TextStyle(fontSize: 12)),
            ),
        ] else
          const Text(
            'Unduh area hanya tersedia untuk gaya "Cockpit Gelap" (CARTO). '
            'Gaya lain tetap disimpan otomatis ke cache saat dilihat.',
            style: TextStyle(fontSize: 12),
          ),
        const SizedBox(height: 6),
        Row(
          children: [
            TextButton.icon(
              onPressed: _busy
                  ? null
                  : () async {
                      await TileCacheService.clear();
                      _refreshStats();
                    },
              icon: const Icon(Icons.delete_outline_rounded),
              label: const Text('Hapus cache'),
            ),
          ],
        ),
        const Text('© OpenStreetMap contributors © CARTO',
            style: TextStyle(fontSize: 10)),
      ],
    );
  }
}
