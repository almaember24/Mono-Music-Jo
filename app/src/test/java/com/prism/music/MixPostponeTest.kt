package com.prism.music

import com.prism.music.playback.MixEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/** A blend whose tail isn't in step as it comes up is put off a little, not dropped. */
class MixPostponeTest {
    @Test
    fun putsOffTwoSecondsTwiceAtMost() {
        assertEquals(2000, MixEngine.postponeMs(12_000, 0, 0.0))
        assertEquals(2000, MixEngine.postponeMs(10_000, 1, 0.0))
        assertEquals(0, MixEngine.postponeMs(8_000, 2, 0.0))
    }

    @Test
    fun keepsAtLeastThreeSecondsOfBlend() {
        assertEquals(2000, MixEngine.postponeMs(5_000, 0, 0.0))
        assertEquals(0, MixEngine.postponeMs(4_999, 0, 0.0))
    }

    @Test
    fun beatMatchedBlendsMoveByWholeBeats() {
        // 120 BPM: 500 ms beats, so 2000 ms is exactly four of them.
        assertEquals(2000, MixEngine.postponeMs(12_000, 0, 500.0))
        // 85.5 BPM (701.75 ms beats): three beats, the first whole number of beats past two seconds.
        assertEquals(2105, MixEngine.postponeMs(12_000, 0, 701.75))
    }
}
