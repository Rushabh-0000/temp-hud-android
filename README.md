# Temp HUD

Compact MangoHud-inspired Android temperature overlay, written in Kotlin with Jetpack Compose Material 3. Designed for Realme 9 Speed Edition, Android 11+.

## Behaviour
- Starts with compact `CPU 46.0°C` and `GPU 45.0°C` rows. CPU 0–7 cores, any readable thermal-zone sensor, and custom text + temperature rows can be enabled independently.
- Rows are measured from the actual text width. There is no fixed wide panel, header, LIVE badge, preview panel or border; background opacity defaults to zero.
- The full text area can be dragged when unlocked. Locking changes the overlay to FLAG_NOT_TOUCHABLE, so all touches pass through to the app beneath it. The window opacity is 0.79 while locked to comply with Android 12+ untrusted overlay touch-pass-through restrictions.
- The interval can be set precisely in 0.05 s increments from 0.50–5.00 s; default is 1.50 s. Text brightness, label colour, scale, optional background, lock, and saved X/Y position are configurable.
- The sensor picker lists readable thermal-zone `temp` values and displays each zone's source ID. Custom rows are saved locally and can be added multiple times.
- Overall CPU is the mean of mapped distinct per-core sources; if mapping is unavailable, it averages deduplicated CPU-labelled zones. Overall GPU averages distinct GPU-labelled zones. Paired Qualcomm `-usr` / `-step` sources are deduplicated with `-usr` preferred.
- Non-CPU/GPU temperature zones are selectable as extra rows. Android/OEM firmware must expose readable thermal data; otherwise the app displays N/A, never a fabricated reading.

## Privacy
No Internet permission, analytics, updater UI, GitHub token, download/install code, or update receiver. Releases are installed manually.

## Build
AGP 9.3.1, Kotlin 2.4.21, Gradle 9.7.0, JDK 25, compile/target SDK 36, min SDK 30, Compose BOM 2026.06.00. Release code and resources are minified/shrunk. CI signs test builds with a disposable runner-only key; final release signing remains a separate setup.
