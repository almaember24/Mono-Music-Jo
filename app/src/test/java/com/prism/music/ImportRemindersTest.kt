package com.prism.music

import android.content.Context
import com.prism.music.data.imports.HistoryImports
import com.prism.music.data.imports.ImportReminders
import com.prism.music.data.imports.ImportSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset

/**
 * The fresh-download reminder (1.8.1): never for anyone who hasn't imported Spotify (or Takeout), every 3 months by
 * default after the last import, Off means never, and once reminded the next is months away again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ImportRemindersTest {
    private val utc = ZoneOffset.UTC
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences(HistoryImports.PREFS, Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test fun theWeeklyMatchingRetryWaitsAWeek() {
        // Started at once, it would match beside the one-time run every import starts (both making the same playlists).
        val week = 7 * 86_400_000L
        val weekly = com.prism.music.data.imports.ImportMatchWorker.weekly(mobile = false)
        assertEquals(week, weekly.workSpec.initialDelay)
        assertEquals(week, weekly.workSpec.intervalDuration)
    }

    @Test fun neverWithoutAnImport() {
        val now = at("2030-01-01T00:00:00Z")
        assertEquals(emptyList<ImportSource>(), ImportReminders.due(now, 3, emptyMap(), emptyMap(), utc))
        assertEquals(emptyList<ImportSource>(), ImportReminders.due(prefs, now, utc))
        assertFalse("no daily check either", ImportReminders.wanted(prefs))
        // Last.fm's scrobbles are asked for by Prism itself: no reminder for them.
        assertEquals(emptyList<ImportSource>(), ImportReminders.due(now, 1, mapOf(ImportSource.LASTFM to at("2020-01-01T00:00:00Z")), emptyMap(), utc))
    }

    @Test fun threeMonthsByDefault() {
        prefs.edit().putLong("last_import_spotify", at("2026-10-03T12:00:00Z")).commit()
        assertEquals(3, ImportReminders.months(prefs))
        assertTrue(ImportReminders.wanted(prefs))
        assertEquals(emptyList<ImportSource>(), ImportReminders.due(prefs, at("2026-11-02T12:00:00Z"), utc))
        assertEquals(emptyList<ImportSource>(), ImportReminders.due(prefs, at("2027-01-03T11:59:00Z"), utc))
        assertEquals(listOf(ImportSource.SPOTIFY), ImportReminders.due(prefs, at("2027-01-03T12:00:00Z"), utc))
    }

    @Test fun offMeansNever() {
        prefs.edit().putLong("last_import_spotify", at("2026-01-01T00:00:00Z")).putInt("remind_months", 0).commit()
        assertEquals(0, ImportReminders.months(prefs))
        assertFalse(ImportReminders.wanted(prefs))
        assertEquals(emptyList<ImportSource>(), ImportReminders.due(prefs, at("2036-01-01T00:00:00Z"), utc))
    }

    @Test fun oneOrSixMonthsAndOnceRemindedTheNextIsMonthsAway() {
        val last = mapOf(ImportSource.SPOTIFY to at("2026-01-31T09:00:00Z"), ImportSource.TAKEOUT to at("2026-03-01T09:00:00Z"))
        assertEquals(listOf(ImportSource.SPOTIFY), ImportReminders.due(at("2026-03-01T08:00:00Z"), 1, last, emptyMap(), utc))
        assertEquals(listOf(ImportSource.SPOTIFY, ImportSource.TAKEOUT), ImportReminders.due(at("2026-04-01T09:00:00Z"), 1, last, emptyMap(), utc))
        assertEquals(emptyList<ImportSource>(), ImportReminders.due(at("2026-07-30T09:00:00Z"), 6, last, emptyMap(), utc))
        assertEquals(listOf(ImportSource.SPOTIFY), ImportReminders.due(at("2026-07-31T09:00:00Z"), 6, last, emptyMap(), utc))
        // Reminded on 31 July: the next one is six months on (no new import came).
        val reminded = mapOf(ImportSource.SPOTIFY to at("2026-07-31T09:00:00Z"))
        assertEquals(emptyList<ImportSource>(), ImportReminders.due(at("2026-08-15T09:00:00Z"), 6, last, reminded, utc))
        assertEquals(listOf(ImportSource.SPOTIFY), ImportReminders.due(at("2027-01-31T09:00:00Z"), 6, last, reminded + (ImportSource.TAKEOUT to at("2026-09-01T09:00:00Z")), utc))
        // An odd stored value reads as the default.
        prefs.edit().putInt("remind_months", 5).commit()
        assertEquals(3, ImportReminders.months(prefs))
    }

    @Test fun freshDownloadPages() {
        assertEquals("https://www.spotify.com/account/privacy/", ImportReminders.requestUrl(ImportSource.SPOTIFY))
        assertEquals("https://takeout.google.com/", ImportReminders.requestUrl(ImportSource.TAKEOUT))
    }
}
