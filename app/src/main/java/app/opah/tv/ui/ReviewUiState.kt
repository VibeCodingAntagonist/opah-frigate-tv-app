package app.opah.tv.ui

import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.ReviewSearchQuery
import app.opah.tv.data.model.ReviewSeverity

enum class ReviewTimeRange(
    val displayName: String,
    val seconds: Double,
) {
    LAST_DAY("24 hours", 24 * 60 * 60.0),
    LAST_THREE_DAYS("3 days", 3 * 24 * 60 * 60.0),
    LAST_WEEK("7 days", 7 * 24 * 60 * 60.0),
}

data class ReviewFilters(
    val severity: ReviewSeverity = ReviewSeverity.ALERT,
    val camera: String? = null,
    val label: String? = null,
    val zone: String? = null,
    val timeRange: ReviewTimeRange = ReviewTimeRange.LAST_DAY,
)

enum class ReviewRecordingState {
    IDLE,
    CHECKING,
    AVAILABLE,
    UNAVAILABLE,
    UNKNOWN,
}

data class ReviewBrowserState(
    val filters: ReviewFilters = ReviewFilters(),
    val items: List<ReviewItem> = emptyList(),
    val knownLabels: Set<String> = emptySet(),
    val knownZones: Set<String> = emptySet(),
    val loading: Boolean = false,
    val loadedOnce: Boolean = false,
    val errorMessage: String? = null,
    val selectedItemId: String? = null,
    val recordingState: ReviewRecordingState = ReviewRecordingState.IDLE,
    val detailErrorMessage: String? = null,
)

internal fun ReviewFilters.toSearchQuery(
    allowedCameras: Set<String>,
    nowSeconds: Double,
): ReviewSearchQuery {
    val requestedCameras = camera?.let(::setOf) ?: allowedCameras
    return ReviewSearchQuery(
        cameras = requestedCameras.intersect(allowedCameras),
        severity = severity,
        label = label,
        zone = zone,
        after = (nowSeconds - timeRange.seconds).coerceAtLeast(0.0),
        before = nowSeconds,
        limit = 100,
    )
}
