package com.prism.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1.7.9 (Reddit: "it plays the same songs over and over"): a song a radio brought, skipped at once, isn't suggested
 * again for a while; a song skipped in the listener's own playlist isn't held against it.
 */
@RunWith(AndroidJUnit4::class)
class SuggestionSkipTest {
    @Before fun start() {
        Fixture.ready()
        Fixture.launch()
    }

    private suspend fun playAndSkip(kind: QueueKind, prefix: String): String {
        val c = Fixture.container
        val songs = Fixture.songs(3, prefix)
        withContext(Dispatchers.Main) { c.player.playQueue(songs, 0, QueueSource(kind, prefix)) }
        withTimeout(10_000) { while (c.player.currentSong.value?.id != songs[0].id) delay(50) }
        withContext(Dispatchers.Main) { c.player.next() }
        withTimeout(10_000) { while (c.player.currentSong.value?.id == songs[0].id) delay(50) }
        delay(500)
        return songs[0].id
    }

    @Test fun aRadioSongSkippedAtOnceIsLeftOut() = runBlocking {
        val id = playAndSkip(QueueKind.RADIO, "Radio song")
        assertTrue("skipped from a radio: left out of suggestions", Fixture.container.recFeedback.turnedDown(id, known = false))
    }

    @Test fun aSongSkippedInYourOwnPlaylistIsNotHeldAgainstIt() = runBlocking {
        val id = playAndSkip(QueueKind.PLAYLIST, "Playlist song")
        assertFalse("skipped in your own playlist: nothing recorded", Fixture.container.recFeedback.turnedDown(id, known = false))
    }
}
