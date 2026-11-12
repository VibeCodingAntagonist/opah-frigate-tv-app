/*
 * Portrait-workaround eligibility adapted from AndroidX Media3 MediaCodecInfo 1.11.0
 * Copyright (C) 2016 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0)
 * Distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND
 * Changes: independent native size/alignment checks; no performance-point shortcut
 */
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package app.opah.tv.playback.media3

import android.media.MediaCodecList
import android.media.MediaCodecInfo.VideoCapabilities
import android.os.Build
import androidx.media3.common.Format
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo

internal fun interface VideoDecoderSizeProbe {
    fun support(decoder: MediaCodecInfo, format: Format): VideoDecoderSizeSupport
}

/**
 * Device-only lookup, cached for this player lifetime. No codec names, raw platform failures or
 * stream data leave this adapter. Enumeration happens lazily on the playback thread, not per frame.
 */
internal class AndroidVideoDecoderSizeProbe : VideoDecoderSizeProbe {
    private val codecInfos by lazy {
        runCatching { MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList() }
            .getOrDefault(emptyList())
    }
    private val capabilities = mutableMapOf<Pair<String, String>, VideoCapabilities?>()

    override fun support(decoder: MediaCodecInfo, format: Format): VideoDecoderSizeSupport {
        if (format.width <= 0 || format.height <= 0) return VideoDecoderSizeSupport.UNKNOWN
        val videoCapabilities = synchronized(capabilities) {
            val key = decoder.name to decoder.codecMimeType
            if (key !in capabilities) {
                capabilities[key] = runCatching {
                    codecInfos.firstOrNull { it.name == decoder.name }
                        ?.getCapabilitiesForType(decoder.codecMimeType)?.videoCapabilities
                }.getOrNull()
            }
            capabilities[key]
        } ?: return VideoDecoderSizeSupport.UNKNOWN
        return try {
            videoDecoderSizeSupport(
                width = format.width,
                height = format.height,
                widthAlignment = videoCapabilities.widthAlignment,
                heightAlignment = videoCapabilities.heightAlignment,
                allowRotatedPortrait = media3PortraitSizeWorkaroundAllowed(
                    codecName = decoder.name,
                    device = Build.DEVICE,
                    workaroundsEnabled = MediaLibraryInfo.enableWorkarounds(),
                ),
                isSizeSupported = videoCapabilities::isSizeSupported,
            )
        } catch (_: RuntimeException) {
            VideoDecoderSizeSupport.UNKNOWN
        }
    }
}

/** Keep Media3's documented portrait exception and its MTK device exclusion, not a universal swap. */
internal fun media3PortraitSizeWorkaroundAllowed(
    codecName: String,
    device: String,
    workaroundsEnabled: Boolean,
): Boolean = workaroundsEnabled &&
    !(codecName == "OMX.MTK.VIDEO.DECODER.HEVC" && device == "mcv5a")
