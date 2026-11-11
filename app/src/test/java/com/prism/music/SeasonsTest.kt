package com.prism.music

import com.prism.music.data.looks.BuiltInLooks
import com.prism.music.data.looks.Season
import com.prism.music.data.looks.SeasonPrompt
import com.prism.music.data.looks.Seasons
import com.prism.music.ui.theme.Backdrop
import com.prism.music.ui.theme.SpectrumPresets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The seasonal Looks (1.8.0): which days are in season, in the phone's own time zone, and the once-a-season question. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class SeasonsTest {
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    @After fun clock() { Seasons.clock = null }

    @Test fun harvestIsNovemberAndHolidayIsDecember() {
        assertNull(Seasons.current(d(2026, 10, 31)))
        assertEquals(Season.HARVEST, Seasons.current(d(2026, 11, 1)))
        assertEquals(Season.HARVEST, Seasons.current(d(2026, 11, 30)))
        assertEquals(Season.HOLIDAY, Seasons.current(d(2026, 12, 1)))
        assertEquals(Season.HOLIDAY, Seasons.current(d(2026, 12, 31)))
        assertNull(Seasons.current(d(2027, 1, 1)))
        assertNull(Seasons.current(d(2027, 7, 4)))
    }

    @Test fun theDayIsThePhonesOwn() {
        // 03:00 on Dec 1 in London is still Nov 30 in New York (22:00): Harvest there, Holiday in Tokyo.
        val at = Instant.parse("2026-12-01T03:00:00Z")
        assertEquals(Season.HARVEST, Seasons.current(Seasons.dateAt(at, ZoneId.of("America/New_York"))))
        assertEquals(Season.HOLIDAY, Seasons.current(Seasons.dateAt(at, ZoneId.of("Asia/Tokyo"))))
        // New Year's: 23:30 on Dec 31 in Los Angeles is Jan 1 everywhere east of it.
        val eve = Instant.parse("2027-01-01T07:30:00Z")
        assertEquals(Season.HOLIDAY, Seasons.current(Seasons.dateAt(eve, ZoneId.of("America/Los_Angeles"))))
        assertNull(Seasons.current(Seasons.dateAt(eve, ZoneId.of("Europe/Berlin"))))
        // The clock can be set (tests, the in-app test): today follows it.
        Seasons.clock = Clock.fixed(Instant.parse("2026-11-01T04:30:00Z"), ZoneId.of("America/Chicago"))
        assertEquals(d(2026, 10, 31), Seasons.today())
        assertNull(Seasons.current())
        Seasons.clock = Clock.fixed(Instant.parse("2026-11-01T06:00:00Z"), ZoneId.of("America/Chicago"))
        assertEquals(Season.HARVEST, Seasons.current())
    }

    @Test fun eachSeasonEndsOncePerYear() {
        val dec1 = Seasons.ended(d(2026, 12, 1)).associate { it.first to it.second }
        assertEquals("HARVEST:2026", dec1[Season.HARVEST])
        assertNull("Holiday is on", dec1[Season.HOLIDAY])
        assertEquals("HOLIDAY:2025", Seasons.ended(d(2026, 11, 15)).toMap()[Season.HOLIDAY])
        assertFalse(Season.HARVEST in Seasons.ended(d(2026, 11, 15)).map { it.first })
        val jan = Seasons.ended(d(2027, 1, 1)).associate { it.first to it.second }
        assertEquals("HARVEST:2026", jan[Season.HARVEST])
        assertEquals("HOLIDAY:2026", jan[Season.HOLIDAY])
        // Still the same turn months later, so it's seen to only once.
        assertEquals("HARVEST:2026", Seasons.ended(d(2027, 10, 31)).toMap()[Season.HARVEST])
    }

    @Test fun seasonalLooksLightsAndBackgroundsAreOnlyOfferedInTheirMonth() {
        val nov = d(2026, 11, 12); val dec = d(2026, 12, 12); val mar = d(2027, 3, 1)
        assertTrue(BuiltInLooks.shown(nov).any { it.id == "harvest" })
        assertFalse(BuiltInLooks.shown(nov).any { it.id == "holiday" })
        assertTrue(BuiltInLooks.shown(dec).any { it.id == "holiday" })
        assertFalse(BuiltInLooks.shown(mar).any { Seasons.of(it.id) != null })
        // Every other Look, always.
        assertEquals(BuiltInLooks.all.size - 2, BuiltInLooks.shown(mar).size)
        assertTrue(Backdrop.HARVEST.offeredOn(nov)); assertFalse(Backdrop.HARVEST.offeredOn(dec))
        assertTrue(Backdrop.HOLIDAY.offeredOn(dec)); assertFalse(Backdrop.HOLIDAY.offeredOn(mar))
        assertTrue(Backdrop.AURORA.offeredOn(mar))
        // Each season's Look uses its own light and background, which say whose they are.
        for (s in Season.entries) {
            val look = BuiltInLooks.of(s.lookId)!!
            assertEquals("s:${s.light}", look.values["spectrum_preset"])
            assertTrue(look.values.getValue("backdrops").contains(s.backdrop))
            assertEquals(s, Backdrop.entries.first { it.name == s.backdrop }.season)
            assertEquals(s, SpectrumPresets.preset(s.light)!!.season)
        }
    }

    @Test fun holidayModeIsAskedOncePerSeasonPerYear() {
        val nov = d(2026, 11, 3)
        // First open in November: asked. Once answered (either way), not again that year.
        assertEquals(Season.HARVEST, SeasonPrompt.due(nov, emptySet(), onboarded = true, currentLook = null))
        val asked = setOf(SeasonPrompt.key(Season.HARVEST, nov))
        assertNull(SeasonPrompt.due(d(2026, 11, 20), asked, true, null))
        // December is its own question.
        assertEquals(Season.HOLIDAY, SeasonPrompt.due(d(2026, 12, 2), asked, true, null))
        // Next November asks once more.
        assertEquals(Season.HARVEST, SeasonPrompt.due(d(2027, 11, 1), asked, true, null))
        // Not before Prism is set up, not out of season, not when already on the season's Look.
        assertNull(SeasonPrompt.due(nov, emptySet(), onboarded = false, currentLook = null))
        assertNull(SeasonPrompt.due(d(2026, 10, 31), emptySet(), true, null))
        assertNull(SeasonPrompt.due(nov, emptySet(), true, currentLook = "harvest"))
        assertEquals(Season.HARVEST, SeasonPrompt.due(nov, emptySet(), true, currentLook = "paper"))
    }

    @Test fun theAnswerIsKept() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val nov = d(2026, 11, 3)
        assertEquals(Season.HARVEST, SeasonPrompt.due(nov, SeasonPrompt.asked(app), true, null))
        SeasonPrompt.markAsked(app, Season.HARVEST, nov)
        assertNull(SeasonPrompt.due(nov, SeasonPrompt.asked(app), true, null))
        assertEquals(Season.HOLIDAY, SeasonPrompt.due(d(2026, 12, 1), SeasonPrompt.asked(app), true, null))
        SeasonPrompt.markAsked(app, Season.HOLIDAY, d(2026, 12, 1))
        assertEquals(setOf("HARVEST:2026", "HOLIDAY:2026"), SeasonPrompt.asked(app))
    }
}
