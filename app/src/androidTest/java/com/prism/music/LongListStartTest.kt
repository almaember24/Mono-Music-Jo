package com.prism.music

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.ExplicitContent
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.components.Playable
import com.prism.music.ui.components.playPlayable
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

/**
 * #356: Shuffle or Play on a long playlist did nothing for seconds, while every page loaded. It now starts from the
 * first page and the rest follows behind the songs playing. These songs have no stream, so the player moves on through
 * them; nothing here depends on which one is current.
 */
@RunWith(AndroidJUnit4::class)
class LongListStartTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = Fixture.container
    private val source = QueueSource(QueueKind.PLAYLIST, "Long list", originId = "PLlong")

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun <T> onMain(read: () -> T): T { var out: T? = null; main { out = read() }; @Suppress("UNCHECKED_CAST") return out as T }
    private fun count() = onMain { c.player.player?.mediaItemCount ?: 0 }
    private fun inPlayOrder(): List<String> = onMain {
        val p = c.player.player!!
        val t = p.currentTimeline
        val out = ArrayList<String>()
        var i = t.getFirstWindowIndex(p.shuffleModeEnabled)
        while (i != androidx.media3.common.C.INDEX_UNSET && out.size < t.windowCount) {
            out += p.getMediaItemAt(i).mediaId
            i = t.getNextWindowIndex(i, androidx.media3.common.Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
        }
        out
    }
    private fun tones(n: Int, prefix: String) = Fixture.songs(n, prefix).map { it.copy(id = "$prefix${it.id}".replace(" ", "")) }

    @After fun cleanUp() {
        RecordedNetwork.answering = null
        runCatching { main { c.player.stop() } }
        runBlocking { c.settings.setExplicitContent(ExplicitContent.SHOW) }
    }

    /** [pages] handed out one at a time, each waiting for its [gate]. */
    private fun rest(vararg pages: Pair<CompletableDeferred<Unit>, List<Song>>): suspend () -> List<Song>? {
        val left = pages.toMutableList()
        return rest@{
            val (gate, songs) = left.removeFirstOrNull() ?: return@rest null
            gate.await()
            songs
        }
    }

    private fun start() { Fixture.ready(); Fixture.launch(); compose.waitUntil(20_000) { onMain { c.player.player != null } } }

    @Test fun itStartsBeforeTheRestIsThere() {
        start()
        val first = tones(100, "First")
        val second = tones(150, "Second")
        val gate = CompletableDeferred<Unit>()
        main { c.player.playQueue(first, 0, source, rest = rest(gate to second)) }
        compose.waitUntil(5_000) { count() == 100 }
        assertTrue("autoplay should wait for the rest", c.queue.filling)
        gate.complete(Unit)
        compose.waitUntil(10_000) { count() == 250 }
        compose.waitUntil(5_000) { !c.queue.filling }
        // In its order, the queue still saying where it's from.
        assertEquals((first + second).map { it.id }, onMain { (0 until c.player.player!!.mediaItemCount).map { c.player.player!!.getMediaItemAt(it).mediaId } })
        assertEquals(source, c.queue.source.value)
    }

    @Test fun shuffleMixesTheWholeList() {
        start()
        val first = tones(100, "First")
        val second = tones(300, "Second")
        val gate = CompletableDeferred<Unit>()
        main { c.player.playQueue(first, 0, source, shuffle = true, rest = rest(gate to second)) }
        compose.waitUntil(5_000) { count() == 100 }
        val playing = onMain { c.player.player!!.currentMediaItem!!.mediaId }
        assertTrue("it starts from the first page", first.any { it.id == playing })
        gate.complete(Unit)
        compose.waitUntil(10_000) { count() == 400 }
        compose.waitUntil(5_000) { inPlayOrder().size == 400 }
        val order = inPlayOrder()
        assertEquals((first + second).map { it.id }.toSet(), order.toSet())
        // The later page all through what's left, not after it: the next 30 songs nearly always hold some of each.
        val at = order.indexOf(onMain { c.player.player!!.currentMediaItem!!.mediaId })
        val next = order.drop(at + 1).take(30)
        assertTrue("the next songs are only the first page's: $next", next.any { it.startsWith("Second") })
        assertTrue(onMain { c.player.player!!.shuffleModeEnabled })
        assertEquals(source, c.queue.source.value)
    }

    @Test fun theExplicitFilterGoesOverTheRestToo() {
        start()
        runBlocking { c.settings.setExplicitContent(ExplicitContent.SKIP) }
        compose.waitUntil(5_000) { c.settings.current.behavior.explicitContent == ExplicitContent.SKIP }
        val first = tones(5, "First")
        val clean = tones(3, "Clean")
        val rude = tones(3, "Rude").map { it.copy(explicit = true) }
        val gate = CompletableDeferred<Unit>()
        main { c.player.playQueue(first, 0, source, rest = rest(gate to (rude + clean + first))) }
        compose.waitUntil(5_000) { count() == 5 }
        gate.complete(Unit)
        // The first page's songs coming again aren't added twice either.
        compose.waitUntil(10_000) { count() == 8 }
        compose.waitUntil(5_000) { !c.queue.filling }
        assertFalse(onMain { (0 until c.player.player!!.mediaItemCount).any { c.player.player!!.getMediaItemAt(it).mediaId.startsWith("Rude") } })
    }

    @Test fun playingSomethingElseLeavesTheRestBehind() {
        start()
        val gate = CompletableDeferred<Unit>()
        val other = tones(4, "Other")
        main { c.player.playQueue(tones(10, "First"), 0, source, rest = rest(gate to tones(50, "Second"))) }
        compose.waitUntil(5_000) { count() == 10 }
        val otherSource = QueueSource(QueueKind.ALBUM, "Other")
        main { c.player.playQueue(other, 0, otherSource) }
        compose.waitUntil(5_000) { count() == 4 }
        assertFalse(c.queue.filling)
        gate.complete(Unit)
        Thread.sleep(1_000)
        assertEquals(4, count())
        assertEquals(otherSource, c.queue.source.value)
    }

    @Test fun stoppingLeavesTheRestBehind() {
        start()
        val gate = CompletableDeferred<Unit>()
        main { c.player.playQueue(tones(10, "First"), 0, source, rest = rest(gate to tones(50, "Second"))) }
        compose.waitUntil(5_000) { count() == 10 }
        main { c.player.stop() }
        assertFalse(c.queue.filling)
        gate.complete(Unit)
        Thread.sleep(1_000)
        assertEquals(0, count())
    }

    // ---- A playlist's long-press Play and Shuffle, through its pages as YouTube serves them.

    private fun row(id: String) = """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"$id"},
        "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song $id"}]}}},
        {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Some Artist"}]}}}]}}"""

    private fun firstPage(ids: List<String>, next: String) =
        """{"contents":{"musicPlaylistShelfRenderer":{"contents":[${ids.joinToString(",", transform = ::row)}],
        "continuations":[{"nextContinuationData":{"continuation":"$next"}}]}}}"""

    private fun laterPage(ids: List<String>) =
        """{"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[${ids.joinToString(",", transform = ::row)}]}}}"""

    private val item = PlaylistItem("PLtestlong", "Long playlist", "", null)
    private fun ids(prefix: String, n: Int) = (1..n).map { "$prefix${it.toString().padStart(4, '0')}" }

    @Test fun aLongPressStartsOnTheFirstPage() {
        start()
        val pageTwo = CountDownLatch(1)
        RecordedNetwork.answering = { _, body ->
            when {
                "VLPLtestlong" in body -> firstPage(ids("aaaaaaa", 100), "second")
                "second" in body -> { pageTwo.await(20, TimeUnit.SECONDS); laterPage(ids("bbbbbbb", 200)) }
                else -> null
            }
        }
        main { playPlayable(c, Fixture.context, Playable.Of(item), item.title, shuffle = true) }
        // Going while the second page hasn't come.
        compose.waitUntil(10_000) { count() == 100 }
        assertEquals(item.title, c.queue.source.value.title)
        assertEquals("PLtestlong", c.queue.source.value.originId)
        assertTrue(onMain { c.player.player!!.isPlaying || c.player.player!!.playWhenReady })
        pageTwo.countDown()
        compose.waitUntil(10_000) { count() == 300 }
        assertEquals(QueueKind.PLAYLIST, c.queue.source.value.kind)
    }

    @Test fun somethingPlayedWhileItLoadsWins() {
        start()
        val pageOne = CountDownLatch(1)
        RecordedNetwork.answering = { _, body ->
            if ("VLPLtestlong" in body) { pageOne.await(20, TimeUnit.SECONDS); firstPage(ids("aaaaaaa", 100), "second") } else null
        }
        main { playPlayable(c, Fixture.context, Playable.Of(item), item.title, shuffle = false) }
        Thread.sleep(300)
        val other = QueueSource(QueueKind.ALBUM, "Other")
        main { c.player.playQueue(tones(3, "Other"), 0, other) }
        compose.waitUntil(5_000) { count() == 3 }
        pageOne.countDown()
        Thread.sleep(1_500)
        assertEquals(3, count())
        assertEquals(other, c.queue.source.value)
    }

    @Test fun itSaysItsLoadingWhenThePageIsSlow() {
        start()
        val pageOne = CountDownLatch(1)
        RecordedNetwork.answering = { _, body ->
            if ("VLPLtestlong" in body) { pageOne.await(20, TimeUnit.SECONDS); firstPage(ids("aaaaaaa", 20), "second") } else null
        }
        val said = tr(R.string.loading_list, item.title)
        val toast = runCatching {
            instrumentation.uiAutomation.executeAndWaitForEvent(
                { main { playPlayable(c, Fixture.context, Playable.Of(item), item.title, shuffle = false) } },
                { e -> e.eventType == android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && e.text.any { it.toString() == said } },
                5_000,
            )
        }
        pageOne.countDown()
        assertTrue("no note said it was loading: ${toast.exceptionOrNull()}", toast.isSuccess)
        compose.waitUntil(10_000) { count() == 20 }
    }
}
