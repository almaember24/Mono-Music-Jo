package com.prism.music.data.imports

import com.prism.music.data.db.PrismDatabase
import java.util.BitSet

/**
 * The plays an earlier import of one source brought (1.8.1, repeat imports), to know a play in a newer download that is
 * already here: the same song within [WINDOW_MS] of the same time is the same play. That covers a fresh copy of the same
 * download (the same times), and Spotify's two histories, whose times differ: the account data's to the minute, the
 * extended one's to the second.
 *
 * The two histories can also name a song's artist differently (the account data the song's own artist, the extended
 * one the album's: "Calvin Harris" against "Calvin Harris, Rihanna", or a featured singer's song on another's album).
 * So a play whose song isn't found by its name is also looked for by its title alone, among stored songs whose artist
 * is related to its own ([related]): one name inside the other, or an artist both credits name. Two songs that share a
 * title but no artist (two different "Stay"s in the same minute) stay two plays.
 *
 * Each stored play stands for one play of the new download at most (a song skipped and played again a minute later is
 * two plays in both). Between the two Spotify histories the extended play is the whole story: where a newer download's
 * extended play meets an account-data play stored before, [absorb] says to [Hit.Upgrade] that row in place.
 */
internal class ImportedIndex private constructor(
    private val songs: Map<String, Plays>,
    /** Each title's stored songs ([ImportKeys.core] → song keys), for the artist-mismatch case. */
    private val byTitle: Map<String, List<String>>,
) {
    /**
     * One song's stored plays, oldest first: their times, rows, and which were account-data plays (no Spotify id);
     * [artist]: the artist as its first play names it.
     */
    private class Plays(val at: LongArray, val ids: LongArray, val plain: BitSet, val artist: String) {
        val used = BitSet(at.size)
    }

    sealed interface Hit {
        /** Not here yet. */
        data object New : Hit
        /** Already here. */
        data object Same : Hit
        /**
         * Already here as an account-data play; [id]'s row takes the extended play's details. [sameSong]: found by its
         * name (false: by its title and a related artist, so the row keeps its own song).
         */
        data class Upgrade(val id: Long, val sameSong: Boolean = true) : Hit
    }

    val isEmpty: Boolean get() = songs.isEmpty()

    /** Whether [p] (its song as [key]) is a play already stored; once matched, that stored play can't match another. */
    fun absorb(p: ParsedPlay, key: String, upgrades: Boolean): Hit {
        songs[key]?.let { s -> nearest(s, p.timestamp)?.let { return claim(s, it, p, upgrades, sameSong = true) } }
        // Not by its name: the same title by a related artist, nearest in time.
        val others = byTitle[key.substringBeforeLast('|')] ?: return Hit.New
        var best: Plays? = null
        var at = -1
        var gap = Long.MAX_VALUE
        for (k in others) {
            if (k == key) continue
            val s = songs.getValue(k)
            if (!related(p.artist, s.artist)) continue
            val i = nearest(s, p.timestamp) ?: continue
            val d = kotlin.math.abs(s.at[i] - p.timestamp)
            if (d < gap) { best = s; at = i; gap = d }
        }
        return best?.let { claim(it, at, p, upgrades, sameSong = false) } ?: Hit.New
    }

    private fun claim(s: Plays, i: Int, p: ParsedPlay, upgrades: Boolean, sameSong: Boolean): Hit {
        s.used.set(i)
        return if (upgrades && p.sourceId != null && s.plain[i]) Hit.Upgrade(s.ids[i], sameSong) else Hit.Same
    }

    /** The nearest unused stored play of [s] within the window of [t] (a song played twice in a few minutes keeps both). */
    private fun nearest(s: Plays, t: Long): Int? {
        var i = java.util.Arrays.binarySearch(s.at, t - WINDOW_MS).let { if (it < 0) -it - 1 else it }
        var best = -1
        var gap = Long.MAX_VALUE
        while (i < s.at.size && s.at[i] <= t + WINDOW_MS) {
            val d = kotlin.math.abs(s.at[i] - t)
            if (!s.used[i] && d < gap) { best = i; gap = d }
            i++
        }
        return best.takeIf { it >= 0 }
    }

    companion object {
        /** "About a minute": the account data's times are to the minute, and its clock and the extended one's differ a little. */
        const val WINDOW_MS = 90_000L

        /**
         * Two credits that can be one song's: one name inside the other, as whole words ("Calvin Harris" in "Calvin
         * Harris, Rihanna"), or an artist both name ("A feat. B" and "B"). Nothing shared: two songs.
         */
        fun related(a: String, b: String): Boolean {
            val na = ImportKeys.norm(a)
            val nb = ImportKeys.norm(b)
            if (na.isEmpty() || nb.isEmpty()) return false
            if (" $na ".contains(" $nb ") || " $nb ".contains(" $na ")) return true
            val ma = ImportKeys.artists(a).map { ImportKeys.norm(it) }.filter { it.isNotEmpty() }.toSet()
            val mb = ImportKeys.artists(b).map { ImportKeys.norm(it) }.filter { it.isNotEmpty() }.toSet()
            return ma.any { it in mb } || ImportKeys.norm(ImportKeys.mainArtist(a)) == ImportKeys.norm(ImportKeys.mainArtist(b))
        }

        /** [source]'s stored plays, read straight off a cursor (a long history is 100,000+ rows). */
        fun of(db: PrismDatabase, source: ImportSource): ImportedIndex {
            val songs = HashMap<String, Plays>()
            db.query(
                androidx.sqlite.db.SimpleSQLiteQuery(
                    "SELECT songKey, timestamp, id, sourceId IS NULL, artist FROM imported_plays WHERE origin = ? ORDER BY songKey, timestamp",
                    arrayOf(source.key),
                )
            ).use { cur ->
                var key: String? = null
                var artist = ""
                val at = LongList(); val ids = LongList(); val plain = BitSet()
                fun close() {
                    val k = key ?: return
                    songs[k] = Plays(at.toArray(), ids.toArray(), plain.clone() as BitSet, artist)
                    at.clear(); ids.clear(); plain.clear()
                }
                while (cur.moveToNext()) {
                    val k = cur.getString(0)
                    if (k != key) { close(); key = k; artist = cur.getString(4) }
                    if (cur.getInt(3) != 0) plain.set(at.size)
                    at.add(cur.getLong(1)); ids.add(cur.getLong(2))
                }
                close()
            }
            // By title, for a play whose song name (its artist) differs from the one stored.
            val byTitle = songs.keys.groupBy { it.substringBeforeLast('|') }
            return ImportedIndex(songs, byTitle)
        }
    }

    /** A growable list of longs without boxing each. */
    private class LongList {
        private var a = LongArray(8)
        var size = 0; private set
        fun add(v: Long) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        fun clear() { size = 0 }
        fun toArray(): LongArray = a.copyOf(size)
    }
}
