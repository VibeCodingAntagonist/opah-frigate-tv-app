package app.opah.tv.notifications.android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri

internal enum class OpahNotificationSettingsDestination(val guidance: String? = null) {
    NOTIFICATIONS,
    APP_DETAILS("Allow notifications in Opah's TV app settings"),
    UNAVAILABLE("Open TV settings and choose Opah to allow notifications"),
}

/** Some TVs expose only app details. Opening either screen never grants or enables anything. */
internal object OpahNotificationSettings {
    fun open(activity: Activity, launch: (Intent) -> Unit = activity::startActivity): OpahNotificationSettingsDestination {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= 26) add(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${activity.packageName}".toUri()))
        }
        for (intent in candidates) {
            try {
                launch(intent)
                return if (intent.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    OpahNotificationSettingsDestination.APP_DETAILS else OpahNotificationSettingsDestination.NOTIFICATIONS
            } catch (_: ActivityNotFoundException) {
                // Try the next standard Android settings route.
            } catch (_: SecurityException) {
                // A TV vendor may reserve its dedicated screen for system apps.
            }
        }
        return OpahNotificationSettingsDestination.UNAVAILABLE
    }
}
