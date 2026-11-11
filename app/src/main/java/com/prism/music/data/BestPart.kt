package com.prism.music.data

import com.prism.music.data.innertube.arr
import com.prism.music.data.innertube.findAll
import com.prism.music.data.innertube.str
import com.prism.music.data.lyrics.LyricLine
import com.prism.music.playback.Tempo
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import kotlin.math.roundToInt

/** YouTube's "Most replayed" curve for a video: how much each stretch of [segmentMs] is replayed, 0..1. */
data class Heatmap(val segmentMs: Long, val intensity: List<Float>) {
    val durationMs: Long get() = segmentMs * intensity.size

    fun encode(): String = intensity.joinToString(",") { "%.3f".format(java.util.Locale.ROOT, it) }

    companion object {
        fun decode(segmentMs: Long, s: String): Heatmap? =
            s.split(',').mapNotNull { it.toFloatOrNull() }.takeIf { it.size >= 10 }?.let { Heatmap(segmentMs, it) }
    }
}

/**
 * Where a song's best part starts: the stretch YouTube's listeners replay most (the "Most replayed" heat markers on
 * youtube.com), or, without them, the first time the chorus comes round in the lyrics. The start is then pulled back
 * to where a lyric line or a bar begins, so it doesn't land mid-word.
 */
object BestPart {
    /** Nothing in a song's opening seconds counts: everyone "replays" the start, so it's always hot. */
    const val SKIP_HEAD_MS = 5_000L
    /** A best part needs some song left after it. */
    const val SKIP_TAIL_MS = 30_000L
    /** Shorter songs (interludes, skits) have no best part to skip to. */
    const val MIN_SONG_MS = 60_000L
    /** How far (as a share) the peak must stand above the song's trend around it to count as one. */
    private const val MIN_RISE = 0.08f
    /** …and at least this much in absolute terms (curves are 0..1). */
    private const val MIN_LIFT = 0.03f
    /** The trend around a moment: the median of the curve this far either side. */
    private const val TREND_HALF_MS = 45_000.0

    /** The heat markers in a youtube.com `next` answer, or null when YouTube has none for the video (too few views). */
    fun parse(res: JsonObject): Heatmap? {
        val entity = res.findAll("macroMarkersListEntity").firstOrNull { it.str("markersList", "markerType") == "MARKER_TYPE_HEATMAP" }
            ?: return null
        val markers = entity.arr("markersList", "markers")?.mapNotNull { m ->
            val start = m.str("startMillis")?.toLongOrNull() ?: return@mapNotNull null
            val length = m.str("durationMillis")?.toLongOrNull() ?: return@mapNotNull null
            val heat = m.str("intensityScoreNormalized")?.toFloatOrNull() ?: return@mapNotNull null
            Triple(start, length, heat.coerceIn(0f, 1f))
        }?.sortedBy { it.first } ?: return null
        if (markers.size < 10) return null
        // YouTube's segments are equal (the length / 100); the median is used in case one is cut short at the end.
        val segment = markers.map { it.second }.sorted()[markers.size / 2]
        if (segment <= 0) return null
        return Heatmap(segment, markers.map { it.third })
    }

    /**
     * Where the most replayed stretch begins, in ms, for a song [durationMs] long (the heatmap's own length if 0).
     * Null when nothing stands out (a flat curve) or the song is too short.
     *
     * A curve mostly falls from start to end (listeners drift off as a song goes on), so the hottest moment is nearly
     * always near the start. What marks the best part is a bump above that trend: each moment is measured against
     * the median of the curve around it (about 45 s either side), and the biggest rise wins.
     */
    fun peakMs(h: Heatmap, durationMs: Long = 0): Long? {
        val duration = durationMs.takeIf { it > 0 } ?: h.durationMs
        if (duration < MIN_SONG_MS || h.intensity.size < 10) return null
        val seg = h.segmentMs
        val n = h.intensity.size
        // About 9 s either side together: one hot moment doesn't win over a whole hot chorus.
        val half = ((4_500.0 / seg).roundToInt()).coerceAtLeast(1)
        val smooth = FloatArray(n) { i ->
            var sum = 0f; var count = 0
            for (j in (i - half).coerceAtLeast(0)..(i + half).coerceAtMost(n - 1)) { sum += h.intensity[j]; count++ }
            sum / count
        }
        val wide = ((TREND_HALF_MS / seg).roundToInt()).coerceAtLeast(3)
        val trend = FloatArray(n) { i ->
            // The opening's own heat (everyone starts there) is left out of the trend.
            val from = (i - wide).coerceAtLeast(1)
            val window = (from..(i + wide).coerceAtMost(n - 1)).map { smooth[it] }.sorted()
            window[window.size / 2]
        }
        fun rise(i: Int) = smooth[i] / trend[i].coerceAtLeast(0.05f) - 1f
        val head = maxOf(SKIP_HEAD_MS, 2 * seg)
        val candidates = (0 until n).filter { i -> i * seg >= head && i * seg <= duration - SKIP_TAIL_MS }
        if (candidates.isEmpty()) return null
        val peak = candidates.maxBy { rise(it) }
        if (rise(peak) < MIN_RISE || smooth[peak] - trend[peak] < MIN_LIFT) return null
        // Back to where the heat starts to rise: half the peak's rise above the trend.
        val floor = rise(peak) * 0.5f
        var start = peak
        while (start - 1 in candidates && rise(start - 1) >= floor) start--
        // The rise is gradual in the smoothed curve; the raw segments say where it actually jumps.
        var jump = start
        for (i in start..peak) if (h.intensity[i] / trend[i].coerceAtLeast(0.05f) - 1f >= floor) { jump = i; break }
        return jump * seg
    }

    /**
     * The first time the chorus comes round, from time-synced lyrics: the earliest line of the most repeated lyric
     * (and the lines of that block before it). Null when nothing repeats.
     */
    fun chorusMs(lines: List<LyricLine>, durationMs: Long): Long? {
        if (durationMs in 1 until MIN_SONG_MS) return null
        val timed = lines.filter { it.timeMs >= 0 && !it.isGap && it.text.isNotBlank() }
        if (timed.size < 4) return null
        val keys = timed.map { normalize(it.text) }
        val counts = keys.filter { it.length >= 4 }.groupingBy { it }.eachCount()
        val most = counts.values.maxOrNull() ?: return null
        if (most < 2) return null
        val limit = if (durationMs > 0) durationMs - SKIP_TAIL_MS else Long.MAX_VALUE
        // The first line of the chorus block: walk back over lines that repeat as often (they're the same block).
        val first = keys.indices.firstOrNull { i -> counts[keys[i]] == most && timed[i].timeMs >= SKIP_HEAD_MS && timed[i].timeMs <= limit } ?: return null
        var start = first
        while (start - 1 >= 0 && (counts[keys[start - 1]] ?: 0) >= 2 && (counts[keys[start - 1]] ?: 0) >= most - 1 &&
            timed[start - 1].timeMs >= SKIP_HEAD_MS && first - start < 4
        ) start--
        return timed[start].timeMs
    }

    /**
     * Pulls [targetMs] back to a natural start: a lyric line beginning near it (just before, or a moment after), else
     * the bar line before it on the song's beat grid ([tempo], from the song scan), else a second before.
     */
    fun snap(targetMs: Long, lineStarts: List<Long>, tempo: Tempo?): Long {
        lineStarts.filter { it in (targetMs - 4_000)..(targetMs + 1_500) }.minByOrNull { abs(it - targetMs) + if (it > targetMs) 1_500 else 0 }?.let {
            return (it - 250).coerceAtLeast(0)
        }
        if (tempo != null && tempo.bpm > 0 && tempo.steady && tempo.firstBeatMs >= 0) {
            val bar = 4 * tempo.periodMs
            val bars = ((targetMs - tempo.firstBeatMs) / bar).toLong()
            if (bars >= 0) return (tempo.firstBeatMs + bars * bar).toLong()
        }
        return (targetMs - 1_000).coerceAtLeast(0)
    }

    private fun normalize(text: String) = text.lowercase().filter { it.isLetterOrDigit() }
}
