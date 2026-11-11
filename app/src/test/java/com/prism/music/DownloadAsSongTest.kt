package com.prism.music

import com.prism.music.data.model.Song
import com.prism.music.data.prefs.AppSettings
import com.prism.music.download.downloadsAsSong
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #390 (1.8.3): a music video picked from search downloaded as the album track although it played with its own sound.
 * A video downloads as its song only when it would also play as its song ("Songs, not videos: Everywhere").
 */
class DownloadAsSongTest {
    private val video = Song("vid", "Jealous (Audio)", isVideo = true)

    @Test fun radiosOnlyKeepsThePickedVideo() {
        assertFalse(downloadsAsSong(video, AppSettings(radioSongsOnly = true, songsEverywhere = false)))
    }

    @Test fun everywhereDownloadsWhatPlays() {
        assertTrue(downloadsAsSong(video, AppSettings(radioSongsOnly = true, songsEverywhere = true)))
    }

    @Test fun offAndNonVideosStayAsTheyAre() {
        assertFalse(downloadsAsSong(video, AppSettings(radioSongsOnly = false, songsEverywhere = true)))
        assertFalse(downloadsAsSong(Song("s", "A song"), AppSettings(radioSongsOnly = true, songsEverywhere = true)))
        assertFalse(downloadsAsSong(video.copy(isEpisode = true), AppSettings(radioSongsOnly = true, songsEverywhere = true)))
    }
}
