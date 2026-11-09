package app.opah.tv.homeassistant

import app.opah.tv.data.realtime.CameraRoleEvidence
import app.opah.tv.data.realtime.CameraScopeState
import app.opah.tv.data.realtime.RealtimeAuthenticationState
import app.opah.tv.data.realtime.RealtimeProfileState
import app.opah.tv.data.realtime.RealtimeTransportState
import app.opah.tv.privacy.AuthorizationFreshness
import app.opah.tv.privacy.NotificationDisclosure
import app.opah.tv.privacy.PinScope
import app.opah.tv.privacy.PrivacySnapshot
import app.opah.tv.privacy.RecognitionDisclosure

/** Unclassified HA images cannot use a message's camera hint to evade local or server restrictions. */
internal fun homeAssistantRichPolicy(snapshot: PrivacySnapshot, hasFrigateProfile: Boolean?,
    transport: RealtimeTransportState): HomeAssistantRichPolicy {
    if (!snapshot.privacyPolicyAvailable || snapshot.epoch < 0 || snapshot.globalNotificationDisclosure == NotificationDisclosure.NEVER ||
        snapshot.guestMode && snapshot.guestNotificationDisclosure == NotificationDisclosure.NEVER
    ) return HomeAssistantRichPolicy(epoch = snapshot.epoch)
    if (snapshot.guestMode) return HomeAssistantRichPolicy(snapshot.epoch, HomeAssistantDisclosure.HIDDEN)
    val evidence = (transport.cameraScope as? CameraScopeState.Fresh)?.evidence
    val haOnly = hasFrigateProfile == false && transport.profileState == RealtimeProfileState.Unavailable
    val allCameras = haOnly || hasFrigateProfile == true && transport.profileState is RealtimeProfileState.Ready &&
        transport.authenticationState == RealtimeAuthenticationState.AUTHENTICATED && snapshot.authenticated &&
        snapshot.authorizationFreshness == AuthorizationFreshness.FRESH && evidence?.boundsValid == true &&
        evidence.role in setOf(CameraRoleEvidence.ADMINISTRATOR, CameraRoleEvidence.VIEWER) && evidence.configuredCameraIds != null &&
        evidence.allowedCameraIds == evidence.configuredCameraIds && snapshot.allowedCameraIds == evidence.allowedCameraIds
    val mediaScopes = setOf(PinScope.PRIVATE_CAMERAS, PinScope.ACTIVITY_HISTORY_SEARCH, PinScope.CLIPS_AND_INCIDENTS)
    val image = if (!allCameras || snapshot.privateCameraIds.isNotEmpty() || snapshot.protectedScopes.any(mediaScopes::contains) ||
        snapshot.cameraNotificationDisclosure.isNotEmpty() || snapshot.ownerRecognitionDisclosure != RecognitionDisclosure.SHOW_ALL
    ) HomeAssistantImageDisclosure.NONE else when (snapshot.globalNotificationDisclosure) {
        NotificationDisclosure.FULL_PREVIEW -> HomeAssistantImageDisclosure.FULL
        NotificationDisclosure.BLURRED_PREVIEW -> HomeAssistantImageDisclosure.BLURRED
        else -> HomeAssistantImageDisclosure.NONE
    }
    val protected = snapshot.pinConfigured && PinScope.HOME_ASSISTANT_ACTIONS in snapshot.protectedScopes
    val proof = snapshot.pinUnlockProof?.takeIf { it.privacyEpoch == snapshot.epoch }
    val authority = if (haOnly) "ha-only" else
        "${transport.generation}/${(transport.profileState as? RealtimeProfileState.Ready)?.profile?.id}/${transport.authorizationScopeEpoch}/${evidence?.revision}"
    return HomeAssistantRichPolicy(snapshot.epoch, HomeAssistantDisclosure.DETAILS, image, protected,
        protected && PinScope.HOME_ASSISTANT_ACTIONS in proof?.scopes.orEmpty(), authority)
}
