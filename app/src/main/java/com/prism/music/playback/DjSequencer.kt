package com.prism.music.playback

import com.prism.music.R
import com.prism.music.tr
import kotlin.math.abs
import kotlin.math.ln

/** How a DJ set's energy should go: level, rising to a peak, or settling down. */
enum class DjFlow(val label: String) { STEADY(tr(R.string.steady)), BUILD(tr(R.string.build_up)), WIND_DOWN(tr(R.string.wind_down)) }

/** What DJ mode knows about a song: its key and tempo when they're clear, its loudness, and its artist. */
data class DjTrack(val id: String, val key: MusicKey?, val bpm: Double?, val loudness: Float?, val artist: String?)

/**
 * Orders songs into a DJ set, the way a DJ picks the next record: in a key that blends (the Camelot wheel), at a
 * tempo close enough to beat-match (octave-free, so 85 and 170 BPM sit together), along an energy curve ([DjFlow]),
 * and not the same artist twice running. A greedy pass from the playing song, then 2-opt (reversing stretches of
 * the set while that makes it smoother overall). Pure, so it's unit-tested.
 */
object DjSequencer {
    private const val W_KEY = 1.0
    private const val W_TEMPO = 0.8
    private const val W_ENERGY = 0.6
    private const val W_ARTIST = 0.7
    /** Tempos up to this far apart (8%) can be beat-matched: cheap. */
    private val TEMPO_MATCH = ln(1.08)
    /** Beyond matching, the gap costs more up to this far apart (25%), the most past it. */
    private val TEMPO_FAR = ln(1.25)
    /** A key that isn't clear: neither a neighbour nor a clash, but nothing to build a blend on either. */
    private const val KEY_UNKNOWN = 0.5

    /**
     * The set's order: [tracks] (not [start], the song before them) arranged to follow on from [start] as smoothly
     * as can be found.
     */
    fun order(start: DjTrack, tracks: List<DjTrack>, flow: DjFlow): List<DjTrack> {
        if (tracks.size < 2) return tracks
        val energy = energies(listOf(start) + tracks)
        val n = tracks.size
        // Greedy: each next song is the one that follows best from the last.
        val left = tracks.indices.toMutableList()
        val seq = ArrayList<Int>(n)
        var prev = -1
        while (left.isNotEmpty()) {
            val pos = seq.size + 1
            val pick = left.minBy { i -> step(trackOf(start, tracks, prev), tracks[i], energyOf(energy, prev), energy[i + 1], pos, n, flow) }
            seq += pick; left.remove(pick); prev = pick
        }
        // 2-opt: reverse any stretch that lowers the whole set's cost, until none does (a few passes at most). Steps
        // count squared, so one clash costs more than a few small compromises: a set is judged by its worst blends.
        var best = total(start, tracks, seq, energy, flow)
        repeat(6) {
            var improved = false
            for (i in 0 until n - 1) for (j in i + 1 until n) {
                seq.subList(i, j + 1).reverse()
                val c = total(start, tracks, seq, energy, flow)
                if (c < best - 1e-9) { best = c; improved = true } else seq.subList(i, j + 1).reverse()
            }
            if (!improved) return seq.map { tracks[it] }
        }
        return seq.map { tracks[it] }
    }

    private fun trackOf(start: DjTrack, tracks: List<DjTrack>, i: Int) = if (i < 0) start else tracks[i]
    private fun energyOf(energy: DoubleArray, i: Int) = energy[i + 1]

    /** The whole set's cost, [seq] following [start] (each step's cost squared). */
    fun total(start: DjTrack, tracks: List<DjTrack>, seq: List<Int>, energy: DoubleArray, flow: DjFlow): Double {
        var sum = 0.0
        var prev = -1
        for ((k, i) in seq.withIndex()) {
            val s = step(trackOf(start, tracks, prev), tracks[i], energyOf(energy, prev), energy[i + 1], k + 1, seq.size, flow)
            sum += s * s
            prev = i
        }
        return sum
    }

    /** What going from [a] to [b] costs, [b] being the [pos]th of [n] songs in the set. */
    fun step(a: DjTrack, b: DjTrack, ea: Double, eb: Double, pos: Int, n: Int, flow: DjFlow): Double {
        val key = if (a.key?.sure == true && b.key?.sure == true) MusicKey.clash(a.key, b.key) else KEY_UNKNOWN
        val tempo = tempoGap(a.bpm, b.bpm)?.let { g ->
            if (g <= TEMPO_MATCH) 0.5 * g / TEMPO_MATCH else 0.5 + 0.5 * ((g - TEMPO_MATCH) / (TEMPO_FAR - TEMPO_MATCH)).coerceAtMost(1.0)
        } ?: 0.5
        // Where in the set [b] falls, 0 (first) to 1 (last): the energy curve's target there.
        val p = if (n > 1) (pos - 1).toDouble() / (n - 1) else 0.5
        val energy = when (flow) {
            DjFlow.STEADY -> abs(eb - ea)
            DjFlow.BUILD -> abs(eb - p)
            DjFlow.WIND_DOWN -> abs(eb - (1 - p))
        }
        val artist = if (a.artist != null && a.artist.equals(b.artist, ignoreCase = true)) 1.0 else 0.0
        return W_KEY * key + W_TEMPO * tempo + W_ENERGY * energy + W_ARTIST * artist
    }

    /** How far apart two tempos are (|ln ratio|), octave-free; null when either is unknown. */
    fun tempoGap(a: Double?, b: Double?): Double? {
        if (a == null || b == null || a <= 0 || b <= 0) return null
        return listOf(b, b * 2, b / 2).minOf { abs(ln(it / a)) }
    }

    /**
     * Each song's energy within the set, 0 (calmest) to 1 (most intense): its loudness and tempo, ranked among the
     * set's songs (all but the first, the one playing, which is placed among them), so it's relative to the set rather
     * than an absolute scale. Unknown counts as the middle.
     */
    fun energies(tracks: List<DjTrack>): DoubleArray {
        val raw = tracks.map { t ->
            val loud = t.loudness?.toDouble()
            // ~10 BPM counts like 1 dB (a faster song feels more intense at the same loudness).
            if (loud == null) null else loud + ((t.bpm ?: 110.0) - 110.0) / 10.0
        }
        val known = raw.withIndex().drop(1).filter { it.value != null }.sortedBy { it.value }
        val out = DoubleArray(tracks.size) { 0.5 }
        if (known.size < 2) return out
        for ((rank, v) in known.withIndex()) out[v.index] = rank.toDouble() / (known.size - 1)
        raw[0]?.let { first -> out[0] = known.count { it.value!! < first }.toDouble() / known.size }
        return out
    }
}
