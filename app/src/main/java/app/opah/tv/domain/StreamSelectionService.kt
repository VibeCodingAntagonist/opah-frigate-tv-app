package app.opah.tv.domain

import app.opah.tv.data.model.Camera
import app.opah.tv.data.model.CodecCapability
import app.opah.tv.data.model.LiveStreamOption
import app.opah.tv.data.model.StreamPreference
import app.opah.tv.data.model.StreamSelection
import app.opah.tv.data.model.VideoCodec

class StreamSelectionService {
    fun select(
        camera: Camera,
        deviceCodecs: List<CodecCapability>,
        preference: StreamPreference = StreamPreference.AUTOMATIC,
    ): Result<StreamSelection> = runCatching {
        require(camera.streams.isNotEmpty()) { "No live streams are configured for ${camera.displayName}." }
        val supportedMimes = deviceCodecs.filter { it.supported }.map { it.mimeType }.toSet()

        val candidates = camera.streams.filter { option ->
            val mime = option.metadata?.videoCodec?.mimeType
            mime == null || mime in supportedMimes
        }
        require(candidates.isNotEmpty()) {
            val codecs = camera.streams.map { it.metadata?.videoCodec?.displayName ?: "unknown" }.distinct()
            "No compatible decoder was reported for ${codecs.joinToString()}."
        }

        val selected = candidates.maxBy { score(it, preference) }
        val codec = selected.metadata?.videoCodec ?: VideoCodec.UNKNOWN
        StreamSelection(
            option = selected,
            reason = when {
                codec == VideoCodec.UNKNOWN -> "Selected from Frigate's configured order; codec will be verified by playback."
                preference == StreamPreference.AUTOMATIC -> "Best configured stream compatible with the device's reported ${codec.displayName} decoder."
                else -> "Best compatible match for ${preference.name.lowercase().replace('_', ' ')}."
            },
        )
    }

    private fun score(option: LiveStreamOption, preference: StreamPreference): Int {
        val label = option.label.lowercase()
        var score = 0
        if (option.metadata?.available == true) score += 200
        if (option.metadata?.videoCodec != VideoCodec.UNKNOWN) score += 50
        if ("two-way" in label || "twoway" in label) score -= 10
        when (preference) {
            StreamPreference.AUTOMATIC -> {
                if ("main" in label) score += 100
                if ("low" in label || "sub" in label) score -= 50
            }
            StreamPreference.MAIN -> if ("main" in label) score += 300 else score -= 100
            StreamPreference.LOW_BANDWIDTH -> {
                if ("low" in label || "sub" in label) score += 300 else score -= 100
            }
        }
        return score
    }
}

