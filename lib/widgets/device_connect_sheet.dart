import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/telemetry_models.dart';
import '../state/dashboard_controller.dart';
import '../theme/app_theme.dart';

class DeviceConnectSheet extends StatefulWidget {
  const DeviceConnectSheet({super.key});

  @override
  State<DeviceConnectSheet> createState() => _DeviceConnectSheetState();
}

class _DeviceConnectSheetState extends State<DeviceConnectSheet> {
  int _selectedSlot = 0; // 0=Motor, 1=JK-BMS, 2=ESP32#1, 3=ESP32#2
  bool _preferBleMotor = false;
  bool _showSerialLog = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      context.read<DashboardController>().loadBondedAndScanBle();
    });
  }

  @override
  Widget build(BuildContext context) {
    final ctrl = context.watch<DashboardController>();
    final c = context.cockpit;
    final devices = _selectedSlot == 0
        ? [
            ...ctrl.pairedBtDevices,
            ...ctrl.scannedBleDevices.where(
              (b) => !ctrl.pairedBtDevices.any((p) => p.address == b.address),
            ),
          ]
        : ctrl.scannedBleDevices;

    return DraggableScrollableSheet(
      initialChildSize: 0.85,
      maxChildSize: 0.95,
      minChildSize: 0.5,
      expand: false,
      builder: (context, scrollCtrl) {
        return Container(
          decoration: BoxDecoration(
            color: c.surfaceCard,
            borderRadius: const BorderRadius.vertical(top: Radius.circular(28)),
          ),
          child: Column(
            children: [
              const SizedBox(height: 10),
              Container(
                width: 44,
                height: 4,
                decoration: BoxDecoration(
                  color: c.borderSubtle,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 14, 12, 8),
                child: Row(
                  children: [
                    Icon(Icons.bluetooth_searching_rounded, color: c.accent),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Text(
                        'Koneksi Perangkat EV',
                        style: Theme.of(context).textTheme.headlineSmall,
                      ),
                    ),
                    IconButton(
                      tooltip: 'Serial Monitor',
                      onPressed: () =>
                          setState(() => _showSerialLog = !_showSerialLog),
                      icon: Icon(
                        _showSerialLog
                            ? Icons.terminal_rounded
                            : Icons.list_alt_rounded,
                        color: _showSerialLog ? c.accent : c.textMuted,
                      ),
                    ),
                    IconButton(
                      tooltip: 'Pindai ulang',
                      onPressed: ctrl.isScanningBle
                          ? null
                          : () => ctrl.loadBondedAndScanBle(),
                      icon: ctrl.isScanningBle
                          ? SizedBox(
                              width: 18,
                              height: 18,
                              child: CircularProgressIndicator(
                                strokeWidth: 2,
                                color: c.accent,
                              ),
                            )
                          : const Icon(Icons.refresh_rounded),
                    ),
                  ],
                ),
              ),
              // Active Connection Strip
              SizedBox(
                height: 68,
                child: ListView(
                  scrollDirection: Axis.horizontal,
                  padding: const EdgeInsets.symmetric(horizontal: 16),
                  children: [
                    _slotPill(
                      idx: 0,
                      label: 'Motor MID',
                      state: ctrl.motorConnState,
                      sub: ctrl.motorConnName.isNotEmpty
                          ? ctrl.motorConnName
                          : 'SPP / BLE',
                      onDisconnect: () => ctrl.disconnectMotor(),
                    ),
                    const SizedBox(width: 8),
                    _slotPill(
                      idx: 1,
                      label: 'JK-BMS',
                      state: ctrl.jkConnState,
                      sub: ctrl.jkConnName.isNotEmpty
                          ? ctrl.jkConnName
                          : 'Baterai 1 BLE',
                      onDisconnect: () => ctrl.disconnectJkBms(),
                    ),
                    const SizedBox(width: 8),
                    _slotPill(
                      idx: 2,
                      label: 'ESP32 #1',
                      state: ctrl.esp1ConnState,
                      sub: ctrl.esp1ConnName.isNotEmpty
                          ? ctrl.esp1ConnName
                          : 'Kontak/Matrix',
                      onDisconnect: () => ctrl.disconnectEspSlot(1),
                    ),
                    const SizedBox(width: 8),
                    _slotPill(
                      idx: 3,
                      label: 'ESP32 #2',
                      state: ctrl.esp2ConnState,
                      sub: ctrl.esp2ConnName.isNotEmpty
                          ? ctrl.esp2ConnName
                          : 'Baterai 2 CAN',
                      onDisconnect: () => ctrl.disconnectEspSlot(2),
                    ),
                  ],
                ),
              ),
              if (_selectedSlot == 0)
                Padding(
                  padding: const EdgeInsets.fromLTRB(20, 8, 20, 4),
                  child: Row(
                    children: [
                      ChoiceChip(
                        label: const Text('Classic SPP'),
                        selected: !_preferBleMotor,
                        onSelected: (_) =>
                            setState(() => _preferBleMotor = false),
                      ),
                      const SizedBox(width: 8),
                      ChoiceChip(
                        label: const Text('BLE GATT'),
                        selected: _preferBleMotor,
                        onSelected: (_) =>
                            setState(() => _preferBleMotor = true),
                      ),
                      const Spacer(),
                      DropdownButton<BatterySourcePriority>(
                        value: ctrl.settings.batteryPriority,
                        underline: const SizedBox.shrink(),
                        items: BatterySourcePriority.values
                            .map((p) => DropdownMenuItem(
                                  value: p,
                                  child: Text('Prioritas: ${p.label}'),
                                ))
                            .toList(),
                        onChanged: (val) {
                          if (val != null) {
                            ctrl.updateSettings(
                                ctrl.settings.copyWith(batteryPriority: val));
                          }
                        },
                      ),
                    ],
                  ),
                ),
              const Divider(height: 1),
              Expanded(
                child: _showSerialLog
                    ? ListView.builder(
                        controller: scrollCtrl,
                        padding: const EdgeInsets.all(14),
                        itemCount: ctrl.serialLogs.length,
                        itemBuilder: (context, i) {
                          final entry = ctrl.serialLogs.elementAt(i);
                          return Padding(
                            padding: const EdgeInsets.symmetric(vertical: 2),
                            child: Text(
                              '[${entry.source}] ${entry.message}',
                              style: TextStyle(
                                fontFamily: 'monospace',
                                fontSize: 11.5,
                                color: entry.isError ? c.danger : c.accent,
                              ),
                            ),
                          );
                        },
                      )
                    : devices.isEmpty
                        ? Center(
                            child: Column(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Icon(Icons.bluetooth_disabled_rounded,
                                    size: 40, color: c.textMuted),
                                const SizedBox(height: 10),
                                Text(
                                  ctrl.isScanningBle
                                      ? 'Memindai perangkat di sekitar…'
                                      : 'Belum ada perangkat ditemukan.\nPastikan Bluetooth & Lokasi aktif.',
                                  textAlign: TextAlign.center,
                                  style: TextStyle(color: c.textMuted),
                                ),
                              ],
                            ),
                          )
                        : ListView.separated(
                            controller: scrollCtrl,
                            padding: const EdgeInsets.all(16),
                            itemCount: devices.length,
                            separatorBuilder: (_, __) =>
                                const SizedBox(height: 8),
                            itemBuilder: (context, i) {
                              final d = devices[i];
                              return ListTile(
                                shape: RoundedRectangleBorder(
                                  borderRadius: BorderRadius.circular(14),
                                  side: BorderSide(color: c.borderSubtle),
                                ),
                                tileColor: c.surfaceElevated,
                                leading: CircleAvatar(
                                  backgroundColor: c.accent.withOpacity(0.14),
                                  child: Icon(
                                    d.isBle
                                        ? Icons.bluetooth_rounded
                                        : Icons.settings_bluetooth_rounded,
                                    color: c.accent,
                                  ),
                                ),
                                title: Text(
                                  d.name,
                                  style: const TextStyle(
                                      fontWeight: FontWeight.w600),
                                ),
                                subtitle: Text(
                                  '${d.address} • ${d.isPaired ? "Paired" : "BLE ${d.rssi} dBm"}',
                                  style: TextStyle(
                                      fontSize: 12, color: c.textMuted),
                                ),
                                trailing: FilledButton(
                                  onPressed: () {
                                    switch (_selectedSlot) {
                                      case 0:
                                        ctrl.connectMotor(d.address,
                                            preferBle: _preferBleMotor || d.isBle);
                                        break;
                                      case 1:
                                        ctrl.connectJkBms(d.address);
                                        break;
                                      case 2:
                                        ctrl.connectEspSlot(1, d.address);
                                        break;
                                      case 3:
                                        ctrl.connectEspSlot(2, d.address);
                                        break;
                                    }
                                  },
                                  child: const Text('Hubung'),
                                ),
                              );
                            },
                          ),
              ),
            ],
          ),
        );
      },
    );
  }

  Widget _slotPill({
    required int idx,
    required String label,
    required ConnectionStateX state,
    required String sub,
    required VoidCallback onDisconnect,
  }) {
    final c = context.cockpit;
    final selected = _selectedSlot == idx;
    final isConn = state == ConnectionStateX.connected;
    final isBusy = state == ConnectionStateX.connecting;

    return InkWell(
      onTap: () => setState(() => _selectedSlot = idx),
      borderRadius: BorderRadius.circular(14),
      child: Container(
        width: 148,
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        decoration: BoxDecoration(
          color: selected ? c.accent.withOpacity(0.14) : c.surfaceElevated,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(
            color: selected ? c.accent : c.borderSubtle,
            width: selected ? 1.5 : 1,
          ),
        ),
        child: Row(
          children: [
            Container(
              width: 8,
              height: 8,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: isConn
                    ? c.accent
                    : isBusy
                        ? c.warning
                        : c.textMuted.withOpacity(0.5),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Text(
                    label,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        fontWeight: FontWeight.w700, fontSize: 12.5),
                  ),
                  Text(
                    sub,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(fontSize: 10.5, color: c.textMuted),
                  ),
                ],
              ),
            ),
            if (isConn)
              GestureDetector(
                onTap: onDisconnect,
                child: Icon(Icons.link_off_rounded, size: 16, color: c.danger),
              ),
          ],
        ),
      ),
    );
  }
}
