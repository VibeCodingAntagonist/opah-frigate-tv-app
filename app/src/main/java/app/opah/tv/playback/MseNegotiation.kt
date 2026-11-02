package app.opah.tv.playback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Never relay a remote error body to logs or the UI. */
internal const val MSE_AVC_CODECS = "avc1.640029,mp4a.40.2,mp4a.40.5"
internal const val MSE_HEVC_CODEC = "hvc1.1.6.L153.B0"

internal fun mseCodecs(hevcSupported: Boolean): String =
    if (hevcSupported) "$MSE_AVC_CODECS,$MSE_HEVC_CODEC" else MSE_AVC_CODECS

internal fun acceptsMseNegotiation(text: String, hevcSupported: Boolean = false): Boolean {
    if (text.length > 16_384) return false
    return runCatching {
        val message = Json.parseToJsonElement(text).jsonObject
        if (message["type"]?.jsonPrimitive?.content != "mse") return false
        val mime = message["value"]?.jsonPrimitive?.content ?: return false
        val match = Regex("""video/mp4;\s*codecs="([^"]+)"""").matchEntire(mime) ?: return false
        val codecs = match.groupValues[1].split(',').map(String::trim)
        fun video(codec: String): Boolean {
            if (Regex("avc1\\.[0-9a-fA-F]{6}").matches(codec)) return true
            if (!hevcSupported) return false
            // Main profile, low tier, at most level 5.1, as advertised to go2rtc.
            val hevc = Regex("hvc1\\.1\\.[0-9a-fA-F]{1,8}\\.L([0-9]{2,3})(?:\\.[0-9a-fA-F]{2}){1,6}")
                .matchEntire(codec) ?: return false
            return hevc.groupValues[1].toInt() in setOf(30, 60, 63, 90, 93, 120, 123, 150, 153)
        }
        codecs.count(::video) == 1 && codecs.all {
            video(it) || it in setOf("mp4a.40.2", "mp4a.40.5")
        }
    }.getOrDefault(false)
}
