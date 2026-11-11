package com.prism.music

import com.prism.music.data.lyrics.LyricsPlusFormat
import com.prism.music.data.lyrics.Ttml
import com.prism.music.ui.player.activeAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Background vocals as their own words under a line (#66), and lines sung over each other both lit (#65). */
class BackgroundVocalsTest {
    // As LyricsPlus serves Apple's "Blinding Lights" (trimmed): the second line starts before the first one ends.
    private val lyricsPlus = """
        {"type":"Word","metadata":{"agents":{"v1":{"type":"person"}}},"lyrics":[
          {"time":139610,"duration":4199,"text":"I'm just calling back to let you know (Back to let you know)","syllabus":[
            {"time":139610,"duration":289,"text":"I'm "},{"time":139899,"duration":160,"text":"just "},
            {"time":140059,"duration":294,"text":"calling "},{"time":140353,"duration":373,"text":"back "},
            {"time":141445,"duration":816,"text":"know "},
            {"time":141720,"duration":434,"text":"(Back ","isBackground":true},{"time":142154,"duration":141,"text":"to ","isBackground":true},
            {"time":142889,"duration":920,"text":"know)","isBackground":true}],"element":{"singer":"v1"}},
          {"time":142475,"duration":3977,"text":"I could never say it","syllabus":[
            {"time":142475,"duration":400,"text":"I "},{"time":142875,"duration":3577,"text":"could never say it"}],"element":{"singer":"v1"}}
        ]}
    """.trimIndent()

    @Test
    fun lyricsPlusBackgroundIsItsOwnLine() {
        val lines = LyricsPlusFormat.parse(lyricsPlus).filter { !it.isGap }
        val first = lines[0]
        assertEquals("I'm just calling back know", first.text)
        assertEquals("(Back to know)", first.backgroundLine?.text)
        // The line lasts as long as its background vocals do.
        assertEquals(142889L + 920, first.endMs)
    }

    @Test
    fun overlappingLinesAreBothSung() {
        val lines = LyricsPlusFormat.parse(lyricsPlus)
        val first = lines.indexOfFirst { it.text.startsWith("I'm") }
        val second = lines.indexOfFirst { it.text.startsWith("I could") }
        assertEquals(listOf(first, second), lines.activeAt(143_000))
        assertEquals(listOf(second), lines.activeAt(144_500))
    }

    @Test
    fun ttmlBackgroundIsKept() {
        val ttml = """
            <tt itunes:timing="Word"><body><div>
            <p begin="10.0" end="12.0" ttm:agent="v1"><span begin="10.0" end="10.5">Hello</span> <span begin="10.5" end="11.0">there</span>
            <span ttm:role="x-bg"><span begin="11.2" end="11.6">(oh</span> <span begin="11.6" end="12.5">yeah)</span></span></p>
            </div></body></tt>
        """.trimIndent()
        val line = Ttml.parse(ttml).first { !it.isGap }
        assertEquals("Hello there", line.text)
        assertEquals("(oh yeah)", line.backgroundLine?.text)
        assertTrue(line.endMs >= 12_500)
    }
}
