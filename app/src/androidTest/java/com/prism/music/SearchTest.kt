package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Search to playing (recorded answers: assets/recorded/): a search shows the song, and tapping it makes it the song
 * playing, in the player. The song's audio isn't recorded, so this checks what's playing, not the sound.
 */
@RunWith(AndroidJUnit4::class)
class SearchTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    @Before fun start() {
        Fixture.ready()
        Fixture.launch()
    }

    @Test fun aSearchFindsTheSongAndPlaysIt() {
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Search") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Search") and hasClickAction()).onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("get lucky daft punk")
        // Enter puts the keyboard away: the results instead of suggestions.
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForText("Get Lucky", 20_000)
        compose.onAllNodes(hasText("Get Lucky", substring = true) and hasClickAction()).onFirst().performClick()
        compose.waitUntil(10_000) { Fixture.container.player.currentSong.value != null }
        val song = Fixture.container.player.currentSong.value!!
        assertTrue("playing ${song.title}", song.title.contains("Get Lucky", ignoreCase = true))
        assertTrue("by ${song.artistText}", song.artistText.contains("Daft Punk", ignoreCase = true))
        assertEquals("not as a video", false, song.isVideo)
    }
}
