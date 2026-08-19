package app.opah.tv.diagnostics

import android.util.Log

interface OpahLogger {
    fun info(event: String, detail: String? = null)
    fun warning(event: String, error: Throwable? = null)
}

/** Logs event names and redacted summaries only; raw request/response bodies are forbidden. */
class AndroidOpahLogger(private val tag: String = "Opah") : OpahLogger {
    override fun info(event: String, detail: String?) {
        Log.i(tag, safeMessage(event, detail))
    }

    override fun warning(event: String, error: Throwable?) {
        val summary = error?.let(SecretRedactor::safeThrowableSummary)
        Log.w(tag, safeMessage(event, summary))
    }

    private fun safeMessage(event: String, detail: String?): String = buildString {
        append(SecretRedactor.redact(event))
        detail?.takeIf(String::isNotBlank)?.let { append(": ").append(SecretRedactor.redact(it)) }
    }
}
