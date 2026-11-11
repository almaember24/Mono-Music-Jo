package com.prism.music.ui.screens

import com.prism.music.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Rearranging a playlist (#140): each drop is sent to YouTube as one move, so the moved song must be found right. */
class PlaylistMoveTest {
    private fun list(vararg ids: String) = ids.map { Song(it, it) }

    @Test
    fun aSongMovedDownIsFound() {
        assertEquals("a", movedSong(list("a", "b", "c", "d"), list("b", "c", "a", "d"))?.id)
    }

    @Test
    fun aSongMovedUpIsFound() {
        assertEquals("d", movedSong(list("a", "b", "c", "d"), list("a", "d", "b", "c"))?.id)
    }

    @Test
    fun neighboursSwappingIsOneMove() {
        // b and c trading places reads as c coming up (or b going down): either way one move gives the same order.
        val moved = movedSong(list("a", "b", "c"), list("a", "c", "b"))?.id
        assertEquals(true, moved == "b" || moved == "c")
    }

    @Test
    fun theLastSongToTheTopAndTheFirstToTheEnd() {
        assertEquals("e", movedSong(list("a", "b", "c", "d", "e"), list("e", "a", "b", "c", "d"))?.id)
        assertEquals("a", movedSong(list("a", "b", "c", "d", "e"), list("b", "c", "d", "e", "a"))?.id)
    }

    @Test
    fun nothingMovedIsNothing() {
        assertNull(movedSong(list("a", "b"), list("a", "b")))
        assertNull(movedSong(list("a", "b"), list("a")))
    }
}
