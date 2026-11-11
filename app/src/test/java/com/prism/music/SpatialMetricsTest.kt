package com.prism.music

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.prism.music.playback.SpatialAudioProcessor
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.random.Random

/** Measures what Prism Spatial does to music-like stereo: how much wider it gets and how much room it adds. */
class SpatialMetricsTest {
    private val rate = 48_000

    /** Music-like stereo: a shared (centre) part plus a little of each side; then [silenceSec] of silence. */
    private fun signal(sec: Double, silenceSec: Double): FloatArray {
        val rnd = Random(7)
        val n = (sec * rate).toInt()
        val total = n + (silenceSec * rate).toInt()
        val out = FloatArray(total * 2)
        var m = 0f; var a = 0f; var b = 0f
        for (i in 0 until n) {
            // Gently low-passed noise, so it's closer to music than white noise.
            m += (rnd.nextFloat() * 2 - 1 - m) * 0.2f
            a += (rnd.nextFloat() * 2 - 1 - a) * 0.2f
            b += (rnd.nextFloat() * 2 - 1 - b) * 0.2f
            out[i * 2] = (m * 0.9f + a * 0.3f) * 0.8f
            out[i * 2 + 1] = (m * 0.9f + b * 0.3f) * 0.8f
        }
        return out
    }

    private fun process(amount: Float, input: FloatArray): FloatArray {
        val p = SpatialAudioProcessor()
        p.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        p.configure(amount > 0f, amount)
        val out = FloatArray(input.size)
        var w = 0
        val chunk = 4096
        var i = 0
        while (i < input.size) {
            val len = minOf(chunk, input.size - i)
            val buf = ByteBuffer.allocateDirect(len * 4).order(ByteOrder.nativeOrder())
            for (k in 0 until len) buf.putFloat(input[i + k])
            buf.flip()
            p.queueInput(buf)
            val o = p.output
            while (o.remaining() >= 4) out[w++] = o.float
            i += len
        }
        return out
    }

    private fun correlation(x: FloatArray, from: Int, to: Int): Double {
        var ll = 0.0; var rr = 0.0; var lr = 0.0
        for (f in from until to) { val l = x[f * 2].toDouble(); val r = x[f * 2 + 1].toDouble(); ll += l * l; rr += r * r; lr += l * r }
        return lr / sqrt(ll * rr)
    }

    private fun rmsDb(x: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (f in from until to) { s += x[f * 2] * x[f * 2].toDouble() + x[f * 2 + 1] * x[f * 2 + 1].toDouble() }
        return 10 * log10(s / ((to - from) * 2) + 1e-20)
    }

    @Test
    fun normalizerTurnsLoudSongsDownQuietOnesUpAndNeverClips() {
        val input = signal(2.0, 0.0)
        val music = (1.0 * rate).toInt() until (2.0 * rate).toInt()
        val dry = rmsDb(input, music.first, music.last)
        for (gainDb in listOf(-6f, 6f, 12f)) {
            val p = com.prism.music.playback.NormalizerProcessor()
            p.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT))
            p.flush(AudioProcessor.StreamMetadata.DEFAULT)
            p.setGainDb(gainDb)
            val buf = ByteBuffer.allocateDirect(input.size * 4).order(ByteOrder.nativeOrder())
            input.forEach { buf.putFloat(it) }
            buf.flip()
            p.queueInput(buf)
            val o = p.output
            val out = FloatArray(o.remaining() / 4) { o.float }
            val level = rmsDb(out, music.first, music.last)
            val peak = out.maxOf { kotlin.math.abs(it) }
            println("[normalize] gain %+.0f dB: level %.1f -> %.1f dB (%+.1f), peak %.2f".format(gainDb, dry, level, level - dry, peak))
            assertTrue("peak under the -1 dB ceiling ($peak)", peak <= 0.891f)
            if (gainDb < 0) assertTrue("turned down", level - dry < -5)
            else assertTrue("turned up", level - dry > 3)
        }
    }

    @Test
    fun spatialIsClearlyWiderAndRoomier() {
        val input = signal(2.0, 0.6)
        val music = (0.5 * rate).toInt() until (2.0 * rate).toInt()
        val tail = (2.05 * rate).toInt() until (2.4 * rate).toInt()
        val dryCorr = correlation(input, music.first, music.last)
        val dryLevel = rmsDb(input, music.first, music.last)
        for (amount in listOf(0.6f, 1f)) {
            val out = process(amount, input)
            val corr = correlation(out, music.first, music.last)
            val level = rmsDb(out, music.first, music.last)
            val room = rmsDb(out, tail.first, tail.last) - level
            println("[spatial] amount ${(amount * 100).toInt()}%%: L/R correlation %.2f -> %.2f, level %.1f -> %.1f dB, room tail %.1f dB below the music"
                .format(dryCorr, corr, dryLevel, level, -room))
            if (amount == 1f) {
                assertTrue("noticeably wider (correlation $corr)", corr < dryCorr - 0.25)
                assertTrue("audible room (tail ${-room} dB down)", room > -30)
                assertTrue("level roughly kept (${level - dryLevel} dB)", kotlin.math.abs(level - dryLevel) < 3)
            }
        }
    }
}
