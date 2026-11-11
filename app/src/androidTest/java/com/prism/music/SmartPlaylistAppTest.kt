package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.SmartField
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smart playlists (1.8.3), in the app: Library's "Smart playlist" chip opens the rule editor; a Tag rule's preview
 * counts what it finds, Save opens the new playlist with those songs, and a song tagged afterwards joins it by itself.
 */
@RunWith(AndroidJUnit4::class)
class SmartPlaylistAppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val c get() = Fixture.container
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @After fun cleanUp() {
        runCatching { instrumentation.runOnMainSync { c.player.stop() } }
        runBlocking { c.db.tags().summaries().forEach { c.tags.delete(it.tag) } }
        c.prismLibrary.lists.value.filter { it.smart != null }.forEach { c.prismLibrary.delete(it.id) }
    }

    private fun has(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    private fun hasTag(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test fun rulesMakeAPlaylistThatKeepsUp() {
        Fixture.ready()
        val songs = Fixture.songs(3)
        runBlocking { c.tags.add("Gym", songs.take(2)) }
        Fixture.launch()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Library") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Library") and hasClickAction()).onFirst().performClick()
        compose.waitUntil(10_000) { hasTag("libraryPager") || has("Library") }
        // The chip sits with the playlists' chips, on the Playlists tab (the first; Library reopens on the last one used).
        for (i in 0 until 8) {
            if (compose.onAllNodes(hasText("Playlists") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()) break
            compose.onRoot().performTouchInput { swipeRight(startX = width * 0.15f, endX = width * 0.95f) }
            compose.waitForIdle()
        }
        compose.onAllNodes(hasText("Playlists") and hasClickAction()).onFirst().performClick()
        // Below Liked songs, Downloads and the other tiles.
        for (i in 0 until 6) {
            if (hasTag("newSmart")) break
            compose.onRoot().performTouchInput { swipeUp(startY = height * 0.7f, endY = height * 0.35f) }
            compose.waitForIdle()
        }
        compose.waitUntil(10_000) { hasTag("newSmart") }
        compose.onAllNodes(hasTestTag("newSmart")).onFirst().performScrollTo().performClick()
        compose.waitUntil(5_000) { hasTag("smartEditor") }

        compose.onAllNodes(hasTestTag("smartName")).onFirst().performTextInput("Gym mix")
        compose.onAllNodes(hasTestTag("addRule")).onFirst().performScrollTo().performClick()
        compose.clickWhenFound(hasTestTag("field:${SmartField.TAG.name}"))
        // The rule offers the listener's tags as chips.
        compose.clickWhenFound(hasText("Gym") and hasClickAction())
        compose.waitUntil(10_000) { has("Finds 2 songs") }
        Fixture.screenshot("smart-editor")

        compose.onAllNodes(hasTestTag("smartSave")).onFirst().performScrollTo().performClick()
        compose.waitUntil(10_000) { has("Test song 2") && has("Gym mix") }
        compose.waitUntil(5_000) { has("Test song 1") && !has("Test song 3") }
        Fixture.screenshot("smart-page")
        val list = c.prismLibrary.lists.value.single { it.title == "Gym mix" }
        assertEquals("the tag picked", "Gym", list.smart!!.rules.single().text)

        // A third song tagged later joins by itself.
        runBlocking { c.tags.add("Gym", listOf(songs[2])) }
        compose.waitUntil(10_000) { has("Test song 3") }
    }
}
