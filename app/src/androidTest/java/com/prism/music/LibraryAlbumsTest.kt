package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prism.music.data.model.AlbumItem
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reddit, 1.7.9.1: YouTube's library albums fill up with singles. Library → Albums can show albums only, or singles
 * and EPs only (by the kind YouTube gives first in a release's subtitle).
 */
@RunWith(AndroidJUnit4::class)
class LibraryAlbumsTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    @Test fun albumsAndSinglesCanBeShownApart() {
        Fixture.ready()
        Fixture.launch()
        Fixture.container.library.albums.value = listOf(
            AlbumItem("MPREb_test_album", "Test Long Player", "Album • Test Artist • 2024", null),
            AlbumItem("MPREb_test_single", "Test One Song", "Single • Test Artist • 2025", null),
            AlbumItem("MPREb_test_ep", "Test Short Player", "EP • Test Artist • 2023", null),
        )
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Library") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Library") and hasClickAction()).onFirst().performClick()
        compose.clickWhenFound(hasText("Albums") and hasClickAction(), 15_000)
        compose.waitForText("Test Long Player")
        compose.waitForText("Test One Song")

        compose.clickWhenFound(hasText("Singles & EPs") and hasClickAction())
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Test Long Player")).fetchSemanticsNodes().isEmpty() }
        compose.waitForText("Test One Song")
        compose.waitForText("Test Short Player")

        // "Albums": the chip, not the tab of the same name (the chips come after the tabs).
        compose.onAllNodes(hasText("Albums") and hasClickAction()).fetchSemanticsNodes().let { assertTrue("an Albums chip", it.size >= 2) }
        compose.onAllNodes(hasText("Albums") and hasClickAction())[1].performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Test One Song")).fetchSemanticsNodes().isEmpty() }
        compose.waitForText("Test Long Player")
    }
}
