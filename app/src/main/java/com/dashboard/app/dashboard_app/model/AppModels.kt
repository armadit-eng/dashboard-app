package com.dashboard.app.dashboard_app.model

import kotlinx.serialization.Serializable

@Serializable
data class AtNavSlotConfig(
    val showRange: Boolean = true,
    val showTrip: Boolean = true,
    val showEnergy: Boolean = true,
    val showEfficiency: Boolean = true,
    val showPower: Boolean = true,
    val showCo2: Boolean = false,
    val showLocation: Boolean = true,
    val showWeather: Boolean = true,
    val intervalSec: Int = 4
)

@Serializable
data class MatrixQueueItem(
    val id: String,
    val text: String,
    val enabled: Boolean = true,
    val speedMs: Int = 45,
    val effectId: Int = 0
)

@Serializable
data class AppSettings(
    val riderName: String = "Pengendara EV",
    val bikeName: String = "GESITS G1 Smart",
    val plateNumber: String = "B 4042 EVX",
    val avatarPath: String = "",
    val isDarkTheme: Boolean = true,
    val accentId: String = "emerald",
    val garageStyleId: String = "stealth_obsidian",
    val cockpitLayout: String = "complete",
    val batteryPriorityId: String = BatterySourcePriority.MOTOR.id,
    val b1NominalV: Double = 72.0,
    val b1CapacityAh: Double = 20.0,
    val b2NominalV: Double = 72.0,
    val b2CapacityAh: Double = 20.0,
    val invertB2Current: Boolean = false,
    val refWhPerKm: Double = 28.0,
    val bikeWeightKg: Double = 95.0,
    val riderWeightKg: Double = 70.0,
    val loadWeightKg: Double = 5.0,
    val routeCalFactor: Double = 1.0,
    val odoBackupKm: Double = 0.0,
    val weeklyGoalKm: Double = 120.0,
    val maintLastOdoKm: Double = 0.0,
    val maintIntervalKm: Double = 2000.0,
    val alarmMasterOn: Boolean = true,
    val alarmSocLowOn: Boolean = true,
    val alarmSocThreshold: Int = 20,
    val alarmTempHighOn: Boolean = true,
    val alarmTempThresholdC: Int = 55,
    val alarmSohLowOn: Boolean = true,
    val alarmDeltaCellOn: Boolean = true,
    val alarmChargeLimitOn: Boolean = false,
    val alarmChargeLimitPct: Int = 90,
    val autoSaveTrip: Boolean = true,
    val autoReconnect: Boolean = true,
    val voiceNavEnabled: Boolean = true,
    val hillAlertVoice: Boolean = true,
    val mapStyleId: String = "dark",
    val dataSaverMap: Boolean = false,
    val mapsLayout: String = "split",
    val mapsRiderPos: String = "low",
    val mapsSnapWidth: Int = 350,
    val mapsSnapHeight: Int = 220,
    val mapsLeChunkSize: Int = 100,
    val mapsLeDelayMs: Int = 30,
    val mapsIntervalMs: Int = 1000,
    val customWallpaperEnabled: Boolean = false,
    val customWallpaperPath: String = "",
    val customMarkerPath: String = "",
    val atNavSlots: AtNavSlotConfig = AtNavSlotConfig(),
    val savedMotorAddress: String? = null,
    val savedMotorName: String? = null,
    val savedJkAddress: String? = null,
    val savedEsp1Address: String? = null,
    val savedEsp2Address: String? = null,
    val ocmApiKey: String = ""
) {
    val batteryPriority: BatterySourcePriority
        get() = BatterySourcePriority.fromId(batteryPriorityId)
}

@Serializable
data class TripRecord(
    val id: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val distanceKm: Double,
    val energyWh: Double,
    val regenWh: Double,
    val maxSpeedKmh: Double,
    val avgSpeedKmh: Double,
    val maxPowerW: Double,
    val ridingScore: Int? = null,
    val startArea: String = "",
    val endArea: String = "",
    val pathPoints: List<GeoPointX> = emptyList(),
    val samples: List<TelemetryPoint> = emptyList()
) {
    val whPerKm: Double
        get() = if (distanceKm > 0.05) energyWh / distanceKm else 0.0
}

@Serializable
data class MaintenanceLogEntry(
    val id: String,
    val odoKm: Double,
    val serviceType: String,
    val notes: String = "",
    val costRp: Int = 0,
    val timestampMs: Long
)

enum class MaintHealthLevel { OK, WARN, DUE }

data class MaintenanceStatus(
    val lastServiceOdoKm: Double,
    val intervalKm: Double,
    val nextServiceOdoKm: Double,
    val remainingKm: Double,
    val overdueKm: Double,
    val progressPct: Double,
    val level: MaintHealthLevel
)

data class FuelSavingsSummary(
    val totalKm: Double,
    val petrolCostRp: Int,
    val electricityCostRp: Int,
    val netSavingsRp: Int,
    val co2SavedKg: Double
)

@Serializable
data class CalibrationEntry(
    val id: String,
    val distanceKm: Double,
    val predictedWh: Double,
    val actualWh: Double,
    val suggestedFactor: Double,
    val timestampMs: Long
) {
    val errorPct: Double
        get() = if (predictedWh > 1.0) ((actualWh - predictedWh) / predictedWh) * 100.0 else 0.0
}

data class RiderBadge(
    val id: String,
    val title: String,
    val description: String,
    val unlocked: Boolean,
    val progress: Double
)

@Serializable
data class ParkedLocation(
    val lat: Double,
    val lng: Double,
    val label: String,
    val timestampMs: Long
)
