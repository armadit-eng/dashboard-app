package com.dashboard.app.dashboard_app.model

import kotlinx.serialization.Serializable

enum class HillGrade { FLAT, UPHILL_MILD, UPHILL_STEEP, DOWNHILL }

enum class MapStyleOption(val id: String, val label: String, val tileUrlTemplate: String) {
    OSM_STANDARD("osm", "OSM Standar", "https://tile.openstreetmap.org/{z}/{x}/{y}.png"),
    DARK_CARTO("dark", "Cockpit Gelap", "https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png"),
    SATELLITE("sat", "Satelit Esri", "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"),
    TOPO("topo", "Topografi", "https://a.tile.opentopomap.org/{z}/{x}/{y}.png");

    companion object {
        fun fromId(id: String?): MapStyleOption =
            entries.firstOrNull { it.id == id } ?: DARK_CARTO
    }
}

@Serializable
data class GeoPointX(
    val lat: Double,
    val lng: Double,
    val altM: Double? = null
)

data class GpsState(
    val location: GeoPointX? = null,
    val speedKmh: Double? = null,
    val headingDeg: Double? = null,
    val accuracyM: Double? = null,
    val roadName: String = "",
    val areaName: String = "",
    val isFromCache: Boolean = false
)

data class RouteStepX(
    val instruction: String,
    val roadName: String,
    val distanceM: Double,
    val durationSec: Double,
    val maneuverType: String,
    val modifier: String? = null,
    val location: GeoPointX
)

data class ElevationSample(
    val distanceKm: Double,
    val elevationM: Double,
    val gradePct: Double,
    val gradeType: HillGrade,
    val point: GeoPointX
)

data class RouteEnergyEstimate(
    val totalWh: Double,
    val regenWh: Double,
    val netWh: Double,
    val whPerKm: Double,
    val socUsedPct: Double,
    val socAtArrivalPct: Double?,
    val maxClimbPct: Double,
    val totalAscentM: Double,
    val totalDescentM: Double
)

data class WaypointArrivalSummary(
    val label: String,
    val legDistKm: Double,
    val legEtaMin: Double,
    val arrivalSocPct: Double?,
    val isChargingStop: Boolean = false
)

data class NavRouteX(
    val geometry: List<GeoPointX>,
    val steps: List<RouteStepX>,
    val distanceM: Double,
    val durationSec: Double,
    val elevationSamples: List<ElevationSample> = emptyList(),
    val energyEstimate: RouteEnergyEstimate? = null,
    val legSummaries: List<WaypointArrivalSummary> = emptyList()
)

@Serializable
data class PlannedStop(
    val id: String,
    val label: String,
    val lat: Double,
    val lng: Double,
    val isSpklu: Boolean = false
)

@Serializable
data class SavedPlace(
    val id: String,
    val name: String,
    val category: String,
    val lat: Double,
    val lng: Double,
    val createdAtMs: Long
)

@Serializable
data class SpkluStation(
    val id: String,
    val name: String,
    val operatorName: String,
    val lat: Double,
    val lng: Double,
    val socketType: String = "Type 2 / CCS2",
    val powerKw: Double? = null,
    val isUserAdded: Boolean = false,
    val distanceKm: Double? = null
)

@Serializable
data class FieldNotePin(
    val id: String,
    val name: String,
    val category: String,
    val note: String,
    val lat: Double,
    val lng: Double,
    val timestampMs: Long
)

data class WeatherData(
    val tempC: Double,
    val feelsLikeC: Double,
    val humidityPct: Int,
    val windKmh: Double,
    val rainProbPct: Int,
    val uvIndex: Double? = null,
    val weatherCode: Int,
    val description: String,
    val moodTag: String,
    val sunrise: String = "--:--",
    val sunset: String = "--:--",
    val fetchedAtMs: Long
)
