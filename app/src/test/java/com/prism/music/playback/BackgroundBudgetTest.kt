package com.prism.music.playback

import com.prism.music.playback.BackgroundBudget.Where
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The budget that keeps Android from closing Prism for using too much processor in the background (#373's log). */
class BackgroundBudgetTest {
    /** Made-up clocks: work that would run flat out on [cores] cores whenever it isn't resting, for [minutes]. */
    private class Run(val cores: Double) {
        var wall = 1_000_000L
        var cpu = 0L
        val perSecond = HashMap<Long, Long>()
        val gauge = BackgroundBudget.Gauge({ wall }, { cpu })

        fun go(ms: Long, where: (Long) -> Where, slow: Boolean = false) {
            val end = wall + ms
            while (wall < end) {
                val rest = gauge.rest(where(wall), slow)
                if (rest > 0) { wall += rest; continue }
                // A step of work between two looks at the budget (a decoded buffer, or a step of the analysis).
                wall += STEP_MS
                val used = (STEP_MS * cores).toLong()
                cpu += used
                perSecond.merge(wall / 1000, used, Long::plus)
            }
        }

        /** The most any window of [windowMs] used, as a share of one core. */
        fun worst(fromMs: Long, toMs: Long, windowMs: Long): Double {
            val secs = windowMs / 1000
            var best = 0.0
            var s = fromMs / 1000
            while (s + secs <= toMs / 1000) {
                var sum = 0L
                for (i in s until s + secs) sum += perSecond[i] ?: 0
                best = maxOf(best, sum.toDouble() / windowMs)
                s += 5
            }
            return best
        }
    }

    @Test fun workLeftBehindStaysWithinEveryLimitAndroidCloses() {
        // The reporter's kind of phone: a scan, a video sync and a Sing separation, about 1.5 cores with nothing resting.
        val r = Run(cores = 1.5)
        val start = r.wall
        r.go(30 * 60_000L, { Where.AWAY })
        // Android's look: 25% in its first ten minutes, then 10%, then 2%.
        val lookMs = BackgroundBudget.ANDROID_LOOK_MS
        var from = start
        for ((upTo, limit) in BackgroundBudget.ANDROID_LIMITS) {
            val to = if (upTo == Long.MAX_VALUE) start + 30 * 60_000L else start + upTo
            val worst = r.worst(from, to, lookMs)
            assertTrue("worst five minutes up to $upTo ms: $worst of a core, Android's limit is $limit", worst < limit)
            from = to - lookMs
        }
    }

    @Test fun workLeftBehindStopsAfterTheGrace() {
        val r = Run(cores = 1.5)
        val start = r.wall
        r.go(BackgroundBudget.AWAY_GRACE_MS + 10 * 60_000L, { Where.AWAY })
        val after = r.perSecond.filterKeys { it * 1000 > start + BackgroundBudget.AWAY_GRACE_MS + 10_000 }.values.sum()
        assertEquals("nothing runs once the grace is over", 0L, after)
    }

    @Test fun whatWasHeldCarriesOnWhenTheListenerIsBack() {
        val r = Run(cores = 1.0)
        val start = r.wall
        val backAt = start + 5 * 60_000L
        r.go(6 * 60_000L, { now -> if (now < backAt) Where.AWAY else Where.PLAYING })
        val after = r.perSecond.filterKeys { it * 1000 >= backAt + 10_000 }.values.sum()
        assertTrue("it went on with music playing: $after ms", after > 30_000)
    }

    @Test fun playingOutOfSightKeepsToACoresWorth() {
        // Two cores' worth of work wanted: half of it, over any minute.
        val r = Run(cores = 2.0)
        val start = r.wall
        r.go(5 * 60_000L, { Where.PLAYING })
        val worst = r.worst(start, start + 5 * 60_000L, 60_000L)
        assertTrue("worst minute: $worst of a core", worst <= 1.1)
        assertTrue("and it does get that: $worst of a core", worst > 0.8)
    }

    @Test fun onScreenOnlyASlowPhoneIsHeldToACoresWorth() {
        val fast = Run(cores = 2.0)
        fast.go(60_000L, { Where.ON_SCREEN }, slow = false)
        assertTrue("a normal phone isn't held back on screen", fast.cpu > 110_000)
        val slow = Run(cores = 2.0)
        slow.go(60_000L, { Where.ON_SCREEN }, slow = true)
        assertTrue("a slow phone keeps a core's worth for its screen: ${slow.cpu} ms", slow.cpu < 70_000)
    }

    @Test fun theShareForEachPlace() {
        assertEquals(-1.0, BackgroundBudget.share(Where.ON_SCREEN, 0, false), 0.0)
        assertEquals(1.0, BackgroundBudget.share(Where.ON_SCREEN, 0, true), 0.0)
        assertEquals(1.0, BackgroundBudget.share(Where.PLAYING, 0, false), 0.0)
        assertEquals(BackgroundBudget.AWAY_SHARE, BackgroundBudget.share(Where.AWAY, 0, false), 0.0)
        assertEquals(0.0, BackgroundBudget.share(Where.AWAY, BackgroundBudget.AWAY_GRACE_MS, false), 0.0)
    }

    @Test fun restingIsWhatBringsTheShareDown() {
        assertEquals("no limit", 0L, BackgroundBudget.restMs(100_000, 1, -1.0))
        assertEquals("within it", 0L, BackgroundBudget.restMs(500, 1_000, 1.0))
        assertEquals("a second over a core", 1_000L, BackgroundBudget.restMs(2_000, 1_000, 1.0))
        assertEquals("not at all", BackgroundBudget.HOLD_MS, BackgroundBudget.restMs(0, 1_000, 0.0))
    }

    private companion object { const val STEP_MS = 20L }
}
