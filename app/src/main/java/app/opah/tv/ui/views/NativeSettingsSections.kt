package app.opah.tv.ui.views

internal enum class NativeAlertSection(val label: String) {
    ACTIVITY("Activity"), SCHEDULE("Schedule"), PREVIEW("Preview"),
}

/** Existing controls are grouped without changing their actions or availability policies. */
internal fun alertSectionForKey(key: String): NativeAlertSection? = when {
    key in setOf("alerts:saving", "alerts:status", "alerts:error", "alerts:enabled") -> null
    key == "alerts:when" || key.startsWith("alerts:schedule:") || key.startsWith("alerts:snooze") ->
        NativeAlertSection.SCHEDULE
    key.startsWith("alerts:privacy") || key in setOf(
        "alerts:display", "alerts:interactive", "alerts:inline-recording", "alerts:test:frigate",
    ) -> NativeAlertSection.PREVIEW
    else -> NativeAlertSection.ACTIVITY
}
