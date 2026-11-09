package app.opah.tv.ui

import kotlinx.coroutines.Job

/** Owns one open form's attempts, without retaining its fields or credentials. */
internal class ConnectionFormSubmission(
    private val onStatus: (busy: Boolean, message: String) -> Unit,
    private val onConnected: () -> Unit,
) {
    private var generation = 0L
    private var closed = false
    private var busy = false
    private var request: Job? = null

    fun submit(testOnly: Boolean, operation: ((Result<String>) -> Unit) -> Job?) {
        if (closed || busy) return
        val attempt = ++generation
        busy = true
        onStatus(true, if (testOnly) "Testing connection…" else "Connecting…")
        val started = operation { result ->
            if (!closed && attempt == generation) {
                generation++
                busy = false
                request = null
                onStatus(false, result.fold({ it }, { it.message ?: "Connection failed" }).trimEnd('.'))
                if (!testOnly && result.isSuccess) onConnected()
            }
        }
        // Validation can finish synchronously before operation returns its job.
        if (!closed && busy && attempt == generation) request = started
    }

    fun close() {
        closed = true
        generation++
        request?.cancel()
        request = null
    }
    fun cancelPending() {
        if (closed) return
        generation++
        request?.cancel(); request = null
        if (busy) { busy = false; onStatus(false, "Connection attempt stopped") }
    }
}
