package app.opah.tv.playback.compatibility

data class PlaybackRun(
    val plan: PlaybackPlan,
    val sessionId: PlaybackSessionId,
    val startedElapsedMillis: Long,
    val totalDeadlineElapsedMillis: Long,
    val lastAcceptedElapsedMillis: Long,
    val attemptedCandidateKeys: Set<PlaybackCandidateKey>,
    val attemptsStarted: Int,
    val recoveryCyclesStarted: Int,
) {
    init {
        require(startedElapsedMillis >= 0) { "Run start must not be negative" }
        require(totalDeadlineElapsedMillis >= startedElapsedMillis) {
            "Run deadline cannot precede its start"
        }
        require(lastAcceptedElapsedMillis >= startedElapsedMillis) {
            "Accepted event time cannot precede the run"
        }
        require(attemptsStarted >= attemptedCandidateKeys.size) {
            "Lifetime attempts cannot be fewer than this cycle's attempted candidates"
        }
        require(recoveryCyclesStarted in 0..plan.budget.maxRecoveryCycles) {
            "Recovery-cycle count exceeds its bounded budget"
        }
    }

    /** Each candidate may be attempted at most once inside the current bounded cycle. */
    val attemptsStartedInCycle: Int get() = attemptedCandidateKeys.size
}

sealed interface PlaybackCompatibilityState {
    val plan: PlaybackPlan
    val sessionId: PlaybackSessionId

    data class Idle(
        override val plan: PlaybackPlan,
        override val sessionId: PlaybackSessionId,
    ) : PlaybackCompatibilityState

    data class Attempting(
        val run: PlaybackRun,
        val attempt: PlaybackAttempt,
    ) : PlaybackCompatibilityState {
        override val plan: PlaybackPlan get() = run.plan
        override val sessionId: PlaybackSessionId get() = run.sessionId
    }

    data class Releasing(
        val run: PlaybackRun,
        val attempt: PlaybackAttempt,
        val afterRelease: AfterRelease,
        val releaseDeadlineElapsedMillis: Long,
        val pendingPersistence: PersistenceInterruption? = null,
        val persistenceResolutionDeadlineElapsedMillis: Long? = null,
        val persistenceDecision: PendingStrategyPersistenceDecision? = null,
    ) : PlaybackCompatibilityState {
        override val plan: PlaybackPlan get() = run.plan
        override val sessionId: PlaybackSessionId get() = run.sessionId
    }

    data class ReleasedAwaitingPersistence(
        val run: PlaybackRun,
        val attempt: PlaybackAttempt,
        val interruption: PersistenceInterruption,
        val releaseFailed: Boolean,
        val persistenceResolutionDeadlineElapsedMillis: Long,
        val persistenceDecision: PendingStrategyPersistenceDecision? = null,
    ) : PlaybackCompatibilityState {
        override val plan: PlaybackPlan get() = run.plan
        override val sessionId: PlaybackSessionId get() = run.sessionId
    }

    data class Persisting(
        val run: PlaybackRun,
        val attempt: PlaybackAttempt,
        val strategy: VerifiedPlaybackStrategy,
        val persistenceDeadlineElapsedMillis: Long,
    ) : PlaybackCompatibilityState {
        override val plan: PlaybackPlan get() = run.plan
        override val sessionId: PlaybackSessionId get() = run.sessionId
    }

    data class Verified(
        val run: PlaybackRun,
        val attempt: PlaybackAttempt,
        val result: PlaybackResult.VerifiedLive,
    ) : PlaybackCompatibilityState {
        override val plan: PlaybackPlan get() = run.plan
        override val sessionId: PlaybackSessionId get() = run.sessionId
    }

    data class UnpersistedLive(
        val run: PlaybackRun,
        val attempt: PlaybackAttempt,
        val strategy: VerifiedPlaybackStrategy,
        val result: PlaybackResult.UnpersistedLive,
    ) : PlaybackCompatibilityState {
        override val plan: PlaybackPlan get() = run.plan
        override val sessionId: PlaybackSessionId get() = run.sessionId
    }

    /** The previous live owner is fully released; one bounded reconnect cycle is backing off. */
    data class Recovering(
        val run: PlaybackRun,
        val previousAttemptId: PlaybackAttemptId,
        val candidate: PlaybackCandidate,
        val failure: ClassifiedPlaybackFailure,
        val notBeforeElapsedMillis: Long,
    ) : PlaybackCompatibilityState {
        override val plan: PlaybackPlan get() = run.plan
        override val sessionId: PlaybackSessionId get() = run.sessionId

        init {
            require(previousAttemptId.sessionId == run.sessionId)
            require(notBeforeElapsedMillis >= run.lastAcceptedElapsedMillis)
        }
    }

    /** No live attempt remains; degraded success is pending an observed snapshot render result. */
    data class PresentingSnapshot(
        val run: PlaybackRun,
        val snapshotRouteId: SnapshotRouteId,
        val cause: PlaybackTerminalCause,
    ) : PlaybackCompatibilityState {
        override val plan: PlaybackPlan get() = run.plan
        override val sessionId: PlaybackSessionId get() = run.sessionId
    }

    data class Finished(
        override val plan: PlaybackPlan,
        override val sessionId: PlaybackSessionId,
        val result: PlaybackResult,
    ) : PlaybackCompatibilityState
}

data class PendingStrategyPersistenceDecision(
    val recordGeneration: Long?,
) {
    init {
        require(recordGeneration == null || recordGeneration > 0)
    }
}

sealed interface AfterRelease {
    data class Retry(
        val candidate: PlaybackCandidate,
    ) : AfterRelease

    data class Finish(
        val result: PlaybackResult,
    ) : AfterRelease

    data class Recover(
        val candidate: PlaybackCandidate,
        val failure: ClassifiedPlaybackFailure,
    ) : AfterRelease
}

sealed interface PersistenceInterruption {
    data class Cancellation(
        val reason: CancellationReason,
    ) : PersistenceInterruption

    data class PlaybackFailure(
        val failure: ClassifiedPlaybackFailure,
        val cancellationOverride: CancellationReason? = null,
        val recoveryEligible: Boolean = false,
    ) : PersistenceInterruption
}

data class PlaybackTransition(
    val state: PlaybackCompatibilityState,
    val commands: List<PlaybackCommand> = emptyList(),
)

/**
 * Pure compatibility state machine. Callers execute commands and feed correlated results back as
 * events; callbacks for any other attempt are harmlessly ignored.
 */
class PlaybackCompatibilityReducer(
    private val planner: PlaybackCandidatePlanner = PlaybackCandidatePlanner(),
) {
    fun initialState(
        plan: PlaybackPlan,
        sessionId: PlaybackSessionId,
    ): PlaybackCompatibilityState = PlaybackCompatibilityState.Idle(plan, sessionId)

    fun reduce(
        state: PlaybackCompatibilityState,
        event: PlaybackEvent,
    ): PlaybackTransition {
        if (event.elapsedMillis < 0) return unchanged(state)
        return when (state) {
            is PlaybackCompatibilityState.Idle -> reduceIdle(state, event)
            is PlaybackCompatibilityState.Attempting -> reduceAttempting(state, event)
            is PlaybackCompatibilityState.Releasing -> reduceReleasing(state, event)
            is PlaybackCompatibilityState.ReleasedAwaitingPersistence ->
                reduceReleasedAwaitingPersistence(state, event)
            is PlaybackCompatibilityState.Persisting -> reducePersisting(state, event)
            is PlaybackCompatibilityState.Verified -> reduceVerified(state, event)
            is PlaybackCompatibilityState.UnpersistedLive -> reduceUnpersistedLive(state, event)
            is PlaybackCompatibilityState.Recovering -> reduceRecovering(state, event)
            is PlaybackCompatibilityState.PresentingSnapshot ->
                reducePresentingSnapshot(state, event)
            is PlaybackCompatibilityState.Finished -> unchanged(state)
        }
    }

    private fun reduceIdle(
        state: PlaybackCompatibilityState.Idle,
        event: PlaybackEvent,
    ): PlaybackTransition = when (event) {
        is PlaybackEvent.Begin -> {
            val deadline = safeAdd(event.elapsedMillis, state.plan.budget.maxTotalDurationMillis)
            val run = PlaybackRun(
                plan = state.plan,
                sessionId = state.sessionId,
                startedElapsedMillis = event.elapsedMillis,
                totalDeadlineElapsedMillis = deadline,
                lastAcceptedElapsedMillis = event.elapsedMillis,
                attemptedCandidateKeys = emptySet(),
                attemptsStarted = 0,
                recoveryCyclesStarted = 0,
            )
            state.plan.candidates.firstOrNull()?.let { startAttempt(run, it, event.elapsedMillis) }
                ?: finishWithoutActiveAttempt(run, PlaybackTerminalCause.NoSafeCandidates)
        }

        is PlaybackEvent.Cancel -> PlaybackTransition(
            PlaybackCompatibilityState.Finished(
                plan = state.plan,
                sessionId = state.sessionId,
                result = PlaybackResult.Cancelled(event.reason, attemptsStarted = 0),
            ),
        )

        else -> unchanged(state)
    }

    private fun reduceAttempting(
        state: PlaybackCompatibilityState.Attempting,
        event: PlaybackEvent,
    ): PlaybackTransition {
        if (
            event !is PlaybackEvent.VideoProgress &&
            event !is PlaybackEvent.AudioProgress &&
            event.elapsedMillis < state.run.lastAcceptedElapsedMillis
        ) {
            return unchanged(state)
        }
        return when (event) {
            is PlaybackEvent.Cancel -> releaseThen(
                state = state,
                atMillis = event.elapsedMillis,
                afterRelease = AfterRelease.Finish(
                    PlaybackResult.Cancelled(event.reason, state.run.attemptsStarted),
                ),
            )

            is PlaybackEvent.FirstFrame -> onFirstFrame(state, event)
            is PlaybackEvent.VideoProgress -> onVideoProgress(state, event)
            is PlaybackEvent.AudioProgress -> onAudioProgress(state, event)
            is PlaybackEvent.AttemptFailed -> onAttemptFailed(state, event)
            is PlaybackEvent.ProbeDeadlineReached -> onProbeDeadline(state, event)
            is PlaybackEvent.Begin,
            is PlaybackEvent.SnapshotPresentationCompleted,
            is PlaybackEvent.AttemptReleased,
            is PlaybackEvent.AttemptReleaseFailed,
            is PlaybackEvent.ReleaseDeadlineReached,
            is PlaybackEvent.RecoveryDeadlineReached,
            is PlaybackEvent.StrategyPersistenceDecisionRequired,
            is PlaybackEvent.StrategyPersisted,
            is PlaybackEvent.StrategyPersistenceFailed,
            is PlaybackEvent.PersistenceResolutionDeadlineReached,
            -> unchanged(state)
        }
    }

    private fun reduceReleasing(
        state: PlaybackCompatibilityState.Releasing,
        event: PlaybackEvent,
    ): PlaybackTransition {
        if (event.elapsedMillis < state.run.lastAcceptedElapsedMillis) return unchanged(state)
        return when (event) {
            is PlaybackEvent.Cancel -> cancelWhileReleasing(state, event)

            is PlaybackEvent.AttemptReleased -> {
                if (event.attemptId != state.attempt.id) {
                    unchanged(state)
                } else if (event.elapsedMillis >= state.releaseDeadlineElapsedMillis) {
                    failRelease(state, event.elapsedMillis)
                } else if (state.pendingPersistence != null) {
                    awaitPersistenceAfterRelease(state, event.elapsedMillis, releaseFailed = false)
                } else {
                    completeRelease(state, event.elapsedMillis)
                }
            }

            is PlaybackEvent.AttemptReleaseFailed -> {
                if (event.attemptId != state.attempt.id) {
                    unchanged(state)
                } else {
                    failRelease(state, event.elapsedMillis)
                }
            }

            is PlaybackEvent.ReleaseDeadlineReached -> {
                if (
                    event.attemptId != state.attempt.id ||
                    event.elapsedMillis < state.releaseDeadlineElapsedMillis
                ) {
                    unchanged(state)
                } else {
                    failRelease(state, event.elapsedMillis)
                }
            }

            is PlaybackEvent.StrategyPersisted -> {
                if (event.attemptId != state.attempt.id || state.pendingPersistence == null) {
                    unchanged(state)
                } else {
                    resolvePersistenceWhileReleasing(
                        state = state,
                        atMillis = event.elapsedMillis,
                    )
                }
            }

            is PlaybackEvent.StrategyPersistenceDecisionRequired -> {
                if (event.attemptId != state.attempt.id || state.pendingPersistence == null) {
                    unchanged(state)
                } else {
                    val run = state.run.accept(event.elapsedMillis)
                    PlaybackTransition(
                        state = state.copy(
                            run = run,
                            persistenceDecision = PendingStrategyPersistenceDecision(
                                event.recordGeneration,
                            ),
                        ),
                        commands = listOf(
                            finalizationCommand(
                                run,
                                state.attempt,
                                event.recordGeneration,
                                state.pendingPersistence,
                            ),
                        ),
                    )
                }
            }

            is PlaybackEvent.StrategyPersistenceFailed -> {
                if (event.attemptId != state.attempt.id || state.pendingPersistence == null) {
                    unchanged(state)
                } else if (event.reason == PersistenceFailureReason.RESOLUTION_UNAVAILABLE) {
                    unchanged(state)
                } else {
                    resolvePersistenceWhileReleasing(
                        state = state,
                        atMillis = event.elapsedMillis,
                    )
                }
            }

            is PlaybackEvent.PersistenceResolutionDeadlineReached ->
                onPersistenceResolutionDeadline(state, event)

            else -> unchanged(state)
        }
    }

    private fun reduceReleasedAwaitingPersistence(
        state: PlaybackCompatibilityState.ReleasedAwaitingPersistence,
        event: PlaybackEvent,
    ): PlaybackTransition {
        if (event.elapsedMillis < state.run.lastAcceptedElapsedMillis) return unchanged(state)
        return when (event) {
            is PlaybackEvent.Cancel -> PlaybackTransition(
                state.copy(
                    run = state.run.accept(event.elapsedMillis),
                    interruption = state.interruption.withCancellation(event.reason),
                ),
            )

            is PlaybackEvent.StrategyPersisted -> {
                if (event.attemptId != state.attempt.id) {
                    unchanged(state)
                } else {
                    resolvePersistenceAfterRelease(
                        state = state,
                        atMillis = event.elapsedMillis,
                    )
                }
            }

            is PlaybackEvent.StrategyPersistenceDecisionRequired -> {
                if (event.attemptId != state.attempt.id) {
                    unchanged(state)
                } else {
                    val run = state.run.accept(event.elapsedMillis)
                    PlaybackTransition(
                        state = state.copy(
                            run = run,
                            persistenceDecision = PendingStrategyPersistenceDecision(
                                event.recordGeneration,
                            ),
                        ),
                        commands = listOf(
                            finalizationCommand(
                                run,
                                state.attempt,
                                event.recordGeneration,
                                state.interruption,
                            ),
                        ),
                    )
                }
            }

            is PlaybackEvent.StrategyPersistenceFailed -> {
                if (event.attemptId != state.attempt.id) {
                    unchanged(state)
                } else if (event.reason == PersistenceFailureReason.RESOLUTION_UNAVAILABLE) {
                    unchanged(state)
                } else {
                    resolvePersistenceAfterRelease(
                        state = state,
                        atMillis = event.elapsedMillis,
                    )
                }
            }


            is PlaybackEvent.PersistenceResolutionDeadlineReached ->
                onPersistenceResolutionDeadline(state, event)

            else -> unchanged(state)
        }
    }

    private fun reducePersisting(
        state: PlaybackCompatibilityState.Persisting,
        event: PlaybackEvent,
    ): PlaybackTransition {
        if (event.elapsedMillis < state.run.lastAcceptedElapsedMillis) return unchanged(state)
        return when (event) {
            is PlaybackEvent.Cancel -> interruptPersistence(
                state,
                PersistenceInterruption.Cancellation(event.reason),
                event.elapsedMillis,
            )

            is PlaybackEvent.StrategyPersisted -> {
                if (event.attemptId != state.attempt.id) {
                    unchanged(state)
                } else {
                    val run = state.run.accept(event.elapsedMillis)
                    val result = PlaybackResult.VerifiedLive(
                        strategy = state.strategy,
                        recordGeneration = event.recordGeneration,
                        attemptsStarted = run.attemptsStarted,
                    )
                    PlaybackTransition(
                        PlaybackCompatibilityState.Verified(run, state.attempt, result),
                    )
                }
            }

            is PlaybackEvent.StrategyPersistenceDecisionRequired -> {
                if (event.attemptId != state.attempt.id) {
                    unchanged(state)
                } else {
                    val run = state.run.accept(event.elapsedMillis)
                    PlaybackTransition(
                        state = state.copy(run = run),
                        commands = listOf(
                            finalizationCommand(
                                run,
                                state.attempt,
                                event.recordGeneration,
                                interruption = null,
                            ),
                        ),
                    )
                }
            }

            is PlaybackEvent.StrategyPersistenceFailed -> {
                if (event.attemptId != state.attempt.id) {
                    unchanged(state)
                } else {
                    persistenceFailed(state, event.elapsedMillis, event.reason)
                }
            }

            is PlaybackEvent.AttemptFailed -> {
                if (event.attemptId != state.attempt.id) {
                    unchanged(state)
                } else {
                    interruptPersistence(
                        state,
                        PersistenceInterruption.PlaybackFailure(event.failure),
                        event.elapsedMillis,
                    )
                }
            }

            else -> unchanged(state)
        }
    }

    private fun interruptPersistence(
        state: PlaybackCompatibilityState.Persisting,
        interruption: PersistenceInterruption,
        atMillis: Long,
    ): PlaybackTransition = beginInterruptedPersistenceRelease(
        run = state.run,
        attempt = state.attempt,
        interruption = interruption,
        atMillis = atMillis,
    )

    private fun beginInterruptedPersistenceRelease(
        run: PlaybackRun,
        attempt: PlaybackAttempt,
        interruption: PersistenceInterruption,
        atMillis: Long,
    ): PlaybackTransition {
        val acceptedRun = run.accept(atMillis)
        val afterRelease = afterReleaseForInterruption(acceptedRun, attempt, interruption)
        val resolutionDeadline = safeAdd(
            atMillis,
            acceptedRun.plan.budget.persistenceResolutionTimeoutMillis,
        )
        val transition = beginRelease(
            run = acceptedRun,
            attempt = attempt,
            afterRelease = afterRelease,
            initialCommands = listOf(
                PlaybackCommand.CancelVerifiedStrategyPersistence(
                    persistenceDecisionContext(acceptedRun, attempt),
                ),
            ),
            pendingPersistence = interruption,
            persistenceResolutionDeadlineElapsedMillis = resolutionDeadline,
        )
        return transition.copy(
            commands = transition.commands + PlaybackCommand.AwaitPersistenceResolutionDeadline(
                attempt.id,
                resolutionDeadline,
            ),
        )
    }

    private fun cancelWhileReleasing(
        state: PlaybackCompatibilityState.Releasing,
        event: PlaybackEvent.Cancel,
    ): PlaybackTransition {
        val run = state.run.accept(event.elapsedMillis)
        val interruption = state.pendingPersistence?.withCancellation(event.reason)
        return PlaybackTransition(
            state.copy(
                run = run,
                afterRelease = AfterRelease.Finish(
                    PlaybackResult.Cancelled(event.reason, run.attemptsStarted),
                ),
                pendingPersistence = interruption,
            ),
        )
    }

    private fun PersistenceInterruption.withCancellation(
        reason: CancellationReason,
    ): PersistenceInterruption = when (this) {
        is PersistenceInterruption.Cancellation -> copy(reason = reason)
        is PersistenceInterruption.PlaybackFailure -> copy(cancellationOverride = reason)
    }

    private fun afterReleaseForInterruption(
        run: PlaybackRun,
        attempt: PlaybackAttempt,
        interruption: PersistenceInterruption,
    ): AfterRelease = when (interruption) {
        is PersistenceInterruption.Cancellation -> AfterRelease.Finish(
            PlaybackResult.Cancelled(interruption.reason, run.attemptsStarted),
        )

        is PersistenceInterruption.PlaybackFailure -> interruption.cancellationOverride?.let {
            AfterRelease.Finish(PlaybackResult.Cancelled(it, run.attemptsStarted))
        } ?: afterReleaseForFailure(
            run = run,
            attempt = attempt,
            failure = interruption.failure,
            allowRecoveryCycle = interruption.recoveryEligible,
        )
    }

    private fun resolvePersistenceWhileReleasing(
        state: PlaybackCompatibilityState.Releasing,
        atMillis: Long,
    ): PlaybackTransition {
        val interruption = requireNotNull(state.pendingPersistence)
        val run = state.run.accept(atMillis)
        return PlaybackTransition(
            state = state.copy(
                run = run,
                afterRelease = afterReleaseForInterruption(run, state.attempt, interruption),
                pendingPersistence = null,
                persistenceResolutionDeadlineElapsedMillis = null,
                persistenceDecision = null,
            ),
            commands = listOf(
                PlaybackCommand.CancelPersistenceResolutionDeadline(state.attempt.id),
            ),
        )
    }

    private fun awaitPersistenceAfterRelease(
        state: PlaybackCompatibilityState.Releasing,
        atMillis: Long,
        releaseFailed: Boolean,
    ): PlaybackTransition {
        val commands = buildList {
            if (releaseFailed) add(PlaybackCommand.ForceReleaseAttempt(state.attempt.id))
            add(PlaybackCommand.CancelReleaseDeadline(state.attempt.id))
        }
        return PlaybackTransition(
            state = PlaybackCompatibilityState.ReleasedAwaitingPersistence(
                run = state.run.accept(atMillis),
                attempt = state.attempt,
                interruption = requireNotNull(state.pendingPersistence),
                releaseFailed = releaseFailed,
                persistenceResolutionDeadlineElapsedMillis = requireNotNull(
                    state.persistenceResolutionDeadlineElapsedMillis,
                ),
                persistenceDecision = state.persistenceDecision,
            ),
            commands = commands,
        )
    }

    private fun resolvePersistenceAfterRelease(
        state: PlaybackCompatibilityState.ReleasedAwaitingPersistence,
        atMillis: Long,
    ): PlaybackTransition {
        val run = state.run.accept(atMillis)
        val transition = if (state.releaseFailed) {
            val cancellation = state.interruption.cancellationReason()
            finishAfterRelease(
                run,
                cancellation?.let { PlaybackResult.Cancelled(it, run.attemptsStarted) }
                    ?: resultForCause(run, PlaybackTerminalCause.AttemptReleaseFailed),
            )
        } else {
            continueAfterRelease(
                run,
                state.attempt.id,
                afterReleaseForInterruption(run, state.attempt, state.interruption),
                atMillis,
            )
        }
        return transition.copy(
            commands = listOf(
                PlaybackCommand.CancelPersistenceResolutionDeadline(state.attempt.id),
            ) + transition.commands,
        )
    }

    private fun onPersistenceResolutionDeadline(
        state: PlaybackCompatibilityState.Releasing,
        event: PlaybackEvent.PersistenceResolutionDeadlineReached,
    ): PlaybackTransition {
        val deadline = state.persistenceResolutionDeadlineElapsedMillis
        if (
            event.attemptId != state.attempt.id ||
            state.pendingPersistence == null ||
            deadline == null ||
            event.elapsedMillis < deadline
        ) {
            return unchanged(state)
        }
        val run = state.run.accept(event.elapsedMillis)
        val interruption = state.pendingPersistence
        return PlaybackTransition(
            state = state.copy(
                run = run,
                afterRelease = afterReleaseForInterruption(run, state.attempt, interruption),
                pendingPersistence = null,
                persistenceResolutionDeadlineElapsedMillis = null,
                persistenceDecision = null,
            ),
            commands = listOfNotNull(
                unresolvedPersistenceQuarantine(
                    run,
                    state.attempt,
                    interruption,
                    state.persistenceDecision?.recordGeneration,
                ),
            ),
        )
    }

    private fun onPersistenceResolutionDeadline(
        state: PlaybackCompatibilityState.ReleasedAwaitingPersistence,
        event: PlaybackEvent.PersistenceResolutionDeadlineReached,
    ): PlaybackTransition {
        if (
            event.attemptId != state.attempt.id ||
            event.elapsedMillis < state.persistenceResolutionDeadlineElapsedMillis
        ) {
            return unchanged(state)
        }
        val run = state.run.accept(event.elapsedMillis)
        val quarantine = unresolvedPersistenceQuarantine(
            run,
            state.attempt,
            state.interruption,
            state.persistenceDecision?.recordGeneration,
        )
        val transition = if (state.releaseFailed) {
            val cancellation = state.interruption.cancellationReason()
            finishAfterRelease(
                run,
                cancellation?.let { PlaybackResult.Cancelled(it, run.attemptsStarted) }
                    ?: resultForCause(run, PlaybackTerminalCause.AttemptReleaseFailed),
            )
        } else {
            continueAfterRelease(
                run,
                state.attempt.id,
                afterReleaseForInterruption(run, state.attempt, state.interruption),
                event.elapsedMillis,
            )
        }
        return transition.copy(commands = listOfNotNull(quarantine) + transition.commands)
    }

    private fun unresolvedPersistenceQuarantine(
        run: PlaybackRun,
        attempt: PlaybackAttempt,
        interruption: PersistenceInterruption,
        expectedRecordGeneration: Long?,
    ): PlaybackCommand.QuarantineUnresolvedStrategyPersistence {
        val failure = (interruption as? PersistenceInterruption.PlaybackFailure)
            ?.takeIf { it.cancellationOverride == null }
            ?.failure
        val candidate = attempt.candidate
        return PlaybackCommand.QuarantineUnresolvedStrategyPersistence(
            UnresolvedStrategyPersistence(
                attemptId = attempt.id,
                identity = run.plan.identity,
                sourceScope = candidate.sourceScope,
                audioMode = candidate.audioMode,
                transportMode = candidate.transportMode,
                decoderMode = candidate.decoderMode,
                expectedRecordGeneration = expectedRecordGeneration,
                fallbackKnownGoodRecordGeneration = candidate.knownGoodRecordGeneration,
                disposition = if (failure?.implicatesKnownStrategy == true) {
                    UnresolvedPersistenceDisposition.INVALIDATE_IMPLICATED_COMMIT
                } else {
                    UnresolvedPersistenceDisposition.PRESERVE_VERIFIED_COMMIT
                },
                category = failure?.takeIf(ClassifiedPlaybackFailure::implicatesKnownStrategy)
                    ?.category,
            ),
        )
    }

    private fun PersistenceInterruption.cancellationReason(): CancellationReason? = when (this) {
        is PersistenceInterruption.Cancellation -> reason
        is PersistenceInterruption.PlaybackFailure -> cancellationOverride
    }

    private fun persistenceDecisionContext(
        run: PlaybackRun,
        attempt: PlaybackAttempt,
    ): StrategyPersistenceDecisionContext = StrategyPersistenceDecisionContext(
        attemptId = attempt.id,
        identity = run.plan.identity,
        sourceScope = attempt.candidate.sourceScope,
        audioMode = attempt.candidate.audioMode,
        transportMode = attempt.candidate.transportMode,
        decoderMode = attempt.candidate.decoderMode,
        fallbackKnownGoodRecordGeneration = attempt.candidate.knownGoodRecordGeneration,
    )

    private fun finalizationCommand(
        run: PlaybackRun,
        attempt: PlaybackAttempt,
        expectedRecordGeneration: Long?,
        interruption: PersistenceInterruption?,
    ): PlaybackCommand.FinalizeStrategyPersistence {
        val implicatedFailure = (interruption as? PersistenceInterruption.PlaybackFailure)
            ?.takeIf { it.cancellationOverride == null && it.failure.implicatesKnownStrategy }
            ?.failure
        val disposition = when {
            implicatedFailure != null ->
                StrategyPersistenceFinalizationDisposition.RECORD_IMPLICATED_FAILURE
            interruption == null && expectedRecordGeneration != null ->
                StrategyPersistenceFinalizationDisposition.ACCEPT_VERIFIED_COMMIT
            else -> StrategyPersistenceFinalizationDisposition.PRESERVE_VERIFIED_COMMIT
        }
        return PlaybackCommand.FinalizeStrategyPersistence(
            StrategyPersistenceFinalization(
                context = persistenceDecisionContext(run, attempt),
                expectedRecordGeneration = expectedRecordGeneration,
                disposition = disposition,
                category = implicatedFailure?.category,
            ),
        )
    }

    private fun reduceVerified(
        state: PlaybackCompatibilityState.Verified,
        event: PlaybackEvent,
    ): PlaybackTransition = reduceActiveLive(state.run, state.attempt, state, event)

    private fun reduceUnpersistedLive(
        state: PlaybackCompatibilityState.UnpersistedLive,
        event: PlaybackEvent,
    ): PlaybackTransition {
        if (event.elapsedMillis < state.run.lastAcceptedElapsedMillis) return unchanged(state)
        return when {
            event is PlaybackEvent.Cancel &&
                state.result.reason == PersistenceFailureReason.RESOLUTION_UNAVAILABLE ->
                beginInterruptedPersistenceRelease(
                    run = state.run,
                    attempt = state.attempt,
                    interruption = PersistenceInterruption.Cancellation(event.reason),
                    atMillis = event.elapsedMillis,
                )

            event is PlaybackEvent.StrategyPersisted &&
                event.attemptId == state.attempt.id &&
                state.result.reason == PersistenceFailureReason.RESOLUTION_UNAVAILABLE -> {
                val run = state.run.accept(event.elapsedMillis)
                PlaybackTransition(
                    PlaybackCompatibilityState.Verified(
                        run = run,
                        attempt = state.attempt,
                        result = PlaybackResult.VerifiedLive(
                            strategy = state.strategy,
                            recordGeneration = event.recordGeneration,
                            attemptsStarted = run.attemptsStarted,
                        ),
                    ),
                )
            }

            event is PlaybackEvent.AttemptFailed &&
                event.attemptId == state.attempt.id &&
                state.result.reason == PersistenceFailureReason.RESOLUTION_UNAVAILABLE ->
                beginInterruptedPersistenceRelease(
                    run = state.run,
                    attempt = state.attempt,
                    interruption = PersistenceInterruption.PlaybackFailure(
                        failure = event.failure,
                        recoveryEligible = true,
                    ),
                    atMillis = event.elapsedMillis,
                )

            else -> reduceActiveLive(state.run, state.attempt, state, event)
        }
    }

    private fun reduceRecovering(
        state: PlaybackCompatibilityState.Recovering,
        event: PlaybackEvent,
    ): PlaybackTransition {
        if (event.elapsedMillis < state.run.lastAcceptedElapsedMillis) return unchanged(state)
        return when (event) {
            is PlaybackEvent.Cancel -> PlaybackTransition(
                state = PlaybackCompatibilityState.Finished(
                    plan = state.plan,
                    sessionId = state.sessionId,
                    result = PlaybackResult.Cancelled(
                        reason = event.reason,
                        attemptsStarted = state.run.attemptsStarted,
                    ),
                ),
                commands = listOf(
                    PlaybackCommand.CancelRecoveryDeadline(state.previousAttemptId),
                ),
            )

            is PlaybackEvent.RecoveryDeadlineReached -> {
                if (
                    event.attemptId != state.previousAttemptId ||
                    event.elapsedMillis < state.notBeforeElapsedMillis
                ) {
                    unchanged(state)
                } else {
                    val recoveryRun = state.run.copy(
                        totalDeadlineElapsedMillis = safeAdd(
                            event.elapsedMillis,
                            state.plan.budget.maxTotalDurationMillis,
                        ),
                        lastAcceptedElapsedMillis = event.elapsedMillis,
                        attemptedCandidateKeys = emptySet(),
                        recoveryCyclesStarted = state.run.recoveryCyclesStarted + 1,
                    )
                    startAttempt(recoveryRun, state.candidate, event.elapsedMillis)
                }
            }

            else -> unchanged(state)
        }
    }

    private fun reducePresentingSnapshot(
        state: PlaybackCompatibilityState.PresentingSnapshot,
        event: PlaybackEvent,
    ): PlaybackTransition {
        if (event.elapsedMillis < state.run.lastAcceptedElapsedMillis) return unchanged(state)
        val result = when (event) {
            is PlaybackEvent.Cancel -> PlaybackResult.Cancelled(
                reason = event.reason,
                attemptsStarted = state.run.attemptsStarted,
            )
            is PlaybackEvent.SnapshotPresentationCompleted -> {
                if (event.snapshotRouteId != state.snapshotRouteId) return unchanged(state)
                when (event.outcome) {
                    PlaybackSnapshotPresentationOutcome.RENDERED ->
                        PlaybackResult.DegradedSnapshot(
                            snapshotRouteId = state.snapshotRouteId,
                            cause = state.cause,
                            attemptsStarted = state.run.attemptsStarted,
                        )
                    PlaybackSnapshotPresentationOutcome.FAILED,
                    PlaybackSnapshotPresentationOutcome.DENIED,
                    -> PlaybackResult.SnapshotUnavailable(
                        snapshotRouteId = state.snapshotRouteId,
                        cause = state.cause,
                        presentationOutcome = event.outcome,
                        attemptsStarted = state.run.attemptsStarted,
                    )
                }
            }
            else -> return unchanged(state)
        }
        return PlaybackTransition(
            PlaybackCompatibilityState.Finished(
                plan = state.plan,
                sessionId = state.sessionId,
                result = result,
            ),
        )
    }

    private fun reduceActiveLive(
        run: PlaybackRun,
        attempt: PlaybackAttempt,
        state: PlaybackCompatibilityState,
        event: PlaybackEvent,
    ): PlaybackTransition {
        if (event.elapsedMillis < run.lastAcceptedElapsedMillis) return unchanged(state)
        return when (event) {
            is PlaybackEvent.Cancel -> beginRelease(
                run = run.accept(event.elapsedMillis),
                attempt = attempt,
                afterRelease = AfterRelease.Finish(
                    PlaybackResult.Cancelled(event.reason, run.attemptsStarted),
                ),
            )

            is PlaybackEvent.AttemptFailed -> {
                if (event.attemptId != attempt.id) {
                    unchanged(state)
                } else {
                    val acceptedRun = run.accept(event.elapsedMillis)
                    val transition = beginRelease(
                        run = acceptedRun,
                        attempt = attempt,
                        afterRelease = afterReleaseForFailure(
                            acceptedRun,
                            attempt,
                            event.failure,
                            allowRecoveryCycle = true,
                        ),
                    )
                    val generation = when (state) {
                        is PlaybackCompatibilityState.Verified -> state.result.recordGeneration
                        is PlaybackCompatibilityState.UnpersistedLive ->
                            attempt.candidate.knownGoodRecordGeneration
                        else -> null
                    }
                    transition.copy(
                        commands = transition.commands + listOfNotNull(
                            implicatedFailureCommand(
                                identity = run.plan.identity,
                                attemptId = attempt.id,
                                candidate = attempt.candidate,
                                recordGeneration = generation,
                                failure = event.failure,
                            ),
                        ),
                    )
                }
            }

            else -> unchanged(state)
        }
    }

    private fun onFirstFrame(
        state: PlaybackCompatibilityState.Attempting,
        event: PlaybackEvent.FirstFrame,
    ): PlaybackTransition {
        if (event.attemptId != state.attempt.id ||
            state.attempt.phase != AttemptPhase.AWAITING_FIRST_FRAME
        ) {
            return unchanged(state)
        }
        if (event.elapsedMillis >= state.attempt.firstFrameDeadlineElapsedMillis) {
            return handleFailure(
                state,
                    ClassifiedPlaybackFailure(
                        category = FailureCategory.FIRST_FRAME_TIMEOUT,
                        phase = FailurePhase.FIRST_FRAME_PROBE,
                        diagnosticCode = PlaybackDiagnosticCode.FIRST_FRAME_TIMEOUT,
                    ),
                event.elapsedMillis,
            )
        }
        val stableDeadline = safeAdd(event.elapsedMillis, state.plan.budget.stableDwellMillis)
        if (stableDeadline > state.run.totalDeadlineElapsedMillis) {
            return releaseForTerminalCause(
                state,
                state.run.accept(event.elapsedMillis),
                PlaybackTerminalCause.TimeBudgetExhausted,
            )
        }
        val run = state.run.accept(event.elapsedMillis)
        val attempt = state.attempt.copy(
            phase = AttemptPhase.VERIFYING_STABILITY,
            firstFrameElapsedMillis = event.elapsedMillis,
            stableDeadlineElapsedMillis = stableDeadline,
            lastVideoProgressElapsedMillis = event.elapsedMillis,
            decoderEvidence = event.decoderEvidence,
        )
        return PlaybackTransition(
            state = PlaybackCompatibilityState.Attempting(run, attempt),
            commands = listOf(
                PlaybackCommand.AwaitStableDwell(
                    attemptId = attempt.id,
                    notBeforeElapsedMillis = stableDeadline,
                    totalDeadlineElapsedMillis = run.totalDeadlineElapsedMillis,
                ),
            ),
        )
    }

    private fun onVideoProgress(
        state: PlaybackCompatibilityState.Attempting,
        event: PlaybackEvent.VideoProgress,
    ): PlaybackTransition {
        val firstFrame = state.attempt.firstFrameElapsedMillis
        val previousProgress = state.attempt.lastVideoProgressElapsedMillis
        if (
            event.attemptId != state.attempt.id ||
            state.attempt.phase != AttemptPhase.VERIFYING_STABILITY ||
            firstFrame == null ||
            previousProgress == null ||
            event.sequence <= state.attempt.latestVideoProgressSequence ||
            event.elapsedMillis <= firstFrame ||
            event.elapsedMillis <= previousProgress
        ) {
            return unchanged(state)
        }
        if (event.elapsedMillis >= state.run.totalDeadlineElapsedMillis) {
            return releaseForTerminalCause(
                state,
                state.run.accept(event.elapsedMillis),
                PlaybackTerminalCause.TimeBudgetExhausted,
            )
        }
        return PlaybackTransition(
            PlaybackCompatibilityState.Attempting(
                run = state.run.accept(event.elapsedMillis),
                attempt = state.attempt.copy(
                    latestVideoProgressSequence = event.sequence,
                    lastVideoProgressElapsedMillis = event.elapsedMillis,
                    videoProgressEventCount = state.attempt.videoProgressEventCount + 1,
                ),
            ),
        )
    }

    private fun onAudioProgress(
        state: PlaybackCompatibilityState.Attempting,
        event: PlaybackEvent.AudioProgress,
    ): PlaybackTransition {
        val firstFrame = state.attempt.firstFrameElapsedMillis
        val previousProgress = state.attempt.lastAudioProgressElapsedMillis
        if (
            event.attemptId != state.attempt.id ||
            state.attempt.phase != AttemptPhase.VERIFYING_STABILITY ||
            state.attempt.candidate.audioMode != AudioMode.WITH_AUDIO ||
            firstFrame == null ||
            event.sequence <= state.attempt.latestAudioProgressSequence ||
            event.elapsedMillis <= firstFrame ||
            previousProgress?.let { event.elapsedMillis <= it } == true
        ) {
            return unchanged(state)
        }
        if (event.elapsedMillis >= state.run.totalDeadlineElapsedMillis) {
            return releaseForTerminalCause(
                state,
                state.run.accept(event.elapsedMillis),
                PlaybackTerminalCause.TimeBudgetExhausted,
            )
        }
        return PlaybackTransition(
            PlaybackCompatibilityState.Attempting(
                run = state.run.accept(event.elapsedMillis),
                attempt = state.attempt.copy(
                    latestAudioProgressSequence = event.sequence,
                    lastAudioProgressElapsedMillis = event.elapsedMillis,
                    audioProgressEventCount = state.attempt.audioProgressEventCount + 1,
                ),
            ),
        )
    }

    private fun onAttemptFailed(
        state: PlaybackCompatibilityState.Attempting,
        event: PlaybackEvent.AttemptFailed,
    ): PlaybackTransition {
        if (event.attemptId != state.attempt.id) return unchanged(state)
        return handleFailure(state, event.failure, event.elapsedMillis)
    }

    private fun onProbeDeadline(
        state: PlaybackCompatibilityState.Attempting,
        event: PlaybackEvent.ProbeDeadlineReached,
    ): PlaybackTransition {
        if (event.attemptId != state.attempt.id) return unchanged(state)
        return when (event.phase) {
            ProbePhase.FIRST_FRAME -> {
                if (state.attempt.phase != AttemptPhase.AWAITING_FIRST_FRAME ||
                    event.elapsedMillis < state.attempt.firstFrameDeadlineElapsedMillis
                ) {
                    unchanged(state)
                } else {
                    handleFailure(
                        state,
                        ClassifiedPlaybackFailure(
                            category = FailureCategory.FIRST_FRAME_TIMEOUT,
                            phase = FailurePhase.FIRST_FRAME_PROBE,
                            diagnosticCode = PlaybackDiagnosticCode.FIRST_FRAME_TIMEOUT,
                        ),
                        event.elapsedMillis,
                    )
                }
            }

            ProbePhase.STABLE_DWELL -> {
                val stableDeadline = state.attempt.stableDeadlineElapsedMillis
                if (state.attempt.phase != AttemptPhase.VERIFYING_STABILITY ||
                    stableDeadline == null ||
                    event.elapsedMillis < stableDeadline
                ) {
                    unchanged(state)
                } else if (event.elapsedMillis >= state.run.totalDeadlineElapsedMillis) {
                    releaseForTerminalCause(
                        state,
                        state.run.accept(event.elapsedMillis),
                        PlaybackTerminalCause.TimeBudgetExhausted,
                    )
                } else {
                    val freshnessFloor = maxOf(
                        requireNotNull(state.attempt.firstFrameElapsedMillis),
                        event.elapsedMillis - state.plan.budget.stableProgressFreshnessMillis,
                    )
                    val firstFrame = requireNotNull(state.attempt.firstFrameElapsedMillis)
                    val videoProgressIsFresh = state.attempt.videoProgressEventCount > 0 &&
                        state.attempt.lastVideoProgressElapsedMillis?.let {
                            it > firstFrame && it < event.elapsedMillis && it >= freshnessFloor
                        } == true
                    val audioProgressIsFresh =
                        state.attempt.candidate.audioMode == AudioMode.VIDEO_ONLY ||
                            state.attempt.audioProgressEventCount > 0 &&
                            state.attempt.lastAudioProgressElapsedMillis?.let {
                                it > firstFrame && it < event.elapsedMillis && it >= freshnessFloor
                            } == true
                    when {
                        !videoProgressIsFresh -> handleFailure(
                            state,
                            ClassifiedPlaybackFailure(
                                category = FailureCategory.REPEATED_BUFFERING_OR_STALL,
                                phase = FailurePhase.STABLE_DWELL_PROBE,
                                diagnosticCode = PlaybackDiagnosticCode.VIDEO_PROGRESS_STALLED,
                            ),
                            event.elapsedMillis,
                        )

                        !audioProgressIsFresh -> handleFailure(
                            state,
                            ClassifiedPlaybackFailure(
                                category = FailureCategory.AUDIO_RENDERER,
                                phase = FailurePhase.STABLE_DWELL_PROBE,
                                diagnosticCode = PlaybackDiagnosticCode.AUDIO_PROGRESS_MISSING,
                            ),
                            event.elapsedMillis,
                        )

                        else -> beginPersistence(state, event.elapsedMillis)
                    }
                }
            }

            ProbePhase.TOTAL_BUDGET -> {
                if (event.elapsedMillis < state.run.totalDeadlineElapsedMillis) {
                    unchanged(state)
                } else {
                    releaseForTerminalCause(
                        state,
                        state.run.accept(event.elapsedMillis),
                        PlaybackTerminalCause.TimeBudgetExhausted,
                    )
                }
            }
        }
    }

    private fun handleFailure(
        state: PlaybackCompatibilityState.Attempting,
        failure: ClassifiedPlaybackFailure,
        atMillis: Long,
        recordGeneration: Long? = state.attempt.candidate.knownGoodRecordGeneration,
    ): PlaybackTransition {
        val run = state.run.accept(atMillis)
        val transition = releaseThen(
            state,
            run,
            afterReleaseForFailure(run, state.attempt, failure),
        )
        val recordFailure = implicatedFailureCommand(
            identity = run.plan.identity,
            attemptId = state.attempt.id,
            candidate = state.attempt.candidate,
            recordGeneration = recordGeneration,
            failure = failure,
        )
        return transition.copy(commands = transition.commands + listOfNotNull(recordFailure))
    }

    private fun afterReleaseForFailure(
        run: PlaybackRun,
        attempt: PlaybackAttempt,
        failure: ClassifiedPlaybackFailure,
        allowRecoveryCycle: Boolean = false,
    ): AfterRelease {
        val current = attempt.candidate
        if (allowRecoveryCycle) {
            return afterReleaseForVerifiedFailure(run, current, failure)
        }
        return when {
            failure.stopsFallback -> AfterRelease.Finish(
                PlaybackResult.Blocked(failure, attemptsStarted = run.attemptsStarted),
            )

            run.lastAcceptedElapsedMillis >= run.totalDeadlineElapsedMillis -> AfterRelease.Finish(
                resultForCause(run, PlaybackTerminalCause.TimeBudgetExhausted),
            )

            run.attemptsStartedInCycle >= run.plan.budget.maxAttempts -> AfterRelease.Finish(
                resultForCause(run, PlaybackTerminalCause.AttemptBudgetExhausted),
            )

            else -> planner.selectNextCandidate(
                plan = run.plan,
                current = current,
                failure = failure,
                attempted = run.attemptedCandidateKeys,
            )?.let(AfterRelease::Retry) ?: AfterRelease.Finish(
                resultForCause(run, PlaybackTerminalCause.CandidateLadderExhausted(failure)),
            )
        }
    }

    private fun afterReleaseForVerifiedFailure(
        run: PlaybackRun,
        current: PlaybackCandidate,
        failure: ClassifiedPlaybackFailure,
    ): AfterRelease {
        if (failure.stopsFallback) {
            return AfterRelease.Finish(
                PlaybackResult.Blocked(failure, attemptsStarted = run.attemptsStarted),
            )
        }
        if (run.recoveryCyclesStarted >= run.plan.budget.maxRecoveryCycles) {
            return AfterRelease.Finish(
                resultForCause(
                    run,
                    PlaybackTerminalCause.CandidateLadderExhausted(failure),
                ),
            )
        }
        val candidate = planner.selectNextCandidate(
            plan = run.plan,
            current = current,
            failure = failure,
            attempted = if (current.alternativeConnection) setOf(current.key()) else emptySet(),
        ) ?: current.takeIf { failure.allowsBoundedSameCandidateRestart }
        return candidate?.let { AfterRelease.Recover(it, failure) }
            ?: AfterRelease.Finish(
                resultForCause(
                    run,
                    PlaybackTerminalCause.CandidateLadderExhausted(failure),
                ),
            )
    }

    private val ClassifiedPlaybackFailure.allowsBoundedSameCandidateRestart: Boolean
        get() = when (category) {
            FailureCategory.DNS_OR_ROUTE,
            FailureCategory.CONNECTION_REFUSED,
            FailureCategory.SOURCE_TIMEOUT,
            FailureCategory.RTSP_TIMEOUT,
            FailureCategory.SOURCE_UNAVAILABLE,
            FailureCategory.REPEATED_BUFFERING_OR_STALL,
            FailureCategory.STREAM_ENDED_UNEXPECTEDLY,
            FailureCategory.NETWORK,
            FailureCategory.UNKNOWN,
            -> true

            else -> false
        }

    private fun implicatedFailureCommand(
        identity: PlaybackCompatibilityIdentity,
        attemptId: PlaybackAttemptId,
        candidate: PlaybackCandidate,
        recordGeneration: Long?,
        failure: ClassifiedPlaybackFailure,
    ): PlaybackCommand.RecordImplicatedStrategyFailure? {
        if (recordGeneration == null || !failure.implicatesKnownStrategy) return null
        return PlaybackCommand.RecordImplicatedStrategyFailure(
            ImplicatedStrategyFailure(
                attemptId = attemptId,
                identity = identity,
                sourceScope = candidate.sourceScope,
                audioMode = candidate.audioMode,
                transportMode = candidate.transportMode,
                decoderMode = candidate.decoderMode,
                recordGeneration = recordGeneration,
                category = failure.category,
            ),
        )
    }

    private fun releaseForTerminalCause(
        state: PlaybackCompatibilityState.Attempting,
        run: PlaybackRun,
        cause: PlaybackTerminalCause,
    ): PlaybackTransition = releaseThen(
        state,
        run,
        AfterRelease.Finish(resultForCause(run, cause)),
    )

    private fun releaseThen(
        state: PlaybackCompatibilityState.Attempting,
        atMillis: Long,
        afterRelease: AfterRelease,
    ): PlaybackTransition = releaseThen(state, state.run.accept(atMillis), afterRelease)

    private fun releaseThen(
        state: PlaybackCompatibilityState.Attempting,
        run: PlaybackRun,
        afterRelease: AfterRelease,
    ): PlaybackTransition = beginRelease(
        run = run,
        attempt = state.attempt,
        afterRelease = afterRelease,
    )

    private fun beginRelease(
        run: PlaybackRun,
        attempt: PlaybackAttempt,
        afterRelease: AfterRelease,
        initialCommands: List<PlaybackCommand> = emptyList(),
        pendingPersistence: PersistenceInterruption? = null,
        persistenceResolutionDeadlineElapsedMillis: Long? = null,
    ): PlaybackTransition {
        val releaseDeadline = safeAdd(
            run.lastAcceptedElapsedMillis,
            run.plan.budget.releaseTimeoutMillis,
        )
        return PlaybackTransition(
            state = PlaybackCompatibilityState.Releasing(
                run = run,
                attempt = attempt.copy(phase = AttemptPhase.RELEASING),
                afterRelease = afterRelease,
                releaseDeadlineElapsedMillis = releaseDeadline,
                pendingPersistence = pendingPersistence,
                persistenceResolutionDeadlineElapsedMillis =
                    persistenceResolutionDeadlineElapsedMillis,
            ),
            commands = initialCommands + listOf(
                PlaybackCommand.ReleaseAttempt(attempt.id),
                PlaybackCommand.AwaitReleaseDeadline(attempt.id, releaseDeadline),
            ),
        )
    }

    private fun completeRelease(
        state: PlaybackCompatibilityState.Releasing,
        atMillis: Long,
    ): PlaybackTransition {
        val run = state.run.accept(atMillis)
        val transition = continueAfterRelease(
            run,
            state.attempt.id,
            state.afterRelease,
            atMillis,
        )
        return transition.copy(
            commands = listOf(PlaybackCommand.CancelReleaseDeadline(state.attempt.id)) +
                transition.commands,
        )
    }

    private fun continueAfterRelease(
        run: PlaybackRun,
        releasedAttemptId: PlaybackAttemptId,
        action: AfterRelease,
        atMillis: Long,
    ): PlaybackTransition = when (action) {
            is AfterRelease.Retry -> {
                if (atMillis >= run.totalDeadlineElapsedMillis) {
                    finishAfterRelease(run, resultForCause(run, PlaybackTerminalCause.TimeBudgetExhausted))
                } else if (run.attemptsStartedInCycle >= run.plan.budget.maxAttempts) {
                    finishAfterRelease(run, resultForCause(run, PlaybackTerminalCause.AttemptBudgetExhausted))
                } else {
                    startAttempt(run, action.candidate, atMillis)
                }
            }

            is AfterRelease.Finish -> finishAfterRelease(run, action.result)

            is AfterRelease.Recover -> {
                val notBefore = safeAdd(atMillis, run.plan.budget.recoveryBackoffMillis)
                PlaybackTransition(
                    state = PlaybackCompatibilityState.Recovering(
                        run = run.accept(atMillis),
                        previousAttemptId = releasedAttemptId,
                        candidate = action.candidate,
                        failure = action.failure,
                        notBeforeElapsedMillis = notBefore,
                    ),
                    commands = listOf(
                        PlaybackCommand.AwaitRecoveryDeadline(
                            attemptId = releasedAttemptId,
                            notBeforeElapsedMillis = notBefore,
                        ),
                    ),
                )
            }
        }

    private fun failRelease(
        state: PlaybackCompatibilityState.Releasing,
        atMillis: Long,
    ): PlaybackTransition {
        if (state.pendingPersistence != null) {
            return awaitPersistenceAfterRelease(state, atMillis, releaseFailed = true)
        }
        val run = state.run.accept(atMillis)
        val result = (state.afterRelease as? AfterRelease.Finish)
            ?.result
            ?.takeIf { it is PlaybackResult.Cancelled }
            ?: resultForCause(run, PlaybackTerminalCause.AttemptReleaseFailed)
        val transition = finishAfterRelease(run, result)
        return transition.copy(
            commands = listOf(
                PlaybackCommand.ForceReleaseAttempt(state.attempt.id),
                PlaybackCommand.CancelReleaseDeadline(state.attempt.id),
            ) + transition.commands,
        )
    }

    private fun startAttempt(
        run: PlaybackRun,
        candidate: PlaybackCandidate,
        atMillis: Long,
    ): PlaybackTransition {
        check(candidate.key() !in run.attemptedCandidateKeys) {
            "Reducer cannot start the same candidate twice"
        }
        val nextOrdinal = run.attemptsStarted + 1
        val updatedRun = run.copy(
            lastAcceptedElapsedMillis = atMillis,
            attemptedCandidateKeys = run.attemptedCandidateKeys + candidate.key(),
            attemptsStarted = nextOrdinal,
        )
        val attempt = PlaybackAttempt(
            id = PlaybackAttemptId(run.sessionId, nextOrdinal),
            candidate = candidate,
            phase = AttemptPhase.AWAITING_FIRST_FRAME,
            startedElapsedMillis = atMillis,
            firstFrameDeadlineElapsedMillis = minOf(
                safeAdd(atMillis, run.plan.budget.firstFrameTimeoutMillis),
                run.totalDeadlineElapsedMillis,
            ),
        )
        return PlaybackTransition(
            state = PlaybackCompatibilityState.Attempting(updatedRun, attempt),
            commands = listOf(
                PlaybackCommand.StartAttempt(
                    attempt = attempt,
                    totalDeadlineElapsedMillis = run.totalDeadlineElapsedMillis,
                    accessGuard = run.plan.accessGuard,
                ),
            ),
        )
    }

    private fun beginPersistence(
        state: PlaybackCompatibilityState.Attempting,
        atMillis: Long,
    ): PlaybackTransition {
        val firstFrame = requireNotNull(state.attempt.firstFrameElapsedMillis)
        val evidence = PlaybackSuccessEvidence(
            firstFrameLatencyMillis = firstFrame - state.attempt.startedElapsedMillis,
            stablePlaybackDurationMillis = atMillis - firstFrame,
            videoProgressEventCount = state.attempt.videoProgressEventCount,
            audioProgressEventCount = state.attempt.audioProgressEventCount,
            decoderEvidence = state.attempt.decoderEvidence,
        )
        val strategy = VerifiedPlaybackStrategy(
            identity = state.plan.identity,
            candidate = state.attempt.candidate,
            evidence = evidence,
        )
        val run = state.run.accept(atMillis)
        val persistenceDeadline = safeAdd(atMillis, run.plan.budget.persistenceTimeoutMillis)
        val attempt = state.attempt.copy(phase = AttemptPhase.VERIFIED)
        if (attempt.candidate.alternativeConnection) return PlaybackTransition(
            PlaybackCompatibilityState.UnpersistedLive(run, attempt, strategy,
                PlaybackResult.UnpersistedLive(attempt.candidate, PersistenceFailureReason.SESSION_ONLY_CONNECTION,
                    run.attemptsStarted)),
        )
        return PlaybackTransition(
            state = PlaybackCompatibilityState.Persisting(
                run = run,
                attempt = attempt,
                strategy = strategy,
                persistenceDeadlineElapsedMillis = persistenceDeadline,
            ),
            commands = listOf(
                PlaybackCommand.PersistVerifiedStrategy(
                    write = VerifiedStrategyWrite(
                        attemptId = attempt.id,
                        record = PersistablePlaybackStrategy.fromVerified(strategy),
                        issuedAtElapsedMillis = atMillis,
                        notAfterElapsedMillis = persistenceDeadline,
                    ),
                ),
            ),
        )
    }

    private fun persistenceFailed(
        state: PlaybackCompatibilityState.Persisting,
        atMillis: Long,
        reason: PersistenceFailureReason,
    ): PlaybackTransition {
        val run = state.run.accept(atMillis)
        val result = PlaybackResult.UnpersistedLive(
            candidate = state.attempt.candidate,
            reason = reason,
            attemptsStarted = run.attemptsStarted,
        )
        return PlaybackTransition(
            state = PlaybackCompatibilityState.UnpersistedLive(
                run = run,
                attempt = state.attempt,
                strategy = state.strategy,
                result = result,
            ),
        )
    }

    private fun finishWithoutActiveAttempt(
        run: PlaybackRun,
        cause: PlaybackTerminalCause,
    ): PlaybackTransition = finishAfterRelease(run, resultForCause(run, cause))

    private fun finishAfterRelease(
        run: PlaybackRun,
        result: PlaybackResult,
    ): PlaybackTransition {
        if (result is PlaybackResult.DegradedSnapshot) {
            return PlaybackTransition(
                state = PlaybackCompatibilityState.PresentingSnapshot(
                    run = run,
                    snapshotRouteId = result.snapshotRouteId,
                    cause = result.cause,
                ),
                commands = listOf(
                    PlaybackCommand.RenderSnapshot(
                        routeId = result.snapshotRouteId,
                        accessGuard = run.plan.accessGuard,
                    ),
                ),
            )
        }
        return PlaybackTransition(
            state = PlaybackCompatibilityState.Finished(
                plan = run.plan,
                sessionId = run.sessionId,
                result = result,
            ),
        )
    }

    private fun resultForCause(
        run: PlaybackRun,
        cause: PlaybackTerminalCause,
    ): PlaybackResult = run.plan.snapshotRouteId?.let { snapshot ->
        PlaybackResult.DegradedSnapshot(
            snapshotRouteId = snapshot,
            cause = cause,
            attemptsStarted = run.attemptsStarted,
        )
    } ?: PlaybackResult.Failed(cause, attemptsStarted = run.attemptsStarted)

    private fun PlaybackRun.accept(atMillis: Long): PlaybackRun =
        copy(lastAcceptedElapsedMillis = maxOf(lastAcceptedElapsedMillis, atMillis))

    private fun unchanged(state: PlaybackCompatibilityState): PlaybackTransition =
        PlaybackTransition(state)

    private fun safeAdd(base: Long, duration: Long): Long =
        if (Long.MAX_VALUE - base < duration) Long.MAX_VALUE else base + duration
}
