package app.opah.tv.domain

import app.opah.tv.data.model.ConnectionProfile
import java.net.URI

object StreamUriFactory {
    fun rtsp(profile: ConnectionProfile, streamName: String): String {
        require(streamName.isNotBlank()) { "Stream name is required." }
        val apiHost = URI(profile.apiBaseUrl).host
            ?: error("Frigate URL does not contain a valid host.")
        val host = profile.rtspHostOverride ?: apiHost
        return URI("rtsp", null, host, profile.rtspPort, "/$streamName", null, null)
            .toASCIIString()
    }
}

