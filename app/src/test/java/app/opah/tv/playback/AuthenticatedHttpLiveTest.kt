package app.opah.tv.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AuthenticatedHttpLiveTest {
    @Test
    fun separateHttpsRequestsCannotReuseAnOldCameraSurface() {
        val first = PlaybackRequest("Camera", "", PlaybackKind.LIVE, cameraName = "cam1",
            httpsLiveRequestId = "request-one")
        val second = first.copy(cameraName = "cam2", httpsLiveRequestId = "request-two")
        assertFalse(playbackSurfaceKey(first) == playbackSurfaceKey(second))
        assertEquals(playbackSurfaceKey(first), playbackSurfaceKey(first.copy(detail = "Changed status")))
        assertFalse(playbackSurfaceKey(first) == playbackSurfaceKey(first.copy(httpsLiveRequestId = "retry")))
    }

    @Test
    fun selectedStreamMustStillExistAndIsNeverSilentlyReplaced() {
        assertEquals("cam_sub", selectHttpLiveStream("cam", listOf("cam", "cam_sub"), "cam_sub"))
        assertNull(selectHttpLiveStream("cam", listOf("cam"), "cam_sub"))
        assertNull(selectHttpLiveStream("cam", listOf("cam"), "rtsp://private/source"))
        assertEquals("cam", selectHttpLiveStream("cam", listOf("cam_sub", "cam"), null))
        assertNull(selectHttpLiveStream("cam", emptyList(), null))
    }

    @Test
    fun defaultPlaybackNeverOptsIntoHttps() {
        val live = PlaybackRequest("Camera", "", PlaybackKind.LIVE, cameraName = "cam1")
        assertFalse(useAuthenticatedLive(live, "https://example.test"))
        assertFalse(useAuthenticatedLive(live.copy(liveCompatibilityRequestId = "rtsp"), "https://example.test"))
        assertFalse(useAuthenticatedLive(live, "http://example.test"))
    }

    @Test
    fun explicitHttpsSessionCannotOverrideDiagnosticsRecordingsOrRtspSession() {
        val live = PlaybackRequest("Camera", "", PlaybackKind.LIVE, cameraName = "cam1",
            httpsLiveRequestId = "explicit-session")
        assertTrue(useAuthenticatedLive(live, "https://example.test"))
        assertFalse(useAuthenticatedLive(live.copy(liveCompatibilityRequestId = "rtsp"), "https://example.test"))
        assertFalse(useAuthenticatedLive(live.copy(compatibilityTest = true), "https://example.test"))
        assertFalse(useAuthenticatedLive(live.copy(kind = PlaybackKind.RECORDED), "https://example.test"))
        assertFalse(useAuthenticatedLive(live, "http://example.test"))
        assertFalse(useAuthenticatedLive(live, null))
        assertFalse(useAuthenticatedLive(live.copy(httpsLiveRequestId = null), "https://example.test"))
    }

    @Test
    fun optionIsOnlyOfferedForSupportedHttpsOrigins() {
        assertTrue(canOfferHttpsLive("https://example.test/frigate/"))
        for (base in listOf(null, "http://example.test", "https://u:p@example.test", "https://example.test/?x=1")) {
            assertFalse(canOfferHttpsLive(base))
        }
    }

    @Test
    fun streamingClientRetainsAuthenticationAndTlsWithoutRedirectsOrWholeCallDeadline() {
        val original = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val streaming = httpLiveClient(original)
        assertSame(original.cookieJar, streaming.cookieJar)
        assertSame(original.sslSocketFactory, streaming.sslSocketFactory)
        assertSame(original.hostnameVerifier, streaming.hostnameVerifier)
        assertEquals(0, streaming.callTimeoutMillis)
        assertEquals(20_000, streaming.readTimeoutMillis)
        assertEquals(15_000, streaming.pingIntervalMillis)
        assertEquals(30_000, original.callTimeoutMillis)
        assertFalse(streaming.followRedirects)
        assertFalse(streaming.followSslRedirects)
    }

    @Test
    fun usesConfiguredHttpsOriginAndBasePath() {
        assertEquals("https://example.test/frigate/live/mse/api/ws?src=cam1",
            httpLiveUri("https://example.test/frigate/", "cam1"))
        assertEquals("https://example.test/live/mse/api/ws?src=cam1",
            httpLiveUri("https://example.test", "cam1"))
    }

    @Test
    fun rejectsInsecureOriginsCredentialsAndSourceInjection() {
        for (base in listOf("http://example.test", "https://user:secret@example.test", "https://example.test/?x=1", "https://example.test/#fragment")) {
            assertNull(httpLiveUri(base, "cam1"))
        }
        for (stream in listOf("rtsp://example.test/cam", "ffmpeg:cam1", "cam1&src=other", "", "../other")) {
            assertNull(httpLiveUri("https://example.test", stream))
        }
    }
}
