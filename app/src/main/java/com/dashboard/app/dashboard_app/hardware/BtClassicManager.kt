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
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.text.Normalizer
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.roundToInt

class BtClassicManager(private val context: Context) {
    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val WRITE_MIN_GAP_MS = 30L
        private const val NAV_SAME_RESEND_MS = 15_000L
    }

    var onLineReceived: ((String) -> Unit)? = null
    var onStateChanged: ((ConnectionStateX, String, String?) -> Unit)? = null
    var onLog: ((String, String, Boolean) -> Unit)? = null

    @Volatile
    var isMapsSending: Boolean = false
        private set

    @Volatile
    var lastMapsWriteMs: Long = 0L
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    private val writeQueue = LinkedBlockingQueue<String>()
    private var writerThread: Thread? = null
    @Volatile
    private var writerRunning = false

    private var pollFuture: ScheduledFuture<*>? = null
    private var pollCycle = 0

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

    @Volatile
    private var reading = false
    @Volatile
    private var connected = false
    @Volatile
    private var shouldReconnect = false
    @Volatile
    private var lastMac: String? = null
    private var reconnectThread: Thread? = null

    private var lastNavSentText: String = ""
    private var lastNavSentAt: Long = 0L

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
        shouldReconnect = true
        lastMac = address
        stopReconnectLoop()
        connectInternal(address, isReconnect = false)
    }

    @SuppressLint("MissingPermission")
    private fun connectInternal(address: String, isReconnect: Boolean): Boolean {
        closeSocketOnly()
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            mainHandler.post {
                onLog?.invoke("MOTOR", "Bluetooth tidak aktif", true)
                onStateChanged?.invoke(ConnectionStateX.DISCONNECTED, "", "BT_DISABLED")
            }
            return false
        }

        if (!isReconnect) {
            mainHandler.post {
                onStateChanged?.invoke(ConnectionStateX.CONNECTING, address, null)
            }
        }

        return try {
            val device = bt.getRemoteDevice(address)
            try {
                bt.cancelDiscovery()
            } catch (_: SecurityException) {
            }

            // Primary: standard SPP UUID; fallback: reflection channel 1 (bypasses SDP/BLE routing quirks)
            val sock = try {
                device.createRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
            } catch (e1: IOException) {
                mainHandler.post {
                    onLog?.invoke("MOTOR", "SPP UUID gagal (${e1.message}), mencoba channel 1…", false)
                }
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                val fallback = m.invoke(device, 1) as BluetoothSocket
                fallback.connect()
                fallback
            }

            socket = sock
            outputStream = sock.outputStream
            reading = true
            connected = true

            val devName = try {
                device.name ?: address
            } catch (_: SecurityException) {
                address
            }

            startWriter()
            mainHandler.post {
                onStateChanged?.invoke(ConnectionStateX.CONNECTED, devName, null)
                startPolling()
            }
            ioExecutor.execute { readLoop(sock) }
            true
        } catch (e: Exception) {
            closeSocketOnly()
            if (!isReconnect) {
                mainHandler.post {
                    onLog?.invoke("MOTOR", "Gagal koneksi SPP ke $address: ${e.message}", true)
                    onStateChanged?.invoke(ConnectionStateX.DISCONNECTED, "", "error: ${e.message}")
                }
            }
            if (shouldReconnect) startReconnectLoop()
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
            val dropWasUnexpected = reading && shouldReconnect
            closeSocketOnly()
            mainHandler.post {
                onStateChanged?.invoke(ConnectionStateX.DISCONNECTED, "", "eof")
            }
            if (dropWasUnexpected) {
                startReconnectLoop()
            }
        }
    }

    private fun startReconnectLoop() {
        val mac = lastMac ?: return
        if (!shouldReconnect || reconnectThread?.isAlive == true) return
        val t = Thread {
            var delayMs = 4_000L
            var attempt = 0
            while (shouldReconnect && !connected) {
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    break
                }
                if (!shouldReconnect || connected) break
                attempt++
                delayMs = min((delayMs * 1.5).toLong(), 30_000L)
                mainHandler.post {
                    onLog?.invoke("MOTOR", "Mencoba sambung ulang MID (#$attempt)…", false)
                }
                if (connectInternal(mac, isReconnect = true)) break
            }
        }.apply {
            isDaemon = true
            name = "MidSppReconnect"
            start()
        }
        reconnectThread = t
    }

    private fun stopReconnectLoop() {
        reconnectThread?.interrupt()
        reconnectThread = null
    }

    fun disconnect() {
        shouldReconnect = false
        lastMac = null
        stopReconnectLoop()
        val wasActive = socket != null || reading || connected
        closeSocketOnly()
        if (wasActive) {
            mainHandler.post {
                onStateChanged?.invoke(ConnectionStateX.DISCONNECTED, "", "manual")
            }
        }
    }

    /** Latest-wins enqueue onto the single serialized BT Classic writer thread. */
    fun sendCommand(cmd: String) {
        if (!connected) return
        val norm = if (cmd.endsWith("\r\n")) cmd else "$cmd\r\n"
        enqueueCoalesced(norm)
    }

    private fun enqueueCoalesced(fullCmd: String) {
        val eqPrefix = when {
            fullCmd.startsWith("AT+MAPS=") -> "AT+MAPS="
            fullCmd.startsWith("AT+NAV=") -> "AT+NAV="
            fullCmd.startsWith("AT+TRIP=") -> "AT+TRIP="
            fullCmd.indexOf('?') > 0 && fullCmd.length < 24 -> fullCmd
            else -> null
        }
        if (eqPrefix != null) {
            writeQueue.removeIf { it.startsWith(eqPrefix) }
        }
        if (writeQueue.size < 48) {
            writeQueue.offer(fullCmd)
        }
    }

    fun mapsQueueDepth(): Int {
        var count = if (isMapsSending) 1 else 0
        for (item in writeQueue) {
            if (item.startsWith("AT+MAPS=")) count++
        }
        return count
    }

    fun sendTripRange(rangeKm: Double) {
        sendCommand("AT+TRIP=\"${String.format(Locale.US, "%.1f", rangeKm)}\"")
    }

    fun sendNavText(rawText: String) {
        val ascii = Normalizer.normalize(rawText, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^\\x20-\\x7E]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val clipped = if (ascii.length > 20) ascii.substring(0, 20).trim() else ascii
        val padded = clipped.padEnd(20, ' ')
        val now = System.currentTimeMillis()
        if (padded == lastNavSentText && now - lastNavSentAt < NAV_SAME_RESEND_MS) {
            return
        }
        lastNavSentText = padded
        lastNavSentAt = now
        sendCommand("AT+NAV=\"$padded\"")
    }

    fun restoreOdo(odoKm: Double) {
        sendCommand("AT+ODO=\"${odoKm.roundToInt()}\"")
    }

    suspend fun sendMapsFrame(base64Jpeg: String): Int? = withContext(Dispatchers.IO) {
        if (!connected || base64Jpeg.isEmpty() || mapsQueueDepth() > 0) return@withContext null
        enqueueCoalesced("AT+MAPS=\"$base64Jpeg\"\r\n")
        val waitStart = System.currentTimeMillis()
        while (connected && mapsQueueDepth() > 0 && System.currentTimeMillis() - waitStart < 4_000L) {
            Thread.sleep(25L)
        }
        lastMapsWriteMs.toInt().coerceAtLeast(1)
    }

    private fun startWriter() {
        stopWriter()
        writerRunning = true
        writeQueue.clear()
        writerThread = Thread {
            var lastWriteAt = 0L
            while (writerRunning) {
                val cmd = try {
                    writeQueue.take()
                } catch (_: InterruptedException) {
                    break
                }
                val out = outputStream ?: continue
                val wait = WRITE_MIN_GAP_MS - (System.currentTimeMillis() - lastWriteAt)
                if (wait > 0) {
                    try {
                        Thread.sleep(wait)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
                val isMaps = cmd.startsWith("AT+MAPS=")
                val t0 = System.currentTimeMillis()
                if (isMaps) isMapsSending = true
                try {
                    out.write(cmd.toByteArray(Charsets.UTF_8))
                    out.flush()
                } catch (e: IOException) {
                    if (isMaps) {
                        mainHandler.post {
                            onLog?.invoke("MOTOR", "Gagal kirim AT+MAPS: ${e.message}", true)
                        }
                    }
                } finally {
                    if (isMaps) {
                        lastMapsWriteMs = System.currentTimeMillis() - t0
                        isMapsSending = false
                    }
                }
                lastWriteAt = System.currentTimeMillis()
                BleCoex.markClassicWrite()
            }
        }.apply {
            isDaemon = true
            name = "MidSppWriter"
            start()
        }
    }

    private fun stopWriter() {
        writerRunning = false
        writerThread?.interrupt()
        writerThread = null
        writeQueue.clear()
        isMapsSending = false
    }

    private fun startPolling() {
        stopPolling()
        val cal = Calendar.getInstance()
        val hh = cal.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
        val mm = cal.get(Calendar.MINUTE).toString().padStart(2, '0')
        sendCommand("AT+TIME=\"$hh$mm\"")

        pollFuture = scheduler.scheduleAtFixedRate({
            if (!connected) return@scheduleAtFixedRate
            enqueueCoalesced("AT+SPD?\r\n")
            enqueueCoalesced("AT+IBAT?\r\n")
            enqueueCoalesced("AT+VBAT?\r\n")
            enqueueCoalesced("AT+TRT?\r\n")
            if (pollCycle % 2 == 0) {
                enqueueCoalesced("AT+SOC?\r\n")
                enqueueCoalesced("AT+ERR?\r\n")
                enqueueCoalesced("AT+MODE?\r\n")
                enqueueCoalesced("AT+PARK?\r\n")
            }
            if (pollCycle % 6 == 0) {
                enqueueCoalesced("AT+CBAT?\r\n")
                enqueueCoalesced("AT+ODO?\r\n")
                enqueueCoalesced("AT+TINV?\r\n")
                enqueueCoalesced("AT+TMOT?\r\n")
                enqueueCoalesced("AT+TBAT?\r\n")
                enqueueCoalesced("AT+SOH?\r\n")
            }
            pollCycle++
        }, 120, 500, TimeUnit.MILLISECONDS)
    }

    private fun stopPolling() {
        pollFuture?.cancel(false)
        pollFuture = null
    }

    private fun closeSocketOnly() {
        reading = false
        connected = false
        stopPolling()
        stopWriter()
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        outputStream = null
    }

    fun dispose() {
        disconnect()
        scheduler.shutdownNow()
        ioExecutor.shutdownNow()
    }
}
