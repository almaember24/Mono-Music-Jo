package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Playlists (Reddit, 1.7.9): a long one has a slider on the right that jumps through it; genres can be turned off so
 * the playlist keeps its own order with no chips; and a song's menu offers another version of it, which then plays
 * in its place.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val songs = Fixture.songs(150)

    @Before fun start() {
        Fixture.ready()
        Fixture.prismPlaylist("Everything", songs)
        Fixture.launch()
    }

    private fun openPlaylist() {
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Library") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Library") and hasClickAction()).onFirst().performClick()
        compose.clickWhenFound(hasText("Everything") and hasClickAction(), 15_000)
        compose.waitForText("Test song 1")
    }

    @Test fun aLongPlaylistsSliderJumpsToItsEnd() {
        openPlaylist()
        // It shows while the list moves.
        compose.onRoot().performTouchInput { swipeUp() }
        val scroller = hasContentDescription("Scroll through the songs")
        compose.waitUntil(5_000) { compose.onAllNodes(scroller).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(scroller).onFirst().performTouchInput {
            down(topCenter)
            moveTo(center)
            moveTo(bottomCenter)
            up()
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Test song 150")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun genresCanBeTurnedOff() {
        openPlaylist()
        compose.clickWhenFound(hasContentDescription("Sort") and hasClickAction())
        compose.waitForText("Group by genre")
        compose.clickWhenFound(hasText("Show genres") and hasClickAction())
        compose.waitUntil(5_000) { !Fixture.container.settings.current.behavior.playlistGenres }

        compose.clickWhenFound(hasContentDescription("Sort") and hasClickAction())
        compose.waitForText("Show genres")
        assertTrue("Off: no Group by genre", compose.onAllNodes(hasText("Group by genre")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun aSongCanPlayAsAnotherVersion() {
        openPlaylist()
        // The first song's own ⋯ (the page has one too).
        compose.clickWhenFound(hasContentDescription("More") and hasClickAction() and androidx.compose.ui.test.hasParent(hasText("Test song 1")))
        compose.clickWhenFound(hasText("Play a different version") and hasClickAction())
        // The song's own recording is always listed first, picked.
        compose.waitForText("Original")
        compose.waitForText("The one you pick plays for this song from now on")

        // A pick sends the song's sound to the other recording, and taking it back returns it.
        val song = songs.first()
        Fixture.container.pickVersion(song, com.prism.music.data.SongVersions.Pick("tst_other_1", "Test Artist · 3:01"))
        assertEquals("tst_other_1", Fixture.container.streams.versionOf(song.id))
        Fixture.container.pickVersion(song, null)
        assertFalse(Fixture.container.versions.picks.value.containsKey(song.id))
    }
}
