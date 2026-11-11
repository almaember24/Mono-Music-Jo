package com.prism.music

import com.prism.music.data.db.LyricsDao
import com.prism.music.data.db.LyricsEntity
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.lyrics.LyricsRepository
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.LyricsSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.util.concurrent.TimeUnit

/**
 * #403: no lyrics for "Nothing Personal" by KAIRO. KuGou's lyric search goes by the artist's exact case ("KAIRO" finds
 * nothing, "Kairo" does); its song search doesn't, and the song's file hash then finds the lyrics.
 */
class KuGouSongSearchTest {
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
    private val song = Song("iKtOlv89DZk", "Nothing Personal", listOf(ArtistRef("KAIRO")), null, 172)

    /** KuGou's real song_search_v2 answer for "KAIRO Nothing Personal" (2026-11-09). */
    private val results = InnerTube.json.parseToJsonElement(
        javaClass.getResourceAsStream("/kugou/song-search-kairo-nothing-personal.json")!!.readBytes().toString(Charsets.UTF_8))

    private fun hashes(title: String, artist: String, sec: Int) =
        repo.kuGouSongHashes(results, Song("x", title, listOf(ArtistRef(artist)), null, sec))

    @Test fun picksKairosNothingPersonal() {
        assertEquals(listOf("35F6828CB6C5808D55749DAE307F56D1"), hashes("Nothing Personal", "KAIRO", 172))
        // Unknown length: still only Kairo's, not AX!A's or Dante Ray's "Nothing Personal".
        assertEquals(listOf("35F6828CB6C5808D55749DAE307F56D1"), hashes("Nothing Personal", "KAIRO", 0))
    }

    @Test fun otherSingersTitlesAndLengthsAreLeftOut() {
        // AX!A's "Nothing Personal (Explicit)" is 211 s, but not KAIRO's.
        assertEquals(emptyList<String>(), hashes("Nothing Personal", "KAIRO", 211))
        // Dante Ray's own song is found, and Kairo's isn't taken for it.
        assertEquals(listOf("CD750590D7EDB3B0F48489190D86DE41"), hashes("Nothing Personal", "Dante Ray", 261))
        // Same singer and length, another title.
        assertEquals(listOf("4E6E9828128348189B2644064E33C733"), hashes("Movie Star", "KAIRO", 172))
        // More than 8 s off.
        assertEquals(emptyList<String>(), hashes("Nothing Personal", "KAIRO", 185))
    }

    /** Live: KuGou has word-timed lyrics for the song, found through its song search. */
    @Category(LiveNetwork::class)
    @Test fun kairoNothingPersonalHasLyrics() = runBlocking {
        val l = repo.fetch(song, LyricsSource.KUGOU)
        assertNotNull("KuGou found no lyrics", l)
        println("[kugou] ${l!!.lines.size} lines, synced ${l.synced}, words ${l.wordSynced}: ${l.lines.take(3).map { it.text }}")
        assertTrue(l.synced && l.lines.size > 10)
    }
}
