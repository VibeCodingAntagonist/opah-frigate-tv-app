package app.opah.tv.homeassistant

import java.util.UUID
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal enum class HomeAssistantImageDisclosure { NONE, BLURRED, FULL }
internal data class HomeAssistantRichPolicy(
    val epoch: Long = -1,
    val disclosure: HomeAssistantDisclosure = HomeAssistantDisclosure.NONE,
    val images: HomeAssistantImageDisclosure = HomeAssistantImageDisclosure.NONE,
    val actionsProtected: Boolean = false,
    val actionsUnlocked: Boolean = false,
    val imageAuthority: String? = null,
)

/** Message labels and camera hints never establish the origin or authority of attached media. */
internal fun homeAssistantImageUrl(server: String, reference: String): HttpUrl? {
    if (reference.length !in 1..2_048 || reference.any { it.isISOControl() || it == '\\' } || reference.startsWith("//")) return null
    val base = runCatching { homeAssistantBaseUrl(server) }.getOrNull() ?: return null
    val url = if (reference.startsWith('/')) base.haEndpoint(reference.removePrefix("/")) else reference.toHttpUrlOrNull() ?: return null
    if (url.scheme != base.scheme || url.host != base.host || url.port != base.port ||
        url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null ||
        !url.encodedPath.startsWith(base.encodedPath)
    ) return null
    val path = url.encodedPath.removePrefix(base.encodedPath)
    if ('%' in path || path.split('/').any { it == "." || it == ".." }) return null
    return url.takeIf {
        path.startsWith("media/local/") && path.length > "media/local/".length ||
            path.matches(Regex("api/(camera_proxy/camera|image_proxy/image)\\.[A-Za-z0-9_]+"))
    }
}

internal fun homeAssistantRegistrationKey(session: HomeAssistantSession): String =
    homeAssistantNotificationKey(session.server, session.deviceId + "\u0000" + session.webhookId)

/** Memory-only, bounded capabilities. Consumption happens before network submission. */
internal class HomeAssistantNoticeBook(private val newId: () -> String = { UUID.randomUUID().toString() }) {
    internal class Entry(val id: String, val key: String, val registration: String, val epoch: Long,
        val message: HomeAssistantMessage.Show, val receivedAt: Long, val expiresAt: Long) {
        var consumed = false
        fun ticket(index: Int) = "$id.$index"
        override fun toString() = "HomeAssistantNoticeBook.Entry([redacted])"
    }
    private val entries = linkedMapOf<String, Entry>()

    @Synchronized fun put(key: String, registration: String, epoch: Long, message: HomeAssistantMessage.Show, now: Long): Pair<Entry, List<Entry>> {
        val removed = entries.values.filter { it.key == key || now >= it.expiresAt }.toMutableList()
        removed.forEach { entries.remove(it.id) }
        if (entries.size >= 16) entries.values.first().let { entries.remove(it.id); removed += it }
        val entry = Entry(newId(), key, registration, epoch, message, now,
            now + message.lifetimeMillis.coerceIn(1, HA_NOTICE_MAX_LIFETIME_MILLIS))
        entries[entry.id] = entry
        return entry to removed
    }
    @Synchronized fun current(entry: Entry, now: Long, registration: String, epoch: Long): Boolean =
        entries[entry.id] === entry && !entry.consumed && now in entry.receivedAt until entry.expiresAt &&
            entry.registration == registration && entry.epoch == epoch

    @Synchronized fun action(ticket: String, now: Long, registration: String, epoch: Long): Pair<Entry, HomeAssistantNotificationAction>? {
        val entry = entries[ticket.substringBeforeLast('.')] ?: return null
        if (!current(entry, now, registration, epoch)) return null
        val index = ticket.substringAfterLast('.').toIntOrNull()?.takeIf { it in 0..2 } ?: return null
        return entry.message.actions.getOrNull(index)?.let { entry to it }
    }
    @Synchronized fun consume(entry: Entry): Boolean {
        if (entries[entry.id] !== entry || entry.consumed) return false
        entry.consumed = true
        return true
    }
    /** A late presentation-dismiss callback cannot withdraw an already confirmed submission. */
    @Synchronized fun dismiss(id: String): Entry? {
        val entry = entries[id] ?: return null
        if (entry.consumed) return null
        return entries.remove(id)
    }
    @Synchronized fun remove(id: String): Entry? = entries.remove(id)
    @Synchronized fun removeKey(key: String): List<Entry> = entries.values.filter { it.key == key }.also { rows -> rows.forEach { entries.remove(it.id) } }
    @Synchronized fun clear(): List<Entry> = entries.values.toList().also { entries.clear() }
}
