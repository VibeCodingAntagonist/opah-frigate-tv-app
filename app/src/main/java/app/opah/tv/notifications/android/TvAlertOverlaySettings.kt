package app.opah.tv.notifications.android

import android.content.Context
import androidx.core.content.edit

enum class TvAlertOverlayVerticalPosition {
    TOP,
    BOTTOM,
}

enum class TvAlertOverlayHorizontalPosition {
    LEFT,
    CENTER,
    RIGHT,
}

enum class TvAlertOverlayImageSize {
    SMALL,
    MEDIUM,
    LARGE,
}

internal const val TV_ALERT_OVERLAY_MIN_DISPLAY_SECONDS = 0
internal const val TV_ALERT_OVERLAY_MAX_DISPLAY_SECONDS = 60
internal const val TV_ALERT_OVERLAY_DEFAULT_DISPLAY_SECONDS = 10

data class TvAlertOverlaySettings(
    val verticalPosition: TvAlertOverlayVerticalPosition = TvAlertOverlayVerticalPosition.TOP,
    val horizontalPosition: TvAlertOverlayHorizontalPosition = TvAlertOverlayHorizontalPosition.CENTER,
    val imageSize: TvAlertOverlayImageSize = TvAlertOverlayImageSize.MEDIUM,
    val displayDurationSeconds: Int = TV_ALERT_OVERLAY_DEFAULT_DISPLAY_SECONDS,
    val interactiveFrigateAlerts: Boolean = false,
    val inlineRecordingPreviews: Boolean = false,
) {
    init {
        require(
            displayDurationSeconds in
                TV_ALERT_OVERLAY_MIN_DISPLAY_SECONDS..TV_ALERT_OVERLAY_MAX_DISPLAY_SECONDS,
        )
    }
}

internal fun tvAlertOverlayDisplayDurationMillis(displayDurationSeconds: Int): Long {
    require(
        displayDurationSeconds in
            TV_ALERT_OVERLAY_MIN_DISPLAY_SECONDS..TV_ALERT_OVERLAY_MAX_DISPLAY_SECONDS,
    )
    return displayDurationSeconds * 1_000L
}

internal class TvAlertOverlaySettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCE_FILE,
        Context.MODE_PRIVATE,
    )

    fun load(): TvAlertOverlaySettings = TvAlertOverlaySettings(
        verticalPosition = preferences.getString(VERTICAL_POSITION, null)
            .enumValueOrDefault(TvAlertOverlayVerticalPosition.TOP),
        horizontalPosition = preferences.getString(HORIZONTAL_POSITION, null)
            .enumValueOrDefault(TvAlertOverlayHorizontalPosition.CENTER),
        imageSize = preferences.getString(IMAGE_SIZE, null)
            .enumValueOrDefault(TvAlertOverlayImageSize.MEDIUM),
        displayDurationSeconds = preferences.getInt(
            DISPLAY_DURATION_SECONDS,
            TV_ALERT_OVERLAY_DEFAULT_DISPLAY_SECONDS,
        ).coerceIn(
            TV_ALERT_OVERLAY_MIN_DISPLAY_SECONDS,
            TV_ALERT_OVERLAY_MAX_DISPLAY_SECONDS,
        ),
        interactiveFrigateAlerts = preferences.getBoolean(INTERACTIVE_FRIGATE_ALERTS, false),
        inlineRecordingPreviews = preferences.getBoolean(INLINE_RECORDING_PREVIEWS, false),
    )

    fun save(settings: TvAlertOverlaySettings): TvAlertOverlaySettings {
        preferences.edit {
            putString(VERTICAL_POSITION, settings.verticalPosition.name)
            putString(HORIZONTAL_POSITION, settings.horizontalPosition.name)
            putString(IMAGE_SIZE, settings.imageSize.name)
            putInt(DISPLAY_DURATION_SECONDS, settings.displayDurationSeconds)
            putBoolean(INTERACTIVE_FRIGATE_ALERTS, settings.interactiveFrigateAlerts)
            putBoolean(INLINE_RECORDING_PREVIEWS, settings.inlineRecordingPreviews)
        }
        return settings
    }

    private inline fun <reified T : Enum<T>> String?.enumValueOrDefault(default: T): T =
        this?.let { value -> runCatching { enumValueOf<T>(value) }.getOrNull() } ?: default

    private companion object {
        const val PREFERENCE_FILE = "opah_tv_alert_overlay"
        const val VERTICAL_POSITION = "vertical_position"
        const val HORIZONTAL_POSITION = "horizontal_position"
        const val IMAGE_SIZE = "image_size"
        const val DISPLAY_DURATION_SECONDS = "display_duration_seconds"
        const val INTERACTIVE_FRIGATE_ALERTS = "interactive_frigate_alerts"
        const val INLINE_RECORDING_PREVIEWS = "inline_recording_previews"
    }
}
