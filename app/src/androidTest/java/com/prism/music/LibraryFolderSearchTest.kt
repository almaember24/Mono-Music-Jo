package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prism.music.data.model.PlaylistItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Library's Playlists tab: a folder can be made before anything's in it (the dialog was there, with no button to open it),
 * and a long library can be searched (#357) and slid through with a finger-sized thumb (#376). Signed out, then with a
 * library as an account has one (test items, no network).
 */
@RunWith(AndroidJUnit4::class)
class LibraryFolderSearchTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()
    private val c get() = Fixture.container

    private fun openLibrary() {
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Library") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Library") and hasClickAction()).onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Songs")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun scrollTo(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty() }
        repeat(8) {
            if (compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()) return
            compose.onRoot().performTouchInput { swipeUp(startY = centerY + height * 0.2f, endY = centerY - height * 0.2f) }
            compose.waitForIdle()
        }
    }

    @Test fun aNewFolderShowsAndOpensEmptySignedOut() {
        Fixture.ready()
        Fixture.launch()
        openLibrary()
        scrollTo("New folder")
        Fixture.screenshot("library-new-folder")
        compose.clickWhenFound(hasText("New folder") and hasClickAction())
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Test folder")
        compose.clickWhenFound(hasText("Save") and hasClickAction())
        scrollTo("Test folder")
        compose.waitForText("Test folder")
        assertEquals(1, c.prismLibrary.folders.value.size)
        compose.clickWhenFound(hasText("Test folder") and hasClickAction())
        compose.waitForText("Nothing in here yet")
    }

    @Test fun aFolderCanBeMadeBesideAnAccountsPlaylists() {
        Fixture.ready()
        c.library.playlists.value = listOf(PlaylistItem("VLPLtest1", "Test mix", "Playlist • Test", null), PlaylistItem("VLPLtest2", "Test road", "Playlist • Test", null))
        Fixture.launch()
        openLibrary()
        scrollTo("New folder")
        compose.clickWhenFound(hasText("New folder") and hasClickAction())
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Test folder")
        compose.clickWhenFound(hasText("Save") and hasClickAction())
        scrollTo("Test folder")
        compose.waitForText("Test folder")
    }

    @Test fun aLongLibraryCanBeSearchedAndSlidThrough() {
        Fixture.ready()
        val folder = c.prismLibrary.createFolder("Test gym folder")
        c.library.playlists.value = (1..100).map { i -> PlaylistItem("VLPLtest$i", "Test list $i", "Playlist • Test", null) } + PlaylistItem("VLPLroad", "Road trip", "Playlist • Test", null)
        c.prismLibrary.moveToFolder("VLPLtest7", folder)
        Fixture.launch()
        openLibrary()
        compose.waitForText("Search playlists")
        Fixture.screenshot("library-search")
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("road")
        compose.waitForText("Road trip")
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Test list 2")).fetchSemanticsNodes().isEmpty() }
        // Nothing else on the tab while searching, and a playlist that's in a folder is found too.
        assertTrue(compose.onAllNodes(hasText("New folder")).fetchSemanticsNodes().isEmpty())
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextClearance()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("test list 7")
        compose.waitForText("Test list 7")
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextClearance()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("gym")
        compose.waitForText("Test gym folder")
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextClearance()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("zzzz")
        compose.waitForText("No matches")
        // Cleared with its own button, which puts the keyboard away too.
        compose.clickWhenFound(hasContentDescription("Clear search"))

        // The thumb, once the list moves; held, its bubble says where.
        scrollTo("Test list 3")
        compose.onRoot().performTouchInput { swipeUp(startY = centerY + height * 0.2f, endY = centerY - height * 0.2f) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Scroll through the list")).fetchSemanticsNodes().isNotEmpty() }
        val thumb = compose.onAllNodes(hasContentDescription("Scroll through the list")).onFirst()
        thumb.performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, 300f)); moveBy(androidx.compose.ui.geometry.Offset(0f, 300f)) }
        compose.waitForIdle()
        Fixture.screenshot("library-scroller")
        thumb.performTouchInput { up() }
        compose.waitForIdle()
        compose.onAllNodes(hasContentDescription("Scroll through the list")).onFirst().performTouchInput { swipeDown(startY = centerY, endY = centerY + 900f) }
        // A long way down in one move, far past where a swipe of the list would land.
        compose.waitUntil(5_000) { (50..100).any { compose.onAllNodes(hasText("Test list $it")).fetchSemanticsNodes().isNotEmpty() } }
    }
}
