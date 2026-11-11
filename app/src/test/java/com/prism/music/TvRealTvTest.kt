package com.prism.music

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import com.prism.music.data.prefs.AppSettings
import com.prism.music.ui.tv.TAG_COLLECTION
import com.prism.music.ui.tv.TAG_LIBRARY
import com.prism.music.ui.tv.TAG_NAV_NOW
import com.prism.music.ui.tv.TAG_PLAYING_MARK
import com.prism.music.ui.tv.TAG_SEARCH
import com.prism.music.ui.tv.TvCollectionScreen
import com.prism.music.ui.tv.TvLibraryEmpty
import com.prism.music.ui.tv.TvLibraryScreen
import com.prism.music.ui.tv.TvNavFrame
import com.prism.music.ui.tv.TvNow
import com.prism.music.ui.tv.TvSearchScreen
import com.prism.music.ui.tv.TvSection
import com.prism.music.ui.tv.TvSong
import com.prism.music.ui.tv.TvTheme
import com.prism.music.ui.tv.collectionEntry
import com.prism.music.ui.tv.tvWelcomeDue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Prism TV, the owner's real TV (1.8.0 RC): each finding's fix, held to here (the remote's own runs are in-app). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = android.app.Application::class)
class TvRealTvTest {
    @get:Rule val compose = createComposeRule()

    private fun focusedOn(tag: String) =
        compose.onAllNodes(hasTestTag(tag) and isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun awaitFocus(tag: String) {
        val end = System.currentTimeMillis() + 5_000
        while (!focusedOn(tag) && System.currentTimeMillis() < end) { Thread.sleep(50); compose.mainClock.advanceTimeBy(50) }
        val now = compose.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes().map { it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null } }
        assertTrue("focus on $tag, not $now", focusedOn(tag))
    }

    /** A screen of its own, as the TV's shell gives each (focus memory isn't shared between tests). */
    @androidx.compose.runtime.Composable
    private fun fresh(content: @androidx.compose.runtime.Composable () -> Unit) {
        val memory = androidx.compose.runtime.remember { com.prism.music.ui.tv.TvFocusMemory() }
        androidx.compose.runtime.CompositionLocalProvider(com.prism.music.ui.tv.LocalTvFocusMemory provides memory, com.prism.music.ui.tv.LocalTvScreen provides "collection") { content() }
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** The D-pad's move (the same focus search a remote's key makes). */
    private var focus: androidx.compose.ui.focus.FocusManager? = null
    private fun press(d: androidx.compose.ui.focus.FocusDirection) { compose.runOnIdle { focus!!.moveFocus(d) } }

    private val songs = (0 until 30).map { TvSong("s$it", "Song ${it + 1}", "Artist", "3:00") }

    @Test fun anAlbumsSongsAreEnteredAtTheFirstOrThePlayingOne() {
        assertEquals(0, collectionEntry(last = -1, playing = -1, size = 49))
        assertEquals(8, collectionEntry(last = -1, playing = 8, size = 49))
        assertEquals(3, collectionEntry(last = 3, playing = 8, size = 49))
        assertEquals(-1, collectionEntry(last = -1, playing = -1, size = 0))
        // Down from Play: the first song (it did nothing on the real TV).
        compose.setContent { focus = androidx.compose.ui.platform.LocalFocusManager.current; TvTheme(AppSettings()) { fresh { TvCollectionScreen("Mellow", "Playlist", null, songs, {}, {}, {}) } } }
        awaitFocus("$TAG_COLLECTION/play")
        press(androidx.compose.ui.focus.FocusDirection.Down)
        awaitFocus("$TAG_COLLECTION/song/0")
    }

    @Test fun thePlayingSongIsMarkedAndEnteredFromShuffle() {
        compose.setContent { focus = androidx.compose.ui.platform.LocalFocusManager.current; TvTheme(AppSettings()) { fresh { TvCollectionScreen("Mellow", "Playlist", null, songs, {}, {}, {}, playing = 8) } } }
        awaitFocus("$TAG_COLLECTION/play")
        assertTrue(exists(TAG_PLAYING_MARK))
        // Right to Shuffle, Right into the songs: the one playing, not whichever row is level with the buttons.
        press(androidx.compose.ui.focus.FocusDirection.Right)
        awaitFocus("$TAG_COLLECTION/shuffle")
        press(androidx.compose.ui.focus.FocusDirection.Right)
        awaitFocus("$TAG_COLLECTION/song/8")
    }

    @Test fun theSideNavigationHasNowPlayingWhileSomethingPlays() {
        val now = TvNow("Stay With Me", "Sam Smith", null, null, 0.1f, "0:03", "2:53", playing = true)
        compose.setContent { TvTheme(AppSettings()) { TvNavFrame(TvSection.HOME, {}, focusNav = true, now = now.title to now.art) {} } }
        compose.waitForIdle()
        assertTrue(exists(TAG_NAV_NOW))
    }

    @Test fun anEmptyLibraryTabSaysWhy() {
        compose.setContent {
            TvTheme(AppSettings()) {
                TvLibraryScreen(listOf("Playlists", "Albums"), 1, {}, emptyList(), {}, empty = { TvLibraryEmpty(signedIn = false) {} })
            }
        }
        compose.waitForIdle()
        assertTrue(exists("$TAG_LIBRARY/empty"))
        assertTrue(exists("$TAG_LIBRARY/signin"))
    }

    @Test fun searchSaysHowBeforeTyping() {
        compose.setContent {
            TvTheme(AppSettings()) { TvSearchScreen("", {}, {}, emptyList(), {}, null, emptyList(), emptyList(), {}, {}) }
        }
        compose.waitForIdle()
        assertTrue(exists("$TAG_SEARCH/how"))
    }

    /** A TV onboarded on the phone app (1.7.x) still gets its welcome once, signed out; never signed in. */
    @Test fun theTvWelcomeComesOncePerTvSignedOut() {
        assertTrue(tvWelcomeDue(welcomed = false, signedIn = false))
        assertFalse(tvWelcomeDue(welcomed = true, signedIn = false))
        assertFalse(tvWelcomeDue(welcomed = false, signedIn = true))
        assertFalse(AppSettings().tv.welcomed)
    }
}
