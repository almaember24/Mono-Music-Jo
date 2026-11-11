package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.innertube.Explore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explore against the real YouTube Music, signed out: the charts' menu and shelves, in the app. Needs the internet, so it's
 * left out of normal runs ([Live]). It looks at what's on the page by what the app parsed, not by shelf names: those change
 * with the language and the week.
 */
@Live
@RunWith(AndroidJUnit4::class)
class ExploreLiveInAppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    @Test fun exploreShowsWhatYouTubeSends() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cmd media_session volume --stream 3 --set 0").close()
        Fixture.ready()
        val c = Fixture.container
        val charts = runBlocking { c.ytm.charts() }
        val rest = runBlocking { c.ytm.explore() }
        assertTrue("${charts.countries.size} countries", charts.countries.size > 20)
        assertTrue("no chart shelves", charts.shelves.isNotEmpty())
        assertTrue("no shelves on Explore", rest.isNotEmpty())

        Fixture.launch()
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("Search") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Search") and hasClickAction()).onFirst().performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().indices.forEach { i ->
                runCatching { compose.onAllNodes(hasScrollToNodeAction())[i].performScrollToNode(hasText("Explore")) }
            }
            compose.onAllNodes(hasText("Explore") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.clickWhenFound(hasText("Explore") and hasClickAction())
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("explore")).fetchSemanticsNodes().isNotEmpty() }
        // The first shelf of the charts, and the country the menu says these are for.
        compose.waitForText(charts.shelves.first().title, 30_000)
        charts.selected?.let { compose.waitForText(it.name, 30_000) }
        Fixture.screenshot("explore-live")
        assertTrue(rest.none(Explore::isMoods) || rest.first(Explore::isMoods).items.isNotEmpty())
    }
}
