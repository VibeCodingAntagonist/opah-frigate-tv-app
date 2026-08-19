package app.opah.tv.ui

import app.opah.tv.playback.PlaybackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootUiPolicyTest {
    @Test
    fun `blocking work shows loading instead of setup form`() {
        assertEquals(RootSurface.LOADING, rootSurface(false, false, true))
        assertEquals(RootSurface.SETUP, rootSurface(false, false, false))
    }

    @Test
    fun `offline saved session shows recovery instead of sign in form`() {
        assertEquals(
            RootSurface.RECOVERY,
            rootSurface(
                hasPlayback = false,
                hasConnectedContent = false,
                loading = false,
                savedSessionRecoveryAvailable = true,
            ),
        )
        assertEquals(
            RootSurface.LOADING,
            rootSurface(
                hasPlayback = false,
                hasConnectedContent = false,
                loading = true,
                savedSessionRecoveryAvailable = true,
            ),
        )
    }

    @Test
    fun `connected and playback surfaces outrank blocking loading`() {
        assertEquals(RootSurface.CONNECTED, rootSurface(false, true, true))
        assertEquals(RootSurface.PLAYBACK, rootSurface(true, true, true))
    }

    @Test
    fun `live controls navigate cameras but do not pretend live video is seekable`() {
        val controls = playbackControlAvailability(PlaybackKind.LIVE, hasPrevious = true, hasNext = true)
        assertTrue(controls.previous)
        assertFalse(controls.seek)
        assertTrue(controls.next)
    }

    @Test
    fun `recorded controls seek without switching into unrelated live cameras`() {
        val controls = playbackControlAvailability(PlaybackKind.RECORDED, hasPrevious = true, hasNext = true)
        assertFalse(controls.previous)
        assertTrue(controls.seek)
        assertFalse(controls.next)
    }

    @Test
    fun `timeline seeking stays inside the recording window`() {
        assertEquals(0L, boundedSeekPosition(3_000L, 60_000L, -10_000L))
        assertEquals(13_000L, boundedSeekPosition(3_000L, 60_000L, 10_000L))
        assertEquals(60_000L, boundedSeekPosition(58_000L, 60_000L, 10_000L))
        assertEquals(0L, boundedSeekPosition(3_000L, 0L, 10_000L))
    }

    @Test
    fun `timeline fraction clamps malformed player positions`() {
        assertEquals(0f, timelineFraction(-1L, 10_000L), 0.0001f)
        assertEquals(0.5f, timelineFraction(5_000L, 10_000L), 0.0001f)
        assertEquals(1f, timelineFraction(12_000L, 10_000L), 0.0001f)
        assertEquals(0f, timelineFraction(5_000L, 0L), 0.0001f)
    }

    @Test
    fun `playback time uses familiar minute and hour notation`() {
        assertEquals("0:00", formatPlaybackTime(-1L))
        assertEquals("1:05", formatPlaybackTime(65_999L))
        assertEquals("1:01:01", formatPlaybackTime(3_661_500L))
    }

    @Test
    fun `ended recording offers replay even when Media3 still wants to play`() {
        assertEquals("Replay", recordedPlaybackButtonLabel(playWhenReady = true, ended = true))
        assertEquals("Pause", recordedPlaybackButtonLabel(playWhenReady = true, ended = false))
        assertEquals("Play", recordedPlaybackButtonLabel(playWhenReady = false, ended = false))
    }

    @Test
    fun `first Back reveals hidden playback controls before leaving playback`() {
        assertTrue(shouldRevealPlaybackControlsOnBack(controlsVisible = false, pictureInPictureActive = false))
        assertFalse(shouldRevealPlaybackControlsOnBack(controlsVisible = true, pictureInPictureActive = false))
        assertFalse(shouldRevealPlaybackControlsOnBack(controlsVisible = false, pictureInPictureActive = true))
    }

    @Test
    fun `rail places Birdseye below Cameras and keeps Diagnostics nested`() {
        assertEquals(
            listOf("Home", "Cameras", "Birdseye", "Review", "Information", "Settings"),
            AppDestination.entries.map { it.label },
        )
    }
}
