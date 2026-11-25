package com.dashboard.app.dashboard_app.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dashboard.app.dashboard_app.ui.components.EnergyFlowBar
import com.dashboard.app.dashboard_app.ui.components.SpeedoGauge
import com.dashboard.app.dashboard_app.ui.theme.LocalCockpitColors
import com.dashboard.app.dashboard_app.viewmodel.DashboardUiState
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun RidingCockpitScreen(
    state: DashboardUiState,
    onUpdateLayout: (String) -> Unit,
    onSetWakelock: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    val c = LocalCockpitColors.current
    val context = LocalContext.current
    val eff = state.effective
    val compact = state.settings.cockpitLayout == "compact"

    BackHandler { onClose() }

    DisposableEffect(Unit) {
        val act = context as? Activity
        if (act != null) {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            val ctrl = WindowCompat.getInsetsController(act.window, act.window.decorView)
            ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
        }
        onSetWakelock(true)
        onDispose {
            if (act != null) {
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                val ctrl = WindowCompat.getInsetsController(act.window, act.window.decorView)
                ctrl.show(WindowInsetsCompat.Type.systemBars())
            }
            if (!state.isNavigating) {
                onSetWakelock(false)
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "energyFlow")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05080D))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        // Top HUD Status Strip
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusPill(eff.modeLabel, c.accent)
            Spacer(Modifier.width(8.dp))
            if (eff.isPark == true) {
                StatusPill("PARK", c.warning)
                Spacer(Modifier.width(8.dp))
            }
            if (eff.sideStandDown) {
                StatusPill("STANDAR", c.danger)
                Spacer(Modifier.width(8.dp))
            }
            if (eff.cruiseActive) {
                StatusPill("CRUISE", c.accentSecondary)
                Spacer(Modifier.width(8.dp))
            }
            Spacer(Modifier.weight(1f))

            if (state.isNavigating && state.activeRoute != null && state.activeRoute.steps.isNotEmpty()) {
                val step = state.activeRoute.steps[state.activeStepIndex.coerceIn(0, state.activeRoute.steps.lastIndex)]
                Surface(
                    shape = CircleShape,
                    color = c.accent.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, c.accent)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Navigation, contentDescription = null, tint = c.accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${state.distToNextStepM.roundToInt()}m • ${step.instruction}",
                            color = Color.White,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Explore, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${(state.gpsState.headingDeg ?: 0.0).roundToInt()}°", color = c.textMuted)
                }
            }

            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = { onUpdateLayout(if (compact) "complete" else "compact") },
                modifier = Modifier.testTag("cockpit_layout_toggle")
            ) {
                Text(if (compact) "Ringkas" else "Lengkap")
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.testTag("close_riding_cockpit_button")
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "Keluar Mode Kokpit", tint = Color.White)
            }
        }

        // Main Landscape Split
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Large Speedometer
            Box(
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxHeight()
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                SpeedoGauge(
                    speedKmh = eff.speedKmh ?: 0.0,
                    powerW = eff.combinedPowerW,
                    socPct = eff.combinedSocPct,
                    accentColor = c.accent,
                    secondaryColor = c.accentSecondary,
                    dangerColor = c.danger,
                    trackColor = Color(0xFF1F2A38),
                    isRegen = eff.isRegen,
                    strokeWidth = 14f,
                    modifier = Modifier.size(230.dp)
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = eff.speedKmh?.roundToInt()?.toString() ?: "—",
                        fontSize = 62.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text("KM/H", fontSize = 12.sp, color = c.textMuted, letterSpacing = 2.sp)
                    if (!compact && eff.rpm != null) {
                        Spacer(Modifier.height(2.dp))
                        Text("${eff.rpm} RPM", fontSize = 12.sp, color = c.accentSecondary)
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            // Right Energy Flow + Key Metrics
            Column(
                modifier = Modifier
                    .weight(1.3f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center
            ) {
                EnergyFlowBar(
                    phase = phase,
                    powerW = eff.combinedPowerW ?: 0.0,
                    isRegen = eff.isRegen,
                    isCharging = eff.isCharging,
                    dischargeColor = c.accent,
                    regenColor = c.regen,
                    idleColor = c.textMuted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("BATERAI", fontSize = 10.sp, color = c.textMuted)
                    Text(
                        "${eff.combinedPowerW?.roundToInt()?.toString() ?: "—"} W",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (eff.isRegen) c.regen else c.accent
                    )
                    Text("MOTOR BLDC", fontSize = 10.sp, color = c.textMuted)
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricTile(
                        title = "BATERAI",
                        value = "${eff.combinedSocPct?.roundToInt()?.toString() ?: "—"}%",
                        sub = "Sisa ${eff.rangeKm?.let { String.format(Locale.US, "%.1f", it) } ?: "—"} km",
                        color = if ((eff.combinedSocPct ?: 50.0) < 20.0) c.danger else c.accent,
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        title = "VOLT / ARUS",
                        value = "${eff.avgVoltageV?.let { String.format(Locale.US, "%.1f", it) } ?: "—"} V",
                        sub = "${eff.combinedCurrentA?.let { String.format(Locale.US, "%.1f", it) } ?: "—"} A",
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        title = "TRIP",
                        value = "${String.format(Locale.US, "%.1f", state.liveTrip.distanceKm)} km",
                        sub = "${state.liveTrip.energyWh.roundToInt()} Wh",
                        color = c.accentSecondary,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (!compact) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Motor: ${state.motorData.tempMotorC?.roundToInt()?.toString() ?: "—"}°C • Inverter: ${state.motorData.tempInverterC?.roundToInt()?.toString() ?: "—"}°C",
                            fontSize = 12.sp,
                            color = c.textMuted
                        )
                        Text(
                            "ODO: ${eff.odoKm?.roundToInt()?.toString() ?: "—"} km",
                            fontSize = 12.sp,
                            color = c.textMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, color: Color) {
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.2f),
        border = BorderStroke(1.dp, color)
    ) {
        Text(
            label,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun MetricTile(
    title: String,
    value: String,
    sub: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF101722),
        border = BorderStroke(1.dp, Color(0xFF1F2B3B))
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(title, fontSize = 10.sp, color = Color(0xFF8A9BAE))
            Spacer(Modifier.height(2.dp))
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
            Text(sub, fontSize = 11.sp, color = Color(0xFF8A9BAE), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
