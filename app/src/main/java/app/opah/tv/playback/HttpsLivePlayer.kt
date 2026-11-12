package app.opah.tv.playback

// Adapted from Goofyschooler's PR #18 (e9d70a5); Apache-2.0, Opah contributors.

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.common.C
import app.opah.tv.playback.media3.SizeCheckedRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
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
    private val authorized: () -> Boolean = { true },
    sizeProbe: app.opah.tv.playback.media3.VideoDecoderSizeProbe = app.opah.tv.playback.media3.AndroidVideoDecoderSizeProbe(),
) : LivePlayer {
    private val sources = CopyOnWriteArrayList<MseLiveDataSource>()
    private var released = false
    private var attached = false
    private val client = httpLiveClient(authenticatedClient)
    private val hevcSupported = platformSupportsHevcMain51()
    override val player = ExoPlayer.Builder(context.applicationContext,
        SizeCheckedRenderersFactory(context.applicationContext, sizeProbe)
            .setEnableDecoderFallback(true)
            .forceDisableMediaCodecAsynchronousQueueing(),
    ).setMediaSourceFactory(DefaultMediaSourceFactory(context.applicationContext)
        .setDataSourceFactory(DataSource.Factory {
            synchronized(sources) {
                if (released) throw IOException("Live player released")
                MseLiveDataSource(client, hevcSupported, authorized).also { sources.add(it) }
            }
        })
        .setLoadErrorHandlingPolicy(object : DefaultLoadErrorHandlingPolicy(0) {
            // A zero minimum alone still permits loader retries. Each new live session
            // needs fresh authorization; retain the first refusal and never reopen it.
            override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long = C.TIME_UNSET
        }),
    ).build()

    override fun prepare(uri: String, options: LivePlaybackOptions) {
        check(!released) { "Live player released" }
        check(authorized()) { "Live access unavailable" }
        require(uri.toHttpUrl().scheme == "https")
        attached = true
        outputLease.attachment.target.attach(player, outputLease.attachment.generation)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, options.videoOnly).build()
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
