package com.prism.music

import com.prism.music.data.recognize.Shazam
import com.prism.music.data.recognize.ShazamResult
import com.prism.music.data.recognize.ShazamSignature
import com.prism.music.playback.sing.Resampler
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.sin

class ShazamTest {
    /** A few seconds of chords: enough peaks to fill every band. */
    private fun tones(seconds: Int): ShortArray {
        val n = ShazamSignature.RATE * seconds
        return ShortArray(n) { i ->
            val t = i.toDouble() / ShazamSignature.RATE
            val f = listOf(330.0, 880.0, 2000.0, 4200.0)[(i / 4000) % 4]
            (6000 * (sin(2 * PI * f * t) + 0.5 * sin(2 * PI * f * 1.5 * t))).toInt().toShort()
        }
    }

    @Test
    fun binaryFormat() {
        val sig = ShazamSignature.of(tones(6))
        assertTrue("peaks ${sig.peakCount()}", sig.peakCount() > 20)
        val bytes = sig.encode()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0xcafe2580.toInt(), bb.getInt(0))
        assertEquals(0x94119c00.toInt(), bb.getInt(12))
        assertEquals(bytes.size - 48, bb.getInt(8))
        assertEquals(bytes.size - 48, bb.getInt(52))
        assertEquals(3 shl 27, bb.getInt(28))
        assertEquals(6 * 16000 + 3840, bb.getInt(40))
        assertEquals(CRC32().apply { update(bytes, 8, bytes.size - 8) }.value.toInt(), bb.getInt(4))
        assertEquals(0, bytes.size % 4)
        // Reads back to the same peaks.
        val back = ShazamSignature.decode(bytes)
        assertEquals(sig.bands, back.bands)
        assertEquals(sig.numberSamples, back.numberSamples)
        assertTrue(sig.uri().startsWith("data:audio/vnd.shazam.sig;base64,"))
    }

    @Test
    fun silenceHasNoPeaks() {
        assertEquals(0, ShazamSignature.of(ShortArray(16000 * 5)).peakCount())
    }

    @Test
    fun parsesAnAnswer() {
        val o = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"matches":[{"id":"1","offset":42.5}],"track":{"key":"123","title":"Blinding Lights","subtitle":"The Weeknd",
               "isrc":"USUG11904206","images":{"coverarthq":"https://x/y.jpg"},
               "sections":[{"type":"SONG","metadata":[{"title":"Album","text":"After Hours"}]}]}}""",
        ) as kotlinx.serialization.json.JsonObject
        val m = Shazam.parse(o) as ShazamResult.Match
        assertEquals("Blinding Lights", m.title); assertEquals("The Weeknd", m.artist); assertEquals("After Hours", m.album)
        assertEquals(42_500L, m.offsetMs)
        assertEquals(ShazamResult.NoMatch, Shazam.parse(kotlinx.serialization.json.Json.parseToJsonElement("""{"matches":[]}""") as kotlinx.serialization.json.JsonObject))
    }

    /**
     * Live: real songs (decoded on the phone, mono, from KEY_DUMPS as for KeyDetectorTest), 10 s from the middle,
     * brought to 16 kHz, fingerprinted here and recognized by Shazam.
     */
    @Test
    fun recognizesRealSongs() {
        val dir = System.getenv("KEY_DUMPS")?.let(::File)
        assumeTrue("KEY_DUMPS not set", dir != null && dir.isDirectory)
        val songs = mapOf("J7p4bzqLvCw" to "Blinding Lights", "7TDeBi34OtE" to "Smells Like Teen Spirit", "hpSrLjc5SMs" to "Wonderwall")
        val shazam = Shazam(OkHttpClient())
        var found = 0
        for ((id, title) in songs) {
            val file = dir!!.listFiles { f -> f.name.startsWith("mix_${id}_") }!!.first()
            val rate = file.name.removeSuffix(".f32").substringAfterLast('_').toInt()
            val fb = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val pcm = FloatArray(fb.remaining()).also { fb.get(it) }
            val mid = pcm.size / 2
            val clip = pcm.copyOfRange(mid, mid + rate * 10)
            val at16 = Resampler.convert(clip, 1, rate, ShazamSignature.RATE)
            val shorts = ShortArray(at16.size) { (at16[it] * 32767 * 0.9f).toInt().coerceIn(-32768, 32767).toShort() }
            val sig = ShazamSignature.of(shorts)
            val r = shazam.recognize(sig)
            println("SHAZAM $title: ${sig.peakCount()} peaks, ${sig.encode().size} bytes -> $r")
            if (r is ShazamResult.Match && r.title.contains(title.substringBefore(' '), ignoreCase = true)) found++
            Thread.sleep(1500)
        }
        assertTrue("found $found of ${songs.size}", found >= 2)
    }
}
