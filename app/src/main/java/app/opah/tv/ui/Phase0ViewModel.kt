package app.opah.tv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.opah.tv.OpahApplication
import app.opah.tv.BuildConfig
import app.opah.tv.data.ConnectionProfileFactory
import app.opah.tv.data.CameraImage
import app.opah.tv.data.DiscoveryBootstrap
import app.opah.tv.data.network.AuthenticationExpiredException
import app.opah.tv.data.network.InvalidCredentialsException
import app.opah.tv.data.network.toOpahFailure
import app.opah.tv.data.model.Camera
import app.opah.tv.data.model.AppSettings
import app.opah.tv.data.model.AppearanceMode
import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.model.CustomThemeColors
import app.opah.tv.data.model.DeviceDiagnostics
import app.opah.tv.data.model.DiscoverySnapshot
import app.opah.tv.data.model.LiveStreamOption
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.ReviewSeverity
import app.opah.tv.data.model.FrigateInformationSummary
import app.opah.tv.data.model.StreamPreference
import app.opah.tv.data.model.ThemeColorPolicy
import app.opah.tv.domain.StreamUriFactory
import app.opah.tv.playback.PlaybackKind
import app.opah.tv.playback.PlaybackRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InformationUiState(
    val loading: Boolean = false,
    val loadedOnce: Boolean = false,
    val summary: FrigateInformationSummary? = null,
    val errorMessage: String? = null,
)

data class Phase0UiState(
    val loading: Boolean = true,
    val testingConnection: Boolean = false,
    val statusMessage: String = "Starting Opah…",
    val errorMessage: String? = null,
    val savedProfile: ConnectionProfile? = null,
    val activeProfile: ConnectionProfile? = null,
    val snapshot: DiscoverySnapshot? = null,
    val device: DeviceDiagnostics? = null,
    val playback: PlaybackRequest? = null,
    val activeCameraName: String? = null,
    val settings: AppSettings = AppSettings(),
    val review: ReviewBrowserState = ReviewBrowserState(),
    val information: InformationUiState = InformationUiState(),
    val savedSessionRecoveryAvailable: Boolean = false,
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
    private val cameraImageRepository = container.cameraImageRepository
    private val reviewImageRepository = container.reviewImageRepository
    private val documentationImages = DocumentationImageStore(application)
    private var reviewLoadJob: Job? = null
    private var reviewDetailJob: Job? = null
    private var enrichmentJob: Job? = null
    private var pendingLiveCameraName: String? = null

    private val _state = MutableStateFlow(Phase0UiState())
    val state: StateFlow<Phase0UiState> = _state.asStateFlow()

    init {
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.value = DocumentationFixtures.state(null)
        } else {
            viewModelScope.launch {
                settingsRepository.settings.collect { settings ->
                    _state.update { it.copy(settings = settings) }
                }
            }
            viewModelScope.launch(Dispatchers.Default) {
                runCatching { codecService.inspect() }
                    .onSuccess { device -> _state.update { it.copy(device = device) } }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        logger.warning("Device codec inspection failed", error)
                    }
            }
            viewModelScope.launch {
                val profile = profileRepository.load()
                _state.update { it.copy(savedProfile = profile) }

                val canRestore = profile != null && withContext(Dispatchers.IO) {
                    container.cookieJar.hasUnexpiredSession() || sessionManager.hasSavedCredential()
                }
                if (profile != null && canRestore) {
                    loadSavedSession(profile)
                } else if (profile != null) {
                    _state.update {
                        it.copy(loading = false, statusMessage = "Sign in to Frigate.")
                    }
                } else {
                    _state.update {
                        it.copy(
                            loading = false,
                            statusMessage = "Enter your Frigate connection.",
                        )
                    }
                }
            }
        }
    }

    fun setDocumentationScenario(scenario: String?) {
        if (!BuildConfig.DOCUMENTATION_MODE) return
        _state.value = DocumentationFixtures.state(scenario)
    }

    fun connect(
        rawUrl: String,
        username: String,
        password: String,
        rtspHostOverride: String,
        rtspPortText: String,
    ) {
        if (_state.value.loading) return
        val profileResult = ConnectionProfileFactory.create(
            rawApiBaseUrl = rawUrl,
            username = username,
            rtspHostOverride = rtspHostOverride,
            rtspPort = rtspPortText.toIntOrNull() ?: -1,
        )
        val profile = profileResult.getOrElse { error ->
            _state.update { it.copy(errorMessage = error.message ?: "Invalid connection settings.") }
            return
        }

        viewModelScope.launch {
            enrichmentJob?.cancel()
            beginWork("Connecting…")
            try {
                val user = sessionManager.signIn(profile, password)
                val bootstrap = repository.discoverEssential(profile, user)
                clearImageCaches()
                publishConnected(profile, bootstrap)
                startEnrichment(profile, bootstrap)
            } catch (error: Throwable) {
                showFailure(error)
            }
        }
    }

    fun testConnection(
        rawUrl: String,
        username: String,
        password: String,
        rtspHostOverride: String,
        rtspPortText: String,
    ) {
        if (_state.value.loading || _state.value.testingConnection) return
        val profile = createProfile(
            rawUrl = rawUrl,
            username = username,
            rtspHostOverride = rtspHostOverride,
            rtspPortText = rtspPortText,
        ) ?: return

        viewModelScope.launch {
            // Deliberately does not go through beginWork()/loading: that flag drives the
            // root-surface swap to the full-screen loader, which would unmount
            // ConnectionSetupScreen and wipe the address/username/password the user just typed
            // (they aren't persisted to savedProfile until a real Connect succeeds).
            _state.update {
                it.copy(testingConnection = true, statusMessage = "Testing connection…", errorMessage = null)
            }
            runCatching {
                sessionManager.testConnection(profile, password) { user ->
                    repository.discover(profile, user)
                }
            }
                .onSuccess { snapshot ->
                    _state.update {
                        it.copy(
                            testingConnection = false,
                            statusMessage = "Connected • ${snapshot.cameras.size} cameras",
                            errorMessage = null,
                        )
                    }
                }
                .onFailure { error ->
                    logger.warning("Frigate operation failed", error)
                    _state.update {
                        it.copy(
                            testingConnection = false,
                            statusMessage = "Connection failed",
                            errorMessage = error.toOpahFailure().userMessage,
                        )
                    }
                }
        }
    }

    fun refresh() {
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update { current ->
                DocumentationFixtures.state(null).copy(settings = current.settings)
            }
            return
        }
        val profile = _state.value.activeProfile ?: return
        enrichmentJob?.cancel()
        viewModelScope.launch {
            beginWork("Refreshing Frigate diagnostics…")
            runCatching {
                val user = sessionManager.restore(profile)
                repository.discover(profile, user)
            }
                .onSuccess { snapshot ->
                    clearImageCaches()
                    _state.update {
                        it.copy(
                            loading = false,
                            statusMessage = "Connected",
                            errorMessage = null,
                            snapshot = snapshot,
                            review = ReviewBrowserState(),
                            information = InformationUiState(),
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
        val profile = _state.value.activeProfile ?: _state.value.savedProfile
        reviewLoadJob?.cancel()
        reviewDetailJob?.cancel()
        enrichmentJob?.cancel()
        viewModelScope.launch {
            sessionManager.signOut(profile, forgetServer)
            _state.update {
                it.copy(
                    loading = false,
                    statusMessage = if (forgetServer) "Enter your Frigate connection." else "Signed out",
                    errorMessage = null,
                    savedProfile = if (forgetServer) null else profile,
                    activeProfile = null,
                    snapshot = null,
                    playback = null,
                    activeCameraName = null,
                    review = ReviewBrowserState(),
                    information = InformationUiState(),
                    savedSessionRecoveryAvailable = false,
                )
            }
            clearImageCaches()
        }
    }

    fun playAutomatic(camera: Camera) {
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    playback = DocumentationFixtures.livePlayback(camera),
                    activeCameraName = camera.name,
                    errorMessage = null,
                )
            }
            return
        }
        val current = _state.value
        val profile = current.activeProfile ?: return
        val codecs = current.device?.codecs.orEmpty()
        streamSelector.select(camera, codecs, current.settings.streamPreference)
            .onSuccess { playStream(profile, camera, it.option, it.reason) }
            .onFailure { error ->
                _state.update { it.copy(errorMessage = error.message ?: "No compatible stream.") }
            }
    }

    fun playStream(camera: Camera, option: LiveStreamOption) {
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    playback = DocumentationFixtures.livePlayback(camera).copy(
                        detail = "${option.label} • Explicit stream selection",
                    ),
                    activeCameraName = camera.name,
                    errorMessage = null,
                )
            }
            return
        }
        val profile = _state.value.activeProfile ?: return
        playStream(profile, camera, option, "Explicit Frigate stream selection")
    }

    fun playBirdseye() {
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    playback = DocumentationFixtures.birdseyePlayback(),
                    activeCameraName = null,
                    errorMessage = null,
                )
            }
            return
        }
        val current = _state.value
        val profile = current.activeProfile ?: return
        val snapshot = current.snapshot ?: return
        val birdseye = snapshot.birdseye
        if (!birdseye.playable) {
            _state.update {
                it.copy(errorMessage = "Frigate Birdseye is not ready for live playback.")
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
                        ),
                        activeCameraName = null,
                        errorMessage = null,
                    )
                }
            }
            .onFailure { _state.update { state -> state.copy(errorMessage = it.message) } }
    }

    fun playReview(item: ReviewItem) {
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    playback = DocumentationFixtures.recordedPlayback(item),
                    activeCameraName = null,
                    errorMessage = null,
                )
            }
            return
        }
        val profile = _state.value.activeProfile ?: return
        _state.update {
            it.copy(
                playback = PlaybackRequest(
                    title = "${item.severity.name.lowercase().replaceFirstChar(Char::uppercase)} — ${item.camera.replace('_', ' ')}",
                    uri = repository.reviewPlaybackUrl(profile, item),
                    kind = PlaybackKind.RECORDED,
                    detail = "Frigate fMP4 HLS recording",
                ),
                activeCameraName = null,
                errorMessage = null,
            )
        }
    }

    fun closePlayback() {
        _state.update { it.copy(playback = null, activeCameraName = null) }
    }

    fun updateAppearance(mode: AppearanceMode) = updateSettings { it.copy(appearanceMode = mode) }

    fun updateCustomTheme(colors: CustomThemeColors) = updateSettings {
        it.copy(customThemeColors = ThemeColorPolicy.sanitize(colors))
    }

    fun loadInformation(force: Boolean = false) {
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update {
                it.copy(
                    information = InformationUiState(
                        loadedOnce = true,
                        summary = DocumentationFixtures.information(),
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

    fun updateStartLiveMuted(enabled: Boolean) =
        updateSettings { it.copy(startLiveMuted = enabled) }

    fun updateDiagnosticsEnabled(enabled: Boolean) =
        updateSettings { it.copy(diagnosticsEnabled = enabled) }

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
        _state.value.activeProfile?.let { cameraImageRepository.cached(it, cameraName) }
    }

    suspend fun refreshCameraImage(cameraName: String, height: Int = 360): Result<CameraImage> {
        if (BuildConfig.DOCUMENTATION_MODE) {
            return documentationImages.camera(cameraName)?.let(Result.Companion::success)
                ?: Result.failure(IllegalStateException("Documentation image is unavailable."))
        }
        val profile = _state.value.activeProfile
            ?: return Result.failure(IllegalStateException("No active Frigate connection."))
        return cameraImageRepository.refresh(profile, cameraName, height)
    }

    fun loadReview() {
        if (BuildConfig.DOCUMENTATION_MODE) {
            val filters = _state.value.review.filters
            val items = DocumentationFixtures.reviewItems().filter { item ->
                item.severity == filters.severity &&
                    (filters.camera == null || item.camera == filters.camera) &&
                    (filters.label == null || filters.label in item.objects) &&
                    (filters.zone == null || filters.zone in item.zones)
            }
            _state.update {
                it.copy(
                    review = it.review.copy(
                        items = items,
                        knownLabels = DocumentationFixtures.reviewItems().flatMap(ReviewItem::objects).toSet(),
                        knownZones = DocumentationFixtures.reviewItems().flatMap(ReviewItem::zones).toSet(),
                        loading = false,
                        loadedOnce = true,
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
        val allowedCameras = current.snapshot?.user?.allowedCameras.orEmpty()
        if (allowedCameras.isEmpty()) return
        val filters = current.review.filters
        reviewLoadJob?.cancel()
        reviewDetailJob?.cancel()
        reviewLoadJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    review = it.review.copy(
                        loading = true,
                        errorMessage = null,
                        selectedItemId = null,
                        recordingState = ReviewRecordingState.IDLE,
                        detailErrorMessage = null,
                    ),
                )
            }
            runCatching {
                repository.searchReview(
                    profile = profile,
                    allowedCameras = allowedCameras,
                    query = filters.toSearchQuery(
                        allowedCameras = allowedCameras,
                        nowSeconds = System.currentTimeMillis() / 1000.0,
                    ),
                )
            }.onSuccess { discovery ->
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
                            loading = false,
                            loadedOnce = true,
                            errorMessage = discovery.warnings.firstOrNull(),
                        ),
                    )
                }
            }.onFailure { error ->
                if (error is AuthenticationExpiredException) {
                    handleConnectedFailure(error)
                } else {
                    logger.warning("Frigate Review load failed", error)
                    _state.update {
                        it.copy(
                            review = it.review.copy(
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

    fun updateReviewSeverity(severity: ReviewSeverity) =
        updateReviewFilters { it.copy(severity = severity) }

    fun updateReviewCamera(camera: String?) = updateReviewFilters { it.copy(camera = camera) }

    fun updateReviewLabel(label: String?) = updateReviewFilters { it.copy(label = label) }

    fun updateReviewZone(zone: String?) = updateReviewFilters { it.copy(zone = zone) }

    fun updateReviewTimeRange(timeRange: ReviewTimeRange) =
        updateReviewFilters { it.copy(timeRange = timeRange) }

    fun resetReviewFilters() {
        _state.update {
            it.copy(review = it.review.copy(filters = ReviewFilters(severity = it.review.filters.severity)))
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
                    ),
                )
            }
            return
        }
        val profile = _state.value.activeProfile ?: return
        reviewDetailJob?.cancel()
        _state.update {
            it.copy(
                review = it.review.copy(
                    selectedItemId = item.id,
                    recordingState = ReviewRecordingState.CHECKING,
                    detailErrorMessage = null,
                ),
            )
        }
        reviewDetailJob = viewModelScope.launch {
            repository.reviewRecordingAvailable(profile, item)
                .onSuccess { available ->
                    _state.update { state ->
                        if (state.review.selectedItemId != item.id) return@update state
                        state.copy(
                            review = state.review.copy(
                                items = state.review.items.map { candidate ->
                                    if (candidate.id == item.id) {
                                        candidate.copy(recordingAvailable = available)
                                    } else {
                                        candidate
                                    }
                                },
                                recordingState = if (available) {
                                    ReviewRecordingState.AVAILABLE
                                } else {
                                    ReviewRecordingState.UNAVAILABLE
                                },
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    if (error is AuthenticationExpiredException) {
                        handleConnectedFailure(error)
                    } else {
                        logger.warning("Review recording availability check failed", error)
                        _state.update { state ->
                            if (state.review.selectedItemId != item.id) return@update state
                            state.copy(
                                review = state.review.copy(
                                    recordingState = ReviewRecordingState.UNKNOWN,
                                    detailErrorMessage = "Recording availability could not be confirmed.",
                                ),
                            )
                        }
                    }
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
                ),
            )
        }
    }

    fun cachedReviewImage(item: ReviewItem) = if (BuildConfig.DOCUMENTATION_MODE) {
        documentationImages.review(item)
    } else {
        _state.value.activeProfile?.let { reviewImageRepository.cached(it, item) }
    }

    suspend fun refreshReviewImage(item: ReviewItem, height: Int = 360) =
        if (BuildConfig.DOCUMENTATION_MODE) {
            documentationImages.review(item)?.let(Result.Companion::success)
                ?: Result.failure(IllegalStateException("Documentation image is unavailable."))
        } else {
            _state.value.activeProfile?.let { reviewImageRepository.refresh(it, item, height) }
                ?: Result.failure(IllegalStateException("No active Frigate connection."))
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
        sessionManager.expireSession()
        _state.update {
            it.copy(
                statusMessage = "Sign in to Frigate.",
                errorMessage = null,
                activeProfile = null,
                snapshot = null,
                playback = null,
                activeCameraName = null,
                savedSessionRecoveryAvailable = false,
            )
        }
    }

    fun sessionExpired(message: String = "The Frigate session expired. Sign in again.") {
        sessionManager.expireSession()
        val profile = _state.value.activeProfile ?: _state.value.savedProfile
        if (profile == null) {
            finishSignedOut(message)
            return
        }
        viewModelScope.launch {
            if (!sessionManager.hasSavedCredential()) {
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
                    finishSignedOut(error.message ?: "Sign in to Frigate.")
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
    ) {
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
                _state.update { it.copy(errorMessage = error.message ?: "Invalid RTSP stream URL.") }
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

    private fun publishConnected(profile: ConnectionProfile, bootstrap: DiscoveryBootstrap) {
        _state.update {
            it.copy(
                loading = false,
                statusMessage = "Connected",
                errorMessage = null,
                savedProfile = profile,
                activeProfile = profile,
                snapshot = bootstrap.snapshot,
                review = ReviewBrowserState(),
                information = InformationUiState(),
                savedSessionRecoveryAvailable = false,
            )
        }
        consumePendingLiveCamera()
    }

    // Called from MainActivity for a launch (cold start or singleTask redelivery via
    // onNewIntent) that asks to jump straight into one camera's fullscreen live view,
    // e.g. from a TV remote automation shortcut. If we're not connected yet, or the
    // camera list hasn't loaded, the request is remembered and consumed once it has.
    fun requestLiveCamera(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        pendingLiveCameraName = trimmed
        consumePendingLiveCamera()
    }

    private fun consumePendingLiveCamera() {
        val name = pendingLiveCameraName ?: return
        val camera = _state.value.snapshot?.cameras?.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return
        pendingLiveCameraName = null
        playAutomatic(camera)
    }

    private fun startEnrichment(profile: ConnectionProfile, bootstrap: DiscoveryBootstrap) {
        enrichmentJob?.cancel()
        enrichmentJob = viewModelScope.launch {
            runCatching { repository.enrich(profile, bootstrap) }
                .onSuccess { snapshot ->
                    _state.update { current ->
                        if (current.activeProfile == profile) current.copy(snapshot = snapshot) else current
                    }
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

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        if (BuildConfig.DOCUMENTATION_MODE) {
            _state.update { it.copy(settings = transform(it.settings)) }
        } else {
            viewModelScope.launch { settingsRepository.update(transform) }
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
            _state.update { it.copy(errorMessage = error.message ?: "Invalid connection settings.") }
            null
        }
    }

    private fun showFailure(error: Throwable) {
        logger.warning("Frigate operation failed", error)
        _state.update {
            it.copy(
                loading = false,
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

    private fun finishSignedOut(message: String) {
        reviewLoadJob?.cancel()
        reviewDetailJob?.cancel()
        enrichmentJob?.cancel()
        sessionManager.expireSession()
        clearImageCaches()
        _state.update {
            it.copy(
                loading = false,
                statusMessage = "Sign in to Frigate.",
                errorMessage = message,
                activeProfile = null,
                snapshot = null,
                playback = null,
                activeCameraName = null,
                review = ReviewBrowserState(),
                information = InformationUiState(),
                savedSessionRecoveryAvailable = false,
            )
        }
    }

    private fun showSavedSessionRecovery(error: Throwable) {
        reviewLoadJob?.cancel()
        reviewDetailJob?.cancel()
        enrichmentJob?.cancel()
        _state.update {
            it.copy(
                loading = false,
                statusMessage = "Frigate is unavailable",
                errorMessage = error.toOpahFailure().userMessage,
                activeProfile = null,
                snapshot = null,
                playback = null,
                activeCameraName = null,
                review = ReviewBrowserState(),
                information = InformationUiState(),
                savedSessionRecoveryAvailable = it.savedProfile != null,
            )
        }
    }

    private fun clearImageCaches() {
        cameraImageRepository.clear()
        reviewImageRepository.clear()
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            Phase0ViewModel(application) as T
    }
}
