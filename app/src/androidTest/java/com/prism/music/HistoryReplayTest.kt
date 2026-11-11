package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prism.music.data.HistoryPause
import com.prism.music.data.db.PlayEventEntity
import com.prism.music.data.db.toEntity
import com.prism.music.data.imports.ImportSource
import com.prism.music.data.prefs.ReplayGraph
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * History and Replay (1.8.1): pausing history (#296) from Settings and turning it back on from Replay, the play
 * threshold (#263), Replay's graphs by hour and by day (#286, #287), and Home's Replay card over the last 14 days saying
 * it counts Spotify too.
 */
@RunWith(AndroidJUnit4::class)
class HistoryReplayTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val c get() = Fixture.container

    @Before fun start() = Fixture.ready()

    @After fun clean() {
        runBlocking { c.settings.editNow { it.remove(androidx.datastore.preferences.core.longPreferencesKey("history_paused_until")) } }
        c.imports.setCounted(ImportSource.SPOTIFY, false)
    }

    /** A few minutes of [songs] played today and earlier this week (and a little last month), as Prism keeps plays. */
    private fun listened() = runBlocking {
        val songs = Fixture.songs(3, "Replay test")
        songs.forEach { c.db.songs().upsert(it.toEntity(null)) }
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        fun at(d: java.time.LocalDate, h: Int) = d.atTime(h, 10).atZone(zone).toInstant().toEpochMilli().coerceAtMost(System.currentTimeMillis() - 60_000)
        // Today, in the first hour (always in the past), and every day of the week so far.
        c.db.plays().insert(PlayEventEntity(songId = songs[0].id, timestamp = at(today, 0), playedMs = 200_000))
        c.db.plays().insert(PlayEventEntity(songId = songs[1].id, timestamp = at(today, 0) + 1_000, playedMs = 180_000))
        (1..6).forEach { back -> c.db.plays().insert(PlayEventEntity(songId = songs[back % 3].id, timestamp = at(today.minusDays(back.toLong()), 12), playedMs = 120_000L * back)) }
    }

    @Test fun historyPausesAndComesBack() {
        Fixture.launch()
        compose.openSettings()
        compose.clickWhenFound(hasText("Recommendations & Replay") and hasClickAction())
        seeText("Pause history")
        compose.clickWhenFound(hasText("1 hour") and hasClickAction())
        compose.waitUntil(5_000) { HistoryPause.paused(c.settings.current.behavior) }
        val until = c.settings.current.behavior.historyPausedUntil
        assertTrue("about an hour", until - System.currentTimeMillis() in 3_500_000L..3_600_000L)
        seeText("Paused until")

        // When a song counts: 30 seconds, and back to ten.
        compose.clickWhenFound(hasText("30 s") and hasClickAction())
        compose.waitUntil(5_000) { c.settings.current.behavior.playCountSeconds == 30 }
        compose.clickWhenFound(hasText("10 s") and hasClickAction())
        compose.waitUntil(5_000) { c.settings.current.behavior.playCountSeconds == 10 }

        // Replay says history is paused, and turns it back on.
        compose.clickWhenFound((hasText("Replay") or hasContentDescription("Replay")) and hasClickAction())
        seeText("History is paused")
        compose.clickWhenFound(hasText("Turn back on") and hasClickAction())
        compose.waitUntil(5_000) { !HistoryPause.paused(c.settings.current.behavior) }
        assertEquals(0L, c.settings.current.behavior.historyPausedUntil)
    }

    @Test fun pausedUntilTurnedBackOnKeepsNoSearches() {
        runBlocking { c.settings.editNow { it[androidx.datastore.preferences.core.longPreferencesKey("history_paused_until")] = HistoryPause.FOREVER } }
        compose.waitUntil(5_000) { HistoryPause.paused(c.settings.current.behavior) }
        // Search's "Recently opened" isn't written while paused.
        val before = com.prism.music.ui.screens.SearchMemory.opened(Fixture.context)
        com.prism.music.ui.screens.SearchMemory.remember(Fixture.context, com.prism.music.data.model.SongItem(Fixture.songs(1, "Private")[0]))
        assertEquals(before, com.prism.music.ui.screens.SearchMemory.opened(Fixture.context))
        Fixture.launch()
        compose.clickWhenFound((hasText("Replay") or hasContentDescription("Replay")) and hasClickAction())
        seeText("until you turn it back on")
    }

    @Test fun replayDrawsAWeekByDayAndADayByHour() {
        listened()
        Fixture.launch()
        compose.clickWhenFound((hasText("Replay") or hasContentDescription("Replay")) and hasClickAction())
        compose.clickWhenFound(hasText("This week") and hasClickAction())
        seeText("Listening by day")
        seeText("Busiest day")
        compose.clickWhenFound(hasText("Today") and hasClickAction())
        seeText("Listening by hour")
        seeText("Busiest hour")
    }

    /** The card shows the last days; it counts Spotify when switched on but doesn't say so (owner, 1.8.1: Replay does). */
    @Test fun homesCardShowsTheLastDaysWithoutSayingWhatItCounts() {
        listened()
        c.settings.setReplayGraph(ReplayGraph.DAYS)
        c.imports.setCounted(ImportSource.SPOTIFY, true)
        compose.waitUntil(5_000) { c.settings.current.view.replayGraph == ReplayGraph.DAYS }
        Fixture.launch()
        seeText("Last 14 days")
        assertTrue(compose.onAllNodes(hasText("Includes Spotify", substring = true)).fetchSemanticsNodes().isEmpty())
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
