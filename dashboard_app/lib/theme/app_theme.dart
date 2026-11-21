import 'package:flutter/material.dart';

class AccentPalette {
  final String id;
  final String name;
  final Color primary;
  final Color secondary;

  const AccentPalette({
    required this.id,
    required this.name,
    required this.primary,
    required this.secondary,
  });
}

const List<AccentPalette> kAccentPalettes = [
  AccentPalette(
    id: 'emerald',
    name: 'Emerald Volt',
    primary: Color(0xFF00E5A3),
    secondary: Color(0xFF38BDF8),
  ),
  AccentPalette(
    id: 'cyan',
    name: 'Cyber Cyan',
    primary: Color(0xFF06B6D4),
    secondary: Color(0xFF6366F1),
  ),
  AccentPalette(
    id: 'amber',
    name: 'Amber Sport',
    primary: Color(0xFFF59E0B),
    secondary: Color(0xFFFB7185),
  ),
  AccentPalette(
    id: 'crimson',
    name: 'Crimson Track',
    primary: Color(0xFFF43F5E),
    secondary: Color(0xFFFB923C),
  ),
  AccentPalette(
    id: 'cobalt',
    name: 'Cobalt Touring',
    primary: Color(0xFF3B82F6),
    secondary: Color(0xFF22D3EE),
  ),
  AccentPalette(
    id: 'violet',
    name: 'Violet Flux',
    primary: Color(0xFFA855F7),
    secondary: Color(0xFFEC4899),
  ),
  AccentPalette(
    id: 'lime',
    name: 'Lime Eco',
    primary: Color(0xFF84CC16),
    secondary: Color(0xFF10B981),
  ),
  AccentPalette(
    id: 'gold',
    name: 'Gold Edition',
    primary: Color(0xFFEAB308),
    secondary: Color(0xFF14B8A6),
  ),
];

class GarageLivery {
  final String id;
  final String name;
  final Color bodyColor;
  final Color trimColor;

  const GarageLivery({
    required this.id,
    required this.name,
    required this.bodyColor,
    required this.trimColor,
  });
}

const List<GarageLivery> kGarageLiveries = [
  GarageLivery(
    id: 'stealth_obsidian',
    name: 'Stealth Obsidian',
    bodyColor: Color(0xFF1E293B),
    trimColor: Color(0xFF00E5A3),
  ),
  GarageLivery(
    id: 'arctic_pearl',
    name: 'Arctic Pearl',
    bodyColor: Color(0xFFE2E8F0),
    trimColor: Color(0xFF0284C7),
  ),
  GarageLivery(
    id: 'crimson_gt',
    name: 'Crimson GT',
    bodyColor: Color(0xFFDC2626),
    trimColor: Color(0xFFFACC15),
  ),
  GarageLivery(
    id: 'forest_matte',
    name: 'Matte Olive',
    bodyColor: Color(0xFF365314),
    trimColor: Color(0xFFA3E635),
  ),
  GarageLivery(
    id: 'electric_cobalt',
    name: 'Electric Cobalt',
    bodyColor: Color(0xFF1D4ED8),
    trimColor: Color(0xFF22D3EE),
  ),
  GarageLivery(
    id: 'sunset_copper',
    name: 'Sunset Copper',
    bodyColor: Color(0xFFC2410C),
    trimColor: Color(0xFFFDE047),
  ),
];

class CockpitColors extends ThemeExtension<CockpitColors> {
  final Color surfaceCard;
  final Color surfaceElevated;
  final Color borderSubtle;
  final Color accent;
  final Color accentSecondary;
  final Color warning;
  final Color danger;
  final Color regen;
  final Color textMuted;

  const CockpitColors({
    required this.surfaceCard,
    required this.surfaceElevated,
    required this.borderSubtle,
    required this.accent,
    required this.accentSecondary,
    required this.warning,
    required this.danger,
    required this.regen,
    required this.textMuted,
  });

  @override
  CockpitColors copyWith({
    Color? surfaceCard,
    Color? surfaceElevated,
    Color? borderSubtle,
    Color? accent,
    Color? accentSecondary,
    Color? warning,
    Color? danger,
    Color? regen,
    Color? textMuted,
  }) {
    return CockpitColors(
      surfaceCard: surfaceCard ?? this.surfaceCard,
      surfaceElevated: surfaceElevated ?? this.surfaceElevated,
      borderSubtle: borderSubtle ?? this.borderSubtle,
      accent: accent ?? this.accent,
      accentSecondary: accentSecondary ?? this.accentSecondary,
      warning: warning ?? this.warning,
      danger: danger ?? this.danger,
      regen: regen ?? this.regen,
      textMuted: textMuted ?? this.textMuted,
    );
  }

  @override
  CockpitColors lerp(ThemeExtension<CockpitColors>? other, double t) {
    if (other is! CockpitColors) return this;
    return CockpitColors(
      surfaceCard: Color.lerp(surfaceCard, other.surfaceCard, t)!,
      surfaceElevated: Color.lerp(surfaceElevated, other.surfaceElevated, t)!,
      borderSubtle: Color.lerp(borderSubtle, other.borderSubtle, t)!,
      accent: Color.lerp(accent, other.accent, t)!,
      accentSecondary: Color.lerp(accentSecondary, other.accentSecondary, t)!,
      warning: Color.lerp(warning, other.warning, t)!,
      danger: Color.lerp(danger, other.danger, t)!,
      regen: Color.lerp(regen, other.regen, t)!,
      textMuted: Color.lerp(textMuted, other.textMuted, t)!,
    );
  }
}

class AppTheme {
  static AccentPalette paletteById(String id) => kAccentPalettes.firstWhere(
        (p) => p.id == id,
        orElse: () => kAccentPalettes.first,
      );

  static ThemeData buildTheme({
    required bool isDark,
    required String accentId,
  }) {
    final pal = paletteById(accentId);
    final bg = isDark ? const Color(0xFF0A0E14) : const Color(0xFFF3F6FA);
    final card = isDark ? const Color(0xFF121822) : Colors.white;
    final elevated = isDark ? const Color(0xFF1A2331) : const Color(0xFFE9EFF6);
    final border = isDark ? const Color(0xFF253244) : const Color(0xFFD5DFEC);
    final muted = isDark ? const Color(0xFF8A9BAE) : const Color(0xFF5B6B7C);

    final scheme = ColorScheme.fromSeed(
      seedColor: pal.primary,
      brightness: isDark ? Brightness.dark : Brightness.light,
      primary: pal.primary,
      secondary: pal.secondary,
      surface: bg,
      error: const Color(0xFFEF4444),
    );

    final baseText =
        isDark ? ThemeData.dark().textTheme : ThemeData.light().textTheme;
    final textTheme = baseText.copyWith(
      displayLarge: TextStyle(
        fontSize: 48,
        fontWeight: FontWeight.w800,
        letterSpacing: -1.5,
        color: scheme.onSurface,
        fontFeatures: const [FontFeature.tabularFigures()],
      ),
      displayMedium: TextStyle(
        fontSize: 32,
        fontWeight: FontWeight.w700,
        letterSpacing: -0.8,
        color: scheme.onSurface,
        fontFeatures: const [FontFeature.tabularFigures()],
      ),
      headlineSmall: TextStyle(
        fontSize: 20,
        fontWeight: FontWeight.w700,
        color: scheme.onSurface,
      ),
      titleMedium: TextStyle(
        fontSize: 16,
        fontWeight: FontWeight.w700,
        color: scheme.onSurface,
      ),
      labelMedium: TextStyle(
        fontFamily: 'monospace',
        fontSize: 12,
        fontWeight: FontWeight.w600,
        color: muted,
      ),
    );

    return ThemeData(
      useMaterial3: true,
      brightness: isDark ? Brightness.dark : Brightness.light,
      colorScheme: scheme,
      scaffoldBackgroundColor: bg,
      textTheme: textTheme,
      cardTheme: CardThemeData(
        color: card,
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(20),
          side: BorderSide(color: border, width: 1),
        ),
        margin: EdgeInsets.zero,
      ),
      appBarTheme: AppBarTheme(
        backgroundColor: bg,
        foregroundColor: scheme.onSurface,
        elevation: 0,
        centerTitle: false,
      ),
      extensions: [
        CockpitColors(
          surfaceCard: card,
          surfaceElevated: elevated,
          borderSubtle: border,
          accent: pal.primary,
          accentSecondary: pal.secondary,
          warning: const Color(0xFFF59E0B),
          danger: const Color(0xFFEF4444),
          regen: const Color(0xFF38BDF8),
          textMuted: muted,
        ),
      ],
    );
  }
}

extension ThemeCockpitExt on BuildContext {
  CockpitColors get cockpit =>
      Theme.of(this).extension<CockpitColors>() ??
      const CockpitColors(
        surfaceCard: Color(0xFF121822),
        surfaceElevated: Color(0xFF1A2331),
        borderSubtle: Color(0xFF253244),
        accent: Color(0xFF00E5A3),
        accentSecondary: Color(0xFF38BDF8),
        warning: Color(0xFFF59E0B),
        danger: Color(0xFFEF4444),
        regen: Color(0xFF38BDF8),
        textMuted: Color(0xFF8A9BAE),
      );
}
