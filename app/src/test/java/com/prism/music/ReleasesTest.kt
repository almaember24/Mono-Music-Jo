package com.prism.music

import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.Releases
import org.junit.Assert.assertEquals
import org.junit.Test

/** Discography ordering: YouTube's "See all" lists tack live albums and remixes on at the end. */
class ReleasesTest {
    private fun a(title: String, sub: String) = AlbumItem(title, title, sub, null)

    // Kid Cudi's "Singles & EPs" as YouTube served it (2026-10-11): newest first, then a run of remixes.
    private val served = listOf(
        a("HAVE U BN 2 HEAVEN @ NITE?", "EP • 2026"),
        a("Amnesia", "Single • 2026"),
        a("Mr. Miracle", "Single • 2025"),
        a("Make Her Say", "Single • 2009"),
        a("Amnesia (Remix)", "Single • 2026"),
        a("Deep Diving (Remix)", "Single • 2025"),
        a("Day 'N' Nite (Crookers Remix)", "Single • 2008"),
        a("No year", "Single"),
    )

    @Test
    fun newestFirstIsStableWithinAYear() {
        assertEquals(
            listOf("HAVE U BN 2 HEAVEN @ NITE?", "Amnesia", "Amnesia (Remix)", "Mr. Miracle", "Deep Diving (Remix)", "Make Her Say", "Day 'N' Nite (Crookers Remix)", "No year"),
            Releases.newestFirst(served).map { it.title },
        )
    }

    @Test
    fun oldestFirstPutsUndatedLast() {
        val sorted = Releases.oldestFirst(served).map { it.title }
        assertEquals("Day 'N' Nite (Crookers Remix)", sorted.first())
        assertEquals("No year", sorted.last())
    }

    @Test
    fun artistPageSubtitlesAreJustTheYear() {
        assertEquals(2024, Releases.yearOf(a("INSANO", "2024")))
    }
}
