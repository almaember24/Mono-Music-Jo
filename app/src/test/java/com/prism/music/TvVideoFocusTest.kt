package com.prism.music

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.prism.music.data.prefs.AppSettings
import com.prism.music.ui.tv.LocalTvScreen
import com.prism.music.ui.tv.TAG_VIDEO_SCREEN
import com.prism.music.ui.tv.TvNow
import com.prism.music.ui.tv.TvTheme
import com.prism.music.ui.tv.TvVideoControl
import com.prism.music.ui.tv.TvVideoPlace
import com.prism.music.ui.tv.tvSeekStep
import com.prism.music.ui.tv.tvVideoControls
import com.prism.music.ui.tv.tvVideoFocusTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The TV's music video (the owner's real TV, 1.8.0: "you can't move to anything other than the play button"): every
 * control is reached with the D-pad, ← → on the line seek, the controls hide with focus on the screen and come back
 * on the control left, and a panel over them gives focus back to the button that opened it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = android.app.Application::class)
class TvVideoFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = TvNow("Song", "Artist", null, null, 0.3f, "1:00", "3:20", playing = false)
    private val seeks = ArrayList<Long>()
    private val pressed = ArrayList<String>()
    private var panel by mutableStateOf(false)

    private fun tag(c: TvVideoControl) = c.fullTag
    private fun focusedOn(tag: String) =
        compose.onAllNodes(hasTestTag(tag) and isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun awaitFocus(tag: String) = compose.waitUntil(5_000) { focusedOn(tag) }
    private fun key(k: Key) {
        compose.onAllNodes(isFocused(), useUnmergedTree = true)[0].performKeyInput { pressKey(k) }
        compose.waitForIdle()
    }

    private var status by mutableStateOf(com.prism.music.ui.tv.TvVideoStatus.LOADING)

    private fun show(nowShown: TvNow = now) {
        compose.setContent {
            TvTheme(AppSettings()) {
                CompositionLocalProvider(LocalTvScreen provides "video") {
                    TvVideoPlace(
                        nowShown, status = status,
                        onPlayPause = { pressed += "playpause" }, onNext = { pressed += "next" }, onPrevious = { pressed += "previous" },
                        onSeek = { seeks += it }, onLyrics = { pressed += "lyrics" }, onQueue = { pressed += "queue"; panel = true },
                        onSong = { pressed += "song" }, onMore = { pressed += "more" },
                        panelOpen = panel,
                        panel = {
                            // A stand-in for the queue: one row that takes focus as it opens; OK on it closes the panel.
                            val row = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
                            androidx.compose.runtime.LaunchedEffect(Unit) {
                                repeat(40) { if (runCatching { row.requestFocus() }.getOrDefault(false)) return@LaunchedEffect; kotlinx.coroutines.delay(50) }
                            }
                            Box(Modifier.align(Alignment.CenterEnd).width(300.dp).fillMaxHeight()) {
                                Button(onClick = { panel = false }, modifier = Modifier.focusRequester(row).testTag("panel/row")) { Text("Row") }
                            }
                        },
                    ) {}
                }
            }
        }
    }

    @Test fun everyControlIsReachedWithTheDpad() {
        show()
        awaitFocus(tag(TvVideoControl.PLAYPAUSE))
        key(Key.DirectionLeft); awaitFocus(tag(TvVideoControl.PREVIOUS))
        key(Key.DirectionRight); awaitFocus(tag(TvVideoControl.PLAYPAUSE))
        for (c in listOf(TvVideoControl.NEXT, TvVideoControl.LYRICS, TvVideoControl.QUEUE, TvVideoControl.SONG, TvVideoControl.MORE)) {
            key(Key.DirectionRight); awaitFocus(tag(c))
        }
        // Up from the row: the line; ← → seek there (focus stays), Down lands on play/pause.
        key(Key.DirectionUp); awaitFocus(tag(TvVideoControl.SEEK))
        key(Key.DirectionRight); key(Key.DirectionLeft); key(Key.DirectionLeft)
        assertEquals(listOf(10_000L, -10_000L, -10_000L), seeks)
        assertTrue(focusedOn(tag(TvVideoControl.SEEK)))
        key(Key.DirectionDown); awaitFocus(tag(TvVideoControl.PLAYPAUSE))
        // OK presses the focused one.
        key(Key.DirectionRight); key(Key.DirectionCenter)
        compose.waitUntil(2_000) { "next" in pressed }
    }

    @Test fun backHidesTheControlsWithFocusOnTheScreenAndAKeyBringsThemBackWhereLeft() {
        show()
        awaitFocus(tag(TvVideoControl.PLAYPAUSE))
        key(Key.DirectionRight); awaitFocus(tag(TvVideoControl.NEXT))
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        awaitFocus(TAG_VIDEO_SCREEN)
        compose.waitUntil(3_000) { compose.onAllNodes(hasTestTag(tag(TvVideoControl.NEXT)), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        // Hidden: the key only brings them back (nothing pressed), on the control left.
        key(Key.DirectionCenter)
        awaitFocus(tag(TvVideoControl.NEXT))
        assertFalse("next" in pressed)
    }

    @Test fun theQueueButtonOpensThePanelAndFocusComesBackToItWhenItCloses() {
        show()
        awaitFocus(tag(TvVideoControl.PLAYPAUSE))
        repeat(3) { key(Key.DirectionRight) }
        awaitFocus(tag(TvVideoControl.QUEUE))
        key(Key.DirectionCenter)
        compose.waitUntil(2_000) { panel }
        // The panel's row takes focus (as the queue's next song does); the controls behind it can't: Down stays.
        awaitFocus("panel/row")
        key(Key.DirectionDown); key(Key.DirectionLeft)
        assertTrue(focusedOn("panel/row"))
        key(Key.DirectionCenter)
        compose.waitUntil(2_000) { !panel }
        awaitFocus(tag(TvVideoControl.QUEUE))
    }

    /** The cover (with a spinner) stands over the video until it has a picture, then goes; it says why when there's none. */
    @Test fun theCoverStandsInUntilThePictureComes() {
        show()
        awaitFocus(tag(TvVideoControl.PLAYPAUSE))
        fun exists(t: String) = compose.onAllNodes(hasTestTag(t), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        assertTrue(exists(com.prism.music.ui.tv.TAG_VIDEO_COVER) && exists(com.prism.music.ui.tv.TAG_VIDEO_SPINNER))
        status = com.prism.music.ui.tv.TvVideoStatus.PICTURE
        compose.waitUntil(3_000) { !exists(com.prism.music.ui.tv.TAG_VIDEO_COVER) }
        status = com.prism.music.ui.tv.TvVideoStatus.NO_VIDEO
        compose.waitUntil(3_000) { exists(com.prism.music.ui.tv.TAG_VIDEO_COVER) }
        assertFalse(exists(com.prism.music.ui.tv.TAG_VIDEO_SPINNER))
        // Focus stays on the controls throughout.
        assertTrue(focusedOn(tag(TvVideoControl.PLAYPAUSE)))
    }

    @Test fun whichControlsAVideoShows() {
        assertEquals(TvVideoControl.entries, tvVideoControls(hasLyrics = true, seekable = true))
        assertFalse(TvVideoControl.SEEK in tvVideoControls(hasLyrics = true, seekable = false))
        assertFalse(TvVideoControl.LYRICS in tvVideoControls(hasLyrics = false, seekable = true))
        // Back to the control left, or play/pause when that one went (a live stream has no line).
        assertEquals(TvVideoControl.QUEUE, tvVideoFocusTarget(TvVideoControl.QUEUE, tvVideoControls(true, true)))
        assertEquals(TvVideoControl.PLAYPAUSE, tvVideoFocusTarget(TvVideoControl.SEEK, tvVideoControls(true, false)))
        // A held ← → seeks on the press and every third repeat.
        assertEquals(listOf(true, false, false, true, false, false, true), (0..6).map { tvSeekStep(it) })
    }
}
