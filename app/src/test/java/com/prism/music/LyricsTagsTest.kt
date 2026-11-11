package com.prism.music

import com.prism.music.data.local.LyricsTags
import com.prism.music.data.lyrics.LyricLine
import com.prism.music.data.lyrics.LyricWord
import com.prism.music.data.lyrics.Lyrics
import com.prism.music.data.prefs.LyricsSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Lyrics written into FLAC and MP3 tags (#16): added, replaced on a second write, everything else kept. */
class LyricsTagsTest {
    private val audio = ByteArray(5000) { (it * 7 + 3).toByte() }

    private fun le(o: ByteArrayOutputStream, v: Int) { o.write(v and 0xFF); o.write(v shr 8 and 0xFF); o.write(v shr 16 and 0xFF); o.write(v shr 24 and 0xFF) }

    private fun flacFile(): File {
        val o = ByteArrayOutputStream()
        o.write("fLaC".toByteArray())
        o.write(0); o.write(0); o.write(0); o.write(34); o.write(ByteArray(34) { 1 })
        val vc = ByteArrayOutputStream().also { v ->
            le(v, 6); v.write("vendor".toByteArray()); le(v, 2)
            "TITLE=Song".toByteArray().let { le(v, it.size); v.write(it) }
            "LYRICS=old".toByteArray().let { le(v, it.size); v.write(it) }
        }.toByteArray()
        o.write(4); o.write(0); o.write(vc.size shr 8); o.write(vc.size and 0xFF); o.write(vc)
        o.write(0x80 or 1); o.write(0); o.write(0); o.write(10); o.write(ByteArray(10))
        o.write(audio)
        return File.createTempFile("lyrt", ".flac").also { it.writeBytes(o.toByteArray()) }
    }

    private fun comments(f: File): List<String> {
        val b = f.readBytes()
        var p = 4
        while (true) {
            val h = b[p].toInt() and 0xFF
            val len = ((b[p + 1].toInt() and 0xFF) shl 16) or ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
            if (h and 0x7F == 4) {
                var q = p + 4
                fun int(): Int { val v = (b[q].toInt() and 0xFF) or ((b[q + 1].toInt() and 0xFF) shl 8) or ((b[q + 2].toInt() and 0xFF) shl 16) or ((b[q + 3].toInt() and 0xFF) shl 24); q += 4; return v }
                val vendor = int(); q += vendor
                return List(int()) { val n = int(); String(b, q, n).also { q += n } }
            }
            p += 4 + len
            if (h and 0x80 != 0) return emptyList()
        }
    }

    private val lyrics = Lyrics(LyricsSource.LRCLIB, true, listOf(LyricLine(1_230, "Hello"), LyricLine(65_400, "", isGap = true), LyricLine(70_010, "Wörld")))

    @Test
    fun lrcText() {
        assertEquals("[00:01.23]Hello\n[01:05.40]\n[01:10.01]Wörld", LyricsTags.text(lyrics))
    }

    /** 1.7.9.2 (Reddit): word timings as enhanced LRC, and the translation under each line, only when asked for. */
    @Test
    fun wordTimingsAndTranslation() {
        val worded = Lyrics(LyricsSource.LRCLIB, true, listOf(
            LyricLine(1_000, "Hi there", words = listOf(LyricWord(1_000, 1_400, "Hi "), LyricWord(1_500, 2_000, "there"))),
            LyricLine(65_400, "", isGap = true),
            LyricLine(70_010, "Bye"),
        ))
        assertEquals("[00:01.00]Hi there\n[01:05.40]\n[01:10.01]Bye", LyricsTags.text(worded))
        assertEquals("[00:01.00]<00:01.00>Hi <00:01.50>there\n[01:05.40]\n[01:10.01]Bye", LyricsTags.text(worded, words = true))
        // A blank or unchanged translation adds nothing; the gap stays a single line.
        assertEquals(
            "[00:01.00]Hi there\n[00:01.00]Hola\n[01:05.40]\n[01:10.01]Bye",
            LyricsTags.text(worded, translations = listOf("Hola", "", "Bye")),
        )
        val plain = Lyrics(LyricsSource.LRCLIB, false, listOf(LyricLine(-1, "One"), LyricLine(-1, "Two")))
        assertEquals("One\nUno\nTwo", LyricsTags.text(plain, translations = listOf("Uno", null)))
    }

    @Test
    fun flacKeepsTagsAndAudio() {
        val src = flacFile()
        val out = File.createTempFile("lyro", ".flac")
        assertTrue(LyricsTags.write(src, out, LyricsTags.Kind.FLAC, LyricsTags.text(lyrics)))
        val c = comments(out)
        assertTrue("TITLE=Song" in c)
        assertEquals(1, c.count { it.startsWith("LYRICS=") })
        assertTrue(c.contains("LYRICS=" + LyricsTags.text(lyrics)))
        assertArrayEquals(audio, out.readBytes().takeLast(audio.size).toByteArray())
        // Again: still one LYRICS.
        val again = File.createTempFile("lyra", ".flac")
        assertTrue(LyricsTags.write(out, again, LyricsTags.Kind.FLAC, "plain"))
        assertEquals(listOf("TITLE=Song", "LYRICS=plain"), comments(again))
        assertArrayEquals(audio, again.readBytes().takeLast(audio.size).toByteArray())
    }

    @Test
    fun notAFlac() {
        val f = File.createTempFile("lyrx", ".flac").also { it.writeBytes(ByteArray(100)) }
        val out = File.createTempFile("lyro", ".flac")
        assertFalse(LyricsTags.write(f, out, LyricsTags.Kind.FLAC, "x"))
        assertFalse(out.exists())
    }

    private fun has(b: ByteArray, part: ByteArray) = (0..b.size - part.size).any { i -> part.indices.all { b[i + it] == part[it] } }

    private fun syncsafe(v: Int) = byteArrayOf((v shr 21 and 0x7F).toByte(), (v shr 14 and 0x7F).toByte(), (v shr 7 and 0x7F).toByte(), (v and 0x7F).toByte())

    private fun frame(id: String, data: ByteArray) = ByteArrayOutputStream().also {
        it.write(id.toByteArray()); it.write(byteArrayOf(0, 0, (data.size shr 8).toByte(), data.size.toByte())); it.write(0); it.write(0); it.write(data)
    }.toByteArray()

    /** The frame ids in an ID3v2.3 tag. */
    private fun frameIds(b: ByteArray): List<String> {
        val size = ((b[6].toInt() and 0x7F) shl 21) or ((b[7].toInt() and 0x7F) shl 14) or ((b[8].toInt() and 0x7F) shl 7) or (b[9].toInt() and 0x7F)
        val ids = ArrayList<String>()
        var p = 10
        while (p + 10 <= 10 + size && b[p].toInt() != 0) {
            val n = ((b[p + 4].toInt() and 0xFF) shl 24) or ((b[p + 5].toInt() and 0xFF) shl 16) or ((b[p + 6].toInt() and 0xFF) shl 8) or (b[p + 7].toInt() and 0xFF)
            ids += String(b, p, 4); p += 10 + n
        }
        return ids
    }

    @Test
    fun mp3ReplacesLyricsFrame() {
        val frames = frame("TIT2", byteArrayOf(0) + "Song".toByteArray()) + frame("USLT", byteArrayOf(0) + "eng".toByteArray() + byteArrayOf(0) + "old".toByteArray())
        val src = File.createTempFile("lyrt", ".mp3").also {
            it.writeBytes("ID3".toByteArray() + byteArrayOf(3, 0, 0) + syncsafe(frames.size) + frames + audio)
        }
        val out = File.createTempFile("lyro", ".mp3")
        assertTrue(LyricsTags.write(src, out, LyricsTags.Kind.MP3, "[00:01.00]Hi"))
        val b = out.readBytes()
        assertEquals(listOf("TIT2", "USLT"), frameIds(b))
        assertTrue(has(b, "[00:01.00]Hi".toByteArray(Charsets.UTF_16LE)))
        assertArrayEquals(audio, b.takeLast(audio.size).toByteArray())
    }

    @Test
    fun mp3WithoutTagGetsOne() {
        val src = File.createTempFile("lyrt", ".mp3").also { it.writeBytes(audio) }
        val out = File.createTempFile("lyro", ".mp3")
        assertTrue(LyricsTags.write(src, out, LyricsTags.Kind.MP3, "Hi there"))
        val b = out.readBytes()
        assertEquals("ID3", String(b, 0, 3))
        assertEquals(4, b[3].toInt())
        assertTrue(has(b, "Hi there".toByteArray()))
        assertArrayEquals(audio, b.takeLast(audio.size).toByteArray())
    }
}
