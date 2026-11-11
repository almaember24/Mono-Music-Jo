package com.prism.music

import com.prism.music.playback.PlaceKeeper
import com.prism.music.playback.QueuePlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #388 (1.8.3): after a crash (or Android closing Prism for memory) the song started again near 0, because the place in
 * it was only written on events. While playing it's now kept every 10 s, and a restart picks up within 10 s of it.
 */
class QueuePlaceTest {
    @Test fun playing25SecondsKeepsThePlaceTwice() {
        val written = ArrayList<QueuePlace>()
        val keeper = PlaceKeeper { written += it }
        // The service ticks every 10 s while playing: at 10 s and 20 s of a 25 s stretch.
        var t = 0L
        repeat((25_000 / PlaceKeeper.EVERY_MS).toInt()) { t += PlaceKeeper.EVERY_MS; keeper.tick(QueuePlace("a", 3, t)) }
        assertTrue("written at least twice: $written", written.size >= 2)
        assertEquals(20_000L, written.last().positionMs)
    }

    @Test fun pausedOrStillNothingNewIsWritten() {
        val written = ArrayList<QueuePlace>()
        val keeper = PlaceKeeper { written += it }
        keeper.tick(QueuePlace("a", 0, 10_000))
        keeper.tick(QueuePlace("a", 0, 10_500)) // hardly moved (a stall): not written again
        keeper.tick(null)
        assertEquals(1, written.size)
        keeper.tick(QueuePlace("b", 1, 400)) // the next song: written
        assertEquals(2, written.size)
        keeper.saved(QueuePlace("b", 1, 9_000)) // the queue was just saved with this place
        keeper.tick(QueuePlace("b", 1, 9_500))
        assertEquals(2, written.size)
    }

    @Test fun aRestoredQueueStartsWithinTenSecondsOfWhereItWas() {
        val ids = listOf("a", "b", "c")
        // The queue file says the start of "b" (written when "b" came up); the kept place says 2:13 into it.
        val playedTo = 133_000L + 9_000 // played on up to 9 s after the last tick, then the crash
        assertEquals(1 to 133_000L, PlaceKeeper.start(ids, 1, 0, QueuePlace("b", 1, 133_000), placeIsNewer = true))
        assertTrue(playedTo - PlaceKeeper.start(ids, 1, 0, QueuePlace("b", 1, 133_000), true).second <= PlaceKeeper.EVERY_MS)
        // The song moved in the queue: found by id.
        assertEquals(2 to 50_000L, PlaceKeeper.start(listOf("a", "c", "b"), 1, 0, QueuePlace("b", 1, 50_000), true))
        // Older than the queue file, or a song no longer queued: the queue file's own place.
        assertEquals(1 to 7_000L, PlaceKeeper.start(ids, 1, 7_000, QueuePlace("b", 1, 50_000), placeIsNewer = false))
        assertEquals(1 to 7_000L, PlaceKeeper.start(ids, 1, 7_000, QueuePlace("x", 1, 50_000), true))
        assertEquals(1 to 7_000L, PlaceKeeper.start(ids, 1, 7_000, null, true))
    }
}
