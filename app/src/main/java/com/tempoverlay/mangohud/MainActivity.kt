package com.tempoverlay.mangohud

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalTextStyle
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

private val Bg = Color(0xFF080C11)
private val Panel = Color(0xFF111922)
private val PanelRaised = Color(0xFF18232E)
private val Mint = Color(0xFF8CFFD0)
private val Blue = Color(0xFF8FB5FF)
private val TextMain = Color(0xFFE7EEF7)
private val TextMuted = Color(0xFF8D9BAA)
private val Outline = Color(0xFF2A3745)

private data class MetricOption(
    val key: String,
    val label: String,
    val hint: String,
    val accent: Color
)

private val cpuMetrics = listOf(
    MetricOption("cpuTemp", "OVERALL CPU TEMP", "Highest CPU-labelled sensor", Mint)
)
private val coreMetrics = (0..7).map { index ->
    MetricOption("core${index}Temp", "CPU ${index}", "Individual core sensor", Mint)
}
private val gpuMetrics = listOf(
    MetricOption("gpuTemp", "GPU TEMP", "GPU-labelled sensor", Blue)
)

private val palette = HudPalette.textOptions

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(8, 12, 17)
        window.navigationBarColor = android.graphics.Color.rgb(8, 12, 17)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Mint,
                    secondary = Blue,
                    background = Bg,
                    surface = Panel,
                    onPrimary = Bg,
                    onBackground = TextMain,
                    onSurface = TextMain
                )
            ) {
                CompositionLocalProvider(
                    LocalTextStyle provides TextStyle(fontFamily = FontFamily.SansSerif, color = TextMain)
                ) {
                    Surface(Modifier.fillMaxSize(), color = Bg) {
                        TempHudScreen()
                    }
                }
            }
        }
    }
}

@Composable
private fun TempHudScreen() {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val safeTopDp = with(density) { WindowInsets.safeDrawing.getTop(this).toDp().value.toInt() }
    var config by remember { mutableStateOf(HudConfig.load(context)) }
    var running by remember { mutableStateOf(HudConfig.isRunning(context)) }
    var showCoreSettings by remember { mutableStateOf(config.coreTempEnabled()) }
    var status by remember { mutableStateOf("Ready · Changes save automatically.") }

    val reader = remember { SensorReader() }
    val latestConfig by rememberUpdatedState(config)
    var readings by remember { mutableStateOf(TemperatureSnapshot()) }

    LaunchedEffect(reader) {
        while (true) {
            val current = latestConfig
            readings = withContext(Dispatchers.IO) { reader.sample(current) }
            delay(current.refreshMs.toLong())
        }
    }

    fun save(next: HudConfig) {
        config = next
        next.save(context)
    }

    fun startHud() {
        if (!Settings.canDrawOverlays(context)) {
            status = "Allow Display over other apps, return here, then tap START OVERLAY."
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
            return
        }
        try {
            val intent = Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            running = true
            status = "Overlay active · sensor values refresh automatically."
        } catch (e: Exception) {
            status = "Could not start overlay: ${e.javaClass.simpleName}"
        }
    }

    fun stopHud() {
        context.stopService(Intent(context, OverlayService::class.java))
        running = false
        status = "Overlay stopped."
    }

    fun valueText(key: String): String =
        readings.valueFor(key)?.let { String.format(Locale.US, "%.1f°C", it) } ?: "N/A"

    val activeSensors = buildList {
        if (config.showCpuTemp) add("cpuTemp" to "CPU")
        listOf(
            config.showCore0Temp to "core0Temp" to "CPU 0",
            config.showCore1Temp to "core1Temp" to "CPU 1",
            config.showCore2Temp to "core2Temp" to "CPU 2",
            config.showCore3Temp to "core3Temp" to "CPU 3",
            config.showCore4Temp to "core4Temp" to "CPU 4",
            config.showCore5Temp to "core5Temp" to "CPU 5",
            config.showCore6Temp to "core6Temp" to "CPU 6",
            config.showCore7Temp to "core7Temp" to "CPU 7"
        ).forEach { (enabledAndKey, label) ->
            val enabled = enabledAndKey.first
            val key = enabledAndKey.second
            if (enabled) add(key to label)
        }
        if (config.showGpuTemp) add("gpuTemp" to "GPU")
    }

    val panelWidthDp = 152f * config.scalePercent / 100f
    val maxX = (configuration.screenWidthDp - panelWidthDp - 2f).toInt().coerceAtLeast(0)
    val panelHeightDp = (8f + config.selectedCount() * 16.5f + 4f) * config.scalePercent / 100f
    val maxY = (configuration.screenHeightDp - panelHeightDp - 2).coerceAtLeast(safeTopDp)
    val minY = (safeTopDp + 2).coerceAtMost(maxY)

    Column(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .background(Bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.size(42.dp).background(PanelRaised, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Text("TH", color = Mint, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Temp HUD", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                Text("TEMPERATURE OVERLAY", color = TextMuted, fontSize = 10.sp, letterSpacing = 1.1.sp)
            }
            Surface(
                color = if (running) Color(0xFF15392D) else PanelRaised,
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, if (running) Color(0xFF2C7155) else Outline)
            ) {
                Text(if (running) "RUNNING" else "READY", Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    color = if (running) Mint else TextMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Surface(color = Panel, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Outline), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("LIVE PREVIEW", color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("Only selected temperatures appear in the overlay.", color = TextMuted, fontSize = 11.sp)
                    }
                    Text("${activeSensors.size} selected", color = Mint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                if (activeSensors.isEmpty()) {
                    Text("Select at least one CPU or GPU sensor below.", color = TextMuted, fontSize = 12.sp)
                } else {
                    activeSensors.forEach { (key, label) ->
                        val reading = readings.valueFor(key)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, color = Color(HudPalette.labelColor(config.textColorIndex, config.textBrightness)), fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            Text(
                                reading?.let { String.format(Locale.US, "%.1f°C", it) } ?: "N/A",
                                color = reading?.let { Color(HudPalette.heatColor(it, config.textBrightness)) } ?: TextMuted,
                                fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        SectionTitle("01", "CPU TEMPERATURES", "Select each value separately.")
        MetricGrid(cpuMetrics, config, readings, ::valueText) { key, checked ->
            save(config.withMetric(key, checked))
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("INDIVIDUAL CPU CORES", color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("Only genuine per-core sensor readings are shown.", color = TextMuted, fontSize = 11.sp)
            }
            Text("${(0..7).count { config.isEnabled("core${it}Temp") }}/8", color = Mint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(3.dp))
            TextButton(onClick = { showCoreSettings = !showCoreSettings }) {
                Text(if (showCoreSettings) "HIDE" else "EDIT", color = Mint, fontSize = 11.sp)
            }
        }

        if (showCoreSettings) {
            MetricGrid(coreMetrics, config, readings, ::valueText) { key, checked ->
                save(config.withMetric(key, checked))
            }
        }

        SectionTitle("02", "GPU TEMPERATURE", "Adreno-labelled thermal sensor.")
        MetricGrid(gpuMetrics, config, readings, ::valueText) { key, checked ->
            save(config.withMetric(key, checked))
        }

        SectionTitle("03", "OVERLAY APPEARANCE", "Designed for readability over games.")
        SettingsCard {
            SliderSetting("Overlay size", "${config.scalePercent}%", config.scalePercent.toFloat(), 70f..110f, 7,
                "Smaller", "Larger") { save(config.copy(scalePercent = it.toInt())) }
            Divider(color = Outline)
            SliderSetting("Background opacity", "${config.transparency}%", config.transparency.toFloat(), 20f..100f, 15,
                "Transparent", "Solid") { save(config.copy(transparency = it.toInt())) }
            Divider(color = Outline)
            SliderSetting("Text brightness", "${config.textBrightness}%", config.textBrightness.toFloat(), 35f..100f, 12,
                "Dim", "Bright") { save(config.copy(textBrightness = it.toInt())) }
            Divider(color = Outline)
            SliderSetting("Refresh interval", String.format(Locale.US, "%.2f s", config.refreshMs / 1000f),
                config.refreshMs.toFloat(), 500f..3000f, 9, "0.5 s", "3.0 s") {
                save(config.copy(refreshMs = it.toInt()))
            }
        }

        Surface(color = Panel, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Outline), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("OVERLAY TEXT COLOUR", color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("Temperature values keep the green → yellow → orange → red heat scale.", color = TextMuted, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                    palette.forEachIndexed { index, option ->
                        FilterChip(
                            selected = config.textColorIndex == index,
                            onClick = { save(config.copy(textColorIndex = index)) },
                            label = { Text(option.name, fontSize = 10.sp) },
                            leadingIcon = {
                                Box(Modifier.size(10.dp).background(Color(option.argb), RoundedCornerShape(50)))
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(option.argb).copy(alpha = 0.15f),
                                selectedLabelColor = Color(option.argb)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        SectionTitle("04", "POSITION & LOCK", "X and Y positions are saved on this device.")
        SettingsCard {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Lock overlay position", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (config.lockPosition) "Drag disabled · use X/Y sliders to reposition." else "Drag the small handle at the panel's top edge.", color = TextMuted, fontSize = 11.sp)
                }
                Switch(checked = config.lockPosition, onCheckedChange = { save(config.copy(lockPosition = it)) })
            }
            Divider(color = Outline)
            SliderSetting("Horizontal position (X)", "${config.positionXDp} dp", config.positionXDp.toFloat(),
                0f..maxX.toFloat().coerceAtLeast(0f), 0, "Left", "Right") {
                save(config.copy(positionXDp = it.toInt()))
            }
            Divider(color = Outline)
            SliderSetting("Vertical position (Y)", "${config.positionYDp} dp", config.positionYDp.toFloat(),
                minY.toFloat()..maxY.toFloat().coerceAtLeast(minY.toFloat()), 0, "Top safe area", "Bottom") {
                save(config.copy(positionYDp = it.toInt()))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { startHud() }, modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)
            ) { Text(if (running) "APPLY SETTINGS" else "START OVERLAY", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            OutlinedButton(
                onClick = { stopHud() }, modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Outline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMain)
            ) { Text("STOP", fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }
        }

        Text(status, color = Mint, fontSize = 11.sp, lineHeight = 16.sp)
        Text("Android 11+ · no network permission · no updater · no analytics", color = TextMuted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 8.dp))
    }
}

@Composable
private fun SectionTitle(number: String, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(number, color = Mint, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(subtitle, color = TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun MetricGrid(
    items: List<MetricOption>,
    config: HudConfig,
    readings: TemperatureSnapshot,
    valueText: (String) -> String,
    onChange: (String, Boolean) -> Unit
) {
    items.chunked(2).forEach { rowItems ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            rowItems.forEach { item ->
                Box(Modifier.weight(1f)) {
                    MetricTile(item, config.isEnabled(item.key), valueText(item.key), readings.valueFor(item.key)) {
                        checked -> onChange(item.key, checked)
                    }
                }
            }
            if (rowItems.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricTile(
    item: MetricOption,
    checked: Boolean,
    valueText: String,
    reading: Float?,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        color = if (checked) item.accent.copy(alpha = 0.09f) else Panel,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (checked) item.accent.copy(alpha = 0.55f) else Outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.label, color = if (checked) item.accent else TextMain, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                Text(
                    if (checked) valueText else item.hint,
                    color = if (checked && reading != null) Color(HudPalette.heatColor(reading, 100)) else TextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.size(30.dp),
                colors = CheckboxDefaults.colors(checkedColor = item.accent, uncheckedColor = TextMuted, checkmarkColor = Bg)
            )
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Panel, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Outline), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp), content = content)
    }
}

@Composable
private fun SliderSetting(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    left: String,
    right: String,
    onChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(title, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(valueText, color = Mint, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(thumbColor = Mint, activeTrackColor = Mint, inactiveTrackColor = Outline)
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(left, color = TextMuted, fontSize = 9.sp)
            Text(right, color = TextMuted, fontSize = 9.sp)
        }
    }
}
