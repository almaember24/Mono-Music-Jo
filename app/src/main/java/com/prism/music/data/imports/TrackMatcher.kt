package com.prism.music.data.imports

import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import kotlin.math.abs

/** A song from another app to find on YouTube Music. Lengths in ms, 0 when the source doesn't say. */
data class TrackToMatch(
    val title: String,
    val artist: String,
    val album: String? = null,
    /** The song's exact length (a Spotify play that ran to its end). */
    val durationMs: Long = 0,
    /** The longest play of it: the song is at least this long. */
    val atLeastMs: Long = 0,
    /** Its ISRC, when the source knows it (none of today's files: kept for Spotify's own data later). */
    val isrc: String? = null,
    /** The YouTube video the source itself names (Google Takeout). */
    val youtubeId: String? = null,
)

data class MatchResult(val status: MatchStatus, val song: Song?, val candidates: List<Song>)

/**
 * Finds a song from another app on YouTube Music (1.8.1). How sure it is decides what happens: a sure match counts
 * at once, an unsure one waits in "Needs a look" for the listener to pick, nothing found is tried again in a week.
 *
 * 1. The ISRC, when known, is searched first: what it finds with the right title and artist is the recording.
 * 2. Else "title artist" in YouTube Music's song search (its own ranking, which Prism's search reads too), each result
 *    held to the track: the same artist, the same title (brackets and "feat." aside), no knock-off the track isn't
 *    (karaoke, cover, sped up…), not shorter than its longest play.
 * 3. The length decides: within [SURE_MS] → sure; within [LOOK_MS] → needs a look; further, or a different version word
 *    (live, acoustic, remix, demo…) → needs a look. Without a length, the first result that fits every rule is sure.
 *    A remaster of the same length (within [REMASTER_MS]) is the same recording: sure.
 */
class TrackMatcher(private val search: suspend (String) -> List<Song>) {
    suspend fun match(t: TrackToMatch): MatchResult {
        t.isrc?.takeIf { it.isNotBlank() }?.let { isrc ->
            val r = judge(t, runCatching { search(isrc) }.getOrDefault(emptyList()))
            if (r.status == MatchStatus.SURE) return r
        }
        val found = search(queryFor(t))
        if (t.youtubeId != null) return byId(t, found)
        val r = judge(t, found)
        // A title with words in brackets YouTube writes otherwise: once more with the title alone.
        if (r.status == MatchStatus.NONE && ImportKeys.core(t.title) != ImportKeys.norm(t.title)) {
            val again = judge(t, runCatching { search(plainQuery(t)) }.getOrDefault(emptyList()))
            if (again.status != MatchStatus.NONE) return again
        }
        return r
    }

    companion object {
        const val SURE_MS = 3_000L
        const val LOOK_MS = 10_000L
        const val REMASTER_MS = 2_000L

        private val FEAT = Regex("\\s*[(\\[](?:feat|ft|featuring|with)\\.?\\s[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE)

        fun queryFor(t: TrackToMatch) = "${t.title.replace(FEAT, "").trim()} ${ImportKeys.mainArtist(t.artist)}".trim()
        /** The second try, when the first finds nothing: the title without its brackets. */
        fun plainQuery(t: TrackToMatch) = "${ImportKeys.core(t.title)} ${ImportKeys.norm(ImportKeys.mainArtist(t.artist))}".trim()

        /** Takeout names the video itself: that's the song, with what search knows of it when it's among the results. */
        fun byId(t: TrackToMatch, found: List<Song>): MatchResult {
            val id = t.youtubeId ?: return MatchResult(MatchStatus.NONE, null, emptyList())
            val known = found.firstOrNull { it.id == id }
            val song = known ?: Song(id, t.title, listOfNotNull(t.artist.takeIf { it.isNotBlank() }?.let { ArtistRef(it) }), durationSec = 0)
            return MatchResult(MatchStatus.SURE, song, listOf(song))
        }

        private fun artistKey(name: String) = ImportKeys.norm(name).removePrefix("the ").replace(" ", "")

        private fun sameArtist(track: Set<String>, song: Song): Boolean = song.artists.any { a ->
            val k = artistKey(a.name)
            k.isNotEmpty() && (k in track || track.any { x -> x.length >= 4 && k.length >= 4 && (k.contains(x) || x.contains(k)) })
        }

        /** 0..1: how alike two core titles are (word overlap; one inside the other for titles without spaces). */
        internal fun likeness(a: String, b: String): Double {
            if (a == b) return 1.0
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val wa = a.split(" ").toSet(); val wb = b.split(" ").toSet()
            val words = wa.intersect(wb).size.toDouble() / wa.union(wb).size
            val inside = if (a.contains(b) || b.contains(a)) minOf(a.length, b.length).toDouble() / maxOf(a.length, b.length) else 0.0
            return maxOf(words, inside)
        }

        private class Scored(
            val song: Song, val rank: Int, val artistOk: Boolean, val titleOk: Boolean, val titleNear: Boolean,
            val marksSame: Boolean, val onlyRemaster: Boolean, val knockoff: Boolean, val delta: Long?, val tooShort: Boolean,
            /** Words in its brackets the track's title hasn't ("Jai Ho! (You Are My Destiny)"): maybe another song. */
            val extraNotes: Boolean,
        )

        /** Judges [candidates] (YouTube's order) against [t]. */
        fun judge(t: TrackToMatch, candidates: List<Song>): MatchResult {
            val tCore = ImportKeys.core(t.title)
            val tMarks = ImportKeys.versionMarks(t.title)
            val tArtists = (ImportKeys.artists(t.artist) + t.artist).map(::artistKey).filter { it.isNotEmpty() }.toSet()
            val scored = candidates.filter { !it.isLive && !it.isEpisode && !it.isUpload }.distinctBy { it.id }.mapIndexed { i, s ->
                val cCore = ImportKeys.core(s.title)
                val cMarks = ImportKeys.versionMarks(s.title)
                val ms = s.durationSec * 1000L
                Scored(
                    song = s, rank = i, artistOk = sameArtist(tArtists, s), titleOk = cCore == tCore,
                    titleNear = likeness(cCore, tCore) >= 0.6,
                    // Remasters of different years are different masters (Hey Jude's 2009 and 2015).
                    marksSame = cMarks == tMarks && sameRemasterYear(s.title, t.title),
                    onlyRemaster = (cMarks - "remaster") == (tMarks - "remaster") && sameRemasterYear(s.title, t.title),
                    knockoff = (cMarks - tMarks).any { it in ImportKeys.KNOCKOFFS },
                    delta = if (t.durationMs > 0 && ms > 0) abs(ms - t.durationMs) else null,
                    tooShort = t.atLeastMs > 0 && ms > 0 && ms < t.atLeastMs - SURE_MS,
                    extraNotes = extraNotes(s.title, t.title),
                )
            }
            val fits = scored.filter { it.artistOk && it.titleNear && !it.knockoff && !it.tooShort }
            fun review(first: Scored?) = MatchResult(MatchStatus.REVIEW, first?.song, top3(first, fits.ifEmpty { scored }))
            val exact = fits.filter { it.titleOk && !it.song.isVideo && (it.marksSame || it.onlyRemaster) }
            if (exact.isNotEmpty()) {
                if (t.durationMs > 0) {
                    val timed = exact.filter { it.delta != null }
                    val closest = timed.minWithOrNull(compareBy<Scored> { it.delta }.thenBy { if (it.marksSame) 0 else 1 }.thenBy { if (it.song.explicit) 0 else 1 }.thenBy { it.rank })
                    if (closest != null) {
                        val d = closest.delta!!
                        val sure = if (closest.marksSame) d <= SURE_MS else d <= REMASTER_MS
                        return if (sure) MatchResult(MatchStatus.SURE, closest.song, top3(closest, fits)) else review(closest)
                    }
                }
                // No length to hold it to: the first that fits every rule, in YouTube's order (the explicit one when
                // YouTube lists both versions next to each other: Spotify plays the explicit one unless told not to).
                // Without a length, a title with more in its brackets than the track's (a subtitle, another song's
                // name) isn't sure either.
                val plain = exact.filter { it.marksSame && !it.extraNotes }
                if (plain.isNotEmpty()) {
                    val first = plain.first()
                    val explicitTwin = plain.firstOrNull { it.song.explicit && it.rank <= first.rank + 2 && it.delta == first.delta }
                    val pick = if (!first.song.explicit && explicitTwin != null) explicitTwin else first
                    return MatchResult(MatchStatus.SURE, pick.song, top3(pick, fits))
                }
                return review(exact.first())
            }
            if (fits.isNotEmpty()) {
                val best = fits.sortedWith(compareBy<Scored> { if (it.titleOk) 0 else 1 }.thenBy { if (it.marksSame) 0 else 1 }.thenBy { it.delta ?: Long.MAX_VALUE }.thenBy { it.rank }).first()
                return review(best)
            }
            // The right title by another name for the artist ("Ye" for Kanye West, a name in another script).
            val namedOtherwise = scored.firstOrNull { it.titleOk && !it.knockoff && !it.tooShort && it.marksSame && !it.song.isVideo }
            if (namedOtherwise != null) return MatchResult(MatchStatus.REVIEW, namedOtherwise.song, top3(namedOtherwise, scored))
            return MatchResult(MatchStatus.NONE, null, emptyList())
        }

        private val YEAR = Regex("\\b(19|20)\\d\\d\\b")

        /** Both name the year of their remaster and the years differ: false. */
        internal fun sameRemasterYear(a: String, b: String): Boolean {
            fun year(s: String) = s.takeIf { ImportKeys.versionMarks(it).contains("remaster") }
                ?.let { Regex("[(\\[][^)\\]]*[)\\]]|\\s+[-–—]\\s+.*$").findAll(it).joinToString(" ") { m -> m.value } }
                ?.let { YEAR.find(it)?.value }
            val ya = year(a) ?: return true
            val yb = year(b) ?: return true
            return ya == yb
        }

        /** Notes that only say what a recording is (or where it's from): they don't make it another song. */
        private val NOTE_WORDS = setOf(
            "version", "edition", "remaster", "remastered", "original", "album", "single", "explicit", "clean", "mono", "stereo",
            "deluxe", "bonus", "track", "edit", "radio", "mix", "live", "acoustic", "remix", "demo", "instrumental", "taylors", "from", "the", "vault",
        )

        /** [candidate]'s brackets hold words that are neither in [track]'s title nor only notes ("feat. …", "From …", years). */
        internal fun extraNotes(candidate: String, track: String): Boolean {
            val trackWords = ImportKeys.norm(track).split(" ").toSet()
            return Regex("[(\\[]([^)\\]]*)[)\\]]").findAll(candidate).map { ImportKeys.norm(it.groupValues[1]) }
                .filterNot { it.startsWith("feat ") || it.startsWith("ft ") || it.startsWith("featuring ") || it.startsWith("with ") || it.startsWith("from ") }
                .any { note -> note.split(" ").any { w -> w.isNotEmpty() && w !in trackWords && w !in NOTE_WORDS && !w.all(Char::isDigit) } }
        }

        private fun top3(first: Scored?, rest: List<Scored>): List<Song> =
            (listOfNotNull(first) + rest.filter { it !== first }).map { it.song }.distinctBy { it.id }.take(3)
    }
}
