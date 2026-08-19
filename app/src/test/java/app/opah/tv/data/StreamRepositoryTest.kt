package app.opah.tv.data

import app.opah.tv.data.model.BirdseyeStatus
import app.opah.tv.data.model.Camera
import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.model.LiveStreamOption
import app.opah.tv.data.network.FrigateGateway
import app.opah.tv.data.model.FrigateUserProfile
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.ReviewSearchQuery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRepositoryTest {
    @Test
    fun `full access trusts complete bulk metadata without per-stream probes`() = runBlocking {
        val gateway = RecordingGateway(
            bulkResponse = """
                {
                  "front_main":{"producers":[{"medias":["video/H264 1920x1080","audio/AAC"]}]},
                  "back_main":{"producers":[{"medias":["video/HEVC 3840x2160"]}]}
                }
            """.trimIndent(),
        )

        val result = repository(gateway).discover(PROFILE, fullAccessCatalog())

        assertTrue(gateway.bulkStreamsRequested)
        assertEquals(emptyList<String>(), gateway.probedStreamNames)
        assertEquals(setOf("front_main", "back_main"), result.metadata.keys)
    }

    @Test
    fun `full access probes only missing or usable unknown-codec streams`() = runBlocking {
        val gateway = RecordingGateway(
            bulkResponse = """
                {
                  "front_main":{"producers":[{"medias":["video/UNKNOWN 1920x1080"]}]}
                }
            """.trimIndent(),
        )

        repository(gateway).discover(PROFILE, fullAccessCatalog())

        assertEquals(listOf("back_main", "front_main"), gateway.probedStreamNames.sorted())
    }

    @Test
    fun `restricted access never fetches unfiltered bulk metadata`() = runBlocking {
        val gateway = RecordingGateway("{}")
        val catalog = fullAccessCatalog().copy(fullCameraAccess = false)

        val result = repository(gateway).discover(PROFILE, catalog)

        assertFalse(gateway.bulkStreamsRequested)
        assertEquals(listOf("back_main", "front_main"), gateway.probedStreamNames.sorted())
        assertEquals(setOf("front_main", "back_main"), result.metadata.keys)
    }

    private fun repository(gateway: FrigateGateway) =
        StreamRepository(gateway, FrigateJsonParsers())

    private fun fullAccessCatalog() = CameraCatalog(
        cameras = listOf(
            Camera("front", "Front", 0, listOf(LiveStreamOption("Main", "front_main"))),
            Camera("back", "Back", 1, listOf(LiveStreamOption("Main", "back_main"))),
        ),
        configuredCameraNames = setOf("front", "back"),
        fullCameraAccess = true,
        birdseye = BirdseyeStatus(false, false, false, null),
    )

    private class RecordingGateway(private val bulkResponse: String) : FrigateGateway {
        var bulkStreamsRequested = false
        val probedStreamNames = mutableListOf<String>()

        override suspend fun login(profile: ConnectionProfile, password: String) = USER
        override suspend fun refreshSession(profile: ConnectionProfile) = USER
        override suspend fun logout(profile: ConnectionProfile) = Unit
        override suspend fun getVersion(profile: ConnectionProfile) = "0.17.2"
        override suspend fun getConfig(profile: ConnectionProfile) = "{}"
        override suspend fun getStats(profile: ConnectionProfile) = "{}"
        override suspend fun getRecordingsStorage(profile: ConnectionProfile) = "{}"
        override suspend fun getGo2RtcStreams(profile: ConnectionProfile): String {
            bulkStreamsRequested = true
            return bulkResponse
        }

        override suspend fun getGo2RtcStream(profile: ConnectionProfile, streamName: String): String {
            probedStreamNames += streamName
            return """{"producers":[{"medias":["video/H264 1280x720"]}]}"""
        }

        override suspend fun getReview(profile: ConnectionProfile, query: ReviewSearchQuery) = "[]"
        override suspend fun getRecordings(
            profile: ConnectionProfile,
            camera: String,
            after: Double,
            before: Double,
        ) = "[]"

        override fun reviewPlaybackUrl(profile: ConnectionProfile, item: ReviewItem) = ""
    }

    private companion object {
        val PROFILE = ConnectionProfile("https://example.invalid", "user")
        val USER = FrigateUserProfile("user", "admin", setOf("front", "back"))
    }
}
