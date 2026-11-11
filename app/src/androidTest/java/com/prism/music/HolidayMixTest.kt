package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 1.8.0's holiday mixes: on a December day Home has its "Holiday music" section, and a mix opens from it as a page of
 * its songs with Play and Shuffle. The mix is put in place beforehand (as if made earlier this week), so nothing is
 * looked up; the date is the test's own.
 */
@RunWith(AndroidJUnit4::class)
class HolidayMixTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    @After fun backToToday() { Fixture.container.seasonal.clock = null }

    @Test fun aMixOpensFromTheHolidaySection() {
        Fixture.ready()
        val zone = ZoneId.systemDefault()
        Fixture.container.seasonal.clock = Clock.fixed(LocalDateTime.of(2026, 12, 10, 12, 0).atZone(zone).toInstant(), zone)
        runBlocking { Fixture.container.seasonal.keep("season:christmas_classics", Fixture.songs(45, "Holiday song")) }
        Fixture.launch()

        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("holidaySection")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForText("Holiday music")
        compose.clickWhenFound(hasContentDescription("Christmas Classics") and hasClickAction(), 15_000)
        compose.waitForText("Holiday song 1", 15_000)
        assertTrue("Play", compose.onAllNodes(hasContentDescription("Play", substring = true)).fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodes(androidx.compose.ui.test.hasText("Play")).fetchSemanticsNodes().isNotEmpty())
        assertTrue("Shuffle", compose.onAllNodes(hasContentDescription("Shuffle", substring = true)).fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodes(androidx.compose.ui.test.hasText("Shuffle")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun outsideItsMonthTheSectionIsGone() {
        Fixture.ready()
        val zone = ZoneId.systemDefault()
        Fixture.container.seasonal.clock = Clock.fixed(LocalDateTime.of(2026, 10, 15, 12, 0).atZone(zone).toInstant(), zone)
        Fixture.launch()
        compose.waitForText("Quick picks", 20_000)
        assertTrue(compose.onAllNodes(hasTestTag("holidaySection")).fetchSemanticsNodes().isEmpty())
    }
}
