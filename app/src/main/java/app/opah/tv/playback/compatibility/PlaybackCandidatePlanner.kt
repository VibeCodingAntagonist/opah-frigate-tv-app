package app.opah.tv.playback.compatibility

/** Creates and traverses a finite compatibility ladder from trusted discovery results. */
class PlaybackCandidatePlanner {
    fun plan(
        context: PlaybackCompatibilityContext,
        knownGood: StoredPlaybackStrategy? = null,
    ): PlaybackPlan {
        val sources = context.knownSources
            .asSequence()
            .filter(KnownPlaybackSource::available)
            .filter { it.isAllowedBy(context.resourcePolicy) }
            .filter { it.orderedDecoders(context.resourcePolicy).isNotEmpty() }
            .sortedWith(
                compareBy<KnownPlaybackSource> { it.preferenceRank }
                    .thenBy { it.routeId.value }
                    .thenBy { it.streamId.value },
            )
            .toList()
        val candidates = LinkedHashMap<PlaybackCandidateKey, PlaybackCandidate>()

        fun add(candidate: PlaybackCandidate) {
            if (candidates.size < context.budget.maxCandidates) {
                candidates.putIfAbsent(candidate.key(), candidate)
            }
        }

        val canonicalKnownGood = canonicalKnownGood(context, sources, knownGood)
        val primarySource = sources.firstOrNull()
        fun baseline(source: KnownPlaybackSource): PlaybackCandidate =
            candidate(
                source = source,
                audioMode = source.primaryAudioMode(context.resourcePolicy),
                transportMode = source.orderedTransports().first(),
                decoderMode = source.orderedDecoders(context.resourcePolicy).first(),
                origin = CandidateOrigin.DISCOVERED_BASELINE,
            )

        val primaryBaseline = primarySource?.let(::baseline)
        val knownGoodCoversAlternateSource = canonicalKnownGood?.let { saved ->
            primaryBaseline != null &&
                (saved.routeId != primaryBaseline.routeId || saved.streamId != primaryBaseline.streamId)
        } == true

        // Reserve one deterministic representative for every failure-critical dimension before
        // broadening the ladder. A representative is selected by its recovery property rather
        // than discovery adjacency, so early equivalent sources cannot starve the only useful
        // later source. One candidate may satisfy several reservations through key de-duplication.
        // The bounded pass intentionally avoids reserving cross-products of source, transport,
        // decoder, and audio variants; those combinations are added only if budget remains.
        canonicalKnownGood?.let(::add)
        primaryBaseline?.let(::add)
        canonicalKnownGood
            ?.takeIf { it.audioMode == AudioMode.WITH_AUDIO }
            ?.copy(
                audioMode = AudioMode.VIDEO_ONLY,
                origin = CandidateOrigin.SAFE_FALLBACK,
                knownGoodRecordGeneration = null,
            )
            ?.let(::add)
        primarySource?.let { source ->
            sources.firstOrNull { it.media.videoCodec != source.media.videoCodec }
                ?.let(::baseline)
                ?.let(::add)

            source.pixelCountOrNull()?.let { primaryPixels ->
                sources
                    .drop(1)
                    .asSequence()
                    .mapNotNull { alternate ->
                        alternate.pixelCountOrNull()
                            ?.takeIf { it < primaryPixels }
                            ?.let { pixels -> alternate to pixels }
                    }
                    .minWithOrNull(
                        compareBy<Pair<KnownPlaybackSource, Long>> { it.second }
                            .thenBy { it.first.preferenceRank }
                            .thenBy { it.first.routeId.value }
                            .thenBy { it.first.streamId.value },
                    )
                    ?.first
                    ?.let(::baseline)
                    ?.let(::add)
            }

            if (!knownGoodCoversAlternateSource) {
                sources.drop(1).firstOrNull()
                    ?.let(::baseline)
                    ?.let(::add)
            }

            primaryBaseline
                ?.takeIf { it.audioMode == AudioMode.WITH_AUDIO }
                ?.copy(audioMode = AudioMode.VIDEO_ONLY, origin = CandidateOrigin.SAFE_FALLBACK)
                ?.let(::add)

            val audio = source.primaryAudioMode(context.resourcePolicy)
            val transport = source.orderedTransports().first()
            val decoder = source.orderedDecoders(context.resourcePolicy).first()
            source.orderedTransports().drop(1).firstOrNull()?.let { alternateTransport ->
                add(
                    candidate(
                        source = source,
                        audioMode = audio,
                        transportMode = alternateTransport,
                        decoderMode = decoder,
                        origin = CandidateOrigin.SAFE_FALLBACK,
                    ),
                )
            }
            source.orderedDecoders(context.resourcePolicy)
                .drop(1)
                .minByOrNull { it.resourceCost() }
                ?.let { alternateDecoder ->
                    add(
                        candidate(
                            source = source,
                            audioMode = audio,
                            transportMode = transport,
                            decoderMode = alternateDecoder,
                            origin = CandidateOrigin.SAFE_FALLBACK,
                        ),
                    )
                }
        }

        // Give every remaining known source a baseline only after critical coverage is reserved.
        sources.drop(1).forEach { source ->
            add(
                candidate(
                    source = source,
                    audioMode = source.primaryAudioMode(context.resourcePolicy),
                    transportMode = source.orderedTransports().first(),
                    decoderMode = source.orderedDecoders(context.resourcePolicy).first(),
                    origin = CandidateOrigin.DISCOVERED_BASELINE,
                ),
            )
        }

        // Audio isolation is a first-class probe, including the PCMA failure shape in issue #11.
        sources.filter { it.media.audioCodec != null && context.resourcePolicy.audioPermitted }.forEach { source ->
            add(
                candidate(
                    source = source,
                    audioMode = AudioMode.VIDEO_ONLY,
                    transportMode = source.orderedTransports().first(),
                    decoderMode = source.orderedDecoders(context.resourcePolicy).first(),
                    origin = CandidateOrigin.SAFE_FALLBACK,
                ),
            )
        }

        // Decoder alternatives alter a video dimension and are never equivalent to audio-off.
        sources.forEach { source ->
            source.orderedDecoders(context.resourcePolicy).drop(1).forEach { decoder ->
                source.audioModes(context.resourcePolicy).forEach { audio ->
                    add(
                        candidate(
                            source = source,
                            audioMode = audio,
                            transportMode = source.orderedTransports().first(),
                            decoderMode = decoder,
                            origin = CandidateOrigin.SAFE_FALLBACK,
                        ),
                    )
                }
            }
        }

        // Transport alternatives are limited to modes explicitly allowed for each known route.
        sources.forEach { source ->
            source.orderedTransports().drop(1).forEach { transport ->
                source.orderedDecoders(context.resourcePolicy).forEach { decoder ->
                    source.audioModes(context.resourcePolicy).forEach { audio ->
                        add(
                            candidate(
                                source = source,
                                audioMode = audio,
                                transportMode = transport,
                                decoderMode = decoder,
                                origin = CandidateOrigin.SAFE_FALLBACK,
                            ),
                        )
                    }
                }
            }
        }

        return PlaybackPlan.fromTrustedDiscovery(
            identity = context.identity,
            candidates = candidates.values.toList(),
            accessGuard = context.accessGuard,
            snapshotRouteId = context.snapshotRouteId,
            budget = context.budget,
            resourcePolicy = context.resourcePolicy,
            authorizedSources = sources,
        )
    }

    /**
     * Chooses a failure-relevant untried candidate. Returning null exhausts the safe ladder; this
     * method never synthesizes a source, route, stream, decoder, or transport.
     */
    fun selectNextCandidate(
        plan: PlaybackPlan,
        current: PlaybackCandidate,
        failure: ClassifiedPlaybackFailure,
        attempted: Set<PlaybackCandidateKey>,
    ): PlaybackCandidate? {
        if (failure.stopsFallback) return null

        return plan.candidates
            .withIndex()
            .asSequence()
            .filter { it.value.key() !in attempted }
            // Size admission checked every eligible decoder. Missing SDP setup fails before
            // decoding. Neither can be repaired by cycling through the same source's variants.
            .filter { indexed ->
                (failure.category != FailureCategory.UNSUPPORTED_VIDEO_SIZE &&
                    !failure.diagnosticCode.isMissingRtspSetup) || attempted.none {
                    it.routeId == indexed.value.routeId && it.streamId == indexed.value.streamId
                }
            }
            .mapNotNull { indexed ->
                relevanceScore(current, indexed.value, failure.category)
                    ?.let { score -> RankedCandidate(indexed.value, score, indexed.index) }
            }
            .sortedWith(compareBy<RankedCandidate> { it.score }.thenBy { it.planIndex })
            .map(RankedCandidate::candidate)
            .firstOrNull()
    }

    private fun canonicalKnownGood(
        context: PlaybackCompatibilityContext,
        sources: List<KnownPlaybackSource>,
        knownGood: StoredPlaybackStrategy?,
    ): PlaybackCandidate? {
        val saved = knownGood?.record
        if (knownGood == null ||
            saved == null ||
            saved.modelVersion != PLAYBACK_COMPATIBILITY_MODEL_VERSION ||
            saved.identity != context.identity
        ) {
            return null
        }
        val source = sources.firstOrNull {
            it.persistenceScope == saved.sourceScope && it.media == saved.media
        } ?: return null
        if (saved.transportMode !in source.supportedTransports ||
            saved.decoderMode !in source.supportedDecoders ||
            saved.decoderMode == DecoderMode.ALLOW_SOFTWARE && !context.resourcePolicy.softwareDecoderPermitted ||
            saved.audioMode == AudioMode.WITH_AUDIO &&
            (!context.resourcePolicy.audioPermitted || source.media.audioCodec == null)
        ) {
            return null
        }
        return candidate(
            source = source,
            audioMode = saved.audioMode,
            transportMode = saved.transportMode,
            decoderMode = saved.decoderMode,
            origin = CandidateOrigin.KNOWN_GOOD,
            knownGoodRecordGeneration = knownGood.recordGeneration,
        )
    }

    private fun relevanceScore(
        current: PlaybackCandidate,
        candidate: PlaybackCandidate,
        failure: FailureCategory,
    ): Int? {
        val audioSpecificFailure = failure == FailureCategory.AUDIO_DECODER ||
            failure == FailureCategory.AUDIO_RENDERER
        if (!audioSpecificFailure && candidate.isAudioIsolationCandidate()) return null

        val sameSource = candidate.routeId == current.routeId &&
            candidate.streamId == current.streamId
        val sameTransport = candidate.transportMode == current.transportMode
        val sameDecoder = candidate.decoderMode == current.decoderMode
        val differentVideoDimension = !sameSource ||
            candidate.media.videoCodec != current.media.videoCodec ||
            !sameDecoder

        return when (failure) {
            FailureCategory.AUTHENTICATION,
            FailureCategory.AUTHORIZATION,
            -> null

            FailureCategory.AUDIO_DECODER,
            FailureCategory.AUDIO_RENDERER,
            -> when {
                sameSource && sameTransport && sameDecoder &&
                    candidate.audioMode == AudioMode.VIDEO_ONLY -> 0
                sameSource && candidate.audioMode == AudioMode.VIDEO_ONLY -> 1
                candidate.audioMode == AudioMode.VIDEO_ONLY -> 2
                !sameSource -> 3
                else -> null
            }

            FailureCategory.VIDEO_DECODER,
            FailureCategory.VIDEO_RENDERER,
            -> when {
                !differentVideoDimension -> null
                sameSource && !sameDecoder -> 0
                !sameSource && candidate.media.videoCodec != current.media.videoCodec -> 1
                !sameSource -> 2
                else -> 3
            }

            FailureCategory.UNSUPPORTED_VIDEO_CODEC -> when {
                !sameSource && candidate.media.videoCodec != current.media.videoCodec -> 0
                else -> null
            }

            FailureCategory.UNSUPPORTED_VIDEO_SIZE -> when {
                sameSource -> null
                candidate.pixelCount() < current.pixelCount() -> 0
                // A narrower source can fit even if its total pixel count is larger. Unknown
                // metadata may also be tried; actual format admission remains authoritative.
                else -> 1
            }

            FailureCategory.SOURCE_TIMEOUT,
            FailureCategory.RTSP_TIMEOUT,
            FailureCategory.FIRST_FRAME_TIMEOUT,
            FailureCategory.REPEATED_BUFFERING_OR_STALL,
            FailureCategory.STREAM_ENDED_UNEXPECTEDLY,
            -> when {
                sameSource && current.transportMode != TransportMode.FORCE_RTP_TCP &&
                    candidate.transportMode == TransportMode.FORCE_RTP_TCP -> 0
                !sameSource -> 1
                else -> null
            }

            FailureCategory.DNS_OR_ROUTE,
            FailureCategory.CONNECTION_REFUSED,
            FailureCategory.MEDIA_SOURCE_OR_SDP,
            FailureCategory.SOURCE_UNAVAILABLE,
            -> if (!sameSource) 0 else null

            FailureCategory.DEVICE_RESOURCE_OR_DECODER_LIMIT -> when {
                sameSource && candidate.decoderMode.resourceCost() < current.decoderMode.resourceCost() -> 0
                !sameSource && candidate.pixelCount() < current.pixelCount() -> 1
                else -> null
            }

            FailureCategory.NETWORK -> when {
                sameSource && current.transportMode != TransportMode.FORCE_RTP_TCP &&
                    candidate.transportMode == TransportMode.FORCE_RTP_TCP -> 0
                !sameSource -> 1
                else -> null
            }

            FailureCategory.UNKNOWN -> 0
        }
    }

    private fun PlaybackCandidate.pixelCount(): Long {
        val width = media.width ?: return Long.MAX_VALUE
        val height = media.height ?: return Long.MAX_VALUE
        return width.toLong() * height.toLong()
    }

    /** A video-only probe is audio isolation only when discovery says audio is available. */
    private fun PlaybackCandidate.isAudioIsolationCandidate(): Boolean =
        audioMode == AudioMode.VIDEO_ONLY && media.audioCodec != null

    private fun KnownPlaybackSource.pixelCountOrNull(): Long? {
        val width = media.width ?: return null
        val height = media.height ?: return null
        return width.toLong() * height.toLong()
    }

    private fun DecoderMode.resourceCost(): Int = when (this) {
        DecoderMode.PREFER_HARDWARE -> 0
        DecoderMode.PLATFORM_DEFAULT -> 1
        DecoderMode.ALLOW_SOFTWARE -> 2
    }

    private fun candidate(
        source: KnownPlaybackSource,
        audioMode: AudioMode,
        transportMode: TransportMode,
        decoderMode: DecoderMode,
        origin: CandidateOrigin,
        knownGoodRecordGeneration: Long? = null,
    ): PlaybackCandidate = PlaybackCandidate(
        routeId = source.routeId,
        streamId = source.streamId,
        sourceScope = source.persistenceScope,
        media = source.media,
        audioMode = audioMode,
        transportMode = transportMode,
        decoderMode = decoderMode,
        origin = origin,
        knownGoodRecordGeneration = knownGoodRecordGeneration,
    )

    private fun KnownPlaybackSource.primaryAudioMode(policy: PlaybackResourcePolicy): AudioMode =
        if (media.audioCodec == null || !policy.audioPermitted) AudioMode.VIDEO_ONLY else AudioMode.WITH_AUDIO

    private fun KnownPlaybackSource.audioModes(policy: PlaybackResourcePolicy): List<AudioMode> =
        if (media.audioCodec == null || !policy.audioPermitted) {
            listOf(AudioMode.VIDEO_ONLY)
        } else {
            listOf(AudioMode.WITH_AUDIO, AudioMode.VIDEO_ONLY)
        }

    private fun KnownPlaybackSource.orderedTransports(): List<TransportMode> =
        listOf(preferredTransport) + supportedTransports.filterNot { it == preferredTransport }

    private fun KnownPlaybackSource.orderedDecoders(policy: PlaybackResourcePolicy): List<DecoderMode> =
        supportedDecoders.filter {
            it != DecoderMode.ALLOW_SOFTWARE || policy.softwareDecoderPermitted
        }

    private fun KnownPlaybackSource.isAllowedBy(policy: PlaybackResourcePolicy): Boolean {
        val maximum = policy.maximumCandidatePixels ?: return true
        val width = media.width ?: return false
        val height = media.height ?: return false
        return width.toLong() * height.toLong() <= maximum
    }

    private data class RankedCandidate(
        val candidate: PlaybackCandidate,
        val score: Int,
        val planIndex: Int,
    )
}
