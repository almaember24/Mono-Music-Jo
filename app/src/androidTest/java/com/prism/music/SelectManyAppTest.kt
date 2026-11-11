package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Select many (1.8.3, G141 / #381), in the app: long-press a song in a playlist, its bar takes the tabs' place, All picks
 * a 500-song playlist without a wait, Back ends it; and picked songs come out of a Prism playlist together.
 */
@RunWith(AndroidJUnit4::class)
class SelectManyAppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val c get() = Fixture.container
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @After fun cleanUp() {
        runCatching { instrumentation.runOnMainSync { c.player.stop() } }
    }

    private fun openLibraryPlaylist(title: String, first: String) {
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Library") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Library") and hasClickAction()).onFirst().performClick()
        // Library keeps the page it was on: its first page, left of Songs, holds the playlists.
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Songs")).fetchSemanticsNodes().isNotEmpty() }
        repeat(6) {
            if (compose.onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty()) return@repeat
            compose.onRoot().performTouchInput { swipeRight(startX = width * 0.15f, endX = width * 0.95f) }
            compose.waitForIdle()
        }
        compose.clickWhenFound(hasText(title) and hasClickAction(), 15_000)
        compose.waitForText(first)
    }

    private fun bar() = compose.onAllNodes(hasTestTag("selectionBar")).fetchSemanticsNodes()

    @Test fun longPressPicksAllPicksTheWholePlaylistAndBackEndsIt() {
        Fixture.ready()
        Fixture.prismPlaylist("Five hundred", Fixture.songs(500))
        Fixture.launch()
        openLibraryPlaylist("Five hundred", "Test song 1")
        compose.onAllNodes(hasText("Test song 1")).onFirst().performTouchInput { longClick() }
        compose.waitUntil(5_000) { bar().isNotEmpty() }
        compose.waitForText("1 song picked")
        val started = System.nanoTime()
        compose.clickWhenFound(hasText("All") and hasClickAction())
        compose.waitForText("500 songs picked", 5_000)
        val ms = (System.nanoTime() - started) / 1_000_000
        Fixture.screenshot("select-many-all")
        android.util.Log.i("SelectManyAppTest", "All picked 500 songs in $ms ms")
        assertTrue("All on 500 songs took $ms ms", ms < 2_000)
        Thread { androidx.test.uiautomator.UiDevice.getInstance(instrumentation).pressBack() }.start()
        compose.waitUntil(5_000) { bar().isEmpty() }
        // A song row, not the playlist's name: Library lists the name too, so that would pass after leaving the page.
        compose.waitForIdle()
        assertTrue("still on the playlist after Back ended the picking", compose.onAllNodes(hasText("Test song 1")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun pickedSongsLeaveAPrismPlaylistTogether() {
        Fixture.ready()
        val songs = Fixture.songs(3)
        Fixture.prismPlaylist("Three", songs)
        Fixture.launch()
        openLibraryPlaylist("Three", "Test song 1")
        compose.onAllNodes(hasText("Test song 1")).onFirst().performTouchInput { longClick() }
        compose.waitUntil(5_000) { bar().isNotEmpty() }
        compose.onAllNodes(hasText("Test song 2")).onFirst().performClick()
        compose.waitForText("2 songs picked")
        Fixture.screenshot("select-many-two")
        compose.clickWhenFound(hasText("Remove from this playlist") and hasClickAction())
        compose.waitUntil(5_000) { bar().isEmpty() }
        val id = c.prismLibrary.lists.value.first { it.title == "Three" }.id
        compose.waitUntil(5_000) { c.prismLibrary.lists.value.first { it.id == id }.songs.size == 1 }
        assertEquals(listOf(songs[2].id), c.prismLibrary.lists.value.first { it.id == id }.songs.map { it.id })
    }
}
