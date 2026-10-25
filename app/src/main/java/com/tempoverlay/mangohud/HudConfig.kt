package com.tempoverlay.mangohud

import android.content.Context

data class HudConfig(
    val showCpu: Boolean = true,
    val showGpu: Boolean = true,
    val refreshMs: Int = 1500,
    val transparency: Int = 82
) {
    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CPU, showCpu)
            .putBoolean(KEY_GPU, showGpu)
            .putInt(KEY_REFRESH, refreshMs.coerceIn(500, 3000))
            .putInt(KEY_ALPHA, transparency.coerceIn(30, 100))
            .apply()
    }

    companion object {
        private const val PREFS = "temp_hud_settings"
        private const val KEY_CPU = "show_cpu"
        private const val KEY_GPU = "show_gpu"
        private const val KEY_REFRESH = "refresh_ms"
        private const val KEY_ALPHA = "background_opacity"
        private const val KEY_RUNNING = "overlay_running"

        fun load(context: Context): HudConfig {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return HudConfig(
                showCpu = prefs.getBoolean(KEY_CPU, true),
                showGpu = prefs.getBoolean(KEY_GPU, true),
                refreshMs = prefs.getInt(KEY_REFRESH, 1500).coerceIn(500, 3000),
                transparency = prefs.getInt(KEY_ALPHA, 82).coerceIn(30, 100)
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
