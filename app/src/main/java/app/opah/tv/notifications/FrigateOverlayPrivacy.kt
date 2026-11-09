package app.opah.tv.notifications

import app.opah.tv.awareness.AwarenessReview
import app.opah.tv.privacy.NotificationDisclosure
import app.opah.tv.privacy.PrivacyDecision
import app.opah.tv.privacy.PrivacyDecisionEngine
import app.opah.tv.privacy.PrivacyRequest
import app.opah.tv.privacy.PrivacySnapshot
import app.opah.tv.privacy.PrivacySurface
import app.opah.tv.privacy.PrivacyTarget

/** Rechecks delayed presentation without trusting an earlier notification's display content. */
internal fun frigateOverlayMayPresent(
    request: AlertNotificationPost,
    activeProfileKey: String?,
    synchronized: Boolean,
    configurationEnabled: Boolean,
    policyVersion: Long?,
    review: AwarenessReview?,
    privacy: PrivacySnapshot,
): Boolean {
    if (request.contentIntentKind != AlertContentIntentKind.EVENT || !synchronized || !configurationEnabled ||
        activeProfileKey != request.profileKey || request.policyVersion == null || request.policyVersion != policyVersion ||
        request.privacyEpoch == null || request.privacyEpoch != privacy.epoch ||
        review?.profileKey != request.profileKey || review.id != request.reviewId || review.reviewed == true
    ) return false
    val camera = review.camera ?: return false
    if (request.overlayCameraKey != NotificationIdentityFactory().eventIdentity(request.profileKey, camera).tag) return false
    val decision = PrivacyDecisionEngine().decide(
        PrivacyRequest(PrivacySurface.NOTIFICATION, PrivacyTarget.Review(review.id, camera, review.subLabels.isNotEmpty()), privacy.epoch),
        privacy,
    )
    val grant = when (decision) {
        is PrivacyDecision.Allow -> decision.grant
        is PrivacyDecision.AllowRedacted -> decision.grant
        else -> return false
    }
    val permitted = when (grant.notificationDisclosure) {
        NotificationDisclosure.FULL_PREVIEW -> NotificationPrivacy.FULL_PREVIEW
        NotificationDisclosure.BLURRED_PREVIEW -> NotificationPrivacy.BLURRED_PREVIEW
        NotificationDisclosure.TEXT_ONLY -> NotificationPrivacy.TEXT_ONLY
        NotificationDisclosure.NEVER, null -> return false
    }
    return request.model.privacy.restriction >= permitted.restriction
}
