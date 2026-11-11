package com.prism.music

import com.prism.music.data.lyrics.LyricLine
import com.prism.music.data.lyrics.LyricWord
import com.prism.music.data.lyrics.Lyrics
import com.prism.music.data.lyrics.Uncensor
import com.prism.music.data.prefs.LyricsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CensoredLyricsTest {
    private fun lyrics(vararg lines: String) = Lyrics(LyricsSource.LRCLIB, true, lines.mapIndexed { i, t -> LyricLine(i * 1000L, t) })

    @Test fun starredWordsAreCensored() {
        listOf("What the f*ck is this", "Oh f***, here we go", "Holy sh*t", "This is some sh**", "Mother**ckers", "f##k it", "Damn [censored] man")
            .forEach { assertTrue(it, lyrics("A clean line", it).censored) }
    }

    @Test fun ordinaryLyricsAreNot() {
        listOf("I *really* love you", "*laughs* yeah", "Number #1 in the world", "Rock & roll - all night", "Fuck it, we ball", "Shit, I'm late")
            .forEach { assertFalse(it, lyrics(it).censored) }
    }

    private val genius = """
        [Verse 1]
        What the fuck is this, I said
        Oh fuck, here we go again
        This is some shit right here
        Motherfuckers never loved us

        [Chorus]
        Fuck it, fuck it, we ball
    """.trimIndent()

    @Test fun starredWordsComeBackFromTheReference() {
        val filled = Uncensor.fill(
            lyrics("What the f*ck is this, I said", "Oh f***, here we go again", "This is some sh** right here", "Mother**ckers never loved us", "F**k it, f**k it, we ball"),
            genius,
        )
        assertEquals(
            listOf("What the fuck is this, I said", "Oh fuck, here we go again", "This is some shit right here", "Motherfuckers never loved us", "Fuck it, fuck it, we ball"),
            filled.lines.map { it.text },
        )
        assertFalse(filled.censored)
    }

    @Test fun karaokeWordsAndTimingsAreKept() {
        val words = listOf(LyricWord(0, 300, "Oh "), LyricWord(300, 700, "f***, "), LyricWord(700, 900, "here "), LyricWord(900, 1100, "we "), LyricWord(1100, 1300, "go "), LyricWord(1300, 1600, "again"))
        val line = LyricLine(0, words.joinToString("") { it.text }, 1600, words)
        val filled = Uncensor.fill(Lyrics(LyricsSource.KUGOU, true, listOf(line)), genius).lines.single()
        assertEquals("Oh fuck, here we go again", filled.text)
        assertEquals(words.map { it.startMs to it.endMs }, filled.words.map { it.startMs to it.endMs })
        assertEquals("fuck, ", filled.words[1].text)
    }

    @Test fun unmatchedLinesStayCensored() {
        val l = lyrics("Some other f**k entirely different words")
        assertEquals(l.lines, Uncensor.fill(l, genius).lines)
    }
}
