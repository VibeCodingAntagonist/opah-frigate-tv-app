package app.opah.tv.ui

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.opah.tv.OpahApplication
import app.opah.tv.BuildConfig
import app.opah.tv.data.ConnectionProfileFactory
import app.opah.tv.data.CameraImage
import app.opah.tv.data.ReviewImage
import app.opah.tv.data.DiscoveryBootstrap
import app.opah.tv.data.sanitizeRecentActivitySearches
import app.opah.tv.data.compatibilityIdentityComponents
import app.opah.tv.data.network.AuthenticationExpiredException
import app.opah.tv.data.network.InvalidCredentialsException
import app.opah.tv.data.network.OpahErrorCode
import app.opah.tv.data.network.PtzCommand
import app.opah.tv.data.network.PtzConnectionState
import app.opah.tv.data.network.PtzConnectionStatus
import app.opah.tv.data.network.toOpahFailure
import app.opah.tv.data.realtime.CameraScopeState
import app.opah.tv.data.realtime.RealtimeAuthenticationState
import app.opah.tv.data.model.Camera
import app.opah.tv.data.model.CameraPtzInfo
import app.opah.tv.data.model.AppSettings
import app.opah.tv.data.model.AppearanceMode
import app.opah.tv.data.model.BatchExportItem
import app.opah.tv.data.model.BatchExportRequest
import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.model.CustomThemeColors
import app.opah.tv.data.model.DeviceDiagnostics
import app.opah.tv.data.model.DiscoverySnapshot
import app.opah.tv.data.model.EventSearchQuery
import app.opah.tv.data.model.ExportIncident
import app.opah.tv.data.model.FrigateFeature
import app.opah.tv.data.model.FrigateMode
import app.opah.tv.data.model.LiveStreamOption
import app.opah.tv.data.model.IncidentDraft
import app.opah.tv.data.model.MotionActivity
import app.opah.tv.data.model.MotionSearchRequest
import app.opah.tv.data.model.MotionSearchJobState
import app.opah.tv.data.model.MotionSearchResult
import app.opah.tv.data.model.RecordingHourSummary
import app.opah.tv.data.model.RecordingSegment
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.ReviewCounts
import app.opah.tv.data.model.ReviewSeverity
import app.opah.tv.data.model.SearchEvent
import app.opah.tv.data.model.SavedCameraView
import app.opah.tv.data.model.RecordingExport
import app.opah.tv.data.model.FrigateInformationSummary
import app.opah.tv.data.model.StreamPreference
import app.opah.tv.data.model.StartupTarget
import app.opah.tv.data.model.StartupTargetKind
import app.opah.tv.data.model.ThemeColorPolicy
import app.opah.tv.data.update.AppUpdateAvailability
import app.opah.tv.data.update.AppUpdateCheckResult
import app.opah.tv.data.update.AppRelease
import app.opah.tv.domain.StreamUriFactory
import app.opah.tv.playback.PlaybackKind
import app.opah.tv.playback.PlaybackRequest
import app.opah.tv.playback.BIRDSEYE_STRETCH_PREFERENCE_KEY
import app.opah.tv.privacy.PinRelockReason
import app.opah.tv.privacy.PinProtectionChangeResult
import app.opah.tv.privacy.PinScope
import app.opah.tv.privacy.PinSetupResult
import app.opah.tv.privacy.PinUnlockResult
import app.opah.tv.privacy.PrivacyPolicyMutationResult
import app.opah.tv.privacy.PrivacyRepositoryState
import app.opah.tv.privacy.RecognitionDisclosure
import app.opah.tv.privacy.NotificationDisclosure
import app.opah.tv.privacy.AuthorizationFreshness
import app.opah.tv.privacy.PrivacyDecision
import app.opah.tv.privacy.PrivacyDecisionEngine
import app.opah.tv.privacy.PrivacyGrant
import app.opah.tv.privacy.PrivacyRequest
import app.opah.tv.privacy.PrivacySessionEvidence
import app.opah.tv.privacy.PrivacySurface
import app.opah.tv.privacy.PrivacyTarget
import app.opah.tv.notifications.AlertConfigurationMutationResult
import app.opah.tv.notifications.AlertConfigurationState
import app.opah.tv.notifications.AlertMode
import app.opah.tv.notifications.NotificationPrivacy
import app.opah.tv.notifications.AlertSnooze
import app.opah.tv.notifications.AlertSnoozeScope
import app.opah.tv.notifications.AlertDayOfWeek
import app.opah.tv.notifications.AlertScheduleWindow
import app.opah.tv.notifications.TvAlertOpenResolution
import app.opah.tv.notifications.nextLocalDayStartEpochMillis
import app.opah.tv.awareness.AwarenessReviewSeverity
import app.opah.tv.monitor.MonitorAction
import app.opah.tv.monitor.MonitorArbiter
import app.opah.tv.monitor.MonitorCommand
import app.opah.tv.monitor.MonitorConfiguration
import app.opah.tv.monitor.MonitorPhase
import app.opah.tv.monitor.MonitorPreset
import app.opah.tv.monitor.MonitorState
import app.opah.tv.playback.compatibility.PlaybackPurpose
import app.opah.tv.playback.compatibility.AudioMode
import app.opah.tv.playback.compatibility.DecoderMode
import app.opah.tv.playback.compatibility.TransportMode
import app.opah.tv.notifications.android.TvAlertActivationResult
import app.opah.tv.notifications.android.TvAlertDeliveryStatus
import app.opah.tv.notifications.android.TvAlertOverlayHorizontalPosition
import app.opah.tv.notifications.android.TvAlertOverlayImageSize
import app.opah.tv.notifications.android.TvAlertOverlaySettings
import app.opah.tv.notifications.android.TvAlertOverlayVerticalPosition
import app.opah.tv.notifications.android.TV_ALERT_OVERLAY_MAX_DISPLAY_SECONDS
import app.opah.tv.notifications.android.TV_ALERT_OVERLAY_MIN_DISPLAY_SECONDS
import app.opah.tv.briefing.BriefingAcknowledgementReason
import app.opah.tv.briefing.BriefingAudience
import app.opah.tv.briefing.BriefingDismissalTarget
import app.opah.tv.briefing.BriefingEntry
import app.opah.tv.briefing.BriefingRefreshRequest
import app.opah.tv.briefing.BriefingScopeKey
import app.opah.tv.briefing.BriefingSummary
import app.opah.tv.briefing.afterPresentedEntriesAcknowledged
import app.opah.tv.playback.compatibility.CompatibilityIdentityDomain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Request

data class InformationUiState(
    val loading: Boolean = false,
    val loadedOnce: Boolean = false,
    val summary: FrigateInformationSummary? = null,
    val errorMessage: String? = null,
)

data class ExportsUiState(
    val loading: Boolean = false,
    val loadedOnce: Boolean = false,
    val items: List<RecordingExport> = emptyList(),
    val errorMessage: String? = null,
    val deletingItemId: String? = null,
    val selectedItemId: String? = null,
    val incidents: List<ExportIncident> = emptyList(),
    val incidentsLoaded: Boolean = false,
    val incidentsErrorMessage: String? = null,
    val selectedIncidentId: String? = null,
    val explicitlyCreatedEmptyIncidentIds: Set<String> = emptySet(),
    val operationBusy: Boolean = false,
    val operationMessage: String? = null,
)

data class AppUpdateUiState(
    val checking: Boolean = false,
    val checkedOnce: Boolean = false,
    val updateAvailable: Boolean = false,
    val latestVersion: String? = null,
    val releasePageUrl: String? = null,
    val releaseNotes: String = "",
    val availableRelease: AppRelease? = null,
    val downloading: Boolean = false,
    val preparedApkPath: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

data class PtzUiState(
    val cameraName: String? = null,
    val connection: PtzConnectionState = PtzConnectionState(),
    val errorMessage: String? = null,
)

data class ModesUiState(
    val loading: Boolean = false,
    val loadedOnce: Boolean = false,
    val modes: List<FrigateMode> = emptyList(),
    val activeMode: String? = null,
    val switching: Boolean = false,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    val undoMode: String? = null,
    val undoAvailable: Boolean = false,
)

data class LiveActionsUiState(
    val snapshotCapturing: Boolean = false,
    val recordingEventId: String? = null,
    val recordingCameraName: String? = null,
    val recordingBusy: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null,
)

data class HealthUiState(
    val messagesByCamera: Map<String, String> = emptyMap(),
) {
    val messages: List<String> get() = messagesByCamera.values.sorted()
}

data class PrivacyUiState(
    val loading: Boolean = true,
    val available: Boolean = false,
    val epoch: Long = 0L,
    val guestModeActive: Boolean = false,
    val startInGuestMode: Boolean = false,
    val privateCameraIds: Set<String> = emptySet(),
    val guestModeFrigateModes: Set<String> = emptySet(),
    val pinConfigured: Boolean = false,
    val pinRecordCorrupt: Boolean = false,
    val protectedScopes: Set<PinScope> = emptySet(),
    val unlockedScopes: Set<PinScope> = emptySet(),
    val pinSelectionsUnlocked: Boolean = false,
    val ownerRecognitionDisclosure: RecognitionDisclosure = RecognitionDisclosure.SHOW_ALL,
    val guestRecognitionDisclosure: RecognitionDisclosure = RecognitionDisclosure.HIDE_ALL,
    val globalNotificationDisclosure: NotificationDisclosure = NotificationDisclosure.TEXT_ONLY,
    val guestNotificationDisclosure: NotificationDisclosure = NotificationDisclosure.TEXT_ONLY,
    val busy: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

data class TvAlertsUiState(
    val loading: Boolean = true,
    val available: Boolean = false,
    val enabled: Boolean = false,
    val mode: AlertMode = AlertMode.IMPORTANT_ACTIVITY,
    val notificationPrivacy: NotificationPrivacy = NotificationPrivacy.TEXT_ONLY,
    val snoozeCount: Int = 0,
    val significantMotionEnabled: Boolean = false,
    val customSeverities: Set<AwarenessReviewSeverity> = setOf(AwarenessReviewSeverity.ALERT),
    val cameraIds: Set<String> = emptySet(),
    val labels: Set<String> = emptySet(),
    val zones: Set<String> = emptySet(),
    val subLabels: Set<String> = emptySet(),
    val plateLabels: Set<String> = emptySet(),
    val schedulePreset: TvAlertSchedulePreset = TvAlertSchedulePreset.ALWAYS,
    val scheduleStartMinute: Int = DEFAULT_TV_ALERT_START_MINUTE,
    val scheduleEndMinute: Int = DEFAULT_TV_ALERT_END_MINUTE,
    val frigateModes: Set<String> = emptySet(),
    val minimumThreatLevel: Int? = null,
    val snoozeChoice: TvAlertSnoozeChoice = TvAlertSnoozeChoice.NONE,
    val deliveryStatus: TvAlertDeliveryStatus = TvAlertDeliveryStatus.AVAILABLE,
    val overlayPermissionGranted: Boolean = false,
    val overlaySettings: TvAlertOverlaySettings = TvAlertOverlaySettings(),
    val busy: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

enum class TvAlertSchedulePreset {
    ALWAYS,
    CUSTOM,
}

internal fun alertSchedulePreset(schedules: List<AlertScheduleWindow>): TvAlertSchedulePreset = when {
    schedules.isEmpty() -> TvAlertSchedulePreset.ALWAYS
    else -> TvAlertSchedulePreset.CUSTOM
}

enum class TvAlertSnoozeChoice {
    NONE,
    FIFTEEN_MINUTES,
    ONE_HOUR,
    UNTIL_TOMORROW,
    UNTIL_MODE_CHANGES,
    MULTIPLE,
}

internal const val DEFAULT_TV_ALERT_START_MINUTE = 7 * 60
internal const val DEFAULT_TV_ALERT_END_MINUTE = 22 * 60

internal fun alertSnoozeChoice(snoozes: List<AlertSnooze>, nowEpochMillis: Long): TvAlertSnoozeChoice {
    val snooze = snoozes.singleOrNull() ?: return if (snoozes.isEmpty()) {
        TvAlertSnoozeChoice.NONE
    } else {
        TvAlertSnoozeChoice.MULTIPLE
    }
    if (snooze.untilModeChangesFrom != null) return TvAlertSnoozeChoice.UNTIL_MODE_CHANGES
    val remaining = (snooze.expiresAtEpochMillis ?: return TvAlertSnoozeChoice.MULTIPLE) - nowEpochMillis
    return when {
        remaining <= 0L -> TvAlertSnoozeChoice.NONE
        remaining <= 20L * 60L * 1_000L -> TvAlertSnoozeChoice.FIFTEEN_MINUTES
        remaining <= 70L * 60L * 1_000L -> TvAlertSnoozeChoice.ONE_HOUR
        else -> TvAlertSnoozeChoice.UNTIL_TOMORROW
    }
}

data class CameraGroupStream(
    val camera: Camera,
    val uri: String,
    val videoMimeType: String? = null,
)

data class CameraGroupViewUiState(
    val title: String,
    val streams: List<CameraGroupStream>,
    val decoderWarning: Boolean = false,
)

data class MonitorModeUiState(
    val title: String,
    val cameras: List<Camera>,
    val preset: MonitorPreset,
    val visibleCameraIds: Set<String>,
    val arbitration: MonitorState,
    val liveCompatibilityRequestId: String? = null,
    val keepScreenAwake: Boolean = false,
    val audioEnabled: Boolean = false,
    val exitAtEpochMillis: Long? = null,
    val exitAfterMinutes: Int? = null,
    val statusMessage: String? = null,
)

data class BriefingUiState(
    val loading: Boolean = false,
    val summary: BriefingSummary? = null,
    val activeHighlightIds: Set<String> = emptySet(),
    val errorMessage: String? = null,
    val audience: BriefingAudience? = null,
    val privacyEpoch: Long? = null,
)

private data class HistoryLoadResult(
    val hourStartSeconds: Double,
    val summaries: List<RecordingHourSummary>,
    val segments: List<RecordingSegment>,
    val motion: List<MotionActivity>,
)

private data class BoundPrivacyGrant(
    val request: PrivacyRequest,
    val grant: PrivacyGrant,
)

internal fun decoderCapacityMayBeInsufficient(
    streams: List<CameraGroupStream>,
    device: DeviceDiagnostics?,
): Boolean = streams.mapNotNull(CameraGroupStream::videoMimeType)
    .groupingBy { it }
    .eachCount()
    .any { (mimeType, requiredInstances) ->
        val codec = device?.codecs?.firstOrNull { it.mimeType == mimeType } ?: return@any false
        val likelyHardwareDecoders = codec.decoders.filter { it.hardwareAccelerated != false }
            .ifEmpty { codec.decoders }
        val reportedMaximum = likelyHardwareDecoders.mapNotNull { it.maxSupportedInstances }.maxOrNull()
        reportedMaximum != null && requiredInstances > reportedMaximum
    }

internal fun AppUpdateUiState.afterSuccessfulCheck(result: AppUpdateCheckResult): AppUpdateUiState =
    AppUpdateUiState(
        checking = false,
        checkedOnce = true,
        updateAvailable = result.availability == AppUpdateAvailability.UPDATE_AVAILABLE,
        latestVersion = result.latestRelease.version.canonical,
        releasePageUrl = result.latestRelease.releasePageUrl,
        releaseNotes = result.latestRelease.releaseNotes,
        availableRelease = result.availableRelease,
        preparedApkPath = preparedApkPath.takeIf {
            result.availability == AppUpdateAvailability.UPDATE_AVAILABLE &&
                latestVersion == result.latestRelease.version.canonical
        },
        statusMessage = when (result.availability) {
            AppUpdateAvailability.UPDATE_AVAILABLE -> "Check complete • Opah ${result.latestRelease.version.canonical} is available"
            AppUpdateAvailability.UP_TO_DATE -> "Check complete • Opah is up to date"
        },
    )

internal fun AppUpdateUiState.afterFailedCheck(message: String): AppUpdateUiState = copy(
    checking = false,
    checkedOnce = true,
    statusMessage = null,
    errorMessage = message,
)

internal fun shouldScheduleAutomaticUpdateCheck(
    enabled: Boolean,
    checkedOnce: Boolean,
    checking: Boolean,
    scheduled: Boolean,
): Boolean = enabled && !checkedOnce && !checking && !scheduled

private const val AUTOMATIC_UPDATE_CHECK_DELAY_MILLIS = 5_000L
private const val TV_ALERT_OPEN_RETRY_ATTEMPTS = 40
private const val TV_ALERT_OPEN_RETRY_DELAY_MILLIS = 250L
private const val BRIEFING_PRIVACY_SCHEMA_VERSION = 1

private fun Long.nextNavigationRequestId(): Long = if (this == Long.MAX_VALUE) 1L else this + 1L

private fun NotificationPrivacy.toPrivacyDisclosure(): NotificationDisclosure = when (this) {
    NotificationPrivacy.FULL_PREVIEW -> NotificationDisclosure.FULL_PREVIEW
    NotificationPrivacy.BLURRED_PREVIEW -> NotificationDisclosure.BLURRED_PREVIEW
    NotificationPrivacy.TEXT_ONLY -> NotificationDisclosure.TEXT_ONLY
    NotificationPrivacy.NEVER_NOTIFY -> NotificationDisclosure.NEVER
}

private fun Set<String>.withSelection(value: String, enabled: Boolean): Set<String> =
    if (enabled) this + value else this - value

enum class TvAlertNavigationDestination {
    ACTIVITY,
    TV_ALERT_SETTINGS,
}

data class TvAlertNavigationUiState(
    val requestId: Long,
    val destination: TvAlertNavigationDestination,
    val expired: Boolean = false,
)

enum class PlaybackCompatibilityTestMode {
    AUTOMATIC,
    STANDARD_CONNECTION,
    RELIABLE_CONNECTION,
    VIDEO_ONLY,
    RELIABLE_VIDEO_ONLY,
}

internal fun savedPlaybackChoiceLabel(
    audioMode: AudioMode,
    transportMode: TransportMode,
    decoderMode: DecoderMode,
): String = buildList {
    add(if (transportMode == TransportMode.FORCE_RTP_TCP) "reliable connection" else "standard connection")
    add(if (audioMode == AudioMode.VIDEO_ONLY) "video only" else "video and sound")
    add(
        when (decoderMode) {
            DecoderMode.PLATFORM_DEFAULT -> "automatic video decoder"
            DecoderMode.PREFER_HARDWARE -> "TV video decoder"
            DecoderMode.ALLOW_SOFTWARE -> "compatibility video decoder"
        },
    )
}.joinToString(" • ")

private data class PendingTvAlertOpen(
    val profileKey: String,
    val reviewId: String,
    val actionNonce: String,
)

private data class ActiveBriefingIdentity(
    val profile: ConnectionProfile,
    val profileKey: String,
    val scopeKey: BriefingScopeKey,
    val privacyEpoch: Long,
    val audience: BriefingAudience,
)

data class Phase0UiState(
    val loading: Boolean = true,
    val connectionWorkInProgress: Boolean = false,
    val statusMessage: String = "Starting Opah…",
    val errorMessage: String? = null,
    val savedProfile: ConnectionProfile? = null,
    val activeProfile: ConnectionProfile? = null,
    val snapshot: DiscoverySnapshot? = null,
    val recentActivityLoaded: Boolean = false,
    val device: DeviceDiagnostics? = null,
    val playback: PlaybackRequest? = null,
    val cameraGroupView: CameraGroupViewUiState? = null,
    val activeCameraName: String? = null,
    val settings: AppSettings = AppSettings(),
    val settingsLoaded: Boolean = false,
    val review: ReviewBrowserState = ReviewBrowserState(),
    val history: HistoryBrowserState = HistoryBrowserState(),
    val activitySearch: ActivitySearchState = ActivitySearchState(),
    val information: InformationUiState = InformationUiState(),
    val exports: ExportsUiState = ExportsUiState(),
    val appUpdate: AppUpdateUiState = AppUpdateUiState(),
    val ptz: PtzUiState = PtzUiState(),
    val modes: ModesUiState = ModesUiState(),
    val liveActions: LiveActionsUiState = LiveActionsUiState(),
    val motionReview: MotionReviewUiState = MotionReviewUiState(),
    val health: HealthUiState = HealthUiState(),
    val savedSessionRecoveryAvailable: Boolean = false,
    val playbackCompatibilityMessage: String? = null,
    val playbackCompatibilityChoices: Map<String, String> = emptyMap(),
    val privacy: PrivacyUiState = PrivacyUiState(),
    val tvAlerts: TvAlertsUiState = TvAlertsUiState(),
    val tvAlertNavigation: TvAlertNavigationUiState? = null,
    val tvAlertPrivacyGateOpen: Boolean = false,
    val monitorMode: MonitorModeUiState? = null,
    val briefing: BriefingUiState = BriefingUiState(),
)

class Phase0ViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as OpahApplication).container
    private val repository = container.frigateRepository
    private val profileRepository = container.profileRepository
    private val sessionManager = container.sessionManager
    private val codecService = container.deviceMediaCapabilityService
    private val streamSelector = container.streamSelectionService
    private val logger = container.logger
    private val settingsRepository = container.settingsRepository
    private val appUpdateRepository = container.appUpdateRepository
    private val appUpdatePreparationRepository = container.appUpdatePreparationRepository
    private val cameraImageRepository = container.cameraImageRepository
    private val reviewImageRepository = container.reviewImageRepository
    private val ptzWebSocketClient = container.ptzWebSocketClient
    private val privacyRepository = container.privacyRepository
    private val alertConfigurationRepository = container.alertConfigurationRepository
    private val pinCredentialService = container.pinCredentialService
    private val privacyDecisionEngine = PrivacyDecisionEngine()
    private val operationsRepository = container.frigateOperationsRepository
    private val briefingCoordinator = container.briefingCoordinator
    private val briefingScopeFactory = container.briefingScopeFactory
    private val documentationImages by lazy { DocumentationSupport.images(application) }
    private var reviewLoadJob: Job? = null
    private var reviewLoadMoreJob: Job? = null
    private var reviewLoadGeneration = 0L
    private var reviewDetailJob: Job? = null
    private var reviewPlaybackNavigationJob: Job? = null
    private var reviewWriteRequestId = 0L
    private var historyLoadJob: Job? = null
    private var historyPrefetchJob: Job? = null
    private val historyRangeCache = linkedMapOf<String, HistoryLoadResult>()
    private val cameraImageFailureCounts = ConcurrentHashMap<String, Int>()
    private var activitySearchJob: Job? = null
    private var activitySearchRequestId: Long = 0
    private var enrichmentJob: Job? = null
    private var connectionRequestGeneration = 0L
    private var briefingRefreshJob: Job? = null
    private var updateCheckJob: Job? = null
    private var automaticUpdateCheckJob: Job? = null
    private var exportsLoadJob: Job? = null
    private var motionSearchJob: Job? = null
    private var motionSearchRequestId: Long = 0
    private var pendingCameraName: String? = null
    private var tvAlertNavigationRequestId = 0L
    private var pendingTvAlertOpen: PendingTvAlertOpen? = null
    private var monitorJob: Job? = null
    private val monitorMutex = Mutex()
    private val monitorArbiter = MonitorArbiter()
    private val documentationReviewStatuses = mutableMapOf<String, Boolean>()
    private val nativeBrowseRestoration = NativeBrowseRestorationGate()

    private val _state = MutableStateFlow(Phase0UiState())
    val state: StateFlow<Phase0UiState> = _state.asStateFlow()

    internal fun attachNativeBrowseSelections(saved: NativeBrowseSelections?) {
        nativeBrowseRestoration.attach(saved)
    }

    internal fun captureNativeBrowseSelections(): NativeBrowseSelections =
        nativeBrowseRestoration.pending ?: _state.value.captureAuthorizedNativeBrowseSelections(nativeBrowseAccess())

    internal fun restoreNativeBrowseSelectionsIfReady(): NativeBrowseRestoreResult {
        if (nativeBrowseRestoration.pending == null) return NativeBrowseRestoreResult.NO_PENDING
        return nativeBrowseRestoration.restore(nativeBrowseAccess()) { valid ->
            _state.update { it.withRestoredNativeBrowseSelections(valid) }
        }
    }

    internal fun validatedNativeBrowseSelections(saved: NativeBrowseSelections): NativeBrowseSelections? =
        nativeBrowseAccess()?.let(saved::authorizedFor)

    private fun nativeBrowseProfileKey(): String? = _state.value.activeProfile?.let { profile ->
        runCatching {
            container.compatibilityIdentityFactory.derive(
                CompatibilityIdentityDomain.PROFILE,
                profile.compatibilityIdentityComponents(),
            ).value
        }.getOrNull()
    }

    private fun nativeBrowseAccess(): NativeBrowseAccess? {
        val current = _state.value
        val snapshot = current.snapshot ?: return null
        if (current.activeProfile == null || current.privacy.loading) return null
        val cameraIds = snapshot.cameras.mapTo(mutableSetOf(), Camera::name)
        return NativeBrowseAccess(
            profileKey = nativeBrowseProfileKey(),
            reviewCameraIds = visibleCameraNames(PrivacySurface.ACTIVITY).intersect(cameraIds),
            historyCameraIds = visibleCameraNames(PrivacySurface.HISTORY).intersect(cameraIds),
            searchCameraIds = visibleCameraNames(PrivacySurface.SEARCH).intersect(cameraIds),
            features = FrigateFeature.entries.filterTo(mutableSetOf(), snapshot.capabilities::supports),
            nowSeconds = System.currentTimeMillis() / 1_000.0,
        )
    }

    init {
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.value = DocumentationSupport.data.state(null)
        } else {
            _state.update { current ->
                current.copy(
                    tvAlerts = current.tvAlerts.copy(
                        overlaySettings = container.tvAlertOverlaySettings(),
                    ),
                )
            }
            viewModelScope.launch {
                ptzWebSocketClient.state.collect { connection ->
                    _state.update { current ->
                        current.copy(ptz = current.ptz.copy(connection = connection))
                    }
                }
            }
            viewModelScope.launch {
                settingsRepository.settings.collect { settings ->
                    _state.update { it.copy(settings = settings, settingsLoaded = true) }
                    scheduleAutomaticUpdateCheckIfNeeded(settings)
                }
            }
            viewModelScope.launch {
                privacyRepository.initialize()
                privacyRepository.state.collect { repositoryState ->
                    val previousPrivacy = _state.value.privacy
                    _state.update { current ->
                        val previous = current.privacy
                        val privacy = when (repositoryState) {
                            PrivacyRepositoryState.Loading -> previous.copy(loading = true)
                            is PrivacyRepositoryState.Unavailable -> previous.copy(
                                loading = false,
                                available = false,
                                epoch = repositoryState.epoch,
                                unlockedScopes = emptySet(),
                                pinSelectionsUnlocked = false,
                                busy = false,
                                errorMessage = "Privacy controls are unavailable",
                            )
                            is PrivacyRepositoryState.Ready -> {
                                val proof = container.pinUnlockSessionOwner.currentProof(
                                    repositoryState.policy.epoch,
                                    SystemClock.elapsedRealtime(),
                                )
                                previous.copy(
                                    loading = false,
                                    available = true,
                                    epoch = repositoryState.policy.epoch,
                                    guestModeActive = repositoryState.policy.guestModeActive,
                                    startInGuestMode = repositoryState.policy.startInGuestMode,
                                    privateCameraIds = repositoryState.policy.privateCameraIds,
                                    guestModeFrigateModes = repositoryState.policy.guestModeFrigateModes,
                                    pinConfigured = repositoryState.pinConfigured,
                                    pinRecordCorrupt = repositoryState.pinRecordCorrupt,
                                    protectedScopes = repositoryState.protectedScopes,
                                    unlockedScopes = proof?.scopes.orEmpty(),
                                    pinSelectionsUnlocked = proof != null,
                                    ownerRecognitionDisclosure = repositoryState.policy.ownerRecognitionDisclosure,
                                    guestRecognitionDisclosure = repositoryState.policy.guestRecognitionDisclosure,
                                    globalNotificationDisclosure = repositoryState.policy.globalNotificationDisclosure,
                                    guestNotificationDisclosure = repositoryState.policy.guestNotificationDisclosure,
                                    errorMessage = if (repositoryState.pinRecordCorrupt) {
                                        "PIN protection needs recovery"
                                    } else {
                                        previous.errorMessage?.takeIf { previous.busy }
                                    },
                                )
                            }
                        }
                        current.copy(privacy = privacy)
                    }
                    val currentPrivacy = _state.value.privacy
                    if (
                        currentPrivacy.epoch != previousPrivacy.epoch ||
                        currentPrivacy.available != previousPrivacy.available ||
                        currentPrivacy.guestModeActive != previousPrivacy.guestModeActive ||
                        currentPrivacy.unlockedScopes != previousPrivacy.unlockedScopes
                    ) {
                        enforceCurrentPrivacyState()
                        loadBriefing(refreshFromFrigate = currentPrivacy.available && _state.value.recentActivityLoaded)
                    }
                }
            }
            viewModelScope.launch {
                alertConfigurationRepository.state.collect { repositoryState ->
                    _state.update { current ->
                        val previous = current.tvAlerts
                        val alerts = when (repositoryState) {
                            AlertConfigurationState.Loading -> previous.copy(
                                loading = true,
                                available = false,
                                busy = false,
                            )
                            is AlertConfigurationState.Unavailable -> previous.copy(
                                loading = false,
                                available = false,
                                enabled = false,
                                busy = false,
                                errorMessage = "TV alerts are unavailable",
                            )
                            is AlertConfigurationState.Ready -> previous.copy(
                                loading = false,
                                available = true,
                                enabled = repositoryState.configuration.enabled,
                                mode = repositoryState.configuration.policy.mode,
                                notificationPrivacy = repositoryState.configuration.policy.notificationPrivacy,
                                snoozeCount = repositoryState.configuration.snoozes.size,
                                significantMotionEnabled =
                                    repositoryState.configuration.policy.significantMotionEnabled,
                                customSeverities = repositoryState.configuration.policy.customSeverities,
                                cameraIds = repositoryState.configuration.policy.cameraIds,
                                labels = repositoryState.configuration.policy.labels,
                                zones = repositoryState.configuration.policy.zones,
                                subLabels = repositoryState.configuration.policy.subLabels,
                                plateLabels = repositoryState.configuration.policy.plateLabels,
                                schedulePreset = alertSchedulePreset(
                                    repositoryState.configuration.policy.schedules,
                                ),
                                scheduleStartMinute = repositoryState.configuration.policy.schedules
                                    .firstOrNull()?.startMinuteInclusive ?: DEFAULT_TV_ALERT_START_MINUTE,
                                scheduleEndMinute = repositoryState.configuration.policy.schedules
                                    .firstOrNull()?.endMinuteExclusive ?: DEFAULT_TV_ALERT_END_MINUTE,
                                frigateModes = repositoryState.configuration.policy.frigateModes,
                                minimumThreatLevel =
                                    repositoryState.configuration.policy.minimumThreatLevel,
                                snoozeChoice = alertSnoozeChoice(
                                    repositoryState.configuration.snoozes,
                                    System.currentTimeMillis().coerceAtLeast(0L),
                                ),
                                busy = false,
                                errorMessage = null,
                            )
                        }
                        current.copy(tvAlerts = alerts)
                    }
                }
            }
            viewModelScope.launch(Dispatchers.Default) {
                runCatching { codecService.inspect() }
                    .onSuccess { device ->
                        // Build the process playback composition off the UI thread before live
                        // camera actions become available through the published diagnostics.
                        container.singleLivePlaybackCoordinator
                        _state.update { it.copy(device = device) }
                    }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        logger.warning("Device codec inspection failed", error)
                    }
            }
            viewModelScope.launch {
                val profile = profileRepository.load()
                _state.update { it.copy(savedProfile = profile) }

                val canRestore = profile != null && (
                    profile.usesUnauthenticatedFrigatePort || withContext(Dispatchers.IO) {
                        container.cookieJar.hasUnexpiredSession() || sessionManager.hasSavedCredential()
                    }
                )
                if (profile != null && canRestore) {
                    loadSavedSession(profile)
                } else if (profile != null) {
                    _state.update {
                        it.copy(loading = false, statusMessage = "Sign in to Frigate")
                    }
                } else {
                    _state.update {
                        it.copy(
                            loading = false,
                            statusMessage = "Enter your Frigate connection",
                        )
                    }
                }
            }
        }
    }

    fun setDocumentationScenario(scenario: String?) {
        if (!BuildConfig.DOCUMENTATION_MODE) return
        documentationReviewStatuses.clear()
        _state.value = DocumentationSupport.data.state(scenario)
    }

    fun checkForUpdates(forceRefresh: Boolean = false) {
        if (BuildConfig.DOCUMENTATION_MODE || updateCheckJob?.isActive == true) return
        _state.update {
            it.copy(
                appUpdate = it.appUpdate.copy(
                    checking = true,
                    statusMessage = "Checking for an update…",
                    errorMessage = null,
                ),
            )
        }
        updateCheckJob = viewModelScope.launch {
            try {
                val result = appUpdateRepository.check(forceRefresh)
                _state.update { current ->
                    current.copy(appUpdate = current.appUpdate.afterSuccessfulCheck(result))
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                logger.warning("App update check failed", error)
                _state.update { current ->
                    current.copy(
                        appUpdate = current.appUpdate.afterFailedCheck(
                            "Opah couldn't check for an update. Try again later.",
                        ),
                    )
                }
            }
        }
    }

    private fun scheduleAutomaticUpdateCheckIfNeeded(settings: AppSettings) {
        val update = _state.value.appUpdate
        if (!shouldScheduleAutomaticUpdateCheck(
                enabled = settings.automaticUpdateChecksEnabled,
                checkedOnce = update.checkedOnce,
                checking = update.checking,
                scheduled = automaticUpdateCheckJob?.isActive == true,
            )
        ) {
            return
        }
        automaticUpdateCheckJob = viewModelScope.launch {
            delay(AUTOMATIC_UPDATE_CHECK_DELAY_MILLIS)
            val current = _state.value
            if (current.settings.automaticUpdateChecksEnabled &&
                !current.appUpdate.checkedOnce &&
                !current.appUpdate.checking
            ) {
                checkForUpdates()
            }
        }
    }

    fun downloadUpdate() {
        val release = _state.value.appUpdate.availableRelease ?: return
        if (_state.value.appUpdate.downloading) return
        _state.update {
            it.copy(
                appUpdate = it.appUpdate.copy(
                    downloading = true,
                    preparedApkPath = null,
                    errorMessage = null,
                ),
            )
        }
        viewModelScope.launch {
            runCatching { appUpdatePreparationRepository.prepare(release) }
                .onSuccess { prepared ->
                    _state.update {
                        it.copy(
                            appUpdate = it.appUpdate.copy(
                                downloading = false,
                                preparedApkPath = prepared.apkPath,
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    logger.warning("App update download or verification failed", error)
                    _state.update {
                        it.copy(
                            appUpdate = it.appUpdate.copy(
                                downloading = false,
                                preparedApkPath = null,
                                errorMessage = (error as? app.opah.tv.data.update.AppUpdateException)?.message
                                    ?: "Opah couldn't verify the update",
                            ),
                        )
                    }
                }
        }
    }

    fun reportUpdateInstallError() {
        _state.update {
            it.copy(
                appUpdate = it.appUpdate.copy(
                    errorMessage = "Android couldn't open the update installer",
                ),
            )
        }
    }

    fun connect(
        rawUrl: String,
        username: String,
        password: String,
        rtspHostOverride: String,
        rtspPortText: String,
    ) {
        submitConnection(rawUrl, username, password, rtspHostOverride, rtspPortText, testOnly = false)
    }

    fun testConnection(
        rawUrl: String,
        username: String,
        password: String,
        rtspHostOverride: String,
        rtspPortText: String,
    ) {
        submitConnection(rawUrl, username, password, rtspHostOverride, rtspPortText, testOnly = true)
    }

    internal fun connect(
        rawUrl: String,
        username: String,
        password: String,
        rtspHostOverride: String,
        rtspPortText: String,
        onResult: (Result<String>) -> Unit,
    ): Job? = submitConnection(rawUrl, username, password, rtspHostOverride, rtspPortText, false, onResult)

    internal fun testConnection(
        rawUrl: String,
        username: String,
        password: String,
        rtspHostOverride: String,
        rtspPortText: String,
        onResult: (Result<String>) -> Unit,
    ): Job? = submitConnection(rawUrl, username, password, rtspHostOverride, rtspPortText, true, onResult)

    private fun submitConnection(
        rawUrl: String,
        username: String,
        password: String,
        rtspHostOverride: String,
        rtspPortText: String,
        testOnly: Boolean,
        onResult: ((Result<String>) -> Unit)? = null,
    ): Job? {
        if (_state.value.loading) {
            onResult?.invoke(Result.failure(IllegalStateException("Wait for the current connection attempt")))
            return null
        }
        val profile = ConnectionProfileFactory.create(
            rawApiBaseUrl = rawUrl,
            username = username,
            rtspHostOverride = rtspHostOverride,
            rtspPort = rtspPortText.toIntOrNull() ?: -1,
        ).getOrElse { error ->
            if (onResult == null) {
                _state.update { it.copy(errorMessage = error.message ?: "Invalid connection settings") }
            } else {
                onResult(Result.failure(error))
            }
            return null
        }
        val generation = ++connectionRequestGeneration
        val previousStatus = _state.value.statusMessage
        if (!testOnly) enrichmentJob?.cancel()
        beginConnectionWork(if (testOnly) "Testing connection…" else "Connecting…")
        return viewModelScope.launch {
            try {
                val message = if (testOnly) {
                    val snapshot = sessionManager.testConnection(profile, password) { user ->
                        repository.discover(profile, user)
                    }
                    if (!isActive || generation != connectionRequestGeneration) return@launch
                    val testedMessage = "Connected • ${snapshot.cameras.size} cameras"
                    _state.update {
                        it.copy(
                            loading = false,
                            connectionWorkInProgress = false,
                            statusMessage = testedMessage,
                            errorMessage = null,
                        )
                    }
                    testedMessage
                } else {
                    val user = sessionManager.signIn(profile, password)
                    val bootstrap = repository.discoverEssential(profile, user)
                    if (!isActive || generation != connectionRequestGeneration) return@launch
                    clearImageCaches()
                    publishConnected(profile, bootstrap)
                    startEnrichment(profile, bootstrap)
                    "Connected"
                }
                onResult?.invoke(Result.success(message))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isActive || generation != connectionRequestGeneration) return@launch
                if (onResult == null) {
                    showFailure(error)
                } else {
                    logger.warning("Frigate operation failed", error)
                    _state.update {
                        it.copy(loading = false, connectionWorkInProgress = false, statusMessage = previousStatus)
                    }
                    onResult(Result.failure(IllegalStateException(error.toOpahFailure().userMessage)))
                }
            }
        }.also { request ->
            request.invokeOnCompletion { cause ->
                if (cause is CancellationException && generation == connectionRequestGeneration) {
                    _state.update {
                        if (it.connectionWorkInProgress) {
                            it.copy(loading = false, connectionWorkInProgress = false, statusMessage = previousStatus)
                        } else it
                    }
                }
            }
        }
    }

    fun refresh() {
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update { current ->
                DocumentationSupport.data.state(null).copy(settings = current.settings)
            }
            return
        }
        val profile = _state.value.activeProfile ?: return
        enrichmentJob?.cancel()
        ptzWebSocketClient.disconnect()
        viewModelScope.launch {
            beginWork("Refreshing Frigate diagnostics…")
            runCatching {
                val user = sessionManager.restore(profile)
                val snapshot = repository.discover(profile, user)
                val counts = runCatching {
                    repository.loadReviewCounts(profile, snapshot.user.allowedCameras)
                }
                snapshot to counts
            }
                .onSuccess { (snapshot, counts) ->
                    clearImageCaches()
                    _state.update { current ->
                        current.copy(
                            loading = false,
                            statusMessage = "Connected",
                            errorMessage = null,
                            snapshot = snapshot,
                            recentActivityLoaded = true,
                            cameraGroupView = null,
                            review = ReviewBrowserState(
                                counts = counts.getOrDefault(current.review.counts),
                            ),
                            history = HistoryBrowserState(),
                            activitySearch = ActivitySearchState(),
                            information = InformationUiState(),
                            exports = ExportsUiState(),
                            ptz = PtzUiState(),
                        )
                    }
                }
                .onFailure(::handleConnectedFailure)
        }
    }

    fun logout(forgetServer: Boolean = false) {
        if (BuildConfig.DOCUMENTATION_MODE) {
            setDocumentationScenario("SETUP")
            return
        }
        container.onSignedOut()
        val beforeLogout = _state.value
        val profile = beforeLogout.activeProfile ?: beforeLogout.savedProfile
        cancelReviewLoads()
        reviewDetailJob?.cancel()
        reviewPlaybackNavigationJob?.cancel()
        historyLoadJob?.cancel()
        historyPrefetchJob?.cancel()
        activitySearchJob?.cancel()
        activitySearchRequestId += 1
        exportsLoadJob?.cancel()
        motionSearchJob?.cancel()
        motionSearchRequestId += 1
        enrichmentJob?.cancel()
        briefingRefreshJob?.cancel()
        ptzWebSocketClient.disconnect()
        viewModelScope.launch {
            val activeEventId = beforeLogout.liveActions.recordingEventId
            val activeSnapshot = beforeLogout.snapshot
            if (profile != null && activeEventId != null && activeSnapshot != null) {
                runCatching {
                    operationsRepository.stopOnDemandRecording(
                        profile,
                        activeSnapshot.user,
                        activeSnapshot.frigateVersion,
                        activeEventId,
                    )
                }.onFailure { error -> logger.warning("Stopping on-demand recording during sign out failed", error) }
            }
            if (forgetServer && profile != null) {
                val profileKey = container.compatibilityIdentityFactory.derive(
                    CompatibilityIdentityDomain.PROFILE,
                    profile.compatibilityIdentityComponents(),
                ).value
                runCatching { container.briefingStore.deleteProfile(profileKey) }
                    .onFailure { error -> logger.warning("Removing saved briefing state failed", error) }
            }
            sessionManager.signOut(profile, forgetServer)
            _state.update {
                it.copy(
                    loading = false,
                    statusMessage = if (forgetServer) "Enter your Frigate connection" else "Signed out",
                    errorMessage = null,
                    savedProfile = if (forgetServer) null else profile,
                    activeProfile = null,
                    snapshot = null,
                    recentActivityLoaded = false,
                    playback = null,
                    cameraGroupView = null,
                    activeCameraName = null,
                    review = ReviewBrowserState(),
                    history = HistoryBrowserState(),
                    activitySearch = ActivitySearchState(),
                    information = InformationUiState(),
                    exports = ExportsUiState(),
                    ptz = PtzUiState(),
                    modes = ModesUiState(),
                    liveActions = LiveActionsUiState(),
                    motionReview = MotionReviewUiState(),
                    health = HealthUiState(),
                    briefing = BriefingUiState(),
                    savedSessionRecoveryAvailable = false,
                )
            }
            clearImageCaches()
            historyRangeCache.clear()
            cameraImageFailureCounts.clear()
        }
    }

    fun enableTvAlertsAfterPermission() {
        if (_state.value.tvAlerts.busy) return
        _state.update {
            it.copy(tvAlerts = it.tvAlerts.copy(busy = true, statusMessage = null, errorMessage = null))
        }
        viewModelScope.launch {
            val result = container.enableTvAlertsFromVisibleUserAction()
            _state.update { current ->
                val message = when (result) {
                    TvAlertActivationResult.STARTED -> "TV alerts are on"
                    TvAlertActivationResult.NOTIFICATION_PERMISSION_REQUIRED ->
                        "Allow notifications to turn on TV alerts"
                    TvAlertActivationResult.ON_SCREEN_PERMISSION_REQUIRED ->
                        "Allow on-screen alerts in Android settings"
                    TvAlertActivationResult.NOTIFICATIONS_BLOCKED ->
                        "Notifications are blocked in Android settings"
                    TvAlertActivationResult.START_REJECTED ->
                        "Android could not start TV alerts"
                    TvAlertActivationResult.CONFIGURATION_UNAVAILABLE ->
                        "TV alert settings are unavailable"
                }
                current.copy(
                    tvAlerts = current.tvAlerts.copy(
                        busy = false,
                        statusMessage = message.takeIf { result == TvAlertActivationResult.STARTED },
                        errorMessage = message.takeUnless { result == TvAlertActivationResult.STARTED },
                    ),
                )
            }
        }
    }

    fun notificationPermissionDenied() {
        _state.update {
            it.copy(
                tvAlerts = it.tvAlerts.copy(
                    busy = false,
                    statusMessage = null,
                    errorMessage = "Allow notifications to use TV alerts",
                ),
            )
        }
    }

    fun notificationDeliveryBlocked() {
        _state.update {
            it.copy(
                tvAlerts = it.tvAlerts.copy(
                    busy = false,
                    deliveryStatus = TvAlertDeliveryStatus.BLOCKED,
                    statusMessage = null,
                    errorMessage = "Allow Opah notifications in Android settings",
                ),
            )
        }
    }

    fun onScreenAlertPermissionDenied() {
        _state.update {
            it.copy(
                tvAlerts = it.tvAlerts.copy(
                    busy = false,
                    overlayPermissionGranted = false,
                    statusMessage = null,
                    errorMessage = "Allow on-screen alerts in Android settings",
                ),
            )
        }
    }

    fun onScreenAlertSettingsUnavailable() {
        _state.update {
            it.copy(
                tvAlerts = it.tvAlerts.copy(
                    busy = false,
                    overlayPermissionGranted = false,
                    statusMessage = null,
                    errorMessage = "Android could not open display settings",
                ),
            )
        }
    }

    fun refreshTvAlertDeliveryStatus() {
        val deliveryStatus = container.tvAlertDeliveryStatus()
        val overlayPermissionGranted = container.tvAlertOverlayPermissionGranted()
        _state.update { current ->
            current.copy(
                tvAlerts = current.tvAlerts.copy(
                    deliveryStatus = deliveryStatus,
                    overlayPermissionGranted = overlayPermissionGranted,
                ),
            )
        }
    }

    fun disableTvAlerts() {
        if (_state.value.tvAlerts.busy) return
        _state.update { it.copy(tvAlerts = it.tvAlerts.copy(busy = true, errorMessage = null)) }
        viewModelScope.launch {
            val disabled = container.disableTvAlerts()
            _state.update {
                it.copy(
                    tvAlerts = it.tvAlerts.copy(
                        busy = false,
                        statusMessage = "TV alerts are off".takeIf { disabled },
                        errorMessage = "Some TV alert data could not be cleared".takeUnless { disabled },
                    ),
                )
            }
        }
    }

    fun setTvAlertMode(mode: AlertMode) {
        if (mode == AlertMode.OFF) {
            disableTvAlerts()
            return
        }
        mutateTvAlertPolicy { it.copy(mode = mode) }
    }

    fun setTvAlertPrivacy(privacy: NotificationPrivacy) {
        if (privacy == NotificationPrivacy.NEVER_NOTIFY) return
        if (!beginTvAlertMutation()) return
        val currentPrivacy = _state.value.privacy
        viewModelScope.launch {
            try {
                privacyRepository.setDisclosurePolicy(
                    ownerRecognition = currentPrivacy.ownerRecognitionDisclosure,
                    guestRecognition = currentPrivacy.guestRecognitionDisclosure,
                    globalNotification = privacy.toPrivacyDisclosure(),
                    guestNotification = currentPrivacy.guestNotificationDisclosure,
                )
                mutateTvAlertPolicyNow { it.copy(notificationPrivacy = privacy) }
            } finally {
                finishTvAlertMutation()
            }
        }
    }

    fun setTvAlertSignificantMotion(enabled: Boolean) = mutateTvAlertPolicy { policy ->
        policy.copy(
            significantMotionEnabled = enabled,
            customSeverities = if (enabled) {
                policy.customSeverities + AwarenessReviewSeverity.SIGNIFICANT_MOTION
            } else {
                policy.customSeverities - AwarenessReviewSeverity.SIGNIFICANT_MOTION
            },
        )
    }

    fun setTvAlertCustomSeverity(severity: AwarenessReviewSeverity, enabled: Boolean) {
        if (severity == AwarenessReviewSeverity.UNKNOWN ||
            severity == AwarenessReviewSeverity.SIGNIFICANT_MOTION
        ) return
        mutateTvAlertPolicy { policy ->
            policy.copy(
                customSeverities = if (enabled) {
                    policy.customSeverities + severity
                } else {
                    policy.customSeverities - severity
                },
            )
        }
    }

    fun setTvAlertAllCameras() = mutateTvAlertPolicy { it.copy(cameraIds = emptySet()) }

    fun setTvAlertCamera(cameraId: String, enabled: Boolean) = mutateTvAlertPolicy { policy ->
        policy.copy(cameraIds = policy.cameraIds.withSelection(cameraId, enabled))
    }

    fun clearTvAlertLabels() = mutateTvAlertPolicy { it.copy(labels = emptySet()) }

    fun setTvAlertLabel(label: String, enabled: Boolean) = mutateTvAlertPolicy { policy ->
        policy.copy(labels = policy.labels.withSelection(label, enabled))
    }

    fun clearTvAlertZones() = mutateTvAlertPolicy { it.copy(zones = emptySet()) }

    fun setTvAlertZone(zone: String, enabled: Boolean) = mutateTvAlertPolicy { policy ->
        policy.copy(zones = policy.zones.withSelection(zone, enabled))
    }

    fun clearTvAlertIdentities() = mutateTvAlertPolicy { it.copy(subLabels = emptySet()) }

    fun setTvAlertIdentity(identity: String, enabled: Boolean) = mutateTvAlertPolicy { policy ->
        policy.copy(subLabels = policy.subLabels.withSelection(identity, enabled))
    }

    fun clearTvAlertPlates() = mutateTvAlertPolicy { it.copy(plateLabels = emptySet()) }

    fun setTvAlertPlate(plate: String, enabled: Boolean) = mutateTvAlertPolicy { policy ->
        policy.copy(plateLabels = policy.plateLabels.withSelection(plate, enabled))
    }

    fun setTvAlertSchedulePreset(preset: TvAlertSchedulePreset) {
        mutateTvAlertPolicy { policy ->
            policy.copy(
                schedules = when (preset) {
                    TvAlertSchedulePreset.ALWAYS -> emptyList()
                    TvAlertSchedulePreset.CUSTOM -> policy.schedules.ifEmpty {
                        listOf(
                            AlertScheduleWindow(
                                AlertDayOfWeek.entries.toSet(),
                                DEFAULT_TV_ALERT_START_MINUTE,
                                DEFAULT_TV_ALERT_END_MINUTE,
                            ),
                        )
                    }
                },
            )
        }
    }

    fun setTvAlertScheduleWindow(startMinute: Int, endMinute: Int) {
        if (startMinute !in 0..1_439 || endMinute !in 0..1_439 || startMinute == endMinute) return
        if (_state.value.tvAlerts.busy) return
        _state.update {
            it.copy(
                tvAlerts = it.tvAlerts.copy(
                    schedulePreset = TvAlertSchedulePreset.CUSTOM,
                    scheduleStartMinute = startMinute,
                    scheduleEndMinute = endMinute,
                ),
            )
        }
        mutateTvAlertPolicy { policy ->
            policy.copy(
                schedules = listOf(
                    AlertScheduleWindow(AlertDayOfWeek.entries.toSet(), startMinute, endMinute),
                ),
            )
        }
    }

    fun clearTvAlertModes() = mutateTvAlertPolicy { it.copy(frigateModes = emptySet()) }

    fun setTvAlertModeFilter(mode: String, enabled: Boolean) = mutateTvAlertPolicy { policy ->
        policy.copy(frigateModes = policy.frigateModes.withSelection(mode, enabled))
    }

    fun setTvAlertMinimumThreatLevel(level: Int?) {
        if (level !in setOf(null, 1, 2)) return
        mutateTvAlertPolicy { policy -> policy.copy(minimumThreatLevel = level) }
    }

    fun snoozeTvAlerts(minutes: Int) {
        if (minutes !in setOf(15, 60)) return
        if (!beginTvAlertMutation()) return
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis().coerceAtLeast(0L)
                val duration = minutes * 60_000L
                val expires = if (now > Long.MAX_VALUE - duration) Long.MAX_VALUE else now + duration
                saveTvAlertSnooze(
                    AlertSnooze(
                        scope = AlertSnoozeScope.ALL,
                        expiresAtEpochMillis = expires,
                    ),
                    if (minutes == 15) "Snoozed for 15 minutes" else "Snoozed for 1 hour",
                )
            } finally {
                finishTvAlertMutation()
            }
        }
    }

    fun snoozeTvAlertsUntilTomorrow() {
        if (!beginTvAlertMutation()) return
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis().coerceAtLeast(0L)
                saveTvAlertSnooze(
                    AlertSnooze(
                        scope = AlertSnoozeScope.ALL,
                        expiresAtEpochMillis = nextLocalDayStartEpochMillis(now, TimeZone.getDefault()),
                    ),
                    "Snoozed until tomorrow",
                )
            } finally {
                finishTvAlertMutation()
            }
        }
    }

    fun snoozeTvAlertsUntilModeChanges() {
        val mode = _state.value.modes.activeMode ?: return
        if (!beginTvAlertMutation()) return
        viewModelScope.launch {
            try {
                saveTvAlertSnooze(
                    AlertSnooze(
                        scope = AlertSnoozeScope.ALL,
                        untilModeChangesFrom = mode,
                    ),
                    "Snoozed until Frigate Mode changes",
                )
            } finally {
                finishTvAlertMutation()
            }
        }
    }

    fun clearTvAlertSnoozes() {
        if (!beginTvAlertMutation()) return
        viewModelScope.launch {
            try {
                val result = container.clearTvAlertSnoozes()
                _state.update {
                    it.copy(
                        tvAlerts = it.tvAlerts.copy(
                            statusMessage = "Snooze ended".takeUnless {
                                result is AlertConfigurationMutationResult.Rejected ||
                                    result is AlertConfigurationMutationResult.Unavailable
                            },
                            errorMessage = "The snooze could not be ended".takeIf {
                                result is AlertConfigurationMutationResult.Rejected ||
                                    result is AlertConfigurationMutationResult.Unavailable
                            },
                        ),
                    )
                }
            } finally {
                finishTvAlertMutation()
            }
        }
    }

    private suspend fun saveTvAlertSnooze(snooze: AlertSnooze, successMessage: String) {
        val cleared = container.clearTvAlertSnoozes()
        val result = if (
            cleared is AlertConfigurationMutationResult.Rejected ||
            cleared is AlertConfigurationMutationResult.Unavailable
        ) cleared else container.addTvAlertSnooze(snooze)
        val failed = result is AlertConfigurationMutationResult.Rejected ||
            result is AlertConfigurationMutationResult.Unavailable
        _state.update {
            it.copy(
                tvAlerts = it.tvAlerts.copy(
                    statusMessage = successMessage.takeUnless { failed },
                    errorMessage = "The snooze could not be saved".takeIf { failed },
                ),
            )
        }
    }

    fun sendLocalTestAlert(includeImage: Boolean) {
        if (_state.value.tvAlerts.busy) return
        _state.update {
            it.copy(tvAlerts = it.tvAlerts.copy(busy = true, statusMessage = null, errorMessage = null))
        }
        viewModelScope.launch {
            val posted = container.postLocalTestAlert(includeImage)
            val deliveryStatus = container.tvAlertDeliveryStatus()
            _state.update {
                it.copy(
                    tvAlerts = it.tvAlerts.copy(
                        busy = false,
                        deliveryStatus = deliveryStatus,
                        statusMessage =
                            "Test alert sent"
                                .takeIf { posted },
                        errorMessage = if (posted) null else when (deliveryStatus) {
                            TvAlertDeliveryStatus.NOTIFICATION_PERMISSION_REQUIRED ->
                                "Allow notifications to use TV alerts"
                            TvAlertDeliveryStatus.PARTIALLY_BLOCKED,
                            TvAlertDeliveryStatus.BLOCKED,
                            -> "Notifications are blocked in Android settings"
                            TvAlertDeliveryStatus.AVAILABLE -> "The test alert could not be shown"
                        },
                    ),
                )
            }
        }
    }

    fun prepareLocalTestAlert() {
        _state.update {
            it.copy(
                tvAlerts = it.tvAlerts.copy(
                    busy = false,
                    statusMessage = null,
                    errorMessage = null,
                ),
            )
        }
    }

    fun setTvAlertOverlayVerticalPosition(position: TvAlertOverlayVerticalPosition) =
        updateTvAlertOverlaySettings { it.copy(verticalPosition = position) }

    fun setTvAlertOverlayHorizontalPosition(position: TvAlertOverlayHorizontalPosition) =
        updateTvAlertOverlaySettings { it.copy(horizontalPosition = position) }

    fun setTvAlertOverlayImageSize(size: TvAlertOverlayImageSize) =
        updateTvAlertOverlaySettings { it.copy(imageSize = size) }

    fun setTvAlertOverlayDisplayDurationSeconds(seconds: Int) {
        if (seconds !in TV_ALERT_OVERLAY_MIN_DISPLAY_SECONDS..TV_ALERT_OVERLAY_MAX_DISPLAY_SECONDS) {
            return
        }
        updateTvAlertOverlaySettings { it.copy(displayDurationSeconds = seconds) }
    }

    private fun updateTvAlertOverlaySettings(
        transform: (TvAlertOverlaySettings) -> TvAlertOverlaySettings,
    ) {
        val updated = container.setTvAlertOverlaySettings(
            transform(_state.value.tvAlerts.overlaySettings),
        )
        _state.update { current ->
            current.copy(
                tvAlerts = current.tvAlerts.copy(
                    overlaySettings = updated,
                    statusMessage = "Alert appearance saved",
                    errorMessage = null,
                ),
            )
        }
    }

    private fun mutateTvAlertPolicy(transform: (app.opah.tv.notifications.AlertPolicy) ->
        app.opah.tv.notifications.AlertPolicy
    ) {
        if (!beginTvAlertMutation()) return
        viewModelScope.launch {
            try {
                mutateTvAlertPolicyNow(transform)
            } finally {
                finishTvAlertMutation()
            }
        }
    }

    private suspend fun mutateTvAlertPolicyNow(
        transform: (app.opah.tv.notifications.AlertPolicy) -> app.opah.tv.notifications.AlertPolicy,
    ) {
        val ready = alertConfigurationRepository.state.value as? AlertConfigurationState.Ready
            ?: return
        when (container.setTvAlertPolicy(transform(ready.configuration.policy))) {
            is AlertConfigurationMutationResult.Updated,
            is AlertConfigurationMutationResult.Unchanged,
            -> Unit
            AlertConfigurationMutationResult.Rejected,
            AlertConfigurationMutationResult.Unavailable,
            -> _state.update {
                it.copy(tvAlerts = it.tvAlerts.copy(errorMessage = "TV alert settings could not be saved"))
            }
        }
    }

    private fun beginTvAlertMutation(): Boolean {
        if (_state.value.tvAlerts.busy) return false
        _state.update {
            it.copy(
                tvAlerts = it.tvAlerts.copy(
                    busy = true,
                    statusMessage = null,
                    errorMessage = null,
                ),
            )
        }
        return true
    }

    private fun finishTvAlertMutation() {
        _state.update { it.copy(tvAlerts = it.tvAlerts.copy(busy = false)) }
    }

    fun playAutomatic(camera: Camera) {
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    playback = DocumentationSupport.data.livePlayback(camera),
                    activeCameraName = camera.name,
                    errorMessage = null,
                )
            }
            return
        }
        if (issuePrivacyGrant(PrivacySurface.LIVE_PLAYBACK, PrivacyTarget.Camera(camera.name)) == null) return
        rememberLastViewedTarget(StartupTarget(StartupTargetKind.CAMERA, camera.name))
        val current = _state.value
        val profile = current.activeProfile ?: return
        val codecs = current.device?.codecs.orEmpty()
        streamSelector.select(camera, codecs, current.settings.streamPreference)
            .onSuccess { playStream(profile, camera, it.option, it.reason) }
            .onFailure { error ->
                _state.update { it.copy(errorMessage = error.message ?: "No compatible stream") }
            }
    }

    /** Explicit one-session alternative; normal clicks and saved settings remain unchanged. */
    fun playUsingHttps(camera: Camera) {
        val current = _state.value
        val profile = current.activeProfile ?: return
        if (BuildConfig.DOCUMENTATION_MODE ||
            !app.opah.tv.playback.canOfferHttpsLive(profile.apiBaseUrl)) return
        if (issuePrivacyGrant(PrivacySurface.LIVE_PLAYBACK, PrivacyTarget.Camera(camera.name)) == null) return
        streamSelector.select(camera, current.device?.codecs.orEmpty(), current.settings.streamPreference)
            .onSuccess { selected ->
                val live = prepareAuthenticatedHttpLive(camera.name, selected.option.streamName)
                if (live == null || !live.isAuthorized()) {
                    _state.update { it.copy(errorMessage = "HTTPS stream unavailable or access denied") }
                    return@onSuccess
                }
                _state.update { it.copy(
                    playback = PlaybackRequest(
                        title = camera.displayName, uri = "", kind = PlaybackKind.LIVE,
                        cameraName = camera.name, liveStreamName = selected.option.streamName,
                        httpsLiveRequestId = java.util.UUID.randomUUID().toString(),
                    ),
                    activeCameraName = camera.name, errorMessage = null,
                ) }
            }
            .onFailure { _state.update { it.copy(errorMessage = "No compatible HTTPS stream") } }
    }

    fun playInstantRewind(cameraName: String) {
        val current = _state.value
        val camera = current.snapshot?.cameras?.firstOrNull { it.name == cameraName } ?: return
        val end = System.currentTimeMillis() / 1_000.0
        val start = end - INSTANT_REWIND_SECONDS
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    playback = DocumentationSupport.data.historyPlayback(camera, start, end).copy(
                        detail = "30 seconds earlier",
                        recordingStartTime = start,
                        recordingEndTime = end,
                        returnToLiveCameraName = camera.name,
                    ),
                    activeCameraName = null,
                    errorMessage = null,
                )
            }
            return
        }
        if (issuePrivacyGrant(PrivacySurface.HISTORY, PrivacyTarget.Camera(cameraName)) == null) return
        val profile = current.activeProfile ?: return
        _state.update {
            it.copy(
                playback = PlaybackRequest(
                    title = camera.displayName,
                    uri = repository.recordingPlaybackUrl(profile, camera.name, start, end),
                    kind = PlaybackKind.RECORDED,
                    cameraName = camera.name,
                    detail = "30 seconds earlier",
                    recordingStartTime = start,
                    recordingEndTime = end,
                    returnToLiveCameraName = camera.name,
                ),
                activeCameraName = null,
                errorMessage = null,
            )
        }
    }

    fun captureInstantSnapshot(
        cameraName: String,
        share: (Bitmap, String) -> Boolean,
    ) {
        val current = _state.value
        val snapshot = current.snapshot ?: return
        if (
            current.liveActions.snapshotCapturing ||
            !snapshot.capabilities.supports(FrigateFeature.INSTANT_SNAPSHOT) ||
            snapshot.cameras.none { it.name == cameraName }
        ) return
        val displayName = snapshot.authorizedCameraNames[cameraName] ?: cameraName
        val privacyGrant = if (BuildConfig.DOCUMENTATION_MODE) {
            null
        } else {
            issuePrivacyGrant(PrivacySurface.LIVE_PLAYBACK, PrivacyTarget.Camera(cameraName)) ?: return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    liveActions = it.liveActions.copy(
                        snapshotCapturing = true,
                        message = null,
                        errorMessage = null,
                    ),
                )
            }
            val result = if (BuildConfig.DOCUMENTATION_MODE) {
                runCatching {
                    documentationImages.camera(cameraName)
                        ?.bitmap
                        ?: error("Snapshot unavailable")
                }
            } else {
                val profile = current.activeProfile
                    ?: return@launch _state.update {
                        it.copy(liveActions = it.liveActions.copy(snapshotCapturing = false))
                    }
                cameraImageRepository.refresh(profile, cameraName, 1080, force = true).map(CameraImage::bitmap)
            }
            result.onSuccess { bitmap ->
                if (privacyGrant != null && !revalidatePrivacyGrant(privacyGrant)) {
                    _state.update {
                        it.copy(liveActions = it.liveActions.copy(snapshotCapturing = false))
                    }
                    return@onSuccess
                }
                val opened = share(bitmap, displayName)
                _state.update {
                    it.copy(
                        liveActions = it.liveActions.copy(
                            snapshotCapturing = false,
                            message = if (opened) {
                                "Snapshot ready to share • it is not saved as a clip"
                            } else {
                                "Snapshot captured, but no sharing app is available"
                            },
                        ),
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        liveActions = it.liveActions.copy(
                            snapshotCapturing = false,
                            errorMessage = error.toOpahFailure().userMessage,
                        ),
                    )
                }
            }
        }
    }

    fun startOnDemandRecording(cameraName: String) {
        val current = _state.value
        val profile = current.activeProfile ?: return
        val snapshot = current.snapshot ?: return
        if (
            current.liveActions.recordingBusy ||
            current.liveActions.recordingEventId != null ||
            !snapshot.capabilities.supports(FrigateFeature.ON_DEMAND_RECORDING)
        ) return
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    liveActions = it.liveActions.copy(
                        recordingEventId = DocumentationSupport.data.recordingEventId(),
                        recordingCameraName = cameraName,
                        message = "Recording started • stops automatically after 5 minutes",
                        errorMessage = null,
                    ),
                )
            }
            return
        }
        if (
            issuePrivacyGrant(PrivacySurface.LIVE_PLAYBACK, PrivacyTarget.Camera(cameraName)) == null ||
            issuePrivacyGrant(
                PrivacySurface.ACTION,
                PrivacyTarget.LocalAction(app.opah.tv.privacy.PrivacyLocalAction.ADMINISTRATIVE_CHANGE),
            ) == null
        ) return
        viewModelScope.launch {
            _state.update {
                it.copy(liveActions = it.liveActions.copy(recordingBusy = true, message = null, errorMessage = null))
            }
            runCatching {
                operationsRepository.startOnDemandRecording(
                    profile,
                    snapshot.user,
                    snapshot.frigateVersion,
                    cameraName,
                    ON_DEMAND_SAFETY_DURATION_SECONDS,
                )
            }.onSuccess { started ->
                _state.update {
                    it.copy(
                        liveActions = it.liveActions.copy(
                            recordingEventId = started.eventId,
                            recordingCameraName = cameraName,
                            recordingBusy = false,
                            message = "Recording started • stops automatically after 5 minutes",
                        ),
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        liveActions = it.liveActions.copy(
                            recordingBusy = false,
                            errorMessage = error.toOpahFailure().userMessage,
                        ),
                    )
                }
            }
        }
    }

    fun stopOnDemandRecording() {
        val current = _state.value
        val eventId = current.liveActions.recordingEventId ?: return
        val profile = current.activeProfile ?: return
        val snapshot = current.snapshot ?: return
        if (current.liveActions.recordingBusy) return
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    liveActions = LiveActionsUiState(message = "Recording stopped • footage will appear in Activity"),
                )
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(liveActions = it.liveActions.copy(recordingBusy = true)) }
            runCatching {
                operationsRepository.stopOnDemandRecording(
                    profile,
                    snapshot.user,
                    snapshot.frigateVersion,
                    eventId,
                )
            }.onSuccess {
                _state.update {
                    it.copy(liveActions = LiveActionsUiState(message = "Recording stopped • footage will appear in Activity"))
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        liveActions = it.liveActions.copy(
                            recordingBusy = false,
                            errorMessage = error.toOpahFailure().userMessage,
                        ),
                    )
                }
            }
        }
    }

    fun clearLiveActionMessage() {
        _state.update { it.copy(liveActions = it.liveActions.copy(message = null, errorMessage = null)) }
    }

    fun openPtzControls(camera: Camera) {
        val current = _state.value
        val info = current.snapshot?.ptzCameras?.get(camera.name) ?: return
        if (!info.hasControls) return
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    ptz = PtzUiState(
                        cameraName = camera.name,
                        connection = PtzConnectionState(PtzConnectionStatus.CONNECTED),
                    ),
                )
            }
            return
        }
        if (
            issuePrivacyGrant(PrivacySurface.LIVE_PLAYBACK, PrivacyTarget.Camera(camera.name)) == null ||
            issuePrivacyGrant(
                PrivacySurface.ACTION,
                PrivacyTarget.LocalAction(app.opah.tv.privacy.PrivacyLocalAction.ADMINISTRATIVE_CHANGE),
            ) == null
        ) return
        val profile = current.activeProfile ?: return
        _state.update { it.copy(ptz = PtzUiState(cameraName = camera.name)) }
        ptzWebSocketClient.connect(profile)
    }

    fun retryPtzControls() {
        val current = _state.value
        val cameraName = current.ptz.cameraName ?: return
        if (current.snapshot?.ptzCameras?.get(cameraName)?.hasControls != true) return
        if (
            !BuildConfig.DOCUMENTATION_MODE && (
                issuePrivacyGrant(PrivacySurface.LIVE_PLAYBACK, PrivacyTarget.Camera(cameraName)) == null ||
                    issuePrivacyGrant(
                        PrivacySurface.ACTION,
                        PrivacyTarget.LocalAction(
                            app.opah.tv.privacy.PrivacyLocalAction.ADMINISTRATIVE_CHANGE,
                        ),
                    ) == null
                )
        ) return
        val profile = current.activeProfile ?: return
        _state.update { it.copy(ptz = it.ptz.copy(errorMessage = null)) }
        ptzWebSocketClient.connect(profile)
    }

    fun sendPtzCommand(command: PtzCommand) {
        val current = _state.value
        val cameraName = current.ptz.cameraName ?: return
        val info = current.snapshot?.ptzCameras?.get(cameraName) ?: return
        if (!info.supports(command)) return
        if (BuildConfig.DOCUMENTATION_MODE) return
        if (
            issuePrivacyGrant(PrivacySurface.LIVE_PLAYBACK, PrivacyTarget.Camera(cameraName)) == null ||
            issuePrivacyGrant(
                PrivacySurface.ACTION,
                PrivacyTarget.LocalAction(app.opah.tv.privacy.PrivacyLocalAction.ADMINISTRATIVE_CHANGE),
            ) == null
        ) {
            closePtzControls()
            return
        }
        if (!ptzWebSocketClient.send(cameraName, command)) {
            _state.update {
                it.copy(ptz = it.ptz.copy(errorMessage = "Camera controls are still connecting"))
            }
        } else if (current.ptz.errorMessage != null) {
            _state.update { it.copy(ptz = it.ptz.copy(errorMessage = null)) }
        }
    }

    fun closePtzControls() {
        val current = _state.value
        val cameraName = current.ptz.cameraName
        if (cameraName != null && current.ptz.connection.status == PtzConnectionStatus.CONNECTED) {
            ptzWebSocketClient.send(cameraName, PtzCommand.Stop)
        }
        ptzWebSocketClient.disconnect()
        _state.update { it.copy(ptz = PtzUiState()) }
    }

    fun openCameraByName(cameraName: String) {
        pendingCameraName = cameraName
        openPendingCamera()
    }

    fun openTvAlert(profileKey: String, reviewId: String, actionNonce: String) {
        if (BuildConfig.DOCUMENTATION_MODE) return
        viewModelScope.launch {
            var attemptsRemaining = TV_ALERT_OPEN_RETRY_ATTEMPTS
            var resolution: TvAlertOpenResolution
            do {
                resolution = container.resolveTvAlertOpen(profileKey, reviewId, actionNonce)
                if (resolution != TvAlertOpenResolution.RetryLater) break
                attemptsRemaining -= 1
                if (attemptsRemaining > 0) delay(TV_ALERT_OPEN_RETRY_DELAY_MILLIS)
            } while (attemptsRemaining > 0)

            when (resolution) {
                is TvAlertOpenResolution.LiveCamera -> openCameraByName(resolution.cameraId)
                is TvAlertOpenResolution.RecordedReview -> {
                    val item = reviewPlaybackItems(_state.value)
                        .firstOrNull { it.id == resolution.reviewId }
                    if (item?.recordingAvailable == true) {
                        playReview(item)
                    } else {
                        showActivityForTvAlert(expired = item?.recordingAvailable == false)
                    }
                }
                is TvAlertOpenResolution.Activity -> showActivityForTvAlert(resolution.expired)
                TvAlertOpenResolution.PrivacyBlocked -> showTvAlertPrivacyGate(
                    PendingTvAlertOpen(profileKey, reviewId, actionNonce),
                )
                TvAlertOpenResolution.RetryLater,
                TvAlertOpenResolution.Unavailable,
                -> _state.update { it.copy(errorMessage = "This alert is no longer available") }
            }
        }
    }

    fun unlockTvAlert(pin: CharArray?) {
        if (_state.value.privacy.busy) {
            pin?.fill('\u0000')
            return
        }
        val pending = pendingTvAlertOpen
        if (pending == null) {
            pin?.fill('\u0000')
            cancelTvAlertPrivacyGate()
            return
        }
        updatePrivacyBusy()
        viewModelScope.launch {
            val privacy = _state.value.privacy
            var unlockedScopes = privacy.unlockedScopes
            if (privacy.pinConfigured) {
                val submitted = pin ?: run {
                    publishPinUnlockFailure(PinUnlockResult.Unavailable)
                    return@launch
                }
                when (val verified = pinCredentialService.verify(submitted)) {
                    is PinUnlockResult.Unlocked -> unlockedScopes = verified.proof.scopes
                    else -> {
                        publishPinUnlockFailure(verified)
                        return@launch
                    }
                }
            } else {
                pin?.fill('\u0000')
            }

            _state.update {
                it.copy(
                    privacy = it.privacy.copy(
                        busy = false,
                        unlockedScopes = unlockedScopes,
                        pinSelectionsUnlocked = privacy.pinSelectionsUnlocked || privacy.pinConfigured,
                        statusMessage = "Unlocked",
                        errorMessage = null,
                    ),
                )
            }

            pendingTvAlertOpen = null
            _state.update {
                it.copy(
                    tvAlertPrivacyGateOpen = false,
                    privacy = it.privacy.copy(busy = false, errorMessage = null),
                )
            }
            openTvAlert(pending.profileKey, pending.reviewId, pending.actionNonce)
        }
    }

    fun cancelTvAlertPrivacyGate() {
        pendingTvAlertOpen = null
        _state.update {
            it.copy(
                tvAlertPrivacyGateOpen = false,
                privacy = it.privacy.copy(errorMessage = null),
            )
        }
    }

    fun consumeTvAlertNavigation(requestId: Long) {
        _state.update { current ->
            current.copy(
                tvAlertNavigation = current.tvAlertNavigation?.takeUnless {
                    it.requestId == requestId
                },
            )
        }
    }

    fun openLocalTestAlertSettings() {
        closePtzControls()
        closePlayback()
        tvAlertNavigationRequestId = tvAlertNavigationRequestId.nextNavigationRequestId()
        _state.update {
            it.copy(
                cameraGroupView = null,
                tvAlertNavigation = TvAlertNavigationUiState(
                    requestId = tvAlertNavigationRequestId,
                    destination = TvAlertNavigationDestination.TV_ALERT_SETTINGS,
                ),
                errorMessage = null,
            )
        }
    }

    private fun showActivityForTvAlert(expired: Boolean) {
        closePtzControls()
        closePlayback()
        tvAlertNavigationRequestId = tvAlertNavigationRequestId.nextNavigationRequestId()
        _state.update {
            it.copy(
                cameraGroupView = null,
                tvAlertNavigation = TvAlertNavigationUiState(
                    requestId = tvAlertNavigationRequestId,
                    destination = TvAlertNavigationDestination.ACTIVITY,
                    expired = expired,
                ),
                errorMessage = if (expired) "That activity is no longer available" else null,
            )
        }
    }

    private fun showTvAlertPrivacyGate(pending: PendingTvAlertOpen) {
        pendingTvAlertOpen = pending
        closePtzControls()
        closePlayback()
        _state.update {
            it.copy(
                cameraGroupView = null,
                tvAlertPrivacyGateOpen = true,
                errorMessage = null,
                privacy = it.privacy.copy(errorMessage = null),
            )
        }
    }

    fun playStream(camera: Camera, option: LiveStreamOption) {
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    playback = DocumentationSupport.data.livePlayback(camera).copy(
                        detail = "${option.label} • Explicit stream selection",
                    ),
                    activeCameraName = camera.name,
                    errorMessage = null,
                )
            }
            return
        }
        if (issuePrivacyGrant(PrivacySurface.LIVE_PLAYBACK, PrivacyTarget.Camera(camera.name)) == null) return
        val profile = _state.value.activeProfile ?: return
        playStream(profile, camera, option, "Explicit Frigate stream selection")
    }

    fun playBirdseye() {
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    playback = DocumentationSupport.data.birdseyePlayback(),
                    activeCameraName = null,
                    errorMessage = null,
                )
            }
            return
        }
        val current = _state.value
        val profile = current.activeProfile ?: return
        val snapshot = current.snapshot ?: return
        val allCameras = snapshot.user.allowedCameras
        val privacyGrant = issuePrivacyGrant(
            PrivacySurface.LIVE_PLAYBACK,
            PrivacyTarget.CameraCollection("birdseye", allCameras),
        ) ?: return
        if (!privacyGrant.grant.visibleCameraIds.containsAll(allCameras)) return
        rememberLastViewedTarget(StartupTarget(StartupTargetKind.BIRDSEYE))
        val birdseye = snapshot.birdseye
        if (!birdseye.playable) {
            _state.update {
                it.copy(errorMessage = "Frigate Birdseye is not ready for live playback")
            }
            return
        }
        val streamName = requireNotNull(birdseye.streamName)
        runCatching { StreamUriFactory.rtsp(profile, streamName) }
            .onSuccess { uri ->
                _state.update {
                    it.copy(
                        playback = PlaybackRequest(
                            title = "Birdseye",
                            uri = uri,
                            kind = PlaybackKind.LIVE,
                            detail = "Frigate composite • Single RTSP stream",
                            stretchPreferenceKey = BIRDSEYE_STRETCH_PREFERENCE_KEY,
                        ),
                        activeCameraName = null,
                        errorMessage = null,
                    )
                }
            }
            .onFailure { _state.update { state -> state.copy(errorMessage = it.message) } }
    }

    fun playReview(
        item: ReviewItem,
        useHomeActivityContext: Boolean = false,
        visibleActivityItemIds: List<String>? = null,
    ) {
        val current = if (visibleActivityItemIds != null) _state.value.projectForPrivacy() else _state.value
        if (visibleActivityItemIds != null && current.review.items.none { it.id == item.id }) return
        val context = reviewPlaybackContext(
            itemId = item.id,
            review = current.review,
            homeItems = current.snapshot?.recentReviewItems.orEmpty(),
            useHomeActivityContext = useHomeActivityContext,
            visibleActivityItemIds = visibleActivityItemIds,
        )
        openReviewPlayback(item, context.itemIds, context.queue)
    }

    fun playBriefingHighlights() {
        val entries = _state.value.briefing.summary?.entries.orEmpty()
        val allowed = visibleCameraNames(PrivacySurface.ACTIVITY)
        val visible = entries.filter { it.item.camera in allowed }
        val first = visible.firstOrNull() ?: return
        val ids = visible.map { it.item.id }
        _state.update {
            it.copy(briefing = it.briefing.copy(activeHighlightIds = ids.toSet(), errorMessage = null))
        }
        openReviewPlayback(first.item, ids, queueContext = true, briefingHighlight = true)
    }

    fun openBriefingReviewQueue() {
        val allowed = visibleCameraNames(PrivacySurface.ACTIVITY)
        val items = _state.value.briefing.summary?.entries.orEmpty()
            .map(BriefingEntry::item)
            .filter { it.camera in allowed }
        val first = items.firstOrNull() ?: return
        cancelReviewLoads()
        _state.update { state ->
            state.copy(
                review = state.review.copy(
                    items = items,
                    loadedOnce = true,
                    loading = false,
                    loadingMore = false,
                    countsLoading = false,
                    hasMore = false,
                    nextBeforeBySeverity = emptyMap(),
                    queueItemIds = items.map(ReviewItem::id),
                    queueIndex = 0,
                    queueActive = true,
                    queueCompleted = false,
                    queueTitle = "Highlights",
                    selectedItemId = null,
                    errorMessage = null,
                ),
            )
        }
        selectReviewItem(first)
    }

    fun dismissBriefing() {
        val summary = _state.value.briefing.summary ?: return
        acknowledgeBriefing(
            entries = summary.entries,
            reason = BriefingAcknowledgementReason.DISMISSED,
            dismissalTargets = summary.dismissalTargets,
        )
    }

    fun onReviewPlaybackWatchThresholdReached(item: ReviewItem) {
        val current = _state.value
        val latestItem = reviewPlaybackItems(current).firstOrNull { it.id == item.id } ?: item
        if (!latestItem.hasBeenReviewed && current.settings.autoMarkReviewedAfterPlayback) {
            if (!current.review.hasPendingWrite()) {
                setReviewReviewed(latestItem, true)
            } else {
                // A pending Save must not discard the automatic watched acknowledgement.
                viewModelScope.launch {
                    while (_state.value.review.hasPendingWrite()) {
                        val waiting = _state.value
                        if (waiting.activeProfile != current.activeProfile || waiting.privacy.epoch != current.privacy.epoch) return@launch
                        _state.first { next ->
                            !next.review.hasPendingWrite() || next.activeProfile != current.activeProfile ||
                                next.privacy.epoch != current.privacy.epoch
                        }
                    }
                    val latest = _state.value
                    if (latest.activeProfile == current.activeProfile && latest.privacy.epoch == current.privacy.epoch &&
                        latest.settings.autoMarkReviewedAfterPlayback) {
                        val visibleItem = reviewPlaybackItems(latest).firstOrNull { it.id == item.id }
                        if (visibleItem != null && !visibleItem.hasBeenReviewed) setReviewReviewed(visibleItem, true)
                    }
                }
            }
        }
    }

    fun onReviewPlaybackCompleted(item: ReviewItem) {
        onReviewPlaybackWatchThresholdReached(item)
        val current = _state.value
        if (current.playback?.briefingHighlight == true && item.id in current.briefing.activeHighlightIds) {
            acknowledgeBriefing(
                entries = current.briefing.summary?.entries.orEmpty().filter { it.item.id == item.id },
                reason = BriefingAcknowledgementReason.HIGHLIGHT_COMPLETED,
                completedHighlightIds = setOf(item.id),
            )
        }
        // A playback request with activity context represents a queue, whether it
        // came from highlights or the ordinary Activity list. Finishing an item
        // should therefore continue to the next available recording automatically.
        if (current.playback?.activityContextItemIds?.isNotEmpty() == true) {
            advanceReviewActivity(acknowledgeBriefingSkip = false)
        }
    }

    fun playNextReviewActivity() = advanceReviewActivity(acknowledgeBriefingSkip = true)

    private fun advanceReviewActivity(acknowledgeBriefingSkip: Boolean) {
        val current = _state.value
        val request = current.playback ?: return
        val currentItemId = request.activityItemId ?: return
        if (request.briefingHighlight && acknowledgeBriefingSkip) {
            val currentEntry = current.briefing.summary?.entries.orEmpty()
                .firstOrNull { it.item.id == currentItemId }
            if (currentEntry != null) {
                acknowledgeBriefing(
                    entries = listOf(currentEntry),
                    reason = BriefingAcknowledgementReason.EXPLICIT_SKIP,
                )
            }
        }
        val contextItemIds = request.activityContextItemIds
        if (contextItemIds.isEmpty() || current.review.advancingPlayback) return
        val firstCandidate = nextReviewPlaybackItem(
            currentItemId = currentItemId,
            contextItemIds = contextItemIds,
            availableItems = reviewPlaybackItems(current),
        ) ?: return
        if (BuildConfig.DOCUMENTATION_MODE) {
            openReviewPlayback(
                firstCandidate,
                contextItemIds,
                request.activityQueueContext,
                request.briefingHighlight,
            )
            return
        }
        val profile = current.activeProfile ?: return
        reviewPlaybackNavigationJob?.cancel()
        _state.update {
            it.copy(
                review = it.review.copy(
                    advancingPlayback = true,
                    playbackNavigationMessage = null,
                ),
            )
        }
        reviewPlaybackNavigationJob = viewModelScope.launch {
            var candidate: ReviewItem? = firstCandidate
            while (candidate != null) {
                val availability = repository.reviewRecordingAvailable(profile, candidate)
                val error = availability.exceptionOrNull()
                if (error is AuthenticationExpiredException) {
                    handleConnectedFailure(error)
                    return@launch
                }
                if (error != null) {
                    logger.warning("Checking the next Activity recording failed", error)
                    _state.update {
                        it.copy(
                            review = it.review.copy(
                                advancingPlayback = false,
                                playbackNavigationMessage = "Couldn't open the next activity",
                            ),
                        )
                    }
                    return@launch
                }
                if (availability.getOrDefault(false)) {
                    openReviewPlayback(
                        candidate,
                        contextItemIds,
                        request.activityQueueContext,
                        request.briefingHighlight,
                    )
                    return@launch
                }
                val unavailableId = candidate.id
                _state.update { state ->
                    state.copy(
                        snapshot = state.snapshot?.copy(
                            recentReviewItems = state.snapshot.recentReviewItems.map { item ->
                                if (item.id == unavailableId) item.copy(recordingAvailable = false) else item
                            },
                        ),
                        review = state.review.copy(
                            items = state.review.items.map { item ->
                                if (item.id == unavailableId) item.copy(recordingAvailable = false) else item
                            },
                        ),
                    )
                }
                candidate = nextReviewPlaybackItem(
                    currentItemId = unavailableId,
                    contextItemIds = contextItemIds,
                    availableItems = reviewPlaybackItems(_state.value),
                )
            }
            _state.update {
                it.copy(
                    review = it.review.copy(
                        advancingPlayback = false,
                        playbackNavigationMessage = null,
                    ),
                )
            }
        }
    }

    fun closePlayback() {
        reviewPlaybackNavigationJob?.cancel()
        reviewPlaybackNavigationJob = null
        if (_state.value.playback?.activityItemId != null) reviewDetailJob?.cancel()
        _state.update { state ->
            val returningFromActivity = state.playback?.activityItemId != null
            val returningFromBriefing = state.playback?.briefingHighlight == true
            state.copy(
                playback = null,
                activeCameraName = null,
                review = if (returningFromActivity) {
                    state.review.afterActivityPlaybackClosed(requireNotNull(state.playback))
                } else {
                    state.review
                },
                briefing = if (returningFromBriefing) {
                    state.briefing.copy(activeHighlightIds = emptySet())
                } else {
                    state.briefing
                },
            )
        }
    }

    private fun openReviewPlayback(
        item: ReviewItem,
        contextItemIds: List<String>,
        queueContext: Boolean,
        briefingHighlight: Boolean = false,
    ) {
        if (
            !BuildConfig.DOCUMENTATION_MODE &&
            issuePrivacyGrant(
                PrivacySurface.ACTIVITY,
                PrivacyTarget.Review(item.id, item.camera, item.subLabels.isNotEmpty()),
            ) == null
        ) return
        val current = _state.value
        val request = if (BuildConfig.DOCUMENTATION_MODE) {
            DocumentationSupport.data.recordedPlayback(item).copy(
                activityContextItemIds = contextItemIds,
                activityQueueContext = queueContext,
                briefingHighlight = briefingHighlight,
                recordingStartTime = item.startTime,
                recordingEndTime = item.endTime,
            )
        } else {
            val profile = current.activeProfile ?: return
            val cameraName = current.snapshot?.cameras
                ?.firstOrNull { it.name == item.camera }
                ?.displayName
                ?: item.camera.replace('_', ' ').replaceFirstChar(Char::uppercase)
            val activityName = if (item.severity == ReviewSeverity.ALERT) "Alert" else "Activity"
            PlaybackRequest(
                title = "$activityName at $cameraName",
                uri = repository.reviewPlaybackUrl(profile, item),
                kind = PlaybackKind.RECORDED,
                cameraName = item.camera,
                detail = "Recording",
                activityItemId = item.id,
                activityContextItemIds = contextItemIds,
                activityQueueContext = queueContext,
                briefingHighlight = briefingHighlight,
                recordingStartTime = item.startTime,
                recordingEndTime = item.endTime,
            )
        }
        _state.update { state ->
            state.copy(
                playback = request,
                activeCameraName = null,
                errorMessage = null,
                review = state.review.copy(
                    selectedItemId = item.id.takeIf {
                        queueContext || state.review.selectedItemId != null
                    },
                    recordingState = ReviewRecordingState.AVAILABLE,
                    queueIndex = if (queueContext) {
                        state.review.queueItemIds.indexOf(item.id).takeIf { it >= 0 }
                            ?: state.review.queueIndex
                    } else {
                        state.review.queueIndex
                    },
                    playbackItem = item.copy(recordingAvailable = true),
                    advancingPlayback = false,
                    playbackNavigationMessage = null,
                ),
            )
        }
    }

    private fun reviewPlaybackItems(state: Phase0UiState): List<ReviewItem> =
        (listOfNotNull(state.review.playbackItem) +
            state.review.items +
            state.snapshot?.recentReviewItems.orEmpty() +
            state.briefing.summary?.entries.orEmpty().map(BriefingEntry::item))
            .distinctBy(ReviewItem::id)

    fun openCameraGroup(title: String, cameraNames: List<String>) {
        val current = _state.value
        val snapshot = current.snapshot ?: return
        val selectedNames = cameraNames.distinct()
        if (selectedNames.size !in MIN_CAMERAS_PER_GROUP_VIEW..MAX_CAMERAS_PER_GROUP_VIEW) {
            _state.update { it.copy(errorMessage = "Choose two to four different cameras") }
            return
        }
        if (!BuildConfig.DOCUMENTATION_MODE) {
            val grant = issuePrivacyGrant(
                PrivacySurface.VIEW,
                PrivacyTarget.CameraCollection("camera-group", selectedNames.toSet()),
            ) ?: return
            if (!grant.grant.visibleCameraIds.containsAll(selectedNames)) return
        }
        val cameras = selectedNames.mapNotNull { name -> snapshot.cameras.firstOrNull { it.name == name } }
        if (cameras.size != selectedNames.size) {
            _state.update { it.copy(errorMessage = "One of those cameras is not available") }
            return
        }
        val streams = runCatching {
            cameras.map { camera ->
                if (BuildConfig.DOCUMENTATION_MODE) {
                    CameraGroupStream(
                        camera = camera,
                        uri = "$DOCUMENTATION_URI_PREFIX${camera.name}",
                    )
                } else {
                    val profile = current.activeProfile ?: error("No active Frigate connection")
                    val selection = streamSelector.select(
                        camera,
                        current.device?.codecs.orEmpty(),
                        StreamPreference.LOW_BANDWIDTH,
                    ).getOrThrow()
                    CameraGroupStream(
                        camera = camera,
                        uri = StreamUriFactory.rtsp(profile, selection.option.streamName),
                        videoMimeType = selection.option.metadata?.videoCodec?.mimeType,
                    )
                }
            }
        }.getOrElse {
            _state.update { state -> state.copy(errorMessage = "Those cameras could not be opened together") }
            return
        }
        _state.update {
            it.copy(
                cameraGroupView = CameraGroupViewUiState(
                    title = title.trim().take(CAMERA_GROUP_VIEW_TITLE_LENGTH).ifEmpty { "Camera group" },
                    streams = streams,
                    decoderWarning = decoderCapacityMayBeInsufficient(streams, current.device),
                ),
                errorMessage = null,
            )
        }
    }

    fun closeCameraGroup() {
        _state.update { it.copy(cameraGroupView = null, playback = null, activeCameraName = null) }
    }

    fun startMonitorMode(preset: MonitorPreset = MonitorPreset.CALM) {
        if (_state.value.monitorMode != null) return
        val current = _state.value
        val group = current.cameraGroupView ?: return
        val cameras = group.streams.map(CameraGroupStream::camera).distinctBy(Camera::name)
        startMonitorMode(group.title, cameras, preset)
    }

    fun startMonitorModeForView(
        title: String,
        cameraNames: List<String>,
        preset: MonitorPreset = MonitorPreset.CALM,
    ) {
        if (_state.value.monitorMode != null) return
        val current = _state.value
        val snapshot = current.snapshot ?: return
        val selectedNames = cameraNames.distinct()
        if (selectedNames.size !in MIN_CAMERAS_PER_GROUP_VIEW..MAX_CAMERAS_PER_GROUP_VIEW) {
            _state.update { it.copy(errorMessage = "Choose two to four different cameras") }
            return
        }
        val cameras = selectedNames.mapNotNull { cameraName ->
            snapshot.cameras.firstOrNull { it.name == cameraName }
        }
        if (cameras.size != selectedNames.size) {
            _state.update { it.copy(errorMessage = "One of those cameras is not available") }
            return
        }
        startMonitorMode(title, cameras, preset)
    }

    private fun startMonitorMode(
        title: String,
        cameras: List<Camera>,
        preset: MonitorPreset,
    ) {
        if (_state.value.monitorMode != null) return
        val current = _state.value
        if (cameras.isEmpty()) return
        val monitorTitle = title.trim().take(CAMERA_GROUP_VIEW_TITLE_LENGTH).ifEmpty { "View" }
        val cameraIds = cameras.mapTo(linkedSetOf(), Camera::name)
        val grant = if (BuildConfig.DOCUMENTATION_MODE) {
            cameraIds
        } else {
            issuePrivacyGrant(
                PrivacySurface.MONITOR,
                PrivacyTarget.CameraCollection("monitor-$monitorTitle", cameraIds),
            )?.grant?.visibleCameraIds.orEmpty()
        }
        if (grant.isEmpty()) {
            _state.update { it.copy(errorMessage = "This View is hidden by the current privacy settings") }
            return
        }
        val now = System.currentTimeMillis().coerceAtLeast(0L)
        val privacyEpoch = current.privacy.epoch.coerceAtLeast(0L)
        _state.update {
            it.copy(
                monitorMode = MonitorModeUiState(
                    title = monitorTitle,
                    cameras = cameras,
                    preset = preset,
                    visibleCameraIds = grant,
                    arbitration = monitorArbiter.initialState(privacyEpoch, now),
                ),
                errorMessage = null,
            )
        }
        if (BuildConfig.DOCUMENTATION_MODE) return
        container.startMonitorAwareness()
        monitorJob?.cancel()
        monitorJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive && _state.value.monitorMode != null) {
                val monitor = _state.value.monitorMode ?: break
                val now = System.currentTimeMillis().coerceAtLeast(0L)
                if (monitor.exitAtEpochMillis?.let { now >= it } == true) {
                    withContext(Dispatchers.Main.immediate) { closeMonitorMode() }
                    break
                }
                advanceMonitor(MonitorAction.Tick)
                delay(MONITOR_TICK_MILLIS)
            }
        }
    }

    fun setMonitorPreset(preset: MonitorPreset) {
        val monitor = _state.value.monitorMode ?: return
        container.revokeSingleLivePlayback()
        container.updateMonitorPresentedReview(null)
        val now = System.currentTimeMillis().coerceAtLeast(0L)
        _state.update {
            it.copy(
                monitorMode = monitor.copy(
                    preset = preset,
                    arbitration = monitorArbiter.initialState(it.privacy.epoch, now),
                    liveCompatibilityRequestId = null,
                    statusMessage = null,
                ),
            )
        }
    }

    fun selectMonitorCamera(cameraId: String) {
        viewModelScope.launch(Dispatchers.Default) {
            advanceMonitor(MonitorAction.ManualSelect(cameraId))
        }
    }

    fun monitorPlaybackReady() {
        viewModelScope.launch(Dispatchers.Default) { advanceMonitor(MonitorAction.PlaybackReady) }
    }

    fun monitorPlaybackFailed() {
        viewModelScope.launch(Dispatchers.Default) {
            advanceMonitor(MonitorAction.PlaybackFailed)
        }
    }

    fun setMonitorKeepScreenAwake(enabled: Boolean) {
        _state.update { state ->
            state.copy(monitorMode = state.monitorMode?.copy(keepScreenAwake = enabled))
        }
    }

    fun setMonitorAudioEnabled(enabled: Boolean) {
        if (enabled && _state.value.privacy.guestModeActive) return
        _state.update { state ->
            state.copy(monitorMode = state.monitorMode?.copy(audioEnabled = enabled))
        }
    }

    fun setMonitorExitMinutes(minutes: Int?) {
        if (minutes != null && minutes !in setOf(30, 60, 120)) return
        val now = System.currentTimeMillis().coerceAtLeast(0L)
        val deadline = minutes?.let { safeMonitorDeadline(now, it * 60_000L) }
        _state.update { state ->
            state.copy(
                monitorMode = state.monitorMode?.copy(
                    exitAtEpochMillis = deadline,
                    exitAfterMinutes = minutes,
                ),
            )
        }
    }

    fun closeMonitorMode() {
        val monitor = _state.value.monitorMode ?: return
        monitorJob?.cancel()
        monitorJob = null
        val now = System.currentTimeMillis().coerceAtLeast(0L)
        val transition = monitorArbiter.reduce(
            monitor.arbitration,
            monitorInput(monitor, MonitorAction.Exit, now),
        )
        if (transition.commands.any { it is MonitorCommand.ReleasePromotion }) {
            container.revokeSingleLivePlayback()
        }
        container.stopMonitorAwareness()
        _state.update { it.copy(monitorMode = null) }
    }

    private suspend fun advanceMonitor(action: MonitorAction) = monitorMutex.withLock {
        val monitor = _state.value.monitorMode ?: return@withLock
        val now = System.currentTimeMillis().coerceAtLeast(0L)
        val input = monitorInput(monitor, action, now)
        if (input.visibleCameraIds.isEmpty()) {
            container.revokeSingleLivePlayback()
            container.updateMonitorPresentedReview(null)
            container.stopMonitorAwareness()
            _state.update { it.copy(monitorMode = null) }
            return@withLock
        }
        val transition = monitorArbiter.reduce(
            monitor.arbitration,
            input,
        )
        container.updateMonitorPresentedReview(transition.state.promotion?.reviewId)
        _state.update { state ->
            state.copy(
                monitorMode = state.monitorMode?.copy(
                    arbitration = transition.state,
                    visibleCameraIds = input.visibleCameraIds,
                    statusMessage = if (action == MonitorAction.PlaybackReady) {
                        null
                    } else {
                        state.monitorMode.statusMessage
                    },
                    audioEnabled = state.monitorMode.audioEnabled &&
                        !state.privacy.guestModeActive,
                ),
            )
        }
        transition.commands.forEach { command ->
            when (command) {
                is MonitorCommand.BeginPromotion -> prepareMonitorPromotion(command.promotion.cameraId)
                is MonitorCommand.ReleasePromotion -> {
                    container.revokeSingleLivePlayback()
                    _state.update { state ->
                        state.copy(
                            monitorMode = state.monitorMode?.copy(
                                liveCompatibilityRequestId = null,
                            ),
                        )
                    }
                }
                MonitorCommand.ShowBaseline,
                is MonitorCommand.ShowPatrolCamera,
                -> _state.update { state ->
                    state.copy(
                        monitorMode = state.monitorMode?.copy(
                            liveCompatibilityRequestId = null,
                            statusMessage = null,
                        ),
                    )
                }
                MonitorCommand.ShowConnectionProblem -> _state.update { state ->
                    state.copy(
                        monitorMode = state.monitorMode?.copy(
                            statusMessage = "Reconnecting to Frigate",
                        ),
                    )
                }
                MonitorCommand.EndSession -> Unit
            }
        }
    }

    private fun monitorInput(
        monitor: MonitorModeUiState,
        action: MonitorAction,
        now: Long,
    ): app.opah.tv.monitor.MonitorInput {
        val cameraIds = monitor.cameras.mapTo(linkedSetOf(), Camera::name)
        val privacyVisible = if (BuildConfig.DOCUMENTATION_MODE) {
            cameraIds
        } else {
            issuePrivacyGrant(
                PrivacySurface.MONITOR,
                PrivacyTarget.CameraCollection("active-monitor", cameraIds),
            )?.grant?.visibleCameraIds.orEmpty()
        }
        val transport = if (BuildConfig.DOCUMENTATION_MODE) {
            null
        } else {
            container.monitorTransportState.value
        }
        val visible = when (val scope = transport?.cameraScope) {
            is CameraScopeState.Fresh -> privacyVisible.intersect(scope.evidence.allowedCameraIds)
            is CameraScopeState.Stale -> privacyVisible.intersect(scope.evidence.allowedCameraIds)
            CameraScopeState.Unknown,
            null,
            -> privacyVisible
        }
        return app.opah.tv.monitor.MonitorInput(
            configuration = MonitorConfiguration(
                preset = monitor.preset,
                viewCameraIds = monitor.cameras.map(Camera::name),
                significantMotionEnabled = _state.value.tvAlerts.significantMotionEnabled,
            ),
            visibleCameraIds = visible,
            privacyEpoch = _state.value.privacy.epoch.coerceAtLeast(0L),
            reviews = if (BuildConfig.DOCUMENTATION_MODE) {
                emptyList()
            } else {
                container.monitorAwarenessState.value.ledger.reviewsById.values
            },
            networkAvailable = BuildConfig.DOCUMENTATION_MODE || (
                transport?.networkAvailable == true &&
                    transport.authenticationState == RealtimeAuthenticationState.AUTHENTICATED
                ),
            nowEpochMillis = now,
            action = action,
        )
    }

    private fun prepareMonitorPromotion(cameraId: String) {
        val current = _state.value
        val monitor = current.monitorMode ?: return
        val profile = current.activeProfile ?: return degradeMonitorPromotion()
        val snapshot = current.snapshot ?: return degradeMonitorPromotion()
        val device = current.device ?: return degradeMonitorPromotion()
        val camera = monitor.cameras.firstOrNull { it.name == cameraId }
            ?: return degradeMonitorPromotion()
        runCatching {
            val option = streamSelector.select(
                camera,
                device.codecs,
                StreamPreference.LOW_BANDWIDTH,
            ).getOrThrow().option
            container.singleLivePlaybackCoordinator.prepare(
                profile = profile,
                snapshot = snapshot,
                camera = camera,
                preferredStreamName = option.streamName,
                device = device,
                preferRtpTcp = current.settings.preferRtpTcp,
                purpose = PlaybackPurpose.MONITOR,
            )
        }.onSuccess { requestId ->
            _state.update { state ->
                state.copy(
                    monitorMode = state.monitorMode?.copy(
                        liveCompatibilityRequestId = requestId.value,
                        statusMessage = "Opening ${camera.displayName}",
                    ),
                )
            }
        }.onFailure {
            degradeMonitorPromotion()
        }
    }

    private fun degradeMonitorPromotion() {
        val monitor = _state.value.monitorMode ?: return
        val now = System.currentTimeMillis().coerceAtLeast(0L)
        val transition = monitorArbiter.reduce(
            monitor.arbitration,
            monitorInput(monitor, MonitorAction.PlaybackFailed, now),
        )
        _state.update { state ->
            state.copy(
                monitorMode = state.monitorMode?.copy(
                    arbitration = transition.state,
                    liveCompatibilityRequestId = null,
                    statusMessage = "Live video is unavailable; showing snapshots",
                ),
            )
        }
    }

    fun saveCameraView(name: String, cameraNames: List<String>) {
        val current = _state.value
        val snapshot = current.snapshot ?: return
        val selected = cameraNames.distinct()
        if (
            selected.size !in MIN_CAMERAS_PER_GROUP_VIEW..MAX_CAMERAS_PER_GROUP_VIEW ||
            selected.any { name -> snapshot.cameras.none { it.name == name } }
        ) {
            _state.update { it.copy(errorMessage = "Choose two to four available cameras") }
            return
        }
        val cleanName = name.trim().replace(Regex("\\s+"), " ").take(SAVED_VIEW_NAME_LENGTH)
        if (cleanName.isEmpty()) {
            _state.update { it.copy(errorMessage = "Enter a name for this view") }
            return
        }
        updateSettings { settings ->
            val existing = settings.savedCameraViews.firstOrNull { it.cameraNames.toSet() == selected.toSet() }
            val view = SavedCameraView(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = cleanName,
                firstCameraName = selected[0],
                secondCameraName = selected[1],
                thirdCameraName = selected.getOrNull(2),
                fourthCameraName = selected.getOrNull(3),
            )
            settings.copy(savedCameraViews = settings.savedCameraViews.filterNot { it.id == view.id } + view)
        }
    }

    fun deleteCameraView(viewId: String) = updateSettings { settings ->
        settings.copy(
            savedCameraViews = settings.savedCameraViews.filterNot { it.id == viewId },
            favoriteViewIds = settings.favoriteViewIds.filterNot { it == "saved:$viewId" },
            startupTarget = settings.startupTarget.takeUnless {
                it.kind == StartupTargetKind.SAVED_VIEW && it.value == viewId
            } ?: StartupTarget(),
        )
    }

    fun toggleFavoriteCamera(cameraName: String) = updateSettings { settings ->
        val favorites = settings.favoriteCameraNames
        settings.copy(
            favoriteCameraNames = if (cameraName in favorites) {
                favorites - cameraName
            } else {
                favorites + cameraName
            },
            hiddenHomeCameraNames = settings.hiddenHomeCameraNames - cameraName,
        )
    }

    fun moveFavoriteCamera(cameraName: String, direction: Int) = updateSettings { settings ->
        settings.copy(favoriteCameraNames = moveOrderedItem(settings.favoriteCameraNames, cameraName, direction))
    }

    fun hideCameraFromHome(cameraName: String) = updateSettings { settings ->
        settings.copy(
            favoriteCameraNames = settings.favoriteCameraNames - cameraName,
            hiddenHomeCameraNames = settings.hiddenHomeCameraNames + cameraName,
        )
    }

    fun toggleFavoriteView(viewId: String) = updateSettings { settings ->
        settings.copy(
            favoriteViewIds = if (viewId in settings.favoriteViewIds) {
                settings.favoriteViewIds - viewId
            } else {
                settings.favoriteViewIds + viewId
            },
        )
    }

    fun moveFavoriteView(viewId: String, direction: Int) = updateSettings { settings ->
        settings.copy(favoriteViewIds = moveOrderedItem(settings.favoriteViewIds, viewId, direction))
    }

    fun restoreHomeDefaults() = updateSettings { settings ->
        settings.copy(
            favoriteCameraNames = emptyList(),
            hiddenHomeCameraNames = emptySet(),
            favoriteViewIds = emptyList(),
        )
    }

    fun updateStartupTarget(target: StartupTarget) = updateSettings { it.copy(startupTarget = target) }

    fun rememberLastViewedTarget(target: StartupTarget) = updateSettings { settings ->
        if (settings.lastViewedTarget == target) settings else settings.copy(lastViewedTarget = target)
    }

    fun loadModes(force: Boolean = false) {
        val current = _state.value
        val snapshot = current.snapshot ?: return
        if (!snapshot.capabilities.supports(FrigateFeature.PROFILE_MODES)) return
        if (current.modes.loading || (!force && current.modes.loadedOnce)) return
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    modes = DocumentationSupport.data.modes(),
                )
            }
            return
        }
        val profile = current.activeProfile ?: return
        viewModelScope.launch {
            _state.update { it.copy(modes = it.modes.copy(loading = true, errorMessage = null)) }
            runCatching { operationsRepository.loadModes(profile, snapshot.frigateVersion) }
                .onSuccess { loaded ->
                    _state.update {
                        it.copy(
                            modes = it.modes.copy(
                                loading = false,
                                loadedOnce = true,
                                modes = loaded.modes,
                                activeMode = loaded.activeMode,
                                errorMessage = null,
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    if (error is AuthenticationExpiredException) {
                        handleConnectedFailure(error)
                    } else {
                        _state.update {
                            it.copy(
                                modes = it.modes.copy(
                                    loading = false,
                                    loadedOnce = true,
                                    errorMessage = error.toOpahFailure().userMessage,
                                ),
                            )
                        }
                    }
                }
        }
    }

    fun switchMode(modeName: String?) {
        val current = _state.value
        val profile = current.activeProfile ?: return
        val snapshot = current.snapshot ?: return
        if (!snapshot.capabilities.supports(FrigateFeature.PROFILE_MODE_SWITCH) || current.modes.switching) return
        if (BuildConfig.DOCUMENTATION_MODE) {
            val previous = current.modes.activeMode
            _state.update {
                it.copy(
                    modes = it.modes.copy(
                        activeMode = modeName,
                        switching = false,
                        statusMessage = "Mode changed to ${modeDisplayName(modeName, it.modes.modes)}",
                        errorMessage = null,
                        undoMode = previous,
                        undoAvailable = true,
                    ),
                )
            }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(modes = it.modes.copy(switching = true, errorMessage = null, statusMessage = null))
            }
            runCatching {
                operationsRepository.switchMode(profile, snapshot.user, snapshot.frigateVersion, modeName)
            }.onSuccess { result ->
                _state.update {
                    it.copy(
                        modes = it.modes.copy(
                            switching = false,
                            activeMode = result.activeMode,
                            statusMessage = "Mode changed to ${modeDisplayName(result.activeMode, it.modes.modes)}",
                            undoMode = result.previousMode,
                            undoAvailable = true,
                        ),
                    )
                }
                refresh()
            }.onFailure { error ->
                if (error is AuthenticationExpiredException) {
                    handleConnectedFailure(error)
                } else {
                    _state.update {
                        it.copy(
                            modes = it.modes.copy(
                                switching = false,
                                errorMessage = error.toOpahFailure().userMessage,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun undoModeSwitch() {
        val current = _state.value
        if (!current.modes.undoAvailable || current.modes.switching) return
        val profile = current.activeProfile ?: return
        val snapshot = current.snapshot ?: return
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    modes = it.modes.copy(
                        activeMode = it.modes.undoMode,
                        statusMessage = "Mode restored",
                        undoAvailable = false,
                        undoMode = null,
                    ),
                )
            }
            return
        }
        val expectedActive = current.modes.activeMode
        val undoMode = current.modes.undoMode
        viewModelScope.launch {
            _state.update { it.copy(modes = it.modes.copy(switching = true, errorMessage = null)) }
            runCatching {
                val actual = operationsRepository.loadModes(profile, snapshot.frigateVersion)
                if (actual.activeMode != expectedActive) error("Mode changed elsewhere")
                operationsRepository.switchMode(profile, snapshot.user, snapshot.frigateVersion, undoMode)
            }.onSuccess { result ->
                _state.update {
                    it.copy(
                        modes = it.modes.copy(
                            switching = false,
                            activeMode = result.activeMode,
                            statusMessage = "Mode restored",
                            undoAvailable = false,
                            undoMode = null,
                        ),
                    )
                }
                refresh()
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        modes = it.modes.copy(
                            switching = false,
                            undoAvailable = false,
                            undoMode = null,
                            errorMessage = if (error.message == "Mode changed elsewhere") {
                                "Mode changed elsewhere, so Undo was cancelled"
                            } else {
                                error.toOpahFailure().userMessage
                            },
                        ),
                    )
                }
            }
        }
    }

    fun clearModeMessage() {
        _state.update { it.copy(modes = it.modes.copy(errorMessage = null, statusMessage = null)) }
    }

    fun updateCameraStretch(cameraName: String, stretched: Boolean) = updateSettings { settings ->
        settings.copy(
            stretchedCameraNames = if (stretched) {
                settings.stretchedCameraNames + cameraName
            } else {
                settings.stretchedCameraNames - cameraName
            },
        )
    }

    fun updateAppearance(mode: AppearanceMode) = updateSettings { it.copy(appearanceMode = mode) }

    fun updateCustomTheme(colors: CustomThemeColors) = updateSettings {
        it.copy(
            appearanceMode = AppearanceMode.CUSTOM,
            customThemeColors = ThemeColorPolicy.sanitize(colors),
        )
    }

    fun updateReducedMotion(enabled: Boolean) = updateSettings { it.copy(reducedMotion = enabled) }

    fun updateHighContrast(enabled: Boolean) = updateSettings { it.copy(highContrast = enabled) }

    fun updateReducedTransparency(enabled: Boolean) = updateSettings { it.copy(reducedTransparency = enabled) }

    fun updateSubtleRoundedCorners(enabled: Boolean) = updateSettings {
        it.copy(subtleRoundedCorners = enabled)
    }

    fun updateAutomaticUpdateChecks(enabled: Boolean) =
        updateSettings { it.copy(automaticUpdateChecksEnabled = enabled) }

    fun loadInformation(force: Boolean = false) {
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    information = InformationUiState(
                        loadedOnce = true,
                        summary = DocumentationSupport.data.information(),
                    ),
                )
            }
            return
        }
        val current = _state.value
        val profile = current.activeProfile ?: return
        val snapshot = current.snapshot ?: return
        if (current.information.loading || (!force && current.information.loadedOnce)) return
        viewModelScope.launch {
            _state.update {
                it.copy(information = it.information.copy(loading = true, errorMessage = null))
            }
            runCatching { repository.loadInformation(profile, snapshot) }
                .onSuccess { summary ->
                    _state.update {
                        it.copy(
                            information = InformationUiState(
                                loading = false,
                                loadedOnce = true,
                                summary = summary,
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    if (error is AuthenticationExpiredException) {
                        handleConnectedFailure(error)
                    } else {
                        logger.warning("Frigate information load failed", error)
                        _state.update {
                            it.copy(
                                information = it.information.copy(
                                    loading = false,
                                    loadedOnce = true,
                                    errorMessage = error.message
                                        ?: error.toOpahFailure().userMessage,
                                ),
                            )
                        }
                    }
                }
        }
    }

    fun updateStreamPreference(preference: StreamPreference) =
        updateSettings { it.copy(streamPreference = preference) }

    fun updatePreferRtpTcp(enabled: Boolean) = updateSettings { it.copy(preferRtpTcp = enabled) }

    fun loadCameraPlaybackCompatibilityChoices() {
        if (BuildConfig.DOCUMENTATION_MODE) return
        val current = _state.value
        val profile = current.activeProfile ?: return
        val snapshot = current.snapshot ?: return
        val device = current.device ?: return
        viewModelScope.launch {
            val choices = buildMap {
                snapshot.cameras.forEach { camera ->
                    val preferred = streamSelector.select(
                        camera = camera,
                        deviceCodecs = device.codecs,
                        preference = current.settings.streamPreference,
                    ).getOrNull()?.option ?: return@forEach
                    val requestId = runCatching {
                        container.singleLivePlaybackCoordinator.prepare(
                            profile = profile,
                            snapshot = snapshot,
                            camera = camera,
                            preferredStreamName = preferred.streamName,
                            device = device,
                            preferRtpTcp = current.settings.preferRtpTcp,
                        )
                    }.getOrNull() ?: return@forEach
                    val saved = runCatching {
                        container.singleLivePlaybackCoordinator.loadSavedStrategy(requestId)
                    }.getOrNull() ?: return@forEach
                    put(camera.name, savedPlaybackChoiceLabel(
                        audioMode = saved.record.audioMode,
                        transportMode = saved.record.transportMode,
                        decoderMode = saved.record.decoderMode,
                    ))
                }
            }
            if (_state.value.activeProfile == profile && _state.value.snapshot == snapshot) {
                _state.update { it.copy(playbackCompatibilityChoices = choices) }
            }
        }
    }

    fun testCameraPlayback(
        cameraName: String,
        mode: PlaybackCompatibilityTestMode = PlaybackCompatibilityTestMode.AUTOMATIC,
    ) {
        val current = _state.value
        val camera = current.snapshot?.cameras?.firstOrNull { it.name == cameraName } ?: return
        if (
            !BuildConfig.DOCUMENTATION_MODE &&
            issuePrivacyGrant(
                PrivacySurface.LIVE_PLAYBACK,
                PrivacyTarget.Camera(camera.name),
            ) == null
        ) {
            _state.update {
                it.copy(
                    playbackCompatibilityMessage =
                        "${camera.displayName} is not available with the current privacy settings",
                )
            }
            return
        }
        _state.update {
            it.copy(
                playbackCompatibilityMessage =
                    "Starting ${camera.displayName}. Video may restart while Opah tries a few safe choices",
            )
        }
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    playback = DocumentationSupport.data.compatibilityPlayback(camera),
                    activeCameraName = camera.name,
                    errorMessage = null,
                )
            }
            return
        }
        val profile = current.activeProfile ?: return
        streamSelector.select(
            camera = camera,
            deviceCodecs = current.device?.codecs.orEmpty(),
            preference = current.settings.streamPreference,
        )
            .onSuccess { selection ->
                val preferRtpTcp = when (mode) {
                    PlaybackCompatibilityTestMode.STANDARD_CONNECTION -> false
                    PlaybackCompatibilityTestMode.RELIABLE_CONNECTION,
                    PlaybackCompatibilityTestMode.RELIABLE_VIDEO_ONLY,
                    -> true
                    PlaybackCompatibilityTestMode.AUTOMATIC,
                    PlaybackCompatibilityTestMode.VIDEO_ONLY,
                    -> current.settings.preferRtpTcp
                }
                playStream(
                    profile = profile,
                    camera = camera,
                    option = selection.option,
                    reason = "Compatibility test",
                    compatibilityTest = true,
                    compatibilityTestVideoOnly = mode == PlaybackCompatibilityTestMode.VIDEO_ONLY ||
                        mode == PlaybackCompatibilityTestMode.RELIABLE_VIDEO_ONLY,
                    compatibilityTestUseSavedStrategyFirst =
                        mode == PlaybackCompatibilityTestMode.AUTOMATIC,
                    preferRtpTcpOverride = preferRtpTcp,
                )
            }
            .onFailure {
                _state.update { state ->
                    state.copy(errorMessage = "This camera has no live stream to test")
                }
            }
    }

    fun reportPlaybackCompatibilityStatus(message: String) {
        _state.update { it.copy(playbackCompatibilityMessage = message) }
    }

    fun resetCameraPlaybackCompatibility(cameraName: String) {
        val current = _state.value
        val profile = current.activeProfile ?: return
        val snapshot = current.snapshot ?: return
        val device = current.device ?: return
        val camera = snapshot.cameras.firstOrNull { it.name == cameraName } ?: return
        val preferred = streamSelector.select(
            camera = camera,
            deviceCodecs = device.codecs,
            preference = current.settings.streamPreference,
        ).getOrNull()?.option ?: run {
            _state.update {
                it.copy(playbackCompatibilityMessage = "This camera has no live stream to reset")
            }
            return
        }
        val requestId = runCatching {
            container.singleLivePlaybackCoordinator.prepare(
                profile = profile,
                snapshot = snapshot,
                camera = camera,
                preferredStreamName = preferred.streamName,
                device = device,
                preferRtpTcp = current.settings.preferRtpTcp,
            )
        }.getOrElse {
            _state.update {
                it.copy(playbackCompatibilityMessage = "The saved choice could not be reset")
            }
            return
        }
        viewModelScope.launch {
            runCatching {
                container.singleLivePlaybackCoordinator.reset(requestId)
            }
                .onSuccess {
                    _state.update {
                        it.copy(
                            playbackCompatibilityMessage =
                                "Saved choice removed. Opah will choose automatically next time ${camera.displayName} opens",
                            playbackCompatibilityChoices = it.playbackCompatibilityChoices - camera.name,
                        )
                    }
                }
                .onFailure {
                    _state.update {
                        it.copy(playbackCompatibilityMessage = "The saved choice could not be reset")
                    }
                }
        }
    }

    fun updateStartLiveMuted(enabled: Boolean) =
        updateSettings { it.copy(startLiveMuted = enabled) }

    fun updateDiagnosticsEnabled(enabled: Boolean) =
        updateSettings { it.copy(diagnosticsEnabled = enabled) }

    fun updateAutoMarkReviewedAfterPlayback(enabled: Boolean) =
        updateSettings { it.copy(autoMarkReviewedAfterPlayback = enabled) }

    fun updateRtspRoute(hostOverride: String, portText: String) {
        if (BuildConfig.DOCUMENTATION_MODE) {
            val profile = _state.value.activeProfile ?: _state.value.savedProfile ?: return
            val updated = profile.copy(
                rtspHostOverride = hostOverride.trim().ifBlank { null },
                rtspPort = portText.toIntOrNull() ?: profile.rtspPort,
            )
            _state.update {
                it.copy(
                    savedProfile = updated,
                    activeProfile = if (it.activeProfile == null) null else updated,
                    statusMessage = "RTSP route updated",
                    errorMessage = null,
                )
            }
            return
        }
        val current = _state.value
        val profile = current.activeProfile ?: current.savedProfile ?: return
        val updated = createProfile(
            rawUrl = profile.apiBaseUrl,
            username = profile.username,
            rtspHostOverride = hostOverride,
            rtspPortText = portText,
        ) ?: return
        viewModelScope.launch {
            profileRepository.save(updated)
            _state.update {
                it.copy(
                    savedProfile = updated,
                    activeProfile = if (it.activeProfile == null) null else updated,
                    statusMessage = "RTSP route updated",
                    errorMessage = null,
                )
            }
        }
    }

    fun cachedCameraImage(cameraName: String): CameraImage? = if (BuildConfig.DOCUMENTATION_MODE) {
        documentationImages.camera(cameraName)
    } else {
        issuePrivacyGrant(PrivacySurface.HOME, PrivacyTarget.Camera(cameraName))
            ?.let { _state.value.activeProfile }
            ?.let { cameraImageRepository.cached(it, cameraName) }
    }

    internal fun prepareAuthenticatedHttpLive(cameraName: String, preferredStreamName: String? = null): app.opah.tv.playback.AuthenticatedHttpLive? {
        val current = _state.value
        val profile = current.activeProfile ?: return null
        val snapshot = current.snapshot ?: return null
        if (cameraName !in snapshot.user.allowedCameras) return null
        val camera = snapshot.cameras.firstOrNull { it.name == cameraName } ?: return null
        val selected = app.opah.tv.playback.selectHttpLiveStream(
            cameraName, camera.streams.map { it.streamName }, preferredStreamName,
        ) ?: return null
        val stream = camera.streams.first { it.streamName == selected }
        val grant = issuePrivacyGrant(PrivacySurface.LIVE_PLAYBACK, PrivacyTarget.Camera(cameraName))
            ?: return null
        val uri = app.opah.tv.playback.httpLiveUri(profile.apiBaseUrl, stream.streamName) ?: return null
        return app.opah.tv.playback.AuthenticatedHttpLive(uri) {
            _state.value.activeProfile == profile && revalidatePrivacyGrant(grant) &&
                _state.value.snapshot?.cameras?.any { latest ->
                    latest.name == cameraName && latest.streams.any { it.streamName == stream.streamName }
                } == true
        }
    }

    suspend fun refreshCameraImage(cameraName: String, height: Int = 360): Result<CameraImage> {
        if (BuildConfig.DOCUMENTATION_MODE) {
            return documentationImages.camera(cameraName)?.let(Result.Companion::success)
                ?: Result.failure(IllegalStateException("Documentation image is unavailable"))
        }
        val profile = _state.value.activeProfile
            ?: return Result.failure(IllegalStateException("No active Frigate connection"))
        val privacyGrant = issuePrivacyGrant(PrivacySurface.HOME, PrivacyTarget.Camera(cameraName))
            ?: return Result.failure(IllegalStateException("This camera is unavailable"))
        val refreshResult = cameraImageRepository.refresh(profile, cameraName, height)
        if (refreshResult.isSuccess && !revalidatePrivacyGrant(privacyGrant)) {
            return Result.failure(IllegalStateException("This camera is unavailable"))
        }
        return refreshResult.also { result ->
            if (result.isSuccess) {
                cameraImageFailureCounts.remove(cameraName)
                _state.update { state ->
                    if (cameraName !in state.health.messagesByCamera) {
                        state
                    } else {
                        state.copy(
                            health = state.health.copy(
                                messagesByCamera = state.health.messagesByCamera - cameraName,
                            ),
                        )
                    }
                }
            } else if (result.exceptionOrNull() !is AuthenticationExpiredException) {
                val failures = cameraImageFailureCounts.merge(cameraName, 1, Int::plus) ?: 1
                val cameraLabel = _state.value.snapshot?.cameras
                    ?.firstOrNull { it.name == cameraName }
                    ?.displayName
                    ?: cameraName.replace('_', ' ')
                cameraHealthMessage(cameraLabel, failures)?.let { message ->
                    _state.update { state ->
                        state.copy(
                            health = state.health.copy(
                                messagesByCamera = state.health.messagesByCamera + (cameraName to message),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun cancelReviewLoads(): Long {
        reviewLoadGeneration += 1
        reviewLoadJob?.cancel()
        reviewLoadJob = null
        reviewLoadMoreJob?.cancel()
        reviewLoadMoreJob = null
        return reviewLoadGeneration
    }

    fun loadReview() {
        val generation = cancelReviewLoads()
        // A deliberate browse/refresh leaves a temporary review queue.
        _state.update { it.copy(review = it.review.copy(
            queueActive = false, queueCompleted = false, queueItemIds = emptyList(),
            loading = false, loadingMore = false, countsLoading = false,
        )) }
        if (BuildConfig.DOCUMENTATION_MODE) {
            val filters = _state.value.review.filters
            val allItems = DocumentationSupport.data.reviewItems().withReviewStatuses(documentationReviewStatuses)
            val items = allItems.filter { item ->
                (filters.severity == null || item.severity == filters.severity) &&
                    (filters.camera == null || item.camera == filters.camera) &&
                    (filters.label == null || filters.label in item.objects) &&
                    (filters.zone == null || filters.zone in item.zones) &&
                    (filters.reviewStatus.apiValue == null ||
                        item.hasBeenReviewed == filters.reviewStatus.apiValue)
            }
            _state.update {
                val alertItems = allItems.filter { item -> item.severity == ReviewSeverity.ALERT }
                val detectionItems = allItems.filter { item -> item.severity == ReviewSeverity.DETECTION }
                it.copy(
                    review = it.review.copy(
                        items = items,
                        knownLabels = DocumentationSupport.data.reviewItems().flatMap(ReviewItem::objects).toSet(),
                        knownZones = DocumentationSupport.data.reviewItems().flatMap(ReviewItem::zones).toSet(),
                        counts = app.opah.tv.data.model.ReviewCounts(
                            reviewedAlerts = alertItems.count(ReviewItem::hasBeenReviewed),
                            reviewedDetections = detectionItems.count(ReviewItem::hasBeenReviewed),
                            totalAlerts = alertItems.size,
                            totalDetections = detectionItems.size,
                        ),
                        loading = false,
                        loadedOnce = true,
                        hasMore = false,
                        nextBeforeBySeverity = emptyMap(),
                        errorMessage = null,
                        selectedItemId = null,
                        recordingState = ReviewRecordingState.IDLE,
                        detailErrorMessage = null,
                    ),
                )
            }
            return
        }
        val current = _state.value
        val profile = current.activeProfile ?: return
        val allowedCameras = visibleCameraNames(PrivacySurface.ACTIVITY)
        if (allowedCameras.isEmpty()) return
        val filters = current.review.filters
        reviewDetailJob?.cancel()
        reviewLoadJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    review = it.review.copy(
                        loading = true,
                        countsLoading = true,
                        errorMessage = null,
                        selectedItemId = null,
                        recordingState = ReviewRecordingState.IDLE,
                        detailErrorMessage = null,
                    ),
                )
            }
            runCatching {
                coroutineScope {
                    val discovery = async {
                        repository.searchReview(
                            profile = profile,
                            allowedCameras = allowedCameras,
                            query = filters.toSearchQuery(
                                allowedCameras = allowedCameras,
                                nowSeconds = System.currentTimeMillis() / 1000.0,
                            ),
                        )
                    }
                    val counts = async { runCatching { repository.loadReviewCounts(profile, allowedCameras) } }
                    discovery.await() to counts.await()
                }
            }.onSuccess { (discovery, countsResult) ->
                if (!isActive || generation != reviewLoadGeneration) return@launch
                _state.update { state ->
                    val labels = discovery.items.flatMap(ReviewItem::objects)
                        .filter(String::isNotBlank)
                        .toSet()
                    val zones = discovery.items.flatMap(ReviewItem::zones)
                        .filter(String::isNotBlank)
                        .toSet()
                    state.copy(
                        review = state.review.copy(
                            items = discovery.items,
                            knownLabels = state.review.knownLabels + labels,
                            knownZones = state.review.knownZones + zones,
                            counts = countsResult.getOrDefault(state.review.counts),
                            countsLoading = false,
                            loading = false,
                            loadedOnce = true,
                            hasMore = discovery.nextBeforeBySeverity.isNotEmpty(),
                            nextBeforeBySeverity = discovery.nextBeforeBySeverity,
                            errorMessage = discovery.warnings.firstOrNull(),
                        ),
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (!isActive || generation != reviewLoadGeneration) return@launch
                if (error is AuthenticationExpiredException) {
                    handleConnectedFailure(error)
                } else {
                    logger.warning("Frigate Review load failed", error)
                    _state.update {
                        it.copy(
                            review = it.review.copy(
                                loading = false,
                                countsLoading = false,
                                loadedOnce = true,
                                errorMessage = error.toOpahFailure().userMessage,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun updateReviewSeverity(severity: ReviewSeverity?) =
        updateReviewFilters { it.copy(severity = severity) }

    fun loadMoreReview() {
        val current = _state.value
        if (
            BuildConfig.DOCUMENTATION_MODE || current.review.loading || current.review.loadingMore ||
            !current.review.hasMore || current.review.queueActive || reviewLoadMoreJob?.isActive == true
        ) return
        val profile = current.activeProfile ?: return
        val allowedCameras = visibleCameraNames(PrivacySurface.ACTIVITY)
        if (allowedCameras.isEmpty()) return
        val filters = current.review.filters
        val generation = reviewLoadGeneration
        reviewLoadMoreJob = viewModelScope.launch {
            _state.update { it.copy(review = it.review.copy(loadingMore = true, errorMessage = null)) }
            runCatching {
                repository.searchReview(
                    profile = profile,
                    allowedCameras = allowedCameras,
                    query = filters.toSearchQuery(
                        allowedCameras = allowedCameras,
                        nowSeconds = System.currentTimeMillis() / 1000.0,
                    ),
                    beforeBySeverity = current.review.nextBeforeBySeverity,
                )
            }.onSuccess { discovery ->
                if (!isActive || generation != reviewLoadGeneration) return@launch
                _state.update { state ->
                    val combined = (state.review.items + discovery.items).distinctBy(ReviewItem::id)
                    state.copy(
                        review = state.review.copy(
                            items = combined,
                            knownLabels = state.review.knownLabels + discovery.items.flatMap(ReviewItem::objects),
                            knownZones = state.review.knownZones + discovery.items.flatMap(ReviewItem::zones),
                            loadingMore = false,
                            hasMore = discovery.nextBeforeBySeverity.isNotEmpty(),
                            nextBeforeBySeverity = discovery.nextBeforeBySeverity,
                        ),
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (!isActive || generation != reviewLoadGeneration) return@launch
                if (error is AuthenticationExpiredException) {
                    handleConnectedFailure(error)
                } else {
                    logger.warning("Loading more Frigate Review items failed", error)
                    _state.update {
                        it.copy(
                            review = it.review.copy(
                                loadingMore = false,
                                errorMessage = error.toOpahFailure().userMessage,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun applyReviewFilters(filters: ReviewFilters) = updateReviewFilters { filters }

    fun resetReviewFilters() {
        _state.update {
            it.copy(review = it.review.copy(filters = it.review.filters.clearDetails()))
        }
        loadReview()
    }

    fun selectReviewItem(item: ReviewItem) {
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    review = it.review.copy(
                        selectedItemId = item.id,
                        recordingState = ReviewRecordingState.AVAILABLE,
                        detailErrorMessage = null,
                        detailLoading = false,
                    ),
                )
            }
            return
        }
        val profile = _state.value.activeProfile ?: return
        val allowedCameras = _state.value.snapshot?.user?.allowedCameras.orEmpty()
        reviewDetailJob?.cancel()
        _state.update {
            it.copy(
                review = it.review.copy(
                    selectedItemId = item.id,
                    recordingState = ReviewRecordingState.CHECKING,
                    detailErrorMessage = null,
                    detailLoading = true,
                ),
            )
        }
        reviewDetailJob = viewModelScope.launch {
            val (recordingResult, enrichmentResult) = coroutineScope {
                val recording = async { repository.reviewRecordingAvailable(profile, item) }
                val enrichment = async {
                    runCatching { repository.enrichReviewItem(profile, allowedCameras, item) }
                }
                recording.await() to enrichment.await()
            }
            val authError = listOf(recordingResult.exceptionOrNull(), enrichmentResult.exceptionOrNull())
                .filterIsInstance<AuthenticationExpiredException>()
                .firstOrNull()
            if (authError != null) {
                handleConnectedFailure(authError)
                return@launch
            }
            recordingResult.exceptionOrNull()?.let {
                logger.warning("Review recording availability check failed", it)
            }
            enrichmentResult.exceptionOrNull()?.let {
                logger.warning("Review linked activity load failed", it)
            }
            _state.update { state ->
                if (state.review.selectedItemId != item.id) return@update state
                val available = recordingResult.getOrNull()
                val enriched = enrichmentResult.getOrDefault(item)
                    .copy(recordingAvailable = available ?: item.recordingAvailable)
                state.copy(
                    review = state.review.copy(
                        items = state.review.items.map { candidate ->
                            if (candidate.id == item.id) enriched else candidate
                        },
                        recordingState = when (available) {
                            true -> ReviewRecordingState.AVAILABLE
                            false -> ReviewRecordingState.UNAVAILABLE
                            null -> ReviewRecordingState.UNKNOWN
                        },
                        detailLoading = false,
                        detailErrorMessage = if (available == null) {
                            "Recording availability could not be confirmed"
                        } else {
                            null
                        },
                    ),
                )
            }
        }
    }

    fun closeReviewItem() {
        reviewDetailJob?.cancel()
        _state.update {
            it.copy(
                review = it.review.copy(
                    selectedItemId = null,
                    recordingState = ReviewRecordingState.IDLE,
                    detailErrorMessage = null,
                    detailLoading = false,
                ),
            )
        }
    }

    fun startReviewQueue() {
        val items = _state.value.review.items.filterNot(ReviewItem::hasBeenReviewed)
        cancelReviewLoads()
        _state.update { it.copy(review = it.review.copy(loading = false, loadingMore = false, countsLoading = false, errorMessage = null)) }
        if (items.isEmpty()) {
            _state.update {
                it.copy(
                    review = it.review.copy(
                        selectedItemId = null,
                        queueItemIds = emptyList(),
                        queueIndex = 0,
                        queueActive = false,
                        queueCompleted = true,
                    ),
                )
            }
            return
        }
        _state.update {
            it.copy(
                review = it.review.copy(
                    queueItemIds = items.map(ReviewItem::id),
                    queueIndex = 0,
                    queueActive = true,
                    queueCompleted = false,
                    queueTitle = "New activity",
                ),
            )
        }
        selectReviewItem(items.first())
    }

    fun moveReviewQueue(direction: Int) {
        val review = _state.value.review
        if (!review.queueActive || direction == 0) return
        val nextIndex = review.queueIndex + direction
        if (nextIndex < 0) return
        if (nextIndex > review.queueItemIds.lastIndex) {
            reviewDetailJob?.cancel()
            _state.update {
                it.copy(
                    review = it.review.copy(
                        selectedItemId = null,
                        recordingState = ReviewRecordingState.IDLE,
                        queueActive = false,
                        queueCompleted = true,
                    ),
                )
            }
            return
        }
        val item = review.items.firstOrNull { it.id == review.queueItemIds[nextIndex] } ?: return
        _state.update { it.copy(review = it.review.copy(queueIndex = nextIndex)) }
        selectReviewItem(item)
    }

    fun endReviewQueue() {
        reviewDetailJob?.cancel()
        _state.update {
            it.copy(
                review = it.review.copy(
                    selectedItemId = null,
                    recordingState = ReviewRecordingState.IDLE,
                    detailErrorMessage = null,
                    queueItemIds = emptyList(),
                    queueIndex = 0,
                    queueActive = false,
                    queueCompleted = false,
                ),
            )
        }
    }

    fun setReviewReviewed(item: ReviewItem, reviewed: Boolean) {
        if (item.hasBeenReviewed == reviewed || _state.value.review.hasPendingWrite()) return
        if (BuildConfig.DOCUMENTATION_MODE) {
            publishReviewStatus(item.id, reviewed)
            return
        }
        val profile = _state.value.activeProfile ?: return
        val grant = issuePrivacyGrant(PrivacySurface.ACTIVITY,
            PrivacyTarget.Review(item.id, item.camera, item.subLabels.isNotEmpty())) ?: return
        val requestId = ++reviewWriteRequestId
        _state.update {
            it.copy(
                review = it.review.copy(
                    markingReviewedItemId = item.id,
                    detailErrorMessage = null,
                    actionError = it.review.actionError?.takeUnless { error -> error.itemId == item.id && error.action == ReviewAction.MARK_REVIEWED },
                ),
            )
        }
        viewModelScope.launch {
            if (!revalidateReviewWrite(requestId, profile, listOf(grant))) return@launch
            runCatching { repository.setReviewReviewed(profile, item, reviewed) }
                .onSuccess {
                    if (!revalidateReviewWrite(requestId, profile, listOf(grant))) return@onSuccess
                    publishReviewStatus(item.id, reviewed)
                    if (reviewed) {
                        val briefingEntry = _state.value.briefing.summary?.entries.orEmpty()
                            .firstOrNull { entry -> entry.item.id == item.id }
                        if (briefingEntry != null) {
                            acknowledgeBriefing(
                                entries = listOf(briefingEntry),
                                reason = BriefingAcknowledgementReason.MARKED_REVIEWED,
                            )
                        }
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) { clearReviewWrite(requestId); throw error }
                    if (!revalidateReviewWrite(requestId, profile, listOf(grant))) return@onFailure
                    if (error is AuthenticationExpiredException) {
                        handleConnectedFailure(error)
                    } else {
                        logger.warning("Changing Frigate Review status failed", error)
                        _state.update { state ->
                            state.copy(
                                review = state.review.copy(
                                    markingReviewedItemId = null,
                                    detailErrorMessage = "Frigate couldn't update this activity",
                                    actionError = ReviewActionError(item.id, ReviewAction.MARK_REVIEWED,
                                        "Couldn’t update this activity", reviewed = reviewed),
                                ),
                            )
                        }
                    }
                }
        }
    }

    fun markAllShownActivityReviewed() {
        val current = _state.value
        val items = current.projectForPrivacy().review.unreviewedShownActivity()
        if (items.isEmpty() || current.review.hasPendingWrite()) return
        if (BuildConfig.DOCUMENTATION_MODE) {
            publishReviewStatuses(items.map(ReviewItem::id).toSet(), reviewed = true)
            return
        }
        val profile = current.activeProfile ?: return
        val grants = items.map { item ->
            issuePrivacyGrant(PrivacySurface.ACTIVITY,
                PrivacyTarget.Review(item.id, item.camera, item.subLabels.isNotEmpty())) ?: return
        }
        val requestId = ++reviewWriteRequestId
        _state.update {
            it.copy(review = it.review.copy(markingAllReviewed = true, errorMessage = null))
        }
        viewModelScope.launch {
            if (!revalidateReviewWrite(requestId, profile, grants)) return@launch
            runCatching { repository.setReviewsReviewed(profile, items, reviewed = true) }
                .onSuccess {
                    if (!revalidateReviewWrite(requestId, profile, grants)) return@onSuccess
                    val ids = items.map(ReviewItem::id).toSet()
                    publishReviewStatuses(ids, reviewed = true)
                    val briefingEntries = _state.value.briefing.summary?.entries.orEmpty()
                        .filter { entry -> entry.item.id in ids }
                    if (briefingEntries.isNotEmpty()) {
                        acknowledgeBriefing(
                            entries = briefingEntries,
                            reason = BriefingAcknowledgementReason.MARKED_REVIEWED,
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) { clearReviewWrite(requestId); throw error }
                    if (!revalidateReviewWrite(requestId, profile, grants)) return@onFailure
                    if (error is AuthenticationExpiredException) {
                        handleConnectedFailure(error)
                    } else {
                        logger.warning("Marking displayed Frigate Review items failed", error)
                        _state.update {
                            it.copy(
                                review = it.review.copy(
                                    markingAllReviewed = false,
                                    errorMessage = "Frigate couldn't update the activity shown",
                                ),
                            )
                        }
                    }
                }
        }
    }

    fun saveReviewClip(item: ReviewItem) {
        val current = _state.value
        if (!current.review.canSaveClip(item)) return
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    review = it.review.afterClipSaved(item.id),
                )
            }
            return
        }
        val reviewPrivacyGrant = issuePrivacyGrant(
            PrivacySurface.ACTIVITY,
            PrivacyTarget.Review(item.id, item.camera, item.subLabels.isNotEmpty()),
        ) ?: return
        val actionPrivacyGrant = issuePrivacyGrant(
            PrivacySurface.ACTION,
            PrivacyTarget.LocalAction(app.opah.tv.privacy.PrivacyLocalAction.ADMINISTRATIVE_CHANGE),
        ) ?: return
        val profile = current.activeProfile ?: return
        val allowed = current.snapshot?.user?.allowedCameras.orEmpty()
        val cameraName = current.snapshot?.authorizedCameraNames?.get(item.camera)
            ?: item.camera.replace('_', ' ')
        val started = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date((item.startTime * 1_000).toLong()))
        val clipName = "$cameraName activity $started".take(256)
        val grants = listOf(reviewPrivacyGrant, actionPrivacyGrant)
        val requestId = ++reviewWriteRequestId
        _state.update {
            it.copy(
                review = it.review.copy(
                    savingClipItemId = item.id,
                    savedClipItemId = null,
                    savedClipMessage = null,
                    detailErrorMessage = null,
                    actionError = it.review.actionError?.takeUnless { error -> error.itemId == item.id && error.action == ReviewAction.SAVE_CLIP },
                ),
            )
        }
        viewModelScope.launch {
            if (!revalidateReviewWrite(requestId, profile, grants)) return@launch
            runCatching { repository.saveReviewClip(profile, allowed, item, clipName) }
                .onSuccess {
                    if (!revalidateReviewWrite(requestId, profile, grants)) return@onSuccess
                    _state.update { state ->
                        state.copy(
                            review = state.review.afterClipSaved(item.id),
                            exports = ExportsUiState(),
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) { clearReviewWrite(requestId); throw error }
                    if (!revalidateReviewWrite(requestId, profile, grants)) return@onFailure
                    if (error is AuthenticationExpiredException) {
                        handleConnectedFailure(error)
                    } else {
                        logger.warning("Saving Frigate recording clip failed", error)
                        _state.update { state ->
                            state.copy(
                                review = state.review.copy(
                                    savingClipItemId = null,
                                    detailErrorMessage = error.toOpahFailure().userMessage,
                                    actionError = ReviewActionError(item.id, ReviewAction.SAVE_CLIP,
                                        error.toOpahFailure().userMessage),
                                ),
                            )
                        }
                    }
                }
        }
    }

    fun saveReviewAllAngles(item: ReviewItem, cameraNames: Set<String>) {
        val current = _state.value
        val snapshot = current.snapshot ?: return
        if (
            current.review.hasPendingWrite() ||
            !snapshot.capabilities.supports(FrigateFeature.MULTI_CAMERA_EXPORT)
        ) return
        val selected = cameraNames.intersect(snapshot.user.allowedCameras)
        if (selected.isEmpty()) return
        if (selected.size > MAX_BATCH_EXPORT_ITEMS) {
            _state.update { state ->
                state.copy(
                    review = state.review.copy(
                        detailErrorMessage = "Choose no more than $MAX_BATCH_EXPORT_ITEMS camera angles",
                        actionError = ReviewActionError(item.id, ReviewAction.SAVE_ALL_ANGLES,
                            "Choose no more than $MAX_BATCH_EXPORT_ITEMS camera angles"),
                    ),
                )
            }
            return
        }
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    review = it.review.afterClipSaved(item.id, ReviewAction.SAVE_ALL_ANGLES).copy(
                        savedClipMessage = "${selected.size} camera angles are being saved",
                    ),
                )
            }
            return
        }
        val reviewPrivacyGrant = issuePrivacyGrant(
            PrivacySurface.ACTIVITY,
            PrivacyTarget.Review(item.id, item.camera, item.subLabels.isNotEmpty()),
        ) ?: return
        val cameraPrivacyGrant = issuePrivacyGrant(
            PrivacySurface.ACTIVITY,
            PrivacyTarget.CameraCollection("save-all-angles-${item.id}", selected),
        )?.takeIf { it.grant.visibleCameraIds.containsAll(selected) } ?: return
        val actionPrivacyGrant = issuePrivacyGrant(
            PrivacySurface.ACTION,
            PrivacyTarget.LocalAction(app.opah.tv.privacy.PrivacyLocalAction.ADMINISTRATIVE_CHANGE),
        ) ?: return
        val profile = current.activeProfile ?: return
        val end = ((item.endTime ?: (item.startTime + 30.0)) + 2.0)
            .coerceAtMost(System.currentTimeMillis() / 1_000.0)
        val start = (item.startTime - 2.0).coerceAtLeast(0.0)
        if (end <= start) return
        val started = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date((item.startTime * 1_000).toLong()))
        val request = BatchExportRequest(
            items = selected.sorted().map { camera ->
                val cameraLabel = snapshot.authorizedCameraNames[camera] ?: camera.replace('_', ' ')
                BatchExportItem(
                    camera = camera,
                    startTime = start,
                    endTime = end,
                    friendlyName = "$cameraLabel activity $started".take(256),
                    clientItemId = camera.take(128),
                )
            },
        )
        val grants = listOf(reviewPrivacyGrant, cameraPrivacyGrant, actionPrivacyGrant)
        val requestId = ++reviewWriteRequestId
        _state.update {
            it.copy(
                review = it.review.copy(
                    savingClipItemId = item.id,
                    savedClipItemId = null,
                    savedClipMessage = null,
                    detailErrorMessage = null,
                    actionError = it.review.actionError?.takeUnless { error -> error.itemId == item.id && error.action == ReviewAction.SAVE_ALL_ANGLES },
                ),
            )
        }
        viewModelScope.launch {
            if (!revalidateReviewWrite(requestId, profile, grants)) return@launch
            runCatching {
                operationsRepository.startBatchExport(
                    profile,
                    snapshot.user,
                    snapshot.frigateVersion,
                    request,
                )
            }.onSuccess { result ->
                if (!revalidateReviewWrite(requestId, profile, grants)) return@onSuccess
                val outcome = reviewBatchSaveOutcome(selected, result.results)
                val savedCount = outcome.savedCameraNames.size
                val failedCount = outcome.retryCameraNames.size
                val unconfirmedCount = outcome.unconfirmedCameraNames.size
                val feedback = when {
                    unconfirmedCount > 0 -> "$unconfirmedCount camera angles weren’t confirmed · Check Clips before trying them again"
                    failedCount > 0 && savedCount > 0 -> "$savedCount angles are saving · $failedCount could not be started"
                    failedCount > 0 -> "Couldn’t save these camera angles"
                    else -> "$savedCount camera angles are being saved"
                }
                _state.update { state ->
                    state.copy(
                        review = (if (item.camera in outcome.savedCameraNames) state.review.afterClipSaved(item.id, ReviewAction.SAVE_ALL_ANGLES) else
                            state.review.copy(savingClipItemId = null)).copy(
                            savedClipItemId = item.id,
                            savedClipMessage = feedback,
                            detailErrorMessage = feedback.takeIf { failedCount > 0 || unconfirmedCount > 0 },
                            actionError = if (failedCount > 0 || unconfirmedCount > 0) ReviewActionError(
                                item.id, ReviewAction.SAVE_ALL_ANGLES, feedback,
                                cameraNames = outcome.retryCameraNames,
                                canRetry = outcome.retryCameraNames.isNotEmpty(),
                            ) else state.review.actionError?.takeUnless { it.itemId == item.id && it.action == ReviewAction.SAVE_ALL_ANGLES },
                        ),
                        exports = ExportsUiState(),
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) { clearReviewWrite(requestId); throw error }
                if (!revalidateReviewWrite(requestId, profile, grants)) return@onFailure
                if (error is AuthenticationExpiredException) {
                    handleConnectedFailure(error)
                } else {
                    logger.warning("Saving multiple Frigate camera angles failed", error)
                    _state.update { state ->
                        state.copy(
                            review = state.review.copy(
                                savingClipItemId = null,
                                detailErrorMessage = error.toOpahFailure().userMessage,
                                actionError = ReviewActionError(item.id, ReviewAction.SAVE_ALL_ANGLES,
                                    "Saving wasn’t confirmed · Check Clips before trying again", canRetry = false),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun revalidateReviewWrite(requestId: Long, profile: ConnectionProfile, grants: List<BoundPrivacyGrant>): Boolean {
        if (requestId != reviewWriteRequestId) return false
        if (_state.value.activeProfile == profile && grants.all(::revalidatePrivacyGrant)) return true
        clearReviewWrite(requestId)
        return false
    }

    private fun clearReviewWrite(requestId: Long) {
        if (requestId != reviewWriteRequestId) return
        _state.update { state ->
            state.copy(review = state.review.copy(savingClipItemId = null, markingReviewedItemId = null, markingAllReviewed = false))
        }
    }

    fun loadExports(force: Boolean = false) {
        val current = _state.value
        if (BuildConfig.DOCUMENTATION_MODE) return
        val profile = current.activeProfile ?: return
        val snapshot = current.snapshot ?: return
        val allowed = visibleCameraNames(PrivacySurface.CLIP)
        if (current.exports.loading || (!force && current.exports.loadedOnce)) return
        if (allowed.isEmpty()) {
            _state.update {
                it.copy(
                    exports = it.exports.copy(
                        loading = false,
                        loadedOnce = true,
                        items = emptyList(),
                        incidents = emptyList(),
                        incidentsLoaded = true,
                        errorMessage = null,
                        incidentsErrorMessage = null,
                    ),
                )
            }
            return
        }
        exportsLoadJob?.cancel()
        exportsLoadJob = viewModelScope.launch {
            _state.update { it.copy(exports = it.exports.copy(loading = true, errorMessage = null)) }
            runCatching {
                val items = repository.loadExports(profile, allowed)
                val incidents = if (snapshot.capabilities.supports(FrigateFeature.EXPORT_CASES)) {
                    runCatching {
                        operationsRepository.loadIncidents(
                            profile,
                            snapshot.user,
                            snapshot.frigateVersion,
                            current.exports.explicitlyCreatedEmptyIncidentIds,
                        )
                    }
                } else {
                    Result.success(emptyList())
                }
                Triple(items, incidents.getOrDefault(emptyList()), incidents.exceptionOrNull())
            }
                .onSuccess { items ->
                    _state.update {
                        it.copy(
                            exports = it.exports.copy(
                                loading = false,
                                loadedOnce = true,
                                items = items.first,
                                incidents = items.second,
                                incidentsLoaded = true,
                                incidentsErrorMessage = items.third?.toOpahFailure()?.userMessage,
                                errorMessage = null,
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    if (error is AuthenticationExpiredException) {
                        handleConnectedFailure(error)
                    } else {
                        logger.warning("Loading Frigate saved clips failed", error)
                        _state.update {
                            it.copy(
                                exports = it.exports.copy(
                                    loading = false,
                                    loadedOnce = true,
                                    errorMessage = error.toOpahFailure().userMessage,
                                ),
                            )
                        }
                    }
                }
        }
    }

    fun playExport(export: RecordingExport) {
        val current = _state.value
        val profile = current.activeProfile ?: return
        if (export.camera !in current.snapshot?.user?.allowedCameras.orEmpty()) return
        if (
            !BuildConfig.DOCUMENTATION_MODE &&
            issuePrivacyGrant(
                PrivacySurface.CLIP,
                PrivacyTarget.ClipOrIncident(export.id, export.camera),
            ) == null
        ) return
        val url = if (BuildConfig.DOCUMENTATION_MODE) {
            "$DOCUMENTATION_URI_PREFIX${export.camera}?export=${export.id}"
        } else {
            repository.exportPlaybackUrl(profile, export)
        }
        if (url == null) {
            _state.update { it.copy(errorMessage = "This saved clip is not ready yet") }
            return
        }
        _state.update {
            it.copy(
                playback = PlaybackRequest(
                    title = export.name.replace('_', ' '),
                    uri = url,
                    kind = PlaybackKind.RECORDED,
                    cameraName = export.camera,
                    detail = "Saved clip",
                    savedClipId = export.id,
                    recordingStartTime = export.createdAt,
                ),
                activeCameraName = null,
                errorMessage = null,
            )
        }
    }

    fun selectExport(export: RecordingExport) {
        _state.update {
            it.copy(
                exports = it.exports.copy(
                    selectedItemId = export.id,
                    errorMessage = null,
                ),
            )
        }
    }

    fun closeExport() {
        _state.update { it.copy(exports = it.exports.copy(selectedItemId = null, errorMessage = null)) }
    }

    fun deleteExport(export: RecordingExport) {
        val current = _state.value
        if (
            current.exports.deletingItemId != null ||
            current.snapshot?.user?.role?.equals("admin", ignoreCase = true) != true ||
            export.inProgress
        ) return
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    exports = it.exports.copy(
                        items = it.exports.items.filterNot { saved -> saved.id == export.id },
                        selectedItemId = null,
                    ),
                )
            }
            return
        }
        val clipPrivacyGrant = issuePrivacyGrant(
            PrivacySurface.CLIP,
            PrivacyTarget.ClipOrIncident(export.id, export.camera),
        ) ?: return
        val deletePrivacyGrant = issuePrivacyGrant(
            PrivacySurface.ACTION,
            PrivacyTarget.LocalAction(app.opah.tv.privacy.PrivacyLocalAction.DESTRUCTIVE_OPERATION),
        ) ?: return
        val profile = current.activeProfile ?: return
        val allowed = visibleCameraNames(PrivacySurface.CLIP)
        val frigateVersion = current.snapshot.frigateVersion
        _state.update {
            it.copy(
                exports = it.exports.copy(
                    deletingItemId = export.id,
                    selectedItemId = null,
                    errorMessage = null,
                ),
            )
        }
        viewModelScope.launch {
            if (
                !revalidatePrivacyGrant(clipPrivacyGrant) ||
                !revalidatePrivacyGrant(deletePrivacyGrant)
            ) {
                _state.update { state ->
                    state.copy(exports = state.exports.copy(deletingItemId = null))
                }
                return@launch
            }
            val deleteFailure = runCatching {
                repository.deleteExport(profile, allowed, export, frigateVersion)
            }.exceptionOrNull()
            if (deleteFailure is AuthenticationExpiredException) {
                handleConnectedFailure(deleteFailure)
                return@launch
            }
            if (deleteFailure != null && deleteFailure.toOpahFailure().code != OpahErrorCode.NOT_FOUND) {
                logger.warning("Deleting Frigate saved recording failed", deleteFailure)
                _state.update { state ->
                    state.copy(
                        exports = state.exports.copy(
                            deletingItemId = null,
                            errorMessage = deleteFailure.toOpahFailure().userMessage,
                        ),
                    )
                }
                return@launch
            }
            _state.update { state ->
                state.copy(
                    exports = state.exports.copy(
                        items = state.exports.items.filterNot { saved -> saved.id == export.id },
                        deletingItemId = null,
                        loading = true,
                        errorMessage = null,
                    ),
                )
            }
            runCatching { repository.loadExports(profile, allowed) }
                .onSuccess { refreshed ->
                    _state.update { state ->
                        state.copy(
                            exports = state.exports.copy(
                                items = refreshed,
                                loading = false,
                                loadedOnce = true,
                                errorMessage = null,
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    if (error is AuthenticationExpiredException) {
                        handleConnectedFailure(error)
                    } else {
                        logger.warning("Refreshing Frigate saved recordings after deletion failed", error)
                        _state.update { state ->
                            state.copy(
                                exports = state.exports.copy(
                                    loading = false,
                                    errorMessage = "Saved recordings couldn't refresh",
                                ),
                            )
                        }
                    }
                }
        }
    }

    fun renameExport(export: RecordingExport, name: String) {
        val normalized = name.trim().replace(Regex("\\s+"), " ").take(256)
        val current = _state.value
        if (normalized.isEmpty() || current.exports.operationBusy || export.inProgress) return
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update { state ->
                state.copy(
                    exports = state.exports.copy(
                        items = state.exports.items.map { item ->
                            if (item.id == export.id) item.copy(name = normalized) else item
                        },
                        operationMessage = "Clip renamed",
                    ),
                )
            }
            return
        }
        val clipPrivacyGrant = issuePrivacyGrant(
            PrivacySurface.CLIP,
            PrivacyTarget.ClipOrIncident(export.id, export.camera),
        ) ?: return
        val actionPrivacyGrant = issuePrivacyGrant(
            PrivacySurface.ACTION,
            PrivacyTarget.LocalAction(app.opah.tv.privacy.PrivacyLocalAction.ADMINISTRATIVE_CHANGE),
        ) ?: return
        val profile = current.activeProfile ?: return
        val snapshot = current.snapshot ?: return
        runExportOperation("Renaming the clip failed") {
            check(revalidatePrivacyGrant(clipPrivacyGrant)) { "This clip is no longer available" }
            check(revalidatePrivacyGrant(actionPrivacyGrant)) { "This action is locked" }
            operationsRepository.renameExport(
                profile,
                snapshot.user,
                snapshot.frigateVersion,
                export.id,
                normalized,
            )
            _state.update { state ->
                state.copy(
                    exports = state.exports.copy(
                        items = state.exports.items.map { item ->
                            if (item.id == export.id) item.copy(name = normalized) else item
                        },
                        operationMessage = "Clip renamed",
                    ),
                )
            }
        }
    }

    fun createIncident(name: String, description: String?) {
        val normalizedName = name.trim().replace(Regex("\\s+"), " ").take(100)
        val normalizedDescription = description?.trim()?.take(1_000)?.takeIf(String::isNotEmpty)
        val current = _state.value
        if (normalizedName.isEmpty() || current.exports.operationBusy) return
        if (BuildConfig.DOCUMENTATION_MODE) {
            val incident = DocumentationSupport.data.incident(normalizedName, normalizedDescription)
            val id = incident.id
            _state.update { state ->
                state.copy(
                    exports = state.exports.copy(
                        incidents = listOf(
                            incident,
                        ) + state.exports.incidents,
                        explicitlyCreatedEmptyIncidentIds = state.exports.explicitlyCreatedEmptyIncidentIds + id,
                        operationMessage = "Incident created",
                    ),
                )
            }
            return
        }
        val clipPrivacyGrant = issueCompleteCameraCollectionGrant(
            PrivacySurface.CLIP,
            "create-incident",
        ) ?: return
        val actionPrivacyGrant = issuePrivacyGrant(
            PrivacySurface.ACTION,
            PrivacyTarget.LocalAction(app.opah.tv.privacy.PrivacyLocalAction.ADMINISTRATIVE_CHANGE),
        ) ?: return
        val profile = current.activeProfile ?: return
        val snapshot = current.snapshot ?: return
        runExportOperation("Creating the Incident failed") {
            check(revalidatePrivacyGrant(clipPrivacyGrant)) { "Incidents are no longer available" }
            check(revalidatePrivacyGrant(actionPrivacyGrant)) { "This action is locked" }
            val id = operationsRepository.createIncident(
                profile,
                snapshot.user,
                snapshot.frigateVersion,
                IncidentDraft(normalizedName, normalizedDescription),
            )
            _state.update { state ->
                state.copy(
                    exports = state.exports.copy(
                        explicitlyCreatedEmptyIncidentIds =
                            state.exports.explicitlyCreatedEmptyIncidentIds + id,
                        operationMessage = "Incident created",
                    ),
                )
            }
            loadExports(force = true)
        }
    }

    fun updateIncident(incident: ExportIncident, name: String, description: String?) {
        val normalizedName = name.trim().replace(Regex("\\s+"), " ").take(100)
        val normalizedDescription = description?.trim()?.take(1_000)?.takeIf(String::isNotEmpty)
        val current = _state.value
        if (normalizedName.isEmpty() || current.exports.operationBusy) return
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update { state ->
                state.copy(
                    exports = state.exports.copy(
                        incidents = state.exports.incidents.map { item ->
                            if (item.id == incident.id) {
                                item.copy(name = normalizedName, description = normalizedDescription)
                            } else {
                                item
                            }
                        },
                        operationMessage = "Incident updated",
                    ),
                )
            }
            return
        }
        val incidentPrivacyGrant = issueIncidentPrivacyGrant(incident) ?: return
        val actionPrivacyGrant = issuePrivacyGrant(
            PrivacySurface.ACTION,
            PrivacyTarget.LocalAction(app.opah.tv.privacy.PrivacyLocalAction.ADMINISTRATIVE_CHANGE),
        ) ?: return
        val profile = current.activeProfile ?: return
        val snapshot = current.snapshot ?: return
        runExportOperation("Updating the Incident failed") {
            check(revalidatePrivacyGrant(incidentPrivacyGrant)) { "This Incident is no longer available" }
            check(revalidatePrivacyGrant(actionPrivacyGrant)) { "This action is locked" }
            operationsRepository.updateIncident(
                profile,
                snapshot.user,
                snapshot.frigateVersion,
                incident.id,
                IncidentDraft(normalizedName, normalizedDescription),
            )
            loadExports(force = true)
        }
    }

    fun assignExportToIncident(export: RecordingExport, incidentId: String?) {
        val current = _state.value
        if (current.exports.operationBusy || export.inProgress) return
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update { state ->
                state.copy(
                    exports = state.exports.copy(
                        items = state.exports.items.map { item ->
                            if (item.id == export.id) item.copy(incidentId = incidentId) else item
                        },
                        operationMessage = if (incidentId == null) {
                            "Clip removed from Incident"
                        } else {
                            "Clip added to Incident"
                        },
                    ),
                )
            }
            return
        }
        val clipPrivacyGrant = issuePrivacyGrant(
            PrivacySurface.CLIP,
            PrivacyTarget.ClipOrIncident(export.id, export.camera),
        ) ?: return
        val actionPrivacyGrant = issuePrivacyGrant(
            PrivacySurface.ACTION,
            PrivacyTarget.LocalAction(app.opah.tv.privacy.PrivacyLocalAction.ADMINISTRATIVE_CHANGE),
        ) ?: return
        val profile = current.activeProfile ?: return
        val snapshot = current.snapshot ?: return
        runExportOperation("Moving the clip failed") {
            check(revalidatePrivacyGrant(clipPrivacyGrant)) { "This clip is no longer available" }
            check(revalidatePrivacyGrant(actionPrivacyGrant)) { "This action is locked" }
            operationsRepository.reassignExports(
                profile,
                snapshot.user,
                snapshot.frigateVersion,
                setOf(export.id),
                incidentId,
            )
            loadExports(force = true)
        }
    }

    fun deleteIncident(incident: ExportIncident, deleteClips: Boolean) {
        val current = _state.value
        if (current.exports.operationBusy) return
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update { state ->
                state.copy(
                    exports = state.exports.copy(
                        incidents = state.exports.incidents.filterNot { it.id == incident.id },
                        items = if (deleteClips) {
                            state.exports.items.filterNot { it.incidentId == incident.id }
                        } else {
                            state.exports.items.map { item ->
                                if (item.incidentId == incident.id) item.copy(incidentId = null) else item
                            }
                        },
                        explicitlyCreatedEmptyIncidentIds =
                            state.exports.explicitlyCreatedEmptyIncidentIds - incident.id,
                        operationMessage = "Incident deleted",
                    ),
                )
            }
            return
        }
        val incidentCameraIds = current.exports.items.asSequence()
            .filter { it.incidentId == incident.id }
            .map { it.camera }
            .toSet()
        val incidentPrivacyGrant = if (incidentCameraIds.isEmpty()) {
            val cameraIds = current.snapshot?.user?.allowedCameras.orEmpty()
            issuePrivacyGrant(
                PrivacySurface.INCIDENT,
                PrivacyTarget.CameraCollection("incident-${incident.id}", cameraIds),
            )?.takeIf { it.grant.visibleCameraIds.containsAll(cameraIds) }
        } else {
            issuePrivacyGrant(
                PrivacySurface.INCIDENT,
                PrivacyTarget.ClipOrIncident(
                    itemId = incident.id,
                    cameraId = incidentCameraIds.first(),
                    additionalCameraIds = incidentCameraIds.drop(1).toSet(),
                ),
            )
        } ?: return
        val deletePrivacyGrant = issuePrivacyGrant(
            PrivacySurface.ACTION,
            PrivacyTarget.LocalAction(app.opah.tv.privacy.PrivacyLocalAction.DESTRUCTIVE_OPERATION),
        ) ?: return
        val profile = current.activeProfile ?: return
        val snapshot = current.snapshot ?: return
        runExportOperation("Deleting the Incident failed") {
            check(revalidatePrivacyGrant(incidentPrivacyGrant)) { "This Incident is no longer available" }
            check(revalidatePrivacyGrant(deletePrivacyGrant)) { "This action is locked" }
            operationsRepository.deleteIncident(
                profile,
                snapshot.user,
                snapshot.frigateVersion,
                incident.id,
                deleteClips,
            )
            _state.update { state ->
                state.copy(
                    exports = state.exports.copy(
                        explicitlyCreatedEmptyIncidentIds =
                            state.exports.explicitlyCreatedEmptyIncidentIds - incident.id,
                    ),
                )
            }
            loadExports(force = true)
        }
    }

    private fun runExportOperation(failureContext: String, operation: suspend () -> Unit) {
        if (_state.value.exports.operationBusy) return
        _state.update {
            it.copy(
                exports = it.exports.copy(
                    operationBusy = true,
                    operationMessage = null,
                    errorMessage = null,
                ),
            )
        }
        viewModelScope.launch {
            runCatching { operation() }
                .onFailure { error ->
                    if (error is AuthenticationExpiredException) {
                        handleConnectedFailure(error)
                    } else {
                        logger.warning(failureContext, error)
                        _state.update {
                            it.copy(exports = it.exports.copy(errorMessage = error.toOpahFailure().userMessage))
                        }
                    }
                }
            _state.update { it.copy(exports = it.exports.copy(operationBusy = false)) }
        }
    }

    fun cachedReviewImage(item: ReviewItem) = if (BuildConfig.DOCUMENTATION_MODE) {
        documentationImages.review(item)
    } else {
        issuePrivacyGrant(
            PrivacySurface.ACTIVITY,
            PrivacyTarget.Review(item.id, item.camera),
        )?.let { _state.value.activeProfile }
            ?.let { reviewImageRepository.cached(it, item) }
    }

    suspend fun refreshReviewImage(item: ReviewItem, height: Int = 360): Result<ReviewImage> {
        return if (BuildConfig.DOCUMENTATION_MODE) {
            documentationImages.review(item)?.let(Result.Companion::success)
                ?: Result.failure(IllegalStateException("Documentation image is unavailable"))
        } else {
            val profile = _state.value.activeProfile
                ?: return Result.failure(IllegalStateException("No active Frigate connection"))
            val privacyGrant = issuePrivacyGrant(
                PrivacySurface.ACTIVITY,
                PrivacyTarget.Review(item.id, item.camera),
            ) ?: return Result.failure(IllegalStateException("This activity is unavailable"))
            reviewImageRepository.refresh(profile, item, height).let { result ->
                if (result.isSuccess && !revalidatePrivacyGrant(privacyGrant)) {
                    Result.failure(IllegalStateException("This activity is unavailable"))
                } else {
                    result
                }
            }
        }
    }

    fun cachedExportImage(export: RecordingExport) = if (BuildConfig.DOCUMENTATION_MODE) {
        documentationImages.export(export)
    } else {
        issuePrivacyGrant(
            PrivacySurface.CLIP,
            PrivacyTarget.ClipOrIncident(export.id, export.camera),
        )?.let { _state.value.activeProfile }
            ?.let { reviewImageRepository.cached(it, export) }
    }

    suspend fun refreshExportImage(export: RecordingExport, height: Int = 360): Result<ReviewImage> {
        return if (BuildConfig.DOCUMENTATION_MODE) {
            documentationImages.export(export)?.let(Result.Companion::success)
                ?: Result.failure(IllegalStateException("Documentation image is unavailable"))
        } else {
            val profile = _state.value.activeProfile
                ?: return Result.failure(IllegalStateException("No active Frigate connection"))
            val privacyGrant = issuePrivacyGrant(
                PrivacySurface.CLIP,
                PrivacyTarget.ClipOrIncident(export.id, export.camera),
            ) ?: return Result.failure(IllegalStateException("This clip is unavailable"))
            reviewImageRepository.refresh(profile, export, height).let { result ->
                if (result.isSuccess && !revalidatePrivacyGrant(privacyGrant)) {
                    Result.failure(IllegalStateException("This clip is unavailable"))
                } else {
                    result
                }
            }
        }
    }

    suspend fun prepareClipShare(export: RecordingExport): Result<File> {
        val current = _state.value
        val profile = current.activeProfile
            ?: return Result.failure(IllegalStateException("No active Frigate connection"))
        if (export.inProgress || export.camera !in current.snapshot?.user?.allowedCameras.orEmpty()) {
            return Result.failure(IllegalArgumentException("This clip is not ready to share"))
        }
        val privacyGrant = issuePrivacyGrant(
            PrivacySurface.CLIP,
            PrivacyTarget.ClipOrIncident(export.id, export.camera),
        ) ?: return Result.failure(IllegalArgumentException("This clip is not ready to share"))
        val url = repository.exportPlaybackUrl(profile, export)
            ?: return Result.failure(IllegalArgumentException("This clip is not ready to share"))
        _state.update {
            it.copy(
                exports = it.exports.copy(
                    operationBusy = true,
                    operationMessage = "Preparing clip to share",
                    errorMessage = null,
                ),
            )
        }
        return runCatching {
            withContext(Dispatchers.IO) {
                val application = getApplication<Application>()
                val directory = File(application.cacheDir, "shared-clips").apply { mkdirs() }.canonicalFile
                check(directory.parentFile == application.cacheDir.canonicalFile)
                val safeId = export.id.filter(Char::isLetterOrDigit).take(40).ifBlank { "clip" }
                val target = File(directory, "opah-$safeId.mp4").canonicalFile
                val partial = File(directory, ".opah-$safeId-${System.nanoTime()}.part").canonicalFile
                check(target.parentFile == directory)
                check(partial.parentFile == directory)
                try {
                    container.httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                        if (response.code == 401) throw AuthenticationExpiredException()
                        check(response.isSuccessful) { "Frigate could not download this clip" }
                        val declaredLength = response.body.contentLength()
                        check(declaredLength == -1L || declaredLength <= MAX_SHARED_CLIP_BYTES) {
                            "This clip is too large to share from the TV"
                        }
                        response.body.byteStream().use { input ->
                            FileOutputStream(partial, false).use { output ->
                                val buffer = ByteArray(64 * 1_024)
                                var total = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    check(total <= MAX_SHARED_CLIP_BYTES) {
                                        "This clip is too large to share from the TV"
                                    }
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                    }
                    check(!target.exists() || target.delete()) { "The previous shared copy could not be replaced" }
                    check(partial.renameTo(target)) { "The downloaded clip could not be prepared for sharing" }
                    if (!revalidatePrivacyGrant(privacyGrant)) {
                        target.delete()
                        error("This clip is no longer available")
                    }
                    target
                } finally {
                    partial.delete()
                }
            }
        }.onFailure { error ->
            if (error is AuthenticationExpiredException) {
                handleConnectedFailure(error)
            } else {
                logger.warning("Preparing an authenticated clip share failed", error)
                _state.update {
                    it.copy(
                        exports = it.exports.copy(
                            errorMessage = error.message ?: "This clip could not be shared from the TV",
                        ),
                    )
                }
            }
        }.also {
            _state.update { state ->
                state.copy(exports = state.exports.copy(operationBusy = false, operationMessage = null))
            }
        }
    }

    fun chooseMotionSearchCamera(cameraName: String) {
        val cameras = _state.value.snapshot?.cameras.orEmpty()
        if (cameras.none { it.name == cameraName } || _state.value.motionReview.searching) return
        if (
            !BuildConfig.DOCUMENTATION_MODE &&
            issuePrivacyGrant(PrivacySurface.SEARCH, PrivacyTarget.Camera(cameraName)) == null
        ) return
        motionSearchRequestId += 1
        _state.update {
            it.copy(motionReview = it.motionReview.withMotionSearchSelection(cameraName = cameraName))
        }
    }

    fun chooseMotionSearchRegion(regionIndex: Int) {
        if (_state.value.motionReview.searching) return
        motionSearchRequestId += 1
        _state.update {
            it.copy(motionReview = it.motionReview.withMotionSearchSelection(regionIndex = regionIndex))
        }
    }

    fun startMotionSearch(cameraName: String? = null, centerTimeSeconds: Double? = null) {
        val current = _state.value
        val snapshot = current.snapshot ?: return
        if (!snapshot.capabilities.supports(FrigateFeature.MOTION_SEARCH) || current.motionReview.searching) return
        val selectedCamera = cameraName
            ?: current.motionReview.cameraName?.takeIf { name -> snapshot.cameras.any { it.name == name } }
            ?: snapshot.cameras.firstOrNull()?.name
            ?: return
        val end = (centerTimeSeconds?.plus(MOTION_SEARCH_CONTEXT_SECONDS) ?: System.currentTimeMillis() / 1_000.0)
        val start = (centerTimeSeconds?.minus(MOTION_SEARCH_CONTEXT_SECONDS) ?: end - MOTION_SEARCH_WINDOW_SECONDS)
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    motionReview = DocumentationSupport.data.motionReview(
                        selectedCamera, current.motionReview.regionIndex, end,
                    ),
                )
            }
            return
        }
        val privacyGrant = issuePrivacyGrant(
            PrivacySurface.SEARCH,
            PrivacyTarget.Camera(selectedCamera),
        ) ?: return
        val profile = current.activeProfile ?: return
        val request = MotionSearchRequest(
            camera = selectedCamera,
            startTime = start,
            endTime = end,
            polygon = motionSearchPolygon(current.motionReview.regionIndex),
        )
        motionSearchJob?.cancel()
        val requestId = ++motionSearchRequestId
        _state.update {
            it.copy(motionReview = it.motionReview.beginMotionSearch(selectedCamera))
        }
        motionSearchJob = viewModelScope.launch {
            try {
                check(revalidatePrivacyGrant(privacyGrant)) { "This camera is no longer available" }
                val jobId = operationsRepository.startMotionSearch(
                    profile,
                    snapshot.user,
                    snapshot.frigateVersion,
                    request,
                )
                _state.update {
                    if (requestId != motionSearchRequestId) it else {
                        it.copy(motionReview = it.motionReview.copy(jobId = jobId))
                    }
                }
                val pollingStartedAt = System.currentTimeMillis()
                var unknownPolls = 0
                while (true) {
                    check(revalidatePrivacyGrant(privacyGrant)) { "This camera is no longer available" }
                    val status = operationsRepository.loadMotionSearch(
                        profile,
                        snapshot.user,
                        snapshot.frigateVersion,
                        selectedCamera,
                        jobId,
                    )
                    if (!revalidatePrivacyGrant(privacyGrant)) return@launch
                    _state.update {
                        if (requestId != motionSearchRequestId) it else {
                            it.copy(
                                motionReview = it.motionReview.copy(
                                    jobState = status.state,
                                    results = status.results,
                                    progress = status.progress,
                                    errorMessage = status.message.takeIf {
                                        status.state == MotionSearchJobState.FAILED
                                    },
                                ),
                            )
                        }
                    }
                    if (requestId != motionSearchRequestId) return@launch
                    if (status.state in MOTION_SEARCH_TERMINAL_STATES) break
                    unknownPolls = if (status.state == MotionSearchJobState.UNKNOWN) unknownPolls + 1 else 0
                    if (
                        System.currentTimeMillis() - pollingStartedAt >= MOTION_SEARCH_TIMEOUT_MILLIS ||
                        unknownPolls >= MOTION_SEARCH_MAX_UNKNOWN_POLLS
                    ) {
                        runCatching {
                            operationsRepository.cancelMotionSearch(
                                profile,
                                snapshot.user,
                                snapshot.frigateVersion,
                                selectedCamera,
                                jobId,
                            )
                        }
                        _state.update {
                            if (requestId != motionSearchRequestId) it else {
                                it.copy(
                                    motionReview = it.motionReview.copy(
                                        searching = false,
                                        jobState = MotionSearchJobState.FAILED,
                                        errorMessage = if (unknownPolls >= MOTION_SEARCH_MAX_UNKNOWN_POLLS) {
                                            "Frigate no longer recognizes this Motion Search. Try again"
                                        } else {
                                            "Motion Search took too long. Try a shorter time range"
                                        },
                                    ),
                                )
                            }
                        }
                        return@launch
                    }
                    delay(MOTION_SEARCH_POLL_MILLIS)
                }
                _state.update {
                    if (requestId != motionSearchRequestId) it else {
                        it.copy(motionReview = it.motionReview.copy(searching = false))
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (requestId != motionSearchRequestId) return@launch
                if (error is AuthenticationExpiredException) {
                    handleConnectedFailure(error)
                } else {
                    _state.update {
                        if (requestId != motionSearchRequestId) it else {
                            it.copy(
                                motionReview = it.motionReview.copy(
                                    searching = false,
                                    jobState = MotionSearchJobState.FAILED,
                                    errorMessage = error.toOpahFailure().userMessage,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    fun cancelMotionSearch() {
        val current = _state.value
        val jobId = current.motionReview.jobId
        val camera = current.motionReview.cameraName
        val profile = current.activeProfile
        val snapshot = current.snapshot
        motionSearchJob?.cancel()
        motionSearchJob = null
        motionSearchRequestId += 1
        _state.update {
            it.copy(
                motionReview = it.motionReview.copy(
                    searching = false,
                    jobState = MotionSearchJobState.CANCELLED,
                ),
            )
        }
        if (BuildConfig.DOCUMENTATION_MODE || jobId == null || camera == null || profile == null || snapshot == null) {
            return
        }
        viewModelScope.launch {
            runCatching {
                operationsRepository.cancelMotionSearch(
                    profile,
                    snapshot.user,
                    snapshot.frigateVersion,
                    camera,
                    jobId,
                )
            }.onFailure { error -> logger.warning("Cancelling Motion Search failed", error) }
        }
    }

    fun openHistoryAt(cameraName: String, timestamp: Double) {
        _state.update { it.copy(history = it.history.copy(cursorTimeSeconds = timestamp)) }
        loadHistory(cameraName, hourStart(timestamp))
    }

    fun loadHistory(
        cameraName: String? = null,
        requestedHourStartSeconds: Double? = null,
    ) {
        val current = _state.value
        val cameras = if (BuildConfig.DOCUMENTATION_MODE) {
            current.snapshot?.cameras.orEmpty()
        } else {
            val visibleCameraNames = visibleCameraNames(PrivacySurface.HISTORY)
            current.snapshot?.cameras.orEmpty().filter { it.name in visibleCameraNames }
        }
        val selectedCamera = cameraName
            ?: current.history.cameraName?.takeIf { selected -> cameras.any { it.name == selected } }
            ?: cameras.firstOrNull()?.name
            ?: return
        val now = System.currentTimeMillis() / 1000.0
        val cameraChanged = selectedCamera != current.history.cameraName
        if (BuildConfig.DOCUMENTATION_MODE) {
            val summaries = DocumentationSupport.data.historySummaries(now)
            val selectedHour = requestedHistoryHour(
                requestedHourStartSeconds,
                current.history.hourStartSeconds?.takeUnless { cameraChanged }
                    ?: summaries.maxOfOrNull(::recordingHourStart)
                    ?: hourStart(now),
                now,
            )
            val end = (selectedHour + HISTORY_HOUR_SECONDS).coerceAtMost(now)
            _state.update {
                it.copy(
                    history = HistoryBrowserState(
                        cameraName = selectedCamera,
                        hourStartSeconds = selectedHour,
                        segments = DocumentationSupport.data.recordingHistory(selectedHour, end),
                        hourSummaries = summaries,
                        motion = DocumentationSupport.data.motionActivity(selectedCamera, selectedHour),
                        loadedOnce = true,
                        savedSlotKeys = it.history.savedSlotKeys,
                        cursorTimeSeconds = it.history.cursorTimeSeconds,
                    ),
                )
            }
            return
        }
        val profile = current.activeProfile ?: return
        val allowedCameras = cameras.mapTo(mutableSetOf()) { it.name }
        historyLoadJob?.cancel()
        historyLoadJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    history = it.history.copy(
                        cameraName = selectedCamera,
                        hourStartSeconds = requestedHourStartSeconds ?: it.history.hourStartSeconds,
                        summaryLoading = cameraChanged || it.history.hourSummaries.isEmpty(),
                        loading = true,
                        savedMessage = null,
                        errorMessage = null,
                    ),
                )
            }
            runCatching {
                coroutineScope {
                    val summaries = if (cameraChanged || current.history.hourSummaries.isEmpty()) {
                        runCatching {
                            repository.loadRecordingHourSummaries(
                                profile,
                                allowedCameras,
                                selectedCamera,
                                TimeZone.getDefault().id,
                            )
                        }.getOrDefault(emptyList())
                    } else {
                        current.history.hourSummaries
                    }
                    val selectedHour = requestedHistoryHour(
                        requestedHourStartSeconds,
                        current.history.hourStartSeconds?.takeUnless { cameraChanged }
                            ?: summaries.maxOfOrNull(::recordingHourStart)
                            ?: hourStart(now),
                        now,
                    )
                    val end = (selectedHour + HISTORY_HOUR_SECONDS).coerceAtMost(now)
                    historyRangeCache[historyRangeCacheKey(selectedCamera, selectedHour)]?.let { cached ->
                        return@coroutineScope cached.copy(summaries = summaries)
                    }
                    val segments = async {
                        repository.loadRecordingHistory(
                            profile,
                            allowedCameras,
                            selectedCamera,
                            selectedHour,
                            end,
                        )
                    }
                    val motion = async {
                        runCatching {
                            repository.loadMotionActivity(
                                profile,
                                allowedCameras,
                                selectedCamera,
                                selectedHour,
                                end,
                            )
                        }.getOrDefault(emptyList())
                    }
                    HistoryLoadResult(selectedHour, summaries, segments.await(), motion.await())
                }
            }.onSuccess { result ->
                putHistoryRangeCache(selectedCamera, result)
                _state.update {
                    it.copy(
                        history = it.history.copy(
                            hourStartSeconds = result.hourStartSeconds,
                            segments = result.segments,
                            hourSummaries = result.summaries,
                            motion = result.motion,
                            summaryLoading = false,
                            loading = false,
                            loadedOnce = true,
                        ),
                    )
                }
                prefetchAdjacentHistoryRanges(
                    profile = profile,
                    allowedCameras = allowedCameras,
                    cameraName = selectedCamera,
                    centerHourStart = result.hourStartSeconds,
                    nowSeconds = now,
                )
            }.onFailure { error ->
                if (error is AuthenticationExpiredException) {
                    handleConnectedFailure(error)
                } else {
                    logger.warning("Recording history load failed", error)
                    _state.update {
                        it.copy(
                            history = it.history.copy(
                                segments = emptyList(),
                                motion = emptyList(),
                                summaryLoading = false,
                                loading = false,
                                loadedOnce = true,
                                errorMessage = error.toOpahFailure().userMessage,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun moveHistoryHour(offsetHours: Int) {
        if (offsetHours == 0) return
        val current = _state.value.history
        val anchor = current.hourStartSeconds ?: hourStart(System.currentTimeMillis() / 1000.0)
        loadHistory(current.cameraName, anchor + offsetHours * HISTORY_HOUR_SECONDS)
    }

    fun historyPlaybackRequest(): PlaybackRequest? {
        val current = _state.value
        val history = current.history
        val cameraName = history.cameraName ?: return null
        val camera = current.snapshot?.cameras?.firstOrNull { it.name == cameraName } ?: return null
        val rangeStart = history.hourStartSeconds ?: return null
        if (history.segments.isEmpty()) return null
        val rangeEnd = (rangeStart + HISTORY_HOUR_SECONDS)
            .coerceAtMost(System.currentTimeMillis() / 1_000.0)
        if (rangeEnd <= rangeStart) return null
        val request = if (BuildConfig.DOCUMENTATION_MODE) {
            DocumentationSupport.data.historyPlayback(camera, rangeStart, rangeEnd)
        } else {
            issuePrivacyGrant(PrivacySurface.HISTORY, PrivacyTarget.Camera(cameraName)) ?: return null
            val profile = current.activeProfile ?: return null
            PlaybackRequest(
                title = camera.displayName,
                uri = repository.recordingPlaybackUrl(profile, cameraName, rangeStart, rangeEnd),
                kind = PlaybackKind.RECORDED,
                cameraName = cameraName,
                detail = "Earlier recording",
            )
        }
        return request.copy(
            recordingStartTime = rangeStart,
            recordingEndTime = rangeEnd,
        )
    }

    fun playHistorySlot(slot: HistorySlot) {
        val playbackStart = slot.playbackStartTime ?: return
        val current = _state.value
        val cameraName = current.history.cameraName ?: return
        val camera = current.snapshot?.cameras?.firstOrNull { it.name == cameraName } ?: return
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    playback = DocumentationSupport.data.historyPlayback(camera, playbackStart, slot.endTime).copy(
                        recordingStartTime = playbackStart,
                        recordingEndTime = slot.endTime,
                        recordingSaveKey = historySlotKey(cameraName, slot),
                    ),
                    activeCameraName = null,
                )
            }
            return
        }
        issuePrivacyGrant(PrivacySurface.HISTORY, PrivacyTarget.Camera(cameraName)) ?: return
        val profile = current.activeProfile ?: return
        _state.update {
            it.copy(
                playback = PlaybackRequest(
                    title = camera.displayName,
                    uri = repository.recordingPlaybackUrl(profile, cameraName, playbackStart, slot.endTime),
                    kind = PlaybackKind.RECORDED,
                    cameraName = cameraName,
                    detail = "Earlier recording",
                    recordingStartTime = playbackStart,
                    recordingEndTime = slot.endTime,
                    recordingSaveKey = historySlotKey(cameraName, slot),
                ),
                activeCameraName = null,
                errorMessage = null,
            )
        }
    }

    fun saveHistoryClip(request: PlaybackRequest) {
        val current = _state.value
        val key = request.recordingSaveKey ?: return
        val camera = request.cameraName ?: return
        val start = request.recordingStartTime ?: return
        val end = request.recordingEndTime ?: return
        if (
            key in current.history.savedSlotKeys || current.history.savingSlotStartTime != null ||
            end <= start
        ) return
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    history = it.history.copy(
                        savedSlotKeys = it.history.savedSlotKeys + key,
                        savedMessage = "Recording saved in Frigate",
                    ),
                )
            }
            return
        }
        val historyPrivacyGrant = issuePrivacyGrant(
            PrivacySurface.HISTORY,
            PrivacyTarget.Camera(camera),
        ) ?: return
        val actionPrivacyGrant = issuePrivacyGrant(
            PrivacySurface.ACTION,
            PrivacyTarget.LocalAction(app.opah.tv.privacy.PrivacyLocalAction.ADMINISTRATIVE_CHANGE),
        ) ?: return
        val profile = current.activeProfile ?: return
        val allowed = visibleCameraNames(PrivacySurface.HISTORY)
        if (camera !in allowed) return
        val displayName = current.snapshot?.authorizedCameraNames?.get(camera)
            ?: camera.replace('_', ' ')
        val started = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date((start * 1_000).toLong()))
        val name = "$displayName recording $started".take(256)
        _state.update {
            it.copy(history = it.history.copy(savingSlotStartTime = start, savedMessage = null))
        }
        viewModelScope.launch {
            runCatching { repository.saveRecordingClip(profile, allowed, camera, start, end, name) }
                .onSuccess {
                    if (
                        !revalidatePrivacyGrant(historyPrivacyGrant) ||
                        !revalidatePrivacyGrant(actionPrivacyGrant)
                    ) {
                        _state.update { state ->
                            state.copy(history = state.history.copy(savingSlotStartTime = null))
                        }
                        return@onSuccess
                    }
                    _state.update { state ->
                        state.copy(
                            history = state.history.copy(
                                savingSlotStartTime = null,
                                savedSlotKeys = state.history.savedSlotKeys + key,
                                savedMessage = "Recording saved in Frigate",
                            ),
                            exports = ExportsUiState(),
                        )
                    }
                }
                .onFailure { error ->
                    if (error is AuthenticationExpiredException) {
                        handleConnectedFailure(error)
                    } else {
                        logger.warning("Saving a History recording failed", error)
                        _state.update {
                            it.copy(
                                history = it.history.copy(
                                    savingSlotStartTime = null,
                                    savedMessage = error.toOpahFailure().userMessage,
                                ),
                            )
                        }
                    }
                }
        }
    }

    fun searchActivity(text: String, filters: ActivitySearchFilters) {
        val normalized = text.trim()
        if (!BuildConfig.DOCUMENTATION_MODE && visibleCameraNames(PrivacySurface.SEARCH).isEmpty()) return
        if (normalized.isNotEmpty()) {
            updateSettings { settings ->
                settings.copy(
                    recentActivitySearches = sanitizeRecentActivitySearches(
                        listOf(normalized) + settings.recentActivitySearches,
                    ),
                )
            }
        }
        runActivitySearch(normalized, filters, eventId = null, similarLabel = null, append = false)
    }

    fun loadMoreActivitySearch() {
        val search = _state.value.activitySearch
        if (!search.hasMore || search.searching || search.loadingMore) return
        runActivitySearch(
            text = search.query,
            filters = search.filters,
            eventId = search.similarToEventId,
            similarLabel = search.similarToLabel,
            append = true,
        )
    }

    fun findSimilarActivity(item: ReviewItem) {
        val event = item.linkedEvents.firstOrNull() ?: return
        runActivitySearch(
            text = "",
            filters = ActivitySearchFilters(),
            eventId = event.id,
            similarLabel = event.subLabel?.takeIf(String::isNotBlank) ?: event.label,
            append = false,
        )
    }

    private fun runActivitySearch(
        text: String,
        filters: ActivitySearchFilters,
        eventId: String?,
        similarLabel: String?,
        append: Boolean,
    ) {
        val queryText = text.trim()
        if (queryText.isEmpty() && eventId.isNullOrBlank()) return
        val current = _state.value
        val snapshot = current.snapshot ?: return
        if (!snapshot.capabilities.supports(FrigateFeature.SEMANTIC_SEARCH)) return
        val allowedCameras = if (BuildConfig.DOCUMENTATION_MODE) {
            snapshot.user.allowedCameras
        } else {
            visibleCameraNames(PrivacySurface.SEARCH)
        }
        if (allowedCameras.isEmpty()) return
        val cameras = filters.cameraName
            ?.takeIf { it in allowedCameras }
            ?.let(::setOf)
            ?: allowedCameras.takeIf { filters.cameraName == null }
            ?: return
        val page = if (append) current.activitySearch.page + 1 else 1
        val now = System.currentTimeMillis() / 1000.0
        val bounds = activitySearchBounds(filters.timeRange, now)
        val before = if (append) {
            listOfNotNull(
                bounds.beforeSeconds,
                current.activitySearch.results.lastOrNull()?.startTime,
            ).minOrNull()
        } else {
            bounds.beforeSeconds
        }
        val after = bounds.afterSeconds
        val requestId = ++activitySearchRequestId
        if (BuildConfig.DOCUMENTATION_MODE) {
            val results = DocumentationSupport.data.searchEvents(
                queryText.ifBlank { similarLabel.orEmpty() },
                cameras,
            ).filter { event ->
                (filters.label == null || event.label == filters.label) &&
                    (filters.subLabel == null || event.subLabel == filters.subLabel) &&
                    (filters.zone == null || filters.zone in event.zones) &&
                    (filters.recognizedLicensePlate == null ||
                        event.recognizedLicensePlate == filters.recognizedLicensePlate)
            }
            _state.update {
                val previousSearch = it.activitySearch
                it.copy(
                    activitySearch = previousSearch.copy(
                        query = queryText,
                        filters = filters,
                        results = if (append) {
                            (previousSearch.results + results).distinctBy(SearchEvent::id)
                        } else {
                            results
                        },
                        resultGeneration = if (append) previousSearch.resultGeneration else requestId,
                        searching = false,
                        loadingMore = false,
                        searchedOnce = true,
                        page = page,
                        hasMore = results.size >= ACTIVITY_SEARCH_PAGE_SIZE,
                        similarToEventId = eventId,
                        similarToLabel = similarLabel,
                        errorMessage = null,
                    ),
                )
            }
            return
        }
        val profile = current.activeProfile ?: return
        activitySearchJob?.cancel()
        _state.update {
            it.copy(
                activitySearch = it.activitySearch.beginActivitySearch(
                    query = queryText,
                    filters = filters,
                    eventId = eventId,
                    similarLabel = similarLabel,
                    append = append,
                    requestId = requestId,
                ),
            )
        }
        activitySearchJob = viewModelScope.launch {
            runCatching {
                repository.searchEvents(
                    profile = profile,
                    allowedCameras = allowedCameras,
                    query = EventSearchQuery(
                        text = queryText.takeIf(String::isNotBlank),
                        cameras = cameras,
                        after = after,
                        before = before,
                        label = filters.label,
                        subLabel = filters.subLabel,
                        zone = filters.zone,
                        recognizedLicensePlate = filters.recognizedLicensePlate,
                        eventId = eventId,
                        limit = ACTIVITY_SEARCH_PAGE_SIZE,
                    ),
                )
            }.onSuccess { results ->
                _state.update {
                    if (requestId != activitySearchRequestId) return@update it
                    val combined = if (append) {
                        (it.activitySearch.results + results).distinctBy(SearchEvent::id)
                    } else {
                        results
                    }
                    it.copy(
                        activitySearch = it.activitySearch.copy(
                            results = combined,
                            searching = false,
                            loadingMore = false,
                            searchedOnce = true,
                            page = page,
                            hasMore = results.size >= ACTIVITY_SEARCH_PAGE_SIZE,
                        ),
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException || requestId != activitySearchRequestId) {
                    return@onFailure
                }
                if (error is AuthenticationExpiredException) {
                    handleConnectedFailure(error)
                } else {
                    logger.warning("Activity search failed", error)
                    _state.update {
                        if (requestId != activitySearchRequestId) it else {
                            it.copy(
                                activitySearch = it.activitySearch.copy(
                                    results = if (append) it.activitySearch.results else emptyList(),
                                    searching = false,
                                    loadingMore = false,
                                    searchedOnce = true,
                                    errorMessage = error.toOpahFailure().userMessage,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    fun playSearchEvent(event: SearchEvent) {
        val current = _state.value
        val camera = current.snapshot?.cameras?.firstOrNull { it.name == event.camera } ?: return
        val start = (event.startTime - SEARCH_PLAYBACK_PADDING_SECONDS).coerceAtLeast(0.0)
        val end = (event.endTime ?: event.startTime + SEARCH_DEFAULT_DURATION_SECONDS) +
            SEARCH_PLAYBACK_PADDING_SECONDS
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    playback = DocumentationSupport.data.searchPlayback(camera, event).copy(
                        recordingStartTime = start,
                        recordingEndTime = end,
                    ),
                    activeCameraName = null,
                )
            }
            return
        }
        issuePrivacyGrant(PrivacySurface.SEARCH, PrivacyTarget.Camera(event.camera)) ?: return
        val profile = current.activeProfile ?: return
        _state.update {
            it.copy(
                playback = PlaybackRequest(
                    title = "${event.label.replace('_', ' ').replaceFirstChar(Char::uppercase)} at ${camera.displayName}",
                    uri = repository.recordingPlaybackUrl(profile, event.camera, start, end),
                    kind = PlaybackKind.RECORDED,
                    cameraName = event.camera,
                    detail = "Search result",
                    recordingStartTime = start,
                    recordingEndTime = end,
                ),
                activeCameraName = null,
                errorMessage = null,
            )
        }
    }

    fun cachedSearchImage(event: SearchEvent) = if (BuildConfig.DOCUMENTATION_MODE) {
        DocumentationSupport.data.reviewItemForEvent(event)?.let(documentationImages::review)
    } else {
        issuePrivacyGrant(PrivacySurface.SEARCH, PrivacyTarget.Camera(event.camera))
            ?.let { _state.value.activeProfile }
            ?.let { reviewImageRepository.cached(it, event) }
    }

    suspend fun refreshSearchImage(event: SearchEvent, height: Int = 360): Result<ReviewImage> {
        return if (BuildConfig.DOCUMENTATION_MODE) {
            DocumentationSupport.data.reviewItemForEvent(event)?.let(documentationImages::review)
                ?.let(Result.Companion::success)
                ?: Result.failure(IllegalStateException("Documentation image is unavailable"))
        } else {
            val profile = _state.value.activeProfile
                ?: return Result.failure(IllegalStateException("No active Frigate connection"))
            val privacyGrant = issuePrivacyGrant(
                PrivacySurface.SEARCH,
                PrivacyTarget.Camera(event.camera),
            ) ?: return Result.failure(IllegalStateException("This search result is unavailable"))
            reviewImageRepository.refresh(profile, event, height).let { result ->
                if (result.isSuccess && !revalidatePrivacyGrant(privacyGrant)) {
                    Result.failure(IllegalStateException("This search result is unavailable"))
                } else {
                    result
                }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun retrySavedSession() {
        if (_state.value.loading) return
        val profile = _state.value.savedProfile ?: return
        viewModelScope.launch { loadSavedSession(profile, "Reconnecting…") }
    }

    fun showConnectionSetup() {
        if (_state.value.loading) return
        if (_state.value.monitorMode != null) closeMonitorMode()
        briefingRefreshJob?.cancel()
        container.onSignedOut()
        ptzWebSocketClient.disconnect()
        sessionManager.expireSession()
        _state.update {
            it.copy(
                statusMessage = "Sign in to Frigate",
                errorMessage = null,
                activeProfile = null,
                snapshot = null,
                recentActivityLoaded = false,
                playback = null,
                cameraGroupView = null,
                activeCameraName = null,
                ptz = PtzUiState(),
                health = HealthUiState(),
                briefing = BriefingUiState(),
                savedSessionRecoveryAvailable = false,
            )
        }
        clearImageCaches()
    }

    fun sessionExpired(message: String = "The Frigate session expired. Sign in again.") {
        container.onSignedOut()
        sessionManager.expireSession()
        val profile = _state.value.activeProfile ?: _state.value.savedProfile
        if (profile == null) {
            finishSignedOut(message)
            return
        }
        viewModelScope.launch {
            if (!profile.usesUnauthenticatedFrigatePort && !sessionManager.hasSavedCredential()) {
                finishSignedOut(message)
            } else {
                loadSavedSession(profile, "Reconnecting…")
            }
        }
    }

    private suspend fun loadSavedSession(
        profile: ConnectionProfile,
        progressMessage: String = "Connecting…",
    ) {
        beginWork(progressMessage)
        runCatching {
            val user = sessionManager.restore(profile)
            repository.discoverEssential(profile, user)
        }
            .onSuccess { bootstrap ->
                clearImageCaches()
                publishConnected(profile, bootstrap)
                startEnrichment(profile, bootstrap)
            }
            .onFailure { error ->
                if (error is AuthenticationExpiredException) {
                    finishSignedOut(error.message ?: "Sign in to Frigate")
                } else if (error is InvalidCredentialsException) {
                    sessionManager.clearSavedCredential()
                    finishSignedOut("The saved sign-in is no longer valid. Enter the current password.")
                } else {
                    logger.warning("Saved Frigate session restore failed", error)
                    showSavedSessionRecovery(error)
                }
            }
    }

    private fun playStream(
        profile: ConnectionProfile,
        camera: Camera,
        option: LiveStreamOption,
        reason: String,
        compatibilityTest: Boolean = false,
        compatibilityTestVideoOnly: Boolean = false,
        compatibilityTestUseSavedStrategyFirst: Boolean = true,
        preferRtpTcpOverride: Boolean? = null,
    ) {
        if (!BuildConfig.DOCUMENTATION_MODE) {
            val current = _state.value
            val snapshot = current.snapshot
            val device = current.device
            if (snapshot == null || device == null) {
                _state.update { it.copy(errorMessage = "Live playback is still getting ready") }
                return
            }
            runCatching {
                container.singleLivePlaybackCoordinator.prepare(
                    profile = profile,
                    snapshot = snapshot,
                    camera = camera,
                    preferredStreamName = option.streamName,
                    device = device,
                    preferRtpTcp = preferRtpTcpOverride ?: current.settings.preferRtpTcp,
                    useSavedStrategyFirst = compatibilityTestUseSavedStrategyFirst,
                )
            }
                .onSuccess { requestId ->
                    _state.update {
                        it.copy(
                            playback = PlaybackRequest(
                                title = camera.displayName,
                                uri = "",
                                kind = PlaybackKind.LIVE,
                                cameraName = camera.name,
                                detail = "${option.label} • $reason",
                                liveCompatibilityRequestId = requestId.value,
                                compatibilityTest = compatibilityTest,
                                compatibilityTestVideoOnly = compatibilityTestVideoOnly,
                            ),
                            activeCameraName = camera.name,
                            errorMessage = null,
                        )
                    }
                }
                .onFailure {
                    _state.update { state ->
                        state.copy(errorMessage = "This camera could not be prepared for live playback")
                    }
                }
            return
        }

        runCatching { StreamUriFactory.rtsp(profile, option.streamName) }
            .onSuccess { uri ->
                val fallbackOption = if (
                    option.label.contains("low", ignoreCase = true) ||
                    option.streamName.contains("sub", ignoreCase = true)
                ) {
                    null
                } else {
                    streamSelector.select(
                        camera,
                        _state.value.device?.codecs.orEmpty(),
                        StreamPreference.LOW_BANDWIDTH,
                    ).getOrNull()?.option?.takeIf { it.streamName != option.streamName }
                }
                val fallbackUri = fallbackOption?.let {
                    runCatching { StreamUriFactory.rtsp(profile, it.streamName) }.getOrNull()
                }
                _state.update {
                    it.copy(
                        playback = PlaybackRequest(
                            title = camera.displayName,
                            uri = uri,
                            kind = PlaybackKind.LIVE,
                            cameraName = camera.name,
                            detail = "${option.label} • $reason",
                            startupFallbackUri = fallbackUri,
                            startupFallbackDetail = fallbackOption?.let { fallback ->
                                "${fallback.label} • Requested stream produced no first frame within 8 seconds"
                            },
                        ),
                        activeCameraName = camera.name,
                        errorMessage = null,
                    )
                }
            }
            .onFailure { error ->
                _state.update { it.copy(errorMessage = error.message ?: "Invalid RTSP stream URL") }
            }
    }

    private fun beginWork(message: String) {
        _state.update {
            it.copy(
                loading = true,
                statusMessage = message,
                errorMessage = null,
                savedSessionRecoveryAvailable = false,
            )
        }
    }

    private fun beginConnectionWork(message: String) {
        _state.update {
            it.copy(
                loading = true,
                connectionWorkInProgress = true,
                statusMessage = message,
                errorMessage = null,
                savedSessionRecoveryAvailable = false,
            )
        }
    }

    private fun prefetchAdjacentHistoryRanges(
        profile: ConnectionProfile,
        allowedCameras: Set<String>,
        cameraName: String,
        centerHourStart: Double,
        nowSeconds: Double,
    ) {
        historyPrefetchJob?.cancel()
        historyPrefetchJob = viewModelScope.launch {
            listOf(centerHourStart - HISTORY_HOUR_SECONDS, centerHourStart + HISTORY_HOUR_SECONDS)
                .filter { it >= 0.0 && it <= hourStart(nowSeconds) }
                .forEach { adjacentHour ->
                    val key = historyRangeCacheKey(cameraName, adjacentHour)
                    if (key in historyRangeCache) return@forEach
                    val end = (adjacentHour + HISTORY_HOUR_SECONDS).coerceAtMost(nowSeconds)
                    runCatching {
                        val segments = repository.loadRecordingHistory(
                            profile,
                            allowedCameras,
                            cameraName,
                            adjacentHour,
                            end,
                        )
                        val motion = runCatching {
                            repository.loadMotionActivity(
                                profile,
                                allowedCameras,
                                cameraName,
                                adjacentHour,
                                end,
                            )
                        }.getOrDefault(emptyList())
                        HistoryLoadResult(adjacentHour, emptyList(), segments, motion)
                    }.onSuccess { putHistoryRangeCache(cameraName, it) }
                        .onFailure { error ->
                            if (error is AuthenticationExpiredException) handleConnectedFailure(error)
                        }
                }
        }
    }

    private fun putHistoryRangeCache(cameraName: String, result: HistoryLoadResult) {
        val key = historyRangeCacheKey(cameraName, result.hourStartSeconds)
        historyRangeCache.remove(key)
        historyRangeCache[key] = result.copy(summaries = emptyList())
        while (historyRangeCache.size > MAX_HISTORY_RANGE_CACHE_ENTRIES) {
            historyRangeCache.remove(historyRangeCache.keys.first())
        }
    }

    private fun historyRangeCacheKey(cameraName: String, hourStartSeconds: Double): String =
        "$cameraName:${hourStartSeconds.toLong()}"

    private fun publishConnected(profile: ConnectionProfile, bootstrap: DiscoveryBootstrap) {
        container.onAuthenticatedConnection(profile, bootstrap.snapshot.frigateVersion)
        historyPrefetchJob?.cancel()
        historyRangeCache.clear()
        cameraImageFailureCounts.clear()
        _state.update {
            it.copy(
                loading = false,
                connectionWorkInProgress = false,
                statusMessage = "Connected",
                errorMessage = null,
                savedProfile = profile,
                activeProfile = profile,
                snapshot = bootstrap.snapshot,
                recentActivityLoaded = false,
                cameraGroupView = null,
                review = ReviewBrowserState(countsLoading = true),
                history = HistoryBrowserState(),
                activitySearch = ActivitySearchState(),
                information = InformationUiState(),
                ptz = PtzUiState(),
                modes = ModesUiState(),
                liveActions = LiveActionsUiState(),
                motionReview = MotionReviewUiState(),
                health = HealthUiState(),
                briefing = BriefingUiState(),
                savedSessionRecoveryAvailable = false,
            )
        }
        openPendingCamera()
        loadBriefing(refreshFromFrigate = false)
    }

    private fun openPendingCamera() {
        val cameraName = pendingCameraName ?: return
        val current = _state.value
        if (current.activeProfile == null || current.snapshot == null) return
        pendingCameraName = null
        val camera = current.snapshot.cameras.firstOrNull { it.name == cameraName }
        if (camera == null) {
            _state.update {
                it.copy(errorMessage = "That camera is not available for this Frigate account")
            }
            return
        }
        playAutomatic(camera)
    }

    private fun startEnrichment(profile: ConnectionProfile, bootstrap: DiscoveryBootstrap) {
        enrichmentJob?.cancel()
        enrichmentJob = viewModelScope.launch {
            runCatching {
                coroutineScope {
                    val snapshot = async { runCatching { repository.enrich(profile, bootstrap) } }
                    val counts = async {
                        runCatching {
                            repository.loadReviewCounts(profile, bootstrap.snapshot.user.allowedCameras)
                        }
                    }
                    val countResult = counts.await()
                    _state.update { current ->
                        if (current.activeProfile == profile) {
                            current.copy(
                                review = current.review.copy(
                                    counts = countResult.getOrDefault(current.review.counts),
                                    countsLoading = false,
                                ),
                            )
                        } else {
                            current
                        }
                    }
                    snapshot.await().getOrThrow()
                }
            }
                .onSuccess { snapshot ->
                    _state.update { current ->
                        if (current.activeProfile == profile) {
                            current.copy(
                                snapshot = snapshot,
                                recentActivityLoaded = true,
                            )
                        } else {
                            current
                        }
                    }
                    loadBriefing(refreshFromFrigate = true)
                }
                .onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    if (_state.value.activeProfile != profile) return@onFailure
                    if (error is AuthenticationExpiredException) {
                        handleConnectedFailure(error)
                    } else {
                        logger.warning("Frigate background enrichment failed", error)
                        _state.update { current ->
                            val snapshot = current.snapshot ?: return@update current
                            current.copy(
                                review = current.review.copy(countsLoading = false),
                                snapshot = snapshot.copy(
                                    warnings = snapshot.warnings +
                                        "Some Frigate details are still unavailable. Refresh to retry.",
                                ),
                            )
                        }
                    }
                }
        }
    }

    private fun loadBriefing(refreshFromFrigate: Boolean) {
        if (BuildConfig.DOCUMENTATION_MODE) return
        val current = _state.value
        val identity = current.briefingIdentity() ?: run {
            _state.update { it.copy(briefing = BriefingUiState()) }
            return
        }
        val allowedCameras = visibleCameraNames(PrivacySurface.ACTIVITY)
        briefingRefreshJob?.cancel()
        _state.update { it.copy(briefing = it.briefing.copy(loading = true, errorMessage = null)) }
        briefingRefreshJob = viewModelScope.launch {
            runCatching {
                if (refreshFromFrigate) {
                    briefingCoordinator.refresh(
                        BriefingRefreshRequest(
                            profile = identity.profile,
                            profileKey = identity.profileKey,
                            scopeKey = identity.scopeKey,
                            allowedCameraIds = allowedCameras,
                            privacySchemaVersion = BRIEFING_PRIVACY_SCHEMA_VERSION,
                            privacyEpoch = identity.privacyEpoch,
                            recognitionDisclosure = current.effectiveBriefingRecognitionDisclosure(),
                            nowEpochMillis = System.currentTimeMillis().coerceAtLeast(0L),
                        ),
                    )
                } else {
                    briefingCoordinator.readAuthorized(
                        identity.profileKey,
                        identity.scopeKey,
                        allowedCameras,
                        current.effectiveBriefingRecognitionDisclosure(),
                    )
                }
            }.onSuccess { summary ->
                _state.update { state ->
                    if (!state.matchesBriefingIdentity(identity)) return@update state
                    state.copy(
                        briefing = state.briefing.copy(
                            loading = false,
                            summary = summary,
                            errorMessage = null,
                            audience = identity.audience,
                            privacyEpoch = identity.privacyEpoch,
                        ),
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                if (error is AuthenticationExpiredException) {
                    handleConnectedFailure(error)
                } else {
                    logger.warning("Since-you-last-watched briefing failed", error)
                    _state.update { state ->
                        if (!state.matchesBriefingIdentity(identity)) return@update state
                        state.copy(
                            briefing = state.briefing.copy(
                                loading = false,
                                errorMessage = "Your briefing couldn't be refreshed",
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun acknowledgeBriefing(
        entries: Collection<BriefingEntry>,
        reason: BriefingAcknowledgementReason,
        completedHighlightIds: Set<String> = emptySet(),
        dismissalTargets: Collection<BriefingDismissalTarget> = emptyList(),
    ) {
        if (
            BuildConfig.DOCUMENTATION_MODE ||
            (entries.isEmpty() && dismissalTargets.isEmpty())
        ) return
        val identity = _state.value.briefingIdentity() ?: return
        val acknowledgedReviewIds = entries.mapTo(linkedSetOf()) { it.item.id }
        val dismissingSummary = reason == BriefingAcknowledgementReason.DISMISSED
        _state.update { it.copy(briefing = it.briefing.copy(loading = true, errorMessage = null)) }
        viewModelScope.launch {
            runCatching {
                val latest = _state.value
                if (!latest.matchesBriefingIdentity(identity)) return@runCatching null
                val currentlyAllowed = visibleCameraNames(PrivacySurface.ACTIVITY)
                if (reason == BriefingAcknowledgementReason.DISMISSED) {
                    briefingCoordinator.dismissSummary(
                        profileKey = identity.profileKey,
                        scopeKey = identity.scopeKey,
                        targets = dismissalTargets,
                        currentlyAllowedCameraIds = currentlyAllowed,
                        recognitionDisclosure = latest.effectiveBriefingRecognitionDisclosure(),
                        nowEpochMillis = System.currentTimeMillis().coerceAtLeast(0L),
                    )
                } else {
                    briefingCoordinator.acknowledgeVisible(
                        profileKey = identity.profileKey,
                        scopeKey = identity.scopeKey,
                        entries = entries,
                        currentlyAllowedCameraIds = currentlyAllowed,
                        recognitionDisclosure = latest.effectiveBriefingRecognitionDisclosure(),
                        reason = reason,
                        completedHighlightIds = completedHighlightIds,
                        nowEpochMillis = System.currentTimeMillis().coerceAtLeast(0L),
                    )
                }
            }.onSuccess {
                _state.update { state ->
                    if (!state.matchesBriefingIdentity(identity)) return@update state
                    state.copy(
                        briefing = state.briefing.copy(
                            loading = false,
                            summary = if (dismissingSummary) {
                                null
                            } else {
                                state.briefing.summary
                                    ?.afterPresentedEntriesAcknowledged(acknowledgedReviewIds)
                            },
                            activeHighlightIds = state.briefing.activeHighlightIds - acknowledgedReviewIds,
                            errorMessage = null,
                            audience = identity.audience,
                            privacyEpoch = identity.privacyEpoch,
                        ),
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                logger.warning("Saving briefing progress failed", error)
                _state.update { state ->
                    if (!state.matchesBriefingIdentity(identity)) return@update state
                    state.copy(
                        briefing = state.briefing.copy(
                            loading = false,
                            errorMessage = "Briefing progress couldn't be saved",
                        ),
                    )
                }
            }
        }
    }

    private fun Phase0UiState.briefingIdentity(): ActiveBriefingIdentity? {
        val profile = activeProfile ?: return null
        if (snapshot == null || !privacy.available) return null
        val profileKey = container.compatibilityIdentityFactory.derive(
            CompatibilityIdentityDomain.PROFILE,
            profile.compatibilityIdentityComponents(),
        ).value
        val audience = if (privacy.guestModeActive) BriefingAudience.GUEST else BriefingAudience.OWNER
        return ActiveBriefingIdentity(
            profile = profile,
            profileKey = profileKey,
            scopeKey = briefingScopeFactory.derive(
                profileKey,
                audience,
                BRIEFING_PRIVACY_SCHEMA_VERSION,
            ),
            privacyEpoch = privacy.epoch.coerceAtLeast(0L),
            audience = audience,
        )
    }

    private fun Phase0UiState.matchesBriefingIdentity(expected: ActiveBriefingIdentity): Boolean {
        val current = briefingIdentity() ?: return false
        return current.profile == expected.profile &&
            current.profileKey == expected.profileKey &&
            current.scopeKey == expected.scopeKey &&
            current.privacyEpoch == expected.privacyEpoch &&
            current.audience == expected.audience
    }

    private fun Phase0UiState.effectiveBriefingRecognitionDisclosure(): RecognitionDisclosure {
        if (!privacy.guestModeActive) return privacy.ownerRecognitionDisclosure
        val owner = privacy.ownerRecognitionDisclosure
        val guest = privacy.guestRecognitionDisclosure
        return when {
            owner == RecognitionDisclosure.HIDE_ALL || guest == RecognitionDisclosure.HIDE_ALL ->
                RecognitionDisclosure.HIDE_ALL
            owner == RecognitionDisclosure.SHOW_ALL -> guest
            guest == RecognitionDisclosure.SHOW_ALL -> owner
            owner == guest -> owner
            else -> RecognitionDisclosure.HIDE_ALL
        }
    }

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update { it.copy(settings = transform(it.settings)) }
        } else {
            viewModelScope.launch { settingsRepository.update(transform) }
        }
    }

    fun setupLocalPin(
        pin: CharArray,
        confirmation: CharArray,
        protectedScopes: Set<PinScope>,
    ) {
        if (_state.value.privacy.busy) {
            pin.fill('\u0000')
            confirmation.fill('\u0000')
            return
        }
        updatePrivacyBusy()
        viewModelScope.launch {
            val result = pinCredentialService.setup(
                pin = pin,
                confirmation = confirmation,
                protectedScopes = protectedScopes,
            )
            val (status, error) = when (result) {
                PinSetupResult.Created -> "PIN is ready" to null
                PinSetupResult.AlreadyConfigured -> null to "A PIN is already set"
                PinSetupResult.ConfirmationMismatch -> null to "PINs don't match"
                PinSetupResult.Malformed -> null to "Use 4 to 8 numbers"
                PinSetupResult.Trivial -> null to "Choose a less predictable PIN"
                PinSetupResult.Unavailable -> null to "PIN couldn't be saved"
            }
            _state.update {
                it.copy(privacy = it.privacy.copy(busy = false, statusMessage = status, errorMessage = error))
            }
        }
    }

    fun setPinScope(scope: PinScope, enabled: Boolean) {
        if (scope == PinScope.EXIT_GUEST_MODE || _state.value.privacy.busy) return
        val currentScopes = _state.value.privacy.protectedScopes
        val updatedScopes = if (enabled) currentScopes + scope else currentScopes - scope
        updatePrivacyBusy()
        viewModelScope.launch {
            val (status, error) = when (
                pinCredentialService.updateProtectedScopes(updatedScopes)
            ) {
                PinProtectionChangeResult.Updated -> "PIN locks updated" to null
                PinProtectionChangeResult.Unchanged -> null to null
                PinProtectionChangeResult.Locked -> null to "Enter the PIN before changing locks"
                PinProtectionChangeResult.Removed,
                PinProtectionChangeResult.Unavailable,
                -> null to "PIN locks couldn't be updated"
            }
            _state.update {
                it.copy(
                    privacy = it.privacy.copy(
                        busy = false,
                        statusMessage = status,
                        errorMessage = error,
                    ),
                )
            }
        }
    }

    fun removeLocalPin(pin: CharArray) {
        if (_state.value.privacy.busy) {
            pin.fill('\u0000')
            return
        }
        updatePrivacyBusy()
        viewModelScope.launch {
            when (val verification = pinCredentialService.verify(pin)) {
                is PinUnlockResult.Unlocked -> {
                    val removed = pinCredentialService.removeVerified()
                    _state.update {
                        it.copy(
                            privacy = it.privacy.copy(
                                busy = false,
                                statusMessage = "PIN removed".takeIf {
                                    removed == PinProtectionChangeResult.Removed
                                },
                                errorMessage = "PIN couldn't be removed".takeUnless {
                                    removed == PinProtectionChangeResult.Removed
                                },
                            ),
                        )
                    }
                }
                else -> publishPinUnlockFailure(verification)
            }
        }
    }

    fun unlockPrivacy(pin: CharArray) {
        if (_state.value.privacy.busy) {
            pin.fill('\u0000')
            return
        }
        updatePrivacyBusy()
        viewModelScope.launch {
            when (val result = pinCredentialService.verify(pin)) {
                is PinUnlockResult.Unlocked -> _state.update {
                    it.copy(
                        privacy = it.privacy.copy(
                            busy = false,
                            unlockedScopes = result.proof.scopes,
                            pinSelectionsUnlocked = true,
                            statusMessage = "Unlocked",
                            errorMessage = null,
                        ),
                    )
                }
                else -> publishPinUnlockFailure(result)
            }
        }
    }

    fun setCameraPrivate(cameraId: String, private: Boolean) {
        val current = _state.value.privacy.privateCameraIds
        val updated = if (private) current + cameraId else current - cameraId
        launchPrivacyMutation(
            successMessage = if (private) "Camera is private" else "Camera is visible",
        ) { privacyRepository.setPrivateCameras(updated) }
    }

    fun lockPrivacyNow() {
        launchPrivacyMutation("Locked") { privacyRepository.relock(PinRelockReason.MANUAL) }
    }

    fun refreshPrivacyUnlockState() {
        val privacy = _state.value.privacy
        if (!privacy.available) return
        val proof = container.pinUnlockSessionOwner.currentProof(
            privacy.epoch,
            SystemClock.elapsedRealtime(),
        )
        _state.update {
            it.copy(
                privacy = it.privacy.copy(
                    unlockedScopes = proof?.scopes.orEmpty(),
                    pinSelectionsUnlocked = proof != null,
                ),
            )
        }
    }

    private fun issuePrivacyGrant(
        surface: PrivacySurface,
        target: PrivacyTarget,
    ): BoundPrivacyGrant? {
        if (BuildConfig.DOCUMENTATION_MODE) return null
        val current = _state.value
        val discovery = current.snapshot ?: return null
        val privacySnapshot = privacyRepository.snapshot(
            session = PrivacySessionEvidence(
                authenticated = current.activeProfile != null,
                authorizationFreshness = AuthorizationFreshness.FRESH,
                allowedCameraIds = discovery.user.allowedCameras,
            ),
            nowMonotonicMillis = SystemClock.elapsedRealtime(),
        )
        val request = PrivacyRequest(
            surface = surface,
            target = target,
            expectedPrivacyEpoch = privacySnapshot.epoch,
        )
        val grant = when (val decision = privacyDecisionEngine.decide(request, privacySnapshot)) {
            is PrivacyDecision.Allow -> decision.grant
            is PrivacyDecision.AllowRedacted -> decision.grant
            is PrivacyDecision.RequirePin,
            is PrivacyDecision.Deny,
            -> null
        } ?: return null
        return BoundPrivacyGrant(request, grant)
    }

    private fun revalidatePrivacyGrant(bound: BoundPrivacyGrant): Boolean {
        val current = _state.value
        val discovery = current.snapshot ?: return false
        val privacySnapshot = privacyRepository.snapshot(
            session = PrivacySessionEvidence(
                authenticated = current.activeProfile != null,
                authorizationFreshness = AuthorizationFreshness.FRESH,
                allowedCameraIds = discovery.user.allowedCameras,
            ),
            nowMonotonicMillis = SystemClock.elapsedRealtime(),
        )
        return when (
            privacyDecisionEngine.revalidate(bound.grant, bound.request, privacySnapshot)
        ) {
            is PrivacyDecision.Allow,
            is PrivacyDecision.AllowRedacted,
            -> true
            is PrivacyDecision.RequirePin,
            is PrivacyDecision.Deny,
            -> false
        }
    }

    private fun visibleCameraNames(surface: PrivacySurface): Set<String> {
        val cameraNames = _state.value.snapshot?.user?.allowedCameras.orEmpty()
        if (cameraNames.isEmpty()) return emptySet()
        if (BuildConfig.DOCUMENTATION_MODE) return cameraNames
        return issuePrivacyGrant(
            surface = surface,
            target = PrivacyTarget.CameraCollection("ui-camera-scope", cameraNames),
        )?.grant?.visibleCameraIds.orEmpty()
    }

    private fun issueCompleteCameraCollectionGrant(
        surface: PrivacySurface,
        collectionId: String,
    ): BoundPrivacyGrant? {
        val cameraIds = _state.value.snapshot?.user?.allowedCameras.orEmpty()
        if (cameraIds.isEmpty()) return null
        return issuePrivacyGrant(
            surface,
            PrivacyTarget.CameraCollection(collectionId, cameraIds),
        )?.takeIf { it.grant.visibleCameraIds.containsAll(cameraIds) }
    }

    private fun issueIncidentPrivacyGrant(incident: ExportIncident): BoundPrivacyGrant? {
        val cameraIds = _state.value.exports.items.asSequence()
            .filter { it.incidentId == incident.id }
            .map { it.camera }
            .toSet()
        return if (cameraIds.isEmpty()) {
            issueCompleteCameraCollectionGrant(PrivacySurface.INCIDENT, "incident-${incident.id}")
        } else {
            issuePrivacyGrant(
                PrivacySurface.INCIDENT,
                PrivacyTarget.ClipOrIncident(
                    itemId = incident.id,
                    cameraId = cameraIds.first(),
                    additionalCameraIds = cameraIds.drop(1).toSet(),
                ),
            )
        }
    }

    private fun enforceCurrentPrivacyState() {
        if (BuildConfig.DOCUMENTATION_MODE) return
        clearImageCaches()

        val activityCameras = visibleCameraNames(PrivacySurface.ACTIVITY)
        val historyCameras = visibleCameraNames(PrivacySurface.HISTORY)
        val searchCameras = visibleCameraNames(PrivacySurface.SEARCH)
        val clipCameras = visibleCameraNames(PrivacySurface.CLIP)
        val current = _state.value

        // Every privacy epoch can narrow a still non-empty Activity camera scope.
        // Invalidate before filtering state so a late full load or page cannot
        // reintroduce rows fetched under the previous scope or publish its error.
        cancelReviewLoads()
        if (activityCameras.isEmpty()) {
            reviewDetailJob?.cancel()
            reviewPlaybackNavigationJob?.cancel()
        }
        if (historyCameras.isEmpty()) historyLoadJob?.cancel()
        if (searchCameras.isEmpty()) {
            activitySearchJob?.cancel()
            activitySearchRequestId += 1
            motionSearchJob?.cancel()
            motionSearchRequestId += 1
        }

        val playbackAllowed = current.playback?.let(::privacyAllowsPlayback) ?: true
        val groupAllowed = current.cameraGroupView?.let { group ->
            val cameraNames = group.streams.mapTo(mutableSetOf()) { it.camera.name }
            val grant = issuePrivacyGrant(
                PrivacySurface.VIEW,
                PrivacyTarget.CameraCollection("active-camera-group", cameraNames),
            )
            grant != null && grant.grant.visibleCameraIds.containsAll(cameraNames)
        } ?: true
        val ptzAllowed = current.ptz.cameraName?.let { cameraName ->
            issuePrivacyGrant(PrivacySurface.LIVE_PLAYBACK, PrivacyTarget.Camera(cameraName)) != null &&
                issuePrivacyGrant(
                    PrivacySurface.ACTION,
                    PrivacyTarget.LocalAction(
                        app.opah.tv.privacy.PrivacyLocalAction.ADMINISTRATIVE_CHANGE,
                    ),
                ) != null
        } ?: true

        if (!playbackAllowed || !groupAllowed) container.revokeSingleLivePlayback()
        if (!ptzAllowed) ptzWebSocketClient.disconnect()

        _state.update { state ->
            val historyAllowed = state.history.cameraName == null || state.history.cameraName in historyCameras
            state.copy(
                playback = state.playback.takeIf { playbackAllowed },
                cameraGroupView = state.cameraGroupView.takeIf { groupAllowed },
                activeCameraName = state.activeCameraName?.takeIf { cameraName ->
                    issuePrivacyGrant(
                        PrivacySurface.LIVE_PLAYBACK,
                        PrivacyTarget.Camera(cameraName),
                    ) != null
                },
                ptz = state.ptz.takeIf { ptzAllowed } ?: PtzUiState(),
                review = state.review.afterActivityScopeChanged(activityCameras),
                history = state.history.takeIf { historyAllowed } ?: HistoryBrowserState(),
                activitySearch = state.activitySearch.copy(
                    results = state.activitySearch.results.filter { it.camera in searchCameras },
                ),
                exports = if (clipCameras.isEmpty()) {
                    ExportsUiState(loadedOnce = state.exports.loadedOnce, incidentsLoaded = true)
                } else {
                    state.exports.copy(items = state.exports.items.filter { it.camera in clipCameras })
                },
            )
        }
    }

    private fun privacyAllowsPlayback(playback: PlaybackRequest): Boolean {
        val cameraName = playback.cameraName
        if (cameraName == null) {
            if (playback.kind != PlaybackKind.LIVE) return false
            val cameraNames = _state.value.snapshot?.user?.allowedCameras.orEmpty()
            val grant = issuePrivacyGrant(
                PrivacySurface.VIEW,
                PrivacyTarget.CameraCollection("active-composite", cameraNames),
            ) ?: return false
            return grant.grant.visibleCameraIds.containsAll(cameraNames)
        }
        val request = when {
            playback.activityItemId != null -> PrivacySurface.ACTIVITY to PrivacyTarget.Review(
                playback.activityItemId,
                cameraName,
            )
            playback.savedClipId != null -> PrivacySurface.CLIP to PrivacyTarget.ClipOrIncident(
                playback.savedClipId,
                cameraName,
            )
            playback.detail == "Search result" -> PrivacySurface.SEARCH to PrivacyTarget.Camera(cameraName)
            playback.kind == PlaybackKind.RECORDED -> PrivacySurface.HISTORY to PrivacyTarget.Camera(cameraName)
            else -> PrivacySurface.LIVE_PLAYBACK to PrivacyTarget.Camera(cameraName)
        }
        return issuePrivacyGrant(request.first, request.second) != null
    }

    private fun launchPrivacyMutation(
        successMessage: String,
        operation: suspend () -> PrivacyPolicyMutationResult,
    ) {
        if (_state.value.privacy.busy) return
        val privacy = _state.value.privacy
        container.pinUnlockSessionOwner.recordActivity(
            privacyEpoch = privacy.epoch,
            nowMonotonicMillis = SystemClock.elapsedRealtime(),
        )
        updatePrivacyBusy()
        viewModelScope.launch {
            publishPrivacyMutation(operation(), successMessage)
        }
    }

    private fun publishPrivacyMutation(
        result: PrivacyPolicyMutationResult,
        successMessage: String,
    ) {
        val error = when (result) {
            is PrivacyPolicyMutationResult.Updated,
            is PrivacyPolicyMutationResult.Unchanged,
            -> null
            PrivacyPolicyMutationResult.Rejected -> "That privacy choice isn't valid"
            PrivacyPolicyMutationResult.Unavailable -> "Privacy controls are unavailable"
        }
        _state.update {
            it.copy(
                privacy = it.privacy.copy(
                    busy = false,
                    statusMessage = successMessage.takeIf { error == null },
                    errorMessage = error,
                ),
            )
        }
    }

    private fun publishPinUnlockFailure(result: PinUnlockResult) {
        val message = when (result) {
            is PinUnlockResult.WrongPin -> retryMessage("PIN isn't correct", result.retryAfterMillis)
            is PinUnlockResult.Cooldown -> retryMessage("Try again", result.retryAfterMillis)
            PinUnlockResult.NotConfigured -> "No PIN is set"
            PinUnlockResult.CorruptRecord -> "PIN protection needs recovery"
            PinUnlockResult.Unavailable -> "PIN couldn't be checked"
            is PinUnlockResult.Unlocked -> return
        }
        _state.update {
            it.copy(
                privacy = it.privacy.copy(
                    busy = false,
                    statusMessage = null,
                    errorMessage = message,
                ),
            )
        }
    }

    private fun retryMessage(prefix: String, retryAfterMillis: Long): String =
        if (retryAfterMillis < 1_000L) {
            "$prefix • wait a moment"
        } else {
            val seconds = (retryAfterMillis + 999L) / 1_000L
            "$prefix • wait $seconds seconds"
        }

    private fun updatePrivacyBusy() {
        _state.update {
            it.copy(
                privacy = it.privacy.copy(
                    busy = true,
                    statusMessage = null,
                    errorMessage = null,
                ),
            )
        }
    }

    private fun createProfile(
        rawUrl: String,
        username: String,
        rtspHostOverride: String,
        rtspPortText: String,
    ): ConnectionProfile? {
        val profileResult = ConnectionProfileFactory.create(
            rawApiBaseUrl = rawUrl,
            username = username,
            rtspHostOverride = rtspHostOverride,
            rtspPort = rtspPortText.toIntOrNull() ?: -1,
        )
        return profileResult.getOrElse { error ->
            _state.update { it.copy(errorMessage = error.message ?: "Invalid connection settings") }
            null
        }
    }

    private fun showFailure(error: Throwable) {
        logger.warning("Frigate operation failed", error)
        _state.update {
            it.copy(
                loading = false,
                connectionWorkInProgress = false,
                statusMessage = "Connection failed",
                errorMessage = error.toOpahFailure().userMessage,
            )
        }
    }

    private fun handleConnectedFailure(error: Throwable) {
        if (error is AuthenticationExpiredException) {
            sessionExpired(error.message ?: "The Frigate session expired. Sign in again.")
        } else {
            showFailure(error)
        }
    }

    private fun updateReviewFilters(transform: (ReviewFilters) -> ReviewFilters) {
        _state.update { it.copy(review = it.review.copy(filters = transform(it.review.filters))) }
        loadReview()
    }

    private fun publishReviewStatus(reviewId: String, reviewed: Boolean) {
        if (BuildConfig.DOCUMENTATION_MODE) {
            documentationReviewStatuses[reviewId] = reviewed
        }
        _state.update { state ->
            val removeFromCurrentResults = state.review.collectionReviewStatus.apiValue?.let {
                it != reviewed
            } == true
            val changedItem = state.review.items.firstOrNull { it.id == reviewId }
                ?: state.snapshot?.recentReviewItems?.firstOrNull { it.id == reviewId }
            val countedItem = changedItem?.takeIf(ReviewItem::isInReviewCountWindow)
            state.copy(
                snapshot = state.snapshot?.copy(
                    recentReviewItems = state.snapshot.recentReviewItems.withReviewStatus(reviewId, reviewed),
                ),
                review = state.review.copy(
                    items = state.review.items.afterReviewStatusChanged(
                        reviewId = reviewId,
                        reviewed = reviewed,
                        reviewStatus = state.review.collectionReviewStatus,
                    ),
                    selectedItemId = state.review.selectedItemId?.takeUnless { removeFromCurrentResults && it == reviewId },
                    recordingState = if (removeFromCurrentResults && state.review.selectedItemId == reviewId) {
                        ReviewRecordingState.IDLE
                    } else {
                        state.review.recordingState
                    },
                    markingReviewedItemId = null,
                    counts = state.review.counts.afterReviewStatusChanged(
                        wasReviewed = countedItem?.hasBeenReviewed,
                        reviewed = reviewed,
                        severity = countedItem?.severity,
                    ),
                    playbackItem = state.review.playbackItem?.let { playbackItem ->
                        if (playbackItem.id == reviewId) {
                            playbackItem.copy(hasBeenReviewed = reviewed)
                        } else {
                            playbackItem
                        }
                    },
                    detailErrorMessage = null,
                    actionError = state.review.actionError?.takeUnless { it.itemId == reviewId && it.action == ReviewAction.MARK_REVIEWED },
                ),
            )
        }
    }

    private fun publishReviewStatuses(reviewIds: Set<String>, reviewed: Boolean) {
        if (reviewIds.isEmpty()) return
        if (BuildConfig.DOCUMENTATION_MODE) {
            reviewIds.forEach { reviewId -> documentationReviewStatuses[reviewId] = reviewed }
        }
        _state.update { state ->
            val changed = state.review.items.filter { it.id in reviewIds && it.hasBeenReviewed != reviewed }
            val removeFromCurrentResults = state.review.collectionReviewStatus.apiValue?.let {
                it != reviewed
            } == true
            val updatedItems = state.review.items.map { item ->
                if (item.id in reviewIds) item.copy(hasBeenReviewed = reviewed) else item
            }.let { items ->
                if (removeFromCurrentResults) items.filterNot { it.id in reviewIds } else items
            }
            val updatedCounts = changed.filter(ReviewItem::isInReviewCountWindow)
                .fold(state.review.counts) { counts, item ->
                counts.afterReviewStatusChanged(item.hasBeenReviewed, reviewed, item.severity)
            }
            state.copy(
                snapshot = state.snapshot?.copy(
                    recentReviewItems = state.snapshot.recentReviewItems.map { item ->
                        if (item.id in reviewIds) item.copy(hasBeenReviewed = reviewed) else item
                    },
                ),
                review = state.review.copy(
                    items = updatedItems,
                    counts = updatedCounts,
                    markingAllReviewed = false,
                    markingReviewedItemId = null,
                    selectedItemId = state.review.selectedItemId?.takeUnless {
                        removeFromCurrentResults && it in reviewIds
                    },
                    playbackItem = state.review.playbackItem?.let { playbackItem ->
                        if (playbackItem.id in reviewIds) {
                            playbackItem.copy(hasBeenReviewed = reviewed)
                        } else {
                            playbackItem
                        }
                    },
                    detailErrorMessage = null,
                    actionError = state.review.actionError?.takeUnless { it.itemId in reviewIds && it.action == ReviewAction.MARK_REVIEWED },
                ),
            )
        }
    }

    private fun finishSignedOut(message: String) {
        if (_state.value.monitorMode != null) closeMonitorMode()
        container.revokeSingleLivePlayback()
        container.onSignedOut()
        cancelReviewLoads()
        reviewDetailJob?.cancel()
        reviewPlaybackNavigationJob?.cancel()
        historyLoadJob?.cancel()
        activitySearchJob?.cancel()
        activitySearchRequestId += 1
        motionSearchJob?.cancel()
        motionSearchRequestId += 1
        enrichmentJob?.cancel()
        briefingRefreshJob?.cancel()
        ptzWebSocketClient.disconnect()
        sessionManager.expireSession()
        clearImageCaches()
        _state.update {
            it.copy(
                loading = false,
                statusMessage = "Sign in to Frigate",
                errorMessage = message,
                activeProfile = null,
                snapshot = null,
                recentActivityLoaded = false,
                playback = null,
                cameraGroupView = null,
                activeCameraName = null,
                review = ReviewBrowserState(),
                history = HistoryBrowserState(),
                activitySearch = ActivitySearchState(),
                information = InformationUiState(),
                ptz = PtzUiState(),
                health = HealthUiState(),
                briefing = BriefingUiState(),
                savedSessionRecoveryAvailable = false,
            )
        }
    }

    private fun showSavedSessionRecovery(error: Throwable) {
        if (_state.value.monitorMode != null) closeMonitorMode()
        cancelReviewLoads()
        reviewDetailJob?.cancel()
        reviewPlaybackNavigationJob?.cancel()
        historyLoadJob?.cancel()
        activitySearchJob?.cancel()
        activitySearchRequestId += 1
        motionSearchJob?.cancel()
        motionSearchRequestId += 1
        enrichmentJob?.cancel()
        briefingRefreshJob?.cancel()
        ptzWebSocketClient.disconnect()
        _state.update {
            it.copy(
                loading = false,
                statusMessage = "Frigate is unavailable",
                errorMessage = error.toOpahFailure().userMessage,
                activeProfile = null,
                snapshot = null,
                recentActivityLoaded = false,
                playback = null,
                cameraGroupView = null,
                activeCameraName = null,
                review = ReviewBrowserState(),
                history = HistoryBrowserState(),
                activitySearch = ActivitySearchState(),
                information = InformationUiState(),
                ptz = PtzUiState(),
                health = HealthUiState(),
                briefing = BriefingUiState(),
                savedSessionRecoveryAvailable = it.savedProfile != null,
            )
        }
    }

    private fun clearImageCaches() {
        cameraImageRepository.clear()
        reviewImageRepository.clear()
        cameraImageFailureCounts.clear()
    }

    override fun onCleared() {
        if (_state.value.monitorMode != null) closeMonitorMode()
        ptzWebSocketClient.disconnect()
        super.onCleared()
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            Phase0ViewModel(application) as T
    }
}

private fun ReviewCounts.afterReviewStatusChanged(
    wasReviewed: Boolean?,
    reviewed: Boolean,
    severity: ReviewSeverity?,
): ReviewCounts {
    if (wasReviewed == null || wasReviewed == reviewed) return this
    val delta = if (reviewed) 1 else -1
    return when (severity) {
        ReviewSeverity.ALERT -> copy(reviewedAlerts = (reviewedAlerts + delta).coerceIn(0, totalAlerts))
        ReviewSeverity.DETECTION -> copy(
            reviewedDetections = (reviewedDetections + delta).coerceIn(0, totalDetections),
        )
        ReviewSeverity.SIGNIFICANT_MOTION,
        ReviewSeverity.UNKNOWN,
        null,
        -> this
    }
}

private fun ReviewItem.isInReviewCountWindow(): Boolean =
    BuildConfig.DOCUMENTATION_MODE ||
        startTime >= (System.currentTimeMillis() / 1_000.0) - REVIEW_COUNT_WINDOW_SECONDS

private fun recordingHourStart(summary: RecordingHourSummary): Double {
    val parser = SimpleDateFormat("yyyy-MM-dd HH", Locale.US).apply {
        isLenient = false
        timeZone = TimeZone.getDefault()
    }
    return parser.parse("${summary.day} ${summary.hour.toString().padStart(2, '0')}")
        ?.time
        ?.div(1_000.0)
        ?: 0.0
}

private fun CameraPtzInfo.supports(command: PtzCommand): Boolean = when (command) {
    PtzCommand.MoveLeft,
    PtzCommand.MoveRight,
    PtzCommand.MoveUp,
    PtzCommand.MoveDown,
    -> canMove
    PtzCommand.ZoomIn, PtzCommand.ZoomOut -> canZoom
    PtzCommand.FocusIn, PtzCommand.FocusOut -> canFocus
    PtzCommand.Stop -> true
    is PtzCommand.Preset -> command.name.trim() in presets
}

internal fun cameraHealthMessage(cameraLabel: String, consecutiveFailures: Int): String? =
    if (consecutiveFailures >= CAMERA_HEALTH_FAILURE_THRESHOLD) {
        "$cameraLabel camera picture is unavailable"
    } else {
        null
    }

private const val CAMERA_HEALTH_FAILURE_THRESHOLD = 3

private fun safeMonitorDeadline(now: Long, duration: Long): Long =
    if (now > Long.MAX_VALUE - duration) Long.MAX_VALUE else now + duration

internal fun <T> moveOrderedItem(items: List<T>, item: T, direction: Int): List<T> {
    val from = items.indexOf(item)
    if (from < 0 || direction == 0) return items
    val to = (from + direction).coerceIn(items.indices)
    if (to == from) return items
    return items.toMutableList().apply {
        removeAt(from)
        add(to, item)
    }
}

internal fun modeDisplayName(modeName: String?, modes: List<FrigateMode>): String =
    if (modeName == null) "Default" else modes.firstOrNull { it.name == modeName }?.displayName
        ?: modeName.replace('_', ' ').replaceFirstChar(Char::uppercase)

private const val SEARCH_PLAYBACK_PADDING_SECONDS = 8.0
private const val SEARCH_DEFAULT_DURATION_SECONDS = 30.0
private const val INSTANT_REWIND_SECONDS = 30.0
private const val ON_DEMAND_SAFETY_DURATION_SECONDS = 5 * 60
private const val MOTION_SEARCH_WINDOW_SECONDS = 60 * 60.0
private const val MOTION_SEARCH_CONTEXT_SECONDS = 15 * 60.0
private const val MOTION_SEARCH_POLL_MILLIS = 500L
private const val MONITOR_TICK_MILLIS = 250L
private const val MAX_BATCH_EXPORT_ITEMS = 50
private const val MOTION_SEARCH_TIMEOUT_MILLIS = 2 * 60 * 1_000L
private const val MOTION_SEARCH_MAX_UNKNOWN_POLLS = 3
private const val MAX_HISTORY_RANGE_CACHE_ENTRIES = 6
private const val MAX_SHARED_CLIP_BYTES = 512L * 1_024 * 1_024
private val MOTION_SEARCH_TERMINAL_STATES = setOf(
    MotionSearchJobState.SUCCESS,
    MotionSearchJobState.FAILED,
    MotionSearchJobState.CANCELLED,
)
private const val ACTIVITY_SEARCH_PAGE_SIZE = 30
private const val REVIEW_COUNT_WINDOW_SECONDS = 24 * 60 * 60.0
private const val MIN_CAMERAS_PER_GROUP_VIEW = 2
private const val MAX_CAMERAS_PER_GROUP_VIEW = 4
private const val CAMERA_GROUP_VIEW_TITLE_LENGTH = 40
private const val SAVED_VIEW_NAME_LENGTH = 40
