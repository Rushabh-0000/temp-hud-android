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
    val coreTempsC: List<Float?> = List(8) { null },
    val gpuLoad: Float? = null,
    val gpuClockMhz: Long? = null,
    val gpuTempC: Float? = null
)

private data class HudLine(val label: String, val value: String, val cpu: Boolean)

class OverlayService : Service() {
    companion object {
        const val ACTION_START = "com.tempoverlay.mangohud.START"
        private const val CHANNEL = "temp_hud_monitor"
        private const val NOTIFICATION_ID = 101
        private const val BASE_WIDTH_DP = 224f
        private const val HEADER_DP = 27f
        private const val ROW_DP = 15.5f
        private const val BOTTOM_DP = 5f
    }

    private var windowManager: WindowManager? = null
    private var panel: HudView? = null
    private var panelParams: WindowManager.LayoutParams? = null
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
            .setContentText("CPU and GPU overlay is running")
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
        val initialConfig = HudConfig.load(this)
        val newPanel = HudView(this, initialConfig)
        panel = newPanel

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            scaledDp(BASE_WIDTH_DP, initialConfig.scalePercent),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(7f)
            y = dp(72f)
        }
        panelParams = params

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

    private fun scaledDp(value: Float, scale: Int): Int = dp(value * scale / 100f)

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

    private fun lines(config: HudConfig, metrics: HudMetrics): List<HudLine> {
        val result = ArrayList<HudLine>(14)
        if (config.showCpuLoad) result += HudLine("CPU LOAD", metrics.cpuLoad?.let { "${it.roundToInt()}%" } ?: "N/A", true)
        if (config.showCpuClock) result += HudLine("CPU CLOCK", metrics.cpuClockMhz?.let { formatClock(it) } ?: "N/A", true)
        if (config.showCpuTemp) result += HudLine("CPU TEMP", formatTemp(metrics.cpuTempC), true)
        val coreFlags = listOf(
            config.showCore0Temp, config.showCore1Temp, config.showCore2Temp, config.showCore3Temp,
            config.showCore4Temp, config.showCore5Temp, config.showCore6Temp, config.showCore7Temp
        )
        coreFlags.forEachIndexed { index, enabled ->
            if (enabled) result += HudLine("CPU $index TEMP", formatTemp(metrics.coreTempsC.getOrNull(index)), true)
        }
        if (config.showGpuLoad) result += HudLine("GPU LOAD", metrics.gpuLoad?.let { "${it.roundToInt()}%" } ?: "N/A", false)
        if (config.showGpuClock) result += HudLine("GPU CLOCK", metrics.gpuClockMhz?.let { formatClock(it) } ?: "N/A", false)
        if (config.showGpuTemp) result += HudLine("GPU TEMP", formatTemp(metrics.gpuTempC), false)
        return result
    }

    private fun formatTemp(value: Float?): String =
        value?.let { String.format(Locale.US, "%.1f°C", it) } ?: "N/A"

    private fun formatClock(mhz: Long): String =
        if (mhz >= 1000) String.format(Locale.US, "%.2f GHz", mhz / 1000f) else "$mhz MHz"

    private inner class HudView(context: Context, initialConfig: HudConfig) : View(context) {
        private var config = initialConfig
        private var metrics = HudMetrics()
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(95, 200, 220, 240)
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val mint = Color.rgb(140, 255, 208)
        private val blue = Color.rgb(143, 181, 255)
        private val white = Color.rgb(235, 242, 249)
        private val muted = Color.rgb(145, 160, 176)
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        private fun u(value: Float): Int = scaledDp(value, config.scalePercent)

        fun update(nextConfig: HudConfig, nextMetrics: HudMetrics) {
            val needsMeasure =
                selectedLineCount(config) != selectedLineCount(nextConfig) ||
                    config.scalePercent != nextConfig.scalePercent
            config = nextConfig
            metrics = nextMetrics
            if (needsMeasure) {
                requestLayout()
                panelParams?.width = u(BASE_WIDTH_DP)
                try { windowManager?.updateViewLayout(this, panelParams) } catch (_: Exception) { }
            }
            invalidate()
        }

        private fun selectedLineCount(c: HudConfig): Int =
            (if (c.showCpuLoad) 1 else 0) +
                (if (c.showCpuClock) 1 else 0) +
                (if (c.showCpuTemp) 1 else 0) +
                listOf(c.showCore0Temp, c.showCore1Temp, c.showCore2Temp, c.showCore3Temp, c.showCore4Temp, c.showCore5Temp, c.showCore6Temp, c.showCore7Temp).count { it } +
                (if (c.showGpuLoad) 1 else 0) +
                (if (c.showGpuClock) 1 else 0) +
                (if (c.showGpuTemp) 1 else 0)

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val rowCount = selectedLineCount(config)
            setMeasuredDimension(u(BASE_WIDTH_DP), u(HEADER_DP + rowCount * ROW_DP + BOTTOM_DP))
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val pad = u(9f).toFloat()
            val radius = u(7f).toFloat()
            bgPaint.color = Color.argb((config.transparency * 255 / 100).coerceIn(0, 255), 8, 14, 21)
            borderPaint.strokeWidth = u(0.8f).toFloat()
            canvas.drawRoundRect(RectF(0f, 0f, w, h), radius, radius, bgPaint)
            canvas.drawRoundRect(RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f), radius, radius, borderPaint)

            drawText(canvas, "TEMP HUD", pad, u(16f).toFloat(), 9.5f, mint, true)
            linePaint.color = Color.argb(80, 200, 220, 240)
            canvas.drawCircle(w - u(37f).toFloat(), u(12.5f).toFloat(), u(2.3f).toFloat(), linePaint)
            drawText(canvas, "LIVE", w - pad, u(16f).toFloat(), 8f, white, true, Paint.Align.RIGHT)
            canvas.drawRect(pad, u(21.5f).toFloat(), w - pad, u(22f).toFloat(), linePaint)

            val allLines = lines(config, metrics)
            allLines.forEachIndexed { index, line ->
                val baseline = u(HEADER_DP + 10.5f + index * ROW_DP).toFloat()
                val accent = if (line.cpu) mint else blue
                drawText(canvas, line.label, pad, baseline, 8.2f, muted, true)
                drawText(canvas, line.value, w - pad, baseline, 9f, if (line.value == "N/A") muted else accent, true, Paint.Align.RIGHT)
            }
        }

        private fun drawText(
            canvas: Canvas,
            text: String,
            x: Float,
            baseline: Float,
            sizeSp: Float,
            color: Int,
            bold: Boolean,
            align: Paint.Align = Paint.Align.LEFT
        ) {
            textPaint.color = color
            textPaint.textSize = u(sizeSp).toFloat()
            textPaint.typeface = Typeface.create("monospace", if (bold) Typeface.BOLD else Typeface.NORMAL)
            textPaint.textAlign = align
            canvas.drawText(text, x, baseline, textPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val params = panelParams ?: return false
            val manager = windowManager ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (event.y > u(24f)) return false
                    dragging = true
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return false
                    val maxX = (resources.displayMetrics.widthPixels - width).coerceAtLeast(0)
                    val maxY = (resources.displayMetrics.heightPixels - height).coerceAtLeast(0)
                    params.x = (startX + (event.rawX - downRawX).toInt()).coerceIn(0, maxX)
                    params.y = (startY + (event.rawY - downRawY).toInt()).coerceIn(0, maxY)
                    try { manager.updateViewLayout(this, params) } catch (_: Exception) { }
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

        private data class ThermalZone(val directory: File, val type: String)

        private val zones: List<ThermalZone> by lazy {
            File("/sys/class/thermal")
                .listFiles { _, name -> name.matches(ZONE_PATTERN) }
                ?.mapNotNull { directory ->
                    val type = readText(File(directory, "type"))?.trim()?.lowercase(Locale.US)
                    if (type.isNullOrBlank()) null else ThermalZone(directory, type)
                } ?: emptyList()
        }

        fun sample(config: HudConfig): HudMetrics {
            val needsCpuTemp = config.showCpuTemp || config.coreTempEnabled()
            val needsGpuTemp = config.showGpuTemp
            val cpuTemps = if (needsCpuTemp) cpuTemperatureReadings(config) else null
            val gpuTemp = if (needsGpuTemp) temperatureMax { type -> type.contains("gpu") || type.contains("gpuss") } else null
            return HudMetrics(
                cpuLoad = if (config.showCpuLoad) cpuUsage() else null,
                cpuClockMhz = if (config.showCpuClock) cpuClockMHz() else null,
                cpuTempC = if (config.showCpuTemp) cpuTemps?.overall else null,
                coreTempsC = cpuTemps?.perCore ?: List(8) { null },
                gpuLoad = if (config.showGpuLoad) gpuUsage() else null,
                gpuClockMhz = if (config.showGpuClock) gpuClockMHz() else null,
                gpuTempC = gpuTemp
            )
        }

        private data class CpuTemps(val overall: Float?, val perCore: List<Float?>)

        private fun cpuTemperatureReadings(config: HudConfig): CpuTemps {
            val temperatures = zones.mapNotNull { zone ->
                if (!(zone.type.contains("cpu") || zone.type.contains("cpuss"))) return@mapNotNull null
                val raw = readNumber(File(zone.directory, "temp"))?.toFloat() ?: return@mapNotNull null
                val temp = normalizeTemperature(raw) ?: return@mapNotNull null
                zone to temp
            }
            val overall = temperatures.maxOfOrNull { it.second }
            val coreValues = MutableList<Float?>(8) { null }
            if (config.coreTempEnabled()) {
                temperatures.forEach { (zone, temperature) ->
                    val index = coreIndex(zone.type) ?: return@forEach
                    val previous = coreValues[index]
                    coreValues[index] = if (previous == null) temperature else maxOf(previous, temperature)
                }
            }
            return CpuTemps(overall, coreValues)
        }

        private fun coreIndex(type: String): Int? {
            // Qualcomm CPU thermal zones often use cpu-<cluster>-<core>-usr/step.
            // On the target 8-core topology, cluster 0 maps to logical CPU 0-3
            // and cluster 1 maps to logical CPU 4-7.
            val qcom = Regex("^cpu-([0-1])-([0-3])-(?:usr|step)(?:[-_].*)?$").find(type)
            if (qcom != null) {
                val cluster = qcom.groupValues[1].toIntOrNull() ?: return null
                val localCore = qcom.groupValues[2].toIntOrNull() ?: return null
                return (cluster * 4 + localCore).takeIf { it in 0..7 }
            }
            val patterns = listOf(
                Regex("^cpu([0-7])(?:$|[-_].*)"),
                Regex("^cpu[-_]core[-_]?([0-7])(?:$|[-_].*)"),
                Regex("^cpu_core[-_]?([0-7])(?:$|[-_].*)"),
                Regex("^cpu[-_ ]([0-7])[-_ ]core(?:$|[-_].*)")
            )
            for (pattern in patterns) {
                val match = pattern.find(type) ?: continue
                return match.groupValues.getOrNull(1)?.toIntOrNull()
            }
            return null
        }

        private fun temperatureMax(matches: (String) -> Boolean): Float? =
            zones.asSequence().filter { matches(it.type) }
                .mapNotNull { zone ->
                    val raw = readNumber(File(zone.directory, "temp"))?.toFloat() ?: return@mapNotNull null
                    normalizeTemperature(raw)
                }.maxOrNull()

        private fun normalizeTemperature(raw: Float): Float? {
            val celsius = if (kotlin.math.abs(raw) > 200f) raw / 1000f else raw
            return celsius.takeIf { it in -20f..150f }
        }

        private fun cpuUsage(): Float? {
            val line = try {
                File("/proc/stat").useLines { lines -> lines.firstOrNull { it.startsWith("cpu ") } }
            } catch (_: Exception) { null } ?: return null
            val fields = line.trim().split(WHITESPACE).drop(1).mapNotNull { it.toLongOrNull() }.take(8)
            if (fields.size < 4) return null
            val total = fields.sum()
            val idle = fields.getOrElse(3) { 0L } + fields.getOrElse(4) { 0L }
            val oldTotal = previousCpuTotal
            val oldIdle = previousCpuIdle
            previousCpuTotal = total
            previousCpuIdle = idle
            if (oldTotal == null || oldIdle == null) return null
            val totalDelta = total - oldTotal
            val idleDelta = idle - oldIdle
            if (totalDelta <= 0) return null
            return (100f * (totalDelta - idleDelta).coerceAtLeast(0L) / totalDelta).coerceIn(0f, 100f)
        }

        private fun cpuClockMHz(): Long? {
            val cores = File("/sys/devices/system/cpu").listFiles { _, name -> name.matches(CPU_PATTERN) } ?: return null
            return cores.mapNotNull { core ->
                val dir = File(core, "cpufreq")
                val raw = readNumber(File(dir, "scaling_cur_freq")) ?: readNumber(File(dir, "cpuinfo_cur_freq")) ?: return@mapNotNull null
                if (raw <= 0L) null else if (raw > 100_000L) raw / 1000L else raw
            }.maxOrNull()
        }

        private fun gpuUsage(): Float? {
            val line = readText(File("/sys/class/kgsl/kgsl-3d0/gpubusy")) ?: return null
            val pair = line.trim().split(WHITESPACE).mapNotNull { it.toLongOrNull() }
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

        private fun readNumber(file: File): Long? = readText(file)?.trim()?.toLongOrNull()
        private fun readText(file: File): String? = try { file.readText().lineSequence().firstOrNull() } catch (_: Exception) { null }

        companion object {
            private val WHITESPACE = Regex("\\s+")
            private val ZONE_PATTERN = Regex("thermal_zone[0-9]+")
            private val CPU_PATTERN = Regex("cpu[0-9]+")
        }
    }
}
