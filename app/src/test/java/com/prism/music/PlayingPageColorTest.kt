package com.prism.music

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.ViewLook
import com.prism.music.ui.components.ArtPalette
import com.prism.music.ui.components.ArtworkAccent
import com.prism.music.ui.components.SongRowLayout
import com.prism.music.ui.theme.PrismTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reddit (1.7.9.2): "Playing song in the page's color". On an album page (inside [ArtworkAccent]) the playing song's
 * bars take the page's color when asked for; off (the default), they stay in the split light.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlayingPageColorTest {
    @get:Rule val compose = createComposeRule()

    private val on = mutableStateOf(false)

    private fun bars() = compose.onAllNodes(hasTestTag("pageColorBars"), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test fun theBarsTakeThePagesColorOnlyWhenAsked() {
        compose.setContent {
            PrismTheme(AppSettings(view = ViewLook(playingInPageColor = on.value)), artworkColor = null) {
                ArtworkAccent(ArtPalette(vibrant = Color(0xFFE8731E), dominant = Color(0xFF3A2410))) {
                    SongRowLayout(Song(id = "s1", title = "A song"), playing = true)
                }
            }
        }
        assertEquals("off: the split light, as before", 0, bars())
        on.value = true
        compose.waitForIdle()
        assertEquals("on: the page's color", 1, bars())
    }
}
