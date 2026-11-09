package app.opah.tv.notifications.android

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.opah.tv.data.model.AppSettings
import app.opah.tv.R
import app.opah.tv.ui.views.NativeTheme
import app.opah.tv.ui.views.nativeGlassPanelBackground

/** Remote focus starts on the card, never on an action. Only a fresh complete OK press invokes it. */
internal class InteractiveTvAlertCard @JvmOverloads constructor(
    context: Context, content: View = View(context), buttons: List<TvAlertOverlayButton> = emptyList(), appearance: AppSettings = AppSettings(),
    private val onInteraction: () -> Unit = {},
    private val onAction: (TvAlertOverlayActionKind) -> Unit = {},
    private val onDismiss: () -> Unit = {},
    private val onFocusLost: () -> Unit = {},
) : FrameLayout(context) {
    private val theme = NativeTheme.snapshot(context, appearance)
    private val interaction = TvAlertOverlayInteraction(SystemClock.uptimeMillis())
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val body = FrameLayout(context)
    private val row = LinearLayout(context).apply { setPadding(dp(12), dp(6), dp(12), dp(12)) }
    private var buttons = buttons.toList()
    private var hadWindowFocus = false

    init {
        tag = "alert:overlay:interactive"
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        background = context.nativeGlassPanelBackground(theme)
        column.addView(body)
        column.addView(TextView(context).apply {
            setText(R.string.tv_alert_interaction_hint)
            setTextColor(theme.palette.secondaryText)
            textSize = 13f
            setPadding(dp(12), 0, dp(12), 0)
        })
        column.addView(row)
        addView(column)
        update(content, buttons)
    }

    fun update(content: View, updatedButtons: List<TvAlertOverlayButton>) {
        content.background = null
        content.elevation = 0f
        if (body.getChildAt(0) !== content) {
            body.removeAllViews()
            body.addView(content)
        }
        contentDescription = content.contentDescription
        if (buttons.map { Triple(it.kind, it.label, it.binding) } != updatedButtons.map { Triple(it.kind, it.label, it.binding) }) interaction.reset()
        buttons = updatedButtons.toList()
        row.removeAllViews()
        buttons.forEachIndexed { index, button ->
            row.addView(TextView(context).apply {
                text = button.label
                textSize = 14f
                gravity = Gravity.CENTER
                maxLines = 2
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(theme.palette.text)
                setPadding(dp(6), dp(7), dp(6), dp(7))
                tag = "alert:overlay:action:${button.kind.name}"
                isClickable = true
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
                setOnClickListener { onAction(button.kind) }
            }, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { if (index > 0) marginStart = dp(5) })
        }
        paintSelection()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> { if (event.action == KeyEvent.ACTION_UP) onDismiss(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.action == KeyEvent.ACTION_DOWN) interaction.centerDown(event.downTime, event.repeatCount)
                if (event.action == KeyEvent.ACTION_UP) interaction.centerUp(event.downTime, event.isCanceled)?.let { index ->
                    buttons.getOrNull(index)?.let { onAction(it.kind) }
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_UP) {
                    val selected = interaction.selectedIndex
                    val next = when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> selected.coerceAtLeast(0)
                        KeyEvent.KEYCODE_DPAD_UP -> -1
                        KeyEvent.KEYCODE_DPAD_LEFT -> if (selected < 0) -1 else (selected - 1).coerceAtLeast(0)
                        else -> if (selected < 0) -1 else (selected + 1).coerceAtMost(buttons.lastIndex)
                    }
                    interaction.select(next, buttons.size)
                    paintSelection()
                    if (interaction.selectedIndex >= 0) onInteraction()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus && hadWindowFocus) onFocusLost()
        if (hasWindowFocus) hadWindowFocus = true
    }

    private fun paintSelection() {
        for (index in 0 until row.childCount) {
            val view = row.getChildAt(index)
            view.isSelected = index == interaction.selectedIndex
            view.background = GradientDrawable().apply {
                setColor(if (view.isSelected) theme.palette.panelSelected else android.graphics.Color.TRANSPARENT)
                cornerRadius = dp(6).toFloat()
                setStroke(dp(if (view.isSelected) 2 else 1), if (view.isSelected) theme.palette.focus else theme.palette.secondaryText)
            }
            if (view.isSelected) view.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
