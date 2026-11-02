package app.opah.tv.ui.views

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.isEmpty
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.opah.tv.playback.AuthenticatedHttpLive
import app.opah.tv.playback.HttpsLivePlayer
import app.opah.tv.playback.liveFailureStatus
import app.opah.tv.playback.compatibility.PlaybackAttemptId
import app.opah.tv.playback.compatibility.PlaybackSessionId
import app.opah.tv.playback.media3.ProcessMedia3VideoOutputRegistry
import app.opah.tv.playback.media3.awaitExclusiveVideoOutput
import java.util.UUID
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.opah.tv.OpahApplication
import app.opah.tv.PictureInPictureRequest
import app.opah.tv.R
import app.opah.tv.BuildConfig
import app.opah.tv.pipAspectRatio
import app.opah.tv.playback.LivePlaybackOptions
import app.opah.tv.playback.PlaybackKind
import app.opah.tv.playback.PlaybackRequest
import app.opah.tv.playback.RecordedPlayerFactory
import app.opah.tv.playback.compatibility.AudioMode
import app.opah.tv.playback.compatibility.DecoderMode
import app.opah.tv.playback.compatibility.PlaybackCandidate
import app.opah.tv.playback.compatibility.PlaybackCompatibilityState
import app.opah.tv.playback.compatibility.PlaybackResult
import app.opah.tv.playback.compatibility.TransportMode
import app.opah.tv.playback.media3.PlayerViewMedia3VideoOutputTarget
import app.opah.tv.ui.DOCUMENTATION_URI_PREFIX
import app.opah.tv.ui.DocumentationSupport
import app.opah.tv.ui.boundedSeekPosition
import app.opah.tv.playback.media3.SingleLivePlaybackRequestId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class NativePlaybackControl(
    val key: String,
    val label: String,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val onClick: () -> Unit,
)

internal enum class NativePlaybackControlPlacement { CONTEXT, MORE }

internal fun nativePlaybackControlPlacement(@Suppress("UNUSED_PARAMETER") key: String): NativePlaybackControlPlacement =
    NativePlaybackControlPlacement.CONTEXT

internal fun nativePlaybackResizeMode(stretched: Boolean): Int = if (stretched) {
    AspectRatioFrameLayout.RESIZE_MODE_FILL
} else {
    AspectRatioFrameLayout.RESIZE_MODE_FIT
}

internal const val AUTOMATIC_REVIEW_WATCH_MILLIS = 8_000L

private val playbackMediaKeys = setOf(
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    KeyEvent.KEYCODE_MEDIA_PLAY,
    KeyEvent.KEYCODE_MEDIA_PAUSE,
    KeyEvent.KEYCODE_MEDIA_REWIND,
    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
)

internal fun automaticReviewWatchThresholdMillis(durationMillis: Long?): Long =
    durationMillis?.takeIf { it > 0L }?.coerceAtMost(AUTOMATIC_REVIEW_WATCH_MILLIS)
        ?: AUTOMATIC_REVIEW_WATCH_MILLIS

internal fun watchedEnoughForAutomaticReview(watchedMillis: Long, durationMillis: Long?): Boolean =
    watchedMillis >= automaticReviewWatchThresholdMillis(durationMillis)

internal data class NativeTimelineRange(val positionMillis: Long = 0L, val durationMillis: Long? = null, val seekable: Boolean = false)

internal class NativeTimelineAccessibility(
    private val range: () -> NativeTimelineRange,
    private val seekBy: (Long) -> Unit,
) : View.AccessibilityDelegate() {
    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(host, info)
        info.className = "android.widget.SeekBar"
        val state = range()
        val duration = state.durationMillis?.takeIf { it > 0L } ?: return
        val position = state.positionMillis.coerceIn(0L, duration)
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, duration / 1_000f, position / 1_000f,
        )
        if (host.isEnabled && state.seekable) {
            if (position < duration) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            if (position > 0L) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
    }

    override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
        val offset = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> 10_000L
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -10_000L
            else -> return super.performAccessibilityAction(host, action, args)
        }
        val state = range()
        val duration = state.durationMillis?.takeIf { it > 0L } ?: return false
        if (!host.isEnabled || !state.seekable || (offset > 0L && state.positionMillis >= duration) ||
            (offset < 0L && state.positionMillis <= 0L)) return false
        seekBy(offset)
        return true
    }
}

/** Content-sized live chrome remains bounded by the TV safe area. */
private class NativePlaybackOverlayColumn @JvmOverloads constructor(
    context: Context,
    minimumDp: Int = 0,
    private val maximumDp: Int? = null,
) : LinearLayout(context) {
    init { minimumWidth = context.dp(minimumDp) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maximum = maximumDp?.let(context::dp)
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val boundedWidth = if (maximum != null && mode != MeasureSpec.EXACTLY) {
            val available = if (mode == MeasureSpec.UNSPECIFIED) maximum else MeasureSpec.getSize(widthMeasureSpec)
            MeasureSpec.makeMeasureSpec(minOf(available, maximum), MeasureSpec.AT_MOST)
        } else widthMeasureSpec
        super.onMeasure(boundedWidth, heightMeasureSpec)
    }
}

@UnstableApi
@SuppressLint("ViewConstructor")
internal class NativePlaybackSurface(
    private val activity: ComponentActivity,
    val request: PlaybackRequest,
    private val authenticatedLive: Boolean = false,
    private val prepareHttpLive: (String, String?) -> AuthenticatedHttpLive? = { _, _ -> null },
    private val preferRtpTcp: Boolean,
    startMuted: Boolean,
    private val diagnosticsEnabled: Boolean = false,
    initialControls: List<NativePlaybackControl> = emptyList(),
    initialActionMessage: String? = null,
    pictureInPictureAvailable: Boolean = false,
    private val onEnterPictureInPicture: (PictureInPictureRequest) -> Boolean = { false },
    private val onCompatibilityTestStatus: (String) -> Unit = {},
    private val onClose: () -> Unit,
    private val onAutomaticReviewWatchThresholdReached: () -> Unit = {},
    private val onPlaybackEnded: () -> Unit = {},
    initialFeedbackPending: Boolean = false,
) : FrameLayout(activity) {
    private val application = activity.application as OpahApplication
    private val documentationPlayback = BuildConfig.DOCUMENTATION_MODE &&
        request.uri.startsWith(DOCUMENTATION_URI_PREFIX)
    private val playerView = PlayerView(activity).apply {
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        isFocusable = false
        setBackgroundColor(Color.BLACK)
    }
    private val documentationImage = if (documentationPlayback) {
        ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            val cameraName = request.cameraName
                ?: request.uri.removePrefix(DOCUMENTATION_URI_PREFIX).substringBefore('?')
            setImageBitmap(DocumentationSupport.images(activity).camera(cameraName)?.bitmap)
            contentDescription = "Fictional documentation camera scene"
        }
    } else {
        null
    }
    private val title = TextView(activity).apply {
        setTextColor(Color.WHITE)
        textSize = 20f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setShadowLayer(5f, 0f, 2f, Color.BLACK)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        text = request.title
    }
    private val status = TextView(activity).apply {
        setTextColor(0xFFD7E1EE.toInt())
        textSize = 13f
        maxLines = 10
        text = request.detail ?: if (request.kind == PlaybackKind.LIVE) "Live" else "Recording"
        setShadowLayer(5f, 0f, 2f, Color.BLACK)
    }
    private val progress = TextView(activity).apply {
        setTextColor(0xFFD7E1EE.toInt())
        textSize = 12f
        setShadowLayer(5f, 0f, 2f, Color.BLACK)
        visibility = if (request.kind == PlaybackKind.RECORDED) View.VISIBLE else View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val primaryControls = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        clipChildren = false
    }
    private val contextControls = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        clipChildren = false
        visibility = View.GONE
    }
    private val mainControlRow = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        clipChildren = false
    }
    private val mainControlScroller = HorizontalScrollView(activity).apply {
        isFocusable = false
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        isHorizontalScrollBarEnabled = false
        clipChildren = false
        clipToPadding = false
        setPadding(0, activity.dp(3), 0, activity.dp(3))
        addView(mainControlRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }
    private val moreControls = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }
    private val moreScroller = HorizontalScrollView(activity).apply {
        isFocusable = false
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        isHorizontalScrollBarEnabled = false
        visibility = View.GONE
        addView(moreControls, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }
    private val actionMessage = TextView(activity).apply {
        setTextColor(0xFFE6F0FB.toInt())
        textSize = 12f
        gravity = Gravity.CENTER
        visibility = View.GONE
        setPadding(activity.dp(10), activity.dp(6), activity.dp(10), activity.dp(6))
    }
    private val controlHint = TextView(activity).apply {
        setTextColor(0xFFE6F0FB.toInt())
        textSize = 12f
        gravity = Gravity.CENTER
        minHeight = activity.dp(20)
        maxWidth = activity.dp(320)
        setLines(2)
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }
    private val timeline = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        max = 1_000
        progressTintList = ColorStateList.valueOf(NativeTheme.palette.focus)
        progressBackgroundTintList = ColorStateList.valueOf(0xFF586575.toInt())
    }
    private val progressStrip = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = if (request.kind == PlaybackKind.RECORDED) View.VISIBLE else View.GONE
        isFocusable = request.kind == PlaybackKind.RECORDED
        isClickable = request.kind == PlaybackKind.RECORDED
        tag = "recorded:timeline"
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        background = activity.nativeFlatOverlayFocusableBackground()
        contentDescription = "Playback timeline, use left and right to seek 10 seconds"
        accessibilityDelegate = NativeTimelineAccessibility({ timelineRange }, ::seekBy)
        setPadding(activity.dp(10), 0, activity.dp(10), activity.dp(3))
        setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN || request.kind != PlaybackKind.RECORDED) {
                return@setOnKeyListener false
            }
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { seekBy(-10_000L); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { seekBy(10_000L); true }
                else -> false
            }
        }
        setOnClickListener { togglePlayback() }
        onFocusChangeListener = OnFocusChangeListener { _, focused ->
            if (focused) controlHint.setText(R.string.native_playback_seek_help)
        }
        addView(progress, LinearLayout.LayoutParams(activity.dp(92), LayoutParams.WRAP_CONTENT))
        addView(
            timeline,
            LinearLayout.LayoutParams(0, activity.dp(3), 1f).apply { marginStart = activity.dp(8) },
        )
    }
    private val diagnostics = TextView(activity).apply {
        setTextColor(0xFFE6F0FB.toInt())
        textSize = 12f
        background = activity.nativeGlassOverlayBackground()
        setPadding(activity.dp(10), activity.dp(7), activity.dp(10), activity.dp(7))
        maxWidth = activity.dp(520)
        visibility = View.GONE
    }
    private var httpsBackend: HttpsLivePlayer? = null
    private var player: ExoPlayer? = null
    private var liveJob: Job? = null
    private var progressJob: Job? = null
    private var muted = startMuted
    private var compatibilityTestExitScheduled = false
    private var playbackEndedReported = false
    private var automaticReviewThresholdReported = false
    private var watchedPlaybackMillis = 0L
    private var lastWatchSampleElapsedRealtime: Long? = null
    private var documentationPositionMillis =
        if (documentationPlayback) DocumentationSupport.data.nativePlaybackPositionMillis() else 0L
    private var timelineRange = NativeTimelineRange()
    private val documentationDurationMillis =
        if (documentationPlayback) DocumentationSupport.data.nativePlaybackDurationMillis() else 0L
    private var pictureInPictureReady = false
    private var pictureInPictureActive = false
    private var chromeVisible = true
    private var moreExpanded = false
    private var diagnosticsVisible = false
    private var chromeHideJob: Job? = null
    private val heldKeys = mutableSetOf<Int>()
    private var revealKey: Int? = null
    private var selectTarget: View? = null
    private var interactionOverlayOpen = false
    private var closed = false
    private var feedbackPending = initialFeedbackPending
    private val dynamicButtons = linkedMapOf<String, TextView>()
    private val coordinatorOwned = authenticatedLive || request.kind == PlaybackKind.LIVE && request.liveCompatibilityRequestId != null
    private val playPause = controlButton("Pause", R.drawable.ic_pause, "Pause playback", emphasized = true) {
        togglePlayback()
    }
    private val seekBack = controlButton("10 sec", R.drawable.ic_replay, "Back 10 seconds") { seekBy(-10_000L) }.apply {
        visibility = if (request.kind == PlaybackKind.RECORDED) View.VISIBLE else View.GONE
    }
    private val seekForward = controlButton("10 sec", R.drawable.ic_forward, "Forward 10 seconds") { seekBy(10_000L) }.apply {
        visibility = if (request.kind == PlaybackKind.RECORDED) View.VISIBLE else View.GONE
    }
    private val audio = controlButton(
        if (muted) "Muted" else "Audio",
        if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_on,
        if (muted) "Turn on audio" else "Mute audio",
    ) { toggleAudio() }
    private val retry = controlButton("Retry", R.drawable.ic_retry, "Try playback again") { retryPlayback() }.apply {
        visibility = View.GONE
    }
    private val pictureInPicture = controlButton(
        "PiP",
        R.drawable.ic_picture_in_picture,
        "Picture-in-picture",
    ) { enterPictureInPicture() }.apply {
        isEnabled = false
        alpha = 0.45f
        visibility = if (pictureInPictureAvailable && request.kind == PlaybackKind.LIVE) View.VISIBLE else View.GONE
    }
    private val diagnosticButton = controlButton(
        "Info",
        R.drawable.ic_playback_info,
        "Playback information",
    ) { toggleDiagnostics() }.apply {
        visibility = if (diagnosticsEnabled) View.VISIBLE else View.GONE
    }
    private val moreButton = controlButton("More", R.drawable.ic_more, "More playback actions") { toggleMoreControls() }.apply {
        tag = "more"
        visibility = View.GONE
    }
    private val topPanel: LinearLayout
    private val bottomPanel: LinearLayout

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        documentationImage?.let { image ->
            addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        topPanel = NativePlaybackOverlayColumn(activity, minimumDp = 220, maximumDp = 680).apply {
            tag = "playback:heading"
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(18), activity.dp(12), activity.dp(18), activity.dp(12))
            background = activity.nativeGlassOverlayBackground()
            addView(title)
            addView(status)
        }
        addView(
            topPanel,
            LayoutParams(if (request.kind == PlaybackKind.LIVE) LayoutParams.WRAP_CONTENT else activity.dp(680), LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                topMargin = activity.dp(24)
                marginStart = activity.dp(36)
                marginEnd = activity.dp(36)
            },
        )
        mainControlRow.addView(controlButton("Back", R.drawable.ic_arrow_back, "Back", action = onClose).apply { tag = "playback:back" })
        mainControlRow.addView(contextControls)
        mainControlRow.addView(primaryControls)
        primaryControls.addView(seekBack)
        primaryControls.addView(playPause)
        primaryControls.addView(seekForward)
        primaryControls.addView(audio)
        primaryControls.addView(retry)
        primaryControls.addView(pictureInPicture)
        primaryControls.addView(diagnosticButton)
        primaryControls.addView(moreButton)
        bottomPanel = NativePlaybackOverlayColumn(activity, minimumDp = 320).apply {
            tag = "playback:controls"
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(activity.dp(16), activity.dp(8), activity.dp(16), activity.dp(10))
            background = activity.nativeGlassOverlayBackground()
            clipChildren = false
            addView(
                actionMessage,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
            addView(progressStrip, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(controlHint, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(moreScroller, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(mainControlScroller, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(
            bottomPanel,
            LayoutParams(if (request.kind == PlaybackKind.LIVE) LayoutParams.WRAP_CONTENT else LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                marginStart = activity.dp(36)
                marginEnd = activity.dp(36)
                bottomMargin = activity.dp(24)
            },
        )
        addView(
            diagnostics,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = activity.dp(24)
                marginEnd = activity.dp(36)
            },
        )
        if (documentationPlayback) {
            startDocumentationPlayback()
        } else if (authenticatedLive) {
            startHttpsPlayback()
        } else if (request.kind == PlaybackKind.LIVE && request.liveCompatibilityRequestId != null) {
            startCompatibilityPlayback(request.liveCompatibilityRequestId)
        } else {
            startDirectPlayback()
        }
        updateControls(initialControls, initialActionMessage, initialFeedbackPending)
        updateDiagnostics()
        if (request.compatibilityTest) {
            bottomPanel.visibility = View.GONE
        } else {
            primaryControls.post { playPause.requestFocus() }
        }
    }

    fun updateControls(updated: List<NativePlaybackControl>, message: String?, feedbackPending: Boolean = false) {
        val messageChanged = actionMessage.text.toString() != message.orEmpty()
        val pendingChanged = this.feedbackPending != feedbackPending
        this.feedbackPending = feedbackPending
        val focusedKey = findFocus()?.tag as? String
        val dynamicControlHadFocus = focusedKey != null
        playerView.resizeMode = nativePlaybackResizeMode(
            updated.firstOrNull { it.key == "stretch" }?.selected == true,
        )
        val nextKeys = updated.map(NativePlaybackControl::key).toSet()
        dynamicButtons.keys.filter { it !in nextKeys }.toList().forEach { key ->
            dynamicButtons.remove(key)?.let { button ->
                if (selectTarget === button) {
                    button.isPressed = false
                    selectTarget = null
                }
                (button.parent as? ViewGroup)?.removeView(button)
            }
        }
        fun reconcile(group: LinearLayout, placement: NativePlaybackControlPlacement) {
            updated.filter { nativePlaybackControlPlacement(it.key) == placement }.forEachIndexed { index, control ->
                val button = dynamicButtons.getOrPut(control.key) {
                    controlButton(control.label, nativePlaybackControlIcon(control.key), control.label, action = control.onClick)
                        .apply { tag = control.key }
                }
                if (group.indexOfChild(button) != index) {
                    (button.parent as? ViewGroup)?.removeView(button)
                    group.addView(button, index)
                }
                if (button.contentDescription?.toString() != control.label) {
                    updateControlContent(button, control.label, nativePlaybackControlIcon(control.key), control.label)
                }
                button.isEnabled = control.enabled
                button.isSelected = control.selected
                button.alpha = if (control.enabled) 1f else 0.5f
                button.setOnClickListener { control.onClick() }
                if (!control.enabled && selectTarget === button) {
                    button.isPressed = false
                    selectTarget = null
                }
            }
        }
        reconcile(contextControls, NativePlaybackControlPlacement.CONTEXT)
        reconcile(moreControls, NativePlaybackControlPlacement.MORE)
        contextControls.visibility = if (contextControls.isEmpty()) View.GONE else View.VISIBLE
        moreButton.visibility = if (moreControls.isEmpty()) View.GONE else View.VISIBLE
        if (moreControls.isEmpty()) moreExpanded = false
        updateMoreControlsVisibility()
        actionMessage.text = message.orEmpty()
        actionMessage.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
        if (dynamicControlHadFocus && chromeVisible && !interactionOverlayOpen &&
            findFocus()?.takeIf { bottomPanel.isAncestorOf(it) && it.isEnabled && it.isFocusable && it.isShown } == null
        ) {
            bottomPanel.post {
                if (closed || !chromeVisible || interactionOverlayOpen) return@post
                val target = findViewWithTag<View>(focusedKey)?.takeIf { it.isEnabled && it.isFocusable }
                    ?: contextControls.firstEnabledFocusableChild()
                    ?: primaryControls.firstEnabledFocusableChild()
                    ?: moreControls.firstEnabledFocusableChild()
                target?.requestFocus()
            }
        }
        if ((messageChanged && !message.isNullOrBlank()) || (pendingChanged && feedbackPending)) {
            showChrome(requestFocus = false)
        } else if (messageChanged || pendingChanged) {
            scheduleChromeHide()
        }
    }

    fun close() {
        closed = true
        selectTarget?.isPressed = false
        selectTarget = null
        heldKeys.clear()
        chromeHideJob?.cancel()
        chromeHideJob = null
        liveJob?.cancel()
        liveJob = null
        progressJob?.cancel()
        progressJob = null
        stopHttpsPlayback()
        playerView.player = null
        if (!coordinatorOwned) player?.release()
        player = null
    }

    fun setPictureInPictureActive(active: Boolean) {
        pictureInPictureActive = active
        if (active) {
            selectTarget?.isPressed = false
            selectTarget = null
            heldKeys.clear()
            revealKey = null
            chromeHideJob?.cancel()
            topPanel.visibility = View.GONE
            bottomPanel.visibility = View.GONE
            diagnostics.visibility = View.GONE
        } else {
            showChrome(requestFocus = false)
        }
    }

    /** Controller-owned selectors and dialogs suspend idle chrome until dismissed. */
    fun setInteractionOverlayOpen(open: Boolean) {
        interactionOverlayOpen = open
        selectTarget?.isPressed = false
        selectTarget = null
        heldKeys.clear()
        revealKey = null
        if (open) chromeHideJob?.cancel() else scheduleChromeHide()
    }

    /** Remote and system Back dismiss all visible playback controls before leaving. */
    fun dismissPlaybackOsd(): Boolean {
        if (closed || pictureInPictureActive || !chromeVisible) return false
        chromeHideJob?.cancel()
        chromeHideJob = null
        selectTarget?.isPressed = false
        selectTarget = null
        heldKeys.clear()
        revealKey = null
        moreExpanded = false
        diagnosticsVisible = false
        updateMoreControlsVisibility()
        diagnosticButton.isSelected = false
        hideChrome(force = true)
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (closed) return true
        val key = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        val up = event.action == KeyEvent.ACTION_UP
        if (down) {
            heldKeys.add(key)
            chromeHideJob?.cancel()
        } else if (up) {
            heldKeys.remove(key)
        }
        // Consume the entire press that reveals chrome, including repeats and its key-up.
        if (revealKey == key) {
            if (up) {
                revealKey = null
                scheduleChromeHide()
            }
            return true
        }
        if (key == KeyEvent.KEYCODE_BACK) {
            // NativeRootLayout consumes the matching key-up after dispatching Back down.
            heldKeys.remove(key)
            if (down && event.repeatCount == 0 && !event.isCanceled && !dismissPlaybackOsd()) onClose()
            return true
        }
        if (key in playbackMediaKeys) {
            if (down && event.repeatCount == 0) {
                when (key) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> togglePlayback()
                    KeyEvent.KEYCODE_MEDIA_PLAY -> player?.play()
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> player?.pause()
                    KeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-10_000L)
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(10_000L)
                }
            }
            if (up) scheduleChromeHide()
            return true
        }
        val select = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER ||
            key == KeyEvent.KEYCODE_NUMPAD_ENTER
        val directional = key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT ||
            key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
        val chromeActuallyVisible = topPanel.isVisible &&
            (request.compatibilityTest || bottomPanel.isVisible)
        if (down && (select || directional) && (!chromeVisible || !chromeActuallyVisible) && !pictureInPictureActive) {
            revealKey = key
            showChrome(requestFocus = true)
            return true
        }
        if (select) {
            if (down && event.repeatCount == 0) {
                selectTarget = findFocus()?.takeIf { it.isClickable && it.isEnabled && it.isShown }
                selectTarget?.isPressed = true
            } else if (up) {
                val target = selectTarget
                selectTarget = null
                target?.isPressed = false
                // A refreshed action row cannot redirect a held Select to a different action.
                if (!event.isCanceled && target != null && target === findFocus() &&
                    target.isAttachedToWindow && target.isShown && target.isEnabled && !interactionOverlayOpen
                ) target.performClick()
                scheduleChromeHide()
            }
            return true
        }
        val handled = super.dispatchKeyEvent(event)
        if (up) scheduleChromeHide()
        return handled
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) {
            selectTarget?.isPressed = false
            selectTarget = null
            heldKeys.clear()
            revealKey = null
            chromeHideJob?.cancel()
        } else {
            scheduleChromeHide()
        }
    }

    private fun controlButton(
        label: String,
        iconRes: Int?,
        accessibilityLabel: String,
        emphasized: Boolean = false,
        action: () -> Unit,
    ): TextView = TextView(activity).apply {
        text = if (iconRes == null) label else ""
        textSize = 13f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        isFocusable = true
        isClickable = true
        contentDescription = accessibilityLabel
        background = activity.nativeFlatOverlayFocusableBackground()
        if (emphasized) setTypeface(typeface, android.graphics.Typeface.BOLD)
        iconRes?.let { resource ->
            setCompoundDrawablesRelativeWithIntrinsicBounds(resource, 0, 0, 0)
            compoundDrawablePadding = 0
            compoundDrawablesRelative.forEach { drawable -> drawable?.setTint(Color.WHITE) }
        }
        setPadding(activity.dp(8), activity.dp(5), activity.dp(8), activity.dp(5))
        layoutParams = LinearLayout.LayoutParams(
            if (iconRes == null) LayoutParams.WRAP_CONTENT else activity.dp(42),
            activity.dp(40),
        ).apply {
            marginStart = activity.dp(2)
            marginEnd = activity.dp(2)
        }
        onFocusChangeListener = OnFocusChangeListener { _, focused ->
            applyNativeFocusMotion(focused, scale = 1.05f)
            if (focused) {
                controlHint.text = contentDescription
            } else if (controlHint.text == contentDescription) {
                controlHint.text = ""
            }
        }
        setOnClickListener { action() }
    }

    private fun nativePlaybackControlIcon(key: String): Int? = when (key) {
        "previous" -> R.drawable.ic_chevron_left
        "next" -> R.drawable.ic_chevron_right
        "next-activity" -> R.drawable.ic_forward
        "return-live" -> R.drawable.ic_videocam
        "rewind" -> R.drawable.ic_replay
        "reviewed" -> R.drawable.ic_mark_reviewed
        "save" -> R.drawable.ic_save_recording
        "stretch" -> R.drawable.ic_stretch
        "recording" -> R.drawable.ic_videocam
        "snapshot" -> R.drawable.ic_snapshot
        else -> null
    }

    private fun toggleMoreControls() {
        moreExpanded = !moreExpanded
        if (moreExpanded) {
            diagnosticsVisible = false
            diagnostics.visibility = View.GONE
            diagnosticButton.isSelected = false
        }
        updateMoreControlsVisibility()
        if (moreExpanded) {
            val target = (0 until moreControls.childCount)
                .map(moreControls::getChildAt)
                .firstOrNull { it.isEnabled && it.isFocusable }
            target?.requestFocus()
        } else {
            moreButton.requestFocus()
        }
        showChrome(requestFocus = false)
    }

    private fun updateMoreControlsVisibility() {
        moreScroller.visibility = if (moreExpanded && moreControls.isNotEmpty()) View.VISIBLE else View.GONE
        moreButton.isSelected = moreExpanded
    }

    private fun toggleDiagnostics() {
        diagnosticsVisible = !diagnosticsVisible
        if (diagnosticsVisible) {
            moreExpanded = false
            updateMoreControlsVisibility()
        }
        diagnostics.visibility = if (diagnosticsVisible && chromeVisible) View.VISIBLE else View.GONE
        diagnosticButton.isSelected = diagnosticsVisible
        updateDiagnostics()
        if (diagnosticsVisible) chromeHideJob?.cancel() else scheduleChromeHide()
    }

    private fun showChrome(requestFocus: Boolean) {
        if (pictureInPictureActive) return
        chromeVisible = true
        topPanel.visibility = View.VISIBLE
        bottomPanel.visibility = if (request.compatibilityTest) View.GONE else View.VISIBLE
        diagnostics.visibility = if (diagnosticsVisible) View.VISIBLE else View.GONE
        val focused = findFocus()
        if (
            !request.compatibilityTest &&
            !interactionOverlayOpen &&
            (requestFocus || focused == null || focused === this || !bottomPanel.isAncestorOf(focused))
        ) {
            playPause.requestFocus()
        }
        scheduleChromeHide()
    }

    private fun hideChrome(force: Boolean = false) {
        if (!force && !canHideChrome()) return
        chromeVisible = false
        topPanel.visibility = View.GONE
        bottomPanel.visibility = View.GONE
        diagnostics.visibility = View.GONE
        requestFocus()
    }

    private fun scheduleChromeHide() {
        chromeHideJob?.cancel()
        chromeHideJob = null
        if (!canHideChrome()) return
        chromeHideJob = activity.lifecycleScope.launch {
            delay(4_500)
            hideChrome()
        }
    }

    private fun canHideChrome(): Boolean = !closed && !request.compatibilityTest &&
        !pictureInPictureActive && !interactionOverlayOpen && !moreExpanded && !diagnosticsVisible &&
        heldKeys.isEmpty() && revealKey == null && selectTarget == null && !feedbackPending &&
        hasWindowFocus() && player?.isPlaying == true

    private fun stopHttpsPlayback() {
        val backend = httpsBackend ?: return
        httpsBackend = null
        if (!backend.close()) status.setText(R.string.native_https_cleanup_failed)
    }

    private fun startHttpsPlayback() {
        retry.visibility = View.GONE
        liveJob = activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val live = request.cameraName?.let { prepareHttpLive(it, request.liveStreamName) }
                if (closed || live == null || !live.isAuthorized()) {
                    status.setText(R.string.native_https_unavailable)
                    return@repeatOnLifecycle
                }
                status.setText(R.string.native_https_connecting)
                val target = PlayerViewMedia3VideoOutputTarget(playerView, ::attachPlayer) {
                    if (player === it) detachPlayer()
                }
                val attempt = PlaybackAttemptId(PlaybackSessionId("https-${UUID.randomUUID()}"), 1)
                val lease = awaitExclusiveVideoOutput(
                    ProcessMedia3VideoOutputRegistry.instance, target, attempt,
                    { !closed && live.isAuthorized() },
                )
                if (lease == null) {
                    status.setText(R.string.native_https_busy)
                    return@repeatOnLifecycle
                }
                val backend = try {
                    HttpsLivePlayer(activity, application.container.httpClient, lease)
                } catch (_: Exception) {
                    lease.finish { false }
                    status.setText(R.string.native_https_setup_failed)
                    return@repeatOnLifecycle
                }
                httpsBackend = backend
                var firstFrame = false
                var failure: String? = null
                val listener = object : Player.Listener {
                    override fun onRenderedFirstFrame() { firstFrame = true }
                    override fun onPlayerError(error: PlaybackException) {
                        failure = "HTTPS playback failed: ${liveFailureStatus(error) ?: error.errorCodeName}"
                    }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) failure = "Live connection ended"
                    }
                }
                try {
                    backend.player.addListener(listener)
                    failure = app.opah.tv.playback.runAuthorizedLiveSession(
                        authorized = { !closed && live.isAuthorized() },
                        start = { backend.prepare(live.uri, LivePlaybackOptions(videoOnly = muted)) },
                        firstFrame = { firstFrame },
                        failure = { failure },
                        close = {
                            if (httpsBackend === backend) {
                                backend.player.removeListener(listener)
                                stopHttpsPlayback()
                            }
                        },
                    )
                    failure?.let { status.text = activity.getString(R.string.native_https_retry_detail, it) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failure = "HTTPS playback failed"
                    status.setText(R.string.native_https_failed)
                } finally {
                    if (httpsBackend === backend) {
                        backend.player.removeListener(listener)
                        failure?.let { status.text = activity.getString(R.string.native_https_retry_detail, it) }
                        stopHttpsPlayback()
                    }
                }
            }
        }
    }

    private fun startDirectPlayback() {
        retry.visibility = View.GONE
        status.setText(R.string.native_playback_starting)
        runCatching {
            when (request.kind) {
                PlaybackKind.LIVE -> {
                    val livePlayer = application.container.livePlayerFactory.create(activity, false)
                    livePlayer.prepare(
                        request.uri,
                        LivePlaybackOptions(forceRtpTcp = preferRtpTcp, videoOnly = false),
                    )
                    livePlayer.player
                }
                PlaybackKind.RECORDED -> RecordedPlayerFactory.create(
                    activity,
                    application.container.httpClient,
                    preferSoftwareVideoDecoder = false,
                ).apply {
                    val item = MediaItem.Builder().setUri(request.uri).apply {
                        val path = request.uri.substringBefore('?').lowercase()
                        when {
                            path.endsWith(".m3u8") -> setMimeType("application/x-mpegURL")
                            path.endsWith(".mp4") -> setMimeType("video/mp4")
                        }
                    }.build()
                    setMediaItem(item)
                    prepare()
                }
            }
        }.onSuccess(::attachPlayer).onFailure {
            status.setText(R.string.native_playback_start_failed)
        }
    }

    private fun startDocumentationPlayback() {
        status.text = request.detail ?: if (request.kind == PlaybackKind.LIVE) "Live" else "Recording"
        if (request.kind == PlaybackKind.LIVE) {
            pictureInPictureReady = true
            pictureInPicture.isEnabled = true
            pictureInPicture.alpha = 1f
        } else {
            updateDocumentationProgress()
        }
        updatePlayPauseControl(true)
    }

    private fun startCompatibilityPlayback(rawRequestId: String) {
        retry.visibility = View.GONE
        status.text = if (request.compatibilityTest) {
            "Checking camera compatibility automatically"
        } else {
            "Finding a compatible stream"
        }
        liveJob = activity.lifecycleScope.launch {
            val target = PlayerViewMedia3VideoOutputTarget(
                playerView = playerView,
                onAttached = { attached -> attachPlayer(attached) },
                onDetached = { detached -> if (player === detached) detachPlayer() },
            )
            val session = try {
                application.container.singleLivePlaybackCoordinator.open(
                    SingleLivePlaybackRequestId(rawRequestId),
                    target,
                    videoOnly = request.compatibilityTestVideoOnly,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status.setText(R.string.native_live_start_failed)
                return@launch
            }
            try {
                session.snapshots.collect { snapshot ->
                    val state = snapshot.state
                    status.text = when (state) {
                        is PlaybackCompatibilityState.Attempting -> "Trying a playback option"
                        is PlaybackCompatibilityState.Recovering -> "Trying the next playback option"
                        is PlaybackCompatibilityState.Verified,
                        is PlaybackCompatibilityState.UnpersistedLive,
                        is PlaybackCompatibilityState.Persisting,
                        -> "Playing with a verified camera setting"
                        is PlaybackCompatibilityState.Finished -> when (state.result) {
                            is PlaybackResult.VerifiedLive,
                            is PlaybackResult.UnpersistedLive,
                            -> "Playing with a verified camera setting"
                            is PlaybackResult.Cancelled -> "Compatibility check stopped"
                            else -> playbackFailureSummary(state.result) +
                                "\nTrigger: ${snapshot.diagnosticReleaseTrigger}" +
                                "\nInitial cleanup: ${snapshot.diagnosticInitialCleanup} (${snapshot.diagnosticCleanupMillis}ms)" +
                                "\nLatest cleanup: ${snapshot.diagnosticReleaseStage}" +
                                (snapshot.diagnosticPriorFailure?.let {
                                    "\nPrior: ${it.category.name} / ${it.phase.name}" +
                                        "\nCode: ${it.diagnosticCode.name}"
                                } ?: "\nPrior: not captured")
                        }
                        is PlaybackCompatibilityState.Idle -> "Preparing compatibility check"
                        is PlaybackCompatibilityState.Releasing,
                        is PlaybackCompatibilityState.ReleasedAwaitingPersistence,
                        -> "Saving the camera setting"
                        is PlaybackCompatibilityState.PresentingSnapshot -> "Confirming video output"
                    }
                    if (request.compatibilityTest) {
                        nativeCompatibilityTestStatus(request.title, state)?.let(onCompatibilityTestStatus)
                    }
                    if (
                        request.compatibilityTest &&
                        nativeCompatibilityTestComplete(state) &&
                        !compatibilityTestExitScheduled
                    ) {
                        compatibilityTestExitScheduled = true
                        delay(COMPATIBILITY_TEST_RESULT_DISPLAY_MILLIS)
                        onClose()
                    }
                }
            } finally {
                withContext(NonCancellable) { session.close() }
                detachPlayer()
            }
        }
    }

    private fun attachPlayer(attached: ExoPlayer) {
        if (player !== attached) {
            if (!coordinatorOwned) player?.release()
            player = attached
        }
        playerView.player = attached
        attached.volume = if (muted) 0f else 1f
        attached.playWhenReady = true
        attached.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                updatePlayPauseControl(attached.isPlaying)
                when (playbackState) {
                    Player.STATE_IDLE -> Unit
                    Player.STATE_BUFFERING -> status.setText(R.string.native_playback_loading)
                    Player.STATE_READY -> if (!request.compatibilityTest) {
                        status.text = request.detail ?: if (request.kind == PlaybackKind.LIVE) "Live" else "Recording"
                        if (request.kind == PlaybackKind.LIVE) {
                            pictureInPictureReady = true
                            pictureInPicture.isEnabled = true
                            pictureInPicture.alpha = 1f
                        }
                    }
                    Player.STATE_ENDED -> {
                        status.setText(R.string.native_playback_finished)
                        showChrome(requestFocus = false)
                        if (!playbackEndedReported) {
                            playbackEndedReported = true
                            onPlaybackEnded()
                        }
                    }
                }
                updateDiagnostics()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseControl(isPlaying)
                if (isPlaying) scheduleChromeHide() else showChrome(requestFocus = false)
            }

            override fun onPlayerError(error: PlaybackException) {
                status.setText(R.string.native_playback_failed)
                retry.visibility = if (coordinatorOwned) View.GONE else View.VISIBLE
                updateDiagnostics()
                showChrome(requestFocus = false)
            }

            override fun onEvents(player: Player, events: Player.Events) {
                updateDiagnostics()
            }
        })
        startProgressUpdates(attached)
        updateDiagnostics()
    }

    private fun detachPlayer() {
        timelineRange = NativeTimelineRange()
        progressJob?.cancel()
        progressJob = null
        playerView.player = null
        player = null
    }

    private fun seekBy(offsetMillis: Long) {
        if (request.kind != PlaybackKind.RECORDED) return
        if (documentationPlayback) {
            documentationPositionMillis = boundedSeekPosition(
                documentationPositionMillis,
                documentationDurationMillis,
                offsetMillis,
            )
            updateDocumentationProgress()
            return
        }
        val active = player ?: return
        val duration = active.duration.takeIf { it > 0L }
        if (!active.isCurrentMediaItemSeekable || duration == null) {
            status.setText(R.string.native_playback_seek_unavailable)
            showChrome(requestFocus = false)
            return
        }
        active.seekTo(boundedSeekPosition(active.currentPosition, duration, offsetMillis))
        updateProgress(active)
    }

    private fun updateDocumentationProgress() {
        timelineRange = NativeTimelineRange(documentationPositionMillis, documentationDurationMillis, seekable = true)
        progress.text = activity.getString(
            R.string.native_playback_progress,
            playbackTime(documentationPositionMillis),
            playbackTime(documentationDurationMillis),
        )
        timeline.progress = (
            documentationPositionMillis.toDouble() / documentationDurationMillis.toDouble() * timeline.max
            ).toInt().coerceIn(0, timeline.max)
        progressStrip.contentDescription =
            "Playback timeline, ${progress.text}, use left and right to seek 10 seconds"
    }

    private fun startProgressUpdates(active: ExoPlayer) {
        progressJob?.cancel()
        if (request.kind != PlaybackKind.RECORDED) return
        progressJob = activity.lifecycleScope.launch {
            while (player === active) {
                updateProgress(active)
                delay(500)
            }
        }
    }

    private fun updateProgress(active: Player) {
        recordAutomaticReviewWatch(active)
        val position = active.currentPosition.coerceAtLeast(0L)
        val duration = active.duration.takeIf { it > 0 }
        val seekable = active.isCurrentMediaItemSeekable && duration != null
        timelineRange = NativeTimelineRange(position, duration, seekable)
        progressStrip.isEnabled = seekable
        progressStrip.alpha = if (seekable) 1f else 0.55f
        progress.text = if (duration != null) {
            "${playbackTime(position)} / ${playbackTime(duration)}"
        } else {
            playbackTime(position)
        }
        timeline.progress = if (duration != null) {
            ((position.toDouble() / duration.toDouble()) * timeline.max)
                .toInt()
                .coerceIn(0, timeline.max)
        } else {
            0
        }
        progressStrip.contentDescription = if (duration != null) {
            "Playback timeline, ${playbackTime(position)} of ${playbackTime(duration)}, " +
                if (seekable) "use left and right to seek 10 seconds" else "loading"
        } else {
            "Playback timeline loading"
        }
    }

    private fun recordAutomaticReviewWatch(active: Player) {
        val now = SystemClock.elapsedRealtime()
        if (active.isPlaying && active.playbackState == Player.STATE_READY) {
            lastWatchSampleElapsedRealtime?.let { previous ->
                watchedPlaybackMillis += (now - previous).coerceIn(0L, 1_000L)
            }
            lastWatchSampleElapsedRealtime = now
        } else {
            lastWatchSampleElapsedRealtime = null
        }
        val duration = active.duration.takeIf { it > 0L }
        if (
            !automaticReviewThresholdReported &&
            request.activityItemId != null &&
            watchedEnoughForAutomaticReview(watchedPlaybackMillis, duration)
        ) {
            automaticReviewThresholdReported = true
            onAutomaticReviewWatchThresholdReached()
        }
    }

    private fun playbackTime(milliseconds: Long): String {
        val totalSeconds = (milliseconds / 1_000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    private fun retryPlayback() {
        if (coordinatorOwned) return
        progressJob?.cancel()
        progressJob = null
        playerView.player = null
        player?.release()
        player = null
        playbackEndedReported = false
        startDirectPlayback()
    }

    private fun togglePlayback() {
        val active = player ?: return
        if (active.isPlaying) {
            active.pause()
        } else {
            if (active.playbackState == Player.STATE_ENDED) active.seekTo(0L)
            active.play()
        }
        updatePlayPauseControl(active.isPlaying)
        if (active.isPlaying) scheduleChromeHide() else showChrome(requestFocus = false)
    }

    private fun toggleAudio() {
        muted = !muted
        player?.volume = if (muted) 0f else 1f
        updateControlContent(
            view = audio,
            label = if (muted) "Muted" else "Audio",
            iconRes = if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_on,
            accessibilityLabel = if (muted) "Turn on audio" else "Mute audio",
        )
        updateDiagnostics()
    }

    private fun updatePlayPauseControl(isPlaying: Boolean) {
        updateControlContent(
            view = playPause,
            label = if (isPlaying) "Pause" else "Play",
            iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
            accessibilityLabel = if (isPlaying) "Pause playback" else "Resume playback",
        )
    }

    private fun updateControlContent(
        view: TextView,
        label: String,
        iconRes: Int?,
        accessibilityLabel: String,
    ) {
        view.text = if (iconRes == null) label else ""
        view.contentDescription = accessibilityLabel
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes ?: 0, 0, 0, 0)
        view.compoundDrawablesRelative.forEach { drawable -> drawable?.setTint(Color.WHITE) }
        if (view.hasFocus()) controlHint.text = accessibilityLabel
    }

    private fun View.isAncestorOf(descendant: View): Boolean {
        var parent = descendant.parent
        while (parent is View) {
            if (parent === this) return true
            parent = parent.parent
        }
        return false
    }

    private fun LinearLayout.firstEnabledFocusableChild(): View? =
        (0 until childCount)
            .map(::getChildAt)
            .firstOrNull { it.isEnabled && it.isFocusable && it.isVisible }

    private fun updateDiagnostics() {
        if (!diagnosticsEnabled) return
        val active = player
        val playbackState = when (active?.playbackState) {
            Player.STATE_BUFFERING -> "Buffering"
            Player.STATE_READY -> if (active.isPlaying) "Playing" else "Ready"
            Player.STATE_ENDED -> "Finished"
            else -> "Starting"
        }
        val video = active?.videoFormat
        val audioFormat = active?.audioFormat
        diagnostics.text = buildList {
            add("$playbackState • ${if (request.kind == PlaybackKind.LIVE) "Live" else "Recording"}")
            if (request.kind == PlaybackKind.LIVE) add(if (preferRtpTcp) "Reliable TCP transport preferred" else "Automatic transport")
            video?.let { format ->
                val size = if (format.width > 0 && format.height > 0) "${format.width} × ${format.height}" else "Size pending"
                add("Video: ${format.sampleMimeType ?: "unknown"} • $size")
            }
            audioFormat?.let { format -> add("Audio: ${format.sampleMimeType ?: "unknown"}${if (muted) " • muted" else ""}") }
        }.joinToString("\n")
    }

    private fun enterPictureInPicture() {
        if (!pictureInPictureReady) return
        val videoSize = player?.videoSize
        val sourceRect = Rect().takeIf { playerView.getGlobalVisibleRect(it) }
        setPictureInPictureActive(true)
        val entered = onEnterPictureInPicture(
            PictureInPictureRequest(
                title = request.title,
                subtitle = request.detail ?: "Live",
                aspectRatio = videoSize?.let { pipAspectRatio(it.width, it.height) },
                sourceRectHint = sourceRect,
            ),
        )
        if (!entered) {
            setPictureInPictureActive(false)
            status.setText(R.string.native_picture_in_picture_unavailable)
        }
    }
}

internal fun nativeCompatibilityTestComplete(state: PlaybackCompatibilityState): Boolean = when (state) {
    is PlaybackCompatibilityState.Verified,
    is PlaybackCompatibilityState.UnpersistedLive,
    -> true
    is PlaybackCompatibilityState.Finished -> nativeCompatibilityTestResultComplete(state.result)
    else -> false
}

internal fun nativeCompatibilityTestResultComplete(result: PlaybackResult): Boolean =
    result !is PlaybackResult.Cancelled

internal fun nativeCompatibilityTestStatus(
    cameraName: String,
    state: PlaybackCompatibilityState,
): String? = when (state) {
    is PlaybackCompatibilityState.Attempting ->
        "$cameraName: trying choice ${state.run.attemptsStarted.coerceAtLeast(1)} of " +
            "${state.plan.budget.maxAttempts + state.plan.budget.maxRecoveryCycles} • " +
            nativeCompatibilityChoiceLabel(state.attempt.candidate)
    is PlaybackCompatibilityState.Recovering ->
        "$cameraName: reconnecting before the next safe choice"
    is PlaybackCompatibilityState.Verified ->
        "$cameraName: choice saved • ${nativeCompatibilityChoiceLabel(state.result.strategy.candidate)}"
    is PlaybackCompatibilityState.UnpersistedLive ->
        "$cameraName: video works for now, but the choice could not be saved"
    is PlaybackCompatibilityState.Persisting ->
        "$cameraName: video works • saving ${nativeCompatibilityChoiceLabel(state.strategy.candidate)}"
    is PlaybackCompatibilityState.Finished -> when (val result = state.result) {
        is PlaybackResult.VerifiedLive ->
            "$cameraName: choice saved • ${nativeCompatibilityChoiceLabel(result.strategy.candidate)}"
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

private fun nativeCompatibilityChoiceLabel(candidate: PlaybackCandidate): String = buildList {
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
