package com.prism.music.data.imports

import com.prism.music.data.db.AlbumPlays
import com.prism.music.data.db.ArtistPlays
import com.prism.music.data.db.CountedPlayDao
import com.prism.music.data.db.FirstPlay
import com.prism.music.data.db.MonthTotal
import com.prism.music.data.db.PlayEventDao
import com.prism.music.data.db.PlayEventEntity
import com.prism.music.data.db.SongPlays
import com.prism.music.data.db.SourcePlays

/**
 * What Replay, Rewind and the Replay card count (1.8.1): Prism's own plays, plus the imported sources the listener
 * counts ("Count history from"). With none of them, [PrismPlays] asks exactly what Replay always asked, so its numbers
 * can't change; with some, [CountedPlays] asks the same questions of [com.prism.music.data.db.CountedPlay].
 */
interface PlayStats {
    /** The imported sources counted besides Prism's own plays (empty: Prism only). */
    val sources: List<ImportSource>

    suspend fun totalMs(from: Long, to: Long): Long
    suspend fun count(from: Long, to: Long): Int
    suspend fun topSongs(from: Long, to: Long, limit: Int): List<SongPlays>
    suspend fun topArtists(from: Long, to: Long, limit: Int): List<ArtistPlays>
    suspend fun monthly(from: Long, to: Long): List<MonthTotal>
    suspend fun activeDays(from: Long, to: Long): Int
    suspend fun firstEvent(): Long?
    suspend fun events(from: Long, to: Long): List<PlayEventEntity>
    suspend fun songCount(from: Long, to: Long): Int
    suspend fun artistCount(from: Long, to: Long): Int
    suspend fun newArtists(from: Long, to: Long): List<ArtistPlays>
    suspend fun topAlbums(from: Long, to: Long, limit: Int): List<AlbumPlays>
    suspend fun firstPlay(from: Long, to: Long): FirstPlay?
    suspend fun bySource(from: Long, to: Long): List<SourcePlays>
    suspend fun recognizedIn(from: Long, to: Long): Int
    suspend fun blendsIn(from: Long, to: Long): Int
    suspend fun releasesFoundIn(from: Long, to: Long): Int
}

/** Prism's own plays only: [PlayEventDao] itself, call for call. */
class PrismPlays(private val d: PlayEventDao) : PlayStats {
    override val sources: List<ImportSource> get() = emptyList()
    override suspend fun totalMs(from: Long, to: Long) = d.totalMs(from, to)
    override suspend fun count(from: Long, to: Long) = d.count(from, to)
    override suspend fun topSongs(from: Long, to: Long, limit: Int) = d.topSongs(from, to, limit)
    override suspend fun topArtists(from: Long, to: Long, limit: Int) = d.topArtists(from, to, limit)
    override suspend fun monthly(from: Long, to: Long) = d.monthly(from, to)
    override suspend fun activeDays(from: Long, to: Long) = d.activeDays(from, to)
    override suspend fun firstEvent() = d.firstEvent()
    override suspend fun events(from: Long, to: Long) = d.events(from, to)
    override suspend fun songCount(from: Long, to: Long) = d.songCount(from, to)
    override suspend fun artistCount(from: Long, to: Long) = d.artistCount(from, to)
    override suspend fun newArtists(from: Long, to: Long) = d.newArtists(from, to)
    override suspend fun topAlbums(from: Long, to: Long, limit: Int) = d.topAlbums(from, to, limit)
    override suspend fun firstPlay(from: Long, to: Long) = d.firstPlay(from, to)
    override suspend fun bySource(from: Long, to: Long) = d.bySource(from, to)
    override suspend fun recognizedIn(from: Long, to: Long) = d.recognizedIn(from, to)
    override suspend fun blendsIn(from: Long, to: Long) = d.blendsIn(from, to)
    override suspend fun releasesFoundIn(from: Long, to: Long) = d.releasesFoundIn(from, to)
}

/** Prism's plays and the imported [sources]. */
class CountedPlays(override val sources: List<ImportSource>, private val d: CountedPlayDao, private val prism: PlayEventDao) : PlayStats {
    private val o = listOf(PRISM) + sources.map { it.key }
    override suspend fun totalMs(from: Long, to: Long) = d.totalMs(o, from, to)
    override suspend fun count(from: Long, to: Long) = d.count(o, from, to)
    override suspend fun topSongs(from: Long, to: Long, limit: Int) = d.topSongs(o, from, to, limit)
    override suspend fun topArtists(from: Long, to: Long, limit: Int) = d.topArtists(o, from, to, limit)
    override suspend fun monthly(from: Long, to: Long) = d.monthly(o, from, to)
    override suspend fun activeDays(from: Long, to: Long) = d.activeDays(o, from, to)
    override suspend fun firstEvent() = d.firstEvent(o)
    override suspend fun events(from: Long, to: Long) = d.events(o, from, to)
    override suspend fun songCount(from: Long, to: Long) = d.songCount(o, from, to)
    override suspend fun artistCount(from: Long, to: Long) = d.artistCount(o, from, to)
    override suspend fun newArtists(from: Long, to: Long) = d.newArtists(o, from, to)
    override suspend fun topAlbums(from: Long, to: Long, limit: Int) = d.topAlbums(o, from, to, limit)
    override suspend fun firstPlay(from: Long, to: Long) = d.firstPlay(o, from, to)
    override suspend fun bySource(from: Long, to: Long) = d.bySource(o, from, to)
    // Not plays: Prism's own, whatever's counted.
    override suspend fun recognizedIn(from: Long, to: Long) = prism.recognizedIn(from, to)
    override suspend fun blendsIn(from: Long, to: Long) = prism.blendsIn(from, to)
    override suspend fun releasesFoundIn(from: Long, to: Long) = prism.releasesFoundIn(from, to)

    companion object { const val PRISM = "prism" }
}

/**
 * What the mixes, radio and Quick picks learn from (1.8.1): Prism's own plays, and the imported ones once they're
 * matched to songs, when "Imported plays shape your mixes" is on. One seam: every recommendation reads plays here.
 */
class TasteHistory(
    private val prism: PlayEventDao,
    private val counted: CountedPlayDao,
    /** More to learn from that isn't plays (Spotify's top songs and artists, 1.8.1); null when there's none. */
    private val boost: () -> TasteBoost? = { null },
    private val imported: () -> List<ImportSource>,
) {
    private fun origins(): List<String>? = imported().takeIf { it.isNotEmpty() }?.let { listOf(CountedPlays.PRISM) + it.map { s -> s.key } }

    suspend fun tasteEventsSince(since: Long): List<PlayEventEntity> =
        origins()?.let { counted.tasteEventsSince(it, since) } ?: prism.tasteEventsSince(since)

    suspend fun tasteTopSongs(from: Long, to: Long, limit: Int): List<SongPlays> {
        val plays = origins()?.let { counted.tasteTopSongs(it, from, to, limit) } ?: prism.tasteTopSongs(from, to, limit)
        val more = boost()?.let { b -> runCatching { b.songs() }.getOrNull() }.orEmpty()
        return if (more.isEmpty()) plays else TasteBoost.mergeSongs(plays, more, limit)
    }

    suspend fun tasteTopArtists(from: Long, to: Long, limit: Int): List<ArtistPlays> {
        val plays = origins()?.let { counted.tasteTopArtists(it, from, to, limit) } ?: prism.tasteTopArtists(from, to, limit)
        val more = boost()?.let { b -> runCatching { b.artists() }.getOrNull() }.orEmpty()
        return if (more.isEmpty()) plays else TasteBoost.mergeArtists(plays, more, limit)
    }

    /** Changes whenever there are new plays to learn from (a snapshot's "anything new?" check). */
    suspend fun changeCount(now: Long): Int =
        prism.count(0, now) + (origins()?.let { o -> counted.matchedCount(o.drop(1), 0, now) } ?: 0) + (boost()?.version() ?: 0)
}

/**
 * Songs and artists the listener is into by another app's measure (Spotify's top items), counted as if they had been
 * played: added to the plays' own numbers, so a song both played here and in Spotify's top climbs, and one only in
 * the top joins the list.
 */
interface TasteBoost {
    suspend fun songs(): List<SongPlays>
    suspend fun artists(): List<ArtistPlays>
    /** Changes when what it says does. */
    fun version(): Int

    companion object {
        fun mergeSongs(plays: List<SongPlays>, more: List<SongPlays>, limit: Int): List<SongPlays> {
            val byId = LinkedHashMap<String, SongPlays>()
            plays.forEach { byId[it.songId] = it }
            more.forEach { m -> byId[m.songId] = byId[m.songId]?.let { it.copy(plays = it.plays + m.plays, ms = it.ms + m.ms) } ?: m }
            return byId.values.sortedWith(compareByDescending<SongPlays> { it.ms }.thenByDescending { it.plays }).take(limit)
        }

        fun mergeArtists(plays: List<ArtistPlays>, more: List<ArtistPlays>, limit: Int): List<ArtistPlays> {
            fun k(a: ArtistPlays) = ImportKeys.norm(a.artistName)
            val byName = LinkedHashMap<String, ArtistPlays>()
            plays.forEach { byName[k(it)] = it }
            more.forEach { m ->
                val key = k(m)
                byName[key] = byName[key]?.let { it.copy(plays = it.plays + m.plays, ms = it.ms + m.ms, thumbnail = it.thumbnail ?: m.thumbnail) } ?: m
            }
            return byName.values.sortedByDescending { it.ms }.take(limit)
        }
    }
}
