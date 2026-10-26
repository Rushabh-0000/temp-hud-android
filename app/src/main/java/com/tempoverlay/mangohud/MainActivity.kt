package com.tempoverlay.mangohud

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
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
    const val BG = 0xFF070B12.toInt()
    const val PANEL = 0xFF101927.toInt()
    const val PANEL_ALT = 0xFF17243A.toInt()
    const val MINT = 0xFFC6F16F.toInt()
    const val BLUE = 0xFF59DCEB.toInt()
    const val WARM = 0xFFFFAA68.toInt()
    const val WHITE = 0xFFF3F6FB.toInt()
    const val MUTED = 0xFF9BA9BC.toInt()
    const val EDGE = 0xFF2C3A4F.toInt()
    const val RED = 0xFFFF718A.toInt()
}
private val SOURCE_INDEX_PATTERN = Regex("(?:CPU|GPU) (\\d+)")
private const val TAB_DASHBOARD = "dashboard"
private const val TAB_SENSORS = "sensors"
private const val TAB_APPEARANCE = "appearance"
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
    private var searchText = ""
    private var selectedTab = TAB_DASHBOARD
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
        // Keep expanded editors and the user's scroll position during rotation,
        // multi-window resizing and Activity recreation.
        showCores = savedInstanceState?.getBoolean("ui_show_cores") ?: false
        showGpuSources = savedInstanceState?.getBoolean("ui_show_gpu_sources") ?: false
        showAllSensors = savedInstanceState?.getBoolean("ui_show_all_sensors") ?: false
        showCustomEditor = savedInstanceState?.getBoolean("ui_show_custom_editor") ?: false
        selectedTab = savedInstanceState?.getString("ui_selected_tab") ?: TAB_DASHBOARD
        searchText = savedInstanceState?.getString("ui_sensor_search").orEmpty()
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }
        }
        renderUi()
        val savedScrollY = savedInstanceState?.getInt("ui_scroll_y", 0) ?: 0
        if (savedScrollY > 0) scrollView?.post { scrollView?.scrollTo(0, savedScrollY) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("ui_show_cores", showCores)
        outState.putBoolean("ui_show_gpu_sources", showGpuSources)
        outState.putBoolean("ui_show_all_sensors", showAllSensors)
        outState.putBoolean("ui_show_custom_editor", showCustomEditor)
        outState.putString("ui_selected_tab", selectedTab)
        outState.putString("ui_sensor_search", searchText)
        outState.putInt("ui_scroll_y", scrollView?.scrollY ?: 0)
        super.onSaveInstanceState(outState)
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
        OverlayService.setSnapshotListenerActive(receiverRegistered)
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
        // The Activity may be paused without reaching onStop in floating/multi-window mode.
        // Stop preview broadcasts immediately so hidden UI work stays near zero.
        OverlayService.setSnapshotListenerActive(false)
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

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        repairScrollContentMeasurement()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        repairScrollContentMeasurement()
    }

    private fun repairScrollContentMeasurement() {
        val scroll = scrollView ?: return
        scroll.post {
            scroll.isFillViewport = false
            val child = scroll.getChildAt(0) ?: return@post
            val params = child.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return@post
            if (params.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                child.layoutParams = params
            }
            child.requestLayout()
            scroll.requestLayout()
            scroll.invalidate()
        }
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
        selectedTab == TAB_SENSORS || showCores || showGpuSources || showAllSensors || showCustomEditor

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

    /**
     * The app now has three real destinations instead of one legacy settings list:
     * Dashboard, Sensors, and Appearance. The shell/header/navigation stay mounted
     * while each destination is rendered into its own scrolling page.
     */
    private fun renderUi() {
        valueViews.clear()
        sensorSearchRows.clear()

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            clipToPadding = true
            clipChildren = true
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(12))
            setBackgroundColor(Ui.BG)
        }
        val logo = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            contentDescription = "Temp HUD app icon"
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = null
            setPadding(0, 0, 0, 0)
        }
        topBar.addView(logo, LinearLayout.LayoutParams(dp(48), dp(48)))
        topBar.addView(label("Temp HUD", 22f, Ui.WHITE, true).apply {
            setPadding(dp(12), 0, 0, 0)
            letterSpacing = -0.015f
        }, LinearLayout.LayoutParams(0, -2, 1f))

        statusView = label(if (HudConfig.isRunning()) "RUNNING" else "READY", 9f,
            if (HudConfig.isRunning()) Ui.MINT else Ui.MUTED, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.45f
            setPadding(dp(11), dp(8), dp(11), dp(8))
            background = rounded(Ui.PANEL_ALT, if (HudConfig.isRunning()) Ui.MINT else Ui.EDGE, 18)
        }
        topBar.addView(statusView)
        shell.addView(topBar, LinearLayout.LayoutParams(-1, -2))

        shell.addView(View(this).apply { setBackgroundColor(Ui.EDGE) },
            LinearLayout.LayoutParams(-1, dp(1)))

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            clipToPadding = true
            clipChildren = true
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(24))
            setBackgroundColor(Ui.BG)
            clipChildren = true
            clipToPadding = true
        }
        scroll.addView(page, android.widget.FrameLayout.LayoutParams(-1, -2))
        scrollView = scroll

        statusLine = label("", 10f, Ui.MUTED, false).apply {
            setPadding(dp(1), dp(1), dp(1), dp(1))
            includeFontPadding = false
        }
        page.addView(statusLine, matchWrap())
        shell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        fun pageHeading(kicker: String, title: String, subtitle: String) {
            val group = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(1), dp(5), dp(1), dp(8))
            }
            group.addView(label(kicker, 9f, Ui.MINT, true).apply {
                letterSpacing = 1.6f
            }, matchWrap(bottom = 7))
            group.addView(label(title, 25f, Ui.WHITE, true), matchWrap())
            group.addView(label(subtitle, 11f, Ui.MUTED, false).apply {
                setPadding(0, dp(6), 0, 0)
            }, matchWrap())
            page.addView(group, matchWrap(bottom = 13))
        }

        fun sectionTitle(title: String, action: String? = null) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(1), dp(3), dp(1), dp(8))
            }
            row.addView(label(title, 13f, Ui.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
            if (action != null) {
                row.addView(label(action, 9f, Ui.MUTED, true).apply {
                    letterSpacing = 0.55f
                })
            }
            page.addView(row, matchWrap(top = 13))
        }

        fun temperatureTile(title: String, detail: String, key: String, enabled: Boolean, accent: Int): LinearLayout {
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(13), dp(10), dp(10))
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Ui.PANEL_ALT, Ui.PANEL)
                ).apply {
                    cornerRadius = dp(18).toFloat()
                    setStroke(dp(1), Ui.EDGE)
                }
            }
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val icon = label(if (title == "CPU") "C" else "G", 12f, accent, true).apply {
                gravity = Gravity.CENTER
                background = rounded(Ui.BG, accent, 9)
            }
            top.addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(8) })
            top.addView(label(title, 12f, Ui.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(View(this).apply { background = rounded(accent, accent, 6) },
                LinearLayout.LayoutParams(dp(6), dp(6)))
            tile.addView(top, matchWrap())

            tile.addView(label(detail.uppercase(Locale.US), 8f, Ui.MUTED, true).apply {
                letterSpacing = 0.65f
                setPadding(0, dp(11), 0, dp(6))
                maxLines = 1
            }, matchWrap())

            val value = label("N/A", 27f, accent, true).apply {
                typeface = Typeface.create("sans-serif-monospace", Typeface.BOLD)
                setSingleLine(true)
                includeFontPadding = false
            }
            valueViews[key] = value
            tile.addView(value, matchWrap())

            tile.addView(View(this).apply { setBackgroundColor(Ui.EDGE) },
                LinearLayout.LayoutParams(-1, dp(1)).apply {
                    topMargin = dp(11)
                    bottomMargin = dp(5)
                })
            tile.addView(CheckBox(this).apply {
                text = "Show on HUD"
                textSize = 10f
                setTextColor(Ui.MUTED)
                buttonTintList = ColorStateList.valueOf(accent)
                isChecked = enabled
                minHeight = dp(30)
                minimumHeight = dp(30)
                setPadding(0, 0, 0, 0)
                setOnCheckedChangeListener { _, checked ->
                    save(if (key == "cpuTemp") config.copy(showCpuTemp = checked) else config.copy(showGpuTemp = checked))
                }
            }, matchWrap())
            return tile
        }

        fun settingCard(title: String, subtitle: String): LinearLayout {
            val card = card().apply { setPadding(dp(11), dp(10), dp(11), dp(11)) }
            card.addView(label(title, 13f, Ui.WHITE, true), matchWrap(bottom = 4))
            card.addView(label(subtitle, 10f, Ui.MUTED, false).apply {
                setPadding(0, 0, 0, dp(7))
            }, matchWrap())
            return card
        }

        when (selectedTab) {
            TAB_SENSORS -> {
                pageHeading(
                    "SENSOR LIBRARY",
                    "Choose your readings",
                    "Build the data set that appears in your floating overlay."
                )

                val selectedCount = config.selectedThermalSensors.size +
                    config.customEntries.size +
                    (if (config.showCpuTemp) 1 else 0) +
                    (if (config.showGpuTemp) 1 else 0)
                val summary = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(13), dp(12), dp(13), dp(12))
                    background = rounded(Ui.PANEL_ALT, Ui.EDGE, 15)
                }
                val summaryCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                summaryCopy.addView(label("SELECTED HUD ROWS", 8.5f, Ui.MUTED, true).apply {
                    letterSpacing = 0.9f
                }, matchWrap(bottom = 4))
                summaryCopy.addView(label("$selectedCount configured", 15f, Ui.WHITE, true), matchWrap())
                summary.addView(summaryCopy, LinearLayout.LayoutParams(0, -2, 1f))
                summary.addView(label(if (fullCatalogLoaded) "${allSourceReadings().size} sensors found" else "Scan needed", 9f,
                    if (fullCatalogLoaded) Ui.MINT else Ui.WARM, true).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    background = rounded(Ui.BG, Ui.EDGE, 10)
                })
                page.addView(summary, matchWrap(bottom = 8))

                sectionTitle("Thermal source groups", "SELECT TO EXPAND")
                addExpandableHeader(page, "CPU cores", coreCountLabel(), showCores, Ui.MINT) {
                    showCores = !showCores
                    rebuildKeepingScroll()
                    requestCatalogMode(needsCatalog())
                }
                if (showCores) buildSourceGroups(page, false)

                addExpandableHeader(page, "GPU sources", gpuCountLabel(), showGpuSources, Ui.BLUE) {
                    showGpuSources = !showGpuSources
                    rebuildKeepingScroll()
                    requestCatalogMode(needsCatalog())
                }
                if (showGpuSources) buildSourceGroups(page, true)

                addExpandableHeader(page, "Available sensors", allSourceReadings().size.toString(), showAllSensors, Ui.BLUE) {
                    showAllSensors = !showAllSensors
                    rebuildKeepingScroll()
                    requestCatalogMode(needsCatalog())
                }
                if (showAllSensors) buildAllSensors(page)

                addExpandableHeader(page, "Custom rows", config.customEntries.size.toString(), showCustomEditor, Ui.WARM) {
                    showCustomEditor = !showCustomEditor
                    rebuildKeepingScroll()
                    requestCatalogMode(needsCatalog())
                }
                if (showCustomEditor) buildCustomRows(page)

                if (!fullCatalogLoaded) {
                    page.addView(button("Scan all thermal sensors", true, Ui.BLUE).apply {
                        setOnClickListener { scanCatalog() }
                    }, matchWrap(top = 11))
                }
            }

            TAB_APPEARANCE -> {
                pageHeading(
                    "HUD PERSONALIZATION",
                    "Appearance & position",
                    "Fine-tune the floating panel without changing your sensor choices."
                )

                val preview = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(13), dp(14), dp(13))
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(0xFF17243A.toInt(), 0xFF0B111C.toInt())
                    ).apply {
                        cornerRadius = dp(19).toFloat()
                        setStroke(dp(1), Ui.EDGE)
                    }
                }
                val previewHeader = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                previewHeader.addView(label("FLOATING HUD PREVIEW", 9f, Ui.WHITE, true).apply {
                    letterSpacing = 0.9f
                }, LinearLayout.LayoutParams(0, -2, 1f))
                previewHeader.addView(label(if (config.lockPosition) "LOCKED" else "MOVABLE", 8f,
                    if (config.lockPosition) Ui.WARM else Ui.MINT, true).apply {
                    setPadding(dp(8), dp(5), dp(8), dp(5))
                    background = rounded(Ui.BG, Ui.EDGE, 9)
                })
                preview.addView(previewHeader, matchWrap(bottom = 12))
                val previewValues = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                val cpuPreviewCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                cpuPreviewCol.addView(label("CPU", 9f, Ui.MINT, true), matchWrap(bottom = 4))
                val cpuPreview = label(temp(snapshot.cpuOverall), 18f, Ui.MINT, true).apply {
                    typeface = Typeface.create("sans-serif-monospace", Typeface.BOLD)
                }
                valueViews["cpuPreview"] = cpuPreview
                cpuPreviewCol.addView(cpuPreview, matchWrap())
                previewValues.addView(cpuPreviewCol, LinearLayout.LayoutParams(0, -2, 1f))
                val gpuPreviewCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                gpuPreviewCol.addView(label("GPU", 9f, Ui.BLUE, true), matchWrap(bottom = 4))
                val gpuPreview = label(temp(snapshot.gpuOverall), 18f, Ui.BLUE, true).apply {
                    typeface = Typeface.create("sans-serif-monospace", Typeface.BOLD)
                }
                valueViews["gpuPreview"] = gpuPreview
                gpuPreviewCol.addView(gpuPreview, matchWrap())
                previewValues.addView(gpuPreviewCol, LinearLayout.LayoutParams(0, -2, 1f))
                preview.addView(previewValues, matchWrap())
                page.addView(preview, matchWrap(bottom = 5))

                sectionTitle("Visual style")
                val visual = settingCard("Readability", "Adjust text intensity, panel opacity and overall scale.")
                addSlider(visual, "Text brightness", config.textBrightness, 35, 100, 1,
                    "35%", "100%", { "${it}%" }) {
                    save(config.copy(textBrightness = it))
                }
                addSlider(visual, "Background opacity", config.backgroundOpacity, 0, 65, 1,
                    "Transparent", "Solid", { "${it}%" }) {
                    save(config.copy(backgroundOpacity = it))
                }
                addSlider(visual, "HUD size", config.scalePercent, 70, 115, 1,
                    "70%", "115%", { "${it}%" }) {
                    save(config.copy(scalePercent = it))
                }
                page.addView(visual, matchWrap(bottom = 9))

                sectionTitle("Position & interaction")
                val position = settingCard("Placement", "Move the HUD to a comfortable corner of your screen.")
                addSlider(position, "Horizontal position", config.positionXDp, 0,
                    (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt().coerceAtLeast(0),
                    1, "Left", "Right", { "${it} dp" }) {
                    save(config.copy(positionXDp = it))
                }
                addSlider(position, "Vertical position", config.positionYDp, 24,
                    (resources.displayMetrics.heightPixels / resources.displayMetrics.density).toInt().coerceAtLeast(24),
                    1, "Top", "Bottom", { "${it} dp" }) {
                    save(config.copy(positionYDp = it))
                }
                val lockRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(7), dp(8), dp(7), dp(3))
                }
                val lockCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                lockCopy.addView(label("Lock overlay position", 12f, Ui.WHITE, true), matchWrap())
                lockCopy.addView(label("Prevent drag movement and allow touch-through", 9.5f, Ui.MUTED, false).apply {
                    setPadding(0, dp(4), 0, 0)
                }, matchWrap())
                lockRow.addView(lockCopy, LinearLayout.LayoutParams(0, -2, 1f))
                lockRow.addView(Switch(this).apply {
                    isChecked = config.lockPosition
                    buttonTintList = ColorStateList.valueOf(Ui.MINT)
                    setOnCheckedChangeListener { _, enabled ->
                        save(config.copy(lockPosition = enabled))
                        statusLine?.text = if (enabled) "Overlay position locked." else "Overlay position unlocked."
                    }
                })
                position.addView(lockRow, matchWrap(top = 8))
                page.addView(position, matchWrap(bottom = 9))

                page.addView(button("Apply display settings", false).apply {
                    setOnClickListener {
                        config.save(this@MainActivity)
                        statusLine?.text = "Display settings saved."
                    }
                }, matchWrap(top = 5))
            }

            else -> {
                pageHeading(
                    "DEVICE OVERVIEW",
                    "Thermal dashboard",
                    "Live CPU and graphics readings, controls, and update frequency."
                )

                val overview = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(13), dp(14), dp(13), dp(14))
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(0xFF17243A.toInt(), 0xFF0C1421.toInt())
                    ).apply {
                        cornerRadius = dp(21).toFloat()
                        setStroke(dp(1), Ui.EDGE)
                    }
                }
                val overviewHead = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val overviewCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                overviewCopy.addView(label("LIVE READINGS", 10f, Ui.WHITE, true).apply {
                    letterSpacing = 1.25f
                }, matchWrap())
                overviewCopy.addView(label("Device thermal telemetry", 10f, Ui.MUTED, false).apply {
                    setPadding(0, dp(4), 0, 0)
                }, matchWrap())
                overviewHead.addView(overviewCopy, LinearLayout.LayoutParams(0, -2, 1f))
                overviewHead.addView(label("LOCAL", 8f, Ui.BLUE, true).apply {
                    letterSpacing = 0.8f
                    setPadding(dp(9), dp(6), dp(9), dp(6))
                    background = rounded(Ui.BG, Ui.EDGE, 10)
                })
                overview.addView(overviewHead, matchWrap(bottom = 13))

                val metrics = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                }
                metrics.addView(temperatureTile("CPU", "Processor", "cpuTemp", config.showCpuTemp, Ui.MINT),
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })
                metrics.addView(temperatureTile("GPU", "Graphics / SoC", "gpuTemp", config.showGpuTemp, Ui.BLUE),
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(5) })
                overview.addView(metrics, matchWrap())
                page.addView(overview, matchWrap())

                val actions = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                actions.addView(button(if (HudConfig.isRunning()) "Apply changes" else "Start overlay", false).apply {
                    setOnClickListener {
                        config.save(this@MainActivity)
                        startOverlay()
                    }
                }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(6) })
                actions.addView(button("Stop", true, Ui.RED).apply {
                    setOnClickListener { stopOverlay() }
                }, LinearLayout.LayoutParams(0, dp(50), 0.45f).apply { marginStart = dp(6) })
                page.addView(actions, matchWrap(top = 10))

                sectionTitle("Temperature source")
                val sourceCard = settingCard(
                    "Average reading variant",
                    "Choose which source variant represents the overall CPU and GPU value."
                )
                addAverageSelector(sourceCard, "CPU source", config.cpuAverageVariant, true)
                addAverageSelector(sourceCard, "GPU source", config.gpuAverageVariant, false)
                page.addView(sourceCard, matchWrap(bottom = 8))

                sectionTitle("Sampling")
                val refreshCard = settingCard(
                    "Refresh interval",
                    "More frequent polling can increase power use."
                )
                addSlider(refreshCard, "Sensor update rate", config.refreshMs, 500, 3000, 10,
                    "0.50 s", "3.00 s", { String.format(Locale.US, "%.2f s", it / 1000f) }) {
                    save(config.copy(refreshMs = it))
                    restartSamplerForNewInterval()
                }
                page.addView(refreshCard, matchWrap(bottom = 8))

                val quickLinks = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                quickLinks.addView(button("Configure sensors  →", true, Ui.BLUE).apply {
                    setOnClickListener { selectTab(TAB_SENSORS) }
                }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(5) })
                quickLinks.addView(button("Customize HUD  →", true, Ui.MINT).apply {
                    setOnClickListener { selectTab(TAB_APPEARANCE) }
                }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(5) })
                page.addView(quickLinks, matchWrap(top = 7))
            }
        }

        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Ui.PANEL, Ui.BG)
            )
        }

        fun addNavigationItem(title: String, short: String, tab: String) {
            val selected = selectedTab == tab
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(5), dp(4), dp(5))
                background = rounded(if (selected) Ui.PANEL_ALT else Ui.BG,
                    if (selected) Ui.EDGE else Ui.BG, 13)
                isClickable = true
                isFocusable = true
            }
            item.addView(View(this).apply {
                background = rounded(if (selected) Ui.MINT else Ui.PANEL_ALT,
                    if (selected) Ui.MINT else Ui.PANEL_ALT, 2)
            }, LinearLayout.LayoutParams(dp(if (selected) 28 else 12), dp(2)).apply {
                bottomMargin = dp(6)
            })
            item.addView(label(short, 8f, if (selected) Ui.MINT else Ui.MUTED, true).apply {
                letterSpacing = 0.7f
            }, matchWrap(bottom = 3))
            item.addView(label(title, 10.5f, if (selected) Ui.WHITE else Ui.MUTED, selected), matchWrap())
            item.setOnClickListener { selectTab(tab) }
            navigation.addView(item, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            })
        }
        addNavigationItem("Dashboard", "01", TAB_DASHBOARD)
        addNavigationItem("Sensors", "02", TAB_SENSORS)
        addNavigationItem("Appearance", "03", TAB_APPEARANCE)
        shell.addView(navigation, LinearLayout.LayoutParams(-1, -2))

        setContentView(shell)
        updateValues()
    }

    private fun selectTab(tab: String) {
        if (selectedTab == tab) {
            scrollView?.smoothScrollTo(0, 0)
            return
        }
        val previous = selectedTab
        selectedTab = tab
        renderUi()
        scrollView?.post { scrollView?.scrollTo(0, 0) }
        if (tab == TAB_SENSORS) {
            requestCatalogMode(true)
        } else if (previous == TAB_SENSORS) {
            requestCatalogMode(false)
        }
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

    private fun addAverageSelector(parent: LinearLayout, title: String, current: String, cpu: Boolean) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(5), dp(2), dp(5), dp(2)) }
        box.addView(label(title, 11f, Ui.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
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
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(10), dp(10), dp(10))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Ui.PANEL_ALT, Ui.PANEL)
            ).apply {
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Ui.EDGE)
            }
            minimumHeight = dp(68)
        }
        val code = when (title) {
            "CPU cores" -> "CPU"
            "GPU sources" -> "GPU"
            "Available sensors" -> "ALL"
            "Custom rows" -> "+"
            else -> "HUD"
        }
        val iconBadge = label(code, if (code.length > 2) 9f else 12f, accent, true).apply {
            gravity = Gravity.CENTER
            background = rounded(Ui.BG, accent, 11)
        }
        row.addView(iconBadge, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            marginEnd = dp(11)
        })

        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(label(title, 13f, Ui.WHITE, true), matchWrap())
        val description = when (title) {
            "CPU cores" -> "Select per-core readings"
            "GPU sources" -> "Select graphics sensor sources"
            "Available sensors" -> "Browse every readable sensor"
            "Custom rows" -> "Build your own HUD row"
            else -> "Overlay display settings"
        }
        copy.addView(label(description, 9.5f, Ui.MUTED, false).apply {
            setPadding(0, dp(4), 0, 0)
            maxLines = 2
        }, matchWrap())
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))

        row.addView(label(count, 10f, accent, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = rounded(Ui.BG, Ui.EDGE, 10)
        }, wrapWrap(start = 6, end = 7))

        row.addView(button(if (expanded) "Close" else "Open", true, accent).apply {
            setPadding(dp(8), 0, dp(8), 0)
            minHeight = dp(36)
            minimumHeight = dp(36)
            setOnClickListener { onClick() }
        }, wrapWrap())
        parent.addView(row, matchWrap(top = 5, bottom = 2))
    }

    private fun buildSourceGroups(parent: LinearLayout, isGpu: Boolean) {
        val grouped = allSourceReadings().mapNotNull { reading ->
            val display = if (isGpu) SensorSourceLabels.gpuSourceOption(reading) else SensorSourceLabels.cpuCoreOption(reading)
            val variant = SensorSourceLabels.sourceVariant(reading) ?: return@mapNotNull null
            if (display == null) return@mapNotNull null
            SourceOption(reading, display.substringBefore(" · "), variant)
        }.groupBy { it.name }.toSortedMap(compareBySourceName())

        if (grouped.isEmpty()) {
            val message = if (isGpu) "No GPU usr/step sources are exposed." else "No CPU usr/step sources are exposed."
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
                    cell.addView(label(if (variant == "usr") "usr —" else "step —", 10f, Ui.MUTED, false)
                        .apply { setPadding(0, dp(8), 0, dp(8)) }, matchWrap())
                } else {
                    val readRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val check = CheckBox(this).apply {
                        text = variant
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

    private fun addSlider(
        parent: LinearLayout, title: String, current: Int, min: Int, max: Int, step: Int,
        left: String, right: String, display: (Int) -> String, onDone: (Int) -> Unit
    ) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(9), dp(5), dp(9), dp(5)) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(title, 12f, Ui.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        val valueLabel = label(display(current), 11f, Ui.MINT, true).apply { typeface = Typeface.create("sans-serif-monospace", Typeface.BOLD) }
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
                "cpuTemp", "cpuPreview" -> snapshot.cpuOverall
                "gpuTemp", "gpuPreview" -> snapshot.gpuOverall
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
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.015f
            isAllCaps = false
            setTextColor(if (outline) accent else Ui.BG)
            val fill = rounded(if (outline) Ui.PANEL_ALT else accent, accent, 14)
            background = RippleDrawable(
                ColorStateList.valueOf(if (outline) 0x33FFFFFF else 0x44FFFFFF),
                fill,
                null
            )
            setPadding(dp(12), dp(3), dp(12), dp(3))
            stateListAnimator = null
            minHeight = dp(44)
            minimumHeight = dp(44)
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(5), dp(6), dp(5))
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Ui.PANEL_ALT, Ui.PANEL)
        ).apply {
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Ui.EDGE)
        }
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(Ui.EDGE)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
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
