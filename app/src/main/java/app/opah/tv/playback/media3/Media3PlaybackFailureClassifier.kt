package app.opah.tv.playback.media3

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import app.opah.tv.playback.compatibility.ClassifiedPlaybackFailure
import app.opah.tv.playback.compatibility.FailureCategory
import app.opah.tv.playback.compatibility.FailurePhase
import app.opah.tv.playback.compatibility.PlaybackDiagnosticCode
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.IdentityHashMap

/** Protocol context known by the source adapter without retaining a URI. */
enum class Media3SourceProtocol {
    RTSP,
    HTTP,
    OTHER,
    UNKNOWN,
}

/** Renderer identity supplied by a callback that can identify the failing renderer. */
enum class Media3RendererType {
    AUDIO,
    VIDEO,
    OTHER,
    UNKNOWN,
}

/** Minimal context that a playback backend may safely add to a Media3 error. */
data class Media3PlaybackFailureContext(
    val sourceProtocol: Media3SourceProtocol = Media3SourceProtocol.UNKNOWN,
    val rendererType: Media3RendererType = Media3RendererType.UNKNOWN,
)

enum class Media3FailureOrigin {
    SOURCE,
    RENDERER,
    UNEXPECTED,
    REMOTE,
    UNKNOWN,
}

enum class Media3FormatSupport {
    HANDLED,
    EXCEEDS_CAPABILITIES,
    UNSUPPORTED_DRM,
    UNSUPPORTED_SUBTYPE,
    UNSUPPORTED_TYPE,
    UNKNOWN,
}

enum class Media3CauseCategory {
    NONE,
    DNS,
    NO_ROUTE,
    CONNECTION_REFUSED,
    TIMEOUT,
    UNEXPECTED_END,
    RTSP_SOURCE,
    RTSP_UNSUPPORTED_TRANSPORT,
    PARSING,
    RESOURCE_EXHAUSTED,
    NATIVE_VIDEO_SIZE_UNSUPPORTED,
    NETWORK_IO,
    OTHER,
}

enum class Media3HttpStatus {
    NONE,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND_OR_GONE,
    OTHER,
}

/** Fixed parser evidence only; never an SDP attribute value or exception message. */
enum class Media3RtspSetupProblem { NONE, MISSING_FMTP, MISSING_VPS, MISSING_SPS, MISSING_PPS }

enum class Media3ErrorKind {
    AUTHENTICATION_EXPIRED,
    PERMISSION_DENIED,
    NOT_SUPPORTED,
    DISCONNECTED,
    TIMEOUT,
    BEHIND_LIVE_WINDOW,
    END_OF_PLAYLIST,
    IO_UNSPECIFIED,
    IO_NETWORK_CONNECTION_FAILED,
    IO_NETWORK_CONNECTION_TIMEOUT,
    IO_INVALID_HTTP_CONTENT_TYPE,
    IO_BAD_HTTP_STATUS,
    IO_FILE_NOT_FOUND,
    IO_NO_PERMISSION,
    IO_CLEARTEXT_NOT_PERMITTED,
    IO_READ_POSITION_OUT_OF_RANGE,
    PARSING_MALFORMED,
    PARSING_UNSUPPORTED,
    DECODER_INIT_FAILED,
    DECODER_QUERY_FAILED,
    DECODING_FAILED,
    DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    DECODING_FORMAT_UNSUPPORTED,
    DECODING_RESOURCES_RECLAIMED,
    AUDIO_RENDERER_FAILED,
    VIDEO_RENDERER_FAILED,
    OTHER,
}

/**
 * Privacy-safe evidence passed to the pure classifier.
 *
 * This type deliberately has no text, URI, response body, exception, or codec-name field.
 */
data class Media3PlaybackFailureEvidence(
    val errorKind: Media3ErrorKind,
    val origin: Media3FailureOrigin = Media3FailureOrigin.UNKNOWN,
    val rendererType: Media3RendererType = Media3RendererType.UNKNOWN,
    val formatType: Media3RendererType = Media3RendererType.UNKNOWN,
    val formatSupport: Media3FormatSupport = Media3FormatSupport.UNKNOWN,
    val causeCategory: Media3CauseCategory = Media3CauseCategory.NONE,
    val httpStatus: Media3HttpStatus = Media3HttpStatus.NONE,
    val sourceProtocol: Media3SourceProtocol = Media3SourceProtocol.UNKNOWN,
    val rtspSetupProblem: Media3RtspSetupProblem = Media3RtspSetupProblem.NONE,
)

/** Injectable pure seam between Media3 extraction and the compatibility domain. */
fun interface Media3PlaybackFailureClassifier {
    fun classify(evidence: Media3PlaybackFailureEvidence): ClassifiedPlaybackFailure
}

/** Default deterministic classifier used by production Media3 adapters. */
object DefaultMedia3PlaybackFailureClassifier : Media3PlaybackFailureClassifier {
    override fun classify(evidence: Media3PlaybackFailureEvidence): ClassifiedPlaybackFailure {
        val rendererType = evidence.resolvedRendererType()
        val category = classifyCategory(evidence, rendererType)
        return ClassifiedPlaybackFailure(
            category = category,
            phase = category.failurePhase(evidence.origin),
            diagnosticCode = if (category == FailureCategory.MEDIA_SOURCE_OR_SDP &&
                evidence.sourceProtocol == Media3SourceProtocol.RTSP &&
                evidence.causeCategory in setOf(Media3CauseCategory.PARSING, Media3CauseCategory.RTSP_SOURCE)
            ) evidence.rtspSetupProblem.diagnosticCode() ?: category.diagnosticCode()
            else category.diagnosticCode(),
        )
    }

    private fun classifyCategory(
        evidence: Media3PlaybackFailureEvidence,
        rendererType: Media3RendererType,
    ): FailureCategory {
        if (evidence.errorKind == Media3ErrorKind.AUTHENTICATION_EXPIRED) {
            return FailureCategory.AUTHENTICATION
        }
        if (evidence.errorKind == Media3ErrorKind.PERMISSION_DENIED) {
            return FailureCategory.AUTHORIZATION
        }

        if (evidence.causeCategory == Media3CauseCategory.NATIVE_VIDEO_SIZE_UNSUPPORTED) {
            return when (evidence.httpStatus) {
                Media3HttpStatus.UNAUTHORIZED -> FailureCategory.AUTHENTICATION
                Media3HttpStatus.FORBIDDEN -> FailureCategory.AUTHORIZATION
                else -> FailureCategory.UNSUPPORTED_VIDEO_SIZE
            }
        }

        if (evidence.errorKind == Media3ErrorKind.NOT_SUPPORTED) {
            return when (rendererType) {
                Media3RendererType.VIDEO -> FailureCategory.UNSUPPORTED_VIDEO_CODEC
                Media3RendererType.AUDIO -> FailureCategory.AUDIO_DECODER
                Media3RendererType.OTHER,
                Media3RendererType.UNKNOWN,
                -> FailureCategory.MEDIA_SOURCE_OR_SDP
            }
        }

        if (evidence.errorKind in DECODER_ERROR_KINDS) {
            return classifyDecoderFailure(evidence, rendererType)
        }

        when (evidence.errorKind) {
            Media3ErrorKind.AUDIO_RENDERER_FAILED -> return FailureCategory.AUDIO_RENDERER
            Media3ErrorKind.VIDEO_RENDERER_FAILED -> return FailureCategory.VIDEO_RENDERER
            else -> Unit
        }

        if (evidence.httpStatus != Media3HttpStatus.NONE) {
            return evidence.httpStatus.httpFailureCategory()
        }

        if (evidence.sourceProtocol == Media3SourceProtocol.RTSP &&
            evidence.rtspSetupProblem != Media3RtspSetupProblem.NONE &&
            evidence.causeCategory in setOf(Media3CauseCategory.PARSING, Media3CauseCategory.RTSP_SOURCE)
        ) return FailureCategory.MEDIA_SOURCE_OR_SDP

        when (evidence.errorKind) {
            Media3ErrorKind.PARSING_MALFORMED,
            Media3ErrorKind.PARSING_UNSUPPORTED,
            Media3ErrorKind.IO_INVALID_HTTP_CONTENT_TYPE,
            -> return FailureCategory.MEDIA_SOURCE_OR_SDP

            Media3ErrorKind.IO_BAD_HTTP_STATUS -> return evidence.httpStatus.httpFailureCategory()
            Media3ErrorKind.IO_NETWORK_CONNECTION_TIMEOUT,
            Media3ErrorKind.TIMEOUT,
            -> return evidence.timeoutCategory()

            Media3ErrorKind.IO_NETWORK_CONNECTION_FAILED -> return evidence.networkFailureCategory()
            Media3ErrorKind.DISCONNECTED -> return FailureCategory.NETWORK
            Media3ErrorKind.IO_FILE_NOT_FOUND,
            Media3ErrorKind.IO_NO_PERMISSION,
            Media3ErrorKind.IO_CLEARTEXT_NOT_PERMITTED,
            Media3ErrorKind.IO_READ_POSITION_OUT_OF_RANGE,
            Media3ErrorKind.BEHIND_LIVE_WINDOW,
            -> return FailureCategory.SOURCE_UNAVAILABLE

            Media3ErrorKind.END_OF_PLAYLIST -> return FailureCategory.STREAM_ENDED_UNEXPECTEDLY
            Media3ErrorKind.IO_UNSPECIFIED -> return evidence.unspecifiedIoFailureCategory()
            else -> Unit
        }

        when (evidence.httpStatus) {
            Media3HttpStatus.UNAUTHORIZED -> return FailureCategory.AUTHENTICATION
            Media3HttpStatus.FORBIDDEN -> return FailureCategory.AUTHORIZATION
            Media3HttpStatus.NOT_FOUND_OR_GONE,
            Media3HttpStatus.OTHER,
            -> return FailureCategory.SOURCE_UNAVAILABLE

            Media3HttpStatus.NONE -> Unit
        }

        when (evidence.causeCategory) {
            Media3CauseCategory.RESOURCE_EXHAUSTED -> {
                return FailureCategory.DEVICE_RESOURCE_OR_DECODER_LIMIT
            }

            Media3CauseCategory.DNS,
            Media3CauseCategory.NO_ROUTE,
            -> return FailureCategory.DNS_OR_ROUTE

            Media3CauseCategory.CONNECTION_REFUSED -> return FailureCategory.CONNECTION_REFUSED
            Media3CauseCategory.TIMEOUT -> return evidence.timeoutCategory()
            Media3CauseCategory.UNEXPECTED_END -> {
                return FailureCategory.STREAM_ENDED_UNEXPECTEDLY
            }

            Media3CauseCategory.PARSING,
            Media3CauseCategory.RTSP_SOURCE,
            Media3CauseCategory.RTSP_UNSUPPORTED_TRANSPORT,
            -> return FailureCategory.MEDIA_SOURCE_OR_SDP

            else -> Unit
        }

        if (evidence.origin == Media3FailureOrigin.RENDERER) {
            if (
                rendererType == Media3RendererType.VIDEO &&
                evidence.formatSupport.isUnsupportedFormat()
            ) {
                return FailureCategory.UNSUPPORTED_VIDEO_CODEC
            }
            if (
                rendererType == Media3RendererType.VIDEO &&
                evidence.formatSupport == Media3FormatSupport.EXCEEDS_CAPABILITIES
            ) {
                return FailureCategory.DEVICE_RESOURCE_OR_DECODER_LIMIT
            }
            return rendererType.rendererFailureCategory()
        }

        if (evidence.origin == Media3FailureOrigin.SOURCE) {
            return if (
                evidence.causeCategory == Media3CauseCategory.NETWORK_IO ||
                evidence.sourceProtocol != Media3SourceProtocol.UNKNOWN
            ) {
                FailureCategory.NETWORK
            } else {
                FailureCategory.SOURCE_UNAVAILABLE
            }
        }

        return FailureCategory.UNKNOWN
    }

    private fun classifyDecoderFailure(
        evidence: Media3PlaybackFailureEvidence,
        rendererType: Media3RendererType,
    ): FailureCategory {
        if (
            evidence.errorKind == Media3ErrorKind.DECODING_RESOURCES_RECLAIMED ||
            evidence.causeCategory == Media3CauseCategory.RESOURCE_EXHAUSTED
        ) {
            return FailureCategory.DEVICE_RESOURCE_OR_DECODER_LIMIT
        }

        if (rendererType == Media3RendererType.VIDEO) {
            if (
                evidence.errorKind == Media3ErrorKind.DECODING_FORMAT_UNSUPPORTED ||
                evidence.formatSupport.isUnsupportedFormat()
            ) {
                return FailureCategory.UNSUPPORTED_VIDEO_CODEC
            }
            if (
                evidence.errorKind == Media3ErrorKind.DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
                evidence.formatSupport == Media3FormatSupport.EXCEEDS_CAPABILITIES
            ) {
                return FailureCategory.DEVICE_RESOURCE_OR_DECODER_LIMIT
            }
            return FailureCategory.VIDEO_DECODER
        }

        if (rendererType == Media3RendererType.AUDIO) {
            return FailureCategory.AUDIO_DECODER
        }

        return FailureCategory.UNKNOWN
    }
}

/**
 * Converts stable public Media3 fields and allowlisted cause classes into sanitized evidence.
 * Raw messages, response bodies, headers, URLs, renderer names, and causes are never retained.
 */
object Media3PlaybackFailureEvidenceExtractor {
    fun extract(
        error: PlaybackException,
        context: Media3PlaybackFailureContext = Media3PlaybackFailureContext(),
    ): Media3PlaybackFailureEvidence = extract(
        player = null,
        error = error,
        context = context,
    )

    fun extract(
        player: Player?,
        error: PlaybackException,
        context: Media3PlaybackFailureContext = Media3PlaybackFailureContext(),
    ): Media3PlaybackFailureEvidence {
        val exoError = error as? ExoPlaybackException
        val playerRendererType = player?.singleSelectedRendererType() ?: Media3RendererType.UNKNOWN
        val rendererType = if (context.rendererType != Media3RendererType.UNKNOWN) {
            context.rendererType
        } else {
            playerRendererType
        }

        return extractStableFields(
            errorCode = error.errorCode,
            cause = error.cause,
            origin = exoError?.type.toFailureOrigin(),
            rendererType = rendererType,
            formatType = exoError?.rendererFormat?.sampleMimeType.toRendererType(),
            formatSupport = exoError?.rendererFormatSupport.toFormatSupport(),
            sourceProtocol = context.sourceProtocol,
        )
    }

    /** Testable boundary for the stable scalar fields read from Media3's public API. */
    internal fun extractStableFields(
        errorCode: Int,
        cause: Throwable?,
        origin: Media3FailureOrigin = Media3FailureOrigin.UNKNOWN,
        rendererType: Media3RendererType = Media3RendererType.UNKNOWN,
        formatType: Media3RendererType = Media3RendererType.UNKNOWN,
        formatSupport: Media3FormatSupport = Media3FormatSupport.UNKNOWN,
        sourceProtocol: Media3SourceProtocol = Media3SourceProtocol.UNKNOWN,
    ): Media3PlaybackFailureEvidence {
        val causes = summarizeCauses(cause)
        val causeProtocol = if (causes.hasRtspCause) {
            Media3SourceProtocol.RTSP
        } else {
            Media3SourceProtocol.UNKNOWN
        }

        return Media3PlaybackFailureEvidence(
            errorKind = errorCode.toErrorKind(),
            origin = origin,
            rendererType = rendererType,
            formatType = formatType,
            formatSupport = formatSupport,
            causeCategory = causes.category,
            httpStatus = causes.httpStatus,
            sourceProtocol = mergeProtocol(sourceProtocol, causeProtocol),
            rtspSetupProblem = causes.rtspSetupProblem,
        )
    }
}

fun Media3PlaybackFailureClassifier.classify(
    error: PlaybackException,
    context: Media3PlaybackFailureContext = Media3PlaybackFailureContext(),
): ClassifiedPlaybackFailure = classify(
    Media3PlaybackFailureEvidenceExtractor.extract(error, context),
)

fun Media3PlaybackFailureClassifier.classify(
    player: Player,
    context: Media3PlaybackFailureContext = Media3PlaybackFailureContext(),
): ClassifiedPlaybackFailure? {
    val error = player.playerError ?: return null
    return classify(
        Media3PlaybackFailureEvidenceExtractor.extract(player, error, context),
    )
}

fun Media3PlaybackFailureClassifier.classify(
    player: Player,
    error: PlaybackException,
    context: Media3PlaybackFailureContext = Media3PlaybackFailureContext(),
): ClassifiedPlaybackFailure = classify(
    Media3PlaybackFailureEvidenceExtractor.extract(player, error, context),
)

private data class CauseSummary(
    val category: Media3CauseCategory,
    val httpStatus: Media3HttpStatus,
    val hasRtspCause: Boolean,
    val rtspSetupProblem: Media3RtspSetupProblem,
)

private fun summarizeCauses(firstCause: Throwable?): CauseSummary {
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current = firstCause
    var depth = 0
    var category = Media3CauseCategory.NONE
    var httpStatus = Media3HttpStatus.NONE
    var hasRtspCause = false
    var setupProblem = Media3RtspSetupProblem.NONE
    var conflictingSetup = false

    while (current != null && depth < MAX_CAUSE_DEPTH && visited.add(current)) {
        val setup = current.missingRtspSetup()
        if (setup != Media3RtspSetupProblem.NONE) {
            if (setupProblem != Media3RtspSetupProblem.NONE && setupProblem != setup) conflictingSetup = true
            setupProblem = setup
            hasRtspCause = true
        }
        val candidate = if (setup != Media3RtspSetupProblem.NONE) Media3CauseCategory.PARSING else when (current) {
            is NativeVideoSizeUnsupportedException -> Media3CauseCategory.NATIVE_VIDEO_SIZE_UNSUPPORTED
            is UnknownHostException -> Media3CauseCategory.DNS
            is NoRouteToHostException -> Media3CauseCategory.NO_ROUTE
            is ConnectException,
            is PortUnreachableException,
            -> Media3CauseCategory.CONNECTION_REFUSED

            is SocketTimeoutException -> Media3CauseCategory.TIMEOUT
            is EOFException -> Media3CauseCategory.UNEXPECTED_END
            is RtspMediaSource.RtspUdpUnsupportedTransportException -> {
                Media3CauseCategory.RTSP_UNSUPPORTED_TRANSPORT
            }

            is RtspMediaSource.RtspPlaybackException -> Media3CauseCategory.RTSP_SOURCE
            is ParserException -> Media3CauseCategory.PARSING
            is OutOfMemoryError -> Media3CauseCategory.RESOURCE_EXHAUSTED
            is SocketException,
            is IOException,
            -> Media3CauseCategory.NETWORK_IO

            else -> Media3CauseCategory.OTHER
        }

        if (candidate.priority > category.priority) category = candidate
        if (current is RtspMediaSource.RtspPlaybackException) hasRtspCause = true
        if (current is HttpDataSource.InvalidResponseCodeException) {
            httpStatus = current.responseCode.toHttpStatus()
        }
        current = current.cause
        depth += 1
    }

    return CauseSummary(
        category = category,
        httpStatus = httpStatus,
        hasRtspCause = hasRtspCause,
        rtspSetupProblem = if (conflictingSetup || current != null) Media3RtspSetupProblem.NONE else setupProblem,
    )
}

/**
 * Issue #21 (reported by megabitus98): Media3 1.11 requires out-of-band setup for HEVC.
 * Match only pinned parser checks, not arbitrary exception text mentioning these words.
 * fmtp is also required by other codecs, so its user-facing message must remain codec-neutral.
 */
private fun Throwable.missingRtspSetup(): Media3RtspSetupProblem {
    if (this !is IllegalArgumentException) return Media3RtspSetupProblem.NONE
    val safeLengthMessage = message?.takeIf { it.length <= MAX_SETUP_MESSAGE_LENGTH }
        ?: return Media3RtspSetupProblem.NONE
    val detail = when (safeLengthMessage) {
        "missing attribute fmtp" -> Media3RtspSetupProblem.MISSING_FMTP
        "missing sprop-vps parameter" -> Media3RtspSetupProblem.MISSING_VPS
        "missing sprop-sps parameter" -> Media3RtspSetupProblem.MISSING_SPS
        "missing sprop-pps parameter" -> Media3RtspSetupProblem.MISSING_PPS
        else -> return Media3RtspSetupProblem.NONE
    }
    val method = if (detail == Media3RtspSetupProblem.MISSING_FMTP) "generatePayloadFormat"
        else "processH265FmtpAttribute"
    return if (stackTrace.take(MAX_PARSER_FRAMES).any {
            it.className == "androidx.media3.exoplayer.rtsp.RtspMediaTrack" && it.methodName == method
        }) detail else Media3RtspSetupProblem.NONE
}

private fun Media3RtspSetupProblem.diagnosticCode(): PlaybackDiagnosticCode? = when (this) {
    Media3RtspSetupProblem.NONE -> null
    Media3RtspSetupProblem.MISSING_FMTP -> PlaybackDiagnosticCode.RTSP_MISSING_FMTP
    Media3RtspSetupProblem.MISSING_VPS -> PlaybackDiagnosticCode.RTSP_MISSING_VPS
    Media3RtspSetupProblem.MISSING_SPS -> PlaybackDiagnosticCode.RTSP_MISSING_SPS
    Media3RtspSetupProblem.MISSING_PPS -> PlaybackDiagnosticCode.RTSP_MISSING_PPS
}

private fun Media3PlaybackFailureEvidence.resolvedRendererType(): Media3RendererType {
    val rendererKnown = rendererType == Media3RendererType.AUDIO ||
        rendererType == Media3RendererType.VIDEO
    val formatKnown = formatType == Media3RendererType.AUDIO ||
        formatType == Media3RendererType.VIDEO
    if (rendererKnown && formatKnown && rendererType != formatType) {
        return Media3RendererType.UNKNOWN
    }
    return when {
        formatKnown -> formatType
        rendererKnown -> rendererType
        rendererType == Media3RendererType.OTHER || formatType == Media3RendererType.OTHER -> {
            Media3RendererType.OTHER
        }

        else -> Media3RendererType.UNKNOWN
    }
}

private fun Media3PlaybackFailureEvidence.timeoutCategory(): FailureCategory =
    if (sourceProtocol == Media3SourceProtocol.RTSP) {
        FailureCategory.RTSP_TIMEOUT
    } else {
        FailureCategory.SOURCE_TIMEOUT
    }

private fun Media3PlaybackFailureEvidence.networkFailureCategory(): FailureCategory =
    when (causeCategory) {
        Media3CauseCategory.DNS,
        Media3CauseCategory.NO_ROUTE,
        -> FailureCategory.DNS_OR_ROUTE

        Media3CauseCategory.CONNECTION_REFUSED -> FailureCategory.CONNECTION_REFUSED
        Media3CauseCategory.TIMEOUT -> timeoutCategory()
        Media3CauseCategory.UNEXPECTED_END -> FailureCategory.STREAM_ENDED_UNEXPECTEDLY
        Media3CauseCategory.PARSING,
        Media3CauseCategory.RTSP_SOURCE,
        Media3CauseCategory.RTSP_UNSUPPORTED_TRANSPORT,
        -> FailureCategory.MEDIA_SOURCE_OR_SDP

        else -> FailureCategory.NETWORK
    }

private fun Media3PlaybackFailureEvidence.unspecifiedIoFailureCategory(): FailureCategory =
    when (causeCategory) {
        Media3CauseCategory.DNS,
        Media3CauseCategory.NO_ROUTE,
        -> FailureCategory.DNS_OR_ROUTE

        Media3CauseCategory.CONNECTION_REFUSED -> FailureCategory.CONNECTION_REFUSED
        Media3CauseCategory.TIMEOUT -> timeoutCategory()
        Media3CauseCategory.UNEXPECTED_END -> FailureCategory.STREAM_ENDED_UNEXPECTEDLY
        Media3CauseCategory.PARSING,
        Media3CauseCategory.RTSP_SOURCE,
        Media3CauseCategory.RTSP_UNSUPPORTED_TRANSPORT,
        -> FailureCategory.MEDIA_SOURCE_OR_SDP

        Media3CauseCategory.RESOURCE_EXHAUSTED -> {
            FailureCategory.DEVICE_RESOURCE_OR_DECODER_LIMIT
        }

        Media3CauseCategory.NATIVE_VIDEO_SIZE_UNSUPPORTED -> FailureCategory.UNSUPPORTED_VIDEO_SIZE

        Media3CauseCategory.NETWORK_IO -> if (sourceProtocol != Media3SourceProtocol.UNKNOWN) {
            FailureCategory.NETWORK
        } else {
            FailureCategory.UNKNOWN
        }

        Media3CauseCategory.NONE,
        Media3CauseCategory.OTHER,
        -> FailureCategory.UNKNOWN
    }

private fun Media3HttpStatus.httpFailureCategory(): FailureCategory = when (this) {
    Media3HttpStatus.UNAUTHORIZED -> FailureCategory.AUTHENTICATION
    Media3HttpStatus.FORBIDDEN -> FailureCategory.AUTHORIZATION
    Media3HttpStatus.NOT_FOUND_OR_GONE,
    Media3HttpStatus.OTHER,
    Media3HttpStatus.NONE,
    -> FailureCategory.SOURCE_UNAVAILABLE
}

private fun Media3RendererType.rendererFailureCategory(): FailureCategory = when (this) {
    Media3RendererType.AUDIO -> FailureCategory.AUDIO_RENDERER
    Media3RendererType.VIDEO -> FailureCategory.VIDEO_RENDERER
    Media3RendererType.OTHER,
    Media3RendererType.UNKNOWN,
    -> FailureCategory.UNKNOWN
}

private fun FailureCategory.failurePhase(origin: Media3FailureOrigin): FailurePhase = when (this) {
    FailureCategory.AUDIO_DECODER,
    FailureCategory.VIDEO_DECODER,
    FailureCategory.UNSUPPORTED_VIDEO_CODEC,
    FailureCategory.UNSUPPORTED_VIDEO_SIZE,
    FailureCategory.DEVICE_RESOURCE_OR_DECODER_LIMIT,
    -> FailurePhase.DECODER

    FailureCategory.AUDIO_RENDERER,
    FailureCategory.VIDEO_RENDERER,
    -> FailurePhase.RENDERER

    FailureCategory.STREAM_ENDED_UNEXPECTEDLY -> FailurePhase.STABLE_DWELL_PROBE
    FailureCategory.UNKNOWN -> when (origin) {
        Media3FailureOrigin.SOURCE -> FailurePhase.SOURCE
        Media3FailureOrigin.RENDERER -> FailurePhase.RENDERER
        else -> FailurePhase.PREPARE
    }

    else -> FailurePhase.SOURCE
}

private fun FailureCategory.diagnosticCode(): PlaybackDiagnosticCode = when (this) {
    FailureCategory.UNSUPPORTED_VIDEO_SIZE -> PlaybackDiagnosticCode.VIDEO_SIZE_UNSUPPORTED
    FailureCategory.AUDIO_DECODER,
    FailureCategory.AUDIO_RENDERER,
    -> PlaybackDiagnosticCode.AUDIO_DECODER

    FailureCategory.VIDEO_DECODER,
    FailureCategory.VIDEO_RENDERER,
    FailureCategory.UNSUPPORTED_VIDEO_CODEC,
    -> PlaybackDiagnosticCode.VIDEO_DECODER

    FailureCategory.RTSP_TIMEOUT -> PlaybackDiagnosticCode.RTSP_TIMEOUT
    FailureCategory.AUTHENTICATION -> PlaybackDiagnosticCode.SESSION_EXPIRED
    FailureCategory.AUTHORIZATION -> PlaybackDiagnosticCode.CAMERA_DENIED
    FailureCategory.DNS_OR_ROUTE,
    FailureCategory.CONNECTION_REFUSED,
    FailureCategory.SOURCE_TIMEOUT,
    FailureCategory.SOURCE_UNAVAILABLE,
    FailureCategory.STREAM_ENDED_UNEXPECTEDLY,
    FailureCategory.NETWORK,
    -> PlaybackDiagnosticCode.RETRYABLE

    else -> PlaybackDiagnosticCode.UNCLASSIFIED
}

private fun Int.toErrorKind(): Media3ErrorKind = when (this) {
    PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED -> Media3ErrorKind.AUTHENTICATION_EXPIRED
    PlaybackException.ERROR_CODE_PERMISSION_DENIED -> Media3ErrorKind.PERMISSION_DENIED
    PlaybackException.ERROR_CODE_NOT_SUPPORTED -> Media3ErrorKind.NOT_SUPPORTED
    PlaybackException.ERROR_CODE_DISCONNECTED -> Media3ErrorKind.DISCONNECTED
    PlaybackException.ERROR_CODE_TIMEOUT -> Media3ErrorKind.TIMEOUT
    PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> Media3ErrorKind.BEHIND_LIVE_WINDOW
    PlaybackException.ERROR_CODE_END_OF_PLAYLIST -> Media3ErrorKind.END_OF_PLAYLIST
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> Media3ErrorKind.IO_UNSPECIFIED
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> {
        Media3ErrorKind.IO_NETWORK_CONNECTION_FAILED
    }

    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> {
        Media3ErrorKind.IO_NETWORK_CONNECTION_TIMEOUT
    }

    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> {
        Media3ErrorKind.IO_INVALID_HTTP_CONTENT_TYPE
    }

    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> Media3ErrorKind.IO_BAD_HTTP_STATUS
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> Media3ErrorKind.IO_FILE_NOT_FOUND
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> Media3ErrorKind.IO_NO_PERMISSION
    PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> {
        Media3ErrorKind.IO_CLEARTEXT_NOT_PERMITTED
    }

    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> {
        Media3ErrorKind.IO_READ_POSITION_OUT_OF_RANGE
    }

    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    -> Media3ErrorKind.PARSING_MALFORMED

    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    -> Media3ErrorKind.PARSING_UNSUPPORTED

    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> Media3ErrorKind.DECODER_INIT_FAILED
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> Media3ErrorKind.DECODER_QUERY_FAILED
    PlaybackException.ERROR_CODE_DECODING_FAILED -> Media3ErrorKind.DECODING_FAILED
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> {
        Media3ErrorKind.DECODING_FORMAT_EXCEEDS_CAPABILITIES
    }

    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> {
        Media3ErrorKind.DECODING_FORMAT_UNSUPPORTED
    }

    PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED -> {
        Media3ErrorKind.DECODING_RESOURCES_RECLAIMED
    }

    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
    -> Media3ErrorKind.AUDIO_RENDERER_FAILED

    PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
    PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
    -> Media3ErrorKind.VIDEO_RENDERER_FAILED

    else -> Media3ErrorKind.OTHER
}

private fun Int?.toFailureOrigin(): Media3FailureOrigin = when (this) {
    ExoPlaybackException.TYPE_SOURCE -> Media3FailureOrigin.SOURCE
    ExoPlaybackException.TYPE_RENDERER -> Media3FailureOrigin.RENDERER
    ExoPlaybackException.TYPE_UNEXPECTED -> Media3FailureOrigin.UNEXPECTED
    ExoPlaybackException.TYPE_REMOTE -> Media3FailureOrigin.REMOTE
    else -> Media3FailureOrigin.UNKNOWN
}

private fun String?.toRendererType(): Media3RendererType = when {
    this == null -> Media3RendererType.UNKNOWN
    MimeTypes.isAudio(this) -> Media3RendererType.AUDIO
    MimeTypes.isVideo(this) -> Media3RendererType.VIDEO
    else -> Media3RendererType.OTHER
}

private fun Int?.toFormatSupport(): Media3FormatSupport = when (this) {
    C.FORMAT_HANDLED -> Media3FormatSupport.HANDLED
    C.FORMAT_EXCEEDS_CAPABILITIES -> Media3FormatSupport.EXCEEDS_CAPABILITIES
    C.FORMAT_UNSUPPORTED_DRM -> Media3FormatSupport.UNSUPPORTED_DRM
    C.FORMAT_UNSUPPORTED_SUBTYPE -> Media3FormatSupport.UNSUPPORTED_SUBTYPE
    C.FORMAT_UNSUPPORTED_TYPE -> Media3FormatSupport.UNSUPPORTED_TYPE
    else -> Media3FormatSupport.UNKNOWN
}

private fun Media3FormatSupport.isUnsupportedFormat(): Boolean =
    this == Media3FormatSupport.UNSUPPORTED_SUBTYPE ||
        this == Media3FormatSupport.UNSUPPORTED_TYPE

private fun Player.singleSelectedRendererType(): Media3RendererType {
    val audioSelected = currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO)
    val videoSelected = currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
    return when {
        audioSelected && !videoSelected -> Media3RendererType.AUDIO
        videoSelected && !audioSelected -> Media3RendererType.VIDEO
        else -> Media3RendererType.UNKNOWN
    }
}

private fun mergeProtocol(
    contextual: Media3SourceProtocol,
    caused: Media3SourceProtocol,
): Media3SourceProtocol = when {
    contextual == Media3SourceProtocol.UNKNOWN -> caused
    caused == Media3SourceProtocol.UNKNOWN -> contextual
    contextual == caused -> contextual
    else -> Media3SourceProtocol.UNKNOWN
}

private fun Int.toHttpStatus(): Media3HttpStatus = when (this) {
    401 -> Media3HttpStatus.UNAUTHORIZED
    403 -> Media3HttpStatus.FORBIDDEN
    404,
    410,
    -> Media3HttpStatus.NOT_FOUND_OR_GONE

    else -> Media3HttpStatus.OTHER
}

private val Media3CauseCategory.priority: Int
    get() = when (this) {
        Media3CauseCategory.NATIVE_VIDEO_SIZE_UNSUPPORTED -> 11
        Media3CauseCategory.RESOURCE_EXHAUSTED -> 10
        Media3CauseCategory.DNS,
        Media3CauseCategory.NO_ROUTE,
        Media3CauseCategory.CONNECTION_REFUSED,
        -> 9

        Media3CauseCategory.TIMEOUT -> 8
        Media3CauseCategory.UNEXPECTED_END -> 7
        Media3CauseCategory.PARSING -> 6
        Media3CauseCategory.RTSP_UNSUPPORTED_TRANSPORT -> 5
        Media3CauseCategory.RTSP_SOURCE -> 4
        Media3CauseCategory.NETWORK_IO -> 3
        Media3CauseCategory.OTHER -> 2
        Media3CauseCategory.NONE -> 1
    }

private val DECODER_ERROR_KINDS = setOf(
    Media3ErrorKind.DECODER_INIT_FAILED,
    Media3ErrorKind.DECODER_QUERY_FAILED,
    Media3ErrorKind.DECODING_FAILED,
    Media3ErrorKind.DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    Media3ErrorKind.DECODING_FORMAT_UNSUPPORTED,
    Media3ErrorKind.DECODING_RESOURCES_RECLAIMED,
)

private const val MAX_CAUSE_DEPTH = 12
private const val MAX_PARSER_FRAMES = 24
private const val MAX_SETUP_MESSAGE_LENGTH = 32
