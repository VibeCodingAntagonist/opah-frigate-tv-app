package app.opah.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.opah.tv.PictureInPictureRequest
import app.opah.tv.R
import app.opah.tv.data.model.Camera
import app.opah.tv.data.model.AppSettings
import app.opah.tv.data.model.DiscoverySnapshot
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.StartupTarget
import app.opah.tv.data.model.StartupTargetKind
import app.opah.tv.playback.PlaybackKind
import app.opah.tv.privacy.PinScope

private class HomeFocusMemory(initialKey: String? = null) {
    var key: String? = initialKey

    companion object {
        val Saver = Saver<HomeFocusMemory, String>(
            save = { memory -> memory.key.orEmpty() },
            restore = { saved -> HomeFocusMemory(saved.takeIf(String::isNotEmpty)) },
        )
    }
}

internal enum class AppDestination(
    val label: String,
    val iconRes: Int,
    val isRoot: Boolean = true,
) {
    HOME("Home", R.drawable.ic_home),
    CAMERAS("Cameras", R.drawable.ic_videocam, isRoot = false),
    ACTIVITY("Activity", R.drawable.ic_review),
    SAVED("Clips", R.drawable.ic_saved),
    SETTINGS("Settings", R.drawable.ic_settings),
}

internal fun AppDestination.rootDestination(): AppDestination = when (this) {
    AppDestination.CAMERAS -> AppDestination.HOME
    else -> this
}

internal fun navigationAccessibilityLabel(
    destination: AppDestination,
    updateAvailable: Boolean,
    unreviewedAlertCount: Int = 0,
    activityCountLoading: Boolean = false,
): String = when {
    destination == AppDestination.SETTINGS && updateAvailable -> "Settings, update available"
    destination == AppDestination.ACTIVITY && activityCountLoading ->
        "Activity, checking for unreviewed alerts"
    destination == AppDestination.ACTIVITY && unreviewedAlertCount > 0 ->
        "Activity, $unreviewedAlertCount unreviewed alerts in the last 24 hours"
    else -> destination.label
}

internal fun compactAlertCount(count: Int): String? = when {
    count <= 0 -> null
    count > 99 -> "99+"
    else -> count.toString()
}

internal enum class RootSurface { PLAYBACK, MONITOR, CAMERA_GROUP, CONNECTED, LOADING, RECOVERY, SETUP }

internal data class CameraNeighbors(
    val previous: Camera?,
    val next: Camera?,
)

internal fun cameraNeighbors(cameras: List<Camera>, currentCameraName: String?): CameraNeighbors {
    val currentIndex = cameras.indexOfFirst { it.name == currentCameraName }
    if (currentIndex < 0 || cameras.size < 2) return CameraNeighbors(null, null)
    return CameraNeighbors(
        previous = cameras.wrappedAt(currentIndex - 1),
        next = cameras.wrappedAt(currentIndex + 1),
    )
}

internal fun resolveConfiguredStartupTarget(
    settings: AppSettings,
    snapshot: DiscoverySnapshot,
): StartupTarget {
    val requested = if (settings.startupTarget.kind == StartupTargetKind.LAST_VIEWED) {
        settings.lastViewedTarget ?: StartupTarget()
    } else {
        settings.startupTarget
    }
    return when (requested.kind) {
        StartupTargetKind.HOME -> StartupTarget()
        StartupTargetKind.LAST_VIEWED -> StartupTarget()
        StartupTargetKind.CAMERA -> requested.takeIf { target ->
            snapshot.cameras.any { it.name == target.value }
        } ?: StartupTarget()
        StartupTargetKind.SAVED_VIEW -> requested.takeIf { target ->
            settings.savedCameraViews.any { view ->
                view.id == target.value && view.cameraNames.all { cameraName ->
                    snapshot.cameras.any { it.name == cameraName }
                }
            }
        } ?: StartupTarget()
        StartupTargetKind.CAMERA_GROUP -> requested.takeIf { target ->
            snapshot.cameraGroups.any { group ->
                group.name == target.value && group.cameraNames.all { cameraName ->
                    snapshot.cameras.any { it.name == cameraName }
                }
            }
        } ?: StartupTarget()
        StartupTargetKind.BIRDSEYE -> requested.takeIf { snapshot.birdseye.playable } ?: StartupTarget()
    }
}

internal fun rootSurface(
    hasPlayback: Boolean,
    hasConnectedContent: Boolean,
    loading: Boolean,
    connectionWorkInProgress: Boolean = false,
    savedSessionRecoveryAvailable: Boolean = false,
    hasCameraGroupView: Boolean = false,
    hasMonitorMode: Boolean = false,
): RootSurface = when {
    hasPlayback -> RootSurface.PLAYBACK
    hasMonitorMode -> RootSurface.MONITOR
    hasCameraGroupView -> RootSurface.CAMERA_GROUP
    hasConnectedContent -> RootSurface.CONNECTED
    connectionWorkInProgress -> RootSurface.SETUP
    loading -> RootSurface.LOADING
    savedSessionRecoveryAvailable -> RootSurface.RECOVERY
    else -> RootSurface.SETUP
}

@Composable
fun OpahApp(
    viewModel: Phase0ViewModel,
    pictureInPictureAvailable: Boolean = false,
    pictureInPictureActive: Boolean = false,
    onEnterPictureInPicture: (PictureInPictureRequest) -> Boolean = { false },
    onFullyDrawn: () -> Unit = {},
    onExitRequested: () -> Unit = {},
    onInstallUpdate: (String) -> Unit = {},
    onShareSnapshot: (Bitmap, String) -> Boolean = { _, _ -> false },
    onShareClip: (app.opah.tv.data.model.RecordingExport) -> Unit = {},
    onEnableTvAlerts: () -> Unit = {},
    onTestTvAlert: (Boolean) -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    onOpenOverlaySettings: () -> Unit = {},
    initialDestinationName: String? = null,
    initialSettingsPageName: String? = null,
    initialInformationTabName: String? = null,
    initialActivityPageName: String? = null,
    initialClipsPageName: String? = null,
) {
    val unprojectedState by viewModel.state.collectAsStateWithLifecycle()
    val state = unprojectedState.projectForPrivacy()
    OpahTheme(
        state.settings.appearanceMode,
        state.settings.customThemeColors,
        state.settings.reducedMotion,
        state.settings.highContrast,
    ) {
        val normalizedInitialDestination = when (initialDestinationName) {
            "REVIEW" -> AppDestination.ACTIVITY.name
            "INFORMATION", "ABOUT" -> AppDestination.SETTINGS.name
            else -> initialDestinationName ?: AppDestination.HOME.name
        }
        val normalizedInitialSettingsPage = when {
            initialDestinationName == "INFORMATION" -> SettingsPage.SYSTEM.name
            initialDestinationName == "ABOUT" -> SettingsPage.ABOUT.name
            initialSettingsPageName == "DIAGNOSTICS" -> SettingsPage.ADVANCED.name
            else -> initialSettingsPageName ?: SettingsPage.MAIN.name
        }
        var destinationName by rememberSaveable(normalizedInitialDestination) {
            mutableStateOf(normalizedInitialDestination)
        }
        var returnDestinationName by rememberSaveable { mutableStateOf(AppDestination.HOME.name) }
        var restoreFocusKey by rememberSaveable { mutableStateOf<String?>(null) }
        var playbackReturnFocusKey by rememberSaveable { mutableStateOf<String?>(null) }
        var cameraGroupOriginFocusKey by rememberSaveable { mutableStateOf<String?>(null) }
        var cameraGroupOriginDestinationName by rememberSaveable {
            mutableStateOf(AppDestination.HOME.name)
        }
        val normalizedInitialActivityPage = initialActivityPageName
            ?.let { runCatching { ActivityPage.valueOf(it).name }.getOrNull() }
            ?: ActivityPage.RECENT.name
        var activityPageName by rememberSaveable(normalizedInitialActivityPage) {
            mutableStateOf(normalizedInitialActivityPage)
        }
        var activityBackDestinationName by rememberSaveable { mutableStateOf(AppDestination.HOME.name) }
        var activityBackFocusKey by rememberSaveable { mutableStateOf<String?>(null) }
        var settingsPageName by rememberSaveable(normalizedInitialSettingsPage) {
            mutableStateOf(normalizedInitialSettingsPage)
        }
        var settingsReturnPageName by rememberSaveable { mutableStateOf<String?>(null) }
        val lastHomeFocus = rememberSaveable(saver = HomeFocusMemory.Saver) { HomeFocusMemory() }
        var configuredStartupApplied by rememberSaveable { mutableStateOf(false) }
        val homeCameraFocusRequester = remember { FocusRequester() }
        val settingsMainFocusRequester = remember { FocusRequester() }
        val settingsDetailFocusRequester = remember { FocusRequester() }
        val destination = runCatching { AppDestination.valueOf(destinationName) }
            .getOrDefault(AppDestination.HOME)
        val settingsPage = runCatching { SettingsPage.valueOf(settingsPageName) }
            .getOrDefault(SettingsPage.MAIN)
        val activityPage = runCatching { ActivityPage.valueOf(activityPageName) }
            .getOrDefault(ActivityPage.RECENT)
        val playback = state.playback
        val surface = rootSurface(
            hasPlayback = playback != null,
            hasConnectedContent = state.activeProfile != null && state.snapshot != null,
            loading = state.loading,
            connectionWorkInProgress = state.connectionWorkInProgress,
            savedSessionRecoveryAvailable = state.savedSessionRecoveryAvailable,
            hasCameraGroupView = state.cameraGroupView != null,
            hasMonitorMode = state.monitorMode != null,
        )

        LaunchedEffect(state.tvAlertNavigation?.requestId) {
            val request = state.tvAlertNavigation ?: return@LaunchedEffect
            when (request.destination) {
                TvAlertNavigationDestination.ACTIVITY -> {
                    destinationName = AppDestination.ACTIVITY.name
                    activityPageName = ActivityPage.RECENT.name
                    activityBackDestinationName = AppDestination.HOME.name
                    activityBackFocusKey = null
                }
                TvAlertNavigationDestination.TV_ALERT_SETTINGS -> {
                    destinationName = AppDestination.SETTINGS.name
                    settingsPageName = SettingsPage.TV_ALERTS.name
                    settingsReturnPageName = SettingsPage.TV_ALERTS.name
                }
            }
            viewModel.consumeTvAlertNavigation(request.requestId)
        }

        LaunchedEffect(
            surface,
            state.settingsLoaded,
            state.settings.startupTarget,
            state.settings.lastViewedTarget,
            state.snapshot,
            initialDestinationName,
        ) {
            if (configuredStartupApplied) return@LaunchedEffect
            if (initialDestinationName != null) {
                configuredStartupApplied = true
                return@LaunchedEffect
            }
            if (surface == RootSurface.PLAYBACK ||
                surface == RootSurface.CAMERA_GROUP ||
                surface == RootSurface.MONITOR
            ) {
                // A deep link or camera shortcut already won launch precedence.
                configuredStartupApplied = true
                return@LaunchedEffect
            }
            val startupSnapshot = state.snapshot
            if (surface != RootSurface.CONNECTED || !state.settingsLoaded || startupSnapshot == null) {
                return@LaunchedEffect
            }
            val target = resolveConfiguredStartupTarget(state.settings, startupSnapshot)
            configuredStartupApplied = true
            returnDestinationName = AppDestination.HOME.name
            when (target.kind) {
                StartupTargetKind.HOME,
                StartupTargetKind.LAST_VIEWED,
                -> destinationName = AppDestination.HOME.name
                StartupTargetKind.CAMERA -> startupSnapshot.cameras
                    .firstOrNull { it.name == target.value }
                    ?.let(viewModel::playAutomatic)
                StartupTargetKind.SAVED_VIEW -> state.settings.savedCameraViews
                    .firstOrNull { it.id == target.value }
                    ?.let { view -> viewModel.openCameraGroup(view.name, view.cameraNames) }
                StartupTargetKind.CAMERA_GROUP -> startupSnapshot.cameraGroups
                    .firstOrNull { it.name == target.value }
                    ?.let { group -> viewModel.openCameraGroup(group.displayName, group.cameraNames) }
                StartupTargetKind.BIRDSEYE -> viewModel.playBirdseye()
            }
        }

        LaunchedEffect(surface) {
            if (
                surface == RootSurface.CONNECTED ||
                surface == RootSurface.CAMERA_GROUP ||
                surface == RootSurface.MONITOR ||
                surface == RootSurface.SETUP
            ) {
                withFrameNanos { }
                onFullyDrawn()
            }
        }

        LaunchedEffect(surface, destination, settingsPage) {
            if (surface == RootSurface.CONNECTED) {
                playbackReturnFocusKey?.let { focusKey ->
                    restoreFocusKey = focusKey
                    playbackReturnFocusKey = null
                }
            }
            if (surface != RootSurface.CONNECTED || destination != AppDestination.SETTINGS) return@LaunchedEffect
            if (settingsPage != SettingsPage.MAIN) {
                for (attempt in 0 until SETTINGS_DETAIL_FOCUS_ATTEMPTS) {
                    withFrameNanos { }
                    if (settingsDetailFocusRequester.requestFocus()) break
                }
            }
        }

        LaunchedEffect(state.liveActions.message, state.liveActions.errorMessage) {
            if (state.liveActions.message != null || state.liveActions.errorMessage != null) {
                kotlinx.coroutines.delay(6_000)
                viewModel.clearLiveActionMessage()
            }
        }

        when (surface) {
            RootSurface.PLAYBACK -> {
                val playbackRequest = requireNotNull(playback)
                val cameras = state.snapshot?.cameras.orEmpty()
                val (previous, next) = cameraNeighbors(cameras, state.activeCameraName)
                val activityItems = (
                    listOfNotNull(state.review.playbackItem) +
                        state.review.items +
                        state.snapshot?.recentReviewItems.orEmpty() +
                        state.briefing.summary?.entries.orEmpty().map { it.item }
                    ).distinctBy(app.opah.tv.data.model.ReviewItem::id)
                val activityItem = playbackRequest.activityItemId?.let { activityId ->
                    activityItems.firstOrNull { it.id == activityId }
                }
                val nextActivityItem = nextReviewPlaybackItem(
                    currentItemId = playbackRequest.activityItemId,
                    contextItemIds = playbackRequest.activityContextItemIds,
                    availableItems = activityItems,
                )
                val nextActivityControl = nextActivityControlState(
                    hasContext = playbackRequest.activityContextItemIds.isNotEmpty(),
                    hasNextActivity = nextActivityItem != null,
                    queueContext = playbackRequest.activityQueueContext,
                    loading = state.review.advancingPlayback,
                )
                val activityQueuePosition = playbackRequest.activityItemId?.let {
                    playbackRequest.activityContextItemIds.indexOf(it).takeIf { index -> index >= 0 }
                }
                val activityQueueProgress = activityQueuePosition?.let { index ->
                    val total = playbackRequest.activityContextItemIds.size
                    if (playbackRequest.briefingHighlight) {
                        "Highlight ${index + 1} of $total • ${total - index - 1} remaining"
                    } else {
                        "Activity ${index + 1} of $total"
                    }
                }
                val historySaveKey = playbackRequest.recordingSaveKey
                PlaybackScreen(
                    request = playbackRequest,
                    onBack = {
                        destinationName = returnDestinationName
                        restoreFocusKey = playbackReturnFocusKey
                        playbackReturnFocusKey = null
                        viewModel.closePlayback()
                    },
                    onSessionExpired = viewModel::sessionExpired,
                    preferRtpTcp = state.settings.preferRtpTcp,
                    startMuted = state.settings.startLiveMuted,
                    diagnosticsAvailable = state.settings.diagnosticsEnabled,
                    pictureInPictureAvailable = pictureInPictureAvailable,
                    pictureInPictureActive = pictureInPictureActive,
                    onEnterPictureInPicture = onEnterPictureInPicture,
                    previousCameraName = previous?.displayName,
                    onPrevious = previous?.let { camera -> { viewModel.playAutomatic(camera) } },
                    nextCameraName = next?.displayName,
                    onNext = next?.let { camera -> { viewModel.playAutomatic(camera) } },
                    onOpenEarlier = playbackRequest.cameraName
                        ?.takeIf { cameraName ->
                            playbackRequest.kind == PlaybackKind.LIVE &&
                                cameras.any { it.name == cameraName }
                        }
                        ?.let { cameraName ->
                            { viewModel.playInstantRewind(cameraName) }
                        },
                    onReturnToLive = playbackRequest.returnToLiveCameraName
                        ?.let { cameraName ->
                            cameras.firstOrNull { it.name == cameraName }
                                ?.let { camera -> { viewModel.playAutomatic(camera) } }
                        },
                    snapshotCapturing = state.liveActions.snapshotCapturing,
                    onInstantSnapshot = playbackRequest.cameraName
                        ?.takeIf {
                            playbackRequest.kind == PlaybackKind.LIVE &&
                                state.snapshot?.capabilities?.supports(
                                    app.opah.tv.data.model.FrigateFeature.INSTANT_SNAPSHOT,
                                ) == true
                        }
                        ?.let { cameraName ->
                            { viewModel.captureInstantSnapshot(cameraName, onShareSnapshot) }
                        },
                    onDemandRecordingActive = state.liveActions.recordingEventId != null,
                    onDemandRecordingBusy = state.liveActions.recordingBusy,
                    onToggleOnDemandRecording = playbackRequest.cameraName
                        ?.takeIf {
                            playbackRequest.kind == PlaybackKind.LIVE &&
                                state.snapshot?.capabilities?.supports(
                                    app.opah.tv.data.model.FrigateFeature.ON_DEMAND_RECORDING,
                                ) == true
                        }
                        ?.let { cameraName ->
                            {
                                if (state.liveActions.recordingEventId == null) {
                                    viewModel.startOnDemandRecording(cameraName)
                                } else {
                                    viewModel.stopOnDemandRecording()
                                }
                            }
                        },
                    liveActionMessage = state.liveActions.errorMessage ?: state.liveActions.message,
                    onPlaybackCompleted = activityItem?.let { item ->
                        { viewModel.onReviewPlaybackCompleted(item) }
                    },
                    activityReviewed = activityItem?.hasBeenReviewed,
                    markingActivityReviewed = activityItem != null &&
                        state.review.markingReviewedItemId == activityItem.id,
                    onMarkActivityReviewed = activityItem?.let { item ->
                        { viewModel.setReviewReviewed(item, !item.hasBeenReviewed) }
                    },
                    stretchVideo = playbackRequest.stretchPreferenceKey
                        ?.let { it in state.settings.stretchedCameraNames }
                        ?: false,
                    onToggleStretchVideo = playbackRequest.stretchPreferenceKey?.let { preferenceKey ->
                        {
                            viewModel.updateCameraStretch(
                                preferenceKey,
                                preferenceKey !in state.settings.stretchedCameraNames,
                            )
                        }
                    },
                    savingActivityRecording = when {
                        activityItem != null -> state.review.savingClipItemId == activityItem.id
                        historySaveKey != null -> state.history.savingSlotStartTime ==
                            playbackRequest.recordingStartTime
                        else -> false
                    },
                    activityRecordingSaved = when {
                        activityItem != null -> activityItem.id in state.review.savedClipItemIds
                        historySaveKey != null -> historySaveKey in state.history.savedSlotKeys
                        else -> false
                    },
                    activityRecordingMessage = when {
                        state.review.playbackNavigationMessage != null ->
                            state.review.playbackNavigationMessage
                        activityItem != null -> state.review.savedClipMessage
                            .takeIf { state.review.savedClipItemId == activityItem.id }
                        historySaveKey != null -> state.history.savedMessage
                        else -> null
                    },
                    activityQueueProgress = activityQueueProgress,
                    onCompatibilityTestStatus = viewModel::reportPlaybackCompatibilityStatus,
                    onSaveActivityRecording = when {
                        activityItem != null -> { { viewModel.saveReviewClip(activityItem) } }
                        historySaveKey != null -> { { viewModel.saveHistoryClip(playbackRequest) } }
                        else -> null
                    },
                    nextActivityLabel = nextActivityControl?.label?.let { label ->
                        if (playbackRequest.briefingHighlight && label == "Next activity") {
                            val remaining = playbackRequest.activityContextItemIds.size -
                                (activityQueuePosition ?: 0) - 1
                            "Next highlight ($remaining remaining)"
                        } else {
                            label
                        }
                    },
                    nextActivityEnabled = nextActivityControl?.enabled == true,
                    onNextActivity = nextActivityControl?.let { viewModel::playNextReviewActivity },
                )
            }

            RootSurface.CAMERA_GROUP -> {
                val cameraGroupView = requireNotNull(state.cameraGroupView)
                CameraGroupViewScreen(
                    state = cameraGroupView,
                    preferRtpTcp = state.settings.preferRtpTcp,
                    stretchedCameraNames = state.settings.stretchedCameraNames,
                    pictureInPictureAvailable = pictureInPictureAvailable,
                    pictureInPictureActive = pictureInPictureActive,
                    onEnterPictureInPicture = onEnterPictureInPicture,
                    onStartMonitor = viewModel::startMonitorMode,
                    onBack = {
                        destinationName = cameraGroupOriginDestinationName
                        restoreFocusKey = cameraGroupOriginFocusKey
                        cameraGroupOriginFocusKey = null
                        cameraGroupOriginDestinationName = AppDestination.HOME.name
                        viewModel.closeCameraGroup()
                    },
                    cachedBitmap = { camera -> viewModel.cachedCameraImage(camera)?.bitmap },
                    refreshBitmap = { camera, height ->
                        viewModel.refreshCameraImage(camera, height).getOrNull()?.bitmap
                    },
                )
            }

            RootSurface.MONITOR -> {
                val monitor = requireNotNull(state.monitorMode)
                MonitorModeScreen(
                    state = monitor,
                    stretchedCameraNames = state.settings.stretchedCameraNames,
                    onPreset = viewModel::setMonitorPreset,
                    onManualCamera = viewModel::selectMonitorCamera,
                    onPlaybackReady = viewModel::monitorPlaybackReady,
                    onPlaybackFailed = viewModel::monitorPlaybackFailed,
                    onKeepScreenAwake = viewModel::setMonitorKeepScreenAwake,
                    onAudioEnabled = viewModel::setMonitorAudioEnabled,
                    onExitMinutes = viewModel::setMonitorExitMinutes,
                    onBack = viewModel::closeMonitorMode,
                    cachedBitmap = { camera -> viewModel.cachedCameraImage(camera)?.bitmap },
                    refreshBitmap = { camera, height ->
                        viewModel.refreshCameraImage(camera, height).getOrNull()?.bitmap
                    },
                )
            }

            RootSurface.CONNECTED -> {
                BackHandler(
                    enabled = state.tvAlertPrivacyGateOpen ||
                        state.ptz.cameraName != null ||
                        destination != AppDestination.HOME ||
                        state.review.selectedItemId != null ||
                        state.exports.selectedItemId != null ||
                        settingsPage != SettingsPage.MAIN,
                ) {
                    if (state.tvAlertPrivacyGateOpen) {
                        viewModel.cancelTvAlertPrivacyGate()
                    } else if (state.ptz.cameraName != null) {
                        viewModel.closePtzControls()
                    } else if (destination == AppDestination.ACTIVITY && state.review.selectedItemId != null) {
                        if (state.review.queueActive) viewModel.endReviewQueue() else viewModel.closeReviewItem()
                    } else if (destination == AppDestination.SAVED && state.exports.selectedItemId != null) {
                        viewModel.closeExport()
                    } else if (destination == AppDestination.ACTIVITY && activityPage != ActivityPage.RECENT) {
                        if (activityBackDestinationName == AppDestination.CAMERAS.name) {
                            destinationName = AppDestination.CAMERAS.name
                            restoreFocusKey = activityBackFocusKey
                            activityPageName = ActivityPage.RECENT.name
                        } else {
                            activityPageName = ActivityPage.RECENT.name
                            restoreFocusKey = null
                        }
                    } else if (destination == AppDestination.SETTINGS && settingsPage != SettingsPage.MAIN) {
                        settingsReturnPageName = settingsPage.name
                        settingsPageName = SettingsPage.MAIN.name
                    } else {
                        destinationName = AppDestination.HOME.name
                        settingsPageName = SettingsPage.MAIN.name
                        restoreFocusKey = lastHomeFocus.key
                    }
                }
                ConnectedShell(
                    destination = destination,
                    updateAvailable = state.appUpdate.updateAvailable,
                    unreviewedAlertCount = state.review.counts.unreviewedAlerts,
                    activityCountLoading = state.review.countsLoading,
                    onDestination = {
                        if (state.tvAlertPrivacyGateOpen) viewModel.cancelTvAlertPrivacyGate()
                        destinationName = it.name
                        if (it == AppDestination.ACTIVITY) {
                            activityBackDestinationName = AppDestination.HOME.name
                            activityBackFocusKey = lastHomeFocus.key
                        }
                        settingsPageName = SettingsPage.MAIN.name
                        settingsReturnPageName = null
                        restoreFocusKey = if (it == AppDestination.HOME) lastHomeFocus.key else null
                        playbackReturnFocusKey = null
                    },
                    contentClaimsInitialFocus = restoreFocusKey != null ||
                        (destination == AppDestination.HOME && state.snapshot?.cameras?.isNotEmpty() == true) ||
                        (destination == AppDestination.SETTINGS && settingsPage == SettingsPage.MAIN) ||
                        (destination == AppDestination.SETTINGS && settingsPage != SettingsPage.MAIN) ||
                        (destination == AppDestination.ACTIVITY && state.review.selectedItemId != null),
                    contentEntryFocusRequester = when {
                        destination == AppDestination.HOME && state.snapshot?.cameras?.isNotEmpty() == true ->
                            homeCameraFocusRequester
                        destination == AppDestination.SETTINGS && settingsPage == SettingsPage.MAIN ->
                            settingsMainFocusRequester
                        else -> null
                    },
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        state.errorMessage?.let { error ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 34.dp, top = 18.dp, end = 34.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ScreenMessage(error, isError = true, modifier = Modifier.weight(1f))
                                Button(onClick = viewModel::clearError) { Text("Dismiss") }
                            }
                        }
                        if (state.tvAlertPrivacyGateOpen) {
                            PrivacyPinGateScreen(
                                pinConfigured = state.privacy.pinConfigured,
                                busy = state.privacy.busy,
                                message = state.privacy.errorMessage,
                                protectedContentLabel = "this alert",
                                onSubmit = { pin -> viewModel.unlockTvAlert(pin) },
                                onBack = viewModel::cancelTvAlertPrivacyGate,
                            )
                        } else {
                        when (destination) {
                            AppDestination.HOME -> HomeScreen(
                                state = state,
                                restoreFocusKey = restoreFocusKey,
                                onFocusRestored = { restoreFocusKey = null },
                                onFocusKeyChanged = { lastHomeFocus.key = it },
                                initialCameraFocusRequester = homeCameraFocusRequester,
                                onOpenReview = {
                                    destinationName = AppDestination.ACTIVITY.name
                                    activityPageName = ActivityPage.RECENT.name
                                    activityBackDestinationName = AppDestination.HOME.name
                                    activityBackFocusKey = lastHomeFocus.key
                                },
                                onPlayBriefing = {
                                    playbackReturnFocusKey = state.snapshot?.cameras?.firstOrNull()
                                        ?.let { camera -> "home:camera:${camera.name}" }
                                    returnDestinationName = AppDestination.HOME.name
                                    viewModel.playBriefingHighlights()
                                },
                                onReviewBriefing = {
                                    viewModel.openBriefingReviewQueue()
                                    destinationName = AppDestination.ACTIVITY.name
                                    activityPageName = ActivityPage.RECENT.name
                                    activityBackDestinationName = AppDestination.HOME.name
                                    activityBackFocusKey = state.snapshot?.cameras?.firstOrNull()
                                        ?.let { camera -> "home:camera:${camera.name}" }
                                },
                                onDismissBriefing = viewModel::dismissBriefing,
                                onOpenCameras = { key ->
                                    lastHomeFocus.key = key
                                    destinationName = AppDestination.CAMERAS.name
                                },
                                onOpenBirdseye = { key ->
                                    lastHomeFocus.key = key
                                    playbackReturnFocusKey = key
                                    returnDestinationName = AppDestination.HOME.name
                                    viewModel.playBirdseye()
                                },
                                onOpenCameraGroup = { title, cameraNames, key ->
                                    lastHomeFocus.key = key
                                    cameraGroupOriginFocusKey = key
                                    cameraGroupOriginDestinationName = AppDestination.HOME.name
                                    when {
                                        key.startsWith("home:view:saved:") -> viewModel.rememberLastViewedTarget(
                                            StartupTarget(
                                                StartupTargetKind.SAVED_VIEW,
                                                key.removePrefix("home:view:saved:"),
                                            ),
                                        )
                                        key.startsWith("home:view:group:") -> viewModel.rememberLastViewedTarget(
                                            StartupTarget(
                                                StartupTargetKind.CAMERA_GROUP,
                                                key.removePrefix("home:view:group:"),
                                            ),
                                        )
                                    }
                                    viewModel.openCameraGroup(title, cameraNames)
                                },
                                onStartMonitor = { title, cameraNames, key ->
                                    lastHomeFocus.key = key
                                    restoreFocusKey = key
                                    viewModel.startMonitorModeForView(title, cameraNames)
                                },
                                onToggleFavoriteCamera = viewModel::toggleFavoriteCamera,
                                onMoveFavoriteCamera = viewModel::moveFavoriteCamera,
                                onHideCamera = viewModel::hideCameraFromHome,
                                onOpenCameraControls = { camera ->
                                    destinationName = AppDestination.CAMERAS.name
                                    viewModel.openPtzControls(camera)
                                },
                                onToggleFavoriteView = viewModel::toggleFavoriteView,
                                onMoveFavoriteView = viewModel::moveFavoriteView,
                                onRestoreHomeDefaults = viewModel::restoreHomeDefaults,
                                onLoadModes = viewModel::loadModes,
                                onSwitchMode = viewModel::switchMode,
                                onUndoMode = viewModel::undoModeSwitch,
                                onClearModeMessage = viewModel::clearModeMessage,
                                onPlayCamera = { camera, key ->
                                    playbackReturnFocusKey = key
                                    returnDestinationName = AppDestination.HOME.name
                                    viewModel.playAutomatic(camera)
                                },
                                onPlayReview = { item, key ->
                                    playbackReturnFocusKey = key
                                    returnDestinationName = AppDestination.HOME.name
                                    viewModel.playReview(item, useHomeActivityContext = true)
                                },
                                cachedBitmap = { camera ->
                                    viewModel.cachedCameraImage(camera)?.bitmap
                                },
                                refreshBitmap = { camera, height ->
                                    viewModel.refreshCameraImage(camera, height).getOrNull()?.bitmap
                                },
                                cachedReviewBitmap = { item -> viewModel.cachedReviewImage(item)?.bitmap },
                                refreshReviewBitmap = { item, height ->
                                    viewModel.refreshReviewImage(item, height).getOrNull()?.bitmap
                                },
                            )

                            AppDestination.CAMERAS -> CamerasScreen(
                                state = state,
                                restoreFocusKey = restoreFocusKey,
                                onFocusRestored = { restoreFocusKey = null },
                                onPlayCamera = { camera, key ->
                                    playbackReturnFocusKey = key
                                    returnDestinationName = AppDestination.CAMERAS.name
                                    viewModel.playAutomatic(camera)
                                },
                                onOpenControls = viewModel::openPtzControls,
                                onRetryControls = viewModel::retryPtzControls,
                                onCloseControls = viewModel::closePtzControls,
                                onPtzCommand = viewModel::sendPtzCommand,
                                onOpenCameraGroup = { title, cameraNames, focusKey ->
                                    cameraGroupOriginFocusKey = focusKey
                                    cameraGroupOriginDestinationName = AppDestination.CAMERAS.name
                                    viewModel.openCameraGroup(title, cameraNames)
                                },
                                onStartMonitor = { title, cameraNames, focusKey ->
                                    restoreFocusKey = focusKey
                                    viewModel.startMonitorModeForView(title, cameraNames)
                                },
                                onSaveCameraView = viewModel::saveCameraView,
                                onDeleteCameraView = viewModel::deleteCameraView,
                                cachedBitmap = { camera ->
                                    viewModel.cachedCameraImage(camera)?.bitmap
                                },
                                refreshBitmap = { camera, height ->
                                    viewModel.refreshCameraImage(camera, height).getOrNull()?.bitmap
                                },
                            )

                            AppDestination.ACTIVITY -> when (activityPage) {
                                ActivityPage.RECENT -> ReviewScreen(
                                    state = state,
                                    restoreFocusKey = restoreFocusKey,
                                    onFocusRestored = { restoreFocusKey = null },
                                    onLoad = viewModel::loadReview,
                                    onLoadMore = viewModel::loadMoreReview,
                                    onStartQueue = viewModel::startReviewQueue,
                                    onMoveQueue = viewModel::moveReviewQueue,
                                    onEndQueue = viewModel::endReviewQueue,
                                    onPage = { page ->
                                        activityPageName = page.name
                                        restoreFocusKey = "activity:page:${page.name}"
                                    },
                                    onSeverity = viewModel::updateReviewSeverity,
                                    onApplyFilters = viewModel::applyReviewFilters,
                                    onResetFilters = viewModel::resetReviewFilters,
                                    onSelectItem = { item, key ->
                                        restoreFocusKey = key
                                        viewModel.selectReviewItem(item)
                                    },
                                    onPlayItem = { item ->
                                        playbackReturnFocusKey = reviewItemFocusKey(item.id)
                                        returnDestinationName = AppDestination.ACTIVITY.name
                                        viewModel.playReview(item)
                                    },
                                    onSetReviewed = viewModel::setReviewReviewed,
                                    onMarkAllReviewed = viewModel::markAllShownActivityReviewed,
                                    onSaveClip = viewModel::saveReviewClip,
                                    onSaveAllAngles = viewModel::saveReviewAllAngles,
                                    onFindSimilar = { item ->
                                        if (viewModel.findSimilarActivity(item)) {
                                            activityPageName = ActivityPage.SEARCH.name
                                            restoreFocusKey = "activity:page:${ActivityPage.SEARCH.name}"
                                        }
                                    },
                                    cachedBitmap = { item -> viewModel.cachedReviewImage(item)?.bitmap },
                                    refreshBitmap = { item, height ->
                                        viewModel.refreshReviewImage(item, height).getOrNull()?.bitmap
                                    },
                                )

                                ActivityPage.HISTORY -> ActivityHistoryScreen(
                                    state = state,
                                    playbackRequest = viewModel.historyPlaybackRequest(),
                                    restoreFocusKey = restoreFocusKey,
                                    onFocusRestored = { restoreFocusKey = null },
                                    onPage = { page ->
                                        activityPageName = page.name
                                        restoreFocusKey = "activity:page:${page.name}"
                                    },
                                    onLoad = viewModel::loadHistory,
                                    onOpenAt = viewModel::openHistoryAt,
                                    onMoveHour = viewModel::moveHistoryHour,
                                    onPlay = { slot, key ->
                                        playbackReturnFocusKey = key
                                        returnDestinationName = AppDestination.ACTIVITY.name
                                        viewModel.playHistorySlot(slot)
                                    },
                                    onFindMotionHere = if (
                                        state.snapshot?.capabilities?.supports(
                                            app.opah.tv.data.model.FrigateFeature.MOTION_SEARCH,
                                        ) == true
                                    ) {
                                        { camera, timestamp ->
                                            viewModel.startMotionSearch(camera, timestamp)
                                            activityPageName = ActivityPage.MOTION.name
                                            restoreFocusKey = "activity:page:${ActivityPage.MOTION.name}"
                                        }
                                    } else {
                                        null
                                    },
                                    cachedBitmap = { camera ->
                                        viewModel.cachedCameraImage(camera)?.bitmap
                                    },
                                    refreshBitmap = { camera, height ->
                                        viewModel.refreshCameraImage(camera, height).getOrNull()?.bitmap
                                    },
                                )

                                ActivityPage.SEARCH -> ActivitySearchScreen(
                                    state = state,
                                    restoreFocusKey = restoreFocusKey,
                                    onFocusRestored = { restoreFocusKey = null },
                                    onPage = { page ->
                                        activityPageName = page.name
                                        restoreFocusKey = "activity:page:${page.name}"
                                    },
                                    onSearch = viewModel::searchActivity,
                                    onRepeatSearch = viewModel::repeatActivitySearch,
                                    onLoadMore = viewModel::loadMoreActivitySearch,
                                    onPlay = { event, key ->
                                        playbackReturnFocusKey = key
                                        returnDestinationName = AppDestination.ACTIVITY.name
                                        viewModel.playSearchEvent(event)
                                    },
                                    cachedBitmap = { event -> viewModel.cachedSearchImage(event)?.bitmap },
                                    refreshBitmap = { event, height ->
                                        viewModel.refreshSearchImage(event, height).getOrNull()?.bitmap
                                    },
                                )

                                ActivityPage.MOTION -> MotionSearchScreen(
                                    state = state,
                                    restoreFocusKey = restoreFocusKey,
                                    onFocusRestored = { restoreFocusKey = null },
                                    onPage = { page ->
                                        if (page != ActivityPage.MOTION && state.motionReview.searching) {
                                            viewModel.cancelMotionSearch()
                                        }
                                        activityPageName = page.name
                                        restoreFocusKey = "activity:page:${page.name}"
                                    },
                                    onCamera = viewModel::chooseMotionSearchCamera,
                                    onRegion = viewModel::chooseMotionSearchRegion,
                                    onStart = { viewModel.startMotionSearch() },
                                    onCancel = viewModel::cancelMotionSearch,
                                    onResult = { result ->
                                        state.motionReview.cameraName?.let { camera ->
                                            viewModel.openHistoryAt(camera, result.timestamp)
                                            activityPageName = ActivityPage.HISTORY.name
                                            restoreFocusKey =
                                                "activity:page:${ActivityPage.HISTORY.name}"
                                        }
                                    },
                                )
                            }

                            AppDestination.SAVED -> ClipsScreen(
                                state = state,
                                initialPageName = initialClipsPageName,
                                restoreFocusKey = restoreFocusKey,
                                onFocusRestored = { restoreFocusKey = null },
                                onLoad = viewModel::loadExports,
                                onPlay = { export ->
                                    playbackReturnFocusKey = "clips:item:${export.id}"
                                    returnDestinationName = AppDestination.SAVED.name
                                    viewModel.playExport(export)
                                },
                                onRename = viewModel::renameExport,
                                onAssignIncident = viewModel::assignExportToIncident,
                                onShare = onShareClip,
                                onDelete = viewModel::deleteExport,
                                onCreateIncident = viewModel::createIncident,
                                onUpdateIncident = viewModel::updateIncident,
                                onDeleteIncident = viewModel::deleteIncident,
                                cachedBitmap = { export -> viewModel.cachedExportImage(export)?.bitmap },
                                refreshBitmap = { export, height ->
                                    viewModel.refreshExportImage(export, height).getOrNull()?.bitmap
                                },
                            )

                            AppDestination.SETTINGS -> when {
                                state.privacy.pinConfigured &&
                                    PinScope.SETTINGS in state.privacy.protectedScopes &&
                                    PinScope.SETTINGS !in state.privacy.unlockedScopes -> PrivacyPinGateScreen(
                                        pinConfigured = true,
                                        busy = state.privacy.busy,
                                        message = state.privacy.errorMessage,
                                        initialFocusRequester = settingsDetailFocusRequester,
                                        onSubmit = viewModel::unlockPrivacy,
                                        onBack = {
                                            destinationName = AppDestination.HOME.name
                                            settingsPageName = SettingsPage.MAIN.name
                                        },
                                    )

                                else -> when (settingsPage) {
                                SettingsPage.MAIN -> SettingsHubScreen(
                                    update = state.appUpdate,
                                    settings = state.settings,
                                    privacy = state.privacy,
                                    tvAlerts = state.tvAlerts,
                                    restorePage = settingsReturnPageName?.let { name ->
                                        runCatching { SettingsPage.valueOf(name) }.getOrNull()
                                    },
                                    initialFocusRequester = settingsMainFocusRequester,
                                    onFocusRestored = { settingsReturnPageName = null },
                                    onOpen = { page ->
                                        settingsReturnPageName = page.name
                                        settingsPageName = page.name
                                    },
                                )

                                SettingsPage.UPDATE -> UpdateSettingsScreen(
                                    update = state.appUpdate,
                                    automaticChecksEnabled = state.settings.automaticUpdateChecksEnabled,
                                    onBack = {
                                        settingsReturnPageName = SettingsPage.UPDATE.name
                                        settingsPageName = SettingsPage.MAIN.name
                                    },
                                    onCheckAgain = { viewModel.checkForUpdates(forceRefresh = true) },
                                    onAutomaticChecks = viewModel::updateAutomaticUpdateChecks,
                                    onDownload = viewModel::downloadUpdate,
                                    onInstall = onInstallUpdate,
                                    initialFocusRequester = settingsDetailFocusRequester,
                                )

                                SettingsPage.APPEARANCE -> AppearanceSettingsScreen(
                                    state = state,
                                    onAppearance = viewModel::updateAppearance,
                                    onCustomTheme = viewModel::updateCustomTheme,
                                    onReducedMotion = viewModel::updateReducedMotion,
                                    onHighContrast = viewModel::updateHighContrast,
                                    onBack = {
                                        settingsReturnPageName = SettingsPage.APPEARANCE.name
                                        settingsPageName = SettingsPage.MAIN.name
                                    },
                                    initialFocusRequester = settingsDetailFocusRequester,
                                )

                                SettingsPage.PLAYBACK -> PlaybackSettingsScreen(
                                    state = state,
                                    onStreamPreference = viewModel::updateStreamPreference,
                                    onCompatibilityMode = viewModel::updatePreferRtpTcp,
                                    onStartMuted = viewModel::updateStartLiveMuted,
                                    onPlaybackDetails = viewModel::updateDiagnosticsEnabled,
                                    onAutoMarkReviewed = viewModel::updateAutoMarkReviewedAfterPlayback,
                                    onTestCamera = {
                                        settingsPageName = SettingsPage.PLAYBACK_TEST.name
                                    },
                                    onDiagnosticData = {
                                        settingsPageName = SettingsPage.CAMERA_DIAGNOSTICS.name
                                    },
                                    onBack = {
                                        settingsReturnPageName = SettingsPage.PLAYBACK.name
                                        settingsPageName = SettingsPage.MAIN.name
                                    },
                                    initialFocusRequester = settingsDetailFocusRequester,
                                )

                                SettingsPage.PLAYBACK_TEST -> PlaybackTestCameraScreen(
                                    state = state,
                                    onTestCamera = { cameraName, mode ->
                                        playbackReturnFocusKey =
                                            "settings:playback-test:$cameraName"
                                        returnDestinationName = AppDestination.SETTINGS.name
                                        viewModel.testCameraPlayback(cameraName, mode)
                                    },
                                    onResetCamera = viewModel::resetCameraPlaybackCompatibility,
                                    onLoadChoices = viewModel::loadCameraPlaybackCompatibilityChoices,
                                    onBack = {
                                        settingsPageName = SettingsPage.PLAYBACK.name
                                    },
                                    initialFocusRequester = settingsDetailFocusRequester,
                                )

                                SettingsPage.CAMERA_DIAGNOSTICS -> CameraDiagnosticDataScreen(
                                    state = state,
                                    onRefresh = viewModel::refresh,
                                    onLoadPlaybackChoices =
                                        viewModel::loadCameraPlaybackCompatibilityChoices,
                                    onBack = {
                                        settingsPageName = SettingsPage.PLAYBACK.name
                                    },
                                    initialFocusRequester = settingsDetailFocusRequester,
                                )

                                SettingsPage.TV_ALERTS -> TvAlertsSettingsScreen(
                                    state = state,
                                    onEnable = onEnableTvAlerts,
                                    onDisable = viewModel::disableTvAlerts,
                                    onMode = viewModel::setTvAlertMode,
                                    onPrivacy = viewModel::setTvAlertPrivacy,
                                    onSignificantMotion = viewModel::setTvAlertSignificantMotion,
                                    onCustomSeverity = viewModel::setTvAlertCustomSeverity,
                                    onAllCameras = viewModel::setTvAlertAllCameras,
                                    onCamera = viewModel::setTvAlertCamera,
                                    onAnyLabel = viewModel::clearTvAlertLabels,
                                    onLabel = viewModel::setTvAlertLabel,
                                    onAnyZone = viewModel::clearTvAlertZones,
                                    onZone = viewModel::setTvAlertZone,
                                    onAnyIdentity = viewModel::clearTvAlertIdentities,
                                    onIdentity = viewModel::setTvAlertIdentity,
                                    onAnyPlate = viewModel::clearTvAlertPlates,
                                    onPlate = viewModel::setTvAlertPlate,
                                    onSchedule = viewModel::setTvAlertSchedulePreset,
                                    onScheduleWindow = viewModel::setTvAlertScheduleWindow,
                                    onAnyMode = viewModel::clearTvAlertModes,
                                    onModeFilter = viewModel::setTvAlertModeFilter,
                                    onMinimumThreatLevel = viewModel::setTvAlertMinimumThreatLevel,
                                    onSnooze = viewModel::snoozeTvAlerts,
                                    onSnoozeUntilTomorrow = viewModel::snoozeTvAlertsUntilTomorrow,
                                    onSnoozeUntilModeChanges = viewModel::snoozeTvAlertsUntilModeChanges,
                                    onClearSnooze = viewModel::clearTvAlertSnoozes,
                                    onTestAlert = onTestTvAlert,
                                    onOverlayVerticalPosition =
                                        viewModel::setTvAlertOverlayVerticalPosition,
                                    onOverlayHorizontalPosition =
                                        viewModel::setTvAlertOverlayHorizontalPosition,
                                    onOverlayImageSize = viewModel::setTvAlertOverlayImageSize,
                                    onOverlayDisplayDurationSeconds =
                                        viewModel::setTvAlertOverlayDisplayDurationSeconds,
                                    onInteractiveFrigateAlerts = viewModel::setInteractiveFrigateAlerts,
                                    onOpenAndroidSettings = onOpenNotificationSettings,
                                    onOpenOverlaySettings = onOpenOverlaySettings,
                                    onLoadModes = viewModel::loadModes,
                                    onRefreshDeliveryStatus = viewModel::refreshTvAlertDeliveryStatus,
                                    onBack = {
                                        settingsReturnPageName = SettingsPage.TV_ALERTS.name
                                        settingsPageName = SettingsPage.MAIN.name
                                    },
                                    initialFocusRequester = settingsDetailFocusRequester,
                                )

                                SettingsPage.PRIVACY -> PrivacySettingsScreen(
                                    state = state,
                                    onSetupPin = viewModel::setupLocalPin,
                                    onUnlockPinChoices = viewModel::unlockPrivacy,
                                    onPinScope = viewModel::setPinScope,
                                    onRemovePin = viewModel::removeLocalPin,
                                    onCameraPrivate = viewModel::setCameraPrivate,
                                    onLockNow = viewModel::lockPrivacyNow,
                                    onBack = {
                                        settingsReturnPageName = SettingsPage.PRIVACY.name
                                        settingsPageName = SettingsPage.MAIN.name
                                    },
                                    initialFocusRequester = settingsDetailFocusRequester,
                                )

                                SettingsPage.STARTUP -> StartupSettingsScreen(
                                    state = state,
                                    onTarget = viewModel::updateStartupTarget,
                                    onBack = {
                                        settingsReturnPageName = SettingsPage.STARTUP.name
                                        settingsPageName = SettingsPage.MAIN.name
                                    },
                                    initialFocusRequester = settingsDetailFocusRequester,
                                )

                                SettingsPage.CONNECTION -> InformationScreen(
                                    state = state,
                                    onLoad = { viewModel.loadInformation() },
                                    onRefresh = { viewModel.loadInformation(force = true) },
                                    onUpdateLiveRoute = viewModel::updateRtspRoute,
                                    onSignOut = { viewModel.logout(false) },
                                    onForgetServer = { viewModel.logout(true) },
                                    onBack = {
                                        settingsReturnPageName = SettingsPage.CONNECTION.name
                                        settingsPageName = SettingsPage.MAIN.name
                                    },
                                    initialFocusRequester = settingsDetailFocusRequester,
                                    initialTabName = InformationTab.CONNECTION.name,
                                )

                                SettingsPage.SYSTEM -> InformationScreen(
                                    state = state,
                                    onLoad = { viewModel.loadInformation() },
                                    onRefresh = { viewModel.loadInformation(force = true) },
                                    onUpdateLiveRoute = viewModel::updateRtspRoute,
                                    onSignOut = { viewModel.logout(false) },
                                    onForgetServer = { viewModel.logout(true) },
                                    onBack = {
                                        settingsReturnPageName = SettingsPage.SYSTEM.name
                                        settingsPageName = SettingsPage.MAIN.name
                                    },
                                    initialFocusRequester = settingsDetailFocusRequester,
                                    initialTabName = initialInformationTabName,
                                )

                                SettingsPage.ADVANCED -> DiagnosticsScreen(
                                    state = state,
                                    onRefresh = viewModel::refresh,
                                    onBack = {
                                        settingsReturnPageName = SettingsPage.ADVANCED.name
                                        settingsPageName = SettingsPage.MAIN.name
                                    },
                                    initialFocusRequester = settingsDetailFocusRequester,
                                )

                                SettingsPage.ABOUT -> AboutScreen(
                                    connectedServerVersion = state.snapshot?.frigateVersion,
                                    onBack = {
                                        settingsReturnPageName = SettingsPage.ABOUT.name
                                        settingsPageName = SettingsPage.MAIN.name
                                    },
                                    initialFocusRequester = settingsDetailFocusRequester,
                                )
                                }
                            }
                        }
                        }
                    }
                }
            }

            RootSurface.LOADING -> StartupLoadingScreen(state.statusMessage)

            RootSurface.RECOVERY -> StartupRecoveryScreen(
                message = state.errorMessage,
                onRetry = viewModel::retrySavedSession,
                onConnectionSettings = viewModel::showConnectionSetup,
            )

            RootSurface.SETUP -> ConnectionSetupScreen(
                state = state,
                onDismissError = viewModel::clearError,
                onTestConnection = viewModel::testConnection,
                onConnect = viewModel::connect,
                onForget = { viewModel.logout(true) },
                onExitRequested = onExitRequested,
            )
        }
    }
}

private const val SETTINGS_DETAIL_FOCUS_ATTEMPTS = 8

@Composable
private fun ConnectedShell(
    destination: AppDestination,
    updateAvailable: Boolean,
    unreviewedAlertCount: Int,
    activityCountLoading: Boolean,
    onDestination: (AppDestination) -> Unit,
    contentClaimsInitialFocus: Boolean,
    contentEntryFocusRequester: FocusRequester?,
    content: @Composable () -> Unit,
) {
    var contentFocusRequest by remember { mutableIntStateOf(0) }
    var shellRenderPass by remember { mutableIntStateOf(0) }
    val focusManager = LocalFocusManager.current
    val rootDestination = destination.rootDestination()
    val rootDestinations = remember { AppDestination.entries.filter(AppDestination::isRoot) }
    val navRequesters = remember { rootDestinations.associateWith { FocusRequester() } }
    val railClaimsInitialFocus = remember { !contentClaimsInitialFocus }
    val railExpansionState = remember { NavigationRailExpansionState() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (railClaimsInitialFocus) {
            navRequesters.getValue(rootDestination).requestFocus()
        } else {
            contentEntryFocusRequester?.requestFocus()
        }
    }
    LaunchedEffect(contentFocusRequest, destination) {
        if (contentFocusRequest == 0) return@LaunchedEffect
        withFrameNanos { }
        focusManager.moveFocus(FocusDirection.Right)
        contentFocusRequest = 0
    }
    LaunchedEffect(destination, contentClaimsInitialFocus) {
        withFrameNanos { }
        shellRenderPass += 1
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .drawWithContent {
                shellRenderPass
                drawContent()
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 60.dp)
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.DirectionLeft) {
                        when (event.type) {
                            KeyEventType.KeyDown -> railExpansionState.armForNavigationFocus()
                            KeyEventType.KeyUp -> railExpansionState.cancelPendingExpansion()
                            else -> Unit
                        }
                    }
                    false
                }
                .onFocusChanged {
                    if (it.hasFocus) railExpansionState.contentFocused()
                },
        ) {
            content()
        }
        if (railExpansionState.expanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = OpahDesignTokens.ScrimOpacity)),
            )
        }
        ConnectedNavigationRail(
            destination = rootDestination,
            updateAvailable = updateAvailable,
            unreviewedAlertCount = unreviewedAlertCount,
            activityCountLoading = activityCountLoading,
            navRequesters = navRequesters,
            expansionState = railExpansionState,
            contentEntryFocusRequester = contentEntryFocusRequester,
            onSelect = { item ->
                railExpansionState.destinationSelected()
                onDestination(item)
                contentFocusRequest += 1
            },
            modifier = Modifier.align(Alignment.CenterStart),
        )
    }
    BackHandler(enabled = railExpansionState.expanded) {
        railExpansionState.dismiss()
        contentEntryFocusRequester?.requestFocus()
    }
}

@Stable
internal class NavigationRailExpansionState {
    var expanded by mutableStateOf(false)
        private set
    private var expandOnNextNavigationFocus = false

    fun armForNavigationFocus() {
        expandOnNextNavigationFocus = true
    }

    fun cancelPendingExpansion() {
        expandOnNextNavigationFocus = false
    }

    fun navigationFocused() {
        if (expandOnNextNavigationFocus) expanded = true
        expandOnNextNavigationFocus = false
    }

    fun navigationInput() {
        expanded = true
        expandOnNextNavigationFocus = false
    }

    fun contentFocused() {
        expanded = false
        expandOnNextNavigationFocus = false
    }

    fun destinationSelected() {
        expanded = false
        expandOnNextNavigationFocus = false
    }

    fun dismiss() {
        expanded = false
        expandOnNextNavigationFocus = false
    }
}

@Composable
private fun ConnectedNavigationRail(
    destination: AppDestination,
    updateAvailable: Boolean,
    unreviewedAlertCount: Int,
    activityCountLoading: Boolean,
    navRequesters: Map<AppDestination, FocusRequester>,
    expansionState: NavigationRailExpansionState,
    contentEntryFocusRequester: FocusRequester?,
    onSelect: (AppDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val expanded = expansionState.expanded
    val visibleDestinations = AppDestination.entries.filter(AppDestination::isRoot)
    Column(
        modifier = modifier
            .width(if (expanded) 180.dp else 60.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 6.dp, vertical = 12.dp)
            .onPreviewKeyEvent { event ->
                if (
                    event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionUp || event.key == Key.DirectionDown)
                ) {
                    expansionState.navigationInput()
                }
                false
            }
            .focusProperties {
                onEnter = { navRequesters.getValue(destination).requestFocus() }
            }
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.opah_brand_mark),
                    contentDescription = null,
                    modifier = Modifier.size(34.dp),
                )
                if (expanded) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Opah",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
        visibleDestinations.forEachIndexed { index, item ->
            NavigationItem(
                focusKey = "nav:${item.name.lowercase()}",
                selected = item == destination,
                accessibilityLabel = navigationAccessibilityLabel(
                    item,
                    updateAvailable,
                    unreviewedAlertCount,
                    activityCountLoading,
                ),
                onClick = { onSelect(item) },
                externalFocusRequester = navRequesters.getValue(item),
                onFocused = { expansionState.navigationFocused() },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusProperties {
                        up = if (index == 0) {
                            FocusRequester.Cancel
                        } else {
                            navRequesters.getValue(visibleDestinations[index - 1])
                        }
                        down = if (index == visibleDestinations.lastIndex) {
                            FocusRequester.Cancel
                        } else {
                            navRequesters.getValue(visibleDestinations[index + 1])
                        }
                        left = FocusRequester.Cancel
                        right = if (item == destination && contentEntryFocusRequester != null) {
                            contentEntryFocusRequester
                        } else {
                            FocusRequester.Default
                        }
                    },
            ) {
                NavigationRailItemContent(
                    item,
                    destination,
                    updateAvailable,
                    unreviewedAlertCount,
                    activityCountLoading,
                    expanded,
                )
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun NavigationRailItemContent(
    item: AppDestination,
    destination: AppDestination,
    updateAvailable: Boolean,
    unreviewedAlertCount: Int,
    activityCountLoading: Boolean,
    expanded: Boolean,
) {
    val iconTint = if (item == destination) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (expanded) 12.dp else 0.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (expanded) Arrangement.spacedBy(12.dp) else Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(item.iconRes),
                contentDescription = null,
                colorFilter = ColorFilter.tint(
                    if (item == AppDestination.ACTIVITY && unreviewedAlertCount > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        iconTint
                    },
                ),
                modifier = Modifier.size(28.dp),
            )
            if (item == AppDestination.SETTINGS && updateAvailable) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
            if (item == AppDestination.ACTIVITY) {
                val countLabel = if (activityCountLoading) "…" else compactAlertCount(unreviewedAlertCount)
                countLabel?.let { count ->
                    Text(
                        text = count,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = if (count.length > 2) 8.sp else 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        if (expanded) {
            Column {
                Text(
                    text = item.label,
                    maxLines = 1,
                    color = if (item == destination) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontWeight = if (item == destination) FontWeight.Bold else FontWeight.Normal,
                )
                if (item == AppDestination.ACTIVITY && activityCountLoading) {
                    Text(
                        text = "Checking alerts",
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (item == AppDestination.ACTIVITY && unreviewedAlertCount > 0) {
                    Text(
                        text = "$unreviewedAlertCount unreviewed alerts",
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun List<Camera>.wrappedAt(index: Int): Camera? {
    if (isEmpty()) return null
    val wrapped = ((index % size) + size) % size
    return get(wrapped)
}
