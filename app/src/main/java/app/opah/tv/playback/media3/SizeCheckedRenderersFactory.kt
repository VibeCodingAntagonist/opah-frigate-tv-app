@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package app.opah.tv.playback.media3

import android.content.Context
import android.media.MediaCrypto
import android.media.MediaFormat
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/** Shared by live transports; codec selection and audio configuration remain with the caller. */
internal open class SizeCheckedRenderersFactory(
    context: Context,
    private val sizeProbe: VideoDecoderSizeProbe = AndroidVideoDecoderSizeProbe(),
    private val onSizeRejected: () -> Unit = {},
) : DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        // Live playback has no bundled video extensions and must not bypass native size admission.
        out.add(
            SizeCheckedVideoRenderer(
                context,
                MediaCodecVideoRenderer.Builder(context)
                    .setCodecAdapterFactory(codecAdapterFactory)
                    .setMediaCodecSelector(mediaCodecSelector)
                    .setEnableDecoderFallback(enableDecoderFallback)
                    .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                    .setEventHandler(eventHandler)
                    .setEventListener(eventListener)
                    .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY),
                sizeProbe,
                onSizeRejected,
                mediaCodecSelector,
            ),
        )
    }

    override fun buildSecondaryVideoRenderer(
        renderer: Renderer,
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
    ): Renderer? = null // Live sessions do not reserve an additional warmed video decoder.
}

/**
 * Enforces admission even when Media3's track capabilities say HANDLED. Initialization, adaptive
 * reuse and decoded output all use the actual format; saved strategies cannot bypass the check.
 */
internal class SizeCheckedVideoRenderer(
    private val applicationContext: Context,
    builder: Builder,
    private val sizeProbe: VideoDecoderSizeProbe,
    private val onSizeRejected: () -> Unit,
    private val configuredSelector: MediaCodecSelector,
) : MediaCodecVideoRenderer(builder) {
    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val checked = checkedDecoders(mediaCodecSelector, format, requiresSecureDecoder)
        if (checked.support == VideoDecoderSizeSupport.UNSUPPORTED) rejectSize(format, allEligibleRejected = true)
        return checked.decoders
    }

    private fun checkedDecoders(
        selector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean,
    ): SizeCheckedVideoDecoders<MediaCodecInfo> {
        val eligible = super.getDecoderInfos(selector, format, requiresSecureDecoder)
            .filter { decoder ->
                runCatching { decoder.isFormatFunctionallySupported(applicationContext, format) }
                    .getOrDefault(true)
            }
        return sizeCheckedVideoDecoders(eligible) { sizeProbe.support(it, format) }
    }

    override fun getMediaCodecConfiguration(
        codecInfo: MediaCodecInfo,
        format: Format,
        crypto: MediaCrypto?,
        codecOperatingRate: Float,
    ): MediaCodecAdapter.Configuration {
        requireAdmitted(codecInfo, format)
        return super.getMediaCodecConfiguration(codecInfo, format, crypto, codecOperatingRate)
    }

    override fun canReuseCodec(
        codecInfo: MediaCodecInfo,
        oldFormat: Format,
        newFormat: Format,
        isAdaptiveFormatChange: Boolean,
    ): DecoderReuseEvaluation {
        if (sizeProbe.support(codecInfo, newFormat) == VideoDecoderSizeSupport.UNSUPPORTED) {
            // Reinitialize through the filtered decoder list; another eligible decoder may fit.
            return DecoderReuseEvaluation(
                codecInfo.name, oldFormat, newFormat, DecoderReuseEvaluation.REUSE_RESULT_NO,
                DecoderReuseEvaluation.DISCARD_REASON_VIDEO_MAX_RESOLUTION_EXCEEDED,
            )
        }
        return super.canReuseCodec(codecInfo, oldFormat, newFormat, isAdaptiveFormatChange)
    }

    override fun onOutputFormatChanged(format: Format, mediaFormat: MediaFormat?) {
        val decoder = codecInfo
        if (decoder != null) {
            requireAdmitted(decoder, format)
            // Use coded output size, not crop/display size. This also covers missing input sizes.
            val actual = runCatching {
                if (mediaFormat?.containsKey(MediaFormat.KEY_WIDTH) == true &&
                    mediaFormat.containsKey(MediaFormat.KEY_HEIGHT)
                ) format.buildUpon()
                    .setWidth(mediaFormat.getInteger(MediaFormat.KEY_WIDTH))
                    .setHeight(mediaFormat.getInteger(MediaFormat.KEY_HEIGHT)).build()
                else null
            }.getOrNull()
            if (actual != null) requireAdmitted(decoder, actual)
        }
        super.onOutputFormatChanged(format, mediaFormat)
    }

    private fun requireAdmitted(decoder: MediaCodecInfo, format: Format) {
        if (sizeProbe.support(decoder, format) != VideoDecoderSizeSupport.UNSUPPORTED) return
        // A late output-size disclosure can reject the selected decoder while an eligible
        // alternative still fits. Only complete native rejection warrants the TV-size message.
        val allRejected = try {
            checkedDecoders(configuredSelector, format, decoder.secure).support ==
                VideoDecoderSizeSupport.UNSUPPORTED
        } catch (_: Exception) {
            false
        }
        rejectSize(format, allRejected)
    }

    private fun rejectSize(format: Format, allEligibleRejected: Boolean): Nothing {
        // In the compatibility backend this synchronously stops verification evidence, before
        // Media3's main-thread error listener or a pending rendered-frame callback can run.
        try {
            onSizeRejected()
        } catch (_: RuntimeException) {
            // A callback cannot turn a native rejection into permission to display a frame.
        }
        throw createRendererException(
            if (allEligibleRejected) NativeVideoSizeUnsupportedException()
            else SelectedVideoDecoderSizeUnsupportedException(),
            format,
            if (allEligibleRejected) PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
            else PlaybackException.ERROR_CODE_DECODING_FAILED,
        )
    }
}
