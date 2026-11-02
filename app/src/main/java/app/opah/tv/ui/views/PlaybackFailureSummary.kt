package app.opah.tv.ui.views

import app.opah.tv.playback.compatibility.ClassifiedPlaybackFailure
import app.opah.tv.playback.compatibility.PlaybackResult
import app.opah.tv.playback.compatibility.PlaybackTerminalCause

/** Only typed, allowlisted codes and attempt counts: never URLs or exception text. */
internal fun playbackFailureSummary(result: PlaybackResult): String {
    val detail = when (result) {
        is PlaybackResult.Failed -> terminalSummary(result.cause)
        is PlaybackResult.Blocked -> classifiedSummary(result.failure)
        is PlaybackResult.DegradedSnapshot -> "SNAPSHOT: ${terminalSummary(result.cause)}"
        is PlaybackResult.SnapshotUnavailable -> "SNAPSHOT_UNAVAILABLE: ${terminalSummary(result.cause)}"
        else -> "NO_VERIFIED_LIVE"
    }
    return "No reliable playback choice was found\n$detail\nAttempts: ${result.attemptsStarted}"
}

private fun classifiedSummary(failure: ClassifiedPlaybackFailure): String =
    "${failure.category.name} / ${failure.phase.name} / ${failure.diagnosticCode.name}"

private fun terminalSummary(cause: PlaybackTerminalCause): String = when (cause) {
    PlaybackTerminalCause.NoSafeCandidates -> "NO_SAFE_CANDIDATES"
    PlaybackTerminalCause.AttemptBudgetExhausted -> "ATTEMPT_BUDGET_EXHAUSTED"
    PlaybackTerminalCause.TimeBudgetExhausted -> "TIME_BUDGET_EXHAUSTED"
    PlaybackTerminalCause.AttemptReleaseFailed -> "ATTEMPT_RELEASE_FAILED"
    is PlaybackTerminalCause.CandidateLadderExhausted -> classifiedSummary(cause.lastFailure)
}
