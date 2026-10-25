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

private data class OverlayTempRow(val label: String, val celsius: Float?)

class OverlayService : Service() {
    companion object {
        const val ACTION_START = "com.tempoverlay.mangohud.START"
        private const val CHANNEL = "temp_hud_monitor"
        private const val NOTIFICATION_ID = 101
        private const val BASE_WIDTH_DP = 152f
        private const val HANDLE_WIDTH_DP = 28f
        private const val HANDLE_HEIGHT_DP = 8f
        private const val ROW_HEIGHT_DP = 16.5f
    }

    private var windowManager: WindowManager? = null
    private var panel: HudView? = null
    private var handleView: DragHandleView? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var handleParams: WindowManager.LayoutParams? = null
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
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        builder.setContentTitle("Temperature overlay active")
            .setContentText("Selected CPU/GPU temperature rows are visible")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        startForeground(NOTIFICATION_ID, builder.build())

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val config = HudConfig.load(this)
        val panelView = HudView(this, config)
        val handle = DragHandleView(this)
        panel = panelView
        handleView = handle

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val panelParams = WindowManager.LayoutParams(
            dp(BASE_WIDTH_DP * config.scalePercent / 100f),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 0.79f
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            x = dp(config.positionXDp.toFloat())
            y = maxOf(dp(config.positionYDp.toFloat()), safeTopPx() + dp(HANDLE_HEIGHT_DP + 4f))
        }
        this.panelParams = panelParams

        val handleFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            if (config.lockPosition) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0
        val handleParams = WindowManager.LayoutParams(
            dp(HANDLE_WIDTH_DP), dp(HANDLE_HEIGHT_DP), type, handleFlags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 0.79f
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            x = panelParams.x + (panelParams.width - dp(HANDLE_WIDTH_DP)) / 2
            y = maxOf(safeTopPx(), panelParams.y - dp(HANDLE_HEIGHT_DP))
        }
        this.handleParams = handleParams

        try {
            windowManager?.addView(panelView, panelParams)
            windowManager?.addView(handle, handleParams)
            HudConfig.setRunning(this, true)
        } catch (_: Exception) {
            try { windowManager?.removeView(panelView) } catch (_: Exception) { }
            try { windowManager?.removeView(handle) } catch (_: Exception) { }
            panel = null
            handleView = null
            stopSelf()
            return
        }

        pollingThread = HandlerThread("TempHudThermalPoll", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        pollingHandler = Handler(pollingThread!!.looper)
        pollingHandler?.post(ticker)
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun pxToDp(value: Int): Int = (value / resources.displayMetrics.density).toInt()
    private fun safeTopPx(): Int =
        try { windowManager?.currentWindowMetrics?.windowInsets?.displayCutout?.safeInsetTop ?: 0 }
        catch (_: Exception) { 0 }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL, "Temperature overlay", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Notification for the user-started temperature overlay"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val p = panel
        if (p != null) {
            val config = HudConfig.load(this)
            val readings = sensorReader.sample(config)
            p.post { p.update(config, readings) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        pollingHandler?.removeCallbacksAndMessages(null)
        pollingThread?.quitSafely()
        try { panel?.let { windowManager?.removeView(it) } } catch (_: Exception) { }
        try { handleView?.let { windowManager?.removeView(it) } } catch (_: Exception) { }
        panel = null
        handleView = null
        HudConfig.setRunning(this, false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun rows(config: HudConfig, snapshot: TemperatureSnapshot): List<OverlayTempRow> = buildList {
        if (config.showCpuTemp) add(OverlayTempRow("CPU", snapshot.cpuOverall?.celsius))
        if (config.showGpuTemp) add(OverlayTempRow("GPU", snapshot.gpuOverall?.celsius))
        for (core in 0..7) {
            val key = "core${core}Temp"
            if (config.isEnabled(key)) add(OverlayTempRow("CPU $core", snapshot.cpuCores.getOrNull(core)?.celsius))
        }
        snapshot.gpuSensors.filter { config.isEnabled(HudConfig.GPU_SENSOR_PREFIX + it.id) }
            .forEachIndexed { index, sensor -> add(OverlayTempRow("GPU ${index + 1}", sensor.celsius)) }
    }

    private inner class HudView(context: Context, initialConfig: HudConfig) : View(context) {
        private var config = initialConfig
        private var snapshot = TemperatureSnapshot()
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private fun u(value: Float): Int = dp(value * config.scalePercent / 100f)

        fun update(nextConfig: HudConfig, nextSnapshot: TemperatureSnapshot) {
            val old = config
            val before = rows(old, snapshot)
            val after = rows(nextConfig, nextSnapshot)
            val layoutChanged = before.size != after.size || old.scalePercent != nextConfig.scalePercent
            val positionChanged = old.positionXDp != nextConfig.positionXDp || old.positionYDp != nextConfig.positionYDp
            val lockChanged = old.lockPosition != nextConfig.lockPosition
            config = nextConfig
            snapshot = nextSnapshot
            if (layoutChanged) requestLayout()

            if (lockChanged) syncHandleLock(nextConfig.lockPosition)

            val params = panelParams
            if (params != null && (layoutChanged || positionChanged)) {
                params.width = u(BASE_WIDTH_DP)
                if (positionChanged) {
                    params.x = dp(config.positionXDp.toFloat()).coerceIn(0, maxPanelX())
                    params.y = maxOf(dp(config.positionYDp.toFloat()), safeTopPx() + dp(HANDLE_HEIGHT_DP + 4f))
                }
                try { windowManager?.updateViewLayout(this, params) } catch (_: Exception) { }
                syncHandlePosition()
            }
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val count = rows(config, snapshot).size
            setMeasuredDimension(u(BASE_WIDTH_DP), u((6f + count * ROW_HEIGHT_DP).coerceAtLeast(3f)))
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (config.transparency > 0) {
                bgPaint.color = Color.argb((config.transparency * 255 / 100).coerceIn(0, 255), 7, 12, 18)
                canvas.drawRoundRect(RectF(0f, 0f, w, h), u(3f).toFloat(), u(3f).toFloat(), bgPaint)
            }
            val labelColor = HudPalette.labelColor(config.textColorIndex, config.textBrightness)
            rows(config, snapshot).forEachIndexed { index, row ->
                val baseline = u(2f + 11f + index * ROW_HEIGHT_DP).toFloat()
                val valueColor = row.celsius?.let { HudPalette.heatColor(it, config.textBrightness) }
                    ?: Color.argb((config.textBrightness * 145 / 100).coerceIn(30, 145), 145, 160, 176)
                drawText(canvas, row.label, u(1f).toFloat(), baseline, 9.4f, labelColor, Paint.Align.LEFT)
                drawText(canvas, row.celsius?.let { String.format(Locale.US, "%.1f°C", it) } ?: "N/A",
                    w - u(1f), baseline, 9.5f, valueColor, Paint.Align.RIGHT)
            }
        }

        private fun drawText(canvas: Canvas, text: String, x: Float, baseline: Float, sizeSp: Float, color: Int, align: Paint.Align) {
            textPaint.color = color
            textPaint.textSize = u(sizeSp).toFloat()
            textPaint.typeface = Typeface.create("monospace", Typeface.BOLD)
            textPaint.textAlign = align
            canvas.drawText(text, x, baseline, textPaint)
        }

        private fun maxPanelX(): Int = (resources.displayMetrics.widthPixels - width).coerceAtLeast(0)

        fun moveFromHandle(dx: Int, dy: Int) {
            if (HudConfig.load(this@OverlayService).lockPosition) return
            val params = panelParams ?: return
            params.x = (params.x + dx).coerceIn(0, maxPanelX())
            params.y = (params.y + dy).coerceAtLeast(safeTopPx() + dp(HANDLE_HEIGHT_DP + 4f))
            try { windowManager?.updateViewLayout(this, params) } catch (_: Exception) { }
            syncHandlePosition()
        }

        private fun syncHandlePosition() {
            val params = panelParams ?: return
            val hp = handleParams ?: return
            val handle = handleView ?: return
            hp.x = (params.x + (params.width - hp.width) / 2)
                .coerceIn(0, (resources.displayMetrics.widthPixels - hp.width).coerceAtLeast(0))
            hp.y = maxOf(safeTopPx(), params.y - hp.height)
            try { windowManager?.updateViewLayout(handle, hp) } catch (_: Exception) { }
        }
    }

    private fun syncHandleLock(locked: Boolean) {
        val params = handleParams ?: return
        val handle = handleView ?: return
        val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        params.flags = baseFlags or if (locked) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0
        try { windowManager?.updateViewLayout(handle, params) } catch (_: Exception) { }
    }

    private inner class DragHandleView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 205, 218, 230) }
        private var lastX = 0f
        private var lastY = 0f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val widthPx = dp(14f).toFloat()
            val heightPx = dp(1f).toFloat()
            val left = (width - widthPx) / 2f
            val top = (height - heightPx) / 2f
            canvas.drawRoundRect(RectF(left, top, left + widthPx, top + heightPx), heightPx, heightPx, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (HudConfig.load(this@OverlayService).lockPosition) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - lastX).toInt()
                    val dy = (event.rawY - lastY).toInt()
                    lastX = event.rawX
                    lastY = event.rawY
                    panel?.moveFromHandle(dx, dy)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    panelParams?.let { p ->
                        HudConfig.load(this@OverlayService).copy(
                            positionXDp = pxToDp(p.x).coerceAtLeast(0),
                            positionYDp = pxToDp(p.y).coerceAtLeast(0)
                        ).save(this@OverlayService)
                    }
                    return true
                }
            }
            return true
        }
    }
}
