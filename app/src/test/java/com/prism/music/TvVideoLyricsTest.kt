package com.prism.music

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.prism.music.data.lyrics.LyricLine
import com.prism.music.data.prefs.AppSettings
import com.prism.music.ui.tv.LocalTvFocusMemory
import com.prism.music.ui.tv.LocalTvScreen
import com.prism.music.ui.tv.TAG_LYRICS
import com.prism.music.ui.tv.TAG_VIDEO_CONTROLS
import com.prism.music.ui.tv.TvFocusMemory
import com.prism.music.ui.tv.TvLyricsPlace
import com.prism.music.ui.tv.TvNow
import com.prism.music.ui.tv.TvTheme
import com.prism.music.ui.tv.TvVideoControl
import com.prism.music.ui.tv.TvVideoPlace
import com.prism.music.ui.tv.tvSingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lyrics over a music video (1.8.0, the owner: "the lyrics should play over the video"): the video stays under them,
 * the controls hide, the lines sit on a scrim (no cover backdrop); Back first lets go of a picked line, then closes the
 * lyrics onto the video's Lyrics button. And what Sing's page says while the song's got ready.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = android.app.Application::class)
class TvVideoLyricsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = TvNow("Song", "Artist", null, null, 0.3f, "1:00", "3:20", playing = false, hasLyrics = true)
    private val lines = (0 until 10).map { i -> LyricLine(i * 4_000L, "Line number ${i + 1}", i * 4_000L + 3_500) }
    private var open by mutableStateOf(false)

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun focusedOn(tag: String) = compose.onAllNodes(hasTestTag(tag) and isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun awaitFocus(tag: String) = compose.waitUntil(5_000) { focusedOn(tag) }
    private fun key(k: Key) {
        compose.onAllNodes(isFocused(), useUnmergedTree = true)[0].performKeyInput { pressKey(k) }
        compose.waitForIdle()
    }
    private fun back() { compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle() }

    private fun show() = compose.setContent {
        TvTheme(AppSettings()) {
            val memory = remember { TvFocusMemory() }
            CompositionLocalProvider(LocalTvFocusMemory provides memory, LocalTvScreen provides "video") {
                TvVideoPlace(
                    now, status = com.prism.music.ui.tv.TvVideoStatus.PICTURE,
                    onPlayPause = {}, onNext = {}, onPrevious = {}, onSeek = {}, onLyrics = { open = true }, onQueue = {}, onSong = {}, onMore = {},
                    panelOpen = open, panelCovers = open,
                    panel = {
                        // As TvVideoLyrics: its own Back first (the lyrics' come after it, so go before it).
                        androidx.activity.compose.BackHandler { open = false }
                        CompositionLocalProvider(LocalTvScreen provides "video/lyrics") {
                            TvLyricsPlace(now, lines, 2, remember { mutableLongStateOf(8_500L) }, loading = false, unavailable = false, source = null, accent = null, overVideo = true) {}
                        }
                    },
                ) { Box(Modifier.fillMaxSize().testTag("the video")) }
            }
        }
    }

    @Test fun lyricsOpenOverTheVideoAndBackClosesThemOntoIt() {
        show()
        awaitFocus(TvVideoControl.PLAYPAUSE.fullTag)
        key(Key.DirectionRight); key(Key.DirectionRight)
        awaitFocus(TvVideoControl.LYRICS.fullTag)
        key(Key.DirectionCenter)
        compose.waitUntil(2_000) { open }
        awaitFocus("$TAG_LYRICS/lines")
        // The video under them, the controls hidden, the lines on a scrim.
        assertTrue(exists("the video"))
        assertTrue(exists("$TAG_LYRICS/scrim"))
        compose.waitUntil(3_000) { !exists(TAG_VIDEO_CONTROLS) }
        // A line picked: Back lets go of it (still over the video)…
        key(Key.DirectionDown)
        assertTrue("Back to now shows", exists("$TAG_LYRICS/now"))
        back()
        assertFalse(exists("$TAG_LYRICS/now"))
        assertTrue(open)
        // …then Back closes them: the video's controls, on Lyrics; the video still there.
        back()
        assertFalse(open)
        awaitFocus(TvVideoControl.LYRICS.fullTag)
        assertTrue(exists("the video"))
    }

    @Test fun theLyricsPageKeepsItsBackdrop() {
        compose.setContent {
            TvTheme(AppSettings()) {
                CompositionLocalProvider(LocalTvFocusMemory provides remember { TvFocusMemory() }, LocalTvScreen provides "lyrics") {
                    TvLyricsPlace(now, lines, 2, remember { mutableLongStateOf(8_500L) }, loading = false, unavailable = false, source = null, accent = null) {}
                }
            }
        }
        compose.waitForIdle()
        assertFalse(exists("$TAG_LYRICS/scrim"))
    }

    /** Sing's page (the owner: "Sing just doesn't do anything"): Right from the lyrics reaches the key's −, then Timing. */
    @Test fun rightFromTheLyricsReachesTheFirstButtonThenTiming() {
        var lower = 0
        compose.setContent {
            TvTheme(AppSettings()) {
                CompositionLocalProvider(LocalTvFocusMemory provides remember { TvFocusMemory() }, LocalTvScreen provides "sing") {
                    TvLyricsPlace(
                        now, lines, 2, remember { mutableLongStateOf(8_500L) }, loading = false, unavailable = false, source = null, accent = null,
                        status = "Getting this song ready",
                        buttons = { com.prism.music.ui.tv.TvButton("Key −", { lower++ }, Modifier.testTag("keydown")) },
                    ) {}
                }
            }
        }
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        awaitFocus("$TAG_LYRICS/lines")
        assertTrue(exists("$TAG_LYRICS/status"))
        key(Key.DirectionRight)
        awaitFocus("keydown")
        key(Key.DirectionCenter)
        compose.waitUntil(2_000) { lower == 1 }
        key(Key.DirectionRight)
        awaitFocus("$TAG_LYRICS/timing")
    }

    @Test fun singsPageSaysHowTheSongIsGettingReady() {
        assertEquals(tr(R.string.sing_s_engine_isn_t_downloaded), tvSingStatus(engineMissing = true, progress = null, complete = false, problem = null))
        assertEquals(tr(R.string.getting_this_song_ready_you_can, 40), tvSingStatus(false, 0.4f, false, null))
        assertEquals(tr(R.string.ready_the_voice_is_out), tvSingStatus(false, null, true, "an old problem"))
        assertEquals("It couldn't be read", tvSingStatus(false, null, false, "It couldn't be read"))
        assertEquals(tr(R.string.getting_this_song_ready), tvSingStatus(false, null, false, null))
    }
}
