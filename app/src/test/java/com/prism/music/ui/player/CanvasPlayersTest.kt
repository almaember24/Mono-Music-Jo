package com.prism.music.ui.player

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #343: opening the player again carries on with the kept animated cover instead of loading it (and a decoder) anew. */
class CanvasPlayersTest {
    private val clip = "https://canvas.example/clip.mp4"

    @Test
    fun theSameClipCarriesOn() {
        assertTrue(CanvasPlayers.loadedAlready(clip, Player.STATE_READY, failed = false, url = clip))
        assertTrue(CanvasPlayers.loadedAlready(clip, Player.STATE_BUFFERING, failed = false, url = clip))
    }

    @Test
    fun anotherClipAStoppedPlayerOrAFailureLoadsAgain() {
        assertFalse(CanvasPlayers.loadedAlready(clip, Player.STATE_READY, failed = false, url = "https://canvas.example/other.mp4"))
        assertFalse(CanvasPlayers.loadedAlready(clip, Player.STATE_IDLE, failed = false, url = clip))
        assertFalse(CanvasPlayers.loadedAlready(clip, Player.STATE_READY, failed = true, url = clip))
        assertFalse(CanvasPlayers.loadedAlready(null, Player.STATE_IDLE, failed = false, url = clip))
    }
}
