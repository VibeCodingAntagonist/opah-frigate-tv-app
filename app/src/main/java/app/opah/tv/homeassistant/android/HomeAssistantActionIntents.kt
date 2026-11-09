package app.opah.tv.homeassistant.android

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import app.opah.tv.OpahApplication

/** Only opaque, expiring tickets cross the Android boundary, never callback payloads or URLs. */
internal object HomeAssistantActionIntents {
    private const val OPEN = "app.opah.tv.HOME_ASSISTANT_ACTION"
    private const val DISMISS = "app.opah.tv.HOME_ASSISTANT_DISMISS"
    private const val TICKET = "home_assistant_ticket"
    private val validTicket = Regex("[a-f0-9-]{36}\\.[0-2]")

    fun ticket(intent: Intent?): String? = intent?.takeIf { it.action == OPEN }
        ?.getStringExtra(TICKET)?.takeIf { validTicket.matches(it) }

    fun pending(context: Context, ticket: String): PendingIntent = PendingIntent.getActivity(context, 0,
        Intent(context, HomeAssistantActionActivity::class.java).setAction(OPEN)
            .setData(Uri.Builder().scheme("opah-internal").authority("ha-action").appendPath(ticket).build())
            .putExtra(TICKET, ticket).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun open(context: Context, ticket: String, overlay: Boolean) {
        if (!validTicket.matches(ticket)) return
        val options = if (overlay && Build.VERSION.SDK_INT >= 34) ActivityOptions.makeBasic().apply {
            @Suppress("DEPRECATION")
            setPendingIntentBackgroundActivityStartMode(if (Build.VERSION.SDK_INT >= 36)
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }.toBundle() else null
        pending(context, ticket).send(context, 0, null, null, null, null, options)
    }

    fun dismiss(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, HomeAssistantNoticeDismissReceiver::class.java).setAction(DISMISS)
            .setData(Uri.Builder().scheme("opah-internal").authority("ha-dismiss").appendPath(id).build())
            .putExtra(TICKET, id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun dismissedId(intent: Intent?): String? = intent?.takeIf { it.action == DISMISS }?.getStringExtra(TICKET)
        ?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) }
}

internal class HomeAssistantNoticeDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        HomeAssistantActionIntents.dismissedId(intent)?.let {
            (context.applicationContext as? OpahApplication)?.container?.homeAssistantNotifications?.dismiss(it)
        }
    }
}
