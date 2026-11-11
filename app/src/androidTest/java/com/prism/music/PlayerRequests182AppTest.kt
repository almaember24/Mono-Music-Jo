package com.prism.music

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.model.Song
import com.prism.music.data.model.formatDuration
import com.prism.music.data.prefs.PanelPlayer
import com.prism.music.data.prefs.PlayerGesture
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.player.CoverFlight
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 1.8.2's player requests on the player itself, with silent tones on the phone for songs (no network). */
@RunWith(AndroidJUnit4::class)
class PlayerRequests182AppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val tones = ArrayList<android.net.Uri>()
    private var wifiOff = false
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val player get() = Fixture.container.player
    private val settings get() = Fixture.container.settings

    @After fun cleanUp() {
        if (wifiOff) shell("svc wifi enable")
        runCatching { instrumentation.runOnMainSync { player.stop() } }
        tones.forEach { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
    }

    /** [seconds]-long tones queued, the one at [start] playing. */
    private fun songs(vararg names: String, seconds: Int = 120, start: Int = 0): List<Song> {
        Fixture.ready()
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        names.forEach { tones += Fixture.tone(it, seconds) }
        Fixture.launch()
        val local = Fixture.container.localMusic
        runBlocking { local.scan() }
        val songs = names.map { n -> local.tracks.value.first { it.song.title == n }.song }
        instrumentation.runOnMainSync { player.playQueue(songs, start, QueueSource(QueueKind.OTHER, "Player requests test")) }
        compose.waitUntil(20_000) { player.currentSong.value?.id == songs[start].id }
        return songs
    }

    /** The full player, by the link a widget's cover uses, then paused so Compose goes idle. */
    private fun openPlayer() {
        Fixture.context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("prism://player"))
                .setClass(Fixture.context, PrismActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        Thread.sleep(2_000)
        instrumentation.runOnMainSync { player.setPlaying(false) }
        compose.waitUntil(15_000) { shown("playerDeck") }
    }

    private fun shown(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun described(what: String) = compose.onAllNodes(hasContentDescription(what), useUnmergedTree = true)
    private fun tapDescribed(what: String) { described(what).onFirst().performClick(); settle() }
    private fun settle(ms: Long = 800) { compose.waitForIdle(); Thread.sleep(ms) }
    private fun back() { androidx.test.uiautomator.UiDevice.getInstance(instrumentation).pressBack(); settle() }
    private fun deck(): SemanticsNodeInteraction = compose.onAllNodes(hasTestTag("playerDeck")).onFirst()
    private fun bounds(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
    private fun playerOpen() = shown("playerDeck")

    /** #351: a 30 s blend is planned at 30 s, the ending is ready for it, and the next song takes over. */
    @Test fun aCrossfadeRunsThirtySeconds() {
        runBlocking {
            settings.setMix(true)
            settings.setMixAuto(false)
            settings.setMixSeconds(30)
        }
        val (a, b) = songs("Prism long blend A", "Prism long blend B", seconds = 100)
        instrumentation.runOnMainSync { player.seekTo(40_000) }
        compose.waitUntil(20_000) { Fixture.container.mixState.mark.value?.let { it.songId == a.id && it.lengthMs == 30_000L } == true }
        // The blend starts 30 s before the end: 8 s from here, so the second song's on its way in below.
        instrumentation.runOnMainSync { player.seekTo(62_000) }
        compose.waitUntil(45_000) { player.currentSong.value?.id == b.id && Fixture.container.mixState.into.value?.songId == b.id }
        val length = Fixture.container.mixState.into.value?.lengthMs ?: 0L
        // A little less when it was put off to get the two in step (it still ends where it would have).
        assertTrue("the blend's length ($length ms)", length in 26_000L..30_000L)
    }

    /** #282: off, a swipe on the controls below the lyrics does nothing; on, it does what's set. On the lyrics it never does. */
    @Test fun gesturesWorkBelowTheLyricsWhenAsked() {
        runBlocking {
            settings.setPanelPlayer(PanelPlayer.ALWAYS)
            settings.setGesture("cover_swipe_left", PlayerGesture.NEXT)
        }
        val (a, b) = songs("Prism gesture A", "Prism gesture B")
        openPlayer()
        tapDescribed(tr(R.string.lyrics))
        assertTrue("the lyrics are open", described(tr(R.string.close_lyrics)).fetchSemanticsNodes().isNotEmpty())

        // Along the row of play and skip (their buttons take taps, not drags).
        val prev = described(tr(R.string.widget_previous)).fetchSemanticsNodes().first().boundsInRoot
        val top = bounds("playerDeck").top
        val width = bounds("playerDeck").width
        fun swipeLeft(y: Float) {
            deck().performTouchInput { swipe(Offset(width * 0.9f, y), Offset(width * 0.1f, y), 300) }
            settle()
        }
        swipeLeft(prev.center.y - top)
        assertEquals("off: as before", a.id, player.currentSong.value?.id)

        runBlocking { settings.setGesturesUnderLyrics(true) }
        settle()
        // Above the controls, on the lyrics: still nothing.
        swipeLeft(-200f)
        assertEquals("on the lyrics: never", a.id, player.currentSong.value?.id)
        swipeLeft(prev.center.y - top)
        compose.waitUntil(5_000) { player.currentSong.value?.id == b.id }
        assertTrue("the lyrics stay open", described(tr(R.string.close_lyrics)).fetchSemanticsNodes().isNotEmpty())
    }

    /** #280: off, Back only leaves the queue and the player opens on its cover; on, it leaves the player and comes back there. */
    @Test fun thePlayerCanReopenOnTheLastPage() {
        songs("Prism page tone")
        openPlayer()
        tapDescribed(tr(R.string.up_next))
        assertTrue("the queue's open", compose.onAllNodes(hasText(tr(R.string.queue))).fetchSemanticsNodes().isNotEmpty())
        back()
        assertTrue("off: Back leaves the queue only", playerOpen())
        assertTrue("off: on the cover", compose.onAllNodes(hasText(tr(R.string.queue))).fetchSemanticsNodes().isEmpty())

        runBlocking { settings.setPlayerRemembersPage(true) }
        settle()
        tapDescribed(tr(R.string.up_next))
        back()
        compose.waitUntil(5_000) { !playerOpen() }
        openPlayer()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(tr(R.string.queue))).fetchSemanticsNodes().isNotEmpty() }

        // And the same for the lyrics, closed with a button first.
        back()
        compose.waitUntil(5_000) { !playerOpen() }
        openPlayer()
        tapDescribed(tr(R.string.lyrics))
        back()
        compose.waitUntil(5_000) { !playerOpen() }
        openPlayer()
        compose.waitUntil(5_000) { described(tr(R.string.close_lyrics)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** #223: with the option on the cover flies back into the mini player as the player closes; without it, it never does. */
    @Test fun theCoverFliesBackWhenAsked() {
        runBlocking { settings.setCoverFlight(true) }
        songs("Prism flight tone")
        compose.waitUntil(15_000) { shown("miniPlayer") }
        settle()
        openPlayer()
        compose.waitUntil(5_000) { !CoverFlight.flying && CoverFlight.player != null }

        // On its own thread: pressBack waits up to a second for the screen to change, longer than the flight.
        fun pressBack() = Thread { androidx.test.uiautomator.UiDevice.getInstance(instrumentation).pressBack() }.start()
        pressBack()
        // Frames only run while the test waits on Compose, so what happens is watched from inside its wait.
        compose.waitUntil(5_000) {
            assertFalse("off: the player slides away, as before", CoverFlight.returning)
            !playerOpen()
        }

        runBlocking { settings.setCoverFlightBack(true) }
        settle()
        openPlayer()
        compose.waitUntil(5_000) { !CoverFlight.flying && CoverFlight.player != null }
        pressBack()
        compose.waitUntil(5_000) { CoverFlight.returning }
        compose.waitUntil(5_000) { !playerOpen() && !CoverFlight.returning && CoverFlight.player == null }
        assertTrue("the mini player's back", shown("miniPlayer"))
    }

    /** #293: over a full-bleed picture the controls take less room, and the title goes down with them. */
    @Test fun aFullBleedPictureCanHaveMoreRoom() {
        runBlocking {
            settings.setFullBleedArtwork(true)
            settings.setHideVolumeBar(true)
        }
        songs("Prism room tone")
        openPlayer()
        settle()
        val deckBefore = bounds("playerDeck").height
        val titleBefore = bounds("playerTitle").top
        runBlocking { settings.setBleedRoomy(true) }
        settle(1_200)
        val deckAfter = bounds("playerDeck").height
        val titleAfter = bounds("playerTitle").top
        assertTrue("a smaller deck ($deckBefore to $deckAfter)", deckAfter < deckBefore * 0.9f)
        assertTrue("the title's lower ($titleBefore to $titleAfter)", titleAfter > titleBefore)
        // The title stays clear of the controls.
        assertTrue("above the deck", bounds("playerTitle").bottom <= bounds("playerDeck").top + 1f)
    }

    /** #331: song lengths when asked for. */
    @Test fun theQueueCanShowSongLengths() {
        songs("Prism length A", "Prism length B", seconds = 125)
        openPlayer()
        tapDescribed(tr(R.string.up_next))
        val length = formatDuration(125)
        // The player's own times show too, so it's the rows that are counted.
        val before = compose.onAllNodes(hasText(length), useUnmergedTree = true).fetchSemanticsNodes().size
        runBlocking { settings.setQueueLengths(true) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(length), useUnmergedTree = true).fetchSemanticsNodes().size >= before + 2 }
    }

    /** #294: "Tidier queue" takes off the × and the Queue button; a song can still go by its menu, and the queue can still close. */
    @Test fun theQueueCanBeTidier() {
        runBlocking { settings.setPanelPlayer(PanelPlayer.ALWAYS) }
        songs("Prism tidy A", "Prism tidy B", "Prism tidy C")
        openPlayer()
        tapDescribed(tr(R.string.up_next))
        assertTrue("off: each song's ×", described(tr(R.string.remove)).fetchSemanticsNodes().isNotEmpty())
        assertTrue("off: the Queue button", described(tr(R.string.up_next)).fetchSemanticsNodes().isNotEmpty())

        runBlocking { settings.setQueueTidy(true) }
        compose.waitUntil(5_000) { described(tr(R.string.remove)).fetchSemanticsNodes().isEmpty() }
        assertTrue("on: no Queue button", described(tr(R.string.up_next)).fetchSemanticsNodes().isEmpty())
        back()
        assertTrue("Back still leaves the queue", compose.onAllNodes(hasText(tr(R.string.queue))).fetchSemanticsNodes().isEmpty())
        assertTrue("the Queue button's back on the cover", described(tr(R.string.up_next)).fetchSemanticsNodes().isNotEmpty())
    }

    /** #294, #331: played songs get handles when asked for, moving among themselves and never past what's playing. */
    @Test fun playedSongsCanBeMovedWhenAsked() {
        // The whole screen for the list, so all four rows are there at once.
        runBlocking { settings.setPanelPlayer(PanelPlayer.HIDDEN) }
        val all = songs("Prism played A", "Prism played B", "Prism played C", "Prism played D", start = 2)
        openPlayer()
        tapDescribed(tr(R.string.up_next))
        // The queue opens at what's playing, with the played songs above it.
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(0)
        settle()
        // One handle: the song still to come.
        compose.waitUntil(5_000) { described(tr(R.string.drag_to_reorder)).fetchSemanticsNodes().size == 1 }

        runBlocking { settings.setQueuePlayedMoves(true) }
        compose.waitUntil(5_000) { described(tr(R.string.drag_to_reorder)).fetchSemanticsNodes().size == 3 }
        fun order() = player.queue.value.map { it.song.id }

        // The first played song, down by a row: it trades places with the second.
        val density = Fixture.context.resources.displayMetrics.density
        fun drag(handle: Int, rows: Int) {
            described(tr(R.string.drag_to_reorder))[handle].performTouchInput {
                down(center)
                val step = rows * 60 * density / 10
                repeat(10) { moveBy(Offset(0f, step)); advanceEventTime(30) }
                up()
            }
            settle()
        }
        drag(0, 1)
        assertEquals(listOf(all[1].id, all[0].id, all[2].id, all[3].id), order())
        assertEquals("what's playing stays", all[2].id, player.currentSong.value?.id)

        // Dragged far past what's playing, it stops at the end of the played songs.
        drag(0, 4)
        assertEquals(listOf(all[0].id, all[1].id, all[2].id, all[3].id), order())
        assertEquals(all[2].id, player.currentSong.value?.id)
    }

    /**
     * #281: on mobile data, with Data saver and the option on, a song's video is unavailable, and comes back with the option
     * off. Wi-Fi is turned off on the emulator for it; a device with no mobile data can't run this.
     */
    @Test fun videosAreOffOnMobileDataWhenAsked() {
        shell("svc wifi disable")
        wifiOff = true
        Fixture.ready()
        val network = Fixture.container.network
        val until = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < until && !(network.online.value && network.metered.value)) Thread.sleep(200)
        assumeTrue("needs mobile data", network.online.value && network.metered.value)
        val (_, second) = songs("Prism data tone 1", "Prism data tone 2")
        // A local file has no video of its own: this stands in for one that's known, without the network.
        instrumentation.runOnMainSync {
            Fixture.container.queue.counterparts[second.id] = "dQw4w9WgXcQ"
            player.next()
        }
        compose.waitUntil(10_000) { player.currentSong.value?.id == second.id && player.videoAvailable.value }

        runBlocking { settings.setDataSaver(true); settings.setNoVideoOnData(true) }
        compose.waitUntil(10_000) { !player.videoAvailable.value && player.videoOffOnData.value }

        // Asked for anyway (a gesture, the web remote): it stays the song.
        instrumentation.runOnMainSync { player.setVideoMode(true) }
        settle()
        assertFalse(player.videoMode.value)

        runBlocking { settings.setNoVideoOnData(false) }
        compose.waitUntil(10_000) { player.videoAvailable.value && !player.videoOffOnData.value }
    }
}
