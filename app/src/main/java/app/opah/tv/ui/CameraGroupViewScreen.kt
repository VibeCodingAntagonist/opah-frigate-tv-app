package app.opah.tv.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.opah.tv.BuildConfig
import app.opah.tv.OpahApplication
import app.opah.tv.PictureInPictureRequest
import app.opah.tv.R
import app.opah.tv.pipAspectRatio
import app.opah.tv.playback.LivePlaybackOptions
import app.opah.tv.playback.LivePlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal enum class CameraGroupLayout { SIDE_BY_SIDE, FEATURED, GRID }

internal enum class CameraGroupBackAction { HIDE_CONTROLS, EXIT }

internal fun cameraGroupLayout(cameraCount: Int): CameraGroupLayout = when (cameraCount) {
    2 -> CameraGroupLayout.SIDE_BY_SIDE
    3 -> CameraGroupLayout.FEATURED
    4 -> CameraGroupLayout.GRID
    else -> error("Camera groups require two to four cameras")
}

internal fun cameraGroupBackAction(
    popOutControlVisible: Boolean,
    pictureInPictureActive: Boolean,
): CameraGroupBackAction = if (popOutControlVisible && !pictureInPictureActive) {
    CameraGroupBackAction.HIDE_CONTROLS
} else {
    CameraGroupBackAction.EXIT
}

@Composable
internal fun CameraGroupViewScreen(
    state: CameraGroupViewUiState,
    preferRtpTcp: Boolean,
    stretchedCameraNames: Set<String>,
    pictureInPictureAvailable: Boolean,
    pictureInPictureActive: Boolean,
    onEnterPictureInPicture: (PictureInPictureRequest) -> Boolean,
    onStartMonitor: () -> Unit,
    onBack: () -> Unit,
    cachedBitmap: (String) -> Bitmap?,
    refreshBitmap: suspend (String, Int) -> Bitmap?,
) {
    val streamKey = state.streams.joinToString("|") { it.camera.name }
    val playerFocusRequester = remember(streamKey) { FocusRequester() }
    val popOutFocusRequester = remember(streamKey) { FocusRequester() }
    val monitorFocusRequester = remember(streamKey) { FocusRequester() }
    var overlayVisible by remember(streamKey) { mutableStateOf(!pictureInPictureActive) }
    var interactionToken by remember(streamKey) { mutableIntStateOf(0) }
    var consumeRevealKeyUp by remember(streamKey) { mutableStateOf(false) }
    var groupBounds by remember(streamKey) { mutableStateOf<Rect?>(null) }
    var pictureInPictureMessage by remember(streamKey) { mutableStateOf<String?>(null) }
    var readyCameraNames by remember(streamKey) {
        mutableStateOf<Set<String>>(
            if (BuildConfig.DOCUMENTATION_MODE) {
                state.streams.mapTo(mutableSetOf()) { it.camera.name }
            } else {
                emptySet()
            },
        )
    }
    val cameraNames = remember(streamKey) { state.streams.mapTo(linkedSetOf()) { it.camera.name } }
    val canOfferPopOut = state.streams.size == 2 && pictureInPictureAvailable
    val popOutAvailable = canOfferPopOut && readyCameraNames.containsAll(cameraNames)

    LaunchedEffect(streamKey, pictureInPictureActive) {
        if (pictureInPictureActive) {
            overlayVisible = false
        } else {
            overlayVisible = true
            interactionToken += 1
        }
    }
    LaunchedEffect(overlayVisible, popOutAvailable, pictureInPictureActive) {
        if (pictureInPictureActive) return@LaunchedEffect
        withFrameNanos { }
        if (overlayVisible) {
            monitorFocusRequester.requestFocus()
        } else {
            playerFocusRequester.requestFocus()
        }
    }
    LaunchedEffect(streamKey, interactionToken, pictureInPictureActive) {
        if (!pictureInPictureActive) {
            delay(GROUP_CONTROLS_TIMEOUT_MS)
            overlayVisible = false
        }
    }

    BackHandler {
        when (cameraGroupBackAction(overlayVisible, pictureInPictureActive)) {
            CameraGroupBackAction.HIDE_CONTROLS -> overlayVisible = false
            CameraGroupBackAction.EXIT -> onBack()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Back || pictureInPictureActive) {
                    false
                } else if (event.type == KeyEventType.KeyUp && consumeRevealKeyUp) {
                    consumeRevealKeyUp = false
                    true
                } else if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    interactionToken += 1
                    if (!overlayVisible) {
                        overlayVisible = true
                        consumeRevealKeyUp = true
                        true
                    } else {
                        false
                    }
                }
            }
            .focusRequester(playerFocusRequester)
            .focusable(enabled = !pictureInPictureActive && !overlayVisible),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInWindow()
                    groupBounds = Rect(
                        bounds.left.roundToInt(),
                        bounds.top.roundToInt(),
                        bounds.right.roundToInt(),
                        bounds.bottom.roundToInt(),
                    )
                },
        ) {
            CameraGroupGrid(
                streams = state.streams,
                preferRtpTcp = preferRtpTcp,
                stretchedCameraNames = stretchedCameraNames,
                showLabels = overlayVisible && !pictureInPictureActive,
                onPlaybackReady = { cameraName, ready ->
                    readyCameraNames = if (ready) {
                        readyCameraNames + cameraName
                    } else {
                        readyCameraNames - cameraName
                    }
                },
                cachedBitmap = cachedBitmap,
                refreshBitmap = refreshBitmap,
            )

            if (overlayVisible && !pictureInPictureActive) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(state.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (state.decoderWarning) {
                        Text(
                            "This TV may not play every camera at once",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CameraGroupMonitorControl(
                        focusRequester = monitorFocusRequester,
                        onFocused = { interactionToken += 1 },
                        onClick = onStartMonitor,
                    )
                    if (popOutAvailable) {
                        CameraGroupPopOutControl(
                            focusRequester = popOutFocusRequester,
                            onFocused = { interactionToken += 1 },
                            onClick = {
                                interactionToken += 1
                                val entered = onEnterPictureInPicture(
                                    PictureInPictureRequest(
                                        title = state.title,
                                        subtitle = "${state.streams.size} cameras",
                                        aspectRatio = pipAspectRatio(16, 9),
                                        sourceRectHint = groupBounds,
                                    ),
                                )
                                if (entered) {
                                    pictureInPictureMessage = null
                                    overlayVisible = false
                                } else {
                                    pictureInPictureMessage = "Picture in Picture is not available"
                                }
                            },
                        )
                    }
                }
            }
        }

        pictureInPictureMessage?.takeIf { !pictureInPictureActive }?.let { message ->
            Text(
                message,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(18.dp)
                    .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun CameraGroupGrid(
    streams: List<CameraGroupStream>,
    preferRtpTcp: Boolean,
    stretchedCameraNames: Set<String>,
    showLabels: Boolean,
    onPlaybackReady: (String, Boolean) -> Unit,
    cachedBitmap: (String) -> Bitmap?,
    refreshBitmap: suspend (String, Int) -> Bitmap?,
) {
    @Composable
    fun Tile(index: Int, modifier: Modifier) {
        val stream = streams[index]
        CameraGroupTile(
            stream = stream,
            index = index,
            preferRtpTcp = preferRtpTcp,
            stretched = stream.camera.name in stretchedCameraNames,
            showLabel = showLabels,
            onPlaybackReady = onPlaybackReady,
            cachedBitmap = cachedBitmap,
            refreshBitmap = refreshBitmap,
            modifier = modifier,
        )
    }

    when (cameraGroupLayout(streams.size)) {
        CameraGroupLayout.SIDE_BY_SIDE -> Row(modifier = Modifier.fillMaxSize()) {
            Tile(0, Modifier.weight(1f).fillMaxHeight())
            Tile(1, Modifier.weight(1f).fillMaxHeight())
        }

        CameraGroupLayout.FEATURED -> Row(modifier = Modifier.fillMaxSize()) {
            Tile(0, Modifier.weight(1f).fillMaxHeight())
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Tile(1, Modifier.weight(1f).fillMaxWidth())
                Tile(2, Modifier.weight(1f).fillMaxWidth())
            }
        }

        CameraGroupLayout.GRID -> Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Tile(0, Modifier.weight(1f).fillMaxHeight())
                Tile(1, Modifier.weight(1f).fillMaxHeight())
            }
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Tile(2, Modifier.weight(1f).fillMaxHeight())
                Tile(3, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun CameraGroupTile(
    stream: CameraGroupStream,
    index: Int,
    preferRtpTcp: Boolean,
    stretched: Boolean,
    showLabel: Boolean,
    onPlaybackReady: (String, Boolean) -> Unit,
    cachedBitmap: (String) -> Bitmap?,
    refreshBitmap: suspend (String, Int) -> Bitmap?,
    modifier: Modifier,
) {
    Box(
        modifier = modifier.background(Color.Black),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (BuildConfig.DOCUMENTATION_MODE) {
                CameraSnapshot(
                    cameraName = stream.camera.name,
                    cachedBitmap = { cachedBitmap(stream.camera.name) },
                    refreshBitmap = { refreshBitmap(stream.camera.name, 420) },
                    contentScale = if (stretched) ContentScale.FillBounds else ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                CameraGroupLiveVideo(
                    uri = stream.uri,
                    preferRtpTcp = preferRtpTcp,
                    stretched = stretched,
                    startDelayMillis = index * NEXT_PLAYER_START_DELAY_MS,
                    onReady = { ready -> onPlaybackReady(stream.camera.name, ready) },
                )
            }
            if (showLabel) {
                Text(
                    text = stream.camera.displayName,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 11.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}

@Composable
private fun CameraGroupPopOutControl(
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.74f), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FocusCard(
            focusKey = "group:control:pip",
            restoreFocusKey = null,
            onFocusRestored = {},
            onClick = onClick,
            accessibilityLabel = "Pop out camera group",
            externalFocusRequester = focusRequester,
            onFocused = { onFocused() },
            containerColor = Color.White.copy(alpha = 0.10f),
            modifier = Modifier.size(44.dp),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(R.drawable.ic_picture_in_picture),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(LocalContentColor.current),
                    modifier = Modifier.size(21.dp),
                )
            }
        }
        Text("Pop out", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CameraGroupMonitorControl(
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.74f), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FocusCard(
            focusKey = "group:control:monitor",
            restoreFocusKey = null,
            onFocusRestored = {},
            onClick = onClick,
            accessibilityLabel = "Start Monitor Mode",
            externalFocusRequester = focusRequester,
            onFocused = { onFocused() },
            containerColor = Color.White.copy(alpha = 0.10f),
            modifier = Modifier.size(width = 92.dp, height = 44.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Monitor", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Stay aware", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun CameraGroupLiveVideo(
    uri: String,
    preferRtpTcp: Boolean,
    stretched: Boolean,
    startDelayMillis: Int,
    onReady: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val application = context.applicationContext as OpahApplication
    val lifecycleOwner = LocalLifecycleOwner.current
    var lifecycleStarted by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    var lifecycleGeneration by remember(lifecycleOwner) { mutableIntStateOf(0) }
    var livePlayer by remember(uri) { mutableStateOf<LivePlayer?>(null) }
    var errorMessage by remember(uri) { mutableStateOf<String?>(null) }
    val currentOnReady by rememberUpdatedState(onReady)

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    lifecycleStarted = true
                    lifecycleGeneration += 1
                }
                Lifecycle.Event.ON_STOP -> lifecycleStarted = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uri, preferRtpTcp, lifecycleStarted, lifecycleGeneration) {
        if (!lifecycleStarted) return@LaunchedEffect
        var created: LivePlayer? = null
        try {
            if (startDelayMillis > 0) delay(startDelayMillis.toLong())
            created = application.container.livePlayerFactory.create(
                context,
                preferSoftwareVideoDecoder = false,
            )
            created.player.volume = 0f
            created.player.addListener(
                object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            errorMessage = null
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        currentOnReady(true)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        errorMessage = "Live video unavailable"
                        currentOnReady(false)
                    }
                },
            )
            created.prepare(
                uri,
                LivePlaybackOptions(forceRtpTcp = preferRtpTcp, videoOnly = true),
            )
            livePlayer = created
            awaitCancellation()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            errorMessage = "Live video unavailable"
            currentOnReady(false)
        } finally {
            if (livePlayer === created) livePlayer = null
            created?.release()
            currentOnReady(false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        livePlayer?.let { activePlayer ->
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        useController = false
                        keepScreenOn = true
                        isFocusable = false
                        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        resizeMode = videoResizeMode(stretched)
                        player = activePlayer.player
                    }
                },
                update = {
                    it.player = activePlayer.player
                    it.resizeMode = videoResizeMode(stretched)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (livePlayer == null && errorMessage == null) {
            Text("Opening live video…", color = Color.White.copy(alpha = 0.72f))
        }
        errorMessage?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
        }
    }
}

private const val NEXT_PLAYER_START_DELAY_MS = 350
private const val GROUP_CONTROLS_TIMEOUT_MS = 3_500L
