package app.opah.tv.domain

import app.opah.tv.data.model.Camera
import app.opah.tv.data.model.CodecCapability
import app.opah.tv.data.model.LiveStreamOption
import app.opah.tv.data.model.StreamMetadata
import app.opah.tv.data.model.StreamPreference
import app.opah.tv.data.model.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSelectionServiceTest {
    private val service = StreamSelectionService()

    @Test
    fun `automatic prefers the available compatible main stream`() {
        val selection = service.select(
            camera = camera(
                option("Low bandwidth", "driveway_sub", VideoCodec.AVC, available = true),
                option("Main", "driveway", VideoCodec.HEVC, available = true),
            ),
            deviceCodecs = listOf(codec(VideoCodec.AVC), codec(VideoCodec.HEVC)),
        ).getOrThrow()

        assertEquals("driveway", selection.option.streamName)
        assertTrue(selection.reason.contains("HEVC"))
    }

    @Test
    fun `automatic falls back when the preferred codec has no decoder`() {
        val selection = service.select(
            camera = camera(
                option("Main", "door_main", VideoCodec.HEVC, available = true),
                option("Low bandwidth", "door_sub", VideoCodec.AVC, available = true),
            ),
            deviceCodecs = listOf(codec(VideoCodec.AVC)),
        ).getOrThrow()

        assertEquals("door_sub", selection.option.streamName)
    }

    @Test
    fun `low-bandwidth preference is explicit and does not infer from stream name alone`() {
        val selection = service.select(
            camera = camera(
                option("Main", "opaque-a", VideoCodec.AVC, available = true),
                option("Low bandwidth", "opaque-b", VideoCodec.AVC, available = true),
            ),
            deviceCodecs = listOf(codec(VideoCodec.AVC)),
            preference = StreamPreference.LOW_BANDWIDTH,
        ).getOrThrow()

        assertEquals("opaque-b", selection.option.streamName)
    }

    @Test
    fun `unknown metadata remains eligible when it is the only candidate`() {
        val selection = service.select(
            camera = camera(
                LiveStreamOption("Main", "unprobed", metadata = null),
            ),
            deviceCodecs = listOf(codec(VideoCodec.AVC)),
        ).getOrThrow()

        assertEquals("unprobed", selection.option.streamName)
        assertTrue(selection.reason.contains("codec will be verified by playback"))
    }

    @Test
    fun `returns an actionable failure when no configured codec is supported`() {
        val result = service.select(
            camera = camera(
                option("Main", "hevc-only", VideoCodec.HEVC, available = true),
            ),
            deviceCodecs = listOf(codec(VideoCodec.AVC)),
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("No compatible decoder"))
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("HEVC"))
    }

    @Test
    fun `fails cleanly when Frigate exposes no live choices`() {
        val result = service.select(
            camera = Camera("empty", "Empty", 0, emptyList()),
            deviceCodecs = listOf(codec(VideoCodec.AVC)),
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("No live streams"))
    }

    private fun camera(vararg streams: LiveStreamOption): Camera = Camera(
        name = "camera",
        displayName = "Camera",
        order = 0,
        streams = streams.toList(),
    )

    private fun option(
        label: String,
        streamName: String,
        videoCodec: VideoCodec,
        available: Boolean,
    ): LiveStreamOption = LiveStreamOption(
        label = label,
        streamName = streamName,
        metadata = StreamMetadata(
            streamName = streamName,
            available = available,
            videoCodec = videoCodec,
        ),
    )

    private fun codec(videoCodec: VideoCodec): CodecCapability = CodecCapability(
        label = videoCodec.displayName,
        mimeType = requireNotNull(videoCodec.mimeType),
        decoders = emptyList(),
    ).copy(
        decoders = listOf(
            app.opah.tv.data.model.DecoderCapability(
                name = "test-decoder",
                mimeType = requireNotNull(videoCodec.mimeType),
                hardwareAccelerated = true,
                softwareOnly = false,
                vendor = true,
                adaptivePlayback = true,
            ),
        ),
    )
}
