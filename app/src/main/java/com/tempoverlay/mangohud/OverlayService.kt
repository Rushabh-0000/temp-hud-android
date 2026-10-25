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
import java.util.Locale

private data class OverlayTempRow(val key: String, val label: String, val celsius: Float?)

class OverlayService : Service() {
    companion object {
        const val ACTION_START = "com.tempoverlay.mangohud.START"
        private const val CHANNEL = "temp_hud_monitor"
        private const val NOTIFICATION_ID = 101
        private const val BASE_WIDTH_DP = 152f
        private const val HANDLE_HEIGHT_DP = 8f
        private const val ROW_HEIGHT_DP = 16.5f
        private const val BOTTOM_DP = 4f
    }

    private var windowManager: WindowManager? = null
    private var panel: HudView? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var pollingThread: HandlerThread? = null
    private var pollingHandler: Handler? = null
    private val sensorReader = SensorReader()

    private val ticker = object : Runnable {
        override fun run() {
            val target = panel ?: return
            val handler = pollingHandler ?: return
            val config = HudConfig.load(this@OverlayService)
            val readings = sensorReader.sample(config)
            target.post { if (panel === target) target.update(config, readings) }
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
        builder.setContentTitle("Temperature overlay active")
            .setContentText("Selected CPU/GPU temperatures are visible")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
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
        val config = HudConfig.load(this)
        val view = HudView(this, config)
        panel = view

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            dp(BASE_WIDTH_DP * config.scalePercent / 100f),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            x = dp(config.positionXDp.toFloat())
            y = maxOf(dp(config.positionYDp.toFloat()), safeTopPx() + dp(3f))
        }
        panelParams = params

        try {
            windowManager?.addView(view, params)
            HudConfig.setRunning(this, true)
        } catch (_: Exception) {
            panel = null
            stopSelf()
            return
        }

        pollingThread = HandlerThread("TempHudThermalPoll", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        pollingHandler = Handler(pollingThread!!.looper)
        pollingHandler?.post(ticker)
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun dpToPx(value: Int): Int = dp(value.toFloat())
    private fun pxToDp(value: Int): Int = (value / resources.displayMetrics.density).toInt()

    private fun safeTopPx(): Int =
        try {
            windowManager?.currentWindowMetrics?.windowInsets?.displayCutout?.safeInsetTop ?: 0
        } catch (_: Exception) { 0 }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL, "Temperature overlay", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Notification for the user-started temperature overlay"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        pollingHandler?.removeCallbacksAndMessages(null)
        pollingThread?.quitSafely()
        try { panel?.let { windowManager?.removeView(it) } } catch (_: Exception) { }
        panel = null
        HudConfig.setRunning(this, false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createRows(config: HudConfig, readings: TemperatureSnapshot): List<OverlayTempRow> = buildList {
        if (config.showCpuTemp) add(OverlayTempRow("cpuTemp", "CPU", readings.cpuOverallC))
        val selectedCores = listOf(
            config.showCore0Temp to 0, config.showCore1Temp to 1,
            config.showCore2Temp to 2, config.showCore3Temp to 3,
            config.showCore4Temp to 4, config.showCore5Temp to 5,
            config.showCore6Temp to 6, config.showCore7Temp to 7
        )
        selectedCores.forEach { (enabled, index) ->
            if (enabled) add(OverlayTempRow("core${index}Temp", "CPU $index", readings.coreTempsC.getOrNull(index)))
        }
        if (config.showGpuTemp) add(OverlayTempRow("gpuTemp", "GPU", readings.gpuOverallC))
    }

    private inner class HudView(context: Context, initialConfig: HudConfig) : View(context) {
        private var config = initialConfig
        private var readings = TemperatureSnapshot()
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bgBase = Color.rgb(7, 12, 18)
        private var downRawX = 0f
        private var downRawY = 0f
        private var dragStartX = 0
        private var dragStartY = 0
        private var dragging = false

        private fun u(value: Float): Int = dp(value * config.scalePercent / 100f)

        fun update(nextConfig: HudConfig, nextReadings: TemperatureSnapshot) {
            val old = config
            val layoutChanged = old.selectedCount() != nextConfig.selectedCount() ||
                old.scalePercent != nextConfig.scalePercent
            val positionChanged = old.positionXDp != nextConfig.positionXDp ||
                old.positionYDp != nextConfig.positionYDp
            config = nextConfig
            readings = nextReadings
            if (layoutChanged) requestLayout()
            if (layoutChanged || positionChanged) {
                panelParams?.let { params ->
                    params.width = u(BASE_WIDTH_DP)
                    if (positionChanged) {
                        params.x = dpToPx(config.positionXDp).coerceAtLeast(0)
                        params.y = maxOf(dpToPx(config.positionYDp), safeTopPx() + dp(3f))
                    }
                    try { windowManager?.updateViewLayout(this, params) } catch (_: Exception) { }
                }
            }
            invalidate()
        }

        private fun rows(): List<OverlayTempRow> = createRows(config, readings)

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val height = HANDLE_HEIGHT_DP + rows().size * ROW_HEIGHT_DP + BOTTOM_DP
            setMeasuredDimension(u(BASE_WIDTH_DP), u(height))
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val radius = u(4.5f).toFloat()
            val padding = u(8f).toFloat()
            bgPaint.color = Color.argb((config.transparency * 255 / 100).coerceIn(0, 255), Color.red(bgBase), Color.green(bgBase), Color.blue(bgBase))
            borderPaint.color = Color.argb((config.transparency * 170 / 100).coerceIn(0, 170), 170, 192, 214)
            borderPaint.strokeWidth = u(0.6f).toFloat()
            canvas.drawRoundRect(RectF(0f, 0f, w, h), radius, radius, bgPaint)
            canvas.drawRoundRect(RectF(0.4f, 0.4f, w - 0.4f, h - 0.4f), radius, radius, borderPaint)

            // Tiny neutral handle; no app name/title or LIVE badge in the HUD.
            handlePaint.color = Color.argb((config.textBrightness * 110 / 100).coerceIn(20, 110), 210, 220, 230)
            canvas.drawRoundRect(
                RectF(w / 2f - u(8f), u(3.5f).toFloat(), w / 2f + u(8f), u(4.5f).toFloat()),
                u(1f).toFloat(), u(1f).toFloat(), handlePaint
            )

            val rows = rows()
            rows.forEachIndexed { index, row ->
                val baseline = u(HANDLE_HEIGHT_DP + 10.6f + index * ROW_HEIGHT_DP).toFloat()
                val labelColor = HudPalette.labelColor(config.textColorIndex, config.textBrightness)
                val valueColor = row.celsius?.let { HudPalette.heatColor(it, config.textBrightness) }
                    ?: Color.argb((config.textBrightness * 145 / 100).coerceIn(35, 145), 145, 160, 176)
                drawText(canvas, row.label, padding, baseline, 9.5f, labelColor, Paint.Align.LEFT)
                val value = row.celsius?.let { String.format(Locale.US, "%.1f°C", it) } ?: "N/A"
                drawText(canvas, value, w - padding, baseline, 10f, valueColor, Paint.Align.RIGHT)
            }
        }

        private fun drawText(canvas: Canvas, text: String, x: Float, baseline: Float, sizeSp: Float, color: Int, align: Paint.Align) {
            textPaint.color = color
            textPaint.textSize = u(sizeSp).toFloat()
            textPaint.typeface = Typeface.create("monospace", Typeface.BOLD)
            textPaint.textAlign = align
            canvas.drawText(text, x, baseline, textPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val params = panelParams ?: return false
            val manager = windowManager ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (config.lockPosition || event.y > u(HANDLE_HEIGHT_DP)) return false
                    dragging = true
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragStartX = params.x
                    dragStartY = params.y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging || config.lockPosition) return false
                    val maxX = (resources.displayMetrics.widthPixels - width).coerceAtLeast(0)
                    val maxY = (resources.displayMetrics.heightPixels - height).coerceAtLeast(0)
                    params.x = (dragStartX + (event.rawX - downRawX).toInt()).coerceIn(0, maxX)
                    params.y = (dragStartY + (event.rawY - downRawY).toInt()).coerceIn(safeTopPx() + dp(3f), maxY)
                    try { manager.updateViewLayout(this, params) } catch (_: Exception) { }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasDragging = dragging
                    dragging = false
                    if (wasDragging) {
                        HudConfig.load(this@OverlayService).copy(
                            positionXDp = pxToDp(params.x).coerceAtLeast(0),
                            positionYDp = pxToDp(params.y).coerceAtLeast(0)
                        ).save(this@OverlayService)
                    }
                    return wasDragging
                }
            }
            return dragging
        }
    }
}
