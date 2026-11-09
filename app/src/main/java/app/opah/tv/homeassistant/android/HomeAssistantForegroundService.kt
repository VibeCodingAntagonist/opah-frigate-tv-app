package app.opah.tv.homeassistant.android

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.opah.tv.OpahApplication
import app.opah.tv.R
import app.opah.tv.notifications.android.AndroidNotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** This listener owns no video players and works independently of Frigate alerts. */
internal class HomeAssistantForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val networkEvents = Channel<Boolean>(Channel.CONFLATED)
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private var networkRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { networkEvents.trySend(true) }
        override fun onLost(network: Network) {
            // A default-network callback reports loss only when no replacement satisfies it.
            networkEvents.trySend(false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            for (available in networkEvents) container().homeAssistantConnection.networkChanged(available)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            scope.launch {
                try { container().homeAssistantConnection.setEnabled(false) }
                finally { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
            }
            return START_NOT_STICKY
        }
        return runCatching {
            AndroidNotificationChannels.create(this)
            val stop = PendingIntent.getService(this, 50_061,
                Intent(this, HomeAssistantForegroundService::class.java).setAction(STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, AndroidNotificationChannels.SERVICE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notifications).setContentTitle("Home Assistant notifications")
                .setContentText("Receive messages from your home automations").setOngoing(true).setSilent(true)
                .addAction(0, "Turn off", stop)
                .extend(NotificationCompat.TvExtender().setSuppressShowOverApps(true)).build()
            ServiceCompat.startForeground(this, 50_060, notification,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
            scope.launch {
                val connection = container().homeAssistantConnection
                connection.loadState()
                if (connection.enabled()) {
                    if (!startNetworkMonitoring()) connection.start()
                }
                else { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
            }
            START_STICKY
        }.getOrElse {
            container().homeAssistantConnection.deliveryUnavailable()
            stopSelf(startId)
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        stopNetworkMonitoring()
        networkEvents.close()
        container().homeAssistantConnection.stop()
        scope.cancel()
        super.onDestroy()
    }

    @Synchronized
    private fun startNetworkMonitoring(): Boolean {
        if (networkRegistered) return true
        val manager = connectivity ?: return false
        container().homeAssistantConnection.networkChanged(false)
        networkRegistered = runCatching { manager.registerDefaultNetworkCallback(networkCallback) }.isSuccess
        return networkRegistered
    }

    @Synchronized
    private fun stopNetworkMonitoring() {
        if (!networkRegistered) return
        networkRegistered = false
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
    }

    private fun container() = (application as OpahApplication).container

    companion object {
        private const val STOP = "app.opah.tv.HOME_ASSISTANT_STOP"
        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, HomeAssistantForegroundService::class.java))
        }.isSuccess
        fun stop(context: Context) { context.stopService(Intent(context, HomeAssistantForegroundService::class.java)) }
    }
}

internal class HomeAssistantBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val app = context.applicationContext as? OpahApplication ?: return@launch
                if (withTimeoutOrNull(8_000) { app.container.homeAssistantConnection.enabled() } == true) {
                    HomeAssistantForegroundService.start(context)
                }
            } finally { pending.finish(); scope.cancel() }
        }
    }
}
