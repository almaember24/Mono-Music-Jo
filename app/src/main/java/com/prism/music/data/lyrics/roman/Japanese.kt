package com.prism.music.data.lyrics.roman

import com.atilika.kuromoji.ipadic.Token
import com.atilika.kuromoji.ipadic.Tokenizer
import com.atilika.kuromoji.dict.CharacterDefinitions
import com.atilika.kuromoji.dict.ConnectionCosts
import com.atilika.kuromoji.dict.InsertedDictionary
import com.atilika.kuromoji.dict.TokenInfoDictionary
import com.atilika.kuromoji.dict.UnknownDictionary
import com.atilika.kuromoji.trie.DoubleArrayTrie
import com.atilika.kuromoji.util.ResourceResolver
import java.io.File
import java.io.InputStream

/**
 * Japanese with kanji, in Latin letters: Kuromoji splits each line into words and gives every word its reading,
 * which [Kana] then spells out in Hepburn, with the particles said as they're said (は "wa", へ "e", を "o") and a space
 * between words ("君の名は" → "kimi no na wa").
 */
class Japanese private constructor(private val tokenizer: Tokenizer) {
    /**
     * Each character of [text] spelled out: a word's whole reading on its first character (with a space before it when
     * it starts a new word), nothing on the rest.
     */
    fun pieces(text: String): List<String> {
        val out = MutableList(text.length) { text[it].toString() }
        val tokens = tokenizer.tokenize(text)
        var first = true
        // A word whose reading ends in っ (だっ, 分かっ) doubles the consonant of the next one: "datta", "wakatta".
        var doubling = false
        for (t in tokens) {
            val at = t.position
            val len = t.surface.length
            if (at < 0 || at + len > text.length) continue
            if (t.surface.isBlank()) { first = true; doubling = false; continue }
            var roman = romaji(t)
            // Not Japanese (Latin words, digits, punctuation) stays as written.
            if (roman == null) { first = t.surface.last().isWhitespace(); doubling = false; continue }
            if (doubling && roman.isNotEmpty()) roman = when {
                roman.startsWith("ch") -> "t$roman"
                roman.first() in "aeiou" -> roman
                else -> roman.first() + roman
            }
            doubling = reading(t).endsWith('ッ')
            val joins = !first && attaches(t)
            out[at] = (if (!first && !joins && !text[at - 1].isWhitespace()) " " else "") + roman
            for (k in 1 until len) out[at + k] = ""
            first = false
        }
        return out
    }

    fun romanize(text: String): String = pieces(text).joinToString("")

    /** The word's reading spelled out, or null when it isn't Japanese. */
    private fun romaji(t: Token): String? {
        if (t.surface.none { Kana.isKana(it) || Character.isIdeographic(it.code) || it == 'ー' || it == '々' }) return null
        // Particles are written as they're said.
        if (t.partOfSpeechLevel1 == "助詞") when (t.surface) {
            "は" -> return "wa"
            "へ" -> return "e"
            "を" -> return "o"
        }
        return Kana.romanize(reading(t)).lowercase()
    }

    private fun reading(t: Token): String = Kana.toKatakana(t.reading.takeIf { it.isNotBlank() && it != "*" } ?: t.surface)

    /** Words that are said joined to the one before (verb endings, auxiliaries, suffixes): "mite", "aishiteru". */
    private fun attaches(t: Token): Boolean =
        t.partOfSpeechLevel1 == "助動詞" ||
            (t.partOfSpeechLevel1 == "助詞" && t.partOfSpeechLevel2 == "接続助詞") ||
            t.partOfSpeechLevel2 == "接尾"

    companion object {
        /**
         * Words IPADIC reads the way they're written in a list rather than as they're sung (二人 as "ni nin"): read
         * as songs say them. Kuromoji's user dictionary format: surface, segmentation, reading, part of speech.
         */
        private val LYRIC_WORDS = listOf(
            "二人" to "フタリ", "一人" to "ヒトリ", "一人きり" to "ヒトリキリ", "二人きり" to "フタリキリ", "今日" to "キョウ",
            "明日" to "アシタ", "昨日" to "キノウ", "大人" to "オトナ", "一日" to "イチニチ", "今夜" to "コンヤ",
            "永遠" to "エイエン", "運命" to "ウンメイ", "世界" to "セカイ", "未来" to "ミライ", "心" to "ココロ",
            "涙" to "ナミダ", "笑顔" to "エガオ", "一緒" to "イッショ", "宇宙" to "ウチュウ", "何処" to "ドコ", "何故" to "ナゼ",
        )

        private fun userDictionary() = LYRIC_WORDS.joinToString("\n") { (w, r) -> "$w,$w,$r,カスタム名詞" }.byteInputStream()

        /** A reader over IPADIC from [dir] (unpacked by [com.prism.music.data.ModelStore]). Takes a second or two. */
        fun load(dir: File): Japanese = Japanese((FromFiles(dir).userDictionary(userDictionary()) as Tokenizer.Builder).build())

        /** A reader over the dictionary inside Kuromoji's own jar (tests on the PC have it; the APK leaves it out). */
        fun bundled(): Japanese = Japanese((Tokenizer.Builder().userDictionary(userDictionary()) as Tokenizer.Builder).build())
    }

    /** Kuromoji's IPADIC tokenizer, reading its dictionary files from a folder instead of from its own jar. */
    private class FromFiles(private val dir: File) : Tokenizer.Builder() {
        override fun loadDictionaries() {
            // Kuromoji's own defaults for IPADIC (they only matter in its search modes).
            penalties = arrayListOf(2, 3000, 7, 1700)
            resolver = ResourceResolver { name -> File(dir, name).inputStream().buffered() as InputStream }
            doubleArrayTrie = DoubleArrayTrie.newInstance(resolver)
            connectionCosts = ConnectionCosts.newInstance(resolver)
            tokenInfoDictionary = TokenInfoDictionary.newInstance(resolver)
            characterDefinitions = CharacterDefinitions.newInstance(resolver)
            unknownDictionary = UnknownDictionary.newInstance(resolver, characterDefinitions, totalFeatures)
            insertedDictionary = InsertedDictionary(totalFeatures)
        }
    }
}
