package app.opah.tv.playback.media3

/** Native size evidence is independent of Media3's handled/performance-point result. */
internal enum class VideoDecoderSizeSupport {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN,
}

/**
 * Checks coded dimensions, including native alignment and the permitted portrait workaround.
 * Missing/throwing capability queries never become evidence that a stream is too large.
 * Issue #22's reproduction and native-size proposal were supplied by megabitus98.
 */
internal fun videoDecoderSizeSupport(
    width: Int,
    height: Int,
    widthAlignment: Int,
    heightAlignment: Int,
    allowRotatedPortrait: Boolean,
    isSizeSupported: (Int, Int) -> Boolean,
): VideoDecoderSizeSupport {
    if (width <= 0 || height <= 0 || widthAlignment <= 0 || heightAlignment <= 0) {
        return VideoDecoderSizeSupport.UNKNOWN
    }

    fun query(w: Int, h: Int): VideoDecoderSizeSupport {
        fun aligned(value: Int, alignment: Int): Int? =
            ((value.toLong() + alignment - 1) / alignment * alignment)
                .takeIf { it <= Int.MAX_VALUE }?.toInt()

        val alignedWidth = aligned(w, widthAlignment) ?: return VideoDecoderSizeSupport.UNSUPPORTED
        val alignedHeight = aligned(h, heightAlignment) ?: return VideoDecoderSizeSupport.UNSUPPORTED
        return try {
            if (isSizeSupported(alignedWidth, alignedHeight)) VideoDecoderSizeSupport.SUPPORTED
            else VideoDecoderSizeSupport.UNSUPPORTED
        } catch (_: RuntimeException) {
            VideoDecoderSizeSupport.UNKNOWN
        }
    }

    val direct = query(width, height)
    if (direct == VideoDecoderSizeSupport.SUPPORTED || width >= height || !allowRotatedPortrait) {
        return direct
    }
    val rotated = query(height, width)
    return when {
        rotated == VideoDecoderSizeSupport.SUPPORTED -> rotated
        direct == VideoDecoderSizeSupport.UNKNOWN || rotated == VideoDecoderSizeSupport.UNKNOWN ->
            VideoDecoderSizeSupport.UNKNOWN
        else -> VideoDecoderSizeSupport.UNSUPPORTED
    }
}

internal data class SizeCheckedVideoDecoders<T>(
    val decoders: List<T>,
    val support: VideoDecoderSizeSupport,
)

/** The caller supplies only decoders eligible for this format and its current resource policy. */
internal fun <T> sizeCheckedVideoDecoders(
    eligibleDecoders: List<T>,
    support: (T) -> VideoDecoderSizeSupport,
): SizeCheckedVideoDecoders<T> {
    val checked = eligibleDecoders.map { decoder -> decoder to support(decoder) }
    val remaining = checked.filter { it.second != VideoDecoderSizeSupport.UNSUPPORTED }
    return SizeCheckedVideoDecoders(
        decoders = remaining.map { it.first },
        support = when {
            checked.isEmpty() -> VideoDecoderSizeSupport.UNKNOWN
            remaining.isEmpty() -> VideoDecoderSizeSupport.UNSUPPORTED
            remaining.any { it.second == VideoDecoderSizeSupport.SUPPORTED } -> VideoDecoderSizeSupport.SUPPORTED
            else -> VideoDecoderSizeSupport.UNKNOWN
        },
    )
}

/** Fixed, non-secret cause marker; it must never carry a Format, URI or platform exception. */
internal class NativeVideoSizeUnsupportedException : RuntimeException("Native video size unsupported")

/** The selected decoder failed admission, but another eligible decoder may still fit. */
internal class SelectedVideoDecoderSizeUnsupportedException : RuntimeException("Selected video decoder size unsupported")
