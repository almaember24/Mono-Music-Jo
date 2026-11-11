package com.prism.music

import org.junit.Test
import java.io.File

/**
 * Not a check: the translation move's tool (1.7.9, plan section T). With PRISM_I18N=<paths> (comma-separated, relative
 * to app/src/main/java/com/prism/music, a folder or a .kt file), every piece of text in them that a person reads is moved
 * into res/values/strings.xml and the code reads it back with `tr(R.string.…)`. Text that's the same everywhere gets
 * one entry. What's left in the code on purpose: log and diagnostics lines, keys, addresses, patterns, a `when`'s
 * cases and anything compared against (YouTube's own labels), annotation and `const` values, and enum entries' names.
 * Writes a report of every literal it left in place to app/build/i18n/<run>.txt, to look through by hand.
 *
 * The compiler checks the result; SettingsIndexTest and the app's tests check what it means.
 */
class I18nConvert {
    private val src = File("src/main/java/com/prism/music")
    private val stringsXml = File("src/main/res/values/strings.xml")

    @Test fun convert() {
        val targets = System.getenv("PRISM_I18N")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return
        val files = targets.flatMap { t -> File(src, t).let { f -> if (f.isDirectory) f.walkTopDown().filter { it.extension == "kt" }.toList() else listOf(f) } }
            .filter { it.name !in SKIP_FILES }
        val res = Resources.load(stringsXml)
        val report = StringBuilder()
        var moved = 0
        // PRISM_I18N_DRY=1: only says what would move, per file, into build/i18n/dry.txt.
        if (System.getenv("PRISM_I18N_DRY") == "1") {
            File("build/i18n").mkdirs()
            File("build/i18n/dry.txt").writeText(files.mapNotNull { f -> pending(f.readText()).takeIf { it.isNotEmpty() }?.let { "== ${f.relativeTo(src)} (${it.size})\n" + it.joinToString("\n") { l -> "  $l" } } }.joinToString("\n"))
            return
        }
        for (f in files) {
            val text = f.readText()
            val (out, n, kept) = convertFile(text, res, onlyLines[f.relativeTo(src).invariantSeparatorsPath])
            if (n > 0) {
                f.writeText(withImports(out, f))
                moved += n
            }
            if (kept.isNotEmpty()) report.append("== ${f.relativeTo(src)}\n").append(kept.joinToString("\n") { "  $it" }).append("\n")
        }
        res.save(stringsXml)
        val dir = File("build/i18n").apply { mkdirs() }
        File(dir, "${System.currentTimeMillis()}.txt").writeText(report.toString())
        println("[i18n] moved $moved texts from ${files.size} files; ${res.added} new entries; left in place: build/i18n/")
    }

    /** What [convert] would move from [text]: text still written in the code (I18nGuardTest). */
    internal fun pending(text: String): List<String> {
        val enums = enumBodies(text)
        return literals(text).filter { lit ->
            looksLikeText(lit.parts.joinToString("") { if (it is String) it else "{}" }) && keepReason(text, lit, enums) == null
        }.map { "${lineOf(text, it.start)}: ${text.substring(it.start, it.end)}" }
    }

    // ------------------------------------------------------------------------------------------- strings.xml

    /** The strings.xml entries: text → key, kept in order, with new ones appended. */
    private class Resources(private val head: String, private val body: StringBuilder, val byText: MutableMap<String, String>, val keys: MutableSet<String>) {
        var added = 0

        fun keyFor(value: String, hasArgs: Boolean): String {
            byText[value]?.let { return it }
            var base = slug(value)
            var key = base
            var i = 2
            while (key in keys) key = "${base}_${i++}"
            keys += key
            byText[value] = key
            val formatted = if (!hasArgs && value.contains('%')) " formatted=\"false\"" else ""
            body.append("    <string name=\"$key\"$formatted>${xmlEscape(value)}</string>\n")
            added++
            return key
        }

        fun save(f: File) = f.writeText(head + body + "</resources>\n")

        companion object {
            fun load(f: File): Resources {
                val all = f.readText()
                val end = all.lastIndexOf("</resources>")
                val head = all.substring(0, end)
                val byText = linkedMapOf<String, String>()
                val keys = linkedSetOf<String>()
                Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(all).forEach { m ->
                    keys += m.groupValues[1]
                    byText.putIfAbsent(xmlUnescape(m.groupValues[2]), m.groupValues[1])
                }
                Regex("<plurals name=\"([^\"]+)\"").findAll(all).forEach { keys += it.groupValues[1] }
                return Resources("", StringBuilder(head), byText, keys)
            }
        }
    }

    // ------------------------------------------------------------------------------------------- the code

    private data class Lit(val start: Int, val end: Int, val parts: List<Any>) // parts: String text or Expr
    private data class Expr(val code: String)

    private data class Result(val text: String, val moved: Int, val kept: List<String>)

    /**
     * PRISM_I18N_LINES="path:12,30;path2:7": in those files only the text on those lines moves, whatever the rules say
     * (picked by hand from the dry run: a mixed file's on-screen errors, not its protocol words or diagnostics).
     */
    private val onlyLines: Map<String, Set<Int>> = System.getenv("PRISM_I18N_LINES").orEmpty().split(';').filter { ':' in it }
        .associate { e -> e.substringBefore(':').trim().replace('\\', '/') to e.substringAfter(':').split(',').mapNotNull { it.trim().toIntOrNull() }.toSet() }

    private fun convertFile(text: String, res: Resources, lines: Set<Int>? = null): Result {
        val lits = literals(text)
        val enums = enumBodies(text)
        val sb = StringBuilder()
        var at = 0
        var moved = 0
        val kept = ArrayList<String>()
        for (lit in lits) {
            val raw = text.substring(lit.start, lit.end)
            val plain = lit.parts.joinToString("") { if (it is String) it else "{}" }
            if (!looksLikeText(plain)) continue
            if (lines != null && lineOf(text, lit.start) !in lines) continue
            val why = if (lines != null) null else keepReason(text, lit, enums)
            if (why != null) { kept += "${lineOf(text, lit.start)}: $raw   [$why]"; continue }
            val args = lit.parts.filterIsInstance<Expr>().map { it.code }
            var n = 0
            val value = lit.parts.joinToString("") { p ->
                if (p is String) (if (args.isNotEmpty()) p.replace("%", "%%") else p) else "%${++n}\$s"
            }
            val key = res.keyFor(value, args.isNotEmpty())
            sb.append(text, at, lit.start)
            sb.append(if (args.isEmpty()) "tr(R.string.$key)" else "tr(R.string.$key, ${args.joinToString(", ")})")
            at = lit.end
            moved++
        }
        sb.append(text, at, text.length)
        return Result(sb.toString(), moved, kept)
    }

    /** Words a person reads: two letters in a row, and a space or a capital word ("Cancel"), not a key or an address. */
    private fun looksLikeText(s: String): Boolean {
        val t = s.replace("{}", "").trim()
        if (!Regex("\\p{L}{2,}").containsMatchIn(t)) return false
        if (t.contains("://") || t.startsWith("www.") || t.startsWith("prism:") || t.startsWith("http")) return false
        if (Regex("^[a-z0-9_.:/#=?&{}%-]+$").matches(s.trim())) return false // keys, routes ("blend/{who}"), ids, SQL patterns
        if (Regex("^(PRAGMA|SELECT|INSERT|UPDATE|DELETE|CREATE|DROP|ALTER) ").containsMatchIn(t)) return false // SQL
        if (t.contains('|') && !t.contains(' ')) return false // stored values ("PHOTO|asset:…")
        if (Regex("^\\{\\}\\p{L}").containsMatchIn(s)) return false // names built from a value ("${name}Filled")
        if (Regex("^[A-Z0-9_]+$").matches(t)) return false // CONSTANT_NAMES and codes ("PRISM", "LIGHT")
        if (Regex("^[sbif]:").containsMatchIn(t)) return false // a Look's tagged values ("s:DARK", "b:1")
        if (Regex("^[a-z]+[A-Z]\\w*$").matches(t)) return false // camelCase identifiers
        if (Regex("^\\w+\\.\\w+").matches(t) && !t.contains(' ')) return false // dotted names
        return t.contains(' ') || Regex("^\\p{Lu}\\p{Ll}").containsMatchIn(t) || t.contains('…') || t.length >= 2 && t.first().isLetter()
    }

    private val KEEP_BEFORE = listOf(
        "Log." to "log", "log(" to "log", "AudioDiagnostics" to "log", "note(" to "log", "PrismLog" to "log", "println(" to "log",
        "Regex(" to "pattern", "toRegex" to "pattern", "PreferencesKey(" to "key", "getSharedPreferences(" to "key",
        // (Keys and routes are lowercase codes, which never look like text; a route's words, like a settings group to
        // open, are the group's own title, so they move with it.)
        "testTag(" to "key", "SemanticsPropertyKey" to "key", "startsWith(" to "compare", "endsWith(" to "compare", "contains(" to "compare", "equals(" to "compare",
        "split(" to "compare", "replace(" to "compare", "removePrefix(" to "compare",
        "removeSuffix(" to "compare", "substringBefore(" to "compare", "substringAfter(" to "compare", "indexOf(" to "compare",
        "Uri.parse(" to "address", "mimeType" to "key", "setType(" to "key", "MIME" to "key",
        "Font(" to "key", "assets" to "key", "File(" to "path",
        "Thread(" to "thread name", "SimpleDateFormat(" to "pattern", "ofPattern(" to "pattern", "DateTimeFormatter" to "pattern",
        "localDates(" to "pattern",
        // Stored as the listener's own: Home's section titles (renamable), the equalizer's preset name.
        "HomeSectionConfig(" to "stored title", "copy(key =" to "stored title", "val preset: String" to "stored name",
        "header(" to "protocol", "getInstance(" to "algorithm", "\"single\" ->" to "YouTube kind",
        // Icons are looked up by their drawing's name (not their descriptions: Icon(PrismIcons.Share, "Share") is text).
        "drawn(" to "icon name", "icon(" to "icon name", "PrismIcons.preview(" to "icon name",
    )

    /**
     * PRISM_I18N_SINKS=1: for code that mixes what the listener sees with diagnostics and protocols (the player service),
     * only text handed straight to something on screen moves: a toast, an error the player shows, the car's folders and
     * buttons, the notification, a queue's "Playing from" title.
     */
    private val sinksOnly = System.getenv("PRISM_I18N_SINKS") == "1"
    private val SINKS = listOf(
        "Toast.makeText(", "reportError(", "tryEmit(", "Denied(", "folder(", "songList(", "startItem(", "button(", "setDisplayName(",
        "QueueSource(", "tile.label", "tile.subtitle", "tile.contentDescription", "setContentTitle(", "setContentText(", "setSubText(",
        "friendlyMessage(", "_problem.value", "follow(", "Notification",
    )

    /** Why a literal stays in the code, or null to move it. */
    private fun keepReason(text: String, lit: Lit, enums: List<IntRange>): String? {
        val lineStart = text.lastIndexOf('\n', lit.start - 1) + 1
        val lineEnd = text.indexOf('\n', lit.end).let { if (it < 0) text.length else it }
        val before = text.substring(lineStart, lit.start)
        val after = text.substring(lit.end, lineEnd)
        if (sinksOnly && SINKS.none { before.contains(it) }) return "not on screen"
        val trimmed = before.trimStart()
        if (trimmed.startsWith("@") || trimmed.contains("const val")) return "constant"
        // A list of icon names handed to a lookup later on the line: listOf("Home", "Search").map { PrismIcons.preview(it) }.
        if (after.contains("PrismIcons.preview(") || after.contains("Icons.preview(")) return "icon name"
        // An enum entry's own words are its label (read once; Prism restarts when its language changes), but not a
        // code like "PRISM" (looksLikeText leaves single capitals words alone).
        // A when's case: "A", "B" -> …
        if (Regex("^\\s*(,\\s*\"[^\"]*\"\\s*)*->").containsMatchIn(after) && Regex("^(\\s*\"[^\"]*\"\\s*,)*\\s*$").matches(before)) return "when case"
        // Compared itself: x == "…", "…" != x, "…" in set.
        if (Regex("^\\s*(==|!=|in\\b)").containsMatchIn(after) || Regex("(==|!=)\\s*$").containsMatchIn(before)) return "compare"
        // The call it's in, as far as this line goes.
        for ((marker, why) in KEEP_BEFORE) {
            // As a whole word ("log(" isn't in "Dialog("), and only while that call is still open.
            val i = Regex("(?<![\\w])" + Regex.escape(marker)).findAll(before).lastOrNull()?.range?.first ?: continue
            val between = before.substring(i + marker.length)
            if (marker.endsWith("(") && between.count { it == ')' } > between.count { it == '(' }) continue
            return why
        }
        if (Regex("^\\s*\\.(equals|startsWith|endsWith|contains)").containsMatchIn(after)) return "compare"
        return null
    }

    private fun lineOf(text: String, at: Int) = text.substring(0, at).count { it == '\n' } + 1

    /** Each enum class's entries (from its body's opening brace to the first ';' or its end). */
    private fun enumBodies(text: String): List<IntRange> = Regex("enum class \\w+").findAll(text).mapNotNull { m ->
        // Its body's brace: after the constructor's parentheses.
        var i = m.range.last + 1
        var depth = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '(') depth++ else if (ch == ')') depth-- else if (ch == '{' && depth == 0) break
            i++
        }
        if (i >= text.length) return@mapNotNull null
        var j = i + 1
        var d = 1
        while (j < text.length && d > 0) {
            when (text[j]) { '{' -> d++; '}' -> d--; ';' -> if (d == 1) break }
            j++
        }
        i..j
    }.toList()

    /** Every ordinary string literal ("…", not raw), with its text and its templates, outside comments. */
    private fun literals(text: String): List<Lit> {
        val out = ArrayList<Lit>()
        var i = 0
        fun skipString(from: Int): Pair<Int, List<Any>> { // from = after the opening quote; returns end (after closing quote)
            val parts = ArrayList<Any>()
            val cur = StringBuilder()
            var k = from
            while (k < text.length) {
                val c = text[k]
                when {
                    c == '\\' -> {
                        val n = text[k + 1]
                        when (n) {
                            'n' -> cur.append('\n'); 't' -> cur.append('\t'); '"' -> cur.append('"'); '\'' -> cur.append('\'')
                            '\\' -> cur.append('\\'); '$' -> cur.append('$')
                            'u' -> { cur.append(text.substring(k + 2, k + 6).toInt(16).toChar()); k += 4 }
                            else -> cur.append(n)
                        }
                        k += 2
                    }
                    c == '"' -> { if (cur.isNotEmpty()) parts += cur.toString(); return (k + 1) to parts }
                    c == '$' && k + 1 < text.length && text[k + 1] == '{' -> {
                        if (cur.isNotEmpty()) { parts += cur.toString(); cur.clear() }
                        var depth = 1
                        var m = k + 2
                        while (m < text.length && depth > 0) {
                            when (text[m]) {
                                '{' -> depth++
                                '}' -> depth--
                                '"' -> { m = skipString(m + 1).first; continue }
                            }
                            m++
                        }
                        parts += Expr(text.substring(k + 2, m - 1).trim())
                        k = m
                    }
                    c == '$' && k + 1 < text.length && (text[k + 1].isLetter() || text[k + 1] == '_') -> {
                        if (cur.isNotEmpty()) { parts += cur.toString(); cur.clear() }
                        var m = k + 1
                        while (m < text.length && (text[m].isLetterOrDigit() || text[m] == '_')) m++
                        parts += Expr(text.substring(k + 1, m))
                        k = m
                    }
                    else -> { cur.append(c); k++ }
                }
            }
            return k to parts
        }
        while (i < text.length) {
            val c = text[i]
            when {
                text.startsWith("//", i) -> i = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                text.startsWith("/*", i) -> i = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                text.startsWith("\"\"\"", i) -> i = text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length else it + 3 }
                c == '\'' -> {
                    var k = i + 1
                    while (k < text.length && text[k] != '\'') k += if (text[k] == '\\') 2 else 1
                    i = k + 1
                }
                c == '"' -> {
                    val (end, parts) = skipString(i + 1)
                    out += Lit(i, end, parts)
                    i = end
                }
                else -> i++
            }
        }
        return out
    }

    private fun withImports(text: String, f: File): String {
        val pkg = Regex("^package ([\\w.]+)", RegexOption.MULTILINE).find(text)?.groupValues?.get(1) ?: return text
        if (pkg == "com.prism.music") return text
        val need = listOf("import com.prism.music.R", "import com.prism.music.tr").filter { imp -> !Regex("^${Regex.escape(imp)}$", RegexOption.MULTILINE).containsMatchIn(text) }
        if (need.isEmpty()) return text
        val pkgLine = Regex("^package [\\w.]+\\s*\n", RegexOption.MULTILINE).find(text)!!
        val firstImport = Regex("^import ", RegexOption.MULTILINE).find(text, pkgLine.range.last)
        val at = firstImport?.range?.first ?: (pkgLine.range.last + 1)
        return text.substring(0, at) + need.joinToString("\n", postfix = "\n") + text.substring(at)
    }

    companion object {
        /** Files left as they are: tools, Prism's own text tool, debug-only screens. */
        internal val SKIP_FILES = setOf(
            "Strings.kt", "DesignGallery.kt",
            // Diagnostics stay English, so a bug report reads the same from every phone.
            "AudioDiagnostics.kt", "MainThreadWatch.kt", "CrashLog.kt", "NerdStats.kt",
            // The equalizer's preset names are stored as the listener's choice (shown translated later, by name).
            "AudioEffects.kt",
        )

        fun slug(s: String): String {
            val words = s.lowercase().replace(Regex("%\\d\\\$s"), " ").split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
                .map { w -> w.filter { it in 'a'..'z' || it in '0'..'9' } }.filter { it.isNotEmpty() }
            val k = words.take(6).joinToString("_").take(48).trimEnd('_').ifEmpty { "text" }
            return when {
                k.first().isDigit() -> "t_$k"
                k in JAVA_WORDS -> "${k}_text" // resource names become Java fields
                else -> k
            }
        }

        private val JAVA_WORDS = setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue", "default", "do",
            "double", "else", "enum", "extends", "false", "final", "finally", "float", "for", "goto", "if", "implements", "import",
            "instanceof", "int", "interface", "long", "native", "new", "null", "package", "private", "protected", "public", "return",
            "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "true", "try",
            "void", "volatile", "while", "var", "record", "yield",
            // Kotlin's own (R.string.in wouldn't compile).
            "as", "fun", "in", "is", "object", "typealias", "typeof", "val", "when", "it",
        )

        fun xmlEscape(s: String): String {
            val b = StringBuilder()
            for (c in s) when (c) {
                '\\' -> b.append("\\\\"); '\'' -> b.append("\\'"); '"' -> b.append("\\\""); '&' -> b.append("&amp;")
                '<' -> b.append("&lt;"); '>' -> b.append("&gt;"); '\n' -> b.append("\\n"); '\t' -> b.append("\\t")
                else -> b.append(c)
            }
            val out = b.toString()
            return if (out.startsWith("@") || out.startsWith("?")) "\\$out" else out
        }

        fun xmlUnescape(s: String): String = s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
            .replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t").replace("\\@", "@").replace("\\?", "?").replace("\\\\", "\\")
    }
}
