package com.tempoverlay.mangohud

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        private const val REPO = "Rushabh-0000/temp-hud-android"
        private const val INSTALL_PERMISSION_REQUEST = 779
    }

    private val io = Executors.newSingleThreadExecutor()
    private var status: TextView? = null
    private var pendingInstaller: File? = null

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cleanStaleUpdateFiles()
        buildUi()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22f), dp(26f), dp(22f), dp(24f))
            setBackgroundColor(Color.rgb(11, 16, 24))
        }

        root.addView(TextView(this).apply {
            text = "Temp HUD"
            textSize = 32f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "Lightweight performance overlay for Android gaming."
            textSize = 15f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(7f), 0, dp(18f))
        })

        root.addView(TextView(this).apply {
            text = "Version ${BuildConfig.VERSION_NAME}  •  Code ${BuildConfig.VERSION_CODE}"
            textSize = 13f
            setTextColor(Color.rgb(72, 224, 178))
            setPadding(0, 0, 0, dp(14f))
        })

        root.addView(TextView(this).apply {
            text = "Per-core CPU load and clock speeds are shown where readable. Real thermal-zone sensors are listed separately; the phone may not expose individual core temperatures."
            textSize = 14f
            setTextColor(Color.rgb(190, 205, 220))
            setPadding(0, 0, 0, dp(18f))
        })

        root.addView(button("Start floating overlay").apply {
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                    return@setOnClickListener
                }
                val service = Intent(this@MainActivity, OverlayService::class.java)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
                showStatus("Overlay started. Drag it to position it over your game.")
            }
        })

        root.addView(button("Stop overlay").apply {
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
                showStatus("Overlay stopped.")
            }
        })

        root.addView(TextView(this).apply {
            text = "Updates"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(22f), 0, dp(6f))
        })

        root.addView(TextView(this).apply {
            text = "For a private GitHub repository, use a fine-grained token with access to this repository and Contents: Read-only. The token stays on this device and is encrypted with Android Keystore."
            setTextColor(Color.rgb(160, 178, 196))
            textSize = 13f
            setPadding(0, 0, 0, dp(10f))
        })

        root.addView(button("Configure GitHub update access").apply {
            setOnClickListener { showTokenDialog() }
        })

        root.addView(button("Check for updates").apply {
            setOnClickListener { checkForUpdates() }
        })

        root.addView(button("Forget GitHub token").apply {
            setOnClickListener {
                SecureTokenStore.clear(this@MainActivity)
                showStatus("Saved GitHub token removed from this device.")
            }
        })

        status = TextView(this).apply {
            text = "Ready. The overlay works without GitHub access."
            setTextColor(Color.rgb(72, 224, 178))
            textSize = 13f
            setPadding(0, dp(14f), 0, dp(10f))
        }
        root.addView(status)

        root.addView(TextView(this).apply {
            text = "No analytics. Update APKs are SHA-256 checked and removed from temporary cache after a successful app update. Android still requires your confirmation to install."
            setTextColor(Color.GRAY)
            textSize = 12f
            setPadding(0, dp(14f), 0, 0)
        })

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun button(label: String) = Button(this).apply { text = label }

    private fun showTokenDialog() {
        val input = EditText(this).apply {
            setSingleLine(true)
            hint = "github_pat_…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("GitHub update access")
            .setMessage(
                "Create a fine-grained personal access token in GitHub Settings → Developer settings. " +
                    "Limit it to $REPO and grant Contents: Read-only. Never share it in chat or commit it to the repository."
            )
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save and check") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isEmpty()) {
                    showStatus("No token entered.")
                    return@setPositiveButton
                }
                try {
                    SecureTokenStore.save(this, value)
                    showStatus("Token encrypted on this device. Checking for a release…")
                    checkForUpdates()
                } catch (e: Exception) {
                    showStatus("Could not securely store token: ${safeMessage(e)}")
                }
            }
            .show()
    }

    private fun checkForUpdates() {
        val token = try {
            SecureTokenStore.read(this)
        } catch (_: Exception) {
            showStatus("Saved token could not be decrypted. Forget it and configure access again.")
            return
        }
        if (token.isNullOrEmpty()) {
            showTokenDialog()
            return
        }

        showStatus("Checking latest release…")
        io.execute {
            try {
                val release = requestJson("https://api.github.com/repos/$REPO/releases/latest", token)
                val assets = release.getJSONArray("assets")
                val updateAsset = findAsset(assets, "update.json")
                    ?: throw IllegalStateException("Latest release has no updater metadata asset.")
                val apkAsset = findAsset(assets, "app-release.apk")
                    ?: throw IllegalStateException("Latest release has no APK asset.")
                val metadata = JSONObject(readAssetText(updateAsset.getString("url"), token))
                val remoteCode = metadata.getInt("versionCode")
                val remoteName = metadata.getString("versionName")

                if (remoteCode <= BuildConfig.VERSION_CODE) {
                    runOnUiThread { showStatus("You're up to date (${BuildConfig.VERSION_NAME}).") }
                    return@execute
                }

                val digest = metadata.getString("apkSha256").lowercase(Locale.ROOT)
                val assetUrl = apkAsset.optString("url")
                runOnUiThread {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Temp HUD update available")
                        .setMessage(
                            "Installed: ${BuildConfig.VERSION_NAME}\nAvailable: $remoteName" +
                                "\n\nDownload and verify the signed APK, then open Android's installer?"
                        )
                        .setNegativeButton("Later") { _, _ -> showStatus("Update postponed.") }
                        .setPositiveButton("Download & install") { _, _ ->
                            downloadAndInstall(assetUrl, token, digest, remoteName)
                        }
                        .show()
                }
            } catch (e: Exception) {
                val message = safeMessage(e)
                runOnUiThread {
                    showStatus(
                        "Update check failed: $message. Verify token access and confirm a release has been published."
                    )
                }
            }
        }
    }

    private fun downloadAndInstall(
        assetApiUrl: String,
        token: String,
        expectedSha256: String,
        version: String
    ) {
        showStatus("Downloading Temp HUD $version…")
        io.execute {
            val folder = File(cacheDir, "updates")
            val partial = File(folder, "app-release.apk.part")
            val apk = File(folder, "app-release.apk")
            try {
                if (!folder.exists() && !folder.mkdirs()) {
                    throw IllegalStateException("Cannot create temporary update folder.")
                }
                if (partial.exists()) partial.delete()
                openGitHubAsset(assetApiUrl, token).use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }

                val actualHash = sha256(partial)
                if (!actualHash.equals(expectedSha256, ignoreCase = true)) {
                    partial.delete()
                    throw SecurityException("APK SHA-256 did not match release metadata.")
                }

                if (apk.exists()) apk.delete()
                if (!partial.renameTo(apk)) throw IllegalStateException("Could not finalize downloaded APK.")
                pendingInstaller = apk
                runOnUiThread {
                    showStatus("APK verified. Opening Android's installer…")
                    openInstallerWhenAllowed()
                }
            } catch (e: Exception) {
                partial.delete()
                runOnUiThread { showStatus("Download failed: ${safeMessage(e)}") }
            }
        }
    }

    private fun openInstallerWhenAllowed() {
        val apk = pendingInstaller
        if (apk == null || !apk.isFile) {
            showStatus("Update package is missing. Please check for updates again.")
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            showStatus("Allow Temp HUD to install updates, then return here.")
            startActivityForResult(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                ),
                INSTALL_PERMISSION_REQUEST
            )
            return
        }

        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(install)
            showStatus("Confirm installation in Android. The temporary APK is removed after the package update completes.")
        } catch (e: Exception) {
            showStatus("Could not open Android installer: ${safeMessage(e)}")
        }
    }

    @Deprecated("Uses the platform settings activity for allowing APK installs")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == INSTALL_PERMISSION_REQUEST) {
            if (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) {
                openInstallerWhenAllowed()
            } else {
                showStatus("Install permission was not enabled. The APK remains temporarily cached for retry.")
            }
        }
    }

    private fun requestJson(url: String, token: String): JSONObject {
        val connection = openApiConnection(url, token, "application/vnd.github+json")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException(httpMessage(code))
            return JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun readAssetText(apiUrl: String, token: String): String =
        openGitHubAsset(apiUrl, token).bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** Follows GitHub's signed asset redirect. Never forward the repository token to the asset CDN. */
    private fun openGitHubAsset(apiUrl: String, token: String): InputStream {
        var current = URL(apiUrl)
        repeat(6) {
            val connection = current.openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/octet-stream")
            connection.setRequestProperty("User-Agent", "TempHUD-Android/${BuildConfig.VERSION_NAME}")
            if (current.host.equals("api.github.com", ignoreCase = true)) {
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }

            val code = connection.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrEmpty()) throw IllegalStateException("GitHub download redirect was missing.")
                current = URL(current, location)
                return@repeat
            }

            if (code !in 200..299) {
                connection.disconnect()
                throw IllegalStateException(httpMessage(code))
            }

            return object : FilterInputStream(connection.inputStream) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        connection.disconnect()
                    }
                }
            }
        }
        throw IllegalStateException("Too many redirects while downloading release asset.")
    }

    private fun openApiConnection(url: String, token: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            requestMethod = "GET"
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "TempHUD-Android/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }

    private fun findAsset(assets: JSONArray, name: String): JSONObject? {
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name") == name) return asset
        }
        return null
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    private fun httpMessage(code: Int): String = when (code) {
        401, 403, 404 -> "GitHub denied access (HTTP $code); check repository token permissions"
        429 -> "GitHub rate limit reached"
        else -> "GitHub request returned HTTP $code"
    }

    private fun safeMessage(error: Exception): String {
        val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        return message
            .replace(Regex("(?i)gh[pousr]_[A-Za-z0-9_]+"), "[redacted]")
            .replace("\n", " ")
    }

    private fun showStatus(message: String) {
        status?.text = message
    }

    private fun cleanStaleUpdateFiles() {
        val folder = File(cacheDir, "updates")
        val files = folder.listFiles() ?: return
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        files.forEach { file -> if (file.lastModified() < cutoff) file.delete() }
    }

    override fun onDestroy() {
        if (isFinishing) io.shutdown()
        super.onDestroy()
    }
}
