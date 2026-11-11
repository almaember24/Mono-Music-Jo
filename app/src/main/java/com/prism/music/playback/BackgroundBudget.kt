package com.prism.music.playback

import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * How much of the processor Prism's own heavy work may take while nobody needs it right now: song scans, video sync,
 * and Sing getting songs ready it wasn't asked for yet.
 *
 * A OnePlus 11 (#373's log, 1.8.1 and 1.8.1.1) had Prism closed three times by Android: "excessive cpu 295450 during
 * 300056", importance 400. Every five minutes Android looks at each process with no foreground service, and closes one
 * that used more than 25% of a core over the last five minutes while it has been in the background for up to ten
 * minutes, 10% up to fifteen, 2% after that ([ANDROID_LIMITS]). Nothing stopped the work when the listener left.
 *
 * The rule, in plain words: on screen, as before (a slow phone keeps it to a core's worth, so the screen's own threads
 * have the rest). While music plays with Prism out of sight, a core's worth at most: Android doesn't count a playing
 * app against it, but a phone in a pocket shouldn't run two cores flat out for the next three songs. With nothing playing
 * and nothing on screen: a little ([AWAY_SHARE]) for a minute, so a song in hand can finish, and then nothing at all
 * until the listener is back. The work waits where it is, asleep, and carries on.
 *
 * Only the pacing is here; each kind of work calls [pace] between its steps (never holding the main thread).
 */
object BackgroundBudget {
    enum class Where { ON_SCREEN, PLAYING, AWAY }

    /** Android's own limits: (in the background for at most this long) to (share of a core over a five-minute look). */
    internal val ANDROID_LIMITS = listOf(10 * 60_000L to 0.25, 15 * 60_000L to 0.10, Long.MAX_VALUE to 0.02)
    internal const val ANDROID_LOOK_MS = 5 * 60_000L

    /** Share of one core the whole process may be using for work to go on; negative: no limit. */
    internal fun share(where: Where, awayForMs: Long, slowPhone: Boolean): Double = when (where) {
        Where.ON_SCREEN -> if (slowPhone) ONE_CORE else -1.0
        Where.PLAYING -> ONE_CORE
        Where.AWAY -> if (awayForMs < AWAY_GRACE_MS) AWAY_SHARE else 0.0
    }

    /**
     * How long to rest now (ms) for [cpuMs] of processor time over [wallMs] of real time to come down to [share]; 0 when
     * it's within it. [share] 0 is "not at all": [HOLD_MS] at a time, however little has been used.
     */
    internal fun restMs(cpuMs: Long, wallMs: Long, share: Double): Long = when {
        share < 0 -> 0
        share == 0.0 -> HOLD_MS
        else -> ((cpuMs / share) - wallMs).toLong().coerceIn(0, MAX_REST_MS)
    }

    /** The arithmetic, on its own clocks (a test runs it on made-up ones). */
    internal class Gauge(private val wallNow: () -> Long, private val cpuNow: () -> Long) {
        private var from = wallNow()
        private var cpuFrom = cpuNow()
        private var awaySince = -1L

        /** How long the caller should rest before going on. */
        @Synchronized fun rest(where: Where, slowPhone: Boolean): Long {
            val now = wallNow()
            if (where == Where.AWAY) { if (awaySince < 0) awaySince = now } else awaySince = -1
            val cpu = cpuNow()
            val rest = restMs(cpu - cpuFrom, now - from, share(where, if (awaySince < 0) 0 else now - awaySince, slowPhone))
            // A fresh look every few seconds, so what ran a while ago doesn't count for or against what runs now.
            if (now - from >= LOOK_MS) { from = now; cpuFrom = cpu }
            return rest
        }
    }

    /** Where Prism is now; set once by the app container. Until then, no limit. */
    @Volatile internal var where: () -> Where = { Where.ON_SCREEN }
    @Volatile internal var slowPhone: () -> Boolean = { false }

    private val gauge by lazy { Gauge({ SystemClock.elapsedRealtime() }, { Process.getElapsedCpuTime() }) }
    /** Work a scheduled job does (Android counts a job's process apart, and the job has its own limits). */
    private val inJob = ThreadLocal<Boolean>()
    @Volatile private var saidHeld = false

    /** Runs [block], which a scheduled job asked for, outside the budget. For blocking work on this thread. */
    fun <T> asJob(scheduled: Boolean, block: () -> T): T {
        if (!scheduled) return block()
        inJob.set(true)
        try { return block() } finally { inJob.remove() }
    }

    /**
     * Rests here while the work is over its share, for as long as [goOn] says the work is still wanted. Off the main
     * thread. Throws [InterruptedException] as any sleep does.
     */
    fun pace(goOn: () -> Boolean = { true }) {
        if (inJob.get() == true) return
        while (goOn()) {
            val place = where()
            val rest = gauge.rest(place, slowPhone())
            if (place != Where.AWAY) saidHeld = false
            if (rest <= 0) break
            if (place == Where.AWAY && !saidHeld) {
                saidHeld = true
                AudioDiagnostics.log("background", "nothing playing and nothing on screen: song scans and the like go slowly, then wait")
                Log.i(TAG, "out of sight with nothing playing: waiting")
            }
            Thread.sleep(rest)
        }
    }

    private const val TAG = "PrismBudget"
    private const val ONE_CORE = 1.0
    /** Out of sight with nothing playing: well under Android's lowest limit that can apply in the first minutes. */
    internal const val AWAY_SHARE = 0.15
    /** For this long after the listener left; then the work waits. */
    internal const val AWAY_GRACE_MS = 60_000L
    /** How long held work sleeps between looks: long enough to cost nothing. */
    internal const val HOLD_MS = 5_000L
    private const val MAX_REST_MS = 5_000L
    private const val LOOK_MS = 5_000L
}
