package com.prism.music.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The visualizer's ear (1.7.4): the last end of Prism's own audio chain (after the normalizer, so it's the sound you
 * hear), tapped through Media3's [TeeAudioProcessor]. No microphone and no permission: Android's own Visualizer API
 * would need the microphone's.
 *
 * It costs nothing while no visualizer is on screen: [handleBuffer] returns at once unless one is [listen]ing, and the
 * analysis ([Spectrum]) runs on the screen's frames, only while the player that shows it is drawn.
 */
object VisualizerTap : TeeAudioProcessor.AudioBufferSink {
    private val listeners = AtomicInteger(0)
    /** Mono samples, newest at [written] - 1 (wrapping). */
    private val ring = FloatArray(RING)
    @Volatile private var written = 0L
    @Volatile private var lastAt = 0L
    @Volatile private var rate = 44_100
    @Volatile private var channels = 2
    @Volatile private var encoding = C.ENCODING_PCM_16BIT

    /** A visualizer came on screen; [stop] when it goes. */
    fun listen() { listeners.incrementAndGet() }
    fun stop() { listeners.decrementAndGet() }

    val sampleRate: Int get() = rate

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        rate = sampleRateHz.takeIf { it > 0 } ?: 44_100
        channels = channelCount.coerceAtLeast(1)
        this.encoding = encoding
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (listeners.get() <= 0) return
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        val ch = channels
        var w = written
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val frames = b.remaining() / (2 * ch)
                var at = b.position()
                repeat(frames) {
                    var sum = 0f
                    for (c in 0 until ch) { sum += b.getShort(at) / 32768f; at += 2 }
                    ring[(w % RING).toInt()] = sum / ch
                    w++
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                val frames = b.remaining() / (4 * ch)
                var at = b.position()
                repeat(frames) {
                    var sum = 0f
                    for (c in 0 until ch) { sum += b.getFloat(at); at += 4 }
                    ring[(w % RING).toInt()] = sum / ch
                    w++
                }
            }
            else -> return
        }
        written = w
        lastAt = SystemClock.uptimeMillis()
    }

    /**
     * The [out].size samples heard about now: the audio chain runs ahead of the speaker by the output's buffer, so
     * the window ends [LATENCY_MS] before the newest sample. False when nothing has played lately (paused, stopped).
     */
    fun window(out: FloatArray): Boolean {
        if (SystemClock.uptimeMillis() - lastAt > QUIET_MS) return false
        val end = written - rate * LATENCY_MS / 1000
        if (end < out.size) return false
        val start = end - out.size
        for (i in out.indices) out[i] = ring[((start + i) % RING).toInt()]
        return true
    }

    private const val RING = 1 shl 15
    /** About how far the decoded sound runs ahead of what comes out of the speaker. */
    private const val LATENCY_MS = 120
    private const val QUIET_MS = 300L
}

/**
 * Turns [VisualizerTap]'s window into [BANDS] bands (log-spaced 50 Hz … 14 kHz, 0–1, rising fast and falling slowly,
 * so they read as movement rather than flicker) and a [level] that leans on the bass (the beat), for Pulse and Glow.
 * Plain arithmetic, one 1024-point FFT a frame (well under a millisecond).
 */
class Spectrum {
    val bands = FloatArray(BANDS)
    var level = 0f
        private set
    private val samples = FloatArray(N)
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val window = FloatArray(N) { i -> (0.5 - 0.5 * cos(2 * PI * i / (N - 1))).toFloat() }
    private var loud = 0.05f

    /** One frame's worth; [dtMs] since the last, for the fall rate. */
    fun update(dtMs: Float) {
        val fall = (dtMs / 1000f) * 1.6f
        if (!VisualizerTap.window(samples)) {
            for (i in bands.indices) bands[i] = max(0f, bands[i] - fall)
            level = max(0f, level - fall)
            return
        }
        for (i in 0 until N) { re[i] = samples[i] * window[i]; im[i] = 0f }
        fft(re, im)
        val rate = VisualizerTap.sampleRate.toFloat()
        val binHz = rate / N
        var bass = 0f
        for (b in 0 until BANDS) {
            val lo = EDGES[b] / binHz
            val hi = max(EDGES[b + 1] / binHz, lo + 1f)
            var sum = 0f
            var n = 0
            var k = lo.toInt().coerceAtLeast(1)
            while (k < hi.toInt().coerceAtMost(N / 2)) { sum += sqrt(re[k] * re[k] + im[k] * im[k]); n++; k++ }
            val mag = if (n == 0) 0f else sum / n
            // dB-ish, with the treble lifted (music's spectrum falls ~3 dB an octave).
            val v = (ln(1f + mag * (1f + b * 0.35f)) / 3.2f).coerceIn(0f, 1.5f)
            if (b < 4) bass += v
            bands[b] = if (v > bands[b]) bands[b] + (v - bands[b]) * 0.65f else max(v, bands[b] - fall)
        }
        // Each song is evened out against its own recent peak, so quiet and loud masters both fill the room.
        val peak = bands.max()
        loud = max(peak, loud * 0.995f).coerceAtLeast(0.05f)
        for (b in 0 until BANDS) bands[b] = min(1f, bands[b] / loud)
        val beat = min(1f, bass / 4f / loud)
        level = if (beat > level) level + (beat - level) * 0.6f else max(beat, level - fall)
    }

    companion object {
        const val BANDS = 28
        private const val N = 1024
        private val EDGES = FloatArray(BANDS + 1) { i -> (50.0 * Math.pow(14_000.0 / 50.0, i / BANDS.toDouble())).toFloat() }

        /** In-place radix-2 FFT. */
        private fun fft(re: FloatArray, im: FloatArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
            }
            var len = 2
            while (len <= n) {
                val ang = -2 * PI / len
                val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
                var i = 0
                while (i < n) {
                    var cr = 1f; var ci = 0f
                    for (k in 0 until len / 2) {
                        val a = i + k; val b = a + len / 2
                        val tr = re[b] * cr - im[b] * ci
                        val ti = re[b] * ci + im[b] * cr
                        re[b] = re[a] - tr; im[b] = im[a] - ti
                        re[a] += tr; im[a] += ti
                        val ncr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr; cr = ncr
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}
