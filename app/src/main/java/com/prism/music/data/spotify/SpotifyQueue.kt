package com.prism.music.data.spotify

import com.prism.music.data.db.ImportMatchEntity
import com.prism.music.data.db.PrismDatabase
import com.prism.music.data.imports.ImportKeys
import com.prism.music.data.imports.MatchStatus
import com.prism.music.data.model.Song

/**
 * Spotify's songs in Bring your history's matching (1.8.1): each one gets its line in import_matches (one per song, shared
 * with the data download's, so a song is matched once whichever way it came), with what Spotify knows that a download
 * doesn't: its exact length and its ISRC, which [com.prism.music.data.imports.TrackMatcher] searches first.
 */
object SpotifyQueue {
    fun key(t: SpotifyTrack) = ImportKeys.songKey(t.title, t.artist)

    /** Lines [tracks] up for matching; songs already there get Spotify's length and ISRC if they hadn't them. */
    suspend fun remember(db: PrismDatabase, tracks: List<SpotifyTrack>) {
        val dao = db.imports()
        tracks.filter { it.artist.isNotBlank() }.distinctBy(::key).chunked(500).forEach { chunk ->
            val known = dao.matches(chunk.map(::key)).associateBy { it.songKey }
            val rows = chunk.mapNotNull { t ->
                val k = key(t)
                val old = known[k]
                if (old == null) {
                    ImportMatchEntity(
                        k, t.title.take(MAX), t.artist.take(MAX), t.album?.take(MAX), durationMs = t.durationMs, sourceId = t.uri,
                        isrc = t.isrc, status = MatchStatus.PENDING.name,
                    )
                } else {
                    val gotIsrc = old.isrc == null && t.isrc != null
                    val gotLength = old.durationMs <= 0 && t.durationMs > 0
                    if (!gotIsrc && !gotLength && old.sourceId != null) return@mapNotNull null
                    old.copy(
                        isrc = old.isrc ?: t.isrc, durationMs = if (gotLength) t.durationMs else old.durationMs, sourceId = old.sourceId ?: t.uri,
                        // Nothing was found by name: with an ISRC now, it's worth another look.
                        status = if (gotIsrc && MatchStatus.of(old.status) == MatchStatus.NONE) MatchStatus.PENDING.name else old.status,
                    )
                }
            }
            if (rows.isNotEmpty()) dao.putMatches(rows)
        }
    }

    /** The YouTube Music songs [keys] were matched to (sure or picked by the listener), by key. */
    suspend fun matched(db: PrismDatabase, keys: Collection<String>): Map<String, Song> {
        val ids = keys.distinct().chunked(500).flatMap { db.imports().matches(it) }
            .filter { MatchStatus.of(it.status).matched && it.matchedId != null }.associate { it.songKey to it.matchedId!! }
        val songs = ids.values.distinct().chunked(500).flatMap { db.songs().getAll(it) }.associateBy { it.id }
        return ids.mapNotNull { (k, id) -> songs[id]?.let { k to it.toSong() } }.toMap()
    }

    /** Of [keys], those not looked up yet. */
    suspend fun pending(db: PrismDatabase, keys: Collection<String>): Set<String> =
        keys.distinct().chunked(500).flatMap { db.imports().matches(it) }.filter { MatchStatus.of(it.status) == MatchStatus.PENDING }.map { it.songKey }.toSet()

    private const val MAX = 300
}
