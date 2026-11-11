package com.prism.music.playback

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * A decoder's output buffers turned into mono at a lower rate, as the scans read them: a buffer at a time in bulk into
 * one reused array, rather than a sample at a time through the buffer (a tenth of a scan's own work on the A16). Keeps its
 * place between buffers. Also notes the loudest sample it saw.
 */
internal class Downmix {
    private var shorts = ShortArray(4096)
    private var floats = FloatArray(4096)
    private var acc = 0f
    private var n = 0
    var peak = 0f
        private set

    /** Starts over at a new place in the song (the peak is kept). */
    fun restart() { acc = 0f; n = 0 }

    /**
     * Reads [frames] frames of [channels] channels at [buf]'s position, and calls [out] with every [factor]th average.
     * [out] is inlined, so a sample costs no call.
     */
    inline fun add(buf: ByteBuffer, frames: Int, channels: Int, isFloat: Boolean, factor: Int, out: (Float) -> Unit) {
        val samples = frames * channels
        val data = samplesOf(buf.order(ByteOrder.nativeOrder()), samples, isFloat)
        var i = 0
        for (f in 0 until frames) {
            var m = 0f
            for (c in 0 until channels) m += data[i++]
            push(m / channels)
            if (ready(factor)) out(take(factor))
        }
    }

    /** The next [samples] samples as floats (-1…1), in one array that's reused. */
    fun samplesOf(buf: ByteBuffer, samples: Int, isFloat: Boolean): FloatArray {
        if (floats.size < samples) floats = FloatArray(samples)
        val f = floats
        var top = peak
        if (isFloat) {
            buf.asFloatBuffer().get(f, 0, samples)
            for (i in 0 until samples) { val v = abs(f[i]); if (v > top) top = v }
        } else {
            if (shorts.size < samples) shorts = ShortArray(samples)
            val s = shorts
            buf.asShortBuffer().get(s, 0, samples)
            for (i in 0 until samples) { val v = s[i] / 32768f; f[i] = v; val a = abs(v); if (a > top) top = a }
        }
        peak = top
        return f
    }

    fun push(mono: Float) { acc += mono; n++ }
    fun ready(factor: Int) = n == factor
    fun take(factor: Int): Float { val v = acc / factor; acc = 0f; n = 0; return v }
}
