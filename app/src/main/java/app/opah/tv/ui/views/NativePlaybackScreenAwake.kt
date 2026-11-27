package app.opah.tv.ui.views

import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.opah.tv.playback.PlaybackAwakeState
import app.opah.tv.playback.PlaybackScreenAwakePolicy

/** Owns only a dedicated playback root's display request; never changes the Activity window. */
@UnstableApi
internal class NativePlaybackScreenAwake(
    private val lifecycleOwner: LifecycleOwner,
    private val host: View,
    private val enabled: Boolean = true,
    private val elapsedMillis: () -> Long = SystemClock::elapsedRealtime,
) : View.OnAttachStateChangeListener, LifecycleEventObserver {
    internal class Stream internal constructor()

    private inner class Entry {
        var waiting = false
        var binding: Binding? = null
    }

    private val policy = PlaybackScreenAwakePolicy()
    private val entries = linkedMapOf<Stream, Entry>()
    private val priorKeepScreenOn = host.keepScreenOn
    private var closed = false
    private var attached = host.isAttachedToWindow
    private var wasVisible = false
    private var layoutObserver: ViewTreeObserver? = null
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { refresh() }
    private val sample = Runnable { refresh() }

    init {
        if (enabled) {
            host.addOnAttachStateChangeListener(this)
            lifecycleOwner.lifecycle.addObserver(this)
            if (host.isAttachedToWindow) onViewAttachedToWindow(host)
        }
    }

    fun openStream(): Stream = Stream().also { if (enabled && !closed) entries[it] = Entry() }

    /** A player replacement retains the logical stream and its original no-video deadline. */
    fun awaitPlayer(stream: Stream) {
        val entry = entries[stream] ?: return
        entry.binding?.dispose()
        entry.binding = null
        entry.waiting = true
        refresh()
    }

    fun bindPlayer(stream: Stream, player: ExoPlayer) {
        val entry = entries[stream] ?: return
        entry.binding?.dispose()
        entry.waiting = false
        val binding = Binding(stream, player)
        entry.binding = binding
        player.addListener(binding)
        refresh()
    }

    fun stopStream(stream: Stream) {
        val entry = entries[stream] ?: return
        entry.binding?.dispose()
        entry.binding = null
        entry.waiting = false
        refresh()
    }

    fun removeStream(stream: Stream) {
        val entry = entries.remove(stream) ?: return
        entry.binding?.dispose()
        refresh()
    }

    fun explicitAction() {
        if (!closed) policy.explicitAction()
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (event == Lifecycle.Event.ON_DESTROY) close() else refresh()
    }

    override fun onViewAttachedToWindow(v: View) {
        if (closed) return
        attached = true
        layoutObserver = host.viewTreeObserver.also { it.addOnGlobalLayoutListener(layoutListener) }
        refresh()
    }

    override fun onViewDetachedFromWindow(v: View) {
        attached = false
        removeLayoutListener()
        refresh()
    }

    fun close() {
        if (closed) return
        closed = true
        policy.close()
        host.removeCallbacks(sample)
        removeLayoutListener()
        host.removeOnAttachStateChangeListener(this)
        lifecycleOwner.lifecycle.removeObserver(this)
        entries.values.forEach { it.binding?.dispose() }
        entries.clear()
        host.keepScreenOn = priorKeepScreenOn
    }

    private fun removeLayoutListener() {
        layoutObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutListener)
        layoutObserver = null
    }

    internal fun refresh() {
        if (!enabled || closed) return
        host.removeCallbacks(sample)
        val visible = attached && host.isAttachedToWindow && host.isShown && host.windowVisibility == View.VISIBLE &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (visible != wasVisible) entries.values.forEach { it.binding?.resetVideoEvidence() }
        wasVisible = visible
        val states = entries.values.map { entry ->
            entry.binding?.state() ?: if (entry.waiting) PlaybackAwakeState.CONNECTING else PlaybackAwakeState.INACTIVE
        }
        val now = elapsedMillis()
        val decision = policy.update(states, visible, now)
        val requested = priorKeepScreenOn || decision.keepScreenOn
        if (host.keepScreenOn != requested) host.keepScreenOn = requested
        if (!visible) return
        val polling = entries.values.any { it.binding != null } && states.any { it != PlaybackAwakeState.INACTIVE }
        val delay = listOfNotNull(
            SAMPLE_MILLIS.takeIf { polling },
            decision.nextCheckAtMillis?.let { (it - now).coerceAtLeast(1L) },
        ).minOrNull()
        if (delay != null) host.postDelayed(sample, delay)
    }

    private inner class Binding(private val stream: Stream, private val player: ExoPlayer) : Player.Listener {
        private var rendered = false
        private var lastCounters: Any? = null
        private var lastRenderedCount = 0
        private var prepared = player.playbackState != Player.STATE_IDLE
        private val current: Boolean get() = !closed && entries[stream]?.binding === this

        init { resetVideoEvidence() }

        fun resetVideoEvidence() {
            rendered = false
            val counters = player.videoDecoderCounters?.also { it.ensureUpdated() }
            lastCounters = counters
            lastRenderedCount = counters?.renderedOutputBufferCount ?: 0
        }

        override fun onRenderedFirstFrame() {
            if (!current) return
            rendered = true
            refresh()
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (current) refresh()
        }

        fun state(): PlaybackAwakeState = runCatching {
            val playbackState = player.playbackState
            if (playbackState != Player.STATE_IDLE) prepared = true
            val tracks = player.currentTracks
            val videoSelected = tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
            val counters = player.videoDecoderCounters?.also { it.ensureUpdated() }
            val count = counters?.renderedOutputBufferCount ?: 0
            if (player.isPlaying && count > 0 && (counters !== lastCounters || count > lastRenderedCount)) rendered = true
            lastCounters = counters
            lastRenderedCount = count
            if (!player.isPlaying) rendered = false
            when {
                player.playerError != null || playbackState == Player.STATE_ENDED ||
                    (prepared && playbackState == Player.STATE_IDLE) -> PlaybackAwakeState.INACTIVE
                !player.playWhenReady || (!tracks.isEmpty && !videoSelected) -> PlaybackAwakeState.INACTIVE
                player.isPlaying && videoSelected && rendered -> PlaybackAwakeState.PLAYING_VIDEO
                else -> PlaybackAwakeState.CONNECTING
            }
        }.getOrDefault(PlaybackAwakeState.INACTIVE)

        fun dispose() { player.removeListener(this) }
    }

    private companion object {
        const val SAMPLE_MILLIS = 250L
    }
}
