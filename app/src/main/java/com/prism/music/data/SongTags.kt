package com.prism.music.data

import com.prism.music.data.db.PrismDatabase
import com.prism.music.data.db.SongEntity
import com.prism.music.data.db.SongTagEntity
import com.prism.music.data.db.TagSummary
import com.prism.music.data.db.toEntity
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/** Tags as a backup carries them (`files/library/tags.json`): each tag with its songs, so they show on any phone. */
@Serializable
data class TagsFile(val tags: List<TagEntry> = emptyList())

@Serializable
data class TagEntry(val tag: String, val songs: List<TaggedSong> = emptyList())

@Serializable
data class TaggedSong(val song: Song, val at: Long)

/**
 * The listener's own tags on songs (1.8.3): from a song's menu, the player's ⋯ or Select many; Library → Tags lists
 * them, each a list to play. In the database (smart playlists ask for them in one query), in backups as
 * [TagsFile] beside the library ([PENDING] is one waiting to be read in after a restore).
 */
class TagStore(private val db: PrismDatabase, scope: CoroutineScope, private val libraryDir: File) {
    private val dao get() = db.tags()

    /** Every tag, the one added to last first. Asked for when something shows them (Library), not at start. */
    val summaries: StateFlow<List<TagSummary>> = dao.summariesFlow().stateIn(scope, SharingStarted.Lazily, emptyList())

    fun tagsOf(songId: String): Flow<List<String>> = dao.tagsOfFlow(songId)

    fun songs(tag: String): Flow<List<Song>> = dao.songsFlow(tag).map { rows -> rows.map(SongEntity::toSong) }

    /** How many of [ids] each tag has. */
    suspend fun holds(ids: List<String>): Map<String, Int> = withContext(Dispatchers.IO) {
        ids.distinct().chunked(500).flatMap { dao.holds(it) }.groupBy { it.tag }.mapValues { (_, v) -> v.sumOf { it.n } }
    }

    /** The name a typed tag goes under: an existing tag that differs only in case, else the typed one tidied ([clean]). */
    suspend fun nameFor(typed: String): String? {
        val name = clean(typed) ?: return null
        return withContext(Dispatchers.IO) { dao.names() }.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
    }

    /** Tags [songs] with [tag] (songs it has already keep their place); how many were new to it. */
    suspend fun add(tag: String, songs: List<Song>): Int = withContext(Dispatchers.IO) {
        val name = nameFor(tag) ?: return@withContext 0
        val list = songs.filter { taggable(it) }.distinctBy { it.id }
        if (list.isEmpty()) return@withContext 0
        keepSongs(list)
        val now = System.currentTimeMillis()
        // Newest first in the tag, in the order given: the first song gets the latest time.
        list.mapIndexed { i, s -> SongTagEntity(name, s.id, now + list.size - i) }.chunked(500).sumOf { rows -> dao.add(rows).count { it != -1L } }
    }

    suspend fun remove(tag: String, ids: List<String>): Int = withContext(Dispatchers.IO) { ids.distinct().chunked(500).sumOf { dao.remove(tag, it) } }

    /** [from] renamed to [to] (joining that tag if there is one); the name it went under, or null for an empty name. */
    suspend fun rename(from: String, to: String): String? = withContext(Dispatchers.IO) {
        val name = clean(to) ?: return@withContext null
        val target = dao.names().firstOrNull { it != from && it.equals(name, ignoreCase = true) } ?: name
        dao.rename(from, target)
        target
    }

    suspend fun delete(tag: String) = withContext(Dispatchers.IO) { dao.delete(tag) }

    /** Every tag with its songs, for a backup. */
    suspend fun export(): TagsFile = withContext(Dispatchers.IO) {
        TagsFile(dao.allWithSongs().groupBy { it.tag }.map { (tag, rows) -> TagEntry(tag, rows.map { TaggedSong(it.song.toSong(), it.addedAt) }) })
    }

    fun exportJson(file: TagsFile): String = InnerTube.json.encodeToString(TagsFile.serializer(), file)

    /** Every tag replaced by [file]'s (a restore); songs not on this phone yet are kept for their names and covers. */
    suspend fun replaceWith(file: TagsFile) = withContext(Dispatchers.IO) {
        val entries = file.tags.mapNotNull { e -> clean(e.tag)?.let { it to e.songs.filter { s -> taggable(s.song) } } }
        keepSongs(entries.flatMap { (_, songs) -> songs.map { it.song } }.distinctBy { it.id })
        dao.replaceAll(entries.flatMap { (tag, songs) -> songs.map { SongTagEntity(tag, it.song.id, it.at) } })
    }

    /** After a restore: the backup's tags ([PENDING]) put in place of these, once. Nothing to do otherwise. */
    suspend fun readPending() = withContext(Dispatchers.IO) {
        val f = File(libraryDir, PENDING)
        if (!f.isFile) return@withContext
        runCatching { replaceWith(InnerTube.json.decodeFromString(TagsFile.serializer(), f.readText())) }
            .onFailure { android.util.Log.w("PrismTags", "the restored tags didn't read", it) }
        f.delete()
    }

    /** Songs a tag shows come from the songs table: each tagged one goes in (keeping what's known of one there). */
    private suspend fun keepSongs(songs: List<Song>) {
        songs.chunked(500).forEach { part ->
            val have = db.songs().getAll(part.map { it.id }).associateBy { it.id }
            db.songs().upsertAll(part.map { it.toEntity(have[it.id]) })
        }
    }

    companion object {
        /** A backup's tags, at its place among the library's files; read in and removed after a restore. */
        const val PENDING = "tags.json"
        const val MAX_LENGTH = 40

        /** A tag as typed, tidied: spaces trimmed and run together, at most [MAX_LENGTH] letters; null when empty. */
        fun clean(typed: String): String? = typed.trim().replace(Regex("\\s+"), " ").take(MAX_LENGTH).trim().takeIf { it.isNotEmpty() }

        /** Songs and files; a feed's episodes come and go with the feed. */
        fun taggable(s: Song) = !s.isRss

        /**
         * The tags in a database file that Room hasn't opened (a restore, before the app starts): read straight from it,
         * or null when it has none to read (older than 1.8.3, or not a database).
         */
        fun readFrom(dbFile: File): TagsFile? = runCatching {
            if (!dbFile.isFile) return@runCatching null
            android.database.sqlite.SQLiteDatabase.openDatabase(dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { sql ->
                sql.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'song_tags'", null).use { if (!it.moveToFirst()) return@runCatching null }
                val rows = mutableListOf<Pair<String, TaggedSong>>()
                sql.rawQuery(
                    """SELECT t.tag, t.addedAt, s.id, s.title, s.artistName, s.artistId, s.albumTitle, s.albumId, s.durationSec,
                       s.thumbnail, s.isVideo, s.primaryArtist, s.artistsJson
                       FROM song_tags t JOIN songs s ON s.id = t.songId ORDER BY t.addedAt""",
                    null,
                ).use { c ->
                    while (c.moveToNext()) {
                        val e = SongEntity(
                            id = c.getString(2), title = c.getString(3), artistName = c.getString(4), artistId = c.getString(5),
                            albumTitle = c.getString(6), albumId = c.getString(7), durationSec = c.getInt(8), thumbnail = c.getString(9),
                            isVideo = c.getInt(10) != 0, primaryArtist = c.getString(11).orEmpty(), artistsJson = c.getString(12),
                        )
                        rows += c.getString(0) to TaggedSong(e.toSong(), c.getLong(1))
                    }
                }
                TagsFile(rows.groupBy({ it.first }, { it.second }).map { (tag, songs) -> TagEntry(tag, songs) })
            }
        }.getOrNull()
    }
}
