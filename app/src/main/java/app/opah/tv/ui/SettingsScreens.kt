package app.opah.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.opah.tv.BuildConfig
import app.opah.tv.data.model.AppearanceMode
import app.opah.tv.data.model.CustomThemeColors
import app.opah.tv.data.model.StreamPreference
import app.opah.tv.data.model.AppSettings
import app.opah.tv.data.model.StartupTarget
import app.opah.tv.data.model.StartupTargetKind
import app.opah.tv.data.update.AppVersion
import app.opah.tv.privacy.PinScope
import app.opah.tv.notifications.AlertMode
import app.opah.tv.notifications.NotificationPrivacy
import app.opah.tv.notifications.android.TvAlertDeliveryStatus
import app.opah.tv.notifications.android.TvAlertOverlayHorizontalPosition
import app.opah.tv.notifications.android.TvAlertOverlayImageSize
import app.opah.tv.notifications.android.TvAlertOverlayVerticalPosition
import app.opah.tv.notifications.android.TV_ALERT_OVERLAY_MAX_DISPLAY_SECONDS
import app.opah.tv.notifications.android.TV_ALERT_OVERLAY_MIN_DISPLAY_SECONDS
import app.opah.tv.awareness.AwarenessReviewSeverity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class SettingsPage(
    val label: String,
    val description: String,
) {
    MAIN("Settings", ""),
    UPDATE("Update", "See whether a newer version of Opah is ready"),
    APPEARANCE("Appearance", "Choose how Opah looks"),
    PLAYBACK("Cameras and Playback", "Choose how cameras and video start and play"),
    PLAYBACK_TEST("Check camera compatibility", "Find a reliable way to play each camera on this TV"),
    CAMERA_DIAGNOSTICS("Diagnostic data", "Technical camera and playback details"),
    TV_ALERTS("TV alerts", "See important activity while another TV app is open"),
    PRIVACY("Privacy", "Protect selected cameras and app areas with a PIN"),
    STARTUP("Startup", "Choose what Opah opens first"),
    CONNECTION("Connection", "Manage this device's connection"),
    SYSTEM("Server", "See server performance and recording space"),
    ADVANCED("Device Info", "See TV and decoder details for troubleshooting"),
    ABOUT("About Opah", "Project, privacy, and legal information"),
}

internal val SETTINGS_PAGE_ORDER = listOf(
    SettingsPage.APPEARANCE,
    SettingsPage.PLAYBACK,
    SettingsPage.TV_ALERTS,
    SettingsPage.PRIVACY,
    SettingsPage.STARTUP,
    SettingsPage.SYSTEM,
    SettingsPage.UPDATE,
    SettingsPage.ADVANCED,
    SettingsPage.ABOUT,
)

@Composable
internal fun SettingsHubScreen(
    update: AppUpdateUiState,
    settings: AppSettings,
    privacy: PrivacyUiState,
    tvAlerts: TvAlertsUiState,
    restorePage: SettingsPage?,
    initialFocusRequester: FocusRequester,
    onFocusRestored: () -> Unit,
    onOpen: (SettingsPage) -> Unit,
) {
    FlatSettingsDetailColumn(
        title = "Settings",
        subtitle = "Make Opah work the way you want",
        onBack = null,
    ) {
        items(SETTINGS_PAGE_ORDER, key = SettingsPage::name) { page ->
            val focusKey = "settings:${page.name.lowercase()}"
            FlatSettingsRow(
                focusKey = focusKey,
                title = page.label,
                value = settingsPageValue(page, update, settings, privacy, tvAlerts),
                restoreFocusKey = restorePage?.let { "settings:${it.name.lowercase()}" },
                onFocusRestored = onFocusRestored,
                onClick = { onOpen(page) },
                accessibilityLabel = settingsPageAccessibilityLabel(page, update.updateAvailable),
                externalFocusRequester = initialFocusRequester.takeIf { page == SettingsPage.UPDATE },
            )
        }
    }
}

private fun settingsPageValue(
    page: SettingsPage,
    update: AppUpdateUiState,
    settings: AppSettings,
    privacy: PrivacyUiState,
    tvAlerts: TvAlertsUiState,
): String? = when (page) {
    SettingsPage.UPDATE -> when {
        update.updateAvailable -> "Ready"
        update.checking -> "Checking"
        update.errorMessage != null -> "Check failed"
        update.checkedOnce -> "Current"
        settings.automaticUpdateChecksEnabled -> "Will check"
        else -> "Manual"
    }
    SettingsPage.APPEARANCE -> settings.appearanceMode.name.lowercase().replaceFirstChar(Char::uppercase)
    SettingsPage.PLAYBACK -> settings.streamPreference.name.lowercase()
        .replace('_', ' ')
        .replaceFirstChar(Char::uppercase)
    SettingsPage.TV_ALERTS -> when {
        tvAlerts.loading -> "Loading"
        !tvAlerts.available -> "Unavailable"
        tvAlerts.enabled -> "On"
        else -> "Off"
    }
    SettingsPage.PRIVACY -> when {
        privacy.loading -> "Loading"
        !privacy.available -> "Unavailable"
        privacy.pinConfigured -> "PIN on"
        else -> "Off"
    }
    SettingsPage.STARTUP -> startupTargetLabel(settings.startupTarget, settings)
    SettingsPage.CONNECTION -> "Connected"
    SettingsPage.SYSTEM -> "Status"
    SettingsPage.ADVANCED,
    SettingsPage.ABOUT,
    SettingsPage.MAIN,
    SettingsPage.PLAYBACK_TEST,
    SettingsPage.CAMERA_DIAGNOSTICS,
    -> null
}

@Composable
internal fun TvAlertsSettingsScreen(
    state: Phase0UiState,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onMode: (AlertMode) -> Unit,
    onPrivacy: (NotificationPrivacy) -> Unit,
    onSignificantMotion: (Boolean) -> Unit,
    onCustomSeverity: (AwarenessReviewSeverity, Boolean) -> Unit,
    onAllCameras: () -> Unit,
    onCamera: (String, Boolean) -> Unit,
    onAnyLabel: () -> Unit,
    onLabel: (String, Boolean) -> Unit,
    onAnyZone: () -> Unit,
    onZone: (String, Boolean) -> Unit,
    onAnyIdentity: () -> Unit,
    onIdentity: (String, Boolean) -> Unit,
    onAnyPlate: () -> Unit,
    onPlate: (String, Boolean) -> Unit,
    onSchedule: (TvAlertSchedulePreset) -> Unit,
    onScheduleWindow: (Int, Int) -> Unit,
    onAnyMode: () -> Unit,
    onModeFilter: (String, Boolean) -> Unit,
    onMinimumThreatLevel: (Int?) -> Unit,
    onSnooze: (Int) -> Unit,
    onSnoozeUntilTomorrow: () -> Unit,
    onSnoozeUntilModeChanges: () -> Unit,
    onClearSnooze: () -> Unit,
    onTestAlert: (Boolean) -> Unit,
    onOverlayVerticalPosition: (TvAlertOverlayVerticalPosition) -> Unit,
    onOverlayHorizontalPosition: (TvAlertOverlayHorizontalPosition) -> Unit,
    onOverlayImageSize: (TvAlertOverlayImageSize) -> Unit,
    onOverlayDisplayDurationSeconds: (Int) -> Unit,
    onInteractiveFrigateAlerts: (Boolean) -> Unit,
    onOpenAndroidSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onLoadModes: () -> Unit,
    onRefreshDeliveryStatus: () -> Unit,
    onBack: () -> Unit,
    initialFocusRequester: FocusRequester,
) {
    LaunchedEffect(Unit) { onLoadModes() }
    val alerts = state.tvAlerts
    val onScreenAlertsEnabled = alerts.overlaySettings.displayDurationSeconds > 0
    var editingScheduleTime by rememberSaveable { mutableStateOf<AlertScheduleTime?>(null) }
    LaunchedEffect(alerts.available, alerts.enabled) {
        if (alerts.available && alerts.enabled) onRefreshDeliveryStatus()
    }
    val recentItems = (state.review.items + state.snapshot?.recentReviewItems.orEmpty())
        .distinctBy { it.id }
    val labelOptions = recentItems.flatMap { it.objects }
        .distinct().sorted().take(MAX_ALERT_FILTER_OPTIONS)
    val zoneOptions = recentItems.flatMap { it.zones }
        .distinct().sorted().take(MAX_ALERT_FILTER_OPTIONS)
    val identityOptions = recentItems.flatMap { it.subLabels }
        .distinct().sorted().take(MAX_ALERT_FILTER_OPTIONS)
    val plateOptions = recentItems.flatMap { item ->
        item.linkedEvents.mapNotNull { event -> event.recognizedLicensePlate }
    }.distinct().sorted().take(MAX_ALERT_FILTER_OPTIONS)
    SettingsDetailColumn(
        title = "TV alerts",
        subtitle = "See selected Frigate activity while another TV app is open",
        onBack = onBack,
    ) {
        SettingsSection(
            title = "How it works",
            subtitle = "Opah connects directly to Frigate without a cloud relay",
        ) {
            Text(
                "Opah shows a small alert card over the app you're watching and keeps an Android " +
                    "notification as a backup",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when {
            alerts.loading -> SettingsItem {
                ScreenMessage("Loading TV alert settings", isError = false)
            }
            !alerts.available -> SettingsItem {
                ScreenMessage(
                    alerts.errorMessage ?: "TV alerts are unavailable",
                    isError = true,
                )
            }
            else -> {
                SettingsSection(
                    "On-screen alerts",
                    when {
                        !onScreenAlertsEnabled -> "Off"
                        alerts.overlayPermissionGranted -> "Allowed by Android"
                        else -> "Permission required"
                    },
                ) {
                    SettingToggle(label = "Interactive Frigate alerts", value = alerts.overlaySettings.interactiveFrigateAlerts,
                        onChange = onInteractiveFrigateAlerts)
                    Text("Use the remote to act on alerts; remote control moves temporarily from your current app",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Choose how long each alert stays on screen",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AlertTimePartRow(
                        focusKey = "settings:tv-alerts:overlay-display-time",
                        label = "Notification display time",
                        value = tvAlertOverlayDisplayDurationLabel(
                            alerts.overlaySettings.displayDurationSeconds,
                        ),
                        onPrevious = {
                            onOverlayDisplayDurationSeconds(
                                adjustTvAlertOverlayDisplayDuration(
                                    alerts.overlaySettings.displayDurationSeconds,
                                    -1,
                                ),
                            )
                        },
                        onNext = {
                            onOverlayDisplayDurationSeconds(
                                adjustTvAlertOverlayDisplayDuration(
                                    alerts.overlaySettings.displayDurationSeconds,
                                    1,
                                ),
                            )
                        },
                        externalFocusRequester = initialFocusRequester.takeIf {
                            !onScreenAlertsEnabled && !alerts.overlayPermissionGranted
                        },
                    )
                    Text(
                        "At 0 seconds, Opah skips the on-screen alert but still keeps the Android notification",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (onScreenAlertsEnabled && alerts.overlayPermissionGranted) {
                        Text(
                            "Opah can show a compact alert without taking control of the remote",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (onScreenAlertsEnabled) {
                        Text(
                            "Android opens a list of apps. Choose Opah, then allow display over other apps",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SecondaryAction(
                            focusKey = "settings:tv-alerts:overlay",
                            label = "Open Android display settings",
                            onClick = onOpenOverlaySettings,
                            externalFocusRequester = initialFocusRequester.takeIf {
                                onScreenAlertsEnabled && !alerts.overlayPermissionGranted
                            },
                        )
                    }
                    Text("Location", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    ChoiceRow(
                        values = TvAlertOverlayVerticalPosition.entries,
                        selected = alerts.overlaySettings.verticalPosition,
                        label = ::tvAlertOverlayVerticalPositionLabel,
                        keyPrefix = "settings:tv-alerts:overlay-vertical",
                        onSelect = onOverlayVerticalPosition,
                    )
                    ChoiceRow(
                        values = TvAlertOverlayHorizontalPosition.entries,
                        selected = alerts.overlaySettings.horizontalPosition,
                        label = ::tvAlertOverlayHorizontalPositionLabel,
                        keyPrefix = "settings:tv-alerts:overlay-horizontal",
                        onSelect = onOverlayHorizontalPosition,
                    )
                    Text("Image size", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    ChoiceRow(
                        values = TvAlertOverlayImageSize.entries,
                        selected = alerts.overlaySettings.imageSize,
                        label = ::tvAlertOverlayImageSizeLabel,
                        keyPrefix = "settings:tv-alerts:overlay-image-size",
                        onSelect = onOverlayImageSize,
                    )
                    Text(
                        "Image size applies only when an alert includes a camera image",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                SettingsSection("Status", if (alerts.enabled) "TV alerts are on" else "TV alerts are off") {
                    SettingToggle(
                        label = "TV alerts",
                        value = alerts.enabled,
                        onChange = { enabled -> if (enabled) onEnable() else onDisable() },
                        externalFocusRequester = initialFocusRequester.takeIf {
                            alerts.overlayPermissionGranted
                        },
                    )
                }

                SettingsSection("Activity", "Important activity is the recommended starting point") {
                    AlertMode.entries.forEach { mode ->
                        ChoiceRow(
                            focusKey = "settings:tv-alerts:mode:${mode.name}",
                            title = alertModeLabel(mode),
                            selected = if (alerts.enabled) alerts.mode == mode else mode == AlertMode.OFF,
                            onClick = { onMode(mode) },
                        )
                    }
                }

                if (alerts.mode == AlertMode.ALL_DETECTED_ACTIVITY || alerts.mode == AlertMode.CUSTOM) {
                    SettingsSection(
                        "Significant motion",
                        "Off by default because ordinary motion can be frequent",
                    ) {
                        SettingToggle(
                            label = "Include significant motion",
                            value = alerts.significantMotionEnabled,
                            onChange = onSignificantMotion,
                        )
                    }
                }

                if (alerts.mode == AlertMode.CUSTOM) {
                    SettingsSection("Custom severity", "Choose the activity you want to see") {
                        SettingToggle(
                            label = "Important alerts",
                            value = AwarenessReviewSeverity.ALERT in alerts.customSeverities,
                            onChange = { onCustomSeverity(AwarenessReviewSeverity.ALERT, it) },
                        )
                        SettingToggle(
                            label = "Detections",
                            value = AwarenessReviewSeverity.DETECTION in alerts.customSeverities,
                            onChange = { onCustomSeverity(AwarenessReviewSeverity.DETECTION, it) },
                        )
                    }

                    state.snapshot?.cameras?.takeIf(List<*>::isNotEmpty)?.let { cameras ->
                        SettingsSection("Cameras", "All cameras are included until you choose specific ones") {
                            ChoiceRow(
                                focusKey = "settings:tv-alerts:camera:any",
                                title = "All cameras",
                                selected = alerts.cameraIds.isEmpty(),
                                onClick = onAllCameras,
                            )
                            cameras.forEach { camera ->
                                SettingToggle(
                                    label = camera.displayName,
                                    value = camera.name in alerts.cameraIds,
                                    onChange = { onCamera(camera.name, it) },
                                )
                            }
                        }
                    }

                    if (labelOptions.isNotEmpty()) {
                        SettingsSection("Object labels", "Uses literal labels already seen by this TV") {
                            ChoiceRow(
                                focusKey = "settings:tv-alerts:label:any",
                                title = "Any object",
                                selected = alerts.labels.isEmpty(),
                                onClick = onAnyLabel,
                            )
                            labelOptions.forEach { label ->
                                SettingToggle(
                                    label = label.replace('_', ' '),
                                    value = label in alerts.labels,
                                    onChange = { onLabel(label, it) },
                                )
                            }
                        }
                    }

                    if (zoneOptions.isNotEmpty()) {
                        SettingsSection("Zones", "Uses literal zones already seen by this TV") {
                            ChoiceRow(
                                focusKey = "settings:tv-alerts:zone:any",
                                title = "Any zone",
                                selected = alerts.zones.isEmpty(),
                                onClick = onAnyZone,
                            )
                            zoneOptions.forEach { zone ->
                                SettingToggle(
                                    label = zone.replace('_', ' '),
                                    value = zone in alerts.zones,
                                    onChange = { onZone(zone, it) },
                                )
                            }
                        }
                    }

                    if (identityOptions.isNotEmpty()) {
                        SettingsSection(
                            "Recognized identities",
                            "Uses literal identities already seen by this TV",
                        ) {
                            ChoiceRow(
                                focusKey = "settings:tv-alerts:identity:any",
                                title = "Any identity",
                                selected = alerts.subLabels.isEmpty(),
                                onClick = onAnyIdentity,
                            )
                            identityOptions.forEach { identity ->
                                SettingToggle(
                                    label = identity,
                                    value = identity in alerts.subLabels,
                                    onChange = { onIdentity(identity, it) },
                                )
                            }
                        }
                    }

                    if (plateOptions.isNotEmpty()) {
                        SettingsSection(
                            "Recognized license plates",
                            "Uses literal plates already seen by this TV",
                        ) {
                            ChoiceRow(
                                focusKey = "settings:tv-alerts:plate:any",
                                title = "Any plate",
                                selected = alerts.plateLabels.isEmpty(),
                                onClick = onAnyPlate,
                            )
                            plateOptions.forEach { plate ->
                                SettingToggle(
                                    label = plate,
                                    value = plate in alerts.plateLabels,
                                    onChange = { onPlate(plate, it) },
                                )
                            }
                        }
                    }

                    if (state.modes.modes.isNotEmpty()) {
                        SettingsSection("Frigate Mode", "Only notify in the Modes you choose") {
                            ChoiceRow(
                                focusKey = "settings:tv-alerts:mode-filter:any",
                                title = "Any Mode",
                                selected = alerts.frigateModes.isEmpty(),
                                onClick = onAnyMode,
                            )
                            state.modes.modes.forEach { mode ->
                                SettingToggle(
                                    label = mode.displayName,
                                    value = mode.name in alerts.frigateModes,
                                    onChange = { onModeFilter(mode.name, it) },
                                )
                            }
                        }
                    }

                    SettingsSection(
                        "AI threat level",
                        "Requires Frigate AI review summaries; missing levels do not notify",
                    ) {
                        listOf(
                            null to "Any threat level",
                            1 to "Suspicious or critical",
                            2 to "Critical only",
                        ).forEach { (level, label) ->
                            ChoiceRow(
                                focusKey = "settings:tv-alerts:threat:${level ?: 0}",
                                title = label,
                                selected = alerts.minimumThreatLevel == level,
                                onClick = { onMinimumThreatLevel(level) },
                            )
                        }
                    }
                }

                SettingsSection("When", "Uses this TV's local time") {
                    ChoiceRow(
                        focusKey = "settings:tv-alerts:schedule:any",
                        title = "Any time",
                        selected = alerts.schedulePreset == TvAlertSchedulePreset.ALWAYS,
                        onClick = { onSchedule(TvAlertSchedulePreset.ALWAYS) },
                    )
                    ChoiceRow(
                        focusKey = "settings:tv-alerts:schedule:custom",
                        title = "Only during chosen hours",
                        selected = alerts.schedulePreset == TvAlertSchedulePreset.CUSTOM,
                        onClick = { onSchedule(TvAlertSchedulePreset.CUSTOM) },
                    )
                    if (alerts.schedulePreset == TvAlertSchedulePreset.CUSTOM) {
                        AlertTimeChoiceRow(
                            focusKey = "settings:tv-alerts:schedule:start",
                            label = "Start alerts",
                            minuteOfDay = alerts.scheduleStartMinute,
                            onClick = { editingScheduleTime = AlertScheduleTime.START },
                        )
                        AlertTimeChoiceRow(
                            focusKey = "settings:tv-alerts:schedule:end",
                            label = "Stop alerts",
                            minuteOfDay = alerts.scheduleEndMinute,
                            onClick = { editingScheduleTime = AlertScheduleTime.STOP },
                        )
                        Text(
                            "Select Start or Stop to choose an exact time",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                SettingsSection("Privacy", "This follows private-camera and PIN-lock rules") {
                    ALERT_PRIVACY_CHOICES.forEach { privacy ->
                        ChoiceRow(
                            focusKey = "settings:tv-alerts:privacy:${privacy.name}",
                            title = alertPrivacyLabel(privacy),
                            selected = alerts.notificationPrivacy == privacy,
                            onClick = { onPrivacy(privacy) },
                        )
                    }
                }


                if (alerts.enabled) {
                    SettingsSection("Snooze", "Snoozing changes only Opah on this TV") {
                        ChoiceRow(
                            focusKey = "settings:tv-alerts:snooze-15",
                            title = "Snooze for 15 minutes",
                            selected = alerts.snoozeChoice == TvAlertSnoozeChoice.FIFTEEN_MINUTES,
                            onClick = { onSnooze(15) },
                        )
                        ChoiceRow(
                            focusKey = "settings:tv-alerts:snooze-60",
                            title = "Snooze for 1 hour",
                            selected = alerts.snoozeChoice == TvAlertSnoozeChoice.ONE_HOUR,
                            onClick = { onSnooze(60) },
                        )
                        ChoiceRow(
                            focusKey = "settings:tv-alerts:snooze-tomorrow",
                            title = "Snooze until tomorrow",
                            selected = alerts.snoozeChoice == TvAlertSnoozeChoice.UNTIL_TOMORROW,
                            onClick = onSnoozeUntilTomorrow,
                        )
                        if (state.modes.activeMode != null) {
                            ChoiceRow(
                                focusKey = "settings:tv-alerts:snooze-mode",
                                title = "Snooze until Frigate Mode changes",
                                selected = alerts.snoozeChoice == TvAlertSnoozeChoice.UNTIL_MODE_CHANGES,
                                onClick = onSnoozeUntilModeChanges,
                            )
                        }
                        if (alerts.snoozeCount > 0) {
                            SecondaryAction(
                                focusKey = "settings:tv-alerts:snooze-clear",
                                label = "End snooze",
                                enabled = !alerts.busy,
                                focusable = true,
                                onClick = onClearSnooze,
                            )
                        }
                    }

                }

                SettingsSection(
                    "Test",
                    "Opah returns to the TV Home screen, then shows the selected fictional alert",
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryAction(
                            focusKey = "settings:tv-alerts:test-without-image",
                            label = if (alerts.busy) "Sending…" else "Test without image",
                            enabled = !alerts.busy,
                            focusable = true,
                            onClick = { onTestAlert(false) },
                        )
                        SecondaryAction(
                            focusKey = "settings:tv-alerts:test-with-image",
                            label = if (alerts.busy) "Sending…" else "Test with image",
                            enabled = !alerts.busy,
                            focusable = true,
                            onClick = { onTestAlert(true) },
                        )
                    }
                }

                alerts.statusMessage?.let { message ->
                    SettingsItem { ScreenMessage(message, isError = false) }
                }
                if (alerts.enabled && alerts.deliveryStatus != TvAlertDeliveryStatus.AVAILABLE) {
                    SettingsItem {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ScreenMessage(
                                when (alerts.deliveryStatus) {
                                    TvAlertDeliveryStatus.NOTIFICATION_PERMISSION_REQUIRED ->
                                        "Android no longer allows Opah notifications"
                                    TvAlertDeliveryStatus.PARTIALLY_BLOCKED ->
                                        "One TV alert channel is blocked in Android settings"
                                    TvAlertDeliveryStatus.BLOCKED ->
                                        "TV alerts are blocked in Android settings"
                                    TvAlertDeliveryStatus.AVAILABLE -> ""
                                },
                                isError = true,
                            )
                            SecondaryAction(
                                focusKey = "settings:tv-alerts:delivery-android",
                                label = "Open Android notification settings",
                                onClick = onOpenAndroidSettings,
                            )
                        }
                    }
                }
                alerts.errorMessage?.let { message ->
                    SettingsItem {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ScreenMessage(message, isError = true)
                            if (message.contains("on-screen alerts", ignoreCase = true) ||
                                message.contains("display settings", ignoreCase = true)
                            ) {
                                SecondaryAction(
                                    focusKey = "settings:tv-alerts:overlay-error",
                                    label = "Open Android display settings",
                                    onClick = onOpenOverlaySettings,
                                )
                            } else if (message.contains("Android settings", ignoreCase = true) ||
                                message.contains("Allow notifications", ignoreCase = true)
                            ) {
                                SecondaryAction(
                                    focusKey = "settings:tv-alerts:android",
                                    label = "Open Android notification settings",
                                    onClick = onOpenAndroidSettings,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editingScheduleTime?.let { choice ->
        val currentMinute = if (choice == AlertScheduleTime.START) {
            alerts.scheduleStartMinute
        } else {
            alerts.scheduleEndMinute
        }
        val otherMinute = if (choice == AlertScheduleTime.START) {
            alerts.scheduleEndMinute
        } else {
            alerts.scheduleStartMinute
        }
        AlertTimePickerDialog(
            label = if (choice == AlertScheduleTime.START) "Start alerts" else "Stop alerts",
            initialMinuteOfDay = currentMinute,
            unavailableMinuteOfDay = otherMinute,
            onDismiss = { editingScheduleTime = null },
            onSave = { minute ->
                editingScheduleTime = null
                if (choice == AlertScheduleTime.START) {
                    onScheduleWindow(minute, alerts.scheduleEndMinute)
                } else {
                    onScheduleWindow(alerts.scheduleStartMinute, minute)
                }
            },
        )
    }
}

@Composable
private fun AlertTimeChoiceRow(
    focusKey: String,
    label: String,
    minuteOfDay: Int,
    onClick: () -> Unit,
) {
    FocusCard(
        focusKey = focusKey,
        restoreFocusKey = null,
        onFocusRestored = {},
        onClick = onClick,
        accessibilityLabel = "$label, ${formatAlertMinute(minuteOfDay)}. Select to change",
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f))
            Text(formatAlertMinute(minuteOfDay), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Change", style = MaterialTheme.typography.labelMedium)
        }
    }
}

internal fun tvAlertOverlayVerticalPositionLabel(position: TvAlertOverlayVerticalPosition): String =
    when (position) {
        TvAlertOverlayVerticalPosition.TOP -> "Top"
        TvAlertOverlayVerticalPosition.BOTTOM -> "Bottom"
    }

internal fun tvAlertOverlayHorizontalPositionLabel(position: TvAlertOverlayHorizontalPosition): String =
    when (position) {
        TvAlertOverlayHorizontalPosition.LEFT -> "Left"
        TvAlertOverlayHorizontalPosition.CENTER -> "Center"
        TvAlertOverlayHorizontalPosition.RIGHT -> "Right"
    }

internal fun tvAlertOverlayImageSizeLabel(size: TvAlertOverlayImageSize): String = when (size) {
    TvAlertOverlayImageSize.SMALL -> "Small"
    TvAlertOverlayImageSize.MEDIUM -> "Medium"
    TvAlertOverlayImageSize.LARGE -> "Large"
}

internal fun adjustTvAlertOverlayDisplayDuration(seconds: Int, delta: Int): Int =
    (seconds.toLong() + delta.toLong()).coerceIn(
        TV_ALERT_OVERLAY_MIN_DISPLAY_SECONDS.toLong(),
        TV_ALERT_OVERLAY_MAX_DISPLAY_SECONDS.toLong(),
    ).toInt()

internal fun tvAlertOverlayDisplayDurationLabel(seconds: Int): String = when (seconds) {
    0 -> "0 seconds (off)"
    1 -> "1 second"
    else -> "$seconds seconds"
}

private enum class AlertScheduleTime { START, STOP }

@Composable
private fun AlertTimePickerDialog(
    label: String,
    initialMinuteOfDay: Int,
    unavailableMinuteOfDay: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    var draftMinute by rememberSaveable(label, initialMinuteOfDay) {
        mutableIntStateOf(Math.floorMod(initialMinuteOfDay, MINUTES_PER_DAY))
    }
    val hourFocusRequester = remember { FocusRequester() }
    val valid = draftMinute != Math.floorMod(unavailableMinuteOfDay, MINUTES_PER_DAY)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 440.dp, max = 620.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                formatAlertMinute(draftMinute),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Use left and right. Hold a button to move quickly",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AlertTimePartRow(
                focusKey = "settings:tv-alerts:time:hour",
                label = "Hour",
                value = alertHourLabel(draftMinute),
                onPrevious = { draftMinute = adjustAlertMinute(draftMinute, -60) },
                onNext = { draftMinute = adjustAlertMinute(draftMinute, 60) },
                externalFocusRequester = hourFocusRequester,
            )
            AlertTimePartRow(
                focusKey = "settings:tv-alerts:time:minute",
                label = "Minute",
                value = "%02d".format(Math.floorMod(draftMinute, 60)),
                onPrevious = { draftMinute = adjustAlertMinute(draftMinute, -1) },
                onNext = { draftMinute = adjustAlertMinute(draftMinute, 1) },
            )
            AlertTimePartRow(
                focusKey = "settings:tv-alerts:time:period",
                label = "AM or PM",
                value = if (Math.floorMod(draftMinute, MINUTES_PER_DAY) < 12 * 60) "AM" else "PM",
                onPrevious = { draftMinute = adjustAlertMinute(draftMinute, -12 * 60) },
                onNext = { draftMinute = adjustAlertMinute(draftMinute, 12 * 60) },
            )
            if (!valid) {
                Text("Start and stop times must be different", color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { onSave(draftMinute) }, enabled = valid) { Text("Save time") }
            }
        }
    }
    LaunchedEffect(Unit) {
        repeat(3) {
            androidx.compose.runtime.withFrameNanos { }
            if (hourFocusRequester.requestFocus()) return@LaunchedEffect
        }
    }
}

@Composable
private fun AlertTimePartRow(
    focusKey: String,
    label: String,
    value: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    externalFocusRequester: FocusRequester? = null,
) {
    FocusCard(
        focusKey = focusKey,
        restoreFocusKey = null,
        onFocusRestored = {},
        onClick = onNext,
        accessibilityLabel = "$label, $value. Use left and right to change",
        externalFocusRequester = externalFocusRequester,
        modifier = Modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        onPrevious()
                        true
                    }
                    Key.DirectionRight -> {
                        onNext()
                        true
                    }
                    else -> false
                }
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f))
            Text("◀  $value  ▶", fontWeight = FontWeight.Bold)
        }
    }
}

internal fun adjustAlertMinute(minuteOfDay: Int, delta: Int): Int =
    Math.floorMod(minuteOfDay + delta, MINUTES_PER_DAY)

internal fun formatAlertMinute(minuteOfDay: Int): String {
    val normalized = Math.floorMod(minuteOfDay, MINUTES_PER_DAY)
    val hour24 = normalized / 60
    val minute = normalized % 60
    val hour12 = when (val value = hour24 % 12) {
        0 -> 12
        else -> value
    }
    return "%d:%02d %s".format(hour12, minute, if (hour24 < 12) "AM" else "PM")
}

internal fun alertHourLabel(minuteOfDay: Int): String {
    val hour24 = Math.floorMod(minuteOfDay, MINUTES_PER_DAY) / 60
    return when (val hour12 = hour24 % 12) {
        0 -> "12"
        else -> hour12.toString()
    }
}

private const val MINUTES_PER_DAY = 24 * 60

private fun alertModeLabel(mode: AlertMode): String = when (mode) {
    AlertMode.OFF -> "Off"
    AlertMode.IMPORTANT_ACTIVITY -> "Important activity"
    AlertMode.ALL_DETECTED_ACTIVITY -> "All detected activity"
    AlertMode.CUSTOM -> "Custom"
}

private fun alertPrivacyLabel(privacy: NotificationPrivacy): String = when (privacy) {
    NotificationPrivacy.FULL_PREVIEW -> "Full preview"
    NotificationPrivacy.BLURRED_PREVIEW -> "Blurred preview"
    NotificationPrivacy.TEXT_ONLY -> "Text only"
    NotificationPrivacy.NEVER_NOTIFY -> "Hidden"
}

private val ALERT_PRIVACY_CHOICES = listOf(
    NotificationPrivacy.FULL_PREVIEW,
    NotificationPrivacy.BLURRED_PREVIEW,
    NotificationPrivacy.TEXT_ONLY,
)

private const val MAX_ALERT_FILTER_OPTIONS = 24

@Composable
internal fun PrivacySettingsScreen(
    state: Phase0UiState,
    onSetupPin: (CharArray, CharArray, Set<PinScope>) -> Unit,
    onUnlockPinChoices: (CharArray) -> Unit,
    onPinScope: (PinScope, Boolean) -> Unit,
    onRemovePin: (CharArray) -> Unit,
    onCameraPrivate: (String, Boolean) -> Unit,
    onLockNow: () -> Unit,
    onBack: () -> Unit,
    initialFocusRequester: FocusRequester,
) {
    val privacy = state.privacy
    var unlockSelectionsVisible by rememberSaveable { mutableStateOf(false) }
    var removePinVisible by rememberSaveable { mutableStateOf(false) }
    val selectionsUnlocked = privacy.pinSelectionsUnlocked
    LaunchedEffect(selectionsUnlocked, privacy.pinConfigured) {
        if (selectionsUnlocked) unlockSelectionsVisible = false
        if (!privacy.pinConfigured) removePinVisible = false
    }
    if (!privacy.loading && privacy.available && !privacy.pinConfigured) {
        PrivacyPinSetupScreen(
            privacy = privacy,
            onSetupPin = onSetupPin,
            onBack = onBack,
            initialFocusRequester = initialFocusRequester,
        )
        return
    }
    FlatSettingsDetailColumn(
        title = "Privacy",
        subtitle = "Choose what people can see on this TV",
        onBack = onBack,
    ) {
        when {
            privacy.loading -> item(key = "privacy:loading") {
                ScreenMessage("Loading privacy controls", isError = false)
            }
            !privacy.available -> item(key = "privacy:unavailable") {
                ScreenMessage(
                    privacy.errorMessage ?: "Privacy controls are unavailable",
                    isError = true,
                )
            }
            else -> {
                item(key = "privacy:heading:selections") {
                    FlatSettingsSectionHeader(
                        title = "PIN selections",
                        subtitle = if (selectionsUnlocked) {
                            "Choose which areas need the PIN"
                        } else {
                            "Unlock selections before making changes"
                        },
                    )
                }
                item(key = "privacy:selection-access") {
                    FlatSettingsRow(
                        focusKey = "settings:privacy:selection-access",
                        title = privacySelectionAccessLabel(selectionsUnlocked),
                        value = if (selectionsUnlocked) "Unlocked" else "Locked",
                        enabled = !privacy.busy && !privacy.pinRecordCorrupt,
                        focusable = !privacy.pinRecordCorrupt,
                        externalFocusRequester = initialFocusRequester,
                        onClick = {
                            if (selectionsUnlocked) onLockNow() else unlockSelectionsVisible = true
                        },
                    )
                }
                items(
                    items = PIN_OPTIONAL_SCOPES,
                    key = { scope -> "privacy:scope:${scope.name}" },
                ) { scope ->
                    FlatSettingsToggleRow(
                        focusKey = "settings:privacy:scope:${scope.name.lowercase()}",
                        label = pinScopeLabel(scope),
                        value = scope in privacy.protectedScopes,
                        enabled = selectionsUnlocked && !privacy.busy,
                        focusable = selectionsUnlocked,
                        onChange = { enabled -> onPinScope(scope, enabled) },
                    )
                }

                item(key = "privacy:heading:pin") {
                    FlatSettingsSectionHeader(
                        title = "PIN",
                        subtitle = if (privacy.pinRecordCorrupt) {
                            "PIN protection needs recovery"
                        } else {
                            "Manage the local PIN stored on this TV"
                        },
                    )
                }
                item(key = "privacy:remove-pin") {
                    FlatSettingsRow(
                        focusKey = "settings:privacy:remove-pin",
                        title = "Remove PIN",
                        value = null,
                        enabled = !privacy.busy && !privacy.pinRecordCorrupt,
                        focusable = !privacy.pinRecordCorrupt,
                        onClick = { removePinVisible = true },
                    )
                }

                state.snapshot?.cameras?.takeIf(List<*>::isNotEmpty)?.let { cameras ->
                    item(key = "privacy:heading:cameras") {
                        FlatSettingsSectionHeader(
                            title = "Private cameras",
                            subtitle = "Choose cameras that need the PIN when Private cameras is locked",
                        )
                    }
                    items(
                        items = cameras,
                        key = { camera -> "privacy:camera:${camera.name}" },
                    ) { camera ->
                        FlatSettingsToggleRow(
                            focusKey = "settings:privacy:camera:${camera.name}",
                            label = camera.displayName,
                            value = camera.name in privacy.privateCameraIds,
                            enabled = !privacy.busy,
                            focusable = true,
                            onChange = { private -> onCameraPrivate(camera.name, private) },
                        )
                    }
                }

                privacy.statusMessage?.let { message ->
                    item(key = "privacy:status") { ScreenMessage(message, isError = false) }
                }
                privacy.errorMessage?.let { message ->
                    item(key = "privacy:error") { ScreenMessage(message, isError = true) }
                }
            }
        }
    }
    if (unlockSelectionsVisible) {
        PrivacyPinDialog(
            title = "Unlock Selections",
            explanation = "Enter the current PIN to change the selections on this page",
            actionLabel = "Unlock Selections",
            busy = privacy.busy,
            message = privacy.errorMessage,
            onSubmit = onUnlockPinChoices,
            onDismiss = { if (!privacy.busy) unlockSelectionsVisible = false },
        )
    }
    if (removePinVisible) {
        PrivacyPinDialog(
            title = "Remove PIN",
            explanation = "Every PIN lock will be removed from this TV",
            actionLabel = "Remove PIN",
            busy = privacy.busy,
            message = privacy.errorMessage,
            onSubmit = onRemovePin,
            onDismiss = { if (!privacy.busy) removePinVisible = false },
        )
    }
}

@Composable
private fun PrivacyPinSetupScreen(
    privacy: PrivacyUiState,
    onSetupPin: (CharArray, CharArray, Set<PinScope>) -> Unit,
    onBack: () -> Unit,
    initialFocusRequester: FocusRequester,
) {
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    val confirmationFocusRequester = remember { FocusRequester() }
    var scopes by remember {
        mutableStateOf(emptySet<PinScope>())
    }
    val guidance = pinSetupGuidance(pin, confirmation)
    val readyToCreate = pin.length in 4..8 && confirmation.length in 4..8 && pin == confirmation
    DisposableEffect(Unit) {
        onDispose {
            pin = ""
            confirmation = ""
            confirming = false
        }
    }
    LaunchedEffect(confirming) {
        if (!confirming) return@LaunchedEffect
        repeat(PRIVACY_GATE_FOCUS_ATTEMPTS) {
            androidx.compose.runtime.withFrameNanos { }
            if (confirmationFocusRequester.requestFocus()) return@LaunchedEffect
        }
    }
    FlatSettingsDetailColumn(
        title = "Privacy",
        subtitle = "Choose what people can see on this TV",
        onBack = onBack,
    ) {
        item(key = "privacy:setup:heading:pin") {
            FlatSettingsSectionHeader(
                title = "Create a local PIN",
                subtitle = "Enter the same 4–8 digit PIN twice",
            )
        }
        item(key = "privacy:setup:pad:${if (confirming) "confirm" else "new"}") {
            TvPinPad(
                label = if (confirming) "Confirm PIN" else "New PIN",
                value = if (confirming) confirmation else pin,
                onValueChange = { value ->
                    if (confirming) confirmation = value else pin = value
                },
                enabled = !privacy.busy,
                focusKeyPrefix = if (confirming) "privacy:confirm-pin" else "privacy:new-pin",
                externalFocusRequester = if (confirming) {
                    confirmationFocusRequester
                } else {
                    initialFocusRequester
                },
            )
        }
        item(key = "privacy:setup:guidance") {
            Text(
                guidance,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                color = if (readyToCreate) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item(key = "privacy:setup:heading:selections") {
            FlatSettingsSectionHeader(
                title = "Optional PIN selections",
                subtitle = "Choose the cameras and app areas that should need this PIN",
            )
        }
        items(
            items = PIN_OPTIONAL_SCOPES,
            key = { scope -> "privacy:setup:scope:${scope.name}" },
        ) { scope ->
            FlatSettingsToggleRow(
                focusKey = "settings:privacy:setup:scope:${scope.name.lowercase()}",
                label = pinScopeLabel(scope),
                value = scope in scopes,
                enabled = !privacy.busy,
                onChange = { enabled -> scopes = if (enabled) scopes + scope else scopes - scope },
            )
        }
        if (!confirming) {
            item(key = "privacy:setup:continue") {
                FlatSettingsRow(
                    focusKey = "settings:privacy:confirm-pin",
                    title = "Continue",
                    value = null,
                    enabled = !privacy.busy && pin.length in 4..8,
                    onClick = {
                        confirmation = ""
                        confirming = true
                    },
                )
            }
        } else {
            item(key = "privacy:setup:start-over") {
                FlatSettingsRow(
                    focusKey = "settings:privacy:start-pin-over",
                    title = "Start over",
                    value = null,
                    enabled = !privacy.busy,
                    onClick = {
                        pin = ""
                        confirmation = ""
                        confirming = false
                    },
                )
            }
            item(key = "privacy:setup:save") {
                FlatSettingsRow(
                    focusKey = "settings:privacy:save-pin",
                    title = if (privacy.busy) "Saving…" else "Save PIN",
                    value = null,
                    enabled = !privacy.busy && readyToCreate,
                    onClick = {
                        val submittedPin = pin.toCharArray()
                        val submittedConfirmation = confirmation.toCharArray()
                        pin = ""
                        confirmation = ""
                        confirming = false
                        onSetupPin(submittedPin, submittedConfirmation, scopes)
                    },
                )
            }
        }
        privacy.statusMessage?.let { message ->
            item(key = "privacy:setup:status") { ScreenMessage(message, isError = false) }
        }
        privacy.errorMessage?.let { message ->
            item(key = "privacy:setup:error") { ScreenMessage(message, isError = true) }
        }
    }
}

internal fun pinSetupGuidance(pin: String, confirmation: String): String = when {
    pin.isEmpty() && confirmation.isEmpty() -> "Enter a PIN, then enter it again to confirm"
    pin.length !in 4..8 -> "The PIN must contain 4 to 8 numbers"
    confirmation.isEmpty() -> "Enter the PIN again in Confirm PIN"
    confirmation.length !in 4..8 -> "The confirmation must contain 4 to 8 numbers"
    pin != confirmation -> "The two PINs do not match"
    else -> "Ready to create"
}

internal fun privacySelectionAccessLabel(unlocked: Boolean): String =
    if (unlocked) "Lock Selections" else "Unlock Selections"

@Composable
internal fun PrivacyPinGateScreen(
    pinConfigured: Boolean,
    busy: Boolean,
    message: String?,
    protectedContentLabel: String = "Settings",
    initialFocusRequester: FocusRequester? = null,
    onSubmit: (CharArray) -> Unit,
    onBack: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    val gateFocusRequester = initialFocusRequester ?: remember { FocusRequester() }
    DisposableEffect(Unit) { onDispose { pin = "" } }
    LaunchedEffect(pinConfigured, busy) {
        if (busy) return@LaunchedEffect
        repeat(PRIVACY_GATE_FOCUS_ATTEMPTS) {
            androidx.compose.runtime.withFrameNanos { }
            if (runCatching { gateFocusRequester.requestFocus() }.getOrDefault(false)) {
                return@LaunchedEffect
            }
        }
    }
    SettingsDetailColumn(
        title = "PIN required",
        subtitle = "Enter the PIN to open $protectedContentLabel",
        onBack = onBack,
    ) {
        SettingsItem {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (pinConfigured) {
                    TvPinPad(
                        label = "PIN",
                        value = pin,
                        onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                        enabled = !busy,
                        focusKeyPrefix = "privacy:gate-pin",
                        externalFocusRequester = gateFocusRequester,
                    )
                    PrimaryAction(
                        focusKey = "privacy:gate:unlock",
                        label = if (busy) "Checking…" else "Unlock $protectedContentLabel",
                        enabled = !busy && pin.length in 4..8,
                        onClick = {
                            val submitted = pin.toCharArray()
                            pin = ""
                            onSubmit(submitted)
                        },
                    )
                }
                message?.let { ScreenMessage(it, isError = true) }
            }
        }
    }
}

@Composable
private fun TvPinPad(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    focusKeyPrefix: String,
    externalFocusRequester: FocusRequester? = null,
) {
    Column(
        modifier = Modifier.widthIn(max = 188.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(
            pinPadDisplay(value),
            modifier = Modifier.heightIn(min = 24.dp),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
        )
        PIN_PAD_KEYS.chunked(3).forEach { rowKeys ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rowKeys.forEach { key ->
                    FocusCard(
                        focusKey = "$focusKeyPrefix:$key",
                        restoreFocusKey = null,
                        onFocusRestored = {},
                        onClick = {
                            when (key) {
                                PIN_PAD_CLEAR -> onValueChange("")
                                PIN_PAD_DELETE -> onValueChange(removePinDigit(value))
                                else -> onValueChange(appendPinDigit(value, key.single()))
                            }
                        },
                        enabled = enabled && when (key) {
                            PIN_PAD_CLEAR, PIN_PAD_DELETE -> value.isNotEmpty()
                            else -> value.length < MAX_PIN_DIGITS
                        },
                        accessibilityLabel = when (key) {
                            PIN_PAD_DELETE -> "Delete last number"
                            else -> key
                        },
                        externalFocusRequester = externalFocusRequester.takeIf { key == "1" },
                        modifier = Modifier.size(width = 58.dp, height = 38.dp),
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                if (key == PIN_PAD_DELETE) "⌫" else key,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
        Text(
            "Use the remote to enter 4–8 numbers",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PrivacyPinDialog(
    title: String,
    explanation: String,
    actionLabel: String,
    busy: Boolean,
    message: String?,
    onSubmit: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    val firstKeyFocusRequester = remember { FocusRequester() }
    DisposableEffect(Unit) { onDispose { pin = "" } }
    LaunchedEffect(busy) {
        if (busy) return@LaunchedEffect
        repeat(PRIVACY_GATE_FOCUS_ATTEMPTS) {
            androidx.compose.runtime.withFrameNanos { }
            if (firstKeyFocusRequester.requestFocus()) return@LaunchedEffect
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        DialogSurface(modifier = Modifier.widthIn(max = 520.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(explanation, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TvPinPad(
                    label = "Current PIN",
                    value = pin,
                    onValueChange = { pin = it },
                    enabled = !busy,
                    focusKeyPrefix = "privacy:dialog:$actionLabel",
                    externalFocusRequester = firstKeyFocusRequester,
                )
                message?.let { ScreenMessage(it, isError = true) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryAction(
                        focusKey = "privacy:dialog:cancel:$actionLabel",
                        label = "Cancel",
                        enabled = !busy,
                        onClick = onDismiss,
                    )
                    PrimaryAction(
                        focusKey = "privacy:dialog:submit:$actionLabel",
                        label = if (busy) "Checking…" else actionLabel,
                        enabled = !busy && pin.length in 4..8,
                        onClick = {
                            val submitted = pin.toCharArray()
                            pin = ""
                            onSubmit(submitted)
                        },
                    )
                }
            }
        }
    }
}

internal fun appendPinDigit(value: String, digit: Char): String =
    if (digit.isDigit() && value.length < MAX_PIN_DIGITS) value + digit else value

internal fun removePinDigit(value: String): String = value.dropLast(1)

internal fun pinPadDisplay(value: String): String =
    if (value.isEmpty()) "○ ○ ○ ○" else "● ".repeat(value.length).trimEnd()

private fun pinScopeLabel(scope: PinScope): String = when (scope) {
    PinScope.SETTINGS -> "Settings and connection"
    PinScope.ADMINISTRATIVE_ACTIONS -> "Administrator actions"
    PinScope.DESTRUCTIVE_ACTIONS -> "Delete and destructive actions"
    PinScope.CLIPS_AND_INCIDENTS -> "Clips and Incidents"
    PinScope.ACTIVITY_HISTORY_SEARCH -> "Activity, History, and Search"
    PinScope.PRIVATE_CAMERAS -> "Private cameras"
    PinScope.HOME_ASSISTANT_ACTIONS -> "Home Assistant actions"
    PinScope.EXIT_GUEST_MODE -> "Legacy protection"
}

private val PIN_OPTIONAL_SCOPES = listOf(
    PinScope.SETTINGS,
    PinScope.ADMINISTRATIVE_ACTIONS,
    PinScope.DESTRUCTIVE_ACTIONS,
    PinScope.CLIPS_AND_INCIDENTS,
    PinScope.ACTIVITY_HISTORY_SEARCH,
    PinScope.PRIVATE_CAMERAS,
    PinScope.HOME_ASSISTANT_ACTIONS,
)

private const val MAX_PIN_DIGITS = 8
private const val PIN_PAD_CLEAR = "Clear"
private const val PIN_PAD_DELETE = "Delete"
private val PIN_PAD_KEYS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", PIN_PAD_CLEAR, "0", PIN_PAD_DELETE)

private const val PRIVACY_GATE_FOCUS_ATTEMPTS = 8

internal fun startupTargetLabel(target: StartupTarget, settings: AppSettings): String = when (target.kind) {
    StartupTargetKind.HOME -> "Cameras"
    StartupTargetKind.LAST_VIEWED -> "Last viewed"
    StartupTargetKind.BIRDSEYE -> "All Cameras"
    StartupTargetKind.CAMERA -> target.value ?: "Home"
    StartupTargetKind.SAVED_VIEW -> settings.savedCameraViews
        .firstOrNull { it.id == target.value }
        ?.name
        ?: "Home"
    StartupTargetKind.CAMERA_GROUP -> target.value ?: "Home"
}

@Composable
internal fun StartupSettingsScreen(
    state: Phase0UiState,
    onTarget: (StartupTarget) -> Unit,
    onBack: () -> Unit,
    initialFocusRequester: FocusRequester,
) {
    val settings = state.settings
    val snapshot = state.snapshot
    SettingsDetailColumn(
        title = "Startup",
        subtitle = "Choose what Opah opens after connecting",
        onBack = onBack,
    ) {
        SettingsSection("Start with", "Camera shortcuts and deep links still take priority") {
            ChoiceRow(
                focusKey = "settings:startup:home",
                title = "Home",
                selected = settings.startupTarget.kind == StartupTargetKind.HOME,
                onClick = { onTarget(StartupTarget()) },
                externalFocusRequester = initialFocusRequester,
            )
            ChoiceRow(
                focusKey = "settings:startup:last",
                title = "Last viewed camera or view",
                selected = settings.startupTarget.kind == StartupTargetKind.LAST_VIEWED,
                onClick = { onTarget(StartupTarget(StartupTargetKind.LAST_VIEWED)) },
            )
            if (snapshot?.birdseye?.playable == true) {
                ChoiceRow(
                    focusKey = "settings:startup:birdseye",
                    title = "All Cameras",
                    selected = settings.startupTarget.kind == StartupTargetKind.BIRDSEYE,
                    onClick = { onTarget(StartupTarget(StartupTargetKind.BIRDSEYE)) },
                )
            }
        }
        if (snapshot?.cameras?.isNotEmpty() == true) {
            SettingsSection("Camera", "Open one camera immediately") {
                snapshot.cameras.forEach { camera ->
                    val target = StartupTarget(StartupTargetKind.CAMERA, camera.name)
                    ChoiceRow(
                        focusKey = "settings:startup:camera:${camera.name}",
                        title = camera.displayName,
                        selected = settings.startupTarget == target,
                        onClick = { onTarget(target) },
                    )
                }
            }
        }
        val savedViews = settings.savedCameraViews
        val groups = snapshot?.cameraGroups.orEmpty()
        if (savedViews.isNotEmpty() || groups.isNotEmpty()) {
            SettingsSection("View", "Open a saved view or Frigate camera group") {
                savedViews.forEach { view ->
                    val target = StartupTarget(StartupTargetKind.SAVED_VIEW, view.id)
                    ChoiceRow(
                        focusKey = "settings:startup:view:${view.id}",
                        title = view.name,
                        selected = settings.startupTarget == target,
                        onClick = { onTarget(target) },
                    )
                }
                groups.forEach { group ->
                    val target = StartupTarget(StartupTargetKind.CAMERA_GROUP, group.name)
                    ChoiceRow(
                        focusKey = "settings:startup:group:${group.name}",
                        title = group.displayName,
                        selected = settings.startupTarget == target,
                        onClick = { onTarget(target) },
                    )
                }
            }
        }
    }
}

internal fun settingsPageAccessibilityLabel(page: SettingsPage, updateAvailable: Boolean): String =
    if (page == SettingsPage.UPDATE && updateAvailable) {
        "Update, update available"
    } else {
        page.label
    }

internal enum class UpdateInitialFocusTarget { INSTALL, DOWNLOAD, CHECK, AUTOMATIC }

internal fun updateInitialFocusTarget(update: AppUpdateUiState): UpdateInitialFocusTarget = when {
    update.preparedApkPath != null -> UpdateInitialFocusTarget.INSTALL
    update.updateAvailable && !update.downloading -> UpdateInitialFocusTarget.DOWNLOAD
    !update.checking && !update.downloading -> UpdateInitialFocusTarget.CHECK
    else -> UpdateInitialFocusTarget.AUTOMATIC
}

@Composable
internal fun UpdateSettingsScreen(
    update: AppUpdateUiState,
    automaticChecksEnabled: Boolean,
    onBack: () -> Unit,
    onCheckAgain: () -> Unit,
    onAutomaticChecks: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onInstall: (String) -> Unit,
    initialFocusRequester: FocusRequester,
) {
    val initialFocusTarget = updateInitialFocusTarget(update)
    SettingsDetailColumn(
        title = "Update",
        subtitle = "Keep Opah up to date",
        onBack = onBack,
    ) {
        SettingsSection(
            title = appUpdateStatusTitle(update),
            subtitle = "Nothing downloads or installs automatically",
        ) {
            ReadOnlyValue("Installed", installedAppVersionLabel())
            update.latestVersion?.let { ReadOnlyValue("Available", it) }
            update.statusMessage?.let { ScreenMessage(it, isError = false) }
            update.errorMessage?.let { ScreenMessage(it, isError = true) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (update.preparedApkPath != null) {
                    PrimaryAction(
                        focusKey = "settings:update:install",
                        label = "Install now",
                        onClick = { onInstall(update.preparedApkPath) },
                        externalFocusRequester = initialFocusRequester.takeIf {
                            initialFocusTarget == UpdateInitialFocusTarget.INSTALL
                        },
                    )
                } else if (update.updateAvailable) {
                    PrimaryAction(
                        focusKey = "settings:update:download",
                        label = if (update.downloading) "Downloading…" else "Download update",
                        onClick = onDownload,
                        enabled = !update.downloading,
                        focusable = true,
                        externalFocusRequester = initialFocusRequester.takeIf {
                            initialFocusTarget == UpdateInitialFocusTarget.DOWNLOAD
                        },
                    )
                }
                SecondaryAction(
                    focusKey = "settings:update:check",
                    label = if (update.checking) "Checking…" else "Check again",
                    onClick = onCheckAgain,
                    enabled = !update.checking,
                    focusable = true,
                    externalFocusRequester = initialFocusRequester.takeIf {
                        initialFocusTarget == UpdateInitialFocusTarget.CHECK
                    },
                )
            }
        }
        if (update.preparedApkPath != null) {
            SettingsItem {
                Text(
                    "Android will ask you to confirm the update. If prompted, allow Opah to install apps, return here, and choose Install now again",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (automaticChecksEnabled && !update.checkedOnce && !update.checking) {
            SettingsItem {
                Text(
                    "Opah will check quietly after it finishes starting",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SettingsSection(
            title = "Automatic checks",
            subtitle = "Choose whether Opah checks after it starts",
        ) {
            SettingToggle(
                label = "Check automatically",
                value = automaticChecksEnabled,
                onChange = onAutomaticChecks,
                externalFocusRequester = initialFocusRequester.takeIf {
                    initialFocusTarget == UpdateInitialFocusTarget.AUTOMATIC
                },
            )
        }
        releaseNotesForDisplay(update.releaseNotes)?.let { notes ->
            SettingsSection(
                title = "What’s new",
                subtitle = update.latestVersion?.let { "Opah $it" },
            ) {
                ScrollableReleaseNotes(notes)
            }
        }
    }
}

@Composable
private fun ScrollableReleaseNotes(notes: String) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val scrollStep = with(LocalDensity.current) { 180.dp.roundToPx() }
    FocusCard(
        focusKey = "settings:update:release-notes",
        restoreFocusKey = null,
        onFocusRestored = {},
        onClick = {
            if (scrollState.value < scrollState.maxValue) {
                scope.launch {
                    scrollState.scrollTo((scrollState.value + scrollStep).coerceAtMost(scrollState.maxValue))
                }
            }
        },
        accessibilityLabel = "Release notes. Use up and down to read",
        modifier = Modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val target = when (event.key) {
                    Key.DirectionDown -> (scrollState.value + scrollStep).coerceAtMost(scrollState.maxValue)
                    Key.DirectionUp -> (scrollState.value - scrollStep).coerceAtLeast(0)
                    else -> return@onPreviewKeyEvent false
                }
                if (target == scrollState.value) return@onPreviewKeyEvent false
                scope.launch { scrollState.scrollTo(target) }
                true
            },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                notes,
                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(scrollState),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (scrollState.maxValue > 0) {
                Text(
                    "Use up and down to read",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

internal fun releaseNotesForDisplay(rawNotes: String): String? {
    val markdownLink = Regex("""!?\[([^]]+)]\([^)]+\)""")
    val simpleFormatting = Regex("""[*_`]""")
    val htmlTag = Regex("""<[^>]+>""")
    val cleanedLines = rawNotes
        .lineSequence()
        .map { line ->
            line.trim()
                .replace(Regex("""^#{1,6}\s+"""), "")
                .replace(Regex("""^[-+*]\s+"""), "• ")
                .replace(markdownLink, "$1")
                .replace(simpleFormatting, "")
                .replace(htmlTag, "")
                .trim()
        }
        .fold(mutableListOf<String>()) { lines, line ->
            if (line.isNotEmpty() || lines.lastOrNull()?.isNotEmpty() == true) lines += line
            lines
        }
        .dropLastWhile(String::isEmpty)
        .take(MAX_RELEASE_NOTE_LINES)
        .joinToString("\n")
        .take(MAX_RELEASE_NOTE_DISPLAY_LENGTH)
        .trim()
    return cleanedLines.takeIf(String::isNotEmpty)
}

private const val MAX_RELEASE_NOTE_LINES = 160
private const val MAX_RELEASE_NOTE_DISPLAY_LENGTH = 16_000

internal fun installedAppVersionLabel(versionName: String = BuildConfig.VERSION_NAME): String =
    AppVersion.parse(versionName)?.canonical ?: versionName

internal fun appUpdateStatusTitle(update: AppUpdateUiState): String = when {
    update.updateAvailable -> "Update available"
    update.checking -> "Checking for an update…"
    update.errorMessage != null -> "Couldn't check for an update"
    update.checkedOnce -> "Opah is up to date"
    else -> "Check for an update"
}

@Composable
internal fun AppearanceSettingsScreen(
    state: Phase0UiState,
    onAppearance: (AppearanceMode) -> Unit,
    onCustomTheme: (CustomThemeColors) -> Unit,
    onReducedMotion: (Boolean) -> Unit,
    onHighContrast: (Boolean) -> Unit,
    onBack: () -> Unit,
    initialFocusRequester: FocusRequester,
) {
    SettingsDetailColumn(
        title = "Appearance",
        subtitle = "Choose how Opah looks",
        onBack = onBack,
    ) {
        SettingsSection("Theme", "Use the device setting, or choose light or dark") {
            ChoiceRow(
                values = AppearanceMode.entries,
                selected = state.settings.appearanceMode,
                label = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                keyPrefix = "settings:appearance",
                onSelect = onAppearance,
                externalFocusRequester = initialFocusRequester,
            )
        }
        if (state.settings.appearanceMode == AppearanceMode.CUSTOM) {
            SettingsSection(
                "Color style",
                "Start with a style, then use left and right to make it yours",
            ) {
                ThemeColorEditor(state.settings.customThemeColors, onCustomTheme)
            }
        }
        SettingsSection("Accessibility", "Adjust motion and visual contrast") {
            SettingToggle("Reduce motion", state.settings.reducedMotion, onReducedMotion)
            SettingToggle("High contrast", state.settings.highContrast, onHighContrast)
        }
    }
}

@Composable
internal fun PlaybackSettingsScreen(
    state: Phase0UiState,
    onStreamPreference: (StreamPreference) -> Unit,
    onCompatibilityMode: (Boolean) -> Unit,
    onStartMuted: (Boolean) -> Unit,
    onPlaybackDetails: (Boolean) -> Unit,
    onAutoMarkReviewed: (Boolean) -> Unit,
    onTestCamera: () -> Unit,
    onDiagnosticData: () -> Unit,
    onBack: () -> Unit,
    initialFocusRequester: FocusRequester,
) {
    SettingsDetailColumn(
        title = "Cameras and Playback",
        subtitle = "Choose how cameras and video start and play",
        onBack = onBack,
    ) {
        SettingsSection("Default live video", "Automatic works best for most people") {
            ChoiceRow(
                values = StreamPreference.entries,
                selected = state.settings.streamPreference,
                label = {
                    when (it) {
                        StreamPreference.AUTOMATIC -> "Automatic"
                        StreamPreference.MAIN -> "Best quality"
                        StreamPreference.LOW_BANDWIDTH -> "Low bandwidth"
                    }
                },
                keyPrefix = "settings:stream",
                onSelect = onStreamPreference,
                externalFocusRequester = initialFocusRequester,
            )
        }
        SettingsSection("When video opens", "These choices apply the next time video opens") {
            SettingToggle("Prefer RTP over TCP", state.settings.preferRtpTcp, onCompatibilityMode)
            SettingToggle("Start live video muted", state.settings.startLiveMuted, onStartMuted)
            SettingToggle("Show playback details", state.settings.diagnosticsEnabled, onPlaybackDetails)
            SettingToggle(
                "Mark activity reviewed after playback",
                state.settings.autoMarkReviewedAfterPlayback,
                onAutoMarkReviewed,
            )
        }
        SettingsSection(
            "Camera compatibility",
            "If a camera has trouble, Opah can try safe playback choices and remember what works on this TV",
        ) {
            SecondaryAction(
                focusKey = "settings:playback:test-camera",
                label = "Check camera compatibility",
                onClick = onTestCamera,
            )
        }
        SettingsSection(
            "Troubleshooting",
            "Technical camera and decoder information for advanced support",
        ) {
            SecondaryAction(
                focusKey = "settings:playback:diagnostic-data",
                label = "Diagnostic data",
                onClick = onDiagnosticData,
            )
        }
    }
}

@Composable
internal fun PlaybackTestCameraScreen(
    state: Phase0UiState,
    onTestCamera: (String, PlaybackCompatibilityTestMode) -> Unit,
    onResetCamera: (String) -> Unit,
    onLoadChoices: () -> Unit,
    onBack: () -> Unit,
    initialFocusRequester: FocusRequester,
) {
    LaunchedEffect(Unit) { onLoadChoices() }
    val cameras = state.snapshot?.cameras.orEmpty()
    var pendingCameraName by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedModeName by rememberSaveable {
        mutableStateOf(PlaybackCompatibilityTestMode.AUTOMATIC.name)
    }
    FlatSettingsDetailColumn(
        title = "Check camera compatibility",
        subtitle = "Choose a camera to see or change the playback choice Opah uses on this TV",
        onBack = onBack,
    ) {
        if (cameras.isEmpty()) {
            item(key = "settings:playback-test:empty") {
                SettingsSection(
                    title = "Cameras",
                    isFocusable = true,
                    initialFocusRequester = initialFocusRequester,
                ) {
                    Text(
                        "No permitted cameras are available",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            state.playbackCompatibilityMessage?.let { message ->
                item {
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            itemsIndexed(
                items = cameras,
                key = { _, camera -> "settings:playback-test:${camera.name}" },
            ) { index, camera ->
                SettingsSection(camera.displayName) {
                    val savedChoice = state.playbackCompatibilityChoices[camera.name]
                    Text(
                        if (state.playbackCompatibilityMessage?.startsWith(camera.displayName) == true) {
                            state.playbackCompatibilityMessage
                        } else if (savedChoice != null) {
                            "Saved choice: $savedChoice\nUsed automatically whenever this camera opens"
                        } else {
                            "No saved choice yet. Opah chooses automatically when this camera opens"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PrimaryAction(
                            focusKey = "settings:playback-test:${camera.name}",
                            label = "Check playback",
                            onClick = {
                                selectedModeName = PlaybackCompatibilityTestMode.AUTOMATIC.name
                                pendingCameraName = camera.name
                            },
                            externalFocusRequester = initialFocusRequester.takeIf { index == 0 },
                        )
                        if (savedChoice != null) {
                            SecondaryAction(
                                focusKey = "settings:playback-reset:${camera.name}",
                                label = "Reset saved choice",
                                onClick = { onResetCamera(camera.name) },
                            )
                        }
                    }
                }
            }
        }
    }
    pendingCameraName?.let { cameraName ->
        val camera = cameras.firstOrNull { it.name == cameraName }
        if (camera != null) {
            PlaybackCompatibilityDialog(
                cameraName = camera.displayName,
                selectedMode = PlaybackCompatibilityTestMode.valueOf(selectedModeName),
                onSelectedMode = { selectedModeName = it.name },
                onDismiss = { pendingCameraName = null },
                onStart = {
                    val mode = PlaybackCompatibilityTestMode.valueOf(selectedModeName)
                    pendingCameraName = null
                    onTestCamera(cameraName, mode)
                },
            )
        }
    }
}

@Composable
private fun PlaybackCompatibilityDialog(
    cameraName: String,
    selectedMode: PlaybackCompatibilityTestMode,
    onSelectedMode: (PlaybackCompatibilityTestMode) -> Unit,
    onDismiss: () -> Unit,
    onStart: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier
                .widthIn(min = 520.dp, max = 720.dp)
                .heightIn(max = 660.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp)),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Check $cameraName",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Opah will briefly open live video and may restart it while trying safe choices. " +
                        "The check closes automatically and saves the first reliable choice",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "That saved choice is used everywhere this camera opens on this TV",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            items(PlaybackCompatibilityTestMode.entries, key = { it.name }) { mode ->
                val label = compatibilityTestModeLabel(mode)
                val description = compatibilityTestModeDescription(mode)
                val technicalDetails = compatibilityTestModeTechnicalDetails(mode)
                FocusCard(
                    focusKey = "settings:playback-test-mode:${mode.name}",
                    restoreFocusKey = null,
                    onFocusRestored = {},
                    selected = selectedMode == mode,
                    onClick = { onSelectedMode(mode) },
                    modifier = Modifier.fillMaxWidth(),
                    accessibilityLabel = listOf(
                        label,
                        description,
                        technicalDetails,
                        if (selectedMode == mode) "selected" else "not selected",
                    ).joinToString(", "),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (selectedMode == mode) {
                                Text(
                                    "Selected",
                                    color = LocalOpahSemanticColors.current.selection,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        Text(
                            description,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Technical details",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            technicalDetails,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SecondaryAction(
                        focusKey = "settings:playback-test-mode:cancel",
                        label = "Cancel",
                        onClick = onDismiss,
                    )
                    PrimaryAction(
                        focusKey = "settings:playback-test-mode:start",
                        label = "Start check",
                        onClick = onStart,
                    )
                }
            }
        }
    }
}

internal fun compatibilityTestModeLabel(mode: PlaybackCompatibilityTestMode): String = when (mode) {
    PlaybackCompatibilityTestMode.AUTOMATIC -> "Automatic (recommended)"
    PlaybackCompatibilityTestMode.STANDARD_CONNECTION -> "Standard connection"
    PlaybackCompatibilityTestMode.RELIABLE_CONNECTION -> "Reliable connection"
    PlaybackCompatibilityTestMode.VIDEO_ONLY -> "Video only"
    PlaybackCompatibilityTestMode.RELIABLE_VIDEO_ONLY -> "Reliable video only"
}

internal fun compatibilityTestModeDescription(mode: PlaybackCompatibilityTestMode): String = when (mode) {
    PlaybackCompatibilityTestMode.AUTOMATIC ->
        "Uses your live-video settings and saves the first reliable choice"
    PlaybackCompatibilityTestMode.STANDARD_CONNECTION ->
        "Tries the TV's standard connection first"
    PlaybackCompatibilityTestMode.RELIABLE_CONNECTION ->
        "Tries the more reliable network connection first"
    PlaybackCompatibilityTestMode.VIDEO_ONLY ->
        "Skips camera sound"
    PlaybackCompatibilityTestMode.RELIABLE_VIDEO_ONLY ->
        "Uses the reliable connection and skips camera sound"
}

internal fun compatibilityTestModeTechnicalDetails(mode: PlaybackCompatibilityTestMode): String = when (mode) {
    PlaybackCompatibilityTestMode.AUTOMATIC ->
        "Saved choice first • otherwise follows Prefer RTP over TCP • audio enabled • decoder fallback available"
    PlaybackCompatibilityTestMode.STANDARD_CONNECTION ->
        "Default RTSP transport first • audio enabled • hardware, platform, then software decoder"
    PlaybackCompatibilityTestMode.RELIABLE_CONNECTION ->
        "RTP over TCP first • audio enabled • hardware, platform, then software decoder"
    PlaybackCompatibilityTestMode.VIDEO_ONLY ->
        "Transport follows Prefer RTP over TCP • audio track disabled • decoder fallback available"
    PlaybackCompatibilityTestMode.RELIABLE_VIDEO_ONLY ->
        "RTP over TCP first • audio track disabled • decoder fallback available"
}

@Composable
internal fun ConnectionSettingsScreen(
    state: Phase0UiState,
    onUpdateLiveRoute: (String, String) -> Unit,
    onSignOut: () -> Unit,
    onForgetServer: () -> Unit,
    onBack: () -> Unit,
    initialFocusRequester: FocusRequester,
) {
    val profile = state.activeProfile ?: return
    var liveHost by rememberSaveable(profile) { mutableStateOf(profile.rtspHostOverride.orEmpty()) }
    var livePort by rememberSaveable(profile) { mutableStateOf(profile.rtspPort.toString()) }
    val inputFocusCoordinator = remember { TvInputFocusCoordinator() }
    val saveFocusRequester = remember { FocusRequester() }
    SettingsDetailColumn(
        title = "Connection",
        subtitle = "Manage this device's connection",
        onBack = onBack,
    ) {
        SettingsSection("Account", "The server and account Opah uses on this device") {
            ReadOnlyValue("Server", profile.apiBaseUrl)
            ReadOnlyValue("Account", profile.username)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryAction(
                    focusKey = "settings:connection:sign-out",
                    label = "Sign out",
                    onClick = onSignOut,
                    externalFocusRequester = initialFocusRequester,
                )
                SecondaryAction(
                    focusKey = "settings:connection:forget",
                    label = "Forget connection",
                    onClick = onForgetServer,
                )
            }
        }
        SettingsSection("Live video route", "Only change this if live video cannot connect") {
            ProductionTvInput(
                label = "Live video host (optional)",
                value = liveHost,
                onValueChange = { liveHost = it },
                placeholder = "Use the same server",
                enabled = true,
                inputKey = "settings:live-host",
                focusCoordinator = inputFocusCoordinator,
                nextInputKey = "settings:live-port",
            )
            ProductionTvInput(
                label = "Live video port",
                value = livePort,
                onValueChange = { livePort = it.filter(Char::isDigit) },
                placeholder = "8554",
                enabled = true,
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
                inputKey = "settings:live-port",
                focusCoordinator = inputFocusCoordinator,
                previousInputKey = "settings:live-host",
                nextFocusRequester = saveFocusRequester,
            )
            Button(
                onClick = { onUpdateLiveRoute(liveHost, livePort) },
                modifier = Modifier.focusRequester(saveFocusRequester),
            ) { Text("Save live video route") }
        }
    }
}

@Composable
internal fun FlatSettingsDetailColumn(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)?,
    content: LazyListScope.() -> Unit,
) {
    onBack?.let { BackHandler(onBack = it) }
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(
                start = 24.dp,
                top = 18.dp,
                end = 24.dp,
                bottom = 10.dp,
            ),
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    }
}

@Composable
internal fun FlatSettingsSectionHeader(
    title: String,
    subtitle: String? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(
            start = 10.dp,
            top = 14.dp,
            end = 10.dp,
            bottom = 6.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        subtitle?.let { value ->
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun FlatSettingsRow(
    focusKey: String,
    title: String,
    value: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
    focusable: Boolean = enabled,
    selected: Boolean = false,
    restoreFocusKey: String? = null,
    onFocusRestored: () -> Unit = {},
    externalFocusRequester: FocusRequester? = null,
    onFocusStateChanged: (Boolean) -> Unit = {},
    accessibilityLabel: String = listOfNotNull(title, value).joinToString(", "),
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsRow(
            focusKey = focusKey,
            title = title,
            value = value,
            onClick = onClick,
            enabled = enabled,
            focusable = focusable,
            selected = selected,
            restoreFocusKey = restoreFocusKey,
            onFocusRestored = onFocusRestored,
            externalFocusRequester = externalFocusRequester,
            onFocusStateChanged = onFocusStateChanged,
            accessibilityLabel = accessibilityLabel,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)),
        )
    }
}

@Composable
internal fun FlatSettingsToggleRow(
    focusKey: String,
    label: String,
    value: Boolean,
    enabled: Boolean,
    focusable: Boolean = enabled,
    onChange: (Boolean) -> Unit,
) {
    FlatSettingsRow(
        focusKey = focusKey,
        title = label,
        value = if (value) "On" else "Off",
        enabled = enabled,
        focusable = focusable,
        selected = value,
        onClick = { onChange(!value) },
    )
}

@Composable
private fun SettingsDetailColumn(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    content: SettingsDetailScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    val entries = SettingsDetailScope().apply(content).entries
    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(
                start = 24.dp,
                top = 18.dp,
                end = 24.dp,
                bottom = 10.dp,
            ),
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(entries, key = SettingsDetailEntry::key) { entry -> entry.content() }
        }
    }
}

private class SettingsDetailScope {
    val entries = mutableListOf<SettingsDetailEntry>()
    private val sectionOccurrences = mutableMapOf<String, Int>()
    private var itemCount = 0

    fun SettingsSection(
        title: String,
        subtitle: String? = null,
        content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
    ) {
        val occurrence = sectionOccurrences.getOrDefault(title, 0)
        sectionOccurrences[title] = occurrence + 1
        entries += SettingsDetailEntry("section:$title:$occurrence:heading") {
            FlatSettingsSectionHeader(title = title, subtitle = subtitle)
        }
        entries += SettingsDetailEntry("section:$title:$occurrence:content") {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                content = content,
            )
        }
    }

    fun SettingsItem(content: @Composable () -> Unit) {
        entries += SettingsDetailEntry("item:${itemCount++}", content)
    }
}

private data class SettingsDetailEntry(
    val key: String,
    val content: @Composable () -> Unit,
)

@Composable
internal fun SafeBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    initialFocusRequester: FocusRequester? = null,
    label: String = "Back",
) {
    var focused by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(focused) {
        armed = false
        if (focused) {
            delay(400)
            armed = true
        }
    }
    Button(
        onClick = { if (armed) onClick() },
        modifier = modifier
            .then(
                if (initialFocusRequester == null) Modifier else Modifier.focusRequester(initialFocusRequester),
            )
            .onFocusChanged { focused = it.isFocused },
    ) { Text(label) }
}
