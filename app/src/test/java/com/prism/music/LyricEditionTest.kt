package com.prism.music

import com.prism.music.data.db.LyricsDao
import com.prism.music.data.db.LyricsEntity
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.lyrics.Editions
import com.prism.music.data.lyrics.LyricsRepository
import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.LyricsSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.util.concurrent.TimeUnit

/**
 * Lyrics for another edition of a song aren't used for it (2026-10-20): KuGou answered the album's "Thriller" (5:57)
 * with "[ti:Thriller(Single Version)]", listed under the album's length, and every line came about 45 s early.
 */
class LyricEditionTest {
    @Test
    fun editionsAreToldApart() {
        assertTrue(Editions.otherEdition("Thriller(Single Version)", "Thriller"))
        assertTrue(Editions.otherEdition("Mr. Brightside (Live)", "Mr. Brightside"))
        assertTrue(Editions.otherEdition("Blinding Lights - Radio Edit", "Blinding Lights"))
        assertFalse(Editions.otherEdition("Thriller", "Thriller"))
        assertFalse(Editions.otherEdition("Thriller (2008 Remaster)", "Thriller"))
        assertFalse(Editions.otherEdition("Love Story (Taylor's Version)", "Love Story (Taylor's Version)"))
        assertFalse(Editions.otherEdition("Get Lucky (Radio Edit)", "Get Lucky (Radio Edit - feat. Pharrell Williams and Nile Rodgers)"))
        assertFalse(Editions.otherEdition(null, "Thriller"))
    }

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

    /** Live: KuGou's lyrics for the album "Thriller" start with "It's close to midnight" at about 0:59. */
    @Category(LiveNetwork::class)
    @Test
    fun thrillerFromKuGouIsTheAlbumsTiming() = runBlocking {
        val song = Song("Z85lxckrtzg", "Thriller", listOf(ArtistRef("Michael Jackson")), AlbumRef("Thriller"), 356)
        val l = LyricsRepository(http, dao, ytm, { settings }).fetch(song, LyricsSource.KUGOU) ?: return@runBlocking
        val first = l.lines.first { it.text.contains("midnight", ignoreCase = true) }
        assertTrue("first line at ${first.timeMs} ms", first.timeMs in 50_000..70_000)
    }
}
