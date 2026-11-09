package app.opah.tv.notifications

import app.opah.tv.awareness.AwarenessReview
import app.opah.tv.awareness.AwarenessReviewLifecycle
import app.opah.tv.awareness.AwarenessReviewSeverity
import java.util.TimeZone
import java.util.Calendar

enum class AlertMode {
    OFF,
    IMPORTANT_ACTIVITY,
    ALL_DETECTED_ACTIVITY,
    CUSTOM,
}

enum class NotificationClass {
    ALERT,
    DETECTION,
}

enum class NotificationPrivacy(val restriction: Int) {
    FULL_PREVIEW(0),
    BLURRED_PREVIEW(1),
    TEXT_ONLY(2),
    NEVER_NOTIFY(3),
}

enum class AlertOpenTarget {
    LIVE_CAMERA,
    RECORDING,
    ACTIVITY,
    EXPIRED_ACTIVITY,
}

enum class AlertDecisionReason {
    ELIGIBLE_INITIAL,
    ELIGIBLE_SILENT_UPDATE,
    ELIGIBLE_ESCALATION,
    MODE_OFF,
    INCOMPLETE_EVENT,
    UNKNOWN_SEVERITY,
    UNKNOWN_LIFECYCLE,
    UNAUTHORIZED_CAMERA,
    PRIVATE_CAMERA,
    PIN_LOCKED,
    NEVER_NOTIFY,
    REVIEWED,
    BELOW_SELECTED_SEVERITY,
    CUSTOM_FILTER_MISMATCH,
    OUTSIDE_SCHEDULE,
    SNOOZED,
    ALREADY_WATCHED,
    DISMISSED,
    OPENED,
    DUPLICATE,
    ENDED_OUTSIDE_GRACE,
    SHARED_PAUSE,
}

data class AlertDecision(
    val eligible: Boolean,
    val notificationClass: NotificationClass?,
    val privacyLevel: NotificationPrivacy,
    val shouldRealert: Boolean,
    val openTarget: AlertOpenTarget?,
    val reason: AlertDecisionReason,
)

enum class AlertDayOfWeek {
    SUNDAY,
    MONDAY,
    TUESDAY,
    WEDNESDAY,
    THURSDAY,
    FRIDAY,
    SATURDAY,
}

data class AlertScheduleWindow(
    val days: Set<AlertDayOfWeek>,
    val startMinuteInclusive: Int,
    val endMinuteExclusive: Int,
) {
    init {
        require(days.isNotEmpty()) { "Schedule days must not be empty" }
        require(startMinuteInclusive in 0..1_439) { "Schedule start minute is invalid" }
        require(endMinuteExclusive in 0..1_439) { "Schedule end minute is invalid" }
    }
}

data class AlertPolicy(
    val mode: AlertMode = AlertMode.IMPORTANT_ACTIVITY,
    val significantMotionEnabled: Boolean = false,
    val cameraIds: Set<String> = emptySet(),
    val labels: Set<String> = emptySet(),
    val zones: Set<String> = emptySet(),
    val subLabels: Set<String> = emptySet(),
    val plateLabels: Set<String> = emptySet(),
    val minimumThreatLevel: Int? = null,
    val frigateModes: Set<String> = emptySet(),
    val customSeverities: Set<AwarenessReviewSeverity> = setOf(
        AwarenessReviewSeverity.ALERT,
    ),
    val schedules: List<AlertScheduleWindow> = emptyList(),
    val notificationPrivacy: NotificationPrivacy = NotificationPrivacy.TEXT_ONLY,
) {
    init {
        require(cameraIds.size <= MAX_FILTER_ITEMS) { "Too many camera filters" }
        require(labels.size <= MAX_FILTER_ITEMS) { "Too many label filters" }
        require(zones.size <= MAX_FILTER_ITEMS) { "Too many zone filters" }
        require(subLabels.size <= MAX_FILTER_ITEMS) { "Too many identity filters" }
        require(plateLabels.size <= MAX_FILTER_ITEMS) { "Too many plate filters" }
        require(frigateModes.size <= MAX_FILTER_ITEMS) { "Too many Mode filters" }
        require(schedules.size <= MAX_SCHEDULES) { "Too many alert schedules" }
        require(minimumThreatLevel == null || minimumThreatLevel >= 0) {
            "Minimum threat level must not be negative"
        }
        (cameraIds + labels + zones + subLabels + plateLabels + frigateModes).forEach {
            require(it.isNotBlank() && it.length <= MAX_FILTER_TEXT_CHARS) {
                "Alert filter text is invalid"
            }
        }
    }
}

enum class AlertSnoozeScope {
    ALL,
    CAMERA,
    VIEW,
}

data class AlertSnooze(
    val scope: AlertSnoozeScope,
    val cameraIds: Set<String> = emptySet(),
    val expiresAtEpochMillis: Long? = null,
    val untilModeChangesFrom: String? = null,
) {
    init {
        require(cameraIds.size <= MAX_FILTER_ITEMS) { "Too many snoozed cameras" }
        require(expiresAtEpochMillis == null || expiresAtEpochMillis >= 0L) {
            "Snooze expiration must not be negative"
        }
        require(untilModeChangesFrom == null || untilModeChangesFrom.length <= MAX_FILTER_TEXT_CHARS) {
            "Snooze Mode is too long"
        }
        require(expiresAtEpochMillis != null || untilModeChangesFrom != null) {
            "Snooze must have an expiration"
        }
        if (scope != AlertSnoozeScope.ALL) {
            require(cameraIds.isNotEmpty()) { "Scoped snooze requires a camera" }
        }
    }
}

data class AlertPrivacyContext(
    val guestModeActive: Boolean = false,
    val privateCameraIds: Set<String> = emptySet(),
    val pinAllowsNotification: Boolean = true,
    val globalPrivacy: NotificationPrivacy = NotificationPrivacy.TEXT_ONLY,
    val cameraPrivacy: NotificationPrivacy? = null,
    val guestPrivacy: NotificationPrivacy = NotificationPrivacy.TEXT_ONLY,
)

data class AlertPresentationContext(
    val viewedReviewId: String? = null,
    val monitorReviewId: String? = null,
    val allowSimultaneousSystemAlerts: Boolean = false,
)

data class AlertNotificationLedger(
    val highestPresentedSeverity: AwarenessReviewSeverity? = null,
    val highestAudibleSeverity: AwarenessReviewSeverity? = null,
    val contentFingerprint: String? = null,
    val lifecycle: AwarenessReviewLifecycle? = null,
    val dismissedAtEpochMillis: Long? = null,
    val severityAtDismissal: AwarenessReviewSeverity? = null,
    val notificationActive: Boolean = false,
)

data class AlertEvaluationInput(
    val review: AwarenessReview,
    val authorizedCameraIds: Set<String>,
    val policy: AlertPolicy,
    val currentFrigateMode: String? = null,
    val nowEpochMillis: Long,
    val timeZone: TimeZone,
    val snoozes: List<AlertSnooze> = emptyList(),
    val privacy: AlertPrivacyContext = AlertPrivacyContext(),
    val presentation: AlertPresentationContext = AlertPresentationContext(),
    val ledger: AlertNotificationLedger? = null,
    val recognizedPlateLabels: Set<String> = emptySet(),
    val recordingAvailable: Boolean = false,
    val activityAvailable: Boolean = true,
    val contentFingerprint: String? = null,
) {
    init {
        require(nowEpochMillis >= 0L) { "Evaluation time must not be negative" }
        require(authorizedCameraIds.size <= MAX_AUTHORIZED_CAMERAS) { "Too many authorized cameras" }
        require(snoozes.size <= MAX_SNOOZES) { "Too many snoozes" }
        require(recognizedPlateLabels.size <= MAX_FILTER_ITEMS) { "Too many recognized plates" }
        require(contentFingerprint == null || contentFingerprint.length <= MAX_FINGERPRINT_CHARS) {
            "Content fingerprint is too long"
        }
    }
}

internal const val MISSED_ALERT_GRACE_MILLIS = 5L * 60L * 1_000L
private const val MAX_FILTER_ITEMS = 256
private const val MAX_SCHEDULES = 64
private const val MAX_SNOOZES = 512
private const val MAX_AUTHORIZED_CAMERAS = 10_000
private const val MAX_FILTER_TEXT_CHARS = 256
private const val MAX_FINGERPRINT_CHARS = 256

internal fun nextLocalDayStartEpochMillis(nowEpochMillis: Long, timeZone: TimeZone): Long {
    require(nowEpochMillis >= 0L)
    return Calendar.getInstance(timeZone).apply {
        timeInMillis = nowEpochMillis
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis.coerceAtLeast(nowEpochMillis)
}
