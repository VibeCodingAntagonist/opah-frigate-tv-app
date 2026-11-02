package app.opah.tv.playback

import java.io.IOException
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test

/** Real loopback TLS/WebSocket exchanges. Certificates and cookies are synthetic. */
class MseTlsTransportTest {
    private class Fixture : AutoCloseable {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val server = MockWebServer()
        val client: OkHttpClient
        init {
            val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
            server.useHttps(serverTls.sslSocketFactory())
            server.start()
            val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
            val original = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
                .cookieJar(object : CookieJar {
                    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
                    override fun loadForRequest(url: HttpUrl) = listOf(
                        Cookie.Builder().name("frigate_token").value("synthetic-test-cookie")
                            .hostOnlyDomain("localhost").secure().build(),
                    )
                }).build()
            client = httpLiveClient(original)
        }
        fun connection() = MseLiveConnection(openSocket = client::newWebSocket)
        fun uri() = server.url("/live/mse/api/ws?src=test").toString()
        override fun close() {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            server.close()
        }
    }
    @Test fun authenticatesAndReceivesWebSocketVideoBytes() {
        Fixture().use { f ->
            f.server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    webSocket.send("""{"type":"mse","value":"video/mp4; codecs=\"avc1.640029\""}""")
                    webSocket.send(byteArrayOf(1, 2, 3).toByteString())
                }
            }).build())
            val connection = f.connection()
            try {
                connection.open(f.uri())
                val bytes = ByteArray(3)
                assertEquals(3, connection.read(bytes, 0, 3, 5_000))
                assertArrayEquals(byteArrayOf(1, 2, 3), bytes)
                val request = requireNotNull(f.server.takeRequest(5, TimeUnit.SECONDS))
                assertEquals("frigate_token=synthetic-test-cookie", request.headers["Cookie"])
            } finally { connection.cancel() }
        }
    }
    @Test fun refusesRedirectAndReportsOnlyStatus() {
        Fixture().use { f ->
            f.server.enqueue(MockResponse.Builder().code(302)
                .addHeader("Location", f.server.url("/must-not-follow")).build())
            val connection = f.connection()
            try {
                connection.open(f.uri())
                val failure = assertThrows(IOException::class.java) { connection.read(ByteArray(1), 0, 1, 5_000) }
                assertEquals("HTTP 302", liveFailureStatus(failure))
                assertEquals(1, f.server.requestCount)
            } finally { connection.cancel() }
        }
    }
    @Test fun authenticationDenialIsNotRetried() {
        Fixture().use { f ->
            f.server.enqueue(MockResponse(code = 401, body = "private server response"))
            val connection = f.connection()
            try {
                connection.open(f.uri())
                val failure = assertThrows(IOException::class.java) { connection.read(ByteArray(1), 0, 1, 5_000) }
                assertEquals("HTTP 401", liveFailureStatus(failure))
                assertFalse(failure.message.orEmpty().contains("private"))
                assertEquals(1, f.server.requestCount)
            } finally { connection.cancel() }
        }
    }
}
