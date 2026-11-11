package com.prism.music

import com.prism.music.data.GenreShare
import com.prism.music.data.Personality
import com.prism.music.data.PrismYear
import com.prism.music.data.RewindCalendar
import com.prism.music.data.RewindSnapshot
import com.prism.music.data.RewindStore
import com.prism.music.data.SeasonGenre
import com.prism.music.data.StoryFacts
import com.prism.music.data.StoryPart
import com.prism.music.data.StoryParts
import com.prism.music.data.db.AlbumPlays
import com.prism.music.data.db.ArtistPlays
import com.prism.music.data.db.FirstPlay
import com.prism.music.data.db.MonthTotal
import com.prism.music.data.db.SongPlays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Rewind: every year, on the phone's own clock, kept once it's over, and a story that fits how much was played. */
// Robolectric: the words come from strings.xml (1.7.9).
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class RewindTest {
    private val zones = listOf("America/New_York", "Europe/London", "Australia/Sydney", "Asia/Kolkata", "America/Sao_Paulo", "Pacific/Chatham", "UTC").map(ZoneId::of)
    private val day = 86_400_000L

    @Test
    fun yearsRunMidnightToMidnightWhateverDaylightSavingDoes() {
        for (zone in zones) for (year in listOf(2025, 2026, 2028)) {
            val (from, to) = RewindCalendar.bounds(year, zone)
            val start = Instant.ofEpochMilli(from).atZone(zone)
            assertEquals(LocalDate.of(year, 1, 1), start.toLocalDate())
            assertEquals(0, start.hour); assertEquals(0, start.minute)
            // The last millisecond is still 31 December there; the next one is next year.
            assertEquals(LocalDate.of(year, 12, 31), Instant.ofEpochMilli(to).atZone(zone).toLocalDate())
            assertEquals(year + 1, RewindCalendar.yearOf(to + 1, zone))
            assertEquals(year, RewindCalendar.yearOf(to, zone))
            // A clock change forward and one back cancel out over a year: exactly 365 (or 366) days.
            val days = if (year % 4 == 0) 366 else 365
            assertEquals("$zone $year", days * day, to - from + 1)
        }
    }

    @Test
    fun seasonsCoverTheYearExactly() {
        val zone = ZoneId.of("America/New_York")
        val (from, to) = RewindCalendar.bounds(2026, zone)
        var at = from
        for (q in 0..3) {
            val (a, b) = RewindCalendar.quarterBounds(2026, q, zone)
            assertEquals(at, a)
            at = b + 1
        }
        assertEquals(to + 1, at)
    }

    @Test
    fun theCurrentYearOpensOnTheFirstOfDecember() {
        val zone = ZoneId.of("Europe/London")
        fun at(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) = ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()
        assertFalse(RewindCalendar.isOpen(2026, at(2026, 11, 30, 23, 59), zone))
        assertTrue(RewindCalendar.isOpen(2026, at(2026, 12, 1), zone))
        assertTrue(RewindCalendar.isOpen(2026, at(2026, 12, 31, 23, 59), zone))
        // Earlier years any time; later ones never; early (debug) opens this one now.
        assertTrue(RewindCalendar.isOpen(2025, at(2026, 3, 1), zone))
        assertFalse(RewindCalendar.isOpen(2027, at(2026, 12, 15), zone))
        assertTrue(RewindCalendar.isOpen(2026, at(2026, 3, 1), zone, early = true))
        assertEquals(at(2026, 12, 1), RewindCalendar.opensAt(2026, zone))
    }

    @Test
    fun everyYearWithListening() {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2028, 6, 1, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val first = ZonedDateTime.of(2025, 12, 31, 23, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(listOf(2028, 2027, 2026, 2025), RewindCalendar.years(first, now, zone))
        assertEquals(listOf(2028), RewindCalendar.years(now - 1000, now, zone))
        assertTrue(RewindCalendar.years(null, now, zone).isEmpty())
        // The same moment is another year in another zone.
        assertEquals(2026, RewindCalendar.yearOf(first, ZoneId.of("Asia/Tokyo")))
    }

    private fun snapshot(year: Int) = RewindSnapshot(
        year = year, frozenAt = 1_800_000_000_000, totalMs = 9_000_000_000, previousMs = 7_000_000_000, previousTopArtist = "Old Fave", previousSongCount = 800,
        plays = 4321, activeDays = 300, songCount = 1200, artistCount = 400,
        songs = listOf(SongPlays("aaaaaaaaaaa", 90, 18_000_000, "Song A", "Artist A", "https://i.ytimg.com/vi/a/hq.jpg", "Album A", 200, "UC1")),
        artists = listOf(ArtistPlays("Artist A", 300, 60_000_000, null, "UC1")),
        genres = listOf(GenreShare("Pop", 1_000), GenreShare("Rock", 500)), genreSongs = mapOf("Pop" to "aaaaaaaaaaa"),
        byHour = (0 until 24).map { it * 1000L }, byWeekday = (0 until 7).map { it * 10L }, longestStreak = 41,
        biggestDay = "$year-07-04", biggestDayMs = 20_000_000, newArtists = listOf(ArtistPlays("New One", 20, 3_000_000, null, null)),
        personality = Personality("The Loyalist", "You know what you love."),
        months = listOf(MonthTotal("$year-01", 100), MonthTotal("$year-02", 200)),
        topAlbum = AlbumPlays("Album A", "Artist A", 120, 20_000_000, 9, null),
        firstSong = FirstPlay("aaaaaaaaaaa", "Song A", "Artist A", null, 1_767_225_600_000),
        seasons = listOf(SeasonGenre("January–March", "Pop", 100, "Song A")), prism = PrismYear(sung = 12, recognized = 3), topLyric = "A line \"quoted\" — with dashes",
    )

    @Test
    fun keptYearsComeBackTheSame() {
        val dir = Files.createTempDirectory("rewind").toFile()
        try {
            val store = RewindStore(dir)
            assertNull(store.load(2025))
            val s = snapshot(2025)
            store.save(s)
            assertEquals(s, store.load(2025))
            assertEquals(listOf(2025), store.years())
            val h = store.load(2025)!!.habits()
            assertEquals(LocalDate.of(2025, 7, 4), h.biggestDay)
            assertEquals(23, h.peakHour)
            // A file from another version (or another year's under this name) isn't used.
            java.io.File(dir, "2024.json").writeText(java.io.File(dir, "2025.json").readText())
            assertNull(store.load(2024))
            java.io.File(dir, "2023.json").writeText(java.io.File(dir, "2025.json").readText().replace("\"version\":1", "\"version\":99"))
            assertNull(store.load(2023))
            java.io.File(dir, "2022.json").writeText("not json")
            assertNull(store.load(2022))
        } finally {
            dir.deleteRecursively()
        }
    }

    private val full = StoryFacts(
        rewind = true, totalMs = 400 * 3_600_000L, plays = 5000, activeDays = 280, songs = 100, artists = 20, genres = 5,
        seasonsWithGenre = 4, album = AlbumPlays("A", "B", 40, 1, 8, null), monthsWithListening = 12, firstSong = true,
        newArtists = 30, prism = true, previous = true,
    )

    @Test
    fun aFullYearGetsEveryPartInOrder() {
        assertEquals(StoryPart.entries.toList(), StoryParts.pick(full))
    }

    @Test
    fun partsOnlyWhenThereIsSomethingToShow() {
        // Replay of a month: the year-only parts never show.
        val month = StoryParts.pick(full.copy(rewind = false))
        assertTrue(listOf(StoryPart.SEASONS, StoryPart.TOP_ALBUM, StoryPart.MONTHS, StoryPart.FIRST_SONG, StoryPart.PRISM_YEAR, StoryPart.YEAR_COMPARE).none { it in month })
        assertEquals(StoryPart.SUMMARY, month.last())
        // A first year: no comparison; no features used: no Prism slide; one album song: no album slide.
        val first = StoryParts.pick(full.copy(previous = false, prism = false, album = AlbumPlays("A", "B", 40, 1, 1, null), newArtists = 0, seasonsWithGenre = 1))
        assertTrue(listOf(StoryPart.YEAR_COMPARE, StoryPart.PRISM_YEAR, StoryPart.TOP_ALBUM, StoryPart.DISCOVERY, StoryPart.SEASONS).none { it in first })
        assertTrue(StoryPart.TOP_ARTISTS in first && StoryPart.MONTHS in first)
    }

    @Test
    fun lightListenersGetAShortStory() {
        val light = StoryParts.pick(full.copy(totalMs = 20 * 60_000L, plays = 9, activeDays = 3, songs = 6, artists = 2))
        assertEquals(listOf(StoryPart.INTRO, StoryPart.MINUTES, StoryPart.TOP_ARTIST, StoryPart.TOP_SONG, StoryPart.SUMMARY), light)
        // Barely anything: still a story, never a slide about nothing.
        val nothing = StoryParts.pick(StoryFacts(rewind = true, totalMs = 30_000, plays = 1, activeDays = 1, songs = 0, artists = 0, genres = 0))
        assertEquals(listOf(StoryPart.INTRO, StoryPart.MINUTES, StoryPart.SUMMARY), nothing)
        // Few artists or songs: no top-five lists.
        val few = StoryParts.pick(full.copy(artists = 2, songs = 2))
        assertTrue(StoryPart.TOP_ARTISTS !in few && StoryPart.TOP_SONGS !in few && StoryPart.TOP_ARTIST in few && StoryPart.TOP_SONG in few)
    }
}
