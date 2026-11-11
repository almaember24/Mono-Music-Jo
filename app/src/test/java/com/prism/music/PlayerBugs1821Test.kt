package com.prism.music

import androidx.compose.ui.unit.dp
import com.prism.music.data.lyrics.LyricsTranslator
import com.prism.music.data.prefs.DeckPart
import com.prism.music.playback.LostTime
import com.prism.music.playback.QueueEdit
import com.prism.music.ui.player.deckSpacing
import com.prism.music.ui.player.stripOverScrubber
import com.prism.music.ui.player.translatedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.8.2.1's player round: what's worked out apart from the player and the screen. */
class PlayerBugs1821Test {
    // ------------------------------------------------------------------ #368 Play next and the queue

    @Test fun playNextMovesTheFirstCopyOfASongComingUp() {
        val ahead = listOf("b", "c", "d", "c")
        // The list's first copy moves up; its other one stays where the list has it.
        assertEquals(listOf(1), QueueEdit.copiesMovingUp(ahead, listOf("c")))
        // New to the queue: nothing makes way.
        assertEquals(emptyList<Int>(), QueueEdit.copiesMovingUp(ahead, listOf("x")))
        // A whole album, some of it coming up: lowest first, each copy once.
        assertEquals(listOf(0, 1, 3), QueueEdit.copiesMovingUp(ahead, listOf("c", "x", "b", "c")))
    }

    private class Row(val id: String, val index: Int)

    private fun sections(order: List<Row>, queued: Set<String>, autoplayFrom: Int) =
        QueueEdit.sections(order, { it.id in queued }, { it.index >= autoplayFrom })

    private fun QueueEdit.Sections<Row>.all() = (queued + upcoming + autoplay).map { it.id }

    /** The listener's songs that really come next have the heading; a song added at the end of the list stays there. */
    @Test fun theQueuesSectionsListSongsInTheOrderTheyPlay() {
        val order = listOf(Row("m", 1), Row("l", 2), Row("x", 3), Row("y", 4), Row("l", 5), Row("m", 6), Row("auto", 7))
        val s = sections(order, setOf("m", "l"), autoplayFrom = 7)
        assertEquals(listOf("m", "l"), s.queued.map { it.id })
        // The second copies stay among the list's songs, where they play (they were listed beside the first ones).
        assertEquals(listOf("x", "y", "l", "m"), s.upcoming.map { it.id })
        assertEquals(listOf("auto"), s.autoplay.map { it.id })
        assertEquals(order.map { it.id }, s.all())

        // Add to queue on a list still playing: it plays after the list's songs, so it's shown there, not first.
        val added = listOf(Row("b", 1), Row("c", 2), Row("x", 3))
        val a = sections(added, setOf("x"), autoplayFrom = 4)
        assertTrue("nothing the listener queued comes next", a.queued.isEmpty())
        assertEquals(listOf("b", "c", "x"), a.all())
    }

    /** Whatever the mix, shuffled ones included, the sections read back as the play order. */
    @Test fun theSectionsNeverReorderASong() {
        val random = kotlin.random.Random(368)
        repeat(200) {
            val order = (0 until random.nextInt(12)).map { Row("s" + random.nextInt(6), random.nextInt(12)) }
            val queued = (0 until 6).filter { random.nextBoolean() }.map { "s$it" }.toSet()
            assertEquals(order.map { it.id }, sections(order, queued, random.nextInt(12)).all())
        }
    }

    // ------------------------------------------------------------------ #364 what a blend's diagnostics say

    /** Readings of a song playing, [stale] ms old every so often; the player's real place is the clock + [behind]. */
    private fun LostTime.play(fromMs: Long, toMs: Long, stale: (Long) -> Long = { 0 }, behind: (Long) -> Long = { 0 }): List<Long> =
        (fromMs until toMs step 20).map { now -> read(1_000_000 + now - behind(now) - stale(now), now) }.filter { it > 0 }

    @Test fun readingsThatAreOnlyStaleLoseNoTime() {
        // A position only updated every 300 ms while the player's busy: steady all the same.
        assertEquals(emptyList<Long>(), LostTime().play(1, 10_000, stale = { it % 300 }))
    }

    @Test fun aSongThatReallyStoppedForAMomentIsSaid() {
        // 250 ms lost at 5 s, once, and said once.
        val lost = LostTime().play(1, 10_000, stale = { it % 300 }, behind = { if (it >= 5_000) 250 else 0 })
        assertEquals(1, lost.size)
        assertTrue("about what it lost: $lost", lost[0] in 200..300)
    }

    // ------------------------------------------------------------------ #361 the current lyric's place in the deck

    /** The reporter's deck: the lyric between the controls and the buttons, the volume bar hidden. */
    @Test fun theCurrentLyricMovedInTheDeckSitsMidwayBetweenItsNeighbours() {
        val deck = listOf(DeckPart.SCRUBBER, DeckPart.TRANSPORT, DeckPart.STRIP, DeckPart.VOLUME, DeckPart.BUTTONS)
        val hidden = deckSpacing(deck, volumeShown = false, small = false)
        assertEquals(listOf(DeckPart.SCRUBBER, DeckPart.TRANSPORT, DeckPart.STRIP, DeckPart.BUTTONS), hidden.parts)
        assertEquals("as far from the controls as from the buttons", hidden.gaps[1].value, hidden.gaps[2].value, 0.01f)
        // The deck keeps its height, so the cover and the controls above stay where they were: 8 + 12 + 0, plus the bar's 38.
        assertEquals(58f, hidden.gaps.sumOf { it.value.toDouble() }.toFloat(), 0.01f)
        // With the bar showing too.
        val shown = deckSpacing(deck, volumeShown = true, small = false)
        assertEquals(listOf(8.dp, 6.dp, 6.dp, 6.dp), shown.gaps)
        // Closed up over a full-bleed picture, the same.
        val tight = deckSpacing(deck, volumeShown = false, small = false, tight = true)
        assertEquals(tight.gaps[1], tight.gaps[2])
    }

    @Test fun overTheProgressBarTheLyricStaysAsItWas() {
        // Its first place, shown or hidden volume bar: as 1.8.2.
        val first = DeckPart.entries
        assertEquals(listOf(0.dp, 8.dp, 12.dp, 6.dp), deckSpacing(first, volumeShown = true, small = false).gaps)
        assertTrue(stripOverScrubber(first, first.indexOf(DeckPart.STRIP)))
        // Moved, it isn't: nor last, with nothing after it.
        val moved = listOf(DeckPart.SCRUBBER, DeckPart.TRANSPORT, DeckPart.STRIP, DeckPart.BUTTONS)
        assertFalse(stripOverScrubber(moved, 2))
        val last = listOf(DeckPart.SCRUBBER, DeckPart.BUTTONS, DeckPart.STRIP)
        assertFalse(stripOverScrubber(last, 2))
        assertEquals(listOf(8.dp, 10.dp), deckSpacing(last, volumeShown = true, small = false).gaps)
        // Second, over the progress bar with the controls above it: a place it can already have, unchanged.
        val second = listOf(DeckPart.TRANSPORT, DeckPart.STRIP, DeckPart.SCRUBBER, DeckPart.BUTTONS)
        assertEquals(listOf(12.dp, 0.dp, 8.dp), deckSpacing(second, volumeShown = true, small = false).gaps)
    }

    // ------------------------------------------------------------------ #358 translations in Immersive mode

    @Test fun onlyARealTranslationIsShownUnderALine() {
        val t = LyricsTranslator.Translation("en", "es", listOf("Hola", null, "same words"))
        assertEquals("Hola", translatedLine(t, 0, "Hello"))
        assertNull("no translation for it", translatedLine(t, 1, "Hello"))
        assertNull("off the end of the list", translatedLine(t, 9, "Hello"))
        // It came back the same (uppercase lyrics, or a word that doesn't translate).
        assertNull(translatedLine(t, 2, " SAME WORDS "))
        assertNull("turned off, or not in yet", translatedLine(null, 0, "Hello"))
        assertNull("the song's in that language already", translatedLine(LyricsTranslator.Translation("es-419", "es", listOf("Hola")), 0, "Hello"))
    }
}
