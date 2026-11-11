package com.prism.music.data

import com.prism.music.R
import com.prism.music.data.prefs.BehaviorPrefs
import com.prism.music.tr

/**
 * When a song counts as played (#263, 1.8.1): how long it has to be heard before it's kept in Replay and History. Until
 * 1.8.1 that was always ten seconds, and it stays the default; YouTube Music's own history was told as a song started.
 */
object PlayCount {
    /** Seconds to choose from (0: any play at all, however short). */
    val CHOICES = listOf(0, 5, 10, 30, 60)
    const val DEFAULT_SEC = 10

    /** Whether [heardMs] of a song is a play under [seconds]. */
    fun counts(heardMs: Long, seconds: Int): Boolean = if (seconds <= 0) heardMs > 0 else heardMs >= seconds * 1000L

    /**
     * How long a song is heard before YouTube Music's history is told of it: at once (as before 1.8.1), or, with "Same
     * for your YouTube Music history" on, when it counts here too ([counts]), so a song skipped at once is in neither.
     */
    fun accountAfterMs(prefs: BehaviorPrefs): Long = if (prefs.playCountOnAccount) prefs.playCountSeconds.coerceAtLeast(0) * 1000L else 0L

    /** "Any time", "5 s", "1 min". */
    fun label(seconds: Int): String = when {
        seconds <= 0 -> tr(R.string.hist_count_any)
        seconds % 60 == 0 -> tr(R.string.hist_count_minutes, seconds / 60)
        else -> tr(R.string.hist_count_seconds, seconds)
    }
}

/**
 * Pausing history (#296, 1.8.1): for a while, or until it's turned back on, nothing Prism keeps of what you play or
 * search is written: no plays for Replay, Rewind and History, no "last played" for Recently played, no recent searches
 * or "Recently opened", and (signed in) no plays sent to your YouTube Music history. Like Spotify's private session,
 * Last.fm gets no scrobbles or "now playing" meanwhile, and skipped suggestions aren't learned from: a pause leaves no
 * trace anywhere. Likes are still likes (and loved on Last.fm when that's on): they're something you did on purpose.
 * What's already kept stays.
 */
object HistoryPause {
    /** Paused until turned back on. */
    const val FOREVER = Long.MAX_VALUE

    enum class Choice(val hours: Int) {
        OFF(0), HOUR(1), DAY(24), UNTIL_ON(-1);

        val label: String get() = when (this) {
            OFF -> tr(R.string.off)
            HOUR -> tr(R.string.hist_pause_hour)
            DAY -> tr(R.string.hist_pause_day)
            UNTIL_ON -> tr(R.string.hist_pause_until_on)
        }
    }

    /** The time a [choice] made at [now] pauses history until (0: not paused). */
    fun until(choice: Choice, now: Long): Long = when (choice) {
        Choice.OFF -> 0L
        Choice.UNTIL_ON -> FOREVER
        else -> now + choice.hours * 3_600_000L
    }

    fun isPaused(until: Long, now: Long = System.currentTimeMillis()): Boolean = until > now

    /** Which choice [until] reads as now (a timed pause shows as the shortest choice that still covers what's left). */
    fun choiceOf(until: Long, now: Long = System.currentTimeMillis()): Choice = when {
        !isPaused(until, now) -> Choice.OFF
        until == FOREVER -> Choice.UNTIL_ON
        until - now <= Choice.HOUR.hours * 3_600_000L -> Choice.HOUR
        else -> Choice.DAY
    }

    /** Whether [prefs] pause history now. */
    fun paused(prefs: BehaviorPrefs, now: Long = System.currentTimeMillis()): Boolean = isPaused(prefs.historyPausedUntil, now)

    /**
     * Whether a play heard for [heardMs] is kept in Prism's history: not paused, and long enough ([PlayCount]). Podcast
     * episodes never are (Replay is about music).
     */
    fun keepsPlay(prefs: BehaviorPrefs, heardMs: Long, episode: Boolean, now: Long = System.currentTimeMillis()): Boolean =
        !episode && !paused(prefs, now) && PlayCount.counts(heardMs, prefs.playCountSeconds)
}
