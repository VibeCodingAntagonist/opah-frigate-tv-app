package app.opah.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionProfileFactoryTest {
    @Test
    fun `defaults to https and normalizes an api suffix`() {
        val profile = ConnectionProfileFactory.create(
            rawApiBaseUrl = "  frigate.example.test:8971/api/  ",
            username = " viewer ",
        ).getOrThrow()

        assertEquals("https://frigate.example.test:8971", profile.apiBaseUrl)
        assertEquals("viewer", profile.username)
        assertNull(profile.rtspHostOverride)
        assertEquals(8554, profile.rtspPort)
    }

    @Test
    fun `preserves an explicit path and removes query and fragment`() {
        val profile = ConnectionProfileFactory.create(
            rawApiBaseUrl = "https://nvr.example.test/frigate/?debug=true#section",
            username = "viewer",
        ).getOrThrow()

        assertEquals("https://nvr.example.test/frigate", profile.apiBaseUrl)
    }

    @Test
    fun `normalizes a bracketed ipv6 rtsp override`() {
        val profile = ConnectionProfileFactory.create(
            rawApiBaseUrl = "https://nvr.example.test",
            username = "viewer",
            rtspHostOverride = " [2001:db8::42] ",
            rtspPort = 9554,
        ).getOrThrow()

        assertEquals("2001:db8::42", profile.rtspHostOverride)
        assertEquals(9554, profile.rtspPort)
    }

    @Test
    fun `rejects embedded credentials`() {
        val result = ConnectionProfileFactory.create(
            rawApiBaseUrl = "https://viewer:secret@nvr.example.test:8971",
            username = "viewer",
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("embed credentials"))
    }

    @Test
    fun `rejects non-http schemes and invalid ports`() {
        assertTrue(
            ConnectionProfileFactory.create("ftp://nvr.example.test", "viewer").isFailure,
        )
        assertFalse(
            ConnectionProfileFactory.create(
                "https://nvr.example.test",
                "viewer",
                rtspPort = 0,
            ).isSuccess,
        )
    }
}
