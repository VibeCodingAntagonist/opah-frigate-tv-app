package app.opah.tv.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.LinkProperties
import android.os.Build
import android.os.CancellationSignal
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.FutureTask
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** A private live session stays on the exact captured Android network and link context. */
internal class AndroidLocalLiveNetwork(context: Context) : LocalLiveNetwork {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val network: Network? = connectivity?.activeNetwork
    private val links = network?.let { connectivity?.getLinkProperties(it) }
    private val valid = AtomicBoolean(network != null && links != null)
    private var registered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(value: Network) { if (value != network) valid.set(false) }
        override fun onLost(value: Network) { if (value == network) valid.set(false) }
        override fun onLinkPropertiesChanged(value: Network, properties: LinkProperties) {
            if (value == network && properties != links) valid.set(false)
        }
    }
    init {
        try {
            if (valid.get()) {
                requireNotNull(connectivity).registerDefaultNetworkCallback(callback)
                registered = true
                validate()
            }
        } catch (_: Exception) { valid.set(false) }
    }

    // Per-packet authorization is an atomic read; callbacks revoke without Binder work per frame.
    override fun isCurrent(): Boolean = valid.get()

    override fun validate(): Boolean {
        if (!valid.get()) return false
        val current = runCatching {
            network != null && connectivity?.activeNetwork == network && connectivity.getLinkProperties(network) == links
        }.getOrDefault(false)
        if (!current) valid.set(false)
        return current
    }

    override fun close() {
        valid.set(false)
        if (registered) { registered = false; runCatching { connectivity?.unregisterNetworkCallback(callback) } }
    }

    override fun bind(socket: Socket) {
        if (!validate()) throw LocalLiveUnavailableException()
        requireNotNull(network).bindSocket(socket)
    }

    override suspend fun resolve(host: String): List<InetAddress> {
        if (!validate()) throw LocalLiveUnavailableException()
        // Android documents .local/mDNS through getaddrinfo (Network.getAllByName),
        // not the direct DNS query API. Both paths remain within the same request deadline.
        return if (Build.VERSION.SDK_INT >= 29 && !host.trimEnd('.').endsWith(".local", ignoreCase = true))
            resolveModern(host) else resolveSystem(host)
    }

    @androidx.annotation.RequiresApi(29)
    private suspend fun resolveModern(host: String): List<InetAddress> = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        try {
            DnsResolver.getInstance().query(network, host, DnsResolver.FLAG_EMPTY, { it.run() }, signal,
                object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                        if (!continuation.isActive) return
                        if (rcode == 0 && isCurrent()) continuation.resume(answer)
                        else continuation.resumeWithException(LocalLiveUnavailableException())
                    }
                    override fun onError(error: DnsResolver.DnsException) {
                        if (continuation.isActive) continuation.resumeWithException(LocalLiveUnavailableException())
                    }
                })
        } catch (_: Exception) {
            if (continuation.isActive) continuation.resumeWithException(LocalLiveUnavailableException())
        }
    }

    /** System getaddrinfo may ignore interruption: never allow more than two occupied workers. */
    private suspend fun resolveSystem(host: String): List<InetAddress> = suspendCancellableCoroutine { continuation ->
        val task = FutureTask {
            try {
                val answer = requireNotNull(network).getAllByName(host).toList()
                if (continuation.isActive) continuation.resume(answer)
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resumeWithException(LocalLiveUnavailableException())
            }
        }
        continuation.invokeOnCancellation { task.cancel(true) }
        try { systemResolver.execute(task) }
        catch (_: Exception) { if (continuation.isActive) continuation.resumeWithException(LocalLiveUnavailableException()) }
    }

    companion object {
        private val systemResolver = ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS, SynchronousQueue(),
            { work -> Thread(work, "Opah local live DNS").apply { isDaemon = true } })
    }
}
