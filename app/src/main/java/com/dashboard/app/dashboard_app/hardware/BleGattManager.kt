package com.dashboard.app.dashboard_app.hardware

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
import com.dashboard.app.dashboard_app.engine.TelemetryEngine
import com.dashboard.app.dashboard_app.model.Battery2Data
import com.dashboard.app.dashboard_app.model.ConnectionStateX
import com.dashboard.app.dashboard_app.model.DiscoveredDeviceX
import com.dashboard.app.dashboard_app.model.Esp32Data
import com.dashboard.app.dashboard_app.model.JkBmsData
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class BleGattManager(private val context: Context) {
    companion object {
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val JK_SVC_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        private val JK_NOTIFY_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        private val JK_WRITE_UUID: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")

        private val NUS_SVC_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_WRITE_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_NOTIFY_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    }

    var onLog: ((String, String, Boolean) -> Unit)? = null
    var onScanResults: ((List<DiscoveredDeviceX>) -> Unit)? = null
    var onScanStateChanged: ((Boolean) -> Unit)? = null

    var onJkStateChanged: ((ConnectionStateX, String) -> Unit)? = null
    var onJkData: ((JkBmsData) -> Unit)? = null

    var onEsp1StateChanged: ((ConnectionStateX, String) -> Unit)? = null
    var onEsp1Data: ((Esp32Data) -> Unit)? = null
    var onMatrixDone: (() -> Unit)? = null

    var onEsp2StateChanged: ((ConnectionStateX, String) -> Unit)? = null
    var onBattery2Data: ((Battery2Data) -> Unit)? = null

    var onMotorBleStateChanged: ((ConnectionStateX, String) -> Unit)? = null
    var onMotorBleLine: ((String) -> Unit)? = null

    @Volatile
    var invertB2Current: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    private var scanCb: ScanCallback? = null
    private val foundMap = LinkedHashMap<String, DiscoveredDeviceX>()

    private val gatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val writeChars = ConcurrentHashMap<String, BluetoothGattCharacteristic>()

    private var jkPollFuture: ScheduledFuture<*>? = null
    private val jkFrameBuf = mutableListOf<Byte>()

    private val esp1Rx = StringBuilder()
    private var esp1Cur = Esp32Data()

    private val esp2Rx = StringBuilder()
    private var b2Cur = Battery2Data()

    private val motorBleRx = StringBuilder()
    private var motorBlePollFuture: ScheduledFuture<*>? = null
    private var motorBleCycle = 0

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @SuppressLint("MissingPermission")
    fun startScan(timeoutMs: Long = 7000L) {
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            mainHandler.post { onScanStateChanged?.invoke(false) }
            return
        }
        stopScan()
        foundMap.clear()
        mainHandler.post {
            onScanStateChanged?.invoke(true)
            onLog?.invoke("BLE", "Memulai pemindaian BLE…", false)
        }

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                val addr = dev.address ?: return
                val name = try {
                    dev.name ?: result.scanRecord?.deviceName ?: "BLE Device"
                } catch (_: SecurityException) {
                    "BLE Device"
                }
                synchronized(foundMap) {
                    foundMap[addr] = DiscoveredDeviceX(
                        name = name,
                        address = addr,
                        isBle = true,
                        rssi = result.rssi
                    )
                    val sorted = foundMap.values.sortedByDescending { it.rssi }
                    mainHandler.post { onScanResults?.invoke(sorted) }
                }
            }
        }
        scanCb = cb
        try {
            bt.bluetoothLeScanner?.startScan(cb)
            mainHandler.postDelayed({ stopScan() }, timeoutMs)
        } catch (e: Exception) {
            scanCb = null
            mainHandler.post {
                onScanStateChanged?.invoke(false)
                onLog?.invoke("BLE", "Gagal scan BLE: ${e.message}", true)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val cb = scanCb
        if (cb != null) {
            scanCb = null
            try {
                adapter?.bluetoothLeScanner?.stopScan(cb)
            } catch (_: Exception) {
            }
        }
        mainHandler.post { onScanStateChanged?.invoke(false) }
    }

    fun connectJkBms(address: String) = connectSlot("jk", address)
    fun disconnectJkBms() = disconnectSlot("jk")

    fun connectEspSlot(slot: Int, address: String) =
        connectSlot(if (slot == 1) "esp1" else "esp2", address)

    fun disconnectEspSlot(slot: Int) =
        disconnectSlot(if (slot == 1) "esp1" else "esp2")

    fun connectMotorBle(address: String) = connectSlot("motor", address)
    fun disconnectMotorBle() = disconnectSlot("motor")

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
        emitSlotState(slot, ConnectionStateX.CONNECTING, devName)
        mainHandler.post {
            onLog?.invoke(slotLabel(slot), "Menghubungkan $address…", false)
        }

        val gatt = dev.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    disconnectSlot(slot)
                    mainHandler.post {
                        onLog?.invoke(slotLabel(slot), "${slotLabel(slot)} terputus.", true)
                    }
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
                    mainHandler.post {
                        onLog?.invoke(slotLabel(slot), "Layanan GATT tidak ditemukan.", true)
                    }
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

                emitSlotState(slot, ConnectionStateX.CONNECTED, devName)
                mainHandler.post {
                    onLog?.invoke(slotLabel(slot), "Terhubung ke $devName", false)
                }

                if (slot == "jk") {
                    startJkPolling()
                } else if (slot == "motor") {
                    startMotorBlePolling()
                }
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION")
                val data = ch.value ?: return
                handleData(slot, data)
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                handleData(slot, value)
            }
        }, BluetoothDevice.TRANSPORT_LE)

        if (gatt != null) gatts[slot] = gatt
    }

    private fun startJkPolling() {
        jkPollFuture?.cancel(false)
        scheduler.schedule({
            writeSlot("jk", TelemetryEngine.buildJkCommandFrame(0x97))
        }, 200, TimeUnit.MILLISECONDS)
        jkPollFuture = scheduler.scheduleAtFixedRate({
            writeSlot("jk", TelemetryEngine.buildJkCommandFrame(0x96))
        }, 650, 2500, TimeUnit.MILLISECONDS)
    }

    private fun startMotorBlePolling() {
        motorBlePollFuture?.cancel(false)
        motorBleCycle = 0
        motorBlePollFuture = scheduler.scheduleAtFixedRate({
            val cmds = mutableListOf("AT+SPD?\r\n", "AT+VBAT?\r\n", "AT+IBAT?\r\n")
            if (motorBleCycle % 2 == 0) cmds.add("AT+SOC?\r\n")
            if (motorBleCycle % 4 == 0) cmds.add("AT+ODO?\r\n")
            motorBleCycle++
            cmds.forEach { cmd ->
                writeMotorBle(cmd)
                try {
                    Thread.sleep(45)
                } catch (_: InterruptedException) {
                }
            }
        }, 250, 550, TimeUnit.MILLISECONDS)
    }

    private fun handleData(slot: String, bytes: ByteArray) {
        when (slot) {
            "jk" -> onJkBytes(bytes)
            "esp1" -> handleLineStream(esp1Rx, bytes) { parseEsp1Line(it) }
            "esp2" -> handleLineStream(esp2Rx, bytes) { parseEsp2Line(it) }
            "motor" -> handleLineStream(motorBleRx, bytes) { line ->
                mainHandler.post { onMotorBleLine?.invoke(line) }
            }
        }
    }

    private fun onJkBytes(chunk: ByteArray) {
        synchronized(jkFrameBuf) {
            if (chunk.size >= 4 &&
                (chunk[0].toInt() and 0xFF) == 0x55 &&
                (chunk[1].toInt() and 0xFF) == 0xAA &&
                (chunk[2].toInt() and 0xFF) == 0xEB &&
                (chunk[3].toInt() and 0xFF) == 0x90
            ) {
                jkFrameBuf.clear()
            }
            chunk.forEach { jkFrameBuf.add(it) }
            if (jkFrameBuf.size >= 300) {
                val frame = jkFrameBuf.subList(0, 300).toByteArray()
                jkFrameBuf.clear()
                val parsed = TelemetryEngine.parseJkBms300ByteFrame(
                    frame,
                    onError = { err -> mainHandler.post { onLog?.invoke("JKBMS", err, true) } }
                )
                if (parsed != null) {
                    mainHandler.post { onJkData?.invoke(parsed) }
                }
            }
        }
    }

    private fun handleLineStream(sb: StringBuilder, bytes: ByteArray, onLine: (String) -> Unit) {
        val text = String(bytes, Charsets.UTF_8)
        synchronized(sb) {
            sb.append(text)
            while (true) {
                val idx = sb.indexOf("\n")
                if (idx < 0) break
                val line = sb.substring(0, idx).trim()
                sb.delete(0, idx + 1)
                if (line.isNotEmpty()) onLine(line)
            }
        }
    }

    private fun parseEsp1Line(line: String) {
        mainHandler.post { onLog?.invoke("ESP1_RX", line, false) }
        if (line.uppercase(Locale.US) == "MATRIX_DONE") {
            esp1Cur = esp1Cur.copy(matrixBusy = false)
            val snap = esp1Cur
            mainHandler.post {
                onEsp1Data?.invoke(snap)
                onMatrixDone?.invoke()
            }
            return
        }
        val eq = line.indexOf('=')
        if (eq <= 0) return
        val key = line.substring(0, eq).trim().uppercase(Locale.US)
        val valStr = line.substring(eq + 1).trim()
        val numVal = valStr.toDoubleOrNull()
        val now = System.currentTimeMillis()
        val isOn = valStr == "1" || valStr.equals("ON", ignoreCase = true)

        esp1Cur = when (key) {
            "SPD" -> esp1Cur.copy(speedKmh = numVal, lastUpdatedMs = now)
            "TRT" -> esp1Cur.copy(throttlePct = numVal?.coerceIn(0.0, 100.0), lastUpdatedMs = now)
            "CRZ" -> esp1Cur.copy(cruiseActive = isOn, lastUpdatedMs = now)
            "REM" -> esp1Cur.copy(reverseActive = isOn, lastUpdatedMs = now)
            "STD" -> esp1Cur.copy(sideStandDown = isOn, lastUpdatedMs = now)
            "MODE" -> esp1Cur.copy(modeIndex = numVal?.toInt(), lastUpdatedMs = now)
            "PARK" -> esp1Cur.copy(isPark = valStr == "1", lastUpdatedMs = now)
            "CONTACT" -> esp1Cur.copy(contactOn = isOn, lastUpdatedMs = now)
            "MATRIX" -> esp1Cur.copy(matrixOn = isOn, lastUpdatedMs = now)
            "MUSIC_TRACK" -> esp1Cur.copy(musicTrack = numVal?.toInt() ?: esp1Cur.musicTrack, lastUpdatedMs = now)
            "MUSIC_STATE" -> esp1Cur.copy(musicState = valStr.uppercase(Locale.US), lastUpdatedMs = now)
            "MUSIC_VOL" -> esp1Cur.copy(musicVol = numVal?.toInt() ?: esp1Cur.musicVol, lastUpdatedMs = now)
            else -> esp1Cur
        }
        val snap = esp1Cur
        mainHandler.post { onEsp1Data?.invoke(snap) }
    }

    private fun parseEsp2Line(line: String) {
        mainHandler.post { onLog?.invoke("ESP2_RX", line, false) }
        val eq = line.indexOf('=')
        if (eq <= 0) return
        val key = line.substring(0, eq).trim().uppercase(Locale.US)
        val valStr = line.substring(eq + 1).trim()
        val numVal = valStr.toDoubleOrNull()
        val now = System.currentTimeMillis()

        b2Cur = when (key) {
            "VBAT", "PACK_V", "V2" -> b2Cur.copy(vbat = numVal, lastUpdatedMs = now)
            "IBAT", "PACK_I", "I2" -> b2Cur.copy(
                ibat = numVal?.let { if (invertB2Current) -it else it },
                lastUpdatedMs = now
            )
            "PWR", "P2" -> b2Cur.copy(powerW = numVal, lastUpdatedMs = now)
            "SOC", "SOC2" -> b2Cur.copy(socPct = numVal?.coerceIn(0.0, 100.0), lastUpdatedMs = now)
            "SOH", "SOH2" -> b2Cur.copy(sohPct = numVal?.coerceIn(0.0, 100.0), lastUpdatedMs = now)
            "TBAT", "TEMP", "T2" -> b2Cur.copy(tempC = numVal, lastUpdatedMs = now)
            "DELTA", "DCELL" -> b2Cur.copy(deltaCellV = numVal, lastUpdatedMs = now)
            "CELLS" -> parseB2Cells(valStr, now)
            "CYCLES", "CBAT" -> b2Cur.copy(cycles = numVal?.toInt(), lastUpdatedMs = now)
            "REMAIN_AH" -> b2Cur.copy(remainAh = numVal, lastUpdatedMs = now)
            "CANMODE" -> b2Cur.copy(canMode = valStr.uppercase(Locale.US), lastUpdatedMs = now)
            "SIMMODE" -> b2Cur.copy(simMode = valStr, lastUpdatedMs = now)
            else -> b2Cur
        }
        val snap = b2Cur
        mainHandler.post { onBattery2Data?.invoke(snap) }
    }

    private fun parseB2Cells(valStr: String, now: Long): Battery2Data {
        val parsed = valStr.split(",").mapNotNull { it.trim().toDoubleOrNull() }
        val delta = if (parsed.size >= 2) parsed.max() - parsed.min() else b2Cur.deltaCellV
        return b2Cur.copy(
            cellCount = parsed.size,
            cellVoltages = parsed,
            deltaCellV = delta,
            lastUpdatedMs = now
        )
    }

    fun sendEspCommand(slot: Int, cmd: String) {
        val slotKey = if (slot == 1) "esp1" else "esp2"
        val normalized = if (cmd.endsWith("\n")) cmd else "$cmd\n"
        val ok = writeSlot(slotKey, normalized.toByteArray(Charsets.UTF_8))
        mainHandler.post {
            if (ok) {
                onLog?.invoke("ESP32#$slot", "TX: ${cmd.trim()}", false)
            } else {
                onLog?.invoke("ESP32#$slot", "Perangkat belum terhubung.", true)
            }
        }
    }

    fun writeMotorBle(text: String): Boolean =
        writeSlot("motor", text.toByteArray(Charsets.UTF_8))

    suspend fun sendMotorBleMapsFrame(
        b64: String,
        chunkSize: Int = 100,
        delayMs: Int = 30
    ) {
        val cs = chunkSize.coerceIn(20, 400)
        writeSlot("motor", "AT+MAPS=\"".toByteArray(Charsets.UTF_8), noResponse = true)
        delay(45)
        var i = 0
        while (i < b64.length) {
            val end = (i + cs).coerceAtMost(b64.length)
            writeSlot("motor", b64.substring(i, end).toByteArray(Charsets.UTF_8), noResponse = true)
            if (delayMs > 0) delay(delayMs.toLong())
            i = end
        }
        writeSlot("motor", "\"\r\n".toByteArray(Charsets.UTF_8), noResponse = true)
    }

    @SuppressLint("MissingPermission")
    private fun writeSlot(slot: String, bytes: ByteArray, noResponse: Boolean = false): Boolean {
        val g = gatts[slot] ?: return false
        val ch = writeChars[slot] ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val writeType = if (noResponse || (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
                g.writeCharacteristic(ch, bytes, writeType) == 0
            } else {
                @Suppress("DEPRECATION")
                ch.value = bytes
                @Suppress("DEPRECATION")
                if (noResponse) ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnectSlot(slot: String) {
        if (slot == "jk") {
            jkPollFuture?.cancel(false)
            jkPollFuture = null
            synchronized(jkFrameBuf) { jkFrameBuf.clear() }
        } else if (slot == "motor") {
            motorBlePollFuture?.cancel(false)
            motorBlePollFuture = null
            synchronized(motorBleRx) { motorBleRx.clear() }
        } else if (slot == "esp1") {
            synchronized(esp1Rx) { esp1Rx.clear() }
        } else if (slot == "esp2") {
            synchronized(esp2Rx) { esp2Rx.clear() }
        }
        writeChars.remove(slot)
        val g = gatts.remove(slot)
        try {
            g?.disconnect()
            g?.close()
        } catch (_: Exception) {
        }
        emitSlotState(slot, ConnectionStateX.DISCONNECTED, "")
    }

    private fun slotLabel(slot: String): String = when (slot) {
        "jk" -> "JKBMS"
        "esp1" -> "ESP32#1"
        "esp2" -> "ESP32#2"
        else -> "MOTOR_BLE"
    }

    private fun emitSlotState(slot: String, state: ConnectionStateX, name: String) {
        mainHandler.post {
            when (slot) {
                "jk" -> onJkStateChanged?.invoke(state, name)
                "esp1" -> onEsp1StateChanged?.invoke(state, name)
                "esp2" -> onEsp2StateChanged?.invoke(state, name)
                "motor" -> onMotorBleStateChanged?.invoke(state, name)
            }
        }
    }

    fun dispose() {
        stopScan()
        disconnectSlot("jk")
        disconnectSlot("esp1")
        disconnectSlot("esp2")
        disconnectSlot("motor")
        scheduler.shutdownNow()
    }
}
