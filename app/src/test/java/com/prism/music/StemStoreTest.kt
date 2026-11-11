package com.prism.music

import com.prism.music.playback.sing.StemStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A song switched to Sing part way through is separated from where it's playing first (1.8.x, the owner's TV): that
 * part and the song's start are two files, read back as the song's vocals in one, frame for frame.
 */
class StemStoreTest {
    private fun write(f: File, from: Int, to: Int) = f.outputStream().buffered().use { out ->
        // Each frame's value is its own song frame (left) and minus it (right), so any shift would show.
        for (frame in from until to) for (ch in 0..1) {
            val s = if (ch == 0) frame % 30_000 else -(frame % 30_000)
            out.write(s and 0xff); out.write((s shr 8) and 0xff)
        }
    }

    private fun expected(frame: Long, ch: Int) = (if (ch == 0) frame % 30_000 else -(frame % 30_000)) / 32768f

    @Test fun twoPartsReadAsOneSong() {
        val root = kotlin.io.path.createTempDirectory("stems").toFile()
        val store = StemStore(root)
        val split = 50_000
        val end = 120_000
        store.writeMeta("s", StemStore.Meta(48_000, end.toLong(), complete = true, from = split.toLong()))
        write(store.pcm("s"), 0, split)
        write(store.pcmFrom("s"), split, end)
        store.reader("s")!!.use { r ->
            // Windows before, across and after the place the second part starts, and past the song's end.
            for (start in listOf(0L, 49_000L, 49_990L, 50_000L, 100_000L, 119_500L)) {
                val out = FloatArray(2048 * 2)
                val real = r.read(start, 2048, out)
                assertEquals("frames there at $start", (minOf(start + 2048, end.toLong()) - start).toInt(), real)
                for (i in 0 until real) for (ch in 0..1) assertEquals("frame ${start + i}", expected(start + i, ch), out[i * 2 + ch], 1e-6f)
                for (i in real until 2048) assertEquals(0f, out[i * 2], 0f)
            }
        }
        assertTrue(store.complete("s"))
        root.deleteRecursively()
    }

    @Test fun whileTheStartIsStillToComeOnlyWhatsWrittenIsReal() {
        // Separated from where it plays (60 000 on, all written) while its start is half done (0..20 000).
        val root = kotlin.io.path.createTempDirectory("stems").toFile()
        val store = StemStore(root)
        store.writeMeta("s", StemStore.Meta(48_000, 100_000, from = 60_000))
        write(store.pcm("s"), 0, 20_000)
        write(store.pcmFrom("s"), 60_000, 100_000)
        store.reader("s")!!.use { r ->
            val out = FloatArray(4096 * 2)
            // The playing place: all there.
            assertEquals(4096, r.read(70_000, 4096, out))
            assertEquals(expected(70_000, 0), out[0], 1e-6f)
            // Between the start's end and the other part: not yet (silence, not counted).
            assertEquals(0, r.read(30_000, 4096, out))
            // Across the start's end: only its written frames.
            assertEquals(1000, r.read(19_000, 4096, out))
        }
        assertEquals(0.6f, store.progress("s"), 1e-3f)
        assertFalse(store.complete("s"))
        // Gone with the song, both parts.
        store.delete("s")
        assertFalse(store.pcmFrom("s").exists() || store.pcm("s").exists())
        root.deleteRecursively()
    }
}
