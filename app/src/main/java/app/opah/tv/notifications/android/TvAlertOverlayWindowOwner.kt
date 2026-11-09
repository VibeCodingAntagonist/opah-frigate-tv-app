package app.opah.tv.notifications.android

import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import app.opah.tv.data.model.AppSettings
import app.opah.tv.notifications.AndroidNotificationIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class TvAlertOverlayActionKind { OPEN, RECORDING, LIVE, ACTIVITY, SNOOZE, HA_FIRST, HA_SECOND, HA_THIRD,
    PREVIEW, PREVIEW_PLAY, PREVIEW_AUDIO, PREVIEW_OPEN }
internal data class TvAlertOverlayButton(val kind: TvAlertOverlayActionKind, val label: String,
    val binding: String = kind.name, val invokeBounded: ((Long) -> Unit)? = null, val invoke: () -> Unit)

internal data class TvAlertOverlayRequest(
    val profileKey: String, val reviewId: String, val identity: AndroidNotificationIdentity,
    val title: String, val body: String, val thumbnail: Bitmap?, val appearance: AppSettings,
    val homeAssistant: Boolean, val priority: Int, val buttons: List<TvAlertOverlayButton>,
    val canPresent: () -> Boolean, val onDismiss: () -> Unit,
    val receivedAtMillis: Long = SystemClock.elapsedRealtime(),
    val onShown: () -> Unit = {},
)

/** Owns every queued window, timer and Back callback on the main thread. No media is fetched here. */
internal class TvAlertOverlayWindowOwner(
    private val context: Context,
    private val canDrawOverlays: () -> Boolean,
    private val addWindow: (View, WindowManager.LayoutParams) -> Unit,
    private val removeWindow: (View) -> Unit,
    private val content: (Context, TvAlertOverlayRequest, TvAlertOverlaySettings) -> View,
    private val params: (TvAlertOverlaySettings, Boolean, Boolean) -> WindowManager.LayoutParams,
    private val animate: (View, TvAlertOverlaySettings, AppSettings) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val settings = TvAlertOverlaySettingsStore(context)
    private val queue = TvAlertOverlayQueue<TvAlertOverlayRequest>({ it.identity.tag }, { it.priority }, { it.receivedAtMillis })
    @Volatile private var active: Active? = null
    private var protectedActionVisible = false

    suspend fun contains(identity: AndroidNotificationIdentity): Boolean = withContext(Dispatchers.Main.immediate) {
        active?.request?.identity == identity || queue.find(identity.tag) != null
    }

    fun present(request: TvAlertOverlayRequest, updateOnly: Boolean = false) = handler.post {
        if (protectedActionVisible) return@post
        if (!permitted(request)) { removeMatching { it.identity == request.identity }; return@post }
        val current = active
        if (current?.request?.identity == request.identity) {
            current.request = request.copy(receivedAtMillis = current.request.receivedAtMillis)
            val body = content(current.view.context, request, current.settings)
            (current.view as? InteractiveTvAlertCard)?.update(body, request.buttons) ?: run {
                // Replacing a passive card cannot capture input, and its original timers remain intact.
                (current.view as android.widget.FrameLayout).apply {
                    removeAllViews()
                    addView(body)
                    contentDescription = body.contentDescription
                }
            }
            return@post
        }
        val waiting = queue.find(request.identity.tag)
        if (updateOnly) {
            if (waiting != null) queue.offer(request.copy(receivedAtMillis = waiting.receivedAtMillis))
            return@post
        }
        val pending = request.copy(receivedAtMillis = waiting?.receivedAtMillis ?: request.receivedAtMillis)
        if (current == null) show(pending) else if (current.request.homeAssistant && !request.homeAssistant && current.view !is InteractiveTvAlertCard) {
            close(current, advance = false)
            show(pending)
        } else queue.offer(pending)
    }

    fun removeMatching(predicate: (TvAlertOverlayRequest) -> Boolean) = handler.post {
        queue.removeWhere(predicate)
        active?.takeIf { predicate(it.request) }?.let { close(it) }
    }

    fun clear() = handler.post { queue.clear(); active?.let { close(it, advance = false) } }

    fun protectedActionVisible(visible: Boolean) {
        val apply: () -> Unit = {
            protectedActionVisible = visible
            if (visible) { queue.clear(); active?.let { close(it, advance = false) } }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) apply() else handler.post(apply)
    }

    fun revalidate() {
        if (active == null) return
        handler.post {
            queue.removeWhere { !permitted(it) }
            active?.takeIf { !permitted(it.request) }?.let { close(it) }
        }
    }

    private fun permitted(request: TvAlertOverlayRequest): Boolean =
        canDrawOverlays() && runCatching(request.canPresent).getOrDefault(false)

    private fun show(request: TvAlertOverlayRequest) {
        if (!permitted(request)) { next(); return }
        val preferences = settings.load()
        if (preferences.displayDurationSeconds == 0) return
        val interactive = request.buttons.isNotEmpty() && (request.homeAssistant || preferences.interactiveFrigateAlerts)
        val ui = runCatching { if (Build.VERSION.SDK_INT >= 30) {
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            context.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else context }.getOrElse { next(); return }
        val body = content(ui, request, preferences)
        lateinit var overlay: Active
        val view = if (interactive) InteractiveTvAlertCard(
            ui, body, request.buttons, request.appearance,
            onInteraction = { active?.takeIf { it === overlay }?.let(::extendIdle) },
            onAction = { kind -> active?.takeIf { it === overlay }?.let { perform(it, kind) } },
            onDismiss = { active?.takeIf { it === overlay }?.let(::dismissByUser) },
            onFocusLost = { handler.post { active?.takeIf { it === overlay }?.let { queue.clear(); close(it, advance = false) } } },
        ) else android.widget.FrameLayout(ui).apply { addView(body); contentDescription = body.contentDescription }
        overlay = Active(request, preferences, view)
        if (runCatching { addWindow(view, params(preferences, request.thumbnail != null, interactive)) }.isFailure) return
        active = overlay
        view.post { if (active === overlay && permitted(request) && view.isAttachedToWindow) runCatching(request.onShown) }
        animate(view, preferences, request.appearance)
        handler.postDelayed(overlay.expire, tvAlertOverlayDisplayDurationMillis(preferences.displayDurationSeconds))
        handler.postDelayed(overlay.maximum, TV_ALERT_INTERACTION_MAX_MILLIS)
        handler.postDelayed(overlay.revalidate, REVALIDATE_MILLIS)
        if (interactive) {
            view.requestFocus()
            if (Build.VERSION.SDK_INT >= 33) view.post {
                if (active !== overlay) return@post
                val dispatcher = view.findOnBackInvokedDispatcher()
                if (dispatcher == null) { close(overlay); return@post }
                val callback = OnBackInvokedCallback { if (active === overlay) dismissByUser(overlay) }
                overlay.back = { dispatcher.unregisterOnBackInvokedCallback(callback) }
                dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            }
        }
    }

    private fun extendIdle(overlay: Active) {
        handler.removeCallbacks(overlay.expire)
        handler.postDelayed(overlay.expire, TV_ALERT_INTERACTION_IDLE_MILLIS)
    }

    private fun perform(overlay: Active, kind: TvAlertOverlayActionKind) {
        if (!permitted(overlay.request)) { close(overlay); return }
        val action = overlay.request.buttons.firstOrNull { it.kind == kind } ?: return
        // Opening video or snoozing must not immediately hand focus to another waiting popup.
        queue.clear()
        // The trusted, deliberate user action runs while this window is still visible to Android.
        runCatching {
            action.invokeBounded?.invoke(overlay.shownAtElapsedMillis + TV_ALERT_INTERACTION_MAX_MILLIS) ?: action.invoke()
        }
        close(overlay, advance = false)
    }

    private fun dismissByUser(overlay: Active) {
        queue.clear()
        close(overlay, advance = false)
        runCatching(overlay.request.onDismiss)
    }

    private fun close(overlay: Active, advance: Boolean = true) {
        if (active !== overlay) return
        active = null
        handler.removeCallbacks(overlay.expire)
        handler.removeCallbacks(overlay.maximum)
        handler.removeCallbacks(overlay.revalidate)
        runCatching { overlay.back?.invoke() }
        overlay.view.animate().cancel()
        runCatching { removeWindow(overlay.view) }
        if (advance) next()
    }

    private fun next() {
        while (active == null) {
            val request = queue.take(SystemClock.elapsedRealtime()) ?: return
            if (permitted(request)) show(request)
        }
    }

    private inner class Active(var request: TvAlertOverlayRequest, val settings: TvAlertOverlaySettings, val view: View) {
        val shownAtElapsedMillis = SystemClock.elapsedRealtime()
        var back: (() -> Unit)? = null
        val expire = Runnable { if (active === this) close(this) }
        val maximum = Runnable { if (active === this) close(this) }
        val revalidate: Runnable = object : Runnable {
            override fun run() {
                if (active !== this@Active) return
                if (!permitted(request) || SystemClock.elapsedRealtime() - shownAtElapsedMillis >= TV_ALERT_INTERACTION_MAX_MILLIS) {
                    close(this@Active)
                } else handler.postDelayed(this, REVALIDATE_MILLIS)
            }
        }
    }

    private companion object { const val REVALIDATE_MILLIS = 250L }
}
