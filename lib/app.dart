import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:provider/provider.dart';

import 'screens/diagnosis_screen.dart';
import 'screens/garage_account_screen.dart';
import 'screens/home_cockpit_screen.dart';
import 'screens/navigation_screen.dart';
import 'screens/stats_screen.dart';
import 'state/dashboard_controller.dart';
import 'theme/app_theme.dart';

class DashboardApp extends StatelessWidget {
  const DashboardApp({super.key});

  @override
  Widget build(BuildContext context) {
    final ctrl = context.watch<DashboardController>();
    return MaterialApp(
      title: 'Dashboard App',
      debugShowCheckedModeBanner: false,
      theme: AppTheme.buildTheme(
        isDark: ctrl.settings.isDarkTheme,
        accentId: ctrl.settings.accentId,
      ),
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      supportedLocales: const [
        Locale('id', 'ID'),
        Locale('en', 'US'),
      ],
      home: const _MainShell(),
    );
  }
}

class _MainShell extends StatefulWidget {
  const _MainShell();

  @override
  State<_MainShell> createState() => _MainShellState();
}

class _MainShellState extends State<_MainShell> {
  int _tabIndex = 0;

  @override
  Widget build(BuildContext context) {
    final c = context.cockpit;
    final wide = MediaQuery.of(context).size.width >= 720;

    final pages = [
      HomeCockpitScreen(onNavigateTab: (idx) => setState(() => _tabIndex = idx)),
      const DiagnosisScreen(),
      const NavigationScreen(),
      const StatsScreen(),
      const GarageAccountScreen(),
    ];

    const items = [
      (icon: Icons.dashboard_rounded, label: 'Kokpit'),
      (icon: Icons.monitor_heart_rounded, label: 'Diagnostik'),
      (icon: Icons.explore_rounded, label: 'Navigasi'),
      (icon: Icons.analytics_rounded, label: 'Statistik'),
      (icon: Icons.garage_rounded, label: 'Garasi'),
    ];

    if (wide) {
      return Scaffold(
        body: SafeArea(
          child: Row(
            children: [
              NavigationRail(
                selectedIndex: _tabIndex,
                onDestinationSelected: (i) => setState(() => _tabIndex = i),
                labelType: NavigationRailLabelType.all,
                destinations: items
                    .map((it) => NavigationRailDestination(
                          icon: Icon(it.icon),
                          label: Text(it.label),
                        ))
                    .toList(),
              ),
              const VerticalDivider(width: 1),
              Expanded(child: IndexedStack(index: _tabIndex, children: pages)),
            ],
          ),
        ),
      );
    }

    return Scaffold(
      extendBody: true,
      body: SafeArea(
        bottom: false,
        child: IndexedStack(index: _tabIndex, children: pages),
      ),
      bottomNavigationBar: Padding(
        padding: EdgeInsets.fromLTRB(
            14, 0, 14, MediaQuery.of(context).padding.bottom + 10),
        child: Container(
          height: 64,
          decoration: BoxDecoration(
            color: c.surfaceCard.withOpacity(0.95),
            borderRadius: BorderRadius.circular(24),
            border: Border.all(color: c.borderSubtle),
            boxShadow: [
              BoxShadow(
                color: Colors.black.withOpacity(0.24),
                blurRadius: 16,
                offset: const Offset(0, 6),
              ),
            ],
          ),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceAround,
            children: items.asMap().entries.map((e) {
              final active = _tabIndex == e.key;
              return InkWell(
                onTap: () => setState(() => _tabIndex = e.key),
                borderRadius: BorderRadius.circular(18),
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 200),
                  padding:
                      const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                  decoration: BoxDecoration(
                    color: active ? c.accent.withOpacity(0.16) : null,
                    borderRadius: BorderRadius.circular(18),
                  ),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(
                        e.value.icon,
                        size: 21,
                        color: active ? c.accent : c.textMuted,
                      ),
                      const SizedBox(height: 2),
                      Text(
                        e.value.label,
                        style: TextStyle(
                          fontSize: 10.5,
                          fontWeight: active ? FontWeight.w700 : FontWeight.w500,
                          color: active ? c.accent : c.textMuted,
                        ),
                      ),
                    ],
                  ),
                ),
              );
            }).toList(),
          ),
        ),
      ),
    );
  }
}
