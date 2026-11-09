package app.opah.tv.ui.views

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import app.opah.tv.BuildConfig
import app.opah.tv.OpahApplication
import app.opah.tv.PictureInPictureRequest
import app.opah.tv.R
import app.opah.tv.pipAspectRatio
import app.opah.tv.playback.LivePlaybackOptions
import app.opah.tv.playback.LivePlayer
import app.opah.tv.playback.LivePlayerFactory
import app.opah.tv.ui.CameraGroupViewUiState
import app.opah.tv.ui.DOCUMENTATION_URI_PREFIX
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@UnstableApi
@SuppressLint("ViewConstructor", "SetTextI18n")
internal class NativeCameraGroupSurface(
    private val activity: ComponentActivity,
    private val state: CameraGroupViewUiState,
    private val preferRtpTcp: Boolean,
    private val stretchedCameraNames: Set<String>,
    private val cachedBitmap: (String) -> Bitmap?,
    private val refreshBitmap: suspend (String, Int) -> Bitmap?,
    private val pictureInPictureAvailable: Boolean,
    private val onEnterPictureInPicture: (PictureInPictureRequest) -> Boolean,
    private val onStartMonitor: () -> Unit,
    private val onBack: () -> Unit,
    private val imageLoadedAtMillis: (String) -> Long? = { null },
    private val livePlayerFactory: LivePlayerFactory = (activity.application as OpahApplication).container.livePlayerFactory,
) : FrameLayout(activity) {
    private val players = mutableListOf<LivePlayer>()
    private val jobs = mutableListOf<Job>()
    private val readyCameraNames = linkedSetOf<String>()
    private val cameraLabels = mutableListOf<View>()
    private val retryButtons = mutableListOf<View>()
    private val headerView: View
    private val controlsView: LinearLayout
    private var warningView: View? = null
    private var pictureInPictureButton: TextView? = null
    private var chromeHideJob: Job? = null
    private var chromeVisible = true
    private var pictureInPictureActive = false
    private var revealKeyCode: Int? = null
    private var heldKeyCode: Int? = null
    private var lastControlKey: String? = null
    private var closed = false

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        addView(buildCameraLayout(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        headerView = buildHeader()
        addView(headerView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            leftMargin = activity.dp(32)
            topMargin = activity.dp(28)
        })
        controlsView = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(activity.dp(8), activity.dp(7), activity.dp(8), activity.dp(7))
            background = activity.nativeGlassOverlayBackground()
            addView(controlButton("Back", R.drawable.ic_chevron_left, onBack))
            addView(controlButton("Monitor mode", R.drawable.ic_videocam, onStartMonitor))
            if (state.streams.size == 2 && pictureInPictureAvailable) {
                pictureInPictureButton = controlButton(
                    "Picture-in-picture",
                    R.drawable.ic_picture_in_picture,
                    ::enterPictureInPicture,
                ).apply {
                    isEnabled = false
                    alpha = 0.45f
                }
                addView(pictureInPictureButton)
            }
        }
        addView(
            controlsView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = activity.dp(24)
                leftMargin = activity.dp(32)
                rightMargin = activity.dp(32)
            },
        )
        if (state.decoderWarning) {
            warningView = statusLabel("This TV may play fewer cameras more reliably")
            addView(
                warningView,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                    topMargin = activity.dp(28)
                    rightMargin = activity.dp(32)
                },
            )
        }
        controlsView.post {
            controlsView.getChildAt(1)?.requestFocus()
            scheduleChromeHide()
        }
    }

    fun close() {
        closed = true
        chromeHideJob?.cancel()
        chromeHideJob = null
        jobs.forEach(Job::cancel)
        jobs.clear()
        players.forEach(LivePlayer::release)
        players.clear()
    }

    fun setPictureInPictureActive(active: Boolean) {
        pictureInPictureActive = active
        chromeHideJob?.cancel()
        if (!active) {
            pictureInPictureButton?.text = "Picture-in-picture"
            refreshPictureInPictureAvailability()
            showChrome(requestFocus = false)
        } else {
            hideChrome()
        }
    }

    fun dismissPlaybackOsd(): Boolean {
        if (closed || pictureInPictureActive || !chromeVisible) return false
        chromeHideJob?.cancel()
        chromeHideJob = null
        heldKeyCode = null
        revealKeyCode = null
        hideChrome(force = true)
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (closed) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
                !event.isCanceled && !dismissPlaybackOsd()
            ) onBack()
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
            if (!chromeVisible && !pictureInPictureActive && event.keyCode in REVEAL_KEYS) {
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

    private fun buildCameraLayout(): View = when (state.streams.size) {
        2 -> horizontalRow(tile(0), tile(1))
        3 -> horizontalRow(
            tile(0),
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(tile(1), weightedVerticalParams())
                addView(tile(2), weightedVerticalParams())
            },
        )
        4 -> LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(horizontalRow(tile(0), tile(1)), weightedVerticalParams())
            addView(horizontalRow(tile(2), tile(3)), weightedVerticalParams())
        }
        else -> FrameLayout(activity).apply {
            addView(statusLabel("This camera group is unavailable"), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
    }

    private fun horizontalRow(first: View, second: View): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(first, weightedHorizontalParams())
        addView(second, weightedHorizontalParams())
    }

    private fun tile(index: Int): View {
        val stream = state.streams[index]
        val stretched = stream.camera.name in stretchedCameraNames
        return FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            val snapshot = ImageView(activity).apply {
                scaleType = if (stretched) ImageView.ScaleType.FIT_XY else ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(NativeTheme.palette.panel)
                cachedBitmap(stream.camera.name)?.let(::setImageBitmap)
            }
            addView(snapshot, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            val playerView = PlayerView(activity).apply {
                useController = false
                resizeMode = nativePlaybackResizeMode(stretched)
                isFocusable = false
                visibility = View.INVISIBLE
                setBackgroundColor(Color.TRANSPARENT)
            }
            addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            val cameraLabel = statusLabel(stream.camera.displayName)
            cameraLabel.visibility = View.GONE
            cameraLabels += cameraLabel
            addView(
                cameraLabel,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                    leftMargin = activity.dp(10)
                    bottomMargin = activity.dp(10)
                },
            )
            // This badge is independent of chrome: an old still must never look like a live feed.
            val playbackStatus = statusLabel("Connecting · Preview").apply {
                tag = "group:status:${stream.camera.name}"
            }
            addView(playbackStatus, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = activity.dp(10)
                rightMargin = activity.dp(10)
            })
            var activePlayer: LivePlayer? = null
            var startJob: Job? = null
            var snapshotJob: Job? = null
            var attempt = 0
            var failed = false
            var rendered = false
            var needsStartStagger = true
            lateinit var startStream: () -> Unit
            val retry = controlButton("Retry ${stream.camera.displayName}", R.drawable.ic_retry) { startStream() }.apply {
                tag = "group:retry:${stream.camera.name}"
                visibility = View.GONE
                maxWidth = activity.dp(340)
            }
            retryButtons += retry
            addView(retry, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            fun reportFailure() {
                if (closed) return
                failed = true
                playerView.visibility = View.INVISIBLE
                readyCameraNames -= stream.camera.name
                refreshPictureInPictureAvailability()
                playbackStatus.text = if (snapshot.drawable == null) "Camera unavailable" else "Camera unavailable · Preview"
                playbackStatus.visibility = View.VISIBLE
                retry.visibility = View.VISIBLE
                retry.isEnabled = true
                retry.isClickable = true
                retry.alpha = 1f
                retry.text = "Retry ${stream.camera.displayName}"
                snapshot.contentDescription = cameraPreviewStatus(
                    imageLoadedAtMillis(stream.camera.name), snapshot.drawable != null, unavailable = true,
                )
            }
            startStream = {
                if (!closed && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && (attempt == 0 || failed)) {
                    attempt += 1
                    val thisAttempt = attempt
                    startJob?.cancel()
                    snapshotJob?.cancel()
                    activePlayer?.let { old -> playerView.player = null; players.remove(old); old.release() }
                    activePlayer = null
                    failed = false
                    rendered = false
                    readyCameraNames -= stream.camera.name
                    refreshPictureInPictureAvailability()
                    playerView.visibility = View.INVISIBLE
                    playbackStatus.text = "Connecting · Preview"
                    playbackStatus.visibility = View.VISIBLE
                    // Keep the retry control and focus stable until this attempt finishes.
                    retry.text = "Connecting"
                    retry.isClickable = false
                    retry.alpha = 0.6f
                    jobs.removeAll { it.isCompleted }
                    val startDelayMillis = if (needsStartStagger) index * PLAYER_START_STAGGER_MILLIS else 0
                    needsStartStagger = false
                    startJob = activity.lifecycleScope.launch {
                        delay(startDelayMillis)
                        var created: LivePlayer? = null
                        try {
                            created = livePlayerFactory.create(activity, false)
                            currentCoroutineContext().ensureActive()
                            if (closed || thisAttempt != attempt) { created.release(); return@launch }
                            val livePlayer = created
                            activePlayer = livePlayer
                            players += livePlayer
                            playerView.player = livePlayer.player
                            livePlayer.player.addListener(object : Player.Listener {
                                override fun onRenderedFirstFrame() {
                                    if (closed || thisAttempt != attempt || failed) return
                                    rendered = true
                                    playerView.visibility = View.VISIBLE
                                    playbackStatus.visibility = View.GONE
                                    val hadRetryFocus = retry.hasFocus()
                                    retry.visibility = View.GONE
                                    if (hadRetryFocus) controlsView.getChildAt(1)?.requestFocus()
                                    readyCameraNames += stream.camera.name
                                    refreshPictureInPictureAvailability()
                                }

                                override fun onPlaybackStateChanged(playbackState: Int) {
                                    if (closed || thisAttempt != attempt || failed) return
                                    when (playbackState) {
                                        Player.STATE_IDLE -> Unit
                                        Player.STATE_BUFFERING -> {
                                            playbackStatus.text = if (rendered) "Reconnecting" else "Connecting · Preview"
                                            playbackStatus.visibility = View.VISIBLE
                                            readyCameraNames -= stream.camera.name
                                            refreshPictureInPictureAvailability()
                                        }
                                        Player.STATE_READY -> if (rendered) {
                                            playbackStatus.visibility = View.GONE
                                            readyCameraNames += stream.camera.name
                                            refreshPictureInPictureAvailability()
                                        }
                                        Player.STATE_ENDED -> reportFailure()
                                    }
                                }

                                override fun onPlayerError(error: PlaybackException) {
                                    if (!closed && thisAttempt == attempt) reportFailure()
                                }
                            })
                            livePlayer.prepare(stream.uri, LivePlaybackOptions(forceRtpTcp = preferRtpTcp, videoOnly = true))
                        } catch (cancelled: CancellationException) {
                            if (thisAttempt == attempt) playerView.player = null
                            created?.let { players.remove(it); it.release() }
                            throw cancelled
                        } catch (_: Exception) {
                            if (thisAttempt == attempt) playerView.player = null
                            created?.let { players.remove(it); it.release() }
                            activePlayer = null
                            if (thisAttempt == attempt) reportFailure()
                        }
                    }.also(jobs::add)
                    snapshotJob = activity.lifecycleScope.launch {
                        val bitmap = try { refreshBitmap(stream.camera.name, GROUP_SNAPSHOT_HEIGHT) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                        currentCoroutineContext().ensureActive()
                        if (!closed && thisAttempt == attempt) bitmap?.let(snapshot::setImageBitmap)
                    }.also(jobs::add)
                }
            }
            if (BuildConfig.DOCUMENTATION_MODE && stream.uri.startsWith(DOCUMENTATION_URI_PREFIX)) {
                // The optional provider supplies still images, not a playable transport.
                playbackStatus.text = if (snapshot.drawable == null) "Preview unavailable" else "Preview"
                snapshot.contentDescription = cameraPreviewStatus(
                    imageLoadedAtMillis(stream.camera.name), snapshot.drawable != null, unavailable = snapshot.drawable == null,
                )
            } else {
                activity.lifecycleScope.launch {
                    activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        try {
                            startStream()
                            awaitCancellation()
                        } finally {
                            attempt += 1
                            startJob?.cancel()
                            snapshotJob?.cancel()
                            playerView.player = null
                            activePlayer?.let { old -> if (players.remove(old)) old.release() }
                            activePlayer = null
                            failed = true
                            rendered = false
                            needsStartStagger = true
                            readyCameraNames -= stream.camera.name
                            refreshPictureInPictureAvailability()
                            playerView.visibility = View.INVISIBLE
                            playbackStatus.text = "Connecting · Preview"
                            playbackStatus.visibility = View.VISIBLE
                            retry.visibility = View.GONE
                        }
                    }
                }.also(jobs::add)
            }
        }
    }

    private fun refreshPictureInPictureAvailability() {
        val enabled = shouldOfferNativeCameraGroupPictureInPicture(
            cameraCount = state.streams.size,
            pictureInPictureAvailable = pictureInPictureAvailable,
            readyCameraCount = readyCameraNames.size,
        )
        pictureInPictureButton?.isEnabled = enabled
        pictureInPictureButton?.alpha = if (enabled) 1f else 0.45f
    }

    private fun enterPictureInPicture() {
        if (
            !shouldOfferNativeCameraGroupPictureInPicture(
                cameraCount = state.streams.size,
                pictureInPictureAvailable = pictureInPictureAvailable,
                readyCameraCount = readyCameraNames.size,
            )
        ) return
        val sourceRect = Rect().takeIf { getGlobalVisibleRect(it) }
        setPictureInPictureActive(true)
        val entered = onEnterPictureInPicture(
            PictureInPictureRequest(
                title = state.title,
                subtitle = "${state.streams.size} cameras",
                aspectRatio = pipAspectRatio(16, 9),
                sourceRectHint = sourceRect,
            ),
        )
        if (!entered) {
            setPictureInPictureActive(false)
            pictureInPictureButton?.text = "Picture-in-picture unavailable"
        }
    }

    private fun buildHeader(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(activity.dp(14), activity.dp(10), activity.dp(14), activity.dp(10))
        background = activity.nativeGlassOverlayBackground()
        addView(TextView(activity).apply {
            text = state.title
            textSize = 21f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(activity).apply {
            text = "${state.streams.size} cameras"
            textSize = 14f
            setTextColor(0xFFD5DFEC.toInt())
        })
    }

    private fun statusLabel(label: String): TextView = TextView(activity).apply {
        text = label
        textSize = 14f
        setTextColor(Color.WHITE)
        setPadding(activity.dp(10), activity.dp(7), activity.dp(10), activity.dp(7))
        background = activity.nativeGlassOverlayBackground()
    }

    private fun controlButton(
        label: String,
        iconRes: Int,
        action: () -> Unit,
    ): TextView = TextView(activity).apply {
        text = label
        tag = "group:control:$iconRes"
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        background = activity.nativeFlatOverlayFocusableBackground()
        isFocusable = true
        isClickable = true
        setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0)
        compoundDrawablePadding = activity.dp(5)
        compoundDrawablesRelative.forEach { drawable -> drawable?.setTint(Color.WHITE) }
        setPadding(activity.dp(11), activity.dp(5), activity.dp(11), activity.dp(5))
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

    private fun showChrome(requestFocus: Boolean) {
        if (closed || pictureInPictureActive) return
        chromeVisible = true
        headerView.visibility = View.VISIBLE
        controlsView.visibility = View.VISIBLE
        warningView?.visibility = View.VISIBLE
        cameraLabels.forEach { it.visibility = View.GONE }
        if (requestFocus || findFocus() == null || findFocus() === this) {
            val remembered = lastControlKey?.let { controlsView.findViewWithTag<View>(it) }
            if (remembered?.isEnabled == true) remembered.requestFocus()
            else controlsView.getChildAt(1)?.requestFocus()
        }
        scheduleChromeHide()
    }

    private fun hideChrome(force: Boolean = false) {
        if (!force && retryButtons.any { it.hasFocus() }) return
        chromeVisible = false
        headerView.visibility = View.GONE
        controlsView.visibility = View.GONE
        warningView?.visibility = View.GONE
        cameraLabels.forEach {
            it.visibility = if (pictureInPictureActive) View.GONE else View.VISIBLE
        }
        requestFocus()
    }

    private fun scheduleChromeHide() {
        chromeHideJob?.cancel()
        if (closed || pictureInPictureActive || heldKeyCode != null) return
        chromeHideJob = activity.lifecycleScope.launch {
            delay(CHROME_HIDE_MILLIS)
            hideChrome()
        }
    }

    private fun weightedHorizontalParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
    private fun weightedVerticalParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)

    private companion object {
        const val GROUP_SNAPSHOT_HEIGHT = 420
        const val PLAYER_START_STAGGER_MILLIS = 250L
        const val CHROME_HIDE_MILLIS = 5_000L
        val REVEAL_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )
    }
}

internal fun shouldOfferNativeCameraGroupPictureInPicture(
    cameraCount: Int,
    pictureInPictureAvailable: Boolean,
    readyCameraCount: Int,
): Boolean = pictureInPictureAvailable && cameraCount == 2 && readyCameraCount >= cameraCount
