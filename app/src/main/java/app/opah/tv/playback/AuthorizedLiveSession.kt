package app.opah.tv.playback

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Runs only inside the foreground lifecycle block. Cleanup includes readers before decoder release. */
internal suspend fun runAuthorizedLiveSession(
    authorized: () -> Boolean,
    start: () -> Unit,
    firstFrame: () -> Boolean,
    failure: () -> String?,
    close: () -> Unit,
    elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L },
): String? = try {
    currentCoroutineContext().ensureActive()
    if (!authorized()) "Access changed; playback stopped" else {
        start()
        val started = elapsedMillis()
        var result: String? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            result = when {
                !authorized() -> "Access changed; playback stopped"
                failure() != null -> failure()
                !firstFrame() && elapsedMillis() - started >= 30_000L -> "HTTPS first-frame timeout"
                else -> null
            }
            if (result != null) break
            delay(250L)
        }
        result
    }
} finally {
    close()
}
