package com.prism.music

import com.prism.music.playback.MixTempo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Normalization's loudness measure ([MixTempo.integratedLoudness]) against BS.1770's reference values. */
class LoudnessTest {
    private val rate = 11_025

    private fun tone(amplitude: Double, seconds: Double, hz: Double = 997.0) =
        FloatArray((seconds * rate).toInt()) { i -> (amplitude * sin(2 * PI * hz * i / rate)).toFloat() }

    @Test
    fun aReferenceToneReadsMinus23() {
        // BS.1770's check: a 997 Hz sine at -20 dBFS (amplitude 0.1) in one channel is -23.0 LUFS.
        val l = MixTempo.integratedLoudness(tone(0.1, 10.0), rate)!!
        assertEquals(-23.0, l.toDouble(), 0.5)
    }

    @Test
    fun twiceAsLoudIsSixDbMore() {
        val a = MixTempo.integratedLoudness(tone(0.1, 10.0), rate)!!
        val b = MixTempo.integratedLoudness(tone(0.2, 10.0), rate)!!
        assertEquals(6.02, (b - a).toDouble(), 0.1)
    }

    @Test
    fun silenceIsGatedOut() {
        // Half silence, half tone: the gate leaves the silence out, so it reads as the tone alone.
        val tone = tone(0.1, 10.0)
        val half = FloatArray(tone.size * 2).also { tone.copyInto(it, tone.size) }
        val l = MixTempo.integratedLoudness(half, rate)!!
        assertEquals(MixTempo.integratedLoudness(tone, rate)!!.toDouble(), l.toDouble(), 0.3)
        assertNull(MixTempo.integratedLoudness(FloatArray(rate * 5), rate))
    }

    @Test
    fun lowRumbleCountsLess() {
        // The K-weighting's high-pass: a 20 Hz rumble as strong as the tone reads far quieter.
        val tone = MixTempo.integratedLoudness(tone(0.1, 10.0), rate)!!
        val rumble = MixTempo.integratedLoudness(tone(0.1, 10.0, hz = 20.0), rate)!!
        assert(rumble < tone - 5) { "rumble $rumble vs tone $tone" }
    }
}
