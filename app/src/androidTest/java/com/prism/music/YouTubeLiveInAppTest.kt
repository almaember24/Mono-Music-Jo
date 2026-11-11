package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prism.music.data.imports.ImportSource
import com.prism.music.data.imports.YouTubeLive
import com.prism.music.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * YouTube Music (live) in the app (1.8.1), offline: two reads of the History page handed in through the source's test
 * hook (no YouTube, no sign-in). Turning it on reads the starting point at once (nothing from before comes in); the next
 * read brings the song newly at the top, matched at once; then "Count history from" lists every source (the ones not
 * brought in greyed, with Bring it in / Turn on), and switching YouTube Music (live) on makes Replay count its play.
 */
@RunWith(AndroidJUnit4::class)
class YouTubeLiveInAppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val c get() = Fixture.container
    private var page: List<Pair<String, List<Song>>> = emptyList()
    private var now = System.currentTimeMillis() - 6 * 3_600_000L

    @Before fun start() {
        Fixture.ready()
        runBlocking { ImportSource.entries.forEach { c.imports.remove(it) } }
        c.imports.youtubeLive.forTest = YouTubeLive.TestHooks(history = { page }, now = { now })
    }

    @After fun clean() {
        c.imports.setYouTubeLive(false)
        c.imports.youtubeLive.forTest = null
        runBlocking { ImportSource.entries.forEach { c.imports.remove(it) } }
    }

    private fun livePlays() = runBlocking { c.db.imports().summaries().firstOrNull { it.origin == ImportSource.YOUTUBE_LIVE.key }?.plays ?: 0 }

    @Test fun twoReadsBringWhatsNewAndCountHistoryFromListsEverySource() {
        val songs = Fixture.songs(4, "Live test")
        page = listOf("Today" to songs.drop(1))
        c.imports.setYouTubeLive(true)
        compose.waitUntil(10_000) { c.imports.youtubeLive.lastCheck.value != null }
        assertEquals("the starting point brings nothing from before", 0, livePlays())

        now += 3 * 3_600_000L
        page = listOf("Today" to songs)
        assertEquals("the song newly at the top", 1, runBlocking { c.imports.runYouTubeLive() })
        val matched = runBlocking { c.db.imports().match(YouTubeLive.keyOf(songs[0])) }
        assertEquals("matched at once", songs[0].id, matched?.matchedId)
        val prismOnly = runBlocking { c.imports.replay().totalMs(0, Long.MAX_VALUE) }

        Fixture.launch()
        compose.openSettings()
        compose.clickWhenFound(hasText("Recommendations & Replay") and hasClickAction())
        seeText("Count history from")
        // Every source: the ones not brought in greyed with the way to them.
        seeText("Bring it in")
        seeText("Turn on")
        seeText("Last.fm")
        compose.clickWhenFound(hasText("YouTube Music (live)") and hasClickAction())
        compose.waitUntil(5_000) { ImportSource.YOUTUBE_LIVE in c.imports.counted.value }
        assertEquals(prismOnly + songs[0].durationSec * 1000L, runBlocking { c.imports.replay().totalMs(0, Long.MAX_VALUE) })
        assertTrue(c.imports.youtubeLive.on.value)
    }

    /**
     * Both live histories where people look for them (owner, 1.8.1): Devices & connections has a YouTube Music row that
     * opens Bring your history, whose "Keep it in step" lists Spotify (live) too, opening the Spotify page.
     */
    @Test fun bothLiveHistoriesAreFoundFromConnectionsAndKeepItInStep() {
        Fixture.launch()
        compose.openSettings()
        compose.clickWhenFound(hasText("Devices & connections") and hasClickAction())
        seeText("Sign in to keep your history in step")
        // By its line: the Sharing cards below are also named YouTube Music.
        compose.clickWhenFound(hasText("Sign in to keep your history in step") and hasClickAction())
        seeText("Keep it in step")
        seeText("Spotify (live)")
        seeText("Connect Spotify to use this")
        compose.clickWhenFound(hasText("Spotify (live)") and hasClickAction())
        seeText("Create app")
    }

    /** Waits for [text], scrolling the page's lists to it (as BringHistoryTest's). */
    private fun seeText(text: String, timeoutMs: Long = 15_000) {
        val matcher = hasText(text, substring = true, ignoreCase = true)
        val end = System.currentTimeMillis() + timeoutMs
        while (compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()) {
            if (System.currentTimeMillis() > end) {
                val shown = compose.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes()
                    .mapNotNull { it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.joinToString() }
                throw AssertionError("Not found: $text. On screen: ${shown.joinToString(" | ").take(1500)}")
            }
            compose.onAllNodes(androidx.compose.ui.test.hasScrollToNodeAction()).fetchSemanticsNodes().indices.forEach { i ->
                runCatching { compose.onAllNodes(androidx.compose.ui.test.hasScrollToNodeAction())[i].performScrollToNode(matcher) }
            }
            compose.waitForIdle()
            Thread.sleep(200)
        }
    }
}
