package com.tempoverlay.mangohud

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import java.util.Locale

private object Ui {
    const val BG = 0xFF080C11.toInt()
    const val PANEL = 0xFF111922.toInt()
    const val PANEL_ALT = 0xFF18232E.toInt()
    const val MINT = 0xFF8CFFD0.toInt()
    const val BLUE = 0xFF8FB5FF.toInt()
    const val WHITE = 0xFFF0F4F8.toInt()
    const val MUTED = 0xFF8D9BAA.toInt()
    const val EDGE = 0xFF293643.toInt()
    const val RED = 0xFFFF8888.toInt()
}
private data class SourceOption(val reading: ThermalReading, val name: String, val variant: String)

class MainActivity : Activity() {
    private val uiHandler = Handler(Looper.getMainLooper())
    private val reader = SensorReader()
    private var config = HudConfig()
    private var snapshot = TemperatureSnapshot()
    private var catalogSnapshot = TemperatureSnapshot()
    private var screenActive = false
    private var receiverRegistered = false
    private var samplingThread: HandlerThread? = null
    private var samplingHandler: Handler? = null
    private var scrollView: ScrollView? = null
    private var statusView: TextView? = null
    private var statusLine: TextView? = null
    private var showCores = false
    private var showGpuSources = false
    private var showAllSensors = false
    private var showCustomEditor = false
    private var showDisplaySettings = false
    private var searchText = ""
    private val valueViews = linkedMapOf<String, TextView>()

    private val snapshotReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != OverlayService.ACTION_SNAPSHOT) return
            val raw = intent.getStringExtra(OverlayService.EXTRA_SNAPSHOT) ?: return
            val fresh = TemperatureSnapshot.fromJson(raw) ?: return
            snapshot = fresh
            statusView?.text = "RUNNING"
            statusView?.setTextColor(Ui.MINT)
            updateValues()
        }
    }

    private val poller = object : Runnable {
        override fun run() {
            val bgHandler = samplingHandler ?: return
            if (!screenActive || HudConfig.isRunning(this@MainActivity)) return
            val start = SystemClock.uptimeMillis()
            val cfg = config
            val fullScan = showAllSensors || showCustomEditor
            val fresh = reader.sample(cfg, includeAllForSettings = fullScan)
            uiHandler.post {
                if (screenActive && !HudConfig.isRunning(this@MainActivity)) {
                    snapshot = fresh
                    if (fullScan) catalogSnapshot = fresh
                    updateValues()
                }
            }
            val elapsed = SystemClock.uptimeMillis() - start
            bgHandler.postDelayed(this, (cfg.refreshMs.toLong() - elapsed).coerceAtLeast(100L))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = HudConfig.load(this)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }
        }
        renderUi()
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
        screenActive = true
        config = HudConfig.load(this)
        if (HudConfig.isRunning(this)) {
            requestCatalogOnce()
        } else {
            startSampler()
        }
        statusView?.text = if (HudConfig.isRunning(this)) "RUNNING" else "READY"
        statusView?.setTextColor(if (HudConfig.isRunning(this)) Ui.MINT else Ui.MUTED)
        updateValues()
    }

    override fun onPause() {
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
        if (HudConfig.isRunning(this) && !isChangingConfigurations) finishAndRemoveTask()
    }

    override fun onDestroy() {
        stopSampler()
        super.onDestroy()
    }

    private fun startSampler() {
        stopSampler()
        samplingThread = HandlerThread("TempHudSettings", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        samplingHandler = Handler(samplingThread!!.looper)
        samplingHandler?.post {
            val first = reader.sample(config, includeAllForSettings = true)
            uiHandler.post {
                if (!screenActive || HudConfig.isRunning(this)) return@post
                snapshot = first
                catalogSnapshot = first
                updateValues()
                samplingHandler?.removeCallbacks(poller)
                samplingHandler?.postDelayed(poller, config.refreshMs.toLong())
            }
        }
    }

    private fun stopSampler() {
        samplingHandler?.removeCallbacksAndMessages(null)
        samplingThread?.quitSafely()
        samplingHandler = null
        samplingThread = null
    }

    private fun requestCatalogOnce() {
        Thread({
            val fresh = reader.sample(HudConfig.load(this), includeAllForSettings = true)
            uiHandler.post {
                if (!screenActive) return@post
                catalogSnapshot = fresh
                if (!HudConfig.isRunning(this)) snapshot = fresh
                updateValues()
                rebuildKeepingScroll()
            }
        }, "TempHudCatalog").apply { priority = Thread.MIN_PRIORITY; start() }
    }

    private fun scanCatalog() {
        val cfg = config
        Thread({
            val fresh = reader.sample(cfg, includeAllForSettings = true)
            uiHandler.post {
                if (!screenActive) return@post
                catalogSnapshot = fresh
                if (!HudConfig.isRunning(this)) snapshot = fresh
                updateValues()
                rebuildKeepingScroll()
            }
        }, "TempHudSensorScan").apply { priority = Thread.MIN_PRIORITY; start() }
    }

    private fun save(next: HudConfig) {
        config = next
        next.save(this)
        updateValues()
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            statusLine?.text = "Allow Display over other apps, then return here."
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        try {
            HudConfig.setRunning(this, true)
            val intent = Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            statusLine?.text = "Overlay started."
            statusView?.text = "RUNNING"
            statusView?.setTextColor(Ui.MINT)
            stopSampler()
        } catch (e: Exception) {
            HudConfig.setRunning(this, false)
            statusLine?.text = "Could not start overlay: ${e.javaClass.simpleName}"
            statusView?.text = "READY"
        }
    }

    private fun stopOverlay() {
        stopService(Intent(this, OverlayService::class.java))
        HudConfig.setRunning(this, false)
        statusView?.text = "READY"
        statusView?.setTextColor(Ui.MUTED)
        statusLine?.text = "Overlay stopped."
        if (screenActive) startSampler()
    }

    private fun renderUi() {
        valueViews.clear()
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG); clipToPadding = false; isFillViewport = true }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(18))
            setBackgroundColor(Ui.BG)
        }
        scroll.addView(column, ScrollView.LayoutParams(-1, -2))
        scrollView = scroll
        setContentView(scroll)

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val logo = TextView(this).apply {
            text = "TH"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(Ui.MINT); background = rounded(Ui.PANEL_ALT, Ui.EDGE, 12)
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(42), dp(42)))
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0)
        }
        titleCol.addView(label("Temp HUD", 20f, Ui.WHITE, true))
        titleCol.addView(label("CPU & GPU TEMPERATURES", 10f, Ui.MUTED, false))
        header.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
        statusView = label(if (HudConfig.isRunning(this)) "RUNNING" else "READY", 10f,
            if (HudConfig.isRunning(this)) Ui.MINT else Ui.MUTED, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(Ui.PANEL, Ui.EDGE, 18)
        }
        header.setPadding(0, dp(2), 0, dp(8))
        header.addView(statusView)
        column.addView(header)

        val overall = card()
        addOverallRow(overall, "CPU", "cpuTemp", config.showCpuTemp)
        addOverallRow(overall, "GPU", "gpuTemp", config.showGpuTemp)
        column.addView(overall, matchWrap())
        addAverageSelector(column, "CPU average source", config.cpuAverageVariant, true)
        addAverageSelector(column, "GPU average source", config.gpuAverageVariant, false)

        column.addView(divider(), matchWrap(top = 7, bottom = 7))
        addExpandableHeader(column, "CPU cores", coreCountLabel(), showCores, Ui.MINT) {
            showCores = !showCores
            if (showCores && allSourceReadings().isEmpty()) scanCatalog() else rebuildKeepingScroll()
        }
        if (showCores) buildSourceGroups(column, false)

        column.addView(divider(), matchWrap(top = 7, bottom = 7))
        addExpandableHeader(column, "GPU cores", gpuCountLabel(), showGpuSources, Ui.BLUE) {
            showGpuSources = !showGpuSources
            if (showGpuSources && allSourceReadings().isEmpty()) scanCatalog() else rebuildKeepingScroll()
        }
        if (showGpuSources) buildSourceGroups(column, true)

        column.addView(divider(), matchWrap(top = 7, bottom = 7))
        addExpandableHeader(column, "Available sensors", "${allSourceReadings().size}", showAllSensors, Ui.MINT) {
            showAllSensors = !showAllSensors
            if (showAllSensors) scanCatalog() else rebuildKeepingScroll()
        }
        if (showAllSensors) buildAllSensors(column)

        column.addView(divider(), matchWrap(top = 7, bottom = 7))
        addExpandableHeader(column, "Custom rows", "${config.customEntries.size}", showCustomEditor, Ui.MINT) {
            showCustomEditor = !showCustomEditor
            if (showCustomEditor) scanCatalog() else rebuildKeepingScroll()
        }
        if (showCustomEditor) buildCustomRows(column)

        column.addView(divider(), matchWrap(top = 7, bottom = 7))
        val refreshCard = card()
        addSlider(refreshCard, "Refresh interval", config.refreshMs, 500, 5000, 50,
            "0.50 s", "5.00 s", { String.format(Locale.US, "%.2f s", it / 1000f) }) {
            save(config.copy(refreshMs = it))
            restartSamplerForNewInterval()
        }
        column.addView(refreshCard, matchWrap())

        column.addView(button(if (showDisplaySettings) "Hide display settings" else "Display settings", true).apply {
            setOnClickListener { showDisplaySettings = !showDisplaySettings; rebuildKeepingScroll() }
        }, matchWrap(top = 8, bottom = 3))
        if (showDisplaySettings) buildDisplaySettings(column)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(button("Apply / start", false).apply {
            setOnClickListener { config.save(this@MainActivity); startOverlay() }
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(6) })
        actions.addView(button("Stop", true).apply { setOnClickListener { stopOverlay() } },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(6) })
        column.addView(actions, matchWrap(top = 10))
        statusLine = label("", 11f, Ui.MINT, false).apply { setPadding(0, dp(8), 0, 0) }
        column.addView(statusLine)
        column.addView(label("Android 11+ · local only · no analytics or updater", 10f, Ui.MUTED, false)
            .apply { setPadding(0, dp(8), 0, 0) })
        updateValues()
    }

    private fun restartSamplerForNewInterval() {
        if (screenActive && !HudConfig.isRunning(this)) startSampler()
        Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_START).let {
            if (HudConfig.isRunning(this)) {
                try { startService(it) } catch (_: Exception) { }
            }
        }
    }

    private fun rebuildKeepingScroll() {
        val y = scrollView?.scrollY ?: 0
        renderUi()
        scrollView?.post { scrollView?.scrollTo(0, y) }
    }

    private fun addOverallRow(parent: LinearLayout, title: String, key: String, checked: Boolean) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(9), dp(5), dp(7), dp(5)) }
        row.addView(label(title, 14f, Ui.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        val value = label("N/A", 13f, Ui.MINT, true).apply { typeface = Typeface.MONOSPACE; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        valueViews[key] = value
        row.addView(value, LinearLayout.LayoutParams(dp(86), -2))
        val check = CheckBox(this).apply {
            buttonTintList = ColorStateList.valueOf(Ui.MINT)
            isChecked = checked
            setOnCheckedChangeListener { _, enabled ->
                save(if (key == "cpuTemp") config.copy(showCpuTemp = enabled) else config.copy(showGpuTemp = enabled))
            }
        }
        row.addView(check, LinearLayout.LayoutParams(dp(42), dp(42)))
        parent.addView(row, matchWrap())
    }

    private fun addAverageSelector(parent: LinearLayout, title: String, current: String, cpu: Boolean) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(9), dp(2), dp(9), dp(2)) }
        box.addView(label(title, 11f, Ui.MUTED, false), LinearLayout.LayoutParams(0, -2, 1f))
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val usr = RadioButton(this).apply {
            text = "usr"; textSize = 11f; setTextColor(Ui.WHITE)
            buttonTintList = ColorStateList.valueOf(if (cpu) Ui.MINT else Ui.BLUE)
            id = View.generateViewId(); isChecked = current != "step"
        }
        val step = RadioButton(this).apply {
            text = "step"; textSize = 11f; setTextColor(Ui.WHITE)
            buttonTintList = ColorStateList.valueOf(if (cpu) Ui.MINT else Ui.BLUE)
            id = View.generateViewId(); isChecked = current == "step"
        }
        group.addView(usr); group.addView(step)
        group.setOnCheckedChangeListener { _, checkedId ->
            val value = if (checkedId == step.id) "step" else "usr"
            if (cpu && config.cpuAverageVariant != value) save(config.copy(cpuAverageVariant = value))
            if (!cpu && config.gpuAverageVariant != value) save(config.copy(gpuAverageVariant = value))
        }
        box.addView(group)
        parent.addView(box, matchWrap())
    }

    private fun addExpandableHeader(parent: LinearLayout, title: String, count: String, expanded: Boolean, accent: Int, onClick: () -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(3), dp(3), dp(1), dp(3)) }
        row.addView(label(title, 13f, Ui.WHITE, true), LinearLayout.LayoutParams(0, dp(40), 1f))
        row.addView(label(count, 10f, Ui.MUTED, false), wrapWrap(start = 5))
        row.addView(button(if (expanded) "Hide" else "Edit", true, accent).apply {
            setPadding(dp(8), 0, dp(8), 0); minHeight = dp(34); minimumHeight = dp(34)
            setOnClickListener { onClick() }
        }, wrapWrap(start = 6))
        parent.addView(row, matchWrap())
    }

    private fun buildSourceGroups(parent: LinearLayout, isGpu: Boolean) {
        val grouped = allSourceReadings().mapNotNull { reading ->
            val text = if (isGpu) SensorSourceLabels.gpuSourceOption(reading) else SensorSourceLabels.cpuCoreOption(reading)
            text?.let {
                val name = it.substringBefore(" · ")
                val variant = it.substringAfter(" · ", "")
                SourceOption(reading, name, if (variant == "step") "step" else if (variant == "usr") "usr" else "")
            }
        }.groupBy { it.name }.toSortedMap(compareBySourceName())

        if (grouped.isEmpty()) {
            parent.addView(label(if (isGpu) "No GPU-labelled thermal sources are exposed." else "No per-core CPU sources are exposed.",
                11f, Ui.MUTED, false).apply { setPadding(dp(8), dp(6), dp(8), dp(10) })
            )
            return
        }
        for ((name, options) in grouped) {
            val groupCard = card().apply { setPadding(dp(8), dp(4), dp(8), dp(4)) }
            groupCard.addView(label(name, 12f, if (isGpu) Ui.BLUE else Ui.MINT, true).apply { setPadding(0, dp(5), 0, dp(2)) })
            val sourceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val ordered = options.sortedBy { if (it.variant == "usr") 0 else if (it.variant == "step") 1 else 2 }
            ordered.forEach { option ->
                val cell = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2), dp(3), dp(2), dp(3)) }
                val check = CheckBox(this).apply {
                    text = if (option.variant.isBlank()) "sensor" else option.variant
                    textSize = 11f
                    setTextColor(Ui.WHITE)
                    buttonTintList = ColorStateList.valueOf(if (isGpu) Ui.BLUE else Ui.MINT)
                    isChecked = config.selectedThermalSensors.contains(option.reading.id)
                    setOnCheckedChangeListener { _, enabled ->
                        save(config.withMetric(HudConfig.SENSOR_PREFIX + option.reading.id, enabled))
                    }
                }
                cell.addView(check, LinearLayout.LayoutParams(0, dp(40), 1f))
                val value = label("N/A", 11f, Ui.MINT, true).apply { typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER_VERTICAL or Gravity.END; minWidth = dp(62) }
                valueViews[HudConfig.SENSOR_PREFIX + option.reading.id] = value
                cell.addView(value, wrapWrap())
                sourceRow.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            }
            groupCard.addView(sourceRow, matchWrap())
            parent.addView(groupCard, matchWrap(bottom = 5))
        }
    }

    private fun buildAllSensors(parent: LinearLayout) {
        val search = EditText(this).apply {
            hint = "Search sensor type"
            setTextColor(Ui.WHITE); setHintTextColor(Ui.MUTED); textSize = 13f; setSingleLine(true)
            setText(searchText); setPadding(dp(10), dp(3), dp(10), dp(3)); background = rounded(Ui.PANEL, Ui.EDGE, 10)
            addTextChangedListener(SimpleTextWatcher { searchText = it })
        }
        parent.addView(search, matchWrap(bottom = 6))
        val readings = allSourceReadings().filter {
            "${it.label} ${it.id} ${it.sourceName}".contains(searchText, ignoreCase = true)
        }
        readings.forEach { reading ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(2), dp(6), dp(2)) }
            row.addView(label("${SensorSourceLabels.overlayLabel(reading)} · ${reading.id}", 11f, Ui.WHITE, false),
                LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(CheckBox(this).apply {
                buttonTintList = ColorStateList.valueOf(Ui.MINT)
                isChecked = config.selectedThermalSensors.contains(reading.id)
                setOnCheckedChangeListener { _, enabled -> save(config.withMetric(HudConfig.SENSOR_PREFIX + reading.id, enabled)) }
            })
            parent.addView(row, matchWrap())
            val value = label(temp(reading), 10f, Ui.MUTED, false)
            valueViews[HudConfig.SENSOR_PREFIX + reading.id] = value
            parent.addView(value, matchWrap(start = 7, bottom = 3))
        }
        if (readings.isEmpty()) parent.addView(button("Scan sensors", true).apply { setOnClickListener { scanCatalog() } }, matchWrap())
    }

    private fun buildCustomRows(parent: LinearLayout) {
        val available = allSourceReadings()
        if (available.isEmpty()) {
            parent.addView(button("Scan all thermal sensors", true).apply { setOnClickListener { scanCatalog() } }, matchWrap())
        } else {
            val labelInput = EditText(this).apply {
                hint = "Custom label, e.g. Hotspot"
                setTextColor(Ui.WHITE); setHintTextColor(Ui.MUTED); textSize = 13f; setSingleLine(true)
                background = rounded(Ui.PANEL, Ui.EDGE, 10); setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            parent.addView(labelInput, matchWrap(bottom = 5))
            val spinner = Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                    available.map { "${SensorSourceLabels.overlayLabel(it)} · ${it.id}" })
            }
            parent.addView(spinner, matchWrap(bottom = 5))
            parent.addView(button("Add custom row", false).apply {
                setOnClickListener {
                    val label = labelInput.text.toString().trim()
                    val index = spinner.selectedItemPosition
                    if (label.isNotEmpty() && index in available.indices) {
                        save(config.copy(customEntries = config.customEntries + CustomHudEntry(label = label, sensorId = available[index].id)))
                        rebuildKeepingScroll()
                    } else statusLine?.text = "Enter a label and choose a sensor."
                }
            }, matchWrap(bottom = 5))
        }
        config.customEntries.forEach { entry ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(label(entry.label, 12f, Ui.WHITE, true), LinearLayout.LayoutParams(0, dp(42), 1f))
            val value = label("N/A", 11f, Ui.MINT, true)
            valueViews[HudConfig.SENSOR_PREFIX + entry.sensorId] = value
            row.addView(value, wrapWrap(end = 4))
            row.addView(button("Remove", true, Ui.RED).apply {
                setOnClickListener {
                    save(config.copy(customEntries = config.customEntries.filterNot { it.id == entry.id }))
                    rebuildKeepingScroll()
                }
            }, wrapWrap())
            parent.addView(row, matchWrap())
        }
    }

    private fun buildDisplaySettings(parent: LinearLayout) {
        val settings = card()
        addSlider(settings, "Text brightness", config.textBrightness, 35, 100, 1, "35%", "100%", { "${it}%" }) {
            save(config.copy(textBrightness = it))
        }
        addSlider(settings, "Background opacity", config.backgroundOpacity, 0, 65, 1, "Text only", "More solid", { "${it}%" }) {
            save(config.copy(backgroundOpacity = it))
        }
        addSlider(settings, "HUD size", config.scalePercent, 70, 115, 1, "70%", "115%", { "${it}%" }) {
            save(config.copy(scalePercent = it))
        }
        addSlider(settings, "Horizontal position", config.positionXDp, 0, (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt().coerceAtLeast(0),
            1, "Left", "Right", { "${it} dp" }) { save(config.copy(positionXDp = it)) }
        addSlider(settings, "Vertical position", config.positionYDp, 24, (resources.displayMetrics.heightPixels / resources.displayMetrics.density).toInt().coerceAtLeast(24),
            1, "Top", "Bottom", { "${it} dp" }) { save(config.copy(positionYDp = it)) }
        val lockRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(5), dp(4), dp(5), dp(4)) }
        lockRow.addView(label("Lock position · touch-through", 12f, Ui.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        lockRow.addView(Switch(this).apply {
            isChecked = config.lockPosition
            setOnCheckedChangeListener { _, enabled -> save(config.copy(lockPosition = enabled)) }
        })
        settings.addView(lockRow, matchWrap())
        parent.addView(settings, matchWrap())
    }

    private fun addSlider(
        parent: LinearLayout, title: String, current: Int, min: Int, max: Int, step: Int,
        left: String, right: String, display: (Int) -> String, onDone: (Int) -> Unit
    ) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(9), dp(5), dp(9), dp(5)) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(title, 12f, Ui.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        val valueLabel = label(display(current), 11f, Ui.MINT, true).apply { typeface = Typeface.MONOSPACE }
        header.addView(valueLabel)
        col.addView(header, matchWrap())
        val stride = step.coerceAtLeast(1)
        val count = ((max - min) / stride).coerceAtLeast(1)
        val seek = SeekBar(this).apply {
            this.max = count
            progress = ((current.coerceIn(min, max) - min) / stride).coerceIn(0, count)
            progressTintList = ColorStateList.valueOf(Ui.MINT)
            thumbTintList = ColorStateList.valueOf(Ui.MINT)
            progressBackgroundTintList = ColorStateList.valueOf(Ui.EDGE)
        }
        var pending = current
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                pending = (min + progress * stride).coerceAtMost(max)
                valueLabel.text = display(pending)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) { if (pending != current) onDone(pending) }
        })
        col.addView(seek, matchWrap())
        val limits = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        limits.addView(label(left, 9f, Ui.MUTED, false), LinearLayout.LayoutParams(0, -2, 1f))
        limits.addView(label(right, 9f, Ui.MUTED, false).apply { gravity = Gravity.END })
        col.addView(limits, matchWrap())
        parent.addView(col, matchWrap())
    }

    private fun allSourceReadings(): List<ThermalReading> {
        val map = linkedMapOf<String, ThermalReading>()
        catalogSnapshot.allSensors.forEach { map[it.id] = it }
        snapshot.allSensors.forEach { map[it.id] = it }
        return map.values.sortedBy { it.id.removePrefix("thermal_zone").toIntOrNull() ?: Int.MAX_VALUE }
    }

    private fun coreCountLabel(): String {
        val count = allSourceReadings().mapNotNull { SensorSourceLabels.cpuCoreOption(it)?.substringBefore(" · ") }.distinct().size
        return if (count == 0) "Core sensors" else "${count} cores"
    }

    private fun gpuCountLabel(): String {
        val count = allSourceReadings().mapNotNull { SensorSourceLabels.gpuSourceOption(it)?.substringBefore(" · ") }.distinct().size
        return if (count == 0) "GPU sensors" else "${count} sources"
    }

    private fun compareBySourceName(): Comparator<String> = Comparator { a, b ->
        fun number(value: String): Int = Regex("(?:CPU|GPU) (\\d+)").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 999
        val diff = number(a).compareTo(number(b))
        if (diff != 0) diff else a.compareTo(b)
    }

    private fun updateValues() {
        val sources = allSourceReadings()
        valueViews.forEach { (key, view) ->
            val reading = when (key) {
                "cpuTemp" -> snapshot.cpuOverall
                "gpuTemp" -> snapshot.gpuOverall
                else -> when {
                    key.startsWith(HudConfig.SENSOR_PREFIX) ->
                        sources.firstOrNull { it.id == key.removePrefix(HudConfig.SENSOR_PREFIX) }
                    key.startsWith("core") && key.endsWith("Temp") ->
                        key.removePrefix("core").removeSuffix("Temp").toIntOrNull()?.let { snapshot.cpuCores.getOrNull(it) }
                    else -> null
                }
            }
            view.text = temp(reading)
            view.setTextColor(reading?.celsius?.let { HudPalette.heatColor(it, config.textBrightness) } ?: Ui.MUTED)
        }
    }

    private fun temp(reading: ThermalReading?): String =
        reading?.celsius?.let { String.format(Locale.US, "%.1f°C", it) } ?: "N/A"

    private fun label(text: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }

    private fun button(text: String, outline: Boolean, accent: Int = Ui.MINT): Button =
        Button(this).apply {
            this.text = text
            textSize = 11f
            isAllCaps = false
            setTextColor(if (outline) accent else Ui.BG)
            background = rounded(if (outline) Ui.PANEL else accent, if (outline) Ui.EDGE else accent, 10)
            stateListAnimator = null
            minHeight = dp(42)
            minimumHeight = dp(42)
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(5), dp(4), dp(5), dp(4))
        background = rounded(Ui.PANEL, Ui.EDGE, 14)
    }

    private fun divider(): View = View(this).apply { setBackgroundColor(Ui.EDGE) }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply { setColor(fill); cornerRadius = dp(radiusDp).toFloat(); setStroke(dp(1), stroke) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun matchWrap(top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(top); bottomMargin = dp(bottom); marginStart = dp(start); marginEnd = dp(end)
        }

    private fun wrapWrap(top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(top); bottomMargin = dp(bottom); marginStart = dp(start); marginEnd = dp(end)
        }
}

private class SimpleTextWatcher(private val changed: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed(s?.toString().orEmpty()) }
    override fun afterTextChanged(s: android.text.Editable?) {}
}
