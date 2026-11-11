package com.prism.music.data.lyrics.roman

/**
 * Japanese kana in Latin letters (Hepburn): digraphs (きゃ → "kya", しゅ → "shu"), the small っ doubling the next
 * consonant (きって → "kitte", まっちゃ → "matcha"), ー lengthening the vowel before it (コーヒー → "koohii"),
 * and the newer katakana pairs (ファ → "fa", ティ → "ti").
 */
object Kana {
    private val BASIC = mapOf(
        'ア' to "a", 'イ' to "i", 'ウ' to "u", 'エ' to "e", 'オ' to "o",
        'カ' to "ka", 'キ' to "ki", 'ク' to "ku", 'ケ' to "ke", 'コ' to "ko",
        'サ' to "sa", 'シ' to "shi", 'ス' to "su", 'セ' to "se", 'ソ' to "so",
        'タ' to "ta", 'チ' to "chi", 'ツ' to "tsu", 'テ' to "te", 'ト' to "to",
        'ナ' to "na", 'ニ' to "ni", 'ヌ' to "nu", 'ネ' to "ne", 'ノ' to "no",
        'ハ' to "ha", 'ヒ' to "hi", 'フ' to "fu", 'ヘ' to "he", 'ホ' to "ho",
        'マ' to "ma", 'ミ' to "mi", 'ム' to "mu", 'メ' to "me", 'モ' to "mo",
        'ヤ' to "ya", 'ユ' to "yu", 'ヨ' to "yo",
        'ラ' to "ra", 'リ' to "ri", 'ル' to "ru", 'レ' to "re", 'ロ' to "ro",
        'ワ' to "wa", 'ヰ' to "i", 'ヱ' to "e", 'ヲ' to "o", 'ン' to "n",
        'ガ' to "ga", 'ギ' to "gi", 'グ' to "gu", 'ゲ' to "ge", 'ゴ' to "go",
        'ザ' to "za", 'ジ' to "ji", 'ズ' to "zu", 'ゼ' to "ze", 'ゾ' to "zo",
        'ダ' to "da", 'ヂ' to "ji", 'ヅ' to "zu", 'デ' to "de", 'ド' to "do",
        'バ' to "ba", 'ビ' to "bi", 'ブ' to "bu", 'ベ' to "be", 'ボ' to "bo",
        'パ' to "pa", 'ピ' to "pi", 'プ' to "pu", 'ペ' to "pe", 'ポ' to "po",
        'ヴ' to "vu", 'ァ' to "a", 'ィ' to "i", 'ゥ' to "u", 'ェ' to "e", 'ォ' to "o",
        'ャ' to "ya", 'ュ' to "yu", 'ョ' to "yo", 'ヮ' to "wa", 'ヵ' to "ka", 'ヶ' to "ke",
    )
    private val SMALL_Y = mapOf('ャ' to "a", 'ュ' to "u", 'ョ' to "o")
    private val PAIRS = mapOf(
        "ファ" to "fa", "フィ" to "fi", "フェ" to "fe", "フォ" to "fo", "ティ" to "ti", "ディ" to "di", "トゥ" to "tu",
        "ドゥ" to "du", "ウィ" to "wi", "ウェ" to "we", "ウォ" to "wo", "ヴァ" to "va", "ヴィ" to "vi", "ヴェ" to "ve",
        "ヴォ" to "vo", "シェ" to "she", "ジェ" to "je", "チェ" to "che", "ツァ" to "tsa", "ツェ" to "tse", "ツォ" to "tso",
        "テュ" to "tyu", "デュ" to "dyu", "フュ" to "fyu",
    )

    /** Hiragana to katakana (the table is in katakana). */
    fun toKatakana(s: String): String = buildString {
        for (c in s) append(if (c.code in 0x3041..0x3096) (c.code + 0x60).toChar() else c)
    }

    fun isKana(c: Char) = c.code in 0x3041..0x30FF

    /**
     * Each character of [text] spelled out (a digraph's whole spelling on its first kana, nothing on the small one);
     * anything that isn't kana stays as it is.
     */
    fun pieces(text: String): List<String> {
        val k = toKatakana(text)
        val out = MutableList(k.length) { text[it].toString() }
        var i = 0
        while (i < k.length) {
            val c = k[i]
            if (!isKana(text[i]) && c != 'ー') { i++; continue }
            val next = k.getOrNull(i + 1)
            val pair = next?.let { PAIRS["$c$it"] }
            when {
                c == 'ー' -> {
                    // The vowel before it, again.
                    val before = (i - 1 downTo 0).map { out[it] }.firstOrNull { it.isNotEmpty() }
                    out[i] = before?.lastOrNull { it in "aeiou" }?.toString() ?: ""
                }
                c == 'ッ' -> out[i] = "" // filled in below, once the next syllable is known
                pair != null -> { out[i] = pair; out[i + 1] = ""; i++ }
                next != null && next in SMALL_Y && BASIC[c]?.endsWith("i") == true && c != 'イ' -> {
                    val base = BASIC.getValue(c)
                    out[i] = when (base) {
                        "shi" -> "sh" + SMALL_Y.getValue(next)
                        "chi" -> "ch" + SMALL_Y.getValue(next)
                        "ji" -> "j" + SMALL_Y.getValue(next)
                        else -> base.dropLast(1) + "y" + SMALL_Y.getValue(next)
                    }
                    out[i + 1] = ""
                    i++
                }
                else -> BASIC[c]?.let { out[i] = it }
            }
            i++
        }
        // っ doubles the consonant that follows it ("tch" before ch).
        for (j in k.indices) if (k[j] == 'ッ') {
            val after = (j + 1 until k.length).map { out[it] }.firstOrNull { it.isNotEmpty() }
            out[j] = when {
                after == null -> ""
                after.startsWith("ch") -> "t"
                after.first() in "aeiou" -> ""
                else -> after.first().toString()
            }
        }
        return out
    }

    fun romanize(text: String): String = pieces(text).joinToString("")
}
