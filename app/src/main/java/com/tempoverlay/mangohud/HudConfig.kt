package com.tempoverlay.mangohud

import android.content.Context
import android.os.Process
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
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
    val textBrightness: Int = 100,
    val cpuAverageVariant: String = "usr",
    val gpuAverageVariant: String = "usr",
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
            .putInt("text_brightness", textBrightness.coerceIn(35, 100))
            .putString("cpu_average_variant", cpuAverageVariant.normalizedVariant())
            .putString("gpu_average_variant", gpuAverageVariant.normalizedVariant())
            .putBoolean("lock_position", lockPosition)
            .putInt("position_x_dp", positionXDp.coerceAtLeast(0))
            .putInt("position_y_dp", positionYDp.coerceAtLeast(0))
            .putBoolean(KEY_RUNNING, isRunning(context))
            .apply()

        writeCrossProcessConfig(context, this)
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
        private const val CONFIG_FILE = "temp_hud_config.json"
        private const val RUNNING_FILE = "temp_hud_overlay.pid"

        fun load(context: Context): HudConfig {
            readCrossProcessConfig(context)?.let { return it }
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val selected = p.getStringSet(
                "selected_thermal_sensors",
                p.getStringSet("selected_gpu_sensors", emptySet())
            )?.toSet() ?: emptySet()
            val entries = parseEntries(p.getString("custom_entries_json", "[]") ?: "[]")
            val migrated = HudConfig(
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
                textBrightness = p.getInt("text_brightness", 100).coerceIn(35, 100),
                cpuAverageVariant = (p.getString("cpu_average_variant", "usr") ?: "usr").normalizedVariant(),
                gpuAverageVariant = (p.getString("gpu_average_variant", "usr") ?: "usr").normalizedVariant(),
                lockPosition = p.getBoolean("lock_position", false),
                positionXDp = p.getInt("position_x_dp", 8).coerceAtLeast(0),
                positionYDp = p.getInt("position_y_dp", 100).coerceAtLeast(0)
            )
            migrated.save(context)
            return migrated
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

        private fun writeCrossProcessConfig(context: Context, config: HudConfig) {
            val entries = JSONArray()
            config.customEntries.forEach { entry ->
                entries.put(JSONObject().put("id", entry.id).put("label", entry.label).put("sensorId", entry.sensorId))
            }
            val selected = JSONArray()
            config.selectedThermalSensors.sorted().forEach { selected.put(it) }
            val json = JSONObject()
                .put("format", 1)
                .put("showCpuTemp", config.showCpuTemp)
                .put("showGpuTemp", config.showGpuTemp)
                .put("showCore0Temp", config.showCore0Temp)
                .put("showCore1Temp", config.showCore1Temp)
                .put("showCore2Temp", config.showCore2Temp)
                .put("showCore3Temp", config.showCore3Temp)
                .put("showCore4Temp", config.showCore4Temp)
                .put("showCore5Temp", config.showCore5Temp)
                .put("showCore6Temp", config.showCore6Temp)
                .put("showCore7Temp", config.showCore7Temp)
                .put("selectedThermalSensors", selected)
                .put("customEntries", entries)
                .put("refreshMs", config.refreshMs.coerceIn(500, 5000))
                .put("backgroundOpacity", config.backgroundOpacity.coerceIn(0, 65))
                .put("scalePercent", config.scalePercent.coerceIn(70, 115))
                .put("textBrightness", config.textBrightness.coerceIn(35, 100))
                .put("lockPosition", config.lockPosition)
                .put("positionXDp", config.positionXDp.coerceAtLeast(0))
                .put("positionYDp", config.positionYDp.coerceAtLeast(0))
            val atomic = AtomicFile(File(context.filesDir, CONFIG_FILE))
            var output: FileOutputStream? = null
            try {
                output = atomic.startWrite()
                output.write(json.toString().toByteArray(Charsets.UTF_8))
                atomic.finishWrite(output)
            } catch (_: Exception) {
                if (output != null) atomic.failWrite(output)
            }
        }

        private fun readCrossProcessConfig(context: Context): HudConfig? {
            return try {
                val atomic = AtomicFile(File(context.filesDir, CONFIG_FILE))
                if (!atomic.baseFile.exists()) {
                    null
                } else {
                    val json = atomic.openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
                    if (json.optInt("format", 0) != 1) {
                        null
                    } else {
                        val selectedJson = json.optJSONArray("selectedThermalSensors") ?: JSONArray()
                        val selected = buildSet {
                            for (i in 0 until selectedJson.length()) {
                                selectedJson.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                            }
                        }
                        val entries = parseEntries((json.optJSONArray("customEntries") ?: JSONArray()).toString())
                        HudConfig(
                            showCpuTemp = json.optBoolean("showCpuTemp", true),
                            showGpuTemp = json.optBoolean("showGpuTemp", true),
                            showCore0Temp = json.optBoolean("showCore0Temp", false),
                            showCore1Temp = json.optBoolean("showCore1Temp", false),
                            showCore2Temp = json.optBoolean("showCore2Temp", false),
                            showCore3Temp = json.optBoolean("showCore3Temp", false),
                            showCore4Temp = json.optBoolean("showCore4Temp", false),
                            showCore5Temp = json.optBoolean("showCore5Temp", false),
                            showCore6Temp = json.optBoolean("showCore6Temp", false),
                            showCore7Temp = json.optBoolean("showCore7Temp", false),
                            selectedThermalSensors = selected,
                            customEntries = entries,
                            refreshMs = json.optInt("refreshMs", 1500).coerceIn(500, 5000),
                            backgroundOpacity = json.optInt("backgroundOpacity", 0).coerceIn(0, 65),
                            scalePercent = json.optInt("scalePercent", 100).coerceIn(70, 115),
                            textBrightness = json.optInt("textBrightness", 100).coerceIn(35, 100),
                            cpuAverageVariant = json.optString("cpuAverageVariant", "usr").normalizedVariant(),
                            gpuAverageVariant = json.optString("gpuAverageVariant", "usr").normalizedVariant(),
                            lockPosition = json.optBoolean("lockPosition", false),
                            positionXDp = json.optInt("positionXDp", 8).coerceAtLeast(0),
                            positionYDp = json.optInt("positionYDp", 100).coerceAtLeast(0)
                        )
                    }
                }
            } catch (_: Exception) {
                null
            }
        }

        fun isRunning(context: Context): Boolean {
            val marker = File(context.filesDir, RUNNING_FILE)
            val pid = try { marker.readText().trim().toIntOrNull() } catch (_: Exception) { null }
            if (pid != null && File("/proc/$pid").exists()) return true
            if (marker.exists()) marker.delete()
            return false
        }

        fun setRunning(context: Context, running: Boolean) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_RUNNING, running).apply()
            val marker = File(context.filesDir, RUNNING_FILE)
            if (running) {
                val temp = File(context.filesDir, "$RUNNING_FILE.tmp")
                try {
                    temp.writeText(Process.myPid().toString())
                    if (marker.exists()) marker.delete()
                    temp.renameTo(marker)
                } catch (_: Exception) { }
            } else {
                marker.delete()
                File(context.filesDir, "$RUNNING_FILE.tmp").delete()
            }
        }
    }
}

object HudPalette {
    private const val WHITE = 0xFFF8FAFC.toInt()

    fun labelColor(brightness: Int): Int = applyBrightness(WHITE, brightness)

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
