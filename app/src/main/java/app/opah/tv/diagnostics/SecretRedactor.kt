package app.opah.tv.diagnostics

/** Defense-in-depth redaction for diagnostic text. Secrets should never be logged deliberately. */
object SecretRedactor {
    private const val REDACTED = "<redacted>"

    private val rules = listOf(
        Regex("""(?i)(\bBearer\s+)([^\s,;]+)"""),
        Regex("""(?i)(\b(?:cookie|set-cookie)\s*:\s*)([^\r\n]+)"""),
        Regex("""(?i)(\"(?:password|passwd|token|access_token|refresh_token|authorization|cookie|set-cookie)\"\s*:\s*\")([^\"]*)(\")"""),
        Regex("""(?i)(\b(?:password|passwd|token|access_token|refresh_token|authorization|cookie|set-cookie)\b\s*[:=]\s*)([^\s,;&]+)"""),
        Regex("""(?i)([?&](?:password|token|access_token|refresh_token|api_key)=)([^&#\s]+)"""),
        Regex("""(?i)(://[^/\s:@]+:)([^@/\s]+)(@)"""),
        Regex("""\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b"""),
    )

    fun redact(value: String): String = rules.fold(value) { redacted, rule ->
        rule.replace(redacted) { match ->
            when (match.groupValues.size) {
                2 -> REDACTED
                3 -> match.groupValues[1] + REDACTED
                4 -> match.groupValues[1] + REDACTED + match.groupValues[3]
                else -> REDACTED
            }
        }
    }

    fun safeThrowableSummary(error: Throwable): String = buildString {
        append(error::class.java.simpleName.ifBlank { "Throwable" })
        error.message?.takeIf(String::isNotBlank)?.let { append(": ").append(redact(it)) }
    }
}
