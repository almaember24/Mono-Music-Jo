package com.prism.music

import com.prism.music.data.db.LyricsDao
import com.prism.music.data.db.LyricsEntity
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.lyrics.LyricsRepository
import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.LyricsSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import org.junit.experimental.categories.Category

/** Starred-out karaoke lyrics get their words back from Genius (live: needs network). */
@Category(LiveNetwork::class)
class UncensorLiveTest {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private var settings = AppSettings()
    private val ytm = YouTubeMusic(InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) }))
    private val dao = object : LyricsDao {
        val rows = HashMap<String, LyricsEntity>()
        override suspend fun get(songId: String, source: String) = rows["$songId|$source"]
        override suspend fun put(e: LyricsEntity) { rows["${e.songId}|${e.source}"] = e }
        override suspend fun clearMisses() {}
        override suspend fun allFor(songId: String) = rows.values.filter { it.songId == songId && it.content.isNotEmpty() }
    }
    private val repo = LyricsRepository(http, dao, ytm, { settings })

    private fun song(id: String, title: String, artist: String, album: String, sec: Int) =
        Song(id, title, listOf(ArtistRef(artist)), AlbumRef(album), sec)

    @Test
    fun censoredSourcesAreFilledIn() = runBlocking {
        val songs = listOf(
            song("tvTRZJ-4EyI", "HUMBLE.", "Kendrick Lamar", "DAMN.", 177),
            song("pc0mxOXbWIU", "Fuck You", "CeeLo Green", "The Lady Killer", 222),
            song("xpVfcZ0ZcFM", "God's Plan", "Drake", "Scorpion", 199),
            song("_Yhyp-_hX2s", "Lose Yourself", "Eminem", "8 Mile", 326),
        )
        var censoredSeen = 0
        var filled = 0
        for (s in songs) for (src in LyricsSource.entries.filter { it != LyricsSource.GENIUS && it != LyricsSource.YTMUSIC }) {
            settings = settings.copy(skipCensored = false)
            val raw = repo.fetch(s, src) ?: continue
            if (!raw.censored) continue
            censoredSeen++
            settings = settings.copy(skipCensored = true)
            val fixed = repo.fetch(s, src)!!
            val before = raw.lines.count { it.text.contains('*') }
            val after = fixed.lines.count { it.text.contains('*') }
            if (!fixed.censored) filled++
            println("[uncensor] ${s.title} · ${src.label}: starred lines $before -> $after, word-synced=${fixed.wordSynced}")
            raw.lines.zip(fixed.lines).filter { (a, b) -> a.text != b.text }.take(3).forEach { (a, b) -> println("    '${a.text}' -> '${b.text}'") }
            // Word timings never move.
            assertTrue(raw.lines.map { it.timeMs } == fixed.lines.map { it.timeMs })
            assertTrue("${src.label} didn't get more starred", after <= before)
        }
        println("[uncensor] censored source/song pairs: $censoredSeen, fully filled: $filled")
    }

    /**
     * "IV. Sweatpants": Apple Music's lyrics star words out whole ("real **** feelin'"), which once didn't count as
     * censored at all; Genius only knows it without "(feat. Jason Martin)"; and some lines are split differently.
     */
    @Test
    fun wholeWordStarsAreFilledIn() = runBlocking {
        val s = song("j_d2o-V5SgM", "IV. Sweatpants (feat. Jason Martin)", "Childish Gambino", "Because the Internet", 181)
        settings = settings.copy(skipCensored = true)
        for (src in listOf(LyricsSource.APPLE, LyricsSource.NETEASE)) {
            val l = repo.fetch(s, src) ?: continue
            l.lines.filter { it.text.contains('*') }.forEach { println("[sweatpants] ${src.label} still starred: ${it.text}") }
            assertTrue("${src.label} still censored", !l.censored)
        }
        val apple = repo.fetch(s, LyricsSource.APPLE)
        if (apple != null) assertTrue(apple.lines.any { it.text.startsWith("I'm chillin', real nigga feelin'") })
        val netease = repo.fetch(s, LyricsSource.NETEASE)
        if (netease != null) assertTrue(netease.lines.any { it.text.contains("doing you, fuck it") })
    }
}
