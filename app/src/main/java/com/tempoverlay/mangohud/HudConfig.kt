package com.tempoverlay.mangohud

import android.content.Context

data class HudConfig(
    val showCpuLoad: Boolean = true,
    val showCpuClock: Boolean = true,
    val showCpuTemp: Boolean = true,
    val showCore0Temp: Boolean = false,
    val showCore1Temp: Boolean = false,
    val showCore2Temp: Boolean = false,
    val showCore3Temp: Boolean = false,
    val showCore4Temp: Boolean = false,
    val showCore5Temp: Boolean = false,
    val showCore6Temp: Boolean = false,
    val showCore7Temp: Boolean = false,
    val showGpuLoad: Boolean = true,
    val showGpuClock: Boolean = true,
    val showGpuTemp: Boolean = true,
    val refreshMs: Int = 1500,
    val transparency: Int = 78,
    val scalePercent: Int = 90
) {
    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("cpu_load", showCpuLoad)
            .putBoolean("cpu_clock", showCpuClock)
            .putBoolean("cpu_temp", showCpuTemp)
            .putBoolean("cpu0_temp", showCore0Temp)
            .putBoolean("cpu1_temp", showCore1Temp)
            .putBoolean("cpu2_temp", showCore2Temp)
            .putBoolean("cpu3_temp", showCore3Temp)
            .putBoolean("cpu4_temp", showCore4Temp)
            .putBoolean("cpu5_temp", showCore5Temp)
            .putBoolean("cpu6_temp", showCore6Temp)
            .putBoolean("cpu7_temp", showCore7Temp)
            .putBoolean("gpu_load", showGpuLoad)
            .putBoolean("gpu_clock", showGpuClock)
            .putBoolean("gpu_temp", showGpuTemp)
            .putInt("refresh_ms", refreshMs.coerceIn(500, 3000))
            .putInt("transparency", transparency.coerceIn(25, 100))
            .putInt("scale_percent", scalePercent.coerceIn(70, 110))
            .apply()
    }

    fun isEnabled(key: String): Boolean = when (key) {
        "cpuLoad" -> showCpuLoad
        "cpuClock" -> showCpuClock
        "cpuTemp" -> showCpuTemp
        "core0Temp" -> showCore0Temp
        "core1Temp" -> showCore1Temp
        "core2Temp" -> showCore2Temp
        "core3Temp" -> showCore3Temp
        "core4Temp" -> showCore4Temp
        "core5Temp" -> showCore5Temp
        "core6Temp" -> showCore6Temp
        "core7Temp" -> showCore7Temp
        "gpuLoad" -> showGpuLoad
        "gpuClock" -> showGpuClock
        "gpuTemp" -> showGpuTemp
        else -> false
    }

    fun withMetric(key: String, enabled: Boolean): HudConfig = when (key) {
        "cpuLoad" -> copy(showCpuLoad = enabled)
        "cpuClock" -> copy(showCpuClock = enabled)
        "cpuTemp" -> copy(showCpuTemp = enabled)
        "core0Temp" -> copy(showCore0Temp = enabled)
        "core1Temp" -> copy(showCore1Temp = enabled)
        "core2Temp" -> copy(showCore2Temp = enabled)
        "core3Temp" -> copy(showCore3Temp = enabled)
        "core4Temp" -> copy(showCore4Temp = enabled)
        "core5Temp" -> copy(showCore5Temp = enabled)
        "core6Temp" -> copy(showCore6Temp = enabled)
        "core7Temp" -> copy(showCore7Temp = enabled)
        "gpuLoad" -> copy(showGpuLoad = enabled)
        "gpuClock" -> copy(showGpuClock = enabled)
        "gpuTemp" -> copy(showGpuTemp = enabled)
        else -> this
    }

    fun coreTempEnabled(): Boolean =
        showCore0Temp || showCore1Temp || showCore2Temp || showCore3Temp ||
            showCore4Temp || showCore5Temp || showCore6Temp || showCore7Temp

    companion object {
        private const val PREFS = "temp_hud_settings"
        private const val KEY_RUNNING = "overlay_running"

        fun load(context: Context): HudConfig {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return HudConfig(
                showCpuLoad = prefs.getBoolean("cpu_load", true),
                showCpuClock = prefs.getBoolean("cpu_clock", true),
                showCpuTemp = prefs.getBoolean("cpu_temp", true),
                showCore0Temp = prefs.getBoolean("cpu0_temp", false),
                showCore1Temp = prefs.getBoolean("cpu1_temp", false),
                showCore2Temp = prefs.getBoolean("cpu2_temp", false),
                showCore3Temp = prefs.getBoolean("cpu3_temp", false),
                showCore4Temp = prefs.getBoolean("cpu4_temp", false),
                showCore5Temp = prefs.getBoolean("cpu5_temp", false),
                showCore6Temp = prefs.getBoolean("cpu6_temp", false),
                showCore7Temp = prefs.getBoolean("cpu7_temp", false),
                showGpuLoad = prefs.getBoolean("gpu_load", true),
                showGpuClock = prefs.getBoolean("gpu_clock", true),
                showGpuTemp = prefs.getBoolean("gpu_temp", true),
                refreshMs = prefs.getInt("refresh_ms", 1500).coerceIn(500, 3000),
                transparency = prefs.getInt("transparency", 78).coerceIn(25, 100),
                scalePercent = prefs.getInt("scale_percent", 90).coerceIn(70, 110)
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
