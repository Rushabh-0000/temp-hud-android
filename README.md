# Temp HUD for Android

A low-overhead MangoHud-inspired floating CPU/thermal HUD written in Kotlin, initially targeted at the Realme 9 5G Speed Edition (Snapdragon 778G, Android 13).

## Current design
- Native Android UI and services written in Kotlin.
- Kotlin DSL build scripts: `build.gradle.kts`, `settings.gradle.kts`, and `app/build.gradle.kts`.
- Draggable overlay with per-logical-core CPU utilization, readable clock speeds, and actual thermal-zone readings when firmware permissions allow them.
- Sensor polling runs on a low-priority background thread every 1.5 seconds.
- Private GitHub release checking in-app. Update APKs are SHA-256 checked before passing to Android's installer.
- App-owned temporary APKs are cleaned after Android confirms the package update; old temp files are removed on next launch.
- Release workflow builds a signed, minified, resource-shrunk APK and attaches it with update metadata to a GitHub Release.
- All Temp HUD workflows share a concurrency group; starting a new run cancels an older active Temp HUD run.
- Temporary Actions artifacts are deleted after successful verification/release. GitHub Release assets are retained for the updater.

## Latest compatible stable toolchain (checked 25 Oct 2026)
This project deliberately uses a compatible stable set instead of independently selecting incompatible "latest" versions:
- Android Gradle Plugin 9.3.1 — Kotlin 2.4.21's documented maximum fully supported AGP version. AGP 9.4.1 is newer, but outside that official compatibility range.
- Kotlin 2.4.21 — current stable Kotlin release.
- Gradle 9.7.0 — Kotlin 2.4.21's maximum fully supported Gradle version. Gradle 9.8.2 is newer, but outside that fully supported range.
- JDK 25
- Android SDK compile/target API 36 (minimum OS Android 8.0 / API 26)
- AndroidX Core 1.18.0, the newest stable Core compatible with the SDK platform available on the hosted runner. Core 1.19.1 requires compiling against API 37, which the runner's SDK package repository did not provide at the time of verification.

References:
- Android Gradle Plugin compatibility: https://developer.android.com/build/releases/about-agp
- Kotlin Gradle Plugin compatibility: https://kotlinlang.org/docs/gradle-configure-project.html
- Gradle releases: https://gradle.org/releases/
- AndroidX Core release notes: https://developer.android.com/jetpack/androidx/releases/core
- Kotlin releases: https://kotlinlang.org/docs/releases.html

## Sensor limits
Android does not promise ordinary apps access to a true temperature sensor for each CPU core. Temp HUD never fabricates temperatures. Per-core temperature stays unavailable when no reliable matching sensor exists; readable hardware thermal zones are listed separately. CPU frequency files also vary by firmware and SELinux policy. FPS and GPU telemetry are not implemented yet.

## Release setup — required before the first release

**The repository must be private before generating or storing release signing material.** The current GitHub API check showed it was still public. The signing and release workflows fail closed while it remains public.

1. In GitHub repository Settings → General → Danger Zone, change visibility to **Private**.
2. Open Actions → **One-time Release Signing Key Setup** → Run workflow.
3. Download the `temp-hud-signing-bootstrap` artifact before it expires (one day). It contains `signing-setup.txt` and `temp-hud-release.p12`.
4. Add the four values listed in `signing-setup.txt` as repository Actions secrets under Settings → Secrets and variables → Actions:
   - `ANDROID_KEYSTORE_BASE64`
   - `ANDROID_KEYSTORE_PASSWORD`
   - `ANDROID_KEY_ALIAS`
   - `ANDROID_KEY_PASSWORD`
5. Keep a secure offline backup of `temp-hud-release.p12` and its password. Every future release must use this same key. Never commit the key or share the values in chat.
6. Open Actions → **Release Temp HUD** → Run workflow; choose patch, minor, or major. The workflow calculates the semantic version and increasing Android `versionCode`, builds/signs/shrinks the release APK, and creates a GitHub Release with `app-release.apk` and `update.json`.

The release workflow is intentionally manual so a source edit does not accidentally publish an app release. It automatically selects the version once you choose the bump type.

## In-app updates for a private repo
The updater needs a fine-grained GitHub personal access token entered on the phone, restricted to this repository with Contents: Read-only permission (GitHub's required metadata access is read-only). It is encrypted with an Android Keystore-backed AES-GCM key, remains on-device, and is not bundled in source or the APK. Never commit or share the token.

The updater reads the latest GitHub Release, fetches `update.json`, downloads the release APK, checks SHA-256, and opens Android's installer. Android still requires the user to approve installation on a standard non-rooted phone. Once Android broadcasts `MY_PACKAGE_REPLACED`, the app deletes its cached update APK. If installation is cancelled, the APK is only removed after it becomes stale.

If the old prototype/debug APK is already installed, uninstall it before the first signed install because its signing identity differs. Future signed releases built with the same key can update normally.

## Cloud build
The Verify Android Release Build workflow compiles the optimized release variant but does not publish an unsigned APK. Only the Release Temp HUD workflow publishes signed release assets.
