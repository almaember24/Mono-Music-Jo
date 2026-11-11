package com.prism.music

import com.prism.music.ui.screens.speedDialPages
import org.junit.Assert.assertEquals
import org.junit.Test

/** #267 (1.7.9.3): Speed dial's last page held one tile; pages are whole now. */
class SpeedDialPagesTest {
    private fun sizes(n: Int) = speedDialPages((1..n).toList()).map { it.size }

    @Test fun onlyWholePages() {
        assertEquals(listOf(9, 9), sizes(19))
        assertEquals(listOf(9, 9, 9), sizes(27))
        assertEquals(listOf(9), sizes(10))
    }

    @Test fun lessThanAPageShowsAsItIs() {
        assertEquals(listOf(4), sizes(4))
        assertEquals(listOf(9), sizes(9))
        assertEquals(emptyList<Int>(), sizes(0))
    }

    /** #349: with "YouTube Music's only" nothing may fill the last page, and cutting it hid YouTube's own tiles. */
    @Test fun youTubeOnlyKeepsItsShortLastPage() {
        fun kept(n: Int) = speedDialPages((1..n).toList(), keepShortPage = true).map { it.size }
        assertEquals(listOf(9, 9, 2), kept(20))
        assertEquals(listOf(9, 9), kept(18))
        assertEquals(listOf(4), kept(4))
        // Every tile YouTube sent, in its order.
        assertEquals((1..20).toList(), speedDialPages((1..20).toList(), keepShortPage = true).flatten())
    }
}
