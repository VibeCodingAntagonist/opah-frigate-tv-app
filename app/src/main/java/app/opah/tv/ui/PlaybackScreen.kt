package app.opah.tv.ui

import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.opah.tv.OpahApplication
import app.opah.tv.BuildConfig
import app.opah.tv.PictureInPictureRequest
import app.opah.tv.R
import app.opah.tv.pipAspectRatio
import app.opah.tv.shouldOfferLivePictureInPicture
import app.opah.tv.playback.LivePlaybackOptions
import app.opah.tv.playback.PlaybackKind
import app.opah.tv.playback.PlaybackRequest
import app.opah.tv.playback.RecordedPlayerFactory
import app.opah.tv.playback.compatibility.PlaybackCompatibilityState
import app.opah.tv.playback.compatibility.PlaybackResult
import app.opah.tv.playback.compatibility.FailureCategory
import app.opah.tv.playback.compatibility.isMissingRtspSetup
import app.opah.tv.playback.compatibility.PlaybackDiagnosticCode
import app.opah.tv.playback.compatibility.AudioMode
import app.opah.tv.playback.compatibility.DecoderMode
import app.opah.tv.playback.compatibility.PlaybackCandidate
import app.opah.tv.playback.compatibility.TransportMode
import app.opah.tv.playback.media3.PlayerViewMedia3VideoOutputTarget
import app.opah.tv.playback.media3.SingleLivePlaybackRequestId
import app.opah.tv.playback.media3.DefaultMedia3PlaybackFailureClassifier
import app.opah.tv.playback.media3.Media3PlaybackFailureEvidenceExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

internal data class PlaybackTelemetry(
    val state: String = "Preparing",
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = true,
    val ended: Boolean = false,
    val videoDecoder: String? = null,
    val audioDecoder: String? = null,
    val videoFormat: String? = null,
    val audioFormat: String? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val firstFrameMs: Long? = null,
    val droppedFrames: Int = 0,
    val positionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val seekable: Boolean = false,
    val safeError: String? = null,
)

internal data class PlaybackControlAvailability(
    val previous: Boolean,
    val seek: Boolean,
    val next: Boolean,
    val earlier: Boolean,
)

internal fun playbackControlAvailability(
    kind: PlaybackKind,
    hasPrevious: Boolean,
    hasNext: Boolean,
    hasEarlier: Boolean,
): PlaybackControlAvailability = PlaybackControlAvailability(
    previous = kind == PlaybackKind.LIVE && hasPrevious,
    seek = kind == PlaybackKind.RECORDED,
    next = kind == PlaybackKind.LIVE && hasNext,
    earlier = kind == PlaybackKind.LIVE && hasEarlier,
)

internal fun recordedMediaMimeType(uri: String): String? = when {
    uri.substringBefore('?').lowercase().endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
    uri.substringBefore('?').lowercase().endsWith(".mp4") -> MimeTypes.VIDEO_MP4
    else -> null
}

internal fun videoResizeMode(stretched: Boolean): Int = if (stretched) {
    AspectRatioFrameLayout.RESIZE_MODE_FILL
} else {
    AspectRatioFrameLayout.RESIZE_MODE_FIT
}

internal enum class PlaybackBackAction { HIDE_OVERLAY, EXIT }

internal enum class DecoderRecoveryAction { LOWER_QUALITY, SOFTWARE_DECODER, NONE }

internal fun decoderRecoveryAction(
    kind: PlaybackKind,
    errorCode: Int,
    fallbackUri: String?,
    lowerQualityActive: Boolean,
    softwareDecoderActive: Boolean,
): DecoderRecoveryAction {
    if (errorCode !in DECODER_PLAYBACK_ERROR_CODES) return DecoderRecoveryAction.NONE
    if (kind == PlaybackKind.LIVE && !lowerQualityActive && !fallbackUri.isNullOrBlank()) {
        return DecoderRecoveryAction.LOWER_QUALITY
    }
    return if (!softwareDecoderActive) {
        DecoderRecoveryAction.SOFTWARE_DECODER
    } else {
        DecoderRecoveryAction.NONE
    }
}

internal fun playbackBackAction(
    controlsVisible: Boolean,
    diagnosticsVisible: Boolean,
    pictureInPictureActive: Boolean,
): PlaybackBackAction = when {
    pictureInPictureActive -> PlaybackBackAction.EXIT
    controlsVisible || diagnosticsVisible -> PlaybackBackAction.HIDE_OVERLAY
    else -> PlaybackBackAction.EXIT
}

internal fun playbackContextLabel(
    kind: PlaybackKind,
    startupFallbackActive: Boolean,
): String = when (kind) {
    PlaybackKind.RECORDED -> "Recording"
    PlaybackKind.LIVE -> if (startupFallbackActive) "Live • Lower quality" else "Live"
}

internal fun playbackMuteControlLabel(muted: Boolean, videoOnly: Boolean): String = when {
    videoOnly -> "Video only is on"
    muted -> "Turn on audio"
    else -> "Mute audio"
}

internal fun playbackAlternateAudioControlLabel(videoOnly: Boolean): String =
    if (videoOnly) "Use video and audio" else "Video only"

internal fun refreshedPlaybackAudioCaption(
    currentLabel: String,
    muted: Boolean,
    videoOnly: Boolean,
): String = when (currentLabel) {
    "Mute audio", "Turn on audio", "Video only is on" -> playbackMuteControlLabel(muted, videoOnly)
    "Video only", "Use video and audio" -> playbackAlternateAudioControlLabel(videoOnly)
    else -> currentLabel
}

@UnstableApi
private class PlayerSession(
    val player: ExoPlayer,
    val startedAtMs: Long,
    val videoOnly: Boolean,
    private val releaseAction: () -> Unit,
) {
    fun release() = releaseAction()
}

@UnstableApi
@Composable
fun PlaybackScreen(
    request: PlaybackRequest,
    onBack: () -> Unit,
    onSessionExpired: (String) -> Unit,
    preferRtpTcp: Boolean = true,
    startMuted: Boolean = false,
    diagnosticsAvailable: Boolean = true,
    pictureInPictureAvailable: Boolean = false,
    pictureInPictureActive: Boolean = false,
    onEnterPictureInPicture: (PictureInPictureRequest) -> Boolean = { false },
    previousCameraName: String? = null,
    onPrevious: (() -> Unit)? = null,
    nextCameraName: String? = null,
    onNext: (() -> Unit)? = null,
    onOpenEarlier: (() -> Unit)? = null,
    onReturnToLive: (() -> Unit)? = null,
    snapshotCapturing: Boolean = false,
    onInstantSnapshot: (() -> Unit)? = null,
    onDemandRecordingActive: Boolean = false,
    onDemandRecordingBusy: Boolean = false,
    onToggleOnDemandRecording: (() -> Unit)? = null,
    liveActionMessage: String? = null,
    onPlaybackCompleted: (() -> Unit)? = null,
    activityReviewed: Boolean? = null,
    markingActivityReviewed: Boolean = false,
    onMarkActivityReviewed: (() -> Unit)? = null,
    stretchVideo: Boolean = false,
    onToggleStretchVideo: (() -> Unit)? = null,
    savingActivityRecording: Boolean = false,
    activityRecordingSaved: Boolean = false,
    activityRecordingMessage: String? = null,
    activityQueueProgress: String? = null,
    onCompatibilityTestStatus: (String) -> Unit = {},
    onSaveActivityRecording: (() -> Unit)? = null,
    nextActivityLabel: String? = null,
    nextActivityEnabled: Boolean = false,
    onNextActivity: (() -> Unit)? = null,
) {
    if (BuildConfig.DOCUMENTATION_MODE && request.uri.startsWith(DOCUMENTATION_URI_PREFIX)) {
        DocumentationPlaybackScreen(
            request = request,
            onBack = onBack,
            startMuted = startMuted,
            diagnosticsAvailable = diagnosticsAvailable,
            pictureInPictureAvailable = pictureInPictureAvailable,
            pictureInPictureActive = pictureInPictureActive,
            onEnterPictureInPicture = onEnterPictureInPicture,
            previousCameraName = previousCameraName,
            onPrevious = onPrevious,
            nextCameraName = nextCameraName,
            onNext = onNext,
            onOpenEarlier = onOpenEarlier,
            onReturnToLive = onReturnToLive,
            snapshotCapturing = snapshotCapturing,
            onInstantSnapshot = onInstantSnapshot,
            onDemandRecordingActive = onDemandRecordingActive,
            onDemandRecordingBusy = onDemandRecordingBusy,
            onToggleOnDemandRecording = onToggleOnDemandRecording,
            liveActionMessage = liveActionMessage,
            onPlaybackCompleted = onPlaybackCompleted,
            activityReviewed = activityReviewed,
            markingActivityReviewed = markingActivityReviewed,
            onMarkActivityReviewed = onMarkActivityReviewed,
            stretchVideo = stretchVideo,
            onToggleStretchVideo = onToggleStretchVideo,
            savingActivityRecording = savingActivityRecording,
            activityRecordingSaved = activityRecordingSaved,
            activityRecordingMessage = activityRecordingMessage,
            activityQueueProgress = activityQueueProgress,
            onSaveActivityRecording = onSaveActivityRecording,
            nextActivityLabel = nextActivityLabel,
            nextActivityEnabled = nextActivityEnabled,
            onNextActivity = onNextActivity,
        )
        return
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val application = context.applicationContext as OpahApplication
    val liveCompatibilityRequestId = request.liveCompatibilityRequestId
        ?.let(::SingleLivePlaybackRequestId)
    val compatibilityLive = request.kind == PlaybackKind.LIVE && liveCompatibilityRequestId != null
    val playbackStateKey = liveCompatibilityRequestId?.value ?: request.uri
    var startupFallbackActive by rememberSaveable(playbackStateKey) { mutableStateOf(false) }
    var softwareDecoderActive by rememberSaveable(playbackStateKey) { mutableStateOf(false) }
    val activeUri = if (startupFallbackActive) request.startupFallbackUri ?: request.uri else request.uri
    val activeDetail = if (request.compatibilityTest) {
        "Testing camera compatibility"
    } else {
        playbackContextLabel(request.kind, startupFallbackActive)
    }
    var forceTcp by rememberSaveable(playbackStateKey, request.kind) {
        mutableStateOf(request.kind == PlaybackKind.LIVE && preferRtpTcp)
    }
    var automaticTcpFallback by rememberSaveable(playbackStateKey) { mutableStateOf(false) }
    var muted by rememberSaveable(playbackStateKey) { mutableStateOf(startMuted) }
    var videoOnly by rememberSaveable(playbackStateKey) {
        mutableStateOf(request.compatibilityTestVideoOnly)
    }
    var diagnosticsVisible by rememberSaveable(playbackStateKey) { mutableStateOf(false) }
    var controlsVisible by rememberSaveable(playbackStateKey) { mutableStateOf(true) }
    var controlsInteractionToken by remember { mutableIntStateOf(0) }
    var overlayRenderPass by remember { mutableIntStateOf(0) }
    var consumeRevealKeyUp by remember { mutableStateOf(false) }
    val hiddenControlsFocusRequester = remember(playbackStateKey) { FocusRequester() }
    val playButtonFocusRequester = remember(playbackStateKey) { FocusRequester() }
    var retryToken by remember { mutableIntStateOf(0) }
    var automaticRetryAttempts by rememberSaveable(playbackStateKey) { mutableIntStateOf(0) }
    var pendingAutomaticRetryMs by remember(playbackStateKey) { mutableStateOf<Long?>(null) }
    var pipRequested by remember(playbackStateKey) { mutableStateOf(false) }
    var pipError by remember(playbackStateKey) { mutableStateOf<String?>(null) }
    var compatibilityMessage by remember(playbackStateKey) { mutableStateOf<String?>(null) }
    var compatibilityTestComplete by remember(playbackStateKey) { mutableStateOf(false) }
    var recordedPositionMs by rememberSaveable(playbackStateKey) { mutableLongStateOf(0L) }
    var resumeWhenStarted by rememberSaveable(playbackStateKey) { mutableStateOf(true) }
    var session by remember(playbackStateKey) { mutableStateOf<PlayerSession?>(null) }
    var playerView by remember(playbackStateKey) { mutableStateOf<PlayerView?>(null) }
    var completionReported by remember(playbackStateKey) { mutableStateOf(false) }
    val lastVideoFrameAtMs = remember(session) { AtomicLong(0L) }

    DisposableEffect(
        lifecycleOwner,
        activeUri,
        request.kind,
        forceTcp,
        videoOnly,
        softwareDecoderActive,
        retryToken,
        compatibilityLive,
    ) {
        if (compatibilityLive) return@DisposableEffect onDispose { }

        fun startPlayback() {
            if (session != null) return
            session = createPlayerSession(
                context = context,
                application = application,
                request = request.copy(uri = activeUri),
                forceTcp = forceTcp,
                videoOnly = videoOnly,
                preferSoftwareVideoDecoder = softwareDecoderActive,
                recordedPositionMs = recordedPositionMs,
                playWhenReady = resumeWhenStarted,
            )
        }

        fun releasePlayback() {
            session?.let { activeSession ->
                if (request.kind == PlaybackKind.RECORDED) {
                    recordedPositionMs = activeSession.player.currentPosition.coerceAtLeast(0L)
                    resumeWhenStarted = activeSession.player.playWhenReady
                }
                activeSession.release()
            }
            session = null
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> startPlayback()
                Lifecycle.Event.ON_STOP -> releasePlayback()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startPlayback()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            releasePlayback()
        }
    }

    var telemetry by remember(session) {
        mutableStateOf(PlaybackTelemetry(playWhenReady = session?.player?.playWhenReady ?: resumeWhenStarted))
    }

    LaunchedEffect(
        lifecycleOwner,
        liveCompatibilityRequestId,
        playerView,
        videoOnly,
    ) {
        val requestId = liveCompatibilityRequestId ?: return@LaunchedEffect
        val outputView = playerView ?: return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            compatibilityMessage = null
            val target = PlayerViewMedia3VideoOutputTarget(
                playerView = outputView,
                onAttached = { player ->
                    session = PlayerSession(
                        player = player,
                        startedAtMs = SystemClock.elapsedRealtime(),
                        videoOnly = videoOnly,
                        releaseAction = { },
                    )
                },
                onDetached = { player ->
                    if (session?.player === player) session = null
                },
            )
            val liveSession = try {
                application.container.singleLivePlaybackCoordinator.open(
                    requestId = requestId,
                    output = target,
                    videoOnly = videoOnly,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                telemetry = telemetry.copy(
                    state = "Playback failed",
                    safeError = "Live video could not start. Go back and try the camera again.",
                )
                controlsVisible = true
                return@repeatOnLifecycle
            }
            try {
                liveSession.snapshots.collect { snapshot ->
                    liveSession.downgradeExplanation(snapshot)?.let { explanation ->
                        compatibilityMessage = explanation
                    }
                    when (val state = snapshot.state) {
                        is PlaybackCompatibilityState.Attempting -> {
                            telemetry = telemetry.copy(state = "Finding a compatible stream", safeError = null)
                        }
                        is PlaybackCompatibilityState.Recovering -> {
                            telemetry = telemetry.copy(state = "Reconnecting", safeError = null)
                        }
                        is PlaybackCompatibilityState.Verified,
                        is PlaybackCompatibilityState.UnpersistedLive,
                        is PlaybackCompatibilityState.Persisting,
                        -> telemetry = telemetry.copy(state = "Ready", safeError = null)

                        is PlaybackCompatibilityState.Finished -> when (state.result) {
                            is PlaybackResult.Cancelled -> Unit
                            is PlaybackResult.VerifiedLive,
                            is PlaybackResult.UnpersistedLive,
                            -> telemetry = telemetry.copy(state = "Ready", safeError = null)

                            else -> {
                                telemetry = telemetry.copy(
                                    state = "Playback failed",
                                    safeError = "Live video is unavailable. Check the camera, server, and network.",
                                )
                                controlsVisible = true
                            }
                        }

                        is PlaybackCompatibilityState.Idle -> Unit
                        is PlaybackCompatibilityState.Releasing -> Unit
                        is PlaybackCompatibilityState.ReleasedAwaitingPersistence -> Unit
                        is PlaybackCompatibilityState.PresentingSnapshot -> Unit
                    }
                    if (request.compatibilityTest) {
                        compatibilityTestStatus(request.title, snapshot.state)?.let { message ->
                            compatibilityMessage = message
                            onCompatibilityTestStatus(message)
                        }
                        compatibilityTestComplete = when (val state = snapshot.state) {
                            is PlaybackCompatibilityState.Verified,
                            is PlaybackCompatibilityState.UnpersistedLive,
                            -> true

                            is PlaybackCompatibilityState.Finished ->
                                state.result !is PlaybackResult.Cancelled

                            else -> false
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) { liveSession.close() }
                session = null
            }
        }
    }

    LaunchedEffect(telemetry.ended, telemetry.playWhenReady, onReturnToLive) {
        if (telemetry.ended && telemetry.playWhenReady && onReturnToLive != null) {
            delay(500)
            onReturnToLive()
        }
    }
    LaunchedEffect(request.compatibilityTest, compatibilityTestComplete) {
        if (request.compatibilityTest && compatibilityTestComplete) {
            delay(COMPATIBILITY_TEST_RESULT_DISPLAY_MILLIS)
            onBack()
        }
    }
    LaunchedEffect(telemetry.ended, onPlaybackCompleted) {
        if (telemetry.ended && !completionReported && onPlaybackCompleted != null) {
            completionReported = true
            onPlaybackCompleted()
        }
    }

    LaunchedEffect(session, activeUri, startupFallbackActive, request.kind) {
        if (compatibilityLive) return@LaunchedEffect
        val watchedSession = session ?: return@LaunchedEffect
        if (startupFallbackActive || request.startupFallbackUri == null) return@LaunchedEffect
        delay(LIVE_FIRST_FRAME_FALLBACK_MS)
        if (
            session === watchedSession &&
            shouldUseStartupFallback(
                kind = request.kind,
                fallbackUri = request.startupFallbackUri,
                firstFrameMs = telemetry.firstFrameMs,
                safeError = telemetry.safeError,
            )
        ) {
            startupFallbackActive = true
        }
    }

    LaunchedEffect(session, muted) {
        session?.player?.volume = if (muted) 0f else 1f
    }

    LaunchedEffect(pendingAutomaticRetryMs, automaticRetryAttempts, activeUri) {
        if (compatibilityLive) return@LaunchedEffect
        val retryDelayMs = pendingAutomaticRetryMs ?: return@LaunchedEffect
        delay(retryDelayMs)
        pendingAutomaticRetryMs = null
        retryToken += 1
    }

    LaunchedEffect(session, telemetry.firstFrameMs, telemetry.safeError, request.kind) {
        if (compatibilityLive) return@LaunchedEffect
        val stableSession = session ?: return@LaunchedEffect
        if (
            request.kind != PlaybackKind.LIVE ||
            telemetry.firstFrameMs == null ||
            telemetry.safeError != null ||
            automaticRetryAttempts == 0
        ) return@LaunchedEffect
        delay(LIVE_RETRY_RESET_AFTER_MS)
        if (session === stableSession && telemetry.safeError == null) automaticRetryAttempts = 0
    }

    LaunchedEffect(session, request.kind, telemetry.playWhenReady, telemetry.safeError) {
        if (compatibilityLive) return@LaunchedEffect
        val watchedSession = session ?: return@LaunchedEffect
        if (
            request.kind != PlaybackKind.LIVE ||
            !telemetry.playWhenReady ||
            telemetry.safeError != null
        ) return@LaunchedEffect
        val watchStartedAtMs = SystemClock.elapsedRealtime()
        while (session === watchedSession) {
            delay(LIVE_STALL_POLL_MS)
            val nowMs = SystemClock.elapsedRealtime()
            val lastFrameMs = lastVideoFrameAtMs.get()
            if (
                !liveStreamStalled(
                    kind = request.kind,
                    playWhenReady = telemetry.playWhenReady,
                    watchStartedAtMs = watchStartedAtMs,
                    lastFrameAtMs = lastFrameMs,
                    nowMs = nowMs,
                ) || pendingAutomaticRetryMs != null
            ) continue
            val retry = playbackRetryDecision(
                kind = request.kind,
                errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                attemptsUsed = automaticRetryAttempts,
            )
            if (retry == null) {
                telemetry = telemetry.copy(
                    state = "Playback stalled",
                    safeError = "Live video stopped. Check the camera, your server, and network, then try again.",
                )
                controlsVisible = true
            } else {
                automaticRetryAttempts = retry.attempt
                pendingAutomaticRetryMs = retry.delayMs
                telemetry = telemetry.copy(
                    state = "Reconnecting",
                    safeError = "Live video stopped updating. Reconnecting automatically " +
                        "(${retry.attempt}/$MAX_LIVE_AUTOMATIC_RETRIES)…",
                )
            }
            break
        }
    }

    LaunchedEffect(session, request.kind) {
        if (request.kind != PlaybackKind.RECORDED) return@LaunchedEffect
        val activePlayer = session?.player ?: return@LaunchedEffect
        while (true) {
            val durationMs = activePlayer.duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: 0L
            telemetry = telemetry.copy(
                positionMs = activePlayer.currentPosition.coerceAtLeast(0L),
                bufferedPositionMs = activePlayer.bufferedPosition.coerceAtLeast(0L),
                durationMs = durationMs,
                seekable = activePlayer.isCurrentMediaItemSeekable,
            )
            delay(if (controlsVisible) PLAYBACK_PROGRESS_REFRESH_MS else 1_000L)
        }
    }

    LaunchedEffect(controlsVisible, diagnosticsVisible) {
        withFrameNanos { }
        overlayRenderPass += 1
    }
    LaunchedEffect(controlsVisible) {
        withFrameNanos { }
        if (controlsVisible) {
            playButtonFocusRequester.requestFocus()
        } else {
            hiddenControlsFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(
        pipRequested,
        session,
        telemetry.firstFrameMs,
        telemetry.safeError,
        telemetry.videoWidth,
        telemetry.videoHeight,
    ) {
        if (!pipRequested) return@LaunchedEffect
        if (telemetry.safeError != null) {
            pipRequested = false
            controlsVisible = true
            pipError = "Picture in Picture could not start because the video did not load"
            return@LaunchedEffect
        }
        val activeSession = session ?: return@LaunchedEffect
        if (!activeSession.videoOnly || telemetry.firstFrameMs == null) return@LaunchedEffect
        val sourceRect = visibleVideoRect(
            playerView = playerView,
            videoWidth = telemetry.videoWidth,
            videoHeight = telemetry.videoHeight,
        )
        val entered = onEnterPictureInPicture(
            PictureInPictureRequest(
                title = request.title,
                subtitle = "Live",
                aspectRatio = pipAspectRatio(telemetry.videoWidth, telemetry.videoHeight),
                sourceRectHint = sourceRect,
            ),
        )
        pipRequested = false
        if (!entered) {
            controlsVisible = true
            pipError = "Picture in Picture is unavailable. Video remains open in Opah."
        }
    }

    LaunchedEffect(
        playbackStateKey,
        controlsInteractionToken,
        telemetry.safeError,
        diagnosticsVisible,
    ) {
        if (telemetry.safeError == null && !diagnosticsVisible && !request.compatibilityTest) {
            delay(PLAYBACK_CONTROLS_TIMEOUT_MS)
            controlsVisible = false
        }
    }

    DisposableEffect(session, request.kind) {
        val activeSession = session ?: return@DisposableEffect onDispose { }
        val playerListener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                telemetry = telemetry.copy(
                    state = when (playbackState) {
                        Player.STATE_IDLE -> "Idle"
                        Player.STATE_BUFFERING -> "Buffering"
                        Player.STATE_READY -> "Ready"
                        Player.STATE_ENDED -> "Ended"
                        else -> "Unknown"
                    },
                    ended = playbackState == Player.STATE_ENDED,
                )
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                telemetry = telemetry.copy(isPlaying = isPlaying)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                telemetry = telemetry.copy(playWhenReady = playWhenReady)
            }

            override fun onTracksChanged(tracks: Tracks) {
                telemetry = telemetry.copy(
                    videoFormat = selectedTrackDescription(tracks, C.TRACK_TYPE_VIDEO),
                    audioFormat = selectedTrackDescription(tracks, C.TRACK_TYPE_AUDIO),
                )
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                telemetry = telemetry.copy(
                    videoWidth = videoSize.width,
                    videoHeight = videoSize.height,
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                if (compatibilityLive) return
                when (
                    decoderRecoveryAction(
                        kind = request.kind,
                        errorCode = error.errorCode,
                        fallbackUri = request.startupFallbackUri,
                        lowerQualityActive = startupFallbackActive,
                        softwareDecoderActive = softwareDecoderActive,
                    )
                ) {
                    DecoderRecoveryAction.LOWER_QUALITY -> {
                        startupFallbackActive = true
                        return
                    }
                    DecoderRecoveryAction.SOFTWARE_DECODER -> {
                        softwareDecoderActive = true
                        return
                    }
                    DecoderRecoveryAction.NONE -> Unit
                }
                if (
                    request.kind == PlaybackKind.LIVE &&
                    !forceTcp &&
                    error.hasRtspUdpUnsupportedTransportCause()
                ) {
                    automaticTcpFallback = true
                    forceTcp = true
                    return
                }
                if (request.kind == PlaybackKind.RECORDED && error.hasHttpStatus(401)) {
                    onSessionExpired("Your sign-in expired while the recording was playing. Sign in again.")
                    return
                }
                playbackRetryDecision(
                    kind = request.kind,
                    errorCode = error.errorCode,
                    attemptsUsed = automaticRetryAttempts,
                )?.let { retry ->
                    automaticRetryAttempts = retry.attempt
                    pendingAutomaticRetryMs = retry.delayMs
                    telemetry = telemetry.copy(
                        state = "Reconnecting",
                        safeError = "Live video was interrupted. Reconnecting automatically " +
                            "(${retry.attempt}/$MAX_LIVE_AUTOMATIC_RETRIES)…",
                    )
                    return
                }
                telemetry = telemetry.copy(
                    state = "Playback failed",
                    safeError = safePlaybackError(error, request.kind),
                )
                controlsVisible = true
            }
        }
        val analyticsListener = object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                telemetry = telemetry.copy(videoDecoder = decoderName)
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                telemetry = telemetry.copy(audioDecoder = decoderName)
            }

            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long,
            ) {
                lastVideoFrameAtMs.set(SystemClock.elapsedRealtime())
                if (telemetry.firstFrameMs == null) {
                    telemetry = telemetry.copy(
                        firstFrameMs = max(0L, SystemClock.elapsedRealtime() - activeSession.startedAtMs),
                    )
                }
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long,
            ) {
                telemetry = telemetry.copy(droppedFrames = telemetry.droppedFrames + droppedFrames)
            }
        }

        val frameMetadataListener = VideoFrameMetadataListener { _, _, _, _ ->
            lastVideoFrameAtMs.set(SystemClock.elapsedRealtime())
        }

        activeSession.player.addListener(playerListener)
        activeSession.player.addAnalyticsListener(analyticsListener)
        activeSession.player.setVideoFrameMetadataListener(frameMetadataListener)
        onDispose {
            activeSession.player.clearVideoFrameMetadataListener(frameMetadataListener)
            activeSession.player.removeAnalyticsListener(analyticsListener)
            activeSession.player.removeListener(playerListener)
        }
    }

    BackHandler {
        when (playbackBackAction(controlsVisible, diagnosticsVisible, pictureInPictureActive)) {
            PlaybackBackAction.HIDE_OVERLAY -> {
                diagnosticsVisible = false
                controlsVisible = false
            }
            PlaybackBackAction.EXIT -> onBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .drawWithContent {
                overlayRenderPass
                drawContent()
            }
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Back) {
                    false
                } else if (event.type == KeyEventType.KeyUp && consumeRevealKeyUp) {
                    consumeRevealKeyUp = false
                    true
                } else if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    controlsInteractionToken += 1
                    if (!controlsVisible) {
                        controlsVisible = true
                        consumeRevealKeyUp = true
                        true
                    } else {
                        false
                    }
                }
            }
            .focusRequester(hiddenControlsFocusRequester)
            .focusable(enabled = !controlsVisible),
    ) {
        if (compatibilityLive) {
            key(playbackStateKey) {
                AndroidView(
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            useController = false
                            keepScreenOn = true
                            resizeMode = videoResizeMode(stretchVideo)
                            playerView = this
                        }
                    },
                    update = {
                        it.resizeMode = videoResizeMode(stretchVideo)
                        playerView = it
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else session?.let { activeSession ->
            key(activeSession) {
                AndroidView(
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            useController = false
                            keepScreenOn = true
                            resizeMode = videoResizeMode(stretchVideo)
                            player = activeSession.player
                            playerView = this
                        }
                    },
                    update = {
                        it.player = activeSession.player
                        it.resizeMode = videoResizeMode(stretchVideo)
                        playerView = it
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (!pictureInPictureActive && (controlsVisible || telemetry.safeError != null || pipError != null)) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(22.dp)
                    .widthIn(max = 720.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.62f),
                        shape = RoundedCornerShape(12.dp),
                    )
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(
                    text = request.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = activeDetail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                telemetry.safeError?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                pipError?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                activityRecordingMessage?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                activityQueueProgress?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                nextActivityLabel?.takeIf { !nextActivityEnabled }?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                liveActionMessage?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                compatibilityMessage?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (!pictureInPictureActive && !controlsVisible && activityQueueProgress != null) {
            Text(
                text = activityQueueProgress,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(22.dp)
                    .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (!pictureInPictureActive && diagnosticsVisible) {
            PlaybackDiagnostics(
                telemetry = telemetry,
                request = request,
                forceTcp = forceTcp,
                automaticTcpFallback = automaticTcpFallback,
                startupFallbackActive = startupFallbackActive,
                automaticRetryAttempts = automaticRetryAttempts,
                videoOnly = videoOnly,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(20.dp),
            )
        }

        if (!pictureInPictureActive) {
            session?.let { activeSession ->
                val pictureInPictureVisible = shouldOfferLivePictureInPicture(
                    sdkInt = Build.VERSION.SDK_INT,
                    hasSystemFeature = pictureInPictureAvailable,
                    kind = request.kind,
                    firstFrameRendered = telemetry.firstFrameMs != null,
                )
                val openPictureInPicture = {
                    pipError = null
                    pipRequested = true
                    muted = true
                    diagnosticsVisible = false
                    controlsVisible = false
                    videoOnly = true
                }
                if (controlsVisible) {
                    PlaybackControls(
                        request = request,
                        telemetry = telemetry,
                        player = activeSession.player,
                        onRetry = {
                            pendingAutomaticRetryMs = null
                            automaticRetryAttempts = 0
                            if (request.kind == PlaybackKind.RECORDED) {
                                recordedPositionMs = 0L
                                resumeWhenStarted = true
                            }
                            retryToken += 1
                        },
                        previousCameraName = previousCameraName,
                        onPrevious = onPrevious,
                        nextCameraName = nextCameraName,
                        onNext = onNext,
                        onOpenEarlier = onOpenEarlier,
                        onReturnToLive = onReturnToLive,
                        snapshotCapturing = snapshotCapturing,
                        onInstantSnapshot = onInstantSnapshot,
                        onDemandRecordingActive = onDemandRecordingActive,
                        onDemandRecordingBusy = onDemandRecordingBusy,
                        onToggleOnDemandRecording = onToggleOnDemandRecording,
                        activityReviewed = activityReviewed,
                        markingActivityReviewed = markingActivityReviewed,
                        onMarkActivityReviewed = onMarkActivityReviewed,
                        stretchVideo = stretchVideo,
                        onToggleStretchVideo = onToggleStretchVideo,
                        savingActivityRecording = savingActivityRecording,
                        activityRecordingSaved = activityRecordingSaved,
                        onSaveActivityRecording = onSaveActivityRecording,
                        nextActivityLabel = nextActivityLabel,
                        nextActivityEnabled = nextActivityEnabled,
                        onNextActivity = onNextActivity,
                        muted = muted,
                        audioHelpActive = videoOnly,
                        diagnosticsAvailable = diagnosticsAvailable,
                        diagnosticsVisible = diagnosticsVisible,
                        pictureInPictureVisible = pictureInPictureVisible,
                        pipRequested = pipRequested,
                        onToggleMute = { muted = !muted },
                        onToggleAudioHelp = { videoOnly = !videoOnly },
                        onPictureInPicture = openPictureInPicture,
                        onToggleDiagnostics = { diagnosticsVisible = !diagnosticsVisible },
                        playFocusRequester = playButtonFocusRequester,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .widthIn(max = 1120.dp)
                            .padding(bottom = 16.dp)
                            .background(
                                color = Color.Black.copy(alpha = 0.68f),
                                shape = RoundedCornerShape(16.dp),
                            )
                            .border(
                                width = 1.dp,
                                color = Color.White.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(16.dp),
                            )
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
            } ?: Text(
                text = "Playback resources released while Opah is in the background",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

private fun compatibilityTestStatus(
    cameraName: String,
    state: PlaybackCompatibilityState,
): String? = when (state) {
    is PlaybackCompatibilityState.Attempting ->
        "$cameraName: trying choice ${state.run.attemptsStarted.coerceAtLeast(1)} of " +
            "${state.plan.budget.maxAttempts + state.plan.budget.maxRecoveryCycles} • " +
            compatibilityChoiceLabel(state.attempt.candidate)
    is PlaybackCompatibilityState.Recovering ->
        "$cameraName: reconnecting before the next safe choice"
    is PlaybackCompatibilityState.Verified ->
        "$cameraName: choice saved • ${compatibilityChoiceLabel(state.result.strategy.candidate)}"
    is PlaybackCompatibilityState.UnpersistedLive ->
        "$cameraName: video works for now, but the choice could not be saved"
    is PlaybackCompatibilityState.Persisting ->
        "$cameraName: video works • saving ${compatibilityChoiceLabel(state.strategy.candidate)}"
    is PlaybackCompatibilityState.Finished -> when (val result = state.result) {
        is PlaybackResult.VerifiedLive ->
            "$cameraName: choice saved • ${compatibilityChoiceLabel(result.strategy.candidate)}"
        is PlaybackResult.UnpersistedLive ->
            "$cameraName: video works for now, but the choice could not be saved"
        is PlaybackResult.Cancelled -> null
        else -> "$cameraName: Opah couldn't confirm a new choice. Existing playback settings were not changed"
    }
    is PlaybackCompatibilityState.Idle -> "$cameraName: preparing the first safe choice"
    is PlaybackCompatibilityState.Releasing -> "$cameraName: moving to the next safe choice"
    is PlaybackCompatibilityState.ReleasedAwaitingPersistence -> "$cameraName: saving the working choice"
    is PlaybackCompatibilityState.PresentingSnapshot -> "$cameraName: preparing video"
}

private fun compatibilityChoiceLabel(candidate: PlaybackCandidate): String = buildList {
    add(if (candidate.transportMode == TransportMode.FORCE_RTP_TCP) "more reliable connection" else "standard connection")
    add(if (candidate.audioMode == AudioMode.VIDEO_ONLY) "video only" else "video and sound")
    add(
        when (candidate.decoderMode) {
            DecoderMode.PLATFORM_DEFAULT -> "automatic video decoder"
            DecoderMode.PREFER_HARDWARE -> "TV video decoder"
            DecoderMode.ALLOW_SOFTWARE -> "compatibility video decoder"
        },
    )
}.joinToString(" • ")

private const val COMPATIBILITY_TEST_RESULT_DISPLAY_MILLIS = 1_200L

@UnstableApi
@Composable
private fun DocumentationPlaybackScreen(
    request: PlaybackRequest,
    onBack: () -> Unit,
    startMuted: Boolean,
    diagnosticsAvailable: Boolean,
    pictureInPictureAvailable: Boolean,
    pictureInPictureActive: Boolean,
    onEnterPictureInPicture: (PictureInPictureRequest) -> Boolean,
    previousCameraName: String?,
    onPrevious: (() -> Unit)?,
    nextCameraName: String?,
    onNext: (() -> Unit)?,
    onOpenEarlier: (() -> Unit)?,
    onReturnToLive: (() -> Unit)?,
    snapshotCapturing: Boolean,
    onInstantSnapshot: (() -> Unit)?,
    onDemandRecordingActive: Boolean,
    onDemandRecordingBusy: Boolean,
    onToggleOnDemandRecording: (() -> Unit)?,
    liveActionMessage: String?,
    onPlaybackCompleted: (() -> Unit)?,
    activityReviewed: Boolean?,
    markingActivityReviewed: Boolean,
    onMarkActivityReviewed: (() -> Unit)?,
    stretchVideo: Boolean,
    onToggleStretchVideo: (() -> Unit)?,
    savingActivityRecording: Boolean,
    activityRecordingSaved: Boolean,
    activityRecordingMessage: String?,
    activityQueueProgress: String?,
    onSaveActivityRecording: (() -> Unit)?,
    nextActivityLabel: String?,
    nextActivityEnabled: Boolean,
    onNextActivity: (() -> Unit)?,
) {
    val context = LocalContext.current
    val cameraName = request.uri.removePrefix(DOCUMENTATION_URI_PREFIX).substringBefore('?')
    val imageResource = remember(cameraName) { DocumentationSupport.cameraDrawable(cameraName) }
    val recorded = request.kind == PlaybackKind.RECORDED
    val initialTelemetry = remember(cameraName, recorded) {
        DocumentationSupport.data.playbackTelemetry(cameraName, recorded, videoOnly = false)
    }
    val player = remember { ExoPlayer.Builder(context).build().apply { playWhenReady = true } }
    DisposableEffect(player) { onDispose(player::release) }
    var controlsVisible by rememberSaveable(request.uri) { mutableStateOf(true) }
    var controlsInteractionToken by remember { mutableIntStateOf(0) }
    var overlayRenderPass by remember { mutableIntStateOf(0) }
    var consumeRevealKeyUp by remember { mutableStateOf(false) }
    val hiddenControlsFocusRequester = remember(request.uri) { FocusRequester() }
    val playButtonFocusRequester = remember(request.uri) { FocusRequester() }
    var muted by rememberSaveable(request.uri) { mutableStateOf(startMuted) }
    var documentationPlaying by rememberSaveable(request.uri) { mutableStateOf(true) }
    var documentationPositionMs by rememberSaveable(request.uri) { mutableLongStateOf(initialTelemetry.positionMs) }
    var videoOnly by rememberSaveable(request.uri) { mutableStateOf(false) }
    var forceTcp by rememberSaveable(request.uri) { mutableStateOf(request.kind == PlaybackKind.LIVE) }
    var diagnosticsVisible by rememberSaveable(request.uri) { mutableStateOf(false) }
    var pipRequested by remember(request.uri) { mutableStateOf(false) }
    var pipError by remember(request.uri) { mutableStateOf<String?>(null) }
    val telemetry = DocumentationSupport.data.playbackTelemetry(cameraName, recorded, videoOnly).copy(
        isPlaying = documentationPlaying,
        playWhenReady = documentationPlaying,
        positionMs = if (recorded) documentationPositionMs else 0L,
    )

    LaunchedEffect(recorded, documentationPositionMs, documentationPlaying, onReturnToLive) {
        if (recorded && documentationPlaying && documentationPositionMs >= telemetry.durationMs && onReturnToLive != null) {
            delay(500)
            onReturnToLive()
        }
    }

    LaunchedEffect(controlsVisible, diagnosticsVisible) {
        withFrameNanos { }
        overlayRenderPass += 1
    }
    LaunchedEffect(controlsVisible) {
        withFrameNanos { }
        if (controlsVisible) {
            playButtonFocusRequester.requestFocus()
        } else {
            hiddenControlsFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(request.uri, controlsInteractionToken, diagnosticsVisible) {
        if (!diagnosticsVisible && !request.compatibilityTest) {
            delay(PLAYBACK_CONTROLS_TIMEOUT_MS)
            controlsVisible = false
        }
    }

    BackHandler {
        when (playbackBackAction(controlsVisible, diagnosticsVisible, pictureInPictureActive)) {
            PlaybackBackAction.HIDE_OVERLAY -> {
                diagnosticsVisible = false
                controlsVisible = false
            }
            PlaybackBackAction.EXIT -> onBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .drawWithContent {
                overlayRenderPass
                drawContent()
            }
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Back) {
                    false
                } else if (event.type == KeyEventType.KeyUp && consumeRevealKeyUp) {
                    consumeRevealKeyUp = false
                    true
                } else if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    controlsInteractionToken += 1
                    if (!controlsVisible) {
                        controlsVisible = true
                        consumeRevealKeyUp = true
                        true
                    } else {
                        false
                    }
                }
            }
            .focusRequester(hiddenControlsFocusRequester)
            .focusable(enabled = !controlsVisible),
    ) {
        Image(
            painter = painterResource(imageResource),
            contentDescription = null,
            contentScale = if (stretchVideo) ContentScale.FillBounds else ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )

        if (!pictureInPictureActive && controlsVisible) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(22.dp)
                    .widthIn(max = 720.dp)
                    .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(
                    text = request.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = buildString {
                        append(
                            if (request.compatibilityTest) {
                                "Testing camera compatibility"
                            } else {
                                playbackContextLabel(request.kind, startupFallbackActive = false)
                            },
                        )
                        if (!documentationPlaying) append(" • Paused")
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                pipError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                activityRecordingMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                activityQueueProgress?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                nextActivityLabel?.takeIf { !nextActivityEnabled }?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                liveActionMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (!pictureInPictureActive && !controlsVisible && activityQueueProgress != null) {
            Text(
                text = activityQueueProgress,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(22.dp)
                    .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (!pictureInPictureActive && diagnosticsVisible) {
            PlaybackDiagnostics(
                telemetry = telemetry,
                request = request,
                forceTcp = forceTcp,
                automaticTcpFallback = false,
                startupFallbackActive = false,
                automaticRetryAttempts = 0,
                videoOnly = videoOnly,
                modifier = Modifier.align(Alignment.CenterEnd).padding(20.dp),
            )
        }

        if (!pictureInPictureActive) {
            val pictureInPictureVisible = shouldOfferLivePictureInPicture(
                sdkInt = Build.VERSION.SDK_INT,
                hasSystemFeature = pictureInPictureAvailable,
                kind = request.kind,
                firstFrameRendered = true,
            )
            val openPictureInPicture = {
                    pipRequested = true
                    muted = true
                    videoOnly = true
                    diagnosticsVisible = false
                    controlsVisible = false
                    val entered = onEnterPictureInPicture(
                        PictureInPictureRequest(
                            title = request.title,
                            subtitle = "Live",
                            aspectRatio = pipAspectRatio(1920, 1080),
                            sourceRectHint = null,
                        ),
                    )
                    pipRequested = false
                    if (!entered) {
                        controlsVisible = true
                        pipError = "Picture-in-picture was unavailable"
                    }
                Unit
            }
            if (controlsVisible) {
                PlaybackControls(
                    request = request,
                    telemetry = telemetry,
                    player = player,
                    onRetry = {},
                    previousCameraName = previousCameraName,
                    onPrevious = onPrevious,
                    nextCameraName = nextCameraName,
                    onNext = onNext,
                    onOpenEarlier = onOpenEarlier,
                    onReturnToLive = onReturnToLive,
                    snapshotCapturing = snapshotCapturing,
                    onInstantSnapshot = onInstantSnapshot,
                    onDemandRecordingActive = onDemandRecordingActive,
                    onDemandRecordingBusy = onDemandRecordingBusy,
                    onToggleOnDemandRecording = onToggleOnDemandRecording,
                    activityReviewed = activityReviewed,
                    markingActivityReviewed = markingActivityReviewed,
                    onMarkActivityReviewed = onMarkActivityReviewed,
                    stretchVideo = stretchVideo,
                    onToggleStretchVideo = onToggleStretchVideo,
                    savingActivityRecording = savingActivityRecording,
                    activityRecordingSaved = activityRecordingSaved,
                    onSaveActivityRecording = onSaveActivityRecording,
                    nextActivityLabel = nextActivityLabel,
                    nextActivityEnabled = nextActivityEnabled,
                    onNextActivity = onNextActivity,
                    muted = muted,
                    audioHelpActive = videoOnly,
                    pictureInPictureVisible = pictureInPictureVisible,
                    pipRequested = pipRequested,
                    onToggleMute = { muted = !muted },
                    onToggleAudioHelp = { videoOnly = !videoOnly },
                    onPictureInPicture = openPictureInPicture,
                    diagnosticsAvailable = diagnosticsAvailable,
                    diagnosticsVisible = diagnosticsVisible,
                    onToggleDiagnostics = { diagnosticsVisible = !diagnosticsVisible },
                    onPlaybackStateChanged = { playing -> documentationPlaying = playing },
                    onSeekPositionChanged = { position -> documentationPositionMs = position },
                    playFocusRequester = playButtonFocusRequester,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .widthIn(max = 1120.dp)
                        .padding(bottom = 16.dp)
                        .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(16.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }
        }
    }
}

private fun visibleVideoRect(
    playerView: PlayerView?,
    videoWidth: Int,
    videoHeight: Int,
): Rect? {
    val container = Rect()
    if (playerView?.getGlobalVisibleRect(container) != true || container.isEmpty) return null
    if (videoWidth <= 0 || videoHeight <= 0) return container

    val videoRatio = videoWidth.toDouble() / videoHeight.toDouble()
    val containerRatio = container.width().toDouble() / container.height().toDouble()
    return if (videoRatio >= containerRatio) {
        val contentHeight = (container.width() / videoRatio).roundToInt().coerceAtMost(container.height())
        val top = container.top + (container.height() - contentHeight) / 2
        Rect(container.left, top, container.right, top + contentHeight)
    } else {
        val contentWidth = (container.height() * videoRatio).roundToInt().coerceAtMost(container.width())
        val left = container.left + (container.width() - contentWidth) / 2
        Rect(left, container.top, left + contentWidth, container.bottom)
    }
}

@UnstableApi
private fun createPlayerSession(
    context: android.content.Context,
    application: OpahApplication,
    request: PlaybackRequest,
    forceTcp: Boolean,
    videoOnly: Boolean,
    preferSoftwareVideoDecoder: Boolean,
    recordedPositionMs: Long,
    playWhenReady: Boolean,
): PlayerSession {
    val startedAt = SystemClock.elapsedRealtime()
    return when (request.kind) {
        PlaybackKind.LIVE -> {
            val livePlayer = application.container.livePlayerFactory.create(
                context,
                preferSoftwareVideoDecoder,
            )
            val player = livePlayer.player
            livePlayer.prepare(
                request.uri,
                LivePlaybackOptions(forceRtpTcp = forceTcp, videoOnly = videoOnly),
            )
            PlayerSession(player, startedAt, videoOnly, livePlayer::release)
        }

        PlaybackKind.RECORDED -> {
            val player = RecordedPlayerFactory.create(
                context,
                application.container.httpClient,
                preferSoftwareVideoDecoder,
            )
            player.disableAudio(videoOnly)
            val mediaItem = MediaItem.Builder()
                .setUri(request.uri)
                .apply { recordedMediaMimeType(request.uri)?.let(::setMimeType) }
                .build()
            player.setMediaItem(mediaItem)
            if (recordedPositionMs > 0L) player.seekTo(recordedPositionMs)
            player.playWhenReady = playWhenReady
            player.prepare()
            PlayerSession(player, startedAt, videoOnly, player::release)
        }
    }
}

@UnstableApi
private fun ExoPlayer.disableAudio(disabled: Boolean) {
    trackSelectionParameters = trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, disabled)
        .build()
}

@Composable
private fun PlaybackDiagnostics(
    telemetry: PlaybackTelemetry,
    request: PlaybackRequest,
    forceTcp: Boolean,
    automaticTcpFallback: Boolean,
    startupFallbackActive: Boolean,
    automaticRetryAttempts: Int,
    videoOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(430.dp)
            .background(Color.Black.copy(alpha = 0.76f), RoundedCornerShape(10.dp))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
            .padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Native playback evidence", fontWeight = FontWeight.Bold)
        DiagnosticLine("State", telemetry.state)
        DiagnosticLine("Video track", telemetry.videoFormat ?: "not selected")
        DiagnosticLine("Video decoder", telemetry.videoDecoder ?: "not initialized")
        DiagnosticLine("Audio pipeline", if (videoOnly) "disabled before prepare" else "enabled")
        DiagnosticLine("Audio track", telemetry.audioFormat ?: if (videoOnly) "disabled" else "not selected")
        DiagnosticLine("Audio decoder", telemetry.audioDecoder ?: if (videoOnly) "disabled" else "not initialized")
        DiagnosticLine("First frame", telemetry.firstFrameMs?.let { "$it ms" } ?: "waiting")
        DiagnosticLine("Dropped frames", telemetry.droppedFrames.toString())
        if (request.kind == PlaybackKind.LIVE) {
            DiagnosticLine("Timeline", "real-time RTSP; rewind unavailable")
            DiagnosticLine(
                "Automatic reconnect",
                "$automaticRetryAttempts/$MAX_LIVE_AUTOMATIC_RETRIES attempts in current outage",
            )
            DiagnosticLine(
                "RTP transport",
                when {
                    automaticTcpFallback -> "TCP (automatic UDP fallback)"
                    forceTcp -> "TCP (preferred default)"
                    else -> "automatic (UDP; TCP fallback armed)"
                },
            )
            if (startupFallbackActive) {
                DiagnosticLine("Stream fallback", "Low bandwidth after 8 s without a first frame")
            }
        } else {
            DiagnosticLine("Source", "Frigate fMP4 HLS")
        }
        telemetry.safeError?.let { error ->
            Spacer(Modifier.height(5.dp))
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun PlaybackControls(
    request: PlaybackRequest,
    telemetry: PlaybackTelemetry,
    player: Player,
    onRetry: () -> Unit,
    previousCameraName: String?,
    onPrevious: (() -> Unit)?,
    nextCameraName: String?,
    onNext: (() -> Unit)?,
    onOpenEarlier: (() -> Unit)?,
    onReturnToLive: (() -> Unit)?,
    snapshotCapturing: Boolean,
    onInstantSnapshot: (() -> Unit)?,
    onDemandRecordingActive: Boolean,
    onDemandRecordingBusy: Boolean,
    onToggleOnDemandRecording: (() -> Unit)?,
    activityReviewed: Boolean?,
    markingActivityReviewed: Boolean,
    onMarkActivityReviewed: (() -> Unit)?,
    stretchVideo: Boolean,
    onToggleStretchVideo: (() -> Unit)?,
    savingActivityRecording: Boolean,
    activityRecordingSaved: Boolean,
    onSaveActivityRecording: (() -> Unit)?,
    nextActivityLabel: String?,
    nextActivityEnabled: Boolean,
    onNextActivity: (() -> Unit)?,
    muted: Boolean,
    audioHelpActive: Boolean,
    diagnosticsAvailable: Boolean,
    diagnosticsVisible: Boolean,
    pictureInPictureVisible: Boolean,
    pipRequested: Boolean,
    onToggleMute: () -> Unit,
    onToggleAudioHelp: () -> Unit,
    onPictureInPicture: () -> Unit,
    onToggleDiagnostics: () -> Unit,
    playFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    onPlaybackStateChanged: ((Boolean) -> Unit)? = null,
    onSeekPositionChanged: ((Long) -> Unit)? = null,
) {
    val isRecorded = request.kind == PlaybackKind.RECORDED
    val availability = playbackControlAvailability(
        kind = request.kind,
        hasPrevious = onPrevious != null,
        hasNext = onNext != null,
        hasEarlier = onOpenEarlier != null,
    )
    val canSeekRecording = availability.seek && telemetry.seekable && telemetry.durationMs > 0L
    val seekToPosition = { positionMs: Long ->
        player.seekTo(positionMs)
        onSeekPositionChanged?.invoke(positionMs)
        Unit
    }
    var focusedControlLabel by remember {
        mutableStateOf(if (telemetry.playWhenReady) "Pause video" else "Play video")
    }
    LaunchedEffect(activityRecordingSaved) {
        if (activityRecordingSaved) focusedControlLabel = "Recording saved"
    }
    LaunchedEffect(muted, audioHelpActive) {
        focusedControlLabel = refreshedPlaybackAudioCaption(
            currentLabel = focusedControlLabel,
            muted = muted,
            videoOnly = audioHelpActive,
        )
    }
    val togglePlayback = {
        if (isRecorded && telemetry.ended) {
            player.seekTo(0L)
            player.play()
            onPlaybackStateChanged?.invoke(true)
        } else if (telemetry.playWhenReady) {
            player.pause()
            onPlaybackStateChanged?.invoke(false)
        } else {
            player.play()
            onPlaybackStateChanged?.invoke(true)
        }
        Unit
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (isRecorded) {
            RecordedTimeline(
                positionMs = telemetry.positionMs,
                bufferedPositionMs = telemetry.bufferedPositionMs,
                durationMs = telemetry.durationMs,
                enabled = canSeekRecording,
                onSeek = seekToPosition,
                onTogglePlayback = togglePlayback,
                modifier = Modifier.width(640.dp),
            )
        }
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isRecorded) {
                    CompactPlaybackAction(
                        iconRes = R.drawable.ic_replay,
                        label = "Back 10 seconds",
                        enabled = canSeekRecording,
                        onFocused = { focusedControlLabel = it },
                        onClick = {
                            seekToPosition(
                                boundedSeekPosition(
                                    telemetry.positionMs,
                                    telemetry.durationMs,
                                    -TIMELINE_SEEK_STEP_MS,
                                ),
                            )
                        },
                    )
                } else {
                    CameraNeighborButton(
                        cameraName = previousCameraName,
                        previous = true,
                        onClick = onPrevious,
                        onFocused = { focusedControlLabel = it },
                    )
                }
                PlaybackPlayButton(
                    iconRes = if (telemetry.playWhenReady && !telemetry.ended) {
                        R.drawable.ic_pause
                    } else {
                        R.drawable.ic_play
                    },
                    label = if (isRecorded) {
                        recordedPlaybackButtonLabel(telemetry.playWhenReady, telemetry.ended)
                    } else if (telemetry.playWhenReady) {
                        "Pause live video"
                    } else {
                        "Resume live video"
                    },
                    focusRequester = playFocusRequester,
                    onFocused = { focusedControlLabel = it },
                    onClick = togglePlayback,
                )
                if (isRecorded) {
                    CompactPlaybackAction(
                        iconRes = R.drawable.ic_forward,
                        label = "Forward 10 seconds",
                        enabled = canSeekRecording,
                        onFocused = { focusedControlLabel = it },
                        onClick = {
                            seekToPosition(
                                boundedSeekPosition(
                                    telemetry.positionMs,
                                    telemetry.durationMs,
                                    TIMELINE_SEEK_STEP_MS,
                                ),
                            )
                        },
                    )
                    if (nextActivityLabel != null && onNextActivity != null) {
                        CompactPlaybackAction(
                            iconRes = R.drawable.ic_chevron_right,
                            label = nextActivityLabel,
                            enabled = nextActivityEnabled,
                            onFocused = { focusedControlLabel = it },
                            onClick = onNextActivity,
                        )
                    }
                } else {
                    CameraNeighborButton(
                        cameraName = nextCameraName,
                        previous = false,
                        onClick = onNext,
                        onFocused = { focusedControlLabel = it },
                    )
                }
                if (telemetry.safeError != null) {
                    CompactPlaybackAction(
                        iconRes = R.drawable.ic_retry,
                        label = "Retry video",
                        onClick = onRetry,
                        onFocused = { focusedControlLabel = it },
                    )
                }
                if (availability.earlier && onOpenEarlier != null) {
                    CompactPlaybackAction(
                        iconRes = R.drawable.ic_replay,
                        label = "Go back 30 seconds",
                        onClick = onOpenEarlier,
                        onFocused = { focusedControlLabel = it },
                    )
                }
                if (isRecorded && onReturnToLive != null) {
                    CompactPlaybackAction(
                        iconRes = R.drawable.ic_videocam,
                        label = "Return to live",
                        onClick = onReturnToLive,
                        onFocused = { focusedControlLabel = it },
                    )
                }
                if (!isRecorded && onInstantSnapshot != null) {
                    CompactPlaybackAction(
                        iconRes = R.drawable.ic_videocam,
                        label = if (snapshotCapturing) "Capturing snapshot" else "Take snapshot",
                        enabled = !snapshotCapturing,
                        onClick = onInstantSnapshot,
                        onFocused = { focusedControlLabel = it },
                    )
                }
                if (!isRecorded && onToggleOnDemandRecording != null) {
                    CompactPlaybackAction(
                        iconRes = R.drawable.ic_save_recording,
                        label = when {
                            onDemandRecordingBusy -> "Updating recording"
                            onDemandRecordingActive -> "Stop recording"
                            else -> "Start recording"
                        },
                        enabled = !onDemandRecordingBusy,
                        selected = onDemandRecordingActive,
                        onClick = onToggleOnDemandRecording,
                        onFocused = { focusedControlLabel = it },
                    )
                }
                if (isRecorded && onMarkActivityReviewed != null) {
                    CompactPlaybackAction(
                        iconRes = R.drawable.ic_mark_reviewed,
                        label = when {
                            markingActivityReviewed -> "Saving review status"
                            activityReviewed == true -> "Mark as not reviewed"
                            else -> "Mark as reviewed"
                        },
                        enabled = !markingActivityReviewed,
                        selected = activityReviewed == true,
                        onClick = onMarkActivityReviewed,
                        onFocused = { focusedControlLabel = it },
                    )
                }
                if (isRecorded && onSaveActivityRecording != null) {
                    CompactPlaybackAction(
                        iconRes = R.drawable.ic_save_recording,
                        label = when {
                            savingActivityRecording -> "Saving recording"
                            activityRecordingSaved -> "Recording saved"
                            else -> "Save recording"
                        },
                        enabled = !savingActivityRecording && !activityRecordingSaved,
                        selected = activityRecordingSaved,
                        onClick = onSaveActivityRecording,
                        onFocused = { focusedControlLabel = it },
                    )
                }
                if (onToggleStretchVideo != null) {
                    CompactPlaybackAction(
                        iconRes = R.drawable.ic_stretch,
                        label = if (stretchVideo) "Fit video to screen" else "Stretch video to screen",
                        selected = stretchVideo,
                        onClick = onToggleStretchVideo,
                        onFocused = { focusedControlLabel = it },
                    )
                }
                CompactPlaybackAction(
                    iconRes = if (muted || audioHelpActive) {
                        R.drawable.ic_volume_off
                    } else {
                        R.drawable.ic_volume_on
                    },
                    label = playbackMuteControlLabel(muted, audioHelpActive),
                    enabled = !audioHelpActive,
                    onClick = onToggleMute,
                    selected = muted,
                    onFocused = { focusedControlLabel = it },
                )
                CompactPlaybackAction(
                    iconRes = R.drawable.ic_audio_help,
                    label = playbackAlternateAudioControlLabel(audioHelpActive),
                    onClick = onToggleAudioHelp,
                    selected = audioHelpActive,
                    onFocused = { focusedControlLabel = it },
                )
                if (pictureInPictureVisible) {
                    CompactPlaybackAction(
                        iconRes = R.drawable.ic_picture_in_picture,
                        label = "Pop out video",
                        enabled = !pipRequested,
                        onClick = onPictureInPicture,
                        onFocused = { focusedControlLabel = it },
                    )
                }
                if (diagnosticsAvailable) {
                    CompactPlaybackAction(
                        iconRes = R.drawable.ic_playback_info,
                        label = if (diagnosticsVisible) "Hide playback information" else "Playback information",
                        onClick = onToggleDiagnostics,
                        selected = diagnosticsVisible,
                        onFocused = { focusedControlLabel = it },
                    )
                }
            }
        }
        Box(
            modifier = Modifier.widthIn(min = 240.dp, max = 640.dp).height(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = focusedControlLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CameraNeighborButton(
    cameraName: String?,
    previous: Boolean,
    onClick: (() -> Unit)?,
    onFocused: (String) -> Unit,
) {
    if (cameraName == null || onClick == null) return
    val accessibilityLabel = cameraNeighborAccessibilityLabel(cameraName, previous)
    FocusCard(
        focusKey = "playback:${if (previous) "previous" else "next"}:$cameraName",
        restoreFocusKey = null,
        onFocusRestored = {},
        onClick = onClick,
        accessibilityLabel = accessibilityLabel,
        onFocused = { onFocused(accessibilityLabel) },
        containerColor = Color.White.copy(alpha = 0.10f),
        modifier = Modifier.size(40.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            PlaybackChevron(if (previous) R.drawable.ic_chevron_left else R.drawable.ic_chevron_right)
        }
    }
}

internal fun cameraNeighborVisibleLabel(cameraName: String, previous: Boolean): String =
    cameraName

internal fun cameraNeighborAccessibilityLabel(cameraName: String, previous: Boolean): String =
    if (previous) "Previous camera, $cameraName" else "Next camera, $cameraName"

@Composable
private fun PlaybackChevron(@DrawableRes iconRes: Int) {
    Image(
        painter = painterResource(iconRes),
        contentDescription = null,
        colorFilter = ColorFilter.tint(LocalContentColor.current),
        modifier = Modifier.size(18.dp),
    )
}

@Composable
private fun PlaybackPlayButton(
    @DrawableRes iconRes: Int,
    label: String,
    focusRequester: FocusRequester,
    onFocused: (String) -> Unit,
    onClick: () -> Unit,
) {
    CompactPlaybackAction(
        iconRes = iconRes,
        label = label,
        onClick = onClick,
        focusRequester = focusRequester,
        onFocused = onFocused,
        controlSize = 44.dp,
        iconSize = 23.dp,
    )
}

@Composable
private fun CompactPlaybackAction(
    @DrawableRes iconRes: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    iconSize: androidx.compose.ui.unit.Dp = 19.dp,
    selected: Boolean = false,
    onFocused: (String) -> Unit = {},
    controlSize: androidx.compose.ui.unit.Dp = 40.dp,
) {
    FocusCard(
        focusKey = "playback:quick:$label",
        restoreFocusKey = null,
        onFocusRestored = {},
        onClick = onClick,
        enabled = enabled,
        selected = selected,
        accessibilityLabel = label,
        externalFocusRequester = focusRequester,
        onFocused = { onFocused(label) },
        containerColor = Color.White.copy(alpha = 0.10f),
        modifier = modifier.size(controlSize),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                colorFilter = ColorFilter.tint(LocalContentColor.current),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
private fun RecordedTimeline(
    positionMs: Long,
    bufferedPositionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
    onTogglePlayback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val positionFraction = timelineFraction(positionMs, durationMs)
    val bufferedFraction = timelineFraction(bufferedPositionMs, durationMs)
    val shape = RoundedCornerShape(10.dp)
    val accentColor = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier
            .onPreviewKeyEvent { event ->
                if (!enabled || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val deltaMs = when (event.key) {
                    Key.DirectionLeft -> -TIMELINE_SEEK_STEP_MS
                    Key.DirectionRight -> TIMELINE_SEEK_STEP_MS
                    Key.DirectionCenter, Key.Enter -> {
                        onTogglePlayback()
                        return@onPreviewKeyEvent true
                    }
                    else -> return@onPreviewKeyEvent false
                }
                onSeek(boundedSeekPosition(positionMs, durationMs, deltaMs))
                true
            }
            .onFocusChanged { focused = it.isFocused }
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = if (enabled) {
                    "Playback timeline, ${formatPlaybackTime(positionMs)} of ${formatPlaybackTime(durationMs)}. Left and right seek ten seconds."
                } else {
                    "Playback timeline loading"
                }
                onClick {
                    if (enabled) onTogglePlayback()
                    enabled
                }
            }
            .focusable(enabled)
            .background(Color.Black.copy(alpha = 0.30f), shape)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.White.copy(alpha = 0.16f)
                },
                shape = shape,
            )
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatPlaybackTime(positionMs),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (durationMs > 0L) formatPlaybackTime(durationMs) else "Loading…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp),
        ) {
            val trackHeight = 5.dp.toPx()
            val trackTop = (size.height - trackHeight) / 2f
            val radius = trackHeight / 2f
            drawRoundRect(
                color = Color.White.copy(alpha = 0.22f),
                topLeft = androidx.compose.ui.geometry.Offset(0f, trackTop),
                size = androidx.compose.ui.geometry.Size(size.width, trackHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius),
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.38f),
                topLeft = androidx.compose.ui.geometry.Offset(0f, trackTop),
                size = androidx.compose.ui.geometry.Size(size.width * bufferedFraction, trackHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius),
            )
            drawRoundRect(
                color = accentColor,
                topLeft = androidx.compose.ui.geometry.Offset(0f, trackTop),
                size = androidx.compose.ui.geometry.Size(size.width * positionFraction, trackHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius),
            )
            drawCircle(
                color = accentColor,
                radius = if (focused) 6.dp.toPx() else 4.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(
                    x = size.width * positionFraction,
                    y = size.height / 2f,
                ),
            )
        }
    }
}

internal fun boundedSeekPosition(
    currentPositionMs: Long,
    durationMs: Long,
    deltaMs: Long,
): Long {
    if (durationMs <= 0L) return 0L
    return (currentPositionMs + deltaMs).coerceIn(0L, durationMs)
}

internal fun timelineFraction(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    return positionMs.coerceIn(0L, durationMs).toFloat() / durationMs.toFloat()
}

internal fun formatPlaybackTime(positionMs: Long): String {
    val totalSeconds = positionMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

internal fun recordedPlaybackButtonLabel(playWhenReady: Boolean, ended: Boolean): String = when {
    ended -> "Replay"
    playWhenReady -> "Pause"
    else -> "Play"
}

private fun selectedTrackDescription(tracks: Tracks, trackType: Int): String? {
    tracks.groups.forEach { group ->
        if (group.type != trackType) return@forEach
        for (index in 0 until group.length) {
            if (group.isTrackSelected(index)) return formatDescription(group.getTrackFormat(index))
        }
    }
    return null
}

private fun formatDescription(format: Format): String = buildString {
    append(format.sampleMimeType ?: "unknown codec")
    if (format.width > 0 && format.height > 0) append(" ${format.width}×${format.height}")
    if (format.frameRate > 0) append(" ${"%.1f".format(format.frameRate)} fps")
    if (format.channelCount > 0) append(" ${format.channelCount} ch")
    if (format.sampleRate > 0) append(" ${format.sampleRate} Hz")
}

private fun PlaybackException.hasHttpStatus(statusCode: Int): Boolean {
    return httpStatusCode() == statusCode
}

private fun Throwable.httpStatusCode(): Int? {
    var current: Throwable? = this
    repeat(10) {
        if (current is HttpDataSource.InvalidResponseCodeException) return current.responseCode
        current = current?.cause
    }
    return null
}

internal fun Throwable.hasRtspUdpUnsupportedTransportCause(): Boolean {
    var current: Throwable? = this
    repeat(10) {
        if (current is RtspMediaSource.RtspUdpUnsupportedTransportException) return true
        current = current?.cause
    }
    return false
}

internal fun initialForceTcp(kind: PlaybackKind): Boolean = kind == PlaybackKind.LIVE

internal fun shouldUseStartupFallback(
    kind: PlaybackKind,
    fallbackUri: String?,
    firstFrameMs: Long?,
    safeError: String?,
): Boolean = kind == PlaybackKind.LIVE &&
    !fallbackUri.isNullOrBlank() &&
    firstFrameMs == null &&
    safeError == null

internal data class PlaybackRetryDecision(
    val attempt: Int,
    val delayMs: Long,
)

internal fun playbackRetryDecision(
    kind: PlaybackKind,
    errorCode: Int,
    attemptsUsed: Int,
): PlaybackRetryDecision? {
    if (kind != PlaybackKind.LIVE || attemptsUsed !in 0 until MAX_LIVE_AUTOMATIC_RETRIES) return null
    val transient = errorCode == PlaybackException.ERROR_CODE_DISCONNECTED ||
        errorCode == PlaybackException.ERROR_CODE_TIMEOUT ||
        errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
    if (!transient) return null
    return PlaybackRetryDecision(
        attempt = attemptsUsed + 1,
        delayMs = LIVE_RETRY_DELAYS_MS[attemptsUsed],
    )
}

internal fun liveStreamStalled(
    kind: PlaybackKind,
    playWhenReady: Boolean,
    watchStartedAtMs: Long,
    lastFrameAtMs: Long,
    nowMs: Long,
): Boolean {
    if (kind != PlaybackKind.LIVE || !playWhenReady) return false
    val referenceMs = if (lastFrameAtMs > 0L) lastFrameAtMs else watchStartedAtMs
    val timeoutMs = if (lastFrameAtMs > 0L) LIVE_FRAME_STALL_TIMEOUT_MS else LIVE_FIRST_FRAME_TIMEOUT_MS
    return nowMs - referenceMs >= timeoutMs
}

private const val LIVE_FIRST_FRAME_FALLBACK_MS = 8_000L
private const val LIVE_FIRST_FRAME_TIMEOUT_MS = 12_000L
private const val LIVE_FRAME_STALL_TIMEOUT_MS = 12_000L
private const val LIVE_STALL_POLL_MS = 2_000L
private const val LIVE_RETRY_RESET_AFTER_MS = 30_000L
internal const val MAX_LIVE_AUTOMATIC_RETRIES = 2
private val LIVE_RETRY_DELAYS_MS = longArrayOf(1_000L, 3_000L)
private const val PLAYBACK_CONTROLS_TIMEOUT_MS = 5_500L
private const val PLAYBACK_PROGRESS_REFRESH_MS = 250L
private const val TIMELINE_SEEK_STEP_MS = 10_000L

private val DECODER_PLAYBACK_ERROR_CODES = setOf(
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
)

internal fun safePlaybackError(error: PlaybackException, kind: PlaybackKind): String {
    val classified = DefaultMedia3PlaybackFailureClassifier.classify(
            Media3PlaybackFailureEvidenceExtractor.extract(error),
        )
    if (kind == PlaybackKind.LIVE && classified.diagnosticCode.isMissingRtspSetup) {
        return if (classified.diagnosticCode == PlaybackDiagnosticCode.RTSP_MISSING_FMTP)
            "The camera did not provide the setup information needed for this connection"
        else "The camera did not provide the video setup information needed for this connection"
    }
    if (classified.category == FailureCategory.UNSUPPORTED_VIDEO_SIZE)
        return "This stream is too large for the TV to play"
    val httpStatus = error.httpStatusCode()
    if (kind == PlaybackKind.RECORDED && httpStatus == 404) {
        return "This recording is no longer available. Try another activity item."
    }
    if (error.errorCode in DECODER_PLAYBACK_ERROR_CODES) {
        return if (kind == PlaybackKind.RECORDED) {
            "This TV couldn't play this recording"
        } else {
            "This TV couldn't play this camera stream"
        }
    }
    return safePlaybackGuidance(error.errorCode)
}

internal fun safePlaybackGuidance(errorCode: Int): String = when (errorCode) {
        PlaybackException.ERROR_CODE_DISCONNECTED,
        PlaybackException.ERROR_CODE_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> "Video couldn't connect. Check the camera, your server, and network, then try again."

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        -> "This video could not be read. Try a different video quality."

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        -> "This video format isn't supported. Try a different video quality."

        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
        -> "This device couldn't play the video. Try a lower video quality."

        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
        -> "Audio could not start. Try Video only from More."

        else -> "Video couldn't play. Try again or choose another camera."
}
