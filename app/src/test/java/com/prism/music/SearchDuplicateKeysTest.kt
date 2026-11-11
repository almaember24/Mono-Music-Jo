package com.prism.music

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import com.prism.music.data.RankedCategories
import com.prism.music.data.model.MoodItem
import com.prism.music.ui.screens.moodGroups
import com.prism.music.ui.screens.moodSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #346 / #330: Search closed as it opened in Italian. Prism's own mood group is "Per te" there, and so is YouTube's
 * own "For you" section, which the landing only left out by its English name; both headings were keyed "mh:Per te",
 * and a lazy list refuses a key twice ("Key "mh:Per te" was already used").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SearchDuplicateKeysTest {
    @get:Rule val compose = createComposeRule()

    private fun mood(id: String, title: String) = MoodItem(id, title, "p$id", 0xFF3366CC)

    @Test fun theOldTitleKeysCrashedTheList() {
        val error = runCatching {
            compose.setContent {
                LazyColumn(Modifier.size(360.dp, 640.dp)) {
                    listOf("Per te", "Per te").forEach { title -> item(key = "mh:$title") { Text(title) } }
                }
            }
            compose.waitForIdle()
        }.exceptionOrNull()
        assertTrue("the same key twice is refused (the bug's crash): $error", error?.message.orEmpty().contains("was already used"))
    }

    @Test fun twoGroupsWithTheSameTitleBothShow() {
        val groups = listOf(
            "Per te" to listOf(mood("a", "Relax"), mood("b", "Energia")),
            "Per te" to listOf(mood("c", "Festa")),
            "Generi" to listOf(mood("d", "Rock")),
        )
        compose.setContent {
            LazyColumn(Modifier.size(360.dp, 2000.dp)) { moodSections(groups, onMore = {}, onMood = {}) }
        }
        compose.waitForIdle()
        listOf("Relax", "Energia", "Festa", "Rock").forEach { t ->
            assertEquals("$t is shown", 1, compose.onAllNodesWithText(t).fetchSemanticsNodes().size)
        }
    }

    @Test fun youTubesOwnForYouSectionIsLeftOutInTheListenersLanguage() {
        val ranked = RankedCategories(
            picks = listOf(mood("a", "Relax")),
            sections = listOf("Per te" to listOf(mood("b", "Energia")), "Generi" to listOf(mood("a", "Relax"), mood("d", "Rock"))),
        )
        val groups = moodGroups(ranked, forYou = "Per te")
        assertEquals(listOf("Per te", "Generi"), groups.map { it.first })
        assertEquals("a tile already in Prism's group isn't shown again", listOf("Rock"), groups[1].second.map { it.title })
        // English still leaves YouTube's out by its own words.
        val english = moodGroups(ranked.copy(sections = listOf("Moods for you" to listOf(mood("b", "Energia")))), forYou = "For you")
        assertEquals(listOf("For you"), english.map { it.first })
    }
}
