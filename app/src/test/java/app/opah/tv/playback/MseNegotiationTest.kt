package app.opah.tv.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MseNegotiationTest {
    private fun message(codec: String) = """{"type":"mse","value":"video/mp4; codecs=\"$codec\""}"""

    @Test fun hevcRequiresDecoderCapabilityAndAdvertisedProfile() {
        assertFalse(mseCodecs(false).contains("hvc1"))
        assertTrue(mseCodecs(true).contains(MSE_HEVC_CODEC))
        assertFalse(acceptsMseNegotiation(message(MSE_HEVC_CODEC)))
        assertTrue(acceptsMseNegotiation(message(MSE_HEVC_CODEC), true))
        assertTrue(acceptsMseNegotiation(message("hvc1.1.6.L150.90,mp4a.40.2"), true))
        for (codec in listOf("hvc1.2.4.L153.B0", "hvc1.1.6.H153.B0", "hvc1.1.6.L180.B0",
            "hvc1.1.6.L99.B0", "hvc1.1.6.L153.B0,opus", "hvc1.1.6.L153.B0,avc1.640029")) {
            assertFalse(codec, acceptsMseNegotiation(message(codec), true))
        }
    }
    @Test fun acceptsVideoAndOptionalSupportedAudio() {
        assertTrue(acceptsMseNegotiation("""{"type":"mse","value":"video/mp4; codecs=\"avc1.640029,mp4a.40.2\""}"""))
        assertTrue(acceptsMseNegotiation("""{"type":"mse","value":"video/mp4; codecs=\"avc1.640029\""}"""))
    }

    @Test fun rejectsMalformedErrorsAndUnsupportedMedia() {
        for (message in listOf(
            "not json", "[]", "{}", "x".repeat(16_385),
            """{"type":"error","value":"private source URL"}""",
            """{"type":"mse","value":"video/mp4; codecs=\"hvc1.1.6.L153.B0\""}""",
            """{"type":"mse","value":"video/mp4; codecs=\"mp4a.40.2\""}""",
            """{"type":"mse","value":"text/html"}""",
        )) assertFalse(acceptsMseNegotiation(message))
    }
}
