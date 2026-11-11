package com.prism.music

import com.prism.music.data.ReplayStats
import com.prism.music.data.db.PlayEventEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

// Robolectric: the words come from strings.xml (1.7.9).
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class ReplayStatsTest {
    private fun at(d: String, hour: Int, min: Int) =
        PlayEventEntity(songId = "s", timestamp = LocalDateTime.parse("${d}T%02d:00".format(hour)).toInstant(ZoneOffset.UTC).toEpochMilli(), playedMs = min * 60_000L)

    @Test
    fun habitsFindPrimeTimeStreakAndBiggestDay() {
        val events = listOf(
            at("2026-03-02", 23, 30), at("2026-03-03", 23, 40), at("2026-03-04", 22, 20), // Mon–Wed in a row
            at("2026-03-06", 9, 5), at("2026-03-07", 23, 90),                           // Sat is the biggest day
        )
        val h = ReplayStats.habits(events, ZoneOffset.UTC)
        assertEquals(23, h.peakHour)
        assertEquals(3, h.longestStreak)
        assertEquals(LocalDate.parse("2026-03-07"), h.biggestDay)
        assertEquals(5, h.topWeekday) // Saturday
        assertEquals("11 PM", ReplayStats.hourLabel(h.peakHour))
        assertEquals("12 AM", ReplayStats.hourLabel(0))
    }

    @Test
    fun personalityPicksTheStrongestTrait() {
        val night = ReplayStats.habits(listOf(at("2026-03-02", 1, 100), at("2026-03-02", 14, 10)), ZoneOffset.UTC)
        val p = ReplayStats.personality(
            totalMs = 110 * 60_000L, topFiveArtistsMs = 30 * 60_000L, artistCount = 40, newArtistCount = 4,
            plays = 50, songCount = 40, topSongPlays = 3, topSongTitle = "X", activeDays = 1, topGenreShare = 0.6, habits = night,
        )
        assertEquals("The Night Owl", p.name)

        val loyal = ReplayStats.personality(
            totalMs = 1000 * 60_000L, topFiveArtistsMs = 900 * 60_000L, artistCount = 8, newArtistCount = 0,
            plays = 300, songCount = 120, topSongPlays = 12, topSongTitle = "Y", activeDays = 30, topGenreShare = 0.8,
            habits = ReplayStats.habits(listOf(at("2026-03-02", 14, 10)), ZoneOffset.UTC),
        )
        assertEquals("The Loyalist", loyal.name)
    }
}
