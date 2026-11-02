package app.opah.tv.playback

import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Bounded bridge from WebSocket fragments to Media3's blocking byte reader. */
internal class LiveBytePipe(private val capacity: Int = 8 * 1024 * 1024) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val chunks = ArrayDeque<ByteArray>()
    private var offset = 0
    private var buffered = 0
    private var failure: IOException? = null

    fun offer(bytes: ByteArray): Boolean = lock.withLock {
        if (failure != null) return false
        if (bytes.size > capacity - buffered) {
            failLocked(IOException("Live buffer limit reached"))
            return false
        }
        if (bytes.isNotEmpty()) {
            chunks.addLast(bytes)
            buffered += bytes.size
            changed.signalAll()
        }
        true
    }

    fun fail(error: IOException) = lock.withLock { failLocked(error) }

    private fun failLocked(error: IOException) {
        if (failure == null) failure = error
        chunks.clear()
        buffered = 0
        offset = 0
        changed.signalAll()
    }

    fun read(target: ByteArray, start: Int, length: Int, timeoutMs: Long = 20_000): Int = lock.withLock {
        if (length == 0) return 0
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (chunks.isEmpty() && failure == null) {
            if (remaining <= 0) {
                failLocked(IOException("Live stream timeout"))
                throw requireNotNull(failure)
            }
            try {
                remaining = changed.awaitNanos(remaining)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Live stream interrupted")
            }
        }
        failure?.let { throw it }
        val chunk = chunks.first
        val count = minOf(length, chunk.size - offset)
        chunk.copyInto(target, start, offset, offset + count)
        buffered -= count
        offset += count
        if (offset == chunk.size) {
            chunks.removeFirst()
            offset = 0
        }
        count
    }
}

internal class LiveTransportException(val httpStatus: Int? = null) :
    IOException("Live connection rejected")

/** Never display exception messages, response bodies, headers or private URLs. */
internal fun liveFailureStatus(error: Throwable): String? {
    var current: Throwable? = error
    repeat(12) {
        val item = current ?: return null
        if (item is LiveTransportException) return item.httpStatus?.let { code -> "HTTP $code" }
        current = item.cause
    }
    return null
}
