package com.dashboard.app.dashboard_app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh

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
    val clampedZoom = zoom.coerceIn(5.0, maxZ)
    val tileIntZ = clampedZoom.roundToInt().coerceIn(5, maxZ.toInt())
    val scaleFactor = 2.0.pow(clampedZoom - tileIntZ)
    val tilePx = (256.0 * density.density * 0.72 * scaleFactor).coerceIn(180.0, 520.0)

    val tileBitmaps = remember(style.id, cartoApiKey) { mutableStateMapOf<String, ImageBitmap?>() }
    var tileTrigger by remember { mutableIntStateOf(0) }

    fun lngToTileX(lng: Double, z: Int): Double = ((lng + 180.0) / 360.0) * (1 shl z)
    fun latToTileY(lat: Double, z: Int): Double {
        val rad = lat.coerceIn(-85.0511, 85.0511) * PI / 180.0
        return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * (1 shl z)
    }
    fun tileXToLng(tx: Double, z: Int): Double = (tx / (1 shl z)) * 360.0 - 180.0
    fun tileYToLat(ty: Double, z: Int): Double {
        val n = PI - (2.0 * PI * ty) / (1 shl z)
        return (180.0 / PI) * atan(sinh(n))
    }

    val customMarkerBitmap = remember(customMarkerPath) {
        if (customMarkerPath.isNotEmpty() && File(customMarkerPath).exists()) {
            BitmapFactory.decodeFile(customMarkerPath)?.asImageBitmap()
        } else null
    }

    BoxWithConstraints(modifier = modifier.background(Color(0xFF0A111A))) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }

        val centerTileX = lngToTileX(center.lng, tileIntZ)
        val centerTileY = latToTileY(center.lat, tileIntZ)
        val halfScreenTilesX = (widthPx / 2f) / tilePx
        val halfScreenTilesY = (heightPx / 2f) / tilePx

        val minX = floor(centerTileX - halfScreenTilesX).toInt()
        val maxX = ceil(centerTileX + halfScreenTilesX).toInt()
        val minY = floor(centerTileY - halfScreenTilesY).toInt()
        val maxY = ceil(centerTileY + halfScreenTilesY).toInt()
        val numTiles = 1 shl tileIntZ

        LaunchedEffect(style.id, cartoApiKey, tileIntZ, minX, maxX, minY, maxY, TileCacheService.offlineOnly) {
            for (ty in minY..maxY) {
                if (ty < 0 || ty >= numTiles) continue
                for (tx in minX..maxX) {
                    val wrappedX = ((tx % numTiles) + numTiles) % numTiles
                    val key = "${style.id}/$tileIntZ/$wrappedX/$ty"
                    if (!tileBitmaps.containsKey(key)) {
                        tileBitmaps[key] = null
                        scope.launch {
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
                        }
                    }
                }
            }
        }

        fun geoToScreen(gp: GeoPointX): Offset {
            val gx = lngToTileX(gp.lng, tileIntZ)
            val gy = latToTileY(gp.lat, tileIntZ)
            return Offset(
                x = (widthPx / 2f + (gx - centerTileX) * tilePx).toFloat(),
                y = (heightPx / 2f + (gy - centerTileY) * tilePx).toFloat()
            )
        }

        fun screenToGeo(offset: Offset): GeoPointX {
            val tx = centerTileX + (offset.x - widthPx / 2f) / tilePx
            val ty = centerTileY + (offset.y - heightPx / 2f) / tilePx
            return GeoPointX(
                lat = tileYToLat(ty, tileIntZ).coerceIn(-85.0, 85.0),
                lng = tileXToLng(tx, tileIntZ).coerceIn(-180.0, 180.0)
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(center, clampedZoom, widthPx, heightPx) {
                    detectTransformGestures { _, pan, zoomChange, _ ->
                        val nextZoom = if (zoomChange != 1f) {
                            (clampedZoom + kotlin.math.ln(zoomChange.toDouble()) / kotlin.math.ln(2.0))
                                .coerceIn(5.0, maxZ)
                        } else clampedZoom
                        val nextTx = centerTileX - pan.x / tilePx
                        val nextTy = centerTileY - pan.y / tilePx
                        val nextCenter = GeoPointX(
                            lat = tileYToLat(nextTy, tileIntZ).coerceIn(-85.0, 85.0),
                            lng = tileXToLng(nextTx, tileIntZ).coerceIn(-180.0, 180.0)
                        )
                        onMapPanZoom(nextCenter, nextZoom)
                    }
                }
                .pointerInput(center, clampedZoom, widthPx, heightPx) {
                    detectTapGestures(
                        onTap = { offset ->
                            onMapTap?.invoke(screenToGeo(offset))
                        },
                        onLongPress = { offset ->
                            onMapLongPress(screenToGeo(offset))
                        }
                    )
                }
        ) {
            // Read trigger so Canvas redraws as async tiles arrive
            val unusedTrigger = tileTrigger
            if (unusedTrigger < 0) return@Canvas

            // 1. Tiles
            val tileSizeInt = ceil(tilePx).toInt() + 1
            for (ty in minY..maxY) {
                if (ty < 0 || ty >= numTiles) continue
                for (tx in minX..maxX) {
                    val wrappedX = ((tx % numTiles) + numTiles) % numTiles
                    val key = "${style.id}/$tileIntZ/$wrappedX/$ty"
                    val bmp = tileBitmaps[key]
                    val sx = (widthPx / 2f + (tx - centerTileX) * tilePx).roundToInt()
                    val sy = (heightPx / 2f + (ty - centerTileY) * tilePx).roundToInt()
                    if (bmp != null) {
                        drawImage(
                            image = bmp,
                            dstOffset = IntOffset(sx, sy),
                            dstSize = IntSize(tileSizeInt, tileSizeInt)
                        )
                    } else {
                        drawRect(
                            color = Color(0xFF101926),
                            topLeft = Offset(sx.toFloat(), sy.toFloat()),
                            size = androidx.compose.ui.geometry.Size(tileSizeInt.toFloat(), tileSizeInt.toFloat())
                        )
                    }
                }
            }

            // 2. Route polyline
            if (routeGeometry.size >= 2) {
                val path = Path()
                routeGeometry.forEachIndexed { idx, gp ->
                    val pt = geoToScreen(gp)
                    if (idx == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
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
                val dpX = with(density) { pos.x.toDp() - 16.dp }
                val dpY = with(density) { pos.y.toDp() - 16.dp }
                Box(
                    modifier = Modifier
                        .offset(x = dpX, y = dpY)
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

        // 3. SPKLU Pins
        nearbySpklu.take(20).forEach { s ->
            val pos = geoToScreen(GeoPointX(s.lat, s.lng))
            if (pos.x in -40f..(widthPx + 40f) && pos.y in -40f..(heightPx + 40f)) {
                val dpX = with(density) { pos.x.toDp() - 18.dp }
                val dpY = with(density) { pos.y.toDp() - 18.dp }
                Surface(
                    modifier = Modifier
                        .offset(x = dpX, y = dpY)
                        .size(36.dp)
                        .clickable { onSpkluClick(s) },
                    shape = CircleShape,
                    color = c.surfaceCard,
                    border = BorderStroke(2.dp, c.regen)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.EvStation,
                            contentDescription = s.name,
                            tint = c.regen,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // 4. Field Note Pins
        fieldNotes.forEach { n ->
            val pos = geoToScreen(GeoPointX(n.lat, n.lng))
            if (pos.x in -40f..(widthPx + 40f) && pos.y in -40f..(heightPx + 40f)) {
                val dpX = with(density) { pos.x.toDp() - 14.dp }
                val dpY = with(density) { pos.y.toDp() - 14.dp }
                Box(
                    modifier = Modifier
                        .offset(x = dpX, y = dpY)
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

        // 5. Planned Stops
        plannedStops.forEachIndexed { idx, stop ->
            val pos = geoToScreen(GeoPointX(stop.lat, stop.lng))
            if (pos.x in -40f..(widthPx + 40f) && pos.y in -40f..(heightPx + 40f)) {
                val dpX = with(density) { pos.x.toDp() - 18.dp }
                val dpY = with(density) { pos.y.toDp() - 18.dp }
                Box(
                    modifier = Modifier
                        .offset(x = dpX, y = dpY)
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

        // 6. Rider Marker
        val riderGeo = riderLocation ?: center
        val rPos = geoToScreen(riderGeo)
        val rDpX = with(density) { rPos.x.toDp() - 22.dp }
        val rDpY = with(density) { rPos.y.toDp() - 22.dp }
        Box(
            modifier = Modifier
                .offset(x = rDpX, y = rDpY)
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

private fun tan(x: Double): Double = kotlin.math.tan(x)
