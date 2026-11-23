package com.dashboard.app.dashboard_app.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dashboard.app.dashboard_app.model.ElevationSample
import com.dashboard.app.dashboard_app.model.HillGrade
import com.dashboard.app.dashboard_app.ui.theme.LocalCockpitColors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun CockpitCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color? = null,
    borderColor: Color? = null,
    cornerRadius: Dp = 20.dp,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = LocalCockpitColors.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        color = backgroundColor ?: c.surfaceCard,
        border = BorderStroke(1.dp, borderColor ?: c.borderSubtle),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

@Composable
fun SpeedoGauge(
    speedKmh: Double,
    maxSpeedKmh: Double = 100.0,
    powerW: Double?,
    socPct: Double?,
    accentColor: Color,
    secondaryColor: Color,
    dangerColor: Color,
    trackColor: Color,
    isRegen: Boolean = false,
    strokeWidth: Float = 14f,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = min(size.width, size.height) * 0.42f
        val startAngleDeg = 135f
        val sweepTotalDeg = 270f
        val arcTopLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2f, radius * 2f)
        val swPx = strokeWidth * density / 2f

        // Background track arc
        drawArc(
            color = trackColor,
            startAngle = startAngleDeg,
            sweepAngle = sweepTotalDeg,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = swPx, cap = StrokeCap.Round)
        )

        // Tick marks
        for (i in 0..20) {
            val frac = i / 20.0
            val angRad = (135.0 + frac * 270.0) * Math.PI / 180.0
            val isMajor = i % 4 == 0
            val rIn = radius - (if (isMajor) 22f else 16f) * (size.minDimension / 260f).coerceIn(0.45f, 1.2f)
            val rOut = radius - 10f * (size.minDimension / 260f).coerceIn(0.45f, 1.2f)
            drawLine(
                color = if (isMajor) accentColor.copy(alpha = 0.55f) else trackColor,
                start = Offset(
                    center.x + (rIn * cos(angRad)).toFloat(),
                    center.y + (rIn * sin(angRad)).toFloat()
                ),
                end = Offset(
                    center.x + (rOut * cos(angRad)).toFloat(),
                    center.y + (rOut * sin(angRad)).toFloat()
                ),
                strokeWidth = if (isMajor) 3.5f else 2f
            )
        }

        // Active speed arc
        val spdFrac = (speedKmh / maxSpeedKmh).coerceIn(0.0, 1.0).toFloat()
        if (spdFrac > 0.005f) {
            val arcColor = if (spdFrac > 0.82f) dangerColor else accentColor
            drawArc(
                color = arcColor,
                startAngle = startAngleDeg,
                sweepAngle = sweepTotalDeg * spdFrac,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = swPx, cap = StrokeCap.Round)
            )
        }

        // Inner Power / Regen ring
        val innerR = (radius - swPx * 1.9f).coerceAtLeast(8f)
        val innerTopLeft = Offset(center.x - innerR, center.y - innerR)
        val innerSize = Size(innerR * 2f, innerR * 2f)
        val innerStroke = (swPx * 0.38f).coerceAtLeast(4f)

        drawArc(
            color = trackColor.copy(alpha = 0.6f),
            startAngle = startAngleDeg,
            sweepAngle = sweepTotalDeg,
            useCenter = false,
            topLeft = innerTopLeft,
            size = innerSize,
            style = Stroke(width = innerStroke, cap = StrokeCap.Round)
        )

        if (powerW != null) {
            val pFrac = (abs(powerW) / 4500.0).coerceIn(0.0, 1.0).toFloat()
            if (pFrac > 0.005f) {
                drawArc(
                    color = if (isRegen) secondaryColor else accentColor.copy(alpha = 0.85f),
                    startAngle = startAngleDeg,
                    sweepAngle = sweepTotalDeg * pFrac,
                    useCenter = false,
                    topLeft = innerTopLeft,
                    size = innerSize,
                    style = Stroke(width = innerStroke, cap = StrokeCap.Round)
                )
            }
        }
    }
}

@Composable
fun EnergyFlowBar(
    phase: Float,
    powerW: Double,
    isRegen: Boolean,
    isCharging: Boolean,
    dischargeColor: Color,
    regenColor: Color,
    idleColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val cy = size.height / 2f
        val x1 = 22.dp.toPx()
        val xMid = size.width / 2f
        val x2 = size.width - 22.dp.toPx()
        val active = abs(powerW) > 15.0
        val lineCol = when {
            !active -> idleColor
            isRegen || isCharging -> regenColor
            else -> dischargeColor
        }

        drawLine(
            color = idleColor.copy(alpha = 0.45f),
            start = Offset(x1, cy),
            end = Offset(x2, cy),
            strokeWidth = 3.dp.toPx()
        )

        if (active) {
            val dir = if (isRegen || isCharging) -1f else 1f
            val dots = 6
            for (i in 0 until dots) {
                val rawT = ((i.toFloat() / dots) + (phase * dir)) % 1f
                val t = if (rawT < 0f) rawT + 1f else rawT
                val px = x1 + t * (x2 - x1)
                drawCircle(color = lineCol, radius = 4.5.dp.toPx(), center = Offset(px, cy))
            }
        }

        fun drawNode(x: Float, border: Color) {
            val r = 11.dp.toPx()
            drawCircle(color = border.copy(alpha = 0.2f), radius = r, center = Offset(x, cy))
            drawCircle(
                color = border,
                radius = r,
                center = Offset(x, cy),
                style = Stroke(width = 2.dp.toPx())
            )
            drawCircle(color = border, radius = 4.dp.toPx(), center = Offset(x, cy))
        }

        drawNode(x1, lineCol)
        drawNode(xMid, if (active) lineCol else idleColor)
        drawNode(x2, if (isCharging) idleColor else lineCol)
    }
}

@Composable
fun ElevationProfileChart(
    samples: List<ElevationSample>,
    trackColor: Color,
    labelColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (samples.size < 2) return@Canvas
        val minE = samples.minOf { it.elevationM }
        val maxRaw = samples.maxOf { it.elevationM }
        val maxE = if ((maxRaw - minE) < 8.0) minE + 8.0 else maxRaw

        val padL = 8.dp.toPx()
        val padR = 8.dp.toPx()
        val padT = 14.dp.toPx()
        val padB = 18.dp.toPx()
        val w = (size.width - padL - padR).coerceAtLeast(10f)
        val h = (size.height - padT - padB).coerceAtLeast(10f)

        for (g in 0..2) {
            val y = padT + (h * g / 2f)
            drawLine(
                color = trackColor,
                start = Offset(padL, y),
                end = Offset(padL + w, y),
                strokeWidth = 1.dp.toPx()
            )
        }

        fun pt(i: Int): Offset {
            val x = padL + (i.toFloat() / (samples.size - 1)) * w
            val normY = ((samples[i].elevationM - minE) / (maxE - minE)).toFloat()
            val y = padT + h - (normY * h)
            return Offset(x, y)
        }

        val fillPath = Path().apply {
            moveTo(padL, padT + h)
            for (i in samples.indices) {
                val p = pt(i)
                lineTo(p.dxOrX(p), p.y)
            }
            lineTo(padL + w, padT + h)
            close()
        }
        drawPath(path = fillPath, color = Color(0xFF00E5A3).copy(alpha = 0.12f))

        for (i in 1 until samples.size) {
            val col = when (samples[i].gradeType) {
                HillGrade.UPHILL_STEEP -> Color(0xFFEF4444)
                HillGrade.UPHILL_MILD -> Color(0xFFF59E0B)
                HillGrade.DOWNHILL -> Color(0xFF38BDF8)
                HillGrade.FLAT -> Color(0xFF00E5A3)
            }
            drawLine(
                color = col,
                start = pt(i - 1),
                end = pt(i),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor.toArgb()
            textSize = 10f * density
            typeface = Typeface.DEFAULT
        }
        drawContext.canvas.nativeCanvas.drawText(
            "${minE.roundToInt()}m",
            padL,
            padT + h + 13f * density,
            tp
        )
        val maxTxt = "Puncak ${maxRaw.roundToInt()}m"
        val tw = tp.measureText(maxTxt)
        drawContext.canvas.nativeCanvas.drawText(
            maxTxt,
            (padL + w - tw).coerceAtLeast(padL),
            10f * density,
            tp
        )
    }
}

private fun Offset.dxOrX(o: Offset) = o.x

data class ChartSeries(
    val name: String,
    val values: List<Double>,
    val color: Color,
    val fixedMin: Double? = null,
    val fixedMax: Double? = null
)

@Composable
fun MultiSeriesChart(
    series: List<ChartSeries>,
    gridColor: Color,
    fillFirstSeries: Boolean = true,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        for (g in 0..3) {
            val y = (size.height - 8.dp.toPx()) * g / 3f + 4.dp.toPx()
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx()
            )
        }

        series.forEachIndexed { sIdx, s ->
            if (s.values.size < 2) return@forEachIndexed
            val minV = s.fixedMin ?: s.values.min()
            val maxRaw = s.fixedMax ?: s.values.max()
            val span = if (abs(maxRaw - minV) < 0.001) 1.0 else (maxRaw - minV)

            val path = Path()
            val fill = Path()
            val bottomY = size.height - 4.dp.toPx()
            val usableH = (size.height - 8.dp.toPx()).coerceAtLeast(4f)

            s.values.forEachIndexed { i, v ->
                val x = (i.toFloat() / (s.values.size - 1)) * size.width
                val norm = ((v - minV) / span).coerceIn(0.0, 1.0).toFloat()
                val y = bottomY - norm * usableH
                if (i == 0) {
                    path.moveTo(x, y)
                    fill.moveTo(x, bottomY)
                    fill.lineTo(x, y)
                } else {
                    path.lineTo(x, y)
                    fill.lineTo(x, y)
                }
            }

            if (fillFirstSeries && sIdx == 0) {
                fill.lineTo(size.width, bottomY)
                fill.close()
                drawPath(path = fill, color = s.color.copy(alpha = 0.14f))
            }

            drawPath(
                path = path,
                color = s.color,
                style = Stroke(
                    width = 2.4.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}
