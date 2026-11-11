package com.prism.music

import com.prism.music.data.HistoryPause
import com.prism.music.data.PlayCount
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.BehaviorPrefs
import com.prism.music.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What Prism keeps of your listening (1.8.1): when a song counts as played (#263, ten seconds as always unless picked),
 * and pausing history (#296) for an hour, a day or until it's turned back on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HistoryRulesTest {
    private val now = 1_800_000_000_000L

    @Test fun todaysRuleIsTheDefault() {
        val b = AppSettings().behavior
        assertEquals("ten seconds, as before 1.8.1", 10, b.playCountSeconds)
        assertEquals(PlayCount.DEFAULT_SEC, b.playCountSeconds)
        assertFalse("YouTube Music is told as a song starts, as before", b.playCountOnAccount)
        assertEquals(0L, b.historyPausedUntil)
        assertFalse(HistoryPause.paused(b, now))
        assertEquals("the account hears of a play at once", 0L, PlayCount.accountAfterMs(b))
        // The old rule exactly: 10 s counts, a moment less doesn't.
        assertTrue(HistoryPause.keepsPlay(b, 10_000, episode = false, now = now))
        assertFalse(HistoryPause.keepsPlay(b, 9_999, episode = false, now = now))
    }

    @Test fun thePlayThreshold() {
        assertTrue("at once: any play at all", PlayCount.counts(1, 0))
        assertFalse("but not none", PlayCount.counts(0, 0))
        assertFalse(PlayCount.counts(4_999, 5))
        assertTrue(PlayCount.counts(5_000, 5))
        assertFalse(PlayCount.counts(59_999, 60))
        assertTrue(PlayCount.counts(60_000, 60))
        // Episodes are never Replay's, whatever the threshold.
        assertFalse(HistoryPause.keepsPlay(BehaviorPrefs(playCountSeconds = 0), 600_000, episode = true, now = now))
        // The account waits for the same only when asked.
        assertEquals(0L, PlayCount.accountAfterMs(BehaviorPrefs(playCountSeconds = 30)))
        assertEquals(30_000L, PlayCount.accountAfterMs(BehaviorPrefs(playCountSeconds = 30, playCountOnAccount = true)))
        assertEquals(0L, PlayCount.accountAfterMs(BehaviorPrefs(playCountSeconds = 0, playCountOnAccount = true)))
        assertEquals(listOf("At once", "5 s", "10 s", "30 s", "1 min"), PlayCount.CHOICES.map { PlayCount.label(it) })
    }

    @Test fun pausingHistory() {
        val hour = HistoryPause.until(HistoryPause.Choice.HOUR, now)
        assertEquals(now + 3_600_000, hour)
        assertTrue(HistoryPause.isPaused(hour, now))
        assertTrue(HistoryPause.isPaused(hour, now + 3_599_999))
        assertFalse("over by itself after the hour", HistoryPause.isPaused(hour, now + 3_600_000))
        assertEquals(now + 24 * 3_600_000L, HistoryPause.until(HistoryPause.Choice.DAY, now))
        assertEquals(HistoryPause.FOREVER, HistoryPause.until(HistoryPause.Choice.UNTIL_ON, now))
        assertTrue("until turned back on: years later too", HistoryPause.isPaused(HistoryPause.FOREVER, now + 10L * 365 * 86_400_000))
        assertEquals(0L, HistoryPause.until(HistoryPause.Choice.OFF, now))

        // What the page shows for a pause.
        assertEquals(HistoryPause.Choice.OFF, HistoryPause.choiceOf(0, now))
        assertEquals(HistoryPause.Choice.HOUR, HistoryPause.choiceOf(hour, now))
        assertEquals(HistoryPause.Choice.DAY, HistoryPause.choiceOf(HistoryPause.until(HistoryPause.Choice.DAY, now), now + 60_000))
        // A day's pause with less than an hour left reads as the hour.
        assertEquals(HistoryPause.Choice.HOUR, HistoryPause.choiceOf(now + 1_000, now))
        assertEquals(HistoryPause.Choice.UNTIL_ON, HistoryPause.choiceOf(HistoryPause.FOREVER, now))
        assertEquals(HistoryPause.Choice.OFF, HistoryPause.choiceOf(now - 1, now))

        // Nothing is kept while paused, however long it played.
        val paused = BehaviorPrefs(historyPausedUntil = hour)
        assertFalse(HistoryPause.keepsPlay(paused, 300_000, episode = false, now = now))
        assertTrue("kept again once the hour is over", HistoryPause.keepsPlay(paused, 300_000, episode = false, now = hour))
    }

    @Test fun theChoicesAreKeptAndNotPartOfALook(): Unit = runBlocking {
        val repo = SettingsRepository(
            RuntimeEnvironment.getApplication(),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
        )
        repo.setHistoryPausedUntil(HistoryPause.FOREVER)
        repo.setPlayCountSeconds(30)
        repo.setPlayCountOnAccount(true)
        val s = withTimeout(5_000) { repo.flow.first { it.behavior.playCountOnAccount && it.behavior.playCountSeconds == 30 && it.behavior.historyPausedUntil == HistoryPause.FOREVER } }
        assertTrue(HistoryPause.paused(s.behavior))
        // On again: the stored pause is gone (not a time in the past).
        repo.setHistoryPausedUntil(0)
        repo.setPlayCountSeconds(10)
        repo.setPlayCountOnAccount(false)
        val back = withTimeout(5_000) { repo.flow.first { !it.behavior.playCountOnAccount && it.behavior.playCountSeconds == 10 && it.behavior.historyPausedUntil == 0L } }
        assertFalse(HistoryPause.paused(back.behavior))
        // How you listen, not how Prism looks: a shared Look can never pause someone's history.
        listOf("history_paused_until", "play_count_seconds", "play_count_account").forEach {
            assertTrue("$it is listed as not a Look", it in repo.notLookNames)
            assertTrue("$it isn't in a Look", it !in repo.lookKinds)
        }
        // The Replay looks (#286, #287) are part of one.
        assertEquals('s', repo.lookKinds["replay_card_graph"])
        assertEquals('b', repo.lookKinds["replay_story_font"])
    }
}
