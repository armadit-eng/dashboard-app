package com.dashboard.app.dashboard_app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

data class AccentPalette(
    val id: String,
    val name: String,
    val primary: Color,
    val secondary: Color
)

val kAccentPalettes = listOf(
    AccentPalette("emerald", "Emerald Volt", Color(0xFF00E5A3), Color(0xFF38BDF8)),
    AccentPalette("cyan", "Cyber Cyan", Color(0xFF06B6D4), Color(0xFF6366F1)),
    AccentPalette("amber", "Amber Sport", Color(0xFFF59E0B), Color(0xFFFB7185)),
    AccentPalette("crimson", "Crimson Track", Color(0xFFF43F5E), Color(0xFFFB923C)),
    AccentPalette("cobalt", "Cobalt Touring", Color(0xFF3B82F6), Color(0xFF22D3EE)),
    AccentPalette("violet", "Violet Flux", Color(0xFFA855F7), Color(0xFFEC4899)),
    AccentPalette("lime", "Lime Eco", Color(0xFF84CC16), Color(0xFF10B981)),
    AccentPalette("gold", "Gold Edition", Color(0xFFEAB308), Color(0xFF14B8A6))
)

data class GarageLivery(
    val id: String,
    val name: String,
    val bodyColor: Color,
    val trimColor: Color
)

val kGarageLiveries = listOf(
    GarageLivery("stealth_obsidian", "Stealth Obsidian", Color(0xFF1E293B), Color(0xFF00E5A3)),
    GarageLivery("arctic_pearl", "Arctic Pearl", Color(0xFFE2E8F0), Color(0xFF0284C7)),
    GarageLivery("crimson_gt", "Crimson GT", Color(0xFFDC2626), Color(0xFFFACC15)),
    GarageLivery("forest_matte", "Matte Olive", Color(0xFF365314), Color(0xFFA3E635)),
    GarageLivery("electric_cobalt", "Electric Cobalt", Color(0xFF1D4ED8), Color(0xFF22D3EE)),
    GarageLivery("sunset_copper", "Sunset Copper", Color(0xFFC2410C), Color(0xFFFDE047))
)

@Immutable
data class CockpitColors(
    val background: Color = Color(0xFF0A0E14),
    val surfaceCard: Color = Color(0xFF121822),
    val surfaceElevated: Color = Color(0xFF1A2331),
    val borderSubtle: Color = Color(0xFF253244),
    val accent: Color = Color(0xFF00E5A3),
    val accentSecondary: Color = Color(0xFF38BDF8),
    val warning: Color = Color(0xFFF59E0B),
    val danger: Color = Color(0xFFEF4444),
    val regen: Color = Color(0xFF38BDF8),
    val textMuted: Color = Color(0xFF8A9BAE),
    val isDark: Boolean = true
)

val LocalCockpitColors = staticCompositionLocalOf { CockpitColors() }

fun paletteById(id: String): AccentPalette =
    kAccentPalettes.firstOrNull { it.id == id } ?: kAccentPalettes.first()

private val CockpitTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp,
        letterSpacing = (-1.5).sp,
        fontFeatureSettings = "tnum"
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        letterSpacing = (-0.8).sp,
        fontFeatureSettings = "tnum"
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp
    )
)

@Composable
fun DashboardAppTheme(
    isDark: Boolean = true,
    accentId: String = "emerald",
    content: @Composable () -> Unit
) {
    val pal = paletteById(accentId)
    val bg = if (isDark) Color(0xFF0A0E14) else Color(0xFFF3F6FA)
    val card = if (isDark) Color(0xFF121822) else Color.White
    val elevated = if (isDark) Color(0xFF1A2331) else Color(0xFFE9EFF6)
    val border = if (isDark) Color(0xFF253244) else Color(0xFFD5DFEC)
    val muted = if (isDark) Color(0xFF8A9BAE) else Color(0xFF5B6B7C)

    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = pal.primary,
            onPrimary = Color(0xFF041418),
            secondary = pal.secondary,
            onSecondary = Color(0xFF041418),
            background = bg,
            onBackground = Color(0xFFF1F5F9),
            surface = bg,
            onSurface = Color(0xFFF1F5F9),
            surfaceVariant = card,
            onSurfaceVariant = muted,
            outline = border,
            error = Color(0xFFEF4444)
        )
    } else {
        lightColorScheme(
            primary = pal.primary,
            onPrimary = Color.Black,
            secondary = pal.secondary,
            onSecondary = Color.Black,
            background = bg,
            onBackground = Color(0xFF0F172A),
            surface = bg,
            onSurface = Color(0xFF0F172A),
            surfaceVariant = card,
            onSurfaceVariant = muted,
            outline = border,
            error = Color(0xFFEF4444)
        )
    }

    val cockpit = CockpitColors(
        background = bg,
        surfaceCard = card,
        surfaceElevated = elevated,
        borderSubtle = border,
        accent = pal.primary,
        accentSecondary = pal.secondary,
        warning = Color(0xFFF59E0B),
        danger = Color(0xFFEF4444),
        regen = Color(0xFF38BDF8),
        textMuted = muted,
        isDark = isDark
    )

    CompositionLocalProvider(LocalCockpitColors provides cockpit) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = CockpitTypography,
            content = content
        )
    }
}
