package com.prism.music

import com.prism.music.data.local.LyricsTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * D3 (Reddit): lyrics written into M4A files (©lyr). Two files made with ffmpeg: AAC with moov before the audio
 * (faststart: every chunk offset has to move) and ALAC with moov at the end. Made-up lyrics.
 */
class LyricsTagsM4aTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun res(name: String) = File(javaClass.classLoader!!.getResource("lyrics-tags/$name")!!.toURI())

    private val lyrics = "[00:00.50]A yellow bicycle\n[00:01.20]By the quiet river"

    /** The text of the first box of [type] anywhere in [bytes] ("©lyr"'s data, after its 16 bytes of headers). */
    private fun lyricsIn(bytes: ByteArray): String? {
        val marker = "©lyr".toByteArray(Charsets.ISO_8859_1)
        val at = (0..bytes.size - 4).firstOrNull { i -> (0 until 4).all { bytes[i + it] == marker[it] } } ?: return null
        val size = ((bytes[at - 4].toInt() and 0xFF) shl 24) or ((bytes[at - 3].toInt() and 0xFF) shl 16) or ((bytes[at - 2].toInt() and 0xFF) shl 8) or (bytes[at - 1].toInt() and 0xFF)
        return String(bytes, at + 4 + 16, size - 8 - 16, Charsets.UTF_8)
    }

    private fun check(name: String) {
        val input = res(name)
        val output = tmp.newFile("out-$name")
        assertTrue("written", LyricsTags.write(input, output, LyricsTags.Kind.M4A, lyrics))
        assertTrue("the audio came through", LyricsTags.audioIntact(input, output, LyricsTags.Kind.M4A))
        val out = output.readBytes()
        assertEquals(lyrics, lyricsIn(out))
        // The title the file had is still there.
        assertTrue(String(out, Charsets.ISO_8859_1).contains("Test Tone"))
        // Written again, the old lyrics are replaced, not added to.
        val again = tmp.newFile("again-$name")
        assertTrue(LyricsTags.write(output, again, LyricsTags.Kind.M4A, "New words"))
        assertEquals("New words", lyricsIn(again.readBytes()))
        assertTrue(LyricsTags.audioIntact(input, again, LyricsTags.Kind.M4A))
        // Kept for a look with ffprobe / ffmpeg on the PC (build/lyrics-tags/).
        File("build/lyrics-tags").apply { mkdirs() }.let { output.copyTo(File(it, name), overwrite = true) }
    }

    @Test fun moovBeforeTheAudio() = check("front.m4a")

    @Test fun moovAfterTheAudio() = check("end.m4a")
}
