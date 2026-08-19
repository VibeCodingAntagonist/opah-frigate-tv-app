package app.opah.tv.domain

import app.opah.tv.data.model.ConnectionProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamUriFactoryTest {
    @Test
    fun `derives rtsp host from the authenticated api profile`() {
        val uri = StreamUriFactory.rtsp(
            ConnectionProfile(
                apiBaseUrl = "https://nvr.example.test:8971",
                username = "viewer",
            ),
            streamName = "Camera_Beta",
        )

        assertEquals("rtsp://nvr.example.test:8554/Camera_Beta", uri)
    }

    @Test
    fun `uses a separate rtsp override and port`() {
        val uri = StreamUriFactory.rtsp(
            ConnectionProfile(
                apiBaseUrl = "https://nvr.example.test:8971/proxy",
                username = "viewer",
                rtspHostOverride = "192.0.2.40",
                rtspPort = 9554,
            ),
            streamName = "Camera_Alpha",
        )

        assertEquals("rtsp://192.0.2.40:9554/Camera_Alpha", uri)
    }

    @Test
    fun `encodes unsafe characters in a configured stream name`() {
        val uri = StreamUriFactory.rtsp(
            ConnectionProfile("https://nvr.example.test", "viewer"),
            streamName = "Main view #1",
        )

        assertEquals("rtsp://nvr.example.test:8554/Main%20view%20%231", uri)
    }

    @Test
    fun `renders an ipv6 rtsp override correctly`() {
        val uri = StreamUriFactory.rtsp(
            ConnectionProfile(
                apiBaseUrl = "https://nvr.example.test",
                username = "viewer",
                rtspHostOverride = "2001:db8::42",
            ),
            streamName = "camera",
        )

        assertEquals("rtsp://[2001:db8::42]:8554/camera", uri)
    }
}
