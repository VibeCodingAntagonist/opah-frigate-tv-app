package app.opah.tv.setup

internal enum class SetupServices { FRIGATE, HOME_ASSISTANT, BOTH;
    val frigate get() = this != HOME_ASSISTANT
    val homeAssistant get() = this != FRIGATE
}
internal enum class SetupStep(val title: String) {
    CHOOSE("Choose services"), FRIGATE("Connect Frigate"), HOME_ASSISTANT("Connect Home Assistant"),
    NOTIFICATIONS("Choose notifications"), PRIVACY("Choose privacy"), PERMISSIONS("Allow TV notifications"),
    CHECKS("Check setup"), FINISH("Ready to finish"),
}
/** Only choices and a cursor may be persisted; credentials and check results never belong here. */
internal data class SetupProgress(
    val services: SetupServices? = null,
    val homeAssistantFirst: Boolean = false,
    val step: SetupStep = SetupStep.CHOOSE,
    val frigateAlerts: Boolean? = null,
    val homeAssistantNotifications: Boolean? = null,
    val popups: Boolean? = null,
    val completed: Boolean = false,
) {
    fun steps(): List<SetupStep> = buildList {
        add(SetupStep.CHOOSE)
        if (services?.homeAssistant == true && homeAssistantFirst) add(SetupStep.HOME_ASSISTANT)
        if (services?.frigate == true) add(SetupStep.FRIGATE)
        if (services?.homeAssistant == true && !homeAssistantFirst) add(SetupStep.HOME_ASSISTANT)
        addAll(listOf(SetupStep.NOTIFICATIONS, SetupStep.PRIVACY, SetupStep.PERMISSIONS, SetupStep.CHECKS, SetupStep.FINISH))
    }
    fun choose(value: SetupServices) = copy(services = value, step = SetupStep.CHOOSE, completed = false)
    fun next(): SetupProgress = if (services == null) this else copy(step = steps().let { it[(it.indexOf(step) + 1).coerceIn(0, it.lastIndex)] })
    fun back(): SetupProgress = copy(step = steps().let { it[(it.indexOf(step) - 1).coerceIn(0, it.lastIndex)] })
    fun validated(): SetupProgress = when {
        services == null -> copy(step = SetupStep.CHOOSE, completed = false)
        step !in steps() -> copy(step = SetupStep.CHOOSE, completed = false)
        else -> this
    }
}

internal data class SetupStatus(
    val canManage: Boolean,
    val binding: String = "",
    val privacyEpoch: Long = -1,
    val frigateSaved: Boolean = false,
    val frigateConnected: Boolean = false,
    val haSaved: Boolean = false,
    val haConnected: Boolean = false,
    val frigateAlerts: Boolean = false,
    val haNotifications: Boolean = false,
    val haDetails: Boolean = false,
    val previewPrivacy: String = "Text only",
    val pinConfigured: Boolean = false,
    val notificationPermission: Boolean = false,
    val homeAssistantChannelAllowed: Boolean = true,
    val overlayPermission: Boolean = false,
    val popups: Boolean = true,
    val delivery: String = "Not tested",
    val deliveryInstruction: String? = null,
    val sharedPause: String = "Not paused",
    val notificationsPaused: Boolean = false,
)

internal fun SetupProgress.finishShowsCameras(status: SetupStatus) = services?.frigate == true && status.frigateConnected
