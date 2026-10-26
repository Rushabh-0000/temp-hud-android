# Temp HUD

Compact MangoHud-inspired Android temperature overlay for Android 11+, written in Kotlin using Android's built-in Material-themed Views to keep the APK lean.

## Low-memory background mode
- The floating overlay service runs in a dedicated Android process (`:overlay`) and uses a background-priority sensor thread. The UI process is not needed to keep the HUD visible.
- When the HUD is active and you leave the app, the settings task is removed; the OS can release the settings Activity process while the separate overlay process remains.
- Settings are stored in an atomic app-private JSON file so UI and overlay processes read the same settings reliably. The app samples sensors only while its screen is visible; it stops polling and collecting when paused.
- Overlay and app can have different sample moments while the settings screen is open, but the HUD does not wait for the app UI. Thermal drivers may refresh values less frequently than the chosen interval.

## Sensor selection
- Overall CPU and GPU temperatures appear first. CPU 0–7 can be selected independently.
- CPU core edit includes each readable Qualcomm `usr` and `step` thermal-zone variant separately. GPU cores/sources lists every readable GPU-labelled zone and lets you select `usr` and `step` variants independently where exposed.
- These are thermal-zone readings exposed by the phone firmware, not guaranteed physical GPU-core sensors. If firmware exposes no suitable sensor, the app shows N/A.
- Any readable thermal-zone sensor can be selected from Browse, and custom rows can pair a custom label with any selected temperature sensor.

## Appearance
- Border-free, text-only HUD by default; no header or load/clock rows. White labels, heat-coloured values, adjustable text brightness, size, refresh interval, optional background opacity, saved position and touch-through lock.
- Refresh interval ranges from 0.50–5.00 seconds in 0.05-second steps; default is 1.50 seconds.
- The overlay and settings screen use native Android Views; Compose and AndroidX UI dependencies are removed to keep the APK smaller.

## Privacy
No Internet permission, analytics, updater UI, APK download/install code, or update receiver. APKs are installed manually.

## One workflow and release signing
The only workflow is `.github/workflows/android.yml`. It builds on pushes and has a manual option to publish a signed release using persistent repository secrets.

Use the persistent keystore you already configured. Copy each value into the matching repository Action secret:
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The key-generation helper is not in the repository. Back up your keystore securely; every update must use the same signing key. Never commit or post the keystore, passwords or Base64 value. Then run **Temp HUD Build and Release** manually with **Publish release** enabled.

## Toolchain
AGP 9.3.1, Kotlin 2.4.21, Gradle 9.7.0, JDK 25, compile/target SDK 36, min SDK 30. There are no Compose or AndroidX UI dependencies. The launcher icon is a vector adaptive icon. Release code/resources are minified and shrunk with R8.
