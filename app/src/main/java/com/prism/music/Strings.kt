package com.prism.music

import android.content.res.Resources
import androidx.annotation.StringRes

/**
 * Prism's text in the listener's language (1.7.9). Every word on screen is in res/values/strings.xml (translated in
 * values-xx/, see [AppLanguage]) and read with [tr]: in a screen, a menu, a notification or a setting's label alike. Prism
 * restarts when its language changes, so text read once (a menu's labels) is never left in the old one.
 *
 * Since 1.8.0 (#268) the translations aren't in resources.arsc, which Android needs stored uncompressed: the build
 * (splitTranslations in app/build.gradle.kts) puts each language's strings in a compressed table, assets/i18n/<folder>,
 * and leaves in the resources only the English, the plurals and what Android reads itself (widgets' and tiles' labels).
 * Which table is the listener's is still Android's own choice: each language's resources name theirs (prism_i18n_table).
 */
object Strings {
    /** Set first thing in PrismApp.onCreate (the language's resources). */
    @Volatile var resources: Resources? = null

    internal fun get(id: Int): String = android()?.let { r -> runCatching { text(r, id) }.getOrNull() } ?: english(id)

    // As Android's getString(id, args) does: formatted in the resources' language.
    internal fun get(id: Int, args: Array<out Any?>): String =
        android()?.let { r -> runCatching { String.format(r.configuration.locales[0], text(r, id), *args) }.getOrNull() }
            ?: english(id).format(*args)

    /** One language's table: its strings' XML text in [names]' order, and what's been read of it so far. */
    private class Table(val resources: Resources, val bytes: ByteArray?, val starts: IntArray) {
        val read = java.util.concurrent.ConcurrentHashMap<Int, String>()
    }

    @Volatile private var table: Table? = null

    /** The translated strings' names, in the tables' order (the same for every language). */
    @Volatile private var names: Map<String, Int>? = null

    private fun text(r: Resources, id: Int): String {
        val t = table?.takeIf { it.resources === r } ?: load(r).also { table = it }
        t.read[id]?.let { return it }
        val s = t.bytes?.let { bytes ->
            val i = names?.get(r.getResourceEntryName(id)) ?: return@let null
            val raw = String(bytes, t.starts[i], t.starts[i + 1] - t.starts[i] - 1, Charsets.UTF_8)
            if (raw == "\u0001") null else compile(raw)
        } ?: r.getString(id)
        t.read[id] = s
        return s
    }

    private fun load(r: Resources): Table {
        val folder = runCatching { r.getString(R.string.prism_i18n_table) }.getOrDefault("")
        val bytes = folder.takeIf { it.isNotEmpty() }?.let { f -> runCatching { r.assets.open("i18n/$f").use { it.readBytes() } }.getOrNull() }
            ?: return Table(r, null, IntArray(0))
        if (names == null) names = r.assets.open("i18n/names").use { it.readBytes() }.toString(Charsets.UTF_8).split('\n')
            .withIndex().associate { it.value to it.index }
        // Where each string starts, and one past the last one's end (as if a NUL followed it too).
        val starts = IntArray(names!!.size + 1)
        var n = 1
        for (i in bytes.indices) if (bytes[i] == 0.toByte() && n < starts.size) starts[n++] = i + 1
        starts[starts.size - 1] = bytes.size + 1
        return Table(r, bytes, starts)
    }

    /**
     * A string's XML text as Android's resource compiler (aapt2) makes it: \' \" \n \t \uXXXX and \\ unescaped,
     * "quoted" parts kept as written, other runs of white space as one space, and the white space at either end dropped.
     */
    internal fun compile(raw: String): String {
        val out = StringBuilder(raw.length)
        var quoted = false
        var space = false // a run of white space, written only if something follows it
        fun put(c: Char) {
            if (space && out.isNotEmpty()) out.append(' ')
            space = false
            out.append(c)
        }
        var i = 0
        while (i < raw.length) {
            val c = raw[i++]
            when {
                !quoted && c in " \t\n\u000B\u000C\r" -> space = true
                c == '"' -> quoted = !quoted
                c == '\\' && i < raw.length -> when (val e = raw[i++]) {
                    'n' -> put('\n')
                    't' -> put('\t')
                    'u' -> {
                        val hex = raw.substring(i, minOf(i + 4, raw.length)).takeWhile { it.isLetterOrDigit() }
                        hex.toIntOrNull(16)?.let { put(it.toChar()); i += hex.length } ?: put('u')
                    }
                    else -> put(e)
                }
                c == '\\' -> Unit
                else -> put(c)
            }
        }
        return out.toString()
    }

    internal fun plural(id: Int, n: Int, args: Array<out Any?>): String {
        val with = if (args.isEmpty()) arrayOf<Any?>(n) else args
        return android()?.let { r -> runCatching { r.getQuantityString(id, n, *with) }.getOrNull() }
            ?: englishPlural(id, n).format(*with)
    }

    /** The plain JVM's English plurals ([english]): "one" for 1, "other" for the rest, as English has it. */
    private fun englishPlural(id: Int, n: Int): String {
        val name = runCatching { Class.forName("com.prism.music.R\$plurals").fields.first { it.getInt(null) == id }.name }.getOrNull()
        val forms = name?.let { englishPlurals[it] } ?: return "#$id"
        return (if (n == 1) forms["one"] else null) ?: forms["other"] ?: forms.values.first()
    }

    private val englishPlurals: Map<String, Map<String, String>> by lazy {
        val xml = stringsFile()?.readText() ?: return@lazy emptyMap<String, Map<String, String>>()
        Regex("<plurals name=\"([^\"]+)\">(.*?)</plurals>", RegexOption.DOT_MATCHES_ALL).findAll(xml).associate { p ->
            p.groupValues[1] to Regex("<item quantity=\"(\\w+)\">(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(p.groupValues[2])
                .associate { it.groupValues[1] to unescape(it.groupValues[2]) }
        }
    }

    /** Prism's resources, or (tests on a plain Application) the process's app's, as Android itself keeps it. */
    private fun android(): Resources? = resources ?: runCatching {
        (Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as android.app.Application).resources
    }.getOrNull()?.also { resources = it }

    /**
     * Only where there's no Android at all (unit tests on the plain JVM, which build settings and parse YouTube without
     * an app): the English text straight from strings.xml, by the resource's name. Never on a phone.
     */
    private fun english(id: Int): String = plainJvm.getOrPut(id) {
        val name = runCatching { Class.forName("com.prism.music.R\$string").fields.first { it.getInt(null) == id }.name }.getOrNull()
        name?.let { englishXml[it] } ?: "#$id"
    }

    private val plainJvm = java.util.concurrent.ConcurrentHashMap<Int, String>()

    private fun stringsFile() = listOf("src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml").map { java.io.File(it) }.firstOrNull { it.isFile }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        .replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t").replace("\\@", "@").replace("\\?", "?")
        .replace("\\\\", "\\")

    private val englishXml: Map<String, String> by lazy {
        val xml = stringsFile()?.readText() ?: return@lazy emptyMap<String, String>()
        Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(xml).associate { m -> m.groupValues[1] to unescape(m.groupValues[2]) }
    }
}

/** [id]'s text in the listener's language. */
fun tr(@StringRes id: Int): String = Strings.get(id)

/** [id]'s text with [args] in its places (%1$s, %2$s…). */
fun tr(@StringRes id: Int, vararg args: Any?): String = Strings.get(id, args)

/**
 * [id]'s form for [n] in the listener's language ("1 song", "5 songs"; Polish and Arabic have more forms than
 * English), with [args] in its places, or [n] alone when there are none.
 */
fun trp(@androidx.annotation.PluralsRes id: Int, n: Int, vararg args: Any?): String = Strings.plural(id, n, args)

/**
 * A date or time format in the listener's language from a [skeleton] of what to show ("MMMd": month and day), in that
 * language's own order and words ("Nov 3", "3 nov.", "11月3日"); "j" is the hour as the language writes it (12 or 24).
 */
fun localDates(skeleton: String): java.time.format.DateTimeFormatter {
    val locale = java.util.Locale.getDefault()
    val pattern = runCatching { android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton) }.getOrNull()
        ?: skeleton.replace("j", "h")
    return java.time.format.DateTimeFormatter.ofPattern(pattern, locale)
}
