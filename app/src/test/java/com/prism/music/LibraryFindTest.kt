package com.prism.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.prism.music.ui.components.FastScroller
import com.prism.music.ui.screens.albumNames
import com.prism.music.ui.screens.libraryMatches
import com.prism.music.ui.screens.scrollLetter
import com.prism.music.ui.screens.scrollMarks
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
 * #357: Library's tabs filter as you type. #376: the finger-sized slider on Library's long lists (grids) and Search's: the
 * grip jumps through the list, and the rest of the edge is still the list's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h640dp-xhdpi")
class LibraryFindTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aSearchFindsNamesAsTheyAreWritten() {
        assertTrue(libraryMatches("road", "Road trip"))
        assertTrue("any case", libraryMatches("ROAD", "Road trip"))
        assertTrue("any order", libraryMatches("trip road", "Road trip"))
        assertTrue("accents", libraryMatches("beyonce", "Beyoncé"))
        assertTrue("punctuation", libraryMatches("ac dc", "AC/DC"))
        assertTrue("another script", libraryMatches("夏", "夏の歌"))
        assertTrue("across names", libraryMatches("nevermind nirvana", "Nevermind", "Nirvana"))
        assertFalse(libraryMatches("road trap", "Road trip"))
        assertFalse(libraryMatches("gym", "Road trip"))
        assertTrue("only punctuation still looks", libraryMatches("!!!", "!!!"))
        assertFalse(libraryMatches("!!!", "Road trip"))
    }

    @Test fun anAlbumsKindIsntPartOfItsNames() {
        assertEquals(listOf("An artist", "2024"), albumNames("Album • An artist • 2024", "Sencillo"))
        assertEquals(listOf("An artist"), albumNames("Sencillo • An artist", "Sencillo"))
        assertEquals(listOf("An artist"), albumNames("An artist", "Single"))
    }

    @Test fun theBubbleSaysALetterOrAPlace() {
        assertEquals("R", scrollLetter(" road"))
        assertEquals("夏", scrollLetter("夏の歌"))
        assertEquals("a whole character, not half of one", "🎵", scrollLetter("🎵 songs"))
        assertNull(scrollLetter("  "))
        assertEquals(mapOf("x" to "A", "y" to "B"), scrollMarks(listOf("x" to "apple", "y" to "bee"), { it.first }, { it.second }))
        assertEquals(mapOf("x" to "1", "y" to "2"), scrollMarks(listOf("x", "y"), { it }, { null }))
    }

    private val description = "Scroll through the list"

    private fun grid(state: LazyGridState) = compose.setContent {
        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize(), state = state) {
                items(400) { i -> Text("Item $i", Modifier.fillMaxWidth().height(80.dp)) }
            }
            FastScroller(state, Modifier.align(Alignment.CenterEnd), description = description, thumbOnly = true) { null }
        }
    }

    @Test fun theGripJumpsThroughAGrid() {
        val state = LazyGridState()
        grid(state)
        // It shows once the list moves.
        compose.onRoot().performTouchInput { swipeUp(startY = centerY + 200f, endY = centerY - 200f) }
        compose.waitForIdle()
        val before = state.firstVisibleItemIndex
        compose.onNodeWithContentDescription(description).performTouchInput { swipeDown(startY = centerY, endY = centerY + 600f) }
        compose.waitForIdle()
        assertTrue("a long way down: $before -> ${state.firstVisibleItemIndex}", state.firstVisibleItemIndex > before + 60)
    }

    @Test fun theEdgeAwayFromTheGripIsStillTheList() {
        val rows = ArrayList<Int>()
        val state = LazyListState()
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), state = state) {
                    items((0 until 400).toList()) { i -> Text("Row $i", Modifier.fillMaxWidth().height(80.dp).clickable { rows += i }) }
                }
                FastScroller(state, Modifier.align(Alignment.CenterEnd), description = description, thumbOnly = true) { null }
            }
        }
        compose.onRoot().performTouchInput { swipeUp(startY = centerY + 200f, endY = centerY - 200f) }
        compose.waitForIdle()
        val before = state.firstVisibleItemIndex
        // At the screen's right edge, well below the thumb, which is near the top: an ordinary scroll, not a jump.
        compose.onRoot().performTouchInput { swipe(start = androidx.compose.ui.geometry.Offset(right - 8f, bottom - 40f), end = androidx.compose.ui.geometry.Offset(right - 8f, bottom - 440f), durationMillis = 500) }
        compose.waitForIdle()
        val moved = state.firstVisibleItemIndex - before
        assertTrue("the list scrolled by the finger, about 2 rows ($moved)", moved in 1..30)
        // And a tap there reaches the row, the thumb showing or not.
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(right - 8f, bottom - 40f)) }
        compose.waitForIdle()
        assertEquals(1, rows.size)
    }

    @Test fun aShortListHasNoSlider() {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                val state = LazyListState()
                LazyColumn(Modifier.fillMaxSize(), state = state) { items((0 until 20).toList()) { i -> Text("Row $i", Modifier.height(80.dp)) } }
                FastScroller(state, Modifier.align(Alignment.CenterEnd), description = description, thumbOnly = true) { null }
            }
        }
        compose.onRoot().performTouchInput { swipeUp(startY = centerY + 200f, endY = centerY - 200f) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(description).assertDoesNotExist()
    }
}
