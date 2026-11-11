package com.prism.music.data

import android.content.SharedPreferences
import androidx.room.withTransaction
import com.prism.music.data.db.GenreEntity
import com.prism.music.data.db.PlayEventEntity
import com.prism.music.data.db.PrismDatabase
import com.prism.music.data.db.SongEntity
import com.prism.music.data.db.toEntity
import com.prism.music.data.foryou.Excluded
import com.prism.music.playback.together.fromPeer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** A song as the phone keeps it (liked or played), for a TV ([BringOver]). */
@Serializable
data class BringSong(
    val id: String,
    val title: String,
    val artistName: String,
    val artistId: String? = null,
    val albumTitle: String? = null,
    val albumId: String? = null,
    val durationSec: Int = 0,
    val thumbnail: String? = null,
    val isVideo: Boolean = false,
    val liked: Boolean = false,
    val likedAt: Long = 0,
    val lastPlayed: Long = 0,
    val primaryArtist: String = "",
    val artistsJson: String? = null,
) {
    fun entity() = SongEntity(id, title, artistName, artistId, albumTitle, albumId, durationSec, thumbnail, isVideo, liked, likedAt, lastPlayed, false, primaryArtist, artistsJson)

    companion object {
        fun of(e: SongEntity) = BringSong(e.id, e.title, e.artistName, e.artistId, e.albumTitle, e.albumId, e.durationSec, e.thumbnail, e.isVideo, e.liked, e.likedAt, e.lastPlayed, e.primaryArtist, e.artistsJson)
    }
}

/** One play ([PlayEventEntity] without its row id). */
@Serializable
data class BringPlay(val songId: String, val timestamp: Long, val playedMs: Long, val source: String? = null, val excluded: Boolean = false)

/** A genre Prism looked up (a cache, but the mixes are made from it: they're right at once). */
@Serializable
data class BringGenre(val key: String, val genre: String?, val fetchedAt: Long)

/** The phone's listening: songs liked or played, every play, the genres looked up. */
@Serializable
data class BringHistory(val songs: List<BringSong> = emptyList(), val plays: List<BringPlay> = emptyList(), val genres: List<BringGenre> = emptyList())

/**
 * Everything "Bring your Prism" carries (1.8.0): [history], saved preferences of [BringOver.PREFS] (typed values, as a
 * Look's: "b:", "i:", "l:", "f:", "s:", "ss:" a JSON list), Prism's playlists, folders and saved albums ([library], the
 * library file's own format) and, if the listener chose it, their [look] (a Look code). Never the sign-in, downloads'
 * audio or the phone's settings.
 */
class BringContent(
    val history: BringHistory,
    val prefs: Map<String, Map<String, String>>,
    val library: String?,
    val look: String?,
)

/** What a merge added on the TV. */
data class BringMerged(val songs: Int = 0, val likes: Int = 0, val plays: Int = 0, val prefs: Int = 0, val playlists: Int = 0, val albums: Int = 0)

/**
 * "Bring your Prism" (1.8.0): a phone's personal Prism, gathered for a TV ([collect]) and merged into the TV's own
 * ([merge]). Merging only adds: likes and plays join the TV's by song and time (the same play twice is one play),
 * preferences the TV has stay as they are, playlists join by id (a playlist on both keeps the copy changed last), and
 * nothing the TV has is wiped. Bringing again changes nothing that came the first time. The phone's own files (local
 * songs) never go: not their plays, likes or names.
 */
object BringOver {
    /** The saved preferences that come along: starred artists, song versions, lyric timings, best parts, exclusions. */
    val PREFS = listOf("artist_prefs", "song_versions", "lyric_offsets", "best_part_mine", "exclusions", "dislikes")

    /** Preferences that hold a list: the two sides' lists are joined rather than one kept. */
    private val LIST_KEYS = mapOf("artist_prefs" to setOf("favorites"), "exclusions" to setOf("artists", "playlists", "songs"), "dislikes" to setOf("ids"))

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val idPattern = Regex("[A-Za-z0-9_-]{11}")
    private const val MAX_KEYS = 50_000
    private const val MAX_KEY = 300
    private const val MAX_VALUE = 200_000
    private const val MAX_GENRE = 8_000

    val PREFS_MAP = MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer()))

    // ------------------------------------------------------------ The phone's side

    /** The phone's listening and preferences, without its files. */
    suspend fun collect(db: PrismDatabase, prefs: (String) -> SharedPreferences, library: String?, look: String?): BringContent {
        val plays = db.plays().events(0, Long.MAX_VALUE).filter { !isLocal(it.songId) }
        val liked = db.songs().liked().filter { !isLocal(it.id) }
        val songs = LinkedHashMap<String, SongEntity>()
        liked.forEach { songs[it.id] = it }
        plays.map { it.songId }.distinct().filter { it !in songs }.chunked(500).forEach { ids -> db.songs().getAll(ids).forEach { songs[it.id] = it } }
        val genres = ArrayList<BringGenre>()
        runCatching {
            db.query(androidx.sqlite.db.SimpleSQLiteQuery("SELECT `key`, genre, fetchedAt FROM genres")).use { cur ->
                while (cur.moveToNext()) {
                    val key = cur.getString(0) ?: continue
                    val genre = if (cur.isNull(1)) null else cur.getString(1)
                    if (key.length > MAX_KEY || (genre?.length ?: 0) > MAX_GENRE || key.contains(com.prism.music.data.model.Song.LOCAL_PREFIX)) continue
                    genres += BringGenre(key, genre, cur.getLong(2))
                }
            }
        }
        val history = BringHistory(
            songs = songs.values.map(BringSong::of),
            plays = plays.map { BringPlay(it.songId, it.timestamp, it.playedMs, it.source, it.excluded) },
            genres = genres,
        )
        val saved = PREFS.associateWith { name ->
            prefs(name).all.mapNotNull { (k, v) -> if (isLocal(k)) null else encode(v)?.let { k to it } }.toMap()
        }.filterValues { it.isNotEmpty() }
        return BringContent(history, saved, library, look)
    }

    fun historyJson(h: BringHistory): String = json.encodeToString(BringHistory.serializer(), h)
    fun prefsJson(p: Map<String, Map<String, String>>): String = json.encodeToString(PREFS_MAP, p)
    fun history(text: String): BringHistory? = runCatching { json.decodeFromString(BringHistory.serializer(), text) }.getOrNull()
    fun prefs(text: String): Map<String, Map<String, String>>? = runCatching { json.decodeFromString(PREFS_MAP, text) }.getOrNull()

    private fun isLocal(id: String) = id.startsWith(com.prism.music.data.model.Song.LOCAL_PREFIX)

    // ------------------------------------------------------------ The TV's side

    /**
     * Merges [content] in. The database's part is one transaction (all of it, or none); preferences and playlists after
     * it. [library] is the TV's own; [prefs] opens its saved preferences by name.
     */
    suspend fun merge(content: BringContent, db: PrismDatabase, prefs: (String) -> SharedPreferences, library: PrismLibrary?): BringMerged {
        val history = mergeHistory(db, content.history)
        var changedPrefs = 0
        for ((name, theirs) in content.prefs) {
            if (name !in PREFS) continue
            val p = prefs(name)
            val changes = mergePrefs(name, p.all, theirs)
            if (changes.isEmpty()) continue
            val ed = p.edit()
            changes.forEach { (k, v) -> put(ed, k, v) }
            ed.commit()
            changedPrefs += changes.size
        }
        val lists = content.library?.let { library?.mergeFrom(it) } ?: (0 to 0)
        return history.copy(prefs = changedPrefs, playlists = lists.first, albums = lists.second)
    }

    /** Songs, likes and plays into [db]: one transaction. */
    suspend fun mergeHistory(db: PrismDatabase, h: BringHistory): BringMerged {
        val songs = h.songs.mapNotNull(::clean).distinctBy { it.id }
        val known = songs.map { it.id }.toHashSet()
        val plays = h.plays.mapNotNull { p ->
            if (p.songId !in known || p.timestamp <= 0) null
            else PlayEventEntity(songId = p.songId, timestamp = p.timestamp, playedMs = p.playedMs.coerceIn(0, 24 * 3_600_000L), source = p.source?.take(80), excluded = p.excluded)
        }
        var added = 0; var likes = 0; var newPlays = 0
        db.withTransaction {
            val dao = db.songs()
            val have = songs.map { it.id }.chunked(500).flatMap { dao.getAll(it) }.associateBy { it.id }
            for (s in songs) {
                val mine = have[s.id]
                val merged = mergeSong(mine, s) ?: continue
                if (mine == null) added++
                if (merged.liked && mine?.liked != true) likes++
                dao.upsert(merged)
            }
            if (plays.isNotEmpty()) {
                val seen = db.plays().events(plays.minOf { it.timestamp }, plays.maxOf { it.timestamp }).mapTo(HashSet()) { it.songId to it.timestamp }
                for (p in plays) if (seen.add(p.songId to p.timestamp)) { db.plays().insert(p); newPlays++ }
            }
            val genres = h.genres.filter { it.key.length <= MAX_KEY && (it.genre?.length ?: 0) <= MAX_GENRE }.distinctBy { it.key }
            val haveGenres = genres.map { it.key }.chunked(500).flatMap { db.meta().genres(it) }.mapTo(HashSet()) { it.key }
            for (g in genres) if (g.key !in haveGenres) db.meta().putGenre(GenreEntity(g.key, g.genre, g.fetchedAt))
        }
        return BringMerged(songs = added, likes = likes, plays = newPlays)
    }

    /** A song as it may come in: a YouTube song only, its text cut to size, its picture from YouTube's hosts. */
    private fun clean(s: BringSong): SongEntity? {
        if (!s.id.matches(idPattern)) return null
        val e = s.entity()
        val song = e.toSong().fromPeer() ?: return null
        return song.toEntity().copy(
            liked = s.liked, likedAt = if (s.liked) s.likedAt.coerceAtLeast(0) else 0, lastPlayed = s.lastPlayed.coerceAtLeast(0),
        )
    }

    /**
     * [theirs] beside the TV's [mine]: what to keep (null: nothing to change). The TV's own details stay; a like on
     * either side is a like (from the earlier of the two times), the last play is the later one.
     */
    internal fun mergeSong(mine: SongEntity?, theirs: SongEntity): SongEntity? {
        if (mine == null) return theirs.copy(smartDownload = false)
        val liked = mine.liked || theirs.liked
        val likedAt = when {
            mine.liked && theirs.liked -> listOf(mine.likedAt, theirs.likedAt).filter { it > 0 }.minOrNull() ?: 0
            mine.liked -> mine.likedAt
            theirs.liked -> theirs.likedAt
            else -> 0
        }
        val merged = mine.copy(
            liked = liked, likedAt = likedAt, lastPlayed = maxOf(mine.lastPlayed, theirs.lastPlayed),
            thumbnail = mine.thumbnail ?: theirs.thumbnail,
            primaryArtist = mine.primaryArtist.ifBlank { theirs.primaryArtist },
            artistsJson = mine.artistsJson ?: theirs.artistsJson,
        )
        return merged.takeIf { it != mine }
    }

    /**
     * The values to write into the TV's [name] preferences ([mine]) from the phone's [theirs]: keys the TV doesn't have,
     * and lists joined. Anything malformed, too long or of a file on the phone is left out.
     */
    fun mergePrefs(name: String, mine: Map<String, *>, theirs: Map<String, String>): Map<String, String> {
        if (name !in PREFS) return emptyMap()
        val out = LinkedHashMap<String, String>()
        val lists = LIST_KEYS[name].orEmpty()
        for ((k, v) in theirs.entries.take(MAX_KEYS)) {
            if (k.isBlank() || k.length > MAX_KEY || v.length > MAX_VALUE || isLocal(k)) continue
            if (decode(v) == null) continue
            if (k in lists) {
                joinLists(name, k, mine[k], v)?.let { out[k] = it }
            } else if (!mine.containsKey(k)) out[k] = v
        }
        return out
    }

    private val favorites = ListSerializer(FavoriteArtist.serializer())
    private val excluded = ListSerializer(Excluded.serializer())
    private val strings = ListSerializer(String.serializer())

    /** A list preference joined: the TV's entries, then the phone's it doesn't have. Null when nothing's new. */
    private fun joinLists(name: String, key: String, mine: Any?, theirs: String): String? {
        val incoming = decode(theirs) ?: return null
        return when {
            name == "artist_prefs" -> {
                val a = (mine as? String)?.let { runCatching { json.decodeFromString(favorites, it) }.getOrNull() }.orEmpty()
                val b = (incoming as? String)?.let { runCatching { json.decodeFromString(favorites, it) }.getOrNull() } ?: return null
                val have = a.mapTo(HashSet()) { it.id }
                val add = b.filter { it.id.isNotBlank() && it.id.length <= MAX_KEY && have.add(it.id) }
                    .map { it.copy(name = it.name.take(200), thumbnail = it.thumbnail?.takeIf { t -> t.startsWith("https://") }) }
                if (add.isEmpty()) null else "s:" + json.encodeToString(favorites, (a + add).sortedByDescending { it.at })
            }
            name == "exclusions" -> {
                val a = (mine as? String)?.let { runCatching { json.decodeFromString(excluded, it) }.getOrNull() }.orEmpty()
                val b = (incoming as? String)?.let { runCatching { json.decodeFromString(excluded, it) }.getOrNull() } ?: return null
                val add = b.filter { e -> e.id.length <= MAX_KEY && a.none { it.id == e.id || (key == "artists" && it.name.equals(e.name, ignoreCase = true)) } }
                    .distinctBy { it.id }.map { it.copy(name = it.name.take(200)) }
                if (add.isEmpty()) null else "s:" + json.encodeToString(excluded, a + add)
            }
            name == "dislikes" -> {
                val a = (mine as? Set<*>)?.filterIsInstance<String>().orEmpty().toSet()
                val b = (incoming as? Set<*>)?.filterIsInstance<String>()?.filter { it.matches(idPattern) } ?: return null
                if (a.containsAll(b)) null else "ss:" + json.encodeToString(strings, (a + b).toList())
            }
            else -> null
        }
    }

    /** A saved preference's value, typed ([decode] reads it back); null for a kind that isn't kept. */
    fun encode(v: Any?): String? = when (v) {
        is Boolean -> "b:$v"
        is Int -> "i:$v"
        is Long -> "l:$v"
        is Float -> "f:$v"
        is String -> "s:$v"
        is Set<*> -> "ss:" + json.encodeToString(strings, v.filterIsInstance<String>())
        else -> null
    }

    fun decode(v: String): Any? = when {
        v.startsWith("b:") -> v.drop(2).toBooleanStrictOrNull()
        v.startsWith("i:") -> v.drop(2).toIntOrNull()
        v.startsWith("l:") -> v.drop(2).toLongOrNull()
        v.startsWith("f:") -> v.drop(2).toFloatOrNull()
        v.startsWith("ss:") -> runCatching { json.decodeFromString(strings, v.drop(3)).toSet() }.getOrNull()
        v.startsWith("s:") -> v.drop(2)
        else -> null
    }

    private fun put(ed: SharedPreferences.Editor, k: String, v: String) {
        when (val d = decode(v)) {
            is Boolean -> ed.putBoolean(k, d)
            is Int -> ed.putInt(k, d)
            is Long -> ed.putLong(k, d)
            is Float -> ed.putFloat(k, d)
            is String -> ed.putString(k, d)
            is Set<*> -> ed.putStringSet(k, d.filterIsInstance<String>().toSet())
        }
    }
}
