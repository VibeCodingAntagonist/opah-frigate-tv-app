@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, androidx.media3.common.util.UnstableApi::class)
package app.opah.tv.playback

import androidx.media3.common.Player
import app.opah.tv.playback.compatibility.PlaybackAttemptId
import app.opah.tv.playback.compatibility.PlaybackSessionId
import app.opah.tv.playback.media3.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AwaitExclusiveVideoOutputTest {
    private fun attempt(name: String) = PlaybackAttemptId(PlaybackSessionId(name), 1)
    private fun target() = object : Media3VideoOutputTarget {
        override fun attach(player: Player, outputGeneration: Long) = Unit
        override fun detachAndHide(player: Player, outputGeneration: Long) = true
    }

    @Test fun waitsForExactPreviousOwnerBeforeStarting() = runTest {
        val registry = BoundedMedia3VideoOutputRegistry()
        val old = requireNotNull(registry.acquireExclusive(target(), attempt("old")))
        val waiting = async {
            awaitExclusiveVideoOutput(registry, target(), attempt("new"), { true }, elapsedMillis = { testScheduler.currentTime })
        }
        runCurrent()
        advanceTimeBy(100)
        assertFalse(waiting.isCompleted)
        assertTrue(old.finish { true })
        advanceTimeBy(50)
        runCurrent()
        val new = requireNotNull(waiting.await())
        assertEquals(attempt("new"), registry.snapshotForTest().claimedAttempt)
        assertTrue(new.finish { true })
    }

    @Test fun backgroundCancellationDoesNotStartWhenOldPlayerFinallyCloses() = runTest {
        val registry = BoundedMedia3VideoOutputRegistry()
        val old = requireNotNull(registry.acquireExclusive(target(), attempt("old")))
        val waiting = async {
            awaitExclusiveVideoOutput(registry, target(), attempt("cancelled"), { true }, elapsedMillis = { testScheduler.currentTime })
        }
        runCurrent()
        waiting.cancel()
        waiting.join()
        assertTrue(old.finish { true })
        advanceTimeBy(5_000)
        runCurrent()
        assertNull(registry.snapshotForTest().claimedAttempt)
        assertNull(registry.snapshotForTest().registrationGeneration)
    }

    @Test fun busySlotTimesOutWithoutReleasingItsOwner() = runTest {
        val registry = BoundedMedia3VideoOutputRegistry()
        registry.acquireExclusive(target(), attempt("old"))
        val result = awaitExclusiveVideoOutput(registry, target(), attempt("new"), { true }, elapsedMillis = { testScheduler.currentTime })
        assertNull(result)
        assertEquals(3_000L, testScheduler.currentTime)
        assertEquals(attempt("old"), registry.snapshotForTest().claimedAttempt)
    }

    @Test fun accessLostImmediatelyAfterAcquisitionReleasesUnusedSlot() = runTest {
        val registry = BoundedMedia3VideoOutputRegistry()
        var checks = 0
        assertNull(awaitExclusiveVideoOutput(registry, target(), attempt("new"), { ++checks == 1 }))
        assertNull(registry.snapshotForTest().claimedAttempt)
        assertNull(registry.snapshotForTest().registrationGeneration)
    }
}
