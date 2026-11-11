package com.prism.music.data.foryou

import com.prism.music.R
import com.prism.music.tr
import kotlin.math.exp

/**
 * The thinking behind Prism's mixes, apart from where the data comes from (so it's unit-tested): which of the
 * listener's artists belong together ([clusters]), how a mix mixes the songs they know with ones they don't
 * ([interleave]), and what the time-of-day mix is called ([TimeBucket]).
 */
object MadeForYou {
    const val RADAR = "radar"
    const val TIME = "time"
    fun daily(n: Int) = "daily:$n"
    const val MAX_DAILY = 6

    /**
     * A Daily Mix (1.7.9): 50 songs, half of them new to the listener, found from three of the day's familiar picks.
     * A mix of few artists lets each of them more songs (a one-artist mix is mostly that artist, with new ones like
     * them), a mix of many keeps each to a handful.
     */
    fun dailyRecipe(id: String, artists: Int): MixMaker.Recipe {
        val size = 50
        val share = 0.5
        val perArtist = maxOf(4, kotlin.math.ceil(size * (1 - share) / artists.coerceAtLeast(1)).toInt() + 2)
        return MixMaker.Recipe(id, size, share, seeds = 3, perArtist = perArtist)
    }

    /** The time-of-day mix: 30 songs, mostly what's played at this time, with new ones like them. */
    val timeRecipe = MixMaker.Recipe(TIME, size = 30, freshShare = 0.4, seeds = 2, perArtist = 4)

    /** A play, as the mixes see it. */
    data class Play(val songId: String, val artist: String, val at: Long, val weight: Double = 1.0)

    /** A group of artists that go together, heaviest listening first; [weight] is the group's share of the listening. */
    data class Cluster(val artists: List<String>, val weight: Double)

    /**
     * Groups the listener's artists into up to [max] clusters that go together: artists in the same genre and artists
     * played one after the other more often than chance are close. Average-linkage clustering over the
     * [top] most played artists (recent plays count most); small groups are dropped unless an artist is big alone.
     */
    fun clusters(plays: List<Play>, genres: Map<String, String?>, now: Long, max: Int = MAX_DAILY, top: Int = 40): List<Cluster> {
        if (plays.isEmpty()) return emptyList()
        val weight = artistWeights(plays, now)
        val artists = topArtists(plays, now, top)
        if (artists.isEmpty()) return emptyList()
        val index = artists.withIndex().associate { it.value to it.index }
        val n = artists.size

        // Played next to each other (one straight after the other, within a sitting), against what chance would give:
        // a shuffled library puts any two artists side by side now and then, a listener who plays them together
        // does it far more often. [lift] 1 is chance; the closeness only counts what's above it.
        val adjacent = Array(n) { DoubleArray(n) }
        val ends = DoubleArray(n)
        var pairs = 0.0
        var prev: Play? = null
        for (p in plays.sortedBy { it.at }) {
            val q = prev
            prev = p
            if (q == null || p.at - q.at > SITTING_GAP_MS || q.artist == p.artist) continue
            val a = index[q.artist] ?: continue
            val b = index[p.artist] ?: continue
            adjacent[a][b] += 1.0; adjacent[b][a] += 1.0
            ends[a] += 1.0; ends[b] += 1.0
            pairs += 1.0
        }

        val sim = Array(n) { a ->
            DoubleArray(n) { b ->
                if (a == b) 1.0 else {
                    val ga = genres[artists[a]]; val gb = genres[artists[b]]
                    val genre = when { ga == null || gb == null -> UNKNOWN_GENRE; ga == gb -> 1.0; else -> 0.0 }
                    val expected = if (pairs == 0.0) 0.0 else ends[a] * ends[b] / (2 * pairs)
                    val lift = if (expected <= 0.0) 0.0 else adjacent[a][b] / expected
                    // Twice chance or more counts in full; a single meeting proves nothing. An artist with too few plays
                    // beside others to tell gets the benefit of the doubt, so it can still join others of its genre.
                    val measured = if (adjacent[a][b] < 2) 0.0 else ((lift - 1) / (LIFT_FULL - 1)).coerceIn(0.0, 1.0)
                    val co = if (minOf(ends[a], ends[b]) < THIN_EVIDENCE) maxOf(measured, BENEFIT_OF_DOUBT) else measured
                    GENRE_WEIGHT * genre + (1 - GENRE_WEIGHT) * co
                }
            }
        }
        val groups = (0 until n).map { mutableListOf(it) }.toMutableList()
        while (groups.size > 1) {
            var best = -1.0; var bi = -1; var bj = -1
            for (i in groups.indices) for (j in i + 1 until groups.size) {
                var s = 0.0
                for (a in groups[i]) for (b in groups[j]) s += sim[a][b]
                s /= groups[i].size * groups[j].size
                if (s > best) { best = s; bi = i; bj = j }
            }
            if (best < MERGE_AT) break
            groups[bi].addAll(groups[bj]); groups.removeAt(bj)
        }
        val total = weight.values.sum().takeIf { it > 0 } ?: return emptyList()
        return groups
            .map { g -> Cluster(g.sortedByDescending { weight[artists[it]] }.map { artists[it] }, g.sumOf { weight[artists[it]] ?: 0.0 } / total) }
            .filter { it.weight >= if (it.artists.size == 1) SOLO_MIN else GROUP_MIN }
            .sortedByDescending { it.weight }
            .take(max)
    }

    /** Each artist's listening, recent plays counting most (a month's half-life, roughly). */
    fun artistWeights(plays: List<Play>, now: Long): Map<String, Double> {
        val weight = HashMap<String, Double>()
        for (p in plays) weight.merge(p.artist, p.weight * exp(-(now - p.at) / (30.0 * DAY)), Double::plus)
        return weight
    }

    /** The [top] artists [clusters] works with (look their genres up first). */
    fun topArtists(plays: List<Play>, now: Long, top: Int = 40): List<String> =
        artistWeights(plays, now).entries.sortedByDescending { it.value }.take(top).map { it.key }

    /**
     * A mix's order: the songs the listener knows with new ones between them, two new in every five (so it's mostly
     * familiar, never a run of strangers), and the same artist never twice in a row where it can be helped.
     */
    fun <T> interleave(known: List<T>, fresh: List<T>, size: Int, artistOf: (T) -> String): List<T> {
        val out = ArrayList<T>(size)
        var k = 0; var f = 0
        while (out.size < size && (k < known.size || f < fresh.size)) {
            val wantFresh = out.size % 5 == 2 || out.size % 5 == 4
            out += when {
                wantFresh && f < fresh.size -> fresh[f++]
                k < known.size -> known[k++]
                else -> fresh[f++]
            }
        }
        // Same artist twice running: swap the second with the next song by someone else.
        for (i in 1 until out.size) {
            if (artistOf(out[i]) != artistOf(out[i - 1])) continue
            val j = (i + 1 until out.size).firstOrNull { artistOf(out[it]) != artistOf(out[i - 1]) } ?: continue
            val t = out[i]; out[i] = out[j]; out[j] = t
        }
        return out
    }

    /** "A, B and C", "A and more" style: up to three names, then "and more". */
    fun namesLine(names: List<String>): String = when {
        names.isEmpty() -> ""
        names.size == 1 -> names[0]
        names.size == 2 -> tr(R.string.and, names[0], names[1])
        names.size == 3 -> tr(R.string.and_2, names[0], names[1], names[2])
        else -> tr(R.string.and_more_5, names[0], names[1], names[2])
    }

    /** The parts of the day the time-of-day mix follows. */
    enum class TimeBucket(val label: String, val hours: IntRange) {
        MORNING("morning", 5..10),
        AFTERNOON("afternoon", 11..16),
        EVENING("evening", 17..20),
        NIGHT("night", 21..23),
        LATE("late-night", 0..4);

        operator fun contains(hour: Int) = hour in hours

        /** The part of the day in the listener's language, for the mix's name. */
        val shown: String get() = tr(when (this) { MORNING -> R.string.time_morning; AFTERNOON -> R.string.time_afternoon; EVENING -> R.string.time_evening; NIGHT -> R.string.time_night; LATE -> R.string.time_late })

        companion object {
            fun of(hour: Int): TimeBucket = entries.first { hour in it.hours }
        }
    }

    /** "Your Tuesday late-night hip-hop" (or "… mix" without a clear genre). */
    fun timeTitle(weekday: String, bucket: TimeBucket, genre: String?): String =
        tr(R.string.your, weekday, bucket.shown, genre?.takeIf { it.isNotBlank() }?.let { com.prism.music.data.meta.Genres.word(it) } ?: tr(R.string.mix_word))

    /**
     * What a mix can be called by: the genre of more than half its songs whose genre is known, with at least half its
     * songs known. Null otherwise ("… mix"): a plurality named a mix of 15 rock songs, 10 indie and 3 hip-hop "indie".
     */
    fun mainGenre(genres: List<String?>): String? {
        val known = genres.filterNotNull()
        if (known.isEmpty() || known.size * 2 < genres.size) return null
        val (top, count) = known.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return null
        return top.takeIf { count * 2 > known.size }
    }

    const val DAY = 86_400_000.0
    private const val SITTING_GAP_MS = 30 * 60_000L
    /**
     * How much sharing a genre counts against being played in the same sittings: genre leads, since a shuffled
     * library puts every artist in the same sitting. Two artists in different genres can't group on sittings alone, and two in the
     * same (coarse) genre still need to be played together a little: metalcore and the Beatles are both \"Rock\".
     */
    private const val GENRE_WEIGHT = 0.45
    /** An artist whose genre isn't known: neither close nor far on that score. */
    private const val UNKNOWN_GENRE = 0.3
    private const val MERGE_AT = 0.5
    /** Played side by side this many times more often than chance: as close as listening can make two artists. */
    private const val LIFT_FULL = 2.0
    /** Fewer plays beside other artists than this can't say who an artist goes with. */
    private const val THIN_EVIDENCE = 6.0
    /** What such an artist's closeness by listening counts as: enough to join its genre, not to cross genres. */
    private const val BENEFIT_OF_DOUBT = 0.2
    /** A group needs this share of the listening to make a mix; one artist alone needs more. */
    private const val GROUP_MIN = 0.04
    private const val SOLO_MIN = 0.08
}
