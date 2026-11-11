package com.prism.music

import com.prism.music.data.db.LyricsDao
import com.prism.music.data.db.LyricsEntity
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.lyrics.LyricsPlusFormat
import com.prism.music.data.lyrics.LyricsRepository
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.LyricsSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LyricsPlus answered "4 Steps Back, 2 Steps Forward" (Motion Sick) with every line at 0:00, labelled synced, so the
 * player sat on the last line from the start. Lines that never move are plain, and a source with real timings wins.
 */
class BogusTimingTest {
    private val later = 2_000_000_000_000L // saved "in the future", so nothing is re-asked over the network

    private fun repo(rows: List<LyricsEntity>, order: List<LyricsSource>): LyricsRepository {
        val dao = object : LyricsDao {
            override suspend fun get(songId: String, source: String) = rows.firstOrNull { it.songId == songId && it.source == source }
            override suspend fun put(e: LyricsEntity) {}
            override suspend fun clearMisses() {}
            override suspend fun allFor(songId: String) = rows.filter { it.songId == songId }
        }
        val settings = AppSettings(lyricsOrder = order, preferSynced = true)
        return LyricsRepository(OkHttpClient(), dao, YouTubeMusic(InnerTube(OkHttpClient(), { settings })), { settings })
    }

    private val song = Song("bogusTiming1", "4 Steps Back, 2 Steps Forward", listOf(ArtistRef("Motion Sick")), durationSec = 188)
    private val zeroTimed = LyricsEntity(
        song.id, LyricsSource.LYRICSPLUS.name, true,
        LyricsPlusFormat.PREFIX + """{"type":"Line","lyrics":[
            {"time":"0","duration":"0","text":"When I was a kid"},
            {"time":"0","duration":"0","text":"Grow up have a family"},
            {"time":"0","duration":"0","text":"Four steps back and two steps forward"}]}""",
        later,
    )
    private val timed = LyricsEntity(
        song.id, LyricsSource.NETEASE.name, true,
        "[00:12.34]When I was a kid\n[00:17.07]Grow up have a family\n[02:53.28]Four steps back and two steps forward", later,
    )

    @Test
    fun linesAllAtZeroAreNotSynced() = runBlocking {
        val got = repo(listOf(zeroTimed), listOf(LyricsSource.LYRICSPLUS)).fetch(song, LyricsSource.LYRICSPLUS)!!
        assertFalse(got.synced)
        assertEquals(3, got.lines.size)
    }

    @Test
    fun realTimingsWinOverZeroStamps() = runBlocking {
        val got = repo(listOf(zeroTimed, timed), listOf(LyricsSource.LYRICSPLUS, LyricsSource.NETEASE)).auto(song)!!
        assertEquals(LyricsSource.NETEASE, got.source)
        assertTrue(got.synced)
    }
}
