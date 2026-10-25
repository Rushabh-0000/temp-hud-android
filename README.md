# Temp HUD for Android

A lightweight MangoHud-inspired floating performance overlay for Android, initially targeted at Realme 9 5G Speed Edition (Snapdragon 778G, Android 13).

## Features
- Draggable floating overlay with CPU-core utilization, readable clock speed, and available real thermal-zone readings.
- Sensor polling off the UI thread at a conservative interval.
- Private GitHub release checking inside the app. Downloaded update packages are SHA-256 verified, then handed to Android's package installer.
- Temporary update APKs are cleared after Android broadcasts that the package replacement completed. Stale cache files are cleaned on next app launch.
- Signed, minified and resource-shrunk release APKs; **no debug APK is published**.
- Every Temp HUD workflow shares one concurrency group; a new workflow cancels an older running Temp HUD workflow.
- Temporary GitHub Actions artifacts are deleted after a release succeeds. GitHub Release assets are kept because the in-app updater needs them.

## Device and sensor limits
Android does not guarantee access to an individual temperature sensor per CPU core. Temp HUD never fabricates temperatures. Per-core temperature stays N/A when no reliable sensor mapping exists; real readable thermal zones are listed separately. CPU frequency files also vary by firmware and SELinux policy.

## First-time setup

**Make the repository private before creating the signing key.** Current app package/source can be seen by anyone while the repository is public.

1. Open Actions → **One-time Release Signing Key Setup** → Run workflow.
2. Download the `temp-hud-signing-bootstrap` artifact before it expires (one day). The ZIP contains `signing-setup.txt` and `temp-hud-release.p12`.
3. Open `signing-setup.txt` and add these four values as repository Actions secrets under Settings → Secrets and variables → Actions:
   - `ANDROID_KEYSTORE_BASE64`
   - `ANDROID_KEYSTORE_PASSWORD`
   - `ANDROID_KEY_ALIAS`
   - `ANDROID_KEY_PASSWORD`
4. Keep an offline backup of `temp-hud-release.p12` and its password. Every future update must use the **same** signing key. Never commit the key, include it in a release asset, or send the secret values in chat.
5. Open Actions → **Release Temp HUD** → Run workflow. Choose patch, minor, or major. It calculates the next semantic version and a monotonically increasing Android `versionCode`, signs/shrinks the release APK, and creates a GitHub Release containing `app-release.apk` and `update.json`.

The signing-key and release workflows check that this repository is private before handling signing material.

## In-app updates for a private repository
The updater needs a fine-grained GitHub personal access token entered on your phone, limited to this repository with **Contents: Read-only** permission (Metadata read is required by GitHub and is normally granted automatically). It is encrypted with an Android Keystore-backed AES-GCM key, stays on-device, and is not bundled into the APK or source. Never share or commit that token.

The update checker reads the latest GitHub Release and its metadata. Before installing, it verifies the APK against the SHA-256 in `update.json`. Android still displays its normal installer/confirmation UI; the app cannot silently install a new version on a standard non-rooted phone. Once Android reports the app update completed, Temp HUD deletes the cached APK. If installation is cancelled, it keeps the file until it becomes stale rather than deleting a file that the installer might still need.

If you previously installed the earlier prototype APK, uninstall it once before installing the first signed release because Android debug and release signatures do not match. Subsequent releases built with the same key update normally.

## Build toolchain
Stable versions verified on 25 October 2026:
- Android Gradle Plugin 9.4.1
- Gradle 9.8.1
- JDK 25
- Android SDK 36.1 (target SDK 36)
- AndroidX Core 1.18.0

The CI workflow compiles the release variant without publishing an unsigned APK. Only the **Release Temp HUD** workflow publishes signed, installable release APKs.
