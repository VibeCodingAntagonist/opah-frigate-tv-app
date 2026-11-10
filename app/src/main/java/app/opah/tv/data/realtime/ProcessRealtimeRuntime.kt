package app.opah.tv.data.realtime

import app.opah.tv.awareness.AwarenessAuthorizedReviewBatchSink
import app.opah.tv.awareness.ProcessAwarenessOwner
import app.opah.tv.data.FrigateJsonParsers
import app.opah.tv.data.FrigateVersionPolicy
import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.model.FrigateApiGeneration
import app.opah.tv.data.network.FrigateGateway
import kotlinx.coroutines.CoroutineScope
import okhttp3.WebSocket

/** Owns the fully assembled process-wide realtime and Awareness graph. */
class ProcessRealtimeRuntime(
    processScope: CoroutineScope,
    webSockets: WebSocket.Factory,
    gateway: FrigateGateway,
    parsers: FrigateJsonParsers,
    private val savedProfile: suspend () -> ConnectionProfile?,
    private val profileKey: (ConnectionProfile) -> String,
    private val wallClock: RealtimeWallClock,
    private val versionPolicy: FrigateVersionPolicy = FrigateVersionPolicy(),
) {
    private val ownerDelegate: ProcessRealtimeTransportOwner
    private val currentState = { ownerDelegate.currentState() }
    private val events = RealtimeEventSink { event -> ownerDelegate.dispatch(event) }
    private val profiles = RealtimeConnectionProfileResolver { requestedProfileId ->
        savedProfile()?.takeIf { profile -> profileKey(profile) == requestedProfileId }
    }
    private val timers = CoroutineRealtimeTimerPort(processScope, events)
    private val authorization = CoroutineAuthorizationScopePort(
        scope = processScope,
        source = FrigateAuthorizationScopeSource(profiles, gateway, parsers),
        events = events,
        currentState = currentState,
    )
    val awareness = ProcessAwarenessOwner(currentState, events, wallClock)
    private val reconciliation = CoroutineAuthorizedReviewReconciliationPort(
        scope = processScope,
        source = FrigateReviewReconciliationSource(profiles, gateway, parsers),
        sink = AwarenessAuthorizedReviewBatchSink(awareness),
        events = events,
        currentState = currentState,
        wallClock = wallClock,
    )
    private val socket = OkHttpAuthenticatedRealtimeSocketPort(
        webSockets = webSockets,
        scope = processScope,
        profiles = profiles,
        parser = RealtimeInboundMessageParser(),
        events = events,
        currentState = currentState,
    )
    val owner: ProcessRealtimeTransportOwner

    init {
        val executor = PortRealtimeTransportCommandExecutor(
            socket = socket,
            reconciliation = reconciliation,
            authorizationScope = authorization,
            timers = timers,
            sink = awareness,
            ptzRejections = PtzCommandRejectionPort { },
        )
        ownerDelegate = ProcessRealtimeTransportOwner(RealtimeTransportReducer(wallClock = wallClock), executor)
        owner = ownerDelegate
    }

    fun activateAuthenticatedProfile(profile: ConnectionProfile, rawFrigateVersion: String) {
        val contract = realtimeContractForVersion(rawFrigateVersion, versionPolicy)
        owner.dispatch(
            RealtimeTransportEvent.ProfileChanged(
                RealtimeProfile(profileKey(profile), contract),
                committedReconciliationWatermarkEpochMillis = null,
            ),
        )
        owner.dispatch(RealtimeTransportEvent.AuthenticationRestored(owner.currentState().generation))
    }

    fun signOut() {
        owner.dispatch(RealtimeTransportEvent.SignedOut)
        authorization.cancelAll()
        reconciliation.cancelAll()
        timers.cancelAll()
    }
}

internal fun realtimeContractForVersion(
    rawFrigateVersion: String,
    versionPolicy: FrigateVersionPolicy = FrigateVersionPolicy(),
): FrigateRealtimeContract = versionPolicy.evaluate(rawFrigateVersion).let { version ->
    when {
        // Exact-build validation is diagnostic evidence, not a 0.18 transport gate.
        // Authentication and fresh camera scope are enforced by the transport owner.
        version.apiGeneration == FrigateApiGeneration.V0_18 -> FrigateRealtimeContract.FRIGATE_0_18
        version.apiGeneration == FrigateApiGeneration.V0_17 && version.validatedContract ->
            FrigateRealtimeContract.FRIGATE_0_17_2
        else -> FrigateRealtimeContract.UNSUPPORTED
    }
}
