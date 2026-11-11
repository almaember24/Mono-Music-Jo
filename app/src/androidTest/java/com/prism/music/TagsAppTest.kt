package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tags (1.8.3), in the app: two songs picked in a playlist get a new tag from the Select many bar; Library then has a
 * Tags tab with the tag's tile, which opens its songs. A song's own menu takes the tag off again.
 */
@RunWith(AndroidJUnit4::class)
class TagsAppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val c get() = Fixture.container
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @After fun cleanUp() {
        runCatching { instrumentation.runOnMainSync { c.player.stop() } }
        runBlocking { c.db.tags().summaries().forEach { c.tags.delete(it.tag) } }
    }

    private fun swipeLibrary(toTheRight: Boolean, until: () -> Boolean) {
        repeat(8) {
            if (until()) return
            compose.onRoot().performTouchInput { if (toTheRight) swipeRight(startX = width * 0.15f, endX = width * 0.95f) else swipeLeft(startX = width * 0.9f, endX = width * 0.1f) }
            compose.waitForIdle()
        }
    }

    private fun has(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test fun pickedSongsGetATagThatLibraryLists() {
        Fixture.ready()
        val songs = Fixture.songs(3)
        Fixture.prismPlaylist("Three to tag", songs)
        Fixture.launch()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Library") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Library") and hasClickAction()).onFirst().performClick()
        compose.waitUntil(10_000) { has("Songs") }
        swipeLibrary(toTheRight = true) { has("Three to tag") }
        compose.clickWhenFound(hasText("Three to tag") and hasClickAction(), 15_000)
        compose.waitForText("Test song 1")

        // Two songs picked → Add a tag → "Gym".
        compose.onAllNodes(hasText("Test song 1")).onFirst().performTouchInput { longClick() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("selectionBar")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Test song 2")).onFirst().performClick()
        compose.waitForText("2 songs picked")
        compose.clickWhenFound(hasText("Add a tag") and hasClickAction())
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("newTag")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasTestTag("newTag")).onFirst().performTextInput("Gym")
        compose.clickWhenFound(hasTestTag("addTag"))
        compose.waitUntil(10_000) { runBlocking { c.tags.songs("Gym").first().size == 2 } }
        assertEquals(setOf(songs[0].id, songs[1].id), runBlocking { c.tags.songs("Gym").first().map { it.id }.toSet() })
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Gym"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        Fixture.screenshot("tags-picker")

        // Library → Tags → Gym: its two songs.
        // Back puts the keyboard away first, then closes the sheet.
        repeat(3) {
            if (compose.onAllNodes(hasTestTag("tagPicker")).fetchSemanticsNodes().isEmpty()) return@repeat
            Thread { androidx.test.uiautomator.UiDevice.getInstance(instrumentation).pressBack() }.start()
            Thread.sleep(1_500)
            compose.waitForIdle()
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("tagPicker")).fetchSemanticsNodes().isEmpty() }
        // The picking ended with the sheet; Back leaves the playlist.
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("selectionBar")).fetchSemanticsNodes().isEmpty() }
        Thread { androidx.test.uiautomator.UiDevice.getInstance(instrumentation).pressBack() }.start()
        compose.waitUntil(10_000) { has("Songs") && !has("Test song 1") }
        compose.clickWhenFound(hasText("Tags") and hasClickAction(), 10_000)
        compose.clickWhenFound(hasText("Gym") and hasClickAction(), 10_000)
        compose.waitForText("Test song 2")
        Fixture.screenshot("tags-page")
        compose.waitUntil(5_000) { has("Test song 1") && !has("Test song 3") }

        // A song's own menu: the tag comes off it.
        compose.onAllNodes(hasContentDescription("More") and hasClickAction()).onFirst().performClick()
        compose.clickWhenFound(hasText("Add a tag") and hasClickAction())
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Gym") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Gym") and hasClickAction()).onFirst().performClick()
        compose.waitUntil(5_000) { runBlocking { c.tags.songs("Gym").first().size == 1 } }
    }
}
