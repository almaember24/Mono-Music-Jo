package com.prism.music

import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.SongItem
import com.prism.music.data.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.junit.experimental.categories.Category

/** Korean and Spanish phones: YouTube answers in their language, and Prism still reads types, years and artists. */
@Category(LiveNetwork::class)
class LocalizedSearchLiveTest {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private var settings = AppSettings()
    private val before = Locale.getDefault()

    private fun ytm() = YouTubeMusic(InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) }))

    @After fun restore() = Locale.setDefault(before)

    private fun check(locale: Locale, query: String) = runBlocking {
        Locale.setDefault(locale)
        val ytm = ytm()
        val albums = ytm.search(query, SearchFilter.ALBUMS).items.filterIsInstance<AlbumItem>()
        println("[$locale] albums: " + albums.take(4).joinToString(" | ") { it.subtitle })
        assertTrue("albums found", albums.isNotEmpty())
        assertTrue("album types in English", albums.all { it.subtitle.substringBefore(" • ") in setOf("Album", "Single", "EP") })
        assertTrue("years without 년", albums.none { it.subtitle.contains("년") })
        val page = ytm.album(albums.first().id)
        println("[$locale] album page: ${page.title} · ${page.releaseKind} · ${page.year}")
        assertTrue("release kind read", page.releaseKind != null)
        val top = ytm.searchTop(query)
        println("[$locale] top: ${top.top}")
        val songs = top.sections.flatMap { it.second }.filterIsInstance<SongItem>()
        assertTrue("songs have artists", songs.isNotEmpty() && songs.all { s -> s.song.artists.none { it.name in setOf("노래", "Canción", "Song") } })
        assertTrue("an artist leads or follows", top.top is ArtistItem || top.sections.any { (f, l) -> f == SearchFilter.ARTISTS && l.isNotEmpty() })
    }

    @Test fun korean() = check(Locale.KOREA, "IU")

    @Test fun spanish() = check(Locale("es", "MX"), "bad bunny")
}
