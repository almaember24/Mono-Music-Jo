package com.prism.music

import com.prism.music.data.imports.ParsedPlay
import com.prism.music.data.imports.TakeoutHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * #375: Takeout names its files in the account's language (`historial-de-reproducciones.json` in Spanish), and only the
 * English names were looked for. A file now counts by what's in it: YouTube Music plays of real videos, with their times.
 */
class TakeoutLanguagesTest {
    private val play = """{"header":"YouTube Music","title":"Has visto Song","titleUrl":"https://www.youtube.com/watch?v=abcdefghijk",""" +
        """"subtitles":[{"name":"Artist - Topic"}],"time":"2026-03-01T10:00:00.000Z","products":["YouTube"]}"""
    private val video = """{"header":"YouTube","title":"Has visto Cats","titleUrl":"https://www.youtube.com/watch?v=abcdefghijk","time":"2026-03-01T09:00:00.000Z"}"""
    /** A search made in YouTube Music is written much like a play, but it's no video. */
    private val search = """{"header":"YouTube Music","title":"Has buscado cats","titleUrl":"https://www.youtube.com/results?search_query=cats","time":"2026-03-01T08:00:00.000Z"}"""

    private fun zip(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            files.forEach { (name, text) -> z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
        }
    }.toByteArray()

    private fun read(name: String, bytes: ByteArray): Pair<Boolean, List<ParsedPlay>> {
        val plays = ArrayList<ParsedPlay>()
        return TakeoutHistory.read(name, ByteArrayInputStream(bytes)) { plays += it } to plays
    }

    @Test fun theSpanishFileInItsZipIsTheHistory() {
        val (found, plays) = read("takeout.zip", zip("Takeout/YouTube y YouTube Music/historial/historial-de-reproducciones.json" to "[$play,$video]"))
        assertTrue("the reporter's own zip", found)
        assertEquals(1, plays.size)
        assertEquals("abcdefghijk", plays[0].youtubeId)
    }

    @Test fun anyLanguagesNameWorks() {
        for (name in listOf("視聴履歴.json", "wiedergabeverlauf.json", "история просмотра.json", "historique-des-vidéos-regardées.json")) {
            val (found, plays) = read(name, "[$play]".toByteArray())
            assertTrue(name, found)
            assertEquals(name, 1, plays.size)
        }
    }

    @Test fun otherFilesInTheZipStayOut() {
        val (found, plays) = read(
            "takeout.zip",
            zip(
                "historial-de-busquedas.json" to "[$search]",
                "suscripciones.json" to """[{"channel":"x","url":"https://www.youtube.com/watch?v=abcdefghijk"}]""",
                "un-objeto.json" to """{"header":"YouTube Music"}""",
                "roto.json" to "[{not json",
                "notas.txt" to "[$play]",
            ),
        )
        assertFalse("nothing in there is a play", found)
        assertTrue(plays.isEmpty())
    }

    @Test fun onlyVideosIsNoHistoryUnderAnotherName() {
        assertFalse(read("historial-de-reproducciones.json", "[$video]".toByteArray()).first)
    }

    /** As before: an English-named history with no music in it was still "found" (and says nothing to bring). */
    @Test fun theEnglishNameStillCounts() {
        assertTrue(read("watch-history.json", "[$video]".toByteArray()).first)
        assertTrue(read("takeout.zip", zip("Takeout/watch-history.json" to "[$play]")).first)
    }

    /** Two history files in one zip each start fresh: a play's length isn't measured from the other file's. */
    @Test fun aSecondFileDoesntMeasureFromTheFirst() {
        val (_, plays) = read("takeout.zip", zip("a.json" to "[$play]", "b.json" to "[${play.replace("abcdefghijk", "bcdefghijkl")}]"))
        assertEquals(2, plays.size)
        assertEquals(plays[0].playedMs, plays[1].playedMs)
    }
}
