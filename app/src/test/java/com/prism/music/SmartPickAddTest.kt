package com.prism.music

import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.ui.player.smartPickAdded
import org.junit.Assert.assertEquals
import org.junit.Test

/** 1.8.1: Smart Shuffle's "Add to …" said "Added" when the song was already in the playlist, as Add to playlist did. */
class SmartPickAddTest {
    @Test fun itSaysWhatYouTubeDid() {
        assertEquals(tr(R.string.added_to, "Road trip") to true, smartPickAdded(YouTubeMusic.PlaylistAdd(1, 0), "Road trip"))
        assertEquals(tr(R.string.already_in, "Road trip") to true, smartPickAdded(YouTubeMusic.PlaylistAdd(0, 1), "Road trip"))
        assertEquals(tr(R.string.couldn_t_add_to, "Road trip") to false, smartPickAdded(null, "Road trip"))
    }
}
