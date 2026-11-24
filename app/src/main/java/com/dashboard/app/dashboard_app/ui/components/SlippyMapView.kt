package com.dashboard.app.dashboard_app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.ElectricMoped
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dashboard.app.dashboard_app.model.FieldNotePin
import com.dashboard.app.dashboard_app.model.GeoPointX
import com.dashboard.app.dashboard_app.model.MapStyleOption
import com.dashboard.app.dashboard_app.model.PlannedStop
import com.dashboard.app.dashboard_app.model.SpkluStation
import com.dashboard.app.dashboard_app.service.TileCacheService
import com.dashboard.app.dashboard_app.ui.theme.LocalCockpitColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sinh

private fun lngToWorldX(lng: Double): Double = (lng + 180.0) / 360.0

private fun latToWorldY(lat: Double): Double {
    val rad = lat.coerceIn(-85.0511, 85.0511) * PI / 180.0
    return (1.0 - ln(kotlin.math.tan(rad) + 1.0 / cos(rad)) / PI) / 2.0
}

private fun worldXToLng(wx: Double): Double = (wx * 360.0 - 180.0).coerceIn(-180.0, 180.0)

private fun worldYToLat(wy: Double): Double {
    val clamped = wy.coerceIn(0.0, 1.0)
    val n = PI - 2.0 * PI * clamped
    return ((180.0 / PI) * atan(sinh(n))).coerceIn(-85.0, 85.0)
}

@Composable
fun SlippyMapView(
    center: GeoPointX,
    zoom: Double,
    style: MapStyleOption,
    cartoApiKey: String = "",
    dataSaverMap: Boolean,
    riderLocation: GeoPointX?,
    riderHeadingDeg: Double,
    customMarkerPath: String,
    routeGeometry: List<GeoPointX>,
    plannedStops: List<PlannedStop>,
    nearbySpklu: List<SpkluStation>,
    fieldNotes: List<FieldNotePin>,
    selectedPin: GeoPointX? = null,
    highlightCursorPoint: GeoPointX? = null,
    highlightedHillPoints: List<GeoPointX> = emptyList(),
    onMapPanZoom: (newCenter: GeoPointX, newZoom: Double) -> Unit,
    onMapTap: ((GeoPointX) -> Unit)? = null,
    onMapLongPress: (GeoPointX) -> Unit,
    onSpkluClick: (SpkluStation) -> Unit,
    onFieldNoteClick: ((FieldNotePin) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val c = LocalCockpitColors.current
    val scope = rememberCoroutineScope()

    val maxZ = if (dataSaverMap) 16.0 else 18.0
    val extZoomClamped = zoom.coerceIn(5.0, maxZ)

    // Internal high-frequency camera state so pan/pinch/fling stay 60fps without restarting pointerInput
    var viewCenter by remember { mutableStateOf(center) }
    var viewZoom by remember { mutableDoubleStateOf(extZoomClamped) }
    var lastEmittedCenter by remember { mutableStateOf(center) }
    var lastEmittedZoom by remember { mutableDoubleStateOf(extZoomClamped) }
    var isUserInteracting by remember { mutableStateOf(false) }
    var flingJob by remember { mutableStateOf<Job?>(null) }
    var zoomAnimJob by remember { mutableStateOf<Job?>(null) }

    // Sync external camera updates (GPS follow, Fit Route, +/- zoom buttons) without breaking gestures
    LaunchedEffect(center, extZoomClamped) {
        if (isUserInteracting || flingJob?.isActive == true) return@LaunchedEffect
        val centerMovedExternally = abs(center.lat - lastEmittedCenter.lat) > 1e-6 ||
            abs(center.lng - lastEmittedCenter.lng) > 1e-6
        val zoomMovedExternally = abs(extZoomClamped - lastEmittedZoom) > 1e-4

        if (centerMovedExternally && abs(center.lat - viewCenter.lat) + abs(center.lng - viewCenter.lng) > 1e-6) {
            viewCenter = center
            lastEmittedCenter = center
        }
        if (zoomMovedExternally && abs(extZoomClamped - viewZoom) > 1e-4) {
            val diff = extZoomClamped - viewZoom
            if (abs(diff) >= 0.4 && zoomAnimJob?.isActive != true) {
                val startZ = viewZoom
                val targetZ = extZoomClamped
                zoomAnimJob = scope.launch {
                    val anim = Animatable(startZ.toFloat())
                    anim.animateTo(targetZ.toFloat(), tween(durationMillis = 190)) {
                        viewZoom = value.toDouble().coerceIn(5.0, maxZ)
                    }
                    lastEmittedZoom = targetZ
                }
            } else {
                viewZoom = extZoomClamped
                lastEmittedZoom = extZoomClamped
            }
        }
    }

    val clampedZoom = viewZoom.coerceIn(5.0, maxZ)
    val tileIntZ = clampedZoom.roundToInt().coerceIn(5, maxZ.toInt())
    val scaleFactor = 2.0.pow(clampedZoom - tileIntZ)
    // Scale continuously across fractional zoom levels without clamping out scaleFactor!
    val baseTilePx = (256.0 * density.density * 0.72).coerceIn(220.0, 560.0)
    val tilePx = baseTilePx * scaleFactor

    val tileBitmaps = remember(style.id, cartoApiKey) { mutableStateMapOf<String, ImageBitmap>() }
    val inFlightKeys = remember(style.id, cartoApiKey) { mutableSetOf<String>() }
    var tileTrigger by remember { mutableIntStateOf(0) }

    val customMarkerBitmap = remember(customMarkerPath) {
        if (customMarkerPath.isNotEmpty() && File(customMarkerPath).exists()) {
            BitmapFactory.decodeFile(customMarkerPath)?.asImageBitmap()
        } else null
    }

    val currentMaxZ by rememberUpdatedState(maxZ)
    val currentOnMapPanZoom by rememberUpdatedState(onMapPanZoom)
    val currentOnMapTap by rememberUpdatedState(onMapTap)
    val currentOnMapLongPress by rememberUpdatedState(onMapLongPress)

    BoxWithConstraints(modifier = modifier.background(Color(0xFF0A111A))) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }

        val numTiles = 1 shl tileIntZ
        val centerWx = lngToWorldX(viewCenter.lng)
        val centerWy = latToWorldY(viewCenter.lat)
        val centerTileX = centerWx * numTiles
        val centerTileY = centerWy * numTiles
        val halfScreenTilesX = (widthPx / 2f) / tilePx
        val halfScreenTilesY = (heightPx / 2f) / tilePx

        val minX = floor(centerTileX - halfScreenTilesX).toInt()
        val maxX = ceil(centerTileX + halfScreenTilesX).toInt()
        val minY = floor(centerTileY - halfScreenTilesY).toInt()
        val maxY = ceil(centerTileY + halfScreenTilesY).toInt()

        LaunchedEffect(style.id, cartoApiKey, tileIntZ, minX, maxX, minY, maxY, TileCacheService.offlineOnly) {
            if (isUserInteracting) delay(24L)
            val needed = mutableListOf<Triple<Int, Int, Double>>()
            for (ty in minY..maxY) {
                if (ty < 0 || ty >= numTiles) continue
                for (tx in minX..maxX) {
                    val wrappedX = ((tx % numTiles) + numTiles) % numTiles
                    val key = "${style.id}/$tileIntZ/$wrappedX/$ty"
                    if (!tileBitmaps.containsKey(key)) {
                        val cached = TileCacheService.getCachedImageBitmap(style.id, tileIntZ, wrappedX, ty)
                        if (cached != null) {
                            tileBitmaps[key] = cached
                        } else if (inFlightKeys.add(key)) {
                            val dist = hypot(tx - centerTileX, ty - centerTileY)
                            needed.add(Triple(wrappedX, ty, dist))
                        }
                    }
                }
            }
            if (tileBitmaps.size > 180) {
                val keepPrefix = "${style.id}/$tileIntZ/"
                val toDrop = tileBitmaps.keys.filter { !it.startsWith(keepPrefix) }.take(tileBitmaps.size - 140)
                toDrop.forEach { tileBitmaps.remove(it) }
            }
            if (needed.isNotEmpty()) {
                needed.sortBy { it.third }
                for ((wrappedX, ty, _) in needed) {
                    val key = "${style.id}/$tileIntZ/$wrappedX/$ty"
                    scope.launch {
                        try {
                            val bmp = TileCacheService.getTileBitmap(
                                context = context,
                                styleId = style.id,
                                urlTemplate = style.tileUrlTemplate,
                                z = tileIntZ,
                                x = wrappedX,
                                y = ty,
                                cartoApiKey = cartoApiKey
                            )
                            if (bmp != null) {
                                tileBitmaps[key] = bmp
                                tileTrigger++
                            }
                        } finally {
                            inFlightKeys.remove(key)
                        }
                    }
                }
            }
        }

        fun geoToScreen(gp: GeoPointX): Offset {
            val worldPx = baseTilePx * 2.0.pow(clampedZoom)
            val gx = lngToWorldX(gp.lng)
            val gy = latToWorldY(gp.lat)
            return Offset(
                x = (widthPx / 2f + (gx - centerWx) * worldPx).toFloat(),
                y = (heightPx / 2f + (gy - centerWy) * worldPx).toFloat()
            )
        }

        fun screenToGeo(offset: Offset): GeoPointX {
            val liveCenter = viewCenter
            val liveZoom = viewZoom.coerceIn(5.0, currentMaxZ)
            val liveCenterWx = lngToWorldX(liveCenter.lng)
            val liveCenterWy = latToWorldY(liveCenter.lat)
            val worldPx = baseTilePx * 2.0.pow(liveZoom)
            val wx = liveCenterWx + (offset.x - widthPx / 2f) / worldPx
            val wy = liveCenterWy + (offset.y - heightPx / 2f) / worldPx
            return GeoPointX(
                lat = worldYToLat(wy),
                lng = worldXToLng(wx)
            )
        }

        fun applyPanZoom(pan: Offset, zoomChange: Float, centroid: Offset) {
            val zOld = viewZoom
            val zNew = if (zoomChange != 1f && zoomChange > 0f) {
                (zOld + ln(zoomChange.toDouble()) / ln(2.0)).coerceIn(5.0, currentMaxZ)
            } else zOld

            val worldOld = baseTilePx * 2.0.pow(zOld)
            val worldNew = baseTilePx * 2.0.pow(zNew)

            val prevCentroid = centroid - pan
            val focalWx = lngToWorldX(viewCenter.lng) + (prevCentroid.x - widthPx / 2f) / worldOld
            val focalWy = latToWorldY(viewCenter.lat) + (prevCentroid.y - heightPx / 2f) / worldOld

            val nextCenterWx = (focalWx - (centroid.x - widthPx / 2f) / worldNew).coerceIn(0.0, 1.0)
            val nextCenterWy = (focalWy - (centroid.y - heightPx / 2f) / worldNew).coerceIn(0.02, 0.98)

            val nextCenter = GeoPointX(
                lat = worldYToLat(nextCenterWy),
                lng = worldXToLng(nextCenterWx)
            )
            viewCenter = nextCenter
            viewZoom = zNew
            lastEmittedCenter = nextCenter
            lastEmittedZoom = zNew
            currentOnMapPanZoom(nextCenter, zNew)
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // Stable keys prevent Compose from tearing down active pan/pinch mid-gesture
                .pointerInput(widthPx, heightPx, baseTilePx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        flingJob?.cancel()
                        zoomAnimJob?.cancel()

                        val velocityTracker = VelocityTracker()
                        var panAccum = Offset.Zero
                        var zoomAccum = 1f
                        var pastTouchSlop = false
                        val touchSlop = viewConfiguration.touchSlop
                        var hadMultiTouch = false

                        velocityTracker.addPosition(down.uptimeMillis, down.position)

                        do {
                            val event = awaitPointerEvent()
                            val canceled = event.changes.any { it.isConsumed }
                            if (canceled) break

                            val pressedCount = event.changes.count { it.pressed }
                            if (pressedCount > 1) {
                                hadMultiTouch = true
                                velocityTracker.resetTracking()
                            } else if (pressedCount == 1 && !hadMultiTouch) {
                                event.changes.firstOrNull { it.pressed }?.let { ch ->
                                    velocityTracker.addPosition(ch.uptimeMillis, ch.position)
                                }
                            }

                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()

                            if (!pastTouchSlop) {
                                zoomAccum *= zoomChange
                                panAccum += panChange
                                val zoomMotion = abs(1f - zoomAccum) * 180f
                                val panMotion = panAccum.getDistance()
                                if (pressedCount > 1 || zoomMotion > touchSlop || panMotion > touchSlop) {
                                    pastTouchSlop = true
                                    isUserInteracting = true
                                }
                            }

                            if (pastTouchSlop) {
                                val currCentroid = event.calculateCentroid(useCurrent = true)
                                val safeCentroid = if (currCentroid != Offset.Unspecified) {
                                    currCentroid
                                } else {
                                    Offset(widthPx / 2f, heightPx / 2f)
                                }
                                if (zoomChange != 1f || panChange != Offset.Zero) {
                                    applyPanZoom(
                                        pan = panChange,
                                        zoomChange = zoomChange,
                                        centroid = safeCentroid
                                    )
                                }
                                event.changes.forEach { ch ->
                                    if (ch.positionChanged()) ch.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        if (pastTouchSlop && !hadMultiTouch) {
                            val vel = velocityTracker.calculateVelocity()
                            val vx = vel.x.coerceIn(-4800f, 4800f)
                            val vy = vel.y.coerceIn(-4800f, 4800f)
                            val speed = hypot(vx, vy)
                            if (speed > 140f) {
                                val dirX = vx / speed
                                val dirY = vy / speed
                                flingJob = scope.launch {
                                    try {
                                        var lastVal = 0f
                                        AnimationState(
                                            initialValue = 0f,
                                            initialVelocity = speed
                                        ).animateDecay(exponentialDecay(frictionMultiplier = 2.4f)) {
                                            val delta = value - lastVal
                                            lastVal = value
                                            if (abs(delta) > 0.05f) {
                                                applyPanZoom(
                                                    pan = Offset(dirX * delta, dirY * delta),
                                                    zoomChange = 1f,
                                                    centroid = Offset(widthPx / 2f, heightPx / 2f)
                                                )
                                            }
                                        }
                                    } finally {
                                        isUserInteracting = false
                                    }
                                }
                            } else {
                                isUserInteracting = false
                            }
                        } else {
                            isUserInteracting = false
                        }
                    }
                }
                .pointerInput(widthPx, heightPx, baseTilePx) {
                    detectTapGestures(
                        onTap = { offset ->
                            currentOnMapTap?.invoke(screenToGeo(offset))
                        },
                        onDoubleTap = { offset ->
                            flingJob?.cancel()
                            zoomAnimJob?.cancel()
                            val startZ = viewZoom
                            val targetZ = (startZ + 1.0).coerceAtMost(currentMaxZ)
                            if (targetZ > startZ + 0.05) {
                                isUserInteracting = true
                                zoomAnimJob = scope.launch {
                                    try {
                                        var prevZ = startZ
                                        val anim = Animatable(startZ.toFloat())
                                        anim.animateTo(targetZ.toFloat(), tween(durationMillis = 220)) {
                                            val nextZ = value.toDouble()
                                            val factor = 2.0.pow(nextZ - prevZ).toFloat()
                                            prevZ = nextZ
                                            applyPanZoom(Offset.Zero, factor, offset)
                                        }
                                    } finally {
                                        isUserInteracting = false
                                    }
                                }
                            }
                        },
                        onLongPress = { offset ->
                            currentOnMapLongPress(screenToGeo(offset))
                        }
                    )
                }
        ) {
            // Read trigger so Canvas redraws as async tiles arrive
            val unusedTrigger = tileTrigger
            if (unusedTrigger < 0) return@Canvas

            // 1. Tiles with synchronous LRU cache lookup + parent/child fallback (zero dark flashes)
            val tileSizeInt = ceil(tilePx).toInt() + 1
            for (ty in minY..maxY) {
                if (ty < 0 || ty >= numTiles) continue
                for (tx in minX..maxX) {
                    val wrappedX = ((tx % numTiles) + numTiles) % numTiles
                    val key = "${style.id}/$tileIntZ/$wrappedX/$ty"
                    val bmp = tileBitmaps[key] ?: TileCacheService.getCachedImageBitmap(style.id, tileIntZ, wrappedX, ty)
                    val sx = (widthPx / 2f + (tx - centerTileX) * tilePx).roundToInt()
                    val sy = (heightPx / 2f + (ty - centerTileY) * tilePx).roundToInt()
                    if (bmp != null) {
                        drawImage(
                            image = bmp,
                            dstOffset = IntOffset(sx, sy),
                            dstSize = IntSize(tileSizeInt, tileSizeInt)
                        )
                    } else {
                        val parentFallback = TileCacheService.findFallbackImageBitmap(
                            styleId = style.id,
                            targetZ = tileIntZ,
                            targetX = wrappedX,
                            targetY = ty,
                            minZ = 5
                        )
                        if (parentFallback != null) {
                            val (pBmp, slice) = parentFallback
                            drawImage(
                                image = pBmp,
                                srcOffset = IntOffset(slice.srcX, slice.srcY),
                                srcSize = IntSize(slice.srcW, slice.srcH),
                                dstOffset = IntOffset(sx, sy),
                                dstSize = IntSize(tileSizeInt, tileSizeInt)
                            )
                        } else {
                            // Try 2x2 child tiles at z+1 when zooming out before parent loads
                            var drewAnyChild = false
                            val childZ = tileIntZ + 1
                            if (childZ <= maxZ.toInt()) {
                                val childNum = 1 shl childZ
                                val halfW = tileSizeInt / 2
                                val halfH = tileSizeInt / 2
                                for (row in 0..1) {
                                    val cy = ty * 2 + row
                                    if (cy < 0 || cy >= childNum) continue
                                    for (col in 0..1) {
                                        val cx = (((wrappedX * 2 + col) % childNum) + childNum) % childNum
                                        val childBmp = TileCacheService.getCachedImageBitmap(style.id, childZ, cx, cy)
                                        if (childBmp != null) {
                                            if (!drewAnyChild) {
                                                drawRect(
                                                    color = Color(0xFF101926),
                                                    topLeft = Offset(sx.toFloat(), sy.toFloat()),
                                                    size = Size(tileSizeInt.toFloat(), tileSizeInt.toFloat())
                                                )
                                                drewAnyChild = true
                                            }
                                            val dstW = if (col == 0) halfW + 1 else tileSizeInt - halfW
                                            val dstH = if (row == 0) halfH + 1 else tileSizeInt - halfH
                                            drawImage(
                                                image = childBmp,
                                                dstOffset = IntOffset(sx + col * halfW, sy + row * halfH),
                                                dstSize = IntSize(dstW.coerceAtLeast(1), dstH.coerceAtLeast(1))
                                            )
                                        }
                                    }
                                }
                            }
                            if (!drewAnyChild) {
                                drawRect(
                                    color = Color(0xFF101926),
                                    topLeft = Offset(sx.toFloat(), sy.toFloat()),
                                    size = Size(tileSizeInt.toFloat(), tileSizeInt.toFloat())
                                )
                            }
                        }
                    }
                }
            }

            // 2. Decimated route polyline (skips sub-2.5px vertices for high framerate)
            if (routeGeometry.size >= 2) {
                val path = Path()
                var lastAdded = Offset.Zero
                val lastIdx = routeGeometry.lastIndex
                routeGeometry.forEachIndexed { idx, gp ->
                    val pt = geoToScreen(gp)
                    if (idx == 0) {
                        path.moveTo(pt.x, pt.y)
                        lastAdded = pt
                    } else if (idx == lastIdx || hypot(pt.x - lastAdded.x, pt.y - lastAdded.y) >= 2.5f) {
                        path.lineTo(pt.x, pt.y)
                        lastAdded = pt
                    }
                }
                drawPath(
                    path = path,
                    color = Color(0xCC041418),
                    style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawPath(
                    path = path,
                    color = c.accent,
                    style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }

            // 2b. Highlighted hill stretch
            if (highlightedHillPoints.size >= 2) {
                val hPath = Path()
                highlightedHillPoints.forEachIndexed { idx, gp ->
                    val pt = geoToScreen(gp)
                    if (idx == 0) hPath.moveTo(pt.x, pt.y) else hPath.lineTo(pt.x, pt.y)
                }
                drawPath(
                    path = hPath,
                    color = Color(0xFFF59E0B),
                    style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }

            // 2c. Elevation scrub cursor on map
            if (highlightCursorPoint != null) {
                val pt = geoToScreen(highlightCursorPoint)
                drawCircle(color = Color.White, radius = 9.dp.toPx(), center = pt)
                drawCircle(color = Color(0xFF38BDF8), radius = 6.dp.toPx(), center = pt)
            }
        }

        // 2d. Tapped point pin marker
        if (selectedPin != null) {
            val pos = geoToScreen(selectedPin)
            if (pos.x in -40f..(widthPx + 40f) && pos.y in -40f..(heightPx + 40f)) {
                val halfPx = with(density) { 16.dp.roundToPx() }
                Box(
                    modifier = Modifier
                        .offset { IntOffset(pos.x.roundToInt() - halfPx, pos.y.roundToInt() - halfPx) }
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEF4444).copy(alpha = 0.92f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }
            }
        }

        // 3. SPKLU & Charger Molis Pins (viewport-culled, lambda offset avoids measure/recompose churn)
        val spkluHalfPx = with(density) { 24.dp.roundToPx() }
        var shownSpklu = 0
        for (s in nearbySpklu) {
            if (shownSpklu >= 45) break
            val pos = geoToScreen(GeoPointX(s.lat, s.lng))
            if (pos.x in -48f..(widthPx + 48f) && pos.y in -48f..(heightPx + 48f)) {
                shownSpklu++
                key(s.id) {
                    val pinTint = if (s.isMolisFriendly) c.accent else c.regen
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(pos.x.roundToInt() - spkluHalfPx, pos.y.roundToInt() - spkluHalfPx) }
                            .size(48.dp)
                            .clickable { onSpkluClick(s) },
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier.size(34.dp),
                            shape = CircleShape,
                            color = c.surfaceCard,
                            border = BorderStroke(2.dp, pinTint),
                            shadowElevation = 3.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (s.isMolisFriendly) Icons.Rounded.ElectricMoped else Icons.Rounded.EvStation,
                                    contentDescription = s.name,
                                    tint = pinTint,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Field Note Pins
        val noteHalfPx = with(density) { 14.dp.roundToPx() }
        fieldNotes.forEach { n ->
            val pos = geoToScreen(GeoPointX(n.lat, n.lng))
            if (pos.x in -40f..(widthPx + 40f) && pos.y in -40f..(heightPx + 40f)) {
                key(n.id) {
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(pos.x.roundToInt() - noteHalfPx, pos.y.roundToInt() - noteHalfPx) }
                            .size(28.dp)
                            .clickable(enabled = onFieldNoteClick != null) { onFieldNoteClick?.invoke(n) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Bookmark,
                            contentDescription = "${n.name}: ${n.note}",
                            tint = c.warning,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }

        // 5. Planned Stops
        val stopHalfPx = with(density) { 18.dp.roundToPx() }
        plannedStops.forEachIndexed { idx, stop ->
            val pos = geoToScreen(GeoPointX(stop.lat, stop.lng))
            if (pos.x in -40f..(widthPx + 40f) && pos.y in -40f..(heightPx + 40f)) {
                key(stop.id) {
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(pos.x.roundToInt() - stopHalfPx, pos.y.roundToInt() - stopHalfPx) }
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (stop.isSpklu) c.regen else c.danger),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (stop.isSpklu) "⚡" else "${idx + 1}",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // 6. Rider Marker
        val riderGeo = riderLocation ?: viewCenter
        val rPos = geoToScreen(riderGeo)
        val riderHalfPx = with(density) { 22.dp.roundToPx() }
        Box(
            modifier = Modifier
                .offset { IntOffset(rPos.x.roundToInt() - riderHalfPx, rPos.y.roundToInt() - riderHalfPx) }
                .size(44.dp)
                .rotate(riderHeadingDeg.toFloat()),
            contentAlignment = Alignment.Center
        ) {
            if (customMarkerBitmap != null) {
                Image(
                    bitmap = customMarkerBitmap,
                    contentDescription = "Posisi Pengendara",
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Rounded.Navigation,
                    contentDescription = "Posisi Pengendara",
                    tint = c.accent,
                    modifier = Modifier.size(34.dp)
                )
            }
        }
    }
}
