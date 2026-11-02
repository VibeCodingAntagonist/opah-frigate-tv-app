package app.opah.tv.playback.compatibility

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackReleaseDiagnosticsTest {
    @Test
    fun timeoutTriggerIsCapturedWithoutAnAttemptFailedCallback() {
        val attempt = PlaybackAttemptId(PlaybackSessionId("test-session"), 1)
        for (phase in ProbePhase.entries) {
            assertEquals(
                "${phase.name}_DEADLINE",
                playbackReleaseTrigger(PlaybackEvent.ProbeDeadlineReached(attempt, phase, 100)),
            )
        }
    }

    @Test
    fun forcedCleanupCannotEraseOriginalFailure() {
        val diagnostics = PlaybackReleaseDiagnostics()
        diagnostics.begin("FIRST_FRAME_DEADLINE")
        diagnostics.completed(false, "BACKEND_NOT_RELEASED", 2001)
        diagnostics.completed(true, "RELEASED", 10)
        assertEquals("FIRST_FRAME_DEADLINE", diagnostics.trigger)
        assertEquals("BACKEND_NOT_RELEASED", diagnostics.initialCleanup)
        assertEquals(2001L, diagnostics.cleanupMillis)
    }

    @Test
    fun nextAttemptResetsPreviousCleanupEvidence() {
        val diagnostics = PlaybackReleaseDiagnostics()
        diagnostics.begin("FIRST_FRAME_DEADLINE")
        diagnostics.completed(false, "RELEASED", 20)
        diagnostics.begin("STABLE_DWELL_DEADLINE")
        assertEquals("STABLE_DWELL_DEADLINE", diagnostics.trigger)
        assertEquals("NOT_STARTED", diagnostics.initialCleanup)
        assertEquals(0L, diagnostics.cleanupMillis)
    }
}
