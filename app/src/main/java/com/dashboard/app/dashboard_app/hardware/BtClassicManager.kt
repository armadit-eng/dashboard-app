package com.dashboard.app.dashboard_app.hardware

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dashboard.app.dashboard_app.model.ConnectionStateX
import com.dashboard.app.dashboard_app.model.DiscoveredDeviceX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class BtClassicManager(private val context: Context) {
    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    var onLineReceived: ((String) -> Unit)? = null
    var onStateChanged: ((ConnectionStateX, String, String?) -> Unit)? = null
    var onLog: ((String, String, Boolean) -> Unit)? = null

    @Volatile
    var isMapsSending: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val writeExecutor = Executors.newSingleThreadExecutor()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    private val txQueue = ConcurrentLinkedQueue<String>()
    private var txFuture: ScheduledFuture<*>? = null
    private var pollFuture: ScheduledFuture<*>? = null
    private var pollCycle = 0

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

    @Volatile
    private var reading = false

    @Volatile
    private var connected = false

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun getBondedDevices(): List<DiscoveredDeviceX> {
        val bt = adapter ?: return emptyList()
        return try {
            bt.bondedDevices?.map { dev ->
                DiscoveredDeviceX(
                    name = dev.name ?: "Perangkat Bluetooth",
                    address = dev.address,
                    isBle = dev.type == BluetoothDevice.DEVICE_TYPE_LE,
                    isPaired = true
                )
            } ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(address: String): Boolean = withContext(Dispatchers.IO) {
        disconnectInternal("reconnect")
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            mainHandler.post {
                onLog?.invoke("MOTOR", "Bluetooth tidak aktif", true)
                onStateChanged?.invoke(ConnectionStateX.DISCONNECTED, "", "BT_DISABLED")
            }
            return@withContext false
        }

        mainHandler.post {
            onStateChanged?.invoke(ConnectionStateX.CONNECTING, address, null)
        }

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
            connected = true

            val devName = try {
                device.name ?: address
            } catch (_: SecurityException) {
                address
            }

            mainHandler.post {
                onStateChanged?.invoke(ConnectionStateX.CONNECTED, devName, null)
                startTxAndPolling()
            }
            ioExecutor.execute { readLoop(sock) }
            true
        } catch (e: Exception) {
            disconnectInternal("error: ${e.message}")
            mainHandler.post {
                onLog?.invoke("MOTOR", "Gagal koneksi SPP ke $address: ${e.message}", true)
            }
            false
        }
    }

    private fun readLoop(sock: BluetoothSocket) {
        try {
            val reader = BufferedReader(InputStreamReader(sock.inputStream, Charsets.UTF_8))
            while (reading) {
                val line = reader.readLine() ?: break
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    mainHandler.post { onLineReceived?.invoke(trimmed) }
                }
            }
        } catch (e: Exception) {
            if (reading) {
                mainHandler.post {
                    onLog?.invoke("MOTOR", e.message ?: "Koneksi terputus", true)
                }
            }
        } finally {
            disconnectInternal("eof")
        }
    }

    fun disconnect() {
        disconnectInternal("manual")
    }

    fun sendCommand(cmd: String) {
        if (!connected) return
        val norm = if (cmd.endsWith("\r\n")) cmd else "$cmd\r\n"
        if (txQueue.size < 40) {
            txQueue.add(norm)
        }
    }

    fun sendTripRange(rangeKm: Double) {
        sendCommand("AT+TRIP=\"${String.format(Locale.US, "%.1f", rangeKm)}\"")
    }

    fun sendNavText(rawText: String) {
        val ascii = rawText.replace(Regex("[^\\x20-\\x7E]"), " ").trim()
        val clipped = if (ascii.length > 20) ascii.substring(0, 20) else ascii
        val padded = clipped.padEnd(20, ' ')
        sendCommand("AT+NAV=\"$padded\"")
    }

    fun restoreOdo(odoKm: Double) {
        sendCommand("AT+ODO=\"${odoKm.roundToInt()}\"")
    }

    suspend fun sendMapsFrame(base64Jpeg: String): Int? = withContext(Dispatchers.IO) {
        if (!connected || isMapsSending || base64Jpeg.isEmpty()) return@withContext null
        val out = outputStream ?: return@withContext null
        isMapsSending = true
        try {
            val payload = "AT+MAPS=\"$base64Jpeg\"\r\n".toByteArray(Charsets.UTF_8)
            val t0 = System.currentTimeMillis()
            synchronized(out) {
                out.write(payload)
                out.flush()
            }
            (System.currentTimeMillis() - t0).toInt()
        } catch (e: Exception) {
            mainHandler.post {
                onLog?.invoke("MOTOR", "Gagal kirim AT+MAPS: ${e.message}", true)
            }
            null
        } finally {
            isMapsSending = false
        }
    }

    private fun startTxAndPolling() {
        stopLoops()
        val cal = Calendar.getInstance()
        val hh = cal.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
        val mm = cal.get(Calendar.MINUTE).toString().padStart(2, '0')
        sendCommand("AT+TIME=\"$hh$mm\"")

        txFuture = scheduler.scheduleAtFixedRate({
            if (!connected) return@scheduleAtFixedRate
            val cmd = txQueue.poll() ?: return@scheduleAtFixedRate
            val out = outputStream ?: return@scheduleAtFixedRate
            writeExecutor.execute {
                try {
                    synchronized(out) {
                        out.write(cmd.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                } catch (_: Exception) {
                }
            }
        }, 65, 65, TimeUnit.MILLISECONDS)

        pollFuture = scheduler.scheduleAtFixedRate({
            if (!connected) return@scheduleAtFixedRate
            enqueueIfMissing("AT+SPD?\r\n")
            enqueueIfMissing("AT+IBAT?\r\n")
            enqueueIfMissing("AT+VBAT?\r\n")
            enqueueIfMissing("AT+TRT?\r\n")
            if (pollCycle % 2 == 0) {
                enqueueIfMissing("AT+SOC?\r\n")
                enqueueIfMissing("AT+ERR?\r\n")
                enqueueIfMissing("AT+MODE?\r\n")
                enqueueIfMissing("AT+PARK?\r\n")
            }
            if (pollCycle % 6 == 0) {
                enqueueIfMissing("AT+CBAT?\r\n")
                enqueueIfMissing("AT+ODO?\r\n")
                enqueueIfMissing("AT+TINV?\r\n")
                enqueueIfMissing("AT+TMOT?\r\n")
                enqueueIfMissing("AT+TBAT?\r\n")
                enqueueIfMissing("AT+SOH?\r\n")
            }
            pollCycle++
        }, 100, 500, TimeUnit.MILLISECONDS)
    }

    private fun enqueueIfMissing(cmd: String) {
        if (txQueue.size < 32 && !txQueue.contains(cmd)) {
            txQueue.add(cmd)
        }
    }

    private fun stopLoops() {
        txFuture?.cancel(false)
        pollFuture?.cancel(false)
        txFuture = null
        pollFuture = null
        txQueue.clear()
    }

    private fun disconnectInternal(reason: String) {
        val wasActive = socket != null || reading || connected
        reading = false
        connected = false
        stopLoops()
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        outputStream = null
        if (wasActive) {
            mainHandler.post {
                onStateChanged?.invoke(ConnectionStateX.DISCONNECTED, "", reason)
            }
        }
    }

    fun dispose() {
        disconnectInternal("dispose")
        scheduler.shutdownNow()
        ioExecutor.shutdownNow()
        writeExecutor.shutdownNow()
    }
}
