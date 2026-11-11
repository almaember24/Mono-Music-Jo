package com.prism.music.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.data.ReplayBuckets
import com.prism.music.data.imports.PlayStats
import com.prism.music.tr
import java.time.ZoneId
import java.util.Locale

/**
 * Replay's graph by time (#286, #287, 1.8.1): a day by its hours, a week or a month by its days, up to half a year by
 * weeks. Until 1.8.1 only a stretch of more than one month had a graph (by month, [MonthChart]); a week had none.
 */
internal class ReplayTimeline(val unit: ReplayBuckets.Unit, val buckets: List<ReplayBuckets.Bucket>, val values: List<Long>, val now: Long) {
    val busiest: Int? get() = ReplayBuckets.busiest(values)
}

/**
 * The graph for [period] ([from]..[to] as Replay shows it), or null when it's drawn by month (a year, everything, or more
 * than half a year of days). A day, a week and a month show whole (the hours and days still to come as stubs).
 */
internal suspend fun loadTimeline(plays: PlayStats, period: Period, from: Long, to: Long, now: Long = System.currentTimeMillis()): ReplayTimeline? {
    if (period.kind == Period.Kind.YEAR || period.kind == Period.Kind.ALL) return null
    val zone = ZoneId.systemDefault()
    val firstDay = java.time.temporal.WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val until = timelineEnd(period, to, zone, firstDay)
    val unit = ReplayBuckets.unitFor(from, until)
    if (unit == ReplayBuckets.Unit.MONTH) return null
    val buckets = ReplayBuckets.edges(from, until, unit, zone, firstDay)
    val events = plays.events(from, to)
    val values = ReplayBuckets.sum(buckets, LongArray(events.size) { events[it].timestamp }, LongArray(events.size) { events[it].playedMs })
    return ReplayTimeline(unit, buckets, values, now)
}

/** Where the graph of [period] ends: today, this week and this month whole; any other stretch where it ends ([to]). */
internal fun timelineEnd(period: Period, to: Long, zone: ZoneId, firstDay: java.time.DayOfWeek): Long {
    val today = java.time.Instant.ofEpochMilli(to).atZone(zone).toLocalDate()
    val end = when (period.kind) {
        Period.Kind.TODAY -> today.plusDays(1)
        Period.Kind.WEEK -> today.with(java.time.temporal.TemporalAdjusters.previousOrSame(firstDay)).plusWeeks(1)
        Period.Kind.MONTH -> today.withDayOfMonth(1).plusMonths(1)
        else -> return to
    }
    return end.atStartOfDay(zone).toInstant().toEpochMilli()
}

/** [ms] on the phone's clock, written by [format] (a [com.prism.music.localDates] format: "j" an hour, "MMMd" a day…). */
internal fun formatAt(ms: Long, format: java.time.format.DateTimeFormatter): String =
    format.format(java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** The label under bar [i] ("" when it has none: [ReplayBuckets.labeled]). */
internal fun ReplayTimeline.label(i: Int, labeled: Set<Int>): String {
    if (i !in labeled) return ""
    val at = buckets[i].start
    return when (unit) {
        ReplayBuckets.Unit.HOUR -> formatAt(at, com.prism.music.localDates("j"))
        ReplayBuckets.Unit.DAY -> if (buckets.size <= 7) formatAt(at, com.prism.music.localDates("EEEEE")) else {
            // Day numbers, with the month at the first label and wherever a new month starts.
            val prev = labeled.filter { it < i }.maxOrNull()
            val month = { ms: Long -> java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).monthValue }
            if (prev == null || month(buckets[prev].start) != month(at)) formatAt(at, com.prism.music.localDates("MMMd")) else formatAt(at, com.prism.music.localDates("d"))
        }
        ReplayBuckets.Unit.WEEK -> formatAt(at, com.prism.music.localDates("MMMd"))
        ReplayBuckets.Unit.MONTH -> formatAt(at, com.prism.music.localDates("MMMMM"))
    }
}

/** The line under the graph: its busiest bar and how long you listened then ("Busiest day: Tue, Nov 3 · 1h 20m"). */
@Composable
internal fun ReplayTimeline.busiestLine(): String? {
    val i = busiest ?: return null
    val at = buckets[i].start
    val time = listenTime(values[i])
    return when (unit) {
        ReplayBuckets.Unit.HOUR -> tr(R.string.replay_busiest_hour, formatAt(at, com.prism.music.localDates("j")), time)
        ReplayBuckets.Unit.DAY -> tr(R.string.replay_busiest_day, formatAt(at, com.prism.music.localDates("MMMEd")), time)
        ReplayBuckets.Unit.WEEK -> tr(R.string.replay_busiest_week, formatAt(at, com.prism.music.localDates("MMMd")), time)
        ReplayBuckets.Unit.MONTH -> tr(R.string.replay_busiest_month, formatAt(at, com.prism.music.localDates("MMMMy")), time)
    }
}

/** The heading over the graph. */
internal fun ReplayTimeline.heading(): String = when (unit) {
    ReplayBuckets.Unit.HOUR -> tr(R.string.replay_by_hour)
    ReplayBuckets.Unit.DAY -> tr(R.string.replay_by_day)
    ReplayBuckets.Unit.WEEK -> tr(R.string.replay_by_week)
    ReplayBuckets.Unit.MONTH -> tr(R.string.replay_by_month)
}

/**
 * The graph, in [MonthChart]'s own style (bars from the tertiary to the accent, growing in), labels under it: what's
 * still to come is a faint stub, the busiest bar's line under it all.
 */
@Composable
internal fun TimelineChart(t: ReplayTimeline, modifier: Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val faint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val anim = remember(t) { Animatable(0f) }
    LaunchedEffect(t) { anim.animateTo(1f, tween(900)) }
    val labeled = remember(t) { ReplayBuckets.labeled(t.buckets.size, t.unit) }
    val labels = remember(t) { t.buckets.indices.map { t.label(it, labeled) } }
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val n = t.values.size
            if (n == 0) return@Canvas
            val max = t.values.max().coerceAtLeast(1L).toFloat()
            // Thin gaps for many bars, MonthChart's 8 dp for a few.
            val gap = (if (n > 14) 3.dp else if (n > 8) 5.dp else 8.dp).toPx()
            val w = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
            val radius = CornerRadius(minOf(6.dp.toPx(), w / 2))
            t.values.forEachIndexed { i, ms ->
                val x = i * (w + gap)
                if (t.buckets[i].start > t.now) {
                    drawRoundRect(faint, Offset(x, size.height - w.coerceAtMost(6.dp.toPx())), Size(w, w.coerceAtMost(6.dp.toPx())), radius)
                    return@forEachIndexed
                }
                val h = size.height * (ms / max) * anim.value
                if (h <= 0f) return@forEachIndexed
                drawRoundRect(Brush.verticalGradient(listOf(tertiary, primary), startY = size.height - h, endY = size.height), Offset(x, size.height - h), Size(w, h), radius)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            labels.forEach { l ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                    // A label is wider than its bar when there are many: it runs over its neighbours, which have none.
                    if (l.isNotEmpty()) Text(l, Modifier.wrapContentWidth(unbounded = true), style = MaterialTheme.typography.labelSmall, color = ink, maxLines = 1, softWrap = false)
                }
            }
        }
        t.busiestLine()?.let { Text(it, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium, color = ink) }
    }
}
