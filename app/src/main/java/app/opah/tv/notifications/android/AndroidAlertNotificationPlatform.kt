package app.opah.tv.notifications.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.ActivityOptions
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.opah.tv.MainActivity
import app.opah.tv.R
import app.opah.tv.notifications.AlertNotificationPlatform
import app.opah.tv.notifications.AlertNotificationPost
import app.opah.tv.notifications.AlertThumbnailRequest
import app.opah.tv.notifications.AlertContentIntentKind
import app.opah.tv.notifications.AndroidNotificationIdentity
import app.opah.tv.notifications.NotificationActionKind
import app.opah.tv.notifications.NotificationAlertBehavior
import app.opah.tv.notifications.NotificationClass
import app.opah.tv.notifications.TvAlertOpenPreference
import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.model.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient

internal suspend fun readTvAlertOverlayAppearance(settings: Flow<AppSettings>): AppSettings = try {
    withTimeoutOrNull(500L) { settings.first() } ?: unavailableOverlayAppearance()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    // Appearance failure must not suppress the durable alert or reuse a stale UI singleton.
    unavailableOverlayAppearance()
}

private fun unavailableOverlayAppearance() = AppSettings(
    reducedMotion = true,
    reducedTransparency = true,
    highContrast = true,
)

/** Native Android/TV presentation boundary. Thumbnail storage is added behind this boundary. */
class AndroidAlertNotificationPlatform internal constructor(
    context: Context,
    client: OkHttpClient,
    profile: suspend (String) -> ConnectionProfile?,
    private val appearanceSettings: Flow<AppSettings>,
    private val overlays: TvAlertOverlayPresenter = TvAlertOverlayPresenter(context),
    private val overlayMayPresent: (AlertNotificationPost) -> Boolean = { false },
    private val overlayTargets: suspend (AlertNotificationPost) -> List<TvAlertOpenPreference> = { emptyList() },
    private val pause: app.opah.tv.notifications.NotificationPauseGate = app.opah.tv.notifications.NotificationPauseGate.NONE,
    private val inlinePreviewAllowed: (AlertNotificationPost) -> Boolean = { false },
    private val inlinePreview: (AlertNotificationPost, Bitmap?, AppSettings, Long, () -> Unit) -> Unit = { _, picture, _, _, _ -> picture?.recycle() },
) : AlertNotificationPlatform {
    private val appContext = context.applicationContext
    private val notifications = NotificationManagerCompat.from(appContext)
    private val thumbnails = AndroidNotificationThumbnailStore(client, profile)
    private val overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val overlayPublications = ConcurrentHashMap<AndroidNotificationIdentity, OverlayPublication>()

    override suspend fun prepareThumbnail(request: AlertThumbnailRequest): String? =
        if (pause.snapshot().paused) null else thumbnails.prepare(request)

    override suspend fun post(request: AlertNotificationPost): Boolean = runCatching {
        val admission = request.presentationRevision ?: pause.snapshot().revision
        if (!pause.snapshot().permits(admission)) return false
        if (!canPostNotifications()) return false
        AndroidNotificationChannels.create(appContext)
        val channelId = request.model.notificationClass.channelId()
        if (!channelAvailable(channelId)) return false
        val silent = request.alertBehavior == NotificationAlertBehavior.SILENT_UPDATE
        // Resolve appearance before any publication, inside the coordinator's serialized post.
        // A delayed settings read must never enqueue a card after a later cancellation.
        val appearance = readTvAlertOverlayAppearance(appearanceSettings)
        val contentIntent = contentIntent(request)
        val thumbnail = request.model.thumbnailContentVersion?.let { version ->
            if (
                request.contentIntentKind == AlertContentIntentKind.LOCAL_TEST &&
                version == LOCAL_TEST_THUMBNAIL_CONTENT_VERSION
            ) {
                localTestThumbnail()
            } else {
                thumbnails.bitmap(request.profileKey, request.reviewId, version)
            }
        }
        val builder = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(R.drawable.ic_videocam)
            .setContentTitle(request.model.title)
            .setContentText(request.model.body)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(silent)
            .setSilent(silent)
            .setContentIntent(contentIntent)
            .setDeleteIntent(serviceActionIntent(request, TvAlertForegroundService.ACTION_DISMISS))
            .extend(
                NotificationCompat.TvExtender()
                    .setChannelId(channelId)
                    .setContentIntent(contentIntent)
                    .setSuppressShowOverApps(false),
            )
        if (thumbnail == null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(request.model.body))
        } else {
            builder
                .setLargeIcon(thumbnail)
                .setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(thumbnail)
                        .setSummaryText(request.model.body),
                )
        }
        if (NotificationActionKind.SNOOZE in request.model.actions) {
            builder.addAction(
                0,
                "Snooze 15 min",
                serviceActionIntent(request, TvAlertForegroundService.ACTION_SNOOZE),
            )
        }
        val notification = builder.build()
        if (request.contentIntentKind == AlertContentIntentKind.EVENT && !overlayMayPresent(request)) return false
        if (!notifyAfterPermissionCheck(request.identity.tag, request.identity.id, notification, admission)) return false
        publishOverlay(request, appearance, channelId, thumbnail, silent, admission)
        true
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        false
    }

    private fun publishOverlay(
        request: AlertNotificationPost,
        appearance: AppSettings,
        channelId: String,
        thumbnail: Bitmap?,
        silent: Boolean,
        admission: Long,
    ) {
        val publication: OverlayPublication
        synchronized(overlayPublications) {
            val prior = overlayPublications.remove(request.identity)
            publication = OverlayPublication(startsPopup = !silent || prior?.startsPopup == true)
            prior?.cancel()
            if (overlayPublications.size >= TV_ALERT_QUEUE_CAPACITY + 1) return
            overlayPublications[request.identity] = publication
        }
        val job = overlayScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!publication.startsPopup && !overlays.hasPending(request.identity)) return@launch
                val buttons = if (TvAlertOverlaySettingsStore(appContext).load().interactiveFrigateAlerts && overlayMayPresent(request)) {
                    withTimeoutOrNull(2_000) { overlayButtons(request, thumbnail, appearance) }.orEmpty()
                } else emptyList()
                if (!isActive || !publication.valid.get()) return@launch
                overlays.show(
                    profileKey = request.profileKey,
                    reviewId = request.reviewId,
                    identity = request.identity,
                    title = request.model.title,
                    body = request.model.body,
                    thumbnail = thumbnail,
                    appearance = appearance,
                    buttons = buttons,
                    canPresent = { pause.snapshot().permits(admission) && publication.valid.get() && canPostNotifications() && channelAvailable(channelId) && overlayMayPresent(request) },
                    onDismiss = {
                        notifications.cancel(request.identity.tag, request.identity.id)
                        if (request.contentIntentKind == AlertContentIntentKind.EVENT) {
                            serviceActionIntent(request, TvAlertForegroundService.ACTION_DISMISS).send()
                        }
                    },
                    priority = if (request.model.notificationClass == NotificationClass.ALERT) 2 else 1,
                    updateOnly = !publication.startsPopup,
                )
            } finally {
                overlayPublications.remove(request.identity, publication)
            }
        }
        publication.job = job
        if (publication.valid.get()) job.start() else job.cancel()
    }

    override suspend fun cancel(identity: AndroidNotificationIdentity): Boolean = runCatching {
        overlayPublications.remove(identity)?.cancel()
        notifications.cancel(identity.tag, identity.id)
        overlays.dismiss(identity)
        true
    }.getOrDefault(false)

    override suspend fun purgeEphemeralContent(profileKey: String, reviewId: String): Boolean {
        overlayPublications.remove(app.opah.tv.notifications.NotificationIdentityFactory().eventIdentity(profileKey, reviewId))?.cancel()
        overlays.dismiss(profileKey, reviewId)
        return thumbnails.purge(profileKey, reviewId)
    }

    override suspend fun purgeProfileEphemeralContent(profileKey: String): Boolean {
        cancelOverlayPublications()
        overlays.dismissProfile(profileKey)
        return thumbnails.purgeProfile(profileKey)
    }

    override suspend fun postSignInRequired(): Boolean = runCatching {
        if (!canPostNotifications()) return false
        AndroidNotificationChannels.create(appContext)
        if (!channelAvailable(AndroidNotificationChannels.ALERT_CHANNEL_ID)) return false
        val contentIntent = PendingIntent.getActivity(
            appContext,
            SIGN_IN_NOTIFICATION_ID,
            Intent(appContext, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(
            appContext,
            AndroidNotificationChannels.ALERT_CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.ic_videocam)
            .setContentTitle("Sign in to Frigate")
            .setContentText("Open Opah to keep TV alerts working")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Open Opah to keep TV alerts working"),
            )
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .extend(
                NotificationCompat.TvExtender()
                    .setChannelId(AndroidNotificationChannels.ALERT_CHANNEL_ID)
                    .setContentIntent(contentIntent)
                    .setSuppressShowOverApps(false),
            )
            .build()
        notifyAfterPermissionCheck(SIGN_IN_NOTIFICATION_TAG, SIGN_IN_NOTIFICATION_ID, notification)
    }.getOrDefault(false)

    override suspend fun cancelSignInRequired(): Boolean = runCatching {
        notifications.cancel(SIGN_IN_NOTIFICATION_TAG, SIGN_IN_NOTIFICATION_ID)
        true
    }.getOrDefault(false)

    fun clearEphemeralContent() {
        cancelOverlayPublications()
        overlays.dismissFrigate()
        thumbnails.clear()
    }

    fun clearForSharedPause() {
        clearEphemeralContent()
        appContext.getSystemService(android.app.NotificationManager::class.java).activeNotifications
            .filter { it.tag?.startsWith("opah1_") == true || it.tag == SIGN_IN_NOTIFICATION_TAG }
            .forEach { notifications.cancel(it.tag, it.id) }
    }

    fun dismissOverlay() { cancelOverlayPublications(); overlays.dismissFrigate() }

    private fun cancelOverlayPublications() {
        synchronized(overlayPublications) {
            overlayPublications.values.forEach(OverlayPublication::cancel)
            overlayPublications.clear()
        }
    }

    private class OverlayPublication(val startsPopup: Boolean) {
        val valid = AtomicBoolean(true)
        @Volatile var job: Job? = null
        fun cancel() { valid.set(false); job?.cancel() }
    }

    private fun localTestThumbnail(): Bitmap {
        val bitmap = createBitmap(480, 270)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.rgb(43, 57, 72))
        paint.color = Color.rgb(104, 142, 163)
        canvas.drawRect(0f, 0f, 480f, 108f, paint)
        paint.color = Color.rgb(68, 82, 69)
        canvas.drawRect(0f, 108f, 480f, 270f, paint)
        paint.color = Color.rgb(212, 198, 170)
        canvas.drawRoundRect(RectF(150f, 54f, 330f, 244f), 8f, 8f, paint)
        paint.color = Color.rgb(52, 61, 70)
        canvas.drawRect(202f, 94f, 278f, 244f, paint)
        paint.color = Color.rgb(112, 214, 198)
        canvas.drawCircle(266f, 170f, 5f, paint)
        paint.color = Color.argb(210, 14, 19, 27)
        canvas.drawRoundRect(RectF(14f, 14f, 170f, 48f), 8f, 8f, paint)
        paint.color = Color.WHITE
        paint.textSize = 18f
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        canvas.drawText("FICTIONAL TEST", 28f, 37f, paint)
        return bitmap
    }

    private fun canPostNotifications(): Boolean = !pause.snapshot().paused && (
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED)

    @SuppressLint("MissingPermission")
    private fun notifyAfterPermissionCheck(tag: String, id: Int, notification: Notification,
        revision: Long = pause.snapshot().revision): Boolean {
        // Both callers check the runtime permission immediately before building and posting.
        // Their outer runCatching also handles revocation in the small race before this call.
        return pause.withPermit(revision) { notifications.notify(tag, id, notification); true } == true
    }

    private fun contentIntent(request: AlertNotificationPost,
        preference: TvAlertOpenPreference = TvAlertOpenPreference.DEFAULT,
        interactive: Boolean = false): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java)
            .setAction(
                when (request.contentIntentKind) {
                    AlertContentIntentKind.EVENT -> TvAlertIntentContract.ACTION_OPEN
                    AlertContentIntentKind.LOCAL_TEST -> TvAlertIntentContract.ACTION_OPEN_LOCAL_TEST
                },
            )
            .setData(
                Uri.Builder()
                    .scheme(INTERNAL_SCHEME)
                    .authority(INTERNAL_AUTHORITY)
                    .appendPath(request.identity.tag)
                    .appendPath(preference.name)
                    .appendPath(if (interactive) "overlay" else "notification")
                    .build(),
            )
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (request.contentIntentKind == AlertContentIntentKind.EVENT) {
            intent.putAlertPayload(request).putExtra(TvAlertIntentContract.EXTRA_OPEN_PREFERENCE, preference.name)
        }
        return PendingIntent.getActivity(
            appContext,
            request.identity.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            if (interactive && Build.VERSION.SDK_INT >= 34) userActionOptions().apply {
                setPendingIntentCreatorBackgroundActivityStartMode(userActionStartMode())
            }.toBundle() else null,
        )
    }

    private suspend fun overlayButtons(request: AlertNotificationPost, thumbnail: Bitmap?, appearance: AppSettings): List<TvAlertOverlayButton> {
        val targets = if (request.contentIntentKind == AlertContentIntentKind.LOCAL_TEST) {
            listOf(TvAlertOpenPreference.DEFAULT)
        } else overlayTargets(request)
        return buildList {
            targets.distinct().take(2).forEach { preference ->
                val kind = when (preference) {
                    TvAlertOpenPreference.DEFAULT -> TvAlertOverlayActionKind.OPEN
                    TvAlertOpenPreference.RECORDING -> TvAlertOverlayActionKind.RECORDING
                    TvAlertOpenPreference.LIVE -> TvAlertOverlayActionKind.LIVE
                    TvAlertOpenPreference.ACTIVITY -> TvAlertOverlayActionKind.ACTIVITY
                }
                val label = when (preference) {
                    TvAlertOpenPreference.DEFAULT -> "Open"
                    TvAlertOpenPreference.RECORDING -> "Watch recording"
                    TvAlertOpenPreference.LIVE -> "Live camera"
                    TvAlertOpenPreference.ACTIVITY -> "Open activity"
                }
                add(TvAlertOverlayButton(kind, label, "${request.actionNonce}/${request.overlayCameraKey}/${preference.name}") {
                    if (overlayMayPresent(request)) contentIntent(request, preference, interactive = true).send(
                        appContext, 0, null, null, null, null,
                        if (Build.VERSION.SDK_INT >= 34) userActionOptions().apply {
                            setPendingIntentBackgroundActivityStartMode(userActionStartMode())
                        }.toBundle() else null,
                    )
                })
                if (preference == TvAlertOpenPreference.RECORDING && inlinePreviewAllowed(request)) {
                    add(TvAlertOverlayButton(TvAlertOverlayActionKind.PREVIEW, "Preview recording",
                        "${request.actionNonce}/${request.overlayCameraKey}/preview", invokeBounded = { deadline ->
                            if (inlinePreviewAllowed(request)) {
                                val copy = runCatching { thumbnail?.takeIf { it.width <= 1280 && it.height <= 720 && !it.isRecycled }
                                    ?.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                                runCatching { inlinePreview(request, copy, appearance, deadline) {
                                    if (overlayMayPresent(request)) contentIntent(request, TvAlertOpenPreference.RECORDING, interactive = true).send(
                                        appContext, 0, null, null, null, null,
                                        if (Build.VERSION.SDK_INT >= 34) userActionOptions().apply {
                                            setPendingIntentBackgroundActivityStartMode(userActionStartMode())
                                        }.toBundle() else null)
                                } }.onFailure { copy?.recycle() }
                            }
                        }, invoke = {}))
                }
            }
            if (NotificationActionKind.SNOOZE in request.model.actions) add(
                TvAlertOverlayButton(TvAlertOverlayActionKind.SNOOZE, "Snooze 15 min", "${request.actionNonce}/${request.overlayCameraKey}/snooze") {
                    if (overlayMayPresent(request)) serviceActionIntent(request, TvAlertForegroundService.ACTION_SNOOZE).send()
                },
            )
        }
    }

    private fun userActionOptions(): ActivityOptions = ActivityOptions.makeBasic()

    @Suppress("DEPRECATION")
    @androidx.annotation.RequiresApi(34)
    private fun userActionStartMode(): Int = if (Build.VERSION.SDK_INT >= 36) {
        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
    } else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED

    private fun serviceActionIntent(request: AlertNotificationPost, action: String): PendingIntent {
        val intent = Intent(appContext, TvAlertForegroundService::class.java)
            .setAction(action)
            .setData(
                Uri.Builder()
                    .scheme(INTERNAL_SCHEME)
                    .authority(INTERNAL_AUTHORITY)
                    .appendPath(action.substringAfterLast('.'))
                    .appendPath(request.identity.tag)
                    .build(),
            )
            .putAlertPayload(request)
        return PendingIntent.getService(
            appContext,
            request.identity.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun Intent.putAlertPayload(request: AlertNotificationPost): Intent =
        putExtra(TvAlertIntentContract.EXTRA_PROFILE_KEY, request.profileKey)
            .putExtra(TvAlertIntentContract.EXTRA_REVIEW_ID, request.reviewId)
            .putExtra(TvAlertIntentContract.EXTRA_ACTION_NONCE, request.actionNonce)

    private fun channelAvailable(channelId: String): Boolean {
        if (!notifications.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val manager = appContext.getSystemService(NotificationManager::class.java)
        return manager.getNotificationChannel(channelId)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun NotificationClass.channelId(): String = when (this) {
        NotificationClass.ALERT -> AndroidNotificationChannels.ALERT_CHANNEL_ID
        NotificationClass.DETECTION -> AndroidNotificationChannels.DETECTION_CHANNEL_ID
    }

    private companion object {
        const val INTERNAL_SCHEME = "opah-internal"
        const val INTERNAL_AUTHORITY = "notification"
        const val SIGN_IN_NOTIFICATION_TAG = "opah-tv-alert-sign-in"
        const val SIGN_IN_NOTIFICATION_ID = 50_052
    }
}

internal const val LOCAL_TEST_THUMBNAIL_CONTENT_VERSION = "opah-local-fictional-thumbnail-v1"
