package com.prism.music.data.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.model.UNKNOWN_ARTIST
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artistName: String,
    val artistId: String?,
    val albumTitle: String?,
    val albumId: String?,
    val durationSec: Int,
    val thumbnail: String?,
    val isVideo: Boolean = false,
    val liked: Boolean = false,
    val likedAt: Long = 0,
    val lastPlayed: Long = 0,
    val smartDownload: Boolean = false,
    /**
     * The song's main artist, which Replay, Rewind, Blend and the mixes count it under ("The Kid LAROI" for "The Kid
     * LAROI, Justin Bieber"). "" when it has none (a file without an artist tag): never counted as an artist.
     */
    @ColumnInfo(defaultValue = "") val primaryArtist: String = "",
    /** Every credited artist as JSON, so a name with a comma in it ("Tyler, The Creator") stays whole. Null on rows from before v8. */
    val artistsJson: String? = null,
) {
    fun toSong() = Song(
        id = id,
        title = title,
        artists = storedArtists(artistsJson) ?: legacyArtists(),
        album = albumTitle?.let { AlbumRef(it, albumId) },
        durationSec = durationSec,
        thumbnail = thumbnail,
        isVideo = isVideo,
    )

    /** A row from before v8: the display text split up again ("Unknown artist" on its own is no artist). */
    private fun legacyArtists(): List<ArtistRef> =
        if (artistName == UNKNOWN_ARTIST && (artistId == null || id.startsWith(Song.LOCAL_PREFIX))) emptyList()
        else artistName.split(", ").filter { it.isNotBlank() }.mapIndexed { i, n -> ArtistRef(n, if (i == 0) artistId else null) }
}

private val artistsJsonFormat = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
private val artistListSerializer = kotlinx.serialization.builtins.ListSerializer(ArtistRef.serializer())

private fun storedArtists(json: String?): List<ArtistRef>? =
    json?.let { runCatching { artistsJsonFormat.decodeFromString(artistListSerializer, it) }.getOrNull() }

fun Song.toEntity(base: SongEntity? = null) = SongEntity(
    id = id,
    title = title,
    artistName = artistText,
    primaryArtist = primaryArtist,
    artistsJson = artistsJsonFormat.encodeToString(artistListSerializer, artists),
    artistId = artists.firstOrNull()?.id,
    albumTitle = album?.title,
    albumId = album?.id,
    durationSec = durationSec.takeIf { it > 0 } ?: (base?.durationSec ?: 0),
    thumbnail = thumbnail ?: base?.thumbnail,
    isVideo = isVideo,
    liked = base?.liked ?: false,
    likedAt = base?.likedAt ?: 0,
    lastPlayed = base?.lastPlayed ?: 0,
    smartDownload = base?.smartDownload ?: false,
)

@Entity(tableName = "play_events", indices = [Index("songId"), Index("timestamp")])
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String,
    val timestamp: Long,
    val playedMs: Long,
    /** What the song was playing as part of: a [com.prism.music.playback.QueueKind] name, or a feature ("smart", "dj", "sing", "blend", "mix:<id>"). */
    val source: String? = null,
    /** From something the listener excluded from their taste profile: Replay counts it, recommendations don't. */
    @ColumnInfo(defaultValue = "0") val excluded: Boolean = false,
)

@Entity(tableName = "lyrics", primaryKeys = ["songId", "source"])
data class LyricsEntity(
    val songId: String,
    val source: String,
    val synced: Boolean,
    val content: String,
    val fetchedAt: Long,
)

@Entity(tableName = "genres")
data class GenreEntity(
    @PrimaryKey val key: String, // "song:<id>", "album:<title|artist>", "artist:<name>"
    val genre: String?,
    val fetchedAt: Long,
)

@Entity(tableName = "motion_art")
data class MotionArtEntity(
    @PrimaryKey val key: String,
    val tallUrl: String?,
    val squareUrl: String?,
    val fetchedAt: Long,
)

/** YouTube's "Most replayed" curve for a video ([com.prism.music.data.Heatmap]); empty [intensity] = it has none. */
@Entity(tableName = "heatmaps")
data class HeatmapEntity(
    @PrimaryKey val videoId: String,
    val segmentMs: Long,
    val intensity: String,
    val fetchedAt: Long,
)

/** A song's lyrics in Latin letters ([com.prism.music.data.lyrics.Romanizer]), keyed by song, lyrics and engine. */
@Entity(tableName = "romanized")
data class RomanizedEntity(
    @PrimaryKey val key: String,
    val content: String,
    val fetchedAt: Long,
)

/**
 * A release on one of the listener's artists' pages (Release Radar): albums, singles and EPs, as first seen there.
 * [baseline]: there when the artist was first looked at (their back catalogue, never announced as new).
 */
@Entity(tableName = "releases", indices = [Index("artistId")])
data class ReleaseEntity(
    /** YouTube's browse id for it (MPREb_…). */
    @PrimaryKey val id: String,
    val artistId: String,
    val artistName: String,
    val title: String,
    /** "Album", "Single" or "EP". */
    val kind: String,
    val year: Int,
    /** "2026-10-16" when Deezer knows it; null until then (or when it doesn't). */
    val releaseDate: String?,
    val thumbnail: String?,
    val playlistId: String?,
    val firstSeenAt: Long,
    val baseline: Boolean,
    @ColumnInfo(defaultValue = "0") val notifiedAt: Long = 0,
)

/** A mix Prism made for the listener (Daily Mix, the time-of-day mix, Release Radar): kept so it's the same all day, offline too. */
@Entity(tableName = "mixes")
data class MixEntity(
    /** "daily:1"…"daily:6", "time", "radar". */
    @PrimaryKey val id: String,
    val title: String,
    val subtitle: String,
    /** The artists it's built around (for its cover and the subtitle), "|"-separated. */
    val seedArtists: String,
    /** Accent color for its cover (ARGB). */
    val color: Int,
    val createdAt: Long,
    /** The songs, as JSON ([com.prism.music.data.foryou.MixSongs]). */
    val songsJson: String,
)

/**
 * A Blend with a friend: their taste code as they sent it, and ours as it was then (what "Send yours back" sends, so
 * both phones work the Blend out from the same two codes). Keyed by the friend's random Blend id.
 */
@Entity(tableName = "blends")
data class BlendEntity(
    @PrimaryKey val friendWho: String,
    val friendName: String,
    val friendCode: String,
    val myCode: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * A song recognized by listening (or waiting to be: [status] "pending" keeps its [signature] to send once online).
 * Only the fingerprint is ever kept or sent, never the sound.
 */
@Entity(tableName = "recognitions")
data class RecognitionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    /** "found", "pending" (offline: asked again when there's a network) or "none" (no match). */
    val status: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val cover: String? = null,
    val shazamKey: String? = null,
    /** The song on YouTube Music it was matched to (null when none was found). */
    val videoId: String? = null,
    /** The fingerprint (Base64), while pending. */
    val signature: String? = null,
)

@kotlinx.serialization.Serializable
data class SongPlays(
    val songId: String,
    val plays: Int,
    val ms: Long,
    val title: String,
    val artistName: String,
    val thumbnail: String?,
    val albumTitle: String?,
    val durationSec: Int,
    val artistId: String?,
    /** The song's main artist ("" for none); empty in Rewind snapshots saved before v8, so read [mainArtist]. */
    val primaryArtist: String = "",
)

/** The artist a played song counts under: its main artist (older snapshots: the first name of its credit). */
val SongPlays.mainArtist: String
    get() = primaryArtist.ifBlank { if (artistName == UNKNOWN_ARTIST) "" else artistName.substringBefore(", ") }

/**
 * One entry per song however many uploads of it were played (YouTube often has two "Beat It"s: the album's and a
 * single's): same title without its brackets, same main artist. The most played upload stands for the song, with the
 * plays and time of all of them; most listened first, as the query orders them.
 */
fun List<SongPlays>.mergeUploads(): List<SongPlays> {
    fun key(s: SongPlays) = s.title.lowercase().replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "").replace(Regex("\\s+"), " ").trim() +
        "|" + s.mainArtist.lowercase()
    return groupBy { if (it.mainArtist.isBlank()) it.songId else key(it) }.values
        .map { same -> same.maxBy { it.ms }.copy(plays = same.sumOf { it.plays }, ms = same.sumOf { it.ms }) }
        .sortedWith(compareByDescending<SongPlays> { it.ms }.thenByDescending { it.plays })
}

@kotlinx.serialization.Serializable
data class ArtistPlays(val artistName: String, val plays: Int, val ms: Long, val thumbnail: String?, val artistId: String?)
@kotlinx.serialization.Serializable
data class MonthTotal(val month: String, val ms: Long)
/** An album's listening (by its title and the songs' main artist); [songs] is how many of its songs were played. */
@kotlinx.serialization.Serializable
data class AlbumPlays(val albumTitle: String, val artistName: String, val plays: Int, val ms: Long, val songs: Int, val thumbnail: String?)
/** The first song played in a stretch of time, and when. */
@kotlinx.serialization.Serializable
data class FirstPlay(val songId: String, val title: String, val artistName: String, val thumbnail: String?, val timestamp: Long)
/** Plays by what they played as part of ([PlayEventEntity.source]). */
data class SourcePlays(val source: String?, val plays: Int, val ms: Long)

@Dao
interface SongDao {
    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun get(id: String): SongEntity?

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun getAll(ids: List<String>): List<SongEntity>

    @Upsert
    suspend fun upsert(song: SongEntity)

    @Upsert
    suspend fun upsertAll(songs: List<SongEntity>)

    @Query("SELECT * FROM songs WHERE liked = 1 ORDER BY likedAt DESC")
    fun likedFlow(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE liked = 1 ORDER BY likedAt DESC")
    suspend fun liked(): List<SongEntity>

    /** Home's "Liked this month" (1.7.6). */
    @Query("SELECT * FROM songs WHERE liked = 1 AND likedAt >= :since ORDER BY likedAt DESC")
    suspend fun likedSince(since: Long): List<SongEntity>

    @Query("SELECT id FROM songs WHERE liked = 1")
    fun likedIdsFlow(): Flow<List<String>>

    @Query("UPDATE songs SET liked = :liked, likedAt = :at WHERE id = :id")
    suspend fun setLiked(id: String, liked: Boolean, at: Long)

    @Query("UPDATE songs SET lastPlayed = :at WHERE id = :id")
    suspend fun touch(id: String, at: Long)

    @Query("SELECT * FROM songs WHERE lastPlayed > 0 ORDER BY lastPlayed DESC LIMIT :limit")
    fun recentFlow(limit: Int = 30): Flow<List<SongEntity>>

    /** The phone's own files, most recently played first (Local files → Recently played). */
    @Query("SELECT id FROM songs WHERE lastPlayed > 0 AND id LIKE 'local:%' ORDER BY lastPlayed DESC LIMIT :limit")
    fun recentLocalIdsFlow(limit: Int = 100): Flow<List<String>>

    @Query("UPDATE songs SET smartDownload = :v WHERE id = :id")
    suspend fun setSmart(id: String, v: Boolean)

    @Query("SELECT * FROM songs WHERE smartDownload = 1")
    suspend fun smartDownloads(): List<SongEntity>

    /**
     * Liked or played songs that may be by the artist with this id (their id in the row, or among its credited artists'):
     * the caller reads the row's artists to be sure. For an artist's page without a network (1.8.2).
     */
    @Query("SELECT * FROM songs WHERE (liked = 1 OR lastPlayed > 0) AND (artistId = :id OR artistsJson LIKE '%' || :id || '%') ORDER BY liked DESC, lastPlayed DESC LIMIT :limit")
    suspend fun mineWithArtistId(id: String, limit: Int = 1000): List<SongEntity>

    /** The same by the artist's name, which can sit anywhere in the row's artist text: the caller checks here too. */
    @Query("SELECT * FROM songs WHERE (liked = 1 OR lastPlayed > 0) AND artistName LIKE '%' || :name || '%' ORDER BY liked DESC, lastPlayed DESC LIMIT :limit")
    suspend fun mineWithArtistName(name: String, limit: Int = 1000): List<SongEntity>

    /** Liked or played songs of one album. */
    @Query("SELECT * FROM songs WHERE (liked = 1 OR lastPlayed > 0) AND albumId = :id ORDER BY liked DESC, lastPlayed DESC")
    suspend fun mineOnAlbum(id: String): List<SongEntity>
}

/** One play in Library → History (#118): when, for how long, and the song. */
data class HistoryRow(
    val playedAt: Long,
    val playedFor: Long,
    @androidx.room.Embedded val song: SongEntity,
)

@Dao
interface PlayEventDao {
    @Insert
    suspend fun insert(e: PlayEventEntity)

    /** The latest [limit] plays on this phone of at least [minMs], newest first, each with its song (Library → History, #118). */
    @Query(
        """SELECT e.timestamp AS playedAt, e.playedMs AS playedFor, s.* FROM play_events e JOIN songs s ON s.id = e.songId
           WHERE e.playedMs >= :minMs ORDER BY e.timestamp DESC LIMIT :limit"""
    )
    fun historyFlow(limit: Int, minMs: Long): Flow<List<HistoryRow>>

    @Query("SELECT * FROM play_events WHERE timestamp > :since")
    suspend fun eventsSince(since: Long): List<PlayEventEntity>

    @Query("SELECT COALESCE(SUM(playedMs),0) FROM play_events WHERE timestamp BETWEEN :from AND :to")
    suspend fun totalMs(from: Long, to: Long): Long

    @Query("SELECT COUNT(*) FROM play_events WHERE timestamp BETWEEN :from AND :to")
    suspend fun count(from: Long, to: Long): Int

    @Query(
        """SELECT e.songId AS songId, COUNT(*) AS plays, SUM(e.playedMs) AS ms, s.title AS title,
           s.artistName AS artistName, s.thumbnail AS thumbnail, s.albumTitle AS albumTitle,
           s.durationSec AS durationSec, s.artistId AS artistId, s.primaryArtist AS primaryArtist
           FROM play_events e JOIN songs s ON s.id = e.songId
           WHERE e.timestamp BETWEEN :from AND :to
           GROUP BY e.songId ORDER BY ms DESC, plays DESC LIMIT :limit"""
    )
    suspend fun topSongs(from: Long, to: Long, limit: Int): List<SongPlays>

    /**
     * Artists by listening, each counted under a song's main artist (a joint credit isn't an artist of its own), and
     * songs without an artist (untagged files) left out.
     */
    @Query(
        """SELECT MAX(s.primaryArtist) AS artistName, COUNT(*) AS plays, SUM(e.playedMs) AS ms,
           MAX(s.thumbnail) AS thumbnail, MAX(s.artistId) AS artistId
           FROM play_events e JOIN songs s ON s.id = e.songId
           WHERE e.timestamp BETWEEN :from AND :to AND s.primaryArtist != ''
           GROUP BY s.primaryArtist ORDER BY ms DESC LIMIT :limit"""
    )
    suspend fun topArtists(from: Long, to: Long, limit: Int): List<ArtistPlays>

    @Query(
        """SELECT strftime('%Y-%m', timestamp / 1000, 'unixepoch', 'localtime') AS month, SUM(playedMs) AS ms
           FROM play_events WHERE timestamp BETWEEN :from AND :to GROUP BY month ORDER BY month"""
    )
    suspend fun monthly(from: Long, to: Long): List<MonthTotal>

    @Query("SELECT COUNT(DISTINCT strftime('%Y-%m-%d', timestamp / 1000, 'unixepoch', 'localtime')) FROM play_events WHERE timestamp BETWEEN :from AND :to")
    suspend fun activeDays(from: Long, to: Long): Int

    @Query("SELECT MIN(timestamp) FROM play_events")
    suspend fun firstEvent(): Long?

    @Query("SELECT * FROM play_events WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp")
    suspend fun events(from: Long, to: Long): List<PlayEventEntity>

    @Query("SELECT COUNT(DISTINCT songId) FROM play_events WHERE timestamp BETWEEN :from AND :to")
    suspend fun songCount(from: Long, to: Long): Int

    @Query("SELECT COUNT(DISTINCT s.primaryArtist) FROM play_events e JOIN songs s ON s.id = e.songId WHERE e.timestamp BETWEEN :from AND :to AND s.primaryArtist != ''")
    suspend fun artistCount(from: Long, to: Long): Int

    /** Artists first heard between [from] and [to], with their listening in that time, most listened first. */
    @Query(
        """SELECT MAX(s.primaryArtist) AS artistName, COUNT(*) AS plays, SUM(e.playedMs) AS ms,
           MAX(s.thumbnail) AS thumbnail, MAX(s.artistId) AS artistId
           FROM play_events e JOIN songs s ON s.id = e.songId
           WHERE e.timestamp BETWEEN :from AND :to AND s.primaryArtist != '' AND s.primaryArtist IN (
               SELECT s2.primaryArtist FROM play_events e2 JOIN songs s2 ON s2.id = e2.songId
               GROUP BY s2.primaryArtist HAVING MIN(e2.timestamp) BETWEEN :from AND :to)
           GROUP BY s.primaryArtist ORDER BY ms DESC"""
    )
    suspend fun newArtists(from: Long, to: Long): List<ArtistPlays>

    @Query("SELECT songId FROM play_events WHERE timestamp > :since GROUP BY songId ORDER BY COUNT(*) DESC LIMIT :limit")
    suspend fun mostPlayedIds(since: Long, limit: Int): List<String>

    /** Home's "Most played in <year>" (1.7.6): the most played songs between two times. */
    @Query("SELECT songId FROM play_events WHERE timestamp BETWEEN :from AND :to GROUP BY songId ORDER BY COUNT(*) DESC LIMIT :limit")
    suspend fun mostPlayedIdsBetween(from: Long, to: Long, limit: Int): List<String>

    /** Of [ids], the ones ever played (Home's "Downloads you haven't played", 1.7.6). */
    @Query("SELECT DISTINCT songId FROM play_events WHERE songId IN (:ids)")
    suspend fun playedOf(ids: List<String>): List<String>

    /** The years with plays in them, newest first. */
    @Query("SELECT DISTINCT CAST(strftime('%Y', timestamp / 1000, 'unixepoch', 'localtime') AS INTEGER) FROM play_events ORDER BY 1 DESC")
    suspend fun yearsPlayed(): List<Int>

    /** Rewind: most-listened albums (songs with an album only). */
    @Query(
        """SELECT s.albumTitle AS albumTitle, COALESCE(NULLIF(MAX(s.primaryArtist), ''), MAX(s.artistName)) AS artistName,
           COUNT(*) AS plays, SUM(e.playedMs) AS ms, COUNT(DISTINCT e.songId) AS songs, MAX(s.thumbnail) AS thumbnail
           FROM play_events e JOIN songs s ON s.id = e.songId
           WHERE e.timestamp BETWEEN :from AND :to AND s.albumTitle IS NOT NULL AND s.albumTitle != ''
           GROUP BY s.albumTitle, s.primaryArtist ORDER BY ms DESC LIMIT :limit"""
    )
    suspend fun topAlbums(from: Long, to: Long, limit: Int): List<AlbumPlays>

    /** Rewind: the first song played in the stretch. */
    @Query(
        """SELECT e.songId AS songId, s.title AS title, s.artistName AS artistName, s.thumbnail AS thumbnail, e.timestamp AS timestamp
           FROM play_events e JOIN songs s ON s.id = e.songId
           WHERE e.timestamp BETWEEN :from AND :to ORDER BY e.timestamp LIMIT 1"""
    )
    suspend fun firstPlay(from: Long, to: Long): FirstPlay?

    /** Rewind: plays by what they were part of (Sing, DJ mode, Smart Shuffle…). */
    @Query("SELECT source AS source, COUNT(*) AS plays, SUM(playedMs) AS ms FROM play_events WHERE timestamp BETWEEN :from AND :to GROUP BY source")
    suspend fun bySource(from: Long, to: Long): List<SourcePlays>

    @Query("SELECT COUNT(*) FROM recognitions WHERE status = 'found' AND at BETWEEN :from AND :to")
    suspend fun recognizedIn(from: Long, to: Long): Int

    @Query("SELECT COUNT(*) FROM blends WHERE createdAt BETWEEN :from AND :to")
    suspend fun blendsIn(from: Long, to: Long): Int

    @Query("SELECT COUNT(*) FROM releases WHERE baseline = 0 AND firstSeenAt BETWEEN :from AND :to")
    suspend fun releasesFoundIn(from: Long, to: Long): Int

    /** [topSongs] for taste: plays the listener left out of it don't count. */
    @Query(
        """SELECT e.songId AS songId, COUNT(*) AS plays, SUM(e.playedMs) AS ms, s.title AS title,
           s.artistName AS artistName, s.thumbnail AS thumbnail, s.albumTitle AS albumTitle,
           s.durationSec AS durationSec, s.artistId AS artistId, s.primaryArtist AS primaryArtist
           FROM play_events e JOIN songs s ON s.id = e.songId
           WHERE e.timestamp BETWEEN :from AND :to AND e.excluded = 0
           GROUP BY e.songId ORDER BY ms DESC, plays DESC LIMIT :limit"""
    )
    suspend fun tasteTopSongs(from: Long, to: Long, limit: Int): List<SongPlays>

    /** [topArtists] for taste: plays the listener left out of it don't count. */
    @Query(
        """SELECT MAX(s.primaryArtist) AS artistName, COUNT(*) AS plays, SUM(e.playedMs) AS ms,
           MAX(s.thumbnail) AS thumbnail, MAX(s.artistId) AS artistId
           FROM play_events e JOIN songs s ON s.id = e.songId
           WHERE e.timestamp BETWEEN :from AND :to AND e.excluded = 0 AND s.primaryArtist != ''
           GROUP BY s.primaryArtist ORDER BY ms DESC LIMIT :limit"""
    )
    suspend fun tasteTopArtists(from: Long, to: Long, limit: Int): List<ArtistPlays>

    /** Plays that count towards the listener's taste (not from anything they excluded from it), oldest first. */
    @Query("SELECT * FROM play_events WHERE timestamp > :since AND excluded = 0 ORDER BY timestamp")
    suspend fun tasteEventsSince(since: Long): List<PlayEventEntity>
}

@Dao
interface ReleaseDao {
    @Query("SELECT id FROM releases WHERE artistId = :artistId")
    suspend fun idsFor(artistId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(releases: List<ReleaseEntity>)

    @Query("UPDATE releases SET releaseDate = :date WHERE id = :id")
    suspend fun setDate(id: String, date: String)

    @Query("UPDATE releases SET notifiedAt = :at WHERE id IN (:ids)")
    suspend fun setNotified(ids: List<String>, at: Long)

    /** Releases that aren't back catalogue, newest first (by date where known, else when they were first seen). */
    @Query("SELECT * FROM releases WHERE baseline = 0 AND year >= :minYear ORDER BY COALESCE(releaseDate, '') DESC, firstSeenAt DESC")
    suspend fun fresh(minYear: Int): List<ReleaseEntity>

    @Query("SELECT * FROM releases WHERE baseline = 0 AND year >= :minYear ORDER BY COALESCE(releaseDate, '') DESC, firstSeenAt DESC")
    fun freshFlow(minYear: Int): Flow<List<ReleaseEntity>>

    @Query("DELETE FROM releases WHERE artistId NOT IN (:keep)")
    suspend fun forgetArtistsExcept(keep: List<String>)
}

@Dao
interface RecognitionDao {
    @Query("SELECT * FROM recognitions WHERE status != 'none' ORDER BY at DESC")
    fun historyFlow(): Flow<List<RecognitionEntity>>

    @Query("SELECT * FROM recognitions WHERE status = 'pending' ORDER BY at")
    suspend fun pending(): List<RecognitionEntity>

    /** The newest song found (or still waiting for a network), for the Recognize widget. */
    @Query("SELECT * FROM recognitions WHERE status != 'none' ORDER BY at DESC LIMIT 1")
    suspend fun latest(): RecognitionEntity?

    @Insert
    suspend fun insert(r: RecognitionEntity): Long

    @Upsert
    suspend fun put(r: RecognitionEntity)

    @Query("DELETE FROM recognitions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM recognitions")
    suspend fun clear()
}

@Dao
interface BlendDao {
    @Query("SELECT * FROM blends ORDER BY updatedAt DESC")
    fun allFlow(): Flow<List<BlendEntity>>

    @Query("SELECT * FROM blends WHERE friendWho = :who")
    suspend fun get(who: String): BlendEntity?

    @Upsert
    suspend fun put(b: BlendEntity)

    @Query("DELETE FROM blends WHERE friendWho = :who")
    suspend fun delete(who: String)
}

@Dao
interface MixDao {
    @Query("SELECT * FROM mixes ORDER BY id")
    fun allFlow(): Flow<List<MixEntity>>

    @Query("SELECT * FROM mixes ORDER BY id")
    suspend fun all(): List<MixEntity>

    @Query("SELECT * FROM mixes WHERE id = :id")
    suspend fun get(id: String): MixEntity?

    @Upsert
    suspend fun put(m: MixEntity)

    @Query("DELETE FROM mixes WHERE id LIKE :pattern AND id NOT IN (:keep)")
    suspend fun deleteOthers(pattern: String, keep: List<String>)
}

/** A song's id and name, to tell what the listener has heard (1.8.2). */
data class HeardSong(val id: String, val title: String, val primaryArtist: String, val artistName: String)

/** An imported play's song by its name, and what it was matched to. */
data class ImportedSong(val title: String, val artist: String, val matchedId: String?)

/**
 * Everything ever heard, for New to you and Deep cuts: all of it, however old, excluded from taste or not, and imported
 * histories whether Replay counts them or not. Its own DAO so test fakes of the others stay small.
 */
@Dao
interface DiscoverDao {
    @Query("SELECT DISTINCT songId FROM play_events")
    suspend fun playedIds(): List<String>

    /** Songs played, started (a song skipped at once never reaches [PlayEventEntity]) or liked. */
    @Query("SELECT id, title, primaryArtist, artistName FROM songs WHERE lastPlayed > 0 OR liked = 1 OR id IN (SELECT songId FROM play_events)")
    suspend fun heardSongs(): List<HeardSong>

    @Query("SELECT DISTINCT title, artist, matchedId FROM imported_plays")
    suspend fun importedSongs(): List<ImportedSong>
}

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE songId = :songId AND source = :source")
    suspend fun get(songId: String, source: String): LyricsEntity?

    @Query("SELECT * FROM lyrics WHERE songId = :songId AND content != ''")
    suspend fun allFor(songId: String): List<LyricsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: LyricsEntity)

    /** Forgets cached "not found" answers so they're looked up again. */
    @Query("DELETE FROM lyrics WHERE content = ''")
    suspend fun clearMisses()
}

/** Search's lyric search (1.7.6), apart from [LyricsDao] so the lyric sources' tests keep their small fakes. */
@Dao
interface LyricsSearchDao {
    /** Saved lyrics with [word] in them (LIKE ignores ASCII case). */
    @Query("SELECT songId, content FROM lyrics WHERE content LIKE '%' || :word || '%' LIMIT :limit")
    suspend fun containing(word: String, limit: Int): List<LyricsText>
}

data class LyricsText(val songId: String, val content: String)

@Dao
interface MetaDao {
    @Query("SELECT * FROM genres WHERE `key` = :key")
    suspend fun genre(key: String): GenreEntity?

    @Query("SELECT * FROM genres WHERE `key` IN (:keys)")
    suspend fun genres(keys: List<String>): List<GenreEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putGenre(e: GenreEntity)

    /** Forgets genre results saved before [before]; Deezer's per-album genres and iTunes' answers are facts and stay. */
    @Query("DELETE FROM genres WHERE fetchedAt < :before AND `key` NOT LIKE 'dzalbum:%' AND `key` NOT LIKE 'it1:%' AND `key` NOT LIKE 'artist3:%' AND `key` NOT LIKE 'song3:%'")
    suspend fun clearGenresBefore(before: Long)

    @Query("SELECT * FROM motion_art WHERE `key` = :key")
    suspend fun motion(key: String): MotionArtEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMotion(e: MotionArtEntity)
}

@Dao
interface HeatmapDao {
    @Query("SELECT * FROM heatmaps WHERE videoId = :id")
    suspend fun get(id: String): HeatmapEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: HeatmapEntity)
}

@Dao
interface RomanizedDao {
    @Query("SELECT * FROM romanized WHERE `key` = :key")
    suspend fun get(key: String): RomanizedEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: RomanizedEntity)
}

@Database(
    entities = [SongEntity::class, PlayEventEntity::class, LyricsEntity::class, GenreEntity::class, MotionArtEntity::class, HeatmapEntity::class,
        RomanizedEntity::class, ReleaseEntity::class, MixEntity::class, BlendEntity::class, RecognitionEntity::class,
        ImportedPlayEntity::class, ImportMatchEntity::class, SongTraitsEntity::class, SongTagEntity::class],
    views = [CountedPlay::class],
    version = 11,
    exportSchema = true,
)
abstract class PrismDatabase : RoomDatabase() {
    abstract fun songs(): SongDao
    abstract fun plays(): PlayEventDao
    abstract fun lyrics(): LyricsDao
    abstract fun lyricsSearch(): LyricsSearchDao
    abstract fun meta(): MetaDao
    abstract fun heatmaps(): HeatmapDao
    abstract fun romanized(): RomanizedDao
    abstract fun releases(): ReleaseDao
    abstract fun mixes(): MixDao
    abstract fun discover(): DiscoverDao
    abstract fun blends(): BlendDao
    abstract fun recognitions(): RecognitionDao
    /** Histories brought in from Spotify, Google Takeout and Last.fm (1.8.1). */
    abstract fun imports(): ImportDao
    /** Replay's numbers over Prism's plays and the imported ones the listener counts (1.8.1). */
    abstract fun counted(): CountedPlayDao
    /** The Spotify connection's questions of the imported plays (1.8.1; no table of its own). */
    abstract fun spotify(): com.prism.music.data.spotify.SpotifyDao
    /** What's been measured of songs: tempo, key, loudness, energy (1.8.3). */
    abstract fun traits(): TraitsDao
    /** The listener's own labels on songs (1.8.3). */
    abstract fun tags(): TagDao

    companion object {
        /**
         * Every schema change gets a migration here (and its exported schema in app/schemas): the database holds the
         * listener's whole play history, so an upgrade must never fall back to wiping it. MigrationTest checks each one.
         */
        val MIGRATIONS = arrayOf(
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE play_events ADD COLUMN source TEXT")
                    db.execSQL("ALTER TABLE play_events ADD COLUMN excluded INTEGER NOT NULL DEFAULT 0")
                }
            },
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS `heatmaps` (`videoId` TEXT NOT NULL, `segmentMs` INTEGER NOT NULL, `intensity` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`videoId`))")
                }
            },
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS `romanized` (`key` TEXT NOT NULL, `content` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))")
                }
            },
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS `releases` (`id` TEXT NOT NULL, `artistId` TEXT NOT NULL, `artistName` TEXT NOT NULL, `title` TEXT NOT NULL, `kind` TEXT NOT NULL, `year` INTEGER NOT NULL, `releaseDate` TEXT, `thumbnail` TEXT, `playlistId` TEXT, `firstSeenAt` INTEGER NOT NULL, `baseline` INTEGER NOT NULL, `notifiedAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_releases_artistId` ON `releases` (`artistId`)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS `mixes` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `subtitle` TEXT NOT NULL, `seedArtists` TEXT NOT NULL, `color` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `songsJson` TEXT NOT NULL, PRIMARY KEY(`id`))")
                }
            },
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS `blends` (`friendWho` TEXT NOT NULL, `friendName` TEXT NOT NULL, `friendCode` TEXT NOT NULL, `myCode` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`friendWho`))")
                }
            },
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS `recognitions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL, `status` TEXT NOT NULL, `title` TEXT, `artist` TEXT, `album` TEXT, `cover` TEXT, `shazamKey` TEXT, `videoId` TEXT, `signature` TEXT)")
                }
            },
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE songs ADD COLUMN primaryArtist TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE songs ADD COLUMN artistsJson TEXT")
                    // The main artist from what's there: the first name of the credit. A song without an artist was
                    // stored as "Unknown artist" (what it shows) and counted as one: it gets none. Each song is stored
                    // again with its exact artists the next time Prism saves it.
                    db.execSQL(
                        """UPDATE songs SET primaryArtist = CASE
                           WHEN artistName = '$UNKNOWN_ARTIST' AND (artistId IS NULL OR id LIKE '${Song.LOCAL_PREFIX}%') THEN ''
                           WHEN instr(artistName, ', ') > 0 THEN substr(artistName, 1, instr(artistName, ', ') - 1)
                           ELSE artistName END"""
                    )
                    // Genres guessed for files without an artist (from a track search on their title alone), and the
                    // genre of a catalogued artist really called "Unknown artist", go: they'd be asked again properly.
                    db.execSQL("DELETE FROM genres WHERE key LIKE 'song2:${Song.LOCAL_PREFIX}%' OR key = 'artist2:unknownartist' OR key = 'artist2:unknown artist'")
                }
            },
            // 1.8.1: histories brought in from Spotify, Google Takeout and Last.fm, kept apart from Prism's own plays.
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS `imported_plays` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `origin` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `playedMs` INTEGER NOT NULL, `skipped` INTEGER NOT NULL, `estimated` INTEGER NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT, `sourceId` TEXT, `songKey` TEXT NOT NULL, `matchedId` TEXT)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_imported_plays_songKey` ON `imported_plays` (`songKey`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_imported_plays_timestamp` ON `imported_plays` (`timestamp`)")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_imported_plays_origin_timestamp_songKey` ON `imported_plays` (`origin`, `timestamp`, `songKey`)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS `import_matches` (`songKey` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT, `durationMs` INTEGER NOT NULL, `atLeastMs` INTEGER NOT NULL, `sourceId` TEXT, `isrc` TEXT, `youtubeId` TEXT, `status` TEXT NOT NULL, `matchedId` TEXT, `candidates` TEXT, `checkedAt` INTEGER NOT NULL, `tries` INTEGER NOT NULL, PRIMARY KEY(`songKey`))")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_import_matches_status` ON `import_matches` (`status`)")
                    db.execSQL("CREATE VIEW `counted_plays` AS $COUNTED_PLAYS_SQL")
                }
            },
            // 1.8.3: what's measured of songs, in one table (smart playlists filter on it). Filled from the scans' prefs
            // in the background after start (AppContainer), not here.
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS `song_traits` (`songId` TEXT NOT NULL, `bpm` REAL, `bpmSure` INTEGER NOT NULL, `key` TEXT, `camelot` TEXT, `lufs` REAL, `energy` REAL, `whole` INTEGER NOT NULL, `measuredAt` INTEGER NOT NULL, PRIMARY KEY(`songId`))")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_song_traits_bpm` ON `song_traits` (`bpm`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_song_traits_energy` ON `song_traits` (`energy`)")
                }
            },
            // 1.8.3: the listener's own tags on songs.
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS `song_tags` (`tag` TEXT NOT NULL, `songId` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`tag`, `songId`))")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_song_tags_songId` ON `song_tags` (`songId`)")
                }
            },
        )

        fun build(context: Context): PrismDatabase =
            Room.databaseBuilder(context, PrismDatabase::class.java, "prism.db")
                .addMigrations(*MIGRATIONS)
                // Only a downgrade (installing an older Prism over a newer one) may start the database over.
                .fallbackToDestructiveMigrationOnDowngrade(true)
                .build()
    }
}
