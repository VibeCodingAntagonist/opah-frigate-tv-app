package app.opah.tv.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import okhttp3.OkHttpClient

/** Authenticated WebSocket fMP4 bytes, with terminal cancellation before decoder release. */
@UnstableApi
internal class MseLiveDataSource(client: OkHttpClient, hevcSupported: Boolean) : BaseDataSource(true) {
    private val connection = MseLiveConnection(hevcSupported, client::newWebSocket)
    private var uri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.position != 0L) throw IOException("Live stream cannot seek")
        transferInitializing(dataSpec)
        connection.open(dataSpec.uri.toString())
        uri = dataSpec.uri
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = connection.read(buffer, offset, length)
        if (count > 0) bytesTransferred(count)
        return count
    }
    override fun getUri(): Uri? = uri
    fun cancel() = connection.cancel()
    override fun close() {
        cancel()
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
