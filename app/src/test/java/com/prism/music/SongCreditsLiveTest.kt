package com.prism.music

import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.model.SongItem
import com.prism.music.data.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import org.junit.experimental.categories.Category

/** Song credits (Reddit 1.7: "view song credits"), live against YouTube Music. */
@Category(LiveNetwork::class)
class SongCreditsLiveTest {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private var settings = AppSettings()
    private val ytm = YouTubeMusic(InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) }))

    @Test
    fun blindingLightsHasWritersAndProducers() = runBlocking {
        val id = ytm.search("blinding lights the weeknd", SearchFilter.SONGS).items.filterIsInstance<SongItem>().first().song.id
        val credits = ytm.credits(id).toMap()
        assertTrue(credits.toString(), credits["Performed by"].orEmpty().any { it.contains("Weeknd") })
        assertTrue(credits.toString(), credits["Written by"].orEmpty().any { it.contains("Max Martin") })
        assertTrue(credits.toString(), credits.containsKey("Produced by"))
    }
}
