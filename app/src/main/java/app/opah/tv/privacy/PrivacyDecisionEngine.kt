package app.opah.tv.privacy

enum class AuthorizationFreshness {
    FRESH,
    STALE,
    UNKNOWN,
}

enum class PinScope {
    SETTINGS,
    ADMINISTRATIVE_ACTIONS,
    DESTRUCTIVE_ACTIONS,
    CLIPS_AND_INCIDENTS,
    ACTIVITY_HISTORY_SEARCH,
    PRIVATE_CAMERAS,
    HOME_ASSISTANT_ACTIONS,
    EXIT_GUEST_MODE,
}

enum class PrivacySurface {
    HOME,
    CAMERA_CATALOG,
    VIEW,
    LIVE_PLAYBACK,
    ACTIVITY,
    HISTORY,
    SEARCH,
    CLIP,
    INCIDENT,
    NOTIFICATION,
    MONITOR,
    BRIEFING,
    DEEP_LINK,
    SETTINGS,
    ACTION,
}

enum class RecognitionDisclosure(val restriction: Int) {
    SHOW_ALL(0),
    HIDE_NAMES(1),
    HIDE_PLATES(1),
    HIDE_ALL(2),
}

enum class NotificationDisclosure(val restriction: Int) {
    FULL_PREVIEW(0),
    BLURRED_PREVIEW(1),
    TEXT_ONLY(2),
    NEVER(3),
}

enum class PrivacyLocalAction(
    val pinScope: PinScope,
    val requiresAuthenticatedSession: Boolean,
    val permittedSurfaces: Set<PrivacySurface>,
) {
    OPEN_SETTINGS(
        PinScope.SETTINGS,
        false,
        setOf(PrivacySurface.ACTION, PrivacySurface.SETTINGS),
    ),
    ADMINISTRATIVE_CHANGE(
        PinScope.ADMINISTRATIVE_ACTIONS,
        true,
        setOf(PrivacySurface.ACTION),
    ),
    DESTRUCTIVE_OPERATION(
        PinScope.DESTRUCTIVE_ACTIONS,
        true,
        setOf(PrivacySurface.ACTION),
    ),
    EXIT_GUEST_MODE(
        PinScope.EXIT_GUEST_MODE,
        false,
        setOf(PrivacySurface.ACTION),
    ),
}

sealed interface PrivacyTarget {
    val cameraIds: Set<String>
    val permitsCameraFiltering: Boolean
    val requiresAuthenticatedSession: Boolean
    val pinScope: PinScope?
    val containsRecognition: Boolean

    data class Camera(
        val cameraId: String,
        override val containsRecognition: Boolean = false,
    ) : PrivacyTarget {
        override val cameraIds: Set<String> = setOf(cameraId)
        override val permitsCameraFiltering: Boolean = false
        override val requiresAuthenticatedSession: Boolean = true
        override val pinScope: PinScope? = null
    }

    data class Review(
        val reviewId: String,
        val cameraId: String,
        override val containsRecognition: Boolean = false,
    ) : PrivacyTarget {
        override val cameraIds: Set<String> = setOf(cameraId)
        override val permitsCameraFiltering: Boolean = false
        override val requiresAuthenticatedSession: Boolean = true
        override val pinScope: PinScope? = PinScope.ACTIVITY_HISTORY_SEARCH
    }

    data class CameraCollection(
        val collectionId: String,
        override val cameraIds: Set<String>,
        override val containsRecognition: Boolean = false,
    ) : PrivacyTarget {
        override val permitsCameraFiltering: Boolean = true
        override val requiresAuthenticatedSession: Boolean = true
        override val pinScope: PinScope? = null
    }

    data class ClipOrIncident(
        val itemId: String,
        val cameraId: String?,
        val additionalCameraIds: Set<String> = emptySet(),
    ) : PrivacyTarget {
        override val cameraIds: Set<String> = cameraId?.let(::setOf).orEmpty() + additionalCameraIds
        override val permitsCameraFiltering: Boolean = false
        override val requiresAuthenticatedSession: Boolean = true
        override val pinScope: PinScope = PinScope.CLIPS_AND_INCIDENTS
        override val containsRecognition: Boolean = false
    }

    data class LocalAction(
        val action: PrivacyLocalAction,
    ) : PrivacyTarget {
        override val cameraIds: Set<String> = emptySet()
        override val permitsCameraFiltering: Boolean = false
        override val containsRecognition: Boolean = false
        override val requiresAuthenticatedSession: Boolean = action.requiresAuthenticatedSession
        override val pinScope: PinScope = action.pinScope
    }
}

data class PrivacyRequest(
    val surface: PrivacySurface,
    val target: PrivacyTarget,
    /** Epoch under which delayed work was originally authorized. */
    val expectedPrivacyEpoch: Long,
)

data class PrivacySnapshot(
    val epoch: Long,
    val authenticated: Boolean,
    val authorizationFreshness: AuthorizationFreshness,
    val allowedCameraIds: Set<String>,
    val guestMode: Boolean,
    val privateCameraIds: Set<String>,
    val pinConfigured: Boolean,
    val protectedScopes: Set<PinScope>,
    val pinUnlockProof: PinUnlockProof? = null,
    val ownerRecognitionDisclosure: RecognitionDisclosure = RecognitionDisclosure.SHOW_ALL,
    val guestRecognitionDisclosure: RecognitionDisclosure = RecognitionDisclosure.HIDE_ALL,
    val globalNotificationDisclosure: NotificationDisclosure = NotificationDisclosure.TEXT_ONLY,
    val cameraNotificationDisclosure: Map<String, NotificationDisclosure> = emptyMap(),
    val guestNotificationDisclosure: NotificationDisclosure = NotificationDisclosure.TEXT_ONLY,
    val privacyPolicyAvailable: Boolean = true,
)

data class PinUnlockProof(
    val privacyEpoch: Long,
    val scopes: Set<PinScope>,
)

data class PrivacyGrantBinding(
    val privacyEpoch: Long,
    val surface: PrivacySurface,
    val target: PrivacyTarget,
)

class PrivacyGrant private constructor(
    /** Exact authorization context; this grant must not be reused for another request. */
    val binding: PrivacyGrantBinding,
    visibleCameraIds: Set<String>,
    val recognitionDisclosure: RecognitionDisclosure,
    val notificationDisclosure: NotificationDisclosure?,
) {
    val visibleCameraIds: Set<String> = visibleCameraIds.toSet()

    override fun equals(other: Any?): Boolean =
        other is PrivacyGrant &&
            binding == other.binding &&
            visibleCameraIds == other.visibleCameraIds &&
            recognitionDisclosure == other.recognitionDisclosure &&
            notificationDisclosure == other.notificationDisclosure

    override fun hashCode(): Int {
        var result = binding.hashCode()
        result = 31 * result + visibleCameraIds.hashCode()
        result = 31 * result + recognitionDisclosure.hashCode()
        result = 31 * result + (notificationDisclosure?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "PrivacyGrant(binding=$binding, visibleCameraIds=$visibleCameraIds, " +
            "recognitionDisclosure=$recognitionDisclosure, " +
            "notificationDisclosure=$notificationDisclosure)"

    companion object {
        internal fun issue(
            binding: PrivacyGrantBinding,
            visibleCameraIds: Set<String>,
            recognitionDisclosure: RecognitionDisclosure,
            notificationDisclosure: NotificationDisclosure?,
        ): PrivacyGrant = PrivacyGrant(
            binding = binding,
            visibleCameraIds = visibleCameraIds,
            recognitionDisclosure = recognitionDisclosure,
            notificationDisclosure = notificationDisclosure,
        )
    }
}

enum class PrivacyRedactionReason {
    FILTERED_UNAUTHORIZED_CAMERA,
    FILTERED_PRIVATE_CAMERA,
    RECOGNITION_POLICY,
    NOTIFICATION_POLICY,
}

enum class PrivacyDenialReason {
    STALE_PRIVACY_EPOCH,
    GRANT_BINDING_MISMATCH,
    GRANT_CONTENT_MISMATCH,
    INVALID_TARGET,
    INVALID_PRIVACY_STATE,
    NOT_AUTHENTICATED,
    AUTHORIZATION_UNAVAILABLE,
    CAMERA_NOT_AUTHORIZED,
    PARTIAL_CAMERA_AUTHORIZATION,
    PRIVATE_CONTENT_HIDDEN,
    GUEST_CONTENT_BLOCKED,
    GUEST_ACTION_BLOCKED,
    NOTIFICATION_DISABLED,
}

sealed interface PrivacyDecision {
    data class Allow(val grant: PrivacyGrant) : PrivacyDecision

    data class AllowRedacted(
        val grant: PrivacyGrant,
        val reasons: Set<PrivacyRedactionReason>,
    ) : PrivacyDecision

    data class RequirePin(val scope: PinScope) : PrivacyDecision

    data class Deny(val reason: PrivacyDenialReason) : PrivacyDecision
}

/**
 * Pure fail-closed composition for server authorization, local privacy, Guest
 * Mode, PIN scopes, and surface disclosure. Callers must re-evaluate rather
 * than reusing a decision after [PrivacySnapshot.epoch] changes.
 */
class PrivacyDecisionEngine {
    fun decide(
        request: PrivacyRequest,
        snapshot: PrivacySnapshot,
    ): PrivacyDecision {
        val frozenRequest = request.immutableCopyOrNull()
            ?: return PrivacyDecision.Deny(PrivacyDenialReason.INVALID_TARGET)
        val frozenSnapshot = snapshot.immutableCopyOrNull()
            ?: return PrivacyDecision.Deny(PrivacyDenialReason.INVALID_PRIVACY_STATE)
        return decideFrozen(frozenRequest, frozenSnapshot)
    }

    private fun decideFrozen(
        request: PrivacyRequest,
        snapshot: PrivacySnapshot,
    ): PrivacyDecision {
        if (
            request.expectedPrivacyEpoch < 0 ||
            snapshot.epoch < 0 ||
            request.expectedPrivacyEpoch != snapshot.epoch
        ) {
            return PrivacyDecision.Deny(PrivacyDenialReason.STALE_PRIVACY_EPOCH)
        }
        if (!request.target.isValid()) {
            return PrivacyDecision.Deny(PrivacyDenialReason.INVALID_TARGET)
        }
        if (!request.hasValidSurfaceTargetCombination()) {
            return PrivacyDecision.Deny(PrivacyDenialReason.INVALID_TARGET)
        }
        if (request.isNoPinGuestExitRecovery(snapshot)) {
            return PrivacyDecision.Allow(
                PrivacyGrant.issue(
                    binding = request.toGrantBinding(),
                    visibleCameraIds = emptySet(),
                    recognitionDisclosure = RecognitionDisclosure.HIDE_ALL,
                    notificationDisclosure = null,
                ),
            )
        }
        if (!snapshot.isValid()) {
            return PrivacyDecision.Deny(PrivacyDenialReason.INVALID_PRIVACY_STATE)
        }
        if (request.target.requiresAuthenticatedSession && !snapshot.authenticated) {
            return PrivacyDecision.Deny(PrivacyDenialReason.NOT_AUTHENTICATED)
        }

        val requestedCameras = request.target.cameraIds
        val redactions = linkedSetOf<PrivacyRedactionReason>()
        var visibleCameras = requestedCameras

        if (requestedCameras.isNotEmpty()) {
            if (snapshot.authorizationFreshness != AuthorizationFreshness.FRESH) {
                return PrivacyDecision.Deny(PrivacyDenialReason.AUTHORIZATION_UNAVAILABLE)
            }

            visibleCameras = requestedCameras.intersect(snapshot.allowedCameraIds)
            if (visibleCameras.isEmpty()) {
                return PrivacyDecision.Deny(PrivacyDenialReason.CAMERA_NOT_AUTHORIZED)
            }
            if (visibleCameras.size != requestedCameras.size) {
                if (!request.target.permitsCameraFiltering) {
                    return PrivacyDecision.Deny(PrivacyDenialReason.PARTIAL_CAMERA_AUTHORIZATION)
                }
                redactions += PrivacyRedactionReason.FILTERED_UNAUTHORIZED_CAMERA
            }
        }

        if (snapshot.guestMode) {
            if (request.surface in GUEST_BLOCKED_SURFACES || request.target.pinScope in GUEST_BLOCKED_SCOPES) {
                return PrivacyDecision.Deny(PrivacyDenialReason.GUEST_CONTENT_BLOCKED)
            }
            if (request.target.pinScope in GUEST_BLOCKED_ACTION_SCOPES) {
                return PrivacyDecision.Deny(PrivacyDenialReason.GUEST_ACTION_BLOCKED)
            }

            visibleCameras = visibleCameras - snapshot.privateCameraIds
            if (requestedCameras.isNotEmpty() && visibleCameras.isEmpty()) {
                return PrivacyDecision.Deny(PrivacyDenialReason.PRIVATE_CONTENT_HIDDEN)
            }
            if (visibleCameras.size != requestedCameras.intersect(snapshot.allowedCameraIds).size) {
                if (!request.target.permitsCameraFiltering) {
                    return PrivacyDecision.Deny(PrivacyDenialReason.PRIVATE_CONTENT_HIDDEN)
                }
                redactions += PrivacyRedactionReason.FILTERED_PRIVATE_CAMERA
            }
        } else if (
            visibleCameras.any(snapshot.privateCameraIds::contains) &&
            PinScope.PRIVATE_CAMERAS in snapshot.protectedScopes &&
            PinScope.PRIVATE_CAMERAS !in snapshot.unlockedScopes()
        ) {
            return PrivacyDecision.RequirePin(PinScope.PRIVATE_CAMERAS)
        }

        request.target.pinScope?.let { requiredScope ->
            val requiresPin = if (requiredScope == PinScope.EXIT_GUEST_MODE) {
                snapshot.guestMode && snapshot.pinConfigured
            } else {
                requiredScope in snapshot.protectedScopes
            }
            if (requiresPin && requiredScope !in snapshot.unlockedScopes()) {
                return PrivacyDecision.RequirePin(requiredScope)
            }
        }

        request.surface.requiredPinScope()?.let { requiredScope ->
            if (
                requiredScope in snapshot.protectedScopes &&
                requiredScope !in snapshot.unlockedScopes()
            ) {
                return PrivacyDecision.RequirePin(requiredScope)
            }
        }

        val recognitionDisclosure = if (snapshot.guestMode) {
            stricterRecognition(
                snapshot.ownerRecognitionDisclosure,
                snapshot.guestRecognitionDisclosure,
            )
        } else {
            snapshot.ownerRecognitionDisclosure
        }
        if (request.target.containsRecognition && recognitionDisclosure != RecognitionDisclosure.SHOW_ALL) {
            redactions += PrivacyRedactionReason.RECOGNITION_POLICY
        }

        val notificationDisclosure = if (request.surface == PrivacySurface.NOTIFICATION) {
            notificationDisclosure(visibleCameras, snapshot)
        } else {
            null
        }
        if (notificationDisclosure == NotificationDisclosure.NEVER) {
            return PrivacyDecision.Deny(PrivacyDenialReason.NOTIFICATION_DISABLED)
        }
        if (notificationDisclosure != null && notificationDisclosure != NotificationDisclosure.FULL_PREVIEW) {
            redactions += PrivacyRedactionReason.NOTIFICATION_POLICY
        }

        val grant = PrivacyGrant.issue(
            binding = request.toGrantBinding(),
            visibleCameraIds = visibleCameras.toSet(),
            recognitionDisclosure = recognitionDisclosure,
            notificationDisclosure = notificationDisclosure,
        )
        return if (redactions.isEmpty()) {
            PrivacyDecision.Allow(grant)
        } else {
            PrivacyDecision.AllowRedacted(grant, redactions)
        }
    }

    /**
     * Re-evaluates delayed work against current state and proves that the grant
     * was issued for the exact surface, target, and privacy epoch being used.
     */
    fun revalidate(
        grant: PrivacyGrant,
        intendedRequest: PrivacyRequest,
        snapshot: PrivacySnapshot,
    ): PrivacyDecision {
        val frozenRequest = intendedRequest.immutableCopyOrNull()
            ?: return PrivacyDecision.Deny(PrivacyDenialReason.INVALID_TARGET)
        val frozenSnapshot = snapshot.immutableCopyOrNull()
            ?: return PrivacyDecision.Deny(PrivacyDenialReason.INVALID_PRIVACY_STATE)
        if (grant.binding != frozenRequest.toGrantBinding()) {
            return PrivacyDecision.Deny(PrivacyDenialReason.GRANT_BINDING_MISMATCH)
        }
        val currentDecision = decideFrozen(frozenRequest, frozenSnapshot)
        val currentGrant = currentDecision.grantOrNull() ?: return currentDecision
        if (grant != currentGrant) {
            return PrivacyDecision.Deny(PrivacyDenialReason.GRANT_CONTENT_MISMATCH)
        }
        return currentDecision
    }

    private fun notificationDisclosure(
        visibleCameras: Set<String>,
        snapshot: PrivacySnapshot,
    ): NotificationDisclosure {
        val ownerDisclosure = visibleCameras
            .map { camera ->
                snapshot.cameraNotificationDisclosure[camera]
                    ?: snapshot.globalNotificationDisclosure
            }
            .maxByOrNull(NotificationDisclosure::restriction)
            ?: snapshot.globalNotificationDisclosure
        return if (snapshot.guestMode) {
            maxOf(ownerDisclosure, snapshot.guestNotificationDisclosure, compareBy { it.restriction })
        } else {
            ownerDisclosure
        }
    }

    private fun stricterRecognition(
        first: RecognitionDisclosure,
        second: RecognitionDisclosure,
    ): RecognitionDisclosure {
        if (first == RecognitionDisclosure.HIDE_ALL || second == RecognitionDisclosure.HIDE_ALL) {
            return RecognitionDisclosure.HIDE_ALL
        }
        if (first == second) return first
        if (first == RecognitionDisclosure.SHOW_ALL) return second
        if (second == RecognitionDisclosure.SHOW_ALL) return first
        return RecognitionDisclosure.HIDE_ALL
    }

    private fun PrivacyTarget.isValid(): Boolean {
        if (cameraIds.size > MAX_CAMERA_COLLECTION_SIZE) return false
        if (cameraIds.any { !it.isSafeIdentifier() }) return false
        return when (this) {
            is PrivacyTarget.Camera -> cameraId.isSafeIdentifier()
            is PrivacyTarget.Review -> reviewId.isSafeIdentifier() && cameraId.isSafeIdentifier()
            is PrivacyTarget.CameraCollection -> collectionId.isSafeIdentifier() && cameraIds.isNotEmpty()
            is PrivacyTarget.ClipOrIncident ->
                itemId.isSafeIdentifier() && cameraIds.isNotEmpty()
            is PrivacyTarget.LocalAction -> true
        }
    }

    private fun String.isSafeIdentifier(): Boolean =
        isNotBlank() && length <= MAX_IDENTIFIER_LENGTH && none { character ->
            character.isISOControl() || Character.getType(character) == Character.FORMAT.toInt()
        }

    private fun PrivacyRequest.hasValidSurfaceTargetCombination(): Boolean = when (val value = target) {
        is PrivacyTarget.LocalAction -> surface in value.action.permittedSurfaces
        is PrivacyTarget.ClipOrIncident -> surface in CLIP_OR_INCIDENT_SURFACES
        is PrivacyTarget.CameraCollection -> surface !in CAMERA_COLLECTION_BLOCKED_SURFACES
        is PrivacyTarget.Camera,
        is PrivacyTarget.Review,
        -> surface !in NON_ACTION_CONTENT_BLOCKED_SURFACES
    }

    private fun PrivacyRequest.isNoPinGuestExitRecovery(snapshot: PrivacySnapshot): Boolean =
        snapshot.guestMode &&
            !snapshot.pinConfigured &&
            (target as? PrivacyTarget.LocalAction)?.action == PrivacyLocalAction.EXIT_GUEST_MODE

    private fun PrivacySnapshot.unlockedScopes(): Set<PinScope> = pinUnlockProof?.scopes.orEmpty()

    private fun PrivacySurface.requiredPinScope(): PinScope? = when (this) {
        PrivacySurface.ACTIVITY,
        PrivacySurface.HISTORY,
        PrivacySurface.SEARCH,
        -> PinScope.ACTIVITY_HISTORY_SEARCH
        PrivacySurface.CLIP,
        PrivacySurface.INCIDENT,
        -> PinScope.CLIPS_AND_INCIDENTS
        else -> null
    }

    private fun PrivacySnapshot.isValid(): Boolean {
        if (!privacyPolicyAvailable) return false
        if (allowedCameraIds.any { !it.isSafeIdentifier() }) return false
        if (privateCameraIds.any { !it.isSafeIdentifier() }) return false
        if (cameraNotificationDisclosure.keys.any { !it.isSafeIdentifier() }) return false
        if (!pinConfigured && (protectedScopes.isNotEmpty() || pinUnlockProof != null)) return false
        val proof = pinUnlockProof ?: return true
        return proof.privacyEpoch == epoch &&
            proof.scopes.all(protectedScopes::contains)
    }

    private fun PrivacyRequest.immutableCopyOrNull(): PrivacyRequest? {
        if (target.cameraIds.size > MAX_CAMERA_COLLECTION_SIZE) return null
        val frozenTarget = runCatching { target.immutableCopy() }.getOrNull() ?: return null
        if (frozenTarget.cameraIds.size > MAX_CAMERA_COLLECTION_SIZE) return null
        return copy(target = frozenTarget)
    }

    private fun PrivacySnapshot.immutableCopyOrNull(): PrivacySnapshot? {
        if (
            allowedCameraIds.size > MAX_CAMERA_COLLECTION_SIZE ||
            privateCameraIds.size > MAX_CAMERA_COLLECTION_SIZE ||
            cameraNotificationDisclosure.size > MAX_CAMERA_COLLECTION_SIZE ||
            protectedScopes.size > PinScope.entries.size ||
            (pinUnlockProof?.scopes?.size ?: 0) > PinScope.entries.size
        ) {
            return null
        }
        val frozen = runCatching {
            copy(
                allowedCameraIds = allowedCameraIds.toSet(),
                privateCameraIds = privateCameraIds.toSet(),
                protectedScopes = protectedScopes.toSet(),
                pinUnlockProof = pinUnlockProof?.copy(scopes = pinUnlockProof.scopes.toSet()),
                cameraNotificationDisclosure = cameraNotificationDisclosure.toMap(),
            )
        }.getOrNull() ?: return null
        return frozen.takeIf {
            it.allowedCameraIds.size <= MAX_CAMERA_COLLECTION_SIZE &&
                it.privateCameraIds.size <= MAX_CAMERA_COLLECTION_SIZE &&
                it.cameraNotificationDisclosure.size <= MAX_CAMERA_COLLECTION_SIZE
        }
    }

    private fun PrivacyDecision.grantOrNull(): PrivacyGrant? = when (this) {
        is PrivacyDecision.Allow -> grant
        is PrivacyDecision.AllowRedacted -> grant
        is PrivacyDecision.RequirePin,
        is PrivacyDecision.Deny,
        -> null
    }

    private fun PrivacyRequest.toGrantBinding(): PrivacyGrantBinding = PrivacyGrantBinding(
        privacyEpoch = expectedPrivacyEpoch,
        surface = surface,
        target = target.immutableCopy(),
    )

    private fun PrivacyTarget.immutableCopy(): PrivacyTarget = when (this) {
        is PrivacyTarget.Camera -> copy()
        is PrivacyTarget.Review -> copy()
        is PrivacyTarget.CameraCollection -> copy(cameraIds = cameraIds.toSet())
        is PrivacyTarget.ClipOrIncident -> copy(additionalCameraIds = additionalCameraIds.toSet())
        is PrivacyTarget.LocalAction -> copy()
    }

    companion object {
        private const val MAX_IDENTIFIER_LENGTH = 256
        private const val MAX_CAMERA_COLLECTION_SIZE = 256

        private val NON_ACTION_CONTENT_BLOCKED_SURFACES = setOf(
            PrivacySurface.CLIP,
            PrivacySurface.INCIDENT,
            PrivacySurface.SETTINGS,
            PrivacySurface.ACTION,
        )
        private val CAMERA_COLLECTION_BLOCKED_SURFACES = setOf(
            PrivacySurface.SETTINGS,
            PrivacySurface.ACTION,
        )
        private val CLIP_OR_INCIDENT_SURFACES = setOf(
            PrivacySurface.CLIP,
            PrivacySurface.INCIDENT,
            PrivacySurface.DEEP_LINK,
        )

        private val GUEST_BLOCKED_SURFACES = setOf(
            PrivacySurface.CLIP,
            PrivacySurface.INCIDENT,
            PrivacySurface.SETTINGS,
        )
        private val GUEST_BLOCKED_SCOPES = setOf(
            PinScope.CLIPS_AND_INCIDENTS,
        )
        private val GUEST_BLOCKED_ACTION_SCOPES = setOf(
            PinScope.ADMINISTRATIVE_ACTIONS,
            PinScope.DESTRUCTIVE_ACTIONS,
            PinScope.SETTINGS,
        )
    }
}
