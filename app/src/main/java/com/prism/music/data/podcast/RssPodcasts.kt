package com.prism.music.data.podcast

import com.prism.music.R
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.playback.unavailableHere
import com.prism.music.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException

/** One episode of a show added by its feed. [audioUrl] went through [RssGuard.link]. */
@Serializable
data class RssEpisode(
    val id: String,
    val title: String,
    val audioUrl: String,
    val description: String? = null,
    val publishedAt: Long? = null,
    val durationSec: Int = 0,
    val image: String? = null,
)

/** A show added by its feed: what the feed said last, and the marks that tell whether it has changed since. */
@Serializable
data class RssShow(
    val id: String,
    val feedUrl: String,
    val title: String,
    val author: String? = null,
    val description: String? = null,
    val image: String? = null,
    /** Newest first. */
    val episodes: List<RssEpisode> = emptyList(),
    val addedAt: Long = 0,
    val refreshedAt: Long = 0,
    val etag: String? = null,
    val lastModified: String? = null,
)

/** The episode as the player and the lists know it: an episode ([Song.isEpisode]) with an id of the feed's ([Song.isRss]). */
fun RssEpisode.song(show: RssShow) = Song(
    id = id,
    title = title.ifBlank { tr(R.string.episode) },
    artists = listOf(ArtistRef(show.title)),
    durationSec = durationSec,
    thumbnail = image ?: show.image,
    isEpisode = true,
    podcastId = show.id,
)

@Serializable
private data class RssFile(val shows: List<RssShow> = emptyList())

/**
 * Podcasts added by their RSS feeds (Podcasts → Add a podcast by RSS). They live on the phone in one JSON file in
 * `files/library/`, which puts them in backups with the rest of the library, written whole on every change through a
 * temporary file. No YouTube and no account: episodes play straight from their own audio links ([audioUrl]).
 *
 * Reading feeds goes through [client], [RssGuard]'s. What's kept of each show is bounded: at most [MAX_EPISODES] episodes,
 * newest first, and [RssFeed]'s limits on their text.
 */
class RssPodcasts(private val dir: File, private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(dir, FILE)
    private val _shows = MutableStateFlow(read())

    /** Newest added first. */
    val shows: StateFlow<List<RssShow>> = _shows

    /** A removed show's links, so the episode playing as it goes keeps playing and seeking. For this run only. */
    private val goneLinks = ConcurrentHashMap<String, String>()

    fun show(id: String): RssShow? = _shows.value.firstOrNull { it.id == id }

    /** Where episode [id]'s audio is: only ever a link from a feed the listener added. Null for anything else. */
    fun audioUrl(id: String): String? =
        show(id.substringBeforeLast(':'))?.episodes?.firstOrNull { it.id == id }?.audioUrl ?: goneLinks[id]

    /** Reads the feed the listener pasted and keeps the show (one already here is brought up to date). Throws [RssException]. */
    suspend fun add(address: String): RssShow = withContext(Dispatchers.IO) {
        val url = RssGuard.feedAddress(address)
        val id = showId(url)
        show(id)?.let { return@withContext refreshShow(it, keep = { false }, conditional = false) ?: it }
        if (_shows.value.size >= MAX_SHOWS) throw RssException(RssProblem.TOO_MANY_SHOWS)
        val feed = fetch(url, null, null, askedInsecure = address.trim().startsWith("http://", ignoreCase = true)) ?: throw RssException(RssProblem.NOT_A_FEED)
        val now = System.currentTimeMillis()
        val show = RssShow(
            id = id, feedUrl = url.toString(), title = feed.parsed.title ?: url.host,
            author = feed.parsed.author, description = feed.parsed.description, image = feed.parsed.image,
            episodes = episodes(id, feed.parsed), addedAt = now, refreshedAt = now, etag = feed.etag, lastModified = feed.lastModified,
        )
        change { list -> listOf(show) + list.filterNot { it.id == id } }
        show
    }

    /**
     * Asks the feed what's new. Episodes that left the feed go, unless [keep] holds on to one (one under way: its place
     * and its link stay good). A feed that's broken today changes nothing. Throws [RssException].
     */
    suspend fun refresh(id: String, keep: (String) -> Boolean = { false }): RssShow? = withContext(Dispatchers.IO) {
        show(id)?.let { refreshShow(it, keep, conditional = true) }
    }

    private fun refreshShow(show: RssShow, keep: (String) -> Boolean, conditional: Boolean): RssShow? {
        val url = RssGuard.feedAddress(show.feedUrl)
        val feed = fetch(url, show.etag.takeIf { conditional }, show.lastModified.takeIf { conditional }, askedInsecure = false)
        val now = System.currentTimeMillis()
        // Not changed since last time.
        val fresh = if (feed == null) show.copy(refreshedAt = now) else {
            val latest = episodes(show.id, feed.parsed)
            val known = latest.mapTo(HashSet()) { it.id }
            val kept = show.episodes.filter { it.id !in known && keep(it.id) }
            show.copy(
                title = feed.parsed.title ?: show.title, author = feed.parsed.author, description = feed.parsed.description, image = feed.parsed.image ?: show.image,
                episodes = (latest + kept).sortedByDescending { it.publishedAt ?: 0L }, refreshedAt = now, etag = feed.etag, lastModified = feed.lastModified,
            )
        }
        change { list -> list.map { if (it.id == show.id) fresh else it } }
        return fresh
    }

    /** The show goes, with its episodes. Their ids, for what else remembers them. */
    suspend fun remove(id: String): List<String> = withContext(Dispatchers.IO) {
        val gone = show(id)?.episodes.orEmpty()
        gone.forEach { goneLinks[it.id] = it.audioUrl }
        change { list -> list.filterNot { it.id == id } }
        gone.map { it.id }
    }

    private class Fetched(val parsed: ParsedFeed, val etag: String?, val lastModified: String?)

    /** Null when the server says nothing changed. */
    private fun fetch(url: HttpUrl, etag: String?, lastModified: String?, askedInsecure: Boolean): Fetched? {
        try {
            client.newCall(RssGuard.feedRequest(url, etag, lastModified)).execute().use { r ->
                if (r.code == 304) return null
                if (!r.isSuccessful) throw RssException(RssProblem.SERVER_ERROR, r.code)
                // Read as it comes (byteStream is after gzip): nothing past RssFeed's limits is ever held.
                val parsed = r.body.byteStream().use { RssFeed.parse(it, r.request.url) }
                return Fetched(parsed, r.header("ETag")?.take(256), r.header("Last-Modified")?.take(64))
            }
        } catch (e: RssException) {
            throw e
        } catch (e: IOException) {
            throw when {
                askedInsecure && (e is SSLException || e is ConnectException || e is InterruptedIOException) -> RssException(RssProblem.NO_SECURE_SERVER, cause = e)
                e is SSLException -> RssException(RssProblem.NO_SECURE_SERVER, cause = e)
                e is InterruptedIOException -> RssException(RssProblem.TIMED_OUT, cause = e)
                e is UnknownHostException || e is ConnectException -> RssException(RssProblem.CANT_REACH, cause = e)
                else -> RssException(RssProblem.CANT_REACH, cause = e)
            }
        }
    }

    private fun episodes(showId: String, feed: ParsedFeed): List<RssEpisode> = feed.items
        .map { i -> RssEpisode(episodeId(showId, i.key), i.title.orEmpty(), i.audioUrl, i.description, i.publishedAt, i.durationSec, i.image) }
        .distinctBy { it.id }
        // Newest first whatever order the feed lists them in; undated ones keep their order, after those.
        .sortedByDescending { it.publishedAt ?: 0L }
        .take(MAX_EPISODES)

    @Synchronized
    private fun change(f: (List<RssShow>) -> List<RssShow>) {
        _shows.update(f)
        write(_shows.value)
    }

    private fun read(): List<RssShow> = runCatching {
        if (!file.isFile) emptyList() else json.decodeFromString(RssFile.serializer(), file.readText()).shows
    }.getOrDefault(emptyList())

    private fun write(shows: List<RssShow>) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$FILE.tmp")
            tmp.writeText(json.encodeToString(RssFile.serializer(), RssFile(shows)))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    companion object {
        /** Under files/: the library's own folder, so [com.prism.music.data.Backup] counts it as Library. */
        const val DIR = "library"
        const val FILE = "rss-shows.json"
        const val MAX_SHOWS = 200
        const val MAX_EPISODES = 500

        private fun hash(s: String, chars: Int) =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(chars)

        /** The same show whichever way its address was written (http or https). */
        fun showId(url: HttpUrl) = Song.RSS_PREFIX + hash(url.host + url.encodedPath + "?" + url.encodedQuery.orEmpty(), 12)

        /** [key] is the feed's own name for the episode, so an episode keeps its id (and your place) when its link changes. */
        fun episodeId(showId: String, key: String) = "$showId:" + hash(key, 16)
    }
}

/**
 * What to tell the listener when an episode won't play, in place of the player's own words; null when those are as good
 * ([com.prism.music.data.friendlyMessage] says "offline" well enough).
 *
 * An episode that came through YouTube and that YouTube calls unavailable: Reddit's report, a show added to a YouTube
 * Music account by RSS. Why YouTube refuses isn't known yet (no account with such a show has been checked), so this
 * says what's certain and offers the way that works.
 */
fun episodeFailureText(song: Song?, error: Throwable): String? {
    if (song?.isEpisode != true) return null
    val chain = generateSequence(error) { it.cause }.take(12).toList()
    if (!song.isRss) return if (unavailableHere(error)) tr(R.string.episode_unavailable_try_rss) else null
    chain.firstNotNullOfOrNull { it as? RssException }?.let { return it.message }
    if (chain.any { it is UnknownHostException }) return null
    return tr(R.string.rss_episode_wont_play)
}
