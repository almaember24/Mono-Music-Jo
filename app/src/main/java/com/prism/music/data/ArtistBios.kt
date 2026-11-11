package com.prism.music.data

import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.SearchRank
import com.prism.music.data.innertube.at
import com.prism.music.data.innertube.obj
import com.prism.music.data.innertube.str
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistPage
import com.prism.music.data.model.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.Locale

/** A short biography from Wikipedia, with what its licence asks to be shown beside it. */
@Serializable
data class ArtistBio(
    /** The article's own title, for the credit. */
    val title: String,
    val text: String,
    val url: String,
    val licenseName: String,
    val licenseUrl: String,
    val lang: String,
)

/**
 * How Prism tells an artist's own Wikipedia article from everything else with that name (Prince is a title of nobility,
 * Nirvana a Buddhist idea, Muse a Greek goddess, Boston a city, and any of them can be another musician). All of it works
 * in any language. Anything short of sure is no article.
 */
object ArtistBioCheck {
    /** Titles any artist might have, which say nothing about which artist this is. */
    private val GENERIC = setOf(
        "greatest hits", "the greatest hits", "best of", "the best of", "the very best of", "hits", "the hits", "live", "live at", "acoustic", "ballads",
        "singles", "the singles", "remixes", "the remixes", "b sides", "bsides", "essentials", "the essentials", "the collection", "collection",
        "anthology", "instrumental", "instrumentals", "unplugged", "demos", "love", "home", "intro", "outro", "untitled",
    )

    /** "Muse (band)" and "Muse（バンド）" are called "Muse". */
    fun baseTitle(title: String): String {
        val t = title.trim()
        if (!t.endsWith(")") && !t.endsWith("）")) return t
        val open = maxOf(t.lastIndexOf('('), t.lastIndexOf('（'))
        return if (open > 0) t.substring(0, open).trim() else t
    }

    /** Whether an article called [title] (or redirected from [matched]) is named what the artist is. */
    fun sameName(name: String, title: String, matched: String? = null): Boolean {
        val want = SearchRank.norm(name)
        if (want.isEmpty()) return false
        return SearchRank.norm(baseTitle(title)) == want || (matched != null && SearchRank.norm(baseTitle(matched)) == want)
    }

    /**
     * What on [page] can tell its artist apart: the titles of their albums and songs, without editions in brackets and
     * without any named after the artist or too common to count.
     */
    fun titlesOf(page: ArtistPage): List<String> = usable(
        page.name,
        page.shelves.flatMap { it.items }.mapNotNull { i ->
            when (i) {
                is AlbumItem -> i.title
                is SongItem -> i.song.title.takeUnless { i.song.isVideo }
                else -> null
            }
        },
    )

    private val BRACKETS = Regex("\\s*[(\\[（［].*?[)\\]）］]")

    fun usable(artist: String, titles: List<String>): List<String> {
        val self = SearchRank.norm(artist)
        return titles.asSequence()
            .map { it.replace(BRACKETS, "").trim() }
            .filter { t -> SearchRank.norm(t).let { it.length >= 3 && it != self && it !in GENERIC } }
            .distinctBy { SearchRank.norm(it) }
            .take(MAX_TITLES).toList()
    }

    /** How many of [titles] an article names as titles: set off in its markup, not just words that happen to be there. */
    fun named(source: String, titles: List<String>): Int {
        val text = source.lowercase(Locale.ROOT).replace('’', '\'')
        return titles.count { t -> setOff(text, t.lowercase(Locale.ROOT).replace('’', '\'')) }
    }

    private const val OPENERS = "'\"“„«『「《[|"
    private const val CLOSERS = "'\"”“»』」》]|"

    private fun setOff(text: String, title: String): Boolean {
        var at = text.indexOf(title)
        while (at >= 0) {
            val end = at + title.length
            val after = text.getOrNull(end)
            val closed = after != null && (after in CLOSERS || (after in ",." && text.getOrNull(end + 1)?.let { it in CLOSERS } == true))
            if (closed && at > 0 && text[at - 1] in OPENERS) return true
            at = text.indexOf(title, at + 1)
        }
        return false
    }

    /** How many titles an article must name: two at least, three for an artist with a long list to choose from. */
    fun needed(titles: List<String>) = if (titles.size >= 8) 3 else 2

    /** YouTube's own description of the artist is often Wikipedia's text with its credit, in the listener's language. */
    fun youTubeQuotesIt(description: String?) = description?.contains("wikipedia.org/wiki/", ignoreCase = true) == true

    /** The Wikipedia for a language, or null for one that can't name one. */
    fun wikiLanguage(locale: Locale): String? {
        val code = when (val l = locale.language.lowercase(Locale.ROOT)) {
            "in" -> "id"
            "iw" -> "he"
            "ji" -> "yi"
            "nb" -> "no"
            "fil" -> "tl"
            else -> l
        }
        return code.takeIf { it.length in 2..3 && it.all { c -> c in 'a'..'z' } }
    }

    private const val MAX_TITLES = 40
}

/**
 * Finds the artist's article through Wikipedia's public REST API (no key, no account). An article counts only if all of
 * these hold, else there's no bio:
 *  - it's named what the artist is, and isn't a disambiguation page;
 *  - Wikidata says its subject is a music artist (a MusicBrainz artist id: goddesses, cities and albums have none);
 *  - it names at least [ArtistBioCheck.needed] of the artist's own titles, so another musician of that name doesn't pass.
 * An article in the listener's language that's too short to name them can still be found through the English one,
 * following Wikipedia's own link between the two. A language with no article has no bio: English isn't shown instead.
 * Only the artist's name and titles are sent, and no cookies. Throws [IOException] when asking failed, so a bad
 * connection is never remembered as "no article".
 */
class WikipediaBios(private val http: OkHttpClient, private val userAgent: String) {
    private class Candidate(val key: String, val title: String)
    private class Summary(val key: String, val title: String, val text: String, val url: String, val item: String?)
    private class License(val name: String, val url: String)

    suspend fun find(name: String, knownTitles: List<String>, lang: String): ArtistBio? = withContext(Dispatchers.IO) {
        val titles = ArtistBioCheck.usable(name, knownTitles)
        if (titles.size < 2) return@withContext null
        verified(lang, name, titles)?.let { (summary, license) -> return@withContext summary.toBio(lang, license) }
        if (lang == "en") return@withContext null
        val (english, license) = verified("en", name, titles) ?: return@withContext null
        val there = links(english.key).firstOrNull { it.first == lang }?.second ?: return@withContext null
        val summary = summary(lang, there) ?: return@withContext null
        summary.takeIf { it.item == english.item && it.item != null }?.toBio(lang, license)
    }

    private fun Summary.toBio(lang: String, license: License) = ArtistBio(title, text.take(MAX_TEXT), url, license.name, license.url, lang)

    private fun verified(lang: String, name: String, titles: List<String>): Pair<Summary, License>? {
        val tried = HashSet<String>()
        for (query in listOf(name, "$name ${titles.first()}")) {
            for (c in search(lang, query, name)) {
                if (!tried.add(c.key) || tried.size > MAX_CANDIDATES) continue
                val summary = summary(lang, c.key) ?: continue
                // Cheapest first: the article's text can be hundreds of KB, and most of those named the same aren't musicians.
                if (summary.item == null || !isMusicArtist(summary.item)) continue
                val (source, license) = source(lang, c.key) ?: continue
                if (ArtistBioCheck.named(source, titles) >= ArtistBioCheck.needed(titles)) return summary to license
            }
        }
        return null
    }

    private fun wiki(lang: String, path: String): HttpUrl.Builder =
        HttpUrl.Builder().scheme("https").host("$lang.wikipedia.org").addPathSegments(path)

    /** What [query] finds that's named [name] (the second search adds an album's title; the name alone must match). */
    private fun search(lang: String, query: String, name: String): List<Candidate> {
        val url = wiki(lang, "w/rest.php/v1/search/page").addQueryParameter("q", query).addQueryParameter("limit", "8").build()
        return (get(url).at("pages") as? JsonArray).orEmpty().mapNotNull { p ->
            val key = p.str("key") ?: return@mapNotNull null
            val title = p.str("title") ?: return@mapNotNull null
            Candidate(key, title).takeIf { ArtistBioCheck.sameName(name, title, p.str("matched_title")) }
        }
    }

    private fun summary(lang: String, key: String): Summary? {
        val s = get(wiki(lang, "api/rest_v1/page/summary").addPathSegment(key).build()) ?: return null
        if (s.str("type") != "standard") return null
        val text = s.str("extract")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val url = (s.str("content_urls", "mobile", "page") ?: s.str("content_urls", "desktop", "page"))?.takeIf { isWikipedia(it) } ?: return null
        return Summary(key, s.str("title") ?: key.replace('_', ' '), text, url, s.str("wikibase_item"))
    }

    private fun isWikipedia(url: String) = runCatching { java.net.URI(url) }.getOrNull()?.let { it.scheme == "https" && it.host.orEmpty().endsWith(".wikipedia.org") } == true

    /** Wikidata's "MusicBrainz artist ID" on the article's subject: people and groups who make music have one. */
    private fun isMusicArtist(item: String): Boolean {
        val url = HttpUrl.Builder().scheme("https").host("www.wikidata.org").addPathSegments("w/api.php")
            .addQueryParameter("action", "wbgetclaims").addQueryParameter("entity", item)
            .addQueryParameter("property", "P434").addQueryParameter("format", "json").build()
        return (get(url).obj("claims")?.get("P434") as? JsonArray)?.isNotEmpty() == true
    }

    private fun source(lang: String, key: String): Pair<String, License>? {
        val p = get(wiki(lang, "w/rest.php/v1/page").addPathSegment(key).build()) ?: return null
        val source = p.str("source") ?: return null
        val name = p.str("license", "title") ?: DEFAULT_LICENSE
        val url = p.str("license", "url")?.takeIf { it.startsWith("https://") } ?: DEFAULT_LICENSE_URL
        return source to License(name, url)
    }

    /** Wikipedia's links from an English article to its others: language, then that article's key. */
    private fun links(key: String): List<Pair<String, String>> =
        (get(wiki("en", "w/rest.php/v1/page").addPathSegment(key).addPathSegment("links").addPathSegment("language").build()) as? JsonArray).orEmpty()
            .mapNotNull { l -> (l.str("code") to l.str("key")).let { (a, b) -> if (a != null && b != null) a to b else null } }

    private fun get(url: HttpUrl): JsonElement? {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", "application/json").build()
        http.newCall(request).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw IOException("Wikipedia answered ${r.code}")
            // Read outside the parsing: this is what waits on the network, and a connection lost here is a failed ask.
            val bytes = r.peekBody(MAX_ANSWER).bytes()
            return runCatching { Json.parseToJsonElement(bytes.decodeToString()) }.getOrElse {
                // An answer cut off at the limit says nothing; anything shorter that isn't an answer is a Wi-Fi's sign-in page or the like.
                if (bytes.size >= MAX_ANSWER) null else throw IOException("Not an answer from Wikipedia", it)
            }
        }
    }

    private companion object {
        const val MAX_CANDIDATES = 4
        const val MAX_TEXT = 1_500
        const val MAX_ANSWER = 3_000_000L
        const val DEFAULT_LICENSE = "CC BY-SA 4.0"
        const val DEFAULT_LICENSE_URL = "https://creativecommons.org/licenses/by-sa/4.0/"
    }
}

/**
 * The bios kept on the phone, one small file per artist and language, so a page shows its bio at once and offline.
 * "No article" is remembered too, so it isn't asked again on every visit; a failed ask isn't.
 */
class ArtistBios(
    private val dir: File,
    private val finder: suspend (name: String, titles: List<String>, lang: String) -> ArtistBio?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    private data class Kept(val bio: ArtistBio? = null, val checkedAt: Long)

    private fun file(id: String, lang: String) = File(dir, lang + "_" + id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    private fun read(id: String, lang: String): Kept? =
        runCatching { InnerTube.json.decodeFromString(Kept.serializer(), file(id, lang).readText()) }.getOrNull()

    /** What's kept for the artist, without asking anyone. Reads a file. */
    fun kept(id: String, lang: String): ArtistBio? = read(id, lang)?.bio

    /** The artist's bio: kept, or asked for when [mayAsk] and what's kept has aged. Null when there's none or it isn't sure. */
    suspend fun bio(id: String, name: String, titles: List<String>, lang: String, mayAsk: Boolean): ArtistBio? = withContext(Dispatchers.IO) {
        val kept = read(id, lang)
        if (!mayAsk) return@withContext kept?.bio
        if (kept != null && now() - kept.checkedAt < if (kept.bio != null) KEEP_BIO_MS else KEEP_NONE_MS) return@withContext kept.bio
        try {
            val found = finder(name, titles, lang)
            save(id, lang, Kept(found, now()))
            found
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            kept?.bio
        }
    }

    private fun save(id: String, lang: String, kept: Kept) {
        runCatching {
            dir.mkdirs()
            val target = file(id, lang)
            val tmp = File(dir, target.name + ".tmp")
            tmp.writeText(InnerTube.json.encodeToString(Kept.serializer(), kept))
            if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
            dir.listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.lastModified() }?.drop(MAX_KEPT)?.forEach { it.delete() }
        }
    }

    internal companion object {
        const val KEEP_BIO_MS = 90L * 24 * 60 * 60 * 1000
        const val KEEP_NONE_MS = 30L * 24 * 60 * 60 * 1000
        const val MAX_KEPT = 400
    }
}
