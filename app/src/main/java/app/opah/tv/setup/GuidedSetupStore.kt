package app.opah.tv.setup

import android.annotation.SuppressLint
import android.content.Context

/** Progress contains no addresses, usernames, passwords, tokens, or delivery evidence. */
@SuppressLint("UseKtx") // Keep commit's result so a failed progress save can be reported.
internal class GuidedSetupStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("opah_guided_setup", Context.MODE_PRIVATE)
    fun read(): SetupProgress = runCatching {
        if (preferences.getInt("format", 1) != 1) return SetupProgress()
        fun choice(key: String) = preferences.getString(key, null)?.let { if (it == "on") true else if (it == "off") false else null }
        SetupProgress(
            services = preferences.getString("services", null)?.let { SetupServices.valueOf(it) },
            homeAssistantFirst = preferences.getBoolean("ha_first", false),
            step = preferences.getString("step", null)?.let { SetupStep.valueOf(it) } ?: SetupStep.CHOOSE,
            frigateAlerts = choice("frigate_alerts"), homeAssistantNotifications = choice("ha_notifications"), popups = choice("popups"),
            completed = preferences.getBoolean("completed", false),
        ).validated()
    }.getOrDefault(SetupProgress())
    fun write(progress: SetupProgress): Boolean {
        val safe = progress.validated()
        fun choice(value: Boolean?) = value?.let { if (it) "on" else "off" }
        return preferences.edit().clear().putInt("format", 1).putString("services", safe.services?.name)
            .putBoolean("ha_first", safe.homeAssistantFirst).putString("step", safe.step.name)
            .putString("frigate_alerts", choice(safe.frigateAlerts)).putString("ha_notifications", choice(safe.homeAssistantNotifications))
            .putString("popups", choice(safe.popups)).putBoolean("completed", safe.completed).commit()
    }
}
