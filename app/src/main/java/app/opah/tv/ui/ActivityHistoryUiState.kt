package app.opah.tv.ui

import app.opah.tv.data.model.RecordingSegment
import app.opah.tv.data.model.RecordingHourSummary
import app.opah.tv.data.model.MotionActivity
import app.opah.tv.data.model.SearchEvent
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.MotionSearchJobState
import app.opah.tv.data.model.MotionSearchResult
import app.opah.tv.data.model.MotionSearchPoint
import kotlin.math.floor
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

data class HistoryBrowserState(
    val cameraName: String? = null,
    val hourStartSeconds: Double? = null,
    val segments: List<RecordingSegment> = emptyList(),
    val hourSummaries: List<RecordingHourSummary> = emptyList(),
    val motion: List<MotionActivity> = emptyList(),
    val summaryLoading: Boolean = false,
    val loading: Boolean = false,
    val loadedOnce: Boolean = false,
    val errorMessage: String? = null,
    val savingSlotStartTime: Double? = null,
    val savedSlotKeys: Set<String> = emptySet(),
    val savedMessage: String? = null,
    val cursorTimeSeconds: Double? = null,
)

data class MotionReviewUiState(
    val cameraName: String? = null,
    val regionIndex: Int = 4,
    val jobId: String? = null,
    val jobState: MotionSearchJobState = MotionSearchJobState.UNKNOWN,
    val results: List<MotionSearchResult> = emptyList(),
    val progress: Double? = null,
    val searching: Boolean = false,
    val searchedOnce: Boolean = false,
    val errorMessage: String? = null,
)

internal fun MotionReviewUiState.withMotionSearchSelection(
    cameraName: String = this.cameraName.orEmpty(),
    regionIndex: Int = this.regionIndex,
): MotionReviewUiState = copy(
    cameraName = cameraName.ifBlank { this.cameraName },
    regionIndex = regionIndex.coerceIn(0, 8),
    jobId = null,
    jobState = MotionSearchJobState.UNKNOWN,
    results = emptyList(),
    progress = null,
    searchedOnce = false,
    errorMessage = null,
)

internal fun MotionReviewUiState.beginMotionSearch(cameraName: String): MotionReviewUiState = copy(
    cameraName = cameraName,
    jobId = null,
    searching = true,
    searchedOnce = true,
    jobState = MotionSearchJobState.QUEUED,
    results = emptyList(),
    progress = 0.0,
    errorMessage = null,
)

enum class HistoryTimelineScale(
    val label: String,
    val windowSeconds: Double,
    val baseSeekSeconds: Double,
) {
    FIVE_MINUTES("5 min", 5 * 60.0, 5.0),
    THIRTY_MINUTES("30 min", 30 * 60.0, 15.0),
    TWO_HOURS("2 hr", 2 * 60 * 60.0, 60.0),
}

internal fun acceleratedHistorySeekSeconds(
    scale: HistoryTimelineScale,
    repeatCount: Int,
): Double = scale.baseSeekSeconds * when {
    repeatCount >= 20 -> 12.0
    repeatCount >= 10 -> 6.0
    repeatCount >= 4 -> 3.0
    else -> 1.0
}

internal fun boundedHistoryCursor(
    cursorSeconds: Double,
    deltaSeconds: Double,
    rangeStartSeconds: Double,
    rangeEndSeconds: Double,
): Double = (cursorSeconds + deltaSeconds).coerceIn(rangeStartSeconds, rangeEndSeconds)

internal fun historyTimelineWindow(
    cursorSeconds: Double,
    scale: HistoryTimelineScale,
    rangeStartSeconds: Double,
    rangeEndSeconds: Double,
): ClosedFloatingPointRange<Double> {
    val available = (rangeEndSeconds - rangeStartSeconds).coerceAtLeast(0.0)
    val width = scale.windowSeconds.coerceAtMost(available)
    val idealStart = cursorSeconds - width / 2.0
    val start = idealStart.coerceIn(rangeStartSeconds, (rangeEndSeconds - width).coerceAtLeast(rangeStartSeconds))
    return start..(start + width)
}

internal fun motionSearchPolygon(regionIndex: Int): List<MotionSearchPoint> {
    val safeIndex = regionIndex.coerceIn(0, 8)
    val row = safeIndex / 3
    val column = safeIndex % 3
    val inset = 0.02
    val left = (column / 3.0 + inset).coerceIn(0.0, 1.0)
    val top = (row / 3.0 + inset).coerceIn(0.0, 1.0)
    val right = ((column + 1) / 3.0 - inset).coerceIn(0.0, 1.0)
    val bottom = ((row + 1) / 3.0 - inset).coerceIn(0.0, 1.0)
    return listOf(
        MotionSearchPoint(left, top),
        MotionSearchPoint(right, top),
        MotionSearchPoint(right, bottom),
        MotionSearchPoint(left, bottom),
    )
}

internal fun motionRegionLabel(index: Int): String = listOf(
    "Top left", "Top center", "Top right",
    "Middle left", "Center", "Middle right",
    "Bottom left", "Bottom center", "Bottom right",
)[index.coerceIn(0, 8)]

internal fun motionRegionGlyph(index: Int): String = listOf(
    "↖", "↑", "↗",
    "←", "•", "→",
    "↙", "↓", "↘",
)[index.coerceIn(0, 8)]

enum class ActivitySearchTimeRange(val displayName: String, val seconds: Double?) {
    TODAY("Today", null),
    OVERNIGHT("Overnight", null),
    LAST_DAY("24 hours", 24 * 60 * 60.0),
    LAST_THREE_DAYS("3 days", 3 * 24 * 60 * 60.0),
    LAST_WEEK("7 days", 7 * 24 * 60 * 60.0),
    ALL("Any time", null),
}

data class ActivitySearchBounds(
    val afterSeconds: Double?,
    val beforeSeconds: Double?,
)

data class SuggestedActivitySearch(
    val label: String,
    val query: String,
    val timeRange: ActivitySearchTimeRange = ActivitySearchTimeRange.ALL,
)

internal val ACTIVITY_SEARCH_SUGGESTIONS = listOf(
    SuggestedActivitySearch("Person", "person"),
    SuggestedActivitySearch("Car", "car"),
    SuggestedActivitySearch("Package", "package"),
    SuggestedActivitySearch("Animal", "animal"),
    SuggestedActivitySearch("Today", "activity", ActivitySearchTimeRange.TODAY),
    SuggestedActivitySearch("Overnight", "activity", ActivitySearchTimeRange.OVERNIGHT),
)

internal fun activitySearchBounds(
    range: ActivitySearchTimeRange,
    nowSeconds: Double,
    timeZone: TimeZone = TimeZone.getDefault(),
): ActivitySearchBounds {
    range.seconds?.let { return ActivitySearchBounds((nowSeconds - it).coerceAtLeast(0.0), null) }
    if (range == ActivitySearchTimeRange.ALL) return ActivitySearchBounds(null, null)
    val calendar = Calendar.getInstance(timeZone).apply { time = Date((nowSeconds * 1_000).toLong()) }
    return when (range) {
        ActivitySearchTimeRange.TODAY -> {
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            ActivitySearchBounds(calendar.timeInMillis / 1_000.0, null)
        }
        ActivitySearchTimeRange.OVERNIGHT -> {
            val before = calendar.clone() as Calendar
            if (calendar.get(Calendar.HOUR_OF_DAY) < 6) {
                before.timeInMillis = (nowSeconds * 1_000).toLong()
            } else {
                before.set(Calendar.HOUR_OF_DAY, 6)
                before.set(Calendar.MINUTE, 0)
                before.set(Calendar.SECOND, 0)
                before.set(Calendar.MILLISECOND, 0)
            }
            val after = before.clone() as Calendar
            after.add(Calendar.DAY_OF_MONTH, -1)
            after.set(Calendar.HOUR_OF_DAY, 22)
            after.set(Calendar.MINUTE, 0)
            after.set(Calendar.SECOND, 0)
            after.set(Calendar.MILLISECOND, 0)
            ActivitySearchBounds(after.timeInMillis / 1_000.0, before.timeInMillis / 1_000.0)
        }
        else -> ActivitySearchBounds(null, null)
    }
}

data class ActivitySearchFilters(
    val cameraName: String? = null,
    val label: String? = null,
    val subLabel: String? = null,
    val zone: String? = null,
    val recognizedLicensePlate: String? = null,
    val timeRange: ActivitySearchTimeRange = ActivitySearchTimeRange.ALL,
)

data class ActivitySearchState(
    val query: String = "",
    val filters: ActivitySearchFilters = ActivitySearchFilters(),
    val results: List<SearchEvent> = emptyList(),
    val resultGeneration: Long = 0,
    val searching: Boolean = false,
    val loadingMore: Boolean = false,
    val searchedOnce: Boolean = false,
    val page: Int = 1,
    val hasMore: Boolean = false,
    val similarToEventId: String? = null,
    val similarToLabel: String? = null,
    val errorMessage: String? = null,
    val similarToCamera: String? = null,
)

internal data class ActivitySimilaritySeed(val eventId: String, val cameraName: String, val label: String)

/** Review detections already identify tracked objects; loading the detail pane is optional. */
internal fun ReviewItem.activitySimilaritySeed(): ActivitySimilaritySeed? {
    val linked = linkedEvents.firstOrNull { event ->
        event.camera == camera && event.id.isNotBlank() &&
            (detectionIds.isEmpty() || event.id in detectionIds)
    }
    val foreignIds = linkedEvents.filter { it.camera != camera }.mapTo(mutableSetOf(), SearchEvent::id)
    val id = linked?.id ?: detectionIds.firstOrNull { it.isNotBlank() && it !in foreignIds } ?: return null
    // The context label describes the object, never a recognized person's name or plate.
    val label = linked?.label?.takeIf(String::isNotBlank)
        ?: objects.filter(String::isNotBlank).distinct().singleOrNull() ?: "activity"
    return ActivitySimilaritySeed(id, camera, label)
}

internal val ActivitySearchState.isSimilaritySearch: Boolean get() = !similarToEventId.isNullOrBlank()
internal val ActivitySearchState.searchDescription: String get() = if (isSimilaritySearch) {
    "Similar to ${similarToLabel?.takeIf(String::isNotBlank)?.replace('_', ' ')?.replaceFirstChar(Char::uppercase) ?: "selected activity"}"
} else query

internal fun ActivitySearchState.afterSearchScopeChanged(allowedCameras: Set<String>): ActivitySearchState =
    if (isSimilaritySearch && similarToCamera !in allowedCameras) ActivitySearchState()
    else copy(results = results.filter { it.camera in allowedCameras })

internal fun ActivitySearchState.beginActivitySearch(
    query: String,
    filters: ActivitySearchFilters,
    eventId: String?,
    similarLabel: String?,
    append: Boolean,
    requestId: Long,
    sourceCamera: String? = null,
): ActivitySearchState = copy(
    query = query,
    filters = filters,
    results = if (append) results else emptyList(),
    resultGeneration = if (append) resultGeneration else requestId,
    searching = !append,
    loadingMore = append,
    searchedOnce = if (append) searchedOnce else false,
    page = if (append) page else 1,
    hasMore = if (append) hasMore else false,
    similarToEventId = eventId,
    similarToLabel = similarLabel,
    similarToCamera = sourceCamera,
    errorMessage = null,
)

internal val ActivitySearchState.cameraName: String? get() = filters.cameraName

internal fun historySlotKey(cameraName: String, slot: HistorySlot): String =
    "$cameraName:${slot.startTime.toLong()}:${slot.endTime.toLong()}"

internal fun HistorySlot.motionLevel(activity: List<MotionActivity>): Double = activity
    .asSequence()
    .filter { it.startTime >= startTime && it.startTime < endTime }
    .sumOf(MotionActivity::motion)

data class HistorySlot(
    val startTime: Double,
    val endTime: Double,
    val playbackStartTime: Double?,
) {
    val available: Boolean get() = playbackStartTime != null
}

internal fun hourStart(epochSeconds: Double): Double =
    floor(epochSeconds / HISTORY_HOUR_SECONDS) * HISTORY_HOUR_SECONDS

internal fun historySlots(
    hourStartSeconds: Double,
    segments: List<RecordingSegment>,
    nowSeconds: Double,
): List<HistorySlot> = (0 until HISTORY_SLOT_COUNT).map { index ->
    val start = hourStartSeconds + index * HISTORY_SLOT_SECONDS
    val end = (start + HISTORY_SLOT_SECONDS).coerceAtMost(nowSeconds)
    val firstRecordingTime = segments.asSequence()
        .filter { it.endTime > start && it.startTime < end }
        .map { it.startTime.coerceAtLeast(start) }
        .minOrNull()
    HistorySlot(
        startTime = start,
        endTime = end.coerceAtLeast(start),
        playbackStartTime = firstRecordingTime.takeIf { end > start },
    )
}

internal fun canMoveHistoryForward(hourStartSeconds: Double, nowSeconds: Double): Boolean =
    hourStartSeconds + HISTORY_HOUR_SECONDS <= hourStart(nowSeconds)

internal const val HISTORY_HOUR_SECONDS = 60 * 60.0
private const val HISTORY_SLOT_SECONDS = 15 * 60.0
private const val HISTORY_SLOT_COUNT = 4
