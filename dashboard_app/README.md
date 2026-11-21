# Dashboard App (Flutter 3.38.5 — AndroidIDE Lightweight Edition)

Proyek Flutter lengkap untuk dashboard motor listrik (*EV Cockpit, Dual-Battery BMS Diagnostics, Smart Navigation, MID AT+MAPS Streaming & Motovlog HUD*) yang dioptimalkan khusus agar **ringan dan stabil dicompile di AndroidIDE HP**.

## Konfigurasi Ringan AndroidIDE
- **Project Name**: `Dashboard App` (`dashboard_app`)
- **Organization**: `com.dashboard.app`
- **Application ID / Namespace**: `com.dashboard.app.dashboard_app`
- **Target Platform**: Android (`minSdk 24`, `compileSdk 34`, `targetSdk 34`, **tanpa NDK / tanpa Google Play Services**)
- **Target Flutter Version**: `3.38.5` (AGP `8.5.2`, Kotlin `2.1.20`)
- **RAM Gradle**: Dibatasi aman (`-Xmx1536m`, `kotlin -Xmx512m`, `workers.max=2`) di `android/gradle.properties` agar AndroidIDE tidak kehabisan RAM (*OOM kill*).

## Cara Update / Build di AndroidIDE
1. Salin isi folder `dashboard_app` ini ke proyek AndroidIDE Anda.
2. **PENTING (Jika menimpa folder proyek lama):**
   - Pastikan tidak ada file ganda `.gradle.kts` bersama `.gradle`: hapus `android/build.gradle.kts`, `android/settings.gradle.kts`, dan `android/app/build.gradle.kts` jika masih ada.
   - Jangan hapus `android/local.properties` milik AndroidIDE (tapi jika ada baris `ndk.dir=...` dan error NDK muncul, hapus baris `ndk.dir` atau tidak perlu karena proyek ini tidak memakai NDK sama sekali).
3. Jalankan di terminal folder `dashboard_app`:
   ```bash
   flutter clean
   flutter pub get
   flutter build apk --debug
   ```

## Arsitektur Native Bridge Ringan (Zero Heavy Native Plugins)
Seluruh dependency pubspec hanya memakai library **Pure Dart** (`provider`, `http`, `intl`, `flutter_map`, `latlong2`, `image`). Fitur perangkat keras ditangani langsung oleh kode Kotlin ringan bawaan di `android/app/src/main/kotlin/com/dashboard/app/dashboard_app/`:
- `BtClassicBridge.kt`: Bluetooth Classic SPP/RFCOMM ke Motor MID (`AT+...`, `AT+MAPS`).
- `BleGattBridge.kt`: BLE Scanner & Multi-Slot GATT untuk JK-BMS, ESP32 #1, ESP32 #2, dan Motor BLE.
- `PlatformToolsBridge.kt`: GPS `LocationManager`, `SharedPreferences`, `TextToSpeech`, `FLAG_KEEP_SCREEN_ON`, Share `Intent`, Runtime Permissions, dan Photo Picker (`ACTION_GET_CONTENT`).
- `MotovlogCameraView.kt`: `Camera2` + `MediaRecorder` `PlatformView` native (tanpa CameraX).
- `ScreenMirrorService.kt`: `MediaProjection` Foreground Service untuk screen mirror ke MID (`AT+MAPS`).
