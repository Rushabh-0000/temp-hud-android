# Temp HUD

Current development version: **0.7.7**.

Compact MangoHud-inspired Android temperature overlay for Android 11+, written in Kotlin using Android's built-in Material-themed Views to keep the APK lean.

## Low-memory background mode
- The overlay service uses a background-priority sensor thread in the app's single process. Leaving the app finishes/removes the settings Activity task and releases its view tree while the foreground service remains. This avoids the extra ART/runtime overhead from running a second app process.
- When the HUD is active and you leave the app, the settings task is removed and the Activity view tree can be collected while the foreground service continues in the same process.
- Settings use app-private SharedPreferences only; the unused duplicate atomic-JSON/PID-file store was removed. The app samples sensors only while its screen is visible; it stops polling and collecting when paused.
- Only one sensor sampler runs at a time: the overlay service while active, or the visible settings screen while stopped. Opening a sensor picker asks the active sampler to include all thermal zones on its next scheduled read; it does not launch another concurrent sensor scan.

## Sensor selection
- Overall CPU and GPU temperatures appear first. CPU 0–7 can be selected independently.
- CPU cores and GPU sources show Use and Step checkboxes side by side for each readable paired zone. Overlay labels remain clean (`CPU 0`, `GPU 0`) without usr/step. CPU and GPU overall averages each have a mutually exclusive Use/Step selector; Use is the default.
- These are thermal-zone readings exposed by the phone firmware, not guaranteed physical GPU-core sensors. If firmware exposes no suitable sensor, the app shows N/A.
- Any readable thermal-zone sensor can be selected from Browse, and custom rows can pair a custom label with any selected temperature sensor.

## UI and floating-window fixes in 0.7.7
- Replaced the custom-measured divider with a normal one-pixel Android View. The previous divider matched the separator colour but could expand into a large blank block in floating-window mode. The ScrollView child now uses explicit ScrollView.LayoutParams with WRAP_CONTENT and reasserts natural content measurement after multi-window/configuration changes.
- The settings header uses the app launcher icon; live readings and CPU/GPU average-source selection are grouped into distinct compact cards. Buttons provide touch ripple feedback. The interface remains built with native Android Views to avoid adding Compose runtime size.

## Layout, sensor and low-allocation fixes in 0.7.5
- The ScrollView uses a wrap-content child without viewport stretching, including in Android floating-window mode, to avoid blank fill areas. Snapshot broadcasts are ignored while the Activity is paused. Thermal-zone IDs stay in the catalog even when a particular poll temporarily returns N/A, so the entire screen is not rebuilt repeatedly.
- CPU/GPU source rows and the Available sensors list show the original kernel thermal-zone type and `thermal_zoneNN` ID under every value. The sensor list has an explicit Refresh action. Custom rows are shown before the editor form, each has its own live-value key, and Add confirms success with a toast/status line.

## Appearance
- Border-free, text-only HUD by default; no header or load/clock rows. White labels, heat-coloured values, adjustable text brightness, size, refresh interval, optional background opacity, saved position and touch-through lock.
- Refresh interval ranges from 0.50–3.00 seconds in 0.01-second steps (e.g. 1.42 s, 1.43 s); default is 1.50 seconds. The service starts one ticker only and repeated Apply/Start commands do not enqueue duplicate sampling loops. Sensor discovery uses that same next ticker, not a second background poller.
- The overlay and settings screen use native Android Views with no Compose or AndroidX UI dependencies. Sensor regexes and heat-colour stops are reused rather than rebuilt on every poll.

## Privacy
No Internet permission, analytics, updater UI, APK download/install code, or update receiver. APKs are installed manually.

## One workflow and release signing
The only workflow is `.github/workflows/android.yml`. It builds on pushes and has a manual option to publish a signed release using persistent repository secrets. SDK Manager skips installation if Android 36 and apksigner are already present; Gradle build caching is enabled.

Use the persistent keystore and four GitHub Actions secrets you already configured. This commit triggers a patch release from the latest GitHub Release tag. Future patch releases can use a commit message containing `[release]`; that path bumps only the patch number. Manual workflow dispatch still lets you choose patch/minor/major.
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The key-generation helper is not in the repository. Back up your keystore securely; every update must use the same signing key. Never commit or post the keystore, passwords or Base64 value. Then run **Temp HUD Build and Release** manually with **Publish release** enabled.

## Toolchain
AGP 9.3.1, Kotlin 2.4.21, Gradle 9.7.0, JDK 25, compile/target SDK 36, min SDK 30. There are no Compose or AndroidX UI dependencies; the screens use native Android Views. The launcher icon is a vector adaptive icon. Release code/resources are minified and shrunk with R8. The settings Activity and overlay share one app process to avoid duplicating Android's runtime in memory.
