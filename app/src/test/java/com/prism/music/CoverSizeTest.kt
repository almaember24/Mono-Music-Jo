package com.prism.music

import androidx.compose.ui.unit.dp
import com.prism.music.ui.player.coverBox
import com.prism.music.ui.player.pausedCoverScale
import org.junit.Assert.assertEquals
import org.junit.Test

/** #276: the player's cover sizes, as the Standard layout lays them out on a phone. */
class CoverSizeTest {
    // A 412 dp wide phone: the cover's box between the 30 dp margins, and the height it has to itself.
    private val box = 352.dp
    private val short = 430.dp
    private val tall = 700.dp

    @Test fun fullWidthMeetsTheScreensSidesOnAShortPlayer() {
        // Too short for a 412 dp square: Full width used to come out as tall as the room allowed, margins and all.
        val (w, h) = coverBox(box, short, 30.dp, full = true, titleRoom = 80.dp, least = 54.dp)
        assertEquals("edge to edge", 412f, w.value, 0.01f)
        assertEquals("as tall as there's room for", 350f, h.value, 0.01f)
        // Large stays a square in the same room.
        val (lw, lh) = coverBox(box, short, 18.dp, full = false, titleRoom = 80.dp, least = 54.dp)
        assertEquals(lw, lh)
        // With the room for it, Full width is a square across the screen.
        val (tw, th) = coverBox(box, tall, 30.dp, full = true, titleRoom = 80.dp, least = 54.dp)
        assertEquals(412f, tw.value, 0.01f); assertEquals(412f, th.value, 0.01f)
    }

    @Test fun aFullWidthCoverDoesNotShrinkWhenPaused() {
        assertEquals(1f, pausedCoverScale(playing = false, shrinkWhenPaused = true, ownReduceMotion = false, fullWidth = true), 0.001f)
        assertEquals(0.86f, pausedCoverScale(playing = false, shrinkWhenPaused = true, ownReduceMotion = false, fullWidth = false), 0.001f)
    }
}
