package com.dashboard.app.dashboard_app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps GPS updates, Bluetooth AT+NAV / AT+TRIP, and MID AT+MAPS rendering alive
 * while the rider locks or turns off the phone screen to conserve battery.
 */
class MidForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "mid_nav_bg_channel"
        private const val NOTIF_ID = 7072
        const val ACTION_START_OR_UPDATE = "com.dashboard.app.ACTION_START_MID_BG"
        const val ACTION_STOP = "com.dashboard.app.ACTION_STOP_MID_BG"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"

        @Volatile
        var isRunning: Boolean = false
            private set

        private var lastStatusKey: String = ""

        fun canStartWithCurrentPermissions(context: Context): Boolean {
            val hasLoc = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            val hasBt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH
                ) == PackageManager.PERMISSION_GRANTED
            }
            return hasLoc || hasBt
        }

        fun startOrUpdate(context: Context, title: String, text: String) {
            if (!canStartWithCurrentPermissions(context)) return
            val key = "$title|$text"
            if (isRunning && key == lastStatusKey) return
            lastStatusKey = key
            val intent = Intent(context, MidForegroundService::class.java).apply {
                action = ACTION_START_OR_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            if (!isRunning) {
                lastStatusKey = ""
                return
            }
            lastStatusKey = ""
            try {
                context.stopService(Intent(context, MidForegroundService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
            ?: "Cockpit & Navigasi Aktif"
        val text = intent?.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() }
            ?: "AT+MAPS & AT+NAV tetap berjalan saat layar terkunci"

        val notif = buildNotification(title, text)
        val fgsType = resolveForegroundTypes()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && fgsType == 0) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        val started = runCatching {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, fgsType)
            true
        }.getOrDefault(false)

        if (!started) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        isRunning = true
        ensureWakeLock()
        return START_STICKY
    }

    private fun resolveForegroundTypes(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var mask = 0
        val hasLoc = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        if (hasLoc) {
            mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }

        val hasBt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
        }
        if (hasBt && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else if (hasBt && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        return mask
    }

    private fun ensureWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DashboardApp:MidNavWake").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun stopSelfSafely() {
        isRunning = false
        lastStatusKey = ""
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        lastStatusKey = ""
        releaseWakeLock()
        super.onDestroy()
    }

    private fun buildNotification(title: String, text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Navigasi & Stream MID",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            nm?.createNotificationChannel(ch)
        }
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            101,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }
}
