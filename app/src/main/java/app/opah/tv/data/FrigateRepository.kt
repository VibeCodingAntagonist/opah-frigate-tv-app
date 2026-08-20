package app.opah.tv.data

import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.model.DiscoverySnapshot
import app.opah.tv.data.model.FrigateUserProfile
import app.opah.tv.data.model.FrigateInformationSummary
import app.opah.tv.data.model.CameraStorageUsage
import app.opah.tv.data.model.RecordingStorageSummary
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.ReviewSearchQuery
import app.opah.tv.data.network.FrigateGateway
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class DiscoveryBootstrap(
    internal val config: String,
    internal val catalog: CameraCatalog,
    val snapshot: DiscoverySnapshot,
)

/** Coordinates foundation repositories into the snapshot consumed by the prototype UI. */
class FrigateRepository(
    private val api: FrigateGateway,
    private val parsers: FrigateJsonParsers,
    private val cameraRepository: CameraRepository = CameraRepository(parsers),
    private val streamRepository: StreamRepository = StreamRepository(api, parsers),
    private val reviewRepository: ReviewRepository = ReviewRepository(api, parsers),
    private val versionPolicy: FrigateVersionPolicy = FrigateVersionPolicy(),
) {
    suspend fun refresh(profile: ConnectionProfile, user: FrigateUserProfile): DiscoverySnapshot =
        discover(profile, user)

    /**
     * Loads only the freshly authorized camera catalog needed to make Home usable.
     * Stream, Review, Birdseye availability, and version enrichment deliberately follow later.
     */
    suspend fun discoverEssential(
        profile: ConnectionProfile,
        user: FrigateUserProfile,
    ): DiscoveryBootstrap {
        val config = api.getConfig(profile)
        val catalog = cameraRepository.catalog(config, user)
        return DiscoveryBootstrap(
            config = config,
            catalog = catalog,
            snapshot = DiscoverySnapshot(
                frigateVersion = "Loading",
                user = user,
                cameras = catalog.cameras,
                streamMetadata = emptyMap(),
                recentReviewItems = emptyList(),
                birdseye = catalog.birdseye,
                authorizedCameraNames = parsers.parseAuthorizedCameraNames(config, user.allowedCameras),
            ),
        )
    }

    suspend fun enrich(
        profile: ConnectionProfile,
        bootstrap: DiscoveryBootstrap,
    ): DiscoverySnapshot = coroutineScope {
        val versionDeferred = async { versionPolicy.evaluate(api.getVersion(profile)) }
        val streamDeferred = async { streamRepository.discover(profile, bootstrap.catalog) }
        val reviewDeferred = async {
            reviewRepository.recent(profile, bootstrap.snapshot.user.allowedCameras)
        }

        val version = versionDeferred.await()
        val streamDiscovery = streamDeferred.await()
        val reviewDiscovery = reviewDeferred.await()
        val warnings = buildList {
            version.warning?.let(::add)
            addAll(streamDiscovery.warnings)
            addAll(reviewDiscovery.warnings)
        }

        bootstrap.snapshot.copy(
            frigateVersion = version.rawVersion,
            cameras = parsers.parseCameras(
                bootstrap.config,
                streamDiscovery.metadata,
                bootstrap.snapshot.user.allowedCameras,
            ),
            streamMetadata = streamDiscovery.metadata,
            recentReviewItems = reviewDiscovery.items,
            birdseye = parsers.parseBirdseyeStatus(bootstrap.config, streamDiscovery.metadata),
            warnings = warnings,
            versionCompatibility = version.compatibility,
        )
    }

    fun reviewPlaybackUrl(profile: ConnectionProfile, item: ReviewItem): String =
        reviewRepository.playbackUrl(profile, item)

    suspend fun searchReview(
        profile: ConnectionProfile,
        allowedCameras: Set<String>,
        query: ReviewSearchQuery,
    ): ReviewDiscovery = reviewRepository.search(profile, allowedCameras, query)

    suspend fun reviewRecordingAvailable(
        profile: ConnectionProfile,
        item: ReviewItem,
    ): Result<Boolean> = reviewRepository.recordingAvailable(profile, item)

    suspend fun markReviewReviewed(profile: ConnectionProfile, item: ReviewItem) =
        reviewRepository.markReviewed(profile, item)

    suspend fun loadRecordingStorage(
        profile: ConnectionProfile,
        snapshot: DiscoverySnapshot,
    ): RecordingStorageSummary = coroutineScope {
        val statsDeferred = async { api.getStats(profile) }
        val storageDeferred = async { api.getRecordingsStorage(profile) }
        createStorageSummary(
            statsJson = statsDeferred.await(),
            storageJson = storageDeferred.await(),
            snapshot = snapshot,
        )
    }

    suspend fun loadInformation(
        profile: ConnectionProfile,
        snapshot: DiscoverySnapshot,
    ): FrigateInformationSummary = coroutineScope {
        val statsDeferred = async { api.getStats(profile) }
        val storageDeferred = async { api.getRecordingsStorage(profile) }
        val statsJson = statsDeferred.await()
        FrigateInformationSummary(
            performance = parsers.parsePerformanceSummary(
                rawJson = statsJson,
                fallbackVersion = snapshot.frigateVersion,
                authorizedCameraNames = snapshot.authorizedCameraNames,
            ),
            storage = createStorageSummary(statsJson, storageDeferred.await(), snapshot),
        )
    }

    private fun createStorageSummary(
        statsJson: String,
        storageJson: String,
        snapshot: DiscoverySnapshot,
    ): RecordingStorageSummary {
        val volume = parsers.parseRecordingStorageVolume(statsJson)
            ?: error("Frigate did not return recording storage totals.")
        val samples = parsers.parseCameraStorageSamples(storageJson)
        val samplesByLabel = samples.associateBy { normalizeStorageLabel(it.serverLabel) }
        val visible = snapshot.authorizedCameraNames.map { (cameraName, displayName) ->
            val sample = sequenceOf(displayName, cameraName)
                .map(::normalizeStorageLabel)
                .mapNotNull(samplesByLabel::get)
                .firstOrNull()
            val usage = sample?.usageMiB ?: 0.0
            CameraStorageUsage(
                cameraName = cameraName,
                displayName = displayName,
                usageMiB = usage,
                percentageOfTotal = if (volume.totalMiB > 0.0) usage / volume.totalMiB * 100 else 0.0,
                bandwidthMiBPerHour = sample?.bandwidthMiBPerHour ?: 0.0,
            )
        }
        val allCameraUsage = samples.sumOf(FrigateJsonParsers.CameraStorageSample::usageMiB)
        return RecordingStorageSummary(
            volume = volume,
            cameras = visible.sortedWith(
                compareByDescending<CameraStorageUsage>(CameraStorageUsage::usageMiB)
                    .thenBy(CameraStorageUsage::displayName),
            ),
            allCameraUsageMiB = allCameraUsage,
            otherUsageMiB = (volume.usedMiB - allCameraUsage).coerceAtLeast(0.0),
        )
    }

    suspend fun discover(
        profile: ConnectionProfile,
        user: FrigateUserProfile,
    ): DiscoverySnapshot = enrich(profile, discoverEssential(profile, user))

    private fun normalizeStorageLabel(value: String): String =
        value.trim().lowercase().replace('_', ' ')
}
