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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

private val SansTypography: Typography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = FontFamily.SansSerif),
        displayMedium = base.displayMedium.copy(fontFamily = FontFamily.SansSerif),
        displaySmall = base.displaySmall.copy(fontFamily = FontFamily.SansSerif),
        headlineLarge = base.headlineLarge.copy(fontFamily = FontFamily.SansSerif),
        headlineMedium = base.headlineMedium.copy(fontFamily = FontFamily.SansSerif),
        headlineSmall = base.headlineSmall.copy(fontFamily = FontFamily.SansSerif),
        titleLarge = base.titleLarge.copy(fontFamily = FontFamily.SansSerif),
        titleMedium = base.titleMedium.copy(fontFamily = FontFamily.SansSerif),
        titleSmall = base.titleSmall.copy(fontFamily = FontFamily.SansSerif),
        bodyLarge = base.bodyLarge.copy(fontFamily = FontFamily.SansSerif),
        bodyMedium = base.bodyMedium.copy(fontFamily = FontFamily.SansSerif),
        bodySmall = base.bodySmall.copy(fontFamily = FontFamily.SansSerif),
        labelLarge = base.labelLarge.copy(fontFamily = FontFamily.SansSerif),
        labelMedium = base.labelMedium.copy(fontFamily = FontFamily.SansSerif),
        labelSmall = base.labelSmall.copy(fontFamily = FontFamily.SansSerif)
    )
}

private data class SensorOption(val key: String, val label: String, val hint: String, val accent: Color)
private val overallCpuOption = listOf(SensorOption("cpuTemp", "CPU", "Average CPU temperature", Mint))
private val coreOptions = (0..7).map { SensorOption("core${it}Temp", "CPU $it", "Individual core temperature", Mint) }
private val overallGpuOption = listOf(SensorOption("gpuTemp", "GPU", "Average GPU temperature", Blue))
private val textPalettes = HudPalette.textOptions

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(8, 12, 17)
        window.navigationBarColor = android.graphics.Color.rgb(8, 12, 17)
        window.setDecorFitsSystemWindows(false)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Mint, secondary = Blue, background = Bg, surface = Panel,
                    onPrimary = Bg, onBackground = TextMain, onSurface = TextMain
                ),
                typography = SansTypography
            ) {
                Surface(Modifier.fillMaxSize(), color = Bg) { TempHudScreen() }
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
    var showCoreOptions by remember { mutableStateOf(config.coreTempEnabled()) }
    var showGpuOptions by remember { mutableStateOf(config.selectedGpuSensors.isNotEmpty()) }
    var showSources by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready · settings save on this device.") }
    val sensorReader = remember { SensorReader() }
    val currentConfig by rememberUpdatedState(config)
    var snapshot by remember { mutableStateOf(TemperatureSnapshot()) }

    LaunchedEffect(sensorReader) {
        while (true) {
            val now = currentConfig
            snapshot = withContext(Dispatchers.IO) { sensorReader.sample(now, includeAllForSettings = true) }
            delay(now.refreshMs.toLong())
        }
    }

    fun save(next: HudConfig) {
        config = next
        next.save(context)
    }

    fun startOverlay() {
        if (!Settings.canDrawOverlays(context)) {
            status = "Allow Display over other apps, then return and tap START OVERLAY."
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
            return
        }
        try {
            val intent = Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            running = true
            status = "Applied · the HUD text layer passes touches through."
        } catch (e: Exception) {
            status = "Could not start overlay: ${e.javaClass.simpleName}"
        }
    }

    fun stopOverlay() {
        context.stopService(Intent(context, OverlayService::class.java))
        running = false
        status = "Overlay stopped."
    }

    fun tempText(reading: ThermalReading?): String =
        reading?.celsius?.let { String.format(Locale.US, "%.1f°C", it) } ?: "N/A"

    val previewRows = buildList<Pair<String, String>> {
        if (config.showCpuTemp) add("cpuTemp" to "CPU")
        if (config.showGpuTemp) add("gpuTemp" to "GPU")
        for (core in 0..7) {
            val key = "core${core}Temp"
            if (config.isEnabled(key)) add(key to "CPU $core")
        }
        snapshot.gpuSensors.filter { config.isEnabled(HudConfig.GPU_SENSOR_PREFIX + it.id) }
            .forEachIndexed { index, sensor ->
                add((HudConfig.GPU_SENSOR_PREFIX + sensor.id) to "GPU SENSOR ${index + 1}")
            }
    }

    val widthDp = 152f * config.scalePercent / 100f
    val maxX = (configuration.screenWidthDp - widthDp - 2f).toInt().coerceAtLeast(0)
    val panelHeightDp = (12f + config.selectedCount() * 16.5f) * config.scalePercent / 100f
    val maxY = (configuration.screenHeightDp.toFloat() - panelHeightDp - 2f).toInt().coerceAtLeast(safeTopDp + 16)
    val minY = (safeTopDp + 16).coerceAtMost(maxY)

    Column(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .background(Bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.size(42.dp).background(PanelRaised, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Text("TH", color = Mint, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Temp HUD", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Text("CPU & GPU TEMPERATURES", color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp)
            }
            Surface(color = if (running) Color(0xFF15392D) else PanelRaised,
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, if (running) Color(0xFF2C7155) else Outline)) {
                Text(if (running) "RUNNING" else "READY",
                    Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    color = if (running) Mint else TextMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Surface(color = Panel, shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, Outline), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("LIVE HUD PREVIEW", color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("Only selected temperature rows appear over your game.", color = TextMuted, fontSize = 11.sp)
                    }
                    Text("${previewRows.size} rows", color = Mint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                if (previewRows.isEmpty()) Text("Select at least one temperature.", color = TextMuted, fontSize = 12.sp)
                previewRows.forEach { (key, label) ->
                    val reading = snapshot.readingFor(key)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = Color(HudPalette.labelColor(config.textColorIndex, config.textBrightness)),
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(tempText(reading),
                            color = reading?.celsius?.let { Color(HudPalette.heatColor(it, config.textBrightness)) } ?: TextMuted,
                            fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        SectionTitle("01", "OVERALL TEMPERATURES", "Averages across unique CPU/GPU-labelled sensors.")
        MetricGrid(overallCpuOption, config, snapshot) { key, enabled -> save(config.withMetric(key, enabled)) }
        MetricGrid(overallGpuOption, config, snapshot) { key, enabled -> save(config.withMetric(key, enabled)) }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("INDIVIDUAL CPU CORES", color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("Off by default · enable only the cores you need.", color = TextMuted, fontSize = 11.sp)
            }
            Text("${(0..7).count { config.isEnabled("core${it}Temp") }}/8", color = Mint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { showCoreOptions = !showCoreOptions }) {
                Text(if (showCoreOptions) "HIDE" else "EDIT", color = Mint, fontSize = 11.sp)
            }
        }
        if (showCoreOptions) MetricGrid(coreOptions, config, snapshot) { key, enabled -> save(config.withMetric(key, enabled)) }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("INDIVIDUAL GPU SENSORS", color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("These are real GPU-labelled thermal zones, not fabricated GPU cores.", color = TextMuted, fontSize = 11.sp)
            }
            Text("${config.selectedGpuSensors.size}", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { showGpuOptions = !showGpuOptions }) {
                Text(if (showGpuOptions) "HIDE" else "EDIT", color = Blue, fontSize = 11.sp)
            }
        }
        if (showGpuOptions) {
            if (snapshot.gpuSensors.isEmpty()) {
                InfoCard("No readable GPU-labelled thermal sensors found. GPU overall temperature will be N/A unless a GPU sensor is exposed.")
            } else {
                snapshot.gpuSensors.forEach { sensor ->
                    val key = HudConfig.GPU_SENSOR_PREFIX + sensor.id
                    MetricTile(sensor.label, sensor.sourceNames.firstOrNull() ?: sensor.id,
                        config.isEnabled(key), sensor.celsius, Blue) { enabled -> save(config.withMetric(key, enabled)) }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("SENSOR SOURCES", color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("Exact thermal-zone names used for each reading.", color = TextMuted, fontSize = 11.sp)
            }
            TextButton(onClick = { showSources = !showSources }) {
                Text(if (showSources) "HIDE" else "VIEW", color = Mint, fontSize = 11.sp)
            }
        }
        if (showSources) {
            SourceGroup("Overall CPU average", snapshot.cpuOverall?.sourceNames.orEmpty())
            for (core in 0..7) SourceGroup("CPU $core", snapshot.cpuCores.getOrNull(core)?.sourceNames.orEmpty())
            SourceGroup("Overall GPU average", snapshot.gpuOverall?.sourceNames.orEmpty())
            snapshot.gpuSensors.forEach { SourceGroup(it.label, it.sourceNames) }
        }

        SectionTitle("02", "TEXT & TRANSPARENCY", "No border. Background opacity defaults to zero.")
        SettingsCard {
            SliderSetting("Background opacity", "${config.transparency}%", config.transparency.toFloat(), 0f..65f, 12,
                "Text only", "More solid") { save(config.copy(transparency = it.toInt())) }
            Divider(color = Outline)
            SliderSetting("Text brightness", "${config.textBrightness}%", config.textBrightness.toFloat(), 35f..100f, 12,
                "Dim", "Bright") { save(config.copy(textBrightness = it.toInt())) }
            Divider(color = Outline)
            SliderSetting("Refresh interval", String.format(Locale.US, "%.2f s", config.refreshMs / 1000f), config.refreshMs.toFloat(),
                500f..3000f, 9, "0.5 s", "3.0 s") { save(config.copy(refreshMs = it.toInt())) }
            Divider(color = Outline)
            SliderSetting("HUD size", "${config.scalePercent}%", config.scalePercent.toFloat(), 70f..110f, 7,
                "Smaller", "Larger") { save(config.copy(scalePercent = it.toInt())) }
            Text("Label text colour", color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                textPalettes.forEachIndexed { index, option ->
                    FilterChip(
                        selected = config.textColorIndex == index,
                        onClick = { save(config.copy(textColorIndex = index)) },
                        label = { Text(option.name, fontSize = 10.sp, maxLines = 1) },
                        leadingIcon = { Box(Modifier.size(8.dp).background(Color(option.argb), RoundedCornerShape(50))) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(option.argb).copy(alpha = 0.16f),
                            selectedLabelColor = Color(option.argb)
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        SectionTitle("03", "TOUCH-THROUGH & POSITION", "The text window never receives touches.")
        SettingsCard {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Lock position", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (config.lockPosition) "Locked · handle also passes touches through." else "Only a tiny handle receives drag touches.",
                        color = TextMuted, fontSize = 11.sp)
                }
                Switch(checked = config.lockPosition, onCheckedChange = { save(config.copy(lockPosition = it)) })
            }
            Divider(color = Outline)
            SliderSetting("Horizontal X", "${config.positionXDp} dp", config.positionXDp.toFloat(),
                0f..maxX.toFloat().coerceAtLeast(0f), 0, "Left", "Right") { save(config.copy(positionXDp = it.toInt())) }
            Divider(color = Outline)
            SliderSetting("Vertical Y", "${config.positionYDp} dp", config.positionYDp.toFloat(),
                minY.toFloat()..maxY.toFloat().coerceAtLeast(minY.toFloat()), 0, "Below notch", "Bottom") {
                save(config.copy(positionYDp = it.toInt()))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { startOverlay() }, modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)) {
                Text(if (running) "APPLY SETTINGS" else "START OVERLAY", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            OutlinedButton(onClick = { stopOverlay() }, modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Outline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMain)) {
                Text("STOP", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
        }
        Text(status, color = Mint, fontSize = 11.sp, lineHeight = 16.sp)
        Text("Android 11+ · no network permission · no updater · no analytics",
            color = TextMuted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 8.dp))
    }
}

@Composable
private fun SectionTitle(number: String, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(number, color = Mint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(subtitle, color = TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun MetricGrid(items: List<SensorOption>, config: HudConfig, snapshot: TemperatureSnapshot, onChange: (String, Boolean) -> Unit) {
    items.chunked(2).forEach { rowItems ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            rowItems.forEach { item ->
                Box(Modifier.weight(1f)) {
                    val reading = snapshot.readingFor(item.key)
                    MetricTile(item.label, reading?.sourceNames?.firstOrNull() ?: item.hint,
                        config.isEnabled(item.key), reading?.celsius, item.accent) { enabled -> onChange(item.key, enabled) }
                }
            }
            if (rowItems.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricTile(label: String, subtitle: String, checked: Boolean, temperature: Float?, color: Color,
                       onCheckedChange: (Boolean) -> Unit) {
    Surface(
        color = if (checked) color.copy(alpha = 0.08f) else Panel,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (checked) color.copy(alpha = 0.5f) else Outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, color = if (checked) color else TextMain, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(temperature?.let { String.format(Locale.US, "%.1f°C", it) } ?: "N/A",
                    color = temperature?.let { Color(HudPalette.heatColor(it, 100)) } ?: TextMuted,
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(subtitle, color = TextMuted, fontSize = 9.sp, lineHeight = 12.sp)
            }
            Checkbox(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.size(30.dp),
                colors = CheckboxDefaults.colors(checkedColor = color, uncheckedColor = TextMuted, checkmarkColor = Bg))
        }
    }
}

@Composable
private fun SourceGroup(title: String, sources: List<String>) {
    Surface(color = Panel, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Outline), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = TextMain, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(if (sources.isEmpty()) "No readable matching sensor" else sources.joinToString(separator = "\n"),
                color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun InfoCard(message: String) {
    Surface(color = Panel, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Outline), modifier = Modifier.fillMaxWidth()) {
        Text(message, color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Panel, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Outline), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(7.dp), content = content)
    }
}

@Composable
private fun SliderSetting(title: String, valueText: String, value: Float,
                          range: ClosedFloatingPointRange<Float>, steps: Int, left: String, right: String,
                          onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(title, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(valueText, color = Mint, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange,
            valueRange = range, steps = steps,
            colors = SliderDefaults.colors(thumbColor = Mint, activeTrackColor = Mint, inactiveTrackColor = Outline))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(left, color = TextMuted, fontSize = 9.sp)
            Text(right, color = TextMuted, fontSize = 9.sp)
        }
    }
}
