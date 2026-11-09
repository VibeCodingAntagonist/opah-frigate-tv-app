package app.opah.tv.notifications.android

internal const val TV_ALERT_INTERACTION_IDLE_MILLIS = 15_000L
internal const val TV_ALERT_INTERACTION_MAX_MILLIS = 60_000L
internal const val TV_ALERT_QUEUE_MAX_AGE_MILLIS = 60_000L
internal const val TV_ALERT_QUEUE_CAPACITY = 5

/** A selection and a fresh complete key press are both required before invoking an action. */
internal class TvAlertOverlayInteraction(private val shownAtMillis: Long) {
    var selectedIndex: Int = -1
        private set
    private var downAtMillis: Long? = null

    fun select(index: Int, actionCount: Int) {
        selectedIndex = index.takeIf { it in 0 until actionCount } ?: -1
        downAtMillis = null
    }

    fun centerDown(downTime: Long, repeatCount: Int) {
        if (repeatCount == 0 && downTime >= shownAtMillis) downAtMillis = downTime
    }

    fun centerUp(downTime: Long, cancelled: Boolean): Int? {
        val selected = selectedIndex.takeIf { it >= 0 && downAtMillis == downTime && !cancelled }
        downAtMillis = null
        return selected
    }

    fun reset() { selectedIndex = -1; downAtMillis = null }
}

/** Main-thread queue; identities replace in place without renewing their waiting lifetime. */
internal class TvAlertOverlayQueue<T>(
    private val identity: (T) -> String,
    private val priority: (T) -> Int,
    private val receivedAt: (T) -> Long,
) {
    private val items = mutableListOf<T>()
    val size: Int get() = items.size

    fun offer(item: T) {
        val index = items.indexOfFirst { identity(it) == identity(item) }
        if (index >= 0) items[index] = item else items += item
        if (items.size > TV_ALERT_QUEUE_CAPACITY) {
            val lowest = items.minOf(priority)
            items.removeAt(items.indexOfFirst { priority(it) == lowest })
        }
    }

    fun removeWhere(predicate: (T) -> Boolean) { items.removeAll(predicate) }
    fun find(key: String): T? = items.firstOrNull { identity(it) == key }
    fun clear() = items.clear()

    fun take(now: Long): T? {
        items.removeAll { now - receivedAt(it) !in 0 until TV_ALERT_QUEUE_MAX_AGE_MILLIS }
        val next = items.maxByOrNull(priority) ?: return null
        items.remove(next)
        return next
    }
}
