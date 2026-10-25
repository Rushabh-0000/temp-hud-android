package com.tempoverlay.mangohud;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String REPO = "Rushabh-0000/temp-hud-android";
    private static final int INSTALL_PERMISSION_REQUEST = 779;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private TextView status;
    private File pendingInstaller;

    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        cleanStaleUpdateFiles();
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(26), dp(22), dp(24));
        root.setBackgroundColor(Color.rgb(11,16,24));

        TextView title = new TextView(this);
        title.setText("Temp HUD");
        title.setTextSize(32);
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Lightweight performance overlay for Android gaming.");
        subtitle.setTextSize(15);
        subtitle.setTextColor(Color.LTGRAY);
        subtitle.setPadding(0, dp(7), 0, dp(18));
        root.addView(subtitle);

        TextView version = new TextView(this);
        version.setText("Version " + BuildConfig.VERSION_NAME + "  •  Code " + BuildConfig.VERSION_CODE);
        version.setTextSize(13);
        version.setTextColor(Color.rgb(72,224,178));
        version.setPadding(0, 0, 0, dp(14));
        root.addView(version);

        TextView info = new TextView(this);
        info.setText("Per-core CPU load and clock speeds are shown where readable. Real thermal-zone sensors are listed separately; the phone may not expose individual core temperatures.");
        info.setTextSize(14);
        info.setTextColor(Color.rgb(190,205,220));
        info.setPadding(0, 0, 0, dp(18));
        root.addView(info);

        Button start = button("Start floating overlay");
        root.addView(start);
        start.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                return;
            }
            Intent service = new Intent(this, OverlayService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
            showStatus("Overlay started. Drag it to position it over your game.");
        });

        Button stop = button("Stop overlay");
        root.addView(stop);
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, OverlayService.class));
            showStatus("Overlay stopped.");
        });

        TextView updateTitle = new TextView(this);
        updateTitle.setText("Updates");
        updateTitle.setTextSize(20);
        updateTitle.setTextColor(Color.WHITE);
        updateTitle.setTypeface(null, 1);
        updateTitle.setPadding(0, dp(22), 0, dp(6));
        root.addView(updateTitle);

        TextView updateInfo = new TextView(this);
        updateInfo.setText("For a private GitHub repository, use a fine-grained token with access to this repository and Contents: Read-only. The token stays on this device and is encrypted with Android Keystore.");
        updateInfo.setTextColor(Color.rgb(160,178,196));
        updateInfo.setTextSize(13);
        updateInfo.setPadding(0, 0, 0, dp(10));
        root.addView(updateInfo);

        Button token = button("Configure GitHub update access");
        root.addView(token);
        token.setOnClickListener(v -> showTokenDialog());

        Button check = button("Check for updates");
        root.addView(check);
        check.setOnClickListener(v -> checkForUpdates());

        Button clear = button("Forget GitHub token");
        root.addView(clear);
        clear.setOnClickListener(v -> {
            SecureTokenStore.clear(this);
            showStatus("Saved GitHub token removed from this device.");
        });

        status = new TextView(this);
        status.setText("Ready. The overlay works without GitHub access.");
        status.setTextColor(Color.rgb(72,224,178));
        status.setTextSize(13);
        status.setPadding(0, dp(14), 0, dp(10));
        root.addView(status);

        TextView foot = new TextView(this);
        foot.setText("No analytics. Update APKs are SHA-256 checked and removed from temporary cache after a successful app update. Android still requires your confirmation to install.");
        foot.setTextColor(Color.GRAY);
        foot.setTextSize(12);
        foot.setPadding(0, dp(14), 0, 0);
        root.addView(foot);
        scroll.addView(root);
        setContentView(scroll);
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        return b;
    }

    private void showTokenDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("github_pat_…");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
                .setTitle("GitHub update access")
                .setMessage("Create a fine-grained personal access token in GitHub Settings → Developer settings. Limit it to " + REPO + " and grant Contents: Read-only. Never share it in chat or commit it to the repository.")
                .setView(input)
                .setNegativeButton("Cancel", (d, w) -> {})
                .setPositiveButton("Save and check", (d, w) -> {
                    String value = input.getText().toString().trim();
                    if (value.isEmpty()) {
                        showStatus("No token entered.");
                        return;
                    }
                    try {
                        SecureTokenStore.save(this, value);
                        showStatus("Token encrypted on this device. Checking for a release…");
                        checkForUpdates();
                    } catch (Exception e) {
                        showStatus("Could not securely store token: " + safeMessage(e));
                    }
                }).show();
    }

    private void checkForUpdates() {
        final String token;
        try {
            token = SecureTokenStore.read(this);
        } catch (Exception e) {
            showStatus("Saved token could not be decrypted. Forget it and configure access again.");
            return;
        }
        if (token == null || token.isEmpty()) {
            showTokenDialog();
            return;
        }
        showStatus("Checking latest release…");
        io.execute(() -> {
            try {
                JSONObject release = requestJson("https://api.github.com/repos/" + REPO + "/releases/latest", token);
                JSONArray assets = release.getJSONArray("assets");
                JSONObject updateAsset = findAsset(assets, "update.json");
                JSONObject apkAsset = findAsset(assets, "app-release.apk");
                if (updateAsset == null || apkAsset == null) throw new IllegalStateException("Latest release has no updater metadata or APK asset.");
                JSONObject metadata = new JSONObject(readAssetText(updateAsset.getString("url"), token));
                int remoteCode = metadata.getInt("versionCode");
                String remoteName = metadata.getString("versionName");
                if (remoteCode <= BuildConfig.VERSION_CODE) {
                    runOnUiThread(() -> showStatus("You're up to date (" + BuildConfig.VERSION_NAME + ")."));
                    return;
                }
                String digest = metadata.getString("apkSha256").toLowerCase(Locale.ROOT);
                String assetUrl = apkAsset.optString("url");
                runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Temp HUD update available")
                        .setMessage("Installed: " + BuildConfig.VERSION_NAME + "\nAvailable: " + remoteName
                                + "\n\nDownload and verify the signed APK, then open Android's installer?")
                        .setNegativeButton("Later", (d, w) -> showStatus("Update postponed."))
                        .setPositiveButton("Download & install", (d, w) -> downloadAndInstall(assetUrl, token, digest, remoteName))
                        .show());
            } catch (Exception e) {
                String message = safeMessage(e);
                runOnUiThread(() -> showStatus("Update check failed: " + message + ". Verify token access and confirm a release has been published."));
            }
        });
    }

    private void downloadAndInstall(String assetApiUrl, String token, String expectedSha256, String version) {
        showStatus("Downloading Temp HUD " + version + "…");
        io.execute(() -> {
            File folder = new File(getCacheDir(), "updates");
            File partial = new File(folder, "app-release.apk.part");
            File apk = new File(folder, "app-release.apk");
            try {
                if (!folder.exists() && !folder.mkdirs()) throw new IllegalStateException("Cannot create temporary update folder.");
                if (partial.exists()) partial.delete();
                try (InputStream in = openGitHubAsset(assetApiUrl, token);
                     FileOutputStream out = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                    out.getFD().sync();
                }
                String actualHash = sha256(partial);
                if (!actualHash.equalsIgnoreCase(expectedSha256)) {
                    partial.delete();
                    throw new SecurityException("APK SHA-256 did not match release metadata.");
                }
                if (apk.exists()) apk.delete();
                if (!partial.renameTo(apk)) throw new IllegalStateException("Could not finalize downloaded APK.");
                pendingInstaller = apk;
                runOnUiThread(() -> {
                    showStatus("APK verified. Opening Android's installer…");
                    openInstallerWhenAllowed();
                });
            } catch (Exception e) {
                partial.delete();
                runOnUiThread(() -> showStatus("Download failed: " + safeMessage(e)));
            }
        });
    }

    private void openInstallerWhenAllowed() {
        if (pendingInstaller == null || !pendingInstaller.isFile()) {
            showStatus("Update package is missing. Please check for updates again.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            showStatus("Allow Temp HUD to install updates, then return here.");
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())), INSTALL_PERMISSION_REQUEST);
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", pendingInstaller);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(install);
            showStatus("Confirm installation in Android. The temporary APK is removed after the package update completes.");
        } catch (Exception e) {
            showStatus("Could not open Android installer: " + safeMessage(e));
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == INSTALL_PERMISSION_REQUEST) {
            if (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls()) {
                openInstallerWhenAllowed();
            } else {
                showStatus("Install permission was not enabled. The APK will remain temporarily cached for retry.");
            }
        }
    }

    private JSONObject requestJson(String url, String token) throws Exception {
        HttpURLConnection c = openApiConnection(url, token, "application/vnd.github+json");
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException(httpMessage(code));
            return new JSONObject(readAll(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private String readAssetText(String apiUrl, String token) throws Exception {
        try (InputStream in = openGitHubAsset(apiUrl, token)) { return readAll(in); }
    }

    /** Follows the API's signed download redirect; never forward the GitHub token to the asset CDN. */
    private InputStream openGitHubAsset(String apiUrl, String token) throws Exception {
        URL current = new URL(apiUrl);
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = (HttpURLConnection) current.openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod("GET");
            c.setRequestProperty("Accept", "application/octet-stream");
            c.setRequestProperty("User-Agent", "TempHUD-Android/" + BuildConfig.VERSION_NAME);
            if ("api.github.com".equalsIgnoreCase(current.getHost()) && token != null) {
                c.setRequestProperty("Authorization", "Bearer " + token);
                c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            }
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = c.getHeaderField("Location");
                c.disconnect();
                if (location == null || location.isEmpty()) throw new IllegalStateException("GitHub download redirect was missing.");
                current = new URL(current, location);
                continue;
            }
            if (code < 200 || code >= 300) {
                c.disconnect();
                throw new IllegalStateException(httpMessage(code));
            }
            return new DisconnectingInputStream(c.getInputStream(), c);
        }
        throw new IllegalStateException("Too many redirects while downloading release asset.");
    }

    private HttpURLConnection openApiConnection(String url, String token, String accept) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestMethod("GET");
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("User-Agent", "TempHUD-Android/" + BuildConfig.VERSION_NAME);
        if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        return c;
    }

    private JSONObject findAsset(JSONArray assets, String name) throws Exception {
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (name.equals(asset.optString("name"))) return asset;
        }
        return null;
    }

    private String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) digest.update(buffer, 0, n);
        }
        StringBuilder out = new StringBuilder();
        for (byte b : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    private String readAll(InputStream input) throws Exception {
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int n;
            while ((n = r.read(buffer)) != -1) out.append(buffer, 0, n);
        }
        return out.toString();
    }

    private String httpMessage(int code) {
        if (code == 401 || code == 403 || code == 404) return "GitHub denied access (HTTP " + code + "); check repository token permissions";
        if (code == 429) return "GitHub rate limit reached";
        return "GitHub request returned HTTP " + code;
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) return e.getClass().getSimpleName();
        return message.replaceAll("(?i)gh[pousr]_[A-Za-z0-9_]+", "[redacted]").replace("\n", " ");
    }

    private void showStatus(String message) {
        if (status != null) status.setText(message);
    }

    private void cleanStaleUpdateFiles() {
        File folder = new File(getCacheDir(), "updates");
        File[] files = folder.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
        for (File file : files) if (file.lastModified() < cutoff) file.delete();
    }

    @Override protected void onDestroy() {
        if (isFinishing()) io.shutdown();
        super.onDestroy();
    }

    private static final class DisconnectingInputStream extends java.io.FilterInputStream {
        private final HttpURLConnection connection;
        DisconnectingInputStream(InputStream in, HttpURLConnection connection) {
            super(in);
            this.connection = connection;
        }
        @Override public void close() throws java.io.IOException {
            try { super.close(); } finally { connection.disconnect(); }
        }
    }
}
