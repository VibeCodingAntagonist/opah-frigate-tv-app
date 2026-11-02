package app.opah.tv.playback.media3

import app.opah.tv.playback.compatibility.PlaybackAttemptId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** A cancelled/backgrounded view never acquires a delayed player slot. */
internal suspend fun awaitExclusiveVideoOutput(
    registry: Media3VideoOutputRegistry,
    target: Media3VideoOutputTarget,
    attempt: PlaybackAttemptId,
    isAuthorized: () -> Boolean,
    timeoutMillis: Long = 3_000L,
    elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L },
): ExclusiveVideoOutputLease? {
    require(timeoutMillis in 1..30_000L)
    val started = elapsedMillis()
    while (elapsedMillis() - started < timeoutMillis) {
        currentCoroutineContext().ensureActive()
        if (!isAuthorized()) return null
        val lease = registry.acquireExclusive(target, attempt)
        if (lease != null) {
            try {
                currentCoroutineContext().ensureActive()
                if (isAuthorized()) return lease
            } catch (error: Exception) {
                lease.finish { true } // No player was created.
                throw error
            }
            lease.finish { true }
            return null
        }
        delay(minOf(50L, timeoutMillis - (elapsedMillis() - started)).coerceAtLeast(1L))
    }
    return null
}
