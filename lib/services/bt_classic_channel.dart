import 'dart:async';
import 'dart:collection';

import 'package:flutter/services.dart';

import '../models/telemetry_models.dart';

class BtClassicChannelService {
  static const _method = MethodChannel('com.dashboard.app/bt_classic');
  static const _events = EventChannel('com.dashboard.app/bt_classic_events');
  static const _mirrorMethod = MethodChannel('com.dashboard.app/screen_mirror');
  static const _mirrorEvents =
      EventChannel('com.dashboard.app/screen_mirror_events');

  final Queue<String> _txQueue = Queue<String>();
  Timer? _txTimer;
  Timer? _pollTimer;
  StreamSubscription<dynamic>? _btSub;
  StreamSubscription<dynamic>? _mirrorSub;
  int _pollCycle = 0;
  bool _connected = false;
  bool _mapsSending = false;

  bool get isMapsSending => _mapsSending;

  void Function(String line)? onLineReceived;
  void Function(ConnectionStateX state, String name, String? reason)?
      onStateChanged;
  void Function(String source, String msg, bool isError)? onLog;
  void Function(String base64Jpeg)? onMirrorFrame;
  void Function(bool running)? onMirrorState;

  void init() {
    _btSub ??= _events.receiveBroadcastStream().listen((event) {
      if (event is! Map) return;
      final type = event['type']?.toString();
      if (type == 'line') {
        final line = event['line']?.toString() ?? '';
        if (line.isNotEmpty) onLineReceived?.call(line);
      } else if (type == 'state') {
        final st = event['state']?.toString();
        final name = event['name']?.toString() ?? '';
        final reason = event['reason']?.toString();
        if (st == 'connected') {
          _connected = true;
          _startTxAndPolling();
          onStateChanged?.call(ConnectionStateX.connected, name, reason);
        } else {
          _stopLoops();
          _connected = false;
          onStateChanged?.call(ConnectionStateX.disconnected, name, reason);
        }
      } else if (type == 'error') {
        onLog?.call(
            'MOTOR', event['message']?.toString() ?? 'Koneksi error', true);
      }
    }, onError: (e) {
      onLog?.call('MOTOR', 'Event stream error: $e', true);
    });

    _mirrorSub ??= _mirrorEvents.receiveBroadcastStream().listen((event) {
      if (event is! Map) return;
      final type = event['type']?.toString();
      if (type == 'frame') {
        final b64 = event['base64']?.toString() ?? '';
        if (b64.isNotEmpty) onMirrorFrame?.call(b64);
      } else if (type == 'state') {
        onMirrorState?.call(event['running'] == true);
      }
    });
  }

  Future<bool> isBluetoothEnabled() async {
    try {
      return await _method.invokeMethod<bool>('isBluetoothEnabled') ?? false;
    } catch (_) {
      return false;
    }
  }

  Future<List<DiscoveredDeviceX>> getBondedDevices() async {
    try {
      final raw = await _method.invokeMethod<List<dynamic>>('getBondedDevices');
      if (raw == null) return const [];
      return raw.whereType<Map>().map((m) {
        return DiscoveredDeviceX(
          name: m['name']?.toString() ?? 'Perangkat',
          address: m['address']?.toString() ?? '',
          isBle: m['isBle'] == true,
          isPaired: m['isPaired'] == true,
        );
      }).toList();
    } catch (e) {
      onLog?.call('MOTOR', 'Gagal baca paired devices: $e', true);
      return const [];
    }
  }

  Future<bool> connect(String address) async {
    init();
    onStateChanged?.call(ConnectionStateX.connecting, address, null);
    try {
      final ok =
          await _method.invokeMethod<bool>('connect', {'address': address});
      return ok == true;
    } catch (e) {
      onStateChanged?.call(ConnectionStateX.disconnected, '', '$e');
      onLog?.call('MOTOR', 'Gagal koneksi SPP ke $address: $e', true);
      return false;
    }
  }

  Future<void> disconnect() async {
    _stopLoops();
    _connected = false;
    try {
      await _method.invokeMethod('disconnect');
    } catch (_) {}
    onStateChanged?.call(ConnectionStateX.disconnected, '', 'manual');
  }

  void sendCommand(String cmd) {
    if (!_connected) return;
    final norm = cmd.endsWith('\r\n') ? cmd : '$cmd\r\n';
    if (_txQueue.length < 40) {
      _txQueue.add(norm);
    }
  }

  void sendTripRange(double rangeKm) {
    sendCommand('AT+TRIP="${rangeKm.toStringAsFixed(1)}"');
  }

  void sendNavText(String rawText) {
    final ascii = rawText
        .replaceAll(RegExp(r'[^\x20-\x7E]'), ' ')
        .trim();
    final clipped = ascii.length > 20 ? ascii.substring(0, 20) : ascii;
    final padded = clipped.padRight(20, ' ');
    sendCommand('AT+NAV="$padded"');
  }

  void restoreOdo(double odoKm) {
    sendCommand('AT+ODO="${odoKm.round()}"');
  }

  Future<int?> sendMapsFrame(String base64Jpeg) async {
    if (!_connected || _mapsSending || base64Jpeg.isEmpty) return null;
    _mapsSending = true;
    try {
      final ms = await _method.invokeMethod<int>('write', {
        'text': 'AT+MAPS="$base64Jpeg"\r\n',
      });
      return ms;
    } catch (e) {
      onLog?.call('MOTOR', 'Gagal kirim AT+MAPS: $e', true);
      return null;
    } finally {
      _mapsSending = false;
    }
  }

  Future<bool> startScreenMirror({
    required int width,
    required int height,
    int quality = 55,
    int intervalMs = 500,
  }) async {
    try {
      final ok = await _mirrorMethod.invokeMethod<bool>('startMirror', {
        'width': width,
        'height': height,
        'quality': quality,
        'intervalMs': intervalMs,
      });
      return ok == true;
    } catch (e) {
      onLog?.call('MIRROR', 'Gagal memulai Screen Mirror: $e', true);
      return false;
    }
  }

  Future<void> stopScreenMirror() async {
    try {
      await _mirrorMethod.invokeMethod('stopMirror');
    } catch (_) {}
  }

  void _startTxAndPolling() {
    _stopLoops();
    final now = DateTime.now();
    final hh = now.hour.toString().padLeft(2, '0');
    final mm = now.minute.toString().padLeft(2, '0');
    sendCommand('AT+TIME="$hh$mm"');

    _txTimer = Timer.periodic(const Duration(milliseconds: 65), (_) async {
      if (!_connected || _txQueue.isEmpty) return;
      final cmd = _txQueue.removeFirst();
      try {
        await _method.invokeMethod('write', {'text': cmd});
      } catch (_) {}
    });

    _pollTimer = Timer.periodic(const Duration(milliseconds: 500), (_) {
      if (!_connected) return;
      _enqueueIfMissing('AT+SPD?\r\n');
      _enqueueIfMissing('AT+IBAT?\r\n');
      _enqueueIfMissing('AT+VBAT?\r\n');
      _enqueueIfMissing('AT+TRT?\r\n');
      if (_pollCycle % 2 == 0) {
        _enqueueIfMissing('AT+SOC?\r\n');
        _enqueueIfMissing('AT+ERR?\r\n');
        _enqueueIfMissing('AT+MODE?\r\n');
        _enqueueIfMissing('AT+PARK?\r\n');
      }
      if (_pollCycle % 6 == 0) {
        _enqueueIfMissing('AT+CBAT?\r\n');
        _enqueueIfMissing('AT+ODO?\r\n');
        _enqueueIfMissing('AT+TINV?\r\n');
        _enqueueIfMissing('AT+TMOT?\r\n');
        _enqueueIfMissing('AT+TBAT?\r\n');
        _enqueueIfMissing('AT+SOH?\r\n');
      }
      _pollCycle++;
    });
  }

  void _enqueueIfMissing(String cmd) {
    if (_txQueue.length < 32 && !_txQueue.contains(cmd)) {
      _txQueue.add(cmd);
    }
  }

  void _stopLoops() {
    _txTimer?.cancel();
    _pollTimer?.cancel();
    _txTimer = null;
    _pollTimer = null;
    _txQueue.clear();
  }

  void dispose() {
    _stopLoops();
    _btSub?.cancel();
    _mirrorSub?.cancel();
  }
}
