package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #395: in Search's typed list, a result's ⋯ opened its menu and the list closed under it (the menu's sheet is a window of
 * its own, which takes the keyboard down, and a keyboard going down ran the search), so the song was gone. With the
 * on-screen keyboard up, as on a phone.
 */
@RunWith(AndroidJUnit4::class)
class SearchTypedMenuAppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var imeWithKeyboard: String? = null

    private fun shell(command: String): String {
        val out = instrumentation.uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes().decodeToString().trim() }
    }

    @After fun cleanUp() {
        RecordedNetwork.answering = null
        imeWithKeyboard?.let { shell("settings put secure show_ime_with_hard_keyboard ${if (it == "null" || it.isBlank()) "0" else it}") }
    }

    /** YouTube's typing suggestions for anything typed: one song, "Beat It", with its picture's place. */
    private val suggestions = """{"contents":[{"searchSuggestionsSectionRenderer":{"contents":[
        {"musicResponsiveListItemRenderer":{"flexColumns":[
          {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Beat It"}]}}},
          {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"},{"text":" • "},{"text":"Michael Jackson"}]}}}],
          "playlistItemData":{"videoId":"oRdxUFDoQe0"}}}]}}]}"""

    private fun keyboardUp(scenario: ActivityScenario<PrismActivity>): Boolean {
        var up = false
        scenario.onActivity { a -> up = ViewCompat.getRootWindowInsets(a.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        return up
    }

    @Test fun aTypedResultsMenuLeavesTheTypedList() {
        Fixture.ready()
        // The on-screen keyboard, also on an emulator with its computer's keyboard.
        imeWithKeyboard = shell("settings get secure show_ime_with_hard_keyboard")
        shell("settings put secure show_ime_with_hard_keyboard 1")
        RecordedNetwork.answering = { url, _ -> if ("get_search_suggestions" in url) suggestions else null }
        val scenario = Fixture.launch()

        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Search") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Search") and hasClickAction()).onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("beat")
        compose.waitForText("Search for “beat”")
        // The typed list waits a moment before asking YouTube (SearchScreen's delay), on the test's clock: nothing moves
        // that clock on while the keyboard keeps Compose busy, so it's moved here.
        compose.mainClock.advanceTimeBy(1_000)
        // The bug needs the keyboard that a menu's window takes away.
        val end = System.currentTimeMillis() + 5_000
        while (!keyboardUp(scenario) && System.currentTimeMillis() < end) Thread.sleep(100)
        assumeTrue("no on-screen keyboard on this device", keyboardUp(scenario))

        // The song's ⋯ in the typed list.
        compose.clickWhenFound(hasContentDescription("More") and hasClickAction() and hasParent(hasText("Beat It")), 15_000)
        compose.waitForText("Play next")
        Thread.sleep(1_000)
        assertTrue("the typed list closed under the menu", compose.onAllNodes(hasText("Search for “beat”")).fetchSemanticsNodes().isNotEmpty())

        // The menu closed: the typed list, and its song, are still there.
        UiDevice.getInstance(instrumentation).pressBack()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Play next")).fetchSemanticsNodes().isEmpty() }
        Thread.sleep(1_000)
        assertTrue("the typed list closed with the menu", compose.onAllNodes(hasText("Search for “beat”")).fetchSemanticsNodes().isNotEmpty())
        assertTrue("the song went", compose.onAllNodes(hasText("Beat It")).fetchSemanticsNodes().isNotEmpty())
    }
}
