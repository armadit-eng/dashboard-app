import 'dart:async';

import 'package:flutter/services.dart';

class NativeGpsFix {
  final double lat;
  final double lng;
  final double? altM;
  final double speedMs;
  final double? bearingDeg;
  final double? accuracyM;

  const NativeGpsFix({
    required this.lat,
    required this.lng,
    this.altM,
    this.speedMs = 0.0,
    this.bearingDeg,
    this.accuracyM,
  });
}

class PlatformToolsChannel {
  static const _method = MethodChannel('com.dashboard.app/platform_tools');
  static const _gpsEvents = EventChannel('com.dashboard.app/gps_events');

  static Stream<NativeGpsFix>? _gpsStream;

  static Future<String?> prefsGet(String key) async {
    try {
      return await _method.invokeMethod<String>('prefsGet', {'key': key});
    } catch (_) {
      return null;
    }
  }

  static Future<void> prefsSet(String key, String value) async {
    try {
      await _method.invokeMethod('prefsSet', {'key': key, 'value': value});
    } catch (_) {}
  }

  static Future<String> getDocsDir() async {
    try {
      return await _method.invokeMethod<String>('getDocsDir') ?? '';
    } catch (_) {
      return '';
    }
  }

  static Future<void>? _permFuture;

  /// Satu permintaan izin pada satu waktu (pemanggil lain ikut menunggu).
  static Future<void> requestPermissions() {
    return _permFuture ??= () async {
      try {
        await _method.invokeMethod('requestPermissions');
      } catch (_) {}
    }()
        .whenComplete(() => _permFuture = null);
  }

  static Future<String> gpsDiag() async {
    try {
      return await _method.invokeMethod<String>('gpsDiag') ?? '-';
    } catch (e) {
      return 'Diagnosis gagal: $e';
    }
  }

  static Stream<NativeGpsFix> gpsStream() {
    _gpsStream ??= _gpsEvents.receiveBroadcastStream().map((ev) {
      final m = Map<String, dynamic>.from(ev as Map);
      return NativeGpsFix(
        lat: (m['lat'] as num).toDouble(),
        lng: (m['lng'] as num).toDouble(),
        altM: (m['alt'] as num?)?.toDouble(),
        speedMs: (m['speedMs'] as num?)?.toDouble() ?? 0.0,
        bearingDeg: (m['bearing'] as num?)?.toDouble(),
        accuracyM: (m['accuracy'] as num?)?.toDouble(),
      );
    }).asBroadcastStream();
    return _gpsStream!;
  }

  static Future<void> startGps() async {
    try {
      await _method.invokeMethod('startGps');
    } catch (_) {}
  }

  static Future<void> speakTts(String text) async {
    try {
      await _method.invokeMethod('speakTts', {'text': text});
    } catch (_) {}
  }

  static Future<void> setWakelock(bool enabled) async {
    try {
      await _method.invokeMethod('setWakelock', {'enabled': enabled});
    } catch (_) {}
  }

  static Future<void> shareText(String text, {String subject = 'Dashboard App'}) async {
    try {
      await _method.invokeMethod('shareText', {'text': text, 'subject': subject});
    } catch (_) {}
  }

  static Future<String?> pickImage() async {
    try {
      return await _method.invokeMethod<String>('pickImage');
    } catch (_) {
      return null;
    }
  }
}
