package com.prism.music.data

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import com.prism.music.AppContainer
import com.prism.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Library's "Saved while playing" (#159): the songs that came to the phone in full as they played, so they play with no
 * connection, without having been downloaded. They live in the song cache, which Prism clears as it needs the room.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object SavedWhilePlaying {
    /**
     * Whether all of [key] is in [cache]: the cache knows the song's full length, and its saved spans cover every byte
     * from the start to there. A length is only known once a request has run to the end (or Load ahead recorded it,
     * [AppContainer.markWhole]), so a song part saved never counts, and neither does one with no known length.
     */
    fun isWhole(cache: Cache, key: String): Boolean {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return length > 0 && cache.isCached(key, 0, length)
    }

    /** Every key saved whole. One short look per key, so playback's reads and writes get their turn in between. */
    fun wholeKeys(cache: Cache): Set<String> = cache.keys.filterTo(HashSet()) { isWhole(cache, it) }

    /**
     * The songs that would play from [whole], given the picked versions ([picks]: song to the recording that plays in its
     * place, kept under that recording's own key). So a song with a version picked counts by that version's bytes, not by
     * what's left of its own, and neither do keys for what isn't kept here: files on the phone and podcast feeds.
     */
    fun songIds(whole: Set<String>, picks: Map<String, String>): Set<String> =
        (whole + picks.filterValues { it in whole }.keys)
            .filterTo(LinkedHashSet()) { (picks[it] ?: it) in whole && !it.startsWith(Song.LOCAL_PREFIX) && !it.startsWith(Song.RSS_PREFIX) }

    /** What [scan] finds; nothing when it fails. Leaving the page isn't failing: that says nothing yet. */
    suspend fun orNone(scan: suspend () -> List<Song>): List<Song> = try {
        scan()
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        emptyList()
    }

    /** Most recently played first, without what Downloads lists already or what Prism has no name for. Off the main thread. */
    suspend fun list(c: AppContainer): List<Song> = withContext(Dispatchers.IO) {
        val whole = wholeKeys(c.playerCache)
        val ids = songIds(whole, c.versions.picks.value.mapValues { it.value.id }).filterNot { c.downloads.isDownloaded(it) }
        ids.chunked(500).flatMap { c.db.songs().getAll(it) }
            .sortedWith(compareByDescending<com.prism.music.data.db.SongEntity> { it.lastPlayed }.thenBy { it.title.lowercase() })
            .map { it.toSong() }
    }
}
