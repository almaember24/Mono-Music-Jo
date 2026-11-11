package com.prism.music

import androidx.room.Room
import com.prism.music.data.db.PrismDatabase
import com.prism.music.data.imports.CountedPlays
import com.prism.music.data.imports.HistoryImporter
import com.prism.music.data.imports.ImportKeys
import com.prism.music.data.imports.ImportPreview
import com.prism.music.data.imports.ImportSource
import com.prism.music.data.imports.LastFmHistory
import com.prism.music.data.imports.LibraryNews
import com.prism.music.data.imports.MatchResult
import com.prism.music.data.imports.MatchStatus
import com.prism.music.data.imports.ParsedPlay
import com.prism.music.data.imports.SpotifyExport
import com.prism.music.data.lastfm.LastFm
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Repeat imports (1.8.1): a newer download of the same source adds only what's new. The preview counts exactly what
 * the import then stores; the same play in Spotify's two histories (the account data's to the minute, the extended
 * one's to the second) counts once whichever came first; only songs never seen are matched, and the listener's picks
 * stay; Last.fm is asked only for what came since.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RepeatImportsTest {
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PrismDatabase::class.java).allowMainThreadQueries().build()
    private val importer = HistoryImporter(db)

    @After fun close() = db.close()

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private class Play(val iso: String, val title: String, val artist: String, val ms: Long = 200_000)

    /** An extended-history file as Spotify writes it. */
    private fun extended(vararg plays: Play) = plays.joinToString(",", "[", "]") { p ->
        """{"ts":"${p.iso}","ms_played":${p.ms},"master_metadata_track_name":"${p.title}","master_metadata_album_artist_name":"${p.artist}",""" +
            """"master_metadata_album_album_name":"An album","spotify_track_uri":"spotify:track:${p.title.hashCode()}","reason_start":"clickrow","reason_end":"trackdone","skipped":false}"""
    }

    /** An account-data history file: the same plays, to the minute. */
    private fun account(vararg plays: Play) = plays.joinToString(",", "[", "]") { p ->
        """{"endTime":"${p.iso.take(16).replace('T', ' ')}","artistName":"${p.artist}","trackName":"${p.title}","msPlayed":${p.ms}}"""
    }

    private fun parse(name: String, json: String): List<ParsedPlay> {
        val out = ArrayList<ParsedPlay>()
        SpotifyExport.read(name, json.byteInputStream()) { out += it }
        return out
    }

    private suspend fun preview(source: ImportSource, plays: List<ParsedPlay>): HistoryImporter.Novelty {
        val n = importer.novelty(source)
        plays.forEach { n.add(it) }
        return n.result()
    }

    private suspend fun bring(source: ImportSource, plays: List<ParsedPlay>): HistoryImporter.Stats {
        val s = importer.session(source)
        plays.forEach { s.add(it) }
        return s.finish()
    }

    private suspend fun spotifyCount() = CountedPlays(listOf(ImportSource.SPOTIFY), db.counted(), db.plays()).count(0, Long.MAX_VALUE)

    private val a = Play("2025-01-10T08:00:12Z", "Africa", "TOTO")
    private val b = Play("2025-02-11T09:30:40Z", "Mr. Brightside", "The Killers")
    private val c = Play("2025-03-12T10:45:05Z", "Creep", "Radiohead")
    private val d = Play("2025-04-13T11:15:59Z", "Dreams", "Fleetwood Mac")
    private val e = Play("2025-05-14T12:00:30Z", "Everlong", "Foo Fighters")

    @Test fun aNewerDownloadAddsOnlyItsNewPlaysAndThePreviewSaysSo() = runBlocking {
        val first = bring(ImportSource.SPOTIFY, parse("Streaming_History_Audio_2025_0.json", extended(a, b, c)))
        assertEquals(3, first.stored)
        // The newer download: the last two plays again, and two new ones.
        val newer = parse("Streaming_History_Audio_2025_1.json", extended(b, c, d, e))
        val fresh = preview(ImportSource.SPOTIFY, newer)
        assertEquals("only what's new", 2, fresh.plays)
        assertEquals(at(d.iso), fresh.firstAt)
        assertEquals(at(e.iso), fresh.lastAt)
        val stats = bring(ImportSource.SPOTIFY, newer)
        assertEquals("the preview said what the import stores", fresh.plays, stats.stored)
        assertEquals(2, stats.alreadyThere)
        assertEquals(2, stats.newSongs)
        assertEquals(5, spotifyCount())
        assertEquals(5, db.imports().summaries().single().plays)
    }

    @Test fun theSameOrAnOlderDownloadHasNothingNew() = runBlocking {
        val all = parse("Streaming_History_Audio_2025_0.json", extended(a, b, c, d))
        bring(ImportSource.SPOTIFY, all)
        val older = parse("Streaming_History_Audio_2025_0.json", extended(a, b))
        assertEquals(0, preview(ImportSource.SPOTIFY, older).plays)
        assertEquals(0, preview(ImportSource.SPOTIFY, all).plays)
        val again = bring(ImportSource.SPOTIFY, all)
        assertEquals(0, again.stored)
        assertEquals(4, again.alreadyThere)
        assertEquals(4, spotifyCount())
        // What the page reads: "Nothing new in this download" only when it's a repeat and nothing at all is new.
        val p = ImportPreview(ImportSource.SPOTIFY, plays = 2, songs = 2, firstAt = at(a.iso), lastAt = at(b.iso), newPlays = 0, lastImportAt = at(d.iso), libraryNews = LibraryNews())
        assertTrue(p.nothingNew)
        assertFalse("a first import is never 'nothing new'", p.copy(lastImportAt = null).nothingNew)
        assertFalse("a new Liked Song is something new", p.copy(libraryNews = LibraryNews(liked = 1)).nothingNew)
        assertFalse(p.copy(newPlays = 1).nothingNew)
    }

    @Test fun accountDataAfterExtendedIsNotCountedTwice() = runBlocking {
        bring(ImportSource.SPOTIFY, parse("Streaming_History_Audio_2025_0.json", extended(a, b, c)))
        // The account data's year: the same three plays to the minute, and one more.
        val acct = parse("StreamingHistory_music_0.json", account(a, b, c, d))
        assertEquals("the same song within a minute is the same play", 1, preview(ImportSource.SPOTIFY, acct).plays)
        val stats = bring(ImportSource.SPOTIFY, acct)
        assertEquals(1, stats.stored)
        assertEquals(4, spotifyCount())
        // The extended plays kept their second-exact times.
        val times = db.counted().events(listOf("spotify"), 0, Long.MAX_VALUE).map { it.timestamp }
        assertTrue(at(a.iso) in times && at(b.iso) in times)
    }

    @Test fun extendedAfterAccountDataTakesThePlaysPlaceNotASecondRow() = runBlocking {
        bring(ImportSource.SPOTIFY, parse("StreamingHistory_music_0.json", account(a, b)))
        val ext = parse("Streaming_History_Audio_2025_0.json", extended(a, b, c))
        assertEquals(1, preview(ImportSource.SPOTIFY, ext).plays)
        val stats = bring(ImportSource.SPOTIFY, ext)
        assertEquals(1, stats.stored)
        assertEquals("the account-data plays took the extended details", 2, stats.upgraded)
        assertEquals(3, spotifyCount())
        val times = db.counted().events(listOf("spotify"), 0, Long.MAX_VALUE).map { it.timestamp }.sorted()
        assertEquals(listOf(at(a.iso), at(b.iso), at(c.iso)), times)
    }

    @Test fun aFeaturedArtistsSongIsOnePlayWhicheverHistoryNamesWhom() = runBlocking {
        // The extended history names the album's artist; the account data the song's own credit.
        val ext = Play("2025-06-01T18:20:41Z", "Stay (with Justin Bieber)", "The Kid LAROI")
        val acct = Play("2025-06-01T18:20:41Z", "Stay (with Justin Bieber)", "The Kid LAROI, Justin Bieber")
        val feat = Play("2025-06-02T09:00:15Z", "Get Lucky", "Pharrell Williams")
        val featAcct = Play("2025-06-02T09:00:15Z", "Get Lucky", "Daft Punk feat. Pharrell Williams")
        assertTrue(com.prism.music.data.imports.ImportedIndex.related("The Kid LAROI", "The Kid LAROI, Justin Bieber"))
        assertTrue(com.prism.music.data.imports.ImportedIndex.related("Daft Punk feat. Pharrell Williams", "Pharrell Williams"))
        assertFalse(com.prism.music.data.imports.ImportedIndex.related("Rihanna", "Zedd"))
        assertFalse("a name inside another counts as whole words only", com.prism.music.data.imports.ImportedIndex.related("Ye", "Kanye West"))

        bring(ImportSource.SPOTIFY, parse("Streaming_History_Audio_2025_0.json", extended(ext, feat)))
        val account = parse("StreamingHistory_music_0.json", account(acct, featAcct))
        assertEquals(0, preview(ImportSource.SPOTIFY, account).plays)
        assertEquals(0, bring(ImportSource.SPOTIFY, account).stored)
        assertEquals(2, spotifyCount())
    }

    @Test fun anExtendedPlayFoundByARelatedArtistKeepsTheStoredSong() = runBlocking {
        val acct = Play("2025-06-01T18:20:41Z", "Stay (with Justin Bieber)", "The Kid LAROI, Justin Bieber")
        val ext = Play("2025-06-01T18:20:41Z", "Stay (with Justin Bieber)", "The Kid LAROI")
        bring(ImportSource.SPOTIFY, parse("StreamingHistory_music_0.json", account(acct)))
        val stats = bring(ImportSource.SPOTIFY, parse("Streaming_History_Audio_2025_0.json", extended(ext)))
        assertEquals(0, stats.stored)
        assertEquals(1, stats.upgraded)
        assertEquals(1, spotifyCount())
        assertEquals("the second time took the extended play's", at(ext.iso), db.counted().events(listOf("spotify"), 0, Long.MAX_VALUE).single().timestamp)
        assertNull("no second song lined up for matching", db.imports().match(ImportKeys.songKey(ext.title, ext.artist)))
    }

    @Test fun twoSongsOfOneTitleByUnrelatedArtistsStayTwoPlays() = runBlocking {
        val rihanna = Play("2025-07-01T21:15:20Z", "Stay", "Rihanna")
        val zedd = Play("2025-07-01T21:15:50Z", "Stay", "Zedd")
        bring(ImportSource.SPOTIFY, parse("Streaming_History_Audio_2025_0.json", extended(rihanna)))
        val account = parse("StreamingHistory_music_0.json", account(zedd))
        assertEquals(1, preview(ImportSource.SPOTIFY, account).plays)
        assertEquals(1, bring(ImportSource.SPOTIFY, account).stored)
        assertEquals(2, spotifyCount())
    }

    @Test fun aSongPlayedTwiceInAMinuteStaysTwoPlays() = runBlocking {
        // Skipped after 15 s (Replay counts plays of 10 s and more), then played through.
        val skipped = Play("2025-01-10T08:00:12Z", "Africa", "TOTO", ms = 15_000)
        val again = Play("2025-01-10T08:00:55Z", "Africa", "TOTO")
        val both = parse("Streaming_History_Audio_2025_0.json", extended(skipped, again))
        bring(ImportSource.SPOTIFY, both)
        assertEquals(0, preview(ImportSource.SPOTIFY, both).plays)
        assertEquals(0, bring(ImportSource.SPOTIFY, both).stored)
        assertEquals(2, spotifyCount())
    }

    @Test fun onlyNewSongsAreMatchedAndPicksStay() = runBlocking {
        bring(ImportSource.SPOTIFY, parse("Streaming_History_Audio_2025_0.json", extended(a, b)))
        val key = ImportKeys.songKey(a.title, a.artist)
        val pick = Song("africa", "Africa", listOf(ArtistRef("TOTO")), durationSec = 295)
        importer.fix(db.imports().match(key)!!, pick)
        val sure = Song("mrb", "Mr. Brightside", listOf(ArtistRef("The Killers")), durationSec = 222)
        importer.applyMatch(db.imports().match(ImportKeys.songKey(b.title, b.artist))!!, MatchResult(MatchStatus.SURE, sure, listOf(sure)))
        val tries = db.imports().match(key)!!.tries

        val later = Play("2025-06-01T10:00:00Z", "Africa", "TOTO")
        val stats = bring(ImportSource.SPOTIFY, parse("Streaming_History_Audio_2025_1.json", extended(a, b, later, c)))
        assertEquals(2, stats.stored)
        assertEquals("only Creep is new to matching", 1, stats.newSongs)
        val m = db.imports().match(key)!!
        assertEquals(MatchStatus.FIXED.name, m.status)
        assertEquals("africa", m.matchedId)
        assertEquals(tries, m.tries)
        assertEquals(MatchStatus.SURE.name, db.imports().match(ImportKeys.songKey(b.title, b.artist))!!.status)
        assertEquals(MatchStatus.PENDING.name, db.imports().match(ImportKeys.songKey(c.title, c.artist))!!.status)
        // The new play of a song picked before counts at once.
        val top = CountedPlays(listOf(ImportSource.SPOTIFY), db.counted(), db.plays()).topSongs(0, Long.MAX_VALUE, 10)
        assertEquals(2, top.first { it.songId == "africa" }.plays)
    }

    @Test fun anImportStoppedHalfWayLeavesNoPlayWithoutItsMatchLine() = runBlocking {
        // More than a batch read, then the import is killed before finish(): the plays stored have their songs lined up,
        // so a re-import (which passes these plays over as already here) never leaves them unmatched for good.
        val s = importer.session(ImportSource.SPOTIFY)
        repeat(HistoryImporter.BATCH + 10) { i -> s.add(ParsedPlay(at("2025-01-01T00:00:00Z") + i * 300_000L, 200_000, "Song $i", "Artist ${i % 40}")) }
        val stored = db.imports().summaries().single().plays
        assertEquals(HistoryImporter.BATCH, stored)
        val unlined = db.query("SELECT COUNT(*) FROM imported_plays p WHERE NOT EXISTS (SELECT 1 FROM import_matches m WHERE m.songKey = p.songKey)", null)
            .use { it.moveToFirst(); it.getInt(0) }
        assertEquals(0, unlined)
        // Finishing still counts every song as new once.
        assertEquals(HistoryImporter.BATCH + 10, s.finish().newSongs)
    }

    @Test fun lastFmIsAskedOnlyForWhatCameSince() = runBlocking {
        assertNull("a first import asks for everything", LastFmHistory.fromSeconds(null))
        val first = listOf(
            ParsedPlay(at("2025-01-01T10:00:00Z"), 200_000, "Africa", "TOTO", estimated = true),
            ParsedPlay(at("2025-01-02T10:00:00Z"), 200_000, "Creep", "Radiohead", estimated = true),
        )
        bring(ImportSource.LASTFM, first)
        val newest = db.imports().newest(ImportSource.LASTFM.key)
        assertEquals(at("2025-01-02T10:00:00Z"), newest)
        val from = LastFmHistory.fromSeconds(newest)
        assertEquals(at("2025-01-02T10:00:00Z") / 1000, from)
        val url = LastFm.recentTracksUrl("someone", 1, 200, from, "key")
        assertEquals(from.toString(), url.queryParameter("from"))
        assertNull(LastFm.recentTracksUrl("someone", 1, 200, null, "key").queryParameter("from"))
        // Last.fm's answer from then on starts with that scrobble again: passed over.
        val since = listOf(
            ParsedPlay(at("2025-01-02T10:00:00Z"), 200_000, "Creep", "Radiohead", estimated = true),
            ParsedPlay(at("2025-01-03T10:00:00Z"), 200_000, "Dreams", "Fleetwood Mac", estimated = true),
        )
        val stats = bring(ImportSource.LASTFM, since)
        assertEquals(1, stats.stored)
        assertEquals(3, db.imports().summaries().single().plays)
    }
}
