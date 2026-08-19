package app.opah.tv.data.model

data class ConnectionProfile(
    val apiBaseUrl: String,
    val username: String,
    val rtspHostOverride: String? = null,
    val rtspPort: Int = 8554,
)

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
)

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

data class RecordingSegment(
    val startTime: Double,
    val endTime: Double,
)

enum class ReviewSeverity {
    ALERT,
    DETECTION,
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

data class ServerVersionInfo(
    val rawVersion: String,
    val major: Int?,
    val minor: Int?,
    val patch: Int?,
    val compatibility: ServerVersionCompatibility,
    val warning: String? = null,
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
    val appearanceMode: AppearanceMode = AppearanceMode.SYSTEM,
    val customThemeColors: CustomThemeColors = CustomThemeColors(),
)

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
