# Dashboard App (EV Motorcycle Smart Telemetry & Cockpit)

Native Android (Kotlin + Jetpack Compose + Material 3) companion application for electric motorcycles with:

- **Dual-Battery Telemetry & Diagnostics**: Motor MID (`AT+` over Classic Bluetooth SPP / BLE GATT fallback), JK-BMS (300-byte binary frame over BLE), ESP32 #1 (Relay Kontak, Side-Stand, Cruise, Reverse, LED Matrix, DFPlayer, Nav Voice), and ESP32 #2 (Battery 2 CAN/SIM + cell array).
- **1 Hz Live Integrator & Riding Score**: Weighted dual-battery SOC/SOH, blended Wh/km range & charge ETA estimator, live 240-point telemetry charts, DTC fault analyzer, and Dev Telemetry Simulator (`DEV-SIM MOTOR`).
- **Smart EV Navigation & Offline Tile Maps**: OSRM turn-by-turn routing, Open-Meteo elevation & weather, route physics energy estimator, Overpass + OpenChargeMap SPKLU finder, disk-cached Slippy tile renderer with bulk offline area download (`Cockpit Gelap` z10–z16), Field Notes (+ GPX export), and `AT+MAPS` / MediaProjection screen mirror streaming to the motorcycle MID.
- **Fullscreen Riding Cockpit & Motovlog Camera HUD**: Landscape immersive Speedometer & animated Energy Flow HUD, plus Camera2 + MediaRecorder 720p MP4 Motovlog recorder with live telemetry HUD overlay.
