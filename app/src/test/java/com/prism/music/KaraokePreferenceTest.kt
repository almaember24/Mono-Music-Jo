package com.prism.music

import com.prism.music.data.db.LyricsDao
import com.prism.music.data.db.LyricsEntity
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.lyrics.LyricsRepository
import com.prism.music.data.lyrics.WordFormat
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.LyricsSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Karaoke (word-by-word) lyrics win over time-synced lines, even when a source earlier in the order has the lines. */
class KaraokePreferenceTest {
    private val later = 2_000_000_000_000L // saved "in the future", so nothing is re-asked over the network

    private fun repo(rows: List<LyricsEntity>, savedSources: Set<LyricsSource>, order: List<LyricsSource>): LyricsRepository {
        val dao = object : LyricsDao {
            override suspend fun get(songId: String, source: String) = rows.firstOrNull { it.songId == songId && it.source == source }
            override suspend fun put(e: LyricsEntity) {}
            override suspend fun clearMisses() {}
            // What the phone has kept: the karaoke source hasn't been asked yet.
            override suspend fun allFor(songId: String) = rows.filter { it.songId == songId && LyricsSource.valueOf(it.source) in savedSources }
        }
        val settings = AppSettings(lyricsOrder = order, preferSynced = true)
        return LyricsRepository(OkHttpClient(), dao, YouTubeMusic(InnerTube(OkHttpClient(), { settings })), { settings })
    }

    private val song = Song("karaokeTest1", "Test Song", listOf(ArtistRef("Test Artist")), durationSec = 30)
    private val lines = LyricsEntity(song.id, LyricsSource.KUGOU.name, true, "[00:01.00]Hello world\n[00:03.00]Second line", later)
    private val words = LyricsEntity(
        song.id, LyricsSource.QQ.name, true,
        WordFormat.wrap(WordFormat.Kind.QRC, "[1000,2000]Hello(1000,500) world(1500,500)\n[3000,2000]Second(3000,500) line(3500,500)"), later,
    )

    @Test
    fun karaokeFromALaterSourceBeatsLinesFromAnEarlierOne() = runBlocking {
        val r = repo(listOf(lines, words), savedSources = setOf(LyricsSource.KUGOU), order = listOf(LyricsSource.KUGOU, LyricsSource.QQ))
        val got = r.auto(song)!!
        assertEquals(LyricsSource.QQ, got.source)
        assertTrue(got.wordSynced)
    }

    @Test
    fun linesStillServeWhenNoSourceHasKaraoke() = runBlocking {
        val r = repo(listOf(lines, words.copy(content = "")), savedSources = setOf(LyricsSource.KUGOU), order = listOf(LyricsSource.KUGOU, LyricsSource.QQ))
        val got = r.auto(song)!!
        assertEquals(LyricsSource.KUGOU, got.source)
        assertTrue(got.synced && !got.wordSynced)
    }
}
