package app.opah.tv.playback

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer

data class LivePlaybackOptions(
    val forceRtpTcp: Boolean = true,
    val videoOnly: Boolean = false,
)

interface LivePlayer {
    val player: ExoPlayer

    fun prepare(uri: String, options: LivePlaybackOptions = LivePlaybackOptions())

    fun release()
}

fun interface LivePlayerFactory {
    fun create(context: Context): LivePlayer
}
