package com.prism.music

import com.prism.music.data.ModelStore
import com.prism.music.data.db.RomanizedDao
import com.prism.music.data.db.RomanizedEntity
import com.prism.music.data.lyrics.LyricLine
import com.prism.music.data.lyrics.LyricWord
import com.prism.music.data.lyrics.Romanizer
import com.prism.music.data.lyrics.roman.Script
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** The romanizer: engines picked per line, karaoke timings kept, results kept; Google's part is live. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RomanizerTest {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private val saved = HashMap<String, RomanizedEntity>()
    private val dao = object : RomanizedDao {
        override suspend fun get(key: String) = saved[key]
        override suspend fun put(e: RomanizedEntity) { saved[e.key] = e }
    }
    private val romanizer = Romanizer(http, dao, ModelStore(RuntimeEnvironment.getApplication(), http), CoroutineScope(Dispatchers.Default))

    private fun w(start: Long, end: Long, text: String) = LyricWord(start, end, text)

    @Test
    fun koreanKaraokeKeepsItsTimings() = runBlocking {
        val line = LyricLine(1_000, "사랑해 같이", 3_000, words = listOf(w(1_000, 2_000, "사랑해 "), w(2_000, 3_000, "같이")))
        val r = romanizer.romanize("k", listOf(line, LyricLine(4_000, "Hello there")), skip = emptySet(), network = false)
        val k = r[0]!!
        assertEquals("saranghae gachi", k.text)
        assertEquals(listOf("saranghae ", "gachi"), k.words.map { it.text })
        assertEquals(listOf(1_000L, 2_000L), k.words.map { it.startMs })
        // A line already in Latin letters needs nothing.
        assertNull(r[1])
        // Kept for next time.
        assertEquals(1, saved.size)
    }

    @Test
    fun thePhonesOwnScriptIsLeftAlone() = runBlocking {
        val r = romanizer.romanize("k2", listOf(LyricLine(0, "사랑해")), skip = Script.native("ko"), network = false)
        assertNull(r[0])
    }

    /**
     * #256: with Google out of reach and no Japanese dictionary, Android's transliterator spelled a Japanese song's
     * kanji as Chinese (pinyin, "xīn の zàng") and kept it. A Japanese line now waits for a proper spelling instead.
     */
    @Test
    fun aJapaneseSongIsNeverSpelledAsChinese() = runBlocking {
        val r = romanizer.romanize("ja-offline", listOf(LyricLine(0, "心の臓を鳴らす")), skip = emptySet(), network = false)
        assertNull("left for a proper spelling, not pinyin: ${r[0]?.text}", r[0])
        assertTrue("not kept, so it's asked for again", saved.isEmpty())
    }

    @org.junit.experimental.categories.Category(LiveNetwork::class)
    @Test
    fun googleSpellsOutJapaneseWholly() = runBlocking {
        val r = romanizer.romanize("ja-live", listOf(LyricLine(0, "心の臓を鳴らす"), LyricLine(2_000, "風の中でも")), skip = emptySet(), network = true)
        println("[live] " + r.map { it?.text })
        for (line in r) {
            assertNotNull(line)
            assertTrue("no kanji or kana left: ${line!!.text}", Script.foreignIn(line.text).isEmpty())
        }
    }

    @Test
    fun spreadFollowsTheOriginalTiming() {
        // Two words of 4 letters over 0–1 s and 1–3 s: the second half of the letters is sung in the last 2 s.
        val words = listOf(w(0, 1_000, "abcd"), w(1_000, 3_000, "efgh"))
        val spread = Romanizer.spread("one two", words)
        assertEquals(2, spread.size)
        assertEquals(0L, spread[0].startMs)
        assertTrue(spread[1].startMs in 1_000..1_400)
        assertEquals(3_000L, spread[1].endMs)
    }

    @org.junit.experimental.categories.Category(LiveNetwork::class)
    @Test
    fun googleSpellsOutChineseAndHindi() = runBlocking {
        val lines = listOf(
            LyricLine(0, "我爱你"), LyricLine(2_000, "月亮代表我的心"),
            LyricLine(4_000, "मेरे सपनों की रानी"),
        )
        val r = romanizer.romanize("live", lines, skip = emptySet(), network = true)
        println("[live] " + r.map { it?.text })
        assertNotNull(r[0]); assertNotNull(r[1]); assertNotNull(r[2])
        assertTrue(r[0]!!.text.lowercase().contains("ài") || r[0]!!.text.lowercase().contains("ai"))
        assertTrue(r[2]!!.text.lowercase().contains("sapn"))
    }
}
