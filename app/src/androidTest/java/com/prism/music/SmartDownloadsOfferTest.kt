package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #339 (1.8.1.1): Smart Downloads is off on a new install, and Home offers it once: Not now keeps it off and puts the
 * card away for good, with the answer kept.
 */
@RunWith(AndroidJUnit4::class)
class SmartDownloadsOfferTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val title = "Download songs before you need them?"

    @Test fun homeOffersItOnceAndNotNowKeepsItOff() {
        Fixture.ready()
        val c = Fixture.container
        assumeFalse("an answer is already kept on this copy", runBlocking { c.settings.smartDownloadsChosen() })
        assertFalse(c.settings.current.smartDownloads)
        Fixture.launch()
        compose.waitForText(title, 20_000)
        compose.onAllNodes(hasText("Not now") and hasClickAction()).onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(title)).fetchSemanticsNodes().isEmpty() }
        assertFalse(c.settings.current.smartDownloads)
        compose.waitUntil(5_000) { runBlocking { c.settings.smartDownloadsChosen() } }
        assertTrue(runBlocking { c.settings.smartDownloadsChosen() })
    }
}
