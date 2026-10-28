package com.tempoverlay.mangohud

import android.content.Context
import android.content.res.Configuration
import org.json.JSONObject

data class HudConfig(
    val showCpuTemp: Boolean = false,
    val showGpuTemp: Boolean = false,
    val selectedThermalSensors: Set<String> = emptySet(),
    val selectedCpuCores: Set<Int> = emptySet(),
    val refreshMs: Int = 1500,
    val backgroundOpacity: Int = 0,
    val scalePercent: Int = 100,
    val textBrightness: Int = 100,
    val cpuAverageVariant: String = "usr",
    val gpuAverageVariant: String = "usr",
    val lockPosition: Boolean = false,
    // positionXDp/positionYDp refer to the current device orientation.
    // Persist portrait and landscape anchors independently so rotating never overwrites the other.
    val positionXDp: Int = 8,
    val positionYDp: Int = 100,
    val portraitPositionXDp: Int = 8,
    val portraitPositionYDp: Int = 100,
    val landscapePositionXDp: Int = 8,
    val landscapePositionYDp: Int = 100,
    val overlayPaused: Boolean = false,
    val sensorOverlayNames: Map<String, String> = emptyMap()
) {
    fun save(context: Context) {
        val normalized = copy(
            selectedThermalSensors = selectedThermalSensors.toSet(),
            selectedCpuCores = selectedCpuCores.filter { it in 0..7 }.toSet(),
            sensorOverlayNames = sensorOverlayNames.filterValues { it.isNotBlank() },
            refreshMs = refreshMs.coerceIn(500, 3000),
            backgroundOpacity = backgroundOpacity.coerceIn(0, 65),
            scalePercent = scalePercent.coerceIn(70, 115),
            textBrightness = textBrightness.coerceIn(35, 100),
            cpuAverageVariant = cpuAverageVariant.normalizedVariant(),
            gpuAverageVariant = gpuAverageVariant.normalizedVariant(),
            positionXDp = positionXDp.coerceAtLeast(0),
            positionYDp = positionYDp.coerceAtLeast(0),
            portraitPositionXDp = portraitPositionXDp.coerceAtLeast(0),
            portraitPositionYDp = portraitPositionYDp.coerceAtLeast(0),
            landscapePositionXDp = landscapePositionXDp.coerceAtLeast(0),
            landscapePositionYDp = landscapePositionYDp.coerceAtLeast(0)
        )
        // Save the active coordinates only to the orientation that is currently shown.
        val stable = if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            normalized.copy(
                landscapePositionXDp = normalized.positionXDp,
                landscapePositionYDp = normalized.positionYDp
            )
        } else {
            normalized.copy(
                portraitPositionXDp = normalized.positionXDp,
                portraitPositionYDp = normalized.positionYDp
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("cpu_temp", stable.showCpuTemp)
            .putBoolean("gpu_temp", stable.showGpuTemp)
            .putStringSet("selected_thermal_sensors", HashSet(stable.selectedThermalSensors))
            .putStringSet("selected_cpu_cores", stable.selectedCpuCores.map { it.toString() }.toSet())
            .putString("sensor_overlay_names_json", JSONObject().apply {
                stable.sensorOverlayNames.forEach { (sensorId, overlayName) -> put(sensorId, overlayName) }
            }.toString())
            .remove("custom_entries_json")
            .putInt("refresh_ms", stable.refreshMs)
            .putInt("transparency", stable.backgroundOpacity)
            .putInt("scale_percent", stable.scalePercent)
            .putInt("text_brightness", stable.textBrightness)
            .putString("cpu_average_variant", stable.cpuAverageVariant)
            .putString("gpu_average_variant", stable.gpuAverageVariant)
            .putBoolean("lock_position", stable.lockPosition)
            .putBoolean("overlay_paused", stable.overlayPaused)
            // Keep legacy shared coordinates too, for older versions and migration.
            .putInt("position_x_dp", stable.positionXDp)
            .putInt("position_y_dp", stable.positionYDp)
            .putInt("position_portrait_x_dp", stable.portraitPositionXDp)
            .putInt("position_portrait_y_dp", stable.portraitPositionYDp)
            .putInt("position_landscape_x_dp", stable.landscapePositionXDp)
            .putInt("position_landscape_y_dp", stable.landscapePositionYDp)
            .apply()
        cachedConfig = stable
    }

    fun hasActiveRows(): Boolean = showCpuTemp || showGpuTemp || selectedCpuCores.isNotEmpty() ||
        selectedThermalSensors.isNotEmpty()

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
            cachedConfig?.let { return it.forCurrentOrientation(context) }
            return synchronized(this) {
                cachedConfig?.forCurrentOrientation(context) ?: run {
                    val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    // Older versions stored one shared position. Use it as the initial value
                    // for both orientations, then keep the two positions independent.
                    val legacyX = p.getInt("position_x_dp", 8).coerceAtLeast(0)
                    val legacyY = p.getInt("position_y_dp", 100).coerceAtLeast(0)
                    val portraitX = p.getInt("position_portrait_x_dp", legacyX).coerceAtLeast(0)
                    val portraitY = p.getInt("position_portrait_y_dp", legacyY).coerceAtLeast(0)
                    val landscapeX = p.getInt("position_landscape_x_dp", legacyX).coerceAtLeast(0)
                    val landscapeY = p.getInt("position_landscape_y_dp", legacyY).coerceAtLeast(0)
                    val isLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    val selected = p.getStringSet("selected_thermal_sensors", emptySet())?.toSet() ?: emptySet()
                    val config = HudConfig(
                        showCpuTemp = p.getBoolean("cpu_temp", false),
                        showGpuTemp = p.getBoolean("gpu_temp", false),
                        selectedThermalSensors = selected,
                        selectedCpuCores = p.getStringSet("selected_cpu_cores", emptySet())
                            ?.mapNotNull { it.toIntOrNull()?.takeIf { core -> core in 0..7 } }?.toSet()
                            ?: emptySet(),
                        sensorOverlayNames = parseSensorNames(p.getString("sensor_overlay_names_json", "{}") ?: "{}"),
                        refreshMs = p.getInt("refresh_ms", 1500).coerceIn(500, 3000),
                        backgroundOpacity = p.getInt("transparency", 0).coerceIn(0, 65),
                        scalePercent = p.getInt("scale_percent", 100).coerceIn(70, 115),
                        textBrightness = p.getInt("text_brightness", 100).coerceIn(35, 100),
                        cpuAverageVariant = (p.getString("cpu_average_variant", "usr") ?: "usr").normalizedVariant(),
                        gpuAverageVariant = (p.getString("gpu_average_variant", "usr") ?: "usr").normalizedVariant(),
                        lockPosition = p.getBoolean("lock_position", false),
                        positionXDp = if (isLandscape) landscapeX else portraitX,
                        positionYDp = if (isLandscape) landscapeY else portraitY,
                        portraitPositionXDp = portraitX,
                        portraitPositionYDp = portraitY,
                        landscapePositionXDp = landscapeX,
                        landscapePositionYDp = landscapeY,
                        overlayPaused = p.getBoolean("overlay_paused", false)
                    )
                    cachedConfig = config
                    config
                }
            }
        }

        private fun HudConfig.forCurrentOrientation(context: Context): HudConfig =
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                copy(positionXDp = landscapePositionXDp, positionYDp = landscapePositionYDp)
            } else {
                copy(positionXDp = portraitPositionXDp, positionYDp = portraitPositionYDp)
            }

        private fun parseSensorNames(raw: String): Map<String, String> = try {
            val json = JSONObject(raw)
            val names = linkedMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val sensorId = keys.next()
                val name = json.optString(sensorId, "").trim()
                if (name.isNotEmpty()) names[sensorId] = name
            }
            names
        } catch (_: Exception) {
            emptyMap()
        }

        fun isRunning(): Boolean = overlayRunning
        fun setRunning(running: Boolean) { overlayRunning = running }
    }
}

object HudPalette {
    private const val WHITE = 0xFFF8FAFC.toInt()
    const val COLOR_START_C = 80f

    // Cooler temperatures are green to lime/yellow-green. At 80°C it changes
    // to a hot yellow, then orange and red as temperature keeps rising.
    private val HEAT_STOPS = listOf(
        25f to 0xFF44E879.toInt(),
        40f to 0xFF5BE36D.toInt(),
        55f to 0xFF82E25A.toInt(),
        70f to 0xFFACDE4D.toInt(),
        79f to 0xFFC4D94A.toInt(),
        80f to 0xFFFFD34F.toInt(),
        85f to 0xFFFF8B45.toInt(),
        90f to 0xFFFF414B.toInt()
    )

    fun labelColor(brightness: Int): Int = applyBrightness(WHITE, brightness)

    fun isHeatColored(celsius: Float): Boolean = celsius >= COLOR_START_C

    fun heatColor(celsius: Float, brightness: Int): Int {
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
        val percent = brightness.coerceIn(35, 100) / 100f
        val gain = percent * (1.0f + 0.22f * percent)
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
