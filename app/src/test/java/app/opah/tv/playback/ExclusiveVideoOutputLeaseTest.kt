@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package app.opah.tv.playback

import androidx.media3.common.Player
import app.opah.tv.playback.compatibility.PlaybackAttemptId
import app.opah.tv.playback.compatibility.PlaybackSessionId
import app.opah.tv.playback.media3.*
import org.junit.Assert.*
import org.junit.Test

class ExclusiveVideoOutputLeaseTest {
    private fun attempt(name: String) = PlaybackAttemptId(PlaybackSessionId(name), 1)
    private fun target() = object : Media3VideoOutputTarget {
        override fun attach(player: Player, outputGeneration: Long) = Unit
        override fun detachAndHide(player: Player, outputGeneration: Long) = true
    }

    @Test fun pendingRtspRegistrationCannotBeStolen() {
        val registry = BoundedMedia3VideoOutputRegistry()
        val rtsp = registry.register(target()) as Media3VideoOutputRegistrationResult.Registered
        assertNull(registry.acquireExclusive(target(), attempt("https")))
        val claimed = registry.claim(attempt("rtsp")) as Media3VideoOutputClaimResult.Claimed
        assertNull(registry.acquireExclusive(target(), attempt("https")))
        assertTrue(registry.release(claimed.attachment))
        // Cleanup still owns its registration even after releasing the decoder attachment.
        assertNull(registry.acquireExclusive(target(), attempt("https")))
        assertTrue(registry.unregister(rtsp.registration))
        assertNotNull(registry.acquireExclusive(target(), attempt("https")))
    }

    @Test fun httpsKeepsRtspOutUntilTeardownCompletes() {
        val registry = BoundedMedia3VideoOutputRegistry()
        val lease = requireNotNull(registry.acquireExclusive(target(), attempt("https")))
        assertEquals(Media3VideoOutputRegistrationResult.RejectedClaimed, registry.register(target()))
        assertTrue(lease.finish {
            assertEquals(Media3VideoOutputRegistrationResult.RejectedClaimed, registry.register(target()))
            assertNull(registry.acquireExclusive(target(), attempt("other")))
            true
        })
        assertTrue(registry.register(target()) is Media3VideoOutputRegistrationResult.Registered)
    }

    @Test fun failedOrThrowingTeardownFailsClosed() {
        for (throws in listOf(false, true)) {
            val registry = BoundedMedia3VideoOutputRegistry()
            val lease = requireNotNull(registry.acquireExclusive(target(), attempt("https")))
            assertFalse(lease.finish { if (throws) error("release failed") else false })
            assertFalse(lease.finish { throw AssertionError("Never repeat teardown") })
            assertNull(registry.acquireExclusive(target(), attempt("new")))
            assertEquals(Media3VideoOutputRegistrationResult.RejectedClaimed, registry.register(target()))
        }
    }

    @Test fun staleCloseCannotReleaseReplacement() {
        val registry = BoundedMedia3VideoOutputRegistry()
        val old = requireNotNull(registry.acquireExclusive(target(), attempt("first")))
        assertTrue(old.finish { true })
        val next = requireNotNull(registry.acquireExclusive(target(), attempt("second")))
        assertTrue(old.finish { throw AssertionError("Stale callback must not run") })
        assertNull(registry.acquireExclusive(target(), attempt("third")))
        assertTrue(next.finish { true })
    }

    @Test fun repeatedForegroundBackgroundCyclesLeaveNoOwner() {
        val registry = BoundedMedia3VideoOutputRegistry()
        repeat(100) { cycle ->
            val lease = requireNotNull(registry.acquireExclusive(target(), attempt("cycle-$cycle")))
            assertTrue(lease.finish { true })
            assertNull(registry.snapshotForTest().claimedAttempt)
            assertNull(registry.snapshotForTest().registrationGeneration)
        }
    }
}
