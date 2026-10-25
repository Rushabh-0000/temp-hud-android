# Temp HUD for Android

Lightweight MangoHud-inspired floating overlay for Android phones, initially targeted at Realme 9 5G Speed Edition (Snapdragon 778G, Android 13).

## Features
- Floating draggable always-on-top overlay
- Per-logical-core CPU load from /proc/stat
- CPU clock where readable from sysfs
- Real thermal-zone readings from /sys/class/thermal
- No third-party runtime libraries
- GitHub Actions builds a debug APK and publishes it as a workflow artifact

## Important limits
Android does not guarantee access to individual CPU-core temperatures for ordinary apps. The HUD marks per-core temperature as unavailable and lists real thermal sensors separately; it never fabricates per-core values. CPU frequency and thermal sysfs access vary by firmware and SELinux policy. FPS and GPU utilization are not implemented.

## Build
Push to main or run Actions > Build Temp HUD APK > Run workflow. Download the `temp-hud-debug-apk` artifact and install `app-debug.apk`. This is a debug APK, not a Play Store release.
