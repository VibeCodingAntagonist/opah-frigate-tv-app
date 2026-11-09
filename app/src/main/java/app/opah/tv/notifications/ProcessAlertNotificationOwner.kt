package app.opah.tv.notifications

import app.opah.tv.awareness.AwarenessProcessState
import app.opah.tv.awareness.ProcessAwarenessOwner
import app.opah.tv.data.realtime.CameraScopeState
import app.opah.tv.data.realtime.RealtimeAuthenticationState
import app.opah.tv.data.realtime.RealtimeTransportState
import app.opah.tv.privacy.AuthorizationFreshness
import app.opah.tv.privacy.PrivacyRepository
import app.opah.tv.privacy.PrivacyRepositoryState
import app.opah.tv.privacy.PrivacySessionEvidence
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow

/** Process-scoped bridge from reconciled Awareness state to durable native notification work. */
internal class ProcessAlertNotificationOwner(
    private val scope: CoroutineScope,
    private val awareness: ProcessAwarenessOwner,
    private val configurationRepository: AlertConfigurationRepository,
    private val privacyRepository: PrivacyRepository,
    private val transportState: StateFlow<RealtimeTransportState>,
    private val modeState: StateFlow<AlertModeProcessState>,
    private val monitorReviewId: StateFlow<String?>,
    private val plateLabels: AlertPlateLabelSource = AlertPlateLabelSource { _, _, _, _ -> emptySet() },
    private val ledgerStore: NotificationLedgerStore,
    private val coordinator: NotificationCoordinator,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val monotonicClockMillis: () -> Long,
    private val timeZone: () -> TimeZone = TimeZone::getDefault,
    private val pauseState: StateFlow<NotificationPauseSnapshot> = kotlinx.coroutines.flow.MutableStateFlow(NotificationPauseSnapshot()),
) {
    private var collectionJob: Job? = null
    private var authenticationJob: Job? = null
    @Volatile
    private var signInNoticePosted = false
    @Volatile
    private var activeProfileKey: String? = null

    @Synchronized
    fun start() {
        if (collectionJob?.isActive == true) return
        authenticationJob = scope.launch {
            transportState
                .map { state -> state.authenticationState }
                .distinctUntilChanged()
                .collect(::updateAuthenticationNotice)
        }
        collectionJob = scope.launch {
            privacyRepository.initialize()
            awareness.state
                .map { it.ledger.profileKey }
                .distinctUntilChanged()
                .collectLatest { profileKey ->
                    activeProfileKey = profileKey
                    if (profileKey == null) return@collectLatest
                    configurationRepository.initialize(profileKey)
                    collectProfile(profileKey)
                }
        }
    }

    @Synchronized
    fun stop() {
        collectionJob?.cancel()
        collectionJob = null
        authenticationJob?.cancel()
        authenticationJob = null
    }

    suspend fun purgeActiveProfile(): Boolean {
        val profileKey = activeProfileKey ?: return coordinator.cancelSignInRequired().also {
            signInNoticePosted = false
        }
        val records = runCatching { ledgerStore.activeOrPending(profileKey) }.getOrElse { return false }
        val now = wallClockMillis().coerceAtLeast(0L)
        val allCancelled = records.all { record ->
            coordinator.cancelStored(
                record = record,
                nowEpochMillis = now,
                reason = AlertDecisionReason.UNAUTHORIZED_CAMERA,
            ) is NotificationProcessingResult.Cancelled
        }
        if (!allCancelled) return false
        coordinator.cancelSignInRequired()
        signInNoticePosted = false
        return runCatching { ledgerStore.deleteProfile(profileKey) }.isSuccess.also { deleted ->
            if (deleted) activeProfileKey = null
        }
    }

    private suspend fun collectProfile(profileKey: String) {
        var previous: EvaluationSignature? = null
        val modeAndMonitor = combine(modeState, monitorReviewId, pauseState) { mode, reviewId, pause ->
            Triple(mode, reviewId, pause)
        }
        combine(
            awareness.state,
            configurationRepository.state,
            privacyRepository.state,
            transportState,
            modeAndMonitor,
        ) { awarenessState, configurationState, privacyState, currentTransportState, currentModeAndMonitor ->
            EvaluationSignal(
                awarenessState,
                configurationState,
                privacyState,
                currentTransportState,
                currentModeAndMonitor.first,
                currentModeAndMonitor.second,
                currentModeAndMonitor.third,
            )
        }.collect { signal ->
            val processState = signal.awareness
            if (processState.ledger.profileKey != profileKey) return@collect
            val configuration = (signal.configuration as? AlertConfigurationState.Ready)
                ?.configuration
                ?.takeIf { it.profileKey == profileKey }
            val signature = EvaluationSignature(
                reviewObservations = processState.ledger.reviewsById.mapValues {
                    it.value.updateMetadata.latestObservationId
                },
                configurationMarker = signal.configuration,
                privacyMarker = signal.privacy,
                transportMarker = signal.transport.notificationMarker(),
                modeMarker = signal.mode,
                monitorReviewId = signal.monitorReviewId,
                pauseMarker = signal.pause,
            )
            val fullSweep = previous == null ||
                previous?.configurationMarker != signature.configurationMarker ||
                previous?.privacyMarker != signature.privacyMarker ||
                previous?.transportMarker != signature.transportMarker ||
                previous?.modeMarker != signature.modeMarker ||
                previous?.monitorReviewId != signature.monitorReviewId || previous?.pauseMarker != signature.pauseMarker
            if (previous != null && previous?.privacyMarker != signature.privacyMarker) {
                coordinator.purgeProfileEphemeralContent(profileKey)
            }
            val changedReviewIds = if (fullSweep) {
                processState.ledger.reviewsById.keys
            } else {
                signature.reviewObservations.keys.filterTo(linkedSetOf()) { reviewId ->
                    signature.reviewObservations[reviewId] != previous?.reviewObservations?.get(reviewId)
                }
            }
            val removedReviewIds = previous?.reviewObservations?.keys.orEmpty() -
                signature.reviewObservations.keys
            val activeRecords = if (fullSweep || removedReviewIds.isNotEmpty() || configuration == null) {
                runCatching { ledgerStore.activeOrPending(profileKey) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            if (configuration == null) {
                activeRecords.forEach { record ->
                    coordinator.cancelStored(record, safeNow(), AlertDecisionReason.NEVER_NOTIFY)
                }
                previous = signature
                return@collect
            }
            val idsToProcess = linkedSetOf<String>().apply {
                addAll(changedReviewIds)
                if (fullSweep) addAll(processState.ledger.reviewsById.keys)
            }
            idsToProcess.sorted().forEach { reviewId ->
                val review = processState.ledger.review(reviewId) ?: return@forEach
                val recognizedPlateLabels = if (
                    configuration.policy.plateLabels.isEmpty() || review.camera == null
                ) {
                    emptySet()
                } else {
                    runCatching {
                        plateLabels.resolve(
                            profileKey = profileKey,
                            camera = review.camera,
                            detectionIds = review.detectionIds,
                            authorizedCameraIds = processState.authorizedCameraIds,
                        )
                    }.getOrDefault(emptySet())
                }
                coordinator.process(
                    NotificationProcessingInput(
                        configuration = configuration.asNotificationView(),
                        review = review,
                        authorizedCameraIds = processState.authorizedCameraIds,
                        privacySnapshots = AlertPrivacySnapshotSource {
                            privacyRepository.snapshot(
                                session = currentPrivacyEvidence(),
                                nowMonotonicMillis = monotonicClockMillis().coerceAtLeast(0L),
                            )
                        },
                        nowEpochMillis = safeNow(),
                        timeZone = timeZone(),
                        cameraDisplayName = review.camera?.let { cameraId ->
                            signal.transport.notificationCameraDisplayName(cameraId)
                        },
                        recognizedPeople = review.subLabels,
                        recognizedPlateLabels = recognizedPlateLabels,
                        currentFrigateMode = signal.mode.currentMode.takeIf {
                            signal.mode.fresh && signal.mode.profileKey == profileKey
                        },
                        presentation = AlertPresentationContext(
                            monitorReviewId = signal.monitorReviewId,
                        ),
                    ),
                )
            }
            if (fullSweep || removedReviewIds.isNotEmpty()) {
                val presentIds = processState.ledger.reviewsById.keys
                activeRecords.filter { it.reviewId !in presentIds }.forEach { record ->
                    coordinator.cancelStored(
                        record,
                        safeNow(),
                        AlertDecisionReason.UNAUTHORIZED_CAMERA,
                    )
                }
            }
            previous = signature
        }
    }

    private fun currentPrivacyEvidence(): PrivacySessionEvidence {
        val state = transportState.value
        val scope = state.cameraScope
        return PrivacySessionEvidence(
            authenticated = state.authenticationState == RealtimeAuthenticationState.AUTHENTICATED,
            authorizationFreshness = when (scope) {
                is CameraScopeState.Fresh -> AuthorizationFreshness.FRESH
                is CameraScopeState.Stale -> AuthorizationFreshness.STALE
                CameraScopeState.Unknown -> AuthorizationFreshness.UNKNOWN
            },
            allowedCameraIds = when (scope) {
                is CameraScopeState.Fresh -> scope.evidence.allowedCameraIds
                is CameraScopeState.Stale -> scope.evidence.allowedCameraIds
                CameraScopeState.Unknown -> emptySet()
            },
        )
    }

    private fun safeNow(): Long = wallClockMillis().coerceAtLeast(0L)

    private suspend fun updateAuthenticationNotice(authentication: RealtimeAuthenticationState) {
        when (authentication) {
            RealtimeAuthenticationState.REQUIRED -> if (!signInNoticePosted) {
                signInNoticePosted = coordinator.postSignInRequired()
            }
            RealtimeAuthenticationState.AUTHENTICATED -> if (signInNoticePosted) {
                if (coordinator.cancelSignInRequired()) signInNoticePosted = false
            }
            RealtimeAuthenticationState.UNKNOWN,
            RealtimeAuthenticationState.FORBIDDEN,
            -> Unit
        }
    }
}

fun interface AlertPlateLabelSource {
    suspend fun resolve(
        profileKey: String,
        camera: String,
        detectionIds: Set<String>,
        authorizedCameraIds: Set<String>,
    ): Set<String>
}

private data class EvaluationSignal(
    val awareness: AwarenessProcessState,
    val configuration: AlertConfigurationState,
    val privacy: PrivacyRepositoryState,
    val transport: RealtimeTransportState,
    val mode: AlertModeProcessState,
    val monitorReviewId: String?,
    val pause: NotificationPauseSnapshot,
)

private data class EvaluationSignature(
    val reviewObservations: Map<String, String>,
    val configurationMarker: AlertConfigurationState,
    val privacyMarker: PrivacyRepositoryState,
    val transportMarker: NotificationTransportMarker,
    val modeMarker: AlertModeProcessState,
    val monitorReviewId: String?,
    val pauseMarker: NotificationPauseSnapshot,
)

private data class NotificationTransportMarker(
    val authenticated: Boolean,
    val authorizationFreshness: AuthorizationFreshness,
    val allowedCameraIds: Set<String>,
    val cameraDisplayNames: Map<String, String>,
)

private fun RealtimeTransportState.notificationMarker(): NotificationTransportMarker =
    when (val scope = cameraScope) {
        is CameraScopeState.Fresh -> NotificationTransportMarker(
            authenticated = authenticationState == RealtimeAuthenticationState.AUTHENTICATED,
            authorizationFreshness = AuthorizationFreshness.FRESH,
            allowedCameraIds = scope.evidence.allowedCameraIds,
            cameraDisplayNames = scope.evidence.cameraDisplayNames,
        )
        is CameraScopeState.Stale -> NotificationTransportMarker(
            authenticated = authenticationState == RealtimeAuthenticationState.AUTHENTICATED,
            authorizationFreshness = AuthorizationFreshness.STALE,
            allowedCameraIds = scope.evidence.allowedCameraIds,
            cameraDisplayNames = scope.evidence.cameraDisplayNames,
        )
        CameraScopeState.Unknown -> NotificationTransportMarker(
            authenticated = authenticationState == RealtimeAuthenticationState.AUTHENTICATED,
            authorizationFreshness = AuthorizationFreshness.UNKNOWN,
            allowedCameraIds = emptySet(),
            cameraDisplayNames = emptyMap(),
        )
    }

internal fun RealtimeTransportState.notificationCameraDisplayName(cameraId: String): String = when (val scope = cameraScope) {
    is CameraScopeState.Fresh -> scope.evidence.cameraDisplayNames[cameraId]
    is CameraScopeState.Stale -> scope.evidence.cameraDisplayNames[cameraId]
    CameraScopeState.Unknown -> null
} ?: cameraId.replace('_', ' ')
    .trim()
    .split(Regex("\\s+"))
    .filter(String::isNotBlank)
    .joinToString(" ") { word -> word.replaceFirstChar(Char::uppercase) }
