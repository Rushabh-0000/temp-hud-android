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
import android.os.IBinder;
import android.os.Looper;
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
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long[] previousTotal, previousIdle;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (panel == null) return;
            panel.setText(makeSnapshot());
            handler.postDelayed(this, 1200);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        nb.setContentTitle("Temp HUD running").setContentText("CPU and thermal overlay is active")
                .setSmallIcon(android.R.drawable.ic_menu_info_details).setContentIntent(pi).setOngoing(true);
        if (Build.VERSION.SDK_INT >= 29) {
            nb.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        startForeground(101, nb.build());
        wm = (WindowManager)getSystemService(WINDOW_SERVICE);
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
        params.x = dp(8); params.y = dp(100);
        panel.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY; int startX, startY;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    downX=e.getRawX(); downY=e.getRawY(); startX=params.x; startY=params.y; return true;
                }
                if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    params.x=startX+(int)(e.getRawX()-downX); params.y=startY+(int)(e.getRawY()-downY);
                    try { wm.updateViewLayout(panel, params); } catch (Exception ignored) {}
                    return true;
                }
                return e.getAction() == MotionEvent.ACTION_UP;
            }
        });
        try { wm.addView(panel, params); } catch (Exception e) { stopSelf(); return; }
        ticker.run();
    }

    private String makeSnapshot() {
        StringBuilder out = new StringBuilder("TEMP HUD  •  LIVE\n");
        int count = 0;
        File cpu = new File("/proc/stat");
        try (BufferedReader r = new BufferedReader(new FileReader(cpu))) {
            String line;
            while ((line=r.readLine()) != null) {
                if (!line.matches("cpu[0-9]+\\s+.*")) continue;
                String[] p=line.trim().split("\\s+");
                int id=Integer.parseInt(p[0].substring(3));
                long user=val(p,1), nice=val(p,2), system=val(p,3), idle=val(p,4), wait=val(p,5);
                long total=user+nice+system+idle+wait;
                long oldT=previousTotal!=null && id<previousTotal.length ? previousTotal[id] : total;
                long oldI=previousIdle!=null && id<previousIdle.length ? previousIdle[id] : idle+wait;
                long dt=total-oldT, di=(idle+wait)-oldI;
                float load=dt>0 ? Math.max(0, Math.min(100, 100f*(dt-di)/dt)) : 0;
                ensure(id+1);
                previousTotal[id]=total; previousIdle[id]=idle+wait;
                long mhz=frequency(id);
                out.append(String.format(Locale.US,"C%-2d %3.0f%%  %s  --°C\n",id,load,mhz>0?String.format(Locale.US,"%.2fG",mhz/1000f):"n/a"));
                count++;
                if(count>=8) break;
            }
        } catch (Exception e) { out.append("CPU stats unavailable\n"); }
        out.append("\nTHERMAL SENSORS\n");
        int shown=0;
        File dir=new File("/sys/class/thermal");
        File[] zones=dir.listFiles((d,n)->n.matches("thermal_zone[0-9]+"));
        if(zones!=null) for(File z:zones) {
            String type=read(new File(z,"type")), raw=read(new File(z,"temp"));
            if(raw==null) continue;
            try {
                float t=Float.parseFloat(raw.trim()); if(Math.abs(t)>1000) t/=1000f;
                if(t < -40 || t > 150) continue;
                String label=type==null?z.getName():type.trim();
                if(label.isEmpty()) label=z.getName();
                out.append(String.format(Locale.US,"%.1f°C  %s\n",t,label));
                if(++shown>=5) break;
            } catch(NumberFormatException ignored) {}
        }
        if(shown==0) out.append("No readable sensors\n");
        out.append("\nDrag panel to move");
        return out.toString();
    }
    private void ensure(int n) { if(previousTotal==null || previousTotal.length<n) { long[] t=new long[n], i=new long[n]; if(previousTotal!=null){System.arraycopy(previousTotal,0,t,0,previousTotal.length);System.arraycopy(previousIdle,0,i,0,previousIdle.length);} previousTotal=t; previousIdle=i; } }
    private long val(String[] p,int i){ try{return Long.parseLong(p[i]);}catch(Exception e){return 0;} }
    private long frequency(int i) {
        String base="/sys/devices/system/cpu/cpu"+i+"/cpufreq/";
        for(String f:new String[]{"scaling_cur_freq","cpuinfo_cur_freq"}) {
            String s=read(new File(base+f)); if(s!=null) try { long n=Long.parseLong(s.trim()); if(n>100000) return n/1000; } catch(Exception ignored) {}
        }
        return 0;
    }
    private String read(File f){ try(BufferedReader r=new BufferedReader(new FileReader(f))){return r.readLine();}catch(Exception e){return null;} }
    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
    private void createChannel(){ if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(CHANNEL,"Temp HUD monitoring",NotificationManager.IMPORTANCE_LOW); getSystemService(NotificationManager.class).createNotificationChannel(c);} }
    @Override public int onStartCommand(Intent i,int flags,int id){ return START_STICKY; }
    @Override public void onDestroy(){ handler.removeCallbacks(ticker); if(wm!=null && panel!=null) try{wm.removeView(panel);}catch(Exception ignored){} super.onDestroy(); }
    @Override public IBinder onBind(Intent i){return null;}
}
