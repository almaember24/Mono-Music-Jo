package com.prism.music

import com.prism.music.data.ReplayBuckets
import com.prism.music.data.ReplayBuckets.Unit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Replay's graphs by time (#286, #287): which bars a stretch gets, where each starts and ends on the phone's clock
 * (daylight saving included), and what's in each.
 */
class ReplayBucketsTest {
    private val ny = ZoneId.of("America/New_York")
    private fun at(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).atZone(ny).toInstant().toEpochMilli()
    private fun day(y: Int, mo: Int, d: Int) = LocalDate.of(y, mo, d).atStartOfDay(ny).toInstant().toEpochMilli()

    @Test fun eachStretchItsUnit() {
        assertEquals("a day by its hours", Unit.HOUR, ReplayBuckets.unitFor(day(2026, 5, 4), day(2026, 5, 5)))
        assertEquals("the day of a clock change too (25 hours)", Unit.HOUR, ReplayBuckets.unitFor(day(2026, 11, 1), day(2026, 11, 2)))
        assertEquals("a week by its days", Unit.DAY, ReplayBuckets.unitFor(day(2026, 5, 4), day(2026, 5, 11)))
        assertEquals("a month by its days", Unit.DAY, ReplayBuckets.unitFor(day(2026, 5, 1), day(2026, 6, 1)))
        assertEquals("two months still by days", Unit.DAY, ReplayBuckets.unitFor(day(2026, 5, 1), day(2026, 7, 1)))
        assertEquals("a quarter by weeks", Unit.WEEK, ReplayBuckets.unitFor(day(2026, 3, 1), day(2026, 6, 1)))
        assertEquals("a year by months", Unit.MONTH, ReplayBuckets.unitFor(day(2026, 1, 1), day(2027, 1, 1)))
    }

    @Test fun barsFollowTheClock() {
        // The day summer time ends in New York: 25 hours, so 25 bars, each an hour.
        val hours = ReplayBuckets.edges(day(2026, 11, 1), day(2026, 11, 2), Unit.HOUR, ny)
        assertEquals(25, hours.size)
        assertTrue(hours.all { it.end - it.start == 3_600_000L })
        // Days are whole days on the clock: that one is 25 hours long, the week still 7 bars.
        val week = ReplayBuckets.edges(day(2026, 10, 29), day(2026, 11, 5), Unit.DAY, ny)
        assertEquals(7, week.size)
        assertEquals(25 * 3_600_000L, week[3].end - week[3].start)
        assertEquals(day(2026, 11, 2), week[3].end)
        // A week as the region starts it: from a Wednesday, a Sunday-first week starts the Sunday before.
        val w = ReplayBuckets.edges(at(2026, 5, 6, 15), at(2026, 5, 7), Unit.WEEK, ny, DayOfWeek.SUNDAY)
        assertEquals(day(2026, 5, 3), w.single().start)
        // Months of different lengths.
        val months = ReplayBuckets.edges(day(2026, 1, 1), day(2026, 4, 1), Unit.MONTH, ny)
        assertEquals(listOf(day(2026, 1, 1), day(2026, 2, 1), day(2026, 3, 1)), months.map { it.start })
        assertEquals(day(2026, 4, 1), months.last().end)
    }

    @Test fun theCardsStretches() {
        val now = at(2026, 11, 2, 15, 30)
        val days = ReplayBuckets.lastDays(14, now, ny)
        assertEquals(14, days.size)
        assertEquals("today last", day(2026, 11, 2), days.last().start)
        assertEquals(day(2026, 10, 20), days.first().start)
        assertEquals(13, ReplayBuckets.current(days, now))
        val weeks = ReplayBuckets.lastWeeks(12, now, ny, DayOfWeek.MONDAY)
        assertEquals(12, weeks.size)
        assertEquals("this week last (Monday 2 November)", day(2026, 11, 2), weeks.last().start)
        assertEquals(day(2026, 8, 17), weeks.first().start)
        val months = ReplayBuckets.monthsSoFar(now, ny)
        assertEquals("January to November", 11, months.size)
        assertEquals(day(2026, 1, 1), months.first().start)
        assertEquals(day(2026, 12, 1), months.last().end)
        assertEquals(1, ReplayBuckets.monthsSoFar(at(2026, 1, 1, 0, 5), ny).size)
    }

    @Test fun listeningInEachBar() {
        val bars = ReplayBuckets.edges(day(2026, 5, 4), day(2026, 5, 7), Unit.DAY, ny)
        val at = longArrayOf(day(2026, 5, 4), at(2026, 5, 4, 23, 59), day(2026, 5, 5), at(2026, 5, 6, 12), day(2026, 5, 7), day(2026, 5, 3))
        val ms = longArrayOf(1, 2, 4, 8, 16, 32)
        // Midnight belongs to the day it starts; plays before the first bar and from the end on aren't counted.
        assertEquals(listOf(3L, 4L, 8L), ReplayBuckets.sum(bars, at, ms))
        assertEquals(2, ReplayBuckets.busiest(listOf(3L, 4L, 8L)))
        assertNull("nothing played: no busiest", ReplayBuckets.busiest(listOf(0L, 0L)))
        assertEquals(emptyList<Long>(), ReplayBuckets.sum(emptyList(), at, ms))
    }

    @Test fun labelsNeverCrowd() {
        assertEquals(setOf(0, 6, 12, 18), ReplayBuckets.labeled(24, Unit.HOUR))
        assertEquals("a week: every day", (0 until 7).toSet(), ReplayBuckets.labeled(7, Unit.DAY))
        assertEquals("a month: about every fifth day", setOf(0, 6, 12, 18, 24, 30), ReplayBuckets.labeled(31, Unit.DAY))
        assertTrue(ReplayBuckets.labeled(26, Unit.WEEK).size <= 5)
        assertEquals((0 until 12).toSet(), ReplayBuckets.labeled(12, Unit.MONTH))
        assertTrue(ReplayBuckets.labeled(60, Unit.MONTH).size <= 8)
        assertEquals(emptySet<Int>(), ReplayBuckets.labeled(0, Unit.DAY))
    }
}
