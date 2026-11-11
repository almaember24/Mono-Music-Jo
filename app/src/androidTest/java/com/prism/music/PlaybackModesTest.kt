package com.prism.music

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #241: repeat one was off again after the song was swiped away from the mini player and Prism closed. Repeat and
 * shuffle are kept with nothing lined up too.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackModesTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var tone: android.net.Uri? = null

    @After fun cleanUp() {
        runCatching { instrumentation.runOnMainSync { Fixture.container.player.stop() } }
        tone?.let { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
    }

    @Test fun repeatAndShuffleComeBackWithNothingLinedUp() {
        Fixture.ready()
        // What Prism kept after the song was swiped away with repeat one and shuffle on: an empty queue.
        java.io.File(Fixture.context.filesDir, "queue.json")
            .writeText("""{"songs":[],"index":0,"positionMs":0,"title":"","shuffle":true,"shuffleOrder":[],"repeat":${Player.REPEAT_MODE_ONE}}""")
        Fixture.launch()
        val player = Fixture.container.player
        waitFor(20_000) { player.repeatMode.value == Player.REPEAT_MODE_ONE }
        assertEquals(Player.REPEAT_MODE_ONE, player.repeatMode.value)
    }

    @Test fun swipingTheSongAwayKeepsRepeatInWhatsSaved() {
        Fixture.ready()
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        tone = Fixture.tone("Prism repeat tone", 60)
        Fixture.launch()
        val local = Fixture.container.localMusic
        runBlocking { local.scan() }
        val song = local.tracks.value.first { it.song.title == "Prism repeat tone" }.song
        val player = Fixture.container.player
        instrumentation.runOnMainSync { player.playQueue(listOf(song), 0, QueueSource(QueueKind.OTHER, "Repeat test")) }
        waitFor(20_000) { player.currentSong.value?.id == song.id }
        // Off → all → one.
        instrumentation.runOnMainSync { player.cycleRepeat(); player.cycleRepeat() }
        waitFor(5_000) { player.repeatMode.value == Player.REPEAT_MODE_ONE }
        // The mini player's swipe down: the queue goes.
        instrumentation.runOnMainSync { player.stop() }
        val saved = java.io.File(Fixture.context.filesDir, "queue.json")
        waitFor(10_000) { saved.exists() && saved.readText().contains("\"songs\":[]") }
        assertTrue("repeat one kept: ${saved.readText()}", saved.readText().contains("\"repeat\":${Player.REPEAT_MODE_ONE}"))
    }

    /**
     * Reddit (1.8.0): shuffled, a song taken out of the queue and put back with Undo came back at a random place in the
     * play order. It comes back where it was: after the song it followed.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun undoPutsASongBackInItsPlaceWhileShuffled() {
        Fixture.ready()
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        val titles = (1..6).map { "Prism undo tone $it" }
        val tones = titles.map { Fixture.tone(it, 40) }
        try {
            Fixture.launch()
            val local = Fixture.container.localMusic
            val end = System.currentTimeMillis() + 20_000
            while (titles.any { t -> local.tracks.value.none { it.song.title == t } } && System.currentTimeMillis() < end) { runBlocking { local.scan() }; Thread.sleep(500) }
            val songs = titles.map { t -> local.tracks.value.first { it.song.title == t }.song }
            val player = Fixture.container.player
            instrumentation.runOnMainSync { player.playQueue(songs, 0, QueueSource(QueueKind.OTHER, "Undo test")) }
            waitFor(20_000) { player.currentSong.value?.id == songs[0].id }
            instrumentation.runOnMainSync { player.toggleShuffle() }
            waitFor(5_000) { player.shuffle.value }
            // A known order, where the song taken out (play position 3, list index 5) doesn't follow the song before
            // it in the list (index 4, played second): put back as a newly added song, it would land after that one.
            instrumentation.runOnMainSync {
                val p = player.player!!
                p.setShuffleOrder(com.prism.music.playback.HistoryShuffleOrder(intArrayOf(0, 4, 2, 5, 1, 3)) { p.currentMediaItemIndex })
            }
            fun order(): List<String> {
                var ids = emptyList<String>()
                instrumentation.runOnMainSync {
                    val p = player.player!!
                    val t = p.currentTimeline
                    val out = mutableListOf<String>()
                    var i = t.getFirstWindowIndex(true)
                    while (i != androidx.media3.common.C.INDEX_UNSET && out.size < t.windowCount) { out += p.getMediaItemAt(i).mediaId; i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true) }
                    ids = out
                }
                return ids
            }
            val before = order()
            // A song in the middle of the play order (not the playing one), taken out and put back.
            val target = before[3]
            var undo: (() -> Unit)? = null
            instrumentation.runOnMainSync {
                val p = player.player!!
                val index = (0 until p.mediaItemCount).first { p.getMediaItemAt(it).mediaId == target }
                undo = player.removeForUndo(index)
            }
            waitFor(5_000) { target !in order() }
            instrumentation.runOnMainSync { undo!!() }
            waitFor(5_000) { target in order() }
            assertEquals("the play order after Undo", before, order())
        } finally {
            tones.forEach { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
        }
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("not after $timeoutMs ms")
            Thread.sleep(200)
        }
    }
}
