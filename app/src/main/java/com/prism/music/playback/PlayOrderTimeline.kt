package com.prism.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi

/**
 * [inner]'s songs listed in the order they'll play (its shuffle order, from the playing song on), each window's
 * periods moving with it. Read in that order, "next" is simply the one after, shuffled or not, so whoever reads
 * this ([LyricsOverlayPlayer]'s session: Android Auto's queue) sees the queue as the phone does.
 * [order] maps a shown place to the real one.
 */
@OptIn(UnstableApi::class)
class PlayOrderTimeline private constructor(private val inner: Timeline, val order: IntArray) : Timeline() {
    private val inverse = IntArray(order.size).also { for ((shownAt, real) in order.withIndex()) it[real] = shownAt }
    /** Shown period index -> real period index, and back; each shown window's first period. */
    private val periodOrder: IntArray
    private val periodInverse = IntArray(inner.periodCount)
    private val firstPeriod = IntArray(order.size)

    init {
        val w = Window()
        val periods = ArrayList<Int>(inner.periodCount)
        for ((shownAt, real) in order.withIndex()) {
            inner.getWindow(real, w)
            firstPeriod[shownAt] = periods.size
            for (p in w.firstPeriodIndex..w.lastPeriodIndex) periods += p
        }
        periodOrder = periods.toIntArray()
        for ((shownAt, real) in periodOrder.withIndex()) periodInverse[real] = shownAt
    }

    fun shownIndexOf(realIndex: Int) = inverse[realIndex]

    override fun getWindowCount() = inner.windowCount

    override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
        inner.getWindow(order[windowIndex], window, defaultPositionProjectionUs)
        val periods = window.lastPeriodIndex - window.firstPeriodIndex
        window.firstPeriodIndex = firstPeriod[windowIndex]
        window.lastPeriodIndex = firstPeriod[windowIndex] + periods
        return window
    }

    override fun getPeriodCount() = inner.periodCount

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
        inner.getPeriod(periodOrder[periodIndex], period, setIds)
        period.windowIndex = inverse[period.windowIndex]
        return period
    }

    override fun getIndexOfPeriod(uid: Any): Int = inner.getIndexOfPeriod(uid).let { if (it == C.INDEX_UNSET) it else periodInverse[it] }

    override fun getUidOfPeriod(periodIndex: Int): Any = inner.getUidOfPeriod(periodOrder[periodIndex])

    companion object {
        /** [timeline] in its shuffled play order (first to last); null if that doesn't cover every song. */
        fun of(timeline: Timeline): PlayOrderTimeline? {
            if (timeline.periodCount == 0) return null
            val order = ArrayList<Int>(timeline.windowCount)
            var i = timeline.getFirstWindowIndex(true)
            while (i != C.INDEX_UNSET && order.size < timeline.windowCount) {
                order += i
                i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
            }
            if (order.size != timeline.windowCount || order.toSet().size != order.size) return null
            return PlayOrderTimeline(timeline, order.toIntArray())
        }
    }
}
