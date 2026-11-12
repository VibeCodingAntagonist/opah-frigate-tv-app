package app.opah.tv.ui.views

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Parcelable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.opah.tv.BuildConfig
import app.opah.tv.OpahApplication
import app.opah.tv.homeassistant.HomeAssistantConnectionPhase
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import app.opah.tv.PictureInPictureRequest
import app.opah.tv.R
import app.opah.tv.data.ConnectionProfileFactory
import app.opah.tv.data.model.AppearanceMode
import app.opah.tv.data.model.Camera
import app.opah.tv.data.model.CustomThemeColors
import app.opah.tv.data.model.ThemeColorPolicy
import app.opah.tv.data.model.FrigateFeature
import app.opah.tv.data.model.FrigateCapabilityAvailability
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.RecordingExport
import app.opah.tv.data.model.StartupTarget
import app.opah.tv.data.model.StartupTargetKind
import app.opah.tv.data.model.StreamPreference
import app.opah.tv.notifications.AlertMode
import app.opah.tv.notifications.NotificationPrivacy
import app.opah.tv.notifications.android.TvAlertOverlayHorizontalPosition
import app.opah.tv.notifications.android.TvAlertOverlayImageSize
import app.opah.tv.notifications.android.TvAlertOverlayVerticalPosition
import app.opah.tv.notifications.android.TvAlertDeliveryStatus
import app.opah.tv.awareness.AwarenessReviewSeverity
import app.opah.tv.privacy.PinScope
import app.opah.tv.ui.Phase0UiState
import app.opah.tv.ui.Phase0ViewModel
import app.opah.tv.ui.ConnectionFormSubmission
import app.opah.tv.ui.PlaybackCompatibilityTestMode
import app.opah.tv.ui.ActivitySearchFilters
import app.opah.tv.ui.activitySimilaritySeed
import app.opah.tv.ui.isSimilaritySearch
import app.opah.tv.ui.searchDescription
import app.opah.tv.ui.ActivitySearchTimeRange
import app.opah.tv.ui.NativeBrowseSelections
import app.opah.tv.ui.NativeBrowseRestoreResult
import app.opah.tv.ui.nativeSelection
import app.opah.tv.ui.filters
import app.opah.tv.ui.ReviewStatusFilter
import app.opah.tv.ui.ReviewFilters
import app.opah.tv.ui.ReviewTimeRange
import app.opah.tv.ui.ReviewAction
import app.opah.tv.ui.ReviewActionError
import app.opah.tv.ui.actionErrorFor
import app.opah.tv.ui.visibleReviewItems
import app.opah.tv.ui.hasPendingWrite
import app.opah.tv.ui.SETTINGS_PAGE_ORDER
import app.opah.tv.ui.SettingsPage
import app.opah.tv.ui.TvAlertSchedulePreset
import app.opah.tv.ui.TvAlertSnoozeChoice
import app.opah.tv.ui.TvAlertNavigationDestination
import app.opah.tv.ui.OPAH_REPOSITORY_URL
import app.opah.tv.ui.aboutOpahText
import app.opah.tv.ui.cameraTechnicalReport
import app.opah.tv.ui.adjustTvAlertOverlayDisplayDuration
import app.opah.tv.ui.canDeleteSavedRecordings
import app.opah.tv.ui.cameraNeighbors
import app.opah.tv.ui.nativeHistoryRowKey
import app.opah.tv.ui.historySlots
import app.opah.tv.ui.historyDateChoices
import app.opah.tv.ui.historyHourChoices
import app.opah.tv.ui.shiftHistoryDate
import app.opah.tv.ui.motionRegionLabel
import app.opah.tv.ui.nextActivityControlState
import app.opah.tv.ui.nextReviewPlaybackItem
import app.opah.tv.ui.projectForPrivacy
import app.opah.tv.ui.resolveConfiguredStartupTarget
import app.opah.tv.ui.startupTargetLabel
import app.opah.tv.ui.themeBrightnessLabel
import app.opah.tv.ui.themeHueName
import app.opah.tv.ui.themeIntensityLabel
import app.opah.tv.ui.tvAlertOverlayDisplayDurationLabel
import app.opah.tv.ui.unreviewedShownActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal data class NativeHostActions(
    val onExitRequested: () -> Unit,
    val onInstallUpdate: (String) -> Unit,
    val onEnableTvAlerts: () -> Unit,
    val onTestTvAlert: (Boolean) -> Unit,
    val onOpenNotificationSettings: () -> Unit,
    val onOpenOverlaySettings: () -> Unit,
    val pictureInPictureAvailable: Boolean,
    val onEnterPictureInPicture: (PictureInPictureRequest) -> Boolean,
    val onShareSnapshot: (Bitmap, String) -> Boolean,
    val onShareClip: (RecordingExport) -> Unit,
    val onFullyDrawn: () -> Unit,
)

internal fun nativeDialogListHeightDp(itemCount: Int): Int =
    (itemCount.coerceAtLeast(1) * 58).coerceIn(96, 360)

internal fun nativeReadingDialogHeightDp(characterCount: Int): Int = when {
    characterCount <= 160 -> 130
    characterCount <= 480 -> 230
    else -> 380
}

private data class PendingProtectedAction(
    val scope: PinScope,
    val title: String,
    val action: () -> Unit,
    val submitted: Boolean = false,
)

@UnstableApi
internal class NativeOpahController(
    private val activity: ComponentActivity,
    private val viewModel: Phase0ViewModel,
    private val actions: NativeHostActions,
    initialDestinationName: String? = null,
    initialSettingsPageName: String? = null,
    initialInformationTabName: String? = null,
    initialActivityPageName: String? = null,
    initialClipsPageName: String? = null,
    restoredUiState: Bundle? = null,
) {
    val root: NativeRootLayout = LayoutInflater.from(activity)
        .inflate(R.layout.native_opah_root, FrameLayout(activity), false) as NativeRootLayout

    private val contentHost: FrameLayout = root.findViewById(R.id.native_content_host)
    private val navigationRail: LinearLayout = root.findViewById(R.id.native_nav_rail)
    private val navigationItems: LinearLayout = root.findViewById(R.id.native_nav_items)
    private val navViews = linkedMapOf<NativeDestination, TextView>()
    private val focusMemory = mutableMapOf<String, String>()
    private var homeBookmark: NativeHomeBookmark? = null
    private var settingsPosition: NativeSettingsPosition? = null
    private var connectionsPosition: NativeSettingsPosition? = null
    private var motionBookmark: NativeMotionSearchBookmark? = null
    private var appearancePosition: NativeAppearancePosition? = null
    private var alertSection = NativeAlertSection.ACTIVITY
    private val browserBookmarks = mutableMapOf<String, NativeMediaBrowserBookmark>()
    private val listScrollMemory = mutableMapOf<String, Parcelable>()
    private val destinationLocations = mutableMapOf<NativeDestination, NativeLocation>()
    private var navigationAppearanceKey: String? = null
    private var openDialogCount = 0
    private val activeDialogs = mutableSetOf<Dialog>()
    private var suppressDialogFocusRestore = false
    private val handlers = mutableMapOf<String, () -> Unit>()
    private val adjustmentHandlers = mutableMapOf<String, (Int) -> Unit>()
    private var route = initialRoute(initialDestinationName, initialSettingsPageName)
    private var currentState = Phase0UiState()
    private var currentListAdapter: NativeListAdapter? = null
    private var currentRecyclerView: RecyclerView? = null
    private var activeSurfaceKey: String? = null
    private var activeHome: NativeHomeSurface? = null
    private var activeSettings: NativeSettingsSurface? = null
    private var activeConnections: NativeSettingsSurface? = null
    private var activeHomeAssistantSetup: NativeHomeAssistantSetup? = null
    private var activeGuidedSetup: NativeGuidedSetup? = null
    private val homeAssistant by lazy { (activity.application as OpahApplication).container.homeAssistantConnection }
    private var activeAppearance: NativeAppearanceSurface? = null
    private var activeAbout: NativeAboutSurface? = null
    private var activeWelcome: NativeWelcomeSurface? = null
    private var activeBrowser: NativeMediaBrowserSurface? = null
    private var activeMotionSearch: NativeMotionSearchSurface? = null
    private var activePtz: NativePtzSurface? = null
    private var activePlayback: NativePlaybackSurface? = null
    private var activeCameraGroup: NativeCameraGroupSurface? = null
    private var activeMonitor: NativeMonitorSurface? = null
    private var stateJob: Job? = null
    private var homeAssistantStateJob: Job? = null
    private var hasPresentedState = false
    private var liveActionMessageJob: Job? = null
    private var scheduledLiveActionMessage: String? = null
    private var pendingProtectedAction: PendingProtectedAction? = null
    private var playbackCompatibilityLoadKey: String? = null
    private val thumbnailJobs = mutableMapOf<String, Job>()
    private val thumbnailTargets = mutableMapOf<String, MutableList<WeakReference<ImageView>>>()
    private var fullyDrawnReported = false
    private var lastNavigationRequestId: Long? = null
    private var lastGlobalErrorMessage: String? = null
    private var activityPage = initialActivityPageName
        ?.let { name -> runCatching { NativeActivityPage.valueOf(name) }.getOrNull() }
        ?: NativeActivityPage.NEW
    private var clipsPage = initialClipsPageName
        ?.let { name -> runCatching { NativeClipsPage.valueOf(name) }.getOrNull() }
        ?: NativeClipsPage.RECORDINGS
    private var serverPage = initialInformationTabName
        ?.let { name -> runCatching { NativeServerPage.valueOf(name) }.getOrNull() }
        ?: NativeServerPage.PERFORMANCE
    private var activitySearchDraftFilters: ActivitySearchFilters? = null
    private var pendingBrowseSelections: NativeBrowseSelections? = null
    private var configuredStartupApplied = initialDestinationName != null
    private var pendingDocumentationCameraChooser = BuildConfig.DOCUMENTATION_MODE &&
        initialDestinationName == "CREATE_VIEW"
    private val navigationHistory = NativeNavigationHistory(NativeLocation())

    init {
        if (restoredUiState != null) restoreUiState(restoredUiState)
        else navigationHistory.reset(currentLocation())
        viewModel.attachNativeBrowseSelections(pendingBrowseSelections)
        buildNavigation()
        styleRoot()
        root.onBackFromContent = ::handleBack
        root.onNavigationStateChanged = { open -> updateNavigationAppearance(open) }
        root.onRestoreContentFocus = {
            (contentHost.findViewWithTag<View>(focusMemory[route.focusMemoryKey])?.requestFocus()
                ?: contentHost.focusSearch(View.FOCUS_DOWN)?.requestFocus()) == true
        }
    }

    fun start() {
        if (stateJob != null) return
        homeAssistantStateJob = activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                homeAssistant.loadState()
                homeAssistant.state.map { Triple(it.phase, it.enabled, it.deviceName) }.distinctUntilChanged().collectLatest {
                    if (hasPresentedState && route.destination == NativeDestination.CONNECTIONS) render(currentState)
                }
            }
        }
        stateJob = activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collectLatest { unprojected ->
                    val restoration = viewModel.restoreNativeBrowseSelectionsIfReady()
                    val draftReady = pendingBrowseSelections == null ||
                        (unprojected.activeProfile != null && unprojected.snapshot != null && !unprojected.privacy.loading)
                    if (draftReady) {
                        pendingBrowseSelections?.let { saved ->
                            val valid = viewModel.validatedNativeBrowseSelections(saved)
                            activitySearchDraftFilters = valid?.searchDraft?.filters()
                            if (valid == null) clearBrowseBookmarks()
                            pendingBrowseSelections = null
                        }
                    }
                    if (restoration == NativeBrowseRestoreResult.REJECTED_PROFILE) clearBrowseBookmarks()
                    val presented = if (restoration == NativeBrowseRestoreResult.APPLIED) {
                        viewModel.state.value.projectForPrivacy()
                    } else unprojected.projectForPrivacy()
                    hasPresentedState = true
                    render(if ((restoration == NativeBrowseRestoreResult.WAITING || !draftReady) &&
                        presented.activeProfile != null && presented.snapshot != null
                    ) {
                        presented.copy(snapshot = null, loading = true)
                    } else presented)
                }
            }
        }
    }

    fun stop() {
        activeGuidedSetup?.close()
        activeGuidedSetup = null
        homeAssistantStateJob?.cancel()
        homeAssistantStateJob = null
        hasPresentedState = false
        activeHomeAssistantSetup?.close()
        activeHomeAssistantSetup = null
        activeConnections = null
        stateJob?.cancel()
        stateJob = null
        liveActionMessageJob?.cancel()
        liveActionMessageJob = null
        scheduledLiveActionMessage = null
        pendingProtectedAction = null
        playbackCompatibilityLoadKey = null
        thumbnailJobs.values.forEach(Job::cancel)
        thumbnailJobs.clear()
        thumbnailTargets.clear()
        suppressDialogFocusRestore = true
        activeDialogs.toList().forEach(Dialog::dismiss)
        activeHome?.close()
        activeHome = null
        activeSettings = null
        activeBrowser = null
        activeMotionSearch?.close()
        activeMotionSearch = null
        activePtz?.close()
        activePtz = null
        activePlayback?.close()
        activePlayback = null
        activeCameraGroup?.close()
        activeCameraGroup = null
        activeMonitor?.close()
        activeMonitor = null
    }

    fun handleSystemBack(): Boolean {
        handleBack()
        return true
    }

    fun onPictureInPictureModeChanged(active: Boolean) {
        activeCameraGroup?.setPictureInPictureActive(active)
        activePlayback?.setPictureInPictureActive(active)
    }

    private fun currentLocation() = NativeLocation(route, activityPage, clipsPage, serverPage)

    fun saveUiState(): Bundle {
        captureSurfacePosition()
        destinationLocations[route.destination] = currentLocation()
        return NativeNavigationStateCodec.encode(NativeSavedNavigationState(
            location = currentLocation(),
            history = navigationHistory.snapshot(),
            perDestinationLocations = destinationLocations,
            home = homeBookmark,
            browsers = browserBookmarks,
            motion = motionBookmark,
            settingsPosition = settingsPosition,
            appearancePosition = appearancePosition,
            alertSection = alertSection,
            focus = focusMemory,
            listScroll = listScrollMemory,
            browse = viewModel.captureNativeBrowseSelections().copy(
                searchDraft = activitySearchDraftFilters?.nativeSelection() ?: pendingBrowseSelections?.searchDraft,
            ),
        ), activity)
    }

    private fun restoreUiState(saved: Bundle) {
        val restored = NativeNavigationStateCodec.decode(saved, activity)
        applyLocation(restored.location)
        navigationHistory.restore(restored.location, restored.history)
        destinationLocations.putAll(restored.perDestinationLocations)
        focusMemory.putAll(restored.focus)
        homeBookmark = restored.home
        browserBookmarks.putAll(restored.browsers)
        motionBookmark = restored.motion
        settingsPosition = restored.settingsPosition
        appearancePosition = restored.appearancePosition
        alertSection = restored.alertSection
        listScrollMemory.putAll(restored.listScroll)
        pendingBrowseSelections = restored.browse
        configuredStartupApplied = true
    }

    private fun clearBrowseBookmarks() {
        homeBookmark = null
        motionBookmark = null
        browserBookmarks.clear()
        focusMemory.clear()
        listScrollMemory.clear()
    }

    private fun applyLocation(location: NativeLocation) {
        route = location.route
        activityPage = location.activityPage
        clipsPage = location.clipsPage
        serverPage = location.serverPage
    }

    private fun createDialog(onDismiss: (() -> Unit)? = null): Dialog {
        val origin = root.findFocus()
        val originKey = origin?.tag as? String
        val originLocation = currentLocation()
        return Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            openDialogCount += 1
            activeDialogs.add(this)
            activePlayback?.setInteractionOverlayOpen(true)
            window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.setDimAmount(0.45f)
            setOnDismissListener {
                onDismiss?.invoke()
                activeDialogs.remove(this)
                openDialogCount = (openDialogCount - 1).coerceAtLeast(0)
                activePlayback?.setInteractionOverlayOpen(openDialogCount > 0)
                if (!suppressDialogFocusRestore && currentLocation() == originLocation && !root.navigationOpen) {
                    val target = if (originKey != null) root.findViewWithTag<View>(originKey) else origin
                    if (target?.isAttachedToWindow == true && target.isShown && target.isEnabled && target.isFocusable) {
                        target.requestFocus()
                    }
                }
            }
        }
    }

    private fun render(state: Phase0UiState) {
        val removedCameraAccess = currentState.snapshot?.cameras.orEmpty().any { previous ->
            state.snapshot?.cameras.orEmpty().none { it.name == previous.name }
        }
        val privacyRevoked = (!currentState.privacy.guestModeActive && state.privacy.guestModeActive) ||
            !state.privacy.unlockedScopes.containsAll(currentState.privacy.unlockedScopes) ||
            (currentState.privacy.available && !state.privacy.available) || removedCameraAccess
        if (privacyRevoked || state.privacy.epoch != currentState.privacy.epoch ||
            (!currentState.tvAlertPrivacyGateOpen && state.tvAlertPrivacyGateOpen)
        ) {
            suppressDialogFocusRestore = true
            activeDialogs.toList().forEach(Dialog::dismiss)
            suppressDialogFocusRestore = false
            if (privacyRevoked) pendingProtectedAction = null
        }
        if (NativeTheme.update(activity, state.settings)) {
            val preserveSurface = activeAppearance != null || activePlayback != null ||
                activeCameraGroup != null || activeMonitor != null
            if (!preserveSurface) {
                clearActiveSurface()
                contentHost.removeAllViews()
            } else {
                activeSettings?.refreshTheme()
                activeConnections?.refreshTheme()
                activeAppearance?.refreshTheme()
            }
            styleRoot()
        }
        currentState = state
        scheduleLiveActionMessageClear(state)
        resumeProtectedActionIfReady(state)
        if (!state.tvAlertPrivacyGateOpen) {
            state.tvAlertNavigation?.let { request ->
                if (lastNavigationRequestId != request.requestId) {
                    configuredStartupApplied = true
                    lastNavigationRequestId = request.requestId
                    route = when (request.destination) {
                        TvAlertNavigationDestination.ACTIVITY -> NativeRoute(NativeDestination.ACTIVITY)
                        TvAlertNavigationDestination.TV_ALERT_SETTINGS ->
                            NativeRoute(NativeDestination.SETTINGS, SettingsPage.TV_ALERTS)
                    }
                    viewModel.consumeTvAlertNavigation(request.requestId)
                }
            }
            applyConfiguredStartupIfReady(state)
        }
        val requestedLocation = currentLocation()
        navigationHistory.visit(requestedLocation)
        when {
            state.tvAlertPrivacyGateOpen -> showTvAlertPrivacyGate(state)
            state.playback != null -> showPlayback(state)
            state.monitorMode != null -> showMonitor(state)
            state.cameraGroupView != null -> showCameraGroup(state)
            state.ptz.cameraName != null -> showPtz(state)
            route.destination == NativeDestination.CONNECTIONS -> showConnections(state)
            route.destination == NativeDestination.SETTINGS && state.privacy.available -> {
                setNavigationVisible(true)
                updateNavigationSelection()
                updateNavigationAppearance(root.navigationOpen)
                showSettings(state)
            }
            state.activeProfile != null && state.snapshot != null -> showConnected(state)
            state.connectionWorkInProgress || state.loading -> showLoading(state)
            state.savedSessionRecoveryAvailable -> showRecovery(state)
            else -> showConnectionSetup(state)
        }
        if (currentLocation() != requestedLocation) navigationHistory.replaceCurrent(currentLocation())
        destinationLocations[route.destination] = currentLocation()
        showGlobalErrorIfNeeded(state)
        if (!fullyDrawnReported && (
                state.activeProfile != null || !state.loading || state.savedSessionRecoveryAvailable
            )
        ) {
            fullyDrawnReported = true
            actions.onFullyDrawn()
        }
    }

    private fun applyConfiguredStartupIfReady(state: Phase0UiState) {
        if (configuredStartupApplied) return
        if (route.destination == NativeDestination.CONNECTIONS) {
            configuredStartupApplied = true
            return
        }
        if (
            state.playback != null ||
            state.monitorMode != null ||
            state.cameraGroupView != null ||
            state.ptz.cameraName != null
        ) {
            configuredStartupApplied = true
            return
        }
        val snapshot = state.snapshot ?: return
        if (state.activeProfile == null || !state.settingsLoaded) return

        val target = resolveConfiguredStartupTarget(state.settings, snapshot)
        configuredStartupApplied = true
        route = NativeRoute(NativeDestination.HOME)
        when (target.kind) {
            StartupTargetKind.HOME,
            StartupTargetKind.LAST_VIEWED,
            -> Unit
            StartupTargetKind.CAMERA -> snapshot.cameras
                .firstOrNull { it.name == target.value }
                ?.let(viewModel::playAutomatic)
            StartupTargetKind.SAVED_VIEW -> state.settings.savedCameraViews
                .firstOrNull { it.id == target.value }
                ?.let { view -> viewModel.openCameraGroup(view.name, view.cameraNames) }
            StartupTargetKind.CAMERA_GROUP -> snapshot.cameraGroups
                .firstOrNull { it.name == target.value }
                ?.let { group -> viewModel.openCameraGroup(group.displayName, group.cameraNames) }
            StartupTargetKind.BIRDSEYE -> viewModel.playBirdseye()
        }
    }

    private fun showGlobalErrorIfNeeded(state: Phase0UiState) {
        val message = state.errorMessage
        if (message == null) {
            lastGlobalErrorMessage = null
            return
        }
        if (state.activeProfile == null || state.snapshot == null || message == lastGlobalErrorMessage) return
        lastGlobalErrorMessage = message
        root.post {
            if (!activity.isFinishing && !activity.isDestroyed) {
                showChoiceDialog(
                    title = "Opah needs attention",
                    explanation = message,
                    choices = listOf(Triple("dismiss", "Dismiss", "Return to Opah")),
                ) { viewModel.clearError() }
            }
        }
    }

    private fun scheduleLiveActionMessageClear(state: Phase0UiState) {
        val message = state.liveActions.errorMessage ?: state.liveActions.message
        if (message == null) {
            liveActionMessageJob?.cancel()
            liveActionMessageJob = null
            scheduledLiveActionMessage = null
            return
        }
        if (message == scheduledLiveActionMessage) return
        liveActionMessageJob?.cancel()
        scheduledLiveActionMessage = message
        liveActionMessageJob = activity.lifecycleScope.launch {
            delay(LIVE_ACTION_MESSAGE_DISPLAY_MILLIS)
            if (scheduledLiveActionMessage == message) {
                scheduledLiveActionMessage = null
                viewModel.clearLiveActionMessage()
            }
        }
    }

    private fun runProtectedAction(scope: PinScope, title: String, action: () -> Unit) {
        val privacy = currentState.privacy
        if (
            !privacy.pinConfigured ||
            scope !in privacy.protectedScopes ||
            scope in privacy.unlockedScopes
        ) {
            action()
            return
        }
        val pending = PendingProtectedAction(scope, title, action)
        pendingProtectedAction = pending
        showPinDialog(
            title = "Unlock $title",
            onCancelled = {
                if (pendingProtectedAction?.submitted == false) pendingProtectedAction = null
            },
        ) { pin ->
            pendingProtectedAction = pending.copy(submitted = true)
            viewModel.unlockPrivacy(pin)
        }
    }

    private fun resumeProtectedActionIfReady(state: Phase0UiState) {
        val pending = pendingProtectedAction ?: return
        if (!pending.submitted || state.privacy.busy) return
        if (pending.scope in state.privacy.unlockedScopes) {
            pendingProtectedAction = null
            root.post(pending.action)
            return
        }
        val error = state.privacy.errorMessage ?: return
        pendingProtectedAction = null
        root.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            showChoiceDialog(
                title = "PIN not accepted",
                explanation = error,
                choices = listOf(
                    Triple("retry", "Try again", "Enter the PIN again"),
                    Triple("cancel", "Cancel", "Do not continue with ${pending.title.lowercase()}"),
                ),
            ) { choice ->
                if (choice == "retry") runProtectedAction(pending.scope, pending.title, pending.action)
            }
        }
    }

    private fun showConnected(state: Phase0UiState) {
        setNavigationVisible(true)
        updateNavigationSelection()
        updateNavigationAppearance(root.navigationOpen)
        when (route.destination) {
            NativeDestination.HOME -> showHome(state)
            NativeDestination.ACTIVITY -> showActivity(state)
            NativeDestination.CLIPS -> showClips(state)
            NativeDestination.CONNECTIONS -> showConnections(state)
            NativeDestination.SETTINGS -> showSettings(state)
        }
        if (pendingDocumentationCameraChooser) {
            pendingDocumentationCameraChooser = false
            root.post(::showCreateCameraViewDialog)
        }
    }

    private fun showHome(state: Phase0UiState) {
        val snapshot = requireNotNull(state.snapshot)
        val cameras = orderedCameras(snapshot.cameras, state.settings.favoriteCameraNames)
            .filterNot { it.name in state.settings.hiddenHomeCameraNames }
        val availableNames = snapshot.cameras.mapTo(mutableSetOf(), Camera::name)
        val tiles = buildList {
            state.settings.savedCameraViews
                .filter { saved -> saved.cameraNames.all(availableNames::contains) }
                .forEach { saved ->
                    val key = "saved:${saved.id}"
                    add(
                        NativeHomeTile(
                            key = key,
                            title = saved.name,
                            subtitle = "Saved view • ${saved.cameraNames.size} cameras",
                            kind = NativeHomeTileKind.SAVED_VIEW,
                            sourceId = saved.id,
                            previewCameraName = saved.cameraNames.firstOrNull(),
                            cameraNames = saved.cameraNames,
                            favorite = key in state.settings.favoriteViewIds,
                        ),
                    )
                }
            snapshot.cameraGroups.sortedBy { it.order }
                .filter { group -> group.cameraNames.size >= 2 && group.cameraNames.all(availableNames::contains) }
                .forEach { group ->
                    val key = "group:${group.name}"
                    val cameraCount = group.cameraNames.size
                    add(
                        NativeHomeTile(
                            key = key,
                            title = group.displayName,
                            subtitle = if (cameraCount <= 4) {
                                "Camera group • $cameraCount cameras"
                            } else {
                                "Camera group • $cameraCount cameras • Choose up to four"
                            },
                            kind = NativeHomeTileKind.CAMERA_GROUP,
                            sourceId = group.name,
                            previewCameraName = group.cameraNames.firstOrNull(),
                            cameraNames = group.cameraNames,
                            favorite = key in state.settings.favoriteViewIds,
                        ),
                    )
                }
            if (snapshot.birdseye.playable) {
                add(
                    NativeHomeTile(
                        key = "birdseye",
                        title = "All cameras",
                        subtitle = "Birdseye",
                        kind = NativeHomeTileKind.BIRDSEYE,
                        previewCameraName = cameras.firstOrNull()?.name,
                    ),
                )
            }
            cameras.forEach { camera ->
                add(
                    NativeHomeTile(
                        key = "camera:${camera.name}",
                        title = camera.displayName,
                        subtitle = state.health.messagesByCamera[camera.name] ?: "Camera preview",
                        kind = NativeHomeTileKind.CAMERA,
                        sourceId = camera.name,
                        previewCameraName = camera.name,
                        cameraNames = listOf(camera.name),
                        favorite = camera.name in state.settings.favoriteCameraNames,
                    ),
                )
            }
        }
        val quickActions = buildList {
            val unreviewed = state.review.counts.unreviewedAlerts
            add(
                NativeHomeQuickAction(
                    key = "activity",
                    title = "Activity",
                    detail = if (unreviewed > 0) "Unreviewed alerts from the last 24 hours" else "Review recent camera activity",
                    iconRes = R.drawable.ic_review,
                    accentColor = NativeTheme.palette.activityBadge,
                    value = if (unreviewed > 0) "$unreviewed new" else "Caught up",
                    onClick = { navigate(NativeDestination.ACTIVITY) },
                ),
            )
            state.briefing.summary?.let { briefing ->
                add(
                    NativeHomeQuickAction(
                        key = "briefing",
                        title = "Highlights",
                        detail = state.briefing.errorMessage ?: "${briefing.headline} • ${briefing.detail}",
                        iconRes = R.drawable.ic_play,
                        accentColor = NativeTheme.palette.focus,
                        value = if (state.briefing.loading) "Saving" else "${briefing.entries.size} clips",
                        enabled = !state.briefing.loading,
                        onClick = ::showBriefingActions,
                    ),
                )
            }
            if (snapshot.capabilities.supports(FrigateFeature.PROFILE_MODES)) {
                add(
                    NativeHomeQuickAction(
                        key = "mode",
                        title = "Mode",
                        detail = state.modes.statusMessage ?: state.modes.errorMessage ?: "Change how Frigate is operating",
                        iconRes = R.drawable.ic_mode,
                        accentColor = NativeTheme.palette.activityBadge,
                        value = state.modes.activeMode?.humanize() ?: if (state.modes.loading) "Loading" else "Choose",
                        enabled = !state.modes.loading && !state.modes.switching,
                        onClick = ::showModeActions,
                    ),
                )
            }
            if (cameras.size >= 2) {
                add(
                    NativeHomeQuickAction(
                        key = "create-view",
                        title = "New view",
                        detail = "Put two to four cameras together",
                        iconRes = R.drawable.ic_videocam,
                        accentColor = NativeTheme.palette.text,
                        value = "Create",
                        onClick = ::showCreateCameraViewDialog,
                    ),
                )
                add(
                    NativeHomeQuickAction(
                        key = "monitor",
                        title = "Monitor",
                        detail = "Automatically bring important activity forward",
                        iconRes = R.drawable.ic_picture_in_picture,
                        accentColor = NativeTheme.palette.focus,
                        value = "Start",
                        onClick = {
                            val cameraNames = cameras.map(Camera::name)
                            if (cameraNames.size <= 4) {
                                viewModel.startMonitorModeForView("All cameras", cameraNames)
                            } else {
                                showCameraGroupChooserDialog("Monitor mode", cameraNames, preferMonitor = true)
                            }
                        },
                    ),
                )
            }
            if (state.settings.hiddenHomeCameraNames.isNotEmpty()) {
                add(
                    NativeHomeQuickAction(
                        key = "restore",
                        title = "Restore",
                        detail = "Restore the default camera layout",
                        iconRes = R.drawable.ic_retry,
                        accentColor = NativeTheme.palette.secondaryText,
                        value = "Cameras",
                        onClick = viewModel::restoreHomeDefaults,
                    ),
                )
            }
        }
        val homeState = NativeHomeUiState(
            subtitle = "${snapshot.cameras.size} cameras • ${state.review.counts.unreviewedAlerts} new alerts",
            tiles = tiles.sortedBy { if (it.kind == NativeHomeTileKind.CAMERA) 0 else 1 },
            quickActions = quickActions,
        )
        if (activeSurfaceKey == "home:dashboard" && activeHome != null) {
            activeHome?.update(homeState)
            return
        }
        clearActiveSurface()
        setContentInsets(fullScreen = false)
        activeSurfaceKey = "home:dashboard"
        val surface = NativeHomeSurface(
            activity = activity,
            initialState = homeState,
            imageLoadedAtMillis = { cameraName -> viewModel.cachedCameraImage(cameraName)?.loadedAtMillis },
            cachedBitmap = { cameraName -> viewModel.cachedCameraImage(cameraName)?.bitmap },
            refreshBitmap = { cameraName, height -> viewModel.refreshCameraImage(cameraName, height).getOrNull()?.bitmap },
            onPrimary = ::activateHomeTile,
            onOptions = ::showHomeTileOptions,
        )
        activeHome = surface
        contentHost.removeAllViews()
        contentHost.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        homeBookmark?.let { surface.restoreBookmark(it, requestFocus = !root.navigationOpen) }
    }

    private fun activateHomeTile(tile: NativeHomeTile) {
        val snapshot = currentState.snapshot ?: return
        when (tile.kind) {
            NativeHomeTileKind.CAMERA -> snapshot.cameras.firstOrNull { it.name == tile.sourceId }?.let(viewModel::playAutomatic)
            NativeHomeTileKind.BIRDSEYE -> viewModel.playBirdseye()
            NativeHomeTileKind.CAMERA_GROUP -> {
                tile.sourceId?.let { id -> viewModel.rememberLastViewedTarget(StartupTarget(StartupTargetKind.CAMERA_GROUP, id)) }
                if (tile.cameraNames.size <= 4) {
                    viewModel.openCameraGroup(tile.title, tile.cameraNames)
                } else {
                    showCameraGroupChooserDialog(tile.title, tile.cameraNames)
                }
            }
            NativeHomeTileKind.SAVED_VIEW -> {
                tile.sourceId?.let { id -> viewModel.rememberLastViewedTarget(StartupTarget(StartupTargetKind.SAVED_VIEW, id)) }
                viewModel.openCameraGroup(tile.title, tile.cameraNames)
            }
        }
    }

    private fun showHomeTileOptions(tile: NativeHomeTile) {
        val state = currentState
        when (tile.kind) {
            NativeHomeTileKind.CAMERA -> {
                val cameraName = tile.sourceId ?: return
                val camera = state.snapshot?.cameras?.firstOrNull { it.name == cameraName } ?: return
                val favoriteIndex = state.settings.favoriteCameraNames.indexOf(cameraName)
                val choices = buildList {
                    if (viewModel.canPlayUsingHttps(camera)) {
                        add(Triple("https-live", "Play using HTTPS", "Use this connection for this viewing session"))
                    }
                    add(Triple("refresh", "Refresh preview", ""))
                    add(Triple("favorite", if (tile.favorite) "Remove favorite" else "Pin as favorite", ""))
                    if (favoriteIndex > 0) add(Triple("earlier", "Move favorite earlier", ""))
                    if (favoriteIndex >= 0 && favoriteIndex < state.settings.favoriteCameraNames.lastIndex) {
                        add(Triple("later", "Move favorite later", ""))
                    }
                    if (state.snapshot.ptzCameras.containsKey(cameraName)) {
                        add(Triple("controls", "Camera controls", "Pan, tilt, zoom, and presets"))
                    }
                    add(Triple("hide", "Hide from Cameras", "You can restore hidden cameras from Cameras"))
                }
                showChoiceDialog(camera.displayName, "Camera options", choices) { choice ->
                    when (choice) {
                        "https-live" -> viewModel.playUsingHttps(camera)
                        "refresh" -> activeHome?.refreshPreview(tile.key)
                        "favorite" -> viewModel.toggleFavoriteCamera(cameraName)
                        "earlier" -> viewModel.moveFavoriteCamera(cameraName, -1)
                        "later" -> viewModel.moveFavoriteCamera(cameraName, 1)
                        "controls" -> runProtectedAction(
                            PinScope.ADMINISTRATIVE_ACTIONS,
                            "camera controls",
                        ) { viewModel.openPtzControls(camera) }
                        "hide" -> viewModel.hideCameraFromHome(cameraName)
                    }
                }
            }
            NativeHomeTileKind.CAMERA_GROUP,
            NativeHomeTileKind.SAVED_VIEW,
            -> {
                val favoriteIndex = state.settings.favoriteViewIds.indexOf(tile.key)
                val choices = buildList {
                    add(Triple("refresh", "Refresh preview", ""))
                    add(Triple("favorite", if (tile.favorite) "Remove favorite" else "Pin as favorite", ""))
                    if (favoriteIndex > 0) add(Triple("earlier", "Move favorite earlier", ""))
                    if (favoriteIndex >= 0 && favoriteIndex < state.settings.favoriteViewIds.lastIndex) {
                        add(Triple("later", "Move favorite later", ""))
                    }
                    add(Triple("monitor", "Start Monitor mode", "Automatically bring important activity forward"))
                    if (tile.kind == NativeHomeTileKind.SAVED_VIEW) {
                        add(Triple("delete", "Delete saved view", "The cameras themselves are not changed"))
                    }
                }
                showChoiceDialog(tile.title, "View options", choices) { choice ->
                    when (choice) {
                        "refresh" -> activeHome?.refreshPreview(tile.key)
                        "favorite" -> viewModel.toggleFavoriteView(tile.key)
                        "earlier" -> viewModel.moveFavoriteView(tile.key, -1)
                        "later" -> viewModel.moveFavoriteView(tile.key, 1)
                        "monitor" -> if (tile.cameraNames.size <= 4) {
                            viewModel.startMonitorModeForView(tile.title, tile.cameraNames)
                        } else {
                            showCameraGroupChooserDialog(tile.title, tile.cameraNames, preferMonitor = true)
                        }
                        "delete" -> tile.sourceId?.let { viewId ->
                            runProtectedAction(PinScope.DESTRUCTIVE_ACTIONS, "deleting saved views") {
                                viewModel.deleteCameraView(viewId)
                            }
                        }
                    }
                }
            }
            NativeHomeTileKind.BIRDSEYE -> showChoiceDialog(tile.title, "View options",
                listOf(Triple("refresh", "Refresh preview", ""))) { activeHome?.refreshPreview(tile.key) }
        }
    }

    private fun showBriefingActions() {
        if (currentState.briefing.loading) return
        val briefing = currentState.briefing.summary ?: return
        showChoiceDialog(
            title = briefing.headline,
            explanation = currentState.briefing.errorMessage ?: briefing.detail,
            choices = listOf(
                Triple("play", "Play highlights", "${briefing.entries.size} clips play automatically"),
                Triple("review", "Review as a list", "Open the highlighted activity"),
                Triple("dismiss", "Dismiss this summary", "It will not be shown again"),
            ),
        ) { choice ->
            when (choice) {
                "play" -> viewModel.playBriefingHighlights()
                "review" -> {
                    destinationLocations[route.destination] = currentLocation()
                    applyLocation(NativeLocation(NativeRoute(NativeDestination.ACTIVITY)))
                    viewModel.openBriefingReviewQueue()
                    render(viewModel.state.value)
                }
                "dismiss" -> viewModel.dismissBriefing()
            }
        }
    }

    private fun showCreateCameraViewDialog() {
        val cameras = currentState.snapshot?.cameras.orEmpty()
        if (cameras.size < 2) return
        showNativeCameraViewDialog(activity, createDialog(), cameras) { name, selected ->
            viewModel.saveCameraView(name, selected)
        }
    }

    private fun showCameraGroupChooserDialog(
        title: String,
        cameraNames: List<String>,
        preferMonitor: Boolean = false,
    ) {
        val cameras = currentState.snapshot?.cameras.orEmpty()
            .filter { it.name in cameraNames }
        if (cameras.size < 2) return
        val dialog = createDialog()
        val selected = linkedSetOf<String>()
        val explanation = TextView(activity).apply {
            textSize = 14f
            setTextColor(NativeTheme.palette.secondaryText)
            setPadding(0, activity.dp(4), 0, activity.dp(10))
        }
        val recycler = RecyclerView(activity).apply {
            layoutManager = NativeLinearLayoutManager(activity)
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val watchButton = dialogButton("Watch") {
            if (selected.size in 2..4) {
                dialog.dismiss()
                viewModel.openCameraGroup(title, selected.toList())
            }
        }
        val monitorButton = dialogButton("Start Monitor mode") {
            if (selected.size in 2..4) {
                dialog.dismiss()
                viewModel.startMonitorModeForView(title, selected.toList())
            }
        }
        lateinit var adapter: NativeListAdapter
        fun updateChoices(restoreKey: String? = null) {
            explanation.text = activity.resources.getQuantityString(
                R.plurals.native_camera_selection_count,
                selected.size,
                selected.size,
            )
            val valid = selected.size in 2..4
            watchButton.isEnabled = valid
            watchButton.alpha = if (valid) 1f else 0.42f
            monitorButton.isEnabled = valid
            monitorButton.alpha = if (valid) 1f else 0.42f
            val models = cameras.map { camera ->
                val chosen = camera.name in selected
                NativeRowModel(
                    key = "camera:${camera.name}",
                    title = camera.displayName,
                    description = if (chosen) "Included in this view" else "Not included",
                    kind = NativeRowKind.CHOICE,
                    enabled = chosen || selected.size < 4,
                    selected = chosen,
                )
            }
            adapter.submitList(models) {
                val position = models.indexOfFirst { it.key == restoreKey }.takeIf { it >= 0 } ?: 0
                recycler.post {
                    recycler.scrollToPosition(position)
                    recycler.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
                }
            }
        }
        adapter = NativeListAdapter(
            onActivate = { key ->
                val cameraName = key.removePrefix("camera:")
                if (cameraName in selected) selected.remove(cameraName) else if (selected.size < 4) selected.add(cameraName)
                updateChoices(key)
            },
            onFocused = { _, _ -> Unit },
        )
        recycler.addNativeFlatDividers()
        recycler.adapter = adapter
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(22), activity.dp(18), activity.dp(22), activity.dp(18))
            background = activity.nativeGlassPanelBackground(strong = true)
            addView(TextView(activity).apply {
                text = title
                textSize = 22f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(NativeTheme.palette.text)
            })
            addView(explanation)
            addView(
                recycler,
                LinearLayout.LayoutParams(
                    activity.dp(680),
                    activity.dp(nativeDialogListHeightDp(cameras.size)),
                ),
            )
            addView(
                LinearLayout(activity).apply {
                    gravity = Gravity.END
                    addView(dialogButton("Cancel") { dialog.dismiss() })
                    addView(watchButton)
                    addView(monitorButton)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(58)).apply {
                    topMargin = activity.dp(10)
                },
            )
        }
        dialog.setContentView(container)
        dialog.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        dialog.setOnShowListener {
            dialog.window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            updateChoices()
            if (preferMonitor) monitorButton.contentDescription = "Start Monitor mode after choosing two to four cameras"
        }
        dialog.show()
    }

    private fun showModeActions() {
        val modes = currentState.modes
        if (!modes.loadedOnce) {
            viewModel.loadModes()
            return
        }
        val canSwitch = currentState.snapshot?.capabilities?.supports(FrigateFeature.PROFILE_MODE_SWITCH) == true
        if (!canSwitch || modes.switching) return
        val choices = buildList {
            add(Triple("__default__", "Default", if (modes.activeMode == null) "Selected" else ""))
            modes.modes.forEach { mode ->
                add(Triple(mode.name, mode.displayName, if (modes.activeMode == mode.name) "Selected" else ""))
            }
            if (modes.undoAvailable) add(Triple("__undo__", "Undo last mode change", ""))
        }
        showChoiceDialog("Choose a Mode", "Modes change Frigate behavior", choices) { choice ->
            when (choice) {
                "__default__" -> runProtectedAction(PinScope.ADMINISTRATIVE_ACTIONS, "Mode changes") {
                    viewModel.switchMode(null)
                }
                "__undo__" -> runProtectedAction(PinScope.ADMINISTRATIVE_ACTIONS, "Mode changes") {
                    viewModel.undoModeSwitch()
                }
                else -> runProtectedAction(PinScope.ADMINISTRATIVE_ACTIONS, "Mode changes") {
                    viewModel.switchMode(choice)
                }
            }
        }
    }

    private fun showActivity(state: Phase0UiState) {
        if (
            state.privacy.pinConfigured &&
            PinScope.ACTIVITY_HISTORY_SEARCH in state.privacy.protectedScopes &&
            PinScope.ACTIVITY_HISTORY_SEARCH !in state.privacy.unlockedScopes
        ) {
            showProtectedContentGate(
                surfaceKey = "activity-tools",
                title = "Activity tools are locked",
                description = "Enter the Opah PIN to use history and search on this TV",
            )
            return
        }
        if (
            activityPage == NativeActivityPage.MOTION &&
            state.snapshot?.capabilities?.supports(FrigateFeature.MOTION_SEARCH) != true
        ) {
            activityPage = NativeActivityPage.NEW
        }
        when (activityPage) {
            NativeActivityPage.NEW -> showReviewActivity(state, reviewed = false)
            NativeActivityPage.REVIEWED -> showReviewActivity(state, reviewed = true)
            NativeActivityPage.HISTORY -> showActivityHistory(state)
            NativeActivityPage.SEARCH -> showActivitySearch(state)
            NativeActivityPage.MOTION -> showMotionSearch(state)
        }
    }

    private fun showReviewActivity(state: Phase0UiState, reviewed: Boolean) {
        if (!state.review.loadedOnce && !state.review.loading) viewModel.loadReview()
        val visibleItems = state.review.visibleReviewItems(reviewed)
        val visibleItemIds = visibleItems.map(ReviewItem::id)
        val rows = buildList {
            visibleItems.forEach { item -> add(reviewRow("activity:item", item, state, visibleItemIds)) }
            if (!state.review.queueActive && (state.review.hasMore || state.review.loadingMore)) {
                add(action("activity:load-more", if (state.review.loadingMore) "Loading more activity" else "Load more activity",
                    enabled = !state.review.loadingMore) { viewModel.loadMoreReview() })
            }
        }
        val details = visibleItems.associate { item ->
            val objectLabel = (item.objects + item.audio).distinct().take(4)
                .joinToString(", ") { it.replace('_', ' ').replaceFirstChar(Char::uppercase) }
                .ifBlank { "Camera activity" }
            val relative = DateUtils.getRelativeTimeSpanString(
                (item.startTime * 1_000.0).toLong(),
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            ).toString()
            val actions = buildList {
                state.review.actionErrorFor(item.id)?.takeIf { it.canRetry }?.let { failure ->
                    add(action("activity:detail:retry:${item.id}", "Retry", failure.message,
                        enabled = !state.review.hasPendingWrite()) {
                        retryReviewAction(item, failure)
                    })
                }
                add(
                    action(
                        "activity:detail:play:${item.id}",
                        "Play recording",
                        enabled = item.recordingAvailable != false,
                    ) { viewModel.playReview(item, visibleActivityItemIds = visibleItemIds) },
                )
                add(
                    action(
                        "activity:detail:reviewed:${item.id}",
                        if (item.hasBeenReviewed) "Mark unreviewed" else "Mark reviewed",
                        value = if (item.hasBeenReviewed) "Reviewed" else "New",
                        enabled = !state.review.hasPendingWrite(),
                    ) { viewModel.setReviewReviewed(item, !item.hasBeenReviewed) },
                )
                if (item.recordingAvailable != false) {
                    val saved = item.id in state.review.savedClipItemIds
                    add(
                        action(
                            "activity:detail:save:${item.id}",
                            if (saved) "Recording saved" else "Save recording",
                            enabled = !saved && !state.review.hasPendingWrite(),
                            selected = saved,
                        ) {
                            runProtectedAction(PinScope.ADMINISTRATIVE_ACTIONS, "saving recordings") {
                                viewModel.saveReviewClip(item)
                            }
                        },
                    )
                    if (
                        state.snapshot?.capabilities?.supports(FrigateFeature.MULTI_CAMERA_EXPORT) == true &&
                        state.snapshot.cameras.size > 1
                    ) {
                        add(
                            action(
                                "activity:detail:save-all:${item.id}",
                                "Save all camera angles",
                                "Choose cameras covering the same moment",
                                enabled = !state.review.hasPendingWrite(),
                            ) { showSaveAllAnglesDialog(item) },
                        )
                    }
                }
                if (state.snapshot?.capabilities?.supports(FrigateFeature.SEMANTIC_SEARCH) == true &&
                    item.activitySimilaritySeed() != null) {
                    add(action("activity:detail:similar:${item.id}", "Find similar activity") {
                        if (viewModel.findSimilarActivity(item)) {
                            activitySearchDraftFilters = null
                            activityPage = NativeActivityPage.SEARCH
                            render(viewModel.state.value.projectForPrivacy())
                        }
                    })
                }
            }
            val body = buildList {
                item.zones.takeIf(List<String>::isNotEmpty)?.let { add("Zones: ${it.joinToString { zone -> zone.humanize() }}") }
                item.summary?.shortSummary?.takeIf(String::isNotBlank)?.let(::add)
                add(if (item.hasBeenReviewed) "Reviewed" else "Not reviewed")
            }.joinToString(" • ")
            "activity:item:${item.id}" to NativeBrowserDetail(
                key = item.id,
                title = cameraDisplayName(item.camera, state),
                subtitle = "$objectLabel • $relative",
                body = body,
                errorMessage = state.review.actionErrorFor(item.id)?.message,
                thumbnail = NativeThumbnailRequest(NativeThumbnailKind.REVIEW, item.id),
                actions = actions,
            )
        }
        val pageLabel = if (reviewed) "Reviewed" else "New"
        val count = visibleItems.size
        showMediaBrowser(
            surfaceKey = "activity:${if (reviewed) "reviewed" else "new"}",
            state = NativeBrowserUiState(
                title = "Activity",
                subtitle = when {
                    state.review.loading -> "Loading activity"
                    state.review.errorMessage != null -> state.review.errorMessage
                    state.review.queueActive -> "${state.review.queueTitle} • $count ${if (count == 1) "item" else "items"} in this review"
                    state.review.queueCompleted -> "Review complete"
                    else -> "$count ${pageLabel.lowercase()} ${if (count == 1) "item" else "items"} • ${state.review.filters.timeRange.displayName}"
                },
                tabs = activityTabs(state),
                tools = activityTools(state),
                rows = rows,
                details = details,
                preferredSelectionKey = state.review.selectedItemId?.takeIf { state.review.queueActive }?.let { "activity:item:$it" },
                // The first load may begin after this render's state snapshot was collected.
                loading = state.review.loading || (!state.review.loadedOnce &&
                    (viewModel.state.value.review.loading || viewModel.state.value.review.loadedOnce)),
                emptyTitle = if (state.review.loading) "Loading activity" else "No ${pageLabel.lowercase()} activity",
                emptyBody = if (reviewed) {
                    "Activity you review or finish playing will appear here"
                } else {
                    "New camera activity will appear here"
                },
            ),
        )
    }

    private fun retryReviewAction(item: ReviewItem, error: ReviewActionError) {
        if (error.itemId != item.id) return
        when (error.action) {
            ReviewAction.MARK_REVIEWED -> viewModel.setReviewReviewed(item, error.reviewed)
            ReviewAction.SAVE_CLIP -> runProtectedAction(PinScope.ADMINISTRATIVE_ACTIONS, "saving recordings") {
                viewModel.saveReviewClip(item)
            }
            ReviewAction.SAVE_ALL_ANGLES -> if (error.cameraNames.isEmpty()) showSaveAllAnglesDialog(item) else {
                runProtectedAction(PinScope.ADMINISTRATIVE_ACTIONS, "saving recordings") {
                    viewModel.saveReviewAllAngles(item, error.cameraNames)
                }
            }
        }
    }

    private fun activityTabs(state: Phase0UiState): List<NativeBrowserTab> {
        fun page(key: String, label: String, page: NativeActivityPage, enabled: Boolean = true): NativeBrowserTab {
            handlers[key] = {
                activityPage = page
                when (page) {
                    NativeActivityPage.NEW,
                    NativeActivityPage.REVIEWED,
                    -> viewModel.loadReview()
                    NativeActivityPage.HISTORY -> if (!currentState.history.loadedOnce) viewModel.loadHistory()
                    NativeActivityPage.SEARCH,
                    NativeActivityPage.MOTION,
                    -> Unit
                }
                render(currentState)
            }
            return NativeBrowserTab(key, label, activityPage == page, enabled)
        }
        handlers["activity:refresh"] = {
            when (activityPage) {
                NativeActivityPage.NEW,
                NativeActivityPage.REVIEWED,
                -> viewModel.loadReview()
                NativeActivityPage.HISTORY -> viewModel.loadHistory()
                NativeActivityPage.SEARCH -> viewModel.repeatActivitySearch(
                    activitySearchDraftFilters ?: state.activitySearch.filters,
                )
                NativeActivityPage.MOTION -> Unit
            }
        }
        handlers["activity:filter"] = ::showActivityFilterActions
        return buildList {
            add(page("activity:page:new", "New", NativeActivityPage.NEW))
            add(page("activity:page:reviewed", "Reviewed", NativeActivityPage.REVIEWED))
            add(page("activity:page:history", "History", NativeActivityPage.HISTORY))
            add(page("activity:page:search", "Search", NativeActivityPage.SEARCH))
            add(page(
                "activity:page:motion",
                "Motion",
                NativeActivityPage.MOTION,
                state.snapshot?.capabilities?.supports(FrigateFeature.MOTION_SEARCH) == true,
            ))
        }
    }

    private fun activityTools(state: Phase0UiState): List<NativeBrowserTool> = when {
        state.review.queueActive && activityPage in listOf(NativeActivityPage.NEW, NativeActivityPage.REVIEWED) -> {
            handlers["activity:leave-queue"] = { viewModel.endReviewQueue(); viewModel.loadReview() }
            listOf(NativeBrowserTool("activity:leave-queue", "All activity", "Leave this review"))
        }
        else -> when (activityPage) {
        NativeActivityPage.NEW -> {
            val count = state.review.unreviewedShownActivity().size
            handlers["activity:mark-all"] = { showMarkShownActivityReviewedConfirmation(count) }
            listOf(
                NativeBrowserTool("activity:filter", "Filter", state.review.filters.summaryWithoutStatus()),
                NativeBrowserTool(
                    "activity:mark-all",
                    if (state.review.markingAllReviewed) "Saving" else "Mark all reviewed",
                    if (count > 0) "$count new" else "Caught up",
                    enabled = count > 0 && !state.review.hasPendingWrite(),
                    primary = true,
                ),
                NativeBrowserTool("activity:refresh", "Refresh", enabled = !state.review.loading),
            )
        }
        NativeActivityPage.REVIEWED -> listOf(
            NativeBrowserTool("activity:filter", "Filter", state.review.filters.summaryWithoutStatus()),
            NativeBrowserTool("activity:refresh", "Refresh", enabled = !state.review.loading),
        )
        NativeActivityPage.HISTORY -> listOf(
            NativeBrowserTool("activity:refresh", "Refresh", enabled = !state.history.loading),
        )
        NativeActivityPage.SEARCH -> emptyList()
        NativeActivityPage.MOTION -> emptyList()
        }
    }

    private fun showActivityFilterActions() {
        val filters = currentState.review.filters
        val selected = filters.severity
        val unreviewedShownCount = currentState.review.unreviewedShownActivity().size
        showChoiceDialog(
            "Show activity",
            "Choose the activity type, camera, object, area, and time",
            buildList {
                add(Triple("alerts", "Alerts", if (selected == app.opah.tv.data.model.ReviewSeverity.ALERT) "Selected" else ""))
                add(Triple("detections", "Detections", if (selected == app.opah.tv.data.model.ReviewSeverity.DETECTION) "Selected" else ""))
                add(Triple("motion", "Significant motion", if (selected == app.opah.tv.data.model.ReviewSeverity.SIGNIFICANT_MOTION) "Selected" else ""))
                add(Triple("all", "All activity", if (selected == null) "Selected" else ""))
                add(Triple("camera", "Camera", filters.camera?.let { cameraDisplayName(it, currentState) } ?: "All cameras"))
                add(Triple("label", "Object", filters.label?.humanize() ?: "All objects"))
                add(Triple("zone", "Area", filters.zone?.humanize() ?: "All areas"))
                add(Triple("time", "Time", filters.timeRange.displayName))
                add(Triple("clear", "Clear detailed filters", "Keep the selected activity type"))
                if (unreviewedShownCount > 0 && !currentState.review.queueActive) {
                    add(Triple("queue", "Review all new activity", "$unreviewedShownCount items"))
                }
                if (currentState.review.hasMore && !currentState.review.loadingMore) {
                    add(Triple("more", "Load more", "More activity is available"))
                }
            },
        ) { choice ->
            when (choice) {
                "alerts" -> viewModel.updateReviewSeverity(app.opah.tv.data.model.ReviewSeverity.ALERT)
                "detections" -> viewModel.updateReviewSeverity(app.opah.tv.data.model.ReviewSeverity.DETECTION)
                "motion" -> viewModel.updateReviewSeverity(app.opah.tv.data.model.ReviewSeverity.SIGNIFICANT_MOTION)
                "all" -> viewModel.updateReviewSeverity(null)
                "camera" -> showReviewCameraFilter()
                "label" -> showReviewLabelFilter()
                "zone" -> showReviewZoneFilter()
                "time" -> showReviewTimeFilter()
                "clear" -> viewModel.applyReviewFilters(
                    filters.copy(
                        camera = null,
                        label = null,
                        zone = null,
                        timeRange = ReviewTimeRange.LAST_DAY,
                        reviewStatus = ReviewStatusFilter.ALL,
                    ),
                )
                "queue" -> {
                    activityPage = NativeActivityPage.NEW
                    viewModel.startReviewQueue()
                    render(viewModel.state.value)
                }
                "more" -> viewModel.loadMoreReview()
            }
        }
    }

    private fun showMarkShownActivityReviewedConfirmation(count: Int) {
        if (count <= 0 || currentState.review.hasPendingWrite()) return
        showChoiceDialog(
            title = "Mark $count ${if (count == 1) "item" else "items"} reviewed?",
            explanation = "This updates the new activity currently shown in Frigate",
            choices = listOf(
                Triple("cancel", "Keep as new", "Nothing changes"),
                Triple("confirm", "Mark reviewed", "You can change individual items later"),
            ),
        ) { choice ->
            if (choice == "confirm") viewModel.markAllShownActivityReviewed()
        }
    }

    private fun showReviewCameraFilter() {
        val selected = currentState.review.filters.camera
        val choices = listOf(Triple("__all__", "All cameras", if (selected == null) "Selected" else "")) +
            currentState.snapshot?.cameras.orEmpty().map { camera ->
                Triple(camera.name, camera.displayName, if (selected == camera.name) "Selected" else "")
            }
        showChoiceDialog("Choose camera", "Only show activity from one camera", choices) { value ->
            viewModel.applyReviewFilters(currentState.review.filters.copy(camera = value.takeUnless { it == "__all__" }))
        }
    }

    private fun showReviewLabelFilter() {
        val selected = currentState.review.filters.label
        val choices = listOf(Triple("__all__", "All objects", if (selected == null) "Selected" else "")) +
            currentState.review.knownLabels.sorted().map { label ->
                Triple(label, label.humanize(), if (selected == label) "Selected" else "")
            }
        showChoiceDialog("Choose object", "Filter using object labels already seen by this TV", choices) { value ->
            viewModel.applyReviewFilters(currentState.review.filters.copy(label = value.takeUnless { it == "__all__" }))
        }
    }

    private fun showReviewZoneFilter() {
        val selected = currentState.review.filters.zone
        val choices = listOf(Triple("__all__", "All areas", if (selected == null) "Selected" else "")) +
            currentState.review.knownZones.sorted().map { zone ->
                Triple(zone, zone.humanize(), if (selected == zone) "Selected" else "")
            }
        showChoiceDialog("Choose area", "Filter using Frigate zones already seen by this TV", choices) { value ->
            viewModel.applyReviewFilters(currentState.review.filters.copy(zone = value.takeUnless { it == "__all__" }))
        }
    }

    private fun showReviewTimeFilter() {
        val selected = currentState.review.filters.timeRange
        showChoiceDialog(
            "Choose recent period",
            "How far back Activity should look",
            ReviewTimeRange.entries.map { range ->
                Triple(range.name, range.displayName, if (selected == range) "Selected" else "")
            },
        ) { value ->
            viewModel.applyReviewFilters(currentState.review.filters.copy(timeRange = ReviewTimeRange.valueOf(value)))
        }
    }

    private fun showSaveAllAnglesDialog(item: ReviewItem, selectedCameraNames: Set<String> = setOf(item.camera)) {
        val cameras = currentState.snapshot?.cameras.orEmpty()
        if (cameras.isEmpty()) return
        val selected = selectedCameraNames.intersect(cameras.mapTo(linkedSetOf(), Camera::name)) + item.camera
        val displayNames = cameras.associate { it.name to it.displayName }
        val choices = buildList {
            add(
                Triple(
                    "__save__",
                    "Save ${selected.size} ${if (selected.size == 1) "angle" else "angles"}",
                    selected.joinToString(" • ") { displayNames[it] ?: it },
                ),
            )
            cameras.forEach { camera ->
                if (camera.name == item.camera) {
                    add(Triple("required:${camera.name}", camera.displayName, "Current camera • always included"))
                } else {
                    add(
                        Triple(
                            "camera:${camera.name}",
                            camera.displayName,
                            if (camera.name in selected) "Included • select to remove" else "Not included • select to add",
                        ),
                    )
                }
            }
        }
        showChoiceDialog(
            "Save all angles",
            "Choose cameras covering the same moment",
            choices,
        ) { choice ->
            when {
                choice == "__save__" -> runProtectedAction(
                    PinScope.ADMINISTRATIVE_ACTIONS,
                    "saving recordings",
                ) { viewModel.saveReviewAllAngles(item, selected) }
                choice.startsWith("camera:") -> {
                    val cameraName = choice.removePrefix("camera:")
                    showSaveAllAnglesDialog(
                        item,
                        if (cameraName in selected) selected - cameraName else selected + cameraName,
                    )
                }
                choice.startsWith("required:") -> showSaveAllAnglesDialog(item, selected)
            }
        }
    }

    private fun showActivityHistory(state: Phase0UiState) {
        if (!state.history.loadedOnce && !state.history.loading) viewModel.loadHistory()
        val cameraName = state.history.cameraName
        val cameraLabel = cameraName?.let { cameraDisplayName(it, state) } ?: "Camera"
        val hourStart = state.history.hourStartSeconds
        val slots = if (hourStart != null) {
            historySlots(hourStart, state.history.segments, System.currentTimeMillis() / 1_000.0)
        } else {
            emptyList()
        }
        val rows = slots.map { slot ->
            val key = nativeHistoryRowKey(cameraName, slot)
            action(
                key = key,
                title = "${formatTime(slot.startTime)}–${formatTime(slot.endTime)}",
                description = if (slot.available) "Recording available" else "No recording in this period",
                value = if (slot.available) "Play" else "Unavailable",
                enabled = slot.available,
            ) { viewModel.playHistorySlot(slot) }
        }
        val details = slots.map { slot ->
            val key = nativeHistoryRowKey(cameraName, slot)
            val motion = state.history.motion
                .filter { it.startTime >= slot.startTime && it.startTime < slot.endTime }
                .sumOf { it.motion }
            key to NativeBrowserDetail(
                key = key,
                title = cameraLabel,
                subtitle = "${formatDate(slot.startTime)} • ${formatTime(slot.startTime)}–${formatTime(slot.endTime)}",
                body = if (slot.available) "Recording available • ${motion.toInt()} motion points" else "Frigate reported no recording for this period",
                actions = buildList {
                    add(action("$key:play", "Play this period", enabled = slot.available) { viewModel.playHistorySlot(slot) })
                    if (cameraName != null && slot.available) {
                        add(action("$key:motion", "Find motion near here") {
                            viewModel.startMotionSearch(cameraName, slot.startTime)
                            activityPage = NativeActivityPage.MOTION
                            render(currentState)
                        })
                    }
                },
            )
        }.toMap()
        handlers["activity:history:camera"] = ::showHistoryCameraChoices
        handlers["activity:history:date"] = { showHistoryDateChoices() }
        handlers["activity:history:earlier"] = { viewModel.moveHistoryHour(-1) }
        handlers["activity:history:later"] = { viewModel.moveHistoryHour(1) }
        showMediaBrowser(
            "activity:history",
            NativeBrowserUiState(
                title = "Activity",
                subtitle = when {
                    state.history.loading -> "Loading recording history"
                    state.history.errorMessage != null -> state.history.errorMessage
                    hourStart != null -> "$cameraLabel • ${formatDate(hourStart)} • ${formatTime(hourStart)}"
                    else -> "Choose a camera to browse recordings"
                },
                tabs = activityTabs(state),
                tools = listOf(
                    NativeBrowserTool("activity:history:camera", "Camera", cameraLabel, field = true),
                    NativeBrowserTool("activity:history:date", "Date & time", enabled = !state.history.loading),
                    NativeBrowserTool("activity:history:earlier", "Earlier", enabled = !state.history.loading),
                    NativeBrowserTool("activity:history:later", "Later", enabled = !state.history.loading &&
                        hourStart != null && hourStart + 3_600.0 <= System.currentTimeMillis() / 1_000.0),
                    NativeBrowserTool("activity:refresh", "Refresh", enabled = !state.history.loading),
                ),
                rows = rows,
                details = details,
                loading = state.history.loading || (!state.history.loadedOnce &&
                    (viewModel.state.value.history.loading || viewModel.state.value.history.loadedOnce)),
                emptyTitle = if (state.history.loading) "Loading history" else "No recording periods found",
                emptyBody = "Choose another camera or hour",
            ),
        )
    }

    private fun showHistoryCameraChoices() {
        val cameras = currentState.snapshot?.cameras.orEmpty()
        showChoiceDialog(
            "History camera",
            "Choose which camera recording history to browse",
            cameras.map { camera -> Triple(camera.name, camera.displayName, if (camera.name == currentState.history.cameraName) "Selected" else "") },
        ) { cameraName -> viewModel.loadHistory(cameraName) }
    }

    private fun showHistoryDateChoices(
        anchorMillis: Long = currentState.history.hourStartSeconds?.let { (it * 1_000).toLong() }
            ?: System.currentTimeMillis(),
    ) {
        val now = System.currentTimeMillis()
        val zone = java.util.TimeZone.getDefault()
        val dates = historyDateChoices(anchorMillis, now, zone)
        if (dates.isEmpty()) return
        val today = historyDateChoices(now, now, zone).first()
        val yesterday = shiftHistoryDate(today, -1, zone)
        val selectedDate = currentState.history.hourStartSeconds?.let {
            historyDateChoices((it * 1_000).toLong(), now, zone).firstOrNull()
        }
        val choices = buildList {
            if (dates.first() != today) add(Triple("today", "Today", "Jump to today's recordings"))
            dates.forEach { day ->
                val label = when (day) {
                    today -> "Today"
                    yesterday -> "Yesterday"
                    else -> DateFormat.getDateInstance(DateFormat.FULL).apply { timeZone = zone }.format(Date(day))
                }
                add(Triple("history:date:$day", label, if (day == selectedDate) "Selected date" else "Choose an hour"))
            }
            if (shiftHistoryDate(dates.last(), -1, zone) >= 0L) {
                add(Triple("older", "Earlier dates", "Previous seven days"))
            }
            if (dates.first() != today) add(Triple("newer", "More recent dates", "Next seven days"))
        }
        showChoiceDialog("Recording date", "Choose a date in ${zone.id.replace('_', ' ')}", choices) { choice ->
            when (choice) {
                "today" -> showHistoryHourChoices(today)
                "older" -> showHistoryDateChoices(shiftHistoryDate(dates.last(), -1, zone))
                "newer" -> showHistoryDateChoices(shiftHistoryDate(dates.first(), 7, zone))
                else -> choice.removePrefix("history:date:").toLongOrNull()?.let(::showHistoryHourChoices)
            }
        }
    }

    private fun showHistoryHourChoices(dayMillis: Long) {
        val zone = java.util.TimeZone.getDefault()
        val hours = historyHourChoices(dayMillis, System.currentTimeMillis(), zone)
        val date = DateFormat.getDateInstance(DateFormat.FULL).apply { timeZone = zone }.format(Date(dayMillis))
        showChoiceDialog(
            "Recording time",
            "$date • ${zone.id.replace('_', ' ')}",
            hours.map { hour -> Triple("history:hour:${hour.startMillis}", hour.label, hour.zoneLabel) } +
                Triple("date", "Choose another date", "Return to dates"),
        ) { choice ->
            if (choice == "date") {
                showHistoryDateChoices(dayMillis)
            } else {
                choice.removePrefix("history:hour:").toLongOrNull()?.let { selected ->
                    // Recheck against the current clock before asking the existing history loader.
                    if (selected <= System.currentTimeMillis()) {
                        viewModel.loadHistory(currentState.history.cameraName, selected / 1_000.0)
                    }
                }
            }
        }
    }

    private fun showActivitySearch(state: Phase0UiState) {
        val search = state.activitySearch
        val filters = activitySearchDraftFilters ?: search.filters
        val capability = state.snapshot?.capabilities?.get(FrigateFeature.SEMANTIC_SEARCH)
        val available = capability?.available == true
        val resultRows = search.results.map { event ->
            val key = "activity:search:event:${event.id}"
            action(
                key = key,
                title = cameraDisplayName(event.camera, state),
                description = "${event.label.humanize()} • ${formatDateTime(event.startTime)}",
                value = if (event.hasClip) "Play" else "Details",
                thumbnail = NativeThumbnailRequest(NativeThumbnailKind.SEARCH, event.id),
            ) {
                if (event.hasClip) viewModel.playSearchEvent(event) else activeBrowser?.focusDetailActions()
            }
        }
        val resultDetails = search.results.associate { event ->
            val key = "activity:search:event:${event.id}"
            key to NativeBrowserDetail(
                key = event.id,
                title = event.label.humanize(),
                subtitle = "${cameraDisplayName(event.camera, state)} • ${formatDateTime(event.startTime)}",
                body = buildList {
                    event.subLabel?.takeIf(String::isNotBlank)?.let { add(it.humanize()) }
                    event.description?.takeIf(String::isNotBlank)?.let(::add)
                    if (event.zones.isNotEmpty()) add("Zones: ${event.zones.joinToString { it.humanize() }}")
                    event.recognizedLicensePlate?.let { add("Plate: $it") }
                }.joinToString(" • "),
                thumbnail = NativeThumbnailRequest(NativeThumbnailKind.SEARCH, event.id),
                actions = listOf(
                    action("$key:play", "Play recording", enabled = event.hasClip) { viewModel.playSearchEvent(event) },
                    action("$key:history", "Open recording history") {
                        viewModel.openHistoryAt(event.camera, event.startTime)
                        activityPage = NativeActivityPage.HISTORY
                        render(currentState)
                    },
                ),
            )
        }
        handlers["activity:search:query"] = { showSearchTextDialog(search.query) }
        handlers["activity:search:filters"] = ::showActivitySearchFilters
        handlers["activity:search:suggestions"] = ::showSearchSuggestions
        handlers["activity:search:recent"] = ::showRecentActivitySearches
        handlers["activity:search:more"] = { viewModel.loadMoreActivitySearch() }
        val filterCount = filters.activeFilterCount()
        val tools = if (available) buildList {
            add(
                NativeBrowserTool(
                    key = "activity:search:query",
                    label = "Search activity",
                    value = search.searchDescription.ifBlank { "Describe what you want to find" },
                    field = true,
                    primary = true,
                ),
            )
            add(
                NativeBrowserTool(
                    "activity:search:filters",
                    if (filterCount == 0) "Filters" else "Filters ($filterCount)",
                ),
            )
            add(NativeBrowserTool("activity:search:suggestions", "Ideas"))
            if (state.settings.recentActivitySearches.isNotEmpty()) {
                add(NativeBrowserTool("activity:search:recent", "Recent"))
            }
        } else {
            emptyList()
        }
        val moreRow = if (available && (search.hasMore || search.loadingMore)) {
            listOf(
                action(
                    "activity:search:more",
                    if (search.loadingMore) "Loading more results" else "Load more results",
                    "Continue this search",
                    enabled = !search.loadingMore,
                ) { viewModel.loadMoreActivitySearch() },
            )
        } else {
            emptyList()
        }
        val rows = resultRows + moreRow
        val details = resultDetails + if (moreRow.isNotEmpty()) {
            mapOf(
                "activity:search:more" to NativeBrowserDetail(
                    key = "search-more",
                    title = "More results",
                    subtitle = "Continue the current search",
                ),
            )
        } else {
            emptyMap()
        }
        val unavailableMessage = when (capability?.availability) {
            FrigateCapabilityAvailability.NOT_CONFIGURED -> "Activity search is not turned on in Frigate"
            FrigateCapabilityAvailability.NOT_PERMITTED -> "This Frigate account cannot use activity search"
            FrigateCapabilityAvailability.NOT_SUPPORTED -> "This Frigate server does not offer activity search"
            else -> "Activity search is not available from this server"
        }
        showMediaBrowser(
            "activity:search",
            NativeBrowserUiState(
                title = "Activity",
                subtitle = when {
                    !available -> unavailableMessage
                    search.searching -> if (search.isSimilaritySearch) "Finding similar activity" else "Searching Frigate"
                    search.errorMessage != null -> search.errorMessage
                    search.searchedOnce -> buildString {
                        append("${search.results.size} ${if (search.results.size == 1) "result" else "results"}")
                        append(if (search.isSimilaritySearch) " • ${search.searchDescription}" else " for ${search.query.ifBlank { "activity" }}")
                        filters.summary(state).takeIf(String::isNotBlank)?.let { append(" • $it") }
                    }
                    else -> "Search descriptions, objects, people, plates, and more"
                },
                tabs = activityTabs(state),
                tools = tools,
                rows = rows,
                details = details,
                loading = available && search.searching,
                emptyTitle = when {
                    !available -> "Search unavailable"
                    search.searching -> "Searching"
                    search.searchedOnce -> if (search.isSimilaritySearch) "No similar activity" else "No matching activity"
                    else -> "Start a search"
                },
                emptyBody = if (available) {
                    if (search.isSimilaritySearch) "Try another activity item or change the filters"
                    else "Enter a search or choose a suggestion"
                } else {
                    unavailableMessage
                },
            ),
        )
    }

    private fun showSearchTextDialog(initial: String) {
        showTextEntryDialog("Search activity", "Describe what you want to find", initial) { query ->
            if (query.isNotBlank()) {
                val filters = activitySearchDraftFilters ?: currentState.activitySearch.filters
                activitySearchDraftFilters = filters
                viewModel.searchActivity(query, filters)
            }
        }
    }

    private fun showSearchSuggestions() {
        showChoiceDialog(
            "Search suggestions",
            "Choose a common search or enter your own words",
            listOf("person", "car", "package", "animal", "activity").map { query -> Triple(query, query.humanize(), "") } +
                Triple("__custom__", "Enter my own search", ""),
        ) { choice ->
            if (choice == "__custom__") showSearchTextDialog("")
            else {
                val filters = activitySearchDraftFilters ?: currentState.activitySearch.filters
                activitySearchDraftFilters = filters
                viewModel.searchActivity(choice, filters)
            }
        }
    }

    private fun showRecentActivitySearches() {
        val searches = currentState.settings.recentActivitySearches
        if (searches.isEmpty()) return
        showChoiceDialog(
            "Recent searches",
            "Choose a previous search",
            searches.mapIndexed { index, query -> Triple(index.toString(), query, "Search again") },
        ) { index ->
            val query = searches.getOrNull(index.toIntOrNull() ?: -1) ?: return@showChoiceDialog
            val filters = activitySearchDraftFilters ?: currentState.activitySearch.filters
            activitySearchDraftFilters = filters
            viewModel.searchActivity(query, filters)
        }
    }

    private fun showActivitySearchFilters() {
        val filters = activitySearchDraftFilters ?: currentState.activitySearch.filters
        val results = currentState.activitySearch.results
        val labels = (currentState.review.knownLabels + results.map { it.label }).distinct().sorted()
        val subLabels = results.mapNotNull { it.subLabel?.takeIf(String::isNotBlank) }.distinct().sorted()
        val zones = (currentState.review.knownZones + results.flatMap { it.zones }).distinct().sorted()
        val plates = results.mapNotNull { it.recognizedLicensePlate?.trim()?.takeIf(String::isNotBlank) }.distinct().sorted()
        val choices = buildList {
            add(Triple("camera", "Camera", filters.cameraName?.let { cameraDisplayName(it, currentState) } ?: "All cameras"))
            if (labels.isNotEmpty()) add(Triple("label", "Object", filters.label?.humanize() ?: "All objects"))
            if (subLabels.isNotEmpty()) add(Triple("recognized", "Recognized", filters.subLabel?.humanize() ?: "All"))
            if (zones.isNotEmpty()) add(Triple("zone", "Area", filters.zone?.humanize() ?: "All areas"))
            if (plates.isNotEmpty()) add(Triple("plate", "License plate", filters.recognizedLicensePlate ?: "All"))
            add(Triple("time", "Time", filters.timeRange.displayName))
            add(Triple("clear", "Clear filters", "Show activity from every camera and time"))
            if (currentState.activitySearch.query.isNotBlank() || currentState.activitySearch.isSimilaritySearch) {
                add(Triple("apply", "Search with these filters", filters.summary(currentState).ifBlank { "No filters selected" }))
            }
        }
        showChoiceDialog("Search filters", "Choose only the details that matter for this search", choices) { choice ->
            when (choice) {
                "camera" -> showActivitySearchFilterChoice(
                    title = "Camera",
                    allLabel = "All cameras",
                    current = filters.cameraName,
                    values = currentState.snapshot?.cameras.orEmpty().map { it.name to it.displayName },
                ) { updateActivitySearchDraft(filters.copy(cameraName = it)) }
                "label" -> showActivitySearchFilterChoice(
                    "Object",
                    "All objects",
                    filters.label,
                    labels.map { it to it.humanize() },
                ) { updateActivitySearchDraft(filters.copy(label = it)) }
                "recognized" -> showActivitySearchFilterChoice(
                    "Recognized",
                    "All",
                    filters.subLabel,
                    subLabels.map { it to it.humanize() },
                ) { updateActivitySearchDraft(filters.copy(subLabel = it)) }
                "zone" -> showActivitySearchFilterChoice(
                    "Area",
                    "All areas",
                    filters.zone,
                    zones.map { it to it.humanize() },
                ) { updateActivitySearchDraft(filters.copy(zone = it)) }
                "plate" -> showActivitySearchFilterChoice(
                    "License plate",
                    "All plates",
                    filters.recognizedLicensePlate,
                    plates.map { it to it },
                ) { updateActivitySearchDraft(filters.copy(recognizedLicensePlate = it)) }
                "time" -> showChoiceDialog(
                    "Time",
                    "Choose when activity happened",
                    ActivitySearchTimeRange.entries.map { range ->
                        Triple(range.name, range.displayName, if (range == filters.timeRange) "Selected" else "")
                    },
                ) { value -> updateActivitySearchDraft(filters.copy(timeRange = ActivitySearchTimeRange.valueOf(value))) }
                "clear" -> updateActivitySearchDraft(ActivitySearchFilters())
                "apply" -> viewModel.repeatActivitySearch(filters)
            }
        }
    }

    private fun showActivitySearchFilterChoice(
        title: String,
        allLabel: String,
        current: String?,
        values: List<Pair<String, String>>,
        onSelected: (String?) -> Unit,
    ) {
        showChoiceDialog(
            title,
            "Choose one option",
            listOf(Triple("__all__", allLabel, if (current == null) "Selected" else "")) +
                values.map { (value, label) -> Triple(value, label, if (value == current) "Selected" else "") },
        ) { value -> onSelected(value.takeUnless { it == "__all__" }) }
    }

    private fun updateActivitySearchDraft(filters: ActivitySearchFilters) {
        activitySearchDraftFilters = filters
        render(currentState)
    }

    private fun showMotionSearch(state: Phase0UiState) {
        val motion = state.motionReview
        val cameraName = motion.cameraName ?: state.snapshot?.cameras?.firstOrNull()?.name
        val rows = motion.results.map { result ->
            val key = "activity:motion:result:$cameraName:${motion.regionIndex}:${result.timestamp}"
            action(
                key = key,
                title = formatDateTime(result.timestamp),
                description = "${result.changedAreaPercent.toInt()}% of ${motionRegionLabel(motion.regionIndex).lowercase(Locale.US)} changed",
                value = "Open history",
            ) {
                cameraName?.let { viewModel.openHistoryAt(it, result.timestamp) }
                activityPage = NativeActivityPage.HISTORY
                render(currentState)
            }
        }
        val uiState = NativeMotionSearchUiState(
            subtitle = when {
                motion.searching -> "Searching recordings${motion.progress?.let { " • ${(it * 100).toInt()}%" }.orEmpty()}"
                motion.errorMessage != null -> motion.errorMessage
                motion.searchedOnce -> "${motion.results.size} motion matches"
                else -> "Choose a camera and an area directly on the picture"
            },
            tabs = activityTabs(state),
            cameras = state.snapshot?.cameras.orEmpty().map { camera ->
                NativeMotionCamera(camera.name, camera.displayName)
            },
            selectedCameraName = cameraName,
            regionIndex = motion.regionIndex,
            searching = motion.searching,
            progress = motion.progress,
            resultRows = rows,
            emptyMessage = when {
                motion.searching -> "Searching the selected area"
                motion.searchedOnce -> "No motion was found in this area"
                else -> "Results will appear here"
            },
        )
        if (activeSurfaceKey == "activity:motion:visual" && activeMotionSearch != null) {
            activeMotionSearch?.update(uiState)
            return
        }
        clearActiveSurface()
        setContentInsets(fullScreen = false)
        activeSurfaceKey = "activity:motion:visual"
        val surface = NativeMotionSearchSurface(
            activity = activity,
            initialState = uiState,
            cachedBitmap = { name -> viewModel.cachedCameraImage(name)?.bitmap },
            refreshBitmap = { name, height -> viewModel.refreshCameraImage(name, height).getOrNull()?.bitmap },
            onActivate = { key -> handlers[key]?.invoke() },
            onCamera = viewModel::chooseMotionSearchCamera,
            onRegion = viewModel::chooseMotionSearchRegion,
            onSearch = {
                if (currentState.motionReview.searching) viewModel.cancelMotionSearch()
                else viewModel.startMotionSearch()
            },
            onFocused = { key, view ->
                focusMemory[route.focusMemoryKey] = key
                root.rememberContentFocus(view)
            },
            onNavigateOutLeft = root::openNavigation,
            initialBookmark = motionBookmark,
        )
        activeMotionSearch = surface
        contentHost.removeAllViews()
        contentHost.addView(
            surface,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    @Suppress("unused")
    private fun showMotionSearchLegacy(state: Phase0UiState) {
        val motion = state.motionReview
        val cameraName = motion.cameraName ?: state.snapshot?.cameras?.firstOrNull()?.name
        val cameraLabel = cameraName?.let { cameraDisplayName(it, state) } ?: "Choose camera"
        val rows = motion.results.mapIndexed { index, result ->
            val key = "activity:motion:result:$index"
            action(
                key,
                formatDateTime(result.timestamp),
                "${result.changedAreaPercent.toInt()}% of the selected area changed",
                "Open",
            ) {
                cameraName?.let { viewModel.openHistoryAt(it, result.timestamp) }
                activityPage = NativeActivityPage.HISTORY
                render(currentState)
            }
        }
        val details = motion.results.mapIndexed { index, result ->
            val key = "activity:motion:result:$index"
            key to NativeBrowserDetail(
                key = key,
                title = cameraLabel,
                subtitle = formatDateTime(result.timestamp),
                body = "${result.changedAreaPercent.toInt()}% of ${motionRegionLabel(motion.regionIndex).lowercase(Locale.US)} changed",
                actions = listOf(
                    action("$key:open", "Open recording history") {
                        cameraName?.let { viewModel.openHistoryAt(it, result.timestamp) }
                        activityPage = NativeActivityPage.HISTORY
                        render(currentState)
                    },
                ),
            )
        }.toMap()
        handlers["activity:motion:camera"] = ::showMotionCameraChoices
        handlers["activity:motion:region"] = ::showMotionRegionChoices
        handlers["activity:motion:start"] = {
            if (motion.searching) viewModel.cancelMotionSearch() else viewModel.startMotionSearch()
        }
        showMediaBrowser(
            "activity:motion",
            NativeBrowserUiState(
                title = "Activity",
                subtitle = when {
                    motion.searching -> "Searching recordings${motion.progress?.let { " • ${(it * 100).toInt()}%" }.orEmpty()}"
                    motion.errorMessage != null -> motion.errorMessage
                    motion.searchedOnce -> "${motion.results.size} motion matches • ${motionRegionLabel(motion.regionIndex)}"
                    else -> "Choose a camera and screen area, then search recorded video"
                },
                tabs = activityTabs(state),
                tools = listOf(
                    NativeBrowserTool("activity:motion:camera", "Camera", cameraLabel, enabled = !motion.searching, field = true),
                    NativeBrowserTool("activity:motion:region", "Area", motionRegionLabel(motion.regionIndex), enabled = !motion.searching),
                    NativeBrowserTool(
                        "activity:motion:start",
                        if (motion.searching) "Cancel search" else "Find motion",
                        primary = true,
                    ),
                ),
                rows = rows,
                details = details,
                loading = motion.searching,
                emptyTitle = if (motion.searching) "Searching for motion" else if (motion.searchedOnce) "No motion matches" else "Motion search",
                emptyBody = "Use the controls above to search a selected area",
            ),
        )
    }

    private fun showMotionCameraChoices() {
        val cameras = currentState.snapshot?.cameras.orEmpty()
        showChoiceDialog(
            "Motion search camera",
            "Choose a camera",
            cameras.map { Triple(it.name, it.displayName, if (it.name == currentState.motionReview.cameraName) "Selected" else "") },
        ) { viewModel.chooseMotionSearchCamera(it) }
    }

    private fun showMotionRegionChoices() {
        showChoiceDialog(
            "Screen area",
            "Choose which part of the picture should be checked for motion",
            (0..8).map { Triple(it.toString(), motionRegionLabel(it), if (it == currentState.motionReview.regionIndex) "Selected" else "") },
        ) { viewModel.chooseMotionSearchRegion(it.toInt()) }
    }

    private fun showMediaBrowser(surfaceKey: String, state: NativeBrowserUiState) {
        if (activeSurfaceKey == surfaceKey && activeBrowser != null) {
            activeBrowser?.update(state)
            return
        }
        clearActiveSurface()
        setContentInsets(fullScreen = false)
        activeSurfaceKey = surfaceKey
        val surface = NativeMediaBrowserSurface(
            activity = activity,
            initialState = state,
            onActivate = { key -> handlers[key]?.invoke() },
            onFocused = { key, view ->
                focusMemory[route.focusMemoryKey] = key
                root.rememberContentFocus(view)
            },
            onThumbnailRequested = ::loadThumbnail,
            onNavigateOutLeft = root::openNavigation,
            initialBookmark = browserBookmarks[surfaceKey],
        )
        activeBrowser = surface
        contentHost.removeAllViews()
        contentHost.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun showClips(state: Phase0UiState) {
        if (
            state.privacy.pinConfigured &&
            PinScope.CLIPS_AND_INCIDENTS in state.privacy.protectedScopes &&
            PinScope.CLIPS_AND_INCIDENTS !in state.privacy.unlockedScopes
        ) {
            showProtectedContentGate(
                surfaceKey = "clips",
                title = "Clips are locked",
                description = "Enter the Opah PIN to open saved recordings and incidents",
            )
            return
        }
        if (!state.exports.loadedOnce && !state.exports.loading) viewModel.loadExports()
        if (
            clipsPage == NativeClipsPage.INCIDENTS &&
            state.snapshot?.capabilities?.supports(FrigateFeature.EXPORT_CASES) != true
        ) {
            clipsPage = NativeClipsPage.RECORDINGS
        }
        when (clipsPage) {
            NativeClipsPage.RECORDINGS -> showRecordings(state)
            NativeClipsPage.INCIDENTS -> showIncidents(state)
        }
    }

    private fun clipsTabs(state: Phase0UiState): List<NativeBrowserTab> {
        val incidentsAvailable = state.snapshot?.capabilities?.supports(FrigateFeature.EXPORT_CASES) == true
        val mutationsAvailable = state.snapshot?.capabilities?.supports(FrigateFeature.INCIDENT_MUTATION) == true
        handlers["clips:page:recordings"] = { clipsPage = NativeClipsPage.RECORDINGS; render(currentState) }
        handlers["clips:refresh"] = { viewModel.loadExports(force = true) }
        return buildList {
            add(NativeBrowserTab("clips:page:recordings", "Recordings", clipsPage == NativeClipsPage.RECORDINGS))
            if (incidentsAvailable) {
                handlers["clips:page:incidents"] = { clipsPage = NativeClipsPage.INCIDENTS; render(currentState) }
                add(NativeBrowserTab("clips:page:incidents", "Incidents", clipsPage == NativeClipsPage.INCIDENTS))
                if (mutationsAvailable) {
                    handlers["clips:create-incident"] = { showCreateIncidentDialog() }
                    add(
                        NativeBrowserTab(
                            "clips:create-incident",
                            "New incident",
                            selected = false,
                            enabled = !state.exports.operationBusy,
                        ),
                    )
                }
            }
            add(
                NativeBrowserTab(
                    "clips:refresh",
                    "Refresh",
                    selected = false,
                    enabled = !state.exports.loading && !state.exports.operationBusy,
                ),
            )
        }
    }

    private fun showRecordings(state: Phase0UiState) {
        val snapshot = requireNotNull(state.snapshot)
        val mutationsAvailable = snapshot.capabilities.supports(FrigateFeature.INCIDENT_MUTATION)
        val canDelete = mutationsAvailable && canDeleteSavedRecordings(snapshot.user.role)
        val rows = state.exports.items.map { clip ->
            action(
                "clips:item:${clip.id}",
                clip.name.replace('_', ' '),
                "${cameraDisplayName(clip.camera, state)} • ${formatDateTime(clip.createdAt)}",
                if (clip.inProgress) "Saving" else "Play",
                enabled = !clip.inProgress,
                thumbnail = NativeThumbnailRequest(NativeThumbnailKind.CLIP, clip.id),
            ) { viewModel.playExport(clip) }
        }
        val details = state.exports.items.associate { clip ->
            val key = "clips:item:${clip.id}"
            val incident = state.exports.incidents.firstOrNull { it.id == clip.incidentId }
            key to NativeBrowserDetail(
                key = clip.id,
                title = clip.name.replace('_', ' '),
                subtitle = "${cameraDisplayName(clip.camera, state)} • ${formatDateTime(clip.createdAt)}",
                body = buildList {
                    add(if (clip.inProgress) "Frigate is still saving this recording" else "Saved in Frigate")
                    incident?.let { add("Incident: ${it.name}") }
                    state.exports.operationMessage?.let(::add)
                }.joinToString(" • "),
                thumbnail = NativeThumbnailRequest(NativeThumbnailKind.CLIP, clip.id),
                actions = buildList {
                    add(action("$key:play", "Play recording", enabled = !clip.inProgress) { viewModel.playExport(clip) })
                    if (mutationsAvailable) {
                        add(action("$key:rename", "Rename", enabled = !state.exports.operationBusy) { showRenameClipDialog(clip) })
                        add(action(
                            "$key:incident",
                            if (incident == null) "Add to incident" else "Change incident",
                            enabled = !state.exports.operationBusy,
                        ) {
                            showAssignIncidentDialog(clip)
                        })
                    }
                    add(
                        action(
                            "$key:share",
                            "Share recording",
                            enabled = !clip.inProgress && !state.exports.operationBusy,
                        ) { actions.onShareClip(clip) },
                    )
                    if (canDelete) {
                        add(
                            action(
                                "$key:delete",
                                if (state.exports.deletingItemId == clip.id) "Deleting recording" else "Delete recording",
                                enabled = state.exports.deletingItemId == null && !state.exports.operationBusy,
                            ) { showDeleteClipDialog(clip) },
                        )
                    }
                },
            )
        }
        showMediaBrowser(
            "clips:recordings",
            NativeBrowserUiState(
                title = "Clips",
                subtitle = when {
                    state.exports.loading -> "Loading saved recordings"
                    state.exports.errorMessage != null -> state.exports.errorMessage
                    else -> "${state.exports.items.size} saved recordings"
                },
                tabs = clipsTabs(state),
                rows = rows,
                details = details,
                loading = state.exports.loading || (!state.exports.loadedOnce &&
                    (viewModel.state.value.exports.loading || viewModel.state.value.exports.loadedOnce)),
                emptyTitle = if (state.exports.loading) "Loading clips" else "No saved recordings",
                emptyBody = "Recordings saved from Activity or live video will appear here",
            ),
        )
    }

    private fun showIncidents(state: Phase0UiState) {
        val mutationsAvailable = state.snapshot?.capabilities?.supports(FrigateFeature.INCIDENT_MUTATION) == true
        val rows = state.exports.incidents.map { incident ->
            val clipCount = state.exports.items.count { it.incidentId == incident.id }
            action(
                "clips:incident:${incident.id}",
                incident.name,
                incident.description ?: "Group related saved recordings",
                "$clipCount ${if (clipCount == 1) "clip" else "clips"}",
            ) {
                activeBrowser?.focusDetailActions()
            }
        }
        val details = state.exports.incidents.associate { incident ->
            val key = "clips:incident:${incident.id}"
            val assigned = state.exports.items.filter { it.incidentId == incident.id }
            key to NativeBrowserDetail(
                key = incident.id,
                title = incident.name,
                subtitle = state.exports.incidentsErrorMessage
                    ?: "${assigned.size} ${if (assigned.size == 1) "recording" else "recordings"}",
                body = incident.description ?: "Group related recordings together",
                thumbnail = assigned.firstOrNull()?.let { NativeThumbnailRequest(NativeThumbnailKind.CLIP, it.id) },
                actions = buildList {
                    assigned.forEachIndexed { index, clip ->
                        add(
                            action(
                                "$key:play:$index:${clip.id}",
                                "Play ${clip.name.replace('_', ' ')}",
                                "${cameraDisplayName(clip.camera, state)} • ${formatDateTime(clip.createdAt)}",
                                enabled = !clip.inProgress,
                                thumbnail = NativeThumbnailRequest(NativeThumbnailKind.CLIP, clip.id),
                            ) { viewModel.playExport(clip) },
                        )
                    }
                    if (mutationsAvailable) {
                        add(
                            action(
                                "$key:edit",
                                "Edit incident",
                                "Change its name or description",
                                enabled = !state.exports.operationBusy,
                            ) { showEditIncidentDialog(incident) },
                        )
                        add(
                            action(
                                "$key:delete",
                                "Delete incident",
                                enabled = !state.exports.operationBusy,
                            ) { showDeleteIncidentDialog(incident, assigned.isNotEmpty()) },
                        )
                    }
                    if (isEmpty()) {
                        add(info("$key:empty", "No recordings in this incident", "Add a saved recording from the Recordings tab"))
                    }
                },
            )
        }
        showMediaBrowser(
            "clips:incidents",
            NativeBrowserUiState(
                title = "Clips",
                subtitle = state.exports.incidentsErrorMessage
                    ?: "${state.exports.incidents.size} ${if (state.exports.incidents.size == 1) "incident" else "incidents"} • organize related recordings",
                tabs = clipsTabs(state),
                rows = rows,
                details = details,
                loading = state.exports.loading || (!state.exports.loadedOnce &&
                    (viewModel.state.value.exports.loading || viewModel.state.value.exports.loadedOnce)),
                emptyTitle = "No incidents",
                emptyBody = "Create an incident to group related recordings",
            ),
        )
    }

    private fun showRenameClipDialog(clip: RecordingExport) {
        showTextEntryDialog("Rename recording", "Choose a short name", clip.name.replace('_', ' ')) { name ->
            if (name.isNotBlank()) {
                runProtectedAction(PinScope.ADMINISTRATIVE_ACTIONS, "renaming recordings") {
                    viewModel.renameExport(clip, name)
                }
            }
        }
    }

    private fun showAssignIncidentDialog(clip: RecordingExport) {
        val choices = listOf(Triple("__none__", "No incident", if (clip.incidentId == null) "Selected" else "")) +
            currentState.exports.incidents.map { incident ->
                Triple(incident.id, incident.name, if (clip.incidentId == incident.id) "Selected" else "")
            } + Triple("__new__", "Create a new incident", "")
        showChoiceDialog("Assign incident", clip.name.replace('_', ' '), choices) { choice ->
            when (choice) {
                "__none__" -> runProtectedAction(PinScope.ADMINISTRATIVE_ACTIONS, "moving recordings") {
                    viewModel.assignExportToIncident(clip, null)
                }
                "__new__" -> showCreateIncidentDialog()
                else -> runProtectedAction(PinScope.ADMINISTRATIVE_ACTIONS, "moving recordings") {
                    viewModel.assignExportToIncident(clip, choice)
                }
            }
        }
    }

    private fun showDeleteClipDialog(clip: RecordingExport) {
        showChoiceDialog(
            "Delete recording?",
            "${clip.name.replace('_', ' ')} will be removed from Frigate",
            listOf(Triple("cancel", "Keep recording", ""), Triple("delete", "Delete permanently", "This cannot be undone")),
        ) { choice ->
            if (choice == "delete") {
                runProtectedAction(PinScope.DESTRUCTIVE_ACTIONS, "deleting recordings") {
                    viewModel.deleteExport(clip)
                }
            }
        }
    }

    private fun showCreateIncidentDialog() {
        showTextEntryDialog("New incident", "Name this group of related recordings", "") { name ->
            if (name.isNotBlank()) {
                showTextEntryDialog(
                    "Incident description",
                    "Optional: add a short note explaining what happened",
                    "",
                    multiline = true,
                ) { description ->
                    runProtectedAction(PinScope.ADMINISTRATIVE_ACTIONS, "creating Incidents") {
                        viewModel.createIncident(name, description.takeIf(String::isNotBlank))
                    }
                }
            }
        }
    }

    private fun showEditIncidentDialog(incident: app.opah.tv.data.model.ExportIncident) {
        showTextEntryDialog("Incident name", "Choose a short, recognizable name", incident.name) { name ->
            if (name.isNotBlank()) {
                showTextEntryDialog(
                    "Incident description",
                    "Optional: add a short note explaining what happened",
                    incident.description.orEmpty(),
                    multiline = true,
                ) { description ->
                    runProtectedAction(PinScope.ADMINISTRATIVE_ACTIONS, "updating Incidents") {
                        viewModel.updateIncident(incident, name, description.takeIf(String::isNotBlank))
                    }
                }
            }
        }
    }

    private fun showDeleteIncidentDialog(incident: app.opah.tv.data.model.ExportIncident, hasClips: Boolean) {
        val choices = buildList {
            add(Triple("cancel", "Keep incident", ""))
            add(Triple("incident", "Delete incident only", if (hasClips) "Recordings remain saved" else ""))
            if (hasClips) add(Triple("all", "Delete incident and recordings", "This cannot be undone"))
        }
        showChoiceDialog("Delete ${incident.name}?", "Choose what happens to its recordings", choices) { choice ->
            when (choice) {
                "incident" -> runProtectedAction(PinScope.DESTRUCTIVE_ACTIONS, "deleting Incidents") {
                    viewModel.deleteIncident(incident, false)
                }
                "all" -> runProtectedAction(PinScope.DESTRUCTIVE_ACTIONS, "deleting Incidents") {
                    viewModel.deleteIncident(incident, true)
                }
            }
        }
    }

    private fun showSettings(state: Phase0UiState) {
        if (
            state.privacy.pinConfigured &&
            PinScope.SETTINGS in state.privacy.protectedScopes &&
            PinScope.SETTINGS !in state.privacy.unlockedScopes
        ) {
            val rows = listOf(
                info(
                    "settings:locked:info",
                    "Settings are locked",
                    "Enter the Opah PIN to open settings on this TV",
                ),
                action("settings:locked:unlock", "Unlock settings", value = "Enter PIN") {
                    showPinDialog("Unlock settings") { pin -> viewModel.unlockPrivacy(pin) }
                },
            )
            showList("settings:locked", "Settings", "Protected with your Opah PIN", rows)
            return
        }
        when (route.settingsPage) {
            SettingsPage.MAIN -> showSettingsHub(state)
            SettingsPage.UPDATE -> showUpdateSettings(state)
            SettingsPage.APPEARANCE -> showAppearanceSettings(state)
            SettingsPage.PLAYBACK -> showPlaybackSettings(state)
            SettingsPage.PLAYBACK_TEST -> showPlaybackTest(state)
            SettingsPage.CAMERA_DIAGNOSTICS -> showCameraDiagnostics(state)
            SettingsPage.TV_ALERTS -> showTvAlerts(state)
            SettingsPage.PRIVACY -> showPrivacy(state)
            SettingsPage.STARTUP -> showStartup(state)
            SettingsPage.CONNECTION -> {
                openConnections(NativeConnectionPage.FRIGATE)
            }
            SettingsPage.SYSTEM -> showServer(state)
            SettingsPage.ADVANCED -> showAdvanced(state)
            SettingsPage.ABOUT -> showAbout(state)
        }
    }

    private fun showProtectedContentGate(surfaceKey: String, title: String, description: String) {
        val rows = listOf(
            info("protected:$surfaceKey:info", title, description),
            action("protected:$surfaceKey:unlock", "Unlock", "Use the PIN configured on this TV", "Enter PIN") {
                showPinDialog("Unlock $surfaceKey") { pin -> viewModel.unlockPrivacy(pin) }
            },
        )
        showList("protected:$surfaceKey", title, description, rows)
    }

    private fun showTvAlertPrivacyGate(state: Phase0UiState) {
        setNavigationVisible(true)
        updateNavigationSelection()
        updateNavigationAppearance(root.navigationOpen)
        val privacy = state.privacy
        val rows = buildList {
            add(
                info(
                    "alert:privacy-gate:explanation",
                    "This alert is protected",
                    "Enter the Opah PIN before activity from a protected camera can appear on this TV",
                ),
            )
            privacy.errorMessage?.let { message ->
                add(info("alert:privacy-gate:error", "The PIN did not work", message))
            }
            if (privacy.pinConfigured) {
                add(
                    action(
                        "alert:privacy-gate:unlock",
                        if (privacy.busy) "Checking PIN" else "Enter PIN",
                        "Use the PIN configured on this TV",
                        if (privacy.busy) "Please wait" else "Open keypad",
                        enabled = !privacy.busy,
                    ) {
                        showPinDialog("Open protected alert") { pin -> viewModel.unlockTvAlert(pin) }
                    },
                )
            } else {
                add(
                    info(
                        "alert:privacy-gate:no-pin",
                        "No PIN is configured",
                        "Return to Opah and review the Privacy settings before opening this alert",
                    ),
                )
            }
            add(
                action(
                    "alert:privacy-gate:cancel",
                    "Return to Opah",
                    "Leave the protected alert closed",
                    "Cancel",
                    enabled = !privacy.busy,
                ) { viewModel.cancelTvAlertPrivacyGate() },
            )
        }
        showList(
            surfaceKey = "alert:privacy-gate",
            title = "PIN required",
            subtitle = "Protected camera activity stays hidden until it is unlocked",
            rows = rows,
        )
    }

    private fun showSettingsHub(state: Phase0UiState) {
        val rows = buildList {
            add(section("settings:overview:status", "At a glance", "Choose a category on the left to make changes"))
            add(info("settings:overview:server", "Server", state.activeProfile?.apiBaseUrl ?: "Not connected", state.snapshot?.frigateVersion.orEmpty()))
            add(info("settings:overview:cameras", "Cameras", "${state.snapshot?.cameras?.size ?: 0} available"))
            add(info("settings:overview:alerts", "TV alerts", if (state.tvAlerts.enabled) "On" else "Off"))
            add(info("settings:overview:privacy", "Privacy PIN", if (state.privacy.pinConfigured) "Configured" else "Not configured"))
            add(action("settings:connections", "Connections", "Manage Frigate and Home Assistant", "Open") {
                openConnections(NativeConnectionPage.FRIGATE)
            })
            if (state.appUpdate.updateAvailable) {
                add(action("settings:overview:update", "Opah update ready", "A newer version is ready to download", "Open") {
                    openSettingsPage(SettingsPage.UPDATE)
                })
            }
        }
        showSettingsPane("Settings", "Make Opah work the way you want", rows, state)
    }

    private fun showUpdateSettings(state: Phase0UiState) {
        val update = state.appUpdate
        val rows = buildList {
            add(
                info(
                    "update:version",
                    "Installed version",
                    displayedVersionName(),
                    if (update.updateAvailable) "Update ready" else "Current",
                ),
            )
            add(
                action(
                    "update:check",
                    if (update.checking) "Checking for updates" else "Check again",
                    "Stay on this page while Opah checks",
                    enabled = !update.checking,
                ) { viewModel.checkForUpdates(forceRefresh = true) },
            )
            update.statusMessage?.let { message ->
                add(
                    info(
                        "update:status",
                        if (update.checking) "Checking now" else "Update check finished",
                        message,
                    ),
                )
            }
            add(toggle("update:auto", "Check automatically", state.settings.automaticUpdateChecksEnabled) {
                viewModel.updateAutomaticUpdateChecks(!state.settings.automaticUpdateChecksEnabled)
            })
            update.errorMessage?.let { add(info("update:error", "Update check", it)) }
            if (update.updateAvailable) {
                add(info("update:available", "Version ${update.latestVersion ?: "available"}", update.releaseNotes))
                when {
                    update.preparedApkPath != null -> add(
                        action("update:install", "Install update", value = "Open installer") {
                            actions.onInstallUpdate(update.preparedApkPath)
                        },
                    )
                    update.downloading -> add(info("update:downloading", "Preparing update", "Download in progress"))
                    else -> add(action("update:download", "Download update", value = "Download") {
                        viewModel.downloadUpdate()
                    })
                }
            }
        }
        showSettingsList("update", "Update", "See whether a newer version of Opah is ready", rows)
    }

    private fun showAppearanceSettings(state: Phase0UiState) {
        val uiState = NativeAppearanceUiState(
            mode = state.settings.appearanceMode,
            colors = ThemeColorPolicy.sanitize(state.settings.customThemeColors),
            reducedMotion = state.settings.reducedMotion,
            highContrast = state.settings.highContrast,
            subtleRoundedCorners = state.settings.subtleRoundedCorners,
            reducedTransparency = state.settings.reducedTransparency,
        )
        val surface = activeAppearance ?: NativeAppearanceSurface(
            activity = activity,
            initialState = uiState,
            onMode = viewModel::updateAppearance,
            onColors = viewModel::updateCustomTheme,
            onReducedMotion = viewModel::updateReducedMotion,
            onHighContrast = viewModel::updateHighContrast,
            onSubtleRoundedCorners = viewModel::updateSubtleRoundedCorners,
            onReducedTransparency = viewModel::updateReducedTransparency,
            onFocused = { key, view ->
                focusMemory[route.focusMemoryKey] = key
                root.rememberContentFocus(view)
            },
        )
        surface.update(uiState)
        showSettingsPane(
            title = "Appearance",
            subtitle = "Preview and tune the look directly",
            rows = emptyList(),
            state = state,
            detailContent = surface,
        )
        activeAppearance = surface
        appearancePosition?.let { position -> surface.restorePosition(position); appearancePosition = null }
    }

    @Suppress("unused")
    private fun showAppearanceSettingsLegacy(state: Phase0UiState) {
        val rows = buildList {
            add(
                NativeRowModel(
                    key = "appearance:preview",
                    title = "Live preview",
                    kind = NativeRowKind.THEME_PREVIEW,
                    enabled = false,
                    previewAccent = NativeTheme.palette.focus,
                    previewBackground = NativeTheme.palette.background,
                ),
            )
            add(section("appearance:theme", "Color theme", "Choose a comfortable look for this TV"))
            AppearanceMode.entries.forEach { mode ->
                add(
                    choice(
                        "appearance:mode:${mode.name}",
                        if (mode == AppearanceMode.CUSTOM) {
                            "Custom colors"
                        } else {
                            mode.name.lowercase().replaceFirstChar(Char::uppercase)
                        },
                        selected = state.settings.appearanceMode == mode,
                        description = if (mode == AppearanceMode.CUSTOM) {
                            "Adjust the accent and background yourself"
                        } else {
                            ""
                        },
                    ) { viewModel.updateAppearance(mode) },
                )
            }
            if (state.settings.appearanceMode == AppearanceMode.CUSTOM) {
                val safeColors = ThemeColorPolicy.sanitize(state.settings.customThemeColors)
                val presets = listOf(
                    "Coral" to CustomThemeColors(0xFFFF7048.toInt(), 0xFF07111F.toInt()),
                    "Ocean" to CustomThemeColors(0xFF53B7FF.toInt(), 0xFF071522.toInt()),
                    "Forest" to CustomThemeColors(0xFF65D68A.toInt(), 0xFF08170F.toInt()),
                    "Plum" to CustomThemeColors(0xFFD29BFF.toInt(), 0xFF180D20.toInt()),
                    "Sunset" to CustomThemeColors(0xFFFFB454.toInt(), 0xFF211108.toInt()),
                    "Slate" to CustomThemeColors(0xFF7DD8D2.toInt(), 0xFF11171A.toInt()),
                )
                val accentHsl = ThemeColorPolicy.toHsl(safeColors.accentArgb)
                val backgroundHsl = ThemeColorPolicy.toHsl(safeColors.backgroundArgb)
                add(section("appearance:adjust", "Customize colors", "Use left and right to see each change immediately"))
                add(adjustment(
                    key = "appearance:adjust:accent-hue",
                    title = "Accent color",
                    value = themeHueName(accentHsl.hue),
                    adjustmentRange = NativeAdjustmentRange(0f, 359f, accentHsl.hue.toFloat(), wraps = true),
                    indicatorColor = safeColors.accentArgb,
                    decrease = {
                        viewModel.updateCustomTheme(
                            safeColors.copy(accentArgb = ThemeColorPolicy.adjustHue(safeColors.accentArgb, -10)),
                        )
                    },
                    increase = {
                        viewModel.updateCustomTheme(
                            safeColors.copy(accentArgb = ThemeColorPolicy.adjustHue(safeColors.accentArgb, 10)),
                        )
                    },
                ))
                add(adjustment(
                    key = "appearance:adjust:accent-strength",
                    title = "Accent intensity",
                    value = themeIntensityLabel(accentHsl.saturation),
                    adjustmentRange = NativeAdjustmentRange(0f, 100f, accentHsl.saturation.toFloat()),
                    indicatorColor = safeColors.accentArgb,
                    decrease = {
                        viewModel.updateCustomTheme(
                            safeColors.copy(accentArgb = ThemeColorPolicy.adjustSaturation(safeColors.accentArgb, -5)),
                        )
                    },
                    increase = {
                        viewModel.updateCustomTheme(
                            safeColors.copy(accentArgb = ThemeColorPolicy.adjustSaturation(safeColors.accentArgb, 5)),
                        )
                    },
                ))
                add(adjustment(
                    key = "appearance:adjust:accent-brightness",
                    title = "Accent brightness",
                    value = themeBrightnessLabel(accentHsl.lightness),
                    adjustmentRange = NativeAdjustmentRange(minOf(5f, accentHsl.lightness.toFloat()), maxOf(95f, accentHsl.lightness.toFloat()), accentHsl.lightness.toFloat()),
                    indicatorColor = safeColors.accentArgb,
                    decrease = {
                        viewModel.updateCustomTheme(
                            safeColors.copy(accentArgb = ThemeColorPolicy.adjustLightness(safeColors.accentArgb, -5)),
                        )
                    },
                    increase = {
                        viewModel.updateCustomTheme(
                            safeColors.copy(accentArgb = ThemeColorPolicy.adjustLightness(safeColors.accentArgb, 5)),
                        )
                    },
                ))
                add(adjustment(
                    key = "appearance:adjust:background-hue",
                    title = "Background tint",
                    value = themeHueName(backgroundHsl.hue),
                    adjustmentRange = NativeAdjustmentRange(0f, 359f, backgroundHsl.hue.toFloat(), wraps = true),
                    indicatorColor = safeColors.backgroundArgb,
                    decrease = {
                        viewModel.updateCustomTheme(
                            safeColors.copy(backgroundArgb = ThemeColorPolicy.adjustHue(safeColors.backgroundArgb, -10)),
                        )
                    },
                    increase = {
                        viewModel.updateCustomTheme(
                            safeColors.copy(backgroundArgb = ThemeColorPolicy.adjustHue(safeColors.backgroundArgb, 10)),
                        )
                    },
                ))
                add(adjustment(
                    key = "appearance:adjust:background-strength",
                    title = "Background intensity",
                    value = themeIntensityLabel(backgroundHsl.saturation),
                    adjustmentRange = NativeAdjustmentRange(0f, 100f, backgroundHsl.saturation.toFloat()),
                    indicatorColor = safeColors.backgroundArgb,
                    decrease = {
                        viewModel.updateCustomTheme(
                            safeColors.copy(backgroundArgb = ThemeColorPolicy.adjustSaturation(safeColors.backgroundArgb, -5)),
                        )
                    },
                    increase = {
                        viewModel.updateCustomTheme(
                            safeColors.copy(backgroundArgb = ThemeColorPolicy.adjustSaturation(safeColors.backgroundArgb, 5)),
                        )
                    },
                ))
                add(adjustment(
                    key = "appearance:adjust:background-brightness",
                    title = "Background brightness",
                    value = themeBrightnessLabel(backgroundHsl.lightness),
                    adjustmentRange = NativeAdjustmentRange(minOf(5f, backgroundHsl.lightness.toFloat()), maxOf(95f, backgroundHsl.lightness.toFloat()), backgroundHsl.lightness.toFloat()),
                    indicatorColor = safeColors.backgroundArgb,
                    decrease = {
                        viewModel.updateCustomTheme(
                            safeColors.copy(backgroundArgb = ThemeColorPolicy.adjustLightness(safeColors.backgroundArgb, -3)),
                        )
                    },
                    increase = {
                        viewModel.updateCustomTheme(
                            safeColors.copy(backgroundArgb = ThemeColorPolicy.adjustLightness(safeColors.backgroundArgb, 3)),
                        )
                    },
                ))
                add(section("appearance:presets", "Optional starting styles", "Choose one only if you want a ready-made combination"))
                presets.forEach { (name, colors) ->
                    val sanitizedPreset = ThemeColorPolicy.sanitize(colors)
                    add(
                        choice(
                            "appearance:preset:${name.lowercase()}",
                            name,
                            selected = safeColors == sanitizedPreset,
                            description = "Ready-made custom colors",
                        ) { viewModel.updateCustomTheme(sanitizedPreset) },
                    )
                }
            }
            add(section("appearance:accessibility", "Accessibility"))
            add(toggle("appearance:motion", "Reduce motion", state.settings.reducedMotion) {
                viewModel.updateReducedMotion(!state.settings.reducedMotion)
            })
            add(toggle("appearance:contrast", "High contrast", state.settings.highContrast) {
                viewModel.updateHighContrast(!state.settings.highContrast)
            })
        }
        showSettingsList("appearance", "Appearance", "Choose how Opah looks", rows)
    }

    private fun showPlaybackSettings(state: Phase0UiState) {
        val rows = buildList {
            add(section("playback:quality", "Default live video", "Automatic works best for most people"))
            StreamPreference.entries.forEach { preference ->
                val label = when (preference) {
                    StreamPreference.AUTOMATIC -> "Automatic"
                    StreamPreference.MAIN -> "Best quality"
                    StreamPreference.LOW_BANDWIDTH -> "Low bandwidth"
                }
                add(
                    choice(
                        "playback:stream:${preference.name}",
                        label,
                        selected = state.settings.streamPreference == preference,
                    ) { viewModel.updateStreamPreference(preference) },
                )
            }
            add(section("playback:opening", "When video opens", "These choices apply next time video opens"))
            add(toggle("playback:tcp", "Prefer a reliable connection", state.settings.preferRtpTcp) {
                viewModel.updatePreferRtpTcp(!state.settings.preferRtpTcp)
            })
            add(toggle("playback:muted", "Start live video muted", state.settings.startLiveMuted) {
                viewModel.updateStartLiveMuted(!state.settings.startLiveMuted)
            })
            add(toggle("playback:details", "Show playback details", state.settings.diagnosticsEnabled) {
                viewModel.updateDiagnosticsEnabled(!state.settings.diagnosticsEnabled)
            })
            add(toggle("playback:reviewed", "Mark activity reviewed after playback", state.settings.autoMarkReviewedAfterPlayback) {
                viewModel.updateAutoMarkReviewedAfterPlayback(!state.settings.autoMarkReviewedAfterPlayback)
            })
            add(section("playback:tools", "Camera tools"))
            add(
                action(
                    "playback:test",
                    "Check camera compatibility",
                    "Opah safely tries playback choices and remembers the verified choice for that camera",
                    "Open",
                ) { openSettingsPage(SettingsPage.PLAYBACK_TEST) },
            )
            add(
                action(
                    "playback:diagnostics",
                    "Diagnostic data",
                    "Technical camera and decoder details for advanced troubleshooting",
                    "Open",
                ) { openSettingsPage(SettingsPage.CAMERA_DIAGNOSTICS) },
            )
        }
        showSettingsList("playback", "Cameras and Playback", "Choose how cameras and video start and play", rows)
    }

    private fun showPlaybackTest(state: Phase0UiState) {
        ensurePlaybackCompatibilityChoicesLoaded(state)
        val cameras = state.snapshot?.cameras.orEmpty()
        val rows = buildList {
            add(
                info(
                    "playback-test:explanation",
                    "What the check does",
                    "Opah opens the camera, tries safe transport, audio, and decoder choices without needing your input, then saves the verified choice for that camera",
                ),
            )
            state.playbackCompatibilityMessage?.let { add(info("playback-test:status", "Last check", it)) }
            if (cameras.isEmpty()) {
                add(info("playback-test:empty", "No cameras available", "This account does not currently have access to a camera Opah can check"))
            }
            cameras.forEach { camera ->
                val saved = state.playbackCompatibilityChoices[camera.name]
                val resetKey = "playback-test:reset:${camera.name}"
                if (saved != null) {
                    handlers[resetKey] = { viewModel.resetCameraPlaybackCompatibility(camera.name) }
                } else {
                    handlers.remove(resetKey)
                }
                add(
                    action(
                        "playback-test:camera:${camera.name}",
                        camera.displayName,
                        saved?.let { "Saved choice: $it" } ?: "No saved choice yet",
                        "Check",
                    ) { showCompatibilityModeDialog(camera) }.copy(
                        secondaryActionKey = resetKey.takeIf { saved != null },
                        secondaryActionLabel = if (saved != null) "Reset" else "",
                    ),
                )
            }
        }
        showSettingsList(
            "playback-test",
            "Check camera compatibility",
            "Choose a camera to inspect or change the playback choice used everywhere in Opah",
            rows,
        )
    }

    private fun showCameraDiagnostics(state: Phase0UiState) {
        ensurePlaybackCompatibilityChoicesLoaded(state)
        val rows = buildList {
            add(
                info(
                    "camera-diagnostics:notice",
                    "Technical support data",
                    "This page shows what the server and this TV reported. It does not change camera settings",
                ),
            )
            state.snapshot?.cameras.orEmpty().forEach { camera ->
                val report = cameraTechnicalReport(
                    camera = camera,
                    metadataByStream = state.snapshot?.streamMetadata.orEmpty(),
                    device = state.device,
                    settings = state.settings,
                    savedPlaybackChoice = state.playbackCompatibilityChoices[camera.name],
                )
                add(section("camera-diagnostics:${camera.name}", camera.displayName, "Camera ID: ${report.cameraId}"))
                add(
                    info(
                        "camera-diagnostics:${camera.name}:defaults",
                        "Playback configuration",
                        "${report.configuredStreamCount} configured streams • ${report.defaultStreamPreference} • ${report.defaultConnection}",
                        "Saved choice: ${report.savedPlaybackChoice}",
                    ),
                )
                if (report.streams.isEmpty()) {
                    add(info("camera-diagnostics:${camera.name}:none", "Live streams", "None reported"))
                }
                report.streams.forEachIndexed { index, stream ->
                    add(
                        info(
                            "camera-diagnostics:${camera.name}:stream:$index",
                            stream.optionLabel,
                            buildList {
                                add("Stream ID: ${stream.streamId}")
                                add("Frigate: ${stream.frigateStatus}")
                                add("Video: ${stream.videoFormat}")
                                add("Resolution: ${stream.resolution}")
                                add(stream.videoDecoderSupport)
                                add("Audio: ${stream.audioFormat}")
                                add(stream.audioDecoderSupport)
                            }.joinToString(" • "),
                            "Video decoders: ${stream.videoDecoderNames}\nAudio decoders: ${stream.audioDecoderNames}",
                        ),
                    )
                }
            }
            state.device?.let { device ->
                add(section("camera-diagnostics:device", "This TV", "${device.manufacturer} ${device.model}"))
                device.codecs.forEachIndexed { index, codec ->
                    add(
                        info(
                            "camera-diagnostics:codec:$index",
                            codec.mimeType,
                            "${codec.decoders.size} decoder choices${if (codec.hasHardwareDecoder) " • hardware available" else ""}",
                            codec.decoders.joinToString("\n") { decoder ->
                                buildList {
                                    add(decoder.name)
                                    when {
                                        decoder.hardwareAccelerated == true -> add("hardware")
                                        decoder.softwareOnly == true -> add("software")
                                    }
                                    if (decoder.adaptivePlayback) add("adaptive")
                                    decoder.maxSupportedInstances?.let { add("up to $it instances") }
                                }.joinToString(" • ")
                            }.ifBlank { "No decoder names advertised" },
                        ),
                    )
                }
            }
        }
        showSettingsList("camera-diagnostics", "Diagnostic data", "Camera, stream, and TV playback details", rows)
    }

    private fun showTvAlerts(state: Phase0UiState) {
        val alerts = state.tvAlerts
        if (alerts.loading) {
            showSettingsList(
                "tv-alerts",
                "TV alerts",
                "See camera activity while another TV app is open",
                listOf(info("alerts:loading", "Loading TV alert settings", "This should take only a moment")),
            )
            return
        }
        if (!alerts.available) {
            showSettingsList(
                "tv-alerts",
                "TV alerts",
                "See camera activity while another TV app is open",
                listOf(
                    info("alerts:unavailable", "TV alerts are unavailable", alerts.errorMessage ?: "Opah could not load alert settings"),
                    action("alerts:retry", "Try again", value = "Retry") { viewModel.refresh() },
                ),
            )
            return
        }
        if (!state.modes.loadedOnce && !state.modes.loading) viewModel.loadModes()
        val recentItems = (state.review.items + state.snapshot?.recentReviewItems.orEmpty()).distinctBy(ReviewItem::id)
        val labelOptions = recentItems.flatMap(ReviewItem::objects).distinct().sorted().take(20)
        val zoneOptions = recentItems.flatMap(ReviewItem::zones).distinct().sorted().take(20)
        val identityOptions = recentItems.flatMap(ReviewItem::subLabels).distinct().sorted().take(20)
        val plateOptions = recentItems.flatMap { item ->
            item.linkedEvents.mapNotNull { it.recognizedLicensePlate }
        }.distinct().sorted().take(20)
        val rows = buildList {
            if (alerts.busy) {
                add(info("alerts:saving", "Saving TV alert settings", "Controls will be ready again in a moment"))
            }
            alerts.statusMessage?.let { add(info("alerts:status", "TV alerts", it)) }
            add(action("alerts:shared-pause", "Pause all TV notifications", notificationPauseLabel(state.notificationPause)) {
                openConnections(NativeConnectionPage.NOTIFICATIONS)
            })
            alerts.errorMessage?.let { add(info("alerts:error", "TV alerts need attention", it)) }
            add(
                toggle(
                    "alerts:enabled",
                    "TV alerts",
                    alerts.enabled,
                    "Show important activity while another TV app is open",
                ) {
                    if (alerts.enabled) viewModel.disableTvAlerts() else actions.onEnableTvAlerts()
                },
            )
            add(section("alerts:which", "Which activity", "Choose how much activity should appear"))
            listOf(
                AlertMode.IMPORTANT_ACTIVITY to "Important activity",
                AlertMode.ALL_DETECTED_ACTIVITY to "All detected activity",
                AlertMode.CUSTOM to "Custom",
            ).forEach { (mode, label) ->
                add(
                    choice(
                        "alerts:mode:${mode.name}",
                        label,
                        selected = alerts.mode == mode,
                    ) { viewModel.setTvAlertMode(mode) },
                )
            }
            if (alerts.mode == AlertMode.ALL_DETECTED_ACTIVITY || alerts.mode == AlertMode.CUSTOM) {
                add(section("alerts:motion", "Significant motion", "Off by default because ordinary motion can be frequent"))
                add(toggle("alerts:motion:enabled", "Include significant motion", alerts.significantMotionEnabled) {
                    viewModel.setTvAlertSignificantMotion(!alerts.significantMotionEnabled)
                })
            }
            if (alerts.mode == AlertMode.CUSTOM) {
                add(section("alerts:severity", "Custom activity", "Choose the activity you want to see"))
                add(toggle(
                    "alerts:severity:alert",
                    "Important alerts",
                    AwarenessReviewSeverity.ALERT in alerts.customSeverities,
                ) {
                    viewModel.setTvAlertCustomSeverity(
                        AwarenessReviewSeverity.ALERT,
                        AwarenessReviewSeverity.ALERT !in alerts.customSeverities,
                    )
                })
                add(toggle(
                    "alerts:severity:detection",
                    "Detections",
                    AwarenessReviewSeverity.DETECTION in alerts.customSeverities,
                ) {
                    viewModel.setTvAlertCustomSeverity(
                        AwarenessReviewSeverity.DETECTION,
                        AwarenessReviewSeverity.DETECTION !in alerts.customSeverities,
                    )
                })
                state.snapshot?.cameras.orEmpty().takeIf(List<Camera>::isNotEmpty)?.let { cameras ->
                    add(section("alerts:cameras", "Cameras", "All cameras are included until you choose specific ones"))
                    add(choice("alerts:camera:any", "All cameras", selected = alerts.cameraIds.isEmpty()) {
                        viewModel.setTvAlertAllCameras()
                    })
                    cameras.forEach { camera ->
                        add(toggle("alerts:camera:${camera.name}", camera.displayName, camera.name in alerts.cameraIds) {
                            viewModel.setTvAlertCamera(camera.name, camera.name !in alerts.cameraIds)
                        })
                    }
                }
                if (labelOptions.isNotEmpty()) {
                    add(section("alerts:labels", "Object labels", "Uses labels already seen by this TV"))
                    add(choice("alerts:label:any", "Any object", selected = alerts.labels.isEmpty()) {
                        viewModel.clearTvAlertLabels()
                    })
                    labelOptions.forEach { label ->
                        add(toggle("alerts:label:$label", label.humanize(), label in alerts.labels) {
                            viewModel.setTvAlertLabel(label, label !in alerts.labels)
                        })
                    }
                }
                if (zoneOptions.isNotEmpty()) {
                    add(section("alerts:zones", "Zones", "Uses zones already seen by this TV"))
                    add(choice("alerts:zone:any", "Any zone", selected = alerts.zones.isEmpty()) {
                        viewModel.clearTvAlertZones()
                    })
                    zoneOptions.forEach { zone ->
                        add(toggle("alerts:zone:$zone", zone.humanize(), zone in alerts.zones) {
                            viewModel.setTvAlertZone(zone, zone !in alerts.zones)
                        })
                    }
                }
                if (identityOptions.isNotEmpty()) {
                    add(section("alerts:identities", "Recognized identities", "Uses identities already seen by this TV"))
                    add(choice("alerts:identity:any", "Any identity", selected = alerts.subLabels.isEmpty()) {
                        viewModel.clearTvAlertIdentities()
                    })
                    identityOptions.forEach { identity ->
                        add(toggle("alerts:identity:$identity", identity, identity in alerts.subLabels) {
                            viewModel.setTvAlertIdentity(identity, identity !in alerts.subLabels)
                        })
                    }
                }
                if (plateOptions.isNotEmpty()) {
                    add(section("alerts:plates", "Recognized license plates", "Uses plates already seen by this TV"))
                    add(choice("alerts:plate:any", "Any plate", selected = alerts.plateLabels.isEmpty()) {
                        viewModel.clearTvAlertPlates()
                    })
                    plateOptions.forEach { plate ->
                        add(toggle("alerts:plate:$plate", plate, plate in alerts.plateLabels) {
                            viewModel.setTvAlertPlate(plate, plate !in alerts.plateLabels)
                        })
                    }
                }
                if (state.modes.modes.isNotEmpty()) {
                    add(section("alerts:modes", "Frigate Mode", "Only notify in the Modes you choose"))
                    add(choice("alerts:mode-filter:any", "Any Mode", selected = alerts.frigateModes.isEmpty()) {
                        viewModel.clearTvAlertModes()
                    })
                    state.modes.modes.forEach { mode ->
                        add(toggle("alerts:mode-filter:${mode.name}", mode.displayName, mode.name in alerts.frigateModes) {
                            viewModel.setTvAlertModeFilter(mode.name, mode.name !in alerts.frigateModes)
                        })
                    }
                }
                add(section("alerts:threat", "AI threat level", "Requires Frigate AI review summaries"))
                listOf(
                    null to "Any threat level",
                    1 to "Suspicious or critical",
                    2 to "Critical only",
                ).forEach { (level, label) ->
                    add(choice(
                        "alerts:threat:${level ?: 0}",
                        label,
                        selected = alerts.minimumThreatLevel == level,
                    ) { viewModel.setTvAlertMinimumThreatLevel(level) })
                }
            }
            add(section("alerts:privacy", "Alert preview", "Choose how much an alert can show"))
            NotificationPrivacy.entries.filterNot { it == NotificationPrivacy.NEVER_NOTIFY }.forEach { privacy ->
                val label = when (privacy) {
                    NotificationPrivacy.FULL_PREVIEW -> "Photo and details"
                    NotificationPrivacy.BLURRED_PREVIEW -> "Blurred photo"
                    NotificationPrivacy.TEXT_ONLY -> "Text only"
                    NotificationPrivacy.NEVER_NOTIFY -> "Never notify"
                }
                add(
                    choice(
                        "alerts:privacy:${privacy.name}",
                        label,
                        selected = alerts.notificationPrivacy == privacy,
                    ) { viewModel.setTvAlertPrivacy(privacy) },
                )
            }
            add(section("alerts:when", "When", "Always, or a precise daily start and end time"))
            add(
                choice(
                    "alerts:schedule:always",
                    "Always",
                    selected = alerts.schedulePreset == TvAlertSchedulePreset.ALWAYS,
                ) { viewModel.setTvAlertSchedulePreset(TvAlertSchedulePreset.ALWAYS) },
            )
            add(
                choice(
                    "alerts:schedule:custom",
                    "Custom daily time",
                    selected = alerts.schedulePreset == TvAlertSchedulePreset.CUSTOM,
                    description = if (alerts.schedulePreset == TvAlertSchedulePreset.CUSTOM) {
                        "${minuteLabel(alerts.scheduleStartMinute)} to ${minuteLabel(alerts.scheduleEndMinute)}"
                    } else {
                        "Choose exact start and end times"
                    },
                ) { showScheduleDialog(alerts.scheduleStartMinute, alerts.scheduleEndMinute) },
            )
            add(section("alerts:snooze", "Snooze", "Temporarily pause alerts"))
            listOf(
                "alerts:snooze:15" to ("15 minutes" to { viewModel.snoozeTvAlerts(15) }),
                "alerts:snooze:60" to ("1 hour" to { viewModel.snoozeTvAlerts(60) }),
                "alerts:snooze:tomorrow" to ("Until tomorrow" to { viewModel.snoozeTvAlertsUntilTomorrow() }),
                "alerts:snooze:mode" to ("Until mode changes" to { viewModel.snoozeTvAlertsUntilModeChanges() }),
            ).forEach { (key, pair) ->
                val selected = when (key) {
                    "alerts:snooze:15" -> alerts.snoozeChoice == TvAlertSnoozeChoice.FIFTEEN_MINUTES
                    "alerts:snooze:60" -> alerts.snoozeChoice == TvAlertSnoozeChoice.ONE_HOUR
                    "alerts:snooze:tomorrow" -> alerts.snoozeChoice == TvAlertSnoozeChoice.UNTIL_TOMORROW
                    else -> alerts.snoozeChoice == TvAlertSnoozeChoice.UNTIL_MODE_CHANGES
                }
                val modeAvailable = key != "alerts:snooze:mode" || state.modes.activeMode != null
                add(
                    choice(
                        key,
                        pair.first,
                        selected = selected,
                        description = if (modeAvailable) "" else "Choose a Frigate Mode before using this snooze",
                        enabled = modeAvailable,
                        action = pair.second,
                    ),
                )
            }
            if (alerts.snoozeChoice != TvAlertSnoozeChoice.NONE) {
                add(action("alerts:snooze:clear", "Clear snooze", value = "Resume") { viewModel.clearTvAlertSnoozes() })
            }
            add(section("alerts:display", "On-screen alert", "Controls the compact alert shown over other TV apps"))
            add(toggle("alerts:interactive", "Interactive Frigate alerts", alerts.overlaySettings.interactiveFrigateAlerts,
                "Use the remote to act on alerts; remote control moves temporarily from your current app") {
                if (alerts.overlaySettings.interactiveFrigateAlerts) viewModel.setInteractiveFrigateAlerts(false)
                else showChoiceDialog("Interactive Frigate alerts", "Remote control moves temporarily from your current app to the alert",
                    listOf(Triple("passive", "Keep passive alerts", "Your current app keeps remote control"),
                        Triple("interactive", "Enable interactive alerts", "Choose an action, or press Back to close"))) { selected ->
                    if (selected == "interactive") viewModel.setInteractiveFrigateAlerts(true)
                }
            })
            if (alerts.overlaySettings.interactiveFrigateAlerts) add(toggle("alerts:inline-recording", "Recording previews in alerts",
                alerts.overlaySettings.inlineRecordingPreviews, "Choose Preview recording to watch here; previews start muted") {
                viewModel.setInlineRecordingPreviews(!alerts.overlaySettings.inlineRecordingPreviews)
            })
            val displaySeconds = alerts.overlaySettings.displayDurationSeconds
            add(adjustment(
                key = "alerts:duration",
                title = "Notification display time",
                description = if (displaySeconds == 0) {
                    "Off skips the on-screen pop-up; the Android notification is still kept"
                } else {
                    "The compact pop-up closes automatically after this time"
                },
                value = tvAlertOverlayDisplayDurationLabel(displaySeconds),
                adjustmentRange = NativeAdjustmentRange(
                    app.opah.tv.notifications.android.TV_ALERT_OVERLAY_MIN_DISPLAY_SECONDS.toFloat(),
                    app.opah.tv.notifications.android.TV_ALERT_OVERLAY_MAX_DISPLAY_SECONDS.toFloat(),
                    displaySeconds.toFloat(),
                ),
                decrease = {
                    viewModel.setTvAlertOverlayDisplayDurationSeconds(
                        adjustTvAlertOverlayDisplayDuration(displaySeconds, -1),
                    )
                },
                increase = {
                    viewModel.setTvAlertOverlayDisplayDurationSeconds(
                        adjustTvAlertOverlayDisplayDuration(displaySeconds, 1),
                    )
                },
            ))
            add(
                action(
                    "alerts:vertical",
                    "Vertical location",
                    "Choose where the alert appears from top to bottom",
                    alerts.overlaySettings.verticalPosition.name.humanize(),
                ) {
                    showChoiceDialog(
                        "Vertical location",
                        "Current: ${alerts.overlaySettings.verticalPosition.name.humanize()}",
                        TvAlertOverlayVerticalPosition.entries.map { position ->
                            Triple(
                                position.name,
                                position.name.humanize(),
                                if (position == alerts.overlaySettings.verticalPosition) "Selected" else "",
                            )
                        },
                    ) { selected ->
                        viewModel.setTvAlertOverlayVerticalPosition(TvAlertOverlayVerticalPosition.valueOf(selected))
                    }
                },
            )
            add(
                action(
                    "alerts:horizontal",
                    "Horizontal location",
                    "Choose where the alert appears from left to right",
                    alerts.overlaySettings.horizontalPosition.name.humanize(),
                ) {
                    showChoiceDialog(
                        "Horizontal location",
                        "Current: ${alerts.overlaySettings.horizontalPosition.name.humanize()}",
                        TvAlertOverlayHorizontalPosition.entries.map { position ->
                            Triple(
                                position.name,
                                position.name.humanize(),
                                if (position == alerts.overlaySettings.horizontalPosition) "Selected" else "",
                            )
                        },
                    ) { selected ->
                        viewModel.setTvAlertOverlayHorizontalPosition(TvAlertOverlayHorizontalPosition.valueOf(selected))
                    }
                },
            )
            add(
                action(
                    "alerts:image",
                    "Photo size",
                    "Choose how much screen space the camera photo uses",
                    alerts.overlaySettings.imageSize.name.humanize(),
                ) {
                    showChoiceDialog(
                        "Photo size",
                        "Current: ${alerts.overlaySettings.imageSize.name.humanize()}",
                        TvAlertOverlayImageSize.entries.map { size ->
                            Triple(
                                size.name,
                                size.name.humanize(),
                                if (size == alerts.overlaySettings.imageSize) "Selected" else "",
                            )
                        },
                    ) { selected ->
                        viewModel.setTvAlertOverlayImageSize(TvAlertOverlayImageSize.valueOf(selected))
                    }
                },
            )
            add(action("alerts:test:text", "Send test without photo", value = "Test") { actions.onTestTvAlert(false) })
            add(action("alerts:test:image", "Send test with photo", value = "Test") { actions.onTestTvAlert(true) })
            add(action("alerts:android", "Android notification settings", value = "Open") {
                actions.onOpenNotificationSettings()
            })
            add(action("alerts:overlay", "Permission to appear over other apps", value = if (alerts.overlayPermissionGranted) "Allowed" else "Open") {
                actions.onOpenOverlaySettings()
            })
            if (alerts.enabled && alerts.deliveryStatus != TvAlertDeliveryStatus.AVAILABLE) {
                val deliveryMessage = when (alerts.deliveryStatus) {
                    TvAlertDeliveryStatus.NOTIFICATION_PERMISSION_REQUIRED -> "Android notification permission is required"
                    TvAlertDeliveryStatus.PARTIALLY_BLOCKED -> "One TV alert channel is blocked"
                    TvAlertDeliveryStatus.BLOCKED -> "TV alerts are blocked in Android settings"
                    TvAlertDeliveryStatus.AVAILABLE -> "Available"
                }
                add(info("alerts:delivery", "Notification delivery", deliveryMessage))
                add(action("alerts:delivery:refresh", "Check notification permission again", value = "Check") {
                    viewModel.refreshTvAlertDeliveryStatus()
                })
            }
        }
        val tabs = NativeAlertSection.entries.map { section ->
            val key = "alerts:section:${section.name}"
            handlers[key] = { alertSection = section; render(currentState) }
            NativeBrowserTab(key, section.label, alertSection == section)
        }
        showSettingsList(
            "tv-alerts", "TV alerts", "See important activity while watching TV",
            rows.filter { alertSectionForKey(it.key)?.let { section -> section == alertSection } ?: true },
            tabs,
        )
    }

    private fun showPrivacy(state: Phase0UiState) {
        val privacy = state.privacy
        if (privacy.loading) {
            showSettingsList(
                "privacy",
                "Privacy",
                "Protect selected cameras and app areas with a PIN",
                listOf(info("privacy:loading", "Loading privacy settings", "This should take only a moment")),
            )
            return
        }
        if (!privacy.available) {
            showSettingsList(
                "privacy",
                "Privacy",
                "Protect selected cameras and app areas with a PIN",
                listOf(
                    info("privacy:unavailable", "Privacy settings are unavailable", privacy.errorMessage ?: "Opah could not load privacy settings"),
                    action("privacy:retry", "Try again", value = "Retry") { viewModel.refresh() },
                ),
            )
            return
        }
        val rows = buildList {
            privacy.statusMessage?.let { add(info("privacy:status", "Privacy", it)) }
            privacy.errorMessage?.let { add(info("privacy:error", "Privacy needs attention", it)) }
            if (!privacy.pinConfigured) {
                add(
                    info(
                        "privacy:intro",
                        "Protect selected areas",
                        "Create a 4 to 8 digit PIN, then choose which cameras and app areas it protects",
                    ),
                )
                add(action("privacy:create", "Create PIN", value = "Open keypad") { showCreatePinFlow() })
            } else {
                add(
                    action(
                        "privacy:selection-lock",
                        if (privacy.pinSelectionsUnlocked) "Lock selections" else "Unlock selections",
                        if (privacy.pinSelectionsUnlocked) {
                            "Stop changes to the choices below"
                        } else {
                            "Enter the PIN before changing protected choices"
                        },
                        if (privacy.pinSelectionsUnlocked) "Lock" else "Unlock",
                    ) {
                        if (privacy.pinSelectionsUnlocked) {
                            viewModel.lockPrivacyNow()
                        } else {
                            showPinDialog("Unlock selections") { pin -> viewModel.unlockPrivacy(pin) }
                        }
                    },
                )
                add(section("privacy:areas", "Protected app areas", "Choose what requires the PIN"))
                listOf(
                    PinScope.SETTINGS to "Settings and connection",
                    PinScope.ADMINISTRATIVE_ACTIONS to "Administrative changes",
                    PinScope.DESTRUCTIVE_ACTIONS to "Delete and destructive actions",
                    PinScope.CLIPS_AND_INCIDENTS to "Clips and incidents",
                    PinScope.ACTIVITY_HISTORY_SEARCH to "Activity history and search",
                    PinScope.PRIVATE_CAMERAS to "Private cameras",
                    PinScope.HOME_ASSISTANT_ACTIONS to "Home Assistant actions",
                ).forEach { (scope, label) ->
                    add(
                        toggle(
                            "privacy:scope:${scope.name}",
                            label,
                            scope in privacy.protectedScopes,
                            enabled = privacy.pinSelectionsUnlocked && !privacy.busy,
                        ) { viewModel.setPinScope(scope, scope !in privacy.protectedScopes) },
                    )
                }
                add(section("privacy:cameras", "Private cameras", "Private cameras are hidden whenever the camera lock is active"))
                state.snapshot?.cameras.orEmpty().forEach { camera ->
                    add(
                        toggle(
                            "privacy:camera:${camera.name}",
                            camera.displayName,
                            camera.name in privacy.privateCameraIds,
                            enabled = privacy.pinSelectionsUnlocked && !privacy.busy,
                        ) { viewModel.setCameraPrivate(camera.name, camera.name !in privacy.privateCameraIds) },
                    )
                }
                add(section("privacy:remove", "Remove PIN"))
                add(action("privacy:remove-pin", "Remove PIN", "This removes all local PIN locks", "Remove") {
                    showPinDialog("Confirm PIN to remove it") { pin -> viewModel.removeLocalPin(pin) }
                })
            }
        }
        showSettingsList("privacy", "Privacy", "Protect selected cameras and app areas with a PIN", rows)
    }

    private fun showStartup(state: Phase0UiState) {
        val rows = buildList {
            add(section("startup:main", "When Opah opens"))
            add(startupChoice("startup:home", "Cameras", StartupTarget(StartupTargetKind.HOME), state))
            add(startupChoice("startup:last", "Last viewed", StartupTarget(StartupTargetKind.LAST_VIEWED), state))
            state.snapshot?.cameras.orEmpty().forEach { camera ->
                add(startupChoice("startup:camera:${camera.name}", camera.displayName, StartupTarget(StartupTargetKind.CAMERA, camera.name), state))
            }
            state.settings.savedCameraViews.forEach { view ->
                add(startupChoice("startup:view:${view.id}", view.name, StartupTarget(StartupTargetKind.SAVED_VIEW, view.id), state))
            }
            state.snapshot?.cameraGroups.orEmpty().forEach { group ->
                add(startupChoice("startup:group:${group.name}", group.displayName, StartupTarget(StartupTargetKind.CAMERA_GROUP, group.name), state))
            }
            if (state.snapshot?.birdseye?.playable == true) {
                add(startupChoice("startup:birdseye", "Birdseye", StartupTarget(StartupTargetKind.BIRDSEYE), state))
            }
        }
        showSettingsList("startup", "Startup", "Choose what Opah opens first", rows)
    }

    private fun showConnection(state: Phase0UiState) {
        route = NativeRoute(NativeDestination.CONNECTIONS)
        showConnections(state)
    }

    private fun openConnections(page: NativeConnectionPage) {
        route = NativeRoute(NativeDestination.CONNECTIONS, connectionPage = page)
        configuredStartupApplied = true
        render(currentState)
    }

    private fun showConnections(state: Phase0UiState) {
        setNavigationVisible(true)
        updateNavigationSelection()
        updateNavigationAppearance(root.navigationOpen)
        when (connectionsAccess(state.privacy)) {
            ConnectionsAccess.UNAVAILABLE -> {
                showList("connections:unavailable", "Connections", "Your connections stay protected", listOf(
                    info("connections:waiting", "Connection settings are unavailable", "Wait for privacy settings to load, or reopen Opah"),
                ))
                return
            }
            ConnectionsAccess.LOCKED -> {
                showProtectedContentGate("connections", "Connections", "Enter the Opah PIN to manage connections on this TV")
                return
            }
            ConnectionsAccess.ALLOWED -> Unit
        }
        val selected = route.connectionPage
        val ha = homeAssistant.state.value
        val haStatus = when {
            ha.phase == HomeAssistantConnectionPhase.SIGN_IN_REQUIRED -> "Sign in again"
            ha.phase == HomeAssistantConnectionPhase.ERROR -> "Needs attention"
            ha.phase == HomeAssistantConnectionPhase.DISCONNECTED -> "Not connected"
            !ha.enabled -> "Notifications off"
            ha.phase == HomeAssistantConnectionPhase.CONNECTED -> "Connected"
            else -> "Connecting"
        }
        val categories = NativeConnectionPage.entries.map { page ->
            action("connections:category:${page.name}", page.label,
                if (page == NativeConnectionPage.NOTIFICATIONS) notificationPauseLabel(state.notificationPause)
                else if (page == NativeConnectionPage.SETUP) "Guided setup and checks"
                else if (page == NativeConnectionPage.HOME_ASSISTANT) haStatus
                else if (state.activeProfile != null && state.snapshot != null) "Connected" else "Not connected",
                selected = page == selected) { openConnections(page) }
        }
        if (activeSurfaceKey != "connections:pane") {
            clearActiveSurface()
            setContentInsets(fullScreen = false)
            activeSurfaceKey = "connections:pane"
        }
        val detailRows = when (selected) {
            NativeConnectionPage.FRIGATE -> connectionRows(state)
            NativeConnectionPage.NOTIFICATIONS -> notificationPauseRows(state.notificationPause, state.notificationPauseBusy, state.notificationPauseError).also {
                handlers["pause:15"] = { viewModel.changeSharedNotificationPause(15) }
                handlers["pause:60"] = { viewModel.changeSharedNotificationPause(60) }
                handlers["pause:resume"] = { viewModel.changeSharedNotificationPause(null) }
            }
            else -> emptyList()
        }
        if (selected != NativeConnectionPage.HOME_ASSISTANT) {
            activeHomeAssistantSetup?.close()
            activeHomeAssistantSetup = null
        } else if (activeHomeAssistantSetup == null) {
            activeHomeAssistantSetup = NativeHomeAssistantSetup(
                activity = activity,
                canManage = { connectionsAccess(currentState.privacy) == ConnectionsAccess.ALLOWED },
                changePopupDuration = { changed ->
                    val seconds = app.opah.tv.notifications.android.TvAlertOverlaySettingsStore(activity).load().displayDurationSeconds
                    showChoiceDialog("TV pop-up duration", "Used for both Frigate and Home Assistant messages",
                        (listOf(0, 5, 10, 15, 20, 30, 60) + seconds).distinct().sorted().map {
                            Triple(it.toString(), if (it == 0) "Off" else "$it seconds", if (it == seconds) "Current" else "")
                        }) { selectedDuration ->
                        if (connectionsAccess(currentState.privacy) == ConnectionsAccess.ALLOWED) {
                            viewModel.setTvAlertOverlayDisplayDurationSeconds(selectedDuration.toInt())
                            changed()
                        }
                    }
                },
                manageNotificationPause = { openConnections(NativeConnectionPage.NOTIFICATIONS) },
                confirmDisconnect = { disconnect ->
                    showChoiceDialog("Disconnect Home Assistant?", "Messages will stop on this TV", listOf(
                        Triple("keep", "Keep connection", ""), Triple("disconnect", "Disconnect", "Remove the saved Home Assistant sign-in"),
                    )) { choice ->
                        if (choice == "disconnect") runProtectedAction(PinScope.DESTRUCTIVE_ACTIONS, "disconnecting Home Assistant", disconnect)
                    }
                },
            ).also { it.createContent() }
        }
        if (selected != NativeConnectionPage.SETUP) {
            activeGuidedSetup?.close(); activeGuidedSetup = null
        } else if (activeGuidedSetup == null) {
            activeGuidedSetup = NativeGuidedSetup(activity, LiveGuidedSetupActions(activity, viewModel,
                pin = {
                    showPinDialog("Create a PIN for settings") { first ->
                        showPinDialog("Enter the PIN again", onCancelled = { first.fill('\u0000') }) { second ->
                            viewModel.setupLocalPin(first, second, setOf(PinScope.SETTINGS))
                        }
                    }
                }, privacy = {
                    route = NativeRoute(NativeDestination.SETTINGS, SettingsPage.PRIVACY)
                    render(currentState)
                }, finished = { progress, completed ->
                    configuredStartupApplied = true
                    route = if (completed && progress.services?.frigate == true && currentState.activeProfile != null && currentState.snapshot != null)
                        NativeRoute(NativeDestination.HOME)
                    else NativeRoute(NativeDestination.CONNECTIONS, connectionPage =
                        if (progress.services?.homeAssistant == true) NativeConnectionPage.HOME_ASSISTANT else NativeConnectionPage.FRIGATE)
                    render(currentState)
                }))
        }
        val paneState = NativeSettingsUiState(
            title = selected.label,
            subtitle = when (selected) { NativeConnectionPage.FRIGATE -> "Connect your cameras and recordings"
                NativeConnectionPage.HOME_ASSISTANT -> "Receive messages from your home automations"
                NativeConnectionPage.SETUP -> "Choose services and check what is ready"
                NativeConnectionPage.NOTIFICATIONS -> "Pause Frigate and Home Assistant together" },
            categories = categories,
            detailRows = detailRows,
            selectedCategoryKey = "connections:category:${selected.name}",
            preferredDetailFocusKey = focusMemory[route.focusMemoryKey],
            detailContent = if (selected == NativeConnectionPage.SETUP) activeGuidedSetup?.view else activeHomeAssistantSetup?.view,
        )
        if (activeConnections != null) {
            activeConnections?.update(paneState)
            return
        }
        val surface = NativeSettingsSurface(activity, paneState,
            onActivate = { handlers[it]?.invoke() }, onAdjust = { _, _ -> },
            onFocused = { key, view -> focusMemory[route.focusMemoryKey] = key; root.rememberContentFocus(view) },
            initialPosition = connectionsPosition, sectionTitle = "Connections")
        activeConnections = surface
        contentHost.removeAllViews()
        contentHost.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun connectionRows(state: Phase0UiState): List<NativeRowModel> {
        val profile = state.activeProfile ?: state.savedProfile
        return buildList {
            if (profile == null) {
                add(info("connection:welcome", "Connect Frigate", "Watch cameras and recordings from your Frigate server"))
                state.errorMessage?.let { add(info("connection:error", "Connection needs attention", it)) }
                add(action("connection:connect", "Connect Frigate", value = "Sign in") {
                    showConnectionDialog("", "", "", "8554")
                })
                return@buildList
            }
            add(section("connection:heading", "Frigate connection"))
            add(info("connection:server", "Server", profile.apiBaseUrl))
            add(
                info(
                    "connection:user",
                    "Access",
                    when {
                        profile.usesUnauthenticatedFrigatePort -> "No account • full Frigate access"
                        else -> profile.username
                    },
                ),
            )
            add(
                info(
                    "connection:rtsp",
                    "Live video route",
                    "${profile.rtspHostOverride ?: "Use server host"}:${profile.rtspPort}",
                ),
            )
            if (state.savedSessionRecoveryAvailable) {
                add(info("connection:unavailable", "Frigate is unavailable", state.errorMessage ?: "Try the saved connection again"))
                add(action("connection:retry", "Try again", value = "Reconnect") { viewModel.retrySavedSession() })
            }
            add(action("connection:edit", "Change connection", "Enter a different server or account", "Open") {
                showConnectionDialog(
                    profile.apiBaseUrl,
                    profile.username,
                    profile.rtspHostOverride.orEmpty(),
                    profile.rtspPort.toString(),
                )
            })
            add(action("connection:rtsp:edit", "Change live video route", "Does not sign out or change the Frigate account", "Open") {
                showRtspRouteDialog(profile.rtspHostOverride.orEmpty(), profile.rtspPort.toString())
            })
            if (state.activeProfile != null) add(action("connection:signout", "Sign out", "Keep the server address on this TV", "Sign out") {
                viewModel.logout(forgetServer = false)
            })
            add(action("connection:forget", "Forget this server", "Remove the saved server and sign-in", "Forget") {
                runProtectedAction(PinScope.DESTRUCTIVE_ACTIONS, "forgetting this server") {
                    viewModel.logout(forgetServer = true)
                }
            })
        }
    }

    private fun showServer(state: Phase0UiState) {
        if (serverPage == NativeServerPage.CONNECTION) {
            openConnections(NativeConnectionPage.FRIGATE)
            return
        }
        if (!state.information.loadedOnce && !state.information.loading) {
            viewModel.loadInformation()
        }
        val summary = state.information.summary
        val tabs = NativeServerPage.entries.filter { it != NativeServerPage.CONNECTION }.map { page ->
            val key = "server:tab:${page.name}"
            handlers[key] = {
                if (page == NativeServerPage.CONNECTION) openConnections(NativeConnectionPage.FRIGATE)
                else {
                    serverPage = page
                    render(currentState)
                }
            }
            NativeBrowserTab(
                key = key,
                label = page.name.lowercase().replaceFirstChar(Char::uppercase),
                selected = serverPage == page,
            )
        }
        val rows = buildList {
            if (serverPage != NativeServerPage.CONNECTION) {
                state.information.errorMessage?.let { add(info("server:error", "Server information", it)) }
            }
            when (serverPage) {
                NativeServerPage.CONNECTION -> addAll(connectionRows(state))
                NativeServerPage.PERFORMANCE -> {
                    add(section("server:performance", "Performance", "Current server and camera workload"))
                    summary?.performance?.let { performance ->
                        val detectorSpeeds = performance.detectors.mapNotNull { it.inferenceSpeedMs }
                        val inferenceValue = when (detectorSpeeds.size) {
                            0 -> "—"
                            1 -> "${String.format(Locale.US, "%.1f", detectorSpeeds.single())} ms"
                            else -> "${String.format(Locale.US, "%.1f", detectorSpeeds.min())}–${String.format(Locale.US, "%.1f", detectorSpeeds.max())} ms"
                        }
                        add(
                            dashboard(
                                "server:performance:overview",
                                NativeDashboardMetric("Uptime", performance.uptimeSeconds?.let(::durationLabel) ?: "—", NativeTheme.palette.focus),
                                NativeDashboardMetric("System CPU", performance.systemCpuPercent.metric("%"), 0xFFFFB454.toInt()),
                                NativeDashboardMetric("Memory", performance.frigateMemoryPercent.metric("%"), 0xFFD29BFF.toInt()),
                                NativeDashboardMetric("Detection", performance.detectionFps.metric("fps"), 0xFF65D68A.toInt()),
                                NativeDashboardMetric("Inference", inferenceValue, NativeTheme.palette.activityBadge),
                            ),
                        )
                        add(
                            info(
                                "server:fps",
                                "Frame rates",
                                "Camera ${performance.cameraFps.metric("fps")} • Process ${performance.processFps.metric("fps")} • Detection ${performance.detectionFps.metric("fps")}",
                                "Skipped ${performance.skippedFps.metric("fps")}",
                            ),
                        )
                        add(
                            visual(
                                key = "server:cpu",
                                title = "CPU use",
                                description = "System ${performance.systemCpuPercent.metric("%")} • Frigate ${performance.frigateCpuPercent.metric("%")}",
                                value = "A full bar represents 100%",
                                segments = listOf(
                                    NativeMeterSegment(performance.frigateCpuPercent ?: 0.0, NativeTheme.palette.focus),
                                    NativeMeterSegment((100.0 - (performance.frigateCpuPercent ?: 0.0)).coerceAtLeast(0.0), NativeTheme.palette.panelSelected),
                                ),
                            ),
                        )
                        if (performance.detectors.isNotEmpty()) {
                            add(section("server:detectors", "Detectors", "Lower inference time is faster"))
                        }
                        performance.detectors.forEach { detector ->
                            add(
                                info(
                                    "server:detector:${detector.name}",
                                    "Detector ${detector.name}",
                                    detector.inferenceSpeedMs?.let { "${String.format(Locale.US, "%.1f", it)} ms inference" }
                                        ?: "Inference speed not reported",
                                ),
                            )
                        }
                        performance.accelerators.forEach { accelerator ->
                            add(
                                info(
                                    "server:accelerator:${accelerator.name}",
                                    accelerator.name,
                                    accelerator.kind,
                                    "Usage ${accelerator.usagePercent.metric("%")} • Memory ${accelerator.memoryPercent.metric("%")}",
                                ),
                            )
                        }
                        performance.temperatures.forEach { temperature ->
                            add(info("server:temperature:${temperature.name}", temperature.name, "${String.format(Locale.US, "%.1f", temperature.celsius)} °C"))
                        }
                        if (performance.cameras.isNotEmpty()) {
                            add(section("server:camera-performance", "Cameras", "Per-camera processing rates"))
                        }
                        performance.cameras.forEach { camera ->
                            add(
                                info(
                                    "server:camera:${camera.cameraName}",
                                    camera.displayName,
                                    "Camera ${camera.cameraFps.metric("fps")} • Process ${camera.processFps.metric("fps")} • Detection ${camera.detectionFps.metric("fps")}",
                                    "Skipped ${camera.skippedFps.metric("fps")}",
                                ),
                            )
                        }
                    } ?: add(info("server:performance:loading", "Performance data", if (state.information.loading) "Loading" else "Not reported by this server"))
                }
                NativeServerPage.STORAGE -> {
                    add(section("server:storage", "Storage", "Recording space reported by Frigate"))
                    summary?.storage?.let { storage ->
                        val visibleCameraUsage = storage.cameras.sumOf { it.usageMiB }
                        val otherUsed = (storage.volume.usedMiB - visibleCameraUsage).coerceAtLeast(0.0)
                        add(
                            dashboard(
                                "server:storage:overview",
                                NativeDashboardMetric("Used", storage.volume.usedMiB.storageAmount(), NativeTheme.palette.focus),
                                NativeDashboardMetric("Available", storage.volume.freeMiB.storageAmount(), 0xFF65D68A.toInt()),
                                NativeDashboardMetric("Total", storage.volume.totalMiB.storageAmount(), 0xFF53B7FF.toInt()),
                                NativeDashboardMetric("Cameras", visibleCameraUsage.storageAmount(), 0xFFFFB454.toInt()),
                            ),
                        )
                        add(
                            visual(
                                key = "server:storage:used",
                                title = "Recording storage",
                                description = "${storage.volume.usedMiB.storageAmount()} used • ${storage.volume.freeMiB.storageAmount()} available",
                                value = "Cameras ${visibleCameraUsage.storageAmount()}   Other ${otherUsed.storageAmount()}   Total ${storage.volume.totalMiB.storageAmount()}",
                                segments = storage.cameras.mapIndexed { index, camera ->
                                    NativeMeterSegment(camera.usageMiB, storageColor(index))
                                } + listOf(
                                    NativeMeterSegment(otherUsed, NativeTheme.palette.secondaryText),
                                    NativeMeterSegment(storage.unusedMiB, NativeTheme.palette.panelSelected),
                                ),
                            ),
                        )
                        add(section("server:storage:cameras", "Cameras", "Color matches each camera's share of the storage bar"))
                        storage.cameras.forEachIndexed { index, camera ->
                            add(
                                info(
                                    "server:storage:${camera.cameraName}",
                                    camera.displayName,
                                    "${camera.usageMiB.storageAmount()} • ${String.format(Locale.US, "%.1f", camera.percentageOfTotal)}% of camera storage",
                                    "Recording rate ${camera.bandwidthMiBPerHour.storageAmount()}/hour",
                                    indicatorColor = storageColor(index),
                                ),
                            )
                        }
                    } ?: add(info("server:storage:loading", "Storage data", if (state.information.loading) "Loading" else "Not reported by this server"))
                }
            }
            if (serverPage != NativeServerPage.CONNECTION) {
                add(action("server:refresh", "Refresh server data", value = "Refresh") { viewModel.loadInformation(force = true) })
            }
        }
        showSettingsList("server", "Server", "Connection, performance, and recording space", rows, tabs)
    }

    private fun showAdvanced(state: Phase0UiState) {
        val device = state.device
        val rows = buildList {
            add(
                info(
                    "advanced:device",
                    "This TV",
                    device?.let { "${it.manufacturer} ${it.model} • Android ${it.androidRelease} • API ${it.apiLevel}" }
                        ?: "Device details are still loading",
                ),
            )
            add(section("advanced:decoders", "Video decoders", "Technical device capabilities used for playback"))
            device?.codecs.orEmpty().forEachIndexed { index, codec ->
                add(
                    info(
                        "advanced:codec:$index",
                        codec.label,
                        "${codec.mimeType} • ${codec.decoders.size} ${if (codec.decoders.size == 1) "decoder" else "decoders"}${if (codec.hasHardwareDecoder) " • hardware available" else ""}",
                        codec.decoders.joinToString("\n") { decoder ->
                            buildList {
                                add(decoder.name)
                                when {
                                    decoder.hardwareAccelerated == true -> add("hardware")
                                    decoder.softwareOnly == true -> add("software")
                                }
                                if (decoder.vendor == true) add("vendor")
                                if (decoder.adaptivePlayback) add("adaptive playback")
                                decoder.maxSupportedInstances?.let { add("up to $it instances") }
                            }.joinToString(" • ")
                        }.ifBlank { "No decoder names advertised" },
                    ),
                )
            }
            state.snapshot?.warnings.orEmpty().forEachIndexed { index, warning ->
                add(info("advanced:warning:$index", "Server warning", warning))
            }
            add(
                action(
                    "advanced:refresh",
                    if (state.loading) "Refreshing device data" else "Refresh device data",
                    enabled = !state.loading,
                    value = "Refresh",
                ) { viewModel.refresh() },
            )
        }
        showSettingsList("advanced", "Device Info", "TV and decoder details for troubleshooting", rows)
    }

    private fun showAbout(state: Phase0UiState) {
        val aboutText = aboutOpahText(displayedVersionName(), state.snapshot?.frigateVersion)
        val versionLabel = "Version ${displayedVersionName()}"
        val surface = activeAbout ?: NativeAboutSurface(
            activity, aboutText, versionLabel,
            onOpenProject = {
                runCatching {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, OPAH_REPOSITORY_URL.toUri()))
                }.onFailure {
                    showReadingDialog("Project address", "Open this address in a browser", OPAH_REPOSITORY_URL)
                }
            },
            onNavigateOutLeft = { activeSettings?.focusSelectedCategory("settings:category:ABOUT") },
        )
        surface.update(aboutText, versionLabel)
        showSettingsPane("About Opah", "Project information", emptyList(), state, detailContent = surface)
        activeAbout = surface
    }

    private fun showLoading(state: Phase0UiState) {
        setNavigationVisible(false)
        showWelcome(
            "system:loading",
            "Opah",
            state.statusMessage,
            listOf(info("system:loading:status", "Getting things ready", state.errorMessage ?: state.statusMessage)),
            loading = true,
        )
    }

    private fun showRecovery(state: Phase0UiState) {
        route = NativeRoute(NativeDestination.CONNECTIONS)
        configuredStartupApplied = true
        showConnections(state)
    }

    private fun showConnectionSetup(state: Phase0UiState) {
        route = NativeRoute(NativeDestination.CONNECTIONS, connectionPage = when {
            homeAssistant.hasSession() -> NativeConnectionPage.HOME_ASSISTANT
            state.savedProfile != null -> NativeConnectionPage.FRIGATE
            else -> NativeConnectionPage.SETUP
        })
        configuredStartupApplied = true
        showConnections(state)
    }

    private fun showWelcome(
        surfaceKey: String,
        title: String,
        subtitle: String,
        rows: List<NativeRowModel>,
        loading: Boolean = false,
    ) {
        if (activeSurfaceKey == surfaceKey && activeWelcome != null) {
            activeWelcome?.update(title, subtitle, rows, loading)
            return
        }
        clearActiveSurface()
        setContentInsets(fullScreen = true)
        activeSurfaceKey = surfaceKey
        val surface = NativeWelcomeSurface(
            activity, title, subtitle, rows,
            onActivate = { key -> handlers[key]?.invoke() },
            onFocused = { key, view ->
                focusMemory[route.focusMemoryKey] = key
                root.rememberContentFocus(view)
            },
            loading = loading,
        )
        activeWelcome = surface
        contentHost.removeAllViews()
        contentHost.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        surface.post { if (!root.navigationOpen) surface.focusPrimaryAction() }
    }

    private fun showCameraGroup(state: Phase0UiState) {
        val group = requireNotNull(state.cameraGroupView)
        val surfaceKey = "surface:group:${group.title}:${group.streams.joinToString { it.camera.name }}"
        if (activeSurfaceKey == surfaceKey && activeCameraGroup != null) return
        clearActiveSurface()
        setNavigationVisible(false)
        setContentInsets(fullScreen = true)
        activeSurfaceKey = surfaceKey
        val surface = NativeCameraGroupSurface(
            activity = activity,
            state = group,
            imageLoadedAtMillis = { cameraName -> viewModel.cachedCameraImage(cameraName)?.loadedAtMillis },
            preferRtpTcp = state.settings.preferRtpTcp,
            stretchedCameraNames = state.settings.stretchedCameraNames,
            cachedBitmap = { cameraName -> viewModel.cachedCameraImage(cameraName)?.bitmap },
            refreshBitmap = { cameraName, height ->
                viewModel.refreshCameraImage(cameraName, height).getOrNull()?.bitmap
            },
            pictureInPictureAvailable = actions.pictureInPictureAvailable,
            onEnterPictureInPicture = actions.onEnterPictureInPicture,
            onStartMonitor = viewModel::startMonitorMode,
            onBack = viewModel::closeCameraGroup,
        )
        activeCameraGroup = surface
        contentHost.removeAllViews()
        contentHost.addView(
            surface,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    private fun showMonitor(state: Phase0UiState) {
        val monitor = requireNotNull(state.monitorMode)
        val surfaceKey = "surface:monitor:${monitor.title}"
        if (activeSurfaceKey == surfaceKey && activeMonitor != null) {
            activeMonitor?.update(monitor, state.settings.stretchedCameraNames)
            return
        }
        clearActiveSurface()
        setNavigationVisible(false)
        setContentInsets(fullScreen = true)
        activeSurfaceKey = surfaceKey
        val surface = NativeMonitorSurface(
            activity = activity,
            initialState = monitor,
            initialStretchedCameraNames = state.settings.stretchedCameraNames,
            imageLoadedAtMillis = { cameraName -> viewModel.cachedCameraImage(cameraName)?.loadedAtMillis },
            cachedBitmap = { cameraName -> viewModel.cachedCameraImage(cameraName)?.bitmap },
            refreshBitmap = { cameraName, height ->
                viewModel.refreshCameraImage(cameraName, height).getOrNull()?.bitmap
            },
            onPreset = viewModel::setMonitorPreset,
            onManualCamera = viewModel::selectMonitorCamera,
            onPlaybackReady = viewModel::monitorPlaybackReady,
            onPlaybackFailed = viewModel::monitorPlaybackFailed,
            onKeepScreenAwake = viewModel::setMonitorKeepScreenAwake,
            onAudioEnabled = viewModel::setMonitorAudioEnabled,
            onExitMinutes = viewModel::setMonitorExitMinutes,
            onBack = viewModel::closeMonitorMode,
        )
        activeMonitor = surface
        contentHost.removeAllViews()
        contentHost.addView(
            surface,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    private fun showPlayback(state: Phase0UiState) {
        val request = requireNotNull(state.playback)
        val surfaceKey = app.opah.tv.playback.playbackSurfaceKey(request)
        val controls = playbackControls(state, request)
        val actionMessage = state.review.actionErrorFor(request.activityItemId)?.message
            ?: state.review.playbackNavigationMessage
            ?: state.liveActions.errorMessage
            ?: state.liveActions.message
            ?: state.review.savedClipMessage.takeIf { state.review.savedClipItemId == request.activityItemId }
            ?: state.history.savedMessage.takeIf { request.recordingSaveKey != null }
        // Keep an unresolved write error readable instead of auto-hiding its recovery action.
        val feedbackPending = state.review.actionErrorFor(request.activityItemId) != null ||
            state.liveActions.recordingBusy || state.review.advancingPlayback ||
            (request.activityItemId != null && state.review.savingClipItemId == request.activityItemId) ||
            (request.recordingStartTime != null && state.history.savingSlotStartTime == request.recordingStartTime)
        if (activeSurfaceKey == surfaceKey && activePlayback != null) {
            activePlayback?.updateLiveRecoveryActions(liveRecoveryActions(state, request))
            activePlayback?.updateControls(controls, actionMessage, feedbackPending)
            return
        }
        clearActiveSurface()
        setNavigationVisible(false)
        setContentInsets(fullScreen = true)
        activeSurfaceKey = surfaceKey
        val queueIndex = request.activityItemId?.let { request.activityContextItemIds.indexOf(it) }
            ?.takeIf { it >= 0 }
        val displayRequest = if (queueIndex != null) {
            val total = request.activityContextItemIds.size
            val sequence = if (request.briefingHighlight) {
                "Highlight ${queueIndex + 1} of $total"
            } else {
                "Activity ${queueIndex + 1} of $total"
            }
            val whenRecorded = request.recordingStartTime?.let(::formatCompactDateTime)
            request.copy(
                title = listOfNotNull(sequence, whenRecorded).joinToString(" • "),
                detail = buildList {
                    add(request.title)
                    if (request.briefingHighlight) add("${total - queueIndex - 1} remaining")
                }.joinToString(" • "),
            )
        } else if (
            request.kind == app.opah.tv.playback.PlaybackKind.RECORDED &&
            request.recordingStartTime != null
        ) {
            request.copy(
                title = "${formatCompactDateTime(request.recordingStartTime)} • ${request.title}",
            )
        } else {
            request
        }
        val surface = NativePlaybackSurface(
            activity = activity,
            request = displayRequest,
            preferRtpTcp = state.settings.preferRtpTcp,
            startMuted = state.settings.startLiveMuted,
            diagnosticsEnabled = state.settings.diagnosticsEnabled,
            initialControls = controls,
            initialActionMessage = actionMessage,
            initialFeedbackPending = feedbackPending,
            initialLiveRecoveryActions = liveRecoveryActions(state, request),
            prepareHttpLive = viewModel::prepareAuthenticatedHttpLive,
            pictureInPictureAvailable = actions.pictureInPictureAvailable && !request.compatibilityTest && request.httpsLiveRequestId == null,
            onEnterPictureInPicture = actions.onEnterPictureInPicture,
            onCompatibilityTestStatus = viewModel::reportPlaybackCompatibilityStatus,
            onClose = {
                viewModel.closePlayback()
                if (request.compatibilityTest) viewModel.loadCameraPlaybackCompatibilityChoices()
            },
            onAutomaticReviewWatchThresholdReached = {
                val item = request.activityItemId?.let { id ->
                    playbackItems(viewModel.state.value).firstOrNull { it.id == id }
                }
                if (item != null) viewModel.onReviewPlaybackWatchThresholdReached(item)
            },
            onPlaybackEnded = {
                val latestState = viewModel.state.value
                val item = request.activityItemId?.let { id -> playbackItems(latestState).firstOrNull { it.id == id } }
                if (item != null) viewModel.onReviewPlaybackCompleted(item)
                request.returnToLiveCameraName?.let { cameraName ->
                    latestState.snapshot?.cameras?.firstOrNull { it.name == cameraName }?.let(viewModel::playAutomatic)
                }
            },
        )
        activePlayback = surface
        contentHost.removeAllViews()
        contentHost.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun showPtz(state: Phase0UiState) {
        val cameraName = state.ptz.cameraName ?: return
        val camera = state.snapshot?.cameras?.firstOrNull { it.name == cameraName } ?: run {
            viewModel.closePtzControls()
            return
        }
        val info = state.snapshot.ptzCameras[cameraName] ?: run {
            viewModel.closePtzControls()
            return
        }
        val key = "ptz:$cameraName"
        if (activeSurfaceKey == key && activePtz != null) {
            activePtz?.update(state.ptz.connection, state.ptz.errorMessage)
            return
        }
        clearActiveSurface()
        setNavigationVisible(false)
        setContentInsets(fullScreen = true)
        activeSurfaceKey = key
        val surface = NativePtzSurface(
            activity = activity,
            cameraTitle = camera.displayName,
            cameraName = cameraName,
            info = info,
            initialConnection = state.ptz.connection,
            initialError = state.ptz.errorMessage,
            cachedBitmap = viewModel.cachedCameraImage(cameraName)?.bitmap,
            refreshBitmap = { viewModel.refreshCameraImage(cameraName, 500).getOrNull()?.bitmap },
            onCommand = { command ->
                runProtectedAction(PinScope.ADMINISTRATIVE_ACTIONS, "camera controls") {
                    viewModel.sendPtzCommand(command)
                }
            },
            onRetry = {
                runProtectedAction(PinScope.ADMINISTRATIVE_ACTIONS, "camera controls") {
                    viewModel.retryPtzControls()
                }
            },
            onClose = viewModel::closePtzControls,
        )
        activePtz = surface
        contentHost.removeAllViews()
        contentHost.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun liveRecoveryActions(
        state: Phase0UiState,
        request: app.opah.tv.playback.PlaybackRequest,
    ): NativeLiveRecoveryActions {
        val requestId = request.httpsLiveRequestId ?: request.liveCompatibilityRequestId ?: return NativeLiveRecoveryActions()
        if (request.compatibilityTest) return NativeLiveRecoveryActions()
        val options = liveRecoveryOptions(state, request)
        val camera = state.snapshot?.cameras?.firstOrNull { it.name == request.cameraName }
        val https = request.httpsLiveRequestId != null
        return NativeLiveRecoveryActions(
            retry = if (options.any { it.streamName == request.liveStreamName }) {
                { if (https) viewModel.recoverHttpsPlayback(requestId) else viewModel.recoverLivePlayback(requestId) }
            } else null,
            tryHttps = if (!https && camera != null && viewModel.canPlayUsingHttps(camera)) {
                { viewModel.playUsingHttps(camera) }
            } else null,
            chooseConnection = if (https && camera != null) {
                { showChoiceDialog("Choose a connection", "Use the standard connection for this camera",
                    listOf(Triple("standard", "Standard connection", ""))) { viewModel.recoverHttpsPlayback(requestId, standardConnection = true) } }
            } else null,
            chooseStream = if (options.any { it.streamName != request.liveStreamName }) {
                { showLiveStreamRecovery(requestId) }
            } else null,
        )
    }

    private fun liveRecoveryOptions(state: Phase0UiState, request: app.opah.tv.playback.PlaybackRequest) =
        state.snapshot?.cameras?.firstOrNull {
            it.name == request.cameraName && it.name in state.snapshot.user.allowedCameras
        }?.streams.orEmpty().filter {
            it.metadata?.available != false && (request.httpsLiveRequestId == null ||
                app.opah.tv.playback.httpLiveUri(state.activeProfile?.apiBaseUrl.orEmpty(), it.streamName) != null)
        }.distinctBy { it.streamName }

    private fun showLiveStreamRecovery(requestId: String) {
        val state = viewModel.state.value
        val request = state.playback?.takeIf { (it.httpsLiveRequestId ?: it.liveCompatibilityRequestId) == requestId } ?: return
        val options = liveRecoveryOptions(state, request).filter { it.streamName != request.liveStreamName }
        if (options.isEmpty()) return
        showChoiceDialog("Choose another stream", "Use a different configured stream for this viewing session",
            options.map { Triple(it.streamName, it.label, "") },
        ) { name ->
            if (request.httpsLiveRequestId != null) {
                viewModel.recoverHttpsPlayback(requestId, name)
            } else viewModel.recoverLivePlayback(requestId, name)
        }
    }

    private fun playbackControls(state: Phase0UiState, request: app.opah.tv.playback.PlaybackRequest): List<NativePlaybackControl> {
        if (request.compatibilityTest) return emptyList()
        val snapshot = state.snapshot
        val cameras = snapshot?.cameras.orEmpty()
        val controls = mutableListOf<NativePlaybackControl>()
        if (request.kind == app.opah.tv.playback.PlaybackKind.LIVE) {
            val (previous, next) = cameraNeighbors(cameras, request.cameraName ?: state.activeCameraName)
            previous?.let { camera ->
                controls += NativePlaybackControl("previous", "Previous: ${camera.displayName}") {
                    viewModel.playAutomatic(camera)
                }
            }
            request.cameraName?.takeIf { cameraName -> cameras.any { it.name == cameraName } }?.let { cameraName ->
                controls += NativePlaybackControl("rewind", "30 seconds earlier") {
                    viewModel.playInstantRewind(cameraName)
                }
            }
            next?.let { camera ->
                controls += NativePlaybackControl("next", "Next: ${camera.displayName}") {
                    viewModel.playAutomatic(camera)
                }
            }
            request.cameraName
                ?.takeIf { snapshot?.capabilities?.supports(FrigateFeature.INSTANT_SNAPSHOT) == true }
                ?.let { cameraName ->
                    controls += NativePlaybackControl(
                        key = "snapshot",
                        label = if (state.liveActions.snapshotCapturing) "Taking snapshot" else "Share snapshot",
                        enabled = !state.liveActions.snapshotCapturing,
                    ) { viewModel.captureInstantSnapshot(cameraName, actions.onShareSnapshot) }
                }
            request.cameraName
                ?.takeIf { snapshot?.capabilities?.supports(FrigateFeature.ON_DEMAND_RECORDING) == true }
                ?.let { cameraName ->
                    val recording = state.liveActions.recordingEventId != null
                    controls += NativePlaybackControl(
                        key = "recording",
                        label = when {
                            state.liveActions.recordingBusy -> "Updating recording"
                            recording -> "Stop recording"
                            else -> "Start recording"
                        },
                        enabled = !state.liveActions.recordingBusy,
                        selected = recording,
                    ) {
                        runProtectedAction(PinScope.ADMINISTRATIVE_ACTIONS, "recording controls") {
                            if (viewModel.state.value.liveActions.recordingEventId == null) {
                                viewModel.startOnDemandRecording(cameraName)
                            } else {
                                viewModel.stopOnDemandRecording()
                            }
                        }
                    }
                }
        } else {
            request.returnToLiveCameraName?.let { cameraName ->
                cameras.firstOrNull { it.name == cameraName }?.let { camera ->
                    controls += NativePlaybackControl("return-live", "Return to live") {
                        viewModel.playAutomatic(camera)
                    }
                }
            }
            val activityItems = playbackItems(state)
            val activityItem = request.activityItemId?.let { id -> activityItems.firstOrNull { it.id == id } }
            activityItem?.let { item ->
                state.review.actionErrorFor(item.id)?.takeIf { it.canRetry }?.let { failure ->
                    controls += NativePlaybackControl("retry-activity", "Retry",
                        enabled = !state.review.hasPendingWrite()) {
                        retryReviewAction(item, failure)
                    }
                }
                controls += NativePlaybackControl(
                    key = "reviewed",
                    label = when {
                        state.review.markingReviewedItemId == item.id -> "Updating activity"
                        item.hasBeenReviewed -> "Mark unreviewed"
                        else -> "Mark reviewed"
                    },
                    enabled = !state.review.hasPendingWrite(),
                    selected = item.hasBeenReviewed,
                ) { viewModel.setReviewReviewed(item, !item.hasBeenReviewed) }
            }
            val historySaveKey = request.recordingSaveKey
            if (activityItem != null || historySaveKey != null) {
                val saving = if (activityItem != null) {
                    state.review.savingClipItemId == activityItem.id
                } else {
                    state.history.savingSlotStartTime == request.recordingStartTime
                }
                val saved = if (activityItem != null) {
                    activityItem.id in state.review.savedClipItemIds
                } else {
                    historySaveKey in state.history.savedSlotKeys
                }
                controls += NativePlaybackControl(
                    key = "save",
                    label = when {
                        saving -> "Saving recording"
                        saved -> "Recording saved"
                        else -> "Save recording"
                    },
                    enabled = !saving && !saved && (activityItem == null || !state.review.hasPendingWrite()),
                    selected = saved,
                ) {
                    runProtectedAction(PinScope.ADMINISTRATIVE_ACTIONS, "saving recordings") {
                        if (activityItem != null) {
                            viewModel.saveReviewClip(activityItem)
                        } else {
                            viewModel.saveHistoryClip(request)
                        }
                    }
                }
            }
            val nextItem = nextReviewPlaybackItem(
                currentItemId = request.activityItemId,
                contextItemIds = request.activityContextItemIds,
                availableItems = activityItems,
            )
            val nextState = nextActivityControlState(
                hasContext = request.activityContextItemIds.isNotEmpty(),
                hasNextActivity = nextItem != null,
                queueContext = request.activityQueueContext,
                loading = state.review.advancingPlayback,
            )
            nextState?.let { nextControl ->
                val index = request.activityContextItemIds.indexOf(request.activityItemId).coerceAtLeast(0)
                val remaining = (request.activityContextItemIds.size - index - 1).coerceAtLeast(0)
                val label = if (request.briefingHighlight && nextControl.label == "Next activity") {
                    "Next highlight ($remaining remaining)"
                } else {
                    nextControl.label
                }
                controls += NativePlaybackControl(
                    key = "next-activity",
                    label = label,
                    enabled = nextControl.enabled,
                ) { viewModel.playNextReviewActivity() }
            }
        }
        request.stretchPreferenceKey?.let { preferenceKey ->
            val stretched = preferenceKey in state.settings.stretchedCameraNames
            controls += NativePlaybackControl(
                key = "stretch",
                label = if (stretched) "Fit video to screen" else "Stretch video to screen",
                selected = stretched,
            ) { viewModel.updateCameraStretch(preferenceKey, !stretched) }
        }
        return controls
    }

    private fun showList(
        surfaceKey: String,
        title: String,
        subtitle: String,
        rows: List<NativeRowModel>,
    ) {
        val recreate = activeSurfaceKey != surfaceKey || currentListAdapter == null
        if (recreate) {
            clearActiveSurface()
            setContentInsets(fullScreen = false)
            activeSurfaceKey = surfaceKey
            val page = LayoutInflater.from(activity).inflate(R.layout.native_content_list, contentHost, false)
            page.findViewById<TextView>(R.id.native_page_title).apply {
                text = title
                setTextColor(NativeTheme.palette.text)
            }
            page.findViewById<TextView>(R.id.native_page_subtitle).apply {
                text = subtitle
                setTextColor(NativeTheme.palette.secondaryText)
            }
            val recycler = page.findViewById<RecyclerView>(R.id.native_page_list)
            recycler.layoutManager = NativeLinearLayoutManager(activity)
            recycler.itemAnimator = null
            val adapter = NativeListAdapter(
                onActivate = { key -> handlers[key]?.invoke() },
                onFocused = { key, view ->
                    focusMemory[route.focusMemoryKey] = key
                    root.rememberContentFocus(view)
                },
                onThumbnailRequested = ::loadThumbnail,
            )
            recycler.addNativeFlatDividers()
            recycler.adapter = adapter
            currentRecyclerView = recycler
            currentListAdapter = adapter
            contentHost.removeAllViews()
            contentHost.addView(page)
        } else {
            val page = contentHost.getChildAt(0)
            page.findViewById<TextView>(R.id.native_page_title).text = title
            page.findViewById<TextView>(R.id.native_page_subtitle).text = subtitle
        }
        currentListAdapter?.submitList(rows) {
            if (recreate) {
                listScrollMemory[surfaceKey]?.let { currentRecyclerView?.layoutManager?.onRestoreInstanceState(it) }
                currentRecyclerView?.doOnPreDraw { requestRememberedOrFirstFocus(rows) }
            }
        }
    }

    private fun showSettingsList(
        key: String,
        title: String,
        subtitle: String,
        rows: List<NativeRowModel>,
        detailTabs: List<NativeBrowserTab> = emptyList(),
    ) {
        showSettingsPane(title, subtitle, rows, currentState, detailTabs)
    }

    private fun showSettingsPane(
        title: String,
        subtitle: String,
        rows: List<NativeRowModel>,
        state: Phase0UiState,
        detailTabs: List<NativeBrowserTab> = emptyList(),
        detailContent: View? = null,
    ) {
        activeAppearance?.takeIf { it !== detailContent }?.let {
            appearancePosition = it.capturePosition()
            activeAppearance = null
        }
        if (activeAbout !== detailContent) activeAbout = null
        val selectedPage = when (route.settingsPage) {
            SettingsPage.PLAYBACK_TEST,
            SettingsPage.CAMERA_DIAGNOSTICS,
            -> SettingsPage.PLAYBACK
            SettingsPage.CONNECTION -> SettingsPage.SYSTEM
            SettingsPage.MAIN -> null
            else -> route.settingsPage
        }
        val categories = SETTINGS_PAGE_ORDER.map { page ->
            action(
                key = "settings:category:${page.name}",
                title = page.label,
                description = settingsPageValue(page, state),
                value = "",
                selected = page == selectedPage,
            ) { openSettingsPage(page) }
        }
        val paneState = NativeSettingsUiState(
            title = title,
            subtitle = subtitle,
            categories = categories,
            detailRows = rows,
            selectedCategoryKey = selectedPage?.let { "settings:category:${it.name}" },
            detailTabs = detailTabs,
            preferredDetailFocusKey = focusMemory[route.focusMemoryKey],
            detailContent = detailContent,
        )
        if (activeSurfaceKey == "settings:pane" && activeSettings != null) {
            activeSettings?.update(paneState)
            return
        }
        clearActiveSurface()
        setContentInsets(fullScreen = false)
        activeSurfaceKey = "settings:pane"
        val surface = NativeSettingsSurface(
            activity = activity,
            initialState = paneState,
            onActivate = { key -> handlers[key]?.invoke() },
            onAdjust = { key, direction -> adjustmentHandlers[key]?.invoke(direction) },
            onFocused = { key, view ->
                focusMemory[route.focusMemoryKey] = key
                root.rememberContentFocus(view)
            },
            initialPosition = settingsPosition,
        )
        activeSettings = surface
        contentHost.removeAllViews()
        contentHost.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun requestRememberedOrFirstFocus(rows: List<NativeRowModel>) {
        val targetKey = focusMemory[route.focusMemoryKey]
            ?.takeIf { remembered -> rows.any { it.key == remembered && it.focusable } }
            ?: rows.firstOrNull { it.focusable }?.key
            ?: return
        currentRecyclerView?.post {
            if (root.navigationOpen) return@post
            val adapter = currentListAdapter ?: return@post
            val position = rows.indexOfFirst { it.key == targetKey }
            if (position >= 0) {
                val visible = currentRecyclerView?.findViewHolderForAdapterPosition(position)?.itemView
                if (visible?.requestFocus() == true) return@post
                currentRecyclerView?.scrollToPosition(position)
                currentRecyclerView?.doOnPreDraw {
                    if (root.navigationOpen) return@doOnPreDraw
                    currentRecyclerView?.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
                        ?: adapter.viewIdForKey(targetKey)?.let { id -> contentHost.findViewById<View>(id)?.requestFocus() }
                }
            }
        }
    }

    private fun clearActiveSurface() {
        activeGuidedSetup?.close()
        activeGuidedSetup = null
        captureSurfacePosition()
        activeHomeAssistantSetup?.close()
        activeHomeAssistantSetup = null
        activeConnections = null
        activeHome?.close()
        activeHome = null
        activeSettings = null
        activeAppearance = null
        activeAbout = null
        activeWelcome = null
        activeBrowser = null
        activeMotionSearch?.close()
        activeMotionSearch = null
        activePtz?.close()
        activePtz = null
        activePlayback?.close()
        activePlayback = null
        activeCameraGroup?.close()
        activeCameraGroup = null
        activeMonitor?.close()
        activeMonitor = null
        currentListAdapter = null
        currentRecyclerView = null
        activeSurfaceKey = null
    }

    private fun captureSurfacePosition() {
        activeHome?.let { homeBookmark = it.captureBookmark() }
        activeSettings?.let { settingsPosition = it.capturePosition() }
        activeConnections?.let { connectionsPosition = it.capturePosition() }
        activeAppearance?.let { appearancePosition = it.capturePosition() }
        activeMotionSearch?.let { motionBookmark = it.captureBookmark() }
        activeSurfaceKey?.let { key ->
            activeBrowser?.let { browserBookmarks[key] = it.captureBookmark() }
            currentRecyclerView?.layoutManager?.onSaveInstanceState()?.let { listScrollMemory[key] = it }
        }
    }

    private fun setContentInsets(fullScreen: Boolean) {
        val params = contentHost.layoutParams as FrameLayout.LayoutParams
        params.marginStart = if (fullScreen) 0 else activity.dp(88)
        contentHost.layoutParams = params
        contentHost.setPadding(
            if (fullScreen) 0 else activity.dp(16),
            if (fullScreen) 0 else activity.dp(28),
            if (fullScreen) 0 else activity.dp(36),
            if (fullScreen) 0 else activity.dp(28),
        )
    }

    private fun buildNavigation() {
        NativeDestination.entries.forEach { destination ->
            val item = TextView(activity).apply {
                id = View.generateViewId()
                gravity = Gravity.CENTER
                textSize = 16f
                setTextColor(NativeTheme.palette.text)
                background = activity.nativeFlatFocusableBackground()
                isFocusable = true
                isClickable = true
                contentDescription = destination.label
                setCompoundDrawablesRelativeWithIntrinsicBounds(destination.iconRes, 0, 0, 0)
                compoundDrawablePadding = activity.dp(14)
                setPadding(activity.dp(11), activity.dp(8), activity.dp(11), activity.dp(8))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(50)).apply {
                    bottomMargin = activity.dp(6)
                }
                setOnClickListener {
                    root.closeNavigation(restoreContentFocus = true)
                    navigate(destination)
                }
            }
            navViews[destination] = item
            navigationItems.addView(item)
        }
        navViews.values.toList().forEachIndexed { index, view ->
            view.nextFocusUpId = navViews.values.elementAtOrNull(index - 1)?.id ?: view.id
            view.nextFocusDownId = navViews.values.elementAtOrNull(index + 1)?.id ?: view.id
            view.nextFocusRightId = view.id
        }
        val exit = TextView(activity).apply {
            id = View.generateViewId()
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(NativeTheme.palette.text)
            background = activity.nativeFlatFocusableBackground()
            isFocusable = true
            isClickable = true
            contentDescription = "Exit Opah"
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_chevron_left, 0, 0, 0)
            compoundDrawablePadding = activity.dp(14)
            setPadding(activity.dp(11), activity.dp(8), activity.dp(11), activity.dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(50)).apply {
                bottomMargin = activity.dp(6)
            }
            setOnClickListener { actions.onExitRequested() }
        }
        val lastNavigation = navViews.values.last()
        lastNavigation.nextFocusDownId = exit.id
        exit.nextFocusUpId = lastNavigation.id
        exit.nextFocusDownId = exit.id
        exit.nextFocusRightId = exit.id
        exit.tag = "native:exit"
        navigationItems.addView(exit)
        navigationRail.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        updateNavigationSelection()
        updateNavigationAppearance(false)
    }

    private fun styleRoot() {
        root.background = activity.nativeCanvasBackground()
        navigationRail.background = activity.nativeGlassPanelBackground()
        navigationAppearanceKey = null
        navViews.values.forEach { view ->
            view.setTextColor(NativeTheme.palette.text)
            view.background = activity.nativeFlatFocusableBackground()
        }
        navigationItems.findViewWithTag<TextView>("native:exit")?.let { exit ->
            exit.setTextColor(NativeTheme.palette.text)
            exit.background = activity.nativeFlatFocusableBackground()
        }
    }

    private fun navigate(destination: NativeDestination) {
        destinationLocations[route.destination] = currentLocation()
        applyLocation(destinationLocations[destination] ?: NativeLocation(NativeRoute(destination)))
        if (destination == NativeDestination.ACTIVITY) viewModel.loadReview()
        if (destination == NativeDestination.CLIPS) viewModel.loadExports()
        if (currentState.tvAlertPrivacyGateOpen) {
            viewModel.cancelTvAlertPrivacyGate()
            return
        }
        render(currentState)
    }

    private fun openSettingsPage(page: SettingsPage) {
        if (page == SettingsPage.CONNECTION) {
            openConnections(NativeConnectionPage.FRIGATE)
            return
        }
        route = NativeRoute(NativeDestination.SETTINGS, page)
        render(currentState)
    }

    private fun handleBack() {
        when {
            root.navigationOpen -> root.closeNavigation()
            currentState.tvAlertPrivacyGateOpen -> viewModel.cancelTvAlertPrivacyGate()
            currentState.playback != null -> if (activePlayback?.dismissPlaybackOsd() != true) viewModel.closePlayback()
            currentState.monitorMode != null -> if (activeMonitor?.dismissPlaybackOsd() != true) viewModel.closeMonitorMode()
            currentState.cameraGroupView != null -> if (activeCameraGroup?.dismissPlaybackOsd() != true) viewModel.closeCameraGroup()
            currentState.ptz.cameraName != null -> viewModel.closePtzControls()
            activeHomeAssistantSetup?.handleBack() == true -> Unit
            activeGuidedSetup?.handleBack() == true -> Unit
            else -> {
                val previous = navigationHistory.back()
                if (previous != null) {
                    applyLocation(previous)
                    render(currentState)
                } else if (route != NativeRoute()) {
                    route = NativeRoute()
                    navigationHistory.reset(currentLocation())
                    render(currentState)
                } else {
                    actions.onExitRequested()
                }
            }
        }
    }

    private fun updateNavigationSelection() {
        navViews.forEach { (destination, view) -> view.isSelected = destination == route.destination }
        root.setSelectedNavigationView(navViews[route.destination])
    }

    private fun updateNavigationAppearance(open: Boolean) {
        val activityCount = currentState.review.counts.unreviewedAlerts
        val activityChecking = currentState.review.countsLoading && !currentState.review.loadedOnce
        val camerasReady = currentState.activeProfile != null && currentState.snapshot != null
        val appearanceKey = "$open:$camerasReady:$activityCount:$activityChecking:${currentState.appUpdate.updateAvailable}:${NativeTheme.palette.hashCode()}"
        if (navigationAppearanceKey == appearanceKey) return
        navigationAppearanceKey = appearanceKey
        val countLabel = when {
            activityChecking -> "…"
            activityCount > 99 -> "99+"
            activityCount > 0 -> activityCount.toString()
            else -> ""
        }
        navViews.forEach { (destination, view) ->
            view.isEnabled = camerasReady || destination in setOf(NativeDestination.CONNECTIONS, NativeDestination.SETTINGS)
            view.alpha = if (view.isEnabled) 1f else 0.45f
            view.text = if (open) destination.label else ""
            view.gravity = if (open) Gravity.CENTER_VERTICAL or Gravity.START else Gravity.CENTER
            val icon = activity.nativeNavigationIcon(
                iconRes = destination.iconRes,
                badgeText = countLabel.takeIf { destination == NativeDestination.ACTIVITY }.orEmpty(),
                centerDot = destination == NativeDestination.SETTINGS && currentState.appUpdate.updateAvailable,
            )
            if (open) {
                view.foreground = null
                view.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
            } else {
                // Foreground positioning is independent of TextView font metrics, so
                // the icon sits in the exact center of its selection outline.
                view.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
                view.foregroundGravity = Gravity.CENTER
                view.foreground = icon
            }
            view.compoundDrawablePadding = activity.dp(if (open) 12 else 0)
            view.setPadding(
                activity.dp(if (open) 13 else 5),
                activity.dp(8),
                activity.dp(if (open) 13 else 5),
                activity.dp(8),
            )
            view.contentDescription = when {
                destination == NativeDestination.ACTIVITY && activityChecking -> "Activity, checking for new alerts"
                destination == NativeDestination.ACTIVITY && activityCount > 0 ->
                    "Activity, $activityCount new ${if (activityCount == 1) "alert" else "alerts"}"
                destination == NativeDestination.SETTINGS && currentState.appUpdate.updateAvailable ->
                    "Settings, update available"
                else -> destination.label
            }
        }
        navigationItems.findViewWithTag<TextView>("native:exit")?.let { exit ->
            exit.text = if (open) "Exit Opah" else ""
            exit.gravity = if (open) Gravity.CENTER_VERTICAL or Gravity.START else Gravity.CENTER
            if (open) {
                exit.foreground = null
                exit.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    activity.nativeNavigationIcon(R.drawable.ic_chevron_left),
                    null,
                    null,
                    null,
                )
            } else {
                exit.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
                exit.foregroundGravity = Gravity.CENTER
                exit.foreground = activity.nativeNavigationIcon(R.drawable.ic_chevron_left)
            }
            exit.compoundDrawablePadding = activity.dp(if (open) 12 else 0)
            exit.setPadding(
                activity.dp(if (open) 13 else 5),
                activity.dp(8),
                activity.dp(if (open) 13 else 5),
                activity.dp(8),
            )
        }
    }

    private fun setNavigationVisible(visible: Boolean) {
        navigationRail.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible && root.navigationOpen) root.closeNavigation(restoreContentFocus = false)
    }

    private fun reviewRow(
        prefix: String,
        item: ReviewItem,
        state: Phase0UiState,
        visibleItemIds: List<String>,
    ): NativeRowModel {
        val objectLabel = (item.objects + item.audio).distinct().take(3)
            .joinToString(", ") { it.replace('_', ' ').replaceFirstChar(Char::uppercase) }
            .ifBlank { "Camera activity" }
        val relative = DateUtils.getRelativeTimeSpanString(
            (item.startTime * 1_000.0).toLong(),
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
        return action(
            "$prefix:${item.id}",
            cameraDisplayName(item.camera, state),
            "$objectLabel • $relative${if (item.hasBeenReviewed) " • Reviewed" else ""}",
            if (item.recordingAvailable == false) "Details" else "Play",
            thumbnail = NativeThumbnailRequest(NativeThumbnailKind.REVIEW, item.id),
        ) {
            if (item.recordingAvailable == false) {
                viewModel.selectReviewItem(item)
            } else {
                viewModel.playReview(item, visibleActivityItemIds = visibleItemIds)
            }
        }
    }

    private fun cameraDisplayName(cameraName: String, state: Phase0UiState): String =
        state.snapshot?.authorizedCameraNames?.get(cameraName)
            ?: state.snapshot?.cameras?.firstOrNull { it.name == cameraName }?.displayName
            ?: cameraName.replace('_', ' ').replaceFirstChar(Char::uppercase)

    private fun settingsPageValue(page: SettingsPage, state: Phase0UiState): String = when (page) {
        SettingsPage.UPDATE -> if (state.appUpdate.updateAvailable) "Update ready" else displayedVersionName()
        SettingsPage.APPEARANCE -> state.settings.appearanceMode.name.humanize()
        SettingsPage.PLAYBACK -> state.settings.streamPreference.name.humanize()
        SettingsPage.TV_ALERTS -> if (state.tvAlerts.enabled) "On" else "Off"
        SettingsPage.PRIVACY -> if (state.privacy.pinConfigured) "PIN set" else "No PIN"
        SettingsPage.STARTUP -> startupTargetLabel(state.settings.startupTarget, state.settings)
        SettingsPage.CONNECTION -> state.activeProfile?.username.orEmpty()
        SettingsPage.SYSTEM -> state.snapshot?.frigateVersion.orEmpty()
        SettingsPage.ADVANCED -> state.device?.model.orEmpty()
        SettingsPage.ABOUT -> displayedVersionName()
        SettingsPage.MAIN,
        SettingsPage.PLAYBACK_TEST,
        SettingsPage.CAMERA_DIAGNOSTICS,
        -> ""
    }

    private fun displayedVersionName(): String =
        if (BuildConfig.DOCUMENTATION_MODE) BuildConfig.VERSION_NAME.removeSuffix("-documentation")
        else BuildConfig.VERSION_NAME

    private fun ensurePlaybackCompatibilityChoicesLoaded(state: Phase0UiState) {
        val profile = state.activeProfile ?: return
        val device = state.device ?: return
        val loadKey = "${profile.apiBaseUrl}|${profile.username}|${device.manufacturer}|${device.model}|${device.device}"
        if (playbackCompatibilityLoadKey == loadKey) return
        playbackCompatibilityLoadKey = loadKey
        viewModel.loadCameraPlaybackCompatibilityChoices()
    }

    private fun startupChoice(key: String, title: String, target: StartupTarget, state: Phase0UiState): NativeRowModel {
        val selected = state.settings.startupTarget == target
        return choice(key, title, selected = selected) {
            viewModel.updateStartupTarget(target)
        }
    }

    private fun showCreatePinFlow() {
        showPinDialog("Create a PIN") { first ->
            showPinDialog("Enter the PIN again") { confirmation ->
                viewModel.setupLocalPin(first, confirmation, emptySet())
            }
        }
    }

    private fun showPinDialog(
        title: String,
        onCancelled: () -> Unit = {},
        onComplete: (CharArray) -> Unit,
    ) {
        val pinContainer = (activity.application as app.opah.tv.OpahApplication).container
        var protecting = false
        val dialog = createDialog(onDismiss = {
            if (protecting) { protecting = false; pinContainer.onProtectedPinPromptStopped() }
        })
        val entered = StringBuilder()
        val value = TextView(activity).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(NativeTheme.palette.text)
            setPadding(0, activity.dp(6), 0, activity.dp(8))
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(20), activity.dp(16), activity.dp(20), activity.dp(16))
            background = activity.nativeGlassPanelBackground(strong = true)
            addView(TextView(activity).apply {
                text = title
                textSize = 21f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(NativeTheme.palette.text)
                gravity = Gravity.CENTER
            })
            addView(TextView(activity).apply {
                setText(R.string.native_pin_length_help)
                textSize = 13f
                setTextColor(NativeTheme.palette.secondaryText)
                gravity = Gravity.CENTER
                setPadding(0, activity.dp(2), 0, 0)
            })
            addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val grid = android.widget.GridLayout(activity).apply {
            columnCount = 3
            alignmentMode = android.widget.GridLayout.ALIGN_BOUNDS
        }
        var deleteButton: TextView? = null
        var doneButton: TextView? = null
        fun updateValue() {
            value.text = buildString {
                repeat(entered.length) { append("● ") }
                repeat((4 - entered.length).coerceAtLeast(0)) { append("○ ") }
            }.trim()
            deleteButton?.let { button ->
                button.isEnabled = entered.isNotEmpty()
                button.alpha = if (button.isEnabled) 1f else 0.42f
            }
            doneButton?.let { button ->
                button.isEnabled = entered.length in 4..8
                button.alpha = if (button.isEnabled) 1f else 0.42f
            }
        }
        (listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "Delete", "0", "Done")).forEach { label ->
            val button = dialogButton(label) {
                when (label) {
                    "Delete" -> if (entered.isNotEmpty()) entered.deleteCharAt(entered.lastIndex)
                    "Done" -> if (entered.length in 4..8) {
                        val result = entered.toString().toCharArray()
                        entered.clear()
                        dialog.dismiss()
                        onComplete(result)
                    }
                    else -> if (entered.length < 8) entered.append(label)
                }
                updateValue()
            }
            if (label == "Delete") deleteButton = button
            if (label == "Done") doneButton = button
            grid.addView(button, android.widget.GridLayout.LayoutParams().apply {
                width = activity.dp(86)
                height = activity.dp(44)
                setMargins(activity.dp(2), activity.dp(2), activity.dp(2), activity.dp(2))
            })
        }
        updateValue()
        column.addView(grid)
        dialog.setContentView(column)
        dialog.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            grid.getChildAt(0)?.requestFocus()
        }
        dialog.setOnCancelListener { onCancelled() }
        pinContainer.onProtectedPinPromptStarted()
        protecting = true
        dialog.show()
    }

    private fun showCompatibilityModeDialog(camera: Camera) {
        val options = listOf(
            PlaybackCompatibilityTestMode.AUTOMATIC to ("Automatic" to "Recommended • tries safe choices and saves the first verified result"),
            PlaybackCompatibilityTestMode.STANDARD_CONNECTION to ("Standard connection" to "UDP transport • video and sound • automatic decoder"),
            PlaybackCompatibilityTestMode.RELIABLE_CONNECTION to ("Reliable connection" to "TCP transport • video and sound • automatic decoder"),
            PlaybackCompatibilityTestMode.VIDEO_ONLY to ("Video only" to "No audio pipeline • current transport preference"),
            PlaybackCompatibilityTestMode.RELIABLE_VIDEO_ONLY to ("Reliable video only" to "TCP transport • no audio pipeline"),
        )
        showChoiceDialog(
            title = "Check ${camera.displayName}",
            explanation = "The player closes automatically after Opah verifies a choice. The saved choice is used whenever this camera opens",
            choices = options.map { (mode, pair) -> Triple(mode.name, pair.first, pair.second) },
        ) { value -> viewModel.testCameraPlayback(camera.name, PlaybackCompatibilityTestMode.valueOf(value)) }
    }

    private fun showScheduleDialog(start: Int, end: Int) {
        showTimeDialog("Alert start time", start) { selectedStart ->
            showTimeDialog("Alert end time", end) { selectedEnd ->
                if (selectedStart == selectedEnd) {
                    showChoiceDialog(
                        "Choose different times",
                        "The start and end time cannot be the same",
                        listOf(Triple("again", "Choose again", "Return to the time picker")),
                    ) { showScheduleDialog(selectedStart, selectedEnd) }
                } else {
                    viewModel.setTvAlertScheduleWindow(selectedStart, selectedEnd)
                }
            }
        }
    }

    private fun showTimeDialog(title: String, current: Int, onComplete: (Int) -> Unit) {
        val normalized = current.coerceIn(0, 1439)
        var hour = when (val hour24 = normalized / 60) {
            0, 12 -> 12
            else -> hour24 % 12
        }
        var minute = normalized % 60
        var afternoon = normalized / 60 >= 12
        val dialog = createDialog()
        val selectedTime = TextView(activity).apply {
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(NativeTheme.palette.text)
            setPadding(0, activity.dp(8), 0, activity.dp(10))
        }
        val adjustmentRows = mutableListOf<TextView>()
        fun refresh() {
            selectedTime.text = activity.getString(
                R.string.native_time_picker_selected_time,
                hour,
                minute,
                if (afternoon) "PM" else "AM",
            )
            adjustmentRows.getOrNull(0)?.text = activity.getString(R.string.native_time_picker_hour, hour)
            adjustmentRows.getOrNull(1)?.text = activity.getString(R.string.native_time_picker_minute, minute)
            adjustmentRows.getOrNull(2)?.text = activity.getString(
                R.string.native_time_picker_time_of_day,
                if (afternoon) "PM" else "AM",
            )
        }
        fun adjustmentRow(
            accessibilityLabel: String,
            decrease: () -> Unit,
            increase: () -> Unit,
        ): TextView = TextView(activity).apply {
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(NativeTheme.palette.text)
            background = activity.nativeFlatFocusableBackground()
            isFocusable = true
            isClickable = true
            setPadding(activity.dp(12), activity.dp(6), activity.dp(12), activity.dp(6))
            contentDescription = "$accessibilityLabel. Press left to decrease, right or Select to increase"
            setOnClickListener { increase(); refresh() }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        decrease()
                        refresh()
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        increase()
                        refresh()
                        true
                    }
                    else -> false
                }
            }
        }
        adjustmentRows += adjustmentRow(
            accessibilityLabel = "Hour",
            decrease = { hour = if (hour == 1) 12 else hour - 1 },
            increase = { hour = if (hour == 12) 1 else hour + 1 },
        )
        adjustmentRows += adjustmentRow(
            accessibilityLabel = "Minute",
            decrease = { minute = if (minute == 0) 59 else minute - 1 },
            increase = { minute = if (minute == 59) 0 else minute + 1 },
        )
        adjustmentRows += adjustmentRow(
            accessibilityLabel = "AM or PM",
            decrease = { afternoon = !afternoon },
            increase = { afternoon = !afternoon },
        )
        refresh()
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(22), activity.dp(18), activity.dp(22), activity.dp(18))
            background = activity.nativeGlassPanelBackground(strong = true)
            addView(TextView(activity).apply {
                text = title
                textSize = 22f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(NativeTheme.palette.text)
            })
            addView(TextView(activity).apply {
                setText(R.string.native_time_picker_help)
                textSize = 14f
                setTextColor(NativeTheme.palette.secondaryText)
                setPadding(0, activity.dp(4), 0, 0)
            })
            addView(selectedTime, LinearLayout.LayoutParams(activity.dp(520), ViewGroup.LayoutParams.WRAP_CONTENT))
            adjustmentRows.forEach { row ->
                addView(
                    row,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(44)).apply {
                        topMargin = activity.dp(1)
                    },
                )
            }
            addView(
                LinearLayout(activity).apply {
                    gravity = Gravity.END
                    addView(dialogButton("Cancel") { dialog.dismiss() })
                    addView(dialogButton("Save time") {
                        val hour24 = when {
                            afternoon && hour != 12 -> hour + 12
                            !afternoon && hour == 12 -> 0
                            else -> hour
                        }
                        dialog.dismiss()
                        onComplete(hour24 * 60 + minute)
                    })
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(58)).apply {
                    topMargin = activity.dp(10)
                },
            )
        }
        dialog.setContentView(column)
        dialog.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        dialog.setOnShowListener {
            dialog.window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            adjustmentRows.first().requestFocus()
        }
        dialog.show()
    }

    private fun showChoiceDialog(
        title: String,
        explanation: String,
        choices: List<Triple<String, String, String>>,
        onComplete: (String) -> Unit,
    ) {
        val dialog = createDialog()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(22), activity.dp(18), activity.dp(22), activity.dp(18))
            background = activity.nativeGlassPanelBackground(strong = true)
            addView(TextView(activity).apply {
                text = title
                textSize = 22f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(NativeTheme.palette.text)
            })
            addView(TextView(activity).apply {
                text = explanation
                textSize = 14f
                setTextColor(NativeTheme.palette.secondaryText)
                setPadding(0, activity.dp(4), 0, activity.dp(10))
            })
        }
        val recycler = RecyclerView(activity).apply {
            layoutManager = NativeLinearLayoutManager(activity)
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val choiceHandlers = choices.associate { choice -> "choice:${choice.first}" to {
            dialog.dismiss()
            onComplete(choice.first)
        } }
        val adapter = NativeListAdapter(
            onActivate = { key -> choiceHandlers[key]?.invoke() },
            onFocused = { _, _ -> Unit },
        )
        recycler.addNativeFlatDividers()
        recycler.adapter = adapter
        adapter.submitList(choices.map { choice -> actionModel("choice:${choice.first}", choice.second, choice.third) }) {
            recycler.post { recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
        }
        container.addView(
            recycler,
            LinearLayout.LayoutParams(
                activity.dp(650),
                activity.dp(nativeDialogListHeightDp(choices.size)),
            ),
        )
        dialog.setContentView(container)
        dialog.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun showReadingDialog(title: String, description: String, value: String) {
        val dialog = createDialog()
        val body = buildList {
            value.takeIf(String::isNotBlank)?.let(::add)
            description.takeIf(String::isNotBlank)?.let(::add)
        }.joinToString("\n\n").ifBlank { title }
        val scroll = ScrollView(activity).apply {
            isFocusable = true
            isClickable = true
            isFillViewport = true
            background = activity.nativeFlatReadingBackground()
            setPadding(activity.dp(14), activity.dp(12), activity.dp(14), activity.dp(12))
            contentDescription = "$title. $body. Use up and down to read"
            addView(
                TextView(activity).apply {
                    text = body
                    textSize = 16f
                    setTextColor(NativeTheme.palette.text)
                    setLineSpacing(activity.dp(3).toFloat(), 1f)
                },
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            setOnKeyListener { _, keyCode, event ->
                if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val step = activity.dp(210)
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> if (canScrollVertically(1)) {
                        smoothScrollBy(0, step)
                        true
                    } else {
                        false
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> if (canScrollVertically(-1)) {
                        smoothScrollBy(0, -step)
                        true
                    } else {
                        false
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER,
                    -> {
                        if (canScrollVertically(1)) smoothScrollBy(0, step) else smoothScrollTo(0, 0)
                        true
                    }
                    else -> false
                }
            }
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(22), activity.dp(18), activity.dp(22), activity.dp(18))
            background = activity.nativeGlassPanelBackground(strong = true)
            addView(TextView(activity).apply {
                text = title
                textSize = 22f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(NativeTheme.palette.text)
            })
            addView(TextView(activity).apply {
                setText(R.string.native_reading_help)
                textSize = 13f
                setTextColor(NativeTheme.palette.secondaryText)
                setPadding(0, activity.dp(4), 0, activity.dp(10))
            })
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    activity.dp(720),
                    activity.dp(nativeReadingDialogHeightDp(body.length)),
                ),
            )
            addView(
                LinearLayout(activity).apply {
                    gravity = Gravity.END
                    addView(dialogButton("Close") { dialog.dismiss() })
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = activity.dp(12) },
            )
        }
        dialog.setContentView(container)
        dialog.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        dialog.setOnShowListener {
            dialog.window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            scroll.requestFocus()
        }
        dialog.show()
    }

    private fun showTextEntryDialog(
        title: String,
        explanation: String,
        initial: String,
        multiline: Boolean = false,
        onComplete: (String) -> Unit,
    ) {
        val dialog = createDialog()
        val input = EditText(activity).apply {
            setText(initial)
            setSelection(text.length)
            hint = title
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
            setTextColor(NativeTheme.palette.text)
            setHintTextColor(NativeTheme.palette.secondaryText)
            setSingleLine(!multiline)
            maxLines = if (multiline) 3 else 1
            gravity = if (multiline) Gravity.TOP or Gravity.START else Gravity.CENTER_VERTICAL
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            background = activity.nativeFlatFieldBackground()
            setPadding(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(8))
        }
        val complete = {
            val value = input.text.toString().trim()
            dialog.dismiss()
            onComplete(value)
        }
        input.setOnEditorActionListener { _, actionId, event ->
            val submitted = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                    event.action == android.view.KeyEvent.ACTION_UP)
            if (submitted) complete()
            submitted
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(22), activity.dp(18), activity.dp(22), activity.dp(18))
            background = activity.nativeGlassPanelBackground(strong = true)
            addView(TextView(activity).apply {
                text = title
                textSize = 22f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(NativeTheme.palette.text)
            })
            addView(TextView(activity).apply {
                text = explanation
                textSize = 14f
                setTextColor(NativeTheme.palette.secondaryText)
                setPadding(0, activity.dp(4), 0, activity.dp(12))
            })
            addView(
                input,
                LinearLayout.LayoutParams(
                    activity.dp(650),
                    activity.dp(if (multiline) 112 else 50),
                ),
            )
            addView(
                LinearLayout(activity).apply {
                    gravity = Gravity.END
                    addView(dialogButton("Cancel") { dialog.dismiss() })
                    addView(dialogButton("Continue") {
                        complete()
                    })
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = activity.dp(14) },
            )
        }
        dialog.setContentView(container)
        dialog.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        prepareDialogKeyboard(dialog, input)
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        requestDialogKeyboard(dialog, input)
    }

    private fun showConnectionDialog(
        initialUrl: String,
        initialUsername: String,
        initialRtspHost: String,
        initialRtspPort: String,
    ) {
        var submission: ConnectionFormSubmission? = null
        var clearPassword: (() -> Unit)? = null
        val dialog = createDialog {
            submission?.close()
            clearPassword?.invoke()
        }
        val initialAccountUsername = initialUsername.takeUnless {
            ConnectionProfileFactory.targetsUnauthenticatedFrigatePort(initialUrl)
        }.orEmpty()
        val fields = listOf(
            dialogTextField(
                "https://frigate.example:8971",
                initialUrl,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            ),
            dialogTextField("Optional", initialAccountUsername),
            dialogTextField(
                "Optional",
                "",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            ),
            dialogTextField(
                "video.example.local",
                initialRtspHost,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            ),
            dialogTextField("8554", initialRtspPort, InputType.TYPE_CLASS_NUMBER),
        )
        clearPassword = { fields[2].text?.clear() }
        fields.forEachIndexed { index, field -> field.tag = "connection:field:$index" }
        fields.forEachIndexed { index, field ->
            field.imeOptions = if (index == 0 || index == 1 || index == 3) {
                android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
            } else {
                android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            }
            field.setOnEditorActionListener { _, actionId, _ ->
                when {
                    actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT &&
                        (index == 0 || index == 1 || index == 3) -> {
                        requestDialogKeyboard(dialog, fields[index + 1])
                        true
                    }
                    else -> false
                }
            }
            prepareDialogKeyboard(dialog, field)
        }
        val unauthenticatedPortNotice = TextView(activity).apply {
            setText(R.string.native_unauthenticated_port_warning)
            textSize = 12.5f
            setTextColor(NativeTheme.palette.secondaryText)
            setPadding(activity.dp(2), activity.dp(3), 0, activity.dp(5))
            visibility = View.GONE
        }
        fun updateCredentialFields() {
            val unauthenticated = ConnectionProfileFactory.targetsUnauthenticatedFrigatePort(
                fields[0].text.toString(),
            )
            fields[0].imeOptions = if (unauthenticated) {
                android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            } else {
                android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
            }
            fields[1].isEnabled = !unauthenticated
            fields[1].isFocusable = !unauthenticated
            fields[1].alpha = if (unauthenticated) 0.5f else 1f
            fields[2].isEnabled = !unauthenticated
            fields[2].isFocusable = !unauthenticated
            fields[2].alpha = if (unauthenticated) 0.5f else 1f
            unauthenticatedPortNotice.visibility = if (unauthenticated) View.VISIBLE else View.GONE
        }
        fields[0].addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(text: Editable?) = updateCredentialFields()
        })
        var differentLiveVideoAddress = initialRtspHost.isNotBlank() ||
            initialRtspPort.trim().let { it.isNotEmpty() && it != "8554" }
        val liveVideoFields = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(activity).apply {
                    setText(R.string.native_separate_live_video_help)
                    textSize = 12.5f
                    setTextColor(NativeTheme.palette.secondaryText)
                    setPadding(activity.dp(2), activity.dp(4), 0, activity.dp(2))
                },
            )
            addView(
                labeledDialogField(
                    "Live video host",
                    fields[3],
                    "Enter a host name or IP address, not a full web address",
                ),
            )
            addView(labeledDialogField("Live video port", fields[4]))
        }
        lateinit var liveVideoChoice: TextView
        fun updateLiveVideoAddressChoice(openKeyboard: Boolean = false) {
            liveVideoChoice.text = if (differentLiveVideoAddress) {
                "Live video address  •  Different address"
            } else {
                "Live video address  •  Same as Frigate (recommended)"
            }
            liveVideoFields.visibility = if (differentLiveVideoAddress) View.VISIBLE else View.GONE
            liveVideoChoice.contentDescription = if (differentLiveVideoAddress) {
                "Live video uses a different address, select to use the Frigate address"
            } else {
                "Live video uses the Frigate address, select to enter a different address"
            }
            if (differentLiveVideoAddress && openKeyboard) requestDialogKeyboard(dialog, fields[3])
        }
        liveVideoChoice = dialogButton("") {
            differentLiveVideoAddress = !differentLiveVideoAddress
            if (!differentLiveVideoAddress) {
                fields[3].setText("")
                fields[4].setText(R.string.native_default_rtsp_port)
            }
            updateLiveVideoAddressChoice(openKeyboard = differentLiveVideoAddress)
        }.apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(44)).apply {
                topMargin = activity.dp(8)
            }
        }
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                labeledDialogField(
                    "Frigate address",
                    fields[0],
                    "Use the address you would open to view Frigate in a browser",
                ),
            )
            addView(unauthenticatedPortNotice)
            addView(labeledDialogField("Username • not used on port 5000", fields[1]))
            addView(labeledDialogField("Password • not used on port 5000", fields[2]))
            addView(liveVideoChoice)
            addView(liveVideoFields)
        }
        updateCredentialFields()
        updateLiveVideoAddressChoice()
        val formScroll = ScrollView(activity).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(form, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val feedback = TextView(activity).apply {
            tag = "connection:feedback"
            textSize = 14f
            setTextColor(NativeTheme.palette.text)
            setPadding(0, activity.dp(4), 0, activity.dp(4))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        val cancel = dialogButton("Cancel") { dialog.dismiss() }
        lateinit var test: TextView
        lateinit var connect: TextView
        fun submit(testOnly: Boolean) {
            submission?.submit(testOnly) { result ->
                val arguments = fields.map { it.text.toString() }
                if (testOnly) {
                    viewModel.testConnection(arguments[0], arguments[1], arguments[2], arguments[3], arguments[4], result)
                } else {
                    viewModel.connect(arguments[0], arguments[1], arguments[2], arguments[3], arguments[4], result)
                }
            }
        }
        test = dialogButton("Test connection") { submit(testOnly = true) }.apply { tag = "connection:test" }
        connect = dialogButton("Connect") { submit(testOnly = false) }.apply { tag = "connection:connect" }
        var lastAction: View? = null
        submission = ConnectionFormSubmission(
            onStatus = { busy, message ->
                if (busy) lastAction = dialog.currentFocus
                fields.forEach { field -> field.isEnabled = !busy }
                listOf(liveVideoChoice, test, connect).forEach { control ->
                    control.isEnabled = !busy
                    control.isFocusable = !busy
                    control.alpha = if (busy) 0.5f else 1f
                }
                if (!busy) updateCredentialFields()
                feedback.text = message
                feedback.visibility = View.VISIBLE
                if (busy) cancel.requestFocus() else lastAction?.requestFocus()
            },
            onConnected = { dialog.dismiss() },
        )
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(22), activity.dp(18), activity.dp(22), activity.dp(18))
            background = activity.nativeGlassPanelBackground(strong = true)
            addView(TextView(activity).apply {
                setText(R.string.native_frigate_connection)
                textSize = 22f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(NativeTheme.palette.text)
            })
            addView(TextView(activity).apply {
                setText(R.string.native_connection_privacy_help)
                textSize = 13f
                setTextColor(NativeTheme.palette.secondaryText)
                setPadding(0, activity.dp(3), 0, activity.dp(6))
            })
            addView(feedback, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(formScroll, LinearLayout.LayoutParams(activity.dp(640), 0, 1f))
            addView(
                LinearLayout(activity).apply {
                    gravity = Gravity.END
                    addView(cancel)
                    addView(test)
                    addView(connect)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(58)).apply { topMargin = activity.dp(12) },
            )
        }
        dialog.setContentView(column)
        dialog.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            minOf(activity.dp(480), activity.resources.displayMetrics.heightPixels - activity.dp(48)),
        )
        requestDialogKeyboard(dialog, fields.first())
    }

    private fun showRtspRouteDialog(initialHost: String, initialPort: String) {
        val dialog = createDialog()
        val host = dialogTextField(
            "Optional",
            initialHost,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        )
        val port = dialogTextField("8554", initialPort, InputType.TYPE_CLASS_NUMBER)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(22), activity.dp(18), activity.dp(22), activity.dp(18))
            background = activity.nativeGlassPanelBackground(strong = true)
            addView(TextView(activity).apply {
                setText(R.string.native_live_video_route)
                textSize = 22f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(NativeTheme.palette.text)
            })
            addView(TextView(activity).apply {
                setText(R.string.native_live_video_route_help)
                textSize = 14f
                setTextColor(NativeTheme.palette.secondaryText)
                setPadding(0, activity.dp(4), 0, activity.dp(10))
            })
            addView(
                labeledDialogField(
                    "Live video host",
                    host,
                    "Enter a host name or IP address, not a full web address",
                ),
                LinearLayout.LayoutParams(activity.dp(620), ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            addView(
                labeledDialogField("Live video port", port),
                LinearLayout.LayoutParams(activity.dp(620), ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            addView(
                LinearLayout(activity).apply {
                    gravity = Gravity.END
                    addView(dialogButton("Cancel") { dialog.dismiss() })
                    addView(dialogButton("Save address") {
                        dialog.dismiss()
                        viewModel.updateRtspRoute(host.text.toString(), port.text.toString())
                    })
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(58)).apply { topMargin = activity.dp(12) },
            )
        }
        dialog.setContentView(column)
        dialog.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        prepareDialogKeyboard(dialog, host)
        prepareDialogKeyboard(dialog, port)
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        requestDialogKeyboard(dialog, host)
    }

    private fun dialogTextField(
        hint: String,
        initial: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
    ): EditText = EditText(activity).apply {
        this.hint = hint
        setText(initial)
        setSingleLine(true)
        this.inputType = inputType
        setTextColor(NativeTheme.palette.text)
        setHintTextColor(NativeTheme.palette.secondaryText)
        background = activity.nativeFlatFieldBackground()
        setPadding(activity.dp(12), activity.dp(7), activity.dp(12), activity.dp(7))
    }

    private fun labeledDialogField(
        label: String,
        field: EditText,
        help: String? = null,
    ): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(activity).apply {
            text = label
            textSize = 12.5f
            setTextColor(NativeTheme.palette.secondaryText)
            setPadding(activity.dp(2), activity.dp(6), 0, activity.dp(3))
        })
        help?.let { message ->
            addView(TextView(activity).apply {
                text = message
                textSize = 11.5f
                setTextColor(NativeTheme.palette.secondaryText)
                setPadding(activity.dp(2), 0, 0, activity.dp(3))
            })
        }
        addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(46)))
    }

    private fun prepareDialogKeyboard(dialog: Dialog, field: EditText) {
        field.setOnClickListener { requestDialogKeyboard(dialog, field) }
        field.showSoftInputOnFocus = true
    }

    private fun requestDialogKeyboard(dialog: Dialog, field: EditText) {
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
        )
        field.requestFocus()
        field.setSelection(field.text.length)
        field.post {
            if (!dialog.isShowing) return@post
            field.requestFocus()
            val keyboard = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            keyboard.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            field.postDelayed({
                if (dialog.isShowing && field.hasFocus()) {
                    keyboard.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
                }
            }, 180L)
        }
    }

    private fun dialogButton(label: String, action: () -> Unit): TextView = TextView(activity).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(NativeTheme.palette.text)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        background = activity.nativeFlatFocusableBackground()
        isFocusable = true
        isClickable = true
        setPadding(activity.dp(12), activity.dp(5), activity.dp(12), activity.dp(5))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(40)).apply {
            marginStart = activity.dp(4)
        }
        setOnClickListener { action() }
    }

    private fun loadThumbnail(row: NativeRowModel, imageView: ImageView) {
        val request = row.thumbnail ?: return
        val cacheKey = request.cacheKey
        imageView.tag = cacheKey
        cachedThumbnail(request)?.let { bitmap ->
            imageView.setImageBitmap(bitmap)
            return
        }
        thumbnailTargets.getOrPut(cacheKey, ::mutableListOf).add(WeakReference(imageView))
        if (thumbnailJobs[cacheKey]?.isActive == true) return
        thumbnailJobs[cacheKey] = activity.lifecycleScope.launch {
            val bitmap = refreshThumbnail(request)
            val targets = thumbnailTargets.remove(cacheKey).orEmpty()
            if (bitmap != null) {
                targets.forEach { reference ->
                    reference.get()?.takeIf { it.tag == cacheKey }?.setImageBitmap(bitmap)
                }
            }
            thumbnailJobs.remove(cacheKey)
        }
    }

    private fun cachedThumbnail(request: NativeThumbnailRequest): Bitmap? = when (request.kind) {
        NativeThumbnailKind.CAMERA -> viewModel.cachedCameraImage(request.sourceId)?.bitmap
        NativeThumbnailKind.REVIEW -> playbackItems(currentState)
            .firstOrNull { it.id == request.sourceId }
            ?.let(viewModel::cachedReviewImage)
            ?.bitmap
        NativeThumbnailKind.CLIP -> currentState.exports.items
            .firstOrNull { it.id == request.sourceId }
            ?.let(viewModel::cachedExportImage)
            ?.bitmap
        NativeThumbnailKind.SEARCH -> currentState.activitySearch.results
            .firstOrNull { it.id == request.sourceId }
            ?.let(viewModel::cachedSearchImage)
            ?.bitmap
    }

    private suspend fun refreshThumbnail(request: NativeThumbnailRequest): Bitmap? = when (request.kind) {
        NativeThumbnailKind.CAMERA -> viewModel.refreshCameraImage(request.sourceId, THUMBNAIL_HEIGHT_PX)
            .getOrNull()
            ?.bitmap
        NativeThumbnailKind.REVIEW -> playbackItems(currentState)
            .firstOrNull { it.id == request.sourceId }
            ?.let { viewModel.refreshReviewImage(it, THUMBNAIL_HEIGHT_PX).getOrNull()?.bitmap }
        NativeThumbnailKind.CLIP -> currentState.exports.items
            .firstOrNull { it.id == request.sourceId }
            ?.let { viewModel.refreshExportImage(it, THUMBNAIL_HEIGHT_PX).getOrNull()?.bitmap }
        NativeThumbnailKind.SEARCH -> currentState.activitySearch.results
            .firstOrNull { it.id == request.sourceId }
            ?.let { viewModel.refreshSearchImage(it, THUMBNAIL_HEIGHT_PX).getOrNull()?.bitmap }
    }

    private fun action(
        key: String,
        title: String,
        description: String = "",
        value: String = "",
        enabled: Boolean = true,
        selected: Boolean = false,
        thumbnail: NativeThumbnailRequest? = null,
        action: () -> Unit,
    ): NativeRowModel {
        val effectiveEnabled = interactionEnabled(key, enabled)
        adjustmentHandlers.remove(key)
        handlers[key] = action
        return actionModel(key, title, description, value, effectiveEnabled, selected, thumbnail)
    }

    private fun actionModel(
        key: String,
        title: String,
        description: String = "",
        value: String = "",
        enabled: Boolean = true,
        selected: Boolean = false,
        thumbnail: NativeThumbnailRequest? = null,
    ) = NativeRowModel(
        key,
        title,
        description,
        value,
        NativeRowKind.ACTION,
        enabled,
        selected,
        thumbnail,
    )

    private fun toggle(
        key: String,
        title: String,
        checked: Boolean,
        description: String = "",
        enabled: Boolean = true,
        action: () -> Unit,
    ): NativeRowModel {
        val effectiveEnabled = interactionEnabled(key, enabled)
        adjustmentHandlers.remove(key)
        handlers[key] = action
        return NativeRowModel(
            key,
            title,
            description,
            if (checked) "On" else "Off",
            NativeRowKind.TOGGLE,
            effectiveEnabled,
            checked,
        )
    }

    private fun choice(
        key: String,
        title: String,
        selected: Boolean,
        description: String = "",
        value: String = "",
        enabled: Boolean = true,
        action: () -> Unit,
    ): NativeRowModel {
        val effectiveEnabled = interactionEnabled(key, enabled)
        adjustmentHandlers.remove(key)
        handlers[key] = action
        return NativeRowModel(
            key = key,
            title = title,
            description = description,
            value = value,
            kind = NativeRowKind.CHOICE,
            enabled = effectiveEnabled,
            selected = selected,
        )
    }

    private fun cycleRow(key: String, title: String, value: String, action: () -> Unit): NativeRowModel =
        action(key, title, "Press to choose the next option", value.humanize(), action = action)

    private fun adjustment(
        key: String,
        title: String,
        value: String,
        description: String = "Use left and right to change",
        indicatorColor: Int? = null,
        adjustmentRange: NativeAdjustmentRange? = null,
        decrease: () -> Unit,
        increase: () -> Unit,
    ): NativeRowModel {
        handlers[key] = increase
        adjustmentHandlers[key] = { direction -> if (direction < 0) decrease() else increase() }
        return NativeRowModel(
            key = key,
            title = title,
            description = description,
            value = value,
            kind = NativeRowKind.ADJUSTMENT,
            adjustmentRange = adjustmentRange,
            enabled = interactionEnabled(key, true),
            indicatorColor = indicatorColor,
        )
    }

    private fun interactionEnabled(key: String, requested: Boolean): Boolean = requested && when {
        key.startsWith("alerts:") -> !currentState.tvAlerts.busy
        key.startsWith("privacy:") -> !currentState.privacy.busy
        else -> true
    }

    private fun info(
        key: String,
        title: String,
        description: String = "",
        value: String = "",
        indicatorColor: Int? = null,
    ): NativeRowModel {
        handlers.remove(key)
        adjustmentHandlers.remove(key)
        return NativeRowModel(
            key = key,
            title = title,
            description = description,
            value = value,
            kind = NativeRowKind.INFORMATION,
            enabled = true,
            indicatorColor = indicatorColor,
        )
    }

    private fun visual(
        key: String,
        title: String,
        description: String,
        value: String,
        segments: List<NativeMeterSegment>,
    ): NativeRowModel {
        handlers.remove(key)
        adjustmentHandlers.remove(key)
        return NativeRowModel(
            key = key,
            title = title,
            description = description,
            value = value,
            kind = NativeRowKind.VISUAL,
            enabled = true,
            meterSegments = segments,
        )
    }

    private fun dashboard(key: String, vararg metrics: NativeDashboardMetric): NativeRowModel {
        handlers.remove(key)
        adjustmentHandlers.remove(key)
        return NativeRowModel(
            key = key,
            title = "Dashboard",
            kind = NativeRowKind.DASHBOARD,
            enabled = false,
            dashboardMetrics = metrics.toList(),
        )
    }

    private fun reading(key: String, title: String, description: String = "", value: String = ""): NativeRowModel {
        handlers[key] = { showReadingDialog(title, description, value) }
        return NativeRowModel(key, title, description, value, NativeRowKind.READING, enabled = true)
    }

    private fun section(key: String, title: String, description: String = "") =
        NativeRowModel(key, title, description, kind = NativeRowKind.SECTION, enabled = false)

    private fun initialRoute(destinationName: String?, settingsPageName: String?): NativeRoute {
        val destination = when (destinationName) {
            "REVIEW", "ACTIVITY" -> NativeDestination.ACTIVITY
            "SAVED", "CLIPS" -> NativeDestination.CLIPS
            "CONNECTIONS" -> NativeDestination.CONNECTIONS
            "SETTINGS", "INFORMATION", "ABOUT" -> NativeDestination.SETTINGS
            else -> NativeDestination.HOME
        }
        val settingsPage = if (destination == NativeDestination.SETTINGS) {
            when (destinationName) {
                "INFORMATION" -> SettingsPage.SYSTEM
                "ABOUT" -> SettingsPage.ABOUT
                else -> settingsPageName?.let { name ->
                    runCatching { SettingsPage.valueOf(name) }.getOrNull()
                } ?: SettingsPage.MAIN
            }
        } else {
            SettingsPage.MAIN
        }
        return NativeRoute(destination, settingsPage)
    }

    private fun orderedCameras(cameras: List<Camera>, favorites: List<String>): List<Camera> {
        val favoriteOrder = favorites.withIndex().associate { it.value to it.index }
        return cameras.sortedWith(compareBy<Camera>({ favoriteOrder[it.name] ?: Int.MAX_VALUE }, { it.order }))
    }

    private fun playbackItems(state: Phase0UiState): List<ReviewItem> = (
        listOfNotNull(state.review.playbackItem) +
            state.review.items +
            state.snapshot?.recentReviewItems.orEmpty() +
            state.briefing.summary?.entries.orEmpty().map { it.item }
        ).distinctBy(ReviewItem::id)

    private fun minuteLabel(minute: Int): String {
        val normalized = minute.coerceIn(0, 1439)
        val hour = normalized / 60
        val minutePart = normalized % 60
        val displayHour = when (hour % 12) { 0 -> 12 else -> hour % 12 }
        return String.format(Locale.US, "%d:%02d %s", displayHour, minutePart, if (hour < 12) "AM" else "PM")
    }

    private fun durationLabel(seconds: Double): String {
        val hours = (seconds / 3600).toLong()
        val days = hours / 24
        return if (days > 0) "$days days" else "$hours hours"
    }

    private fun ReviewFilters.summaryWithoutStatus(): String = buildList {
        camera?.let { add(cameraDisplayName(it, currentState)) }
        label?.let { add(it.humanize()) }
        zone?.let { add(it.humanize()) }
        timeRange.takeUnless { it == ReviewTimeRange.LAST_DAY }?.let { add(it.displayName) }
    }.joinToString(" • ").ifBlank { "All cameras" }

    private fun ActivitySearchFilters.activeFilterCount(): Int = listOfNotNull(
        cameraName,
        label,
        subLabel,
        zone,
        recognizedLicensePlate,
        timeRange.takeUnless { it == ActivitySearchTimeRange.ALL },
    ).size

    private fun ActivitySearchFilters.summary(state: Phase0UiState): String = buildList {
        cameraName?.let { add(cameraDisplayName(it, state)) }
        label?.let { add(it.humanize()) }
        subLabel?.let { add(it.humanize()) }
        zone?.let { add(it.humanize()) }
        recognizedLicensePlate?.let(::add)
        timeRange.takeUnless { it == ActivitySearchTimeRange.ALL }?.let { add(it.displayName) }
    }.joinToString(" • ")

    private fun Double?.metric(unit: String): String = this?.let { value ->
        "${String.format(Locale.US, "%.1f", value)} $unit"
    } ?: "Not reported"

    private fun Double.storageAmount(): String = when {
        this >= 1024.0 -> "${String.format(Locale.US, "%.1f", this / 1024.0)} GiB"
        else -> "${String.format(Locale.US, "%.0f", this)} MiB"
    }

    private fun storageColor(index: Int): Int = STORAGE_COLORS[index.mod(STORAGE_COLORS.size)]

    private fun formatTime(epochSeconds: Double): String =
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date((epochSeconds * 1_000).toLong()))

    private fun formatDate(epochSeconds: Double): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date((epochSeconds * 1_000).toLong()))

    private fun formatDateTime(epochSeconds: Double): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date((epochSeconds * 1_000).toLong()))

    private fun formatCompactDateTime(epochSeconds: Double): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date((epochSeconds * 1_000).toLong()))

    private fun String.humanize(): String = lowercase(Locale.US)
        .replace('_', ' ')
        .replaceFirstChar(Char::uppercase)

    private inline fun <reified T : Enum<T>> T.next(): T {
        val values = enumValues<T>()
        return values[(ordinal + 1) % values.size]
    }

    private companion object {
        const val THUMBNAIL_HEIGHT_PX = 180
        const val LIVE_ACTION_MESSAGE_DISPLAY_MILLIS = 6_000L
        val STORAGE_COLORS = intArrayOf(
            0xFFFF7048.toInt(),
            0xFF53B7FF.toInt(),
            0xFF65D68A.toInt(),
            0xFFD29BFF.toInt(),
            0xFFFFB454.toInt(),
            0xFF7DD8D2.toInt(),
            0xFFFF7FA8.toInt(),
            0xFF8FA8FF.toInt(),
        )
    }
}

internal class NativeLinearLayoutManager(context: android.content.Context) : LinearLayoutManager(context) {
    override fun onFocusSearchFailed(
        focused: View,
        focusDirection: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State,
    ): View? {
        val candidate = super.onFocusSearchFailed(focused, focusDirection, recycler, state)
        return candidate ?: focused
    }
}
