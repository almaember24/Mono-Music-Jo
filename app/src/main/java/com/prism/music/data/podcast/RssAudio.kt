package com.prism.music.data.podcast

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.prism.music.data.model.Song

/**
 * An episode from a feed reads through [rss], everything else through [songs], so a feed's audio never goes near the
 * clients and resolvers that talk to YouTube, and no song near [RssGuard]'s. Picked per `open`, by the song id in
 * `prism://audio/<id>`: Cast's decoder and the tail player get it the same way as the main player.
 */
@OptIn(UnstableApi::class)
class RssOrSongSource(private val rss: DataSource, private val songs: DataSource) : DataSource {
    private var opened: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        rss.addTransferListener(transferListener)
        songs.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (isRss(dataSpec.uri)) rss else songs
        opened = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = opened?.read(buffer, offset, length) ?: error("not open")
    override fun getUri(): Uri? = opened?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = opened?.responseHeaders ?: emptyMap()

    override fun close() {
        val source = opened
        opened = null
        source?.close()
    }

    companion object {
        fun isRss(uri: Uri): Boolean = uri.scheme == "prism" && uri.lastPathSegment?.startsWith(Song.RSS_PREFIX) == true
    }
}
