package com.tempoverlay.mangohud;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;

public final class OverlayService extends Service {
    private static final String CHANNEL = "temp_hud_monitor";
    private WindowManager wm;
    private TextView panel;
    private WindowManager.LayoutParams params;
    private HandlerThread pollingThread;
    private Handler pollingHandler;
    private long[] previousTotal, previousIdle;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            TextView target = panel;
            if (target == null || pollingHandler == null) return;
            final String snapshot = makeSnapshot();
            target.post(() -> {
                if (panel == target) target.setText(snapshot);
            });
            if (pollingHandler != null && panel != null) pollingHandler.postDelayed(this, 1500);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        nb.setContentTitle("Temp HUD running")
                .setContentText("CPU and thermal overlay is active")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentIntent(pi).setOngoing(true);
        if (Build.VERSION.SDK_INT >= 29) nb.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        startForeground(101, nb.build());

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        panel = new TextView(this);
        panel.setTextColor(Color.rgb(230,245,255));
        panel.setTextSize(12);
        panel.setTypeface(Typeface.MONOSPACE);
        panel.setPadding(dp(10), dp(8), dp(10), dp(8));
        panel.setBackgroundColor(0xE817202C);
        panel.setText("Temp HUD\nReading sensors…");
        params = new WindowManager.LayoutParams(dp(240), WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(8);
        params.y = dp(100);

        panel.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = params.x;
                    startY = params.y;
                    return true;
                }
                if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    params.x = startX + (int) (e.getRawX() - downX);
                    params.y = startY + (int) (e.getRawY() - downY);
                    try { wm.updateViewLayout(panel, params); } catch (Exception ignored) {}
                    return true;
                }
                return e.getAction() == MotionEvent.ACTION_UP;
            }
        });

        try {
            wm.addView(panel, params);
        } catch (Exception e) {
            panel = null;
            stopSelf();
            return;
        }

        // Read /proc and thermal sysfs off the UI thread to keep the HUD cheap while gaming.
        pollingThread = new HandlerThread("TempHudSensors", Process.THREAD_PRIORITY_BACKGROUND);
        pollingThread.start();
        pollingHandler = new Handler(pollingThread.getLooper());
        pollingHandler.post(ticker);
    }

    private String makeSnapshot() {
        StringBuilder out = new StringBuilder("TEMP HUD  •  LIVE\n");
        int count = 0;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/stat"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.matches("cpu[0-9]+\\s+.*")) continue;
                String[] p = line.trim().split("\\s+");
                int id = Integer.parseInt(p[0].substring(3));
                long user = val(p, 1), nice = val(p, 2), system = val(p, 3), idle = val(p, 4), wait = val(p, 5);
                long total = user + nice + system + idle + wait;
                long oldT = previousTotal != null && id < previousTotal.length ? previousTotal[id] : total;
                long oldI = previousIdle != null && id < previousIdle.length ? previousIdle[id] : idle + wait;
                long dt = total - oldT, di = (idle + wait) - oldI;
                float load = dt > 0 ? Math.max(0, Math.min(100, 100f * (dt - di) / dt)) : 0;
                ensure(id + 1);
                previousTotal[id] = total;
                previousIdle[id] = idle + wait;
                long mhz = frequency(id);
                out.append(String.format(Locale.US, "C%-2d %3.0f%%  %s  --°C\n", id, load,
                        mhz > 0 ? String.format(Locale.US, "%.2fG", mhz / 1000f) : "n/a"));
                if (++count >= 8) break;
            }
        } catch (Exception e) {
            out.append("CPU stats unavailable\n");
        }

        out.append("\nTHERMAL SENSORS\n");
        int shown = 0;
        File dir = new File("/sys/class/thermal");
        File[] zones = dir.listFiles((d, n) -> n.matches("thermal_zone[0-9]+"));
        if (zones != null) for (File zone : zones) {
            String type = read(new File(zone, "type"));
            String raw = read(new File(zone, "temp"));
            if (raw == null) continue;
            try {
                float temp = Float.parseFloat(raw.trim());
                if (Math.abs(temp) > 1000) temp /= 1000f;
                if (temp < -40 || temp > 150) continue;
                String label = type == null ? zone.getName() : type.trim();
                if (label.isEmpty()) label = zone.getName();
                out.append(String.format(Locale.US, "%.1f°C  %s\n", temp, label));
                if (++shown >= 5) break;
            } catch (NumberFormatException ignored) {}
        }
        if (shown == 0) out.append("No readable sensors\n");
        out.append("\nDrag panel to move");
        return out.toString();
    }

    private void ensure(int n) {
        if (previousTotal == null || previousTotal.length < n) {
            long[] t = new long[n], i = new long[n];
            if (previousTotal != null) {
                System.arraycopy(previousTotal, 0, t, 0, previousTotal.length);
                System.arraycopy(previousIdle, 0, i, 0, previousIdle.length);
            }
            previousTotal = t;
            previousIdle = i;
        }
    }

    private long val(String[] parts, int index) {
        try { return Long.parseLong(parts[index]); } catch (Exception e) { return 0; }
    }

    private long frequency(int id) {
        String base = "/sys/devices/system/cpu/cpu" + id + "/cpufreq/";
        for (String name : new String[]{"scaling_cur_freq", "cpuinfo_cur_freq"}) {
            String value = read(new File(base + name));
            if (value != null) try {
                long n = Long.parseLong(value.trim());
                if (n > 100000) return n / 1000;
            } catch (Exception ignored) {}
        }
        return 0;
    }

    private String read(File file) {
        try (BufferedReader r = new BufferedReader(new FileReader(file))) { return r.readLine(); }
        catch (Exception e) { return null; }
    }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "Temp HUD monitoring", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override public void onDestroy() {
        if (pollingHandler != null) pollingHandler.removeCallbacksAndMessages(null);
        if (pollingThread != null) pollingThread.quitSafely();
        if (wm != null && panel != null) try { wm.removeView(panel); } catch (Exception ignored) {}
        panel = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
