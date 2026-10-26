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
private val SOURCE_INDEX_PATTERN = Regex("(?:CPU|GPU) (\\d+)")
private data class SourceOption(val reading: ThermalReading, val name: String, val variant: String)

class MainActivity : Activity() {
    private val uiHandler = Handler(Looper.getMainLooper())
    private val reader = SensorReader()
    @Volatile private var config = HudConfig()
    private var snapshot = TemperatureSnapshot()
    private var catalogSnapshot = TemperatureSnapshot()
    private var screenActive = false
    private var receiverRegistered = false
    private var samplingThread: HandlerThread? = null
    private var samplingHandler: Handler? = null
    private var scrollView: ScrollView? = null
    private var statusView: TextView? = null
    private var statusLine: TextView? = null
    @Volatile private var showCores = false
    @Volatile private var showGpuSources = false
    @Volatile private var showAllSensors = false
    @Volatile private var showCustomEditor = false
    private var showDisplaySettings = false
    private var searchText = ""
    private var fullCatalogLoaded = false
    private val sensorSearchRows = mutableListOf<Pair<View, String>>()
    private val valueViews = linkedMapOf<String, TextView>()

    private val snapshotReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!screenActive || intent.action != OverlayService.ACTION_SNAPSHOT) return
            val raw = intent.getStringExtra(OverlayService.EXTRA_SNAPSHOT) ?: return
            val fresh = TemperatureSnapshot.fromJson(raw) ?: return
            val wantsCatalog = needsCatalog()
            val oldCatalog = catalogSnapshot
            val catalogComplete = intent.getBooleanExtra(OverlayService.EXTRA_CATALOG_COMPLETE, false)

            snapshot = fresh
            // A normal sample contains only the active CPU/GPU/selected zones. Never
            // replace the full picker catalog with that partial list: it made section
            // counts jump and caused the whole view tree to rebuild on transient read errors.
            if (wantsCatalog && catalogComplete) {
                catalogSnapshot = fresh
                fullCatalogLoaded = true
                if (!sameSensorIds(oldCatalog, fresh)) {
                    rebuildKeepingScroll()
                } else {
                    updateValues()
                }
            } else {
                updateValues()
            }
            statusView?.text = "RUNNING"
            statusView?.setTextColor(Ui.MINT)
        }
    }

    private val poller = object : Runnable {
        override fun run() {
            val bgHandler = samplingHandler ?: return
            if (!screenActive || HudConfig.isRunning()) return
            val start = SystemClock.uptimeMillis()
            val cfg = config
            val fullScan = needsCatalog()
            val previousCatalog = if (fullScan) catalogSnapshot else null
            val fresh = reader.sample(cfg, includeAllForSettings = fullScan)
            uiHandler.post {
                if (screenActive && !HudConfig.isRunning()) {
                    snapshot = fresh
                    catalogSnapshot = if (fullScan) fresh else TemperatureSnapshot()
                    fullCatalogLoaded = fullScan
                    if (fullScan && previousCatalog != null && !sameSensorIds(previousCatalog, fresh)) rebuildKeepingScroll() else updateValues()
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
        OverlayService.setSnapshotListenerActive(receiverRegistered)
    }

    override fun onResume() {
        super.onResume()
        screenActive = true
        config = HudConfig.load(this)
        if (HudConfig.isRunning()) {
            requestCatalogOnce()
        } else {
            startSampler()
        }
        statusView?.text = if (HudConfig.isRunning()) "RUNNING" else "READY"
        statusView?.setTextColor(if (HudConfig.isRunning()) Ui.MINT else Ui.MUTED)
        updateValues()
    }

    override fun onPause() {
        screenActive = false
        stopSampler()
        requestCatalogMode(false)
        super.onPause()
    }

    override fun onStop() {
        OverlayService.setSnapshotListenerActive(false)
        if (receiverRegistered) {
            try { unregisterReceiver(snapshotReceiver) } catch (_: Exception) { }
            receiverRegistered = false
        }
        super.onStop()
        if (HudConfig.isRunning() && !isChangingConfigurations) finishAndRemoveTask()
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
            val fullScan = needsCatalog()
            val previousCatalog = if (fullScan) catalogSnapshot else null
            val first = reader.sample(config, includeAllForSettings = fullScan)
            uiHandler.post {
                if (!screenActive || HudConfig.isRunning()) return@post
                snapshot = first
                catalogSnapshot = first
                fullCatalogLoaded = fullScan
                if (fullScan && previousCatalog != null && !sameSensorIds(previousCatalog, first)) rebuildKeepingScroll() else updateValues()
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

    private fun needsCatalog(): Boolean =
        showCores || showGpuSources || showAllSensors || showCustomEditor

    private fun requestCatalogOnce() {
        requestCatalogMode(needsCatalog())
    }

    private fun requestCatalogMode(enabled: Boolean) {
        if (!HudConfig.isRunning()) {
            if (enabled) scanCatalogLocally() else fullCatalogLoaded = false
            return
        }
        try {
            startService(
                Intent(this, OverlayService::class.java)
                    .setAction(OverlayService.ACTION_CATALOG)
                    .putExtra(OverlayService.EXTRA_CATALOG, enabled)
            )
            if (enabled) statusLine?.text = "Loading readable thermal sensors…"
            else fullCatalogLoaded = false
        } catch (_: Exception) { }
    }

    private fun scanCatalog() {
        searchText = ""
        requestCatalogMode(true)
        rebuildKeepingScroll()
    }

    private fun scanCatalogLocally() {
        if (!screenActive || HudConfig.isRunning()) return
        val handler = samplingHandler
        if (handler == null) {
            startSampler()
            return
        }
        val previousCatalog = catalogSnapshot
        statusLine?.text = "Scanning all readable thermal sensors…"
        handler.removeCallbacks(poller)
        handler.post {
            if (!screenActive || HudConfig.isRunning()) return@post
            val fresh = reader.sample(config, includeAllForSettings = true)
            handler.removeCallbacks(poller)
            uiHandler.post {
                if (!screenActive || HudConfig.isRunning()) return@post
                snapshot = fresh
                catalogSnapshot = fresh
                fullCatalogLoaded = true
                if (!sameSensorIds(previousCatalog, fresh)) rebuildKeepingScroll() else updateValues()
                statusLine?.text = "Found ${fresh.allSensors.size} readable sensors."
            }
            handler.postDelayed(poller, config.refreshMs.toLong())
        }
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
            HudConfig.setRunning(true)
            val intent = Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            statusLine?.text = "Overlay started."
            statusView?.text = "RUNNING"
            statusView?.setTextColor(Ui.MINT)
            stopSampler()
        } catch (e: Exception) {
            HudConfig.setRunning(false)
            statusLine?.text = "Could not start overlay: ${e.javaClass.simpleName}"
            statusView?.text = "READY"
        }
    }

    private fun stopOverlay() {
        stopService(Intent(this, OverlayService::class.java))
        HudConfig.setRunning(false)
        statusView?.text = "READY"
        statusView?.setTextColor(Ui.MUTED)
        statusLine?.text = "Overlay stopped."
        if (screenActive) startSampler()
    }

    private fun renderUi() {
        valueViews.clear()
        sensorSearchRows.clear()
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            clipToPadding = false
            clipChildren = false
            isFillViewport = false
            isVerticalScrollBarEnabled = true
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(18))
            setBackgroundColor(Ui.BG)
        }
        column.minimumHeight = 0
        column.clipChildren = false
        scroll.addView(column, ScrollView.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ))
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
        statusView = label(if (HudConfig.isRunning()) "RUNNING" else "READY", 10f,
            if (HudConfig.isRunning()) Ui.MINT else Ui.MUTED, true).apply {
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

        column.addView(divider(), dividerParams(top = 7, bottom = 7))
        addExpandableHeader(column, "CPU cores", coreCountLabel(), showCores, Ui.MINT) {
            showCores = !showCores
            rebuildKeepingScroll()
            requestCatalogMode(needsCatalog())
        }
        if (showCores) buildSourceGroups(column, false)

        column.addView(divider(), dividerParams(top = 7, bottom = 7))
        addExpandableHeader(column, "GPU cores", gpuCountLabel(), showGpuSources, Ui.BLUE) {
            showGpuSources = !showGpuSources
            rebuildKeepingScroll()
            requestCatalogMode(needsCatalog())
        }
        if (showGpuSources) buildSourceGroups(column, true)

        column.addView(divider(), dividerParams(top = 7, bottom = 7))
        addExpandableHeader(column, "Available sensors", "${allSourceReadings().size}", showAllSensors, Ui.MINT) {
            showAllSensors = !showAllSensors
            rebuildKeepingScroll()
            requestCatalogMode(needsCatalog())
        }
        if (showAllSensors) buildAllSensors(column)

        column.addView(divider(), dividerParams(top = 7, bottom = 7))
        addExpandableHeader(column, "Custom rows", "${config.customEntries.size}", showCustomEditor, Ui.MINT) {
            showCustomEditor = !showCustomEditor
            rebuildKeepingScroll()
            requestCatalogMode(needsCatalog())
        }
        if (showCustomEditor) buildCustomRows(column)

        column.addView(divider(), dividerParams(top = 7, bottom = 7))
        val refreshCard = card()
        addSlider(refreshCard, "Refresh interval", config.refreshMs, 500, 3000, 10,
            "0.50 s", "3.00 s", { String.format(Locale.US, "%.2f s", it / 1000f) }) {
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
        if (screenActive && !HudConfig.isRunning()) {
            samplingHandler?.removeCallbacks(poller)
            samplingHandler?.postDelayed(poller, config.refreshMs.toLong())
        }
        // The active overlay adopts the new interval at its next scheduled sample.
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
            text = "Use"; textSize = 11f; setTextColor(Ui.WHITE)
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
            val display = if (isGpu) SensorSourceLabels.gpuSourceOption(reading) else SensorSourceLabels.cpuCoreOption(reading)
            val variant = SensorSourceLabels.sourceVariant(reading) ?: return@mapNotNull null
            if (display == null) return@mapNotNull null
            SourceOption(reading, display.substringBefore(" · "), variant)
        }.groupBy { it.name }.toSortedMap(compareBySourceName())

        if (grouped.isEmpty()) {
            val message = if (isGpu) "No GPU Use/Step sources are exposed." else "No CPU Use/Step sources are exposed."
            parent.addView(label(message, 11f, Ui.MUTED, false).apply {
                setPadding(dp(8), dp(6), dp(8), dp(10))
            })
            return
        }

        for ((name, options) in grouped) {
            val groupCard = card().apply { setPadding(dp(8), dp(4), dp(8), dp(5)) }
            groupCard.addView(label(name, 12f, if (isGpu) Ui.BLUE else Ui.MINT, true)
                .apply { setPadding(0, dp(5), 0, dp(2)) })
            val sourceRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            for (variant in listOf("usr", "step")) {
                val option = options.firstOrNull { it.variant == variant }
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.TOP
                    setPadding(dp(2), dp(2), dp(4), dp(3))
                }
                if (option == null) {
                    cell.addView(label(if (variant == "usr") "Use —" else "Step —", 10f, Ui.MUTED, false)
                        .apply { setPadding(0, dp(8), 0, dp(8)) }, matchWrap())
                } else {
                    val readRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val check = CheckBox(this).apply {
                        text = if (variant == "usr") "Use" else "Step"
                        textSize = 10f
                        setTextColor(Ui.WHITE)
                        buttonTintList = ColorStateList.valueOf(if (isGpu) Ui.BLUE else Ui.MINT)
                        isChecked = config.selectedThermalSensors.contains(option.reading.id)
                        setOnCheckedChangeListener { _, enabled ->
                            save(config.withMetric(HudConfig.SENSOR_PREFIX + option.reading.id, enabled))
                        }
                    }
                    readRow.addView(check, LinearLayout.LayoutParams(0, dp(38), 1f))
                    val value = label(temp(option.reading), 10.5f, Ui.MINT, true).apply {
                        typeface = Typeface.MONOSPACE
                        gravity = Gravity.CENTER_VERTICAL or Gravity.END
                        minWidth = dp(54)
                    }
                    valueViews[HudConfig.SENSOR_PREFIX + option.reading.id] = value
                    readRow.addView(value, wrapWrap())
                    cell.addView(readRow, matchWrap())
                    // Keep the full kernel source visible beneath the short UI label.
                    cell.addView(label(option.reading.sourceName, 8.5f, Ui.MUTED, false).apply {
                        isSingleLine = true
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(dp(2), 0, 0, 0)
                    }, LinearLayout.LayoutParams(-1, dp(16)))
                }
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
            addTextChangedListener(SimpleTextWatcher {
                searchText = it
                filterSensorSearchRows()
            })
        }
        parent.addView(search, matchWrap(bottom = 6))
        if (!fullCatalogLoaded) {
            parent.addView(label("Sensor list has not been fully scanned yet.", 10f, Ui.MUTED, false)
                .apply { setPadding(dp(6), dp(4), dp(6), dp(4)) }, matchWrap())
        }
        parent.addView(button(if (fullCatalogLoaded) "Refresh sensor list" else "Scan all thermal sensors", true).apply {
            setOnClickListener { scanCatalog() }
        }, matchWrap(bottom = 6))

        val readings = allSourceReadings()
        readings.forEach { reading ->
            val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(2), dp(6), dp(1))
            }
            row.addView(label(SensorSourceLabels.overlayLabel(reading), 11f, Ui.WHITE, false),
                LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(CheckBox(this).apply {
                buttonTintList = ColorStateList.valueOf(Ui.MINT)
                isChecked = config.selectedThermalSensors.contains(reading.id)
                setOnCheckedChangeListener { _, enabled ->
                    save(config.withMetric(HudConfig.SENSOR_PREFIX + reading.id, enabled))
                }
            })
            item.addView(row, matchWrap())
            item.addView(label(reading.sourceName, 9f, Ui.MUTED, false).apply {
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(9), 0, dp(4), 0)
            }, matchWrap())
            val value = label(temp(reading), 10f, Ui.MINT, false)
            valueViews[HudConfig.SENSOR_PREFIX + reading.id] = value
            item.addView(value, matchWrap(start = 9, bottom = 3))
            parent.addView(item, matchWrap(bottom = 4))
            sensorSearchRows += item to "${reading.label} ${reading.id} ${reading.sourceName} ${SensorSourceLabels.overlayLabel(reading)}"
        }
        filterSensorSearchRows()
    }

        private fun filterSensorSearchRows() {
        val query = searchText.trim()
        sensorSearchRows.forEach { (row, haystack) ->
            row.visibility = if (query.isEmpty() || haystack.contains(query, ignoreCase = true)) View.VISIBLE else View.GONE
        }
    }

    private fun buildCustomRows(parent: LinearLayout) {
        val available = allSourceReadings()

        // Saved entries appear first so a newly added row is visible immediately.
        config.customEntries.forEach { entry ->
            val reading = available.firstOrNull { it.id == entry.sensorId }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val details = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(3), dp(4), dp(3))
            }
            details.addView(label(entry.label, 12f, Ui.WHITE, true), matchWrap())
            details.addView(label(reading?.sourceName ?: entry.sensorId, 9f, Ui.MUTED, false).apply {
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, matchWrap())
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            val value = label(temp(reading), 11f, Ui.MINT, true)
            valueViews["custom:${entry.id}"] = value
            row.addView(value, wrapWrap(end = 4))
            row.addView(button("Remove", true, Ui.RED).apply {
                setOnClickListener {
                    save(config.copy(customEntries = config.customEntries.filterNot { it.id == entry.id }))
                    rebuildKeepingScroll()
                }
            }, wrapWrap())
            parent.addView(row, matchWrap())
        }

        if (!fullCatalogLoaded) {
            parent.addView(button("Load all thermal sensors", true).apply {
                setOnClickListener { scanCatalog() }
            }, matchWrap(bottom = 6))
        }
        if (available.isEmpty()) {
            parent.addView(label("No sensor list loaded yet. Scan to choose a source.", 10f, Ui.MUTED, false)
                .apply { setPadding(dp(6), dp(4), dp(6), dp(4)) }, matchWrap())
            parent.addView(button("Scan all thermal sensors", true).apply {
                setOnClickListener { scanCatalog() }
            }, matchWrap())
            return
        }

        val labelInput = EditText(this).apply {
            hint = "Custom label, e.g. Hotspot"
            setTextColor(Ui.WHITE); setHintTextColor(Ui.MUTED); textSize = 13f; setSingleLine(true)
            background = rounded(Ui.PANEL, Ui.EDGE, 10); setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        parent.addView(labelInput, matchWrap(bottom = 5))
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                available.map { "${SensorSourceLabels.overlayLabel(it)} · ${it.sourceName}" }
            )
        }
        parent.addView(spinner, matchWrap(bottom = 5))
        parent.addView(button("Add custom row", false).apply {
            setOnClickListener {
                val customLabel = labelInput.text.toString().trim()
                val selected = available.getOrNull(spinner.selectedItemPosition)
                if (customLabel.isEmpty()) {
                    statusLine?.text = "Enter a label first."
                    labelInput.error = "Enter a label"
                    return@setOnClickListener
                }
                if (selected == null) {
                    statusLine?.text = "Choose a sensor from the list."
                    return@setOnClickListener
                }
                val entry = CustomHudEntry(label = customLabel, sensorId = selected.id)
                save(config.copy(customEntries = config.customEntries + entry))
                rebuildKeepingScroll()
                statusLine?.text = "Added ${entry.label} · ${selected.sourceName}"
                android.widget.Toast.makeText(this@MainActivity, "Custom row added", android.widget.Toast.LENGTH_SHORT).show()
            }
        }, matchWrap(bottom = 5))
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

    private fun sameSensorIds(first: TemperatureSnapshot, second: TemperatureSnapshot): Boolean {
        val a = first.allSensors.mapTo(HashSet()) { it.id }
        val b = second.allSensors.mapTo(HashSet()) { it.id }
        return a == b
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
        fun number(value: String): Int = SOURCE_INDEX_PATTERN.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 999
        val diff = number(a).compareTo(number(b))
        if (diff != 0) diff else a.compareTo(b)
    }

    private fun updateValues() {
        val needsSensorLookup = valueViews.keys.any {
            it.startsWith(HudConfig.SENSOR_PREFIX) || it.startsWith("custom:")
        }
        val sources = if (needsSensorLookup) allSourceReadings() else emptyList()
        valueViews.forEach { (key, view) ->
            val reading = when (key) {
                "cpuTemp" -> snapshot.cpuOverall
                "gpuTemp" -> snapshot.gpuOverall
                else -> when {
                    key.startsWith(HudConfig.SENSOR_PREFIX) ->
                        sources.firstOrNull { it.id == key.removePrefix(HudConfig.SENSOR_PREFIX) }
                    key.startsWith("custom:") -> {
                        val entryId = key.removePrefix("custom:")
                        config.customEntries.firstOrNull { it.id == entryId }
                            ?.let { entry -> sources.firstOrNull { it.id == entry.sensorId } }
                    }
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

    private fun divider(): View = object : View(this) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), dp(1))
        }
    }.apply {
        setBackgroundColor(Ui.EDGE)
        minimumHeight = dp(1)
    }

    private fun dividerParams(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, dp(1)).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

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
