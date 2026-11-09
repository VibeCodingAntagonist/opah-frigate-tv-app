package app.opah.tv.notifications

import app.opah.tv.awareness.AwarenessReview
import app.opah.tv.awareness.AwarenessReviewLifecycle
import app.opah.tv.privacy.*

/** A notification's permission to show a picture is never a permission to play its recording. */
internal fun frigateInlinePreviewAllowed(
    request: AlertNotificationPost,
    review: AwarenessReview?,
    privacy: PrivacySnapshot,
    interactive: Boolean,
    previews: Boolean,
): Boolean {
    if (!interactive || !previews || privacy.guestMode || request.model.privacy != NotificationPrivacy.FULL_PREVIEW ||
        request.privacyEpoch == null || request.privacyEpoch != privacy.epoch || review?.profileKey != request.profileKey ||
        review.id != request.reviewId || review.lifecycle != AwarenessReviewLifecycle.ENDED || review.reviewed == true) return false
    val camera = review.camera ?: return false
    val notification = PrivacyDecisionEngine().decide(PrivacyRequest(PrivacySurface.NOTIFICATION,
        PrivacyTarget.Review(review.id, camera, review.subLabels.isNotEmpty()), privacy.epoch), privacy)
    val disclosure = when(notification) {
        is PrivacyDecision.Allow -> notification.grant.notificationDisclosure
        is PrivacyDecision.AllowRedacted -> notification.grant.notificationDisclosure
        else -> return false
    }
    if (disclosure != NotificationDisclosure.FULL_PREVIEW) return false
    val decision = PrivacyDecisionEngine().decide(PrivacyRequest(PrivacySurface.ACTIVITY,
        PrivacyTarget.Review(review.id, camera, review.subLabels.isNotEmpty()), privacy.epoch), privacy)
    val grant = when(decision) {
        is PrivacyDecision.Allow -> decision.grant
        is PrivacyDecision.AllowRedacted -> decision.grant
        else -> return false
    }
    return grant.visibleCameraIds == setOf(camera)
}
