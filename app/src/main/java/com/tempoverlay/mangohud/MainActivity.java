package com.tempoverlay.mangohud;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(20));
        root.setBackgroundColor(Color.rgb(11,16,24));
        TextView title = new TextView(this);
        title.setText("Temp HUD");
        title.setTextSize(32);
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, 1);
        root.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText("Lightweight MangoHud-style overlay for Android 13 and gaming.");
        subtitle.setTextSize(15);
        subtitle.setTextColor(Color.LTGRAY);
        subtitle.setPadding(0, dp(8), 0, dp(20));
        root.addView(subtitle);
        TextView info = new TextView(this);
        info.setText("Shows per-core CPU load and clock speed where readable, plus real thermal-zone sensors. Android may not expose a temperature for every core; unavailable readings stay marked N/A.");
        info.setTextSize(14);
        info.setTextColor(Color.rgb(190,205,220));
        info.setPadding(0,0,0,dp(20));
        root.addView(info);
        Button start = new Button(this);
        start.setText("Start floating overlay");
        root.addView(start);
        start.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                Intent permission = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                startActivity(permission);
                return;
            }
            Intent service = new Intent(this, OverlayService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
        });
        Button stop = new Button(this);
        stop.setText("Stop overlay");
        root.addView(stop);
        stop.setOnClickListener(v -> stopService(new Intent(this, OverlayService.class)));
        TextView foot = new TextView(this);
        foot.setText("\nPrototype 0.1.0 • No analytics • No third-party runtime libraries");
        foot.setTextColor(Color.GRAY);
        foot.setTextSize(12);
        root.addView(foot);
        setContentView(root);
    }
}
