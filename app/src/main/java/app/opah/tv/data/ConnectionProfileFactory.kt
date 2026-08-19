package app.opah.tv.data

import app.opah.tv.data.model.ConnectionProfile
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ConnectionProfileFactory {
    fun create(
        rawApiBaseUrl: String,
        username: String,
        rtspHostOverride: String? = null,
        rtspPort: Int = 8554,
    ): Result<ConnectionProfile> = runCatching {
        require(username.isNotBlank()) { "Username is required." }
        require(rtspPort in 1..65535) { "RTSP port must be between 1 and 65535." }

        val withScheme = rawApiBaseUrl.trim().let { input ->
            require(input.isNotBlank()) { "Frigate URL is required." }
            if ("://" in input) input else "https://$input"
        }
        val parsed = withScheme.toHttpUrlOrNull()
            ?: error("Enter a valid Frigate HTTP or HTTPS URL.")
        require(parsed.scheme == "https" || parsed.scheme == "http") {
            "Frigate URL must use HTTPS or HTTP."
        }
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) {
            "Do not embed credentials in the Frigate URL."
        }

        val normalizedPath = parsed.encodedPath
            .trimEnd('/')
            .removeSuffix("/api")
            .ifEmpty { "/" }
        val normalized = parsed.newBuilder()
            .encodedPath(normalizedPath)
            .query(null)
            .fragment(null)
            .build()
            .toString()
            .trimEnd('/')

        val hostOverride = rtspHostOverride
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.removePrefix("[")
            ?.removeSuffix("]")

        ConnectionProfile(
            apiBaseUrl = normalized,
            username = username.trim(),
            rtspHostOverride = hostOverride,
            rtspPort = rtspPort,
        )
    }
}

