package com.prism.music

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.TvFixture.awaitFocusOn
import com.prism.music.data.db.LyricsEntity
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.tv.TAG_HOME
import com.prism.music.ui.tv.TAG_LYRICS
import com.prism.music.ui.tv.TAG_PLAYER
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prism TV, slice 4: big lyrics from the player, a line picked with the remote plays from there, and Back returns to
 * the player. The song is a silent tone with lyrics saved for it (as if found earlier), so no network is needed.
 */
@TvTest
@RunWith(AndroidJUnit4::class)
class TvLyricsTests {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var tone: android.net.Uri? = null
    private val player get() = Fixture.container.player

    @Before fun setUp() {
        TvFixture.assumeTv()
        Fixture.ready()
    }

    @After fun cleanUp() {
        runCatching { instrumentation.runOnMainSync { player.stop() } }
        tone?.let { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
    }

    /** A silent song with saved lyrics, paused, its lyrics page open from Home with the lines focused. */
    private fun openLyrics() {
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        tone = Fixture.tone("Prism TV lyrics tone", 120)
        val local = Fixture.container.localMusic
        runBlocking { local.scan() }
        val song = local.tracks.value.first { it.song.title == "Prism TV lyrics tone" }.song
        val lrc = (0 until 12).joinToString("\n") { i -> "[00:%02d.00]Line number ${i + 1} of the tone".format(i * 8) }
        runBlocking { Fixture.container.db.lyrics().put(LyricsEntity(song.id, "LRCLIB", true, lrc, System.currentTimeMillis())) }
        instrumentation.runOnMainSync { player.playQueue(listOf(song), 0, QueueSource(QueueKind.OTHER, "TV lyrics test")) }

        // Paused from the start: the sung line's sweep moves every frame while playing, and the test framework waits for
        // the screen to be still.
        val end0 = System.currentTimeMillis() + 20_000
        while (!player.isPlaying.value && System.currentTimeMillis() < end0) Thread.sleep(100)
        instrumentation.runOnMainSync { player.setPlaying(false) }
        TvFixture.launch()
        compose.awaitFocusOn(hasTestTag("$TAG_HOME/player"), 20_000)
        // Home's own Lyrics button, right of "Now playing".
        TvFixture.right()
        compose.awaitFocusOn(hasTestTag("$TAG_HOME/lyrics"))
        TvFixture.ok()
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("Line number 1 of the tone", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.awaitFocusOn(hasTestTag("$TAG_LYRICS/lines"), 10_000)
    }

    @Test fun pickingALineWithTheRemotePlaysFromIt() {
        openLyrics()
        // Three lines down and OK plays from there (0:24).
        repeat(3) { TvFixture.down(); Thread.sleep(200) }
        // The picked line glides up into the sung line's place (a third of the way down), the list moving under it.
        compose.waitUntil(5_000) {
            val list = compose.onAllNodes(hasTestTag("$TAG_LYRICS/list"), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot
            val line = compose.onAllNodes(hasTestTag("$TAG_LYRICS/line/3"), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot
            list != null && line != null && line.top - list.top < list.height * 0.45f
        }
        TvFixture.ok()
        // The player is read on its own thread.
        fun position(): Long { var p = 0L; instrumentation.runOnMainSync { p = player.position }; return p }
        val end = System.currentTimeMillis() + 5_000
        while (position() < 20_000 && System.currentTimeMillis() < end) Thread.sleep(100)
        assertTrue("played from the picked line: ${position()} ms", position() in 20_000..40_000)
        // Playing from it let go of the line (no "Back to now"), so one Back leaves the lyrics.
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("$TAG_LYRICS/now"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        compose.awaitFocusOn(hasTestTag("$TAG_LYRICS/lines"))
        // Back: Home, on its Lyrics button; then the player, its controls as they were.
        TvFixture.back()
        compose.awaitFocusOn(hasTestTag("$TAG_HOME/lyrics"), 10_000)
        TvFixture.left(); TvFixture.ok()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/playpause"), 10_000)
    }

    /** The owner: a line picked, Back (or a moment without a key) goes back to the sung line, following the song. */
    @Test fun backFromAPickedLineFollowsTheSongAgain() {
        openLyrics()
        TvFixture.down(); TvFixture.down()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("$TAG_LYRICS/now"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        TvFixture.back()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("$TAG_LYRICS/now"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        compose.awaitFocusOn(hasTestTag("$TAG_LYRICS/lines"))
        // Still on the lyrics (the first Back only let go of the line).
        compose.onNode(hasTestTag("$TAG_LYRICS/list"), useUnmergedTree = true).assertExists()
        // And on its own, after a few seconds without a key.
        TvFixture.down()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("$TAG_LYRICS/now"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(com.prism.music.ui.tv.TV_PICK_RETURN_MS + 3_000) { compose.onAllNodes(hasTestTag("$TAG_LYRICS/now"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }

    /** The owner: the song's lyrics timing from the lyrics, with the remote, kept per song (the phone's own store). */
    @Test fun timingMovesTheLyricsAndIsKeptForTheSong() {
        openLyrics()
        val c = Fixture.container
        instrumentation.runOnMainSync { player.setLyricOffset(0L) }
        // Right to Timing, OK: the panel, focus on its nudge.
        TvFixture.right()
        compose.awaitFocusOn(hasTestTag("$TAG_LYRICS/timing"))
        TvFixture.ok()
        compose.awaitFocusOn(hasTestTag("$TAG_LYRICS/nudge"), 5_000)
        repeat(3) { TvFixture.right(); Thread.sleep(150) }
        compose.waitUntil(5_000) { player.lyricOffset.value == 300L }
        val song = player.currentSong.value!!
        assertTrue("kept for the song", c.lyricOffsets.getLong(song.id, 0L) == 300L)
        // The lyrics' time runs 0.3 s ahead of the music now: each line lights up 0.3 s earlier.
        var shift = 0L
        instrumentation.runOnMainSync { shift = player.lyricPosition - player.position }
        assertTrue("lines 0.3 s earlier ($shift ms)", shift == 300L)
        // OK puts it back in sync; Back closes the panel, focus back on the lines.
        TvFixture.ok()
        compose.waitUntil(5_000) { player.lyricOffset.value == 0L }
        TvFixture.back()
        compose.awaitFocusOn(hasTestTag("$TAG_LYRICS/lines"), 5_000)
    }
}