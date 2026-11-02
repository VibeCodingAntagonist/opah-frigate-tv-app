package app.opah.tv.playback.compatibility

import java.util.ArrayDeque
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val MAX_RUNTIME_TOKEN_LENGTH = 96
private const val RUNNER_INBOX_CAPACITY = 32
private const val MAX_PREPARATION_OPERATION_TIMEOUT_MILLIS = 5_000L
private val RUNTIME_TOKEN_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")

private fun requireRuntimeToken(value: String, label: String) {
    require(value.length <= MAX_RUNTIME_TOKEN_LENGTH && RUNTIME_TOKEN_PATTERN.matches(value)) {
        "$label must be a bounded opaque token"
    }
}

/**
 * A capability understood only by the trusted route/backend adapters. It cannot contain a URI,
 * host, path, or credential and is never persisted by the compatibility domain.
 */
class ResolvedPlaybackToken private constructor(
    private val opaqueValue: String,
) {
    companion object {
        fun fromTrustedAdapter(value: String): ResolvedPlaybackToken {
            requireRuntimeToken(value, "Resolved playback token")
            return ResolvedPlaybackToken(value)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ResolvedPlaybackToken && opaqueValue == other.opaqueValue

    override fun hashCode(): Int = opaqueValue.hashCode()

    override fun toString(): String = "ResolvedPlaybackToken([redacted])"
}

data class PlaybackRouteLookup(
    val routeId: PlaybackRouteId,
    val streamId: PlaybackStreamId,
)

/**
 * One immutable read of current access plus its route capabilities. The provider must assemble
 * both from the same authorization/privacy revision; the gateway never authorizes one snapshot
 * and resolves from another.
 */
class FreshPlaybackRouteSnapshot(
    val currentAccess: CurrentPlaybackAccess,
    liveTokens: Map<PlaybackRouteLookup, ResolvedPlaybackToken>,
    snapshotTokens: Map<SnapshotRouteId, ResolvedPlaybackToken> = emptyMap(),
) {
    val liveTokens: Map<PlaybackRouteLookup, ResolvedPlaybackToken> =
        Collections.unmodifiableMap(LinkedHashMap(liveTokens))
    val snapshotTokens: Map<SnapshotRouteId, ResolvedPlaybackToken> =
        Collections.unmodifiableMap(LinkedHashMap(snapshotTokens))

    init {
        require(this.liveTokens.values.distinct().size == this.liveTokens.size) {
            "Live playback capabilities must not be reused across sources"
        }
        require(this.snapshotTokens.values.distinct().size == this.snapshotTokens.size) {
            "Snapshot capabilities must not be reused across routes"
        }
        require(
            (this.liveTokens.values + this.snapshotTokens.values).distinct().size ==
                this.liveTokens.size + this.snapshotTokens.size,
        ) { "Live and snapshot capabilities must not be reused across content namespaces" }
        require(this.liveTokens.keys.all { lookup ->
            currentAccess.authorizedSources.any {
                it.routeId == lookup.routeId && it.streamId == lookup.streamId
            }
        }) { "A live capability is outside the current authorized source snapshot" }
        require(this.snapshotTokens.keys.all(currentAccess.authorizedSnapshotRoutes::contains)) {
            "A snapshot capability is outside the current authorized route snapshot"
        }
    }
}

sealed interface FreshPlaybackRouteSnapshotResult {
    /** One atomic authorization/privacy revision and only capabilities derived from that revision. */
    data class Available(val snapshot: FreshPlaybackRouteSnapshot) : FreshPlaybackRouteSnapshotResult

    /** The shared authenticated session is missing or expired. */
    data object AuthenticationRequired : FreshPlaybackRouteSnapshotResult

    /** Current access could not be read conclusively; stale plan evidence must not grant access. */
    data object StaleAccess : FreshPlaybackRouteSnapshotResult

    /** The current route-capability snapshot could not be assembled conclusively. */
    data object Unavailable : FreshPlaybackRouteSnapshotResult
}

fun interface FreshPlaybackRouteSnapshotProvider {
    /**
     * Returns an allowlisted outcome. Authentication loss must be reported explicitly; unexpected
     * adapter failures are treated as stale access by the gateway without exposing exception data.
     */
    suspend fun freshSnapshot(): FreshPlaybackRouteSnapshotResult
}

enum class PlaybackRouteContentKind {
    LIVE,
    SNAPSHOT,
}

sealed interface PlaybackRouteResolution {
    val contentKind: PlaybackRouteContentKind

    data class Authorized(
        override val contentKind: PlaybackRouteContentKind,
        val token: ResolvedPlaybackToken,
    ) : PlaybackRouteResolution

    data class Denied(
        override val contentKind: PlaybackRouteContentKind,
        val authorization: PlaybackExecutionAuthorization,
    ) : PlaybackRouteResolution {
        init {
            require(authorization != PlaybackExecutionAuthorization.AUTHORIZED)
            require(
                authorization != PlaybackExecutionAuthorization.SOURCE_NOT_AUTHORIZED ||
                    contentKind == PlaybackRouteContentKind.LIVE,
            ) { "Live-source authorization cannot deny snapshot content" }
            require(
                authorization != PlaybackExecutionAuthorization.SNAPSHOT_NOT_AUTHORIZED ||
                    contentKind == PlaybackRouteContentKind.SNAPSHOT,
            ) { "Snapshot authorization cannot deny live content" }
        }
    }
}

fun interface AtomicAuthorizedPlaybackRouteGateway {
    suspend fun resolve(command: PlaybackContentCommand): PlaybackRouteResolution
}

/**
 * Performs the fail-closed execution authorization and capability lookup against one fresh,
 * immutable snapshot. A production provider is responsible for making that snapshot read atomic.
 */
class AuthorizingPlaybackRouteGateway(
    private val snapshots: FreshPlaybackRouteSnapshotProvider,
    private val authorizer: PlaybackExecutionAuthorizer = PlaybackExecutionAuthorizer(),
) : AtomicAuthorizedPlaybackRouteGateway {
    override suspend fun resolve(command: PlaybackContentCommand): PlaybackRouteResolution {
        val contentKind = command.routeContentKind()
        val result = try {
            snapshots.freshSnapshot()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return PlaybackRouteResolution.Denied(
                contentKind,
                PlaybackExecutionAuthorization.STALE_ACCESS,
            )
        }
        val snapshot = when (result) {
            is FreshPlaybackRouteSnapshotResult.Available -> result.snapshot
            FreshPlaybackRouteSnapshotResult.AuthenticationRequired ->
                return PlaybackRouteResolution.Denied(
                    contentKind,
                    PlaybackExecutionAuthorization.AUTHENTICATION_REQUIRED,
                )
            FreshPlaybackRouteSnapshotResult.StaleAccess ->
                return PlaybackRouteResolution.Denied(
                    contentKind,
                    PlaybackExecutionAuthorization.STALE_ACCESS,
                )
            FreshPlaybackRouteSnapshotResult.Unavailable ->
                return PlaybackRouteResolution.Denied(
                    contentKind,
                    PlaybackExecutionAuthorization.RESOLUTION_UNAVAILABLE,
                )
        }
        val authorization = authorizer.authorize(command, snapshot.currentAccess)
        if (authorization != PlaybackExecutionAuthorization.AUTHORIZED) {
            return PlaybackRouteResolution.Denied(contentKind, authorization)
        }
        val token = when (command) {
            is PlaybackCommand.StartAttempt -> snapshot.liveTokens[
                PlaybackRouteLookup(
                    command.attempt.candidate.routeId,
                    command.attempt.candidate.streamId,
                ),
            ]

            is PlaybackCommand.RenderSnapshot -> snapshot.snapshotTokens[command.routeId]
        }
        return token?.let { PlaybackRouteResolution.Authorized(contentKind, it) }
            ?: PlaybackRouteResolution.Denied(
                contentKind,
                PlaybackExecutionAuthorization.RESOLUTION_UNAVAILABLE,
            )
    }
}

private fun PlaybackContentCommand.routeContentKind(): PlaybackRouteContentKind = when (this) {
    is PlaybackCommand.StartAttempt -> PlaybackRouteContentKind.LIVE
    is PlaybackCommand.RenderSnapshot -> PlaybackRouteContentKind.SNAPSHOT
}

class PlaybackResourceLeaseToken private constructor(
    private val opaqueValue: String,
) {
    companion object {
        fun fromTrustedCoordinator(value: String): PlaybackResourceLeaseToken {
            requireRuntimeToken(value, "Playback resource lease")
            return PlaybackResourceLeaseToken(value)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is PlaybackResourceLeaseToken && opaqueValue == other.opaqueValue

    override fun hashCode(): Int = opaqueValue.hashCode()

    override fun toString(): String = "PlaybackResourceLeaseToken([redacted])"
}

sealed interface PlaybackResourceLeaseResult {
    data class Granted(val lease: PlaybackResourceLeaseToken) : PlaybackResourceLeaseResult

    data class Denied(
        val reason: PlaybackResourceDenialReason,
    ) : PlaybackResourceLeaseResult
}

enum class PlaybackResourceLeaseReleaseOutcome {
    RELEASED,
    FAILED,
}

/**
 * Narrow mutable lease boundary around [PlaybackResourcePlanner]. Implementations must be local,
 * bounded, and non-I/O: cancellation while waiting must happen before mutation, and a call which
 * enters its mutation section must return its authoritative result without another suspension.
 * The production coordinator owns the global desired request set, uses that planner for every
 * acquisition/replan, and returns a lease only when the exact reducer candidate remains allocated.
 * Acquisition must be atomic: a denial or exception must not leave an unrepresented allocation
 * behind. Release must be idempotent for the coordinator's documented retry horizon.
 */
interface PlaybackResourceLeasePort {
    suspend fun acquire(
        attemptId: PlaybackAttemptId,
        purpose: PlaybackPurpose,
        candidate: PlaybackCandidate,
        resourcePolicy: PlaybackResourcePolicy,
    ): PlaybackResourceLeaseResult

    suspend fun release(
        attemptId: PlaybackAttemptId,
        lease: PlaybackResourceLeaseToken,
    ): PlaybackResourceLeaseReleaseOutcome
}

class PlaybackAttemptHandleToken private constructor(
    private val opaqueValue: String,
) {
    companion object {
        fun fromTrustedBackend(value: String): PlaybackAttemptHandleToken {
            requireRuntimeToken(value, "Playback attempt handle")
            return PlaybackAttemptHandleToken(value)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is PlaybackAttemptHandleToken && opaqueValue == other.opaqueValue

    override fun hashCode(): Int = opaqueValue.hashCode()

    override fun toString(): String = "PlaybackAttemptHandleToken([redacted])"
}

data class AuthorizedPlaybackAttempt(
    val attempt: PlaybackAttempt,
    val resolvedToken: ResolvedPlaybackToken,
    val resourcePolicy: PlaybackResourcePolicy,
)

class PlaybackSnapshotPresentationId private constructor(
    private val opaqueValue: String,
) {
    companion object {
        fun fromTrustedRunner(value: String): PlaybackSnapshotPresentationId {
            requireRuntimeToken(value, "Snapshot presentation ID")
            return PlaybackSnapshotPresentationId(value)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is PlaybackSnapshotPresentationId && opaqueValue == other.opaqueValue

    override fun hashCode(): Int = opaqueValue.hashCode()

    override fun toString(): String = "PlaybackSnapshotPresentationId([redacted])"
}

data class AuthorizedSnapshotPresentation(
    val presentationId: PlaybackSnapshotPresentationId,
    val routeId: SnapshotRouteId,
    val resolvedToken: ResolvedPlaybackToken,
)

sealed interface PlaybackBackendStartOutcome {
    data class Started(val handle: PlaybackAttemptHandleToken) : PlaybackBackendStartOutcome

    data class Failed(val failure: ClassifiedPlaybackFailure) : PlaybackBackendStartOutcome

    /** Token redemption lost typed access after the runner's final live-route revalidation. */
    data class AccessLost(
        val authorization: PlaybackExecutionAuthorization,
    ) : PlaybackBackendStartOutcome {
        init {
            require(authorization != PlaybackExecutionAuthorization.AUTHORIZED)
            require(authorization != PlaybackExecutionAuthorization.SNAPSHOT_NOT_AUTHORIZED) {
                "Snapshot authorization cannot deny a live playback attempt"
            }
        }
    }
}

enum class PlaybackBackendReleaseOutcome {
    RELEASED,
    FAILED,
}

sealed interface PlaybackSnapshotRenderOutcome {
    /** Presentation succeeded and remains owned under its presentation ID until cancellation. */
    data object Rendered : PlaybackSnapshotRenderOutcome

    /** Presentation failed with no retained image or in-flight ownership. */
    data object Failed : PlaybackSnapshotRenderOutcome

    /** Presentation failed while exact-ID ownership remains and requires cancellation/handoff. */
    data object CancellationRequired : PlaybackSnapshotRenderOutcome

    /** Token redemption lost typed access after the runner's final route revalidation. */
    data class AccessLost(
        val authorization: PlaybackExecutionAuthorization,
    ) : PlaybackSnapshotRenderOutcome {
        init {
            require(authorization != PlaybackExecutionAuthorization.AUTHORIZED)
            require(authorization != PlaybackExecutionAuthorization.SOURCE_NOT_AUTHORIZED) {
                "Live-source authorization cannot deny a snapshot presentation"
            }
        }
    }
}

enum class PlaybackSnapshotCancellationOutcome {
    /** The ID is tombstoned and no work or retained presentation can expose snapshot content. */
    ACKNOWLEDGED,
    FAILED,
}

sealed interface PlaybackAttemptSignal {
    val attemptId: PlaybackAttemptId
    val elapsedMillis: Long

    data class FirstFrame(
        override val attemptId: PlaybackAttemptId,
        override val elapsedMillis: Long,
        val decoderEvidence: PlaybackDecoderEvidence = PlaybackDecoderEvidence.UNKNOWN,
    ) : PlaybackAttemptSignal {
        init {
            require(elapsedMillis >= 0)
        }
    }

    data class VideoProgress(
        override val attemptId: PlaybackAttemptId,
        override val elapsedMillis: Long,
        val sequence: Long,
    ) : PlaybackAttemptSignal {
        init {
            require(elapsedMillis >= 0)
            require(sequence > 0)
        }
    }

    data class AudioProgress(
        override val attemptId: PlaybackAttemptId,
        override val elapsedMillis: Long,
        val sequence: Long,
    ) : PlaybackAttemptSignal {
        init {
            require(elapsedMillis >= 0)
            require(sequence > 0)
        }
    }

    data class Failed(
        override val attemptId: PlaybackAttemptId,
        override val elapsedMillis: Long,
        val failure: ClassifiedPlaybackFailure,
    ) : PlaybackAttemptSignal {
        init {
            require(elapsedMillis >= 0)
        }
    }
}

fun interface PlaybackAttemptEventSink {
    /** May be called from a Media3 callback thread; the runner serializes the signal. */
    fun emit(signal: PlaybackAttemptSignal)
}

/**
 * Transactional backend boundary keyed by the globally unique attempt ID. Ownership begins when
 * [start] is invoked, before a handle is returned. [release] must atomically tombstone that attempt,
 * cancel or resolve any in-flight start, and release the associated player. A null handle means the
 * start result was lost; the backend must still resolve by attempt ID. Exact repeated releases must
 * be idempotent. [start] may report [PlaybackBackendStartOutcome.Failed] or throw only after proving
 * that no player remains owned; [PlaybackBackendStartOutcome.Started] returns an optimization token
 * but does not replace attempt-ID correlation.
 *
 * Snapshot ownership begins before [renderSnapshot] redeems its capability. A rendered snapshot
 * remains owned by its [PlaybackSnapshotPresentationId]. [cancelSnapshot] must install an exact-ID
 * tombstone before its first suspension and return [PlaybackSnapshotCancellationOutcome.ACKNOWLEDGED]
 * only when no in-flight work or retained presentation can expose content. Exact repeated
 * cancellations must be idempotent.
 */
interface PlaybackAttemptBackend {
    suspend fun start(
        request: AuthorizedPlaybackAttempt,
        events: PlaybackAttemptEventSink,
    ): PlaybackBackendStartOutcome

    suspend fun release(
        attemptId: PlaybackAttemptId,
        handle: PlaybackAttemptHandleToken?,
        force: Boolean,
    ): PlaybackBackendReleaseOutcome

    /** The backend must redeem the revision-bound token atomically before exposing image bytes. */
    suspend fun renderSnapshot(
        request: AuthorizedSnapshotPresentation,
    ): PlaybackSnapshotRenderOutcome

    suspend fun cancelSnapshot(
        presentationId: PlaybackSnapshotPresentationId,
    ): PlaybackSnapshotCancellationOutcome
}

enum class PlaybackDeadlineKind {
    FIRST_FRAME,
    STABLE_DWELL,
    TOTAL_BUDGET,
    RELEASE,
    RECOVERY_BACKOFF,
    PERSISTENCE_RESOLUTION,
}

data class PlaybackDeadlineKey(
    val attemptId: PlaybackAttemptId,
    val kind: PlaybackDeadlineKind,
)

data class PlaybackScheduledDeadline(
    val key: PlaybackDeadlineKey,
    val notBeforeElapsedMillis: Long,
) {
    init {
        require(notBeforeElapsedMillis >= 0)
    }

    fun event(elapsedMillis: Long): PlaybackEvent {
        require(elapsedMillis >= notBeforeElapsedMillis) {
            "A playback deadline cannot fire early"
        }
        return when (key.kind) {
            PlaybackDeadlineKind.FIRST_FRAME -> PlaybackEvent.ProbeDeadlineReached(
                key.attemptId,
                ProbePhase.FIRST_FRAME,
                elapsedMillis,
            )
            PlaybackDeadlineKind.STABLE_DWELL -> PlaybackEvent.ProbeDeadlineReached(
                key.attemptId,
                ProbePhase.STABLE_DWELL,
                elapsedMillis,
            )
            PlaybackDeadlineKind.TOTAL_BUDGET -> PlaybackEvent.ProbeDeadlineReached(
                key.attemptId,
                ProbePhase.TOTAL_BUDGET,
                elapsedMillis,
            )
            PlaybackDeadlineKind.RELEASE ->
                PlaybackEvent.ReleaseDeadlineReached(key.attemptId, elapsedMillis)
            PlaybackDeadlineKind.RECOVERY_BACKOFF ->
                PlaybackEvent.RecoveryDeadlineReached(key.attemptId, elapsedMillis)
            PlaybackDeadlineKind.PERSISTENCE_RESOLUTION ->
                PlaybackEvent.PersistenceResolutionDeadlineReached(key.attemptId, elapsedMillis)
        }
    }
}

fun interface PlaybackDeadlineEventSink {
    fun emit(event: PlaybackEvent)
}

/** Production implementations may use monotonic coroutine timers; tests can fire them manually. */
interface PlaybackDeadlineScheduler {
    suspend fun schedule(
        deadline: PlaybackScheduledDeadline,
        events: PlaybackDeadlineEventSink,
    )

    suspend fun cancel(key: PlaybackDeadlineKey)

    suspend fun cancelAll(attemptId: PlaybackAttemptId)
}

data class PlaybackCleanupHandoff(
    val attemptId: PlaybackAttemptId,
    val handle: PlaybackAttemptHandleToken?,
    val lease: PlaybackResourceLeaseToken?,
    val backendReleased: Boolean,
) {
    init {
        require(lease != null) { "A cleanup handoff must retain its resource lease" }
        require(!backendReleased || handle == null) {
            "A released backend cannot retain an attempt handle"
        }
    }
}

/**
 * Non-blocking handoff to a process-level cleanup owner. The receiver must retain every request
 * until backend release is authoritative and only then release the lease. Enqueueing must not do
 * I/O, reject, or throw; this is the final fail-closed ownership transfer during session teardown.
 */
fun interface PlaybackCleanupHandoffPort {
    fun handoff(request: PlaybackCleanupHandoff)
}

data class PlaybackSnapshotCleanupHandoff(
    val presentationId: PlaybackSnapshotPresentationId,
)

/**
 * Non-blocking process-owner handoff for an exact snapshot presentation whose backend cancellation
 * did not acknowledge within the session bound. Enqueueing must not reject or throw. The receiver
 * must retain the tombstone and retry exact-ID cancellation until it is authoritative.
 */
fun interface PlaybackSnapshotCleanupHandoffPort {
    fun handoff(request: PlaybackSnapshotCleanupHandoff)
}

data class PlaybackCompatibilitySessionSnapshot(
    val state: PlaybackCompatibilityState,
    val activeAttemptId: PlaybackAttemptId?,
    val snapshotPresentationActive: Boolean,
    val lastContentAuthorization: PlaybackExecutionAuthorization?,
    val cleanupHandedOff: Boolean,
    val diagnosticPriorFailure: ClassifiedPlaybackFailure? = null,
    val diagnosticReleaseStage: String = "NOT_STARTED",
    val diagnosticReleaseTrigger: String = "NOT_CAPTURED",
    val diagnosticInitialCleanup: String = "NOT_STARTED",
    val diagnosticCleanupMillis: Long = 0,
)

/**
 * Serialized command pump for one live compatibility plan. Media3 callbacks, timer callbacks, and
 * external cancellation enter one actor. The actor never starts a retry until the reducer has
 * requested and acknowledged release of the prior attempt.
 *
 * The owner must call [accessRevoked] with exact allowlisted evidence whenever current access is
 * revoked. Ordinary lifecycle disposal remains the neutral [lifecycleCancel] path.
 */
class PlaybackCompatibilitySessionRunner(
    scope: CoroutineScope,
    plan: PlaybackPlan,
    sessionId: PlaybackSessionId,
    private val clock: PlaybackElapsedRealtimeClock,
    private val routeGateway: AtomicAuthorizedPlaybackRouteGateway,
    private val resourceLeases: PlaybackResourceLeasePort,
    private val backend: PlaybackAttemptBackend,
    private val persistence: PlaybackStrategyPersistenceCoordinator,
    private val deadlines: PlaybackDeadlineScheduler,
    private val cleanupHandoff: PlaybackCleanupHandoffPort,
    private val snapshotCleanupHandoff: PlaybackSnapshotCleanupHandoffPort,
    private val reducer: PlaybackCompatibilityReducer = PlaybackCompatibilityReducer(),
    private val releaseOperationTimeoutMillis: Long = plan.budget.releaseTimeoutMillis,
    private val preparationOperationTimeoutMillis: Long = minOf(
        plan.budget.firstFrameTimeoutMillis,
        MAX_PREPARATION_OPERATION_TIMEOUT_MILLIS,
    ),
    private val persistenceDrainTimeoutMillis: Long = plan.budget.persistenceResolutionTimeoutMillis,
) {
    private val sessionJob = SupervisorJob(scope.coroutineContext[Job])
    private val sessionScope = CoroutineScope(scope.coroutineContext + sessionJob)
    private val inbox = Channel<Message>(RUNNER_INBOX_CAPACITY)
    private val cancellationWake = Channel<Unit>(Channel.CONFLATED)
    private val callbackMailbox = CallbackMailbox()
    private val persistenceJobs = Collections.synchronizedSet(mutableSetOf<Job>())
    private val externalCancellation = AtomicReference<ExternalCancellation?>(null)
    private val externalCancellationSignal = CompletableDeferred<Unit>()
    private val stopMutex = Mutex()
    private var state: PlaybackCompatibilityState = reducer.initialState(
        plan,
        uniqueRunSessionId(sessionId),
    )
    private var activeAttempt: ActiveAttempt? = null
    private var activeSnapshotPresentation: ActiveSnapshotPresentation? = null
    private var lastContentAuthorization: PlaybackExecutionAuthorization? = null
    private var appliedExternalCancellation: ExternalCancellation? = null
    private var cleanupHandedOff: Boolean = false
    private var diagnosticPriorFailure: ClassifiedPlaybackFailure? = null
    private var diagnosticReleaseStage: String = "NOT_STARTED"
    private val releaseDiagnostics = PlaybackReleaseDiagnostics()
    private val mutableSnapshots = MutableStateFlow(snapshotUnsafe())

    /**
     * Current presentation-safe session state. Runtime route capabilities, player handles, and
     * resource-lease tokens never cross this observation boundary.
     */
    val snapshots: StateFlow<PlaybackCompatibilitySessionSnapshot> =
        mutableSnapshots.asStateFlow()

    @Volatile
    private var terminalSnapshot: PlaybackCompatibilitySessionSnapshot? = null
    private val worker = sessionScope.launch(start = CoroutineStart.UNDISPATCHED) {
        var workerFailure: Throwable? = null
        try {
            while (true) {
                val message = select<Message> {
                    inbox.onReceive { it }
                    cancellationWake.onReceive { Message.ExternalCancellation }
                }
                var stopRequested = false
                try {
                    reconcileExternalCancellation()
                    when (message) {
                        is Message.Event -> {
                            pump(message.event)
                            message.reply?.complete(snapshotUnsafe())
                        }
                        is Message.Snapshot -> {
                            awaitPersistenceJobs(cancelOnTimeout = false)
                            drainPendingCallbacks()
                            reconcileExternalCancellation()
                            message.reply.complete(snapshotUnsafe())
                        }
                        is Message.Stop -> {
                            settleForStop()
                            message.reply.complete(snapshotUnsafe())
                            stopRequested = true
                        }
                        Message.DrainCallbacks,
                        Message.ExternalCancellation,
                        -> Unit
                    }
                    drainPendingCallbacks()
                    reconcileExternalCancellation()
                } catch (error: Throwable) {
                    message.completeExceptionally(sessionFailureForCallers())
                    throw error
                }
                if (stopRequested) break
            }
        } catch (error: Throwable) {
            workerFailure = error
            throw error
        } finally {
            callbackMailbox.clear()
            if (activeSnapshotPresentation != null) {
                withContext(NonCancellable) {
                    try {
                        requireSnapshotCancellationAcknowledged()
                    } catch (_: Throwable) {
                        // A broken handoff remains represented as active in the terminal snapshot.
                    }
                }
            }
            activeAttempt?.attemptId?.let { attemptId ->
                withContext(NonCancellable) {
                    try {
                        executeRelease(attemptId, force = true)
                    } catch (_: Throwable) {
                        // The adapter contracts are bounded and best-effort here. No raw backend
                        // exception is logged or allowed to cross this final cleanup boundary.
                    }
                }
            }
            activeAttempt?.let { unresolved ->
                try {
                    cleanupHandoff.handoff(unresolved.toCleanupHandoff())
                    activeAttempt = null
                    cleanupHandedOff = true
                } catch (_: Throwable) {
                    // The trusted handoff contract forbids rejection or throws. Retain ownership in
                    // the terminal snapshot if a broken adapter violates that fail-closed contract.
                }
            }
            synchronized(persistenceJobs) { persistenceJobs.toList() }.forEach(Job::cancel)
            val callerFailure = if (workerFailure == null) {
                CancellationException("Playback compatibility session stopped")
            } else {
                sessionFailureForCallers()
            }
            inbox.close(callerFailure)
            while (true) {
                val pending = inbox.tryReceive().getOrNull() ?: break
                pending.completeExceptionally(callerFailure)
            }
            cancellationWake.close()
            terminalSnapshot = snapshotUnsafe()
            publishSnapshot()
        }
    }

    init {
        require(releaseOperationTimeoutMillis > 0)
        require(preparationOperationTimeoutMillis > 0)
        require(persistenceDrainTimeoutMillis > 0)
    }

    private val backendEvents = PlaybackAttemptEventSink { signal ->
        enqueueCallback(signal.attemptId, signal.toPlaybackEvent())
    }
    private val deadlineEvents = PlaybackDeadlineEventSink { event ->
        enqueueCallback(event.attemptId(), event)
    }

    suspend fun begin(): PlaybackCompatibilitySessionSnapshot =
        dispatch(PlaybackEvent.Begin(now()))

    suspend fun dispatch(event: PlaybackEvent): PlaybackCompatibilitySessionSnapshot {
        val reply = CompletableDeferred<PlaybackCompatibilitySessionSnapshot>()
        inbox.send(Message.Event(event, reply))
        return reply.await()
    }

    suspend fun userCancel(): PlaybackCompatibilitySessionSnapshot {
        markExternalCancellation(ExternalCancellation.Neutral(CancellationReason.USER))
        return awaitIdle()
    }

    suspend fun lifecycleCancel(): PlaybackCompatibilitySessionSnapshot {
        markExternalCancellation(ExternalCancellation.Neutral(CancellationReason.LIFECYCLE))
        return awaitIdle()
    }

    suspend fun accessRevoked(
        revocation: PlaybackAccessRevocation,
    ): PlaybackCompatibilitySessionSnapshot {
        markExternalCancellation(ExternalCancellation.AccessRevoked(revocation.authorization))
        return awaitIdle()
    }

    /** FIFO barrier for callbacks which were already submitted to this runner. */
    suspend fun awaitIdle(): PlaybackCompatibilitySessionSnapshot {
        val reply = CompletableDeferred<PlaybackCompatibilitySessionSnapshot>()
        inbox.send(Message.Snapshot(reply))
        return reply.await()
    }

    suspend fun stop(): PlaybackCompatibilitySessionSnapshot = stopMutex.withLock {
        terminalSnapshot?.let { return@withLock it }
        markExternalCancellation(ExternalCancellation.Neutral(CancellationReason.LIFECYCLE))
        val reply = CompletableDeferred<PlaybackCompatibilitySessionSnapshot>()
        inbox.send(Message.Stop(reply))
        val result = reply.await()
        worker.join()
        sessionJob.cancel()
        terminalSnapshot ?: result
    }

    private suspend fun pump(initialEvent: PlaybackEvent) {
        val events = ArrayDeque<PlaybackEvent>()
        events.add(initialEvent)
        while (events.isNotEmpty()) {
            val event = events.removeFirst()
            cancelSatisfiedDeadline(event)
            if (event is PlaybackEvent.AttemptFailed) diagnosticPriorFailure = event.failure
            val transition = reducer.reduce(state, event)
            if (state !is PlaybackCompatibilityState.Releasing &&
                transition.state is PlaybackCompatibilityState.Releasing
            ) {
                releaseDiagnostics.begin(playbackReleaseTrigger(event))
            }
            state = transition.state
            callbackMailbox.acceptOnly(state.correlatedAttemptId())
            val generatedEvents = ArrayList<PlaybackEvent>()
            transition.commands.forEach { command ->
                generatedEvents += execute(command)
            }
            publishSnapshot()
            val processingTime = now()
            generatedEvents.forEach { generated ->
                events.addLast(generated.withElapsedFloor(processingTime))
            }
        }
    }

    private suspend fun execute(command: PlaybackCommand): List<PlaybackEvent> = when (command) {
        is PlaybackCommand.StartAttempt -> executeStart(command)

        is PlaybackCommand.AwaitStableDwell -> {
            val scheduled = schedule(
                command.attemptId,
                PlaybackDeadlineKind.STABLE_DWELL,
                command.notBeforeElapsedMillis,
            )
            if (scheduled) emptyList() else listOf(
                PlaybackEvent.AttemptFailed(command.attemptId, unknownStartFailure(), now()),
            )
        }

        is PlaybackCommand.AwaitReleaseDeadline -> {
            val scheduled = schedule(
                command.attemptId,
                PlaybackDeadlineKind.RELEASE,
                command.notBeforeElapsedMillis,
            )
            if (scheduled) emptyList() else listOf(
                PlaybackEvent.ReleaseDeadlineReached(
                    command.attemptId,
                    maxOf(now(), command.notBeforeElapsedMillis),
                ),
            )
        }

        is PlaybackCommand.CancelReleaseDeadline -> {
            cancelDeadline(PlaybackDeadlineKey(command.attemptId, PlaybackDeadlineKind.RELEASE))
            emptyList()
        }

        is PlaybackCommand.AwaitRecoveryDeadline -> {
            val scheduled = schedule(
                command.attemptId,
                PlaybackDeadlineKind.RECOVERY_BACKOFF,
                command.notBeforeElapsedMillis,
            )
            if (scheduled) emptyList() else listOf(
                PlaybackEvent.RecoveryDeadlineReached(
                    command.attemptId,
                    maxOf(now(), command.notBeforeElapsedMillis),
                ),
            )
        }

        is PlaybackCommand.CancelRecoveryDeadline -> {
            cancelDeadline(
                PlaybackDeadlineKey(command.attemptId, PlaybackDeadlineKind.RECOVERY_BACKOFF),
            )
            emptyList()
        }

        is PlaybackCommand.ReleaseAttempt -> executeRelease(command.attemptId, force = false)

        is PlaybackCommand.ForceReleaseAttempt -> {
            executeRelease(command.attemptId, force = true)
            emptyList()
        }

        is PlaybackCommand.RenderSnapshot -> {
            listOf(executeSnapshot(command))
        }

        is PlaybackCommand.PersistVerifiedStrategy -> {
            cancelAllDeadlines(command.write.attemptId)
            launchPersistenceOperation(command.write.attemptId) {
                persistence.persist(command)
            }
            emptyList()
        }

        is PlaybackCommand.CancelVerifiedStrategyPersistence -> {
            launchPersistenceOperation(command.context.attemptId) {
                persistence.cancel(command)
            }
            emptyList()
        }

        is PlaybackCommand.FinalizeStrategyPersistence ->
            listOfNotNull(persistence.finalize(command))

        is PlaybackCommand.AwaitPersistenceResolutionDeadline -> {
            val scheduled = schedule(
                command.attemptId,
                PlaybackDeadlineKind.PERSISTENCE_RESOLUTION,
                command.notBeforeElapsedMillis,
            )
            if (scheduled) emptyList() else listOf(
                PlaybackEvent.PersistenceResolutionDeadlineReached(
                    command.attemptId,
                    maxOf(now(), command.notBeforeElapsedMillis),
                ),
            )
        }

        is PlaybackCommand.CancelPersistenceResolutionDeadline -> {
            cancelDeadline(
                PlaybackDeadlineKey(
                    command.attemptId,
                    PlaybackDeadlineKind.PERSISTENCE_RESOLUTION,
                ),
            )
            emptyList()
        }

        is PlaybackCommand.QuarantineUnresolvedStrategyPersistence -> {
            persistence.quarantine(command)
            emptyList()
        }

        is PlaybackCommand.RecordImplicatedStrategyFailure -> {
            launchPersistenceOperation(command.failure.attemptId) {
                persistence.recordFailure(command)
                null
            }
            emptyList()
        }
    }

    private suspend fun executeStart(
        command: PlaybackCommand.StartAttempt,
    ): List<PlaybackEvent> {
        check(activeAttempt == null) { "A playback retry cannot start before release" }
        cancellationEventIfRequested()?.let { return listOf(it) }
        val resolution = resolveRoute(command, command.totalDeadlineElapsedMillis)
        if (resolution is PlaybackRouteResolution.Denied) {
            lastContentAuthorization = resolution.authorization
            return listOf(
                contentAccessEvent(command.attempt.id, resolution.authorization),
            )
        }
        resolution as PlaybackRouteResolution.Authorized
        lastContentAuthorization = PlaybackExecutionAuthorization.AUTHORIZED
        cancellationEventIfRequested()?.let { return listOf(it) }

        val leaseResult = try {
            resourceLeases.acquire(
                attemptId = command.attempt.id,
                purpose = state.plan().identity.purpose,
                candidate = command.attempt.candidate,
                resourcePolicy = state.plan().resourcePolicy,
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            PlaybackResourceLeaseResult.Denied(
                PlaybackResourceDenialReason.NO_POLICY_COMPATIBLE_CANDIDATE,
            )
        }
        if (leaseResult is PlaybackResourceLeaseResult.Denied) {
            return listOf(
                PlaybackEvent.AttemptFailed(
                    command.attempt.id,
                    resourceFailure(),
                    now(),
                ),
            )
        }
        leaseResult as PlaybackResourceLeaseResult.Granted

        // Track the lease before invoking any fallible timer/backend adapter. If preparation fails,
        // the reducer's ordinary ReleaseAttempt command must still release it before any retry.
        activeAttempt = ActiveAttempt(
            attemptId = command.attempt.id,
            handle = null,
            lease = leaseResult.lease,
            backendReleased = true,
        )
        cancellationEventIfRequested()?.let { return listOf(it) }

        // Resource acquisition may suspend while privacy or authorization changes. Revalidate the
        // exact route immediately before backend preparation; production tokens must additionally
        // be revision-bound and redeemed atomically inside the trusted backend.
        val finalResolution = resolveRoute(command, command.totalDeadlineElapsedMillis)
        if (finalResolution is PlaybackRouteResolution.Denied) {
            lastContentAuthorization = finalResolution.authorization
            return listOf(
                contentAccessEvent(command.attempt.id, finalResolution.authorization),
            )
        }
        finalResolution as PlaybackRouteResolution.Authorized
        lastContentAuthorization = PlaybackExecutionAuthorization.AUTHORIZED
        cancellationEventIfRequested()?.let { return listOf(it) }
        deadlineEventIfElapsed(command)?.let { return listOf(it) }

        return try {
            check(schedule(
                command.attempt.id,
                PlaybackDeadlineKind.FIRST_FRAME,
                command.attempt.firstFrameDeadlineElapsedMillis,
            )) { "First-frame deadline scheduling failed" }
            deadlineEventIfElapsed(command)?.let { return listOf(it) }
            check(schedule(
                command.attempt.id,
                PlaybackDeadlineKind.TOTAL_BUDGET,
                command.totalDeadlineElapsedMillis,
            )) { "Total playback deadline scheduling failed" }
            cancellationEventIfRequested()?.let { return listOf(it) }
            deadlineEventIfElapsed(command)?.let { return listOf(it) }

            // From this point the backend owns the attempt ID even though a handle may never reach
            // us. Its release-by-ID tombstone closes timeout and revocation acknowledgement races.
            activeAttempt = requireNotNull(activeAttempt).copy(backendReleased = false)
            when (
                val result = awaitBackendStart(
                    command,
                    AuthorizedPlaybackAttempt(
                        attempt = command.attempt,
                        resolvedToken = finalResolution.token,
                        resourcePolicy = state.plan().resourcePolicy,
                    ),
                )
            ) {
                BackendStartWaitResult.Cancelled ->
                    listOf(requireNotNull(cancellationEventIfRequested()))
                BackendStartWaitResult.TimedOut -> listOf(
                    deadlineEventIfElapsed(command)
                        ?: PlaybackEvent.AttemptFailed(
                            command.attempt.id,
                            unknownStartFailure(),
                            now(),
                        ),
                )
                BackendStartWaitResult.Faulted -> {
                    activeAttempt = requireNotNull(activeAttempt).copy(
                        handle = null,
                        backendReleased = true,
                    )
                    listOf(
                        PlaybackEvent.AttemptFailed(
                            command.attempt.id,
                            unknownStartFailure(),
                            now(),
                        ),
                    )
                }
                is BackendStartWaitResult.Completed -> when (val outcome = result.outcome) {
                    is PlaybackBackendStartOutcome.Started -> {
                        activeAttempt = requireNotNull(activeAttempt).copy(
                            handle = outcome.handle,
                            backendReleased = false,
                        )
                        cancellationEventIfRequested()?.let(::listOf) ?: emptyList()
                    }
                    is PlaybackBackendStartOutcome.Failed -> {
                        activeAttempt = requireNotNull(activeAttempt).copy(
                            handle = null,
                            backendReleased = true,
                        )
                        cancellationEventIfRequested()?.let(::listOf)
                            ?: listOf(
                                PlaybackEvent.AttemptFailed(
                                    command.attempt.id,
                                    outcome.failure,
                                    now(),
                                ),
                            )
                    }
                    is PlaybackBackendStartOutcome.AccessLost -> {
                        activeAttempt = requireNotNull(activeAttempt).copy(
                            handle = null,
                            backendReleased = true,
                        )
                        lastContentAuthorization = outcome.authorization
                        cancellationEventIfRequested()?.let(::listOf)
                            ?: listOf(
                                contentAccessEvent(
                                    command.attempt.id,
                                    outcome.authorization,
                                ),
                            )
                    }
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            cancellationEventIfRequested()?.let(::listOf)
                ?: listOf(PlaybackEvent.AttemptFailed(command.attempt.id, unknownStartFailure(), now()))
        }
    }

    private suspend fun executeRelease(
        attemptId: PlaybackAttemptId,
        force: Boolean,
    ): List<PlaybackEvent> {
        val cleanupStarted = now()
        val cleanupDeadline = safeRunnerAdd(cleanupStarted, releaseOperationTimeoutMillis)
        diagnosticReleaseStage = "STARTED"
        val active = activeAttempt
        if (active == null) {
            diagnosticReleaseStage = "NO_ACTIVE_ATTEMPT"
            releaseDiagnostics.completed(force, diagnosticReleaseStage, now() - cleanupStarted)
            cancelAllDeadlines(attemptId, cleanupDeadline)
            return if (force) emptyList() else listOf(PlaybackEvent.AttemptReleased(attemptId, now()))
        }
        if (active.attemptId != attemptId) {
            diagnosticReleaseStage = "ATTEMPT_ID_MISMATCH"
            releaseDiagnostics.completed(force, diagnosticReleaseStage, now() - cleanupStarted)
            cancelAllDeadlines(attemptId, cleanupDeadline)
            return if (force) emptyList() else {
                listOf(PlaybackEvent.AttemptReleaseFailed(attemptId, now()))
            }
        }

        var backendReleased = active.backendReleased
        var leaseReleased = active.lease == null
        val remainingMillis = (cleanupDeadline - now()).coerceAtLeast(0L)
        withTimeoutOrNull(remainingMillis) {
            if (!backendReleased) {
                diagnosticReleaseStage = "BACKEND_PENDING"
                try {
                    backendReleased = backend.release(
                        attemptId,
                        active.handle,
                        force,
                    ) ==
                        PlaybackBackendReleaseOutcome.RELEASED
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    backendReleased = false
                }
            }
            if (backendReleased && !leaseReleased) {
                diagnosticReleaseStage = "LEASE_PENDING"
                leaseReleased = releaseLeaseAdapter(attemptId, requireNotNull(active.lease))
            }
        }
        diagnosticReleaseStage = when {
            !backendReleased -> "BACKEND_NOT_RELEASED"
            !leaseReleased -> "LEASE_NOT_RELEASED"
            else -> "RELEASED"
        }

        activeAttempt = if (backendReleased && leaseReleased) {
            null
        } else {
            active.copy(
                handle = active.handle.takeUnless { backendReleased },
                backendReleased = backendReleased,
                lease = active.lease.takeUnless { leaseReleased },
            )
        }
        cancelAllDeadlines(attemptId, cleanupDeadline)
        releaseDiagnostics.completed(force, diagnosticReleaseStage, now() - cleanupStarted)
        if (force) return emptyList()

        return if (backendReleased && leaseReleased) {
            activeAttempt = null
            listOf(PlaybackEvent.AttemptReleased(attemptId, now()))
        } else {
            listOf(PlaybackEvent.AttemptReleaseFailed(attemptId, now()))
        }
    }

    private suspend fun executeSnapshot(
        command: PlaybackCommand.RenderSnapshot,
    ): PlaybackEvent {
        cancellationEventIfRequested()?.let { return it }
        val operationDeadline = safeRunnerAdd(now(), preparationOperationTimeoutMillis)
        val initialResolution = resolveRoute(command, operationDeadline)
        cancellationEventIfRequested()?.let { return it }
        return when (initialResolution) {
            is PlaybackRouteResolution.Denied -> {
                lastContentAuthorization = initialResolution.authorization
                PlaybackEvent.SnapshotPresentationCompleted(
                    snapshotRouteId = command.routeId,
                    outcome = snapshotPresentationOutcome(initialResolution.authorization),
                    elapsedMillis = now(),
                )
            }
            is PlaybackRouteResolution.Authorized -> {
                val finalResolution = resolveRoute(command, operationDeadline)
                cancellationEventIfRequested()?.let { return it }
                when (finalResolution) {
                    is PlaybackRouteResolution.Denied -> {
                        lastContentAuthorization = finalResolution.authorization
                        PlaybackEvent.SnapshotPresentationCompleted(
                            snapshotRouteId = command.routeId,
                            outcome = snapshotPresentationOutcome(finalResolution.authorization),
                            elapsedMillis = now(),
                        )
                    }
                    is PlaybackRouteResolution.Authorized -> {
                        lastContentAuthorization = PlaybackExecutionAuthorization.AUTHORIZED
                        val presentationId = PlaybackSnapshotPresentationId.fromTrustedRunner(
                            UUID.randomUUID().toString(),
                        )
                        activeSnapshotPresentation = ActiveSnapshotPresentation(presentationId)
                        val renderOutcome = awaitSnapshotRender(
                            AuthorizedSnapshotPresentation(
                                presentationId = presentationId,
                                routeId = command.routeId,
                                resolvedToken = finalResolution.token,
                            ),
                            operationDeadline,
                        )
                        cancellationEventIfRequested()?.let { cancellation ->
                            requireSnapshotCancellationAcknowledged()
                            return cancellation
                        }
                        PlaybackEvent.SnapshotPresentationCompleted(
                            snapshotRouteId = command.routeId,
                            outcome = when (renderOutcome) {
                                PlaybackSnapshotRenderOutcome.Rendered ->
                                    PlaybackSnapshotPresentationOutcome.RENDERED
                                PlaybackSnapshotRenderOutcome.Failed -> {
                                    activeSnapshotPresentation = null
                                    PlaybackSnapshotPresentationOutcome.FAILED
                                }
                                PlaybackSnapshotRenderOutcome.CancellationRequired -> {
                                    requireSnapshotCancellationAcknowledged()
                                    PlaybackSnapshotPresentationOutcome.FAILED
                                }
                                is PlaybackSnapshotRenderOutcome.AccessLost -> {
                                    activeSnapshotPresentation = null
                                    lastContentAuthorization = renderOutcome.authorization
                                    snapshotPresentationOutcome(renderOutcome.authorization)
                                }
                            },
                            elapsedMillis = now(),
                        )
                    }
                }
            }
        }
    }

    private suspend fun releaseLeaseAdapter(
        attemptId: PlaybackAttemptId,
        lease: PlaybackResourceLeaseToken,
    ): Boolean = try {
        resourceLeases.release(attemptId, lease) == PlaybackResourceLeaseReleaseOutcome.RELEASED
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        false
    }

    private suspend fun schedule(
        attemptId: PlaybackAttemptId,
        kind: PlaybackDeadlineKind,
        notBeforeElapsedMillis: Long,
    ): Boolean = boundedLocalAdapterCall {
        deadlines.schedule(
            PlaybackScheduledDeadline(
                PlaybackDeadlineKey(attemptId, kind),
                notBeforeElapsedMillis,
            ),
            deadlineEvents,
        )
    }

    private suspend fun cancelDeadline(key: PlaybackDeadlineKey): Boolean =
        boundedLocalAdapterCall { deadlines.cancel(key) }

    private suspend fun cancelAllDeadlines(
        attemptId: PlaybackAttemptId,
        operationDeadlineElapsedMillis: Long? = null,
    ): Boolean = boundedLocalAdapterCall(operationDeadlineElapsedMillis) {
        deadlines.cancelAll(attemptId)
    }

    private suspend fun cancelSatisfiedDeadline(event: PlaybackEvent) {
        when (event) {
            is PlaybackEvent.FirstFrame -> cancelDeadline(
                PlaybackDeadlineKey(event.attemptId, PlaybackDeadlineKind.FIRST_FRAME),
            )
            is PlaybackEvent.ProbeDeadlineReached -> cancelDeadline(
                PlaybackDeadlineKey(
                    event.attemptId,
                    when (event.phase) {
                        ProbePhase.FIRST_FRAME -> PlaybackDeadlineKind.FIRST_FRAME
                        ProbePhase.STABLE_DWELL -> PlaybackDeadlineKind.STABLE_DWELL
                        ProbePhase.TOTAL_BUDGET -> PlaybackDeadlineKind.TOTAL_BUDGET
                    },
                ),
            )
            is PlaybackEvent.ReleaseDeadlineReached -> cancelDeadline(
                PlaybackDeadlineKey(event.attemptId, PlaybackDeadlineKind.RELEASE),
            )
            is PlaybackEvent.RecoveryDeadlineReached -> cancelDeadline(
                PlaybackDeadlineKey(event.attemptId, PlaybackDeadlineKind.RECOVERY_BACKOFF),
            )
            is PlaybackEvent.PersistenceResolutionDeadlineReached -> cancelDeadline(
                PlaybackDeadlineKey(
                    event.attemptId,
                    PlaybackDeadlineKind.PERSISTENCE_RESOLUTION,
                ),
            )
            else -> Unit
        }
    }

    private suspend fun resolveRoute(
        command: PlaybackContentCommand,
        operationDeadlineElapsedMillis: Long,
    ): PlaybackRouteResolution {
        val contentKind = command.routeContentKind()
        val resolution = boundedPreparationCall(operationDeadlineElapsedMillis) {
            routeGateway.resolve(command)
        } ?: return PlaybackRouteResolution.Denied(
            contentKind,
            PlaybackExecutionAuthorization.STALE_ACCESS,
        )
        return if (resolution.contentKind == contentKind) {
            resolution
        } else {
            PlaybackRouteResolution.Denied(
                contentKind,
                PlaybackExecutionAuthorization.RESOLUTION_UNAVAILABLE,
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitBackendStart(
        command: PlaybackCommand.StartAttempt,
        request: AuthorizedPlaybackAttempt,
    ): BackendStartWaitResult {
        val operationDeadline = minOf(
            command.attempt.firstFrameDeadlineElapsedMillis,
            command.totalDeadlineElapsedMillis,
            safeRunnerAdd(now(), preparationOperationTimeoutMillis),
        )
        val remainingMillis = operationDeadline - now()
        if (remainingMillis <= 0L) return BackendStartWaitResult.TimedOut
        val operation = sessionScope.async {
            if (externalCancellation.get() != null) {
                return@async BackendStartWaitResult.Cancelled
            }
            if (
                now() >= command.attempt.firstFrameDeadlineElapsedMillis ||
                now() >= command.totalDeadlineElapsedMillis
            ) {
                return@async BackendStartWaitResult.TimedOut
            }
            try {
                BackendStartWaitResult.Completed(backend.start(request, backendEvents))
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                BackendStartWaitResult.Faulted
            }
        }
        return select {
            operation.onAwait { it }
            externalCancellationSignal.onAwait {
                operation.cancel()
                BackendStartWaitResult.Cancelled
            }
            onTimeout(remainingMillis) {
                operation.cancel()
                BackendStartWaitResult.TimedOut
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitSnapshotRender(
        request: AuthorizedSnapshotPresentation,
        operationDeadlineElapsedMillis: Long,
    ): PlaybackSnapshotRenderOutcome {
        val remainingMillis = minOf(
            operationDeadlineElapsedMillis - now(),
            preparationOperationTimeoutMillis,
        )
        if (remainingMillis <= 0L) {
            requireSnapshotCancellationAcknowledged()
            return PlaybackSnapshotRenderOutcome.Failed
        }
        val operation = sessionScope.async {
            try {
                SnapshotRenderWaitResult.Completed(backend.renderSnapshot(request))
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                SnapshotRenderWaitResult.Faulted
            }
        }
        return select {
            operation.onAwait { result ->
                when (result) {
                    is SnapshotRenderWaitResult.Completed -> result.outcome
                    SnapshotRenderWaitResult.Faulted -> {
                        requireSnapshotCancellationAcknowledged()
                        PlaybackSnapshotRenderOutcome.Failed
                    }
                }
            }
            externalCancellationSignal.onAwait {
                operation.cancel()
                requireSnapshotCancellationAcknowledged()
                PlaybackSnapshotRenderOutcome.Failed
            }
            onTimeout(remainingMillis) {
                operation.cancel()
                requireSnapshotCancellationAcknowledged()
                PlaybackSnapshotRenderOutcome.Failed
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun requireSnapshotCancellationAcknowledged() {
        val active = activeSnapshotPresentation ?: return
        val cancellation = sessionScope.async(start = CoroutineStart.UNDISPATCHED) {
            try {
                backend.cancelSnapshot(active.presentationId) ==
                    PlaybackSnapshotCancellationOutcome.ACKNOWLEDGED
            } catch (_: Throwable) {
                false
            }
        }
        val acknowledged = select {
            cancellation.onAwait { it }
            onTimeout(releaseOperationTimeoutMillis) {
                cancellation.cancel()
                false
            }
        }
        if (acknowledged) {
            activeSnapshotPresentation = null
            return
        }
        try {
            snapshotCleanupHandoff.handoff(
                PlaybackSnapshotCleanupHandoff(active.presentationId),
            )
        } catch (_: Throwable) {
            throw sessionFailureForCallers()
        }
        activeSnapshotPresentation = null
        cleanupHandedOff = true
    }

    private fun deadlineEventIfElapsed(
        command: PlaybackCommand.StartAttempt,
    ): PlaybackEvent.ProbeDeadlineReached? {
        val observedAt = now()
        return when {
            observedAt >= command.totalDeadlineElapsedMillis ->
                PlaybackEvent.ProbeDeadlineReached(
                    command.attempt.id,
                    ProbePhase.TOTAL_BUDGET,
                    observedAt,
                )
            observedAt >= command.attempt.firstFrameDeadlineElapsedMillis ->
                PlaybackEvent.ProbeDeadlineReached(
                    command.attempt.id,
                    ProbePhase.FIRST_FRAME,
                    observedAt,
                )
            else -> null
        }
    }

    private suspend fun <T> boundedPreparationCall(
        operationDeadlineElapsedMillis: Long,
        block: suspend () -> T,
    ): T? {
        val remainingMillis = operationDeadlineElapsedMillis - now()
        if (remainingMillis <= 0L) return null
        return try {
            withTimeoutOrNull(minOf(remainingMillis, preparationOperationTimeoutMillis)) { block() }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            null
        }
    }

    private suspend fun boundedLocalAdapterCall(
        operationDeadlineElapsedMillis: Long? = null,
        block: suspend () -> Unit,
    ): Boolean = try {
        val timeoutMillis = operationDeadlineElapsedMillis?.let { deadline ->
            minOf(releaseOperationTimeoutMillis, (deadline - now()).coerceAtLeast(0L))
        } ?: releaseOperationTimeoutMillis
        if (timeoutMillis <= 0L) return false
        withTimeoutOrNull(timeoutMillis) {
            block()
            true
        } == true
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        false
    }

    private fun launchPersistenceOperation(
        attemptId: PlaybackAttemptId,
        operation: suspend () -> PlaybackEvent?,
    ) {
        lateinit var job: Job
        job = sessionScope.launch(start = CoroutineStart.LAZY) {
            try {
                operation()?.let { event -> enqueueCallback(attemptId, event) }
            } finally {
                persistenceJobs.remove(job)
            }
        }
        persistenceJobs += job
        job.start()
    }

    private suspend fun awaitPersistenceJobs(cancelOnTimeout: Boolean) {
        val jobs = synchronized(persistenceJobs) { persistenceJobs.toList() }
        if (jobs.isEmpty()) return
        val completed = withTimeoutOrNull(persistenceDrainTimeoutMillis) {
            jobs.joinAll()
            true
        } == true
        if (!completed && cancelOnTimeout) jobs.forEach(Job::cancel)
        drainPendingCallbacks()
    }

    private suspend fun settleForStop() {
        for (iteration in 0 until 4) {
            awaitPersistenceJobs(cancelOnTimeout = true)
            drainPendingCallbacks()
            when (val current = state) {
                is PlaybackCompatibilityState.Releasing -> {
                    val persistenceDeadline = current.persistenceResolutionDeadlineElapsedMillis
                    if (current.pendingPersistence != null && persistenceDeadline != null) {
                        pump(
                            PlaybackEvent.PersistenceResolutionDeadlineReached(
                                current.attempt.id,
                                maxOf(now(), persistenceDeadline),
                            ),
                        )
                    } else {
                        pump(
                            PlaybackEvent.ReleaseDeadlineReached(
                                current.attempt.id,
                                maxOf(now(), current.releaseDeadlineElapsedMillis),
                            ),
                        )
                    }
                }
                is PlaybackCompatibilityState.ReleasedAwaitingPersistence -> pump(
                    PlaybackEvent.PersistenceResolutionDeadlineReached(
                        current.attempt.id,
                        maxOf(now(), current.persistenceResolutionDeadlineElapsedMillis),
                    ),
                )
                else -> break
            }
        }
        awaitPersistenceJobs(cancelOnTimeout = true)
        drainPendingCallbacks()
        activeAttempt?.attemptId?.let { executeRelease(it, force = true) }
        requireSnapshotCancellationAcknowledged()
    }

    private fun enqueueCallback(attemptId: PlaybackAttemptId, event: PlaybackEvent) {
        if (callbackMailbox.offer(attemptId, event)) {
            inbox.trySend(Message.DrainCallbacks)
        }
    }

    private suspend fun drainPendingCallbacks() {
        repeat(4) {
            val pending = callbackMailbox.drain()
            if (pending.isEmpty()) return
            pending.forEach { event -> pump(event) }
        }
    }

    private fun markExternalCancellation(cancellation: ExternalCancellation) {
        while (true) {
            val existing = externalCancellation.get()
            val replacement = preferredExternalCancellation(existing, cancellation)
            if (externalCancellation.compareAndSet(existing, replacement)) {
                externalCancellationSignal.complete(Unit)
                cancellationWake.trySend(Unit)
                return
            }
        }
    }

    private suspend fun reconcileExternalCancellation() {
        val cancellation = externalCancellation.get() ?: return
        if (appliedExternalCancellation == cancellation) return
        appliedExternalCancellation = cancellation
        if (cancellation is ExternalCancellation.AccessRevoked) {
            lastContentAuthorization = cancellation.authorization
        }
        pump(PlaybackEvent.Cancel(cancellation.cancellationReason, now()))
        requireSnapshotCancellationAcknowledged()
        publishSnapshot()
    }

    private fun cancellationEventIfRequested(): PlaybackEvent.Cancel? =
        externalCancellation.get()?.let { cancellation ->
            PlaybackEvent.Cancel(cancellation.cancellationReason, now())
        }

    private fun preferredExternalCancellation(
        existing: ExternalCancellation?,
        candidate: ExternalCancellation,
    ): ExternalCancellation = when {
        existing == null -> candidate
        existing is ExternalCancellation.AccessRevoked &&
            candidate is ExternalCancellation.AccessRevoked ->
            if (candidate.priority > existing.priority) candidate else existing
        existing is ExternalCancellation.AccessRevoked -> existing
        candidate is ExternalCancellation.AccessRevoked -> candidate
        existing.cancellationReason == CancellationReason.USER -> existing
        else -> candidate
    }

    private fun PlaybackCompatibilityState.correlatedAttemptId(): PlaybackAttemptId? = when (this) {
        is PlaybackCompatibilityState.Attempting -> attempt.id
        is PlaybackCompatibilityState.Releasing -> attempt.id
        is PlaybackCompatibilityState.ReleasedAwaitingPersistence -> attempt.id
        is PlaybackCompatibilityState.Persisting -> attempt.id
        is PlaybackCompatibilityState.Verified -> attempt.id
        is PlaybackCompatibilityState.UnpersistedLive -> attempt.id
        is PlaybackCompatibilityState.Recovering -> previousAttemptId
        is PlaybackCompatibilityState.PresentingSnapshot -> null
        is PlaybackCompatibilityState.Idle,
        is PlaybackCompatibilityState.Finished,
        -> null
    }

    private fun PlaybackEvent.withElapsedFloor(floor: Long): PlaybackEvent {
        val adjusted = maxOf(elapsedMillis, floor)
        if (adjusted == elapsedMillis) return this
        return when (this) {
            is PlaybackEvent.Begin -> copy(elapsedMillis = adjusted)
            is PlaybackEvent.SnapshotPresentationCompleted -> copy(elapsedMillis = adjusted)
            is PlaybackEvent.FirstFrame -> copy(elapsedMillis = adjusted)
            is PlaybackEvent.VideoProgress -> copy(elapsedMillis = adjusted)
            is PlaybackEvent.AudioProgress -> copy(elapsedMillis = adjusted)
            is PlaybackEvent.AttemptFailed -> copy(elapsedMillis = adjusted)
            is PlaybackEvent.ProbeDeadlineReached -> copy(elapsedMillis = adjusted)
            is PlaybackEvent.AttemptReleased -> copy(elapsedMillis = adjusted)
            is PlaybackEvent.AttemptReleaseFailed -> copy(elapsedMillis = adjusted)
            is PlaybackEvent.ReleaseDeadlineReached -> copy(elapsedMillis = adjusted)
            is PlaybackEvent.RecoveryDeadlineReached -> copy(elapsedMillis = adjusted)
            is PlaybackEvent.StrategyPersistenceDecisionRequired -> copy(elapsedMillis = adjusted)
            is PlaybackEvent.StrategyPersisted -> copy(elapsedMillis = adjusted)
            is PlaybackEvent.StrategyPersistenceFailed -> copy(elapsedMillis = adjusted)
            is PlaybackEvent.PersistenceResolutionDeadlineReached -> copy(elapsedMillis = adjusted)
            is PlaybackEvent.Cancel -> copy(elapsedMillis = adjusted)
        }
    }

    private fun PlaybackEvent.attemptId(): PlaybackAttemptId = when (this) {
        is PlaybackEvent.ProbeDeadlineReached -> attemptId
        is PlaybackEvent.ReleaseDeadlineReached -> attemptId
        is PlaybackEvent.RecoveryDeadlineReached -> attemptId
        is PlaybackEvent.PersistenceResolutionDeadlineReached -> attemptId
        else -> error("Only scheduled deadline events enter the deadline callback boundary")
    }

    private fun sessionFailureForCallers(): IllegalStateException =
        IllegalStateException("Playback compatibility session failed")

    private fun contentAccessEvent(
        attemptId: PlaybackAttemptId,
        authorization: PlaybackExecutionAuthorization,
    ): PlaybackEvent = if (authorization == PlaybackExecutionAuthorization.STALE_ACCESS) {
        // Stale plan evidence requires a fresh plan. It is neither permission denial nor media
        // evidence and must not advance this plan's candidate ladder.
        PlaybackEvent.Cancel(CancellationReason.LIFECYCLE, now())
    } else {
        PlaybackEvent.AttemptFailed(
            attemptId,
            contentAccessFailure(authorization),
            now(),
        )
    }

    private fun safeRunnerAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun snapshotUnsafe(): PlaybackCompatibilitySessionSnapshot =
        PlaybackCompatibilitySessionSnapshot(
            state = state,
            activeAttemptId = activeAttempt?.attemptId,
            snapshotPresentationActive = activeSnapshotPresentation != null,
            lastContentAuthorization = lastContentAuthorization,
            cleanupHandedOff = cleanupHandedOff,
            diagnosticPriorFailure = diagnosticPriorFailure,
            diagnosticReleaseStage = diagnosticReleaseStage,
            diagnosticReleaseTrigger = releaseDiagnostics.trigger,
            diagnosticInitialCleanup = releaseDiagnostics.initialCleanup,
            diagnosticCleanupMillis = releaseDiagnostics.cleanupMillis,
        )

    private fun publishSnapshot() {
        mutableSnapshots.value = snapshotUnsafe()
    }

    private fun now(): Long = clock.elapsedRealtimeMillis().coerceAtLeast(0)

    private fun PlaybackCompatibilityState.plan(): PlaybackPlan = when (this) {
        is PlaybackCompatibilityState.Idle -> plan
        is PlaybackCompatibilityState.Attempting -> run.plan
        is PlaybackCompatibilityState.Releasing -> run.plan
        is PlaybackCompatibilityState.ReleasedAwaitingPersistence -> run.plan
        is PlaybackCompatibilityState.Persisting -> run.plan
        is PlaybackCompatibilityState.Verified -> run.plan
        is PlaybackCompatibilityState.UnpersistedLive -> run.plan
        is PlaybackCompatibilityState.Recovering -> run.plan
        is PlaybackCompatibilityState.PresentingSnapshot -> run.plan
        is PlaybackCompatibilityState.Finished -> plan
    }

    private fun PlaybackAttemptSignal.toPlaybackEvent(): PlaybackEvent = when (this) {
        is PlaybackAttemptSignal.FirstFrame -> PlaybackEvent.FirstFrame(
            attemptId,
            elapsedMillis,
            decoderEvidence,
        )
        is PlaybackAttemptSignal.VideoProgress -> PlaybackEvent.VideoProgress(
            attemptId,
            sequence,
            elapsedMillis,
        )
        is PlaybackAttemptSignal.AudioProgress -> PlaybackEvent.AudioProgress(
            attemptId,
            sequence,
            elapsedMillis,
        )
        is PlaybackAttemptSignal.Failed -> PlaybackEvent.AttemptFailed(
            attemptId,
            failure,
            elapsedMillis,
        )
    }

    private data class ActiveAttempt(
        val attemptId: PlaybackAttemptId,
        val handle: PlaybackAttemptHandleToken?,
        val lease: PlaybackResourceLeaseToken?,
        val backendReleased: Boolean = false,
    ) {
        fun toCleanupHandoff(): PlaybackCleanupHandoff = PlaybackCleanupHandoff(
            attemptId = attemptId,
            handle = handle,
            lease = lease,
            backendReleased = backendReleased,
        )
    }

    private data class ActiveSnapshotPresentation(
        val presentationId: PlaybackSnapshotPresentationId,
    )

    private sealed interface ExternalCancellation {
        val cancellationReason: CancellationReason

        data class Neutral(
            val reason: CancellationReason,
        ) : ExternalCancellation {
            override val cancellationReason: CancellationReason = reason
        }

        data class AccessRevoked(
            val authorization: PlaybackExecutionAuthorization,
        ) : ExternalCancellation {
            init {
                require(authorization in setOf(
                    PlaybackExecutionAuthorization.AUTHENTICATION_REQUIRED,
                    PlaybackExecutionAuthorization.STALE_ACCESS,
                    PlaybackExecutionAuthorization.SOURCE_NOT_AUTHORIZED,
                    PlaybackExecutionAuthorization.SNAPSHOT_NOT_AUTHORIZED,
                    PlaybackExecutionAuthorization.PRIVACY_REVOKED,
                )) { "Active-session revocation requires exact access-loss evidence" }
            }

            override val cancellationReason: CancellationReason = CancellationReason.LIFECYCLE

            val priority: Int
                get() = when (authorization) {
                    PlaybackExecutionAuthorization.AUTHENTICATION_REQUIRED -> 5
                    PlaybackExecutionAuthorization.PRIVACY_REVOKED -> 4
                    PlaybackExecutionAuthorization.SOURCE_NOT_AUTHORIZED,
                    PlaybackExecutionAuthorization.SNAPSHOT_NOT_AUTHORIZED,
                    -> 3
                    PlaybackExecutionAuthorization.STALE_ACCESS -> 2
                    PlaybackExecutionAuthorization.AUTHORIZED,
                    PlaybackExecutionAuthorization.RESOLUTION_UNAVAILABLE,
                    -> 0
                }
        }
    }

    private sealed interface BackendStartWaitResult {
        data class Completed(
            val outcome: PlaybackBackendStartOutcome,
        ) : BackendStartWaitResult

        data object Cancelled : BackendStartWaitResult
        data object TimedOut : BackendStartWaitResult
        data object Faulted : BackendStartWaitResult
    }

    private sealed interface SnapshotRenderWaitResult {
        data class Completed(
            val outcome: PlaybackSnapshotRenderOutcome,
        ) : SnapshotRenderWaitResult

        data object Faulted : SnapshotRenderWaitResult
    }

    private class CallbackMailbox {
        private val pending = LinkedHashMap<CallbackKey, PlaybackEvent>()
        private var acceptedAttemptId: PlaybackAttemptId? = null
        private var signalPending = false

        @Synchronized
        fun acceptOnly(attemptId: PlaybackAttemptId?) {
            acceptedAttemptId = attemptId
            pending.keys.removeAll { it.attemptId != attemptId }
            if (pending.isEmpty()) signalPending = false
        }

        @Synchronized
        fun offer(attemptId: PlaybackAttemptId, event: PlaybackEvent): Boolean {
            if (attemptId != acceptedAttemptId) return false
            val kind = event.callbackKind()
            if (event.isAuthoritativePersistenceProgress()) {
                pending.remove(
                    CallbackKey(attemptId, CallbackKind.PERSISTENCE_RESOLUTION_UNAVAILABLE),
                )
            }
            val key = CallbackKey(attemptId, kind)
            val existing = pending[key]
            pending[key] = when {
                existing is PlaybackEvent.VideoProgress && event is PlaybackEvent.VideoProgress ->
                    if (event.sequence > existing.sequence) event else existing
                existing is PlaybackEvent.AudioProgress && event is PlaybackEvent.AudioProgress ->
                    if (event.sequence > existing.sequence) event else existing
                existing != null -> existing
                else -> event
            }
            if (signalPending) return false
            signalPending = true
            return true
        }

        @Synchronized
        fun drain(): List<PlaybackEvent> {
            signalPending = false
            if (pending.isEmpty()) return emptyList()
            val snapshot = pending.values.toList()
            pending.clear()
            return snapshot
        }

        @Synchronized
        fun clear() {
            acceptedAttemptId = null
            signalPending = false
            pending.clear()
        }

        private fun PlaybackEvent.isDefinitivePersistenceResolution(): Boolean =
            this is PlaybackEvent.StrategyPersisted ||
                (this is PlaybackEvent.StrategyPersistenceFailed &&
                    reason != PersistenceFailureReason.RESOLUTION_UNAVAILABLE)

        private fun PlaybackEvent.isAuthoritativePersistenceProgress(): Boolean =
            this is PlaybackEvent.StrategyPersistenceDecisionRequired ||
                isDefinitivePersistenceResolution()

        private fun PlaybackEvent.callbackKind(): CallbackKind = when (this) {
            is PlaybackEvent.FirstFrame -> CallbackKind.FIRST_FRAME
            is PlaybackEvent.VideoProgress -> CallbackKind.VIDEO_PROGRESS
            is PlaybackEvent.AudioProgress -> CallbackKind.AUDIO_PROGRESS
            is PlaybackEvent.AttemptFailed -> CallbackKind.ATTEMPT_FAILED
            is PlaybackEvent.ProbeDeadlineReached -> when (phase) {
                ProbePhase.FIRST_FRAME -> CallbackKind.FIRST_FRAME_DEADLINE
                ProbePhase.STABLE_DWELL -> CallbackKind.STABLE_DWELL_DEADLINE
                ProbePhase.TOTAL_BUDGET -> CallbackKind.TOTAL_BUDGET_DEADLINE
            }
            is PlaybackEvent.ReleaseDeadlineReached -> CallbackKind.RELEASE_DEADLINE
            is PlaybackEvent.RecoveryDeadlineReached -> CallbackKind.RECOVERY_DEADLINE
            is PlaybackEvent.PersistenceResolutionDeadlineReached ->
                CallbackKind.PERSISTENCE_RESOLUTION_DEADLINE
            is PlaybackEvent.StrategyPersistenceDecisionRequired ->
                CallbackKind.PERSISTENCE_DECISION_REQUIRED
            is PlaybackEvent.StrategyPersisted -> CallbackKind.PERSISTENCE_COMMITTED
            is PlaybackEvent.StrategyPersistenceFailed -> when (reason) {
                PersistenceFailureReason.RESOLUTION_UNAVAILABLE ->
                    CallbackKind.PERSISTENCE_RESOLUTION_UNAVAILABLE
                else -> CallbackKind.PERSISTENCE_FAILED
            }
            else -> error("Unsupported callback event")
        }

        private data class CallbackKey(
            val attemptId: PlaybackAttemptId,
            val kind: CallbackKind,
        )
    }

    private enum class CallbackKind {
        FIRST_FRAME,
        VIDEO_PROGRESS,
        AUDIO_PROGRESS,
        ATTEMPT_FAILED,
        FIRST_FRAME_DEADLINE,
        STABLE_DWELL_DEADLINE,
        TOTAL_BUDGET_DEADLINE,
        RELEASE_DEADLINE,
        RECOVERY_DEADLINE,
        PERSISTENCE_RESOLUTION_DEADLINE,
        PERSISTENCE_DECISION_REQUIRED,
        PERSISTENCE_COMMITTED,
        PERSISTENCE_RESOLUTION_UNAVAILABLE,
        PERSISTENCE_FAILED,
    }

    private sealed interface Message {
        fun completeExceptionally(error: Throwable)

        data class Event(
            val event: PlaybackEvent,
            val reply: CompletableDeferred<PlaybackCompatibilitySessionSnapshot>?,
        ) : Message {
            override fun completeExceptionally(error: Throwable) {
                reply?.completeExceptionally(error)
            }
        }

        data class Snapshot(
            val reply: CompletableDeferred<PlaybackCompatibilitySessionSnapshot>,
        ) : Message {
            override fun completeExceptionally(error: Throwable) {
                reply.completeExceptionally(error)
            }
        }

        data class Stop(
            val reply: CompletableDeferred<PlaybackCompatibilitySessionSnapshot>,
        ) : Message {
            override fun completeExceptionally(error: Throwable) {
                reply.completeExceptionally(error)
            }
        }

        data object DrainCallbacks : Message {
            override fun completeExceptionally(error: Throwable) = Unit
        }

        data object ExternalCancellation : Message {
            override fun completeExceptionally(error: Throwable) = Unit
        }
    }

    private companion object {
        @Suppress("UNUSED_PARAMETER")
        fun uniqueRunSessionId(requestedSessionId: PlaybackSessionId): PlaybackSessionId =
            PlaybackSessionId("run-${UUID.randomUUID()}")

        fun contentAccessFailure(
            authorization: PlaybackExecutionAuthorization,
        ): ClassifiedPlaybackFailure = when (authorization) {
            PlaybackExecutionAuthorization.AUTHENTICATION_REQUIRED -> ClassifiedPlaybackFailure(
                category = FailureCategory.AUTHENTICATION,
                phase = FailurePhase.PREPARE,
                diagnosticCode = PlaybackDiagnosticCode.SESSION_EXPIRED,
            )
            PlaybackExecutionAuthorization.STALE_ACCESS -> ClassifiedPlaybackFailure(
                category = FailureCategory.SOURCE_UNAVAILABLE,
                phase = FailurePhase.PREPARE,
                diagnosticCode = PlaybackDiagnosticCode.STALE_ACCESS,
            )
            PlaybackExecutionAuthorization.RESOLUTION_UNAVAILABLE -> ClassifiedPlaybackFailure(
                category = FailureCategory.SOURCE_UNAVAILABLE,
                phase = FailurePhase.PREPARE,
                diagnosticCode = PlaybackDiagnosticCode.RESOLUTION_UNAVAILABLE,
            )
            PlaybackExecutionAuthorization.SOURCE_NOT_AUTHORIZED -> ClassifiedPlaybackFailure(
                category = FailureCategory.AUTHORIZATION,
                phase = FailurePhase.PREPARE,
                diagnosticCode = PlaybackDiagnosticCode.SOURCE_NOT_AUTHORIZED,
            )
            PlaybackExecutionAuthorization.SNAPSHOT_NOT_AUTHORIZED -> ClassifiedPlaybackFailure(
                category = FailureCategory.AUTHORIZATION,
                phase = FailurePhase.PREPARE,
                diagnosticCode = PlaybackDiagnosticCode.SNAPSHOT_NOT_AUTHORIZED,
            )
            PlaybackExecutionAuthorization.PRIVACY_REVOKED -> ClassifiedPlaybackFailure(
                category = FailureCategory.AUTHORIZATION,
                phase = FailurePhase.PREPARE,
                diagnosticCode = PlaybackDiagnosticCode.PRIVACY_REVOKED,
            )
            PlaybackExecutionAuthorization.AUTHORIZED ->
                error("Authorized playback cannot produce an access failure")
        }

        fun snapshotPresentationOutcome(
            authorization: PlaybackExecutionAuthorization,
        ): PlaybackSnapshotPresentationOutcome = when (authorization) {
            PlaybackExecutionAuthorization.AUTHENTICATION_REQUIRED,
            PlaybackExecutionAuthorization.SNAPSHOT_NOT_AUTHORIZED,
            PlaybackExecutionAuthorization.PRIVACY_REVOKED,
            -> PlaybackSnapshotPresentationOutcome.DENIED

            PlaybackExecutionAuthorization.STALE_ACCESS,
            PlaybackExecutionAuthorization.RESOLUTION_UNAVAILABLE,
            -> PlaybackSnapshotPresentationOutcome.FAILED

            PlaybackExecutionAuthorization.AUTHORIZED,
            PlaybackExecutionAuthorization.SOURCE_NOT_AUTHORIZED,
            -> error("Snapshot presentation received incompatible authorization evidence")
        }

        fun resourceFailure(): ClassifiedPlaybackFailure = ClassifiedPlaybackFailure(
            category = FailureCategory.DEVICE_RESOURCE_OR_DECODER_LIMIT,
            phase = FailurePhase.PREPARE,
            diagnosticCode = PlaybackDiagnosticCode.RETRYABLE,
        )

        fun unknownStartFailure(): ClassifiedPlaybackFailure = ClassifiedPlaybackFailure(
            category = FailureCategory.UNKNOWN,
            phase = FailurePhase.PREPARE,
            diagnosticCode = PlaybackDiagnosticCode.UNCLASSIFIED,
        )
    }
}
