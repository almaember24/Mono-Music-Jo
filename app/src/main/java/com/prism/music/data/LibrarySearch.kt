package com.prism.music.data

import com.prism.music.data.db.RomanizedDao
import com.prism.music.data.db.RomanizedEntity
import com.prism.music.data.lyrics.roman.Script
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** What a library search can be narrowed to (1.8.3). */
enum class LibKind { SONG, PLAYLIST, TAG, ARTIST, ALBUM }

/**
 * A library search's filters: one kind (null: every kind), and for songs, liked / downloaded / a file on this phone
 * (all that are on must hold). A song filter on leaves only songs.
 */
data class LibFilter(val kind: LibKind? = null, val liked: Boolean = false, val downloaded: Boolean = false, val onPhone: Boolean = false) {
    val songFlags get() = liked || downloaded || onPhone
    fun allows(k: LibKind) = (kind == null || kind == k) && (!songFlags || k == LibKind.SONG)
}

/** One thing in the library to find: what it is, the names it goes by, and (a song) where it is. */
class LibEntry(
    val kind: LibKind,
    val item: BrowseItem,
    val names: List<String>,
    val liked: Boolean = false,
    val downloaded: Boolean = false,
    val onPhone: Boolean = false,
)

/**
 * Searching your library (1.8.3): songs (liked, downloaded, files on the phone, in your playlists, tagged), playlists,
 * tags, artists and albums, each word typed anywhere in a name, without minding capitals or accents, and in Latin
 * letters for titles in other scripts (#363) once [TitleSpellings] has spelled them.
 */
object LibrarySearch {
    private val MARKS = Regex("\\p{Mn}+")
    // Marks that stay (Hindi's and Bengali's vowel signs) are part of the word, not a gap in it.
    private val NOT_WORDS = Regex("[^\\p{L}\\p{M}\\p{N}]+")

    /** Lower case, accents off, punctuation as spaces ("Beyoncé" → "beyonce", "AC/DC" → "ac dc"). */
    fun norm(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(MARKS, "").replace(NOT_WORDS, " ").trim()

    /**
     * The library's songs as entries, each once: [liked], [downloaded] and [files] say where a song is; [others] are
     * songs that are the listener's some other way (in a playlist, tagged).
     */
    fun songEntries(liked: List<Song>, downloaded: List<Song>, files: List<Song>, others: List<Song>): List<LibEntry> {
        val likedIds = liked.mapTo(HashSet()) { it.id }
        val downIds = downloaded.mapTo(HashSet()) { it.id }
        val fileIds = files.mapTo(HashSet()) { it.id }
        val seen = HashSet<String>()
        return (liked + downloaded + files + others).filter { seen.add(it.id) }.map { s ->
            LibEntry(
                LibKind.SONG, SongItem(s), listOfNotNull(s.title, s.artistText, s.album?.title),
                liked = s.id in likedIds, downloaded = s.id in downIds, onPhone = s.id in fileIds || s.id.startsWith(Song.LOCAL_PREFIX),
            )
        }
    }

    /** An index of [entries] to search as one types, with [spellings] (a name → its Latin spelling) in it too. */
    class Index(val entries: List<LibEntry>, spellings: Map<String, String>) {
        internal val titles = Array(entries.size) { norm(spelled(entries[it].names.first(), spellings)) }
        internal val hays = Array(entries.size) { i -> entries[i].names.joinToString(" ") { norm(spelled(it, spellings)) } }

        private fun spelled(name: String, spellings: Map<String, String>) = spellings[name]?.let { "$name $it" } ?: name
    }

    /**
     * What [query] finds in [index] with [filter], grouped by kind (songs first) and in each, names that start with
     * it first, then those it's all in the title of, then the rest; at most [limit].
     */
    fun find(index: Index, query: String, filter: LibFilter, limit: Int = 500): List<LibEntry> {
        val q = norm(query)
        val words = q.split(' ').filter { it.isNotBlank() }
        val hits = ArrayList<Pair<Int, Int>>()
        for (i in index.entries.indices) {
            val e = index.entries[i]
            if (!filter.allows(e.kind)) continue
            if (filter.liked && !e.liked || filter.downloaded && !e.downloaded || filter.onPhone && !e.onPhone) continue
            val rank = when {
                // Nothing typed (filters alone): everything they let through.
                words.isEmpty() -> if (query.isBlank()) 2 else if (e.names.any { it.contains(query.trim(), ignoreCase = true) }) 1 else null
                !words.all { it in index.hays[i] } -> null
                index.titles[i].startsWith(q) -> 0
                words.all { it in index.titles[i] } -> 1
                else -> 2
            } ?: continue
            hits += i to rank
        }
        return hits.sortedWith(compareBy<Pair<Int, Int>> { index.entries[it.first].kind.ordinal }.thenBy { it.second })
            .take(limit).map { index.entries[it.first] }
    }
}

/**
 * Titles in other scripts spelled in Latin letters for searching (#363), with the phone's engines ([spell]): worked out
 * in the background in small batches when a search wants them (never at start-up), and kept in the `romanized` table so
 * each title is spelled once. [japaneseReady]: whether kanji can be read now (a title spelled without the dictionary is
 * spelled again once it's there).
 */
class TitleSpellings(
    private val dao: RomanizedDao,
    private val scope: CoroutineScope,
    private val japaneseReady: () -> Boolean,
    private val spell: suspend (String) -> String?,
) {
    private val _known = MutableStateFlow<Map<String, String>>(emptyMap())
    /** A title → its Latin spelling, for those spelled so far. */
    val known: StateFlow<Map<String, String>> = _known
    private val asked: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val queue = ConcurrentLinkedQueue<String>()
    @Volatile private var job: Job? = null

    private fun key(text: String) = "title:$VERSION:${if (japaneseReady()) "ja" else "-"}:$text"

    /** Spells those of [titles] in other scripts not spelled yet, in the background. */
    fun want(titles: Collection<String>) {
        titles.forEach { t -> if (Script.foreignIn(t).isNotEmpty() && asked.add(key(t))) queue += t }
        if (queue.isEmpty() || job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val found = HashMap<String, String>()
            var n = 0
            while (true) {
                val text = queue.poll() ?: break
                val k = key(text)
                val spelled = runCatching { dao.get(k) }.getOrNull()?.content ?: (runCatching { spell(text) }.getOrNull() ?: "").also {
                    // "" too: a title no engine here can spell isn't tried again.
                    runCatching { dao.put(RomanizedEntity(k, it, System.currentTimeMillis())) }
                }
                if (spelled.isNotBlank()) found[text] = spelled
                if (++n % BATCH == 0) {
                    if (found.isNotEmpty()) { _known.value = _known.value + found; found.clear() }
                    delay(PAUSE_MS)
                }
            }
            if (found.isNotEmpty()) _known.value = _known.value + found
        }.also { j ->
            // Titles queued after the loop found it empty, but while the job still counted as active, would wait for the
            // next search: once it's done, it starts again for them (not after a cancel: the scope is closing).
            j.invokeOnCompletion { cause -> if (cause == null && queue.isNotEmpty()) want(emptyList()) }
        }
    }

    companion object {
        const val VERSION = 1
        const val BATCH = 40
        const val PAUSE_MS = 30L
    }
}
