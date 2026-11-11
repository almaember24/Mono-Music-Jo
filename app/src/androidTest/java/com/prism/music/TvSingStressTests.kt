package com.prism.music

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.TvFixture.awaitFocusOn
import com.prism.music.TvStress.pump
import com.prism.music.TvStress.until
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.tv.TAG_ACTIONS
import com.prism.music.ui.tv.TAG_AMBIENT
import com.prism.music.ui.tv.TAG_HOME
import com.prism.music.ui.tv.TAG_LYRICS
import com.prism.music.ui.tv.TAG_PLAYER
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two reports about Sing on a TV.
 * Reddit, 1.8.1: "Removing vocals works, but restoring them freezes the app and makes all buttons unresponsive." Sing on
 * and off again and again from the player, on music Sing really works on, with the main thread watched: after each off
 * the buttons answer, the song plays.
 * GitHub #342: "after a few seconds, the music poster or album artwork appears on the screen and remains displayed":
 * neither the ambient screen nor anything else takes the lyrics' place while Sing's page is open.
 */
@TvTest
@RunWith(AndroidJUnit4::class)
class TvSingStressTests {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val made = ArrayList<android.net.Uri>()
    private val c get() = Fixture.container
    private val player get() = c.player

    @Before fun setUp() {
        TvFixture.assumeTv()
        Fixture.ready()
        TvStress.mute()
        TvStress.grantAudio()
    }

    @After fun cleanUp() {
        TestHooks.singPassHangs = false
        TestHooks.singHolding = false
        runCatching { instrumentation.runOnMainSync { c.sing.setOn(false); c.sing.stuckMs = com.prism.music.playback.sing.SingWatch.STUCK_MS; player.stop() } }
        made.forEach { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
        c.settings.setTvAmbientMinutes(3)
    }

    private fun play(titles: List<String>, seconds: Int = 120) {
        titles.forEach { made += TvStress.music(it, seconds) }
        val songs = TvStress.songs(titles).mapIndexed { i, s ->
            TvStress.saveLyrics(s)
            s.copy(thumbnail = android.net.Uri.fromFile(TvStress.cover(i)).toString())
        }
        instrumentation.runOnMainSync { player.playQueue(songs, 0, QueueSource(QueueKind.OTHER, "TV Sing stress")) }
        compose.until(20_000, "playing") { player.isPlaying.value }
    }

    private fun openPlayer() {
        TvFixture.launch()
        compose.awaitFocusOn(hasTestTag("$TAG_HOME/player"), 20_000)
        TvFixture.ok()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/playpause"), 10_000)
    }

    /** From anywhere on the player's second row: along to ⋯, opened, and Sing (a test song's only action). */
    private fun openSing() {
        for (i in 0 until 10) {
            if (compose.onAllNodes(hasTestTag("$TAG_PLAYER/more") and isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) break
            TvFixture.right(); compose.pump(200)
        }
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/more"))
        TvFixture.ok()
        compose.awaitFocusOn(hasTestTag("$TAG_ACTIONS/0"), 10_000)
        TvFixture.ok()
        // Playing, the lyrics never settle for the test's rule, so what's asked of the screen goes by Sing's own state.
        compose.until(10_000, "Sing on") { c.sing.on.value }
    }

    @Test fun singOnAndOffAgainAndAgainLeavesTheButtonsAnswering() {
        play(listOf("Prism TV sing stress one", "Prism TV sing stress two"))
        openPlayer()
        TvFixture.down()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/lyrics"))
        TvStress.MainWatch().use { watch ->
            repeat(10) { round ->
                openSing()
                // Long enough for the quick way's work to be under way, on some rounds to have lined up and be taking
                // the voice out.
                compose.pump(if (round % 3 == 2) 8_000 else 2_500)
                watch.restart()
                TvFixture.back()
                // Off, at once, with the button that opened it back in focus, and the song playing on.
                compose.until(3_000, "Sing off (round $round)") { !c.sing.on.value }
                compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/more"), 5_000)
                compose.until(5_000, "playing (round $round)") { player.isPlaying.value }
                android.util.Log.i("PrismTest", "round $round: main thread held at most ${watch.longestMs} ms; ${watch.worstStack}")
                assertTrue("the main thread froze ${watch.longestMs} ms as Sing went off (round $round):\n${watch.worstStack}", watch.longestMs < FREEZE_MS)
            }
        }
        // Every button still answers: Left along the row, Up to the controls, OK pauses and plays.
        for (i in 0 until 10) {
            if (compose.onAllNodes(hasTestTag("$TAG_PLAYER/lyrics") and isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) break
            TvFixture.left(); compose.pump(200)
        }
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/lyrics"))
        TvFixture.up()
        compose.pump(300)
        val at = player.currentIndex.value
        TvFixture.press(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
        compose.until(5_000, "the next song") { player.currentIndex.value == at + 1 }
    }

    /** As fast as a hand can go: on and straight off, the work on the song just started. */
    @Test fun singSwitchedOffAsItStarts() {
        play(listOf("Prism TV sing quick"))
        openPlayer()
        TvStress.MainWatch().use { watch ->
            repeat(30) {
                instrumentation.runOnMainSync { c.sing.setOn(true) }
                instrumentation.runOnMainSync { player.currentSong.value?.let { s -> c.sing.prepare(listOf(s), 0) } }
                compose.pump(150)
                instrumentation.runOnMainSync { c.sing.setOn(false) }
                compose.pump(150)
            }
            compose.until(5_000, "playing") { player.isPlaying.value }
            android.util.Log.i("PrismTest", "30 quick rounds: main thread held at most ${watch.longestMs} ms; ${watch.worstStack}")
            assertTrue("the main thread froze ${watch.longestMs} ms:\n${watch.worstStack}", watch.longestMs < FREEZE_MS)
        }
    }

    /**
     * #342: a quiet minute (the ambient screen's shortest wait) and more on Sing's page, playing: still the lyrics, never
     * the ambient screen.
     */
    @Test fun theAmbientScreenStaysAwayFromSingsPage() {
        c.settings.setTvAmbientMinutes(1)
        play(listOf("Prism TV sing ambient"), seconds = 180)
        openPlayer()
        TvFixture.down()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/lyrics"))
        openSing()
        assertFalse("the ambient screen came over Sing's page", ambientComesWithin(95_000))
        assertTrue("Sing is still on", c.sing.on.value)
        assertLyricsThere()
    }

    /**
     * #342, the way it can happen: Sing lets go of a song on its own (its watchdog, a slow TV: the song sat loading while
     * Sing worked), so Sing is off while its page stays open, and the ambient screen was then free to come, with its
     * cover: the "poster". The page stays the lyrics for as long as it's open.
     */
    @Test fun singLettingGoLeavesTheLyricsOnScreen() {
        c.settings.setTvAmbientMinutes(1)
        play(listOf("Prism TV sing let go"), seconds = 180)
        openPlayer()
        TvFixture.down()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/lyrics"))
        openSing()
        // The song's been sat on (as a real TV's watchdog found): Sing lets go, its page still open.
        instrumentation.runOnMainSync { c.sing.setOn(false) }
        assertFalse("the ambient screen came over Sing's page", ambientComesWithin(95_000))
        assertLyricsThere()
        // And once the page is left, the ambient screen is free again: the quiet counts from there.
        TvFixture.back()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/more"), 10_000)
        instrumentation.runOnMainSync { player.setPlaying(true) }
        assertTrue("the ambient screen comes again away from Sing", ambientComesWithin(95_000))
    }

    /**
     * The reports side by side. A long song on Sing's page with the remote put down, as karaoke goes, then Back: Sing goes
     * off, and Back counts for nothing as input on a newer TV (it never reaches the activity's keys), so the ambient screen
     * could come at that moment, over a player the listener had just asked for.
     */
    @Test fun leavingSingAfterAQuietSpellShowsThePlayer() {
        c.settings.setTvAmbientMinutes(1)
        play(listOf("Prism TV sing quiet"), seconds = 180)
        openPlayer()
        TvFixture.down()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/lyrics"))
        openSing()
        compose.pump(70_000)
        TvFixture.back()
        compose.until(3_000, "Sing off") { !c.sing.on.value }
        assertFalse("the ambient screen came as Sing's page was left", ambientComesWithin(8_000))
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/more"), 5_000)
        // And the buttons answer.
        TvFixture.left()
        compose.pump(300)
        TvFixture.press(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
        compose.until(5_000, "paused") { !player.isPlaying.value }
    }

    /** Next with Sing on: the page follows the song, nothing breaks, and the main thread isn't held. */
    @Test fun skipsWithSingOn() {
        val titles = (1..6).map { "Prism TV sing skip $it" }
        titles.forEach { made += TvStress.music(it, 40) }
        val six = TvStress.songs(titles).mapIndexed { i, s ->
            TvStress.saveLyrics(s)
            s.copy(thumbnail = android.net.Uri.fromFile(TvStress.cover(i)).toString())
        }
        val songs = (0 until 30).map { six[it % six.size] }
        instrumentation.runOnMainSync { player.playQueue(songs, 0, QueueSource(QueueKind.OTHER, "TV Sing skips")) }
        compose.until(20_000, "playing") { player.isPlaying.value }
        openPlayer()
        TvFixture.down()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/lyrics"))
        openSing()
        TvStress.MainWatch().use { watch ->
            repeat(25) { i ->
                TvFixture.press(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
                compose.pump(if (i % 5 == 4) 3_000 else 250)
            }
            compose.until(15_000, { "the song after 25 skips, on ${player.currentIndex.value}" }) { player.currentIndex.value == 25 }
            compose.until(15_000, "playing") { player.isPlaying.value }
            android.util.Log.i("PrismTest", "25 skips with Sing on: main thread held at most ${watch.longestMs} ms; ${watch.worstStack}")
            assertTrue("the main thread froze ${watch.longestMs} ms:\n${watch.worstStack}", watch.longestMs < FREEZE_MS)
        }
        assertTrue("Sing stays on", c.sing.on.value)
    }

    /** With the song playing: the screens' rule isn't asked about nodes while it plays (the lyrics never settle). */
    private fun ambientComesWithin(ms: Long): Boolean {
        val end = android.os.SystemClock.uptimeMillis() + ms
        var seen = false
        compose.waitUntil(ms + 60_000) {
            seen = seen || com.prism.music.ui.tv.Idle.ambient
            seen || android.os.SystemClock.uptimeMillis() >= end
        }
        return seen
    }

    /** Sing's page is what's on screen (paused, so the rule can look). */
    private fun assertLyricsThere() {
        instrumentation.runOnMainSync { player.setPlaying(false) }
        assertEquals("the ambient screen", 0, compose.onAllNodes(hasTestTag(TAG_AMBIENT), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertTrue("the lyrics are gone", compose.onAllNodes(hasTestTag("$TAG_LYRICS/lines"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    private companion object {
        /** A freeze a listener sees: held longer than this as Sing goes off (a slow TV's frame or two is far less). */
        const val FREEZE_MS = 2_000L
    }
}
