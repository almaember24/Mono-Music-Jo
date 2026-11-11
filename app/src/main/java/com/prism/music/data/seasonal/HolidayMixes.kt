package com.prism.music.data.seasonal

import androidx.annotation.StringRes
import com.prism.music.R
import java.time.LocalDate
import java.time.MonthDay

/**
 * The seasons Prism makes holiday mixes for (1.8.0), the same windows as the seasonal Looks: Thanksgiving through
 * November, Christmas from the 1st to the 31st of December, in the phone's own time zone.
 */
enum class Holiday(@StringRes val section: Int) {
    THANKSGIVING(R.string.section_thanksgiving),
    CHRISTMAS(R.string.section_holiday_music);

    companion object {
        /** The season [date] falls in, or null outside both. */
        fun on(date: LocalDate): Holiday? = when (date.monthValue) {
            11 -> THANKSGIVING
            12 -> CHRISTMAS
            else -> null
        }
    }
}

/** What's drawn on a holiday mix's cover (simple shapes, drawn by Prism: no downloaded pictures). */
enum class Motif { LEAF, PUMPKIN, MUG, MOON, HEART, FOOTBALL, NOTE, WHEAT, ORNAMENT, STAR, SNOWFLAKE, TREE, GIFT, BELL, CANDY_CANE, MIC, BOLT, FLOWER, SNOWMAN, DISCO, SPARKLE, PINE }

/**
 * One of the mix's seed searches on YouTube Music (signed out). [query] is a search, not text anyone reads. A [playlist]
 * seed takes YouTube Music's own playlists for it (Featured), [take] of them; otherwise the Songs results. [weight]: how
 * much its songs count against the other seeds'. [lists]: words one of a playlist's title must have for it to be taken
 * (null: the query's own words, less the holiday's: "kids christmas" wants a kids' playlist, not "Christmas Hits").
 */
data class Seed(val query: String, val playlist: Boolean = false, val weight: Double = 1.0, val take: Int = 1, val lists: Set<String>? = null)

/**
 * A holiday mix: its name and line ([name], [blurb]), its cover ([top], [bottom], [motif]), the days it's shown, and
 * how its songs are found ([seeds]). [asks]: knock-off words the mix itself wants (Merry Lo-fi wants lo-fi);
 * [holidayOnly]: a song found by a search must sound like the holiday (its title or album), as a Christmas mix should;
 * [noHoliday]: no Christmas songs at all (November's mixes, Winter Chill).
 */
data class HolidayMix(
    val key: String,
    val holiday: Holiday,
    @StringRes val name: Int,
    @StringRes val blurb: Int,
    val top: Int,
    val bottom: Int,
    val motif: Motif,
    val seeds: List<Seed>,
    val asks: Set<String> = emptySet(),
    val holidayOnly: Boolean = false,
    val noHoliday: Boolean = false,
    val from: MonthDay = if (holiday == Holiday.THANKSGIVING) MonthDay.of(11, 1) else MonthDay.of(12, 1),
    val to: MonthDay = if (holiday == Holiday.THANKSGIVING) MonthDay.of(11, 30) else MonthDay.of(12, 31),
) {
    /** Its id as a mix (pages, queues, pins): "season:<key>". */
    val id: String get() = HolidayMixes.PREFIX + key

    fun shownOn(date: LocalDate): Boolean {
        val d = MonthDay.from(date)
        return Holiday.on(date) == holiday && !d.isBefore(from) && !d.isAfter(to)
    }
}

/**
 * Prism's holiday mixes, presented as Spotify's and Apple Music's seasonal hubs are: the standards, and around them
 * the season in every style (soul, lo-fi, jazz, country, hip-hop, rock, Latin, kids', a party, a quiet winter one).
 */
object HolidayMixes {
    const val PREFIX = "season:"

    fun isId(id: String) = id.startsWith(PREFIX)

    fun byId(id: String): HolidayMix? = all.firstOrNull { it.id == id }

    /** The mixes shown on [date], in their order on Home. */
    fun on(date: LocalDate): List<HolidayMix> = all.filter { it.shownOn(date) }

    private fun c(v: Long) = v.toInt()

    /** Knock-off words a lo-fi mix wants, and the "artist" words its uploaders go by. */
    private val LOFI = setOf("lofi", "lo fi", "beats", "chill")
    private val JAZZ = setOf("jazz", "swing", "crooners", "lounge", "big band")
    private val LATIN = setOf("navidad", "navidena", "latina", "latin", "fiesta", "villancicos", "aguinaldos", "parranda", "salsa", "feliz")
    private val PARTY = setOf("party", "dance", "upbeat", "fun", "rockin", "bangers")

    val all: List<HolidayMix> = listOf(
        // ------------------------------------------------------------------------------------------- November
        HolidayMix(
            "thanksgiving_dinner", Holiday.THANKSGIVING, R.string.holiday_thanksgiving_dinner, R.string.holiday_thanksgiving_dinner_blurb,
            c(0xFF7B7B7B), c(0xFF2F2F2F), Motif.PUMPKIN,
            listOf(
                Seed("motown classics", playlist = true, weight = 1.3), Seed("motown hits"), Seed("classic soul hits"),
                Seed("70s soul classics", playlist = true), Seed("soul classics", playlist = true),
            ),
            noHoliday = true,
        ),
        HolidayMix(
            "friendsgiving", Holiday.THANKSGIVING, R.string.holiday_friendsgiving, R.string.holiday_friendsgiving_blurb,
            c(0xFFACACAC), c(0xFF4E4E4E), Motif.HEART,
            listOf(
                Seed("feel good pop", playlist = true, weight = 1.2), Seed("feel good indie", playlist = true, lists = setOf("indie", "alternative", "happy", "feel")),
                Seed("feel good indie pop songs"), Seed("happy pop hits"), Seed("good vibes", playlist = true),
            ),
            noHoliday = true,
        ),
        HolidayMix(
            "cozy_fall_acoustic", Holiday.THANKSGIVING, R.string.holiday_cozy_fall_acoustic, R.string.holiday_cozy_fall_acoustic_blurb,
            c(0xFF828282), c(0xFF2E2E2E), Motif.MUG,
            listOf(
                Seed("sweater weather", playlist = true, weight = 1.3), Seed("acoustic hits", playlist = true), Seed("coffeehouse acoustic", playlist = true),
                Seed("indie folk", playlist = true), Seed("autumn acoustic songs"), Seed("indie folk acoustic"),
            ),
            noHoliday = true,
        ),
        HolidayMix(
            "autumn_lofi", Holiday.THANKSGIVING, R.string.holiday_autumn_lofi, R.string.holiday_autumn_lofi_blurb,
            c(0xFF9B9B9B), c(0xFF282828), Motif.LEAF,
            listOf(Seed("autumn lofi", playlist = true, weight = 1.3), Seed("jazzy lofi", playlist = true), Seed("autumn lofi"), Seed("lofi hip hop chill beats")),
            asks = LOFI, noHoliday = true,
        ),
        HolidayMix(
            "gratitude", Holiday.THANKSGIVING, R.string.holiday_gratitude, R.string.holiday_gratitude_blurb,
            c(0xFFA6A6A6), c(0xFF3D3D3D), Motif.SPARKLE,
            listOf(
                Seed("gospel classics", playlist = true, weight = 1.2), Seed("thankful gospel songs"), Seed("gratitude gospel"),
                Seed("soul gospel", playlist = true), Seed("praise and worship gospel"),
            ),
            noHoliday = true,
        ),
        HolidayMix(
            "football_sunday", Holiday.THANKSGIVING, R.string.holiday_football_sunday, R.string.holiday_football_sunday_blurb,
            c(0xFF797979), c(0xFF242424), Motif.FOOTBALL,
            listOf(
                Seed("stadium rock anthems", playlist = true, weight = 1.3), Seed("hip hop workout hype", playlist = true),
                Seed("pump up", playlist = true, lists = setOf("pump", "hype", "energy", "workout", "anthems")), Seed("rock anthems"), Seed("hip hop anthems"),
            ),
            noHoliday = true,
        ),
        HolidayMix(
            "fall_jazz", Holiday.THANKSGIVING, R.string.holiday_fall_jazz, R.string.holiday_fall_jazz_blurb,
            c(0xFF5F5F5F), c(0xFF1A1A1A), Motif.NOTE,
            listOf(
                Seed("autumn jazz", playlist = true, weight = 1.3), Seed("jazz classics", playlist = true),
                Seed("autumn leaves jazz"), Seed("cozy jazz standards"), Seed("jazz piano trio"),
            ),
            noHoliday = true,
        ),
        HolidayMix(
            "harvest_country", Holiday.THANKSGIVING, R.string.holiday_harvest_country, R.string.holiday_harvest_country_blurb,
            c(0xFFA2A2A2), c(0xFF383838), Motif.WHEAT,
            listOf(
                Seed("country fall", playlist = true, weight = 1.3), Seed("country bonfire", playlist = true),
                Seed("country back roads songs"), Seed("classic country hits"), Seed("country classics", playlist = true),
            ),
            noHoliday = true,
        ),
        // ------------------------------------------------------------------------------------------- December
        HolidayMix(
            "christmas_classics", Holiday.CHRISTMAS, R.string.holiday_christmas_classics, R.string.holiday_christmas_classics_blurb,
            c(0xFF555555), c(0xFF202020), Motif.ORNAMENT,
            listOf(
                Seed("christmas classics", playlist = true, weight = 1.4, take = 2, lists = setOf("classic", "hits", "essential", "oldies", "crooners")),
                Seed("christmas classics"), Seed("christmas songs 1950s 1960s"), Seed("holiday classics", playlist = true, lists = setOf("classic", "hits", "oldies")),
            ),
            holidayOnly = true,
        ),
        HolidayMix(
            "christmas_pop_hits", Holiday.CHRISTMAS, R.string.holiday_christmas_pop_hits, R.string.holiday_christmas_pop_hits_blurb,
            c(0xFF7A7A7A), c(0xFF292929), Motif.STAR,
            listOf(Seed("christmas pop", playlist = true, weight = 1.4, take = 2), Seed("christmas pop hits"), Seed("new christmas pop songs")),
            holidayOnly = true,
        ),
        HolidayMix(
            "cozy_christmas_acoustic", Holiday.CHRISTMAS, R.string.holiday_cozy_christmas_acoustic, R.string.holiday_cozy_christmas_acoustic_blurb,
            c(0xFF666666), c(0xFF1F1F1F), Motif.MUG,
            listOf(Seed("acoustic christmas", playlist = true, weight = 1.4, take = 2), Seed("acoustic christmas"), Seed("christmas indie folk")),
            holidayOnly = true,
        ),
        HolidayMix(
            "merry_lofi", Holiday.CHRISTMAS, R.string.holiday_merry_lofi, R.string.holiday_merry_lofi_blurb,
            c(0xFF6B6B6B), c(0xFF1B1B1B), Motif.MOON,
            listOf(Seed("lofi christmas", playlist = true, weight = 1.4, take = 2), Seed("christmas lofi"), Seed("lofi christmas beats"),
                Seed("christmas lofi hip hop"), Seed("chill christmas beats"),
            ),
            asks = LOFI, holidayOnly = true,
        ),
        HolidayMix(
            "christmas_jazz", Holiday.CHRISTMAS, R.string.holiday_christmas_jazz, R.string.holiday_christmas_jazz_blurb,
            c(0xFF424242), c(0xFF171717), Motif.NOTE,
            listOf(Seed("christmas jazz", playlist = true, weight = 1.4, lists = JAZZ), Seed("jazz christmas", playlist = true, weight = 1.2, lists = JAZZ),
                Seed("christmas crooners", playlist = true, lists = JAZZ),
                Seed("christmas jazz standards"), Seed("christmas swing"), Seed("a charlie brown christmas"),
            ),
            holidayOnly = true,
        ),
        HolidayMix(
            "rnb_christmas", Holiday.CHRISTMAS, R.string.holiday_rnb_christmas, R.string.holiday_rnb_christmas_blurb,
            c(0xFF4F4F4F), c(0xFF161616), Motif.MIC,
            listOf(Seed("r&b christmas", playlist = true, weight = 1.4, take = 2), Seed("r&b christmas songs"), Seed("soul christmas")),
            holidayOnly = true,
        ),
        HolidayMix(
            "hip_hop_christmas", Holiday.CHRISTMAS, R.string.holiday_hip_hop_christmas, R.string.holiday_hip_hop_christmas_blurb,
            c(0xFF6C6C6C), c(0xFF1B1B1B), Motif.GIFT,
            listOf(Seed("hip hop christmas", playlist = true, weight = 1.4, take = 2), Seed("hip hop christmas"), Seed("rap christmas songs")),
            holidayOnly = true,
        ),
        HolidayMix(
            "country_christmas", Holiday.CHRISTMAS, R.string.holiday_country_christmas, R.string.holiday_country_christmas_blurb,
            c(0xFF5F5F5F), c(0xFF202020), Motif.PINE,
            listOf(Seed("country christmas", playlist = true, weight = 1.4, take = 2), Seed("country christmas"), Seed("classic country christmas")),
            holidayOnly = true,
        ),
        HolidayMix(
            "rock_christmas", Holiday.CHRISTMAS, R.string.holiday_rock_christmas, R.string.holiday_rock_christmas_blurb,
            c(0xFF2B2B2B), c(0xFF3D3D3D), Motif.BOLT,
            listOf(Seed("rock christmas", playlist = true, weight = 1.4, take = 2, lists = setOf("rock", "punk", "metal", "twisted", "alternative")),
                Seed("rock christmas"), Seed("punk christmas"), Seed("metal christmas"),
            ),
            holidayOnly = true,
        ),
        HolidayMix(
            "navidad_latina", Holiday.CHRISTMAS, R.string.holiday_navidad_latina, R.string.holiday_navidad_latina_blurb,
            c(0xFF686868), c(0xFF3A3A3A), Motif.FLOWER,
            listOf(
                Seed("navidad", playlist = true, take = 2, lists = LATIN), Seed("fiesta navideña", playlist = true, lists = LATIN), Seed("villancicos", weight = 1.2),
                Seed("navidad salsa", weight = 1.2), Seed("aguinaldos navideños", weight = 1.2), Seed("canciones de navidad", weight = 1.2),
            ),
            holidayOnly = true,
        ),
        HolidayMix(
            "kids_christmas", Holiday.CHRISTMAS, R.string.holiday_kids_christmas, R.string.holiday_kids_christmas_blurb,
            c(0xFF8D8D8D), c(0xFF383838), Motif.SNOWMAN,
            listOf(Seed("kids christmas", playlist = true, weight = 1.4, take = 2, lists = setOf("kids", "family", "children", "disney", "sing along")),
                Seed("christmas songs for kids"), Seed("disney christmas"), Seed("christmas sing along"),
            ),
            holidayOnly = true,
        ),
        HolidayMix(
            "christmas_party", Holiday.CHRISTMAS, R.string.holiday_christmas_party, R.string.holiday_christmas_party_blurb,
            c(0xFFB2B2B2), c(0xFF383838), Motif.DISCO,
            listOf(Seed("upbeat christmas", playlist = true, weight = 1.3, lists = PARTY), Seed("christmas dance party", playlist = true, weight = 1.2, lists = PARTY),
                Seed("fun christmas", playlist = true, lists = PARTY), Seed("christmas party", playlist = true, lists = PARTY),
                Seed("christmas dance hits"), Seed("christmas remix"), Seed("christmas party songs"),
            ),
            asks = setOf("remix"), holidayOnly = true,
        ),
        HolidayMix(
            "winter_chill", Holiday.CHRISTMAS, R.string.holiday_winter_chill, R.string.holiday_winter_chill_blurb,
            c(0xFFB0B0B0), c(0xFF323232), Motif.SNOWFLAKE,
            listOf(
                Seed("cold weather", playlist = true, weight = 1.3), Seed("winter", playlist = true, take = 2, lists = setOf("winter", "snow", "cold", "frost")),
                Seed("cozy winter indie"), Seed("winter indie folk"), Seed("snowy day songs"), Seed("winter songs"),
            ),
            noHoliday = true,
        ),
        HolidayMix(
            "new_years_eve", Holiday.CHRISTMAS, R.string.holiday_new_years_eve, R.string.holiday_new_years_eve_blurb,
            c(0xFF1F1F1F), c(0xFF878787), Motif.SPARKLE,
            listOf(
                Seed("new year's eve", playlist = true, weight = 1.4, take = 2), Seed("new years eve party songs"),
                Seed("party anthems", playlist = true), Seed("throwback party", playlist = true, lists = setOf("party")), Seed("countdown songs"),
            ),
            asks = setOf("remix"), noHoliday = true, from = MonthDay.of(12, 26),
        ),
    )
}
