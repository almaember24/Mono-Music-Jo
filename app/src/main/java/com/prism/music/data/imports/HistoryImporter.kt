package com.prism.music.data.imports

import androidx.room.withTransaction
import com.prism.music.data.db.ImportMatchEntity
import com.prism.music.data.db.ImportedPlayEntity
import com.prism.music.data.db.PrismDatabase
import com.prism.music.data.db.toEntity
import com.prism.music.data.model.Song
import kotlinx.serialization.builtins.ListSerializer

/**
 * Stores imported plays (1.8.1): a [Session] takes plays as a file is read, a few thousand at a time into the database,
 * and keeps one entry per song for matching. Takeout and Last.fm plays that are really Prism's own (Prism reports its
 * plays to YouTube's history, and scrobbles them) are left out. Matching's answers come back through [applyMatch].
 *
 * A newer download of the same source adds only what's new ([ImportedIndex]): a play already brought in by an earlier
 * import is passed over, and only songs never seen before are lined up for matching.
 */
class HistoryImporter(private val db: PrismDatabase) {
    /**
     * [songs]: the songs of the plays stored (or upgraded) now; [upgraded]: account-data plays an extended download's
     * same plays took the place of (counted in [alreadyThere]); [library]: what the download's library added
     * ([HistoryImports.bring] fills it in).
     */
    data class Stats(
        val read: Int, val stored: Int, val alreadyThere: Int, val prismsOwn: Int, val songs: Int, val newSongs: Int,
        val upgraded: Int = 0, val library: LibraryNews = LibraryNews(),
    )

    /** What a download holds that isn't here yet ([HistoryImports.preview]); counted, nothing stored. */
    data class Novelty(val plays: Int, val firstAt: Long?, val lastAt: Long?, val prismsOwn: Int)

    /** Counts what of a download is new, as a [Session] of [source] would store it (nothing is written). */
    suspend fun novelty(source: ImportSource): NoveltyCounter = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        NoveltyCounter(ImportedIndex.of(db, source), if (source != ImportSource.SPOTIFY) PrismIndex.of(db) else null)
    }

    class NoveltyCounter internal constructor(private val stored: ImportedIndex, private val prism: PrismIndex?) {
        private var plays = 0
        private var dropped = 0
        private var first = Long.MAX_VALUE
        private var last = Long.MIN_VALUE

        /** One play read from the download (the readers call back plainly). */
        fun add(p: ParsedPlay) {
            val key = ImportKeys.songKey(p.title, p.artist)
            if (prism != null && prism.absorbs(p, key)) { dropped++; return }
            if (stored.absorb(p, key, upgrades = false) != ImportedIndex.Hit.New) return
            plays++
            if (p.timestamp < first) first = p.timestamp
            if (p.timestamp > last) last = p.timestamp
        }

        fun result() = Novelty(plays, first.takeIf { plays > 0 }, last.takeIf { plays > 0 }, dropped)
    }

    private class Seed(val title: String, val artist: String, val album: String?, val sourceId: String?, val youtubeId: String?) {
        var full = 0L
        var longest = 0L
    }

    fun session(source: ImportSource): Session = Session(source)

    inner class Session internal constructor(private val source: ImportSource) {
        private val buffer = ArrayList<ImportedPlayEntity>(BATCH)
        private val seeds = HashMap<String, Seed>()
        /** Songs seen since their match lines were last written ([writeSeeds]). */
        private val unwritten = HashSet<String>()
        private var prism: PrismIndex? = null
        private var earlier: ImportedIndex? = null
        private val upgrades = ArrayList<Pair<Long, ParsedPlay>>()
        private var read = 0
        private var stored = 0
        private var dropped = 0
        private var upgraded = 0
        private var fresh = 0

        /** One play read from the file. */
        suspend fun add(p: ParsedPlay) {
            read++
            val key = ImportKeys.songKey(p.title, p.artist)
            // Prism's plays go to YouTube's history and Last.fm, never to Spotify's. YouTube Music (live) leaves Prism's
            // out itself, over the whole time between its two reads (its times are only estimates).
            if (source != ImportSource.SPOTIFY && source != ImportSource.SPOTIFY_LIVE && source != ImportSource.YOUTUBE_LIVE) {
                val index = prism ?: PrismIndex.of(db).also { prism = it }
                if (index.absorbs(p, key)) { dropped++; return }
            }
            // Brought in by an earlier import: passed over (an account-data play takes the extended one's details).
            val before = earlier ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ImportedIndex.of(db, source) }.also { earlier = it }
            when (val hit = before.absorb(p, key, upgrades = source == ImportSource.SPOTIFY)) {
                ImportedIndex.Hit.New -> Unit
                ImportedIndex.Hit.Same -> return
                is ImportedIndex.Hit.Upgrade -> {
                    // Found by its title and a related artist: the row keeps its own song (and its match).
                    if (hit.sameSong) seedOf(key, p)
                    upgrades += hit.id to p
                    if (upgrades.size >= BATCH) flush()
                    return
                }
            }
            seedOf(key, p)
            buffer += ImportedPlayEntity(
                origin = source.key, timestamp = p.timestamp, playedMs = p.playedMs.coerceIn(0, MAX_PLAY_MS), skipped = p.skipped,
                estimated = p.estimated, title = p.title.take(MAX_TEXT), artist = p.artist.take(MAX_TEXT), album = p.album?.take(MAX_TEXT),
                sourceId = p.sourceId?.take(120), songKey = key,
            )
            if (buffer.size >= BATCH) flush()
        }

        private fun seedOf(key: String, p: ParsedPlay) {
            val seed = seeds.getOrPut(key) { Seed(p.title.take(MAX_TEXT), p.artist.take(MAX_TEXT), p.album?.take(MAX_TEXT), p.sourceId, p.youtubeId) }
            if (p.fullPlay) seed.full = maxOf(seed.full, p.playedMs)
            if (!p.estimated) seed.longest = maxOf(seed.longest, p.playedMs)
            unwritten += key
        }

        private suspend fun flush() {
            if (buffer.isEmpty() && upgrades.isEmpty()) return
            // The songs' match lines before their plays: an import stopped half way never leaves plays with none.
            writeSeeds()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (buffer.isNotEmpty()) stored += insertRows(buffer)
                if (upgrades.isNotEmpty()) upgraded += upgradeRows(upgrades)
            }
            buffer.clear()
            upgrades.clear()
        }

        /** Lines up the songs seen since the last time (a song matched before keeps its match and the listener's pick). */
        private suspend fun writeSeeds() {
            if (unwritten.isEmpty()) return
            val dao = db.imports()
            db.withTransaction {
                unwritten.toList().chunked(500).forEach { keys ->
                    val known = dao.matches(keys).associateBy { it.songKey }
                    val changed = keys.mapNotNull { k ->
                        val s = seeds.getValue(k)
                        val old = known[k]
                        if (old == null) fresh++
                        val next = old?.copy(
                            durationMs = maxOf(old.durationMs, s.full), atLeastMs = maxOf(old.atLeastMs, s.longest),
                            sourceId = old.sourceId ?: s.sourceId, youtubeId = old.youtubeId ?: s.youtubeId,
                        ) ?: ImportMatchEntity(
                            songKey = k, title = s.title, artist = s.artist, album = s.album, durationMs = s.full, atLeastMs = s.longest,
                            sourceId = s.sourceId, youtubeId = s.youtubeId, status = MatchStatus.PENDING.name,
                        )
                        next.takeIf { it != old }
                    }
                    if (changed.isNotEmpty()) dao.putMatches(changed)
                }
            }
            unwritten.clear()
        }

        /**
         * Stores what's left and lines the songs up for matching (songs matched before get their plays at once). A song
         * matched before keeps its match and the listener's pick; only songs never seen are new to matching.
         */
        suspend fun finish(): Stats {
            flush()
            writeSeeds()
            val dao = db.imports()
            db.withTransaction {
                // Nothing stored (the same download again): nothing to link or cap.
                if (stored > 0 || upgraded > 0) {
                    dao.linkKnown(source.key)
                    dao.capEstimatedAll(source.key)
                }
            }
            return Stats(read, stored, read - dropped - stored, dropped, seeds.size, fresh, upgraded)
        }
    }

    /**
     * Account-data plays an extended download's same plays take the place of: its time to the second, how long it was
     * heard, whether it was skipped, the album and the Spotify id. Returns how many rows changed.
     */
    private fun upgradeRows(rows: List<Pair<Long, ParsedPlay>>): Int {
        var n = 0
        db.runInTransaction {
            db.compileStatement(
                "UPDATE OR IGNORE imported_plays SET timestamp = ?, playedMs = ?, skipped = ?, album = COALESCE(album, ?), sourceId = ? WHERE id = ?"
            ).use { st ->
                for ((id, p) in rows) {
                    st.clearBindings()
                    st.bindLong(1, p.timestamp); st.bindLong(2, p.playedMs.coerceIn(0, MAX_PLAY_MS)); st.bindLong(3, if (p.skipped) 1 else 0)
                    p.album?.take(MAX_TEXT)?.let { st.bindString(4, it) } ?: st.bindNull(4)
                    p.sourceId?.take(120)?.let { st.bindString(5, it) } ?: st.bindNull(5)
                    st.bindLong(6, id)
                    n += st.executeUpdateDelete()
                }
            }
        }
        return n
    }

    /**
     * Stores [rows] in one transaction, [ROWS_PER_STATEMENT] to a statement (a long history is 100,000+ rows: one
     * statement each was most of an import's time). A row already there (the same file again) is passed over.
     * Returns how many were new.
     */
    private fun insertRows(rows: List<ImportedPlayEntity>): Int {
        var inserted = 0
        db.runInTransaction {
            for (chunk in rows.chunked(ROWS_PER_STATEMENT)) {
                val sql = "INSERT OR IGNORE INTO imported_plays (origin, timestamp, playedMs, skipped, estimated, title, artist, album, sourceId, songKey, matchedId) VALUES " +
                    chunk.joinToString(",") { "(?,?,?,?,?,?,?,?,?,?,?)" }
                db.compileStatement(sql).use { st ->
                    var i = 1
                    for (r in chunk) {
                        st.bindString(i++, r.origin); st.bindLong(i++, r.timestamp); st.bindLong(i++, r.playedMs)
                        st.bindLong(i++, if (r.skipped) 1 else 0); st.bindLong(i++, if (r.estimated) 1 else 0)
                        st.bindString(i++, r.title); st.bindString(i++, r.artist)
                        r.album?.let { st.bindString(i, it) } ?: st.bindNull(i); i++
                        r.sourceId?.let { st.bindString(i, it) } ?: st.bindNull(i); i++
                        st.bindString(i++, r.songKey)
                        r.matchedId?.let { st.bindString(i, it) } ?: st.bindNull(i); i++
                    }
                    inserted += st.executeUpdateDelete()
                }
            }
        }
        return inserted
    }

    /** Lines up library songs (Liked Songs, playlists) for matching, beside the history's. */
    suspend fun addLibrarySongs(tracks: List<LibraryTrack>) {
        val dao = db.imports()
        tracks.distinctBy { it.key }.chunked(500).forEach { chunk ->
            val known = dao.matches(chunk.map { it.key }).map { it.songKey }.toSet()
            dao.putMatches(chunk.filter { it.key !in known }.map { t ->
                ImportMatchEntity(t.key, t.title.take(MAX_TEXT), t.artist.take(MAX_TEXT), t.album?.take(MAX_TEXT), sourceId = t.sourceId, status = MatchStatus.PENDING.name)
            })
        }
    }

    /** Matching's answer for one song: kept, and its plays get (or lose) their song. */
    suspend fun applyMatch(m: ImportMatchEntity, r: MatchResult, now: Long = System.currentTimeMillis()) {
        val dao = db.imports()
        db.withTransaction {
            val song = r.song?.takeIf { r.status.matched }
            // A song Prism already knows keeps its row as it is: Replay's Prism-only numbers can't move.
            song?.let { s -> if (db.songs().get(s.id) == null) db.songs().upsert(s.toEntity()) }
            dao.putMatches(listOf(m.copy(
                status = r.status.name, matchedId = r.song?.id, candidates = r.candidates.takeIf { it.isNotEmpty() }?.let(::candidatesJson),
                checkedAt = now, tries = m.tries + 1,
            )))
            dao.setPlaysMatched(m.songKey, song?.id)
            song?.let { s -> if (s.durationSec > 0) dao.capEstimated(m.songKey, s.durationSec * 1000L) }
        }
    }

    /** The listener's own pick from "Needs a look" ([song] null: none of them). */
    suspend fun fix(m: ImportMatchEntity, song: Song?) =
        applyMatch(m, MatchResult(if (song != null) MatchStatus.FIXED else MatchStatus.SKIPPED, song, candidatesOf(m)))

    /** Takes one source's plays away; matches nothing refers to any more go too (except [keep] and the listener's picks). */
    suspend fun remove(source: ImportSource, keep: Set<String>): Int = db.withTransaction {
        val n = db.imports().deletePlays(source.key)
        db.imports().orphanMatches().filter { it !in keep }.chunked(500).forEach { db.imports().deleteMatches(it) }
        n
    }

    companion object {
        const val BATCH = 2_000
        /** 11 columns each: 990 values, under SQLite's 999 on older Android versions. */
        private const val ROWS_PER_STATEMENT = 90
        private const val MAX_TEXT = 300
        private const val MAX_PLAY_MS = 6 * 3_600_000L

        private val songs = ListSerializer(Song.serializer())
        fun candidatesJson(list: List<Song>): String = ImportFiles.json.encodeToString(songs, list)
        fun candidatesOf(m: ImportMatchEntity): List<Song> = m.candidates?.let { runCatching { ImportFiles.json.decodeFromString(songs, it) }.getOrNull() }.orEmpty()
    }
}

/**
 * Prism's own plays, to know a Takeout or Last.fm play that is one of them: the same song (by its YouTube id, or its
 * name) at about the same time. Prism notes a play when it ends; the others, when it starts.
 */
internal class PrismIndex private constructor(
    private val byId: Map<String, MutableList<Long>>,
    private val byKey: Map<String, MutableList<Long>>,
) {
    fun absorbs(p: ParsedPlay, key: String): Boolean {
        val list = (p.youtubeId?.let { byId[it] } ?: byKey[key]) ?: return false
        val hit = list.indexOfFirst { it in (p.timestamp - BEFORE_MS)..(p.timestamp + AFTER_MS) }
        if (hit < 0) return false
        // Each of Prism's plays stands for one imported play at most (a song played twice running is two).
        val ts = list.removeAt(hit)
        if (p.youtubeId != null) byKey[key]?.remove(ts) else byId.values.forEach { it.remove(ts) }
        return true
    }

    companion object {
        const val BEFORE_MS = 3 * 60_000L
        const val AFTER_MS = 15 * 60_000L

        suspend fun of(db: PrismDatabase): PrismIndex {
            val byId = HashMap<String, MutableList<Long>>()
            val byKey = HashMap<String, MutableList<Long>>()
            db.imports().prismPlays(0, Long.MAX_VALUE).forEach { r ->
                byId.getOrPut(r.songId) { ArrayList(2) } += r.timestamp
                // By its main artist, and by its whole credit (Prism scrobbles "A, B" to Last.fm unless told not to).
                val keys = setOf(ImportKeys.songKey(r.title, r.primaryArtist), ImportKeys.songKey(r.title, r.artistName))
                keys.forEach { k -> byKey.getOrPut(k) { ArrayList(2) } += r.timestamp }
            }
            return PrismIndex(byId, byKey)
        }
    }
}
