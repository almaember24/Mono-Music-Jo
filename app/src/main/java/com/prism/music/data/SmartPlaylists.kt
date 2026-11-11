package com.prism.music.data

import com.prism.music.data.db.PrismDatabase
import com.prism.music.data.db.SongEntity
import com.prism.music.data.db.toEntity
import com.prism.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** Whether a smart playlist's songs meet all of its rules or any one of them. */
@Serializable
enum class SmartMatch { ALL, ANY }

/**
 * What a rule looks at (1.8.3). Sound: what the song scans measured ([com.prism.music.data.db.SongTraitsEntity]);
 * Library: what's yours; Your listening: Prism's own plays.
 */
@Serializable
enum class SmartField(val sound: Boolean = false) {
    /** BPM from [SmartRule.min] to [SmartRule.max] (either open). */
    BPM(sound = true),
    /** The key, as its Camelot code ([SmartRule.text], "8A"). */
    KEY(sound = true),
    /** Keys that mix well with [SmartRule.text]: the same, one step round the wheel, or its relative major/minor. */
    GOES_WITH(sound = true),
    /** Energy 0..1 ([com.prism.music.playback.Energy]). */
    ENERGY(sound = true),
    /** Loudness in LUFS. */
    LOUDNESS(sound = true),
    LIKED, DOWNLOADED,
    /** A music file on the phone. */
    ON_PHONE,
    /** Has the tag [SmartRule.text]. */
    TAG,
    /** In the genre [SmartRule.text], as Prism has it without asking anyone (the genres it has saved). */
    GENRE,
    /** [SmartRule.text] in the song's artists. */
    ARTIST,
    /** [SmartRule.text] in the album's name. */
    ALBUM,
    /** Played at least [SmartRule.min] / at most [SmartRule.max] times (Prism's own plays). */
    PLAYS,
    /** Last played within [SmartRule.min] days; [SmartRule.not]: played, but not in them. */
    LAST_PLAYED,
    /** In the library (liked, or first played) within [SmartRule.min] days; [SmartRule.not]: before them. */
    ADDED,
    NEVER_PLAYED,
}

/** One rule; [not] turns it round ("isn't", "not liked"). */
@Serializable
data class SmartRule(
    val field: SmartField,
    val not: Boolean = false,
    val min: Double? = null,
    val max: Double? = null,
    val text: String? = null,
)

@Serializable
enum class SmartSort { MOST_PLAYED, RECENTLY_ADDED, RECENTLY_PLAYED, LEAST_RECENTLY_PLAYED, BPM, ENERGY, TITLE, RANDOM }

/** A smart playlist's rules, sort and length ([limit] null: all of them). */
@Serializable
data class SmartRules(
    val rules: List<SmartRule> = emptyList(),
    val match: SmartMatch = SmartMatch.ALL,
    val sort: SmartSort = SmartSort.MOST_PLAYED,
    val limit: Int? = 50,
) {
    val usesSound get() = rules.any { it.field.sound }
}

/** A query and its arguments. */
data class SmartSql(val sql: String, val args: List<Any?>)

/**
 * Smart playlists' songs (1.8.3): the rules made into one SQL query over the songs Prism knows as the listener's
 * (liked, played, tagged, downloaded or a file on the phone), their measurements, tags and plays. What isn't in the
 * database (downloads, the phone's files, genres) goes into a temporary table first, in the same transaction.
 */
object SmartQuery {
    const val HAVE = "smart_have"
    private const val DAY = 86_400_000L

    /** The plays of each song: how many, the last, the first. */
    private const val PLAYS = "(SELECT songId, COUNT(*) AS n, MAX(timestamp) AS last, MIN(timestamp) AS first FROM play_events GROUP BY songId)"

    /** The FROM and the songs that count as the listener's, shared by the songs and the not-measured count. */
    private const val FROM = """FROM songs s
        LEFT JOIN song_traits tr ON tr.songId = s.id
        LEFT JOIN $PLAYS p ON p.songId = s.id
        LEFT JOIN $HAVE h ON h.id = s.id
        WHERE (s.liked = 1 OR s.lastPlayed > 0 OR p.n > 0 OR h.id IS NOT NULL OR EXISTS (SELECT 1 FROM song_tags t0 WHERE t0.songId = s.id))"""

    /** Camelot neighbours that mix well: "8A" → 8A, 7A, 9A, 8B. */
    fun goesWith(camelot: String): List<String> {
        val m = Regex("^(\\d{1,2})([AB])$").find(camelot.trim().uppercase()) ?: return emptyList()
        val n = m.groupValues[1].toInt().takeIf { it in 1..12 } ?: return emptyList()
        val l = m.groupValues[2]
        fun wrap(x: Int) = ((x - 1 + 12) % 12) + 1
        return listOf("$n$l", "${wrap(n - 1)}$l", "${wrap(n + 1)}$l", "$n${if (l == "A") "B" else "A"}")
    }

    /** One rule as SQL (null: it can't hold, e.g. an empty tag), its arguments added to [args]. */
    private fun rule(r: SmartRule, now: Long, args: MutableList<Any?>): String {
        fun range(col: String): String {
            val parts = mutableListOf("$col IS NOT NULL")
            r.min?.let { parts += "$col >= ?"; args += it }
            r.max?.let { parts += "$col <= ?"; args += it }
            return parts.joinToString(" AND ")
        }
        fun like(text: String?) = "%" + text.orEmpty().trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val sql = when (r.field) {
            SmartField.BPM -> range("tr.bpm")
            SmartField.ENERGY -> range("tr.energy")
            SmartField.LOUDNESS -> range("tr.lufs")
            SmartField.KEY -> { args += r.text.orEmpty().trim().uppercase(); "tr.camelot = ?" }
            SmartField.GOES_WITH -> {
                val keys = goesWith(r.text.orEmpty())
                if (keys.isEmpty()) "0" else { args.addAll(keys); "tr.camelot IN (${keys.joinToString(",") { "?" }})" }
            }
            SmartField.LIKED -> "s.liked = 1"
            // A song with no row in the flags table has NULLs there: COALESCE, or "isn't" (NOT NULL) would drop it.
            SmartField.DOWNLOADED -> "COALESCE(h.down, 0) = 1"
            // What the phone's files are now (a file's songs row outlives the file).
            SmartField.ON_PHONE -> "COALESCE(h.local, 0) = 1"
            SmartField.TAG -> { args += r.text.orEmpty().trim(); "EXISTS (SELECT 1 FROM song_tags t WHERE t.songId = s.id AND t.tag = ? COLLATE NOCASE)" }
            // Each song's own genre (when it's one a rule asks for), so each Genre rule tests its own.
            SmartField.GENRE -> { args += r.text.orEmpty().trim().lowercase(); "COALESCE(h.genre, '') = ?" }
            SmartField.ARTIST -> { args += like(r.text); "COALESCE(s.artistName, '') LIKE ? ESCAPE '\\'" }
            SmartField.ALBUM -> { args += like(r.text); "COALESCE(s.albumTitle, '') LIKE ? ESCAPE '\\'" }
            SmartField.PLAYS -> {
                val parts = mutableListOf<String>()
                r.min?.let { parts += "COALESCE(p.n, 0) >= ?"; args += it.toLong() }
                r.max?.let { parts += "COALESCE(p.n, 0) <= ?"; args += it.toLong() }
                parts.joinToString(" AND ").ifEmpty { "1" }
            }
            SmartField.LAST_PLAYED -> {
                args += now - ((r.min ?: 30.0) * DAY).toLong()
                // Turned round: played, but not lately (a song never played isn't "played before").
                return if (r.not) "(p.last IS NOT NULL AND p.last < ?)" else "(p.last >= ?)"
            }
            SmartField.ADDED -> {
                args += now - ((r.min ?: 30.0) * DAY).toLong()
                val added = "COALESCE(NULLIF(s.likedAt, 0), p.first)"
                return if (r.not) "($added IS NOT NULL AND $added < ?)" else "($added >= ?)"
            }
            SmartField.NEVER_PLAYED -> "COALESCE(p.n, 0) = 0"
        }
        return if (r.not) "NOT ($sql)" else "($sql)"
    }

    private fun where(rules: SmartRules, now: Long, args: MutableList<Any?>): String {
        if (rules.rules.isEmpty()) return ""
        val joined = rules.rules.joinToString(if (rules.match == SmartMatch.ALL) " AND " else " OR ") { rule(it, now, args) }
        return " AND ($joined)"
    }

    private fun order(sort: SmartSort): String = when (sort) {
        SmartSort.MOST_PLAYED -> "COALESCE(p.n, 0) DESC, p.last DESC"
        SmartSort.RECENTLY_ADDED -> "COALESCE(NULLIF(s.likedAt, 0), p.first, 0) DESC"
        SmartSort.RECENTLY_PLAYED -> "COALESCE(p.last, 0) DESC"
        SmartSort.LEAST_RECENTLY_PLAYED -> "COALESCE(p.last, 0) ASC"
        SmartSort.BPM -> "tr.bpm IS NULL, tr.bpm ASC"
        SmartSort.ENERGY -> "tr.energy IS NULL, tr.energy DESC"
        SmartSort.TITLE -> "s.title COLLATE NOCASE ASC"
        // Shuffled after, all of them (SmartPlaylists.run).
        SmartSort.RANDOM -> "s.id"
    } + ", s.id"

    /** The songs, sorted and cut to the limit (Random: all of them, shuffled and cut after). */
    fun songs(rules: SmartRules, now: Long): SmartSql {
        val args = mutableListOf<Any?>()
        val w = where(rules, now, args)
        val limit = rules.limit?.takeIf { rules.sort != SmartSort.RANDOM }?.let { " LIMIT $it" } ?: ""
        return SmartSql("SELECT s.* $FROM$w ORDER BY ${order(rules.sort)}$limit", args)
    }

    /**
     * How many of the listener's songs the Sound rules can't see yet (no measurement): with "all", those that meet the
     * other rules; with "any", all of them. 0 without Sound rules.
     */
    fun unmeasured(rules: SmartRules, now: Long): SmartSql? {
        if (!rules.usesSound) return null
        val args = mutableListOf<Any?>()
        val others = rules.copy(rules = if (rules.match == SmartMatch.ALL) rules.rules.filter { !it.field.sound } else emptyList())
        val w = where(others, now, args)
        return SmartSql("SELECT COUNT(*) $FROM AND tr.songId IS NULL$w", args)
    }

    /** Ids of the listener's songs with no measurement, the most played first (what "Measure now" scans). */
    fun unmeasuredIds(rules: SmartRules, now: Long, limit: Int): SmartSql? {
        val count = unmeasured(rules, now) ?: return null
        return SmartSql(count.sql.replace("SELECT COUNT(*)", "SELECT s.id") + " ORDER BY COALESCE(p.n, 0) DESC LIMIT $limit", count.args)
    }

    /** The database's songs that count as the listener's, downloads and files aside (for working out genres first). */
    const val KNOWN = """SELECT s.* FROM songs s WHERE s.liked = 1 OR s.lastPlayed > 0
        OR EXISTS (SELECT 1 FROM play_events e WHERE e.songId = s.id) OR EXISTS (SELECT 1 FROM song_tags t0 WHERE t0.songId = s.id)"""
}

/**
 * "Measure now" (1.8.3): songs a smart playlist's Sound rules can't see yet, scanned one after another in the background
 * with the scan Normalize and DJ mode use. Songs not on the phone are fetched only on an unmetered network ([unmetered]).
 * [progress]: done of all, while it runs.
 */
class SmartMeasure(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val measure: suspend (id: String, network: Boolean) -> Unit,
    private val unmetered: () -> Boolean,
) {
    val progress = kotlinx.coroutines.flow.MutableStateFlow<Pair<Int, Int>?>(null)
    private var job: kotlinx.coroutines.Job? = null

    /** Starts on [ids] unless it's already measuring; whether songs not on the phone will be fetched. */
    fun start(ids: List<String>): Boolean {
        val network = unmetered()
        if (job?.isActive == true || ids.isEmpty()) return network
        job = scope.launch(Dispatchers.IO) {
            progress.value = 0 to ids.size
            try {
                ids.forEachIndexed { i, id ->
                    runCatching { measure(id, network) }
                    progress.value = i + 1 to ids.size
                }
            } finally { progress.value = null }
        }
        return network
    }

    fun stop() { job?.cancel() }

    companion object {
        /** The most songs one tap measures (each is a whole song's scan). */
        const val AT_ONCE = 100
    }
}

/** What a smart playlist shows: its songs, and how many of the listener's songs its Sound rules can't see yet. */
data class SmartResult(val songs: List<Song>, val unmeasured: Int)

/**
 * Runs smart playlists' queries ([SmartQuery]) on the database. [downloaded] and [phoneFiles] are what the phone has
 * (not in the database); [genres] works out songs' genres from what's saved (no network).
 */
class SmartPlaylists(
    private val db: PrismDatabase,
    private val downloaded: () -> List<Song>,
    private val phoneFiles: () -> List<Song>,
    private val genres: suspend (List<Song>) -> Map<String, String>,
) {
    /** Downloads and files known to have a songs row. */
    private val hasRow: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** What the temporary table holds (set and read inside the writer's transaction); compared whole, not by hash. */
    @Volatile private var filled: Map<String, Have>? = null

    /** A song's row in the temporary table: downloaded, a file on the phone, its genre when a rule asks for it. */
    private data class Have(val down: Boolean = false, val local: Boolean = false, val genre: String? = null)

    suspend fun run(rules: SmartRules, now: Long = System.currentTimeMillis()): SmartResult = withContext(Dispatchers.IO) {
        val down = downloaded()
        val files = phoneFiles()
        // Downloads and files the database doesn't have a row for yet get one (only what's missing; songs rows are never
        // deleted, so ones seen once needn't be looked up again).
        (down + files).distinctBy { it.id }.filter { it.id !in hasRow }.chunked(500).forEach { part ->
            val have = db.songs().getAll(part.map { it.id }).map { it.id }.toHashSet()
            part.filter { it.id !in have }.takeIf { it.isNotEmpty() }?.let { db.songs().upsertAll(it.map { s -> s.toEntity() }) }
            part.forEach { hasRow += it.id }
        }
        // Genres come from what Prism saved of them (several lookups per song): worked out before the query, for the
        // listener's songs only, and only when a rule asks.
        val genreRules = rules.rules.filter { it.field == SmartField.GENRE }
        val genreOf: Map<String, String> = if (genreRules.isEmpty()) emptyMap() else {
            val known = rows(db.openHelper.readableDatabase, androidx.sqlite.db.SimpleSQLiteQuery(SmartQuery.KNOWN)).map { it.toSong() }
            val wanted = genreRules.mapNotNull { it.text?.trim()?.lowercase() }.toSet()
            genres((known + down + files).distinctBy { it.id }).mapValues { it.value.trim().lowercase() }.filterValues { it in wanted }
        }
        db.runInTransaction<SmartResult> {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("CREATE TEMP TABLE IF NOT EXISTS ${SmartQuery.HAVE} (id TEXT PRIMARY KEY, down INTEGER NOT NULL DEFAULT 0, local INTEGER NOT NULL DEFAULT 0, genre TEXT)")
            // Each song's row worked out here. The table (on the one writer connection) is refilled only when they
            // changed or it lost its rows; in rows of 200 per statement (4 arguments each, under old SQLite's 999).
            val flags = HashMap<String, Have>()
            down.forEach { flags[it.id] = Have(down = true) }
            files.forEach { flags[it.id] = (flags[it.id] ?: Have()).copy(local = true) }
            genreOf.forEach { (id, g) -> flags[id] = (flags[id] ?: Have()).copy(genre = g) }
            val rowsNow = sql.query("SELECT COUNT(*) FROM ${SmartQuery.HAVE}").use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }
            if (flags != filled || rowsNow != flags.size) {
                sql.execSQL("DELETE FROM ${SmartQuery.HAVE}")
                flags.entries.chunked(200).forEach { part ->
                    val args = ArrayList<Any?>(part.size * 4)
                    part.forEach { (id, h) -> args += id; args += if (h.down) 1 else 0; args += if (h.local) 1 else 0; args += h.genre }
                    sql.execSQL(
                        "INSERT INTO ${SmartQuery.HAVE} (id, down, local, genre) VALUES " + part.joinToString(",") { "(?,?,?,?)" },
                        args.toTypedArray(),
                    )
                }
                filled = HashMap(flags)
            }
            val q = SmartQuery.songs(rules, now)
            val found = rows(sql, androidx.sqlite.db.SimpleSQLiteQuery(q.sql, q.args.toTypedArray())).map { it.toSong() }
            // Random: the same order all day (a list that reshuffles itself each time it's opened would be no list).
            val songs = if (rules.sort != SmartSort.RANDOM) found
                else found.shuffled(kotlin.random.Random(now / 86_400_000L)).let { s -> rules.limit?.let { s.take(it) } ?: s }
            val unmeasured = SmartQuery.unmeasured(rules, now)?.let { u ->
                sql.query(androidx.sqlite.db.SimpleSQLiteQuery(u.sql, u.args.toTypedArray())).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            } ?: 0
            SmartResult(songs, unmeasured)
        }
    }

    /** Ids of up to [limit] songs the rules' Sound part can't see yet (for "Measure now"). */
    suspend fun unmeasuredIds(rules: SmartRules, limit: Int, now: Long = System.currentTimeMillis()): List<String> = withContext(Dispatchers.IO) {
        val q = SmartQuery.unmeasuredIds(rules, now, limit) ?: return@withContext emptyList()
        db.runInTransaction<List<String>> {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("CREATE TEMP TABLE IF NOT EXISTS ${SmartQuery.HAVE} (id TEXT PRIMARY KEY, down INTEGER NOT NULL DEFAULT 0, local INTEGER NOT NULL DEFAULT 0, genre INTEGER NOT NULL DEFAULT 0)")
            sql.query(androidx.sqlite.db.SimpleSQLiteQuery(q.sql, q.args.toTypedArray())).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        }
    }

    private fun rows(sql: androidx.sqlite.db.SupportSQLiteDatabase, q: androidx.sqlite.db.SupportSQLiteQuery): List<SongEntity> =
        sql.query(q).use { c ->
            fun col(n: String) = c.getColumnIndexOrThrow(n)
            val id = col("id"); val title = col("title"); val artistName = col("artistName"); val artistId = col("artistId")
            val albumTitle = col("albumTitle"); val albumId = col("albumId"); val dur = col("durationSec"); val thumb = col("thumbnail")
            val video = col("isVideo"); val liked = col("liked"); val likedAt = col("likedAt"); val last = col("lastPlayed")
            val smart = col("smartDownload"); val primary = col("primaryArtist"); val json = col("artistsJson")
            buildList {
                while (c.moveToNext()) add(
                    SongEntity(
                        c.getString(id), c.getString(title), c.getString(artistName), c.getString(artistId), c.getString(albumTitle),
                        c.getString(albumId), c.getInt(dur), c.getString(thumb), c.getInt(video) != 0, c.getInt(liked) != 0, c.getLong(likedAt),
                        c.getLong(last), c.getInt(smart) != 0, c.getString(primary).orEmpty(), c.getString(json),
                    ),
                )
            }
        }
}
