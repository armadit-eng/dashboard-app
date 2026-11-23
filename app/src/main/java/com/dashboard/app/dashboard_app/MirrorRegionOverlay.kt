package com.dashboard.app.dashboard_app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
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
import kotlin.math.roundToInt

/**
 * Draws a non-touchable green border slightly OUTSIDE the cropped capture region
 * so riders can preview the exact area sent to the MID without the border leaking
 * into the captured frames.
 */
object MirrorRegionOverlay {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoHideTask = Runnable { hideNow() }
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    fun canDraw(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun show(
        context: Context,
        crop: FloatArray = floatArrayOf(0f, 0.10f, 1f, 0.78f),
        autoHideMs: Long = 5_000L
    ) {
        val app = context.applicationContext
        mainHandler.post { showNow(app, crop, autoHideMs) }
    }

    fun hide() {
        mainHandler.post { hideNow() }
    }

    @Suppress("DEPRECATION")
    private fun showNow(app: Context, crop: FloatArray, autoHideMs: Long) {
        hideNow()
        if (!canDraw(app) || crop.size != 4) return
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
            val eff = effectiveCrop(crop, fw, fh)
            val v = RegionView(app, eff, density)
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

    private fun effectiveCrop(c: FloatArray, fw: Int, fh: Int): FloatArray {
        val l = (fw * c[0]).roundToInt()
        val t = (fh * c[1]).roundToInt()
        val r = (fw * c[2]).roundToInt()
        val b = (fh * c[3]).roundToInt()
        val rw = max(1, r - l)
        val rh = max(1, b - t)
        val outW = ScreenMirrorService.reqOutWidth.coerceIn(160, 480)
        val outH = ScreenMirrorService.reqOutHeight.coerceIn(120, 360)
        val ratio = outW.toFloat() / outH.toFloat()
        val sw: Int
        val sh: Int
        if (rw.toFloat() / rh.toFloat() > ratio) {
            sh = rh
            sw = (sh * ratio).roundToInt()
        } else {
            sw = rw
            sh = (sw / ratio).roundToInt()
        }
        val sx = l + (rw - sw) / 2
        val sy = t + (rh - sh) / 2
        return floatArrayOf(
            sx.toFloat() / fw,
            sy.toFloat() / fh,
            (sx + sw).toFloat() / fw,
            (sy + sh).toFloat() / fh
        )
    }

    private class RegionView(
        context: Context,
        private val eff: FloatArray,
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
                "AREA MIRROR MID  atas %d%% | bawah %d%%",
                (eff[1] * 100).roundToInt(),
                (eff[3] * 100).roundToInt()
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
