package com.prism.music

import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.text.TextLayoutResult
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** GitHub issues fixed before the 1.7.9 check: #237 tab labels, #225 search in Local files, #227 screen fades. */
@RunWith(AndroidJUnit4::class)
class IssueRound179Test {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private var tone: android.net.Uri? = null

    @After fun cleanUp() {
        tone?.let { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
    }

    /** #237: "Liked songs" on a floating, compact, six-tab bar with labels fits its tab instead of being cut off. */
    @Test fun longTabNamesFitTheFloatingBar() {
        Fixture.ready()
        runBlocking {
            Fixture.container.settings.editNow {
                it[stringPreferencesKey("nav_style")] = "FLOATING"
                it[stringPreferencesKey("nav_labels")] = "ALWAYS"
                it[stringPreferencesKey("nav_bar_size")] = "COMPACT"
                it[stringPreferencesKey("nav_tabs")] = "HOME,SEARCH,LIBRARY,LIKED,LOCAL,SETTINGS"
            }
        }
        Fixture.launch()
        val label = hasText("Liked songs")
        compose.waitUntil(15_000) { compose.onAllNodes(label, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // The tab bar's one: the lowest on screen (Home may show a Liked songs tile too).
        val node = compose.onAllNodes(label, useUnmergedTree = true).fetchSemanticsNodes().maxBy { it.boundsInRoot.bottom }
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.single()
        assertFalse("the label is cut off", layout.hasVisualOverflow)
        assertFalse("the label ends in …", layout.isLineEllipsized(0))
    }

    /** #225: Local files has a search box; a word finds the file, and nonsense says nothing matches. */
    @Test fun localFilesCanBeSearched() {
        Fixture.ready()
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        ui.grantRuntimePermission(Fixture.context.packageName, perm)
        tone = addTone("Prism search tone")
        Fixture.launch()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Library") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Library") and hasClickAction()).onFirst().performClick()
        compose.clickWhenFound(hasText("Local files") and hasClickAction(), 15_000)
        compose.waitUntil(30_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("search tone")
        compose.waitForText("Prism search tone", 30_000)
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("zzqx nothing")
        compose.waitForText("No matches")
    }

    /**
     * #227: the screen fades can be turned off from Settings. Since 1.8.0 that's "Changing screens" → None (fades and
     * #208's slide are one choice, on the Customize Prism page); searching "fades" still finds it.
     */
    @Test fun screenFadesCanBeTurnedOff() {
        Fixture.ready()
        Fixture.launch()
        compose.openSettings()
        compose.onNode(hasSetTextAction()).performTextInput("fades")
        compose.clickWhenFound(hasText("Changing screens") and hasClickAction())
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        assertTrue(Fixture.container.settings.current.behavior.screenFades)
        compose.clickWhenFound(hasText("None") and hasClickAction())
        compose.waitUntil(5_000) { !Fixture.container.settings.current.behavior.screenFades }
    }

    private fun addTone(title: String) = Fixture.tone(title)
}
