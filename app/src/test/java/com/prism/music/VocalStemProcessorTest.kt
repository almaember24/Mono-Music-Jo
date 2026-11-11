package com.prism.music

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import com.prism.music.playback.sing.StemStore
import com.prism.music.playback.sing.VocalStemProcessor
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Sing's processor takes the vocals out of the music only once it has found where they line up, even when the
 * separated vocals are a little out (as two decodes of a file can be): here they're 37 samples late.
 */
class VocalStemProcessorTest {
    private val rate = 48_000

    private class OneSong(private val id: String, private val durationUs: Long) : Timeline() {
        override fun getWindowCount() = 1
        override fun getPeriodCount() = 1
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window =
            window.set(id, MediaItem.Builder().setMediaId(id).build(), null, 0, 0, 0, true, false, null, 0, durationUs, 0, 0, 0)
        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period = period.set(id, id, 0, durationUs, 0)
        override fun getIndexOfPeriod(uid: Any) = if (uid == id) 0 else C.INDEX_UNSET
        override fun getUidOfPeriod(periodIndex: Int): Any = id
    }

    private fun left0(out: FloatArray, backing: FloatArray) = FloatArray(out.size) { out[it] - backing[it] }

    /** With Sing off, the music goes through untouched, sample for sample (16-bit, so any rounding would show). */
    @Test
    fun singOffPassesTheMusicStraightThrough() {
        val source = object : VocalStemProcessor.Source {
            override val onNow = false
            override val levelNow = 1f
            override val store = StemStore(kotlin.io.path.createTempDirectory("sing").toFile())
        }
        val p = VocalStemProcessor(source)
        p.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        p.flush(AudioProcessor.StreamMetadata.Builder().setTimeline(OneSong("song1", 10_000_000L)).setPeriodUid("song1").setPositionOffsetUs(0).build())
        val rnd = Random(7)
        repeat(20) {
            val samples = ShortArray(2048) { rnd.nextInt(-32768, 32768).toShort() }
            val buf = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
            samples.forEach { buf.putShort(it) }
            buf.flip()
            p.queueInput(buf)
            val o = p.output.order(ByteOrder.nativeOrder()).asShortBuffer()
            val back = ShortArray(o.remaining()).also { o.get(it) }
            assertTrue("same length", back.size == samples.size)
            assertTrue("bit for bit", back.contentEquals(samples))
        }
    }

    @Test
    fun linesUpThenTakesTheVocalsOut() {
        val seconds = 14
        val n = rate * seconds
        val rnd = Random(3)
        // The backing: noise, a bass line and chords. The "voice": a sung melody with vibrato.
        val backing = FloatArray(n * 2) { i -> val t = (i / 2).toDouble() / rate; ((rnd.nextFloat() - 0.5f) * 0.12f + 0.15 * sin(2 * PI * 55 * t) + 0.08 * sin(2 * PI * 330 * t)).toFloat() }
        val voice = FloatArray(n * 2) { i -> val t = (i / 2).toDouble() / rate; (0.25 * sin(2 * PI * (440 + 6 * sin(2 * PI * 5 * t)) * t) * (0.6 + 0.4 * sin(2 * PI * 0.3 * t))).toFloat() }
        val music = FloatArray(n * 2) { backing[it] + voice[it] }

        // The separated vocals, written 37 samples late.
        val root = kotlin.io.path.createTempDirectory("sing").toFile()
        val store = StemStore(root)
        val late = 37
        store.writeMeta("song1", StemStore.Meta(rate, n.toLong(), complete = true))
        store.pcm("song1").outputStream().buffered().use { out ->
            for (f in 0 until n) for (ch in 0 until 2) {
                val src = f - late
                val v = if (src >= 0) voice[src * 2 + ch] else 0f
                val s = (v * 32767f).toInt().coerceIn(-32768, 32767)
                out.write(s and 0xff); out.write((s shr 8) and 0xff)
            }
        }
        val source = object : VocalStemProcessor.Source {
            override val onNow = true
            override val levelNow = 0f
            override val store = store
        }
        val p = VocalStemProcessor(source)
        p.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT))
        p.flush(AudioProcessor.StreamMetadata.Builder().setTimeline(OneSong("song1", seconds * 1_000_000L)).setPeriodUid("song1").setPositionOffsetUs(0).build())

        val out = FloatArray(n * 2)
        var at = 0
        val block = 1024
        while (at < n) {
            val frames = minOf(block, n - at)
            val buf = ByteBuffer.allocateDirect(frames * 8).order(ByteOrder.nativeOrder())
            for (i in 0 until frames * 2) buf.putFloat(music[at * 2 + i])
            buf.flip()
            p.queueInput(buf)
            val o = p.output
            val fb = o.order(ByteOrder.nativeOrder()).asFloatBuffer()
            fb.get(out, at * 2, frames * 2)
            at += frames
            // After 5 s of music (in real playback that's 5 s of time), the alignment thread has had its look.
            if (at >= rate * 5 && at < rate * 5 + block) {
                val until = System.currentTimeMillis() + 5_000
                while (p.alignment.first == null && System.currentTimeMillis() < until) Thread.sleep(50)
            }
        }
        fun rms(a: FloatArray, from: Int, to: Int) = sqrt((from * 2 until to * 2).sumOf { (a[it] * a[it]).toDouble() } / ((to - from) * 2))
        val tail = n - rate * 3
        val left = FloatArray(n * 2) { out[it] - backing[it] }
        // (The test's vocals are shifted 37 samples late, so its last 37 samples of voice aren't in them at all.)
        val end = n - rate / 10
        val voiceLeft = rms(left, tail, end) / rms(voice, tail, end)
        val start = rms(FloatArray(n * 2) { out[it] - music[it] }, 0, rate)
        println("[sing] alignment ${p.alignment}")
        println("[sing] voice left at the end: ${"%.3f".format(voiceLeft)} (dB ${"%.1f".format(20 * kotlin.math.log10(voiceLeft))}); change in the first second ${"%.4f".format(start)}")
        // Untouched until lined up…
        assertTrue("changed before lining up: $start", start < 1e-6)
        // …then the voice is gone (16-bit vocals: what's left is rounding, ~-70 dB).
        assertTrue("voice left $voiceLeft", voiceLeft < 0.01)
        root.deleteRecursively()
    }

    private fun writeStem(store: StemStore, id: String, frames: Int, complete: Boolean, sample: (Int) -> Float) {
        store.writeMeta(id, StemStore.Meta(rate, frames.toLong(), complete = complete))
        store.pcm(id).outputStream().buffered().use { out ->
            for (i in 0 until frames * 2) {
                val s = (sample(i) * 32767f).toInt().coerceIn(-32768, 32767)
                out.write(s and 0xff); out.write((s shr 8) and 0xff)
            }
        }
    }

    /**
     * 1.8.x, the owner's TV ("once in the Sing menu it just doesn't do anything"): the quick way's stand-in, done in
     * seconds, is taken out at once; when the song's own vocals reach the playing place, they take over there, from the
     * same frame: every buffer comes back as long as it went in, nothing is repeated or skipped, and the change glides.
     */
    @Test
    fun theQuickStandInThenTheSongsOwnVocalsFromTheSamePlace() {
        val seconds = 16
        val n = rate * seconds
        val rnd = Random(5)
        val backing = FloatArray(n * 2) { i -> val t = (i / 2).toDouble() / rate; ((rnd.nextFloat() - 0.5f) * 0.12f + 0.15 * sin(2 * PI * 55 * t) + 0.08 * sin(2 * PI * 330 * t)).toFloat() }
        val voice = FloatArray(n * 2) { i -> val t = (i / 2).toDouble() / rate; (0.25 * sin(2 * PI * (440 + 6 * sin(2 * PI * 5 * t)) * t) * (0.6 + 0.4 * sin(2 * PI * 0.3 * t))).toFloat() }
        val music = FloatArray(n * 2) { backing[it] + voice[it] }
        val root = kotlin.io.path.createTempDirectory("sing").toFile()
        val store = StemStore(root)
        // The stand-in: half the voice (the quick way leaves some in). The song's own: nothing yet.
        writeStem(store.quick!!, "song1", n, complete = true) { voice[it] * 0.5f }
        writeStem(store, "song1", 0, complete = false) { 0f }
        val source = object : VocalStemProcessor.Source {
            override val onNow = true
            override val levelNow = 0f
            override val store = store
        }
        val p = VocalStemProcessor(source)
        p.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT))
        p.flush(AudioProcessor.StreamMetadata.Builder().setTimeline(OneSong("song1", seconds * 1_000_000L)).setPeriodUid("song1").setPositionOffsetUs(0).build())
        val out = FloatArray(n * 2)
        var at = 0
        val block = 1024
        val handover = rate * 9
        while (at < n) {
            val frames = minOf(block, n - at)
            val buf = ByteBuffer.allocateDirect(frames * 8).order(ByteOrder.nativeOrder())
            for (i in 0 until frames * 2) buf.putFloat(music[at * 2 + i])
            buf.flip()
            p.queueInput(buf)
            val fb = p.output.order(ByteOrder.nativeOrder()).asFloatBuffer()
            assertTrue("a buffer came back as long as it went in", fb.remaining() == frames * 2)
            fb.get(out, at * 2, frames * 2)
            at += frames
            if (at >= rate * 5 && at < rate * 5 + block) {
                val until = System.currentTimeMillis() + 5_000
                while (p.alignment.first == null && System.currentTimeMillis() < until) Thread.sleep(50)
            }
            // 9 s in, the song's own separation has caught up (written whole, as a fast device would have it).
            if (at >= handover && at < handover + block) {
                writeStem(store, "song1", n, complete = true) { voice[it] }
                Thread.sleep(300)
            }
        }
        fun rms(a: FloatArray, from: Int, to: Int) = sqrt((from * 2 until to * 2).sumOf { (a[it] * a[it]).toDouble() } / ((to - from) * 2))
        val left = FloatArray(n * 2) { out[it] - backing[it] }
        // The stand-in at work, at its place: half the voice left, sample for sample.
        val quickErr = rms(FloatArray(n * 2) { left[it] - 0.5f * voice[it] }, rate * 6, rate * 8) / rms(voice, rate * 6, rate * 8)
        // The song's own vocals from a moment after they reached the place: the voice gone.
        val ownLeft = rms(left, rate * 11, rate * 15) / rms(voice, rate * 11, rate * 15)
        // The change: no jump bigger than the voice's own steps.
        var jump = 0f; var step = 0f
        for (i in handover * 2 until (handover + rate) * 2 - 2) {
            jump = maxOf(jump, kotlin.math.abs(left[i + 2] - left[i]))
            step = maxOf(step, kotlin.math.abs(voice[i + 2] - voice[i]))
        }
        println("[sing] stand-in error ${"%.4f".format(quickErr)}, own vocals left ${"%.4f".format(ownLeft)}, largest step at the change $jump (the voice's own $step)")
        assertTrue("the stand-in leaves half the voice, in place: $quickErr", quickErr < 0.02)
        assertTrue("the song's own vocals take it out: $ownLeft", ownLeft < 0.01)
        assertTrue("no click at the change: $jump vs $step", jump <= step)
        root.deleteRecursively()
    }
}
