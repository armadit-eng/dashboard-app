package com.dashboard.app.dashboard_app.model

import kotlinx.serialization.Serializable

enum class ConnectionStateX {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED
}

enum class BatterySourcePriority(val id: String, val label: String) {
    MOTOR("motor", "Motor MID"),
    JIKONG("jikong", "JK-BMS");

    companion object {
        fun fromId(id: String?): BatterySourcePriority =
            if (id == "jikong") JIKONG else MOTOR
    }
}

enum class ChargingIndicatorState(val label: String) {
    CHARGING("Mengisi Daya"),
    FULL("Terisi Penuh"),
    NOT_CHARGING("Tidak Mengisi"),
    REGEN("Regeneratif")
}

data class DiscoveredDeviceX(
    val name: String,
    val address: String,
    val isBle: Boolean = false,
    val rssi: Int = 0,
    val isPaired: Boolean = false
)

data class ErrorDefinition(
    val bit: Int,
    val label: String,
    val fix: String
)

val kMotorErrorDefs: List<ErrorDefinition> = listOf(
    ErrorDefinition(
        bit = 0,
        label = "Inverter Over Temperature",
        fix = "Diamkan motor hingga suhu inverter normal, hindari beban berat terus-menerus."
    ),
    ErrorDefinition(
        bit = 1,
        label = "Motor Over Temperature",
        fix = "Istirahatkan motor sampai suhu turun ke rentang normal."
    ),
    ErrorDefinition(
        bit = 2,
        label = "Motor Fault",
        fix = "Periksa kabel & konektor fasa motor; bawa ke bengkel jika berulang."
    ),
    ErrorDefinition(
        bit = 3,
        label = "Hall Sensor Fault",
        fix = "Cek konektor sensor hall motor, pastikan tidak longgar atau kotor."
    ),
    ErrorDefinition(
        bit = 4,
        label = "Throttle Fault",
        fix = "Periksa posisi & kabel gas (throttle), pastikan kembali ke nol."
    ),
    ErrorDefinition(
        bit = 5,
        label = "Controller Fault",
        fix = "Restart sistem (kontak OFF lalu ON). Jika menetap, cek di bengkel resmi."
    ),
    ErrorDefinition(
        bit = 6,
        label = "Over Voltage",
        fix = "Periksa charger & sumber listrik, pastikan tegangan pack tidak melebihi batas."
    ),
    ErrorDefinition(
        bit = 7,
        label = "Under Voltage",
        fix = "Segera isi daya baterai, tegangan baterai terlalu rendah."
    ),
    ErrorDefinition(
        bit = 11,
        label = "Throttle Signal Abnormal",
        fix = "Cek soket sinyal throttle dan kabel bodi depan."
    )
)

data class MotorData(
    val speedKmh: Double? = null,
    val vbat: Double? = null,
    val ibat: Double? = null,
    val throttlePct: Double? = null,
    val socPct: Double? = null,
    val sohPct: Double? = null,
    val cbatCycles: Int? = null,
    val odoKm: Double? = null,
    val errMask: Int = 0,
    val modeIndex: Int? = null,
    val isPark: Boolean? = null,
    val tempInverterC: Double? = null,
    val tempMotorC: Double? = null,
    val tempBatteryC: Double? = null,
    val rpm: Int? = null,
    val timeStr: String? = null,
    val lastUpdatedMs: Long = 0L
)

data class JkBmsData(
    val vpack: Double? = null,
    val ibat: Double? = null,
    val socPct: Double? = null,
    val sohPct: Double? = null,
    val temp1C: Double? = null,
    val temp2C: Double? = null,
    val tempMosC: Double? = null,
    val deltaCellV: Double? = null,
    val minCellV: Double? = null,
    val maxCellV: Double? = null,
    val minCellIndex: Int = -1,
    val maxCellIndex: Int = -1,
    val cellCount: Int = 0,
    val cellVoltages: List<Double> = emptyList(),
    val cycles: Int? = null,
    val remainAh: Double? = null,
    val capacityAh: Double? = null,
    val balancingActive: Boolean? = null,
    val lastUpdatedMs: Long = 0L
)

data class Battery2Data(
    val vbat: Double? = null,
    val ibat: Double? = null,
    val powerW: Double? = null,
    val socPct: Double? = null,
    val sohPct: Double? = null,
    val tempC: Double? = null,
    val deltaCellV: Double? = null,
    val cellCount: Int = 0,
    val cellVoltages: List<Double> = emptyList(),
    val cycles: Int? = null,
    val remainAh: Double? = null,
    val canMode: String = "LISTEN",
    val simMode: String? = null,
    val lastUpdatedMs: Long = 0L
)

data class Esp32Data(
    val speedKmh: Double? = null,
    val throttlePct: Double? = null,
    val cruiseActive: Boolean = false,
    val reverseActive: Boolean = false,
    val sideStandDown: Boolean = false,
    val modeIndex: Int? = null,
    val isPark: Boolean? = null,
    val contactOn: Boolean? = null,
    val matrixOn: Boolean? = null,
    val matrixBusy: Boolean = false,
    val musicTrack: Int = 1,
    val musicState: String = "STOP",
    val musicVol: Int = 20,
    val lastUpdatedMs: Long = 0L
)

data class LiveTripMetrics(
    val startTimeMs: Long = 0L,
    val distanceKm: Double = 0.0,
    val energyWh: Double = 0.0,
    val consumedAh: Double = 0.0,
    val regenWh: Double = 0.0,
    val chargeWh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val maxPowerW: Double = 0.0,
    val maxRegenW: Double = 0.0,
    val maxCurrentA: Double = 0.0,
    val minVbat: Double? = null,
    val maxVbat: Double? = null,
    val maxTbatC: Double? = null,
    val movingSeconds: Int = 0,
    val stopCount: Int = 0,
    val lastSampleMs: Long = 0L,
    val startSocPct: Double? = null
) {
    val rawWhPerKm: Double
        get() = if (distanceKm > 0.05) energyWh / distanceKm else 0.0

    val avgSpeedKmh: Double
        get() = if (movingSeconds > 0) distanceKm / (movingSeconds / 3600.0) else 0.0

    val netWhPerKm: Double
        get() = if (distanceKm > 0.05) maxOf(0.0, energyWh - regenWh) / distanceKm else 0.0
}

data class RidingScoreState(
    val samples: Int = 0,
    val ecoViolations: Int = 0,
    val hardBrakes: Int = 0,
    val regenEvents: Int = 0,
    val lastSpeedKmh: Double = 0.0
) {
    val score: Int?
        get() {
            if (samples < 3) return null
            val ecoRatio = 1.0 - (ecoViolations.toDouble() / samples)
            val brakeRatio = 1.0 - minOf(1.0, hardBrakes / (samples * 0.05))
            val regenRatio = minOf(1.0, regenEvents / (samples * 0.10))
            return (ecoRatio * 50.0 + brakeRatio * 30.0 + regenRatio * 20.0)
                .toInt()
                .coerceIn(0, 100)
        }

    val label: String
        get() {
            val s = score ?: return "Belum ada data"
            return when {
                s >= 80 -> "Efisien"
                s >= 60 -> "Normal"
                else -> "Boros"
            }
        }
}

data class EffectiveTelemetry(
    val speedKmh: Double? = null,
    val rpm: Int? = null,
    val throttlePct: Double? = null,
    val b1SocPct: Double? = null,
    val b1Vbat: Double? = null,
    val b1Ibat: Double? = null,
    val b1TempC: Double? = null,
    val b1SohPct: Double? = null,
    val b1SourceLabel: String = "—",
    val b2Active: Boolean = false,
    val b2SocPct: Double? = null,
    val b2Vbat: Double? = null,
    val b2Ibat: Double? = null,
    val b2TempC: Double? = null,
    val combinedSocPct: Double? = null,
    val combinedSohPct: Double? = null,
    val combinedPowerW: Double? = null,
    val combinedCurrentA: Double? = null,
    val avgVoltageV: Double? = null,
    val isCharging: Boolean = false,
    val isRegen: Boolean = false,
    val rangeKm: Double? = null,
    val blendedWhPerKm: Double = 28.0,
    val totalAvailableWh: Double = 0.0,
    val chargeEtaHours: Double? = null,
    val odoKm: Double? = null,
    val modeLabel: String = "—",
    val isPark: Boolean? = null,
    val sideStandDown: Boolean = false,
    val cruiseActive: Boolean = false,
    val reverseActive: Boolean = false,
    val activeErrors: List<ErrorDefinition> = emptyList()
)

enum class AlertSeverity { INFO, WARNING, CRITICAL }

data class AppAlert(
    val id: String,
    val title: String,
    val message: String,
    val severity: AlertSeverity,
    val timestampMs: Long
)

@Serializable
data class TelemetryPoint(
    val timestampMs: Long,
    val speedKmh: Double,
    val throttlePct: Double,
    val powerW: Double,
    val socPct: Double,
    val voltageV: Double,
    val currentA: Double,
    val tempC: Double,
    val lat: Double? = null,
    val lng: Double? = null,
    val altM: Double? = null
)

data class SerialLogEntry(
    val timestampMs: Long,
    val source: String,
    val message: String,
    val isError: Boolean = false
)
