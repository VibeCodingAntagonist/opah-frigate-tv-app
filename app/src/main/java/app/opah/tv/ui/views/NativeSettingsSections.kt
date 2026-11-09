package app.opah.tv.ui.views

internal enum class NativeAlertSection(val label: String) {
    ACTIVITY("Activity"), SCHEDULE("Schedule"), PREVIEW("Preview"), DELIVERY("Delivery"),
}

/** Existing controls are grouped without changing their actions or availability policies. */
internal fun alertSectionForKey(key: String): NativeAlertSection? = when {
    key in setOf("alerts:saving", "alerts:status", "alerts:error", "alerts:enabled") -> null
    key == "alerts:when" || key.startsWith("alerts:schedule:") || key.startsWith("alerts:snooze") ->
        NativeAlertSection.SCHEDULE
    key.startsWith("alerts:privacy") || key in setOf(
        "alerts:display", "alerts:duration", "alerts:vertical", "alerts:horizontal", "alerts:image", "alerts:interactive",
    ) -> NativeAlertSection.PREVIEW
    key.startsWith("alerts:test:") || key.startsWith("alerts:delivery") ||
        key in setOf("alerts:android", "alerts:overlay") -> NativeAlertSection.DELIVERY
    else -> NativeAlertSection.ACTIVITY
}
