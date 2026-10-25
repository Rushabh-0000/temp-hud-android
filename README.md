# Temp HUD for Android

Compact, MangoHud-inspired Android **temperature-only** overlay. Written in Kotlin with Jetpack Compose Material 3. Designed for Realme 9 5G Speed Edition (Snapdragon 778G / Android 13); minimum OS Android 11 (API 30).

## Temperature-only HUD

- Independently enable overall CPU temperature, CPU 0-7 temperature, and overall GPU temperature.
- HUD rows contain only a sensor label and its value (for example `CPU 1  46.1°C`). No title, LIVE label, CPU/GPU load, or frequency lines are drawn.
- Temperature text transitions from green through lime/yellow/orange to red as it rises.
- Change sensor label color, text brightness, background opacity, and panel scale.
- Lock the overlay against dragging, or save its horizontal X and vertical Y positions through drag or app sliders.
- Overlay position is clamped below display cutouts/notches; the app screen observes safe drawing insets.
- Sensor values are sampled on a low-priority background thread at configurable intervals. The settings page shows live readings for the selected sensors.

## Temperature sensors and limitations

- Overall CPU temperature uses the maximum readable CPU/CPUSS thermal-zone temperature.
- Overall GPU temperature reads only GPU/GPUSS-labelled thermal zones.
- Qualcomm names such as `cpu-0-2-usr` and `cpu-1-3-step` encode cluster and local-core indexes; cluster 0 maps to logical CPU 0-3 and cluster 1 to CPU 4-7 on the targeted 8-core layout.
- Generic or unrelated zones (modem, display, battery, skin) are not relabelled as CPU/GPU temperatures. If Android/OEM firmware does not expose a metric, it displays `N/A` rather than a guess.
- The app does not duplicate the overall CPU temperature across all per-core rows.

## Privacy and build

No network permission, analytics, GitHub token UI, in-app updater, APK download/install code, or updater receiver. New versions are installed manually.

AGP 9.3.1, Kotlin 2.4.21, Gradle 9.7.0, JDK 25, compile/target SDK 36, min SDK 30, Compose BOM 2026.06.00. Release APK is minified and resource-shrunk. CI uses a temporary runner-only key for test APK artifacts; a permanent signing key is a separate one-time setup after the repository is private.
