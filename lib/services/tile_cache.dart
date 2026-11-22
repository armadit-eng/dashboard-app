import 'dart:async';
import 'dart:io';
import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/painting.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:http/http.dart' as http;

import 'platform_tools_channel.dart';

/// Cache tile raster di disk + unduh area untuk dipakai offline.
class TileCacheService {
  /// Jika true, tile hanya diambil dari cache (tidak memakai internet).
  static bool offlineOnly = false;

  /// Batas keamanan jumlah tile dalam sekali unduh area.
  static const int maxTilesPerDownload = 3500;

  static String? _root;
  static final http.Client _client = http.Client();

  // PNG transparan 1x1, dipakai bila tile tidak ada di cache saat offline.
  static final Uint8List blankTile = Uint8List.fromList(const [
    137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1,
    0, 0, 0, 1, 8, 6, 0, 0, 0, 31, 21, 196, 137, 0, 0, 0, 10, 73, 68, 65, 84,
    120, 156, 99, 0, 1, 0, 0, 5, 0, 1, 13, 10, 45, 180, 0, 0, 0, 0, 73, 69, 78,
    68, 174, 66, 96, 130,
  ]);

  static Future<String> root() async {
    if (_root != null) return _root!;
    var base = await PlatformToolsChannel.getDocsDir();
    if (base.isEmpty) base = Directory.systemTemp.path;
    _root = '$base/tile_cache';
    return _root!;
  }

  static Future<File> _file(String styleId, int z, int x, int y) async {
    final r = await root();
    return File('$r/$styleId/$z/$x/$y.tile');
  }

  /// Ambil bytes tile: cache dulu, lalu jaringan (kecuali mode offline).
  static Future<Uint8List?> getTileBytes(
      String styleId, String url, int z, int x, int y) async {
    final f = await _file(styleId, z, x, y);
    try {
      if (await f.exists()) {
        final b = await f.readAsBytes();
        if (b.isNotEmpty) return b;
      }
    } catch (_) {}
    if (offlineOnly) return null;
    final bytes = await _fetch(url);
    if (bytes != null) unawaited(_save(f, bytes));
    return bytes;
  }

  static Future<Uint8List?> _fetch(String url) async {
    try {
      final res = await _client.get(Uri.parse(url), headers: const {
        'User-Agent': 'com.dashboard.app.dashboard_app',
      }).timeout(const Duration(seconds: 12));
      if (res.statusCode == 200 && res.bodyBytes.isNotEmpty) {
        return res.bodyBytes;
      }
    } catch (_) {}
    return null;
  }

  static Future<void> _save(File f, Uint8List bytes) async {
    try {
      await f.parent.create(recursive: true);
      await f.writeAsBytes(bytes);
    } catch (_) {}
  }

  // ---- Matematika tile (Web Mercator) ----
  static int _tx(double lng, int z) =>
      (((lng + 180.0) / 360.0) * (1 << z)).floor().clamp(0, (1 << z) - 1).toInt();

  static int _ty(double lat, int z) {
    final l = lat.clamp(-85.0, 85.0).toDouble() * math.pi / 180.0;
    final v = (1 - math.log(math.tan(l) + 1 / math.cos(l)) / math.pi) / 2;
    return (v * (1 << z)).floor().clamp(0, (1 << z) - 1).toInt();
  }

  /// Daftar tile (z, x, y) untuk area persegi radius [radiusKm] dari pusat.
  static List<(int, int, int)> tilesFor(
      double lat, double lng, double radiusKm, int minZ, int maxZ) {
    final dLat = radiusKm / 111.32;
    final cosLat = math.cos(lat * math.pi / 180.0).abs().clamp(0.2, 1.0).toDouble();
    final dLng = radiusKm / (111.32 * cosLat);
    final out = <(int, int, int)>[];
    for (var z = minZ; z <= maxZ; z++) {
      final x0 = _tx(lng - dLng, z);
      final x1 = _tx(lng + dLng, z);
      final y0 = _ty(lat + dLat, z);
      final y1 = _ty(lat - dLat, z);
      for (var x = math.min(x0, x1); x <= math.max(x0, x1); x++) {
        for (var y = math.min(y0, y1); y <= math.max(y0, y1); y++) {
          out.add((z, x, y));
        }
      }
    }
    return out;
  }

  /// Unduh area. Mengembalikan jumlah tile yang berhasil tersedia di cache.
  /// Melempar StateError bila jumlah tile melebihi batas.
  static Future<int> downloadArea({
    required String styleId,
    required String urlTemplate,
    required double lat,
    required double lng,
    required double radiusKm,
    int minZoom = 10,
    int maxZoom = 16,
    required void Function(int done, int total) onProgress,
    required bool Function() isCancelled,
  }) async {
    final tiles = tilesFor(lat, lng, radiusKm, minZoom, maxZoom);
    if (tiles.length > maxTilesPerDownload) {
      throw StateError(
          'Area terlalu besar (${tiles.length} tile, batas $maxTilesPerDownload).');
    }
    var done = 0;
    var ok = 0;
    onProgress(0, tiles.length);
    const batch = 4;
    for (var i = 0; i < tiles.length; i += batch) {
      if (isCancelled()) break;
      final slice = tiles.skip(i).take(batch).toList();
      final results = await Future.wait(slice.map((t) async {
        final (z, x, y) = t;
        final f = await _file(styleId, z, x, y);
        try {
          if (await f.exists() && await f.length() > 0) return true;
        } catch (_) {}
        final url = urlTemplate
            .replaceAll('{z}', '$z')
            .replaceAll('{x}', '$x')
            .replaceAll('{y}', '$y');
        final bytes = await _fetch(url);
        if (bytes == null) return false;
        await _save(f, bytes);
        return true;
      }));
      ok += results.where((e) => e).length;
      done += slice.length;
      onProgress(done, tiles.length);
      await Future<void>.delayed(const Duration(milliseconds: 80));
    }
    return ok;
  }

  static Future<({int files, int bytes})> stats() async {
    final d = Directory(await root());
    if (!await d.exists()) return (files: 0, bytes: 0);
    var n = 0;
    var b = 0;
    try {
      await for (final e in d.list(recursive: true, followLinks: false)) {
        if (e is File) {
          n++;
          b += await e.length();
        }
      }
    } catch (_) {}
    return (files: n, bytes: b);
  }

  static Future<void> clear() async {
    final d = Directory(await root());
    try {
      if (await d.exists()) await d.delete(recursive: true);
    } catch (_) {}
  }
}

/// TileProvider flutter_map yang membaca/menulis cache disk.
class CachedTileProvider extends TileProvider {
  CachedTileProvider({required this.styleId});

  final String styleId;

  @override
  ImageProvider getImage(TileCoordinates coordinates, TileLayer options) {
    return _CachedTileImage(
      styleId,
      getTileUrl(coordinates, options),
      coordinates.z,
      coordinates.x,
      coordinates.y,
    );
  }
}

class _CachedTileImage extends ImageProvider<_CachedTileImage> {
  const _CachedTileImage(this.styleId, this.url, this.z, this.x, this.y);

  final String styleId;
  final String url;
  final int z;
  final int x;
  final int y;

  @override
  Future<_CachedTileImage> obtainKey(ImageConfiguration configuration) =>
      SynchronousFuture<_CachedTileImage>(this);

  @override
  ImageStreamCompleter loadImage(
      _CachedTileImage key, ImageDecoderCallback decode) {
    return MultiFrameImageStreamCompleter(
      codec: _load(key, decode),
      scale: 1.0,
    );
  }

  Future<ui.Codec> _load(
      _CachedTileImage key, ImageDecoderCallback decode) async {
    final bytes = await TileCacheService.getTileBytes(
            key.styleId, key.url, key.z, key.x, key.y) ??
        TileCacheService.blankTile;
    final buffer = await ui.ImmutableBuffer.fromUint8List(bytes);
    return decode(buffer);
  }

  @override
  bool operator ==(Object other) =>
      other is _CachedTileImage &&
      other.styleId == styleId &&
      other.url == url;

  @override
  int get hashCode => Object.hash(styleId, url);
}
