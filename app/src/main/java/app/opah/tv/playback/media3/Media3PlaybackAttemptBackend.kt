@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package app.opah.tv.playback.media3

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import app.opah.tv.playback.compatibility.AudioMode
import app.opah.tv.playback.compatibility.AuthorizedPlaybackAttempt
import app.opah.tv.playback.compatibility.AuthorizedSnapshotPresentation
import app.opah.tv.playback.compatibility.ClassifiedPlaybackFailure
import app.opah.tv.playback.compatibility.DecoderMode
import app.opah.tv.playback.compatibility.FailureCategory
import app.opah.tv.playback.compatibility.FailurePhase
import app.opah.tv.playback.compatibility.PlaybackAttemptBackend
import app.opah.tv.playback.compatibility.PlaybackAttemptEventSink
import app.opah.tv.playback.compatibility.PlaybackAttemptHandleToken
import app.opah.tv.playback.compatibility.PlaybackAttemptId
import app.opah.tv.playback.compatibility.PlaybackAttemptSignal
import app.opah.tv.playback.compatibility.PlaybackBackendReleaseOutcome
import app.opah.tv.playback.compatibility.PlaybackBackendStartOutcome
import app.opah.tv.playback.compatibility.PlaybackDiagnosticCode
import app.opah.tv.playback.compatibility.PlaybackExecutionAuthorization
import app.opah.tv.playback.compatibility.PlaybackSnapshotCancellationOutcome
import app.opah.tv.playback.compatibility.PlaybackSnapshotPresentationId
import app.opah.tv.playback.compatibility.PlaybackSnapshotRenderOutcome
import app.opah.tv.playback.compatibility.ResolvedPlaybackToken
import app.opah.tv.playback.compatibility.TransportMode
import java.net.URI
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * A route capability redeemed inside the backend. The raw URI never has a public or internal
 * accessor; trusted vaults may only compare two wrappers for exact route continuity.
 */
internal class PrevalidatedRtspUri private constructor(
    private val uri: URI,
) {
    companion object {
        fun fromTrustedAdapter(uri: Uri): PrevalidatedRtspUri =
            fromTrustedAdapter(URI.create(uri.toString()))

        /** JVM-safe construction seam for trusted vault tests and non-Android route adapters. */
        fun fromTrustedAdapter(uri: URI): PrevalidatedRtspUri {
            val scheme = uri.scheme?.lowercase(Locale.US)
            require(scheme == RTSP_SCHEME) {
                "Trusted playback route must use plain RTSP until verified TLS is configured"
            }
            require(!uri.host.isNullOrBlank()) { "Trusted playback route must have a host" }
            require(uri.fragment == null) { "Trusted playback route cannot contain a fragment" }
            return PrevalidatedRtspUri(uri)
        }
    }

    internal fun sameRouteAs(other: PrevalidatedRtspUri): Boolean = uri == other.uri

    /** Installs route material directly; no URI-bearing value crosses back to the caller. */
    internal fun installOnPlayer(
        player: ExoPlayer,
        forceRtpTcp: Boolean,
        timeoutMillis: Long,
    ) {
        val source = RtspMediaSource.Factory()
            .setTimeoutMs(timeoutMillis)
            .setForceUseRtpTcp(forceRtpTcp)
            // SDP and route material may be private; debug logging must remain disabled.
            .setDebugLoggingEnabled(false)
            .createMediaSource(MediaItem.fromUri(uri.toASCIIString()))
        player.setMediaSource(source)
    }

    override fun toString(): String = "PrevalidatedRtspUri([redacted])"
}

/** The token is consumed exactly at this trusted, non-persisting backend boundary. */
internal fun interface TrustedRtspTokenRedeemer {
    suspend fun redeem(token: ResolvedPlaybackToken): TrustedRtspTokenRedemption
}

internal sealed interface TrustedRtspTokenRedemption {
    class Resolved(val route: PrevalidatedRtspUri) : TrustedRtspTokenRedemption {
        override fun toString(): String = "TrustedRtspTokenRedemption.Resolved([redacted])"
    }

    data class AccessLost(
        val authorization: PlaybackExecutionAuthorization,
    ) : TrustedRtspTokenRedemption {
        init {
            require(authorization != PlaybackExecutionAuthorization.AUTHORIZED)
            require(authorization != PlaybackExecutionAuthorization.SNAPSHOT_NOT_AUTHORIZED) {
                "Snapshot authorization cannot deny a live redemption"
            }
        }
    }
}

/** Snapshot bytes and their route redemption remain outside this live-player implementation. */
internal interface TrustedSnapshotPresenter : Media3SnapshotCancellationOwner {
    suspend fun present(request: AuthorizedSnapshotPresentation): PlaybackSnapshotRenderOutcome

    /** Must hide/cancel the exact presentation before acknowledging. */
    override suspend fun cancel(
        presentationId: PlaybackSnapshotPresentationId,
    ): PlaybackSnapshotCancellationOutcome
}

/**
 * Process Media3 backend with one transactionally owned player slot.
 *
 * Player construction, configuration, prepare, and release are confined to the Android main
 * looper. The process ownership state is installed before token redemption, and release cancels
 * and waits for an in-flight start before it returns.
 */
internal class Media3PlaybackAttemptBackend(
    context: Context,
    private val tokenRedeemer: TrustedRtspTokenRedeemer,
    private val snapshotPresenter: TrustedSnapshotPresenter = UnavailableSnapshotPresenter,
    private val failureClassifier: Media3PlaybackFailureClassifier =
        DefaultMedia3PlaybackFailureClassifier,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val clock: Media3EvidenceClock = AndroidMedia3EvidenceClock,
    private val ownership: Media3AttemptOwnership<OwnedMedia3Attempt> =
        ProcessMedia3AttemptOwnership.slot,
    private val snapshotOwnership: Media3SnapshotOwnership =
        ProcessMedia3SnapshotOwnership.registry,
    private val videoOutputs: Media3VideoOutputRegistry =
        ProcessMedia3VideoOutputRegistry.instance,
) : PlaybackAttemptBackend {
    private val applicationContext = context.applicationContext
    private val decoderEvidenceMapper = AndroidMedia3DecoderEvidenceMapper.inspectCurrentDevice()

    override suspend fun start(
        request: AuthorizedPlaybackAttempt,
        events: PlaybackAttemptEventSink,
    ): PlaybackBackendStartOutcome {
        val startJob = currentCoroutineContext()[Job]
        val reservation = when (
            val result = ownership.reserve(request.attempt.id) {
                startJob?.cancel(Media3StartRevokedCancellation())
            }
        ) {
            is Media3ReservationResult.Reserved -> result.reservation
            Media3ReservationResult.RejectedOccupied,
            Media3ReservationResult.RejectedSaturated,
            Media3ReservationResult.RejectedTombstoned,
            -> return PlaybackBackendStartOutcome.Failed(BACKEND_RESOURCE_FAILURE)
        }

        if (
            request.attempt.candidate.decoderMode == DecoderMode.ALLOW_SOFTWARE &&
            !request.resourcePolicy.softwareDecoderPermitted
        ) {
            ownership.abandon(reservation)
            return PlaybackBackendStartOutcome.Failed(BACKEND_RESOURCE_FAILURE)
        }
        val outputAttachment = when (val output = videoOutputs.claim(request.attempt.id)) {
            is Media3VideoOutputClaimResult.Claimed -> output.attachment
            Media3VideoOutputClaimResult.Missing,
            Media3VideoOutputClaimResult.AlreadyClaimed,
            Media3VideoOutputClaimResult.GenerationExhausted,
            -> {
                ownership.abandon(reservation)
                return PlaybackBackendStartOutcome.Failed(BACKEND_OUTPUT_FAILURE)
            }
        }

        var representedHandle: PlaybackAttemptHandleToken? = null
        try {
            val redemption = tokenRedeemer.redeem(request.resolvedToken)
            val route = when (redemption) {
                is TrustedRtspTokenRedemption.Resolved -> redemption.route
                is TrustedRtspTokenRedemption.AccessLost -> {
                    videoOutputs.release(outputAttachment)
                    ownership.abandon(reservation)
                    return PlaybackBackendStartOutcome.AccessLost(redemption.authorization)
                }
            }

            val setup = withContext(mainDispatcher) {
                checkMainLooper()
                buildAndStartOnMain(
                    request = request,
                    route = route,
                    events = events,
                    reservation = reservation,
                    outputAttachment = outputAttachment,
                    onOwnershipRepresented = { representedHandle = it },
                )
            }

            if (setup.cleanupRetryRequired) {
                val cleanup = withContext(NonCancellable) {
                    releaseOwnedAttempt(request.attempt.id, awaitExistingRelease = false)
                }
                if (cleanup != PlaybackBackendReleaseOutcome.RELEASED) {
                    // The player remains represented by the slot, so Started is the only truthful
                    // outcome. The runner's bounded release path will retry this exact attempt.
                    return PlaybackBackendStartOutcome.Started(setup.handle)
                }
            }
            return setup.outcome
        } catch (cancelled: CancellationException) {
            if (representedHandle != null) {
                withContext(NonCancellable) {
                    releaseOwnedAttempt(request.attempt.id, awaitExistingRelease = false)
                }
            } else {
                videoOutputs.release(outputAttachment)
                ownership.abandon(reservation)
            }
            throw cancelled
        } catch (error: Throwable) {
            val handle = representedHandle
            if (handle != null) {
                val cleanup = withContext(NonCancellable) {
                    releaseOwnedAttempt(request.attempt.id, awaitExistingRelease = false)
                }
                if (cleanup != PlaybackBackendReleaseOutcome.RELEASED) {
                    return PlaybackBackendStartOutcome.Started(handle)
                }
            } else {
                videoOutputs.release(outputAttachment)
                ownership.abandon(reservation)
            }
            if (error is Error && error !is OutOfMemoryError) throw error
            return PlaybackBackendStartOutcome.Failed(error.toSanitizedStartFailure())
        }
    }

    override suspend fun release(
        attemptId: PlaybackAttemptId,
        handle: PlaybackAttemptHandleToken?,
        force: Boolean,
    ): PlaybackBackendReleaseOutcome {
        // Attempt ID is authoritative. A handle is deliberately only an opaque optimization, and
        // force uses the same exact release because Media3 exposes no safer partial-release mode.
        @Suppress("UNUSED_VARIABLE")
        val ignoredHints = handle to force
        return releaseOwnedAttempt(attemptId, awaitExistingRelease = true)
    }

    override suspend fun renderSnapshot(
        request: AuthorizedSnapshotPresentation,
    ): PlaybackSnapshotRenderOutcome {
        when (
            snapshotOwnership.reserve(
                presentationId = request.presentationId,
                cancellationOwner = snapshotPresenter,
            )
        ) {
            Media3SnapshotReservationResult.RESERVED -> Unit
            Media3SnapshotReservationResult.REJECTED_DUPLICATE ->
                return PlaybackSnapshotRenderOutcome.CancellationRequired
            Media3SnapshotReservationResult.REJECTED_TOMBSTONED,
            Media3SnapshotReservationResult.REJECTED_CAPACITY,
            -> return PlaybackSnapshotRenderOutcome.Failed
        }

        val outcome = try {
            snapshotPresenter.present(request)
        } catch (cancelled: CancellationException) {
            // Ownership stays represented; the runner's exact-ID cleanup path will cancel it.
            throw cancelled
        } catch (_: Throwable) {
            return when (cancelSnapshot(request.presentationId)) {
                PlaybackSnapshotCancellationOutcome.ACKNOWLEDGED ->
                    PlaybackSnapshotRenderOutcome.Failed
                PlaybackSnapshotCancellationOutcome.FAILED ->
                    PlaybackSnapshotRenderOutcome.CancellationRequired
            }
        }

        return when (
            snapshotOwnership.completePresentation(request.presentationId, outcome)
        ) {
            Media3SnapshotPresentationDisposition.ACCEPT -> outcome
            Media3SnapshotPresentationDisposition.CANCELLED ->
                PlaybackSnapshotRenderOutcome.Failed
            Media3SnapshotPresentationDisposition.CANCELLATION_REQUIRED -> {
                when (cancelSnapshot(request.presentationId)) {
                    PlaybackSnapshotCancellationOutcome.ACKNOWLEDGED ->
                        PlaybackSnapshotRenderOutcome.Failed
                    PlaybackSnapshotCancellationOutcome.FAILED ->
                        PlaybackSnapshotRenderOutcome.CancellationRequired
                }
            }
        }
    }

    override suspend fun cancelSnapshot(
        presentationId: PlaybackSnapshotPresentationId,
    ): PlaybackSnapshotCancellationOutcome {
        // beginCancellation installs the exact-ID tombstone before this method can suspend.
        return when (val plan = snapshotOwnership.beginCancellation(presentationId)) {
            Media3SnapshotCancellationPlan.AlreadyAcknowledged ->
                PlaybackSnapshotCancellationOutcome.ACKNOWLEDGED
            is Media3SnapshotCancellationPlan.Await -> plan.completion.await()
            is Media3SnapshotCancellationPlan.Delegate -> {
                val delegated = try {
                    plan.cancellationOwner.cancel(presentationId)
                } catch (cancelled: CancellationException) {
                    snapshotOwnership.completeCancellation(
                        presentationId,
                        plan,
                        PlaybackSnapshotCancellationOutcome.FAILED,
                    )
                    throw cancelled
                } catch (_: Throwable) {
                    PlaybackSnapshotCancellationOutcome.FAILED
                }
                snapshotOwnership.completeCancellation(
                    presentationId,
                    plan,
                    delegated,
                )
            }
        }
    }

    private fun buildAndStartOnMain(
        request: AuthorizedPlaybackAttempt,
        route: PrevalidatedRtspUri,
        events: PlaybackAttemptEventSink,
        reservation: Media3AttemptReservation,
        outputAttachment: Media3VideoOutputAttachment,
        onOwnershipRepresented: (PlaybackAttemptHandleToken) -> Unit,
    ): Media3SetupResult {
        val handle = PlaybackAttemptHandleToken.fromTrustedBackend(UUID.randomUUID().toString())
        val candidate = request.attempt.candidate
        val safeEvents = PlaybackAttemptEventSink { signal ->
            try {
                events.emit(signal)
            } catch (_: RuntimeException) {
                // Runner deadlines stay authoritative after its callback boundary has closed.
            }
        }
        val progressEvidence = Media3PlaybackProgressEvidenceSession(
            attemptId = request.attempt.id,
            attemptStartedElapsedMillis = request.attempt.startedElapsedMillis,
            intendedVideoOutputGeneration = outputAttachment.generation,
            eventSink = safeEvents,
            decoderEvidenceMapper = decoderEvidenceMapper,
            clock = clock,
        )
        val failureSignals = Media3FailureSignalEmitter(
            attemptId = request.attempt.id,
            attemptStartedElapsedMillis = request.attempt.startedElapsedMillis,
            eventSink = safeEvents,
            clock = clock,
            onTerminal = { progressEvidence.cancel(request.attempt.id) },
        )
        val renderersFactory = ProbedMedia3RenderersFactory(
            context = applicationContext,
            decoderMode = candidate.decoderMode,
            softwareDecoderPermitted = request.resourcePolicy.softwareDecoderPermitted,
            progressEvidence = progressEvidence,
        )

        var owned: OwnedMedia3Attempt? = null
        try {
            val player = ExoPlayer.Builder(applicationContext, renderersFactory).build()
            val created = OwnedMedia3Attempt(
                attemptId = request.attempt.id,
                player = player,
                outputAttachment = outputAttachment,
                progressEvidence = progressEvidence,
                failureSignals = failureSignals,
                renderedFrameProgressProbe = Media3RenderedFrameProgressProbe(
                    player = player,
                    attemptId = request.attempt.id,
                    outputGeneration = outputAttachment.generation,
                    progressEvidence = progressEvidence,
                ),
            )
            owned = created
            installListeners(created)
            check(
                progressEvidence.attachVideoOutput(
                    request.attempt.id,
                    outputAttachment.generation,
                ),
            ) { "Video output attachment did not match the playback attempt" }
            outputAttachment.target.attach(
                player = player,
                outputGeneration = outputAttachment.generation,
            )
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(
                    C.TRACK_TYPE_AUDIO,
                    candidate.audioMode == AudioMode.VIDEO_ONLY,
                )
                .build()
            route.installOnPlayer(
                player = player,
                forceRtpTcp = candidate.transportMode == TransportMode.FORCE_RTP_TCP,
                timeoutMillis = MEDIA3_RTSP_TIMEOUT_MILLIS,
            )
            player.playWhenReady = true
            player.prepare()

            return when (ownership.publish(reservation, created, handle)) {
                Media3PublishResult.PUBLISHED -> {
                    created.renderedFrameProgressProbe.start()
                    onOwnershipRepresented(handle)
                    Media3SetupResult(
                        outcome = PlaybackBackendStartOutcome.Started(handle),
                        handle = handle,
                        cleanupRetryRequired = false,
                    )
                }

                Media3PublishResult.DISCARD -> discardUnpublishedOnMain(
                    reservation = reservation,
                    owned = created,
                    handle = handle,
                    failure = BACKEND_RESOURCE_FAILURE,
                    onOwnershipRepresented = onOwnershipRepresented,
                )
            }
        } catch (error: Throwable) {
            val created = owned
            if (created == null) {
                progressEvidence.cancel(request.attempt.id)
                failureSignals.close()
                videoOutputs.release(outputAttachment)
                ownership.abandon(reservation)
                throw error
            }
            return discardUnpublishedOnMain(
                reservation = reservation,
                owned = created,
                handle = handle,
                failure = error.toSanitizedStartFailure(),
                onOwnershipRepresented = onOwnershipRepresented,
            )
        }
    }

    private fun discardUnpublishedOnMain(
        reservation: Media3AttemptReservation,
        owned: OwnedMedia3Attempt,
        handle: PlaybackAttemptHandleToken,
        failure: ClassifiedPlaybackFailure,
        onOwnershipRepresented: (PlaybackAttemptHandleToken) -> Unit,
    ): Media3SetupResult {
        val released = releasePlayerOnMain(owned)
        if (released) {
            ownership.abandon(reservation)
        } else {
            ownership.retainFailedDiscard(reservation, owned, handle)
            onOwnershipRepresented(handle)
        }
        return Media3SetupResult(
            outcome = PlaybackBackendStartOutcome.Failed(failure),
            handle = handle,
            cleanupRetryRequired = !released,
        )
    }

    private fun installListeners(owned: OwnedMedia3Attempt) {
        val player = owned.player
        player.addListener(
            object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    if (!tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)) {
                        owned.progressEvidence.resetAudioPath(owned.attemptId)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    val evidence = Media3PlaybackFailureEvidenceExtractor.extract(
                        player = player,
                        error = error,
                        context = Media3PlaybackFailureContext(
                            sourceProtocol = Media3SourceProtocol.RTSP,
                        ),
                    )
                    owned.failureSignals.emit(failureClassifier.classify(evidence))
                }

                override fun onSurfaceSizeChanged(width: Int, height: Int) {
                    owned.progressEvidence.recordVideoSurfaceSize(
                        requestedAttemptId = owned.attemptId,
                        outputGeneration = owned.outputAttachment.generation,
                        width = width,
                        height = height,
                    )
                }
            },
        )
        player.addAnalyticsListener(owned.progressEvidence.analyticsListener)
        player.addAnalyticsListener(
            object : AnalyticsListener {
                override fun onAudioUnderrun(
                    eventTime: AnalyticsListener.EventTime,
                    bufferSize: Int,
                    bufferSizeMs: Long,
                    elapsedSinceLastFeedMs: Long,
                ) {
                    owned.progressEvidence.resetAudioPath(owned.attemptId)
                }

                override fun onAudioDisabled(
                    eventTime: AnalyticsListener.EventTime,
                    decoderCounters: DecoderCounters,
                ) {
                    owned.progressEvidence.resetAudioPath(owned.attemptId)
                }

                override fun onAudioDecoderReleased(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                ) {
                    owned.progressEvidence.resetAudioPath(owned.attemptId)
                }

                override fun onAudioSinkError(
                    eventTime: AnalyticsListener.EventTime,
                    audioSinkError: Exception,
                ) {
                    owned.progressEvidence.resetAudioPath(owned.attemptId)
                }
            },
        )
        player.setVideoFrameMetadataListener(owned.progressEvidence.videoFrameMetadataListener)
    }

    private suspend fun releaseOwnedAttempt(
        attemptId: PlaybackAttemptId,
        awaitExistingRelease: Boolean,
    ): PlaybackBackendReleaseOutcome {
        var transitions = 0
        while (transitions < MAX_RELEASE_STATE_TRANSITIONS) {
            transitions += 1
            when (val plan = ownership.beginRelease(attemptId)) {
                Media3ReleasePlan.AlreadyReleased -> return PlaybackBackendReleaseOutcome.RELEASED
                is Media3ReleasePlan.AwaitStart -> {
                    if (!awaitExistingRelease) return PlaybackBackendReleaseOutcome.FAILED
                    plan.settlement.await()
                }

                is Media3ReleasePlan.AwaitRelease -> {
                    if (!awaitExistingRelease) return PlaybackBackendReleaseOutcome.FAILED
                    return plan.completion.await()
                }

                is Media3ReleasePlan.Release -> {
                    val released = try {
                        withContext(NonCancellable + mainDispatcher) {
                            checkMainLooper()
                            releasePlayerOnMain(plan.resource)
                        }
                    } catch (_: Throwable) {
                        false
                    }
                    val outcome = if (released) {
                        PlaybackBackendReleaseOutcome.RELEASED
                    } else {
                        PlaybackBackendReleaseOutcome.FAILED
                    }
                    ownership.completeRelease(plan.reservation, plan.completion, outcome)
                    return outcome
                }
            }
        }
        return PlaybackBackendReleaseOutcome.FAILED
    }

    private fun releasePlayerOnMain(owned: OwnedMedia3Attempt): Boolean {
        checkMainLooper()
        owned.renderedFrameProgressProbe.stop()
        owned.progressEvidence.detachVideoOutput(
            requestedAttemptId = owned.attemptId,
            outputGeneration = owned.outputAttachment.generation,
        )
        owned.progressEvidence.cancel(owned.attemptId)
        owned.failureSignals.close()
        return owned.releaseProgress.advance(
            hideOutput = {
                owned.outputAttachment.target.detachAndHide(
                    player = owned.player,
                    outputGeneration = owned.outputAttachment.generation,
                )
            },
            releasePlayer = {
                owned.player.release()
                true
            },
            releaseRegistryAttachment = {
                videoOutputs.release(owned.outputAttachment)
            },
        )
    }
}

private class ProbedMedia3RenderersFactory(
    context: Context,
    decoderMode: DecoderMode,
    softwareDecoderPermitted: Boolean,
    private val progressEvidence: Media3PlaybackProgressEvidenceSession,
) : SizeCheckedRenderersFactory(
    context,
    onSizeRejected = { progressEvidence.cancel(progressEvidence.attemptId) },
) {
    init {
        val selector = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
            val discovered = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecure,
                requiresTunneling,
            )
            if (!mimeType.startsWith(VIDEO_MIME_PREFIX)) {
                discovered
            } else {
                applyMedia3VideoDecoderPolicy(
                    decoders = discovered,
                    decoderMode = decoderMode,
                    softwareDecoderPermitted = softwareDecoderPermitted,
                    isHardware = { it.hardwareAccelerated },
                    isSoftware = { it.softwareOnly },
                )
            }
        }
        setMediaCodecSelector(selector)
        if (decoderMode != DecoderMode.PLATFORM_DEFAULT || !softwareDecoderPermitted) {
            setEnableDecoderFallback(true)
        }
        if (decoderMode == DecoderMode.ALLOW_SOFTWARE && softwareDecoderPermitted) {
            forceDisableMediaCodecAsynchronousQueueing()
        }
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? = super.buildAudioSink(
            context,
            enableFloatOutput,
            enableAudioTrackPlaybackParams,
        )?.let(progressEvidence::wrapAudioSink)
}

internal data class OwnedMedia3Attempt(
    val attemptId: PlaybackAttemptId,
    val player: ExoPlayer,
    val outputAttachment: Media3VideoOutputAttachment,
    val progressEvidence: Media3PlaybackProgressEvidenceSession,
    val failureSignals: Media3FailureSignalEmitter,
    val renderedFrameProgressProbe: Media3RenderedFrameProgressProbe,
    val releaseProgress: Media3ReleaseProgress = Media3ReleaseProgress(),
)

/**
 * Samples Media3's rendered-output counter for devices that omit per-frame metadata callbacks.
 * Evidence remains gated by the exact output generation and rendered-first-frame proof inside
 * [Media3PlaybackProgressEvidenceSession].
 */
internal class Media3RenderedFrameProgressProbe(
    private val player: ExoPlayer,
    private val attemptId: PlaybackAttemptId,
    private val outputGeneration: Long,
    private val progressEvidence: Media3PlaybackProgressEvidenceSession,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private val counter = Media3RenderedOutputCounter()
    private var running = false
    private val sample = object : Runnable {
        override fun run() {
            if (!running) return
            val renderedCount = runCatching {
                player.videoDecoderCounters
                    ?.also(DecoderCounters::ensureUpdated)
                    ?.renderedOutputBufferCount
            }.getOrNull()
            renderedCount?.let(counter::observe)?.takeIf { it > 0 }?.let { renderedFrames ->
                progressEvidence.recordProcessedVideoFrameBatch(
                    requestedAttemptId = attemptId,
                    frameCount = renderedFrames,
                    outputGeneration = outputGeneration,
                )
            }
            if (running) handler.postDelayed(this, MEDIA3_RENDERED_FRAME_PROGRESS_POLL_MILLIS)
        }
    }

    fun start() {
        checkMainLooper()
        if (running) return
        running = true
        handler.post(sample)
    }

    fun stop() {
        checkMainLooper()
        if (!running) return
        running = false
        handler.removeCallbacks(sample)
    }
}

internal class Media3RenderedOutputCounter {
    private var previous = 0

    fun observe(renderedOutputBufferCount: Int): Int {
        if (renderedOutputBufferCount < 0) return 0
        val advancedBy = if (renderedOutputBufferCount > previous) {
            renderedOutputBufferCount - previous
        } else {
            0
        }
        previous = renderedOutputBufferCount
        return advancedBy
    }
}

/** Retry-safe, fail-closed release ledger for player, output concealment, and registry ownership. */
internal class Media3ReleaseProgress {
    private var outputHidden = false
    private var playerReleased = false
    private var registryAttachmentReleased = false

    fun advance(
        hideOutput: () -> Boolean,
        releasePlayer: () -> Boolean,
        releaseRegistryAttachment: () -> Boolean,
    ): Boolean {
        if (!outputHidden) outputHidden = safeCleanupStep(hideOutput)
        if (!playerReleased) playerReleased = safeCleanupStep(releasePlayer)
        if (outputHidden && playerReleased && !registryAttachmentReleased) {
            registryAttachmentReleased = safeCleanupStep(releaseRegistryAttachment)
        }
        return outputHidden && playerReleased && registryAttachmentReleased
    }

    internal fun snapshotForTest(): Media3ReleaseProgressSnapshot = Media3ReleaseProgressSnapshot(
        outputHidden = outputHidden,
        playerReleased = playerReleased,
        registryAttachmentReleased = registryAttachmentReleased,
    )

    private fun safeCleanupStep(step: () -> Boolean): Boolean = try {
        step()
    } catch (_: Throwable) {
        false
    }
}

internal data class Media3ReleaseProgressSnapshot(
    val outputHidden: Boolean,
    val playerReleased: Boolean,
    val registryAttachmentReleased: Boolean,
)

internal class Media3FailureSignalEmitter(
    private val attemptId: PlaybackAttemptId,
    private val attemptStartedElapsedMillis: Long,
    private val eventSink: PlaybackAttemptEventSink,
    private val clock: Media3EvidenceClock,
    private val onTerminal: () -> Unit,
) {
    private val terminal = AtomicBoolean(false)

    fun emit(failure: ClassifiedPlaybackFailure) {
        if (!terminal.compareAndSet(false, true)) return
        try {
            onTerminal()
        } catch (_: RuntimeException) {
            // Failure evidence still needs delivery even if an optional cleanup hook misbehaves.
        }
        val signal = PlaybackAttemptSignal.Failed(
            attemptId = attemptId,
            elapsedMillis = maxOf(
                attemptStartedElapsedMillis,
                clock.elapsedRealtimeMillis().coerceAtLeast(0L),
            ),
            failure = failure,
        )
        try {
            eventSink.emit(signal)
        } catch (_: RuntimeException) {
            // The reducer's deadlines remain authoritative if its callback boundary has closed.
        }
    }

    fun close() {
        terminal.set(true)
    }
}

private data class Media3SetupResult(
    val outcome: PlaybackBackendStartOutcome,
    val handle: PlaybackAttemptHandleToken,
    val cleanupRetryRequired: Boolean,
)

private object ProcessMedia3AttemptOwnership {
    val slot = Media3AttemptOwnership<OwnedMedia3Attempt>()
}

private object ProcessMedia3SnapshotOwnership {
    val registry = Media3SnapshotOwnership()
}

private object UnavailableSnapshotPresenter : TrustedSnapshotPresenter {
    override suspend fun present(
        request: AuthorizedSnapshotPresentation,
    ): PlaybackSnapshotRenderOutcome = PlaybackSnapshotRenderOutcome.Failed

    override suspend fun cancel(
        presentationId: PlaybackSnapshotPresentationId,
    ): PlaybackSnapshotCancellationOutcome = PlaybackSnapshotCancellationOutcome.ACKNOWLEDGED
}

private class Media3StartRevokedCancellation : CancellationException("Playback start was released")

private fun Throwable.toSanitizedStartFailure(): ClassifiedPlaybackFailure =
    if (this is OutOfMemoryError) {
        BACKEND_RESOURCE_FAILURE
    } else {
        BACKEND_PREPARE_FAILURE
    }

private fun checkMainLooper() {
    check(Looper.myLooper() == Looper.getMainLooper()) {
        "Media3 player ownership must remain on the Android main looper"
    }
}

private val BACKEND_RESOURCE_FAILURE = ClassifiedPlaybackFailure(
    category = FailureCategory.DEVICE_RESOURCE_OR_DECODER_LIMIT,
    phase = FailurePhase.PREPARE,
    diagnosticCode = PlaybackDiagnosticCode.RETRYABLE,
)

private val BACKEND_OUTPUT_FAILURE = ClassifiedPlaybackFailure(
    category = FailureCategory.VIDEO_RENDERER,
    phase = FailurePhase.PREPARE,
    diagnosticCode = PlaybackDiagnosticCode.RETRYABLE,
)

private val BACKEND_PREPARE_FAILURE = ClassifiedPlaybackFailure(
    category = FailureCategory.UNKNOWN,
    phase = FailurePhase.PREPARE,
    diagnosticCode = PlaybackDiagnosticCode.UNCLASSIFIED,
)

private const val RTSP_SCHEME = "rtsp"
private const val VIDEO_MIME_PREFIX = "video/"
private const val MEDIA3_RTSP_TIMEOUT_MILLIS = 8_000L
private const val MEDIA3_RENDERED_FRAME_PROGRESS_POLL_MILLIS = 250L
private const val MAX_RELEASE_STATE_TRANSITIONS = 4
