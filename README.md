# Temp HUD for Android

Compact MangoHud-inspired Android performance overlay, written in Kotlin with a Jetpack Compose settings screen. Target device: Realme 9 5G Speed Edition (Snapdragon 778G, Android 13). Minimum OS: Android 11 (API 30).

## HUD controls

- Individually select CPU load, CPU clock, overall CPU temperature, CPU 0 through CPU 7 temperature, GPU load, GPU clock, and overall GPU temperature.
- Each selected item appears as its own one-line metric in a compact fixed-width floating panel.
- Drag only the header to move the panel. Change the fixed panel scale from 70% to 110% in settings.
- Set background transparency and refresh interval (0.5-3.0 seconds).
- The overlay is drawn with a lightweight Canvas View. Procfs/sysfs reads run on a low-priority background thread, and disabled metrics are not polled.
- CPU load uses aggregate `/proc/stat` counters when readable. CPU clock uses readable per-core cpufreq files.
- GPU load/clock use Qualcomm KGSL/devfreq interfaces if readable.

## Temperature reporting

- Overall CPU temperature is the maximum readable thermal-zone temperature whose type is explicitly CPU/CPUSS-labelled.
- Overall GPU temperature uses GPU/GPUSS-labelled zones only.
- Qualcomm names such as `cpu-0-2-usr` and `cpu-1-3-step` encode cluster and local-core indexes. For this target 8-core layout, cluster 0 maps to logical CPU 0-3 and cluster 1 maps to logical CPU 4-7. Other sensors are mapped only when their name explicitly identifies a logical core.
- If the vendor kernel does not expose per-core temperature sensors, each selected core row remains `N/A`. The app does not duplicate overall CPU temperature across all eight cores or relabel modem/display/battery/skin sensors as CPU/GPU.

Android's public HardwarePropertiesManager API may throw SecurityException for ordinary apps; the app therefore uses readable thermal-zone interfaces and reports unavailable values as N/A rather than relying on privileged APIs.

## Privacy and updates

No internet permission, analytics, GitHub token screen, in-app updater, APK download/install code, or update receiver. New releases are installed manually by the user.

## Build

- AGP 9.3.1, Kotlin 2.4.21 (with matching Compose compiler plugin), Gradle 9.7.0, JDK 25.
- compileSdk/targetSdk 36, minSdk 30.
- Jetpack Compose BOM 2026.06.00, Material 3, Activity Compose 1.13.0.
- Release is minified and resource-shrunk. CI creates a temporary short-lived test signing key on the runner and uploads an optimized test APK artifact; no signing material is committed.

The permanent release signing key remains a one-time operation that should only be generated after the repository is private. Install the CI test APK manually. Because each CI test uses a disposable key, uninstall a previous test build before installing the next one.
