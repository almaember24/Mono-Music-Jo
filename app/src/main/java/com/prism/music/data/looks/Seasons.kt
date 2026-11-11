package com.prism.music.data.looks

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId

/**
 * Prism's seasonal Looks (1.8.0): Harvest for Thanksgiving in November, Holiday for Christmas in December. Each is only
 * offered in its own month, in the phone's own time zone; outside it its Look, its light and its background are out of
 * the pickers, and a listener still on it at the turn goes back to the Look they had before ([LookStore.endSeasons]).
 */
enum class Season(
    /** Its built-in Look's id ([BuiltInLooks]). */
    val lookId: String,
    val month: Month,
    /** Its background's name (`Backdrop`). */
    val backdrop: String,
    /** Its split light's preset key (`SpectrumPresets`). */
    val light: String,
) {
    HARVEST("harvest", Month.NOVEMBER, "HARVEST", "HARVEST"),
    HOLIDAY("holiday", Month.DECEMBER, "HOLIDAY", "HOLIDAY");

    /** Whether [date] (a local date) is in this season. */
    fun contains(date: LocalDate): Boolean = date.month == month

    /** The year of this season's last month before [date] (it may be [date]'s own year or the year before). */
    fun lastYearBefore(date: LocalDate): Int = if (date.monthValue > month.value) date.year else date.year - 1
}

object Seasons {
    /** The clock seasons are read from; tests set a fixed one (null: the phone's clock and time zone, read each time). */
    @Volatile var clock: Clock? = null

    /** Today, on the phone's clock in its own time zone (or the test's [clock]). */
    fun today(): LocalDate = LocalDate.now(clock ?: Clock.systemDefaultZone())

    /** The local date at [instant] in [zone]. */
    fun dateAt(instant: Instant, zone: ZoneId): LocalDate = instant.atZone(zone).toLocalDate()

    /** The season [date] is in, if any. */
    fun current(date: LocalDate = today()): Season? = Season.entries.firstOrNull { it.contains(date) }

    /** The season whose Look is [lookId], if it's a seasonal one. */
    fun of(lookId: String?): Season? = Season.entries.firstOrNull { it.lookId == lookId }

    /** The season whose background is [backdrop] (a `Backdrop` name), if it's a seasonal one. */
    fun ofBackdrop(backdrop: String?): Season? = Season.entries.firstOrNull { it.backdrop == backdrop }

    /** The season whose light preset is [key], if it's a seasonal one. */
    fun ofLight(key: String?): Season? = Season.entries.firstOrNull { it.light == key }

    /** Whether the Look [lookId] may be shown on [date]: every Look but a seasonal one out of its month. */
    fun shown(lookId: String, date: LocalDate = today()): Boolean = of(lookId)?.contains(date) ?: true

    /** The seasons over by [date], each with the key of its last turn ("HARVEST:2026"), so each ends only once. */
    fun ended(date: LocalDate): List<Pair<Season, String>> =
        Season.entries.filter { !it.contains(date) }.map { it to "${it.name}:${it.lastYearBefore(date)}" }
}
