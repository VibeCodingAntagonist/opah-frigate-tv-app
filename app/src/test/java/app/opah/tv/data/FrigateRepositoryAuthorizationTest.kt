package app.opah.tv.data

import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.model.FrigateUserProfile
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.ReviewSearchQuery
import app.opah.tv.data.network.FrigateGateway
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrigateRepositoryAuthorizationTest {
    @Test
    fun `essential discovery returns authorized cameras before enrichment requests begin`() = runBlocking {
        val gateway = RecordingGateway()
        val repository = FrigateRepository(gateway, FrigateJsonParsers())

        val bootstrap = repository.discoverEssential(PROFILE, LIMITED_USER)

        assertTrue(gateway.configRequested)
        assertFalse(gateway.versionRequested)
        assertFalse(gateway.reviewRequested)
        assertFalse(gateway.bulkStreamsRequested)
        assertEquals(emptyList<String>(), gateway.probedStreamNames)
        assertEquals(listOf("Allowed"), bootstrap.snapshot.cameras.map { it.name })
        assertEquals(setOf("Allowed"), bootstrap.snapshot.authorizedCameraNames.keys)
        assertTrue(bootstrap.snapshot.streamMetadata.isEmpty())
        assertTrue(bootstrap.snapshot.recentReviewItems.isEmpty())
    }

    @Test
    fun `limited role never fetches bulk streams and retains only permitted data`() = runBlocking {
        val gateway = RecordingGateway()
        val repository = FrigateRepository(gateway, FrigateJsonParsers())

        val snapshot = repository.refresh(PROFILE, LIMITED_USER)

        assertFalse(gateway.bulkStreamsRequested)
        assertEquals(listOf("Allowed_main"), gateway.probedStreamNames)
        assertEquals(listOf("Allowed"), snapshot.cameras.map { it.name })
        assertEquals(setOf("Allowed_main"), snapshot.streamMetadata.keys)
        assertEquals(listOf("Allowed"), snapshot.recentReviewItems.map { it.camera })
        assertEquals(listOf("Allowed"), gateway.recordingCameras)
        assertEquals(50, gateway.reviewQuery?.limit)
        assertEquals(setOf("Allowed"), gateway.reviewQuery?.cameras)
        assertEquals(true, snapshot.recentReviewItems.single().recordingAvailable)
    }

    @Test
    fun `storage summary drops disallowed camera rows before returning to the UI`() = runBlocking {
        val gateway = RecordingGateway()
        val repository = FrigateRepository(gateway, FrigateJsonParsers())
        val snapshot = repository.refresh(PROFILE, LIMITED_USER)

        val summary = repository.loadRecordingStorage(PROFILE, snapshot)

        assertEquals(listOf("Allowed"), summary.cameras.map { it.cameraName })
        assertEquals(100.0, summary.cameras.single().usageMiB, 0.001)
        assertEquals(1_000.0, summary.allCameraUsageMiB, 0.001)
        assertEquals(500.0, summary.otherUsageMiB, 0.001)
        assertTrue(summary.cameras.none { it.cameraName == "Restricted" })
    }

    @Test
    fun `information summary filters restricted performance camera rows`() = runBlocking {
        val gateway = RecordingGateway()
        val repository = FrigateRepository(gateway, FrigateJsonParsers())
        val snapshot = repository.refresh(PROFILE, LIMITED_USER)

        val summary = repository.loadInformation(PROFILE, snapshot)

        assertEquals(listOf("Allowed"), summary.performance.cameras.map { it.cameraName })
        assertTrue(summary.performance.cameras.none { it.cameraName == "Restricted" })
        assertEquals(listOf("Allowed"), summary.storage.cameras.map { it.cameraName })
    }

    private class RecordingGateway : FrigateGateway {
        var bulkStreamsRequested = false
        var configRequested = false
        var versionRequested = false
        var reviewRequested = false
        val probedStreamNames = mutableListOf<String>()
        val recordingCameras = mutableListOf<String>()
        var reviewQuery: ReviewSearchQuery? = null

        override suspend fun login(profile: ConnectionProfile, password: String) = LIMITED_USER
        override suspend fun refreshSession(profile: ConnectionProfile) = LIMITED_USER
        override suspend fun logout(profile: ConnectionProfile) = Unit
        override suspend fun getVersion(profile: ConnectionProfile): String {
            versionRequested = true
            return "0.17.2"
        }
        override suspend fun getConfig(profile: ConnectionProfile): String {
            configRequested = true
            return CONFIG
        }
        override suspend fun getStats(profile: ConnectionProfile) =
            """{"cameras":{"Allowed":{"camera_fps":5},"Restricted":{"camera_fps":30}},"service":{"version":"0.17.2","storage":{"/media/frigate/recordings":{"total":2000,"used":1500,"free":500}}}}"""
        override suspend fun getRecordingsStorage(profile: ConnectionProfile) =
            """{"Allowed":{"usage":100,"bandwidth":50},"Restricted":{"usage":900,"bandwidth":500}}"""

        override suspend fun getGo2RtcStreams(profile: ConnectionProfile): String {
            bulkStreamsRequested = true
            return BULK_STREAMS
        }

        override suspend fun getGo2RtcStream(profile: ConnectionProfile, streamName: String): String {
            probedStreamNames += streamName
            return """{"producers":[{"medias":["video/H264 1920x1080"]}]}"""
        }

        override suspend fun getReview(profile: ConnectionProfile, query: ReviewSearchQuery): String {
            reviewRequested = true
            reviewQuery = query
            return REVIEWS
        }

        override suspend fun getRecordings(
            profile: ConnectionProfile,
            camera: String,
            after: Double,
            before: Double,
        ): String {
            recordingCameras += camera
            return """[{"start_time":0.5,"end_time":2.5}]"""
        }

        override fun reviewPlaybackUrl(profile: ConnectionProfile, item: ReviewItem) = "https://example.invalid/vod"
    }

    private companion object {
        val PROFILE = ConnectionProfile("https://example.invalid:8971", "limited")
        val LIMITED_USER = FrigateUserProfile("limited", "viewer", setOf("Allowed"))
        val CONFIG = """
            {
              "go2rtc": {"streams": {"Allowed_main": {}, "Restricted_main": {}}},
              "cameras": {
                "Allowed": {"enabled": true, "live": {"streams": {"Main": "Allowed_main"}}},
                "Restricted": {"enabled": true, "live": {"streams": {"Main": "Restricted_main"}}}
              }
            }
        """.trimIndent()
        val BULK_STREAMS = """
            {
              "Allowed_main": {"producers": [{"medias": ["video/H264"]}]},
              "Restricted_main": {"producers": [{"medias": ["video/HEVC"]}]}
            }
        """.trimIndent()
        val REVIEWS = """
            [
              {"id":"a","camera":"Allowed","start_time":1,"end_time":2,"severity":"alert","data":{}},
              {"id":"r","camera":"Restricted","start_time":1,"end_time":2,"severity":"alert","data":{}}
            ]
        """.trimIndent()
    }
}
