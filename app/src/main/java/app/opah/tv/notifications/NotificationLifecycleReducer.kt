package app.opah.tv.notifications

enum class NotificationAlertBehavior {
    AUDIBLE_INITIAL,
    AUDIBLE_ESCALATION,
    SILENT_UPDATE,
}

enum class NotificationActionKind {
    OPEN,
    SNOOZE,
}

data class NotificationRenderModel(
    val title: String,
    val body: String,
    val notificationClass: NotificationClass,
    val privacy: NotificationPrivacy,
    val openTarget: AlertOpenTarget,
    val contentFingerprint: String,
    val thumbnailContentVersion: String? = null,
    val actions: Set<NotificationActionKind> = setOf(
        NotificationActionKind.OPEN,
        NotificationActionKind.SNOOZE,
    ),
) {
    init {
        require(title.isNotBlank() && title.length <= MAX_NOTIFICATION_TITLE_CHARS) {
            "Notification title is invalid"
        }
        require(body.isNotBlank() && body.length <= MAX_NOTIFICATION_BODY_CHARS) {
            "Notification body is invalid"
        }
        require(privacy != NotificationPrivacy.NEVER_NOTIFY) {
            "A hidden notification cannot be rendered"
        }
        require(contentFingerprint.isNotBlank() && contentFingerprint.length <= MAX_NOTIFICATION_FINGERPRINT_CHARS) {
            "Notification fingerprint is invalid"
        }
        require(thumbnailContentVersion == null || thumbnailContentVersion.length <= MAX_NOTIFICATION_FINGERPRINT_CHARS) {
            "Thumbnail content version is invalid"
        }
        require(
            privacy in setOf(NotificationPrivacy.FULL_PREVIEW, NotificationPrivacy.BLURRED_PREVIEW) ||
                thumbnailContentVersion == null
        ) { "Text-only notifications cannot contain a thumbnail" }
    }
}

sealed interface NotificationLifecycleCommand {
    val reason: AlertDecisionReason

    data class NoOp(override val reason: AlertDecisionReason) : NotificationLifecycleCommand

    data class Post(
        val renderModel: NotificationRenderModel,
        val alertBehavior: NotificationAlertBehavior,
        override val reason: AlertDecisionReason,
    ) : NotificationLifecycleCommand

    data class Cancel(override val reason: AlertDecisionReason) : NotificationLifecycleCommand

    data class CancelAndPurge(override val reason: AlertDecisionReason) : NotificationLifecycleCommand
}

object NotificationLifecycleReducer {
    fun reduce(
        input: AlertEvaluationInput,
        render: (AlertDecision) -> NotificationRenderModel,
    ): NotificationLifecycleCommand {
        val decision = AlertEvaluator.evaluate(input)
        if (decision.eligible) {
            val renderModel = render(decision)
            require(renderModel.notificationClass == decision.notificationClass)
            require(renderModel.privacy == decision.privacyLevel)
            require(renderModel.openTarget == decision.openTarget)
            require(renderModel.contentFingerprint == input.contentFingerprint)
            val behavior = when (decision.reason) {
                AlertDecisionReason.ELIGIBLE_INITIAL -> NotificationAlertBehavior.AUDIBLE_INITIAL
                AlertDecisionReason.ELIGIBLE_ESCALATION -> NotificationAlertBehavior.AUDIBLE_ESCALATION
                AlertDecisionReason.ELIGIBLE_SILENT_UPDATE -> NotificationAlertBehavior.SILENT_UPDATE
                else -> error("Eligible alert has an invalid lifecycle reason")
            }
            return NotificationLifecycleCommand.Post(renderModel, behavior, decision.reason)
        }

        val active = input.ledger?.notificationActive == true
        return when (decision.reason) {
            AlertDecisionReason.UNAUTHORIZED_CAMERA,
            AlertDecisionReason.PRIVATE_CAMERA,
            AlertDecisionReason.PIN_LOCKED,
            AlertDecisionReason.NEVER_NOTIFY,
            AlertDecisionReason.SHARED_PAUSE,
            AlertDecisionReason.REVIEWED,
            AlertDecisionReason.INCOMPLETE_EVENT,
            AlertDecisionReason.UNKNOWN_SEVERITY,
            AlertDecisionReason.UNKNOWN_LIFECYCLE,
            -> NotificationLifecycleCommand.CancelAndPurge(decision.reason)

            AlertDecisionReason.MODE_OFF,
            AlertDecisionReason.BELOW_SELECTED_SEVERITY,
            AlertDecisionReason.CUSTOM_FILTER_MISMATCH,
            AlertDecisionReason.OUTSIDE_SCHEDULE,
            AlertDecisionReason.SNOOZED,
            AlertDecisionReason.ALREADY_WATCHED,
            AlertDecisionReason.DISMISSED,
            AlertDecisionReason.OPENED,
            -> if (active) {
                NotificationLifecycleCommand.Cancel(decision.reason)
            } else {
                NotificationLifecycleCommand.NoOp(decision.reason)
            }

            AlertDecisionReason.DUPLICATE,
            AlertDecisionReason.ENDED_OUTSIDE_GRACE,
            -> NotificationLifecycleCommand.NoOp(decision.reason)

            AlertDecisionReason.ELIGIBLE_INITIAL,
            AlertDecisionReason.ELIGIBLE_SILENT_UPDATE,
            AlertDecisionReason.ELIGIBLE_ESCALATION,
            -> error("Ineligible alert has an eligible lifecycle reason")
        }
    }
}

private const val MAX_NOTIFICATION_TITLE_CHARS = 160
private const val MAX_NOTIFICATION_BODY_CHARS = 1_000
private const val MAX_NOTIFICATION_FINGERPRINT_CHARS = 256
