package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Updates from Settings (#201, 1.7.9): "Check for updates" finds a newer Prism and offers it right there, so it installs
 * without Home (the Home tab can be turned off). GitHub's answer is the test's own: a made-up Prism 9.9.9.
 */
@RunWith(AndroidJUnit4::class)
class UpdatesTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    @Before fun start() {
        RecordedNetwork.answers["https://api.github.com/repos/Yigder/Prism/releases/latest"] =
            """{"tag_name":"v9.9.9","html_url":"https://github.com/Yigder/Prism/releases/tag/v9.9.9",
               "assets":[{"name":"Prism.apk","browser_download_url":"https://github.com/Yigder/Prism/releases/download/v9.9.9/Prism.apk"}]}"""
        Fixture.ready()
        Fixture.launch()
    }

    @Test fun checkForUpdatesOffersTheUpdateInSettings() {
        compose.openSettings()
        compose.onNode(hasSetTextAction()).performTextInput("check for updates")
        compose.clickWhenFound(hasText("Check for updates") and hasClickAction())
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        // Its page: tap it, and the update shows under it, ready to install (not tapped: that would leave for Android's
        // "Install unknown apps" page).
        compose.clickWhenFound(hasText("Check for updates") and hasClickAction())
        compose.waitForText("Prism 9.9.9 is out")
        compose.waitForText("Prism 9.9.9 is available")
        compose.onAllNodes(hasText("Tap to update") and hasClickAction(), useUnmergedTree = false).fetchSemanticsNodes().let {
            org.junit.Assert.assertTrue("the update can be tapped to install", it.isNotEmpty())
        }
    }
}
