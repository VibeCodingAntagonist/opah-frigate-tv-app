package app.opah.tv.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource

@UnstableApi
class Media3LivePlayer(context: Context) : LivePlayer {
    private val exoPlayer = ExoPlayer.Builder(context.applicationContext).build()

    override val player: ExoPlayer get() = exoPlayer

    override fun prepare(uri: String, options: LivePlaybackOptions) {
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, options.videoOnly)
            .build()
        val source = RtspMediaSource.Factory()
            .setTimeoutMs(RTSP_TIMEOUT_MS)
            .setForceUseRtpTcp(options.forceRtpTcp)
            // Never enable Media3 RTSP debug logging here; SDP may contain private details.
            .createMediaSource(MediaItem.fromUri(uri))
        exoPlayer.setMediaSource(source)
        exoPlayer.playWhenReady = true
        exoPlayer.prepare()
    }

    override fun release() {
        exoPlayer.release()
    }

    private companion object {
        const val RTSP_TIMEOUT_MS = 8_000L
    }
}

class Media3LivePlayerFactory : LivePlayerFactory {
    override fun create(context: Context): LivePlayer = Media3LivePlayer(context)
}
