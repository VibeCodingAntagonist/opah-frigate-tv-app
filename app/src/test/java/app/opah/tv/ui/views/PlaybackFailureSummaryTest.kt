package app.opah.tv.ui.views

import app.opah.tv.playback.compatibility.PlaybackResult
import app.opah.tv.playback.compatibility.PlaybackTerminalCause
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFailureSummaryTest {
    @Test
    fun releaseFailureIncludesAttemptCount() {
        val summary = playbackFailureSummary(
            PlaybackResult.Failed(PlaybackTerminalCause.AttemptReleaseFailed, 1),
        )
        assertTrue(summary.contains("ATTEMPT_RELEASE_FAILED"))
        assertTrue(summary.contains("Attempts: 1"))
    }
}
