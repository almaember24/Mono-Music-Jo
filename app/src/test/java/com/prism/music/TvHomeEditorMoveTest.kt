package com.prism.music

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.HomeSectionConfig
import com.prism.music.data.prefs.HomeSections
import com.prism.music.ui.tv.LocalTvFocusMemory
import com.prism.music.ui.tv.LocalTvScreen
import com.prism.music.ui.tv.TAG_HOME_EDITOR
import com.prism.music.ui.tv.TvFocusMemory
import com.prism.music.ui.tv.TvHomeEditorRow
import com.prism.music.ui.tv.TvHomeEditorScreen
import com.prism.music.ui.tv.TvHomeLayout
import com.prism.music.ui.tv.TvTheme
import com.prism.music.ui.tv.layoutWithShown
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The TV suite's case for Customize Home's moves (1.8.0): a fresh layout (every row the TV can have, the holiday key out
 * of season, rows with no content) where Home shows only YouTube's shelves A, B and C, A hidden in the editor. Move
 * beside C, then Up twice, carries C above A, as the remote does it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = android.app.Application::class)
class TvHomeEditorMoveTest {
    @get:Rule val compose = createComposeRule()
    private val a = HomeSections.shelfKey("Prism test A")
    private val b = HomeSections.shelfKey("Prism test B")
    private val c = HomeSections.shelfKey("Prism test C")

    @Test fun moveAndUpTwiceCarriesTheRowPastTheRowsOnHome() {
        var tv by mutableStateOf(emptyList<HomeSectionConfig>())
        val phone = layoutWithShown(HomeSections.initial, a, "Prism test A", shown = false)
        val available = TvHomeLayout.available(listOf(a, b, c).map { HomeSectionConfig(it, it) }, phone) { it }
        // What Home showed before the editor opened: only the three shelves have songs.
        val onHome = setOf(a, b, c)
        compose.setContent {
            TvTheme(AppSettings()) {
                CompositionLocalProvider(LocalTvScreen provides "home_editor", LocalTvFocusMemory provides remember { TvFocusMemory() }) {
                    var moving by remember { mutableStateOf<String?>(null) }
                    val layout = TvHomeLayout.editable(tv, available, phone)
                    TvHomeEditorScreen(
                        layout.map { TvHomeEditorRow(it.key, it.title, it.visible) }, moving,
                        onToggle = {}, onPickUp = { moving = it },
                        onStep = { key, by ->
                            val visible = layout.filter { it.visible }.map { it.key }.toSet()
                            tv = TvHomeLayout.saved(TvHomeLayout.movedPast(layout, key, by) { k -> k in visible && k in onHome })
                        },
                        onPutDown = { moving = null }, onReset = {},
                    )
                }
            }
        }
        val move = hasTestTag("$TAG_HOME_EDITOR/move/$c")
        compose.onNode(hasTestTag("$TAG_HOME_EDITOR/list")).performScrollToNode(move)
        compose.onNode(move).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitUntil(5_000) { compose.onAllNodes(move and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(move).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.onNode(move).performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitForIdle()
        compose.onNode(move).performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitForIdle()
        val keys = tv.map { it.key }
        assertTrue("layout $keys", keys.isNotEmpty() && keys.indexOf(c) in 0 until keys.indexOf(a))
        // Carried, focus stayed on C's Move.
        compose.waitUntil(5_000) { compose.onAllNodes(move and isFocused()).fetchSemanticsNodes().isNotEmpty() }
    }
}
