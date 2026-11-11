package com.prism.music

import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import org.junit.experimental.categories.Category

/**
 * Reddit R1 (2026-10-26): signed out, YouTube's album pages list tracks as their music videos where they have one
 * (MUSIC_VIDEO_TYPE_OMV), so "Death & Romance" from "Imaginal Disk" played its 8-minute video with "Fear, Sex" in it,
 * and "BUZZ" its video. The album's own playlist (OLAK…) still lists the songs, in the same order. Live.
 */
@Category(LiveNetwork::class)
class AlbumSongsPlayLiveTest {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private var settings = AppSettings()
    private val innerTube = InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) })

    @Test
    fun albumPagesListTheirSongs() = runBlocking {
        val pairs = HashMap<String, String>()
        val ytm = YouTubeMusic(innerTube, onPairs = { pairs.putAll(it) })
        val disk = ytm.album("MPREb_gLzsNqMumEh")
        println("[r1] ${disk.title} playlist ${disk.playlistId}: " + disk.songs.joinToString { "${it.id} ${it.title.take(16)} ${it.durationSec}s v=${it.isVideo}" })
        // Its own playlist, not another album's play button further down the page.
        assertEquals("OLAK5uy_mK6yC022-ZRU9J0I0oAjkhkgz8PXGqlpQ", disk.playlistId)
        val death = disk.songs.first { it.title.contains("Death", true) }
        assertNotEquals("Death & Romance is not its 8-minute video", "_8gfVTnbBi8", death.id)
        assertTrue("the song's length, ${death.durationSec}s", death.durationSec in 300..330)
        assertTrue("play counts kept for the songs", disk.trackPlays.keys.containsAll(disk.songs.map { it.id }.filter { it in disk.trackPlays }) && death.id in disk.trackPlays)
        val buzz = ytm.album("MPREb_nLz3XqJovLD").songs.single()
        println("[r1] BUZZ ${buzz.id} ${buzz.durationSec}s; pairs $pairs")
        assertEquals("EsKlh42np0U", buzz.id)
        assertEquals("the video stays the song's Video button", "EtRDi6HhCfg", pairs["EsKlh42np0U"])
    }
}
