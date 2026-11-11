package com.prism.music

import com.prism.music.ui.player.BestPartButton
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the player's "Best part" button is up. */
class BestPartButtonTest {
    @Test
    fun upInTheFirstThirtySeconds() {
        assertTrue(BestPartButton.shows(atMs = 5_000, startMs = 60_000, knownAtMs = 1_000))
        assertTrue(BestPartButton.shows(atMs = 29_000, startMs = 60_000, knownAtMs = 1_000))
        assertFalse(BestPartButton.shows(atMs = 31_000, startMs = 60_000, knownAtMs = 1_000))
    }

    @Test
    fun foundLateItStillGetsItsTime() {
        // Known at 25 s (a slow network): up until 40 s rather than gone 5 s later.
        assertTrue(BestPartButton.shows(atMs = 38_000, startMs = 90_000, knownAtMs = 25_000))
        assertFalse(BestPartButton.shows(atMs = 41_000, startMs = 90_000, knownAtMs = 25_000))
        // Never past 45 s.
        assertFalse(BestPartButton.shows(atMs = 46_000, startMs = 120_000, knownAtMs = 40_000))
    }

    @Test
    fun goneOnceTheBestPartIsHere() {
        assertFalse(BestPartButton.shows(atMs = 20_000, startMs = 22_000, knownAtMs = 0))
        assertFalse(BestPartButton.shows(atMs = 25_000, startMs = 22_000, knownAtMs = 0))
    }
}
