package app.opah.tv.data

import app.opah.tv.data.model.AudioCodec
import app.opah.tv.data.model.ReviewSeverity
import app.opah.tv.data.model.StreamMetadata
import app.opah.tv.data.model.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrigateJsonParsersTest {
    private val parser = FrigateJsonParsers()

    @Test
    fun `discovers fixture cameras in Frigate order and preserves live labels`() {
        val metadata = parser.parseGo2RtcStreams(fixture("go2rtc_streams_sanitized.json"))

        val cameras = parser.parseCameras(
            configJson = fixture("frigate_config_0_17_sanitized.json"),
            streamMetadata = metadata,
        )

        assertEquals(
            listOf("Camera_Alpha", "Camera_Beta", "Camera_Gamma", "Camera_Delta", "Camera_Epsilon"),
            cameras.map { it.name },
        )
        assertEquals("Camera Beta", cameras[1].displayName)
        assertEquals(
            listOf("Main with audio", "Main", "Low bandwidth"),
            cameras.first().streams.map { it.label },
        )
        assertEquals(
            listOf("Camera_Alpha_audio", "Camera_Alpha", "Camera_Alpha_sub"),
            cameras.first().streams.map { it.streamName },
        )
        assertEquals(VideoCodec.HEVC, cameras.first().streams.first().metadata?.videoCodec)
        assertEquals(AudioCodec.OPUS, cameras.first().streams.first().metadata?.audioCodec)
    }

    @Test
    fun `camera discovery enforces the profile camera allow-list`() {
        val cameras = parser.parseCameras(
            configJson = fixture("frigate_config_0_17_sanitized.json"),
            streamMetadata = emptyMap(),
            allowedCameras = setOf("Camera_Epsilon", "Camera_Alpha"),
        )

        assertEquals(listOf("Camera_Alpha", "Camera_Epsilon"), cameras.map { it.name })
        assertTrue(
            parser.parseCameras(
                fixture("frigate_config_0_17_sanitized.json"),
                emptyMap(),
                allowedCameras = emptySet(),
            ).isEmpty(),
        )
    }

    @Test
    fun `configured camera names include every permission-relevant camera`() {
        val names = parser.parseConfiguredCameraNames(
            """
                {
                  "cameras": {
                    "Visible": {"enabled": true},
                    "DashboardHidden": {"enabled": true, "ui": {"dashboard": false}},
                    "Disabled": {"enabled": false}
                  }
                }
            """.trimIndent(),
        )

        assertEquals(setOf("Visible", "DashboardHidden", "Disabled"), names)
    }

    @Test
    fun `authorized camera names retain friendly names even when hidden from the dashboard`() {
        val names = parser.parseAuthorizedCameraNames(
            """
            {"cameras": {
              "Visible": {"friendly_name": "Front Door"},
              "Hidden": {"friendly_name": "Utility", "ui": {"dashboard": false}},
              "Restricted": {"friendly_name": "Private"}
            }}
            """.trimIndent(),
            setOf("Visible", "Hidden"),
        )

        assertEquals(mapOf("Visible" to "Front Door", "Hidden" to "Utility"), names)
    }

    @Test
    fun `filters disabled and dashboard-hidden cameras and provides configured fallback`() {
        val config = """
            {
              "go2rtc": {"streams": {"Fallback": {}}},
              "cameras": {
                "Hidden": {"enabled": true, "ui": {"dashboard": false, "order": 0}},
                "Disabled": {"enabled": false, "ui": {"order": 1}},
                "Fallback": {"enabled": true, "friendly_name": "Fallback camera", "ui": {"order": 2}}
              }
            }
        """.trimIndent()

        val cameras = parser.parseCameras(config, emptyMap())

        assertEquals(1, cameras.size)
        assertEquals("Fallback camera", cameras.single().displayName)
        assertEquals("Fallback", cameras.single().streams.single().streamName)
    }

    @Test
    fun `parses optional go2rtc evidence without retaining source urls`() {
        val metadata = parser.parseGo2RtcStreams(
            """
                {
                  "secure stream": {
                    "producers": [{
                      "medias": [
                        "video/HEVC 3840x2160",
                        "audio/opus",
                        "rtsp://redacted.invalid/private"
                      ]
                    }]
                  }
                }
            """.trimIndent(),
        ).getValue("secure stream")

        assertTrue(metadata.available)
        assertEquals(VideoCodec.HEVC, metadata.videoCodec)
        assertEquals(AudioCodec.OPUS, metadata.audioCodec)
        assertEquals(3840, metadata.width)
        assertEquals(2160, metadata.height)
        assertFalse(metadata.evidence.any { it.contains("rtsp://", ignoreCase = true) })
    }

    @Test
    fun `treats an empty producer list as unavailable`() {
        val metadata = parser.parseGo2RtcStreams(
            fixture("go2rtc_streams_sanitized.json"),
        ).getValue("Camera_Epsilon_sub")

        assertFalse(metadata.available)
        assertEquals(VideoCodec.UNKNOWN, metadata.videoCodec)
        assertEquals(AudioCodec.NONE, metadata.audioCodec)
        assertNull(metadata.width)
    }

    @Test
    fun `parses review severity objects audio and zones independently`() {
        val items = parser.parseReviewItems(fixture("review_items_sanitized.json"))

        assertEquals(2, items.size)
        assertEquals(ReviewSeverity.ALERT, items[0].severity)
        assertEquals(listOf("person", "speech"), items[0].objects)
        assertEquals(listOf("zone_one"), items[0].zones)
        assertEquals("/media/frigate/clips/review/sanitized-alert.webp", items[0].thumbnailPath)
        assertFalse(items[0].hasBeenReviewed)
        assertEquals(ReviewSeverity.DETECTION, items[1].severity)
        assertNull(items[1].endTime)
        assertNull(items[1].thumbnailPath)
        assertTrue(items[1].hasBeenReviewed)
    }

    @Test
    fun `parses valid recording ranges and rejects malformed segments`() {
        val segments = parser.parseRecordingSegments(
            """
            [
              {"start_time":30.0,"end_time":40.0,"segment_size":1234},
              {"start_time":10.0,"end_time":20.0},
              {"start_time":50.0,"end_time":50.0},
              {"start_time":"invalid","end_time":60.0}
            ]
            """.trimIndent(),
        )

        assertEquals(listOf(10.0, 30.0), segments.map { it.startTime })
        assertEquals(listOf(20.0, 40.0), segments.map { it.endTime })
    }

    @Test
    fun `parses recording volume and tolerates a relocated recordings mount`() {
        val volume = parser.parseRecordingStorageVolume(
            """
            {
              "service": {"storage": {
                "/custom/media/recordings": {"total": 255979.5, "used": 119808.0, "free": 136171.5}
              }}
            }
            """.trimIndent(),
        )

        requireNotNull(volume)
        assertEquals(255979.5, volume.totalMiB, 0.001)
        assertEquals(119808.0, volume.usedMiB, 0.001)
        assertEquals(136171.5, volume.freeMiB, 0.001)
    }

    @Test
    fun `parses camera storage usage and bandwidth without trusting server percentages`() {
        val samples = parser.parseCameraStorageSamples(
            """
            {
              "Camera One": {"usage": 7485.4, "bandwidth": 4474.9, "usage_percent": 2.92},
              "Camera Two": {"usage": 47400.0, "bandwidth": 10003.0, "usage_percent": 18.56}
            }
            """.trimIndent(),
        )

        assertEquals(listOf("Camera One", "Camera Two"), samples.map { it.serverLabel })
        assertEquals(7485.4, samples.first().usageMiB, 0.001)
        assertEquals(4474.9, samples.first().bandwidthMiBPerHour, 0.001)
    }

    @Test
    fun `parses performance stats and filters camera rows before returning them`() {
        val summary = parser.parsePerformanceSummary(
            rawJson = """
                {
                  "camera_fps": 35.0,
                  "process_fps": 34.5,
                  "detection_fps": 8.2,
                  "skipped_fps": 0.5,
                  "cameras": {
                    "camera_one": {"camera_fps": 15, "process_fps": 14.8, "detection_fps": 3.2, "skipped_fps": 0.2},
                    "restricted": {"camera_fps": 20, "process_fps": 19.7, "detection_fps": 5, "skipped_fps": 0.3}
                  },
                  "detectors": {"coral": {"inference_speed": 7.64}},
                  "cpu_usages": {
                    "123": {"cpu": "12.5", "mem": "3.0"},
                    "456": {"cpu": 7.5, "mem": 2.0},
                    "frigate.full_system": {"cpu": "42.5", "mem": "61.2"},
                    "frigate": {"cpu": 99, "mem": 99, "cmdline": "must not be counted"}
                  },
                  "gpu_usages": {"intel-vaapi": {"gpu": "18 %", "mem": "24.5 %"}},
                  "npu_usages": {"hailo": {"usage": "31%"}},
                  "temperatures": {"apex": 47.5, "gpu": {"temperature": "52 C"}},
                  "service": {"version": "0.17.2", "uptime": 86461}
                }
            """.trimIndent(),
            fallbackVersion = "fallback",
            authorizedCameraNames = mapOf("camera_one" to "Camera One"),
        )

        assertEquals("0.17.2", summary.version)
        assertEquals(86461.0, summary.uptimeSeconds!!, 0.001)
        assertEquals(42.5, summary.systemCpuPercent!!, 0.001)
        assertEquals(20.0, summary.frigateCpuPercent!!, 0.001)
        assertEquals(5.0, summary.frigateMemoryPercent!!, 0.001)
        assertEquals(listOf("Camera One"), summary.cameras.map { it.displayName })
        assertEquals(7.64, summary.detectors.single().inferenceSpeedMs!!, 0.001)
        assertEquals(listOf("GPU", "NPU"), summary.accelerators.map { it.kind })
        assertEquals(18.0, summary.accelerators.first().usagePercent!!, 0.001)
        assertEquals(listOf(47.5, 52.0), summary.temperatures.map { it.celsius })
    }

    @Test
    fun `reports sample Birdseye as enabled but not restreamed`() {
        val status = parser.parseBirdseyeStatus(
            configJson = fixture("frigate_config_0_17_sanitized.json"),
            metadata = emptyMap(),
        )

        assertTrue(status.enabled)
        assertFalse(status.restreamConfigured)
        assertFalse(status.streamAvailable)
        assertFalse(status.playable)
        assertNull(status.streamName)
    }

    @Test
    fun `reports Birdseye available only from probed metadata`() {
        val status = parser.parseBirdseyeStatus(
            configJson = "{\"birdseye\":{\"enabled\":true,\"restream\":true}}",
            metadata = mapOf(
                "birdseye" to StreamMetadata("birdseye", available = true),
            ),
        )

        assertTrue(status.enabled)
        assertTrue(status.restreamConfigured)
        assertTrue(status.streamAvailable)
        assertTrue(status.playable)
        assertEquals("birdseye", status.streamName)
    }

    private fun fixture(name: String): String = requireNotNull(
        javaClass.getResource("/fixtures/$name"),
    ) { "Missing test fixture: $name" }.readText()
}
