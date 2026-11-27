package app.opah.tv.playback

internal enum class PlaybackAwakeState { PLAYING_VIDEO, CONNECTING, INACTIVE }

internal data class PlaybackAwakeDecision(
    val keepScreenOn: Boolean,
    val nextCheckAtMillis: Long? = null,
)

/** One no-video budget per viewing episode, shared across players and automatic retries. */
internal class PlaybackScreenAwakePolicy {
    private var closed = false
    private var graceAvailable = true
    private var graceDeadline: Long? = null
    private var lastElapsedMillis = 0L

    fun update(
        states: Collection<PlaybackAwakeState>,
        visible: Boolean,
        elapsedMillis: Long,
    ): PlaybackAwakeDecision {
        val now = maxOf(lastElapsedMillis, elapsedMillis)
        lastElapsedMillis = now
        if (closed || !visible) return PlaybackAwakeDecision(false)
        if (PlaybackAwakeState.PLAYING_VIDEO in states) {
            graceAvailable = true
            graceDeadline = null
            return PlaybackAwakeDecision(true)
        }
        if (PlaybackAwakeState.CONNECTING !in states) return PlaybackAwakeDecision(false)
        if (graceAvailable) {
            graceAvailable = false
            graceDeadline = if (now > Long.MAX_VALUE - GRACE_MILLIS) Long.MAX_VALUE else now + GRACE_MILLIS
        }
        val deadline = graceDeadline ?: return PlaybackAwakeDecision(false)
        return if (now < deadline) PlaybackAwakeDecision(true, deadline) else PlaybackAwakeDecision(false)
    }

    /** Only an actual user viewing/retry action can renew an exhausted startup budget. */
    fun explicitAction() {
        if (closed) return
        graceAvailable = true
        graceDeadline = null
    }

    fun close() {
        closed = true
        graceAvailable = false
        graceDeadline = null
    }

    companion object {
        const val GRACE_MILLIS = 30_000L
    }
}
