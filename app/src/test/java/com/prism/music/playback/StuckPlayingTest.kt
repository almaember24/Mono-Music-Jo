package com.prism.music.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.StuckPlayerException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StuckPlayingTest {
    private fun error(type: Int) = PlaybackException("Unexpected runtime error", StuckPlayerException(type, 10_000), PlaybackException.ERROR_CODE_TIMEOUT)

    @Test fun onlyAPositionThatStoppedMovingIsTriedAgain() {
        assertTrue(stuckPlaying(error(StuckPlayerException.STUCK_PLAYING_NO_PROGRESS)))
        // At its end and not ending, or waiting on the network: playing it again from there wouldn't help.
        assertFalse(stuckPlaying(error(StuckPlayerException.STUCK_PLAYING_NOT_ENDING)))
        assertFalse(stuckPlaying(error(StuckPlayerException.STUCK_BUFFERING_NO_PROGRESS)))
        assertFalse(stuckPlaying(PlaybackException("Source error", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)))
    }
}
