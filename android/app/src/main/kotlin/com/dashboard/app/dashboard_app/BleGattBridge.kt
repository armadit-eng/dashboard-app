package com.dashboard.app.dashboard_app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BleGattBridge(private val context: Context) :
    MethodChannel.MethodCallHandler, EventChannel.StreamHandler {

    companion object {
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val JK_SVC_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        private val JK_NOTIFY_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        private val JK_WRITE_UUID: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")

        private val NUS_SVC_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_WRITE_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_NOTIFY_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var eventSink: EventChannel.EventSink? = null
    private var scanCb: ScanCallback? = null
    private val foundMap = LinkedHashMap<String, Map<String, Any?>>()

    private val gatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val writeChars = ConcurrentHashMap<String, BluetoothGattCharacteristic>()

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "startScan" -> {
                val timeoutMs = (call.argument<Int>("timeoutMs") ?: 7000).toLong()
                startScan(timeoutMs)
                result.success(true)
            }
            "stopScan" -> {
                stopScan()
                result.success(true)
            }
            "connectSlot" -> {
                val slot = call.argument<String>("slot") ?: "jk"
                val address = call.argument<String>("address") ?: ""
                if (address.isBlank()) {
                    result.error("INVALID_ADDR", "Address required", null)
                    return
                }
                connectSlot(slot, address)
                result.success(true)
            }
            "disconnectSlot" -> {
                val slot = call.argument<String>("slot") ?: "jk"
                disconnectSlot(slot)
                result.success(true)
            }
            "writeSlot" -> {
                val slot = call.argument<String>("slot") ?: "jk"
                val bytes = call.argument<ByteArray>("bytes") ?: ByteArray(0)
                val ok = writeSlot(slot, bytes)
                result.success(ok)
            }
            else -> result.notImplemented()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan(timeoutMs: Long) {
        val bt = adapter ?: return
        if (!bt.isEnabled) return
        stopScan()
        foundMap.clear()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                val addr = dev.address ?: return
                val name = try {
                    dev.name ?: result.scanRecord?.deviceName ?: "BLE Device"
                } catch (_: SecurityException) {
                    "BLE Device"
                }
                foundMap[addr] = mapOf(
                    "name" to name,
                    "address" to addr,
                    "isBle" to true,
                    "rssi" to result.rssi
                )
                val list = foundMap.values.sortedByDescending { (it["rssi"] as? Int) ?: -100 }
                emit(mapOf("type" to "scan", "devices" to list))
            }
        }
        scanCb = cb
        try {
            bt.bluetoothLeScanner?.startScan(cb)
            mainHandler.postDelayed({ stopScan() }, timeoutMs)
        } catch (e: Exception) {
            emit(mapOf("type" to "log", "source" to "BLE", "msg" to "Gagal scan BLE: ${e.message}", "err" to true))
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        val cb = scanCb ?: return
        scanCb = null
        try {
            adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: Exception) {
        }
        emit(mapOf("type" to "scanDone"))
    }

    @SuppressLint("MissingPermission")
    private fun connectSlot(slot: String, address: String) {
        disconnectSlot(slot)
        stopScan()
        val dev = try {
            adapter?.getRemoteDevice(address)
        } catch (_: Exception) {
            null
        } ?: return

        val devName = try {
            dev.name ?: address
        } catch (_: SecurityException) {
            address
        }
        emit(mapOf("type" to "slotState", "slot" to slot, "state" to "connecting", "name" to devName))

        val gatt = dev.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    disconnectSlot(slot)
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val isNus = slot == "esp1" || slot == "esp2"
                val svc = if (isNus) {
                    g.getService(NUS_SVC_UUID)
                } else {
                    g.getService(JK_SVC_UUID) ?: g.services.firstOrNull { s ->
                        s.characteristics.any { (it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 }
                    }
                }
                if (svc == null) {
                    emit(mapOf("type" to "log", "source" to slot.uppercase(), "msg" to "Layanan GATT tidak ditemukan.", "err" to true))
                    disconnectSlot(slot)
                    return
                }

                val notifyCh = if (isNus) {
                    svc.getCharacteristic(NUS_NOTIFY_UUID)
                } else {
                    svc.getCharacteristic(JK_NOTIFY_UUID) ?: svc.characteristics.firstOrNull {
                        (it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    }
                }
                val writeCh = if (isNus) {
                    svc.getCharacteristic(NUS_WRITE_UUID)
                } else {
                    svc.getCharacteristic(JK_WRITE_UUID) ?: notifyCh ?: svc.characteristics.firstOrNull {
                        (it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
                    }
                }
                if (writeCh != null) writeChars[slot] = writeCh

                if (notifyCh != null) {
                    g.setCharacteristicNotification(notifyCh, true)
                    val cccd = notifyCh.getDescriptor(CCCD_UUID)
                    if (cccd != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            g.writeDescriptor(cccd)
                        }
                    }
                }
                emit(mapOf("type" to "slotState", "slot" to slot, "state" to "connected", "name" to devName))
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION")
                val data = ch.value ?: return
                emit(mapOf("type" to "data", "slot" to slot, "bytes" to data))
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
                emit(mapOf("type" to "data", "slot" to slot, "bytes" to value))
            }
        }, BluetoothDevice.TRANSPORT_LE)

        if (gatt != null) gatts[slot] = gatt
    }

    @SuppressLint("MissingPermission")
    private fun writeSlot(slot: String, bytes: ByteArray): Boolean {
        val g = gatts[slot] ?: return false
        val ch = writeChars[slot] ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val writeType = if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
                g.writeCharacteristic(ch, bytes, writeType) == 0
            } else {
                @Suppress("DEPRECATION")
                ch.value = bytes
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnectSlot(slot: String) {
        writeChars.remove(slot)
        val g = gatts.remove(slot)
        try {
            g?.disconnect()
            g?.close()
        } catch (_: Exception) {
        }
        emit(mapOf("type" to "slotState", "slot" to slot, "state" to "disconnected", "name" to ""))
    }

    private fun emit(payload: Map<String, Any?>) {
        mainHandler.post { eventSink?.success(payload) }
    }
}
