package com.prism.music.data.canvas

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** The largest clip rendition played and saved (either side, in px). */
const val CANVAS_MAX_SIDE = 1280

/**
 * The largest rendition for this phone: no wider than its screen (1.7.6). A 720 px phone took the 1080 px clip, whose
 * frames cost it more memory and decoding than it can show. The player and the saved copies use the same size.
 */
fun canvasMaxSide(context: Context): Int {
    val m = context.resources.displayMetrics
    return minOf(m.widthPixels, m.heightPixels).coerceIn(480, CANVAS_MAX_SIDE)
}

/**
 * Where animated-cover clips live on the phone: recently played ones (a 400 MB cache) and saved ones
 * (kept for good — from the player, a whole album at a time, or with a download). Saved files are
 * keyed by "clipUrl|part", so a clip shared by an album's songs is stored once.
 */
@OptIn(UnstableApi::class)
class CanvasStore(
    private val context: Context,
    private val http: OkHttpClient,
    private val repo: CanvasRepository,
    private val ytm: YouTubeMusic,
    databaseProvider: DatabaseProvider,
    private val scope: CoroutineScope,
) {
    private val recentCache = SimpleCache(File(context.cacheDir, "canvas_cache"), LeastRecentlyUsedCacheEvictor(400L * 1024 * 1024), databaseProvider)
    private val savedCache = SimpleCache(File(context.filesDir, "canvas_saved"), NoOpCacheEvictor(), databaseProvider)
    /** A few clips at a time, so saving an album doesn't swamp the connection. */
    private val gate = Semaphore(2)
    /** Saves look their clips up one at a time; the player's own lookup never waits for this. */
    private val lookups = Semaphore(1)
    private val busy = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun recent() = CacheDataSource.Factory()
        .setCache(recentCache)
        .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(http))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private fun savedKeys(clip: () -> String) = CacheKeyFactory { spec -> "${clip()}|${spec.key ?: spec.uri}" }

    /**
     * For the player: saved clips → recent clips → network. While [keep] says the song's cover is
     * saved, whatever plays is written to the saved store too (filling any gaps a download left).
     */
    fun dataSourceFactory(clip: () -> String, keep: () -> Boolean): DataSource.Factory {
        val recent = recent()
        val keys = savedKeys(clip)
        return DataSource.Factory {
            CacheDataSource.Factory()
                .setCache(savedCache)
                .setCacheKeyFactory(keys)
                .setUpstreamDataSourceFactory(recent)
                .apply { if (!keep()) setCacheWriteDataSinkFactory(null) }
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource()
        }
    }

    /** Saves the cover from the player: this song's, and every other song's on the album. */
    fun save(song: Song) {
        repo.setSaved(song, true)
        scope.launch(Dispatchers.IO) {
            download(song)
            // The rest of the album, whose songs often share the clip (stored once) or have their own.
            val albumId = song.album?.id ?: return@launch
            val tracks = runCatching { ytm.album(albumId).songs }.getOrNull().orEmpty()
            tracks.filter { it.id != song.id }.forEach { download(it) }
        }
    }

    fun unsave(song: Song) {
        repo.setSaved(song, false)
        prune()
    }

    /** A downloaded song keeps its animated cover too. */
    fun saveWithDownload(song: Song) {
        repo.setSavedWithDownload(song.id, true)
        scope.launch(Dispatchers.IO) { download(song) }
    }

    fun forgetDownloads(ids: Collection<String>) {
        ids.forEach { repo.setSavedWithDownload(it, false) }
        prune()
    }

    /** Fetches [song]'s clip into the saved store, if it has one and it isn't there yet. */
    private suspend fun download(song: Song) {
        val art = lookups.withPermit { runCatching { repo.canvasFor(song) }.getOrNull() } ?: return
        if (!busy.add(art.url)) return
        try {
            gate.withPermit {
                if (!fetch(art.url)) art.fallbackUrl?.let { fetch(it) }
            }
        } finally { busy.remove(art.url) }
    }

    /** Downloads one clip: the whole file, or for HLS the one rendition the player picks. True when it's all there. */
    private fun fetch(url: String): Boolean = runCatching {
        val factory = CacheDataSource.Factory()
            .setCache(savedCache)
            .setCacheKeyFactory(savedKeys { url })
            .setUpstreamDataSourceFactory(recent())
        if (url.substringBefore('?').endsWith(".m3u8")) {
            val item = MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8)
            variantFor(url)?.let { item.setStreamKeys(listOf(StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_VARIANT, it))) }
            HlsDownloader(item.build(), factory).download(null)
        } else {
            ProgressiveDownloader(MediaItem.fromUri(url), factory).download(null)
        }
        true
    }.getOrDefault(false)

    /**
     * The rendition the player settles on: H.264 (preferred there too), the highest bitrate that
     * fits [canvasMaxSide]. Null for a playlist with a single rendition.
     */
    private fun variantFor(url: String): Int? {
        val playlist = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            HlsPlaylistParser().parse(Uri.parse(url), r.body.byteStream())
        } as? HlsMultivariantPlaylist ?: return null
        val side = canvasMaxSide(context)
        val fits = playlist.variants.withIndex().filter { (_, v) ->
            v.format.width <= side && v.format.height <= side
        }.ifEmpty { listOfNotNull(playlist.variants.withIndex().minByOrNull { it.value.format.width }) }
        val avc = fits.filter { it.value.format.codecs?.contains("avc1") == true }.ifEmpty { fits }
        return avc.maxByOrNull { it.value.format.bitrate }?.index
    }

    /** Removes saved clip files nothing saved uses any more. */
    private fun prune() {
        scope.launch(Dispatchers.IO) {
            val keep = repo.savedClipUrls()
            runCatching { savedCache.keys.filter { it.substringBefore('|') !in keep }.forEach { savedCache.removeResource(it) } }
        }
    }

    val savedBytes: Long get() = runCatching { savedCache.cacheSpace }.getOrDefault(0)
    val recentBytes: Long get() = runCatching { recentCache.cacheSpace }.getOrDefault(0)

    fun clearSaved() {
        repo.clearSaved()
        runCatching { savedCache.keys.toList().forEach { savedCache.removeResource(it) } }
    }

    fun clearRecent() {
        runCatching { recentCache.keys.toList().forEach { recentCache.removeResource(it) } }
    }
}
