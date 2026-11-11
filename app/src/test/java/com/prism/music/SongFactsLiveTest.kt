package com.prism.music

import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.model.SongItem
import com.prism.music.data.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import org.junit.experimental.categories.Category

/** #211's numbers, live against YouTube (a song, its music video). */
@Category(LiveNetwork::class)
class SongFactsLiveTest {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private var settings = AppSettings()
    private val ytm = YouTubeMusic(InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) }))

    @Test
    fun aSongHasPlaysAndItsReleaseDate() = runBlocking {
        val song = ytm.search("blinding lights the weeknd", SearchFilter.SONGS).items.filterIsInstance<SongItem>().first().song
        val f = ytm.songFacts(song.id)
        assertTrue("$f", (f.count ?: 0) > 1_000_000)
        assertEquals("$f", LocalDate.of(2019, 11, 29), f.releasedOn)
    }

    @Test
    fun aMusicVideoHasViewsAndNoReleaseDate() = runBlocking {
        val f = ytm.songFacts("4NRXx6U8ABQ") // Blinding Lights (Official Video)
        assertTrue("$f", (f.count ?: 0) > 100_000_000)
        assertNull("$f", f.releasedOn)
        assertNotNull("$f", f.publishedOn)
    }
}
