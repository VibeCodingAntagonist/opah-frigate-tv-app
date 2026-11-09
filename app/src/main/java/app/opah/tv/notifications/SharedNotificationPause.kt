package app.opah.tv.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NotificationPauseSnapshot(
    val paused: Boolean = false,
    val revision: Long = 0,
    val untilEpochMillis: Long? = null,
    val quietThroughEpochMillis: Long = 0,
    val available: Boolean = true,
) {
    fun permits(expectedRevision: Long) = available && !paused && revision == expectedRevision
    fun suppressesFrigate(startEpochSeconds: Double?): Boolean = paused || !available ||
        quietThroughEpochMillis > 0 && (startEpochSeconds == null || !startEpochSeconds.isFinite() ||
            startEpochSeconds * 1_000 <= quietThroughEpochMillis)
}

interface NotificationPauseGate {
    fun snapshot(): NotificationPauseSnapshot
    fun <T> withPermit(revision: Long, work: () -> T): T? = if (snapshot().permits(revision)) work() else null
    companion object {
        val NONE: NotificationPauseGate = object : NotificationPauseGate {
            override fun snapshot() = NotificationPauseSnapshot()
        }
    }
}

internal data class NotificationPauseRecord(
    val startedWall: Long,
    val startedElapsed: Long,
    val bootCount: Int,
    val durationMillis: Long,
    val quietThrough: Long,
) {
    init {
        require(startedWall >= 0 && startedElapsed >= 0 && bootCount >= 0 && quietThrough >= 0)
        require(durationMillis in setOf(0L, 15 * 60_000L, 60 * 60_000L))
        require(startedWall <= Long.MAX_VALUE - durationMillis)
    }
}

internal interface NotificationPauseStore {
    fun read(): NotificationPauseRecord?
    fun write(record: NotificationPauseRecord): Boolean
}

/** Device-local presentation control. Only timing evidence is persisted; no notification content. */
internal class SharedNotificationPause(
    private val store: NotificationPauseStore,
    private val wall: () -> Long,
    private val elapsed: () -> Long,
    private val boot: () -> Int,
) : NotificationPauseGate {
    private var record: NotificationPauseRecord? = null
    private var available = true
    private var revision = 0L
    private val mutableState = MutableStateFlow(NotificationPauseSnapshot())
    val state: StateFlow<NotificationPauseSnapshot> = mutableState.asStateFlow()
    init {
        try { record = store.read() } catch (_: Exception) { available = false }
        snapshot()
    }
    @Synchronized override fun snapshot(): NotificationPauseSnapshot {
        val saved = record
        if (available && saved != null && saved.durationMillis > 0) {
            val remaining = if (boot() == saved.bootCount) saved.durationMillis - (elapsed() - saved.startedElapsed)
                else saved.startedWall + saved.durationMillis - wall()
            if (remaining > saved.durationMillis || remaining < -Long.MAX_VALUE / 2) available = false
            else if (remaining <= 0) {
                persist(saved.copy(durationMillis = 0, quietThrough = maxOf(saved.quietThrough, saved.startedWall + saved.durationMillis)))
            }
        }
        val current = record
        return NotificationPauseSnapshot(
            paused = !available || current?.durationMillis?.let { it > 0 } == true,
            revision = revision,
            untilEpochMillis = current?.takeIf { it.durationMillis > 0 }?.let { it.startedWall + it.durationMillis },
            quietThroughEpochMillis = current?.quietThrough ?: 0,
            available = available,
        ).also { mutableState.value = it }
    }
    @Synchronized fun pause(minutes: Int): Boolean {
        if (minutes !in setOf(15, 60)) return false
        snapshot()
        val next = runCatching { NotificationPauseRecord(wall(), elapsed(), boot(), minutes * 60_000L, record?.quietThrough ?: 0) }.getOrNull() ?: return false
        val saved = persist(next)
        snapshot()
        return saved
    }
    @Synchronized fun resume(): Boolean {
        val next = runCatching { NotificationPauseRecord(wall(), elapsed(), boot(), 0, maxOf(record?.quietThrough ?: 0, wall())) }.getOrNull() ?: return false
        val saved = persist(next)
        snapshot()
        return saved
    }
    @Synchronized override fun <T> withPermit(revision: Long, work: () -> T): T? =
        if (snapshot().permits(revision)) work() else null

    private fun persist(next: NotificationPauseRecord): Boolean {
        revision++
        available = runCatching { store.write(next) }.getOrDefault(false)
        if (available) record = next
        return available
    }
}
