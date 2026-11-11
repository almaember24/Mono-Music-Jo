package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.SongsFrom
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Library → Songs → From (1.8.3, #382), in the app: a playlist ticked instead of liked songs lists its songs in the
 * tab; the choice is kept; nothing is liked.
 */
@RunWith(AndroidJUnit4::class)
class SongsFromAppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val c get() = Fixture.container
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @After fun cleanUp() {
        runCatching { instrumentation.runOnMainSync { c.player.stop() } }
        c.library.setSort(SongsFrom.KEY, null)
    }

    private fun has(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    private fun hasTag(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test fun aPickedPlaylistsSongsShowUnderSongs() {
        Fixture.ready()
        c.library.setSort(SongsFrom.KEY, null)
        val songs = Fixture.songs(3)
        val id = Fixture.prismPlaylist("Road trip", songs)
        Fixture.launch()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Library") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Library") and hasClickAction()).onFirst().performClick()
        // The tab chips scroll: back to the start, where Songs is.
        for (i in 0 until 8) {
            if (compose.onAllNodes(hasText("Songs") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()) break
            compose.onRoot().performTouchInput { swipeRight(startX = width * 0.15f, endX = width * 0.95f) }
            compose.waitForIdle()
        }
        compose.onAllNodes(hasText("Songs") and hasClickAction()).onFirst().performClick()
        compose.waitUntil(10_000) { hasTag("songsFrom") }
        compose.onAllNodes(hasTestTag("songsFrom")).onFirst().performClick()
        compose.waitUntil(5_000) { hasTag("songsFromSheet") }
        compose.clickWhenFound(hasTestTag("from:liked"))
        compose.clickWhenFound(hasTestTag("from:$id"))
        compose.waitForIdle()
        Thread.sleep(500)
        Fixture.screenshot("songs-from-sheet")
        compose.clickWhenFound(hasTestTag("fromDone"))

        compose.waitUntil(10_000) { has("Test song 1") && has("Test song 3") }
        compose.waitUntil(5_000) { has("1 playlist") && has("Original order") }
        Fixture.screenshot("songs-from")
        assertEquals(SongsFrom(false, listOf(id)), SongsFrom.decode(c.library.sortOf(SongsFrom.KEY)))
        assertEquals("nothing was liked", 0, c.library.likedShown.value.count { s -> songs.any { it.id == s.id } })
    }
}
