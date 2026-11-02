package app.opah.tv.ui.views

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import app.opah.tv.playback.AuthenticatedHttpLive
import app.opah.tv.playback.HttpsLivePlayer
import app.opah.tv.playback.LivePlaybackOptions
import app.opah.tv.playback.liveFailureStatus
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.opah.tv.OpahApplication
import app.opah.tv.R
import app.opah.tv.data.model.Camera
import app.opah.tv.monitor.MonitorPhase
import app.opah.tv.monitor.MonitorPresentationKind
import app.opah.tv.monitor.MonitorPreset
import app.opah.tv.playback.compatibility.PlaybackCompatibilityState
import app.opah.tv.playback.compatibility.PlaybackResult
import app.opah.tv.playback.media3.PlayerViewMedia3VideoOutputTarget
import app.opah.tv.playback.media3.SingleLivePlaybackRequestId
import app.opah.tv.playback.media3.ProcessMedia3VideoOutputRegistry
import app.opah.tv.playback.media3.awaitExclusiveVideoOutput
import app.opah.tv.playback.compatibility.PlaybackAttemptId
import app.opah.tv.playback.compatibility.PlaybackSessionId
import java.util.UUID
import app.opah.tv.ui.MonitorModeUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
@SuppressLint("ViewConstructor", "SetTextI18n")
internal class NativeMonitorSurface(
    private val activity: ComponentActivity,
    initialState: MonitorModeUiState,
    private val cachedBitmap: (String) -> Bitmap?,
    private val refreshBitmap: suspend (String, Int) -> Bitmap?,
    private val onPreset: (MonitorPreset) -> Unit,
    private val onManualCamera: (String) -> Unit,
    private val onPlaybackReady: () -> Unit,
    private val onPlaybackFailed: () -> Unit,
    private val onKeepScreenAwake: (Boolean) -> Unit,
    private val onAudioEnabled: (Boolean) -> Unit,
    private val onExitMinutes: (Int?) -> Unit,
    private val onBack: () -> Unit,
    private val imageLoadedAtMillis: (String) -> Long? = { null },
    private val prepareHttpLive: (String) -> AuthenticatedHttpLive? = { null },
) : FrameLayout(activity) {
    private val application = activity.application as OpahApplication
    private val visualHost = FrameLayout(activity)
    private val title = TextView(activity)
    private val status = TextView(activity)
    private val header = LinearLayout(activity)
    private val controls = LinearLayout(activity)
    private val controlsScroller = HorizontalScrollView(activity)
    private val presetButtons = linkedMapOf<MonitorPreset, TextView>()
    private val cameraLabels = mutableListOf<View>()
    private val previewBindings = mutableMapOf<String, MonitorPreviewBinding>()
    private val recoveryStatus = statusLabel("").apply { visibility = View.GONE }
    private val audioButton: TextView
    private val awakeButton: TextView
    private val exitButton: TextView
    private val gridButton: TextView
    private var state = initialState
    private var visualKey: String? = null
    private var visualJob: Job? = null
    private var chromeHideJob: Job? = null
    private var chromeVisible = true
    private var revealKeyCode: Int? = null
    private var heldKeyCode: Int? = null
    private var lastControlKey: String? = null
    private var closed = false
    private var pinnedCamera: String? = null
    private var pinnedStatus = "Connecting HTTPS video"
    private var httpsBackend: HttpsLivePlayer? = null
    private var httpsView: PlayerView? = null
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP) {
            visualJob?.cancel()
            stopHttpsLive()
            visualKey = null
        } else if (event == Lifecycle.Event.ON_START && !closed) {
            update(state)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        addView(visualHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(recoveryStatus, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = activity.dp(28)
            rightMargin = activity.dp(32)
        })
        header.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(8))
            background = activity.nativeGlassOverlayBackground()
            this@NativeMonitorSurface.title.apply {
                textSize = 20f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            }
            this@NativeMonitorSurface.status.apply {
                textSize = 15f
                setTextColor(0xFFD5DFEC.toInt())
            }
            addView(this@NativeMonitorSurface.title)
            addView(this@NativeMonitorSurface.status)
        }
        addView(
            header,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                leftMargin = activity.dp(32)
                topMargin = activity.dp(28)
            },
        )
        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER_VERTICAL
        controls.setPadding(activity.dp(12), activity.dp(6), activity.dp(12), activity.dp(6))
        controls.background = activity.nativeGlassOverlayBackground()
        controls.addView(controlButton("Exit", R.drawable.ic_chevron_left, onBack))
        controls.addView(panelLabel("View"))
        MonitorPreset.entries.forEach { preset ->
            val button = controlButton(preset.displayLabel()) {
                pinnedCamera = null
                update(state)
                onPreset(preset)
            }
            presetButtons[preset] = button
            controls.addView(button)
        }
        controls.addView(divider())
        gridButton = controlButton("Show grid") { clearPinnedCamera() }
        controls.addView(gridButton)
        audioButton = controlButton("", R.drawable.ic_volume_off) { onAudioEnabled(!state.audioEnabled) }
        awakeButton = controlButton("") { onKeepScreenAwake(!state.keepScreenAwake) }
        exitButton = controlButton("") { onExitMinutes(nextNativeMonitorExitMinutes(state.exitAfterMinutes)) }
        audioButton.tag = "monitor:control:audio"
        awakeButton.tag = "monitor:control:awake"
        exitButton.tag = "monitor:control:timer"
        controls.addView(audioButton)
        controls.addView(awakeButton)
        controls.addView(exitButton)
        controlsScroller.apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = false
            clipToPadding = false
            addView(controls, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        addView(
            controlsScroller,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                leftMargin = activity.dp(32)
                rightMargin = activity.dp(32)
                bottomMargin = activity.dp(24)
            },
        )
        activity.lifecycle.addObserver(lifecycleObserver)
        update(initialState)
        controls.post {
            presetButtons[state.preset]?.requestFocus()
            scheduleChromeHide()
        }
    }

    fun update(updated: MonitorModeUiState) {
        if (closed) return
        state = updated
        val pinned = updated.cameras.firstOrNull {
            it.name == pinnedCamera && it.name in updated.visibleCameraIds && updated.preset == MonitorPreset.FIXED
        }
        if (pinned == null) pinnedCamera = null
        gridButton.visibility = if (pinned != null) View.VISIBLE else View.GONE
        keepScreenOn = updated.keepScreenAwake
        title.text = updated.title
        status.text = if (pinned != null) "Pinned ${pinned.displayName} · $pinnedStatus" else monitorStatus(updated)
        httpsBackend?.player?.volume = if (updated.audioEnabled) 1f else 0f
        recoveryStatus.text = "Reconnecting · Showing previews"
        recoveryStatus.visibility = if (updated.arbitration.phase == MonitorPhase.RECOVERING) View.VISIBLE else View.GONE
        presetButtons.forEach { (preset, button) -> button.isSelected = preset == updated.preset }
        audioButton.text = if (updated.audioEnabled) "Audio on" else "Audio off"
        audioButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (updated.audioEnabled) R.drawable.ic_volume_on else R.drawable.ic_volume_off,
            0,
            0,
            0,
        )
        audioButton.compoundDrawablesRelative.forEach { drawable -> drawable?.setTint(Color.WHITE) }
        audioButton.isSelected = updated.audioEnabled
        awakeButton.text = if (updated.keepScreenAwake) "Stay awake" else "Allow sleep"
        awakeButton.isSelected = updated.keepScreenAwake
        exitButton.text = updated.exitAfterMinutes?.let { "Timer: $it min" } ?: "Timer: Never"
        exitButton.isSelected = updated.exitAfterMinutes != null

        val promoted = updated.arbitration.promotion?.cameraId
            ?.let { id -> updated.cameras.firstOrNull { it.name == id } }
        val patrol = updated.arbitration.patrolCameraId
            ?.let { id -> updated.cameras.firstOrNull { it.name == id } }
        val visible = updated.cameras.filter { it.name in updated.visibleCameraIds }
        val nextVisualKey = when {
            pinned != null -> "https:${pinned.name}"
            promoted != null &&
                updated.arbitration.promotion.presentation == MonitorPresentationKind.LIVE &&
                updated.liveCompatibilityRequestId != null ->
                "live:${updated.liveCompatibilityRequestId}:${updated.audioEnabled}"
            promoted != null -> "snapshot:${promoted.name}"
            patrol != null -> "snapshot:${patrol.name}"
            else -> "grid:${visible.joinToString { it.name }}"
        }
        if (nextVisualKey == visualKey) return
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val focusedTileKey = visualHost.findFocus()?.tag as? String
        visualKey = nextVisualKey
        visualJob?.cancel()
        stopHttpsLive()
        visualJob = null
        clearPreviewBindings()
        visualHost.removeAllViews()
        cameraLabels.clear()
        when {
            pinned != null -> showHttpsLive(pinned)
            nextVisualKey.startsWith("live:") -> showLive(requireNotNull(updated.liveCompatibilityRequestId), !updated.audioEnabled)
            promoted != null -> showSnapshot(promoted)
            patrol != null -> showSnapshot(patrol)
            visible.isNotEmpty() -> showGrid(visible)
            else -> visualHost.addView(
                statusLabel("No cameras are available in this view"),
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
            )
        }
        if (focusedTileKey != null && chromeVisible) post {
            if (closed) return@post
            val restored = visualHost.findViewWithTag<View>(focusedTileKey)
            if (restored?.isFocusable == true) restored.requestFocus()
            else presetButtons[state.preset]?.requestFocus()
        }
    }

    fun close() {
        closed = true
        activity.lifecycle.removeObserver(lifecycleObserver)
        stopHttpsLive()
        chromeHideJob?.cancel()
        chromeHideJob = null
        visualJob?.cancel()
        visualJob = null
        clearPreviewBindings()
        keepScreenOn = false
    }

    fun dismissPlaybackOsd(): Boolean {
        if (closed || !chromeVisible) return false
        chromeHideJob?.cancel()
        chromeHideJob = null
        heldKeyCode = null
        revealKeyCode = null
        hideChrome(force = true)
        return true
    }

    private fun clearPinnedCamera() {
        pinnedCamera = null
        update(state)
        showChrome(requestFocus = true)
    }

    /** Used both by the TV key dispatcher and Android's system Back callback. */
    fun handlePlaybackBack(): Boolean {
        if (closed) return false
        if (dismissPlaybackOsd()) return true
        if (pinnedCamera == null) return false
        clearPinnedCamera()
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (closed) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
                !event.isCanceled && !handlePlaybackBack()
            ) {
                onBack()
            }
            return true
        }
        if (event.keyCode == revealKeyCode) {
            if (event.action == KeyEvent.ACTION_UP) {
                revealKeyCode = null
                heldKeyCode = null
                scheduleChromeHide()
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            heldKeyCode = event.keyCode
            chromeHideJob?.cancel()
            if (!chromeVisible && event.keyCode in REVEAL_KEYS) {
                revealKeyCode = event.keyCode
                showChrome(requestFocus = true)
                return true
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            heldKeyCode = null
            scheduleChromeHide()
        }
        return super.dispatchKeyEvent(event)
    }

    private fun stopHttpsLive() {
        val backend = httpsBackend
        httpsBackend = null
        httpsView?.player = null
        httpsView?.visibility = View.INVISIBLE
        httpsView = null
        if (backend != null && !backend.close()) {
            pinnedStatus = "Playback cleanup incomplete; restart Opah"
            status.text = pinnedStatus
        }
    }

    private fun showHttpsLive(camera: Camera) {
        val request = prepareHttpLive(camera.name)
        fun report(message: String) {
            pinnedStatus = message
            status.text = "Pinned ${camera.displayName} · $message"
        }
        if (request == null || !request.isAuthorized()) {
            report("HTTPS stream unavailable or access denied; Back to retry")
            return
        }
        report("Waiting for previous player to close")
        val view = PlayerView(activity).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setKeepContentOnPlayerReset(false)
        }
        visualHost.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val output = PlayerViewMedia3VideoOutputTarget(view, {}, {})
        val attempt = PlaybackAttemptId(PlaybackSessionId("https-${UUID.randomUUID()}"), 1)
        visualJob = activity.lifecycleScope.launch {
            val acquired = awaitExclusiveVideoOutput(
                ProcessMedia3VideoOutputRegistry.instance, output, attempt, request.isAuthorized,
            )
            if (acquired == null) {
                report("Player busy or access changed; Back to retry")
                return@launch
            }
            if (!request.isAuthorized()) {
                acquired.finish { true } // Nothing was constructed or attached.
                report("Access changed; playback stopped")
                return@launch
            }
            val backend = try {
                HttpsLivePlayer(activity, application.container.httpClient, acquired)
            } catch (_: Exception) {
                // Without a returned player, construction cleanup cannot be proven.
                acquired.finish { false }
                report("Player setup failed; restart Opah")
                return@launch
            }
            httpsBackend = backend
            httpsView = view
            var firstFrame = false
            try { backend.player.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    if (httpsBackend !== backend) return
                    if (!request.isAuthorized()) {
                        report("Access changed; playback stopped")
                        stopHttpsLive()
                        return
                    }
                    firstFrame = true
                    report("Live HTTPS video")
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (httpsBackend !== backend) return
                    report("HTTPS playback failed: ${liveFailureStatus(error) ?: error.errorCodeName}; Back to retry")
                    stopHttpsLive()
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (httpsBackend !== backend) return
                    if (playbackState == Player.STATE_ENDED) {
                        report("Live connection ended; Back to retry")
                        stopHttpsLive()
                    }
                }
            }) } catch (_: Exception) {
                report("Player listener setup failed; Back to retry")
                stopHttpsLive()
                return@launch
            }
            val started = android.os.SystemClock.elapsedRealtime()
            try {
                report("Connecting HTTPS video")
                backend.prepare(request.uri, LivePlaybackOptions(videoOnly = !state.audioEnabled))
                while (isActive && httpsBackend === backend) {
                    if (!request.isAuthorized()) {
                        report("Access changed; playback stopped")
                        break
                    }
                    if (!firstFrame && android.os.SystemClock.elapsedRealtime() - started >= 30_000L) {
                        report("HTTPS first-frame timeout; Back to retry")
                        break
                    }
                    delay(250L)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                report("HTTPS player setup failed; Back to retry")
            } finally {
                if (httpsBackend === backend) stopHttpsLive()
                else backend.close()
            }
        }
    }

    private fun showLive(requestId: String, videoOnly: Boolean) {
        val playerView = PlayerView(activity).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            isFocusable = false
            setBackgroundColor(Color.BLACK)
        }
        visualHost.addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val playbackStatus = statusLabel("Connecting").apply { tag = "monitor:live-status" }
        visualHost.addView(playbackStatus, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = activity.dp(80)
            rightMargin = activity.dp(24)
        })
        visualJob = activity.lifecycleScope.launch {
            val session = try {
                application.container.singleLivePlaybackCoordinator.open(
                    SingleLivePlaybackRequestId(requestId),
                    PlayerViewMedia3VideoOutputTarget(
                        playerView = playerView,
                        onAttached = { player -> player.volume = if (videoOnly) 0f else 1f },
                        onDetached = {},
                    ),
                    videoOnly = videoOnly,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                playbackStatus.text = "Camera unavailable"
                onPlaybackFailed()
                return@launch
            }
            var readyReported = false
            var failureReported = false
            try {
                session.snapshots.collect { snapshot ->
                    when (val playback = snapshot.state) {
                        is PlaybackCompatibilityState.Verified,
                        is PlaybackCompatibilityState.UnpersistedLive,
                        is PlaybackCompatibilityState.Persisting,
                        -> {
                            playbackStatus.visibility = View.GONE
                            if (!readyReported) {
                                readyReported = true
                                onPlaybackReady()
                            }
                        }
                        is PlaybackCompatibilityState.Recovering,
                        is PlaybackCompatibilityState.Attempting,
                        is PlaybackCompatibilityState.Releasing,
                        -> {
                            playbackStatus.text = if (readyReported) "Reconnecting" else "Connecting"
                            playbackStatus.visibility = View.VISIBLE
                        }
                        is PlaybackCompatibilityState.Finished -> if (
                            playback.result !is PlaybackResult.VerifiedLive &&
                            playback.result !is PlaybackResult.UnpersistedLive &&
                            playback.result !is PlaybackResult.Cancelled &&
                            !failureReported
                        ) {
                            failureReported = true
                            playbackStatus.text = "Camera unavailable"
                            playbackStatus.visibility = View.VISIBLE
                            onPlaybackFailed()
                        }
                        else -> Unit
                    }
                }
            } finally {
                withContext(NonCancellable) { session.close() }
            }
        }
    }

    private fun showSnapshot(camera: Camera) {
        val host = FrameLayout(activity)
        visualHost.addView(host, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addPreview(host, camera, MONITOR_SNAPSHOT_HEIGHT)
        val cameraLabel = statusLabel(camera.displayName).apply {
            visibility = if (chromeVisible) View.GONE else View.VISIBLE
        }
        cameraLabels += cameraLabel
        visualHost.addView(
            cameraLabel,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = activity.dp(16)
                bottomMargin = activity.dp(16)
            },
        )
    }

    private fun showGrid(cameras: List<Camera>) {
        val shown = cameras.take(4)
        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        shown.chunked(2).forEach { rowCameras ->
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            rowCameras.forEach { camera ->
                row.addView(cameraTile(camera), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
            if (rowCameras.size == 1) row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
            rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        visualHost.addView(rows, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun cameraTile(camera: Camera): View = FrameLayout(activity).apply {
        tag = "monitor:tile:${camera.name}"
        isFocusable = true
        isClickable = true
        foreground = activity.nativeFlatScrollRegionBackground()
        setPadding(activity.dp(2), activity.dp(2), activity.dp(2), activity.dp(2))
        addPreview(this, camera, MONITOR_GRID_HEIGHT)
        val cameraLabel = statusLabel(camera.displayName).apply {
            visibility = if (chromeVisible) View.GONE else View.VISIBLE
        }
        cameraLabels += cameraLabel
        addView(
            cameraLabel,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = activity.dp(10)
                bottomMargin = activity.dp(10)
            },
        )
        setOnClickListener {
            if (state.preset == MonitorPreset.FIXED) {
                pinnedCamera = camera.name
                update(state)
                showChrome(requestFocus = true)
            } else onManualCamera(camera.name)
        }
        onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
            cameraLabel.visibility = if (focused || !chromeVisible) View.VISIBLE else View.GONE
        }
    }

    private fun addPreview(host: FrameLayout, camera: Camera, height: Int) {
        val cached = cachedBitmap(camera.name)
        val image = ImageView(activity).apply {
            tag = "monitor:image:${camera.name}"
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(NativeTheme.palette.panel)
            cached?.let(::setImageBitmap)
        }
        host.addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val badge = statusLabel(cameraPreviewStatus(imageLoadedAtMillis(camera.name), cached != null, false)).apply {
            tag = "monitor:preview-status:${camera.name}"
        }
        host.addView(badge, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = activity.dp(8)
            rightMargin = activity.dp(8)
        })
        val retry = controlButton("Retry preview", R.drawable.ic_retry) {
            previewBindings[camera.name]?.let(::startPreview)
        }.apply {
            tag = "monitor:retry:${camera.name}"
            contentDescription = "Retry ${camera.displayName} preview"
            visibility = View.GONE
        }
        host.addView(retry, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val binding = MonitorPreviewBinding(camera, image, badge, retry, height, imageLoadedAtMillis(camera.name), cached != null)
        previewBindings[camera.name] = binding
        startPreview(binding)
    }

    private fun startPreview(binding: MonitorPreviewBinding) {
        binding.job?.cancel()
        binding.job = activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive && !closed && previewBindings[binding.camera.name] === binding) {
                    val bitmap = try { refreshBitmap(binding.camera.name, binding.height) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    currentCoroutineContext().ensureActive()
                    if (closed || previewBindings[binding.camera.name] !== binding) return@repeatOnLifecycle
                    if (bitmap != null) {
                        binding.image.setImageBitmap(bitmap)
                        binding.hasImage = true
                        binding.loadedAtMillis = imageLoadedAtMillis(binding.camera.name) ?: System.currentTimeMillis()
                    }
                    binding.badge.text = cameraPreviewStatus(binding.loadedAtMillis, binding.hasImage, bitmap == null)
                    val hadRetryFocus = binding.retry.hasFocus()
                    binding.retry.visibility = if (bitmap == null) View.VISIBLE else View.GONE
                    if (hadRetryFocus && bitmap != null) {
                        val tile = binding.image.parent as? View
                        if (tile?.isFocusable == true) tile.requestFocus() else presetButtons[state.preset]?.requestFocus()
                    }
                    delay(SNAPSHOT_REFRESH_MILLIS)
                }
            }
        }
    }

    private fun clearPreviewBindings() {
        previewBindings.values.forEach { it.job?.cancel() }
        previewBindings.clear()
    }

    private data class MonitorPreviewBinding(
        val camera: Camera,
        val image: ImageView,
        val badge: TextView,
        val retry: TextView,
        val height: Int,
        var loadedAtMillis: Long?,
        var hasImage: Boolean,
        var job: Job? = null,
    )

    private fun monitorStatus(value: MonitorModeUiState): String {
        val promoted = value.arbitration.promotion?.cameraId
            ?.let { id -> value.cameras.firstOrNull { it.name == id } }
        val patrol = value.arbitration.patrolCameraId
            ?.let { id -> value.cameras.firstOrNull { it.name == id } }
        return value.statusMessage ?: when {
            value.arbitration.phase == MonitorPhase.RECOVERING -> "Reconnecting"
            promoted != null -> "Watching ${promoted.displayName}"
            patrol != null -> "Patrolling ${patrol.displayName}"
            value.preset == MonitorPreset.FIXED -> "Fixed view"
            else -> "Watching for activity"
        }
    }

    private fun controlButton(
        label: String,
        iconRes: Int? = null,
        action: () -> Unit,
    ): TextView = TextView(activity).apply {
        text = label
        tag = "monitor:control:$label"
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        background = activity.nativeFlatOverlayFocusableBackground()
        isFocusable = true
        isClickable = true
        iconRes?.let { resource ->
            setCompoundDrawablesRelativeWithIntrinsicBounds(resource, 0, 0, 0)
            compoundDrawablePadding = activity.dp(5)
            compoundDrawablesRelative.forEach { drawable -> drawable?.setTint(Color.WHITE) }
        }
        setPadding(activity.dp(10), activity.dp(5), activity.dp(10), activity.dp(5))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(40)).apply {
            marginStart = activity.dp(2)
            marginEnd = activity.dp(2)
        }
        setOnClickListener {
            action()
            showChrome(requestFocus = false)
        }
        onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
            view.applyNativeFocusMotion(focused, scale = 1.05f)
            if (focused) lastControlKey = view.tag as? String
        }
    }

    private fun panelLabel(label: String): TextView = TextView(activity).apply {
        text = label
        textSize = 12f
        setTextColor(0xFFD5DFEC.toInt())
        setPadding(activity.dp(9), 0, activity.dp(5), 0)
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, activity.dp(40))
    }

    private fun divider(): View = View(activity).apply {
        setBackgroundColor(0x66FFFFFF)
        layoutParams = LinearLayout.LayoutParams(activity.dp(1), activity.dp(24)).apply {
            marginStart = activity.dp(7)
            marginEnd = activity.dp(7)
            gravity = Gravity.CENTER_VERTICAL
        }
    }

    private fun statusLabel(label: String): TextView = TextView(activity).apply {
        text = label
        textSize = 14f
        setTextColor(Color.WHITE)
        setPadding(activity.dp(10), activity.dp(7), activity.dp(10), activity.dp(7))
        background = activity.nativeGlassOverlayBackground()
    }

    private fun showChrome(requestFocus: Boolean) {
        if (closed) return
        chromeVisible = true
        header.visibility = View.VISIBLE
        controlsScroller.visibility = View.VISIBLE
        cameraLabels.forEach { it.visibility = View.GONE }
        if (requestFocus || findFocus() == null || findFocus() === this) {
            val remembered = lastControlKey?.let { controls.findViewWithTag<View>(it) }
            if (remembered?.isEnabled == true) remembered.requestFocus()
            else presetButtons[state.preset]?.requestFocus()
        }
        scheduleChromeHide()
    }

    private fun hideChrome(force: Boolean = false) {
        if (!force && previewBindings.values.any { it.retry.hasFocus() }) return
        chromeVisible = false
        header.visibility = View.GONE
        controlsScroller.visibility = View.GONE
        cameraLabels.forEach { it.visibility = View.VISIBLE }
        requestFocus()
    }

    private fun scheduleChromeHide() {
        chromeHideJob?.cancel()
        if (closed || heldKeyCode != null) return
        chromeHideJob = activity.lifecycleScope.launch {
            delay(CHROME_HIDE_MILLIS)
            hideChrome()
        }
    }

    private fun MonitorPreset.displayLabel(): String = when (this) {
        MonitorPreset.FIXED -> "Fixed"
        MonitorPreset.CALM -> "Calm"
        MonitorPreset.ACTIVE -> "Active"
        MonitorPreset.PATROL -> "Patrol"
    }

    private companion object {
        const val MONITOR_SNAPSHOT_HEIGHT = 720
        const val MONITOR_GRID_HEIGHT = 360
        const val SNAPSHOT_REFRESH_MILLIS = 5_000L
        const val CHROME_HIDE_MILLIS = 5_000L
        val REVEAL_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )
    }
}
