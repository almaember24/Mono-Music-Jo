package com.prism.music

import com.prism.music.data.innertube.Explore
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.PodcastItem
import com.prism.music.data.model.SongItem
import com.prism.music.data.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import org.junit.experimental.categories.Category

/**
 * What Explore and Charts really answer, signed out, as the app asks for them. Nothing here looks at a shelf's title:
 * those change with the language. Signing in isn't possible where this runs; the app asks these pages without the account.
 */
@Category(LiveNetwork::class)
class ExploreLiveTest {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private var settings = AppSettings()
    private val ytm = YouTubeMusic(InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) }))

    @Test fun explorePageHasReleasesMoodsAndSongs() = runBlocking {
        val shelves = ytm.explore()
        val shown = shelves.joinToString { "${it.title}(${it.items.size})" }
        assertTrue(shown, shelves.any { s -> s.items.any { it is AlbumItem } })
        assertTrue(shown, shelves.any(Explore::isMoods))
        assertTrue(shown, shelves.any { s -> s.items.any { it is SongItem } })
        assertTrue("no podcasts here: $shown", shelves.none { s -> s.items.any { it is PodcastItem || (it is SongItem && it.song.isEpisode) } })
    }

    @Test fun chartsComeWithCountriesAndFollowThePick() = runBlocking {
        val first = ytm.charts()
        assertTrue("${first.countries.size} countries", first.countries.size > 20)
        val us = ytm.charts("US")
        assertEquals("US", us.selected?.code)
        val brazil = ytm.charts("BR")
        assertEquals("BR", brazil.selected?.code)
        for (p in listOf(us, brazil)) {
            val what = p.shelves.joinToString { "${it.title}(${it.items.size})" }
            assertTrue(what, p.shelves.any { s -> s.items.any { it is PlaylistItem } })
            assertTrue(what, p.shelves.any { s -> s.items.any { it is ArtistItem } })
        }
    }

    @Test fun globalIsOnTheList() = runBlocking {
        assertTrue(ytm.charts().countries.any { it.code == "ZZ" })
        assertEquals("ZZ", ytm.charts("ZZ").selected?.code)
    }
}
