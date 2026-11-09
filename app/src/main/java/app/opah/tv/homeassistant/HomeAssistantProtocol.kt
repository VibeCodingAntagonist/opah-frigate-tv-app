package app.opah.tv.homeassistant

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString.Companion.toByteString

internal const val HA_CLIENT_ID = "https://github.com/VibeCodingAntagonist/opah-frigate-tv-app"
internal const val HA_CALLBACK = "$HA_CLIENT_ID/auth-callback"
internal const val HA_MAX_FRAME_CHARS = 65_536
internal val haJson = Json { ignoreUnknownKeys = true }

internal enum class HomeAssistantProblem(val explanation: String) {
    ADDRESS("Enter a valid Home Assistant address"),
    SIGN_IN("Sign in to Home Assistant again"),
    ACCESS_TOKEN("Refreshing the Home Assistant sign-in"),
    CALLBACK("Sign-in could not be verified — please try again"),
    NETWORK("Home Assistant is unavailable — Opah will try again"),
    RESPONSE("Home Assistant returned an unexpected response"),
    REGISTRATION("Home Assistant could not register this TV — check that Mobile App is enabled"),
    CHANNEL("Home Assistant could not open notifications for this TV"),
    STORAGE("Opah could not save the Home Assistant connection"),
}

internal class HomeAssistantException(val problem: HomeAssistantProblem) : Exception(problem.explanation)

internal fun homeAssistantBaseUrl(value: String): HttpUrl {
    if (value.length > 2_048 || value.any(Char::isISOControl)) throw HomeAssistantException(HomeAssistantProblem.ADDRESS)
    val url = runCatching { value.trim().toHttpUrl() }.getOrNull()
        ?: throw HomeAssistantException(HomeAssistantProblem.ADDRESS)
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null ||
        url.host.isBlank() ||
        (url.scheme != "https" && !localHomeAssistantHost(url.host))
    ) throw HomeAssistantException(HomeAssistantProblem.ADDRESS)
    return url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/").build()
}

private fun localHomeAssistantHost(host: String): Boolean {
    if (host == "localhost" || host == "homeassistant" || host == "::1" || host.endsWith(".local") || host.endsWith(".home.arpa")) return true
    val ipv4 = host.split('.').mapNotNull(String::toIntOrNull)
    if (ipv4.size == 4 && ipv4.all { it in 0..255 }) {
        return ipv4[0] in setOf(10, 127) || (ipv4[0] == 192 && ipv4[1] == 168) || (ipv4[0] == 172 && ipv4[1] in 16..31)
    }
    if (':' in host) {
        val prefix = host.substringBefore(':').toIntOrNull(16) ?: return false
        return prefix in 0xfc00..0xfdff || prefix in 0xfe80..0xfebf
    }
    return false
}

internal fun HttpUrl.haEndpoint(path: String): HttpUrl = newBuilder().addPathSegments(path).build()

/** Tokens and webhook capabilities must never be included in diagnostics or UI state. */
@Serializable
internal data class HomeAssistantSession(
    val server: String,
    val deviceId: String,
    val deviceName: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val webhookId: String,
    val enabled: Boolean = false,
    val showDetails: Boolean = false,
    val showImages: Boolean = false,
    val allowActions: Boolean = false,
) {
    override fun toString(): String = "HomeAssistantSession([redacted])"
}

internal interface HomeAssistantSessionStore {
    fun read(): HomeAssistantSession?
    fun write(session: HomeAssistantSession)
    fun clear()
    fun deviceId(): String
}

/** One short-lived PKCE transaction; the callback is intercepted before any external navigation. */
internal class HomeAssistantSignIn(
    val server: HttpUrl,
    val deviceName: String,
    private val startedAtMillis: Long,
    random: SecureRandom = SecureRandom(),
) {
    val verifier: String = ByteArray(32).also(random::nextBytes).toByteString().base64Url().trimEnd('=')
    private val state = ByteArray(32).also(random::nextBytes).toByteString().base64Url().trimEnd('=')
    private var consumed = false
    val authorizeUrl: HttpUrl = server.haEndpoint("auth/authorize").newBuilder()
        .addQueryParameter("client_id", HA_CLIENT_ID)
        .addQueryParameter("redirect_uri", HA_CALLBACK)
        .addQueryParameter("response_type", "code")
        .addQueryParameter("state", state)
        .addQueryParameter("code_challenge", MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII)).toByteString().base64Url().trimEnd('='))
        .addQueryParameter("code_challenge_method", "S256")
        .build()

    fun isCallback(value: String): Boolean {
        val url = runCatching { value.toHttpUrl() }.getOrNull() ?: return false
        val callback = HA_CALLBACK.toHttpUrl()
        return url.scheme == callback.scheme && url.host == callback.host && url.port == callback.port &&
            url.encodedPath == callback.encodedPath && url.username.isEmpty() && url.password.isEmpty()
    }

    fun allowsLoginNavigation(value: String): Boolean {
        val url = runCatching { value.toHttpUrl() }.getOrNull() ?: return false
        return url.scheme == server.scheme && url.host == server.host && url.port == server.port &&
            url.username.isEmpty() && url.password.isEmpty()
    }

    @Synchronized
    fun takeCode(value: String, nowMillis: Long): String {
        val url = runCatching { value.toHttpUrl() }.getOrNull()
        if (consumed || value.length > 8_192 || !isCallback(value) || nowMillis - startedAtMillis !in 0..600_000 ||
            url == null || url.fragment != null || url.queryParameterValues("state").size != 1 ||
            !MessageDigest.isEqual(state.toByteArray(), url.queryParameter("state").orEmpty().toByteArray()) ||
            url.queryParameterValues("code").size != 1 || url.queryParameter("error") != null
        ) throw HomeAssistantException(HomeAssistantProblem.CALLBACK)
        val code = url.queryParameter("code")?.takeIf { it.isNotBlank() && it.length <= 4_096 && it.none(Char::isISOControl) }
            ?: throw HomeAssistantException(HomeAssistantProblem.CALLBACK)
        consumed = true
        return code
    }

    override fun toString(): String = "HomeAssistantSignIn([redacted])"
}

internal sealed interface HomeAssistantMessage {
    data class Show(val tag: String, val title: String, val body: String,
        val image: String? = null, val actions: List<HomeAssistantNotificationAction> = emptyList(),
        val lifetimeMillis: Long = HA_NOTICE_MAX_LIFETIME_MILLIS,
        val actionData: JsonObject = JsonObject(emptyMap()),
    ) : HomeAssistantMessage {
        override fun toString(): String = "HomeAssistantMessage.Show([redacted])"
    }
    data class Clear(val tag: String) : HomeAssistantMessage
    data object Ignore : HomeAssistantMessage
}

internal const val HA_NOTICE_MAX_LIFETIME_MILLIS = 300_000L
internal data class HomeAssistantNotificationAction(val id: String, val title: String, val authenticationRequired: Boolean = false) {
    override fun toString(): String = "HomeAssistantNotificationAction([redacted])"
}

internal data class HomeAssistantDelivery(val confirmId: String?, val message: HomeAssistantMessage)

internal enum class HomeAssistantDisclosure { NONE, HIDDEN, DETAILS }

internal data class HomeAssistantNotice(val title: String, val body: String, val details: Boolean)

internal fun homeAssistantNotice(message: HomeAssistantMessage.Show, showDetails: Boolean,
    disclosure: HomeAssistantDisclosure): HomeAssistantNotice? {
    if (disclosure == HomeAssistantDisclosure.NONE) return null
    val details = showDetails && disclosure == HomeAssistantDisclosure.DETAILS
    return HomeAssistantNotice(
        if (details && message.title != "Home Assistant") "Home Assistant · ${message.title}" else "Home Assistant",
        if (details) message.body else "New notification", details)
}

internal fun parseHomeAssistantDelivery(event: JsonObject): HomeAssistantDelivery {
    val confirm = event.string("hass_confirm_id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
    val data = event["data"] as? JsonObject
    val tag = data?.string("tag")?.takeIf { it.length in 1..256 && it.none(Char::isISOControl) }
    val raw = event.string("message") ?: return HomeAssistantDelivery(confirm, HomeAssistantMessage.Ignore)
    if (raw == "clear_notification") return HomeAssistantDelivery(confirm,
        tag?.let(HomeAssistantMessage::Clear) ?: HomeAssistantMessage.Ignore)
    if (raw.startsWith("command_") || raw in setOf("TTS", "request_location_update") || raw.isBlank()) {
        return HomeAssistantDelivery(confirm, HomeAssistantMessage.Ignore)
    }
    val title = event.string("title").orEmpty().safeNotificationText(160).ifBlank { "Home Assistant" }
    val actions = (data?.get("actions") as? JsonArray).orEmpty().take(16).mapNotNull { value ->
        val action = value as? JsonObject ?: return@mapNotNull null
        val id = action.string("action")?.takeIf { it.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) && it !in setOf("URI", "REPLY") }
            ?: return@mapNotNull null
        if (action["uri"] != null || action["behavior"] != null || id.startsWith("command_")) return@mapNotNull null
        val label = action.string("title")?.safeNotificationText(48)?.replace('\n', ' ')?.takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        HomeAssistantNotificationAction(id, label, (action["authenticationRequired"] as? JsonPrimitive)?.booleanOrNull == true)
    }.distinctBy { it.id }.take(3)
    val lifetime = (data?.get("timeout") as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
        ?.coerceAtMost(300)?.times(1_000) ?: HA_NOTICE_MAX_LIFETIME_MILLIS
    val extra = (data?.get("action_data") as? JsonObject)?.takeIf { it.size <= 16 && it.toString().length <= 2_048 }
        ?: JsonObject(emptyMap())
    return HomeAssistantDelivery(confirm, HomeAssistantMessage.Show(tag ?: UUID.randomUUID().toString(), title,
        raw.safeNotificationText(1_500), data?.string("image")?.takeIf { it.length in 1..2_048 && it.none(Char::isISOControl) },
        actions, lifetime, extra))
}

internal fun JsonObject.string(key: String): String? = runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

internal fun String.safeNotificationText(limit: Int): String = filterNot { it.isISOControl() && it != '\n' }
    .filterNot { it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }.trim().take(limit)

internal fun homeAssistantNotificationKey(server: String, tag: String): String = "opah_ha_" +
    MessageDigest.getInstance("SHA-256").digest((server + "\u0000" + tag).toByteArray())
        .toByteString().hex()

internal interface HomeAssistantNotificationSink {
    suspend fun show(server: String, message: HomeAssistantMessage.Show): Boolean
    suspend fun show(session: HomeAssistantSession, message: HomeAssistantMessage.Show): Boolean = show(session.server, message)
    fun clear(server: String, tag: String)
    fun clearAll()
    fun hideOverlay()
}
