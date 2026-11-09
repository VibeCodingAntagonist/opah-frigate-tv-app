package app.opah.tv.notifications

import app.opah.tv.awareness.AwarenessReview
import app.opah.tv.awareness.AwarenessReviewSeverity
import app.opah.tv.privacy.NotificationDisclosure
import app.opah.tv.privacy.PrivacyDecision
import app.opah.tv.privacy.PrivacyDecisionEngine
import app.opah.tv.privacy.PrivacyDenialReason
import app.opah.tv.privacy.PrivacyGrant
import app.opah.tv.privacy.PrivacyRequest
import app.opah.tv.privacy.PrivacySnapshot
import app.opah.tv.privacy.PrivacySurface
import app.opah.tv.privacy.PrivacyTarget
import app.opah.tv.privacy.RecognitionDisclosure
import java.util.TimeZone
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AlertNotificationPost(
    val profileKey: String,
    val reviewId: String,
    val identity: AndroidNotificationIdentity,
    val actionNonce: String,
    val model: NotificationRenderModel,
    val alertBehavior: NotificationAlertBehavior,
    val contentIntentKind: AlertContentIntentKind = AlertContentIntentKind.EVENT,
    val privacyEpoch: Long? = null,
    val policyVersion: Long? = null,
    val recognizedPlateLabels: Set<String> = emptySet(),
    val overlayCameraKey: String? = null,
    val presentationRevision: Long? = null,
) {
    init {
        require(profileKey.isNotBlank() && profileKey.length <= 128 && profileKey.none(Char::isISOControl))
        require(reviewId.isNotBlank() && reviewId.length <= 256 && reviewId.none(Char::isISOControl))
    }
}

enum class AlertContentIntentKind {
    EVENT,
    LOCAL_TEST,
}

interface AlertNotificationPlatform {
    suspend fun prepareThumbnail(request: AlertThumbnailRequest): String? = null
    suspend fun post(request: AlertNotificationPost): Boolean
    suspend fun cancel(identity: AndroidNotificationIdentity): Boolean
    suspend fun purgeEphemeralContent(profileKey: String, reviewId: String): Boolean
    suspend fun purgeProfileEphemeralContent(profileKey: String): Boolean = true
    suspend fun postSignInRequired(): Boolean = false
    suspend fun cancelSignInRequired(): Boolean = true
}

data class AlertThumbnailRequest(
    val profileKey: String,
    val reviewId: String,
    val cameraId: String,
    val thumbnailPath: String?,
    val privacy: NotificationPrivacy,
) {
    init {
        require(profileKey.isNotBlank() && profileKey.length <= 128)
        require(reviewId.isNotBlank() && reviewId.length <= 256)
        require(cameraId.isNotBlank() && cameraId.length <= 256)
        require(thumbnailPath == null || thumbnailPath.isNotBlank() && thumbnailPath.length <= 2_048)
        require(privacy in setOf(NotificationPrivacy.FULL_PREVIEW, NotificationPrivacy.BLURRED_PREVIEW))
    }
}

fun interface AlertPrivacySnapshotSource {
    suspend fun snapshot(): PrivacySnapshot
}

data class NotificationProcessingInput(
    val configuration: AlertConfigurationRecordView,
    val review: AwarenessReview,
    val authorizedCameraIds: Set<String>,
    val privacySnapshots: AlertPrivacySnapshotSource,
    val nowEpochMillis: Long,
    val timeZone: TimeZone,
    val currentFrigateMode: String? = null,
    val presentation: AlertPresentationContext = AlertPresentationContext(),
    val cameraDisplayName: String? = null,
    val recognizedPeople: Set<String> = emptySet(),
    val recognizedPlateLabels: Set<String> = emptySet(),
    val recordingAvailable: Boolean = false,
    val activityAvailable: Boolean = true,
) {
    init {
        require(nowEpochMillis >= 0L)
        require(authorizedCameraIds.size <= 10_000)
        require(configuration.profileKey == review.profileKey)
    }
}

/** A narrow immutable view keeps the coordinator independent of Room persistence details. */
data class AlertConfigurationRecordView(
    val profileKey: String,
    val enabled: Boolean,
    val policyVersion: Long,
    val policy: AlertPolicy,
    val snoozes: List<AlertSnooze>,
) {
    init {
        require(profileKey.isNotBlank() && profileKey.length <= 128 && profileKey.none(Char::isISOControl))
        require(policyVersion >= 0L)
        require(snoozes.size <= 512)
    }
}

sealed interface NotificationProcessingResult {
    data class NoChange(val reason: AlertDecisionReason) : NotificationProcessingResult
    data class Posted(val behavior: NotificationAlertBehavior) : NotificationProcessingResult
    data class Cancelled(val reason: AlertDecisionReason, val purged: Boolean) : NotificationProcessingResult
    data object Deferred : NotificationProcessingResult
    data object Unavailable : NotificationProcessingResult
}

/**
 * Serializes one Review item's policy, privacy, durable ledger, and platform operation.
 * Callers should avoid concurrent calls for the same Review identity.
 */
class NotificationCoordinator(
    private val ledgerStore: NotificationLedgerStore,
    private val platform: AlertNotificationPlatform,
    private val identities: NotificationIdentityFactory = NotificationIdentityFactory(),
    private val privacyEngine: PrivacyDecisionEngine = PrivacyDecisionEngine(),
    private val pause: NotificationPauseGate = NotificationPauseGate.NONE,
) {
    private val operationMutex = Mutex()

    suspend fun purgeProfileEphemeralContent(profileKey: String): Boolean = operationMutex.withLock {
        if (profileKey.isBlank() || profileKey.length > 128) return@withLock false
        runCatching { platform.purgeProfileEphemeralContent(profileKey) }.getOrDefault(false)
    }

    suspend fun postSignInRequired(): Boolean = operationMutex.withLock {
        runCatching { platform.postSignInRequired() }.getOrDefault(false)
    }

    suspend fun cancelSignInRequired(): Boolean = operationMutex.withLock {
        runCatching { platform.cancelSignInRequired() }.getOrDefault(false)
    }

    suspend fun dismiss(
        profileKey: String,
        reviewId: String,
        actionNonce: String,
        nowEpochMillis: Long,
    ): Boolean = operationMutex.withLock {
        if (nowEpochMillis < 0L || !validActionIdentity(profileKey, reviewId, actionNonce)) {
            return@withLock false
        }
        val record = when (val read = runCatching { ledgerStore.read(profileKey, reviewId) }.getOrNull()) {
            is NotificationLedgerReadResult.Available -> read.record
            NotificationLedgerReadResult.Missing,
            NotificationLedgerReadResult.Corrupt,
            null,
            -> return@withLock false
        }
        if (!record.notificationActive || record.actionNonce != actionNonce) return@withLock false
        val dismissed = record.copy(
            dismissedAtEpochMillis = nowEpochMillis,
            severityAtDismissal = record.highestPresentedSeverity,
            notificationActive = false,
            pendingOperation = null,
            terminalReason = AlertDecisionReason.DISMISSED.name,
            reconciledAtEpochMillis = nowEpochMillis,
        )
        runCatching { ledgerStore.upsert(dismissed) }.isSuccess
    }

    /**
     * Consumes a notification tap only while its opaque nonce still names the active ledger row.
     * The dismissal markers prevent metadata from resurrecting the notification, while a later
     * severity escalation may still notify normally.
     */
    suspend fun acknowledgeOpen(
        profileKey: String,
        reviewId: String,
        actionNonce: String,
        nowEpochMillis: Long,
    ): Boolean = operationMutex.withLock {
        if (nowEpochMillis < 0L || !validActionIdentity(profileKey, reviewId, actionNonce)) {
            return@withLock false
        }
        val record = (runCatching { ledgerStore.read(profileKey, reviewId) }.getOrNull() as?
            NotificationLedgerReadResult.Available)?.record
            ?.takeIf { it.notificationActive && it.actionNonce == actionNonce }
            ?: return@withLock false
        runCatching {
            ledgerStore.upsert(
                record.copy(
                    dismissedAtEpochMillis = nowEpochMillis,
                    severityAtDismissal = record.highestPresentedSeverity,
                    notificationActive = false,
                    pendingOperation = null,
                    terminalReason = AlertDecisionReason.OPENED.name,
                    reconciledAtEpochMillis = nowEpochMillis,
                ),
            )
        }.isSuccess
    }

    suspend fun activeActionRecord(
        profileKey: String,
        reviewId: String,
        actionNonce: String,
    ): NotificationLedgerRecord? = operationMutex.withLock {
        if (!validActionIdentity(profileKey, reviewId, actionNonce)) return@withLock null
        val record = (runCatching { ledgerStore.read(profileKey, reviewId) }.getOrNull() as?
            NotificationLedgerReadResult.Available)?.record
        record?.takeIf { it.notificationActive && it.actionNonce == actionNonce }
    }

    private fun validActionIdentity(
        profileKey: String,
        reviewId: String,
        actionNonce: String,
    ): Boolean = listOf(
        profileKey to 128,
        reviewId to 256,
        actionNonce to 256,
    ).all { (value, maximumLength) ->
        value.isNotBlank() && value.length <= maximumLength && value.none { character ->
            character.isISOControl() || Character.getType(character) == Character.FORMAT.toInt()
        }
    }

    suspend fun cancelStored(
        record: NotificationLedgerRecord,
        nowEpochMillis: Long,
        reason: AlertDecisionReason = AlertDecisionReason.UNAUTHORIZED_CAMERA,
    ): NotificationProcessingResult = operationMutex.withLock {
        require(nowEpochMillis >= 0L)
        val expectedIdentity = identities.eventIdentity(record.profileKey, record.reviewId)
        val storedIdentity = AndroidNotificationIdentity(record.notificationTag)
        val operationId = identities.newActionNonce()
        val staged = record.copy(
            actionNonce = identities.newActionNonce(),
            pendingOperation = PendingNotificationOperation(
                operationId = operationId,
                kind = PendingNotificationOperationKind.CANCEL,
                stagedAtEpochMillis = nowEpochMillis,
            ),
            terminalReason = reason.name,
            reconciledAtEpochMillis = nowEpochMillis,
        )
        if (!stage(staged)) {
            runCatching { platform.cancel(expectedIdentity) }
            if (storedIdentity != expectedIdentity) runCatching { platform.cancel(storedIdentity) }
            runCatching { platform.purgeEphemeralContent(record.profileKey, record.reviewId) }
            return NotificationProcessingResult.Deferred
        }
        val expectedCancelled = runCatching { platform.cancel(expectedIdentity) }.getOrDefault(false)
        val storedCancelled = storedIdentity == expectedIdentity ||
            runCatching { platform.cancel(storedIdentity) }.getOrDefault(false)
        val purged = runCatching {
            platform.purgeEphemeralContent(record.profileKey, record.reviewId)
        }.getOrDefault(false)
        if (!expectedCancelled || !storedCancelled || !purged) return NotificationProcessingResult.Deferred
        return if (settle(staged, active = false, nowEpochMillis)) {
            NotificationProcessingResult.Cancelled(reason, purged = true)
        } else {
            NotificationProcessingResult.Deferred
        }
    }

    suspend fun process(input: NotificationProcessingInput): NotificationProcessingResult =
        operationMutex.withLock { processLocked(input) }

    private suspend fun processLocked(input: NotificationProcessingInput): NotificationProcessingResult {
        val admission = pause.snapshot()
        val review = input.review
        val identity = identities.eventIdentity(review.profileKey, review.id)
        var recoveredPendingOperation = false
        val prior = when (val read = runCatching {
            ledgerStore.read(review.profileKey, review.id)
        }.getOrElse {
            safeCancelAndPurge(input, identity)
            return NotificationProcessingResult.Unavailable
        }) {
            NotificationLedgerReadResult.Missing -> null
            is NotificationLedgerReadResult.Available -> {
                recoveredPendingOperation = read.record.pendingOperation != null
                recoverPending(input, identity, read.record)
                    ?: return NotificationProcessingResult.Deferred
            }
            NotificationLedgerReadResult.Corrupt -> {
                safeCancelAndPurge(input, identity)
                return NotificationProcessingResult.Unavailable
            }
        }

        if (admission.suppressesFrigate(review.startEpochSeconds) || prior?.terminalReason == "SHARED_PAUSE") {
            return suppressPaused(input, identity, prior)
        }
        val initialPrivacy = runCatching { input.privacySnapshots.snapshot() }
            .getOrElse {
                return executeCancel(
                    input,
                    identity,
                    prior,
                    AlertDecisionReason.NEVER_NOTIFY,
                    purge = true,
                )
            }
        val privacyRequest = PrivacyRequest(
            surface = PrivacySurface.NOTIFICATION,
            target = PrivacyTarget.Review(
                reviewId = review.id,
                cameraId = review.camera.orEmpty(),
                containsRecognition = input.recognizedPeople.isNotEmpty() ||
                    input.recognizedPlateLabels.isNotEmpty(),
            ),
            expectedPrivacyEpoch = initialPrivacy.epoch,
        )
        val privacyDecision = privacyEngine.decide(privacyRequest, initialPrivacy)
        val grant = privacyDecision.grantOrNull()
            ?: return executeCancel(
                input,
                identity,
                prior,
                privacyDecision.alertReason(),
                purge = true,
            )

        val privacy = AlertPrivacyContext(
            globalPrivacy = grant.notificationDisclosure.toNotificationPrivacy(),
        )
        val policy = if (input.configuration.enabled) {
            input.configuration.policy
        } else {
            input.configuration.policy.copy(mode = AlertMode.OFF)
        }
        val baseEvaluation = AlertEvaluationInput(
            review = review,
            authorizedCameraIds = input.authorizedCameraIds,
            policy = policy,
            currentFrigateMode = input.currentFrigateMode,
            nowEpochMillis = input.nowEpochMillis,
            timeZone = input.timeZone,
            snoozes = input.configuration.snoozes,
            privacy = privacy,
            presentation = input.presentation,
            ledger = prior?.evaluatorLedger(),
            recognizedPlateLabels = input.recognizedPlateLabels,
            recordingAvailable = input.recordingAvailable,
            activityAvailable = input.activityAvailable,
        )
        val firstDecision = AlertEvaluator.evaluate(baseEvaluation)
        if (!firstDecision.eligible) {
            val command = NotificationLifecycleReducer.reduce(baseEvaluation) {
                error("An ineligible alert must not render")
            }
            return execute(input, identity, prior, command, null)
        }

        val thumbnailVersion = if (
            firstDecision.privacyLevel in setOf(
                NotificationPrivacy.FULL_PREVIEW,
                NotificationPrivacy.BLURRED_PREVIEW,
            ) && review.camera != null
        ) {
            val fetchPrivacy = runCatching { input.privacySnapshots.snapshot() }.getOrNull()
            val stillAllowed = fetchPrivacy?.let { snapshot ->
                privacyEngine.revalidate(grant, privacyRequest, snapshot).grantOrNull() != null
            } == true
            if (!stillAllowed) {
                return executeCancel(
                    input,
                    identity,
                    prior,
                    AlertDecisionReason.NEVER_NOTIFY,
                    purge = true,
                )
            }
            runCatching {
                platform.prepareThumbnail(
                    AlertThumbnailRequest(
                        profileKey = review.profileKey,
                        reviewId = review.id,
                        cameraId = review.camera,
                        thumbnailPath = review.thumbnail,
                        privacy = firstDecision.privacyLevel,
                    ),
                )
            }.getOrNull()
        } else {
            if (prior != null && !recoveredPendingOperation) {
                runCatching { platform.purgeEphemeralContent(review.profileKey, review.id) }
            }
            null
        }

        val renderModel = NotificationContentRenderer.render(
            decision = firstDecision,
            input = NotificationContentInput(
                review = review,
                cameraDisplayName = input.cameraDisplayName,
                recognizedPeople = input.recognizedPeople,
                recognizedPlateLabels = input.recognizedPlateLabels,
                showRecognizedPeople = grant.recognitionDisclosure.showsNames(),
                showPlateLabels = grant.recognitionDisclosure.showsPlates(),
                thumbnailContentVersion = thumbnailVersion,
            ),
        )
        val finalEvaluation = baseEvaluation.copy(contentFingerprint = renderModel.contentFingerprint)
        val command = NotificationLifecycleReducer.reduce(finalEvaluation) { renderModel }
        if (command is NotificationLifecycleCommand.Post) {
            val latestPrivacy = runCatching { input.privacySnapshots.snapshot() }.getOrNull()
            val stillAllowed = latestPrivacy?.let { snapshot ->
                privacyEngine.revalidate(grant, privacyRequest, snapshot).grantOrNull() != null
            } == true
            if (!stillAllowed) {
                return executeCancel(
                    input,
                    identity,
                    prior,
                    AlertDecisionReason.NEVER_NOTIFY,
                    purge = true,
                )
            }
        }
        if (!pause.snapshot().permits(admission.revision)) return suppressPaused(input, identity, prior)
        return execute(input, identity, prior, command, initialPrivacy.epoch, admission.revision)
    }

    private suspend fun suppressPaused(input: NotificationProcessingInput, identity: AndroidNotificationIdentity,
        prior: NotificationLedgerRecord?): NotificationProcessingResult {
        if (prior?.terminalReason == "SHARED_PAUSE" && !prior.notificationActive && prior.pendingOperation == null)
            return NotificationProcessingResult.NoChange(AlertDecisionReason.SHARED_PAUSE)
        val suppressed = (prior ?: emptyRecord(input, identity)).copy(actionNonce = identities.newActionNonce(),
            contentFingerprint = null, openTarget = null, notificationActive = false)
        return executeCancel(input, identity, suppressed, AlertDecisionReason.SHARED_PAUSE, purge = true)
    }

    private suspend fun execute(
        input: NotificationProcessingInput,
        identity: AndroidNotificationIdentity,
        prior: NotificationLedgerRecord?,
        command: NotificationLifecycleCommand,
        privacyEpoch: Long?,
        presentationRevision: Long? = null,
    ): NotificationProcessingResult = when (command) {
        is NotificationLifecycleCommand.NoOp -> NotificationProcessingResult.NoChange(command.reason)
        is NotificationLifecycleCommand.Post -> executePost(
            input = input,
            identity = identity,
            prior = prior,
            command = command,
            privacyEpoch = requireNotNull(privacyEpoch),
            presentationRevision = presentationRevision,
        )
        is NotificationLifecycleCommand.Cancel -> executeCancel(
            input,
            identity,
            prior,
            command.reason,
            purge = false,
        )
        is NotificationLifecycleCommand.CancelAndPurge -> executeCancel(
            input,
            identity,
            prior,
            command.reason,
            purge = true,
        )
    }

    private suspend fun executePost(
        input: NotificationProcessingInput,
        identity: AndroidNotificationIdentity,
        prior: NotificationLedgerRecord?,
        command: NotificationLifecycleCommand.Post,
        privacyEpoch: Long,
        presentationRevision: Long?,
    ): NotificationProcessingResult {
        val operationId = identities.newActionNonce()
        val actionNonce = prior?.actionNonce ?: identities.newActionNonce()
        val severity = input.review.severity
        val audible = command.alertBehavior != NotificationAlertBehavior.SILENT_UPDATE
        val staged = NotificationLedgerRecord(
            profileKey = input.review.profileKey,
            reviewId = input.review.id,
            notificationTag = identity.tag,
            actionNonce = actionNonce,
            lifecycle = input.review.lifecycle,
            highestPresentedSeverity = maxSeverity(prior?.highestPresentedSeverity, severity),
            highestAudibleSeverity = if (audible) {
                maxSeverity(prior?.highestAudibleSeverity, severity)
            } else {
                prior?.highestAudibleSeverity
            },
            contentFingerprint = command.renderModel.contentFingerprint,
            lastPostAtEpochMillis = input.nowEpochMillis,
            lastAlertAtEpochMillis = input.nowEpochMillis.takeIf { audible }
                ?: prior?.lastAlertAtEpochMillis,
            dismissedAtEpochMillis = prior?.dismissedAtEpochMillis,
            severityAtDismissal = prior?.severityAtDismissal,
            openTarget = command.renderModel.openTarget,
            policyVersion = input.configuration.policyVersion,
            privacyEpoch = privacyEpoch,
            notificationActive = prior?.notificationActive == true,
            pendingOperation = PendingNotificationOperation(
                operationId = operationId,
                kind = PendingNotificationOperationKind.POST,
                stagedAtEpochMillis = input.nowEpochMillis,
            ),
            terminalReason = null,
            reconciledAtEpochMillis = input.nowEpochMillis,
        )
        if (!pause.snapshot().permits(presentationRevision ?: pause.snapshot().revision)) return suppressPaused(input, identity, prior)
        if (!stage(staged)) return NotificationProcessingResult.Deferred
        val posted = runCatching {
            platform.post(
                AlertNotificationPost(
                    profileKey = input.review.profileKey,
                    reviewId = input.review.id,
                    identity = identity,
                    actionNonce = actionNonce,
                    model = command.renderModel,
                    alertBehavior = command.alertBehavior,
                    privacyEpoch = privacyEpoch,
                    policyVersion = input.configuration.policyVersion,
                    recognizedPlateLabels = input.recognizedPlateLabels.toSet(),
                    overlayCameraKey = identities.eventIdentity(input.review.profileKey, requireNotNull(input.review.camera)).tag,
                    presentationRevision = presentationRevision,
                ),
            )
        }.getOrDefault(false)
        if (!pause.snapshot().permits(presentationRevision ?: pause.snapshot().revision)) return suppressPaused(input, identity, staged)
        if (!posted) return NotificationProcessingResult.Deferred
        return if (settle(staged, active = true, input.nowEpochMillis)) {
            NotificationProcessingResult.Posted(command.alertBehavior)
        } else {
            NotificationProcessingResult.Deferred
        }
    }

    private suspend fun executeCancel(
        input: NotificationProcessingInput,
        identity: AndroidNotificationIdentity,
        prior: NotificationLedgerRecord?,
        reason: AlertDecisionReason,
        purge: Boolean,
    ): NotificationProcessingResult {
        val operationId = identities.newActionNonce()
        val staged = (prior ?: emptyRecord(input, identity)).copy(
            lifecycle = input.review.lifecycle,
            policyVersion = input.configuration.policyVersion,
            pendingOperation = PendingNotificationOperation(
                operationId = operationId,
                kind = PendingNotificationOperationKind.CANCEL,
                stagedAtEpochMillis = input.nowEpochMillis,
            ),
            terminalReason = reason.name,
            reconciledAtEpochMillis = input.nowEpochMillis,
        )
        if (!stage(staged)) {
            safeCancelAndPurge(input, identity, purge)
            return NotificationProcessingResult.Deferred
        }
        val cancelled = runCatching { platform.cancel(identity) }.getOrDefault(false)
        val purged = !purge || runCatching {
            platform.purgeEphemeralContent(input.review.profileKey, input.review.id)
        }.getOrDefault(false)
        if (!cancelled || !purged) return NotificationProcessingResult.Deferred
        return if (settle(staged, active = false, input.nowEpochMillis)) {
            NotificationProcessingResult.Cancelled(reason, purge)
        } else {
            NotificationProcessingResult.Deferred
        }
    }

    private suspend fun recoverPending(
        input: NotificationProcessingInput,
        identity: AndroidNotificationIdentity,
        record: NotificationLedgerRecord,
    ): NotificationLedgerRecord? {
        val pending = record.pendingOperation ?: return record
        val cancelled = runCatching { platform.cancel(identity) }.getOrDefault(false)
        val purged = runCatching {
            platform.purgeEphemeralContent(input.review.profileKey, input.review.id)
        }.getOrDefault(false)
        if (!cancelled || !purged || !runCatching {
                ledgerStore.settle(
                    record.profileKey,
                    record.reviewId,
                    pending.operationId,
                    notificationActive = false,
                    reconciledAtEpochMillis = input.nowEpochMillis,
                )
            }.getOrDefault(false)
        ) return null
        return record.copy(
            notificationActive = false,
            // The staged content was not durably confirmed. Retain audible history so an
            // uncertain platform call cannot replay sound, but force content reconciliation.
            contentFingerprint = null,
            pendingOperation = null,
            reconciledAtEpochMillis = input.nowEpochMillis,
        )
    }

    private fun emptyRecord(
        input: NotificationProcessingInput,
        identity: AndroidNotificationIdentity,
    ) = NotificationLedgerRecord(
        profileKey = input.review.profileKey,
        reviewId = input.review.id,
        notificationTag = identity.tag,
        actionNonce = identities.newActionNonce(),
        lifecycle = input.review.lifecycle,
        highestPresentedSeverity = null,
        highestAudibleSeverity = null,
        contentFingerprint = null,
        lastPostAtEpochMillis = null,
        lastAlertAtEpochMillis = null,
        dismissedAtEpochMillis = null,
        severityAtDismissal = null,
        openTarget = null,
        policyVersion = input.configuration.policyVersion,
        privacyEpoch = 0L,
        notificationActive = false,
        pendingOperation = null,
        terminalReason = null,
        reconciledAtEpochMillis = input.nowEpochMillis,
    )

    private suspend fun stage(record: NotificationLedgerRecord): Boolean =
        runCatching { ledgerStore.upsert(record) }.isSuccess

    private suspend fun settle(
        record: NotificationLedgerRecord,
        active: Boolean,
        now: Long,
    ): Boolean = runCatching {
        ledgerStore.settle(
            record.profileKey,
            record.reviewId,
            requireNotNull(record.pendingOperation).operationId,
            notificationActive = active,
            reconciledAtEpochMillis = now,
        )
    }.getOrDefault(false)

    private suspend fun safeCancelAndPurge(
        input: NotificationProcessingInput,
        identity: AndroidNotificationIdentity,
        purge: Boolean = true,
    ) {
        runCatching { platform.cancel(identity) }
        if (purge) runCatching {
            platform.purgeEphemeralContent(input.review.profileKey, input.review.id)
        }
    }
}

private fun PrivacyDecision.grantOrNull(): PrivacyGrant? = when (this) {
    is PrivacyDecision.Allow -> grant
    is PrivacyDecision.AllowRedacted -> grant
    is PrivacyDecision.Deny,
    is PrivacyDecision.RequirePin,
    -> null
}

private fun PrivacyDecision.alertReason(): AlertDecisionReason = when (this) {
    is PrivacyDecision.RequirePin -> AlertDecisionReason.PIN_LOCKED
    is PrivacyDecision.Deny -> when (reason) {
        PrivacyDenialReason.CAMERA_NOT_AUTHORIZED,
        PrivacyDenialReason.AUTHORIZATION_UNAVAILABLE,
        -> AlertDecisionReason.UNAUTHORIZED_CAMERA
        PrivacyDenialReason.PRIVATE_CONTENT_HIDDEN,
        PrivacyDenialReason.GUEST_CONTENT_BLOCKED,
        -> AlertDecisionReason.PRIVATE_CAMERA
        else -> AlertDecisionReason.NEVER_NOTIFY
    }
    is PrivacyDecision.Allow,
    is PrivacyDecision.AllowRedacted,
    -> error("Allowed privacy decision does not have a denial reason")
}

private fun NotificationDisclosure?.toNotificationPrivacy(): NotificationPrivacy = when (this) {
    NotificationDisclosure.FULL_PREVIEW -> NotificationPrivacy.FULL_PREVIEW
    NotificationDisclosure.BLURRED_PREVIEW -> NotificationPrivacy.BLURRED_PREVIEW
    NotificationDisclosure.TEXT_ONLY -> NotificationPrivacy.TEXT_ONLY
    NotificationDisclosure.NEVER,
    null,
    -> NotificationPrivacy.NEVER_NOTIFY
}

private fun RecognitionDisclosure.showsNames(): Boolean = when (this) {
    RecognitionDisclosure.SHOW_ALL,
    RecognitionDisclosure.HIDE_PLATES,
    -> true
    RecognitionDisclosure.HIDE_NAMES,
    RecognitionDisclosure.HIDE_ALL,
    -> false
}

private fun RecognitionDisclosure.showsPlates(): Boolean = when (this) {
    RecognitionDisclosure.SHOW_ALL,
    RecognitionDisclosure.HIDE_NAMES,
    -> true
    RecognitionDisclosure.HIDE_PLATES,
    RecognitionDisclosure.HIDE_ALL,
    -> false
}

private fun maxSeverity(
    first: AwarenessReviewSeverity?,
    second: AwarenessReviewSeverity,
): AwarenessReviewSeverity = if (first.severityRank() >= second.severityRank()) {
    requireNotNull(first)
} else {
    second
}

private fun AwarenessReviewSeverity?.severityRank(): Int = when (this) {
    null,
    AwarenessReviewSeverity.UNKNOWN,
    -> 0
    AwarenessReviewSeverity.SIGNIFICANT_MOTION -> 1
    AwarenessReviewSeverity.DETECTION -> 2
    AwarenessReviewSeverity.ALERT -> 3
}
