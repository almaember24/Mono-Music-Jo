package com.prism.music

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.prism.music.data.db.LyricsDao
import com.prism.music.data.db.LyricsEntity
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.lyrics.LyricLine
import com.prism.music.data.lyrics.LyricWord
import com.prism.music.data.lyrics.LyricsRepository
import com.prism.music.data.lyrics.WordTiming
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.LyricsSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #301: word-by-word lyrics (Better Lyrics, LyricsPlus…) off against the singing while line-by-line ones were right.
 * Word timings that aren't the words' are shown by their lines; a whole set early or late against the song's line-timed
 * lyrics by one steady amount is lined up with them, once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class WordTimingTest {
    private val texts = listOf(
        "I walked along the river", "the lights were coming on", "and every window glowing", "was singing me a song",
        "so take me where the night goes", "and leave the day behind", "I'll follow every heartbeat", "until the end of time",
    )

    /** Line-timed lyrics, a line every 4 s from 10 s. */
    private val lines = texts.mapIndexed { i, t -> LyricLine(10_000L + i * 4_000L, t, 10_000L + (i + 1) * 4_000L) }

    /** The same lines, word-timed (each word its own length), [late] ms after [lines]. */
    private fun words(late: Long, jitter: (Int) -> Long = { 0 }) = texts.mapIndexed { i, t ->
        val start = 10_000L + i * 4_000L + late + jitter(i)
        var at = start
        val ws = t.split(" ").mapIndexed { j, w -> LyricWord(at, at + 180 + j * 40, "$w ").also { at += 230 + j * 40 } }
        LyricLine(start, t, at, ws)
    }

    @Test fun aSteadyDifferenceIsFound() {
        assertEquals(1_500L, WordTiming.offsetAgainst(words(1_500), lines))
        assertEquals(-900L, WordTiming.offsetAgainst(words(-900), lines))
        // A line's stamp a little before the singing is how line-timed lyrics are made: left alone.
        assertNull(WordTiming.offsetAgainst(words(250), lines))
    }

    @Test fun aScatteredDifferenceIsLeftAlone() {
        // Another edit of the song (some lines move, others don't): no one difference, so nothing is moved.
        assertNull(WordTiming.offsetAgainst(words(0) { i -> if (i % 2 == 0) 0 else 2_000 }, lines))
        // Too few lines in common.
        assertNull(WordTiming.offsetAgainst(words(1_500), lines.take(3)))
    }

    @Test fun shiftingMovesTheWordsWithTheirLines() {
        val moved = WordTiming.shifted(words(1_500), -1_500)
        assertEquals(lines.map { it.timeMs }, moved.map { it.timeMs })
        assertEquals(10_000L, moved.first().words.first().startMs)
        assertTrue(moved.all { l -> l.words.all { it.startMs >= l.timeMs } })
    }

    @Test fun wordsSharedOutEvenlyAreShownByTheirLines() {
        val even = texts.mapIndexed { i, t ->
            val start = 10_000L + i * 4_000L
            val ws = t.split(" ").mapIndexed { j, w -> LyricWord(start + j * 500L, start + (j + 1) * 500L, "$w ") }
            LyricLine(start, t, start + 4_000L, ws)
        }
        assertTrue(WordTiming.checked(even).none { it.isWordSynced })
        // Real (uneven) timings stay.
        assertTrue(WordTiming.checked(words(0)).all { it.isWordSynced })
    }

    @Test fun wordsOutsideTheirLineAreShownByTheirLines() {
        val wrong = words(0).map { l -> l.copy(words = l.words.map { it.copy(startMs = it.startMs + 30_000, endMs = it.endMs + 30_000) }) }
        assertTrue(WordTiming.checked(wrong).none { it.isWordSynced })
        // One bad line among many is the source's slip, not the set: kept.
        val one = words(0).mapIndexed { i, l -> if (i != 3) l else l.copy(words = l.words.reversed()) }
        assertTrue(WordTiming.checked(one).all { it.isWordSynced })
    }

    // ---- In the lyrics repository: lined up once, and kept.

    private val song = Song("wordShift01", "Test Song", listOf(ArtistRef("Test Artist")), durationSec = 60)
    private val later = 2_000_000_000_000L // saved "in the future", so nothing is asked over the network

    private fun lrc() = lines.joinToString("\n") { l -> "[%02d:%02d.%02d]%s".format(l.timeMs / 60_000, l.timeMs / 1000 % 60, l.timeMs % 1000 / 10, l.text) }

    private fun ttml(late: Long): String {
        fun s(ms: Long) = "%d.%03d".format(ms / 1000, ms % 1000)
        val ps = words(late).joinToString("") { l ->
            "<p begin=\"${s(l.timeMs)}\" end=\"${s(l.endMs)}\">" + l.words.joinToString(" ") { w -> "<span begin=\"${s(w.startMs)}\" end=\"${s(w.endMs)}\">${w.text.trim()}</span>" } + "</p>"
        }
        return "<tt xmlns=\"http://www.w3.org/ns/ttml\" itunes:timing=\"Word\"><body><div>$ps</div></body></tt>"
    }

    private fun repo(late: Long): Pair<LyricsRepository, android.content.SharedPreferences> {
        val rows = listOf(
            LyricsEntity(song.id, LyricsSource.APPLE.name, true, ttml(late), later),
            LyricsEntity(song.id, LyricsSource.LRCLIB.name, true, lrc(), later),
        )
        val dao = object : LyricsDao {
            override suspend fun get(songId: String, source: String) = rows.firstOrNull { it.songId == songId && it.source == source }
            override suspend fun put(e: LyricsEntity) {}
            override suspend fun clearMisses() {}
            override suspend fun allFor(songId: String) = rows.filter { it.songId == songId }
        }
        val settings = AppSettings(lyricsOrder = listOf(LyricsSource.APPLE, LyricsSource.LRCLIB), preferSynced = true)
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("lyrics_word_shift_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return LyricsRepository(OkHttpClient(), dao, YouTubeMusic(InnerTube(OkHttpClient(), { settings })), { settings }).apply { wordShifts = prefs } to prefs
    }

    @Test fun lateWordsAreLinedUpWithTheLinesOnce() = runBlocking {
        val (r, prefs) = repo(late = 1_500)
        val first = r.auto(song)!!
        assertEquals(LyricsSource.APPLE, first.source)
        assertTrue(first.wordSynced)
        assertEquals(lines.map { it.timeMs }, first.lines.filter { !it.isGap }.map { it.timeMs })
        assertEquals("1500", prefs.getString("${song.id}|APPLE", null))
        // Again (saved now): moved by the same amount, not twice.
        val again = r.auto(song)!!
        assertEquals(lines.map { it.timeMs }, again.lines.filter { !it.isGap }.map { it.timeMs })
        assertEquals(lines.map { it.timeMs }, r.savedOnly(song)!!.lines.filter { !it.isGap }.map { it.timeMs })
    }

    @Test fun wordsThatAgreeWithTheLinesStayAsTheyAre() = runBlocking {
        val (r, prefs) = repo(late = 200)
        val got = r.auto(song)!!
        assertEquals(words(200).map { it.timeMs }, got.lines.filter { !it.isGap }.map { it.timeMs })
        assertEquals("0", prefs.getString("${song.id}|APPLE", null))
        assertFalse(got.lines.isEmpty())
    }
}
