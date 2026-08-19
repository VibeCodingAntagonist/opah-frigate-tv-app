package app.opah.tv.ui

import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.common.PlaybackException
import app.opah.tv.playback.PlaybackKind
import org.junit.Assert.assertEquals
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackErrorPolicyTest {
    @Test
    fun `live startup without a first frame uses an available fallback`() {
        assertTrue(
            shouldUseStartupFallback(
                kind = PlaybackKind.LIVE,
                fallbackUri = "rtsp://example.invalid/sub",
                firstFrameMs = null,
                safeError = null,
            ),
        )
    }

    @Test
    fun `startup fallback is not used after a frame or error`() {
        assertFalse(shouldUseStartupFallback(PlaybackKind.LIVE, "rtsp://example.invalid/sub", 500L, null))
        assertFalse(shouldUseStartupFallback(PlaybackKind.LIVE, "rtsp://example.invalid/sub", null, "failed"))
        assertFalse(shouldUseStartupFallback(PlaybackKind.RECORDED, "rtsp://example.invalid/sub", null, null))
        assertFalse(shouldUseStartupFallback(PlaybackKind.LIVE, null, null, null))
    }

    @Test
    fun `live playback prefers RTP over TCP immediately`() {
        assertTrue(initialForceTcp(PlaybackKind.LIVE))
    }

    @Test
    fun `recorded playback does not use RTSP transport policy`() {
        assertFalse(initialForceTcp(PlaybackKind.RECORDED))
    }

    @Test
    fun `detects direct UDP unsupported transport failure`() {
        val failure = RtspMediaSource.RtspUdpUnsupportedTransportException("UDP rejected")

        assertTrue(failure.hasRtspUdpUnsupportedTransportCause())
    }

    @Test
    fun `detects nested UDP unsupported transport failure`() {
        val failure = IOException(
            "outer",
            RtspMediaSource.RtspUdpUnsupportedTransportException("UDP rejected"),
        )

        assertTrue(failure.hasRtspUdpUnsupportedTransportCause())
    }

    @Test
    fun `does not retry unrelated failures as TCP`() {
        assertFalse(IOException("unreachable").hasRtspUdpUnsupportedTransportCause())
    }

    @Test
    fun `transient live interruption retries twice with bounded backoff`() {
        assertEquals(
            PlaybackRetryDecision(attempt = 1, delayMs = 1_000L),
            playbackRetryDecision(
                PlaybackKind.LIVE,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                attemptsUsed = 0,
            ),
        )
        assertEquals(
            PlaybackRetryDecision(attempt = 2, delayMs = 3_000L),
            playbackRetryDecision(
                PlaybackKind.LIVE,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                attemptsUsed = 1,
            ),
        )
        assertNull(
            playbackRetryDecision(
                PlaybackKind.LIVE,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                attemptsUsed = MAX_LIVE_AUTOMATIC_RETRIES,
            ),
        )
    }

    @Test
    fun `codec and malformed SDP failures never enter an automatic retry loop`() {
        assertNull(
            playbackRetryDecision(
                PlaybackKind.LIVE,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                attemptsUsed = 0,
            ),
        )
        assertNull(
            playbackRetryDecision(
                PlaybackKind.LIVE,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                attemptsUsed = 0,
            ),
        )
    }

    @Test
    fun `recorded playback is not silently restarted after transport failure`() {
        assertNull(
            playbackRetryDecision(
                PlaybackKind.RECORDED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                attemptsUsed = 0,
            ),
        )
    }

    @Test
    fun `live watchdog detects missing and frozen frames without treating pause as failure`() {
        assertFalse(
            liveStreamStalled(PlaybackKind.LIVE, true, 1_000L, 0L, 12_999L),
        )
        assertTrue(
            liveStreamStalled(PlaybackKind.LIVE, true, 1_000L, 0L, 13_000L),
        )
        assertFalse(
            liveStreamStalled(PlaybackKind.LIVE, true, 1_000L, 10_000L, 21_999L),
        )
        assertTrue(
            liveStreamStalled(PlaybackKind.LIVE, true, 1_000L, 10_000L, 22_000L),
        )
        assertFalse(
            liveStreamStalled(PlaybackKind.LIVE, false, 1_000L, 10_000L, 30_000L),
        )
        assertFalse(
            liveStreamStalled(PlaybackKind.RECORDED, true, 1_000L, 10_000L, 30_000L),
        )
    }

    @Test
    fun `playback errors give specific safe recovery guidance`() {
        val malformed = safePlaybackGuidance(
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        )
        val decoder = safePlaybackGuidance(
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        )
        val audio = safePlaybackGuidance(
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        )

        assertTrue(malformed.contains("malformed stream information"))
        assertTrue(decoder.contains("could not decode"))
        assertTrue(audio.contains("Video only"))
    }
}
