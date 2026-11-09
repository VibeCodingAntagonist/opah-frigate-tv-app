package app.opah.tv.notifications.android

import android.annotation.SuppressLint
import android.content.Context
import app.opah.tv.notifications.NotificationPauseRecord
import app.opah.tv.notifications.NotificationPauseStore

@SuppressLint("UseKtx") // A failed commit must not silently report a successful pause or resume.
internal class AndroidNotificationPauseStore(context: Context) : NotificationPauseStore {
    private val preferences = context.applicationContext.getSharedPreferences("opah_notification_pause", Context.MODE_PRIVATE)
    override fun read(): NotificationPauseRecord? {
        if (!preferences.contains("format")) { check(preferences.all.isEmpty()); return null }
        check(preferences.getInt("format", 0) == 1)
        return NotificationPauseRecord(preferences.getLong("wall", -1), preferences.getLong("elapsed", -1),
            preferences.getInt("boot", -1), preferences.getLong("duration", -1), preferences.getLong("quiet_through", -1))
    }
    override fun write(record: NotificationPauseRecord): Boolean = preferences.edit().clear().putInt("format", 1)
        .putLong("wall", record.startedWall).putLong("elapsed", record.startedElapsed).putInt("boot", record.bootCount)
        .putLong("duration", record.durationMillis).putLong("quiet_through", record.quietThrough).commit()
}
