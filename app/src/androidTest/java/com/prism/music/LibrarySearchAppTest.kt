package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Searching your library (1.8.3), in the app: Library's search button opens it; a song in a playlist is found, the
 * Playlists kind finds the playlist, Liked leaves nothing (none are liked), and a kana title is found by typing it in
 * Latin letters (#363).
 */
@RunWith(AndroidJUnit4::class)
class LibrarySearchAppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val c get() = Fixture.container
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @After fun cleanUp() { runCatching { instrumentation.runOnMainSync { c.player.stop() } } }

    private fun has(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun type(text: String) {
        val field = compose.onAllNodes(hasTestTag("librarySearchField"), useUnmergedTree = true).onFirst()
        compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).onFirst().performTextClearance()
        compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).onFirst().performTextInput(text)
        field.assertExists()
    }

    @Test fun findsSongsPlaylistsAndKanaByTheirSpelling() {
        Fixture.ready()
        val songs = Fixture.songs(3) + Song("libsearch-kana", "とりのうた", listOf(ArtistRef("Lia")), durationSec = 240)
        Fixture.prismPlaylist("Morning drive", songs)
        Fixture.launch()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Library") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Library") and hasClickAction()).onFirst().performClick()
        compose.clickWhenFound(hasTestTag("librarySearch"), 10_000)
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("libraryResults")).fetchSemanticsNodes().isNotEmpty() }

        type("test song 2")
        compose.waitUntil(10_000) { has("Test song 2") && !has("Test song 3") }
        Fixture.screenshot("library-search")

        type("morning")
        compose.clickWhenFound(hasTestTag("kind:PLAYLIST"))
        compose.waitUntil(10_000) { has("Morning drive") }

        compose.clickWhenFound(hasTestTag("kind:ALL"))
        type("test song")
        compose.clickWhenFound(hasTestTag("flag:liked"))
        compose.waitUntil(10_000) { has("Nothing in your library matches") }
        compose.clickWhenFound(hasTestTag("flag:liked"))

        // Spelled in the background: kana by Prism's own engine.
        type("tori no uta")
        compose.waitUntil(20_000) { has("とりのうた") }
        Fixture.screenshot("library-search-kana")
    }
}
