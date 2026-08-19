package app.opah.tv.data

import app.opah.tv.data.model.ServerVersionCompatibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FrigateVersionPolicyTest {
    private val policy = FrigateVersionPolicy()

    @Test
    fun `validated Frigate version is supported without warning`() {
        val result = policy.evaluate("0.17.2")

        assertEquals(ServerVersionCompatibility.SUPPORTED, result.compatibility)
        assertEquals(0, result.major)
        assertEquals(17, result.minor)
        assertEquals(2, result.patch)
        assertNull(result.warning)
    }

    @Test
    fun `build suffix on validated version remains supported`() {
        assertEquals(
            ServerVersionCompatibility.SUPPORTED,
            policy.evaluate("0.17.2-abc123").compatibility,
        )
    }

    @Test
    fun `validated Frigate 0 18 beta build is supported without warning`() {
        val result = policy.evaluate("0.18.0-344efb6")

        assertEquals(ServerVersionCompatibility.SUPPORTED, result.compatibility)
        assertEquals(0, result.major)
        assertEquals(18, result.minor)
        assertEquals(0, result.patch)
        assertNull(result.warning)
    }

    @Test
    fun `different Frigate 0 18 beta build is compatible but unverified`() {
        val result = policy.evaluate("0.18.0-deadbee")

        assertEquals(ServerVersionCompatibility.COMPATIBLE_UNVERIFIED, result.compatibility)
        assertNotNull(result.warning)
    }

    @Test
    fun `future stable Frigate 0 18 build is not inferred from beta validation`() {
        val result = policy.evaluate("0.18.0")

        assertEquals(ServerVersionCompatibility.COMPATIBLE_UNVERIFIED, result.compatibility)
        assertNotNull(result.warning)
    }

    @Test
    fun `same API line patch is compatible but unverified`() {
        val result = policy.evaluate("v0.17.4")

        assertEquals(ServerVersionCompatibility.COMPATIBLE_UNVERIFIED, result.compatibility)
        assertNotNull(result.warning)
    }

    @Test
    fun `same 0 18 API line patch is compatible but unverified`() {
        val result = policy.evaluate("0.18.1")

        assertEquals(ServerVersionCompatibility.COMPATIBLE_UNVERIFIED, result.compatibility)
        assertNotNull(result.warning)
    }

    @Test
    fun `different API line is unsupported`() {
        assertEquals(
            ServerVersionCompatibility.UNSUPPORTED,
            policy.evaluate("0.19.0").compatibility,
        )
    }

    @Test
    fun `unparseable version is unknown rather than silently supported`() {
        assertEquals(
            ServerVersionCompatibility.UNKNOWN,
            policy.evaluate("development").compatibility,
        )
    }
}
