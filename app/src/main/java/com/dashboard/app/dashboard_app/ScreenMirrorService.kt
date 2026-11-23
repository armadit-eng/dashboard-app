package com.dashboard.app.dashboard_app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
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
import kotlin.math.max
import kotlin.math.min
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

        fun startService(
            context: Context,
            resultCode: Int,
            dataIntent: Intent,
            width: Int,
            height: Int,
            quality: Int = 55,
            intervalMs: Long = 500L
        ) {
            val intent = Intent(context, ScreenMirrorService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, dataIntent)
                putExtra(EXTRA_WIDTH, width)
                putExtra(EXTRA_HEIGHT, height)
                putExtra(EXTRA_QUALITY, quality)
                putExtra(EXTRA_INTERVAL_MS, intervalMs)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ScreenMirrorService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var lastFrameAt = 0L

    private var outWidth = 350
    private var outHeight = 220
    private var jpegQuality = 55
    private var intervalMs = 500L
    private var capWidth = 700
    private var capHeight = 1400
    private var lastDispW = 0
    private var lastDispH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMirroring()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                outWidth = intent.getIntExtra(EXTRA_WIDTH, 350).coerceIn(160, 480)
                outHeight = intent.getIntExtra(EXTRA_HEIGHT, 220).coerceIn(120, 360)
                jpegQuality = intent.getIntExtra(EXTRA_QUALITY, 55).coerceIn(15, 90)
                intervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, 500L).coerceIn(200L, 5000L)

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

    private fun computeCaptureDimensions(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val dispW = dm.widthPixels.coerceAtLeast(1)
        val dispH = dm.heightPixels.coerceAtLeast(1)
        lastDispW = dispW
        lastDispH = dispH
        val targetShort = max(outWidth, outHeight) * 2
        val scale = targetShort.toDouble() / min(dispW, dispH).toDouble()
        val w = (dispW * scale).roundToInt().coerceIn(240, 1440)
        val h = (dispH * scale).roundToInt().coerceIn(240, 1440)
        return w to h
    }

    private fun attachImageReader(
        reader: ImageReader,
        handler: Handler
    ) {
        reader.setOnImageAvailableListener({ ir ->
            val dm = resources.displayMetrics
            if (dm.widthPixels != lastDispW || dm.heightPixels != lastDispH) {
                val (newW, newH) = computeCaptureDimensions()
                if (newW != capWidth || newH != capHeight) {
                    capWidth = newW
                    capHeight = newH
                    val nextReader = ImageReader.newInstance(capWidth, capHeight, PixelFormat.RGBA_8888, 2)
                    attachImageReader(nextReader, handler)
                    virtualDisplay?.resize(capWidth, capHeight, dm.densityDpi)
                    virtualDisplay?.surface = nextReader.surface
                    val oldReader = imageReader
                    imageReader = nextReader
                    try {
                        oldReader?.close()
                    } catch (_: Exception) {
                    }
                    return@setOnImageAvailableListener
                }
            }

            val now = System.currentTimeMillis()
            val img = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (now - lastFrameAt < intervalMs) return@setOnImageAvailableListener
                lastFrameAt = now

                val imgW = img.width
                val imgH = img.height
                val plane = img.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride.coerceAtLeast(1)
                val rowStride = plane.rowStride
                val rowPadding = (rowStride - pixelStride * imgW).coerceAtLeast(0)
                val rawBmp = Bitmap.createBitmap(
                    imgW + rowPadding / pixelStride,
                    imgH,
                    Bitmap.Config.ARGB_8888
                )
                rawBmp.copyPixelsFromBuffer(buffer)
                val cropped = if (rawBmp.width != imgW || rawBmp.height != imgH) {
                    val c = Bitmap.createBitmap(rawBmp, 0, 0, imgW, imgH)
                    rawBmp.recycle()
                    c
                } else {
                    rawBmp
                }

                // Aspect-fill crop onto outWidth x outHeight so portrait/landscape fills the MID without black bars
                val targetAspect = outWidth.toFloat() / outHeight.toFloat()
                val srcAspect = imgW.toFloat() / imgH.toFloat()
                val srcRect = if (srcAspect < targetAspect) {
                    val cropH = (imgW / targetAspect).roundToInt().coerceIn(1, imgH)
                    val top = ((imgH - cropH) * 0.38f).roundToInt().coerceIn(0, imgH - cropH)
                    Rect(0, top, imgW, top + cropH)
                } else if (srcAspect > targetAspect) {
                    val cropW = (imgH * targetAspect).roundToInt().coerceIn(1, imgW)
                    val left = ((imgW - cropW) / 2f).roundToInt().coerceIn(0, imgW - cropW)
                    Rect(left, 0, left + cropW, imgH)
                } else {
                    Rect(0, 0, imgW, imgH)
                }

                val outBmp = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(outBmp)
                canvas.drawBitmap(cropped, srcRect, Rect(0, 0, outWidth, outHeight), Paint(Paint.FILTER_BITMAP_FLAG))
                cropped.recycle()

                var q = jpegQuality
                var b64: String
                while (true) {
                    val baos = ByteArrayOutputStream()
                    outBmp.compress(Bitmap.CompressFormat.JPEG, q, baos)
                    b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                    if (b64.length <= 7000 || q <= 8) break
                    q -= 7
                }
                outBmp.recycle()
                if (b64.length <= 7000) onFrameReady?.invoke(b64)
            } catch (_: Exception) {
            } finally {
                img.close()
            }
        }, handler)
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

        val (cw, ch) = computeCaptureDimensions()
        capWidth = cw
        capHeight = ch
        val reader = ImageReader.newInstance(capWidth, capHeight, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        attachImageReader(reader, handler)

        virtualDisplay = projection.createVirtualDisplay(
            "DashboardMidMirror",
            capWidth,
            capHeight,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )

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
