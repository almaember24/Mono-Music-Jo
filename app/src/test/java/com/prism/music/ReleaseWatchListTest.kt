package com.prism.music

import com.prism.music.data.foryou.ReleaseRadar
import com.prism.music.data.foryou.ReleaseRadar.FavoriteRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.8.1, Reddit (blinkertoon, 1.7.9): "New from your artists" missed releases from some of the listener's artists. The
 * followed and library artists came first and the list stopped at 150, so the artists actually played lately were
 * never looked at.
 */
class ReleaseWatchListTest {
    private fun refs(prefix: String, n: Int) = (1..n).map { FavoriteRef("$prefix$it", "$prefix $it") }

    @Test fun playedArtistsAreLookedAtWhateverYouFollow() {
        val played = refs("played", 30)
        val list = ReleaseRadar.watchList(refs("star", 5), played, refs("follow", 400), refs("lib", 300), turn = 0)
        assertTrue(list.map { it.id }.containsAll(played.map { it.id }))
        assertTrue(list.size <= ReleaseRadar.MAX_ARTISTS)
    }

    @Test fun everyFollowedArtistComesUpWithinAFewLooks() {
        val follow = refs("follow", 400)
        val seen = HashSet<String>()
        for (turn in 0 until 3) seen += ReleaseRadar.watchList(refs("star", 5), refs("played", 30), follow, emptyList(), turn).map { it.id }
        assertEquals(follow.map { it.id }.toSet(), seen.filter { it.startsWith("follow") }.toSet())
    }

    @Test fun excludedArtistsAreLeftOut() {
        val list = ReleaseRadar.watchList(refs("star", 2), refs("played", 3), emptyList(), emptyList(), 0) { it.id == "played2" }
        assertEquals(listOf("star1", "star2", "played1", "played3"), list.map { it.id })
    }
}
