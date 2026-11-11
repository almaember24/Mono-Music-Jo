package com.prism.music.data.lyrics.roman

/**
 * Korean in Latin letters by the Revised Romanization (the official system, what K-pop romanized lyrics use), with
 * the sound changes between syllables it writes down: a final consonant carried onto the next syllable (사랑이 →
 * "sarang-i", 같이 → "gachi"), nasal (합니다 → "hamnida") and liquid (신라 → "silla") assimilation, and ㅎ's
 * aspiration (좋고 → "joko"). Words (between spaces) are romanized on their own, as the system does.
 */
object Hangul {
    private const val BASE = 0xAC00
    private val INITIALS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private val INITIAL_ROMAN = arrayOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")
    private val MEDIAL_ROMAN = arrayOf("a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i")
    /** Final consonants by index (0 = none); compound ones as their two letters. */
    private val FINALS = arrayOf("", "ㄱ", "ㄲ", "ㄱㅅ", "ㄴ", "ㄴㅈ", "ㄴㅎ", "ㄷ", "ㄹ", "ㄹㄱ", "ㄹㅁ", "ㄹㅂ", "ㄹㅅ", "ㄹㅌ", "ㄹㅍ", "ㄹㅎ",
        "ㅁ", "ㅂ", "ㅂㅅ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ")
    private const val I_VOWEL = 20 // ㅣ

    private data class Syllable(val initial: Char, val medial: Int, val final: String)

    private fun decompose(c: Char): Syllable? {
        val code = c.code - BASE
        if (code !in 0..11171) return null
        return Syllable(INITIALS[code / 588], (code % 588) / 28, FINALS[code % 28])
    }

    private fun initialRoman(c: Char) = INITIAL_ROMAN[INITIALS.indexOf(c)]

    /** How a final sounds when nothing (or a consonant) follows: one of k, n, t, l, m, p, ng. */
    private fun representative(f: String): String = when (f) {
        "" -> ""
        "ㄱ", "ㄲ", "ㅋ", "ㄱㅅ", "ㄹㄱ" -> "k"
        "ㄴ", "ㄴㅈ", "ㄴㅎ" -> "n"
        "ㄷ", "ㅅ", "ㅆ", "ㅈ", "ㅊ", "ㅌ", "ㅎ" -> "t"
        "ㄹ", "ㄹㅂ", "ㄹㅅ", "ㄹㅌ", "ㄹㅎ" -> "l"
        "ㅁ", "ㄹㅁ" -> "m"
        "ㅂ", "ㅍ", "ㅂㅅ", "ㄹㅍ" -> "p"
        "ㅇ" -> "ng"
        else -> ""
    }

    /**
     * The meeting of syllable a's final [f] and the next syllable's initial [i] (whose vowel is [nextMedial]):
     * what's written for the final, and for the next initial.
     */
    private fun boundary(f: String, i: Char, nextMedial: Int): Pair<String, String> {
        if (f.isEmpty()) return "" to initialRoman(i)
        val rep = representative(f)
        if (i == 'ㅇ') {
            // A vowel follows: the consonant moves over to start the next syllable.
            if (f == "ㅇ") return "ng" to ""
            if (f == "ㅎ") return "" to ""
            if (nextMedial == I_VOWEL && f == "ㄷ") return "" to "j"
            if (nextMedial == I_VOWEL && f == "ㅌ") return "" to "ch"
            if (f.length == 2) return when (f) {
                "ㄴㅎ" -> "" to "n"
                "ㄹㅎ" -> "" to "r"
                else -> (if (f[0] == 'ㄹ') "l" else representative(f[0].toString())) to initialRoman(f[1])
            }
            return "" to initialRoman(f[0])
        }
        if (i == 'ㅎ') return when (rep) {
            "k" -> "" to "k"
            "t" -> if (f == "ㅈ" || f == "ㅊ") "" to "ch" else "" to "t"
            "p" -> "" to "p"
            else -> rep to "h"
        }
        // ㅎ (alone or in ㄶ, ㅀ) before ㄱ ㄷ ㅈ: the pair is said aspirated.
        if (f.endsWith("ㅎ") && i in "ㄱㄷㅈ") {
            val left = when (f) { "ㄴㅎ" -> "n"; "ㄹㅎ" -> "l"; else -> "" }
            return left to when (i) { 'ㄱ' -> "k"; 'ㄷ' -> "t"; else -> "ch" }
        }
        // Nasalization: k t p before ㄴ ㅁ become ng n m (and before ㄹ, which then sounds as ㄴ).
        if (rep in setOf("k", "t", "p") && i in "ㄴㅁㄹ") {
            val nasal = when (rep) { "k" -> "ng"; "t" -> "n"; else -> "m" }
            return nasal to if (i == 'ㄹ') "n" else initialRoman(i)
        }
        // Liquids: ㄴ and ㄹ side by side are both l.
        if ((rep == "l" && i in "ㄴㄹ") || (rep == "n" && i == 'ㄹ')) return "l" to "l"
        if ((rep == "m" || rep == "ng") && i == 'ㄹ') return rep to "n"
        return rep to initialRoman(i)
    }

    /** Each character of [text] spelled out: a syllable's share of the romanization, anything else as it is. */
    fun pieces(text: String): List<String> {
        val out = MutableList(text.length) { text[it].toString() }
        var i = 0
        while (i < text.length) {
            if (decompose(text[i]) == null) { i++; continue }
            // A run of syllables with nothing between them: one word.
            var end = i
            while (end < text.length && decompose(text[end]) != null) end++
            val syl = (i until end).map { decompose(text[it])!! }
            val inits = Array(syl.size) { k -> if (k == 0) initialRoman(syl[0].initial) else "" }
            val finals = Array(syl.size) { "" }
            for (k in syl.indices) {
                if (k + 1 < syl.size) {
                    val (f, next) = boundary(syl[k].final, syl[k + 1].initial, syl[k + 1].medial)
                    finals[k] = f
                    inits[k + 1] = next
                } else finals[k] = representative(syl[k].final)
            }
            for (k in syl.indices) out[i + k] = inits[k] + MEDIAL_ROMAN[syl[k].medial] + finals[k]
            i = end
        }
        return out
    }

    fun romanize(text: String): String = pieces(text).joinToString("")

    fun isHangul(c: Char) = decompose(c) != null
}
