package com.prism.music

import com.prism.music.playback.Energy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.8.3: a song's energy from what the scans already measure (loudness, beat, brightness), 0 … 1. */
class EnergyTest {
    private fun bands(perBand: (Int) -> Double, frames: Int = 50) = Array(16) { b -> FloatArray(frames) { kotlin.math.ln(perBand(b)).toFloat() } }

    @Test fun louderIsMoreEnergetic() {
        assertTrue(Energy.of(-7f, 5.0, 0.4)!! > Energy.of(-16f, 5.0, 0.4)!!)
    }

    @Test fun aClearerBeatIsMoreEnergetic() {
        assertTrue(Energy.of(-10f, 7.0, 0.4)!! > Energy.of(-10f, 2.0, 0.4)!!)
        assertTrue("no beat found counts as none", Energy.of(-10f, 2.0, 0.4)!! >= Energy.of(-10f, null, 0.4)!!)
    }

    @Test fun brighterIsMoreEnergetic() {
        assertTrue(Energy.of(-10f, 5.0, 0.55)!! > Energy.of(-10f, 5.0, 0.25)!!)
    }

    @Test fun alwaysBetweenZeroAndOne() {
        assertEquals(0f, Energy.of(-40f, null, 0.0)!!, 1e-6f)
        assertEquals(1f, Energy.of(0f, 20.0, 1.0)!!, 1e-6f)
    }

    @Test fun noLoudnessNoEnergy() {
        assertNull(Energy.of(null, 5.0, 0.4))
        assertNull(Energy.of(Float.NaN, 5.0, 0.4))
    }

    @Test fun measuredBeforeBrightnessWasKeptTheOtherTwoCountAlike() {
        // Loud part 0.5 (−13 LUFS), beat part 0.5 (confidence 4.75): 0.5 either way without brightness.
        assertEquals(0.5f, Energy.of(-13f, 4.75, null)!!, 1e-4f)
    }

    @Test fun brightnessIsWhereTheSoundSits() {
        assertEquals("equal energy in every band (pink noise): the middle", 0.5, Energy.brightness(bands({ 1.0 }))!!, 1e-6)
        assertEquals("all in the lowest band", 0.0, Energy.brightness(bands({ if (it == 0) 1000.0 else 1e-9 }))!!, 1e-6)
        assertEquals("all in the highest band", 1.0, Energy.brightness(bands({ if (it == 15) 1000.0 else 1e-9 }))!!, 1e-6)
        assertTrue("more top than bottom", Energy.brightness(bands({ 1.0 + it }))!! > 0.5)
    }

    @Test fun noFramesNoBrightness() {
        assertNull(Energy.brightness(Array(16) { FloatArray(0) }))
        assertNull(Energy.brightness(emptyArray()))
    }
}
