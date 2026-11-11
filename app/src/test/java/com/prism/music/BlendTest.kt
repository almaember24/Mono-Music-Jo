package com.prism.music

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.prism.music.data.blend.BlendCode
import com.prism.music.data.blend.BlendMaths
import com.prism.music.data.blend.BlendSong
import com.prism.music.data.blend.CodeArtist
import com.prism.music.data.blend.CodeSong
import com.prism.music.data.blend.TasteCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlendTest {
    private val rnd = java.util.Random(11)
    private fun id(n: Int, alphabet: String = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_") =
        (1..n).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")

    private val words = "love night heart fire dream city summer light girl blue gold wild young stars rain".split(' ')
    private fun title() = (1..(1 + rnd.nextInt(4))).joinToString(" ") { words[rnd.nextInt(words.size)].replaceFirstChar(Char::uppercase) }

    /** A realistic, full-size code: 30 artists with channel ids, 50 songs with long-ish titles. */
    private fun bigCode(who: String, name: String): TasteCode {
        val artists = (1..30).map { CodeArtist("UC" + id(22), "Artist ${title()}", 100 - it * 3) }
        val songs = (1..50).map { i -> val a = artists[i % artists.size]; CodeSong(id(11), title() + " (feat. Someone)", a.name, a.id, 99 - i) }
        return TasteCode(who, name, artists, songs, mapOf("Pop" to 40, "Hip-hop" to 30, "Indie & alternative" to 20, "R&B & soul" to 10))
    }

    @Test
    fun roundTrip() {
        val c = TasteCode(
            "w1", "Sam ✨", listOf(CodeArtist("UCabcdefghijklmnopqrstuv", "Björk", 90), CodeArtist(null, "Local Band", 30)),
            listOf(CodeSong("abcdefghijk", "Jóga", "Björk", "UCabcdefghijklmnopqrstuv", 80), CodeSong("bcdefghijkl", "Song", "Someone Else", null, 20)),
            mapOf("Indie & alternative" to 70, "Pop" to 30),
        )
        val text = BlendCode.encode(c)
        assertTrue(text.startsWith(BlendCode.PREFIX))
        assertEquals(c, BlendCode.decode("Here's my Prism Blend code: $text — scan it!"))
        assertNull(BlendCode.decode("PRISMBLEND1:not-a-code"))
        assertNull(BlendCode.decode("nothing here"))
    }

    @Test
    fun aFullCodeFitsAQrCode() {
        val c = bigCode("who123", "A listener with a long name")
        val text = BlendCode.encode(c)
        println("full code: ${text.length} chars, ${BlendCode.decode(text)!!.songs.size} songs, ${BlendCode.decode(text)!!.artists.size} artists")
        assertTrue(text.length <= BlendCode.MAX_CHARS)
        val back = BlendCode.decode(text)!!
        assertTrue("kept ${back.songs.size} songs", back.songs.size >= 30)
        // The most played are what's kept.
        assertEquals(c.songs.first().id, back.songs.first().id)

        // And a QR code of it reads back.
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4))
        val scale = 4
        val w = matrix.width * scale
        val pixels = IntArray(w * w) { i -> if (matrix[(i % w) / scale, (i / w) / scale]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val read = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, w, pixels))))
        assertEquals(text, read.text)
        println("QR version ${(matrix.width - 17 - 8) / 4}, ${matrix.width} modules")
    }

    @Test
    fun theBlendIsTheSameOnBothPhones() {
        val a = bigCode("aaa", "Ana"); val b = bigCode("bbb", "Ben")
        val x = BlendMaths.blend(a, b); val y = BlendMaths.blend(b, a)
        assertEquals(x, y)
        assertEquals(50, x.songs.size)
        assertEquals(x.songs.size, x.songs.map { it.song.id }.toSet().size)
        // Neither takes over: about half each.
        val first = x.songs.count { it.from == BlendSong.FIRST }; val second = x.songs.count { it.from == BlendSong.SECOND }
        assertTrue("$first / $second", kotlin.math.abs(first - second) <= 2)
    }

    @Test
    fun sharedTasteComesFirstAndCounts() {
        val shared = CodeArtist("UC" + "s".repeat(22), "Shared Artist", 90)
        val shared2 = CodeArtist(null, "Shared Two", 40)
        val both = CodeSong("SHAREDSONG1", "Both Love It", shared.name, shared.id, 90)
        val a = TasteCode("a", "Ana", listOf(shared, shared2, CodeArtist(null, "Only Ana", 50)),
            listOf(both, CodeSong("anaonly0001", "Ana's", "Only Ana", null, 80), CodeSong("anashared01", "Ana's shared-artist song", shared2.name, null, 10)),
            mapOf("Pop" to 60, "Rock" to 40))
        val b = TasteCode("b", "Ben", listOf(shared, shared2, CodeArtist(null, "Only Ben", 50)),
            listOf(both, CodeSong("benonly0001", "Ben's", "Only Ben", null, 80)), mapOf("Pop" to 50, "Jazz" to 50))
        val blend = BlendMaths.blend(a, b)
        assertEquals("SHAREDSONG1", blend.songs.first().song.id)
        assertEquals(BlendSong.BOTH, blend.songs.first().from)
        // Ana's song by an artist Ben plays comes before her other favourites, though she plays it less.
        val ids = blend.songs.map { it.song.id }
        assertTrue(ids.indexOf("anashared01") < ids.indexOf("anaonly0001"))
        assertEquals(listOf("Shared Artist", "Shared Two"), blend.sharedArtists)
        assertEquals(listOf("Pop"), blend.sharedGenres)
        println("match ${blend.match}, songs ${blend.songs.map { it.song.id }}")
        assertTrue("match ${blend.match}", blend.match in 40..90)

        val same = BlendMaths.blend(a, a.copy(who = "z"))
        assertEquals(100, same.match)
        val strangers = BlendMaths.blend(
            TasteCode("x", "X", listOf(CodeArtist(null, "P", 1)), emptyList(), mapOf("Pop" to 100)),
            TasteCode("y", "Y", listOf(CodeArtist(null, "Q", 1)), emptyList(), mapOf("Jazz" to 100)),
        )
        assertEquals(0, strangers.match)
    }

    @Test
    fun findsTheCodeInAMessage() {
        val code = BlendCode.encode(bigCode("q", "Q"))
        assertNotNull(BlendCode.findIn("Blend with me on Prism! $code"))
        assertEquals(code, BlendCode.findIn("$code\n"))
    }
}
