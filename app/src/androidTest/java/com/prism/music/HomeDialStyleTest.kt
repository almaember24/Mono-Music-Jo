package com.prism.music

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prism.music.data.prefs.SectionStyle
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reddit, 1.7.9.1: a Home section can be shown as YouTube Music's 3 × 3 pages. Home (recorded answers) with its
 * sections set to 3 × 3 shows them as those pages (Quick picks among them).
 */
@RunWith(AndroidJUnit4::class)
class HomeDialStyleTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    @Test fun aSectionCanBeAThreeByThreeGrid() {
        Fixture.ready()
        Fixture.launch()
        compose.waitForText("Quick picks", 20_000)
        val s = Fixture.container.settings
        runBlocking { s.setHomeLayout(s.current.homeLayout.map { it.copy(style = SectionStyle.DIAL) }) }
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("dialPages")).fetchSemanticsNodes().isNotEmpty() }
    }
}
