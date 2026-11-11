package com.prism.music

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every translation (1.7.9, plan section T) against the English: nothing missing, nothing extra, the same placeholders
 * in every string and every plural form (a missing %1$s shows the wrong thing; an extra one crashes), and not mostly
 * left in English. A correction sent as a pull request runs through this too.
 */
class TranslationsTest {
    private val res = File("src/main/res")

    private data class Entries(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    private fun read(f: File): Entries {
        val all = f.readText()
        val strings = Regex("<string name=\"([^\"]+)\"([^>]*)>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(all)
            .filterNot { it.groupValues[2].contains("translatable=\"false\"") }
            .associate { it.groupValues[1] to it.groupValues[3] }
        val plurals = Regex("<plurals name=\"([^\"]+)\"[^>]*>(.*?)</plurals>", RegexOption.DOT_MATCHES_ALL).findAll(all).associate { p ->
            p.groupValues[1] to Regex("<item quantity=\"(\\w+)\">(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(p.groupValues[2])
                .associate { it.groupValues[1] to it.groupValues[2] }
        }
        return Entries(strings, plurals)
    }

    /** The placeholders in [s], as a sorted list: "%1$s", "%2$d", "%s", "%.1f", "%%". */
    private fun placeholders(s: String): List<String> =
        Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[sdfxXc%]").findAll(s).map { it.value }.sorted().toList()

    /** Placeholders without a position ("%s") count by kind, so a translation may reorder positioned ones only. */
    private fun samePlaceholders(english: String, other: String) = placeholders(english) == placeholders(other)

    private val languages get() = res.listFiles().orEmpty()
        .filter { it.isDirectory && it.name.startsWith("values-") && File(it, "strings.xml").exists() }
        .filter { d -> File(d, "strings.xml").readText().contains("<string ") }

    @Test fun everyTranslationMatchesTheEnglish() {
        val english = read(File(res, "values/strings.xml"))
        val problems = ArrayList<String>()
        for (dir in languages) {
            val lang = dir.name.removePrefix("values-")
            val t = read(File(dir, "strings.xml"))
            (english.strings.keys - t.strings.keys).takeIf { it.isNotEmpty() }?.let { problems += "$lang: missing ${it.size}: ${it.take(10)}" }
            (english.plurals.keys - t.plurals.keys).takeIf { it.isNotEmpty() }?.let { problems += "$lang: missing plurals ${it.take(10)}" }
            (t.strings.keys - english.strings.keys).takeIf { it.isNotEmpty() }?.let { problems += "$lang: not in the English ${it.take(10)}" }
            (t.plurals.keys - english.plurals.keys).takeIf { it.isNotEmpty() }?.let { problems += "$lang: plurals not in the English ${it.take(10)}" }
            for ((k, v) in t.strings) {
                val en = english.strings[k] ?: continue
                if (!samePlaceholders(en, v)) problems += "$lang/$k: placeholders ${placeholders(v)}, the English has ${placeholders(en)}"
                if (Regex("(?<!\\\\)'").containsMatchIn(v.replace(Regex("\"[^\"]*\""), ""))) problems += "$lang/$k: an apostrophe needs \\'"
            }
            for ((k, forms) in t.plurals) {
                val en = english.plurals[k] ?: continue
                if ("other" !in forms) problems += "$lang/$k: no \"other\" form"
                // Each form carries the English "other"'s placeholders, except that a "one" (or "zero"/"two") may
                // spell the number out instead of %s, as the English "one" may.
                val want = placeholders(en["other"].orEmpty())
                for ((q, v) in forms) {
                    val got = placeholders(v)
                    val ok = got == want || (q != "other" && got == placeholders(en[q] ?: en["other"].orEmpty())) ||
                        (q != "other" && want.containsAll(got))
                    if (!ok) problems += "$lang/$k[$q]: placeholders $got, the English has $want"
                }
            }
            // Mostly still English: a language left half done. Names (Prism, Last.fm, EQ) and short words that read the
            // same in many languages are allowed for; a third the same is not.
            val same = t.strings.count { (k, v) -> v == english.strings[k] && v.count { it == ' ' } >= 2 }
            val long = english.strings.values.count { it.count { c -> c == ' ' } >= 2 }
            if (long > 0 && same * 3 > long) problems += "$lang: $same of $long sentences are still the English"
        }
        assertTrue(problems.take(60).joinToString("\n"), problems.isEmpty())
    }

    /** Settings → Language offers exactly the languages there are translations for. */
    @Test fun thePickerListsEveryTranslation() {
        // A tag's folder: "pt-BR" → values-pt-rBR; Android's resources know Indonesian as "in" and Hebrew as "iw", and a
        // three-letter language (Filipino) only in the BCP 47 form, values-b+fil.
        fun folder(tag: String) = "values-" + tag.split('-').let { p ->
            when {
                p[0].length == 3 -> "b+" + p.joinToString("+")
                else -> (when (p[0]) { "id" -> "in"; "he" -> "iw"; else -> p[0] }) + p.drop(1).joinToString("") { "-r$it" }
            }
        }
        val listed = AppLanguage.available.filter { it != "en" }.map(::folder).toSet()
        val there = languages.map { it.name }.toSet()
        assertTrue("in the picker, no translation: ${listed - there}; translated, not in the picker: ${there - listed}", listed == there)
    }

    @Test fun theEnglishItselfIsWellFormed() {
        val english = read(File(res, "values/strings.xml"))
        val problems = ArrayList<String>()
        for ((k, v) in english.strings) {
            // Two positionless placeholders can't be reordered by a translation: number them.
            if (Regex("%[sd]").findAll(v).count() > 1) problems += "$k: number its placeholders (%1\$s, %2\$s)"
        }
        for ((k, forms) in english.plurals) {
            if ("other" !in forms) problems += "$k: no \"other\" form"
            if (Regex("%[sd]").findAll(forms["other"].orEmpty()).count() > 1) problems += "$k: number its placeholders"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
