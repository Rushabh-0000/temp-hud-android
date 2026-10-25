package com.tempoverlay.mangohud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.util.Locale

class OverlayService : Service() {
    companion object {
        private const val CHANNEL = "temp_hud_monitor"
        private const val NOTIFICATION_ID = 101
        private const val POLL_INTERVAL_MS = 1500L
    }

    private var windowManager: WindowManager? = null
    private var panel: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var pollingThread: HandlerThread? = null
    private var pollingHandler: Handler? = null
    private var previousTotal: LongArray? = null
    private var previousIdle: LongArray? = null

    private val ticker = object : Runnable {
        override fun run() {
            val target = panel ?: return
            val handler = pollingHandler ?: return
            val snapshot = makeSnapshot()
            target.post {
                if (panel === target) target.text = snapshot
            }
            if (panel != null) handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val openApp = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openApp,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notificationBuilder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        notificationBuilder
            .setContentTitle("Temp HUD running")
            .setContentText("CPU and thermal overlay is active")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
        if (Build.VERSION.SDK_INT >= 29) {
            notificationBuilder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        startForeground(NOTIFICATION_ID, notificationBuilder.build())

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val newPanel = TextView(this).apply {
            setTextColor(Color.rgb(230, 245, 255))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            setBackgroundColor(0xE817202C.toInt())
            text = "TEMP HUD\nReading sensors…"
        }
        panel = newPanel

        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            dp(240f),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8f)
            y = dp(100f)
        }
        layoutParams = params

        newPanel.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = event.rawX
                    touchDownY = event.rawY
                    touchStartX = params.x
                    touchStartY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = touchStartX + (event.rawX - touchDownX).toInt()
                    params.y = touchStartY + (event.rawY - touchDownY).toInt()
                    try {
                        windowManager?.updateViewLayout(newPanel, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        try {
            windowManager?.addView(newPanel, params)
        } catch (_: Exception) {
            panel = null
            stopSelf()
            return
        }

        // Procfs/sysfs polling is done on a low-priority background thread, not the UI thread.
        pollingThread = HandlerThread("TempHudSensors", Process.THREAD_PRIORITY_BACKGROUND).also {
            it.start()
        }
        pollingHandler = Handler(pollingThread!!.looper)
        pollingHandler?.post(ticker)
    }

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchStartX = 0
    private var touchStartY = 0

    private fun makeSnapshot(): String {
        val out = StringBuilder("TEMP HUD  •  LIVE\n")
        var count = 0
        try {
            BufferedReader(FileReader("/proc/stat")).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    if (!line.matches(Regex("cpu[0-9]+\\s+.*"))) {
                        line = reader.readLine()
                        continue
                    }
                    val parts = line.trim().split(Regex("\\s+"))
                    val id = parts[0].substring(3).toInt()
                    val user = value(parts, 1)
                    val nice = value(parts, 2)
                    val system = value(parts, 3)
                    val idle = value(parts, 4)
                    val wait = value(parts, 5)
                    val total = user + nice + system + idle + wait
                    val oldTotal = previousTotal?.getOrNull(id) ?: total
                    val oldIdle = previousIdle?.getOrNull(id) ?: (idle + wait)
                    val totalDelta = total - oldTotal
                    val idleDelta = (idle + wait) - oldIdle
                    val load = if (totalDelta > 0) {
                        (100f * (totalDelta - idleDelta) / totalDelta).coerceIn(0f, 100f)
                    } else {
                        0f
                    }

                    ensureCapacity(id + 1)
                    previousTotal!![id] = total
                    previousIdle!![id] = idle + wait

                    val mhz = frequency(id)
                    val clock = if (mhz > 0) String.format(Locale.US, "%.2fG", mhz / 1000f) else "n/a"
                    out.append(String.format(Locale.US, "C%-2d %3.0f%%  %s  --°C\n", id, load, clock))
                    count++
                    if (count >= 8) break
                    line = reader.readLine()
                }
            }
        } catch (_: Exception) {
            out.append("CPU stats unavailable\n")
        }

        out.append("\nTHERMAL SENSORS\n")
        var shown = 0
        val thermalDirectory = File("/sys/class/thermal")
        val zones = thermalDirectory.listFiles { _, name ->
            name.matches(Regex("thermal_zone[0-9]+"))
        } ?: emptyArray()

        for (zone in zones) {
            val type = read(File(zone, "type"))
            val raw = read(File(zone, "temp")) ?: continue
            try {
                var temperature = raw.trim().toFloat()
                if (kotlin.math.abs(temperature) > 1000f) temperature /= 1000f
                if (temperature < -40f || temperature > 150f) continue
                val label = type?.trim()?.takeIf { it.isNotEmpty() } ?: zone.name
                out.append(String.format(Locale.US, "%.1f°C  %s\n", temperature, label))
                shown++
                if (shown >= 5) break
            } catch (_: NumberFormatException) {
            }
        }

        if (shown == 0) out.append("No readable sensors\n")
        out.append("\nDrag panel to move")
        return out.toString()
    }

    private fun ensureCapacity(size: Int) {
        if (previousTotal == null || previousTotal!!.size < size) {
            val oldTotal = previousTotal
            val oldIdle = previousIdle
            previousTotal = LongArray(size).also { oldTotal?.copyInto(it) }
            previousIdle = LongArray(size).also { oldIdle?.copyInto(it) }
        }
    }

    private fun value(parts: List<String>, index: Int): Long =
        parts.getOrNull(index)?.toLongOrNull() ?: 0L

    private fun frequency(core: Int): Long {
        val base = "/sys/devices/system/cpu/cpu$core/cpufreq/"
        for (name in listOf("scaling_cur_freq", "cpuinfo_cur_freq")) {
            val value = read(File(base + name))?.trim()?.toLongOrNull() ?: continue
            if (value > 100_000L) return value / 1000L
        }
        return 0L
    }

    private fun read(file: File): String? =
        try {
            BufferedReader(FileReader(file)).use { it.readLine() }
        } catch (_: Exception) {
            null
        }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL,
                "Temp HUD monitoring",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        pollingHandler?.removeCallbacksAndMessages(null)
        pollingThread?.quitSafely()
        val oldPanel = panel
        if (windowManager != null && oldPanel != null) {
            try {
                windowManager?.removeView(oldPanel)
            } catch (_: Exception) {
            }
        }
        panel = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

}
