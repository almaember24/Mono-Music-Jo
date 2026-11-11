package com.prism.music

import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.data.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit
import org.junit.experimental.categories.Category

/**
 * Live against YouTube (#202, 1.7.9): "Songs, not videos → Everywhere" finds the song for an artist's own video that
 * runs over a minute past it, and for a label channel's upload titled 'Artist - "Song" Label'; a video of two songs
 * still has none.
 */
@Category(LiveNetwork::class)
class EverywhereLiveTest {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private var settings = AppSettings()
    private val ytm = YouTubeMusic(InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) }))

    private suspend fun video(q: String, title: String) =
        ytm.search(q, SearchFilter.VIDEOS).items.filterIsInstance<SongItem>().map { it.song }.first { it.isVideo && it.title.equals(title, true) }

    @Test fun aLongerVideoFindsItsSong() = runBlocking {
        val v = video("Punk Rocky A\$AP Rocky", "Punk Rocky")
        assertEquals("PUNK ROCKY", ytm.songForVideo(v)?.title?.uppercase())
    }

    @Test fun aLabelsUploadFindsItsSong() = runBlocking {
        // As BlankTV titles it (the reporter's screenshot): the artist before the dash, the song in quotes.
        val v = Song("blanktv-monster", "Meg & Dia - \"Monster\" Doghouse Records", listOf(ArtistRef("BlankTV", null)), durationSec = 165, isVideo = true)
        val song = ytm.songForVideo(v)
        assertEquals("Monster", song?.title)
        assertEquals("Meg & Dia", song?.artists?.firstOrNull()?.name)
    }

    @Test fun aVideoOfTwoSongsHasNone() = runBlocking {
        assertNull(ytm.songForVideo(video("WHISKEY BLACK DEMARCO", "WHISKEY/BLACK DEMARCO")))
    }
}
