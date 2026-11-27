package app.opah.tv.ui.views

import app.opah.tv.R
import app.opah.tv.ui.SettingsPage

internal enum class NativeDestination(
    val label: String,
    val iconRes: Int,
) {
    HOME("Cameras", R.drawable.ic_videocam),
    ACTIVITY("Activity", R.drawable.ic_review),
    CLIPS("Clips", R.drawable.ic_saved),
    SETTINGS("Settings", R.drawable.ic_settings),
}

internal enum class NativeActivityPage { NEW, REVIEWED, HISTORY, SEARCH, MOTION }

internal enum class NativeClipsPage { RECORDINGS, INCIDENTS }

internal enum class NativeServerPage { CONNECTION, PERFORMANCE, STORAGE }

internal enum class NativeConnectionPage(val label: String) {
    FRIGATE("Frigate"), HOME_ASSISTANT("Home Assistant"), SETUP("Setup & checks"), NOTIFICATIONS("Notifications"),
}

internal data class NativeRoute(
    val destination: NativeDestination = NativeDestination.HOME,
    val settingsPage: SettingsPage = SettingsPage.MAIN,
) {
    init {
        require(destination == NativeDestination.SETTINGS || settingsPage == SettingsPage.MAIN)
    }

    val focusMemoryKey: String = when {
        destination != NativeDestination.SETTINGS -> destination.name
        else -> "SETTINGS:${settingsPage.name}"
    }
}

internal val NATIVE_SETTINGS_PAGE_ORDER = listOf(
    SettingsPage.CONNECTION,
    SettingsPage.TV_ALERTS,
    SettingsPage.PLAYBACK,
    SettingsPage.PRIVACY,
    SettingsPage.APPEARANCE,
    SettingsPage.ABOUT,
)

internal val SettingsPage.nativeCategory: SettingsPage get() = when (this) {
    SettingsPage.STARTUP, SettingsPage.PLAYBACK_TROUBLESHOOT, SettingsPage.PLAYBACK_TEST,
    SettingsPage.CAMERA_DIAGNOSTICS, SettingsPage.ADVANCED -> SettingsPage.PLAYBACK
    SettingsPage.PRIVACY_PIN, SettingsPage.PRIVACY_AREAS, SettingsPage.PRIVACY_CAMERAS -> SettingsPage.PRIVACY
    SettingsPage.APPEARANCE_CUSTOM -> SettingsPage.APPEARANCE
    SettingsPage.UPDATE, SettingsPage.ABOUT_PROJECT -> SettingsPage.ABOUT
    SettingsPage.CONNECTION_FRIGATE, SettingsPage.CONNECTION_FRIGATE_ADVANCED, SettingsPage.CONNECTION_HOME_ASSISTANT,
    SettingsPage.CONNECTION_SETUP, SettingsPage.CONNECTION_CHECK, SettingsPage.SYSTEM -> SettingsPage.CONNECTION
    SettingsPage.NOTIFICATION_FRIGATE, SettingsPage.NOTIFICATION_FRIGATE_RULES,
    SettingsPage.NOTIFICATION_HOME_ASSISTANT, SettingsPage.NOTIFICATION_POPUPS -> SettingsPage.TV_ALERTS
    else -> this
}

internal enum class NativeRowKind {
    ACTION,
    TOGGLE,
    CHOICE,
    ADJUSTMENT,
    READING,
    INFORMATION,
    VISUAL,
    DASHBOARD,
    THEME_PREVIEW,
    SECTION,
}

internal data class NativeMeterSegment(
    val amount: Double,
    val color: Int,
)

internal data class NativeDashboardMetric(
    val label: String,
    val value: String,
    val color: Int,
)

internal enum class NativeThumbnailKind {
    CAMERA,
    REVIEW,
    CLIP,
    SEARCH,
}

internal data class NativeThumbnailRequest(
    val kind: NativeThumbnailKind,
    val sourceId: String,
) {
    val cacheKey: String = "${kind.name}:$sourceId"
}

internal data class NativeAdjustmentRange(
    val minimum: Float,
    val maximum: Float,
    val current: Float,
    val wraps: Boolean = false,
) {
    init {
        require(minimum.isFinite() && maximum.isFinite() && current.isFinite())
        require(minimum <= maximum && current in minimum..maximum)
    }

    fun canAdjust(direction: Int): Boolean = wraps || if (direction < 0) current > minimum else current < maximum
}

internal data class NativeRowModel(
    val key: String,
    val title: String,
    val description: String = "",
    val value: String = "",
    val kind: NativeRowKind = NativeRowKind.ACTION,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val thumbnail: NativeThumbnailRequest? = null,
    val indicatorColor: Int? = null,
    val meterSegments: List<NativeMeterSegment> = emptyList(),
    val dashboardMetrics: List<NativeDashboardMetric> = emptyList(),
    val previewAccent: Int? = null,
    val previewBackground: Int? = null,
    val secondaryActionKey: String? = null,
    val secondaryActionLabel: String = "",
    val adjustmentRange: NativeAdjustmentRange? = null,
) {
    val focusable: Boolean get() = enabled && kind in FOCUSABLE_ROW_KINDS
    val actionable: Boolean get() = enabled && kind in ACTIONABLE_ROW_KINDS

    private companion object {
        val FOCUSABLE_ROW_KINDS = setOf(
            NativeRowKind.ACTION,
            NativeRowKind.TOGGLE,
            NativeRowKind.CHOICE,
            NativeRowKind.ADJUSTMENT,
            NativeRowKind.READING,
        )
        val ACTIONABLE_ROW_KINDS = FOCUSABLE_ROW_KINDS
    }
}

internal fun adjacentFocusableRowPosition(
    rows: List<NativeRowModel>,
    currentPosition: Int,
    forward: Boolean,
): Int? {
    if (currentPosition !in rows.indices) return null
    val positions = if (forward) {
        (currentPosition + 1)..rows.lastIndex
    } else {
        (currentPosition - 1) downTo 0
    }
    return positions.firstOrNull { rows[it].focusable }
}

internal enum class NativeBackAction {
    CLOSE_NAVIGATION,
    CLOSE_DETAIL,
    CLOSE_CONTENT_OVERLAY,
    OPEN_NAVIGATION,
    EXIT_APP,
}

internal fun nativeBackAction(
    navigationOpen: Boolean,
    inPlayback: Boolean,
    inContentOverlay: Boolean,
    route: NativeRoute,
): NativeBackAction = when {
    navigationOpen -> NativeBackAction.CLOSE_NAVIGATION
    inPlayback -> NativeBackAction.CLOSE_CONTENT_OVERLAY
    inContentOverlay -> NativeBackAction.CLOSE_CONTENT_OVERLAY
    route.destination == NativeDestination.SETTINGS && route.settingsPage != SettingsPage.MAIN ->
        NativeBackAction.CLOSE_DETAIL
    route.destination != NativeDestination.HOME -> NativeBackAction.OPEN_NAVIGATION
    else -> NativeBackAction.OPEN_NAVIGATION
}

internal fun shouldKeepFocusInContent(
    direction: Int,
    focusedInContent: Boolean,
    proposedInNavigation: Boolean,
): Boolean = focusedInContent && proposedInNavigation && (
    direction == android.view.View.FOCUS_UP || direction == android.view.View.FOCUS_DOWN
    )

internal fun stableNativeItemId(key: String): Long {
    var hash = -0x340d631b7bdddcdbL
    key.forEach { character ->
        hash = hash xor character.code.toLong()
        hash *= 0x100000001b3L
    }
    return hash
}
