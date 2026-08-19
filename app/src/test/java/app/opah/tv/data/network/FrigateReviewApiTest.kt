package app.opah.tv.data.network

import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.model.ReviewSearchQuery
import app.opah.tv.data.model.ReviewSeverity
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrigateReviewApiTest {
    @Test
    fun `review request serializes the verified Frigate filters`() = runBlocking {
        var capturedUrl: HttpUrl? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                capturedUrl = chain.request().url
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("[]".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val api = FrigateApiClient(client, EmptySessionStore)

        api.getReview(
            PROFILE,
            ReviewSearchQuery(
                cameras = setOf("Back", "Front"),
                severity = ReviewSeverity.DETECTION,
                label = "person",
                zone = "driveway",
                reviewed = false,
                after = 100.5,
                before = 200.5,
                limit = 500,
            ),
        )

        val url = requireNotNull(capturedUrl)
        assertEquals("/api/review", url.encodedPath)
        assertEquals("Back,Front", url.queryParameter("cameras"))
        assertEquals("detection", url.queryParameter("severity"))
        assertEquals("person", url.queryParameter("labels"))
        assertEquals("driveway", url.queryParameter("zones"))
        assertEquals("0", url.queryParameter("reviewed"))
        assertEquals("100.5", url.queryParameter("after"))
        assertEquals("200.5", url.queryParameter("before"))
        assertEquals("200", url.queryParameter("limit"))
    }

    @Test
    fun `unknown severity omits the server severity filter`() = runBlocking {
        var capturedUrl: HttpUrl? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                capturedUrl = chain.request().url
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("[]".toResponseBody())
                    .build()
            }
            .build()

        FrigateApiClient(client, EmptySessionStore).getReview(
            PROFILE,
            ReviewSearchQuery(cameras = setOf("Front"), severity = ReviewSeverity.UNKNOWN),
        )

        assertNull(requireNotNull(capturedUrl).queryParameter("severity"))
    }

    @Test
    fun `storage requests use the verified Frigate API routes`() = runBlocking {
        val capturedPaths = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                capturedPaths += chain.request().url.encodedPath
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val api = FrigateApiClient(client, EmptySessionStore)

        api.getStats(PROFILE)
        api.getRecordingsStorage(PROFILE)

        assertEquals(listOf("/api/stats", "/api/recordings/storage"), capturedPaths)
        assertTrue(capturedPaths.none { it.contains("//api") })
    }

    private object EmptySessionStore : SessionCookieStore {
        override fun clear() = Unit
        override fun hasUnexpiredSession() = false
    }

    private companion object {
        val PROFILE = ConnectionProfile("https://frigate.example:8971", "viewer")
    }
}
