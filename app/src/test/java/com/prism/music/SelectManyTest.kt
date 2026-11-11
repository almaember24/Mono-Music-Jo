package com.prism.music

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.prism.music.data.model.Song
import com.prism.music.ui.components.LocalRowMarks
import com.prism.music.ui.components.LocalSongSelection
import com.prism.music.ui.components.RowMarks
import com.prism.music.ui.components.SelectableSongs
import com.prism.music.ui.components.SelectionPlan
import com.prism.music.ui.components.SongRow
import com.prism.music.ui.components.SongSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Select many (1.8.3, G141 / #381): long-press a song in a list to start picking, tap to pick more, "All" for the list;
 * the bar's buttons act on one, many or all of them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h640dp-xhdpi")
class SelectManyTest {
    @get:Rule val compose = createComposeRule()

    private val a = Song("a", "Alpha")
    private val b = Song("b", "Bravo")
    private val c = Song("c", "Charlie")
    private val file = Song("${Song.LOCAL_PREFIX}9", "On the phone")
    private val episode = Song("rss:1", "An episode")

    private val marks = RowMarks(mutableStateOf(emptySet()), mutableStateOf(emptyMap()), mutableStateOf(emptyMap()), mutableStateOf(emptyMap()))

    // ---- the selection itself

    @Test fun pickingStartsWithOneAndTogglesTheRest() {
        val s = SongSelection()
        assertFalse(s.selecting)
        s.start(a, null)
        assertEquals(listOf("a"), s.picked!!.keys.toList())
        s.toggle(b); s.toggle(c)
        assertEquals("in the order picked", listOf("a", "b", "c"), s.picked!!.keys.toList())
        s.toggle(b)
        assertEquals(listOf("a", "c"), s.picked!!.keys.toList())
        s.toggle(a); s.toggle(c)
        assertTrue("none picked still picking (until ✕ or Back)", s.selecting)
        s.clear()
        assertNull(s.picked)
    }

    @Test fun allPicksTheListAndThenNone() {
        val s = SongSelection()
        val list = com.prism.music.ui.components.SelectScope().apply { songs = listOf(a, b, c) }
        s.start(b, list)
        s.all()
        assertEquals(setOf("a", "b", "c"), s.picked!!.keys)
        s.all()
        assertTrue("with all picked, All picks none", s.picked!!.isEmpty())
    }

    @Test fun aToggleWhileNotPickingDoesNothing() {
        val s = SongSelection()
        s.toggle(a); s.all()
        assertNull(s.picked)
    }

    // ---- what the bar's buttons do with one, many and all

    @Test fun likeLikesTheOnesNotYetLikedElseUnlikesThemAll() {
        assertEquals(true to listOf(a), SelectionPlan.like(listOf(a), emptySet()))
        assertEquals("many: only the unliked", true to listOf(b, c), SelectionPlan.like(listOf(a, b, c), setOf("a")))
        assertEquals("all liked: unlike them all", false to listOf(a, b, c), SelectionPlan.like(listOf(a, b, c), setOf("a", "b", "c")))
        assertEquals("episodes have no likes", true to listOf(a), SelectionPlan.like(listOf(a, episode), emptySet()))
        assertTrue("only episodes: nothing", SelectionPlan.like(listOf(episode), emptySet()).second.isEmpty())
    }

    @Test fun downloadGetsTheMissingOnesElseRemovesThemAll() {
        val down = setOf("a")
        assertEquals(true to listOf(b), SelectionPlan.download(listOf(b)) { it.id in down })
        assertEquals("many: only what's missing", true to listOf(b, c), SelectionPlan.download(listOf(a, b, c)) { it.id in down })
        assertEquals("all on the phone: remove them", false to listOf(a), SelectionPlan.download(listOf(a)) { it.id in down })
        assertEquals("files and episodes aren't downloaded", true to listOf(b), SelectionPlan.download(listOf(b, file, episode)) { false })
    }

    @Test fun playlistsTakeAllButEpisodes() {
        assertEquals(listOf(a, file), SelectionPlan.forPlaylist(listOf(a, file, episode)))
    }

    // ---- the rows

    @Test fun longPressPicksTapsPickMoreAndPlayingWaits() {
        val s = SongSelection()
        var played = 0
        compose.setContent {
            CompositionLocalProvider(LocalRowMarks provides marks, LocalSongSelection provides s) {
                SelectableSongs(listOf(a, b, c)) {
                    LazyColumn { items(listOf(a, b, c)) { song -> SongRow(song) { played++ } } }
                }
            }
        }
        compose.onAllNodesWithTag("pick", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("Alpha").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(setOf("a"), s.picked!!.keys)
        compose.onAllNodesWithTag("pick", useUnmergedTree = true).assertCountEquals(3)
        compose.onNodeWithText("Bravo").performClick()
        compose.waitForIdle()
        assertEquals("a tap picks rather than plays", setOf("a", "b"), s.picked!!.keys)
        assertEquals(0, played)
        compose.onAllNodesWithContentDescription("Picked").assertCountEquals(2)
        s.clear()
        compose.waitForIdle()
        compose.onAllNodesWithTag("pick", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("Charlie").performClick()
        assertEquals("picking over: a tap plays again", 1, played)
    }

    /** Home's shelves, an artist's top songs: their rows aren't pickable, even while a list elsewhere is picking. */
    @Test fun aListNotTakingPartIsLeftAsItWas() {
        val s = SongSelection()
        var played = 0
        compose.setContent {
            CompositionLocalProvider(LocalRowMarks provides marks, LocalSongSelection provides s) {
                androidx.compose.foundation.layout.Column {
                    SelectableSongs(listOf(a)) { SongRow(a) {} }
                    SongRow(b) { played++ }
                }
            }
        }
        compose.onNodeWithText("Alpha").performTouchInput { longClick() }
        compose.waitForIdle()
        assertTrue(s.selecting)
        compose.onAllNodesWithTag("pick", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("Bravo").performClick()
        assertEquals("a tap there still plays", 1, played)
        assertEquals(setOf("a"), s.picked!!.keys)
    }

    @Test fun leavingTheListEndsThePicking() {
        val s = SongSelection()
        val shown = mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalRowMarks provides marks, LocalSongSelection provides s) {
                if (shown.value) SelectableSongs(listOf(a)) { SongRow(a) {} }
            }
        }
        compose.onNodeWithText("Alpha").performTouchInput { longClick() }
        compose.waitForIdle()
        assertTrue(s.selecting)
        shown.value = false
        compose.waitForIdle()
        assertFalse(s.selecting)
    }
}
