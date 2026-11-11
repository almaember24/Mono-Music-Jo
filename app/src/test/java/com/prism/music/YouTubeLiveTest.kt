package com.prism.music

import android.content.Context
import androidx.room.Room
import com.prism.music.data.db.ImportSummary
import com.prism.music.data.db.PlayEventEntity
import com.prism.music.data.db.PrismDatabase
import com.prism.music.data.db.toEntity
import com.prism.music.data.imports.CountSources
import com.prism.music.data.imports.CountedPlays
import com.prism.music.data.imports.HistoryDiff
import com.prism.music.data.imports.HistoryImporter
import com.prism.music.data.imports.ImportKeys
import com.prism.music.data.imports.ImportSource
import com.prism.music.data.imports.LiveGuard
import com.prism.music.data.imports.ParsedPlay
import com.prism.music.data.imports.PrismPlays
import com.prism.music.data.imports.YouTubeLive
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
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
import java.time.ZoneId
import org.junit.experimental.categories.Category

/**
 * YouTube Music (live) (1.8.1): what's new between two reads of the account's History page (FEmusic_history, answered by
 * [FakeYouTube] as YouTube shapes it), Prism's own plays left out, estimated times inside the window, the first read only
 * a starting point, the kill switch, no job while off or signed out, and "Count history from" listing every source.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@Category(LiveNetwork::class)
class YouTubeLiveTest {
    private val app = RuntimeEnvironment.getApplication()
    private val db = Room.inMemoryDatabaseBuilder(app, PrismDatabase::class.java).allowMainThreadQueries().build()
    private val importer = HistoryImporter(db)
    private val prefs = app.getSharedPreferences(YouTubeLive.PREFS, Context.MODE_PRIVATE)
    private val utc = ZoneId.of("UTC")

    @After fun close() { db.close(); prefs.edit().clear().commit() }

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    // ---- The History page as YouTube sends it

    /** One row of the History page: a song, its artist (linked, as YouTube's are) and its length. */
    private fun row(id: String, sec: Int = 200) = """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"$id"},
        "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song $id"}]}}},
        {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist $id","navigationEndpoint":{"browseEndpoint":{"browseId":"UC$id",
          "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}}]}}}],
        "fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"runs":[{"text":"${sec / 60}:${"%02d".format(sec % 60)}"}]}}}]}}"""

    /** FEmusic_history: shelves ("Today", "Yesterday"…) of rows, newest first. */
    private fun historyPage(vararg shelves: Pair<String, List<String>>) = """{"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":
        {"sectionListRenderer":{"contents":[${shelves.joinToString(",") { (title, ids) ->
            """{"musicShelfRenderer":{"title":{"runs":[{"text":"$title"}]},"contents":[${ids.joinToString(",") { row(it) }}]}}"""
        }}]}}}}]}}}"""

    private var page = historyPage()
    private var reads = 0
    private val yt = FakeYouTube { endpoint, body ->
        if (endpoint == "browse" && body["browseId"]?.jsonPrimitive?.content == "FEmusic_history") { reads++; 200 to page } else 200 to "{}"
    }

    private class FakeJobs : YouTubeLive.Jobs {
        var running = false
        override fun schedule() { running = true }
        override fun cancel() { running = false }
    }

    private var now = at("2026-11-03T09:00:00Z")
    private var account: String? = "0|sam@example.com"
    private val jobs = FakeJobs()
    private val logged = mutableListOf<String>()

    private fun live(read: (suspend () -> List<Pair<String, List<Song>>>)? = null): YouTubeLive {
        prefs.edit().putBoolean("on", true).commit()
        return YouTubeLive(
        prefs, db, importer, read = read ?: { yt.ytm.historySections() }, account = { account }, jobs = jobs,
        clock = { now }, zone = { utc }, log = { logged += it },
        )
    }

    private suspend fun livePlays() = db.imports().summaries().firstOrNull { it.origin == ImportSource.YOUTUBE_LIVE.key }?.plays ?: 0

    // ---- The diff

    @Test fun songsNewlyOnTopAreTheNewPlays() {
        val before = listOf("a", "b", "c", "d", "e")
        assertEquals("nothing new", 0, HistoryDiff.newOnTop(before, before))
        assertEquals("two new on top", 2, HistoryDiff.newOnTop(before, listOf("x", "y") + before))
        assertEquals("one moved up: played again", 1, HistoryDiff.newOnTop(before, listOf("c", "a", "b", "d", "e")))
        assertEquals("a new one and one moved up", 2, HistoryDiff.newOnTop(before, listOf("x", "d", "a", "b", "c", "e")))
        assertEquals("played again while already on top, listed twice", 1, HistoryDiff.newOnTop(before, listOf("a") + before))
        assertEquals("the page's end cut off", 1, HistoryDiff.newOnTop(before, listOf("x", "a", "b", "c", "d")))
        assertEquals("an empty history before: all of it is new", 2, HistoryDiff.newOnTop(emptyList(), listOf("x", "y")))
        assertNull("nothing in common: a new starting point, nothing counted", HistoryDiff.newOnTop(before, listOf("p", "q", "r")))
        // YouTube removed some from the middle: only the songs the last read didn't have count.
        assertEquals(1, HistoryDiff.newOnTop(before, listOf("x", "b", "a", "e")))
    }

    @Test fun estimatedTimesStayInsideTheWindow() {
        val from = at("2026-11-03T06:00:00Z")
        val to = at("2026-11-03T09:00:00Z")
        val t = HistoryDiff.times(List(5) { "Today" }, "Today", from, to, utc)
        assertEquals(5, t.size)
        assertTrue("inside the window", t.all { it in (from + 1) until to })
        assertEquals("newest latest", t.sortedDescending(), t)
        assertEquals("all different", 5, t.toSet().size)

        // Across midnight: "Today" after it, "Yesterday" before it.
        val evening = at("2026-11-02T22:00:00Z")
        val night = at("2026-11-03T01:00:00Z")
        val midnight = at("2026-11-03T00:00:00Z")
        val split = HistoryDiff.times(listOf("Today", "Today", "Yesterday"), "Yesterday", evening, night, utc)
        assertTrue(split.take(2).all { it in midnight..night })
        assertTrue(split[2] in evening..midnight)
        // All new ones under "Today", the one below them under "Yesterday": all after midnight.
        assertTrue(HistoryDiff.times(listOf("Today", "Today"), "Yesterday", evening, night, utc).all { it in midnight..night })
        // Shelves that don't change as often as the window passes midnight: spread over the whole window.
        val week = HistoryDiff.times(listOf("This week", "This week"), "Last week", at("2026-10-30T10:00:00Z"), at("2026-11-03T10:00:00Z"), utc)
        assertTrue(week.all { it in at("2026-10-30T10:00:00Z")..at("2026-11-03T10:00:00Z") })
    }

    // ---- Reading the page

    @Test fun theFirstReadIsOnlyAStartingPointAndThenNewSongsCount() = runBlocking {
        val live = live()
        page = historyPage("Today" to listOf("a", "b"), "Yesterday" to listOf("c", "d", "e"))
        assertEquals("the first read brings nothing from before", 0, live.sync())
        assertEquals(0, livePlays())
        assertEquals(now, live.lastCheck.value)

        val from = now
        now += 3 * 3_600_000L
        page = historyPage("Today" to listOf("x", "c", "a", "b"), "Yesterday" to listOf("d", "e"))
        assertEquals("x is new, c was played again", 2, live.sync())
        val rows = db.query("SELECT timestamp, playedMs, songKey, matchedId, estimated FROM imported_plays WHERE origin = 'youtube_live' ORDER BY timestamp DESC", null).use { c ->
            generateSequence { if (c.moveToNext()) listOf(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getInt(4)) else null }.toList()
        }
        assertEquals(listOf(ImportKeys.songKey("Song x", "Artist x"), ImportKeys.songKey("Song c", "Artist c")), rows.map { it[2] })
        assertTrue("times inside the window", rows.all { (it[0] as Long) in from..now })
        assertTrue("the song's length, estimated", rows.all { it[1] == 200_000L && it[4] == 1 })
        assertEquals("the page names the song: matched at once", listOf("x", "c"), rows.map { it[3] })
        assertEquals("one request a read", 2, reads)

        // The same page again: nothing new.
        now += 3 * 3_600_000L
        assertEquals(0, live.sync())
        assertEquals(2, livePlays())
    }

    @Test fun prismsOwnPlaysAreLeftOut() = runBlocking {
        val live = live()
        page = historyPage("Today" to listOf("a", "b", "c"))
        live.sync()
        val from = now
        now += 2 * 3_600_000L
        // Prism played y an hour ago (its play is noted when it ends), and reported z, still playing.
        val y = Song("y", "Song y", listOf(ArtistRef("Artist y")), durationSec = 200)
        db.songs().upsert(y.toEntity())
        db.plays().insert(PlayEventEntity(songId = "y", timestamp = from + 3_600_000L, playedMs = 200_000))
        YouTubeLive.noteSent(app, "z", ImportKeys.songKey("Song z", "Artist z"), now = now - 60_000)
        // A Takeout already has w in this window.
        importer.session(ImportSource.TAKEOUT).apply { add(ParsedPlay(from + 600_000, 200_000, "Song w", "Artist w", sourceId = "w", youtubeId = "w", estimated = true)) }.finish()
        page = historyPage("Today" to listOf("z", "y", "x", "w", "a", "b", "c"))
        assertEquals("only x was played elsewhere", 1, live.sync())
        assertEquals(1, livePlays())

        // A Takeout brought later takes the live play of the same song out (it's the complete history).
        val liveAt = db.query("SELECT timestamp FROM imported_plays WHERE origin = 'youtube_live'", null).use { c -> c.moveToFirst(); c.getLong(0) }
        importer.session(ImportSource.TAKEOUT).apply { add(ParsedPlay(liveAt + 1_800_000, 200_000, "Song x", "Artist x", youtubeId = "x", estimated = true)) }.finish()
        assertEquals(1, db.imports().dropYouTubeLiveTwins(YouTubeLive.TWIN_MS))
        assertEquals(0, livePlays())
    }

    @Test fun anotherAccountOrALongGapIsANewStartingPoint() = runBlocking {
        val live = live()
        page = historyPage("Today" to listOf("a", "b", "c"))
        live.sync()
        account = "1|other@example.com"
        now += 3_600_000L
        page = historyPage("Today" to listOf("x", "a", "b", "c"))
        assertEquals(0, live.sync())
        account = "1|other@example.com"
        now += YouTubeLive.MAX_GAP_MS + 1
        page = historyPage("Today" to listOf("y", "x", "a", "b", "c"))
        assertEquals(0, live.sync())
        assertEquals(0, livePlays())
        // An email still loading is the same account.
        assertTrue(YouTubeLive.sameAccount("0|", "0|sam@example.com"))
        assertFalse(YouTubeLive.sameAccount("0|sam@example.com", "1|sam@example.com"))
        assertFalse(YouTubeLive.sameAccount("0|sam@example.com", "0|alex@example.com"))
    }

    @Test fun anEmptyPageKeepsTheLastReadToCompareWith() = runBlocking {
        val live = live()
        page = historyPage("Today" to listOf("a", "b", "c"))
        live.sync()
        val first = live.snapshot()
        now += 3_600_000L
        page = historyPage()
        assertEquals(0, live.sync())
        assertEquals("kept", first, live.snapshot())
        now += 3_600_000L
        page = historyPage("Today" to listOf("x", "a", "b", "c"))
        assertEquals("only x, not the whole page", 1, live.sync())
    }

    @Test fun aReadThatFailsToStoreIsComparedAgainNotLost() = runBlocking {
        page = historyPage("Today" to listOf("a", "b", "c"))
        live().sync()
        val first = live().snapshot()
        now += 3_600_000L
        page = historyPage("Today" to listOf("x", "a", "b", "c"))
        // Storing fails (a closed database here): the read before stays the one to compare with.
        val gone = Room.inMemoryDatabaseBuilder(app, PrismDatabase::class.java).allowMainThreadQueries().build().also { it.close() }
        val failing = YouTubeLive(prefs, db, HistoryImporter(gone), read = { yt.ytm.historySections() }, account = { account }, jobs = jobs, clock = { now }, zone = { utc })
        assertTrue(runCatching { failing.sync() }.isFailure)
        assertEquals("kept", first, live().snapshot())
        now += 3_600_000L
        assertEquals("x is brought in next time", 1, live().sync())
        assertEquals(1, livePlays())
    }

    // ---- Kill switch and scheduling

    @Test fun failuresPauseItForADay() = runBlocking {
        var asked = 0
        val live = live { asked++; throw java.io.IOException("offline") }
        repeat(LiveGuard.LIMIT) { assertNull(live.run()) }
        assertEquals(LiveGuard.LIMIT, asked)
        assertEquals(LiveGuard.State.PAUSED, live.guard.state.value)
        assertTrue(logged.any { it.contains("paused for a day") })
        // Paused: not asked at all.
        assertNull(live.run())
        assertEquals(LiveGuard.LIMIT, asked)
        now += LiveGuard.DAY_MS + 1
        live.run()
        assertEquals("asked again a day later", LiveGuard.LIMIT + 1, asked)
    }

    @Test fun offOrSignedOutMeansNoJobAndNoRequests() = runBlocking {
        val live = live()
        live.setOn(false)
        assertFalse("off: no job", jobs.running)
        assertNull("off: nothing asked", live.run())
        live.setOn(true)
        assertTrue("on and signed in: the job", jobs.running)
        account = null
        live.reschedule()
        assertFalse("signed out: no job", jobs.running)
        assertNull(live.run())
        assertEquals("no request at all", 0, reads)

        // Sign-out (PrismApp watches it): the job stops and the next read starts over.
        account = "0|sam@example.com"
        page = historyPage("Today" to listOf("a"))
        live.sync()
        val fake = FakeJobs()
        YouTubeLive.signInChanged(app, signedIn = false, wasSignedIn = true, jobs = fake)
        assertFalse(fake.running)
        assertNull(live.snapshot())
        YouTubeLive.signInChanged(app, signedIn = true, wasSignedIn = false, jobs = fake)
        assertTrue("signed in again with it on: the job again", fake.running)
        prefs.edit().putBoolean("on", false).commit()
        YouTubeLive.signInChanged(app, signedIn = true, wasSignedIn = true, jobs = fake)
        assertFalse("never turned on: no job", fake.running)
    }

    @Test fun nothingIsRememberedOfPrismsReportsWhileOff() {
        prefs.edit().putBoolean("on", false).commit()
        YouTubeLive.noteSent(app, "z", "k")
        assertFalse(prefs.contains("sent"))
    }

    // ---- Count history from, and Replay

    @Test fun countHistoryFromListsEverySource() {
        val none = CountSources.rows(emptyList(), emptySet(), emptySet())
        assertEquals(
            listOf(ImportSource.SPOTIFY, ImportSource.TAKEOUT, ImportSource.LASTFM, ImportSource.SPOTIFY_LIVE, ImportSource.YOUTUBE_LIVE),
            none.map { it.source },
        )
        assertTrue(none.all { it.state == CountSources.State.MISSING && !it.counted })
        val rows = CountSources.rows(
            listOf(ImportSummary("spotify", 12, 1, 2, 10, 1_000)),
            liveOn = setOf(ImportSource.YOUTUBE_LIVE),
            // Takeout "counted" but nothing brought in: greyed, never counted.
            counted = setOf(ImportSource.SPOTIFY, ImportSource.TAKEOUT, ImportSource.YOUTUBE_LIVE),
        ).associateBy { it.source }
        assertEquals(CountSources.State.COUNTABLE, rows.getValue(ImportSource.SPOTIFY).state)
        assertEquals(12, rows.getValue(ImportSource.SPOTIFY).plays)
        assertTrue(rows.getValue(ImportSource.SPOTIFY).counted)
        assertEquals(CountSources.State.MISSING, rows.getValue(ImportSource.TAKEOUT).state)
        assertFalse(rows.getValue(ImportSource.TAKEOUT).counted)
        assertEquals("on, no plays yet: its switch", CountSources.State.COUNTABLE, rows.getValue(ImportSource.YOUTUBE_LIVE).state)
        assertEquals(CountSources.State.MISSING, rows.getValue(ImportSource.SPOTIFY_LIVE).state)
        assertTrue(ImportSource.YOUTUBE_LIVE.isLive && !ImportSource.TAKEOUT.isLive)
    }

    @Test fun replayWithItOffIsAsBefore() = runBlocking {
        val s = Song("p", "Song p", listOf(ArtistRef("Artist p")), durationSec = 180)
        db.songs().upsert(s.toEntity())
        db.plays().insert(PlayEventEntity(songId = "p", timestamp = at("2026-11-02T12:00:00Z"), playedMs = 180_000))
        val prism = PrismPlays(db.plays())
        val before = prism.totalMs(0, Long.MAX_VALUE) to prism.count(0, Long.MAX_VALUE)

        val live = live()
        page = historyPage("Today" to listOf("a", "b", "c"))
        live.sync()
        now += 3_600_000L
        page = historyPage("Today" to listOf("x", "a", "b", "c"))
        assertEquals(1, live.sync())
        assertEquals("Prism only: exactly as before", before, prism.totalMs(0, Long.MAX_VALUE) to prism.count(0, Long.MAX_VALUE))
        val counted = CountedPlays(listOf(ImportSource.YOUTUBE_LIVE), db.counted(), db.plays())
        assertEquals("counted when switched on", before.first + 200_000, counted.totalMs(0, Long.MAX_VALUE))
    }
}
