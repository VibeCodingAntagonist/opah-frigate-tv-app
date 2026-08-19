package app.opah.tv

import android.graphics.Rect
import app.opah.tv.playback.PlaybackKind

data class PipAspectRatio(
    val width: Int,
    val height: Int,
)

data class PictureInPictureRequest(
    val title: String,
    val subtitle: String,
    val aspectRatio: PipAspectRatio?,
    val sourceRectHint: Rect?,
)

internal fun shouldOfferLivePictureInPicture(
    sdkInt: Int,
    hasSystemFeature: Boolean,
    kind: PlaybackKind,
    firstFrameRendered: Boolean,
): Boolean = sdkInt >= TV_PIP_MIN_SDK &&
    hasSystemFeature &&
    kind == PlaybackKind.LIVE &&
    firstFrameRendered

internal fun pipAspectRatio(width: Int, height: Int): PipAspectRatio? {
    if (width <= 0 || height <= 0) return null
    val ratio = width.toDouble() / height.toDouble()
    if (ratio !in MIN_PIP_ASPECT_RATIO..MAX_PIP_ASPECT_RATIO) return null
    return PipAspectRatio(width, height)
}

private const val TV_PIP_MIN_SDK = 34
private const val MIN_PIP_ASPECT_RATIO = 1.0 / 2.39
private const val MAX_PIP_ASPECT_RATIO = 2.39
