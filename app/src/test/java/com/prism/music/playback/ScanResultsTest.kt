package com.prism.music.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** The scans read the audio with far less memory (1.8.2.1): what they find must not have changed by a hair. */
class ScanResultsTest {
    /** Half a minute of chords with a bit of noise and silence at both ends. */
    private fun music(rate: Int = RATE, seconds: Int = 40): FloatArray {
        val noise = Random(7)
        return FloatArray(rate * seconds) { i ->
            val t = i.toDouble() / rate
            if (t < 2 || t > seconds - 2) 0f
            else (0.3 * sin(2 * PI * 220 * t) + 0.2 * sin(2 * PI * 277.18 * t) + 0.2 * sin(2 * PI * 329.63 * t) +
                0.15 * sin(2 * PI * 110 * t) + 0.02 * (noise.nextDouble() - 0.5)).toFloat()
        }
    }

    @Test fun chromaIsWhatItWas() {
        val pcm = music()
        val before = doubleArrayOf(
            0.0019987456849991066, 0.39223196906352664, 0.0019425820633879184, 0.0019308020280750264, 0.3349885131349699,
            0.0018230230920638327, 0.001941170988798352, 0.0019351473762644102, 0.0017849106803340979, 0.8558025421673281,
            0.0017831651284626535, 0.0018374285917900023,
        )
        // As 1.8.2 worked it out (its frames each in arrays of their own), to the last digit.
        assertArrayEquals(before, KeyDetector.chroma(pcm, RATE)!!, 0.0)
    }

    @Test fun outOfSightOnlyTheNextSongIsScanned() {
        val up = listOf("now", "a", "b", "c", "d")
        assertEquals(listOf("now", "a", "b", "c"), MixTempo.worthScanning(up, "now", onScreen = true, readsPlaying = false, ahead = 3))
        assertEquals(listOf("a"), MixTempo.worthScanning(up, "now", onScreen = false, readsPlaying = false, ahead = 3))
        // Crossfade reads the ending of what's playing, wherever the listener is.
        assertEquals(listOf("now", "a"), MixTempo.worthScanning(up, "now", onScreen = false, readsPlaying = true, ahead = 3))
        assertEquals(listOf("a"), MixTempo.worthScanning(listOf("a", "b"), null, onScreen = false, readsPlaying = true, ahead = 3))
    }

    @Test fun videosAreMeasuredAheadOnlyForThoseWhoWatchThem() {
        val day = 86_400_000L
        val now = 100 * day
        assertEquals(false, VideoSync.watchesLately(0L, now))
        assertEquals(true, VideoSync.watchesLately(now - day, now))
        assertEquals(true, VideoSync.watchesLately(now - 13 * day, now))
        assertEquals(false, VideoSync.watchesLately(now - 15 * day, now))
    }

    /** #354: 1200 px for a 720 px screen is near three times the memory to fill and hand to the graphics chip. */
    /** A fade over the slide draws the whole player into a layer each frame: most of the stutter on an A16 (#354, #343). */
    @Test fun thePlayerSlidesWithNoFadeOnTop() {
        for (reduce in listOf(false, true)) {
            val enter = com.prism.music.ui.player.playerSlideIn(reduce).toString()
            val exit = com.prism.music.ui.player.playerSlideOut(reduce).toString()
            assert("Fade - null" in enter) { enter }
            assert("Fade - null" in exit) { exit }
            assert("Slide - null" !in enter) { enter }
            assert("Slide - null" !in exit) { exit }
        }
    }

    @Test fun thePlayersCoverIsNoBiggerThanTheScreenShowsIt() {
        assertEquals(720, com.prism.music.ui.player.playerCoverPx(720, 1600, fullBleed = false))
        // Full-bleed crops it to fill its height.
        assertEquals(1200, com.prism.music.ui.player.playerCoverPx(720, 1600, fullBleed = true))
        assertEquals(1000, com.prism.music.ui.player.playerCoverPx(600, 1000, fullBleed = true))
        assertEquals(1200, com.prism.music.ui.player.playerCoverPx(1344, 2992, fullBleed = false))
        assertEquals(720, com.prism.music.ui.player.playerCoverPx(1600, 720, fullBleed = false))
    }


    @Test fun onlyTheFirstCountSamplesAreRead() {
        val pcm = music()
        val roomy = pcm.copyOf(pcm.size + RATE * 5).also { it.fill(0.9f, pcm.size) }
        assertArrayEquals(KeyDetector.chroma(pcm, RATE)!!, KeyDetector.chroma(roomy, RATE, pcm.size)!!, 0.0)
        assertEquals(MixTempo.integratedLoudness(pcm, RATE)!!, MixTempo.integratedLoudness(roomy, RATE, pcm.size)!!, 0f)
        assertArrayEquals(com.prism.music.data.SongWaves.outline(pcm), com.prism.music.data.SongWaves.outline(roomy, pcm.size), 0f)
        val a = VideoSync.bandFeatures(pcm, RATE)
        val b = VideoSync.bandFeatures(roomy, RATE, pcm.size)
        assertEquals(a.size, b.size)
        for (i in a.indices) assertArrayEquals(a[i], b[i], 0f)
    }

    @Test fun roomForASongFitsItsLength() {
        assertEquals((240L + 2) * 11025, MixTempo.decodedSize(240_000, 11025).toLong())
        // A song whose length isn't known starts at three minutes.
        assertEquals((180L + 2) * 11025, MixTempo.decodedSize(0, 11025).toLong())
    }

    /** One sample at a time through the buffer, as the scans read it before. */
    private fun oneByOne(buf: ByteBuffer, frames: Int, channels: Int, isFloat: Boolean, factor: Int): Pair<List<Float>, Float> {
        val out = ArrayList<Float>()
        var acc = 0f; var n = 0; var peak = 0f
        val b = buf.duplicate().order(ByteOrder.nativeOrder())
        for (f in 0 until frames) {
            var m = 0f
            for (c in 0 until channels) {
                val v = if (isFloat) b.float else b.short / 32768f
                if (v > peak) peak = v else if (-v > peak) peak = -v
                m += v
            }
            acc += m / channels
            if (++n == factor) { out += acc / factor; acc = 0f; n = 0 }
        }
        return out to peak
    }

    private fun check(channels: Int, isFloat: Boolean, factor: Int) {
        val random = Random(channels * 31 + factor)
        val frames = 5003
        val bytes = frames * channels * (if (isFloat) 4 else 2)
        val whole = ByteBuffer.allocateDirect(bytes + 16).order(ByteOrder.nativeOrder())
        whole.position(16)
        for (i in 0 until frames * channels) {
            if (isFloat) whole.putFloat(random.nextFloat() * 2 - 1) else whole.putShort(random.nextInt(-32768, 32768).toShort())
        }
        whole.position(16); whole.limit(16 + bytes)
        val (expected, peak) = oneByOne(whole, frames, channels, isFloat, factor)

        // In pieces, as a decoder hands it over.
        val mono = Downmix()
        val got = ArrayList<Float>()
        var done = 0
        while (done < frames) {
            val piece = minOf(1024, frames - done)
            val at = 16 + done * channels * (if (isFloat) 4 else 2)
            val part = whole.duplicate().order(ByteOrder.nativeOrder())
            part.position(at); part.limit(at + piece * channels * (if (isFloat) 4 else 2))
            mono.add(part, piece, channels, isFloat, factor) { got += it }
            done += piece
        }
        assertEquals(expected, got)
        assertEquals(peak, mono.peak, 0f)
    }

    @Test fun downmixIsWhatReadingEachSampleGave() {
        for (channels in listOf(1, 2, 6)) for (isFloat in listOf(false, true)) for (factor in listOf(1, 3, 4)) check(channels, isFloat, factor)
    }

    private companion object { const val RATE = 11_025 }
}
