package com.prism.music

import com.prism.music.data.Letterbox
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** GitHub #92: a video thumbnail's black bars are found and trimmed, and its middle cut square. */
class VideoCoverTest {
    private val black = 0xFF050505.toInt()
    private val picture = 0xFF8040C0.toInt()

    /** A [w]×[h] frame, black except the picture from [top] to [bottom] (exclusive) and [left] to [right]. */
    private fun frame(w: Int, h: Int, left: Int, top: Int, right: Int, bottom: Int) =
        IntArray(w * h) { i -> val x = i % w; val y = i / w; if (x in left until right && y in top until bottom) picture else black }

    @Test fun youTubeThumbnailLosesItsBars() {
        // hqdefault.jpg: 480×360 with a 16:9 picture of 480×270 in the middle.
        val box = Letterbox.content(frame(480, 360, 0, 45, 480, 315), 480, 360)
        assertArrayEquals(intArrayOf(0, 45, 480, 315), box)
        assertArrayEquals(intArrayOf(105, 45, 375, 315), Letterbox.square(box))
    }

    @Test fun pillarboxedFrameLosesItsSides() {
        val box = Letterbox.content(frame(400, 300, 50, 0, 350, 300), 400, 300)
        assertArrayEquals(intArrayOf(50, 0, 350, 300), box)
    }

    @Test fun aDarkSkyAtTheTopIsNotTakenForABar() {
        // Black for 60 rows at the top only (night sky), nothing at the bottom: not a letterbox, nothing trimmed.
        val box = Letterbox.content(frame(480, 360, 0, 60, 480, 360), 480, 360)
        assertArrayEquals(intArrayOf(0, 0, 480, 360), box)
    }

    @Test fun anAllBlackFrameKeepsItsMiddle() {
        val box = Letterbox.content(IntArray(300 * 300) { black }, 300, 300)
        assertArrayEquals(intArrayOf(100, 100, 200, 200), box)
    }

    @Test fun aFullFrameIsLeftAlone() {
        assertArrayEquals(intArrayOf(0, 0, 320, 180), Letterbox.content(frame(320, 180, 0, 0, 320, 180), 320, 180))
    }
}
