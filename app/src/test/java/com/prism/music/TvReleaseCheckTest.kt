package com.prism.music

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.AppSettings
import com.prism.music.ui.tv.HoldsAmbient
import com.prism.music.ui.tv.Idle
import com.prism.music.ui.tv.LocalTvFocusMemory
import com.prism.music.ui.tv.LocalTvScreen
import com.prism.music.ui.tv.TAG_COLLECTION
import com.prism.music.ui.tv.TAG_LYRICS
import com.prism.music.ui.tv.TV_PICK_RETURN_MS
import com.prism.music.ui.tv.TvCollectionScreen
import com.prism.music.ui.tv.TvFocusMemory
import com.prism.music.ui.tv.TvHomeOwn
import com.prism.music.ui.tv.TvLyricsPlace
import com.prism.music.ui.tv.TvNow
import com.prism.music.ui.tv.TvPlay
import com.prism.music.ui.tv.TvSong
import com.prism.music.ui.tv.TvTheme
import com.prism.music.ui.tv.byDpad
import com.prism.music.ui.tv.tvHoldsFocus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The 1.8.0 release check of Prism TV: what it found, each kept fixed. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = android.app.Application::class)
class TvReleaseCheckTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun focusedOn(tag: String) = compose.onAllNodes(hasTestTag(tag) and isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun awaitFocus(tag: String) = compose.waitUntil(5_000) { focusedOn(tag) }
    private fun key(k: Key) {
        compose.onAllNodes(isFocused(), useUnmergedTree = true)[0].performKeyInput { pressKey(k) }
        compose.waitForIdle()
    }

    /** 11: a play cancelled (the page left, another pick) isn't a play that failed: no "Couldn't start". */
    @Test fun aCancelledPlayIsntAFailedOne() = runBlocking {
        assertTrue(TvPlay.tried { true })
        assertFalse(TvPlay.tried { throw java.io.IOException("no connection") })
        try {
            TvPlay.tried { throw CancellationException("left") }
            fail("a cancellation goes on up")
        } catch (_: CancellationException) {
        }
    }

    /** 5: one account never sees another's own rows; Quick picks made before YouTube's shelves are made again with them. */
    @Test fun anotherAccountsRowsGo() {
        TvHomeOwn.clear()
        TvHomeOwn.forAccount("a@example.com")
        TvHomeOwn.picks = listOf(Song("aaaaaaaaaaa", "A's pick", listOf(ArtistRef("A", null))))
        assertFalse("the same account keeps them", TvHomeOwn.forAccount("a@example.com"))
        assertTrue(TvHomeOwn.picks != null)
        assertTrue("another account: they go", TvHomeOwn.forAccount("b@example.com"))
        assertEquals(null, TvHomeOwn.picks)
        // Made from the listening alone (no shelves yet): made again once the shelves are here, not before.
        TvHomeOwn.picks = listOf(Song("bbbbbbbbbbb", "B's pick", listOf(ArtistRef("B", null))))
        TvHomeOwn.picksWithShelves = false
        assertFalse(TvHomeOwn.picksDue(shelvesNow = false))
        assertTrue(TvHomeOwn.picksDue(shelvesNow = true))
        TvHomeOwn.picksWithShelves = true
        assertFalse(TvHomeOwn.picksDue(shelvesNow = true))
        // Signed out: nothing kept either.
        assertTrue(TvHomeOwn.forAccount(null))
        assertEquals(null, TvHomeOwn.picks)
    }

    /** 4: a picked line running out while "Back to now" has focus: focus goes to the lines, never onto nothing. */
    @Test fun aPickRunningOutGivesFocusBackToTheLines() {
        val now = TvNow("A song", "An artist", null, null, 0.2f, "0:20", "3:00", playing = true, hasLyrics = true)
        val lines = (0 until 10).map { i -> LyricLine(i * 4_000L, "Line number ${i + 1}", i * 4_000L + 3_500) }
        compose.setContent {
            TvTheme(AppSettings()) {
                CompositionLocalProvider(LocalTvFocusMemory provides remember { TvFocusMemory() }, LocalTvScreen provides "lyrics") {
                    TvLyricsPlace(now, lines, 2, remember { mutableLongStateOf(8_500L) }, loading = false, unavailable = false, source = null, accent = null) {}
                }
            }
        }
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        awaitFocus("$TAG_LYRICS/lines")
        key(Key.DirectionDown)
        key(Key.DirectionRight)
        awaitFocus("$TAG_LYRICS/now")
        compose.mainClock.advanceTimeBy(TV_PICK_RETURN_MS + 500); compose.waitForIdle()
        assertTrue("Back to now is gone", compose.onAllNodes(hasTestTag("$TAG_LYRICS/now"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        awaitFocus("$TAG_LYRICS/lines")
    }

    /** 2: a panel over a page holds focus: the D-pad never leaves it for a control hidden behind it. */
    @Test fun aPanelHoldsFocus() {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                Column { listOf("behind/a", "behind/b").forEach { Box(Modifier.size(40.dp).testTag(it).focusable()) } }
                val first = remember { FocusRequester() }
                Column(Modifier.size(200.dp).tvHoldsFocus()) {
                    Box(Modifier.size(40.dp).focusRequester(first).testTag("panel/a").focusable())
                    Box(Modifier.size(40.dp).testTag("panel/b").focusable())
                }
                androidx.compose.runtime.LaunchedEffect(Unit) { first.requestFocus() }
            }
        }
        awaitFocus("panel/a")
        key(Key.DirectionUp); key(Key.DirectionLeft)
        assertTrue(focusedOn("panel/a"))
        key(Key.DirectionDown); key(Key.DirectionDown); key(Key.DirectionDown)
        assertTrue(focusedOn("panel/b"))
    }

    /** 8: a group's own way in is only for the D-pad: an item given focus (a page coming back) keeps it. */
    @Test fun comingBackFocusesTheItemLeft() {
        val b = FocusRequester()
        compose.setContent {
            val first = remember { FocusRequester() }
            Column {
                Box(Modifier.size(40.dp).testTag("above").focusable())
                Row(Modifier.focusProperties { onEnter = { if (byDpad()) first.requestFocus() } }.focusGroup()) {
                    Box(Modifier.size(40.dp).focusRequester(first).testTag("row/a").focusable())
                    Box(Modifier.size(40.dp).focusRequester(b).testTag("row/b").focusable())
                }
            }
        }
        compose.runOnUiThread { b.requestFocus() }
        awaitFocus("row/b")
        key(Key.DirectionUp)
        awaitFocus("above")
        // Down into the row: its first item, as the design's path.
        key(Key.DirectionDown)
        awaitFocus("row/a")
    }

    /** 7: Down from Play on a long list reaches the playing song, though the list was made before its songs came. */
    @Test fun downFromPlayReachesThePlayingSongOfALongList() {
        var songs by mutableStateOf(emptyList<TvSong>())
        compose.setContent {
            TvTheme(AppSettings()) {
                CompositionLocalProvider(LocalTvFocusMemory provides remember { TvFocusMemory() }, LocalTvScreen provides "album") {
                    TvCollectionScreen("An album", "An artist", null, songs, {}, {}, {}, playing = if (songs.isEmpty()) -1 else 80)
                }
            }
        }
        compose.waitForIdle()
        songs = (1..120).map { TvSong("s$it", "Song $it", "Artist", "3:00", null) }
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        awaitFocus("$TAG_COLLECTION/play")
        key(Key.DirectionDown)
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        awaitFocus("$TAG_COLLECTION/song/80")
    }

    /** 10: a page that keeps the ambient screen away (pairing, typing a search) does so while it's shown, then not. */
    @Test fun pagesHoldTheAmbientScreenAway() {
        var shown by mutableStateOf(true)
        val before = Idle.holds
        compose.setContent { if (shown) HoldsAmbient() }
        compose.waitForIdle()
        assertEquals(before + 1, Idle.holds)
        shown = false
        compose.waitForIdle()
        assertEquals(before, Idle.holds)
        // Let go, the quiet counts from then.
        assertTrue(System.currentTimeMillis() - Idle.lastInput < 5_000)
    }
}
