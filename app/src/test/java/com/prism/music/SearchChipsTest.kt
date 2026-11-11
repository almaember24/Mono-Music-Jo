package com.prism.music

import com.prism.music.data.innertube.SearchFilter
import com.prism.music.ui.screens.chipName
import com.prism.music.ui.screens.chipOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/** #224: Search's chips in the listener's order, the first being where Search opens. */
class SearchChipsTest {
    @Test
    fun prismsOrderUntilYouChooseOne() {
        assertEquals(SearchFilter.entries + null, chipOrder(emptyList()))
    }

    @Test
    fun yourOrderFirstThenTheRest() {
        val order = chipOrder(listOf("PHONE", "SONGS"))
        assertEquals(null, order[0])
        assertEquals(SearchFilter.SONGS, order[1])
        assertEquals(SearchFilter.TOP, order[2])
        // Every chip shows once, including ones a later version adds.
        assertEquals((SearchFilter.entries + null).toSet(), order.toSet())
        assertEquals(order.size, order.distinct().size)
    }

    @Test
    fun namesItDoesntKnowAreDropped() {
        assertEquals(SearchFilter.ALBUMS, chipOrder(listOf("GONE", "ALBUMS", "ALBUMS"))[0])
        assertEquals(listOf("PHONE", "TOP"), chipOrder(listOf("PHONE")).take(2).map(::chipName))
    }

    @Test
    fun labelsAreTranslatable() {
        // They come from strings.xml (they were English in every language before 1.7.9.2).
        assertEquals("Top results", SearchFilter.TOP.label)
        assertEquals("Community playlists", SearchFilter.PLAYLISTS.label)
    }
}
