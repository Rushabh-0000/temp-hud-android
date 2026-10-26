# Temp HUD

Compact MangoHud-inspired Android temperature overlay for Android 11+, written in Kotlin with Jetpack Compose Material 3.

## Low-memory background mode
- The floating overlay service runs in a dedicated Android process (`:overlay`) and uses a background-priority sensor thread. The UI process is not needed to keep the HUD visible.
- When the HUD is active and you leave the app, the settings task is removed; the OS can release the Compose Activity process while the separate overlay process remains.
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
- The overlay remains a native Android View; the full Compose UI is separate and does not run as a background screen.

## Privacy
No Internet permission, analytics, updater UI, APK download/install code, or update receiver. APKs are installed manually.

## One workflow and release signing
The only workflow is `.github/workflows/android.yml`. It builds on pushes and has a manual option to publish a signed release using persistent repository secrets.

Generate the persistent keystore plus a copy/paste text file from a Linux/macOS/JDK workspace with:

```bash
bash scripts/generate-signing-key.sh
```

The command creates `temp-hud-release.p12` and `github-release-secrets.txt` in the workspace root. Copy each value into the matching repository Action secret:
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The generated key and secrets file are ignored by Git. Back up the keystore securely; every update must use the same signing key. Never post either file or its contents publicly. Then run **Temp HUD Build and Release** manually with **Publish release** enabled.

## Toolchain
AGP 9.3.1, Kotlin 2.4.21, Gradle 9.7.0, JDK 25, compile/target SDK 36, min SDK 30, Compose BOM 2026.06.00. Release code/resources are minified and shrunk.
