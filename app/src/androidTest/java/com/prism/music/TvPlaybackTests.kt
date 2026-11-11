package com.prism.music

import android.view.KeyEvent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.TvFixture.awaitFocusOn
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.tv.TAG_HOME
import com.prism.music.ui.tv.TAG_PLAYER
import com.prism.music.ui.tv.TAG_QUEUE
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prism TV, slice 3: playing from Home and controlling it with the remote only. Songs are silent tones on the TV
 * (Fixture.tone), so nothing is heard and no network is needed.
 */
@TvTest
@RunWith(AndroidJUnit4::class)
class TvPlaybackTests {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val tones = ArrayList<android.net.Uri>()
    private val player get() = Fixture.container.player

    @Before fun setUp() {
        TvFixture.assumeTv()
        Fixture.ready()
    }

    @After fun cleanUp() {
        runCatching { instrumentation.runOnMainSync { player.stop() } }
        tones.forEach { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
    }

    /**
     * Two silent songs lined up and playing, as if picked from Home ([times] over, playing from [from]: a long queue
     * with the song playing far down it).
     */
    private fun lineUp(times: Int = 1, from: Int = 0) {
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        tones += Fixture.tone("Prism TV tone one", 90)
        tones += Fixture.tone("Prism TV tone two", 90)
        val local = Fixture.container.localMusic
        runBlocking { local.scan() }
        val two = listOf("Prism TV tone one", "Prism TV tone two").map { t -> local.tracks.value.first { it.song.title == t }.song }
        val songs = (0 until times).flatMap { two }
        instrumentation.runOnMainSync { player.playQueue(songs, from, QueueSource(QueueKind.OTHER, "TV test")) }
        waitFor(20_000) { player.currentIndex.value == from && player.isPlaying.value }
    }

    /**
     * The real TV's feedback (1.8.0): Left from the hero's first button opens the side navigation (it fell to a card
     * below), and the navigation's "Now playing" opens the player from any place.
     */
    @Test fun theSideNavigationLeadsBackToThePlayer() {
        lineUp()
        TvFixture.launch()
        compose.awaitFocusOn(hasTestTag("$TAG_HOME/player"), 15_000)
        TvFixture.left()
        compose.awaitFocusOn(hasTestTag("$TAG_HOME/playpause"))
        TvFixture.left()
        compose.awaitFocusOn(hasTestTag("${com.prism.music.ui.tv.TAG_NAV}/HOME"), 10_000)
        // Down past the places to "Now playing".
        repeat(4) { TvFixture.down(); Thread.sleep(250) }
        compose.awaitFocusOn(hasTestTag(com.prism.music.ui.tv.TAG_NAV_NOW))
        TvFixture.ok()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/playpause"), 10_000)
    }

    /** The review (1.8.0): far down a long queue, the queue still opens on the next song, and OK plays it. */
    @Test fun aLongQueueOpensOnTheNextSongFarDownAndPlaysIt() {
        lineUp(times = 15, from = 20)
        TvFixture.launch()
        compose.awaitFocusOn(hasTestTag("$TAG_HOME/player"), 15_000)
        TvFixture.ok()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/playpause"), 10_000)
        TvFixture.down()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/lyrics"))
        TvFixture.right()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/queue"))
        TvFixture.ok()
        compose.awaitFocusOn(hasTestTag("$TAG_QUEUE/21"), 10_000)
        // Two further down, and OK: that song plays (prepared and playing, as the phone's queue does).
        TvFixture.down(); Thread.sleep(200); TvFixture.down()
        compose.awaitFocusOn(hasTestTag("$TAG_QUEUE/23"))
        TvFixture.ok()
        waitFor(5_000) { player.currentIndex.value == 23 && player.isPlaying.value }
        // Left closes the panel.
        TvFixture.left()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/queue"), 10_000)
    }

    @Test fun homesHeroOpensThePlayerAndBackComesBack() {
        lineUp()
        TvFixture.launch()
        compose.awaitFocusOn(hasTestTag("$TAG_HOME/player"), 15_000)
        TvFixture.ok()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/playpause"), 10_000)
        // OK on play/pause pauses, OK again plays.
        TvFixture.ok()
        waitFor(5_000) { !player.isPlaying.value }
        TvFixture.ok()
        waitFor(5_000) { player.isPlaying.value }
        // Back: Home, with focus where it was.
        TvFixture.back()
        compose.awaitFocusOn(hasTestTag("$TAG_HOME/player"), 10_000)
    }

    @Test fun mediaKeysWorkOnEveryScreen() {
        lineUp()
        TvFixture.launch()
        compose.awaitFocusOn(hasTestTag("$TAG_HOME/player"), 15_000)
        TvFixture.press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        waitFor(5_000) { !player.isPlaying.value }
        TvFixture.press(KeyEvent.KEYCODE_MEDIA_PLAY)
        waitFor(5_000) { player.isPlaying.value }
        TvFixture.press(KeyEvent.KEYCODE_MEDIA_NEXT)
        waitFor(5_000) { player.currentIndex.value == 1 }
        // On the player too, and focus stays where it was (a media key never moves it).
        TvFixture.ok()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/playpause"), 10_000)
        TvFixture.press(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        waitFor(5_000) { player.currentIndex.value == 0 }
        TvFixture.press(KeyEvent.KEYCODE_MEDIA_PAUSE)
        waitFor(5_000) { !player.isPlaying.value }
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/playpause"))
    }

    @Test fun theQueueOpensOverThePlayerAndPlaysTheNextSong() {
        lineUp()
        TvFixture.launch()
        compose.awaitFocusOn(hasTestTag("$TAG_HOME/player"), 15_000)
        TvFixture.ok()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/playpause"), 10_000)
        // Down to the second row (Lyrics first), then right to Queue.
        TvFixture.down()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/lyrics"))
        TvFixture.right()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/queue"))
        TvFixture.ok()
        compose.awaitFocusOn(hasTestTag("$TAG_QUEUE/1"), 10_000)
        TvFixture.ok()
        waitFor(5_000) { player.currentIndex.value == 1 }
        // Back closes the panel; the Queue button has focus again.
        TvFixture.back()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/queue"), 10_000)
        assertEquals(1, player.currentIndex.value)
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("not after $timeoutMs ms")
            Thread.sleep(100)
        }
    }
}
