package com.tempoverlay.mangohud

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class CustomHudEntry(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val sensorId: String
)

data class HudConfig(
    val showCpuTemp: Boolean = false,
    val showGpuTemp: Boolean = false,
    val selectedThermalSensors: Set<String> = emptySet(),
    val selectedCpuCores: Set<Int> = emptySet(),
    val customEntries: List<CustomHudEntry> = emptyList(),
    val refreshMs: Int = 1500,
    val backgroundOpacity: Int = 0,
    val scalePercent: Int = 100,
    val textBrightness: Int = 100,
    val cpuAverageVariant: String = "usr",
    val gpuAverageVariant: String = "usr",
    val lockPosition: Boolean = false,
    val positionXDp: Int = 8,
    val positionYDp: Int = 100,
    val overlayPaused: Boolean = false
) {
    fun save(context: Context) {
        val stable = copy(
            selectedThermalSensors = selectedThermalSensors.toSet(),
            selectedCpuCores = selectedCpuCores.filter { it in 0..7 }.toSet(),
            customEntries = customEntries.toList(),
            refreshMs = refreshMs.coerceIn(500, 3000),
            backgroundOpacity = backgroundOpacity.coerceIn(0, 65),
            scalePercent = scalePercent.coerceIn(70, 115),
            textBrightness = textBrightness.coerceIn(35, 150),
            cpuAverageVariant = cpuAverageVariant.normalizedVariant(),
            gpuAverageVariant = gpuAverageVariant.normalizedVariant(),
            positionXDp = positionXDp.coerceAtLeast(0),
            positionYDp = positionYDp.coerceAtLeast(0)
        )
        val entries = JSONArray()
        stable.customEntries.forEach { entry ->
            entries.put(JSONObject().put("id", entry.id).put("label", entry.label).put("sensorId", entry.sensorId))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("cpu_temp", stable.showCpuTemp)
            .putBoolean("gpu_temp", stable.showGpuTemp)
            .putStringSet("selected_thermal_sensors", HashSet(stable.selectedThermalSensors))
            .putStringSet("selected_cpu_cores", stable.selectedCpuCores.map { it.toString() }.toSet())
            .putString("custom_entries_json", entries.toString())
            .putInt("refresh_ms", stable.refreshMs)
            .putInt("transparency", stable.backgroundOpacity)
            .putInt("scale_percent", stable.scalePercent)
            .putInt("text_brightness", stable.textBrightness)
            .putString("cpu_average_variant", stable.cpuAverageVariant)
            .putString("gpu_average_variant", stable.gpuAverageVariant)
            .putBoolean("lock_position", stable.lockPosition)
            .putBoolean("overlay_paused", stable.overlayPaused)
            .putInt("position_x_dp", stable.positionXDp)
            .putInt("position_y_dp", stable.positionYDp)
            .apply()
        cachedConfig = stable
    }

    fun hasActiveRows(): Boolean = showCpuTemp || showGpuTemp || selectedCpuCores.isNotEmpty() ||
        selectedThermalSensors.isNotEmpty() || customEntries.isNotEmpty()

    fun withMetric(key: String, enabled: Boolean): HudConfig = when (key) {
        "cpuTemp" -> copy(showCpuTemp = enabled)
        "gpuTemp" -> copy(showGpuTemp = enabled)
        else -> when {
            key.startsWith(CORE_PREFIX) -> {
                val core = key.removePrefix(CORE_PREFIX).toIntOrNull()
                if (core == null || core !in 0..7) this else copy(
                    selectedCpuCores = if (enabled) selectedCpuCores + core else selectedCpuCores - core
                )
            }
            key.startsWith(SENSOR_PREFIX) -> {
                val id = key.removePrefix(SENSOR_PREFIX)
                copy(selectedThermalSensors = if (enabled) selectedThermalSensors + id else selectedThermalSensors - id)
            }
            else -> this
        }
    }

    companion object {
        const val SENSOR_PREFIX = "sensor:"
        const val CORE_PREFIX = "core:"
        private const val PREFS = "temp_hud_settings_v2"
        @Volatile private var cachedConfig: HudConfig? = null
        @Volatile private var overlayRunning = false

        fun load(context: Context): HudConfig {
            cachedConfig?.let { return it }
            return synchronized(this) {
                cachedConfig ?: run {
                    val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val selected = p.getStringSet("selected_thermal_sensors", emptySet())?.toSet() ?: emptySet()
                    val config = HudConfig(
                        showCpuTemp = p.getBoolean("cpu_temp", false),
                        showGpuTemp = p.getBoolean("gpu_temp", false),
                        selectedThermalSensors = selected,
                        selectedCpuCores = p.getStringSet("selected_cpu_cores", emptySet())
                            ?.mapNotNull { it.toIntOrNull()?.takeIf { core -> core in 0..7 } }?.toSet()
                            ?: emptySet(),
                        customEntries = parseEntries(p.getString("custom_entries_json", "[]") ?: "[]"),
                        refreshMs = p.getInt("refresh_ms", 1500).coerceIn(500, 3000),
                        backgroundOpacity = p.getInt("transparency", 0).coerceIn(0, 65),
                        scalePercent = p.getInt("scale_percent", 100).coerceIn(70, 115),
                        textBrightness = p.getInt("text_brightness", 100).coerceIn(35, 150),
                        cpuAverageVariant = (p.getString("cpu_average_variant", "usr") ?: "usr").normalizedVariant(),
                        gpuAverageVariant = (p.getString("gpu_average_variant", "usr") ?: "usr").normalizedVariant(),
                        lockPosition = p.getBoolean("lock_position", false),
                        positionXDp = p.getInt("position_x_dp", 8).coerceAtLeast(0),
                        positionYDp = p.getInt("position_y_dp", 100).coerceAtLeast(0),
                        overlayPaused = p.getBoolean("overlay_paused", false)
                    )
                    cachedConfig = config
                    config
                }
            }
        }

        private fun parseEntries(raw: String): List<CustomHudEntry> = try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val label = item.optString("label", "").trim()
                    val sensorId = item.optString("sensorId", "").trim()
                    if (label.isNotEmpty() && sensorId.isNotEmpty()) {
                        add(CustomHudEntry(item.optString("id", UUID.randomUUID().toString()), label, sensorId))
                    }
                }
            }
        } catch (_: Exception) { emptyList() }

        fun isRunning(): Boolean = overlayRunning
        fun setRunning(running: Boolean) { overlayRunning = running }
    }
}

object HudPalette {
    private const val WHITE = 0xFFF8FAFC.toInt()
    const val COLOR_START_C = 80f

    // Match the DevCheck-style thermal threshold: stay neutral below ~80°C,
    // then progress from yellow to orange and red as temperature rises.
    private val HEAT_STOPS = listOf(
        80f to 0xFFFFD34F.toInt(),
        85f to 0xFFFF8B45.toInt(),
        90f to 0xFFFF414B.toInt()
    )

    fun labelColor(brightness: Int): Int = applyBrightness(WHITE, brightness)

    fun isHeatColored(celsius: Float): Boolean = celsius >= COLOR_START_C

    fun heatColor(celsius: Float, brightness: Int): Int {
        if (celsius < COLOR_START_C) return applyBrightness(WHITE, brightness)
        val stops = HEAT_STOPS
        if (celsius <= stops.first().first) return applyBrightness(stops.first().second, brightness)
        if (celsius >= stops.last().first) return applyBrightness(stops.last().second, brightness)
        for (i in 0 until stops.lastIndex) {
            val (t0, c0) = stops[i]
            val (t1, c1) = stops[i + 1]
            if (celsius in t0..t1) {
                val f = ((celsius - t0) / (t1 - t0)).coerceIn(0f, 1f)
                return applyBrightness(android.graphics.Color.rgb(
                    (android.graphics.Color.red(c0) + (android.graphics.Color.red(c1) - android.graphics.Color.red(c0)) * f).toInt(),
                    (android.graphics.Color.green(c0) + (android.graphics.Color.green(c1) - android.graphics.Color.green(c0)) * f).toInt(),
                    (android.graphics.Color.blue(c0) + (android.graphics.Color.blue(c1) - android.graphics.Color.blue(c0)) * f).toInt()
                ), brightness)
            }
        }
        return applyBrightness(stops.last().second, brightness)
    }

    private fun applyBrightness(color: Int, brightness: Int): Int {
        val percent = brightness.coerceIn(35, 150) / 100f
        // 100% slider brightness maps to 150% color gain; output channels are clamped.
        val gain = 0.5f + percent
        return android.graphics.Color.argb(
            android.graphics.Color.alpha(color),
            (android.graphics.Color.red(color) * gain).toInt().coerceIn(0, 255),
            (android.graphics.Color.green(color) * gain).toInt().coerceIn(0, 255),
            (android.graphics.Color.blue(color) * gain).toInt().coerceIn(0, 255)
        )
    }
}

private fun String.normalizedVariant(): String =
    if (equals("step", ignoreCase = true)) "step" else "usr"
