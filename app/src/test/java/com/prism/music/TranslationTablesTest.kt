package com.prism.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The translations as compressed tables (1.8.0, #268; splitTranslations in app/build.gradle.kts, Strings.kt): each
 * language still reads its own words, the words Android shows itself stayed resources, and a string's text comes out
 * as Android's resource compiler made it (checked once against all 115,103 translated strings of 1.7.9.3's APK).
 */
class TranslationTablesTest {
    @Test fun textComesOutAsAapt2MakesIt() {
        assertEquals("a b", Strings.compile("  a \n\t  b  "))
        assertEquals("Couldn't load \"%1\$s\"", Strings.compile("Couldn\\'t load \\\"%1\$s\\\""))
        assertEquals("a\nb", Strings.compile("a\\nb"))
        // An escaped line break or a quoted space at either end is kept; plain white space there isn't.
        assertEquals("…and %1\$s more\n", Strings.compile("…and %1\$s more\\n "))
        assertEquals("  a  b", Strings.compile(" \"  a  \"b "))
        assertEquals("é @ ? \\ 100%", Strings.compile("\\u00e9 \\@ \\? \\\\ 100%"))
        // Only ASCII white space is folded: French's narrow no-break space stays where it is.
        assertEquals("a : b", Strings.compile("a :   b"))
    }

    @Test fun nothingReadsAStringPastTr() {
        // A string read straight from the resources would now be English in every language (the translations aren't
        // resources any more), so the code reads them all through tr()/trp().
        val bad = Regex("""(getString|getText)\(\s*(com\.prism\.music\.)?R\.string\.""")
        val found = File("src/main/java").walkTopDown().filter { it.extension == "kt" && it.name != "Strings.kt" }
            .flatMap { f -> f.readLines().withIndex().filter { bad.containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}" } }.toList()
        assertTrue("Read with tr() instead: $found", found.isEmpty())
    }
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class TranslationTablesLanguagesTest {
    private val res = File("src/main/res")

    private fun strings(folder: String): Map<String, String> {
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$folder/strings.xml"))
        val l = doc.documentElement.getElementsByTagName("string")
        return (0 until l.length).map { l.item(it) as org.w3c.dom.Element }.associate { it.getAttribute("name") to it.textContent }
    }

    /** "values-pt-rBR" → pt-BR; Android's folders know Indonesian as "in", Hebrew as "iw" and Filipino as "b+fil". */
    private fun locale(folder: String): Locale = Locale.forLanguageTag(
        when (val q = folder.removePrefix("values-")) { "in" -> "id"; "iw" -> "he"; else -> q.removePrefix("b+").replace("-r", "-") },
    )

    private fun id(name: String) = R.string::class.java.getField(name).getInt(null)

    @Test fun everyLanguageReadsItsOwnWords() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val english = strings("values")
        val folders = res.listFiles().orEmpty().filter { it.name.startsWith("values-") && File(it, "strings.xml").isFile }.map { it.name }
        assertEquals(AppLanguage.available.size - 1, folders.size)
        val problems = ArrayList<String>()
        try {
            for (folder in folders) {
                val config = android.content.res.Configuration(ctx.resources.configuration).apply { setLocale(locale(folder)) }
                Strings.resources = ctx.createConfigurationContext(config).resources
                val words = strings(folder)
                // Every 40th string, and the ones a widget or the launcher shows (still resources).
                val names = english.keys.filterIndexed { i, _ -> i % 40 == 0 } + listOf("widget_play", "tile_recognize", "downloads")
                for (n in names) {
                    val want = words[n]?.let(Strings::compile) ?: continue
                    val got = tr(id(n))
                    if (got != want) problems += "$folder/$n: [$got], the translation is [$want]"
                }
                // Android's own read of a widget's label is translated too.
                val label = Strings.resources!!.getString(R.string.widget_play)
                if (label != words["widget_play"]?.let(Strings::compile)) problems += "$folder: the widget's label is [$label]"
                // Formatted in the language, as Android's getString(id, args) does.
                if (!tr(R.string.couldn_t_load, "X").contains("X")) problems += "$folder: couldn_t_load lost its %1\$s"
            }
        } finally {
            Strings.resources = null
        }
        assertTrue(problems.take(30).joinToString("\n"), problems.isEmpty())
    }

    @Test fun englishAndUntranslatedStringsComeFromTheResources() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        try {
            Strings.resources = ctx.resources
            assertEquals(Strings.compile(strings("values").getValue("couldn_t_load")), tr(R.string.couldn_t_load))
            val config = android.content.res.Configuration(ctx.resources.configuration).apply { setLocale(Locale.forLanguageTag("es")) }
            Strings.resources = ctx.createConfigurationContext(config).resources
            // translatable="false": the same in every language, from the English resources.
            assertEquals("yyyy-MM-dd", tr(R.string.yyyy_mm_dd))
        } finally {
            Strings.resources = null
        }
    }
}
