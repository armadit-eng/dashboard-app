package com.dashboard.app.dashboard_app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class CaptureBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Draws a non-touchable green border slightly OUTSIDE the cropped capture region
 * so riders can preview the exact area sent to the MID without the border leaking
 * into the captured frames. Also provides unified capture-rect math used by ScreenMirrorService.
 */
object MirrorRegionOverlay {
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val autoHideTask = Runnable { hideNow() }
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    fun canDraw(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun computeCaptureBounds(
        fw: Int,
        fh: Int,
        outW: Int,
        outH: Int,
        offsetX: Float,
        offsetY: Float,
        scalePct: Int
    ): CaptureBounds {
        val safeFw = fw.coerceAtLeast(32)
        val safeFh = fh.coerceAtLeast(32)
        val ratio = outW.coerceAtLeast(16).toFloat() / outH.coerceAtLeast(16).toFloat()
        val scale = (scalePct / 100f).coerceIn(0.35f, 1.0f)
        val maxPossibleW = min(safeFw.toFloat(), safeFh.toFloat() * ratio)
        val sw = (maxPossibleW * scale).roundToInt().coerceIn(32, safeFw)
        val sh = (sw / ratio).roundToInt().coerceIn(32, safeFh)
        val maxX = (safeFw - sw).coerceAtLeast(0)
        val maxY = (safeFh - sh).coerceAtLeast(0)
        val sx = (offsetX.coerceIn(0f, 1f) * maxX).roundToInt().coerceIn(0, maxX)
        val sy = (offsetY.coerceIn(0f, 1f) * maxY).roundToInt().coerceIn(0, maxY)
        return CaptureBounds(sx, sy, sx + sw, sy + sh)
    }

    fun computeCaptureRect(
        fw: Int,
        fh: Int,
        outW: Int,
        outH: Int,
        offsetX: Float,
        offsetY: Float,
        scalePct: Int
    ): Rect {
        val b = computeCaptureBounds(fw, fh, outW, outH, offsetX, offsetY, scalePct)
        return Rect(b.left, b.top, b.right, b.bottom)
    }

    fun show(
        context: Context,
        offsetX: Float = ScreenMirrorService.mirrorOffsetX,
        offsetY: Float = ScreenMirrorService.mirrorOffsetY,
        scalePct: Int = ScreenMirrorService.mirrorScalePct,
        autoHideMs: Long = 5_000L
    ) {
        val app = context.applicationContext
        mainHandler.post { showNow(app, offsetX, offsetY, scalePct, autoHideMs) }
    }

    fun hide() {
        mainHandler.post { hideNow() }
    }

    @Suppress("DEPRECATION")
    private fun showNow(
        app: Context,
        offsetX: Float,
        offsetY: Float,
        scalePct: Int,
        autoHideMs: Long
    ) {
        hideNow()
        if (!canDraw(app)) return
        try {
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = wm
            val (fw, fh, density) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = wm.maximumWindowMetrics.bounds
                Triple(b.width(), b.height(), app.resources.displayMetrics.density)
            } else {
                val dm = DisplayMetrics()
                wm.defaultDisplay.getRealMetrics(dm)
                Triple(dm.widthPixels, dm.heightPixels, dm.density)
            }
            val rect = computeCaptureRect(
                fw = fw,
                fh = fh,
                outW = ScreenMirrorService.reqOutWidth,
                outH = ScreenMirrorService.reqOutHeight,
                offsetX = offsetX,
                offsetY = offsetY,
                scalePct = scalePct
            )
            val eff = floatArrayOf(
                rect.left.toFloat() / fw.coerceAtLeast(1),
                rect.top.toFloat() / fh.coerceAtLeast(1),
                rect.right.toFloat() / fw.coerceAtLeast(1),
                rect.bottom.toFloat() / fh.coerceAtLeast(1)
            )
            val v = RegionView(app, eff, offsetX, offsetY, scalePct, density)
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val lp = WindowManager.LayoutParams(
                fw,
                fh,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            wm.addView(v, lp)
            overlayView = v
            mainHandler.removeCallbacks(autoHideTask)
            if (autoHideMs > 0) {
                mainHandler.postDelayed(autoHideTask, autoHideMs)
            }
        } catch (_: Exception) {
            overlayView = null
        }
    }

    private fun hideNow() {
        mainHandler.removeCallbacks(autoHideTask)
        val v = overlayView
        val wm = windowManager
        if (v != null && wm != null) {
            try {
                wm.removeView(v)
            } catch (_: Exception) {
            }
        }
        overlayView = null
    }

    private class RegionView(
        context: Context,
        private val eff: FloatArray,
        private val offsetX: Float,
        private val offsetY: Float,
        private val scalePct: Int,
        private val dp: Float
    ) : View(context) {
        private val dim = Paint().apply { color = 0x66000000 }
        private val edge = Paint().apply { color = 0xFF00E5A3.toInt() }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 11f * dp
            isFakeBoldText = true
            setShadowLayer(3f * dp, 0f, 0f, Color.BLACK)
        }

        override fun onDraw(cv: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val l = eff[0] * w
            val t = eff[1] * h
            val r = eff[2] * w
            val b = eff[3] * h

            val g = 5f * dp
            val s = 3f * dp
            val ol = l - g
            val ot = t - g
            val or = r + g
            val ob = b + g

            cv.drawRect(0f, 0f, w, ot - s, dim)
            cv.drawRect(0f, ob + s, w, h, dim)
            cv.drawRect(0f, ot - s, ol - s, ob + s, dim)
            cv.drawRect(or + s, ot - s, w, ob + s, dim)

            cv.drawRect(ol - s, ot - s, or + s, ot, edge)
            cv.drawRect(ol - s, ob, or + s, ob + s, edge)
            cv.drawRect(ol - s, ot, ol, ob, edge)
            cv.drawRect(or, ot, or + s, ob, edge)

            val label = String.format(
                Locale.US,
                "AREA MIRROR MID  X:%d%% Y:%d%% (%d%%)",
                (offsetX * 100).roundToInt(),
                (offsetY * 100).roundToInt(),
                scalePct
            )
            val x = max(8f * dp, l)
            if (ot - s > 22f * dp) {
                cv.drawText(label, x, ot - s - 6f * dp, txt)
            } else if (h - ob - s > 28f * dp) {
                cv.drawText(label, x, ob + s + 16f * dp, txt)
            }
        }
    }
}
