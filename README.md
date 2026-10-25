# Temp HUD for Android

Compact, temperature-only Android overlay inspired by MangoHud, written in Kotlin with Jetpack Compose Material 3. Designed for Realme 9 5G Speed Edition (Snapdragon 778G / Android 13); minimum OS Android 11 (API 30).

## Overlay and selection

- Default rows are overall `CPU` and overall `GPU` temperature. Per-core CPU and individual GPU-labelled thermal sensors are off until selected.
- Overall CPU/GPU are arithmetic averages across unique readable sources. Qualcomm paired `-usr` and `-step` thermal zones for the same source are deduplicated, preferring `-usr`.
- The HUD has no title, LIVE badge, border, load, or frequency rows. Heat-coloured values move green → yellow → orange → red as temperature increases.
- Background opacity defaults to zero for text-only rendering. Text colour, brightness, size, refresh interval, and optional background opacity are adjustable.
- The text window is always non-touchable. A separate tiny handle is the only touchable piece when unlocked. When locked, the handle also becomes non-touchable; touches through the HUD are intended to continue to the game.
- Main and handle windows set alpha to 0.79 so Android 12+'s untrusted-touch policy can permit pass-through under its default maximum obscuring opacity. This stays below the documented 0.8 maximum.
- X/Y position persists and is clamped below the display cutout. The app screen uses safe drawing insets.
- The app shows live temperatures and expandable exact source names such as `cpu-1-3-usr · thermal_zone15`.

## Temperature sensors

- Overall CPU temperature averages mapped per-core readings when available; if none are mapped, it averages unique explicitly CPU-labelled zones.
- Overall GPU temperature averages unique GPU/GPUSS-labelled zones.
- Individual CPU core values are mapped only from recognisable CPU core labels. GPU sensor choices come only from readable GPU-labelled thermal zones. The app does not invent GPU cores or relabel modem/display/battery/skin sensors as CPU/GPU.
- Unsupported or blocked readings show `N/A`.

## Privacy and build

No network permission, analytics, GitHub token UI, updater, APK download/install code, or updater receiver. Future builds are manually installed.

AGP 9.3.1, Kotlin 2.4.21, Gradle 9.7.0, JDK 25, compile/target SDK 36, min SDK 30, Compose BOM 2026.06.00. Release APK is minified and resource-shrunk. CI uses a temporary runner-only key for test artifacts; permanent signing is a separate step once the repository is private.
