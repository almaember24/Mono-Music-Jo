package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** #224 (1.7.9.2): Search's chips in your order, and Search opens on your first one ("On this phone" here). */
@RunWith(AndroidJUnit4::class)
class SearchChipsAppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    @After fun cleanUp() { runBlocking { Fixture.container.settings.setSearchChips(emptyList()) } }

    private fun left(text: String) = compose.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().first().boundsInRoot.left

    @Test fun searchOpensOnYourFirstChip() {
        Fixture.ready()
        runBlocking { Fixture.container.settings.setSearchChips(listOf("PHONE", "SONGS")) }
        Fixture.launch()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(tr(R.string.search)) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText(tr(R.string.search)) and hasClickAction()).onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("get lucky")
        compose.onNode(hasSetTextAction()).performImeAction()
        val phone = tr(R.string.on_this_phone)
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(phone) and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("On this phone comes first", left(phone) < left(tr(R.string.songs)))
        assertTrue("Songs before Top results", left(tr(R.string.songs)) < left(tr(R.string.top_results)))
    }
}
