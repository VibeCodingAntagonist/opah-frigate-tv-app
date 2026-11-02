package app.opah.tv.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import java.util.concurrent.CopyOnWriteArrayList
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import app.opah.tv.playback.media3.ExclusiveVideoOutputLease

/** Continuous fMP4 over Frigate's MSE route; retains authenticated TLS and cookie policy. */
@UnstableApi
internal class HttpsLivePlayer(
    context: Context,
    authenticatedClient: OkHttpClient,
    private val outputLease: ExclusiveVideoOutputLease,
) : LivePlayer {
    private val sources = CopyOnWriteArrayList<MseLiveDataSource>()
    private var released = false
    private var attached = false
    private val client = httpLiveClient(authenticatedClient)
    private val hevcSupported = platformSupportsHevcMain51()
    override val player = ExoPlayer.Builder(context.applicationContext,
        DefaultRenderersFactory(context.applicationContext)
            .setEnableDecoderFallback(true)
            .forceDisableMediaCodecAsynchronousQueueing(),
    ).setMediaSourceFactory(DefaultMediaSourceFactory(context.applicationContext)
        .setDataSourceFactory(DataSource.Factory {
            synchronized(sources) {
                if (released) throw IOException("Live player released")
                MseLiveDataSource(client, hevcSupported).also { sources.add(it) }
            }
        })
        .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0)),
    ).build()

    override fun prepare(uri: String, options: LivePlaybackOptions) {
        check(!released) { "Live player released" }
        require(uri.toHttpUrl().scheme == "https")
        attached = true
        outputLease.attachment.target.attach(player, outputLease.attachment.generation)
        player.volume = if (options.videoOnly) 0f else 1f
        player.setMediaItem(MediaItem.Builder().setUri(uri).setMimeType("video/mp4").build())
        player.playWhenReady = true
        player.prepare()
    }

    override fun release() { close() }

    fun close(): Boolean = outputLease.finish {
        // Wake loading threads before synchronous Media3 teardown on the main looper.
        val cancelled = runCatching {
            synchronized(sources) {
                released = true
                sources.forEach { it.cancel() }
            }
        }.isSuccess
        val concealed = runCatching {
            !attached || outputLease.attachment.target.detachAndHide(player, outputLease.attachment.generation)
        }.getOrDefault(false)
        val decoderReleased = runCatching { player.release() }.isSuccess
        sources.clear()
        cancelled && concealed && decoderReleased
    }
}
