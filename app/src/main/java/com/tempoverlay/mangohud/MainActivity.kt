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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    MetricOption("cpuLoad", "CPU LOAD", "Overall usage", Mint),
    MetricOption("cpuClock", "CPU CLOCK", "Highest readable clock", Mint),
    MetricOption("cpuTemp", "CPU TEMP", "Overall CPU sensor", Mint)
)

private val coreMetrics = (0..7).map { index ->
    MetricOption("core${index}Temp", "CPU ${index} TEMP", "Core sensor only", Mint)
}

private val gpuMetrics = listOf(
    MetricOption("gpuLoad", "GPU LOAD", "Adreno usage", Blue),
    MetricOption("gpuClock", "GPU CLOCK", "Adreno frequency", Blue),
    MetricOption("gpuTemp", "GPU TEMP", "GPU-labelled sensor", Blue)
)

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
                Surface(Modifier.fillMaxSize(), color = Bg) {
                    TempHudScreen()
                }
            }
        }
    }
}

@Composable
private fun TempHudScreen() {
    val context = LocalContext.current
    var config by remember { mutableStateOf(HudConfig.load(context)) }
    var running by remember { mutableStateOf(HudConfig.isRunning(context)) }
    var showCoreSettings by remember { mutableStateOf(config.coreTempEnabled()) }
    var status by remember { mutableStateOf("Ready · Settings are saved on this device.") }

    fun save(next: HudConfig) {
        config = next
        next.save(context)
    }

    fun startHud() {
        if (!Settings.canDrawOverlays(context)) {
            status = "Allow Display over other apps, return here, then tap START HUD."
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
            )
            return
        }
        try {
            val intent = Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
            running = true
            status = "HUD applied. Drag the small top strip to move it."
        } catch (e: Exception) {
            status = "Could not start HUD: ${e.javaClass.simpleName}"
        }
    }

    fun stopHud() {
        context.stopService(Intent(context, OverlayService::class.java))
        running = false
        status = "HUD stopped."
    }

    Column(
        Modifier.fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(44.dp).background(PanelRaised, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("TH", color = Mint, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("TEMP HUD", color = TextMain, fontWeight = FontWeight.Black, fontSize = 21.sp, letterSpacing = 1.3.sp)
                Text("CPU + ADRENO TELEMETRY", color = TextMuted, fontSize = 9.sp, letterSpacing = 1.2.sp)
            }
            Surface(
                color = if (running) Color(0xFF15392D) else PanelRaised,
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, if (running) Color(0xFF2C7155) else Outline)
            ) {
                Text(
                    if (running) "LIVE" else "READY",
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    color = if (running) Mint else TextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Surface(
            color = Panel, shape = RoundedCornerShape(13.dp),
            border = BorderStroke(1.dp, Outline), modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("HUD PROFILE  /  REALME 9 SPEED EDITION", color = Mint, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Choose each value independently. Only selected items are drawn and polled. The floating panel stays compact and fixed-width; use its header to drag it.",
                    color = TextMuted, fontSize = 12.sp, lineHeight = 17.sp
                )
            }
        }

        SectionTitle("01", "CPU", "Overall readings")
        MetricGrid(cpuMetrics, config) { key, checked -> save(config.withMetric(key, checked)) }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("CPU CORE TEMPERATURES", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.8.sp)
                Text("CPU 0 through CPU 7 · individual switches", color = TextMuted, fontSize = 11.sp)
            }
            Text(
                "${listOf(config.showCore0Temp, config.showCore1Temp, config.showCore2Temp, config.showCore3Temp, config.showCore4Temp, config.showCore5Temp, config.showCore6Temp, config.showCore7Temp).count { it }}/8",
                color = Mint, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp
            )
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = { showCoreSettings = !showCoreSettings }) {
                Text(if (showCoreSettings) "HIDE" else "EDIT", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (showCoreSettings) {
            MetricGrid(coreMetrics, config) { key, checked -> save(config.withMetric(key, checked)) }
        }

        SectionTitle("02", "GPU", "Adreno readings")
        MetricGrid(gpuMetrics, config) { key, checked -> save(config.withMetric(key, checked)) }

        SectionTitle("03", "HUD SIZE + TIMING", "Compact by default. Adjust without resizing by dragging.")
        SettingsCard {
            SliderSetting(
                title = "HUD scale",
                valueText = "${config.scalePercent}%",
                value = config.scalePercent.toFloat(),
                range = 70f..110f,
                steps = 7,
                left = "Smaller",
                right = "Larger",
                onChange = { save(config.copy(scalePercent = it.toInt())) }
            )
            Divider(color = Outline.copy(alpha = 0.7f))
            SliderSetting(
                title = "Refresh interval",
                valueText = String.format(Locale.US, "%.2f s", config.refreshMs / 1000f),
                value = config.refreshMs.toFloat(),
                range = 500f..3000f,
                steps = 9,
                left = "0.5 s",
                right = "3.0 s",
                onChange = { save(config.copy(refreshMs = it.toInt())) }
            )
            Divider(color = Outline.copy(alpha = 0.7f))
            SliderSetting(
                title = "Background opacity",
                valueText = "${config.transparency}%",
                value = config.transparency.toFloat(),
                range = 25f..100f,
                steps = 14,
                left = "Transparent",
                right = "Solid",
                onChange = { save(config.copy(transparency = it.toInt())) }
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { startHud() },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)
            ) {
                Text(if (running) "APPLY HUD" else "START HUD", fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 0.4.sp)
            }
            OutlinedButton(
                onClick = { stopHud() },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Outline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMain)
            ) {
                Text("STOP HUD", fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.4.sp)
            }
        }

        Text(status, color = Mint, fontSize = 11.sp, lineHeight = 16.sp)
        Text(
            "Android 11+ · no network permission · no updater · no analytics",
            color = TextMuted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 8.dp)
        )
    }
}

@Composable
private fun SectionTitle(number: String, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(number, color = Mint, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = TextMain, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.1.sp)
            Text(subtitle, color = TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun MetricGrid(
    items: List<MetricOption>,
    config: HudConfig,
    onChange: (String, Boolean) -> Unit
) {
    items.chunked(2).forEach { rowItems ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            rowItems.forEach { item ->
                Box(Modifier.weight(1f)) {
                    MetricTile(item, config.isEnabled(item.key)) { checked -> onChange(item.key, checked) }
                }
            }
            if (rowItems.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricTile(item: MetricOption, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Surface(
        color = if (checked) item.accent.copy(alpha = 0.09f) else Panel,
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(1.dp, if (checked) item.accent.copy(alpha = 0.55f) else Outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.label, color = if (checked) item.accent else TextMain, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                Text(item.hint, color = TextMuted, fontSize = 9.sp, lineHeight = 12.sp)
            }
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.size(30.dp),
                colors = CheckboxDefaults.colors(
                    checkedColor = item.accent,
                    uncheckedColor = TextMuted,
                    checkmarkColor = Bg
                )
            )
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = Panel,
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, Outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content
        )
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
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Mint,
                activeTrackColor = Mint,
                inactiveTrackColor = Outline
            )
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(left, color = TextMuted, fontSize = 9.sp)
            Text(right, color = TextMuted, fontSize = 9.sp)
        }
    }
}
