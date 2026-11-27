package app.opah.tv.data

import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.model.isDefaultFrigateInternalPort
import java.net.URI
import java.util.Locale

/** Canonical profile identity input shared by playback, Awareness, and notification persistence. */
internal fun ConnectionProfile.compatibilityIdentityComponents(): Map<String, String> {
    val api = URI(apiBaseUrl)
    val scheme = api.scheme?.lowercase(Locale.ROOT) ?: error("Frigate URL is invalid")
    val apiHost = api.host?.lowercase(Locale.ROOT) ?: error("Frigate URL is invalid")
    val apiPort = if (api.port >= 0) api.port else when (scheme) {
        "https" -> 443
        "http" -> 80
        else -> error("Frigate URL is invalid")
    }
    val components = mapOf(
        "api_scheme" to scheme,
        "api_host" to apiHost,
        "api_port" to apiPort.toString(),
        "api_path" to (api.path?.trimEnd('/') ?: ""),
        "username" to username,
        "rtsp_host" to (rtspHostOverride ?: apiHost).lowercase(Locale.ROOT),
        "rtsp_port" to rtspPort.toString(),
    )
    // Keep legacy bindings stable while separating access modes on the same address.
    return if (withoutSignIn != isDefaultFrigateInternalPort(apiBaseUrl)) {
        components + ("without_sign_in" to withoutSignIn.toString())
    } else components
}
