package com.prism.music.data.podcast

import okhttp3.HttpUrl
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** What a feed says, once everything in it was checked: plain text only, and only links Prism may open. */
data class ParsedFeed(
    val title: String?,
    val author: String?,
    val description: String?,
    val image: String?,
    val items: List<ParsedItem>,
    /** Cut short: the feed was longer than Prism reads ([RssFeed.MAX_BYTES], [RssFeed.MAX_ITEMS]), or it broke part-way. */
    val partial: Boolean,
)

data class ParsedItem(
    /** What the feed calls this episode for good: its guid or Atom id, else its audio link. */
    val key: String,
    val title: String?,
    val audioUrl: String,
    val description: String?,
    val publishedAt: Long?,
    val durationSec: Int,
    val image: String?,
)

/**
 * Reads a podcast feed (RSS 2.0 with iTunes tags, RSS 1.0, Atom). A feed is someone else's data, so:
 *
 * - **No DTD.** A document that declares one is refused before anything in the declaration is used, so no entity is
 *   ever expanded (a "billion laughs" feed) or fetched (an external entity). `android.util.Xml.newPullParser()` turns
 *   declarations on; this parser comes from the plain factory, which leaves them off.
 * - **Bounded.** At most [MAX_BYTES] of the feed's text are read (counted after gzip, so a small zip can't unpack into a
 *   huge one) and [MAX_ITEMS] items, each text at most [MAX_TEXT] characters. A long but honest feed keeps what fits: its
 *   newest episodes, since feeds list those first.
 * - **Plain text.** Descriptions come out of [plain]: no HTML reaches the screen, nor a tag's attributes.
 * - **Links through [RssGuard.link]:** http(s) only, nothing on the listener's own network.
 *
 * Otherwise it's forgiving, as real feeds need it to be: odd dates and lengths, relative links, undeclared prefixes,
 * HTML entities, a feed that breaks half-way (what came before it stands).
 */
object RssFeed {
    const val MAX_BYTES = 5L * 1024 * 1024
    const val MAX_ITEMS = 3_000
    private const val MAX_TEXT = 64 * 1024
    private const val MAX_DEPTH = 48
    private const val MAX_KEY = 512
    const val MAX_TITLE = 300
    const val MAX_AUTHOR = 200
    const val MAX_SHOW_DESCRIPTION = 4_000
    const val MAX_EPISODE_DESCRIPTION = 3_000

    /** kxml's own: a bare "&" or an unknown entity is text, not the end of the feed. */
    private const val RELAXED = "http://xmlpull.org/v1/doc/features.html#relaxed"
    private val ROOTS = setOf("rss", "feed", "rdf:rdf")
    private val ITEMS = setOf("item", "entry", "atom:entry")
    /** Elements that may hold HTML as markup (Atom's xhtml): what's inside them doesn't start a new text. */
    private val RICH = setOf("description", "summary", "content", "content:encoded", "itunes:summary", "subtitle", "itunes:subtitle")

    private class ShowParts {
        var title: String? = null
        var author: String? = null
        var description: String? = null
        var summary: String? = null
        var itunesImage: String? = null
        var image: String? = null
    }

    private class ItemParts {
        var title: String? = null
        var itunesTitle: String? = null
        var guid: String? = null
        var description: String? = null
        var summary: String? = null
        var content: String? = null
        var date: String? = null
        var duration: String? = null
        var image: String? = null
        var link: String? = null
        /** Link and declared type, in the feed's order: enclosures first, media:content after. */
        val enclosures = ArrayList<Pair<String, String?>>(1)
        val media = ArrayList<Pair<String, String?>>(1)
    }

    /**
     * The feed in [input]; [base] is where it came from, for relative links. Throws [RssException] when it isn't a feed
     * Prism can use, and lets the [IOException] of a dropped connection through.
     */
    fun parse(input: InputStream, base: HttpUrl): ParsedFeed {
        val capped = CappedStream(input, MAX_BYTES)
        val show = ShowParts()
        val items = ArrayList<ParsedItem>()
        val dates = DateReader()
        val path = ArrayList<String>(16)
        val text = StringBuilder()
        var root: String? = null
        var item: ItemParts? = null
        var itemDepth = -1
        var richDepth = -1
        var seen = 0
        var partial = false
        var reading = true
        fun add(s: String?) { if (s != null && text.length < MAX_TEXT) text.append(s, 0, minOf(s.length, MAX_TEXT - text.length)) }
        try {
            val p = XmlPullParserFactory.newInstance().newPullParser()
            runCatching { p.setFeature(RELAXED, true) }
            p.setInput(capped, null)
            while (reading) {
                // Token by token up to the root, the only place a DTD is shown (and is refused). After it, whole texts:
                // nextToken() can't give back a bare "&" once an entity it doesn't know went by ("AT&T" came out as "AT").
                // A later declaration is skipped by the parser: declarations are off, so nothing in it is ever used.
                when (if (root == null) p.nextToken() else p.next()) {
                    XmlPullParser.END_DOCUMENT -> {
                        // An element left open: the feed stopped part-way. In relaxed mode that's no error.
                        if (path.isNotEmpty()) partial = true
                        reading = false
                    }
                    // Refused as it's met, and nothing in it is kept: a podcast feed has no use for one.
                    XmlPullParser.DOCDECL -> throw RssException(RssProblem.NOT_A_FEED)
                    XmlPullParser.START_TAG -> {
                        val name = p.name.lowercase(Locale.ROOT)
                        if (root == null) {
                            root = name
                            if (name !in ROOTS) throw RssException(RssProblem.NOT_A_FEED)
                        }
                        if (path.size >= MAX_DEPTH) { partial = true; reading = false; continue }
                        path += name
                        if (richDepth >= 0) { text.append(' '); continue }
                        text.setLength(0)
                        if (name in RICH) richDepth = path.size
                        if (item == null && name in ITEMS) {
                            item = ItemParts()
                            itemDepth = path.size
                        } else if (item != null && path.size == itemDepth + 1) attributesOfItem(p, name, item)
                        else if (item == null && path.size in 2..3) attributesOfShow(p, name, show)
                    }
                    // A named entity XML doesn't know (&nbsp; outside CDATA) stays in the text for [plain] to read.
                    XmlPullParser.TEXT -> add(p.text)
                    XmlPullParser.END_TAG -> {
                        if (path.isEmpty()) continue
                        val depth = path.size
                        val name = path.removeAt(path.lastIndex)
                        if (richDepth >= 0 && depth > richDepth) continue
                        richDepth = -1
                        val value = text.toString().trim()
                        text.setLength(0)
                        val parent = path.lastOrNull()
                        val open = item
                        if (open != null && depth == itemDepth) {
                            seen++
                            build(open, base, dates)?.let { items += it }
                            item = null
                            if (seen >= MAX_ITEMS) { partial = true; reading = false }
                        } else if (open != null) {
                            if (depth == itemDepth + 1) textOfItem(name, value, open)
                        } else textOfShow(name, parent, path.getOrNull(path.size - 2), value, show)
                    }
                }
            }
        } catch (e: RssException) {
            throw e
        } catch (e: XmlPullParserException) {
            partial = true
        } catch (e: IOException) {
            throw e
        } catch (e: RuntimeException) {
            // Bad enough to trip the parser: as broken as any other break in the XML.
            partial = true
        }
        if (root == null) throw RssException(RssProblem.NOT_A_FEED)
        if (items.isEmpty()) throw RssException(if (capped.reachedCap || !partial) RssProblem.NO_EPISODES else RssProblem.NOT_A_FEED)
        return ParsedFeed(
            title = plain(show.title, MAX_TITLE),
            author = plain(show.author, MAX_AUTHOR),
            description = plain(show.description ?: show.summary, MAX_SHOW_DESCRIPTION),
            image = RssGuard.link(show.itunesImage, base) ?: RssGuard.link(show.image, base),
            items = items,
            partial = partial || capped.reachedCap,
        )
    }

    private fun attr(p: XmlPullParser, name: String): String? {
        for (i in 0 until p.attributeCount) if (p.getAttributeName(i).equals(name, ignoreCase = true)) return p.getAttributeValue(i)
        return null
    }

    private fun attributesOfShow(p: XmlPullParser, name: String, show: ShowParts) {
        if (name == "itunes:image") attr(p, "href")?.let { show.itunesImage = show.itunesImage ?: it }
    }

    private fun attributesOfItem(p: XmlPullParser, name: String, item: ItemParts) {
        when (name) {
            "enclosure" -> attr(p, "url")?.let { item.enclosures += it to attr(p, "type") }
            "media:content" -> attr(p, "url")?.let { item.media += it to (attr(p, "type") ?: attr(p, "medium")?.let { m -> "$m/" }) }
            "itunes:image" -> attr(p, "href")?.let { item.image = item.image ?: it }
            "media:thumbnail" -> attr(p, "url")?.let { item.image = item.image ?: it }
            "link", "atom:link" -> {
                val href = attr(p, "href") ?: return
                when (attr(p, "rel")?.lowercase(Locale.ROOT)) {
                    "enclosure" -> item.enclosures += href to attr(p, "type")
                    null, "alternate" -> item.link = item.link ?: href
                }
            }
        }
    }

    private fun textOfShow(name: String, parent: String?, grand: String?, value: String, show: ShowParts) {
        if (value.isEmpty()) return
        when {
            parent == "channel" || parent == "feed" || parent == "atom:feed" -> when (name) {
                "title", "atom:title" -> show.title = show.title ?: value
                "itunes:author", "dc:creator" -> show.author = show.author ?: value
                "description" -> show.description = show.description ?: value
                "itunes:summary", "subtitle", "itunes:subtitle" -> show.summary = show.summary ?: value
                "logo", "icon" -> show.image = show.image ?: value
            }
            // <image><url>, the channel's own (RSS 1.0 lists it beside the channel).
            name == "url" && parent == "image" && (grand == "channel" || grand == "rdf:rdf") -> show.image = show.image ?: value
            // Atom's <author><name>.
            name == "name" && parent == "author" && (grand == "feed" || grand == "atom:feed") -> show.author = show.author ?: value
        }
    }

    private fun textOfItem(name: String, value: String, item: ItemParts) {
        if (value.isEmpty()) return
        when (name) {
            "title", "atom:title" -> item.title = item.title ?: value
            "itunes:title" -> item.itunesTitle = value
            "guid", "id", "atom:id" -> item.guid = item.guid ?: value
            "description" -> item.description = item.description ?: value
            "summary", "itunes:summary" -> item.summary = item.summary ?: value
            "content", "content:encoded" -> item.content = item.content ?: value
            "pubdate", "published", "dc:date" -> item.date = value
            "updated" -> item.date = item.date ?: value
            "itunes:duration", "duration" -> item.duration = value
            "link" -> item.link = item.link ?: value
        }
    }

    /** The finished item, or null when it has no audio Prism may play (that's what an episode is here). */
    private fun build(item: ItemParts, base: HttpUrl, dates: DateReader): ParsedItem? {
        val audio = audioLink(item, base) ?: return null
        val key = item.guid?.takeIf { it.length <= MAX_KEY } ?: audio
        return ParsedItem(
            key = key,
            title = plain(item.title ?: item.itunesTitle, MAX_TITLE) ?: fileName(audio),
            audioUrl = audio,
            description = plain(item.description ?: item.summary ?: item.content, MAX_EPISODE_DESCRIPTION),
            publishedAt = dates.read(item.date),
            durationSec = durationOf(item.duration),
            image = RssGuard.link(item.image, base),
        )
    }

    private fun audioLink(item: ItemParts, base: HttpUrl): String? =
        (item.enclosures + item.media + listOfNotNull(item.link?.let { it to null })).firstNotNullOfOrNull { (raw, type) ->
            RssGuard.link(raw, base)?.takeIf { isAudio(type, it) }
        }

    /** Audio by its declared type, or by the file's name when the type says nothing useful. Streaming playlists aren't files. */
    internal fun isAudio(type: String?, url: String): Boolean {
        val t = type?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val ext = url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext in PLAYLISTS || "mpegurl" in t) return false
        return when {
            t.startsWith("audio/") -> true
            t.isEmpty() || t in VAGUE_TYPES -> ext in AUDIO_FILES
            else -> false
        }
    }

    private val PLAYLISTS = setOf("m3u", "m3u8", "pls", "asx")
    private val VAGUE_TYPES = setOf("application/octet-stream", "binary/octet-stream", "application/x-download", "application/download")
    private val AUDIO_FILES = setOf("mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "flac", "wav", "mpga")

    /** An episode with no title goes by its file's name. */
    private fun fileName(url: String): String? = runCatching {
        java.net.URLDecoder.decode(url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringBeforeLast('.'), "UTF-8")
    }.getOrNull()?.let { plain(it, MAX_TITLE) }

    // ---------------------------------------------------------------- Text

    private val SKIPPED_TAGS = setOf("script", "style", "head", "iframe", "object", "embed", "svg", "noscript", "template", "math", "video", "audio")
    private val BREAK_TAGS = setOf("br", "p", "div", "li", "ul", "ol", "tr", "table", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "hr", "section", "article")
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "ndash" to "–", "mdash" to "—",
        "hellip" to "…", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "copy" to "©", "reg" to "®", "trade" to "™",
        "bull" to "•", "middot" to "·", "eacute" to "é", "egrave" to "è", "agrave" to "à", "aacute" to "á", "iacute" to "í",
        "oacute" to "ó", "uacute" to "ú", "ntilde" to "ñ", "uuml" to "ü", "ouml" to "ö", "auml" to "ä", "ccedil" to "ç", "szlig" to "ß",
    )

    /** [raw] as plain text of at most [max] characters: tags gone, what scripts and styles hold gone, entities read. Null when nothing's left. */
    internal fun plain(raw: String?, max: Int): String? {
        if (raw.isNullOrBlank()) return null
        // Escaped once more than it should be, as some feeds do: "&lt;p&gt;Hello".
        val src = if ('<' !in raw && "&lt;" in raw) decodeEntities(raw) else raw
        val out = StringBuilder(minOf(src.length, max * 2))
        var i = 0
        val n = src.length
        while (i < n && out.length < max * 2) {
            val ch = src[i]
            val next = src.getOrNull(i + 1)
            if (ch != '<' || next == null || !(next.isLetter() || next == '/' || next == '!' || next == '?')) { out.append(ch); i++; continue }
            if (src.startsWith("<!--", i)) {
                val end = src.indexOf("-->", i + 4)
                i = if (end < 0) n else end + 3
                continue
            }
            val closing = next == '/'
            val start = if (closing) i + 2 else i + 1
            var j = start
            while (j < n && src[j].isLetterOrDigit()) j++
            val tag = src.substring(start, j).lowercase(Locale.ROOT)
            val end = src.indexOf('>', j)
            // A tag that never ends: what's left isn't text anyone meant to show.
            if (end < 0) break
            i = end + 1
            if (!closing && tag in SKIPPED_TAGS && !src.startsWith("/>", end - 1)) {
                val close = src.indexOf("</$tag", i, ignoreCase = true)
                val after = if (close < 0) -1 else src.indexOf('>', close)
                i = if (after < 0) n else after + 1
            } else if (tag in BREAK_TAGS) out.append('\n')
        }
        return clean(decodeEntities(out.toString()), max)
    }

    internal fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            // Looked for only as far as an entity can reach: a feed of bare ampersands doesn't walk to the end for each.
            val semi = if (ch == '&') (i + 2..minOf(i + 11, s.length - 1)).firstOrNull { s[it] == ';' } else null
            if (semi == null) { out.append(ch); i++; continue }
            val body = s.substring(i + 1, semi)
            val decoded = when {
                body.startsWith("#x", true) -> body.substring(2).toIntOrNull(16)?.let(::codePoint)
                body.startsWith("#") -> body.substring(1).toIntOrNull()?.let(::codePoint)
                else -> NAMED[body]
            }
            if (decoded == null) { out.append(ch); i++ } else { out.append(decoded); i = semi + 1 }
        }
        return out.toString()
    }

    private fun codePoint(v: Int): String? = if (Character.isValidCodePoint(v) && v !in 0xD800..0xDFFF) String(Character.toChars(v)) else null

    /** No control characters or direction overrides (they can make a title read as something else); spacing tidied. */
    private fun clean(s: String, max: Int): String? {
        val out = StringBuilder(minOf(s.length, max + 16))
        var blankLines = 0
        var space = false
        var lineHasText = false
        for (c in s) {
            if (out.length >= max) break
            when {
                c == '\n' -> {
                    if (!lineHasText) blankLines++ else blankLines = 0
                    if (blankLines <= 1 && out.isNotEmpty()) out.append('\n')
                    lineHasText = false
                    space = false
                }
                c == ' ' || c == '\t' || c == ' ' -> space = lineHasText
                c == '\r' || c.code < 0x20 || c.code in 0x7F..0x9F || c == '\uFEFF' -> Unit
                c in '‪'..'‮' || c in '⁦'..'⁩' -> Unit
                else -> {
                    if (space) out.append(' ')
                    space = false
                    out.append(c)
                    lineHasText = true
                    blankLines = 0
                }
            }
        }
        return out.toString().trim().ifEmpty { null }
    }

    // ---------------------------------------------------------------- Dates and lengths

    /** "1:02:03", "62:03", "3723", "3723.5"; 0 when it's anything else or absurd. */
    internal fun durationOf(raw: String?): Int {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return 0
        val parts = s.split(':')
        if (parts.size > 3) return 0
        var total = 0L
        for (p in parts) {
            val v = p.trim().toDoubleOrNull()?.takeIf { it >= 0 && !it.isNaN() && !it.isInfinite() } ?: return 0
            total = total * 60 + v.toLong()
            if (total > MAX_DURATION_SEC) return 0
        }
        return total.toInt()
    }

    private const val MAX_DURATION_SEC = 7 * 24 * 3600L

    /** One feed's dates. Keeps its formats (slow to make, not to be shared between threads). */
    internal class DateReader {
        private val utc = TimeZone.getTimeZone("UTC")
        private val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss z", "dd MMM yyyy HH:mm:ss Z", "dd MMM yyyy HH:mm:ss z",
            "EEE, dd MMM yyyy HH:mm Z", "EEE, dd MMM yyyy HH:mm z", "EEE, dd MMM yyyy HH:mm:ss", "EEE, dd MMM yyyy",
            "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mmXXX", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd",
            "dd MMM yyyy", "MMM dd, yyyy", "MMMM dd, yyyy", "yyyy/MM/dd",
        ).map { p -> SimpleDateFormat(p, Locale.US).apply { timeZone = utc; isLenient = true } }
        private val latest = Calendar.getInstance(utc).apply { add(Calendar.YEAR, 2) }.timeInMillis
        private val earliest = Calendar.getInstance(utc).apply { clear(); set(1990, Calendar.JANUARY, 1) }.timeInMillis

        fun read(raw: String?): Long? {
            val s = raw?.trim()?.replace(Regex("\\s+"), " ")?.take(64) ?: return null
            if (s.isEmpty()) return null
            // Fractions of a second aren't worth a format of their own, and "UT" is RFC 822's "UTC".
            val text = s.replace(Regex("(:\\d{2})\\.\\d+"), "$1").replace(Regex(" UT$"), " UTC")
            for (f in formats) {
                val at: Date = runCatching { f.parse(text) }.getOrNull() ?: continue
                return at.time.takeIf { it in earliest..latest } ?: continue
            }
            return null
        }
    }

    /** Reads up to [cap] bytes, then says the stream ended (and [reachedCap]). */
    private class CappedStream(private val input: InputStream, private val cap: Long) : InputStream() {
        private var read = 0L
        var reachedCap = false
            private set

        override fun read(): Int {
            if (read >= cap) { reachedCap = true; return -1 }
            return input.read().also { if (it >= 0) read++ }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (read >= cap) { reachedCap = true; return -1 }
            val n = input.read(b, off, minOf(len.toLong(), cap - read).toInt())
            if (n > 0) read += n
            return n
        }
    }
}
