package com.prism.music.data

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.data.db.AlbumPlays
import com.prism.music.data.db.ArtistPlays
import com.prism.music.data.db.FirstPlay
import com.prism.music.data.db.MonthTotal
import com.prism.music.data.db.SongPlays
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** When each year's Rewind is: the year's bounds on the phone's own clock, and when the current year's opens. */
object RewindCalendar {
    /** The current year's Rewind opens on this day (earlier years' any time). */
    const val OPENS_MONTH = 12
    const val OPENS_DAY = 1

    /**
     * [year] from its first millisecond to its last, in [zone] (midnight to midnight there, whatever daylight saving
     * does; a zone with no midnight on 1 January gets the first moment that day has).
     */
    fun bounds(year: Int, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
        val start = LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return start to end
    }

    fun yearOf(ms: Long, zone: ZoneId = ZoneId.systemDefault()): Int = Instant.ofEpochMilli(ms).atZone(zone).year

    /** When [year]'s Rewind opens: 1 December, midnight, in [zone]. */
    fun opensAt(year: Int, zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDate.of(year, OPENS_MONTH, OPENS_DAY).atStartOfDay(zone).toInstant().toEpochMilli()

    /** Finished years are always open; the current one from 1 December ([early]: now, for trying it out). */
    fun isOpen(year: Int, now: Long, zone: ZoneId = ZoneId.systemDefault(), early: Boolean = false): Boolean {
        val current = yearOf(now, zone)
        return year < current || (year == current && (early || now >= opensAt(year, zone)))
    }

    /** Every year with listening, newest first, from the first play's year to now (gaps included: a quiet year is a short story). */
    fun years(firstEventMs: Long?, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Int> {
        val first = firstEventMs?.let { yearOf(it, zone) } ?: return emptyList()
        val current = yearOf(now, zone)
        return (current downTo first.coerceAtMost(current)).toList()
    }

    /** "January–March"… for a year's four seasons (by months: hemispheres disagree on the names). */
    val quarters = listOf(tr(R.string.january_march) to 1, tr(R.string.april_june) to 4, tr(R.string.july_september) to 7, tr(R.string.october_december) to 10)

    fun quarterBounds(year: Int, q: Int, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
        val month = quarters[q].second
        val start = LocalDate.of(year, month, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(year, month, 1).plusMonths(3).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return start to end
    }
}

/** A season's sound: its top genre and song. */
@Serializable
data class SeasonGenre(val label: String, val genre: String?, val ms: Long, val topSong: String?)

@Serializable
data class GenreShare(val genre: String, val ms: Long)

/** The year as Prism's own features saw it. */
@Serializable
data class PrismYear(
    val sung: Int = 0,
    val djSongs: Int = 0,
    val smartPicks: Int = 0,
    val recognized: Int = 0,
    val blends: Int = 0,
    val radarFinds: Int = 0,
) {
    val any: Boolean get() = sung + djSongs + smartPicks + recognized + blends + radarFinds > 0
}

/**
 * A year's Rewind, everything its story shows. Finished years are worked out once and kept ([RewindStore], in the
 * backup), so they open at once and stay as they were.
 */
@Serializable
data class RewindSnapshot(
    val version: Int = VERSION,
    val year: Int,
    val frozenAt: Long,
    val totalMs: Long,
    val previousMs: Long? = null,
    val previousTopArtist: String? = null,
    val previousSongCount: Int? = null,
    val plays: Int,
    val activeDays: Int,
    val songCount: Int,
    val artistCount: Int,
    val songs: List<SongPlays>,
    val artists: List<ArtistPlays>,
    val genres: List<GenreShare>,
    /** Each top genre's most-played song (its id). */
    val genreSongs: Map<String, String> = emptyMap(),
    val byHour: List<Long>,
    val byWeekday: List<Long>,
    val longestStreak: Int,
    val biggestDay: String? = null,
    val biggestDayMs: Long = 0,
    val newArtists: List<ArtistPlays>? = null,
    val personality: Personality,
    val topArtistImage: String? = null,
    val months: List<MonthTotal> = emptyList(),
    val topAlbum: AlbumPlays? = null,
    val firstSong: FirstPlay? = null,
    val seasons: List<SeasonGenre> = emptyList(),
    val prism: PrismYear = PrismYear(),
    /** A line from the #1 song, where its best part starts. */
    val topLyric: String? = null,
) {
    fun habits() = Habits(byHour.toLongArray(), byWeekday.toLongArray(), longestStreak, biggestDay?.let { LocalDate.parse(it) }, biggestDayMs)

    companion object { const val VERSION = 1 }
}

/** Kept Rewinds of finished years: `files/rewind/<year>.json`. */
class RewindStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(year: Int): RewindSnapshot? = runCatching {
        File(dir, "$year.json").takeIf { it.isFile }?.readText()?.let { json.decodeFromString(RewindSnapshot.serializer(), it) }
    }.getOrNull()?.takeIf { it.version == RewindSnapshot.VERSION && it.year == year }

    fun save(s: RewindSnapshot) {
        dir.mkdirs()
        val f = File(dir, "${s.year}.json")
        val tmp = File(dir, "${s.year}.json.tmp")
        tmp.writeText(json.encodeToString(RewindSnapshot.serializer(), s))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun years(): List<Int> = dir.listFiles()?.mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }.orEmpty()
}

/** The parts of a Replay or Rewind story. */
enum class StoryPart(val seconds: Int) {
    INTRO(6), MINUTES(9), SEASONS(9), GENRES(8), TOP_ARTIST(9), TOP_ARTISTS(8), TOP_SONG(10), TOP_SONGS(8), TOP_ALBUM(8),
    MONTHS(8), FIRST_SONG(8), HABITS(9), DISCOVERY(8), PRISM_YEAR(9), YEAR_COMPARE(8), PERSONALITY(9), SUMMARY(0),
}

/** What a story has to go on, for [StoryParts.pick]. */
data class StoryFacts(
    val rewind: Boolean,
    val totalMs: Long,
    val plays: Int,
    val activeDays: Int,
    val songs: Int,
    val artists: Int,
    val genres: Int,
    val seasonsWithGenre: Int = 0,
    val album: AlbumPlays? = null,
    val monthsWithListening: Int = 0,
    val firstSong: Boolean = false,
    val newArtists: Int = 0,
    val prism: Boolean = false,
    val previous: Boolean = false,
)

object StoryParts {
    /** Under this, the story is a short one: the total, the top song and artist, and the summary. */
    const val LIGHT_MS = 30 * 60_000L
    const val LIGHT_PLAYS = 15

    /** Which parts a story has: each only when there's enough to make it worth a slide. */
    fun pick(f: StoryFacts): List<StoryPart> = buildList {
        add(StoryPart.INTRO)
        add(StoryPart.MINUTES)
        val light = f.totalMs < LIGHT_MS || f.plays < LIGHT_PLAYS
        if (light) {
            if (f.artists > 0) add(StoryPart.TOP_ARTIST)
            if (f.songs > 0) add(StoryPart.TOP_SONG)
            add(StoryPart.SUMMARY)
            return@buildList
        }
        if (f.rewind && f.seasonsWithGenre >= 2) add(StoryPart.SEASONS)
        if (f.genres > 0) add(StoryPart.GENRES)
        if (f.artists > 0) add(StoryPart.TOP_ARTIST)
        if (f.artists >= 3) add(StoryPart.TOP_ARTISTS)
        if (f.songs > 0) add(StoryPart.TOP_SONG)
        if (f.songs >= 3) add(StoryPart.TOP_SONGS)
        if (f.rewind && f.album != null && f.album.songs >= 2 && f.album.plays >= 5) add(StoryPart.TOP_ALBUM)
        if (f.rewind && f.monthsWithListening >= 3) add(StoryPart.MONTHS)
        if (f.rewind && f.firstSong) add(StoryPart.FIRST_SONG)
        if (f.activeDays >= 5) add(StoryPart.HABITS)
        if (f.newArtists > 0) add(StoryPart.DISCOVERY)
        if (f.rewind && f.prism) add(StoryPart.PRISM_YEAR)
        if (f.rewind && f.previous) add(StoryPart.YEAR_COMPARE)
        if (f.activeDays >= 5) add(StoryPart.PERSONALITY)
        add(StoryPart.SUMMARY)
    }
}
