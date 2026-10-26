package com.tempoverlay.mangohud

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.util.Locale

private object V2Colors {
    const val BG = 0xFF080A0C.toInt()
    const val CARD = 0xFF171A1E.toInt()
    const val CARD_ACTIVE = 0xFF14251A.toInt()
    const val EDGE = 0xFF343940.toInt()
    const val GREEN = 0xFF73F778.toInt()
    const val GREEN_DARK = 0xFF285C30.toInt()
    const val WHITE = 0xFFF5F7F6.toInt()
    const val MUTED = 0xFF92999F.toInt()
}

private data class MetricTile(val card: LinearLayout, val name: TextView, val value: TextView, val source: TextView)

class MainActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reader = SensorReader()
    @Volatile private var config = HudConfig()
    @Volatile private var screenActive = false
    private var receiverRegistered = false
    private var samplingThread: HandlerThread? = null
    private var samplingHandler: Handler? = null
    private var snapshot = TemperatureSnapshot()
    private var catalogReadings: List<ThermalReading> = emptyList()

    private val tileWidgets = linkedMapOf<String, MetricTile>()
    private val sensorRows = linkedMapOf<String, Pair<LinearLayout, TextView>>()
    private var sensorListContainer: LinearLayout? = null
    private var sensorSectionTitle: TextView? = null
    private var sensorRefreshButton: TextView? = null
    private var overlayControlButton: TextView? = null
    private var overlayControlStatus: TextView? = null
    @Volatile private var sensorsExpanded = false

    private val snapshotReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!screenActive || intent.action != OverlayService.ACTION_SNAPSHOT) return
            val fresh = TemperatureSnapshot.fromJson(
                intent.getStringExtra(OverlayService.EXTRA_SNAPSHOT) ?: return
            ) ?: return
            // The service persists the exact on-screen position when lock state changes.
            // Reload its latest config so later setting edits cannot restore a stale position.
            config = HudConfig.load(context)
            snapshot = fresh
            if (intent.getBooleanExtra(OverlayService.EXTRA_CATALOG_COMPLETE, false)) {
                val idsChanged = catalogReadings.map { it.id } != fresh.allSensors.map { it.id }
                catalogReadings = fresh.allSensors
                if (idsChanged) rebuildSensorRows() else updateSensorRows()
            }
            updateLiveValues()
        }
    }

    private val localTicker = object : Runnable {
        override fun run() {
            val worker = samplingHandler ?: return
            if (!screenActive || HudConfig.isRunning()) return
            val cfg = config
            // Live tile sampling only; the full catalogue is refreshed manually.
            val fresh = try {
                reader.sample(cfg, includeAllForSettings = sensorsExpanded)
            } catch (_: Exception) {
                TemperatureSnapshot()
            }
            if (!screenActive || HudConfig.isRunning() || samplingHandler !== worker) return
            mainHandler.post {
                if (!screenActive || HudConfig.isRunning()) return@post
                snapshot = fresh
                updateLiveValues()
            }
            if (screenActive && !HudConfig.isRunning() && samplingHandler === worker) {
                worker.postDelayed(this, cfg.refreshMs.toLong())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = HudConfig.load(this)
        enterImmersiveMode()
        buildScreen()
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            try {
                val filter = IntentFilter(OverlayService.ACTION_SNAPSHOT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(snapshotReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    registerReceiver(snapshotReceiver, filter)
                }
                receiverRegistered = true
            } catch (_: Exception) { }
        }
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
        screenActive = true
        config = HudConfig.load(this)
        OverlayService.setSnapshotListenerActive(receiverRegistered)
        if (HudConfig.isRunning()) {
            // Resume only the live overlay state. The full sensor catalogue is opt-in
            // through All sensors expansion or either explicit picker Refresh button.
            stopSampler()
        } else {
            startSampler()
        }
        syncOverlayState(requestPermission = false)
        updateLiveValues()
    }

    override fun onPause() {
        OverlayService.setSnapshotListenerActive(false)
        if (HudConfig.isRunning()) requestCatalogMode(false)
        screenActive = false
        stopSampler()
        super.onPause()
    }

    override fun onStop() {
        if (receiverRegistered) {
            try { unregisterReceiver(snapshotReceiver) } catch (_: Exception) { }
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        stopSampler()
        OverlayService.setSnapshotListenerActive(false)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    private fun enterImmersiveMode() {
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        window.statusBarColor = V2Colors.BG
        window.navigationBarColor = V2Colors.BG
    }

    private fun buildScreen() {
        tileWidgets.clear()
        val scroll = ScrollView(this).apply {
            setBackgroundColor(V2Colors.BG)
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(V2Colors.BG)
            setPadding(dp(10), dp(7), dp(10), dp(16))
        }

        buildOverlayControl(content)
        addTileRow(content, listOf("cpuTemp", "gpuTemp"))
        for (firstCore in 0..7 step 2) {
            addTileRow(content, listOf(
                "${HudConfig.CORE_PREFIX}$firstCore",
                "${HudConfig.CORE_PREFIX}${firstCore + 1}"
            ))
        }

        buildSensorSection(content)
        buildSettingsSection(content)

        scroll.addView(content, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        setContentView(scroll)
    }

    private fun buildOverlayControl(parent: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(5))
        }
        overlayControlStatus = TextView(this).apply {
            textSize = 10f
            setTextColor(V2Colors.MUTED)
            includeFontPadding = false
            maxLines = 2
        }
        row.addView(overlayControlStatus, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginEnd = dp(8)
        })
        overlayControlButton = smallButton("Ⅱ Pause overlay").apply {
            setPadding(dp(13), dp(10), dp(13), dp(10))
            setOnClickListener {
                saveConfig(config.copy(overlayPaused = !config.overlayPaused))
            }
        }
        row.addView(overlayControlButton, wrapWrap())
        parent.addView(row, matchWrap(bottom = 6))
        updateOverlayControl()
    }

    private fun updateOverlayControl() {
        overlayControlStatus?.text = when {
            config.overlayPaused -> "Overlay paused · your selected rows are saved"
            config.hasActiveRows() -> "Overlay active · pause it without clearing selections"
            else -> "Overlay off · select a tile or sensor below"
        }
        overlayControlButton?.text = if (config.overlayPaused) "▶ Resume overlay" else "Ⅱ Pause overlay"
        overlayControlButton?.isEnabled = config.overlayPaused || config.hasActiveRows()
        overlayControlButton?.alpha = if (overlayControlButton?.isEnabled == true) 1f else 0.55f
    }

    private fun addTileRow(parent: LinearLayout, keys: List<String>) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        keys.forEachIndexed { index, key ->
            val tile = createTile(key)
            row.addView(tile.card, LinearLayout.LayoutParams(0, dp(74), 1f).apply {
                if (index == 0) marginEnd = dp(5) else marginStart = dp(5)
            })
        }
        parent.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(7) })
    }

    private fun createTile(key: String): MetricTile {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(7))
            isClickable = true
            isFocusable = true
        }
        val name = TextView(this).apply {
            text = metricLabel(key)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            maxLines = 1
        }
        val value = TextView(this).apply {
            textSize = 21f
            typeface = Typeface.create("sans-serif-monospace", Typeface.BOLD)
            includeFontPadding = false
            maxLines = 1
            setPadding(0, dp(5), 0, 0)
        }
        val source = TextView(this).apply {
            textSize = 8f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(V2Colors.MUTED)
            includeFontPadding = false
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        }
        card.addView(name, LinearLayout.LayoutParams(-1, -2))
        card.addView(value, LinearLayout.LayoutParams(-1, -2))
        card.addView(source, LinearLayout.LayoutParams(-1, -2))
        val tile = MetricTile(card, name, value, source)
        tileWidgets[key] = tile
        card.setOnClickListener {
            val enable = !isMetricActive(key)
            saveConfig(config.withMetric(key, enable), requestPermission = enable)
        }
        styleTile(tile, isMetricActive(key), readingForMetric(key))
        updateTileValue(key, tile)
        return tile
    }

    private fun buildSensorSection(parent: LinearLayout) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(12), dp(2), dp(7))
            isClickable = true
            isFocusable = true
        }
        sensorSectionTitle = sectionLabel("All sensors  ▸").apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(sensorSectionTitle, LinearLayout.LayoutParams(0, dp(38), 1f))
        sensorRefreshButton = smallButton("Refresh").apply {
            visibility = View.GONE
            setOnClickListener { refreshSensors() }
        }
        header.addView(sensorRefreshButton, wrapWrap())
        header.setOnClickListener {
            sensorsExpanded = !sensorsExpanded
            updateSensorSectionVisibility()
            if (sensorsExpanded) refreshSensors() else requestCatalogMode(false)
        }
        parent.addView(header, matchWrap())

        val sensorBox = panel().apply {
            setPadding(dp(6), dp(5), dp(6), dp(5))
            visibility = View.GONE
        }
        sensorListContainer = sensorBox
        parent.addView(sensorBox, matchWrap(bottom = 6))
        rebuildSensorRows()
        updateSensorSectionVisibility()
    }

    private fun updateSensorSectionVisibility() {
        sensorListContainer?.visibility = if (sensorsExpanded) View.VISIBLE else View.GONE
        sensorRefreshButton?.visibility = if (sensorsExpanded) View.VISIBLE else View.GONE
        sensorSectionTitle?.text = if (sensorsExpanded) "All sensors  ▾" else "All sensors  ▸"
    }

    private fun rebuildSensorRows() {
        val container = sensorListContainer ?: return
        container.removeAllViews()
        sensorRows.clear()

        // Two-column cards make long thermal-zone lists much easier to scan.
        catalogReadings.chunked(2).forEach { pair ->
            val gridRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            pair.forEachIndexed { index, reading ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(9), dp(10), dp(8))
                    minimumHeight = dp(68)
                    isClickable = true
                    isFocusable = true
                }
                val name = TextView(this).apply {
                    text = reading.label
                    textSize = 10f
                    setTextColor(V2Colors.MUTED)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                val value = TextView(this).apply {
                    textSize = 15f
                    typeface = Typeface.create("sans-serif-monospace", Typeface.BOLD)
                    includeFontPadding = false
                    maxLines = 1
                    setPadding(0, dp(6), 0, 0)
                }
                card.addView(name, LinearLayout.LayoutParams(-1, -2))
                card.addView(value, LinearLayout.LayoutParams(-1, -2))
                sensorRows[reading.id] = card to value
                card.setOnClickListener {
                    val enabled = !config.selectedThermalSensors.contains(reading.id)
                    saveConfig(config.withMetric(HudConfig.SENSOR_PREFIX + reading.id, enabled),
                        requestPermission = enabled)
                }
                card.setOnLongClickListener {
                    showSensorNameDialog(reading)
                    true
                }
                gridRow.addView(card, LinearLayout.LayoutParams(0, dp(68), 1f).apply {
                    if (index == 0) marginEnd = dp(5) else marginStart = dp(5)
                })
            }
            if (pair.size == 1) {
                gridRow.addView(View(this), LinearLayout.LayoutParams(0, dp(68), 1f).apply {
                    marginStart = dp(5)
                })
            }
            container.addView(gridRow, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(5)
            })
        }
        updateSensorRows()
    }

    private fun updateSensorRows() {
        val readingsById = catalogReadings.associateBy { it.id }
        val liveReadingsById = snapshot.allSensors.associateBy { it.id }
        sensorRows.forEach { (id, views) ->
            val reading = liveReadingsById[id] ?: readingsById[id]
            views.second.text = formatTemp(reading)
            views.second.setTextColor(temperatureColor(reading))
            styleSensorRow(views.first, config.selectedThermalSensors.contains(id), reading)
        }
    }

    private fun showSensorNameDialog(reading: ThermalReading) {
        val defaultName = SensorSourceLabels.overlayLabel(reading)
        val currentName = config.sensorOverlayNames[reading.id] ?: defaultName
        val input = EditText(this).apply {
            setSingleLine(true)
            textSize = 14f
            setTextColor(V2Colors.WHITE)
            setHintTextColor(V2Colors.MUTED)
            hint = defaultName
            setText(currentName)
            setSelection(text.length)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = rounded(0xFF0D1013.toInt(), V2Colors.EDGE, 1, 9)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Edit overlay name")
            .setMessage("Long-press sensor: ${reading.sourceName}\nThis name appears on the overlay.")
            .setView(input)
            .setPositiveButton("Save", null)
            .setNeutralButton("Reset to default", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    saveConfig(config.copy(sensorOverlayNames = config.sensorOverlayNames + (reading.id to name)))
                    dialog.dismiss()
                } else {
                    input.error = "Name cannot be empty"
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                saveConfig(config.copy(sensorOverlayNames = config.sensorOverlayNames - reading.id))
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun buildSettingsSection(parent: LinearLayout) {
        parent.addView(sectionLabel("Overlay settings"), matchWrap(top = 15, bottom = 7))
        val box = panel().apply { setPadding(dp(10), dp(7), dp(10), dp(9)) }
        addSettingSlider(box, "Refresh", config.refreshMs, 500, 3000, 100,
            { String.format(Locale.US, "%.1f s", it / 1000f) }) {
            saveConfig(config.copy(refreshMs = it))
        }
        addSettingSlider(box, "Scale", config.scalePercent, 70, 115, 1, { "${it}%" }) {
            saveConfig(config.copy(scalePercent = it))
        }
        addSettingSlider(box, "Text brightness", config.textBrightness, 35, 150, 1, { "${it}%" }) {
            saveConfig(config.copy(textBrightness = it))
        }
        addSettingSlider(box, "Background opacity", config.backgroundOpacity, 0, 65, 1, { "${it}%" }) {
            saveConfig(config.copy(backgroundOpacity = it))
        }
        val widthDp = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val heightDp = (resources.displayMetrics.heightPixels / resources.displayMetrics.density).toInt().coerceAtLeast(24)
        addSettingSlider(box, "Position X", config.positionXDp, 0, widthDp, 1, { "${it} dp" }) {
            saveConfig(config.copy(positionXDp = it))
        }
        addSettingSlider(box, "Position Y", config.positionYDp, 24, heightDp, 1, { "${it} dp" }) {
            saveConfig(config.copy(positionYDp = it))
        }
        val lockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(5), dp(2), dp(1))
        }
        lockRow.addView(TextView(this).apply {
            text = "Lock position"
            textSize = 12f
            setTextColor(V2Colors.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        lockRow.addView(Switch(this).apply {
            isChecked = config.lockPosition
            buttonTintList = ColorStateList.valueOf(V2Colors.GREEN)
            setOnCheckedChangeListener { _, checked -> saveConfig(config.copy(lockPosition = checked)) }
        }, wrapWrap())
        box.addView(lockRow, matchWrap(top = 4))
        parent.addView(box, matchWrap(bottom = 8))
    }

    private fun addSettingSlider(
        parent: LinearLayout,
        title: String,
        current: Int,
        min: Int,
        max: Int,
        step: Int,
        format: (Int) -> String,
        commit: (Int) -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(5), 0, dp(4))
        }
        val labelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labelRow.addView(TextView(this).apply {
            text = title
            textSize = 11f
            setTextColor(V2Colors.WHITE)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val value = TextView(this).apply {
            text = format(current)
            textSize = 10f
            setTextColor(V2Colors.GREEN)
            typeface = Typeface.create("sans-serif-monospace", Typeface.BOLD)
        }
        labelRow.addView(value, wrapWrap())
        row.addView(labelRow, matchWrap())
        val seek = SeekBar(this).apply {
            this.max = ((max - min) / step).coerceAtLeast(1)
            progress = ((current.coerceIn(min, max) - min) / step).coerceIn(0, this.max)
            progressTintList = ColorStateList.valueOf(V2Colors.GREEN)
            thumbTintList = ColorStateList.valueOf(V2Colors.GREEN)
            progressBackgroundTintList = ColorStateList.valueOf(V2Colors.EDGE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    value.text = format((min + progress * step).coerceIn(min, max))
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    commit((min + (seekBar?.progress ?: 0) * step).coerceIn(min, max))
                }
            })
        }
        row.addView(seek, LinearLayout.LayoutParams(-1, dp(32)))
        parent.addView(row, matchWrap())
    }

    private fun refreshSensors() {
        if (HudConfig.isRunning()) {
            requestCatalogMode(true)
            return
        }
        var worker = samplingHandler
        if (worker == null) {
            startSampler()
            worker = samplingHandler
        }
        val handler = worker ?: return
        handler.removeCallbacks(localTicker)
        handler.post {
            reader.refreshCatalog()
            val fresh = try { reader.sample(config, includeAllForSettings = true) }
                catch (_: Exception) { TemperatureSnapshot() }
            mainHandler.post {
                if (!screenActive || HudConfig.isRunning()) return@post
                val idsChanged = catalogReadings.map { it.id } != fresh.allSensors.map { it.id }
                snapshot = fresh
                catalogReadings = fresh.allSensors
                if (idsChanged) rebuildSensorRows() else updateSensorRows()
                updateLiveValues()
            }
            if (screenActive && !HudConfig.isRunning() && samplingHandler === handler) {
                handler.postDelayed(localTicker, config.refreshMs.toLong())
            }
        }
    }

    private fun startSampler() {
        if (!screenActive || HudConfig.isRunning()) return
        stopSampler()
        samplingThread = HandlerThread("TempHudV2Preview", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        samplingHandler = Handler(samplingThread!!.looper)
        samplingHandler?.post(localTicker)
    }

    private fun stopSampler() {
        samplingHandler?.removeCallbacksAndMessages(null)
        samplingThread?.quitSafely()
        samplingHandler = null
        samplingThread = null
    }

    private fun requestCatalogMode(enabled: Boolean) {
        if (!HudConfig.isRunning()) return
        try {
            startService(
                Intent(this, OverlayService::class.java)
                    .setAction(OverlayService.ACTION_CATALOG)
                    .putExtra(OverlayService.EXTRA_CATALOG, enabled)
            )
        } catch (_: Exception) { }
    }

    private fun saveConfig(next: HudConfig, requestPermission: Boolean = false) {
        config = next
        next.save(this)
        updateLiveValues()
        updateOverlayControl()
        syncOverlayState(requestPermission)
    }

    private fun syncOverlayState(requestPermission: Boolean) {
        updateOverlayControl()
        if (!config.hasActiveRows() || config.overlayPaused) {
            if (HudConfig.isRunning()) {
                stopService(Intent(this, OverlayService::class.java))
                HudConfig.setRunning(false)
            }
            if (screenActive && samplingHandler == null) startSampler()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            if (requestPermission) {
                try {
                    startActivity(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ))
                } catch (_: Exception) { }
            }
            if (screenActive && !HudConfig.isRunning() && samplingHandler == null) startSampler()
            return
        }

        val wasRunning = HudConfig.isRunning()
        if (!wasRunning) {
            try {
                HudConfig.setRunning(true)
                val intent = Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_START)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            } catch (_: Exception) {
                HudConfig.setRunning(false)
                if (screenActive && samplingHandler == null) startSampler()
                return
            }
        }
        stopSampler()
        if (screenActive && !wasRunning && sensorsExpanded) requestCatalogMode(true)
    }

    private fun isMetricActive(key: String): Boolean = when {
        key == "cpuTemp" -> config.showCpuTemp
        key == "gpuTemp" -> config.showGpuTemp
        key.startsWith(HudConfig.CORE_PREFIX) ->
            key.removePrefix(HudConfig.CORE_PREFIX).toIntOrNull()?.let { it in config.selectedCpuCores } == true
        else -> false
    }

    private fun metricLabel(key: String): String = when {
        key == "cpuTemp" -> "CPU"
        key == "gpuTemp" -> "GPU"
        key.startsWith(HudConfig.CORE_PREFIX) -> "CPU ${key.removePrefix(HudConfig.CORE_PREFIX)}"
        else -> key
    }

    private fun readingForMetric(key: String): ThermalReading? = when {
        key == "cpuTemp" -> snapshot.cpuOverall
        key == "gpuTemp" -> snapshot.gpuOverall
        key.startsWith(HudConfig.CORE_PREFIX) ->
            key.removePrefix(HudConfig.CORE_PREFIX).toIntOrNull()?.let { snapshot.cpuCores.getOrNull(it) }
        else -> null
    }

    private fun updateLiveValues() {
        tileWidgets.forEach { (key, tile) ->
            styleTile(tile, isMetricActive(key), readingForMetric(key))
            updateTileValue(key, tile)
        }
        updateSensorRows()
    }

    private fun updateTileValue(key: String, tile: MetricTile) {
        val reading = readingForMetric(key)
        tile.name.text = metricLabel(key)
        tile.value.text = formatTemp(reading)
        tile.value.setTextColor(temperatureColor(reading))
        tile.source.text = reading?.sourceName?.replace(", ", "\n") ?: "No readable -usr source"
        tile.source.setTextColor(if (reading == null) V2Colors.MUTED else 0xFFADB6BD.toInt())
    }

    private fun styleTile(tile: MetricTile, active: Boolean, reading: ThermalReading?) {
        val temperature = reading?.celsius
        val heat = temperature?.let { HudPalette.heatColor(it, 100) }
        val tintStrength = when {
            temperature == null -> 0f
            HudPalette.isHeatColored(temperature) -> 0.18f
            else -> 0.10f
        }
        val heatTinted = heat?.let { mixColor(V2Colors.CARD, it, tintStrength) } ?: V2Colors.CARD
        val fill = if (active) mixColor(heatTinted, V2Colors.GREEN, 0.10f) else heatTinted
        val stroke = when {
            active -> V2Colors.GREEN
            heat != null && temperature != null -> mixColor(
                V2Colors.EDGE, heat, if (HudPalette.isHeatColored(temperature)) 0.55f else 0.22f
            )
            else -> V2Colors.EDGE
        }
        tile.card.background = rounded(fill, stroke, if (active) 2 else 1, 13)
        tile.card.elevation = dp(if (active) 6f else 0f).toFloat()
        tile.name.setTextColor(if (active) V2Colors.GREEN else V2Colors.MUTED)
        if (active) tile.name.setShadowLayer(dp(4f).toFloat(), 0f, 0f, V2Colors.GREEN_DARK)
        else tile.name.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
    }

    private fun styleSensorRow(row: LinearLayout, active: Boolean, reading: ThermalReading?) {
        val temperature = reading?.celsius
        val heat = temperature?.let { HudPalette.heatColor(it, 100) }
        val tintStrength = when {
            temperature == null -> 0f
            HudPalette.isHeatColored(temperature) -> 0.18f
            else -> 0.10f
        }
        val heatTinted = heat?.let { mixColor(V2Colors.CARD, it, tintStrength) } ?: V2Colors.CARD
        val fill = if (active) mixColor(heatTinted, V2Colors.GREEN, 0.10f) else heatTinted
        val stroke = when {
            active -> V2Colors.GREEN
            heat != null && temperature != null -> mixColor(
                V2Colors.EDGE, heat, if (HudPalette.isHeatColored(temperature)) 0.55f else 0.22f
            )
            else -> V2Colors.EDGE
        }
        row.background = rounded(fill, stroke, if (active) 2 else 1, 12)
        row.elevation = dp(if (active) 3f else 0f).toFloat()
    }

    private fun mixColor(base: Int, tint: Int, amount: Float): Int {
        val fraction = amount.coerceIn(0f, 1f)
        return android.graphics.Color.rgb(
            (android.graphics.Color.red(base) * (1f - fraction) +
                android.graphics.Color.red(tint) * fraction).toInt().coerceIn(0, 255),
            (android.graphics.Color.green(base) * (1f - fraction) +
                android.graphics.Color.green(tint) * fraction).toInt().coerceIn(0, 255),
            (android.graphics.Color.blue(base) * (1f - fraction) +
                android.graphics.Color.blue(tint) * fraction).toInt().coerceIn(0, 255)
        )
    }

    private fun formatTemp(reading: ThermalReading?): String =
        reading?.celsius?.let { String.format(Locale.US, "%.1f°C", it) } ?: "—"

    private fun temperatureColor(reading: ThermalReading?): Int =
        reading?.celsius?.let { HudPalette.heatColor(it, config.textBrightness) } ?: V2Colors.MUTED

    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(V2Colors.CARD, V2Colors.EDGE, 1, 12)
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(V2Colors.WHITE)
        includeFontPadding = false
    }

    private fun smallButton(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(V2Colors.GREEN)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = rounded(V2Colors.CARD, V2Colors.EDGE, 1, 9)
        isClickable = true
        isFocusable = true
    }

    private fun rounded(fill: Int, stroke: Int, strokeDp: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp.toFloat()).toFloat()
            setStroke(dp(strokeDp.toFloat()), stroke)
        }

    private fun dp(value: Int): Int = dp(value.toFloat())

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun matchWrap(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (top != 0) topMargin = dp(top.toFloat())
            if (bottom != 0) bottomMargin = dp(bottom.toFloat())
        }

    private fun wrapWrap(start: Int = 0, end: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (start != 0) marginStart = dp(start.toFloat())
            if (end != 0) marginEnd = dp(end.toFloat())
        }
}
