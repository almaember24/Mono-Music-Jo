package com.prism.music.data

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.trp
import com.prism.music.data.db.PlayEventEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** When you listen: by hour and weekday, your longest run of days, and your biggest day. */
class Habits(
    /** Milliseconds listened in each hour of the day, 0 = midnight. */
    val byHour: LongArray,
    /** Milliseconds listened on each weekday, 0 = Monday. */
    val byWeekday: LongArray,
    val longestStreak: Int,
    val biggestDay: LocalDate?,
    val biggestDayMs: Long,
) {
    val peakHour: Int get() = byHour.indices.maxByOrNull { byHour[it] } ?: 20
    val topWeekday: Int get() = byWeekday.indices.maxByOrNull { byWeekday[it] } ?: 5
    private val total: Long get() = byHour.sum().coerceAtLeast(1)
    /** Share of listening between 10 PM and 4 AM. */
    val nightShare: Double get() = (listOf(22, 23, 0, 1, 2, 3).sumOf { byHour[it] }).toDouble() / total
    /** Share of listening between 5 and 9 AM. */
    val morningShare: Double get() = ((5..8).sumOf { byHour[it] }).toDouble() / total
}

@kotlinx.serialization.Serializable
data class Personality(val name: String, val line: String)

object ReplayStats {
    val weekdays = listOf(tr(R.string.monday), tr(R.string.tuesday), tr(R.string.wednesday), tr(R.string.thursday), tr(R.string.friday), tr(R.string.saturday), tr(R.string.sunday))

    fun hourLabel(h: Int): String = when (h) {
        0 -> tr(R.string.t_12_am); 12 -> tr(R.string.t_12_pm)
        in 1..11 -> "$h AM"
        else -> "${h - 12} PM"
    }

    fun habits(events: List<PlayEventEntity>, zone: ZoneId = ZoneId.systemDefault()): Habits {
        val byHour = LongArray(24)
        val byWeekday = LongArray(7)
        val byDay = HashMap<LocalDate, Long>()
        for (e in events) {
            val t = Instant.ofEpochMilli(e.timestamp).atZone(zone)
            byHour[t.hour] += e.playedMs
            byWeekday[t.dayOfWeek.value - 1] += e.playedMs
            byDay.merge(t.toLocalDate(), e.playedMs, Long::plus)
        }
        var longest = 0
        var run = 0
        var prev: LocalDate? = null
        for (d in byDay.keys.sorted()) {
            run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1
            longest = maxOf(longest, run)
            prev = d
        }
        val biggest = byDay.maxByOrNull { it.value }
        return Habits(byHour, byWeekday, longest, biggest?.key, biggest?.value ?: 0)
    }

    /**
     * Your listening personality: whichever of these describes you most strongly,
     * each measured against what would make it stand out.
     */
    fun personality(
        totalMs: Long,
        topFiveArtistsMs: Long,
        artistCount: Int,
        newArtistCount: Int?,
        plays: Int,
        songCount: Int,
        topSongPlays: Int,
        topSongTitle: String?,
        activeDays: Int,
        topGenreShare: Double?,
        habits: Habits,
    ): Personality {
        val total = totalMs.coerceAtLeast(1).toDouble()
        val loyal = topFiveArtistsMs / total
        val minutesPerDay = totalMs / 60_000.0 / activeDays.coerceAtLeast(1)
        val candidates = buildList {
            add(Triple(loyal / 0.55, tr(R.string.the_loyalist), tr(R.string.you_know_what_you_love_your, (loyal * 100).toInt())))
            if (newArtistCount != null && artistCount > 0) {
                val share = newArtistCount.toDouble() / artistCount
                add(Triple(share / 0.35, tr(R.string.the_explorer), trp(R.plurals.explorer_n_of_artists, artistCount, newArtistCount, artistCount)))
            }
            if (songCount > 0) add(Triple(plays.toDouble() / songCount / 4.0, tr(R.string.the_replayer),
                tr(R.string.when_a_song_hits_you_run, topSongTitle?.let { trp(R.plurals.alone_got_n_plays, topSongPlays, it, topSongPlays) } ?: "").trim()))
            add(Triple(habits.nightShare / 0.35, tr(R.string.the_night_owl), tr(R.string.your_music_comes_alive_after_dark, (habits.nightShare * 100).toInt())))
            add(Triple(habits.morningShare / 0.30, tr(R.string.the_early_riser), tr(R.string.you_start_the_day_with_a, (habits.morningShare * 100).toInt())))
            add(Triple(minutesPerDay / 150.0, tr(R.string.the_marathoner), trp(R.plurals.marathon_n_minutes, minutesPerDay.toInt())))
            if (topGenreShare != null) add(Triple((1 - topGenreShare) / 0.65, tr(R.string.the_shapeshifter), tr(R.string.no_single_sound_could_hold_you)))
        }
        val best = candidates.maxByOrNull { it.first } ?: return Personality(tr(R.string.the_listener), tr(R.string.every_play_counted))
        return Personality(best.second, best.third)
    }
}
