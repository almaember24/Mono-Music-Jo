package com.prism.music.data.meta

import com.prism.music.data.db.GenreEntity
import com.prism.music.data.db.MetaDao
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.str
import com.prism.music.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import kotlin.math.abs

/**
 * YouTube Music's genre categories (its "Moods & genres" page), kept to the
 * mostly English-language ones; every song gets one main category — there's no
 * "Other" — plus, now and then, a clear second one. Raw genre names from catalogues (Deezer, iTunes) are mapped onto
 * these, most specific first. Regional scenes (K-Pop, Latin, Bollywood…) fold
 * into the nearest of these rather than getting categories of their own.
 */
object Genres {
    /** A genre (Prism's own, kept and matched in English) as the listener reads it, for the screen. */
    fun shown(genre: String): String = when (genre) {
        "Reggae & caribbean" -> com.prism.music.R.string.genre_reggae
        "Christian & gospel" -> com.prism.music.R.string.genre_christian
        "Soundtracks & musicals" -> com.prism.music.R.string.genre_soundtracks
        "Family" -> com.prism.music.R.string.genre_family
        "Hip-hop" -> com.prism.music.R.string.genre_hip_hop
        "Metal" -> com.prism.music.R.string.genre_metal
        "R&B & soul" -> com.prism.music.R.string.genre_rnb
        "Indie & alternative" -> com.prism.music.R.string.genre_indie
        "Dance & electronic" -> com.prism.music.R.string.genre_dance
        "Country & Americana" -> com.prism.music.R.string.genre_country
        "Folk & acoustic" -> com.prism.music.R.string.genre_folk
        "Jazz" -> com.prism.music.R.string.genre_jazz
        "Blues" -> com.prism.music.R.string.genre_blues
        "Classical" -> com.prism.music.R.string.genre_classical
        "Rock" -> com.prism.music.R.string.genre_rock
        "Pop" -> com.prism.music.R.string.genre_pop
        else -> null
    }?.let { com.prism.music.tr(it) } ?: genre

    /** A genre as one word in a mix's name ("Your Tuesday evening hip-hop", "Your indie mix"). */
    fun word(genre: String): String = when (genre) {
        "Reggae & caribbean" -> com.prism.music.R.string.genre_word_reggae
        "Christian & gospel" -> com.prism.music.R.string.genre_word_christian
        "Soundtracks & musicals" -> com.prism.music.R.string.genre_word_soundtracks
        "Family" -> com.prism.music.R.string.genre_word_family
        "Hip-hop" -> com.prism.music.R.string.genre_word_hip_hop
        "Metal" -> com.prism.music.R.string.genre_word_metal
        "R&B & soul" -> com.prism.music.R.string.genre_word_rnb
        "Indie & alternative" -> com.prism.music.R.string.genre_word_indie
        "Dance & electronic" -> com.prism.music.R.string.genre_word_dance
        "Country & Americana" -> com.prism.music.R.string.genre_word_country
        "Folk & acoustic" -> com.prism.music.R.string.genre_word_folk
        "Jazz" -> com.prism.music.R.string.genre_word_jazz
        "Blues" -> com.prism.music.R.string.genre_word_blues
        "Classical" -> com.prism.music.R.string.genre_word_classical
        "Rock" -> com.prism.music.R.string.genre_word_rock
        "Pop" -> com.prism.music.R.string.genre_word_pop
        else -> null
    }?.let { com.prism.music.tr(it) } ?: genre.substringBefore(" & ").lowercase()
    /** Regional / non-English scenes; their YouTube tiles and shelves are left out of Prism. */
    private val regionalWords = listOf(
        "k-pop", "kpop", "korean", "j-pop", "jpop", "j-rock", "japanese", "anime", "city pop", "mandopop", "cantopop", "c-pop",
        "chinese", "mandarin", "cantonese", "opm", "pinoy", "filipino", "tagalog", "bollywood", "indian", "hindi", "punjabi",
        "bhangra", "tamil", "telugu", "desi", "filmi", "bengali", "marathi", "malayalam", "kannada", "gujarati", "haryanvi",
        "bhojpuri", "ghazal", "sufi", "carnatic", "hindustani", "qawwali", "devotional", "arabic", "arab ", "khaleeji",
        "mahraganat", "african", "afro", "amapiano", "highlife", "bongo", "brazil", "sertanejo", "funk carioca", "pagode",
        "forró", "latin", "latino", "español", "espanol", "spanish", "reggaeton", "urbano", "regional mexican", "mexican",
        "mexicano", "corrido", "banda", "mariachi", "norteño", "cumbia", "bachata", "salsa", "merengue", "turkish",
        "french", "german", "italian", "portuguese", "russian", "thai", "indonesian", "vietnamese", "persian", "greek",
        "schlager", "chanson", "fado", "musica", "música",
    )

    /** True for tiles and shelves about non-English regional music ("Bollywood & Indian", "Hip-hop en Español"…). */
    fun isRegional(title: String): Boolean {
        val l = title.lowercase()
        return regionalPatterns.any { it.containsMatchIn(l) }
    }

    // Whole words ("desi" isn't "designer", "musica" isn't "musicals"); a few also start longer words ("afrobeats", "brazilian").
    private val regionalPatterns = regionalWords.map { w ->
        val e = Regex.escape(w.trim())
        if (w.trim() in setOf("afro", "brazil")) Regex("(^|[^\\p{L}])$e") else Regex("(^|[^\\p{L}])$e([^\\p{L}]|$)")
    }

    private val categories: List<Pair<String, List<String>>> = listOf(
        "Reggae & caribbean" to listOf("reggae", "dancehall", "ska", "soca", "calypso", "dub", "caribbean", "zouk"),
        "Christian & gospel" to listOf("christian", "gospel", "worship", "inspirational", "ccm", "praise"),
        "Soundtracks & musicals" to listOf("soundtrack", "score", "film", "movie", "video game", "games", "musical", "broadway", "tv"),
        "Family" to listOf("children", "kids", "lullaby", "family", "nursery"),
        "Hip-hop" to listOf("hip-hop", "hip hop", "hiphop", "rap", "trap", "drill", "grime", "lo-fi", "lofi", "boom bap", "reggaeton", "urbano"),
        "Metal" to listOf("metal", "metalcore", "deathcore", "hardcore", "screamo", "djent", "thrash", "post-hardcore", "grindcore"),
        "R&B & soul" to listOf("r&b", "rnb", "r & b", "soul", "funk", "motown", "quiet storm"),
        "Indie & alternative" to listOf("alternative", "indie", "emo", "shoegaze", "post-punk", "britpop", "grunge", "dream pop", "bedroom"),
        "Dance & electronic" to listOf("electro", "electronic", "dance", "house", "techno", "edm", "trance", "dubstep", "drum", "garage", "synth", "disco", "breakbeat", "ambient", "idm", "hardstyle", "club", "phonk", "amapiano"),
        "Country & Americana" to listOf("country", "americana", "bluegrass", "honky", "outlaw"),
        "Folk & acoustic" to listOf("folk", "singer/songwriter", "singer-songwriter", "songwriter", "acoustic", "celtic", "traditional", "flamenco"),
        "Jazz" to listOf("jazz", "swing", "bebop", "big band", "bossa"),
        "Blues" to listOf("blues"),
        "Classical" to listOf("classical", "orchestra", "opera", "baroque", "piano", "chamber", "new age", "instrumental", "meditation", "choral"),
        "Rock" to listOf("rock", "punk"),
        "Pop" to listOf(
            "pop", "easy listening", "vocal", "adult contemporary", "holiday", "christmas", "comedy", "world", "fitness", "standards", "cabaret", "schlager", "spoken",
            // Regional catalogue genres ("Latin Music", "Asian Music", "Indian Music"…) land here.
            "latin", "asian", "african", "afro", "brazil", "indian", "bollywood", "arab", "korean", "japan", "anime", "chinese", "mpb",
            "samba", "salsa", "cumbia", "bachata", "tropical", "mexican", "mexicano", "corrido", "banda", "mariachi", "norteño", "merengue",
            "highlife", "punjabi", "bhangra", "hindi", "tamil", "telugu", "desi", "filmi", "opm", "pinoy", "filipino", "tagalog", "mandopop", "cantopop",
        ),
    )

    /** Every category, in YouTube Music's order. */
    val all: List<String> = categories.map { it.first }.sorted()

    /** These only count as whole words ("dub" isn't "dubstep", "emo" isn't "emotional"). */
    private val wholeWords = setOf("dub", "ska", "tv", "emo", "ccm", "idm", "opm", "rnb", "soca", "pop", "folk", "funk", "club", "drum", "reggae")

    /** Short keywords must start a word ("rap" isn't "trap", but "afro" is in "afrobeats"). */
    private val patterns = categories.map { (name, keys) ->
        name to keys.map { k ->
            val e = Regex.escape(k)
            when {
                k in wholeWords -> Regex("(^|[^a-z])$e([^a-z]|$)")
                k.length <= 4 -> Regex("(^|[^a-z])$e")
                else -> Regex(e)
            }
        }
    }

    /**
     * Answers by raw name. The same few hundred names ("Pop", "Hip-Hop/Rap"…) come back for every song of every artist
     * read from the iTunes cache at each start, and each regex test makes a matcher that holds native memory until a
     * garbage collection: ~120 tests a name ran into a million matchers and ~200 MB of native memory a minute after
     * start (release check 1.7.6, mid emulator).
     */
    private val known = java.util.concurrent.ConcurrentHashMap<String, String>()
    private const val NONE = ""

    /** The category for a raw genre name, or null if it doesn't say enough. */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        known[raw]?.let { return it.ifEmpty { null } }
        val l = raw.lowercase()
        // Electronic, though they name hip-hop ("Chill Out/Trip-Hop/Lounge").
        val found = if ("trip-hop" in l || "trip hop" in l || "chill out" in l || "chillout" in l || "lounge" in l) "Dance & electronic"
        else patterns.firstOrNull { (_, keys) -> keys.any { it.containsMatchIn(l) } }?.first
        if (known.size < 4_000) known[raw] = found ?: NONE
        return found
    }
}

/**
 * Genre detection (Deezer, then iTunes) and caching. YouTube Music doesn't
 * expose genres, so songs are classified by their artist (one lookup covers
 * every song by them), falling back to the artist's related artists, and
 * finally to whatever the rest of the collection is mostly made of.
 */
class MetaRepository(private val http: OkHttpClient, private val dao: MetaDao) {
    /** Diagnostics hook (tests print it). */
    var trace: ((String) -> Unit)? = null
    /** Deezer allows ~50 requests per 5 s; iTunes only ~20 a minute, so it's a last resort. */
    private val deezer = Semaphore(6)
    private val itunesLock = Mutex()
    private var lastItunes = 0L

    private fun key(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()

    // v2 keys: the old cache could hold one song's odd genre saved as its artist's, so it isn't reused.
    private fun artistKey(name: String) = "artist2:${key(name)}"

    private fun cleanTitle(t: String) = t.replace(Regex("\\s*[(\\[].*?[)\\]]"), "").substringBefore(" - ").trim()

    private fun fetchJson(url: okhttp3.HttpUrl): JsonElement? =
        http.newCall(Request.Builder().url(url).header("User-Agent", "PrismMusic/1.0").build()).execute().use { r ->
            if (!r.isSuccessful) null else InnerTube.json.parseToJsonElement(r.body.string())
        }

    private val deezerSlots = Mutex()
    private val deezerSent = ArrayDeque<Long>()

    /**
     * Requests to api.deezer.com are paced to 40 per 5 seconds (its limit is 50) and
     * retried briefly if it still answers "quota exceeded"; everything else goes straight out.
     */
    private suspend fun getJson(url: okhttp3.HttpUrl): JsonElement? {
        if (url.host != "api.deezer.com") return fetchJson(url)
        repeat(4) { attempt ->
            deezerSlots.withLock {
                while (true) {
                    val now = System.currentTimeMillis()
                    while (deezerSent.isNotEmpty() && now - deezerSent.first() > 5_000) deezerSent.removeFirst()
                    if (deezerSent.size < 40) { deezerSent.addLast(now); break }
                    delay(5_000 - (now - deezerSent.first()) + 20)
                }
            }
            val res = fetchJson(url)
            val quota = (res.field("error") as? JsonObject)?.let { it.str("code") == "4" } == true
            if (!quota) return res
            delay(1_200L * (attempt + 1))
        }
        return null
    }

    private fun JsonElement?.field(k: String): JsonElement? = (this as? JsonObject)?.get(k)

    private suspend fun deezerAlbumGenre(albumId: String): String? {
        val cacheKey = "dzalbum:$albumId"
        dao.genre(cacheKey)?.let { return it.genre }
        val album = getJson("https://api.deezer.com/album/$albumId".toHttpUrl())
        val g = (album.field("genres").field("data") as? JsonArray)?.mapNotNull { it.str("name") }?.joinToString(" / ")
        dao.putGenre(GenreEntity(cacheKey, g, System.currentTimeMillis()))
        return g
    }

    /** Raw genre for a track (closest duration match on Deezer). */
    private suspend fun deezerTrackGenre(song: Song): String? = deezer.withPermit {
        val q = "artist:\"${song.primaryArtist}\" track:\"${cleanTitle(song.title)}\""
        val search = getJson("https://api.deezer.com/search".toHttpUrl().newBuilder().addQueryParameter("q", q).addQueryParameter("limit", "5").build())
        // Its strict search finds nothing for some titles (quotes in them: "TEXAS HOLD 'EM"); a plain one, by this artist.
        val data = (search.field("data") as? JsonArray)?.toList()?.ifEmpty { null }
            ?: (getJson("https://api.deezer.com/search".toHttpUrl().newBuilder().addQueryParameter("q", "${song.primaryArtist} ${cleanTitle(song.title)}").addQueryParameter("limit", "8").build())
                .field("data") as? JsonArray)?.toList()?.filter { key(it.field("artist").str("name") ?: "") == key(song.primaryArtist) }
            ?: return@withPermit null
        // The same song (its title starts the same), not just anything the artist's name finds; closest in length when known.
        val title = key(cleanTitle(song.title))
        val same = data.filter { key(cleanTitle(it.str("title") ?: "")).let { t -> t == title || t.startsWith("$title ") || title.startsWith("$t ") } }.ifEmpty { data }
        val best = (if (song.durationSec > 0) same.minByOrNull { abs((it.str("duration")?.toIntOrNull() ?: 0) - song.durationSec) } else same.firstOrNull())
            ?: return@withPermit null
        best.field("album").str("id")?.let { deezerAlbumGenre(it) }
    }

    /** Deezer's fixed top-level genre ids. */
    private val deezerGenres = mapOf(
        132 to "Pop", 116 to "Rap/Hip Hop", 152 to "Rock", 113 to "Dance", 165 to "R&B", 85 to "Alternative", 106 to "Electro",
        466 to "Folk", 144 to "Reggae", 129 to "Jazz", 84 to "Country", 67 to "Salsa", 65 to "Traditional Mexicano",
        98 to "Classical", 173 to "Films/Games", 464 to "Metal", 169 to "Soul & Funk", 2 to "African Music", 16 to "Asian Music",
        153 to "Blues", 75 to "Brazilian Music", 81 to "Indian Music", 95 to "Kids", 197 to "Latin Music",
    )

    /**
     * Raw genres of an artist on Deezer, most common first: a vote across their
     * releases (albums count double, compilations not at all), in two requests.
     */
    private suspend fun deezerArtistGenre(name: String): String? =
        deezerArtistVotes(name)?.entries?.sortedByDescending { it.value }?.joinToString(" / ") { it.key }?.ifBlank { null }

    /** Deezer release votes per artist this session, so the main genre and the extras share one lookup. */
    private val deezerVoteCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, Int>>()

    /** Raw Deezer genre -> weighted release count for an artist; null if Deezer couldn't be asked. */
    private suspend fun deezerArtistVotes(name: String): Map<String, Int>? {
        deezerVoteCache[key(name)]?.let { return it }
        return deezer.withPermit {
            val search = getJson("https://api.deezer.com/search/artist".toHttpUrl().newBuilder().addQueryParameter("q", name).addQueryParameter("limit", "5").build())
            val found = search.field("data") as? JsonArray ?: return@withPermit null
            // Several artists can share a name; the one with the most fans is nearly always who's meant.
            val artist = found.filter { key(it.str("name") ?: "") == key(name) }.maxByOrNull { it.str("nb_fan")?.toLongOrNull() ?: 0L }
                ?: found.firstOrNull() ?: return@withPermit emptyMap()
            val id = artist.str("id") ?: return@withPermit emptyMap()
            val albums = getJson("https://api.deezer.com/artist/$id/albums".toHttpUrl().newBuilder().addQueryParameter("limit", "40").build())
                .field("data") as? JsonArray ?: return@withPermit null
            val votes = HashMap<String, Int>()
            albums.forEach { a ->
                val genre = a.str("genre_id")?.toIntOrNull()?.let { deezerGenres[it] } ?: return@forEach
                val weight = when (a.str("record_type")) { "album" -> 2; "compile" -> 0; else -> 1 }
                if (weight > 0) votes.merge(genre, weight, Int::plus)
            }
            votes
        }?.also { deezerVoteCache[key(name)] = it }
    }

    // ---------------------------------------------------------------- Extra genres

    private val lastFmKey = com.prism.music.BuildConfig.LASTFM_API_KEY
    private val lastFmLock = Mutex()
    private var lastLastFm = 0L

    private fun extrasKey(name: String) = "artistx1:${key(name)}"

    /**
     * Last.fm's tags for an artist as categories, scored 0–100 (its top tag is 100). Null without
     * an API key or if Last.fm couldn't be reached. Requests are spaced to stay well inside its limits.
     */
    private suspend fun lastFmArtistTags(name: String): Map<String, Int>? {
        if (lastFmKey.isBlank()) return null
        val res = lastFmLock.withLock {
            val wait = 260 - (System.currentTimeMillis() - lastLastFm)
            if (wait > 0) delay(wait)
            lastLastFm = System.currentTimeMillis()
            getJson(
                "https://ws.audioscrobbler.com/2.0/".toHttpUrl().newBuilder()
                    .addQueryParameter("method", "artist.gettoptags").addQueryParameter("artist", name)
                    .addQueryParameter("autocorrect", "1").addQueryParameter("api_key", lastFmKey).addQueryParameter("format", "json").build()
            )
        } ?: return null
        val tags = res.field("toptags").field("tag") as? JsonArray ?: return if (res.field("error") != null) emptyMap() else null
        val score = HashMap<String, Int>()
        tags.forEach { t ->
            val tag = t.str("name")?.lowercase() ?: return@forEach
            // Descriptions of the singer, not the music ("female vocalists" would otherwise read as Pop).
            if ("vocal" in tag || "seen live" in tag || "favorite" in tag || "favourite" in tag) return@forEach
            val category = Genres.normalize(tag) ?: return@forEach
            score.merge(category, t.str("count")?.toIntOrNull() ?: 0, ::maxOf)
        }
        return score
    }

    /**
     * Genres an artist clearly belongs to besides [primary], so their songs can sit in more than one.
     * Deliberately strict: with Last.fm, a category needs a tag at least half as strong as the
     * artist's top tag, and the main genre has to show up there too (so the two sources agree on who
     * this is); otherwise Deezer's releases decide, where it needs half the main genre's weight and a
     * few releases of its own. At most two extras.
     */
    suspend fun artistExtraGenres(name: String, primary: String): List<String> = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext emptyList()
        val k = extrasKey(name)
        dao.genre(k)?.let { cached -> return@withContext cached.genre.orEmpty().split("|").filter { it.isNotBlank() && it != primary } }
        val fromLastFm = runCatching { lastFmArtistTags(name) }.getOrNull()?.let { tags ->
            if ((tags[primary] ?: 0) < 25) emptyList()
            else tags.filter { (g, n) -> g != primary && n >= 50 }.entries.sortedByDescending { it.value }.take(2).map { it.key }
        }
        val extras = fromLastFm ?: runCatching { deezerArtistVotes(name) }.getOrNull()?.let { votes ->
            val byCategory = HashMap<String, Int>()
            votes.forEach { (raw, n) -> Genres.normalize(raw)?.let { byCategory.merge(it, n, Int::plus) } }
            val main = byCategory[primary] ?: 0
            // Deezer doesn't see the main genre at all: it's describing someone else, so it gets no say.
            if (main == 0) emptyList()
            else byCategory.filter { (g, n) -> g != primary && n >= 3 && n * 2 >= main }.entries.sortedByDescending { it.value }.take(1).map { it.key }
        } ?: return@withContext emptyList() // Nothing could be asked; try again next time.
        trace?.invoke("extras $name ($primary): lastfm=$fromLastFm -> $extras")
        dao.putGenre(GenreEntity(k, extras.joinToString("|"), System.currentTimeMillis()))
        extras
    }

    /**
     * Further genres for songs whose main genre is in [primary] (song id -> genre), by artist,
     * reported in batches as they're found. Songs with no extras aren't reported.
     */
    suspend fun resolveExtraGenres(
        songs: List<Song>,
        primary: Map<String, String>,
        onBatch: (Map<String, List<String>>) -> Unit,
    ) = onIo(onBatch) { emit ->
        val byArtist = songs.filter { it.id in primary }.groupBy { it.primaryArtist to primary.getValue(it.id) }
        val results = Channel<Pair<List<String>, List<Song>>>(Channel.UNLIMITED)
        byArtist.forEach { (artist, list) -> launch { results.send(artistExtraGenres(artist.first, artist.second) to list) } }
        val buffer = HashMap<String, List<String>>()
        var lastEmit = System.currentTimeMillis()
        repeat(byArtist.size) { n ->
            val (extras, list) = results.receive()
            if (extras.isNotEmpty()) list.forEach { buffer[it.id] = extras }
            if (buffer.isNotEmpty() && (n == byArtist.size - 1 || buffer.size >= 25 || System.currentTimeMillis() - lastEmit > 600)) {
                emit(buffer.toMap())
                buffer.clear()
                lastEmit = System.currentTimeMillis()
            }
        }
        results.close()
    }

    /** Until when iTunes said "too many" (403/429): nothing more is asked of it before then. */
    @Volatile private var itunesResting = 0L

    private suspend fun itunes(url: okhttp3.HttpUrl): JsonElement? = itunesLock.withLock {
        if (System.currentTimeMillis() < itunesResting) return@withLock null
        val wait = 3_100 - (System.currentTimeMillis() - lastItunes)
        if (wait > 0) delay(wait)
        lastItunes = System.currentTimeMillis()
        http.newCall(Request.Builder().url(url).header("User-Agent", "PrismMusic/1.0").build()).execute().use { r ->
            if (r.code == 403 || r.code == 429) { itunesResting = System.currentTimeMillis() + 90_000; trace?.invoke("itunes rate-limited"); null }
            else if (!r.isSuccessful) null
            else InnerTube.json.parseToJsonElement(r.body.string())
        }
    }

    // ---------------------------------------------------------------- Song genres from iTunes

    private fun itunesSongsKey(name: String) = "it1:${key(name)}"

    /**
     * Up to 200 of an artist's songs on iTunes with Apple's genre for each (cleaned title key → raw genre), in one
     * request, kept for good. Apple files each release in a genre of its own (Post Malone's country album as Country,
     * a musical's songs as Musicals), so it's the closest Prism gets to a genre per song. Null if iTunes couldn't be
     * asked (try again later); empty if it doesn't have the artist.
     */
    private suspend fun itunesArtistSongs(name: String): Map<String, String>? {
        val k = itunesSongsKey(name)
        dao.genre(k)?.let { return decodeSongs(it.genre) }
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", name).addQueryParameter("media", "music").addQueryParameter("entity", "song")
            .addQueryParameter("attribute", "artistTerm").addQueryParameter("limit", "200").build()
        val results = runCatching { itunes(url) }.getOrNull()?.field("results") as? JsonArray ?: return null
        val wanted = key(name)
        // A song can be on several releases (a hit on its album, a soundtrack and a best-of): every genre it's filed under.
        val songs = LinkedHashMap<String, MutableList<String>>()
        results.forEach { r ->
            val artist = key(r.str("artistName") ?: return@forEach)
            // The artist themself (or with others: "Post Malone & Morgan Wallen"), not a namesake or a tribute act.
            if (artist != wanted && !artist.startsWith("$wanted ") && !artist.contains(" $wanted")) return@forEach
            val title = key(cleanTitle(r.str("trackName") ?: return@forEach))
            val genre = r.str("primaryGenreName")?.takeIf { '\t' !in it && '|' !in it } ?: return@forEach
            if (title.isNotBlank()) songs.getOrPut(title) { ArrayList() }.let { if (genre !in it) it += genre }
        }
        dao.putGenre(GenreEntity(k, songs.entries.joinToString("\n") { "${it.key}\t${it.value.joinToString("|")}" }, System.currentTimeMillis()))
        trace?.invoke("itunes songs $name: ${songs.size}")
        return songs.mapValues { it.value.joinToString("|") }
    }

    private fun decodeSongs(s: String?): Map<String, String> =
        s.orEmpty().lineSequence().mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()

    /** The artist's genre by Apple's count over their songs (each song's first genre, most common). */
    private fun majority(songs: Map<String, String>): String? =
        songs.values.mapNotNull { Genres.normalize(it.substringBefore('|')) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    /** The categories Apple files [song] under (from its artist's songs on iTunes); empty if it isn't among them. */
    private fun ownCategories(song: Song, songs: Map<String, String>): List<String> {
        val t = key(cleanTitle(song.title))
        if (t.isBlank()) return emptyList()
        val raw = songs[t] ?: songs.entries.firstOrNull { (k, _) -> t.length >= 4 && (k.startsWith("$t ") || t.startsWith("$k ")) }?.value
        return raw?.split('|')?.mapNotNull { Genres.normalize(it) }?.distinct().orEmpty()
    }

    /**
     * [song]'s category from its artist's iTunes songs: its own (the artist's usual genre when it's filed under that
     * among others, so "Lose Yourself" is Hip-hop rather than its soundtrack's genre), else the artist's usual one.
     */
    private fun fromItunes(song: Song, songs: Map<String, String>): String? {
        if (songs.isEmpty()) return null
        val major = majority(songs)
        val own = ownCategories(song, songs)
        return if (own.isEmpty()) major else if (major in own) major else own.first()
    }

    /**
     * A song iTunes didn't list for its artist: its own release on Deezer can still say it's something else (a country
     * single by a rapper). Deezer files broadly, so its Pop and Rock don't overrule the artist's genre; anything more
     * specific does. Null if Deezer couldn't be asked.
     */
    private suspend fun songOffList(song: Song, major: String?): String? {
        val raw = runCatching { deezerTrackGenre(song) }.getOrNull() ?: return null
        val found = raw.split(" / ").mapNotNull { Genres.normalize(it) }.distinct()
        val first = found.firstOrNull() ?: return major
        return when {
            major == null || first == major -> first
            first == "Pop" || first == "Rock" -> major
            else -> first
        }
    }

    /** [song]'s category given its artist's iTunes songs (asking Deezer about it if it isn't among them). */
    private suspend fun songGenre(song: Song, songs: Map<String, String>): String? {
        val own = ownCategories(song, songs)
        val major = majority(songs)
        if (own.isNotEmpty()) return if (major in own) major else own.first()
        return songOffList(song, major) ?: major
    }

    // Artists waiting for their iTunes songs (asked one at a time, as iTunes allows about 20 requests a minute).
    private val refineQueue = Channel<Pair<String, List<Song>>>(Channel.UNLIMITED)
    private val queued = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val _refined = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 64)
    /** An artist whose songs' genres were just made exact (lists showing them can read [cachedGenres] again). */
    val refined: kotlinx.coroutines.flow.SharedFlow<String> = _refined

    /** Starts the background refiner (the app's scope; tests ask inline instead). */
    fun startRefining(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            for ((name, mine) in refineQueue) {
                val songs = runCatching { itunesArtistSongs(name) }.getOrNull()
                queued.remove(key(name))
                if (songs == null) { delay(30_000); continue } // offline or resting: it's asked again when next needed
                majority(songs)?.let { dao.putGenre(GenreEntity(exactArtistKey(name), it, System.currentTimeMillis())) }
                // The listener's own songs by them, each settled (the ones iTunes didn't list are asked of Deezer).
                if (songs.isNotEmpty()) for (s in mine) {
                    val k = "song3:${s.id}"
                    if (dao.genre(k) != null) continue
                    runCatching { songGenre(s, songs) }.getOrNull()?.let { dao.putGenre(GenreEntity(k, it, System.currentTimeMillis())) }
                }
                _refined.tryEmit(name)
            }
        }
    }

    /**
     * Has [name]'s songs looked up on iTunes in the background (and [mine], the listener's songs by them, settled one
     * by one), unless that's done or under way.
     */
    fun refineLater(name: String, mine: List<Song> = emptyList()) {
        if (name.isBlank()) return
        if (queued.add(key(name))) refineQueue.trySend(name to mine)
    }

    private suspend fun itunesArtistGenre(name: String): String? {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", name).addQueryParameter("media", "music").addQueryParameter("entity", "musicArtist").addQueryParameter("limit", "3").build()
        val results = itunes(url).field("results") as? JsonArray ?: return null
        return (results.firstOrNull { key(it.str("artistName") ?: "") == key(name) } ?: results.firstOrNull()).str("primaryGenreName")
    }

    private fun categoryOf(raw: String?): String? = raw?.split(" / ")?.firstNotNullOfOrNull { Genres.normalize(it) } ?: Genres.normalize(raw)

    /** The artist's genre from Apple's count over their songs ([itunesArtistSongs]): the one Prism trusts most. */
    private fun exactArtistKey(name: String) = "artist3:${key(name)}"

    /**
     * An artist's category: Apple's (by their songs on iTunes) once known; until then Deezer's, quickly, with the
     * iTunes lookup queued in the background ([refineLater]). Cached.
     */
    suspend fun artistGenre(name: String, relatedNames: (suspend () -> List<String>)? = null, force: Boolean = false): String? = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext null
        dao.genre(exactArtistKey(name))?.genre?.let { return@withContext it }
        dao.genre(itunesSongsKey(name))?.let { cached ->
            majority(decodeSongs(cached.genre))?.let { g ->
                dao.putGenre(GenreEntity(exactArtistKey(name), g, System.currentTimeMillis()))
                return@withContext g
            }
        }
        refineLater(name)
        val k = artistKey(name)
        if (!force) dao.genre(k)?.let { cached -> categoryOf(cached.genre)?.let { return@withContext it } }
        val t0 = System.currentTimeMillis()
        val dz = runCatching { deezerArtistGenre(name) }.onFailure { trace?.invoke("deezer error $name: $it") }.getOrNull()
        var g = categoryOf(dz)
        val it = if (g == null) runCatching { itunesArtistGenre(name) }.getOrNull() else null
        if (g == null) g = categoryOf(it)
        trace?.invoke("artist $name: deezer=$dz itunes=$it -> $g (${System.currentTimeMillis() - t0} ms)")
        if (g == null && relatedNames != null) {
            // Not catalogued anywhere: go by the company it keeps (YouTube Music's related artists).
            val votes = runCatching { relatedNames() }.getOrDefault(emptyList()).take(5)
                .mapNotNull { other -> dao.genre(artistKey(other))?.genre?.let(::categoryOf) ?: runCatching { categoryOf(deezerArtistGenre(other)) }.getOrNull() }
            g = votes.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        }
        if (g != null) dao.putGenre(GenreEntity(k, g, System.currentTimeMillis()))
        g
    }

    /**
     * One song's category: its own on iTunes (Apple files each release, so an artist's country album is Country), else
     * its artist's usual one there; until the artist's iTunes songs are in, the artist's genre from Deezer stands in
     * (and they're queued). [wait]: ask iTunes now rather than in the background (slow: it's paced).
     */
    suspend fun genreFor(song: Song, wait: Boolean = false): String? = withContext(Dispatchers.IO) {
        val k = "song3:${song.id}"
        dao.genre(k)?.let { cached -> categoryOf(cached.genre)?.let { return@withContext it } }
        // A song without an artist (an untagged file) isn't guessed from its title alone: a search for "Track 01" or
        // a test tone finds some other song's genre.
        if (song.primaryArtist.isBlank()) return@withContext null
        val songs = if (wait) runCatching { itunesArtistSongs(song.primaryArtist) }.getOrNull()
        else dao.genre(itunesSongsKey(song.primaryArtist))?.let { decodeSongs(it.genre) }
        songs?.takeIf { it.isNotEmpty() }?.let { songGenre(song, it) }?.let { g ->
            dao.putGenre(GenreEntity(k, g, System.currentTimeMillis()))
            return@withContext g
        }
        // Not on iTunes yet (or not at all): Deezer, by the artist, then by the song itself.
        artistGenre(song.primaryArtist) ?: runCatching { categoryOf(deezerTrackGenre(song)) }.getOrNull()
    }

    /**
     * Looks every genre up again (after detection improves). The artists of [songs] — the listener's
     * own music — are redone in place first, so Home and Replay never go blank; everything older is
     * then forgotten and found again when it's next needed. False if it couldn't get through
     * (e.g. offline), in which case nothing is forgotten.
     */
    suspend fun refreshAll(songs: List<Song>): Boolean = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val artists = songs.map { it.primaryArtist }.filter { it.isNotBlank() }.distinct()
        val found = java.util.concurrent.atomic.AtomicInteger()
        coroutineScope {
            artists.forEach { a -> launch { if (runCatching { artistGenre(a, force = true) }.getOrNull() != null) found.incrementAndGet() } }
        }
        trace?.invoke("refreshed ${found.get()} / ${artists.size} artists")
        if (artists.isNotEmpty() && found.get() < artists.size / 2) return@withContext false
        // Older guesses (Deezer's by song, the first artist cache) go; iTunes' answers are kept.
        dao.clearGenresBefore(started)
        true
    }

    /**
     * Categories already known, without any network: a song's own (from iTunes), else its artist's songs on iTunes,
     * else the artist's genre (Apple's, then Deezer's).
     */
    suspend fun cachedGenres(songs: List<Song>): Map<String, String> = withContext(Dispatchers.IO) {
        val bySong = songs.map { "song3:${it.id}" }.chunked(500).flatMap { dao.genres(it) }
            .associate { it.key.removePrefix("song3:") to categoryOf(it.genre) }
        val artists = songs.map { it.primaryArtist }.filter { it.isNotBlank() }.distinct()
        val itunesSongs = artists.map { itunesSongsKey(it) }.chunked(500).flatMap { dao.genres(it) }.associate { it.key to decodeSongs(it.genre) }
        val keys = artists.flatMap { listOf(exactArtistKey(it), artistKey(it)) }
        val byArtist = keys.chunked(500).flatMap { dao.genres(it) }.associate { it.key to categoryOf(it.genre) }
        songs.mapNotNull { s ->
            if (s.primaryArtist.isBlank()) return@mapNotNull bySong[s.id]?.let { s.id to it }
            val g = bySong[s.id]
                ?: itunesSongs[itunesSongsKey(s.primaryArtist)]?.let { fromItunes(s, it) }
                ?: byArtist[exactArtistKey(s.primaryArtist)] ?: byArtist[artistKey(s.primaryArtist)]
            g?.let { s.id to it }
        }.toMap()
    }

    /**
     * Sorts every song into a category, reporting results in batches. Artists
     * are looked up once each (several at a time); anything still unknown at
     * the end takes the collection's most common category.
     */
    suspend fun resolveGenres(
        songs: List<Song>,
        related: (suspend (Song) -> List<String>)? = null,
        refineInline: Boolean = true,
        onBatch: (Map<String, String>) -> Unit,
    ) = onIo(onBatch) { emit ->
        val known = cachedGenres(songs).toMutableMap()
        if (known.isNotEmpty()) emit(known.toMap())
        val pending = songs.filter { it.id !in known }.groupBy { it.primaryArtist }
        // Every artist is looked up concurrently (the rate limiters pace the requests); results
        // stream back and are handed over in small batches as they arrive.
        val results = Channel<Pair<String?, List<Song>>>(Channel.UNLIMITED)
        pending.forEach { (artist, list) ->
            launch { results.send(artistGenre(artist, related?.let { r -> { r(list.first()) } }) to list) }
        }
        val buffer = HashMap<String, String>()
        var lastEmit = System.currentTimeMillis()
        repeat(pending.size) { n ->
            val (g, list) = results.receive()
            if (g != null) list.forEach { buffer[it.id] = g }
            val last = n == pending.size - 1
            if (buffer.isNotEmpty() && (last || buffer.size >= 25 || System.currentTimeMillis() - lastEmit > 600)) {
                known += buffer
                emit(buffer.toMap())
                buffer.clear()
                lastEmit = System.currentTimeMillis()
            }
        }
        results.close()
        val missing = songs.filter { it.id !in known }
        if (missing.isNotEmpty()) {
            val fallback = known.values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "Pop"
            emit(missing.associate { it.id to fallback })
        }
        if (!refineInline) return@onIo
        // Then each song's own genre from iTunes, artist by artist (most songs first), as iTunes allows (~20 a minute).
        val byArtist = songs.filter { it.primaryArtist.isNotBlank() }.groupBy { it.primaryArtist }.entries.sortedByDescending { it.value.size }
        for ((artist, list) in byArtist) {
            val cached = dao.genre(itunesSongsKey(artist)) != null
            val its = runCatching { itunesArtistSongs(artist) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: continue
            val changed = list.mapNotNull { s ->
                val songKey = "song3:${s.id}"
                val g = dao.genre(songKey)?.genre ?: songGenre(s, its)?.also { dao.putGenre(GenreEntity(songKey, it, System.currentTimeMillis())) }
                g?.takeIf { it != known[s.id] }?.let { s.id to it }
            }.toMap()
            if (changed.isNotEmpty()) { known += changed; emit(changed) }
            if (!cached) majority(its)?.let { dao.putGenre(GenreEntity(exactArtistKey(artist), it, System.currentTimeMillis())) }
        }
    }

    /**
     * #228 (1.8.3): a screen calls the genre lookups from its own (main) thread, and some of them wait on Deezer and
     * iTunes over the network, which stalled opening and closing mixes for half a second at a time. All of the work
     * runs on the IO threads; each batch is handed back on the caller's thread, where the screen's state lives.
     */
    private suspend fun <T> onIo(onBatch: (T) -> Unit, block: suspend CoroutineScope.(emit: suspend (T) -> Unit) -> Unit) {
        val caller = currentCoroutineContext()[ContinuationInterceptor]
        withContext(Dispatchers.IO) {
            block { b -> if (caller == null) onBatch(b) else withContext(caller) { onBatch(b) } }
        }
    }
}
