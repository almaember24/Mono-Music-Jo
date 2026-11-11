package com.prism.music

import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.lyrics.LyricSearch
import com.prism.music.data.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import org.junit.experimental.categories.Category

/** Search by a line of the lyrics (1.7.6), live: Genius's lyrics index, then the song on YouTube Music. */
@Category(LiveNetwork::class)
class LyricSearchLiveTest {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private var settings = AppSettings()
    private val ytm = YouTubeMusic(InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) }))
    private val search = LyricSearch(http, ytm, { _, _ -> emptyList() }, { emptyList() }) { emptyList() }

    @Test
    fun linesFindTheirSongs() = runBlocking {
        val cases = mapOf(
            "i got my driver's license last week" to "drivers license",
            "we were both young when i first saw you" to "love story",
            "she's got a smile that it seems to me reminds me of childhood memories" to "sweet child",
        )
        for ((line, title) in cases) {
            val hits = search.find(line)
            println("[lyrics] $line → " + hits.joinToString(" | ") { "${it.song.title} by ${it.song.artistText} (${"%.2f".format(it.score)}): ${it.line}" })
            assertTrue("$line → $title", hits.firstOrNull()?.song?.title?.lowercase()?.contains(title) == true)
            assertTrue("$line is sure", hits.first().sure)
        }
        // Not a line of any song: nothing sure.
        val none = search.find("the quiet purple lamp argued with seven teaspoons")
        println("[lyrics] nonsense → " + none.joinToString(" | ") { "${it.song.title} (${it.score})" })
        assertTrue(none.none { it.sure })
    }
}
