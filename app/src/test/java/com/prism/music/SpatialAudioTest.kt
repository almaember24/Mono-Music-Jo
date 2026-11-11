package com.prism.music

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.prism.music.data.CategoryRanker
import com.prism.music.data.model.MoodItem
import com.prism.music.data.model.Shelf
import com.prism.music.playback.SpatialAudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin

class SpatialAudioTest {
    private fun run(p: SpatialAudioProcessor, frames: Int): ShortArray {
        val inBuf = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val l = (sin(i * 2 * Math.PI * 440 / 48000) * 0.8 * 32767).toInt().toShort()
            val r = (sin(i * 2 * Math.PI * 660 / 48000) * 0.8 * 32767).toInt().toShort()
            inBuf.putShort(l); inBuf.putShort(r)
        }
        inBuf.flip()
        p.queueInput(inBuf)
        val out = p.output
        return ShortArray(out.remaining() / 2) { out.short }
    }

    @Test
    fun spatialProcessorPassesThroughWhenOffAndStaysInRangeWhenOn() {
        val p = SpatialAudioProcessor()
        p.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val dry = run(p, 4800)
        assertEquals(9600, dry.size)
        assertEquals((sin(100 * 2 * Math.PI * 440 / 48000) * 0.8 * 32767).toInt().toShort(), dry[200])

        p.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        p.configure(true, 1f)
        val wet = run(p, 48000)
        val tail = wet.copyOfRange(wet.size / 2, wet.size)
        val diff = tail.indices.count { abs(tail[it] - dry[it % dry.size]) > 500 }
        println("[spatial] changed samples: $diff / ${tail.size}, peak ${tail.maxOf { abs(it.toInt()) }}")
        assertTrue("effect audible", diff > tail.size / 10)
        assertTrue("no hard clipping", tail.count { abs(it.toInt()) >= 32767 } == 0)
    }

    @Test
    fun rankerPutsListeningGenresFirst() {
        fun m(t: String) = MoodItem(t, t, null, 0)
        val shelves = listOf(
            Shelf("Moods & moments", items = listOf("Chill", "Commute", "Energize", "Feel good", "Focus", "Gaming", "Party", "Romance", "Sad", "Sleep", "Workout").map(::m)),
            Shelf("Genres", items = listOf("African", "Arabic", "Blues", "Bollywood & Indian", "Hip-hop", "Indie & alternative", "J-Pop", "K-Pop", "Pop", "R&B & soul", "Rock").map(::m)),
        )
        val r = CategoryRanker.rank(shelves, mapOf("Hip-hop" to 0.6, "R&B & soul" to 0.3, "Pop" to 0.1), hour = 20)
        println("[ranker] picks: " + r.picks.take(8).joinToString { it.title })
        println("[ranker] genres: " + r.sections[1].second.take(5).joinToString { it.title })
        assertEquals("Hip-hop", r.sections[1].second[0].title)
        assertEquals("R&B & soul", r.sections[1].second[1].title)
        assertTrue(r.picks.take(4).any { it.title == "Workout" || it.title == "Party" })
        // Regional tiles are left out altogether.
        val titles = r.sections.flatMap { it.second }.map { it.title } + r.picks.map { it.title }
        listOf("African", "Arabic", "Bollywood & Indian", "J-Pop", "K-Pop").forEach { assertTrue("dropped $it", it !in titles) }
        assertTrue("Pop kept", "Pop" in titles)
    }
}
