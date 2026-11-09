package app.opah.tv.ui

import app.opah.tv.data.model.AppSettings
import app.opah.tv.data.model.CameraGroup
import app.opah.tv.data.model.ReviewCounts
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.SavedCameraView
import app.opah.tv.data.model.SearchEvent
import app.opah.tv.data.model.StartupTarget
import app.opah.tv.data.model.StartupTargetKind
import app.opah.tv.privacy.AuthorizationFreshness
import app.opah.tv.privacy.PinUnlockProof
import app.opah.tv.privacy.PrivacyDecision
import app.opah.tv.privacy.PrivacyDecisionEngine
import app.opah.tv.privacy.PrivacyGrant
import app.opah.tv.privacy.PrivacyRequest
import app.opah.tv.privacy.PrivacySnapshot
import app.opah.tv.privacy.PrivacySurface
import app.opah.tv.privacy.PrivacyTarget
import app.opah.tv.privacy.RecognitionDisclosure
import app.opah.tv.playback.PlaybackKind
import app.opah.tv.briefing.BriefingAudience

/** One UI projection; screens never receive an unscoped connected-state collection. */
internal fun Phase0UiState.projectForPrivacy(
    engine: PrivacyDecisionEngine = PrivacyDecisionEngine(),
): Phase0UiState {
    val discovery = snapshot ?: return this
    val policy = privacySnapshot(discovery.user.allowedCameras)
    val epoch = policy.epoch

    fun grant(surface: PrivacySurface, target: PrivacyTarget): PrivacyGrant? = when (
        val decision = engine.decide(PrivacyRequest(surface, target, epoch), policy)
    ) {
        is PrivacyDecision.Allow -> decision.grant
        is PrivacyDecision.AllowRedacted -> decision.grant
        is PrivacyDecision.RequirePin,
        is PrivacyDecision.Deny,
        -> null
    }

    val cameraGrants = discovery.cameras.associate { camera ->
        camera.name to grant(PrivacySurface.CAMERA_CATALOG, PrivacyTarget.Camera(camera.name))
    }
    val visibleCameraIds = cameraGrants.filterValues { it != null }.keys
    val visibleCameras = discovery.cameras.filter { it.name in visibleCameraIds }
    val visibleReviewItems = discovery.recentReviewItems.mapNotNull { item ->
        grant(
            PrivacySurface.HOME,
            PrivacyTarget.Review(item.id, item.camera, item.containsRecognition()),
        )?.let { item.redacted(it.recognitionDisclosure) }
    }
    val visibleGroups = discovery.cameraGroups.mapNotNull { group ->
        group.visibleCopy(engine, policy)
    }
    val visibleStreamNames = visibleCameras.flatMapTo(mutableSetOf()) { camera ->
        camera.streams.map { it.streamName }
    }
    val anyCameraHidden = !visibleCameraIds.containsAll(discovery.user.allowedCameras)
    val projectedDiscovery = discovery.copy(
        user = discovery.user.copy(allowedCameras = discovery.user.allowedCameras.intersect(visibleCameraIds)),
        cameras = visibleCameras,
        recentReviewItems = visibleReviewItems,
        birdseye = discovery.birdseye.copy(
            enabled = discovery.birdseye.enabled && visibleCameraIds.containsAll(discovery.user.allowedCameras),
        ),
        streamMetadata = discovery.streamMetadata.filterKeys(visibleStreamNames::contains),
        warnings = if (anyCameraHidden || !privacy.available) emptyList() else discovery.warnings,
        authorizedCameraNames = discovery.authorizedCameraNames.filterKeys(visibleCameraIds::contains),
        ptzCameras = discovery.ptzCameras.filterKeys(visibleCameraIds::contains),
        cameraGroups = visibleGroups,
    )

    val reviewItems = review.items.mapNotNull { item ->
        grant(
            PrivacySurface.ACTIVITY,
            PrivacyTarget.Review(item.id, item.camera, item.containsRecognition()),
        )?.let { item.redacted(it.recognitionDisclosure) }
    }
    val visibleReviewIds = reviewItems.mapTo(mutableSetOf(), ReviewItem::id)
    val activityLocallyLocked = privacy.guestModeActive || (
        app.opah.tv.privacy.PinScope.ACTIVITY_HISTORY_SEARCH in privacy.protectedScopes &&
            app.opah.tv.privacy.PinScope.ACTIVITY_HISTORY_SEARCH !in privacy.unlockedScopes
        )
    val projectedReview = review.copy(
        filters = review.filters.copy(camera = review.filters.camera?.takeIf(visibleCameraIds::contains)),
        items = reviewItems,
        counts = if (activityLocallyLocked || anyCameraHidden) {
            reviewItems.toReviewCounts()
        } else {
            review.counts
        },
        selectedItemId = review.selectedItemId?.takeIf(visibleReviewIds::contains),
        actionError = review.actionError?.takeIf { error ->
            val item = (review.items + discovery.recentReviewItems + listOfNotNull(review.playbackItem))
                .firstOrNull { it.id == error.itemId }
            item != null && grant(PrivacySurface.ACTIVITY,
                PrivacyTarget.Review(item.id, item.camera, item.containsRecognition())) != null &&
                error.cameraNames.all { camera -> grant(PrivacySurface.ACTIVITY, PrivacyTarget.Camera(camera)) != null }
        },
        queueItemIds = review.queueItemIds.filter(visibleReviewIds::contains),
        playbackItem = review.playbackItem?.let { item ->
            grant(
                PrivacySurface.ACTIVITY,
                PrivacyTarget.Review(item.id, item.camera, item.containsRecognition()),
            )?.let { item.redacted(it.recognitionDisclosure) }
        },
    )

    val projectedSearchResults = activitySearch.results.mapNotNull { event ->
        grant(
            PrivacySurface.SEARCH,
            PrivacyTarget.Review(event.id, event.camera, event.containsRecognition()),
        )?.let { event.redacted(it.recognitionDisclosure) }
    }
    val historyCameraVisible = history.cameraName?.let { camera ->
        grant(PrivacySurface.HISTORY, PrivacyTarget.Camera(camera)) != null
    } != false
    val motionCameraVisible = motionReview.cameraName?.let { camera ->
        grant(PrivacySurface.SEARCH, PrivacyTarget.Camera(camera)) != null
    } != false
    val visibleExports = exports.items.filter { export ->
        grant(
            PrivacySurface.CLIP,
            PrivacyTarget.ClipOrIncident(export.id, export.camera),
        ) != null
    }
    val visibleIncidents = exports.incidents.filter { incident ->
        val cameras = exports.items.asSequence()
            .filter { it.incidentId == incident.id }
            .map { it.camera }
            .distinct()
            .toList()
        cameras.isNotEmpty() && grant(
            PrivacySurface.INCIDENT,
            PrivacyTarget.ClipOrIncident(
                itemId = incident.id,
                cameraId = cameras.first(),
                additionalCameraIds = cameras.drop(1).toSet(),
            ),
        ) != null
    }
    val projectedSettings = settings.visibleCopy(
        visibleCameraIds = visibleCameraIds,
        visibleGroups = visibleGroups,
        birdseyeVisible = !anyCameraHidden,
        clearRecentSearches = activityLocallyLocked,
    )
    val playbackVisible = when {
        playback == null -> true
        playback.activityItemId != null && playback.cameraName != null -> grant(
            PrivacySurface.ACTIVITY,
            PrivacyTarget.Review(playback.activityItemId, playback.cameraName),
        ) != null
        playback.savedClipId != null && playback.cameraName != null -> grant(
            PrivacySurface.CLIP,
            PrivacyTarget.ClipOrIncident(playback.savedClipId, playback.cameraName),
        ) != null
        playback.detail == "Search result" && playback.cameraName != null -> grant(
            PrivacySurface.SEARCH,
            PrivacyTarget.Camera(playback.cameraName),
        ) != null
        playback.kind == PlaybackKind.RECORDED && playback.cameraName != null -> grant(
            PrivacySurface.HISTORY,
            PrivacyTarget.Camera(playback.cameraName),
        ) != null
        playback.cameraName != null -> grant(
            PrivacySurface.LIVE_PLAYBACK,
            PrivacyTarget.Camera(playback.cameraName),
        ) != null
        playback.kind == PlaybackKind.LIVE -> grant(
            PrivacySurface.VIEW,
            PrivacyTarget.CameraCollection("active-composite", discovery.user.allowedCameras),
        )?.visibleCameraIds?.containsAll(discovery.user.allowedCameras) == true
        else -> false
    }
    val projectedGroupView = cameraGroupView?.copy(
        streams = cameraGroupView.streams.filter { stream -> stream.camera.name in visibleCameraIds },
    )?.takeIf { it.streams.size == cameraGroupView.streams.size }
    val projectedMonitor = monitorMode?.let { monitor ->
        val monitorCameraIds = monitor.cameras.mapTo(linkedSetOf()) { it.name }
        val monitorVisible = grant(
            PrivacySurface.MONITOR,
            PrivacyTarget.CameraCollection("active-monitor", monitorCameraIds),
        )?.visibleCameraIds.orEmpty()
        monitor.copy(
            cameras = monitor.cameras.filter { it.name in monitorVisible },
            visibleCameraIds = monitor.visibleCameraIds.intersect(monitorVisible),
            audioEnabled = monitor.audioEnabled && !privacy.guestModeActive,
        ).takeIf { it.cameras.isNotEmpty() }
    }
    val expectedBriefingAudience = if (privacy.guestModeActive) {
        BriefingAudience.GUEST
    } else {
        BriefingAudience.OWNER
    }
    val projectedBriefing = if (
        briefing.audience == expectedBriefingAudience && briefing.privacyEpoch == privacy.epoch
    ) {
        val currentSummary = briefing.summary
        if (currentSummary == null) {
            briefing
        } else {
            val projectedEntries = currentSummary.entries.mapNotNull { entry ->
                grant(
                    PrivacySurface.HOME,
                    PrivacyTarget.Review(
                        entry.item.id,
                        entry.item.camera,
                        entry.item.containsRecognition(),
                    ),
                )?.let { privacyGrant ->
                    entry.copy(
                        item = entry.item.redacted(privacyGrant.recognitionDisclosure),
                        frigateAnalysis = entry.frigateAnalysis.takeIf {
                            privacyGrant.recognitionDisclosure == RecognitionDisclosure.SHOW_ALL
                        },
                    )
                }
            }
            val projectedDismissalTargets = currentSummary.dismissalTargets.filter { target ->
                grant(
                    PrivacySurface.HOME,
                    PrivacyTarget.Review(
                        target.reviewId,
                        target.cameraId,
                        target.containsRecognition,
                    ),
                ) != null
            }
            briefing.copy(
                summary = currentSummary.copy(
                    entries = projectedEntries,
                    dismissalTargets = projectedDismissalTargets,
                ).takeIf {
                    projectedEntries.size == currentSummary.entries.size &&
                        projectedDismissalTargets.size == currentSummary.dismissalTargets.size
                },
            )
        }
    } else {
        BriefingUiState()
    }

    return copy(
        snapshot = projectedDiscovery,
        playback = playback.takeIf { playbackVisible },
        cameraGroupView = projectedGroupView,
        monitorMode = projectedMonitor,
        briefing = projectedBriefing,
        activeCameraName = activeCameraName?.takeIf(visibleCameraIds::contains),
        settings = projectedSettings,
        review = projectedReview,
        history = if (historyCameraVisible) history else HistoryBrowserState(),
        activitySearch = activitySearch.copy(
            filters = activitySearch.filters.copy(
                cameraName = activitySearch.filters.cameraName?.takeIf(visibleCameraIds::contains),
                subLabel = activitySearch.filters.subLabel.takeUnless { privacy.guestModeActive },
                recognizedLicensePlate = activitySearch.filters.recognizedLicensePlate
                    .takeUnless { privacy.guestModeActive },
            ),
            results = projectedSearchResults,
        ).afterSearchScopeChanged(discovery.cameras.mapNotNullTo(mutableSetOf()) { camera ->
            camera.name.takeIf { grant(PrivacySurface.SEARCH, PrivacyTarget.Camera(it)) != null }
        }),
        exports = exports.copy(
            items = visibleExports,
            incidents = visibleIncidents,
            selectedItemId = exports.selectedItemId?.takeIf { id -> visibleExports.any { it.id == id } },
            selectedIncidentId = exports.selectedIncidentId?.takeIf { id -> visibleIncidents.any { it.id == id } },
        ),
        ptz = ptz.takeIf { it.cameraName == null || it.cameraName in visibleCameraIds } ?: PtzUiState(),
        liveActions = liveActions.takeIf {
            it.recordingCameraName == null || it.recordingCameraName in visibleCameraIds
        } ?: LiveActionsUiState(),
        motionReview = motionReview.takeIf { motionCameraVisible } ?: MotionReviewUiState(),
        health = health.copy(messagesByCamera = health.messagesByCamera.filterKeys(visibleCameraIds::contains)),
    )
}

private fun Phase0UiState.privacySnapshot(allowedCameraIds: Set<String>): PrivacySnapshot = PrivacySnapshot(
    epoch = privacy.epoch,
    authenticated = activeProfile != null && snapshot != null,
    authorizationFreshness = if (activeProfile != null && snapshot != null) {
        AuthorizationFreshness.FRESH
    } else {
        AuthorizationFreshness.UNKNOWN
    },
    allowedCameraIds = allowedCameraIds.toSet(),
    guestMode = privacy.guestModeActive,
    privateCameraIds = privacy.privateCameraIds.toSet(),
    pinConfigured = privacy.pinConfigured,
    protectedScopes = privacy.protectedScopes.toSet(),
    pinUnlockProof = privacy.unlockedScopes.takeIf(Set<*>::isNotEmpty)?.let { scopes ->
        PinUnlockProof(privacy.epoch, scopes.toSet())
    },
    ownerRecognitionDisclosure = privacy.ownerRecognitionDisclosure,
    guestRecognitionDisclosure = privacy.guestRecognitionDisclosure,
    globalNotificationDisclosure = privacy.globalNotificationDisclosure,
    guestNotificationDisclosure = privacy.guestNotificationDisclosure,
    privacyPolicyAvailable = privacy.available && !privacy.loading,
)

private fun CameraGroup.visibleCopy(
    engine: PrivacyDecisionEngine,
    snapshot: PrivacySnapshot,
): CameraGroup? {
    if (cameraNames.isEmpty()) return null
    val decision = engine.decide(
        PrivacyRequest(
            surface = PrivacySurface.VIEW,
            target = PrivacyTarget.CameraCollection(name, cameraNames.toSet()),
            expectedPrivacyEpoch = snapshot.epoch,
        ),
        snapshot,
    )
    val visible = when (decision) {
        is PrivacyDecision.Allow -> decision.grant.visibleCameraIds
        is PrivacyDecision.AllowRedacted -> decision.grant.visibleCameraIds
        is PrivacyDecision.RequirePin,
        is PrivacyDecision.Deny,
        -> emptySet()
    }
    return visible.takeIf(Set<*>::isNotEmpty)?.let {
        copy(cameraNames = cameraNames.filter(visible::contains))
            .takeIf { filtered -> filtered.cameraNames.size == cameraNames.distinct().size }
    }
}

private fun AppSettings.visibleCopy(
    visibleCameraIds: Set<String>,
    visibleGroups: List<CameraGroup>,
    birdseyeVisible: Boolean,
    clearRecentSearches: Boolean,
): AppSettings {
    val visibleViews = savedCameraViews.mapNotNull { it.visibleCopy(visibleCameraIds) }
    val visibleViewIds = visibleViews.mapTo(mutableSetOf(), SavedCameraView::id)
    val visibleGroupIds = visibleGroups.mapTo(mutableSetOf(), CameraGroup::name)
    fun StartupTarget.safe(): StartupTarget = when (kind) {
        StartupTargetKind.CAMERA -> takeIf { value in visibleCameraIds }
        StartupTargetKind.SAVED_VIEW -> takeIf { value in visibleViewIds }
        StartupTargetKind.CAMERA_GROUP -> takeIf { value in visibleGroupIds }
        StartupTargetKind.BIRDSEYE -> takeIf { birdseyeVisible && visibleCameraIds.isNotEmpty() }
        StartupTargetKind.HOME,
        StartupTargetKind.LAST_VIEWED,
        -> this
    } ?: StartupTarget()
    return copy(
        stretchedCameraNames = stretchedCameraNames.intersect(visibleCameraIds),
        savedCameraViews = visibleViews,
        favoriteCameraNames = favoriteCameraNames.filter(visibleCameraIds::contains),
        hiddenHomeCameraNames = hiddenHomeCameraNames.intersect(visibleCameraIds),
        favoriteViewIds = favoriteViewIds.filter(visibleViewIds::contains),
        startupTarget = startupTarget.safe(),
        lastViewedTarget = lastViewedTarget?.safe(),
        recentActivitySearches = if (clearRecentSearches) emptyList() else recentActivitySearches,
    )
}

private fun SavedCameraView.visibleCopy(visibleCameraIds: Set<String>): SavedCameraView? {
    val visible = cameraNames.filter(visibleCameraIds::contains)
    if (visible.size < 2 || visible.size != cameraNames.distinct().size) return null
    return SavedCameraView(
        id = id,
        name = name,
        firstCameraName = visible[0],
        secondCameraName = visible[1],
        thirdCameraName = visible.getOrNull(2),
        fourthCameraName = visible.getOrNull(3),
    )
}

private fun ReviewItem.containsRecognition(): Boolean =
    subLabels.isNotEmpty() || linkedEvents.any(SearchEvent::containsRecognition)

private fun SearchEvent.containsRecognition(): Boolean =
    !subLabel.isNullOrBlank() || !recognizedLicensePlate.isNullOrBlank()

private fun ReviewItem.redacted(disclosure: RecognitionDisclosure): ReviewItem = when (disclosure) {
    RecognitionDisclosure.SHOW_ALL -> this
    RecognitionDisclosure.HIDE_NAMES -> copy(
        subLabels = emptyList(),
        summary = null,
        linkedEvents = linkedEvents.map { it.redacted(disclosure) },
    )
    RecognitionDisclosure.HIDE_PLATES -> copy(
        summary = null,
        linkedEvents = linkedEvents.map { it.redacted(disclosure) },
    )
    RecognitionDisclosure.HIDE_ALL -> copy(
        subLabels = emptyList(),
        summary = null,
        linkedEvents = linkedEvents.map { it.redacted(disclosure) },
    )
}

private fun SearchEvent.redacted(disclosure: RecognitionDisclosure): SearchEvent = when (disclosure) {
    RecognitionDisclosure.SHOW_ALL -> this
    RecognitionDisclosure.HIDE_NAMES -> copy(subLabel = null, description = null)
    RecognitionDisclosure.HIDE_PLATES -> copy(
        recognizedLicensePlate = null,
        recognizedLicensePlateScore = null,
        description = null,
    )
    RecognitionDisclosure.HIDE_ALL -> copy(
        subLabel = null,
        recognizedLicensePlate = null,
        recognizedLicensePlateScore = null,
        description = null,
    )
}

private fun List<ReviewItem>.toReviewCounts(): ReviewCounts = ReviewCounts(
    reviewedAlerts = count { it.severity == app.opah.tv.data.model.ReviewSeverity.ALERT && it.hasBeenReviewed },
    reviewedDetections = count { it.severity == app.opah.tv.data.model.ReviewSeverity.DETECTION && it.hasBeenReviewed },
    totalAlerts = count { it.severity == app.opah.tv.data.model.ReviewSeverity.ALERT },
    totalDetections = count { it.severity == app.opah.tv.data.model.ReviewSeverity.DETECTION },
)
