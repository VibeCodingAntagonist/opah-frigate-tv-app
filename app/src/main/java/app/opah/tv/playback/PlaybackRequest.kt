package app.opah.tv.playback

data class PlaybackRequest(
    val title: String,
    val uri: String,
    val kind: PlaybackKind,
    val cameraName: String? = null,
    val detail: String? = null,
    val startupFallbackUri: String? = null,
    val startupFallbackDetail: String? = null,
    /** Opaque process capability for compatibility-engine live playback; never a URI. */
    val liveCompatibilityRequestId: String? = null,
    val compatibilityTest: Boolean = false,
    val compatibilityTestVideoOnly: Boolean = false,
    val activityItemId: String? = null,
    /** Saved Frigate export identity used to preserve clip-specific privacy authorization. */
    val savedClipId: String? = null,
    val activityContextItemIds: List<String> = emptyList(),
    val activityQueueContext: Boolean = false,
    val briefingHighlight: Boolean = false,
    val recordingStartTime: Double? = null,
    val recordingEndTime: Double? = null,
    val recordingSaveKey: String? = null,
    val returnToLiveCameraName: String? = null,
    val stretchPreferenceKey: String? = cameraName,
    val liveStreamName: String? = null,
    val httpsLiveRequestId: String? = null,
)

const val BIRDSEYE_STRETCH_PREFERENCE_KEY = "__opah_birdseye__"

enum class PlaybackKind {
    LIVE,
    RECORDED,
}

/** Opaque HTTPS session identity prevents camera switches from reusing the prior surface. */
internal fun playbackSurfaceKey(request: PlaybackRequest): String =
    "playback:${request.httpsLiveRequestId ?: request.liveCompatibilityRequestId ?: request.uri}:" +
        "${request.activityItemId}:${request.compatibilityTestVideoOnly}"
