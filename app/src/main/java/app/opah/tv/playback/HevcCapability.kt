package app.opah.tv.playback

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList

/** Advertise Main 5.1 only when a platform decoder reports the required capability. */
internal fun platformSupportsHevcMain51(): Boolean = runCatching {
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
        !codec.isEncoder && codec.supportedTypes.any { it.equals("video/hevc", ignoreCase = true) } &&
            codec.getCapabilitiesForType("video/hevc").let { caps ->
                caps.profileLevels.any {
                    it.profile == CodecProfileLevel.HEVCProfileMain &&
                        it.level >= CodecProfileLevel.HEVCMainTierLevel51
                } && caps.videoCapabilities?.isSizeSupported(3840, 2160) == true
            }
    }
}.getOrDefault(false)
