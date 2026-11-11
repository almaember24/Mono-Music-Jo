package com.prism.music

import androidx.media3.common.C
import com.prism.music.playback.Spectrum
import com.prism.music.playback.VisualizerTap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** The visualizer (1.7.4): its tap only works while one is shown, and its bands follow the music's pitch. */
@RunWith(RobolectricTestRunner::class)
class VisualizerTest {
    private fun tone(hz: Double, seconds: Double, rate: Int = 44_100): ByteBuffer {
        val frames = (rate * seconds).toInt()
        val b = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val v = (sin(2 * PI * hz * i / rate) * 0.5 * Short.MAX_VALUE).toInt().toShort()
            b.putShort(v); b.putShort(v)
        }
        b.flip()
        return b
    }

    @Test fun nothingIsHeardWithoutAListener() {
        // Past anything another test fed it (it counts as heard for 300 ms).
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(400))
        VisualizerTap.flush(44_100, 2, C.ENCODING_PCM_16BIT)
        val buffer = tone(440.0, 0.5)
        VisualizerTap.handleBuffer(buffer)
        // The sound passes on untouched (the tap reads a copy) and nothing was kept.
        assertEquals(0, buffer.position())
        assertTrue(!VisualizerTap.window(FloatArray(1024)))
    }

    @Test fun aLowToneLightsTheLowBandsAndAHighToneTheHighOnes() {
        fun loudestBand(hz: Double): Int {
            VisualizerTap.listen()
            try {
                VisualizerTap.flush(44_100, 2, C.ENCODING_PCM_16BIT)
                VisualizerTap.handleBuffer(tone(hz, 0.6))
                val s = Spectrum()
                repeat(10) { s.update(16f) }
                return s.bands.indices.maxBy { s.bands[it] }
            } finally { VisualizerTap.stop() }
        }
        val low = loudestBand(110.0)
        val high = loudestBand(5_000.0)
        assertTrue("110 Hz peaked in band $low", low < Spectrum.BANDS / 3)
        assertTrue("5 kHz peaked in band $high", high > Spectrum.BANDS * 2 / 3)
    }
}
