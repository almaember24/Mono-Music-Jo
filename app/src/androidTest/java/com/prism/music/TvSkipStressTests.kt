package com.prism.music

import android.view.KeyEvent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.TvFixture.awaitFocusOn
import com.prism.music.TvStress.pump
import com.prism.music.TvStress.until
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.tv.TAG_HOME
import com.prism.music.ui.tv.TAG_LYRICS
import com.prism.music.ui.tv.TAG_PLAYER
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reddit, 1.8.1: "Clicking Next Song sometimes crashes the app repeatedly" (no log). Fifty skips with the remote, on the
 * player's Next button, from the remote's own media key, and on the lyrics page: real covers (so the cover's frost,
 * mesh and color are made for each), saved lyrics, songs lined up one after another. A crash anywhere fails the run; the
 * main thread is watched too.
 */
@TvTest
@RunWith(AndroidJUnit4::class)
class TvSkipStressTests {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val made = ArrayList<android.net.Uri>()
    private val player get() = Fixture.container.player

    @Before fun setUp() {
        TvFixture.assumeTv()
        Fixture.ready()
        TvStress.mute()
        TvStress.grantAudio()
    }

    @After fun cleanUp() {
        runCatching { instrumentation.runOnMainSync { player.stop() } }
        made.forEach { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
    }

    /** [count] songs lined up and playing: silent files, each with its own 1080 px cover and saved lyrics. */
    private fun lineUp(count: Int) {
        val titles = (0 until 6).map { "Prism TV skip $it" }
        titles.forEach { made += Fixture.tone(it, 40) }
        val six = TvStress.songs(titles).mapIndexed { i, s ->
            TvStress.saveLyrics(s)
            s.copy(thumbnail = android.net.Uri.fromFile(TvStress.cover(i)).toString())
        }
        val songs = (0 until count).map { six[it % six.size] }
        instrumentation.runOnMainSync { player.playQueue(songs, 0, QueueSource(QueueKind.OTHER, "TV skip test")) }
        compose.until(20_000, "the queue playing") { player.currentIndex.value == 0 && player.isPlaying.value }
    }

    private fun openPlayer() {
        TvFixture.launch()
        compose.awaitFocusOn(hasTestTag("$TAG_HOME/player"), 20_000)
        TvFixture.ok()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/playpause"), 10_000)
    }

    private fun assertAlive(watch: TvStress.MainWatch, skips: Int, from: Int = 0) {
        compose.until(15_000, { "the song after $skips skips (index ${player.currentIndex.value})" }) { player.currentIndex.value == from + skips }
        compose.until(15_000, "playing after $skips skips") { player.isPlaying.value }
        android.util.Log.i("PrismTest", "$skips skips: main thread held at most ${watch.longestMs} ms")
    }

    @Test fun fiftySkipsOnThePlayersNextButton() {
        lineUp(60)
        openPlayer()
        TvFixture.right()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/next"))
        TvStress.MainWatch().use { watch ->
            repeat(50) { i ->
                // A hand on a remote: some quick presses, some a moment apart (as the song has started).
                TvFixture.ok()
                compose.pump(if (i % 7 == 6) 1_500 else 200)
            }
            assertAlive(watch, 50)
            // Focus is still on Next, and it still works.
            compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/next"), 10_000)
            TvFixture.ok()
            assertAlive(watch, 51)
        }
    }

    @Test fun fiftySkipsAsFastAsTheRemotesMediaKeyGoes() {
        lineUp(60)
        openPlayer()
        TvStress.MainWatch().use { watch ->
            repeat(50) { TvFixture.press(KeyEvent.KEYCODE_MEDIA_NEXT); compose.pump(30) }
            assertAlive(watch, 50)
            repeat(25) { TvFixture.press(KeyEvent.KEYCODE_MEDIA_PREVIOUS); compose.pump(30) }
            // (Previous within a song's first seconds goes to the song before; further in it would start the song over.)
            compose.until(15_000, { "back through the queue, at ${player.currentIndex.value}" }) { player.currentIndex.value in 25 until 50 }
            compose.until(15_000, "playing") { player.isPlaying.value }
        }
    }

    /** On the lyrics page, whose lines change with every song. */
    @Test fun fiftySkipsOnTheLyricsPage() {
        lineUp(60)
        openPlayer()
        TvFixture.down()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/lyrics"))
        TvFixture.ok()
        // (Playing, the lyrics never settle for the test's rule: the page is looked for with the song paused.)
        instrumentation.runOnMainSync { player.setPlaying(false) }
        compose.awaitFocusOn(hasTestTag("$TAG_LYRICS/lines"), 20_000)
        instrumentation.runOnMainSync { player.setPlaying(true) }
        TvStress.MainWatch().use { watch ->
            repeat(50) { i ->
                TvFixture.press(KeyEvent.KEYCODE_MEDIA_NEXT)
                compose.pump(if (i % 7 == 6) 1_500 else 150)
            }
            assertAlive(watch, 50)
        }
    }

    /**
     * Past the end of the queue: Next on the last song does nothing, and nothing breaks. Autoplay may add songs from the
     * TV's own files as the end nears (a device with other music on it does), so "the end" is wherever the queue ends.
     */
    @Test fun nextOnTheLastSongStaysPut() {
        lineUp(3)
        openPlayer()
        TvFixture.right()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/next"))
        fun atTheEnd() = player.currentIndex.value == player.queue.value.size - 1 && !Fixture.container.queue.autoplayLoading
        var presses = 0
        while (presses < 10 || (!atTheEnd() && presses < 60)) { TvFixture.ok(); compose.pump(150); presses++ }
        compose.until(10_000, { "at the end (index ${player.currentIndex.value} of ${player.queue.value.size})" }) { atTheEnd() }
        val last = player.currentIndex.value
        repeat(5) { TvFixture.ok(); compose.pump(150) }
        assertEquals(last, player.currentIndex.value)
        assertTrue(player.currentSong.value != null)
    }
}
