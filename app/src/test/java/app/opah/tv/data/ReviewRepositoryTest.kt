package app.opah.tv.data

import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.model.FrigateUserProfile
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.ReviewSearchQuery
import app.opah.tv.data.model.ReviewSeverity
import app.opah.tv.data.network.FrigateGateway
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewRepositoryTest {
    @Test
    fun `search constrains request and response to the profile camera allow list`() = runBlocking {
        val gateway = RecordingGateway()
        val repository = ReviewRepository(gateway, FrigateJsonParsers())

        val result = repository.search(
            profile = PROFILE,
            allowedCameras = setOf("Front"),
            query = ReviewSearchQuery(
                cameras = setOf("Front", "Private"),
                severity = ReviewSeverity.ALERT,
                limit = 100,
            ),
        )

        assertEquals(setOf("Front"), gateway.reviewQuery?.cameras)
        assertEquals(listOf("front-item"), result.items.map(ReviewItem::id))
        assertEquals(0, gateway.recordingRequests.size)
    }

    @Test
    fun `recording availability uses only the selected review window`() = runBlocking {
        val gateway = RecordingGateway()
        val repository = ReviewRepository(gateway, FrigateJsonParsers())
        val item = ReviewItem(
            id = "front-item",
            camera = "Front",
            startTime = 100.0,
            endTime = 120.0,
            severity = ReviewSeverity.ALERT,
            objects = listOf("person"),
            zones = emptyList(),
            hasBeenReviewed = false,
        )

        assertTrue(repository.recordingAvailable(PROFILE, item).getOrThrow())
        assertEquals(listOf(RecordingRequest("Front", 92.0, 128.0)), gateway.recordingRequests)
    }

    private data class RecordingRequest(val camera: String, val after: Double, val before: Double)

    private class RecordingGateway : FrigateGateway {
        var reviewQuery: ReviewSearchQuery? = null
        val recordingRequests = mutableListOf<RecordingRequest>()

        override suspend fun login(profile: ConnectionProfile, password: String) = USER
        override suspend fun refreshSession(profile: ConnectionProfile) = USER
        override suspend fun logout(profile: ConnectionProfile) = Unit
        override suspend fun getVersion(profile: ConnectionProfile) = error("unused")
        override suspend fun getConfig(profile: ConnectionProfile) = error("unused")
        override suspend fun getStats(profile: ConnectionProfile) = error("unused")
        override suspend fun getRecordingsStorage(profile: ConnectionProfile) = error("unused")
        override suspend fun getGo2RtcStreams(profile: ConnectionProfile) = error("unused")
        override suspend fun getGo2RtcStream(profile: ConnectionProfile, streamName: String) = error("unused")

        override suspend fun getReview(profile: ConnectionProfile, query: ReviewSearchQuery): String {
            reviewQuery = query
            return REVIEWS
        }

        override suspend fun getRecordings(
            profile: ConnectionProfile,
            camera: String,
            after: Double,
            before: Double,
        ): String {
            recordingRequests += RecordingRequest(camera, after, before)
            return """[{"start_time":95.0,"end_time":110.0}]"""
        }

        override fun reviewPlaybackUrl(profile: ConnectionProfile, item: ReviewItem) = error("unused")
    }

    private companion object {
        val PROFILE = ConnectionProfile("https://frigate.example:8971", "viewer")
        val USER = FrigateUserProfile("viewer", "viewer", setOf("Front"))
        val REVIEWS = """
            [
              {"id":"front-item","camera":"Front","start_time":100,"end_time":120,"severity":"alert","data":{}},
              {"id":"private-item","camera":"Private","start_time":100,"end_time":120,"severity":"alert","data":{}}
            ]
        """.trimIndent()
    }
}
