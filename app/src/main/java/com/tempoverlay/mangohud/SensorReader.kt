package com.tempoverlay.mangohud

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class ThermalReading(
    val id: String,
    val label: String,
    val celsius: Float?,
    val sourceName: String
)

data class TemperatureSnapshot(
    val cpuOverall: ThermalReading? = null,
    val cpuCores: List<ThermalReading?> = List(8) { null },
    val gpuOverall: ThermalReading? = null,
    val allSensors: List<ThermalReading> = emptyList()
) {
    fun toJson(): String {
        fun encode(reading: ThermalReading?): Any = if (reading == null) JSONObject.NULL else JSONObject()
            .put("id", reading.id)
            .put("label", reading.label)
            .put("celsius", reading.celsius?.toDouble() ?: JSONObject.NULL)
            .put("sourceName", reading.sourceName)

        val cores = JSONArray()
        cpuCores.forEach { cores.put(encode(it)) }
        val sensors = JSONArray()
        allSensors.forEach { sensors.put(encode(it)) }
        return JSONObject()
            .put("format", 1)
            .put("cpuOverall", encode(cpuOverall))
            .put("cpuCores", cores)
            .put("gpuOverall", encode(gpuOverall))
            .put("allSensors", sensors)
            .toString()
    }

    fun readingFor(key: String): ThermalReading? = when {
        key == "cpuTemp" -> cpuOverall
        key == "gpuTemp" -> gpuOverall
        key.startsWith("core") && key.endsWith("Temp") ->
            key.removePrefix("core").removeSuffix("Temp").toIntOrNull()?.let { cpuCores.getOrNull(it) }
        key.startsWith(HudConfig.SENSOR_PREFIX) ->
            allSensors.firstOrNull { it.id == key.removePrefix(HudConfig.SENSOR_PREFIX) }
        else -> null
    }

    companion object {
        fun fromJson(raw: String): TemperatureSnapshot? {
            return try {
                val json = JSONObject(raw)
                if (json.optInt("format", 0) != 1) {
                    null
                } else {
                    fun decode(value: Any?): ThermalReading? {
                        if (value == null || value === JSONObject.NULL || value !is JSONObject) return null
                        val temp = if (value.isNull("celsius")) null else value.optDouble("celsius").toFloat()
                        return ThermalReading(
                            id = value.optString("id", ""),
                            label = value.optString("label", ""),
                            celsius = temp,
                            sourceName = value.optString("sourceName", "")
                        )
                    }

                    val coreJson = json.optJSONArray("cpuCores") ?: JSONArray()
                    val cores = (0..7).map { index -> decode(coreJson.opt(index)) }
                    val sensorJson = json.optJSONArray("allSensors") ?: JSONArray()
                    val sensors = buildList {
                        for (index in 0 until sensorJson.length()) {
                            decode(sensorJson.opt(index))?.let { add(it) }
                        }
                    }
                    TemperatureSnapshot(
                        cpuOverall = decode(json.opt("cpuOverall")),
                        cpuCores = cores,
                        gpuOverall = decode(json.opt("gpuOverall")),
                        allSensors = sensors
                    )
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}

class SensorReader {
    private data class ThermalZone(val directory: File, val type: String) {
        val id: String get() = directory.name
        val sourceName: String get() = "$type · $id"
    }

    private data class ZoneValue(val zone: ThermalZone, val celsius: Float) {
        val sourceName: String get() = zone.sourceName
        fun asReading(): ThermalReading = ThermalReading(zone.id, zone.type, celsius, sourceName)
    }

    private val catalog: List<ThermalZone> by lazy {
        File("/sys/class/thermal").listFiles { _, name -> name.startsWith("thermal_zone") }
            ?.mapNotNull { directory ->
                val type = readText(File(directory, "type"))?.trim()?.lowercase(Locale.US)
                if (type.isNullOrEmpty() || !directory.name.removePrefix("thermal_zone").all { it.isDigit() }) {
                    null
                } else ThermalZone(directory, type)
            }?.sortedBy { it.id.removePrefix("thermal_zone").toIntOrNull() ?: Int.MAX_VALUE }
            ?: emptyList()
    }

    fun sample(config: HudConfig, includeAllForSettings: Boolean = false): TemperatureSnapshot {
        val selectedIds = config.selectedThermalSensors + config.customEntries.map { it.sensorId }
        val readCpu = includeAllForSettings || config.cpuTempEnabled()
        val readGpu = includeAllForSettings || config.showGpuTemp
        val toRead = if (includeAllForSettings) catalog else catalog.filter { zone ->
            zone.id in selectedIds ||
                (readCpu && isCpuZone(zone.type)) ||
                (readGpu && isGpuZone(zone.type))
        }
        val values = toRead.mapNotNull(::readTemperature)
        val cpuValues = values.filter { isCpuZone(it.zone.type) }
        val gpuValues = values.filter { isGpuZone(it.zone.type) }
        val selectedCpuValues = selectVariant(cpuValues, config.cpuAverageVariant)
        val selectedGpuValues = selectVariant(gpuValues, config.gpuAverageVariant)

        val cores = coreReadings(selectedCpuValues)
        val mappedCoreSources = cores.mapNotNull { it?.sourceName }.toSet()
        val overallCpuSources = if (mappedCoreSources.isNotEmpty()) {
            selectedCpuValues.filter { it.sourceName in mappedCoreSources }
        } else {
            deduplicatePairs(selectedCpuValues)
        }
        val cpuAverage = average(overallCpuSources)
        val uniqueGpu = deduplicatePairs(selectedGpuValues)
        val gpuAverage = average(uniqueGpu)

        return TemperatureSnapshot(
            cpuOverall = if (readCpu) ThermalReading(
                id = "cpuTemp", label = "CPU", celsius = cpuAverage,
                sourceName = if (overallCpuSources.isEmpty()) "No readable CPU-labelled sensor"
                    else overallCpuSources.joinToString { it.sourceName }
            ) else null,
            cpuCores = cores,
            gpuOverall = if (readGpu) ThermalReading(
                id = "gpuTemp", label = "GPU", celsius = gpuAverage,
                sourceName = if (uniqueGpu.isEmpty()) "No readable GPU-labelled sensor"
                    else uniqueGpu.joinToString { it.sourceName }
            ) else null,
            allSensors = values.map { it.asReading() }
        )
    }

    private fun selectVariant(values: List<ZoneValue>, variant: String): List<ZoneValue> =
        values.filter { value ->
            val type = value.zone.type
            when {
                type.endsWith("-usr") -> variant == "usr"
                type.endsWith("-step") -> variant == "step"
                else -> false
            }
        }

    private fun average(values: List<ZoneValue>): Float? {
        if (values.isEmpty()) return null
        val mean = values.map { it.celsius }.average()
        return mean.takeIf { it.isFinite() }?.toFloat()
    }

    private fun readTemperature(zone: ThermalZone): ZoneValue? {
        val raw = readNumber(File(zone.directory, "temp"))?.toFloat() ?: return null
        val celsius = if (kotlin.math.abs(raw) > 200f) raw / 1000f else raw
        return if (celsius in -20f..150f) ZoneValue(zone, celsius) else null
    }

    private fun coreReadings(values: List<ZoneValue>): List<ThermalReading?> {
        val grouped = values.mapNotNull { value -> coreIndex(value.zone.type)?.let { it to value } }
            .groupBy({ it.first }, { it.second })
        return (0..7).map { core ->
            val picked = grouped[core].orEmpty().maxWithOrNull(
                compareBy<ZoneValue> { sourcePreference(it.zone.type) }.thenBy { it.zone.id }
            ) ?: return@map null
            ThermalReading("core${core}Temp", "CPU $core", picked.celsius, picked.sourceName)
        }
    }

    private fun deduplicatePairs(values: List<ZoneValue>): List<ZoneValue> =
        values.groupBy { value ->
            val type = value.zone.type
            if (type.endsWith("-usr") || type.endsWith("-step")) {
                "paired:" + type.removeSuffix("-usr").removeSuffix("-step")
            } else {
                type + ":" + value.zone.id
            }
        }.values.mapNotNull { group ->
            group.maxWithOrNull(compareBy<ZoneValue> { sourcePreference(it.zone.type) }.thenBy { it.zone.id })
        }.sortedBy { it.zone.id }

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
            Regex("^cpu_core[-_]?([0-7])(?:$|[-_].*)")
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
}


object SensorSourceLabels {
    private val qcomCpu = Regex("^cpu-([0-1])-([0-3])-(usr|step)(?:$|[-_].*)$")
    private val genericCpu = Regex("^cpu(?:[-_]core)?[-_]?([0-7])[-_](usr|step)(?:$|[-_].*)$")
    private val indexedGpu = Regex("^(?:gpuss|gpu)[-_]?([0-9]+)-(usr|step)(?:$|[-_].*)$")
    private val numberedGpu = Regex("^(?:gpuss|gpu)[-_]?([0-9]+)(?:$|[-_].*)$")

    fun cpuCoreOption(reading: ThermalReading): String? {
        val type = reading.label.lowercase(Locale.US)
        val qcom = qcomCpu.find(type)
        if (qcom != null) {
            val cluster = qcom.groupValues[1].toIntOrNull() ?: return null
            val local = qcom.groupValues[2].toIntOrNull() ?: return null
            val core = cluster * 4 + local
            val variant = qcom.groupValues[3]
            return "CPU $core · $variant"
        }
        val generic = genericCpu.find(type) ?: return null
        val core = generic.groupValues[1].toIntOrNull() ?: return null
        return "CPU $core · ${generic.groupValues[2]}"
    }

    fun gpuSourceOption(reading: ThermalReading): String? {
        val type = reading.label.lowercase(Locale.US)
        if (!type.contains("gpu") && !type.contains("gpuss")) return null
        val indexed = indexedGpu.find(type)
        if (indexed != null) return "GPU ${indexed.groupValues[1]} · ${indexed.groupValues[2]}"
        val numbered = numberedGpu.find(type)
        if (numbered != null) return "GPU ${numbered.groupValues[1]}"
        return "GPU sensor · ${reading.id}"
    }

    fun sourceVariant(reading: ThermalReading): String? = when {
        reading.label.lowercase(Locale.US).endsWith("-usr") -> "usr"
        reading.label.lowercase(Locale.US).endsWith("-step") -> "step"
        else -> null
    }

    fun overlayLabel(reading: ThermalReading): String {
        val base = (cpuCoreOption(reading) ?: gpuSourceOption(reading)
            ?: reading.label.uppercase(Locale.US)).substringBefore(" · ")
        return base.replace(Regex("[-_](usr|step)$", RegexOption.IGNORE_CASE), "")
    }
}
