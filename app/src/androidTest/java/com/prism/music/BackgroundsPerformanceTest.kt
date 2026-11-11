package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reddit (1.8.0): "I can't use my own wallpapers anymore. Only a black background is displayed now." With Performance
 * mode on every screen is drawn on the plain background, whichever is picked; the Backgrounds page now says so, as the
 * look pages in Settings do, instead of showing the pick as if it were in use.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundsPerformanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val c get() = Fixture.container

    @After fun off() { c.settings.setPerformanceMode(false) }

    @Test fun theBackgroundsPageSaysPerformanceModeKeepsThemPlain() {
        Fixture.ready()
        c.settings.setPerformanceMode(true)
        Fixture.launch()
        compose.openSettings()
        compose.clickWhenFound(hasText("Customize Prism") and hasClickAction())
        compose.clickWhenFound(hasText("Glass & backgrounds") and hasClickAction())
        compose.clickWhenFound(hasText("Screen backgrounds") and hasClickAction())
        compose.waitForText("Same on every screen")
        assertTrue(
            "the Backgrounds page names Performance mode",
            compose.onAllNodes(hasText("Performance mode is on")).fetchSemanticsNodes().isNotEmpty(),
        )
    }
}
