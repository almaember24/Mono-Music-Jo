package com.prism.music

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #242: a song played from Home's Quick picks wasn't marked there, as it is in a playlist. Home (recorded answers) →
 * tap a Quick pick → its row says "Now Playing" (the color and bars for the eye, the state for TalkBack).
 */
@RunWith(AndroidJUnit4::class)
class QuickPicksPlayingTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    @After fun cleanUp() {
        runCatching { InstrumentationRegistry.getInstrumentation().runOnMainSync { Fixture.container.player.stop() } }
    }

    @Test fun theQuickPickPlayingIsMarked() {
        Fixture.ready()
        Fixture.launch()
        compose.waitForText("Quick picks", 20_000)
        val pick = hasText("Choosin' Texas", substring = true) and hasClickAction()
        compose.clickWhenFound(pick, 15_000)
        // Played (its stream isn't in the recordings, so it doesn't sound): the row is marked while it's the current song.
        val marked = pick and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Now Playing")
        compose.waitUntil(10_000) { compose.onAllNodes(marked).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * #349: "Quick picks from: YouTube Music" shows YouTube's own, even with Prism's set there to take their place, and
     * tapping one starts a radio from it as Prism's do (with "Quick picks start a radio" on, as it starts).
     */
    @Test fun youTubesOwnPicksShowAndStartARadio() {
        Fixture.ready()
        val c = Fixture.container
        kotlinx.coroutines.runBlocking {
            c.settings.setQuickPicksSource(com.prism.music.data.prefs.QuickPicksSource.YOUTUBE)
            kotlinx.coroutines.withTimeout(5_000) { while (c.settings.current.behavior.quickPicksSource != com.prism.music.data.prefs.QuickPicksSource.YOUTUBE) kotlinx.coroutines.delay(20) }
        }
        Fixture.launch()
        compose.waitForText("Quick picks", 20_000)
        compose.clickWhenFound(hasText("Choosin' Texas", substring = true) and hasClickAction(), 15_000)
        compose.waitUntil(10_000) { c.queue.source.value.isSongRadio }
        org.junit.Assert.assertTrue(c.queue.source.value.title, "Choosin' Texas" in c.queue.source.value.title)
    }
}
