package app.opah.tv.ui.views

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.opah.tv.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil

internal enum class NativeHomeTileKind { CAMERA, CAMERA_GROUP, SAVED_VIEW, BIRDSEYE }

internal data class NativeHomeTile(
    val key: String,
    val title: String,
    val subtitle: String,
    val kind: NativeHomeTileKind,
    val sourceId: String? = null,
    val previewCameraName: String? = null,
    val cameraNames: List<String> = emptyList(),
    val favorite: Boolean = false,
)

internal data class NativeHomeQuickAction(
    val key: String,
    val title: String,
    val detail: String,
    val iconRes: Int,
    val accentColor: Int,
    val value: String = "Open",
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val onClick: () -> Unit,
)

internal data class NativeHomeUiState(
    val subtitle: String,
    val tiles: List<NativeHomeTile>,
    val quickActions: List<NativeHomeQuickAction>,
)

/** Identities only: restoration always resolves against the current privacy-projected list. */
internal data class NativeHomeBookmark(
    val filterKey: String = "all",
    val focusedKey: String? = null,
    val selectedTileKey: String? = null,
    val anchorKey: String? = null,
    val anchorOffset: Int = 0,
)

@SuppressLint("ViewConstructor", "SetTextI18n")
internal class NativeHomeSurface(
    private val activity: ComponentActivity,
    initialState: NativeHomeUiState,
    private val cachedBitmap: (String) -> Bitmap?,
    private val refreshBitmap: suspend (String, Int) -> Bitmap?,
    private val onPrimary: (NativeHomeTile) -> Unit,
    private val onOptions: (NativeHomeTile) -> Unit,
    private val imageLoadedAtMillis: (String) -> Long? = { null },
) : LinearLayout(activity) {
    private val subtitleView = text(14f, secondary = true)
    private val filterButtons = linkedMapOf<String, TextView>()
    private val quickActionIcons = mutableMapOf<String, Int>()
    private val quickItems = LinearLayout(activity).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        // Keep the growing focus outline inside the scroller, including its first and last chips.
        setPadding(activity.dp(8), activity.dp(3), activity.dp(8), activity.dp(3))
    }
    private val quickScroller = HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = false
        isHorizontalFadingEdgeEnabled = false
        isSmoothScrollingEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isFocusable = false
        clipToPadding = false
        addView(quickItems)
    }
    private var restoringVisibleTileFocus: View? = null
    private val gridManager = object : GridLayoutManager(activity, GRID_COLUMNS) {
        override fun onRequestChildFocus(
            parent: RecyclerView,
            state: RecyclerView.State,
            child: View,
            focused: View?,
        ): Boolean = child === restoringVisibleTileFocus || super.onRequestChildFocus(parent, state, child, focused)
    }
    private val tilesView = RecyclerView(activity).apply {
        layoutManager = gridManager
        itemAnimator = null
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        clipChildren = false
        setPadding(activity.dp(5), activity.dp(5), activity.dp(5), activity.dp(8))
        setItemViewCacheSize(GRID_COLUMNS)
        addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                outRect.set(activity.dp(5), activity.dp(5), activity.dp(5), activity.dp(5))
            }
        })
    }
    private val emptyView = text(19f, secondary = true).apply {
        gravity = Gravity.CENTER
        visibility = View.GONE
    }
    private val tileAdapter = HomeTileAdapter(
        onFocused = ::selectTile,
        onActivated = { tile -> selectTile(tile); onPrimary(tile) },
        onOptions = { tile -> selectTile(tile); onOptions(tile) },
        onImage = ::loadTileImage,
        onRecycled = ::releaseTileImage,
        onAttached = { image -> tilePreviews[image]?.let(::startTilePreview) },
        onDetached = { image -> tilePreviews[image]?.job?.cancel() },
        revealOnFocus = { focused -> focused !== restoringVisibleTileFocus },
    )
    private var state = initialState
    private var filterKey = "all"
    private var selectedTile: NativeHomeTile? = null
    private val tilePreviews = mutableMapOf<ImageView, TilePreviewBinding>()
    private var firstSubmission = true
    private var suppressInitialFocus = false
    private var pendingBookmark: NativeHomeBookmark? = null
    private var pendingRestoreFocus = false
    private var closed = false

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.TRANSPARENT)
        clipChildren = false
        isFocusable = false
        addView(
            LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(activity).apply {
                    orientation = VERTICAL
                    addView(text(26f, bold = true).apply { text = "Cameras" })
                    addView(subtitleView)
                    addView(text(12f, secondary = true).apply { text = "Hold OK for options" })
                }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(LinearLayout(activity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = activity.nativeGlassPanelBackground()
                    setPadding(activity.dp(4), activity.dp(4), activity.dp(4), activity.dp(4))
                    listOf("all" to "All", "favorites" to "Favorites", "views" to "Views").forEach { (key, label) ->
                        val chip = button(label, "home:filter:$key") { selectFilter(key) }
                        filterButtons[key] = chip
                        addView(chip)
                    }
                })
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        addView(quickScroller, LayoutParams(LayoutParams.MATCH_PARENT, activity.dp(44)).apply {
            topMargin = activity.dp(6)
            bottomMargin = activity.dp(3)
        })
        tilesView.adapter = tileAdapter
        addView(FrameLayout(activity).apply {
            tag = "home:grid-viewport"
            // Allow focus growth within the grid's padding, while keeping scrolling tiles
            // inside this viewport so they cannot cover the fixed header and quick actions.
            clipChildren = true
            clipToPadding = true
            addView(tilesView, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(emptyView, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        update(initialState)
    }

    fun captureBookmark(): NativeHomeBookmark {
        val first = gridManager.findFirstVisibleItemPosition()
        val anchor = tileAdapter.currentList.getOrNull(first)
        return NativeHomeBookmark(
            filterKey = filterKey,
            focusedKey = findFocus()?.tag as? String,
            selectedTileKey = selectedTile?.key,
            anchorKey = anchor?.key,
            anchorOffset = gridManager.findViewByPosition(first)?.let { gridManager.getDecoratedTop(it) - tilesView.paddingTop } ?: 0,
        )
    }

    fun restoreBookmark(bookmark: NativeHomeBookmark, requestFocus: Boolean = true) {
        suppressInitialFocus = true
        pendingBookmark = bookmark
        pendingRestoreFocus = requestFocus
        filterKey = bookmark.filterKey.takeIf(filterButtons::containsKey) ?: "all"
        submitTiles()
    }

    fun update(next: NativeHomeUiState) {
        state = next
        subtitleView.text = next.subtitle
        updateQuickActions(next.quickActions)
        submitTiles()
    }

    fun refreshPreview(tileKey: String) {
        if (tileAdapter.currentList.none { it.key == tileKey }) return
        tilePreviews.values.firstOrNull { it.tile.key == tileKey }?.let(::startTilePreview)
    }

    fun close() {
        closed = true
        tilePreviews.values.forEach { it.job?.cancel() }
        tilePreviews.clear()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focused = findFocus()
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                (filterButtons.values.any { it === focused } || quickItems.containsDescendant(focused))) {
                focusTile(selectedTile?.key)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun selectFilter(key: String) {
        if (filterKey == key) return
        filterKey = key
        selectedTile = null
        gridManager.scrollToPositionWithOffset(0, 0)
        submitTiles()
    }

    private fun submitTiles() {
        val focusedKey = findFocus()?.tag as? String
        val hadGridFocus = tilesView.hasFocus()
        val oldIndex = tileAdapter.currentList.indexOfFirst { it.key == selectedTile?.key }.coerceAtLeast(0)
        val visible = state.tiles.filter { tile ->
            when (filterKey) {
                "favorites" -> tile.favorite
                "views" -> tile.kind != NativeHomeTileKind.CAMERA
                else -> true
            }
        }.sortedBy { if (it.kind == NativeHomeTileKind.CAMERA) 0 else 1 }
        filterButtons.forEach { (key, view) -> view.isSelected = key == filterKey }
        tileAdapter.submitList(visible) {
            if (closed) return@submitList
            val bookmark = pendingBookmark
            val selectedKey = bookmark?.selectedTileKey ?: selectedTile?.key
            val next = visible.firstOrNull { it.key == selectedKey }
                ?: visible.getOrNull(oldIndex.coerceAtMost(visible.lastIndex))
            selectedTile = next
            if (next != null) selectTile(next)
            emptyView.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
            emptyView.text = when (filterKey) {
                "favorites" -> "Favorite a camera or view to find it here"
                "views" -> "Create a view to watch cameras together"
                else -> "No cameras are available"
            }
            if (bookmark != null) {
                val anchorPosition = visible.indexOfFirst { it.key == bookmark.anchorKey }
                if (anchorPosition >= 0) gridManager.scrollToPositionWithOffset(anchorPosition, bookmark.anchorOffset)
                val restoreFocus = pendingRestoreFocus
                pendingBookmark = null
                pendingRestoreFocus = false
                if (restoreFocus) tilesView.doOnLayout {
                    post { restoreFocusedKey(bookmark.focusedKey) }
                }
            } else if (hadGridFocus && visible.none { it.key == focusedKey }) {
                post { focusTile(next?.key) }
            } else if (firstSubmission) {
                tilesView.doOnLayout {
                    post {
                        val focused = rootView.findFocus()
                        val focusBelongsHere = focused == null || focused === this || containsDescendant(focused)
                        if (!closed && !suppressInitialFocus && focusBelongsHere) focusTile(next?.key)
                    }
                }
            }
            firstSubmission = false
        }
    }

    private fun restoreFocusedKey(key: String?) {
        if (closed) return
        val fixedControl = key?.let { findViewWithTag<View>(it) }?.takeUnless { view ->
            view.parent === tilesView && tileAdapter.currentList.none { it.key == key }
        }
        if (fixedControl?.isFocusable == true && fixedControl.isShown && fixedControl.isEnabled) {
            val visibleTile = fixedControl.parent === tilesView && fixedControl.width > 0 && fixedControl.height > 0 &&
                fixedControl.left >= tilesView.paddingLeft && fixedControl.top >= tilesView.paddingTop &&
                fixedControl.right <= tilesView.width - tilesView.paddingRight &&
                fixedControl.bottom <= tilesView.height - tilesView.paddingBottom
            if (visibleTile) {
                // The saved viewport already shows this tile. RecyclerView includes
                // decoration in its focus reveal, which would shift the saved anchor.
                restoringVisibleTileFocus = fixedControl
                try { fixedControl.requestFocus() } finally { restoringVisibleTileFocus = null }
            } else {
                fixedControl.requestFocus()
            }
        } else {
            focusTile(key?.takeIf { candidate -> tileAdapter.currentList.any { it.key == candidate } } ?: selectedTile?.key)
        }
    }

    private fun focusTile(key: String?) {
        val list = tileAdapter.currentList
        if (list.isEmpty()) {
            filterButtons[filterKey]?.requestFocus()
            return
        }
        val position = list.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: 0
        val existing = tilesView.findViewHolderForAdapterPosition(position)?.itemView
        if (existing != null) {
            existing.requestFocus()
        } else {
            tilesView.scrollToPosition(position)
            tilesView.doOnLayout {
                if (!closed) tilesView.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
            }
        }
    }

    private fun selectTile(tile: NativeHomeTile) {
        selectedTile = tile
    }

    private fun updateQuickActions(actions: List<NativeHomeQuickAction>) {
        val focusedKey = quickItems.findFocus()?.tag as? String
        val keys = (0 until quickItems.childCount).map { quickItems.getChildAt(it).tag as? String }
        if (keys != actions.map { "home:quick:${it.key}" }) {
            quickItems.removeAllViews()
            quickActionIcons.clear()
            actions.forEach { action -> quickItems.addView(button(action.title, "home:quick:${action.key}", revealQuickAction = true) {} ) }
        }
        actions.forEachIndexed { index, action ->
            (quickItems.getChildAt(index) as TextView).apply {
                val label = when {
                    action.key == "activity" && action.value != "Caught up" -> "${action.title} · ${action.value}"
                    action.key == "mode" -> "${action.title} · ${action.value}"
                    else -> action.title
                }
                if (text.toString() != label) {
                    text = label
                    if (hasFocus()) doOnLayout { if (hasFocus()) revealButton(this, quickAction = true) }
                }
                contentDescription = listOf(action.title, action.value, action.detail).filter(String::isNotBlank).joinToString(", ")
                isEnabled = action.enabled
                isFocusable = action.enabled
                isClickable = action.enabled
                isSelected = action.selected
                alpha = if (action.enabled) 1f else 0.48f
                if (quickActionIcons[action.key] != action.iconRes) {
                    val icon = activity.getDrawable(action.iconRes)?.mutate()?.apply {
                        setTint(NativeTheme.palette.text)
                        setBounds(0, 0, activity.dp(18), activity.dp(18))
                    }
                    setCompoundDrawablesRelative(icon, null, null, null)
                    compoundDrawablePadding = activity.dp(6)
                    quickActionIcons[action.key] = action.iconRes
                }
                setOnClickListener(if (action.enabled) View.OnClickListener {
                    if (isEnabled) action.onClick()
                } else null)
                // Setting an OnClickListener can mark a View clickable; apply availability last.
                isClickable = action.enabled
            }
        }
        quickScroller.visibility = if (actions.isEmpty()) View.GONE else View.VISIBLE
        if (focusedKey != null) {
            val remembered = quickItems.findViewWithTag<View>(focusedKey)?.takeIf { it.isFocusable }
            val fallback = (0 until quickItems.childCount).map(quickItems::getChildAt).firstOrNull { it.isFocusable }
            (remembered ?: fallback ?: filterButtons[filterKey])?.takeUnless { it.hasFocus() }?.requestFocus()
        }
    }

    private fun loadTileImage(tile: NativeHomeTile, target: ImageView, label: TextView) {
        val wasUnavailable = tilePreviews[target]?.takeIf { it.tile.key == tile.key }?.unavailable == true
        releaseTileImage(target)
        target.tag = tile.key
        val cameraName = tile.previewCameraName ?: return
        val cached = cachedBitmap(cameraName)
        cached?.let(target::setImageBitmap)
        val binding = TilePreviewBinding(tile, target, label, imageLoadedAtMillis(cameraName), cached != null, wasUnavailable)
        tilePreviews[target] = binding
        updatePreviewLabel(binding)
        if (target.isAttachedToWindow) startTilePreview(binding)
    }

    private fun startTilePreview(binding: TilePreviewBinding) {
        binding.job?.cancel()
        val generation = ++binding.refreshGeneration
        // RecyclerView attaches a holder before its image has visible bounds. Wait for that
        // traversal so the first request cannot accidentally sleep for the refresh interval.
        binding.image.doOnLayout {
            binding.image.post {
                if (!closed && binding.image.isAttachedToWindow && tilePreviews[binding.image] === binding &&
                    binding.refreshGeneration == generation) {
                    refreshVisibleTile(binding)
                }
            }
        }
    }

    private fun refreshVisibleTile(binding: TilePreviewBinding) {
        binding.job = activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive && !closed && tilePreviews[binding.image] === binding) {
                    if (binding.image.isShown && binding.image.getLocalVisibleRect(Rect())) {
                        val cameraName = requireNotNull(binding.tile.previewCameraName)
                        val bitmap = try {
                            refreshBitmap(cameraName, TILE_IMAGE_HEIGHT)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                        currentCoroutineContext().ensureActive()
                        if (closed || tilePreviews[binding.image] !== binding || binding.image.tag != binding.tile.key) return@repeatOnLifecycle
                        binding.unavailable = bitmap == null
                        if (bitmap != null) {
                            binding.image.setImageBitmap(bitmap)
                            binding.hasImage = true
                            binding.loadedAtMillis = imageLoadedAtMillis(cameraName) ?: System.currentTimeMillis()
                        }
                        updatePreviewLabel(binding)
                    }
                    delay(PREVIEW_REFRESH_MILLIS)
                }
            }
        }
    }

    private fun updatePreviewLabel(binding: TilePreviewBinding) {
        if (binding.tile.kind != NativeHomeTileKind.CAMERA) return
        val label = cameraPreviewStatus(binding.loadedAtMillis, binding.hasImage, binding.unavailable)
        if (binding.label.text.toString() != label) binding.label.text = label
        (binding.image.parent as? View)?.contentDescription =
            "${binding.tile.title}, $label${if (binding.tile.favorite) ", favorite" else ""}"
    }

    private fun releaseTileImage(target: ImageView) {
        tilePreviews.remove(target)?.job?.cancel()
        target.tag = null
        target.setImageDrawable(null)
    }

    private data class TilePreviewBinding(
        val tile: NativeHomeTile,
        val image: ImageView,
        val label: TextView,
        var loadedAtMillis: Long?,
        var hasImage: Boolean,
        var unavailable: Boolean = false,
        var job: Job? = null,
        var refreshGeneration: Long = 0,
    )

    private fun text(size: Float, bold: Boolean = false, secondary: Boolean = false) = TextView(activity).apply {
        textSize = size
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(if (secondary) NativeTheme.palette.secondaryText else NativeTheme.palette.text)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(label: String, key: String, revealQuickAction: Boolean = false, action: () -> Unit) = text(15f).apply {
        tag = key
        text = label
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        background = activity.nativeFlatFocusableBackground()
        setPadding(activity.dp(12), activity.dp(5), activity.dp(12), activity.dp(5))
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, activity.dp(38)).apply { marginEnd = activity.dp(4) }
        setOnClickListener { action() }
        onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
            view.applyNativeFocusMotion(focused, scale = BUTTON_FOCUS_SCALE)
            if (focused) revealButton(view, revealQuickAction)
        }
    }

    private fun revealButton(view: View, quickAction: Boolean) {
        val scale = if (quickAction && !NativeTheme.reducedMotion) BUTTON_FOCUS_SCALE else 1f
        val horizontal = ceil(view.width * (scale - 1f) / 2f).toInt()
        val vertical = ceil(view.height * (scale - 1f) / 2f).toInt()
        view.requestRectangleOnScreen(
            Rect(-horizontal, -vertical, view.width + horizontal, view.height + vertical),
            quickAction,
        )
    }

    private fun ViewGroup.containsDescendant(view: View?): Boolean {
        var parent = view?.parent
        while (parent is View) {
            if (parent === this) return true
            parent = parent.parent
        }
        return false
    }

    private class HomeTileAdapter(
        private val onFocused: (NativeHomeTile) -> Unit,
        private val onActivated: (NativeHomeTile) -> Unit,
        private val onOptions: (NativeHomeTile) -> Unit,
        private val onImage: (NativeHomeTile, ImageView, TextView) -> Unit,
        private val onRecycled: (ImageView) -> Unit,
        private val onAttached: (ImageView) -> Unit,
        private val onDetached: (ImageView) -> Unit,
        private val revealOnFocus: (View) -> Boolean,
    ) : ListAdapter<NativeHomeTile, TileHolder>(DIFF) {
        init { setHasStableIds(true) }
        override fun getItemId(position: Int): Long = stableNativeItemId(getItem(position).key)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileHolder = TileHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.native_home_tile, parent, false),
        )
        override fun onBindViewHolder(holder: TileHolder, position: Int) {
            holder.bind(getItem(position), onFocused, onActivated, onOptions, onImage, revealOnFocus)
        }
        override fun onViewAttachedToWindow(holder: TileHolder) {
            super.onViewAttachedToWindow(holder)
            onAttached(holder.image)
        }
        override fun onViewDetachedFromWindow(holder: TileHolder) {
            onDetached(holder.image)
            super.onViewDetachedFromWindow(holder)
        }
        override fun onViewRecycled(holder: TileHolder) {
            onRecycled(holder.image)
            holder.itemView.animate().cancel()
            holder.itemView.scaleX = 1f
            holder.itemView.scaleY = 1f
            super.onViewRecycled(holder)
        }
        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<NativeHomeTile>() {
                override fun areItemsTheSame(oldItem: NativeHomeTile, newItem: NativeHomeTile) = oldItem.key == newItem.key
                override fun areContentsTheSame(oldItem: NativeHomeTile, newItem: NativeHomeTile) = oldItem == newItem
            }
        }
    }

    private class TileHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.native_home_tile_image)
        private val title: TextView = view.findViewById(R.id.native_home_tile_title)
        private val subtitle: TextView = view.findViewById(R.id.native_home_tile_subtitle)

        fun bind(
            tile: NativeHomeTile,
            onFocused: (NativeHomeTile) -> Unit,
            onActivated: (NativeHomeTile) -> Unit,
            onOptions: (NativeHomeTile) -> Unit,
            onImage: (NativeHomeTile, ImageView, TextView) -> Unit,
            revealOnFocus: (View) -> Boolean,
        ) {
            itemView.background = itemView.context.nativeFlatFocusableBackground()
            image.background = itemView.context.nativeGlassPanelBackground()
            image.clipToOutline = true
            title.setTextColor(NativeTheme.palette.text)
            subtitle.setTextColor(NativeTheme.palette.secondaryText)
            itemView.tag = tile.key
            itemView.contentDescription = "${tile.title}, ${tile.subtitle}${if (tile.favorite) ", favorite" else ""}"
            title.text = if (tile.favorite) "★ ${tile.title}" else tile.title
            subtitle.text = tile.subtitle
            onImage(tile, image, subtitle)
            itemView.setOnClickListener { onActivated(tile) }
            itemView.setOnLongClickListener { onOptions(tile); true }
            itemView.onFocusChangeListener = View.OnFocusChangeListener { focusedView, focused ->
                focusedView.applyNativeFocusMotion(focused, scale = 1.025f)
                if (focused) {
                    onFocused(tile)
                    // Decide synchronously while bookmark focus restoration owns the
                    // request; its suppression ends before a posted reveal would run.
                    if (revealOnFocus(focusedView)) focusedView.post {
                        if (focusedView.hasFocus()) focusedView.requestRectangleOnScreen(
                            Rect(0, 0, focusedView.width, focusedView.height), false,
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val BUTTON_FOCUS_SCALE = 1.05f
        const val GRID_COLUMNS = 3
        const val TILE_IMAGE_HEIGHT = 320
        const val PREVIEW_REFRESH_MILLIS = 10_000L
    }
}
