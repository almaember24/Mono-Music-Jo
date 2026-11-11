package com.prism.music.data.spotify

import com.prism.music.data.db.PrismDatabase
import com.prism.music.data.imports.HistoryImporter
import com.prism.music.data.imports.ImportKeys
import com.prism.music.data.imports.ImportSource
import com.prism.music.data.imports.ParsedPlay

/**
 * "Recent plays" (1.8.1): what the listener played in Spotify, read from its recently played list (its last 50, all
 * the API gives) every few hours, and stored as imported plays from "Spotify (live)", a source of its own: Replay
 * counts it only when switched on under "Count history from", and Remove takes it away like any import.
 *
 * Spotify doesn't say how long each was heard, so a play counts as the song's length (estimated, held to the matched
 * song's length as other estimated plays are). A play a data download already has (the same song within [TWIN_MS]) is
 * left out, and one a download brings later is taken out then ([SpotifyDao.dropLiveTwins]).
 */
class SpotifyRecent(
    private val api: SpotifyApi,
    private val db: PrismDatabase,
    private val importer: HistoryImporter,
    private val store: SpotifyStore,
) {
    /** Reads Spotify's recently played once; returns how many plays were new. */
    suspend fun sync(): Int {
        val plays = SpotifyParse.recentlyPlayed(api.get("/me/player/recently-played", mapOf("limit" to "50")))
        db.spotify().dropLiveTwins(TWIN_MS)
        val fresh = withoutTwins(plays) { key, uri, from, to -> db.spotify().downloadTimes(key, uri, from, to) }
        val session = importer.session(ImportSource.SPOTIFY_LIVE)
        fresh.forEach { session.add(toParsed(it)) }
        val stats = session.finish()
        SpotifyQueue.remember(db, fresh.map { it.track })
        store.prefs.edit().putLong(LAST_SYNC, System.currentTimeMillis()).apply()
        return stats.stored
    }

    companion object {
        /** A play and a data download's play of the same song this close (or this close to its end) are one. */
        const val TWIN_MS = 2 * 60_000L
        const val LAST_SYNC = "recent_last_sync"

        fun toParsed(p: RecentPlay) = ParsedPlay(
            timestamp = p.playedAt, playedMs = p.track.durationMs, title = p.track.title, artist = p.track.artist,
            album = p.track.album, sourceId = p.track.uri, fullPlay = p.track.durationMs > 0, estimated = true,
        )

        /**
         * [plays] without the ones a data download already has ([times]: the download's plays of a song, by its key or
         * its Spotify URI, in a window).
         */
        suspend fun withoutTwins(plays: List<RecentPlay>, times: suspend (String, String, Long, Long) -> List<Long>): List<RecentPlay> = plays.filter { p ->
            val key = ImportKeys.songKey(p.track.title, p.track.artist)
            times(key, p.track.uri, p.playedAt - TWIN_MS, p.playedAt + p.track.durationMs + TWIN_MS).isEmpty()
        }
    }
}
