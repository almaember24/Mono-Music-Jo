package com.prism.music

import com.prism.music.playback.KeyDetector
import com.prism.music.playback.MusicKey
import com.prism.music.playback.QuickScan
import com.prism.music.playback.Tempo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

class KeyDetectorTest {
    private val rate = 11_025

    /** Chords (MIDI notes) of [beats] seconds each, as rich tones (6 partials) over a bass, with a little noise. */
    private fun progression(chords: List<List<Int>>, seconds: Double = 2.0, cents: Double = 0.0, repeats: Int = 6): FloatArray {
        val per = (seconds * rate).toInt()
        val out = FloatArray(per * chords.size * repeats)
        val rnd = java.util.Random(7)
        for (r in 0 until repeats) for ((ci, chord) in chords.withIndex()) {
            val start = (r * chords.size + ci) * per
            val notes = chord + (chord.first() - 24)
            for (i in 0 until per) {
                val t = i.toDouble() / rate
                var s = 0.0
                for (m in notes) {
                    val f = 440.0 * 2.0.pow((m - 69 + cents / 100) / 12)
                    for (h in 1..6) if (f * h < rate / 2) s += sin(2 * PI * f * h * t) / h
                }
                val env = minOf(1.0, i / 200.0) * minOf(1.0, (per - i) / 200.0)
                out[start + i] = (0.08 * s * env + 0.02 * rnd.nextGaussian()).toFloat()
            }
        }
        return out
    }

    private fun triad(root: Int, minor: Boolean = false) = listOf(root, root + if (minor) 3 else 4, root + 7)

    @Test
    fun majorProgressionIsFound() {
        // D major: I – IV – V – vi – IV – V – I
        val d = 62
        val key = KeyDetector.detect(progression(listOf(triad(d), triad(d + 5), triad(d + 7), triad(d + 9, true), triad(d + 5), triad(d + 7), triad(d))), rate)
        assertNotNull(key)
        assertEquals("D major", key!!.name)
        assertEquals("10B", key.camelot)
        assertTrue("confidence ${key.confidence}", key.sure)
    }

    @Test
    fun minorProgressionIsFound() {
        // A minor: i – iv – V (with its raised leading note) – i – VI – iv – V
        val a = 57
        val key = KeyDetector.detect(progression(listOf(triad(a, true), triad(a + 5, true), triad(a + 7), triad(a, true), triad(a + 8), triad(a + 5, true), triad(a + 7))), rate)
        assertEquals("A minor", key!!.name)
        assertEquals("8A", key.camelot)
    }

    @Test
    fun offTuningRecordingStillLandsOnItsKey() {
        // E♭ major recorded 40 cents sharp (between E♭ and E): the tuning estimate pulls it back.
        val eb = 63
        val key = KeyDetector.detect(progression(listOf(triad(eb), triad(eb + 5), triad(eb + 7), triad(eb)), cents = 40.0), rate)
        assertEquals("E♭ major", key!!.name)
    }

    @Test
    fun noiseHasNoClearKey() {
        val rnd = java.util.Random(3)
        val noise = FloatArray(rate * 30) { (0.1 * rnd.nextGaussian()).toFloat() }
        val key = KeyDetector.detect(noise, rate)
        println("noise clarity %.3f".format(KeyDetector.chroma(noise, rate)?.let(KeyDetector::clarity) ?: 0.0))
        assertTrue("noise read as ${key?.name} (${key?.confidence})", key == null || !key.sure)
    }

    @Test
    fun camelotWheel() {
        assertEquals("8B", MusicKey(0, false).camelot)   // C
        assertEquals("8A", MusicKey(9, true).camelot)    // A minor
        assertEquals("12A", MusicKey(1, true).camelot)   // C♯ minor
        assertEquals("4B", MusicKey(8, false).camelot)   // A♭
        assertEquals("3A", MusicKey(10, true).camelot)   // B♭ minor
        assertEquals("1B", MusicKey(11, false).camelot)  // B
        assertEquals(0.0, MusicKey.clash(MusicKey(0, false), MusicKey(0, false)), 0.0)
        assertTrue(MusicKey.clash(MusicKey(0, false), MusicKey(9, true)) < 0.3)   // relative
        assertTrue(MusicKey.clash(MusicKey(0, false), MusicKey(7, false)) < 0.3)  // a fifth up
        assertEquals(1.0, MusicKey.clash(MusicKey(0, false), MusicKey(6, false)), 0.0)  // tritone
        val k = MusicKey(4, true, 0.12f)
        assertEquals(k, MusicKey.decode(k.encode()))
    }

    /**
     * Real songs whose keys are well documented, decoded by the phone (debug build: `mix_dump` + `scan_list.txt`,
     * then `adb pull` the `mix_<id>_<rate>.f32` files to a folder named in KEY_DUMPS). Prints each song's result and
     * the accuracy: exact, relative major/minor, a fifth away (both are Camelot neighbours, so still smooth to mix).
     */
    @Test
    fun realSongs() {
        val dir = System.getenv("KEY_DUMPS")?.let(::File)
        assumeTrue("KEY_DUMPS not set", dir != null && dir.isDirectory)
        val expected = mapOf(
            "x9Qv47HpEqw" to "C minor", "UQ8cXH7qbVU" to "A major", "yjo_aXygRDI" to "F minor", "xTvyyoF_LZY" to "C♯ minor",
            "ORrFJ63nlcA" to "A♭ major", "ZD6rXLXZOEI" to "G minor", "b72gdhV_rXM" to "E minor", "7CTJcHjkq0E" to "F♯ minor",
            "FXovf5dsRTw" to "B minor", "Kx7B-XvmFtE" to "B♭ minor", "sQtnhwU2R9Y" to "A♭ major", "GnUW4AF1LZo" to "F major",
            "7TDeBi34OtE" to "F minor", "HzdD8kbDzZA" to "A major", "BciS5krYL80" to "B minor", "pRIZohFFOMo" to "D♭ major",
            "mM3Vmn4pdog" to "E major", "hpSrLjc5SMs" to "F♯ minor", "9RfVp-GhKfs" to "G major", "b-D5yAAXYng" to "A major",
            "8jjbhrR15Y8" to "A minor", "jg51kFe7CWU" to "D minor", "S-LO6dctBms" to "C minor", "nXMPx4DDVWM" to "A minor",
            "2NiyrtYegso" to "B minor", "tYvFa2ARD24" to "D minor", "dJqxQzxLbHM" to "F minor", "J7p4bzqLvCw" to "F minor",
        )
        var exact = 0; var near = 0; var total = 0
        for ((id, want) in expected) {
            val file = dir!!.listFiles { f -> f.name.startsWith("mix_${id}_") && f.name.endsWith(".f32") }?.firstOrNull() ?: continue
            val sr = file.name.removeSuffix(".f32").substringAfterLast('_').toInt()
            val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val pcm = FloatArray(bytes.remaining()).also { bytes.get(it) }
            val chroma = KeyDetector.chroma(pcm, sr)
            val key = chroma?.let(KeyDetector::best)
            println("KEY $id clarity %.3f".format(chroma?.let(KeyDetector::clarity) ?: 0.0))
            val wantKey = MusicKey((0 until 24).first { MusicKey(it % 12, it >= 12).name == want } % 12, want.endsWith("minor"))
            total++
            val verdict = when {
                key == null -> "none"
                key.name == want -> { exact++; near++; "exact" }
                key.camelotNumber == wantKey.camelotNumber -> { near++; "relative" }
                MusicKey.clash(key, wantKey) <= 0.25 -> { near++; "fifth" }
                else -> "wrong"
            }
            println("KEY $id  want $want (${wantKey.camelot})  got ${key?.name} (${key?.camelot}, %.3f)  $verdict".format(key?.confidence ?: 0f))
        }
        println("KEY accuracy: exact $exact/$total, within a Camelot step $near/$total")
        assumeTrue(total > 0)
        assertTrue("only $near of $total within a Camelot step", near >= total * 0.75)
    }

    /**
     * DJ mode's quick scan ([QuickScan]: three 25 s stretches) on the same songs: its key should be about as right as
     * the whole song's, and its tempo the same.
     */
    @Test
    fun realSongsQuick() {
        val dir = System.getenv("KEY_DUMPS")?.let(::File)
        assumeTrue("KEY_DUMPS not set", dir != null && dir.isDirectory)
        var near = 0; var exact = 0; var total = 0; var sameTempo = 0; var tempos = 0
        for (file in dir!!.listFiles { f -> f.name.endsWith(".f32") }!!.sortedBy { it.name }) {
            val id = file.name.removePrefix("mix_").substringBeforeLast('_')
            val want = EXPECTED[id] ?: continue
            val sr = file.name.removeSuffix(".f32").substringAfterLast('_').toInt()
            val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val pcm = FloatArray(bytes.remaining()).also { bytes.get(it) }
            val pieces = QuickScan.windows(pcm.size * 1000L / sr).map { (start, len) ->
                val from = (start * sr / 1000).toInt().coerceIn(0, pcm.size)
                pcm.copyOfRange(from, (from + (len * sr / 1000).toInt()).coerceAtMost(pcm.size))
            }
            val started = System.nanoTime()
            val quick = QuickScan.analyse(pieces, sr)
            val quickMs = (System.nanoTime() - started) / 1_000_000
            val whole = Tempo.analyze(com.prism.music.playback.VideoSync.bandFeatures(pcm, sr), (sr / 100) * 1000.0 / sr)
            val wantKey = MusicKey((0 until 24).first { MusicKey(it % 12, it >= 12).name == want } % 12, want.endsWith("minor"))
            val key = quick.key
            total++
            when {
                key == null -> {}
                key.name == want -> { exact++; near++ }
                key.camelotNumber == wantKey.camelotNumber || MusicKey.clash(key, wantKey) <= 0.25 -> near++
            }
            if (whole != null && quick.tempo != null) {
                tempos++
                // Octave-free: 85 and 170 are the same tempo to a DJ.
                val ratio = quick.tempo!!.bpm / whole.bpm
                if (listOf(0.5, 1.0, 2.0).any { kotlin.math.abs(ratio - it) < 0.03 * it }) sameTempo++
            }
            println("QUICK $id want $want got ${key?.name}  tempo quick %.1f whole %.1f  (%d ms)".format(quick.tempo?.bpm ?: 0.0, whole?.bpm ?: 0.0, quickMs))
        }
        println("QUICK accuracy: exact $exact/$total, within a Camelot step $near/$total; tempo same as whole song $sameTempo/$tempos")
        assumeTrue(total > 0)
        assertTrue("only $near of $total within a Camelot step", near >= total * 0.75)
        assertTrue("tempo agreed on only $sameTempo of $tempos", sameTempo >= tempos * 0.8)
    }

    private companion object {
        val EXPECTED = mapOf(
            "x9Qv47HpEqw" to "C minor", "UQ8cXH7qbVU" to "A major", "yjo_aXygRDI" to "F minor", "xTvyyoF_LZY" to "C♯ minor",
            "ORrFJ63nlcA" to "A♭ major", "ZD6rXLXZOEI" to "G minor", "b72gdhV_rXM" to "E minor", "7CTJcHjkq0E" to "F♯ minor",
            "FXovf5dsRTw" to "B minor", "Kx7B-XvmFtE" to "B♭ minor", "sQtnhwU2R9Y" to "A♭ major", "GnUW4AF1LZo" to "F major",
            "7TDeBi34OtE" to "F minor", "HzdD8kbDzZA" to "A major", "BciS5krYL80" to "B minor", "pRIZohFFOMo" to "D♭ major",
            "mM3Vmn4pdog" to "E major", "hpSrLjc5SMs" to "F♯ minor", "9RfVp-GhKfs" to "G major", "b-D5yAAXYng" to "A major",
            "8jjbhrR15Y8" to "A minor", "jg51kFe7CWU" to "D minor", "S-LO6dctBms" to "C minor", "nXMPx4DDVWM" to "A minor",
            "2NiyrtYegso" to "B minor", "tYvFa2ARD24" to "D minor", "dJqxQzxLbHM" to "F minor", "J7p4bzqLvCw" to "F minor",
        )
    }
}
