package com.prism.music

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.Song
import com.prism.music.playback.PlaybackServices
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.playback.VideoOnData
import com.prism.music.playback.videoModeId
import com.prism.music.ui.components.Playable
import com.prism.music.ui.components.playPlayable
import com.prism.music.ui.player.CoverFlight
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** What the code review of 1.8.2 found in the player and the queue, each failing before its fix. Silent tones or songs with no stream. */
@RunWith(AndroidJUnit4::class)
class CodeReview182AppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = Fixture.container
    private val player get() = Fixture.container.player
    private val settings get() = Fixture.container.settings
    private val tones = ArrayList<android.net.Uri>()
    private val released = CountDownLatch(1)

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun <T> onMain(read: () -> T): T { var out: T? = null; main { out = read() }; @Suppress("UNCHECKED_CAST") return out as T }
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).close()

    @After fun cleanUp() {
        released.countDown()
        RecordedNetwork.answering = null
        runCatching { main { player.stop() } }
        tones.forEach { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
    }

    private fun start() {
        Fixture.ready()
        shell("cmd media_session volume --stream 3 --set 0")
        Fixture.launch()
        compose.waitUntil(20_000) { onMain { player.player != null } }
    }

    /** [names] as tones on the phone, the first one playing. */
    private fun songs(vararg names: String, seconds: Int = 120): List<Song> {
        Fixture.ready()
        shell("cmd media_session volume --stream 3 --set 0")
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        names.forEach { tones += Fixture.tone(it, seconds) }
        Fixture.launch()
        val local = c.localMusic
        runBlocking { local.scan() }
        val songs = names.map { n -> local.tracks.value.first { it.song.title == n }.song }
        main { player.playQueue(songs, 0, QueueSource(QueueKind.OTHER, "Code review test")) }
        compose.waitUntil(20_000) { player.currentSong.value?.id == songs[0].id }
        return songs
    }

    // ---- "No videos on mobile data" (#281) and a music video tapped in Search.

    @Test fun aTappedVideoIsOnlyItsSoundOnMobileData() {
        start()
        // On mobile data, whatever this phone is on (not every test phone has any). Each test has a fresh app.
        listOf("_online", "_metered").forEach { name ->
            val field = c.network.javaClass.getDeclaredField(name).apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") (field.get(c.network) as kotlinx.coroutines.flow.MutableStateFlow<Boolean>).value = true
        }
        runBlocking { settings.setDataSaver(true); settings.setNoVideoOnData(true) }
        compose.waitUntil(5_000) { VideoOnData.off(c) }
        var picture = false
        main {
            player.player!!.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { if (mediaItem?.videoModeId != null) picture = true }
            })
        }
        val video = Song("dQw4w9WgXcQ", "Some video", listOf(ArtistRef("Someone")), durationSec = 200, isVideo = true)
        main { player.playQueue(listOf(video), 0, QueueSource(QueueKind.SINGLE, "Autoplay", seedId = video.id)) }
        compose.waitUntil(10_000) { player.currentSong.value?.id == video.id }
        assertFalse("its picture was asked for over mobile data", picture)
    }

    // ---- A skip while a stuck song is about to try again.

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun aSkipBeforeAStuckSongsRetryKeepsTheNextSongAtItsStart() {
        val (_, second) = songs("Prism retry tone one", "Prism retry tone two")
        compose.waitUntil(20_000) { PlaybackServices.failForTest != null && onMain { player.position } > 1_000 }
        main { player.seekTo(60_000) }
        compose.waitUntil(5_000) { onMain { player.position } >= 60_000 }
        main { player.player!!.stop() }
        compose.waitUntil(5_000) { onMain { player.player!!.playbackState == Player.STATE_IDLE } }
        main {
            PlaybackServices.failForTest!!.invoke(
                PlaybackException(
                    "Unexpected runtime error",
                    androidx.media3.common.util.StuckPlayerException(androidx.media3.common.util.StuckPlayerException.STUCK_PLAYING_NO_PROGRESS, 10_000),
                    PlaybackException.ERROR_CODE_TIMEOUT,
                ),
            )
            // Within the moment it waits before trying again.
            player.next()
        }
        Thread.sleep(1_500)
        assertEquals(second.id, onMain { player.player!!.currentMediaItem?.mediaId })
        val at = onMain { player.position }
        assertTrue("the next song went to the last one's place ($at ms)", at < 30_000)
    }

    // ---- A queue started while the last one's Autoplay is still looking.

    private fun song(id: String) = Song(id, "Song $id", listOf(ArtistRef("Artist $id")), durationSec = 190)
    private fun suggestions(prefix: String, n: Int) = (1..n).map { song("$prefix${it.toString().padStart(4, '0')}") }
    private fun queue(): List<String> = onMain { val p = player.player!!; (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId } }

    private fun row(s: Song) = """{"playlistPanelVideoRenderer":{"videoId":"${s.id}","title":{"runs":[{"text":"${s.title}"}]},
        "longBylineText":{"runs":[{"text":"${s.primaryArtist}"}]},"lengthText":{"runs":[{"text":"3:10"}]}}}"""

    private fun page(list: List<Song>) =
        """{"contents":{"playlistPanelRenderer":{"contents":[${list.joinToString(",", transform = ::row)}]}}}"""

    /** A's radio takes as long as the test likes; B's comes at once. */
    private fun radioWaitsForA(a: Song, b: Song) {
        val asked = CountDownLatch(1)
        RecordedNetwork.answering = { url, body ->
            val seed = Regex("\"playlistId\":\"RDAMVM([^\"]+)\"").find(body)?.groupValues?.get(1)
            when {
                "/next" !in url || seed == null -> null
                seed == a.id -> { asked.countDown(); released.await(30, TimeUnit.SECONDS); page(suggestions("aaaa", 10)) }
                seed == b.id -> page(suggestions("bbbb", 10))
                else -> null
            }
        }
        main { player.playQueue(listOf(a), 0, QueueSource(QueueKind.SINGLE, "A", seedId = a.id)) }
        assertTrue("A's radio wasn't asked for", asked.await(15, TimeUnit.SECONDS))
    }

    @Test fun aNewQueueGetsItsSuggestionsAndNotTheOldOnes() {
        start()
        val a = song("seedAAAA")
        val b = song("seedBBBB")
        radioWaitsForA(a, b)
        main { player.playQueue(listOf(b), 0, QueueSource(QueueKind.SINGLE, "B", seedId = b.id)) }
        // Without waiting for A's to come back: until then the old way only held B's back, and B would end the music.
        compose.waitUntil(10_000) { queue().any { it.startsWith("bbbb") } }
        released.countDown()
        Thread.sleep(1_500)
        assertTrue("A's suggestions joined B's queue: ${queue()}", queue().none { it.startsWith("aaaa") })
        compose.waitUntil(5_000) { !c.queue.autoplayLoading }
    }

    /** The same source object (a page's own, played again) can't tell the old top-up that its queue has gone. */
    @Test fun theSameSourceStartedAgainDoesntTakeTheOldSuggestions() {
        start()
        val a = song("seedAAAA")
        val b = song("seedBBBB")
        val source = QueueSource(QueueKind.PLAYLIST, "Mine")
        val asked = CountDownLatch(1)
        RecordedNetwork.answering = { url, body ->
            val seed = Regex("\"playlistId\":\"RDAMVM([^\"]+)\"").find(body)?.groupValues?.get(1)
            when {
                "/next" !in url || seed == null -> null
                seed == a.id -> { asked.countDown(); released.await(30, TimeUnit.SECONDS); page(suggestions("aaaa", 10)) }
                seed == b.id -> page(suggestions("bbbb", 10))
                else -> null
            }
        }
        main { player.playQueue(listOf(a), 0, source) }
        assertTrue(asked.await(15, TimeUnit.SECONDS))
        main { player.playQueue(listOf(b), 0, source) }
        compose.waitUntil(10_000) { queue().any { it.startsWith("bbbb") } }
        released.countDown()
        Thread.sleep(1_500)
        assertTrue("A's suggestions joined the new queue: ${queue()}", queue().none { it.startsWith("aaaa") })
    }

    // ---- A long press on a list that won't load, while another list is still filling.

    @Test fun aListThatWontLoadLeavesTheOneFillingToFinish() {
        start()
        val first = Fixture.songs(10, "First").map { it.copy(id = "First${it.id}".replace(" ", "")) }
        val second = Fixture.songs(50, "Second").map { it.copy(id = "Second${it.id}".replace(" ", "")) }
        val gate = CompletableDeferred<Unit>()
        var pages = 0
        main {
            player.playQueue(first, 0, QueueSource(QueueKind.PLAYLIST, "Long list", originId = "PLlong"), rest = {
                if (pages++ == 0) { gate.await(); second } else null
            })
        }
        compose.waitUntil(5_000) { queue().size == 10 }
        // Nothing recorded for this one: its first page comes back as a failure.
        val broken = PlaylistItem("PLbrokenlist", "Broken list", "", null)
        main { playPlayable(c, Fixture.context, Playable.Of(broken), broken.title, shuffle = false) }
        Thread.sleep(3_000)
        gate.complete(Unit)
        compose.waitUntil(10_000) { queue().size == 60 }
        compose.waitUntil(5_000) { !c.queue.filling }
    }

    // ---- The player opened again as it leaves.

    private fun shown(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun deckTop() = compose.onAllNodes(hasTestTag("playerDeck"), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot.top

    private fun link() = Fixture.context.startActivity(
        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("prism://player"))
            .setClass(Fixture.context, PrismActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )

    private fun openPlayer() {
        link()
        Thread.sleep(2_000)
        main { player.setPlaying(false) }
        compose.waitUntil(15_000) { shown("playerDeck") }
        compose.waitForIdle()
    }

    /** On its own thread: pressBack waits up to a second for the screen to change, longer than what's being watched. */
    private fun pressBack() = Thread { androidx.test.uiautomator.UiDevice.getInstance(instrumentation).pressBack() }.start()

    /** It must be where it was, and close as usual. */
    private fun backInPlace(top: Float) {
        compose.waitUntil(8_000) { shown("playerDeck") && abs(deckTop() - top) < 3f }
        compose.waitForIdle()
        Thread.sleep(800)
        compose.waitForIdle()
        assertTrue("it moved after settling", abs(deckTop() - top) < 3f)
        pressBack()
        compose.waitUntil(8_000) { !shown("playerDeck") }
    }

    @Test fun pulledDownWithTheCoverFlyingBackThenOpenedAgainItsInPlace() {
        runBlocking { settings.setCoverFlight(true); settings.setCoverFlightBack(true) }
        songs("Prism reopen flight tone")
        compose.waitUntil(15_000) { shown("miniPlayer") }
        openPlayer()
        compose.waitUntil(5_000) { !CoverFlight.flying && CoverFlight.player != null }
        Thread.sleep(600)
        compose.waitForIdle()
        val top = deckTop()
        compose.onAllNodes(hasTestTag("playerDeck")).onFirst().performTouchInput { swipe(Offset(width / 2f, -400f), Offset(width / 2f, 500f), 300) }
        compose.waitUntil(5_000) { CoverFlight.returning }
        // By its link (the player, still fading, lies over the mini player). Delivered while the frames wait: they only run
        // while the test waits on Compose.
        link()
        Thread.sleep(600)
        backInPlace(top)
    }

    @Test fun slidingClosedThenOpenedAgainItsInPlace() {
        songs("Prism reopen slide tone")
        compose.waitUntil(15_000) { shown("miniPlayer") }
        openPlayer()
        Thread.sleep(600)
        compose.waitForIdle()
        val top = deckTop()
        // By the clock, as anything that reads the screen first waits for the exit to finish: 340 ms of sliding away, then
        // 360 of leaving, and it's opened again in that second part.
        compose.mainClock.autoAdvance = false
        pressBack()
        Thread.sleep(800)
        compose.mainClock.advanceTimeBy(480)
        link()
        Thread.sleep(600)
        compose.mainClock.autoAdvance = true
        backInPlace(top)
    }
}
