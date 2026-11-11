package com.prism.music.playback.sing

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * An in-place complex FFT of a fixed size [n] (a power of two), with its twiddles and bit reversal worked out once.
 * Real and imaginary parts are separate arrays.
 *
 * Radix 4 (1.8.x): each pass combines four transforms a quarter of the size, so a 4096-point one takes 6 passes over
 * the data instead of radix 2's 12, and a quarter fewer multiplications; Sing's separation runs two of these for every
 * 23 ms of song, and on a TV they took half as long as the network itself.
 */
class Fft(val n: Int) {
    init { require(n >= 2 && n and (n - 1) == 0) { "FFT size must be a power of two" } }

    private val levels = Integer.numberOfTrailingZeros(n)
    private val half = n / 2
    private val cosT = DoubleArray(half) { cos(2 * PI * it / n) }
    private val sinT = DoubleArray(half) { sin(2 * PI * it / n) }
    private val rev = IntArray(n) { Integer.reverse(it) ushr (32 - levels) }

    /** Forward transform (e^-i), or the inverse ([inverse], e^+i, scaled by 1/n). */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        val sign = if (inverse) 1.0 else -1.0
        // An odd number of levels: one radix-2 pass first (pairs, no twiddles), the rest radix 4.
        var quarter = 1
        if (levels % 2 == 1) {
            var i = 0
            while (i < n) {
                val r = re[i + 1]; val m = im[i + 1]
                re[i + 1] = re[i] - r; im[i + 1] = im[i] - m
                re[i] += r; im[i] += m
                i += 2
            }
            quarter = 2
        }
        // After bit reversal, a block's four quarters are the transforms of samples 4k, 4k+2, 4k+1 and 4k+3.
        while (quarter * 4 <= n) {
            val size = quarter * 4
            val step = n / size
            var i = 0
            while (i < n) {
                for (k in 0 until quarter) {
                    val a = i + k; val b = a + quarter; val c = b + quarter; val d = c + quarter
                    // W^k on samples 4k+1, W^2k on 4k+2, W^3k on 4k+3 (W = e^∓2πi/size).
                    val k1 = k * step
                    val c1 = cosT[k1]; val s1 = sign * sinT[k1]
                    val k2 = 2 * k1
                    val c2 = cosT[k2]; val s2 = sign * sinT[k2]
                    val k3 = 3 * k1
                    val c3: Double; val s3: Double
                    if (k3 < half) { c3 = cosT[k3]; s3 = sign * sinT[k3] } else { c3 = -cosT[k3 - half]; s3 = -sign * sinT[k3 - half] }
                    val t0r = re[a]; val t0i = im[a]
                    val t1r = re[c] * c1 - im[c] * s1; val t1i = re[c] * s1 + im[c] * c1
                    val t2r = re[b] * c2 - im[b] * s2; val t2i = re[b] * s2 + im[b] * c2
                    val t3r = re[d] * c3 - im[d] * s3; val t3i = re[d] * s3 + im[d] * c3
                    val ar = t0r + t2r; val ai = t0i + t2i
                    val br = t0r - t2r; val bi = t0i - t2i
                    val cr = t1r + t3r; val ci = t1i + t3i
                    // ∓i·(t1 − t3): forward −i, inverse +i.
                    val dr = -sign * (t1i - t3i); val di = sign * (t1r - t3r)
                    re[a] = ar + cr; im[a] = ai + ci
                    re[b] = br + dr; im[b] = bi + di
                    re[c] = ar - cr; im[c] = ai - ci
                    re[d] = br - dr; im[d] = bi - di
                }
                i += size
            }
            quarter = size
        }
        if (inverse) { val s = 1.0 / n; for (i in 0 until n) { re[i] *= s; im[i] *= s } }
    }
}

/**
 * Streaming sample-rate conversion by windowed sinc (Kaiser, 32 zero crossings either side, from a finely sampled
 * table), each output sample taken at its exact time: nothing is shifted, as Sing's subtraction needs everything to
 * line up to the sample. Feed it with [push], then [finish].
 */
class Resampler(private val channels: Int, private val from: Int, private val to: Int) {
    private val ratio = from.toDouble() / to
    /** Below 1 when going down: the sinc is widened, so nothing above the new Nyquist folds back. */
    private val cutoff = minOf(1.0, to.toDouble() / from)
    /** Input frames either side of an output sample that count. */
    private val reach = (ZEROS / cutoff).toInt() + 1
    private var buf = FloatArray(0)
    private var bufFrames = 0
    /** The absolute input frame [buf] starts at. */
    private var bufStart = 0L
    /** The next output sample, in absolute input frames. */
    private var outIndex = 0L
    private var ended = false

    /** Appends [input] (interleaved) and returns the output samples it completes. */
    fun push(input: FloatArray): FloatArray {
        if (from == to) return input
        append(input, input.size / channels)
        return produce()
    }

    /** The rest of the output, once the input is over. */
    fun finish(): FloatArray {
        if (from == to) return FloatArray(0)
        ended = true
        val out = produce()
        return out
    }

    private fun append(input: FloatArray, frames: Int) {
        if (bufFrames + frames > buf.size / channels) buf = buf.copyOf(maxOf(buf.size * 2, (bufFrames + frames) * channels))
        System.arraycopy(input, 0, buf, bufFrames * channels, frames * channels)
        bufFrames += frames
    }

    // Polyphase: for a rational ratio (48 000 → 44 100 is 160 : 147) each output sample falls at one of [up] fixed
    // fractions between input samples, so each fraction's taps are worked out once. Ratios too awkward for that
    // (very large tables) fall back to working the taps out per sample.
    private val gcd = gcd(from, to)
    private val up = to / gcd
    private val down = from / gcd
    private val taps = 2 * reach
    private val phases: Array<FloatArray>? = if (up in 1..MAX_PHASES) Array(up) { p ->
        val frac = p.toDouble() / up
        val h = DoubleArray(taps) { i -> kernel((frac - (i - reach + 1)) * cutoff) }
        val sum = h.sum()
        FloatArray(taps) { (h[it] / sum).toFloat() }
    } else null

    private fun produce(): FloatArray {
        val totalIn = bufStart + bufFrames
        val out = ArrayList<FloatArray>()
        var count = 0
        val block = FloatArray(4096 * channels)
        var n = 0
        while (true) {
            val t = outIndex * ratio
            val last = t.toLong() + reach
            if (!ended && last >= totalIn) break
            if (ended && t >= totalIn) break
            val first = (if (phases != null) outIndex * down / up else t.toLong()) - reach + 1
            if (phases != null && first >= bufStart && first + taps <= totalIn) {
                // The usual case: all the taps are in hand.
                val h = phases[((outIndex * down) % up).toInt()]
                val base = ((first - bufStart) * channels).toInt()
                for (ch in 0 until channels) {
                    var acc = 0f
                    var at = base + ch
                    for (i in 0 until taps) { acc += buf[at] * h[i]; at += channels }
                    block[n * channels + ch] = acc
                }
            } else for (ch in 0 until channels) {
                // The song's very start or end (some taps fall outside it), or an awkward ratio.
                var acc = 0.0
                var norm = 0.0
                val center = t.toLong()
                for (k in center - reach + 1..center + reach) {
                    if (k < 0 || k >= totalIn || k < bufStart) continue
                    val w = kernel((t - k) * cutoff)
                    if (w == 0.0) continue
                    acc += buf[((k - bufStart) * channels + ch).toInt()] * w
                    norm += w
                }
                block[n * channels + ch] = if (norm != 0.0) (acc / norm).toFloat() else 0f
            }
            n++; count++; outIndex++
            if (n == 4096) { out += block.copyOf(); n = 0 }
        }
        if (n > 0) out += block.copyOf(n * channels)
        // Keep only what later output samples still reach.
        val keepFrom = ((outIndex * ratio).toLong() - reach - 1).coerceAtLeast(bufStart)
        val drop = (keepFrom - bufStart).toInt()
        if (drop > 0) {
            System.arraycopy(buf, drop * channels, buf, 0, (bufFrames - drop) * channels)
            bufFrames -= drop
            bufStart += drop
        }
        val result = FloatArray(count * channels)
        var at = 0
        for (b in out) { System.arraycopy(b, 0, result, at, b.size); at += b.size }
        return result
    }

    companion object {
        private const val MAX_PHASES = 4096
        private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
        private const val ZEROS = 32
        private const val PER_UNIT = 512
        private const val BETA = 8.0
        private val table: DoubleArray by lazy {
            DoubleArray(ZEROS * PER_UNIT + 2) { i ->
                val x = i.toDouble() / PER_UNIT
                if (x >= ZEROS) 0.0 else {
                    val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
                    val r = x / ZEROS
                    sinc * bessel0(BETA * sqrt(1 - r * r)) / bessel0(BETA)
                }
            }
        }

        private fun kernel(x: Double): Double {
            val a = abs(x) * PER_UNIT
            val i = a.toInt()
            if (i >= ZEROS * PER_UNIT) return 0.0
            val f = a - i
            return table[i] * (1 - f) + table[i + 1] * f
        }

        private fun bessel0(x: Double): Double {
            var sum = 1.0; var term = 1.0; var k = 1
            while (term > 1e-12 * sum) { term *= (x / (2 * k)) * (x / (2 * k)); sum += term; k++ }
            return sum
        }

        /** A whole signal in one go. */
        fun convert(input: FloatArray, channels: Int, from: Int, to: Int): FloatArray {
            val r = Resampler(channels, from, to)
            val a = r.push(input)
            val b = r.finish()
            return a + b
        }
    }
}
