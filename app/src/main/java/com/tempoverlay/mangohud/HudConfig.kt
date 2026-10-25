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
    val showCpuTemp: Boolean = true,
    val showGpuTemp: Boolean = true,
    val showCore0Temp: Boolean = false,
    val showCore1Temp: Boolean = false,
    val showCore2Temp: Boolean = false,
    val showCore3Temp: Boolean = false,
    val showCore4Temp: Boolean = false,
    val showCore5Temp: Boolean = false,
    val showCore6Temp: Boolean = false,
    val showCore7Temp: Boolean = false,
    val selectedThermalSensors: Set<String> = emptySet(),
    val customEntries: List<CustomHudEntry> = emptyList(),
    val refreshMs: Int = 1500,
    val backgroundOpacity: Int = 0,
    val scalePercent: Int = 100,
    val textColorIndex: Int = 0,
    val textBrightness: Int = 100,
    val lockPosition: Boolean = false,
    val positionXDp: Int = 8,
    val positionYDp: Int = 100
) {
    fun save(context: Context) {
        val entriesJson = JSONArray()
        customEntries.forEach { entry ->
            entriesJson.put(JSONObject()
                .put("id", entry.id)
                .put("label", entry.label)
                .put("sensorId", entry.sensorId))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("cpu_temp", showCpuTemp)
            .putBoolean("gpu_temp", showGpuTemp)
            .putBoolean("cpu0_temp", showCore0Temp)
            .putBoolean("cpu1_temp", showCore1Temp)
            .putBoolean("cpu2_temp", showCore2Temp)
            .putBoolean("cpu3_temp", showCore3Temp)
            .putBoolean("cpu4_temp", showCore4Temp)
            .putBoolean("cpu5_temp", showCore5Temp)
            .putBoolean("cpu6_temp", showCore6Temp)
            .putBoolean("cpu7_temp", showCore7Temp)
            .putStringSet("selected_thermal_sensors", HashSet(selectedThermalSensors))
            .putString("custom_entries_json", entriesJson.toString())
            .putInt("refresh_ms", refreshMs.coerceIn(500, 5000))
            .putInt("transparency", backgroundOpacity.coerceIn(0, 65))
            .putInt("scale_percent", scalePercent.coerceIn(70, 115))
            .putInt("text_color_index", textColorIndex.coerceIn(0, HudPalette.textOptions.lastIndex))
            .putInt("text_brightness", textBrightness.coerceIn(35, 100))
            .putBoolean("lock_position", lockPosition)
            .putInt("position_x_dp", positionXDp.coerceAtLeast(0))
            .putInt("position_y_dp", positionYDp.coerceAtLeast(0))
            .apply()
    }

    fun isEnabled(key: String): Boolean = when (key) {
        "cpuTemp" -> showCpuTemp
        "gpuTemp" -> showGpuTemp
        "core0Temp" -> showCore0Temp
        "core1Temp" -> showCore1Temp
        "core2Temp" -> showCore2Temp
        "core3Temp" -> showCore3Temp
        "core4Temp" -> showCore4Temp
        "core5Temp" -> showCore5Temp
        "core6Temp" -> showCore6Temp
        "core7Temp" -> showCore7Temp
        else -> key.startsWith(SENSOR_PREFIX) && selectedThermalSensors.contains(key.removePrefix(SENSOR_PREFIX))
    }

    fun withMetric(key: String, enabled: Boolean): HudConfig = when (key) {
        "cpuTemp" -> copy(showCpuTemp = enabled)
        "gpuTemp" -> copy(showGpuTemp = enabled)
        "core0Temp" -> copy(showCore0Temp = enabled)
        "core1Temp" -> copy(showCore1Temp = enabled)
        "core2Temp" -> copy(showCore2Temp = enabled)
        "core3Temp" -> copy(showCore3Temp = enabled)
        "core4Temp" -> copy(showCore4Temp = enabled)
        "core5Temp" -> copy(showCore5Temp = enabled)
        "core6Temp" -> copy(showCore6Temp = enabled)
        "core7Temp" -> copy(showCore7Temp = enabled)
        else -> if (key.startsWith(SENSOR_PREFIX)) {
            val id = key.removePrefix(SENSOR_PREFIX)
            copy(selectedThermalSensors = if (enabled) selectedThermalSensors + id else selectedThermalSensors - id)
        } else this
    }

    fun coreTempEnabled(): Boolean =
        showCore0Temp || showCore1Temp || showCore2Temp || showCore3Temp ||
            showCore4Temp || showCore5Temp || showCore6Temp || showCore7Temp

    fun cpuTempEnabled(): Boolean = showCpuTemp || coreTempEnabled()

    fun selectedCount(): Int =
        (if (showCpuTemp) 1 else 0) +
            (if (showGpuTemp) 1 else 0) +
            listOf(showCore0Temp, showCore1Temp, showCore2Temp, showCore3Temp,
                showCore4Temp, showCore5Temp, showCore6Temp, showCore7Temp).count { it } +
            selectedThermalSensors.size + customEntries.size

    companion object {
        const val SENSOR_PREFIX = "sensor:"
        private const val PREFS = "temp_hud_settings"
        private const val KEY_RUNNING = "overlay_running"

        fun load(context: Context): HudConfig {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val selected = p.getStringSet(
                "selected_thermal_sensors",
                p.getStringSet("selected_gpu_sensors", emptySet())
            )?.toSet() ?: emptySet()
            val entries = try {
                val array = JSONArray(p.getString("custom_entries_json", "[]"))
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

            return HudConfig(
                showCpuTemp = p.getBoolean("cpu_temp", true),
                showGpuTemp = p.getBoolean("gpu_temp", true),
                showCore0Temp = p.getBoolean("cpu0_temp", false),
                showCore1Temp = p.getBoolean("cpu1_temp", false),
                showCore2Temp = p.getBoolean("cpu2_temp", false),
                showCore3Temp = p.getBoolean("cpu3_temp", false),
                showCore4Temp = p.getBoolean("cpu4_temp", false),
                showCore5Temp = p.getBoolean("cpu5_temp", false),
                showCore6Temp = p.getBoolean("cpu6_temp", false),
                showCore7Temp = p.getBoolean("cpu7_temp", false),
                selectedThermalSensors = selected,
                customEntries = entries,
                refreshMs = p.getInt("refresh_ms", 1500).coerceIn(500, 5000),
                backgroundOpacity = p.getInt("transparency", 0).coerceIn(0, 65),
                scalePercent = p.getInt("scale_percent", 100).coerceIn(70, 115),
                textColorIndex = p.getInt("text_color_index", 0).coerceIn(0, HudPalette.textOptions.lastIndex),
                textBrightness = p.getInt("text_brightness", 100).coerceIn(35, 100),
                lockPosition = p.getBoolean("lock_position", false),
                positionXDp = p.getInt("position_x_dp", 8).coerceAtLeast(0),
                positionYDp = p.getInt("position_y_dp", 100).coerceAtLeast(0)
            )
        }

        fun isRunning(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_RUNNING, false)

        fun setRunning(context: Context, running: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_RUNNING, running).apply()
        }
    }
}

object HudPalette {
    data class TextOption(val name: String, val argb: Int)
    val textOptions = listOf(
        TextOption("White", 0xFFF8FAFC.toInt()),
        TextOption("Mint", 0xFF8CFFD0.toInt()),
        TextOption("Cyan", 0xFF80DFFF.toInt()),
        TextOption("Amber", 0xFFFFD08A.toInt()),
        TextOption("Violet", 0xFFC8B5FF.toInt())
    )

    fun labelColor(index: Int, brightness: Int): Int =
        applyBrightness(textOptions.getOrElse(index) { textOptions[0] }.argb, brightness)

    fun heatColor(celsius: Float, brightness: Int): Int {
        val stops = listOf(
            35f to 0xFF44E879.toInt(),
            45f to 0xFF8FE85A.toInt(),
            55f to 0xFFD7E84D.toInt(),
            65f to 0xFFFFD34F.toInt(),
            75f to 0xFFFF8B45.toInt(),
            85f to 0xFFFF414B.toInt()
        )
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
        // Boost the top of the slider so 100% visibly brightens coloured temperature text.
        // Gain rises smoothly from the selected percentage to 1.22x at the maximum.
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
