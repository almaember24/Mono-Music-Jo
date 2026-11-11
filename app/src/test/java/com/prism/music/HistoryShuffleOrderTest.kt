package com.prism.music

import androidx.media3.common.C
import androidx.media3.exoplayer.source.ShuffleOrder
import com.prism.music.playback.HistoryShuffleOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shuffled, Previous goes back to the song played last, whatever is added to the queue meanwhile. */
class HistoryShuffleOrderTest {
    private fun ShuffleOrder.list(): List<Int> =
        generateSequence(getFirstIndex().takeIf { it != C.INDEX_UNSET }) { getNextIndex(it).takeIf { n -> n != C.INDEX_UNSET } }.toList()

    /** Five songs playing 2, 0, 4, 1, 3; the queue's first (0) is playing, so 2 has been heard. */
    private var current = 0
    private val start = HistoryShuffleOrder(intArrayOf(2, 0, 4, 1, 3)) { current }

    /** Shuffled, a song dragged in the queue moves in the play order; what's been heard stays where it is. */
    @Test
    fun aDraggedSongMovesInThePlayOrder() {
        // Playing 0 (2 heard): coming up 4, 1, 3. Drag 3 up above 4, then 4 down below 1.
        val up = start.placed(3, beside = 4, after = false)
        assertEquals(listOf(2, 0, 3, 4, 1), up.list())
        assertEquals(listOf(2, 0, 3, 1, 4), up.placed(4, beside = 1, after = true).list())
        // Previous from the playing song is still the one heard before it.
        assertEquals(2, up.getPreviousIndex(0))
        // Nothing to move beside: unchanged.
        assertEquals(start.list(), start.placed(3, beside = 9, after = true).list())
    }

    /** #356: a long playlist's later pages shuffle in with the songs still to come, not after them. */
    @Test
    fun laterPagesAreScatteredAmongWhatsComingUp() {
        val list = (0 until 100).toList()
        val first = HistoryShuffleOrder(list.toIntArray()) { 0 }
        // 100 songs (playing 0, in the shuffle's first place), then 300 more.
        val order = first.cloneAndInsert(100, 300) as HistoryShuffleOrder
        val out = order.scattered((100 until 400).toList(), playing = 0, random = kotlin.random.Random(7)).list()
        assertEquals((0 until 400).toSet(), out.toSet())
        assertEquals(400, out.size)
        // The playing song stays, what's coming up keeps its own order, and the new ones sit all through it.
        assertEquals(0, out.first())
        assertEquals((1 until 100).toList(), out.filter { it < 100 && it != 0 })
        assertTrue("a later page's song comes before the first page's last", out.indexOf(99) > out.indexOfFirst { it >= 100 })
        assertTrue("the new songs aren't all at the end", out.take(100).count { it >= 100 } > 50)
    }

    /** #356: what's heard stays heard, and the [keep] songs (queued by hand, about to play) stay next. */
    @Test
    fun scatteringLeavesThePlayedAndTheNextAlone() {
        var playing = 2
        val heard = HistoryShuffleOrder(intArrayOf(5, 2, 0, 1, 3, 4)) { playing }
        val inserted = heard.cloneAndInsert(6, 20) as HistoryShuffleOrder
        val out = inserted.scattered((6 until 26).toList(), playing = 2, keep = 2, random = kotlin.random.Random(3)).list()
        assertEquals(listOf(5, 2, 0, 1), out.take(4))
        assertEquals(26, out.size)
        // Previous from the playing song is still the one heard before it.
        assertEquals(5, inserted.scattered((6 until 26).toList(), playing = 2, random = kotlin.random.Random(3)).getPreviousIndex(2))
        // Nothing playing that's known: the songs still go in, nothing lost.
        assertEquals(26, inserted.scattered((6 until 26).toList(), playing = 99, random = kotlin.random.Random(3)).list().size)
    }

    @Test
    fun songsAddedAtTheEndGoAfterEverything() {
        val added = start.cloneAndInsert(5, 2)
        assertEquals(listOf(2, 0, 4, 1, 3, 5, 6), added.list())
        // Previous from the playing song is still the one heard before it.
        assertEquals(2, added.getPreviousIndex(0))
    }

    @Test
    fun playNextComesStraightAfterThePlayingSong() {
        // Inserted at 1 (just after 0 in the queue): the queue's later songs move up one.
        val added = HistoryShuffleOrder(intArrayOf(2, 0, 4, 1, 3, 5, 6)) { current }.cloneAndInsert(1, 1)
        assertEquals(listOf(3, 0, 1, 5, 2, 4, 6, 7), added.list())
    }

    @Test
    fun songsInsertedBeforeThePlayingOneGoAtTheEnd() {
        // Inserted at the very start of the queue: everything moves up one, and the order stays.
        val added = start.cloneAndInsert(0, 1)
        assertEquals(listOf(3, 1, 5, 2, 4, 0), added.list())
    }

    @Test
    fun jumpingToASongMakesItNext() {
        val jumped = start.playingNext(0, 3)
        assertEquals(listOf(2, 0, 3, 4, 1), jumped.list())
        assertEquals(0, jumped.getPreviousIndex(3))
    }

    @Test
    fun removingAndMovingKeepTheOrder() {
        assertEquals(listOf(1, 3, 0, 2), start.cloneAndRemove(0, 1).list())
        // The queue's first song moved to the end: 0 becomes 4, the rest move down one.
        assertEquals(listOf(1, 4, 3, 0, 2), start.cloneAndMove(0, 1, 4).list())
    }

    @Test
    fun aNewQueueStartsFromItsFirstSong() {
        val set = start.cloneAndSet(6, 3)
        assertEquals(3, set.getFirstIndex())
        assertEquals((0 until 6).toSet(), set.list().toSet())
    }
}
