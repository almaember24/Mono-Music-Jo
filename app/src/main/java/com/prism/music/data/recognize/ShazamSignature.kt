package com.prism.music.data.recognize

import com.prism.music.playback.sing.Fft
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max

/**
 * Shazam's audio fingerprint ("signature"), made on the phone from 16 kHz mono audio: the spectrum's strongest local
 * peaks (frequency and loudness, per frame), in four frequency bands. Only this is sent to Shazam, never the sound.
 *
 * A port of SongRec's `algorithm.rs` and `signature_format.rs` (Marin M., GPL-3.0; https://github.com/marin-m/SongRec),
 * which worked the format out: 2048-sample Hann-windowed FFTs every 128 samples, peaks spread over frequency and time,
 * a peak kept when it beats its neighbours 46 frames back, and the binary layout below.
 */
class ShazamSignature private constructor(
    val sampleRateHz: Int,
    val numberSamples: Int,
    /** Band (0: 250–520 Hz, 1: 520–1450 Hz, 2: 1450–3500 Hz, 3: 3500–5500 Hz) -> its peaks in time order. */
    val bands: Map<Int, List<Peak>>,
) {
    data class Peak(val fftPassNumber: Int, val magnitude: Int, val correctedBin: Int)

    /** How long the audio was, in ms (what the request reports). */
    val sampleMs: Int get() = (numberSamples.toFloat() / sampleRateHz * 1000).toInt()

    fun peakCount() = bands.values.sumOf { it.size }

    /** The binary signature (little-endian), with its CRC-32 and sizes filled in. */
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        fun u32(v: Long) { out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()) }
        u32(0xcafe2580L)           // magic 1
        u32(0)                     // CRC-32, below
        u32(0)                     // size minus the header, below
        u32(0x94119c00L)           // magic 2
        u32(0); u32(0); u32(0)     // void
        u32(sampleRateId(sampleRateHz).toLong() shl 27)
        u32(0); u32(0)             // void
        u32((numberSamples + (sampleRateHz * 0.24f).toInt()).toLong())
        u32(((15 shl 19) + 0x40000).toLong())
        u32(0x40000000L)
        u32(0)                     // size minus the header again, below
        for ((band, peaks) in bands.toSortedMap()) {
            val p = ByteArrayOutputStream()
            var pass = 0
            for (peak in peaks) {
                if (peak.fftPassNumber - pass >= 255) {
                    p.write(0xff)
                    p.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(peak.fftPassNumber).array())
                    pass = peak.fftPassNumber
                }
                p.write(peak.fftPassNumber - pass)
                p.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(peak.magnitude.toShort()).array())
                p.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(peak.correctedBin.toShort()).array())
                pass = peak.fftPassNumber
            }
            val bytes = p.toByteArray()
            u32(0x60030040L + band)
            u32(bytes.size.toLong())
            out.write(bytes)
            repeat((4 - bytes.size % 4) % 4) { out.write(0) }
        }
        val buf = out.toByteArray()
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(8, buf.size - 48)
        bb.putInt(52, buf.size - 48)
        val crc = CRC32().apply { update(buf, 8, buf.size - 8) }.value
        bb.putInt(4, crc.toInt())
        return buf
    }

    /** The signature as Shazam's request carries it. */
    fun uri(): String = "data:audio/vnd.shazam.sig;base64," + Base64.getEncoder().encodeToString(encode())

    companion object {
        const val RATE = 16_000
        private const val WINDOW = 2048
        private const val HOP = 128
        private const val BINS = 1025

        private fun sampleRateId(hz: Int) = when (hz) { 8000 -> 1; 11025 -> 2; 16000 -> 3; 32000 -> 4; 44100 -> 5; 48000 -> 6; else -> error("unsupported rate $hz") }

        /** numpy's hanning(2050)[1:-1]. */
        private val hann = FloatArray(WINDOW) { i -> (0.5 - 0.5 * cos(2 * PI * (i + 1) / (WINDOW + 1))).toFloat() }

        /** The signature of [samples] (16 kHz mono, 16-bit). */
        fun of(samples: ShortArray): ShazamSignature = Generator(samples.size).run {
            var i = 0
            while (i + HOP <= samples.size) {
                feed(samples, i)
                i += HOP
            }
            result()
        }

        /** Reads back a binary signature (for tests, and for queued ones). */
        fun decode(bytes: ByteArray): ShazamSignature {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bb.getInt(0) == 0xcafe2580.toInt()) { "not a signature" }
            val rateId = (bb.getInt(28) ushr 27)
            val rate = mapOf(1 to 8000, 2 to 11025, 3 to 16000, 4 to 32000, 5 to 44100, 6 to 48000).getValue(rateId)
            val samples = bb.getInt(40) - (rate * 0.24f).toInt()
            val bands = sortedMapOf<Int, List<Peak>>()
            var at = 56
            while (at + 8 <= bytes.size) {
                val band = bb.getInt(at) - 0x60030040; val size = bb.getInt(at + 4)
                at += 8
                val peaks = ArrayList<Peak>()
                var p = at; var pass = 0
                while (p < at + size) {
                    var off = bytes[p].toInt() and 0xff
                    if (off == 0xff) { pass = bb.getInt(p + 1); p += 5; off = bytes[p].toInt() and 0xff }
                    pass += off
                    peaks += Peak(pass, bb.getShort(p + 1).toInt() and 0xffff, bb.getShort(p + 3).toInt() and 0xffff)
                    p += 5
                }
                bands[band] = peaks
                at += size + (4 - size % 4) % 4
            }
            return ShazamSignature(rate, samples, bands)
        }
    }

    /** SongRec's SignatureGenerator, as it is (ring buffers of samples, FFT outputs and spread outputs). */
    private class Generator(private val total: Int) {
        private val samples = ShortArray(WINDOW)
        private var samplePos = 0
        private val fftOutputs = Array(256) { FloatArray(BINS) }
        private var fftPos = 0
        private val spread = Array(256) { FloatArray(BINS) }
        private var spreadPos = 0
        private var spreadWritten = 0
        private val fft = Fft(WINDOW)
        private val re = DoubleArray(WINDOW)
        private val im = DoubleArray(WINDOW)
        private val bands = sortedMapOf<Int, MutableList<Peak>>()

        fun feed(input: ShortArray, from: Int) {
            doFft(input, from)
            doPeakSpreading()
            spreadWritten++
            if (spreadWritten >= 46) doPeakRecognition()
        }

        fun result() = ShazamSignature(RATE, total, bands)

        private fun doFft(input: ShortArray, from: Int) {
            System.arraycopy(input, from, samples, samplePos, HOP)
            samplePos = (samplePos + HOP) and (WINDOW - 1)
            for (i in 0 until WINDOW) {
                re[i] = (samples[(i + samplePos) and (WINDOW - 1)] * hann[i]).toDouble()
                im[i] = 0.0
            }
            fft.transform(re, im)
            val out = fftOutputs[fftPos]
            for (k in 0 until BINS) out[k] = max(((re[k] * re[k] + im[k] * im[k]) / (1 shl 17)).toFloat(), 1e-10f)
            fftPos = (fftPos + 1) and 255
        }

        private fun doPeakSpreading() {
            val real = fftOutputs[(fftPos - 1) and 255]
            val s = spread[spreadPos]
            System.arraycopy(real, 0, s, 0, BINS)
            for (p in 0..1022) s[p] = max(max(s[p], s[p + 1]), s[p + 2])
            val copy = s.copyOf()
            for (p in 0 until BINS) for (former in intArrayOf(1, 3, 6)) {
                val f = spread[(spreadPos - former) and 255]
                f[p] = max(f[p], copy[p])
            }
            spreadPos = (spreadPos + 1) and 255
        }

        private fun doPeakRecognition() {
            val m46 = fftOutputs[(fftPos - 46) and 255]
            val m49 = spread[(spreadPos - 49) and 255]
            for (bin in 10..1014) {
                val v = m46[bin]
                if (v < 1f / 64 || v < m49[bin - 1]) continue
                var maxNeighbor = 0f
                for (o in intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)) maxNeighbor = max(maxNeighbor, m49[bin + o])
                if (v <= maxNeighbor) continue
                var maxOther = maxNeighbor
                for (o in intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249)) {
                    maxOther = max(maxOther, spread[(spreadPos + o) and 255][bin - 1])
                }
                if (v <= maxOther) continue
                val pass = spreadWritten - 46
                fun mag(x: Float) = max(ln(x), 1f / 64) * 1477.3f + 6144f
                val peak = mag(v)
                val before = mag(m46[bin - 1])
                val after = mag(m46[bin + 1])
                val variation1 = peak * 2 - before - after
                if (variation1 <= 0f) continue
                val variation2 = (after - before) * 32 / variation1
                val corrected = bin * 64 + variation2.toInt()
                val hz = corrected * (RATE / 2f / 1024f / 64f)
                val band = when (hz.toInt()) {
                    in 250..519 -> 0
                    in 520..1449 -> 1
                    in 1450..3499 -> 2
                    in 3500..5500 -> 3
                    else -> continue
                }
                bands.getOrPut(band) { ArrayList() } += Peak(pass, peak.toInt(), corrected)
            }
        }
    }
}
