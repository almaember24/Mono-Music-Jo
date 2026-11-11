package com.prism.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.model.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live, so only with `record=true` (it plays real audio from YouTube; the recordings hold no media): a music video
 * playing as a song takes its song's album cover (Reddit 1.7.9, "Carl Sagan" by Night Moves showed a video frame), and
 * the swap doesn't stop or restart the music.
 */
@Live
@RunWith(AndroidJUnit4::class)
class VideoCoverLiveTest {
    @Test fun aMusicVideoPlayingAsASongShowsItsSongsCover() = runBlocking {
        assumeTrue("live test: run with record=true", InstrumentationRegistry.getArguments().getString("record") == "true")
        Fixture.ready()
        Fixture.launch()
        val c = Fixture.container
        suspend fun pos() = withContext(Dispatchers.Main) { c.player.position }
        val video = withContext(Dispatchers.IO) {
            c.ytm.search("Night Moves Carl Sagan", SearchFilter.VIDEOS).items.filterIsInstance<SongItem>().map { it.song }
                .first { it.isVideo && it.title.contains("Carl Sagan", ignoreCase = true) }
        }
        withContext(Dispatchers.Main) {
            c.player.playQueue(listOf(video), 0, com.prism.music.playback.QueueSource(com.prism.music.playback.QueueKind.SINGLE, "test"))
        }
        // Music under way.
        val end = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < end && pos() < 3_000) kotlinx.coroutines.delay(200)
        assertTrue("the video's sound plays", pos() >= 3_000)
        // Then its song's cover, without the music stopping.
        while (System.currentTimeMillis() < end && c.player.currentSong.value?.thumbnail == video.thumbnail) kotlinx.coroutines.delay(200)
        val swappedAt = pos()
        assertNotEquals("the song's cover, not the video's", video.thumbnail, c.player.currentSong.value?.thumbnail)
        kotlinx.coroutines.delay(2_000)
        assertTrue("still playing on from ${swappedAt} ms (now ${pos()} ms)", pos() > swappedAt + 1_000)
        Fixture.screenshot("VideoCoverLiveTest-player")
    }
}
