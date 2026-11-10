package app.opah.tv.data.realtime

import app.opah.tv.data.FrigateJsonParsers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Bounded outer-envelope decoder for the shared authenticated WebSocket.
 *
 * Wrong JSON shapes are ignored instead of throwing, and only sanitized typed values leave this
 * boundary. Generation-specific REST/Review detail decoding remains outside the transport.
 */
class RealtimeInboundMessageParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val maximumFrameCharacters: Int = 1_048_576,
    private val maximumCameraCount: Int = 256,
    private val maximumIdentifierCharacters: Int = 256,
    private val maximumTopicCharacters: Int = 128,
    private val maximumObjectsPerCamera: Int = 256,
) {
    private val reviewDetails = FrigateJsonParsers(json)

    init {
        require(maximumFrameCharacters > 0)
        require(maximumCameraCount > 0)
        require(maximumIdentifierCharacters > 0)
        require(maximumTopicCharacters > 0)
        require(maximumObjectsPerCamera > 0)
    }

    fun parse(rawJson: String): RealtimeInboundMessage? {
        if (rawJson.length > maximumFrameCharacters) return null
        val envelope = parseObject(rawJson) ?: return null
        val topic = envelope.stringValue("topic")
            ?.takeIf { it.isBoundedText(maximumTopicCharacters) }
            ?: return null
        return when (topic) {
            REVIEW_TOPIC -> parseReview(rawJson, envelope["payload"])
            CAMERA_ACTIVITY_TOPIC -> parseCameraActivity(envelope["payload"])
            else -> RealtimeInboundMessage.UnknownTopic(topic)
        }
    }

    private fun parseReview(rawJson: String, payload: JsonElement?): RealtimeInboundMessage? {
        val updateObject = payload.payloadObject() ?: return null
        val after = updateObject["after"] as? JsonObject ?: return null
        val cameraId = after.stringValue("camera")
            ?.takeIf { it.isBoundedText(maximumIdentifierCharacters) }
            ?: return null
        val reviewId = after.stringValue("id")
            ?.takeIf { it.isBoundedText(maximumIdentifierCharacters) }
            ?: return null
        val lifecycleValue = updateObject.stringValue("type")
            ?.takeIf { it.isBoundedText(MAXIMUM_SIGNAL_CHARACTERS) }
            ?: return null
        val lifecycle = when (lifecycleValue.trim().lowercase()) {
            "new" -> ReviewLifecycleSignal.NEW
            "update" -> ReviewLifecycleSignal.UPDATE
            "end" -> ReviewLifecycleSignal.END
            "genai" -> ReviewLifecycleSignal.GENAI
            else -> ReviewLifecycleSignal.UNKNOWN
        }
        val update = reviewDetails.parseRealtimeReviewUpdate(rawJson)
            ?.takeIf { it.after.camera == cameraId && it.after.id == reviewId }
        return RealtimeInboundMessage.ReviewChanged(cameraId, reviewId, lifecycle, update)
    }

    private fun parseCameraActivity(payload: JsonElement?): RealtimeInboundMessage? {
        val payloadObject = payload.payloadObject() ?: return null
        val activityObject = (payloadObject[CAMERA_ACTIVITY_TOPIC] as? JsonObject) ?: payloadObject
        if (activityObject.isEmpty() || activityObject.size > maximumCameraCount) return null
        val result = LinkedHashMap<String, CameraActivitySignal>(activityObject.size)
        for ((cameraId, element) in activityObject) {
            if (!cameraId.isBoundedText(maximumIdentifierCharacters)) return null
            result[cameraId] = cameraActivitySignal(element) ?: return null
        }
        return RealtimeInboundMessage.CameraActivity(result)
    }

    private fun cameraActivitySignal(element: JsonElement): CameraActivitySignal? {
        if (element !is JsonObject) {
            val value = element.strictString()?.takeIf { it.isBoundedText(MAXIMUM_SIGNAL_CHARACTERS) }
                ?: return null
            return when (value.trim().lowercase()) {
                "idle", "off" -> CameraActivitySignal.IDLE
                "active", "on" -> CameraActivitySignal.ACTIVE
                "recording" -> CameraActivitySignal.RECORDING
                else -> CameraActivitySignal.UNKNOWN
            }
        }
        // Frigate's onConnect snapshot includes objects and configuration per camera.
        // Configuration such as record=true is not evidence of current activity.
        val motion = element["motion"].strictBoolean()
        if (element.containsKey("motion") && motion == null) return CameraActivitySignal.UNKNOWN
        val rawObjects = element["objects"] ?: return when (motion) {
            true -> CameraActivitySignal.ACTIVE
            false -> CameraActivitySignal.IDLE
            null -> CameraActivitySignal.UNKNOWN
        }
        val objects = rawObjects as? JsonArray ?: return null
        if (objects.size > maximumObjectsPerCamera) return null
        var activeObject = false
        var unknownObject = false
        for (rawObject in objects) {
            val value = rawObject as? JsonObject ?: return null
            when (value["stationary"].strictBoolean()) {
                false -> activeObject = true
                true -> Unit
                null -> unknownObject = true
            }
        }
        return when {
            motion == true || activeObject -> CameraActivitySignal.ACTIVE
            unknownObject -> CameraActivitySignal.UNKNOWN
            else -> CameraActivitySignal.IDLE
        }
    }

    private fun JsonElement?.strictBoolean(): Boolean? =
        (this as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

    private fun JsonElement?.payloadObject(): JsonObject? = when (this) {
        is JsonObject -> this
        is JsonPrimitive -> strictString()
            ?.takeIf { it.length <= maximumFrameCharacters }
            ?.let(::parseObject)
        else -> null
    }

    private fun parseObject(value: String): JsonObject? =
        runCatching { json.parseToJsonElement(value) as? JsonObject }.getOrNull()

    private fun JsonObject.stringValue(key: String): String? = get(key).strictString()

    private fun JsonElement?.strictString(): String? =
        (this as? JsonPrimitive)
            ?.takeUnless { it is JsonNull }
            ?.takeIf { it.isString }
            ?.content

    private fun String.isBoundedText(maximumCharacters: Int): Boolean =
        isNotBlank() &&
            length <= maximumCharacters &&
            codePoints().noneMatch { codePoint ->
                Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT.toInt()
            }

    private companion object {
        const val REVIEW_TOPIC = "reviews"
        const val CAMERA_ACTIVITY_TOPIC = "camera_activity"
        const val MAXIMUM_SIGNAL_CHARACTERS = 64
    }
}
