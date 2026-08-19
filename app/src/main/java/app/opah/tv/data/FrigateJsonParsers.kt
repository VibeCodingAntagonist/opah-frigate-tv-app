package app.opah.tv.data

import app.opah.tv.data.model.AudioCodec
import app.opah.tv.data.model.BirdseyeStatus
import app.opah.tv.data.model.Camera
import app.opah.tv.data.model.AcceleratorPerformance
import app.opah.tv.data.model.CameraPerformance
import app.opah.tv.data.model.DetectorPerformance
import app.opah.tv.data.model.FrigatePerformanceSummary
import app.opah.tv.data.model.RecordingStorageVolume
import app.opah.tv.data.model.LiveStreamOption
import app.opah.tv.data.model.RecordingSegment
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.ReviewSeverity
import app.opah.tv.data.model.StreamMetadata
import app.opah.tv.data.model.TemperatureReading
import app.opah.tv.data.model.VideoCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FrigateJsonParsers(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    data class CameraStorageSample(
        val serverLabel: String,
        val usageMiB: Double,
        val bandwidthMiBPerHour: Double,
    )

    fun parseConfiguredCameraNames(configJson: String): Set<String> {
        val root = json.parseToJsonElement(configJson).jsonObject
        return root.obj("cameras")?.keys.orEmpty()
    }

    fun parseAuthorizedCameraNames(
        configJson: String,
        allowedCameras: Set<String>,
    ): Map<String, String> {
        val cameras = json.parseToJsonElement(configJson).jsonObject.obj("cameras")
            ?: return emptyMap()
        return cameras.mapNotNull { (name, value) ->
            if (name !in allowedCameras) return@mapNotNull null
            val camera = value as? JsonObject ?: return@mapNotNull null
            name to (camera.string("friendly_name")?.takeIf(String::isNotBlank) ?: humanize(name))
        }.toMap()
    }

    fun parseCameras(
        configJson: String,
        streamMetadata: Map<String, StreamMetadata>,
        allowedCameras: Set<String>? = null,
    ): List<Camera> {
        val root = json.parseToJsonElement(configJson).jsonObject
        val configuredGo2RtcStreams = root.obj("go2rtc")?.obj("streams")?.keys.orEmpty()
        val cameraObject = root.obj("cameras") ?: return emptyList()

        return cameraObject.entries.mapNotNull { (name, element) ->
            val camera = element as? JsonObject ?: return@mapNotNull null
            if (camera.bool("enabled") == false) return@mapNotNull null
            if (allowedCameras != null && name !in allowedCameras) return@mapNotNull null

            val liveStreams = camera.obj("live")?.obj("streams")
            val options = liveStreams?.entries?.mapNotNull { (label, streamElement) ->
                val streamName = streamElement.jsonPrimitive.contentOrNull
                    ?: return@mapNotNull null
                LiveStreamOption(label, streamName, streamMetadata[streamName])
            }.orEmpty().ifEmpty {
                if (name in configuredGo2RtcStreams || name in streamMetadata) {
                    listOf(LiveStreamOption("Main", name, streamMetadata[name]))
                } else {
                    emptyList()
                }
            }

            Camera(
                name = name,
                displayName = camera.string("friendly_name")?.takeIf { it.isNotBlank() }
                    ?: humanize(name),
                order = camera.obj("ui")?.int("order") ?: Int.MAX_VALUE,
                streams = options,
            )
        }.filter { camera ->
            val raw = cameraObject[camera.name] as? JsonObject
            raw?.obj("ui")?.bool("dashboard") != false
        }.sortedWith(compareBy<Camera> { it.order }.thenBy { it.name })
    }

    fun parseGo2RtcStreams(rawJson: String): Map<String, StreamMetadata> {
        val root = json.parseToJsonElement(rawJson) as? JsonObject ?: return emptyMap()
        return root.mapValues { (streamName, element) ->
            val streamObject = element as? JsonObject
            val producers = streamObject?.get("producers") as? JsonArray
            val evidence = buildList { collectEvidence(element, false, this) }
                .distinct()
            val joined = evidence.joinToString("\n")
            val resolution = RESOLUTION.find(joined)

            StreamMetadata(
                streamName = streamName,
                available = producers?.isNotEmpty() == true,
                videoCodec = detectVideoCodec(joined),
                audioCodec = detectAudioCodec(joined, evidence),
                width = resolution?.groupValues?.getOrNull(1)?.toIntOrNull(),
                height = resolution?.groupValues?.getOrNull(2)?.toIntOrNull(),
                evidence = evidence.filterNot { it.contains("rtsp://", ignoreCase = true) }
                    .take(12),
            )
        }
    }

    fun parseSingleGo2RtcStream(streamName: String, rawJson: String): StreamMetadata? {
        val root = json.parseToJsonElement(rawJson) as? JsonObject ?: return null
        parseGo2RtcStreams(rawJson)[streamName]?.let { return it }
        val wrapped = JsonObject(mapOf(streamName to root))
        return parseGo2RtcStreams(wrapped.toString())[streamName]
    }

    fun parseReviewItems(rawJson: String): List<ReviewItem> {
        val array = json.parseToJsonElement(rawJson) as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.string("id") ?: return@mapNotNull null
            val camera = item.string("camera") ?: return@mapNotNull null
            val start = item.double("start_time") ?: return@mapNotNull null
            val data = item.obj("data")
            ReviewItem(
                id = id,
                camera = camera,
                startTime = start,
                endTime = item.double("end_time"),
                severity = when (item.string("severity")?.lowercase()) {
                    "alert" -> ReviewSeverity.ALERT
                    "detection" -> ReviewSeverity.DETECTION
                    else -> ReviewSeverity.UNKNOWN
                },
                thumbnailPath = item.string("thumb_path"),
                objects = data.stringList("objects") + data.stringList("audio"),
                zones = data.stringList("zones"),
                hasBeenReviewed = item.bool("has_been_reviewed") ?: false,
            )
        }
    }

    fun parseRecordingSegments(rawJson: String): List<RecordingSegment> {
        val array = json.parseToJsonElement(rawJson) as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val segment = element as? JsonObject ?: return@mapNotNull null
            val start = segment.double("start_time") ?: return@mapNotNull null
            val end = segment.double("end_time") ?: return@mapNotNull null
            if (end <= start) return@mapNotNull null
            RecordingSegment(start, end)
        }.sortedBy(RecordingSegment::startTime)
    }

    fun parseRecordingStorageVolume(rawJson: String): RecordingStorageVolume? {
        val root = json.parseToJsonElement(rawJson) as? JsonObject ?: return null
        val storage = root.obj("service")?.obj("storage") ?: return null
        val recordings = (storage["/media/frigate/recordings"] as? JsonObject)
            ?: storage.entries.firstNotNullOfOrNull { (path, value) ->
                (value as? JsonObject)?.takeIf {
                    path.trimEnd('/').endsWith("/recordings") && it.double("total") != null
                }
            }
            ?: return null
        val total = recordings.double("total") ?: return null
        val used = recordings.double("used") ?: return null
        val free = recordings.double("free") ?: (total - used)
        if (total <= 0.0 || used < 0.0 || free < 0.0) return null
        return RecordingStorageVolume(total, used, free)
    }

    fun parseCameraStorageSamples(rawJson: String): List<CameraStorageSample> {
        val root = json.parseToJsonElement(rawJson) as? JsonObject ?: return emptyList()
        return root.mapNotNull { (label, value) ->
            val item = value as? JsonObject ?: return@mapNotNull null
            CameraStorageSample(
                serverLabel = label,
                usageMiB = (item.double("usage") ?: 0.0).coerceAtLeast(0.0),
                bandwidthMiBPerHour = (item.double("bandwidth") ?: 0.0).coerceAtLeast(0.0),
            )
        }
    }

    fun parsePerformanceSummary(
        rawJson: String,
        fallbackVersion: String,
        authorizedCameraNames: Map<String, String>,
    ): FrigatePerformanceSummary {
        val root = json.parseToJsonElement(rawJson) as? JsonObject ?: JsonObject(emptyMap())
        val service = root.obj("service")
        val cpuUsages = root.obj("cpu_usages")
        val fullSystem = cpuUsages?.get("frigate.full_system") as? JsonObject
        val cpuProcesses = cpuUsages.orEmpty().mapNotNull { (pid, value) ->
            if (pid.toLongOrNull() == null) return@mapNotNull null
            value as? JsonObject
        }
        val cameraStats = root.obj("cameras")
        val cameras = authorizedCameraNames.mapNotNull { (cameraName, displayName) ->
            val item = cameraStats?.get(cameraName) as? JsonObject ?: return@mapNotNull null
            CameraPerformance(
                cameraName = cameraName,
                displayName = displayName,
                cameraFps = item.number("camera_fps"),
                processFps = item.number("process_fps"),
                detectionFps = item.number("detection_fps"),
                skippedFps = item.number("skipped_fps"),
            )
        }.sortedBy(CameraPerformance::displayName)
        val detectors = root.obj("detectors").orEmpty().mapNotNull { (name, value) ->
            val item = value as? JsonObject ?: return@mapNotNull null
            DetectorPerformance(name = humanize(name), inferenceSpeedMs = item.number("inference_speed"))
        }.sortedBy(DetectorPerformance::name)
        val accelerators = buildList {
            addAll(parseAccelerators(root.obj("gpu_usages"), "GPU"))
            addAll(parseAccelerators(root.obj("npu_usages"), "NPU"))
        }
        val temperatures = root.obj("temperatures").orEmpty().mapNotNull { (name, value) ->
            val celsius = when (value) {
                is JsonObject -> value.number("temperature") ?: value.number("value")
                else -> value.numericValue()
            } ?: return@mapNotNull null
            TemperatureReading(humanize(name), celsius)
        }.sortedBy(TemperatureReading::name)

        return FrigatePerformanceSummary(
            version = service?.string("version")?.takeIf(String::isNotBlank) ?: fallbackVersion,
            uptimeSeconds = service?.number("uptime"),
            cameraFps = root.number("camera_fps"),
            processFps = root.number("process_fps"),
            detectionFps = root.number("detection_fps"),
            skippedFps = root.number("skipped_fps"),
            systemCpuPercent = fullSystem?.number("cpu"),
            frigateCpuPercent = cpuProcesses.mapNotNull { it.number("cpu") }.takeIf(List<Double>::isNotEmpty)?.sum(),
            frigateMemoryPercent = cpuProcesses.mapNotNull { it.number("mem") ?: it.number("memory") }
                .takeIf(List<Double>::isNotEmpty)?.sum(),
            detectors = detectors,
            accelerators = accelerators,
            cameras = cameras,
            temperatures = temperatures,
        )
    }

    fun parseBirdseyeStatus(
        configJson: String,
        metadata: Map<String, StreamMetadata>,
    ): BirdseyeStatus {
        val root = json.parseToJsonElement(configJson).jsonObject
        val birdseye = root.obj("birdseye")
        val enabled = birdseye?.bool("enabled") == true
        val restreamConfigured = birdseye?.bool("restream") == true
        val candidate = metadata.entries.firstOrNull {
            it.key.equals("birdseye", ignoreCase = true)
        }
        return BirdseyeStatus(
            enabled = enabled,
            restreamConfigured = restreamConfigured,
            streamAvailable = candidate?.value?.available == true,
            streamName = candidate?.key,
        )
    }

    private fun collectEvidence(
        element: JsonElement,
        relevantParent: Boolean,
        destination: MutableList<String>,
    ) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                val relevant = relevantParent || key.lowercase() in EVIDENCE_KEYS
                collectEvidence(value, relevant, destination)
            }
            is JsonArray -> element.forEach { collectEvidence(it, relevantParent, destination) }
            is JsonNull -> Unit
            else -> if (relevantParent) {
                element.jsonPrimitive.contentOrNull?.let(destination::add)
            }
        }
    }

    private fun detectVideoCodec(value: String): VideoCodec = when {
        HEVC.containsMatchIn(value) -> VideoCodec.HEVC
        AVC.containsMatchIn(value) -> VideoCodec.AVC
        else -> VideoCodec.UNKNOWN
    }

    private fun detectAudioCodec(value: String, evidence: List<String>): AudioCodec = when {
        OPUS.containsMatchIn(value) -> AudioCodec.OPUS
        AAC.containsMatchIn(value) -> AudioCodec.AAC
        PCMA.containsMatchIn(value) -> AudioCodec.PCMA
        PCMU.containsMatchIn(value) -> AudioCodec.PCMU
        evidence.any { it.contains("audio", ignoreCase = true) } -> AudioCodec.UNKNOWN
        else -> AudioCodec.NONE
    }

    private fun humanize(name: String): String = name.replace('_', ' ')

    private fun parseAccelerators(source: JsonObject?, kind: String): List<AcceleratorPerformance> =
        source.orEmpty().mapNotNull { (name, value) ->
            val item = value as? JsonObject ?: return@mapNotNull null
            AcceleratorPerformance(
                name = humanize(name),
                kind = kind,
                usagePercent = item.number("gpu")
                    ?: item.number("npu")
                    ?: item.number("usage")
                    ?: item.number("utilization")
                    ?: item.number("load"),
                memoryPercent = item.number("mem")
                    ?: item.number("memory")
                    ?: item.number("memory_usage"),
            )
        }.sortedBy(AcceleratorPerformance::name)

    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
    private fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean? = get(key)?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.int(key: String): Int? = get(key)?.jsonPrimitive?.intOrNull
    private fun JsonObject.double(key: String): Double? = get(key)?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.number(key: String): Double? = get(key)?.numericValue()
    private fun JsonElement.numericValue(): Double? = runCatching {
        jsonPrimitive.doubleOrNull ?: NUMBER.find(jsonPrimitive.contentOrNull.orEmpty())
            ?.value?.toDoubleOrNull()
    }.getOrNull()
    private fun JsonObject?.stringList(key: String): List<String> =
        (this?.get(key) as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

    companion object {
        private val EVIDENCE_KEYS = setOf(
            "medias", "media", "sdp", "codec", "codec_name", "video", "audio", "resolution",
        )
        private val AVC = Regex("(?i)(H\\.?264|AVC1?|video/avc)")
        private val HEVC = Regex("(?i)(H\\.?265|HEVC|HVC1|video/hevc)")
        private val OPUS = Regex("(?i)(OPUS|audio/opus)")
        private val AAC = Regex("(?i)(MPEG4-GENERIC|MP4A|AAC|audio/mp4a-latm)")
        private val PCMA = Regex("(?i)(PCMA|G711A|G\\.711 A)")
        private val PCMU = Regex("(?i)(PCMU|G711U|G\\.711 (mu|μ))")
        private val RESOLUTION = Regex("(?i)(\\d{3,5})\\s*[x×]\\s*(\\d{3,5})")
        private val NUMBER = Regex("-?\\d+(?:\\.\\d+)?")
    }
}
