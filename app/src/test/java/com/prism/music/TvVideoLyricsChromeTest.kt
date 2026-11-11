package com.prism.music

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.prism.music.data.lyrics.LyricLine
import com.prism.music.data.prefs.AppSettings
import com.prism.music.ui.tv.LocalTvFocusMemory
import com.prism.music.ui.tv.LocalTvScreen
import com.prism.music.ui.tv.TAG_LYRICS
import com.prism.music.ui.tv.TV_LYRICS_CHROME_MS
import com.prism.music.ui.tv.TvFocusMemory
import com.prism.music.ui.tv.TvLyricsPlace
import com.prism.music.ui.tv.TvNow
import com.prism.music.ui.tv.TvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lyrics over a music video (the owner's Google TV, 1.8.0: "the song name, progress bar, and lyrics timing setting should
 * disappear after a second or two"): shown at first, they fade a moment after the last key while the song plays (the
 * lines stay); a key only brings them back; focus never stays on Timing as it goes; paused, or on the lyrics page, they
 * stay; with Reduce motion they go without fading.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = android.app.Application::class)
class TvVideoLyricsChromeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var playing by mutableStateOf(true)
    private val lines = (0 until 10).map { i -> LyricLine(i * 4_000L, "Line number ${i + 1}", i * 4_000L + 3_500) }
    private var plays = 0

    private val chrome = listOf("$TAG_LYRICS/title", "$TAG_LYRICS/progress", "$TAG_LYRICS/timing")

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun focusedOn(tag: String) = compose.onAllNodes(hasTestTag(tag) and isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun awaitFocus(tag: String) = compose.waitUntil(5_000) { focusedOn(tag) }
    private fun key(k: Key) {
        compose.onAllNodes(isFocused(), useUnmergedTree = true)[0].performKeyInput { pressKey(k) }
        compose.waitForIdle()
    }
    private fun pass(ms: Long) { compose.mainClock.advanceTimeBy(ms); compose.waitForIdle() }
    private fun chromeShown() = chrome.all(::exists)
    private fun chromeGone() = chrome.none(::exists)

    private fun show(settings: AppSettings = AppSettings(), overVideo: Boolean = true) = compose.setContent {
        TvTheme(settings) {
            CompositionLocalProvider(LocalTvFocusMemory provides remember { TvFocusMemory() }, LocalTvScreen provides "video/lyrics") {
                TvLyricsPlace(
                    TvNow("Song", "Artist", null, null, 0.3f, "1:00", "3:20", playing = playing, hasLyrics = true),
                    lines, 2, remember { mutableLongStateOf(8_500L) }, loading = false, unavailable = false, source = null, accent = null,
                    onPlayPause = { plays++ }, overVideo = overVideo,
                    // A stand-in for the song's timing (it reads the player).
                    timingPanel = { r -> Box(Modifier.size(100.dp).focusRequester(r).testTag("$TAG_LYRICS/nudge").focusable()) },
                ) {}
            }
        }
    }

    @Test fun overAPlayingVideoTheyFadeAfterTheLastKeyAndTheLinesStay() {
        show()
        awaitFocus("$TAG_LYRICS/lines")
        assertTrue("shown at first", chromeShown())
        pass(TV_LYRICS_CHROME_MS + 1_000)
        assertTrue("gone a moment after the last key", chromeGone())
        assertTrue("the lines stay", exists("$TAG_LYRICS/line/2"))
        assertTrue(focusedOn("$TAG_LYRICS/lines"))
    }

    @Test fun aKeyOnlyBringsThemBack() {
        show()
        awaitFocus("$TAG_LYRICS/lines")
        pass(TV_LYRICS_CHROME_MS + 1_000)
        assertTrue(chromeGone())
        // OK on the lines would pause: here it only brings them back.
        key(Key.DirectionCenter)
        pass(500)
        assertTrue(chromeShown())
        assertEquals(0, plays)
        // Gone again; Down would pick a line: here too it only brings them back (no "Back to now").
        pass(TV_LYRICS_CHROME_MS + 1_000)
        assertTrue(chromeGone())
        key(Key.DirectionDown)
        pass(500)
        assertTrue(chromeShown())
        assertFalse("a line was picked", exists("$TAG_LYRICS/now"))
        assertTrue(focusedOn("$TAG_LYRICS/lines"))
        // Shown, keys work as ever (and keep them up).
        key(Key.DirectionCenter)
        assertEquals(1, plays)
        assertTrue(chromeShown())
    }

    @Test fun focusOnTimingMovesToTheLinesAsItGoes() {
        show()
        awaitFocus("$TAG_LYRICS/lines")
        key(Key.DirectionRight)
        awaitFocus("$TAG_LYRICS/timing")
        pass(TV_LYRICS_CHROME_MS + 1_000)
        assertTrue(chromeGone())
        assertTrue("focus on the lines, not a hidden button", focusedOn("$TAG_LYRICS/lines"))
        // Back again on a key, Right reaches Timing as before.
        key(Key.DirectionRight)
        pass(500)
        assertTrue(focusedOn("$TAG_LYRICS/lines"))
        key(Key.DirectionRight)
        awaitFocus("$TAG_LYRICS/timing")
    }

    @Test fun whileTheTimingIsOpenTheyStay() {
        show()
        awaitFocus("$TAG_LYRICS/lines")
        key(Key.DirectionRight)
        awaitFocus("$TAG_LYRICS/timing")
        key(Key.DirectionCenter)
        awaitFocus("$TAG_LYRICS/nudge")
        pass(TV_LYRICS_CHROME_MS * 2)
        assertTrue(chromeShown())
    }

    @Test fun pausedTheyStayAndAPauseBringsThemBack() {
        playing = false
        show()
        awaitFocus("$TAG_LYRICS/lines")
        pass(TV_LYRICS_CHROME_MS * 2)
        assertTrue("paused, they stay", chromeShown())
        playing = true
        compose.waitForIdle()
        pass(TV_LYRICS_CHROME_MS + 1_000)
        assertTrue(chromeGone())
        playing = false
        compose.waitForIdle()
        pass(500)
        assertTrue("paused, they're back", chromeShown())
    }

    @Test fun onTheLyricsPageTheyAlwaysShow() {
        show(overVideo = false)
        awaitFocus("$TAG_LYRICS/lines")
        pass(TV_LYRICS_CHROME_MS * 2)
        assertTrue(chromeShown())
    }

    /** The clock held from a key on (← on the lines does nothing), so the fade's start is known to the frame. */
    private fun fromAKey() {
        compose.mainClock.autoAdvance = false
        key(Key.DirectionLeft)
        assertTrue(chromeShown())
    }

    @Test fun withReduceMotionTheyGoWithoutFading() {
        show(AppSettings(reduceMotion = true))
        awaitFocus("$TAG_LYRICS/lines")
        fromAKey()
        pass(TV_LYRICS_CHROME_MS + 100)
        assertTrue("gone at once", chromeGone())
    }

    @Test fun otherwiseTheyFade() {
        show()
        awaitFocus("$TAG_LYRICS/lines")
        fromAKey()
        pass(TV_LYRICS_CHROME_MS + 100)
        assertTrue("still fading", chromeShown())
        pass(1_000)
        assertTrue(chromeGone())
    }
}
