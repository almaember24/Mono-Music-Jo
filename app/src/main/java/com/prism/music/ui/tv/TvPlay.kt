package com.prism.music.ui.tv

import com.prism.music.AppContainer
import com.prism.music.R
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.SongItem
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Starting music from Prism TV's screens, with the calls the phone's screens make (Home, Search's top card, the
 * artist page), so a song, album, playlist, mix or artist plays the same way on both. Each returns false when nothing
 * could start (no connection, YouTube refused), for the screen to say so.
 */
object TvPlay {
    /** A song in a row plays with the rest of the row after it, or as a radio from it ([radio], Quick picks' setting). */
    fun song(c: AppContainer, row: List<BrowseItem>, index: Int, rowTitle: String, radio: Boolean) {
        val songs = row.filterIsInstance<SongItem>().map { it.song }
        val tapped = (row.getOrNull(index) as? SongItem)?.song ?: return
        if (radio || songs.size < 2) c.player.playRadio(tapped)
        else c.player.playQueue(songs, songs.indexOf(tapped).coerceAtLeast(0), QueueSource(QueueKind.OTHER, rowTitle, seedId = tapped.id))
    }

    /** A station or mix: it only exists as a watch queue, started here (as on the phone; a station can open on a live stream). */
    suspend fun mix(c: AppContainer, mix: PlaylistItem): Boolean {
        val r = try {
            withContext(Dispatchers.IO) { c.radio(mix.seedVideoId, mix.id, mix.params, keepFirst = true) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (r == null || r.songs.isEmpty()) return false
        val first = r.songs.first()
        val live = first.isLive || withContext(Dispatchers.IO) {
            withTimeoutOrNull(LIVE_CHECK_MS) { runCatching { c.streams.isLive(first.id) }.getOrDefault(false) } ?: false
        }
        val songs = if (live && !first.isLive) listOf(first.copy(isLive = true)) + r.songs.drop(1) else r.songs
        c.queue.counterparts.putAll(r.counterparts)
        c.player.playQueue(songs, 0, QueueSource(QueueKind.RADIO, mix.title))
        return true
    }

    /**
     * [block]'s answer, or false when it failed; but a cancellation (the page left, a new pick) goes on up rather than
     * counting as a failure ("Couldn't start" was said for a play that was only cancelled).
     */
    internal suspend fun tried(block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /** An album or playlist from its first song, or shuffled. */
    suspend fun collection(c: AppContainer, item: BrowseItem, shuffle: Boolean = false, from: Int = 0): Boolean = tried tried@{
        val (kind, songs) = withContext<Pair<QueueKind, List<com.prism.music.data.model.Song>>?>(Dispatchers.IO) {
            when (item) {
                is AlbumItem -> QueueKind.ALBUM to c.ytm.album(item.id).songs
                is PlaylistItem -> QueueKind.PLAYLIST to (
                    if (com.prism.music.data.PrismLibrary.isPrismList(item.id)) c.prismLibrary.list(item.id)?.songs.orEmpty() else c.ytm.playlist(item.id).songs
                    )
                else -> null
            }
        } ?: return@tried false
        if (songs.isEmpty()) return@tried false
        c.player.playQueue(songs, from.coerceIn(0, songs.lastIndex), QueueSource(kind, item.title, originId = item.id, page = item), shuffle = shuffle)
        true
    }

    /** An artist, shuffled (YouTube's shuffle of their songs), or their radio ([radio]). */
    suspend fun artist(c: AppContainer, id: String, radio: Boolean = false): Boolean = tried tried@{
        val a = withContext(Dispatchers.IO) { c.ytm.artist(id) }
        val list = (if (radio) a.radioPlaylistId ?: a.shufflePlaylistId else a.shufflePlaylistId ?: a.radioPlaylistId) ?: return@tried false
        val r = withContext(Dispatchers.IO) {
            if (radio) c.radio(a.radioVideoId ?: a.shuffleVideoId, list, a.radioParams ?: a.shuffleParams)
            else c.radio(a.shuffleVideoId ?: a.radioVideoId, list, a.shuffleParams ?: a.radioParams)
        }
        if (r.songs.isEmpty()) return@tried false
        c.player.playQueue(r.songs, 0, QueueSource(QueueKind.RADIO, if (radio) tr(R.string.mix, a.name) else a.name, originId = id))
        true
    }

    /** One song by its id (a link), as the phone plays a link's song: its details from YouTube, then it alone. */
    suspend fun single(c: AppContainer, id: String) {
        val r = withContext(Dispatchers.IO) { runCatching { c.ytm.next(id) }.getOrNull() }
        val song = r?.songs?.firstOrNull { it.id == id } ?: com.prism.music.data.model.Song(id, tr(R.string.loading_2))
        withContext(Dispatchers.Main) { c.player.playSingle(song) }
    }

    private const val LIVE_CHECK_MS = 8_000L
}

