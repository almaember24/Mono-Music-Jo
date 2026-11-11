package com.prism.music.data.innertube

import kotlinx.serialization.json.JsonElement
import java.time.LocalDate

/**
 * What YouTube says about a song or a music video (#211), only as far as its answer really says it: anything it left out
 * is null, and nothing is worked out or guessed.
 */
data class SongFacts(
    /** How many times it's been played: YouTube Music calls this "plays" for a song and "views" for a video. */
    val count: Long? = null,
    /** The release date its label gave, from the "Released on:" line of an auto-generated song's description. */
    val releasedOn: LocalDate? = null,
    /** When this upload went up on YouTube. Not the release date: often years after it, sometimes a day before. */
    val publishedOn: LocalDate? = null,
) {
    val isEmpty: Boolean get() = count == null && releasedOn == null && publishedOn == null

    /** These facts, with what they lack from [other]. */
    fun orElse(other: SongFacts) = SongFacts(count ?: other.count, releasedOn ?: other.releasedOn, publishedOn ?: other.publishedOn)

    companion object {
        // YouTube writes this line itself under auto-generated songs, in English whatever the language asked for.
        private val RELEASED = Regex("""(?m)^Released on:\s*(\d{4}-\d{2}-\d{2})\s*$""")

        /**
         * Reads the player's answer for [videoId]. An answer about another video, which YouTube can send for one it won't
         * serve, says nothing about this one.
         */
        fun parse(videoId: String, player: JsonElement?): SongFacts {
            val details = player.obj("videoDetails")
            if (details != null && details.str("videoId").let { it != null && it != videoId }) return SongFacts()
            val web = player.obj("microformat", "playerMicroformatRenderer")
            val music = player.obj("microformat", "microformatDataRenderer")
            return SongFacts(
                count = (details.str("viewCount") ?: web.str("viewCount") ?: music.str("viewCount"))?.toLongOrNull()?.takeIf { it > 0 },
                releasedOn = details.str("shortDescription")?.let { RELEASED.find(it)?.groupValues?.get(1) }?.let(::date),
                publishedOn = (web.str("publishDate") ?: web.str("uploadDate") ?: music.str("publishDate") ?: music.str("uploadDate"))?.let(::date),
            )
        }

        /** "2019-11-28" or "2019-11-28T21:01:00-08:00" as written, whatever its time zone. */
        private fun date(s: String): LocalDate? = runCatching { LocalDate.parse(s.take(10)) }.getOrNull()
    }
}
