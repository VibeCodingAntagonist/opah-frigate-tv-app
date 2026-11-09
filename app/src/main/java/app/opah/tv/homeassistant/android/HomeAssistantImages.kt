package app.opah.tv.homeassistant.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import app.opah.tv.homeassistant.HomeAssistantImageDisclosure
import app.opah.tv.homeassistant.HomeAssistantSession
import app.opah.tv.homeassistant.homeAssistantImageUrl
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

/** No disk cache, redirects, cookies, alternate hosts, or unbounded decodes. */
internal class HomeAssistantImages(client: OkHttpClient) {
    private val client = client.newBuilder().cache(null).cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(4, TimeUnit.SECONDS).build()

    suspend fun fetch(session: HomeAssistantSession, reference: String, disclosure: HomeAssistantImageDisclosure): Bitmap? {
        if (disclosure == HomeAssistantImageDisclosure.NONE) return null
        val url = homeAssistantImageUrl(session.server, reference) ?: return null
        val bytes = read(client.newCall(Request.Builder().url(url).header("Authorization", "Bearer ${session.accessToken}")
            .header("Cache-Control", "no-store").build())) ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth !in 1..4_096 || bounds.outHeight !in 1..4_096 ||
                bounds.outWidth.toLong() * bounds.outHeight > 12_000_000 || bounds.outMimeType !in setOf("image/png", "image/jpeg")) return null
            var sample = 1
            while (bounds.outWidth / sample > 960 || bounds.outHeight / sample > 540) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            if (disclosure == HomeAssistantImageDisclosure.FULL) decoded else {
                val tiny = decoded.scale(minOf(12, (decoded.width / 8).coerceAtLeast(1)),
                    minOf(8, (decoded.height / 8).coerceAtLeast(1)), filter = false)
                tiny.scale(decoded.width, decoded.height, filter = false).also { redacted ->
                    if (tiny !== redacted && tiny !== decoded) tiny.recycle()
                    if (decoded !== redacted) decoded.recycle()
                }
            }
        } catch (_: Exception) { null } finally { bytes.fill(0) }
    }

    private suspend fun read(call: Call): ByteArray? = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resume(null) }
            override fun onResponse(call: Call, response: Response) {
                val bytes = runCatching { response.use {
                    if (!it.isSuccessful || it.body.contentType()?.let { type -> "${type.type}/${type.subtype}" } !in setOf("image/png", "image/jpeg") ||
                        it.body.contentLength() > MAX_BYTES) return@use null
                    val buffer = Buffer()
                    val source = it.body.source()
                    while (buffer.size <= MAX_BYTES) {
                        if (source.read(buffer, minOf(8_192, MAX_BYTES + 1 - buffer.size)) == -1L) break
                    }
                    if (buffer.size > MAX_BYTES) { buffer.clear(); null } else buffer.readByteArray()
                } }.getOrNull()
                if (continuation.isActive) continuation.resume(bytes) else bytes?.fill(0)
            }
        })
    }
    private companion object { const val MAX_BYTES = 2_097_152L }
}
