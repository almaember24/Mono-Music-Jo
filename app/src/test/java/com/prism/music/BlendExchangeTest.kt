package com.prism.music

import com.prism.music.data.blend.BlendExchange
import com.prism.music.data.blend.Blends
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Blend made on both phones: the scanning phone sends its code back to the one showing the QR code, over the local
 * network, with the one-time key from the code; a wrong key or a closed screen gets nothing made.
 */
class BlendExchangeTest {
    private val codeA = "PRISMBLEND1:AAAAaaaa"
    private val codeB = "PRISMBLEND1:BBBBbbbb"

    @Test
    fun theScannersCodeComesBack() = runBlocking {
        val got = mutableListOf<Pair<String, String?>>()
        val a = BlendExchange({ code, mine -> got += code to mine; Blends.Import.Added("whoB", "Bee", false) }, { "Ay" })
        val b = BlendExchange({ _, _ -> Blends.Import.NotACode }, { "Bee" })
        a.open(codeA)
        val port = a.listeningPort!!
        val key = a.currentKey!!
        // As the scanning phone reads it off the screen (loopback here, a Wi-Fi address on phones).
        val scanned = "$codeA\n${BlendExchange.HINT}127.0.0.1:$port:$key"

        val back = b.sendBack(scanned, codeB)
        assertEquals(BlendExchange.SendBack.Done("Ay"), back)
        assertEquals("their Blend is made from my code as they saw it", listOf(codeB to codeA), got)

        val wrongKey = b.sendBack("$codeA\n${BlendExchange.HINT}127.0.0.1:$port:${"x".repeat(16)}", codeB)
        assertEquals(BlendExchange.SendBack.Unreachable, wrongKey)
        assertEquals(1, got.size)

        a.close()
        assertEquals(BlendExchange.SendBack.Unreachable, b.sendBack(scanned, codeB))
        assertEquals(BlendExchange.SendBack.NoWay, b.sendBack(codeA, codeB))
    }

    /**
     * With PRISM_BLEND_SEND=port:key (from a debug build's "PrismBlend" log while its Blend screen is open, after
     * `adb forward tcp:<port> tcp:<port>`): plays a friend's phone that scanned it, sending a made-up friend's code.
     */
    @Test
    fun sendToAPhone() = runBlocking {
        val target = System.getenv("PRISM_BLEND_SEND") ?: return@runBlocking
        val (port, key) = target.split(':')
        val friend = com.prism.music.data.blend.TasteCode(
            "testFriend123", "Test Friend",
            listOf(com.prism.music.data.blend.CodeArtist(null, "Queen", 90), com.prism.music.data.blend.CodeArtist(null, "Daft Punk", 70)),
            listOf(
                com.prism.music.data.blend.CodeSong("fJ9rUzIMcZQ", "Bohemian Rhapsody", "Queen", null, 90),
                com.prism.music.data.blend.CodeSong("5NV6Rdv1a3I", "Get Lucky", "Daft Punk", null, 80),
            ),
            mapOf("Rock" to 60, "Dance & electronic" to 40),
        )
        val code = com.prism.music.data.blend.BlendCode.encode(friend)
        val back = BlendExchange({ _, _ -> Blends.Import.NotACode }, { "Test Friend" })
            .sendBack("PRISMBLEND1:x\n${BlendExchange.HINT}127.0.0.1:$port:$key", code)
        println("SENT: $back")
        assertTrue(back is BlendExchange.SendBack.Done)
    }

    @Test
    fun hintsAreReadCarefully() {
        val h = BlendExchange.parseHint("PRISMBLEND1:abc\nPRISMBLENDX:192.168.1.5,10.0.0.3:47324:abcdefgh12")!!
        assertEquals(listOf("192.168.1.5", "10.0.0.3"), h.hosts)
        assertEquals(47324, h.port)
        assertNull(BlendExchange.parseHint("PRISMBLENDX:evil.example.com:80:abcdefgh12"))
        assertNull(BlendExchange.parseHint("PRISMBLENDX:192.168.1.5:99999:abcdefgh12"))
        assertNull(BlendExchange.parseHint("PRISMBLENDX:192.168.1.5:80:k"))
        assertTrue(BlendExchange.parseHint("just a code") == null)
    }
}
