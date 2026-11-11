package com.prism.music

import com.prism.music.data.imports.FileKind
import com.prism.music.data.imports.ImportKeys
import com.prism.music.data.imports.LastFmHistory
import com.prism.music.data.imports.ParsedPlay
import com.prism.music.data.imports.SpotifyExport
import com.prism.music.data.imports.TakeoutHistory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Bring your history (1.8.1): the files people pick, read as they come. Spotify's data download in both its kinds and
 * their older formats (extended history: `Streaming_History_Audio_*` and the older `endsong_*`; account data:
 * `StreamingHistory_music_*` and the older `StreamingHistory*`), its library and playlists, a zip as it arrives, Google
 * Takeout's watch history and Last.fm's pages. The fixtures are small but written as the real files are.
 */
class ImportParsersTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/imports/$name")!!.readBytes()

    private fun spotify(name: String, bytes: ByteArray = res(name), options: SpotifyExport.Options = SpotifyExport.Options()): Pair<List<ParsedPlay>, SpotifyExport.Result> {
        val plays = ArrayList<ParsedPlay>()
        val r = SpotifyExport.read(name, ByteArrayInputStream(bytes), options) { plays += it }
        return plays to r
    }

    @Test fun extendedHistoryReadsSongsAndLeavesOutPodcasts() {
        val (plays, r) = spotify("Streaming_History_Audio_2023-2024_0.json")
        assertEquals(setOf(FileKind.SPOTIFY_EXTENDED), r.kinds)
        // 7 entries: a podcast and a broken date left out.
        assertEquals(5, plays.size)
        val first = plays[0]
        assertEquals("Blinding Lights", first.title)
        assertEquals("The Weeknd", first.artist)
        assertEquals("After Hours", first.album)
        assertEquals(Instant.parse("2023-03-14T08:12:40Z").toEpochMilli(), first.timestamp)
        assertEquals(200_040L, first.playedMs)
        assertTrue("ran to its end: its length is known", first.fullPlay)
        assertEquals("spotify:track:0VjIjW4GlUZAMYd2vXMi3b", first.sourceId)
        assertTrue("the forward button is a skip", plays[1].skipped)
        assertFalse(plays[1].fullPlay)
        assertFalse("skipped: null isn't a skip", plays[2].skipped)
        assertTrue(plays.none { it.title.contains("Episode") })
    }

    @Test fun olderExtendedFormatToo() {
        val (plays, r) = spotify("endsong_0.json")
        assertEquals(setOf(FileKind.SPOTIFY_EXTENDED), r.kinds)
        assertEquals(listOf("Shape of You", "Castle on the Hill"), plays.map { it.title })
        assertEquals("÷ (Deluxe)", plays[0].album)
        assertTrue(plays[1].skipped)
    }

    @Test fun accountDataBothFormats() {
        val (plays, r) = spotify("StreamingHistory_music_0.json")
        assertEquals(setOf(FileKind.SPOTIFY_ACCOUNT), r.kinds)
        assertEquals(3, plays.size)
        // "2024-01-02 22:05" is UTC, to the minute.
        assertEquals(Instant.parse("2024-01-02T22:05:00Z").toEpochMilli(), plays[0].timestamp)
        assertEquals("米津玄師", plays[2].artist)
        val (old, _) = spotify("StreamingHistory0.json")
        // "Unknown Track" by "Unknown Artist" (a phone's own file) left out; the podcast in the old mixed file can't be told apart.
        assertEquals(listOf("Bohemian Rhapsody - Remastered 2011", "#1385 - Some Guest"), old.map { it.title })
        assertEquals(SpotifyExport.kindOf("StreamingHistory_podcast_0.json"), null)
    }

    @Test fun libraryAndPlaylists() {
        val (_, lib) = spotify("YourLibrary.json")
        assertEquals("the same song twice is one", listOf("Blinding Lights", "Tití Me Preguntó"), lib.library.liked.map { it.title })
        assertEquals("Rumours", lib.library.albums.single().title)
        assertEquals(listOf("Radiohead", "Rosalía"), lib.library.artists)
        val (_, pl) = spotify("Playlist1.json")
        val road = pl.library.playlists.first { it.name == "Road trip" }
        assertEquals("episodes and the phone's own files aren't songs", listOf("Africa", "Mr. Brightside"), road.tracks.map { it.title })
        assertTrue(pl.library.playlists.any { it.name == "Empty one" && it.tracks.isEmpty() })
    }

    /** The zip as Spotify sends it, folders and all: both histories in it, and the extended one wins. */
    @Test fun aZipAsItCame() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                fun put(path: String, name: String) { z.putNextEntry(ZipEntry(path)); z.write(res(name)); z.closeEntry() }
                z.putNextEntry(ZipEntry("Spotify Extended Streaming History/")); z.closeEntry()
                put("Spotify Extended Streaming History/Streaming_History_Audio_2023-2024_0.json", "Streaming_History_Audio_2023-2024_0.json")
                put("Spotify Account Data/StreamingHistory_music_0.json", "StreamingHistory_music_0.json")
                put("Spotify Account Data/YourLibrary.json", "YourLibrary.json")
                put("Spotify Account Data/Playlist1.json", "Playlist1.json")
                z.putNextEntry(ZipEntry("Spotify Account Data/Userdata.json")); z.write("{\"username\":\"x\"}".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("Spotify Account Data/ReadMeFirst.pdf")); z.write(byteArrayOf(1, 2, 3)); z.closeEntry()
            }
        }.toByteArray()
        val (plays, r) = spotify("my_spotify_data.zip", zip, SpotifyExport.Options(accountHistory = false))
        assertEquals(setOf(FileKind.SPOTIFY_EXTENDED, FileKind.SPOTIFY_ACCOUNT, FileKind.SPOTIFY_LIBRARY, FileKind.SPOTIFY_PLAYLISTS), r.kinds)
        assertEquals("only the extended history's plays", 5, plays.size)
        assertEquals(2, r.library.liked.size)
        assertEquals(2, r.library.playlists.size)
        assertEquals("Userdata.json isn't read", 1, r.skippedFiles)
    }

    @Test fun aFileWithAByteOrderMarkOrCutShort() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + res("StreamingHistory_music_0.json")
        assertEquals(3, spotify("StreamingHistory_music_0.json", bom).first.size)
        val whole = res("Streaming_History_Audio_2023-2024_0.json")
        val cut = whole.copyOf(whole.size * 2 / 3)
        val (plays, _) = spotify("Streaming_History_Audio_2023-2024_0.json", cut)
        assertTrue("what was there before the cut is read (${plays.size})", plays.size in 2..5)
    }

    @Test fun takeoutKeepsYouTubeMusicPlaysOnly() {
        val plays = ArrayList<ParsedPlay>()
        assertTrue(TakeoutHistory.read("watch-history.json", ByteArrayInputStream(res("watch-history.json"))) { plays += it })
        assertEquals("not YouTube's videos, removed videos or ads", listOf("Blinding Lights", "Tití Me Preguntó", "Africa"), plays.map { it.title })
        assertEquals("fHI8X4OXluQ", plays[0].youtubeId)
        assertEquals("The Weeknd", plays[0].artist)
        assertTrue(plays.all { it.estimated })
        // Newest first: the next play's time caps the one before it.
        assertEquals(4 * 60_000L, plays[1].playedMs)
        assertEquals("a day's gap is no listen", TakeoutHistory.CAP_MS, plays[2].playedMs)
        assertEquals("nothing after it", TakeoutHistory.CAP_MS, plays[0].playedMs)
        assertFalse(TakeoutHistory.read("search-history.json", ByteArrayInputStream("[]".toByteArray())) {})
    }

    @Test fun lastFmPagesAndPaging() = runBlocking {
        val one = LastFmHistory.page(res("lastfm-page1.json").decodeToString())!!
        assertEquals(2, one.totalPages)
        assertEquals("now playing isn't a scrobble", listOf("Paranoid Android", "Airbag"), one.plays.map { it.title })
        assertEquals("Radiohead", one.plays[0].artist)
        assertEquals("OK Computer", one.plays[0].album)
        assertEquals(1_714_557_600_000L, one.plays[0].timestamp)
        assertEquals("the gap to the next scrobble", 280_000L, one.plays[1].playedMs)
        val asked = ArrayList<Int>()
        val all = ArrayList<ParsedPlay>()
        val n = LastFmHistory.readAll({ p -> asked += p; res("lastfm-page$p.json").decodeToString() }, { all += it }, pause = 0)
        assertEquals(listOf(1, 2), asked)
        assertEquals(3, n.plays)
        assertTrue(n.finished)
        val lucky = all.last()
        assertEquals("a page with one scrobble", "Get Lucky (feat. Pharrell Williams & Nile Rodgers)", lucky.title)
        assertEquals("capped across pages", TakeoutHistory.CAP_MS, lucky.playedMs)
        // A page that keeps failing ends the reading with what was read.
        var tries = 0
        val partial = LastFmHistory.readAll({ p -> if (p == 1) res("lastfm-page1.json").decodeToString() else { tries++; throw java.io.IOException("offline") } }, {}, pause = 0)
        assertEquals(2, partial.plays)
        assertEquals(3, tries)
        // It says so, and the next import asks for everything again (not only since the newest scrobble it got).
        assertFalse(partial.finished)
        assertNull(LastFmHistory.fromSeconds(1_714_557_600_000L, lastFinished = false))
        assertEquals(1_714_557_600L, LastFmHistory.fromSeconds(1_714_557_600_000L, lastFinished = true))
        assertNull(LastFmHistory.page("{\"error\":6,\"message\":\"User not found\"}"))
    }

    @Test fun songKeysTieTheSameSongTogether() {
        assertEquals(ImportKeys.songKey("Get Lucky (feat. Pharrell Williams)", "Daft Punk"), ImportKeys.songKey("Get Lucky", "Daft Punk feat. Pharrell Williams"))
        assertEquals(ImportKeys.songKey("Bohemian Rhapsody - Remastered 2011", "Queen"), ImportKeys.songKey("Bohemian Rhapsody", "Queen"))
        assertTrue(ImportKeys.songKey("Blinding Lights", "The Weeknd") != ImportKeys.songKey("Blinding Lights", "Some Cover Band"))
        assertEquals("Simon & Garfunkel", ImportKeys.mainArtist("Simon & Garfunkel"))
        assertEquals("a comma is part of a name", "Earth, Wind & Fire", ImportKeys.mainArtist("Earth, Wind & Fire"))
        assertEquals("The Weeknd", ImportKeys.mainArtist("The Weeknd - Topic"))
        assertEquals(setOf("live"), ImportKeys.versionMarks("Hotel California - Live On MTV, 1994"))
        assertEquals(setOf("remaster"), ImportKeys.versionMarks("Hey Jude - Remastered 2015"))
        assertEquals("a song called Live Forever isn't live", emptySet<String>(), ImportKeys.versionMarks("Live Forever"))
        assertEquals(setOf("rerecorded"), ImportKeys.versionMarks("Love Story (Taylor's Version)"))
        assertEquals("Original Mix is the song", emptySet<String>(), ImportKeys.versionMarks("Strobe - Original Mix"))
    }
}
