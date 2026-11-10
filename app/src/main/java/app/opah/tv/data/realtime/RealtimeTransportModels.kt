package app.opah.tv.data.realtime

import app.opah.tv.data.model.RealtimeReviewUpdate
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/** A process-wide feature that currently needs Frigate activity. */
enum class RealtimeDemandOwner {
    FOREGROUND_UI,
    AWARENESS_SERVICE,
    MONITOR_MODE,
    PTZ_CONTROL,
}

@JvmInline
value class RealtimeLeaseId(val value: String)

/** Supported API contracts; authentication and camera scope independently gate transport access. */
enum class FrigateRealtimeContract {
    FRIGATE_0_17_2,
    FRIGATE_0_18,
    UNSUPPORTED,
}

data class RealtimeProfile(
    val id: String,
    val contract: FrigateRealtimeContract,
)

sealed interface RealtimeProfileState {
    data object Unavailable : RealtimeProfileState

    data class Ready(val profile: RealtimeProfile) : RealtimeProfileState

    data class Invalid(val safeReason: String) : RealtimeProfileState
}

enum class RealtimeAuthenticationState {
    UNKNOWN,
    AUTHENTICATED,
    REQUIRED,
    FORBIDDEN,
}

enum class CameraRoleEvidence {
    ADMINISTRATOR,
    VIEWER,
    CUSTOM,
    UNKNOWN,
}

/**
 * A bounded snapshot from the authenticated profile/permissions boundary.
 *
 * [configuredCameraIds] is null when full-camera coverage could not be proved.
 * It is never inferred from WebSocket traffic.
 */
class CameraScopeEvidence(
    val revision: Long,
    val role: CameraRoleEvidence,
    allowedCameraIds: Set<String>,
    configuredCameraIds: Set<String>?,
    ptzCameraIds: Set<String> = emptySet(),
    val boundsValid: Boolean = true,
    cameraDisplayNames: Map<String, String> = emptyMap(),
) {
    val allowedCameraIds: Set<String> = allowedCameraIds.frozenSetCopy()
    val configuredCameraIds: Set<String>? = configuredCameraIds?.frozenSetCopy()
    val ptzCameraIds: Set<String> = ptzCameraIds.frozenSetCopy()
    val cameraDisplayNames: Map<String, String> = cameraDisplayNames.frozenMapCopy()

    fun copy(
        revision: Long = this.revision,
        role: CameraRoleEvidence = this.role,
        allowedCameraIds: Set<String> = this.allowedCameraIds,
        configuredCameraIds: Set<String>? = this.configuredCameraIds,
        ptzCameraIds: Set<String> = this.ptzCameraIds,
        boundsValid: Boolean = this.boundsValid,
        cameraDisplayNames: Map<String, String> = this.cameraDisplayNames,
    ): CameraScopeEvidence = CameraScopeEvidence(
        revision = revision,
        role = role,
        allowedCameraIds = allowedCameraIds,
        configuredCameraIds = configuredCameraIds,
        ptzCameraIds = ptzCameraIds,
        boundsValid = boundsValid,
        cameraDisplayNames = cameraDisplayNames,
    )

    override fun equals(other: Any?): Boolean =
        other is CameraScopeEvidence &&
            revision == other.revision &&
            role == other.role &&
            allowedCameraIds == other.allowedCameraIds &&
            configuredCameraIds == other.configuredCameraIds &&
            ptzCameraIds == other.ptzCameraIds &&
            cameraDisplayNames == other.cameraDisplayNames &&
            boundsValid == other.boundsValid

    override fun hashCode(): Int {
        var result = revision.hashCode()
        result = 31 * result + role.hashCode()
        result = 31 * result + allowedCameraIds.hashCode()
        result = 31 * result + (configuredCameraIds?.hashCode() ?: 0)
        result = 31 * result + ptzCameraIds.hashCode()
        result = 31 * result + cameraDisplayNames.hashCode()
        result = 31 * result + boundsValid.hashCode()
        return result
    }

    override fun toString(): String =
        "CameraScopeEvidence(revision=$revision, role=$role, " +
            "allowedCameraIds=$allowedCameraIds, configuredCameraIds=$configuredCameraIds, " +
            "ptzCameraIds=$ptzCameraIds, cameraDisplayNames=$cameraDisplayNames, " +
            "boundsValid=$boundsValid)"
}

sealed interface CameraScopeState {
    data object Unknown : CameraScopeState

    data class Fresh(val evidence: CameraScopeEvidence) : CameraScopeState

    /** Last positively observed camera IDs, retained only for scoped REST repair. */
    data class Stale(val evidence: CameraScopeEvidence) : CameraScopeState
}

enum class CameraScopeCoverage {
    FULL,
    RESTRICTED,
    AMBIGUOUS,
}

enum class RealtimeTransportMode {
    NONE,
    SHARED_WEB_SOCKET,
    AUTHORIZED_REST_ONLY,
}

enum class RealtimeTransportPhase {
    STOPPED,
    WAITING_FOR_PROFILE,
    WAITING_FOR_AUTHENTICATION,
    WAITING_FOR_SCOPE,
    WAITING_FOR_NETWORK,
    OPENING_SOCKET,
    SOCKET_SYNCHRONIZING,
    SOCKET_HEALTHY,
    REST_RECONCILING,
    REST_ONLY,
    BACKING_OFF,
    AUTHENTICATION_REQUIRED,
    AUTHORIZATION_FORBIDDEN,
    INVALID_PROFILE,
    UNSUPPORTED,
}

@JvmInline
value class RealtimeOperationId(val value: Long)

data class AuthorizationScopeToken(
    val generation: Long,
    val epoch: Long,
    val revision: Long,
)

enum class SocketSessionStatus {
    OPENING,
    SYNCHRONIZING,
    HEALTHY,
}

data class SocketSession(
    val operationId: RealtimeOperationId,
    val status: SocketSessionStatus,
    val scopeToken: AuthorizationScopeToken,
    val lastSynchronizedReconciliationOperationId: RealtimeOperationId? = null,
)

enum class ReconciliationReason {
    COLD_START,
    RECONNECT,
    NETWORK_RECOVERY,
    PERIODIC_REST_POLL,
    FOREGROUND_REFRESH,
    SCOPE_CHANGE,
    QUEUE_OVERFLOW,
}

enum class ReconciliationWindow {
    BOUNDED_COLD_START,
    BOUNDED_OVERLAP,
}

data class ReconciliationAttempt(
    val operationId: RealtimeOperationId,
    val mode: RealtimeTransportMode,
    val reason: ReconciliationReason,
    val window: ReconciliationWindow,
    val overlapSeconds: Long,
    val rangeStartEpochMillis: Long,
    val rangeEndEpochMillis: Long,
    val scopeToken: AuthorizationScopeToken,
    val socketOperationId: RealtimeOperationId? = null,
) {
    init {
        require(overlapSeconds >= 0)
        require(rangeStartEpochMillis >= 0)
        require(rangeEndEpochMillis >= rangeStartEpochMillis)
        require(
            (window == ReconciliationWindow.BOUNDED_COLD_START && overlapSeconds == 0L) ||
                (window == ReconciliationWindow.BOUNDED_OVERLAP && overlapSeconds > 0L),
        )
    }
}

enum class RealtimeTimerPurpose {
    RETRY,
    REST_POLL,
    SCOPE_EXPIRY,
    SCOPE_REFRESH_TIMEOUT,
    SCOPE_REFRESH_RETRY,
}

data class PendingRealtimeTimer(
    val operationId: RealtimeOperationId,
    val purpose: RealtimeTimerPurpose,
    val delayMillis: Long,
)

data class ScopeRefreshAttempt(
    val operationId: RealtimeOperationId,
)

/** All mutable transport facts needed by the pure reducer. */
data class RealtimeTransportState(
    val generation: Long = 0,
    val authorizationScopeEpoch: Long = 0,
    val nextOperationValue: Long = 1,
    val profileState: RealtimeProfileState = RealtimeProfileState.Unavailable,
    val authenticationState: RealtimeAuthenticationState = RealtimeAuthenticationState.UNKNOWN,
    val cameraScope: CameraScopeState = CameraScopeState.Unknown,
    val networkAvailable: Boolean = false,
    val leases: Map<RealtimeLeaseId, RealtimeDemandOwner> = emptyMap(),
    val selectedMode: RealtimeTransportMode = RealtimeTransportMode.NONE,
    val phase: RealtimeTransportPhase = RealtimeTransportPhase.STOPPED,
    val scopeRefresh: ScopeRefreshAttempt? = null,
    val socket: SocketSession? = null,
    val reconciliation: ReconciliationAttempt? = null,
    val timer: PendingRealtimeTimer? = null,
    val scopeTimer: PendingRealtimeTimer? = null,
    val consecutiveFailures: Int = 0,
    val committedReconciliationWatermarkEpochMillis: Long? = null,
    /** Highest authorization revision accepted in this profile generation, even while scope is unknown. */
    val maximumAcceptedScopeRevision: Long? = null,
    /** Only the newest queued PTZ request remains executable. */
    val latestPtzRequestOperationId: RealtimeOperationId? = null,
    val pendingReconciliationReason: ReconciliationReason? = null,
    val socketEpochDesynchronized: Boolean = false,
    val repairAfterCurrentReconciliation: Boolean = false,
)

enum class ReviewLifecycleSignal {
    NEW,
    UPDATE,
    END,
    GENAI,
    UNKNOWN,
}

enum class CameraActivitySignal {
    IDLE,
    ACTIVE,
    RECORDING,
    UNKNOWN,
}

/** Sanitized, typed messages only. Raw payloads never cross this boundary. */
sealed interface RealtimeInboundMessage {
    data class ReviewChanged(
        val cameraId: String,
        val reviewId: String,
        val lifecycle: ReviewLifecycleSignal,
        val update: RealtimeReviewUpdate? = null,
    ) : RealtimeInboundMessage

    class CameraActivity(
        activityByCamera: Map<String, CameraActivitySignal>,
    ) : RealtimeInboundMessage {
        val activityByCamera: Map<String, CameraActivitySignal> = activityByCamera.frozenMapCopy()

        override fun equals(other: Any?): Boolean =
            other is CameraActivity && activityByCamera == other.activityByCamera

        override fun hashCode(): Int = activityByCamera.hashCode()

        override fun toString(): String = "CameraActivity(activityByCamera=$activityByCamera)"
    }

    data class UnknownTopic(val boundedTopic: String) : RealtimeInboundMessage
}

enum class PtzOperation {
    MOVE_UP,
    MOVE_DOWN,
    MOVE_LEFT,
    MOVE_RIGHT,
    ZOOM_IN,
    ZOOM_OUT,
    STOP,
}

enum class PtzRejectionReason {
    NO_PTZ_LEASE,
    REST_ONLY_MODE,
    SOCKET_NOT_HEALTHY,
    CAMERA_NOT_AUTHORIZED,
    CAPABILITY_NOT_PROVED,
    STALE_REQUEST,
}

enum class SafeTransportFailure {
    DNS,
    ROUTE,
    CONNECTION,
    SERVER,
    TIMEOUT,
    ABNORMAL_CLOSE,
    AUTHENTICATION,
    AUTHORIZATION,
    INVALID_PROFILE,
}

enum class SocketCloseReason {
    LAST_LEASE_RELEASED,
    NETWORK_LOST,
    PROFILE_CHANGED,
    SCOPE_NO_LONGER_SOCKET_SAFE,
    AUTHENTICATION_FAILED,
    AUTHORIZATION_FAILED,
    RETRY_AFTER_FAILURE,
    SIGNED_OUT,
}

sealed interface RealtimeTransportEvent {
    data class ProfileChanged(
        val profile: RealtimeProfile,
        val committedReconciliationWatermarkEpochMillis: Long? = null,
    ) : RealtimeTransportEvent

    data class ProfileInvalidated(val safeReason: String) : RealtimeTransportEvent

    data object SignedOut : RealtimeTransportEvent

    data class AuthenticationRestored(val generation: Long) : RealtimeTransportEvent

    data class LeaseAcquired(
        val generation: Long,
        val leaseId: RealtimeLeaseId,
        val owner: RealtimeDemandOwner,
    ) : RealtimeTransportEvent

    data class LeaseReleased(
        val generation: Long,
        val leaseId: RealtimeLeaseId,
    ) : RealtimeTransportEvent

    data class NetworkAvailabilityChanged(val available: Boolean) : RealtimeTransportEvent

    data class ScopeRefreshSucceeded(
        val generation: Long,
        val operationId: RealtimeOperationId,
        val evidence: CameraScopeEvidence,
    ) : RealtimeTransportEvent

    data class ScopeRefreshFailed(
        val generation: Long,
        val operationId: RealtimeOperationId,
        val failure: SafeTransportFailure,
    ) : RealtimeTransportEvent

    /** A push invalidation/change from the authenticated permissions boundary. */
    data class ScopeReplaced(
        val generation: Long,
        val scope: CameraScopeState,
    ) : RealtimeTransportEvent

    data class ScopeExpired(
        val generation: Long,
        val revision: Long,
    ) : RealtimeTransportEvent

    data class SocketOpened(
        val generation: Long,
        val operationId: RealtimeOperationId,
    ) : RealtimeTransportEvent

    data class SocketFailed(
        val generation: Long,
        val operationId: RealtimeOperationId,
        val failure: SafeTransportFailure,
        val jitterUnit: Double = 0.5,
    ) : RealtimeTransportEvent

    data class SocketClosedUnexpectedly(
        val generation: Long,
        val operationId: RealtimeOperationId,
        val jitterUnit: Double = 0.5,
    ) : RealtimeTransportEvent

    data class SocketMessageReceived(
        val generation: Long,
        val operationId: RealtimeOperationId,
        val message: RealtimeInboundMessage,
    ) : RealtimeTransportEvent

    data class AuthoritativeQueueOverflow(
        val generation: Long,
        val operationId: RealtimeOperationId,
    ) : RealtimeTransportEvent

    data class ReconciliationSucceeded(
        val generation: Long,
        val operationId: RealtimeOperationId,
    ) : RealtimeTransportEvent

    data class ReconciliationFailed(
        val generation: Long,
        val operationId: RealtimeOperationId,
        val failure: SafeTransportFailure,
        val jitterUnit: Double = 0.5,
    ) : RealtimeTransportEvent

    data class TimerFired(
        val generation: Long,
        val operationId: RealtimeOperationId,
    ) : RealtimeTransportEvent

    data class ForegroundRefreshRequested(val generation: Long) : RealtimeTransportEvent

    data class PublishPtzRequested(
        val generation: Long,
        val leaseId: RealtimeLeaseId,
        val cameraId: String,
        val operation: PtzOperation,
    ) : RealtimeTransportEvent
}

data class RealtimeTransition(
    val state: RealtimeTransportState,
    val commands: List<RealtimeTransportCommand> = emptyList(),
)

/** Wall time is used only for persisted Frigate Review query bounds, never retry/timer deadlines. */
fun interface RealtimeWallClock {
    fun nowEpochMillis(): Long
}

internal fun <T> Set<T>.frozenSetCopy(): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(this))

internal fun <K, V> Map<K, V>.frozenMapCopy(): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(this))

internal fun <T> List<T>.frozenListCopy(): List<T> =
    Collections.unmodifiableList(ArrayList(this))
