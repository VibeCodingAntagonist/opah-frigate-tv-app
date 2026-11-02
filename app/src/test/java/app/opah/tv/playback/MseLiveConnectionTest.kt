package app.opah.tv.playback

import java.io.IOException
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test

class MseLiveConnectionTest {
    private class Socket : WebSocket {
        var cancelled = false
        override fun request() = Request.Builder().url("https://example.test/live/mse/api/ws?src=test").build()
        override fun queueSize() = 0L
        override fun send(text: String) = !cancelled
        override fun send(bytes: ByteString) = !cancelled
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled = true }
    }
    private class Fixture(hevcSupported: Boolean = false) {
        val socket = Socket()
        lateinit var listener: WebSocketListener
        val connection = MseLiveConnection(hevcSupported) { _, callback -> listener = callback; socket }
        fun open() = connection.open(socket.request().url.toString())
        fun negotiate() = listener.onMessage(socket, """{"type":"mse","value":"video/mp4; codecs=\"avc1.640029\""}""")
    }
    @Test fun hevcFragmentsAreAcceptedOnlyOnCapableSession() {
        val reply = """{"type":"mse","value":"video/mp4; codecs=\"hvc1.1.6.L153.B0\""}"""
        val capable = Fixture(true); capable.open()
        capable.listener.onMessage(capable.socket, reply)
        capable.listener.onMessage(capable.socket, byteArrayOf(7).toByteString())
        assertEquals(1, capable.connection.read(ByteArray(1), 0, 1))
        capable.connection.cancel()
        val unsupported = Fixture(); unsupported.open()
        unsupported.listener.onMessage(unsupported.socket, reply)
        assertTrue(unsupported.socket.cancelled)
        assertThrows(IOException::class.java) { unsupported.connection.read(ByteArray(1), 0, 1) }
    }
    @Test fun readsOrderedFragmentsOnlyAfterNegotiation() {
        val f = Fixture(); f.open(); f.negotiate()
        f.listener.onMessage(f.socket, byteArrayOf(1, 2).toByteString())
        val target = ByteArray(2)
        assertEquals(2, f.connection.read(target, 0, 2))
        assertArrayEquals(byteArrayOf(1, 2), target)
        f.connection.cancel()
    }
    @Test fun rejectsBinaryBeforeNegotiation() {
        val f = Fixture(); f.open()
        f.listener.onMessage(f.socket, byteArrayOf(1).toByteString())
        assertTrue(f.socket.cancelled)
        assertThrows(IOException::class.java) { f.connection.read(ByteArray(1), 0, 1) }
    }
    @Test fun timeoutCancelsSocketAndDiscardsLateFragments() {
        val f = Fixture(); f.open(); f.negotiate()
        assertThrows(IOException::class.java) { f.connection.read(ByteArray(1), 0, 1, 1) }
        assertTrue(f.socket.cancelled)
        f.listener.onMessage(f.socket, byteArrayOf(1).toByteString())
        assertThrows(IOException::class.java) { f.connection.read(ByteArray(1), 0, 1) }
    }
    @Test fun cancelBeforeOpenNeverConnects() {
        val connection = MseLiveConnection { _, _ -> throw AssertionError("Must not connect") }
        connection.cancel()
        assertThrows(IOException::class.java) { connection.open("https://example.test/") }
    }
    @Test fun cancelWhileSocketIsBeingCreatedCancelsReturnedSocket() {
        val socket = Socket()
        lateinit var connection: MseLiveConnection
        connection = MseLiveConnection { _, _ -> connection.cancel(); socket }
        connection.open("https://example.test/")
        assertTrue(socket.cancelled)
    }
    @Test fun errorsAndDisconnectsAreTerminal() {
        val f = Fixture(); f.open()
        f.listener.onMessage(f.socket, """{"type":"error","value":"private details"}""")
        assertThrows(IOException::class.java) { f.connection.read(ByteArray(1), 0, 1) }
        assertTrue(f.socket.cancelled)
        val g = Fixture(); g.open(); g.negotiate()
        g.listener.onClosing(g.socket, 1000, "private")
        assertTrue(g.socket.cancelled)
        assertThrows(IOException::class.java) { g.connection.read(ByteArray(1), 0, 1) }
    }
    @Test fun insecureUrlsNeverConnect() {
        val connection = MseLiveConnection { _, _ -> throw AssertionError("Must not connect") }
        assertThrows(IOException::class.java) { connection.open("http://example.test/") }
        assertThrows(IOException::class.java) { connection.open("https://user:secret@example.test/") }
    }
}
