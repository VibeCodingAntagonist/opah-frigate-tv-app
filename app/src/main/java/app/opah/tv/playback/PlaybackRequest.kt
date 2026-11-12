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
    /** Discovered source for explicit session recovery; never a URL or a saved preference. */
    val liveStreamName: String? = null,
    /** Explicit, ephemeral HTTPS request. Normal camera clicks never set this capability. */
    val httpsLiveRequestId: String? = null,
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
)

const val BIRDSEYE_STRETCH_PREFERENCE_KEY = "__opah_birdseye__"

enum class PlaybackKind {
    LIVE,
    RECORDED,
}

/** Adapted from Goofyschooler's PR #18: a retry must create a different surface/session. */
internal fun playbackSurfaceKey(request: PlaybackRequest): String =
    "playback:${request.httpsLiveRequestId ?: request.liveCompatibilityRequestId ?: request.uri}:" +
        "${request.activityItemId}:${request.compatibilityTestVideoOnly}"
