package com.prism.music

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.prism.music.playback.MixTempo
import com.prism.music.playback.NormalizerProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

/**
 * #210 (1.8.0): "Normalize volume" evens out big level gaps between songs. It used to lift a song by at most 6 dB and
 * lower one by at most 12, so a quiet recording stayed quiet next to a loud master: now a song is lifted as far as its
 * own peaks allow (and the 6 dB the limiter may take off them), and lowered as far as it needs.
 */
class NormalizeGapsTest {
    private val rate = 11_025

    /** Ten seconds of a chord, its loudest sample at [peak]. */
    private fun song(peak: Double): FloatArray {
        val raw = DoubleArray(rate * 10) { i -> listOf(220.0, 440.0, 997.0).sumOf { hz -> sin(2 * PI * hz * i / rate) } }
        val max = raw.maxOf { abs(it) }
        return FloatArray(raw.size) { (raw[it] / max * peak).toFloat() }
    }

    /** As the song scan reads it: its loudness (the scan hears mono, 3 dB under the stereo figure) and peak in dBFS. */
    private fun measure(mono: FloatArray) =
        MixTempo.integratedLoudness(mono, rate)!! + 3f to (20 * log10(mono.maxOf { abs(it) }.toDouble())).toFloat()

    /** [mono] in both channels through the normalizer at [gainDb]: the left channel that comes out. */
    private fun play(mono: FloatArray, gainDb: Float): FloatArray {
        val p = NormalizerProcessor()
        p.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        p.setGainDb(gainDb)
        val buf = ByteBuffer.allocateDirect(mono.size * 8).order(ByteOrder.nativeOrder())
        mono.forEach { buf.putFloat(it); buf.putFloat(it) }
        buf.flip()
        p.queueInput(buf)
        val o = p.output
        return FloatArray(o.remaining() / 8) { o.float.also { o.float } }
    }

    @Test fun aLoudMasterAndAQuietRecordingEndUpAlike() {
        val loud = song(0.98)
        val quiet = song(0.06) // 24 dB down
        for (target in listOf(-14f, -23f, -8f)) {
            val levels = listOf(loud, quiet).map { s ->
                val (lufs, peak) = measure(s)
                MixTempo.integratedLoudness(play(s, NormalizerProcessor.gainFor(target, lufs, peak)), rate)!! + 3f
            }
            assertEquals("at $target LUFS: the loud one ${levels[0]}, the quiet one ${levels[1]}", levels[0], levels[1], 1.5f)
        }
    }

    @Test fun aQuietSongWithLoudPeaksIsntCrushed() {
        // Quiet on the whole (a soft passage) with a few hits near full scale: lifting it 15 dB would flatten every hit.
        val s = song(0.03).also { for (i in it.indices step rate / 2) for (j in 0 until 40) if (i + j < it.size) it[i + j] = 0.97f }
        val (lufs, peak) = measure(s)
        val gain = NormalizerProcessor.gainFor(-14f, lufs, peak)
        assertTrue("lifted at most the limiter's 6 dB over its headroom ($gain dB, peak $peak dBFS)", gain <= -1f - peak + 6f + 0.01f)
        assertTrue("never over the ceiling", play(s, gain).maxOf { abs(it) } <= 0.891f)
    }

    @Test fun withoutAPeakFigureItLiftsAsBefore() {
        // A song only YouTube's figure is known for: at most 6 dB up, as before.
        assertEquals(6f, NormalizerProcessor.gainFor(-14f, -30f, null), 0.001f)
        assertEquals(-20f, NormalizerProcessor.gainFor(-14f, 6f, null), 0.001f)
    }
}
