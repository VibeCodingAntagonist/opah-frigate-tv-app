package app.opah.tv.notifications.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat

internal object AndroidNotificationChannels {
    const val SERVICE_CHANNEL_ID = "opah_tv_alerts_service_v1"
    const val ALERT_CHANNEL_ID = "opah_tv_alerts_important_v1"
    const val DETECTION_CHANNEL_ID = "opah_tv_alerts_detection_v1"
    const val HOME_ASSISTANT_CHANNEL_ID = "opah_home_assistant_v1"

    fun create(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(HOME_ASSISTANT_CHANNEL_ID, "Home Assistant", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Notifications sent to this TV by Home Assistant"
                },
                NotificationChannel(
                    SERVICE_CHANNEL_ID,
                    "TV alerts status",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows when Opah TV alerts are active"
                    setShowBadge(false)
                },
                NotificationChannel(
                    ALERT_CHANNEL_ID,
                    "Important TV activity",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Important activity selected by your Opah alert settings"
                },
                NotificationChannel(
                    DETECTION_CHANNEL_ID,
                    "Detected TV activity",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Detected activity selected by your Opah alert settings"
                },
            ),
        )
    }

    fun eventChannelsAvailable(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val manager = context.getSystemService(NotificationManager::class.java)
        return listOf(ALERT_CHANNEL_ID, DETECTION_CHANNEL_ID).any { channelId ->
            manager.getNotificationChannel(channelId)?.importance
                ?.let { importance -> importance != NotificationManager.IMPORTANCE_NONE } == true
        }
    }
}
