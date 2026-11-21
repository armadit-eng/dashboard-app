import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import 'app.dart';
import 'state/dashboard_controller.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);

  final controller = DashboardController();
  await controller.initialize();

  runApp(
    ChangeNotifierProvider<DashboardController>.value(
      value: controller,
      child: const DashboardApp(),
    ),
  );
}
