package com.prism.music.playback.sing

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * How Sing tells a song's voice apart (1.8.x), best first. [FULL] and [LIGHT] need Sing's download; [QUICK] runs
 * anywhere, in seconds a song.
 */
enum class Separation {
    /** Spleeter's two networks, the voice's and the backing's, weighed against each other. */
    FULL,
    /** The voice's network alone (half the work; a little more of the backing goes with the voice). */
    LIGHT,
    /**
     * No network: what sits in the middle of the stereo picture, in the voice's range (lead vocals are almost always
     * mixed there). Anything else in the middle in that range goes too, and a mono song can't be done this way.
     */
    QUICK,
}

/**
 * Deezer's Spleeter (2 stems), as sherpa-onnx exported it: the song's vocals, taken out of stereo 44.1 kHz audio
 * fed in a bit at a time ([feed], then [finish]), and handed back as soon as they're final.
 *
 * Each channel's spectrum (4096-point, hop 1024, periodic Hann) goes through both networks 512 frames (~11.9 s) at a
 * time, as Spleeter does it. Their outputs make a soft mask (vocals² / (vocals² + accompaniment²)) that's applied to
 * the mix's own spectrum, so the vocals keep the mix's phase; the networks only see up to ~11 kHz, and above it each
 * frame uses the mask's average over its top band, so sibilants go with the voice. The vocals come back out by
 * overlap-add, lined up sample for sample with what went in. [method] says which networks make the mask, or none
 * ([Separation.QUICK]); it can change from one chunk to the next.
 */
class Spleeter(
    private val env: OrtEnvironment?,
    private val vocals: OrtSession?,
    private val accompaniment: OrtSession?,
    /** With false the mask is all ones: the whole mix comes back (for testing the transforms). */
    private val separate: Boolean = true,
) {
    /** How the next chunk's vocals are told apart (the best the sessions given allow, unless set lower). */
    @Volatile var method: Separation = when {
        accompaniment != null -> Separation.FULL
        vocals != null -> Separation.LIGHT
        else -> Separation.QUICK
    }

    /** How the last chunk's vocals were told apart (the full way without its second network is the light way). */
    @Volatile var ran: Separation = method
        private set

    /** Chunks done the quick way, and how many of them were mono (nothing to tell the middle apart from). */
    var quickChunks = 0
        private set
    var monoChunks = 0
        private set
    companion object {
        const val RATE = 44_100
        const val N_FFT = 4096
        const val HOP = 1024
        const val BINS = 1024
        const val FRAMES = 512
        /** How much song one chunk is (~11.9 s). */
        const val CHUNK_SECONDS = FRAMES.toDouble() * HOP / RATE
        private const val SPEC = N_FFT / 2 + 1
        /** Leading zeros, so the song's first sample is covered by four frames like every other. */
        private const val PAD = N_FFT - HOP
        private const val EPS = 1e-10f
        /** Sum of the squared window over the overlapping frames (periodic Hann at 75 % overlap). */
        private const val NORM = 1.5
        private const val EXT_FROM = 896

        /**
         * The quick way: how alike left and right must be to begin to count as the middle (1 = identical). 0.6 came
         * closest to the real parts of 50 songs (MUSDB18's 7-second excerpts; 0.2–0.9 tried, 1.8.x).
         */
        internal const val CENTRE_FROM = 0.6f
        /** A chunk with less sound on the sides than this share (−30 dB) is mono. */
        private const val MONO = 1e-3
        /** The quick way's range: the voice's, from 250 Hz (faded in from 120) to 7 kHz (faded out by 11). */
        private val band = FloatArray(BINS) { k ->
            val hz = k.toFloat() * RATE / N_FFT
            when {
                hz < 120f -> 0f
                hz < 250f -> (hz - 120f) / 130f
                hz <= 7_000f -> 1f
                hz < 11_000f -> 1f - (hz - 7_000f) / 4_000f
                else -> 0f
            }
        }

        /** Threads for the transforms (up to 4), at background priority like the separation itself. */
        internal var WORKERS = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        /** The transform threads' Java priority (4 is Android's background priority); open to the speed test. */
        internal var workerPriority = 4
        private val pool by lazy {
            java.util.concurrent.Executors.newFixedThreadPool(4) { r ->
                Thread(r, "PrismSingFft").apply { isDaemon = true; priority = workerPriority }
            }
        }
    }

    /** Called after each chunk: how long the networks took, and the whole chunk (ms). */
    var onChunk: ((modelMs: Long, totalMs: Long) -> Unit)? = null

    /** Time in the forward and the inverse transforms so far (ms), for the speed test. */
    internal var forwardMs = 0L
    internal var inverseMs = 0L

    private val fft = Fft(N_FFT)
    private val window = DoubleArray(N_FFT) { 0.5 - 0.5 * cos(2 * PI * it / N_FFT) }

    // Input, in padded coordinates (index 0 is the first of PAD zeros), from [inStart] on.
    private var input = FloatArray(PAD * 2 + 2 * (FRAMES * HOP + N_FFT))
    private var inFrames = PAD
    private var inStart = 0L
    /** Song samples fed so far (without the padding). */
    private var fed = 0L

    // Output accumulator (overlap-add), padded coordinates, from [outStart] on.
    private var acc = DoubleArray(2 * (FRAMES * HOP + N_FFT))
    private var outStart = 0L
    /** Song samples handed back so far. */
    private var emitted = 0L

    private var chunk = 0L

    /** One worker's room for its transforms. */
    private class Scratch {
        val re = DoubleArray(N_FFT)
        val im = DoubleArray(N_FFT)
        val maskedRe = Array(2) { FloatArray(SPEC) }
        val maskedIm = Array(2) { FloatArray(SPEC) }
    }
    private val workers = WORKERS
    private val scratch = Array(workers) { Scratch() }
    private val specRe = Array(2) { Array(FRAMES) { FloatArray(SPEC) } }
    private val specIm = Array(2) { Array(FRAMES) { FloatArray(SPEC) } }
    private val mag = FloatArray(2 * FRAMES * BINS)

    /** Feeds stereo 44.1 kHz samples (interleaved); returns the vocals now final (interleaved, same rate). */
    fun feed(samples: FloatArray): FloatArray {
        append(samples)
        fed += samples.size / 2
        val out = ArrayList<FloatArray>()
        while (inStart + inFrames >= chunkEnd(chunk)) out += runChunk()
        return out.flatten()
    }

    /** The rest of the vocals, once all the song is in (the last chunk padded with silence). */
    fun finish(): FloatArray {
        val total = fed
        val out = ArrayList<FloatArray>()
        while (emitted < total) {
            val need = (chunkEnd(chunk) - (inStart + inFrames)).coerceAtLeast(0)
            if (need > 0) append(FloatArray((need * 2).toInt()))
            out += runChunk()
        }
        val all = out.flatten()
        // Only as long as the song itself.
        return all
    }

    /** The padded sample past the last one chunk [c]'s frames need. */
    private fun chunkEnd(c: Long) = ((c + 1) * FRAMES - 1) * HOP + N_FFT

    private fun append(samples: FloatArray) {
        val frames = samples.size / 2
        if ((inFrames + frames) * 2 > input.size) input = input.copyOf(maxOf(input.size * 2, (inFrames + frames) * 2))
        System.arraycopy(samples, 0, input, inFrames * 2, samples.size)
        inFrames += frames
    }

    private fun runChunk(): FloatArray {
        val t0 = System.nanoTime()
        val first = chunk * FRAMES
        // Forward transforms, the chunk's frames shared out among the workers (the network's threads are idle then).
        parallel { w -> for (f in w * FRAMES / workers until (w + 1) * FRAMES / workers) forward(scratch[w], first, f) }
        val tm = System.nanoTime()
        forwardMs += (tm - t0) / 1_000_000
        val mask = if (separate) masks() else null
        val modelNs = System.nanoTime() - tm

        // Masked spectra back to sound, overlap-added: in blocks, every other one at a time, so no two workers ever
        // add into the same samples (a block of 64 frames reaches only 3 frames into the next).
        ensureAcc(chunkEnd(chunk))
        val blocks = 2 * workers
        val ti = System.nanoTime()
        for (phase in 0..1) parallel { w ->
            val b = 2 * w + phase
            for (f in b * FRAMES / blocks until (b + 1) * FRAMES / blocks) inverse(scratch[w], mask, first, f)
        }
        inverseMs += (System.nanoTime() - ti) / 1_000_000
        chunk++
        // Everything before the next chunk's first frame is final.
        val finalUpTo = chunk * FRAMES * HOP
        val ready = (finalUpTo - outStart).toInt()
        val emit = FloatArray(maxOf(0, ready) * 2)
        var n = 0
        for (i in 0 until ready) {
            val padded = outStart + i
            val song = padded - PAD
            if (song < 0 || song >= fed) continue
            emit[n * 2] = (acc[i * 2] / NORM).toFloat()
            emit[n * 2 + 1] = (acc[i * 2 + 1] / NORM).toFloat()
            n++
        }
        // Slide both buffers along.
        System.arraycopy(acc, ready * 2, acc, 0, acc.size - ready * 2)
        java.util.Arrays.fill(acc, acc.size - ready * 2, acc.size, 0.0)
        outStart = finalUpTo
        val keepFrom = (chunk * FRAMES * HOP) - inStart
        val drop = keepFrom.toInt().coerceIn(0, inFrames)
        System.arraycopy(input, drop * 2, input, 0, (inFrames - drop) * 2)
        inFrames -= drop
        inStart += drop
        emitted += n
        onChunk?.invoke(modelNs / 1_000_000, (System.nanoTime() - t0) / 1_000_000)
        return emit.copyOf(n * 2)
    }

    /** Frame [f] of the chunk from [first]: both channels in one FFT (left real, right imaginary), then pulled apart. */
    private fun forward(s: Scratch, first: Long, f: Int) {
        val re = s.re; val im = s.im
        val start = ((first + f) * HOP - inStart).toInt()
        for (n in 0 until N_FFT) {
            val w = window[n]
            re[n] = input[(start + n) * 2] * w
            im[n] = input[(start + n) * 2 + 1] * w
        }
        fft.transform(re, im)
        val lr = specRe[0][f]; val li = specIm[0][f]; val rr = specRe[1][f]; val ri = specIm[1][f]
        for (k in 0 until SPEC) {
            val j = (N_FFT - k) % N_FFT
            // X_L = (Z[k] + conj Z[N-k]) / 2,  X_R = (Z[k] - conj Z[N-k]) / 2i
            lr[k] = ((re[k] + re[j]) / 2).toFloat()
            li[k] = ((im[k] - im[j]) / 2).toFloat()
            rr[k] = ((im[k] + im[j]) / 2).toFloat()
            ri[k] = ((re[j] - re[k]) / 2).toFloat()
        }
        for (ch in 0 until 2) {
            val a = specRe[ch][f]; val b = specIm[ch][f]
            val base = (ch * FRAMES + f) * BINS
            for (k in 0 until BINS) mag[base + k] = sqrt(a[k] * a[k] + b[k] * b[k])
        }
    }

    /** Frame [f]'s masked spectrum back to sound, added into [acc]. */
    private fun inverse(s: Scratch, mask: FloatArray?, first: Long, f: Int) {
        // Each channel's masked spectrum (bins 0..N/2).
        for (ch in 0 until 2) {
            val base = (ch * FRAMES + f) * BINS
            var ext = 1f
            if (mask != null) {
                var sum = 0f
                for (k in EXT_FROM until BINS) sum += mask[base + k]
                ext = sum / (BINS - EXT_FROM)
            }
            val mr = s.maskedRe[ch]; val mi = s.maskedIm[ch]
            val sr = specRe[ch][f]; val si = specIm[ch][f]
            for (k in 0 until SPEC) {
                val m = when {
                    mask == null -> 1f
                    k < BINS -> mask[base + k]
                    else -> ext
                }
                mr[k] = sr[k] * m
                mi[k] = si[k] * m
            }
        }
        // Both channels back in one inverse FFT: Z[k] = L[k] + i·R[k], and Z[N-k] = conj(L[k]) + i·conj(R[k]).
        val re = s.re; val im = s.im
        val lr = s.maskedRe[0]; val li = s.maskedIm[0]; val rr = s.maskedRe[1]; val ri = s.maskedIm[1]
        for (k in 0 until SPEC) {
            re[k] = (lr[k] - ri[k]).toDouble()
            im[k] = (li[k] + rr[k]).toDouble()
        }
        for (k in 1 until N_FFT / 2) {
            re[N_FFT - k] = (lr[k] + ri[k]).toDouble()
            im[N_FFT - k] = (rr[k] - li[k]).toDouble()
        }
        fft.transform(re, im, inverse = true)
        val at = ((first + f) * HOP - outStart).toInt()
        val acc = acc
        for (n in 0 until N_FFT) {
            val w = window[n]
            acc[(at + n) * 2] += re[n] * w
            acc[(at + n) * 2 + 1] += im[n] * w
        }
    }

    /** [body] for each worker (0 until [workers]) at once, back when all are done. */
    private fun parallel(body: (Int) -> Unit) {
        if (workers == 1) { body(0); return }
        pool.invokeAll((0 until workers).map { w -> java.util.concurrent.Callable { body(w) } }).forEach { it.get() }
    }

    private fun ensureAcc(paddedEnd: Long) {
        val need = ((paddedEnd - outStart) * 2).toInt()
        if (need > acc.size) acc = acc.copyOf(need)
    }

    /**
     * The vocals' share of each bin in this chunk, by [method]. Each network's answer is its own soft mask (a sigmoid)
     * times the magnitudes, so with the vocals network alone (the light way, half the work) its mask is used as it is;
     * with both, they're weighed against each other as Spleeter does. The quick way needs no network ([centreMasks]).
     */
    private fun masks(): FloatArray {
        val how = method
        if (how == Separation.QUICK || vocals == null) { ran = Separation.QUICK; return centreMasks() }
        val shape = longArrayOf(2, 1, FRAMES.toLong(), BINS.toLong())
        val v = run(vocals, shape)
        val acc = accompaniment.takeIf { how == Separation.FULL }
        if (acc == null) {
            ran = Separation.LIGHT
            return FloatArray(v.size) { (v[it] / (mag[it] + EPS)).coerceIn(0f, 1f) }
        }
        ran = Separation.FULL
        val a = run(acc, shape)
        val m = FloatArray(v.size)
        for (i in m.indices) {
            val v2 = v[i] * v[i]
            val a2 = a[i] * a[i]
            m[i] = (v2 + EPS / 2) / (v2 + a2 + EPS)
        }
        return m
    }

    private var centre: FloatArray? = null
    /** [CENTRE_FROM], open to the quality measurement (SingSpeedTest) trying others. */
    internal var centreFrom = CENTRE_FROM

    /**
     * The quick way ([Separation.QUICK]): each bin's share of sound that's the same in both channels. Left and right
     * are compared over the bin's neighbours (a bin either side, a frame either side), as 2·Re(L·R̄) / (|L|² + |R|²),
     * 1 for sound dead in the middle and 0 for sound only on one side or out of phase; above [CENTRE_FROM] a growing
     * share of the bin counts as the voice's (all of it at 1), and only in the voice's range ([band]): the bass and kick
     * drum below it and the cymbals' air above it stay. A chunk that's mono throughout gets no mask at all (all of it is "in the
     * middle"): the song plays as it is rather than losing everything in that range.
     */
    private fun centreMasks(): FloatArray {
        val m = centre ?: FloatArray(2 * FRAMES * BINS).also { centre = it }
        quickChunks++
        // The networks' magnitudes aren't needed: their room holds each bin's Re(L·R̄), then (|L|² + |R|²) / 2.
        val power = FRAMES * BINS
        parallel { w ->
            for (f in w * FRAMES / workers until (w + 1) * FRAMES / workers) {
                val lr = specRe[0][f]; val li = specIm[0][f]; val rr = specRe[1][f]; val ri = specIm[1][f]
                for (k in 0 until BINS) {
                    mag[f * BINS + k] = lr[k] * rr[k] + li[k] * ri[k]
                    mag[power + f * BINS + k] = (lr[k] * lr[k] + li[k] * li[k] + rr[k] * rr[k] + ri[k] * ri[k]) / 2
                }
            }
        }
        // |L − R|² over |L|² + |R|²: none at all is a mono chunk.
        var side = 0.0
        var all = 0.0
        for (i in 0 until power) { all += mag[power + i]; side += mag[power + i] - mag[i] }
        if (side <= MONO * all) {
            monoChunks++
            java.util.Arrays.fill(m, 0f)
            return m
        }
        parallel { w ->
            for (f in w * FRAMES / workers until (w + 1) * FRAMES / workers) for (k in 0 until BINS) {
                var c = 0f
                var p = 0f
                for (g in maxOf(0, f - 1)..minOf(FRAMES - 1, f + 1)) for (q in maxOf(0, k - 1)..minOf(BINS - 1, k + 1)) {
                    c += mag[g * BINS + q]
                    p += mag[power + g * BINS + q]
                }
                val v = ((c / (p + EPS) - centreFrom) / (1 - centreFrom)).coerceIn(0f, 1f) * band[k]
                m[f * BINS + k] = v
                m[power + f * BINS + k] = v
            }
        }
        return m
    }

    private fun run(session: OrtSession, shape: LongArray): FloatArray {
        OnnxTensor.createTensor(env!!, FloatBuffer.wrap(mag), shape).use { x ->
            session.run(mapOf(session.inputNames.first() to x)).use { r ->
                val t = r[0] as OnnxTensor
                val buf = t.floatBuffer
                return FloatArray(buf.remaining()).also { buf.get(it) }
            }
        }
    }

    private fun List<FloatArray>.flatten(): FloatArray {
        val out = FloatArray(sumOf { it.size })
        var at = 0
        for (a in this) { System.arraycopy(a, 0, out, at, a.size); at += a.size }
        return out
    }
}
