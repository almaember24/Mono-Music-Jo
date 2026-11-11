package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.db.LyricsEntity
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.PlayerLayout
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1.8.1's player round: a lyric line tapped while paused (#308), Start radio on the song playing (#292), a swipe down
 * above the cover in the Cover layout (#276), and the mini player without play / pause (Reddit). A silent tone on the
 * phone stands in for a song, so no network is needed.
 */
@RunWith(AndroidJUnit4::class)
class PlayerRound181Test {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private var tone: android.net.Uri? = null
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val player get() = Fixture.container.player

    @After fun cleanUp() {
        runCatching { instrumentation.runOnMainSync { player.stop() } }
        tone?.let { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
    }

    /** The tone [title] playing (Prism open), with [lyrics] saved for it when given. */
    private fun play(title: String, lyrics: String? = null): Song {
        Fixture.ready()
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        tone = Fixture.tone(title, 120)
        Fixture.launch()
        val local = Fixture.container.localMusic
        runBlocking { local.scan() }
        val song = local.tracks.value.first { it.song.title == title }.song
        if (lyrics != null) runBlocking { Fixture.container.db.lyrics().put(LyricsEntity(song.id, "LRCLIB", true, lyrics, System.currentTimeMillis())) }
        instrumentation.runOnMainSync { player.playQueue(listOf(song), 0, QueueSource(QueueKind.OTHER, "Round test")) }
        compose.waitForText(title, 20_000)
        return song
    }

    /** The full player (by the link a widget's cover uses), then paused so Compose goes idle. */
    private fun openPlayer() {
        Fixture.context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("prism://player"))
                .setClass(Fixture.context, PrismActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        Thread.sleep(2_000)
        instrumentation.runOnMainSync { player.setPlaying(false) }
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("playerArtBox")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun position(): Long { var p = 0L; instrumentation.runOnMainSync { p = player.position }; return p }

    /** #308: tapping a line while paused moves the music there, and the lyrics with it (they stayed on the old line). */
    @Test fun aLineTappedWhilePausedBecomesTheSungLine() {
        val lrc = (0 until 12).joinToString("\n") { i -> "[00:%02d.00]Line number ${i + 1} of the tone".format(i * 8) }
        play("Prism paused lyrics tone", lrc)
        openPlayer()
        compose.clickWhenFound(hasContentDescription(tr(R.string.lyrics)) and hasClickAction(), 15_000)
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Line number 1 of the tone", substring = true) and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("paused", !player.isPlaying.value)
        // Line 4 is at 0:24.
        compose.clickWhenFound(hasText("Line number 4 of the tone", substring = true) and hasClickAction())
        val end = System.currentTimeMillis() + 5_000
        while (position() < 20_000 && System.currentTimeMillis() < end) Thread.sleep(100)
        assertTrue("the music moved to the line: ${position()} ms", position() in 20_000..30_000)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Line number 4 of the tone", substring = true) and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("still paused", !player.isPlaying.value)
    }

    /** #292: Start radio on the song playing keeps it playing where it is, the radio lined up after it. */
    @Test fun startRadioKeepsTheSongPlaying() {
        val song = play("Prism radio tone")
        instrumentation.runOnMainSync { player.seekTo(40_000) }
        val end = System.currentTimeMillis() + 5_000
        while (position() < 40_000 && System.currentTimeMillis() < end) Thread.sleep(100)
        instrumentation.runOnMainSync { player.playRadio(song) }
        Thread.sleep(1_000)
        assertTrue("it carried on from 0:40 (${position()} ms)", position() >= 40_000)
        assertEquals("the same song", song.id, player.currentSong.value?.id)
        assertEquals("now a radio of it", QueueKind.RADIO, Fixture.container.queue.source.value.kind)
        assertEquals(song.id, Fixture.container.queue.source.value.seedId)
    }

    /** #276: in the Cover layout, a swipe down above the cover closes the player (it only worked on the cover). */
    @Test fun aSwipeDownAboveTheCoverClosesThePlayer() {
        runBlocking { Fixture.container.settings.setPlayerLayout(PlayerLayout.COVER) }
        play("Prism cover layout tone")
        openPlayer()
        // From the top edge of the cover's room, above the cover itself.
        compose.onAllNodes(hasTestTag("playerArtBox")).onFirst().performTouchInput { swipeDown(startY = top + 4f, endY = top + height * 0.7f) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("playerArtBox")).fetchSemanticsNodes().isEmpty() }
    }

    /** Reddit (1.8.1): play / pause can be left off the mini player, as Next can; on by default. */
    @Test fun theMiniPlayersPlayButtonCanGo() {
        play("Prism mini player tone")
        instrumentation.runOnMainSync { player.setPlaying(false) }
        val miniPlay = hasContentDescription(tr(R.string.play_pause))
        compose.waitUntil(10_000) { compose.onAllNodes(miniPlay).fetchSemanticsNodes().isNotEmpty() }
        runBlocking { Fixture.container.settings.setMiniPlayButton(false) }
        compose.waitUntil(10_000) { compose.onAllNodes(miniPlay).fetchSemanticsNodes().isEmpty() }
        // The song's still there to open.
        assertTrue(compose.onAllNodes(hasText("Prism mini player tone", substring = true)).fetchSemanticsNodes().isNotEmpty())
    }
}
