package com.prism.music.data.lyrics

import com.prism.music.data.db.LyricsSearchDao
import com.prism.music.data.db.LyricsText
import com.prism.music.data.db.SongDao
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * Search finds songs by a line of their lyrics (1.7.6): typed into the ordinary search box, no separate mode. YouTube's
 * search only matches the words of a song's title, so a line ("i got my driver's license last week") gave songs that
 * merely shared a word with it. The line is looked for in the lyrics saved on this phone first (every song shown with
 * lyrics keeps them), then in Genius's lyrics index, whose answers name the song and the line it matched; each is then
 * found on YouTube Music. Only queries that read like a line (four words or more) are asked.
 */
class LyricSearch(
    http: OkHttpClient,
    private val ytm: YouTubeMusic,
    /** Saved lyrics that have a word in them (at most so many). */
    private val savedWith: suspend (word: String, limit: Int) -> List<LyricsText>,
    private val songsById: suspend (List<String>) -> List<Song>,
    private val lines: (String) -> List<String>,
) {
    constructor(http: OkHttpClient, ytm: YouTubeMusic, lyricsDao: LyricsSearchDao, songDao: SongDao, lines: (String) -> List<String>) :
        this(http, ytm, lyricsDao::containing, { ids -> songDao.getAll(ids).map { it.toSong() } }, lines)

    /** A song whose lyrics have the line typed: [line] is that line as the song has it; [sure] = nearly word for word. */
    data class Hit(val song: Song, val line: String, val score: Float) {
        val sure: Boolean get() = score >= SURE
    }

    private val http = http.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()

    suspend fun find(query: String): List<Hit> = coroutineScope {
        val words = words(query)
        if (!readsLikeALine(words)) return@coroutineScope emptyList()
        val local = async { runCatching { onThisPhone(query, words) }.getOrDefault(emptyList()) }
        val genius = async { runCatching { fromGenius(query, words) }.getOrDefault(emptyList()) }
        oneEach(local.await() + genius.await())
    }

    /** Lyrics saved on the phone: matched in full, on the spot, offline too. */
    internal suspend fun onThisPhone(query: String, words: List<String>): List<Hit> = withContext(Dispatchers.IO) {
        // The rarest-looking words (the two longest) narrow the table down in SQL first, written as typed. Two, since
        // the saved text has its apostrophes and accents and the typing may not: "drivers" isn't in "driver's".
        val typed = query.lowercase()
        val probes = words.distinct().sortedWith(compareBy({ it !in typed }, { -it.length })).take(2)
        val rows = probes.flatMap { savedWith(it, LOCAL_ROWS) }.distinctBy { it.songId }
        val best = rows.mapNotNull { r ->
            val (line, score) = bestLine(words, lines(r.content)) ?: return@mapNotNull null
            if (score < LOCAL_MIN) null else Triple(r.songId, line, score)
        }.sortedByDescending { it.third }.distinctBy { it.first }.take(MAX_HITS)
        if (best.isEmpty()) return@withContext emptyList()
        val songs = songsById(best.map { it.first }).associateBy { it.id }
        best.mapNotNull { (id, line, score) -> songs[id]?.let { Hit(it, line, score) } }
    }

    /** Genius's lyrics index: songs whose lyrics have the line, then the same songs on YouTube Music. */
    internal suspend fun fromGenius(query: String, words: List<String>): List<Hit> = coroutineScope {
        val url = "https://genius.com/api/search/multi".toHttpUrl().newBuilder().addQueryParameter("q", query).build()
        val body = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).header("User-Agent", BROWSER_UA).build()).execute()
                .use { r -> if (r.isSuccessful) r.body.string() else null }
        } ?: return@coroutineScope emptyList()
        val found = geniusHits(InnerTube.json.parseToJsonElement(body), words)
        found.take(MAX_HITS).map { g ->
            async { runCatching { onYouTube(g) }.getOrNull() }
        }.awaitAll().filterNotNull()
    }

    private suspend fun onYouTube(g: GeniusHit): Hit? {
        val songs = ytm.search("${g.title} ${g.artist}", SearchFilter.SONGS).items.filterIsInstance<SongItem>().map { it.song }
        val title = key(bare(g.title))
        val artist = key(g.artist)
        val song = songs.firstOrNull { s ->
            val t = key(bare(s.title))
            (t == title || t.startsWith(title) || title.startsWith(t)) && s.artists.any { a -> key(a.name).let { it.contains(artist) || artist.contains(it) } }
        } ?: return null
        return Hit(song, g.line, g.score)
    }

    data class GeniusHit(val title: String, val artist: String, val line: String, val score: Float)

    companion object {
        private const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
        const val MAX_HITS = 3
        private const val LOCAL_ROWS = 300
        /** A share of the typed words found in order in the song's line (and the one after it) for it to count. */
        private const val LOCAL_MIN = 0.8f
        private const val GENIUS_MIN = 0.75f
        /** Nearly word for word: shown above the title matches. */
        const val SURE = 0.9f

        /**
         * One hit per song, best match first. Songs of one title that have the typed line are one song (its demo, a cover:
         * Genius lists Olivia Rodrigo's "drivers license", its demo, then jxdn's cover): the first stays, which is what's
         * on the phone, then Genius's own order. Otherwise Search, which leaves out a hit it already shows as the top
         * result, put the cover alone under "From the lyrics" above the song itself.
         */
        fun oneEach(hits: List<Hit>): List<Hit> =
            hits.sortedByDescending { it.score }.distinctBy { titleKey(it.song.title) }.take(MAX_HITS)

        /** A title as one key, without its brackets or punctuation: "Shape of You (Major Lazer Remix)" = "shapeofyou". */
        fun titleKey(title: String): String = key(bare(title))

        /**
         * What Search shows under "From the lyrics": nothing when the line's song is already on the page (by id, or a
         * song of the same title: another upload or a remix of it), since then the other hits are songs that quote the
         * line ("It's me, hi, I'm the problem" in a GloRilla song, Weird Al's polka of "Shake It Off").
         */
        fun toShow(hits: List<Hit>, shownIds: Set<String>, shownTitles: List<String>): List<Hit> {
            val titles = shownTitles.map(::titleKey).toSet()
            return if (hits.any { it.song.id in shownIds || titleKey(it.song.title) in titles }) emptyList() else hits
        }

        /** Four words or more, as a line is: "daft punk one more" stays an ordinary search's. */
        fun readsLikeALine(words: List<String>): Boolean = words.size >= 4 && words.sumOf { it.length } >= 14

        /** Lower case, no accents or apostrophes ("driver's" = "drivers"), split into words. */
        fun words(s: String): List<String> = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(MARKS, "").replace(APOSTROPHES, "").replace(NOT_WORDS, " ").trim().split(' ').filter { it.isNotBlank() }

        private fun key(s: String) = words(s).joinToString("")

        /** A title without its brackets ("Love Story (Taylor's Version)" → "Love Story"). */
        private fun bare(title: String) = title.replace(BRACKETS, "").trim().ifBlank { title }

        /**
         * How much of [query] a passage has, in order and close together: the most typed words found in the passage's
         * own order (skipping a word here or there) within a stretch hardly longer than what was typed, as a share of
         * the words typed. "the best you ever had" is all in "make up the best years you and I have ever had" in order,
         * but spread over ten words: that's not the line (the 1.7.6 test build showed it as a match).
         */
        fun coverage(query: List<String>, passage: List<String>): Float {
            if (query.isEmpty() || passage.isEmpty()) return 0f
            val span = query.size + maxOf(2, query.size / 4)
            if (passage.size <= span) return lcs(query, passage).toFloat() / query.size
            var best = 0
            for (start in 0..passage.size - span) {
                best = maxOf(best, lcs(query, passage.subList(start, start + span)))
                if (best == query.size) break
            }
            return best.toFloat() / query.size
        }

        private fun lcs(a: List<String>, b: List<String>): Int {
            val prev = IntArray(b.size + 1)
            val cur = IntArray(b.size + 1)
            for (x in a) {
                for (j in 1..b.size) cur[j] = if (x == b[j - 1]) prev[j - 1] + 1 else maxOf(prev[j], cur[j - 1])
                cur.copyInto(prev)
            }
            return prev[b.size]
        }

        /** The song's line the typed words best match (a line can run on into the next one), and how well. */
        fun bestLine(query: List<String>, lines: List<String>): Pair<String, Float>? {
            var best: Pair<String, Float>? = null
            for (i in lines.indices) {
                val one = words(lines[i])
                if (one.isEmpty()) continue
                // A typed line often spans two of the song's: "…real life is this just fantasy" is one line, but
                // "…fantasy caught in a landslide" runs into the next.
                val two = if (i + 1 < lines.size) one + words(lines[i + 1]) else one
                val s1 = coverage(query, one)
                val s2 = coverage(query, two)
                val (text, s) = if (s2 > s1 + 0.01f) "${lines[i].trim()} / ${lines[i + 1].trim()}" to s2 else lines[i].trim() to s1
                // As good a match in less: the line itself, not the line before it run into it.
                if (best == null || s > best.second || (s == best.second && text.length < best.first.length)) best = text to s
            }
            return best
        }

        /** Genius's "lyric" answers that really have the line, best first. Its "top hit" can be a film script. */
        fun geniusHits(json: JsonElement, query: List<String>): List<GeniusHit> {
            val sections = ((json as? JsonObject)?.get("response") as? JsonObject)?.get("sections") as? JsonArray ?: return emptyList()
            val hits = sections.filterIsInstance<JsonObject>().filter { str(it, "type") == "lyric" }
                .flatMap { (it["hits"] as? JsonArray).orEmpty() }.filterIsInstance<JsonObject>()
            return hits.mapNotNull { h ->
                if (str(h, "type") != "song") return@mapNotNull null
                val r = h["result"] as? JsonObject ?: return@mapNotNull null
                val title = str(r, "title")?.trim().takeUnless { it.isNullOrBlank() } ?: return@mapNotNull null
                val artist = (str(r, "primary_artist_names") ?: (r["primary_artist"] as? JsonObject)?.let { str(it, "name") })
                    ?.trim().takeUnless { it.isNullOrBlank() } ?: return@mapNotNull null
                // Genius's own pages of translations and transcripts aren't songs to play.
                if (artist.startsWith("Genius ", ignoreCase = true) || artist.equals("Movie Scripts", true) || title.contains("(Script)", true)) return@mapNotNull null
                val text = (h["highlights"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                    .firstOrNull { str(it, "property") == "lyrics" }?.let { str(it, "value") } ?: return@mapNotNull null
                val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("[") && !it.startsWith("(Verse") && !it.startsWith("Lyrics from") }
                val (line, score) = bestLine(query, lines) ?: return@mapNotNull null
                if (score < GENIUS_MIN) null else GeniusHit(title, artist, line, score)
            }.sortedByDescending { it.score }.distinctBy { key(bare(it.title)) + "|" + key(it.artist) }
        }

        private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private val MARKS = Regex("\\p{Mn}+")
        private val APOSTROPHES = Regex("['’`]")
        private val NOT_WORDS = Regex("[^\\p{L}\\p{N}]+")
        private val BRACKETS = Regex("\\s*[(\\[][^)\\]]*[)\\]]")
    }
}
