package com.tempoverlay.mangohud

import android.content.Context

data class HudConfig(
    val showCpuTemp: Boolean = true,
    val showCore0Temp: Boolean = false,
    val showCore1Temp: Boolean = false,
    val showCore2Temp: Boolean = false,
    val showCore3Temp: Boolean = false,
    val showCore4Temp: Boolean = false,
    val showCore5Temp: Boolean = false,
    val showCore6Temp: Boolean = false,
    val showCore7Temp: Boolean = false,
    val showGpuTemp: Boolean = true,
    val refreshMs: Int = 1500,
    val transparency: Int = 76,
    val scalePercent: Int = 90,
    val textColorIndex: Int = 0,
    val textBrightness: Int = 100,
    val lockPosition: Boolean = false,
    val positionXDp: Int = 6,
    val positionYDp: Int = 72
) {
    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("cpu_temp", showCpuTemp)
            .putBoolean("cpu0_temp", showCore0Temp)
            .putBoolean("cpu1_temp", showCore1Temp)
            .putBoolean("cpu2_temp", showCore2Temp)
            .putBoolean("cpu3_temp", showCore3Temp)
            .putBoolean("cpu4_temp", showCore4Temp)
            .putBoolean("cpu5_temp", showCore5Temp)
            .putBoolean("cpu6_temp", showCore6Temp)
            .putBoolean("cpu7_temp", showCore7Temp)
            .putBoolean("gpu_temp", showGpuTemp)
            .putInt("refresh_ms", refreshMs.coerceIn(500, 3000))
            .putInt("transparency", transparency.coerceIn(20, 100))
            .putInt("scale_percent", scalePercent.coerceIn(70, 110))
            .putInt("text_color_index", textColorIndex.coerceIn(0, HudPalette.textOptions.lastIndex))
            .putInt("text_brightness", textBrightness.coerceIn(35, 100))
            .putBoolean("lock_position", lockPosition)
            .putInt("position_x_dp", positionXDp.coerceAtLeast(0))
            .putInt("position_y_dp", positionYDp.coerceAtLeast(0))
            .apply()
    }

    fun isEnabled(key: String): Boolean = when (key) {
        "cpuTemp" -> showCpuTemp
        "core0Temp" -> showCore0Temp
        "core1Temp" -> showCore1Temp
        "core2Temp" -> showCore2Temp
        "core3Temp" -> showCore3Temp
        "core4Temp" -> showCore4Temp
        "core5Temp" -> showCore5Temp
        "core6Temp" -> showCore6Temp
        "core7Temp" -> showCore7Temp
        "gpuTemp" -> showGpuTemp
        else -> false
    }

    fun withMetric(key: String, enabled: Boolean): HudConfig = when (key) {
        "cpuTemp" -> copy(showCpuTemp = enabled)
        "core0Temp" -> copy(showCore0Temp = enabled)
        "core1Temp" -> copy(showCore1Temp = enabled)
        "core2Temp" -> copy(showCore2Temp = enabled)
        "core3Temp" -> copy(showCore3Temp = enabled)
        "core4Temp" -> copy(showCore4Temp = enabled)
        "core5Temp" -> copy(showCore5Temp = enabled)
        "core6Temp" -> copy(showCore6Temp = enabled)
        "core7Temp" -> copy(showCore7Temp = enabled)
        "gpuTemp" -> copy(showGpuTemp = enabled)
        else -> this
    }

    fun coreTempEnabled(): Boolean =
        showCore0Temp || showCore1Temp || showCore2Temp || showCore3Temp ||
            showCore4Temp || showCore5Temp || showCore6Temp || showCore7Temp

    fun cpuTempEnabled(): Boolean = showCpuTemp || coreTempEnabled()

    fun selectedCount(): Int =
        (if (showCpuTemp) 1 else 0) +
            listOf(showCore0Temp, showCore1Temp, showCore2Temp, showCore3Temp,
                showCore4Temp, showCore5Temp, showCore6Temp, showCore7Temp).count { it } +
            (if (showGpuTemp) 1 else 0)

    companion object {
        private const val PREFS = "temp_hud_settings"
        private const val KEY_RUNNING = "overlay_running"

        fun load(context: Context): HudConfig {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return HudConfig(
                showCpuTemp = p.getBoolean("cpu_temp", true),
                showCore0Temp = p.getBoolean("cpu0_temp", false),
                showCore1Temp = p.getBoolean("cpu1_temp", false),
                showCore2Temp = p.getBoolean("cpu2_temp", false),
                showCore3Temp = p.getBoolean("cpu3_temp", false),
                showCore4Temp = p.getBoolean("cpu4_temp", false),
                showCore5Temp = p.getBoolean("cpu5_temp", false),
                showCore6Temp = p.getBoolean("cpu6_temp", false),
                showCore7Temp = p.getBoolean("cpu7_temp", false),
                showGpuTemp = p.getBoolean("gpu_temp", true),
                refreshMs = p.getInt("refresh_ms", 1500).coerceIn(500, 3000),
                transparency = p.getInt("transparency", 76).coerceIn(20, 100),
                scalePercent = p.getInt("scale_percent", 90).coerceIn(70, 110),
                textColorIndex = p.getInt("text_color_index", 0).coerceIn(0, HudPalette.textOptions.lastIndex),
                textBrightness = p.getInt("text_brightness", 100).coerceIn(35, 100),
                lockPosition = p.getBoolean("lock_position", false),
                positionXDp = p.getInt("position_x_dp", 6).coerceAtLeast(0),
                positionYDp = p.getInt("position_y_dp", 72).coerceAtLeast(0)
            )
        }

        fun isRunning(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_RUNNING, false)

        fun setRunning(context: Context, running: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_RUNNING, running)
                .apply()
        }
    }
}

object HudPalette {
    data class TextOption(val name: String, val argb: Int)
    val textOptions = listOf(
        TextOption("White", 0xFFF0F4F8.toInt()),
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
                val ratio = ((celsius - t0) / (t1 - t0)).coerceIn(0f, 1f)
                val a = android.graphics.Color.alpha(c0)
                val r = (android.graphics.Color.red(c0) + (android.graphics.Color.red(c1) - android.graphics.Color.red(c0)) * ratio).toInt()
                val g = (android.graphics.Color.green(c0) + (android.graphics.Color.green(c1) - android.graphics.Color.green(c0)) * ratio).toInt()
                val b = (android.graphics.Color.blue(c0) + (android.graphics.Color.blue(c1) - android.graphics.Color.blue(c0)) * ratio).toInt()
                return applyBrightness(android.graphics.Color.argb(a, r, g, b), brightness)
            }
        }
        return applyBrightness(stops.last().second, brightness)
    }

    private fun applyBrightness(color: Int, brightness: Int): Int {
        val factor = brightness.coerceIn(35, 100) / 100f
        return android.graphics.Color.argb(
            android.graphics.Color.alpha(color),
            (android.graphics.Color.red(color) * factor).toInt().coerceIn(0, 255),
            (android.graphics.Color.green(color) * factor).toInt().coerceIn(0, 255),
            (android.graphics.Color.blue(color) * factor).toInt().coerceIn(0, 255)
        )
    }
}
