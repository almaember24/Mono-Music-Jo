package com.prism.music.playback

import com.prism.music.playback.sing.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A song's key: its tonic (pitch class, 0 = C … 11 = B) and whether it's minor, with how sure the detection was.
 * DJ mode orders songs by the Camelot wheel ([camelot]): neighbours on it share all but one note, so they blend
 * without clashing.
 */
data class MusicKey(val tonic: Int, val minor: Boolean, val confidence: Float = 1f) {
    /** The Camelot wheel's hour (1–12): C major and A minor are 8, each step round the circle of fifths is one more. */
    val camelotNumber: Int get() = ((if (minor) tonic + 3 else tonic) * 7 + 7) % 12 + 1
    /** "8B" for C major, "8A" for A minor. */
    val camelot: String get() = "$camelotNumber${if (minor) "A" else "B"}"
    /** "C♯ minor", "B♭ major" (the spelling DJs use). */
    val name: String get() = if (minor) "${MINOR_NAMES[tonic]} minor" else "${NAMES[tonic]} major"
    /** Clear enough to plan around; an unclear key counts as unknown rather than being forced. */
    val sure: Boolean get() = confidence >= MIN_CONFIDENCE

    fun encode() = "$VERSION|$tonic|${if (minor) 1 else 0}|$confidence"

    /** The same key moved by [semitones] (DJ mode's harmonic shift). */
    fun shifted(semitones: Int) = copy(tonic = Math.floorMod(tonic + semitones, 12))

    companion object {
        /** Bumped when [KeyDetector] changes, so songs measured the old way are measured again. */
        const val VERSION = 1
        const val MIN_CONFIDENCE = 0.04f
        val NAMES = listOf("C", "D♭", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
        /** Minor keys are spelt as sharps where majors take flats (C♯ minor, G♯ minor), as they're usually written. */
        val MINOR_NAMES = listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "G♯", "A", "B♭", "B")

        fun decode(s: String): MusicKey? = runCatching {
            val p = s.split('|')
            if (p[0].toInt() != VERSION) null else MusicKey(p[1].toInt(), p[2] == "1", p[3].toFloat())
        }.getOrNull()

        /**
         * How well [a] flows into [b], 0 (the same key) to 1 (a clash), by the Camelot wheel: the relative major/minor
         * and the next hour either way are smooth; two hours away is usable; anything further clashes.
         */
        fun clash(a: MusicKey, b: MusicKey): Double {
            val d = abs(a.camelotNumber - b.camelotNumber).let { minOf(it, 12 - it) }
            val sameMode = a.minor == b.minor
            return when {
                d == 0 && sameMode -> 0.0
                d == 0 -> 0.2
                d == 1 && sameMode -> 0.25
                d == 1 -> 0.65
                d == 2 && sameMode -> 0.6
                else -> 1.0
            }
        }
    }
}

/**
 * Finds a song's key from its audio (mono, any rate from ~8 kHz up). The spectrum (0.74 s frames) is whitened so notes
 * stand out from drums and noise, the tuning is measured (records aren't always at A = 440 Hz), and the notes'
 * energy is folded into a 12-note chromagram, the bass apart from the rest so the root counts. The chromagram is
 * then matched against major and minor key profiles in all 12 keys; the confidence is how far the best key's match
 * is ahead of the best one on another hour of the Camelot wheel.
 */
object KeyDetector {
    // Key profiles (Temperley's, from the Kostka-Payne corpus): how much each scale degree is heard in that key.
    private val MAJOR = doubleArrayOf(0.748, 0.060, 0.488, 0.082, 0.670, 0.460, 0.096, 0.715, 0.104, 0.366, 0.057, 0.400)
    private val MINOR = doubleArrayOf(0.712, 0.084, 0.474, 0.618, 0.049, 0.460, 0.105, 0.747, 0.404, 0.067, 0.133, 0.330)

    private const val LOW_HZ = 55.0
    private const val BASS_TOP_HZ = 220.0
    private const val HIGH_HZ = 2_000.0
    private const val BASS_WEIGHT = 0.6

    /** The key, or null when the audio is too short or has no clear notes. */
    fun detect(pcm: FloatArray, rate: Int, count: Int = pcm.size): MusicKey? {
        val chroma = chroma(pcm, rate, count) ?: return null
        return best(chroma)
    }

    /**
     * The best key for [chroma] (12 values from C), with its confidence: how far ahead of the best key on another hour
     * of the wheel it is, scaled down when the chromagram is nearly flat (noise, speech, drums), where any key would
     * "fit" by chance.
     */
    fun best(chroma: DoubleArray): MusicKey? {
        if (chroma.sum() <= 0.0) return null
        val scores = DoubleArray(24) { k -> correlation(chroma, if (k < 12) MAJOR else MINOR, k % 12) }
        val top = scores.indices.maxBy { scores[it] }
        val key = MusicKey(top % 12, top >= 12)
        val other = scores.indices.filter { i -> MusicKey(i % 12, i >= 12).camelotNumber != key.camelotNumber }.maxOf { scores[it] }
        val margin = scores[top] - other
        return key.copy(confidence = (margin * (clarity(chroma) / CLEAR).coerceAtMost(1.0)).toFloat())
    }

    /** How uneven the chromagram is (its spread over its mean): ~0 for noise, higher the more a few notes dominate. */
    fun clarity(chroma: DoubleArray): Double {
        val m = chroma.average()
        if (m <= 0) return 0.0
        return sqrt(chroma.sumOf { (it - m) * (it - m) } / 12) / m
    }

    /** A chromagram at least this uneven counts fully (tuned on real songs, see KeyDetectorTest.realSongs). */
    private const val CLEAR = 0.25

    /** Pearson correlation of [chroma] with [profile] laid on [tonic]. */
    private fun correlation(chroma: DoubleArray, profile: DoubleArray, tonic: Int): Double {
        val mc = chroma.average(); val mp = profile.average()
        var num = 0.0; var dc = 0.0; var dp = 0.0
        for (i in 0 until 12) {
            val c = chroma[(i + tonic) % 12] - mc
            val p = profile[i] - mp
            num += c * p; dc += c * c; dp += p * p
        }
        return if (dc <= 0 || dp <= 0) 0.0 else num / sqrt(dc * dp)
    }

    /**
     * The song's chromagram (C … B) from the first [count] samples, or null when it's too short or nothing in it is a note.
     *
     * The frames' spectra live side by side in one array, whitened in place: three arrays a frame (2,000 of 12 KB each,
     * all large objects, for a 4-minute song) were most of a scan's garbage on Android (#373's log).
     */
    fun chroma(pcm: FloatArray, rate: Int, count: Int = pcm.size): DoubleArray? {
        var n = 1
        while (n * 2 <= rate * 0.75) n *= 2
        n *= 2
        val hop = n / 2
        if (count < n * 8) return null
        val fft = Fft(n)
        val window = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }
        val df = rate.toDouble() / n
        val lo = (LOW_HZ / df).toInt().coerceAtLeast(1)
        val hi = minOf((HIGH_HZ / df).toInt(), n / 2 - 2)
        val re = DoubleArray(n); val im = DoubleArray(n)
        val frames = (count - n) / hop + 1
        val width = hi + 2
        val spectra = DoubleArray(frames * width)
        val energies = DoubleArray(frames)
        for (f in 0 until frames) {
            val at = f * hop
            for (i in 0 until n) { re[i] = pcm[at + i] * window[i]; im[i] = 0.0 }
            fft.transform(re, im)
            val row = f * width
            var e = 0.0
            for (k in 0 until width) { val m = sqrt(re[k] * re[k] + im[k] * im[k]); spectra[row + k] = m; e += m * m }
            energies[f] = e
        }
        // Quiet frames (silence, fades) say nothing about the key.
        val loud = energies.sortedArray()[frames / 2] * 0.05
        // Whitening: each bin against its neighbourhood (a third of an octave), so peaks count, not the overall tilt.
        val prefix = DoubleArray(width + 1)
        for (f in 0 until frames) {
            if (energies[f] < loud) continue
            val row = f * width
            for (k in 0 until width) prefix[k + 1] = prefix[k] + spectra[row + k]
            for (k in 0 until width) {
                if (k < lo || k > hi) { spectra[row + k] = 0.0; continue }
                val half = maxOf(2, (k * 0.12).roundToInt())
                val a = maxOf(0, k - half); val b = minOf(width - 1, k + half)
                val mean = (prefix[b + 1] - prefix[a]) / (b - a + 1)
                spectra[row + k] = (spectra[row + k] - mean).coerceAtLeast(0.0)
            }
        }
        val tuning = tuningCents(spectra, energies, loud, width, df, lo, hi)
        // Each bin's note and weight. Higher notes in an octave cover more bins than lower ones (the FFT's bins are
        // evenly spaced in Hz), so each note's share is divided by its bins' total weight: noise comes out flat.
        val pcOf = IntArray(hi + 1) { -1 }
        val wOf = DoubleArray(hi + 1)
        val coverB = DoubleArray(12); val coverT = DoubleArray(12)
        for (k in lo..hi) {
            val midi = 69 + 12 * ln(k * df / 440.0) / ln(2.0) - tuning / 100
            val nearest = midi.roundToInt()
            val weight = 1 - 2 * abs(midi - nearest)
            if (weight <= 0) continue
            pcOf[k] = Math.floorMod(nearest, 12); wOf[k] = weight
            if (k * df < BASS_TOP_HZ) coverB[pcOf[k]] += weight else coverT[pcOf[k]] += weight
        }
        val bass = DoubleArray(12); val treble = DoubleArray(12)
        val fb = DoubleArray(12); val ft = DoubleArray(12)
        for (f in 0 until frames) {
            if (energies[f] < loud) continue
            val row = f * width
            fb.fill(0.0); ft.fill(0.0)
            for (k in lo..hi) {
                val v = spectra[row + k]
                val pc = pcOf[k]
                if (v <= 0 || pc < 0) continue
                if (k * df < BASS_TOP_HZ) fb[pc] += v * wOf[k] / coverB[pc] else ft[pc] += v * wOf[k] / coverT[pc]
            }
            // Each frame counts by its shape, not its loudness, so a loud chorus doesn't drown the verses.
            val sb = fb.sum(); val st = ft.sum()
            if (sb > 0) for (i in 0 until 12) bass[i] += fb[i] / sb
            if (st > 0) for (i in 0 until 12) treble[i] += ft[i] / st
        }
        val sb = bass.sum(); val st = treble.sum()
        if (st <= 0) return null
        return DoubleArray(12) { treble[it] / st + if (sb > 0) BASS_WEIGHT * bass[it] / sb else 0.0 }
    }

    /** How far (cents, −50…50) the recording sits from A = 440 Hz, from where its spectral peaks fall. */
    private fun tuningCents(white: DoubleArray, energies: DoubleArray, loud: Double, width: Int, df: Double, lo: Int, hi: Int): Double {
        var x = 0.0; var y = 0.0
        for (f in energies.indices) {
            if (energies[f] < loud) continue
            val row = f * width
            for (k in maxOf(lo, 2)..hi - 1) {
                val v = white[row + k]
                if (v <= 0 || v < white[row + k - 1] || v < white[row + k + 1]) continue
                // The peak's true frequency, between bins (parabolic).
                val a = white[row + k - 1]; val c = white[row + k + 1]
                val denom = a - 2 * v + c
                val shift = if (denom != 0.0) 0.5 * (a - c) / denom else 0.0
                val freq = (k + shift) * df
                if (freq < 100) continue
                val cents = 1200 * ln(freq / 440.0) / ln(2.0)
                val angle = 2 * PI * cents / 100
                x += v * cos(angle); y += v * sin(angle)
            }
        }
        if (x == 0.0 && y == 0.0) return 0.0
        return atan2(y, x) / (2 * PI) * 100
    }
}
