package com.dashboard.app.dashboard_app.hardware

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

data class NativeGpsFix(
    val lat: Double,
    val lng: Double,
    val altM: Double? = null,
    val speedMs: Double = 0.0,
    val bearingDeg: Double? = null,
    val accuracyM: Double? = null
)

class LocationAndTtsManager(private val context: Context) : LocationListener {

    companion object {
        fun navVoiceCodeForManeuver(type: String?, modifier: String?): Int {
            if (type == "arrive") return 14
            if (type == "roundabout" || type == "rotary") return 12
            return when (modifier) {
                "left", "sharp left" -> 8
                "slight left" -> 10
                "right", "sharp right" -> 9
                "slight right" -> 11
                "uturn" -> 13
                else -> 7
            }
        }
    }

    var onGpsFix: ((NativeGpsFix) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val locationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }
    private val regProviders = mutableSetOf<String>()

    private var gpsEmitCount = 0
    private var gpsLastEmitMs = 0L

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastSpokenMs = 0L

    @SuppressLint("MissingPermission")
    fun startGps(force: Boolean = false) {
        if (force) {
            regProviders.clear()
        }
        val hasFine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return

        try {
            val last = (if (hasFine) runCatching { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull() else null)
                ?: runCatching { locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
            if (last != null) emitLocation(last)
        } catch (_: Exception) {
        }

        if (hasFine && !regProviders.contains(LocationManager.GPS_PROVIDER)) {
            try {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        1000L,
                        0f,
                        this,
                        Looper.getMainLooper()
                    )
                    regProviders.add(LocationManager.GPS_PROVIDER)
                }
            } catch (_: Exception) {
            }
        }
        if (!regProviders.contains(LocationManager.NETWORK_PROVIDER)) {
            try {
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        3000L,
                        0f,
                        this,
                        Looper.getMainLooper()
                    )
                    regProviders.add(LocationManager.NETWORK_PROVIDER)
                }
            } catch (_: Exception) {
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                if (locationManager.allProviders.contains(LocationManager.FUSED_PROVIDER) &&
                    !regProviders.contains(LocationManager.FUSED_PROVIDER)
                ) {
                    locationManager.requestLocationUpdates(
                        LocationManager.FUSED_PROVIDER,
                        1500L,
                        0f,
                        this,
                        Looper.getMainLooper()
                    )
                    regProviders.add(LocationManager.FUSED_PROVIDER)
                }
            } catch (_: Exception) {
            }
        }

        if (!regProviders.contains(LocationManager.PASSIVE_PROVIDER)) {
            try {
                if (locationManager.allProviders.contains(LocationManager.PASSIVE_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.PASSIVE_PROVIDER,
                        5000L,
                        0f,
                        this,
                        Looper.getMainLooper()
                    )
                    regProviders.add(LocationManager.PASSIVE_PROVIDER)
                }
            } catch (_: Exception) {
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val prov = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    locationManager.allProviders.contains(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
                hasFine && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }
            if (prov != null) {
                try {
                    locationManager.getCurrentLocation(prov, null, context.mainExecutor) { loc ->
                        if (loc != null) emitLocation(loc)
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    fun stopGps() {
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {
        }
        regProviders.clear()
    }

    fun gpsDiagText(): String {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val gpsOn = runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        val netOn = runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        val age = if (gpsLastEmitMs == 0L) "belum pernah" else "${(System.currentTimeMillis() - gpsLastEmitMs) / 1000} dtk lalu"
        return buildString {
            appendLine("Izin lokasi presisi: ${if (fine) "ya" else "TIDAK"}")
            appendLine("Izin lokasi perkiraan: ${if (coarse) "ya" else "TIDAK"}")
            appendLine("GPS HP aktif: ${if (gpsOn) "ya" else "TIDAK (nyalakan Lokasi)"}")
            appendLine("Lokasi jaringan aktif: ${if (netOn) "ya" else "tidak"}")
            appendLine("Provider terdaftar: ${if (regProviders.isEmpty()) "tidak ada" else regProviders.joinToString()}")
            append("Fix diterima: $gpsEmitCount ($age)")
        }
    }

    override fun onLocationChanged(location: Location) {
        emitLocation(location)
    }

    @Deprecated("Deprecated in Android API")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onProviderEnabled(provider: String) {
        mainHandler.post { startGps() }
    }

    override fun onProviderDisabled(provider: String) {}

    private fun emitLocation(loc: Location) {
        gpsEmitCount++
        gpsLastEmitMs = System.currentTimeMillis()
        val fix = NativeGpsFix(
            lat = loc.latitude,
            lng = loc.longitude,
            altM = if (loc.hasAltitude()) loc.altitude else null,
            speedMs = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0,
            bearingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else null,
            accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null
        )
        mainHandler.post { onGpsFix?.invoke(fix) }
    }

    fun speak(text: String, force: Boolean = false) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSpokenMs < 4000L) return
        lastSpokenMs = now

        if (tts == null) {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.forLanguageTag("id-ID")
                    ttsReady = true
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav_${System.currentTimeMillis()}")
                }
            }
        } else if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav_${System.currentTimeMillis()}")
        }
    }

    fun dispose() {
        stopGps()
        tts?.shutdown()
        tts = null
    }
}
