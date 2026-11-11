package com.prism.music.data.db

import androidx.room.Dao
import androidx.room.DatabaseView
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A play brought in from another app's history (1.8.1, "Bring your history"): Spotify's data download, YouTube Music's
 * Google Takeout, Last.fm. Kept apart from Prism's own plays ([PlayEventEntity]) with where it came from, so Replay only
 * counts it when the listener says so and removing a source takes exactly its plays away.
 */
@Entity(
    tableName = "imported_plays",
    indices = [
        Index("songKey"), Index("timestamp"),
        // The same file brought in twice adds nothing (and queries by source use it).
        Index(value = ["origin", "timestamp", "songKey"], unique = true),
    ],
)
data class ImportedPlayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "spotify", "takeout" or "lastfm" ([com.prism.music.data.imports.ImportSource.key]). */
    val origin: String,
    /** When it was played: the end for Spotify (what it gives), the start for Takeout and Last.fm (theirs). */
    val timestamp: Long,
    /** How long it was heard. Estimated for Takeout and Last.fm (see [estimated]). */
    val playedMs: Long,
    val skipped: Boolean = false,
    /** No listen length in the source: the song's length, capped by the gap to the next play. */
    val estimated: Boolean = false,
    val title: String,
    val artist: String,
    val album: String? = null,
    /** The source's own id for the song (a Spotify URI, a YouTube video id), when it has one. */
    val sourceId: String? = null,
    /** The song as its name ([com.prism.music.data.imports.ImportKeys.songKey]): links the play to its [ImportMatchEntity]. */
    val songKey: String,
    /** The YouTube Music song it was matched to; null until it's matched (or when it can't be). */
    val matchedId: String? = null,
)

/**
 * One song from an imported history or library and the YouTube Music song it stands for (1.8.1): matched once, then
 * remembered, so a song is never matched twice and the listener's own picks stay. Keyed by the song's name, so the same
 * song from Spotify, Last.fm and a playlist shares one match.
 */
@Entity(tableName = "import_matches", indices = [Index("status")])
data class ImportMatchEntity(
    @PrimaryKey val songKey: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    /** The song's exact length when the source shows it (a Spotify play that ran to the end), else 0. */
    val durationMs: Long = 0,
    /** The longest any play of it ran: the song is at least this long. */
    val atLeastMs: Long = 0,
    val sourceId: String? = null,
    /** Kept for the sources that know it (Spotify's own data, later); none of today's files have it. */
    val isrc: String? = null,
    /** The YouTube video the source names itself (Google Takeout): no search needed to know which song it is. */
    val youtubeId: String? = null,
    /** [com.prism.music.data.imports.MatchStatus] by name. */
    val status: String,
    val matchedId: String? = null,
    /** Up to three candidates for "Needs a look", as JSON songs. */
    val candidates: String? = null,
    val checkedAt: Long = 0,
    val tries: Int = 0,
)

/**
 * Every play Replay can count: Prism's own ("prism") and the imported ones by source. Imported plays shorter than 10 s
 * don't count, as Prism doesn't record those of its own. An imported play's song id is null until it's matched: it
 * counts towards time and plays then, but no song list can show it. A play whose length was only estimated (Takeout,
 * Last.fm) counts once its song is known, its length then held to the song's.
 */
@DatabaseView(viewName = "counted_plays", value = COUNTED_PLAYS_SQL)
data class CountedPlay(
    val origin: String,
    val songId: String?,
    val timestamp: Long,
    val playedMs: Long,
    val source: String?,
    val excluded: Boolean,
)

/** [CountedPlay]'s query: the migration that made the view uses it too (Room checks the view's text matches). */
const val COUNTED_PLAYS_SQL = """SELECT 'prism' AS origin, songId, timestamp, playedMs, source, excluded FROM play_events
UNION ALL
SELECT origin, matchedId AS songId, timestamp, playedMs, origin AS source, 0 AS excluded FROM imported_plays WHERE playedMs >= 10000 AND (matchedId IS NOT NULL OR estimated = 0)"""

/** A source's imported history at a glance. */
data class ImportSummary(val origin: String, val plays: Int, val firstAt: Long?, val lastAt: Long?, val matched: Int, val ms: Long)

/** How many songs are in each state of matching. */
data class StatusCount(val status: String, val n: Int)

/** A Prism play with its song's name, to tell an imported play that is really one of Prism's own. */
data class PrismPlayName(val songId: String, val timestamp: Long, val title: String, val primaryArtist: String, val artistName: String)

@Dao
interface ImportDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlays(plays: List<ImportedPlayEntity>): List<Long>

    @Query("SELECT * FROM import_matches WHERE songKey IN (:keys)")
    suspend fun matches(keys: List<String>): List<ImportMatchEntity>

    @Query("SELECT * FROM import_matches WHERE songKey = :key")
    suspend fun match(key: String): ImportMatchEntity?

    @Upsert
    suspend fun putMatches(m: List<ImportMatchEntity>)

    /** Songs to match now: never tried, or not found a week ago ([before]). Plays first, so Replay fills in soonest. */
    @Query(
        """SELECT m.* FROM import_matches m WHERE m.status = 'PENDING' OR (m.status = 'NONE' AND m.checkedAt < :before)
           ORDER BY m.status DESC, (SELECT COUNT(*) FROM imported_plays p WHERE p.songKey = m.songKey) DESC LIMIT :limit"""
    )
    suspend fun toMatch(before: Long, limit: Int): List<ImportMatchEntity>

    @Query("SELECT COUNT(*) FROM import_matches WHERE status = 'PENDING' OR (status = 'NONE' AND checkedAt < :before)")
    suspend fun toMatchCount(before: Long): Int

    @Query("SELECT * FROM import_matches WHERE status = 'REVIEW' ORDER BY title")
    fun reviewFlow(): Flow<List<ImportMatchEntity>>

    @Query("SELECT status, COUNT(*) AS n FROM import_matches GROUP BY status")
    fun statusFlow(): Flow<List<StatusCount>>

    @Query("SELECT status, COUNT(*) AS n FROM import_matches GROUP BY status")
    suspend fun statusCounts(): List<StatusCount>

    /** Every play of [key] gets its song (or loses it: [id] null). */
    @Query("UPDATE imported_plays SET matchedId = :id WHERE songKey = :key")
    suspend fun setPlaysMatched(key: String, id: String?)

    /** Plays whose length was estimated never run longer than the song itself. */
    @Query("UPDATE imported_plays SET playedMs = MIN(playedMs, :songMs) WHERE songKey = :key AND estimated = 1 AND :songMs > 0")
    suspend fun capEstimated(key: String, songMs: Long)

    /** [capEstimated] for every matched play from [origin] whose song's length is known. */
    @Query(
        """UPDATE imported_plays SET playedMs = MIN(playedMs, (SELECT s.durationSec * 1000 FROM songs s WHERE s.id = imported_plays.matchedId))
           WHERE origin = :origin AND estimated = 1 AND matchedId IS NOT NULL
           AND (SELECT s.durationSec FROM songs s WHERE s.id = imported_plays.matchedId) > 0"""
    )
    suspend fun capEstimatedAll(origin: String)

    /** New plays of songs matched before get their song at once. */
    @Query(
        """UPDATE imported_plays SET matchedId = (SELECT m.matchedId FROM import_matches m WHERE m.songKey = imported_plays.songKey AND m.status IN ('SURE', 'FIXED'))
           WHERE origin = :origin AND matchedId IS NULL"""
    )
    suspend fun linkKnown(origin: String)

    @Query(
        """SELECT origin, COUNT(*) AS plays, MIN(timestamp) AS firstAt, MAX(timestamp) AS lastAt,
           SUM(CASE WHEN matchedId IS NOT NULL THEN 1 ELSE 0 END) AS matched, COALESCE(SUM(playedMs), 0) AS ms
           FROM imported_plays GROUP BY origin"""
    )
    fun summariesFlow(): Flow<List<ImportSummary>>

    @Query(
        """SELECT origin, COUNT(*) AS plays, MIN(timestamp) AS firstAt, MAX(timestamp) AS lastAt,
           SUM(CASE WHEN matchedId IS NOT NULL THEN 1 ELSE 0 END) AS matched, COALESCE(SUM(playedMs), 0) AS ms
           FROM imported_plays GROUP BY origin"""
    )
    suspend fun summaries(): List<ImportSummary>

    @Query("DELETE FROM imported_plays WHERE origin = :origin")
    suspend fun deletePlays(origin: String): Int

    /** The newest play [origin] brought (a repeat Last.fm import asks only for scrobbles since). */
    @Query("SELECT MAX(timestamp) FROM imported_plays WHERE origin = :origin")
    suspend fun newest(origin: String): Long?

    /** When [key] was played in a Google Takeout history, between two times (YouTube Music (live) leaves those out). */
    @Query("SELECT timestamp FROM imported_plays WHERE origin = 'takeout' AND songKey = :key AND timestamp BETWEEN :from AND :to")
    suspend fun takeoutTimes(key: String, from: Long, to: Long): List<Long>

    /**
     * Takes away YouTube Music (live) plays a Takeout brought later (1.8.1): the same song within [slackMs] of the live
     * play's estimated time, and no later than the Takeout's own newest play (a live play after it isn't in the Takeout).
     */
    @Query(
        """DELETE FROM imported_plays WHERE origin = 'youtube_live'
           AND timestamp <= (SELECT MAX(t.timestamp) FROM imported_plays t WHERE t.origin = 'takeout')
           AND EXISTS (SELECT 1 FROM imported_plays d WHERE d.origin = 'takeout' AND d.songKey = imported_plays.songKey
               AND d.timestamp BETWEEN imported_plays.timestamp - :slackMs AND imported_plays.timestamp + :slackMs)"""
    )
    suspend fun dropYouTubeLiveTwins(slackMs: Long): Int

    /** Matches no imported play refers to (the listener's own picks aside, kept for a later import). */
    @Query("SELECT songKey FROM import_matches WHERE status != 'FIXED' AND songKey NOT IN (SELECT DISTINCT songKey FROM imported_plays)")
    suspend fun orphanMatches(): List<String>

    @Query("DELETE FROM import_matches WHERE songKey IN (:keys)")
    suspend fun deleteMatches(keys: List<String>): Int

    /** Prism's own plays between two times with their songs' names (to leave out Takeout and Last.fm plays that are Prism's). */
    @Query(
        """SELECT e.songId AS songId, e.timestamp AS timestamp, s.title AS title, s.primaryArtist AS primaryArtist, s.artistName AS artistName
           FROM play_events e JOIN songs s ON s.id = e.songId WHERE e.timestamp BETWEEN :from AND :to"""
    )
    suspend fun prismPlays(from: Long, to: Long): List<PrismPlayName>

    @Query("SELECT COUNT(*) FROM imported_plays WHERE matchedId IS NOT NULL AND playedMs >= 10000 AND origin IN (:origins)")
    suspend fun countedCount(origins: List<String>): Int
}

/**
 * Replay's and Rewind's numbers over [CountedPlay]: the same questions as [PlayEventDao]'s, asked of the sources in
 * `o` ("prism" and the imported ones the listener counts). With only "prism" each answers exactly what PlayEventDao's
 * does; Replay asks PlayEventDao itself then ([com.prism.music.data.imports.PrismPlays]).
 */
@Dao
interface CountedPlayDao {
    @Query("SELECT COALESCE(SUM(playedMs),0) FROM counted_plays WHERE origin IN (:o) AND timestamp BETWEEN :from AND :to")
    suspend fun totalMs(o: List<String>, from: Long, to: Long): Long

    @Query("SELECT COUNT(*) FROM counted_plays WHERE origin IN (:o) AND timestamp BETWEEN :from AND :to")
    suspend fun count(o: List<String>, from: Long, to: Long): Int

    @Query(
        """SELECT e.songId AS songId, COUNT(*) AS plays, SUM(e.playedMs) AS ms, s.title AS title,
           s.artistName AS artistName, s.thumbnail AS thumbnail, s.albumTitle AS albumTitle,
           s.durationSec AS durationSec, s.artistId AS artistId, s.primaryArtist AS primaryArtist
           FROM counted_plays e JOIN songs s ON s.id = e.songId
           WHERE e.origin IN (:o) AND e.timestamp BETWEEN :from AND :to
           GROUP BY e.songId ORDER BY ms DESC, plays DESC LIMIT :limit"""
    )
    suspend fun topSongs(o: List<String>, from: Long, to: Long, limit: Int): List<SongPlays>

    @Query(
        """SELECT MAX(s.primaryArtist) AS artistName, COUNT(*) AS plays, SUM(e.playedMs) AS ms,
           MAX(s.thumbnail) AS thumbnail, MAX(s.artistId) AS artistId
           FROM counted_plays e JOIN songs s ON s.id = e.songId
           WHERE e.origin IN (:o) AND e.timestamp BETWEEN :from AND :to AND s.primaryArtist != ''
           GROUP BY s.primaryArtist ORDER BY ms DESC LIMIT :limit"""
    )
    suspend fun topArtists(o: List<String>, from: Long, to: Long, limit: Int): List<ArtistPlays>

    @Query(
        """SELECT strftime('%Y-%m', timestamp / 1000, 'unixepoch', 'localtime') AS month, SUM(playedMs) AS ms
           FROM counted_plays WHERE origin IN (:o) AND timestamp BETWEEN :from AND :to GROUP BY month ORDER BY month"""
    )
    suspend fun monthly(o: List<String>, from: Long, to: Long): List<MonthTotal>

    @Query("SELECT COUNT(DISTINCT strftime('%Y-%m-%d', timestamp / 1000, 'unixepoch', 'localtime')) FROM counted_plays WHERE origin IN (:o) AND timestamp BETWEEN :from AND :to")
    suspend fun activeDays(o: List<String>, from: Long, to: Long): Int

    @Query("SELECT MIN(timestamp) FROM counted_plays WHERE origin IN (:o)")
    suspend fun firstEvent(o: List<String>): Long?

    @Query(
        """SELECT 0 AS id, COALESCE(songId, '') AS songId, timestamp, playedMs, source, excluded FROM counted_plays
           WHERE origin IN (:o) AND timestamp BETWEEN :from AND :to ORDER BY timestamp"""
    )
    suspend fun events(o: List<String>, from: Long, to: Long): List<PlayEventEntity>

    @Query("SELECT COUNT(DISTINCT songId) FROM counted_plays WHERE origin IN (:o) AND timestamp BETWEEN :from AND :to")
    suspend fun songCount(o: List<String>, from: Long, to: Long): Int

    @Query("SELECT COUNT(DISTINCT s.primaryArtist) FROM counted_plays e JOIN songs s ON s.id = e.songId WHERE e.origin IN (:o) AND e.timestamp BETWEEN :from AND :to AND s.primaryArtist != ''")
    suspend fun artistCount(o: List<String>, from: Long, to: Long): Int

    @Query(
        """SELECT MAX(s.primaryArtist) AS artistName, COUNT(*) AS plays, SUM(e.playedMs) AS ms,
           MAX(s.thumbnail) AS thumbnail, MAX(s.artistId) AS artistId
           FROM counted_plays e JOIN songs s ON s.id = e.songId
           WHERE e.origin IN (:o) AND e.timestamp BETWEEN :from AND :to AND s.primaryArtist != '' AND s.primaryArtist IN (
               SELECT s2.primaryArtist FROM counted_plays e2 JOIN songs s2 ON s2.id = e2.songId WHERE e2.origin IN (:o)
               GROUP BY s2.primaryArtist HAVING MIN(e2.timestamp) BETWEEN :from AND :to)
           GROUP BY s.primaryArtist ORDER BY ms DESC"""
    )
    suspend fun newArtists(o: List<String>, from: Long, to: Long): List<ArtistPlays>

    @Query(
        """SELECT s.albumTitle AS albumTitle, COALESCE(NULLIF(MAX(s.primaryArtist), ''), MAX(s.artistName)) AS artistName,
           COUNT(*) AS plays, SUM(e.playedMs) AS ms, COUNT(DISTINCT e.songId) AS songs, MAX(s.thumbnail) AS thumbnail
           FROM counted_plays e JOIN songs s ON s.id = e.songId
           WHERE e.origin IN (:o) AND e.timestamp BETWEEN :from AND :to AND s.albumTitle IS NOT NULL AND s.albumTitle != ''
           GROUP BY s.albumTitle, s.primaryArtist ORDER BY ms DESC LIMIT :limit"""
    )
    suspend fun topAlbums(o: List<String>, from: Long, to: Long, limit: Int): List<AlbumPlays>

    @Query(
        """SELECT e.songId AS songId, s.title AS title, s.artistName AS artistName, s.thumbnail AS thumbnail, e.timestamp AS timestamp
           FROM counted_plays e JOIN songs s ON s.id = e.songId
           WHERE e.origin IN (:o) AND e.timestamp BETWEEN :from AND :to ORDER BY e.timestamp LIMIT 1"""
    )
    suspend fun firstPlay(o: List<String>, from: Long, to: Long): FirstPlay?

    @Query("SELECT source AS source, COUNT(*) AS plays, SUM(playedMs) AS ms FROM counted_plays WHERE origin IN (:o) AND timestamp BETWEEN :from AND :to GROUP BY source")
    suspend fun bySource(o: List<String>, from: Long, to: Long): List<SourcePlays>

    // Taste (mixes, radio, Quick picks): only plays matched to a song, and none the listener excluded.

    @Query(
        """SELECT e.songId AS songId, COUNT(*) AS plays, SUM(e.playedMs) AS ms, s.title AS title,
           s.artistName AS artistName, s.thumbnail AS thumbnail, s.albumTitle AS albumTitle,
           s.durationSec AS durationSec, s.artistId AS artistId, s.primaryArtist AS primaryArtist
           FROM counted_plays e JOIN songs s ON s.id = e.songId
           WHERE e.origin IN (:o) AND e.timestamp BETWEEN :from AND :to AND e.excluded = 0
           GROUP BY e.songId ORDER BY ms DESC, plays DESC LIMIT :limit"""
    )
    suspend fun tasteTopSongs(o: List<String>, from: Long, to: Long, limit: Int): List<SongPlays>

    @Query(
        """SELECT MAX(s.primaryArtist) AS artistName, COUNT(*) AS plays, SUM(e.playedMs) AS ms,
           MAX(s.thumbnail) AS thumbnail, MAX(s.artistId) AS artistId
           FROM counted_plays e JOIN songs s ON s.id = e.songId
           WHERE e.origin IN (:o) AND e.timestamp BETWEEN :from AND :to AND e.excluded = 0 AND s.primaryArtist != ''
           GROUP BY s.primaryArtist ORDER BY ms DESC LIMIT :limit"""
    )
    suspend fun tasteTopArtists(o: List<String>, from: Long, to: Long, limit: Int): List<ArtistPlays>

    @Query(
        """SELECT 0 AS id, songId, timestamp, playedMs, source, excluded FROM counted_plays
           WHERE origin IN (:o) AND timestamp > :since AND excluded = 0 AND songId IS NOT NULL ORDER BY timestamp"""
    )
    suspend fun tasteEventsSince(o: List<String>, since: Long): List<PlayEventEntity>

    @Query("SELECT COUNT(*) FROM counted_plays WHERE origin IN (:o) AND timestamp BETWEEN :from AND :to AND songId IS NOT NULL")
    suspend fun matchedCount(o: List<String>, from: Long, to: Long): Int

    /** How many times a song's been played, as Replay counts it (#211's "Your plays"). */
    @Query("SELECT COUNT(*) FROM counted_plays WHERE origin IN (:o) AND songId = :id")
    suspend fun songPlays(o: List<String>, id: String): Int

    // An artist's page (1.8.2): their songs by the artist's id or their main artist's name, as Replay names artists.

    @Query(
        """SELECT e.songId AS songId, COUNT(*) AS plays, SUM(e.playedMs) AS ms, s.title AS title,
           s.artistName AS artistName, s.thumbnail AS thumbnail, s.albumTitle AS albumTitle,
           s.durationSec AS durationSec, s.artistId AS artistId, s.primaryArtist AS primaryArtist
           FROM counted_plays e JOIN songs s ON s.id = e.songId
           WHERE e.origin IN (:o) AND (s.artistId = :id OR (:name != '' AND s.primaryArtist = :name COLLATE NOCASE))
           GROUP BY e.songId ORDER BY ms DESC, plays DESC"""
    )
    suspend fun artistSongs(o: List<String>, id: String, name: String): List<SongPlays>

    /** [artistSongs] without the plays the listener left out of their taste. */
    @Query(
        """SELECT e.songId AS songId, COUNT(*) AS plays, SUM(e.playedMs) AS ms, s.title AS title,
           s.artistName AS artistName, s.thumbnail AS thumbnail, s.albumTitle AS albumTitle,
           s.durationSec AS durationSec, s.artistId AS artistId, s.primaryArtist AS primaryArtist
           FROM counted_plays e JOIN songs s ON s.id = e.songId
           WHERE e.origin IN (:o) AND e.excluded = 0 AND (s.artistId = :id OR (:name != '' AND s.primaryArtist = :name COLLATE NOCASE))
           GROUP BY e.songId ORDER BY ms DESC, plays DESC"""
    )
    suspend fun artistTasteSongs(o: List<String>, id: String, name: String): List<SongPlays>

    @Query(
        """SELECT MIN(e.timestamp) FROM counted_plays e JOIN songs s ON s.id = e.songId
           WHERE e.origin IN (:o) AND (s.artistId = :id OR (:name != '' AND s.primaryArtist = :name COLLATE NOCASE))"""
    )
    suspend fun artistFirstPlay(o: List<String>, id: String, name: String): Long?
}
