package com.prism.music.data.lyrics.roman

import java.text.Normalizer

/** The writing systems lyrics come in that Prism can spell out in Latin letters. */
enum class Script {
    LATIN, HANGUL, KANA, HAN, CYRILLIC, GREEK, ARABIC, HEBREW, DEVANAGARI, BENGALI, GURMUKHI, GUJARATI, TAMIL,
    TELUGU, KANNADA, MALAYALAM, THAI, GEORGIAN, ARMENIAN, OTHER;

    companion object {
        fun of(c: Char): Script? {
            val cp = c.code
            return when {
                c.isWhitespace() || !c.isLetter() && !Character.isIdeographic(cp) && cp !in 0x3040..0x30FF -> null
                cp < 0x0250 -> LATIN
                cp in 0x1E00..0x1EFF -> LATIN
                cp in 0xAC00..0xD7A3 || cp in 0x1100..0x11FF || cp in 0x3130..0x318F -> HANGUL
                cp in 0x3040..0x30FF || cp in 0x31F0..0x31FF || cp in 0xFF66..0xFF9F -> KANA
                // 々 (repeat the kanji before: 日々), 〆 and 〻 aren't "ideographs" to Unicode, but they're kanji in use.
                cp == 0x3005 || cp == 0x3006 || cp == 0x303B -> HAN
                Character.isIdeographic(cp) || cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xF900..0xFAFF -> HAN
                cp in 0x0400..0x052F -> CYRILLIC
                cp in 0x0370..0x03FF || cp in 0x1F00..0x1FFF -> GREEK
                cp in 0x0600..0x06FF || cp in 0x0750..0x077F || cp in 0xFB50..0xFDFF || cp in 0xFE70..0xFEFF -> ARABIC
                cp in 0x0590..0x05FF -> HEBREW
                cp in 0x0900..0x097F -> DEVANAGARI
                cp in 0x0980..0x09FF -> BENGALI
                cp in 0x0A00..0x0A7F -> GURMUKHI
                cp in 0x0A80..0x0AFF -> GUJARATI
                cp in 0x0B80..0x0BFF -> TAMIL
                cp in 0x0C00..0x0C7F -> TELUGU
                cp in 0x0C80..0x0CFF -> KANNADA
                cp in 0x0D00..0x0D7F -> MALAYALAM
                cp in 0x0E00..0x0E7F -> THAI
                cp in 0x10A0..0x10FF -> GEORGIAN
                cp in 0x0530..0x058F -> ARMENIAN
                else -> OTHER
            }
        }

        /** The non-Latin scripts in [text] (empty for a line already in Latin letters). */
        fun foreignIn(text: String): Set<Script> = text.mapNotNullTo(HashSet()) { of(it) }.apply { remove(LATIN) }

        /**
         * The scripts a reader of the phone's language reads without help, so their lyrics aren't spelled out by
         * default (a Korean phone isn't shown Korean in Latin letters).
         */
        fun native(language: String): Set<Script> = when (language) {
            "ko" -> setOf(HANGUL)
            "ja" -> setOf(KANA, HAN)
            "zh" -> setOf(HAN)
            "ru", "uk", "be", "bg", "sr", "mk", "kk", "ky", "mn", "tg" -> setOf(CYRILLIC)
            "el" -> setOf(GREEK)
            "ar", "fa", "ur", "ps" -> setOf(ARABIC)
            "he", "iw", "yi" -> setOf(HEBREW)
            "hi", "mr", "ne", "sa" -> setOf(DEVANAGARI)
            "bn", "as" -> setOf(BENGALI)
            "pa" -> setOf(GURMUKHI)
            "gu" -> setOf(GUJARATI)
            "ta" -> setOf(TAMIL)
            "te" -> setOf(TELUGU)
            "kn" -> setOf(KANNADA)
            "ml" -> setOf(MALAYALAM)
            "th" -> setOf(THAI)
            "ka" -> setOf(GEORGIAN)
            "hy" -> setOf(ARMENIAN)
            else -> emptySet()
        }
    }
}

/** Accents and marks taken off (ISO-style transliterations are full of them): "mērē" → "mere". */
fun plainLatin(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .replace('ʼ', '\'').replace('ʻ', '\'').replace('ʹ', '\'').replace('ʺ', '"')

/** Cyrillic in Latin letters, the way songs' fans write it ("Я тебя люблю" → "Ya tebya lyublyu"). */
object Cyrillic {
    private val map = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo", 'ж' to "zh", 'з' to "z",
        'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r",
        'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh",
        'щ' to "shch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
        // Ukrainian, Belarusian, Serbian, Macedonian, Kazakh
        'і' to "i", 'ї' to "yi", 'є' to "ye", 'ґ' to "g", 'ў' to "w", 'ђ' to "dj", 'ј' to "j", 'љ' to "lj", 'њ' to "nj",
        'ћ' to "c", 'џ' to "dz", 'ѓ' to "gj", 'ќ' to "kj", 'ѕ' to "dz", 'ә' to "a", 'ғ' to "gh", 'қ' to "q",
        'ң' to "ng", 'ө' to "o", 'ұ' to "u", 'ү' to "u", 'һ' to "h",
    )
    private val vowels = "аеёиоуыэюяіїє".toSet()

    /** Each character of [text] spelled out; anything that isn't Cyrillic stays as it is. */
    fun pieces(text: String): List<String> = text.mapIndexed { i, ch ->
        val lower = ch.lowercaseChar()
        val prev = text.getOrNull(i - 1)?.lowercaseChar()
        var r = map[lower] ?: return@mapIndexed ch.toString()
        // "е" is "ye" at the start of a word and after a vowel or a hard/soft sign, as it sounds.
        if (lower == 'е' && (prev == null || !prev.isLetter() || prev in vowels || prev == 'ъ' || prev == 'ь')) r = "ye"
        if (ch.isUpperCase() && r.isNotEmpty()) r.replaceFirstChar { it.uppercaseChar() } else r
    }

    fun romanize(text: String): String = pieces(text).joinToString("")
}

/** Greek in Latin letters (after ELOT 743, simplified): "Σ' αγαπώ" → "S' agapo". */
object Greek {
    private val pairs = listOf("ου" to "ou", "αυ" to "av", "ευ" to "ev", "ηυ" to "iv", "αι" to "ai", "ει" to "ei", "οι" to "oi",
        "γγ" to "ng", "γκ" to "gk", "γξ" to "nx", "γχ" to "nch", "μπ" to "mp", "ντ" to "nt", "τσ" to "ts", "τζ" to "tz")
    private val map = mapOf(
        'α' to "a", 'β' to "v", 'γ' to "g", 'δ' to "d", 'ε' to "e", 'ζ' to "z", 'η' to "i", 'θ' to "th", 'ι' to "i",
        'κ' to "k", 'λ' to "l", 'μ' to "m", 'ν' to "n", 'ξ' to "x", 'ο' to "o", 'π' to "p", 'ρ' to "r", 'σ' to "s",
        'ς' to "s", 'τ' to "t", 'υ' to "y", 'φ' to "f", 'χ' to "ch", 'ψ' to "ps", 'ω' to "o",
    )

    /** Each character of [text] spelled out (a letter pair's spelling on its first letter); the rest as it is. */
    fun pieces(text: String): List<String> {
        val out = MutableList(text.length) { text[it].toString() }
        // Accents come off letter by letter, so the pieces stay lined up with [text].
        val plain = text.map { Normalizer.normalize(it.toString(), Normalizer.Form.NFD).firstOrNull() ?: it }.joinToString("")
        var i = 0
        while (i < plain.length) {
            val ch = plain[i]
            val two = plain.substring(i, minOf(i + 2, plain.length)).lowercase()
            val pair = pairs.firstOrNull { it.first == two }
            val r = pair?.second ?: map[ch.lowercaseChar()]
            if (r == null) { i++; continue }
            out[i] = if (ch.isUpperCase()) r.replaceFirstChar { it.uppercaseChar() } else r
            if (pair != null) { out[i + 1] = ""; i += 2 } else i++
        }
        // Greek combining accents (written as separate marks) take no room.
        for (k in out.indices) if (Character.getType(text[k]) == Character.NON_SPACING_MARK.toInt()) out[k] = ""
        return out
    }

    fun romanize(text: String): String = pieces(text).joinToString("")
}
