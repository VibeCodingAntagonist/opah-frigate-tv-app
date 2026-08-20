package app.opah.tv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.ReviewSeverity
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private enum class ReviewPicker { CAMERA, LABEL, ZONE, TIME }

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
    onSeverity: (ReviewSeverity) -> Unit,
    onCamera: (String?) -> Unit,
    onLabel: (String?) -> Unit,
    onZone: (String?) -> Unit,
    onTimeRange: (ReviewTimeRange) -> Unit,
    onResetFilters: () -> Unit,
    onSelectItem: (ReviewItem, String) -> Unit,
    onCloseItem: () -> Unit,
    onPlayItem: (ReviewItem) -> Unit,
    onMarkReviewed: (ReviewItem) -> Unit,
    cachedBitmap: (ReviewItem) -> Bitmap?,
    refreshBitmap: suspend (ReviewItem, Int) -> Bitmap?,
) {
    val review = state.review
    val selected = review.selectedItemId?.let { selectedId ->
        review.items.firstOrNull { it.id == selectedId }
    }
    var picker by rememberSaveable { mutableStateOf<ReviewPicker?>(null) }

    LaunchedEffect(review.loadedOnce) {
        if (!review.loadedOnce && !review.loading) onLoad()
    }

    if (selected != null) {
        ReviewDetail(
            item = selected,
            recordingState = review.recordingState,
            errorMessage = review.detailErrorMessage,
            onBack = onCloseItem,
            onPlay = { onPlayItem(selected) },
            onMarkReviewed = { onMarkReviewed(selected) },
            markingReviewed = review.markingReviewedItemId == selected.id,
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
                    Text("Review", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Frigate Alerts and Detections, designed for the television remote.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (review.loading) {
                    Text("Refreshing…", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ReviewCategoryButton(
                    label = "Alerts",
                    selected = review.filters.severity == ReviewSeverity.ALERT,
                    onClick = { onSeverity(ReviewSeverity.ALERT) },
                )
                ReviewCategoryButton(
                    label = "Detections",
                    selected = review.filters.severity == ReviewSeverity.DETECTION,
                    onClick = { onSeverity(ReviewSeverity.DETECTION) },
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { picker = ReviewPicker.CAMERA },
                    modifier = Modifier.weight(1.1f),
                ) {
                    Text(
                        "Camera: ${cameraLabel(review.filters.camera, state.snapshot?.cameras.orEmpty())}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(
                    onClick = { picker = ReviewPicker.LABEL },
                    modifier = Modifier.weight(0.9f),
                ) {
                    Text(
                        "Label: ${friendlyName(review.filters.label) ?: "All"}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(
                    onClick = { picker = ReviewPicker.ZONE },
                    modifier = Modifier.weight(1.1f),
                ) {
                    Text(
                        "Zone: ${friendlyName(review.filters.zone) ?: "All"}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(
                    onClick = { picker = ReviewPicker.TIME },
                    modifier = Modifier.weight(1.1f),
                ) {
                    Text(
                        "Time: ${review.filters.timeRange.displayName}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(
                    onClick = onResetFilters,
                    modifier = Modifier.weight(0.6f),
                ) { Text("Reset", maxLines = 1) }
            }
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
                Text("Loading Review activity…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            review.items.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No ${review.filters.severity.displayName().lowercase()} match these filters.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> ReviewGrid(
                items = review.items,
                restoreFocusKey = restoreFocusKey,
                onFocusRestored = onFocusRestored,
                onSelectItem = onSelectItem,
                cachedBitmap = cachedBitmap,
                refreshBitmap = refreshBitmap,
            )
        }
    }

    picker?.let { activePicker ->
        val options = pickerOptions(activePicker, state)
        ReviewPickerDialog(
            title = activePicker.title(),
            options = options,
            selectedKey = selectedPickerKey(activePicker, review.filters),
            onDismiss = { picker = null },
            onSelected = { key ->
                when (activePicker) {
                    ReviewPicker.CAMERA -> onCamera(key.takeUnless { it == ALL_FILTER_KEY })
                    ReviewPicker.LABEL -> onLabel(key.takeUnless { it == ALL_FILTER_KEY })
                    ReviewPicker.ZONE -> onZone(key.takeUnless { it == ALL_FILTER_KEY })
                    ReviewPicker.TIME -> onTimeRange(ReviewTimeRange.valueOf(key))
                }
                picker = null
            },
        )
    }
}

@Composable
private fun ReviewCategoryButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FocusCard(
        focusKey = "review:category:${label.lowercase()}",
        restoreFocusKey = null,
        onFocusRestored = {},
        onClick = onClick,
        selected = selected,
        accessibilityLabel = "$label Review category",
    ) {
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun ReviewGrid(
    items: List<ReviewItem>,
    restoreFocusKey: String?,
    onFocusRestored: () -> Unit,
    onSelectItem: (ReviewItem, String) -> Unit,
    cachedBitmap: (ReviewItem) -> Bitmap?,
    refreshBitmap: suspend (ReviewItem, Int) -> Bitmap?,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(items, key = ReviewItem::id) { item ->
            val focusKey = "review:item:${item.id}"
            FocusCard(
                focusKey = focusKey,
                restoreFocusKey = restoreFocusKey,
                onFocusRestored = onFocusRestored,
                onClick = { onSelectItem(item, focusKey) },
                accessibilityLabel = reviewAccessibilityLabel(item),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f),
                    ) {
                        ReviewThumbnail(
                            item = item,
                            cachedBitmap = { cachedBitmap(item) },
                            refreshBitmap = { refreshBitmap(item, 300) },
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (item.hasBeenReviewed) {
                            Text(
                                text = "Reviewed",
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(10.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
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
                                item.objects.firstOrNull()?.let(::friendlyName) ?: item.severity.displayName(),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                formatReviewDateTime(item.startTime),
                                color = MaterialTheme.colorScheme.secondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            friendlyName(item.camera).orEmpty(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val detail = (item.objects.drop(1) + item.zones).distinct()
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
    }
}

@Composable
private fun ReviewDetail(
    item: ReviewItem,
    recordingState: ReviewRecordingState,
    errorMessage: String?,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onMarkReviewed: () -> Unit,
    markingReviewed: Boolean,
    cachedBitmap: () -> Bitmap?,
    refreshBitmap: suspend () -> Bitmap?,
) {
    val initialFocusRequester = remember(item.id) { FocusRequester() }
    val playFocusRequester = remember(item.id) { FocusRequester() }
    val markReviewedFocusRequester = remember(item.id) { FocusRequester() }
    LaunchedEffect(item.id, item.hasBeenReviewed) {
        // Returning from full-screen Media3 playback recreates the connected shell.
        // A completed Review action also removes its button. Give the shell one
        // layout pass before taking focus back from the navigation rail.
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
                Text("Review details", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${item.severity.displayName()} • ${formatReviewDateTime(item.startTime)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (item.hasBeenReviewed) "Reviewed" else "Not reviewed",
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.Bold,
            )
        }
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            ReviewThumbnail(
                item = item,
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
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ReviewDetailLine("Camera", friendlyName(item.camera).orEmpty())
                ReviewDetailLine("Started", formatReviewDateTime(item.startTime))
                ReviewDetailLine("Duration", reviewDuration(item))
                ReviewDetailLine("Labels", item.objects.joinFriendly())
                ReviewDetailLine("Zones", item.zones.joinFriendly())
                ReviewDetailLine("Recording", recordingState.recordingLabel())
                errorMessage?.let { ScreenMessage(it, isError = true) }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onBack,
                        modifier = Modifier
                            .focusRequester(initialFocusRequester)
                            .focusProperties {
                                right = playFocusRequester
                                if (!item.hasBeenReviewed) down = markReviewedFocusRequester
                            },
                    ) { Text("Back to Review") }
                    Button(
                        onClick = onPlay,
                        enabled = recordingState == ReviewRecordingState.AVAILABLE,
                        modifier = Modifier
                            .focusRequester(playFocusRequester)
                            .focusProperties {
                                left = initialFocusRequester
                                if (!item.hasBeenReviewed) down = markReviewedFocusRequester
                            },
                    ) {
                        Text(if (recordingState == ReviewRecordingState.CHECKING) "Checking…" else "Play recording")
                    }
                }
                if (!item.hasBeenReviewed) {
                    Button(
                        onClick = onMarkReviewed,
                        enabled = !markingReviewed,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(markReviewedFocusRequester)
                            .focusProperties { up = initialFocusRequester },
                    ) {
                        Text(if (markingReviewed) "Saving…" else "Mark as reviewed")
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewDetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun ReviewThumbnail(
    item: ReviewItem,
    cachedBitmap: () -> Bitmap?,
    refreshBitmap: suspend () -> Bitmap?,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(item.id, item.thumbnailPath) { mutableStateOf(cachedBitmap()) }
    var unavailable by remember(item.id, item.thumbnailPath) { mutableStateOf(item.thumbnailPath == null) }
    LaunchedEffect(item.id, item.thumbnailPath) {
        if (item.thumbnailPath != null) {
            val loaded = refreshBitmap()
            if (loaded == null) unavailable = true else bitmap = loaded
        }
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
private fun ReviewPickerDialog(
    title: String,
    options: List<ReviewPickerOption>,
    selectedKey: String,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        val initialFocusKey = "review:picker:${selectedKey.takeIf { selected -> options.any { it.key == selected } }
            ?: options.firstOrNull()?.key.orEmpty()}"
        Column(
            modifier = Modifier
                .widthIn(min = 460.dp, max = 620.dp)
                .heightIn(max = 720.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(options, key = ReviewPickerOption::key) { option ->
                    FocusCard(
                        focusKey = "review:picker:${option.key}",
                        restoreFocusKey = initialFocusKey,
                        onFocusRestored = {},
                        onClick = { onSelected(option.key) },
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

private fun pickerOptions(picker: ReviewPicker, state: Phase0UiState): List<ReviewPickerOption> = when (picker) {
    ReviewPicker.CAMERA -> listOf(ReviewPickerOption(ALL_FILTER_KEY, "All cameras")) +
        state.snapshot?.cameras.orEmpty().map { ReviewPickerOption(it.name, it.displayName) }
    ReviewPicker.LABEL -> listOf(ReviewPickerOption(ALL_FILTER_KEY, "All labels")) +
        state.review.knownLabels.sorted().map { ReviewPickerOption(it, friendlyName(it).orEmpty()) }
    ReviewPicker.ZONE -> listOf(ReviewPickerOption(ALL_FILTER_KEY, "All zones")) +
        state.review.knownZones.sorted().map { ReviewPickerOption(it, friendlyName(it).orEmpty()) }
    ReviewPicker.TIME -> ReviewTimeRange.entries.map { ReviewPickerOption(it.name, it.displayName) }
}

private fun selectedPickerKey(picker: ReviewPicker, filters: ReviewFilters): String = when (picker) {
    ReviewPicker.CAMERA -> filters.camera ?: ALL_FILTER_KEY
    ReviewPicker.LABEL -> filters.label ?: ALL_FILTER_KEY
    ReviewPicker.ZONE -> filters.zone ?: ALL_FILTER_KEY
    ReviewPicker.TIME -> filters.timeRange.name
}

private fun ReviewPicker.title(): String = when (this) {
    ReviewPicker.CAMERA -> "Choose camera"
    ReviewPicker.LABEL -> "Choose label"
    ReviewPicker.ZONE -> "Choose zone"
    ReviewPicker.TIME -> "Choose recent period"
}

private fun ReviewSeverity.displayName(): String = when (this) {
    ReviewSeverity.ALERT -> "Alerts"
    ReviewSeverity.DETECTION -> "Detections"
    ReviewSeverity.UNKNOWN -> "Activity"
}

private fun ReviewRecordingState.recordingLabel(): String = when (this) {
    ReviewRecordingState.IDLE -> "Not checked"
    ReviewRecordingState.CHECKING -> "Checking retained footage…"
    ReviewRecordingState.AVAILABLE -> "Available"
    ReviewRecordingState.UNAVAILABLE -> "No longer retained"
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
