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
    val cpuEcoCluster: ThermalReading? = null,
    val cpuPerfCluster: ThermalReading? = null,
    val cpuCores: List<ThermalReading?> = List(8) { null },
    val gpuOverall: ThermalReading? = null,
    val gpuSensor1: ThermalReading? = null,
    val gpuSensor2: ThermalReading? = null,
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
            .put("cpuEcoCluster", encode(cpuEcoCluster))
            .put("cpuPerfCluster", encode(cpuPerfCluster))
            .put("cpuCores", cores)
            .put("gpuOverall", encode(gpuOverall))
            .put("gpuSensor1", encode(gpuSensor1))
            .put("gpuSensor2", encode(gpuSensor2))
            .put("allSensors", sensors)
            .toString()
    }

    fun readingFor(key: String): ThermalReading? = when {
        key == "cpuTemp" -> cpuOverall
        key == "cpuEcoCluster" -> cpuEcoCluster
        key == "cpuPerfCluster" -> cpuPerfCluster
        key == "gpuTemp" -> gpuOverall
        key == "gpuSensor1" -> gpuSensor1
        key == "gpuSensor2" -> gpuSensor2
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
                        cpuEcoCluster = decode(json.opt("cpuEcoCluster")),
                        cpuPerfCluster = decode(json.opt("cpuPerfCluster")),
                        cpuCores = cores,
                        gpuOverall = decode(json.opt("gpuOverall")),
                        gpuSensor1 = decode(json.opt("gpuSensor1")),
                        gpuSensor2 = decode(json.opt("gpuSensor2")),
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
        // The thermal zone "type" identifies the source. Accept only names ending
        // exactly with -usr; step aliases and non-usr zones are never read.
        File("/sys/class/thermal").listFiles { _, name -> name.startsWith("thermal_zone") }
            ?.mapNotNull { directory ->
                val type = readText(File(directory, "type"))?.trim()?.lowercase(Locale.US)
                if (type.isNullOrEmpty() ||
                    !directory.name.removePrefix("thermal_zone").all { it.isDigit() } ||
                    !type.endsWith("-usr") || type.endsWith("-step")) {
                    null
                } else ThermalZone(directory, type)
            }?.sortedBy { it.id.removePrefix("thermal_zone").toIntOrNull() ?: Int.MAX_VALUE }
            ?: emptyList()
    }

    @Suppress("UNUSED_PARAMETER")
    fun sample(config: HudConfig, includeAllForSettings: Boolean = false): TemperatureSnapshot {
        // Cached eligible zone paths are sampled on the background HandlerThread.
        val values = catalog.mapNotNull(::readTemperature)
        val valuesById = values.associateBy { it.zone.id }
        val allSensorRows = catalog.map { zone ->
            valuesById[zone.id]?.asReading()
                ?: ThermalReading(zone.id, zone.type, null, zone.sourceName)
        }

        val cpuValues = values.filter { isCpuZone(it.zone.type) }
        val gpuValues = values.filter { isGpuZone(it.zone.type) }

        val cpuEco = clusterReading(
            expectedType = "cpuss-0-usr",
            label = "CPU Eco Cluster",
            logicalId = "cpuEcoCluster",
            coreRange = 0..3,
            cpuValues = cpuValues
        )
        val cpuPerf = clusterReading(
            expectedType = "cpuss-1-usr",
            label = "CPU Perf Cluster",
            logicalId = "cpuPerfCluster",
            coreRange = 4..7,
            cpuValues = cpuValues
        )

        val gpu1 = gpuReading(
            expectedType = "gpuss-0-usr",
            label = "GPU Sensor 1",
            logicalId = "gpuSensor1",
            index = 0,
            candidates = gpuValues,
            alreadyUsedSources = emptySet()
        )
        val gpu2 = gpuReading(
            expectedType = "gpuss-1-usr",
            label = "GPU Sensor 2",
            logicalId = "gpuSensor2",
            index = 1,
            candidates = gpuValues,
            alreadyUsedSources = setOfNotNull(gpu1?.sourceName)
        )

        val cpuTotal = averageReadings(listOfNotNull(cpuEco, cpuPerf))
            ?: average(cpuValues)?.let {
                ThermalReading("cpuTemp", "CPU", it, "Fallback average of active CPU -usr sensors")
            }
        val gpuTotal = averageReadings(listOfNotNull(gpu1, gpu2))
            ?: average(gpuValues)?.let {
                ThermalReading("gpuTemp", "GPU", it, "Fallback average of active GPU -usr sensors")
            }

        return TemperatureSnapshot(
            cpuOverall = cpuTotal?.copy(id = "cpuTemp", label = "CPU"),
            cpuEcoCluster = cpuEco,
            cpuPerfCluster = cpuPerf,
            cpuCores = coreReadings(cpuValues),
            gpuOverall = gpuTotal?.copy(id = "gpuTemp", label = "GPU"),
            gpuSensor1 = gpu1,
            gpuSensor2 = gpu2,
            allSensors = allSensorRows
        )
    }

    private fun clusterReading(
        expectedType: String,
        label: String,
        logicalId: String,
        coreRange: IntRange,
        cpuValues: List<ZoneValue>
    ): ThermalReading? {
        val exact = cpuValues.firstOrNull { it.zone.type == expectedType }
        if (exact != null) return ThermalReading(logicalId, label, exact.celsius, exact.sourceName)

        val clusterCores = cpuValues.filter { value ->
            coreIndex(value.zone.type)?.let { it in coreRange } == true
        }
        if (clusterCores.isNotEmpty()) {
            return ThermalReading(
                logicalId,
                label,
                average(clusterCores),
                "Fallback average: " + clusterCores.joinToString { it.sourceName }
            )
        }

        val targetIndex = if (logicalId == "cpuEcoCluster") 0 else 1
        val fallback = cpuValues.minWithOrNull(
            compareBy<ZoneValue> {
                val idx = clusterIndex(it.zone.type)
                if (idx == null) 10 else kotlin.math.abs(idx - targetIndex)
            }.thenBy { it.zone.id.removePrefix("thermal_zone").toIntOrNull() ?: Int.MAX_VALUE }
        ) ?: return null
        return ThermalReading(logicalId, label, fallback.celsius, "Fallback: ${fallback.sourceName}")
    }

    private fun gpuReading(
        expectedType: String,
        label: String,
        logicalId: String,
        index: Int,
        candidates: List<ZoneValue>,
        alreadyUsedSources: Set<String>
    ): ThermalReading? {
        val exact = candidates.firstOrNull { it.zone.type == expectedType }
        if (exact != null) return ThermalReading(logicalId, label, exact.celsius, exact.sourceName)

        val fallback = candidates
            .filterNot { it.sourceName in alreadyUsedSources }
            .minWithOrNull(
                compareBy<ZoneValue> {
                    val candidateIndex = gpuIndex(it.zone.type)
                    if (candidateIndex == null) 10 else kotlin.math.abs(candidateIndex - index)
                }.thenBy { it.zone.id.removePrefix("thermal_zone").toIntOrNull() ?: Int.MAX_VALUE }
            )
            ?: candidates.minByOrNull {
                val candidateIndex = gpuIndex(it.zone.type)
                if (candidateIndex == null) 10 else kotlin.math.abs(candidateIndex - index)
            }
            ?: return null
        return ThermalReading(logicalId, label, fallback.celsius, "Fallback: ${fallback.sourceName}")
    }

    private fun averageReadings(readings: List<ThermalReading>): Float? {
        val temperatures = readings.mapNotNull { it.celsius }
        if (temperatures.isEmpty()) return null
        val total = temperatures.sumOf { it.toDouble() }
        return (total / temperatures.size).toFloat().takeIf { it.isFinite() }
    }

    private fun clusterIndex(type: String): Int? = when (type) {
        "cpuss-0-usr" -> 0
        "cpuss-1-usr" -> 1
        else -> null
    }

    private fun gpuIndex(type: String): Int? =
        GPU_INDEX_PATTERN.find(type)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun average(values: List<ZoneValue>): Float? {
        if (values.isEmpty()) return null
        var total = 0.0
        for (value in values) total += value.celsius
        val mean = total / values.size
        return mean.takeIf { it.isFinite() }?.toFloat()
    }

    private fun readTemperature(zone: ThermalZone): ZoneValue? {
        val raw = readNumber(File(zone.directory, "temp"))?.toFloat() ?: return null
        val celsius = raw / 1000f
        return if (celsius in -20f..150f) ZoneValue(zone, celsius) else null
    }

    private fun coreReadings(values: List<ZoneValue>): List<ThermalReading?> {
        val grouped = values.mapNotNull { value -> coreIndex(value.zone.type)?.let { it to value } }
            .groupBy({ it.first }, { it.second })
        return (0..7).map { core ->
            val picked = grouped[core].orEmpty().maxWithOrNull(
                compareBy<ZoneValue> { if (EXACT_CORE_PATTERN.matches(it.zone.type)) 2 else 1 }
                    .thenBy { it.zone.id }
            ) ?: return@map null
            ThermalReading("core${core}Temp", "CPU $core", picked.celsius, picked.sourceName)
        }
    }

    private fun isCpuZone(type: String): Boolean = type.contains("cpu")
    private fun isGpuZone(type: String): Boolean = type.contains("gpu")

    private fun coreIndex(type: String): Int? {
        val qcom = QCOM_CORE_PATTERN.find(type)
        if (qcom != null) {
            val cluster = qcom.groupValues[1].toIntOrNull() ?: return null
            val local = qcom.groupValues[2].toIntOrNull() ?: return null
            return (cluster * 4 + local).takeIf { it in 0..7 }
        }
        for (pattern in GENERIC_CORE_PATTERNS) {
            val match = pattern.find(type) ?: continue
            return match.groupValues.getOrNull(1)?.toIntOrNull()
        }
        return null
    }

    private fun readNumber(file: File): Long? = readText(file)?.trim()?.toLongOrNull()
    private fun readText(file: File): String? =
        try { file.readText().lineSequence().firstOrNull() } catch (_: Exception) { null }

    private companion object {
        val QCOM_CORE_PATTERN = Regex("^cpu-([0-1])-([0-3])-usr$")
        val GENERIC_CORE_PATTERNS = listOf(
            Regex("^cpu-([0-7])-usr$"),
            Regex("^cpu([0-7])-usr$"),
            Regex("^cpu[-_]core[-_]?([0-7])-usr$"),
            Regex("^cpu_core[-_]?([0-7])-usr$")
        )
        val EXACT_CORE_PATTERN = Regex("^cpu-[0-7]-usr$")
        val GPU_INDEX_PATTERN = Regex("^(?:gpuss|gpu)[-_]?(\\d+)-usr$")
    }
}


object SensorSourceLabels {
    private val sourceSuffix = Regex("[-_](usr|step)(?:[-_].*)?$", RegexOption.IGNORE_CASE)
    private val sourceVariantPattern = Regex("(?:^|[-_])(usr|step)(?:$|[-_])", RegexOption.IGNORE_CASE)
    private val qcomCpu = Regex("^cpu-([0-1])-([0-3])-usr$")
    private val genericCpu = Regex("^cpu(?:[-_]core)?[-_]?([0-7])-usr$")
    private val indexedGpu = Regex("^(?:gpuss|gpu)[-_]?([0-9]+)-usr$")

    fun cpuCoreOption(reading: ThermalReading): String? {
        val type = reading.label.lowercase(Locale.US)
        if (!type.endsWith("-usr") || type.endsWith("-step")) return null
        val qcom = qcomCpu.find(type)
        if (qcom != null) {
            val cluster = qcom.groupValues[1].toIntOrNull() ?: return null
            val local = qcom.groupValues[2].toIntOrNull() ?: return null
            return "CPU ${cluster * 4 + local} · usr"
        }
        val generic = genericCpu.find(type) ?: return null
        return "CPU ${generic.groupValues[1]} · usr"
    }

    fun cpuClusterOption(reading: ThermalReading): String? = when (reading.label.lowercase(Locale.US)) {
        "cpuss-0-usr" -> "CPU Eco Cluster · usr"
        "cpuss-1-usr" -> "CPU Perf Cluster · usr"
        else -> null
    }

    fun gpuSourceOption(reading: ThermalReading): String? {
        val type = reading.label.lowercase(Locale.US)
        if (!type.endsWith("-usr") || type.endsWith("-step") ||
            (!type.contains("gpu") && !type.contains("gpuss"))) return null
        val indexed = indexedGpu.find(type)
        if (indexed != null) return "GPU Sensor ${(indexed.groupValues[1].toIntOrNull() ?: 0) + 1} · usr"
        return "GPU Sensor · ${reading.id}"
    }

    fun sourceVariant(reading: ThermalReading): String? = sourceVariant(reading.label)

    fun sourceVariant(label: String): String? =
        sourceVariantPattern.find(label)?.groupValues?.getOrNull(1)?.lowercase(Locale.US)
            ?.takeIf { it == "usr" }

    fun overlayLabel(reading: ThermalReading): String {
        val type = reading.label.lowercase(Locale.US)
        return when (type) {
            "cpuss-0-usr" -> "CPU Eco Cluster"
            "cpuss-1-usr" -> "CPU Perf Cluster"
            "gpuss-0-usr" -> "GPU Sensor 1"
            "gpuss-1-usr" -> "GPU Sensor 2"
            else -> (cpuCoreOption(reading) ?: gpuSourceOption(reading)
                ?: reading.label.uppercase(Locale.US)).substringBefore(" · ").replace(sourceSuffix, "")
        }
    }
}
