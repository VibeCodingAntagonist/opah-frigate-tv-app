package app.opah.tv.playback

// Adapted from Goofyschooler's PR #18 (e9d70a5); Apache-2.0, Opah contributors.
// Local playback and stream-selection follow-up informed by megabitus98's issue #21 contribution and testing.

import app.opah.tv.data.model.Camera
import app.opah.tv.data.model.CodecCapability
import app.opah.tv.data.model.LiveStreamOption
import app.opah.tv.data.model.StreamPreference
import app.opah.tv.domain.StreamSelectionService
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

/** Authorized ticket; the explicit local mode has no server login and retains scope/privacy checks. */
internal class AuthenticatedHttpLive(
    val uri: String,
    isAuthorized: () -> Boolean,
    refreshAuthorization: suspend () -> Boolean = { isAuthorized() },
    val localTransport: LocalHttpLiveTransport? = null,
) {
    private val authorizationCheck = isAuthorized
    private val refreshCheck = refreshAuthorization
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    val isAuthorized: () -> Boolean = { !closed.get() && authorizationCheck() }
    val refreshAuthorization: suspend () -> Boolean = { !closed.get() && refreshCheck() && !closed.get() }
    fun close() { closed.set(true); localTransport?.close() }
    override fun toString(): String = "AuthenticatedHttpLive([redacted])"
}

/** Only configured origins and discovered named sources; local candidates still need a pinned transport. */
internal fun httpLiveUri(baseUrl: String, streamName: String): String? {
    if (!Regex("[A-Za-z0-9_.-]{1,160}").matches(streamName)) return null
    val base = baseUrl.toHttpUrlOrNull() ?: return null
    if (((base.scheme != "https" || base.port == 5000) && !isLocalLiveCandidate(base)) ||
        base.username.isNotEmpty() || base.password.isNotEmpty() || base.query != null || base.fragment != null ||
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
    baseUrl?.startsWith("https://", ignoreCase = true) == true && httpLiveUri(baseUrl, "probe") != null

internal fun canOfferHttpLive(baseUrl: String?): Boolean =
    baseUrl != null && httpLiveUri(baseUrl, "probe") != null

/** Use the same canonical parser as the request, never a legacy profile-port heuristic. */
internal fun isLocalHttpLiveBase(baseUrl: String?): Boolean =
    baseUrl != null && httpLiveUri(baseUrl, "probe")?.startsWith("http://") == true

internal fun useAuthenticatedLive(request: PlaybackRequest, baseUrl: String?): Boolean =
    request.kind == PlaybackKind.LIVE && !request.compatibilityTest &&
        request.httpsLiveRequestId != null && request.liveCompatibilityRequestId == null &&
        canOfferHttpLive(baseUrl)

/**
 * Only select from this camera's current eligible discovery. A connection change supplies
 * [preferred] and must not score or replace it. With Automatic quality, a Main label is not
 * evidence of an original feed; prefer camera identity, then configured order instead.
 */
internal fun selectHttpLiveStream(
    camera: Camera,
    deviceCodecs: List<CodecCapability>,
    preference: StreamPreference = StreamPreference.AUTOMATIC,
    preferred: String? = null,
): LiveStreamOption? {
    val supportedMimes = deviceCodecs.filter { it.supported }.map { it.mimeType }.toSet()
    val eligible = camera.copy(streams = camera.streams.filter {
        val mime = it.metadata?.videoCodec?.mimeType
        it.metadata?.available != false && (mime == null || mime in supportedMimes)
    })
    if (preferred != null) return eligible.streams.firstOrNull { it.streamName == preferred }
    return if (preference == StreamPreference.AUTOMATIC) {
        eligible.streams.firstOrNull { it.streamName == camera.name } ?: eligible.streams.firstOrNull()
    } else {
        StreamSelectionService().select(eligible, deviceCodecs, preference).getOrNull()?.option
    }
}

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
