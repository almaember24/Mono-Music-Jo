package com.prism.music.data.lyrics

import com.prism.music.data.db.LyricsDao
import com.prism.music.data.db.LyricsEntity
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.innertube.str
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.LyricsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.roundToInt

/** One sung word (or syllable) with its own timing, for word-by-word highlighting. */
data class LyricWord(val startMs: Long, val endMs: Long, val text: String)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val endMs: Long = -1,
    val words: List<LyricWord> = emptyList(),
    /** Second voice in a duet; drawn right-aligned. */
    val alignEnd: Boolean = false,
    /** An instrumental break between lines. */
    val isGap: Boolean = false,
    /** Background vocals sung with the line ("(Back to let you know)"), word-timed; drawn smaller under it (#66). */
    val background: List<LyricWord> = emptyList(),
) {
    val isWordSynced: Boolean get() = words.isNotEmpty()

    /** The background vocals as a line of their own, for drawing; null when there are none. */
    val backgroundLine: LyricLine? get() = if (background.isEmpty()) null else
        LyricLine(background.first().startMs, background.joinToString("") { it.text }.trim(), background.last().endMs, background, alignEnd)
}

data class Lyrics(
    val source: LyricsSource,
    val synced: Boolean,
    val lines: List<LyricLine>,
) {
    val plain: String get() = lines.joinToString("\n") { it.text }
    val wordSynced: Boolean get() = lines.any { it.isWordSynced }
    /** Swear words starred or blanked out ("f**k", "sh*t", "[censored]"). */
    val censored: Boolean by lazy { lines.any { !it.isGap && censorMark.containsMatchIn(it.text) } }
}

/**
 * A word with starred-out letters ("f*ck", "f***", "sh**", "**ck", "f##k"), a word starred out whole ("****",
 * as Apple Music's lyrics do), or an outright "[censored]" / "(bleep)". A lone "*laughs*" style aside doesn't count.
 */
private val censorMark = Regex(
    "\\p{L}[*#]+\\p{L}|\\b\\p{L}{1,3}[*#]{2,}|[*#]{2,}\\p{L}{1,3}\\b|(?<![\\p{L}\\p{N}*#])[*#]{3,}(?![\\p{L}\\p{N}*#])|[\\[(](?:censored|bleep(?:ed)?)[\\])]",
    RegexOption.IGNORE_CASE,
)

/** A word starred out completely: nothing of it shows, and the number of stars needn't match its length. */
private fun String.allStars() = length >= 3 && all { it == '*' || it == '#' }

/**
 * Puts starred-out words ("f**k", "sh*t") back from an uncensored text of the same song (Genius),
 * keeping every line and word timing, so karaoke lyrics read in full. Each censored line is paired
 * with the reference line sharing most of its other words; a starred word then takes the reference
 * word that fits its letters (same length first) nearest where its neighbours put it. Words that
 * can't be placed with confidence stay as they were.
 */
object Uncensor {
    private val wordRe = Regex("[\\p{L}\\p{N}'’*#]+")
    private val apostropheRe = Regex("['’]")
    private val ingRe = Regex("(?<=\\p{L}{2})ing$")
    /** A word for matching: no case or apostrophes, and "doing" / "doin'" alike. */
    private fun key(w: String) = w.lowercase().replace(apostropheRe, "").replace(ingRe, "in")
    /** Whether [text] still has a starred-out word. */
    fun isStarred(text: String) = censorMark.containsMatchIn(text)
    private fun isCensored(w: String) = w.allStars() || (w.any { it.isLetter() } && censorMark.containsMatchIn(w))

    fun fill(lyrics: Lyrics, reference: String?): Lyrics {
        if (reference.isNullOrBlank() || !lyrics.censored) return lyrics
        val single = reference.lines()
            .map { l -> wordRe.findAll(l).map { it.value }.filter { w -> w.any(Char::isLetterOrDigit) }.toList() }
            .filter { it.isNotEmpty() }
        if (single.isEmpty()) return lyrics
        // Sources break long lines in different places: a line may be two of the reference's, run together.
        val ref = single + single.zipWithNext { a, b -> a + b }
        // Each reference line's keys are worked out once, not again for every starred line (a long explicit song froze the player).
        val refKeys = ref.map { r -> r.map(::key) }
        val refSets = refKeys.map { it.toSet() }
        return lyrics.copy(lines = lyrics.lines.map { if (it.isGap || !censorMark.containsMatchIn(it.text)) it else fillLine(it, ref, refKeys, refSets) })
    }

    internal fun fillLine(
        line: LyricLine,
        ref: List<List<String>>,
        refKeys: List<List<String>> = ref.map { r -> r.map(::key) },
        refSets: List<Set<String>> = refKeys.map { it.toSet() },
    ): LyricLine {
        val words = wordRe.findAll(line.text).map { it.value }.toList()
        val clean = words.filterNot(::isCensored).map(::key)
        if (clean.isEmpty()) return line
        val bestAt = ref.indices.maxByOrNull { j ->
            clean.count { it in refSets[j] } - abs(ref[j].size - words.size) * 0.01
        } ?: return line
        val best = ref[bestAt]
        val bestKeys = refKeys[bestAt]
        // Most of the line's other words have to be there, or it's a different line.
        if (clean.count { it in bestKeys } < maxOf(1, (clean.size * 0.6).roundToInt())) return line
        val swaps = ArrayList<Pair<String, String>>()
        val cleanSet = clean.toSet()
        words.forEachIndexed { i, w ->
            if (!isCensored(w)) return@forEachIndexed
            val expected = expectedIndex(i, words, bestKeys, best.size)
            val pick = if (w.allStars()) {
                // No letters to go by (and "****" can stand for "nigga"): the reference word right where the stars
                // sit, and one the line doesn't have elsewhere, or nothing.
                // Placed by its nearest neighbours on both sides, at every spot each appears ("you" can be there three times).
                val spots = neighbourSpots(i, words, bestKeys).ifEmpty { listOf(expected) }
                fun off(j: Int) = spots.minOf { abs(j - it) }
                best.indices.filter { j -> !isCensored(best[j]) && key(best[j]) !in cleanSet }
                    .minByOrNull(::off)?.takeIf { off(it) <= 1.0 } ?: return@forEachIndexed
            } else candidates(w, best, exact = true).ifEmpty { candidates(w, best, exact = false) }
                .minByOrNull { abs(it - expected) } ?: return@forEachIndexed
            swaps += w to matchCase(w, best[pick])
        }
        if (swaps.isEmpty()) return line
        var text = line.text
        swaps.forEach { (from, to) -> text = text.replaceFirst(from, to) }
        // Karaoke words carry the same starred text; they're swapped in the same order.
        var k = 0
        val newWords = line.words.map { lw ->
            var t = lw.text
            while (k < swaps.size && t.contains(swaps[k].first)) { t = t.replaceFirst(swaps[k].first, swaps[k].second); k++ }
            if (t == lw.text) lw else lw.copy(text = t)
        }
        return line.copy(text = text, words = newWords)
    }

    /** Where word [i] should sit in the reference line, going by its nearest uncensored neighbour. */
    private fun expectedIndex(i: Int, words: List<String>, refKeys: List<String>, refSize: Int): Double {
        for (d in 1 until words.size) {
            words.getOrNull(i - d)?.takeIf { !isCensored(it) }?.let { w -> refKeys.indexOf(key(w)).takeIf { it >= 0 }?.let { return (it + d).toDouble() } }
            words.getOrNull(i + d)?.takeIf { !isCensored(it) }?.let { w -> refKeys.indexOf(key(w)).takeIf { it >= 0 }?.let { return (it - d).toDouble() } }
        }
        return i.toDouble() * refSize / words.size.coerceAtLeast(1)
    }

    /** Where word [i] could sit in the reference line: one past each place its left neighbour is, one before each of its right. */
    private fun neighbourSpots(i: Int, words: List<String>, refKeys: List<String>): List<Double> {
        val out = ArrayList<Double>()
        val left = (i - 1 downTo 0).firstOrNull { !isCensored(words[it]) }
        val right = (i + 1 until words.size).firstOrNull { !isCensored(words[it]) }
        left?.let { l -> refKeys.indices.filter { refKeys[it] == key(words[l]) }.forEach { out += (it + (i - l)).toDouble() } }
        right?.let { r -> refKeys.indices.filter { refKeys[it] == key(words[r]) }.forEach { out += (it - (r - i)).toDouble() } }
        return out
    }

    /** Reference words a starred word could be: its letters where they show, any letter under each star. */
    private fun candidates(censored: String, ref: List<String>, exact: Boolean): List<Int> {
        val pattern = StringBuilder()
        var i = 0
        while (i < censored.length) {
            val ch = censored[i]
            if (ch == '*' || ch == '#') {
                var n = 0
                while (i < censored.length && (censored[i] == '*' || censored[i] == '#')) { n++; i++ }
                pattern.append(if (exact) "\\p{L}{$n}" else "\\p{L}+")
                continue
            }
            pattern.append(if (ch == '\'' || ch == '’') "['’]?" else Regex.escape(ch.lowercase()))
            i++
        }
        val re = Regex(pattern.toString())
        return ref.indices.filter { j -> !isCensored(ref[j]) && re.matches(ref[j].lowercase()) }
    }

    private fun matchCase(censored: String, word: String): String {
        val letters = censored.filter { it.isLetter() }
        return when {
            letters.length > 1 && letters.all { it.isUpperCase() } -> word.uppercase()
            letters.firstOrNull()?.isUpperCase() == true && censored.first().isLetter() -> word.replaceFirstChar { it.uppercase() }
            else -> word.replaceFirstChar { it.lowercase() }
        }
    }
}

private val creditLine = Regex(
    "^(\\s*(lyrics?|words|composed|composer|written|writer|produced|producer|arranged|music|mixed|mastered|vocals?)\\s*(by)?\\s*[:：]|.*(作词|作曲|编曲|制作人|制作|混音|母带|和声|吉他|贝斯|鼓)\\s*[:：]).*",
    RegexOption.IGNORE_CASE,
)

private fun String.decodeEntities(): String = replace("&amp;", "&").replace("&apos;", "'").replace("&#39;", "'")
    .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")

/** Telling editions of a song apart by their titles. */
object Editions {
    private val words = listOf(
        // Not "version" or "remaster" on their own: "Taylor's Version" and a remaster keep the song's timing.
        "single version", "single edit", "radio edit", "radio version", "edit", "live", "remix", "extended",
        "acoustic", "instrumental", "demo", "short version", "long version", "club mix", "unplugged", "sped up", "slowed",
    )

    // Compiled once: marks() runs for every saved lyric row read back (all downloads' lyrics at each start).
    private val bracketed = Regex("[(\\[]([^)\\]]*)[)\\]]")
    private val wordPatterns = words.map { it to Regex("(?<![\\p{L}])${Regex.escape(it)}(?![\\p{L}])") }

    /** The words in brackets or after " - " that name an edition ("Single Version", "Live at Wembley"). */
    private fun marks(title: String): Set<String> {
        val t = title.lowercase()
        val tails = bracketed.findAll(t).map { it.groupValues[1] } + t.substringAfter(" - ", "")
        val tail = tails.joinToString(" ")
        // A word that isn't in the tail at all can't be a mark: skip its regex (and the native matcher it makes).
        return wordPatterns.filter { (w, re) -> w in tail && re.containsMatchIn(tail) }.map { it.first }.toSet()
    }

    /**
     * Whether [found] (a lyric file's own title) names an edition the song [wanted] isn't, like KuGou's
     * "Thriller(Single Version)" for the album's "Thriller". Unknown titles pass.
     */
    fun otherEdition(found: String?, wanted: String): Boolean {
        if (found.isNullOrBlank()) return false
        val want = marks(wanted)
        return marks(found).any { m -> m !in want && want.none { it.contains(m) || m.contains(it) } }
    }
}

object Lrc {
    private val tag = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val meta = Regex("^\\[(ar|ti|al|by|offset|length|re|ve|id|au|la|tool|#):.*]$", RegexOption.IGNORE_CASE)
    private val offsetTag = Regex("^\\[offset:\\s*([+-]?\\d+)\\s*]$", RegexOption.IGNORE_CASE)
    /** A word's start in enhanced LRC: "[00:12.00]<00:12.00>Word <00:12.48>by". */
    private val wordTag = Regex("<(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?>")

    fun isLrc(text: String) = tag.containsMatchIn(text)

    private fun ms(min: String, sec: String, frac: String): Long =
        min.toLong() * 60_000 + sec.toLong() * 1000 + when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }

    /** Enhanced LRC's line as its words ("Word " keeps the space after it), each to the next one's start; empty for a plain line. */
    private fun words(content: String, lineEndMs: Long): List<LyricWord> {
        val tags = wordTag.findAll(content).toList()
        if (tags.isEmpty()) return emptyList()
        return tags.mapIndexedNotNull { i, m ->
            val text = content.substring(m.range.last + 1, tags.getOrNull(i + 1)?.range?.first ?: content.length)
            if (text.isEmpty()) return@mapIndexedNotNull null
            val start = ms(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            val next = tags.getOrNull(i + 1)?.let { ms(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
            LyricWord(start, maxOf(start, next ?: lineEndMs), text)
        }
    }

    /** An ID tag's value ("[ti:Thriller(Single Version)]" → "Thriller(Single Version)"), or null. */
    fun metaTag(text: String, name: String): String? =
        Regex("^\\[${Regex.escape(name)}:(.*)]\\s*$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).find(text)?.groupValues?.get(1)?.trim()

    fun parse(text: String): List<LyricLine> {
        val raw = mutableListOf<Pair<Long, String>>()
        // [offset:+500] means the lyrics come 500 ms sooner than their stamps say. It used to be skipped
        // with the other tags, which left every line of such a file that much late (or early).
        var offset = 0L
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            offsetTag.find(line)?.let { offset = it.groupValues[1].toLongOrNull() ?: 0L; continue }
            if (line.isEmpty() || meta.matches(line)) continue
            val stamps = tag.findAll(line).toList()
            if (stamps.isEmpty()) continue
            val content = line.substring(stamps.last().range.last + 1).trim()
            if (creditLine.matches(content.replace(wordTag, ""))) continue
            for (m in stamps) raw += ms(m.groupValues[1], m.groupValues[2], m.groupValues[3]) to content
        }
        val sorted = raw.map { (t, s) -> (t - offset).coerceAtLeast(0) to s }.sortedBy { it.first }
        return sorted.mapIndexed { i, (t, s) ->
            val end = sorted.getOrNull(i + 1)?.first ?: (t + 5000)
            val text = s.replace(wordTag, "")
            // Enhanced LRC ([words]), as [com.prism.music.data.local.LyricsTags] writes it into files: the stamps never show.
            val words = if (text.length == s.length) emptyList() else words(s, end + offset).map { w ->
                w.copy(startMs = (w.startMs - offset).coerceAtLeast(0), endMs = (w.endMs - offset).coerceAtLeast(0))
            }
            LyricLine(t, text.trim(), end, words = words, isGap = text.isBlank())
        }.withLeadingGap()
    }

    fun plainLines(text: String) = text.lines().map { LyricLine(-1, it.trim(), isGap = it.isBlank()) }
}

/** An intro before the first sung line reads as an instrumental break. */
private fun List<LyricLine>.withLeadingGap(): List<LyricLine> {
    val first = firstOrNull { !it.isGap } ?: return this
    return if (first.timeMs > 3000 && firstOrNull()?.isGap != true) listOf(LyricLine(0, "", first.timeMs, isGap = true)) + this else this
}

/** Apple Music TTML (word or line timed), as served by Better Lyrics. */
object Ttml {
    private val para = Regex("<p\\b([^>]*)>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
    private val wordSpan = Regex("<span\\b([^>]*)>([^<]*)</span>")
    private fun attr(attrs: String, name: String) = Regex("\\b$name=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)

    fun time(t: String?): Long? {
        if (t.isNullOrBlank()) return null
        val s = t.trim().removeSuffix("s")
        return runCatching { (s.split(":").fold(0.0) { acc, p -> acc * 60 + p.toDouble() } * 1000).toLong() }.getOrNull()
    }

    /**
     * Takes the background-vocal spans (which nest spans of their own) out of a line: the line without them, and what
     * was inside each, for the background vocals under it (#66).
     */
    private fun splitBackground(content: String): Pair<String, List<String>> {
        val sb = StringBuilder(content)
        val background = mutableListOf<String>()
        while (true) {
            val start = Regex("<span[^>]*ttm:role=\"x-bg\"[^>]*>").find(sb) ?: break
            var depth = 0
            var end = -1
            val tags = Regex("<span\\b[^>]*>|</span>").findAll(sb, start.range.first)
            for (m in tags) {
                if (m.value.startsWith("</")) depth-- else depth++
                if (depth == 0) { end = m.range.last + 1; break }
            }
            if (end < 0) break
            // What's between the background span's own tags.
            background += sb.substring(start.range.last + 1, end - "</span>".length)
            sb.delete(start.range.first, end)
        }
        return sb.toString() to background
    }

    /** A word-timed run of spans as words, a space kept after each word that has one after it. */
    private fun timedWords(content: String): List<LyricWord> {
        val words = mutableListOf<LyricWord>()
        var last = 0
        for (w in wordSpan.findAll(content)) {
            val between = content.substring(last, w.range.first)
            if (words.isNotEmpty() && between.contains(Regex("\\s"))) {
                val prev = words.removeAt(words.lastIndex)
                words += prev.copy(text = prev.text + " ")
            }
            val a = w.groupValues[1]
            val s = time(attr(a, "begin")) ?: continue
            val e = time(attr(a, "end")) ?: s
            words += LyricWord(s, e, w.groupValues[2].decodeEntities())
            last = w.range.last + 1
        }
        return words
    }

    fun parse(ttml: String): List<LyricLine> {
        val wordTimed = ttml.contains("itunes:timing=\"Word\"") || ttml.contains("timing=\"Word\"")
        // Untimed documents (timing="None") are plain lyrics, one line per <p>, verses split by <div>.
        if (ttml.contains("timing=\"None\"") || !Regex("<p\\b[^>]*\\bbegin=").containsMatchIn(ttml)) {
            val body = ttml.substringAfter("<body", "")
            val divs = Regex("<div\\b[^>]*>(.*?)</div>", RegexOption.DOT_MATCHES_ALL).findAll(body).map { it.groupValues[1] }.toList().ifEmpty { listOf(body) }
            return divs.flatMapIndexed { i, div ->
                val lines = para.findAll(div).map { LyricLine(-1, it.groupValues[2].replace(Regex("<[^>]+>"), "").decodeEntities().trim()) }
                    .filter { it.text.isNotBlank() }.toList()
                if (i > 0 && lines.isNotEmpty()) listOf(LyricLine(-1, "", isGap = true)) + lines else lines
            }
        }
        val out = mutableListOf<LyricLine>()
        for (m in para.findAll(ttml)) {
            val attrs = m.groupValues[1]
            val begin = time(attr(attrs, "begin")) ?: continue
            val end = time(attr(attrs, "end")) ?: begin
            val agent = attr(attrs, "ttm:agent")
            val (content, bgParts) = splitBackground(m.groupValues[2])
            val words = if (wordTimed) timedWords(content) else emptyList()
            // Several background runs in one line follow each other with a space between.
            val background = if (!wordTimed) emptyList() else bgParts.map(::timedWords).filter { it.isNotEmpty() }
                .flatMapIndexed { i, run -> if (i == 0) run else listOf(run.first().copy(text = " " + run.first().text.trimStart())) + run.drop(1) }
            val text = if (words.isNotEmpty()) words.joinToString("") { it.text }.trim()
            else content.replace(Regex("<[^>]+>"), "").decodeEntities().trim()
            if (text.isBlank()) continue
            val lineEnd = maxOf(end, background.lastOrNull()?.endMs ?: 0L)
            out += LyricLine(begin, text, lineEnd, words, alignEnd = agent != null && agent != "v1", background = background)
        }
        return out.withInstrumentalGaps()
    }
}

/** Instrumental breaks of 6s+ between lines become gap rows. */
internal fun List<LyricLine>.withInstrumentalGaps(): List<LyricLine> {
    val withGaps = mutableListOf<LyricLine>()
    sortedBy { it.timeMs }.forEachIndexed { i, l ->
        val prevEnd = withGaps.lastOrNull()?.endMs ?: 0L
        if (i > 0 && l.timeMs - prevEnd > 6000) withGaps += LyricLine(prevEnd, "", l.timeMs, isGap = true)
        withGaps += l
    }
    return withGaps.withLeadingGap()
}

/** LyricsPlus v2 JSON: lines with syllable timings that are glued back into words. */
object LyricsPlusFormat {
    const val PREFIX = "LYRICSPLUS-JSON\n"

    fun parse(body: String): List<LyricLine> {
        val root = runCatching { InnerTube.json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val lines = root["lyrics"] as? JsonArray ?: return emptyList()
        if (root.str("type") == "None") return emptyList()
        val agents = (root["metadata"] as? JsonObject)?.get("agents") as? JsonObject
        val multiVoice = (agents?.size ?: 0) > 1
        val out = lines.mapNotNull { line ->
            val start = line.str("time")?.toLongOrNull() ?: return@mapNotNull null
            val dur = line.str("duration")?.toLongOrNull() ?: 0L
            val singer = (line as? JsonObject)?.get("element").str("singer")
            val syllables = (line as? JsonObject)?.get("syllabus") as? JsonArray ?: JsonArray(emptyList())
            // Background vocals ("isBackground") are their own words under the line (#66); they used to run on in it.
            val (bgSyl, mainSyl) = syllables.partition { it.str("isBackground") == "true" }
            val words = glue(mainSyl)
            val background = glue(bgSyl)
            val text = if (words.isNotEmpty()) words.joinToString("") { it.text }.trim()
                else line.str("text")?.trim()?.takeIf { background.isEmpty() }.orEmpty()
            if (text.isBlank()) return@mapNotNull null
            val end = maxOf(if (dur > 0) start + dur else start + 5000, background.lastOrNull()?.endMs ?: 0L)
            LyricLine(start, text, end, words, alignEnd = multiVoice && singer != null && singer != "v1", background = background)
        }
        return out.withInstrumentalGaps()
    }

    /** Syllables glued back into words (a word ends where a syllable ends in a space). */
    private fun glue(syllables: List<JsonElement>): List<LyricWord> {
        val words = mutableListOf<LyricWord>()
        val current = StringBuilder()
        var ws = 0L
        var we = 0L
        for (syl in syllables) {
            val t = syl.str("text") ?: continue
            if (t.isBlank()) continue
            val st = syl.str("time")?.toLongOrNull() ?: continue
            if (current.isEmpty()) ws = st
            current.append(t.trim())
            we = st + (syl.str("duration")?.toLongOrNull() ?: 0L)
            if (t.last().isWhitespace()) { words += LyricWord(ws, we, "$current "); current.setLength(0) }
        }
        if (current.isNotEmpty()) words += LyricWord(ws, we, current.toString())
        return words
    }
}

/**
 * Karaoke formats with a timing for every word (or syllable):
 *  - QRC (QQ Music): `[lineStart,lineDur]word(start,dur)word(start,dur)…`
 *  - YRC (NetEase): `[lineStart,lineDur](start,dur,0)word(start,dur,0)word…`
 *  - KRC (KuGou): `[lineStart,lineDur]<offset,dur,0>word<offset,dur,0>word…` (offsets from the line start)
 * Stored with a one-line header naming the format.
 */
object WordFormat {
    enum class Kind { QRC, YRC, KRC }

    const val PREFIX = "WORDS-"
    fun wrap(kind: Kind, body: String) = "$PREFIX${kind.name}\n$body"

    private val lineHead = Regex("^\\[(\\d+),(\\d+)]")
    private val qrcTag = Regex("\\((\\d+),(\\d+)\\)")
    private val yrcTag = Regex("\\((\\d+),(\\d+),-?\\d+\\)")
    private val krcTag = Regex("<(\\d+),(\\d+),-?\\d+>")

    fun parse(stored: String): List<LyricLine> {
        val kind = runCatching { Kind.valueOf(stored.substringAfter(PREFIX).substringBefore('\n').trim()) }.getOrNull() ?: return emptyList()
        return parse(kind, stored.substringAfter('\n'))
    }

    fun parse(kind: Kind, body: String): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        for (raw in body.lines()) {
            val line = raw.trim()
            val head = lineHead.find(line) ?: continue
            val start = head.groupValues[1].toLong()
            val dur = head.groupValues[2].toLong()
            val rest = line.substring(head.range.last + 1)
            // Each tag's text and absolute timing, in order.
            val tokens = mutableListOf<Triple<Long, Long, String>>()
            when (kind) {
                Kind.QRC -> {
                    var last = 0
                    for (m in qrcTag.findAll(rest)) {
                        tokens += Triple(m.groupValues[1].toLong(), m.groupValues[2].toLong(), rest.substring(last, m.range.first))
                        last = m.range.last + 1
                    }
                }
                Kind.YRC, Kind.KRC -> {
                    val tags = (if (kind == Kind.YRC) yrcTag else krcTag).findAll(rest).toList()
                    tags.forEachIndexed { i, m ->
                        val text = rest.substring(m.range.last + 1, tags.getOrNull(i + 1)?.range?.first ?: rest.length)
                        val t = m.groupValues[1].toLong() + if (kind == Kind.KRC) start else 0
                        tokens += Triple(t, m.groupValues[2].toLong(), text)
                    }
                }
            }
            if (tokens.isEmpty()) continue
            // Syllables are glued into words; a space (on a syllable or on its own) ends a word.
            val words = mutableListOf<LyricWord>()
            val cur = StringBuilder()
            var ws = 0L
            var we = 0L
            for ((t, d, text) in tokens) {
                if (text.isEmpty()) continue
                if (text.first().isWhitespace() && cur.isNotEmpty()) {
                    words += LyricWord(ws, we, "$cur ")
                    cur.setLength(0)
                }
                val word = text.trim()
                if (word.isNotEmpty()) {
                    if (cur.isEmpty()) ws = t
                    cur.append(word)
                    we = t + d
                }
                if (text.last().isWhitespace() && cur.isNotEmpty()) {
                    words += LyricWord(ws, we, "$cur ")
                    cur.setLength(0)
                }
            }
            if (cur.isNotEmpty()) words += LyricWord(ws, we, cur.toString())
            val text = words.joinToString("") { it.text }.decodeEntities().trim()
            if (text.isBlank() || creditLine.matches(text)) continue
            // QQ and KuGou open with a "Title - Artist" card before the song starts.
            if (out.isEmpty() && start < 1000 && text.contains(" - ")) continue
            out += LyricLine(start, text, start + dur, words.map { it.copy(text = it.text.decodeEntities()) })
        }
        return out.withInstrumentalGaps()
    }

    /** KuGou's KRC files: "krc1", then XOR with a fixed key, then zlib. */
    fun decodeKrc(base64: String): String? = runCatching {
        val bytes = java.util.Base64.getMimeDecoder().decode(base64)
        if (bytes.size < 5 || String(bytes, 0, 4, Charsets.US_ASCII) != "krc1") return null
        val key = intArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105)
        val data = ByteArray(bytes.size - 4) { i -> (bytes[i + 4].toInt() xor key[i % 16]).toByte() }
        // KuGou's files can start with a byte-order mark, which then stuck to the first line's first word (#403's KAIRO).
        java.util.zip.InflaterInputStream(data.inputStream()).bufferedReader(Charsets.UTF_8).readText().replace("﻿", "")
    }.getOrNull()
}

/** Fetches lyrics from several providers, with per-source caching. */
class LyricsRepository(
    private val http: OkHttpClient,
    private val dao: LyricsDao,
    private val ytm: YouTubeMusic,
    private val settings: () -> AppSettings,
) {
    private val browserUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    private fun cleanTitle(t: String) = t
        .replace(Regex("\\s*[(\\[](official|lyric|lyrics|audio|video|visualizer|music video|mv|hd|4k|explicit)[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*-\\s*(official|lyric).*$", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "").replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun getText(url: okhttp3.HttpUrl, headers: Map<String, String> = emptyMap()): String? {
        val b = Request.Builder().url(url).header("User-Agent", browserUa)
        headers.forEach { (k, v) -> b.header(k, v) }
        return http.newCall(b.build()).execute().use { r -> if (!r.isSuccessful) null else r.body.string() }
    }

    private fun getJson(url: okhttp3.HttpUrl, headers: Map<String, String> = emptyMap()): JsonElement? =
        getText(url, headers)?.let { runCatching { InnerTube.json.parseToJsonElement(it) }.getOrNull() }

    private fun JsonElement?.field(key: String): JsonElement? = (this as? JsonObject)?.get(key)

    /**
     * Whether an edition [sec] seconds long can be the song playing. Sources that pick "the closest length" took
     * an extended or radio edit 10–30 s off when that was all they had, and every line ran early or late.
     * A few seconds' difference (fade-outs, rounding) is normal; unknown lengths pass.
     */
    private fun lengthFits(song: Song, sec: Double?): Boolean =
        song.durationSec <= 0 || sec == null || sec <= 0 || abs(sec - song.durationSec) <= MAX_LENGTH_GAP_SEC

    // Word timings that aren't the words' (outside their line, every word the same length) are shown by their lines (#301).
    private fun build(source: LyricsSource, text: String?): Lyrics? = parse(source, text)?.let(::untimedIfBogus)
        ?.let { l -> if (!l.wordSynced) l else WordTiming.checked(l.lines).let { lines -> if (lines === l.lines) l else l.copy(lines = lines) } }

    /** A saved answer's lines as plain text, whatever its format (Search's lyric search). */
    fun textLines(stored: String): List<String> =
        parse(LyricsSource.LRCLIB, stored)?.lines?.filter { !it.isGap && it.text.isNotBlank() }?.map { it.text }.orEmpty()

    /**
     * Some sources label plain lyrics as synced and stamp every line 0:00 (LyricsPlus for "4 Steps Back, 2 Steps Forward"
     * by Motion Sick): the player then sits on the last line from the start. Lines that don't move through the song are
     * shown as plain text instead, so a source with real timings wins.
     */
    private fun untimedIfBogus(l: Lyrics): Lyrics {
        if (!l.synced) return l
        val real = l.lines.filter { !it.isGap && it.timeMs >= 0 }
        if (real.size < 3 || real.map { it.timeMs }.distinct().size > 1) return l
        return Lyrics(l.source, false, l.lines.filter { !it.isGap }.map { LyricLine(-1, it.text) })
    }

    private fun parse(source: LyricsSource, text: String?): Lyrics? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trimStart()
        return when {
            trimmed.startsWith(WordFormat.PREFIX) -> WordFormat.parse(trimmed).takeIf { l -> l.any { !it.isGap } }
                ?.let { Lyrics(source, true, it) }
            trimmed.startsWith(LyricsPlusFormat.PREFIX) -> LyricsPlusFormat.parse(trimmed.removePrefix(LyricsPlusFormat.PREFIX))
                .takeIf { it.isNotEmpty() }?.let { lines -> Lyrics(source, lines.any { it.timeMs >= 0 }, lines) }
            trimmed.startsWith("<tt") -> Ttml.parse(trimmed).takeIf { l -> l.any { !it.isGap } }
                ?.let { lines -> Lyrics(source, lines.any { it.timeMs >= 0 }, lines) }
            Lrc.isLrc(text) -> Lrc.parse(text).takeIf { l -> l.any { !it.isGap } }?.let { Lyrics(source, true, it) }
            else -> Lyrics(source, false, Lrc.plainLines(text))
        }
    }

    // ------------------------------------------------------------ Providers

    /** Apple Music's own word-timed lyrics (TTML), via the Better Lyrics API. */
    private fun betterLyricsUrl(base: String, song: Song) = base.toHttpUrl().newBuilder()
        .addQueryParameter("s", cleanTitle(song.title))
        .addQueryParameter("a", song.primaryArtist)
        .apply {
            if (song.durationSec > 0) addQueryParameter("d", song.durationSec.toString())
            song.album?.title?.let { addQueryParameter("al", it) }
        }.build()

    private fun fetchApple(song: Song): String? {
        for (base in listOf("https://api.betterlyrics.org/getLyrics", "https://lyrics-api.boidu.dev/getLyrics")) {
            runCatching { getJson(betterLyricsUrl(base, song)).str("ttml") }.getOrNull()?.takeIf { it.contains("<p") }?.let { return it }
        }
        return null
    }

    /**
     * QQ Music's word-timed karaoke lyrics, as Better Lyrics serves them ("Portato").
     * Only songs Better Lyrics already has on file come back; it no longer looks up new ones without a key.
     */
    private fun fetchQQ(song: Song): String? {
        val xml = getJson(betterLyricsUrl("https://api.betterlyrics.org/qq/getLyrics", song)).str("lyrics") ?: return null
        val content = Regex("LyricContent=\"(.*?)\"\\s*/?>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.decodeEntities()
            ?: return null
        return when {
            Regex("\\[\\d+,\\d+]").containsMatchIn(content) -> WordFormat.wrap(WordFormat.Kind.QRC, content)
            Lrc.isLrc(content) -> content
            else -> null
        }
    }

    private fun fetchLrcLib(song: Song): String? {
        val title = cleanTitle(song.title)
        val base = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", song.primaryArtist)
        song.album?.title?.let { base.addQueryParameter("album_name", it) }
        if (song.durationSec > 0) base.addQueryParameter("duration", song.durationSec.toString())
        val exact = runCatching { getJson(base.build()) }.getOrNull()
        val pick = exact ?: run {
            val search = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("track_name", title)
                .addQueryParameter("artist_name", song.primaryArtist).build()
            val list = (runCatching { getJson(search) }.getOrNull() as? JsonArray) ?: return null
            list.minByOrNull { abs((it.str("duration")?.toDoubleOrNull() ?: 0.0) - song.durationSec) }
                ?.takeIf { lengthFits(song, it.str("duration")?.toDoubleOrNull()) }
        }
        return pick.str("syncedLyrics")?.takeIf { it.isNotBlank() } ?: pick.str("plainLyrics")
    }

    internal fun fetchNetEase(song: Song): String? {
        val headers = mapOf("Referer" to "https://music.163.com/")
        val search = "https://music.163.com/api/search/get".toHttpUrl().newBuilder()
            .addQueryParameter("s", "${cleanTitle(song.title)} ${song.primaryArtist}")
            .addQueryParameter("type", "1").addQueryParameter("limit", "10").build()
        val songs = getJson(search, headers).field("result").field("songs") as? JsonArray ?: return null
        val wantTitle = norm(cleanTitle(song.title))
        val wantArtist = norm(song.primaryArtist)
        val candidates = songs.filter { s ->
            val t = norm(s.str("name") ?: "")
            val artists = (s.field("artists") as? JsonArray)?.map { norm(it.str("name") ?: "") } ?: emptyList()
            (t == wantTitle || t.contains(wantTitle) || wantTitle.contains(t)) &&
                artists.any { it.isNotBlank() && (it.contains(wantArtist) || wantArtist.contains(it)) } &&
                lengthFits(song, s.str("duration")?.toDoubleOrNull()?.div(1000))
        }.sortedBy { abs((it.str("duration")?.toLongOrNull() ?: 0L) / 1000 - song.durationSec) }
        // Several editions often match; not all of them carry lyrics.
        for (best in candidates.take(4)) {
            val id = best.str("id") ?: continue
            val lyric = "https://music.163.com/api/song/lyric".toHttpUrl().newBuilder()
                .addQueryParameter("id", id).addQueryParameter("lv", "1").addQueryParameter("kv", "1")
                .addQueryParameter("yv", "1").addQueryParameter("tv", "-1").build()
            val res = getJson(lyric, headers)
            // Word-by-word (YRC) when NetEase has it, else line-timed LRC.
            res.field("yrc").str("lyric")?.takeIf { it.contains(Regex("\\[\\d+,\\d+]\\(")) }?.let { return WordFormat.wrap(WordFormat.Kind.YRC, it) }
            res.field("lrc").str("lyric")?.takeIf { Lrc.isLrc(it) }?.let { return it }
        }
        return null
    }

    private suspend fun fetchYtMusic(song: Song): String? {
        val next = ytm.next(song.id, queue = false)
        val id = next.lyricsBrowseId ?: return null
        // Time-synced when YouTube Music has them (its app's "live lyrics"; many songs other sources lack, Arabic ones
        // among them), else its plain text.
        runCatching { ytm.syncedLyrics(id) }.getOrNull()?.let { return it }
        return ytm.lyrics(id)
    }

    /**
     * The file hashes of KuGou song-search hits ([results], song_search_v2) that are [song]: its title, one of its
     * singers and its length; best first, up to 3.
     */
    internal fun kuGouSongHashes(results: JsonElement?, song: Song): List<String> {
        val title = norm(cleanTitle(song.title))
        val artist = norm(song.primaryArtist)
        val hits = results.field("data").field("lists") as? JsonArray ?: return emptyList()
        if (title.isBlank() || artist.isBlank()) return emptyList()
        return hits.filter { s ->
            val t = norm(s.str("OriSongName")?.takeIf { it.isNotBlank() } ?: s.str("SongName") ?: "")
            val singers = (s.str("SingerName") ?: "").split('、', ',', '&').map(::norm)
            t.isNotBlank() && (t == title || t.contains(title) || title.contains(t)) &&
                singers.any { it.isNotBlank() && (it.contains(artist) || artist.contains(it)) } &&
                lengthFits(song, s.str("Duration")?.toDoubleOrNull())
        }.mapNotNull { it.str("FileHash")?.takeIf { h -> h.isNotBlank() } }.take(3)
    }

    private fun fetchKuGou(song: Song): String? {
        fun search(keyword: String, hash: String = "") = "https://lyrics.kugou.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("ver", "1").addQueryParameter("man", "yes").addQueryParameter("client", "pc")
            .addQueryParameter("keyword", keyword)
            .addQueryParameter("duration", (song.durationSec * 1000L).toString())
            .addQueryParameter("hash", hash).build()
        fun found(url: okhttp3.HttpUrl) =(getJson(url).field("candidates") as? JsonArray)?.takeIf { it.isNotEmpty() }
        // KuGou's song search, which (unlike its lyric search) doesn't go by the artist's exact case.
        fun songHashes() = kuGouSongHashes(getJson("https://songsearch.kugou.com/song_search_v2".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", "${song.primaryArtist} ${cleanTitle(song.title)}")
            .addQueryParameter("page", "1").addQueryParameter("pagesize", "10")
            .addQueryParameter("platform", "WebFilter").build()), song)
        // KuGou matches "Artist - Title" far more often than the other way round. The lyric search goes by the artist's
        // exact case ("KAIRO" finds nothing, "Kairo" does, #403); the song search's file hash finds those.
        val candidates = listOf("${song.primaryArtist} - ${cleanTitle(song.title)}", "${cleanTitle(song.title)} - ${song.primaryArtist}")
            .firstNotNullOfOrNull { found(search(it)) }
            ?: songHashes().firstNotNullOfOrNull { found(search("", it)) } ?: return null
        val fitting = candidates.filter { lengthFits(song, it.str("duration")?.toDoubleOrNull()?.div(1000)) }
            .sortedBy { abs((it.str("duration")?.toLongOrNull() ?: 0L) - song.durationSec * 1000L) }
        // KuGou lists some edits under the album's length: "Thriller" (5:57) came back as "[ti:Thriller(Single Version)]",
        // every line 45 s early. The lyrics' own title says which edition they are; another one is passed over.
        for (best in fitting.take(4)) {
            val id = best.str("id") ?: continue
            val key = best.str("accesskey") ?: continue
            fun download(fmt: String) = "https://lyrics.kugou.com/download".toHttpUrl().newBuilder()
                .addQueryParameter("fmt", fmt).addQueryParameter("charset", "utf8").addQueryParameter("client", "pc")
                .addQueryParameter("ver", "1").addQueryParameter("id", id).addQueryParameter("accesskey", key).build()
            // Word-by-word KRC first, line-timed LRC as the fallback.
            val text = getJson(download("krc")).str("content")?.let(WordFormat::decodeKrc)?.takeIf { it.contains(Regex("\\[\\d+,\\d+]<")) }
                ?.let { WordFormat.wrap(WordFormat.Kind.KRC, it) }
                ?: getJson(download("lrc")).str("content")?.let { String(java.util.Base64.getMimeDecoder().decode(it), Charsets.UTF_8) }
                ?: continue
            if (Editions.otherEdition(Lrc.metaTag(text, "ti"), song.title)) continue
            return text
        }
        return null
    }

    /** Plain lyrics with [Verse]/[Chorus] headers from Genius. */
    private fun fetchGenius(song: Song): String? {
        // Genius titles leave guests out: "IV. Sweatpants (feat. Jason Martin)" is "IV. Sweatpants" there.
        val bare = cleanTitle(song.title).replace(Regex("\\s*[(\\[](?:feat|ft|with)\\.?\\s[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE), "").trim()
        val q = "$bare ${song.primaryArtist}"
        val search = "https://genius.com/api/search/multi".toHttpUrl().newBuilder().addQueryParameter("q", q).build()
        val sections = getJson(search).field("response").field("sections") as? JsonArray ?: return null
        val hits = sections.firstOrNull { it.str("type") == "song" }.field("hits") as? JsonArray ?: return null
        val title = norm(bare)
        val artist = norm(song.primaryArtist)
        val url = hits.map { it.field("result") }.firstOrNull { r ->
            val t = norm(r.str("title") ?: "")
            val a = norm(r.str("primary_artist_names") ?: r.field("primary_artist").str("name") ?: "")
            (t.contains(title) || title.contains(t)) && (a.contains(artist) || artist.contains(a))
        }.str("url") ?: return null
        val doc = Jsoup.parse(getText(url.toHttpUrl()) ?: return null)
        val containers = doc.select("div[data-lyrics-container=true]")
        if (containers.isEmpty()) return null
        val text = containers.joinToString("\n") { c ->
            c.select("[data-exclude-from-selection=true]").remove()
            c.select("br").append("\\n")
            c.text().replace("\\n", "\n").lines().joinToString("\n") { it.trim() }
        }
        return text.replace(Regex("\\n{3,}"), "\n\n").trim().takeIf { it.isNotBlank() }
    }

    /** Apple Music TTML through the Bini catalogue (a second matcher over Apple's lyrics). */
    private fun fetchBini(song: Song): String? {
        fun query(withDuration: Boolean) = "https://lyrics-api.binimum.org/".toHttpUrl().newBuilder()
            .addQueryParameter("track", cleanTitle(song.title))
            .addQueryParameter("artist", song.primaryArtist)
            .apply { if (withDuration && song.durationSec > 0) addQueryParameter("duration", song.durationSec.toString()) }
            .build()
        // Its duration match is strict, and YouTube's length often differs by a second or two.
        val results = (getJson(query(true)).field("results") as? JsonArray)?.takeIf { it.isNotEmpty() }
            ?: getJson(query(false)).field("results") as? JsonArray ?: return null
        val title = norm(cleanTitle(song.title))
        // Prefer word-timed editions, then line-timed, then the closest length.
        fun timingRank(t: String?) = when (t) { "word" -> 0; "line" -> 1; else -> 2 }
        val fitting = results.filter { lengthFits(song, it.str("duration")?.toDoubleOrNull()) }
        val best = fitting.filter { norm(it.str("track_name") ?: "").let { t -> t == title || t.startsWith(title) } }
            .sortedWith(compareBy({ timingRank(it.str("timing_type")) }, { abs((it.str("duration")?.toIntOrNull() ?: 0) - song.durationSec) }))
            .firstOrNull() ?: fitting.firstOrNull() ?: return null
        val doc = best.str("lyricsUrl")?.takeIf { it.startsWith("https://") } ?: return null
        return getText(doc.toHttpUrl())?.takeIf { it.contains("<tt") }
    }

    private val lyricsPlusMirrors = listOf(
        "https://lyricsplus.binimum.org",
        "https://lyricsplus.prjktla.workers.dev",
        "https://lyricsplus.prjktla.my.id",
        "https://lyricsplus.atomix.one",
        "https://lyricsplus-seven.vercel.app",
        "https://lyrics-plus-backend.vercel.app",
    )
    @Volatile private var lyricsPlusGood: String? = null
    private val quickHttp by lazy {
        http.newBuilder().callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
    }

    /** Syllable-timed lyrics from LyricsPlus (Apple / QQ / Musixmatch), across volunteer mirrors. */
    private fun fetchLyricsPlus(song: Song): String? {
        val hosts = lyricsPlusGood?.let { listOf(it) + lyricsPlusMirrors.filter { m -> m != it } } ?: lyricsPlusMirrors
        for (host in hosts) {
            val url = "$host/v2/lyrics/get".toHttpUrl().newBuilder()
                .addQueryParameter("title", cleanTitle(song.title))
                .addQueryParameter("artist", song.primaryArtist)
                .apply {
                    if (song.durationSec > 0) addQueryParameter("duration", song.durationSec.toString())
                    song.album?.title?.let { addQueryParameter("album", it) }
                }.build()
            val (code, body) = runCatching {
                quickHttp.newCall(Request.Builder().url(url).header("User-Agent", browserUa).build()).execute()
                    .use { r -> r.code to (if (r.isSuccessful) r.body.string() else null) }
            }.getOrNull() ?: continue
            // The mirror known to work saying it hasn't got the song is the answer: asking the others as well only made
            // a song without lyrics wait longer. (A dead mirror answers 404 to everything, so only the good one counts.)
            if (code == 404 && host == lyricsPlusGood) return null
            if (body == null) continue
            if (LyricsPlusFormat.parse(body).isNotEmpty()) {
                lyricsPlusGood = host
                return LyricsPlusFormat.PREFIX + body
            }
        }
        return null
    }

    /** Community-submitted lyrics (TTML, LRC or plain) from Unison. */
    private fun fetchUnison(song: Song): String? {
        val url = "https://unison.boidu.dev/lyrics".toHttpUrl().newBuilder()
            .addQueryParameter("song", cleanTitle(song.title))
            .addQueryParameter("artist", song.primaryArtist)
            .apply {
                song.album?.title?.let { addQueryParameter("album", it) }
                if (song.durationSec > 0) addQueryParameter("duration", song.durationSec.toString())
            }.build()
        val res = getJson(url) ?: return null
        if (res.str("success") != "true") return null
        return res.field("data").str("lyrics")?.takeIf { it.isNotBlank() }
    }

    /** [source]'s lyrics for [song] (saved copy first), with any starred-out words filled in ([uncensor]). */
    suspend fun fetch(song: Song, source: LyricsSource, force: Boolean = false): Lyrics? =
        fetchRaw(song, source, force)?.let { uncensor(song, it) }?.let { linedUp(song, it) }

    /**
     * Where each song's word timings were found early or late against its line-timed lyrics (#301): "<song id>|<source>"
     * -> the difference in ms ("0" when they agree), or "?<when>" when there were no line-timed lyrics to compare with.
     */
    var wordShifts: android.content.SharedPreferences? = null

    /**
     * Word-by-word lyrics lined up with the song's line-timed lyrics when the two disagree by one steady amount
     * ([WordTiming.offsetAgainst]): worked out once per song and source, then kept. Call it once on lyrics as read (it
     * moves them). With [network] the line-timed lyrics may be asked for; with nothing to compare with, that's asked
     * again after a week, and a reference that doesn't come in time is asked for the next time the lyrics load. Without
     * it, only line-timed lyrics already on the phone are compared with.
     */
    private suspend fun linedUp(song: Song, l: Lyrics, network: Boolean = true): Lyrics {
        // A file's own lyrics are timed to that file, and no service gets asked about them.
        if (!l.wordSynced || l.source == LyricsSource.EMBEDDED) return l
        val prefs = wordShifts ?: return l
        val key = shiftKey(song, l.source)
        val kept = prefs.getString(key, null)
        kept?.toLongOrNull()?.let { return withShift(l, it) }
        val asked = kept?.removePrefix("?")?.toLongOrNull()
        val reference = if (!network) lineReference(song, network = false).ifEmpty { return l }
        else {
            if (asked != null && System.currentTimeMillis() - asked < RECHECK_SHIFT_MS) return l
            kotlinx.coroutines.withTimeoutOrNull(REFERENCE_WAIT_MS) { lineReference(song, network = true) } ?: return l
        }
        if (reference.isEmpty()) {
            prefs.edit().putString(key, "?${System.currentTimeMillis()}").apply()
            return l
        }
        val shift = WordTiming.offsetAgainst(l.lines, reference) ?: 0L
        prefs.edit().putString(key, shift.toString()).apply()
        if (shift != 0L) com.prism.music.playback.AudioDiagnostics.log("lyrics", "${l.source.name} words $shift ms off the line timing for ${song.id}: lined up")
        return withShift(l, shift)
    }

    /** Whether [l]'s words were compared with the song's line timing already ([linedUp]). */
    private fun shiftKnown(song: Song, l: Lyrics) = l.source == LyricsSource.EMBEDDED || wordShifts?.getString(shiftKey(song, l.source), null)?.toLongOrNull() != null

    private fun shiftKey(song: Song, source: LyricsSource) = "${song.id}|${source.name}"

    private fun withShift(l: Lyrics, lateMs: Long) = if (lateMs == 0L) l else l.copy(lines = WordTiming.shifted(l.lines, -lateMs))

    /**
     * The song's line-timed lyrics to compare word timings with: a saved copy first (YouTube Music's own, timed to
     * YouTube's audio, before the rest), else LRCLIB's or YouTube Music's from the network when the listener has those
     * sources on. Empty when there are none.
     */
    private suspend fun lineReference(song: Song, network: Boolean): List<LyricLine> {
        val rows = withContext(Dispatchers.IO) { dao.allFor(song.id) }.associateBy { it.source }
        // Every source's line-timed copy will do (a word source's too, when it only had lines for this song).
        val order = listOf(LyricsSource.YTMUSIC, LyricsSource.LRCLIB) + LyricsSource.entries.filter { it != LyricsSource.YTMUSIC && it != LyricsSource.LRCLIB }
        fun lines(l: Lyrics?) = l?.takeIf { it.synced && !it.wordSynced }?.lines
        order.firstNotNullOfOrNull { src -> rows[src.name]?.let { lines(build(src, it.content)) } }?.let { return it }
        if (!network) return emptyList()
        val on = settings().lyricsSources
        for (src in listOf(LyricsSource.LRCLIB, LyricsSource.YTMUSIC).filter { it in on }) {
            // Not runCatching: the wait running out cancels this, and that has to get through (or "none" would be kept).
            val got = try { fetchRaw(song, src) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
            lines(got)?.let { return it }
        }
        return emptyList()
    }

    /**
     * Starred-out words put back from Genius's full text of the song, every timing kept ([Uncensor]).
     * Only looked up when the lyrics are censored and the listener skips censored lyrics.
     */
    private suspend fun uncensor(song: Song, l: Lyrics): Lyrics {
        if (!l.censored || l.source == LyricsSource.GENIUS || !skipCensored()) return l
        // Genius first; when it doesn't have the song, any other source's uncensored text does as well.
        fun starred(x: Lyrics) = x.lines.count { !it.isGap && Uncensor.isStarred(it.text) }
        var best = l
        for (src in REFERENCE_SOURCES) {
            if (src == l.source) continue
            val reference = runCatching { fetchRaw(song, src) }.getOrNull()?.takeIf { !it.censored } ?: continue
            // Off the main thread: the player's lyrics are loaded from a UI scope.
            val filled = withContext(Dispatchers.Default) { Uncensor.fill(best, reference.plain) }
            if (!filled.censored) return filled
            if (starred(filled) < starred(best)) best = filled
        }
        return best
    }

    private suspend fun fetchRaw(song: Song, source: LyricsSource, force: Boolean = false): Lyrics? = withContext(Dispatchers.IO) {
        // Never saved in the database: the file is the copy, and it may change.
        if (source == LyricsSource.EMBEDDED) return@withContext inFile(song)
        if (!force) dao.get(song.id, source.name)?.let { cached ->
            // A real answer is kept; a "not found" is retried after a day.
            // NetEase and KuGou answers from before they went word-by-word are fetched again once.
            val upgrade = source in setOf(LyricsSource.NETEASE, LyricsSource.KUGOU) && cached.fetchedAt < WORDS_SINCE &&
                !cached.content.startsWith(WordFormat.PREFIX)
            // Answers from before the length check ([lengthFits]) may be another edit's timings: asked again once.
            val recheck = (source in LENGTH_CHECKED && cached.fetchedAt < LENGTH_CHECK_SINCE) ||
                // KuGou's lyrics for another edition of the song (its own title says so), saved before that was checked.
                (source == LyricsSource.KUGOU && Editions.otherEdition(Lrc.metaTag(cached.content, "ti"), song.title)) ||
                // YouTube Music's plain text, saved before its synced lyrics were asked for: asked again once.
                (source == LyricsSource.YTMUSIC && cached.fetchedAt < YT_SYNCED_SINCE && !Lrc.isLrc(cached.content))
            if (cached.content.isNotEmpty() && !upgrade && !recheck) return@withContext build(source, cached.content)
            if (cached.content.isEmpty() && System.currentTimeMillis() - cached.fetchedAt < 86_400_000L) return@withContext null
        }
        // If asking again fails (no network), what was saved still serves.
        val saved = if (force) null else dao.get(song.id, source.name)?.content?.takeIf { it.isNotEmpty() }
        // The title and artist the listener gave this song's lyrics, if any (#63); saved under the song all the same.
        val asked = searchSong(song)
        val text = try {
            when (source) {
                LyricsSource.APPLE -> fetchApple(asked)
                LyricsSource.LYRICSPLUS -> fetchLyricsPlus(asked)
                LyricsSource.BINI -> fetchBini(asked)
                LyricsSource.QQ -> fetchQQ(asked)
                LyricsSource.LRCLIB -> fetchLrcLib(asked)
                LyricsSource.NETEASE -> fetchNetEase(asked)
                LyricsSource.YTMUSIC -> fetchYtMusic(song)
                LyricsSource.KUGOU -> fetchKuGou(asked)
                LyricsSource.UNISON -> fetchUnison(asked)
                LyricsSource.GENIUS -> fetchGenius(asked)
                LyricsSource.EMBEDDED -> null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Never record a cancelled lookup as "no lyrics".
            throw e
        } catch (e: Exception) {
            // Network trouble isn't an answer either; don't cache it.
            return@withContext saved?.let { build(source, it) }
        }
        val lyrics = build(source, text)
        dao.put(LyricsEntity(song.id, source.name, lyrics?.synced == true, if (lyrics == null) "" else text ?: "", System.currentTimeMillis()))
        lyrics
    }

    private companion object {
        /** When NetEase and KuGou started returning word-by-word lyrics (Oct 2026). */
        const val WORDS_SINCE = 1_791_080_000_000L
        /** How far (s) a source's edition may differ in length from the song playing. */
        const val MAX_LENGTH_GAP_SEC = 8.0
        /** When the length check came in (2026-10-14); saved answers from these sources before it are checked again. */
        const val LENGTH_CHECK_SINCE = 1_791_936_000_000L
        /** YouTube Music's synced lyrics came in (1.6.4); plain text from it saved before this is asked for again. */
        const val YT_SYNCED_SINCE = 1_792_627_200_000L // 2026-10-22
        /** How long [auto] waits on a source before asking the next one as well. */
        const val HEAD_START_MS = 1_200L
        /** How long word lyrics wait on line-timed ones to compare with (#301), and when "none to compare with" is asked again. */
        const val REFERENCE_WAIT_MS = 2_500L
        const val RECHECK_SHIFT_MS = 7 * 86_400_000L
        val LENGTH_CHECKED = setOf(LyricsSource.KUGOU, LyricsSource.NETEASE, LyricsSource.LRCLIB, LyricsSource.BINI)

        /** Where the full words for starred-out lyrics come from, in order. */
        val REFERENCE_SOURCES = listOf(LyricsSource.GENIUS, LyricsSource.YTMUSIC, LyricsSource.LRCLIB)

        /** Sources that can have a timing for every word. */
        val WORD_SOURCES = setOf(
            LyricsSource.APPLE, LyricsSource.LYRICSPLUS, LyricsSource.BINI, LyricsSource.QQ,
            LyricsSource.NETEASE, LyricsSource.KUGOU, LyricsSource.UNISON,
        )
    }

    /** The lyrics text in the file [Song] plays from, if it has one (#344). Blocking: only called on IO. */
    var fileLyrics: ((Song) -> String?)? = null

    private fun inFile(song: Song): Lyrics? =
        runCatching { fileLyrics?.invoke(song) }.getOrNull()?.let { build(LyricsSource.EMBEDDED, it) }

    /** Remembers which source the listener picked for a song (song id -> source name). */
    var choices: android.content.SharedPreferences? = null

    /**
     * Another title / artist to look a song's lyrics up by (#63): sources often have the lyrics under a title without
     * "(feat. …)", or a translated one. Song id -> "title\u0001artist" ("" artist: the song's own).
     */
    var searchNames: android.content.SharedPreferences? = null

    /** What the lyrics are looked up as, when the listener changed it: (title, artist). */
    fun searchAs(song: Song): Pair<String, String>? =
        searchNames?.getString(song.id, null)?.split('\u0001')?.let { it[0] to it.getOrElse(1) { "" } }

    /** Sets (or, with a blank title or the song's own, clears) what [song]'s lyrics are looked up as. */
    fun setSearchAs(song: Song, title: String, artist: String) {
        val t = title.trim()
        val a = artist.trim().takeIf { it != song.artistText && it != song.primaryArtist }.orEmpty()
        searchNames?.edit()?.apply {
            if (t.isEmpty() || (t == song.title && a.isEmpty())) remove(song.id) else putString(song.id, "$t\u0001$a")
        }?.apply()
    }

    private fun searchSong(song: Song): Song = searchAs(song)?.let { (t, a) ->
        song.copy(title = t, artists = if (a.isBlank()) song.artists else listOf(com.prism.music.data.model.ArtistRef(a)))
    } ?: song

    fun choose(song: Song, source: LyricsSource) {
        choices?.edit()?.putString(song.id, source.name)?.apply()
    }

    fun forgetChoice(song: Song) {
        choices?.edit()?.remove(song.id)?.apply()
    }

    private fun chosen(song: Song): LyricsSource? =
        choices?.getString(song.id, null)?.let { n -> LyricsSource.entries.firstOrNull { it.name == n } }

    /** Lyrics already saved on the phone (downloaded or seen before), without touching the network: karaoke first. */
    private suspend fun saved(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        val s = settings()
        val rows = dao.allFor(song.id).associateBy { it.source }
            .filterNot { (src, row) -> src == LyricsSource.KUGOU.name && Editions.otherEdition(Lrc.metaTag(row.content, "ti"), song.title) }
        // Genius's text (or another saved uncensored one), if it's saved, fills in starred-out words offline too.
        val reference = if (!s.skipCensored) null else REFERENCE_SOURCES.firstNotNullOfOrNull { src ->
            rows[src.name]?.let { build(src, it.content) }?.takeIf { !it.censored }?.plain
        }
        val all = s.lyricsSources.mapNotNull { src -> if (src == LyricsSource.EMBEDDED) inFile(song) else rows[src.name]?.let { build(src, it.content) } }
            .map { if (it.source == LyricsSource.GENIUS) it else Uncensor.fill(it, reference) }
            .filterNot { skipCensored() && it.censored }
        if (!s.preferSynced) return@withContext all.firstOrNull()
        all.maxByOrNull { level(it) }
    }

    private fun skipCensored() = settings().skipCensored

    /** Starred-out lyrics are passed over while another source might have the real words. */
    private fun unwanted(l: Lyrics) = skipCensored() && l.censored

    /** How good a set of lyrics is: word-by-word 3, line-synced 2, plain 1, none 0. */
    fun level(l: Lyrics?): Int = when {
        l == null -> 0
        l.wordSynced -> 3
        l.synced -> 2
        else -> 1
    }

    /** The best lyrics for [song]: saved on the phone, or (with [fetch]) the best any source has, karaoke first. */
    suspend fun best(song: Song, fetch: Boolean): Lyrics? = saved(song)?.let { linedUp(song, it, network = false) } ?: if (fetch) download(song) else null

    /** The best lyrics saved on the phone for [song], as a [level]. */
    suspend fun savedLevel(song: Song): Int = level(saved(song))

    /** Lyrics already on the phone (the source picked for the song first), with no lyric service asked (#95). */
    suspend fun savedOnly(song: Song): Lyrics? {
        if (song.isEpisode) return null
        val picked = chosen(song)?.let { src ->
            if (src == LyricsSource.EMBEDDED) withContext(Dispatchers.IO) { inFile(song) }
            else withContext(Dispatchers.IO) { dao.allFor(song.id) }.firstOrNull { it.source == src.name }?.let { build(src, it.content) }
        }
        return (picked ?: saved(song))?.let { linedUp(song, it, network = false) }
    }

    /**
     * For downloads: karaoke (word-by-word) lyrics from any source that has them, then
     * line-synced, then plain, so the best version is on the phone for offline listening.
     * The listener's own pick for the song still wins.
     */
    suspend fun download(song: Song): Lyrics? {
        chosen(song)?.let { src -> fetch(song, src)?.let { return it } }
        saved(song)?.takeIf { it.wordSynced }?.let { return linedUp(song, it) }
        val order = settings().lyricsSources
        var best: Lyrics? = null
        var censored: Lyrics? = null
        for (src in order.filter { it in WORD_SOURCES } + order.filter { it !in WORD_SOURCES }) {
            // Line-synced is already in hand: the rest of the sources only ever have that or less.
            if (src !in WORD_SOURCES && level(best) >= 2) break
            val l = fetch(song, src) ?: continue
            if (unwanted(l)) { if (level(l) > level(censored)) censored = l; continue }
            if (l.wordSynced) return l
            if (level(l) > level(best)) best = l
        }
        return best ?: censored
    }

    /**
     * The listener's own pick for this song if they made one, else whatever's
     * already saved (so downloaded songs have lyrics offline, instantly), else
     * sources in the user's order, preferring karaoke (word by word), then time-synced lines, when enabled.
     */
    suspend fun auto(song: Song, force: Boolean = false): Lyrics? {
        if (song.isEpisode) return null
        val s = settings()
        var savedBest: Lyrics? = null
        if (!force) {
            chosen(song)?.let { src -> fetch(song, src)?.let { return it } }
            // Saved karaoke is as good as it gets. Saved line-synced lyrics used to be kept for good, so a song never
            // found the karaoke a later source had; now the sources are asked (their own saved answers come back
            // instantly), and the saved copy is the fallback, offline too.
            savedBest = saved(song)?.let { linedUp(song, it, network = false) }
            // Saved karaoke not yet checked against the song's line timing (#301) is checked once, the network allowed.
            savedBest?.takeIf { it.wordSynced || !s.preferSynced }?.let { return if (it.wordSynced && !shiftKnown(song, it)) linedUp(song, it) else it }
        }
        var plainFallback: Lyrics? = null
        var censored: Lyrics? = null
        val order = s.lyricsSources
        // The sources used to be asked strictly one after another, so a song most of them don't have (or one slow
        // source) waited for each in turn: about a minute for some songs. Now each source has [HEAD_START_MS] to
        // answer before the next one is asked as well (at once if it's done sooner), and the answers are still taken
        // in the listener's order. Whatever is still out when the choice is made is dropped.
        val job = kotlinx.coroutines.SupervisorJob()
        val asks = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + job)
        try {
            val answers = order.map { src ->
                asks.async(start = kotlinx.coroutines.CoroutineStart.LAZY) { runCatching { fetch(song, src, force) }.getOrNull() }
            }
            asks.launch {
                for (a in answers) {
                    a.start()
                    kotlinx.coroutines.withTimeoutOrNull(HEAD_START_MS) { a.join() }
                }
            }
            for ((i, src) in order.withIndex()) {
                val l = answers[i].await() ?: continue
                if (unwanted(l)) { if (censored == null || (l.synced && !censored.synced)) censored = l; continue }
                if (l.wordSynced || !s.preferSynced) return l
                if (l.synced) {
                    // Karaoke beats time-synced lines, even from a source further down the list: those that can have
                    // it are asked as well, all at once so it stays quick, and the first in the listener's order wins.
                    val rest = order.indices.drop(i + 1).filter { order[it] in WORD_SOURCES }.map { answers[it] }
                    rest.forEach { it.start() }
                    return rest.firstNotNullOfOrNull { a -> a.await()?.takeIf { it.wordSynced && !unwanted(it) } } ?: l
                }
                if (plainFallback == null) plainFallback = l
            }
        } finally {
            job.cancel()
        }
        // Saved lyrics beat what's left (offline, say); censored words beat no words at all.
        return savedBest?.takeIf { it.synced } ?: plainFallback ?: savedBest ?: censored
    }
}
