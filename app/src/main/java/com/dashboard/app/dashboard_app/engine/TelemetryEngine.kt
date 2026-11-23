package com.dashboard.app.dashboard_app.engine

import com.dashboard.app.dashboard_app.model.AlertSeverity
import com.dashboard.app.dashboard_app.model.AppAlert
import com.dashboard.app.dashboard_app.model.AppSettings
import com.dashboard.app.dashboard_app.model.AtNavSlotConfig
import com.dashboard.app.dashboard_app.model.Battery2Data
import com.dashboard.app.dashboard_app.model.BatterySourcePriority
import com.dashboard.app.dashboard_app.model.ChargingIndicatorState
import com.dashboard.app.dashboard_app.model.ConnectionStateX
import com.dashboard.app.dashboard_app.model.EffectiveTelemetry
import com.dashboard.app.dashboard_app.model.Esp32Data
import com.dashboard.app.dashboard_app.model.FuelSavingsSummary
import com.dashboard.app.dashboard_app.model.JkBmsData
import com.dashboard.app.dashboard_app.model.LiveTripMetrics
import com.dashboard.app.dashboard_app.model.MaintHealthLevel
import com.dashboard.app.dashboard_app.model.MaintenanceStatus
import com.dashboard.app.dashboard_app.model.MotorData
import com.dashboard.app.dashboard_app.model.RidingScoreState
import com.dashboard.app.dashboard_app.model.kMotorErrorDefs
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class IntegrateResult(
    val trip: LiveTripMetrics,
    val score: RidingScoreState,
    val wasMoving: Boolean
)

object TelemetryEngine {
    const val STALE_MS = 10_000L
    const val INTEGRATE_MAX_GAP_MS = 4_500L

    fun parseMotorAtLine(
        rawLine: String,
        current: MotorData,
        nowMs: Long = System.currentTimeMillis(),
        onValidOdo: ((Double) -> Unit)? = null,
        onCorruptOdo: ((String) -> Unit)? = null
    ): MotorData? {
        val line = rawLine.trim()
        if (line.isEmpty() || line == "OK") return null
        val eq = line.indexOf('=')
        if (eq <= 0) return null

        val key = line.substring(0, eq).trim().uppercase(Locale.US)
        val valStr = line.substring(eq + 1).trim().replace("\"", "")
        val numVal = valStr.toDoubleOrNull()

        return when {
            key.contains("SPD") && numVal != null ->
                current.copy(speedKmh = max(0.0, numVal), lastUpdatedMs = nowMs)
            key.contains("VBAT") && numVal != null ->
                current.copy(vbat = numVal, lastUpdatedMs = nowMs)
            key.contains("IBAT") && numVal != null ->
                current.copy(ibat = numVal, lastUpdatedMs = nowMs)
            key.contains("TRT") && numVal != null ->
                current.copy(throttlePct = numVal.coerceIn(0.0, 100.0), lastUpdatedMs = nowMs)
            key.contains("SOC") && numVal != null ->
                current.copy(socPct = numVal.coerceIn(0.0, 100.0), lastUpdatedMs = nowMs)
            key.contains("SOH") && numVal != null ->
                current.copy(sohPct = numVal.coerceIn(0.0, 100.0), lastUpdatedMs = nowMs)
            key.contains("CBAT") && numVal != null ->
                current.copy(cbatCycles = numVal.roundToInt(), lastUpdatedMs = nowMs)
            key.contains("ODO") && numVal != null -> {
                if (numVal >= 0 && numVal < 400_000) {
                    onValidOdo?.invoke(numVal)
                    current.copy(odoKm = numVal, lastUpdatedMs = nowMs)
                } else {
                    onCorruptOdo?.invoke("ODO korup diabaikan: $numVal")
                    current
                }
            }
            key.contains("ERR") -> {
                val cleaned = valStr.replaceFirst(Regex("^0x", RegexOption.IGNORE_CASE), "")
                val parsedMask = valStr.toIntOrNull() ?: cleaned.toIntOrNull(16) ?: 0
                current.copy(errMask = parsedMask, lastUpdatedMs = nowMs)
            }
            key.contains("MODE") && numVal != null ->
                current.copy(modeIndex = numVal.roundToInt(), lastUpdatedMs = nowMs)
            key.contains("PARK") && numVal != null ->
                current.copy(isPark = numVal.roundToInt() == 1, lastUpdatedMs = nowMs)
            key.contains("TINV") && numVal != null ->
                current.copy(tempInverterC = numVal, lastUpdatedMs = nowMs)
            key.contains("TMOT") && numVal != null ->
                current.copy(tempMotorC = numVal, lastUpdatedMs = nowMs)
            key.contains("TBAT") && numVal != null ->
                current.copy(tempBatteryC = numVal, lastUpdatedMs = nowMs)
            else -> null
        }
    }

    fun buildJkCommandFrame(reg: Int): ByteArray {
        val cmd = ByteArray(20)
        cmd[0] = 0xAA.toByte()
        cmd[1] = 0x55.toByte()
        cmd[2] = 0x90.toByte()
        cmd[3] = 0xEB.toByte()
        cmd[4] = (reg and 0xFF).toByte()
        cmd[5] = 0x00
        var sum = 0
        for (i in 0 until 19) {
            sum += cmd[i].toInt() and 0xFF
        }
        cmd[19] = (sum and 0xFF).toByte()
        return cmd
    }

    fun parseJkBms300ByteFrame(
        buf: ByteArray,
        nowMs: Long = System.currentTimeMillis(),
        onError: ((String) -> Unit)? = null
    ): JkBmsData? {
        if (buf.size < 300) return null
        if ((buf[0].toInt() and 0xFF) != 0x55 ||
            (buf[1].toInt() and 0xFF) != 0xAA ||
            (buf[2].toInt() and 0xFF) != 0xEB ||
            (buf[3].toInt() and 0xFF) != 0x90
        ) return null
        if ((buf[4].toInt() and 0xFF) != 0x02) return null

        var crc = 0
        for (i in 0 until 299) {
            crc += buf[i].toInt() and 0xFF
        }
        if ((crc and 0xFF) != (buf[299].toInt() and 0xFF)) {
            onError?.invoke("Checksum frame JK-BMS tidak cocok")
            return null
        }

        fun u16le(off: Int): Int =
            (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)

        fun i16le(off: Int): Int {
            val v = u16le(off)
            return if (v >= 0x8000) v - 0x10000 else v
        }

        fun u32le(off: Int): Long =
            (buf[off].toLong() and 0xFF) or
                ((buf[off + 1].toLong() and 0xFF) shl 8) or
                ((buf[off + 2].toLong() and 0xFF) shl 16) or
                ((buf[off + 3].toLong() and 0xFF) shl 24)

        fun i32le(off: Int): Long {
            val v = u32le(off)
            return if (v >= 0x80000000L) v - 0x100000000L else v
        }

        val cells = mutableListOf<Double>()
        for (i in 0 until 32) {
            val mv = u16le(6 + i * 2)
            if (mv in 1500..5000) {
                cells.add(mv / 1000.0)
            }
        }

        val vpack24 = u32le(118) * 0.001
        val vpack32 = u32le(134) * 0.001
        val cellSum = cells.sum()
        val use32Offset = abs(cellSum - vpack32) < abs(cellSum - vpack24)
        val off = if (use32Offset) 16 else 0

        val vpack = u32le(118 + off) * 0.001
        val ibat = i32le(126 + off) * 0.001
        val temp1 = i16le(130 + off) * 0.1
        val temp2 = i16le(132 + off) * 0.1
        val tempMos = i16le(112 + off) * 0.1
        val remainAh = u32le(142 + off) * 0.001
        val capAh = u32le(146 + off) * 0.001
        val cycles = u32le(150 + off).toInt()
        val soc = (buf[141 + off].toInt() and 0xFF).toDouble().coerceIn(0.0, 100.0)
        val sohRaw = buf[158 + off].toInt() and 0xFF
        val soh = if (sohRaw in 1..100) sohRaw.toDouble() else null
        val balAction = buf[140 + off].toInt() != 0

        var minCell: Double? = null
        var maxCell: Double? = null
        var minIdx = -1
        var maxIdx = -1
        cells.forEachIndexed { idx, c ->
            if (minCell == null || c < minCell!!) {
                minCell = c
                minIdx = idx
            }
            if (maxCell == null || c > maxCell!!) {
                maxCell = c
                maxIdx = idx
            }
        }
        val delta = if (minCell != null && maxCell != null) maxCell!! - minCell!! else null

        return JkBmsData(
            vpack = vpack,
            ibat = ibat,
            socPct = soc,
            sohPct = soh,
            temp1C = temp1,
            temp2C = temp2,
            tempMosC = tempMos,
            deltaCellV = delta,
            minCellV = minCell,
            maxCellV = maxCell,
            minCellIndex = minIdx,
            maxCellIndex = maxIdx,
            cellCount = cells.size,
            cellVoltages = cells,
            cycles = cycles,
            remainAh = remainAh,
            capacityAh = capAh,
            balancingActive = balAction,
            lastUpdatedMs = nowMs
        )
    }

    fun computeEffective(
        motor: MotorData,
        motorState: ConnectionStateX,
        jk: JkBmsData,
        jkState: ConnectionStateX,
        esp1: Esp32Data,
        esp1State: ConnectionStateX,
        b2: Battery2Data,
        esp2State: ConnectionStateX,
        gpsSpeedKmh: Double?,
        trip: LiveTripMetrics,
        settings: AppSettings,
        nowMs: Long = System.currentTimeMillis()
    ): EffectiveTelemetry {
        val motorAlive = motorState == ConnectionStateX.CONNECTED && (nowMs - motor.lastUpdatedMs) < STALE_MS
        val jkAlive = jkState == ConnectionStateX.CONNECTED && (nowMs - jk.lastUpdatedMs) < STALE_MS
        val esp1Alive = esp1State == ConnectionStateX.CONNECTED && (nowMs - esp1.lastUpdatedMs) < STALE_MS
        val esp2Alive = esp2State == ConnectionStateX.CONNECTED && (nowMs - b2.lastUpdatedMs) < STALE_MS

        val speedKmh: Double? = when {
            motorAlive && motor.speedKmh != null -> motor.speedKmh
            esp1Alive && esp1.speedKmh != null -> esp1.speedKmh
            else -> gpsSpeedKmh
        }

        val throttlePct: Double? = when {
            motorAlive && motor.throttlePct != null -> motor.throttlePct
            esp1Alive && esp1.throttlePct != null -> esp1.throttlePct
            else -> null
        }

        val preferJk = settings.batteryPriority == BatterySourcePriority.JIKONG
        val b1Source: String
        var b1Soc: Double? = null
        var b1Vbat: Double? = null
        var b1Ibat: Double? = null
        var b1Temp: Double? = null
        var b1Soh: Double? = null

        if ((preferJk && jkAlive) || (!motorAlive && jkAlive)) {
            b1Source = "JK-BMS"
            b1Soc = jk.socPct ?: motor.socPct
            b1Vbat = jk.vpack ?: motor.vbat
            b1Ibat = jk.ibat ?: motor.ibat
            b1Temp = jk.temp1C ?: motor.tempBatteryC
            b1Soh = jk.sohPct ?: motor.sohPct
        } else if (motorAlive) {
            b1Source = "Motor MID"
            b1Soc = motor.socPct
            b1Vbat = motor.vbat
            b1Ibat = motor.ibat
            b1Temp = motor.tempBatteryC
            b1Soh = motor.sohPct
        } else {
            b1Source = "—"
        }

        val b2Active = esp2Alive && (b2.socPct != null || b2.vbat != null)
        val b2Soc = if (b2Active) b2.socPct else null
        val b2Vbat = if (b2Active) b2.vbat else null
        val b2Ibat = if (b2Active) b2.ibat else null
        val b2Temp = if (b2Active) b2.tempC else null
        val b2Soh = if (b2Active) b2.sohPct else null

        val w1 = settings.b1NominalV * settings.b1CapacityAh
        val w2 = settings.b2NominalV * settings.b2CapacityAh

        val combinedSoc: Double? = if (b1Soc != null && b2Soc != null) {
            (b1Soc * w1 + b2Soc * w2) / (w1 + w2)
        } else {
            b1Soc ?: b2Soc
        }

        val combinedSoh: Double? = if (b1Soh != null && b2Soh != null) {
            (b1Soh * w1 + b2Soh * w2) / (w1 + w2)
        } else {
            b1Soh ?: b2Soh
        }

        val p1 = if (b1Vbat != null && b1Ibat != null) -(b1Vbat * b1Ibat) else null
        val p2 = if (b2Active) {
            b2.powerW ?: if (b2Vbat != null && b2Ibat != null) -(b2Vbat * b2Ibat) else null
        } else null

        val combinedPowerW = if (p1 != null && p2 != null) (p1 + p2) else (p1 ?: p2)
        val combinedCurrentA = if (b1Ibat != null && b2Ibat != null) (b1Ibat + b2Ibat) else (b1Ibat ?: b2Ibat)
        val avgVoltageV = if (b1Vbat != null && b2Vbat != null) (b1Vbat + b2Vbat) / 2.0 else (b1Vbat ?: b2Vbat)

        val spd = speedKmh ?: 0.0
        val curr = combinedCurrentA ?: 0.0
        val isRegen = curr > 0.3 && spd > 1.0
        val isCharging = curr > 0.3 && spd <= 1.0

        val refWhKm = settings.refWhPerKm.coerceIn(10.0, 90.0)
        val measuredWhKm = trip.rawWhPerKm
        val blendedWhKm = when {
            trip.distanceKm >= 1.5 && measuredWhKm > 1.0 -> measuredWhKm
            trip.distanceKm > 0.1 && measuredWhKm > 1.0 -> {
                val k0 = 0.3 * (1.0 - trip.distanceKm / 1.5)
                k0 * refWhKm + (1.0 - k0) * measuredWhKm
            }
            else -> refWhKm
        }

        val totalCapWh: Double
        val maxPackWh: Double
        when {
            b1Soc != null && b2Soc != null -> {
                totalCapWh = (w1 * (b1Soc / 100.0)) + (w2 * (b2Soc / 100.0))
                maxPackWh = w1 + w2
            }
            b1Soc != null -> {
                totalCapWh = w1 * (b1Soc / 100.0)
                maxPackWh = w1
            }
            b2Soc != null -> {
                totalCapWh = w2 * (b2Soc / 100.0)
                maxPackWh = w2
            }
            else -> {
                totalCapWh = 0.0
                maxPackWh = w1
            }
        }

        val rangeKm = if (blendedWhKm > 1.0) max(0.0, totalCapWh / blendedWhKm) else 0.0

        var chargeEtaHours: Double? = null
        if (isCharging && combinedPowerW != null && combinedPowerW < -20.0) {
            val remainingWhToFull = max(0.0, maxPackWh - totalCapWh)
            chargeEtaHours = remainingWhToFull / abs(combinedPowerW)
        }

        val activeErrors = kMotorErrorDefs.filter { ((motor.errMask ushr it.bit) and 1) == 1 }
        val odoKm = motor.odoKm ?: settings.odoBackupKm
        val modeIdx = esp1.modeIndex ?: motor.modeIndex
        val modeLabel = when (modeIdx) {
            0 -> "ECO"
            1 -> "URBAN"
            2 -> "SPORT"
            else -> "—"
        }

        return EffectiveTelemetry(
            speedKmh = speedKmh,
            rpm = speedKmh?.let { (it * 51.45).roundToInt() },
            throttlePct = throttlePct,
            b1SocPct = b1Soc,
            b1Vbat = b1Vbat,
            b1Ibat = b1Ibat,
            b1TempC = b1Temp,
            b1SohPct = b1Soh,
            b1SourceLabel = b1Source,
            b2Active = b2Active,
            b2SocPct = b2Soc,
            b2Vbat = b2Vbat,
            b2Ibat = b2Ibat,
            b2TempC = b2Temp,
            combinedSocPct = combinedSoc,
            combinedSohPct = combinedSoh,
            combinedPowerW = combinedPowerW,
            combinedCurrentA = combinedCurrentA,
            avgVoltageV = avgVoltageV,
            isCharging = isCharging,
            isRegen = isRegen,
            rangeKm = rangeKm,
            blendedWhPerKm = blendedWhKm,
            totalAvailableWh = totalCapWh,
            chargeEtaHours = chargeEtaHours,
            odoKm = odoKm,
            modeLabel = modeLabel,
            isPark = esp1.isPark ?: motor.isPark,
            sideStandDown = esp1Alive && esp1.sideStandDown,
            cruiseActive = esp1Alive && esp1.cruiseActive,
            reverseActive = esp1Alive && esp1.reverseActive,
            activeErrors = activeErrors
        )
    }

    fun chargingIndicator(eff: EffectiveTelemetry): ChargingIndicatorState {
        val soc = eff.combinedSocPct
        if (eff.isRegen) return ChargingIndicatorState.REGEN
        if (eff.isCharging) return ChargingIndicatorState.CHARGING
        if (soc != null && soc >= 99.5) return ChargingIndicatorState.FULL
        return ChargingIndicatorState.NOT_CHARGING
    }

    fun integrateSample(
        prevTrip: LiveTripMetrics,
        prevScore: RidingScoreState,
        eff: EffectiveTelemetry,
        nowMs: Long,
        wasMoving: Boolean
    ): IntegrateResult {
        val trip = if (prevTrip.startTimeMs == 0L) {
            prevTrip.copy(
                startTimeMs = nowMs,
                startSocPct = eff.combinedSocPct,
                lastSampleMs = nowMs
            )
        } else prevTrip

        val dtMs = if (trip.lastSampleMs > 0L) nowMs - trip.lastSampleMs else 0L
        if (dtMs <= 0L || dtMs > INTEGRATE_MAX_GAP_MS) {
            return IntegrateResult(
                trip = trip.copy(lastSampleMs = nowMs),
                score = prevScore,
                wasMoving = wasMoving
            )
        }

        val dtSec = dtMs / 1000.0
        val dtHr = dtMs / 3_600_000.0
        val spd = eff.speedKmh ?: 0.0
        val nowMoving = spd > 1.5
        val distDeltaKm = if (nowMoving) spd * dtHr else 0.0

        val p1 = if (eff.b1Vbat != null && eff.b1Ibat != null) eff.b1Vbat * eff.b1Ibat else 0.0
        val p2 = if (eff.b2Vbat != null && eff.b2Ibat != null) eff.b2Vbat * eff.b2Ibat else 0.0
        val totalI = eff.combinedCurrentA ?: 0.0
        val totalP = -(p1 + p2)

        var energyAdd = 0.0
        var ahAdd = 0.0
        var regenAdd = 0.0
        var chargeAdd = 0.0

        when {
            totalI < 0.0 -> {
                energyAdd = abs(totalP) * dtHr
                ahAdd = abs(totalI) * dtHr
            }
            totalI > 0.0 && spd > 1.0 -> regenAdd = abs(totalP) * dtHr
            totalI > 0.0 && spd <= 1.0 -> chargeAdd = abs(totalP) * dtHr
        }

        val stopInc = if (wasMoving && !nowMoving && trip.distanceKm > 0.05) 1 else 0
        val moveIncSec = if (nowMoving) max(1, dtSec.roundToInt()) else 0

        val nextTrip = trip.copy(
            distanceKm = trip.distanceKm + distDeltaKm,
            energyWh = trip.energyWh + energyAdd,
            consumedAh = trip.consumedAh + ahAdd,
            regenWh = trip.regenWh + regenAdd,
            chargeWh = trip.chargeWh + chargeAdd,
            maxSpeedKmh = max(trip.maxSpeedKmh, spd),
            maxPowerW = max(trip.maxPowerW, if (totalP > 0) totalP else 0.0),
            maxRegenW = max(trip.maxRegenW, if (eff.isRegen) abs(totalP) else 0.0),
            movingSeconds = trip.movingSeconds + moveIncSec,
            stopCount = trip.stopCount + stopInc,
            lastSampleMs = nowMs,
            startSocPct = trip.startSocPct ?: eff.combinedSocPct
        )

        val nextScore = if (eff.speedKmh != null && (nowMoving || abs(totalP) > 50.0)) {
            prevScore.copy(
                samples = prevScore.samples + 1,
                ecoViolations = prevScore.ecoViolations + if (totalP > 2500.0) 1 else 0,
                hardBrakes = prevScore.hardBrakes + if ((prevScore.lastSpeedKmh - spd) > 15.0) 1 else 0,
                regenEvents = prevScore.regenEvents + if (totalI > 0.0 && spd > 5.0) 1 else 0,
                lastSpeedKmh = spd
            )
        } else {
            prevScore.copy(lastSpeedKmh = spd)
        }

        return IntegrateResult(trip = nextTrip, score = nextScore, wasMoving = nowMoving)
    }

    fun evaluateAlarms(
        eff: EffectiveTelemetry,
        jk: JkBmsData,
        b2: Battery2Data,
        settings: AppSettings
    ): List<AppAlert> {
        if (!settings.alarmMasterOn) return emptyList()
        val now = System.currentTimeMillis()
        val alerts = mutableListOf<AppAlert>()

        val soc = eff.combinedSocPct
        if (settings.alarmSocLowOn && soc != null && soc < settings.alarmSocThreshold) {
            alerts.add(
                AppAlert(
                    id = "soc_low",
                    title = "Baterai Lemah (${soc.roundToInt()}%)",
                    message = "SOC di bawah ${settings.alarmSocThreshold}%. Segera cari SPKLU terdekat.",
                    severity = if (soc <= 10) AlertSeverity.CRITICAL else AlertSeverity.WARNING,
                    timestampMs = now
                )
            )
        }

        if (settings.alarmChargeLimitOn && eff.isCharging && soc != null && soc >= settings.alarmChargeLimitPct) {
            alerts.add(
                AppAlert(
                    id = "charge_limit",
                    title = "Batas Pengisian Tercapai (${soc.roundToInt()}%)",
                    message = "SOC mencapai target ${settings.alarmChargeLimitPct}%. Anda dapat mencabut charger untuk umur baterai optimal.",
                    severity = AlertSeverity.INFO,
                    timestampMs = now
                )
            )
        }

        val temps = listOfNotNull(eff.b1TempC, eff.b2TempC, jk.tempMosC)
        if (temps.isNotEmpty()) {
            val maxT = temps.max()
            if (settings.alarmTempHighOn && maxT > settings.alarmTempThresholdC) {
                alerts.add(
                    AppAlert(
                        id = "temp_high",
                        title = "Suhu Baterai/MOS Tinggi (${maxT.roundToInt()}°C)",
                        message = "Melebihi ambang ${settings.alarmTempThresholdC}°C. Kurangi beban akselerasi.",
                        severity = AlertSeverity.WARNING,
                        timestampMs = now
                    )
                )
            }
        }

        val sohs = listOfNotNull(eff.b1SohPct, b2.sohPct)
        if (sohs.isNotEmpty()) {
            val minSoh = sohs.min()
            if (settings.alarmSohLowOn && minSoh < 75.0) {
                alerts.add(
                    AppAlert(
                        id = "soh_low",
                        title = "Kesehatan Baterai Menurun (${minSoh.roundToInt()}%)",
                        message = "SOH di bawah 75%. Pertimbangkan kalibrasi atau servis sel.",
                        severity = AlertSeverity.INFO,
                        timestampMs = now
                    )
                )
            }
        }

        val deltas = listOfNotNull(jk.deltaCellV, b2.deltaCellV)
        if (deltas.isNotEmpty()) {
            val maxDelta = deltas.max()
            if (settings.alarmDeltaCellOn && maxDelta > 0.080) {
                alerts.add(
                    AppAlert(
                        id = "delta_cell",
                        title = "Ketidakseimbangan Sel (${String.format(Locale.US, "%.3f", maxDelta)} V)",
                        message = "Delta sel > 80 mV. Lakukan balancing pengisian hingga penuh.",
                        severity = AlertSeverity.WARNING,
                        timestampMs = now
                    )
                )
            }
        }

        if (eff.activeErrors.isNotEmpty()) {
            alerts.add(
                AppAlert(
                    id = "motor_err",
                    title = "${eff.activeErrors.size} Error Motor Aktif",
                    message = eff.activeErrors.joinToString(", ") { it.label },
                    severity = AlertSeverity.CRITICAL,
                    timestampMs = now
                )
            )
        }

        return alerts
    }

    fun computeMaintenanceStatus(odoKm: Double?, settings: AppSettings): MaintenanceStatus? {
        if (settings.maintLastOdoKm <= 0 && (odoKm ?: 0.0) <= 0) return null
        val cur = odoKm ?: settings.odoBackupKm
        val interval = max(100.0, settings.maintIntervalKm)
        val next = settings.maintLastOdoKm + interval
        val remaining = max(0.0, next - cur)
        val overdue = max(0.0, cur - next)
        val pct = ((cur - settings.maintLastOdoKm) / interval).coerceIn(0.0, 1.0)
        val level = when {
            pct >= 1.0 -> MaintHealthLevel.DUE
            pct >= 0.85 -> MaintHealthLevel.WARN
            else -> MaintHealthLevel.OK
        }
        return MaintenanceStatus(
            lastServiceOdoKm = settings.maintLastOdoKm,
            intervalKm = interval,
            nextServiceOdoKm = next,
            remainingKm = remaining,
            overdueKm = overdue,
            progressPct = pct,
            level = level
        )
    }

    fun computeFuelSavings(totalKm: Double, totalEnergyWh: Double): FuelSavingsSummary {
        val petrolLiters = totalKm / 40.0
        val petrolCost = (petrolLiters * 10_000.0).roundToInt()
        val elecKwh = totalEnergyWh / 1000.0
        val elecCost = (elecKwh * 1447.0).roundToInt()
        val petrolCo2Kg = petrolLiters * 2.31
        val gridCo2Kg = elecKwh * 0.724
        return FuelSavingsSummary(
            totalKm = totalKm,
            petrolCostRp = petrolCost,
            electricityCostRp = elecCost,
            netSavingsRp = max(0, petrolCost - elecCost),
            co2SavedKg = max(0.0, petrolCo2Kg - gridCo2Kg)
        )
    }

    fun buildAtNavSlots(
        eff: EffectiveTelemetry,
        trip: LiveTripMetrics,
        roadName: String,
        areaName: String,
        weatherMood: String,
        slots: AtNavSlotConfig
    ): List<String> {
        val out = mutableListOf<String>()
        if (slots.showRange) out.add("RNG ${String.format(Locale.US, "%.1f", eff.rangeKm)} km")
        if (slots.showTrip) out.add("TRP ${String.format(Locale.US, "%.1f", trip.distanceKm)} km")
        if (slots.showEnergy) out.add("ENG ${trip.energyWh.roundToInt()} Wh")
        if (slots.showEfficiency) out.add("EFF ${eff.blendedWhPerKm.roundToInt()}Wh/km")
        if (slots.showPower && eff.combinedPowerW != null) {
            out.add("PWR ${eff.combinedPowerW.roundToInt()} W")
        }
        if (slots.showCo2) {
            val savedKg = (trip.distanceKm / 40.0 * 2.31) - (trip.energyWh / 1000.0 * 0.724)
            out.add("CO2 -${String.format(Locale.US, "%.2f", max(0.0, savedKg))}kg")
        }
        if (slots.showLocation && (roadName.isNotBlank() || areaName.isNotBlank())) {
            val loc = listOf(roadName, areaName).filter { it.isNotBlank() }.joinToString(", ")
            out.add(if (loc.length > 20) loc.substring(0, 20) else loc)
        }
        if (slots.showWeather && weatherMood.isNotBlank()) {
            out.add(if (weatherMood.length > 20) weatherMood.substring(0, 20) else weatherMood)
        }
        return out
    }
}
