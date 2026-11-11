package com.prism.music

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.LyricsLookPrefs
import com.prism.music.data.prefs.LyricsStyle
import com.prism.music.ui.player.lyricsLookOf
import com.prism.music.ui.tv.LocalTvFocusMemory
import com.prism.music.ui.tv.LocalTvScreen
import com.prism.music.ui.tv.TAG_LYRICS_STYLE
import com.prism.music.ui.tv.TvFocusMemory
import com.prism.music.ui.tv.TvLyricsStyleChoice
import com.prism.music.ui.tv.TvTheme
import com.prism.music.ui.tv.tvExampleLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings → Lyrics style on the TV (1.8.0, the owner's pick): the phone's styles with a live example of the focused
 * one; OK picks. Light on a TV's GPU: Glow's example has no blurred copy, and the example stops moving as the panel
 * closes.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = android.app.Application::class)
class TvLyricsStyleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var open by mutableStateOf(true)
    private val picked = ArrayList<LyricsStyle>()

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun focusedOn(tag: String) = compose.onAllNodes(hasTestTag(tag) and isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun key(k: Key) {
        compose.onAllNodes(isFocused(), useUnmergedTree = true)[0].performKeyInput { pressKey(k) }
        compose.waitForIdle()
    }

    private fun show(settings: AppSettings) = compose.setContent {
        TvTheme(settings) {
            CompositionLocalProvider(LocalTvFocusMemory provides remember { TvFocusMemory() }, LocalTvScreen provides "settings/lyricsstyle") {
                Box(Modifier.fillMaxSize()) { if (open) TvLyricsStyleChoice(LyricsStyle.GLOW) { picked += it } }
            }
        }
    }

    @Test fun theFocusedStylesExampleShowsAndOkPicksIt() {
        // Held still (Reduce motion), so the screen settles between keys.
        show(AppSettings(reduceMotion = true))
        compose.waitUntil(5_000) { focusedOn("$TAG_LYRICS_STYLE/glow") }
        assertTrue("the current style's example", exists("$TAG_LYRICS_STYLE/example/glow"))
        key(Key.DirectionDown)
        compose.waitUntil(5_000) { focusedOn("$TAG_LYRICS_STYLE/typewriter") }
        assertTrue("the focused style's example", exists("$TAG_LYRICS_STYLE/example/typewriter"))
        assertFalse(exists("$TAG_LYRICS_STYLE/example/glow"))
        key(Key.DirectionUp); key(Key.DirectionUp)
        compose.waitUntil(5_000) { focusedOn("$TAG_LYRICS_STYLE/card") }
        assertTrue(exists("$TAG_LYRICS_STYLE/example/card"))
        key(Key.DirectionCenter)
        compose.waitUntil(2_000) { picked.isNotEmpty() }
        assertEquals(listOf(LyricsStyle.CARD), picked)
    }

    @Test fun theExampleMovesOnlyWhileThePanelIsOpen() {
        compose.mainClock.autoAdvance = false
        show(AppSettings())
        repeat(30) { compose.mainClock.advanceTimeByFrame() }
        assertTrue(exists("$TAG_LYRICS_STYLE/sung"))
        open = false
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        // Closed: nothing waits for a frame any more, so the screen goes still (it wouldn't with the example running).
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertFalse(exists("$TAG_LYRICS_STYLE/sung"))
    }

    @Test fun glowsExampleHasNoBlurredCopy() {
        val glow = lyricsLookOf(LyricsLookPrefs().startFrom(LyricsStyle.GLOW), null, Color.White, Color.White, null)
        assertTrue(glow.halo)
        val example = tvExampleLook(glow)
        assertFalse("no blurred copy (an offscreen layer)", example.halo)
        assertEquals("a step bolder, as Glow sets the line", glow.weight + 100, example.weight)
        val prism = lyricsLookOf(LyricsLookPrefs().startFrom(LyricsStyle.PRISM), null, Color.White, Color.White, null)
        assertEquals(prism, tvExampleLook(prism))
    }
}
