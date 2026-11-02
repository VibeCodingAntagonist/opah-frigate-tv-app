package app.opah.tv.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthorizedLiveSessionTest {
    @Test fun deniedAccessDoesNotStartAndReleasesSlot() = runTest {
        var started = 0; var closed = 0
        val result = runAuthorizedLiveSession({ false }, { started++ }, { false }, { null }, { closed++ })
        assertEquals(0, started); assertEquals(1, closed)
        assertEquals("Access changed; playback stopped", result)
    }
    @Test fun backgroundCancellationClosesAndResumeCreatesNewSession() = runTest {
        var starts = 0; var closes = 0
        repeat(5) {
            val job = launch { runAuthorizedLiveSession({ true }, { starts++ }, { true }, { null }, { closes++ }) }
            runCurrent(); job.cancel(); job.join()
            assertEquals(it + 1, starts); assertEquals(starts, closes)
        }
    }
    @Test fun firstFrameTimeoutReleasesTransport() = runTest {
        var closed = 0; var result: String? = null
        val job = launch { result = runAuthorizedLiveSession({ true }, {}, { false }, { null }, { closed++ }, { testScheduler.currentTime }) }
        runCurrent(); advanceTimeBy(30_000); runCurrent(); job.join()
        assertEquals("HTTPS first-frame timeout", result); assertEquals(1, closed)
    }
    @Test fun accessRevokedDuringPlaybackStopsAndReleases() = runTest {
        var allowed = true; var closed = 0; var result: String? = null
        val job = launch { result = runAuthorizedLiveSession({ allowed }, {}, { true }, { null }, { closed++ }) }
        runCurrent(); allowed = false; advanceTimeBy(250); runCurrent(); job.join()
        assertEquals("Access changed; playback stopped", result); assertEquals(1, closed)
    }
    @Test fun startupExceptionStillReleases() = runTest {
        var closed = 0
        try {
            runAuthorizedLiveSession({ true }, { throw IllegalStateException() }, { false }, { null }, { closed++ })
            fail("expected exception")
        } catch (_: IllegalStateException) { }
        assertEquals(1, closed)
    }
}
