package com.dashboard.app.dashboard_app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

class ScreenMirrorService : Service() {

    companion object {
        const val ACTION_START = "com.dashboard.app.ACTION_START_MIRROR"
        const val ACTION_STOP = "com.dashboard.app.ACTION_STOP_MIRROR"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data_intent"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_INTERVAL_MS = "interval_ms"

        private const val CHANNEL_ID = "mid_screen_mirror_channel"
        private const val NOTIF_ID = 7071

        @Volatile
        var onFrameReady: ((String) -> Unit)? = null

        @Volatile
        var onMirrorStateChanged: ((Boolean) -> Unit)? = null

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var lastFrameAt = 0L

    private var outWidth = 240
    private var outHeight = 180
    private var jpegQuality = 55
    private var intervalMs = 450L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMirroring()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                outWidth = intent.getIntExtra(EXTRA_WIDTH, 240).coerceIn(160, 480)
                outHeight = intent.getIntExtra(EXTRA_HEIGHT, 180).coerceIn(120, 360)
                jpegQuality = intent.getIntExtra(EXTRA_QUALITY, 55).coerceIn(15, 90)
                intervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, 450L).coerceIn(200L, 5000L)

                startForeground(NOTIF_ID, buildNotification())
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val dataIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }

                if (resultCode == Activity.RESULT_OK && dataIntent != null) {
                    startProjection(resultCode, dataIntent)
                } else {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        stopProjectionOnly()
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mgr.getMediaProjection(resultCode, data) ?: run {
            stopSelf()
            return
        }
        mediaProjection = projection
        val thread = HandlerThread("MidMirrorThread").apply { start() }
        workerThread = thread
        val handler = Handler(thread.looper)
        workerHandler = handler

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopMirroring()
                stopSelf()
            }
        }, handler)

        val capW = outWidth * 2
        val capH = outHeight * 2
        val reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = projection.createVirtualDisplay(
            "DashboardMidMirror",
            capW,
            capH,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )

        reader.setOnImageAvailableListener({ ir ->
            val now = System.currentTimeMillis()
            val img = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (now - lastFrameAt < intervalMs) return@setOnImageAvailableListener
                lastFrameAt = now

                val plane = img.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * capW
                val rawBmp = Bitmap.createBitmap(
                    capW + (rowPadding.toFloat() / pixelStride).roundToInt(),
                    capH,
                    Bitmap.Config.ARGB_8888
                )
                rawBmp.copyPixelsFromBuffer(buffer)
                val cropped = Bitmap.createBitmap(rawBmp, 0, 0, capW, capH)
                if (cropped != rawBmp) rawBmp.recycle()
                val scaled = Bitmap.createScaledBitmap(cropped, outWidth, outHeight, true)
                if (scaled != cropped) cropped.recycle()

                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)
                scaled.recycle()
                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                onFrameReady?.invoke(b64)
            } catch (_: Exception) {
            } finally {
                img.close()
            }
        }, handler)

        isRunning = true
        onMirrorStateChanged?.invoke(true)
    }

    private fun stopProjectionOnly() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
        try {
            workerThread?.quitSafely()
        } catch (_: Exception) {
        }
        workerThread = null
        workerHandler = null
    }

    private fun stopMirroring() {
        stopProjectionOnly()
        isRunning = false
        onMirrorStateChanged?.invoke(false)
    }

    override fun onDestroy() {
        stopMirroring()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "MID Screen Mirror",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(ch)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Dashboard App Screen Mirror")
            .setContentText("Mengirim tampilan ke layar MID motor via AT+MAPS")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
    }
}
