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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.opah.tv.data.model.AppSettings
import app.opah.tv.notifications.AndroidNotificationIdentity
import app.opah.tv.playback.RecordedPlayerFactory
import app.opah.tv.ui.views.NativeTheme
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

internal data class InlineRecordingPreview(
    val profileKey: String,
    val reviewId: String,
    val identity: AndroidNotificationIdentity,
    val title: String,
    val appearance: AppSettings,
    /** An owned, bounded copy of the already permitted alert picture. */
    val thumbnail: Bitmap?,
    val deadlineElapsedMillis: Long,
    val canPresent: () -> Boolean,
    val openInOpah: () -> Unit,
)

/** One deliberately opened recording preview; no player or media request exists before load(). */
@androidx.annotation.OptIn(UnstableApi::class)
internal class FrigateInlinePreviewOwner(
    private val context: Context,
    private val canDrawOverlays: () -> Boolean,
    private val addWindow: (View, WindowManager.LayoutParams) -> Unit,
    private val removeWindow: (View) -> Unit,
    private val screenAwake: () -> Boolean = { context.getSystemService(android.os.PowerManager::class.java)?.isInteractive == true },
    private val visibilityChanged: (Boolean) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var sequence = 0L
    private var active: Active? = null
    var playersCreated = 0
        private set
    var playersReleased = 0
        private set

    fun begin(request: InlineRecordingPreview): Long? {
        check(Looper.myLooper() == Looper.getMainLooper())
        close()
        if (!canDrawOverlays() || !permitted(screenAwake) || !permitted(request.canPresent) || request.deadlineElapsedMillis <= SystemClock.elapsedRealtime()) {
            request.thumbnail?.recycle(); return null
        }
        val ui = runCatching {
            if (Build.VERSION.SDK_INT >= 30) context.createDisplayContext(context.getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY)).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else context
        }.getOrElse { request.thumbnail?.recycle(); return null }
        val item = runCatching { Active(++sequence, request, ui) }.getOrElse { request.thumbnail?.recycle(); return null }
        active = item
        visibilityChanged(true)
        if (runCatching { addWindow(item.card, item.params()) }.isFailure) { close(); return null }
        item.card.requestFocus()
        handler.postDelayed(item.idle, 15_000)
        handler.postDelayed(item.tick, 250)
        if (Build.VERSION.SDK_INT >= 33) item.card.post {
            if (active !== item) return@post
            val dispatcher = item.card.findOnBackInvokedDispatcher()
            if (dispatcher == null) { close(); return@post }
            val callback = OnBackInvokedCallback { if (active === item) close() }
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            item.removeBack = { dispatcher.unregisterOnBackInvokedCallback(callback) }
        }
        return item.ticket
    }

    fun load(ticket: Long, uri: String, client: OkHttpClient, canPlay: () -> Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val item = active?.takeIf { it.ticket == ticket } ?: return
        if (item.player != null || item.failed || item.playbackAllowed != null) return
        if (!valid(item) || !permitted(canPlay)) { close(); return }
        val origin = uri.toHttpUrlOrNull() ?: run { unavailable(ticket); return }
        if (origin.scheme !in setOf("http", "https")) { unavailable(ticket); return }
        item.playbackAllowed = canPlay
        fun checkRequest(target: okhttp3.HttpUrl) {
            if (!permitted(canPlay) || target.scheme != origin.scheme || target.host != origin.host || target.port != origin.port)
                throw IOException("Recording preview is no longer permitted")
        }
        val guarded = client.newBuilder().cache(null).followRedirects(false).followSslRedirects(false).addInterceptor { chain ->
            checkRequest(chain.request().url)
            chain.proceed(chain.request())
        }.addNetworkInterceptor { chain ->
            checkRequest(chain.request().url)
            chain.proceed(chain.request())
        }.build()
        val player = runCatching { RecordedPlayerFactory.create(item.ui, guarded, preferSoftwareVideoDecoder = false) }
            .getOrElse { unavailable(ticket); return }
        playersCreated++
        item.player = player
        player.volume = 0f
        player.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, false)
        item.video.player = player
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (active !== item) return
                if (state == Player.STATE_ENDED) close()
                else if (state == Player.STATE_READY) {
                    if (!valid(item)) { close(); return }
                    item.ready = true; item.media.visibility = View.VISIBLE; item.video.visibility = View.VISIBLE; item.image.visibility = View.GONE; item.render()
                }
            }
            override fun onPlayerError(error: PlaybackException) { if (active === item) unavailable(ticket) }
        })
        if (!valid(item)) { close(); return }
        player.setMediaItem(MediaItem.fromUri(uri)); player.prepare(); player.playWhenReady = true
    }

    fun unavailable(ticket: Long) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val item = active?.takeIf { it.ticket == ticket } ?: return
        item.releasePlayer(); item.playbackAllowed = null; item.failed = true; item.ready = false
        if (!valid(item)) { close(); return }
        item.video.visibility = View.GONE; item.image.visibility = View.VISIBLE
        item.media.visibility = if (item.request.thumbnail == null) View.GONE else View.VISIBLE; item.render()
    }

    fun removeMatching(predicate: (InlineRecordingPreview) -> Boolean) { active?.takeIf { predicate(it.request) }?.let { close() } }
    fun revalidate() { active?.takeIf { !valid(it) }?.let { close() } }
    fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val item = active ?: return
        active = null; sequence++
        handler.removeCallbacks(item.tick); handler.removeCallbacks(item.idle)
        runCatching { item.removeBack?.invoke() }; item.releasePlayer()
        item.image.setImageDrawable(null); runCatching { removeWindow(item.card) }
        item.request.thumbnail?.recycle()
        visibilityChanged(false)
    }

    private fun permitted(gate: () -> Boolean) = runCatching(gate).getOrDefault(false)
    private fun valid(item: Active) = canDrawOverlays() && permitted(screenAwake) && SystemClock.elapsedRealtime() < item.request.deadlineElapsedMillis &&
        permitted(item.request.canPresent) && item.playbackAllowed?.let(::permitted) != false

    private inner class Active(val ticket: Long, val request: InlineRecordingPreview, val ui: Context) {
        val theme = NativeTheme.snapshot(ui, request.appearance)
        val image = ImageView(ui).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(request.thumbnail) }
        val video = PlayerView(ui).apply { useController = false; visibility = View.INVISIBLE; setKeepContentOnPlayerReset(false) }
        val label = TextView(ui).apply { textSize = 15f; setTextColor(theme.palette.secondaryText); setPadding(dp(12), 0, dp(12), dp(6)) }
        val width = dp(560).coerceAtMost(ui.resources.displayMetrics.widthPixels - dp(40))
        val media = FrameLayout(ui).apply {
            addView(image, FrameLayout.LayoutParams(-1,-1)); addView(video,FrameLayout.LayoutParams(-1,-1))
            visibility = if (request.thumbnail == null) View.GONE else View.VISIBLE
        }
        val content = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(ui).apply { text = request.title; textSize = 18f; maxLines = 2; setTextColor(theme.palette.text); setPadding(dp(12), dp(10), dp(12), dp(5)) })
            addView(media, LinearLayout.LayoutParams(-1, (this@Active.width * 9 / 16).coerceAtMost(ui.resources.displayMetrics.heightPixels - dp(180))))
            addView(label)
        }
        var player: ExoPlayer? = null
        var playbackAllowed: (() -> Boolean)? = null
        var failed = false
        var ready = false
        var removeBack: (() -> Unit)? = null
        val idle = Runnable { if (active === this) close() }
        val card = InteractiveTvAlertCard(ui, content, emptyList(), request.appearance,
            onInteraction = { extend() }, onAction = { kind -> act(kind) }, onDismiss = { close() },
            onFocusLost = { handler.post { if (active === this) close() } }).also { render(it) }
        val tick: Runnable = object : Runnable {
            override fun run() { if (active !== this@Active) return; if (!valid(this@Active)) close() else handler.postDelayed(this,250) }
        }
        fun params() = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT).apply {
                val settings = TvAlertOverlaySettingsStore(context).load()
                gravity = (if (settings.verticalPosition == TvAlertOverlayVerticalPosition.TOP) android.view.Gravity.TOP else android.view.Gravity.BOTTOM) or
                    when(settings.horizontalPosition) { TvAlertOverlayHorizontalPosition.LEFT -> android.view.Gravity.START; TvAlertOverlayHorizontalPosition.RIGHT -> android.view.Gravity.END; else -> android.view.Gravity.CENTER_HORIZONTAL }
                x = if (settings.horizontalPosition == TvAlertOverlayHorizontalPosition.CENTER) 0 else dp(20); y = dp(20); setTitle("Opah recording preview")
            }
        fun render(target: InteractiveTvAlertCard = card) {
            label.text = when { failed -> "Preview unavailable — you can open the recording in Opah"; !ready -> "Preparing recording preview"; player?.volume == 0f -> "Muted · Back to return"; else -> "Sound on · Back to return" }
            target.update(content, buildList {
                if (ready) {
                    add(TvAlertOverlayButton(TvAlertOverlayActionKind.PREVIEW_PLAY, if (player?.playWhenReady == true) "Pause" else "Play") {})
                    add(TvAlertOverlayButton(TvAlertOverlayActionKind.PREVIEW_AUDIO, if (player?.volume == 0f) "Sound on" else "Mute") {})
                }
                add(TvAlertOverlayButton(TvAlertOverlayActionKind.PREVIEW_OPEN, "Open in Opah") {})
            })
        }
        fun extend() { handler.removeCallbacks(idle); handler.postDelayed(idle,15_000) }
        fun act(kind: TvAlertOverlayActionKind) {
            if (!valid(this)) { close(); return }
            extend()
            when(kind) {
                TvAlertOverlayActionKind.PREVIEW_PLAY -> { player?.let { it.playWhenReady = !it.playWhenReady }; render() }
                TvAlertOverlayActionKind.PREVIEW_AUDIO -> { player?.let { it.volume = if (it.volume == 0f) 1f else 0f }; render() }
                TvAlertOverlayActionKind.PREVIEW_OPEN -> { runCatching(request.openInOpah); close() }
                else -> Unit
            }
        }
        fun releasePlayer() { video.player = null; player?.let { it.release(); playersReleased++ }; player = null }
        fun dp(value: Int) = (value * ui.resources.displayMetrics.density).toInt()
    }
}
