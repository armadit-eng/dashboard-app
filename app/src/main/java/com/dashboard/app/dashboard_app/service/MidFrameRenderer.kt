package com.dashboard.app.dashboard_app.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.Base64
import com.dashboard.app.dashboard_app.engine.RoutePhysics
import com.dashboard.app.dashboard_app.model.AppSettings
import com.dashboard.app.dashboard_app.model.EffectiveTelemetry
import com.dashboard.app.dashboard_app.model.GeoPointX
import com.dashboard.app.dashboard_app.model.GpsState
import com.dashboard.app.dashboard_app.model.HillGrade
import com.dashboard.app.dashboard_app.model.MapStyleOption
import com.dashboard.app.dashboard_app.model.NavRouteX
import com.dashboard.app.dashboard_app.model.RouteStepX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

object MidFrameRenderer {
    const val MAX_MAPS_CHARS = 7000
    private const val TILE_PX = 256.0
    private const val SLIPPY_ZOOM = 17

    @Volatile
    var lastMinChars: Int = 0
        private set

    suspend fun renderFrameBase64(
        context: Context,
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
        val w = settings.mapsSnapWidth.coerceIn(160, 480)
        val h = settings.mapsSnapHeight.coerceIn(120, 360)

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
        val style = MapStyleOption.fromId(settings.mapStyleId)
        val isDark = style == MapStyleOption.DARK_CARTO
        canvas.drawColor(if (isDark) 0xFF111111.toInt() else 0xFFE9E5DC.toInt())

        val sc = w / 350f
        val isSplit = settings.mapsLayout == "split"
        val mapW = if (isSplit) w / 2f else w.toFloat()
        val navOn = isNavigating && activeRoute != null && activeRoute.steps.isNotEmpty()
        val step = if (navOn) activeRoute!!.steps.getOrNull(activeStepIndex) ?: activeRoute.steps.first() else null

        drawHeadingUpMapArea(
            context = context,
            canvas = canvas,
            mapW = mapW,
            h = h.toFloat(),
            sc = sc,
            mapStyle = style,
            gps = gps,
            route = activeRoute,
            navOn = navOn,
            isSplit = isSplit,
            riderLow = settings.mapsRiderPos == "low"
        )

        if (isSplit) {
            drawSplitTelemetryPanel(
                canvas = canvas,
                x0 = mapW,
                w = w.toFloat(),
                h = h.toFloat(),
                sc = sc,
                gps = gps,
                eff = eff,
                route = activeRoute,
                step = step,
                distToNextStepM = distToNextStepM,
                etaMin = etaMin
            )
        } else if (navOn && step != null) {
            drawFullNavBar(
                canvas = canvas,
                w = w.toFloat(),
                h = h.toFloat(),
                sc = sc,
                route = activeRoute!!,
                step = step,
                activeStepIndex = activeStepIndex,
                distToNextStepM = distToNextStepM,
                etaMin = etaMin
            )
        }

        val encoded = encodeWithinBudget(bmp, maxChars)
        bmp.recycle()
        encoded
    }

    private fun encodeWithinBudget(source: Bitmap, maxChars: Int): String {
        val qualities = intArrayOf(78, 66, 54, 44, 34, 26, 18, 12, 8, 6)
        var current = source
        lastMinChars = 0

        for (attempt in 0 until 5) {
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
            if (nw < 48 || nh < 48) break
            val next = Bitmap.createScaledBitmap(current, nw, nh, true)
            if (current !== source) current.recycle()
            current = next
        }
        if (current !== source) current.recycle()
        return ""
    }

    private fun worldX(lng: Double, z: Int): Double =
        ((lng + 180.0) / 360.0) * TILE_PX * (1 shl z)

    private fun worldY(lat: Double, z: Int): Double {
        val rad = lat.coerceIn(-85.0511, 85.0511) * PI / 180.0
        val s = sin(rad)
        return (0.5 - ln((1.0 + s) / (1.0 - s)) / (4.0 * PI)) * TILE_PX * (1 shl z)
    }

    private suspend fun drawHeadingUpMapArea(
        context: Context,
        canvas: Canvas,
        mapW: Float,
        h: Float,
        sc: Float,
        mapStyle: MapStyleOption,
        gps: GpsState,
        route: NavRouteX?,
        navOn: Boolean,
        isSplit: Boolean,
        riderLow: Boolean
    ) {
        val origin = gps.location
            ?: route?.geometry?.firstOrNull()
            ?: GeoPointX(-6.1754, 106.8272)

        val visH = if (navOn && !isSplit) h - 78f * sc else h
        val mx = mapW * 0.5f
        val my = if (navOn) visH * (if (riderLow) 0.74f else 0.50f) else h * 0.50f
        val hdg = if (navOn || (gps.headingDeg ?: 0.0) != 0.0) (gps.headingDeg ?: 0.0).toFloat() else 0f

        val tz = SLIPPY_ZOOM
        val scale = 1.0
        val cx = worldX(origin.lng, tz)
        val cy = worldY(origin.lat, tz)
        val numTiles = 1 shl tz

        // Project 4 canvas corners back to rotated tile space
        val th = Math.toRadians(hdg.toDouble())
        val ct = cos(th)
        val sn = sin(th)
        var minPx = 1e18
        var maxPx = -1e18
        var minPy = 1e18
        var maxPy = -1e18
        val corners = arrayOf(
            0f to 0f,
            mapW to 0f,
            0f to h,
            mapW to h
        )
        for ((kx, ky) in corners) {
            val vx = (kx - mx) / scale
            val vy = (ky - my) / scale
            val px = cx + vx * ct - vy * sn
            val py = cy + vx * sn + vy * ct
            minPx = min(minPx, px)
            maxPx = max(maxPx, px)
            minPy = min(minPy, py)
            maxPy = max(maxPy, py)
        }

        val minTx = floor(minPx / TILE_PX).toInt()
        val maxTx = floor(maxPx / TILE_PX).toInt()
        val minTy = floor(minPy / TILE_PX).toInt()
        val maxTy = floor(maxPy / TILE_PX).toInt()

        val tileCoords = buildList {
            for (ty in minTy..maxTy) {
                if (ty !in 0 until numTiles) continue
                for (tx in minTx..maxTx) {
                    val wx = ((tx % numTiles) + numTiles) % numTiles
                    add(Triple(tx, ty, wx))
                }
            }
        }

        val fetchedMap = coroutineScope {
            tileCoords.map { coord ->
                async {
                    val bmp = withTimeoutOrNull(2200L) {
                        TileCacheService.getTileAndroidBitmap(
                            context = context,
                            styleId = mapStyle.id,
                            urlTemplate = mapStyle.tileUrlTemplate,
                            z = tz,
                            x = coord.third,
                            y = coord.second
                        )
                    }
                    coord to bmp
                }
            }.awaitAll()
        }.toMap()

        canvas.save()
        canvas.clipRect(0f, 0f, mapW, h)

        canvas.save()
        canvas.translate(mx, my)
        canvas.rotate(-hdg)

        val tilePaint = if (mapStyle == MapStyleOption.DARK_CARTO) {
            Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(
                    ColorMatrix(
                        floatArrayOf(
                            1.55f, 0f, 0f, 0f, 14f,
                            0f, 1.55f, 0f, 0f, 14f,
                            0f, 0f, 1.55f, 0f, 16f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                )
            }
        } else {
            Paint(Paint.FILTER_BITMAP_FLAG)
        }

        for (coord in tileCoords) {
            val dx = (coord.first * TILE_PX - cx).toFloat()
            val dy = (coord.second * TILE_PX - cy).toFloat()
            val exact = fetchedMap[coord]
            if (exact != null && !exact.isRecycled) {
                canvas.drawBitmap(
                    exact,
                    null,
                    RectF(dx, dy, dx + TILE_PX.toFloat() + 0.6f, dy + TILE_PX.toFloat() + 0.6f),
                    tilePaint
                )
                continue
            }
            // Parent-tile upscaled fallback (z-1, z-2) so no blank hole renders on MID
            for (up in 1..2) {
                val pz = tz - up
                val px = coord.third shr up
                val py = coord.second shr up
                val parent = TileCacheService.getCachedMemBitmap(mapStyle.id, pz, px, py)
                if (parent != null) {
                    val sub = TILE_PX.toInt() shr up
                    val sx = (coord.third - (px shl up)) * sub
                    val sy = (coord.second - (py shl up)) * sub
                    canvas.drawBitmap(
                        parent,
                        Rect(sx, sy, sx + sub, sy + sub),
                        RectF(dx, dy, dx + TILE_PX.toFloat() + 0.6f, dy + TILE_PX.toFloat() + 0.6f),
                        tilePaint
                    )
                    break
                }
            }
        }

        if (route != null && route.geometry.size >= 2) {
            drawProjectedRoute(canvas, route, origin, tz, cx, cy, sc)
        }

        canvas.restore()

        // Accuracy halo + Google-Maps-style rider marker (always upright in heading-up)
        val mpp = 156543.03392 * cos(Math.toRadians(origin.lat)) / (1 shl tz)
        val accPx = gps.accuracyM?.let { (it / mpp).toFloat() } ?: 0f
        drawRiderMarker(canvas, mx, my, sc, navOn, accPx)

        canvas.restore()
    }

    private fun drawProjectedRoute(
        canvas: Canvas,
        route: NavRouteX,
        origin: GeoPointX,
        tz: Int,
        cx: Double,
        cy: Double,
        sc: Float
    ) {
        val pts = route.geometry
        val cosLat = cos(Math.toRadians(origin.lat))
        var bestSg = 0
        var bestT = 0.0
        var bestOff = Double.MAX_VALUE

        for (sg in 0 until pts.size - 1) {
            val bx = (pts[sg + 1].lng - pts[sg].lng) * 111320.0 * cosLat
            val by = (pts[sg + 1].lat - pts[sg].lat) * 110540.0
            val px = (origin.lng - pts[sg].lng) * 111320.0 * cosLat
            val py = (origin.lat - pts[sg].lat) * 110540.0
            val len2 = bx * bx + by * by
            val t = if (len2 > 0) ((px * bx + py * by) / len2).coerceIn(0.0, 1.0) else 0.0
            val off = hypot(px - t * bx, py - t * by)
            if (off < bestOff) {
                bestOff = off
                bestSg = sg
                bestT = t
            }
        }

        val startLat = pts[bestSg].lat + (pts[bestSg + 1].lat - pts[bestSg].lat) * bestT
        val startLng = pts[bestSg].lng + (pts[bestSg + 1].lng - pts[bestSg].lng) * bestT

        val path = Path()
        path.moveTo((worldX(startLng, tz) - cx).toFloat(), (worldY(startLat, tz) - cy).toFloat())
        for (i in (bestSg + 1) until pts.size) {
            path.lineTo((worldX(pts[i].lng, tz) - cx).toFloat(), (worldY(pts[i].lat, tz) - cy).toFloat())
        }

        val casing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF06253A.toInt()
            style = Paint.Style.STROKE
            strokeWidth = max(6f, 8.5f * sc)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val mainLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2979FF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = max(4f, 5.5f * sc)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(path, casing)
        canvas.drawPath(path, mainLine)

        // Destination pin
        val dest = pts.last()
        val dx = (worldX(dest.lng, tz) - cx).toFloat()
        val dy = (worldY(dest.lat, tz) - cy).toFloat()
        canvas.drawCircle(dx, dy, 6.5f * sc, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEF4444.toInt() })
        canvas.drawCircle(dx, dy, 6.5f * sc, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * sc
        })
    }

    private fun drawRiderMarker(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        sc: Float,
        navOn: Boolean,
        accPx: Float
    ) {
        val r = max(7f, 10f * sc)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (accPx > r * 1.4f) {
            val rr = min(accPx, 46f * sc)
            p.style = Paint.Style.FILL
            p.color = 0x1E2979FF
            canvas.drawCircle(cx, cy, rr, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = max(1f, sc)
            p.color = 0x802979FF.toInt()
            canvas.drawCircle(cx, cy, rr, p)
        }
        if (navOn) {
            val ah = r * 1.4f
            val aw = r * 0.9f
            canvas.save()
            canvas.translate(cx, cy)
            p.color = 0x402979FF
            p.style = Paint.Style.FILL
            canvas.drawCircle(0f, 0f, r * 0.45f, p)
            val arrow = Path().apply {
                moveTo(0f, -ah)
                lineTo(aw, ah * 0.5f)
                lineTo(0f, ah * 0.2f)
                lineTo(-aw, ah * 0.5f)
                close()
            }
            p.color = 0xFF2979FF.toInt()
            p.style = Paint.Style.FILL
            canvas.drawPath(arrow, p)
            p.color = Color.WHITE
            p.style = Paint.Style.STROKE
            p.strokeWidth = max(1.2f, r * 0.16f)
            p.strokeJoin = Paint.Join.ROUND
            canvas.drawPath(arrow, p)
            canvas.restore()
        } else {
            p.style = Paint.Style.FILL
            p.color = 0x382196F3
            canvas.drawCircle(cx, cy, r * 1.1f, p)
            p.color = Color.WHITE
            canvas.drawCircle(cx, cy, r * 0.82f, p)
            p.color = 0xFF1E88E5.toInt()
            canvas.drawCircle(cx, cy, r * 0.55f, p)
        }
    }

    private fun drawFullNavBar(
        canvas: Canvas,
        w: Float,
        h: Float,
        sc: Float,
        route: NavRouteX,
        step: RouteStepX,
        activeStepIndex: Int,
        distToNextStepM: Double,
        etaMin: Double
    ) {
        val barH = 78f * sc
        val barY = h - barH
        val arrowW = 62f * sc

        val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        f.color = 0xFF071827.toInt()
        canvas.drawRect(0f, barY, w, h, f)
        f.color = 0xFF102F43.toInt()
        canvas.drawRect(0f, barY, arrowW, h, f)
        f.color = Color.WHITE
        canvas.drawRect(0f, barY, w, barY + max(2f, 2f * sc), f)
        f.color = 0xFF456477.toInt()
        canvas.drawRect(arrowW, barY + 7f * sc, arrowW + max(1f, sc), barY + barH - 7f * sc, f)

        drawManeuverArrow(canvas, arrowW / 2f, barY + 27f * sc, 19f * sc / 22f, step, sc)

        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = max(0.6f, 0.75f * sc)
            color = Color.WHITE
        }

        val distStr = if (distToNextStepM >= 1000.0) {
            String.format(Locale.US, "%.1fkm", distToNextStepM / 1000.0)
        } else {
            "${distToNextStepM.roundToInt()}m"
        }
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = max(13f, (14f * sc).roundToInt().toFloat())
        canvas.drawText(distStr, arrowW / 2f, midBase(tp, barY + 59f * sc), tp)

        tp.textAlign = Paint.Align.LEFT
        val tx = arrowW + 9f * sc
        val tw = (w - tx - 7f * sc).coerceAtLeast(20f)
        val act = maneuverAction(step).replaceFirstChar { it.uppercase(Locale.US) }
        fitText(tp, act, tw, 18f * sc, 13f * sc)
        canvas.drawText(act, tx, midBase(tp, barY + 15f * sc), tp)

        val road = route.steps.getOrNull(activeStepIndex + 1)?.roadName?.ifEmpty { step.roadName } ?: step.roadName
        if (road.isNotEmpty()) {
            tp.textSize = max(11f, (13f * sc).roundToInt().toFloat())
            val rt = TextUtils.ellipsize("KE $road", tp, tw, TextUtils.TruncateAt.END).toString()
            canvas.drawText(rt, tx, midBase(tp, barY + 36f * sc), tp)
        }

        val remM = route.distanceM
        val remTxt = if (remM >= 1000.0) String.format(Locale.US, "%.1f km", remM / 1000.0) else "${remM.roundToInt()} m"
        val etaTxt = if (etaMin < 1.0) "<1 mnt" else "${etaMin.roundToInt()} mnt"
        val cal = Calendar.getInstance().apply { add(Calendar.SECOND, (etaMin * 60.0).roundToInt()) }
        val arr = String.format(Locale.US, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        val meta = "SISA $remTxt   ETA $etaTxt   TIBA $arr"
        fitText(tp, meta, tw, 12f * sc, 8.5f * sc)
        val metaY = if (road.isEmpty()) 43f * sc else 62f * sc
        canvas.drawText(meta, tx, midBase(tp, barY + metaY), tp)
    }

    private fun drawSplitTelemetryPanel(
        canvas: Canvas,
        x0: Float,
        w: Float,
        h: Float,
        sc: Float,
        gps: GpsState,
        eff: EffectiveTelemetry,
        route: NavRouteX?,
        step: RouteStepX?,
        distToNextStepM: Double,
        etaMin: Double
    ) {
        val pw = w - x0
        val left = x0 + 8f * sc
        val tw = (pw - 14f * sc).coerceAtLeast(24f)

        val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        f.color = 0xFF0B1620.toInt()
        canvas.drawRect(x0, 0f, w, h, f)
        f.color = 0xFF456477.toInt()
        canvas.drawRect(x0, 0f, x0 + max(2f, 2f * sc), h, f)

        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = max(0.6f, 0.75f * sc)
            textAlign = Paint.Align.LEFT
        }

        if (step != null && route != null) {
            drawManeuverArrow(canvas, x0 + 30f * sc, 31f * sc, 19f * sc / 22f, step, sc)

            val distStr = if (distToNextStepM >= 1000.0) {
                String.format(Locale.US, "%.1fkm", distToNextStepM / 1000.0)
            } else {
                "${distToNextStepM.roundToInt()}m"
            }
            tp.color = Color.WHITE
            fitText(tp, distStr, (pw - 66f * sc).coerceAtLeast(24f), 28f * sc, 16f * sc)
            canvas.drawText(distStr, x0 + 58f * sc, midBase(tp, 31f * sc), tp)

            val act = maneuverAction(step).replaceFirstChar { it.uppercase(Locale.US) }
            fitText(tp, act, tw, 17f * sc, 11f * sc)
            canvas.drawText(act, left, midBase(tp, 66f * sc), tp)

            val road = step.roadName.ifEmpty { step.instruction }
            if (road.isNotEmpty()) {
                tp.color = 0xFFE3ECF2.toInt()
                tp.textSize = max(11f, 13f * sc)
                val rt = TextUtils.ellipsize(road, tp, tw, TextUtils.TruncateAt.END).toString()
                canvas.drawText(rt, left, midBase(tp, 87f * sc), tp)
            }

            val etaStr = if (etaMin < 1.0) "<1 mnt" else "${etaMin.roundToInt()} mnt"
            val cal = Calendar.getInstance().apply { add(Calendar.SECOND, (etaMin * 60.0).roundToInt()) }
            val arr = String.format(Locale.US, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            tp.color = 0xFF8BFF98.toInt()
            fitText(tp, "ETA $etaStr  $arr", tw, 15f * sc, 10.5f * sc)
            canvas.drawText("ETA $etaStr  $arr", left, midBase(tp, 108f * sc), tp)

            val arrSoc = route.energyEstimate?.socAtArrivalPct
            if (arrSoc != null) {
                val socTxt = "TIBA SOC ${arrSoc.roundToInt().coerceAtLeast(0)}%"
                tp.color = when {
                    arrSoc < 5.0 -> 0xFFFF5252.toInt()
                    arrSoc < 15.0 -> 0xFFFFB300.toInt()
                    else -> Color.WHITE
                }
                fitText(tp, socTxt, tw, 15f * sc, 10f * sc)
                canvas.drawText(socTxt, left, midBase(tp, 128f * sc), tp)
            }

            // Bottom elevation profile card
            val cx1 = x0 + 6f * sc
            val cx2 = w - 6f * sc
            val cy1 = 144f * sc
            val cy2 = h - 6f * sc
            if (cy2 - cy1 > 34f * sc) {
                f.color = 0xFF10202B.toInt()
                canvas.drawRoundRect(RectF(cx1, cy1, cx2, cy2), 5f * sc, 5f * sc, f)
                val samples = route.elevationSamples
                if (samples.size >= 3) {
                    val maxClimb = route.energyEstimate?.maxClimbPct ?: 0.0
                    val label = if (maxClimb >= 5.0) "Tanjakan ${maxClimb.roundToInt()}%" else "Profil Elevasi"
                    tp.color = if (maxClimb >= 5.0) 0xFFFFB300.toInt() else 0xFFD6E2EA.toInt()
                    fitText(tp, label, cx2 - cx1 - 10f * sc, 12f * sc, 9f * sc)
                    canvas.drawText(label, cx1 + 6f * sc, midBase(tp, cy1 + 12f * sc), tp)

                    val minE = samples.minOf { it.elevationM }
                    val maxERaw = samples.maxOf { it.elevationM }
                    val range = max(20.0, maxERaw - minE)
                    val base = minE - (range - (maxERaw - minE)) / 2.0
                    val x1 = cx1 + 8f * sc
                    val x2 = cx2 - 8f * sc
                    val y1 = cy1 + 22f * sc
                    val y2 = cy2 - 6f * sc
                    val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = max(2f, 2.5f * sc)
                        strokeCap = Paint.Cap.ROUND
                    }
                    for (i in 1 until samples.size) {
                        val xa = x1 + (x2 - x1) * (i - 1f) / (samples.size - 1f)
                        val xb = x1 + (x2 - x1) * i.toFloat() / (samples.size - 1f)
                        val ya = (y2 - ((samples[i - 1].elevationM - base) / range) * (y2 - y1)).toFloat()
                        val yb = (y2 - ((samples[i].elevationM - base) / range) * (y2 - y1)).toFloat()
                        lineP.color = when (samples[i].gradeType) {
                            HillGrade.UPHILL_STEEP -> 0xFFFF3D00.toInt()
                            HillGrade.UPHILL_MILD -> 0xFFFFB300.toInt()
                            HillGrade.DOWNHILL -> 0xFFA24BF0.toInt()
                            HillGrade.FLAT -> 0xFF00E676.toInt()
                        }
                        canvas.drawLine(xa, ya, xb, yb, lineP)
                    }
                }
            }
        } else {
            // Free ride split panel (omits SOC/speed/clock because MID renders them outside AT+MAPS)
            val dirs = arrayOf(
                "UTARA", "TIMUR LAUT", "TIMUR", "TENGGARA",
                "SELATAN", "BARAT DAYA", "BARAT", "BARAT LAUT"
            )
            val hdg = ((gps.headingDeg ?: 0.0) % 360.0 + 360.0) % 360.0
            val card = dirs[((hdg + 22.5) / 45.0).toInt() % 8]

            tp.color = Color.WHITE
            fitText(tp, card, tw, 26f * sc, 15f * sc)
            canvas.drawText(card, left, midBase(tp, 24f * sc), tp)

            tp.color = 0xFFB8C7D1.toInt()
            tp.textSize = max(12f, 14f * sc)
            canvas.drawText(String.format(Locale.US, "%03d°", hdg.roundToInt()), left, midBase(tp, 48f * sc), tp)

            tp.textSize = max(11f, 12f * sc)
            canvas.drawText("ELEVASI", left, midBase(tp, 70f * sc), tp)
            tp.color = Color.WHITE
            val alt = gps.location?.altM?.let { "${it.roundToInt()} m" } ?: "--"
            fitText(tp, alt, tw, 22f * sc, 13f * sc)
            canvas.drawText(alt, left, midBase(tp, 90f * sc), tp)

            val road = gps.roadName.ifEmpty { gps.areaName }
            if (road.isNotEmpty()) {
                tp.color = 0xFFE3ECF2.toInt()
                tp.textSize = max(11f, 13f * sc)
                val rt = TextUtils.ellipsize(road, tp, tw, TextUtils.TruncateAt.END).toString()
                canvas.drawText(rt, left, midBase(tp, 114f * sc), tp)
            }

            tp.color = 0xFFB8C7D1.toInt()
            tp.textSize = max(11f, 12f * sc)
            canvas.drawText("EFISIENSI", left, midBase(tp, 142f * sc), tp)
            tp.color = 0xFF8BFF98.toInt()
            val efTxt = "${eff.blendedWhPerKm.roundToInt()} Wh/km"
            fitText(tp, efTxt, tw, 20f * sc, 13f * sc)
            canvas.drawText(efTxt, left, midBase(tp, 164f * sc), tp)
        }
    }

    private fun drawManeuverArrow(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        s: Float,
        step: RouteStepX,
        sc: Float
    ) {
        val m = (step.modifier ?: "").lowercase(Locale.US)
        val t = step.maneuverType.lowercase(Locale.US)
        val pts = when {
            t == "arrive" -> floatArrayOf(22f, 3f, 36f, 20f, 29f, 20f, 29f, 41f, 15f, 41f, 15f, 20f, 8f, 20f)
            m.contains("uturn") -> floatArrayOf(22f, 39f, 10f, 22f, 16f, 22f, 16f, 9f, 28f, 9f, 28f, 22f, 34f, 22f)
            m.contains("sharp left") -> floatArrayOf(5f, 39f, 5f, 5f, 23f, 24f, 14f, 24f)
            m.contains("sharp right") -> floatArrayOf(39f, 39f, 39f, 5f, 21f, 24f, 30f, 24f)
            m.contains("slight left") -> floatArrayOf(7f, 7f, 37f, 19f, 26f, 22f, 22f, 37f)
            m.contains("slight right") -> floatArrayOf(37f, 7f, 7f, 19f, 18f, 22f, 22f, 37f)
            m.contains("left") -> floatArrayOf(3f, 22f, 36f, 8f, 27f, 22f, 36f, 36f)
            m.contains("right") -> floatArrayOf(41f, 22f, 8f, 8f, 17f, 22f, 8f, 36f)
            else -> floatArrayOf(22f, 3f, 36f, 36f, 22f, 27f, 8f, 36f)
        }
        val ap = Path()
        var i = 0
        while (i < pts.size) {
            val x = cx + (pts[i] - 22f) * s
            val y = cy + (pts[i + 1] - 22f) * s
            if (i == 0) ap.moveTo(x, y) else ap.lineTo(x, y)
            i += 2
        }
        ap.close()
        val f = Paint(Paint.ANTI_ALIAS_FLAG)
        f.style = Paint.Style.FILL
        f.color = if (t == "arrive") 0xFFFFD740.toInt() else 0xFF00F078.toInt()
        canvas.drawPath(ap, f)
        f.style = Paint.Style.STROKE
        f.color = Color.WHITE
        f.strokeWidth = max(1.2f, 1.6f * sc)
        f.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(ap, f)
    }

    private fun maneuverAction(step: RouteStepX): String {
        val t = step.maneuverType.lowercase(Locale.US)
        val m = (step.modifier ?: "").lowercase(Locale.US)
        return when {
            t == "arrive" -> "tiba di tujuan"
            m.contains("uturn") -> "putar balik"
            m.contains("sharp left") -> "belok tajam kiri"
            m.contains("sharp right") -> "belok tajam kanan"
            m.contains("slight left") -> "agak kiri"
            m.contains("slight right") -> "agak kanan"
            m.contains("left") -> "belok kiri"
            m.contains("right") -> "belok kanan"
            t == "roundabout" || t == "rotary" -> "masuk bundaran"
            else -> RoutePhysics.translateOsrmManeuver(t, m, "").ifBlank { "lurus terus" }
        }
    }

    private fun midBase(p: Paint, y: Float): Float = y - (p.ascent() + p.descent()) / 2f

    private fun fitText(tp: TextPaint, text: String, maxW: Float, startSize: Float, minSize: Float) {
        var sz = startSize
        tp.textSize = sz
        while (sz > minSize && tp.measureText(text) > maxW) {
            sz -= 1f
            tp.textSize = sz
        }
    }
}
