package com.dashboard.app.dashboard_app.service

import android.content.Context
import android.net.Uri
import com.dashboard.app.dashboard_app.model.AppSettings
import com.dashboard.app.dashboard_app.model.CalibrationEntry
import com.dashboard.app.dashboard_app.model.FieldNotePin
import com.dashboard.app.dashboard_app.model.MaintenanceLogEntry
import com.dashboard.app.dashboard_app.model.MatrixQueueItem
import com.dashboard.app.dashboard_app.model.ParkedLocation
import com.dashboard.app.dashboard_app.model.SavedPlace
import com.dashboard.app.dashboard_app.model.SpkluStation
import com.dashboard.app.dashboard_app.model.TripRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

@Serializable
data class CachedGeocode(
    val road: String = "",
    val area: String = "",
    val lat: Double,
    val lng: Double
)

class PersistenceService(private val context: Context) {
    companion object {
        private const val PREFS_NAME = "dashboard_app_prefs"
        private const val K_SETTINGS = "dash_settings_v1"
        private const val K_PARKED = "dash_parked_loc_v1"
        private const val K_LAST_GEO = "dash_last_geocode_v1"
        private const val K_TRIPS_FILE = "trips_history.json"
        private const val K_PLACES_FILE = "saved_places.json"
        private const val K_SPKLU_FILE = "user_spklu.json"
        private const val K_NOTES_FILE = "field_notes.json"
        private const val K_MAINT_FILE = "maint_logs.json"
        private const val K_CALIB_FILE = "calib_logs.json"
        private const val K_MATRIX_FILE = "matrix_queue.json"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    suspend fun loadSettings(): AppSettings = withContext(Dispatchers.IO) {
        val raw = prefs.getString(K_SETTINGS, null)
        if (raw.isNullOrBlank()) return@withContext AppSettings()
        try {
            val loaded = json.decodeFromString(AppSettings.serializer(), raw)
            if (loaded.mapsSnapWidth == 240 && loaded.mapsSnapHeight == 180) {
                loaded.copy(mapsSnapWidth = 350, mapsSnapHeight = 220)
            } else {
                loaded
            }
        } catch (_: Exception) {
            AppSettings()
        }
    }

    suspend fun saveSettings(settings: AppSettings) = withContext(Dispatchers.IO) {
        try {
            prefs.edit().putString(K_SETTINGS, json.encodeToString(AppSettings.serializer(), settings)).apply()
        } catch (_: Exception) {
        }
    }

    suspend fun loadParkedLocation(): ParkedLocation? = withContext(Dispatchers.IO) {
        val raw = prefs.getString(K_PARKED, null)
        if (raw.isNullOrBlank()) return@withContext null
        try {
            json.decodeFromString(ParkedLocation.serializer(), raw)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun saveParkedLocation(loc: ParkedLocation) = withContext(Dispatchers.IO) {
        try {
            prefs.edit().putString(K_PARKED, json.encodeToString(ParkedLocation.serializer(), loc)).apply()
        } catch (_: Exception) {
        }
    }

    suspend fun loadCachedGeocode(): CachedGeocode? = withContext(Dispatchers.IO) {
        val raw = prefs.getString(K_LAST_GEO, null)
        if (raw.isNullOrBlank()) return@withContext null
        try {
            json.decodeFromString(CachedGeocode.serializer(), raw)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun saveCachedGeocode(road: String, area: String, lat: Double, lng: Double) =
        withContext(Dispatchers.IO) {
            try {
                val payload = json.encodeToString(
                    CachedGeocode.serializer(),
                    CachedGeocode(road = road, area = area, lat = lat, lng = lng)
                )
                prefs.edit().putString(K_LAST_GEO, payload).apply()
            } catch (_: Exception) {
            }
        }

    private suspend fun <T> readJsonList(filename: String, serializer: KSerializer<T>): List<T> =
        withContext(Dispatchers.IO) {
            val file = File(context.filesDir, filename)
            if (!file.exists()) return@withContext emptyList()
            try {
                val raw = file.readText()
                json.decodeFromString(ListSerializer(serializer), raw)
            } catch (_: Exception) {
                emptyList()
            }
        }

    private suspend fun <T> writeJsonList(
        filename: String,
        serializer: KSerializer<T>,
        list: List<T>
    ) = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, filename)
            file.writeText(json.encodeToString(ListSerializer(serializer), list))
        } catch (_: Exception) {
        }
    }

    suspend fun loadTrips(): List<TripRecord> = readJsonList(K_TRIPS_FILE, TripRecord.serializer())
    suspend fun saveTrips(list: List<TripRecord>) = writeJsonList(K_TRIPS_FILE, TripRecord.serializer(), list)

    suspend fun loadPlaces(): List<SavedPlace> = readJsonList(K_PLACES_FILE, SavedPlace.serializer())
    suspend fun savePlaces(list: List<SavedPlace>) = writeJsonList(K_PLACES_FILE, SavedPlace.serializer(), list)

    suspend fun loadUserSpklu(): List<SpkluStation> = readJsonList(K_SPKLU_FILE, SpkluStation.serializer())
    suspend fun saveUserSpklu(list: List<SpkluStation>) = writeJsonList(K_SPKLU_FILE, SpkluStation.serializer(), list)

    suspend fun loadFieldNotes(): List<FieldNotePin> = readJsonList(K_NOTES_FILE, FieldNotePin.serializer())
    suspend fun saveFieldNotes(list: List<FieldNotePin>) = writeJsonList(K_NOTES_FILE, FieldNotePin.serializer(), list)

    suspend fun loadMaintLogs(): List<MaintenanceLogEntry> = readJsonList(K_MAINT_FILE, MaintenanceLogEntry.serializer())
    suspend fun saveMaintLogs(list: List<MaintenanceLogEntry>) = writeJsonList(K_MAINT_FILE, MaintenanceLogEntry.serializer(), list)

    suspend fun loadCalibrationLogs(): List<CalibrationEntry> = readJsonList(K_CALIB_FILE, CalibrationEntry.serializer())
    suspend fun saveCalibrationLogs(list: List<CalibrationEntry>) = writeJsonList(K_CALIB_FILE, CalibrationEntry.serializer(), list)

    suspend fun loadMatrixQueue(): List<MatrixQueueItem> {
        val loaded = readJsonList(K_MATRIX_FILE, MatrixQueueItem.serializer())
        if (loaded.isNotEmpty()) return loaded
        return listOf(
            MatrixQueueItem(id = "m1", text = "GESITS EV READY", enabled = true, speedMs = 45),
            MatrixQueueItem(id = "m2", text = "STAY SAFE ON ROAD", enabled = true, speedMs = 50)
        )
    }

    suspend fun saveMatrixQueue(list: List<MatrixQueueItem>) = writeJsonList(K_MATRIX_FILE, MatrixQueueItem.serializer(), list)

    suspend fun copyPickedImage(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val outFile = File(context.filesDir, "picked_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (outFile.exists() && outFile.length() > 0) outFile.absolutePath else null
        } catch (_: Exception) {
            null
        }
    }
}
