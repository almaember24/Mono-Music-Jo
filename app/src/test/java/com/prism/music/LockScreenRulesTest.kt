package com.prism.music

import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.LockLayout
import com.prism.music.data.prefs.LockPaused
import com.prism.music.ui.lockscreen.LockScreenRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockScreenRulesTest {
    @Test fun offUntilSwitchedOn() {
        assertFalse(AppSettings().behavior.lockScreen)
        assertEquals(LockLayout.COVER, AppSettings().behavior.lockScreenLayout)
        assertEquals(LockPaused.TEN_MINUTES, AppSettings().behavior.lockScreenPaused)
        assertFalse(AppSettings().behavior.lockScreenHideSong)
    }

    @Test fun performanceModeIsAlwaysQuiet() {
        LockLayout.entries.forEach { chosen ->
            assertEquals(LockLayout.QUIET, LockScreenRules.layoutFor(chosen, performanceMode = true, hideSong = false))
            assertEquals(LockLayout.QUIET, LockScreenRules.layoutFor(chosen, performanceMode = true, hideSong = true))
            assertEquals(chosen, LockScreenRules.layoutFor(chosen, performanceMode = false, hideSong = false))
        }
    }

    @Test fun aHiddenSongHasNoLyricsToShow() {
        assertEquals(LockLayout.COVER, LockScreenRules.layoutFor(LockLayout.LYRICS, performanceMode = false, hideSong = true))
        assertEquals(LockLayout.FULL, LockScreenRules.layoutFor(LockLayout.FULL, performanceMode = false, hideSong = true))
    }

    @Test fun stayingWhilePaused() {
        val now = 100_000_000L
        // Playing: always.
        LockPaused.entries.forEach { assertTrue(LockScreenRules.stays(it, 0L, now)) }
        assertTrue(LockScreenRules.stays(LockPaused.TEN_MINUTES, now - 9 * 60_000, now))
        assertFalse(LockScreenRules.stays(LockPaused.TEN_MINUTES, now - 10 * 60_000, now))
        assertTrue(LockScreenRules.stays(LockPaused.UNTIL_SLEEP, now - 60 * 60_000, now))
        assertFalse("the phone has slept", LockScreenRules.stays(LockPaused.UNTIL_SLEEP, now - 60 * 60_000, now, slept = true))
        assertTrue("the others don't go by the sleep", LockScreenRules.stays(LockPaused.TEN_MINUTES, now - 60_000, now, slept = true))
        // Off still lets a pause just pressed be undone.
        assertTrue(LockScreenRules.stays(LockPaused.OFF, now - 1_000, now))
        assertFalse(LockScreenRules.stays(LockPaused.OFF, now - 3_000, now))
    }

    @Test fun looksForTheLockQuicklyThenSlowlyThenStops() {
        assertEquals(200L, LockScreenRules.nextLookMs(0))
        assertEquals(1_000L, LockScreenRules.nextLookMs(5_000))
        assertEquals(15_000L, LockScreenRules.nextLookMs(60_000))
        // Android's longest lock delay is 30 minutes.
        assertEquals(15_000L, LockScreenRules.nextLookMs(30 * 60_000L))
        assertNull(LockScreenRules.nextLookMs(36 * 60_000L))
    }
}
