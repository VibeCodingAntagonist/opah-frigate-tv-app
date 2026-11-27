package app.opah.tv.data.model

data class ConnectionProfile(
    val apiBaseUrl: String,
    val username: String,
    val rtspHostOverride: String? = null,
    val rtspPort: Int = 8554,
    val withoutSignIn: Boolean = isDefaultFrigateInternalPort(apiBaseUrl),
)

internal fun isDefaultFrigateInternalPort(apiBaseUrl: String): Boolean =
    runCatching { java.net.URI(apiBaseUrl).port == 5000 }.getOrDefault(false)

data class FrigateUserProfile(
    val username: String,
    val role: String,
    val allowedCameras: Set<String>,
)

data class Camera(
    val name: String,
    val displayName: String,
    val order: Int,
    val streams: List<LiveStreamOption>,
)

data class CameraGroup(
    val name: String,
    val displayName: String,
    val cameraNames: List<String>,
    val order: Int,
)

data class SavedCameraView(
    val id: String,
    val name: String,
    val firstCameraName: String,
    val secondCameraName: String,
    val thirdCameraName: String? = null,
    val fourthCameraName: String? = null,
) {
    val cameraNames: List<String>
        get() = listOfNotNull(firstCameraName, secondCameraName, thirdCameraName, fourthCameraName)
}

data class CameraPtzInfo(
    val cameraName: String,
    val features: Set<String>,
    val presets: List<String>,
) {
    val canMove: Boolean get() = "pt" in features
    val canZoom: Boolean get() = "zoom" in features
    val canFocus: Boolean get() = "focus" in features
    val hasControls: Boolean get() = canMove || canZoom || canFocus || presets.isNotEmpty()
}

data class LiveStreamOption(
    val label: String,
    val streamName: String,
    val metadata: StreamMetadata? = null,
)

data class StreamMetadata(
    val streamName: String,
    val available: Boolean,
    val videoCodec: VideoCodec = VideoCodec.UNKNOWN,
    val audioCodec: AudioCodec = AudioCodec.UNKNOWN,
    val width: Int? = null,
    val height: Int? = null,
    val evidence: List<String> = emptyList(),
)

enum class VideoCodec(val displayName: String, val mimeType: String?) {
    AVC("H.264 / AVC", "video/avc"),
    HEVC("H.265 / HEVC", "video/hevc"),
    UNKNOWN("Unknown", null),
}

enum class AudioCodec(val displayName: String, val mimeType: String?) {
    OPUS("Opus", "audio/opus"),
    AAC("AAC", "audio/mp4a-latm"),
    PCMA("G.711 A-law", "audio/g711-alaw"),
    PCMU("G.711 mu-law", "audio/g711-mlaw"),
    NONE("None reported", null),
    UNKNOWN("Unknown", null),
}

data class ReviewItem(
    val id: String,
    val camera: String,
    val startTime: Double,
    val endTime: Double?,
    val severity: ReviewSeverity,
    val thumbnailPath: String? = null,
    val objects: List<String>,
    val zones: List<String>,
    val hasBeenReviewed: Boolean,
    val recordingAvailable: Boolean? = null,
    val audio: List<String> = emptyList(),
    val detectionIds: List<String> = emptyList(),
    val subLabels: List<String> = emptyList(),
    val summary: ReviewSummaryMetadata? = null,
    val linkedEvents: List<SearchEvent> = emptyList(),
    val rawSeverity: String? = null,
    val significantMotionAreas: List<Int> = emptyList(),
)

/** A Review snapshot carried by Frigate's real-time channel, before REST reconciliation. */
data class RealtimeReviewItem(
    val id: String,
    val camera: String,
    val startTime: Double,
    val endTime: Double?,
    val severity: ReviewSeverity,
    val rawSeverity: String?,
    val thumbnailPath: String?,
    val objects: Set<String> = emptySet(),
    val zones: Set<String> = emptySet(),
    val audio: Set<String> = emptySet(),
    val detectionIds: Set<String> = emptySet(),
    val subLabels: Set<String> = emptySet(),
    val significantMotionAreas: Set<Int> = emptySet(),
    val summary: ReviewSummaryMetadata? = null,
    val hasBeenReviewed: Boolean? = null,
)

data class RealtimeReviewUpdate(
    val lifecycle: ReviewLifecycle,
    val rawLifecycle: String?,
    val before: RealtimeReviewItem?,
    val after: RealtimeReviewItem,
)

data class ReviewSummaryMetadata(
    val title: String? = null,
    val shortSummary: String? = null,
    val scene: String? = null,
    val potentialThreatLevel: Int? = null,
    val otherConcerns: List<String> = emptyList(),
)

data class ReviewCounts(
    val reviewedAlerts: Int = 0,
    val reviewedDetections: Int = 0,
    val totalAlerts: Int = 0,
    val totalDetections: Int = 0,
) {
    val unreviewedAlerts: Int get() = (totalAlerts - reviewedAlerts).coerceAtLeast(0)
    val unreviewedDetections: Int get() = (totalDetections - reviewedDetections).coerceAtLeast(0)
    val unreviewedTotal: Int get() = unreviewedAlerts + unreviewedDetections
}

data class ReviewSearchQuery(
    val cameras: Set<String>,
    val severity: ReviewSeverity? = null,
    val label: String? = null,
    val zone: String? = null,
    val reviewed: Boolean? = null,
    val after: Double? = null,
    val before: Double? = null,
    val limit: Int = 100,
)

data class EventSearchQuery(
    val text: String?,
    val cameras: Set<String>,
    val after: Double? = null,
    val before: Double? = null,
    val label: String? = null,
    val subLabel: String? = null,
    val zone: String? = null,
    val recognizedLicensePlate: String? = null,
    val eventId: String? = null,
    val limit: Int = 50,
)

private const val MAX_LITERAL_LICENSE_PLATE_FILTER_LENGTH = 32

/**
 * Keeps license-plate lookups on Frigate's literal-match path. Older supported
 * Frigate versions interpret several punctuation characters as raw regular
 * expressions, so every caller must use this policy before sending a filter.
 */
internal fun isSafeLiteralLicensePlateFilter(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.length in 1..MAX_LITERAL_LICENSE_PLATE_FILTER_LENGTH &&
        trimmed.all { character ->
            character.isLetterOrDigit() || character == ' ' || character == '-' || character == '_'
        }
}

data class SearchEvent(
    val id: String,
    val camera: String,
    val label: String,
    val subLabel: String? = null,
    val zones: List<String> = emptyList(),
    val startTime: Double,
    val endTime: Double?,
    val description: String? = null,
    val recognizedLicensePlate: String? = null,
    val recognizedLicensePlateScore: Double? = null,
    val averageEstimatedSpeed: Double? = null,
    val attributes: List<String> = emptyList(),
    val hasClip: Boolean = true,
)

data class RecordingSegment(
    val startTime: Double,
    val endTime: Double,
)

data class RecordingHourSummary(
    val day: String,
    val hour: Int,
    val durationSeconds: Int,
    val motionSeconds: Double,
    val objectSeconds: Double,
    val eventCount: Int,
)

data class MotionActivity(
    val startTime: Double,
    val motion: Double,
    val camera: String,
)

data class RecordingExport(
    val id: String,
    val camera: String,
    val name: String,
    val createdAt: Double,
    val videoPath: String,
    val thumbnailPath: String?,
    val inProgress: Boolean,
    val incidentId: String? = null,
)

data class RecordingExportStart(
    val exportId: String,
    val message: String,
)

enum class ReviewSeverity {
    ALERT,
    DETECTION,
    SIGNIFICANT_MOTION,
    UNKNOWN,
}

enum class ReviewLifecycle {
    NEW,
    UPDATE,
    END,
    GENAI,
    UNKNOWN,
}

data class BirdseyeStatus(
    val enabled: Boolean,
    val restreamConfigured: Boolean,
    val streamAvailable: Boolean,
    val streamName: String? = null,
) {
    val playable: Boolean
        get() = enabled && restreamConfigured && streamAvailable && streamName != null
}

data class DecoderCapability(
    val name: String,
    val mimeType: String,
    val hardwareAccelerated: Boolean?,
    val softwareOnly: Boolean?,
    val vendor: Boolean?,
    val adaptivePlayback: Boolean,
    val maxSupportedInstances: Int? = null,
)

data class CodecCapability(
    val label: String,
    val mimeType: String,
    val decoders: List<DecoderCapability>,
) {
    val supported: Boolean get() = decoders.isNotEmpty()
    val hasHardwareDecoder: Boolean get() = decoders.any { it.hardwareAccelerated == true }
}

data class DeviceDiagnostics(
    val manufacturer: String,
    val model: String,
    val device: String,
    val androidRelease: String,
    val apiLevel: Int,
    val codecs: List<CodecCapability>,
)

data class DiscoverySnapshot(
    val frigateVersion: String,
    val user: FrigateUserProfile,
    val cameras: List<Camera>,
    val streamMetadata: Map<String, StreamMetadata>,
    val recentReviewItems: List<ReviewItem>,
    val birdseye: BirdseyeStatus,
    val warnings: List<String> = emptyList(),
    val versionCompatibility: ServerVersionCompatibility = ServerVersionCompatibility.UNKNOWN,
    val authorizedCameraNames: Map<String, String> = cameras.associate { it.name to it.displayName },
    val capabilities: FrigateCapabilities = FrigateCapabilities.unknown(),
    val ptzCameras: Map<String, CameraPtzInfo> = emptyMap(),
    val cameraGroups: List<CameraGroup> = emptyList(),
)

data class RecordingStorageVolume(
    val totalMiB: Double,
    val usedMiB: Double,
    val freeMiB: Double,
)

data class CameraStorageUsage(
    val cameraName: String,
    val displayName: String,
    val usageMiB: Double,
    val percentageOfTotal: Double,
    val bandwidthMiBPerHour: Double,
)

data class RecordingStorageSummary(
    val volume: RecordingStorageVolume,
    val cameras: List<CameraStorageUsage>,
    val allCameraUsageMiB: Double,
    val otherUsageMiB: Double,
) {
    val unusedMiB: Double get() = volume.freeMiB.coerceAtLeast(0.0)
}

data class DetectorPerformance(
    val name: String,
    val inferenceSpeedMs: Double?,
)

data class AcceleratorPerformance(
    val name: String,
    val kind: String,
    val usagePercent: Double?,
    val memoryPercent: Double?,
)

data class CameraPerformance(
    val cameraName: String,
    val displayName: String,
    val cameraFps: Double?,
    val processFps: Double?,
    val detectionFps: Double?,
    val skippedFps: Double?,
)

data class TemperatureReading(
    val name: String,
    val celsius: Double,
)

data class FrigatePerformanceSummary(
    val version: String,
    val uptimeSeconds: Double?,
    val cameraFps: Double?,
    val processFps: Double?,
    val detectionFps: Double?,
    val skippedFps: Double?,
    val systemCpuPercent: Double?,
    val frigateCpuPercent: Double?,
    val frigateMemoryPercent: Double?,
    val detectors: List<DetectorPerformance>,
    val accelerators: List<AcceleratorPerformance>,
    val cameras: List<CameraPerformance>,
    val temperatures: List<TemperatureReading>,
)

data class FrigateInformationSummary(
    val performance: FrigatePerformanceSummary,
    val storage: RecordingStorageSummary,
)

enum class ServerVersionCompatibility {
    SUPPORTED,
    COMPATIBLE_UNVERIFIED,
    UNSUPPORTED,
    UNKNOWN,
}

enum class FrigateApiGeneration {
    V0_17,
    V0_18,
    UNKNOWN,
}

data class ServerVersionInfo(
    val rawVersion: String,
    val major: Int?,
    val minor: Int?,
    val patch: Int?,
    val compatibility: ServerVersionCompatibility,
    val warning: String? = null,
    val normalizedVersion: String = rawVersion.trim().removePrefix("v").lowercase(),
    val prerelease: String? = null,
    val apiGeneration: FrigateApiGeneration = FrigateApiGeneration.UNKNOWN,
    val validatedContract: Boolean = compatibility == ServerVersionCompatibility.SUPPORTED,
    val latestTestedBuild: String? = null,
)

enum class StreamPreference {
    AUTOMATIC,
    MAIN,
    LOW_BANDWIDTH,
}

data class StreamSelection(
    val option: LiveStreamOption,
    val reason: String,
)

data class AppSettings(
    val streamPreference: StreamPreference = StreamPreference.AUTOMATIC,
    val preferRtpTcp: Boolean = true,
    val startLiveMuted: Boolean = false,
    val diagnosticsEnabled: Boolean = true,
    val automaticUpdateChecksEnabled: Boolean = true,
    val appearanceMode: AppearanceMode = AppearanceMode.SYSTEM,
    val customThemeColors: CustomThemeColors = CustomThemeColors(),
    val stretchedCameraNames: Set<String> = emptySet(),
    val savedCameraViews: List<SavedCameraView> = emptyList(),
    val reducedMotion: Boolean = false,
    val reducedTransparency: Boolean = false,
    val highContrast: Boolean = false,
    val subtleRoundedCorners: Boolean = false,
    val favoriteCameraNames: List<String> = emptyList(),
    val hiddenHomeCameraNames: Set<String> = emptySet(),
    val favoriteViewIds: List<String> = emptyList(),
    val startupTarget: StartupTarget = StartupTarget(),
    val lastViewedTarget: StartupTarget? = null,
    val autoMarkReviewedAfterPlayback: Boolean = false,
    val recentActivitySearches: List<String> = emptyList(),
)

data class StartupTarget(
    val kind: StartupTargetKind = StartupTargetKind.HOME,
    val value: String? = null,
)

enum class StartupTargetKind {
    HOME,
    LAST_VIEWED,
    CAMERA,
    SAVED_VIEW,
    CAMERA_GROUP,
    BIRDSEYE,
}

data class CustomThemeColors(
    val accentArgb: Int = 0xFFFF7048.toInt(),
    val backgroundArgb: Int = 0xFF07111F.toInt(),
)

enum class AppearanceMode {
    SYSTEM,
    DARK,
    LIGHT,
    CUSTOM,
}
