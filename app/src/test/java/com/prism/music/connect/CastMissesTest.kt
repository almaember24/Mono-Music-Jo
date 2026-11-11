package com.prism.music.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #239 (1.8.3): a Sonos played fine until Prism left the screen; Android then held Prism's network back, six polls went
 * unanswered (~28 s), and casting ended as "lost the connection". Polls failing while the network is held back aren't
 * counted; six real misses in a row still end it.
 */
class CastMissesTest {
    @Test fun missesWhileTheNetworkIsHeldBackDontEndCasting() {
        val m = CastMisses(6)
        repeat(50) { assertFalse(m.failed(blocked = true)) }
        assertEquals(0, m.count)
    }

    @Test fun sixRealMissesInARowStillDo() {
        val m = CastMisses(6)
        repeat(5) { assertFalse(m.failed(blocked = false)) }
        assertTrue(m.failed(blocked = false))
    }

    @Test fun anAnswerStartsTheCountAgain() {
        val m = CastMisses(6)
        repeat(5) { m.failed(blocked = false) }
        m.ok()
        repeat(5) { assertFalse(m.failed(blocked = false)) }
    }
}
