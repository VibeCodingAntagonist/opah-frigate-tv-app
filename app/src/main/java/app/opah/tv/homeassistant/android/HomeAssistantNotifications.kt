package app.opah.tv.homeassistant.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.opah.tv.R
import app.opah.tv.data.model.AppSettings
import app.opah.tv.homeassistant.*
import app.opah.tv.notifications.AndroidNotificationIdentity
import app.opah.tv.notifications.android.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import okhttp3.Call
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal enum class HomeAssistantActionResult(val message: String) {
    READY("Choose Send request to continue"),
    SENT("Request sent — Home Assistant decides what happens next"),
    UNCERTAIN("Request may have been sent — check Home Assistant"),
    UNAVAILABLE("This Home Assistant action is no longer available"),
    NEEDS_PIN("Enter the Opah PIN to send this request"),
}

internal class HomeAssistantNotifications(
    context: Context,
    private val store: HomeAssistantSessionStore,
    private val overlays: TvAlertOverlayPresenter,
    private val appearance: Flow<AppSettings>,
    private val richPolicy: () -> HomeAssistantRichPolicy = { HomeAssistantRichPolicy() },
    private val connected: () -> Boolean = { false },
    private val api: HomeAssistantApiClient = HomeAssistantApiClient(),
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val openAction: (String) -> Unit = { HomeAssistantActionIntents.open(context, it, overlay = true) },
    private val disclosure: () -> HomeAssistantDisclosure,
    private val pause: app.opah.tv.notifications.NotificationPauseGate = app.opah.tv.notifications.NotificationPauseGate.NONE,
) : HomeAssistantNotificationSink {
    private val context = context.applicationContext
    private val notifications = NotificationManagerCompat.from(this.context)
    private val epoch = AtomicLong()
    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val images = HomeAssistantImages(api.client)
    private val book = HomeAssistantNoticeBook()
    private val pending = linkedMapOf<String, String>()
    private val resources = mutableMapOf<String, Resources>()
    private class Resources(val expires: Runnable, val presentationRevision: Long) { var image: Job? = null; var call: Call? = null }

    // Text-only entry points cannot confer a registration capability.
    override suspend fun show(server: String, message: HomeAssistantMessage.Show) = present(server, null, message)
    override suspend fun show(session: HomeAssistantSession, message: HomeAssistantMessage.Show) =
        present(session.server, homeAssistantRegistrationKey(session), message)

    private suspend fun present(server: String, origin: String?, message: HomeAssistantMessage.Show): Boolean {
        val admission = pause.snapshot()
        if (admission.paused) return false
        val key = homeAssistantNotificationKey(server, message.tag)
        val delivery = UUID.randomUUID().toString()
        val received = now()
        val expected = epoch.get()
        synchronized(lock) {
            pending[key] = delivery
            while (pending.size > 32) pending.remove(pending.keys.first())
        }
        val theme = readTvAlertOverlayAppearance(appearance)
        synchronized(lock) {
            val session = session(server) ?: return false
            if (!pause.snapshot().permits(admission.revision) || epoch.get() != expected || pending[key] != delivery || !canPost()) {
                if (pending[key] == delivery) pending.remove(key)
                return false
            }
            val notice = homeAssistantNotice(message, session.showDetails, disclosure()) ?: return false
            val policy = richPolicy()
            val bound = origin != null && origin == homeAssistantRegistrationKey(session)
            if (origin != null && !bound) return false
            val retained = if (bound) message else message.copy(image = null, actions = emptyList())
            val (entry, removed) = book.put(key, homeAssistantRegistrationKey(session), policy.epoch, retained, received)
            pending[key] = entry.id
            removed.forEach { release(it, cancelNotification = it.key != key) }
            val expires = Runnable { synchronized(lock) { book.remove(entry.id)?.let { release(it) } } }
            resources[entry.id] = Resources(expires, admission.revision)
            handler.postDelayed(expires, (entry.expiresAt - now()).coerceAtLeast(0))
            val buttons = if (bound && notice.details && session.allowActions && connected() && policy.actionsProtected) {
                entry.message.actions.mapIndexed { index, action ->
                    val ticket = entry.ticket(index)
                    TvAlertOverlayButton(ACTION_KINDS[index], action.title, ticket) {
                        synchronized(lock) {
                            if (actionStatus(ticket) != HomeAssistantActionResult.UNAVAILABLE) openAction(ticket)
                        }
                    }
                }
            } else emptyList()
            publish(entry, session, theme, notice, buttons, null, expected, updateOnly = false)
            if (bound && notice.details && session.showImages && policy.images != HomeAssistantImageDisclosure.NONE && message.image != null && connected()) {
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    synchronized(lock) {
                        if (!valid(entry, expected, notice.details) || richPolicy().images != policy.images ||
                            richPolicy().imageAuthority != policy.imageAuthority) return@launch
                    }
                    val bitmap = images.fetch(session, message.image, policy.images) ?: return@launch
                    synchronized(lock) {
                        if (valid(entry, expected, notice.details) && richPolicy().images == policy.images &&
                            richPolicy().imageAuthority == policy.imageAuthority && session(server)?.showImages == true)
                            publish(entry, session, theme, notice, buttons, bitmap, expected, updateOnly = true, imagePolicy = policy)
                    }
                }
                resources[entry.id]?.image = job
                job.start()
            }
            return true
        }
    }

    @SuppressLint("MissingPermission")
    private fun publish(entry: HomeAssistantNoticeBook.Entry, session: HomeAssistantSession, theme: AppSettings,
        notice: HomeAssistantNotice, buttons: List<TvAlertOverlayButton>, bitmap: Bitmap?, expected: Long, updateOnly: Boolean,
        imagePolicy: HomeAssistantRichPolicy? = null) {
        if (!valid(entry, expected, notice.details)) return
        AndroidNotificationChannels.create(context)
        val builder = NotificationCompat.Builder(context, AndroidNotificationChannels.HOME_ASSISTANT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notifications).setContentTitle(notice.title).setContentText(notice.body)
            .setOnlyAlertOnce(true).setSilent(updateOnly).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true)
            .setTimeoutAfter((entry.expiresAt - now()).coerceAtLeast(1))
            .setDeleteIntent(HomeAssistantActionIntents.dismiss(context, entry.id))
            .extend(NotificationCompat.TvExtender().setChannelId(AndroidNotificationChannels.HOME_ASSISTANT_CHANNEL_ID).setSuppressShowOverApps(true))
        if (bitmap == null) builder.setStyle(NotificationCompat.BigTextStyle().bigText(notice.body)) else {
            builder.setLargeIcon(bitmap).setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap).setSummaryText(notice.body))
        }
        buttons.forEachIndexed { index, button -> builder.addAction(0, button.label, HomeAssistantActionIntents.pending(context, entry.ticket(index))) }
        val revision = resources[entry.id]?.presentationRevision ?: return
        if (pause.withPermit(revision) { notifications.notify(entry.key, NOTIFICATION_ID, builder.build()); true } != true) return
        overlays.showHomeAssistant(session.server, entry.message.tag, AndroidNotificationIdentity(entry.key, NOTIFICATION_ID),
            notice.title, notice.body, theme, bitmap, buttons, onDismiss = { dismiss(entry.id) }, updateOnly = updateOnly) {
            synchronized(lock) {
                val current = richPolicy()
                valid(entry, expected, notice.details) && (bitmap == null || imagePolicy != null && current.images == imagePolicy.images &&
                    current.imageAuthority == imagePolicy.imageAuthority) &&
                    (buttons.isEmpty() || richPolicy().actionsProtected && session(session.server)?.allowActions == true && connected())
            }
        }
    }

    fun actionStatus(ticket: String): HomeAssistantActionResult = synchronized(lock) {
        val session = runCatching { store.read() }.getOrNull() ?: return HomeAssistantActionResult.UNAVAILABLE
        val policy = richPolicy()
        if (!session.enabled || !session.showDetails || !session.allowActions || !connected() ||
            disclosure() != HomeAssistantDisclosure.DETAILS || !policy.actionsProtected || !canPost()) return HomeAssistantActionResult.UNAVAILABLE
        val (entry, _) = book.action(ticket, now(), homeAssistantRegistrationKey(session), policy.epoch) ?: return HomeAssistantActionResult.UNAVAILABLE
        if (!pause.snapshot().permits(resources[entry.id]?.presentationRevision ?: -1)) return HomeAssistantActionResult.UNAVAILABLE
        if (pending[entry.key] != entry.id) return HomeAssistantActionResult.UNAVAILABLE
        if (!policy.actionsUnlocked) HomeAssistantActionResult.NEEDS_PIN else HomeAssistantActionResult.READY
    }

    fun actionTitle(ticket: String): String? = synchronized(lock) {
        if (actionStatus(ticket) == HomeAssistantActionResult.UNAVAILABLE) return null
        val session = runCatching { store.read() }.getOrNull() ?: return null
        book.action(ticket, now(), homeAssistantRegistrationKey(session), richPolicy().epoch)?.second?.title
    }

    /** Consume before submission; no queue, automatic retry, or implied automation confirmation. */
    suspend fun executeAction(ticket: String): HomeAssistantActionResult {
        val prepared = synchronized(lock) {
            val admission = actionStatus(ticket)
            if (admission != HomeAssistantActionResult.READY) return admission
            val session = runCatching { store.read() }.getOrNull() ?: return HomeAssistantActionResult.UNAVAILABLE
            val (entry, action) = book.action(ticket, now(), homeAssistantRegistrationKey(session), richPolicy().epoch) ?: return HomeAssistantActionResult.UNAVAILABLE
            val call = api.notificationActionCall(session, entry.message, action)
            if (!book.consume(entry)) return HomeAssistantActionResult.UNAVAILABLE
            resources[entry.id]?.apply { image?.cancel(); this.call = call }
            notifications.cancel(entry.key, NOTIFICATION_ID)
            overlays.dismiss(AndroidNotificationIdentity(entry.key, NOTIFICATION_ID))
            entry to call
        }
        return try {
            if (api.sendNotificationAction(prepared.second)) HomeAssistantActionResult.SENT else HomeAssistantActionResult.UNCERTAIN
        } finally { synchronized(lock) { book.remove(prepared.first.id)?.let { release(it) } } }
    }

    fun dismiss(id: String) = synchronized(lock) { book.dismiss(id)?.let { release(it) }; Unit }
    override fun clear(server: String, tag: String) = synchronized(lock) {
        val key = homeAssistantNotificationKey(server, tag)
        pending.remove(key)
        book.removeKey(key).forEach { release(it) }
        notifications.cancel(key, NOTIFICATION_ID)
        overlays.dismiss(AndroidNotificationIdentity(key, NOTIFICATION_ID))
    }
    override fun clearAll() = synchronized(lock) {
        epoch.incrementAndGet()
        pending.clear()
        book.clear().forEach { release(it) }
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .filter { it.tag?.startsWith("opah_ha_") == true && it.id == NOTIFICATION_ID }
            .forEach { notifications.cancel(it.tag, it.id) }
        overlays.dismissHomeAssistant()
    }
    override fun hideOverlay() = clearAll()

    private fun release(entry: HomeAssistantNoticeBook.Entry, cancelNotification: Boolean = true) {
        resources.remove(entry.id)?.let { handler.removeCallbacks(it.expires); it.image?.cancel(); it.call?.cancel() }
        if (pending[entry.key] == entry.id) pending.remove(entry.key)
        if (cancelNotification) { notifications.cancel(entry.key, NOTIFICATION_ID); overlays.dismiss(AndroidNotificationIdentity(entry.key, NOTIFICATION_ID)) }
    }
    private fun session(server: String) = runCatching { store.read() }.getOrNull()?.takeIf { it.enabled && it.server == server }
    private fun valid(entry: HomeAssistantNoticeBook.Entry, expected: Long, details: Boolean): Boolean {
        val session = runCatching { store.read() }.getOrNull() ?: return false
        val policy = disclosure()
        return pause.snapshot().permits(resources[entry.id]?.presentationRevision ?: -1) && expected == epoch.get() && pending[entry.key] == entry.id && session.enabled && canPost() &&
            policy != HomeAssistantDisclosure.NONE && (!details || session.showDetails && policy == HomeAssistantDisclosure.DETAILS) &&
            book.current(entry, now(), homeAssistantRegistrationKey(session), richPolicy().epoch)
    }
    private fun canPost(): Boolean = !pause.snapshot().paused && notifications.areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        (Build.VERSION.SDK_INT < 26 || context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(AndroidNotificationChannels.HOME_ASSISTANT_CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE)

    private companion object {
        const val NOTIFICATION_ID = 2
        val ACTION_KINDS = listOf(TvAlertOverlayActionKind.HA_FIRST, TvAlertOverlayActionKind.HA_SECOND, TvAlertOverlayActionKind.HA_THIRD)
    }
}
