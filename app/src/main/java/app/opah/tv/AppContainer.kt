package app.opah.tv

import android.content.Context
import android.os.SystemClock
import app.opah.tv.awareness.AwarenessReviewLifecycle
import app.opah.tv.data.FrigateJsonParsers
import app.opah.tv.data.FrigateRepository
import app.opah.tv.data.CameraImageRepository
import app.opah.tv.data.FrigateSessionManager
import app.opah.tv.data.FrigateOperationsRepository
import app.opah.tv.data.ProfileRepository
import app.opah.tv.data.ReviewImageRepository
import app.opah.tv.data.SettingsRepository
import app.opah.tv.data.compatibilityIdentityComponents
import app.opah.tv.data.network.FrigateApiClient
import app.opah.tv.data.network.FrigatePtzWebSocketClient
import app.opah.tv.data.network.PersistentCookieJar
import app.opah.tv.data.persistence.OpahDatabase
import app.opah.tv.data.persistence.RoomPlaybackPersistenceRecoveryAdapter
import app.opah.tv.data.persistence.RoomNotificationLedgerStore
import app.opah.tv.data.persistence.RoomAlertConfigurationStore
import app.opah.tv.data.persistence.RoomBriefingStore
import app.opah.tv.data.persistence.RoomVerifiedPlaybackStrategyStore
import app.opah.tv.data.realtime.AndroidRealtimeNetworkMonitor
import app.opah.tv.data.realtime.ProcessRealtimeRuntime
import app.opah.tv.data.realtime.RealtimeDemandOwner
import app.opah.tv.data.realtime.RealtimeLeaseId
import app.opah.tv.data.realtime.CameraScopeState
import app.opah.tv.data.realtime.RealtimeAuthenticationState
import app.opah.tv.data.realtime.RealtimeProfileState
import app.opah.tv.data.security.AndroidKeystoreCompatibilityHmac
import app.opah.tv.data.security.SecureSessionStore
import app.opah.tv.data.update.AppUpdateRepository
import app.opah.tv.data.update.DataStoreUpdateCheckCache
import app.opah.tv.data.update.GitHubReleaseApiClient
import app.opah.tv.data.update.AppUpdateDownloadClient
import app.opah.tv.data.update.AppUpdatePackageVerifier
import app.opah.tv.data.update.AppUpdatePreparationRepository
import app.opah.tv.device.DeviceMediaCapabilityService
import app.opah.tv.diagnostics.AndroidOpahLogger
import app.opah.tv.domain.StreamSelectionService
import app.opah.tv.playback.Media3LivePlayerFactory
import app.opah.tv.playback.compatibility.CompatibilityIdentityFactory
import app.opah.tv.playback.compatibility.CompatibilityIdentityDomain
import app.opah.tv.playback.compatibility.PlaybackElapsedRealtimeClock
import app.opah.tv.playback.compatibility.PersistedPlaybackPersistenceRecoveryHandler
import app.opah.tv.playback.compatibility.PlaybackPersistencePriorEpochReconciler
import app.opah.tv.playback.compatibility.PlaybackPersistenceStartupRecoveryOwner
import app.opah.tv.playback.compatibility.PlaybackResourcePlanner
import app.opah.tv.playback.compatibility.PlaybackStrategyPersistenceCoordinator
import app.opah.tv.playback.media3.SingleLivePlaybackCoordinator
import app.opah.tv.privacy.KeystorePinCredentialStore
import app.opah.tv.privacy.DataStorePrivacyPolicyStore
import app.opah.tv.privacy.PinRelockReason
import app.opah.tv.privacy.PinCredentialService
import app.opah.tv.privacy.PinUnlockSessionOwner
import app.opah.tv.privacy.PrivacyRepository
import app.opah.tv.privacy.AuthorizationFreshness
import app.opah.tv.privacy.PinScope
import app.opah.tv.privacy.PrivacyDecision
import app.opah.tv.privacy.PrivacyDecisionEngine
import app.opah.tv.privacy.PrivacyDenialReason
import app.opah.tv.privacy.PrivacyRequest
import app.opah.tv.privacy.PrivacySessionEvidence
import app.opah.tv.privacy.PrivacySurface
import app.opah.tv.privacy.PrivacyTarget
import app.opah.tv.notifications.AlertConfigurationRepository
import app.opah.tv.notifications.AlertConfigurationMutationResult
import app.opah.tv.notifications.AlertConfigurationState
import app.opah.tv.notifications.AlertPolicy
import app.opah.tv.notifications.AlertSnooze
import app.opah.tv.notifications.AlertSnoozeScope
import app.opah.tv.notifications.AlertDecisionReason
import app.opah.tv.notifications.AlertNotificationPost
import app.opah.tv.notifications.AlertContentIntentKind
import app.opah.tv.homeassistant.HomeAssistantApiClient
import app.opah.tv.homeassistant.HomeAssistantConnection
import app.opah.tv.homeassistant.android.HomeAssistantSecretStore
import app.opah.tv.homeassistant.android.HomeAssistantNotifications
import app.opah.tv.homeassistant.HomeAssistantDisclosure
import app.opah.tv.notifications.android.TvAlertOverlayPresenter
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import app.opah.tv.notifications.AlertOpenTarget
import app.opah.tv.notifications.NotificationActionKind
import app.opah.tv.notifications.NotificationAlertBehavior
import app.opah.tv.notifications.NotificationClass
import app.opah.tv.notifications.NotificationIdentityFactory
import app.opah.tv.notifications.NotificationPrivacy
import app.opah.tv.notifications.NotificationRenderModel
import app.opah.tv.notifications.NotificationCoordinator
import app.opah.tv.notifications.ProcessAlertNotificationOwner
import app.opah.tv.notifications.ProcessAlertModeOwner
import app.opah.tv.notifications.TvAlertOpenResolution
import app.opah.tv.notifications.TvAlertOpenPreference
import app.opah.tv.notifications.frigateOverlayMayPresent
import app.opah.tv.notifications.AlertEvaluationInput
import app.opah.tv.notifications.AlertPrivacyContext
import app.opah.tv.notifications.AlertNotificationLedger
import app.opah.tv.notifications.AlertEvaluator
import app.opah.tv.notifications.android.AndroidAlertNotificationPlatform
import app.opah.tv.notifications.android.AndroidNotificationChannels
import app.opah.tv.notifications.android.TvAlertActivationResult
import app.opah.tv.notifications.android.TvAlertServiceController
import app.opah.tv.notifications.android.TvAlertStartResult
import app.opah.tv.notifications.android.TvAlertDeliveryStatus
import app.opah.tv.notifications.android.TvAlertOverlayPermission
import app.opah.tv.notifications.android.TvAlertOverlaySettings
import app.opah.tv.notifications.android.TvAlertOverlaySettingsStore
import app.opah.tv.notifications.android.LOCAL_TEST_THUMBNAIL_CONTENT_VERSION
import app.opah.tv.briefing.BriefingCoordinator
import app.opah.tv.briefing.BriefingScopeFactory
import app.opah.tv.briefing.FrigateBriefingReviewSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val playbackPersistenceRecoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playbackProcessScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val privacyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val realtimeProcessScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val alertProcessScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val homeAssistantProcessScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val monitorPresentedReviewId = MutableStateFlow<String?>(null)
    val secureSessionStore = SecureSessionStore(appContext)
    internal val pinCredentialStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        KeystorePinCredentialStore(appContext)
    }
    internal val pinUnlockSessionOwner = PinUnlockSessionOwner()
    private val privacyRepositoryDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PrivacyRepository(
            policyStore = DataStorePrivacyPolicyStore(appContext),
            pinStore = pinCredentialStore,
            unlockSessions = pinUnlockSessionOwner,
        )
    }
    internal val privacyRepository: PrivacyRepository
        get() = privacyRepositoryDelegate.value
    internal val pinCredentialService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PinCredentialService(
            store = pinCredentialStore,
            privacyRepository = privacyRepository,
            unlockSessions = pinUnlockSessionOwner,
            monotonicClockMillis = SystemClock::elapsedRealtime,
        )
    }

    val cookieJar = PersistentCookieJar(secureSessionStore)
    val httpClient = FrigateApiClient.defaultClient(cookieJar)
    val apiClient = FrigateApiClient(httpClient, cookieJar)
    val ptzWebSocketClient = FrigatePtzWebSocketClient(httpClient)
    val profileRepository = ProfileRepository(appContext)
    val sessionManager = FrigateSessionManager(
        gateway = apiClient,
        cookieStore = cookieJar,
        credentialStore = secureSessionStore,
        profileStore = profileRepository,
    )
    val frigateRepository = FrigateRepository(apiClient, FrigateJsonParsers())
    val frigateOperationsRepository = FrigateOperationsRepository(
        api = apiClient,
        baseParsers = FrigateJsonParsers(),
        invalidateCapabilities = frigateRepository::invalidateCapabilities,
    )
    val settingsRepository = SettingsRepository(appContext)
    internal val notificationPause = app.opah.tv.notifications.SharedNotificationPause(
        app.opah.tv.notifications.android.AndroidNotificationPauseStore(appContext), System::currentTimeMillis,
        SystemClock::elapsedRealtime, { runCatching { android.provider.Settings.Global.getInt(appContext.contentResolver,
            android.provider.Settings.Global.BOOT_COUNT) }.getOrDefault(0) })
    private val notificationPauseMutation = kotlinx.coroutines.sync.Mutex()

    internal suspend fun changeSharedNotificationPause(minutes: Int?): Boolean {
        notificationPauseMutation.lock()
        try {
            return kotlinx.coroutines.withContext(Dispatchers.IO) {
                if (!canManageConnectionSetup()) return@withContext false
                val changed = if (minutes == null) notificationPause.resume() else notificationPause.pause(minutes)
                if (notificationPause.snapshot().paused) clearPausedPresentations()
                changed
            }
        } finally { notificationPauseMutation.unlock() }
    }
    private fun clearPausedPresentations() {
        alertNotificationPlatform.clearForSharedPause()
        homeAssistantNotifications.clearAll()
        tvAlertOverlays.dismissAll()
    }
    private val tvAlertOverlays by lazy {
        TvAlertOverlayPresenter(appContext).also { presenter ->
            alertProcessScope.launch {
                kotlinx.coroutines.flow.combine(privacyRepository.state, realtimeRuntime.owner.state, alertConfigurationRepository.state) {
                    _, _, _ -> Unit
                }.collect { presenter.revalidate() }
            }
        }
    }
    internal val homeAssistantSecretStore by lazy { HomeAssistantSecretStore(appContext) }
    @Volatile private var homeAssistantHasFrigateProfile: Boolean? = null
    internal val homeAssistantNotifications: HomeAssistantNotifications by lazy {
        HomeAssistantNotifications(appContext, homeAssistantSecretStore, tvAlertOverlays, settingsRepository.settings,
            richPolicy = ::homeAssistantRichPolicy,
            connected = { homeAssistantConnection.state.value.phase == app.opah.tv.homeassistant.HomeAssistantConnectionPhase.CONNECTED },
            disclosure = { homeAssistantRichPolicy().disclosure },
            pause = notificationPause,
        ).also { platform ->
            homeAssistantProcessScope.launch {
                val cameraPrivacy = realtimeRuntime.owner.state.map {
                    listOf(it.generation, it.profileState, it.authenticationState, it.cameraScope, it.authorizationScopeEpoch)
                }.distinctUntilChanged()
                kotlinx.coroutines.flow.combine(privacyRepository.state, cameraPrivacy, profileRepository.hasSavedProfile) {
                    _, _, hasProfile -> homeAssistantHasFrigateProfile = hasProfile
                }.drop(1).collect { platform.clearAll() }
            }
        }
    }

    internal fun homeAssistantRichPolicy(): app.opah.tv.homeassistant.HomeAssistantRichPolicy {
        val ready = privacyRepository.state.value as? app.opah.tv.privacy.PrivacyRepositoryState.Ready
            ?: return app.opah.tv.homeassistant.HomeAssistantRichPolicy()
        if (ready.pinRecordCorrupt) return app.opah.tv.homeassistant.HomeAssistantRichPolicy(epoch = ready.policy.epoch)
        return app.opah.tv.homeassistant.homeAssistantRichPolicy(notificationActionPrivacy(), homeAssistantHasFrigateProfile,
            realtimeRuntime.owner.currentState())
    }

    internal fun canManageConnectionSetup(): Boolean {
        val snapshot = notificationActionPrivacy()
        val decision = PrivacyDecisionEngine().decide(PrivacyRequest(PrivacySurface.SETTINGS,
            PrivacyTarget.LocalAction(app.opah.tv.privacy.PrivacyLocalAction.OPEN_SETTINGS), snapshot.epoch), snapshot)
        return decision is PrivacyDecision.Allow || decision is PrivacyDecision.AllowRedacted
    }
    internal fun showSetupPreview(identity: app.opah.tv.notifications.AndroidNotificationIdentity,
        appearance: app.opah.tv.data.model.AppSettings, active: () -> Boolean, shown: () -> Unit) {
        tvAlertOverlays.showSetupPreview(identity, appearance, { !notificationPause.snapshot().paused && active() && canManageConnectionSetup() }, shown)
    }
    internal fun dismissSetupPreview(identity: app.opah.tv.notifications.AndroidNotificationIdentity) { tvAlertOverlays.dismiss(identity) }
    internal val homeAssistantConnection: HomeAssistantConnection by lazy {
        HomeAssistantConnection(HomeAssistantApiClient(), homeAssistantSecretStore, homeAssistantNotifications, homeAssistantProcessScope, pause = notificationPause)
    }
    private val tvAlertOverlaySettingsStore = TvAlertOverlaySettingsStore(appContext)
    private val updateApiClient = GitHubReleaseApiClient(
        httpClient = GitHubReleaseApiClient.defaultClient(),
        appVersionName = BuildConfig.VERSION_NAME,
    )
    val appUpdateRepository = AppUpdateRepository(
        gateway = updateApiClient,
        cache = DataStoreUpdateCheckCache(appContext),
        installedVersionName = BuildConfig.VERSION_NAME,
    )
    val appUpdatePreparationRepository = AppUpdatePreparationRepository(
        downloadClient = AppUpdateDownloadClient(GitHubReleaseApiClient.downloadClient()),
        packageVerifier = AppUpdatePackageVerifier(appContext),
        updateDirectory = java.io.File(appContext.cacheDir, "updates"),
    )
    val cameraImageRepository = CameraImageRepository(httpClient)
    val reviewImageRepository = ReviewImageRepository(httpClient)
    val logger = AndroidOpahLogger()
    val deviceMediaCapabilityService = DeviceMediaCapabilityService()
    val streamSelectionService = StreamSelectionService()
    val playbackResourcePlanner = PlaybackResourcePlanner()
    val compatibilityIdentityFactory by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CompatibilityIdentityFactory(AndroidKeystoreCompatibilityHmac())
    }
    private val realtimeRuntimeDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ProcessRealtimeRuntime(
            processScope = realtimeProcessScope,
            webSockets = httpClient,
            gateway = apiClient,
            parsers = FrigateJsonParsers(),
            savedProfile = profileRepository::load,
            profileKey = { profile ->
                compatibilityIdentityFactory.derive(
                    CompatibilityIdentityDomain.PROFILE,
                    profile.compatibilityIdentityComponents(),
                ).value
            },
            wallClock = System::currentTimeMillis,
        )
    }
    internal val realtimeRuntime: ProcessRealtimeRuntime
        get() = realtimeRuntimeDelegate.value
    private val realtimeNetworkMonitorDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidRealtimeNetworkMonitor(appContext) { event -> realtimeRuntime.owner.dispatch(event) }
    }
    val opahDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OpahDatabase.create(appContext)
    }
    val verifiedPlaybackStrategyStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomVerifiedPlaybackStrategyStore(opahDatabase)
    }
    val playbackPersistenceRecoveryPort by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomPlaybackPersistenceRecoveryAdapter(opahDatabase)
    }
    val notificationLedgerStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomNotificationLedgerStore(opahDatabase.notificationLedgerDao())
    }
    val alertConfigurationStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomAlertConfigurationStore(opahDatabase.alertConfigurationDao())
    }
    val briefingStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomBriefingStore(opahDatabase.briefingDao())
    }
    internal val briefingScopeFactory by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BriefingScopeFactory(compatibilityIdentityFactory)
    }
    internal val briefingCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BriefingCoordinator(briefingStore, FrigateBriefingReviewSource(frigateRepository))
    }
    private val alertConfigurationRepositoryDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AlertConfigurationRepository(
            store = alertConfigurationStore,
            wallClockMillis = System::currentTimeMillis,
        )
    }
    internal val alertConfigurationRepository: AlertConfigurationRepository
        get() = alertConfigurationRepositoryDelegate.value
    private val alertNotificationPlatform by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidAlertNotificationPlatform(
            context = appContext,
            client = httpClient,
            appearanceSettings = settingsRepository.settings,
            overlays = tvAlertOverlays,
            overlayMayPresent = ::mayPresentFrigateOverlay,
            overlayTargets = ::frigateOverlayTargets,
            pause = notificationPause,
            inlinePreviewAllowed = ::mayPlayFrigateInlinePreview,
            inlinePreview = ::startFrigateInlinePreview,
            profile = { requestedProfileKey ->
                profileRepository.load()?.takeIf { profile ->
                    compatibilityIdentityFactory.derive(
                        CompatibilityIdentityDomain.PROFILE,
                        profile.compatibilityIdentityComponents(),
                    ).value == requestedProfileKey
                }
            },
        )
    }
    private val notificationCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NotificationCoordinator(
            ledgerStore = notificationLedgerStore,
            platform = alertNotificationPlatform,
            pause = notificationPause,
        )
    }
    private val alertModeOwnerDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ProcessAlertModeOwner(
            scope = alertProcessScope,
            transportState = realtimeRuntime.owner.state,
            configuration = alertConfigurationRepository,
            profile = { requestedProfileKey ->
                profileRepository.load()?.takeIf { profile ->
                    compatibilityIdentityFactory.derive(
                        CompatibilityIdentityDomain.PROFILE,
                        profile.compatibilityIdentityComponents(),
                    ).value == requestedProfileKey
                }
            },
            gateway = apiClient,
        )
    }
    private val alertNotificationOwnerDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ProcessAlertNotificationOwner(
            scope = alertProcessScope,
            awareness = realtimeRuntime.awareness,
            configurationRepository = alertConfigurationRepository,
            privacyRepository = privacyRepository,
            transportState = realtimeRuntime.owner.state,
            modeState = alertModeOwnerDelegate.value.state,
            monitorReviewId = monitorPresentedReviewId,
            pauseState = notificationPause.state,
            plateLabels = { requestedProfileKey, camera, detectionIds, authorizedCameraIds ->
                val profile = profileRepository.load()?.takeIf { candidate ->
                    compatibilityIdentityFactory.derive(
                        CompatibilityIdentityDomain.PROFILE,
                        candidate.compatibilityIdentityComponents(),
                    ).value == requestedProfileKey
                } ?: return@ProcessAlertNotificationOwner emptySet()
                frigateRepository.recognizedLicensePlates(
                    profile = profile,
                    allowedCameras = authorizedCameraIds,
                    camera = camera,
                    detectionIds = detectionIds,
                )
            },
            ledgerStore = notificationLedgerStore,
            coordinator = notificationCoordinator,
            wallClockMillis = System::currentTimeMillis,
            monotonicClockMillis = SystemClock::elapsedRealtime,
        )
    }
    val playbackStrategyPersistenceCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PlaybackStrategyPersistenceCoordinator(
            store = verifiedPlaybackStrategyStore,
            clock = PlaybackElapsedRealtimeClock(SystemClock::elapsedRealtime),
            recoveryPort = playbackPersistenceRecoveryPort,
            persistedRecoveryHandler = PersistedPlaybackPersistenceRecoveryHandler { obligation ->
                verifiedPlaybackStrategyStore.recoverPersistedUnresolvedWrite(obligation)
            },
        )
    }
    val playbackPersistenceStartupRecoveryOwner by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PlaybackPersistenceStartupRecoveryOwner(
            scope = playbackPersistenceRecoveryScope,
            recoveryPort = playbackPersistenceRecoveryPort,
            handler = PersistedPlaybackPersistenceRecoveryHandler { obligation ->
                verifiedPlaybackStrategyStore.recoverPersistedUnresolvedWrite(obligation)
            },
            priorEpochReconciler = PlaybackPersistencePriorEpochReconciler {
                verifiedPlaybackStrategyStore.reconcilePriorEpochPendingPersistenceAttempts()
            },
        )
    }
    val livePlayerFactory by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Media3LivePlayerFactory()
    }
    private val singleLivePlaybackCoordinatorDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SingleLivePlaybackCoordinator(
            context = appContext,
            processScope = playbackProcessScope,
            identityFactory = compatibilityIdentityFactory,
            strategyStore = verifiedPlaybackStrategyStore,
            persistence = playbackStrategyPersistenceCoordinator,
        )
    }
    internal val singleLivePlaybackCoordinator: SingleLivePlaybackCoordinator
        get() = singleLivePlaybackCoordinatorDelegate.value

    internal fun revokeSingleLivePlayback() {
        if (singleLivePlaybackCoordinatorDelegate.isInitialized()) {
            singleLivePlaybackCoordinatorDelegate.value.revokeAll()
        }
    }

    internal fun onAppBackgrounded() {
        val revokedGrant = pinUnlockSessionOwner.onAppBackgrounded()
        privacyScope.launch {
            privacyRepository.relock(
                reason = PinRelockReason.APP_BACKGROUND,
                forceEpochAdvance = revokedGrant,
            )
        }
    }

    private val homeAssistantActionUiCount = java.util.concurrent.atomic.AtomicInteger()
    private val protectedPinPromptCount = java.util.concurrent.atomic.AtomicInteger()
    private fun updateProtectedOverlayUi() {
        tvAlertOverlays.protectedActionVisible(homeAssistantActionUiCount.get() > 0 || protectedPinPromptCount.get() > 0)
    }
    internal fun onProtectedPinPromptStarted() {
        protectedPinPromptCount.incrementAndGet()
        updateProtectedOverlayUi()
    }
    internal fun onProtectedPinPromptStopped() {
        protectedPinPromptCount.updateAndGet { (it - 1).coerceAtLeast(0) }
        updateProtectedOverlayUi()
    }
    internal fun onHomeAssistantActionUiStarted() {
        homeAssistantActionUiCount.incrementAndGet()
        updateProtectedOverlayUi()
    }
    internal fun onMainUiStopped() { if (homeAssistantActionUiCount.get() == 0) onAppBackgrounded() }
    internal fun onHomeAssistantActionUiStopped() {
        if (homeAssistantActionUiCount.decrementAndGet() <= 0) {
            homeAssistantActionUiCount.set(0)
            onAppBackgrounded()
            updateProtectedOverlayUi()
        }
    }

    internal fun onSignedOut() {
        alertNotificationPlatform.clearEphemeralContent()
        if (alertNotificationOwnerDelegate.isInitialized()) {
            alertNotificationOwnerDelegate.value.stop()
            alertProcessScope.launch {
                alertNotificationOwnerDelegate.value.purgeActiveProfile()
            }
        }
        if (realtimeRuntimeDelegate.isInitialized()) {
            realtimeRuntimeDelegate.value.signOut()
        }
        if (realtimeNetworkMonitorDelegate.isInitialized()) {
            realtimeNetworkMonitorDelegate.value.stop()
        }
        pinUnlockSessionOwner.onSignOut()
        if (alertConfigurationRepositoryDelegate.isInitialized()) {
            alertConfigurationRepositoryDelegate.value.clearActiveProfile()
        }
        privacyScope.launch {
            privacyRepository.relock(PinRelockReason.SIGN_OUT, forceEpochAdvance = true)
        }
    }

    internal fun startPrivacyInitialization() {
        privacyScope.launch {
            privacyRepository.initialize()
        }
    }

    internal fun onAuthenticatedConnection(
        profile: app.opah.tv.data.model.ConnectionProfile,
        rawFrigateVersion: String,
    ) {
        realtimeRuntime.activateAuthenticatedProfile(profile, rawFrigateVersion)
        realtimeNetworkMonitorDelegate.value.start()
        val profileKey = compatibilityIdentityFactory.derive(
            CompatibilityIdentityDomain.PROFILE,
            profile.compatibilityIdentityComponents(),
        ).value
        alertProcessScope.launch {
            alertConfigurationRepository.initialize(profileKey)
        }
    }

    internal suspend fun setTvAlertPolicy(policy: AlertPolicy): AlertConfigurationMutationResult =
        alertConfigurationRepository.setPolicy(policy)

    internal suspend fun addTvAlertSnooze(snooze: AlertSnooze): AlertConfigurationMutationResult =
        alertConfigurationRepository.addSnooze(snooze)

    internal suspend fun clearTvAlertSnoozes(): AlertConfigurationMutationResult =
        alertConfigurationRepository.clearSnoozes()

    internal fun tvAlertOverlaySettings(): TvAlertOverlaySettings = tvAlertOverlaySettingsStore.load()

    internal fun setTvAlertOverlaySettings(settings: TvAlertOverlaySettings): TvAlertOverlaySettings {
        val saved = tvAlertOverlaySettingsStore.save(settings)
        tvAlertOverlays.dismissAll()
        return saved
    }

    internal suspend fun changeInlineRecordingPreviews(enabled: Boolean): TvAlertOverlaySettings? {
        if (!canManageConnectionSetup() || notificationActionPrivacy().guestMode) return null
        return setTvAlertOverlaySettings(tvAlertOverlaySettingsStore.load().copy(inlineRecordingPreviews = enabled))
    }

    private fun mayPresentFrigateOverlay(request: AlertNotificationPost): Boolean {
        if (notificationPause.snapshot().paused) return false
        val transport = realtimeRuntime.owner.currentState()
        val currentPrivacy = notificationActionPrivacy()
        if (request.contentIntentKind == app.opah.tv.notifications.AlertContentIntentKind.LOCAL_TEST) {
            val ready = privacyRepository.state.value as? app.opah.tv.privacy.PrivacyRepositoryState.Ready ?: return false
            return !ready.pinRecordCorrupt && currentPrivacy.globalNotificationDisclosure != app.opah.tv.privacy.NotificationDisclosure.NEVER &&
                !(currentPrivacy.guestMode && currentPrivacy.guestNotificationDisclosure == app.opah.tv.privacy.NotificationDisclosure.NEVER) &&
                (!currentPrivacy.pinConfigured || PinScope.SETTINGS !in currentPrivacy.protectedScopes ||
                    PinScope.SETTINGS in currentPrivacy.pinUnlockProof?.scopes.orEmpty())
        }
        val process = realtimeRuntime.awareness.state.value
        val configuration = (alertConfigurationRepository.state.value as? AlertConfigurationState.Ready)?.configuration
        val review = process.ledger.review(request.reviewId)
        if (notificationPause.snapshot().suppressesFrigate(review?.startEpochSeconds)) return false
        val profileKey = (transport.profileState as? RealtimeProfileState.Ready)?.profile?.id
        if (!frigateOverlayMayPresent(request, profileKey,
                !process.desynchronized && process.ledger.profileKey == profileKey &&
                    process.authorizedCameraIds == currentPrivacy.allowedCameraIds,
                configuration?.enabled == true, configuration?.policyVersion, review, currentPrivacy)
        ) return false
        val policy = configuration ?: return false
        val mode = alertModeOwnerDelegate.value.state.value
        return AlertEvaluator.evaluate(AlertEvaluationInput(
            review = review ?: return false, authorizedCameraIds = currentPrivacy.allowedCameraIds,
            policy = policy.policy, currentFrigateMode = mode.currentMode.takeIf { mode.fresh && mode.profileKey == profileKey },
            nowEpochMillis = System.currentTimeMillis(), timeZone = java.util.TimeZone.getDefault(), snoozes = policy.snoozes,
            privacy = AlertPrivacyContext(globalPrivacy = request.model.privacy),
            ledger = AlertNotificationLedger(lifecycle = review.lifecycle), recognizedPlateLabels = request.recognizedPlateLabels,
        )).eligible
    }

    private fun notificationActionPrivacy(): app.opah.tv.privacy.PrivacySnapshot {
        val transport = realtimeRuntime.owner.currentState()
        val scope = (transport.cameraScope as? CameraScopeState.Fresh)?.evidence?.takeIf { it.boundsValid }
        return privacyRepository.snapshot(PrivacySessionEvidence(
            authenticated = transport.authenticationState == RealtimeAuthenticationState.AUTHENTICATED,
            authorizationFreshness = if (scope == null) AuthorizationFreshness.UNKNOWN else AuthorizationFreshness.FRESH,
            allowedCameraIds = scope?.allowedCameraIds.orEmpty(),
        ), SystemClock.elapsedRealtime())
    }

    private fun mayPlayFrigateInlinePreview(request: AlertNotificationPost): Boolean {
        if (!mayPresentFrigateOverlay(request)) return false
        val options = tvAlertOverlaySettingsStore.load()
        return app.opah.tv.notifications.frigateInlinePreviewAllowed(request,
            realtimeRuntime.awareness.state.value.ledger.review(request.reviewId), notificationActionPrivacy(),
            options.interactiveFrigateAlerts, options.inlineRecordingPreviews)
    }

    private fun startFrigateInlinePreview(request: AlertNotificationPost, picture: android.graphics.Bitmap?, appearance: app.opah.tv.data.model.AppSettings,
        deadline: Long, openInOpah: () -> Unit) {
        val revision = request.presentationRevision ?: notificationPause.snapshot().revision
        alertProcessScope.launch(Dispatchers.Main.immediate) {
            var ticket: Long? = null
            var handedOver = false
            try {
                val allowed = { notificationPause.snapshot().permits(revision) && mayPlayFrigateInlinePreview(request) }
                handedOver = true
                ticket = tvAlertOverlays.beginInlinePreview(app.opah.tv.notifications.android.InlineRecordingPreview(
                    request.profileKey, request.reviewId, request.identity, request.model.title, appearance, picture, deadline,
                    canPresent = allowed, openInOpah = openInOpah))
                val active = ticket ?: return@launch
                val uri = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val resolved = resolveTvAlertOpen(request.profileKey, request.reviewId, request.actionNonce,
                        TvAlertOpenPreference.RECORDING, consumeNotification = false)
                    val item = (resolved as? TvAlertOpenResolution.RecordedReview)?.item
                    val profile = notificationProfile(request.profileKey)
                    if (item != null && profile != null && allowed()) frigateRepository.reviewPlaybackUrl(profile, item) else null
                }
                if (uri == null || !allowed()) tvAlertOverlays.unavailableInlinePreview(active)
                else tvAlertOverlays.loadInlinePreview(active, uri, httpClient, allowed)
            } catch (error: Exception) {
                if (ticket != null) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { tvAlertOverlays.unavailableInlinePreview(ticket) }
                else if (!handedOver) picture?.recycle()
                if (error is kotlinx.coroutines.CancellationException) throw error
            }
        }
    }

    private suspend fun frigateOverlayTargets(request: AlertNotificationPost): List<TvAlertOpenPreference> {
        if (!mayPresentFrigateOverlay(request)) return emptyList()
        val review = realtimeRuntime.awareness.state.value.ledger.review(request.reviewId) ?: return emptyList()
        val item = notificationReviewItem(review)
        val profile = notificationProfile(request.profileKey)
        val recorded = review.lifecycle == AwarenessReviewLifecycle.ENDED && item != null && profile != null &&
            withTimeoutOrNull(1_500) { frigateRepository.reviewRecordingAvailable(profile, item).getOrDefault(false) } == true
        if (!mayPresentFrigateOverlay(request)) return emptyList()
        return if (recorded) listOf(TvAlertOpenPreference.RECORDING, TvAlertOpenPreference.LIVE)
            else if (review.lifecycle == AwarenessReviewLifecycle.ACTIVE) listOf(TvAlertOpenPreference.LIVE)
            else listOf(TvAlertOpenPreference.ACTIVITY, TvAlertOpenPreference.LIVE)
    }

    private suspend fun notificationProfile(profileKey: String) = profileRepository.load()?.takeIf { profile ->
        compatibilityIdentityFactory.derive(CompatibilityIdentityDomain.PROFILE, profile.compatibilityIdentityComponents()).value == profileKey
    }

    private fun notificationReviewItem(review: app.opah.tv.awareness.AwarenessReview): app.opah.tv.data.model.ReviewItem? {
        val camera = review.camera ?: return null
        val start = review.startEpochSeconds ?: return null
        return app.opah.tv.data.model.ReviewItem(review.id, camera, start, review.endEpochSeconds,
            app.opah.tv.data.model.ReviewSeverity.entries.firstOrNull { it.name == review.severity.name }
                ?: app.opah.tv.data.model.ReviewSeverity.UNKNOWN,
            thumbnailPath = review.thumbnail, objects = review.objects.toList(), zones = review.zones.toList(),
            hasBeenReviewed = review.reviewed == true, detectionIds = review.detectionIds.toList())
    }

    internal suspend fun postLocalTestAlert(includeImage: Boolean): Boolean {
        val configuration = (alertConfigurationRepository.state.value as? AlertConfigurationState.Ready)
            ?.configuration
            ?: return false
        val identities = NotificationIdentityFactory()
        val identity = identities.eventIdentity(configuration.profileKey, LOCAL_TEST_REVIEW_ID)
        alertNotificationPlatform.cancel(identity)
        delay(LOCAL_TEST_REPOST_DELAY_MILLIS)
        return alertNotificationPlatform.post(
            AlertNotificationPost(
                profileKey = configuration.profileKey,
                reviewId = LOCAL_TEST_REVIEW_ID,
                identity = identity,
                actionNonce = identities.newActionNonce(),
                model = NotificationRenderModel(
                    title = "Person at Demo entrance",
                    body = if (includeImage) {
                        "Fictional alert with a test image"
                    } else {
                        "Fictional alert without a camera image"
                    },
                    notificationClass = NotificationClass.ALERT,
                    privacy = if (includeImage) {
                        NotificationPrivacy.FULL_PREVIEW
                    } else {
                        NotificationPrivacy.TEXT_ONLY
                    },
                    openTarget = AlertOpenTarget.ACTIVITY,
                    contentFingerprint = if (includeImage) {
                        "$LOCAL_TEST_FINGERPRINT-image"
                    } else {
                        "$LOCAL_TEST_FINGERPRINT-text"
                    },
                    thumbnailContentVersion = LOCAL_TEST_THUMBNAIL_CONTENT_VERSION.takeIf { includeImage },
                    actions = setOf(NotificationActionKind.OPEN),
                ),
                alertBehavior = NotificationAlertBehavior.AUDIBLE_INITIAL,
                contentIntentKind = AlertContentIntentKind.LOCAL_TEST,
            ),
        )
    }

    internal suspend fun dismissTvAlert(
        profileKey: String,
        reviewId: String,
        actionNonce: String,
    ): Boolean {
        if (!notificationCoordinator.dismiss(profileKey, reviewId, actionNonce, System.currentTimeMillis().coerceAtLeast(0L))) return false
        alertNotificationPlatform.cancel(NotificationIdentityFactory().eventIdentity(profileKey, reviewId))
        alertNotificationPlatform.purgeEphemeralContent(profileKey, reviewId)
        return true
    }

    /** Resolves an opaque notification tap without trusting any display content in the Intent. */
    internal suspend fun resolveTvAlertOpen(
        profileKey: String,
        reviewId: String,
        actionNonce: String,
        preference: TvAlertOpenPreference = TvAlertOpenPreference.DEFAULT,
        consumeNotification: Boolean = true,
    ): TvAlertOpenResolution {
        val presentation = notificationPause.snapshot()
        if (presentation.paused) return TvAlertOpenResolution.Unavailable
        notificationCoordinator.activeActionRecord(profileKey, reviewId, actionNonce)
            ?: return TvAlertOpenResolution.Unavailable

        val transport = realtimeRuntime.owner.currentState()
        val activeProfile = when (val profile = transport.profileState) {
            RealtimeProfileState.Unavailable -> return TvAlertOpenResolution.RetryLater
            is RealtimeProfileState.Invalid -> return TvAlertOpenResolution.Unavailable
            is RealtimeProfileState.Ready -> profile.profile
                .takeIf { it.id == profileKey }
                ?: return TvAlertOpenResolution.Unavailable
        }
        when (transport.authenticationState) {
            RealtimeAuthenticationState.UNKNOWN -> return TvAlertOpenResolution.RetryLater
            RealtimeAuthenticationState.AUTHENTICATED -> Unit
            RealtimeAuthenticationState.REQUIRED,
            RealtimeAuthenticationState.FORBIDDEN,
            -> return TvAlertOpenResolution.Unavailable
        }
        val scope = (transport.cameraScope as? CameraScopeState.Fresh)?.evidence
            ?.takeIf { it.boundsValid }
            ?: return TvAlertOpenResolution.RetryLater
        val allowedCameraIds = scope.allowedCameraIds
        val processState = realtimeRuntime.awareness.state.value
        if (
            processState.desynchronized ||
            processState.ledger.profileKey == null ||
            processState.authorizedCameraIds != allowedCameraIds
        ) {
            return TvAlertOpenResolution.RetryLater
        }
        if (processState.ledger.profileKey != activeProfile.id) {
            return TvAlertOpenResolution.Unavailable
        }

        privacyRepository.initialize()
        val privacySnapshot = privacyRepository.snapshot(
            session = PrivacySessionEvidence(
                authenticated = true,
                authorizationFreshness = AuthorizationFreshness.FRESH,
                allowedCameraIds = allowedCameraIds,
            ),
            nowMonotonicMillis = SystemClock.elapsedRealtime(),
        )
        val review = processState.ledger.review(reviewId)
            ?.takeIf { it.profileKey == profileKey }
        if (notificationPause.snapshot().suppressesFrigate(review?.startEpochSeconds)) return TvAlertOpenResolution.Unavailable
        val resolution = if (review == null) {
            if (allowedCameraIds.isEmpty()) return TvAlertOpenResolution.Unavailable
            if (
                PinScope.ACTIVITY_HISTORY_SEARCH in privacySnapshot.protectedScopes &&
                PinScope.ACTIVITY_HISTORY_SEARCH !in privacySnapshot.pinUnlockProof?.scopes.orEmpty()
            ) {
                return TvAlertOpenResolution.PrivacyBlocked
            }
            val request = PrivacyRequest(
                surface = PrivacySurface.DEEP_LINK,
                target = PrivacyTarget.CameraCollection(
                    collectionId = "expired-notification",
                    cameraIds = allowedCameraIds,
                ),
                expectedPrivacyEpoch = privacySnapshot.epoch,
            )
            when (val decision = PrivacyDecisionEngine().decide(request, privacySnapshot)) {
                is PrivacyDecision.Allow,
                is PrivacyDecision.AllowRedacted,
                -> TvAlertOpenResolution.Activity(expired = true)
                is PrivacyDecision.RequirePin -> return TvAlertOpenResolution.PrivacyBlocked
                is PrivacyDecision.Deny -> return decision.toTvAlertBlockedResolution()
            }
        } else {
            val camera = review.camera
                ?.takeIf(allowedCameraIds::contains)
                ?.takeIf(processState.authorizedCameraIds::contains)
                ?: return TvAlertOpenResolution.Unavailable
            val request = PrivacyRequest(
                surface = PrivacySurface.DEEP_LINK,
                target = if (preference == TvAlertOpenPreference.LIVE) PrivacyTarget.Camera(camera) else PrivacyTarget.Review(
                    reviewId = review.id,
                    cameraId = camera,
                    containsRecognition = review.subLabels.isNotEmpty(),
                ),
                expectedPrivacyEpoch = privacySnapshot.epoch,
            )
            when (val decision = PrivacyDecisionEngine().decide(request, privacySnapshot)) {
                is PrivacyDecision.Allow,
                is PrivacyDecision.AllowRedacted,
                -> when {
                    preference == TvAlertOpenPreference.LIVE -> TvAlertOpenResolution.LiveCamera(camera)
                    preference == TvAlertOpenPreference.ACTIVITY -> TvAlertOpenResolution.Activity(expired = false)
                    preference == TvAlertOpenPreference.DEFAULT && review.lifecycle == AwarenessReviewLifecycle.ACTIVE ->
                        TvAlertOpenResolution.LiveCamera(camera)
                    review.lifecycle == AwarenessReviewLifecycle.ENDED -> {
                        val item = notificationReviewItem(review) ?: return TvAlertOpenResolution.Activity(expired = true)
                        val profile = notificationProfile(profileKey) ?: return TvAlertOpenResolution.Unavailable
                        val available = withTimeoutOrNull(2_000) { frigateRepository.reviewRecordingAvailable(profile, item) }
                            ?: return TvAlertOpenResolution.RetryLater
                        if (available.isFailure) return TvAlertOpenResolution.RetryLater
                        if (available.getOrDefault(false)) TvAlertOpenResolution.RecordedReview(review.id, item.copy(recordingAvailable = true))
                        else TvAlertOpenResolution.Activity(expired = true)
                    }
                    else -> TvAlertOpenResolution.Activity(expired = true)
                }
                is PrivacyDecision.RequirePin -> return TvAlertOpenResolution.PrivacyBlocked
                is PrivacyDecision.Deny -> return decision.toTvAlertBlockedResolution()
            }
        }

        // Network availability checks may suspend; revoke the earlier grant before consuming a tap.
        val latestPrivacy = notificationActionPrivacy()
        if (latestPrivacy.epoch != privacySnapshot.epoch || !latestPrivacy.authenticated ||
            latestPrivacy.authorizationFreshness != AuthorizationFreshness.FRESH ||
            latestPrivacy.allowedCameraIds != allowedCameraIds || latestPrivacy.pinUnlockProof != privacySnapshot.pinUnlockProof ||
            (realtimeRuntime.owner.currentState().profileState as? RealtimeProfileState.Ready)?.profile?.id != profileKey
        ) return TvAlertOpenResolution.PrivacyBlocked
        if (!notificationPause.snapshot().permits(presentation.revision)) return TvAlertOpenResolution.Unavailable
        if (!consumeNotification) return resolution
        val consumed = notificationCoordinator.acknowledgeOpen(
            profileKey = profileKey,
            reviewId = reviewId,
            actionNonce = actionNonce,
            nowEpochMillis = System.currentTimeMillis().coerceAtLeast(0L),
        )
        if (consumed) {
            alertNotificationPlatform.cancel(NotificationIdentityFactory().eventIdentity(profileKey, reviewId))
            alertNotificationPlatform.purgeEphemeralContent(profileKey, reviewId)
        }
        return if (consumed) resolution else TvAlertOpenResolution.Unavailable
    }

    private fun PrivacyDecision.Deny.toTvAlertBlockedResolution(): TvAlertOpenResolution =
        when (reason) {
            PrivacyDenialReason.GUEST_CONTENT_BLOCKED,
            PrivacyDenialReason.GUEST_ACTION_BLOCKED,
            PrivacyDenialReason.PRIVATE_CONTENT_HIDDEN,
            -> TvAlertOpenResolution.PrivacyBlocked
            else -> TvAlertOpenResolution.Unavailable
        }

    internal suspend fun snoozeTvAlertFromNotification(
        profileKey: String,
        reviewId: String,
        actionNonce: String,
    ): Boolean {
        if (notificationPause.snapshot().paused) return false
        val record = notificationCoordinator.activeActionRecord(profileKey, reviewId, actionNonce)
            ?: return false
        val processState = realtimeRuntime.awareness.state.value
        val review = processState.ledger.review(reviewId)
            ?.takeIf { it.profileKey == profileKey }
            ?: return false
        val camera = review.camera?.takeIf(processState.authorizedCameraIds::contains) ?: return false
        if (notificationPause.snapshot().suppressesFrigate(review.startEpochSeconds)) return false
        val privacy = notificationActionPrivacy()
        val decision = PrivacyDecisionEngine().decide(
            PrivacyRequest(PrivacySurface.NOTIFICATION, PrivacyTarget.Review(reviewId, camera), privacy.epoch), privacy,
        )
        if (decision !is PrivacyDecision.Allow && decision !is PrivacyDecision.AllowRedacted) return false
        if (processState.desynchronized || processState.authorizedCameraIds != privacy.allowedCameraIds) return false
        val configuration = (alertConfigurationRepository.state.value as? AlertConfigurationState.Ready)
            ?.configuration
            ?.takeIf { it.profileKey == profileKey && it.enabled }
            ?: return false
        val now = System.currentTimeMillis().coerceAtLeast(0L)
        val duration = 15L * 60L * 1_000L
        val expires = if (now > Long.MAX_VALUE - duration) Long.MAX_VALUE else now + duration
        val mutation = alertConfigurationRepository.addSnooze(
            AlertSnooze(
                scope = AlertSnoozeScope.CAMERA,
                cameraIds = setOf(camera),
                expiresAtEpochMillis = expires,
            ),
        )
        if (mutation is AlertConfigurationMutationResult.Rejected ||
            mutation is AlertConfigurationMutationResult.Unavailable
        ) return false
        return notificationCoordinator.cancelStored(
            record = record.copy(policyVersion = configuration.policyVersion),
            nowEpochMillis = now,
            reason = AlertDecisionReason.SNOOZED,
        ) is app.opah.tv.notifications.NotificationProcessingResult.Cancelled
    }

    internal suspend fun enableTvAlertsFromVisibleUserAction(): TvAlertActivationResult {
        val profileKey = (realtimeRuntime.owner.currentState().profileState as?
            app.opah.tv.data.realtime.RealtimeProfileState.Ready)?.profile?.id
            ?: return TvAlertActivationResult.CONFIGURATION_UNAVAILABLE
        val initialized = alertConfigurationRepository.initialize(profileKey)
        if (initialized !is AlertConfigurationState.Ready) {
            return TvAlertActivationResult.CONFIGURATION_UNAVAILABLE
        }
        return when (alertConfigurationRepository.setEnabled(true)) {
            is AlertConfigurationMutationResult.Updated,
            is AlertConfigurationMutationResult.Unchanged,
            -> {
                val start = TvAlertServiceController.startFromVisibleUserAction(appContext)
                if (start == TvAlertStartResult.STARTED) {
                    TvAlertActivationResult.STARTED
                } else {
                    alertConfigurationRepository.setEnabled(false)
                    start.toActivationResult()
                }
            }
            AlertConfigurationMutationResult.Rejected,
            AlertConfigurationMutationResult.Unavailable,
            -> {
                TvAlertActivationResult.CONFIGURATION_UNAVAILABLE
            }
        }
    }

    internal fun tvAlertDeliveryStatus(): TvAlertDeliveryStatus =
        TvAlertServiceController.deliveryStatus(appContext)

    internal fun tvAlertOverlayPermissionGranted(): Boolean =
        TvAlertOverlayPermission.isGranted(appContext)

    internal suspend fun restoreTvAlertsAfterProcessRecreation(): Boolean {
        if (!AndroidNotificationChannels.eventChannelsAvailable(appContext)) return false
        val profile = profileRepository.load() ?: return false
        if (!profile.usesUnauthenticatedFrigatePort && !cookieJar.hasUnexpiredSession()) return false
        val profileKey = compatibilityIdentityFactory.derive(
            CompatibilityIdentityDomain.PROFILE,
            profile.compatibilityIdentityComponents(),
        ).value
        val configuration = alertConfigurationRepository.initialize(profileKey)
        return configuration is AlertConfigurationState.Ready && configuration.configuration.enabled
    }

    internal suspend fun disableTvAlerts(stopAndroidService: Boolean = true): Boolean {
        alertNotificationPlatform.dismissOverlay()
        val profileKey = (alertConfigurationRepository.state.value as? AlertConfigurationState.Ready)
            ?.configuration?.profileKey
        val mutation = alertConfigurationRepository.setEnabled(false)
        if (stopAndroidService) TvAlertServiceController.stop(appContext)
        stopAlertAwareness()
        val purged = profileKey?.let { purgeAlertProfile(it) } ?: true
        val signInNoticeCleared = notificationCoordinator.cancelSignInRequired()
        return mutation !is AlertConfigurationMutationResult.Unavailable && purged && signInNoticeCleared
    }

    internal fun startAlertAwareness() {
        val runtime = realtimeRuntime
        realtimeNetworkMonitorDelegate.value.start()
        runtime.owner.acquireLease(ALERT_AWARENESS_LEASE, RealtimeDemandOwner.AWARENESS_SERVICE)
        alertNotificationOwnerDelegate.value.start()
        alertModeOwnerDelegate.value.start()
        if (runtime.owner.currentState().profileState is app.opah.tv.data.realtime.RealtimeProfileState.Unavailable) {
            realtimeProcessScope.launch {
                val profile = profileRepository.load() ?: return@launch
                if (!profile.usesUnauthenticatedFrigatePort && !cookieJar.hasUnexpiredSession()) return@launch
                runCatching { apiClient.getVersion(profile) }
                    .onSuccess { version -> runtime.activateAuthenticatedProfile(profile, version) }
            }
        }
    }

    internal fun stopAlertAwareness() {
        if (alertNotificationOwnerDelegate.isInitialized()) {
            alertNotificationOwnerDelegate.value.stop()
        }
        if (alertModeOwnerDelegate.isInitialized()) {
            alertModeOwnerDelegate.value.stop()
        }
        if (!realtimeRuntimeDelegate.isInitialized()) return
        realtimeRuntimeDelegate.value.owner.releaseLease(ALERT_AWARENESS_LEASE)
        if (realtimeRuntimeDelegate.value.owner.currentState().leases.isEmpty() &&
            realtimeNetworkMonitorDelegate.isInitialized()
        ) {
            realtimeNetworkMonitorDelegate.value.stop()
        }
    }

    internal fun dismissTvAlertOverlay() {
        alertNotificationPlatform.dismissOverlay()
    }

    internal val monitorAwarenessState
        get() = realtimeRuntime.awareness.state

    internal val monitorTransportState
        get() = realtimeRuntime.owner.state

    internal fun startMonitorAwareness() {
        val runtime = realtimeRuntime
        realtimeNetworkMonitorDelegate.value.start()
        runtime.owner.acquireLease(MONITOR_AWARENESS_LEASE, RealtimeDemandOwner.MONITOR_MODE)
        if (runtime.owner.currentState().profileState is
            app.opah.tv.data.realtime.RealtimeProfileState.Unavailable
        ) {
            realtimeProcessScope.launch {
                val profile = profileRepository.load() ?: return@launch
                if (!profile.usesUnauthenticatedFrigatePort && !cookieJar.hasUnexpiredSession()) return@launch
                runCatching { apiClient.getVersion(profile) }
                    .onSuccess { version -> runtime.activateAuthenticatedProfile(profile, version) }
            }
        }
    }

    internal fun stopMonitorAwareness() {
        monitorPresentedReviewId.value = null
        if (!realtimeRuntimeDelegate.isInitialized()) return
        realtimeRuntimeDelegate.value.owner.releaseLease(MONITOR_AWARENESS_LEASE)
        if (realtimeRuntimeDelegate.value.owner.currentState().leases.isEmpty() &&
            realtimeNetworkMonitorDelegate.isInitialized()
        ) {
            realtimeNetworkMonitorDelegate.value.stop()
        }
    }

    internal fun updateMonitorPresentedReview(reviewId: String?) {
        monitorPresentedReviewId.value = reviewId?.takeIf {
            it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl)
        }
    }

    init {
        alertProcessScope.launch {
            notificationPause.state.collect { if (it.paused && notificationPause.snapshot().paused) clearPausedPresentations() }
        }
        alertProcessScope.launch {
            while (true) { notificationPause.snapshot(); delay(1_000) }
        }
    }

    private companion object {
        val ALERT_AWARENESS_LEASE = RealtimeLeaseId("tv-alert-service")
        val MONITOR_AWARENESS_LEASE = RealtimeLeaseId("monitor-mode")
        const val LOCAL_TEST_REPOST_DELAY_MILLIS = 250L
        const val LOCAL_TEST_REVIEW_ID = "local-fictional-test-alert"
        const val LOCAL_TEST_FINGERPRINT = "opah-local-fictional-test-alert-v1"
    }

    private suspend fun purgeAlertProfile(profileKey: String): Boolean {
        val records = runCatching { notificationLedgerStore.activeOrPending(profileKey) }
            .getOrElse { return false }
        val now = System.currentTimeMillis().coerceAtLeast(0L)
        val cancelled = records.all { record ->
            notificationCoordinator.cancelStored(record, now) is
                app.opah.tv.notifications.NotificationProcessingResult.Cancelled
        }
        if (!cancelled) return false
        return runCatching { notificationLedgerStore.deleteProfile(profileKey) }.isSuccess
    }
}

private fun TvAlertStartResult.toActivationResult(): TvAlertActivationResult = when (this) {
    TvAlertStartResult.STARTED -> TvAlertActivationResult.STARTED
    TvAlertStartResult.NOTIFICATION_PERMISSION_REQUIRED ->
        TvAlertActivationResult.NOTIFICATION_PERMISSION_REQUIRED
    TvAlertStartResult.ON_SCREEN_PERMISSION_REQUIRED ->
        TvAlertActivationResult.ON_SCREEN_PERMISSION_REQUIRED
    TvAlertStartResult.NOTIFICATIONS_BLOCKED -> TvAlertActivationResult.NOTIFICATIONS_BLOCKED
    TvAlertStartResult.START_REJECTED -> TvAlertActivationResult.START_REJECTED
}
