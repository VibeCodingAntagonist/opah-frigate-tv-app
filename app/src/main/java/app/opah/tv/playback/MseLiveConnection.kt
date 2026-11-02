package app.opah.tv.playback

import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** One terminal streaming session, independent of Android so release races are testable. */
internal class MseLiveConnection(
    private val hevcSupported: Boolean = false,
    private val openSocket: (Request, WebSocketListener) -> WebSocket,
) {
    private val pipe = LiveBytePipe()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var stopped = false
    @Volatile private var negotiated = false
    private var started = false

    @Synchronized fun open(uri: String) {
        if (stopped || started) throw IOException("Live stream closed")
        val url = uri.toHttpUrl()
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw IOException("Secure live origin required")
        }
        started = true
        val created = openSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (stopped) { webSocket.cancel(); return }
                if (!webSocket.send("""{"type":"mse","value":"${mseCodecs(hevcSupported)}"}""")) {
                    cancel()
                    webSocket.cancel()
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (stopped || negotiated || !acceptsMseNegotiation(text, hevcSupported)) {
                    fail(LiveTransportException())
                    webSocket.cancel()
                } else negotiated = true
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (stopped || !negotiated || bytes.size > 8 * 1024 * 1024 || !pipe.offer(bytes.toByteArray())) {
                    fail(LiveTransportException())
                    webSocket.cancel()
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail(LiveTransportException(response?.code))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { cancel() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { cancel() }
        })
        socket = created
        if (stopped) created.cancel()
    }

    private fun fail(error: IOException) {
        stopped = true
        pipe.fail(error)
        socket?.cancel()
    }
    fun cancel() = fail(IOException("Live stream closed"))
    fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long = 20_000): Int = try {
        pipe.read(buffer, offset, length, timeoutMs)
    } catch (failure: IOException) {
        cancel()
        throw failure
    }
}
