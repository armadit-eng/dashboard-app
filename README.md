# Dashboard App (Flutter 3.38.5 — AndroidIDE Ready)

Proyek Flutter lengkap untuk dashboard motor listrik (*EV Cockpit, Dual-Battery BMS Diagnostics, Smart Navigation, MID AT+MAPS Streaming & Motovlog HUD*).

## Konfigurasi Proyek (Sesuai AndroidIDE)
- **Project Name**: `Dashboard App` (`dashboard_app`)
- **Organization**: `com.dashboard.app`
- **Application ID / Namespace**: `com.dashboard.app.dashboard_app`
- **Target Platform**: Android (`minSdk 24`, `compileSdk 35`)
- **Target Flutter Version**: `3.38.5`

## Cara Membuka & Build di AndroidIDE
1. Salin folder `dashboard_app` ini ke direktori proyek AndroidIDE Anda (misalnya `/storage/emulated/0/AndroidIDEProjects/dashboard_app` atau folder workspace AndroidIDE).
2. Buka terminal di dalam folder `dashboard_app`, lalu jalankan:
   ```bash
   flutter pub get
   ```
3. Build atau Run langsung melalui tombol **Run** di AndroidIDE atau perintah:
   ```bash
   flutter build apk --release
   ```

## Catatan Arsitektur Native Bridge
- **Bluetooth Classic (SPP/RFCOMM)** untuk Motor MID diimplementasikan langsung via native Kotlin (`BtClassicBridge.kt`) menggunakan `MethodChannel` + `EventChannel` (`com.dashboard.app/bt_classic`) agar 100% kompatibel dengan Flutter 3.38.5 dan AGP 8+ tanpa kendala package pihak ketiga yang usang.
- **BLE GATT** untuk JK-BMS (`0xFFE0/FFE1/FFF2`), ESP32 #1, dan ESP32 #2 (`Nordic UART 6E400001`) menggunakan `flutter_blue_plus`.
- **Screen Mirror -> MID (`AT+MAPS`)** menggunakan `MediaProjection` Foreground Service native (`ScreenMirrorService.kt`) dan juga mendukung frame canvas Flutter langsung tanpa izin tangkap layar.
