package com.prism.music

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Looks (1.7.3): picking one changes everything it holds at once, Undo puts the listener's own back, and saving keeps it. */
@RunWith(AndroidJUnit4::class)
class LooksTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    @Before fun start() {
        // A November day (1.8.0's seasonal Looks): Harvest is offered, Holiday isn't.
        val zone = java.time.ZoneId.systemDefault()
        com.prism.music.data.looks.Seasons.clock = java.time.Clock.fixed(java.time.LocalDate.of(2026, 11, 20).atTime(12, 0).atZone(zone).toInstant(), zone)
        Fixture.ready()
        Fixture.launch()
    }

    @org.junit.After fun end() { com.prism.music.data.looks.Seasons.clock = null }

    private fun openLooks() {
        compose.openSettings()
        compose.onAllNodes(hasText("Customize Prism") and hasClickAction()).onFirst().performClick()
        compose.waitForText("Looks")
        compose.onAllNodes(hasText("Looks") and hasClickAction()).onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(clickLabel("Use Midnight")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun aLookChangesEverythingAndUndoPutsItBack() {
        openLooks()
        val looks = Fixture.container.looks
        val settings = Fixture.container.settings
        assertEquals(false, settings.current.pureBlack)
        val before = Fixture.container.settings.lookValues.value
        android.util.Log.i("PrismTest", "fresh install: Look ${looks.currentId.value}, look values $before")
        compose.clickWhenFound(clickLabel("Use Midnight"))
        compose.waitUntil(10_000) { looks.currentId.value == "midnight" }
        assertEquals("Midnight's pure black", true, settings.current.pureBlack)
        assertEquals("Midnight's theme", com.prism.music.data.prefs.ThemeMode.DARK, settings.current.themeMode)
        // The bar under the page: "Undo" puts back exactly the look the listener had.
        compose.waitForText("Undo")
        compose.onAllNodes(hasText("Undo") and hasClickAction()).onFirst().performClick()
        compose.waitUntil(10_000) { settings.lookValues.value == before }
        assertEquals(false, settings.current.pureBlack)
    }

    /** 1.8.0: in November the Looks page offers Harvest (card and grid); picking it puts on its colors and background. */
    @Test fun harvestInNovember() {
        // The date is November 20 ([start]).
        openLooks()
        compose.waitForText("For the season: Harvest")
        assertEquals("Holiday isn't offered in November", 0, compose.onAllNodes(clickLabel("Use Holiday")).fetchSemanticsNodes().size)
        compose.clickWhenFound(clickLabel("Use Harvest"))
        val looks = Fixture.container.looks
        compose.waitUntil(10_000) { looks.currentId.value == "harvest" }
        val values = Fixture.container.settings.lookValues.value
        assertEquals("s:HARVEST", values["spectrum_preset"])
        assertEquals(true, values["backdrops"]?.contains("HARVEST"))
    }

    @Test fun aSavedLookComesBackAfterAnother() {
        openLooks()
        val looks = Fixture.container.looks
        runBlocking {
            Fixture.container.settings.editNow { it[androidx.datastore.preferences.core.booleanPreferencesKey("pure_black")] = true }
            // Saving reads the look as Prism last saw it: wait until that has the change.
            kotlinx.coroutines.withTimeout(5_000) { while ("pure_black" !in Fixture.container.settings.lookValues.value) kotlinx.coroutines.delay(20) }
            looks.saveCurrent("Test look", id = "my-test")
        }
        compose.clickWhenFound(clickLabel("Use Paper"))
        compose.waitUntil(10_000) { looks.currentId.value == "paper" }
        assertEquals(false, Fixture.container.settings.current.pureBlack)
        compose.clickWhenFound(clickLabel("Use Test look"))
        compose.waitUntil(10_000) { looks.currentId.value == "my-test" }
        assertEquals(true, Fixture.container.settings.current.pureBlack)
    }
}

/**
 * Taps the first node matching [matcher], scrolling the page (and rows of cards) to it first: lists only build what's on
 * screen, so a node further down isn't there until it's scrolled to.
 */
fun androidx.compose.ui.test.junit4.ComposeTestRule.clickWhenFound(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) {
    val end = System.currentTimeMillis() + timeoutMs
    while (onAllNodes(matcher).fetchSemanticsNodes().isEmpty()) {
        if (System.currentTimeMillis() > end) {
            // What the screen did show, so a failure says where the test was.
            val shown = onAllNodes(androidx.compose.ui.test.hasText("", substring = true)).fetchSemanticsNodes()
                .mapNotNull { it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.joinToString() }
            throw AssertionError("Not found: ${matcher.description}. On screen: ${shown.joinToString(" | ").take(1200)}")
        }
        // Every scrollable on the page, outer first: the page, then each row.
        onAllNodes(androidx.compose.ui.test.hasScrollToNodeAction()).fetchSemanticsNodes().indices.forEach { i ->
            runCatching { onAllNodes(androidx.compose.ui.test.hasScrollToNodeAction())[i].performScrollToNode(matcher) }
        }
        waitForIdle()
    }
    // Its click itself, not a touch at its middle: the floating tab bar can cover a card's lower half.
    val node = onAllNodes(matcher).onFirst()
    runCatching { node.performScrollTo() } // not in a list (a top bar): already where it is
    node.performSemanticsAction(SemanticsActions.OnClick)
}

/** A node whose tap is labelled [label] (what TalkBack says: "Use Midnight"). */
fun clickLabel(label: String) = SemanticsMatcher("click label '$label'") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }
