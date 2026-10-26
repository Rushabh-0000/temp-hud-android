package com.tempoverlay.mangohud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import java.util.Locale

private data class OverlayRow(val label: String, val reading: ThermalReading?)

class OverlayService : Service() {
    companion object {
        const val ACTION_START = "com.tempoverlay.mangohud.START"
        const val ACTION_SNAPSHOT = "com.tempoverlay.mangohud.SNAPSHOT"
        const val EXTRA_SNAPSHOT = "snapshot_json"
        const val ACTION_CATALOG = "com.tempoverlay.mangohud.CATALOG"
        const val EXTRA_CATALOG = "catalog_requested"
        const val EXTRA_CATALOG_COMPLETE = "catalog_complete"
        @Volatile private var snapshotListenerActive = false
        fun setSnapshotListenerActive(active: Boolean) { snapshotListenerActive = active }

        private const val CHANNEL = "temp_hud_monitor"
        private const val NOTIFICATION_ID = 101
        private const val ROW_HEIGHT_DP = 15.5f
        private const val HORIZONTAL_PADDING_DP = 2.5f
        private const val VALUE_GAP_DP = 4f
        private const val MIN_WIDTH_DP = 22f
    }

    private var windowManager: WindowManager? = null
    private var hudView: HudView? = null
    private var hudWindowParams: WindowManager.LayoutParams? = null
    private var pollingThread: HandlerThread? = null
    private var pollingHandler: Handler? = null
    private val sensorReader = SensorReader()
    @Volatile private var catalogRequested = false
    @Volatile private var pollingStarted = false

    private val ticker = object : Runnable {
        override fun run() {
            val target = hudView ?: return
            val handler = pollingHandler ?: return
            if (!pollingStarted) return

            val startedAt = SystemClock.uptimeMillis()
            val sampleConfig = HudConfig.load(this@OverlayService)
            val snapshot = try {
                sensorReader.sample(sampleConfig, includeAllForSettings = catalogRequested)
            } catch (_: Exception) {
                // One unreadable sysfs zone must not kill the entire polling loop.
                TemperatureSnapshot()
            }

            // Teardown can race a sysfs read; do not publish or queue more work after stop.
            if (!pollingStarted || hudView !== target || pollingHandler !== handler) return

            target.post {
                if (pollingStarted && hudView === target) {
                    target.update(HudConfig.load(this@OverlayService), snapshot)
                }
            }
            if (snapshotListenerActive) {
                sendBroadcast(
                    Intent(ACTION_SNAPSHOT)
                        .setPackage(packageName)
                        .putExtra(EXTRA_SNAPSHOT, snapshot.toJson())
                        .putExtra(EXTRA_CATALOG_COMPLETE, catalogRequested)
                )
            }

            val elapsedMs = SystemClock.uptimeMillis() - startedAt
            // Bounded start-to-start polling avoids busy loops; sysfs reads stay off main.
            if (pollingStarted && hudView === target && pollingHandler === handler) {
                handler.postDelayed(this, (sampleConfig.refreshMs.toLong() - elapsedMs).coerceAtLeast(250L))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notificationIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        builder.setContentTitle("Temperature overlay active")
            .setContentText("Selected temperature rows are visible")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(notificationIntent)
            .setOngoing(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        startForeground(NOTIFICATION_ID, builder.build())

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val config = HudConfig.load(this)
        val view = HudView(this, config)
        hudView = view
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            makeFlags(config.lockPosition),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = overlayWindowAlpha(config.lockPosition)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            x = dp(config.positionXDp.toFloat())
            y = maxOf(dp(config.positionYDp.toFloat()), safeTopPx() + dp(4f))
        }
        hudWindowParams = params

        try {
            windowManager?.addView(view, params)
            HudConfig.setRunning(true)
        } catch (_: Exception) {
            hudView = null
            stopSelf()
            return
        }

        // The Activity and service intentionally share one process to avoid duplicating
        // the Android runtime. The Activity stops its preview sampler while this service runs.
        pollingThread = HandlerThread("TempHudThermalPoll", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        pollingHandler = Handler(pollingThread!!.looper)
        // Start the ticker only from the first ACTION_START. Posting it here too can race
        // with onStartCommand and queue two polling loops on first launch.
    }

    private fun makeFlags(locked: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (locked) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return flags
    }

    private fun overlayWindowAlpha(locked: Boolean): Float {
        if (!locked || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 1f
        // Android 12+ restricts touch pass-through through FLAG_NOT_TOUCHABLE overlays.
        // Respect the device's actual obscuring-opacity limit; keep unlocked text at full opacity.
        val systemLimit = try {
            getSystemService(android.hardware.input.InputManager::class.java)
                ?.maximumObscuringOpacityForTouch ?: 0.8f
        } catch (_: Exception) {
            0.8f
        }
        return systemLimit.coerceIn(0f, 0.8f)
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun pxToDp(value: Int): Int = (value / resources.displayMetrics.density).toInt()

    private fun safeTopPx(): Int = try {
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CATALOG) {
            catalogRequested = intent.getBooleanExtra(EXTRA_CATALOG, false)
            if (catalogRequested) sensorReader.refreshCatalog()
            return START_NOT_STICKY
        }
        // Start exactly one ticker. Repeated Apply/Start intents update persisted settings,
        // but never enqueue another copy of the sampling loop.
        if (!pollingStarted) {
            pollingStarted = true
            pollingHandler?.post(ticker)
        }
        return START_NOT_STICKY
    }

    private fun stopPollingLoop() {
        pollingStarted = false
        pollingHandler?.removeCallbacksAndMessages(null)
        pollingHandler = null
        pollingThread?.quitSafely()
        pollingThread = null
    }

    override fun onDestroy() {
        // Stop first, so a read already in progress cannot schedule another tick.
        stopPollingLoop()
        try { hudView?.let { windowManager?.removeView(it) } } catch (_: Exception) { }
        hudView = null
        hudWindowParams = null
        windowManager = null
        HudConfig.setRunning(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun rows(config: HudConfig, snapshot: TemperatureSnapshot): List<OverlayRow> = buildList {
        if (config.showGpuTemp) add(OverlayRow("GPU", snapshot.gpuOverall))
        if (config.showCpuTemp) add(OverlayRow("CPU", snapshot.cpuOverall))
        config.selectedCpuCores.sorted().forEach { core ->
            snapshot.cpuCores.getOrNull(core)?.let { add(OverlayRow(it.label, it)) }
        }
        snapshot.allSensors
            .filter { it.id in config.selectedThermalSensors }
            .forEach { add(OverlayRow(SensorSourceLabels.overlayLabel(it), it)) }
        config.customEntries.forEach { custom ->
            add(OverlayRow(custom.label, snapshot.allSensors.firstOrNull { it.id == custom.sensorId }))
        }
    }

    private inner class HudView(context: android.content.Context, initialConfig: HudConfig) : View(context) {
        private var config = initialConfig
        private var snapshot = TemperatureSnapshot()
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("monospace", Typeface.BOLD)
        }
        private var startRawX = 0f
        private var startRawY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        private fun u(value: Float): Int = dp(value * config.scalePercent / 100f)
        private fun currentRows(): List<OverlayRow> = rows(config, snapshot)

        fun update(nextConfig: HudConfig, nextSnapshot: TemperatureSnapshot) {
            val previous = config
            val params = hudWindowParams
            val lockingNow = !previous.lockPosition && nextConfig.lockPosition

            // Locking freezes the position currently visible on screen, not a possibly
            // stale slider value from before the last drag. Persist that anchor once.
            val effectiveConfig = if (lockingNow && params != null) {
                nextConfig.copy(
                    positionXDp = pxToDp(params.x).coerceAtLeast(0),
                    positionYDp = pxToDp(params.y).coerceAtLeast(0)
                ).also { it.save(this@OverlayService) }
            } else nextConfig

            val previousRows = rows(previous, snapshot)
            val nextRows = rows(effectiveConfig, nextSnapshot)
            val layoutChanged = previous.scalePercent != effectiveConfig.scalePercent ||
                previousRows.map { it.label } != nextRows.map { it.label } ||
                previous.textBrightness != effectiveConfig.textBrightness ||
                previous.backgroundOpacity != effectiveConfig.backgroundOpacity
            val positionChanged = previous.positionXDp != effectiveConfig.positionXDp ||
                previous.positionYDp != effectiveConfig.positionYDp
            val touchChanged = previous.lockPosition != effectiveConfig.lockPosition
            config = effectiveConfig
            snapshot = nextSnapshot
            if (layoutChanged) requestLayout()

            if (params != null) {
                val needUpdate = layoutChanged || positionChanged || touchChanged
                params.width = WindowManager.LayoutParams.WRAP_CONTENT
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                params.alpha = overlayWindowAlpha(effectiveConfig.lockPosition)
                this.alpha = 1f
                if (touchChanged) params.flags = makeFlags(effectiveConfig.lockPosition)
                // When lock turns on, keep params.x/y exactly as they are. A deliberate
                // position-slider change is still applied in later locked updates.
                if (positionChanged && !lockingNow) {
                    params.x = dp(effectiveConfig.positionXDp.toFloat()).coerceIn(0, maxX())
                    params.y = maxOf(dp(effectiveConfig.positionYDp.toFloat()), safeTopPx() + dp(4f))
                }
                if (needUpdate) {
                    try { windowManager?.updateViewLayout(this, params) } catch (_: Exception) { }
                }
            }
            invalidate()
        }

        private fun maxX(): Int = (resources.displayMetrics.widthPixels - width).coerceAtLeast(0)
        private fun maxY(): Int = (resources.displayMetrics.heightPixels - height).coerceAtLeast(safeTopPx())

        private fun valueText(row: OverlayRow): String =
            row.reading?.celsius?.let { String.format(Locale.US, "%.1f°C", it) } ?: "N/A"

        private fun configureTextPaint(size: Float) {
            textPaint.textSize = u(size).toFloat()
            textPaint.alpha = 255
            textPaint.typeface = Typeface.create("monospace", Typeface.BOLD)
        }

        private fun rowWidth(row: OverlayRow): Float {
            configureTextPaint(10f)
            val labelWidth = textPaint.measureText(row.label)
            // Reserve just enough for the longest normal temperature, so the first live poll never clips.
            val valueWidth = textPaint.measureText("100.0°C")
            return labelWidth + u(VALUE_GAP_DP) + valueWidth
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val rowList = currentRows()
            val maxRowWidth = rowList.maxOfOrNull { rowWidth(it) } ?: 0f
            val totalWidth = maxOf(u(MIN_WIDTH_DP), (maxRowWidth + u(HORIZONTAL_PADDING_DP * 2)).toInt())
            val totalHeight = maxOf(dp(3f), rowList.size * u(ROW_HEIGHT_DP) + dp(2f))
            setMeasuredDimension(totalWidth, totalHeight)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val rowList = currentRows()
            val w = width.toFloat()
            val h = height.toFloat()
            if (config.backgroundOpacity > 0) {
                fillPaint.color = Color.argb((config.backgroundOpacity * 255 / 100).coerceIn(0, 255), 4, 8, 13)
                canvas.drawRoundRect(RectF(0f, 0f, w, h), dp(2f).toFloat(), dp(2f).toFloat(), fillPaint)
            }

            val labelColor = HudPalette.labelColor(config.textBrightness)
            rowList.forEachIndexed { index, row ->
                configureTextPaint(10f)
                val baseline = (index * u(ROW_HEIGHT_DP) + u(11.5f)).toFloat()
                val x = u(HORIZONTAL_PADDING_DP).toFloat()
                val valueX = x + textPaint.measureText(row.label) + u(VALUE_GAP_DP)
                val valueColor = row.reading?.celsius?.let { HudPalette.heatColor(it, config.textBrightness) }
                    ?: Color.argb((config.textBrightness * 175 / 100).coerceIn(50, 175), 205, 212, 222)
                drawLegibleText(canvas, row.label, x, baseline, labelColor)
                drawLegibleText(canvas, valueText(row), valueX, baseline, valueColor)
            }
        }

        private fun drawLegibleText(canvas: Canvas, text: String, x: Float, baseline: Float, color: Int) {
            configureTextPaint(10f)
            textPaint.color = color
            canvas.drawText(text, x, baseline, textPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (config.lockPosition) return false
            val params = hudWindowParams ?: return false
            val manager = windowManager ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startRawX).toInt()
                    val dy = (event.rawY - startRawY).toInt()
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(2f)) moved = true
                    if (!moved) return true
                    params.x = (startX + dx).coerceIn(0, maxX())
                    params.y = (startY + dy).coerceIn(safeTopPx() + dp(4f), maxY())
                    try { manager.updateViewLayout(this, params) } catch (_: Exception) { }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) {
                        HudConfig.load(this@OverlayService).copy(
                            positionXDp = pxToDp(params.x).coerceAtLeast(0),
                            positionYDp = pxToDp(params.y).coerceAtLeast(0)
                        ).save(this@OverlayService)
                    }
                    moved = false
                    return true
                }
            }
            return true
        }
    }
}
