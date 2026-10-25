package com.tempoverlay.mangohud

import java.io.File
import java.util.Locale

data class TemperatureSnapshot(
    val cpuOverallC: Float? = null,
    val coreTempsC: List<Float?> = List(8) { null },
    val gpuOverallC: Float? = null
) {
    fun valueFor(key: String): Float? = when (key) {
        "cpuTemp" -> cpuOverallC
        "core0Temp" -> coreTempsC.getOrNull(0)
        "core1Temp" -> coreTempsC.getOrNull(1)
        "core2Temp" -> coreTempsC.getOrNull(2)
        "core3Temp" -> coreTempsC.getOrNull(3)
        "core4Temp" -> coreTempsC.getOrNull(4)
        "core5Temp" -> coreTempsC.getOrNull(5)
        "core6Temp" -> coreTempsC.getOrNull(6)
        "core7Temp" -> coreTempsC.getOrNull(7)
        "gpuTemp" -> gpuOverallC
        else -> null
    }
}

class SensorReader {
    private data class ThermalZone(val directory: File, val type: String)

    private val zones: List<ThermalZone> by lazy {
        File("/sys/class/thermal")
            .listFiles { _, name -> name.matches(ZONE_PATTERN) }
            ?.mapNotNull { directory ->
                val type = readText(File(directory, "type"))?.trim()?.lowercase(Locale.US)
                if (type.isNullOrBlank()) null else ThermalZone(directory, type)
            } ?: emptyList()
    }

    fun sample(config: HudConfig): TemperatureSnapshot {
        val cpuReadings = if (config.cpuTempEnabled()) readCpuTemperatures(config) else CpuReadings(null, List(8) { null })
        val gpuTemp = if (config.showGpuTemp) {
            readMaxTemperature { type -> type.contains("gpu") || type.contains("gpuss") }
        } else null
        return TemperatureSnapshot(
            cpuOverallC = if (config.showCpuTemp) cpuReadings.overall else null,
            coreTempsC = cpuReadings.cores,
            gpuOverallC = gpuTemp
        )
    }

    private data class CpuReadings(val overall: Float?, val cores: List<Float?>)

    private fun readCpuTemperatures(config: HudConfig): CpuReadings {
        val cores = MutableList<Float?>(8) { null }
        var overall: Float? = null
        zones.forEach { zone ->
            if (!(zone.type.contains("cpu") || zone.type.contains("cpuss"))) return@forEach
            val raw = readNumber(File(zone.directory, "temp"))?.toFloat() ?: return@forEach
            val temp = normalize(raw) ?: return@forEach
            if (overall == null || temp > overall!!) overall = temp
            if (!config.coreTempEnabled()) return@forEach
            val index = coreIndex(zone.type) ?: return@forEach
            val previous = cores[index]
            if (previous == null || temp > previous) cores[index] = temp
        }
        return CpuReadings(overall, cores)
    }

    private fun coreIndex(type: String): Int? {
        // Qualcomm names cpu-<cluster>-<local-core>-usr/step. Snapdragon 778G
        // has 4 little cores in cluster 0 and 4 big cores in cluster 1.
        val qualcomm = Regex("^cpu-([0-1])-([0-3])-(?:usr|step)(?:[-_].*)?$").find(type)
        if (qualcomm != null) {
            val cluster = qualcomm.groupValues[1].toIntOrNull() ?: return null
            val local = qualcomm.groupValues[2].toIntOrNull() ?: return null
            return (cluster * 4 + local).takeIf { it in 0..7 }
        }
        val patterns = listOf(
            Regex("^cpu([0-7])(?:$|[-_].*)"),
            Regex("^cpu[-_]core[-_]?([0-7])(?:$|[-_].*)"),
            Regex("^cpu_core[-_]?([0-7])(?:$|[-_].*)"),
            Regex("^cpu[-_ ]([0-7])[-_ ]core(?:$|[-_].*)")
        )
        patterns.forEach { pattern ->
            val match = pattern.find(type) ?: return@forEach
            return match.groupValues.getOrNull(1)?.toIntOrNull()
        }
        return null
    }

    private fun readMaxTemperature(matches: (String) -> Boolean): Float? =
        zones.asSequence().filter { matches(it.type) }
            .mapNotNull { zone ->
                val raw = readNumber(File(zone.directory, "temp"))?.toFloat() ?: return@mapNotNull null
                normalize(raw)
            }.maxOrNull()

    private fun normalize(raw: Float): Float? {
        val celsius = if (kotlin.math.abs(raw) > 200f) raw / 1000f else raw
        return celsius.takeIf { it in -20f..150f }
    }

    private fun readNumber(file: File): Long? = readText(file)?.trim()?.toLongOrNull()
    private fun readText(file: File): String? =
        try { file.readText().lineSequence().firstOrNull() } catch (_: Exception) { null }

    companion object {
        private val ZONE_PATTERN = Regex("thermal_zone[0-9]+")
    }
}
