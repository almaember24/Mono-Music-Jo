package com.prism.music.data.imports

import com.prism.music.data.innertube.SearchRank

/**
 * How imported songs are named and compared (1.8.1): a song's key from its title and main artist (so the same song from
 * Spotify, Last.fm and a playlist is one song), its title without the brackets and tails that don't name the recording,
 * and the words that do name a different recording (live, acoustic, remix…).
 */
object ImportKeys {
    /** Lower case, no accents or punctuation, "&" read as "and" ([SearchRank.norm], which search uses). */
    fun norm(s: String): String = SearchRank.norm(s)

    /**
     * "A feat. B" / "A ft. B" / "A; B": the artists named. A comma, "&" and "x" stay inside a name: the sources give
     * one artist each (Spotify's album artist, a Takeout channel), and names have them (Earth, Wind & Fire; Tyler, The
     * Creator; Simon & Garfunkel).
     */
    fun artists(credit: String): List<String> =
        credit.split(Regex("\\s+(?:feat\\.?|ft\\.?|featuring)\\s+|\\s*;\\s*", RegexOption.IGNORE_CASE))
            .map { it.trim() }.filter { it.isNotEmpty() }

    /** The main artist of a credit. A YouTube channel "Artist - Topic" is the artist. */
    fun mainArtist(credit: String): String = artists(credit.removeSuffix(" - Topic")).firstOrNull() ?: credit.trim()

    /** One key per song: its core title and main artist. */
    fun songKey(title: String, artist: String): String = core(title) + "|" + norm(mainArtist(artist))

    private val FEAT = Regex("\\s*[(\\[](?:feat|ft|featuring|with)\\.?\\s[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE)
    private val BRACKETS = Regex("\\s*[(\\[][^)\\]]*[)\\]]")
    private val TAIL = Regex("\\s+[-–—]\\s+.*$")

    /** "Song (feat. X) - Remastered 2011" → "song". */
    fun core(title: String): String = norm(title.replace(FEAT, "").replace(BRACKETS, "").replace(TAIL, "")).ifEmpty { norm(title) }

    /**
     * The words that make a recording another version of the song, as one word each: "live", "acoustic", "remaster",
     * "demo", "remix", "edit", "extended", "instrumental", "unplugged", "acapella", "orchestral", "rerecorded" (Taylor's
     * Version too), "sped up", "slowed", "karaoke", "cover", "mono", "clean". Only from the brackets and the tail (a
     * song called "Live Forever" isn't live).
     */
    fun versionMarks(title: String): Set<String> {
        val extra = (BRACKETS.findAll(title.replace(FEAT, "")).map { it.value } + TAIL.findAll(title).map { it.value }).joinToString(" ")
        val t = " " + norm(extra) + " "
        if (t.isBlank()) return emptySet()
        return MARKS.filter { (_, words) -> words.any { t.contains(" $it ") } }.keys
    }

    /** Version marks that are never the song itself: a candidate with one the track hasn't is a knock-off. */
    val KNOCKOFFS = setOf("karaoke", "cover", "instrumental", "sped up", "slowed", "tribute", "8d")

    private val MARKS: Map<String, List<String>> = linkedMapOf(
        "live" to listOf("live", "en vivo", "ao vivo", "en directo", "live at", "concert"),
        "acoustic" to listOf("acoustic", "acustico", "acoustique", "stripped"),
        "remaster" to listOf("remaster", "remastered", "remasterizado", "remasterise"),
        "demo" to listOf("demo"),
        // Not "mix" alone: "Original Mix" is the song.
        "remix" to listOf("remix", "rmx", "vip mix", "rework", "flip"),
        "edit" to listOf("edit", "radio edit"),
        "extended" to listOf("extended", "club mix"),
        "instrumental" to listOf("instrumental"),
        "unplugged" to listOf("unplugged"),
        "acapella" to listOf("acapella", "a cappella"),
        "orchestral" to listOf("orchestral", "symphonic"),
        "rerecorded" to listOf("taylors version", "rerecorded", "re recorded"),
        "sped up" to listOf("sped up", "speed up", "nightcore"),
        "slowed" to listOf("slowed", "reverb"),
        "karaoke" to listOf("karaoke", "made famous"),
        "cover" to listOf("cover"),
        "tribute" to listOf("tribute"),
        "8d" to listOf("8d"),
        "mono" to listOf("mono"),
        "clean" to listOf("clean"),
    )
}
