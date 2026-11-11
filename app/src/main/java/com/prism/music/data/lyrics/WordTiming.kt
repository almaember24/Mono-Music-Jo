package com.prism.music.data.lyrics

import java.text.Normalizer
import kotlin.math.abs

/**
 * Checks on word-by-word timings (#301: "word-by-word lyrics don't line up with the vocals" from Better Lyrics,
 * LyricsPlus and the other word sources, while line-by-line lyrics were fine).
 *
 * Two ways they go wrong that Prism can tell without listening:
 *  - **Timings that aren't the words'**: words stamped outside their own line, out of order, or every word in a line
 *    the same length (a line's time shared out evenly, not sung). Such lyrics are shown by their lines instead
 *    ([checked]), so a source with real word timings, or the line timing, wins.
 *  - **A whole set early or late**: word sources are Apple Music's, timed to Apple's copy of the song, and some of
 *    YouTube's copies start a little sooner or later (another master, a longer intro). Line-timed lyrics of the same
 *    song (YouTube Music's own, LRCLIB's: what the listener found right) show it: when the lines agree on one steady
 *    difference ([offsetAgainst]), the words are moved by it ([shifted]).
 */
object WordTiming {
    /** A word may start this much before its line or after its end (sources round a line's own times). */
    private const val LINE_SLACK_MS = 1_000L
    /** Lyrics whose checked lines are off this often (share) are shown by their lines. */
    private const val BAD_SHARE = 0.3
    /** Lyrics whose lines are shared out evenly this often are shown by their lines. */
    private const val EVEN_SHARE = 0.7

    /** How many lines must match, and what share of the word-timed lines, to trust a difference. */
    private const val MIN_MATCHES = 6
    private const val MIN_MATCHED_SHARE = 0.4
    /** How far the matched lines may scatter round their difference (median absolute deviation), in ms. */
    private const val MAX_SPREAD_MS = 250L
    /**
     * A smaller difference is left alone: line-timed lyrics are stamped a little before the singing on purpose (to show
     * the line in time), so lines and words disagree by a few hundred ms when both are right.
     */
    const val MIN_SHIFT_MS = 400L
    /** A larger one isn't the same recording: left to the listener (the timing sheet). */
    const val MAX_SHIFT_MS = 30_000L

    /** [lines] as they are, or without their word timings when those aren't the words' (see the class). */
    fun checked(lines: List<LyricLine>): List<LyricLine> {
        val timed = lines.filter { !it.isGap && it.words.size >= 2 }
        if (timed.isEmpty()) return lines
        val bad = timed.count { !fitsItsLine(it) }
        val even = timed.filter { it.words.size >= 3 }.let { l -> if (l.size < 3) 0.0 else l.count(::sharedOut).toDouble() / l.size }
        if (bad.toDouble() / timed.size < BAD_SHARE && even < EVEN_SHARE) return lines
        return lines.map { if (it.words.isEmpty() && it.background.isEmpty()) it else it.copy(words = emptyList(), background = emptyList()) }
    }

    private fun fitsItsLine(l: LyricLine): Boolean {
        val end = if (l.endMs > l.timeMs) l.endMs else Long.MAX_VALUE / 2
        var last = l.timeMs - LINE_SLACK_MS
        for (w in l.words) {
            if (w.endMs < w.startMs) return false
            if (w.startMs < l.timeMs - LINE_SLACK_MS || w.startMs > end + LINE_SLACK_MS) return false
            // A little overlap is how syllables run together; a word well before the one before it isn't.
            if (w.startMs < last - 50) return false
            last = w.startMs
        }
        return true
    }

    /** Every word the same length and each starting where the last ended: the line's time shared out, not sung. */
    private fun sharedOut(l: LyricLine): Boolean {
        val lengths = l.words.map { it.endMs - it.startMs }
        if (lengths.any { it <= 0 }) return false
        val same = lengths.max() - lengths.min() <= 2
        val joined = l.words.zipWithNext().all { (a, b) -> abs(b.startMs - a.endMs) <= 2 }
        return same && joined
    }

    /**
     * How much later [words]' lines come than the same lines in [reference] (line-timed lyrics of the same song), when
     * they agree on one steady difference of at least [MIN_SHIFT_MS]; null when they don't (too few lines in common,
     * scattered, or close enough already).
     */
    fun offsetAgainst(words: List<LyricLine>, reference: List<LyricLine>): Long? {
        val mine = words.filter { !it.isGap && it.timeMs >= 0 && key(it.text).isNotEmpty() }
        val theirs = reference.filter { !it.isGap && it.timeMs >= 0 && key(it.text).isNotEmpty() }
        if (mine.size < MIN_MATCHES || theirs.isEmpty()) return null
        val diffs = ArrayList<Long>()
        var from = 0
        for (l in mine) {
            val k = key(l.text)
            // In order, a few lines ahead at most: choruses repeat, and the nearest one that's the same line is it.
            val at = (from until minOf(theirs.size, from + LOOK_AHEAD)).firstOrNull { same(k, key(theirs[it].text)) } ?: continue
            diffs += l.timeMs - theirs[at].timeMs
            from = at + 1
        }
        if (diffs.size < MIN_MATCHES || diffs.size < mine.size * MIN_MATCHED_SHARE) return null
        val m = median(diffs)
        val spread = median(diffs.map { abs(it - m) })
        if (spread > MAX_SPREAD_MS || abs(m) < MIN_SHIFT_MS || abs(m) > MAX_SHIFT_MS) return null
        return m
    }

    /** [lines] moved [byMs] later (earlier when negative), words and background vocals with them. */
    fun shifted(lines: List<LyricLine>, byMs: Long): List<LyricLine> {
        if (byMs == 0L) return lines
        fun t(ms: Long) = if (ms < 0) ms else (ms + byMs).coerceAtLeast(0)
        fun w(list: List<LyricWord>) = list.map { it.copy(startMs = t(it.startMs), endMs = t(it.endMs)) }
        return lines.map { l -> l.copy(timeMs = t(l.timeMs), endMs = t(l.endMs), words = w(l.words), background = w(l.background)) }
    }

    private const val LOOK_AHEAD = 4

    /** A line's words for matching: lower case, no accents or punctuation. */
    private fun key(text: String): List<String> =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(ACCENTS, "").split(NOT_WORD).filter { it.isNotEmpty() }

    /** The same line: the same words, or nearly (a word more or less, "gonna" for "going to"). */
    private fun same(a: List<String>, b: List<String>): Boolean {
        if (a == b) return true
        if (a.size < 3 || b.size < 3) return false
        val shared = a.toSet().intersect(b.toSet()).size
        return shared >= 0.8 * maxOf(a.toSet().size, b.toSet().size)
    }

    private fun median(values: List<Long>): Long {
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    private val ACCENTS = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")
}
