package app.opah.tv.playback.compatibility

import java.util.ArrayList
import java.util.Collections

const val PLAYBACK_COMPATIBILITY_MODEL_VERSION: Int = 4
const val MAX_PERSISTENCE_WRITE_HORIZON_MILLIS: Long = 30_000

private const val MAX_DISCOVERED_PLAYBACK_SOURCES = 256

private const val MAX_OPAQUE_ID_LENGTH = 160
private val OPAQUE_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}")

private fun requireOpaqueId(value: String, label: String) {
    require(value.length <= MAX_OPAQUE_ID_LENGTH) { "$label is too long" }
    require(OPAQUE_ID_PATTERN.matches(value)) {
        "$label must be an opaque identifier, not a URI or path"
    }
}

private fun <T> frozenList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

/**
 * A key resolved by a trusted adapter to a prevalidated route. It is deliberately not a URI.
 */
@JvmInline
value class PlaybackRouteId(val value: String) {
    init {
        requireOpaqueId(value, "Playback route ID")
    }
}

/** A Frigate stream key which has already passed the integration boundary's validation. */
@JvmInline
value class PlaybackStreamId(val value: String) {
    init {
        requireOpaqueId(value, "Playback stream ID")
    }
}

/** A separately resolved snapshot route. Snapshot routes never enter the live candidate ladder. */
@JvmInline
value class SnapshotRouteId(val value: String) {
    init {
        requireOpaqueId(value, "Snapshot route ID")
    }
}

/** Versioned nonreversible identity material supplied by the integration layer. */
@JvmInline
value class CompatibilityIdentityKey private constructor(val value: String) {
    companion object {
        private const val PREFIX = "h1:"
        private val persistedPattern = Regex("h1:[0-9a-f]{64}")
        private val hex = "0123456789abcdef".toCharArray()

        /** Accepts only the 32-byte output of the caller's keyed HMAC-SHA-256 operation. */
        internal fun fromHmacSha256(digest: ByteArray): CompatibilityIdentityKey {
            require(digest.size == 32) { "Compatibility identity HMAC must contain 32 bytes" }
            val encoded = CharArray(PREFIX.length + digest.size * 2)
            PREFIX.toCharArray().copyInto(encoded)
            digest.forEachIndexed { index, byte ->
                val unsigned = byte.toInt() and 0xff
                encoded[PREFIX.length + index * 2] = hex[unsigned ushr 4]
                encoded[PREFIX.length + index * 2 + 1] = hex[unsigned and 0x0f]
            }
            return CompatibilityIdentityKey(encoded.concatToString())
        }

        /** Rehydrates only the exact versioned digest representation accepted by this model. */
        fun fromPersisted(value: String): CompatibilityIdentityKey {
            require(persistedPattern.matches(value)) {
                "Persisted compatibility identity key is malformed or unsupported"
            }
            return CompatibilityIdentityKey(value)
        }
    }
}

/**
 * Snapshot of the central authorization and local privacy decisions used to assemble a plan.
 * Revisions are process-monotonic. Content-opening commands must be revalidated against current
 * access immediately before route resolution; matching this token alone never grants access.
 */
data class PlaybackAccessGuard(
    val profileScope: CompatibilityIdentityKey,
    val cameraScope: CompatibilityIdentityKey,
    val authorizationScope: CompatibilityIdentityKey,
    val authorizationRevision: Long,
    val privacyRevision: Long,
) {
    init {
        require(authorizationRevision >= 0) { "Authorization revision must not be negative" }
        require(privacyRevision >= 0) { "Privacy revision must not be negative" }
    }
}

@JvmInline
value class PlaybackSessionId(val value: String) {
    init {
        requireOpaqueId(value, "Playback session ID")
    }
}

enum class PlaybackPurpose {
    SINGLE_LIVE,
    PICTURE_IN_PICTURE,
    CAMERA_GROUP,
    MONITOR,
    COMPATIBILITY_TEST,
}

enum class PlaybackResourceClass {
    PRIMARY,
    SECONDARY,
    MULTI_VIEW,
    MONITOR_PROMOTION,
    DIAGNOSTIC,
}

data class PlaybackResourcePolicy(
    val resourceClass: PlaybackResourceClass,
    val audioPermitted: Boolean,
    val softwareDecoderPermitted: Boolean,
    val maximumCandidatePixels: Long? = null,
) {
    init {
        require(maximumCandidatePixels == null || maximumCandidatePixels > 0) {
            "Maximum candidate pixels must be positive"
        }
    }

    companion object {
        fun defaultFor(purpose: PlaybackPurpose): PlaybackResourcePolicy = when (purpose) {
            PlaybackPurpose.SINGLE_LIVE -> PlaybackResourcePolicy(
                PlaybackResourceClass.PRIMARY,
                audioPermitted = true,
                softwareDecoderPermitted = true,
            )
            PlaybackPurpose.PICTURE_IN_PICTURE -> PlaybackResourcePolicy(
                PlaybackResourceClass.SECONDARY,
                audioPermitted = false,
                softwareDecoderPermitted = false,
            )
            PlaybackPurpose.CAMERA_GROUP -> PlaybackResourcePolicy(
                PlaybackResourceClass.MULTI_VIEW,
                audioPermitted = false,
                softwareDecoderPermitted = false,
            )
            PlaybackPurpose.MONITOR -> PlaybackResourcePolicy(
                PlaybackResourceClass.MONITOR_PROMOTION,
                audioPermitted = false,
                softwareDecoderPermitted = false,
            )
            PlaybackPurpose.COMPATIBILITY_TEST -> PlaybackResourcePolicy(
                PlaybackResourceClass.DIAGNOSTIC,
                audioPermitted = true,
                softwareDecoderPermitted = true,
            )
        }
    }
}

/**
 * Exact identity for a saved strategy. Any relevant change produces a different identity and
 * prevents stale strategy promotion.
 */
data class PlaybackCompatibilityIdentity(
    val profileScope: CompatibilityIdentityKey,
    val cameraScope: CompatibilityIdentityKey,
    val authorizationScope: CompatibilityIdentityKey,
    val deviceScope: CompatibilityIdentityKey,
    val osApiLevel: Int,
    val appCompatibilityRevision: Int,
    val serverApiGeneration: CompatibilityIdentityKey,
    val streamConfigurationRevision: CompatibilityIdentityKey,
    val purpose: PlaybackPurpose,
    val resourceClass: PlaybackResourceClass = PlaybackResourcePolicy.defaultFor(purpose).resourceClass,
    val modelVersion: Int = PLAYBACK_COMPATIBILITY_MODEL_VERSION,
) {
    init {
        require(modelVersion == PLAYBACK_COMPATIBILITY_MODEL_VERSION) {
            "Unsupported playback identity model version"
        }
        require(osApiLevel > 0) { "OS API level must be positive" }
        require(appCompatibilityRevision > 0) { "App compatibility revision must be positive" }
    }
}

enum class VideoCodec {
    AVC,
    HEVC,
    MPEG4,
    VP8,
    VP9,
    AV1,
    UNKNOWN,
}

enum class AudioCodec {
    AAC,
    PCMA,
    PCMU,
    OPUS,
    UNKNOWN,
}

data class PlaybackMediaMetadata(
    val videoCodec: VideoCodec,
    val audioCodec: AudioCodec? = null,
    val width: Int? = null,
    val height: Int? = null,
) {
    init {
        require(width == null || width > 0) { "Video width must be positive" }
        require(height == null || height > 0) { "Video height must be positive" }
    }
}

enum class AudioMode {
    WITH_AUDIO,
    VIDEO_ONLY,
}

enum class TransportMode {
    DEFAULT,
    FORCE_RTP_TCP,
}

enum class DecoderMode {
    PLATFORM_DEFAULT,
    PREFER_HARDWARE,
    ALLOW_SOFTWARE,
}

enum class DecoderImplementationEvidence {
    HARDWARE,
    SOFTWARE,
    UNKNOWN,
}

@JvmInline
value class SanitizedDecoderName private constructor(val value: String) {
    companion object {
        private val allowedPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")

        fun fromAdapter(value: String): SanitizedDecoderName {
            require(allowedPattern.matches(value)) { "Decoder name is not safely reportable" }
            return SanitizedDecoderName(value)
        }
    }
}

data class PlaybackDecoderEvidence(
    val implementation: DecoderImplementationEvidence,
    val sanitizedName: SanitizedDecoderName? = null,
) {
    init {
        require(implementation != DecoderImplementationEvidence.UNKNOWN || sanitizedName == null) {
            "Unknown decoder evidence cannot carry a decoder name"
        }
    }

    companion object {
        val UNKNOWN = PlaybackDecoderEvidence(DecoderImplementationEvidence.UNKNOWN)
    }
}

/**
 * A source returned by trusted discovery. The compatibility domain retains only opaque IDs and
 * media metadata; URI construction and host validation remain outside this package.
 */
class KnownPlaybackSource(
    val routeId: PlaybackRouteId,
    val streamId: PlaybackStreamId,
    val persistenceScope: CompatibilityIdentityKey,
    val media: PlaybackMediaMetadata,
    val preferenceRank: Int,
    supportedTransports: List<TransportMode> = listOf(TransportMode.DEFAULT),
    preferredTransport: TransportMode? = null,
    supportedDecoders: List<DecoderMode> = listOf(DecoderMode.PLATFORM_DEFAULT),
    val available: Boolean = true,
) {
    val supportedTransports: List<TransportMode> = frozenList(supportedTransports)
    val preferredTransport: TransportMode = preferredTransport ?: this.supportedTransports.firstOrNull()
        ?: TransportMode.DEFAULT
    val supportedDecoders: List<DecoderMode> = frozenList(supportedDecoders)

    init {
        require(preferenceRank >= 0) { "Source preference rank must not be negative" }
        require(this.supportedTransports.isNotEmpty()) { "A source must have a transport" }
        require(this.supportedTransports.distinct().size == this.supportedTransports.size) {
            "Source transports must be unique"
        }
        require(this.preferredTransport in this.supportedTransports) {
            "Preferred transport must be supported by the source"
        }
        require(this.supportedDecoders.isNotEmpty()) { "A source must have a decoder mode" }
        require(this.supportedDecoders.distinct().size == this.supportedDecoders.size) {
            "Source decoder modes must be unique"
        }
    }

    internal fun frozenCopy(): KnownPlaybackSource = KnownPlaybackSource(
        routeId = routeId,
        streamId = streamId,
        persistenceScope = persistenceScope,
        media = media,
        preferenceRank = preferenceRank,
        supportedTransports = supportedTransports,
        preferredTransport = preferredTransport,
        supportedDecoders = supportedDecoders,
        available = available,
    )
}

data class PlaybackAttemptBudget(
    val maxCandidates: Int = 12,
    val maxAttempts: Int = 6,
    /** Bounded reconnect cycles after an already verified live attempt fails. */
    val maxRecoveryCycles: Int = 2,
    val maxTotalDurationMillis: Long = 30_000,
    val recoveryBackoffMillis: Long = 500,
    val firstFrameTimeoutMillis: Long = 8_000,
    val stableDwellMillis: Long = 2_000,
    val stableProgressFreshnessMillis: Long = minOf(750, stableDwellMillis),
    val releaseTimeoutMillis: Long = 2_000,
    val persistenceTimeoutMillis: Long = 3_000,
    val persistenceResolutionTimeoutMillis: Long = 5_000,
) {
    init {
        require(maxCandidates in 8..32) {
            "Candidate budget must be between 8 and 32 so critical fallback dimensions fit"
        }
        require(maxAttempts in 1..maxCandidates) {
            "Attempt budget must be positive and no greater than the candidate budget"
        }
        require(maxRecoveryCycles in 0..4) {
            "Recovery-cycle budget must be between zero and four"
        }
        require(maxTotalDurationMillis > 0) { "Total time budget must be positive" }
        require(recoveryBackoffMillis in 1..maxTotalDurationMillis) {
            "Recovery backoff must fit within the total attempt duration"
        }
        require(firstFrameTimeoutMillis > 0) { "First-frame timeout must be positive" }
        require(stableDwellMillis > 0) { "Stable dwell must be positive" }
        require(stableProgressFreshnessMillis in 1..stableDwellMillis) {
            "Stable progress freshness must fit within the stable dwell"
        }
        require(releaseTimeoutMillis > 0) { "Release timeout must be positive" }
        require(persistenceTimeoutMillis > 0) { "Persistence timeout must be positive" }
        require(persistenceTimeoutMillis <= MAX_PERSISTENCE_WRITE_HORIZON_MILLIS) {
            "Persistence timeout exceeds the bounded write horizon"
        }
        require(persistenceResolutionTimeoutMillis > 0) {
            "Persistence resolution timeout must be positive"
        }
    }
}

class PlaybackCompatibilityContext(
    val identity: PlaybackCompatibilityIdentity,
    knownSources: List<KnownPlaybackSource>,
    val accessGuard: PlaybackAccessGuard,
    val snapshotRouteId: SnapshotRouteId? = null,
    val budget: PlaybackAttemptBudget = PlaybackAttemptBudget(),
    val resourcePolicy: PlaybackResourcePolicy = PlaybackResourcePolicy.defaultFor(identity.purpose),
    val modelVersion: Int = PLAYBACK_COMPATIBILITY_MODEL_VERSION,
) {
    init {
        require(knownSources.size <= MAX_DISCOVERED_PLAYBACK_SOURCES) {
            "Playback discovery exceeds its bounded source limit"
        }
    }

    val knownSources: List<KnownPlaybackSource> = frozenList(
        knownSources.map(KnownPlaybackSource::frozenCopy),
    )

    init {
        require(modelVersion == PLAYBACK_COMPATIBILITY_MODEL_VERSION) {
            "Unsupported playback context model version"
        }
        require(identity.modelVersion == PLAYBACK_COMPATIBILITY_MODEL_VERSION) {
            "Playback identity and context model versions must match"
        }
        require(identity.resourceClass == resourcePolicy.resourceClass) {
            "Playback identity and resource policy classes must match"
        }
        require(accessGuard.profileScope == identity.profileScope) {
            "Playback access profile must match the compatibility identity"
        }
        require(accessGuard.cameraScope == identity.cameraScope) {
            "Playback access camera must match the compatibility identity"
        }
        require(accessGuard.authorizationScope == identity.authorizationScope) {
            "Playback access authorization must match the compatibility identity"
        }
        val identities = this.knownSources.map { it.routeId to it.streamId }
        require(identities.distinct().size == identities.size) {
            "Known source route and stream pairs must be unique"
        }
        val persistenceScopes = this.knownSources.map(KnownPlaybackSource::persistenceScope)
        require(persistenceScopes.distinct().size == persistenceScopes.size) {
            "Known source persistence scopes must be unique"
        }
    }
}

enum class PlaybackCameraAuthorization {
    AUTHORIZED,
    NOT_AUTHORIZED,
}

/** Fresh, centralized access state supplied immediately before a content-opening command runs. */
class CurrentPlaybackAccess(
    val guard: PlaybackAccessGuard,
    val cameraAuthorization: PlaybackCameraAuthorization,
    authorizedSources: List<KnownPlaybackSource>,
    authorizedSnapshotRoutes: List<SnapshotRouteId>,
) {
    init {
        require(authorizedSources.size <= MAX_DISCOVERED_PLAYBACK_SOURCES)
        require(authorizedSnapshotRoutes.size <= MAX_DISCOVERED_PLAYBACK_SOURCES)
    }

    val authorizedSources: List<KnownPlaybackSource> = frozenList(
        authorizedSources.map(KnownPlaybackSource::frozenCopy),
    )
    val authorizedSnapshotRoutes: List<SnapshotRouteId> = frozenList(authorizedSnapshotRoutes)

    init {
        require(this.authorizedSources.map { it.routeId to it.streamId }.distinct().size ==
            this.authorizedSources.size
        ) { "Current playback source authorizations must be unique" }
        require(this.authorizedSnapshotRoutes.distinct().size == this.authorizedSnapshotRoutes.size) {
            "Current snapshot route authorizations must be unique"
        }
        require(
            cameraAuthorization != PlaybackCameraAuthorization.NOT_AUTHORIZED ||
                (this.authorizedSources.isEmpty() && this.authorizedSnapshotRoutes.isEmpty()),
        ) { "A denied camera cannot expose live or snapshot route evidence" }
    }
}

enum class PlaybackExecutionAuthorization {
    AUTHORIZED,
    /** No current authenticated Frigate session exists; sign-in is required. */
    AUTHENTICATION_REQUIRED,
    /** The plan's authorization/privacy revision is no longer current. */
    STALE_ACCESS,
    /** Current route capability evidence is unavailable or internally inconsistent. */
    RESOLUTION_UNAVAILABLE,
    /** The current authenticated user is not authorized for the live camera. */
    SOURCE_NOT_AUTHORIZED,
    /** The current authenticated user is not authorized for the snapshot camera. */
    SNAPSHOT_NOT_AUTHORIZED,
    /** Current local privacy policy no longer permits presentation. */
    PRIVACY_REVOKED,
}

/** Typed access loss accepted by an already active compatibility session. */
enum class PlaybackAccessRevocation {
    SESSION_EXPIRED,
    STALE_ACCESS,
    SOURCE_NOT_AUTHORIZED,
    SNAPSHOT_NOT_AUTHORIZED,
    PRIVACY_REVOKED;

    val authorization: PlaybackExecutionAuthorization
        get() = when (this) {
            SESSION_EXPIRED -> PlaybackExecutionAuthorization.AUTHENTICATION_REQUIRED
            STALE_ACCESS -> PlaybackExecutionAuthorization.STALE_ACCESS
            SOURCE_NOT_AUTHORIZED -> PlaybackExecutionAuthorization.SOURCE_NOT_AUTHORIZED
            SNAPSHOT_NOT_AUTHORIZED -> PlaybackExecutionAuthorization.SNAPSHOT_NOT_AUTHORIZED
            PRIVACY_REVOKED -> PlaybackExecutionAuthorization.PRIVACY_REVOKED
        }
}

/**
 * Fail-closed execution gate. The route resolver/player must call this with freshly obtained
 * central access state before resolving a route ID into a URI or opening any media.
 */
class PlaybackExecutionAuthorizer {
    fun authorize(
        command: PlaybackContentCommand,
        currentAccess: CurrentPlaybackAccess,
    ): PlaybackExecutionAuthorization {
        if (command.accessGuard != currentAccess.guard) {
            return PlaybackExecutionAuthorization.STALE_ACCESS
        }
        if (currentAccess.cameraAuthorization == PlaybackCameraAuthorization.NOT_AUTHORIZED) {
            return when (command) {
                is PlaybackCommand.StartAttempt ->
                    PlaybackExecutionAuthorization.SOURCE_NOT_AUTHORIZED
                is PlaybackCommand.RenderSnapshot ->
                    PlaybackExecutionAuthorization.SNAPSHOT_NOT_AUTHORIZED
            }
        }
        return when (command) {
            is PlaybackCommand.StartAttempt -> {
                val candidate = command.attempt.candidate
                val source = currentAccess.authorizedSources.firstOrNull {
                    it.routeId == candidate.routeId && it.streamId == candidate.streamId
                }
                when {
                    source == null -> PlaybackExecutionAuthorization.RESOLUTION_UNAVAILABLE
                    !source.available -> PlaybackExecutionAuthorization.RESOLUTION_UNAVAILABLE
                    source.persistenceScope != candidate.sourceScope ||
                        source.media != candidate.media ||
                        candidate.transportMode !in source.supportedTransports ||
                        candidate.decoderMode !in source.supportedDecoders ->
                        PlaybackExecutionAuthorization.STALE_ACCESS
                    else -> PlaybackExecutionAuthorization.AUTHORIZED
                }
            }

            is PlaybackCommand.RenderSnapshot -> {
                if (command.routeId in currentAccess.authorizedSnapshotRoutes) {
                    PlaybackExecutionAuthorization.AUTHORIZED
                } else {
                    PlaybackExecutionAuthorization.RESOLUTION_UNAVAILABLE
                }
            }
        }
    }
}

enum class CandidateOrigin {
    KNOWN_GOOD,
    DISCOVERED_BASELINE,
    SAFE_FALLBACK,
}

data class PlaybackCandidate(
    val routeId: PlaybackRouteId,
    val streamId: PlaybackStreamId,
    val sourceScope: CompatibilityIdentityKey,
    val media: PlaybackMediaMetadata,
    val audioMode: AudioMode,
    val transportMode: TransportMode,
    val decoderMode: DecoderMode,
    val origin: CandidateOrigin,
    val knownGoodRecordGeneration: Long? = null,
) {
    init {
        require(audioMode != AudioMode.WITH_AUDIO || media.audioCodec != null) {
            "A candidate cannot enable absent audio"
        }
        require((origin == CandidateOrigin.KNOWN_GOOD) == (knownGoodRecordGeneration != null)) {
            "Only a correlated known-good candidate may carry a saved proof"
        }
        require(knownGoodRecordGeneration == null || knownGoodRecordGeneration > 0) {
            "Known-good record generation must be positive"
        }
    }

    fun key(): PlaybackCandidateKey = PlaybackCandidateKey(
        routeId = routeId,
        streamId = streamId,
        audioMode = audioMode,
        transportMode = transportMode,
        decoderMode = decoderMode,
    )
}

data class PlaybackCandidateKey(
    val routeId: PlaybackRouteId,
    val streamId: PlaybackStreamId,
    val audioMode: AudioMode,
    val transportMode: TransportMode,
    val decoderMode: DecoderMode,
)

data class PlaybackSuccessEvidence(
    val firstFrameLatencyMillis: Long,
    val stablePlaybackDurationMillis: Long,
    val videoProgressEventCount: Int,
    val audioProgressEventCount: Int,
    val decoderEvidence: PlaybackDecoderEvidence = PlaybackDecoderEvidence.UNKNOWN,
) {
    init {
        require(firstFrameLatencyMillis >= 0) { "First-frame latency must not be negative" }
        require(stablePlaybackDurationMillis > 0) { "Stable playback duration must be positive" }
        require(videoProgressEventCount > 0) { "Verified playback requires video progress" }
        require(audioProgressEventCount >= 0) { "Audio progress count must not be negative" }
    }
}

/** Runtime proof. Its route IDs are never passed to the persistence store. */
data class VerifiedPlaybackStrategy(
    val identity: PlaybackCompatibilityIdentity,
    val candidate: PlaybackCandidate,
    val evidence: PlaybackSuccessEvidence,
    val modelVersion: Int = PLAYBACK_COMPATIBILITY_MODEL_VERSION,
) {
    init {
        require(modelVersion == PLAYBACK_COMPATIBILITY_MODEL_VERSION)
        require(candidate.audioMode != AudioMode.WITH_AUDIO || evidence.audioProgressEventCount > 0) {
            "An audio-enabled strategy requires positive audio progress"
        }
    }
}

/** Persistence-safe dimensions; raw route and stream identifiers are deliberately absent. */
data class PersistablePlaybackStrategy(
    val identity: PlaybackCompatibilityIdentity,
    val sourceScope: CompatibilityIdentityKey,
    val media: PlaybackMediaMetadata,
    val audioMode: AudioMode,
    val transportMode: TransportMode,
    val decoderMode: DecoderMode,
    val evidence: PlaybackSuccessEvidence,
    val modelVersion: Int = PLAYBACK_COMPATIBILITY_MODEL_VERSION,
) {
    init {
        require(modelVersion == PLAYBACK_COMPATIBILITY_MODEL_VERSION)
        require(identity.modelVersion == modelVersion)
        require(audioMode != AudioMode.WITH_AUDIO || media.audioCodec != null)
        require(audioMode != AudioMode.WITH_AUDIO || evidence.audioProgressEventCount > 0)
    }

    companion object {
        fun fromVerified(strategy: VerifiedPlaybackStrategy): PersistablePlaybackStrategy =
            PersistablePlaybackStrategy(
                identity = strategy.identity,
                sourceScope = strategy.candidate.sourceScope,
                media = strategy.candidate.media,
                audioMode = strategy.candidate.audioMode,
                transportMode = strategy.candidate.transportMode,
                decoderMode = strategy.candidate.decoderMode,
                evidence = strategy.evidence,
            )
    }
}

data class StoredPlaybackStrategy(
    val record: PersistablePlaybackStrategy,
    val recordGeneration: Long,
) {
    init {
        require(recordGeneration > 0) { "Stored strategy generation must be positive" }
    }
}

data class VerifiedStrategyWrite(
    val attemptId: PlaybackAttemptId,
    val record: PersistablePlaybackStrategy,
    val issuedAtElapsedMillis: Long,
    /** Exclusive monotonic deadline enforced before the store admits the final write transaction. */
    val notAfterElapsedMillis: Long,
) {
    init {
        require(issuedAtElapsedMillis >= 0) { "Strategy write issue time must not be negative" }
        require(notAfterElapsedMillis >= 0) { "Strategy write deadline must not be negative" }
        require(notAfterElapsedMillis >= issuedAtElapsedMillis) {
            "Strategy write deadline cannot precede its issue time"
        }
        require(
            notAfterElapsedMillis - issuedAtElapsedMillis <= MAX_PERSISTENCE_WRITE_HORIZON_MILLIS,
        ) { "Strategy write deadline exceeds the bounded persistence horizon" }
    }
}

sealed interface StrategyWriteOutcome {
    /** Phase one completed; the row remains invisible until the reducer finalizes this decision. */
    data class DecisionRequired(val recordGeneration: Long?) : StrategyWriteOutcome {
        init {
            require(recordGeneration == null || recordGeneration > 0) {
                "Pending strategy generation must be positive"
            }
        }
    }

    /** Exact idempotent replay after this attempt already reached a terminal decision. */
    data class FinalizedReplay(
        val outcome: StrategyPersistenceFinalizationOutcome,
    ) : StrategyWriteOutcome {
        init {
            require(outcome != StrategyPersistenceFinalizationOutcome.ResolutionUnavailable)
        }
    }

    data object WriteFailed : StrategyWriteOutcome

    data object TimedOutBeforeCommit : StrategyWriteOutcome
}

sealed interface StrategyWriteCancellationOutcome {
    /** Cancellation observed phase one and now requires a reducer-owned terminal decision. */
    data class DecisionRequired(val recordGeneration: Long?) : StrategyWriteCancellationOutcome {
        init {
            require(recordGeneration == null || recordGeneration > 0) {
                "Pending strategy generation must be positive"
            }
        }
    }

    /** Exact idempotent replay after this attempt already reached a terminal decision. */
    data class FinalizedReplay(
        val outcome: StrategyPersistenceFinalizationOutcome,
    ) : StrategyWriteCancellationOutcome {
        init {
            require(outcome != StrategyPersistenceFinalizationOutcome.ResolutionUnavailable)
        }
    }

    /** Corrupt or otherwise ambiguous durable state; callers must wait for fail-closed quarantine. */
    data object ResolutionUnavailable : StrategyWriteCancellationOutcome
}

sealed interface StrategyPersistenceFinalizationOutcome {
    data class Committed(val recordGeneration: Long) : StrategyPersistenceFinalizationOutcome {
        init {
            require(recordGeneration > 0) { "Committed strategy generation must be positive" }
        }
    }

    data object Cancelled : StrategyPersistenceFinalizationOutcome

    data object ResolutionUnavailable : StrategyPersistenceFinalizationOutcome
}

/** Exact non-secret context required to prepare interruption resolution before its disposition. */
data class StrategyPersistenceDecisionContext(
    val attemptId: PlaybackAttemptId,
    val identity: PlaybackCompatibilityIdentity,
    val sourceScope: CompatibilityIdentityKey,
    val audioMode: AudioMode,
    val transportMode: TransportMode,
    val decoderMode: DecoderMode,
    val fallbackKnownGoodRecordGeneration: Long? = null,
) {
    init {
        require(fallbackKnownGoodRecordGeneration == null || fallbackKnownGoodRecordGeneration > 0) {
            "Fallback known-good generation must be positive"
        }
    }
}

enum class StrategyPersistenceFinalizationDisposition {
    ACCEPT_VERIFIED_COMMIT,
    PRESERVE_VERIFIED_COMMIT,
    RECORD_IMPLICATED_FAILURE,
}

/**
 * Reducer-owned phase-two decision. The store applies commit visibility and any implicated failure
 * evidence in one transaction, so neither may survive without the other after process death.
 */
data class StrategyPersistenceFinalization(
    val context: StrategyPersistenceDecisionContext,
    val expectedRecordGeneration: Long?,
    val disposition: StrategyPersistenceFinalizationDisposition,
    val category: FailureCategory? = null,
) {
    init {
        require(expectedRecordGeneration == null || expectedRecordGeneration > 0) {
            "Expected strategy generation must be positive"
        }
        require(
            disposition != StrategyPersistenceFinalizationDisposition.ACCEPT_VERIFIED_COMMIT ||
                expectedRecordGeneration != null,
        ) { "Accepting a verified commit requires its exact pending generation" }
        require(
            disposition != StrategyPersistenceFinalizationDisposition.RECORD_IMPLICATED_FAILURE ||
                category?.implicatesSavedStrategy == true,
        ) { "Recording persistence failure requires implicated evidence" }
        require(
            disposition == StrategyPersistenceFinalizationDisposition.RECORD_IMPLICATED_FAILURE ||
                category == null,
        ) { "Neutral persistence finalization cannot carry failure evidence" }
    }
}

enum class StrategyFailureRecordOutcome {
    IGNORED,
    RECORDED,
    INVALIDATED,
}

data class ImplicatedStrategyFailure(
    val attemptId: PlaybackAttemptId,
    val identity: PlaybackCompatibilityIdentity,
    val sourceScope: CompatibilityIdentityKey,
    val audioMode: AudioMode,
    val transportMode: TransportMode,
    val decoderMode: DecoderMode,
    val recordGeneration: Long,
    val category: FailureCategory,
) {
    init {
        require(recordGeneration > 0) { "Implicated strategy generation must be positive" }
        require(category.implicatesSavedStrategy) {
            "Only a failure that implicates a saved strategy may cross the persistence boundary"
        }
    }
}

/**
 * Fail-closed cleanup request used only when an interrupted write's authoritative result was not
 * delivered within its bounded resolution interval. The store atomically cancels an unpublished
 * write or invalidates the exact generation correlated to [attemptId].
 */
enum class UnresolvedPersistenceDisposition {
    PRESERVE_VERIFIED_COMMIT,
    INVALIDATE_IMPLICATED_COMMIT,
}

data class UnresolvedStrategyPersistence(
    val attemptId: PlaybackAttemptId,
    val identity: PlaybackCompatibilityIdentity,
    val sourceScope: CompatibilityIdentityKey,
    val audioMode: AudioMode,
    val transportMode: TransportMode,
    val decoderMode: DecoderMode,
    val expectedRecordGeneration: Long? = null,
    val fallbackKnownGoodRecordGeneration: Long? = null,
    val disposition: UnresolvedPersistenceDisposition,
    val category: FailureCategory? = null,
) {
    init {
        require(expectedRecordGeneration == null || expectedRecordGeneration > 0) {
            "Expected unresolved generation must be positive"
        }
        require(fallbackKnownGoodRecordGeneration == null || fallbackKnownGoodRecordGeneration > 0) {
            "Fallback known-good generation must be positive"
        }
        require(
            disposition != UnresolvedPersistenceDisposition.INVALIDATE_IMPLICATED_COMMIT ||
                category?.implicatesSavedStrategy == true,
        ) {
            "Invalidating unresolved persistence requires an implicated strategy failure"
        }
        require(
            disposition != UnresolvedPersistenceDisposition.PRESERVE_VERIFIED_COMMIT ||
                category == null,
        ) {
            "A neutral unresolved-persistence resolution cannot carry failure evidence"
        }
    }
}

interface VerifiedPlaybackStrategyStore {
    suspend fun load(identity: PlaybackCompatibilityIdentity): StoredPlaybackStrategy?

    /**
     * Returns [StrategyWriteOutcome.DecisionRequired] only after phase one was durably admitted
     * before the write's exclusive monotonic deadline. The row remains invisible until [finalize]
     * applies the reducer-owned decision. A timeout or precommit cancellation can never supersede
     * an exact pending generation.
     */
    suspend fun save(write: VerifiedStrategyWrite): StrategyWriteOutcome

    /**
     * Establishes the durable phase-one result for an interrupted write without choosing whether
     * implicated failure evidence is recorded. The reducer owns that final disposition.
     */
    suspend fun cancel(
        context: StrategyPersistenceDecisionContext,
    ): StrategyWriteCancellationOutcome

    /**
     * Applies the reducer's accepted phase-two decision atomically. A committed outcome means the
     * exact strategy is visible and any implicated failure evidence is already durable.
     */
    suspend fun finalize(
        finalization: StrategyPersistenceFinalization,
    ): StrategyPersistenceFinalizationOutcome

    /**
     * Atomically matches identity, globally non-reused generation, source scope, and strategy
     * dimensions before recording or invalidating. A stale or mismatched failure is ignored.
     */
    suspend fun recordImplicatedFailure(failure: ImplicatedStrategyFailure): StrategyFailureRecordOutcome

    /**
     * Resolves a lost interrupted-write callback transactionally. Loads must exclude a write that
     * remains unresolved for this attempt until this operation has cancelled or invalidated it.
     */
    suspend fun quarantineUnresolvedWrite(
        request: UnresolvedStrategyPersistence,
    ): StrategyFailureRecordOutcome

    /**
     * Removes records for [identity] without resetting the database's monotonic generation source.
     * A generation is not reused while this local database survives, including ordinary reset and
     * migrations that preserve it. Reconstructing deleted app data creates a new correlation realm.
     */
    suspend fun reset(identity: PlaybackCompatibilityIdentity)
}

class PlaybackPlan private constructor(
    val identity: PlaybackCompatibilityIdentity,
    candidates: List<PlaybackCandidate>,
    val accessGuard: PlaybackAccessGuard,
    val snapshotRouteId: SnapshotRouteId?,
    val budget: PlaybackAttemptBudget,
    val resourcePolicy: PlaybackResourcePolicy,
    val modelVersion: Int = PLAYBACK_COMPATIBILITY_MODEL_VERSION,
) {
    val candidates: List<PlaybackCandidate> = frozenList(candidates)

    init {
        require(modelVersion == PLAYBACK_COMPATIBILITY_MODEL_VERSION) {
            "Unsupported playback plan model version"
        }
        require(identity.modelVersion == modelVersion) { "Plan and identity model versions must match" }
        require(identity.resourceClass == resourcePolicy.resourceClass) {
            "Plan identity and resource policy classes must match"
        }
        require(accessGuard.profileScope == identity.profileScope)
        require(accessGuard.cameraScope == identity.cameraScope)
        require(accessGuard.authorizationScope == identity.authorizationScope)
        require(this.candidates.size <= budget.maxCandidates) { "Plan exceeds its candidate budget" }
        require(this.candidates.map(PlaybackCandidate::key).distinct().size == this.candidates.size) {
            "Playback plan candidates must be unique"
        }
    }

    companion object {
        internal fun fromTrustedDiscovery(
            identity: PlaybackCompatibilityIdentity,
            candidates: List<PlaybackCandidate>,
            accessGuard: PlaybackAccessGuard,
            snapshotRouteId: SnapshotRouteId?,
            budget: PlaybackAttemptBudget,
            resourcePolicy: PlaybackResourcePolicy,
            authorizedSources: List<KnownPlaybackSource>,
        ): PlaybackPlan {
            candidates.forEach { candidate ->
                val source = authorizedSources.firstOrNull {
                    it.routeId == candidate.routeId && it.streamId == candidate.streamId
                }
                requireNotNull(source) { "Playback candidate is outside trusted discovery" }
                require(candidate.sourceScope == source.persistenceScope) {
                    "Playback candidate persistence scope must match trusted discovery"
                }
                require(candidate.media == source.media) {
                    "Playback candidate media must match trusted discovery"
                }
                require(candidate.transportMode in source.supportedTransports) {
                    "Playback candidate transport is not authorized for its source"
                }
                require(candidate.decoderMode in source.supportedDecoders) {
                    "Playback candidate decoder is not supported for its source"
                }
                require(resourcePolicy.allows(candidate)) {
                    "Playback candidate violates its resource policy"
                }
            }
            return PlaybackPlan(
                identity = identity,
                candidates = candidates,
                accessGuard = accessGuard,
                snapshotRouteId = snapshotRouteId,
                budget = budget,
                resourcePolicy = resourcePolicy,
            )
        }
    }
}

private fun PlaybackResourcePolicy.allows(candidate: PlaybackCandidate): Boolean {
    if (!audioPermitted && candidate.audioMode == AudioMode.WITH_AUDIO) return false
    if (!softwareDecoderPermitted && candidate.decoderMode == DecoderMode.ALLOW_SOFTWARE) return false
    val maximum = maximumCandidatePixels ?: return true
    val width = candidate.media.width ?: return false
    val height = candidate.media.height ?: return false
    return width.toLong() * height.toLong() <= maximum
}

enum class AttemptPhase {
    AWAITING_FIRST_FRAME,
    VERIFYING_STABILITY,
    RELEASING,
    VERIFIED,
}

enum class ProbePhase {
    FIRST_FRAME,
    STABLE_DWELL,
    TOTAL_BUDGET,
}

enum class FailureCategory {
    DNS_OR_ROUTE,
    CONNECTION_REFUSED,
    AUTHENTICATION,
    AUTHORIZATION,
    AUDIO_DECODER,
    AUDIO_RENDERER,
    VIDEO_DECODER,
    VIDEO_RENDERER,
    UNSUPPORTED_VIDEO_CODEC,
    UNSUPPORTED_VIDEO_SIZE,
    MEDIA_SOURCE_OR_SDP,
    SOURCE_TIMEOUT,
    RTSP_TIMEOUT,
    SOURCE_UNAVAILABLE,
    FIRST_FRAME_TIMEOUT,
    REPEATED_BUFFERING_OR_STALL,
    STREAM_ENDED_UNEXPECTEDLY,
    DEVICE_RESOURCE_OR_DECODER_LIMIT,
    NETWORK,
    UNKNOWN,
}

val FailureCategory.implicatesSavedStrategy: Boolean
    get() = this in setOf(
        FailureCategory.AUDIO_DECODER,
        FailureCategory.AUDIO_RENDERER,
        FailureCategory.VIDEO_DECODER,
        FailureCategory.VIDEO_RENDERER,
        FailureCategory.UNSUPPORTED_VIDEO_CODEC,
        FailureCategory.UNSUPPORTED_VIDEO_SIZE,
        FailureCategory.MEDIA_SOURCE_OR_SDP,
        FailureCategory.FIRST_FRAME_TIMEOUT,
        FailureCategory.REPEATED_BUFFERING_OR_STALL,
        FailureCategory.STREAM_ENDED_UNEXPECTEDLY,
    )

enum class FailurePhase {
    PREPARE,
    SOURCE,
    DECODER,
    RENDERER,
    FIRST_FRAME_PROBE,
    STABLE_DWELL_PROBE,
}

enum class PlaybackDiagnosticCode {
    AUDIO_DECODER,
    VIDEO_DECODER,
    VIDEO_SIZE_UNSUPPORTED,
    RTSP_MISSING_FMTP,
    RTSP_MISSING_VPS,
    RTSP_MISSING_SPS,
    RTSP_MISSING_PPS,
    RTSP_TIMEOUT,
    SESSION_EXPIRED,
    CAMERA_DENIED,
    STALE_ACCESS,
    RESOLUTION_UNAVAILABLE,
    SOURCE_NOT_AUTHORIZED,
    SNAPSHOT_NOT_AUTHORIZED,
    PRIVACY_REVOKED,
    UNCLASSIFIED,
    RETRYABLE,
    LATE_CALLBACK,
    LATE_FIRST_FRAME,
    FIRST_FRAME_TIMEOUT,
    VIDEO_PROGRESS_STALLED,
    AUDIO_PROGRESS_MISSING,
    RELEASE_FAILED,
}

val PlaybackDiagnosticCode.isMissingRtspSetup: Boolean
    get() = this == PlaybackDiagnosticCode.RTSP_MISSING_FMTP ||
        this == PlaybackDiagnosticCode.RTSP_MISSING_VPS ||
        this == PlaybackDiagnosticCode.RTSP_MISSING_SPS ||
        this == PlaybackDiagnosticCode.RTSP_MISSING_PPS

data class ClassifiedPlaybackFailure(
    val category: FailureCategory,
    val phase: FailurePhase,
    /** Stable allowlisted adapter code; raw exception text cannot cross this boundary. */
    val diagnosticCode: PlaybackDiagnosticCode,
) {

    val stopsFallback: Boolean
        get() = category == FailureCategory.AUTHENTICATION ||
            category == FailureCategory.AUTHORIZATION

    val implicatesKnownStrategy: Boolean
        get() = category.implicatesSavedStrategy
}

data class PlaybackAttemptId(
    val sessionId: PlaybackSessionId,
    val ordinal: Int,
) {
    init {
        require(ordinal > 0) { "Attempt ordinal must be positive" }
    }
}

data class PlaybackAttempt(
    val id: PlaybackAttemptId,
    val candidate: PlaybackCandidate,
    val phase: AttemptPhase,
    val startedElapsedMillis: Long,
    val firstFrameDeadlineElapsedMillis: Long,
    val firstFrameElapsedMillis: Long? = null,
    val stableDeadlineElapsedMillis: Long? = null,
    val latestVideoProgressSequence: Long = 0,
    val latestAudioProgressSequence: Long = 0,
    val lastVideoProgressElapsedMillis: Long? = null,
    val lastAudioProgressElapsedMillis: Long? = null,
    val videoProgressEventCount: Int = 0,
    val audioProgressEventCount: Int = 0,
    val decoderEvidence: PlaybackDecoderEvidence = PlaybackDecoderEvidence.UNKNOWN,
) {
    init {
        require(startedElapsedMillis >= 0) { "Attempt start must not be negative" }
        require(firstFrameDeadlineElapsedMillis >= startedElapsedMillis) {
            "First-frame deadline cannot precede attempt start"
        }
        require(latestVideoProgressSequence >= 0 && latestAudioProgressSequence >= 0)
        require(videoProgressEventCount >= 0 && audioProgressEventCount >= 0)
    }
}

enum class CancellationReason {
    USER,
    LIFECYCLE,
}

sealed interface PlaybackTerminalCause {
    data object NoSafeCandidates : PlaybackTerminalCause

    data object AttemptBudgetExhausted : PlaybackTerminalCause

    data object TimeBudgetExhausted : PlaybackTerminalCause

    data object AttemptReleaseFailed : PlaybackTerminalCause

    data class CandidateLadderExhausted(
        val lastFailure: ClassifiedPlaybackFailure,
    ) : PlaybackTerminalCause
}

sealed interface PlaybackResult {
    val attemptsStarted: Int

    data class VerifiedLive(
        val strategy: VerifiedPlaybackStrategy,
        val recordGeneration: Long,
        override val attemptsStarted: Int,
    ) : PlaybackResult {
        init {
            require(recordGeneration > 0) { "Verified record generation must be positive" }
        }
    }

    data class UnpersistedLive(
        val candidate: PlaybackCandidate,
        val reason: PersistenceFailureReason,
        override val attemptsStarted: Int,
    ) : PlaybackResult

    /** Terminal degraded presentation. It is not a verified or persistable live strategy. */
    data class DegradedSnapshot(
        val snapshotRouteId: SnapshotRouteId,
        val cause: PlaybackTerminalCause,
        override val attemptsStarted: Int,
    ) : PlaybackResult

    /** The allowlisted snapshot could not be presented and must not masquerade as degraded success. */
    data class SnapshotUnavailable(
        val snapshotRouteId: SnapshotRouteId,
        val cause: PlaybackTerminalCause,
        val presentationOutcome: PlaybackSnapshotPresentationOutcome,
        override val attemptsStarted: Int,
    ) : PlaybackResult {
        init {
            require(presentationOutcome != PlaybackSnapshotPresentationOutcome.RENDERED) {
                "A rendered snapshot must use the degraded snapshot success result"
            }
        }
    }

    data class Failed(
        val cause: PlaybackTerminalCause,
        override val attemptsStarted: Int,
    ) : PlaybackResult

    data class Blocked(
        val failure: ClassifiedPlaybackFailure,
        override val attemptsStarted: Int,
    ) : PlaybackResult

    /** Neutral termination: cancellation never becomes compatibility failure evidence. */
    data class Cancelled(
        val reason: CancellationReason,
        override val attemptsStarted: Int,
    ) : PlaybackResult
}

enum class PersistenceFailureReason {
    WRITE_FAILED,
    TIMED_OUT,
    CANCELLED_BEFORE_COMMIT,
    RESOLUTION_UNAVAILABLE,
}

/** Exhaustive result of presenting the separately authorized degraded snapshot. */
enum class PlaybackSnapshotPresentationOutcome {
    RENDERED,
    FAILED,
    DENIED,
}

sealed interface PlaybackCommand {
    data class StartAttempt(
        val attempt: PlaybackAttempt,
        val totalDeadlineElapsedMillis: Long,
        override val accessGuard: PlaybackAccessGuard,
    ) : PlaybackContentCommand

    data class AwaitStableDwell(
        val attemptId: PlaybackAttemptId,
        val notBeforeElapsedMillis: Long,
        val totalDeadlineElapsedMillis: Long,
    ) : PlaybackCommand

    data class AwaitReleaseDeadline(
        val attemptId: PlaybackAttemptId,
        val notBeforeElapsedMillis: Long,
    ) : PlaybackCommand

    data class AwaitRecoveryDeadline(
        val attemptId: PlaybackAttemptId,
        val notBeforeElapsedMillis: Long,
    ) : PlaybackCommand

    data class CancelRecoveryDeadline(
        val attemptId: PlaybackAttemptId,
    ) : PlaybackCommand

    data class CancelReleaseDeadline(
        val attemptId: PlaybackAttemptId,
    ) : PlaybackCommand

    data class ReleaseAttempt(
        val attemptId: PlaybackAttemptId,
    ) : PlaybackCommand

    data class ForceReleaseAttempt(
        val attemptId: PlaybackAttemptId,
    ) : PlaybackCommand

    data class RenderSnapshot(
        val routeId: SnapshotRouteId,
        override val accessGuard: PlaybackAccessGuard,
    ) : PlaybackContentCommand

    data class PersistVerifiedStrategy(
        val write: VerifiedStrategyWrite,
    ) : PlaybackCommand

    data class CancelVerifiedStrategyPersistence(
        val context: StrategyPersistenceDecisionContext,
    ) : PlaybackCommand

    data class FinalizeStrategyPersistence(
        val finalization: StrategyPersistenceFinalization,
    ) : PlaybackCommand

    data class AwaitPersistenceResolutionDeadline(
        val attemptId: PlaybackAttemptId,
        val notBeforeElapsedMillis: Long,
    ) : PlaybackCommand

    data class CancelPersistenceResolutionDeadline(
        val attemptId: PlaybackAttemptId,
    ) : PlaybackCommand

    data class QuarantineUnresolvedStrategyPersistence(
        val request: UnresolvedStrategyPersistence,
    ) : PlaybackCommand

    data class RecordImplicatedStrategyFailure(
        val failure: ImplicatedStrategyFailure,
    ) : PlaybackCommand
}

sealed interface PlaybackContentCommand : PlaybackCommand {
    val accessGuard: PlaybackAccessGuard
}

sealed interface PlaybackEvent {
    val elapsedMillis: Long

    data class Begin(override val elapsedMillis: Long) : PlaybackEvent

    data class SnapshotPresentationCompleted(
        val snapshotRouteId: SnapshotRouteId,
        val outcome: PlaybackSnapshotPresentationOutcome,
        override val elapsedMillis: Long,
    ) : PlaybackEvent

    data class FirstFrame(
        val attemptId: PlaybackAttemptId,
        override val elapsedMillis: Long,
        val decoderEvidence: PlaybackDecoderEvidence = PlaybackDecoderEvidence.UNKNOWN,
    ) : PlaybackEvent

    data class VideoProgress(
        val attemptId: PlaybackAttemptId,
        val sequence: Long,
        override val elapsedMillis: Long,
    ) : PlaybackEvent {
        init {
            require(sequence > 0) { "Video progress sequence must be positive" }
        }
    }

    data class AudioProgress(
        val attemptId: PlaybackAttemptId,
        val sequence: Long,
        override val elapsedMillis: Long,
    ) : PlaybackEvent {
        init {
            require(sequence > 0) { "Audio progress sequence must be positive" }
        }
    }

    data class AttemptFailed(
        val attemptId: PlaybackAttemptId,
        val failure: ClassifiedPlaybackFailure,
        override val elapsedMillis: Long,
    ) : PlaybackEvent

    data class ProbeDeadlineReached(
        val attemptId: PlaybackAttemptId,
        val phase: ProbePhase,
        override val elapsedMillis: Long,
    ) : PlaybackEvent

    data class AttemptReleased(
        val attemptId: PlaybackAttemptId,
        override val elapsedMillis: Long,
    ) : PlaybackEvent

    data class AttemptReleaseFailed(
        val attemptId: PlaybackAttemptId,
        override val elapsedMillis: Long,
    ) : PlaybackEvent

    data class ReleaseDeadlineReached(
        val attemptId: PlaybackAttemptId,
        override val elapsedMillis: Long,
    ) : PlaybackEvent

    data class RecoveryDeadlineReached(
        val attemptId: PlaybackAttemptId,
        override val elapsedMillis: Long,
    ) : PlaybackEvent

    data class StrategyPersisted(
        val attemptId: PlaybackAttemptId,
        val recordGeneration: Long,
        override val elapsedMillis: Long,
    ) : PlaybackEvent {
        init {
            require(recordGeneration > 0) { "Persisted strategy generation must be positive" }
        }
    }

    /** Durable phase one exists; the reducer must choose and atomically finalize its disposition. */
    data class StrategyPersistenceDecisionRequired(
        val attemptId: PlaybackAttemptId,
        val recordGeneration: Long?,
        override val elapsedMillis: Long,
    ) : PlaybackEvent {
        init {
            require(recordGeneration == null || recordGeneration > 0) {
                "Pending strategy generation must be positive"
            }
        }
    }

    data class StrategyPersistenceFailed(
        val attemptId: PlaybackAttemptId,
        val reason: PersistenceFailureReason,
        override val elapsedMillis: Long,
    ) : PlaybackEvent

    data class PersistenceResolutionDeadlineReached(
        val attemptId: PlaybackAttemptId,
        override val elapsedMillis: Long,
    ) : PlaybackEvent

    data class Cancel(
        val reason: CancellationReason,
        override val elapsedMillis: Long,
    ) : PlaybackEvent
}
