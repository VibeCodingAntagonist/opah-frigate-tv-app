package app.opah.tv.playback

// Local playback adapted from megabitus98's issue #21 contribution.
// The MSE player originated in Goofyschooler's PR #18; Apache-2.0, Opah contributors.

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody
import okio.ForwardingSource
import okio.buffer

internal class LocalLiveUnavailableException : IOException("Local live connection unavailable")

/** Never references the saved authenticated session, even if one remains in storage. */
internal object AnonymousLiveSessionCookies : app.opah.tv.data.network.SessionCookieStore {
    override fun clear() = Unit
    override fun hasUnexpiredSession(): Boolean = false
}

/** Captured network context, not a process-wide resolver, proxy or credential store. */
internal interface LocalLiveNetwork {
    fun isCurrent(): Boolean
    fun validate(): Boolean = isCurrent()
    suspend fun resolve(host: String): List<InetAddress>
    fun bind(socket: Socket)
    fun close() = Unit
}

/** Parse literals without ever sending a hostname to a resolver. */
internal fun liveAddressLiteral(host: String): InetAddress? {
    if (':' in host) return runCatching { InetAddress.getByName(host) }.getOrNull()
    if (!Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}").matches(host)) return null
    val bytes = host.split('.').map { it.toIntOrNull()?.takeIf { part -> part in 0..255 } ?: return null }
    return InetAddress.getByAddress(bytes.map(Int::toByte).toByteArray())
}

internal fun isPrivateLiveAddress(address: InetAddress): Boolean {
    var bytes = address.address
    if (bytes.size == 16 && bytes.take(10).all { it == 0.toByte() } &&
        bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
    ) bytes = bytes.copyOfRange(12, 16)
    if (bytes.size == 16) return (bytes[0].toInt() and 0xfe) == 0xfc
    if (bytes.size != 4) return false
    val first = bytes[0].toInt() and 255
    val second = bytes[1].toInt() and 255
    return first == 10 || (first == 172 && second in 16..31) || (first == 192 && second == 168)
}

/** Syntactic candidacy only. Hostnames still need bounded, checked resolution before any GET. */
internal fun isLocalLiveCandidate(url: HttpUrl): Boolean = url.scheme == "http" &&
    (liveAddressLiteral(url.host)?.let(::isPrivateLiveAddress) ?: true)

/**
 * One source on one explicit anonymous origin, with fresh private DNS pinned for its lifetime.
 * A new session resolves again. Network changes revoke this session instead of moving it.
 */
internal class LocalHttpLiveTransport private constructor(
    private val base: HttpUrl,
    private val live: HttpUrl,
    private val network: LocalLiveNetwork,
    addresses: List<InetAddress>,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    val scopeBaseUrl: String get() = base.toString()
    private val approved = addresses.map { InetAddress.getByAddress(it.address) }.distinct()
    private val apiUrls = setOf("profile", "config", "version").map {
        base.newBuilder().addPathSegment("api").addPathSegment(it).build()
    }.toSet()
    private val allowedHeaders = setOf("host", "connection", "upgrade", "sec-websocket-key", "sec-websocket-version",
        "sec-websocket-extensions", "user-agent", "accept-encoding", "content-length")

    fun isCurrent(): Boolean = !closed.get() && runCatching { network.isCurrent() }.getOrDefault(false)
    fun allowsLiveUri(url: HttpUrl): Boolean = isCurrent() && url == live

    private fun checkRequest(request: Request) {
        val host = if (':' in base.host) "[${base.host}]" else base.host
        val hostHeader = if (base.port == 80) host else "$host:${base.port}"
        if (!isCurrent() || !network.validate() || request.method != "GET" || request.body != null ||
            (request.url != live && request.url !in apiUrls) ||
            request.headers.names().any { it.lowercase() !in allowedHeaders } ||
            request.header("Host")?.let { it != hostHeader } == true
        ) throw LocalLiveUnavailableException()
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.SECONDS)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (!isCurrent() || hostname != base.host) throw UnknownHostException("Local live destination unavailable")
                return approved.toList()
            }
        })
        .socketFactory(object : SocketFactory() {
            override fun createSocket(): Socket = object : Socket() {
                override fun connect(endpoint: SocketAddress) = connect(endpoint, 2_000)
                override fun connect(endpoint: SocketAddress, timeout: Int) {
                    val destination = endpoint as? InetSocketAddress ?: throw LocalLiveUnavailableException()
                    if (!isCurrent() || destination.isUnresolved || destination.port != base.port ||
                        destination.address !in approved || !isPrivateLiveAddress(destination.address)
                    ) throw LocalLiveUnavailableException()
                    try {
                        network.bind(this)
                        if (!isCurrent()) throw LocalLiveUnavailableException()
                        super.connect(destination, timeout)
                        if (!isCurrent() || inetAddress !in approved || port != base.port) throw LocalLiveUnavailableException()
                    } catch (_: Exception) {
                        runCatching { close() }
                        throw LocalLiveUnavailableException()
                    }
                }
            }
            override fun createSocket(host: String, port: Int): Socket = throw LocalLiveUnavailableException()
            override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = throw LocalLiveUnavailableException()
            override fun createSocket(host: InetAddress, port: Int): Socket = throw LocalLiveUnavailableException()
            override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = throw LocalLiveUnavailableException()
        })
        .addInterceptor { chain ->
            checkRequest(chain.request())
            val response = chain.proceed(chain.request())
            if (!isCurrent()) { response.close(); throw LocalLiveUnavailableException() }
            if (response.code == 101) response else {
                val body = response.body
                if (body.contentLength() > MAX_SCOPE_BYTES) { response.close(); throw LocalLiveUnavailableException() }
                response.newBuilder().body(object : ResponseBody() {
                    private val limited = object : ForwardingSource(body.source()) {
                        private var read = 0L
                        override fun read(sink: okio.Buffer, byteCount: Long): Long {
                            if (!isCurrent()) throw LocalLiveUnavailableException()
                            val count = super.read(sink, minOf(byteCount, MAX_SCOPE_BYTES - read + 1))
                            if (count > 0) read += count
                            if (read > MAX_SCOPE_BYTES) throw LocalLiveUnavailableException()
                            return count
                        }
                    }.buffer()
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source() = limited
                }).build()
            }
        }.build()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        network.close()
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    override fun toString(): String = "LocalHttpLiveTransport([redacted])"

    companion object {
        private const val MAX_SCOPE_BYTES = 4L * 1024 * 1024

        suspend fun prepare(baseUrl: String, streamName: String, network: LocalLiveNetwork): LocalHttpLiveTransport {
            var handedOff = false
            try {
                val base = baseUrl.toHttpUrlOrNull() ?: throw LocalLiveUnavailableException()
                val live = httpLiveUri(baseUrl, streamName)?.toHttpUrlOrNull() ?: throw LocalLiveUnavailableException()
                if (!isLocalLiveCandidate(base) || !network.validate()) throw LocalLiveUnavailableException()
                val addresses = liveAddressLiteral(base.host)?.let(::listOf) ?: try {
                    withTimeoutOrNull(2_000L) { network.resolve(base.host) } ?: throw LocalLiveUnavailableException()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { throw LocalLiveUnavailableException() }
                if (!network.validate() || addresses.isEmpty() || addresses.size > 8 || addresses.any { !isPrivateLiveAddress(it) }) {
                    throw LocalLiveUnavailableException()
                }
                return LocalHttpLiveTransport(base, live, network, addresses).also { handedOff = true }
            } finally { if (!handedOff) network.close() }
        }
    }
}
