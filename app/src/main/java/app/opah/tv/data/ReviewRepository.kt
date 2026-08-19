package app.opah.tv.data

import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.model.RecordingSegment
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.ReviewSearchQuery
import app.opah.tv.data.network.FrigateGateway
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class ReviewDiscovery(
    val items: List<ReviewItem>,
    val warnings: List<String> = emptyList(),
)

/** Loads Frigate Review data and resolves whether its recording windows still exist. */
class ReviewRepository(
    private val gateway: FrigateGateway,
    private val parsers: FrigateJsonParsers,
) {
    suspend fun recent(
        profile: ConnectionProfile,
        allowedCameras: Set<String>,
        limit: Int = DEFAULT_FETCH_LIMIT,
    ): ReviewDiscovery = coroutineScope {
        val items = runCatching {
            load(
                profile = profile,
                allowedCameras = allowedCameras,
                query = ReviewSearchQuery(
                    cameras = allowedCameras,
                    after = (System.currentTimeMillis() / 1000.0) - RECENT_WINDOW_SECONDS,
                    limit = limit,
                ),
            )
        }.getOrElse {
            return@coroutineScope ReviewDiscovery(
                items = emptyList(),
                warnings = listOf("Recent Review items unavailable."),
            )
        }
        if (items.isEmpty()) return@coroutineScope ReviewDiscovery(emptyList())

        val warnings = mutableListOf<String>()

        val now = System.currentTimeMillis() / 1000.0
        val recordingResults = items.groupBy(ReviewItem::camera).map { (camera, cameraItems) ->
            async {
                val after = (cameraItems.minOf(ReviewItem::startTime) - RECORDING_PADDING_SECONDS)
                    .coerceAtLeast(0.0)
                val before = cameraItems.maxOf { it.endTime ?: now } + RECORDING_PADDING_SECONDS
                camera to runCatching {
                    parsers.parseRecordingSegments(gateway.getRecordings(profile, camera, after, before))
                }
            }
        }.map { it.await() }

        recordingResults.forEach { (camera, result) ->
            if (result.isFailure) {
                warnings += "Recording availability unavailable for ${camera.replace('_', ' ')}."
            }
        }
        val recordingsByCamera: Map<String, List<RecordingSegment>?> =
            recordingResults.associate { (camera, result) -> camera to result.getOrNull() }
        val resolvedItems = items.map { item ->
            val segments = recordingsByCamera[item.camera]
            val windowStart = (item.startTime - RECORDING_PADDING_SECONDS).coerceAtLeast(0.0)
            val windowEnd = (item.endTime ?: now) + RECORDING_PADDING_SECONDS
            item.copy(
                recordingAvailable = segments?.any { segment ->
                    segment.startTime <= windowEnd && segment.endTime >= windowStart
                },
            )
        }
        ReviewDiscovery(resolvedItems, warnings)
    }

    suspend fun search(
        profile: ConnectionProfile,
        allowedCameras: Set<String>,
        query: ReviewSearchQuery,
    ): ReviewDiscovery {
        if (allowedCameras.isEmpty()) return ReviewDiscovery(emptyList())
        return ReviewDiscovery(load(profile, allowedCameras, query))
    }

    suspend fun recordingAvailable(
        profile: ConnectionProfile,
        item: ReviewItem,
    ): Result<Boolean> = runCatching {
        val now = System.currentTimeMillis() / 1000.0
        val windowStart = (item.startTime - RECORDING_PADDING_SECONDS).coerceAtLeast(0.0)
        val windowEnd = (item.endTime ?: now) + RECORDING_PADDING_SECONDS
        parsers.parseRecordingSegments(
            gateway.getRecordings(profile, item.camera, windowStart, windowEnd),
        ).any { segment -> segment.startTime <= windowEnd && segment.endTime >= windowStart }
    }

    fun playbackUrl(profile: ConnectionProfile, item: ReviewItem): String =
        gateway.reviewPlaybackUrl(profile, item)

    private suspend fun load(
        profile: ConnectionProfile,
        allowedCameras: Set<String>,
        query: ReviewSearchQuery,
    ): List<ReviewItem> {
        val requestedCameras = query.cameras.intersect(allowedCameras)
        if (requestedCameras.isEmpty()) return emptyList()
        val constrained = query.copy(cameras = requestedCameras)
        return parsers.parseReviewItems(gateway.getReview(profile, constrained))
            .filter { it.camera in requestedCameras }
    }

    private companion object {
        const val DEFAULT_FETCH_LIMIT = 50
        const val RECORDING_PADDING_SECONDS = 8.0
        const val RECENT_WINDOW_SECONDS = 24 * 60 * 60.0
    }
}
