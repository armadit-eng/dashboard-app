import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter_blue_plus/flutter_blue_plus.dart';

import '../models/telemetry_models.dart';
import 'telemetry_engine.dart';

class BleDevicesService {
  static final Guid jkServiceUuid = Guid('0000ffe0-0000-1000-8000-00805f9b34fb');
  static final Guid jkNotifyUuid = Guid('0000ffe1-0000-1000-8000-00805f9b34fb');
  static final Guid jkWriteUuid = Guid('0000fff2-0000-1000-8000-00805f9b34fb');

  static final Guid nusServiceUuid =
      Guid('6e400001-b5a3-f393-e0a9-e50e24dcca9e');
  static final Guid nusWriteUuid = Guid('6e400002-b5a3-f393-e0a9-e50e24dcca9e');
  static final Guid nusNotifyUuid =
      Guid('6e400003-b5a3-f393-e0a9-e50e24dcca9e');

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

  StreamSubscription<List<ScanResult>>? _scanSub;
  final Map<String, DiscoveredDeviceX> _found = {};

  BluetoothDevice? _jkDev;
  BluetoothCharacteristic? _jkWriteChar;
  StreamSubscription<BluetoothConnectionState>? _jkConnSub;
  StreamSubscription<List<int>>? _jkNotifySub;
  Timer? _jkPollTimer;
  final List<int> _jkFrameBuf = [];

  BluetoothDevice? _esp1Dev;
  BluetoothCharacteristic? _esp1WriteChar;
  StreamSubscription<BluetoothConnectionState>? _esp1ConnSub;
  StreamSubscription<List<int>>? _esp1NotifySub;
  final StringBuffer _esp1Rx = StringBuffer();
  Esp32Data _esp1Cur = const Esp32Data();

  BluetoothDevice? _esp2Dev;
  BluetoothCharacteristic? _esp2WriteChar;
  StreamSubscription<BluetoothConnectionState>? _esp2ConnSub;
  StreamSubscription<List<int>>? _esp2NotifySub;
  final StringBuffer _esp2Rx = StringBuffer();
  Battery2Data _b2Cur = const Battery2Data();

  BluetoothDevice? _motorBleDev;
  BluetoothCharacteristic? _motorBleWriteChar;
  StreamSubscription<BluetoothConnectionState>? _motorBleConnSub;
  StreamSubscription<List<int>>? _motorBleNotifySub;
  final StringBuffer _motorBleRx = StringBuffer();
  Timer? _motorBlePollTimer;
  int _motorBleCycle = 0;

  Future<void> startScan({Duration timeout = const Duration(seconds: 7)}) async {
    _found.clear();
    await stopScan();
    onLog?.call('BLE', 'Memulai pemindaian BLE…', false);
    _scanSub = FlutterBluePlus.scanResults.listen((results) {
      for (final r in results) {
        final id = r.device.remoteId.str;
        final name = r.device.platformName.isNotEmpty
            ? r.device.platformName
            : (r.advertisementData.advName.isNotEmpty
                ? r.advertisementData.advName
                : 'BLE Device');
        _found[id] = DiscoveredDeviceX(
          name: name,
          address: id,
          isBle: true,
          rssi: r.rssi,
        );
      }
      final sorted = _found.values.toList()
        ..sort((a, b) => b.rssi.compareTo(a.rssi));
      onScanResults?.call(sorted);
    });

    try {
      await FlutterBluePlus.startScan(timeout: timeout);
    } catch (e) {
      onLog?.call('BLE', 'Gagal scan BLE: $e', true);
    }
  }

  Future<void> stopScan() async {
    await _scanSub?.cancel();
    _scanSub = null;
    try {
      await FlutterBluePlus.stopScan();
    } catch (_) {}
  }

  Future<void> connectJkBms(String remoteId) async {
    await disconnectJkBms();
    final dev = BluetoothDevice.fromId(remoteId);
    _jkDev = dev;
    onJkStateChanged?.call(ConnectionStateX.connecting, remoteId);
    onLog?.call('JKBMS', 'Menghubungkan JK-BMS $remoteId…', false);

    try {
      await dev.connect(timeout: const Duration(seconds: 12), autoConnect: false);
      _jkConnSub = dev.connectionState.listen((s) {
        if (s == BluetoothConnectionState.disconnected) {
          _cleanupJk();
          onJkStateChanged?.call(ConnectionStateX.disconnected, '');
          onLog?.call('JKBMS', 'JK-BMS terputus.', true);
        }
      });

      final services = await dev.discoverServices();
      BluetoothService? target;
      for (final s in services) {
        if (s.uuid == jkServiceUuid) {
          target = s;
          break;
        }
      }
      if (target == null) {
        onLog?.call('JKBMS', 'Layanan FFE0 JK-BMS tidak ditemukan.', true);
        await disconnectJkBms();
        return;
      }

      BluetoothCharacteristic? notifyChar;
      for (final c in target.characteristics) {
        if (c.uuid == jkNotifyUuid) notifyChar = c;
        if (c.uuid == jkWriteUuid) _jkWriteChar = c;
      }
      _jkWriteChar ??= notifyChar;

      if (notifyChar != null) {
        await notifyChar.setNotifyValue(true);
        _jkNotifySub = notifyChar.onValueReceived.listen(_onJkBytes);
      }

      final name = dev.platformName.isNotEmpty ? dev.platformName : remoteId;
      onJkStateChanged?.call(ConnectionStateX.connected, name);
      onLog?.call('JKBMS', 'Terhubung ke JK-BMS ($name)', false);

      // Handshake 0x97 lalu polling 0x96 setiap 2.5 detik
      await _writeJkReg(0x97);
      await Future<void>.delayed(const Duration(milliseconds: 400));
      await _writeJkReg(0x96);
      _jkPollTimer = Timer.periodic(const Duration(milliseconds: 2500), (_) {
        _writeJkReg(0x96);
      });
    } catch (e) {
      onLog?.call('JKBMS', 'Gagal koneksi JK-BMS: $e', true);
      await disconnectJkBms();
    }
  }

  Future<void> _writeJkReg(int reg) async {
    final ch = _jkWriteChar;
    if (ch == null) return;
    try {
      await ch.write(
        TelemetryEngine.buildJkCommandFrame(reg),
        withoutResponse: ch.properties.writeWithoutResponse,
      );
    } catch (_) {}
  }

  void _onJkBytes(List<int> chunk) {
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

  Future<void> disconnectJkBms() async {
    _cleanupJk();
    try {
      await _jkDev?.disconnect();
    } catch (_) {}
    _jkDev = null;
    onJkStateChanged?.call(ConnectionStateX.disconnected, '');
  }

  void _cleanupJk() {
    _jkPollTimer?.cancel();
    _jkPollTimer = null;
    _jkNotifySub?.cancel();
    _jkNotifySub = null;
    _jkConnSub?.cancel();
    _jkConnSub = null;
    _jkWriteChar = null;
    _jkFrameBuf.clear();
  }

  Future<void> connectEspSlot(int slot, String remoteId) async {
    await disconnectEspSlot(slot);
    final dev = BluetoothDevice.fromId(remoteId);
    if (slot == 1) {
      _esp1Dev = dev;
      onEsp1StateChanged?.call(ConnectionStateX.connecting, remoteId);
    } else {
      _esp2Dev = dev;
      onEsp2StateChanged?.call(ConnectionStateX.connecting, remoteId);
    }
    onLog?.call('ESP32#$slot', 'Menghubungkan $remoteId…', false);

    try {
      await dev.connect(timeout: const Duration(seconds: 12), autoConnect: false);
      final connSub = dev.connectionState.listen((s) {
        if (s == BluetoothConnectionState.disconnected) {
          disconnectEspSlot(slot);
          onLog?.call('ESP32#$slot', 'ESP32 #$slot terputus.', true);
        }
      });
      if (slot == 1) {
        _esp1ConnSub = connSub;
      } else {
        _esp2ConnSub = connSub;
      }

      final services = await dev.discoverServices();
      BluetoothService? nus;
      for (final s in services) {
        if (s.uuid == nusServiceUuid) {
          nus = s;
          break;
        }
      }
      if (nus == null) {
        onLog?.call('ESP32#$slot', 'Nordic UART Service tidak ditemukan.', true);
        await disconnectEspSlot(slot);
        return;
      }

      BluetoothCharacteristic? notifyCh;
      BluetoothCharacteristic? writeCh;
      for (final c in nus.characteristics) {
        if (c.uuid == nusNotifyUuid) notifyCh = c;
        if (c.uuid == nusWriteUuid) writeCh = c;
      }
      if (slot == 1) {
        _esp1WriteChar = writeCh;
      } else {
        _esp2WriteChar = writeCh;
      }

      if (notifyCh != null) {
        await notifyCh.setNotifyValue(true);
        final sub = notifyCh.onValueReceived.listen((bytes) {
          _handleEspChunk(slot, utf8.decode(bytes, allowMalformed: true));
        });
        if (slot == 1) {
          _esp1NotifySub = sub;
        } else {
          _esp2NotifySub = sub;
        }
      }

      final name = dev.platformName.isNotEmpty ? dev.platformName : remoteId;
      if (slot == 1) {
        onEsp1StateChanged?.call(ConnectionStateX.connected, name);
      } else {
        onEsp2StateChanged?.call(ConnectionStateX.connected, name);
      }
      onLog?.call('ESP32#$slot', 'Terhubung ke ESP32 #$slot ($name)', false);
    } catch (e) {
      onLog?.call('ESP32#$slot', 'Gagal koneksi: $e', true);
      await disconnectEspSlot(slot);
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
          throttlePct: numVal?.clamp(0.0, 100.0), lastUpdatedMs: now),
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
          socPct: numVal?.clamp(0.0, 100.0), lastUpdatedMs: now),
      'SOH' || 'SOH2' => _b2Cur.copyWith(
          sohPct: numVal?.clamp(0.0, 100.0), lastUpdatedMs: now),
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

  Future<void> sendEspCommand(int slot, String cmd) async {
    final ch = slot == 1 ? _esp1WriteChar : _esp2WriteChar;
    if (ch == null) {
      onLog?.call('ESP32#$slot', 'Perangkat belum terhubung.', true);
      return;
    }
    final payload = utf8.encode(cmd.endsWith('\n') ? cmd : '$cmd\n');
    try {
      await ch.write(payload, withoutResponse: ch.properties.writeWithoutResponse);
      onLog?.call('ESP32#$slot', 'TX: ${cmd.trim()}', false);
    } catch (e) {
      onLog?.call('ESP32#$slot', 'Gagal kirim: $e', true);
    }
  }

  Future<void> disconnectEspSlot(int slot) async {
    if (slot == 1) {
      await _esp1NotifySub?.cancel();
      await _esp1ConnSub?.cancel();
      _esp1NotifySub = null;
      _esp1ConnSub = null;
      _esp1WriteChar = null;
      _esp1Rx.clear();
      try {
        await _esp1Dev?.disconnect();
      } catch (_) {}
      _esp1Dev = null;
      onEsp1StateChanged?.call(ConnectionStateX.disconnected, '');
    } else {
      await _esp2NotifySub?.cancel();
      await _esp2ConnSub?.cancel();
      _esp2NotifySub = null;
      _esp2ConnSub = null;
      _esp2WriteChar = null;
      _esp2Rx.clear();
      try {
        await _esp2Dev?.disconnect();
      } catch (_) {}
      _esp2Dev = null;
      onEsp2StateChanged?.call(ConnectionStateX.disconnected, '');
    }
  }

  Future<void> connectMotorBle(String remoteId) async {
    await disconnectMotorBle();
    final dev = BluetoothDevice.fromId(remoteId);
    _motorBleDev = dev;
    onMotorBleStateChanged?.call(ConnectionStateX.connecting, remoteId);
    try {
      await dev.connect(timeout: const Duration(seconds: 12), autoConnect: false);
      _motorBleConnSub = dev.connectionState.listen((s) {
        if (s == BluetoothConnectionState.disconnected) {
          disconnectMotorBle();
        }
      });

      final services = await dev.discoverServices();
      BluetoothCharacteristic? notifyCh;
      BluetoothCharacteristic? writeCh;
      for (final s in services) {
        if (s.uuid == jkServiceUuid ||
            s.characteristics.any((c) => c.properties.notify)) {
          for (final c in s.characteristics) {
            if (c.uuid == jkNotifyUuid ||
                (notifyCh == null && c.properties.notify)) {
              notifyCh = c;
            }
            if (c.uuid == jkWriteUuid ||
                (writeCh == null &&
                    (c.properties.write || c.properties.writeWithoutResponse))) {
              writeCh = c;
            }
          }
          if (notifyCh != null) break;
        }
      }
      _motorBleWriteChar = writeCh ?? notifyCh;
      if (notifyCh != null) {
        await notifyCh.setNotifyValue(true);
        _motorBleNotifySub = notifyCh.onValueReceived.listen((bytes) {
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
        });
      }

      final name = dev.platformName.isNotEmpty ? dev.platformName : remoteId;
      onMotorBleStateChanged?.call(ConnectionStateX.connected, name);
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
    } catch (e) {
      onLog?.call('MOTOR_BLE', 'Gagal BLE Motor: $e', true);
      await disconnectMotorBle();
    }
  }

  Future<void> writeMotorBle(String text) async {
    final ch = _motorBleWriteChar;
    if (ch == null) return;
    try {
      await ch.write(
        utf8.encode(text),
        withoutResponse: ch.properties.writeWithoutResponse,
      );
    } catch (_) {}
  }

  Future<void> sendMotorBleMapsFrame(
    String b64, {
    int chunkSize = 100,
    int delayMs = 30,
  }) async {
    final ch = _motorBleWriteChar;
    if (ch == null) return;
    final cs = chunkSize.clamp(20, 400);
    await ch.write(utf8.encode('AT+MAPS="'), withoutResponse: true);
    await Future<void>.delayed(const Duration(milliseconds: 45));
    for (int i = 0; i < b64.length; i += cs) {
      final end = (i + cs < b64.length) ? i + cs : b64.length;
      await ch.write(utf8.encode(b64.substring(i, end)), withoutResponse: true);
      if (delayMs > 0) await Future<void>.delayed(Duration(milliseconds: delayMs));
    }
    await ch.write(utf8.encode('"\r\n'), withoutResponse: true);
  }

  Future<void> disconnectMotorBle() async {
    _motorBlePollTimer?.cancel();
    _motorBlePollTimer = null;
    await _motorBleNotifySub?.cancel();
    await _motorBleConnSub?.cancel();
    _motorBleNotifySub = null;
    _motorBleConnSub = null;
    _motorBleWriteChar = null;
    _motorBleRx.clear();
    try {
      await _motorBleDev?.disconnect();
    } catch (_) {}
    _motorBleDev = null;
    onMotorBleStateChanged?.call(ConnectionStateX.disconnected, '');
  }
}
