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
import com.dashboard.app.dashboard_app.model.BmsProtocol
import com.dashboard.app.dashboard_app.model.ConnectionStateX
import com.dashboard.app.dashboard_app.model.ControllerProtocol
import com.dashboard.app.dashboard_app.model.DiscoveredDeviceX
import com.dashboard.app.dashboard_app.model.Esp32Data
import com.dashboard.app.dashboard_app.model.FardriverData
import com.dashboard.app.dashboard_app.model.JkBmsData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class BleGattManager(private val context: Context) {
    companion object {
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val JK_SVC_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        private val JK_NOTIFY_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        private val JK_ALT_WRITE_UUID: UUID = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb")
        private val JK_WRITE_UUID: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")

        private val DALY_SVC_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        private val DALY_NOTIFY_UUID: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        private val DALY_WRITE_UUID: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")

        private val JBD_SVC_UUID: UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
        private val JBD_NOTIFY_UUID: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
        private val JBD_WRITE_UUID: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")

        private val NUS_SVC_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_WRITE_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_NOTIFY_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

        private const val CONNECT_TIMEOUT_MS = 15_000L
        private val RECONNECT_DELAYS_MS = longArrayOf(3_000L, 5_000L, 8_000L, 12_000L, 20_000L)
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
    var onFardriverData: ((FardriverData) -> Unit)? = null
    var onSlotRssi: ((String, Int) -> Unit)? = null

    @Volatile
    var invertB2Current: Boolean = false

    @Volatile
    var activeBmsProtocol: BmsProtocol = BmsProtocol.JIKONG

    @Volatile
    var activeControllerProtocol: ControllerProtocol = ControllerProtocol.MID_AT

    @Volatile
    var fardriverPolePairs: Int = 20

    @Volatile
    var fardriverWheelCircMm: Int = 1580

    @Volatile
    var fardriverCurrentScale: Double = 0.25

    @Volatile
    var isMotorBleMapsSending: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    /** MTU hasil negosiasi per slot (default ATT = 23 bila belum/tidak berhasil dinegosiasi). */
    private val slotMtu = ConcurrentHashMap<String, Int>()

    /** Dilepas tiap onCharacteristicWrite slot motor: kendali alur agar chunk tidak saling menimpa. */
    private val motorWriteSem = Semaphore(0)

    private var scanCb: ScanCallback? = null
    private val foundMap = LinkedHashMap<String, DiscoveredDeviceX>()

    private val gatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val writeChars = ConcurrentHashMap<String, BluetoothGattCharacteristic>()
    private val slotGenerations = ConcurrentHashMap<String, Int>()
    private val slotManualStop = ConcurrentHashMap<String, Boolean>()
    private val slotLastMac = ConcurrentHashMap<String, String>()
    private val slotReconnectAttempts = ConcurrentHashMap<String, Int>()
    private val slotTimeoutRunnables = ConcurrentHashMap<String, Runnable>()
    private val slotReconnectRunnables = ConcurrentHashMap<String, Runnable>()

    private var jkPollFuture: ScheduledFuture<*>? = null
    private val jkFrameBuf = mutableListOf<Byte>()
    private var bmsCur = JkBmsData()
    private val dalyCellAcc = mutableMapOf<Int, Double>()
    private var antReplyV2 = false
    private var antPollCount = 0
    private var jbdToggle = false
    private var dalyCmdIdx = 0
    @Volatile
    private var jkAwaitingResponse: Boolean = false
    private var jkAwaitTimeoutRunnable: Runnable? = null

    private val fdFrameBuf = mutableListOf<Byte>()
    private var fardriverCur = FardriverData()
    private var fardriverPollFuture: ScheduledFuture<*>? = null

    private val esp1Rx = StringBuilder()
    private var esp1Cur = Esp32Data()

    private val esp2Rx = StringBuilder()
    private var b2Cur = Battery2Data()

    private data class PollEntry(val cmd: String, val intervalMs: Long, var lastSentMs: Long = 0L)

    private val motorBleSchedule = listOf(
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

    private val motorBleRx = StringBuilder()
    private var motorBlePollFuture: ScheduledFuture<*>? = null
    private var motorBleTimeFuture: ScheduledFuture<*>? = null
    @Volatile
    private var motorBleCbatReceived: Boolean = false
    private var rssiPollFuture: ScheduledFuture<*>? = null

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

    private fun connectSlot(slot: String, address: String) {
        slotManualStop[slot] = false
        slotLastMac[slot] = address
        slotReconnectAttempts[slot] = 0
        cancelReconnect(slot)
        openSlotGatt(slot, address)
    }

    @SuppressLint("MissingPermission")
    private fun openSlotGatt(slot: String, address: String) {
        val gen = (slotGenerations[slot] ?: 0) + 1
        slotGenerations[slot] = gen
        closeSlotOnly(slot, emitDisconnect = false)
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
        val gateKey = slotLabel(slot)
        val waitMsg = BleConnectGate.waitingFor()
        emitSlotState(slot, ConnectionStateX.CONNECTING, devName)
        mainHandler.post {
            val suffix = if (waitMsg.isEmpty()) "" else " ($waitMsg)"
            onLog?.invoke(gateKey, "Menghubungkan $address$suffix…", false)
        }

        BleConnectGate.setAbortHook(gateKey) {
            if (slotGenerations[slot] == gen) {
                closeSlotOnly(slot, emitDisconnect = true)
                scheduleReconnect(slot)
            }
        }

        BleConnectGate.acquire(gateKey) {
            if (slotGenerations[slot] != gen || slotManualStop[slot] == true) {
                BleConnectGate.release(gateKey)
                return@acquire
            }

            val timeoutTask = Runnable {
                if (slotGenerations[slot] != gen) return@Runnable
                mainHandler.post {
                    onLog?.invoke(gateKey, "Timeout setup BLE > 15 detik, reset GATT", true)
                }
                closeSlotOnly(slot, emitDisconnect = true)
                BleConnectGate.release(gateKey)
                scheduleReconnect(slot)
            }
            slotTimeoutRunnables[slot] = timeoutTask
            mainHandler.postDelayed(timeoutTask, CONNECT_TIMEOUT_MS)

            val gatt = dev.connectGatt(context, false, object : BluetoothGattCallback() {
                private var discovered = false

                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (slotGenerations[slot] != gen) {
                        try { g.close() } catch (_: Exception) {}
                        return
                    }
                    if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                        val mtuRequested = try {
                            g.requestMtu(247)
                        } catch (_: Exception) {
                            false
                        }
                        if (!mtuRequested) {
                            discovered = true
                            g.discoverServices()
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                        clearConnectTimeout(slot)
                        BleConnectGate.release(gateKey)
                        closeSlotOnly(slot, emitDisconnect = true)
                        mainHandler.post {
                            onLog?.invoke(gateKey, "$gateKey terputus (status=$status).", true)
                        }
                        if (slotManualStop[slot] != true) {
                            scheduleReconnect(slot)
                        }
                    }
                }

                override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                    if (slotGenerations[slot] != gen) return
                    if (status == BluetoothGatt.GATT_SUCCESS) slotMtu[slot] = mtu
                    if (!discovered) {
                        discovered = true
                        g.discoverServices()
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (slotGenerations[slot] != gen) return
                    val isNus = slot == "esp1" || slot == "esp2"
                    var notifyCh: BluetoothGattCharacteristic? = null
                    var writeCh: BluetoothGattCharacteristic? = null

                    fun isNotifyOrIndicate(ch: BluetoothGattCharacteristic): Boolean =
                        (ch.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0

                    fun isWritable(ch: BluetoothGattCharacteristic): Boolean =
                        (ch.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0

                    if (isNus) {
                        val svc = g.getService(NUS_SVC_UUID)
                        notifyCh = svc?.getCharacteristic(NUS_NOTIFY_UUID)
                        writeCh = svc?.getCharacteristic(NUS_WRITE_UUID)
                    } else if (slot == "jk") {
                        when (activeBmsProtocol) {
                            BmsProtocol.JBD -> {
                                val jbdSvc = g.getService(JBD_SVC_UUID)
                                notifyCh = jbdSvc?.getCharacteristic(JBD_NOTIFY_UUID)
                                writeCh = jbdSvc?.getCharacteristic(JBD_WRITE_UUID)
                            }
                            BmsProtocol.DALY -> {
                                val dalySvc = g.getService(DALY_SVC_UUID)
                                if (dalySvc != null) {
                                    notifyCh = dalySvc.getCharacteristic(DALY_NOTIFY_UUID)
                                        ?: dalySvc.characteristics.firstOrNull(::isNotifyOrIndicate)
                                    writeCh = dalySvc.getCharacteristic(DALY_WRITE_UUID)
                                        ?: dalySvc.characteristics.firstOrNull(::isWritable)
                                }
                                if (notifyCh == null) {
                                    val ffeSvc = g.getService(JK_SVC_UUID)
                                    notifyCh = ffeSvc?.getCharacteristic(JK_NOTIFY_UUID)
                                        ?: ffeSvc?.characteristics?.firstOrNull(::isNotifyOrIndicate)
                                    writeCh = ffeSvc?.getCharacteristic(JK_ALT_WRITE_UUID)
                                        ?: ffeSvc?.getCharacteristic(JK_WRITE_UUID)
                                        ?: ffeSvc?.characteristics?.firstOrNull(::isWritable)
                                }
                            }
                            BmsProtocol.ANT -> {
                                val ffeSvc = g.getService(JK_SVC_UUID)
                                if (ffeSvc != null) {
                                    notifyCh = ffeSvc.getCharacteristic(JK_NOTIFY_UUID)
                                        ?: ffeSvc.characteristics.firstOrNull(::isNotifyOrIndicate)
                                    writeCh = ffeSvc.getCharacteristic(JK_NOTIFY_UUID)
                                        ?: ffeSvc.getCharacteristic(JK_ALT_WRITE_UUID)
                                        ?: ffeSvc.characteristics.firstOrNull(::isWritable)
                                }
                                if (notifyCh == null) {
                                    val fffSvc = g.getService(DALY_SVC_UUID)
                                    notifyCh = fffSvc?.getCharacteristic(DALY_NOTIFY_UUID)
                                        ?: fffSvc?.characteristics?.firstOrNull(::isNotifyOrIndicate)
                                    writeCh = fffSvc?.getCharacteristic(DALY_WRITE_UUID)
                                        ?: fffSvc?.characteristics?.firstOrNull(::isWritable)
                                }
                            }
                            BmsProtocol.JIKONG -> {
                                // Some JK-BMS devices declare FFE0 twice where FFE1/FFF2 live on the second instance.
                                for (s in g.services) {
                                    if (s.uuid == JK_SVC_UUID) {
                                        if (notifyCh == null) notifyCh = s.getCharacteristic(JK_NOTIFY_UUID)
                                        if (writeCh == null) writeCh = s.getCharacteristic(JK_WRITE_UUID)
                                        if (notifyCh != null && writeCh != null) break
                                    }
                                }
                            }
                        }
                    } else if (slot == "motor") {
                        for (s in g.services) {
                            if (s.uuid == JK_SVC_UUID) {
                                notifyCh = notifyCh ?: s.getCharacteristic(JK_NOTIFY_UUID)
                                writeCh = writeCh ?: s.getCharacteristic(JK_WRITE_UUID) ?: s.getCharacteristic(JK_ALT_WRITE_UUID) ?: notifyCh
                            }
                        }
                        if (notifyCh == null) {
                            val jbdSvc = g.getService(JBD_SVC_UUID)
                            if (jbdSvc != null) {
                                notifyCh = jbdSvc.getCharacteristic(JBD_NOTIFY_UUID)
                                writeCh = jbdSvc.getCharacteristic(JBD_WRITE_UUID) ?: jbdSvc.characteristics.firstOrNull(::isWritable)
                            }
                        }
                        if (notifyCh == null) {
                            val dalySvc = g.getService(DALY_SVC_UUID)
                            if (dalySvc != null) {
                                notifyCh = dalySvc.getCharacteristic(DALY_NOTIFY_UUID)
                                writeCh = dalySvc.getCharacteristic(DALY_WRITE_UUID) ?: dalySvc.characteristics.firstOrNull(::isWritable)
                            }
                        }
                        if (notifyCh == null) {
                            val nusSvc = g.getService(NUS_SVC_UUID)
                            if (nusSvc != null) {
                                notifyCh = nusSvc.getCharacteristic(NUS_NOTIFY_UUID)
                                writeCh = nusSvc.getCharacteristic(NUS_WRITE_UUID)
                            }
                        }
                    }

                    // Generic fallback across all discovered GATT services
                    if (notifyCh == null) {
                        val fallbackSvc = g.services.firstOrNull { s ->
                            s.characteristics.any(::isNotifyOrIndicate)
                        }
                        if (fallbackSvc != null) {
                            notifyCh = fallbackSvc.getCharacteristic(JK_NOTIFY_UUID)
                                ?: fallbackSvc.characteristics.firstOrNull(::isNotifyOrIndicate)
                            writeCh = writeCh
                                ?: fallbackSvc.getCharacteristic(JK_WRITE_UUID)
                                ?: fallbackSvc.characteristics.firstOrNull(::isWritable)
                                ?: notifyCh
                        }
                    } else if (writeCh == null) {
                        writeCh = notifyCh
                    }

                    if (notifyCh == null) {
                        clearConnectTimeout(slot)
                        BleConnectGate.release(gateKey)
                        mainHandler.post {
                            onLog?.invoke(gateKey, "Layanan GATT / characteristic notify tidak ditemukan.", true)
                        }
                        closeSlotOnly(slot, emitDisconnect = true)
                        return
                    }
                    if (writeCh != null) writeChars[slot] = writeCh

                    var waitingCccd = false
                    g.setCharacteristicNotification(notifyCh, true)
                    val cccd = notifyCh.getDescriptor(CCCD_UUID)
                    if (cccd != null) {
                        val useIndicate = (notifyCh.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0 &&
                            (notifyCh.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                        val cccdVal = if (useIndicate) {
                            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        } else {
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        }
                        waitingCccd = true
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            g.writeDescriptor(cccd, cccdVal)
                        } else {
                            @Suppress("DEPRECATION")
                            cccd.value = cccdVal
                            @Suppress("DEPRECATION")
                            g.writeDescriptor(cccd)
                        }
                    }

                    if (!waitingCccd) {
                        finishSetup(slot, gateKey, devName)
                    }
                }

                override fun onCharacteristicWrite(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    if (slot == "motor") motorWriteSem.release()
                }

                override fun onDescriptorWrite(
                    g: BluetoothGatt,
                    descriptor: BluetoothGattDescriptor,
                    status: Int
                ) {
                    if (slotGenerations[slot] != gen) return
                    finishSetup(slot, gateKey, devName)
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

                override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
                    if (slotGenerations[slot] == gen && status == BluetoothGatt.GATT_SUCCESS) {
                        mainHandler.post { onSlotRssi?.invoke(slot, rssi) }
                    }
                }
            }, BluetoothDevice.TRANSPORT_LE)

            if (gatt != null) {
                gatts[slot] = gatt
            } else {
                clearConnectTimeout(slot)
                BleConnectGate.release(gateKey)
                emitSlotState(slot, ConnectionStateX.DISCONNECTED, "")
            }
        }
    }

    private fun finishSetup(slot: String, gateKey: String, devName: String) {
        clearConnectTimeout(slot)
        slotReconnectAttempts[slot] = 0
        BleConnectGate.release(gateKey)
        emitSlotState(slot, ConnectionStateX.CONNECTED, devName)
        mainHandler.post {
            onLog?.invoke(gateKey, "Terhubung ke $devName", false)
        }
        if (slot == "jk") {
            startJkPolling()
        } else if (slot == "motor") {
            startMotorBlePolling()
        }
        startRssiPolling()
    }

    private fun clearConnectTimeout(slot: String) {
        slotTimeoutRunnables.remove(slot)?.let { mainHandler.removeCallbacks(it) }
    }

    private fun cancelReconnect(slot: String) {
        slotReconnectRunnables.remove(slot)?.let { mainHandler.removeCallbacks(it) }
    }

    private fun scheduleReconnect(slot: String) {
        val mac = slotLastMac[slot] ?: return
        if (slotManualStop[slot] == true) return
        val attempt = slotReconnectAttempts[slot] ?: 0
        if (attempt >= 8) return
        slotReconnectAttempts[slot] = attempt + 1
        val baseDelay = RECONNECT_DELAYS_MS[attempt.coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)]
        val delayMs = BleCoex.stretch(baseDelay)
        cancelReconnect(slot)
        val task = Runnable {
            if (slotManualStop[slot] != true && gatts[slot] == null) {
                openSlotGatt(slot, mac)
            }
        }
        slotReconnectRunnables[slot] = task
        mainHandler.postDelayed(task, delayMs)
    }

    private fun armJkAwaitGuard() {
        jkAwaitingResponse = true
        jkAwaitTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            if (jkAwaitingResponse) {
                jkAwaitingResponse = false
                synchronized(jkFrameBuf) { jkFrameBuf.clear() }
            }
        }
        jkAwaitTimeoutRunnable = r
        mainHandler.postDelayed(r, 3000L)
    }

    private fun clearJkAwaitGuard() {
        jkAwaitingResponse = false
        jkAwaitTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        jkAwaitTimeoutRunnable = null
    }

    private fun startJkPolling() {
        jkPollFuture?.cancel(false)
        clearJkAwaitGuard()
        synchronized(jkFrameBuf) { jkFrameBuf.clear() }
        synchronized(dalyCellAcc) { dalyCellAcc.clear() }
        antReplyV2 = false
        antPollCount = 0
        jbdToggle = false
        dalyCmdIdx = 0

        when (activeBmsProtocol) {
            BmsProtocol.JIKONG -> {
                // Mandatory JK-BMS handshake: 0x97 (Device Info) first, wait 1500ms so its
                // 300-byte settings response finishes before requesting 0x96 (Cell Info).
                scheduler.schedule({
                    if (gatts["jk"] != null) {
                        armJkAwaitGuard()
                        writeSlot("jk", TelemetryEngine.buildJkCommandFrame(0x97), noResponse = true)
                    }
                }, 200, TimeUnit.MILLISECONDS)

                jkPollFuture = scheduler.scheduleAtFixedRate({
                    if (gatts["jk"] == null) return@scheduleAtFixedRate
                    if (jkAwaitingResponse) return@scheduleAtFixedRate
                    armJkAwaitGuard()
                    writeSlot("jk", TelemetryEngine.buildJkCommandFrame(0x96), noResponse = true)
                }, 1700, 2500, TimeUnit.MILLISECONDS)
            }
            BmsProtocol.ANT -> {
                jkPollFuture = scheduler.scheduleAtFixedRate({
                    if (gatts["jk"] == null) return@scheduleAtFixedRate
                    val cmd = if (antReplyV2 || antPollCount % 4 == 3) {
                        TelemetryEngine.ANT_V2_POLL_CMD
                    } else {
                        TelemetryEngine.ANT_LEGACY_POLL_CMD
                    }
                    antPollCount++
                    writeSlot("jk", cmd, noResponse = true)
                }, 300, 1500, TimeUnit.MILLISECONDS)
            }
            BmsProtocol.DALY -> {
                val cmds = intArrayOf(0x90, 0x91, 0x92, 0x93, 0x94, 0x95, 0x96)
                jkPollFuture = scheduler.scheduleAtFixedRate({
                    if (gatts["jk"] == null) return@scheduleAtFixedRate
                    val cmd = cmds[dalyCmdIdx % cmds.size]
                    dalyCmdIdx++
                    writeSlot("jk", TelemetryEngine.buildDalyCommandFrame(cmd), noResponse = true)
                }, 300, 260, TimeUnit.MILLISECONDS)
            }
            BmsProtocol.JBD -> {
                jkPollFuture = scheduler.scheduleAtFixedRate({
                    if (gatts["jk"] == null) return@scheduleAtFixedRate
                    val cmd = if (jbdToggle) {
                        TelemetryEngine.JBD_READ_CELLS_CMD
                    } else {
                        TelemetryEngine.JBD_READ_BASIC_CMD
                    }
                    jbdToggle = !jbdToggle
                    writeSlot("jk", cmd, noResponse = true)
                }, 300, 750, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun startRssiPolling() {
        if (rssiPollFuture != null) return
        rssiPollFuture = scheduler.scheduleAtFixedRate({
            gatts.values.forEach { g ->
                try {
                    @Suppress("MissingPermission")
                    g.readRemoteRssi()
                } catch (_: Exception) {
                }
            }
        }, 2500, 3500, TimeUnit.MILLISECONDS)
    }

    private fun sendMotorBleTimeNow() {
        val cal = Calendar.getInstance()
        val hh = cal.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
        val mm = cal.get(Calendar.MINUTE).toString().padStart(2, '0')
        writeMotorBle("AT+TIME=\"$hh$mm\"\r\n")
    }

    private fun startMotorBlePolling() {
        motorBlePollFuture?.cancel(false)
        motorBleTimeFuture?.cancel(false)
        fardriverPollFuture?.cancel(false)
        motorBleCbatReceived = false
        motorBleSchedule.forEach { it.lastSentMs = 0L }

        if (activeControllerProtocol == ControllerProtocol.FARDRIVER_BLE) {
            synchronized(fdFrameBuf) { fdFrameBuf.clear() }
            fardriverCur = FardriverData()
            scheduler.schedule({
                if (gatts["motor"] != null) {
                    writeSlot("motor", TelemetryEngine.FARDRIVER_WAKE_CMD, noResponse = true)
                }
            }, 250, TimeUnit.MILLISECONDS)
            fardriverPollFuture = scheduler.scheduleAtFixedRate({
                if (gatts["motor"] != null) {
                    writeSlot("motor", TelemetryEngine.FARDRIVER_WAKE_CMD, noResponse = true)
                }
            }, 3000, 3000, TimeUnit.MILLISECONDS)
            return
        }

        val startupCmds = listOf(
            "AT+CBAT?\r\n",
            "AT+SOH?\r\n",
            "AT+SOC?\r\n",
            "AT+ODO?\r\n",
            "AT+VBAT?\r\n",
            "AT+TBAT?\r\n"
        )
        startupCmds.forEachIndexed { idx, cmd ->
            scheduler.schedule({
                if (gatts["motor"] != null) writeMotorBle(cmd)
            }, 450L + idx * 170L, TimeUnit.MILLISECONDS)
        }

        motorBleTimeFuture = scheduler.scheduleAtFixedRate({
            if (gatts["motor"] != null) sendMotorBleTimeNow()
        }, 1800, 60_000, TimeUnit.MILLISECONDS)

        motorBlePollFuture = scheduler.scheduleAtFixedRate({
            if (gatts["motor"] == null || isMotorBleMapsSending) return@scheduleAtFixedRate
            val now = System.currentTimeMillis()
            val bleBusy = BleConnectGate.isBusy()
            var best: PollEntry? = null
            var bestScore = 1.0
            for (entry in motorBleSchedule) {
                val isUnreceivedCbat = !motorBleCbatReceived && entry.cmd.startsWith("AT+CBAT")
                if (bleBusy && entry.intervalMs > 1000L && !isUnreceivedCbat) continue
                val baseInterval = if (isUnreceivedCbat) 3000L else entry.intervalMs
                val interval = baseInterval * if (bleBusy) 2L else 1L
                val score = (now - entry.lastSentMs).toDouble() / interval.toDouble()
                if (score >= bestScore) {
                    best = entry
                    bestScore = score
                }
            }
            if (best != null) {
                best.lastSentMs = now
                writeMotorBle(best.cmd)
            }
        }, 2200, 220, TimeUnit.MILLISECONDS)
    }

    private fun handleData(slot: String, bytes: ByteArray) {
        when (slot) {
            "jk" -> onBmsBytes(bytes)
            "esp1" -> handleLineStream(esp1Rx, bytes) { parseEsp1Line(it) }
            "esp2" -> handleLineStream(esp2Rx, bytes) { parseEsp2Line(it) }
            "motor" -> if (activeControllerProtocol == ControllerProtocol.FARDRIVER_BLE) {
                onFardriverBytes(bytes)
            } else {
                handleLineStream(motorBleRx, bytes) { line ->
                    if (!motorBleCbatReceived && line.contains("CBAT", ignoreCase = true) && line.any { it.isDigit() }) {
                        motorBleCbatReceived = true
                    }
                    mainHandler.post { onMotorBleLine?.invoke(line) }
                }
            }
        }
    }

    private fun onBmsBytes(chunk: ByteArray) {
        when (activeBmsProtocol) {
            BmsProtocol.JIKONG -> onJkBytes(chunk)
            BmsProtocol.ANT -> onAntBytes(chunk)
            BmsProtocol.DALY -> onDalyBytes(chunk)
            BmsProtocol.JBD -> onJbdBytes(chunk)
        }
    }

    private fun onFardriverBytes(chunk: ByteArray) {
        synchronized(fdFrameBuf) {
            chunk.forEach { fdFrameBuf.add(it) }
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

    private fun findJkHeader(buf: List<Byte>): Int {
        for (i in 0..(buf.size - 4)) {
            if ((buf[i].toInt() and 0xFF) == 0x55 &&
                (buf[i + 1].toInt() and 0xFF) == 0xAA &&
                (buf[i + 2].toInt() and 0xFF) == 0xEB &&
                (buf[i + 3].toInt() and 0xFF) == 0x90
            ) {
                return i
            }
        }
        return -1
    }

    private fun onJkBytes(chunk: ByteArray) {
        synchronized(jkFrameBuf) {
            chunk.forEach { jkFrameBuf.add(it) }
            val hdrIdx = findJkHeader(jkFrameBuf)
            if (hdrIdx < 0) {
                if (jkFrameBuf.size > 600) {
                    jkFrameBuf.clear()
                    mainHandler.post {
                        onLog?.invoke("JKBMS", "Header 55 AA EB 90 tidak ditemukan dalam 600 byte — reset buffer.", true)
                    }
                }
                return
            }
            if (hdrIdx > 0) {
                jkFrameBuf.subList(0, hdrIdx).clear()
            }
            if (jkFrameBuf.size < 300) return

            val frame = jkFrameBuf.subList(0, 300).toByteArray()
            jkFrameBuf.subList(0, 300).clear()
            clearJkAwaitGuard()

            val parsed = TelemetryEngine.parseJkBms300ByteFrame(
                frame,
                onError = { err -> mainHandler.post { onLog?.invoke("JKBMS", err, true) } }
            )
            if (parsed != null) {
                bmsCur = parsed
                mainHandler.post { onJkData?.invoke(parsed) }
            }
        }
    }

    private fun onAntBytes(chunk: ByteArray) {
        synchronized(jkFrameBuf) {
            chunk.forEach { jkFrameBuf.add(it) }
            while (jkFrameBuf.size >= 4) {
                // Check for 140-byte legacy AA 55 AA FF / AA 55 AA 55
                var legacyIdx = -1
                for (i in 0..(jkFrameBuf.size - 4)) {
                    val b0 = jkFrameBuf[i].toInt() and 0xFF
                    val b1 = jkFrameBuf[i + 1].toInt() and 0xFF
                    val b2 = jkFrameBuf[i + 2].toInt() and 0xFF
                    val b3 = jkFrameBuf[i + 3].toInt() and 0xFF
                    if (b0 == 0xAA && b1 == 0x55 && b2 == 0xAA && (b3 == 0xFF || b3 == 0x55)) {
                        legacyIdx = i
                        break
                    }
                }
                if (legacyIdx >= 0) {
                    if (legacyIdx > 0) jkFrameBuf.subList(0, legacyIdx).clear()
                    if (jkFrameBuf.size < 140) return
                    val frame = jkFrameBuf.subList(0, 140).toByteArray()
                    jkFrameBuf.subList(0, 140).clear()
                    val parsed = TelemetryEngine.parseAntBms140ByteFrame(frame)
                    if (parsed != null) {
                        bmsCur = parsed
                        mainHandler.post { onJkData?.invoke(parsed) }
                    }
                    continue
                }

                // Check for newer 7E A1 ... AA 55 protocol
                var v2Idx = -1
                for (i in 0..(jkFrameBuf.size - 2)) {
                    if ((jkFrameBuf[i].toInt() and 0xFF) == 0x7E &&
                        (jkFrameBuf[i + 1].toInt() and 0xFF) == 0xA1
                    ) {
                        v2Idx = i
                        break
                    }
                }
                if (v2Idx >= 0) {
                    if (v2Idx > 0) jkFrameBuf.subList(0, v2Idx).clear()
                    if (jkFrameBuf.size < 10) return
                    val dataLen = jkFrameBuf[5].toInt() and 0xFF
                    val totalLen = 10 + dataLen
                    if (totalLen < 12 || totalLen > 400) {
                        jkFrameBuf.removeAt(0)
                        continue
                    }
                    if (jkFrameBuf.size < totalLen) return
                    val frame = jkFrameBuf.subList(0, totalLen).toByteArray()
                    val parsed = TelemetryEngine.parseAntV2Frame(frame, bmsCur)
                    if (parsed != null) {
                        antReplyV2 = true
                        bmsCur = parsed
                        jkFrameBuf.subList(0, totalLen).clear()
                        mainHandler.post { onJkData?.invoke(parsed) }
                    } else {
                        jkFrameBuf.removeAt(0)
                    }
                    continue
                }

                if (jkFrameBuf.size > 400) jkFrameBuf.clear()
                return
            }
        }
    }

    private fun onDalyBytes(chunk: ByteArray) {
        synchronized(jkFrameBuf) {
            chunk.forEach { jkFrameBuf.add(it) }
            while (jkFrameBuf.size >= 13) {
                val hdrIdx = jkFrameBuf.indexOfFirst { (it.toInt() and 0xFF) == 0xA5 }
                if (hdrIdx < 0) {
                    jkFrameBuf.clear()
                    return
                }
                if (hdrIdx > 0) jkFrameBuf.subList(0, hdrIdx).clear()
                if (jkFrameBuf.size < 13) return
                val frame = jkFrameBuf.subList(0, 13).toByteArray()
                val parsed = synchronized(dalyCellAcc) {
                    TelemetryEngine.parseDaly13ByteFrame(frame, bmsCur, dalyCellAcc)
                }
                if (parsed != null) {
                    jkFrameBuf.subList(0, 13).clear()
                    bmsCur = parsed
                    mainHandler.post { onJkData?.invoke(parsed) }
                } else {
                    jkFrameBuf.removeAt(0)
                }
            }
        }
    }

    private fun onJbdBytes(chunk: ByteArray) {
        synchronized(jkFrameBuf) {
            chunk.forEach { jkFrameBuf.add(it) }
            while (jkFrameBuf.size >= 7) {
                val hdrIdx = jkFrameBuf.indexOfFirst { (it.toInt() and 0xFF) == 0xDD }
                if (hdrIdx < 0) {
                    jkFrameBuf.clear()
                    return
                }
                if (hdrIdx > 0) jkFrameBuf.subList(0, hdrIdx).clear()
                if (jkFrameBuf.size < 7) return
                val len = jkFrameBuf[3].toInt() and 0xFF
                val totalLen = 4 + len + 3
                if (totalLen > 280) {
                    jkFrameBuf.removeAt(0)
                    continue
                }
                if (jkFrameBuf.size < totalLen) return
                val frame = jkFrameBuf.subList(0, totalLen).toByteArray()
                val parsed = TelemetryEngine.parseJbdFrame(frame, bmsCur)
                if (parsed != null) {
                    jkFrameBuf.subList(0, totalLen).clear()
                    bmsCur = parsed
                    mainHandler.post { onJkData?.invoke(parsed) }
                } else {
                    jkFrameBuf.removeAt(0)
                }
            }
        }
    }

    private fun handleLineStream(sb: StringBuilder, bytes: ByteArray, onLine: (String) -> Unit) {
        val text = String(bytes, Charsets.UTF_8).replace('\r', '\n')
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

    /**
     * Kirim frame AT+MAPS lewat BLE. Mengembalikan true hanya bila SEMUA potongan diterima stack BLE.
     * Perbaikan: chunk dibatasi MTU-3 (sebelumnya bisa terpotong diam-diam), hasil tiap tulis dicek
     * dan di-retry bila stack sibuk, dan menunggu onCharacteristicWrite sebelum potongan berikutnya.
     */
    suspend fun sendMotorBleMapsFrame(
        b64: String,
        chunkSize: Int = 100,
        delayMs: Int = 30
    ): Boolean {
        if (isMotorBleMapsSending) return false
        isMotorBleMapsSending = true
        try {
            val mtu = slotMtu["motor"] ?: 23
            val maxPayload = (mtu - 3).coerceAtLeast(20)
            val cs = chunkSize.coerceIn(20, 400).coerceAtMost(maxPayload)
            var ackWorks = true

            suspend fun put(bytes: ByteArray): Boolean {
                repeat(40) {
                    motorWriteSem.drainPermits()
                    if (writeSlot("motor", bytes, noResponse = true)) {
                        if (ackWorks) {
                            val got = withContext(Dispatchers.IO) {
                                motorWriteSem.tryAcquire(150, TimeUnit.MILLISECONDS)
                            }
                            // Bila callback tak pernah datang di perangkat ini, andalkan delayMs saja.
                            if (!got) ackWorks = false
                        }
                        return true
                    }
                    delay(15)
                }
                return false
            }

            var ok = put("AT+MAPS=\"".toByteArray(Charsets.UTF_8))
            if (ok) {
                delay(45)
                var i = 0
                while (i < b64.length) {
                    val end = (i + cs).coerceAtMost(b64.length)
                    if (!put(b64.substring(i, end).toByteArray(Charsets.UTF_8))) {
                        ok = false
                        break
                    }
                    if (delayMs > 0) delay(delayMs.toLong())
                    i = end
                }
            }
            if (ok) ok = put("\"\r\n".toByteArray(Charsets.UTF_8))
            if (!ok) {
                // Tutup frame yang setengah terkirim supaya MID tidak menggabungkannya dengan frame berikut.
                writeSlot("motor", "\"\r\n".toByteArray(Charsets.UTF_8), noResponse = true)
                mainHandler.post {
                    onLog?.invoke("MOTOR", "AT+MAPS via BLE gagal: stack BLE menolak potongan data (MTU=$mtu, chunk=$cs).", true)
                }
            }
            return ok
        } finally {
            isMotorBleMapsSending = false
        }
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

    fun disconnectSlot(slot: String) {
        slotManualStop[slot] = true
        slotLastMac.remove(slot)
        cancelReconnect(slot)
        clearConnectTimeout(slot)
        BleConnectGate.release(slotLabel(slot))
        closeSlotOnly(slot, emitDisconnect = true)
    }

    @SuppressLint("MissingPermission")
    private fun closeSlotOnly(slot: String, emitDisconnect: Boolean) {
        if (slot == "jk") {
            jkPollFuture?.cancel(false)
            jkPollFuture = null
            clearJkAwaitGuard()
            synchronized(jkFrameBuf) { jkFrameBuf.clear() }
            synchronized(dalyCellAcc) { dalyCellAcc.clear() }
            bmsCur = JkBmsData(protocolLabel = activeBmsProtocol.shortLabel)
            antReplyV2 = false
            antPollCount = 0
        } else if (slot == "motor") {
            motorBlePollFuture?.cancel(false)
            motorBlePollFuture = null
            motorBleTimeFuture?.cancel(false)
            motorBleTimeFuture = null
            fardriverPollFuture?.cancel(false)
            fardriverPollFuture = null
            isMotorBleMapsSending = false
            synchronized(motorBleRx) { motorBleRx.clear() }
            synchronized(fdFrameBuf) { fdFrameBuf.clear() }
            fardriverCur = FardriverData()
        } else if (slot == "esp1") {
            synchronized(esp1Rx) { esp1Rx.clear() }
        } else if (slot == "esp2") {
            synchronized(esp2Rx) { esp2Rx.clear() }
        }
        writeChars.remove(slot)
        slotMtu.remove(slot)
        val g = gatts.remove(slot)
        try {
            g?.disconnect()
            g?.close()
        } catch (_: Exception) {
        }
        if (emitDisconnect) {
            emitSlotState(slot, ConnectionStateX.DISCONNECTED, "")
        }
    }

    private fun slotLabel(slot: String): String = when (slot) {
        "jk" -> "BMS_${activeBmsProtocol.name}"
        "esp1" -> "ESP32#1"
        "esp2" -> "ESP32#2"
        else -> if (activeControllerProtocol == ControllerProtocol.FARDRIVER_BLE) "FARDRIVER_BLE" else "MOTOR_BLE"
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
        rssiPollFuture?.cancel(false)
        rssiPollFuture = null
        disconnectSlot("jk")
        disconnectSlot("esp1")
        disconnectSlot("esp2")
        disconnectSlot("motor")
        scheduler.shutdownNow()
    }
}
