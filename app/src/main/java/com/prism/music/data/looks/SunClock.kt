package com.prism.music.data.looks

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sunrise and sunset for a Look schedule (1.7.3), worked out on the phone with no location: the time zone's main place
 * ([ZonePlaces]) stands in for where the phone is, which is close enough for "light by day, dark by night".
 */
object SunClock {
    /** Day or night: which of a schedule's two Looks is on. */
    enum class Part { DAY, NIGHT }

    /** A change from one part to the other at [at]. */
    data class Turn(val at: Instant, val to: Part)

    /**
     * Sunrise and sunset (as instants) on [date] at [lat], [lon], by NOAA's solar equations; a null means the sun doesn't
     * rise (polar night) or set (midnight sun) that day.
     */
    fun sunriseSunset(date: LocalDate, lat: Double, lon: Double): Pair<Instant?, Instant?> {
        val g = 2 * PI / 365 * (date.dayOfYear - 1)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) + 0.000907 * sin(2 * g) -
            0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val phi = Math.toRadians(lat)
        val x = cos(Math.toRadians(90.833)) / (cos(phi) * cos(decl)) - tan(phi) * tan(decl)
        if (x > 1 || x < -1) return null to null
        val ha = Math.toDegrees(acos(x))
        val midnight = date.atStartOfDay(ZoneId.of("UTC")).toInstant()
        fun at(minutes: Double) = midnight.plusSeconds((minutes * 60).toLong())
        return at(720 - 4 * (lon + ha) - eqTime) to at(720 - 4 * (lon - ha) - eqTime)
    }

    /** Whether the sun stays up all day on [date] (midnight sun) rather than down (polar night). Only asked when it doesn't rise or set. */
    private fun sunUpAllDay(date: LocalDate, lat: Double): Boolean {
        val g = 2 * PI / 365 * (date.dayOfYear - 1)
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g)
        return (lat > 0) == (decl > 0)
    }

    /**
     * The turns around [now]: the last one before it (which part is on) and the next one after it. [sun] uses sunrise and
     * sunset at [place]; otherwise [dayAt] and [nightAt], minutes after midnight in [zone].
     */
    fun around(now: Instant, zone: ZoneId, sun: Boolean, dayAt: Int, nightAt: Int, place: Pair<Double, Double>): Pair<Turn, Turn> {
        val today = now.atZone(zone).toLocalDate()
        val turns = (-2L..2L).flatMap { d ->
            val date = today.plusDays(d)
            if (sun) {
                val (rise, set) = sunriseSunset(date, place.first, place.second)
                if (rise == null || set == null) {
                    // No sunrise or sunset: the day is all one part, from its start.
                    listOf(Turn(date.atStartOfDay(zone).toInstant(), if (sunUpAllDay(date, place.first)) Part.DAY else Part.NIGHT))
                } else listOf(Turn(rise, Part.DAY), Turn(set, Part.NIGHT))
            } else listOf(
                Turn(ZonedDateTime.of(date, LocalTime.of(dayAt / 60 % 24, dayAt % 60), zone).toInstant(), Part.DAY),
                Turn(ZonedDateTime.of(date, LocalTime.of(nightAt / 60 % 24, nightAt % 60), zone).toInstant(), Part.NIGHT),
            )
        }.sortedBy { it.at }
        val last = turns.lastOrNull { !it.at.isAfter(now) } ?: turns.first()
        val next = turns.firstOrNull { it.at.isAfter(now) && it.to != last.to } ?: turns.last()
        return last to next
    }
}

/**
 * Each time zone's main place (latitude, longitude), from the tz database's zone1970.tab and its old names from
 * `backward` (public domain), in assets/looks/zones.txt. A zone that isn't listed is placed by its UTC offset.
 */
object ZonePlaces {
    @Volatile private var table: Map<String, String>? = null

    fun load(lines: Sequence<String>): Map<String, String> = lines
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { l -> l.split('\t').takeIf { it.size >= 2 }?.let { f -> f[0] to f.drop(1).joinToString("\t") } }
        .toMap()

    fun of(context: android.content.Context, zone: ZoneId): Pair<Double, Double> {
        val t = table ?: runCatching {
            context.assets.open("looks/zones.txt").bufferedReader().useLines { load(it) }
        }.getOrDefault(emptyMap()).also { table = it }
        return of(t, zone)
    }

    fun of(t: Map<String, String>, zone: ZoneId): Pair<Double, Double> {
        var id = zone.id
        repeat(4) {
            val row = t[id] ?: return@repeat
            if (row.startsWith("=")) { id = row.substring(1); return@repeat }
            val f = row.split('\t')
            val lat = f.getOrNull(0)?.toDoubleOrNull()
            val lon = f.getOrNull(1)?.toDoubleOrNull()
            if (lat != null && lon != null) return lat to lon
        }
        // Not listed: the longitude the zone's offset belongs to (15° an hour), at a middling northern latitude.
        val offset = zone.rules.getStandardOffset(Instant.now()).totalSeconds
        return 40.0 to offset / 240.0
    }
}
