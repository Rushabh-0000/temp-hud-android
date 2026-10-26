# Temp HUD

Current development version: **0.7.15**.

Compact MangoHud-inspired Android temperature overlay for Android 11+, written in Kotlin using Android's built-in Material-themed Views to keep the APK lean.


## Dashboard redesign (0.7.10)
- Rebuilt the home screen around large, separate CPU and GPU temperature tiles with clearer reading hierarchy.
- Moved the primary floating-HUD start/stop controls directly below the live dashboard.
- Grouped sensor selection, refresh frequency, and appearance/position controls under labelled sections.
- Updated the accent palette for stronger contrast and a more distinct thermal-monitor dashboard.


## Premium visual refresh (0.7.12)
- Removed the redundant control-centre subtitle from the app header.
- Reworked the launcher icon into a layered gauge-and-thermometer mark with metallic bezel details, segmented lime/cyan arcs and a warm thermal indicator, using Android vectors to keep it crisp and small.
- Refined the native-View dashboard with a midnight/graphite palette, more deliberate metric tiles, improved sensor cards, higher contrast and less default-looking controls.

## Complete three-screen redesign (0.7.14)
- Replaced the single long settings list with a fixed header, a scrollable destination, and bottom navigation for Dashboard, Sensors, and Appearance.
- Dashboard now focuses on live CPU/GPU readings, Start/Stop, average source selection, and polling frequency.
- Sensors has its own library for CPU core variants, GPU sources, full sensor search, and custom rows.
- Appearance has a live reading preview plus separate visual and position controls, including position lock.
- Preserved existing sensor sampling and foreground overlay service; kept the UI built with native Android Views to avoid adding a large UI toolkit dependency.

## Thermal source routing (0.7.15)
- Reads zone source names from /sys/class/thermal/thermal_zone*/type and samples only names ending in -usr; -step and other variants are excluded.
- Converts raw millidegree values to Celsius using raw / 1000.0.
- CPU summary averages cpuss-0-usr and cpuss-1-usr. GPU summary averages gpuss-0-usr and gpuss-1-usr. Missing exact zones fall back to the closest readable CPU/GPU -usr values.
- Sampling runs on a low-priority HandlerThread with a bounded interval. Service teardown clears queued callbacks and quits the worker. Polling uses some power while active; zero battery drain is not possible for a live monitor.

## Low-memory background mode
- The overlay service uses a background-priority sensor thread in the app's single process. Leaving the app finishes/removes the settings Activity task and releases its view tree while the foreground service remains. This avoids the extra ART/runtime overhead from running a second app process.
- When the HUD is active and you leave the app, the settings task is removed and the Activity view tree can be collected while the foreground service continues in the same process.
- Settings use app-private SharedPreferences only; the unused duplicate atomic-JSON/PID-file store was removed. The app samples sensors only while its screen is visible; it stops polling and collecting when paused.
- Only one sensor sampler runs at a time: the overlay service while active, or the visible settings screen while stopped. Opening a sensor picker asks the active sampler to include all thermal zones on its next scheduled read; it does not launch another concurrent sensor scan.

## Sensor selection
- Overall CPU and GPU temperatures appear first. The average selectors show the exact **usr** or **step** variant; if the selected variant is not readable, the value is **N/A**.
- CPU 0–7 can be selected independently. Source editor columns are labelled **usr** and **step**, and the two variants remain independently selectable.
- CPU sources and GPU-labelled sources show **usr**/**step** checkboxes side by side for each available variant. When both variants for one label are selected, the HUD displays one row and prefers **usr**; it uses **step** only when **usr** is not selected. Overlay labels remain clean (`CPU 0`, `GPU 0`) without variant suffixes. Both overall averages default to **usr** and use a mutually exclusive selector.
- These are thermal-zone readings exposed by the phone firmware, not guaranteed physical GPU-core sensors. If firmware exposes no suitable sensor, the app shows N/A.
- Any readable thermal-zone sensor can be selected from Browse, and custom rows can pair a custom label with any selected temperature sensor.

## UI refresh and locked-position fix in 0.7.9
- The settings screen remains a native View hierarchy in a vertical ScrollView with natural WRAP_CONTENT content height. Section cards now use a clearer title/description/count hierarchy, a refreshed green/cyan palette, and better spacing. Expanded sections, sensor search, and scroll position survive Activity recreation.
- The lock toggle now pins the overlay at its current on-screen coordinates and persists that anchor. Overlay samples re-read the latest config before updating the view so an old in-flight sensor sample cannot restore a stale lock or position after the user changes settings.
- Replaced the launcher artwork with a lightweight vector thermometer icon, an Android adaptive foreground/monochrome version, and a matching green/cyan theme accent.
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

Use the persistent keystore and four GitHub Actions secrets you already configured. A commit message containing `[release]` triggers a patch release from the latest GitHub Release tag. Manual workflow dispatch lets you choose patch/minor/major.
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The key-generation helper is not in the repository. Back up your keystore securely; every update must use the same signing key. Never commit or post the keystore, passwords or Base64 value. Then run **Temp HUD Build and Release** manually with **Publish release** enabled.

## Toolchain
AGP 9.3.1, Kotlin 2.4.21, Gradle 9.7.0, JDK 25, compile/target SDK 36, min SDK 30. There are no Compose or AndroidX UI dependencies; the screens use native Android Views. The launcher icon is a vector adaptive icon. Release code/resources are minified and shrunk with R8. The settings Activity and overlay share one app process to avoid duplicating Android's runtime in memory. On Android 12+, locked touch-through mode respects the device's obscuring-opacity limit (at most 0.8); unlocked mode remains at full window opacity. Android's window-level opacity rule means locked text can appear slightly less bright while pass-through is enabled.
