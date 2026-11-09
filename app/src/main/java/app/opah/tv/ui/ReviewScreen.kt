package app.opah.tv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.opah.tv.data.model.Camera
import app.opah.tv.data.model.FrigateFeature
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.ReviewSeverity
import app.opah.tv.data.model.SearchEvent
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private enum class ReviewPicker { CAMERA, LABEL, ZONE, TIME, REVIEW_STATUS }

private data class ReviewPickerOption(
    val key: String,
    val label: String,
)

@Composable
internal fun ReviewScreen(
    state: Phase0UiState,
    restoreFocusKey: String?,
    onFocusRestored: () -> Unit,
    onLoad: () -> Unit,
    onLoadMore: () -> Unit,
    onStartQueue: () -> Unit,
    onMoveQueue: (Int) -> Unit,
    onEndQueue: () -> Unit,
    onPage: (ActivityPage) -> Unit,
    onSeverity: (ReviewSeverity?) -> Unit,
    onApplyFilters: (ReviewFilters) -> Unit,
    onResetFilters: () -> Unit,
    onSelectItem: (ReviewItem, String) -> Unit,
    onPlayItem: (ReviewItem) -> Unit,
    onSetReviewed: (ReviewItem, Boolean) -> Unit,
    onMarkAllReviewed: () -> Unit,
    onSaveClip: (ReviewItem) -> Unit,
    onSaveAllAngles: (ReviewItem, Set<String>) -> Unit,
    onFindSimilar: (ReviewItem) -> Unit,
    cachedBitmap: (ReviewItem) -> Bitmap?,
    refreshBitmap: suspend (ReviewItem, Int) -> Bitmap?,
) {
    val review = state.review
    val selected = review.selectedItemId?.let { selectedId ->
        review.items.firstOrNull { it.id == selectedId }
    }
    var filtersVisible by rememberSaveable { mutableStateOf(false) }
    var markAllConfirmationVisible by rememberSaveable { mutableStateOf(false) }
    var filtersFocusRestoreToken by rememberSaveable { mutableIntStateOf(0) }
    val filtersFocusRequester = remember { FocusRequester() }
    val recentPageFocusRequester = remember { FocusRequester() }
    val alertsFocusRequester = remember { FocusRequester() }
    val allActivityFocusRequester = remember { FocusRequester() }
    val reviewEntryFocusRequester = remember { FocusRequester() }
    val reviewListState = rememberLazyListState()
    val selectedCategoryFocusRequester = if (review.filters.severity == ReviewSeverity.ALERT) {
        alertsFocusRequester
    } else {
        allActivityFocusRequester
    }
    val unreviewedShownActivity = review.unreviewedShownActivity()
    val reviewEntryItemId by remember(review.items, reviewListState) {
        derivedStateOf { review.items.getOrNull(reviewListState.firstVisibleItemIndex)?.id }
    }

    LaunchedEffect(review.loadedOnce) {
        if (!review.loadedOnce && !review.loading) onLoad()
    }
    LaunchedEffect(filtersFocusRestoreToken) {
        if (filtersFocusRestoreToken > 0) {
            delay(100)
            filtersFocusRequester.requestFocus()
        }
    }
    LaunchedEffect(review.items.isEmpty(), restoreFocusKey) {
        if (review.items.isEmpty() && restoreFocusKey != null) {
            delay(100)
            if (filtersFocusRequester.requestFocus()) onFocusRestored()
        }
    }

    if (selected != null) {
        ReviewDetail(
            item = selected,
            imageRevision = state.privacy.epoch,
            recordingState = review.recordingState,
            errorMessage = review.detailErrorMessage,
            onPlay = { onPlayItem(selected) },
            onSetReviewed = { reviewed -> onSetReviewed(selected, reviewed) },
            markingReviewed = review.markingReviewedItemId == selected.id,
            onSaveClip = { onSaveClip(selected) },
            onSaveAllAngles = { cameras -> onSaveAllAngles(selected, cameras) },
            allAnglesAvailable = state.snapshot?.capabilities?.supports(
                FrigateFeature.MULTI_CAMERA_EXPORT,
            ) == true && state.snapshot.cameras.size > 1,
            cameras = state.snapshot?.cameras.orEmpty(),
            savingClip = review.savingClipItemId == selected.id,
            savedClip = selected.id in review.savedClipItemIds,
            savedClipMessage = review.savedClipMessage.takeIf { review.savedClipItemId == selected.id },
            onFindSimilar = if (state.snapshot?.capabilities?.supports(FrigateFeature.SEMANTIC_SEARCH) == true &&
                selected.activitySimilaritySeed() != null) ({ onFindSimilar(selected) }) else null,
            queuePosition = if (review.queueActive) {
                "${review.queueIndex + 1} of ${review.queueItemIds.size}"
            } else {
                null
            },
            onPrevious = if (review.queueActive && review.queueIndex > 0) {
                { onMoveQueue(-1) }
            } else {
                null
            },
            onNext = if (review.queueActive) {
                { onMoveQueue(1) }
            } else {
                null
            },
            cachedBitmap = { cachedBitmap(selected) },
            refreshBitmap = { refreshBitmap(selected, 720) },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(start = 24.dp, top = 18.dp, end = 24.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text("Activity", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Alerts are important activity; Everything also includes other detections",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    val unreviewedShown = review.items.count { !it.hasBeenReviewed }
                    if (unreviewedShown > 0) {
                        PrimaryAction(
                            focusKey = "review:start-queue",
                            label = if (review.filters.severity == ReviewSeverity.ALERT) {
                                "Review new alerts"
                            } else {
                                "Review new activity"
                            },
                            onClick = onStartQueue,
                        )
                    }
                    if (review.loading || review.countsLoading) {
                        Text("Refreshing…", color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }

            ActivityNavigation(
                ActivityPage.RECENT,
                restoreFocusKey,
                onFocusRestored,
                onPage,
                state.snapshot?.capabilities?.supports(app.opah.tv.data.model.FrigateFeature.MOTION_SEARCH) == true,
                selectedPageFocusRequester = recentPageFocusRequester,
                downFocusRequester = selectedCategoryFocusRequester,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ReviewCategoryButton(
                    label = "Alerts",
                    selected = review.filters.severity == ReviewSeverity.ALERT,
                    onClick = { onSeverity(ReviewSeverity.ALERT) },
                    externalFocusRequester = alertsFocusRequester,
                    modifier = Modifier.focusProperties {
                        up = recentPageFocusRequester
                        down = reviewEntryFocusRequester
                    },
                )
                ReviewCategoryButton(
                    label = "Everything",
                    selected = review.filters.severity == null,
                    onClick = { onSeverity(null) },
                    externalFocusRequester = allActivityFocusRequester,
                    modifier = Modifier.focusProperties {
                        up = recentPageFocusRequester
                        down = reviewEntryFocusRequester
                    },
                )
                Spacer(Modifier.weight(1f))
                SecondaryAction(
                    focusKey = "review:mark-all-reviewed",
                    label = if (review.markingAllReviewed) "Saving…" else "Mark all reviewed",
                    onClick = { markAllConfirmationVisible = true },
                    enabled = unreviewedShownActivity.isNotEmpty() && !review.markingAllReviewed,
                    modifier = Modifier.focusProperties { down = reviewEntryFocusRequester },
                )
                Button(
                    onClick = {
                        filtersVisible = true
                    },
                    modifier = Modifier
                        .focusRequester(filtersFocusRequester)
                        .focusProperties { down = reviewEntryFocusRequester },
                ) {
                    val count = review.filters.activeDetailCount()
                    Text(if (count == 0) "Filters" else "Filters ($count)")
                }
                if (review.filters.activeDetailCount() > 0) {
                    Button(
                        onClick = {
                            onResetFilters()
                            filtersFocusRestoreToken += 1
                        },
                        modifier = Modifier.focusProperties { down = reviewEntryFocusRequester },
                    ) { Text("Clear") }
                }
            }
            Text(
                review.filters.countSummary(review.counts).let { summary ->
                    if (review.filters.activeDetailCount() == 0 && !review.countsLoading) {
                        summary
                    } else {
                        review.filters.summary(state.snapshot?.cameras.orEmpty())
                    }
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 2.dp),
            )
            }

        review.errorMessage?.let { error ->
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ScreenMessage(error, isError = true, modifier = Modifier.weight(1f))
                Button(onClick = onLoad) { Text("Retry") }
            }
        }

        when {
            review.loading && !review.loadedOnce -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("Loading activity…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            review.queueCompleted -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("You’re caught up", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("There are no more items in this review", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PrimaryAction(
                        focusKey = "review:queue-done",
                        label = "Done",
                        onClick = onEndQueue,
                    )
                }
            }

            review.items.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    review.filters.emptyMessage(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> ReviewGrid(
                items = review.items,
                imageRevision = state.privacy.epoch,
                restoreFocusKey = restoreFocusKey,
                onFocusRestored = onFocusRestored,
                onSelectItem = onSelectItem,
                cachedBitmap = cachedBitmap,
                refreshBitmap = refreshBitmap,
                hasMore = review.hasMore,
                loadingMore = review.loadingMore,
                onLoadMore = onLoadMore,
                listState = reviewListState,
                entryFocusRequester = reviewEntryFocusRequester,
                entryItemId = reviewEntryItemId,
                headerFocusRequester = selectedCategoryFocusRequester,
            )
        }
    }

    if (filtersVisible) {
        ReviewFiltersDialog(
            filters = review.filters,
            state = state,
            onDismiss = {
                filtersVisible = false
                filtersFocusRestoreToken += 1
            },
            onApply = { filters ->
                filtersVisible = false
                filtersFocusRestoreToken += 1
                onApplyFilters(filters)
            },
        )
    }
    if (markAllConfirmationVisible) {
        MarkAllActivityReviewedDialog(
            count = unreviewedShownActivity.size,
            onDismiss = { markAllConfirmationVisible = false },
            onConfirm = {
                markAllConfirmationVisible = false
                onMarkAllReviewed()
            },
        )
    }
}

@Composable
private fun MarkAllActivityReviewedDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        DialogSurface(modifier = Modifier.width(520.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "Mark all shown activity reviewed?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (count == 1) {
                        "The new item currently shown will be marked reviewed\nYou can only undo this one item at a time"
                    } else {
                        "All $count new items currently shown will be marked reviewed\nYou can only undo them one at a time"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                ) {
                    SecondaryAction(
                        focusKey = "review:mark-all-cancel",
                        label = "Cancel",
                        onClick = onDismiss,
                    )
                    PrimaryAction(
                        focusKey = "review:mark-all-confirm",
                        label = "Mark reviewed",
                        onClick = onConfirm,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewCategoryButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    externalFocusRequester: FocusRequester? = null,
) {
    SegmentedTab(
        focusKey = "review:category:${label.lowercase()}",
        label = label,
        onClick = onClick,
        selected = selected,
        modifier = modifier,
        externalFocusRequester = externalFocusRequester,
    )
}

@Composable
private fun ReviewGrid(
    items: List<ReviewItem>,
    imageRevision: Long,
    restoreFocusKey: String?,
    onFocusRestored: () -> Unit,
    onSelectItem: (ReviewItem, String) -> Unit,
    cachedBitmap: (ReviewItem) -> Bitmap?,
    refreshBitmap: suspend (ReviewItem, Int) -> Bitmap?,
    hasMore: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    listState: LazyListState,
    entryFocusRequester: FocusRequester,
    entryItemId: String?,
    headerFocusRequester: FocusRequester,
) {
    val effectiveRestoreFocusKey = restoreFocusKey?.let { requested ->
        requested.takeIf { key -> items.any { reviewItemFocusKey(it.id) == key } }
            ?: items.firstOrNull()?.let { reviewItemFocusKey(it.id) }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val cardWidth = ((maxWidth - 76.dp) / 3).coerceIn(280.dp, 360.dp)
        LazyRow(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
        items(items, key = ReviewItem::id) { item ->
            val focusKey = reviewItemFocusKey(item.id)
            MediaTile(
                focusKey = focusKey,
                restoreFocusKey = effectiveRestoreFocusKey,
                onFocusRestored = onFocusRestored,
                onClick = { onSelectItem(item, focusKey) },
                accessibilityLabel = reviewAccessibilityLabel(item),
                externalFocusRequester = entryFocusRequester.takeIf { item.id == entryItemId },
                modifier = Modifier
                    .width(cardWidth)
                    .focusProperties { up = headerFocusRequester },
            ) {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                    ) {
                        ReviewThumbnail(
                            item = item,
                            imageRevision = imageRevision,
                            cachedBitmap = { cachedBitmap(item) },
                            refreshBitmap = { refreshBitmap(item, 300) },
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (!item.hasBeenReviewed) {
                            StatusBadge(
                                label = "New",
                                color = if (item.severity == ReviewSeverity.ALERT) {
                                    LocalOpahSemanticColors.current.alert
                                } else {
                                    LocalOpahSemanticColors.current.detection
                                },
                                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                item.summary?.title
                                    ?: item.objects.firstOrNull()?.let(::friendlyName)
                                    ?: item.severity.displayName(),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                formatReviewDateTime(item.startTime),
                                color = MaterialTheme.colorScheme.secondary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                modifier = Modifier.padding(start = 10.dp),
                            )
                        }
                        Text(
                            friendlyName(item.camera).orEmpty(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val detail = (item.objects.drop(1) + item.audio + item.zones).distinct()
                            .joinToString(" • ") { friendlyName(it).orEmpty() }
                        Text(
                            text = detail.ifBlank { reviewDuration(item) },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
            if (hasMore || loadingMore) {
                item {
                    Box(
                        modifier = Modifier
                            .width(240.dp)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Button(onClick = onLoadMore, enabled = !loadingMore) {
                            Text(if (loadingMore) "Loading…" else "More activity")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewDetail(
    item: ReviewItem,
    imageRevision: Long,
    recordingState: ReviewRecordingState,
    errorMessage: String?,
    onPlay: () -> Unit,
    onSetReviewed: (Boolean) -> Unit,
    markingReviewed: Boolean,
    onSaveClip: () -> Unit,
    onSaveAllAngles: (Set<String>) -> Unit,
    allAnglesAvailable: Boolean,
    cameras: List<Camera>,
    savingClip: Boolean,
    savedClip: Boolean,
    savedClipMessage: String?,
    onFindSimilar: (() -> Unit)?,
    queuePosition: String?,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    cachedBitmap: () -> Bitmap?,
    refreshBitmap: suspend () -> Bitmap?,
) {
    val initialFocusRequester = remember(item.id) { FocusRequester() }
    var allAnglesVisible by rememberSaveable(item.id) { mutableStateOf(false) }
    val facts = buildList {
        add("Camera" to friendlyName(item.camera).orEmpty())
        add("Duration" to reviewDuration(item))
        add("Objects" to item.objects.joinFriendly())
        if (item.audio.isNotEmpty()) add("Sounds" to item.audio.joinFriendly())
        add("Areas" to item.zones.joinFriendly())
        val recognized = (item.subLabels + item.linkedEvents.mapNotNull { it.subLabel }).distinct()
        if (recognized.isNotEmpty()) add("Recognized" to recognized.joinFriendly())
        item.linkedEvents.mapNotNull { it.recognizedLicensePlate }.distinct().takeIf { it.isNotEmpty() }
            ?.let { add("License plate" to it.joinToString(", ")) }
        item.summary?.shortSummary?.let { add("Summary" to it) }
        if (item.summary?.shortSummary == null) {
            item.linkedEvents.firstNotNullOfOrNull(SearchEvent::description)
                ?.let { add("What happened" to it) }
        }
        item.summary?.scene?.takeIf { it != item.summary.shortSummary }
            ?.let { add("What happened" to it) }
        item.linkedEvents.flatMap(SearchEvent::attributes).distinct().takeIf { it.isNotEmpty() }
            ?.let { add("Details" to it.joinFriendly()) }
        item.summary?.potentialThreatLevel?.let { add("Attention" to threatLevelLabel(it)) }
        if (item.summary?.otherConcerns?.isNotEmpty() == true) {
            add("Also noticed" to item.summary.otherConcerns.joinFriendly())
        }
        add("Video" to recordingState.recordingLabel())
    }
    LaunchedEffect(item.id, item.hasBeenReviewed) {
        // Returning from full-screen Media3 playback recreates the connected shell.
        // Give the shell one layout pass before taking focus back from the navigation rail.
        delay(100)
        initialFocusRequester.requestFocus()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text(
                    item.summary?.title ?: "Activity details",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                queuePosition?.let {
                    Text("Review $it", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "${item.severity.displayName()} • ${formatReviewDateTime(item.startTime)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusBadge(
                label = if (item.hasBeenReviewed) "Reviewed" else "New",
                color = if (item.hasBeenReviewed) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    LocalOpahSemanticColors.current.alert
                },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            ReviewThumbnail(
                item = item,
                imageRevision = imageRevision,
                cachedBitmap = cachedBitmap,
                refreshBitmap = refreshBitmap,
                modifier = Modifier
                    .weight(1.65f)
                    .aspectRatio(16f / 9f),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReviewDetailFacts(facts)
                errorMessage?.let { ScreenMessage(it, isError = true) }
                savedClipMessage?.let { ScreenMessage(it, isError = false) }
            }
        }
        ReviewDetailActionBar(
            reviewItem = item,
            recordingState = recordingState,
            initialFocusRequester = initialFocusRequester,
            markingReviewed = markingReviewed,
            savingClip = savingClip,
            savedClip = savedClip,
            allAnglesAvailable = allAnglesAvailable,
            onPrevious = onPrevious,
            onPlay = onPlay,
            onNext = onNext,
            onSetReviewed = onSetReviewed,
            onFindSimilar = onFindSimilar,
            onSaveClip = onSaveClip,
            onSaveAllAngles = { allAnglesVisible = true },
        )
    }
    if (allAnglesVisible) {
        MultiCameraClipDialog(
            currentCamera = item.camera,
            cameras = cameras,
            onDismiss = { allAnglesVisible = false },
            onSave = { selected ->
                allAnglesVisible = false
                onSaveAllAngles(selected)
            },
        )
    }
}

@Composable
private fun ReviewDetailActionBar(
    reviewItem: ReviewItem,
    recordingState: ReviewRecordingState,
    initialFocusRequester: FocusRequester,
    markingReviewed: Boolean,
    savingClip: Boolean,
    savedClip: Boolean,
    allAnglesAvailable: Boolean,
    onPrevious: (() -> Unit)?,
    onPlay: () -> Unit,
    onNext: (() -> Unit)?,
    onSetReviewed: (Boolean) -> Unit,
    onFindSimilar: (() -> Unit)?,
    onSaveClip: () -> Unit,
    onSaveAllAngles: () -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        onPrevious?.let { previous ->
            item {
                SecondaryAction(
                    focusKey = "activity:detail:previous",
                    label = "Previous",
                    onClick = previous,
                )
            }
        }
        item {
            PrimaryAction(
                focusKey = "activity:detail:play",
                label = if (recordingState == ReviewRecordingState.CHECKING) "Checking…" else "Play recording",
                onClick = onPlay,
                enabled = recordingState == ReviewRecordingState.AVAILABLE,
                externalFocusRequester = initialFocusRequester.takeIf {
                    recordingState == ReviewRecordingState.AVAILABLE
                },
            )
        }
        onNext?.let { next ->
            item {
                SecondaryAction(
                    focusKey = "activity:detail:next",
                    label = "Next",
                    onClick = next,
                )
            }
        }
        item {
            SecondaryAction(
                focusKey = "activity:detail:reviewed",
                label = when {
                    markingReviewed -> "Saving…"
                    reviewItem.hasBeenReviewed -> "Mark not reviewed"
                    else -> "Mark reviewed"
                },
                onClick = { onSetReviewed(!reviewItem.hasBeenReviewed) },
                enabled = !markingReviewed,
                externalFocusRequester = initialFocusRequester.takeIf {
                    recordingState != ReviewRecordingState.AVAILABLE
                },
            )
        }
        onFindSimilar?.let { findSimilar ->
            item {
                SecondaryAction(
                    focusKey = "activity:detail:similar",
                    label = "Find similar",
                    onClick = findSimilar,
                )
            }
        }
        item {
            SecondaryAction(
                focusKey = "activity:detail:save",
                label = when {
                    savingClip -> "Saving recording…"
                    savedClip -> "Recording saved"
                    else -> "Keep clip"
                },
                onClick = onSaveClip,
                enabled = recordingState == ReviewRecordingState.AVAILABLE && !savingClip && !savedClip,
            )
        }
        if (allAnglesAvailable) {
            item {
                SecondaryAction(
                    focusKey = "activity:detail:save-all",
                    label = "Save all angles",
                    onClick = onSaveAllAngles,
                    enabled = recordingState == ReviewRecordingState.AVAILABLE && !savingClip,
                )
            }
        }
    }
}

@Composable
private fun MultiCameraClipDialog(
    currentCamera: String,
    cameras: List<Camera>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    var selected by remember(currentCamera, cameras) { mutableStateOf(setOf(currentCamera)) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 540.dp, max = 760.dp)
                .heightIn(max = 720.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    RoundedCornerShape(14.dp),
                )
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Save all angles", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Choose cameras covering the same moment", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(cameras, key = Camera::name) { camera ->
                    val required = camera.name == currentCamera
                    MediaTile(
                        focusKey = "save-all:${camera.name}",
                        accessibilityLabel = "${camera.displayName}, ${if (camera.name in selected) "selected" else "not selected"}",
                        onClick = {
                            if (!required) {
                                selected = if (camera.name in selected) {
                                    selected - camera.name
                                } else {
                                    selected + camera.name
                                }
                            }
                        },
                        selected = camera.name in selected,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(camera.displayName, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            Text(
                                when {
                                    required -> "Current camera"
                                    camera.name in selected -> "Included"
                                    else -> "Not included"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { onSave(selected) }, enabled = selected.isNotEmpty()) {
                    Text("Save ${selected.size} angles")
                }
            }
        }
    }
}

@Composable
private fun ReviewDetailFacts(facts: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        facts.chunked(2).forEach { rowFacts ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                rowFacts.forEach { (label, value) ->
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            value,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (rowFacts.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun ReviewThumbnail(
    item: ReviewItem,
    imageRevision: Long,
    cachedBitmap: () -> Bitmap?,
    refreshBitmap: suspend () -> Bitmap?,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(item.id, item.thumbnailPath, imageRevision) {
        mutableStateOf(cachedBitmap())
    }
    var unavailable by remember(item.id, item.thumbnailPath, imageRevision) {
        mutableStateOf(false)
    }
    LaunchedEffect(item.id, item.thumbnailPath, imageRevision) {
        val loaded = refreshBitmap()
        if (loaded == null) unavailable = true else bitmap = loaded
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { current ->
            val imageBitmap = remember(current) { current.asImageBitmap() }
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } ?: Text(
            if (unavailable) "Preview unavailable" else "Loading preview…",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ReviewFiltersDialog(
    filters: ReviewFilters,
    state: Phase0UiState,
    onDismiss: () -> Unit,
    onApply: (ReviewFilters) -> Unit,
) {
    var draft by remember(filters) { mutableStateOf(filters) }
    var picker by rememberSaveable { mutableStateOf<ReviewPicker?>(null) }
    var returnFocusPicker by rememberSaveable { mutableStateOf<ReviewPicker?>(null) }
    Dialog(
        onDismissRequest = {
            if (picker != null) {
                returnFocusPicker = picker
                picker = null
            } else {
                onDismiss()
            }
        },
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 540.dp, max = 680.dp)
                .heightIn(max = 700.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val activePicker = picker
            if (activePicker == null) {
                Text("Filters", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Narrow the activity shown", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReviewFilterRow(
                        picker = ReviewPicker.CAMERA,
                        label = "Camera",
                        value = cameraLabel(draft.camera, state.snapshot?.cameras.orEmpty()),
                        restoreFocusPicker = returnFocusPicker ?: ReviewPicker.CAMERA,
                        onClick = { picker = ReviewPicker.CAMERA },
                    )
                    ReviewFilterRow(
                        picker = ReviewPicker.LABEL,
                        label = "Object",
                        value = friendlyName(draft.label) ?: "All",
                        restoreFocusPicker = returnFocusPicker,
                        onClick = { picker = ReviewPicker.LABEL },
                    )
                    ReviewFilterRow(
                        picker = ReviewPicker.ZONE,
                        label = "Area",
                        value = friendlyName(draft.zone) ?: "All",
                        restoreFocusPicker = returnFocusPicker,
                        onClick = { picker = ReviewPicker.ZONE },
                    )
                    ReviewFilterRow(
                        picker = ReviewPicker.TIME,
                        label = "Time",
                        value = draft.timeRange.displayName,
                        restoreFocusPicker = returnFocusPicker,
                        onClick = { picker = ReviewPicker.TIME },
                    )
                    ReviewFilterRow(
                        picker = ReviewPicker.REVIEW_STATUS,
                        label = "Review status",
                        value = draft.reviewStatus.displayName,
                        restoreFocusPicker = returnFocusPicker,
                        onClick = { picker = ReviewPicker.REVIEW_STATUS },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                ) {
                    Button(onClick = { draft = draft.clearDetails() }) { Text("Clear") }
                    Button(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = { onApply(draft) }) { Text("Apply") }
                }
            } else {
                val options = pickerOptions(activePicker, state)
                val selectedKey = selectedPickerKey(activePicker, draft)
                val initialFocusKey = "review:picker:${selectedKey.takeIf { selected ->
                    options.any { it.key == selected }
                } ?: options.firstOrNull()?.key.orEmpty()}"
                Text(activePicker.title(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                LazyColumn(
                    modifier = Modifier.heightIn(max = 590.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(options, key = ReviewPickerOption::key) { option ->
                        FocusCard(
                            focusKey = "review:picker:${option.key}",
                            restoreFocusKey = initialFocusKey,
                            onFocusRestored = {},
                            onClick = {
                                draft = draft.withPickerSelection(activePicker, option.key)
                                returnFocusPicker = activePicker
                                picker = null
                            },
                            selected = option.key == selectedKey,
                            accessibilityLabel = option.label,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                option.label,
                                fontWeight = if (option.key == selectedKey) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewFilterRow(
    picker: ReviewPicker,
    label: String,
    value: String,
    restoreFocusPicker: ReviewPicker?,
    onClick: () -> Unit,
) {
    FocusCard(
        focusKey = "review:filter:${picker.name}",
        restoreFocusKey = restoreFocusPicker?.let { "review:filter:${it.name}" },
        onFocusRestored = {},
        onClick = onClick,
        accessibilityLabel = "$label, $value",
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun pickerOptions(picker: ReviewPicker, state: Phase0UiState): List<ReviewPickerOption> = when (picker) {
    ReviewPicker.CAMERA -> listOf(ReviewPickerOption(ALL_FILTER_KEY, "All cameras")) +
        state.snapshot?.cameras.orEmpty().map { ReviewPickerOption(it.name, it.displayName) }
    ReviewPicker.LABEL -> listOf(ReviewPickerOption(ALL_FILTER_KEY, "All objects")) +
        state.review.knownLabels.sorted().map { ReviewPickerOption(it, friendlyName(it).orEmpty()) }
    ReviewPicker.ZONE -> listOf(ReviewPickerOption(ALL_FILTER_KEY, "All areas")) +
        state.review.knownZones.sorted().map { ReviewPickerOption(it, friendlyName(it).orEmpty()) }
    ReviewPicker.TIME -> ReviewTimeRange.entries.map { ReviewPickerOption(it.name, it.displayName) }
    ReviewPicker.REVIEW_STATUS -> ReviewStatusFilter.entries.map { ReviewPickerOption(it.name, it.displayName) }
}

private fun selectedPickerKey(picker: ReviewPicker, filters: ReviewFilters): String = when (picker) {
    ReviewPicker.CAMERA -> filters.camera ?: ALL_FILTER_KEY
    ReviewPicker.LABEL -> filters.label ?: ALL_FILTER_KEY
    ReviewPicker.ZONE -> filters.zone ?: ALL_FILTER_KEY
    ReviewPicker.TIME -> filters.timeRange.name
    ReviewPicker.REVIEW_STATUS -> filters.reviewStatus.name
}

private fun ReviewFilters.withPickerSelection(picker: ReviewPicker, key: String): ReviewFilters = when (picker) {
    ReviewPicker.CAMERA -> copy(camera = key.takeUnless { it == ALL_FILTER_KEY })
    ReviewPicker.LABEL -> copy(label = key.takeUnless { it == ALL_FILTER_KEY })
    ReviewPicker.ZONE -> copy(zone = key.takeUnless { it == ALL_FILTER_KEY })
    ReviewPicker.TIME -> copy(timeRange = ReviewTimeRange.valueOf(key))
    ReviewPicker.REVIEW_STATUS -> copy(reviewStatus = ReviewStatusFilter.valueOf(key))
}

private fun ReviewPicker.title(): String = when (this) {
    ReviewPicker.CAMERA -> "Choose camera"
    ReviewPicker.LABEL -> "Choose object"
    ReviewPicker.ZONE -> "Choose area"
    ReviewPicker.TIME -> "Choose recent period"
    ReviewPicker.REVIEW_STATUS -> "Choose review status"
}

private fun ReviewSeverity.displayName(): String = when (this) {
    ReviewSeverity.ALERT -> "Alert"
    ReviewSeverity.DETECTION -> "Activity"
    ReviewSeverity.SIGNIFICANT_MOTION -> "Motion"
    ReviewSeverity.UNKNOWN -> "Activity"
}

private fun threatLevelLabel(level: Int): String = when (level) {
    0 -> "Nothing concerning noted"
    1 -> "Worth a closer look"
    2 -> "Needs attention"
    else -> "Review recommended"
}

private fun ReviewRecordingState.recordingLabel(): String = when (this) {
    ReviewRecordingState.IDLE -> "Not checked"
    ReviewRecordingState.CHECKING -> "Checking saved video…"
    ReviewRecordingState.AVAILABLE -> "Available"
    ReviewRecordingState.UNAVAILABLE -> "No longer available"
    ReviewRecordingState.UNKNOWN -> "Could not confirm"
}

private fun reviewAccessibilityLabel(item: ReviewItem): String = buildString {
    append(item.severity.displayName().removeSuffix("s"))
    append(" at ")
    append(friendlyName(item.camera))
    item.objects.firstOrNull()?.let { append(", ").append(friendlyName(it)) }
    append(", ").append(formatReviewDateTime(item.startTime))
    if (item.hasBeenReviewed) append(", reviewed")
}

private fun cameraLabel(camera: String?, cameras: List<Camera>): String =
    camera?.let { selected -> cameras.firstOrNull { it.name == selected }?.displayName ?: friendlyName(selected) }
        ?: "All"

private fun ReviewFilters.summary(cameras: List<Camera>): String = buildList {
    add(if (severity == ReviewSeverity.ALERT) "Alerts" else "Everything")
    camera?.let { add(cameraLabel(it, cameras)) }
    label?.let { friendlyName(it)?.let(::add) }
    zone?.let { friendlyName(it)?.let(::add) }
    add(timeRange.displayName)
    if (reviewStatus != ReviewStatusFilter.ALL) add(reviewStatus.displayName)
}.joinToString(" • ")

internal fun ReviewFilters.countSummary(counts: app.opah.tv.data.model.ReviewCounts): String {
    val range = timeRange.displayName.lowercase()
    return if (severity == ReviewSeverity.ALERT) {
        val count = counts.unreviewedAlerts
        "$count unreviewed ${if (count == 1) "alert" else "alerts"} from the last $range"
    } else {
        val count = counts.unreviewedTotal
        "$count unreviewed ${if (count == 1) "item" else "items"} from the last $range, including alerts"
    }
}

private fun ReviewFilters.emptyMessage(): String {
    val category = if (severity == ReviewSeverity.ALERT) "alerts" else "activity"
    return if (activeDetailCount() == 0) {
        "No $category in the last 24 hours"
    } else {
        "No $category matches these filters"
    }
}

private fun formatReviewDateTime(epochSeconds: Double): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date((epochSeconds * 1000).toLong()))

private fun reviewDuration(item: ReviewItem): String {
    val end = item.endTime ?: return "In progress"
    val seconds = (end - item.startTime).coerceAtLeast(0.0).roundToInt()
    val minutes = seconds / 60
    val remainder = seconds % 60
    return if (minutes > 0) "${minutes}m ${remainder}s" else "${remainder}s"
}

private fun List<String>.joinFriendly(): String =
    distinct().joinToString(", ") { friendlyName(it).orEmpty() }.ifBlank { "None reported" }

private fun friendlyName(value: String?): String? = value
    ?.replace('_', ' ')
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.replaceFirstChar(Char::uppercase)

private const val ALL_FILTER_KEY = "__all__"
