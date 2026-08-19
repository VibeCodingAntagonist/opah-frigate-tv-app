package app.opah.tv.data.network

import okhttp3.CookieJar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrigateApiClientTest {
    @Test
    fun `default API client never follows redirects with credentials or cookies`() {
        val client = FrigateApiClient.defaultClient(CookieJar.NO_COOKIES)

        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    @Test
    fun `HTTP timeout rate limit and server failures are retryable`() {
        assertEquals(OpahErrorCode.TIMEOUT, apiFailureForStatus(408).code)
        assertTrue(apiFailureForStatus(408).retryable)
        assertEquals(OpahErrorCode.RATE_LIMITED, apiFailureForStatus(429).code)
        assertTrue(apiFailureForStatus(429).retryable)
        assertEquals(OpahErrorCode.SERVER_ERROR, apiFailureForStatus(503).code)
        assertTrue(apiFailureForStatus(503).retryable)
    }

    @Test
    fun `redirect and missing endpoint failures do not loop`() {
        assertEquals(OpahErrorCode.REDIRECT_REJECTED, apiFailureForStatus(307).code)
        assertFalse(apiFailureForStatus(307).retryable)
        assertEquals(OpahErrorCode.NOT_FOUND, apiFailureForStatus(404).code)
        assertFalse(apiFailureForStatus(404).retryable)
    }
}
