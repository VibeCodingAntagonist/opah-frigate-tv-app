package app.opah.tv.ui

import android.graphics.Bitmap
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.opah.tv.BuildConfig
import app.opah.tv.OpahApplication
import app.opah.tv.data.model.Camera
import app.opah.tv.data.model.MotionSearchResult
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.ReviewSeverity
import app.opah.tv.playback.PlaybackRequest
import app.opah.tv.playback.RecordedPlayerFactory
import app.opah.tv.ui.views.NativePlaybackScreenAwake
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun HistoryTimelineViewer(
    request: PlaybackRequest?,
    history: HistoryBrowserState,
    cameras: List<Camera>,
    reviewItems: List<ReviewItem>,
    motionResults: List<MotionSearchResult>,
    cachedBitmap: (String) -> Bitmap?,
    refreshBitmap: suspend (String, Int) -> Bitmap?,
    onSelectCamera: (String, Double) -> Unit,
    onOpenFullScreen: (Double, String) -> Unit,
    onFindMotion: ((Double) -> Unit)?,
    externalFocusRequester: FocusRequester? = null,
    exitFocusRequester: FocusRequester? = null,
    recordedPlayerFactory: ((android.content.Context) -> ExoPlayer)? = null,
) {
    val rangeStart = request?.recordingStartTime ?: history.hourStartSeconds ?: return
    val rangeEnd = request?.recordingEndTime ?: (rangeStart + HISTORY_HOUR_SECONDS)
    if (rangeEnd <= rangeStart) return
    val initialCursor = history.cursorTimeSeconds
        ?.coerceIn(rangeStart, rangeEnd)
        ?: history.segments.firstOrNull()?.startTime?.coerceIn(rangeStart, rangeEnd)
        ?: rangeStart
    var cursor by rememberSaveable(history.cameraName, rangeStart) { mutableDoubleStateOf(initialCursor) }
    var scale by rememberSaveable { mutableStateOf(HistoryTimelineScale.THIRTY_MINUTES) }
    var cameraStripVisible by rememberSaveable { mutableStateOf(false) }
    var detailsVisible by rememberSaveable { mutableStateOf(false) }
    var restoreTimelineFocus by remember { mutableStateOf(false) }
    var timelineFocused by remember { mutableStateOf(false) }
    var player by remember(request?.uri) { mutableStateOf<ExoPlayer?>(null) }
    var playbackError by remember(request?.uri) { mutableStateOf<String?>(null) }
    var screenAwake by remember(request?.uri) { mutableStateOf<NativePlaybackScreenAwake?>(null) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val semanticColors = LocalOpahSemanticColors.current
    val recordingColor = MaterialTheme.colorScheme.primary
    val motionResultColor = MaterialTheme.colorScheme.tertiary
    val cursorColor = MaterialTheme.colorScheme.onSurface
    val rememberedTimelineFocusRequester = remember { FocusRequester() }
    val timelineFocusRequester = externalFocusRequester ?: rememberedTimelineFocusRequester

    fun closeTimelineOverlay() {
        cameraStripVisible = false
        detailsVisible = false
        restoreTimelineFocus = true
    }

    BackHandler(enabled = cameraStripVisible || detailsVisible) {
        closeTimelineOverlay()
    }

    BackHandler(
        enabled = timelineFocused && !cameraStripVisible && !detailsVisible && exitFocusRequester != null,
    ) {
        exitFocusRequester?.requestFocus()
    }

    LaunchedEffect(restoreTimelineFocus) {
        if (restoreTimelineFocus) {
            withFrameNanos { }
            timelineFocusRequester.requestFocus()
            restoreTimelineFocus = false
        }
    }

    if (!BuildConfig.DOCUMENTATION_MODE && request != null) {
        DisposableEffect(request.uri, lifecycleOwner) {
            val application = context.applicationContext as OpahApplication
            val activePlayer = (recordedPlayerFactory?.invoke(context) ?: RecordedPlayerFactory.create(
                context,
                application.container.httpClient,
                preferSoftwareVideoDecoder = false,
            )).apply {
                setMediaItem(
                    MediaItem.Builder()
                        .setUri(request.uri)
                        .apply { recordedMediaMimeType(request.uri)?.let(::setMimeType) }
                        .build(),
                )
                playWhenReady = true
                prepare()
            }
            val listener = object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    playbackError = safePlaybackError(error, request.kind)
                }
            }
            activePlayer.addListener(listener)
            player = activePlayer
            val lifecycleObserver = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> activePlayer.play()
                    Lifecycle.Event.ON_STOP -> activePlayer.pause()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
                activePlayer.removeListener(listener)
                activePlayer.release()
                if (player === activePlayer) player = null
            }
        }
    }

    LaunchedEffect(player, rangeStart, rangeEnd) {
        val activePlayer = player ?: return@LaunchedEffect
        while (true) {
            if (activePlayer.isPlaying) {
                cursor = (rangeStart + activePlayer.currentPosition / 1_000.0)
                    .coerceIn(rangeStart, rangeEnd)
            }
            delay(500)
        }
    }

    val moveCursor: (Double) -> Unit = { delta ->
        cursor = boundedHistoryCursor(cursor, delta, rangeStart, rangeEnd)
        player?.seekTo(((cursor - rangeStart) * 1_000).toLong().coerceAtLeast(0L))
    }
    val window = historyTimelineWindow(cursor, scale, rangeStart, rangeEnd)
    val cameraName = history.cameraName
    val currentReview = reviewItems
        .filter { it.camera == cameraName && cursor >= it.startTime && cursor <= (it.endTime ?: it.startTime + 30) }
        .maxByOrNull(ReviewItem::startTime)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(218.dp)
                .clip(MediaTileShape)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (BuildConfig.DOCUMENTATION_MODE && cameraName != null) {
                CameraSnapshot(
                    cameraName = cameraName,
                    cachedBitmap = { cachedBitmap(cameraName) },
                    refreshBitmap = { refreshBitmap(cameraName, 540) },
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (player != null && request != null) {
                val awake = rememberPlaybackAwakeHost(request.uri)
                SideEffect { screenAwake = awake }
                PlaybackAwakeStream(awake, player)
                AndroidView(
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            useController = false
                            resizeMode = videoResizeMode(stretched = false)
                            this.player = player
                        }
                    },
                    update = { it.player = player },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text("Getting saved video ready…", color = Color.White.copy(alpha = 0.8f))
            }
            playbackError?.let { error ->
                Text(
                    error,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(14.dp)
                        .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                formatHistoryTime(cursor),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.64f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(OpahDesignTokens.MediaCornerRadius))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))
                .border(
                    if (timelineFocused) 3.dp else 1.dp,
                    if (timelineFocused) {
                        semanticColors.focus
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    },
                    RoundedCornerShape(OpahDesignTokens.MediaCornerRadius),
                )
                .focusRequester(timelineFocusRequester)
                .onFocusChanged { timelineFocused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (!timelineFocused) return@onPreviewKeyEvent false
                    when {
                        event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft -> {
                            moveCursor(-acceleratedHistorySeekSeconds(scale, event.nativeKeyEvent.repeatCount))
                            true
                        }
                        event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight -> {
                            moveCursor(acceleratedHistorySeekSeconds(scale, event.nativeKeyEvent.repeatCount))
                            true
                        }
                        event.type == KeyEventType.KeyUp && event.key == Key.DirectionCenter -> {
                            player?.let {
                                if (it.isPlaying) {
                                    it.pause()
                                } else {
                                    screenAwake?.explicitAction()
                                    it.play()
                                }
                            }
                            true
                        }
                        event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp -> {
                            cameraStripVisible = true
                            true
                        }
                        event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown -> {
                            detailsVisible = true
                            true
                        }
                        else -> false
                    }
                }
                .semantics {
                    contentDescription =
                        "History timeline at ${formatHistoryTime(cursor)}. Left and right move through time. " +
                        "Hold to move faster. Center plays or pauses. Up shows cameras. Down shows details. " +
                        "Back returns to history controls."
                }
                .focusable()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatHistoryTime(window.start), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                Text(scale.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(formatHistoryTime(window.endInclusive), style = MaterialTheme.typography.bodySmall)
            }
            Canvas(modifier = Modifier.fillMaxWidth().height(62.dp)) {
                val duration = (window.endInclusive - window.start).coerceAtLeast(1.0)
                fun x(timestamp: Double): Float =
                    (((timestamp - window.start) / duration).coerceIn(0.0, 1.0) * size.width).toFloat()
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.22f),
                    topLeft = Offset(0f, 12f),
                    size = Size(size.width, 22f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f),
                )
                history.segments.forEach { segment ->
                    val start = segment.startTime.coerceAtLeast(window.start)
                    val end = segment.endTime.coerceAtMost(window.endInclusive)
                    if (end > start) {
                        drawRoundRect(
                            color = recordingColor,
                            topLeft = Offset(x(start), 12f),
                            size = Size((x(end) - x(start)).coerceAtLeast(2f), 22f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
                        )
                    }
                }
                history.motion.filter { it.startTime in window }.forEach { motion ->
                    val intensity = (motion.motion / 100.0).coerceIn(0.12, 1.0).toFloat()
                    drawLine(
                        color = semanticColors.detection.copy(alpha = intensity),
                        start = Offset(x(motion.startTime), 38f),
                        end = Offset(x(motion.startTime), 38f + 18f * intensity),
                        strokeWidth = 3f,
                    )
                }
                reviewItems.filter { it.camera == cameraName && it.startTime in window }.forEach { item ->
                    drawCircle(
                        color = if (item.severity == ReviewSeverity.ALERT) semanticColors.alert else semanticColors.detection,
                        radius = if (item.severity == ReviewSeverity.ALERT) 6f else 4f,
                        center = Offset(x(item.startTime), 7f),
                    )
                }
                motionResults.filter { it.timestamp in window }.forEach { result ->
                    drawCircle(
                        color = motionResultColor,
                        radius = 5f,
                        center = Offset(x(result.timestamp), 48f),
                    )
                }
                drawLine(
                    color = cursorColor,
                    start = Offset(x(cursor), 0f),
                    end = Offset(x(cursor), size.height),
                    strokeWidth = 3f,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (player?.isPlaying == true) "Playing" else "Paused",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "← → move  •  hold to move faster  •  OK play/pause  •  ↑ cameras  •  ↓ details  •  Back controls",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

    }

    if (cameraStripVisible) {
        HistoryCameraOverlay(
            cameras = cameras,
            cameraName = cameraName,
            cursor = cursor,
            cachedBitmap = cachedBitmap,
            refreshBitmap = refreshBitmap,
            onDismiss = ::closeTimelineOverlay,
            onSelected = { selected ->
                closeTimelineOverlay()
                onSelectCamera(selected, cursor)
            },
        )
    }

    if (detailsVisible) {
        HistoryDetailsOverlay(
            scale = scale,
            cursor = cursor,
            currentReview = currentReview,
            motionSearchAvailable = onFindMotion != null,
            onScale = { scale = it },
            onOpenFullScreen = {
                closeTimelineOverlay()
                onOpenFullScreen(cursor, "history:timeline")
            },
            onFindMotion = {
                closeTimelineOverlay()
                onFindMotion?.invoke(cursor)
            },
            onDismiss = ::closeTimelineOverlay,
        )
    }
}

@Composable
private fun HistoryCameraOverlay(
    cameras: List<Camera>,
    cameraName: String?,
    cursor: Double,
    cachedBitmap: (String) -> Bitmap?,
    refreshBitmap: suspend (String, Int) -> Bitmap?,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    val initialFocusRequester = remember(cameraName) { FocusRequester() }
    LaunchedEffect(cameraName) {
        withFrameNanos { }
        initialFocusRequester.requestFocus()
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.68f))
                .padding(horizontal = 80.dp, vertical = 120.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 1500.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        RoundedCornerShape(16.dp),
                    )
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Choose camera", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Keep watching from ${formatHistoryTime(cursor)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(cameras, key = Camera::name) { camera ->
                        MediaTile(
                            focusKey = "history:camera-overlay:${camera.name}",
                            accessibilityLabel = "${camera.displayName} at ${formatHistoryTime(cursor)}",
                            onClick = { onSelected(camera.name) },
                            selected = camera.name == cameraName,
                            externalFocusRequester = initialFocusRequester.takeIf {
                                camera.name == cameraName || cameraName == null && camera == cameras.firstOrNull()
                            },
                            modifier = Modifier.width(250.dp),
                        ) {
                            Column {
                                CameraSnapshot(
                                    cameraName = camera.name,
                                    cachedBitmap = { cachedBitmap(camera.name) },
                                    refreshBitmap = { refreshBitmap(camera.name, 180) },
                                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                                )
                                Text(
                                    camera.displayName,
                                    modifier = Modifier.padding(10.dp),
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryDetailsOverlay(
    scale: HistoryTimelineScale,
    cursor: Double,
    currentReview: ReviewItem?,
    motionSearchAvailable: Boolean,
    onScale: (HistoryTimelineScale) -> Unit,
    onOpenFullScreen: () -> Unit,
    onFindMotion: () -> Unit,
    onDismiss: () -> Unit,
) {
    val initialFocusRequester = remember(scale) { FocusRequester() }
    LaunchedEffect(scale) {
        withFrameNanos { }
        initialFocusRequester.requestFocus()
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 620.dp, max = 860.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    RoundedCornerShape(16.dp),
                )
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("History details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    currentReview?.summary?.title
                        ?: currentReview?.objects?.firstOrNull()?.let(::friendlyActivityName)
                        ?: "Saved video",
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    currentReview?.summary?.shortSummary ?: formatHistoryTime(cursor),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Timeline scale", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HistoryTimelineScale.entries.forEach { option ->
                    FocusableSurface(
                        focusKey = "history:scale:${option.name}",
                        restoreFocusKey = null,
                        onFocusRestored = {},
                        onClick = { onScale(option) },
                        selected = scale == option,
                        accessibilityLabel = "${option.label} timeline scale",
                        externalFocusRequester = initialFocusRequester.takeIf { scale == option },
                        style = FocusableSurfaceStyle.SEGMENTED_TAB,
                    ) {
                        Text(option.label, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryAction(
                    focusKey = "history:full-screen",
                    label = "Full screen",
                    onClick = onOpenFullScreen,
                )
                if (motionSearchAvailable) {
                    SecondaryAction(
                        focusKey = "history:motion-here",
                        label = "Find motion here",
                        onClick = onFindMotion,
                    )
                }
            }
        }
    }
}
