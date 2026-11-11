package com.prism.music.data.blend

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A song in a Blend, and whose taste it comes from: [FIRST], [SECOND] (in [Blend.people] order) or [BOTH]. */
data class BlendSong(val song: CodeSong, val from: Int) {
    companion object { const val FIRST = 0; const val SECOND = 1; const val BOTH = 2 }
}

/**
 * Two people's Blend. [people] is the two codes in a fixed order (by their ids), so both phones work it out the same.
 */
data class Blend(
    val people: Pair<TasteCode, TasteCode>,
    /** How close their tastes are, 0–100. */
    val match: Int,
    /** Artists both play, most shared first. */
    val sharedArtists: List<String>,
    /** Genres both play, most shared first. */
    val sharedGenres: List<String>,
    val songs: List<BlendSong>,
)

/**
 * Works a Blend out from two taste codes, the same on both phones whichever code is "yours": the codes are taken in
 * the order of their ids, and nothing random is used.
 *
 * - **Match**: artists in common (cosine over the artists' weights, square-rooted, since two people rarely share many
 *   artists exactly) and genres in common (cosine over their shares), 40 : 60.
 * - **The playlist** (up to [size]): songs both play first; then the two people's own songs in turns (each person's songs
 *   by artists the other plays come first in their list). The same artist never twice in a row where it can be helped.
 */
object BlendMaths {
    fun blend(a: TasteCode, b: TasteCode, size: Int = 50): Blend {
        val (p, q) = if (a.who <= b.who) a to b else b to a
        val match = (100 * (0.4 * sqrt(cosine(weights(p.artists), weights(q.artists))) + 0.6 * cosine(p.genres.mapValues { it.value.toDouble() }, q.genres.mapValues { it.value.toDouble() })))
            .roundToInt().coerceIn(0, 100)

        val pa = p.artists.associateBy { it.key }
        val qa = q.artists.associateBy { it.key }
        val shared = pa.keys.intersect(qa.keys).sortedWith(compareByDescending<String> { minOf(pa[it]!!.weight, qa[it]!!.weight) }.thenBy { it })
            .map { pa[it]!!.name }
        val sharedGenres = p.genres.keys.intersect(q.genres.keys)
            .sortedWith(compareByDescending<String> { minOf(p.genres[it]!!, q.genres[it]!!) }.thenBy { it })

        val ps = p.songs.sortedWith(order)
        val qs = q.songs.sortedWith(order)
        val qIds = qs.associateBy { it.id }
        val pIds = ps.associateBy { it.id }
        val out = LinkedHashMap<String, BlendSong>()
        fun add(s: CodeSong, from: Int) { if (out.size < size && s.id !in out) out[s.id] = BlendSong(s, from) }

        ps.filter { it.id in qIds }.sortedWith(compareByDescending<CodeSong> { it.weight + qIds[it.id]!!.weight }.thenBy { it.id })
            .forEach { add(it, BlendSong.BOTH) }
        fun turns(x: List<CodeSong>, y: List<CodeSong>) {
            val n = maxOf(x.size, y.size)
            for (i in 0 until n) { x.getOrNull(i)?.let { add(it, BlendSong.FIRST) }; y.getOrNull(i)?.let { add(it, BlendSong.SECOND) } }
        }
        // Each person's list (songs by artists the other plays first, then their other favourites), taken in turns,
        // so neither takes over the Blend.
        fun own(x: List<CodeSong>, others: Set<String>, theirs: Map<String, CodeArtist>) =
            x.filter { it.id !in others }.let { l -> l.filter { it.artistKey in theirs } + l.filter { it.artistKey !in theirs } }
        turns(own(ps, qIds.keys, qa), own(qs, pIds.keys, pa))

        return Blend(p to q, match, shared, sharedGenres, spreadArtists(out.values.toList()))
    }

    /** Heaviest first, then by id, so ties fall the same way on both phones. */
    private val order = compareByDescending<CodeSong> { it.weight }.thenBy { it.id }

    private fun weights(list: List<CodeArtist>) = list.associate { it.key to it.weight.toDouble() }

    fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
        var dot = 0.0
        for ((k, v) in a) b[k]?.let { dot += v * it }
        val na = sqrt(a.values.sumOf { it * it }); val nb = sqrt(b.values.sumOf { it * it })
        return if (na == 0.0 || nb == 0.0) 0.0 else (dot / (na * nb)).coerceIn(0.0, 1.0)
    }

    /** The same artist twice in a row: the second swaps with the next song by someone else. */
    private fun spreadArtists(list: List<BlendSong>): List<BlendSong> {
        val out = list.toMutableList()
        for (i in 1 until out.size) {
            if (out[i].song.artistKey != out[i - 1].song.artistKey) continue
            val j = (i + 1 until out.size).firstOrNull { out[it].song.artistKey != out[i - 1].song.artistKey } ?: continue
            val t = out[i]; out[i] = out[j]; out[j] = t
        }
        return out
    }
}
