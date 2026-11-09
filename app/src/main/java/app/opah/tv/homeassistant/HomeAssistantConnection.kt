package app.opah.tv.homeassistant

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

internal enum class HomeAssistantConnectionPhase { DISCONNECTED, READY, CONNECTING, CONNECTED, RETRYING, SIGN_IN_REQUIRED, ERROR }

internal data class HomeAssistantConnectionState(
    val phase: HomeAssistantConnectionPhase = HomeAssistantConnectionPhase.DISCONNECTED,
    val deviceName: String = "",
    val enabled: Boolean = false,
    val received: Int = 0,
    val message: String? = null,
)

/** A separate provider: it never reads a Frigate profile, session, camera, or playback owner. */
internal class HomeAssistantConnection(
    private val api: HomeAssistantApiClient,
    private val store: HomeAssistantSessionStore,
    private val notifications: HomeAssistantNotificationSink,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val retryDelayMillis: Long = 1_000,
    internal val setupProbe: HomeAssistantSetupProbe = HomeAssistantSetupProbe(),
    private val pause: app.opah.tv.notifications.NotificationPauseGate = app.opah.tv.notifications.NotificationPauseGate.NONE,
) {
    private val mutableState = MutableStateFlow(HomeAssistantConnectionState())
    val state: StateFlow<HomeAssistantConnectionState> = mutableState.asStateFlow()
    private val generation = AtomicLong()
    private val mutation = Mutex()
    private val receiver = Mutex()
    private var worker: Job? = null
    private val seenConfirmations = linkedSetOf<String>()

    fun loadState() {
        if (worker?.isActive == true) return
        val session = readSession()
        if (mutableState.value.phase in setOf(HomeAssistantConnectionPhase.SIGN_IN_REQUIRED, HomeAssistantConnectionPhase.ERROR)) return
        mutableState.value = if (session == null) HomeAssistantConnectionState()
        else HomeAssistantConnectionState(HomeAssistantConnectionPhase.READY, session.deviceName, session.enabled)
    }

    suspend fun connect(signIn: HomeAssistantSignIn, code: String, device: HomeAssistantDevice,
        acceptConnection: () -> Boolean = { true }) = mutation.withLock {
        check(readSession() == null) { "Disconnect the current Home Assistant server first" }
        if (mutableState.value.message == HomeAssistantProblem.STORAGE.explanation) {
            throw HomeAssistantException(HomeAssistantProblem.STORAGE)
        }
        val expected = generation.get()
        var pendingSession: HomeAssistantSession? = null
        mutableState.value = HomeAssistantConnectionState(HomeAssistantConnectionPhase.CONNECTING, signIn.deviceName)
        try {
            val session = api.connect(signIn, code, store.deviceId(), device).also { pendingSession = it }
            currentCoroutineContext().ensureActive()
            if (generation.get() != expected || !acceptConnection()) throw CancellationException("Connection setup stopped")
            saveSession(session)
            pendingSession = null
            synchronized(seenConfirmations) { seenConfirmations.clear() }
            mutableState.value = HomeAssistantConnectionState(HomeAssistantConnectionPhase.READY, session.deviceName)
        } catch (cancelled: CancellationException) {
            mutableState.value = HomeAssistantConnectionState()
            throw cancelled
        } catch (error: Exception) {
            mutableState.value = HomeAssistantConnectionState(HomeAssistantConnectionPhase.ERROR, signIn.deviceName,
                message = error.problem().explanation)
        } finally {
            pendingSession?.let { session ->
                runCatching { store.clear() }
                api.revokeBestEffort(homeAssistantBaseUrl(session.server), session.refreshToken)
            }
        }
    }

    fun hasSession(): Boolean = readSession() != null
    fun enabled(): Boolean = readSession()?.enabled == true
    fun showDetails(): Boolean = readSession()?.showDetails == true
    fun beginSetupDeliveryCheck(): String? {
        val session = readSession()?.takeIf { it.enabled } ?: return null
        if (state.value.phase != HomeAssistantConnectionPhase.CONNECTED) return null
        return setupProbe.arm(homeAssistantRegistrationKey(session))
    }

    suspend fun checkSavedSignIn(accept: () -> Boolean): Boolean = mutation.withLock {
        val session = readSession() ?: return@withLock false
        val expected = generation.get()
        if (!accept()) return@withLock false
        val checked = if (session.expiresAtMillis - nowMillis() < 60_000) api.refresh(session) else session
        api.checkAvailability(checked)
        currentCoroutineContext().ensureActive()
        if (generation.get() != expected || !accept()) return@withLock false
        if (checked != session) saveSession(checked)
        true
    }
    fun showImages(): Boolean = readSession()?.showImages == true
    fun allowActions(): Boolean = readSession()?.allowActions == true

    suspend fun setRichNotifications(images: Boolean? = null, actions: Boolean? = null) = mutation.withLock {
        val session = readSession() ?: return@withLock
        saveSession(session.copy(showImages = images ?: session.showImages, allowActions = actions ?: session.allowActions))
        notifications.clearAll()
    }

    fun deliveryUnavailable() {
        mutableState.value = mutableState.value.copy(phase = HomeAssistantConnectionPhase.ERROR,
            message = "Android could not start notifications — please try again")
    }

    /** Android network changes invalidate a socket even when its TCP timeout has not fired. */
    @Synchronized
    fun networkChanged(available: Boolean) {
        if (!enabled() || mutableState.value.phase in setOf(HomeAssistantConnectionPhase.SIGN_IN_REQUIRED, HomeAssistantConnectionPhase.ERROR)) return
        stop()
        mutableState.value = mutableState.value.copy(phase = HomeAssistantConnectionPhase.RETRYING,
            message = HomeAssistantProblem.NETWORK.explanation)
        if (available) start()
    }

    suspend fun setShowDetails(show: Boolean) = mutation.withLock {
        val session = readSession() ?: return@withLock
        saveSession(session.copy(showDetails = show))
        notifications.clearAll()
    }

    suspend fun setEnabled(enabled: Boolean): Boolean {
        // Cancel a pending token request before waiting for the credential mutation lock.
        if (!enabled) stop()
        return mutation.withLock {
            val session = readSession() ?: return@withLock false
            saveSession(session.copy(enabled = enabled))
            mutableState.value = mutableState.value.copy(enabled = enabled)
            if (!enabled) {
                stop()
                notifications.clearAll()
                mutableState.value = HomeAssistantConnectionState(HomeAssistantConnectionPhase.READY, session.deviceName)
            }
            true
        }
    }

    suspend fun disconnect() {
        generation.incrementAndGet()
        stop()
        mutation.withLock {
            val session = readSession()
            notifications.clearAll()
            store.clear()
            stop()
            synchronized(seenConfirmations) { seenConfirmations.clear() }
            mutableState.value = HomeAssistantConnectionState()
            if (session != null) api.revokeBestEffort(homeAssistantBaseUrl(session.server), session.refreshToken)
        }
    }

    @Synchronized
    fun start() {
        if (worker?.isActive == true || !enabled()) return
        val expected = generation.get()
        worker = scope.launch {
            // A previous WebSocket must finish closing before a replacement acquires the receiver.
            receiver.withLock {
                var backoff = retryDelayMillis
                var forceRefresh = false
                var rejectedTokenRefreshed = false
                while (isActive && generation.get() == expected) {
                    var session = readSession()?.takeIf { it.enabled } ?: break
                    try {
                        if (forceRefresh || session.expiresAtMillis - nowMillis() < 60_000) {
                            forceRefresh = false
                            session = mutation.withLock {
                                currentCoroutineContext().ensureActive()
                                val saved = readSession()?.takeIf { it.enabled && generation.get() == expected }
                                    ?: throw CancellationException("Home Assistant receiver stopped")
                                api.refresh(saved).also(::saveSession)
                            }
                        }
                        mutableState.value = mutableState.value.copy(phase = HomeAssistantConnectionPhase.CONNECTING,
                            deviceName = session.deviceName, enabled = true, message = null)
                        val lifetime = (session.expiresAtMillis - nowMillis() - 30_000).coerceAtLeast(1_000)
                        withTimeoutOrNull(lifetime) { receive(session, expected) { rejectedTokenRefreshed = false } }
                        backoff = retryDelayMillis
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        var problem = error.problem()
                        if (problem == HomeAssistantProblem.ACCESS_TOKEN) {
                            if (!rejectedTokenRefreshed) {
                                rejectedTokenRefreshed = true
                                forceRefresh = true
                                continue
                            }
                            problem = HomeAssistantProblem.SIGN_IN
                        }
                        if (problem in setOf(HomeAssistantProblem.SIGN_IN, HomeAssistantProblem.CHANNEL, HomeAssistantProblem.STORAGE)) {
                            mutableState.value = mutableState.value.copy(
                                phase = if (problem == HomeAssistantProblem.SIGN_IN) HomeAssistantConnectionPhase.SIGN_IN_REQUIRED else HomeAssistantConnectionPhase.ERROR,
                                message = problem.explanation,
                            )
                            break
                        }
                        mutableState.value = mutableState.value.copy(phase = HomeAssistantConnectionPhase.RETRYING,
                            message = HomeAssistantProblem.NETWORK.explanation)
                        delay(backoff)
                        backoff = (backoff * 2).coerceAtMost(30_000)
                    }
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        setupProbe.clear()
        worker?.cancel()
        worker = null
        notifications.hideOverlay()
        if (mutableState.value.phase in setOf(HomeAssistantConnectionPhase.CONNECTED, HomeAssistantConnectionPhase.CONNECTING, HomeAssistantConnectionPhase.RETRYING)) {
            mutableState.value = mutableState.value.copy(phase = HomeAssistantConnectionPhase.READY, message = null)
        }
    }

    private suspend fun receive(session: HomeAssistantSession, expected: Long, onConnected: () -> Unit) {
        val events = Channel<Pair<String, app.opah.tv.notifications.NotificationPauseSnapshot>>(32)
        val socket = api.client.newWebSocket(Request.Builder()
            .url(homeAssistantBaseUrl(session.server).haEndpoint("api/websocket")).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > HA_MAX_FRAME_CHARS || events.trySend(text to pause.snapshot()).isFailure) {
                    events.close(HomeAssistantException(HomeAssistantProblem.NETWORK))
                    webSocket.cancel()
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                events.close(HomeAssistantException(HomeAssistantProblem.NETWORK))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                events.close(HomeAssistantException(HomeAssistantProblem.NETWORK))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                events.close(HomeAssistantException(HomeAssistantProblem.NETWORK))
                webSocket.close(1000, null)
            }
        })
        var authenticated = false
        var subscribed = false
        var nextId = 2
        try {
            while (currentCoroutineContext().isActive) {
                val raw = if (subscribed) events.receive() else withTimeoutOrNull(15_000) { events.receive() }
                    ?: throw HomeAssistantException(HomeAssistantProblem.NETWORK)
                currentCoroutineContext().ensureActive()
                if (generation.get() != expected) break
                val frame = runCatching { haJson.parseToJsonElement(raw.first).jsonObject }.getOrNull()
                    ?: throw HomeAssistantException(HomeAssistantProblem.RESPONSE)
                fun send(value: JsonObject) {
                    if (!socket.send(value.toString())) throw HomeAssistantException(HomeAssistantProblem.NETWORK)
                }
                when (frame.string("type")) {
                    "auth_required" -> if (!authenticated) send(buildJsonObject {
                        put("type", "auth"); put("access_token", session.accessToken)
                    })
                    "auth_invalid" -> throw HomeAssistantException(HomeAssistantProblem.ACCESS_TOKEN)
                    "auth_ok" -> if (!authenticated) {
                        authenticated = true
                        send(buildJsonObject {
                            put("id", 1); put("type", "mobile_app/push_notification_channel")
                            put("webhook_id", session.webhookId); put("support_confirm", true)
                        })
                    }
                    "result" -> if (frame["id"]?.jsonPrimitive?.intOrNull == 1) {
                        if (!authenticated || frame["success"]?.jsonPrimitive?.booleanOrNull != true) {
                            throw HomeAssistantException(HomeAssistantProblem.CHANNEL)
                        }
                        subscribed = true
                        onConnected()
                        mutableState.value = mutableState.value.copy(phase = HomeAssistantConnectionPhase.CONNECTED, message = null)
                    }
                    "event" -> if (subscribed && frame["id"]?.jsonPrimitive?.intOrNull == 1) {
                        val event = frame["event"] as? JsonObject ?: continue
                        val delivery = parseHomeAssistantDelivery(event)
                        delivery.confirmId?.let { id -> send(buildJsonObject {
                            put("id", nextId++); put("type", "mobile_app/push_notification_confirm")
                            put("webhook_id", session.webhookId); put("confirm_id", id)
                        }) }
                        val confirm = delivery.confirmId
                        if (confirm != null && !synchronized(seenConfirmations) {
                            val fresh = seenConfirmations.add(confirm)
                            if (seenConfirmations.size > 256) seenConfirmations.remove(seenConfirmations.first())
                            fresh
                        }) continue
                        when (val message = delivery.message) {
                            is HomeAssistantMessage.Show -> {
                                if (!raw.second.paused && pause.snapshot().permits(raw.second.revision)) notifications.show(session, message)
                                currentCoroutineContext().ensureActive()
                                setupProbe.observe(homeAssistantRegistrationKey(session), message.body)
                                mutableState.value = mutableState.value.copy(received = (mutableState.value.received.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                            }
                            is HomeAssistantMessage.Clear -> notifications.clear(session.server, message.tag)
                            HomeAssistantMessage.Ignore -> Unit
                        }
                    }
                }
            }
        } finally {
            setupProbe.clear()
            notifications.hideOverlay()
            socket.cancel()
            events.cancel()
        }
    }

    private fun readSession(): HomeAssistantSession? = try { store.read() } catch (_: Exception) {
        mutableState.value = mutableState.value.copy(phase = HomeAssistantConnectionPhase.ERROR, message = HomeAssistantProblem.STORAGE.explanation)
        null
    }
    private fun saveSession(session: HomeAssistantSession) {
        try { store.write(session) } catch (_: Exception) { throw HomeAssistantException(HomeAssistantProblem.STORAGE) }
    }
}

private fun Exception.problem(): HomeAssistantProblem = (this as? HomeAssistantException)?.problem ?: HomeAssistantProblem.NETWORK
