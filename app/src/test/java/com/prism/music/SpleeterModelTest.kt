package com.prism.music

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.prism.music.playback.sing.Resampler
import com.prism.music.playback.sing.Spleeter
import org.junit.Assume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Sing's Spleeter on real music, with the real models (when SPLEETER_DIR points at the unpacked model and
 * SING_TEST_WAV at a 16-bit WAV): the vocals come out the song's length, lined up, with a plausible share of it.
 */
class SpleeterModelTest {
    @Test
    fun separatesASong() {
        val dir = System.getenv("SPLEETER_DIR")?.let(::File)
        val wav = System.getenv("SING_TEST_WAV")?.let(::File)
        Assume.assumeTrue(dir?.isDirectory == true && wav?.isFile == true)
        val (pcm, rate, channels) = readWav(wav!!)
        val stereo = if (channels == 2) pcm else FloatArray(pcm.size * 2) { pcm[it / 2] }
        val song = Resampler.convert(stereo, 2, rate, Spleeter.RATE)
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
        val sp = Spleeter(env, env.createSession(File(dir, "vocals.fp16.onnx").path, opts), env.createSession(File(dir, "accompaniment.fp16.onnx").path, opts))
        val start = System.nanoTime()
        val vocals = sp.feed(song) + sp.finish()
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(song.size, vocals.size)
        fun rms(a: FloatArray) = sqrt(a.fold(0.0) { s, v -> s + v * v } / a.size)
        val share = rms(vocals) / rms(song)
        val rest = FloatArray(song.size) { song[it] - vocals[it] }
        println("[spleeter] ${song.size / 2 / Spleeter.RATE.toDouble()} s of audio in $ms ms; vocals rms share ${"%.2f".format(share)}, instrumental ${"%.2f".format(rms(rest) / rms(song))}")
        assertTrue("vocals share $share", share in 0.05..0.95)
    }

    private fun readWav(f: File): Triple<FloatArray, Int, Int> {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val channels = b.getShort(22).toInt()
        val rate = b.getInt(24)
        var p = 12
        while (p < b.limit() - 8) {
            val id = String(ByteArray(4) { b.get(p + it) })
            val size = b.getInt(p + 4)
            if (id == "data") {
                val n = size / 2
                return Triple(FloatArray(n) { b.getShort(p + 8 + it * 2) / 32768f }, rate, channels)
            }
            p += 8 + size
        }
        error("no data chunk")
    }
}
