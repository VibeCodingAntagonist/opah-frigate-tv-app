package app.opah.tv.ui

import android.view.View
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.opah.tv.ui.views.NativePlaybackScreenAwake

/** Give this playback surface its own display request, separate from the Compose root and Monitor. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun BoxScope.rememberPlaybackAwakeHost(
    viewingKey: Any,
): NativePlaybackScreenAwake? = key(viewingKey) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val host = remember(context) { View(context) }
    var awake by remember(lifecycleOwner, host) { mutableStateOf<NativePlaybackScreenAwake?>(null) }
    AndroidView(factory = { host }, modifier = Modifier.matchParentSize())
    DisposableEffect(lifecycleOwner, host) {
        val owner = NativePlaybackScreenAwake(lifecycleOwner, host)
        awake = owner
        onDispose { owner.close() }
    }
    awake
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun PlaybackAwakeStream(awake: NativePlaybackScreenAwake?, player: ExoPlayer?) {
    if (awake == null || player == null) return
    DisposableEffect(awake, player) {
        val stream = awake.openStream()
        awake.bindPlayer(stream, player)
        onDispose { awake.removeStream(stream) }
    }
}
