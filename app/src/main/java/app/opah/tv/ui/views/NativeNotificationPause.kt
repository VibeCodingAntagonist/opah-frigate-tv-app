package app.opah.tv.ui.views

import app.opah.tv.notifications.NotificationPauseSnapshot
import java.text.DateFormat
import java.util.Date

internal fun notificationPauseLabel(pause: NotificationPauseSnapshot): String = when {
    !pause.available -> "Paused — pause settings need attention"
    !pause.paused -> "Not paused"
    pause.untilEpochMillis != null -> "Paused until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(pause.untilEpochMillis))}"
    else -> "Paused"
}

internal fun notificationPauseRows(pause: NotificationPauseSnapshot, busy: Boolean, error: String?): List<NativeRowModel> = buildList {
    add(NativeRowModel("pause:status", "TV notifications", notificationPauseLabel(pause), kind = NativeRowKind.INFORMATION))
    error?.let { add(NativeRowModel("pause:error", "Pause needs attention", it, kind = NativeRowKind.INFORMATION)) }
    add(NativeRowModel("pause:15", "Pause for 15 minutes", enabled = pause.available && !busy))
    add(NativeRowModel("pause:60", "Pause for 1 hour", enabled = pause.available && !busy))
    if (pause.paused || !pause.available) add(NativeRowModel("pause:resume", "Resume notifications", enabled = !busy))
    add(NativeRowModel("pause:help", "Frigate and Home Assistant", "Pauses messages and pop-ups on this TV\nRecordings and home automations continue\nExisting Frigate snoozes and schedules stay in place\nPaused notifications are not replayed", kind = NativeRowKind.INFORMATION))
}
