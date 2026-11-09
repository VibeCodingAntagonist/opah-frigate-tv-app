package app.opah.tv.ui

import android.graphics.Bitmap
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.opah.tv.OpahApplication
import app.opah.tv.data.model.Camera
import app.opah.tv.monitor.MonitorPhase
import app.opah.tv.monitor.MonitorPresentationKind
import app.opah.tv.monitor.MonitorPreset
import app.opah.tv.playback.compatibility.PlaybackCompatibilityState
import app.opah.tv.playback.compatibility.PlaybackResult
import app.opah.tv.playback.media3.PlayerViewMedia3VideoOutputTarget
import app.opah.tv.playback.media3.SingleLivePlaybackRequestId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect

@Composable
internal fun MonitorModeScreen(
    state: MonitorModeUiState,
    stretchedCameraNames: Set<String>,
    onPreset: (MonitorPreset) -> Unit,
    onManualCamera: (String) -> Unit,
    onPlaybackReady: () -> Unit,
    onPlaybackFailed: () -> Unit,
    onKeepScreenAwake: (Boolean) -> Unit,
    onAudioEnabled: (Boolean) -> Unit,
    onExitMinutes: (Int?) -> Unit,
    onBack: () -> Unit,
    cachedBitmap: (String) -> Bitmap?,
    refreshBitmap: suspend (String, Int) -> Bitmap?,
) {
    val visibleCameras = state.cameras.filter { it.name in state.visibleCameraIds }
    val promotedCamera = state.arbitration.promotion?.cameraId?.let { cameraId ->
        visibleCameras.firstOrNull { it.name == cameraId }
    }
    val patrolCamera = state.arbitration.patrolCameraId?.let { cameraId ->
        visibleCameras.firstOrNull { it.name == cameraId }
    }
    val localView = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnBack by rememberUpdatedState(onBack)
    val focusRequester = remember { FocusRequester() }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionToken by remember { mutableIntStateOf(0) }
    var consumeRevealKeyUp by remember { mutableStateOf(false) }

    DisposableEffect(localView, state.keepScreenAwake) {
        val previous = localView.keepScreenOn
        localView.keepScreenOn = state.keepScreenAwake
        onDispose { localView.keepScreenOn = previous }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) currentOnBack()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(interactionToken) {
        delay(MONITOR_CONTROLS_TIMEOUT_MILLIS)
        controlsVisible = false
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    BackHandler {
        when (playbackBackAction(controlsVisible, diagnosticsVisible = false, pictureInPictureActive = false)) {
            PlaybackBackAction.HIDE_OVERLAY -> {
                controlsVisible = false
                consumeRevealKeyUp = false
                focusRequester.requestFocus()
            }
            PlaybackBackAction.EXIT -> onBack()
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
                    interactionToken += 1
                    if (!controlsVisible) {
                        controlsVisible = true
                        consumeRevealKeyUp = true
                        true
                    } else {
                        false
                    }
                }
            }
            .focusRequester(focusRequester)
            .focusable(),
    ) {
        when {
            promotedCamera != null &&
                state.arbitration.promotion.presentation == MonitorPresentationKind.LIVE &&
                state.liveCompatibilityRequestId != null -> MonitorLiveVideo(
                requestId = state.liveCompatibilityRequestId,
                videoOnly = !state.audioEnabled,
                stretched = promotedCamera.name in stretchedCameraNames,
                onReady = onPlaybackReady,
                onFailed = onPlaybackFailed,
            )
            promotedCamera != null -> MonitorSnapshot(
                camera = promotedCamera,
                stretched = promotedCamera.name in stretchedCameraNames,
                cachedBitmap = cachedBitmap,
                refreshBitmap = refreshBitmap,
                modifier = Modifier.fillMaxSize(),
            )
            patrolCamera != null -> MonitorSnapshot(
                camera = patrolCamera,
                stretched = patrolCamera.name in stretchedCameraNames,
                cachedBitmap = cachedBitmap,
                refreshBitmap = refreshBitmap,
                modifier = Modifier.fillMaxSize(),
            )
            visibleCameras.isNotEmpty() -> MonitorSnapshotGrid(
                cameras = visibleCameras,
                stretchedCameraNames = stretchedCameraNames,
                onManualCamera = onManualCamera,
                cachedBitmap = cachedBitmap,
                refreshBitmap = refreshBitmap,
            )
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No cameras are available in this View", color = Color.White)
            }
        }

        if (controlsVisible) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(14.dp)
                    .background(Color.Black.copy(alpha = 0.74f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(state.title, color = Color.White, fontWeight = FontWeight.Bold)
                Text(
                    monitorStatus(state, promotedCamera, patrolCamera),
                    color = Color.White.copy(alpha = 0.74f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp)
                    .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(14.dp))
                    .padding(9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    MonitorPreset.entries.forEach { preset ->
                        MonitorControl(
                            label = preset.displayLabel(),
                            selected = state.preset == preset,
                            onClick = { onPreset(preset) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    MonitorControl(
                        label = if (state.keepScreenAwake) "Screen awake" else "Allow sleep",
                        selected = state.keepScreenAwake,
                        onClick = { onKeepScreenAwake(!state.keepScreenAwake) },
                    )
                    MonitorControl(
                        label = if (state.audioEnabled) "Audio on" else "Muted",
                        selected = state.audioEnabled,
                        onClick = { onAudioEnabled(!state.audioEnabled) },
                    )
                    listOf(null to "No timer", 30 to "30 min", 60 to "1 hour").forEach { (minutes, label) ->
                        MonitorControl(
                            label = label,
                            selected = state.exitAfterMinutes == minutes,
                            onClick = { onExitMinutes(minutes) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonitorSnapshotGrid(
    cameras: List<Camera>,
    stretchedCameraNames: Set<String>,
    onManualCamera: (String) -> Unit,
    cachedBitmap: (String) -> Bitmap?,
    refreshBitmap: suspend (String, Int) -> Bitmap?,
) {
    @Composable
    fun Tile(camera: Camera, modifier: Modifier) {
        FocusCard(
            focusKey = "monitor:camera:${camera.name}",
            restoreFocusKey = null,
            onFocusRestored = {},
            onClick = { onManualCamera(camera.name) },
            accessibilityLabel = "Open ${camera.displayName}",
            modifier = modifier,
        ) {
            MonitorSnapshot(
                camera,
                camera.name in stretchedCameraNames,
                cachedBitmap,
                refreshBitmap,
                Modifier.fillMaxSize(),
            )
        }
    }
    when (cameras.size) {
        1 -> Tile(cameras.first(), Modifier.fillMaxSize())
        2 -> Row(Modifier.fillMaxSize()) {
            cameras.forEach { Tile(it, Modifier.weight(1f).fillMaxHeight()) }
        }
        3 -> Row(Modifier.fillMaxSize()) {
            Tile(cameras[0], Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Tile(cameras[1], Modifier.weight(1f).fillMaxWidth())
                Tile(cameras[2], Modifier.weight(1f).fillMaxWidth())
            }
        }
        else -> Column(Modifier.fillMaxSize()) {
            cameras.take(4).chunked(2).forEach { row ->
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    row.forEach { Tile(it, Modifier.weight(1f).fillMaxHeight()) }
                }
            }
        }
    }
}

@Composable
private fun MonitorSnapshot(
    camera: Camera,
    stretched: Boolean,
    cachedBitmap: (String) -> Bitmap?,
    refreshBitmap: suspend (String, Int) -> Bitmap?,
    modifier: Modifier,
) {
    Box(modifier.background(Color.Black)) {
        CameraSnapshot(
            cameraName = camera.name,
            cachedBitmap = { cachedBitmap(camera.name) },
            refreshBitmap = { refreshBitmap(camera.name, 720) },
            contentScale = if (stretched) ContentScale.FillBounds else ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            camera.displayName,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(13.dp)
                .background(Color.Black.copy(alpha = 0.66f), RoundedCornerShape(8.dp))
                .padding(horizontal = 11.dp, vertical = 7.dp),
            color = Color.White,
        )
    }
}

@Composable
private fun MonitorControl(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FocusCard(
        focusKey = "monitor:control:$label",
        restoreFocusKey = null,
        onFocusRestored = {},
        onClick = onClick,
        accessibilityLabel = label,
        containerColor = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        } else {
            Color.White.copy(alpha = 0.10f)
        },
        modifier = Modifier.size(width = 104.dp, height = 44.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MonitorLiveVideo(
    requestId: String,
    videoOnly: Boolean,
    stretched: Boolean,
    onReady: () -> Unit,
    onFailed: () -> Unit,
) {
    val context = LocalContext.current
    val application = context.applicationContext as OpahApplication
    val lifecycleOwner = LocalLifecycleOwner.current
    var playerView by remember(requestId) { mutableStateOf<PlayerView?>(null) }
    var readyReported by remember(requestId, videoOnly) { mutableStateOf(false) }
    var failureReported by remember(requestId, videoOnly) { mutableStateOf(false) }

    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                useController = false
                keepScreenOn = false
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                resizeMode = videoResizeMode(stretched)
                playerView = this
            }
        },
        update = { it.resizeMode = videoResizeMode(stretched) },
        modifier = Modifier.fillMaxSize(),
    )

    LaunchedEffect(requestId, videoOnly, playerView, lifecycleOwner) {
        val output = playerView ?: return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val session = try {
                application.container.singleLivePlaybackCoordinator.open(
                    requestId = SingleLivePlaybackRequestId(requestId),
                    output = PlayerViewMedia3VideoOutputTarget(
                        playerView = output,
                        onAttached = { player -> player.volume = if (videoOnly) 0f else 1f },
                        onDetached = {},
                    ),
                    videoOnly = videoOnly,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!failureReported) {
                    failureReported = true
                    onFailed()
                }
                return@repeatOnLifecycle
            }
            try {
                session.snapshots.collect { snapshot ->
                    when (val playback = snapshot.state) {
                        is PlaybackCompatibilityState.Verified,
                        is PlaybackCompatibilityState.UnpersistedLive,
                        is PlaybackCompatibilityState.Persisting,
                        -> if (!readyReported) {
                            readyReported = true
                            onReady()
                        }
                        is PlaybackCompatibilityState.Finished -> if (
                            playback.result !is PlaybackResult.VerifiedLive &&
                            playback.result !is PlaybackResult.UnpersistedLive &&
                            playback.result !is PlaybackResult.Cancelled &&
                            !failureReported
                        ) {
                            failureReported = true
                            onFailed()
                        }
                        else -> Unit
                    }
                }
            } finally {
                withContext(NonCancellable) { session.close() }
            }
        }
    }
}

private fun monitorStatus(
    state: MonitorModeUiState,
    promotedCamera: Camera?,
    patrolCamera: Camera?,
): String = state.statusMessage ?: when {
    state.arbitration.phase == MonitorPhase.RECOVERING -> "Reconnecting"
    promotedCamera != null -> "Watching ${promotedCamera.displayName}"
    patrolCamera != null -> "Patrolling ${patrolCamera.displayName}"
    state.preset == MonitorPreset.FIXED -> "Fixed View"
    else -> "Watching for activity"
}

private fun MonitorPreset.displayLabel(): String = when (this) {
    MonitorPreset.FIXED -> "Fixed"
    MonitorPreset.CALM -> "Calm"
    MonitorPreset.ACTIVE -> "Active"
    MonitorPreset.PATROL -> "Patrol"
}

private const val MONITOR_CONTROLS_TIMEOUT_MILLIS = 6_000L
