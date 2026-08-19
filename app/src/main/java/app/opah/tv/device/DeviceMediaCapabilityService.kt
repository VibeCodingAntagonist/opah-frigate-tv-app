package app.opah.tv.device

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import app.opah.tv.data.model.CodecCapability
import app.opah.tv.data.model.DecoderCapability
import app.opah.tv.data.model.DeviceDiagnostics

class DeviceMediaCapabilityService {
    fun inspect(): DeviceDiagnostics {
        val codecInfos = runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList()
        }.getOrDefault(emptyList())

        return DeviceDiagnostics(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            apiLevel = Build.VERSION.SDK_INT,
            codecs = TARGETS.map { (label, mimeType) ->
                CodecCapability(
                    label = label,
                    mimeType = mimeType,
                    decoders = codecInfos.mapNotNull { info -> info.describeDecoder(mimeType) },
                )
            },
        )
    }

    private fun MediaCodecInfo.describeDecoder(mimeType: String): DecoderCapability? {
        if (isEncoder || supportedTypes.none { it.equals(mimeType, ignoreCase = true) }) return null
        val capabilities = runCatching { getCapabilitiesForType(mimeType) }.getOrNull()
        return DecoderCapability(
            name = name,
            mimeType = mimeType,
            hardwareAccelerated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isHardwareAccelerated
            } else {
                null
            },
            softwareOnly = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isSoftwareOnly else null,
            vendor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isVendor else null,
            adaptivePlayback = capabilities?.isFeatureSupported(
                MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback,
            ) == true,
        )
    }

    private companion object {
        val TARGETS = listOf(
            "H.264 / AVC" to "video/avc",
            "H.265 / HEVC" to "video/hevc",
            "Opus" to "audio/opus",
            "AAC" to "audio/mp4a-latm",
        )
    }
}

