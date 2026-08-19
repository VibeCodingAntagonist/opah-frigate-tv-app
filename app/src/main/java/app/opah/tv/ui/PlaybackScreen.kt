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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.tv.material3.Button
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.opah.tv.OpahApplication
import app.opah.tv.BuildConfig
import app.opah.tv.PictureInPictureRequest
import app.opah.tv.pipAspectRatio
import app.opah.tv.shouldOfferLivePictureInPicture
import app.opah.tv.playback.LivePlaybackOptions
import app.opah.tv.playback.PlaybackKind
import app.opah.tv.playback.PlaybackRequest
import app.opah.tv.playback.RecordedPlayerFactory
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

private data class PlaybackTelemetry(
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
)

internal fun playbackControlAvailability(
    kind: PlaybackKind,
    hasPrevious: Boolean,
    hasNext: Boolean,
): PlaybackControlAvailability = PlaybackControlAvailability(
    previous = kind == PlaybackKind.LIVE && hasPrevious,
    seek = kind == PlaybackKind.RECORDED,
    next = kind == PlaybackKind.LIVE && hasNext,
)

internal fun shouldRevealPlaybackControlsOnBack(
    controlsVisible: Boolean,
    pictureInPictureActive: Boolean,
): Boolean = !controlsVisible && !pictureInPictureActive

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
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
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
            onPrevious = onPrevious,
            onNext = onNext,
        )
        return
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val application = context.applicationContext as OpahApplication
    var startupFallbackActive by rememberSaveable(request.uri) { mutableStateOf(false) }
    val activeUri = if (startupFallbackActive) request.startupFallbackUri ?: request.uri else request.uri
    val activeDetail = if (startupFallbackActive) request.startupFallbackDetail ?: request.detail else request.detail
    var forceTcp by rememberSaveable(request.uri, request.kind) {
        mutableStateOf(request.kind == PlaybackKind.LIVE && preferRtpTcp)
    }
    var automaticTcpFallback by rememberSaveable(request.uri) { mutableStateOf(false) }
    var muted by rememberSaveable(request.uri) { mutableStateOf(startMuted) }
    var videoOnly by rememberSaveable(request.uri) { mutableStateOf(false) }
    var diagnosticsVisible by rememberSaveable(request.uri) { mutableStateOf(false) }
    var controlsVisible by rememberSaveable(request.uri) { mutableStateOf(true) }
    var controlsInteractionToken by remember { mutableIntStateOf(0) }
    var consumeRevealKeyUp by remember { mutableStateOf(false) }
    var retryToken by remember { mutableIntStateOf(0) }
    var automaticRetryAttempts by rememberSaveable(request.uri) { mutableIntStateOf(0) }
    var pendingAutomaticRetryMs by remember(request.uri) { mutableStateOf<Long?>(null) }
    var pipRequested by remember(request.uri) { mutableStateOf(false) }
    var pipError by remember(request.uri) { mutableStateOf<String?>(null) }
    var recordedPositionMs by rememberSaveable(request.uri) { mutableLongStateOf(0L) }
    var resumeWhenStarted by rememberSaveable(request.uri) { mutableStateOf(true) }
    var session by remember(request.uri) { mutableStateOf<PlayerSession?>(null) }
    var playerView by remember(request.uri) { mutableStateOf<PlayerView?>(null) }
    val lastVideoFrameAtMs = remember(session) { AtomicLong(0L) }

    DisposableEffect(lifecycleOwner, activeUri, request.kind, forceTcp, videoOnly, retryToken) {
        fun startPlayback() {
            if (session != null) return
            session = createPlayerSession(
                context = context,
                application = application,
                request = request.copy(uri = activeUri),
                forceTcp = forceTcp,
                videoOnly = videoOnly,
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

    LaunchedEffect(session, activeUri, startupFallbackActive, request.kind) {
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
        val retryDelayMs = pendingAutomaticRetryMs ?: return@LaunchedEffect
        delay(retryDelayMs)
        pendingAutomaticRetryMs = null
        retryToken += 1
    }

    LaunchedEffect(session, telemetry.firstFrameMs, telemetry.safeError, request.kind) {
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
                    safeError = "The live stream stopped producing video after " +
                        "$MAX_LIVE_AUTOMATIC_RETRIES automatic reconnect attempts. " +
                        "Check the camera, Frigate, and network, then retry.",
                )
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
            pipError = "Pop out could not start because the video-only stream failed."
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
                subtitle = "Live • Video only",
                aspectRatio = pipAspectRatio(telemetry.videoWidth, telemetry.videoHeight),
                sourceRectHint = sourceRect,
            ),
        )
        pipRequested = false
        if (!entered) {
            controlsVisible = true
            pipError = "Picture-in-picture was unavailable. Video remains open in Opah."
        }
    }

    LaunchedEffect(
        request.uri,
        controlsInteractionToken,
        telemetry.safeError,
        diagnosticsVisible,
    ) {
        if (telemetry.safeError == null && !diagnosticsVisible) {
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
                    onSessionExpired("The Frigate session expired during recording playback. Sign in again.")
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
                        safeError = "Live stream interrupted. Reconnecting automatically " +
                            "(${retry.attempt}/$MAX_LIVE_AUTOMATIC_RETRIES)…",
                    )
                    return
                }
                telemetry = telemetry.copy(
                    state = "Playback failed",
                    safeError = safePlaybackError(error, request.kind),
                )
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
        if (shouldRevealPlaybackControlsOnBack(controlsVisible, pictureInPictureActive)) {
            controlsInteractionToken += 1
            controlsVisible = true
        } else {
            onBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
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
            },
    ) {
        session?.let { activeSession ->
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
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            player = activeSession.player
                            playerView = this
                        }
                    },
                    update = {
                        it.player = activeSession.player
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
                activeDetail?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
            }
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
                PlaybackControls(
                    request = request,
                    telemetry = telemetry,
                    muted = muted,
                    videoOnly = videoOnly,
                    forceTcp = forceTcp,
                    player = activeSession.player,
                    onBack = onBack,
                    onRetry = {
                        pendingAutomaticRetryMs = null
                        automaticRetryAttempts = 0
                        if (request.kind == PlaybackKind.RECORDED) {
                            recordedPositionMs = 0L
                            resumeWhenStarted = true
                        }
                        retryToken += 1
                    },
                    onToggleMute = { muted = !muted },
                    onToggleVideoOnly = { videoOnly = !videoOnly },
                    pictureInPictureVisible = shouldOfferLivePictureInPicture(
                        sdkInt = Build.VERSION.SDK_INT,
                        hasSystemFeature = pictureInPictureAvailable,
                        kind = request.kind,
                        firstFrameRendered = telemetry.firstFrameMs != null,
                    ),
                    pipRequested = pipRequested,
                    onPopOut = {
                        pipError = null
                        pipRequested = true
                        muted = true
                        diagnosticsVisible = false
                        controlsVisible = false
                        videoOnly = true
                    },
                    onToggleForceTcp = {
                        automaticTcpFallback = false
                        forceTcp = !forceTcp
                    },
                    diagnosticsAvailable = diagnosticsAvailable,
                    diagnosticsVisible = diagnosticsVisible,
                    onToggleDiagnostics = { diagnosticsVisible = !diagnosticsVisible },
                    onPrevious = onPrevious,
                    onNext = onNext,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 30.dp, vertical = 20.dp)
                        .alpha(if (controlsVisible || telemetry.safeError != null) 1f else 0f)
                        .background(
                            color = Color.Black.copy(alpha = 0.66f),
                            shape = RoundedCornerShape(16.dp),
                        )
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(16.dp),
                        )
                        .padding(horizontal = 18.dp, vertical = 13.dp),
                )
            } ?: Text(
                text = "Playback resources released while Opah is in the background.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

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
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
) {
    val context = LocalContext.current
    val resourceName = when (request.uri.removePrefix(DOCUMENTATION_URI_PREFIX)) {
        "garden" -> "docs_camera_garden"
        "driveway" -> "docs_camera_driveway"
        "birdseye" -> "docs_camera_birdseye"
        else -> "docs_camera_entry"
    }
    val imageResource = remember(resourceName) { DocumentationResources.drawable(resourceName) }
    val player = remember { ExoPlayer.Builder(context).build().apply { playWhenReady = true } }
    DisposableEffect(player) { onDispose(player::release) }
    var controlsVisible by rememberSaveable(request.uri) { mutableStateOf(true) }
    var consumeRevealKeyUp by remember { mutableStateOf(false) }
    var muted by rememberSaveable(request.uri) { mutableStateOf(startMuted) }
    var videoOnly by rememberSaveable(request.uri) { mutableStateOf(false) }
    var forceTcp by rememberSaveable(request.uri) { mutableStateOf(request.kind == PlaybackKind.LIVE) }
    var diagnosticsVisible by rememberSaveable(request.uri) { mutableStateOf(false) }
    var pipRequested by remember(request.uri) { mutableStateOf(false) }
    var pipError by remember(request.uri) { mutableStateOf<String?>(null) }
    val recorded = request.kind == PlaybackKind.RECORDED
    val telemetry = PlaybackTelemetry(
        state = "Ready",
        isPlaying = true,
        playWhenReady = true,
        videoDecoder = "Reference hardware decoder",
        audioDecoder = if (videoOnly) null else "Reference AAC decoder",
        videoFormat = if (resourceName == "docs_camera_driveway") "H.265 / HEVC • 3840×2160" else "H.264 / AVC • 2560×1440",
        audioFormat = if (videoOnly) null else "AAC • stereo",
        videoWidth = 1920,
        videoHeight = 1080,
        firstFrameMs = 118L,
        positionMs = if (recorded) 24_000L else 0L,
        bufferedPositionMs = if (recorded) 56_000L else 0L,
        durationMs = if (recorded) 78_000L else 0L,
        seekable = recorded,
    )

    BackHandler {
        if (shouldRevealPlaybackControlsOnBack(controlsVisible, pictureInPictureActive)) {
            controlsVisible = true
        } else {
            onBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Back) {
                    false
                } else if (event.type == KeyEventType.KeyUp && consumeRevealKeyUp) {
                    consumeRevealKeyUp = false
                    true
                } else if (event.type != KeyEventType.KeyDown) {
                    false
                } else if (!controlsVisible) {
                    controlsVisible = true
                    consumeRevealKeyUp = true
                    true
                } else {
                    false
                }
            },
    ) {
        Image(
            painter = painterResource(imageResource),
            contentDescription = null,
            contentScale = ContentScale.Fit,
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
                request.detail?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                pipError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
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
            PlaybackControls(
                request = request,
                telemetry = telemetry,
                muted = muted,
                videoOnly = videoOnly,
                forceTcp = forceTcp,
                player = player,
                onBack = onBack,
                onRetry = {},
                onToggleMute = { muted = !muted },
                onToggleVideoOnly = { videoOnly = !videoOnly },
                pictureInPictureVisible = shouldOfferLivePictureInPicture(
                    sdkInt = Build.VERSION.SDK_INT,
                    hasSystemFeature = pictureInPictureAvailable,
                    kind = request.kind,
                    firstFrameRendered = true,
                ),
                pipRequested = pipRequested,
                onPopOut = {
                    pipRequested = true
                    muted = true
                    videoOnly = true
                    diagnosticsVisible = false
                    controlsVisible = false
                    val entered = onEnterPictureInPicture(
                        PictureInPictureRequest(
                            title = request.title,
                            subtitle = "Live • Video only",
                            aspectRatio = pipAspectRatio(1920, 1080),
                            sourceRectHint = null,
                        ),
                    )
                    pipRequested = false
                    if (!entered) {
                        controlsVisible = true
                        pipError = "Picture-in-picture was unavailable."
                    }
                },
                onToggleForceTcp = { forceTcp = !forceTcp },
                diagnosticsAvailable = diagnosticsAvailable,
                diagnosticsVisible = diagnosticsVisible,
                onToggleDiagnostics = { diagnosticsVisible = !diagnosticsVisible },
                onPrevious = onPrevious,
                onNext = onNext,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 30.dp, vertical = 20.dp)
                    .background(Color.Black.copy(alpha = 0.66f), RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 18.dp, vertical = 13.dp),
            )
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
    recordedPositionMs: Long,
    playWhenReady: Boolean,
): PlayerSession {
    val startedAt = SystemClock.elapsedRealtime()
    return when (request.kind) {
        PlaybackKind.LIVE -> {
            val livePlayer = application.container.livePlayerFactory.create(context)
            val player = livePlayer.player
            livePlayer.prepare(
                request.uri,
                LivePlaybackOptions(forceRtpTcp = forceTcp, videoOnly = videoOnly),
            )
            PlayerSession(player, startedAt, videoOnly, livePlayer::release)
        }

        PlaybackKind.RECORDED -> {
            val player = RecordedPlayerFactory.create(context, application.container.httpClient)
            player.disableAudio(videoOnly)
            player.setMediaItem(
                MediaItem.Builder()
                    .setUri(request.uri)
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build(),
            )
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
    muted: Boolean,
    videoOnly: Boolean,
    forceTcp: Boolean,
    player: Player,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleVideoOnly: () -> Unit,
    pictureInPictureVisible: Boolean,
    pipRequested: Boolean,
    onPopOut: () -> Unit,
    onToggleForceTcp: () -> Unit,
    diagnosticsAvailable: Boolean,
    diagnosticsVisible: Boolean,
    onToggleDiagnostics: () -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val playFocusRequester = remember { FocusRequester() }
    LaunchedEffect(player) { playFocusRequester.requestFocus() }
    val isRecorded = request.kind == PlaybackKind.RECORDED
    val availability = playbackControlAvailability(
        kind = request.kind,
        hasPrevious = onPrevious != null,
        hasNext = onNext != null,
    )
    val canSeekRecording = availability.seek && telemetry.seekable && telemetry.durationMs > 0L
    val togglePlayback = {
        if (isRecorded && telemetry.ended) {
            player.seekTo(0L)
            player.play()
        } else if (telemetry.playWhenReady) {
            player.pause()
        } else {
            player.play()
        }
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (isRecorded) {
            RecordedTimeline(
                positionMs = telemetry.positionMs,
                bufferedPositionMs = telemetry.bufferedPositionMs,
                durationMs = telemetry.durationMs,
                enabled = canSeekRecording,
                onSeek = { player.seekTo(it) },
                onTogglePlayback = togglePlayback,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediaControlButton(
                    iconRes = android.R.drawable.ic_media_previous,
                    label = "Restart",
                    onClick = { player.seekTo(0L) },
                    enabled = canSeekRecording,
                )
                MediaControlButton(
                    iconRes = android.R.drawable.ic_media_rew,
                    label = "Back 10 s",
                    onClick = {
                        player.seekTo(
                            boundedSeekPosition(player.currentPosition, telemetry.durationMs, -10_000L),
                        )
                    },
                    enabled = canSeekRecording,
                )
                MediaControlButton(
                    iconRes = if (telemetry.playWhenReady && !telemetry.ended) {
                        android.R.drawable.ic_media_pause
                    } else {
                        android.R.drawable.ic_media_play
                    },
                    label = recordedPlaybackButtonLabel(
                        playWhenReady = telemetry.playWhenReady,
                        ended = telemetry.ended,
                    ),
                    modifier = Modifier.focusRequester(playFocusRequester),
                    onClick = togglePlayback,
                )
                MediaControlButton(
                    iconRes = android.R.drawable.ic_media_ff,
                    label = "Forward 10 s",
                    onClick = {
                        player.seekTo(
                            boundedSeekPosition(player.currentPosition, telemetry.durationMs, 10_000L),
                        )
                    },
                    enabled = canSeekRecording,
                )
            }
        } else {
            Text(
                text = "LIVE  •  Real-time stream",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediaControlButton(
                    iconRes = android.R.drawable.ic_media_previous,
                    label = "Previous camera",
                    onClick = { onPrevious?.invoke() },
                    enabled = availability.previous,
                )
                MediaControlButton(
                    iconRes = if (telemetry.playWhenReady) {
                        android.R.drawable.ic_media_pause
                    } else {
                        android.R.drawable.ic_media_play
                    },
                    label = if (telemetry.playWhenReady) "Pause view" else "Resume live",
                    modifier = Modifier.focusRequester(playFocusRequester),
                    onClick = togglePlayback,
                )
                MediaControlButton(
                    iconRes = android.R.drawable.ic_media_next,
                    label = "Next camera",
                    onClick = { onNext?.invoke() },
                    enabled = availability.next,
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onBack) { Text("Back") }
            if (telemetry.safeError != null) Button(onClick = onRetry) { Text("Retry") }
            Button(onClick = onToggleMute, enabled = !videoOnly) { Text(if (muted) "Unmute" else "Mute") }
            Button(onClick = onToggleVideoOnly) { Text(if (videoOnly) "Enable audio" else "Video only") }
            if (pictureInPictureVisible) {
                Button(onClick = onPopOut, enabled = !pipRequested) {
                    Text(if (pipRequested) "Preparing…" else "Pop out")
                }
            }
            if (!isRecorded) {
                Button(onClick = onToggleForceTcp) {
                    Text(if (forceTcp) "RTP: TCP" else "RTP: Auto")
                }
            }
            if (diagnosticsAvailable) {
                Button(onClick = onToggleDiagnostics) {
                    Text(if (diagnosticsVisible) "Hide info" else "Info")
                }
            }
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
) {
    var focused by remember { mutableStateOf(false) }
    val positionFraction = timelineFraction(positionMs, durationMs)
    val bufferedFraction = timelineFraction(bufferedPositionMs, durationMs)
    val shape = RoundedCornerShape(10.dp)
    val accentColor = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
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
                    "Playback timeline, ${formatPlaybackTime(positionMs)} of ${formatPlaybackTime(durationMs)}. Left and right seek five seconds."
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
        verticalArrangement = Arrangement.spacedBy(5.dp),
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
                .height(14.dp),
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

@Composable
private fun MediaControlButton(
    @DrawableRes iconRes: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(LocalContentColor.current),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(label)
    }
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
private const val TIMELINE_SEEK_STEP_MS = 5_000L

internal fun safePlaybackError(error: PlaybackException, kind: PlaybackKind): String {
    val source = if (kind == PlaybackKind.LIVE) "RTSP stream" else "Frigate recording"
    val causeName = error.cause?.javaClass?.simpleName
    val httpStatus = error.httpStatusCode()
    if (kind == PlaybackKind.RECORDED && httpStatus == 404) {
        return "No recording is available for this review window (HTTP 404). Try another review item or verify that Frigate recording retention covers this camera and time."
    }
    val guidance = safePlaybackGuidance(error.errorCode)
    return buildString {
        append("$source failed: ${error.errorCodeName}")
        if (httpStatus != null) append(" (HTTP $httpStatus)")
        if (!causeName.isNullOrBlank()) append(" ($causeName)")
        append(". $guidance Connection details are intentionally omitted.")
    }
}

internal fun safePlaybackGuidance(errorCode: Int): String = when (errorCode) {
        PlaybackException.ERROR_CODE_DISCONNECTED,
        PlaybackException.ERROR_CODE_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> "The source is unreachable or stopped responding. Check the camera, Frigate, and network, then retry."

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        -> "The source returned malformed stream information. Check the camera or restream configuration."

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        -> "This stream format is not supported by the player. Try another configured stream."

        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
        -> "The TV could not decode this stream. Try a lower-bandwidth or differently encoded stream."

        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
        -> "Audio output failed. Retry with Video only to isolate the camera audio track."

        else -> "Check reachability, stream codec, and Frigate permissions, then retry."
}
