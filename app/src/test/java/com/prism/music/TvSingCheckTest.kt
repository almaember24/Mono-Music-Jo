package com.prism.music

import com.prism.music.playback.sing.Separation
import com.prism.music.playback.sing.SingPace
import com.prism.music.ui.tv.TvSing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sing on more devices (1.8.x): which way a device separates songs, from how fast the networks go on it, and what the
 * TV's speed check makes of its timings. A device too slow gets the quick way, never nothing.
 */
class TvSingCheckTest {
    @Test fun theFullWayWhereItsWellAheadTheLightWayWhereItKeepsUp() {
        // The A16 (64-bit): 7.4× real time with both networks.
        assertEquals(Separation.FULL, SingPace.choose(true, 7.4, null))
        // Just ahead with both (1.5×), the light one is 2.25× by the estimate: keeps up with room to spare.
        assertEquals(Separation.LIGHT, SingPace.choose(true, 1.5, null))
        // Measured, the light one counts as measured.
        assertEquals(Separation.LIGHT, SingPace.choose(true, 1.0, 1.4))
        assertEquals(Separation.QUICK, SingPace.choose(true, 1.0, 1.2))
        // Too slow for either: the quick way.
        assertEquals(Separation.QUICK, SingPace.choose(true, 0.6, null))
        // Only the light one timed, fast: the full one's worth trying again (3.2 / 1.5 ≈ 2.1).
        assertEquals(Separation.FULL, SingPace.choose(true, null, 3.2))
        assertEquals(Separation.LIGHT, SingPace.choose(true, null, 2.5))
    }

    @Test fun nothingTimedYetStartsWithTheFullWay() {
        // It's timed from its first chunk and steps down within the song if it's too slow.
        assertEquals(Separation.FULL, SingPace.choose(true, null, null))
    }

    @Test fun withoutTheEngineOnlyTheQuickWay() {
        assertEquals(Separation.QUICK, SingPace.choose(false, 9.0, 9.0))
        assertEquals(Separation.QUICK, SingPace.choose(false, null, null))
    }

    @Test fun recentTimingsCountMost() {
        assertEquals(2.0, SingPace.blend(null, 2.0), 1e-9)
        assertEquals(0.6 * 3.0 + 0.4 * 1.0, SingPace.blend(3.0, 1.0), 1e-9)
        assertEquals(11.89, SingPace.speed(11.89, 1000), 1e-9)
    }

    @Test fun theTvCheckOffersTheQuickWayRatherThanRefusing() {
        assertEquals(TvSing.State.READY, TvSing.verdict(2.4, 3.6))
        assertEquals(TvSing.State.READY, TvSing.verdict(0.95, 1.45))
        // Too slow: the quick way, and Sing is offered all the same.
        assertEquals(TvSing.State.SLOW, TvSing.verdict(0.5, 0.8))
        assertTrue(TvSing.State.SLOW.sings)
        assertTrue(TvSing.State.UNSUPPORTED.sings)
        assertTrue(TvSing.State.READY.sings)
        // The owner's TV (1.8.x): Sing wasn't in the player's ⋯ till the check had run. Unchecked, it is (the first use
        // times the TV as it goes, the quick way standing in); only while the check runs it isn't.
        assertTrue(TvSing.State.UNCHECKED.sings)
        assertFalse(TvSing.State.CHECKING.sings)
        // Nothing timed (the song couldn't be had): no answer to keep.
        assertNull(TvSing.verdict(null, null))
    }

    @Test fun quickStandInsComeFirstForThePlayingSongAndTheNext() {
        val quick = mutableSetOf<String>()
        val own = mutableSetOf<String>()
        fun next(ids: List<String>, ownWay: Boolean = true) =
            com.prism.music.playback.sing.Sing.nextPass(ids, { it in quick }, { ownWay && it !in own })
        val ids = listOf("playing", "next", "later")
        // The playing song's stand-in, then the next one's, before either's own separation.
        assertEquals("playing" to true, next(ids)); quick += "playing"
        assertEquals("next" to true, next(ids)); quick += "next"
        // Then the playing song's own, then the next one's.
        assertEquals("playing" to false, next(ids)); own += "playing"
        assertEquals("next" to false, next(ids)); own += "next"
        // A later song: its stand-in just before its own.
        assertEquals("later" to true, next(ids)); quick += "later"
        assertEquals("later" to false, next(ids)); own += "later"
        assertNull(next(ids))
        // Where only the quick way runs, the stand-ins are all there is to do.
        quick.clear(); own.clear()
        assertEquals("playing" to true, next(ids, ownWay = false)); quick += ids
        assertNull(next(ids, ownWay = false))
    }
}
