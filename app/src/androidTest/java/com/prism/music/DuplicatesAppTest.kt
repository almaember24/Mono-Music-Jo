package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The duplicate finder (1.8.3), in the app: a playlist with a song and its video; ⋯ → Find duplicates lists the video
 * as the extra copy, and Remove (asked first) takes it out, keeping the song.
 */
@RunWith(AndroidJUnit4::class)
class DuplicatesAppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val c get() = Fixture.container
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var listId: String? = null

    @After fun cleanUp() {
        runCatching { instrumentation.runOnMainSync { c.player.stop() } }
        listId?.let { c.prismLibrary.delete(it) }
    }

    private fun has(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test fun theVideoCopyIsFoundAndTakenOut() {
        Fixture.ready()
        val song = Song("dupe-song", "Dupe Anthem", listOf(ArtistRef("Test Artist")), durationSec = 200)
        val video = Song("dupe-video", "Test Artist - Dupe Anthem (Official Video)", listOf(ArtistRef("Test Artist")), durationSec = 201, isVideo = true)
        val other = Song("dupe-other", "Another One", listOf(ArtistRef("Test Artist")), durationSec = 180)
        val id = Fixture.prismPlaylist("Twice over", listOf(video, other, song)).also { listId = it }
        Fixture.launch()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Library") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Library") and hasClickAction()).onFirst().performClick()
        for (i in 0 until 8) {
            if (compose.onAllNodes(hasText("Playlists") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()) break
            compose.onRoot().performTouchInput { swipeRight(startX = width * 0.15f, endX = width * 0.95f) }
            compose.waitForIdle()
        }
        compose.onAllNodes(hasText("Playlists") and hasClickAction()).onFirst().performClick()
        for (i in 0 until 6) {
            if (compose.onAllNodes(hasText("Twice over") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()) break
            compose.onRoot().performTouchInput { swipeUp(startY = height * 0.7f, endY = height * 0.35f) }
            compose.waitForIdle()
        }
        compose.clickWhenFound(hasText("Twice over") and hasClickAction(), 10_000)
        compose.waitForText("Dupe Anthem")

        // The page's own ⋯ is the topmost (each song row has one too).
        val more = compose.onAllNodes(hasContentDescription("More") and hasClickAction())
        val top = more.fetchSemanticsNodes().withIndex().minBy { it.value.boundsInRoot.top }.index
        more[top].performClick()
        compose.clickWhenFound(hasText("Find duplicates") and hasClickAction())
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("duplicates")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { has("1 song is here more than once") && has("Keeping “Dupe Anthem”") }
        Fixture.screenshot("duplicates")
        compose.clickWhenFound(hasTestTag("removeDuplicates"))
        compose.waitUntil(5_000) { has("This takes 1 song out of this playlist.") }
        compose.clickWhenFound(hasTestTag("confirmRemoveDuplicates"))
        compose.waitUntil(10_000) { c.prismLibrary.list(id)!!.songs.size == 2 }
        assertEquals(listOf("dupe-other", "dupe-song"), c.prismLibrary.list(id)!!.songs.map { it.id })
    }
}
