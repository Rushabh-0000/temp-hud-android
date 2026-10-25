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

private val Background = Color(0xFF090D12)
private val SurfaceDark = Color(0xFF121922)
private val SurfaceRaised = Color(0xFF19232E)
private val Mint = Color(0xFF8CFFD0)
private val Blue = Color(0xFF91B9FF)
private val MainText = Color(0xFFEAF1F8)
private val MutedText = Color(0xFF8F9DAD)
private val Border = Color(0xFF283441)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(9, 13, 18)
        window.navigationBarColor = android.graphics.Color.rgb(9, 13, 18)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Mint, secondary = Blue, background = Background,
                    surface = SurfaceDark, onPrimary = Background,
                    onBackground = MainText, onSurface = MainText
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = Background) { TempHudScreen() }
            }
        }
    }
}

@Composable
private fun TempHudScreen() {
    val context = LocalContext.current
    var config by remember { mutableStateOf(HudConfig.load(context)) }
    var running by remember { mutableStateOf(HudConfig.isRunning(context)) }
    var status by remember { mutableStateOf("Ready. No account or network access is needed.") }

    fun save(next: HudConfig) {
        config = next
        next.save(context)
    }
    fun startHud() {
        if (!Settings.canDrawOverlays(context)) {
            status = "Allow Display over other apps, return here, then tap START HUD."
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
            return
        }
        try {
            val intent = Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
            running = true
            status = "HUD starting. Drag its top strip to reposition it."
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .background(Background).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier.size(54.dp).background(SurfaceRaised, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("TH", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, color = Mint, fontSize = 20.sp)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text("TEMP HUD", color = MainText, fontWeight = FontWeight.Black, fontSize = 25.sp, letterSpacing = 1.6.sp)
                Text("REAL-TIME GAMING OVERLAY", color = MutedText, fontSize = 10.sp, letterSpacing = 1.5.sp)
            }
            Surface(
                color = if (running) Color(0xFF15362B) else SurfaceRaised,
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, if (running) Color(0xFF27694E) else Border)
            ) {
                Text(
                    if (running) "LIVE" else "READY",
                    Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                    color = if (running) Mint else MutedText,
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold
                )
            }
        }

        Text(
            "A compact MangoHud-inspired panel. Fixed-width, draggable, adjustable transparency, and designed to stay quiet in the background.",
            color = MutedText, fontSize = 14.sp, lineHeight = 21.sp
        )

        SectionHeading("01", "HUD MODULES", "Select only the hardware blocks you want to poll.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            MetricToggle(Modifier.weight(1f), "CPU", "Load · clock · temp", Mint, config.showCpu) { enabled ->
                if (!enabled && !config.showGpu) status = "Keep at least one module enabled."
                else save(config.copy(showCpu = enabled))
            }
            MetricToggle(Modifier.weight(1f), "GPU", "Load · clock · temp", Blue, config.showGpu) { enabled ->
                if (!enabled && !config.showCpu) status = "Keep at least one module enabled."
                else save(config.copy(showGpu = enabled))
            }
        }

        SectionHeading("02", "REFRESH RATE", "Lower intervals update faster but use more resources.")
        ControlCard {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Sensor polling", color = MainText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("Background refresh interval", color = MutedText, fontSize = 12.sp)
                }
                Text(
                    String.format(Locale.US, "%.2f s", config.refreshMs / 1000f),
                    color = Mint, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 15.sp
                )
            }
            Slider(
                value = config.refreshMs.toFloat(),
                onValueChange = { save(config.copy(refreshMs = it.toInt())) },
                valueRange = 500f..3000f, steps = 9,
                colors = SliderDefaults.colors(thumbColor = Mint, activeTrackColor = Mint, inactiveTrackColor = Border)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("0.5 s · Faster", color = MutedText, fontSize = 11.sp)
                Text("3.0 s · Lower use", color = MutedText, fontSize = 11.sp)
            }
        }

        SectionHeading("03", "HUD TRANSPARENCY", "Only the background fades; text stays readable.")
        ControlCard {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Background opacity", color = MainText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text("${config.transparency}%", color = Mint, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Slider(
                value = config.transparency.toFloat(),
                onValueChange = { save(config.copy(transparency = it.toInt())) },
                valueRange = 30f..100f, steps = 13,
                colors = SliderDefaults.colors(thumbColor = Mint, activeTrackColor = Mint, inactiveTrackColor = Border)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("See-through", color = MutedText, fontSize = 11.sp)
                Text("Solid", color = MutedText, fontSize = 11.sp)
            }
        }

        SectionHeading("04", "OVERLAY CONTROL", "Drag the top strip to move it. HUD size is locked.")
        Button(
            onClick = { startHud() }, modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Background)
        ) {
            Text(if (running) "APPLY / RESTART HUD" else "START FLOATING HUD", fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        }
        Button(
            onClick = { stopHud() }, modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SurfaceRaised, contentColor = MainText)
        ) {
            Text("STOP HUD", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }

        Surface(
            color = SurfaceDark, shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("METRIC HONESTY", color = Mint, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                Text(
                    "Only CPU- and GPU-labelled thermal sensors are used. Android may block temperature, frequency, or GPU-load files; unavailable data shows N/A instead of unrelated sensors or made-up values.",
                    color = MutedText, fontSize = 12.sp, lineHeight = 18.sp
                )
            }
        }

        Text(status, color = Mint, fontSize = 12.sp, lineHeight = 17.sp)
        Text(
            "Android 11+ · Compose UI · No analytics · No internet permission · No in-app updater",
            color = MutedText, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.padding(bottom = 12.dp)
        )
    }
}

@Composable
private fun SectionHeading(number: String, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(number, color = Mint, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = MainText, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.4.sp)
            Text(subtitle, color = MutedText, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun MetricToggle(
    modifier: Modifier, title: String, detail: String, accent: Color, checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = modifier, shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, if (checked) accent.copy(alpha = 0.55f) else Border)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Switch(checked = checked, onCheckedChange = onCheckedChange)
            }
            Text(detail, color = MutedText, fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun ControlCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark), border = BorderStroke(1.dp, Border)
    ) {
        Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            content()
        }
    }
}
