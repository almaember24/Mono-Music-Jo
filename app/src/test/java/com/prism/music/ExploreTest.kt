package com.prism.music

import com.prism.music.data.innertube.ChartCountry
import com.prism.music.data.innertube.Explore
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.MoodItem
import com.prism.music.data.model.PodcastItem
import com.prism.music.data.model.Shelf
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.data.prefs.SearchSection
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Explore's pages, in the shape YouTube sends them (trimmed from live answers, 2026-11-05). */
class ExploreTest {
    private val album = AlbumItem("MPREb_a", "Album", "Album • Someone", null, null)

    @Test fun shelvesAreToldApartByWhatsInThemNotTheirTitles() {
        val shown = Explore.music(
            listOf(
                Shelf("", items = listOf(MoodItem("FEmusic_new_releases", "Nuevos lanzamientos", "", 0), MoodItem("FEmusic_charts", "Listas", "", 0))),
                Shelf("Nuevos álbumes", items = listOf(album)),
                Shelf("Estados de ánimo", items = listOf(MoodItem("FEmusic_moods_and_genres_category", "Chill", "abc", 0))),
                Shelf("Episodios populares", items = listOf(SongItem(Song("e", "Episode", emptyList(), isEpisode = true)))),
                Shelf("Programas", items = listOf(PodcastItem("MPSP", "Show", "", null))),
                Shelf("Tendencias", items = listOf(SongItem(Song("s", "Song", emptyList())))),
                Shelf("Empty", items = emptyList()),
            ),
        )
        assertEquals(listOf("Nuevos álbumes", "Estados de ánimo", "Tendencias"), shown.map { it.title })
        assertTrue(Explore.isMoods(shown[1]))
        assertFalse(Explore.isMoods(shown[0]))
    }

    private fun chartsAnswer(selected: String, extra: String = "") = """{
      "contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
        {"musicShelfRenderer":{"subheaders":[{"musicSideAlignedItemRenderer":{"startItems":[{"musicSortFilterButtonRenderer":{
          "title":{"runs":[{"text":"Brazil"}]},
          "menu":{"musicMultiSelectMenuRenderer":{"options":[
            {"musicMultiSelectMenuItemRenderer":{"title":{"runs":[{"text":"United States"}]},"formItemEntityKey":"k-us"}},
            {"musicMultiSelectMenuItemRenderer":{"title":{"runs":[{"text":"Brazil"}]},"formItemEntityKey":"k-br"}},
            {"musicMultiSelectMenuItemRenderer":{"title":{"runs":[{"text":"Nowhere"}]},"formItemEntityKey":"k-unknown"}}
          ]}}}}]}}]}}
        $extra
      ]}}}}]}},
      "frameworkUpdates":{"entityBatchUpdate":{"mutations":[
        {"entityKey":"k-us","payload":{"musicFormBooleanChoice":{"id":"k-us","selected":${selected == "US"},"opaqueToken":"US"}}},
        {"entityKey":"k-br","payload":{"musicFormBooleanChoice":{"id":"k-br","selected":${selected == "BR"},"opaqueToken":"BR"}}}
      ]}}
    }"""

    @Test fun theCountriesComeFromTheMenuWithTheCodesToAskFor() {
        val page = Explore.charts(Json.parseToJsonElement(chartsAnswer("BR")))
        assertEquals(listOf(ChartCountry("US", "United States"), ChartCountry("BR", "Brazil")), page.countries)
        assertEquals(ChartCountry("BR", "Brazil"), page.selected)
    }

    @Test fun whenNoChoiceIsMarkedTheMenusOwnNameSays() {
        assertEquals("BR", Explore.charts(Json.parseToJsonElement(chartsAnswer("none"))).selected?.code)
    }

    @Test fun anAnswerWithoutAMenuStillHasWhatItHas() {
        val page = Explore.charts(Json.parseToJsonElement("""{"contents":{}}"""))
        assertTrue(page.countries.isEmpty())
        assertNull(page.selected)
        assertTrue(page.shelves.isEmpty())
    }

    @Test fun searchsNewLineIsOfferedButNotPushedOnAnArrangedPage() {
        assertEquals(SearchSection.EXPLORE, SearchSection.entries.last())
        assertTrue("as a new install opens", SearchSection.EXPLORE in SearchSection.parse(null))
        assertFalse("an arranged list stays as it was", SearchSection.EXPLORE in SearchSection.parse("RECENT,ARTISTS,MOODS"))
    }
}
