package app.opah.tv.data.network

import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.model.FrigateUserProfile
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.ReviewSearchQuery
import app.opah.tv.data.model.ReviewSeverity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class FrigateApiClient(
    val httpClient: OkHttpClient,
    private val cookieJar: SessionCookieStore,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : FrigateGateway {
    override suspend fun login(profile: ConnectionProfile, password: String): FrigateUserProfile {
        require(password.isNotEmpty()) { "Password is required." }
        cookieJar.clear()
        val payload = buildString {
            append("{\"user\":")
            append(json.encodeToString(profile.username))
            append(",\"password\":")
            append(json.encodeToString(password))
            append('}')
        }
        execute(
            Request.Builder()
                .url(apiUrl(profile, "login"))
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
            invalidCredentials = true,
        )
        if (!cookieJar.hasUnexpiredSession()) {
            throw OpahException(
                OpahFailure(
                    OpahErrorCode.INVALID_RESPONSE,
                    "Frigate accepted the login but did not return a usable session cookie.",
                    RecoveryAction.RETRY,
                    retryable = true,
                ),
            )
        }
        return getProfile(profile)
    }

    override suspend fun refreshSession(profile: ConnectionProfile): FrigateUserProfile {
        if (!cookieJar.hasUnexpiredSession()) {
            throw AuthenticationExpiredException("The saved Frigate session has expired. Sign in again.")
        }
        execute(Request.Builder().url(apiUrl(profile, "auth")).get().build())
        return getProfile(profile)
    }

    override suspend fun logout(profile: ConnectionProfile) {
        runCatching {
            execute(
                Request.Builder().url(apiUrl(profile, "logout")).get().build(),
                acceptedStatusCodes = setOf(303),
            )
        }
        cookieJar.clear()
    }

    override suspend fun getVersion(profile: ConnectionProfile): String =
        executeText(Request.Builder().url(apiUrl(profile, "version")).get().build()).trim()

    override suspend fun getConfig(profile: ConnectionProfile): String =
        executeText(Request.Builder().url(apiUrl(profile, "config")).get().build())

    override suspend fun getStats(profile: ConnectionProfile): String =
        executeText(Request.Builder().url(apiUrl(profile, "stats")).get().build())

    override suspend fun getRecordingsStorage(profile: ConnectionProfile): String =
        executeText(Request.Builder().url(apiUrl(profile, "recordings", "storage")).get().build())

    override suspend fun getGo2RtcStreams(profile: ConnectionProfile): String =
        executeText(Request.Builder().url(apiUrl(profile, "go2rtc", "streams")).get().build())

    override suspend fun getGo2RtcStream(profile: ConnectionProfile, streamName: String): String =
        executeText(
            Request.Builder()
                .url(apiUrl(profile, "go2rtc", "streams", streamName))
                .get()
                .build(),
        )

    override suspend fun getReview(profile: ConnectionProfile, query: ReviewSearchQuery): String {
        require(query.cameras.isNotEmpty()) { "At least one permitted camera is required." }
        require(query.before == null || query.after == null || query.before >= query.after) {
            "Review range end must not precede its start."
        }
        val builder = apiUrl(profile, "review").newBuilder()
            .addQueryParameter("cameras", query.cameras.sorted().joinToString(","))
            .addQueryParameter("limit", query.limit.coerceIn(1, 200).toString())
        val severity = when (query.severity) {
            ReviewSeverity.ALERT -> "alert"
            ReviewSeverity.DETECTION -> "detection"
            ReviewSeverity.UNKNOWN, null -> null
        }
        severity?.let { builder.addQueryParameter("severity", it) }
        query.label?.takeIf(String::isNotBlank)?.let { builder.addQueryParameter("labels", it) }
        query.zone?.takeIf(String::isNotBlank)?.let { builder.addQueryParameter("zones", it) }
        query.reviewed?.let { builder.addQueryParameter("reviewed", if (it) "1" else "0") }
        query.after?.let { builder.addQueryParameter("after", it.toString()) }
        query.before?.let { builder.addQueryParameter("before", it.toString()) }
        val url = builder.build()
        return executeText(Request.Builder().url(url).get().build())
    }

    override suspend fun setReviewsViewed(
        profile: ConnectionProfile,
        reviewIds: Set<String>,
        reviewed: Boolean,
    ) {
        require(reviewIds.isNotEmpty()) { "At least one Review item is required." }
        require(reviewIds.none(String::isBlank)) { "Review item IDs cannot be blank." }
        val payload = buildString {
            append("{\"ids\":[")
            append(reviewIds.sorted().joinToString(",") { json.encodeToString(it) })
            append("],\"reviewed\":")
            append(reviewed)
            append('}')
        }
        execute(
            Request.Builder()
                .url(apiUrl(profile, "reviews", "viewed"))
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
    }

    override suspend fun getRecordings(
        profile: ConnectionProfile,
        camera: String,
        after: Double,
        before: Double,
    ): String {
        require(before >= after) { "Recording range end must not precede its start." }
        val url = apiUrl(profile, camera, "recordings").newBuilder()
            .addQueryParameter("after", after.toString())
            .addQueryParameter("before", before.toString())
            .build()
        return executeText(Request.Builder().url(url).get().build())
    }

    override fun reviewPlaybackUrl(profile: ConnectionProfile, item: ReviewItem): String {
        val start = (item.startTime - REVIEW_PADDING_SECONDS).coerceAtLeast(0.0)
        val end = (item.endTime ?: (System.currentTimeMillis() / 1000.0)) + REVIEW_PADDING_SECONDS
        return rootUrl(
            profile,
            "vod",
            item.camera,
            "start",
            start.toString(),
            "end",
            end.toString(),
            "master.m3u8",
        ).toString()
    }

    private suspend fun getProfile(profile: ConnectionProfile): FrigateUserProfile {
        val raw = executeText(Request.Builder().url(apiUrl(profile, "profile")).get().build())
        val obj = json.parseToJsonElement(raw) as? JsonObject
            ?: throw OpahException(
                OpahFailure(
                    OpahErrorCode.INVALID_RESPONSE,
                    "Frigate returned an invalid profile response.",
                    RecoveryAction.RETRY,
                    retryable = true,
                ),
            )
        val username = obj["username"]?.jsonPrimitive?.contentOrNull ?: profile.username
        val role = obj["role"]?.jsonPrimitive?.contentOrNull ?: "viewer"
        val allowed = (obj["allowed_cameras"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.toSet()
            ?: emptySet()
        return FrigateUserProfile(username, role, allowed)
    }

    private suspend fun executeText(request: Request): String = withContext(Dispatchers.IO) {
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throwStatus(response.code)
                response.body.string()
            }
        } catch (error: OpahException) {
            throw error
        } catch (error: Throwable) {
            throw translate(error)
        }
    }

    private suspend fun execute(
        request: Request,
        invalidCredentials: Boolean = false,
        acceptedStatusCodes: Set<Int> = emptySet(),
    ) =
        withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful && response.code !in acceptedStatusCodes) {
                        throwStatus(response.code, invalidCredentials)
                    }
                }
            } catch (error: OpahException) {
                throw error
            } catch (error: Throwable) {
                throw translate(error)
            }
        }

    private fun throwStatus(code: Int, invalidCredentials: Boolean = false): Nothing {
        if (code == 401 && invalidCredentials) throw InvalidCredentialsException()
        if (code == 401 && !invalidCredentials) throw AuthenticationExpiredException()
        throw OpahException(apiFailureForStatus(code))
    }

    private fun translate(error: Throwable): OpahException = when (error) {
        is SSLPeerUnverifiedException, is SSLHandshakeException -> OpahException(
            OpahFailure(
                OpahErrorCode.TLS_FAILURE,
                "TLS certificate validation failed. Opah does not bypass certificate checks.",
                RecoveryAction.CHECK_SERVER_URL,
                retryable = false,
            ),
            error,
        )
        is UnknownHostException -> OpahException(
            OpahFailure(
                OpahErrorCode.DNS_FAILURE,
                "The Frigate hostname could not be resolved.",
                RecoveryAction.CHECK_CONNECTION,
                retryable = true,
            ),
            error,
        )
        is SocketTimeoutException -> OpahException(
            OpahFailure(
                OpahErrorCode.TIMEOUT,
                "The Frigate connection timed out.",
                RecoveryAction.RETRY,
                retryable = true,
            ),
            error,
        )
        is ConnectException -> OpahException(
            OpahFailure(
                OpahErrorCode.CONNECTION_REFUSED,
                "The Frigate server refused the connection.",
                RecoveryAction.CHECK_CONNECTION,
                retryable = true,
            ),
            error,
        )
        else -> OpahException(
            OpahFailure(
                OpahErrorCode.UNKNOWN,
                "The Frigate request failed.",
                RecoveryAction.RETRY,
                retryable = true,
            ),
            error,
        )
    }

    private fun apiUrl(profile: ConnectionProfile, vararg segments: String): HttpUrl =
        profile.apiBaseUrl.toHttpUrl().newBuilder()
            .addPathSegment("api")
            .apply { segments.forEach(::addPathSegment) }
            .build()

    private fun rootUrl(profile: ConnectionProfile, vararg segments: String): HttpUrl =
        profile.apiBaseUrl.toHttpUrl().newBuilder()
            .apply { segments.forEach(::addPathSegment) }
            .build()

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val REVIEW_PADDING_SECONDS = 8.0

        fun defaultClient(cookieJar: CookieJar): OkHttpClient = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            // Keep credentials and cookies on the canonical URL the user entered.
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

internal fun apiFailureForStatus(code: Int): OpahFailure = when (code) {
    408 -> OpahFailure(
        OpahErrorCode.TIMEOUT,
        "Frigate timed out while processing the request.",
        RecoveryAction.RETRY,
        retryable = true,
    )
    403 -> OpahFailure(
        OpahErrorCode.PERMISSION_DENIED,
        "This Frigate account does not have permission for that operation.",
        RecoveryAction.USE_DIFFERENT_ACCOUNT,
        retryable = false,
    )
    404 -> OpahFailure(
        OpahErrorCode.NOT_FOUND,
        "The requested Frigate endpoint was not found.",
        RecoveryAction.CHECK_SERVER_URL,
        retryable = false,
    )
    429 -> OpahFailure(
        OpahErrorCode.RATE_LIMITED,
        "Frigate received too many requests. Wait before trying again.",
        RecoveryAction.RETRY,
        retryable = true,
    )
    in 300..399 -> OpahFailure(
        OpahErrorCode.REDIRECT_REJECTED,
        "Frigate redirected the API request. Enter the server's canonical base URL.",
        RecoveryAction.CHECK_SERVER_URL,
        retryable = false,
    )
    in 500..599 -> OpahFailure(
        OpahErrorCode.SERVER_ERROR,
        "Frigate returned a server error ($code).",
        RecoveryAction.RETRY,
        retryable = true,
    )
    else -> OpahFailure(
        OpahErrorCode.INVALID_RESPONSE,
        "Frigate returned HTTP $code.",
        RecoveryAction.RETRY,
        retryable = code >= 500,
    )
}
