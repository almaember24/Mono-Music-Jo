package com.prism.music

import com.prism.music.data.foryou.LocalRadio
import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.HomeSectionConfig
import com.prism.music.data.prefs.HomeSections
import com.prism.music.data.seasonal.Holiday
import com.prism.music.data.seasonal.HolidayMixMaker
import com.prism.music.data.seasonal.HolidayMixMaker.Found
import com.prism.music.data.seasonal.HolidayMixes
import com.prism.music.data.seasonal.Seed
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 1.8.0's holiday mixes: their seasons, how a mix is put together, and Home's section coming and going with the date. */
class HolidayMixesTest {
    private fun d(m: Int, day: Int, y: Int = 2026) = LocalDate.of(y, m, day)

    private fun song(id: String, title: String, artist: String, video: Boolean = false, album: String? = null, sec: Int = 200) =
        Song(id, title, listOf(ArtistRef(artist)), album = album?.let { AlbumRef(it) }, durationSec = sec, isVideo = video)

    private val christmas = HolidayMixes.byId("season:christmas_classics")!!
    private val thanksgiving = HolidayMixes.byId("season:thanksgiving_dinner")!!

    // ------------------------------------------------------------------------------------------------ seasons

    @Test fun theSeasonsAreNovemberAndDecember() {
        assertNull(Holiday.on(d(10, 31)))
        assertEquals(Holiday.THANKSGIVING, Holiday.on(d(11, 1)))
        assertEquals(Holiday.THANKSGIVING, Holiday.on(d(11, 30)))
        assertEquals(Holiday.CHRISTMAS, Holiday.on(d(12, 1)))
        assertEquals(Holiday.CHRISTMAS, Holiday.on(d(12, 31)))
        assertNull(Holiday.on(d(1, 1, 2027)))
        assertTrue(HolidayMixes.on(d(10, 31)).isEmpty())
        assertTrue(HolidayMixes.on(d(1, 1, 2027)).isEmpty())
    }

    @Test fun eachMonthShowsItsOwnMixes() {
        val nov = HolidayMixes.on(d(11, 20))
        assertEquals(8, nov.size)
        assertTrue(nov.all { it.holiday == Holiday.THANKSGIVING })
        val dec = HolidayMixes.on(d(12, 10))
        assertTrue(dec.all { it.holiday == Holiday.CHRISTMAS })
        // New Year's Eve's party only from Boxing Day.
        assertFalse(dec.any { it.key == "new_years_eve" })
        assertFalse(HolidayMixes.on(d(12, 25)).any { it.key == "new_years_eve" })
        assertTrue(HolidayMixes.on(d(12, 26)).any { it.key == "new_years_eve" })
        assertTrue(HolidayMixes.on(d(12, 31)).any { it.key == "new_years_eve" })
        assertEquals(14, HolidayMixes.on(d(12, 31)).size)
    }

    @Test fun theDateIsThePhonesOwn() {
        // 03:00 on the 1st of December in London is still the 30th of November in Los Angeles.
        val at = Instant.parse("2026-12-01T03:00:00Z")
        assertEquals(Holiday.THANKSGIVING, Holiday.on(LocalDate.now(Clock.fixed(at, ZoneId.of("America/Los_Angeles")))))
        assertEquals(Holiday.CHRISTMAS, Holiday.on(LocalDate.now(Clock.fixed(at, ZoneId.of("Europe/London")))))
    }

    @Test fun everyMixHasANameALineAndSeeds() {
        assertEquals(HolidayMixes.all.size, HolidayMixes.all.map { it.id }.distinct().size)
        for (m in HolidayMixes.all) {
            assertFalse(m.key, tr(m.name).startsWith("#"))
            assertFalse(m.key, tr(m.blurb).startsWith("#"))
            assertTrue(m.key, m.seeds.size >= 3)
            assertTrue(m.key, m.seeds.any { it.playlist })
            assertEquals(m, HolidayMixes.byId(m.id))
        }
        assertTrue(HolidayMixes.isId("season:merry_lofi"))
        assertFalse(HolidayMixes.isId("daily:1"))
    }

    // ------------------------------------------------------------------------------------------------ assembly

    @Test fun oneCopyOfEachSong() {
        val found = listOf(
            Found(Seed("a", playlist = true), listOf(song("v1", "Last Christmas (Official Video)", "Wham!", video = true), song("s1", "Last Christmas", "Wham!"))),
            Found(Seed("b"), listOf(song("s2", "Last Christmas (Remastered)", "Wham!"), song("s3", "Jingle Bell Rock", "Bobby Helms"))),
        )
        val mix = HolidayMixMaker.assemble(christmas, found, "2026-W50")
        assertEquals(2, mix.size)
        // The song, not its music video.
        assertEquals(1, mix.count { LocalRadio.sameSong(it) == LocalRadio.sameSong(song("x", "Last Christmas", "Wham!")) })
        assertFalse(mix.any { it.isVideo })
    }

    @Test fun knockOffsAreLeftOut() {
        val bad = listOf(
            song("k1", "Jingle Bells (Karaoke Version)", "Party Singers"),
            song("k2", "Last Christmas - Sped Up", "Someone"),
            song("k3", "Silent Night", "Karaoke Christmas Band"),
            song("k4", "All I Want for Christmas Is You (Cover)", "A Fan"),
            song("k5", "Feliz Navidad (Instrumental)", "Studio Players"),
            song("k6", "Santa Baby sped up", "Edits"),
        )
        val good = song("g1", "Feliz Navidad", "José Feliciano")
        val mix = HolidayMixMaker.assemble(christmas, listOf(Found(Seed("q", playlist = true), bad + good)), "2026-W50")
        assertEquals(listOf("g1"), mix.map { it.id })
        assertTrue(bad.all { HolidayMixMaker.isKnockoff(it) || HolidayMixMaker.isFiller(christmas, it, true) })
        // What the mix asks for isn't a knock-off there: Merry Lo-fi's lo-fi.
        val lofi = HolidayMixes.byId("season:merry_lofi")!!
        assertFalse(HolidayMixMaker.isKnockoff(song("l", "Silent Night (Lofi)", "Sleepy Fish"), lofi.asks))
        assertTrue(HolidayMixMaker.isKnockoff(song("l", "Silent Night (Lofi)", "Sleepy Fish")))
    }

    @Test fun noArtistMoreThanThreeTimesNorTwiceRunning() {
        val mariah = (1..8).map { song("m$it", "Christmas Song $it", "Mariah Carey") }
        val others = (1..12).map { song("o$it", "Holiday Tune $it", "Artist $it") }
        val mix = HolidayMixMaker.assemble(christmas, listOf(Found(Seed("q", playlist = true), mariah + others)), "2026-W50")
        // (Two here: with nobody else left, a third would have followed her second.)
        assertTrue(mix.count { it.primaryArtist == "Mariah Carey" } in 2..3)
        assertTrue(mix.zipWithNext().none { (a, b) -> a.primaryArtist == b.primaryArtist })
        assertTrue(mix.groupingBy { it.primaryArtist }.eachCount().values.all { it <= HolidayMixMaker.PER_ARTIST })
    }

    @Test fun aboutFiftySongsAndTheWeekChangesThem() {
        val pool = (1..150).map { song("p$it", "Snow Song $it", "Singer $it") }
        val found = listOf(Found(Seed("q", playlist = true), pool))
        val w1 = HolidayMixMaker.assemble(christmas, found, "2026-W49")
        val w2 = HolidayMixMaker.assemble(christmas, found, "2026-W50")
        assertEquals(HolidayMixMaker.SIZE, w1.size)
        assertEquals(w1, HolidayMixMaker.assemble(christmas, found, "2026-W49"))
        assertTrue("a new week, a little different", w1.map { it.id }.toSet() != w2.map { it.id }.toSet())
    }

    @Test fun eachSeasonKeepsToItself() {
        // November's mixes have no Christmas songs; December's keep a search's songs only when they sound like it.
        assertFalse(HolidayMixMaker.fits(thanksgiving, song("a", "Christmas In Harlem", "Kanye West"), fromPlaylist = true))
        assertTrue(HolidayMixMaker.fits(thanksgiving, song("b", "My Girl", "The Temptations"), fromPlaylist = false))
        assertFalse(HolidayMixMaker.fits(christmas, song("c", "Hotel California", "Eagles"), fromPlaylist = false))
        assertTrue(HolidayMixMaker.fits(christmas, song("d", "Sleigh Ride", "The Ronettes"), fromPlaylist = false))
        assertTrue(HolidayMixMaker.fits(christmas, song("e", "River", "Joni Mitchell", album = "Blue"), fromPlaylist = true))
        // Halloween never.
        assertFalse(HolidayMixMaker.fits(christmas, song("f", "This Is Halloween", "Danny Elfman"), fromPlaylist = true))
        // A search's music videos aren't taken; a playlist's are.
        assertFalse(HolidayMixMaker.fits(christmas, song("g", "Jingle Bells", "Bing Crosby", video = true), fromPlaylist = false))
        assertTrue(HolidayMixMaker.fits(christmas, song("g", "Jingle Bells", "Bing Crosby", video = true), fromPlaylist = true))
        // Too short or too long.
        assertFalse(HolidayMixMaker.fits(christmas, song("h", "Jingle Bells", "Bing Crosby", sec = 30), fromPlaylist = true))
    }

    @Test fun uploadsMadeForSearchesAreLeftOut() {
        assertTrue(HolidayMixMaker.isFiller(christmas, song("a", "Christmas Classics", "Christmas Songs"), true))
        assertTrue(HolidayMixMaker.isFiller(christmas, song("b", "Silent Night", "Jazzy Christmas"), true))
        assertTrue(HolidayMixMaker.isFiller(christmas, song("c", "Lofi Christmas Study Beats", "Someone"), true))
        assertTrue(HolidayMixMaker.isFiller(christmas, song("d", "Cinematic Rock | Viral Rock Music, Best Rock Songs", "Band"), true))
        // A title of nothing but the search's own words, from a search.
        assertTrue(HolidayMixMaker.isFiller(christmas, song("e", "Christmas Classics", "Someone"), false))
        assertFalse(HolidayMixMaker.isFiller(christmas, song("f", "White Christmas", "Bing Crosby"), false))
        // A real "Holiday" in a name is no genre: Billie Holiday.
        assertFalse(HolidayMixMaker.isFiller(HolidayMixes.byId("season:fall_jazz")!!, song("g", "Autumn In New York", "Billie Holiday"), true))
    }

    @Test fun playlistsMustBeAboutTheSeed() {
        val kids = HolidayMixes.byId("season:kids_christmas")!!
        val kidsSeed = kids.seeds.first { it.playlist }
        assertFalse(HolidayMixMaker.listFits(kids, kidsSeed, "Christmas Hits"))
        assertTrue(HolidayMixMaker.listFits(kids, kidsSeed, "Family Christmas"))
        assertFalse(HolidayMixMaker.listFits(christmas, christmas.seeds.first { it.playlist }, "80s Pop Party"))
        assertTrue(HolidayMixMaker.listFits(christmas, christmas.seeds.first { it.playlist }, "Classical Christmas"))
        val hipHop = HolidayMixes.byId("season:hip_hop_christmas")!!
        assertTrue(HolidayMixMaker.listFits(hipHop, hipHop.seeds.first { it.playlist }, "Hip-Hop Christmas"))
        assertFalse(HolidayMixMaker.listFits(hipHop, hipHop.seeds.first { it.playlist }, "Y2K Pop Party"))
        // November's mixes take no Christmas playlist.
        assertFalse(HolidayMixMaker.listFits(thanksgiving, thanksgiving.seeds.first { it.playlist }, "Motown Christmas"))
    }

    @Test fun aPlaylistIsTakenOnceAndAFailedSeedDoesntSinkTheMix() = runBlocking {
        val asked = ArrayList<String>()
        val sources = object : HolidayMixMaker.Sources {
            override suspend fun songs(query: String, pages: Int): List<Song> {
                if (query == "christmas songs 1950s 1960s") error("YouTube said no")
                return (1..20).map { song("$query$it", "White Christmas $query $it", "Search $query $it") }
            }
            override suspend fun playlists(query: String) = listOf(
                PlaylistItem("PLsame", "Christmas Classics Forever", "YouTube Music", null),
                PlaylistItem("PLother$query", "Classic Christmas $query", "YouTube Music", null),
            )
            override suspend fun playlistSongs(id: String): List<Song> {
                synchronized(asked) { asked += id }
                return (1..30).map { song("$id-$it", "Holly Jolly $id $it", "Singer $id $it") }
            }
        }
        val found = HolidayMixMaker(sources).find(christmas)
        assertEquals("each playlist read once", asked.size, asked.distinct().size)
        assertTrue(found.first { it.seed.query == "christmas songs 1950s 1960s" }.songs.isEmpty())
        val mix = HolidayMixMaker.assemble(christmas, found, "2026-W50")
        assertTrue(mix.size >= 40)
    }

    // ------------------------------------------------------------------------------------------------ Home

    private val layout = listOf(
        HomeSectionConfig(HomeSections.QUICK_PICKS, "Quick picks"),
        HomeSectionConfig(HomeSections.SEASONAL, "Holiday music"),
        HomeSectionConfig(HomeSections.pinKey(HomeSections.HOLIDAY_PIN, "new_years_eve"), "New Year's Eve Party"),
        HomeSectionConfig(HomeSections.pinKey(HomeSections.HOLIDAY_PIN, "autumn_lofi"), "Autumn Lo-fi"),
        HomeSectionConfig(HomeSections.MADE_FOR_YOU, "Made for you"),
    )

    @Test fun theHolidaySectionComesAndGoesWithTheDate() {
        fun keys(date: LocalDate) = HomeSections.inSeason(layout, date).map { it.key }
        assertEquals(listOf(HomeSections.QUICK_PICKS, HomeSections.MADE_FOR_YOU), keys(d(10, 31)))
        assertEquals(
            listOf(HomeSections.QUICK_PICKS, HomeSections.SEASONAL, HomeSections.pinKey(HomeSections.HOLIDAY_PIN, "autumn_lofi"), HomeSections.MADE_FOR_YOU),
            keys(d(11, 1)),
        )
        assertEquals(listOf(HomeSections.QUICK_PICKS, HomeSections.SEASONAL, HomeSections.MADE_FOR_YOU), keys(d(12, 10)))
        assertEquals(
            listOf(HomeSections.QUICK_PICKS, HomeSections.SEASONAL, HomeSections.pinKey(HomeSections.HOLIDAY_PIN, "new_years_eve"), HomeSections.MADE_FOR_YOU),
            keys(d(12, 27)),
        )
        assertEquals(listOf(HomeSections.QUICK_PICKS, HomeSections.MADE_FOR_YOU), keys(d(1, 2, 2027)))
    }

    @Test fun homeHasTheSectionAfterQuickPicks() {
        val fresh = HomeSections.initial.map { it.key }
        assertEquals(fresh.indexOf(HomeSections.QUICK_PICKS) + 1, fresh.indexOf(HomeSections.SEASONAL))
        assertTrue(HomeSections.defaults.any { it.key == HomeSections.SEASONAL })
        // A layout from before 1.8.0 gets it once, after Quick picks.
        val old = listOf(HomeSectionConfig(HomeSections.REPLAY, "Replay"), HomeSectionConfig(HomeSections.QUICK_PICKS, "Quick picks"), HomeSectionConfig("x", "X"))
        val added = HomeSections.withNewSections(old, listOf(HomeSectionConfig(HomeSections.SEASONAL, "Holiday music")))
        assertEquals(listOf(HomeSections.REPLAY, HomeSections.QUICK_PICKS, HomeSections.SEASONAL, "x"), added.map { it.key })
        assertEquals("Holiday music", HomeSections.inYourLanguage(HomeSectionConfig(HomeSections.SEASONAL, "x")).title)
    }
}
