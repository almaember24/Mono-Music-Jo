package com.prism.music

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Settings search (#73): a word finds the setting, the result opens its page, and the setting there works. */
@RunWith(AndroidJUnit4::class)
class SettingsSearchTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    @Before fun start() {
        Fixture.ready()
        Fixture.launch()
    }

    /** 1.8.0: Crossfade is one choice (Off / Automatic / Fixed length); its old name still finds it. */
    @Test fun aSearchOpensTheSettingAndItWorks() {
        compose.openSettings()
        compose.onNode(hasSetTextAction()).performTextInput("crossfade songs")
        compose.clickWhenFound(androidx.compose.ui.test.hasText("Crossfade") and androidx.compose.ui.test.hasClickAction())
        // Its page: the search box is gone and the choice is there.
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        compose.clickWhenFound(androidx.compose.ui.test.hasText("Fixed length") and androidx.compose.ui.test.hasClickAction())
        compose.waitUntil(5_000) { Fixture.container.settings.current.let { it.mix && !it.mixAuto } }
        val s = Fixture.container.settings.current
        assertTrue("Crossfade on with a fixed length (mix=${s.mix}, mixAuto=${s.mixAuto})", s.mix && !s.mixAuto)
    }

    /** #209 (1.7.9): a slider described in words also shows its number. */
    @Test fun slidersShowTheirNumber() {
        compose.openSettings()
        compose.onNode(hasSetTextAction()).performTextInput("line spacing")
        compose.clickWhenFound(androidx.compose.ui.test.hasText("Line spacing") and androidx.compose.ui.test.hasClickAction())
        compose.waitForText("As usual · 100%")
    }

    @Test fun aWordWithNoSettingSaysSo() {
        compose.openSettings()
        compose.onNode(hasSetTextAction()).performTextInput("zzqx")
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Crossfade songs")).fetchSemanticsNodes().isEmpty() }
    }
}

/** Home's picture opens Settings (there's no Settings tab by default). */
/** [name]: the Settings button's name in the language Prism is in. */
fun androidx.compose.ui.test.junit4.ComposeTestRule.openSettings(name: String = "Settings") {
    waitUntil(15_000) { onAllNodesWithContentDescription(name).fetchSemanticsNodes().isNotEmpty() }
    onAllNodesWithContentDescription(name).onFirst().performClick()
    waitUntil(10_000) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
}

fun androidx.compose.ui.test.junit4.ComposeTestRule.waitForText(text: String, timeoutMs: Long = 10_000) =
    waitUntil(timeoutMs) { onAllNodes(androidx.compose.ui.test.hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
