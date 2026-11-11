package com.prism.music

import com.prism.music.playback.sing.Separation
import com.prism.music.playback.sing.Spleeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sing's quick way (1.8.x, Separation.QUICK), for devices too slow for Spleeter or without its engine: what's in the
 * middle of the stereo picture, in the voice's range, is taken out; what's to one side, out of phase, or below and
 * above the voice's range stays, and a mono song is left as it is.
 */
class SingQuickTest {
    private val rate = Spleeter.RATE
    private val seconds = 20.0

    private fun tone(hz: Double, amp: Double, t: Int) = amp * sin(2 * PI * hz * t / rate + hz)

    /** The song's vocals the quick way. */
    private fun quick(song: FloatArray): Pair<FloatArray, Spleeter> {
        val sp = Spleeter(null, null, null)
        assertEquals(Separation.QUICK, sp.method)
        return (sp.feed(song) + sp.finish()) to sp
    }

    /** Level of [hz] in one channel of [x] over its middle 8 s, in dB. */
    private fun level(x: FloatArray, ch: Int, hz: Double): Double {
        val from = (6 * rate); val n = 8 * rate
        var re = 0.0; var im = 0.0
        for (i in 0 until n) {
            val t = from + i
            val w = 0.5 - 0.5 * cos(2 * PI * i / n)
            re += x[t * 2 + ch] * w * cos(2 * PI * hz * t / rate)
            im += x[t * 2 + ch] * w * sin(2 * PI * hz * t / rate)
        }
        return 10 * log10((re * re + im * im) + 1e-30)
    }

    private fun minus(a: FloatArray, b: FloatArray) = FloatArray(a.size) { a[it] - b[it] }

    @Test fun theMiddleGoesTheSidesStay() {
        val frames = (seconds * rate).toInt()
        val voice = listOf(440.0, 880.0, 1320.0)
        val left = listOf(660.0, 1650.0)
        val right = listOf(990.0, 2200.0)
        val bass = 60.0
        val air = 13_000.0
        val song = FloatArray(frames * 2)
        for (t in 0 until frames) {
            val v = voice.sumOf { tone(it, 0.08, t) } + tone(bass, 0.15, t) + tone(air, 0.02, t)
            song[t * 2] = (v + left.sumOf { tone(it, 0.06, t) }).toFloat()
            song[t * 2 + 1] = (v + right.sumOf { tone(it, 0.06, t) }).toFloat()
        }
        val (vocals, _) = quick(song)
        assertEquals(song.size, vocals.size)
        val backing = minus(song, vocals)
        for (hz in voice) for (ch in 0..1) {
            val cut = level(song, ch, hz) - level(backing, ch, hz)
            assertTrue("the voice at $hz Hz, channel $ch, down $cut dB", cut > 20)
        }
        for ((hz, ch) in left.map { it to 0 } + right.map { it to 1 }) {
            val change = level(song, ch, hz) - level(backing, ch, hz)
            assertTrue("one side's sound at $hz Hz down $change dB", kotlin.math.abs(change) < 1)
        }
        for (hz in listOf(bass, air)) for (ch in 0..1) {
            val change = level(song, ch, hz) - level(backing, ch, hz)
            assertTrue("$hz Hz in the middle but out of the voice's range: down $change dB", kotlin.math.abs(change) < 1)
        }
    }

    @Test fun outOfPhaseSoundStays() {
        // A wide stereo effect (the same sound, one channel inverted) isn't the middle.
        val frames = (seconds * rate).toInt()
        val song = FloatArray(frames * 2)
        for (t in 0 until frames) {
            val v = tone(700.0, 0.1, t)
            song[t * 2] = v.toFloat(); song[t * 2 + 1] = (-v).toFloat()
        }
        val (vocals, _) = quick(song)
        val backing = minus(song, vocals)
        assertTrue(kotlin.math.abs(level(song, 0, 700.0) - level(backing, 0, 700.0)) < 1)
    }

    @Test fun aMonoSongPlaysAsItIs() {
        // Everything's in the middle: the quick way can't tell the voice apart, so nothing is taken out.
        val frames = (seconds * rate).toInt()
        val song = FloatArray(frames * 2)
        for (t in 0 until frames) { val v = (tone(440.0, 0.1, t) + tone(150.0, 0.1, t)).toFloat(); song[t * 2] = v; song[t * 2 + 1] = v }
        val (vocals, sp) = quick(song)
        var e = 0.0
        for (x in vocals) e += x * x
        assertEquals(0.0, sqrt(e / vocals.size), 1e-6)
        assertTrue(sp.quickChunks > 0)
        assertEquals(sp.quickChunks, sp.monoChunks)
    }
}
