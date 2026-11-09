package app.opah.tv.homeassistant

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

internal data class HomeAssistantDevice(val manufacturer: String, val model: String, val osVersion: String, val appVersion: String)

internal class HomeAssistantApiClient(
    val client: OkHttpClient = defaultClient(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val actionClient = client.newBuilder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).cache(null).cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .callTimeout(5, TimeUnit.SECONDS).build()

    fun notificationActionCall(session: HomeAssistantSession, message: HomeAssistantMessage.Show,
        action: HomeAssistantNotificationAction): Call {
        val body = buildJsonObject {
            put("type", "fire_event")
            put("data", buildJsonObject {
                put("event_type", "mobile_app_notification_action")
                put("event_data", buildJsonObject {
                    put("action", action.id); put("tag", message.tag)
                    if (message.actionData.isNotEmpty()) put("action_data", message.actionData)
                })
            })
        }
        return actionClient.newCall(Request.Builder()
            .url(homeAssistantBaseUrl(session.server).haEndpoint("api/webhook/").newBuilder().addPathSegment(session.webhookId).build())
            .post(body.toString().toRequestBody(JSON_TYPE)).build())
    }

    suspend fun sendNotificationAction(call: Call): Boolean = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resume(false) }
            override fun onResponse(call: Call, response: Response) {
                val accepted = response.use { it.isSuccessful }
                if (continuation.isActive) continuation.resume(accepted)
            }
        })
    }

    suspend fun connect(signIn: HomeAssistantSignIn, code: String, deviceId: String, device: HomeAssistantDevice): HomeAssistantSession {
        val tokens = request(Request.Builder().url(signIn.server.haEndpoint("auth/token")).post(FormBody.Builder()
            .add("grant_type", "authorization_code").add("code", code).add("client_id", HA_CLIENT_ID)
            .add("code_verifier", signIn.verifier).build()).build())
        val refresh = tokens.token("refresh_token")
        return try {
            val access = tokens.token("access_token")
            val expires = expiry(tokens)
            val body = buildJsonObject {
                put("app_id", "app.opah.tv")
                put("app_name", "Opah")
                put("app_version", device.appVersion)
                put("device_id", deviceId)
                put("device_name", signIn.deviceName)
                put("manufacturer", device.manufacturer)
                put("model", device.model)
                put("os_name", "Android")
                put("os_version", device.osVersion)
                put("supports_encryption", false)
                put("app_data", buildJsonObject { put("push_websocket_channel", true) })
            }
            // Registration is attempted once, following the user's sign-in. Reconnection never repeats it.
            val registration =
                request(Request.Builder().url(signIn.server.haEndpoint("api/mobile_app/registrations"))
                    .header("Authorization", "Bearer $access").post(body.toString().toRequestBody(JSON_TYPE)).build(), registration = true)
            val webhook = registration.string("webhook_id")?.takeIf { it.length in 16..256 && it.none(Char::isISOControl) }
                ?: throw HomeAssistantException(HomeAssistantProblem.REGISTRATION)
            HomeAssistantSession(signIn.server.toString(), deviceId, signIn.deviceName, access, refresh, expires, webhook)
        } catch (error: Exception) {
            revokeBestEffort(signIn.server, refresh)
            throw error
        }
    }

    suspend fun refresh(session: HomeAssistantSession): HomeAssistantSession {
        val response = request(Request.Builder().url(homeAssistantBaseUrl(session.server).haEndpoint("auth/token"))
            .post(FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", session.refreshToken)
                .add("client_id", HA_CLIENT_ID).build()).build())
        return session.copy(accessToken = response.token("access_token"), expiresAtMillis = expiry(response))
    }

    suspend fun checkAvailability(session: HomeAssistantSession) {
        val response = request(Request.Builder().url(homeAssistantBaseUrl(session.server).haEndpoint("api/"))
            .header("Authorization", "Bearer ${session.accessToken}").get().build())
        if (response.string("message") != "API running.") throw HomeAssistantException(HomeAssistantProblem.RESPONSE)
    }

    suspend fun revoke(server: HttpUrl, refreshToken: String) {
        try {
            request(Request.Builder().url(server.haEndpoint("auth/revoke"))
                .post(FormBody.Builder().add("token", refreshToken).build()).build(), emptyAllowed = true, allowMissingEndpoint = true)
        } catch (_: MissingEndpoint) {
            request(Request.Builder().url(server.haEndpoint("auth/token"))
                .post(FormBody.Builder().add("token", refreshToken).add("action", "revoke").build()).build(), emptyAllowed = true)
        }
    }

    /** Complete bounded cleanup even if the user closes the connection form mid-request. */
    suspend fun revokeBestEffort(server: HttpUrl, refreshToken: String) = withContext(NonCancellable) {
        withTimeoutOrNull(5_000) { runCatching { revoke(server, refreshToken) } }
        Unit
    }

    private fun expiry(value: JsonObject): Long {
        val seconds = value["expires_in"]?.jsonPrimitive?.longOrNull?.takeIf { it in 60..86_400 }
            ?: throw HomeAssistantException(HomeAssistantProblem.RESPONSE)
        if (!value.string("token_type").equals("Bearer", ignoreCase = true)) throw HomeAssistantException(HomeAssistantProblem.RESPONSE)
        return nowMillis() + seconds * 1_000
    }

    private fun JsonObject.token(name: String): String = string(name)?.takeIf {
        it.length in 1..16_384 && it.none(Char::isISOControl)
    } ?: throw HomeAssistantException(HomeAssistantProblem.RESPONSE)

    private class MissingEndpoint : Exception()

    private suspend fun request(request: Request, registration: Boolean = false, emptyAllowed: Boolean = false,
        allowMissingEndpoint: Boolean = false): JsonObject =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(HomeAssistantException(HomeAssistantProblem.NETWORK))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (it.code == 404 && allowMissingEndpoint) throw MissingEndpoint()
                            if (!it.isSuccessful) throw HomeAssistantException(when {
                                registration -> HomeAssistantProblem.REGISTRATION
                                it.code in setOf(400, 401, 403) -> HomeAssistantProblem.SIGN_IN
                                else -> HomeAssistantProblem.NETWORK
                            })
                            val source = it.body.source()
                            val buffer = Buffer()
                            var remaining = 262_145L
                            while (remaining > 0) {
                                val read = source.read(buffer, minOf(8_192L, remaining))
                                if (read == -1L) break
                                remaining -= read
                            }
                            val bytes = buffer.readByteArray()
                            if (bytes.size > 262_144) throw HomeAssistantException(HomeAssistantProblem.RESPONSE)
                            if (emptyAllowed && bytes.isEmpty()) JsonObject(emptyMap())
                            else haJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                        }
                    }
                    if (continuation.isActive) continuation.resumeWith(result.fold(
                        onSuccess = { Result.success(it) },
                        onFailure = { Result.failure(if (it is HomeAssistantException || it is MissingEndpoint) it else HomeAssistantException(HomeAssistantProblem.RESPONSE)) },
                    ))
                }
            })
        }

    companion object {
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).pingInterval(30, TimeUnit.SECONDS).build()
    }
}
