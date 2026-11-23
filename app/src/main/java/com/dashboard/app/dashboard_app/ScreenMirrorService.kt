package com.dashboard.app.dashboard_app

import android.app.Activity
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.text.TextPaint
import android.text.TextUtils
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ScreenMirrorService : Service() {

    companion object {
        private const val TAG = "ScreenMirror"
        const val ACTION_START = "com.dashboard.app.ACTION_START_MIRROR"
        const val ACTION_STOP = "com.dashboard.app.ACTION_STOP_MIRROR"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data_intent"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_INTERVAL_MS = "interval_ms"
        const val EXTRA_CROP = "crop_rect"
        const val EXTRA_OVERLAY_STRIP = "overlay_strip"

        private const val CHANNEL_ID = "mid_screen_mirror_channel"
        private const val NOTIF_ID = 7071
        private const val MAX_CHARS = 7000
        private const val SAFE_TOP = 0.17f
        private const val SAFE_BOTTOM = 0.88f

        @Volatile var onFrameReady: ((String) -> Unit)? = null
        @Volatile var onMirrorStateChanged: ((Boolean) -> Unit)? = null
        @Volatile var onExternalNavLine: ((String) -> Unit)? = null
        @Volatile var isRunning: Boolean = false
            private set

        @Volatile var reqOutWidth: Int = 350
        @Volatile var reqOutHeight: Int = 220

        // Live Google Maps notification data (populated by MapsNotifListener)
        @Volatile var navDist: String = ""
        @Volatile var navInstr: String = ""
        @Volatile var navEta: String = ""
        @Volatile var navUpdatedMs: Long = 0L
        @Volatile var navIcon: Bitmap? = null
        @Volatile var overlayStripEnabled: Boolean = true
        @Volatile var overlayTop: Boolean = false

        fun startService(
            context: Context,
            resultCode: Int,
            dataIntent: Intent,
            width: Int,
            height: Int,
            quality: Int = 72,
            intervalMs: Long = 900L,
            crop: FloatArray = floatArrayOf(0f, 0.10f, 1f, 0.78f),
            overlayStrip: Boolean = true
        ) {
            reqOutWidth = width.coerceIn(160, 480)
            reqOutHeight = height.coerceIn(120, 360)
            overlayStripEnabled = overlayStrip
            val intent = Intent(context, ScreenMirrorService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, dataIntent)
                putExtra(EXTRA_WIDTH, width)
                putExtra(EXTRA_HEIGHT, height)
                putExtra(EXTRA_QUALITY, quality)
                putExtra(EXTRA_INTERVAL_MS, intervalMs)
                putExtra(EXTRA_CROP, crop)
                putExtra(EXTRA_OVERLAY_STRIP, overlayStrip)
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    private var outWidth = 350
    private var outHeight = 220
    private var jpegQuality = 72
    private var intervalMs = 900L
    private var crop = floatArrayOf(0f, 0.10f, 1f, 0.78f)

    private var capWidth = 720
    private var capHeight = 1440
    private var lastDispW = 0
    private var lastDispH = 0
    @Volatile private var projectionDead = false

    private var lastCardKey = ""
    private var lastCardMs = 0L

    private val frameTick = object : Runnable {
        override fun run() {
            if (!isRunning) return
            try {
                captureOrSendCard()
            } catch (e: Exception) {
                Log.w(TAG, "Frame tick error: ${e.message}")
            }
            if (isRunning) {
                workerHandler?.postDelayed(this, intervalMs)
            }
        }
    }

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
                reqOutWidth = outWidth
                reqOutHeight = outHeight
                jpegQuality = intent.getIntExtra(EXTRA_QUALITY, 72).coerceIn(15, 90)
                intervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, 900L).coerceIn(350L, 5000L)
                intent.getFloatArrayExtra(EXTRA_CROP)?.takeIf { it.size == 4 }?.let { crop = it }
                overlayStripEnabled = intent.getBooleanExtra(EXTRA_OVERLAY_STRIP, overlayStripEnabled)

                startForegroundCompat()

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

    private fun startForegroundCompat() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    @Suppress("DEPRECATION")
    private fun getRealScreenMetrics(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            bounds.width().coerceAtLeast(1) to bounds.height().coerceAtLeast(1)
        } else {
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels.coerceAtLeast(1) to dm.heightPixels.coerceAtLeast(1)
        }
    }

    private fun computeCaptureDimensions(): Pair<Int, Int> {
        val (dispW, dispH) = getRealScreenMetrics()
        lastDispW = dispW
        lastDispH = dispH
        val targetShort = max(outWidth, outHeight) * 2
        val scale = targetShort.toDouble() / min(dispW, dispH).toDouble()
        val w = (dispW * scale).roundToInt().coerceIn(240, 1440)
        val h = (dispH * scale).roundToInt().coerceIn(240, 1440)
        return w to h
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        stopProjectionOnly()
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mgr.getMediaProjection(resultCode, data) ?: run {
            stopSelf()
            return
        }
        mediaProjection = projection
        projectionDead = false

        val thread = HandlerThread("MidMirrorThread").apply { start() }
        workerThread = thread
        val handler = Handler(thread.looper)
        workerHandler = handler

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (isRunning && isDeviceLockedOrOff()) {
                    // Android stops projection while screen locks; keep running lockscreen nav cards
                    projectionDead = true
                    releaseCaptureOnly()
                } else {
                    stopMirroring()
                    stopSelf()
                }
            }
        }, mainHandler)

        val (cw, ch) = computeCaptureDimensions()
        capWidth = cw
        capHeight = ch
        val reader = ImageReader.newInstance(capWidth, capHeight, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = projection.createVirtualDisplay(
            "DashboardMidMirror",
            capWidth,
            capHeight,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DashboardApp:MidMirror").apply {
                acquire(8 * 60 * 60 * 1000L)
            }
        } catch (_: Exception) {
        }

        isRunning = true
        onMirrorStateChanged?.invoke(true)
        handler.postDelayed(frameTick, 500L)
    }

    private fun isScreenOff(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm != null && !pm.isInteractive
    }

    private fun isKeyguardLocked(): Boolean {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km != null && km.isKeyguardLocked
    }

    private fun isDeviceLockedOrOff(): Boolean = isScreenOff() || isKeyguardLocked()

    private fun maybeResizeForOrientation() {
        val (dispW, dispH) = getRealScreenMetrics()
        if (dispW == lastDispW && dispH == lastDispH) return
        val (newW, newH) = computeCaptureDimensions()
        if (newW == capWidth && newH == capHeight) return
        capWidth = newW
        capHeight = newH
        val nextReader = ImageReader.newInstance(capWidth, capHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay?.resize(capWidth, capHeight, resources.displayMetrics.densityDpi)
        virtualDisplay?.surface = nextReader.surface
        val oldReader = imageReader
        imageReader = nextReader
        try {
            oldReader?.close()
        } catch (_: Exception) {
        }
    }

    private fun captureOrSendCard() {
        val screenOff = isScreenOff()
        val keyguard = isKeyguardLocked()

        if (screenOff) {
            try {
                if (!projectionDead) imageReader?.acquireLatestImage()?.close()
            } catch (_: Exception) {
            }
            sendNavCard()
            return
        }

        if (projectionDead || imageReader == null) {
            if (keyguard) {
                sendNavCard()
                return
            }
            stopMirroring()
            stopSelf()
            return
        }

        maybeResizeForOrientation()
        val img = imageReader?.acquireLatestImage() ?: return
        val fullBmp: Bitmap = try {
            val plane = img.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride.coerceAtLeast(1)
            val rowStride = plane.rowStride
            val imgW = img.width
            val imgH = img.height
            val rowPadding = (rowStride - pixelStride * imgW).coerceAtLeast(0)
            val raw = Bitmap.createBitmap(
                imgW + rowPadding / pixelStride,
                imgH,
                Bitmap.Config.ARGB_8888
            )
            raw.copyPixelsFromBuffer(buffer)
            if (raw.width != imgW || raw.height != imgH) {
                val clean = Bitmap.createBitmap(raw, 0, 0, imgW, imgH)
                raw.recycle()
                clean
            } else {
                raw
            }
        } finally {
            img.close()
        }

        // Cover-crop user region to target MID aspect ratio (eliminates portrait black bars)
        val fw = fullBmp.width
        val fh = fullBmp.height
        val l = (fw * crop[0]).roundToInt().coerceIn(0, fw - 1)
        val t = (fh * crop[1]).roundToInt().coerceIn(0, fh - 1)
        val r = (fw * crop[2]).roundToInt().coerceIn(l + 1, fw)
        val b = (fh * crop[3]).roundToInt().coerceIn(t + 1, fh)
        val rw = max(1, r - l)
        val rh = max(1, b - t)
        val ratio = outWidth.toFloat() / outHeight.toFloat()

        val sw: Int
        val sh: Int
        if (rw.toFloat() / rh.toFloat() > ratio) {
            sh = rh
            sw = (sh * ratio).roundToInt().coerceIn(1, rw)
        } else {
            sw = rw
            sh = (sw / ratio).roundToInt().coerceIn(1, rh)
        }
        val sx = (l + (rw - sw) / 2f).roundToInt().coerceIn(0, fw - sw)
        val sy = (t + (rh - sh) / 2f).roundToInt().coerceIn(0, fh - sh)

        val outBmp = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outBmp)
        canvas.drawBitmap(
            fullBmp,
            Rect(sx, sy, sx + sw, sy + sh),
            Rect(0, 0, outWidth, outHeight),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        fullBmp.recycle()

        if (keyguard && looksBlank(outBmp)) {
            outBmp.recycle()
            sendNavCard()
            return
        }

        if (overlayStripEnabled) {
            drawInfoStrip(canvas)
        }

        val b64 = encodeUnderLimit(outBmp)
        outBmp.recycle()
        if (b64 != null) {
            onFrameReady?.invoke(b64)
        }
    }

    private fun looksBlank(b: Bitmap): Boolean {
        val w = b.width
        val h = b.height
        var dark = 0
        var count = 0
        var y = 4
        while (y < h) {
            var x = 4
            while (x < w) {
                val px = b.getPixel(x, y)
                val lum = (Color.red(px) * 3 + Color.green(px) * 6 + Color.blue(px)) / 10
                if (lum < 24) dark++
                count++
                x += 12
            }
            y += 12
        }
        return count > 0 && dark * 100 >= count * 97
    }

    private fun sendNavCard() {
        val now = System.currentTimeMillis()
        if (now - navUpdatedMs > 120_000L) return
        if (navInstr.isEmpty() && navDist.isEmpty()) return
        val key = "$navDist|$navInstr|$navEta"
        if (key == lastCardKey && now - lastCardMs < 10_000L) return

        val card = renderNavCard()
        val b64 = encodeUnderLimit(card)
        card.recycle()
        if (b64 != null) {
            lastCardKey = key
            lastCardMs = now
            onFrameReady?.invoke(b64)
        }
    }

    /** High-contrast black/white turn card for locked/off screen, padded for MID overlays. */
    private fun renderNavCard(): Bitmap {
        val w = outWidth
        val h = outHeight
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)

        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isFakeBoldText = true
            color = Color.WHITE
        }

        val pad = w * 0.04f
        val top = h * SAFE_TOP
        val bottom = h * SAFE_BOTTOM
        val rowH = (bottom - top) * 0.48f
        var colX = pad

        val icon = navIcon
        if (icon != null && !icon.isRecycled && icon.width > 0 && icon.height > 0) {
            val side = rowH
            val sc = side / max(icon.width, icon.height).toFloat()
            val dw = icon.width * sc
            val dh = icon.height * sc
            val dx = pad + (side - dw) / 2f
            val dy = top + (side - dh) / 2f
            val ip = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            }
            c.drawBitmap(icon, null, RectF(dx, dy, dx + dw, dy + dh), ip)
            colX = pad + side + pad
        }

        val availW = (w - colX - pad).coerceAtLeast(24f)
        val dist = navDist.ifEmpty { "NAV" }
        var size = rowH * 0.64f
        p.textSize = size
        while (size > 14f && p.measureText(dist) > availW) {
            size -= 2f
            p.textSize = size
        }
        val distBase = top + size * 0.82f
        c.drawText(dist, colX, distBase, p)

        if (navEta.isNotEmpty()) {
            val parts = navEta.split(Regex("\\s*[·•]\\s*"))
            val eta = if (parts.size >= 2) "${parts[0]} · ${parts[1]}" else navEta
            p.textSize = h * 0.09f
            c.drawText(
                TextUtils.ellipsize(eta, p, availW, TextUtils.TruncateAt.END).toString(),
                colX,
                distBase + h * 0.115f,
                p
            )
        }

        if (navInstr.isNotEmpty()) {
            p.textSize = h * 0.12f
            drawWrapped(c, p, navInstr, pad, h * 0.655f, w - 2 * pad, h * 0.13f, 2)
        }
        return bmp
    }

    private fun drawWrapped(
        c: Canvas,
        p: TextPaint,
        text: String,
        x: Float,
        y: Float,
        maxW: Float,
        lineH: Float,
        maxLines: Int
    ) {
        var rest = text.trim()
        for (n in 0 until maxLines) {
            if (rest.isEmpty()) break
            val yy = y + n * lineH
            if (n == maxLines - 1) {
                c.drawText(TextUtils.ellipsize(rest, p, maxW, TextUtils.TruncateAt.END).toString(), x, yy, p)
                break
            }
            val cnt = p.breakText(rest, true, maxW, null)
            if (cnt >= rest.length) {
                c.drawText(rest, x, yy, p)
                break
            }
            var cut = rest.lastIndexOf(' ', cnt)
            if (cut <= 0) cut = cnt
            c.drawText(rest.substring(0, cut), x, yy, p)
            rest = rest.substring(cut).trim()
        }
    }

    private fun drawInfoStrip(c: Canvas) {
        if (System.currentTimeMillis() - navUpdatedMs > 120_000L) return
        val dist = navDist
        val instr = navInstr
        val eta = navEta
        if (instr.isEmpty() && eta.isEmpty()) return

        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 13f
        }
        val rowH = 16
        val row1 = !(dist.isEmpty() && instr.isEmpty())
        val row2 = eta.isNotEmpty()
        val h = ((if (row1) 1 else 0) + (if (row2) 1 else 0)) * rowH + 4
        val y0 = if (overlayTop) 0 else outHeight - h

        c.drawRect(0f, y0.toFloat(), outWidth.toFloat(), (y0 + h).toFloat(), Paint().apply {
            color = 0xFF0E1620.toInt()
        })

        var base = (y0 + 2 + rowH - 4).toFloat()
        if (row1) {
            var x = 4f
            if (dist.isNotEmpty()) {
                tp.color = 0xFFFFC107.toInt()
                c.drawText(dist, x, base, tp)
                x += tp.measureText(dist) + 6f
            }
            tp.color = Color.WHITE
            val clipped = TextUtils.ellipsize(instr, tp, (outWidth - x - 4f).coerceAtLeast(16f), TextUtils.TruncateAt.END)
            c.drawText(clipped.toString(), x, base, tp)
            base += rowH
        }
        if (row2) {
            tp.color = 0xFF80E27E.toInt()
            val clipped = TextUtils.ellipsize(eta, tp, (outWidth - 8f).coerceAtLeast(16f), TextUtils.TruncateAt.END)
            c.drawText(clipped.toString(), 4f, base, tp)
        }
    }

    private fun encodeUnderLimit(bmp: Bitmap): String? {
        var q = max(jpegQuality, 76)
        while (q >= 6) {
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, q, bos)
            val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            if (b64.length <= MAX_CHARS) return b64
            q -= 5
        }
        return null
    }

    private fun releaseCaptureOnly() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
    }

    private fun stopProjectionOnly() {
        releaseCaptureOnly()
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        try { workerHandler?.removeCallbacksAndMessages(null) } catch (_: Exception) {}
        try { workerThread?.quitSafely() } catch (_: Exception) {}
        workerThread = null
        workerHandler = null
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
    }

    private fun stopMirroring() {
        val wasRunning = isRunning
        isRunning = false
        MirrorRegionOverlay.hide()
        stopProjectionOnly()
        if (wasRunning) {
            onMirrorStateChanged?.invoke(false)
        }
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
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
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
            .setOngoing(true)
            .build()
    }
}
