package app.opah.tv.data.network

import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.model.EventSearchQuery
import app.opah.tv.data.model.FrigateUserProfile
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.ReviewSearchQuery
import app.opah.tv.data.model.ReviewSeverity
import app.opah.tv.data.model.RecordingExport
import app.opah.tv.data.model.BatchExportRequest
import app.opah.tv.data.model.IncidentDraft
import app.opah.tv.data.model.MotionSearchRequest
import app.opah.tv.data.model.isSafeLiteralLicensePlateFilter
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
    private val noSignInClient by lazy { httpClient.newBuilder().cookieJar(CookieJar.NO_COOKIES).build() }

    override suspend fun login(profile: ConnectionProfile, password: String): FrigateUserProfile {
        cookieJar.clear()
        if (profile.withoutSignIn) return getProfile(profile)
        require(password.isNotEmpty()) { "Password is required." }
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
        if (profile.withoutSignIn) return getProfile(profile)
        if (!cookieJar.hasUnexpiredSession()) {
            throw AuthenticationExpiredException("The saved Frigate session has expired. Sign in again.")
        }
        execute(Request.Builder().url(apiUrl(profile, "auth")).get().build())
        return getProfile(profile)
    }

    override suspend fun logout(profile: ConnectionProfile) {
        if (!profile.withoutSignIn) {
            runCatching {
                execute(
                    Request.Builder().url(apiUrl(profile, "logout")).get().build(),
                    acceptedStatusCodes = setOf(303),
                )
            }
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

    override suspend fun getPtzInfo(profile: ConnectionProfile, camera: String): String {
        require(camera.isNotBlank()) { "Camera is required." }
        return executeText(
            Request.Builder()
                .url(apiUrl(profile, camera, "ptz", "info"))
                .get()
                .build(),
        )
    }

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
            ReviewSeverity.SIGNIFICANT_MOTION,
            ReviewSeverity.UNKNOWN,
            null,
            -> null
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

    override suspend fun getReviewSummary(
        profile: ConnectionProfile,
        cameras: Set<String>,
        timezone: String,
    ): String {
        require(cameras.isNotEmpty()) { "At least one permitted camera is required." }
        val url = apiUrl(profile, "review", "summary").newBuilder()
            .addQueryParameter("cameras", cameras.sorted().joinToString(","))
            .addQueryParameter("timezone", timezone)
            .build()
        return executeText(Request.Builder().url(url).get().build())
    }

    override suspend fun getReviewMotionActivity(
        profile: ConnectionProfile,
        cameras: Set<String>,
        after: Double,
        before: Double,
    ): String {
        require(cameras.isNotEmpty()) { "At least one permitted camera is required." }
        require(before >= after) { "Motion range end must not precede its start." }
        val url = apiUrl(profile, "review", "activity", "motion").newBuilder()
            .addQueryParameter("cameras", cameras.sorted().joinToString(","))
            .addQueryParameter("after", after.toString())
            .addQueryParameter("before", before.toString())
            .addQueryParameter("scale", "30")
            .build()
        return executeText(Request.Builder().url(url).get().build())
    }

    override suspend fun getEventsByIds(
        profile: ConnectionProfile,
        eventIds: Set<String>,
    ): String {
        require(eventIds.isNotEmpty()) { "At least one event is required." }
        require(eventIds.none(String::isBlank)) { "Event IDs cannot be blank." }
        val url = apiUrl(profile, "event_ids").newBuilder()
            .addQueryParameter("ids", eventIds.sorted().joinToString(","))
            .build()
        return executeText(Request.Builder().url(url).get().build())
    }

    override suspend fun searchEvents(profile: ConnectionProfile, query: EventSearchQuery): String {
        val text = query.text?.trim().orEmpty()
        val eventId = query.eventId?.trim().orEmpty()
        val recognizedLicensePlate = query.recognizedLicensePlate?.trim().orEmpty()
        require(text.isNotEmpty() || eventId.isNotEmpty()) { "Search text or an activity item is required." }
        require(text.length <= MAX_SEARCH_TEXT_LENGTH) { "Search text is too long." }
        require(query.cameras.isNotEmpty()) { "At least one permitted camera is required." }
        require(query.before == null || query.after == null || query.before >= query.after) {
            "Search range end must not precede its start."
        }
        require(
            recognizedLicensePlate.isEmpty() || isSafeLiteralLicensePlateFilter(recognizedLicensePlate),
        ) { "License plate filters may contain only letters, numbers, spaces, hyphens, or underscores." }
        val builder = apiUrl(profile, "events", "search").newBuilder()
            .addQueryParameter("search_type", if (eventId.isNotEmpty()) "similarity" else "thumbnail")
            .addQueryParameter("include_thumbnails", "0")
            .addQueryParameter("cameras", query.cameras.sorted().joinToString(","))
            .addQueryParameter("limit", query.limit.coerceIn(1, 100).toString())
        text.takeIf(String::isNotEmpty)?.let { builder.addQueryParameter("query", it) }
        eventId.takeIf(String::isNotEmpty)?.let { builder.addQueryParameter("event_id", it) }
        query.label?.takeIf(String::isNotBlank)?.let { builder.addQueryParameter("labels", it) }
        query.subLabel?.takeIf(String::isNotBlank)?.let { builder.addQueryParameter("sub_labels", it) }
        query.zone?.takeIf(String::isNotBlank)?.let { builder.addQueryParameter("zones", it) }
        recognizedLicensePlate.takeIf(String::isNotEmpty)?.let {
            builder.addQueryParameter("recognized_license_plate", it)
        }
        query.after?.let { builder.addQueryParameter("after", it.toString()) }
        query.before?.let { builder.addQueryParameter("before", it.toString()) }
        return executeText(Request.Builder().url(builder.build()).get().build())
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

    override suspend fun getRecordingSummary(
        profile: ConnectionProfile,
        camera: String,
        timezone: String,
    ): String {
        require(camera.isNotBlank()) { "Camera is required." }
        val url = apiUrl(profile, camera, "recordings", "summary").newBuilder()
            .addQueryParameter("timezone", timezone)
            .build()
        return executeText(Request.Builder().url(url).get().build())
    }

    override suspend fun getExports(profile: ConnectionProfile): String =
        executeText(Request.Builder().url(apiUrl(profile, "exports")).get().build())

    override suspend fun startRecordingExport(
        profile: ConnectionProfile,
        camera: String,
        startTime: Double,
        endTime: Double,
        name: String,
    ): String {
        require(camera.isNotBlank()) { "Camera is required." }
        require(endTime > startTime) { "Recording end must follow its start." }
        require(name.isNotBlank() && name.length <= 256) { "Clip name is invalid." }
        val payload = buildString {
            append("{\"playback\":\"realtime\",\"source\":\"recordings\",\"name\":")
            append(json.encodeToString(name))
            append('}')
        }
        return executeText(
            Request.Builder()
                .url(
                    apiUrl(
                        profile,
                        "export",
                        camera,
                        "start",
                        startTime.coerceAtLeast(0.0).toString(),
                        "end",
                        endTime.toString(),
                    ),
                )
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
    }

    override suspend fun deleteExport(profile: ConnectionProfile, exportId: String) {
        require(exportId.isNotBlank()) { "Saved recording is required." }
        executeText(
            Request.Builder()
                .url(apiUrl(profile, "export", exportId))
                .delete()
                .build(),
        )
    }

    override suspend fun deleteExports(profile: ConnectionProfile, exportIds: Set<String>) {
        require(exportIds.isNotEmpty() && exportIds.none(String::isBlank)) { "Saved recording is required." }
        val payload = "{\"ids\":${json.encodeToString(exportIds.toList())}}"
        executeText(
            Request.Builder()
                .url(apiUrl(profile, "exports", "delete"))
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
    }

    override suspend fun getProfiles(profile: ConnectionProfile): String =
        getJson(profile, "profiles")

    override suspend fun getActiveProfile(profile: ConnectionProfile): String =
        getJson(profile, "profile", "active")

    override suspend fun setActiveProfile(profile: ConnectionProfile, profileName: String?): String {
        val value = profileName?.trim()?.takeIf(String::isNotEmpty) ?: "none"
        require(value.length <= 100) { "Mode name is too long." }
        return putJson(profile, arrayOf("camera", "*", "set", "profile"), """{"value":${json.encodeToString(value)}}""")
    }

    override suspend fun startMotionSearch(
        profile: ConnectionProfile,
        request: MotionSearchRequest,
    ): String {
        val points = request.polygon.joinToString(separator = ",", prefix = "[", postfix = "]") { point ->
            "[${point.x},${point.y}]"
        }
        val payload = """{"start_time":${request.startTime},"end_time":${request.endTime},"polygon_points":$points,"threshold":${request.threshold},"min_area":${request.minimumAreaPercent},"parallel":${request.parallel},"max_results":${request.maximumResults}}"""
        return postJson(profile, arrayOf(request.camera, "search", "motion"), payload)
    }

    override suspend fun getMotionSearch(
        profile: ConnectionProfile,
        camera: String,
        jobId: String,
    ): String {
        requireIdentifier(camera, "Camera")
        requireIdentifier(jobId, "Motion Search job")
        return getJson(profile, camera, "search", "motion", jobId)
    }

    override suspend fun cancelMotionSearch(
        profile: ConnectionProfile,
        camera: String,
        jobId: String,
    ): String {
        requireIdentifier(camera, "Camera")
        requireIdentifier(jobId, "Motion Search job")
        return postJson(profile, arrayOf(camera, "search", "motion", jobId, "cancel"), "{}")
    }

    override suspend fun startOnDemandRecording(
        profile: ConnectionProfile,
        camera: String,
        durationSeconds: Int?,
    ): String {
        requireIdentifier(camera, "Camera")
        require(durationSeconds == null || durationSeconds in 1..86_400) { "Recording duration is invalid." }
        val duration = durationSeconds?.toString() ?: "null"
        return postJson(
            profile,
            arrayOf("events", camera, "on_demand", "create"),
            """{"include_recording":true,"duration":$duration}""",
        )
    }

    override suspend fun stopOnDemandRecording(profile: ConnectionProfile, eventId: String): String {
        requireIdentifier(eventId, "Recording event")
        return putJson(profile, arrayOf("events", eventId, "end"), "{}")
    }

    override suspend fun startBatchExport(
        profile: ConnectionProfile,
        request: BatchExportRequest,
    ): String {
        val items = request.items.joinToString(separator = ",", prefix = "[", postfix = "]") { item ->
            buildString {
                append("{\"camera\":")
                append(json.encodeToString(item.camera))
                append(",\"start_time\":${item.startTime},\"end_time\":${item.endTime}")
                item.friendlyName?.let { append(",\"friendly_name\":${json.encodeToString(it)}") }
                item.clientItemId?.let { append(",\"client_item_id\":${json.encodeToString(it)}") }
                append('}')
            }
        }
        val payload = buildString {
            append("{\"items\":$items")
            request.existingIncidentId?.let { append(",\"export_case_id\":${json.encodeToString(it)}") }
            request.newIncidentName?.let { append(",\"new_case_name\":${json.encodeToString(it)}") }
            request.newIncidentDescription?.let {
                append(",\"new_case_description\":${json.encodeToString(it)}")
            }
            append('}')
        }
        return postJson(profile, arrayOf("exports", "batch"), payload)
    }

    override suspend fun getActiveExportJobs(profile: ConnectionProfile): String =
        getJson(profile, "jobs", "export")

    override suspend fun getExportJob(profile: ConnectionProfile, exportId: String): String {
        requireIdentifier(exportId, "Clip job")
        return getJson(profile, "jobs", "export", exportId)
    }

    override suspend fun getIncidents(profile: ConnectionProfile): String = getJson(profile, "cases")

    override suspend fun createIncident(profile: ConnectionProfile, draft: IncidentDraft): String =
        postJson(profile, arrayOf("cases"), incidentPayload(draft))

    override suspend fun updateIncident(
        profile: ConnectionProfile,
        incidentId: String,
        draft: IncidentDraft,
    ): String {
        requireIdentifier(incidentId, "Incident")
        return patchJson(profile, arrayOf("cases", incidentId), incidentPayload(draft))
    }

    override suspend fun deleteIncident(
        profile: ConnectionProfile,
        incidentId: String,
        deleteClips: Boolean,
    ): String {
        requireIdentifier(incidentId, "Incident")
        val url = apiUrl(profile, "cases", incidentId).newBuilder()
            .addQueryParameter("delete_exports", deleteClips.toString())
            .build()
        return executeText(Request.Builder().url(url).delete().build())
    }

    override suspend fun reassignExports(
        profile: ConnectionProfile,
        exportIds: Set<String>,
        incidentId: String?,
    ): String {
        require(exportIds.isNotEmpty() && exportIds.none(String::isBlank)) { "At least one Clip is required." }
        val ids = json.encodeToString(exportIds.sorted())
        val target = incidentId?.let { json.encodeToString(it) } ?: "null"
        return postJson(profile, arrayOf("exports", "reassign"), """{"ids":$ids,"export_case_id":$target}""")
    }

    override suspend fun renameExport(
        profile: ConnectionProfile,
        exportId: String,
        name: String,
    ): String {
        requireIdentifier(exportId, "Clip")
        require(name.isNotBlank() && name.length <= 256) { "Clip name is invalid." }
        return patchJson(
            profile,
            arrayOf("export", exportId, "rename"),
            """{"name":${json.encodeToString(name.trim())}}""",
        )
    }

    override fun reviewPlaybackUrl(profile: ConnectionProfile, item: ReviewItem): String {
        val start = (item.startTime - REVIEW_PADDING_SECONDS).coerceAtLeast(0.0)
        val end = (item.endTime ?: (System.currentTimeMillis() / 1000.0)) + REVIEW_PADDING_SECONDS
        return recordingPlaybackUrl(profile, item.camera, start, end)
    }

    override fun recordingPlaybackUrl(
        profile: ConnectionProfile,
        camera: String,
        startTime: Double,
        endTime: Double,
    ): String {
        require(camera.isNotBlank()) { "Camera is required." }
        require(endTime > startTime) { "Recording end must follow its start." }
        return rootUrl(
            profile,
            "vod",
            camera,
            "start",
            startTime.coerceAtLeast(0.0).toString(),
            "end",
            endTime.toString(),
            "master.m3u8",
        ).toString()
    }

    override fun exportPlaybackUrl(
        profile: ConnectionProfile,
        export: RecordingExport,
    ): String? {
        if (export.inProgress) return null
        val relative = export.videoPath.removePrefix(EXPORT_MEDIA_PREFIX)
        if (relative == export.videoPath || relative.isBlank()) return null
        val segments = relative.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." || '\\' in it }) return null
        return rootUrl(profile, "exports", *segments.toTypedArray()).toString()
    }

    private suspend fun getProfile(profile: ConnectionProfile): FrigateUserProfile {
        val raw = executeText(
            Request.Builder().url(apiUrl(profile, "profile")).get().build(),
            client = if (profile.withoutSignIn) noSignInClient else httpClient,
        )
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

    private suspend fun getJson(profile: ConnectionProfile, vararg segments: String): String =
        executeText(Request.Builder().url(apiUrl(profile, *segments)).get().build())

    private suspend fun postJson(
        profile: ConnectionProfile,
        segments: Array<String>,
        payload: String,
    ): String = executeText(
        Request.Builder()
            .url(apiUrl(profile, *segments))
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build(),
    )

    private suspend fun putJson(
        profile: ConnectionProfile,
        segments: Array<String>,
        payload: String,
    ): String = executeText(
        Request.Builder()
            .url(apiUrl(profile, *segments))
            .put(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build(),
    )

    private suspend fun patchJson(
        profile: ConnectionProfile,
        segments: Array<String>,
        payload: String,
    ): String = executeText(
        Request.Builder()
            .url(apiUrl(profile, *segments))
            .patch(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build(),
    )

    private fun incidentPayload(draft: IncidentDraft): String = buildString {
        append("{\"name\":${json.encodeToString(draft.name.trim())}")
        draft.description?.let { append(",\"description\":${json.encodeToString(it)}") }
        append('}')
    }

    private fun requireIdentifier(value: String, label: String) {
        require(value.isNotBlank() && value.length <= 256 && '/' !in value && '\\' !in value) {
            "$label is invalid"
        }
    }

    private suspend fun executeText(request: Request, client: OkHttpClient = httpClient): String = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
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
                "The Frigate hostname could not be resolved",
                RecoveryAction.CHECK_CONNECTION,
                retryable = true,
            ),
            error,
        )
        is SocketTimeoutException -> OpahException(
            OpahFailure(
                OpahErrorCode.TIMEOUT,
                "The Frigate connection timed out",
                RecoveryAction.RETRY,
                retryable = true,
            ),
            error,
        )
        is ConnectException -> OpahException(
            OpahFailure(
                OpahErrorCode.CONNECTION_REFUSED,
                "The Frigate server refused the connection",
                RecoveryAction.CHECK_CONNECTION,
                retryable = true,
            ),
            error,
        )
        else -> OpahException(
            OpahFailure(
                OpahErrorCode.UNKNOWN,
                "The Frigate request failed",
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
        private const val MAX_SEARCH_TEXT_LENGTH = 160
        private const val EXPORT_MEDIA_PREFIX = "/media/frigate/exports/"

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
    400 -> OpahFailure(
        OpahErrorCode.BAD_REQUEST,
        "Frigate could not use that request",
        RecoveryAction.NONE,
        retryable = false,
        httpStatus = code,
    )
    408 -> OpahFailure(
        OpahErrorCode.TIMEOUT,
        "Frigate timed out while processing the request",
        RecoveryAction.RETRY,
        retryable = true,
        httpStatus = code,
    )
    403 -> OpahFailure(
        OpahErrorCode.PERMISSION_DENIED,
        "This Frigate account does not have permission for that operation",
        RecoveryAction.USE_DIFFERENT_ACCOUNT,
        retryable = false,
        httpStatus = code,
    )
    404 -> OpahFailure(
        OpahErrorCode.NOT_FOUND,
        "The requested Frigate endpoint was not found",
        RecoveryAction.CHECK_SERVER_URL,
        retryable = false,
        httpStatus = code,
    )
    409 -> OpahFailure(
        OpahErrorCode.OPERATION_CONFLICT,
        "Frigate could not complete that action in its current state",
        RecoveryAction.RETRY,
        retryable = true,
        httpStatus = code,
    )
    422 -> OpahFailure(
        OpahErrorCode.UNPROCESSABLE_REQUEST,
        "Frigate rejected part of that request",
        RecoveryAction.NONE,
        retryable = false,
        httpStatus = code,
    )
    429 -> OpahFailure(
        OpahErrorCode.RATE_LIMITED,
        "Frigate received too many requests. Wait before trying again.",
        RecoveryAction.RETRY,
        retryable = true,
        httpStatus = code,
    )
    in 300..399 -> OpahFailure(
        OpahErrorCode.REDIRECT_REJECTED,
        "Frigate redirected the API request. Enter the server's canonical base URL.",
        RecoveryAction.CHECK_SERVER_URL,
        retryable = false,
        httpStatus = code,
    )
    in 500..599 -> OpahFailure(
        OpahErrorCode.SERVER_ERROR,
        "Frigate returned a server error ($code)",
        RecoveryAction.RETRY,
        retryable = true,
        httpStatus = code,
    )
    else -> OpahFailure(
        OpahErrorCode.INVALID_RESPONSE,
        "Frigate returned HTTP $code",
        RecoveryAction.RETRY,
        retryable = code >= 500,
        httpStatus = code,
    )
}
