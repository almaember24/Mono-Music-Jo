package com.prism.music

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import com.prism.music.playback.EdgeSilenceProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * #210 (1.8.0): Skip silence trims only a song's very start and end, never a pause inside it. Songs went through
 * Media3's own skipper before, which shortened every pause longer than 0.15 s to a fraction of a second (run
 * against it, [pausesInsideASongStay]'s 2 s pause came out 0.17 s long).
 */
class EdgeSilenceTest {
    private val rate = 8_000

    // Started off zero, so its first sample is sound too.
    private fun tone(seconds: Double) = FloatArray((seconds * rate).toInt()) { (0.3 * sin(2 * PI * 440 * it / rate + 1.0)).toFloat() }
    private fun silence(seconds: Double) = FloatArray((seconds * rate).toInt())
    private fun cat(vararg parts: FloatArray) = parts.reduce { a, b -> a + b }

    /**
     * Songs one after another, as the player's timeline has them: each [songs]' name (its period's uid, which stays the
     * same when the queue changes around it) and length in seconds.
     */
    private class Songs(private val songs: List<Pair<String, Double>>) : Timeline() {
        override fun getWindowCount() = songs.size
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window = window.set(
            songs[windowIndex].first, MediaItem.Builder().setMediaId(songs[windowIndex].first).build(), null, 0, 0, 0, true, false,
            null, 0, (songs[windowIndex].second * 1e6).toLong(), windowIndex, windowIndex, 0,
        )
        override fun getPeriodCount() = songs.size
        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
            period.set(songs[periodIndex].first, songs[periodIndex].first, periodIndex, (songs[periodIndex].second * 1e6).toLong(), 0)
        override fun getIndexOfPeriod(uid: Any) = songs.indexOfFirst { it.first == uid }.takeIf { it >= 0 } ?: C.INDEX_UNSET
        override fun getUidOfPeriod(periodIndex: Int): Any = songs[periodIndex].first
    }

    private fun songs(lengths: List<Double>) = Songs(lengths.mapIndexed { i, s -> "song$i" to s })

    private fun processor() = EdgeSilenceProcessor().apply { enabled = true }

    /** [pcm] (mono, 16-bit) through [p] from the start of the first of [songs], a bit at a time as the player feeds it. */
    private fun play(p: AudioProcessor, pcm: FloatArray, songs: List<Double>, startUs: Long = 0): FloatArray =
        play(p, pcm, songs(songs), startUs)

    private fun play(p: AudioProcessor, pcm: FloatArray, songs: Songs, startUs: Long = 0, queueChange: Songs? = null): FloatArray {
        p.configure(AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_16BIT))
        p.flush(AudioProcessor.StreamMetadata.Builder().setTimeline(songs).setPeriodUid(songs.getUidOfPeriod(0)).setPositionOffsetUs(startUs).build())
        // The queue changes while the first song plays: the player says so, and doesn't flush the processors.
        queueChange?.let { (p as EdgeSilenceProcessor).timelineChanged(it) }
        val out = ArrayList<Float>()
        fun drain() { val o = p.output.order(ByteOrder.nativeOrder()); while (o.remaining() >= 2) out += o.short / 32768f }
        for (from in pcm.indices step 1_000) {
            val n = minOf(1_000, pcm.size - from)
            val b = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder())
            for (i in from until from + n) b.putShort((pcm[i] * 32767).toInt().toShort())
            b.flip()
            p.queueInput(b)
            drain()
        }
        p.queueEndOfStream()
        drain()
        return out.toFloatArray()
    }

    /** The silent stretches in [pcm], in seconds. */
    private fun pauses(pcm: FloatArray): List<Double> {
        val runs = ArrayList<Double>()
        var n = 0
        for (x in pcm) if (abs(x) < 0.002f) n++ else { if (n > 0) runs += n.toDouble() / rate; n = 0 }
        if (n > 0) runs += n.toDouble() / rate
        return runs.filter { it > 0.01 }
    }

    @Test fun pausesInsideASongStay() {
        val p = processor()
        val song = cat(silence(1.0), tone(3.0), silence(2.0), tone(3.0), silence(2.0))
        val out = play(p, song, listOf(11.0))
        assertEquals("the start's and the end's silence left out", 8.0, out.size.toDouble() / rate, 0.01)
        assertEquals("the pause inside it kept whole", listOf(2.0), pauses(out).map { Math.round(it * 100) / 100.0 })
        assertEquals("what's left out is counted (the position stays right)", 3 * rate.toLong(), p.skippedFrames)
    }

    @Test fun theNextSongInAGaplessRunTrimsItsOwnStart() {
        val p = processor()
        val out = play(p, cat(tone(3.0), silence(1.0), silence(1.5), tone(3.0), silence(0.5)), listOf(4.0, 5.0))
        assertEquals(6.0, out.size.toDouble() / rate, 0.01)
        assertTrue("no silence between the songs: ${pauses(out)}", pauses(out).isEmpty())
    }

    @Test fun soundComingBackNearTheEndIsKept() {
        // A stop two seconds before the end, then a last chord: not the end's silence.
        val song = cat(tone(5.0), silence(2.0), tone(1.0))
        val out = play(processor(), song, listOf(8.0))
        assertEquals(song.size, out.size)
        assertEquals(listOf(2.0), pauses(out).map { Math.round(it * 100) / 100.0 })
    }

    @Test fun aLongQuietStartIsPlayedWhole() {
        // 15 s near silent before the first sound: a quiet intro, not a gap. Its first 10 s used to be dropped.
        val p = processor()
        val song = cat(silence(15.0), tone(2.0))
        assertEquals(song.size, play(p, song, listOf(17.0)).size)
        assertEquals(0L, p.skippedFrames)
    }

    @Test fun aSongPutNextWithoutAFlushIsCountedByItsOwnLength() {
        // A plays with C next; Play next puts B between them. The player doesn't flush the processors for that, so the
        // move from A to B was counted by C's length: B's end and C's start stayed, and C's own pause, where the old
        // count ran out, was taken for a new song's silent start and dropped.
        val a = cat(tone(3.0), silence(1.0))
        val b = cat(silence(1.0), tone(4.0), silence(1.0))
        val c = cat(silence(0.5), tone(1.5), silence(2.0), tone(3.0), silence(1.0))
        val out = play(
            processor(), cat(a, b, c), Songs(listOf("A" to 4.0, "C" to 8.0)),
            queueChange = Songs(listOf("A" to 4.0, "B" to 6.0, "C" to 8.0)),
        )
        assertEquals("C's pause kept whole: ${pauses(out)}", listOf(2.0), pauses(out).map { Math.round(it * 100) / 100.0 })
        assertEquals("each song's own start and end left out", 3.0 + 4.0 + 6.5, out.size.toDouble() / rate, 0.01)
    }

    @Test fun aSeekIntoTheSongPlaysFromThere() {
        // Not the start of the song: nothing before the sound is skipped.
        val out = play(processor(), cat(silence(1.0), tone(2.0)), listOf(20.0), startUs = 5_000_000)
        assertEquals(3.0, out.size.toDouble() / rate, 0.01)
    }

    /**
     * The 1.8.0 review: a seek landing past a song's length as its timeline gives it (YouTube's lengths are a little
     * short of the stream), with a shorter song after it, left less than nothing to cut up to: the audio was read back,
     * or the buffer's limit set below its start (an exception on the playback thread).
     */
    @Test(timeout = 20_000) fun aSeekPastASongsStatedLengthGoesThrough() {
        val out = play(processor(), tone(1.0), listOf(2.0, 0.5), startUs = 3_000_000)
        // Its sound comes out once, none of it read back (the trimming only ever takes silence away).
        assertTrue("${out.size.toDouble() / rate} s", out.size <= tone(1.0).size && out.size >= tone(0.5).size)
    }

    @Test fun offItPassesEverything() {
        val song = cat(silence(1.0), tone(3.0), silence(2.0))
        val p = EdgeSilenceProcessor()
        assertEquals(song.size, play(p, song, listOf(6.0)).size)
        assertEquals(0L, p.skippedFrames)
    }
}
