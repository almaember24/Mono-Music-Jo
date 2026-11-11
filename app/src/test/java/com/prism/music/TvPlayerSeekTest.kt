package com.prism.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.prism.music.data.prefs.AppSettings
import com.prism.music.ui.tv.TAG_PLAYER
import com.prism.music.ui.tv.TV_SEEK_STEP_MS
import com.prism.music.ui.tv.TvNow
import com.prism.music.ui.tv.TvNowPlayingScreen
import com.prism.music.ui.tv.TvTheme
import com.prism.music.ui.tv.tvSeekStep
import com.prism.music.ui.tv.tvSeekStepMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TV: ← → seek on the player (1.8.3, D29, Reddit). Up from the controls lands on the progress line; there ← → move
 * 10 s (held: faster), OK pauses, Down goes back. ← → on the buttons still move between them: nothing is taken from them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = android.app.Application::class)
class TvPlayerSeekTest {
    @get:Rule val compose = createComposeRule()

    private val seeks = ArrayList<Long>()
    private var plays = 0

    private fun show(total: String = "3:41") {
        val now = TvNow("Test title", "Test artist", null, null, 0.38f, "1:24", total, playing = true, liked = false, hasLyrics = true, hasVideo = false, next = null)
        compose.setContent {
            TvTheme(AppSettings()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    TvNowPlayingScreen(now, { plays++ }, {}, {}, {}, {}, {}, {}, {}, {}, {}, onSeek = { seeks += it })
                }
            }
        }
        compose.waitForIdle()
    }

    private fun node(tag: String) = compose.onNode(hasTestTag("$TAG_PLAYER/$tag"), useUnmergedTree = true)

    @Test fun upReachesTheLineWhereLeftAndRightSeek() {
        show()
        node("playpause").requestFocus()
        node("playpause").performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitForIdle()
        node("progress").assertIsFocused()
        node("progress").performKeyInput { pressKey(Key.DirectionRight) }
        node("progress").performKeyInput { pressKey(Key.DirectionRight) }
        node("progress").performKeyInput { pressKey(Key.DirectionLeft) }
        assertEquals(listOf(TV_SEEK_STEP_MS, TV_SEEK_STEP_MS, -TV_SEEK_STEP_MS), seeks)
        node("progress").assertIsFocused()
        // OK on the line plays and pauses.
        node("progress").performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(1, plays)
        // Down: back to the controls.
        node("progress").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        val focusedControl = listOf("shuffle", "previous", "playpause", "next", "repeat").any { tag ->
            runCatching { node(tag).assertIsFocused() }.isSuccess
        }
        assertTrue("Down goes back to the controls", focusedControl)
    }

    @Test fun theButtonsKeepLeftAndRight() {
        show()
        node("playpause").requestFocus()
        node("playpause").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        node("previous").assertIsFocused()
        node("previous").performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        node("playpause").assertIsFocused()
        assertTrue("no seek from the buttons", seeks.isEmpty())
    }

    @Test fun aLiveStreamHasAPlainLine() {
        show(total = "")
        node("playpause").requestFocus()
        node("playpause").performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitForIdle()
        assertTrue(runCatching { node("progress").assertIsFocused() }.isFailure)
        assertTrue(seeks.isEmpty())
    }

    @Test fun heldGoesFaster() {
        // A press, then every third repeat; after about a second and a half held, 30 s a step.
        assertTrue(tvSeekStep(0))
        assertEquals(TV_SEEK_STEP_MS, tvSeekStepMs(0))
        assertEquals(TV_SEEK_STEP_MS, tvSeekStepMs(29))
        assertEquals(TV_SEEK_STEP_MS * 3, tvSeekStepMs(30))
        assertEquals(10_000L, TV_SEEK_STEP_MS)
    }
}
