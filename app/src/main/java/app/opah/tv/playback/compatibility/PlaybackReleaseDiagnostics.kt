package app.opah.tv.playback.compatibility

/** Only fixed labels, enum names and elapsed durations; never routes or exception messages. */
internal class PlaybackReleaseDiagnostics {
    var trigger: String = "NOT_CAPTURED"
        private set
    var initialCleanup: String = "NOT_STARTED"
        private set
    var cleanupMillis: Long = 0
        private set

    fun begin(trigger: String) {
        this.trigger = trigger
        initialCleanup = "NOT_STARTED"
        cleanupMillis = 0
    }

    fun completed(force: Boolean, stage: String, elapsedMillis: Long) {
        // Forced cleanup is a recovery operation, not evidence that the original release passed.
        if (force) return
        initialCleanup = stage
        cleanupMillis = elapsedMillis.coerceAtLeast(0)
    }
}

internal fun playbackReleaseTrigger(event: PlaybackEvent): String = when (event) {
    is PlaybackEvent.AttemptFailed -> event.failure.diagnosticCode.name
    is PlaybackEvent.ProbeDeadlineReached -> "${event.phase.name}_DEADLINE"
    is PlaybackEvent.Cancel -> "CANCEL"
    else -> "OTHER_TRANSITION"
}
