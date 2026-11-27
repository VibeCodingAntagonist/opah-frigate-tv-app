package app.opah.tv.data

import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.model.isDefaultFrigateInternalPort
import app.opah.tv.playback.canOfferHttpLive
import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** One saved-server opt-in. Unknown, absent and old values remain off. No address is duplicated. */
data class AutomaticLiveRecoveryPreference(val binding: String? = null) {
    fun enabledFor(profile: ConnectionProfile?): Boolean = profile != null &&
        binding != null && binding == automaticLiveRecoveryBinding(profile)
}

internal fun automaticLiveRecoveryBinding(profile: ConnectionProfile): String? {
    if (!canOfferHttpLive(profile.apiBaseUrl, profile.withoutSignIn)) return null
    val base = profile.apiBaseUrl.toHttpUrlOrNull() ?: return null
    val legacyParts = listOf(base.toString().trimEnd('/'), profile.username,
        profile.rtspHostOverride.orEmpty(), profile.rtspPort.toString())
    val parts = if (profile.withoutSignIn != isDefaultFrigateInternalPort(profile.apiBaseUrl)) {
        legacyParts + profile.withoutSignIn.toString()
    } else legacyParts
    val input = parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
    return "v1:" + MessageDigest.getInstance("SHA-256").digest(input).joinToString("") { "%02x".format(it) }
}
