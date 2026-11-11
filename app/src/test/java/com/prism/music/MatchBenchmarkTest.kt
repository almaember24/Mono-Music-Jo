package com.prism.music

import com.prism.music.data.imports.ImportKeys
import com.prism.music.data.imports.MatchStatus
import com.prism.music.data.imports.TrackMatcher
import com.prism.music.data.imports.TrackToMatch
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.data.prefs.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The matcher's benchmark (1.8.1, plan §3): a gold set of songs (charts, songs not in English, and traps: live and
 * remastered versions, titles several artists share, featured artists, symbols in names), each with the YouTube Music
 * song it is, run against YouTube Music's answers recorded once (`match-bench/answers.json`), so it gives the same
 * result every run. Two ways a song arrives: with its length (Spotify's extended history knows it) and without
 * (account data, playlists, Last.fm). Reported: how many matches are sure, and how many sure ones are right; a sure
 * match must never be wrong in the first 100 and ≥ 97% right in all.
 *
 * "Right" is the labelled song, or the same recording on another release (YouTube often has the single's and the
 * album's upload): same artist, same title and version, lengths within 2 s. A gold song labelled with no song (the
 * version asked for isn't on YouTube Music) is right only when the matcher isn't sure.
 *
 * To add songs: a line in `gold-input.txt`, then [record] with PRISM_MATCH_RECORD=1 (live; it asks YouTube Music, a
 * search a second, and labels by a stricter rule than the matcher's own), then read the `[label]` lines and fix any
 * label by hand in gold.json.
 */
class MatchBenchmarkTest {
    @Serializable
    data class Cand(val id: String, val title: String, val artists: List<String>, val album: String? = null, val sec: Int = 0, val explicit: Boolean = false, val video: Boolean = false) {
        fun song() = Song(id, title, artists.map { ArtistRef(it) }, album?.let { AlbumRef(it) }, sec, explicit = explicit, isVideo = video)
    }

    @Serializable
    data class Gold(
        val title: String, val artist: String, val album: String? = null, val kind: String,
        /** The YouTube Music song it is; null: not on YouTube Music as asked (the matcher mustn't be sure). */
        val expect: String? = null,
        /** The song's length as Spotify would give it (the labelled song's, give or take a second). */
        val ms: Long = 0,
    )

    private val dir = listOf("src/test/resources/match-bench", "app/src/test/resources/match-bench").map(::File).first { it.isDirectory }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val answersSer = MapSerializer(String.serializer(), ListSerializer(Cand.serializer()))

    data class Score(val total: Int, val sure: Int, val sureRight: Int, val firstHundredWrong: Int, val review: Int, val none: Int, val wrong: List<String>, val unsure: List<String> = emptyList()) {
        val precision get() = if (sure == 0) 1.0 else sureRight.toDouble() / sure
        override fun toString() = "sure $sure of $total (${"%.1f".format(sure * 100.0 / total)}%), right ${"%.1f".format(precision * 100)}%, " +
            "wrong in the first 100: $firstHundredWrong, needs a look $review, not found $none"
    }

    private fun sameRecording(a: Song, b: Cand): Boolean {
        fun art(s: String) = ImportKeys.norm(s).removePrefix("the ")
        return a.artists.any { x -> b.artists.any { art(it) == art(x.name) } } && ImportKeys.core(a.title) == ImportKeys.core(b.title) &&
            ImportKeys.versionMarks(a.title) - "remaster" == ImportKeys.versionMarks(b.title) - "remaster" && a.durationSec > 0 && abs(a.durationSec - b.sec) <= 2
    }

    private fun score(gold: List<Gold>, answers: Map<String, List<Cand>>, withLength: Boolean): Score = runBlocking {
        val byId = answers.values.flatten().associateBy { it.id }
        val matcher = TrackMatcher { q -> answers[q].orEmpty().map { it.song() } }
        var sure = 0; var right = 0; var firstWrong = 0; var review = 0; var none = 0
        val wrong = ArrayList<String>()
        val unsure = ArrayList<String>()
        gold.forEachIndexed { i, g ->
            val r = matcher.match(TrackToMatch(g.title, g.artist, g.album, durationMs = if (withLength) g.ms else 0))
            when (r.status) {
                MatchStatus.SURE -> {
                    sure++
                    val want = g.expect?.let { byId[it] }
                    val ok = want != null && (r.song!!.id == want.id || sameRecording(r.song!!, want))
                    if (ok) right++ else {
                        wrong += "${g.title} · ${g.artist} → ${r.song?.title} · ${r.song?.artistText} (${r.song?.id}, ${r.song?.durationSec}s), want ${want?.let { "${it.title} (${it.id}, ${it.sec}s)" } ?: "no sure match"}"
                        if (i < 100) firstWrong++
                    }
                }
                MatchStatus.REVIEW -> { review++; unsure += "needs a look: ${g.title} · ${g.artist}" }
                else -> { none++; unsure += "not found: ${g.title} · ${g.artist}" }
            }
        }
        Score(gold.size, sure, right, firstWrong, review, none, wrong, unsure)
    }

    @Test fun benchmark() {
        val goldFile = File(dir, "gold.json")
        val answersFile = File(dir, "answers.json")
        assumeTrue("record the benchmark first (PRISM_MATCH_RECORD=1)", goldFile.exists() && answersFile.exists())
        val gold = json.decodeFromString(ListSerializer(Gold.serializer()), goldFile.readText())
        val answers = json.decodeFromString(answersSer, answersFile.readText())
        val timed = score(gold, answers, withLength = true)
        val untimed = score(gold, answers, withLength = false)
        println("[bench] ${gold.size} songs, ${gold.count { it.expect == null }} labelled as not on YouTube Music as asked")
        println("[bench] with lengths (extended history): $timed")
        timed.wrong.forEach { println("[bench]   wrong: $it") }
        timed.unsure.forEach { println("[bench]   $it") }
        println("[bench] without lengths (account data, playlists, Last.fm): $untimed")
        untimed.wrong.forEach { println("[bench]   wrong: $it") }
        assertEquals("sure but wrong in the first 100 (with lengths)", 0, timed.firstHundredWrong)
        assertTrue("sure matches right (with lengths): ${timed.precision}", timed.precision >= 0.97)
        assertEquals("sure but wrong in the first 100 (without lengths)", 0, untimed.firstHundredWrong)
        assertTrue("sure matches right (without lengths): ${untimed.precision}", untimed.precision >= 0.97)
    }

    /** Live: asks YouTube Music for each gold song once and keeps its answers, then labels gold.json. */
    @Test fun record() = runBlocking {
        assumeTrue("live recording: set PRISM_MATCH_RECORD=1", System.getenv("PRISM_MATCH_RECORD") == "1")
        var settings = AppSettings()
        val ytm = YouTubeMusic(InnerTube(OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build(), { settings }, { settings = settings.copy(visitorData = it) }))
        val input = File(dir, "gold-input.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val p = line.split(" | ").map { it.trim() }
            Gold(p[0], p[1], p.getOrNull(2)?.takeIf { it.isNotBlank() }, p.getOrNull(3) ?: "chart")
        }
        val answersFile = File(dir, "answers.json")
        val answers = (if (answersFile.exists()) json.decodeFromString(answersSer, answersFile.readText()) else emptyMap()).toMutableMap()
        suspend fun ask(q: String) {
            if (q in answers) return
            val found = runCatching { ytm.search(q, SearchFilter.SONGS).items.filterIsInstance<SongItem>().map { it.song } }.getOrElse { println("[record] failed: $q: $it"); return }
            answers[q] = found.take(10).map { s -> Cand(s.id, s.title, s.artists.map { it.name }, s.album?.title, s.durationSec, s.explicit, s.isVideo) }
            delay(900)
        }
        input.forEach { g -> val t = TrackToMatch(g.title, g.artist, g.album); ask(TrackMatcher.queryFor(t)); ask(TrackMatcher.plainQuery(t)) }
        answersFile.writeText(json.encodeToString(answersSer, answers.toSortedMap()))
        val old = File(dir, "gold.json").takeIf { it.exists() }?.let { json.decodeFromString(ListSerializer(Gold.serializer()), it.readText()) }.orEmpty()
            .associateBy { it.title + "|" + it.artist }
        val gold = input.map { g -> old[g.title + "|" + g.artist] ?: label(g, answers[TrackMatcher.queryFor(TrackToMatch(g.title, g.artist, g.album))].orEmpty()) }
        File(dir, "gold.json").writeText(json.encodeToString(ListSerializer(Gold.serializer()), gold))
        gold.forEach { g ->
            val c = answers.values.flatten().firstOrNull { it.id == g.expect }
            println("[label] ${g.title} · ${g.artist} → ${c?.let { "${it.title} · ${it.artists.joinToString()} · ${it.album} · ${it.sec}s (${it.id})" } ?: "none"}")
            // What there was to pick from, for a label to be set by hand.
            if (c == null) answers[TrackMatcher.queryFor(TrackToMatch(g.title, g.artist, g.album))].orEmpty().take(6).forEach {
                println("[label]     ${it.id} | ${it.title} | ${it.artists.joinToString()} | ${it.album} | ${it.sec}s${if (it.video) " video" else ""}${if (it.explicit) " E" else ""}")
            }
        }
    }

    /**
     * Labels by a stricter rule than the matcher's: the song (not a video) by the gold artist whose whole title, brackets
     * and all, is the gold title; else, for a title with no version words, the first song by the artist with the same
     * title and none either. Anything else is "not on YouTube Music as asked". Read and fix by hand.
     */
    private fun label(g: Gold, found: List<Cand>): Gold {
        HAND[g.title]?.let { (id, sec) -> return g.copy(expect = id, ms = sec * 1000L + 400) }
        fun art(s: String) = ImportKeys.norm(s).removePrefix("the ")
        val main = art(ImportKeys.mainArtist(g.artist))
        val byArtist = found.filter { c -> !c.video && c.artists.any { art(it) == main } }
        val whole = byArtist.firstOrNull { ImportKeys.norm(it.title) == ImportKeys.norm(g.title) }
        val plain = if (ImportKeys.versionMarks(g.title).isEmpty())
            byArtist.firstOrNull { ImportKeys.core(it.title) == ImportKeys.core(g.title) && ImportKeys.versionMarks(it.title).isEmpty() } else null
        val pick = whole ?: plain
        // Spotify's length is YouTube's give or take a second.
        val jitter = (g.title.hashCode() % 1000).toLong()
        return g.copy(expect = pick?.id, ms = pick?.let { it.sec * 1000L + jitter } ?: 0)
    }

    companion object {
        /**
         * Labels set by hand (2026-11-02, from the recorded answers), where the strict rule finds none: YouTube Music
         * names a remaster plainly ("Bohemian Rhapsody" on A Night At The Opera is the 2011 remaster), writes a remix's
         * guests in its title, or names the artist in another script (米津玄師 is Kenshi Yonezu). Left out on purpose
         * (the matcher mustn't be sure of them): the live Hotel California, One Dance (not in YouTube's answer), Aretha's
         * 2002 remaster (only a live one), Hamaki's Habibi (only "Enta Habibi"), and an artist that doesn't exist.
         */
        val HAND = mapOf(
            "Wonderwall - Remastered" to ("FVdjZYfDuLE" to 259), "Bohemian Rhapsody - Remastered 2011" to ("BSTsnWoslP4" to 355),
            "Don't Stop Me Now - Remastered 2011" to ("1UjLFMg18cY" to 210), "Hotel California - 2013 Remaster" to ("BciS5krYL80" to 392),
            "Old Town Road - Remix" to ("TxcmdhjmnEE" to 158), "Hey Jude - Remastered 2015" to ("mQER0A0ej0M" to 426),
            "Imagine - Remastered 2010" to ("rAn-AWXtHv0" to 188), "Despacito - Remix" to ("FdMG84qN_98" to 231),
            "Bzrp Music Sessions, Vol. 53" to ("7IO7dX8AV5A" to 218), "Lemon" to ("3NNhrqHZqlI" to 257),
            "Jai Ho" to ("2R3XstG35sE" to 319), "Alors on danse" to ("gUbeQ7eb3nc" to 207),
        )
    }
}
