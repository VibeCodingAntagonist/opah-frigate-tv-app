package app.opah.tv.ui.views

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.os.Parcelable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import androidx.core.view.doOnPreDraw
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import app.opah.tv.R

internal data class NativeSettingsUiState(
    val title: String,
    val subtitle: String,
    val categories: List<NativeRowModel>,
    val detailRows: List<NativeRowModel>,
    val selectedCategoryKey: String?,
    val detailTabs: List<NativeBrowserTab> = emptyList(),
    val preferredDetailFocusKey: String? = null,
    val detailContent: View? = null,
)

/** View-only bookmarks. No settings values, private content, or action drafts are retained. */
internal data class NativeSettingsPosition(
    val categoryKey: String?,
    val focusedKey: String?,
    val focusInDetails: Boolean,
    val categoryScroll: Parcelable?,
    val detailScroll: Parcelable?,
)

@SuppressLint("ViewConstructor", "SetTextI18n")
internal class NativeSettingsSurface(
    private val activity: ComponentActivity,
    initialState: NativeSettingsUiState,
    private val onActivate: (String) -> Unit,
    private val onFocused: (String, View) -> Unit,
    private val onAdjust: (String, Int) -> Unit,
    initialPosition: NativeSettingsPosition? = null,
    sectionTitle: String = "Settings",
) : LinearLayout(activity) {
    private val title = TextView(activity).apply {
        textSize = 20f
        includeFontPadding = false
        setTextColor(NativeTheme.palette.text)
        setTypeface(typeface, Typeface.BOLD)
    }
    private val subtitle = TextView(activity).apply {
        textSize = 14f
        setTextColor(NativeTheme.palette.secondaryText)
        maxLines = 2
    }
    private val categoryList = NativeSettingsColumn(activity).apply {
        isFocusable = false
        itemAnimator = null
        overScrollMode = View.OVER_SCROLL_NEVER
        setPadding(activity.dp(2), activity.dp(2), activity.dp(2), activity.dp(6))
    }
    private val detailList = NativeSettingsColumn(activity).apply {
        tag = "settings:reading"
        isFocusable = false
        isFocusableInTouchMode = true
        itemAnimator = null
        overScrollMode = View.OVER_SCROLL_NEVER
        setPadding(activity.dp(2), activity.dp(2), activity.dp(2), activity.dp(6))
    }
    private val detailScrollGuide = TextView(activity).apply {
        textSize = 12f
        setTextColor(NativeTheme.palette.secondaryText)
        visibility = View.GONE
        setPadding(activity.dp(12), 0, 0, activity.dp(4))
    }
    private val detailTabs = LinearLayout(activity).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        setPadding(activity.dp(4), activity.dp(2), activity.dp(4), activity.dp(8))
    }
    private val standardDetailColumn = LinearLayout(activity).apply {
        orientation = VERTICAL
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(0, activity.dp(2), 0, 0)
        addView(detailTabs, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(detailScrollGuide, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(detailList, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }
    // Scrolling content must stay below the fixed title and subtitle.
    private val detailHost = FrameLayout(activity)
    private var currentDetailContent: View? = null
    private var preferDetailFocus = false
    private var rememberedDetailKey: String? = null
    private var detailHasActions = false
    private var selectedCategoryKey: String? = initialState.selectedCategoryKey
    private var enterCategoryKey: String? = null
    private val categoryAdapter = NativeListAdapter(
        onActivate = onActivate,
        compact = true,
        onFocused = { key, view ->
            preferDetailFocus = false
            if (enterCategoryKey != key) enterCategoryKey = null
            onFocused(key, view)
        },
    )
    private val detailAdapter = NativeListAdapter(
        onActivate = onActivate,
        onAdjust = onAdjust,
        focusInformationRows = true,
        onFocused = { key, view ->
            preferDetailFocus = true
            rememberedDetailKey = key
            onFocused(key, view)
        },
    )
    private var firstUpdate = true
    private var updateGeneration = 0
    private var locationKey: String? = null
    private val detailPositions = mutableMapOf<String, NativeSettingsPosition>()
    private var pendingPosition = initialPosition
    private var detailUpdatePending = false
    private var enterDetailsAfterUpdate = false

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.TRANSPARENT)
        clipChildren = false
        clipToPadding = false
        addView(
            TextView(activity).apply {
                tag = "settings:heading"
                text = sectionTitle
                textSize = 26f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(NativeTheme.palette.text)
                includeFontPadding = false
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = activity.dp(10) },
        )
        categoryList.adapter = categoryAdapter
        detailList.adapter = detailAdapter
        addView(
            LinearLayout(activity).apply {
                orientation = HORIZONTAL
                clipChildren = false
                addView(
                    LinearLayout(activity).apply {
                        tag = "settings:categories-panel"
                        orientation = VERTICAL
                        background = activity.nativeGlassPanelBackground()
                        setPadding(activity.dp(4), activity.dp(4), activity.dp(4), activity.dp(4))
                        addView(categoryList, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
                    },
                    LayoutParams(activity.dp(210), LayoutParams.MATCH_PARENT),
                )
                addView(
                    LinearLayout(activity).apply {
                        tag = "settings:detail-panel"
                        orientation = VERTICAL
                        background = activity.nativeGlassPanelBackground(strong = true)
                        setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(8))
                        addView(title)
                        addView(subtitle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                            topMargin = activity.dp(3)
                            bottomMargin = activity.dp(6)
                        })
                        addView(detailHost, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
                    },
                    LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                        marginStart = activity.dp(12)
                    },
                )
            },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
        )
        update(initialState)
    }

    fun update(state: NativeSettingsUiState) {
        val generation = ++updateGeneration
        val nextLocation = "${state.selectedCategoryKey}:${state.detailTabs.firstOrNull { it.selected }?.key.orEmpty()}"
        val changedLocation = locationKey != nextLocation
        if (changedLocation) locationKey?.let {
            val position = capturePosition()
            detailPositions[it] = position.copy(focusedKey = position.focusedKey.takeIf { position.focusInDetails } ?: rememberedDetailKey)
        }
        val hadDetailFocus = detailHost.hasFocus()
        val focusedBefore = findFocus()?.tag as? String
        val restore = pendingPosition?.takeIf { it.categoryKey == state.selectedCategoryKey }
            ?: if (changedLocation) detailPositions[nextLocation] else null
        pendingPosition = null
        locationKey = nextLocation
        if (changedLocation) {
            rememberedDetailKey = restore?.focusedKey?.takeIf { key ->
                state.detailRows.any { it.key == key && detailAdapter.isFocusableRow(it) } || state.detailContent?.findViewWithTag<View>(key) != null
            }
        }
        val focusedDetailKey =
            restore?.focusedKey?.takeIf { restore.focusInDetails }
                ?: (detailList.findFocus()?.tag as? String).takeUnless { changedLocation }
                ?: state.preferredDetailFocusKey?.takeIf { firstUpdate && it !in state.categories.map(NativeRowModel::key) }?.takeIf { key ->
                    state.detailRows.any { it.key == key && detailAdapter.isFocusableRow(it) }
                }
        if (focusedDetailKey != null) {
            preferDetailFocus = true
            rememberedDetailKey = focusedDetailKey
        }
        title.text = state.title
        subtitle.text = state.subtitle
        subtitle.isVisible = state.subtitle.isNotBlank()
        selectedCategoryKey = state.selectedCategoryKey
        detailHasActions = state.detailRows.any(detailAdapter::isFocusableRow)
        val readOnlyDetail = state.detailContent == null && !detailHasActions
        detailList.isFocusable = readOnlyDetail
        detailList.background = if (readOnlyDetail) activity.nativeFlatScrollRegionBackground() else null
        detailList.contentDescription = if (readOnlyDetail) {
            "${state.title} details, use up and down to scroll"
        } else {
            null
        }
        detailScrollGuide.text = "↑ ↓  Scroll"
        updateDetailTabs(state.detailTabs)
        updateDetailContent(state.detailContent)
        categoryAdapter.submitList(state.categories) {
            restore?.categoryScroll?.let { categoryList.layoutManager?.onRestoreInstanceState(it) }
        }
        if (state.detailContent != null) {
            detailUpdatePending = false
            detailAdapter.submitList(emptyList())
            if (firstUpdate) {
                firstUpdate = false
                val preferredDetail = (restore?.focusedKey ?: state.preferredDetailFocusKey)?.let { key ->
                    state.detailContent.findViewWithTag<View>(key)
                }
                if (preferredDetail != null) {
                    preferredDetail.post { preferredDetail.requestFocus() }
                } else {
                    requestInitialCategoryFocus(state, generation)
                }
            }
            enterActivatedCategoryWhenReady(generation)
            return
        }
        detailUpdatePending = true
        detailAdapter.submitList(state.detailRows) {
            if (generation != updateGeneration) return@submitList
            detailUpdatePending = false
            restore?.detailScroll?.let { detailList.layoutManager?.onRestoreInstanceState(it) }
            if (focusedDetailKey != null && (hadDetailFocus || firstUpdate || restore?.focusInDetails == true)) {
                restoreDetailFocus(focusedDetailKey, generation, explicit = firstUpdate && restore?.focusInDetails == true)
            } else if (hadDetailFocus && !changedLocation && focusedBefore != null && !detailList.hasFocus()) {
                // A row can disappear after an authorization or capability update.
                focusFirstDetailAction()
            } else if (firstUpdate) {
                if (restore?.focusInDetails == true && readOnlyDetail) detailList.post { detailList.requestFocus() }
                else requestInitialCategoryFocus(state, generation)
            }
            firstUpdate = false
            if (enterDetailsAfterUpdate) {
                enterDetailsAfterUpdate = false
                if (categoryList.hasFocus()) {
                    if (!focusFirstDetailAction()) detailList.requestFocus()
                }
            }
            enterActivatedCategoryWhenReady(generation)
        }
    }

    fun capturePosition(): NativeSettingsPosition = NativeSettingsPosition(
        categoryKey = selectedCategoryKey,
        focusedKey = findFocus()?.tag as? String ?: rememberedDetailKey.takeIf { preferDetailFocus },
        focusInDetails = if (hasFocus()) detailHost.hasFocus() else preferDetailFocus,
        categoryScroll = categoryList.layoutManager?.onSaveInstanceState(),
        detailScroll = detailList.layoutManager?.onSaveInstanceState(),
    )

    fun restorePosition(position: NativeSettingsPosition) {
        if (position.categoryKey != selectedCategoryKey) { pendingPosition = position; return }
        position.categoryScroll?.let { categoryList.layoutManager?.onRestoreInstanceState(it) }
        position.detailScroll?.let { detailList.layoutManager?.onRestoreInstanceState(it) }
        preferDetailFocus = position.focusInDetails
        if (position.focusInDetails) {
            val custom = position.focusedKey?.let { currentDetailContent?.findViewWithTag<View>(it) }
            if (custom?.isFocusable == true) custom.post { custom.requestFocus() }
            else if (position.focusedKey != null) restoreDetailFocus(position.focusedKey, updateGeneration, explicit = true)
            else if (!detailHasActions) detailList.post { detailList.requestFocus() }
        } else {
            categoryList.doOnPreDraw { focusSelectedCategory(position.focusedKey) }
            categoryList.invalidate()
        }
    }

    fun refreshTheme() {
        findViewWithTag<TextView>("settings:heading")?.setTextColor(NativeTheme.palette.text)
        findViewWithTag<View>("settings:categories-panel")?.background = activity.nativeGlassPanelBackground()
        findViewWithTag<View>("settings:detail-panel")?.background = activity.nativeGlassPanelBackground(strong = true)
        title.setTextColor(NativeTheme.palette.text)
        subtitle.setTextColor(NativeTheme.palette.secondaryText)
        detailScrollGuide.setTextColor(NativeTheme.palette.secondaryText)
        if (!detailHasActions) detailList.background = activity.nativeFlatScrollRegionBackground()
        for (index in 0 until detailTabs.childCount) {
            (detailTabs.getChildAt(index) as? TextView)?.apply {
                setTextColor(NativeTheme.palette.text)
                background = activity.nativeFlatFocusableBackground()
            }
        }
        categoryAdapter.refreshTheme()
        detailAdapter.refreshTheme()
        (currentDetailContent as? NativeAppearanceSurface)?.refreshTheme()
    }

    private fun restoreDetailFocus(key: String, generation: Int, explicit: Boolean = false) {
        // onRestoreInstanceState applies on the next layout. Looking up a holder before
        // that layout can incorrectly overwrite a saved pixel offset with scrollToPosition.
        detailList.doOnPreDraw {
            if (generation != updateGeneration || (!explicit && categoryList.hasFocus())) return@doOnPreDraw
            val position = detailAdapter.currentList.indexOfFirst { it.key == key && detailAdapter.isFocusableRow(it) }
            if (position < 0) {
                if (!focusFirstDetailAction() && !detailHasActions) detailList.requestFocus()
                return@doOnPreDraw
            }
            val visible = detailList.findViewHolderForAdapterPosition(position)?.itemView
            if (visible != null) {
                if (!visible.hasFocus()) {
                    if (visible.bottom > detailList.paddingTop && visible.top < detailList.height - detailList.paddingBottom) {
                        detailList.focusWithoutScrolling(visible)
                    } else {
                        visible.requestFocus()
                    }
                }
            } else {
                // The saved item is still valid but no longer inside the restored viewport.
                detailList.scrollToPosition(position)
                detailList.doOnPreDraw {
                    if (generation == updateGeneration && (explicit || !categoryList.hasFocus())) {
                        val target = detailList.findViewHolderForAdapterPosition(position)?.itemView
                        if (target?.tag == key) target.requestFocus()
                    }
                }
            }
        }
        detailList.invalidate()
    }

    private fun requestInitialCategoryFocus(state: NativeSettingsUiState, generation: Int) {
        categoryList.post {
            if (generation != updateGeneration || rootView.findFocus() != null) return@post
            val index = state.categories.indexOfFirst { it.key == state.selectedCategoryKey }.coerceAtLeast(0)
            categoryList.focusRow(index)
        }
    }

    fun focusSelectedCategory(key: String?) {
        preferDetailFocus = false
        val requestedKey = key ?: selectedCategoryKey
        val index = categoryAdapter.currentList.indexOfFirst { it.key == requestedKey }.coerceAtLeast(0)
        categoryList.focusRow(index)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focused = findFocus()
            when {
                focused != null && categoryList.isAncestorOf(focused) -> {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            val categoryKey = focused.tag as? String ?: return true
                            if (categoryKey != selectedCategoryKey) {
                                // Keep focus in this column until the requested pane has arrived.
                                // Otherwise Right briefly enters controls belonging to the old category.
                                if (enterCategoryKey != categoryKey) {
                                    enterCategoryKey = categoryKey
                                    onActivate(categoryKey)
                                }
                                return true
                            }
                            preferDetailFocus = true
                            if (!focusFirstDetailTarget()) detailList.requestFocus()
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_UP,
                        KeyEvent.KEYCODE_DPAD_DOWN,
                        -> {
                            categoryList.focusAdjacentRow(
                                categoryAdapter,
                                focused,
                                forward = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN,
                            )
                            return true
                        }
                    }
                    preferDetailFocus = false
                }
                focused === detailList -> when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (!detailList.canScrollVertically(-1)) {
                            detailList.focusUpTarget?.takeIf(View::isFocusable)?.requestFocus()
                            return true
                        }
                        detailList.scrollBy(0, -(detailList.height * DETAIL_SCROLL_FRACTION).toInt())
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        detailList.scrollBy(0, (detailList.height * DETAIL_SCROLL_FRACTION).toInt())
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    -> {
                        if (focusFirstDetailAction()) return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        focusSelectedCategory(null)
                        return true
                    }
                }
                focused != null && detailList.isAncestorOf(focused) -> {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            val focusedKind = (focused.tag as? String)?.let { key ->
                                detailAdapter.currentList.firstOrNull { it.key == key }?.kind
                            }
                            if (focusedKind != NativeRowKind.ADJUSTMENT) {
                                focusSelectedCategory(null)
                                return true
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_UP,
                        KeyEvent.KEYCODE_DPAD_DOWN,
                        -> {
                            preferDetailFocus = true
                            rememberedDetailKey = focused.tag as? String ?: rememberedDetailKey
                            val forward = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                            // A decoder list can be taller than the viewport. Read its remaining
                            // lines before advancing to the next row or the trailing Refresh action.
                            val row = detailList.findContainingViewHolder(focused)?.itemView
                            if (row != null && scrollWithinDetailRow(row, forward)) return true
                            if (focusAdjacentDetailRow(focused, forward)) return true
                            if (!forward) {
                                detailList.focusUpTarget?.takeIf(View::isFocusable)?.requestFocus()
                            }
                            return true
                        }
                        else -> {
                            preferDetailFocus = true
                            rememberedDetailKey = focused.tag as? String ?: rememberedDetailKey
                        }
                    }
                }
                focused != null && detailTabs.isAncestorOf(focused) -> {
                    preferDetailFocus = true
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> return true
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            focusFirstDetailTarget()
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (!focusHorizontalDetailTab(focused, forward = false)) {
                                focusSelectedCategory(null)
                            }
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            focusHorizontalDetailTab(focused, forward = true)
                            return true
                        }
                    }
                }
                focused != null && currentDetailContent?.let { content ->
                    content === focused || (content as? ViewGroup)?.isAncestorOf(focused) == true
                } == true -> {
                    preferDetailFocus = true
                    val tag = focused.tag as? String
                    rememberedDetailKey = tag ?: rememberedDetailKey
                    if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && tag == "appearance:mode:SYSTEM") {
                        focusSelectedCategory(null)
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun focusFirstDetailTarget(): Boolean {
        if (currentDetailContent == null && detailUpdatePending) {
            enterDetailsAfterUpdate = true
            return true
        }
        val customTarget = rememberedDetailKey?.let { currentDetailContent?.findViewWithTag<View>(it) }
            ?.takeIf { it.isFocusable && it.isEnabled && it.isVisible }
            ?: currentDetailContent?.let(::firstFocusableDescendant)
        if (customTarget != null) {
            customTarget.requestFocus()
            return true
        }
        return focusFirstDetailAction()
    }

    private fun enterActivatedCategoryWhenReady(generation: Int) {
        val key = enterCategoryKey ?: return
        if (selectedCategoryKey != key || detailUpdatePending) return
        detailHost.doOnPreDraw {
            if (generation != updateGeneration || enterCategoryKey != key) return@doOnPreDraw
            enterCategoryKey = null
            if (categoryList.findFocus()?.tag == key) {
                preferDetailFocus = true
                if (!focusFirstDetailTarget()) detailList.requestFocus()
            }
        }
        detailHost.invalidate()
    }

    private fun focusHorizontalDetailTab(focused: View, forward: Boolean): Boolean {
        val index = detailTabs.indexOfChild(focused)
        if (index < 0) return false
        val positions = if (forward) {
            (index + 1) until detailTabs.childCount
        } else {
            index - 1 downTo 0
        }
        val target = positions
            .map(detailTabs::getChildAt)
            .firstOrNull { it.isFocusable && it.isEnabled }
        return target?.requestFocus() == true
    }

    private fun focusFirstDetailAction(): Boolean {
        val remembered = detailAdapter.currentList.indexOfFirst { it.key == rememberedDetailKey && detailAdapter.isFocusableRow(it) }
        val position = remembered.takeIf { it >= 0 } ?: detailAdapter.currentList.indexOfFirst(detailAdapter::isFocusableRow)
        if (position < 0) return false
        if (remembered >= 0) {
            detailList.findViewHolderForAdapterPosition(position)?.itemView?.let { view ->
                if (view.bottom > detailList.paddingTop && view.top < detailList.height - detailList.paddingBottom) {
                    return detailList.focusWithoutScrolling(view)
                }
            }
        }
        return detailList.focusRow(position)
    }

    private fun scrollWithinDetailRow(row: View, forward: Boolean): Boolean {
        val viewportHeight = detailList.height - detailList.paddingTop - detailList.paddingBottom
        val remaining = if (forward) row.bottom - (detailList.height - detailList.paddingBottom)
            else detailList.paddingTop - row.top
        if (remaining <= 0 || viewportHeight <= 0) return false
        val step = minOf(remaining, (viewportHeight * DETAIL_SCROLL_FRACTION).toInt().coerceAtLeast(1))
        detailList.stopScroll()
        detailList.scrollBy(0, if (forward) step else -step)
        return true
    }

    private fun focusAdjacentDetailRow(focused: View, forward: Boolean): Boolean {
        if (!detailList.focusAdjacentRow(detailAdapter, focused, forward)) return false
        if (!forward) {
            // When returning from an action below a long block, resume at its final lines.
            val generation = updateGeneration
            detailList.doOnPreDraw {
                if (generation != updateGeneration) return@doOnPreDraw
                val row = detailList.findFocus()?.let(detailList::findContainingViewHolder)?.itemView
                    ?: return@doOnPreDraw
                if (row === focused) return@doOnPreDraw
                val viewportHeight = detailList.height - detailList.paddingTop - detailList.paddingBottom
                if (row.height > viewportHeight) {
                    detailList.stopScroll()
                    detailList.scrollBy(0, row.bottom - (detailList.height - detailList.paddingBottom))
                }
            }
            detailList.invalidate()
        }
        return true
    }

    private fun updateDetailTabs(models: List<NativeBrowserTab>) {
        val focusedKey = detailTabs.findFocus()?.tag as? String
        val existingKeys = (0 until detailTabs.childCount).map { detailTabs.getChildAt(it).tag as? String }
        if (existingKeys != models.map(NativeBrowserTab::key)) {
            detailTabs.removeAllViews()
            models.forEach { tab ->
                detailTabs.addView(
                    TextView(activity).apply {
                        id = View.generateViewId()
                        tag = tab.key
                        textSize = 14f
                        gravity = Gravity.CENTER
                        setTextColor(NativeTheme.palette.text)
                        background = activity.nativeFlatFocusableBackground()
                        setPadding(activity.dp(11), activity.dp(5), activity.dp(11), activity.dp(5))
                        setOnClickListener { onActivate(tab.key) }
                    },
                    LayoutParams(LayoutParams.WRAP_CONTENT, activity.dp(36)).apply { marginEnd = activity.dp(3) },
                )
            }
        }
        models.forEachIndexed { index, tab ->
            (detailTabs.getChildAt(index) as? TextView)?.apply {
                text = tab.label
                isFocusable = tab.enabled
                isClickable = tab.enabled
                isEnabled = tab.enabled
                isSelected = tab.selected
                alpha = if (tab.enabled) 1f else 0.5f
            }
        }
        detailTabs.visibility = if (models.isEmpty()) View.GONE else View.VISIBLE
        detailList.focusUpTarget = models.firstOrNull { it.selected }?.key?.let { key ->
            detailTabs.findViewWithTag(key)
        } ?: detailTabs.getChildAt(0)
        if (focusedKey != null) detailTabs.post { detailTabs.findViewWithTag<View>(focusedKey)?.requestFocus() }
    }

    private fun updateDetailContent(content: View?) {
        if (currentDetailContent === content && detailHost.isNotEmpty()) return
        currentDetailContent = content
        detailHost.removeAllViews()
        val child = content ?: standardDetailColumn
        (child.parent as? ViewGroup)?.removeView(child)
        detailHost.addView(
            child,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    private fun firstFocusableDescendant(view: View): View? {
        if (!view.isVisible) return null
        if (view.isFocusable && view.isEnabled && view.isVisible) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            firstFocusableDescendant(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun ViewGroup.isAncestorOf(view: View): Boolean {
        var parent = view.parent
        while (parent is View) {
            if (parent === this) return true
            parent = parent.parent
        }
        return false
    }

    init {
        detailList.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
            if (focused) preferDetailFocus = true
        }
    }

    private companion object {
        const val DETAIL_SCROLL_FRACTION = 0.72f
    }
}

/**
 * Keeps vertical navigation inside its current settings column. Moving between
 * category and detail columns is always an explicit left/right action, which
 * prevents a list boundary from unexpectedly sending focus across the page.
 */
private class NativeSettingsColumn(context: android.content.Context) : RecyclerView(context) {
    var focusUpTarget: View? = null
    private var preserveScrollOnFocus = false

    init {
        layoutManager = object : LinearLayoutManager(context) {
            override fun onRequestChildFocus(
                parent: RecyclerView,
                state: RecyclerView.State,
                child: View,
                focused: View?,
            ): Boolean = preserveScrollOnFocus || super.onRequestChildFocus(parent, state, child, focused)

            override fun onFocusSearchFailed(
                focused: View,
                focusDirection: Int,
                recycler: RecyclerView.Recycler,
                state: RecyclerView.State,
            ): View? = super.onFocusSearchFailed(focused, focusDirection, recycler, state) ?: focused
        }
    }

    fun focusWithoutScrolling(view: View): Boolean {
        stopScroll()
        preserveScrollOnFocus = true
        return try { view.requestFocus() } finally { preserveScrollOnFocus = false }
    }

    override fun focusSearch(focused: View?, direction: Int): View? {
        val proposed = super.focusSearch(focused, direction)
        val vertical = direction == View.FOCUS_UP || direction == View.FOCUS_DOWN
        if (
            direction == View.FOCUS_UP &&
            focused != null && isAncestorOf(focused) &&
            (proposed == null || !isAncestorOf(proposed)) &&
            focusUpTarget?.isFocusable == true
        ) {
            return focusUpTarget
        }
        return if (
            vertical &&
            focused != null && focused !== this && isAncestorOf(focused) &&
            (proposed == null || !isAncestorOf(proposed))
        ) {
            focused
        } else {
            proposed
        }
    }

    private fun ViewGroup.isAncestorOf(view: View): Boolean {
        var parent = view.parent
        while (parent is View) {
            if (parent === this) return true
            parent = parent.parent
        }
        return false
    }
}
