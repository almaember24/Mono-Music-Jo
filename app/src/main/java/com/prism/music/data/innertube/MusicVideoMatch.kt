package com.prism.music.data.innertube

import com.prism.music.data.model.Song
import kotlin.math.abs

/**
 * Picks a song's music video from a video search, when YouTube doesn't link one itself.
 *
 * - Posted by the artist (their channel, or its VEVO / "Official" one), the same length give or take [LOOSE_S], and not
 *   another take on the song (a cover, a live set, a lyric video…) unless the song itself is one: the usual case.
 * - Posted by someone else, it has to be the very recording: within [TIGHT_S] of the song's length, naming the artist
 *   in its title, and from the same source as the song when the song names one in brackets. "Linger (SiriusXM Session)"
 *   by Royel Otis has its video on SiriusXM's channel as "Royel Otis — Linger (The Cranberries Cover) [Live @
 *   SiriusXM]": a live cover, by the radio station, and the right one.
 * - Lyric videos, karaoke, reactions, lessons, remixes, slowed/sped-up edits and fan uploads never count (#60: a guitar
 *   cover and a fan's lyric video named the artist in their titles).
 */
object MusicVideoMatch {
    fun pick(song: Song, videos: List<Song>): String? {
        val title = norm(song.title)
        val artists = song.artists.map { norm(it.name) }.filter { it.isNotBlank() }
        if (title.isBlank() || artists.isEmpty()) return null
        val songWords = song.title.lowercase()
        // The source the song names in its brackets ("SiriusXM Session" → "siriusxm"), without the generic words.
        val source = Regex("\\((.*?)\\)|\\[(.*?)]").findAll(songWords)
            .flatMap { m -> m.value.split(Regex("[^\\p{L}\\p{N}]+")) }
            .filter { it.length > 2 && it !in GENERIC }.toSet()

        fun lengthWithin(v: Song, s: Int) = song.durationSec == 0 || v.durationSec == 0 || abs(v.durationSec - song.durationSec) <= s
        fun has(words: List<Regex>, text: String) = words.any { it.containsMatchIn(text) && !it.containsMatchIn(songWords) }

        // The channel counts too ("Rebassed by Justin" posts nothing but bass-boosted copies), unless it's the artist's
        // own (The Piano Guys).
        fun ownChannel(v: Song) = v.artists.any { a -> officialChannel(norm(a.name)).let { ch -> ch.isNotBlank() && ch in artists } }
        // The video is of this song: once the artists' names, the channel's and tags like "(Official Video)" are taken
        // out, what's left of its title is exactly the song's ("House of Memories" isn't "Memories").
        val candidates = videos.filter { v ->
            sameTitle(song, v) && !has(NEVER, v.title.lowercase()) &&
                (ownChannel(v) || !has(NEVER, v.artists.joinToString(" ") { it.name.lowercase() }))
        }
        // The artist's own upload first: their channel (by id, else by name, VEVO and "Official" ones too), or their
        // record label's.
        val artistIds = song.artists.mapNotNull { it.id }.toSet()
        candidates.firstOrNull { v ->
            val byArtist = v.artists.any { a -> a.id != null && a.id in artistIds } || ownChannel(v) || byLabel(v, artists)
            byArtist && lengthWithin(v, LOOSE_S) && !has(OTHER_TAKE, v.title.lowercase())
        }?.let { return it.id }
        // A studio song takes nobody else's upload: re-uploads, translations and fan lyric videos look just like the
        // song (#60, "What You Need", "Bohemian Rhapsody"). Better no video than someone else's.
        if (source.isEmpty()) return null
        // A song from a named session or show ("Linger (SiriusXM Session)"): that very recording, posted by whoever made it.
        return candidates.firstOrNull { v ->
            val words = v.title.lowercase()
            val where = (words + " " + v.artists.joinToString(" ") { it.name.lowercase() })
            val namesArtist = artists.any { norm(words).contains(it) }
            lengthWithin(v, TIGHT_S) && song.durationSec > 0 && v.durationSec > 0 && namesArtist && source.any { where.contains(it) }
        }?.id
    }

    /**
     * Posted by the artist's record label, naming the artist: a label we know, or a channel that calls itself one
     * ("… Records", "… Recordings", "… Music Group") whose title also says it's official.
     */
    private fun byLabel(v: Song, artists: List<String>): Boolean {
        if (artists.none { norm(v.title).contains(it) }) return false
        return v.artists.any { a ->
            val n = norm(a.name)
            n in LABELS || (LABEL_WORDS.containsMatchIn(a.name.lowercase()) && Regex("(?<![\\p{L}])official(?![\\p{L}])").containsMatchIn(v.title.lowercase()))
        }
    }

    private val LABEL_WORDS = Regex("(?<![\\p{L}])(records|recordings|music group|entertainment|label)(?![\\p{L}])")

    /**
     * Whether video [v]'s title is [song]'s title: brackets, "| …" tails, "ft./feat." credits, the words of a video
     * label ("Official Music Video") and the names of the song's artists and the video's channel all taken out.
     */
    fun sameTitle(song: Song, v: Song): Boolean {
        val want = core(song.title, emptyList())
        if (want.isBlank()) return false
        val names = (song.artists + v.artists).map { norm(it.name) }.filter { it.length >= 2 }
            .flatMap { listOf(it, officialChannel(it)) }.distinct().sortedByDescending { it.length }
        return core(v.title, names) == want
    }

    private fun core(title: String, names: List<String>): String {
        var t = title.lowercase()
            .replace(Regex("\\(.*?\\)|\\[.*?]|【.*?】"), " ")
            .substringBefore(" | ").substringBefore(" // ")
            .replace(Regex("\\s(ft\\.?|feat\\.?|featuring)\\s.*$"), " ")
            .replace(Regex("(?<![\\p{L}])(official|music|video|videoclip|clip|hd|4k|hq|remastered|remaster|visualizer|mv|m/v)(?![\\p{L}])"), " ")
        t = norm(t)
        // The artist's name wherever it is ("Queen - Bohemian Rhapsody", "Bohemian Rhapsody - Queen"), longest first.
        for (n in names) if (t != n) t = t.replace(n, "")
        return t
    }

    fun norm(s: String) = s.lowercase().replace(Regex("\\(.*?\\)|\\[.*?]"), "").replace(Regex("[^\\p{L}\\p{N}]"), "")

    /** "NirvanaVEVO", "Charly García Oficial", "Official Billie Eilish" → the artist's own name, normalized. */
    fun officialChannel(name: String): String {
        var n = name
        for (s in listOf("vevo", "official", "oficial", "officiel", "offiziell", "ufficiale", "music", "tv", "channel")) n = n.removeSuffix(s)
        return n.removePrefix("official").ifBlank { name }
    }

    private fun words(vararg w: String) = w.map { Regex("(?<![\\p{L}\\p{N}])${Regex.escape(it)}(?![\\p{L}\\p{N}])") }

    /** Never the song's video, whoever posted it. */
    private val NEVER = words(
        "lyric", "lyrics", "letra", "letras", "paroles", "testo", "traducida", "traducción", "sub español", "subtitulad", "перевод", "текст",
        "karaoke", "instrumental", "reaction", "tutorial", "lesson", "guitar", "piano", "drum", "bass", "slowed", "sped up", "nightcore",
        "8d", "fan made", "fanmade", "remix", "highlights", "edit audio", "official audio", "audio oficial", "audio only",
        "rebassed", "bass boosted", "bassboosted", "boosted", "reverb", "daycore", "speed up", "1 hour", "10 hours", "loop",
        "mashup", "parody", "ai cover",
    ) + listOf(
        // A frequency in the title ("(25-70Hz)", "432hz") marks a re-tuned or bass-boosted copy.
        Regex("(?<![\\p{L}])\\d+\\s?hz(?![\\p{L}])"),
    )

    /** Whether [title] says it's another take (live, acoustic, a cover, a session) rather than the record. */
    fun otherTake(title: String): Boolean = title.lowercase().let { t -> OTHER_TAKE.any { it.containsMatchIn(t) } }

    /** Another take on the song: fine when the song is that take itself. */
    private val OTHER_TAKE = words("cover", "acoustic", "acústic", "unplugged", "live", "en vivo", "ao vivo", "concert", "session")

    /** Bracket words that don't name a source. */
    private val GENERIC = setOf(
        "feat", "with", "from", "the", "and", "version", "session", "sessions", "live", "remaster", "remastered", "edit",
        "radio", "mix", "original", "deluxe", "explicit", "clean", "cover", "acoustic", "mono", "stereo", "single",
    )

    /** Record labels that post their artists' official videos (normalized channel names). */
    private val LABELS = setOf(
        "fueledbyramen", "atlanticrecords", "warnerrecords", "warnermusic", "elektrarecords", "roadrunnerrecords", "interscope",
        "interscoperecords", "republicrecords", "islandrecords", "defjam", "defjamrecordings", "capitolrecords", "columbiarecords",
        "epicrecords", "rcarecords", "sonymusic", "sonymusicentertainment", "universalmusicgroup", "emimusic", "parlophone",
        "parlophonerecords", "epitaphrecords", "hopelessrecords", "riserecords", "sumerianrecords", "nuclearblastrecords",
        "spinninrecords", "monstercat", "ultramusic", "armadamusic", "xlrecordings", "subpop", "dominorecordco", "4ad",
        "matadorrecords", "bigmachinerecords", "rhinorecords", "motownrecords", "topdawgent", "xorecords", "glassnoterecords",
    )

    private const val LOOSE_S = 150
    private const val TIGHT_S = 4
}
