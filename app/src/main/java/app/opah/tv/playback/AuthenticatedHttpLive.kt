package app.opah.tv.playback

// Adapted from Goofyschooler's PR #18 (e9d70a5); Apache-2.0, Opah contributors.

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal fun httpLiveClient(authenticatedClient: OkHttpClient): OkHttpClient =
    authenticatedClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

internal class AuthenticatedHttpLive(
    val uri: String,
    val isAuthorized: () -> Boolean,
    val refreshAuthorization: suspend () -> Boolean = { isAuthorized() },
) {
    override fun toString(): String = "AuthenticatedHttpLive([redacted])"
}

/** Only named streams from discovery, on the configured HTTPS origin. Never accepts source URLs. */
internal fun httpLiveUri(baseUrl: String, streamName: String): String? {
    if (!Regex("[A-Za-z0-9_.-]{1,160}").matches(streamName)) return null
    val base = baseUrl.toHttpUrlOrNull() ?: return null
    if (base.scheme != "https" || base.username.isNotEmpty() || base.password.isNotEmpty() ||
        base.query != null || base.fragment != null || base.port == 5000 ||
        baseUrl.any { it <= ' ' || it == '\\' } ||
        base.pathSegments.any { '/' in it || '\\' in it }
    ) return null
    return base.newBuilder()
        .addPathSegments("live/mse/api/ws")
        .addQueryParameter("src", streamName)
        .build().toString()
}

/** Only an explicit session request opts into HTTPS. Defaults/RTSP diagnostics are unchanged. */
internal fun canOfferHttpsLive(baseUrl: String?): Boolean =
    baseUrl != null && httpLiveUri(baseUrl, "probe") != null

internal fun useAuthenticatedLive(request: PlaybackRequest, baseUrl: String?): Boolean =
    request.kind == PlaybackKind.LIVE && !request.compatibilityTest &&
        request.httpsLiveRequestId != null && request.liveCompatibilityRequestId == null &&
        canOfferHttpsLive(baseUrl)

/** Explicit stream choice is never silently replaced when discovery or access changes. */
internal fun selectHttpLiveStream(cameraName: String, available: List<String>, preferred: String?): String? =
    if (preferred != null) preferred.takeIf { it in available }
    else available.firstOrNull { it == cameraName } ?: available.firstOrNull()

/** MSE camera-scoped authorization was reviewed in stable 0.18.2, not older generations. */
internal fun supportsAuthorizedHttpsLive(rawVersion: String): Boolean {
    val version = app.opah.tv.data.FrigateVersionPolicy().evaluate(rawVersion)
    return version.major == 0 && version.minor == 18 && (version.patch ?: -1) >= 2 &&
        (version.prerelease == null || Regex("[0-9a-fA-F]{7,40}").matches(version.prerelease))
}

internal fun httpsScopeOwnsStream(config: String, user: app.opah.tv.data.model.FrigateUserProfile, camera: String, stream: String): Boolean =
    camera in user.allowedCameras && httpLiveUri("https://fixture.invalid", stream) != null &&
        app.opah.tv.data.FrigateJsonParsers().parseCameras(config, emptyMap(), user.allowedCameras)
            .any { it.name == camera && it.streams.any { source -> source.streamName == stream } }
