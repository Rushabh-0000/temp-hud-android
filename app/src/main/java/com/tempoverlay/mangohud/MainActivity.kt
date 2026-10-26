package com.tempoverlay.mangohud

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import java.util.UUID
import kotlin.math.roundToInt

private val Bg = Color(0xFF080C11)
private val Panel = Color(0xFF111922)
private val Mint = Color(0xFF8CFFD0)
private val Blue = Color(0xFF8FB5FF)
private val MainText = Color(0xFFF0F4F8)
private val Muted = Color(0xFF8D9BAA)
private val Edge = Color(0xFF293643)

private val CleanTypography: Typography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.copy(fontFamily = FontFamily.SansSerif),
        displayMedium = t.displayMedium.copy(fontFamily = FontFamily.SansSerif),
        displaySmall = t.displaySmall.copy(fontFamily = FontFamily.SansSerif),
        headlineLarge = t.headlineLarge.copy(fontFamily = FontFamily.SansSerif),
        headlineMedium = t.headlineMedium.copy(fontFamily = FontFamily.SansSerif),
        headlineSmall = t.headlineSmall.copy(fontFamily = FontFamily.SansSerif),
        titleLarge = t.titleLarge.copy(fontFamily = FontFamily.SansSerif),
        titleMedium = t.titleMedium.copy(fontFamily = FontFamily.SansSerif),
        titleSmall = t.titleSmall.copy(fontFamily = FontFamily.SansSerif),
        bodyLarge = t.bodyLarge.copy(fontFamily = FontFamily.SansSerif),
        bodyMedium = t.bodyMedium.copy(fontFamily = FontFamily.SansSerif),
        bodySmall = t.bodySmall.copy(fontFamily = FontFamily.SansSerif),
        labelLarge = t.labelLarge.copy(fontFamily = FontFamily.SansSerif),
        labelMedium = t.labelMedium.copy(fontFamily = FontFamily.SansSerif),
        labelSmall = t.labelSmall.copy(fontFamily = FontFamily.SansSerif)
    )
}

private val coreKeys = (0..7).map { "core${it}Temp" }
private val coreLabels = (0..7).map { "CPU $it" }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(8, 12, 17)
        window.navigationBarColor = android.graphics.Color.rgb(8, 12, 17)
        window.setDecorFitsSystemWindows(false)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Mint,
                    secondary = Blue,
                    background = Bg,
                    surface = Panel,
                    onPrimary = Bg,
                    onBackground = MainText,
                    onSurface = MainText
                ),
                typography = CleanTypography
            ) {
                Surface(Modifier.fillMaxSize(), color = Bg) { SettingsScreen() }
            }
        }
    }
}

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val safeTop = with(density) { WindowInsets.safeDrawing.getTop(this).toDp().value.toInt() }
    var config by remember { mutableStateOf(HudConfig.load(context)) }
    val snapshot by TemperatureRepository.snapshots.collectAsState()
    var sensorCatalogSnapshot by remember { mutableStateOf(TemperatureSnapshot()) }
    var overlayActive by remember { mutableStateOf(HudConfig.isRunning(context)) }
    var screenResumed by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var showAllSensors by remember { mutableStateOf(false) }
    var showCores by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(false) }
    var showCustomEditor by remember { mutableStateOf(false) }
    var customLabel by remember { mutableStateOf("") }
    var customSensorId by remember { mutableStateOf("") }
    var sensorMenuOpen by remember { mutableStateOf(false) }

    val lifecycle = (context as? ComponentActivity)?.lifecycle
    DisposableEffect(lifecycle) {
        if (lifecycle == null) {
            screenResumed = true
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> screenResumed = true
                    Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> screenResumed = false
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            screenResumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            onDispose { lifecycle.removeObserver(observer) }
        }
    }

    val reader = remember { SensorReader() }
    val latestConfig by rememberUpdatedState(config)
    LaunchedEffect(reader, screenResumed, showAllSensors, showCustomEditor) {
        if (!screenResumed) return@LaunchedEffect
        while (true) {
            val current = latestConfig
            val serviceOwnsPolling = HudConfig.isRunning(context)
            overlayActive = serviceOwnsPolling
            val needsCatalog = showAllSensors || showCustomEditor

            // The overlay service is the single sampler while active. The app only
            // scans thermal files when visible and the service is stopped, or when
            // the user explicitly opens the sensor/custom-row picker.
            if (!serviceOwnsPolling || needsCatalog) {
                val fresh = withContext(Dispatchers.IO) {
                    reader.sample(current, includeAllForSettings = needsCatalog)
                }
                if (needsCatalog) sensorCatalogSnapshot = fresh
                if (!serviceOwnsPolling) TemperatureRepository.publish(fresh)
            }
            delay(current.refreshMs.toLong())
        }
    }

    fun save(next: HudConfig) {
        config = next
        next.save(context)
    }

    fun startOverlay() {
        if (!Settings.canDrawOverlays(context)) {
            status = "Allow Display over other apps, then return here."
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
            return
        }
        try {
            val intent = Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            overlayActive = true
            status = "Overlay applied."
        } catch (e: Exception) {
            status = "Could not start overlay: ${e.javaClass.simpleName}"
        }
    }

    fun stopOverlay() {
        context.stopService(Intent(context, OverlayService::class.java))
        overlayActive = false
        status = "Overlay stopped."
    }

    fun temp(reading: ThermalReading?): String =
        reading?.celsius?.let { String.format(Locale.US, "%.1f°C", it) } ?: "N/A"

    val sortedSensors = sensorCatalogSnapshot.allSensors.sortedWith(
        compareBy<ThermalReading> { it.label }.thenBy { it.id }
    )
    val filteredSensors = sortedSensors.filter {
        val haystack = "${it.label} ${it.id} ${it.sourceName}".lowercase(Locale.US)
        haystack.contains(search.trim().lowercase(Locale.US))
    }
    val selectedCustomSensor = sortedSensors.firstOrNull { it.id == customSensorId }
        ?: sortedSensors.firstOrNull()
    val screenMaxX = (configuration.screenWidthDp - 8).coerceAtLeast(0)
    val estimatedHudHeight = config.selectedCount() * 16 + 10
    val screenMaxY = (configuration.screenHeightDp - estimatedHudHeight - 8).coerceAtLeast(safeTop + 16)
    val screenMinY = (safeTop + 16).coerceAtMost(screenMaxY)

    Column(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .background(Bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(38.dp).background(Mint.copy(alpha = 0.14f), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("TH", color = Mint, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("Temp HUD", color = MainText, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("CPU & GPU TEMPERATURES", color = Muted, fontSize = 10.sp, letterSpacing = 0.8.sp)
            }
            Surface(
                color = if (overlayActive) Color(0xFF15392D) else Panel,
                shape = RoundedCornerShape(50),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (overlayActive) Mint.copy(alpha = 0.5f) else Edge)
            ) {
                Text(
                    if (overlayActive) "RUNNING" else "READY",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    color = if (overlayActive) Mint else Muted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Overall values and toggles.
        CompactSensorRow(
            label = "CPU",
            value = temp(snapshot.cpuOverall),
            detail = null,
            checked = config.showCpuTemp,
            valueColor = snapshot.cpuOverall?.celsius?.let { Color(HudPalette.heatColor(it, config.textBrightness)) } ?: Muted,
            onChecked = { save(config.copy(showCpuTemp = it)) }
        )
        CompactSensorRow(
            label = "GPU",
            value = temp(snapshot.gpuOverall),
            detail = null,
            checked = config.showGpuTemp,
            valueColor = snapshot.gpuOverall?.celsius?.let { Color(HudPalette.heatColor(it, config.textBrightness)) } ?: Muted,
            onChecked = { save(config.copy(showGpuTemp = it)) }
        )

        Divider(color = Edge, thickness = 0.7.dp)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("CPU cores", color = MainText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${(0..7).count { config.isEnabled(coreKeys[it]) }}/8", color = Muted, fontSize = 11.sp)
            TextButton(onClick = { showCores = !showCores }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                Text(if (showCores) "Hide" else "Edit", color = Mint, fontSize = 11.sp)
            }
        }
        if (showCores) {
            for (index in 0..7) {
                val reading = snapshot.cpuCores.getOrNull(index)
                CompactSensorRow(
                    label = coreLabels[index],
                    value = temp(reading),
                    detail = reading?.sourceName,
                    checked = config.isEnabled(coreKeys[index]),
                    valueColor = reading?.celsius?.let { Color(HudPalette.heatColor(it, config.textBrightness)) } ?: Muted,
                    onChecked = { save(config.withMetric(coreKeys[index], it)) }
                )
            }
        }

        Divider(color = Edge, thickness = 0.7.dp)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Available sensors", color = MainText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${sortedSensors.size}", color = Muted, fontSize = 11.sp)
            TextButton(onClick = { showAllSensors = !showAllSensors }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                Text(if (showAllSensors) "Hide" else "Browse", color = Mint, fontSize = 11.sp)
            }
        }
        if (showAllSensors) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("Search sensor name", fontSize = 12.sp) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = MainText),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            )
            Text("Turn on a sensor to add its temperature to the overlay.", color = Muted, fontSize = 10.sp)
            filteredSensors.forEach { sensor ->
                val key = HudConfig.SENSOR_PREFIX + sensor.id
                CompactSensorRow(
                    label = sensor.label,
                    value = temp(sensor),
                    detail = "${sensor.id} · ${sensor.sourceName}",
                    checked = config.selectedThermalSensors.contains(sensor.id),
                    valueColor = sensor.celsius?.let { Color(HudPalette.heatColor(it, config.textBrightness)) } ?: Muted,
                    onChecked = { save(config.withMetric(key, it)) }
                )
            }
            if (filteredSensors.isEmpty()) Text("No readable temperature sensors match.", color = Muted, fontSize = 11.sp)
        }

        Divider(color = Edge, thickness = 0.7.dp)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Custom rows", color = MainText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${config.customEntries.size}", color = Muted, fontSize = 11.sp)
            TextButton(onClick = { showCustomEditor = !showCustomEditor }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                Text(if (showCustomEditor) "Close" else "Add", color = Mint, fontSize = 11.sp)
            }
        }
        if (showCustomEditor) {
            OutlinedTextField(
                value = customLabel,
                onValueChange = { customLabel = it.take(30) },
                placeholder = { Text("Label, e.g. Hotspot", fontSize = 12.sp) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = MainText),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            )
            Box {
                OutlinedButton(onClick = { sensorMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(selectedCustomSensor?.let { "${it.label} · ${it.id}" } ?: "Choose a sensor",
                        color = MainText, fontSize = 11.sp, maxLines = 1)
                }
                DropdownMenu(
                    expanded = sensorMenuOpen,
                    onDismissRequest = { sensorMenuOpen = false },
                    modifier = Modifier.heightIn(max = 340.dp).background(Panel)
                ) {
                    sortedSensors.forEach { sensor ->
                        DropdownMenuItem(
                            text = { Text("${sensor.label} · ${sensor.id}", fontSize = 11.sp, maxLines = 1) },
                            onClick = {
                                customSensorId = sensor.id
                                sensorMenuOpen = false
                            }
                        )
                    }
                }
            }
            Button(
                onClick = {
                    val chosen = sortedSensors.firstOrNull { it.id == (customSensorId.ifEmpty { selectedCustomSensor?.id ?: "" }) }
                    val label = customLabel.trim()
                    if (chosen != null && label.isNotEmpty()) {
                        save(config.copy(customEntries = config.customEntries + CustomHudEntry(label = label, sensorId = chosen.id)))
                        customLabel = ""
                        customSensorId = chosen.id
                        status = "Custom row added."
                    }
                },
                enabled = customLabel.trim().isNotEmpty() && selectedCustomSensor != null,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)
            ) { Text("Add overlay row", fontWeight = FontWeight.SemiBold) }
        }
        config.customEntries.forEach { entry ->
            val sensor = snapshot.allSensors.firstOrNull { it.id == entry.sensorId }
                ?: sensorCatalogSnapshot.allSensors.firstOrNull { it.id == entry.sensorId }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(entry.label, color = MainText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(sensor?.let { "${it.label} · ${it.id}" } ?: "Sensor unavailable",
                        color = Muted, fontSize = 10.sp, maxLines = 1)
                }
                Text(temp(sensor), color = sensor?.celsius?.let { Color(HudPalette.heatColor(it, config.textBrightness)) } ?: Muted, fontSize = 11.sp)
                TextButton(
                    onClick = { save(config.copy(customEntries = config.customEntries.filterNot { it.id == entry.id })) },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) { Text("Remove", color = Color(0xFFFF8888), fontSize = 10.sp) }
            }
        }

        Divider(color = Edge, thickness = 0.7.dp)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Refresh interval", color = MainText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(String.format(Locale.US, "%.2f s", config.refreshMs / 1000f), color = Mint, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
        Slider(
            value = config.refreshMs.toFloat(),
            onValueChange = { save(config.copy(refreshMs = ((it / 50f).roundToInt() * 50).coerceIn(500, 5000))) },
            valueRange = 500f..5000f,
            steps = 89,
            colors = SliderDefaults.colors(thumbColor = Mint, activeTrackColor = Mint, inactiveTrackColor = Edge)
        )

        TextButton(onClick = { showControls = !showControls }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)) {
            Text(if (showControls) "Hide display and position settings" else "Display and position settings",
                color = Mint, fontSize = 12.sp)
        }
        if (showControls) {
            SettingsSlider("HUD size", "${config.scalePercent}%", config.scalePercent.toFloat(), 70f..115f, 8,
                "Small", "Large") { save(config.copy(scalePercent = it.toInt())) }
            SettingsSlider("Text brightness", "${config.textBrightness}%", config.textBrightness.toFloat(), 35f..100f, 12,
                "Dim", "Bright") { save(config.copy(textBrightness = it.toInt())) }
            SettingsSlider("Background opacity", "${config.backgroundOpacity}%", config.backgroundOpacity.toFloat(), 0f..65f, 12,
                "Text only", "More visible") { save(config.copy(backgroundOpacity = it.toInt())) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Lock position", color = MainText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (config.lockPosition) "Locked · all HUD touches pass through." else "Unlocked · drag anywhere on the rows.",
                        color = Muted, fontSize = 10.sp)
                }
                Switch(checked = config.lockPosition, onCheckedChange = { save(config.copy(lockPosition = it)) })
            }
            SettingsSlider("Horizontal position", "${config.positionXDp} dp", config.positionXDp.toFloat(),
                0f..screenMaxX.toFloat().coerceAtLeast(0f), 0, "Left", "Right") { save(config.copy(positionXDp = it.toInt())) }
            SettingsSlider("Vertical position", "${config.positionYDp} dp", config.positionYDp.toFloat(),
                screenMinY.toFloat()..screenMaxY.toFloat().coerceAtLeast(screenMinY.toFloat()), 0, "Below notch", "Bottom") {
                save(config.copy(positionYDp = it.toInt()))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Button(
                onClick = { startOverlay() }, modifier = Modifier.weight(1f).height(44.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg),
                shape = RoundedCornerShape(10.dp)
            ) { Text("Apply / start", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            OutlinedButton(
                onClick = { stopOverlay() }, modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(10.dp)
            ) { Text("Stop", color = MainText, fontSize = 12.sp) }
        }
        if (status.isNotBlank()) Text(status, color = Mint, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun CompactSensorRow(
    label: String,
    value: String,
    detail: String?,
    checked: Boolean,
    valueColor: Color,
    onChecked: (Boolean) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(label, color = MainText, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(value, color = valueColor, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(5.dp))
            Checkbox(
                checked = checked,
                onCheckedChange = onChecked,
                modifier = Modifier.size(30.dp),
                colors = CheckboxDefaults.colors(checkedColor = Mint, uncheckedColor = Muted, checkmarkColor = Bg)
            )
        }
        if (!detail.isNullOrBlank()) Text(detail, color = Muted, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun SettingsSlider(
    title: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>,
    steps: Int, low: String, high: String, onChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(title, color = MainText, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(valueText, color = Mint, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(thumbColor = Mint, activeTrackColor = Mint, inactiveTrackColor = Edge)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(low, color = Muted, fontSize = 9.sp)
            Text(high, color = Muted, fontSize = 9.sp)
        }
    }
}
