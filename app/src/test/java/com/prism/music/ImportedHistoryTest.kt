package com.prism.music

import androidx.room.Room
import com.prism.music.data.db.PlayEventEntity
import com.prism.music.data.db.PrismDatabase
import com.prism.music.data.db.toEntity
import com.prism.music.data.imports.CountedPlays
import com.prism.music.data.imports.HistoryImporter
import com.prism.music.data.imports.ImportKeys
import com.prism.music.data.imports.ImportSource
import com.prism.music.data.imports.MatchResult
import com.prism.music.data.imports.MatchStatus
import com.prism.music.data.imports.ParsedPlay
import com.prism.music.data.imports.PlayStats
import com.prism.music.data.imports.PrismPlays
import com.prism.music.data.imports.SpotifyExport
import com.prism.music.data.imports.TakeoutHistory
import com.prism.music.data.imports.TasteHistory
import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.time.Instant

/**
 * Bring your history (1.8.1) in the database: imported plays stay apart from Prism's own, Replay with no imported
 * source counted is exactly what it was before the import (every number), counting one adds just its plays, removing a
 * source takes exactly its plays away, the same file twice adds nothing, and a Takeout play that is one of Prism's own
 * plays isn't counted twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ImportedHistoryTest {
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PrismDatabase::class.java).allowMainThreadQueries().build()
    private val importer = HistoryImporter(db)

    @After fun close() = db.close()

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun song(id: String, title: String, artist: String, sec: Int, album: String? = null) =
        Song(id, title, listOf(ArtistRef(artist, "UC$id")), album?.let { AlbumRef(it) }, sec, thumbnail = "https://i/$id.jpg")

    /** Prism's own history: a few songs played in 2023 and 2024. */
    private suspend fun prismHistory() {
        val songs = listOf(
            song("p1", "Starboy", "The Weeknd", 230, "Starboy"), song("p2", "Creep", "Radiohead", 239, "Pablo Honey"),
            song("fHI8X4OXluQ", "Blinding Lights", "The Weeknd", 201, "After Hours"),
        )
        songs.forEach { db.songs().upsert(it.toEntity()) }
        listOf(
            PlayEventEntity(songId = "p1", timestamp = at("2023-03-01T10:00:00Z"), playedMs = 230_000, source = "ALBUM"),
            PlayEventEntity(songId = "p1", timestamp = at("2023-07-01T10:00:00Z"), playedMs = 120_000),
            PlayEventEntity(songId = "p2", timestamp = at("2024-01-05T22:00:00Z"), playedMs = 239_000, source = "sing"),
            // Played in Prism, and so in YouTube's history too (Takeout has it at 10:10, the start).
            PlayEventEntity(songId = "fHI8X4OXluQ", timestamp = at("2024-05-01T10:13:21Z"), playedMs = 201_000),
        ).forEach { db.plays().insert(it) }
    }

    /** Every number Replay, Rewind and the Replay card read, for a year and for all time. */
    private suspend fun numbers(s: PlayStats): List<Any?> = listOf(0L to Long.MAX_VALUE, at("2023-01-01T00:00:00Z") to at("2023-12-31T23:59:59Z")).flatMap { (a, b) ->
        listOf(
            s.totalMs(a, b), s.count(a, b), s.topSongs(a, b, 100), s.topArtists(a, b, 20), s.monthly(a, b), s.activeDays(a, b),
            s.events(a, b).map { Triple(it.songId, it.timestamp, it.playedMs) }, s.songCount(a, b), s.artistCount(a, b), s.newArtists(a, b),
            s.topAlbums(a, b, 5), s.firstPlay(a, b), s.bySource(a, b).sortedBy { it.source.orEmpty() },
        )
    } + listOf(s.firstEvent())

    private suspend fun bringSpotify(): HistoryImporter.Stats {
        val session = importer.session(ImportSource.SPOTIFY)
        val plays = ArrayList<ParsedPlay>()
        SpotifyExport.read("Streaming_History_Audio_2023-2024_0.json", javaClass.getResourceAsStream("/imports/Streaming_History_Audio_2023-2024_0.json")!!) { plays += it }
        plays.forEach { session.add(it) }
        return session.finish()
    }

    private suspend fun bringTakeout(): HistoryImporter.Stats {
        val session = importer.session(ImportSource.TAKEOUT)
        val plays = ArrayList<ParsedPlay>()
        TakeoutHistory.read("watch-history.json", javaClass.getResourceAsStream("/imports/watch-history.json")!!) { plays += it }
        plays.forEach { session.add(it) }
        return session.finish()
    }

    private suspend fun matchAs(title: String, artist: String, s: Song) {
        val m = db.imports().match(ImportKeys.songKey(title, artist))!!
        importer.applyMatch(m, MatchResult(MatchStatus.SURE, s, listOf(s)))
    }

    @Test fun replayWithImportsOffIsExactlyPrismOnly() = runBlocking {
        prismHistory()
        val before = numbers(PrismPlays(db.plays()))
        bringSpotify()
        bringTakeout()
        matchAs("Blinding Lights", "The Weeknd", song("fHI8X4OXluQ", "Blinding Lights", "The Weeknd", 201, "After Hours"))
        matchAs("Anti-Hero", "Taylor Swift", song("ah", "Anti-Hero", "Taylor Swift", 200, "Midnights"))
        assertEquals("Replay counting Prism only: the same numbers as before the import", before, numbers(PrismPlays(db.plays())))
        assertEquals("…and asked of the combined view with no source counted", before, numbers(CountedPlays(emptyList(), db.counted(), db.plays())))
    }

    @Test fun countingASourceAddsJustItsPlays() = runBlocking {
        prismHistory()
        val prism = PrismPlays(db.plays())
        val prismMs = prism.totalMs(0, Long.MAX_VALUE)
        bringSpotify()
        matchAs("Blinding Lights", "The Weeknd", song("fHI8X4OXluQ", "Blinding Lights", "The Weeknd", 201, "After Hours"))
        val spotify = CountedPlays(listOf(ImportSource.SPOTIFY), db.counted(), db.plays())
        // Five song plays (a podcast and a broken line left out), all over 10 s; the unmatched ones count in time and plays.
        assertEquals(prismMs + 200_040 + 21_877 + 268_000 + 200_100 + 269_000, spotify.totalMs(0, Long.MAX_VALUE))
        assertEquals(4 + 5, spotify.count(0, Long.MAX_VALUE))
        val top = spotify.topSongs(0, Long.MAX_VALUE, 10)
        assertEquals("Prism's play and Spotify's two of Blinding Lights", 3, top.first { it.songId == "fHI8X4OXluQ" }.plays)
        assertTrue("an unmatched song isn't in the lists", top.none { it.title == "Anti-Hero" })
        assertEquals(at("2023-03-01T10:00:00Z"), spotify.firstEvent())
        // A source not counted adds nothing.
        val withSpotify = spotify.totalMs(0, Long.MAX_VALUE)
        bringTakeout()
        matchAs("Africa", "TOTO", song("qDLJ3pUZm9A", "Africa", "TOTO", 295, "Toto IV"))
        assertEquals(withSpotify, spotify.totalMs(0, Long.MAX_VALUE))
    }

    @Test fun removingASourceTakesExactlyItsPlays() = runBlocking {
        prismHistory()
        bringSpotify()
        bringTakeout()
        val takeoutBefore = db.imports().summaries().first { it.origin == "takeout" }
        importer.remove(ImportSource.SPOTIFY, keep = emptySet())
        val left = db.imports().summaries()
        assertEquals(listOf("takeout"), left.map { it.origin })
        assertEquals(takeoutBefore, left.single())
        assertEquals("Prism's own plays untouched", 4, db.plays().count(0, Long.MAX_VALUE))
        // Matches only Spotify's plays used are gone; Takeout's stay.
        assertEquals(null, db.imports().match(ImportKeys.songKey("Anti-Hero", "Taylor Swift")))
        assertTrue(db.imports().match(ImportKeys.songKey("Africa", "TOTO")) != null)
    }

    @Test fun theSameFileTwiceAddsNothing() = runBlocking {
        val first = bringSpotify()
        assertEquals(5, first.stored)
        assertEquals(4, first.songs)
        val again = bringSpotify()
        assertEquals(0, again.stored)
        assertEquals(5, again.alreadyThere)
        assertEquals(0, again.newSongs)
    }

    @Test fun aTakeoutPlayThatIsPrismsOwnIsLeftOutAndLengthsComeFromTheSong() = runBlocking {
        prismHistory()
        val stats = bringTakeout()
        assertEquals("Blinding Lights at 10:10 is Prism's play that ended 10:13", 1, stats.prismsOwn)
        assertEquals(2, stats.stored)
        val takeout = CountedPlays(listOf(ImportSource.TAKEOUT), db.counted(), db.plays())
        val prismMs = PrismPlays(db.plays()).totalMs(0, Long.MAX_VALUE)
        assertEquals("estimated plays count once their song is known", prismMs, takeout.totalMs(0, Long.MAX_VALUE))
        matchAs("Africa", "TOTO", song("qDLJ3pUZm9A", "Africa", "TOTO", 295, "Toto IV"))
        assertEquals("held to the song's length (it was the 10-minute cap)", prismMs + 295_000, takeout.totalMs(0, Long.MAX_VALUE))
    }

    @Test fun mixesLearnFromImportsOnlyWhenAllowed() = runBlocking {
        prismHistory()
        bringSpotify()
        matchAs("La Bachata", "Manuel Turizo", song("lb", "La Bachata", "Manuel Turizo", 269, "2000"))
        var teach = false
        val taste = TasteHistory(db.plays(), db.counted()) { if (teach) ImportSource.entries else emptyList() }
        val prismOnly = taste.tasteEventsSince(0).map { it.songId }
        assertEquals(db.plays().tasteEventsSince(0).map { it.songId }, prismOnly)
        val count = taste.changeCount(Long.MAX_VALUE)
        teach = true
        val with = taste.tasteEventsSince(0).map { it.songId }
        assertEquals("only matched imported plays", prismOnly.size + 1, with.size)
        assertTrue("lb" in with)
        assertTrue(taste.tasteTopArtists(0, Long.MAX_VALUE, 10).any { it.artistName == "Manuel Turizo" })
        assertEquals(count + 1, taste.changeCount(Long.MAX_VALUE))
    }

    @Test fun aPickInNeedsALookSticks() = runBlocking {
        bringSpotify()
        val key = ImportKeys.songKey("Anti-Hero", "Taylor Swift")
        val m = db.imports().match(key)!!
        val a = song("a1", "Anti-Hero", "Taylor Swift", 200)
        val b = song("a2", "Anti-Hero (Acoustic)", "Taylor Swift", 210)
        importer.applyMatch(m, MatchResult(MatchStatus.REVIEW, a, listOf(a, b)))
        assertEquals(listOf("a1", "a2"), HistoryImporter.candidatesOf(db.imports().match(key)!!).map { it.id })
        assertEquals("unsure: its plays wait", 0, CountedPlays(listOf(ImportSource.SPOTIFY), db.counted(), db.plays()).topSongs(0, Long.MAX_VALUE, 10).size)
        importer.fix(db.imports().match(key)!!, b)
        assertEquals(MatchStatus.FIXED.name, db.imports().match(key)!!.status)
        assertEquals("a2", CountedPlays(listOf(ImportSource.SPOTIFY), db.counted(), db.plays()).topSongs(0, Long.MAX_VALUE, 10).single().songId)
        // The same song from a later import gets the listener's pick at once.
        val later = importer.session(ImportSource.LASTFM)
        later.add(ParsedPlay(at("2025-01-01T00:00:00Z"), 200_000, "Anti-Hero", "Taylor Swift", estimated = true))
        later.finish()
        assertEquals(2, CountedPlays(listOf(ImportSource.SPOTIFY, ImportSource.LASTFM), db.counted(), db.plays()).topSongs(0, Long.MAX_VALUE, 10).single().plays)
    }
}
