package com.prism.music

import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.SongItem
import com.prism.music.data.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import org.junit.experimental.categories.Category

/** The "Live" search filter and artist-page livestreams, live against youtube.com (Lofi Girl streams 24/7). */
@Category(LiveNetwork::class)
class LiveStreamsLiveTest {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private var settings = AppSettings()
    private val ytm = YouTubeMusic(InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) }))

    @Test
    fun liveSearchFindsLofiGirl() = runBlocking {
        val r = ytm.search("lofi girl", SearchFilter.LIVE)
        val songs = r.items.filterIsInstance<SongItem>().map { it.song }
        songs.take(5).forEach { println("${it.id}  ${it.title}  — ${it.artistText}") }
        assertTrue("no live results", songs.isNotEmpty())
        assertTrue(songs.all { it.isLive && it.isVideo })
        assertTrue(songs.any { it.artistText.contains("Lofi Girl", true) })
    }

    @Test
    fun lofiGirlArtistPageHasStreams() = runBlocking {
        val artist = ytm.search("Lofi Girl", SearchFilter.ARTISTS).items.filterIsInstance<ArtistItem>().first()
        val page = ytm.artist(artist.id)
        println("artist ${page.id} channel ${page.channelId}")
        val streams = ytm.liveStreams(page.channelId ?: page.id, ArtistRef(page.name, page.id))
        streams.forEach { println("${it.id}  ${it.title}") }
        assertTrue("no streams on ${page.channelId ?: page.id}", streams.isNotEmpty())
    }
}
