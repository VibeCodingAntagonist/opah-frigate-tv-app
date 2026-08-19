package app.opah.tv.data

import app.opah.tv.data.model.BirdseyeStatus
import app.opah.tv.data.model.Camera
import app.opah.tv.data.model.FrigateUserProfile

data class CameraCatalog(
    val cameras: List<Camera>,
    val configuredCameraNames: Set<String>,
    val fullCameraAccess: Boolean,
    val birdseye: BirdseyeStatus,
) {
    val permittedStreamNames: Set<String> = buildSet {
        addAll(cameras.flatMap { camera -> camera.streams.map { it.streamName } })
        if (fullCameraAccess && birdseye.enabled && birdseye.restreamConfigured) add(BIRDSEYE_STREAM_NAME)
    }

    private companion object {
        const val BIRDSEYE_STREAM_NAME = "birdseye"
    }
}

/** Maps Frigate configuration to the camera catalog visible to the signed-in role. */
class CameraRepository(private val parsers: FrigateJsonParsers) {
    fun catalog(config: String, user: FrigateUserProfile): CameraCatalog {
        val configuredNames = parsers.parseConfiguredCameraNames(config)
        return CameraCatalog(
            cameras = parsers.parseCameras(config, emptyMap(), user.allowedCameras),
            configuredCameraNames = configuredNames,
            fullCameraAccess = configuredNames.isNotEmpty() &&
                user.allowedCameras.containsAll(configuredNames),
            birdseye = parsers.parseBirdseyeStatus(config, emptyMap()),
        )
    }
}
