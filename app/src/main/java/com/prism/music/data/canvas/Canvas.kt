package com.prism.music.data.canvas

import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.str
import com.prism.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.util.Base64
import java.util.Locale

/** A looping clip that stands in for a track's cover art. */
@Serializable
data class CanvasArtwork(
    val url: String,
    val fallbackUrl: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
)

/**
 * Letters of every script are kept (#58: only a–z were, so a Russian title came out empty, and an empty title
 * "matched" the community index's first clip).
 */
internal fun String.normalizeForMatch(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private val ARTIST_SEPARATORS = Regex(
    "(?:\\s*,\\s*|\\s*&\\s*|\\s+×\\s+|\\s+x\\s+|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b|\\bwith\\b)",
    RegexOption.IGNORE_CASE,
)

internal fun splitArtists(raw: String): List<String> =
    raw.split(ARTIST_SEPARATORS).map { it.normalizeForMatch() }.filter { it.isNotBlank() }

private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

/**
 * Finds motion artwork for the player: Apple Music's animated covers, Tidal's
 * video covers, then a community index. Every answer is re-checked against the
 * track, because a wrong clip is worse than none; any failure is just "no canvas".
 */
class CanvasRepository(private val http: OkHttpClient) {
    /** Songs whose animated artwork you've switched off from the player; set up by the app container. */
    var hiddenPrefs: android.content.SharedPreferences? = null
        set(v) { field = v; hidden.value = v?.getStringSet("ids", emptySet())?.toSet() ?: emptySet() }
    val hidden = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

    fun setHidden(songId: String, hide: Boolean) {
        val next = if (hide) hidden.value + songId else hidden.value - songId
        hidden.value = next
        hiddenPrefs?.edit()?.putStringSet("ids", next)?.apply()
    }

    /**
     * Animated covers kept on the phone for good (the clips live in [CanvasStore]): songs saved from
     * the player, whole albums (saving one song's cover keeps the album's), and downloaded songs.
     */
    data class Saved(val songs: Set<String> = emptySet(), val albums: Set<String> = emptySet(), val downloads: Set<String> = emptySet()) {
        fun has(songId: String, album: String?) = songId in songs || songId in downloads || (album != null && album in albums)
        val count: Int get() = (songs + downloads).size + albums.size
    }

    var savedPrefs: android.content.SharedPreferences? = null
        set(v) {
            field = v
            fun read(key: String) = v?.getStringSet(key, emptySet())?.toSet() ?: emptySet()
            saved.value = Saved(read("ids"), read("albums"), read("downloads"))
        }
    val saved = kotlinx.coroutines.flow.MutableStateFlow(Saved())

    fun isSaved(song: Song) = saved.value.has(song.id, albumKey(song))
    private fun isSaved(songId: String, entry: Entry?) = saved.value.has(songId, entry?.album)

    /** Saves (or forgets) the cover for [song] and every song on its album. */
    fun setSaved(song: Song, keep: Boolean) = updateSaved { s ->
        val album = albumKey(song)
        if (keep) s.copy(songs = s.songs + song.id, albums = if (album != null) s.albums + album else s.albums)
        else s.copy(songs = s.songs - song.id, albums = if (album != null) s.albums - album else s.albums)
    }

    /** Covers kept because the song is downloaded. */
    fun setSavedWithDownload(songId: String, keep: Boolean) = updateSaved { s ->
        s.copy(downloads = if (keep) s.downloads + songId else s.downloads - songId)
    }

    fun clearSaved() = updateSaved { Saved() }

    private fun updateSaved(change: (Saved) -> Saved) {
        val next = synchronized(saved) { change(saved.value).also { saved.value = it } }
        savedPrefs?.edit()?.putStringSet("ids", next.songs)?.putStringSet("albums", next.albums)?.putStringSet("downloads", next.downloads)?.apply()
        scheduleWrite()
    }

    /** The clips (by their URLs) that saved songs use; anything else in the saved store can go. */
    fun savedClipUrls(): Set<String> = synchronized(cache) {
        cache.filter { (id, e) -> e.art != null && isSaved(id, e) }.values.flatMap { listOfNotNull(it.art!!.url, it.art.fallbackUrl) }.toSet()
    }
    /** What each song's lookup found (or didn't), remembered across launches so clips start without searching again. */
    @Serializable
    private data class Entry(val art: CanvasArtwork? = null, val hadAlbum: Boolean = false, val at: Long = 0, val album: String? = null, val v: Int = 0)

    private val cache = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) =
            size > MAX_ENTRIES && !isSaved(eldest.key, eldest.value)
    }
    /**
     * Lookups under way, one per song: the player and a background save asking for the same song share one. (A single
     * lock over every lookup made the player's wait behind a whole album's, or every download's, so the songs it came
     * to showed their still cover.)
     */
    private val inFlight = HashMap<String, kotlinx.coroutines.Deferred<CanvasArtwork?>>()
    private var indexFile: java.io.File? = null
    private val ioScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private var writeJob: kotlinx.coroutines.Job? = null

    /** Reads the remembered lookups from [file] (in the background) and keeps it up to date. */
    fun useIndex(file: java.io.File) {
        indexFile = file
        ioScope.launch {
            val stored = runCatching {
                InnerTube.json.decodeFromString(MapSerializer(String.serializer(), Entry.serializer()), file.readText())
            }.getOrNull() ?: return@launch
            val now = System.currentTimeMillis()
            synchronized(cache) {
                stored.forEach { (id, e) -> if (id !in cache && (isSaved(id, e) || !e.stale(now))) cache[id] = e }
            }
        }
    }

    /** Found clips are trusted for a month (links do move), misses for a few days. Saved ones never expire. */
    private fun Entry.stale(now: Long) = now - at > if (art != null) 30 * DAY else 3 * DAY

    private fun scheduleWrite() {
        val file = indexFile ?: return
        writeJob?.cancel()
        writeJob = ioScope.launch {
            kotlinx.coroutines.delay(1500)
            val snapshot = synchronized(cache) { HashMap(cache) }
            runCatching {
                val tmp = java.io.File(file.path + ".tmp")
                tmp.writeText(InnerTube.json.encodeToString(MapSerializer(String.serializer(), Entry.serializer()), snapshot))
                tmp.renameTo(file)
            }
        }
    }

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String? = runCatching {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> b.header(k, v) }
        http.newCall(b.build()).execute().use { if (it.isSuccessful) it.body.string() else null }
    }.getOrNull()

    private fun parse(text: String?): JsonElement? = text?.let { runCatching { InnerTube.json.parseToJsonElement(it) }.getOrNull() }
    private fun JsonElement?.f(key: String): JsonElement? = (this as? JsonObject)?.get(key)

    private fun String.cleaned(): String = replace(
        Regex("\\((?:from|official|lyrical|video|audio|feat\\.?|ft\\.?)[^)]*\\)|\\[[^]]*]|\\b(?:official (?:video|audio|music video)|lyrical|4k video)\\b", RegexOption.IGNORE_CASE),
        " ",
    ).substringBefore(" | ").replace(Regex("\\s+"), " ").trim().ifBlank { this }

    /**
     * A lookup made before non-Latin titles and artists could be matched ([MATCHING]), for a song with one: the old rules
     * reduced its title or artist to nothing, which "matched" other songs' clips (#58). It's asked again, saved or not.
     */
    private fun Entry.suspect(song: Song) = v < MATCHING && (latinless(song.title) || latinless(song.primaryArtist))
    private fun latinless(s: String) = !Regex("[a-z0-9]").containsMatchIn(Normalizer.normalize(s, Normalizer.Form.NFD).lowercase(Locale.ROOT))
    private fun entry(song: Song): Entry? = cache[song.id]?.takeIf { !it.suspect(song) }

    fun cached(song: Song): CanvasArtwork? = synchronized(cache) { entry(song)?.art }

    suspend fun canvasFor(song: Song): CanvasArtwork? {
        // A feed's episode: its name isn't sent around looking for a clip of a song it isn't.
        if (song.isRss) return null
        val now = System.currentTimeMillis()
        synchronized(cache) {
            entry(song)?.let { e ->
                val fresh = isSaved(song) || !e.stale(now)
                if (fresh && (e.art != null || e.hadAlbum || song.album == null)) return e.art
            }
        }
        val job = synchronized(inFlight) {
            inFlight[song.id] ?: ioScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try { lookup(song, now) } finally { synchronized(inFlight) { inFlight.remove(song.id) } }
            }.also { inFlight[song.id] = it }
        }
        return job.await()
    }

    private suspend fun lookup(song: Song, now: Long): CanvasArtwork? {
        val album = song.album?.title
        val title = song.title.cleaned()
        val artist = song.primaryArtist.cleaned()
        if (title.isBlank() || artist.isBlank()) return null
        val found = withContext(Dispatchers.IO) {
            listOf<() -> CanvasArtwork?>(
                { apple(title, artist, album) },
                { tidal(title, artist, album) },
                { community(title, artist, album) },
                { applePage(artist, album ?: title) },
            ).firstNotNullOfOrNull { source -> runCatching { source() }.getOrNull() }
        }
        synchronized(cache) {
            // Offline or a source hiccup: keep the clip we already knew rather than forgetting it.
            val known = entry(song)?.art
            if (found != null || known == null) cache[song.id] = Entry(found, album != null, now, albumKey(song), MATCHING)
        }
        scheduleWrite()
        return found ?: synchronized(cache) { entry(song)?.art }
    }

    companion object {
        /** Which album a song belongs to, for album-wide saves. */
        fun albumKey(song: Song): String? = song.album?.let { a -> a.id ?: "${a.title}|${song.primaryArtist}".lowercase(Locale.ROOT) }

        private const val MAX_ENTRIES = 3000
        private const val DAY = 86_400_000L
        /** The matching rules' version a lookup was made with: 1 = non-Latin letters kept ([normalizeForMatch], #58). */
        private const val MATCHING = 1
    }

    // ------------------------------------------------------------ Apple Music

    private val storefront = Locale.getDefault().country.takeIf { it.length == 2 }?.lowercase(Locale.ROOT) ?: "us"
    @Volatile private var token: String? = null
    @Volatile private var tokenExpires = 0L
    @Volatile private var tokenRetryAfter = 0L

    @Synchronized
    private fun appleToken(): String? {
        val now = System.currentTimeMillis()
        token?.let { if (now < tokenExpires - 60_000) return it }
        if (now < tokenRetryAfter) return null
        val html = get("https://music.apple.com/us/browse")
        val scripts = html?.let { Regex("/assets/index(?:-legacy)?[~-][A-Za-z0-9_-]+\\.js").findAll(it).map { m -> m.value }.distinct().toList() }.orEmpty()
        for (path in scripts) {
            val js = get("https://music.apple.com$path") ?: continue
            val jwts = Regex("ey[A-Za-z0-9_-]+\\.ey[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+").findAll(js).map { it.value }.distinct()
                .mapNotNull { jwt -> expiry(jwt)?.let { jwt to it } }.filter { it.second > now }.toList()
            val pick = jwts.firstOrNull { isWebPlayer(it.first) } ?: jwts.firstOrNull() ?: continue
            token = pick.first
            tokenExpires = pick.second
            return pick.first
        }
        tokenRetryAfter = now + 30 * 60_000
        return null
    }

    private fun decode(part: String) = String(Base64.getUrlDecoder().decode(part), Charsets.UTF_8)
    private fun isWebPlayer(jwt: String) = runCatching {
        val p = jwt.split(".")
        decode(p[0]).contains("WebPlayKid") || decode(p[1]).contains("AMPWebPlay")
    }.getOrDefault(false)

    private fun expiry(jwt: String): Long? = runCatching {
        Regex("\"exp\"\\s*:\\s*(\\d+)").find(decode(jwt.split(".")[1]))?.groupValues?.get(1)?.toLong()?.times(1000)
    }.getOrNull()

    private fun motionUrls(video: JsonElement?): Pair<String, String?>? {
        fun link(key: String) = video.f(key)?.let { it.str("video") ?: it.str("videoUrl") ?: it.str("hlsUrl") ?: it.str("url") }?.takeIf { it.isNotBlank() }
        val square = link("motionDetailSquare") ?: link("motionSquareVideo1x1")
        val tall = link("motionDetailTall") ?: link("motionTallVideo3x4")
        val primary = square ?: tall ?: return null
        return primary to listOfNotNull(square, tall).firstOrNull { it != primary }
    }

    private fun isCompilation(name: String) = listOf("playlist", "essentials", "dj mix", "mixed", "apple music", "today's hits", "set list")
        .any { name.lowercase(Locale.ROOT).contains(it) }

    private fun apple(title: String, artist: String, album: String?): CanvasArtwork? {
        val bearer = appleToken() ?: return null
        val headers = mapOf("Authorization" to "Bearer $bearer", "Origin" to "https://music.apple.com", "Referer" to "https://music.apple.com/")
        val term = buildString {
            append(artist).append(' ').append(title)
            if (!album.isNullOrBlank() && !title.contains(album, true)) append(' ').append(album)
        }
        val url = "https://amp-api.music.apple.com/v1/catalog/$storefront/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", term).addQueryParameter("types", "songs").addQueryParameter("limit", "10")
            .addQueryParameter("extend", "editorialVideo").addQueryParameter("include", "albums").build().toString()
        val hits = parse(get(url, headers)).f("results").f("songs").f("data") as? JsonArray ?: return null
        val wantTitle = title.normalizeForMatch()
        val wanted = splitArtists(artist)
        for (hit in hits) {
            val a = hit.f("attributes") ?: continue
            val name = a.str("name") ?: continue
            val hitArtists = splitArtists(a.str("artistName") ?: "")
            val albumName = a.str("albumName") ?: ""
            if (isCompilation(albumName)) continue
            if (wanted.isEmpty() || !wanted.all { w -> hitArtists.any { it == w } }) continue
            val n = name.normalizeForMatch()
            if (n != wantTitle && !n.startsWith("$wantTitle ") && !wantTitle.startsWith("$n ")) continue
            motionUrls(a.f("editorialVideo"))?.let { (p, alt) -> return CanvasArtwork(p, alt, name, a.str("artistName"), albumName) }
            val albumId = (hit.f("relationships").f("albums").f("data") as? JsonArray)?.firstOrNull().str("id")
                ?: a.str("url")?.substringAfter("/album/", "")?.substringBefore("?")?.substringAfterLast("/")?.takeIf { it.all(Char::isDigit) }
                ?: continue
            val albumUrl = "https://amp-api.music.apple.com/v1/catalog/$storefront/albums/$albumId?extend=editorialVideo"
            val albumData = (parse(get(albumUrl, headers)).f("data") as? JsonArray)?.firstOrNull().f("attributes")
            motionUrls(albumData.f("editorialVideo"))?.let { (p, alt) -> return CanvasArtwork(p, alt, name, a.str("artistName"), albumName) }
        }
        return null
    }

    /** Fallback when the catalog token can't be read: the public album page's markup. */
    private fun applePage(artist: String, album: String): CanvasArtwork? {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", "$artist $album").addQueryParameter("media", "music")
            .addQueryParameter("entity", "album").addQueryParameter("limit", "5").build().toString()
        val results = parse(get(url)).f("results") as? JsonArray ?: return null
        val wanted = splitArtists(artist)
        val wantAlbum = album.normalizeForMatch()
        if (wanted.isEmpty() || wantAlbum.isBlank()) return null
        val hit = results.firstOrNull { r ->
            val credited = splitArtists(r.str("artistName") ?: "")
            val name = (r.str("collectionName") ?: "").normalizeForMatch()
            wanted.all { w -> credited.any { it == w } } && (name == wantAlbum || name.startsWith(wantAlbum))
        } ?: return null
        val page = hit.str("collectionViewUrl")?.substringBefore("?") ?: return null
        val html = get(page)?.replace("\\u002F", "/")?.replace("\\/", "/") ?: return null
        fun grab(name: String) = Regex("\"$name\"\\s*:\\s*\\{[^}]*?\"video\"\\s*:\\s*\"(https://[^\"]+?\\.m3u8)\"").find(html)?.groupValues?.get(1)
        val square = grab("motionDetailSquare") ?: grab("motionSquareVideo1x1") ?: return null
        return CanvasArtwork(square, grab("motionDetailTall"), album = hit.str("collectionName"), artist = hit.str("artistName"))
    }

    // ------------------------------------------------------------ Tidal

    private fun tidal(title: String, artist: String, album: String?): CanvasArtwork? {
        val country = Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase(Locale.ROOT) ?: "US"
        val url = "https://api.tidal.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", if (album.isNullOrBlank()) "$artist $title" else "$album $artist $title")
            .addQueryParameter("limit", "10").addQueryParameter("types", "TRACKS").addQueryParameter("countryCode", country)
            .build().toString()
        val items = parse(get(url, mapOf("X-Tidal-Token" to "vNVdglQOjFJJGG2U"))).f("tracks").f("items") as? JsonArray ?: return null
        val wanted = splitArtists(artist)
        for (track in items) {
            val name = track.str("title") ?: continue
            if (name.normalizeForMatch() != title.normalizeForMatch()) continue
            val credited = (track.f("artists") as? JsonArray)?.mapNotNull { it.str("name")?.normalizeForMatch() } ?: emptyList()
            if (wanted.isEmpty() || !wanted.all { w -> credited.any { it == w } }) continue
            val cover = track.f("album").str("videoCover")?.takeIf { it.isNotBlank() } ?: continue
            val parts = cover.split("-")
            if (parts.size != 5) continue
            return CanvasArtwork("https://resources.tidal.com/videos/${parts.joinToString("/")}/1280x1280.mp4", title = name, album = track.f("album").str("title"))
        }
        return null
    }

    // ------------------------------------------------------------ Community index

    @Volatile private var manifest: List<CanvasArtwork> = emptyList()
    @Volatile private var manifestAt = 0L

    @Synchronized
    private fun community(title: String, artist: String, album: String?): CanvasArtwork? {
        val now = System.currentTimeMillis()
        if (manifest.isEmpty() || now - manifestAt > 30 * 60_000) {
            manifestAt = now
            val items = parse(get("https://vivimusicanvas.mkmdevilmi.workers.dev/canvas.json")).f("items") as? JsonArray
            items?.mapNotNull { i ->
                CanvasArtwork(i.str("url") ?: return@mapNotNull null, title = i.str("song") ?: return@mapNotNull null, artist = i.str("artist") ?: return@mapNotNull null, album = i.str("album"))
            }?.takeIf { it.isNotEmpty() }?.let { manifest = it }
        }
        val t = title.normalizeForMatch()
        val a = artist.normalizeForMatch()
        val al = album?.normalizeForMatch()
        if (t.isBlank() || a.isBlank()) return null
        val wanted = splitArtists(artist)
        return manifest.firstOrNull { e ->
            val s = e.title!!.normalizeForMatch()
            val l = e.album?.normalizeForMatch().orEmpty()
            // The same title (give or take a trailing "(Remastered)"-style word run) by the same artist: substrings
            // matched any song whose title held another's ("Love" in "Love Is Lonely").
            s.isNotBlank() && (s == t || t.startsWith("$s ") || s.startsWith("$t ")) &&
                splitArtists(e.artist!!).let { credited -> wanted.isNotEmpty() && wanted.any { w -> credited.any { it == w } } } &&
                (l.isBlank() || al.isNullOrBlank() || l == al)
        }
    }
}
