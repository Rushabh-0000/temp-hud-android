package com.tempoverlay.mangohud

import java.io.File
import java.util.Locale

data class ThermalReading(
    val id: String,
    val label: String,
    val celsius: Float?,
    val sourceNames: List<String>
)

data class TemperatureSnapshot(
    val cpuOverall: ThermalReading? = null,
    val cpuCores: List<ThermalReading?> = List(8) { null },
    val gpuOverall: ThermalReading? = null,
    val gpuSensors: List<ThermalReading> = emptyList()
) {
    fun readingFor(key: String): ThermalReading? = when {
        key == "cpuTemp" -> cpuOverall
        key == "gpuTemp" -> gpuOverall
        key.startsWith("core") && key.endsWith("Temp") ->
            key.removePrefix("core").removeSuffix("Temp").toIntOrNull()?.let { cpuCores.getOrNull(it) }
        key.startsWith(HudConfig.GPU_SENSOR_PREFIX) ->
            gpuSensors.firstOrNull { it.id == key.removePrefix(HudConfig.GPU_SENSOR_PREFIX) }
        else -> null
    }
}

class SensorReader {
    private data class ThermalZone(val directory: File, val type: String) {
        val zoneName: String get() = directory.name
        val sourceName: String get() = "$type · $zoneName"
    }
    private data class ZoneValue(val zone: ThermalZone, val celsius: Float) {
        val sourceName: String get() = zone.sourceName
    }

    private val zoneCatalog: List<ThermalZone> by lazy {
        File("/sys/class/thermal")
            .listFiles { _, name -> name.matches(ZONE_PATTERN) }
            ?.mapNotNull { dir ->
                val type = readText(File(dir, "type"))?.trim()?.lowercase(Locale.US)
                if (type.isNullOrBlank()) null else ThermalZone(dir, type)
            } ?: emptyList()
    }

    fun sample(config: HudConfig, includeAllForSettings: Boolean = false): TemperatureSnapshot {
        val cpuZones = zoneCatalog.filter { isCpuZone(it.type) }
        val gpuZones = zoneCatalog.filter { isGpuZone(it.type) }
        val readCpu = includeAllForSettings || config.cpuTempEnabled()
        val readGpu = includeAllForSettings || config.showGpuTemp || config.selectedGpuSensors.isNotEmpty()
        val cpuValues = if (readCpu) cpuZones.mapNotNull(::readTemperature) else emptyList()
        val gpuValues = if (readGpu) gpuZones.mapNotNull(::readTemperature) else emptyList()

        val cores = coreReadings(cpuValues)
        val mappedCores = cores.mapNotNull { it }
        val cpuSourceValues = if (mappedCores.isNotEmpty()) mappedCores.mapNotNull { reading ->
            val source = cpuValues.firstOrNull { it.sourceName == reading.sourceNames.firstOrNull() }
            source
        } else dedupeZones(cpuValues)
        val cpuAverage = cpuSourceValues.map { it.celsius }.average().takeIf {
            cpuSourceValues.isNotEmpty() && it.isFinite()
        }?.toFloat()

        val uniqueGpu = dedupeZones(gpuValues)
        val gpuAverage = uniqueGpu.map { it.celsius }.average().takeIf {
            uniqueGpu.isNotEmpty() && it.isFinite()
        }?.toFloat()

        val gpuReadings = uniqueGpu.sortedBy { it.zone.zoneName }.mapIndexed { index, item ->
            ThermalReading(
                id = item.zone.zoneName,
                label = "GPU SENSOR ${index + 1}",
                celsius = item.celsius,
                sourceNames = listOf(item.sourceName)
            )
        }

        return TemperatureSnapshot(
            cpuOverall = if (readCpu) ThermalReading("cpuTemp", "CPU", cpuAverage,
                cpuSourceValues.map { it.sourceName }) else null,
            cpuCores = cores,
            gpuOverall = if (readGpu) ThermalReading("gpuTemp", "GPU", gpuAverage,
                uniqueGpu.map { it.sourceName }) else null,
            gpuSensors = gpuReadings
        )
    }

    private fun readTemperature(zone: ThermalZone): ZoneValue? {
        val raw = readNumber(File(zone.directory, "temp"))?.toFloat() ?: return null
        val celsius = if (kotlin.math.abs(raw) > 200f) raw / 1000f else raw
        return if (celsius in -20f..150f) ZoneValue(zone, celsius) else null
    }

    private fun coreReadings(values: List<ZoneValue>): List<ThermalReading?> {
        val grouped = values.mapNotNull { value ->
            coreIndex(value.zone.type)?.let { it to value }
        }.groupBy({ it.first }, { it.second })
        return (0..7).map { core ->
            val chosen = grouped[core].orEmpty().maxWithOrNull(
                compareBy<ZoneValue> { sourcePreference(it.zone.type) }.thenBy { it.zone.zoneName }
            ) ?: return@map null
            ThermalReading(
                id = "core${core}Temp", label = "CPU $core", celsius = chosen.celsius,
                sourceNames = listOf(chosen.sourceName)
            )
        }
    }

    private fun dedupeZones(values: List<ZoneValue>): List<ZoneValue> =
        values.groupBy { value ->
            if (value.zone.type.endsWith("-usr") || value.zone.type.endsWith("-step")) {
                "paired:" + value.zone.type.replace(Regex("-(usr|step)$"), "")
            } else {
                value.zone.type + ":" + value.zone.zoneName
            }
        }.values.mapNotNull { group ->
            group.maxWithOrNull(compareBy<ZoneValue> { sourcePreference(it.zone.type) }.thenBy { it.zone.zoneName })
        }

    private fun sourcePreference(type: String): Int = when {
        type.endsWith("-usr") -> 3
        type.endsWith("-step") -> 2
        else -> 1
    }

    private fun isCpuZone(type: String): Boolean = type.contains("cpu") || type.contains("cpuss")
    private fun isGpuZone(type: String): Boolean = type.contains("gpu") || type.contains("gpuss")

    private fun coreIndex(type: String): Int? {
        val qcom = Regex("^cpu-([0-1])-([0-3])-(?:usr|step)(?:[-_].*)?$").find(type)
        if (qcom != null) {
            val cluster = qcom.groupValues[1].toIntOrNull() ?: return null
            val local = qcom.groupValues[2].toIntOrNull() ?: return null
            return (cluster * 4 + local).takeIf { it in 0..7 }
        }
        val patterns = listOf(
            Regex("^cpu([0-7])(?:$|[-_].*)"),
            Regex("^cpu[-_]core[-_]?([0-7])(?:$|[-_].*)"),
            Regex("^cpu_core[-_]?([0-7])(?:$|[-_].*)"),
            Regex("^cpu[-_ ]([0-7])[-_ ]core(?:$|[-_].*)")
        )
        for (pattern in patterns) {
            val match = pattern.find(type) ?: continue
            return match.groupValues.getOrNull(1)?.toIntOrNull()
        }
        return null
    }

    private fun readNumber(file: File): Long? = readText(file)?.trim()?.toLongOrNull()
    private fun readText(file: File): String? =
        try { file.readText().lineSequence().firstOrNull() } catch (_: Exception) { null }

    companion object {
        private val ZONE_PATTERN = Regex("thermal_zone[0-9]+")
    }
}
