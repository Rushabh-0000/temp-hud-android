# Temp HUD for Android

A compact MangoHud-inspired overlay written in Kotlin with a Jetpack Compose control screen. Designed first for the Realme 9 5G Speed Edition (Snapdragon 778G, Android 13).

## Features

- Minimum OS: Android 11 (API 30); target API 36.
- Compose control screen with separate CPU and GPU module switches.
- Fixed-width HUD that can be moved by dragging the top strip; the panel cannot be resized by dragging.
- Adjustable panel transparency and sensor refresh interval.
- Overall CPU usage from aggregate `/proc/stat` counters when readable.
- CPU clock from readable CPU cpufreq files.
- GPU load and clock from Qualcomm KGSL/devfreq interfaces when the OS exposes them.
- Temperature readings are restricted to thermal-zone type names containing `cpu` or `gpu`. Modem, display, battery, and unknown zones are never labelled as CPU/GPU temperatures.
- When Android/OEM permissions block a metric or no matching sensor exists, it shows `N/A`, not a guessed value.
- Polling uses a low-priority background thread. Disabled modules are not polled.

## No in-app updater

There is no GitHub token UI, update checker, APK installer code, network permission, or analytics. Future releases are installed manually through Android's package installer.

## Build toolchain

- Android Gradle Plugin 9.3.1
- Kotlin 2.4.21 and the matching Compose compiler plugin
- Gradle 9.7.0; JDK 25
- Android SDK compile/target API 36; minimum API 30
- Jetpack Compose BOM 2026.07.00, Material 3, Activity Compose 1.13.0

The newer Compose 1.12 line requires compileSdk API 37. The hosted runner previously could not install that platform, so this project uses the latest stable Compose line compatible with the API 36 build environment rather than choosing a dependency that cannot build there.

## Cloud build

The **Verify Android Release Build** workflow compiles the optimized release variant and an installable debug APK. The debug artifact is uploaded as `temp-hud-debug-apk` for seven days. It uses a separate debug application ID and is for testing, not the final release.

For a signed release, configure the persistent release signing key and the four repository Actions secrets. Run **Release Temp HUD** to publish the signed, minified APK. Because the app has no updater, the release contains only the APK.

Keep the signing-key bootstrap artifact private: anyone who obtains the signing key can sign an app as Temp HUD. The key-setup workflow therefore requires the repository to be private before generating that key. This does not block ordinary CI builds or debug APKs.
