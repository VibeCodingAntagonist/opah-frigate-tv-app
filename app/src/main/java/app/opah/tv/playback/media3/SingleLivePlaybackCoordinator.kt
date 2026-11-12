package app.opah.tv.playback.media3

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import app.opah.tv.data.model.AudioCodec as DiscoveryAudioCodec
import app.opah.tv.data.model.Camera
import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.model.DeviceDiagnostics
import app.opah.tv.data.model.DiscoverySnapshot
import app.opah.tv.data.model.FrigateUserProfile
import app.opah.tv.data.model.LiveStreamOption
import app.opah.tv.data.model.VideoCodec as DiscoveryVideoCodec
import app.opah.tv.data.compatibilityIdentityComponents
import app.opah.tv.device.AndroidPlaybackResourceCapacityAdapter
import app.opah.tv.playback.compatibility.AudioCodec
import app.opah.tv.playback.compatibility.AudioMode
import app.opah.tv.playback.compatibility.AuthorizingPlaybackRouteGateway
import app.opah.tv.playback.compatibility.CompatibilityIdentityDomain
import app.opah.tv.playback.compatibility.CompatibilityIdentityFactory
import app.opah.tv.playback.compatibility.CurrentPlaybackAccess
import app.opah.tv.playback.compatibility.DecoderMode
import app.opah.tv.playback.compatibility.KnownPlaybackSource
import app.opah.tv.playback.compatibility.MonotonicCoroutinePlaybackDeadlineScheduler
import app.opah.tv.playback.compatibility.PlaybackAccessGuard
import app.opah.tv.playback.compatibility.PlaybackCameraAuthorization
import app.opah.tv.playback.compatibility.PlaybackCandidatePlanner
import app.opah.tv.playback.compatibility.PlaybackCandidate
import app.opah.tv.playback.compatibility.CandidateOrigin
import app.opah.tv.playback.compatibility.PlaybackCompatibilityContext
import app.opah.tv.playback.compatibility.PlaybackCompatibilityIdentity
import app.opah.tv.playback.compatibility.PlaybackCompatibilitySessionRunner
import app.opah.tv.playback.compatibility.PlaybackCompatibilitySessionSnapshot
import app.opah.tv.playback.compatibility.PlaybackElapsedRealtimeClock
import app.opah.tv.playback.compatibility.PlaybackMediaMetadata
import app.opah.tv.playback.compatibility.PlaybackPurpose
import app.opah.tv.playback.compatibility.PlaybackResourceLeaseCoordinator
import app.opah.tv.playback.compatibility.PlaybackResourcePolicy
import app.opah.tv.playback.compatibility.PlaybackRouteId
import app.opah.tv.playback.compatibility.PlaybackRouteLookup
import app.opah.tv.playback.compatibility.PlaybackSessionId
import app.opah.tv.playback.compatibility.PlaybackStrategyPersistenceCoordinator
import app.opah.tv.playback.compatibility.PlaybackStreamId
import app.opah.tv.playback.compatibility.ProcessPlaybackCleanupOwner
import app.opah.tv.playback.compatibility.ProcessPlaybackSnapshotCleanupOwner
import app.opah.tv.playback.compatibility.TransportMode
import app.opah.tv.playback.compatibility.StoredPlaybackStrategy
import app.opah.tv.playback.compatibility.VerifiedPlaybackStrategyStore
import app.opah.tv.playback.compatibility.VideoCodec
import java.net.URI
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** Opaque UI/navigation capability for one already-authorized single-camera live request. */
@JvmInline
internal value class SingleLivePlaybackRequestId(val value: String) {
    init {
        require(REQUEST_ID_PATTERN.matches(value))
    }
}

/**
 * Process composition for the first production compatibility-engine path.
 *
 * The coordinator retains validated profile and route material behind an opaque request ID. The
 * UI receives neither an RTSP URI nor a host. Only one session may own the process Media3 player,
 * route snapshot, and output slot at a time.
 */
internal class SingleLivePlaybackCoordinator(
    context: Context,
    private val processScope: CoroutineScope,
    private val identityFactory: CompatibilityIdentityFactory,
    private val strategyStore: VerifiedPlaybackStrategyStore,
    private val persistence: PlaybackStrategyPersistenceCoordinator,
    capacityAdapter: AndroidPlaybackResourceCapacityAdapter =
        AndroidPlaybackResourceCapacityAdapter(),
    private val clock: PlaybackElapsedRealtimeClock =
        PlaybackElapsedRealtimeClock(SystemClock::elapsedRealtime),
    private val videoOutputs: Media3VideoOutputRegistry =
        ProcessMedia3VideoOutputRegistry.instance,
) {
    private val applicationContext = context.applicationContext
    private val requestLock = Any()
    private val requests = LinkedHashMap<SingleLivePlaybackRequestId, PreparedRequest>()
    private val revision = AtomicLong(0L)
    private val revocationEpoch = AtomicLong(0L)
    private val routeSource = ActiveSingleLiveRouteSnapshotSource()
    private val routeGateway = RevisionBoundRtspRouteGateway(routeSource)
    private val backend = Media3PlaybackAttemptBackend(
        context = applicationContext,
        tokenRedeemer = routeGateway,
        videoOutputs = videoOutputs,
    )
    private val resourceLeases = PlaybackResourceLeaseCoordinator(
        capacity = capacityAdapter.currentCapacity(),
    )
    private val deadlines = MonotonicCoroutinePlaybackDeadlineScheduler(
        schedulerScope = processScope,
        clock = clock,
    )
    private val cleanupOwner = ProcessPlaybackCleanupOwner(
        backend = backend,
        resourceLeases = resourceLeases,
        processScope = processScope,
        clock = clock,
    )
    private val snapshotCleanupOwner = ProcessPlaybackSnapshotCleanupOwner(
        backend = backend,
        processScope = processScope,
        clock = clock,
    )
    private val sessionMutex = Mutex()
    @Volatile
    private var activeSession: SingleLivePlaybackSession? = null

    fun prepare(
        profile: ConnectionProfile,
        snapshot: DiscoverySnapshot,
        camera: Camera,
        preferredStreamName: String,
        device: DeviceDiagnostics,
        preferRtpTcp: Boolean,
        useSavedStrategyFirst: Boolean = true,
        purpose: PlaybackPurpose = PlaybackPurpose.SINGLE_LIVE,
    ): SingleLivePlaybackRequestId {
        require(purpose == PlaybackPurpose.SINGLE_LIVE || purpose == PlaybackPurpose.MONITOR) {
            "This coordinator only accepts single-output live playback purposes"
        }
        require(camera.name in snapshot.user.allowedCameras) { "Camera is not permitted" }
        require(snapshot.cameras.any { it.name == camera.name }) { "Camera is unavailable" }
        require(preferredStreamName.isNotBlank()) { "Live stream is unavailable" }
        require(camera.streams.any { it.streamName == preferredStreamName }) {
            "Live stream is unavailable"
        }

        val prepared = PreparedRequest(
            revocationEpoch = revocationEpoch.get(),
            profile = profile,
            user = snapshot.user,
            frigateVersion = snapshot.frigateVersion,
            camera = camera,
            preferredStreamName = preferredStreamName,
            device = device,
            preferRtpTcp = preferRtpTcp,
            useSavedStrategyFirst = useSavedStrategyFirst,
            purpose = purpose,
        )
        val id = SingleLivePlaybackRequestId("live-${UUID.randomUUID()}")
        synchronized(requestLock) {
            requests[id] = prepared
            while (requests.size > MAX_PREPARED_REQUESTS) {
                requests.remove(requests.keys.first())
            }
        }
        return id
    }

    suspend fun open(
        requestId: SingleLivePlaybackRequestId,
        output: Media3VideoOutputTarget,
        videoOnly: Boolean,
    ): SingleLivePlaybackSession = sessionMutex.withLock {
        check(activeSession == null) { "Another live camera is still closing" }
        val request = synchronized(requestLock) { requests[requestId] }
            ?: error("This live camera request has expired")
        val materialized = materialize(request, videoOnly)
        val knownGood = if (request.useSavedStrategyFirst) {
            runCatching { strategyStore.load(materialized.context.identity) }.getOrNull()
        } else {
            null
        }
        check(request.revocationEpoch == revocationEpoch.get()) {
            "This live camera request has expired"
        }
        val plan = PlaybackCandidatePlanner().plan(materialized.context, knownGood)
        check(plan.candidates.isNotEmpty()) { "No compatible live stream is available" }

        val registration = when (val result = videoOutputs.register(output)) {
            is Media3VideoOutputRegistrationResult.Registered -> result.registration
            Media3VideoOutputRegistrationResult.RejectedClaimed ->
                error("Another live camera is still closing")
            Media3VideoOutputRegistrationResult.RejectedGenerationExhausted ->
                error("Live playback needs an app restart")
        }
        val activation = routeSource.activate(materialized.routeSnapshot)
        val runner = PlaybackCompatibilitySessionRunner(
            scope = processScope,
            plan = plan,
            sessionId = PlaybackSessionId("single-${UUID.randomUUID()}"),
            clock = clock,
            routeGateway = AuthorizingPlaybackRouteGateway(routeGateway),
            resourceLeases = resourceLeases,
            backend = backend,
            persistence = persistence,
            deadlines = deadlines,
            cleanupHandoff = cleanupOwner,
            snapshotCleanupHandoff = snapshotCleanupOwner,
        )
        lateinit var session: SingleLivePlaybackSession
        session = SingleLivePlaybackSession(
            snapshots = runner.snapshots,
            explanationDescriptor = materialized.explanationDescriptor,
            beginAction = runner::begin,
            revokeAction = {
                runner.accessRevoked(
                    app.opah.tv.playback.compatibility.PlaybackAccessRevocation.SESSION_EXPIRED,
                )
            },
            closeAction = {
                sessionMutex.withLock {
                    if (activeSession !== session) return@withLock
                    try {
                        cleanupExact(runner, registration, activation)
                    } finally {
                        activeSession = null
                    }
                }
            },
        )
        activeSession = session
        try {
            check(request.revocationEpoch == revocationEpoch.get()) {
                "This live camera request has expired"
            }
            session.begin()
            session
        } catch (error: Throwable) {
            try {
                withContext(NonCancellable) {
                    cleanupExact(runner, registration, activation)
                }
            } finally {
                activeSession = null
            }
            throw error
        }
    }

    suspend fun reset(requestId: SingleLivePlaybackRequestId) {
        val request = synchronized(requestLock) { requests[requestId] }
            ?: error("This camera request has expired")
        val identity = materialize(request, videoOnly = false).context.identity
        strategyStore.reset(identity)
    }

    suspend fun loadSavedStrategy(requestId: SingleLivePlaybackRequestId): StoredPlaybackStrategy? {
        val request = synchronized(requestLock) { requests[requestId] }
            ?: error("This camera request has expired")
        val identity = materialize(request, videoOnly = false).context.identity
        return strategyStore.load(identity)
    }

    /** Immediately removes route/request material, then asynchronously releases any live owner. */
    fun revokeAll() {
        revocationEpoch.updateAndGet { current ->
            check(current != Long.MAX_VALUE) { "Playback revocation epoch is exhausted" }
            current + 1L
        }
        synchronized(requestLock) { requests.clear() }
        routeSource.revokeAll()
        val session = activeSession
        processScope.launch {
            try {
                session?.revokeAccess()
            } finally {
                routeGateway.invalidate()
            }
        }
    }

    private suspend fun cleanupExact(
        runner: PlaybackCompatibilitySessionRunner,
        registration: Media3VideoOutputRegistration,
        activation: RouteSnapshotActivation,
    ) {
        try {
            runner.stop()
        } finally {
            routeSource.deactivate(activation)
            routeGateway.invalidate()
            check(videoOutputs.unregister(registration)) {
                "Live video output could not be released"
            }
        }
    }

    private fun materialize(
        request: PreparedRequest,
        videoOnly: Boolean,
    ): MaterializedRequest {
        val revisionValue = revision.updateAndGet { current ->
            check(current != Long.MAX_VALUE) { "Live authorization revision is exhausted" }
            current + 1L
        }
        val profileScope = identityFactory.derive(
            CompatibilityIdentityDomain.PROFILE,
            request.profile.compatibilityIdentityComponents(),
        )
        val cameraScope = identityFactory.derive(
            CompatibilityIdentityDomain.CAMERA,
            mapOf("camera" to request.camera.name),
        )
        val authorizationScope = identityFactory.derive(
            CompatibilityIdentityDomain.AUTHORIZATION,
            mapOf(
                "username" to request.user.username,
                "role" to request.user.role,
                "allowed_cameras" to request.user.allowedCameras.sorted().joinToString("\u001f"),
            ),
        )
        val deviceScope = identityFactory.derive(
            CompatibilityIdentityDomain.DEVICE,
            request.device.identityComponents(),
        )
        val serverScope = identityFactory.derive(
            CompatibilityIdentityDomain.SERVER_API,
            mapOf("frigate_version" to request.frigateVersion),
        )
        val orderedOptions = request.camera.streams
            .filter { it.streamName.isNotBlank() }
            .distinctBy(LiveStreamOption::streamName)
            .sortedWith(
                compareBy<LiveStreamOption> { it.streamName != request.preferredStreamName }
                    .thenBy { it.streamName },
            )
            .take(MAX_LIVE_SOURCES)
        val streamConfigurationScope = identityFactory.derive(
            CompatibilityIdentityDomain.STREAM_CONFIGURATION,
            mapOf(
                "camera" to request.camera.name,
                "streams" to orderedOptions.joinToString("\u001e") { it.configurationIdentity() },
            ),
        )
        val resourcePolicy = PlaybackResourcePolicy.defaultFor(request.purpose).copy(
            audioPermitted = !videoOnly,
        )
        val identity = PlaybackCompatibilityIdentity(
            profileScope = profileScope,
            cameraScope = cameraScope,
            authorizationScope = authorizationScope,
            deviceScope = deviceScope,
            osApiLevel = request.device.apiLevel,
            appCompatibilityRevision = SINGLE_LIVE_COMPATIBILITY_REVISION,
            serverApiGeneration = serverScope,
            streamConfigurationRevision = streamConfigurationScope,
            purpose = request.purpose,
            resourceClass = resourcePolicy.resourceClass,
        )
        val guard = PlaybackAccessGuard(
            profileScope = profileScope,
            cameraScope = cameraScope,
            authorizationScope = authorizationScope,
            authorizationRevision = revisionValue,
            privacyRevision = 0L,
        )
        val routes = LinkedHashMap<PlaybackRouteLookup, PrevalidatedRtspUri>()
        val sourcePresentations = LinkedHashMap<
            app.opah.tv.playback.compatibility.CompatibilityIdentityKey,
            SingleLiveSourcePresentation,
        >()
        val sources = orderedOptions.mapIndexed { index, option ->
            val sourceScope = identityFactory.derive(
                CompatibilityIdentityDomain.PLAYBACK_SOURCE,
                mapOf(
                    "camera" to request.camera.name,
                    "stream" to option.streamName,
                    "metadata" to option.configurationIdentity(),
                ),
            )
            val routeKey = identityFactory.derive(
                CompatibilityIdentityDomain.PLAYBACK_SOURCE,
                mapOf("kind" to "route", "camera" to request.camera.name, "stream" to option.streamName),
            )
            val streamKey = identityFactory.derive(
                CompatibilityIdentityDomain.PLAYBACK_SOURCE,
                mapOf("kind" to "stream", "camera" to request.camera.name, "stream" to option.streamName),
            )
            val source = KnownPlaybackSource(
                routeId = PlaybackRouteId(routeKey.value),
                streamId = PlaybackStreamId(streamKey.value),
                persistenceScope = sourceScope,
                media = option.toPlaybackMetadata(),
                preferenceRank = index,
                supportedTransports = listOf(TransportMode.DEFAULT, TransportMode.FORCE_RTP_TCP),
                preferredTransport = if (request.preferRtpTcp) {
                    TransportMode.FORCE_RTP_TCP
                } else {
                    TransportMode.DEFAULT
                },
                supportedDecoders = listOf(
                    DecoderMode.PREFER_HARDWARE,
                    DecoderMode.PLATFORM_DEFAULT,
                    DecoderMode.ALLOW_SOFTWARE,
                ),
                available = option.metadata?.available != false,
            )
            routes[PlaybackRouteLookup(source.routeId, source.streamId)] =
                request.profile.prevalidatedRtspRoute(option.streamName)
            sourcePresentations[source.persistenceScope] = SingleLiveSourcePresentation(
                preferred = index == 0,
                pixels = option.metadata?.let { metadata ->
                    val width = metadata.width ?: return@let null
                    val height = metadata.height ?: return@let null
                    width.toLong() * height.toLong()
                },
            )
            source
        }
        check(sources.isNotEmpty()) { "No configured live stream is available" }
        val currentAccess = CurrentPlaybackAccess(
            guard = guard,
            cameraAuthorization = PlaybackCameraAuthorization.AUTHORIZED,
            authorizedSources = sources,
            authorizedSnapshotRoutes = emptyList(),
        )
        return MaterializedRequest(
            context = PlaybackCompatibilityContext(
                identity = identity,
                knownSources = sources,
                accessGuard = guard,
                resourcePolicy = resourcePolicy,
            ),
            routeSnapshot = TrustedRtspRouteSnapshot(
                currentAccess = currentAccess,
                routes = routes,
            ),
            explanationDescriptor = SingleLiveExplanationDescriptor(
                audioRequested = !videoOnly,
                rtpTcpPreferred = request.preferRtpTcp,
                sources = sourcePresentations,
            ),
        )
    }

    private data class PreparedRequest(
        val revocationEpoch: Long,
        val profile: ConnectionProfile,
        val user: FrigateUserProfile,
        val frigateVersion: String,
        val camera: Camera,
        val preferredStreamName: String,
        val device: DeviceDiagnostics,
        val preferRtpTcp: Boolean,
        val useSavedStrategyFirst: Boolean,
        val purpose: PlaybackPurpose,
    )

    private data class MaterializedRequest(
        val context: PlaybackCompatibilityContext,
        val routeSnapshot: TrustedRtspRouteSnapshot,
        val explanationDescriptor: SingleLiveExplanationDescriptor,
    )

    private companion object {
        const val MAX_PREPARED_REQUESTS = 16
        const val MAX_LIVE_SOURCES = 32
        const val SINGLE_LIVE_COMPATIBILITY_REVISION = 2
    }
}

internal class SingleLivePlaybackSession(
    val snapshots: StateFlow<PlaybackCompatibilitySessionSnapshot>,
    private val explanationDescriptor: SingleLiveExplanationDescriptor,
    private val beginAction: suspend () -> PlaybackCompatibilitySessionSnapshot,
    private val revokeAction: suspend () -> PlaybackCompatibilitySessionSnapshot,
    private val closeAction: suspend () -> Unit,
) {
    private val closeMutex = Mutex()
    private var begun = false
    private var closed = false

    suspend fun begin(): PlaybackCompatibilitySessionSnapshot {
        check(!closed) { "Live playback session is closed" }
        check(!begun) { "Live playback session already began" }
        begun = true
        return beginAction()
    }

    fun downgradeExplanation(snapshot: PlaybackCompatibilitySessionSnapshot): String? {
        val candidate = when (val state = snapshot.state) {
            is app.opah.tv.playback.compatibility.PlaybackCompatibilityState.Verified ->
                state.strategyCandidate()
            is app.opah.tv.playback.compatibility.PlaybackCompatibilityState.UnpersistedLive ->
                state.strategy.candidate
            is app.opah.tv.playback.compatibility.PlaybackCompatibilityState.Finished ->
                when (val result = state.result) {
                    is app.opah.tv.playback.compatibility.PlaybackResult.VerifiedLive ->
                        result.strategy.candidate
                    is app.opah.tv.playback.compatibility.PlaybackResult.UnpersistedLive ->
                        result.candidate
                    else -> null
                }
            else -> null
        }
        return candidate?.let { singleLiveDowngradeExplanation(it, explanationDescriptor) }
    }

    suspend fun revokeAccess() = withContext(NonCancellable) {
        revokeAction()
        close()
    }

    suspend fun close() = withContext(NonCancellable) {
        closeMutex.withLock {
            if (closed) return@withLock
            closed = true
            closeAction()
        }
    }
}

private fun app.opah.tv.playback.compatibility.PlaybackCompatibilityState.Verified
    .strategyCandidate(): PlaybackCandidate = result.strategy.candidate

internal data class SingleLiveExplanationDescriptor(
    val audioRequested: Boolean,
    val rtpTcpPreferred: Boolean,
    val sources: Map<
        app.opah.tv.playback.compatibility.CompatibilityIdentityKey,
        SingleLiveSourcePresentation,
    >,
)

internal data class SingleLiveSourcePresentation(
    val preferred: Boolean,
    val pixels: Long?,
)

internal fun singleLiveDowngradeExplanation(
    candidate: PlaybackCandidate,
    descriptor: SingleLiveExplanationDescriptor,
): String? {
    // A remembered strategy should normally be quiet after it has already been accepted and saved.
    if (candidate.origin == CandidateOrigin.KNOWN_GOOD) return null
    if (
        descriptor.audioRequested &&
        candidate.audioMode == AudioMode.VIDEO_ONLY &&
        candidate.media.audioCodec != null
    ) {
        return "Playing without audio because this TV could not use the camera audio"
    }
    val selectedSource = descriptor.sources[candidate.sourceScope]
    if (selectedSource?.preferred == false) {
        val preferredPixels = descriptor.sources.values.firstOrNull { it.preferred }?.pixels
        return if (
            selectedSource.pixels != null &&
            preferredPixels != null &&
            selectedSource.pixels < preferredPixels
        ) {
            "Using the lower-bandwidth stream on this TV"
        } else {
            "Using another camera stream for more reliable playback"
        }
    }
    if (!descriptor.rtpTcpPreferred && candidate.transportMode == TransportMode.FORCE_RTP_TCP) {
        return "Using RTP over TCP for a more stable connection"
    }
    if (candidate.decoderMode == DecoderMode.ALLOW_SOFTWARE) {
        return "Using a compatible decoder on this TV"
    }
    return null
}

internal class ActiveSingleLiveRouteSnapshotSource : TrustedRtspRouteSnapshotSource {
    private val active = AtomicReference<ActiveRouteSnapshot?>(null)

    fun activate(snapshot: TrustedRtspRouteSnapshot): RouteSnapshotActivation {
        val activation = RouteSnapshotActivation(UUID.randomUUID())
        active.set(ActiveRouteSnapshot(activation, snapshot))
        return activation
    }

    fun deactivate(activation: RouteSnapshotActivation) {
        while (true) {
            val observed = active.get() ?: return
            if (observed.activation != activation) return
            if (active.compareAndSet(observed, null)) return
        }
    }

    fun revokeAll() {
        active.set(null)
    }

    override suspend fun freshSnapshot(): TrustedRtspRouteSnapshotResult =
        active.get()?.let { TrustedRtspRouteSnapshotResult.Available(it.snapshot) }
            ?: TrustedRtspRouteSnapshotResult.AuthenticationRequired

    private data class ActiveRouteSnapshot(
        val activation: RouteSnapshotActivation,
        val snapshot: TrustedRtspRouteSnapshot,
    )
}

internal data class RouteSnapshotActivation(val id: UUID)

private fun ConnectionProfile.prevalidatedRtspRoute(streamName: String): PrevalidatedRtspUri {
    require(streamName.isNotBlank())
    val apiHost = URI(apiBaseUrl).host ?: error("Frigate URL is invalid")
    val host = rtspHostOverride ?: apiHost
    val route = URI("rtsp", null, host, rtspPort, "/$streamName", null, null)
    return PrevalidatedRtspUri.fromTrustedAdapter(Uri.parse(route.toASCIIString()))
}

private fun DeviceDiagnostics.identityComponents(): Map<String, String> = mapOf(
    "manufacturer" to manufacturer,
    "model" to model,
    "device" to device,
    "android_release" to androidRelease,
    "api_level" to apiLevel.toString(),
    "codec_fingerprint" to codecs
        .sortedBy { it.mimeType }
        .joinToString("\u001e") { codec ->
            listOf(
                codec.mimeType,
                codec.supported.toString(),
                codec.hasHardwareDecoder.toString(),
                codec.decoders.size.toString(),
            ).joinToString("\u001f")
        },
)

private fun LiveStreamOption.configurationIdentity(): String = listOf(
    streamName,
    metadata?.available?.toString().orEmpty(),
    metadata?.videoCodec?.name.orEmpty(),
    metadata?.audioCodec?.name.orEmpty(),
    metadata?.width?.toString().orEmpty(),
    metadata?.height?.toString().orEmpty(),
).joinToString("\u001f")

private fun LiveStreamOption.toPlaybackMetadata(): PlaybackMediaMetadata = PlaybackMediaMetadata(
    videoCodec = when (metadata?.videoCodec) {
        DiscoveryVideoCodec.AVC -> VideoCodec.AVC
        DiscoveryVideoCodec.HEVC -> VideoCodec.HEVC
        DiscoveryVideoCodec.UNKNOWN,
        null,
        -> VideoCodec.UNKNOWN
    },
    audioCodec = when (metadata?.audioCodec) {
        DiscoveryAudioCodec.AAC -> AudioCodec.AAC
        DiscoveryAudioCodec.PCMA -> AudioCodec.PCMA
        DiscoveryAudioCodec.PCMU -> AudioCodec.PCMU
        DiscoveryAudioCodec.OPUS -> AudioCodec.OPUS
        DiscoveryAudioCodec.UNKNOWN -> AudioCodec.UNKNOWN
        DiscoveryAudioCodec.NONE,
        null,
        -> null
    },
    width = metadata?.width,
    height = metadata?.height,
)

private val REQUEST_ID_PATTERN = Regex("live-[0-9a-fA-F-]{36}")
