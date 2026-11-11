package com.prism.music

import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prism.music.data.looks.Season
import com.prism.music.data.looks.SeasonPrompt
import com.prism.music.data.looks.Seasons
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * Holiday Mode (1.8.0): the first time Prism opens in November it asks once; No thanks is kept (no question on the next
 * launch), and Turn on puts Harvest on. The date is set through [Seasons.clock].
 */
@RunWith(AndroidJUnit4::class)
class HolidayModeTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val november = LocalDate.of(2026, 11, 12)

    @Before fun start() {
        val zone = ZoneId.systemDefault()
        Seasons.clock = Clock.fixed(november.atTime(12, 0).atZone(zone).toInstant(), zone)
        Fixture.ready()
        // Not asked yet this season.
        Fixture.context.getSharedPreferences("prompts", Context.MODE_PRIVATE).edit().remove("season_asked").commit()
    }

    @After fun end() { Seasons.clock = null }

    private fun dialogShown() = compose.onAllNodes(hasText("Holiday Mode")).fetchSemanticsNodes().isNotEmpty()

    @Test fun noThanksIsKept() {
        val first = Fixture.launch()
        compose.waitUntil(20_000) { dialogShown() }
        compose.onAllNodes(hasText("No thanks") and hasClickAction()).onFirst().performClick()
        compose.waitUntil(10_000) { !dialogShown() }
        assertTrue(SeasonPrompt.key(Season.HARVEST, november) in SeasonPrompt.asked(Fixture.context))
        assertNotEquals("harvest", Fixture.container.looks.currentId.value)
        first.close()

        Fixture.launch()
        // Home, then longer than the question takes to come up: it doesn't.
        compose.waitUntil(15_000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Settings")).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(4_000)
        compose.waitForIdle()
        assertTrue("asked again after No thanks", !dialogShown())
    }

    @Test fun turnOnPutsTheSeasonsLookOn() {
        Fixture.launch()
        compose.waitUntil(20_000) { dialogShown() }
        // The dialog's own: Home's Smart Downloads card (1.8.1.1) has a "Turn on" too.
        compose.onAllNodes(hasText("Turn on") and hasClickAction() and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.isDialog())).onFirst().performClick()
        compose.waitUntil(10_000) { Fixture.container.looks.currentId.value == "harvest" }
        assertEquals("s:HARVEST", Fixture.container.settings.lookValues.value["spectrum_preset"])
        assertTrue(SeasonPrompt.key(Season.HARVEST, november) in SeasonPrompt.asked(Fixture.context))
    }
}
