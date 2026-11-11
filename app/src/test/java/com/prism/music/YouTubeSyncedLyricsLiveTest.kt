package com.prism.music

import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.lyrics.Lrc
import com.prism.music.data.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import org.junit.experimental.categories.Category

/** YouTube Music's own time-synced lyrics (issue #6), live and signed out. */
@Category(LiveNetwork::class)
class YouTubeSyncedLyricsLiveTest {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private var settings = AppSettings()
    private val ytm = YouTubeMusic(InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) }))

    @Test
    fun arabicSongHasSyncedLyrics() = runBlocking {
        // "FAHMAN DONYA" by Marwan Moussa: plain text on the web client and LRCLIB, synced in YouTube Music's app.
        val browseId = ytm.next("XXrjqdtHVsI").lyricsBrowseId
        assertNotNull(browseId)
        val lrc = ytm.syncedLyrics(browseId!!)
        assertNotNull(lrc)
        assertTrue(Lrc.isLrc(lrc!!))
        val lines = Lrc.parse(lrc).filter { !it.isGap }
        assertTrue("${lines.size} lines", lines.size > 20)
        assertTrue(lines.any { l -> l.text.any { it in '؀'..'ۿ' } })
        assertTrue(lines.zipWithNext().all { (a, b) -> a.timeMs <= b.timeMs })
    }
}
