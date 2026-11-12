package app.opah.tv.playback

// Adapted from Goofyschooler's PR #18 (e9d70a5); Apache-2.0, Opah contributors.
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

internal enum class HttpsLiveFailure { ACCESS_CHANGED, UNAVAILABLE, TIMEOUT, VIDEO_SIZE, FAILED, BUSY, CLEANUP_FAILED }

/** A refresh cannot extend stale evidence unless a fresh authenticated scope check succeeds. */
internal class HttpsAccessLease(
    private val localAllowed: () -> Boolean,
    private val fetchFreshScope: suspend () -> Boolean,
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val expires = AtomicLong(0L)
    fun allowed(): Boolean = elapsedMillis() < expires.get() && localAllowed()
    suspend fun refresh(): Boolean {
        val started = elapsedMillis()
        val fresh = try { withTimeoutOrNull(10_000L) { localAllowed() && fetchFreshScope() && localAllowed() } == true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        expires.set(if (fresh) started + 60_000L else 0L)
        return fresh && allowed()
    }
}

/** Foreground session watchdog. Bytes arriving without decoded progress cannot keep it alive. */
internal suspend fun runAuthorizedLiveSession(
    authorized: () -> Boolean,
    start: () -> Unit,
    firstFrame: () -> Boolean,
    failure: () -> HttpsLiveFailure?,
    close: () -> Unit,
    progress: () -> Long = { 0L },
    wantsPlayback: () -> Boolean = { true },
    elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L },
): HttpsLiveFailure? { return try {
    currentCoroutineContext().ensureActive()
    if (!authorized()) HttpsLiveFailure.ACCESS_CHANGED else {
        start()
        val started = elapsedMillis()
        var lastProgress = started
        var sequence = progress()
        var rendered = false
        while (true) {
            currentCoroutineContext().ensureActive()
            val now = elapsedMillis()
            if (firstFrame() && !rendered) { rendered = true; lastProgress = now }
            val next = progress()
            if (next != sequence || !wantsPlayback()) { sequence = next; lastProgress = now }
            val result = when {
                !authorized() -> HttpsLiveFailure.ACCESS_CHANGED
                failure() != null -> failure()
                !rendered && now - started >= 30_000L -> HttpsLiveFailure.TIMEOUT
                rendered && now - lastProgress >= 20_000L -> HttpsLiveFailure.TIMEOUT
                else -> null
            }
            if (result != null) return result
            delay(250L)
        }
        @Suppress("UNREACHABLE_CODE") null
    }
} finally { close() } }
