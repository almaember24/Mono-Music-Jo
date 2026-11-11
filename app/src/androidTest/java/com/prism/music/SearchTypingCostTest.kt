package com.prism.music

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prism.music.data.innertube.SearchRank
import com.prism.music.ui.screens.SettingsIndex
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a letter costs the main thread in the two search boxes (#355), on the device: Android's Regexes are ICU's, whose
 * compiling is what made typing slow, and a JVM unit test can't see that. On a Galaxy A16 a letter cost ~100 ms here.
 */
@RunWith(AndroidJUnit4::class)
class SearchTypingCostTest {
    private val letters = "daft punk get lucky".let { q -> (1..q.length).map { q.take(it) } }

    @Before fun start() = Fixture.ready()

    private fun medianMs(rounds: Int = 3, work: (String) -> Unit): Double {
        val times = ArrayList<Double>()
        repeat(rounds) {
            for (typed in letters) {
                val from = SystemClock.elapsedRealtimeNanos()
                work(typed)
                times += (SystemClock.elapsedRealtimeNanos() - from) / 1e6
            }
        }
        return times.sorted()[times.size / 2]
    }

    /** As [SearchRank.norm] was: three Regexes compiled per call. */
    private fun normAsItWas(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .replace("&", " and ")
        .replace(Regex("[^\\p{L}\\p{N} ]"), "")
        .replace(Regex("\\s+"), " ").trim()

    /** Measured against the old way in the same run, so a slow or busy device doesn't matter. */
    @Test fun normCompilesNoRegexes() {
        val name = "Simon & Garfunkel – Beyoncé (Live)"
        val old = medianMs(1) { repeat(20) { normAsItWas(name) } }
        val now = medianMs(1) { repeat(20) { SearchRank.norm(name) } }
        Log.i("PrismTyping", "norm ×20: now $now ms, with Regexes $old ms")
        assertTrue("norm should be far cheaper than three Regexes: now $now ms, with them $old ms", now * 5 < old)
    }

    /** A listener's 700 artists and their recent searches, for each letter. */
    @Test fun aLetterInSearchCostsLittle() {
        val taste = SearchRank.Taste((1..700).map { SearchRank.KnownArtist("id$it", "Some Artist Number $it", null, it / 700.0) }, emptyMap())
        val recents = (1..20).map { "an earlier search $it" }
        SearchRank.predict("warm up", taste, recents)
        val ms = medianMs { SearchRank.predict(it, taste, recents, limit = 2) }
        Log.i("PrismTyping", "predict: $ms ms a letter")
        assertTrue("predict took $ms ms a letter", ms < LIMIT_MS)
    }

    @Test fun aLetterInSettingsCostsLittle() {
        SettingsIndex.prepare()
        val ms = medianMs { SettingsIndex.search(it) }
        Log.i("PrismTyping", "settings search: $ms ms a letter")
        assertTrue("settings search took $ms ms a letter", ms < LIMIT_MS)
    }

    private companion object {
        /** A generous part of a frame: tests run debuggable, so several times slower than a release build. */
        const val LIMIT_MS = 12.0
    }
}
