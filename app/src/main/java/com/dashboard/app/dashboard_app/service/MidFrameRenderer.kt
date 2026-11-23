package com.dashboard.app.dashboard_app.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.Base64
import com.dashboard.app.dashboard_app.model.AppSettings
import com.dashboard.app.dashboard_app.model.EffectiveTelemetry
import com.dashboard.app.dashboard_app.model.GeoPointX
import com.dashboard.app.dashboard_app.model.GpsState
import com.dashboard.app.dashboard_app.model.HillGrade
import com.dashboard.app.dashboard_app.model.NavRouteX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

object MidFrameRenderer {
    const val MAX_MAPS_CHARS = 7000

    @Volatile
    var lastMinChars: Int = 0
        private set

    suspend fun renderFrameBase64(
        settings: AppSettings,
        gps: GpsState,
        eff: EffectiveTelemetry,
        activeRoute: NavRouteX?,
        isNavigating: Boolean,
        activeStepIndex: Int,
        distToNextStepM: Double,
        etaMin: Double,
        maxChars: Int = MAX_MAPS_CHARS
    ): String = withContext(Dispatchers.Default) {
        val w = settings.mapsSnapWidth.coerceIn(64, 1024)
        val h = settings.mapsSnapHeight.coerceIn(64, 1024)

        if (settings.customWallpaperEnabled && settings.customWallpaperPath.isNotEmpty()) {
            val file = File(settings.customWallpaperPath)
            if (file.exists()) {
                val decoded = BitmapFactory.decodeFile(file.absolutePath)
                if (decoded != null) {
                    val resized = Bitmap.createScaledBitmap(decoded, w, h, true)
                    if (resized != decoded) decoded.recycle()
                    val b64 = encodeWithinBudget(resized, maxChars)
                    resized.recycle()
                    return@withContext b64
                }
            }
        }

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#0A131C"))

        val isSplit = settings.mapsLayout == "split"
        val mapW = if (isSplit) (w * 0.54f).roundToInt().toFloat() else w.toFloat()

        drawMiniMapArea(
            canvas = canvas,
            mapW = mapW,
            h = h.toFloat(),
            gps = gps,
            route = activeRoute,
            isNavigating = isNavigating,
            riderLow = settings.mapsRiderPos == "low"
        )

        if (isSplit) {
            drawSplitTelemetryPanel(
                canvas = canvas,
                x0 = mapW,
                w = w.toFloat(),
                h = h.toFloat(),
                gps = gps,
                eff = eff,
                route = activeRoute,
                isNavigating = isNavigating,
                activeStepIndex = activeStepIndex,
                distToNextStepM = distToNextStepM,
                etaMin = etaMin
            )
        } else if (isNavigating && activeRoute != null) {
            val step = activeRoute.steps.getOrNull(activeStepIndex)
            val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#DD0B1620")
            }
            canvas.drawRoundRect(RectF(6f, 6f, w - 6f, 36f), 6f, 6f, pillPaint)
            val distTxt = if (distToNextStepM >= 1000.0) {
                "${String.format(Locale.US, "%.1f", distToNextStepM / 1000.0)}km"
            } else {
                "${distToNextStepM.roundToInt()}m"
            }
            drawText(
                canvas = canvas,
                text = "$distTxt ${step?.instruction ?: "Ikuti rute"}",
                x = 12f,
                y = 11f,
                sizeSp = 12f,
                color = Color.parseColor("#00E5A3"),
                bold = true,
                maxWidth = (w - 24f).coerceAtLeast(20f)
            )
        }

        val encoded = encodeWithinBudget(bmp, maxChars)
        bmp.recycle()
        encoded
    }

    private fun encodeWithinBudget(source: Bitmap, maxChars: Int): String {
        val qualities = intArrayOf(72, 58, 46, 36, 28, 20, 14, 10)
        var current = source
        lastMinChars = 0

        for (attempt in 0 until 4) {
            for (q in qualities) {
                val baos = ByteArrayOutputStream()
                current.compress(Bitmap.CompressFormat.JPEG, q, baos)
                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                if (lastMinChars == 0 || b64.length < lastMinChars) {
                    lastMinChars = b64.length
                }
                if (b64.length <= maxChars) {
                    if (current !== source) current.recycle()
                    return b64
                }
            }
            val nw = (current.width * 0.85f).roundToInt()
            val nh = (current.height * 0.85f).roundToInt()
            if (nw < 64 || nh < 64) break
            val next = Bitmap.createScaledBitmap(current, nw, nh, true)
            if (current !== source) current.recycle()
            current = next
        }
        if (current !== source) current.recycle()
        return ""
    }

    private fun drawMiniMapArea(
        canvas: Canvas,
        mapW: Float,
        h: Float,
        gps: GpsState,
        route: NavRouteX?,
        isNavigating: Boolean,
        riderLow: Boolean
    ) {
        canvas.save()
        canvas.clipRect(0f, 0f, mapW, h)

        val gridPaint = Paint().apply {
            color = Color.parseColor("#152433")
            strokeWidth = 1f
        }
        var gx = 0f
        while (gx < mapW) {
            canvas.drawLine(gx, 0f, gx, h, gridPaint)
            gx += 24f
        }
        var gy = 0f
        while (gy < h) {
            canvas.drawLine(0f, gy, mapW, gy, gridPaint)
            gy += 24f
        }

        val origin = gps.location
            ?: route?.geometry?.firstOrNull()
            ?: GeoPointX(-6.1754, 106.8272)

        val riderX = mapW * 0.5f
        val riderY = if (isNavigating && riderLow) h * 0.70f else h * 0.50f
        val scale = 256.0 * (1 shl 16)

        fun worldX(lng: Double) = ((lng + 180.0) / 360.0) * scale
        fun worldY(lat: Double): Double {
            val rad = lat.coerceIn(-85.0511, 85.0511) * PI / 180.0
            val sinLat = sin(rad)
            return (0.5 - ln((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * PI)) * scale
        }

        val ox = worldX(origin.lng)
        val oy = worldY(origin.lat)
        fun toScreen(gp: GeoPointX): Pair<Float, Float> = Pair(
            (riderX + (worldX(gp.lng) - ox)).toFloat(),
            (riderY + (worldY(gp.lat) - oy)).toFloat()
        )

        if (route != null && route.geometry.size >= 2) {
            val path = Path()
            route.geometry.forEachIndexed { i, gp ->
                val (sx, sy) = toScreen(gp)
                if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
            }
            val bgStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#041418")
                style = Paint.Style.STROKE
                strokeWidth = 8f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val fgStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#00E5A3")
                style = Paint.Style.STROKE
                strokeWidth = 4.5f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawPath(path, bgStroke)
            canvas.drawPath(path, fgStroke)
        }

        // Rider arrow
        canvas.save()
        canvas.translate(riderX, riderY)
        canvas.rotate((gps.headingDeg ?: 0.0).toFloat())
        val arrow = Path().apply {
            moveTo(0f, -10f)
            lineTo(7f, 8f)
            lineTo(0f, 4f)
            lineTo(-7f, 8f)
            close()
        }
        canvas.drawPath(arrow, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00E5A3") })
        canvas.drawPath(arrow, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.6f
        })
        canvas.restore()
        canvas.restore()
    }

    private fun drawSplitTelemetryPanel(
        canvas: Canvas,
        x0: Float,
        w: Float,
        h: Float,
        gps: GpsState,
        eff: EffectiveTelemetry,
        route: NavRouteX?,
        isNavigating: Boolean,
        activeStepIndex: Int,
        distToNextStepM: Double,
        etaMin: Double
    ) {
        canvas.drawRect(x0, 0f, w, h, Paint().apply { color = Color.parseColor("#0B141E") })
        canvas.drawRect(x0, 0f, x0 + 1.5f, h, Paint().apply { color = Color.parseColor("#334B61") })
        val left = x0 + 8f
        val panelW = (w - left - 6f).coerceAtLeast(24f)

        if (isNavigating && route != null) {
            val step = route.steps.getOrNull(activeStepIndex)
            val distTxt = if (distToNextStepM >= 1000.0) {
                "${String.format(Locale.US, "%.1f", distToNextStepM / 1000.0)} km"
            } else {
                "${distToNextStepM.roundToInt()} m"
            }
            drawText(canvas, distTxt, left, 10f, 17f, Color.WHITE, bold = true, maxWidth = panelW)
            drawText(
                canvas,
                step?.instruction ?: "Ikuti rute",
                left,
                34f,
                11f,
                Color.parseColor("#00E5A3"),
                bold = true,
                maxWidth = panelW
            )
            val arriveSoc = route.energyEstimate?.socAtArrivalPct?.roundToInt()?.toString() ?: "--"
            drawText(
                canvas,
                "ETA ${etaMin.roundToInt()}m  •  TIBA $arriveSoc%",
                left,
                58f,
                10f,
                Color.parseColor("#9FB4C8"),
                maxWidth = panelW
            )

            val samples = route.elevationSamples
            if (samples.size >= 3) {
                val top = h - 54f
                canvas.drawRoundRect(
                    RectF(left, top, left + panelW, top + 46f),
                    5f,
                    5f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#132230") }
                )
                val minE = samples.minOf { it.elevationM }
                val maxERaw = samples.maxOf { it.elevationM }
                val maxE = if ((maxERaw - minE) < 5.0) minE + 5.0 else maxERaw
                val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2.2f }
                for (i in 1 until samples.size) {
                    val xA = left + 4f + ((i - 1f) / (samples.size - 1f)) * (panelW - 8f)
                    val xB = left + 4f + (i.toFloat() / (samples.size - 1f)) * (panelW - 8f)
                    val yA = (h - 12f) - (((samples[i - 1].elevationM - minE) / (maxE - minE)) * 32.0).toFloat()
                    val yB = (h - 12f) - (((samples[i].elevationM - minE) / (maxE - minE)) * 32.0).toFloat()
                    linePaint.color = when (samples[i].gradeType) {
                        HillGrade.UPHILL_STEEP -> Color.parseColor("#EF4444")
                        HillGrade.UPHILL_MILD -> Color.parseColor("#F59E0B")
                        HillGrade.DOWNHILL -> Color.parseColor("#38BDF8")
                        HillGrade.FLAT -> Color.parseColor("#00E5A3")
                    }
                    canvas.drawLine(xA, yA, xB, yB, linePaint)
                }
            }
        } else {
            val dirs = arrayOf(
                "UTARA", "TIMUR LAUT", "TIMUR", "TENGGARA",
                "SELATAN", "BARAT DAYA", "BARAT", "BARAT LAUT"
            )
            val hdg = ((gps.headingDeg ?: 0.0) % 360.0 + 360.0) % 360.0
            val card = dirs[(hdg / 45.0).roundToInt() % 8]
            drawText(canvas, card, left, 12f, 16f, Color.WHITE, bold = true, maxWidth = panelW)
            drawText(canvas, "${hdg.roundToInt()}°", left, 34f, 12f, Color.parseColor("#9FB4C8"), maxWidth = panelW)
            drawText(
                canvas,
                "${eff.blendedWhPerKm.roundToInt()} Wh/km",
                left,
                64f,
                13f,
                Color.parseColor("#00E5A3"),
                bold = true,
                maxWidth = panelW
            )
            if (gps.roadName.isNotEmpty()) {
                drawText(canvas, gps.roadName, left, 88f, 11f, Color.argb(180, 255, 255, 255), maxWidth = panelW)
            }
            val alt = gps.location?.altM
            if (alt != null) {
                drawText(
                    canvas,
                    "ELEV ${alt.roundToInt()} m",
                    left,
                    114f,
                    12f,
                    Color.parseColor("#38BDF8"),
                    bold = true,
                    maxWidth = panelW
                )
            }
        }
    }

    private fun drawText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
        maxWidth: Float = 180f
    ) {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.textSize = sizeSp
            this.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val ellipsized = TextUtils.ellipsize(text, tp, maxWidth, TextUtils.TruncateAt.END).toString()
        val fm = tp.fontMetrics
        canvas.drawText(ellipsized, x, y - fm.ascent, tp)
    }
}
