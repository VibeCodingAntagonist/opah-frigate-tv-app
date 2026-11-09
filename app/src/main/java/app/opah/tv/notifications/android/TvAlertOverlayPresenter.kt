package app.opah.tv.notifications.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.opah.tv.notifications.AndroidNotificationIdentity
import app.opah.tv.data.model.AppSettings
import app.opah.tv.ui.views.NativeTheme
import app.opah.tv.ui.views.nativeGlassPanelBackground

internal object TvAlertOverlayPermission {
    fun isGranted(context: Context): Boolean = Settings.canDrawOverlays(context)
}

/**
 * Presents privacy-checked Frigate alerts and Home Assistant messages.
 *
 * Frigate interaction is explicitly opt-in; the window owner bounds focus, queues and timers.
 */
internal class TvAlertOverlayPresenter(
    context: Context,
    private val canDrawOverlays: () -> Boolean = { TvAlertOverlayPermission.isGranted(context.applicationContext) },
    private val addWindow: (View, WindowManager.LayoutParams) -> Unit = { view, params ->
        view.context.getSystemService(WindowManager::class.java).addView(view, params)
    },
    private val removeWindow: (View) -> Unit = { view ->
        view.context.getSystemService(WindowManager::class.java).removeViewImmediate(view)
    },
) {
    private val appContext = context.applicationContext
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var protectedActionVisible = false
    private var inlineVisible = false
    private val inlineDelegate = lazy {
        FrigateInlinePreviewOwner(appContext, canDrawOverlays, addWindow, removeWindow) { visible ->
            inlineVisible = visible
            owner.protectedActionVisible(protectedActionVisible || inlineVisible)
        }
    }
    private val owner by lazy {
        TvAlertOverlayWindowOwner(appContext, canDrawOverlays, addWindow, removeWindow,
            content = { ui, request, settings ->
                buildCard(ui, request.title, request.body, request.thumbnail, settings, request.appearance).also { card ->
                    if (request.homeAssistant && request.thumbnail != null) card.findViewWithTag<TextView>("alert:overlay:title").maxLines = 2
                }
            },
            params = { settings, image, interactive -> layoutParams(settings, image, interactive) },
            animate = { view, settings, appearance -> animateEntry(view, settings.verticalPosition, appearance.reducedMotion) },
        )
    }

    fun showHomeAssistant(
        server: String, tag: String, identity: AndroidNotificationIdentity, title: String, body: String,
        appearance: AppSettings, thumbnail: Bitmap? = null, buttons: List<TvAlertOverlayButton> = emptyList(),
        onDismiss: () -> Unit = {}, updateOnly: Boolean = false, canPresent: () -> Boolean,
    ) {
        owner.present(TvAlertOverlayRequest(server, tag, identity, title, body, thumbnail, appearance,
            homeAssistant = true, priority = 0, buttons = buttons, canPresent = canPresent, onDismiss = onDismiss), updateOnly)
    }

    fun show(
        profileKey: String, reviewId: String, identity: AndroidNotificationIdentity, title: String, body: String,
        thumbnail: Bitmap?, appearance: AppSettings, buttons: List<TvAlertOverlayButton> = emptyList(),
        canPresent: () -> Boolean = { false }, onDismiss: () -> Unit = {}, priority: Int = 2,
        updateOnly: Boolean = false,
    ): Boolean {
        if (!canDrawOverlays()) return false
        owner.present(TvAlertOverlayRequest(profileKey, reviewId, identity, title, body, thumbnail, appearance,
            homeAssistant = false, priority = priority, buttons = buttons, canPresent = canPresent, onDismiss = onDismiss), updateOnly)
        return true
    }

    fun dismissHomeAssistant() { owner.removeMatching { it.homeAssistant } }
    fun dismissFrigate() { owner.removeMatching { !it.homeAssistant }; inline { close() } }
    fun dismiss(identity: AndroidNotificationIdentity) { owner.removeMatching { it.identity == identity }; inline { removeMatching { it.identity == identity } } }
    fun dismiss(profileKey: String, reviewId: String) { owner.removeMatching { it.profileKey == profileKey && it.reviewId == reviewId }; inline { removeMatching { it.profileKey == profileKey && it.reviewId == reviewId } } }
    fun dismissProfile(profileKey: String) { owner.removeMatching { it.profileKey == profileKey }; inline { removeMatching { it.profileKey == profileKey } } }
    fun dismissAll() { owner.clear(); inline { close() } }
    fun revalidate() { owner.revalidate(); inline { revalidate() } }
    fun protectedActionVisible(visible: Boolean) { mainHandler.post {
        protectedActionVisible = visible
        if (visible && inlineDelegate.isInitialized()) inlineDelegate.value.close()
        owner.protectedActionVisible(visible || inlineVisible)
    } }
    private fun inline(work: FrigateInlinePreviewOwner.() -> Unit) { mainHandler.post {
        if (inlineDelegate.isInitialized()) inlineDelegate.value.work()
    } }
    internal suspend fun beginInlinePreview(request: InlineRecordingPreview): Long? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
        if (protectedActionVisible) { request.thumbnail?.recycle(); null } else inlineDelegate.value.begin(request)
    }
    internal suspend fun loadInlinePreview(ticket: Long, uri: String, client: okhttp3.OkHttpClient, allowed: () -> Boolean) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { inlineDelegate.value.load(ticket, uri, client, allowed) }
    internal suspend fun unavailableInlinePreview(ticket: Long) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
        inlineDelegate.value.unavailable(ticket)
    }
    fun showSetupPreview(identity: AndroidNotificationIdentity, appearance: AppSettings, canPresent: () -> Boolean, onShown: () -> Unit) {
        owner.present(TvAlertOverlayRequest("local-setup", identity.tag, identity, "Opah setup preview",
            "Fictional preview — no message was sent by Home Assistant", null, appearance,
            homeAssistant = true, priority = 0, buttons = emptyList(), canPresent = canPresent, onDismiss = {}, onShown = onShown))
    }
    suspend fun hasPending(identity: AndroidNotificationIdentity): Boolean = owner.contains(identity)

    internal fun buildCard(
        context: Context,
        title: String,
        body: String,
        thumbnail: Bitmap?,
        settings: TvAlertOverlaySettings,
        appearance: AppSettings,
    ): View {
        val theme = NativeTheme.snapshot(context, appearance)
        val card = FrameLayout(context).apply {
            background = context.nativeGlassPanelBackground(theme)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isFocusable = false
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "Alert, $title, $body"
            elevation = dp(8).toFloat()
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        card.addView(
            row,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        if (thumbnail != null) {
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(thumbnail)
                background = roundedBackground(
                    fill = Color.TRANSPARENT,
                    radius = dp(8).toFloat(),
                )
                clipToOutline = true
            }
            val (imageWidthDp, imageHeightDp) = imageDimensions(settings.imageSize)
            row.addView(
                image,
                LinearLayout.LayoutParams(dp(imageWidthDp), dp(imageHeightDp)).apply {
                    marginEnd = dp(10)
                },
            )
        }

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        row.addView(
            textColumn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        textColumn.addView(
            textView(
                context = context,
                text = title,
                sizeSp = 17f,
                color = theme.palette.text,
                style = Typeface.BOLD,
                maximumLines = 1,
            ).apply { tag = "alert:overlay:title" },
        )
        textColumn.addView(
            textView(
                context = context,
                text = body,
                sizeSp = 14f,
                color = theme.palette.secondaryText,
                style = Typeface.NORMAL,
                maximumLines = 2,
            ).apply {
                tag = "alert:overlay:body"
                setPadding(0, dp(3), 0, 0)
            },
        )
        return card
    }

    private fun imageDimensions(size: TvAlertOverlayImageSize): Pair<Int, Int> = when (size) {
        TvAlertOverlayImageSize.SMALL -> 96 to 54
        TvAlertOverlayImageSize.MEDIUM -> 144 to 81
        TvAlertOverlayImageSize.LARGE -> 208 to 117
    }

    private fun textView(
        context: Context,
        text: String,
        sizeSp: Float,
        color: Int,
        style: Int,
        maximumLines: Int,
    ) = TextView(context).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        setTypeface(typeface, style)
        maxLines = maximumLines
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    internal fun layoutParams(settings: TvAlertOverlaySettings, hasThumbnail: Boolean, interactive: Boolean = false): WindowManager.LayoutParams {
        val availableWidth = appContext.resources.displayMetrics.widthPixels - dp(64)
        val preferredWidth = when {
            !hasThumbnail -> TEXT_ONLY_CARD_WIDTH_DP
            settings.imageSize == TvAlertOverlayImageSize.SMALL -> SMALL_IMAGE_CARD_WIDTH_DP
            settings.imageSize == TvAlertOverlayImageSize.MEDIUM -> MEDIUM_IMAGE_CARD_WIDTH_DP
            else -> LARGE_IMAGE_CARD_WIDTH_DP
        }
        val width = dp(if (interactive) preferredWidth.coerceAtLeast(540) else preferredWidth).coerceAtMost(availableWidth).coerceAtLeast(dp(280))
        return WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or if (interactive) {
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            },
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = overlayGravity(settings)
            x = if (settings.horizontalPosition == TvAlertOverlayHorizontalPosition.CENTER) 0 else dp(20)
            y = dp(20)
            setTitle("Opah TV alert")
        }
    }

    private fun overlayGravity(settings: TvAlertOverlaySettings): Int {
        val vertical = when (settings.verticalPosition) {
            TvAlertOverlayVerticalPosition.TOP -> Gravity.TOP
            TvAlertOverlayVerticalPosition.BOTTOM -> Gravity.BOTTOM
        }
        val horizontal = when (settings.horizontalPosition) {
            TvAlertOverlayHorizontalPosition.LEFT -> Gravity.START
            TvAlertOverlayHorizontalPosition.CENTER -> Gravity.CENTER_HORIZONTAL
            TvAlertOverlayHorizontalPosition.RIGHT -> Gravity.END
        }
        return vertical or horizontal
    }

    internal fun animateEntry(card: View, position: TvAlertOverlayVerticalPosition, reducedMotion: Boolean) {
        card.animate().cancel()
        if (reducedMotion) {
            card.alpha = 1f
            card.translationY = 0f
            return
        }
        card.alpha = 0f
        card.translationY = if (position == TvAlertOverlayVerticalPosition.TOP) -dp(10).toFloat() else dp(10).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(ENTER_ANIMATION_MILLIS).start()
    }

    private fun roundedBackground(
        fill: Int,
        stroke: Int? = null,
        strokeWidth: Int = 0,
        radius: Float,
    ) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radius
        if (stroke != null && strokeWidth > 0) setStroke(strokeWidth, stroke)
    }

    private fun dp(value: Int): Int = (value * appContext.resources.displayMetrics.density).toInt()

    private companion object {
        const val TEXT_ONLY_CARD_WIDTH_DP = 410
        const val SMALL_IMAGE_CARD_WIDTH_DP = 480
        const val MEDIUM_IMAGE_CARD_WIDTH_DP = 530
        const val LARGE_IMAGE_CARD_WIDTH_DP = 620
        const val ENTER_ANIMATION_MILLIS = 180L
    }
}
