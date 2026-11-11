package com.prism.music.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Replay's graphs by time (#286, #287, 1.8.1): the stretches a chart's bars stand for (hours, days, weeks or months on
 * the phone's clock, whatever daylight saving does) and the listening in each. Until 1.8.1 Replay only drew months, so a
 * week or a month had no graph at all, and the Home card always showed this year's months.
 */
object ReplayBuckets {
    enum class Unit { HOUR, DAY, WEEK, MONTH }

    /** One bar: from [start] up to (not including) [end]. */
    data class Bucket(val start: Long, val end: Long)

    /** What a chart of [from]..[to] is drawn in: a day by hours, up to two months by days, half a year by weeks, else months. */
    fun unitFor(from: Long, to: Long): Unit {
        val hours = (to - from) / 3_600_000.0
        return when {
            hours <= 25.5 -> Unit.HOUR
            hours <= 62 * 24.0 -> Unit.DAY
            hours <= 183 * 24.0 -> Unit.WEEK
            else -> Unit.MONTH
        }
    }

    /** The start of the [unit] that [at] falls in. */
    fun startOf(at: Long, unit: Unit, zone: ZoneId, firstDay: DayOfWeek = DayOfWeek.MONDAY): ZonedDateTime {
        val t = Instant.ofEpochMilli(at).atZone(zone)
        return when (unit) {
            Unit.HOUR -> t.truncatedTo(ChronoUnit.HOURS)
            Unit.DAY -> t.toLocalDate().atStartOfDay(zone)
            Unit.WEEK -> t.toLocalDate().with(TemporalAdjusters.previousOrSame(firstDay)).atStartOfDay(zone)
            Unit.MONTH -> t.toLocalDate().withDayOfMonth(1).atStartOfDay(zone)
        }
    }

    private fun ZonedDateTime.next(unit: Unit): ZonedDateTime = when (unit) {
        Unit.HOUR -> plusHours(1)
        // By the date, not 24 hours: a day of a clock change is 23 or 25 hours long.
        Unit.DAY -> toLocalDate().plusDays(1).atStartOfDay(zone)
        Unit.WEEK -> toLocalDate().plusWeeks(1).atStartOfDay(zone)
        Unit.MONTH -> toLocalDate().plusMonths(1).atStartOfDay(zone)
    }

    /** The [unit]s from the one [from] falls in up to [until] (at most [MAX] of them). */
    fun edges(from: Long, until: Long, unit: Unit, zone: ZoneId, firstDay: DayOfWeek = DayOfWeek.MONDAY): List<Bucket> {
        val out = ArrayList<Bucket>()
        var start = startOf(from, unit, zone, firstDay)
        while (start.toInstant().toEpochMilli() < until && out.size < MAX) {
            val end = start.next(unit)
            out += Bucket(start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
            start = end
        }
        return out
    }

    /** The last [n] days, today last. */
    fun lastDays(n: Int, now: Long, zone: ZoneId): List<Bucket> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val from = today.minusDays((n - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        return edges(from, today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), Unit.DAY, zone)
    }

    /** The last [n] weeks as the phone's region starts them ([firstDay]), this week last. */
    fun lastWeeks(n: Int, now: Long, zone: ZoneId, firstDay: DayOfWeek): List<Bucket> {
        val week = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(firstDay))
        val from = week.minusWeeks((n - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        return edges(from, week.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli(), Unit.WEEK, zone, firstDay)
    }

    /** This year's months so far, January first, this month last. */
    fun monthsSoFar(now: Long, zone: ZoneId): List<Bucket> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val from = LocalDate.of(today.year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        return edges(from, today.withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli(), Unit.MONTH, zone)
    }

    /** Listening ([playedMs] of plays at [at]) in each bucket; plays outside them aren't counted. */
    fun sum(buckets: List<Bucket>, at: LongArray, playedMs: LongArray): List<Long> {
        val out = LongArray(buckets.size)
        if (buckets.isEmpty()) return out.toList()
        val starts = LongArray(buckets.size) { buckets[it].start }
        for (i in at.indices) {
            val t = at[i]
            var k = java.util.Arrays.binarySearch(starts, t)
            if (k < 0) k = -k - 2
            if (k < 0 || t >= buckets[k].end) continue
            out[k] += playedMs[i]
        }
        return out.toList()
    }

    /** The bar with the most listening (null: none had any). */
    fun busiest(values: List<Long>): Int? = values.indices.filter { values[it] > 0 }.maxByOrNull { values[it] }

    /** The index of the bar [now] falls in (null: none, a stretch in the past). */
    fun current(buckets: List<Bucket>, now: Long): Int? = buckets.indexOfFirst { now >= it.start && now < it.end }.takeIf { it >= 0 }

    /**
     * Which of [n] bars of [unit] get a label under them, so the labels never crowd: every sixth hour, each day of a
     * week (else about six days), about five weeks, every month up to a year (else about eight).
     */
    fun labeled(n: Int, unit: Unit): Set<Int> {
        if (n <= 0) return emptySet()
        val step = when (unit) {
            Unit.HOUR -> 6
            Unit.DAY -> if (n <= 7) 1 else kotlin.math.ceil(n / 6.0).toInt()
            Unit.WEEK -> kotlin.math.ceil(n / 5.0).toInt()
            Unit.MONTH -> if (n <= 12) 1 else kotlin.math.ceil(n / 8.0).toInt()
        }.coerceAtLeast(1)
        return (0 until n step step).toSet()
    }

    const val MAX = 400
}
