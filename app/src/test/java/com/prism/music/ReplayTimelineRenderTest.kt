package com.prism.music

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.prism.music.data.ReplayBuckets
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.ThemeMode
import com.prism.music.ui.screens.Period
import com.prism.music.ui.screens.ReplayTimeline
import com.prism.music.ui.screens.StoryTypeSample
import com.prism.music.ui.screens.TimelineChart
import com.prism.music.ui.screens.heading
import com.prism.music.ui.screens.timelineEnd
import com.prism.music.ui.theme.PrismTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Replay's graphs by time (#286, #287) as the Replay page draws them (a day by hours, a week and a month by days, a
 * quarter by weeks), and the stories' type in Prism's poster faces and in the app's font, onto app/build/replay-graphs/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w412dp-h2400dp-xxhdpi")
class ReplayTimelineRenderTest {
    @get:Rule val compose = createComposeRule()

    private val zone = ZoneId.systemDefault()
    private val now = LocalDateTime.of(2026, 11, 4, 18, 20).atZone(zone).toInstant().toEpochMilli()
    private fun day(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()

    /** Bars of [unit] from [from] to [until], each with a made-up listening ([minutes] by index), none after [now]. */
    private fun timeline(from: Long, until: Long, unit: ReplayBuckets.Unit, minutes: (Int) -> Int): ReplayTimeline {
        val buckets = ReplayBuckets.edges(from, until, unit, zone)
        return ReplayTimeline(unit, buckets, buckets.mapIndexed { i, b -> if (b.start > now) 0L else minutes(i) * 60_000L }, now)
    }

    private val today get() = timeline(day(LocalDate.of(2026, 11, 4)), day(LocalDate.of(2026, 11, 5)), ReplayBuckets.Unit.HOUR) { h -> listOf(0, 0, 0, 0, 0, 0, 0, 12, 34, 20, 8, 5, 18, 22, 9, 4, 15, 40, 55, 0)[h.coerceAtMost(19)] }
    private val week get() = timeline(day(LocalDate.of(2026, 11, 2)), day(LocalDate.of(2026, 11, 9)), ReplayBuckets.Unit.DAY) { listOf(84, 40, 121, 0, 0, 0, 0)[it] }
    private val month get() = timeline(day(LocalDate.of(2026, 11, 1)), day(LocalDate.of(2026, 12, 1)), ReplayBuckets.Unit.DAY) { (it * 37 % 90) + 10 }
    private val quarter get() = timeline(day(LocalDate.of(2026, 7, 6)), now, ReplayBuckets.Unit.WEEK) { (it * 53 % 300) + 60 }

    private fun shoot(name: String, dark: Boolean) {
        val charts = listOf(today, week, month, quarter)
        compose.setContent {
            PrismTheme(AppSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT), artworkColor = null) {
                Column(Modifier.width(412.dp).background(MaterialTheme.colorScheme.background).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    charts.forEach { t ->
                        Text(t.heading(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
                        TimelineChart(t, Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(196.dp))
                    }
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StoryTypeSample(false, Modifier.weight(1f))
                        StoryTypeSample(true, Modifier.weight(1f))
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/replay-graphs").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("wrote ${File(dir, "$name.png").absolutePath}")
    }

    @Test fun dark() = shoot("replay-graphs-dark", dark = true)
    @Test fun light() = shoot("replay-graphs-light", dark = false)

    @Test fun eachGraphSaysItsBusiestBar() {
        compose.setContent { PrismTheme(AppSettings(), artworkColor = null) { TimelineChart(week, Modifier.width(400.dp).height(200.dp)) } }
        // Wednesday 4 November, 121 minutes (in hours, as Replay shows them unless set to minutes).
        val line = compose.onNodeWithText("Busiest day:", substring = true).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString()
        org.junit.Assert.assertTrue(line, line.endsWith("· 2h 1m"))
    }

    @Test fun aDayAWeekAndAMonthShowWhole() {
        val first = java.time.DayOfWeek.MONDAY
        assertEquals(day(LocalDate.of(2026, 11, 5)), timelineEnd(Period.TODAY, now, zone, first))
        assertEquals(day(LocalDate.of(2026, 11, 9)), timelineEnd(Period.WEEK, now, zone, first))
        assertEquals(day(LocalDate.of(2026, 12, 1)), timelineEnd(Period.MONTH, now, zone, first))
        assertEquals("the last 30 days end now", now, timelineEnd(Period.DAYS30, now, zone, first))
        assertEquals(24, today.buckets.size)
        assertEquals(7, week.buckets.size)
        assertEquals(30, month.buckets.size)
    }
}
