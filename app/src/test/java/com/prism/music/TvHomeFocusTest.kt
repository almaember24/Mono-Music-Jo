package com.prism.music

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import com.prism.music.data.prefs.AppSettings
import com.prism.music.ui.tv.LocalTvScreen
import com.prism.music.ui.tv.TAG_HOME
import com.prism.music.ui.tv.TvHomeScreen
import com.prism.music.ui.tv.TvItem
import com.prism.music.ui.tv.TvShelf
import com.prism.music.ui.tv.TvTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Prism TV's Home while it loads (found on a slow Android TV 11): Search stands in and has focus; when the rows come it
 * goes, and focus moves to the first card, never left on the side navigation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = android.app.Application::class)
class TvHomeFocusTest {
    @get:Rule val compose = createComposeRule()

    private fun focusedOn(tag: String) =
        compose.onAllNodes(hasTestTag(tag) and isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun whenTheRowsComeFocusMovesFromSearchToTheFirstCard() {
        var shelves by mutableStateOf(emptyList<TvShelf>())
        compose.setContent {
            TvTheme(AppSettings()) {
                CompositionLocalProvider(LocalTvScreen provides "section:HOME") {
                    TvHomeScreen("Good evening", null, shelves, {}, {}, {}, { _, _ -> }, onSearch = {})
                }
            }
        }
        compose.waitUntil(5_000) { focusedOn("$TAG_HOME/search") }
        shelves = listOf(TvShelf("Quick picks", listOf(TvItem("a", "Song A", "Artist", null), TvItem("b", "Song B", "Artist", null))))
        compose.waitUntil(5_000) { focusedOn("$TAG_HOME/row0/0") }
    }
}
