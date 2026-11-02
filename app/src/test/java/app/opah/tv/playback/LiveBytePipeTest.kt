package app.opah.tv.playback

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveBytePipeTest {
    @Test fun fragmentsRemainOrderedAcrossPartialReads() {
        val pipe = LiveBytePipe(8)
        assertTrue(pipe.offer(byteArrayOf(1, 2, 3)))
        assertTrue(pipe.offer(byteArrayOf(4, 5)))
        val buffer = ByteArray(5)
        assertEquals(2, pipe.read(buffer, 0, 2))
        assertEquals(1, pipe.read(buffer, 2, 3))
        assertEquals(2, pipe.read(buffer, 3, 2))
        assertTrue(buffer.contentEquals(byteArrayOf(1, 2, 3, 4, 5)))
    }

    @Test fun overflowFailsRatherThanDroppingMp4Bytes() {
        val pipe = LiveBytePipe(4)
        assertTrue(pipe.offer(ByteArray(4)))
        assertFalse(pipe.offer(byteArrayOf(5)))
        assertThrows(IOException::class.java) { pipe.read(ByteArray(4), 0, 4) }
    }

    @Test fun consumedCapacityCanBeReused() {
        val pipe = LiveBytePipe(4)
        pipe.offer(ByteArray(4))
        assertEquals(4, pipe.read(ByteArray(4), 0, 4))
        assertTrue(pipe.offer(ByteArray(4)))
    }

    @Test fun closeUnblocksReaderAndRejectsLateCallbacks() {
        val pipe = LiveBytePipe()
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        try {
            val result = executor.submit<Boolean> {
                started.countDown()
                try {
                    pipe.read(ByteArray(1), 0, 1)
                    false
                } catch (_: IOException) { true }
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            pipe.fail(IOException("closed"))
            assertTrue(result.get(1, TimeUnit.SECONDS))
            assertFalse(pipe.offer(byteArrayOf(1)))
        } finally { executor.shutdownNow() }
    }

    @Test fun timeoutAndZeroLengthReadsAreBounded() {
        val pipe = LiveBytePipe()
        assertEquals(0, pipe.read(ByteArray(0), 0, 0))
        assertThrows(IOException::class.java) { pipe.read(ByteArray(1), 0, 1, 1) }
    }

    @Test fun statusShowsOnlyNumericHttpCodeNotPrivateDetails() {
        assertEquals("HTTP 404", liveFailureStatus(IOException("private", LiveTransportException(404))))
        assertEquals("HTTP 401", liveFailureStatus(LiveTransportException(401)))
        assertNull(liveFailureStatus(IOException("https://private.invalid/token")))
        assertNull(liveFailureStatus(LiveTransportException()))
    }

    @Test fun timeoutIsTerminalAndRejectsLateVideo() {
        val pipe = LiveBytePipe()
        assertThrows(IOException::class.java) { pipe.read(ByteArray(1), 0, 1, 1) }
        assertFalse(pipe.offer(byteArrayOf(1)))
        assertThrows(IOException::class.java) { pipe.read(ByteArray(1), 0, 1, 1) }
    }
}
