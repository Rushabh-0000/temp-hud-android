# Temp HUD

Compact MangoHud-inspired Android temperature overlay for Android 11+, written in Kotlin with Jetpack Compose Material 3.

## Resource use and live readings
- The floating overlay is a native Android View hosted by a foreground service. The Compose settings activity no longer keeps polling once it pauses; Android can release its activity UI while the small overlay service remains active.
- The service samples thermal files on a background-priority thread at the chosen interval (default 1.50 seconds). The app preview consumes the same in-process sample while the overlay is running, so the two displays do not compete with separate readers or show values from different sample times.
- The app samples sensors itself only while it is resumed and the overlay service is stopped. Browsing all thermal zones triggers a temporary catalog read only while that screen is open.
- No network permission, analytics, updater UI, APK download/install code, or updater receiver. The overlay only polls the selected CPU/GPU and custom sensor sources unless the user opens the full sensor picker.

## Overlay features
- Default rows are overall CPU and GPU temperatures; CPU 0-7 and any readable thermal-zone sensor can be selected independently.
- User-defined rows support a custom label and any available thermal sensor.
- Border-free, text-only HUD by default. White labels are fixed; temperature numbers use a green-to-red heat scale. Text brightness, scale, refresh interval, optional background opacity, position, and touch-through lock are configurable.
- Refresh interval: 0.50–5.00 seconds in 0.05-second steps, default 1.50 seconds.
- Overall CPU temperature averages distinct mapped per-core sources when possible, otherwise deduplicated CPU-labelled zones. Overall GPU temperature averages distinct GPU-labelled zones. Qualcomm paired `-usr`/`-step` sensors are deduplicated, preferring `-usr`.
- If Android/OEM firmware does not expose a readable sensor, the app shows N/A rather than fabricating a reading.

## One GitHub Actions workflow
The single workflow `.github/workflows/android.yml` builds an optimized APK on pushes and supports manually publishing a signed GitHub Release.

For a persistent release signature, add these repository **Settings → Secrets and variables → Actions** secrets:
- `ANDROID_KEYSTORE_BASE64` — Base64-encoded keystore file
- `ANDROID_KEYSTORE_PASSWORD` — keystore password
- `ANDROID_KEY_ALIAS` — key alias
- `ANDROID_KEY_PASSWORD` — key password

On push, CI creates a disposable key only if no signing secrets exist, and uploads a short-lived test APK. To publish a proper release, run the workflow manually with **Publish release** enabled; all four secrets are required and the saved keystore signs the release. Keep the keystore backed up securely and never commit it.

## Build
AGP 9.3.1, Kotlin 2.4.21, Gradle 9.7.0, JDK 25, compile/target SDK 36, min SDK 30, Compose BOM 2026.06.00. Release code and resources are minified/shrunk.
