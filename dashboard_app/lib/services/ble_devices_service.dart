import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/services.dart';

import '../models/telemetry_models.dart';
import 'telemetry_engine.dart';

class BleDevicesService {
  static const _method = MethodChannel('com.dashboard.app/ble_gatt');
  static const _events = EventChannel('com.dashboard.app/ble_gatt_events');

  void Function(String source, String msg, bool isError)? onLog;
  void Function(List<DiscoveredDeviceX> devices)? onScanResults;
  void Function(ConnectionStateX state, String name)? onJkStateChanged;
  void Function(JkBmsData data)? onJkData;
  void Function(ConnectionStateX state, String name)? onEsp1StateChanged;
  void Function(Esp32Data data)? onEsp1Data;
  void Function()? onMatrixDone;
  void Function(ConnectionStateX state, String name)? onEsp2StateChanged;
  void Function(Battery2Data data)? onBattery2Data;
  void Function(ConnectionStateX state, String name)? onMotorBleStateChanged;
  void Function(String atLine)? onMotorBleLine;

  bool invertB2Current = false;

  StreamSubscription<dynamic>? _sub;
  Completer<void>? _scanCompleter;

  final List<int> _jkFrameBuf = [];
  Timer? _jkPollTimer;

  final StringBuffer _esp1Rx = StringBuffer();
  Esp32Data _esp1Cur = const Esp32Data();

  final StringBuffer _esp2Rx = StringBuffer();
  Battery2Data _b2Cur = const Battery2Data();

  final StringBuffer _motorBleRx = StringBuffer();
  Timer? _motorBlePollTimer;
  int _motorBleCycle = 0;

  void _ensureListening() {
    _sub ??= _events.receiveBroadcastStream().listen((ev) {
      if (ev is! Map) return;
      final type = ev['type']?.toString();
      switch (type) {
        case 'scan':
          final raw = (ev['devices'] as List?) ?? const [];
          final list = raw.whereType<Map>().map((m) {
            return DiscoveredDeviceX(
              name: m['name']?.toString() ?? 'BLE Device',
              address: m['address']?.toString() ?? '',
              isBle: true,
              rssi: (m['rssi'] as num?)?.toInt() ?? 0,
            );
          }).toList();
          onScanResults?.call(list);
          break;
        case 'scanDone':
          if (_scanCompleter != null && !_scanCompleter!.isCompleted) {
            _scanCompleter!.complete();
          }
          break;
        case 'log':
          onLog?.call(
            ev['source']?.toString() ?? 'BLE',
            ev['msg']?.toString() ?? '',
            ev['err'] == true,
          );
          break;
        case 'slotState':
          final slot = ev['slot']?.toString() ?? '';
          final stStr = ev['state']?.toString() ?? 'disconnected';
          final name = ev['name']?.toString() ?? '';
          final st = switch (stStr) {
            'connected' => ConnectionStateX.connected,
            'connecting' => ConnectionStateX.connecting,
            _ => ConnectionStateX.disconnected,
          };
          _handleSlotState(slot, st, name);
          break;
        case 'data':
          final slot = ev['slot']?.toString() ?? '';
          final rawBytes = ev['bytes'];
          if (rawBytes is Uint8List) {
            _handleSlotBytes(slot, rawBytes);
          } else if (rawBytes is List) {
            _handleSlotBytes(
                slot, Uint8List.fromList(rawBytes.cast<int>()));
          }
          break;
      }
    });
  }

  void _handleSlotState(String slot, ConnectionStateX st, String name) {
    switch (slot) {
      case 'jk':
        onJkStateChanged?.call(st, name);
        if (st == ConnectionStateX.connected) {
          _startJkPolling();
        } else {
          _jkPollTimer?.cancel();
          _jkPollTimer = null;
          _jkFrameBuf.clear();
        }
        break;
      case 'esp1':
        onEsp1StateChanged?.call(st, name);
        if (st == ConnectionStateX.disconnected) _esp1Rx.clear();
        break;
      case 'esp2':
        onEsp2StateChanged?.call(st, name);
        if (st == ConnectionStateX.disconnected) _esp2Rx.clear();
        break;
      case 'motor_ble':
        onMotorBleStateChanged?.call(st, name);
        if (st == ConnectionStateX.connected) {
          _startMotorBlePolling();
        } else {
          _motorBlePollTimer?.cancel();
          _motorBlePollTimer = null;
          _motorBleRx.clear();
        }
        break;
    }
  }

  void _handleSlotBytes(String slot, Uint8List bytes) {
    switch (slot) {
      case 'jk':
        _onJkBytes(bytes);
        break;
      case 'esp1':
        _handleEspChunk(1, utf8.decode(bytes, allowMalformed: true));
        break;
      case 'esp2':
        _handleEspChunk(2, utf8.decode(bytes, allowMalformed: true));
        break;
      case 'motor_ble':
        _motorBleRx.write(utf8.decode(bytes, allowMalformed: true));
        var full = _motorBleRx.toString();
        while (true) {
          final idx = full.indexOf('\n');
          if (idx < 0) break;
          final line = full.substring(0, idx).trim();
          full = full.substring(idx + 1);
          if (line.isNotEmpty) onMotorBleLine?.call(line);
        }
        _motorBleRx
          ..clear()
          ..write(full);
        break;
    }
  }

  Future<void> startScan({Duration timeout = const Duration(seconds: 7)}) async {
    _ensureListening();
    _scanCompleter = Completer<void>();
    try {
      await _method
          .invokeMethod('startScan', {'timeoutMs': timeout.inMilliseconds});
      await _scanCompleter!.future
          .timeout(timeout + const Duration(seconds: 1), onTimeout: () {});
    } catch (e) {
      onLog?.call('BLE', 'Gagal scan BLE: $e', true);
    }
  }

  Future<void> stopScan() async {
    try {
      await _method.invokeMethod('stopScan');
    } catch (_) {}
  }

  Future<void> connectJkBms(String remoteId) async {
    _ensureListening();
    await _method.invokeMethod(
        'connectSlot', {'slot': 'jk', 'address': remoteId});
  }

  Future<void> disconnectJkBms() async {
    _jkPollTimer?.cancel();
    _jkPollTimer = null;
    await _method.invokeMethod('disconnectSlot', {'slot': 'jk'});
  }

  void _startJkPolling() async {
    _jkPollTimer?.cancel();
    await _writeSlotBytes('jk', TelemetryEngine.buildJkCommandFrame(0x97));
    await Future<void>.delayed(const Duration(milliseconds: 400));
    await _writeSlotBytes('jk', TelemetryEngine.buildJkCommandFrame(0x96));
    _jkPollTimer = Timer.periodic(const Duration(milliseconds: 2500), (_) {
      _writeSlotBytes('jk', TelemetryEngine.buildJkCommandFrame(0x96));
    });
  }

  void _onJkBytes(Uint8List chunk) {
    if (chunk.length >= 4 &&
        chunk[0] == 0x55 &&
        chunk[1] == 0xAA &&
        chunk[2] == 0xEB &&
        chunk[3] == 0x90) {
      _jkFrameBuf.clear();
    }
    _jkFrameBuf.addAll(chunk);
    if (_jkFrameBuf.length >= 300) {
      final frame = Uint8List.fromList(_jkFrameBuf.sublist(0, 300));
      _jkFrameBuf.clear();
      final parsed = TelemetryEngine.parseJkBms300ByteFrame(
        frame,
        onError: (e) => onLog?.call('JKBMS', e, true),
      );
      if (parsed != null) onJkData?.call(parsed);
    }
  }

  Future<void> connectEspSlot(int slot, String remoteId) async {
    _ensureListening();
    await _method.invokeMethod(
        'connectSlot', {'slot': 'esp$slot', 'address': remoteId});
  }

  Future<void> disconnectEspSlot(int slot) async {
    await _method.invokeMethod('disconnectSlot', {'slot': 'esp$slot'});
  }

  Future<void> sendEspCommand(int slot, String cmd) async {
    final payload = utf8.encode(cmd.endsWith('\n') ? cmd : '$cmd\n');
    final ok = await _writeSlotBytes('esp$slot', payload);
    if (ok) {
      onLog?.call('ESP32#$slot', 'TX: ${cmd.trim()}', false);
    }
  }

  void _handleEspChunk(int slot, String chunk) {
    final buf = slot == 1 ? _esp1Rx : _esp2Rx;
    buf.write(chunk);
    var full = buf.toString();
    while (true) {
      final idx = full.indexOf('\n');
      if (idx < 0) break;
      final line = full.substring(0, idx).trim();
      full = full.substring(idx + 1);
      if (line.isNotEmpty) {
        if (slot == 1) {
          _parseEsp1Line(line);
        } else {
          _parseEsp2Line(line);
        }
      }
    }
    buf
      ..clear()
      ..write(full);
  }

  void _parseEsp1Line(String line) {
    onLog?.call('ESP1_RX', line, false);
    if (line.toUpperCase() == 'MATRIX_DONE') {
      _esp1Cur = _esp1Cur.copyWith(matrixBusy: false);
      onEsp1Data?.call(_esp1Cur);
      onMatrixDone?.call();
      return;
    }
    final eq = line.indexOf('=');
    if (eq <= 0) return;
    final key = line.substring(0, eq).trim().toUpperCase();
    final valStr = line.substring(eq + 1).trim();
    final numVal = double.tryParse(valStr);
    final now = DateTime.now().millisecondsSinceEpoch;

    _esp1Cur = switch (key) {
      'SPD' => _esp1Cur.copyWith(speedKmh: numVal, lastUpdatedMs: now),
      'TRT' => _esp1Cur.copyWith(
          throttlePct: numVal?.clamp(0.0, 100.0).toDouble(),
          lastUpdatedMs: now),
      'CRZ' => _esp1Cur.copyWith(
          cruiseActive: valStr == '1' || valStr.toUpperCase() == 'ON',
          lastUpdatedMs: now),
      'REM' => _esp1Cur.copyWith(
          reverseActive: valStr == '1' || valStr.toUpperCase() == 'ON',
          lastUpdatedMs: now),
      'STD' => _esp1Cur.copyWith(
          sideStandDown: valStr == '1' || valStr.toUpperCase() == 'ON',
          lastUpdatedMs: now),
      'MODE' =>
        _esp1Cur.copyWith(modeIndex: numVal?.round(), lastUpdatedMs: now),
      'PARK' => _esp1Cur.copyWith(isPark: valStr == '1', lastUpdatedMs: now),
      'CONTACT' => _esp1Cur.copyWith(
          contactOn: valStr == '1' || valStr.toUpperCase() == 'ON',
          lastUpdatedMs: now),
      'MATRIX' => _esp1Cur.copyWith(
          matrixOn: valStr == '1' || valStr.toUpperCase() == 'ON',
          lastUpdatedMs: now),
      'MUSIC_TRACK' => _esp1Cur.copyWith(
          musicTrack: numVal?.round() ?? _esp1Cur.musicTrack,
          lastUpdatedMs: now),
      'MUSIC_STATE' => _esp1Cur.copyWith(
          musicState: valStr.toUpperCase(), lastUpdatedMs: now),
      'MUSIC_VOL' => _esp1Cur.copyWith(
          musicVol: numVal?.round() ?? _esp1Cur.musicVol, lastUpdatedMs: now),
      _ => _esp1Cur,
    };
    onEsp1Data?.call(_esp1Cur);
  }

  void _parseEsp2Line(String line) {
    onLog?.call('ESP2_RX', line, false);
    final eq = line.indexOf('=');
    if (eq <= 0) return;
    final key = line.substring(0, eq).trim().toUpperCase();
    final valStr = line.substring(eq + 1).trim();
    final numVal = double.tryParse(valStr);
    final now = DateTime.now().millisecondsSinceEpoch;

    _b2Cur = switch (key) {
      'VBAT' || 'PACK_V' || 'V2' =>
        _b2Cur.copyWith(vbat: numVal, lastUpdatedMs: now),
      'IBAT' || 'PACK_I' || 'I2' => _b2Cur.copyWith(
          ibat: numVal != null ? (invertB2Current ? -numVal : numVal) : null,
          lastUpdatedMs: now,
        ),
      'PWR' || 'P2' => _b2Cur.copyWith(powerW: numVal, lastUpdatedMs: now),
      'SOC' || 'SOC2' => _b2Cur.copyWith(
          socPct: numVal?.clamp(0.0, 100.0).toDouble(), lastUpdatedMs: now),
      'SOH' || 'SOH2' => _b2Cur.copyWith(
          sohPct: numVal?.clamp(0.0, 100.0).toDouble(), lastUpdatedMs: now),
      'TBAT' || 'TEMP' || 'T2' =>
        _b2Cur.copyWith(tempC: numVal, lastUpdatedMs: now),
      'DELTA' || 'DCELL' =>
        _b2Cur.copyWith(deltaCellV: numVal, lastUpdatedMs: now),
      'CELLS' => _parseB2Cells(valStr, now),
      'CYCLES' || 'CBAT' =>
        _b2Cur.copyWith(cycles: numVal?.round(), lastUpdatedMs: now),
      'REMAIN_AH' => _b2Cur.copyWith(remainAh: numVal, lastUpdatedMs: now),
      'CANMODE' =>
        _b2Cur.copyWith(canMode: valStr.toUpperCase(), lastUpdatedMs: now),
      'SIMMODE' => _b2Cur.copyWith(simMode: valStr, lastUpdatedMs: now),
      _ => _b2Cur,
    };
    onBattery2Data?.call(_b2Cur);
  }

  Battery2Data _parseB2Cells(String valStr, int now) {
    final parsed = valStr
        .split(',')
        .map((e) => double.tryParse(e.trim()))
        .whereType<double>()
        .toList();
    double? delta = _b2Cur.deltaCellV;
    if (parsed.length >= 2) {
      final minV = parsed.reduce((a, b) => a < b ? a : b);
      final maxV = parsed.reduce((a, b) => a > b ? a : b);
      delta = maxV - minV;
    }
    return _b2Cur.copyWith(
      cellCount: parsed.length,
      cellVoltages: parsed,
      deltaCellV: delta,
      lastUpdatedMs: now,
    );
  }

  Future<void> connectMotorBle(String remoteId) async {
    _ensureListening();
    await _method.invokeMethod(
        'connectSlot', {'slot': 'motor_ble', 'address': remoteId});
  }

  void _startMotorBlePolling() {
    _motorBlePollTimer?.cancel();
    _motorBlePollTimer =
        Timer.periodic(const Duration(milliseconds: 550), (_) async {
      final cmds = [
        'AT+SPD?\r\n',
        'AT+VBAT?\r\n',
        'AT+IBAT?\r\n',
        if (_motorBleCycle % 2 == 0) 'AT+SOC?\r\n',
        if (_motorBleCycle % 4 == 0) 'AT+ODO?\r\n',
      ];
      _motorBleCycle++;
      for (final c in cmds) {
        await writeMotorBle(c);
        await Future<void>.delayed(const Duration(milliseconds: 45));
      }
    });
  }

  Future<void> writeMotorBle(String text) =>
      _writeSlotBytes('motor_ble', utf8.encode(text));

  Future<void> sendMotorBleMapsFrame(
    String b64, {
    int chunkSize = 100,
    int delayMs = 30,
  }) async {
    final int cs = chunkSize.clamp(20, 400).toInt();
    await writeMotorBle('AT+MAPS="');
    await Future<void>.delayed(const Duration(milliseconds: 45));
    for (int i = 0; i < b64.length; i += cs) {
      final end = (i + cs < b64.length) ? i + cs : b64.length;
      await writeMotorBle(b64.substring(i, end));
      if (delayMs > 0) await Future<void>.delayed(Duration(milliseconds: delayMs));
    }
    await writeMotorBle('"\r\n');
  }

  Future<void> disconnectMotorBle() async {
    _motorBlePollTimer?.cancel();
    _motorBlePollTimer = null;
    await _method.invokeMethod('disconnectSlot', {'slot': 'motor_ble'});
  }

  Future<bool> _writeSlotBytes(String slot, List<int> bytes) async {
    try {
      final ok = await _method.invokeMethod<bool>('writeSlot', {
        'slot': slot,
        'bytes': Uint8List.fromList(bytes),
      });
      return ok == true;
    } catch (_) {
      return false;
    }
  }
}
