package app.opah.tv.data

import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.model.StreamMetadata
import app.opah.tv.data.model.VideoCodec
import app.opah.tv.data.network.FrigateGateway
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class StreamDiscovery(
    val metadata: Map<String, StreamMetadata>,
    val warnings: List<String> = emptyList(),
)

/** Discovers only streams the authenticated camera role is allowed to observe. */
class StreamRepository(
    private val gateway: FrigateGateway,
    private val parsers: FrigateJsonParsers,
) {
    suspend fun discover(profile: ConnectionProfile, catalog: CameraCatalog): StreamDiscovery =
        coroutineScope {
            val warnings = mutableListOf<String>()
            val allMetadata = if (catalog.fullCameraAccess) {
                runCatching { gateway.getGo2RtcStreams(profile) }
                    .map(parsers::parseGo2RtcStreams)
                    .map { streams -> streams.filterKeys(catalog.permittedStreamNames::contains) }
                    .getOrElse {
                        warnings += "go2rtc stream list unavailable."
                        emptyMap()
                    }
            } else {
                // Frigate 0.17.2's bulk route is not camera-authorized. Probe only
                // stream names derived from cameras the signed-in role may access.
                emptyMap()
            }

            val streamsToProbe = if (catalog.fullCameraAccess) {
                catalog.permittedStreamNames.filter { streamName ->
                    val metadata = allMetadata[streamName]
                    metadata == null || (metadata.available && metadata.videoCodec == VideoCodec.UNKNOWN)
                }
            } else {
                catalog.permittedStreamNames
            }
            val probeSemaphore = Semaphore(MAX_CONCURRENT_PROBES)
            val probedMetadata = streamsToProbe.map { streamName ->
                async {
                    streamName to probeSemaphore.withPermit {
                        runCatching { gateway.getGo2RtcStream(profile, streamName) }
                            .map { raw -> parsers.parseSingleGo2RtcStream(streamName, raw) }
                            .getOrNull()
                    }
                }
            }.mapNotNull { it.await() }
                .mapNotNull { (name, metadata) -> metadata?.let { name to it } }
                .toMap()

            StreamDiscovery(
                metadata = allMetadata + probedMetadata,
                warnings = warnings,
            )
        }

    private companion object {
        const val MAX_CONCURRENT_PROBES = 3
    }
}
