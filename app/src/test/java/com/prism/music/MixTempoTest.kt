package com.prism.music

import com.prism.music.playback.Tempo
import com.prism.music.playback.VideoSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.random.Random

/** Mix's tempo reading on synthetic songs: kicks on the beat, hats between, a bed of noise, silence at both ends. */
class MixTempoTest {
    private val sr = 11025

    private fun song(bpm: Double, seconds: Int, leadSilence: Double, tailSilence: Double, seed: Int = 1): FloatArray {
        val rnd = Random(seed)
        val n = ((leadSilence + seconds + tailSilence) * sr).toInt()
        val out = FloatArray(n)
        val start = (leadSilence * sr).toInt()
        val end = start + seconds * sr
        val period = 60.0 / bpm
        var beat = 0
        while (true) {
            val t0 = start + (beat * period * sr).toInt()
            if (t0 >= end) break
            // Kick: a falling sine with a click.
            for (i in 0 until (0.18 * sr).toInt()) {
                val idx = t0 + i; if (idx >= end) break
                val t = i.toDouble() / sr
                val f = 50 + 90 * exp(-t * 30)
                out[idx] += (0.9 * exp(-t * 18) * sin(2 * PI * f * t)).toFloat()
            }
            // Hi-hat on the off-beat.
            val h0 = t0 + (period * sr / 2).toInt()
            for (i in 0 until (0.04 * sr).toInt()) {
                val idx = h0 + i; if (idx >= end) break
                out[idx] += (0.25 * exp(-i.toDouble() / sr * 90) * (rnd.nextDouble() * 2 - 1)).toFloat()
            }
            beat++
        }
        // A pad underneath, so the "music" isn't only drums.
        for (i in start until end) out[i] += (0.05 * sin(2 * PI * 220 * i / sr) + 0.02 * (rnd.nextDouble() * 2 - 1)).toFloat()
        return out
    }

    private fun read(pcm: FloatArray): Tempo {
        val frameMs = (sr / 100) * 1000.0 / sr
        val t = Tempo.analyze(VideoSync.bandFeatures(pcm, sr), frameMs, VideoSync.WINDOW / 2 * 1000.0 / sr)
        assertNotNull("no tempo read", t)
        return t!!
    }

    /** Tempo equal up to an octave (85 for 170 is the same beat grid, every other beat). */
    private fun assertTempo(expected: Double, got: Double) {
        val octaves = ln(got / expected) / ln(2.0)
        val off = abs(octaves - Math.round(octaves))
        assertTrue("expected $expected BPM (any octave), got $got", off < 0.01)
    }

    @Test
    fun readsTempoBeatsAndEdges() {
        for (bpm in listOf(92.0, 120.0, 128.0, 140.0, 174.0)) {
            val lead = 2.0; val tail = 3.0; val seconds = 60
            val t = read(song(bpm, seconds, lead, tail, seed = bpm.toInt()))
            println("bpm $bpm -> ${"%.2f".format(t.bpm)} conf ${"%.1f".format(t.confidence)} first ${t.firstSoundMs} last ${t.lastSoundMs} firstBeat ${t.firstBeatMs} outroBeat ${t.outroBeatMs}")
            assertTempo(bpm, t.bpm)
            assertTrue("steady beat for $bpm", t.steady)
            assertEquals("music starts ($bpm)", (lead * 1000).roundToLong().toDouble(), t.firstSoundMs.toDouble(), 80.0)
            assertEquals("music ends ($bpm)", ((lead + seconds) * 1000).toDouble(), t.lastSoundMs.toDouble(), 400.0)
            assertEquals("first beat on the first kick ($bpm)", lead * 1000, t.firstBeatMs.toDouble(), 40.0)
            // The outro's beats sit on the same grid as the song's first beat (the detected period, any octave).
            val period = 60_000.0 / bpm
            val beats = (t.outroBeatMs - lead * 1000) / period
            val frac = abs(beats - Math.round(beats)) * period
            assertTrue("outro beat on the grid ($bpm): ${"%.0f".format(frac)} ms off", frac < 45 || period - frac < 45)
        }
    }

    @Test
    fun noiseHasNoSteadyBeat() {
        val rnd = Random(7)
        val pcm = FloatArray(sr * 40) { (0.3 * (rnd.nextDouble() * 2 - 1)).toFloat() }
        val t = Tempo.analyze(VideoSync.bandFeatures(pcm, sr), (sr / 100) * 1000.0 / sr)
        println("noise -> ${t?.bpm} conf ${t?.confidence}")
        assertTrue("noise shouldn't read as a steady beat", t == null || !t.steady)
    }

    @Test
    fun encodesAndDecodes() {
        val t = Tempo(123.4, 5.6, 1000, 200_000, 1100, 199_500, 6200, 3100)
        assertEquals(t, Tempo.decode(t.encode()))
        // Read before the edges were measured: still a tempo, its edges unknown (so it's scanned again).
        val old = Tempo.decode("2|123.4|5.6|1000|200000|1100|199500")!!
        assertTrue(old.edgesUnknown)
        assertTrue(Tempo.isOld("2|123.4|5.6|1000|200000|1100|199500"))
    }

    @Test
    fun fadeOutAndQuietIntroAreMeasured() {
        val lead = 2.0; val seconds = 60
        val pcm = song(120.0, seconds, lead, 3.0, seed = 3)
        val start = (lead * sr).toInt(); val end = start + seconds * sr
        // A quiet first 6 s (-15 dB), then full; the last 8 s fade out (amplitude straight down to nothing).
        for (i in start until start + 6 * sr) pcm[i] *= 0.18f
        for (i in end - 8 * sr until end) pcm[i] *= (end - i).toFloat() / (8 * sr)
        val t = read(pcm)
        println("fade-out ${t.fadeOutMs} ms, quiet intro ${t.quietIntroMs} ms")
        // The fade is heard from where it's dropped a few dB to where the music ends.
        assertTrue("fade-out ${t.fadeOutMs} ms", t.fadeOutMs in 3_500..8_500)
        assertEquals("quiet intro", 6_000.0, t.quietIntroMs.toDouble(), 700.0)
    }

    @Test
    fun hardEdgesHaveNoFadeOrIntro() {
        val t = read(song(128.0, 60, 2.0, 3.0, seed = 5))
        println("hard edges: fade-out ${t.fadeOutMs} ms, quiet intro ${t.quietIntroMs} ms")
        assertTrue("fade-out ${t.fadeOutMs} ms", t.fadeOutMs < 1_000)
        assertTrue("quiet intro ${t.quietIntroMs} ms", t.quietIntroMs < 1_000)
    }

    @Test
    fun autoCrossfadeLength() {
        fun edges(fade: Long, intro: Long) = Tempo(120.0, 5.0, 0, 200_000, 0, 199_500, fade, intro)
        val plain = edges(0, 0)
        // Over the ending song's own fade-out, up to 12 s.
        assertEquals(7_000L, com.prism.music.playback.MixEngine.autoLengthMs(edges(7_000, 0), plain))
        assertEquals(12_000L, com.prism.music.playback.MixEngine.autoLengthMs(edges(20_000, 0), plain))
        // An abrupt ending into a quiet intro: over the intro, at most 4 s.
        assertEquals(3_000L, com.prism.music.playback.MixEngine.autoLengthMs(plain, edges(0, 3_000)))
        assertEquals(4_000L, com.prism.music.playback.MixEngine.autoLengthMs(plain, edges(0, 15_000)))
        // Two hard edges: a brief touch.
        assertEquals(1_500L, com.prism.music.playback.MixEngine.autoLengthMs(plain, plain))
        // Not scanned yet: a middling length.
        assertEquals(6_000L, com.prism.music.playback.MixEngine.autoLengthMs(null, plain))
    }
}
