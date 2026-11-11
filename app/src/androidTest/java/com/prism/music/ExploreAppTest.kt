package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.prefs.SearchSection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explore (1.8.2) in the app, with Explore's and Charts' answers TYPED BY HAND in the shape seen live (2026-11-05, signed
 * out; [ExploreLiveTest] and [ExploreLiveInAppTest] ask the real thing). Everything here checks what's drawn when a section
 * exists, when it doesn't, and when asking fails.
 */
@RunWith(AndroidJUnit4::class)
class ExploreAppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val c get() = Fixture.container

    private fun wrapped(vararg sections: String) =
        """{"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[${sections.joinToString(",")}]}}}}]}}}"""

    private fun albums(shelf: String, album: String) =
        """{"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"$shelf"}]}}},"contents":[
          {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"$album"}]},"subtitle":{"runs":[{"text":"Album"},{"text":" • "},{"text":"2026"}]},
            "navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_${album.hashCode()}","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}}}]}}"""

    private fun songs(shelf: String, vararg titles: String) =
        """{"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"$shelf"}]}}},"contents":[${titles.joinToString(",") { t ->
            """{"musicResponsiveListItemRenderer":{"flexColumns":[
              {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$t"}]}}},
              {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Some Artist"}]}}}
            ],"playlistItemData":{"videoId":"tst${t.hashCode().toUInt()}"}}}"""
        }}]}}"""

    private fun moods(shelf: String, vararg names: String) =
        """{"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"$shelf"}]}}},"contents":[${names.joinToString(",") { n ->
            """{"musicNavigationButtonRenderer":{"buttonText":{"runs":[{"text":"$n"}]},"solid":{"leftStripeColor":"4286578816"},"clickCommand":{"browseEndpoint":{"browseId":"FEmusic_moods_and_genres_category","params":"p$n"}}}}"""
        }}]}}"""

    /** Explore's row of buttons to pages of their own: the page these lead to is the one being shown, so they aren't drawn. */
    private val buttons = """{"gridRenderer":{"items":[
        {"musicNavigationButtonRenderer":{"buttonText":{"runs":[{"text":"New releases button"}]},"clickCommand":{"browseEndpoint":{"browseId":"FEmusic_new_releases"}}}}
      ]}}"""

    private val explorePage = wrapped(buttons, albums("New albums and singles", "Fresh Album"), moods("Moods and genres", "Chill Tile", "Focus Tile"), songs("Trending", "Trending Song One"))

    /** The charts as Brazil's or the US's: the menu of countries, then that country's shelves (the two differ, as they really do). */
    private fun chartsAnswer(country: String, vararg shelves: String): String {
        val name = if (country == "BR") "Brazil" else "United States"
        val menu = """{"musicShelfRenderer":{"subheaders":[{"musicSideAlignedItemRenderer":{"startItems":[{"musicSortFilterButtonRenderer":{
          "title":{"runs":[{"text":"$name"}]},
          "menu":{"musicMultiSelectMenuRenderer":{"options":[
            {"musicMultiSelectMenuItemRenderer":{"title":{"runs":[{"text":"United States"}]},"formItemEntityKey":"k-us"}},
            {"musicMultiSelectMenuItemRenderer":{"title":{"runs":[{"text":"Brazil"}]},"formItemEntityKey":"k-br"}}
          ]}}}}]}}]}}"""
        val body = wrapped(menu, *shelves).removeSuffix("}")
        return """$body,"frameworkUpdates":{"entityBatchUpdate":{"mutations":[
          {"entityKey":"k-us","payload":{"musicFormBooleanChoice":{"id":"k-us","selected":${country == "US"},"opaqueToken":"US"}}},
          {"entityKey":"k-br","payload":{"musicFormBooleanChoice":{"id":"k-br","selected":${country == "BR"},"opaqueToken":"BR"}}}
        ]}}}"""
    }

    private var asBrazil: () -> String = { chartsAnswer("BR", albums("Top albums", "Brazil Album")) }

    @Before fun start() {
        RecordedNetwork.pages.clear()
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cmd media_session volume --stream 3 --set 0").close()
        Fixture.ready()
    }

    @After fun end() {
        RecordedNetwork.answering = null
        RecordedNetwork.pages.clear()
        runCatching { InstrumentationRegistry.getInstrumentation().runOnMainSync { c.player.stop() } }
    }

    /** Which country's charts a request is for by what it sends, as the real page answers. */
    private fun answerCharts() {
        RecordedNetwork.answering = { url, body ->
            when {
                !url.contains("/browse") || !body.contains("FEmusic_charts") -> null
                body.contains("\"BR\"") -> asBrazil()
                else -> chartsAnswer("US", albums("Top albums", "America Album"))
            }
        }
    }

    private fun ComposeTestRule.seeText(text: String, timeoutMs: Long = 20_000) {
        val match = hasText(text, substring = true)
        val end = System.currentTimeMillis() + timeoutMs
        while (onAllNodes(match).fetchSemanticsNodes().isEmpty()) {
            if (System.currentTimeMillis() > end) {
                val shown = onAllNodes(hasText("", substring = true)).fetchSemanticsNodes()
                    .mapNotNull { it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.joinToString() }
                throw AssertionError("Not on the page: \"$text\". On screen: ${shown.joinToString(" | ").take(1200)}")
            }
            onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().indices.forEach { i ->
                runCatching { onAllNodes(hasScrollToNodeAction())[i].performScrollToNode(match) }
            }
            waitForIdle()
            Thread.sleep(100)
        }
    }

    private fun openSearch() {
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("Search") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Search") and hasClickAction()).onFirst().performClick()
        compose.waitForIdle()
    }

    private fun openExplore() {
        Fixture.launch()
        openSearch()
        compose.seeText("Charts, new releases")
        compose.clickWhenFound(hasText("Explore") and hasClickAction())
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("explore")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun searchLeadsToEverySectionYouTubeSent() {
        RecordedNetwork.pages["FEmusic_explore"] = explorePage
        answerCharts()
        openExplore()
        compose.seeText("America Album")
        compose.seeText("United States")
        Fixture.screenshot("explore-top")
        compose.seeText("Fresh Album")
        compose.seeText("Chill Tile")
        compose.seeText("Trending Song One")
        Fixture.screenshot("explore-rest")
        assertTrue("its own buttons aren't drawn", compose.onAllNodes(hasText("New releases button")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun anotherCountrysChartsAreItsOwn() {
        RecordedNetwork.pages["FEmusic_explore"] = explorePage
        answerCharts()
        openExplore()
        compose.seeText("America Album")
        compose.clickWhenFound(hasText("United States") and hasClickAction())
        compose.waitForText("Choose a country")
        compose.clickWhenFound(hasText("Brazil") and hasClickAction())
        compose.seeText("Brazil Album")
        assertTrue("the US's have gone", compose.onAllNodes(hasText("America Album")).fetchSemanticsNodes().isEmpty())
        // Only for this visit: nothing about the country was kept in settings to surprise anyone.
        assertEquals(com.prism.music.data.prefs.AppSettings().contentCountry, c.settings.current.contentCountry)
    }

    @Test fun aCountryWithNoChartsSaysSo() {
        RecordedNetwork.pages["FEmusic_explore"] = explorePage
        asBrazil = { chartsAnswer("BR") }
        answerCharts()
        openExplore()
        compose.clickWhenFound(hasText("United States") and hasClickAction(), 15_000)
        compose.clickWhenFound(hasText("Brazil") and hasClickAction())
        compose.seeText("No charts for this country right now")
        compose.seeText("Fresh Album") // and the rest of the page stays
    }

    @Test fun chartsFailingLeavesTheRest() {
        RecordedNetwork.pages["FEmusic_explore"] = explorePage
        openExplore()
        compose.seeText("Fresh Album")
        compose.seeText("Try again")
    }

    @Test fun theRestFailingLeavesTheCharts() {
        answerCharts()
        openExplore()
        compose.seeText("America Album")
        compose.seeText("Try again")
    }

    @Test fun everythingFailingIsOneErrorAndTryAgainBringsItBack() {
        openExplore()
        compose.seeText("Try again")
        assertEquals(1, compose.onAllNodes(hasText("Try again")).fetchSemanticsNodes().size)
        RecordedNetwork.pages["FEmusic_explore"] = explorePage
        answerCharts()
        compose.clickWhenFound(hasText("Try again") and hasClickAction())
        compose.seeText("America Album")
        compose.seeText("Fresh Album")
    }

    /** A Search page arranged before Explore came keeps what it had; Arrange offers the new line. */
    @Test fun anArrangedSearchPageIsLeftAsItWas() {
        c.settings.setSearchSections(listOf(SearchSection.RECENT, SearchSection.MOODS))
        compose.waitUntil(5_000) { SearchSection.EXPLORE !in c.settings.current.searchSections }
        Fixture.launch()
        openSearch()
        compose.seeText("Arrange this page")
        assertFalse(compose.onAllNodes(hasText("Charts, new releases", substring = true)).fetchSemanticsNodes().isNotEmpty())
        compose.clickWhenFound(hasText("Arrange this page") and hasClickAction())
        compose.waitForText("Explore")
    }
}
