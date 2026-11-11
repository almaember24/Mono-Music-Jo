package com.prism.music

import com.prism.music.playback.VideoSync
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

class VideoSyncTest {
    private val sr = 11025

    /** A stand-in for a song: random notes and drum hits on a 120 bpm grid. */
    private fun music(seconds: Int, seed: Long): FloatArray {
        val rnd = Random(seed)
        val out = FloatArray(seconds * sr)
        val step = sr / 4
        var t = 0
        while (t < out.size) {
            val freq = 110.0 * Math.pow(2.0, rnd.nextInt(36) / 12.0)
            val amp = 0.15 + rnd.nextDouble() * 0.25
            val len = step * (1 + rnd.nextInt(3))
            for (i in 0 until minOf(len, out.size - t)) out[t + i] += (amp * sin(2 * PI * freq * i / sr) * exp(-3.0 * i / len)).toFloat()
            if (rnd.nextInt(3) == 0) for (i in 0 until minOf(sr / 20, out.size - t)) out[t + i] += ((rnd.nextDouble() - 0.5) * 0.6 * exp(-40.0 * i / sr)).toFloat()
            t += step
        }
        return out
    }

    @Test
    fun findsVideoIntroLengthDespiteLevelAndNoiseDifferences() {
        val song = music(70, 7)
        val intro = 23_400
        val rnd = Random(3)
        val video = music(intro / 1000 + 1, 99).copyOf(intro * sr / 1000) +
            FloatArray(song.size + 60 * sr) { i -> (if (i < song.size) song[i] * 0.7f else 0f) + (rnd.nextGaussian() * 0.02).toFloat() }
        val lag = VideoSync.align(VideoSync.bandFeatures(song, sr), VideoSync.bandFeatures(video, sr), (sr / 100) * 1000.0 / sr)
        println("[sync] lag=$lag expected=$intro")
        assertNotNull(lag)
        assertTrue("within 40 ms", abs(lag!! - intro) <= 40)
    }

    @Test
    fun refusesToMatchADifferentSong() {
        val lag = VideoSync.align(VideoSync.bandFeatures(music(70, 1), sr), VideoSync.bandFeatures(music(190, 2), sr))
        assertNull(lag)
    }
}
