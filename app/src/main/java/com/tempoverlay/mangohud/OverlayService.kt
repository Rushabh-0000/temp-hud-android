package com.tempoverlay.mangohud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

private data class HudMetrics(
    val cpuLoad: Float? = null,
    val cpuClockMhz: Long? = null,
    val cpuTempC: Float? = null,
    val gpuLoad: Float? = null,
    val gpuClockMhz: Long? = null,
    val gpuTempC: Float? = null
)

class OverlayService : Service() {
    companion object {
        const val ACTION_START = "com.tempoverlay.mangohud.START"
        private const val CHANNEL = "temp_hud_monitor"
        private const val NOTIFICATION_ID = 101
    }

    private var windowManager: WindowManager? = null
    private var panel: HudView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var pollingThread: HandlerThread? = null
    private var pollingHandler: Handler? = null
    private val telemetry = TelemetryReader()

    private val ticker = object : Runnable {
        override fun run() {
            val target = panel ?: return
            val handler = pollingHandler ?: return
            val config = HudConfig.load(this@OverlayService)
            val metrics = telemetry.sample(config)
            target.post { if (panel === target) target.update(config, metrics) }
            handler.postDelayed(this, config.refreshMs.toLong())
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        builder.setContentTitle("Temp HUD active")
            .setContentText("CPU / GPU overlay is running")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        startForeground(NOTIFICATION_ID, builder.build())

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val newPanel = HudView(this, HudConfig.load(this))
        panel = newPanel
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            dp(282f), WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(10f)
            y = dp(92f)
        }
        layoutParams = params

        try {
            windowManager?.addView(newPanel, params)
            HudConfig.setRunning(this, true)
        } catch (_: Exception) {
            panel = null
            stopSelf()
            return
        }

        pollingThread = HandlerThread("TempHudPoll", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        pollingHandler = Handler(pollingThread!!.looper)
        pollingHandler?.post(ticker)
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL, "Temp HUD", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Notification for the user-started performance overlay"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        pollingHandler?.removeCallbacksAndMessages(null)
        pollingThread?.quitSafely()
        val oldPanel = panel
        if (oldPanel != null) {
            try {
                windowManager?.removeView(oldPanel)
            } catch (_: Exception) {
            }
        }
        panel = null
        HudConfig.setRunning(this, false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private inner class HudView(context: Context, initialConfig: HudConfig) : View(context) {
        private var config = initialConfig
        private var metrics = HudMetrics()
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1f).toFloat()
            color = Color.argb(85, 205, 225, 245)
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val mint = Color.rgb(140, 255, 208)
        private val blue = Color.rgb(145, 185, 255)
        private val softWhite = Color.rgb(233, 241, 248)
        private val muted = Color.rgb(137, 153, 169)
        private val pad = dp(12f).toFloat()
        private val radius = dp(10f).toFloat()
        private var dragX = 0f
        private var dragY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        fun update(nextConfig: HudConfig, nextMetrics: HudMetrics) {
            config = nextConfig
            metrics = nextMetrics
            requestLayout()
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val count = (if (config.showCpu) 1 else 0) + (if (config.showGpu) 1 else 0)
            setMeasuredDimension(dp(282f), dp((40 + 56 * count + 16).toFloat()))
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            bgPaint.color = Color.argb((config.transparency * 255 / 100).coerceIn(0, 255), 9, 15, 22)
            canvas.drawRoundRect(RectF(0f, 0f, w, h), radius, radius, bgPaint)
            canvas.drawRoundRect(
                RectF(dp(0.5f).toFloat(), dp(0.5f).toFloat(), w - dp(0.5f), h - dp(0.5f)),
                radius, radius, borderPaint
            )
            drawText(canvas, "TEMP HUD", pad, dp(20f).toFloat(), 12f, mint, true)
            canvas.drawCircle(w - dp(52f).toFloat(), dp(16f).toFloat(), dp(3f).toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mint })
            drawText(canvas, "LIVE", w - pad, dp(20f).toFloat(), 9f, softWhite, true, Paint.Align.RIGHT)
            barPaint.color = Color.argb(65, 205, 225, 245)
            canvas.drawRect(pad, dp(29f).toFloat(), w - pad, dp(29.6f).toFloat(), barPaint)

            var rowTop = dp(35f).toFloat()
            if (config.showCpu) {
                drawMetric(canvas, rowTop, "CPU", metrics.cpuLoad, metrics.cpuClockMhz, metrics.cpuTempC, mint)
                rowTop += dp(56f)
            }
            if (config.showGpu) {
                drawMetric(canvas, rowTop, "GPU", metrics.gpuLoad, metrics.gpuClockMhz, metrics.gpuTempC, blue)
            }
            if (!config.showCpu && !config.showGpu) drawText(canvas, "Select CPU or GPU in app", pad, dp(52f).toFloat(), 10f, muted, false)
            drawText(canvas, "DRAG TOP STRIP TO MOVE", pad, h - dp(6f), 8f, muted, false)
        }

        private fun drawMetric(
            canvas: Canvas, top: Float, label: String, load: Float?, clockMhz: Long?,
            temperatureC: Float?, accent: Int
        ) {
            val right = width.toFloat() - pad
            drawText(canvas, label, pad, top + dp(10f), 9f, muted, true)
            val temp = temperatureC?.let { String.format(Locale.US, "%.1f°C", it) } ?: "N/A"
            drawText(canvas, "TEMP $temp", right, top + dp(10f), 9f, softWhite, true, Paint.Align.RIGHT)
            drawText(canvas, load?.let { "${it.roundToInt()}%" } ?: "N/A", pad, top + dp(29f), 17f, softWhite, true)
            val clock = clockMhz?.let {
                if (it >= 1000) String.format(Locale.US, "CLK %.2f GHz", it / 1000f) else "CLK $it MHz"
            } ?: "CLK N/A"
            drawText(canvas, clock, pad + dp(54f), top + dp(28f), 9f, muted, false)

            val barTop = top + dp(37f)
            val barBottom = barTop + dp(4f)
            barPaint.color = Color.argb(68, 180, 200, 220)
            canvas.drawRoundRect(RectF(pad, barTop, right, barBottom), dp(2f).toFloat(), dp(2f).toFloat(), barPaint)
            if (load != null) {
                barPaint.color = accent
                val end = pad + (right - pad) * (load.coerceIn(0f, 100f) / 100f)
                canvas.drawRoundRect(RectF(pad, barTop, end.coerceAtLeast(pad + dp(2f)), barBottom), dp(2f).toFloat(), dp(2f).toFloat(), barPaint)
            }
        }

        private fun drawText(
            canvas: Canvas, value: String, x: Float, y: Float, sizeSp: Float,
            color: Int, bold: Boolean, align: Paint.Align = Paint.Align.LEFT
        ) {
            textPaint.color = color
            textPaint.textSize = dp(sizeSp).toFloat()
            textPaint.typeface = Typeface.create("monospace", if (bold) Typeface.BOLD else Typeface.NORMAL)
            textPaint.textAlign = align
            canvas.drawText(value, x, y, textPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val params = this@OverlayService.layoutParams ?: return false
            val manager = this@OverlayService.windowManager ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (event.y > dp(30f)) return false
                    dragging = true
                    dragX = event.rawX
                    dragY = event.rawY
                    startX = params.x
                    startY = params.y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return false
                    val maxX = (resources.displayMetrics.widthPixels - width).coerceAtLeast(0)
                    val maxY = (resources.displayMetrics.heightPixels - height).coerceAtLeast(0)
                    params.x = (startX + (event.rawX - dragX).toInt()).coerceIn(0, maxX)
                    params.y = (startY + (event.rawY - dragY).toInt()).coerceIn(0, maxY)
                    try {
                        manager.updateViewLayout(this, params)
                    } catch (_: Exception) {
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasDragging = dragging
                    dragging = false
                    return wasDragging
                }
            }
            return dragging
        }
    }

    private class TelemetryReader {
        private var previousCpuTotal: Long? = null
        private var previousCpuIdle: Long? = null
        private var previousGpuBusy: Long? = null
        private var previousGpuTotal: Long? = null

        private data class Zone(val directory: File, val type: String)
        private val zones: List<Zone> by lazy {
            File("/sys/class/thermal").listFiles { _, name -> name.matches(Regex("thermal_zone[0-9]+")) }
                ?.mapNotNull { directory ->
                    val type = readLine(File(directory, "type"))?.trim()?.lowercase(Locale.US)
                    if (type.isNullOrBlank()) null else Zone(directory, type)
                } ?: emptyList()
        }

        fun sample(config: HudConfig) = HudMetrics(
            cpuLoad = if (config.showCpu) cpuUsage() else null,
            cpuClockMhz = if (config.showCpu) cpuClockMHz() else null,
            cpuTempC = if (config.showCpu) temperature("cpu") else null,
            gpuLoad = if (config.showGpu) gpuUsage() else null,
            gpuClockMhz = if (config.showGpu) gpuClockMHz() else null,
            gpuTempC = if (config.showGpu) temperature("gpu") else null
        )

        private fun cpuUsage(): Float? {
            val line = try {
                File("/proc/stat").useLines { lines -> lines.firstOrNull { it.startsWith("cpu ") } }
            } catch (_: Exception) { null } ?: return null
            val fields = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }.take(8)
            if (fields.size < 4) return null
            val total = fields.sum()
            val idle = fields.getOrElse(3) { 0L } + fields.getOrElse(4) { 0L }
            val oldTotal = previousCpuTotal
            val oldIdle = previousCpuIdle
            previousCpuTotal = total
            previousCpuIdle = idle
            if (oldTotal == null || oldIdle == null) return null
            val deltaTotal = total - oldTotal
            val deltaIdle = idle - oldIdle
            if (deltaTotal <= 0) return null
            return (100f * (deltaTotal - deltaIdle).coerceAtLeast(0L) / deltaTotal).coerceIn(0f, 100f)
        }

        private fun cpuClockMHz(): Long? {
            val cores = File("/sys/devices/system/cpu").listFiles { _, name -> name.matches(Regex("cpu[0-9]+")) } ?: return null
            return cores.mapNotNull { core ->
                val dir = File(core, "cpufreq")
                val raw = readNumber(File(dir, "scaling_cur_freq")) ?: readNumber(File(dir, "cpuinfo_cur_freq")) ?: return@mapNotNull null
                if (raw <= 0L) null else if (raw > 100_000L) raw / 1000L else raw
            }.maxOrNull()
        }

        private fun gpuUsage(): Float? {
            val line = readLine(File("/sys/class/kgsl/kgsl-3d0/gpubusy")) ?: return null
            val pair = line.trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
            if (pair.size < 2) return null
            val busy = pair[0]
            val total = pair[1]
            val oldBusy = previousGpuBusy
            val oldTotal = previousGpuTotal
            previousGpuBusy = busy
            previousGpuTotal = total
            if (oldBusy == null || oldTotal == null) return null
            val busyDelta = busy - oldBusy
            val totalDelta = total - oldTotal
            if (busyDelta < 0L || totalDelta <= 0L) return null
            return (100f * busyDelta / totalDelta).coerceIn(0f, 100f)
        }

        private fun gpuClockMHz(): Long? {
            val files = mutableListOf(
                File("/sys/class/kgsl/kgsl-3d0/gpuclk"),
                File("/sys/class/devfreq/3d00000.gpu/cur_freq"),
                File("/sys/class/devfreq/57000000.gpu/cur_freq")
            )
            File("/sys/class/devfreq").listFiles()
                ?.filter { it.name.contains("gpu", ignoreCase = true) }
                ?.forEach { files.add(File(it, "cur_freq")) }
            for (file in files) {
                val raw = readNumber(file) ?: continue
                if (raw <= 0L) continue
                return when {
                    raw >= 10_000_000L -> raw / 1_000_000L
                    raw >= 100_000L -> raw / 1000L
                    else -> raw
                }
            }
            return null
        }

        private fun temperature(kind: String): Float? {
            return zones.asSequence().filter { zone ->
                when (kind) {
                    "cpu" -> zone.type.contains("cpu") && !zone.type.contains("gpu")
                    "gpu" -> zone.type.contains("gpu")
                    else -> false
                }
            }.mapNotNull { zone ->
                val raw = readNumber(File(zone.directory, "temp"))?.toFloat() ?: return@mapNotNull null
                val celsius = if (kotlin.math.abs(raw) > 200f) raw / 1000f else raw
                celsius.takeIf { it in -20f..150f }
            }.maxOrNull()
        }

        private fun readNumber(file: File): Long? = readLine(file)?.trim()?.toLongOrNull()
        private fun readLine(file: File): String? = try {
            file.readText().lineSequence().firstOrNull()
        } catch (_: Exception) { null }
    }
}
