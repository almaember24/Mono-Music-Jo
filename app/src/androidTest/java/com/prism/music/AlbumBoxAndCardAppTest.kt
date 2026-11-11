package com.prism.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prism.music.data.Habits
import com.prism.music.data.Personality
import com.prism.music.data.db.ArtistPlays
import com.prism.music.data.db.SongPlays
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.ThemeMode
import com.prism.music.ui.screens.Period
import com.prism.music.ui.screens.ReplayStory
import com.prism.music.ui.screens.SummaryCard
import com.prism.music.ui.theme.PrismTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On a phone, with its own text drawing: Replay's summary card holds its whole title on one line ("TODA" on Reddit), and a
 * description with Wikipedia's credit is a closed box with its links under their words. The words and names are made up.
 */
@RunWith(AndroidJUnit4::class)
class AlbumBoxAndCardAppTest {
    @get:Rule val compose = createComposeRule()

    private fun story(title: String, includes: String?) = ReplayStory(
        period = Period.ALL, title = title, totalMs = 72_240_000, previousMs = null, previousLabel = null, plays = 300, activeDays = 12, songCount = 80, artistCount = 40,
        songs = listOf(SongPlays("a", 9, 900_000, "A song with a rather long title for its column", "An artist", null, null, 200, null)),
        artists = listOf(ArtistPlays("An artist with a long name", 9, 900_000, null, null), ArtistPlays("Another artist", 5, 500_000, null, null)),
        genres = listOf("Dance & electronic" to 900_000L), genreSongs = emptyMap(), habits = Habits(LongArray(24), LongArray(7), 3, null, 0),
        newArtists = null, personality = Personality("The Explorer", ""), topArtistImage = null, topArtistType = com.prism.music.ui.theme.ArtistTypography.standard,
        rewind = false, includes = includes,
    )

    @Test fun theCardsTitleIsWholeOnAPhone() {
        val titles = listOf("Today", "Сентябрь")
        compose.setContent {
            PrismTheme(AppSettings(themeMode = ThemeMode.DARK), artworkColor = null) {
                Column(Modifier.fillMaxSize().background(Color.Black).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SummaryCard(story(titles[0], null), null, Modifier.width(320.dp))
                    SummaryCard(story(titles[1], "Includes Spotify, Last.fm, YouTube Music"), null, Modifier.width(272.dp))
                }
            }
        }
        compose.waitForIdle()
        Fixture.screenshot("replay-card")
        for (t in titles) {
            val node = compose.onAllNodes(hasText(t.uppercase())).fetchSemanticsNodes().single()
            val results = ArrayList<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            val layout = results.single()
            assertEquals(t, 1, layout.lineCount)
            assertTrue("$t: ${layout.multiParagraph.getLineRight(0)} of ${node.size.width}", layout.multiParagraph.getLineRight(0) <= node.size.width + 1f)
        }
    }
}

/** The box itself, in Prism: a playlist of test songs with a long description, opened from Library. */
@RunWith(AndroidJUnit4::class)
class AlbumBoxInPrismTest {
    @get:Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    @Test fun aLongDescriptionIsAClosedBoxWithItsCredit() {
        Fixture.ready()
        val body = "An imagined record, made over two winters in a small town by the sea. ".repeat(8).trim()
        Fixture.container.prismLibrary.create(
            "Test long player", Fixture.songs(3),
            "$body\n\nFrom Wikipedia (https://en.wikipedia.org/wiki/Imagined_(album)) under Creative Commons Attribution CC-BY-SA 3.0 (https://creativecommons.org/licenses/by-sa/3.0/legalcode)",
        )
        Fixture.launch()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Library") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Library") and hasClickAction()).onFirst().performClick()
        repeat(8) {
            if (compose.onAllNodes(hasText("Test long player")).fetchSemanticsNodes().isNotEmpty()) return@repeat
            compose.onRoot().performTouchInput { swipeUp(startY = centerY + height * 0.2f, endY = centerY - height * 0.2f) }
            compose.waitForIdle()
        }
        compose.clickWhenFound(hasText("Test long player") and hasClickAction(), 15_000)
        compose.waitForText("Show more")
        // Play stays above the box: under it, a long description pushed Play behind the mini player.
        val play = compose.onAllNodes(hasText("Play") and hasClickAction()).fetchSemanticsNodes().single().boundsInRoot
        val more = compose.onAllNodes(hasText("Show more", substring = true)).fetchSemanticsNodes().first().boundsInRoot
        assertTrue("Play at ${play.bottom}, the box's Show more at ${more.top}", play.bottom <= more.top)
        assertTrue("the credit's there", compose.onAllNodes(hasText("From Wikipedia", substring = true)).fetchSemanticsNodes().isNotEmpty())
        assertTrue("its address isn't read out", compose.onAllNodes(hasText("https", substring = true)).fetchSemanticsNodes().isEmpty())
        Fixture.screenshot("album-box-closed")
        compose.clickWhenFound(hasText("Show more") and hasClickAction())
        compose.waitForText("Show less")
        compose.waitForIdle()
        Fixture.screenshot("album-box-open")
    }
}
