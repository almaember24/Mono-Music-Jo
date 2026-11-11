package com.prism.music

import com.prism.music.data.foryou.ReleaseRadar
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/** Live (YouTube Music + Deezer): an artist's releases are found, and most recent ones get their exact date. */
@Category(LiveNetwork::class)
class ReleaseRadarLiveTest {
    private var settings = AppSettings()
    private val http = OkHttpClient()
    private val ytm = YouTubeMusic(InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) }))

    @Test
    fun releasesAndDates() = runBlocking {
        var recent = 0; var dated = 0
        for (name in listOf("Sabrina Carpenter", "Kendrick Lamar", "Taylor Swift")) {
            val page = ytm.artist(ytm.search(name, SearchFilter.ARTISTS).items.filterIsInstance<ArtistItem>().first().id)
            val releases = ReleaseRadar.discography(page)
            assertTrue("$name: no releases", releases.size >= 5)
            assertTrue("$name: no singles", releases.any { it.kind != "Album" })
            val dz = ReleaseRadar.Deezer.artistId(http, page.name)
            assertTrue("$name: not on Deezer", dz != null)
            val dates = ReleaseRadar.Deezer.releaseDates(http, dz!!)
            val newest = releases.maxOf { it.year }
            for (r in releases.filter { it.year >= newest - 1 }) {
                val d = ReleaseRadar.Deezer.dateFor(dates, r.title)?.takeIf { it.startsWith(r.year.toString()) }
                recent++; if (d != null) dated++
                println("RELEASE ${page.name} | ${r.kind} ${r.year} | ${r.title} | ${d ?: "no date"}")
            }
        }
        println("RELEASE dated $dated of $recent recent releases")
        assertTrue("only $dated of $recent dated", dated * 10 >= recent * 6)
    }
}
