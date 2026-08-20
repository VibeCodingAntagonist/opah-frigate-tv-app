package app.opah.tv.ui

import android.content.Context
import android.graphics.BitmapFactory
import app.opah.tv.data.CameraImage
import app.opah.tv.data.ReviewImage
import app.opah.tv.data.model.AcceleratorPerformance
import app.opah.tv.data.model.AppSettings
import app.opah.tv.data.model.AppearanceMode
import app.opah.tv.data.model.AudioCodec
import app.opah.tv.data.model.BirdseyeStatus
import app.opah.tv.data.model.Camera
import app.opah.tv.data.model.CameraPerformance
import app.opah.tv.data.model.CameraStorageUsage
import app.opah.tv.data.model.CodecCapability
import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.model.CustomThemeColors
import app.opah.tv.data.model.DecoderCapability
import app.opah.tv.data.model.DetectorPerformance
import app.opah.tv.data.model.DeviceDiagnostics
import app.opah.tv.data.model.DiscoverySnapshot
import app.opah.tv.data.model.FrigateInformationSummary
import app.opah.tv.data.model.FrigatePerformanceSummary
import app.opah.tv.data.model.FrigateUserProfile
import app.opah.tv.data.model.LiveStreamOption
import app.opah.tv.data.model.RecordingStorageSummary
import app.opah.tv.data.model.RecordingStorageVolume
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.ReviewSeverity
import app.opah.tv.data.model.ServerVersionCompatibility
import app.opah.tv.data.model.StreamMetadata
import app.opah.tv.data.model.TemperatureReading
import app.opah.tv.data.model.VideoCodec
import app.opah.tv.playback.PlaybackKind
import app.opah.tv.playback.PlaybackRequest

internal const val DOCUMENTATION_URI_PREFIX = "documentation://"

internal interface DocumentationResourceProvider {
    fun drawable(resourceName: String): Int
}

internal object DocumentationResources {
    private val provider: DocumentationResourceProvider by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        check(app.opah.tv.BuildConfig.DOCUMENTATION_MODE) {
            "Documentation resources are unavailable in production builds."
        }
        val providerClass = Class.forName(
            "app.opah.tv.ui.DocumentationResourceProviderImpl",
        )
        providerClass.getDeclaredConstructor().newInstance() as DocumentationResourceProvider
    }

    fun drawable(resourceName: String): Int = provider.drawable(resourceName)
}

internal object DocumentationFixtures {
    private val profile = ConnectionProfile(
        apiBaseUrl = "https://frigate.example.test",
        username = "demo_viewer",
        rtspHostOverride = "streams.example.test",
        rtspPort = 8554,
    )

    private val cameraDefinitions = listOf(
        Triple("entry", "Front Entry", VideoCodec.AVC),
        Triple("garden", "Garden", VideoCodec.AVC),
        Triple("driveway", "Driveway", VideoCodec.HEVC),
    )

    private val cameras = cameraDefinitions.mapIndexed { index, (name, displayName, codec) ->
        Camera(
            name = name,
            displayName = displayName,
            order = index,
            streams = listOf(
                LiveStreamOption(
                    label = "Main",
                    streamName = "${name}_main",
                    metadata = StreamMetadata(
                        streamName = "${name}_main",
                        available = true,
                        videoCodec = codec,
                        audioCodec = AudioCodec.AAC,
                        width = if (name == "driveway") 3840 else 2560,
                        height = if (name == "driveway") 2160 else 1440,
                        evidence = listOf("Documentation fixture"),
                    ),
                ),
                LiveStreamOption(
                    label = "Low bandwidth",
                    streamName = "${name}_sub",
                    metadata = StreamMetadata(
                        streamName = "${name}_sub",
                        available = true,
                        videoCodec = VideoCodec.AVC,
                        audioCodec = AudioCodec.NONE,
                        width = 1280,
                        height = 720,
                        evidence = listOf("Documentation fixture"),
                    ),
                ),
            ),
        )
    }

    private val reviewItems = listOf(
        reviewItem("review-entry", "entry", 1_787_157_300.0, ReviewSeverity.ALERT, listOf("person"), listOf("porch")),
        reviewItem("review-driveway", "driveway", 1_787_153_900.0, ReviewSeverity.ALERT, listOf("car"), listOf("driveway")),
        reviewItem("review-garden", "garden", 1_787_147_200.0, ReviewSeverity.DETECTION, listOf("dog"), listOf("yard")),
        reviewItem("review-entry-2", "entry", 1_787_139_800.0, ReviewSeverity.ALERT, listOf("package"), listOf("porch")),
        reviewItem("review-driveway-2", "driveway", 1_787_131_100.0, ReviewSeverity.DETECTION, listOf("bicycle"), listOf("driveway")),
        reviewItem("review-garden-2", "garden", 1_787_121_800.0, ReviewSeverity.ALERT, listOf("cat"), listOf("yard")),
    )

    private val streamMetadata = cameras
        .flatMap(Camera::streams)
        .mapNotNull(LiveStreamOption::metadata)
        .associateBy(StreamMetadata::streamName) + (
        "birdseye" to StreamMetadata(
            streamName = "birdseye",
            available = true,
            videoCodec = VideoCodec.AVC,
            audioCodec = AudioCodec.NONE,
            width = 1920,
            height = 1080,
            evidence = listOf("Documentation fixture"),
        )
        )

    private val snapshot = DiscoverySnapshot(
        frigateVersion = "0.18.0",
        user = FrigateUserProfile(
            username = profile.username,
            role = "viewer",
            allowedCameras = cameras.map(Camera::name).toSet(),
        ),
        cameras = cameras,
        streamMetadata = streamMetadata,
        recentReviewItems = reviewItems.take(3),
        birdseye = BirdseyeStatus(
            enabled = true,
            restreamConfigured = true,
            streamAvailable = true,
            streamName = "birdseye",
        ),
        versionCompatibility = ServerVersionCompatibility.SUPPORTED,
    )

    private val device = DeviceDiagnostics(
        manufacturer = "Reference",
        model = "Android TV",
        device = "documentation",
        androidRelease = "14",
        apiLevel = 34,
        codecs = listOf(
            codecCapability("H.264 / AVC", "video/avc", "Reference AVC hardware decoder"),
            codecCapability("H.265 / HEVC", "video/hevc", "Reference HEVC hardware decoder"),
        ),
    )

    private val information = FrigateInformationSummary(
        performance = FrigatePerformanceSummary(
            version = "0.18.0",
            uptimeSeconds = 432_845.0,
            cameraFps = 45.0,
            processFps = 15.0,
            detectionFps = 6.4,
            skippedFps = 0.0,
            systemCpuPercent = 38.2,
            frigateCpuPercent = 164.0,
            frigateMemoryPercent = 27.6,
            detectors = listOf(DetectorPerformance("coral", 7.82)),
            accelerators = listOf(AcceleratorPerformance("Integrated GPU", "GPU", 31.0, 22.0)),
            cameras = listOf(
                CameraPerformance("entry", "Front Entry", 15.0, 5.0, 2.1, 0.0),
                CameraPerformance("garden", "Garden", 15.0, 5.0, 1.8, 0.0),
                CameraPerformance("driveway", "Driveway", 15.0, 5.0, 2.5, 0.0),
            ),
            temperatures = listOf(TemperatureReading("System", 51.4), TemperatureReading("GPU", 47.8)),
        ),
        storage = RecordingStorageSummary(
            volume = RecordingStorageVolume(
                totalMiB = 512_000.0,
                usedMiB = 286_720.0,
                freeMiB = 225_280.0,
            ),
            cameras = listOf(
                CameraStorageUsage("entry", "Front Entry", 42_500.0, 8.30, 1_180.0),
                CameraStorageUsage("garden", "Garden", 56_900.0, 11.11, 1_620.0),
                CameraStorageUsage("driveway", "Driveway", 91_300.0, 17.83, 2_460.0),
            ),
            allCameraUsageMiB = 190_700.0,
            otherUsageMiB = 96_020.0,
        ),
    )

    fun state(rawScenario: String?): Phase0UiState {
        val scenario = rawScenario?.uppercase().orEmpty()
        val base = connectedState()
        return when (scenario) {
            "CONNECTING" -> Phase0UiState(
                loading = true,
                statusMessage = "Connecting…",
                savedProfile = profile,
                settings = base.settings,
            )
            "SETUP" -> Phase0UiState(
                loading = false,
                statusMessage = "Sign in to Frigate.",
                savedProfile = profile,
                settings = base.settings,
            )
            "RECOVERY" -> Phase0UiState(
                loading = false,
                statusMessage = "Frigate is unavailable",
                errorMessage = "The demonstration server could not be reached.",
                savedProfile = profile,
                settings = base.settings,
                savedSessionRecoveryAvailable = true,
            )
            "REVIEW_DETAIL" -> base.copy(
                review = base.review.copy(
                    selectedItemId = reviewItems.first().id,
                    recordingState = ReviewRecordingState.AVAILABLE,
                ),
            )
            "LIVE_PLAYBACK" -> base.copy(
                playback = livePlayback(cameras.first()),
                activeCameraName = cameras.first().name,
            )
            "RECORDED_PLAYBACK" -> base.copy(playback = recordedPlayback(reviewItems.first()))
            "CUSTOM_THEME" -> base.copy(
                settings = base.settings.copy(
                    appearanceMode = AppearanceMode.CUSTOM,
                    customThemeColors = CustomThemeColors(
                        accentArgb = 0xFF5ED4C6.toInt(),
                        backgroundArgb = 0xFF0B1730.toInt(),
                    ),
                ),
            )
            else -> base
        }
    }

    fun reviewItems(): List<ReviewItem> = reviewItems

    fun information(): FrigateInformationSummary = information

    fun livePlayback(camera: Camera): PlaybackRequest = PlaybackRequest(
        title = camera.displayName,
        uri = "$DOCUMENTATION_URI_PREFIX${camera.name}",
        kind = PlaybackKind.LIVE,
        detail = "Main • Automatic stream selection",
    )

    fun birdseyePlayback(): PlaybackRequest = PlaybackRequest(
        title = "Birdseye",
        uri = "${DOCUMENTATION_URI_PREFIX}birdseye",
        kind = PlaybackKind.LIVE,
        detail = "Frigate composite • Single RTSP stream",
    )

    fun recordedPlayback(item: ReviewItem): PlaybackRequest = PlaybackRequest(
        title = "Alert — ${cameraDisplayName(item.camera)}",
        uri = "$DOCUMENTATION_URI_PREFIX${item.camera}",
        kind = PlaybackKind.RECORDED,
        detail = "Frigate retained recording",
    )

    private fun connectedState(): Phase0UiState = Phase0UiState(
        loading = false,
        statusMessage = "Connected",
        savedProfile = profile,
        activeProfile = profile,
        snapshot = snapshot,
        device = device,
        settings = AppSettings(
            appearanceMode = AppearanceMode.DARK,
            diagnosticsEnabled = true,
        ),
        review = ReviewBrowserState(
            items = reviewItems.filter { it.severity == ReviewSeverity.ALERT },
            knownLabels = reviewItems.flatMap(ReviewItem::objects).toSet(),
            knownZones = reviewItems.flatMap(ReviewItem::zones).toSet(),
            loadedOnce = true,
        ),
        information = InformationUiState(
            loadedOnce = true,
            summary = information,
        ),
    )

    private fun reviewItem(
        id: String,
        camera: String,
        startTime: Double,
        severity: ReviewSeverity,
        objects: List<String>,
        zones: List<String>,
    ) = ReviewItem(
        id = id,
        camera = camera,
        startTime = startTime,
        endTime = startTime + 28.0,
        severity = severity,
        thumbnailPath = null,
        objects = objects,
        zones = zones,
        hasBeenReviewed = id.endsWith("2"),
        recordingAvailable = true,
    )

    private fun codecCapability(label: String, mimeType: String, decoderName: String) = CodecCapability(
        label = label,
        mimeType = mimeType,
        decoders = listOf(
            DecoderCapability(
                name = decoderName,
                mimeType = mimeType,
                hardwareAccelerated = true,
                softwareOnly = false,
                vendor = true,
                adaptivePlayback = true,
            ),
        ),
    )

    private fun cameraDisplayName(cameraName: String): String =
        cameras.firstOrNull { it.name == cameraName }?.displayName ?: cameraName
}

internal class DocumentationImageStore(context: Context) {
    private val resources = context.resources
    private val cameraImages = mutableMapOf<String, CameraImage>()

    fun camera(cameraName: String): CameraImage? = cameraImages.getOrPut(cameraName) {
        val resourceName = when (cameraName) {
            "entry" -> "docs_camera_entry"
            "garden" -> "docs_camera_garden"
            "driveway" -> "docs_camera_driveway"
            "birdseye" -> "docs_camera_birdseye"
            else -> "docs_camera_entry"
        }
        val resourceId = DocumentationResources.drawable(resourceName)
        CameraImage(
            bitmap = requireNotNull(BitmapFactory.decodeResource(resources, resourceId)),
            loadedAtMillis = System.currentTimeMillis(),
        )
    }

    fun review(item: ReviewItem): ReviewImage? = camera(item.camera)?.let {
        ReviewImage(bitmap = it.bitmap, loadedAtMillis = it.loadedAtMillis)
    }
}
