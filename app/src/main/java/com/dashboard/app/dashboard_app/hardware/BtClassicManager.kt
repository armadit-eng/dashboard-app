package com.dashboard.app.dashboard_app.hardware

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dashboard.app.dashboard_app.engine.TelemetryEngine
import com.dashboard.app.dashboard_app.model.ConnectionStateX
import com.dashboard.app.dashboard_app.model.ControllerProtocol
import com.dashboard.app.dashboard_app.model.DiscoveredDeviceX
import com.dashboard.app.dashboard_app.model.FardriverData
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
    var onFardriverData: ((FardriverData) -> Unit)? = null
    var onStateChanged: ((ConnectionStateX, String, String?) -> Unit)? = null
    var onLog: ((String, String, Boolean) -> Unit)? = null
    var isNavOrMapsBusy: (() -> Boolean)? = null

    @Volatile
    var activeControllerProtocol: ControllerProtocol = ControllerProtocol.MID_AT

    @Volatile
    var fardriverPolePairs: Int = 20

    @Volatile
    var fardriverWheelCircMm: Int = 1580

    @Volatile
    var fardriverCurrentScale: Double = 0.25

    @Volatile
    var isMapsSending: Boolean = false
        private set

    @Volatile
    private var mapsSendStartedMs: Long = 0L

    @Volatile
    var lastMapsWriteMs: Long = 0L
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    private sealed class QueuedWrite {
        data class Text(val cmd: String) : QueuedWrite()
        data class Raw(val bytes: ByteArray) : QueuedWrite()
    }

    private val writeQueue = LinkedBlockingQueue<QueuedWrite>()
    private var writerThread: Thread? = null
    @Volatile
    private var writerRunning = false

    private val fdFrameBuf = mutableListOf<Byte>()
    private var fardriverCur = FardriverData()

    private data class PollEntry(val cmd: String, val intervalMs: Long, var lastSentMs: Long = 0L)

    private val pollSchedule = listOf(
        PollEntry("AT+SPD?\r\n", 500L),
        PollEntry("AT+IBAT?\r\n", 500L),
        PollEntry("AT+VBAT?\r\n", 1000L),
        PollEntry("AT+TRT?\r\n", 2000L),
        PollEntry("AT+ODO?\r\n", 2000L),
        PollEntry("AT+SOC?\r\n", 4000L),
        PollEntry("AT+ERR?\r\n", 8000L),
        PollEntry("AT+MODE?\r\n", 8000L),
        PollEntry("AT+PARK?\r\n", 8000L),
        PollEntry("AT+CBAT?\r\n", 8000L),
        PollEntry("AT+TINV?\r\n", 15_000L),
        PollEntry("AT+TMOT?\r\n", 15_000L),
        PollEntry("AT+TBAT?\r\n", 15_000L),
        PollEntry("AT+SOH?\r\n", 30_000L)
    )

    private var pollFuture: ScheduledFuture<*>? = null
    private var timeFuture: ScheduledFuture<*>? = null

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
    @Volatile
    private var cbatReceived: Boolean = false

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    /** True hanya bila perangkat diketahui BLE-only (tidak punya Classic/SPP). */
    @SuppressLint("MissingPermission")
    fun isBleOnly(address: String): Boolean = try {
        adapter?.getRemoteDevice(address)?.type == BluetoothDevice.DEVICE_TYPE_LE
    } catch (_: Exception) {
        false
    }

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
            if (activeControllerProtocol == ControllerProtocol.FARDRIVER_SPP) {
                val input = sock.inputStream
                val buf = ByteArray(512)
                while (reading) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        onFardriverBytes(buf, n)
                    }
                }
            } else {
                val reader = BufferedReader(InputStreamReader(sock.inputStream, Charsets.UTF_8))
                while (reading) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        if (!cbatReceived && trimmed.contains("CBAT", ignoreCase = true) && trimmed.any { it.isDigit() }) {
                            cbatReceived = true
                        }
                        mainHandler.post { onLineReceived?.invoke(trimmed) }
                    }
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

    private fun onFardriverBytes(chunk: ByteArray, length: Int) {
        synchronized(fdFrameBuf) {
            for (i in 0 until length) fdFrameBuf.add(chunk[i])
            while (fdFrameBuf.size >= 16) {
                val startIdx = fdFrameBuf.indexOfFirst { (it.toInt() and 0xFF) == 0xAA }
                if (startIdx < 0) {
                    fdFrameBuf.clear()
                    return
                }
                if (startIdx > 0) {
                    fdFrameBuf.subList(0, startIdx).clear()
                }
                if (fdFrameBuf.size < 16) return
                val candidate = fdFrameBuf.subList(0, 16).toByteArray()
                val pktId = candidate[1].toInt() and 0x3F
                if (pktId !in 0..54) {
                    fdFrameBuf.removeAt(0)
                    continue
                }
                fdFrameBuf.subList(0, 16).clear()
                val parsed = TelemetryEngine.parseFardriver16ByteFrame(
                    buf = candidate,
                    current = fardriverCur,
                    polePairs = fardriverPolePairs,
                    wheelCircMm = fardriverWheelCircMm,
                    currentScale = fardriverCurrentScale
                )
                if (parsed != null) {
                    fardriverCur = parsed
                    mainHandler.post { onFardriverData?.invoke(parsed) }
                }
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

    fun sendRawBytes(bytes: ByteArray) {
        if (!connected) return
        if (writeQueue.size < 48) {
            writeQueue.offer(QueuedWrite.Raw(bytes))
        }
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
            writeQueue.removeIf { item -> item is QueuedWrite.Text && item.cmd.startsWith(eqPrefix) }
        }
        if (writeQueue.size < 48) {
            writeQueue.offer(QueuedWrite.Text(fullCmd))
        }
    }

    fun mapsQueueDepth(): Int {
        if (isMapsSending && mapsSendStartedMs > 0L && System.currentTimeMillis() - mapsSendStartedMs > 10_000L) {
            isMapsSending = false
            mapsSendStartedMs = 0L
        }
        var count = if (isMapsSending) 1 else 0
        for (item in writeQueue) {
            if (item is QueuedWrite.Text && item.cmd.startsWith("AT+MAPS=")) count++
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
                val item = try {
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
                val isMaps = item is QueuedWrite.Text && item.cmd.startsWith("AT+MAPS=")
                val t0 = System.currentTimeMillis()
                if (isMaps) {
                    isMapsSending = true
                    mapsSendStartedMs = t0
                }
                try {
                    val bytes = when (item) {
                        is QueuedWrite.Text -> item.cmd.toByteArray(Charsets.UTF_8)
                        is QueuedWrite.Raw -> item.bytes
                    }
                    out.write(bytes)
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
                        mapsSendStartedMs = 0L
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

    fun sendTimeNow() {
        if (!connected) return
        val cal = Calendar.getInstance()
        val hh = cal.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
        val mm = cal.get(Calendar.MINUTE).toString().padStart(2, '0')
        sendCommand("AT+TIME=\"$hh$mm\"")
    }

    private fun startPolling() {
        stopPolling()
        cbatReceived = false
        pollSchedule.forEach { it.lastSentMs = 0L }

        if (activeControllerProtocol == ControllerProtocol.FARDRIVER_SPP) {
            synchronized(fdFrameBuf) { fdFrameBuf.clear() }
            fardriverCur = FardriverData()
            scheduler.schedule({
                if (connected) sendRawBytes(TelemetryEngine.FARDRIVER_WAKE_CMD)
            }, 250, TimeUnit.MILLISECONDS)
            pollFuture = scheduler.scheduleAtFixedRate({
                if (connected) sendRawBytes(TelemetryEngine.FARDRIVER_WAKE_CMD)
            }, 3000, 3000, TimeUnit.MILLISECONDS)
            return
        }

        // Query battery & odometer snapshot immediately after socket settles, before AT+MAPS floods link
        scheduler.schedule({
            if (connected) {
                listOf(
                    "AT+CBAT?\r\n",
                    "AT+SOH?\r\n",
                    "AT+SOC?\r\n",
                    "AT+ODO?\r\n",
                    "AT+VBAT?\r\n",
                    "AT+TBAT?\r\n"
                ).forEach { enqueueCoalesced(it) }
            }
        }, 450, TimeUnit.MILLISECONDS)

        // Allow link/readLoop to settle (1.6s), then sync RTC clock every 60s
        timeFuture = scheduler.scheduleAtFixedRate({
            if (connected) sendTimeNow()
        }, 1600, 60_000, TimeUnit.MILLISECONDS)

        // Single most-overdue command per tick (prevents MID serial RX overflow)
        pollFuture = scheduler.scheduleAtFixedRate({
            if (!connected) return@scheduleAtFixedRate
            if (mapsQueueDepth() > 0 || writeQueue.size >= 2) return@scheduleAtFixedRate
            val now = System.currentTimeMillis()
            val navBusy = isNavOrMapsBusy?.invoke() == true
            val bleBusy = BleConnectGate.isBusy()
            var best: PollEntry? = null
            var bestScore = 1.0
            for (entry in pollSchedule) {
                val isUnreceivedCbat = !cbatReceived && entry.cmd.startsWith("AT+CBAT")
                if (bleBusy && entry.intervalMs > 1000L && !isUnreceivedCbat) continue
                val baseInterval = if (isUnreceivedCbat) 3000L else entry.intervalMs
                val interval = ((if (navBusy && baseInterval >= 8000L) baseInterval * 2L else baseInterval) *
                    if (bleBusy) 2L else 1L)
                val score = (now - entry.lastSentMs).toDouble() / interval.toDouble()
                if (score >= bestScore) {
                    best = entry
                    bestScore = score
                }
            }
            if (best != null) {
                best.lastSentMs = now
                enqueueCoalesced(best.cmd)
            }
        }, 2200, 180, TimeUnit.MILLISECONDS)
    }

    private fun stopPolling() {
        pollFuture?.cancel(false)
        pollFuture = null
        timeFuture?.cancel(false)
        timeFuture = null
    }

    private fun closeSocketOnly() {
        reading = false
        connected = false
        stopPolling()
        stopWriter()
        synchronized(fdFrameBuf) { fdFrameBuf.clear() }
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
