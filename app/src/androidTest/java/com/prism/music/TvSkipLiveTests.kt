package com.prism.music

import android.view.KeyEvent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.TvFixture.awaitFocusOn
import com.prism.music.TvStress.pump
import com.prism.music.TvStress.until
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.model.SongItem
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.tv.TAG_HOME
import com.prism.music.ui.tv.TAG_PLAYER
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real thing, so only with `record=true` (it plays YouTube's songs and draws their covers, muted): fifty skips on
 * the player, and with a music video on. [TvSkipStressTests] is the same with files on the TV, every run.
 */
@Live
@TvTest
@RunWith(AndroidJUnit4::class)
class TvSkipLiveTests {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = Fixture.container
    private val player get() = c.player

    @Before fun setUp() {
        TvFixture.assumeTv()
        assumeTrue("live test: run with record=true", InstrumentationRegistry.getArguments().getString("record") == "true")
        Fixture.ready()
        TvStress.mute()
    }

    private fun songs() = runBlocking {
        withContext(Dispatchers.IO) {
            listOf("queen greatest hits", "daft punk", "the weeknd", "taylor swift", "bad bunny").flatMap { q ->
                runCatching { c.ytm.search(q, SearchFilter.SONGS).items.filterIsInstance<SongItem>().map { it.song } }.getOrDefault(emptyList())
            }.distinctBy { it.id }.take(60)
        }
    }

    private fun start(): Int {
        val list = songs()
        android.util.Log.i("PrismTest", "${list.size} songs")
        assumeTrue("YouTube answered (${list.size} songs)", list.size >= 20)
        instrumentation.runOnMainSync { player.playQueue(list, 0, QueueSource(QueueKind.OTHER, "TV live skips")) }
        TvFixture.launch()
        compose.awaitFocusOn(hasTestTag("$TAG_HOME/player"), 30_000)
        TvFixture.ok()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/playpause"), 20_000)
        return list.size
    }

    @Test fun fiftySkipsOfRealSongs() {
        val count = start()
        TvStress.MainWatch().use { watch ->
            TvFixture.right()
            compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/next"))
            repeat(minOf(50, count - 1)) { i ->
                TvFixture.ok()
                // Some straight on, some once the song has started.
                compose.pump(when { i % 9 == 8 -> 6_000L; i % 3 == 2 -> 1_500L; else -> 300L })
            }
            compose.until(90_000, { "a song playing after the skips, on ${player.currentIndex.value}" }) { player.isPlaying.value }
            android.util.Log.i("PrismTest", "main thread held at most ${watch.longestMs} ms; ${watch.worstStack}")
        }
    }

    /**
     * GitHub #342: "after a few seconds, the music poster or album artwork appears on the screen and remains displayed".
     * A real song on Sing's page, left alone: pictures of the screen over two and a half minutes (in /sdcard/prism-tests/),
     * for looking at, and the page still there at the end.
     */
    @Test fun singsPageLeftAloneOnARealSong() {
        c.settings.setTvAmbientMinutes(1)
        val song = songs().first { !it.isVideo && it.durationSec > 150 }
        instrumentation.runOnMainSync { player.playQueue(listOf(song), 0, QueueSource(QueueKind.SINGLE, "TV live Sing")) }
        TvFixture.launch()
        compose.awaitFocusOn(hasTestTag("$TAG_HOME/player"), 30_000)
        TvFixture.ok()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/playpause"), 20_000)
        TvFixture.down()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/lyrics"))
        for (i in 0 until 8) {
            if (compose.onAllNodes(hasTestTag("$TAG_PLAYER/more") and androidx.compose.ui.test.isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) break
            TvFixture.right(); compose.pump(250)
        }
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/more"))
        TvFixture.ok()
        // Its album, artists, then Sing: the last of the list.
        val actions = com.prism.music.ui.tv.TAG_ACTIONS
        compose.awaitFocusOn(hasTestTag("$actions/0"), 10_000)
        for (i in 0 until 8) { TvFixture.down(); compose.pump(150) }
        TvFixture.ok()
        compose.until(10_000, "Sing on") { c.sing.on.value }
        val started = System.currentTimeMillis()
        for (at in listOf(5, 15, 30, 45, 60, 75, 90, 120, 150)) {
            compose.pump((at * 1000L - (System.currentTimeMillis() - started)).coerceAtLeast(0))
            Fixture.screenshot("sing-left-alone-${at}s")
            android.util.Log.i("PrismTest", "at $at s: Sing on ${c.sing.on.value}, playing ${player.isPlaying.value}, problem ${c.sing.problem.value}")
            org.junit.Assert.assertFalse("the ambient screen over Sing at $at s", com.prism.music.ui.tv.Idle.ambient)
        }
    }

    /**
     * Reddit, Prism TV: "Removing vocals works, but restoring them freezes the app and makes all buttons unresponsive."
     * Where Sing's engine runs (only a 64-bit or ARM TV, so not the small test TVs): a song being separated, Back. The song
     * in hand is finished for a while yet (Sing's grace), with the network busy on every core: the player's buttons must
     * answer through it. The log says which threads were working, at what priority.
     */
    @Test fun leavingSingWhileItSeparatesAFileLeavesTheButtonsAnswering() {
        assumeTrue("Sing's engine runs here", c.sing.fullEngine)
        assumeTrue("Sing's engine downloaded", runBlocking { c.sing.engineReady() || c.sing.downloadEngine() })
        c.sing.rechoose()
        TvStress.grantAudio()
        val title = "Prism TV Sing engine"
        val file = Fixture.tone(title, 240)
        try {
            val song = TvStress.songs(listOf(title)).first()
            instrumentation.runOnMainSync { player.playQueue(listOf(song), 0, QueueSource(QueueKind.OTHER, "TV Sing engine")) }
            compose.until(20_000, "the file playing") { player.isPlaying.value }
            instrumentation.runOnMainSync { c.sing.setOn(true) }
            TvFixture.launch()
            compose.awaitFocusOn(hasTestTag("$TAG_HOME/player"), 30_000)
            TvFixture.ok()
            compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/playpause"), 20_000)
            // The networks at work on this very song, not the quick way's stand-in or the decoder alone.
            compose.until(180_000, { "separating (${c.sing.method.value}, ${c.sing.progress.value})" }) { (c.sing.progress.value[song.id] ?: 0f) > 0.02f }
            val busy = TvStress.busiestThreads(4_000) { compose.pump(it) }
            android.util.Log.i("PrismTest", "method ${c.sing.method.value}, progress ${c.sing.progress.value}; busiest as it separates:\n${busy.joinToString("\n")}")
            TvStress.MainWatch().use { watch ->
                repeat(6) { round ->
                    instrumentation.runOnMainSync { c.sing.setOn(false) }
                    compose.pump(2_000)
                    org.junit.Assert.assertTrue("playing after Sing off, round $round", player.isPlaying.value)
                    instrumentation.runOnMainSync { c.sing.setOn(true) }
                    compose.pump(2_000)
                    android.util.Log.i("PrismTest", "round $round: main thread held at most ${watch.longestMs} ms; progress ${c.sing.progress.value}")
                }
                instrumentation.runOnMainSync { c.sing.setOn(false) }
                val threads = TvStress.busiestThreads(10_000) { compose.pump(it) }
                android.util.Log.i("PrismTest", "Sing off, busiest (name, nice, ms of CPU in 10 s):\n${threads.joinToString("\n")}")
                repeat(12) { i ->
                    val started = android.os.SystemClock.uptimeMillis()
                    if (i % 2 == 0) TvFixture.right() else TvFixture.left()
                    compose.pump(100)
                    android.util.Log.i("PrismTest", "key $i answered within ${android.os.SystemClock.uptimeMillis() - started} ms")
                }
                android.util.Log.i("PrismTest", "main thread held at most ${watch.longestMs} ms; ${watch.worstStack}")
                org.junit.Assert.assertTrue("the main thread froze ${watch.longestMs} ms:\n${watch.worstStack}", watch.longestMs < 2_000)
            }
            fun position(): Long { var ms = 0L; instrumentation.runOnMainSync { ms = player.position }; return ms }
            val at = position()
            compose.pump(3_000)
            val later = position()
            org.junit.Assert.assertTrue("the song plays on ($later after $at)", later > at)
            TvFixture.press(KeyEvent.KEYCODE_MEDIA_PAUSE)
            compose.until(10_000, "paused") { !player.isPlaying.value }
        } finally {
            runCatching { instrumentation.runOnMainSync { c.sing.setOn(false); player.stop() } }
            runCatching { Fixture.context.contentResolver.delete(file, null, null) }
        }
    }

    @Test fun leavingSingWhileItsEngineWorksLeavesTheButtonsAnswering() {
        assumeTrue("Sing's engine runs here", c.sing.fullEngine)
        assumeTrue("Sing's engine downloaded", runBlocking { c.sing.engineReady() || c.sing.downloadEngine() })
        c.sing.rechoose()
        val song = songs().first { !it.isVideo && it.durationSec > 150 }
        instrumentation.runOnMainSync { player.playQueue(listOf(song), 0, QueueSource(QueueKind.SINGLE, "TV live Sing engine")) }
        TvFixture.launch()
        compose.awaitFocusOn(hasTestTag("$TAG_HOME/player"), 30_000)
        TvFixture.ok()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/playpause"), 20_000)
        TvFixture.down()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/lyrics"))
        for (i in 0 until 8) {
            if (compose.onAllNodes(hasTestTag("$TAG_PLAYER/more") and androidx.compose.ui.test.isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) break
            TvFixture.right(); compose.pump(250)
        }
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/more"))
        TvFixture.ok()
        val actions = com.prism.music.ui.tv.TAG_ACTIONS
        compose.awaitFocusOn(hasTestTag("$actions/0"), 10_000)
        for (i in 0 until 8) { TvFixture.down(); compose.pump(150) }
        TvFixture.ok()
        compose.until(10_000, "Sing on") { c.sing.on.value }
        // The network way under way (past the quick way's stand-in).
        val waited = System.currentTimeMillis()
        while (System.currentTimeMillis() - waited < 120_000 && TvStress.busiestThreads(4_000) { compose.pump(it) }.none { it.contains("onnx", true) || it.contains("PrismSing") }) Unit
        android.util.Log.i("PrismTest", "method ${c.sing.method.value}, progress ${c.sing.progress.value}, as Back is pressed")
        TvStress.MainWatch().use { watch ->
            TvFixture.back()
            compose.until(5_000, "Sing off") { !c.sing.on.value }
            compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/more"), 10_000)
            val threads = TvStress.busiestThreads(10_000) { compose.pump(it) }
            android.util.Log.i("PrismTest", "after Back, busiest threads (name, nice, ms of CPU in 10 s):\n${threads.joinToString("\n")}")
            // Every button: along the row and back, each key answered.
            repeat(12) { i ->
                val started = android.os.SystemClock.uptimeMillis()
                if (i % 2 == 0) TvFixture.left() else TvFixture.right()
                compose.pump(100)
                android.util.Log.i("PrismTest", "key $i answered within ${android.os.SystemClock.uptimeMillis() - started} ms")
            }
            android.util.Log.i("PrismTest", "main thread held at most ${watch.longestMs} ms; ${watch.worstStack}")
            org.junit.Assert.assertTrue("the main thread froze ${watch.longestMs} ms:\n${watch.worstStack}", watch.longestMs < 2_000)
        }
        TvFixture.press(KeyEvent.KEYCODE_MEDIA_PAUSE)
        compose.until(10_000, "paused") { !player.isPlaying.value }
    }

    /** With a music video on the screen, each next song lined up as its video too (Keep playing videos). */
    @Test fun skipsWithAMusicVideoOn() {
        val count = start()
        TvFixture.down()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/lyrics"))
        TvFixture.right()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/queue"))
        TvFixture.right()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/video"), 10_000)
        TvFixture.ok()
        compose.pump(8_000)
        TvStress.MainWatch().use { watch ->
            repeat(minOf(25, count - 1)) { i ->
                TvFixture.press(KeyEvent.KEYCODE_MEDIA_NEXT)
                compose.pump(if (i % 5 == 4) 8_000 else 1_000)
            }
            compose.until(90_000, { "a song playing after the skips, on ${player.currentIndex.value}" }) { player.isPlaying.value }
            android.util.Log.i("PrismTest", "main thread held at most ${watch.longestMs} ms; ${watch.worstStack}")
        }
    }
}
