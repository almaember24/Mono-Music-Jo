package com.prism.music.playback

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** GitHub #343: background song scans make way for the listener on slow phones, and DJ mode's pre-scan waits for an idle phone. */
class ScanMannersTest {
    private val slow = { true }
    private val fast = { false }

    @Test
    fun scansStepAsideWhileTheListenerTouchesASlowPhone() {
        // A finger on the screen, and the 2 s after the last touch (a fling).
        assertTrue(MixTempo.stepsAside(slow, down = true, sinceTouchMs = 10_000, urgent = 0))
        assertTrue(MixTempo.stepsAside(slow, down = false, sinceTouchMs = 1_500, urgent = 0))
        assertFalse(MixTempo.stepsAside(slow, down = false, sinceTouchMs = 2_500, urgent = 0))
    }

    @Test
    fun fastPhonesNeverWait() {
        var asked = false
        assertFalse(MixTempo.stepsAside(fast, down = true, sinceTouchMs = 0, urgent = 0))
        // Nobody touching: the phone isn't even asked (it reads a setting).
        assertFalse(MixTempo.stepsAside({ asked = true; true }, down = false, sinceTouchMs = 60_000, urgent = 0))
        assertFalse(asked)
    }

    @Test
    fun aSongsEndWaitingForItsScanComesFirst() {
        // Crossfade near a song's end scans it from the phone: nothing makes way for touches meanwhile.
        assertFalse(MixTempo.stepsAside(slow, down = true, sinceTouchMs = 0, urgent = 1))
        runBlocking {
            val during = MixTempo.urgently { MixTempo.stepsAside(slow, down = true, sinceTouchMs = 0, urgent = 1) }
            assertFalse(during)
        }
    }

    @Test
    fun whichPhonesCountAsSlow() {
        assertTrue(MixTempo.isSlowPhone(cores = 2, mayBeSlow = false, performanceMode = false))
        assertTrue(MixTempo.isSlowPhone(cores = 4, mayBeSlow = false, performanceMode = false))
        assertTrue(MixTempo.isSlowPhone(cores = 8, mayBeSlow = true, performanceMode = false))
        assertTrue(MixTempo.isSlowPhone(cores = 8, mayBeSlow = false, performanceMode = true))
        assertFalse(MixTempo.isSlowPhone(cores = 8, mayBeSlow = false, performanceMode = false))
    }

    @Test
    fun djPreScanOnlyWhileThePhoneIsLeftAlone() {
        assertTrue(DjPrescanWorker.mayRun(screenOn = false, playing = false))
        assertFalse(DjPrescanWorker.mayRun(screenOn = true, playing = false))
        assertFalse(DjPrescanWorker.mayRun(screenOn = false, playing = true))
        assertEquals(false, DjPrescanWorker.mayRun(screenOn = true, playing = true))
    }
}
