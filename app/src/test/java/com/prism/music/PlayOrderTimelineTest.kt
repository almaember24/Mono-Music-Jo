package com.prism.music

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.prism.music.playback.PlayOrderTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The session's queue while shuffled (Android Auto's queue matched the phone's only unshuffled). */
@RunWith(RobolectricTestRunner::class)
class PlayOrderTimelineTest {
    /** Five songs, one period each, shuffled to play 2, 0, 4, 1, 3. */
    private class Shuffled(private val shuffle: IntArray) : Timeline() {
        private val ids = listOf("a", "b", "c", "d", "e")
        override fun getWindowCount() = ids.size
        override fun getPeriodCount() = ids.size
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window =
            window.set(ids[windowIndex], MediaItem.Builder().setMediaId(ids[windowIndex]).build(), null, 0, 0, 0, true, false, null, 0, 1_000_000, windowIndex, windowIndex, 0)
        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
            period.set(ids[periodIndex], ids[periodIndex], periodIndex, 1_000_000, 0)
        override fun getIndexOfPeriod(uid: Any) = ids.indexOf(uid)
        override fun getUidOfPeriod(periodIndex: Int): Any = ids[periodIndex]
        override fun getFirstWindowIndex(shuffleModeEnabled: Boolean) = if (shuffleModeEnabled) shuffle.first() else 0
        override fun getLastWindowIndex(shuffleModeEnabled: Boolean) = if (shuffleModeEnabled) shuffle.last() else ids.lastIndex
        override fun getNextWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int {
            if (!shuffleModeEnabled) return super.getNextWindowIndex(windowIndex, repeatMode, false)
            val at = shuffle.indexOf(windowIndex)
            return if (at + 1 < shuffle.size) shuffle[at + 1] else C.INDEX_UNSET
        }
    }

    private val play = intArrayOf(2, 0, 4, 1, 3)

    @Test
    fun listsSongsInPlayOrder() {
        val t = PlayOrderTimeline.of(Shuffled(play))
        assertNotNull(t)
        val w = Timeline.Window()
        val shown = (0 until t!!.windowCount).map { t.getWindow(it, w).mediaItem.mediaId }
        assertEquals(listOf("c", "a", "e", "b", "d"), shown)
        // Read in that order, the next song is simply the one after, as Android Auto reads a queue.
        assertEquals(1, t.getNextWindowIndex(0, Player.REPEAT_MODE_OFF, true))
    }

    @Test
    fun placesMapBackToTheRealSongs() {
        val t = PlayOrderTimeline.of(Shuffled(play))!!
        // The car's third song is the real fifth ("e"); the real second ("b") is shown fourth.
        assertEquals(4, t.order[2])
        assertEquals(3, t.shownIndexOf(1))
        // Periods move with their windows, and point back at the shown window.
        val w = Timeline.Window()
        val p = Timeline.Period()
        for (i in 0 until t.windowCount) {
            t.getWindow(i, w)
            assertEquals(i, t.getPeriod(w.firstPeriodIndex, p, true).windowIndex)
            assertEquals(w.uid, p.uid)
            assertEquals(w.firstPeriodIndex, t.getIndexOfPeriod(p.uid!!))
        }
    }
}
