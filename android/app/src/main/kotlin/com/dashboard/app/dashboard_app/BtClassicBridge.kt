package com.dashboard.app.dashboard_app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors

class BtClassicBridge(private val context: Context) :
    MethodChannel.MethodCallHandler, EventChannel.StreamHandler {

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val writeExecutor = Executors.newSingleThreadExecutor()

    private var eventSink: EventChannel.EventSink? = null
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

    @Volatile
    private var reading = false

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
            "isBluetoothEnabled" -> {
                result.success(adapter?.isEnabled == true)
            }

            "getBondedDevices" -> {
                result.success(getBondedDevicesList())
            }

            "connect" -> {
                val address = call.argument<String>("address")
                if (address.isNullOrBlank()) {
                    result.error("INVALID_ARGS", "Address is required", null)
                    return
                }
                connectSpp(address, result)
            }

            "write" -> {
                val text = call.argument<String>("text") ?: ""
                writeRaw(text, result)
            }

            "disconnect" -> {
                disconnectInternal("manual")
                result.success(true)
            }

            else -> result.notImplemented()
        }
    }

    @SuppressLint("MissingPermission")
    private fun getBondedDevicesList(): List<Map<String, Any?>> {
        val bt = adapter ?: return emptyList()
        return try {
            bt.bondedDevices?.map { dev ->
                mapOf(
                    "name" to (dev.name ?: "Perangkat Bluetooth"),
                    "address" to dev.address,
                    "isBle" to (dev.type == BluetoothDevice.DEVICE_TYPE_LE),
                    "isPaired" to true
                )
            } ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectSpp(address: String, result: MethodChannel.Result) {
        disconnectInternal("reconnect")
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            result.error("BT_DISABLED", "Bluetooth tidak aktif", null)
            return
        }

        ioExecutor.execute {
            try {
                val device = bt.getRemoteDevice(address)
                val sock = device.createRfcommSocketToServiceRecord(SPP_UUID)
                try {
                    bt.cancelDiscovery()
                } catch (_: SecurityException) {
                }
                sock.connect()
                socket = sock
                outputStream = sock.outputStream
                reading = true
                val devName = try {
                    device.name ?: address
                } catch (_: SecurityException) {
                    address
                }
                emitEvent(
                    mapOf(
                        "type" to "state",
                        "state" to "connected",
                        "name" to devName,
                        "address" to address
                    )
                )
                mainHandler.post { result.success(true) }
                readLoop(sock)
            } catch (e: Exception) {
                disconnectInternal("error: ${e.message}")
                mainHandler.post {
                    result.error("CONNECT_FAILED", e.message ?: "Gagal menghubungkan SPP", null)
                }
            }
        }
    }

    private fun readLoop(sock: BluetoothSocket) {
        try {
            val reader = BufferedReader(InputStreamReader(sock.inputStream, Charsets.UTF_8))
            while (reading) {
                val line = reader.readLine() ?: break
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    emitEvent(mapOf("type" to "line", "line" to trimmed))
                }
            }
        } catch (e: Exception) {
            if (reading) {
                emitEvent(mapOf("type" to "error", "message" to (e.message ?: "Koneksi terputus")))
            }
        } finally {
            disconnectInternal("eof")
        }
    }

    private fun writeRaw(text: String, result: MethodChannel.Result) {
        val out = outputStream
        if (out == null) {
            result.error("NOT_CONNECTED", "Socket SPP belum terhubung", null)
            return
        }
        writeExecutor.execute {
            val t0 = System.currentTimeMillis()
            try {
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                val elapsed = System.currentTimeMillis() - t0
                mainHandler.post { result.success(elapsed.toInt()) }
            } catch (e: Exception) {
                mainHandler.post {
                    result.error("WRITE_FAILED", e.message ?: "Gagal menulis ke socket", null)
                }
            }
        }
    }

    private fun disconnectInternal(reason: String) {
        val wasActive = socket != null || reading
        reading = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        outputStream = null
        if (wasActive) {
            emitEvent(mapOf("type" to "state", "state" to "disconnected", "reason" to reason))
        }
    }

    private fun emitEvent(payload: Map<String, Any?>) {
        mainHandler.post {
            eventSink?.success(payload)
        }
    }
}
