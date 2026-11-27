package app.opah.tv.ui.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.GridLayout
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import app.opah.tv.R
import app.opah.tv.data.model.AppearanceMode
import app.opah.tv.data.model.CustomThemeColors
import app.opah.tv.data.model.HslColor
import app.opah.tv.data.model.ThemeColorPolicy

internal data class NativeAppearanceUiState(
    val mode: AppearanceMode,
    val colors: CustomThemeColors,
    val reducedMotion: Boolean,
    val highContrast: Boolean,
    val reducedTransparency: Boolean = false,
)

internal data class NativeAppearancePosition(val focusedKey: String?, val scrollY: Int)

private data class ThemePreset(
    val name: String,
    val colors: CustomThemeColors,
)

@SuppressLint("ViewConstructor")
internal class NativeAppearanceSurface(
    private val activity: ComponentActivity,
    initialState: NativeAppearanceUiState,
    private val onMode: (AppearanceMode) -> Unit,
    private val onColors: (CustomThemeColors) -> Unit,
    private val onReducedMotion: (Boolean) -> Unit,
    private val onHighContrast: (Boolean) -> Unit,
    private val onFocused: (String, View) -> Unit,
    private val onReducedTransparency: (Boolean) -> Unit = {},
    val customEditor: Boolean = false,
    onCustomize: () -> Unit = {},
) : LinearLayout(activity) {
    private val labels = mutableListOf<TextView>()
    private val preview = NativeThemePreviewView(activity)
    private val modeChoices = linkedMapOf<AppearanceMode, NativeThemeSwatchView>()
    private val presetChoices = linkedMapOf<String, NativeThemeSwatchView>()
    private val accentHue = track("Accent hue") { direction -> adjustAccentHue(direction) }
    private val accentStrength = track("Accent strength") { direction -> adjustAccentStrength(direction) }
    private val accentBrightness = track("Accent brightness") { direction -> adjustAccentBrightness(direction) }
    private val backgroundHue = track("Background hue") { direction -> adjustBackgroundHue(direction) }
    private val backgroundStrength = track("Background strength") { direction -> adjustBackgroundStrength(direction) }
    private val backgroundBrightness = track("Background brightness") { direction -> adjustBackgroundBrightness(direction) }
    private val reduceMotion = toggle("Reduce motion") { onReducedMotion(!state.reducedMotion) }
    private val highContrast = toggle("High contrast") { onHighContrast(!state.highContrast) }
    private val reduceTransparency = toggle("Reduce transparency") { onReducedTransparency(!state.reducedTransparency) }
    private val customizeTheme = settingControl("Customize theme", "appearance:customize", onCustomize)
    private val useSavedTheme = settingControl("Use saved custom theme", "appearance:use-custom") {
        onMode(AppearanceMode.CUSTOM)
    }
    private val comfortControls = listOf(reduceMotion, highContrast, reduceTransparency)
    private val colorTracks = listOf(accentHue, accentStrength, accentBrightness, backgroundHue, backgroundStrength, backgroundBrightness)
    private var state = initialState
    private val editor = ScrollView(activity).apply {
        isFocusable = false
        clipToPadding = true
        clipChildren = true
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    private val presets = listOf(
        ThemePreset("Coral", CustomThemeColors(0xFFFF7048.toInt(), 0xFF07111F.toInt())),
        ThemePreset("Ocean", CustomThemeColors(0xFF53B7FF.toInt(), 0xFF071522.toInt())),
        ThemePreset("Forest", CustomThemeColors(0xFF65D68A.toInt(), 0xFF08170F.toInt())),
        ThemePreset("Plum", CustomThemeColors(0xFFD29BFF.toInt(), 0xFF180D20.toInt())),
        ThemePreset("Sunset", CustomThemeColors(0xFFFFB454.toInt(), 0xFF211108.toInt())),
        ThemePreset("Slate", CustomThemeColors(0xFF7DD8D2.toInt(), 0xFF11171A.toInt())),
    )

    init {
        orientation = VERTICAL
        clipChildren = true
        clipToPadding = true
        val content = LinearLayout(activity).apply {
            orientation = VERTICAL
            setPadding(activity.dp(3), activity.dp(2), activity.dp(3), activity.dp(8))
        }
        if (!customEditor) {
            content.addView(LinearLayout(activity).apply {
                orientation = HORIZONTAL
                listOf(AppearanceMode.SYSTEM, AppearanceMode.DARK, AppearanceMode.LIGHT).forEach { mode ->
                    val choice = NativeThemeSwatchView(activity).apply {
                        tag = "appearance:mode:" + mode.name
                        isFocusable = true
                        isClickable = true
                        contentDescription = modeLabel(mode) + " theme"
                        setOnClickListener { onMode(mode) }
                        onFocusChangeListener = focusListener(tag as String)
                    }
                    modeChoices[mode] = choice
                    addView(choice, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = activity.dp(5) })
                }
            })
        }
        content.addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, activity.dp(116)).apply {
            topMargin = activity.dp(8)
            bottomMargin = activity.dp(8)
        })
        if (customEditor) {
            content.addView(label("Palettes", 16f, bold = true).apply {
                setPadding(0, 0, 0, activity.dp(6))
            })
            content.addView(GridLayout(activity).apply {
                columnCount = 3
                presets.forEachIndexed { index, preset ->
                    val swatch = NativeThemeSwatchView(activity).apply {
                        tag = "appearance:preset:" + preset.name.lowercase()
                        isFocusable = true
                        isClickable = true
                        contentDescription = preset.name + " palette"
                        setOnClickListener { onColors(ThemeColorPolicy.sanitize(preset.colors)) }
                        onFocusChangeListener = focusListener(tag as String)
                    }
                    presetChoices[preset.name] = swatch
                    addView(swatch, GridLayout.LayoutParams(GridLayout.spec(index / 3), GridLayout.spec(index % 3, 1f)).apply {
                        width = 0
                        height = LayoutParams.WRAP_CONTENT
                        setMargins(0, 0, activity.dp(5), activity.dp(5))
                    })
                }
            })
            content.addView(useSavedTheme)
            content.addView(label("Fine-tune colors", 16f, bold = true).apply {
                setPadding(0, activity.dp(8), 0, activity.dp(5))
            })
            colorTracks.forEach(content::addView)
        } else {
            content.addView(label("Comfort and clarity", 16f, bold = true))
            content.addView(GridLayout(activity).apply {
                columnCount = 2
                comfortControls.forEachIndexed { index, control ->
                    addView(control, GridLayout.LayoutParams(GridLayout.spec(index / 2), GridLayout.spec(index % 2, 1f)).apply {
                        width = 0
                        height = LayoutParams.WRAP_CONTENT
                        setMargins(0, activity.dp(6), activity.dp(5), 0)
                    })
                }
            })
            content.addView(customizeTheme, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = activity.dp(8)
            })
        }
        editor.addView(content, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(editor, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        update(initialState)
    }

    fun update(next: NativeAppearanceUiState) {
        state = next.copy(colors = ThemeColorPolicy.sanitize(next.colors))
        val colors = state.colors
        val customAccent = colors.accentArgb
        val customBackground = colors.backgroundArgb
        modeChoices.forEach { (mode, view) ->
            val sample = modeSample(mode, colors)
            view.show(modeLabel(mode), sample.backgroundArgb, sample.accentArgb, state.mode == mode)
        }
        presets.forEach { preset ->
            val safe = ThemeColorPolicy.sanitize(preset.colors)
            presetChoices[preset.name]?.show(
                preset.name,
                safe.backgroundArgb,
                safe.accentArgb,
                state.mode == AppearanceMode.CUSTOM && colors == safe,
            )
        }
        if (customEditor) {
            val accent = ThemeColorPolicy.toHsl(customAccent)
            val background = ThemeColorPolicy.toHsl(customBackground)
            accentHue.show("Accent hue", "${accent.hue}°", hueColors(), accent.hue / 30, NativeAdjustmentRange(0f, 359f, accent.hue.toFloat(), wraps = true))
            accentStrength.show("Accent strength", "${accent.saturation}%", saturationColors(accent), accent.saturation / 10, NativeAdjustmentRange(0f, 100f, accent.saturation.toFloat()))
            accentBrightness.show("Accent brightness", "${accent.lightness}%", lightnessColors(accent), accent.lightness / 10, lightnessRange(accent.lightness))
            backgroundHue.show("Background hue", "${background.hue}°", hueColors(lightness = 28), background.hue / 30, NativeAdjustmentRange(0f, 359f, background.hue.toFloat(), wraps = true))
            backgroundStrength.show(
                "Background strength",
                "${background.saturation}%",
                saturationColors(background),
                background.saturation / 10,
                NativeAdjustmentRange(0f, 100f, background.saturation.toFloat()),
            )
            backgroundBrightness.show(
                "Background brightness",
                "${background.lightness}%",
                lightnessColors(background),
                background.lightness / 10,
                lightnessRange(background.lightness),
            )
            useSavedTheme.isSelected = state.mode == AppearanceMode.CUSTOM
        } else {
            reduceMotion.text = activity.getString(R.string.native_reduce_motion_state, activity.getString(if (state.reducedMotion) R.string.native_state_on else R.string.native_state_off))
            reduceMotion.isSelected = state.reducedMotion
            highContrast.text = activity.getString(R.string.native_high_contrast_state, activity.getString(if (state.highContrast) R.string.native_state_on else R.string.native_state_off))
            highContrast.isSelected = state.highContrast
            reduceTransparency.text = activity.getString(R.string.native_reduce_transparency_state, activity.getString(if (state.reducedTransparency) R.string.native_state_on else R.string.native_state_off))
            reduceTransparency.isSelected = state.reducedTransparency
            customizeTheme.isSelected = state.mode == AppearanceMode.CUSTOM
        }
        refreshTheme()
    }

    fun refreshTheme() {
        labels.forEach { it.setTextColor(NativeTheme.palette.text) }
        (if (customEditor) listOf(useSavedTheme) else comfortControls + customizeTheme).forEach {
            it.setTextColor(NativeTheme.palette.text)
            it.background = activity.nativeFlatFocusableBackground()
            it.contentDescription = it.text.toString().replace("   ", ", ")
        }
        modeChoices.values.forEach { it.refreshTheme() }
        presetChoices.values.forEach { it.refreshTheme() }
        if (customEditor) {
            colorTracks.forEach { it.refreshTheme() }
            preview.show(state.colors.accentArgb, state.colors.backgroundArgb)
        } else {
            preview.show(NativeTheme.palette.focus, NativeTheme.palette.background)
        }
    }

    fun capturePosition() = NativeAppearancePosition(findFocus()?.tag as? String, editor.scrollY)

    private fun lightnessRange(value: Int) = NativeAdjustmentRange(minOf(5, value).toFloat(), maxOf(95, value).toFloat(), value.toFloat())

    fun restorePosition(position: NativeAppearancePosition) {
        editor.post {
            val focused = position.focusedKey?.let { findViewWithTag<View>(it) }
                ?.takeIf { it.isShown && it.isFocusable && it.isEnabled } ?: return@post
            focused.requestFocus()
            editor.scrollTo(0, position.scrollY.coerceAtLeast(0))
        }
    }

    private fun adjustAccentHue(direction: Int) = onColors(
        state.colors.copy(accentArgb = ThemeColorPolicy.adjustHue(state.colors.accentArgb, direction * 10)),
    )

    private fun adjustAccentStrength(direction: Int) = onColors(
        state.colors.copy(accentArgb = ThemeColorPolicy.adjustSaturation(state.colors.accentArgb, direction * 5)),
    )

    private fun adjustAccentBrightness(direction: Int) = onColors(
        state.colors.copy(accentArgb = ThemeColorPolicy.adjustLightness(state.colors.accentArgb, direction * 5)),
    )

    private fun adjustBackgroundHue(direction: Int) = onColors(
        state.colors.copy(backgroundArgb = ThemeColorPolicy.adjustHue(state.colors.backgroundArgb, direction * 10)),
    )

    private fun adjustBackgroundStrength(direction: Int) = onColors(
        state.colors.copy(backgroundArgb = ThemeColorPolicy.adjustSaturation(state.colors.backgroundArgb, direction * 5)),
    )

    private fun adjustBackgroundBrightness(direction: Int) = onColors(
        state.colors.copy(backgroundArgb = ThemeColorPolicy.adjustLightness(state.colors.backgroundArgb, direction * 3)),
    )

    private fun track(label: String, onAdjust: (Int) -> Unit) = NativeColorTrackView(activity).apply {
        tag = "appearance:track:${label.lowercase().replace(' ', '-')}"
        setAdjustmentListener(onAdjust)
        onFocusChangeListener = focusListener(tag as String)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(5) }
    }

    private fun settingControl(label: String, key: String, onClick: () -> Unit) = TextView(activity).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER_VERTICAL
        minHeight = activity.dp(44)
        maxLines = 2
        setPadding(activity.dp(10), activity.dp(6), activity.dp(10), activity.dp(6))
        setTextColor(NativeTheme.palette.text)
        background = activity.nativeFlatFocusableBackground()
        isFocusable = true
        isClickable = true
        tag = key
        setOnClickListener { onClick() }
        onFocusChangeListener = focusListener(key)
    }

    private fun toggle(label: String, onClick: () -> Unit) = settingControl(
        label, "appearance:toggle:${label.lowercase().replace(' ', '-')}", onClick,
    ).apply {
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Switch"
                info.isCheckable = true
                @Suppress("DEPRECATION")
                info.isChecked = host.isSelected
            }
        }
    }

    private fun focusListener(key: String) = OnFocusChangeListener { view, focused ->
        view.invalidate()
        if (focused) onFocused(key, view)
    }

    private fun label(value: String, size: Float, bold: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(NativeTheme.palette.text)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        labels.add(this)
    }

    private fun modeLabel(mode: AppearanceMode): String = when (mode) {
        AppearanceMode.SYSTEM -> "System"
        AppearanceMode.DARK -> "Dark"
        AppearanceMode.LIGHT -> "Light"
        AppearanceMode.CUSTOM -> "Custom"
    }

    private fun modeSample(mode: AppearanceMode, custom: CustomThemeColors): CustomThemeColors = when (mode) {
        AppearanceMode.SYSTEM -> if (
            resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        ) CustomThemeColors(0xFFFF7048.toInt(), 0xFF101319.toInt()) else CustomThemeColors(0xFFD94B25.toInt(), 0xFFF2F4F7.toInt())
        AppearanceMode.DARK -> CustomThemeColors(0xFFFF7048.toInt(), 0xFF101319.toInt())
        AppearanceMode.LIGHT -> CustomThemeColors(0xFFD94B25.toInt(), 0xFFF2F4F7.toInt())
        AppearanceMode.CUSTOM -> custom
    }

    private fun hueColors(lightness: Int = 55): IntArray = IntArray(12) { index ->
        ThemeColorPolicy.hslToArgb(HslColor(index * 30, 82, lightness))
    }

    private fun saturationColors(base: HslColor): IntArray = IntArray(11) { index ->
        ThemeColorPolicy.hslToArgb(base.copy(saturation = index * 10))
    }

    private fun lightnessColors(base: HslColor): IntArray = IntArray(11) { index ->
        ThemeColorPolicy.hslToArgb(base.copy(lightness = (index * 9 + 5).coerceAtMost(95)))
    }
}

private class NativeThemeSwatchView(context: Context) : LinearLayout(context) {
    private val sample = View(context)
    private val labelView = TextView(context).apply {
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 2
        gravity = Gravity.CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        orientation = VERTICAL
        setPadding(context.dp(7), context.dp(7), context.dp(7), context.dp(7))
        minimumHeight = context.dp(64)
        addView(sample, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(17)).apply { bottomMargin = context.dp(5) })
        addView(labelView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun show(label: String, background: Int, accent: Int, selected: Boolean) {
        labelView.text = label
        isSelected = selected
        sample.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(background, background, accent)).apply {
            cornerRadius = context.dp(5).toFloat()
            setStroke(context.dp(1), NativeTheme.palette.divider)
        }
        contentDescription = label + if (selected) ", selected" else ", not selected"
        refreshTheme()
    }

    fun refreshTheme() {
        background = context.nativeFlatFocusableBackground()
        labelView.setTextColor(NativeTheme.palette.text)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.RadioButton"
        info.isCheckable = true
        @Suppress("DEPRECATION")
        info.isChecked = isSelected
    }
}

private class NativeColorTrackView(context: Context) : LinearLayout(context) {
    private val labelView = TextView(context).apply {
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val valueView = TextView(context).apply {
        textSize = 13f
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val ramp = NativeColorRampView(context)
    private var onAdjust: (Int) -> Unit = {}
    private var fraction = 0f
    private var adjustmentRange = NativeAdjustmentRange(0f, 1f, 0f)

    init {
        orientation = VERTICAL
        isFocusable = true
        isClickable = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        setPadding(context.dp(12), context.dp(9), context.dp(12), context.dp(10))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(labelView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(valueView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(10) })
        })
        addView(ramp, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(14)).apply { topMargin = context.dp(7) })
        setOnClickListener { onAdjust(1) }
        refreshTheme()
    }

    fun setAdjustmentListener(listener: (Int) -> Unit) { onAdjust = listener }

    fun show(label: String, value: String, colors: IntArray, selectedIndex: Int, range: NativeAdjustmentRange) {
        adjustmentRange = range
        labelView.text = label
        valueView.text = value
        fraction = if (colors.size > 1) selectedIndex.coerceIn(colors.indices).toFloat() / (colors.size - 1) else 0f
        ramp.show(colors, fraction)
        contentDescription = label + ", " + value + ", use left and right to change"
    }

    fun refreshTheme() {
        background = context.nativeFlatFocusableBackground()
        labelView.setTextColor(NativeTheme.palette.text)
        valueView.setTextColor(NativeTheme.palette.secondaryText)
        ramp.invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> { onAdjust(-1); true }
        KeyEvent.KEYCODE_DPAD_RIGHT -> { onAdjust(1); true }
        else -> super.onKeyDown(keyCode, event)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, adjustmentRange.minimum, adjustmentRange.maximum, adjustmentRange.current)
        if (isEnabled && adjustmentRange.canAdjust(1)) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        if (isEnabled && adjustmentRange.canAdjust(-1)) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> adjustAccessibly(1)
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> adjustAccessibly(-1)
        else -> super.performAccessibilityAction(action, arguments)
    }

    private fun adjustAccessibly(direction: Int): Boolean {
        if (!isEnabled || !adjustmentRange.canAdjust(direction)) return false
        onAdjust(direction)
        return true
    }
}

private class NativeColorRampView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var colors = intArrayOf(NativeTheme.palette.focus)
    private var fraction = 0f

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun show(colors: IntArray, fraction: Float) {
        this.colors = colors
        this.fraction = fraction
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val inset = context.dp(4).toFloat()
        val available = (width - inset * 2).coerceAtLeast(0f)
        colors.forEachIndexed { index, color ->
            paint.color = color
            canvas.drawRect(inset + available * index / colors.size, height * 0.25f, inset + available * (index + 1) / colors.size, height * 0.75f, paint)
        }
        paint.color = NativeTheme.palette.text
        canvas.drawCircle(inset + fraction * available, height / 2f, height * 0.4f, paint)
        paint.color = NativeTheme.palette.background
        canvas.drawCircle(inset + fraction * available, height / 2f, height * 0.18f, paint)
    }
}
