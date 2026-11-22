package com.dashboard.app.dashboard_app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class PlatformToolsBridge(private val activity: Activity) :
    MethodChannel.MethodCallHandler, EventChannel.StreamHandler, LocationListener {

    companion object {
        const val REQ_PERMISSIONS = 9101
        const val REQ_PICK_IMAGE = 9102
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy {
        activity.getSharedPreferences("dashboard_app_prefs", Context.MODE_PRIVATE)
    }
    private val locationManager by lazy {
        activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    private var gpsSink: EventChannel.EventSink? = null
    private var gpsListening = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingPermResult: MethodChannel.Result? = null
    private var pendingPickResult: MethodChannel.Result? = null

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        gpsSink = events
        startGpsInternal()
    }

    override fun onCancel(arguments: Any?) {
        gpsSink = null
        stopGpsInternal()
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "prefsGet" -> {
                val key = call.argument<String>("key") ?: ""
                result.success(prefs.getString(key, null))
            }
            "prefsSet" -> {
                val key = call.argument<String>("key") ?: ""
                val value = call.argument<String>("value")
                if (value == null) {
                    prefs.edit().remove(key).apply()
                } else {
                    prefs.edit().putString(key, value).apply()
                }
                result.success(true)
            }
            "getDocsDir" -> {
                result.success(activity.filesDir.absolutePath)
            }
            "requestPermissions" -> {
                requestAllRuntimePermissions(result)
            }
            "startGps" -> {
                startGpsInternal()
                result.success(true)
            }
            "stopGps" -> {
                stopGpsInternal()
                result.success(true)
            }
            "speakTts" -> {
                val text = call.argument<String>("text") ?: ""
                speakText(text)
                result.success(true)
            }
            "setWakelock" -> {
                val enabled = call.argument<Boolean>("enabled") == true
                mainHandler.post {
                    if (enabled) {
                        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                result.success(true)
            }
            "shareText" -> {
                val text = call.argument<String>("text") ?: ""
                val subject = call.argument<String>("subject") ?: "Dashboard App"
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                activity.startActivity(Intent.createChooser(intent, subject))
                result.success(true)
            }
            "pickImage" -> {
                pendingPickResult = result
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                @Suppress("DEPRECATION")
                activity.startActivityForResult(Intent.createChooser(intent, "Pilih Gambar"), REQ_PICK_IMAGE)
            }
            "gpsDiag" -> result.success(gpsDiagText())
            else -> result.notImplemented()
        }
    }

    private var gpsEmitCount = 0
    private var gpsLastEmitMs = 0L

    @SuppressLint("MissingPermission")
    private fun gpsDiagText(): String {
        val fine = activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val gpsOn = runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        val netOn = runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        val age = if (gpsLastEmitMs == 0L) "belum pernah" else "${(System.currentTimeMillis() - gpsLastEmitMs) / 1000} dtk lalu"
        return "Izin lokasi presisi: ${if (fine) "ya" else "TIDAK"}\n" +
            "Izin lokasi perkiraan: ${if (coarse) "ya" else "TIDAK"}\n" +
            "GPS HP aktif: ${if (gpsOn) "ya" else "TIDAK (nyalakan Lokasi)"}\n" +
            "Lokasi jaringan aktif: ${if (netOn) "ya" else "tidak"}\n" +
            "Provider terdaftar: ${if (regProviders.isEmpty()) "tidak ada" else regProviders.joinToString()}\n" +
            "Penerima data aplikasi: ${if (gpsSink != null) "siap" else "BELUM"}\n" +
            "Fix diterima: $gpsEmitCount ($age)"
    }

    private fun requestAllRuntimePermissions(result: MethodChannel.Result) {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_SCAN)
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startGpsInternal()
            result.success(true)
            return
        }
        // Selesaikan permintaan lama agar Future di Dart tidak menggantung
        pendingPermResult?.success(false)
        pendingPermResult = result
        activity.requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
    }

    fun onRequestPermissionsResult(requestCode: Int) {
        if (requestCode == REQ_PERMISSIONS) {
            startGpsInternal()
            pendingPermResult?.success(true)
            pendingPermResult = null
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_PICK_IMAGE) {
            val res = pendingPickResult
            pendingPickResult = null
            if (resultCode == Activity.RESULT_OK && data?.data != null) {
                val path = copyUriToFiles(data.data!!)
                res?.success(path)
            } else {
                res?.success(null)
            }
        }
    }

    private fun copyUriToFiles(uri: Uri): String? {
        return try {
            val outFile = File(activity.filesDir, "picked_${System.currentTimeMillis()}.jpg")
            activity.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
            outFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private val regProviders = mutableSetOf<String>()

    @SuppressLint("MissingPermission")
    private fun startGpsInternal() {
        val hasFine = activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return

        // Posisi terakhir yang diketahui (langsung ditampilkan)
        try {
            val last = (if (hasFine) runCatching { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull() else null)
                ?: runCatching { locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
            if (last != null) emitLocation(last)
        } catch (_: Exception) {
        }

        // Daftarkan tiap provider sendiri-sendiri; gagal satu tidak membatalkan yang lain.
        if (hasFine && !regProviders.contains(LocationManager.GPS_PROVIDER)) {
            try {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
                    regProviders.add(LocationManager.GPS_PROVIDER)
                }
            } catch (_: Exception) {
            }
        }
        if (!regProviders.contains(LocationManager.NETWORK_PROVIDER)) {
            try {
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 0f, this, Looper.getMainLooper())
                    regProviders.add(LocationManager.NETWORK_PROVIDER)
                }
            } catch (_: Exception) {
            }
        }
        gpsListening = regProviders.isNotEmpty()

        // Fix cepat satu kali (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val prov = when {
                hasFine && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }
            if (prov != null) {
                try {
                    locationManager.getCurrentLocation(prov, null, activity.mainExecutor) { loc ->
                        if (loc != null) emitLocation(loc)
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun stopGpsInternal() {
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {
        }
        regProviders.clear()
        gpsListening = false
    }

    override fun onLocationChanged(location: Location) {
        emitLocation(location)
    }

    @Deprecated("Deprecated in Android API")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {
        mainHandler.post { startGpsInternal() }
    }
    override fun onProviderDisabled(provider: String) {}

    private fun emitLocation(loc: Location) {
        gpsEmitCount++
        gpsLastEmitMs = System.currentTimeMillis()
        mainHandler.post {
            gpsSink?.success(
                mapOf(
                    "lat" to loc.latitude,
                    "lng" to loc.longitude,
                    "alt" to if (loc.hasAltitude()) loc.altitude else null,
                    "speedMs" to if (loc.hasSpeed()) loc.speed.toDouble() else 0.0,
                    "bearing" to if (loc.hasBearing()) loc.bearing.toDouble() else null,
                    "accuracy" to if (loc.hasAccuracy()) loc.accuracy.toDouble() else null
                )
            )
        }
    }

    private fun speakText(text: String) {
        if (text.isBlank()) return
        if (tts == null) {
            tts = TextToSpeech(activity.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale("id", "ID")
                    ttsReady = true
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav_${System.currentTimeMillis()}")
                }
            }
        } else if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav_${System.currentTimeMillis()}")
        }
    }

    fun dispose() {
        stopGpsInternal()
        tts?.shutdown()
        tts = null
    }
}
