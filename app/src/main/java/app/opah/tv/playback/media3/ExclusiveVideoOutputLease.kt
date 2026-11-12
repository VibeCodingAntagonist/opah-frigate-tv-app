package app.opah.tv.playback.media3

// Adapted from Goofyschooler's PR #18 (e9d70a5); Apache-2.0, Opah contributors.

/**
 * Terminal ownership: cleanup failure keeps the slot occupied until process restart.
 * A stale close can never run cleanup twice or release a later player's slot.
 */
internal class ExclusiveVideoOutputLease(
    val attachment: Media3VideoOutputAttachment,
    private val releaseSlot: () -> Boolean,
) {
    private var finished = false
    private var clean = false

    @Synchronized fun finish(teardown: () -> Boolean): Boolean {
        if (finished) return clean
        finished = true
        clean = try { teardown() && releaseSlot() } catch (_: Exception) { false }
        return clean
    }
}
