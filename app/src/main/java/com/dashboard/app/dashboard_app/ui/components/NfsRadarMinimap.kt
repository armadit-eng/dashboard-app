package com.dashboard.app.dashboard_app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.GpsOff
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dashboard.app.dashboard_app.engine.RoutePhysics
import com.dashboard.app.dashboard_app.model.GeoPointX
import com.dashboard.app.dashboard_app.model.MapStyleOption
import com.dashboard.app.dashboard_app.model.PlannedStop
import com.dashboard.app.dashboard_app.model.SpkluStation
import com.dashboard.app.dashboard_app.service.TileCacheService
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

object NfsRadarMath {
    private fun lngToWorldX(lng: Double): Double = (lng + 180.0) / 360.0

    private fun latToWorldY(lat: Double): Double {
        val rad = lat.coerceIn(-85.0511, 85.0511) * PI / 180.0
        return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0
    }

    /**
     * Smoothly moves [currentUnwrappedDeg] to match [targetBearingDeg] (0..360)
     * along the shortest angular arc so 355° -> 5° animates +10° instead of -350°.
     */
    fun shortestUnwrappedAngleDeg(currentUnwrappedDeg: Float, targetBearingDeg: Float): Float {
        var delta = ((targetBearingDeg - currentUnwrappedDeg) % 360f + 540f) % 360f - 180f
        if (delta == -180f) delta = 180f
        return currentUnwrappedDeg + delta
    }

    /**
     * Computes north-up screen offset relative to radar center (0,0) in pixels.
     */
    fun geoToRadarOffsetPx(
        point: GeoPointX,
        center: GeoPointX,
        zoom: Double,
        baseTilePx: Double
    ): Offset {
        val worldPx = baseTilePx * 2.0.pow(zoom)
        val dx = ((lngToWorldX(point.lng) - lngToWorldX(center.lng)) * worldPx).toFloat()
        val dy = ((latToWorldY(point.lat) - latToWorldY(center.lat)) * worldPx).toFloat()
        return Offset(dx, dy)
    }

    /**
     * Rotates a north-up offset by [rotationDeg] (same convention as Compose DrawScope.rotate)
     * and clamps it to [maxRimRadiusPx] if outside the radar circle.
     */
    fun projectAndClampToRadarRim(
        northUpOffset: Offset,
        rotationDeg: Float,
        maxRimRadiusPx: Float
    ): Pair<Offset, Boolean> {
        val rad = rotationDeg * PI / 180.0
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        val rx = northUpOffset.x * cosA - northUpOffset.y * sinA
        val ry = northUpOffset.x * sinA + northUpOffset.y * cosA
        val dist = hypot(rx, ry)
        return if (dist <= maxRimRadiusPx || dist < 1e-3f) {
            Offset(rx, ry) to false
        } else {
            val scale = maxRimRadiusPx / dist
            Offset(rx * scale, ry * scale) to true
        }
    }

    fun radarRangeMetersLabel(zoom: Double): String = when {
        zoom >= 17.2 -> "120m"
        zoom >= 16.2 -> "240m"
        else -> "480m"
    }
}

@Composable
fun NfsRadarMinimap(
    riderLocation: GeoPointX?,
    riderHeadingDeg: Double,
    speedKmh: Double?,
    roadName: String,
    areaName: String,
    mapStyle: MapStyleOption,
    cartoApiKey: String = "",
    routeGeometry: List<GeoPointX> = emptyList(),
    plannedStops: List<PlannedStop> = emptyList(),
    spkluStations: List<SpkluStation> = emptyList(),
    breadcrumbPoints: List<GeoPointX> = emptyList(),
    hasLocationPermission: Boolean = true,
    compactSize: Dp = 148.dp,
    expandedSize: Dp = 196.dp,
    isExpanded: Boolean = false,
    onToggleExpand: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var headingUp by remember { mutableStateOf(true) }
    val zoomPresets = remember { listOf(16.4, 17.3, 15.4) }
    var zoomPresetIdx by remember { mutableIntStateOf(0) }
    val activeZoom = zoomPresets[zoomPresetIdx]

    // Unwrapped heading so radar rotates smoothly across 359° -> 0°
    var targetUnwrappedHeading by remember { mutableFloatStateOf(riderHeadingDeg.toFloat()) }
    LaunchedEffect(riderHeadingDeg) {
        targetUnwrappedHeading = NfsRadarMath.shortestUnwrappedAngleDeg(
            targetUnwrappedHeading,
            riderHeadingDeg.toFloat()
        )
    }
    val smoothHeading by animateFloatAsState(
        targetValue = targetUnwrappedHeading,
        animationSpec = tween(durationMillis = 360),
        label = "radarHeading"
    )
    val animatedSize by animateDpAsState(
        targetValue = if (isExpanded) expandedSize else compactSize,
        animationSpec = spring(),
        label = "radarSize"
    )

    val inf = rememberInfiniteTransition(label = "radarSweep")
    val pulsePhase by inf.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulsePhase"
    )

    // Prefer dark high-contrast map tiles for authentic NFS Most Wanted radar look
    val effectiveStyle = remember(mapStyle) {
        if (mapStyle.isDarkBase) mapStyle else MapStyleOption.DARK_CARTO
    }

    val tileBitmaps = remember(effectiveStyle.id, cartoApiKey) { mutableStateMapOf<String, ImageBitmap>() }
    val inFlightKeys = remember(effectiveStyle.id, cartoApiKey) { mutableSetOf<String>() }
    var tileTrigger by remember { mutableIntStateOf(0) }

    val tileIntZ = activeZoom.roundToInt().coerceIn(14, 18)
    val scaleFactor = 2.0.pow(activeZoom - tileIntZ)
    val baseTilePx = (256.0 * density.density * 0.68).coerceIn(190.0, 460.0)
    val tilePx = baseTilePx * scaleFactor
    val radarPx = with(density) { animatedSize.toPx() }
    val halfDiag = hypot(radarPx / 2f, radarPx / 2f)

    // Fetch visible slippy tiles around current rider location (covering rotated diagonal radius)
    LaunchedEffect(riderLocation, effectiveStyle.id, cartoApiKey, tileIntZ, radarPx, TileCacheService.offlineOnly) {
        val loc = riderLocation ?: return@LaunchedEffect
        val numTiles = 1 shl tileIntZ
        val cWx = (loc.lng + 180.0) / 360.0
        val rad = loc.lat.coerceIn(-85.0511, 85.0511) * PI / 180.0
        val cWy = (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0
        val cTx = cWx * numTiles
        val cTy = cWy * numTiles
        val spanTiles = (halfDiag / tilePx) + 0.35

        val minX = floor(cTx - spanTiles).toInt()
        val maxX = ceil(cTx + spanTiles).toInt()
        val minY = floor(cTy - spanTiles).toInt()
        val maxY = ceil(cTy + spanTiles).toInt()

        for (ty in minY..maxY) {
            if (ty < 0 || ty >= numTiles) continue
            for (tx in minX..maxX) {
                val wx = ((tx % numTiles) + numTiles) % numTiles
                val key = "${effectiveStyle.id}/$tileIntZ/$wx/$ty"
                if (!tileBitmaps.containsKey(key)) {
                    val cached = TileCacheService.getCachedImageBitmap(effectiveStyle.id, tileIntZ, wx, ty)
                    if (cached != null) {
                        tileBitmaps[key] = cached
                    } else if (inFlightKeys.add(key)) {
                        scope.launch {
                            try {
                                val bmp = TileCacheService.getTileBitmap(
                                    context = context,
                                    styleId = effectiveStyle.id,
                                    urlTemplate = effectiveStyle.tileUrlTemplate,
                                    z = tileIntZ,
                                    x = wx,
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
        }
        if (tileBitmaps.size > 64) {
            val keepPrefix = "${effectiveStyle.id}/$tileIntZ/"
            tileBitmaps.keys.filter { !it.startsWith(keepPrefix) }.take(tileBitmaps.size - 48).forEach {
                tileBitmaps.remove(it)
            }
        }
    }

    val nfsGold = Color(0xFFF59E0B)
    val nfsGoldBright = Color(0xFFFACC15)
    val nfsCyan = Color(0xFF38BDF8)
    val nfsEmerald = Color(0xFF10B981)
    val gpsReady = riderLocation != null
    val statusBorderColor by animateColorAsState(
        targetValue = if (gpsReady) nfsGold else Color(0xFF64748B),
        label = "radarBorder"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start
    ) {
        // Circular Need for Speed Most Wanted Radar Scope
        Box(
            modifier = Modifier
                .size(animatedSize)
                .clip(CircleShape)
                .background(Color(0xE6060B12))
                .clickable(onClick = onToggleExpand)
                .testTag("motovlog_nfs_radar")
                .semantics {
                    contentDescription = "Radar minimap lokasi terkini, ketuk untuk perbesar atau perkecil"
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val unusedTrigger = tileTrigger
                if (unusedTrigger < 0) return@Canvas

                val cx = size.width / 2f
                val cy = size.height / 2f
                val radius = size.minDimension / 2f
                val mapRotationDeg = if (headingUp) -smoothHeading else 0f

                val circleClip = Path().apply {
                    addOval(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                }

                clipPath(circleClip) {
                    drawRect(color = Color(0xFF070D16))

                    if (riderLocation != null) {
                        val numTiles = 1 shl tileIntZ
                        val cWx = (riderLocation.lng + 180.0) / 360.0
                        val radLat = riderLocation.lat.coerceIn(-85.0511, 85.0511) * PI / 180.0
                        val cWy = (1.0 - ln(tan(radLat) + 1.0 / cos(radLat)) / PI) / 2.0
                        val cTx = cWx * numTiles
                        val cTy = cWy * numTiles
                        val spanTiles = (hypot(cx, cy) / tilePx) + 0.35

                        val minX = floor(cTx - spanTiles).toInt()
                        val maxX = ceil(cTx + spanTiles).toInt()
                        val minY = floor(cTy - spanTiles).toInt()
                        val maxY = ceil(cTy + spanTiles).toInt()
                        val tileSizeInt = ceil(tilePx).toInt() + 1

                        rotate(degrees = mapRotationDeg, pivot = Offset(cx, cy)) {
                            // 1. Live Slippy Map Tiles
                            for (ty in minY..maxY) {
                                if (ty < 0 || ty >= numTiles) continue
                                for (tx in minX..maxX) {
                                    val wx = ((tx % numTiles) + numTiles) % numTiles
                                    val key = "${effectiveStyle.id}/$tileIntZ/$wx/$ty"
                                    val bmp = tileBitmaps[key]
                                        ?: TileCacheService.getCachedImageBitmap(effectiveStyle.id, tileIntZ, wx, ty)
                                    val sx = (cx + (tx - cTx) * tilePx).roundToInt()
                                    val sy = (cy + (ty - cTy) * tilePx).roundToInt()
                                    if (bmp != null) {
                                        drawImage(
                                            image = bmp,
                                            dstOffset = IntOffset(sx, sy),
                                            dstSize = IntSize(tileSizeInt, tileSizeInt)
                                        )
                                    } else {
                                        val fallback = TileCacheService.findFallbackImageBitmap(
                                            styleId = effectiveStyle.id,
                                            targetZ = tileIntZ,
                                            targetX = wx,
                                            targetY = ty,
                                            minZ = 5
                                        )
                                        if (fallback != null) {
                                            val (pBmp, slice) = fallback
                                            drawImage(
                                                image = pBmp,
                                                srcOffset = IntOffset(slice.srcX, slice.srcY),
                                                srcSize = IntSize(slice.srcW, slice.srcH),
                                                dstOffset = IntOffset(sx, sy),
                                                dstSize = IntSize(tileSizeInt, tileSizeInt)
                                            )
                                        }
                                    }
                                }
                            }

                            // 2. Fallback/overlay subtle tactical grid lines so roads read sharply
                            val gridStep = radius * 0.45f
                            for (g in -3..3) {
                                val off = g * gridStep
                                drawLine(
                                    color = Color(0x1E38BDF8),
                                    start = Offset(cx + off, cy - radius * 1.4f),
                                    end = Offset(cx + off, cy + radius * 1.4f),
                                    strokeWidth = 1f
                                )
                                drawLine(
                                    color = Color(0x1E38BDF8),
                                    start = Offset(cx - radius * 1.4f, cy + off),
                                    end = Offset(cx + radius * 1.4f, cy + off),
                                    strokeWidth = 1f
                                )
                            }

                            // 3. Breadcrumb ridden trail
                            if (breadcrumbPoints.size >= 2) {
                                val trailPath = Path()
                                breadcrumbPoints.forEachIndexed { idx, gp ->
                                    val off = NfsRadarMath.geoToRadarOffsetPx(gp, riderLocation, activeZoom, baseTilePx)
                                    val p = Offset(cx + off.x, cy + off.y)
                                    if (idx == 0) trailPath.moveTo(p.x, p.y) else trailPath.lineTo(p.x, p.y)
                                }
                                drawPath(
                                    path = trailPath,
                                    color = nfsEmerald.copy(alpha = 0.65f),
                                    style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                )
                            }

                            // 4. Active navigation route line (NFS electric cyan)
                            if (routeGeometry.size >= 2) {
                                val routePath = Path()
                                var lastPt = Offset.Zero
                                val lastIdx = routeGeometry.lastIndex
                                routeGeometry.forEachIndexed { idx, gp ->
                                    val off = NfsRadarMath.geoToRadarOffsetPx(gp, riderLocation, activeZoom, baseTilePx)
                                    val p = Offset(cx + off.x, cy + off.y)
                                    if (idx == 0) {
                                        routePath.moveTo(p.x, p.y)
                                        lastPt = p
                                    } else if (idx == lastIdx || hypot(p.x - lastPt.x, p.y - lastPt.y) >= 2f) {
                                        routePath.lineTo(p.x, p.y)
                                        lastPt = p
                                    }
                                }
                                drawPath(
                                    path = routePath,
                                    color = Color(0xCC04121F),
                                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                )
                                drawPath(
                                    path = routePath,
                                    color = nfsCyan,
                                    style = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                )
                            }

                            // 5. Nearby EV Charger / Molis charging blips inside 2.5 km
                            spkluStations.forEach { st ->
                                val stGeo = GeoPointX(st.lat, st.lng)
                                if (RoutePhysics.haversineKm(riderLocation, stGeo) <= 2.5) {
                                    val off = NfsRadarMath.geoToRadarOffsetPx(stGeo, riderLocation, activeZoom, baseTilePx)
                                    val blipCenter = Offset(cx + off.x, cy + off.y)
                                    val blipColor = if (st.isMolisFriendly) nfsEmerald else nfsCyan
                                    drawCircle(
                                        color = blipColor.copy(alpha = 0.35f),
                                        radius = 5.5.dp.toPx(),
                                        center = blipCenter
                                    )
                                    drawCircle(
                                        color = blipColor,
                                        radius = 2.8.dp.toPx(),
                                        center = blipCenter
                                    )
                                }
                            }
                        }
                    } else {
                        // Radar grid even before first GPS lock
                        val step = radius * 0.4f
                        for (g in -2..2) {
                            drawLine(
                                color = Color(0x22F59E0B),
                                start = Offset(cx + g * step, 0f),
                                end = Offset(cx + g * step, size.height),
                                strokeWidth = 1f
                            )
                            drawLine(
                                color = Color(0x22F59E0B),
                                start = Offset(0f, cy + g * step),
                                end = Offset(size.width, cy + g * step),
                                strokeWidth = 1f
                            )
                        }
                    }

                    // 6. NFS Radar concentric rings + scanning pulse + dark radial edge vignette
                    drawRect(
                        brush = Brush.radialGradient(
                            0.0f to Color.Transparent,
                            0.68f to Color(0x22040810),
                            1.0f to Color(0xD8040810),
                            center = Offset(cx, cy),
                            radius = radius
                        )
                    )

                    val ringDash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                    drawCircle(
                        color = nfsGold.copy(alpha = 0.25f),
                        radius = radius * 0.44f,
                        center = Offset(cx, cy),
                        style = Stroke(width = 1.dp.toPx(), pathEffect = ringDash)
                    )
                    drawCircle(
                        color = nfsGold.copy(alpha = 0.2f),
                        radius = radius * 0.80f,
                        center = Offset(cx, cy),
                        style = Stroke(width = 1.dp.toPx())
                    )

                    // Expanding radar ping ring
                    val speedBoost = ((speedKmh ?: 0.0) / 80.0).coerceIn(0.0, 1.0).toFloat()
                    drawCircle(
                        color = (if (gpsReady) nfsGold else nfsCyan).copy(alpha = (1f - pulsePhase) * (0.28f + 0.14f * speedBoost)),
                        radius = radius * (0.12f + 0.84f * pulsePhase),
                        center = Offset(cx, cy),
                        style = Stroke(width = 1.6.dp.toPx())
                    )

                    // 7. Destination objective marker (either on-map or clamped along radar rim!)
                    val finalStop = plannedStops.lastOrNull()
                    if (riderLocation != null && finalStop != null) {
                        val destOffset = NfsRadarMath.geoToRadarOffsetPx(
                            GeoPointX(finalStop.lat, finalStop.lng),
                            riderLocation,
                            activeZoom,
                            baseTilePx
                        )
                        val (projected, wasClamped) = NfsRadarMath.projectAndClampToRadarRim(
                            northUpOffset = destOffset,
                            rotationDeg = mapRotationDeg,
                            maxRimRadiusPx = radius - 11.dp.toPx()
                        )
                        val targetPos = Offset(cx + projected.x, cy + projected.y)
                        val targetAngleDeg = (atan2(projected.y, projected.x) * 180.0 / PI).toFloat() + 90f
                        if (wasClamped) {
                            // Classic NFS rim directional triangle pointing to target
                            rotate(degrees = targetAngleDeg, pivot = targetPos) {
                                val rPx = 5.5.dp.toPx()
                                val tri = Path().apply {
                                    moveTo(targetPos.x, targetPos.y - rPx)
                                    lineTo(targetPos.x - rPx * 0.8f, targetPos.y + rPx * 0.7f)
                                    lineTo(targetPos.x + rPx * 0.8f, targetPos.y + rPx * 0.7f)
                                    close()
                                }
                                drawPath(tri, color = Color(0xFF090D16), style = Stroke(width = 3.dp.toPx()))
                                drawPath(tri, color = nfsGoldBright, style = Fill)
                            }
                        } else {
                            drawCircle(color = nfsGoldBright.copy(alpha = 0.35f), radius = 8.dp.toPx(), center = targetPos)
                            drawCircle(color = nfsGoldBright, radius = 4.dp.toPx(), center = targetPos)
                        }
                    }

                    // 8. Center Player Arrow (NFS Most Wanted chevron + headlight cone)
                    val arrowRotation = if (headingUp) 0f else smoothHeading
                    rotate(degrees = arrowRotation, pivot = Offset(cx, cy)) {
                        drawPlayerFovAndChevron(center = Offset(cx, cy), arrowColor = nfsGoldBright)
                    }
                }

                // 9. Metallic NFS outer bezel + 15° radar tick marks + North indicator pip
                drawCircle(
                    color = statusBorderColor.copy(alpha = 0.85f),
                    radius = radius - 1.5.dp.toPx(),
                    center = Offset(cx, cy),
                    style = Stroke(width = 2.5.dp.toPx())
                )
                drawCircle(
                    color = Color(0x99060A12),
                    radius = radius - 4.5.dp.toPx(),
                    center = Offset(cx, cy),
                    style = Stroke(width = 3.dp.toPx())
                )
                for (deg in 0 until 360 step 15) {
                    val isMajor = deg % 90 == 0
                    val rad = (deg - 90 + mapRotationDeg) * PI / 180.0
                    val innerR = radius - (if (isMajor) 9.dp.toPx() else 6.dp.toPx())
                    val outerR = radius - 3.dp.toPx()
                    drawLine(
                        color = if (isMajor) nfsGold.copy(alpha = 0.9f) else Color(0x66CBD5E1),
                        start = Offset(cx + cos(rad).toFloat() * innerR, cy + sin(rad).toFloat() * innerR),
                        end = Offset(cx + cos(rad).toFloat() * outerR, cy + sin(rad).toFloat() * outerR),
                        strokeWidth = if (isMajor) 2.dp.toPx() else 1.dp.toPx()
                    )
                }

                // Rotating North ("N") indicator pip on bezel rim
                val northAngleRad = (-90.0 + mapRotationDeg) * PI / 180.0
                val pipR = radius - 7.dp.toPx()
                val nPos = Offset(
                    cx + cos(northAngleRad).toFloat() * pipR,
                    cy + sin(northAngleRad).toFloat() * pipR
                )
                drawCircle(color = Color(0xFFEF4444), radius = 4.2.dp.toPx(), center = nPos)
                drawCircle(color = Color.White, radius = 1.6.dp.toPx(), center = nPos)
            }

            // Tiny corner zoom indicator inside bottom of radar
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xCC060B12),
                border = BorderStroke(0.8.dp, statusBorderColor.copy(alpha = 0.65f)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
            ) {
                Text(
                    text = if (gpsReady) NfsRadarMath.radarRangeMetersLabel(activeZoom) else "NO FIX",
                    color = if (gpsReady) nfsGoldBright else Color(0xFF94A3B8),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // Compact Road Name + Radar Controls Row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xB8070B12),
                border = BorderStroke(1.dp, statusBorderColor.copy(alpha = 0.55f)),
                modifier = Modifier.widthIn(max = 176.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (gpsReady) Icons.Rounded.GpsFixed else Icons.Rounded.GpsOff,
                        contentDescription = null,
                        tint = if (gpsReady) nfsEmerald else nfsGold,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = when {
                            !hasLocationPermission -> "Izin GPS nonaktif"
                            roadName.isNotBlank() -> roadName
                            areaName.isNotBlank() -> areaName
                            riderLocation != null -> String.format(
                                java.util.Locale.US,
                                "%.4f, %.4f",
                                riderLocation.lat,
                                riderLocation.lng
                            )
                            else -> "Mencari GPS…"
                        },
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (!hasLocationPermission) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = nfsGold,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clickable(onClick = onRequestLocationPermission)
                        .testTag("radar_enable_gps_button")
                ) {
                    Text(
                        text = "GPS",
                        color = Color.Black,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            } else {
                // Toggle Heading-Up vs North-Up
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xB8070B12),
                    border = BorderStroke(1.dp, (if (headingUp) nfsGold else nfsCyan).copy(alpha = 0.65f)),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clickable { headingUp = !headingUp }
                        .testTag("radar_heading_toggle")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Explore,
                            contentDescription = "Mode orientasi radar",
                            tint = if (headingUp) nfsGoldBright else nfsCyan,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = if (headingUp) "HDG" else "N",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Cycle Zoom Scale (240m / 120m / 480m)
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xB8070B12),
                    border = BorderStroke(1.dp, nfsCyan.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clickable { zoomPresetIdx = (zoomPresetIdx + 1) % zoomPresets.size }
                        .testTag("radar_zoom_cycle")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.ZoomIn,
                            contentDescription = "Ubah skala radar",
                            tint = nfsCyan,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawPlayerFovAndChevron(center: Offset, arrowColor: Color) {
    val coneRadius = 34.dp.toPx()
    // Forward FOV / headlight cone
    drawArc(
        brush = Brush.radialGradient(
            0f to arrowColor.copy(alpha = 0.42f),
            1f to Color.Transparent,
            center = center,
            radius = coneRadius
        ),
        startAngle = -118f,
        sweepAngle = 56f,
        useCenter = true,
        topLeft = Offset(center.x - coneRadius, center.y - coneRadius),
        size = Size(coneRadius * 2f, coneRadius * 2f)
    )

    // Iconic Need for Speed swept delta chevron arrow
    val tipY = center.y - 9.5.dp.toPx()
    val wingY = center.y + 7.5.dp.toPx()
    val notchY = center.y + 4.dp.toPx()
    val halfW = 7.2.dp.toPx()

    val arrowPath = Path().apply {
        moveTo(center.x, tipY)
        lineTo(center.x + halfW, wingY)
        lineTo(center.x, notchY)
        lineTo(center.x - halfW, wingY)
        close()
    }
    drawPath(
        path = arrowPath,
        color = Color(0xE605080F),
        style = Stroke(width = 3.2.dp.toPx(), join = StrokeJoin.Round)
    )
    drawPath(
        path = arrowPath,
        color = arrowColor,
        style = Fill
    )
    drawPath(
        path = arrowPath,
        color = Color.White.copy(alpha = 0.85f),
        style = Stroke(width = 1.dp.toPx(), join = StrokeJoin.Round)
    )
}
