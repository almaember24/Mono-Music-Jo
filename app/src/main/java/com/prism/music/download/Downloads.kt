package com.prism.music.download

import com.prism.music.tr
import android.app.Notification
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.prism.music.AppContainer
import com.prism.music.R
import com.prism.music.container
import com.prism.music.data.db.toEntity
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.data.prefs.AudioQuality
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

const val DOWNLOAD_CHANNEL = "downloads"

/**
 * [lossless]: a file on the phone that lossless sync matched to this song (see [LosslessSync]): a lossless one, or with
 * [ownFile] one of your own MP3/AAC/Ogg files (1.8.1, #314), whose quality that is ("MP3 · 320 kbps").
 */
data class DownloadInfo(val song: Song?, val state: Int, val percent: Float, val bytes: Long, val addedAt: Long = 0, val lossless: Boolean = false, val ownFile: String? = null)

/**
 * A list row's download: its own, or for a music video that was downloaded as its song ("Songs, not videos"), the
 * song's ([standIns]: video id -> song id). #315: a playlist's video rows showed as not downloaded.
 */
fun Map<String, DownloadInfo>.forRow(id: String, standIns: Map<String, String>): DownloadInfo? = this[id] ?: standIns[id]?.let { this[it] }

/**
 * A song row's downloaded mark (#385, 1.8.3): downloaded under its id or its song's (#315), or it plays from a file on
 * the phone. Playing matches a file by the song (title, artist, length), while the mark went by id only, so another
 * playlist's id for the same song played the FLAC with no mark. [fromPhone] is asked only while lossless sync has
 * files at all.
 */
fun rowDownloaded(song: Song, downloads: Map<String, DownloadInfo>, standIns: Map<String, String>, fromPhone: (Song) -> Boolean): Boolean {
    if (downloads.forRow(song.id, standIns)?.state == Download.STATE_COMPLETED) return true
    return !song.isLocal && !song.isVideo && downloads.values.any { it.lossless } && fromPhone(song)
}

/** A song's music video saved for offline: its picture, plus the video's own audio when that isn't the song's. */
data class VideoDownload(val videoId: String, val state: Int, val percent: Float, val bytes: Long)

/** What each part of a video download carries: the song it belongs to and the video's id. */
@kotlinx.serialization.Serializable
private data class VideoPart(val song: Song, val videoId: String)

/** Request ids of a video download's parts (kept apart from song downloads, so they don't list as songs). */
private const val VIDEO_PICTURE = "vpic:"
private const val VIDEO_AUDIO = "vaud:"
/** How often a download YouTube refused (403) tries again by itself ([DownloadRepository.retryFailed]). */
private const val AUTO_RETRY_GAP_MS = 6 * 60 * 60_000L
private fun isVideoPart(id: String) = id.startsWith(VIDEO_PICTURE) || id.startsWith(VIDEO_AUDIO)
private fun partSongId(id: String) = id.substringAfter(':')

/**
 * Whether a music video downloads as its song (#186). #390 (1.8.3): only when it would also play as its song ("Songs, not
 * videos: Everywhere"); with "Radios" a video picked from search plays with its own sound, so it downloads that way too,
 * not as the album track.
 */
internal fun downloadsAsSong(song: Song, s: com.prism.music.data.prefs.AppSettings): Boolean =
    song.isVideo && !song.isUpload && !song.isLive && !song.isEpisode && s.radioSongsOnly && s.songsEverywhere

@OptIn(UnstableApi::class)
class DownloadRepository(private val context: Context, private val c: AppContainer, scope: CoroutineScope) {

    val manager: DownloadManager = DownloadManager(
        context,
        c.databaseProvider,
        c.downloadCache,
        c.downloadDataSourceFactory(),
        Executors.newFixedThreadPool(4),
    ).apply {
        maxParallelDownloads = 4
        // Media3 tries a failing download 5 times by itself, each with a fresh link; 3 is plenty (#185's log: the same
        // refused songs fetched and refused all day long).
        minRetryCount = 3
        requirements = Requirements(if (c.settings.current.downloadWifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)
    }

    /** Downloads Prism made itself, and lossless files on the phone ([LosslessSync]); both show as downloads. */
    private val _media = MutableStateFlow<Map<String, DownloadInfo>>(emptyMap())
    private val _lossless = MutableStateFlow<Map<String, DownloadInfo>>(emptyMap())
    private val _downloads = MutableStateFlow<Map<String, DownloadInfo>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadInfo>> = _downloads
    /**
     * Music videos that were downloaded as their song (#186, "Songs, not videos"), video id -> the song's id: a
     * playlist's video row shows its song's download (#315: those rows read "not downloaded" whatever was saved).
     */
    private val standInPrefs = context.getSharedPreferences("download_standins", Context.MODE_PRIVATE)
    private val _standIns = MutableStateFlow(standInPrefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap())

    private fun standIn(videoId: String, songId: String) {
        if (videoId == songId || _standIns.value[videoId] == songId) return
        _standIns.value = _standIns.value + (videoId to songId)
        standInPrefs.edit().putString(videoId, songId).apply()
        publish()
    }

    /** The id a download is kept under: a music video downloaded as its song is kept under the song's. */
    private fun keptAs(id: String): String = if (id in _media.value || id in _lossless.value) id else _standIns.value[id] ?: id

    private fun publish() {
        _downloads.value = _lossless.value + _media.value
        c.downloadStandIns.value = _standIns.value
        c.downloadState.value = _downloads.value
        c.smartPickBytes = _smartIds.value.sumOf { _media.value[it]?.bytes ?: 0L }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Songs Smart Downloads picked (the songs table's `smartDownload` flag), so their space and network rule are known. */
    private val _smartIds = MutableStateFlow<Set<String>>(emptySet())
    val smartIds: StateFlow<Set<String>> = _smartIds

    /** Re-reads which downloads are Smart Downloads' picks (after it adds or drops some). */
    fun refreshSmartIds() {
        c.scope.launch(Dispatchers.IO) {
            _smartIds.value = runCatching { c.db.songs().smartDownloads().map { it.id }.toSet() }.getOrDefault(_smartIds.value)
            publish()
            applyRequirements()
            c.trimSongCache()
        }
    }

    /**
     * Downloads wait for Wi-Fi when they're set to; Smart Downloads' picks may use mobile data when it's allowed to,
     * so while only picks are waiting, any network will do (a song you download yourself still waits for Wi-Fi).
     */
    private fun applyRequirements() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { mainHandler.post { applyRequirements() }; return }
        val s = c.settings.current
        val waiting = { d: DownloadInfo -> d.state == Download.STATE_QUEUED || d.state == Download.STATE_DOWNLOADING }
        val smart = _smartIds.value
        val picksOnly = s.smartDownloads && s.smartDownloadsCellular &&
            _media.value.any { (id, d) -> id in smart && waiting(d) } &&
            _media.value.none { (id, d) -> id !in smart && waiting(d) } &&
            _videoParts.value.values.none { it.state == Download.STATE_QUEUED || it.state == Download.STATE_DOWNLOADING }
        val req = Requirements(if (s.downloadWifiOnly && !picksOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)
        if (manager.requirements != req) manager.requirements = req
    }

    /** Video download parts by request id, and the videos they add up to, by song id. */
    private val _videoParts = MutableStateFlow<Map<String, Download>>(emptyMap())
    private val _videos = MutableStateFlow<Map<String, VideoDownload>>(emptyMap())
    val videos: StateFlow<Map<String, VideoDownload>> = _videos

    private fun publishVideos() {
        _videos.value = _videoParts.value.values.groupBy { partSongId(it.request.id) }.mapNotNull { (songId, parts) ->
            val videoId = parts.firstNotNullOfOrNull { it.part()?.videoId } ?: return@mapNotNull null
            val state = when {
                parts.all { it.state == Download.STATE_COMPLETED } -> Download.STATE_COMPLETED
                parts.any { it.state == Download.STATE_FAILED } -> Download.STATE_FAILED
                parts.any { it.state == Download.STATE_DOWNLOADING } -> Download.STATE_DOWNLOADING
                else -> Download.STATE_QUEUED
            }
            songId to VideoDownload(videoId, state, parts.map { it.percentDownloaded.coerceAtLeast(0f) }.average().toFloat(), parts.sumOf { it.bytesDownloaded })
        }.toMap()
        c.videoState.value = _videos.value
    }

    private fun Download.part(): VideoPart? = runCatching { InnerTube.json.decodeFromString(VideoPart.serializer(), String(request.data)) }.getOrNull()

    private val lyricTries = context.getSharedPreferences("download_lyrics", Context.MODE_PRIVATE)
    private val lyricGate = kotlinx.coroutines.sync.Semaphore(2)
    private val _lyricsWorking = MutableStateFlow(0)
    /** How many downloaded songs are having their lyrics fetched right now. */
    val lyricsWorking: StateFlow<Int> = _lyricsWorking
    private val _lyricLevels = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Saved lyrics per downloaded song, as [com.prism.music.data.lyrics.LyricsRepository.level]. */
    val lyricLevels: StateFlow<Map<String, Int>> = _lyricLevels
    // Above init: its background work and listeners use them straight away.
    private val _waiting = MutableStateFlow(false)
    /** Downloads are held back by their network rule (waiting for Wi-Fi). */
    val waitingForNetwork: StateFlow<Boolean> = _waiting
    private val failurePrefs = context.getSharedPreferences("download_failures", Context.MODE_PRIVATE)
    private val _failures = MutableStateFlow(failurePrefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap())
    /** Why each failed download failed, in words ([DownloadFailures], #98); kept until it finishes or is removed. */
    val failures: StateFlow<Map<String, String>> = _failures

    private fun noteFailure(id: String, why: String?) {
        if (why == null) {
            if (id !in _failures.value) return
            _failures.update { it - id }
            failurePrefs.edit().remove(id).apply()
        } else {
            _failures.update { it + (id to why) }
            failurePrefs.edit().putString(id, why).apply()
        }
    }
    /** Bytes each running download had at the last look, and how many looks in a row it hasn't moved. */
    private val progressSeen = HashMap<String, Pair<Long, Int>>()

    init {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(DOWNLOAD_CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(tr(R.string.downloads)).build()
        )
        scope.launch(Dispatchers.IO) {
            val map = mutableMapOf<String, DownloadInfo>()
            val parts = mutableMapOf<String, Download>()
            manager.downloadIndex.getDownloads().use { cursor ->
                while (cursor.moveToNext()) {
                    val d = cursor.download
                    if (isVideoPart(d.request.id)) parts[d.request.id] = d else map[d.request.id] = d.info()
                }
            }
            _media.value = map
            _videoParts.value = parts
            publish()
            publishVideos()
            refreshSmartIds()
            // Songs downloaded before lyrics came with them (or while offline) get theirs now.
            backfillLyrics()
            // ...and their animated covers, if they waited for Wi-Fi.
            saveCanvases()
            // ...and their covers, so they show offline (#54).
            saveCovers()
            // Downloads that failed (a stream link that ran out, a dropped connection) try again by themselves (#55).
            retryFailedWhenQuiet()
            _waiting.value = manager.notMetRequirements != 0
        }
        c.network.onReconnect { retryFailedWhenQuiet(); saveCovers() }
        watchStalls(scope)
        manager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) {
                if (isVideoPart(download.request.id)) {
                    _videoParts.update { it + (download.request.id to download) }
                    publishVideos()
                    applyRequirements()
                    return
                }
                _media.update { it + (download.request.id to download.info()) }
                publish()
                applyRequirements()
                when (download.state) {
                    Download.STATE_FAILED -> {
                        noteFailure(download.request.id, DownloadFailures.describe(finalException))
                        android.util.Log.w("PrismDownload", "failed ${download.request.id}", finalException)
                    }
                    Download.STATE_COMPLETED -> noteFailure(download.request.id, null)
                }
                holdInBackground()
                // A pick that arrived takes its space from the song cache.
                if (download.state == Download.STATE_COMPLETED && download.request.id in _smartIds.value) c.trimSongCache()
                if (download.state == Download.STATE_COMPLETED) download.info().song?.let { s ->
                    c.scope.launch(Dispatchers.IO) { ensureLyrics(s) }
                    c.scope.launch(Dispatchers.IO) { saveCover(s) }
                    // Its animated cover is kept with it (on Wi-Fi, unless allowed on mobile data; [saveCanvases] catches up).
                    saveCanvas(s)
                }
            }

            override fun onRequirementsStateChanged(manager: DownloadManager, requirements: Requirements, notMetRequirements: Int) {
                _waiting.value = notMetRequirements != 0
            }

            override fun onDownloadRemoved(manager: DownloadManager, download: Download) {
                if (isVideoPart(download.request.id)) {
                    _videoParts.update { it - download.request.id }
                    publishVideos()
                    return
                }
                _media.update { it - download.request.id }
                publish()
                applyRequirements()
                noteFailure(download.request.id, null)
                c.forgetDownloadFormat(download.request.id)
                c.canvasStore.forgetDownloads(listOf(download.request.id))
            }
        })
        scope.launch {
            c.losslessSync.songs.collect { synced ->
                val known = _lossless.value
                _lossless.value = synced.mapValues { (id, v) ->
                    DownloadInfo(
                        v.first, Download.STATE_COMPLETED, 100f, v.second.size, known[id]?.addedAt ?: System.currentTimeMillis(), lossless = true,
                        ownFile = v.second.takeIf { it.lossy }?.let(com.prism.music.data.local.OwnFiles::label),
                    )
                }
                publish()
                // Lyrics come with synced songs too, like with any download (same Wi-Fi-only rule).
                val metered = context.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered != false
                if (!metered || !c.settings.current.downloadWifiOnly) {
                    synced.keys.filter { it !in known }.forEach { id -> synced[id]?.first?.let { s -> c.scope.launch(Dispatchers.IO) { ensureLyrics(s) } } }
                }
            }
        }
        // Back on Wi-Fi: covers that waited for it are saved.
        runCatching {
            context.getSystemService(android.net.ConnectivityManager::class.java)?.registerNetworkCallback(
                android.net.NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED).build(),
                object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) = saveCanvases()
                },
            )
        }
        scope.launch {
            c.settings.flow.collect { applyRequirements() }
        }
        scope.launch(Dispatchers.Main) { c.visible.collect { holdInBackground() } }
    }

    /** Downloads held back while Prism is out of sight ([holdInBackground]); they go on when it's opened. */
    private var heldInBackground = false

    /**
     * Android 12+ only lets the download service put up its notification (become a foreground service) while Prism is
     * on screen. A download that started or restarted with Prism in the background (Wi-Fi coming back, a stalled one
     * restarted) made Android throw, which closed Prism (GitHub #100's log, 1.7.2). So while Prism isn't showing and
     * nothing is downloading, the downloads wait; ones already running carry on (their notification is already up).
     * Main thread.
     */
    private fun holdInBackground() {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        if (c.visible.value) {
            if (heldInBackground) { heldInBackground = false; manager.resumeDownloads() }
        } else if (!heldInBackground && manager.currentDownloads.none { it.state == Download.STATE_DOWNLOADING }) {
            heldInBackground = true
            manager.pauseDownloads()
        }
    }

    /**
     * Starts a download: through the download service while Prism is showing (it puts up the progress notification),
     * else straight into the download manager, since Android doesn't let an app start a service from the background
     * (Smart Downloads' worker ran into that).
     */
    private fun add(request: DownloadRequest) {
        if (c.visible.value) DownloadService.sendAddDownload(context, PrismDownloadService::class.java, request, false)
        else mainHandler.post { manager.addDownload(request) }
    }


    /**
     * Starts failed downloads again (#55), with fresh stream links. Before, a download that failed stayed failed for
     * good, still showing how far it had got, and an app restart didn't help.
     */
    /**
     * [retryFailed] at launch and on reconnecting: once Prism is on screen (Android 12+ won't let the download service
     * start from the background: 1.6.8 crashed when Prism was started in the background, by the playback service or a
     * worker, with failed downloads waiting) and once the music has loaded, so the retries don't hold it up.
     */
    private fun retryFailedWhenQuiet() {
        c.scope.launch {
            while (!appOnScreen()) kotlinx.coroutines.delay(5_000)
            c.quietNetwork()
            if (appOnScreen()) retryFailed(auto = true) else retryFailedWhenQuiet()
        }
    }

    private fun appOnScreen(): Boolean {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        return info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /**
     * When each refused download was last tried again by itself. YouTube refusing a link (403) isn't fixed by trying
     * again a minute later: gog-com's log (#185, #188) had 25 such songs re-asked ~170 times each in a day, every launch
     * and reconnect, using data and holding up playback. They go again by themselves at most every [AUTO_RETRY_GAP_MS];
     * "Try again" always works.
     */
    private val autoRetried = context.getSharedPreferences("download_auto_retry", Context.MODE_PRIVATE)

    /** Starts failed downloads again: all of them, or only those in [only] (song ids). [auto]: not asked for by the listener. */
    fun retryFailed(only: Set<String>? = null, auto: Boolean = false) {
        if (!c.network.isOnline()) return
        val now = System.currentTimeMillis()
        val failed = runCatching {
            buildList {
                manager.downloadIndex.getDownloads(Download.STATE_FAILED).use { cursor -> while (cursor.moveToNext()) add(cursor.download) }
            }
        }.getOrDefault(emptyList()).filter { only == null || it.request.id in only }.filter { d ->
            val id = d.request.id
            if (!auto || _failures.value[id]?.contains("(403)") != true) return@filter true
            val last = autoRetried.getLong(id, 0L)
            (now - last >= AUTO_RETRY_GAP_MS || last > now).also { go -> if (go) autoRetried.edit().putLong(id, now).apply() }
        }
        if (failed.isEmpty()) return
        mainHandler.post {
            for (d in failed) {
                streamOf(d)?.let(c.streams::invalidate)
                runCatching { DownloadService.sendAddDownload(context, PrismDownloadService::class.java, d.request, false) }
            }
        }
    }

    /**
     * A finished download the player couldn't read (its file is broken: #183, two formats stitched together by a
     * download that resumed in another one): it's thrown away and fetched again, whole. True when it was a download.
     * [unfinished]: one still coming down starts over too (another version was picked: what's come so far is the
     * version before, and finishing it would keep that recording as the song's download).
     */
    fun redownload(id: String, unfinished: Boolean = false): Boolean {
        val d = runCatching { manager.downloadIndex.getDownload(id) }.getOrNull() ?: return false
        if (d.state != Download.STATE_COMPLETED && !(unfinished && d.state in UNFINISHED)) return false
        mainHandler.post {
            manager.removeDownload(id)
            c.forgetDownloadFormat(id)
            add(d.request)
        }
        return true
    }

    /**
     * Finished song downloads below Premium's 256 kbps (#149): by the format they came in, or, for ones from before
     * Prism kept that (1.7.7.1), by their size against the song's length. Lossless files and videos aren't counted.
     */
    fun belowPremium(): List<String> = _downloads.value.filter { (id, d) ->
        d.state == Download.STATE_COMPLETED && !d.lossless && !isVideoPart(id) && !id.startsWith(Song.LOCAL_PREFIX)
    }.keys.filter { id ->
        c.downloadedItag(id)?.let { it !in PREMIUM_ITAGS } ?: run {
            val dl = runCatching { manager.downloadIndex.getDownload(id) }.getOrNull() ?: return@run false
            val sec = runCatching { InnerTube.json.decodeFromString(Song.serializer(), String(dl.request.data)).durationSec }.getOrDefault(0)
            sec > 30 && dl.bytesDownloaded * 8 / 1000 / sec < PREMIUM_KBPS
        }
    }

    /** Fetches [ids] again, whole (in Premium quality when it's on and the account has it). */
    fun downloadAgain(ids: Collection<String>) = ids.forEach { redownload(it) }

    /** The notification's Cancel (R14): every download that hasn't finished is taken out; finished ones stay. */
    fun cancelUnfinished() = mainHandler.post {
        manager.currentDownloads.filter { it.state != Download.STATE_COMPLETED }.forEach { manager.removeDownload(it.request.id) }
    }

    /** The YouTube id a download's bytes come from (a video part's is its video's). */
    private fun streamOf(d: Download): String? = if (isVideoPart(d.request.id)) d.part()?.videoId else d.request.id


    /**
     * Every minute: a download that hasn't moved for three minutes while online (a connection that hung, a stream link
     * that ran out mid-way) is stopped and started again from where it got to, with a fresh link.
     */
    private fun watchStalls(scope: CoroutineScope) = scope.launch(Dispatchers.Main) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            val running = manager.currentDownloads.filter { it.state == Download.STATE_DOWNLOADING }
            progressSeen.keys.retainAll(running.map { it.request.id }.toSet())
            // Not while Prism is out of sight: a restarted download couldn't put its notification back (Android 12+).
            if (!c.network.online.value || !c.visible.value) continue
            for (d in running) {
                val id = d.request.id
                val (bytes, still) = progressSeen[id] ?: (-1L to 0)
                val now = if (d.bytesDownloaded == bytes) still + 1 else 0
                progressSeen[id] = d.bytesDownloaded to now
                if (now >= 3) {
                    progressSeen.remove(id)
                    streamOf(d)?.let(c.streams::invalidate)
                    manager.setStopReason(id, STALL_STOP)
                    manager.setStopReason(id, Download.STOP_REASON_NONE)
                }
            }
        }
    }

    /**
     * Keeps a downloaded song's cover on the phone, for offline (#54): the cover in its lossless file when it's one of
     * those (your own artwork), else the one YouTube Music shows, at full size.
     */
    private suspend fun saveCover(song: Song) {
        if (song.isLocal || song.isRss) return
        val lossless = isLosslessOnly(song.id)
        // A cover kept small for the library (an album's, 544 px) is fetched again at full size (#86).
        if (if (lossless) c.artwork.has(song.thumbnail) else c.artwork.hasFull(song.thumbnail)) return
        val file = if (lossless) c.localLossless.match(song)?.uri else null
        if (file != null) {
            val embedded = runCatching {
                val r = android.media.MediaMetadataRetriever()
                try { r.setDataSource(context, file); r.embeddedPicture } finally { r.release() }
            }.getOrNull()
            if (embedded != null && c.artwork.saveBytes(song.thumbnail, embedded)) return
        }
        if (c.network.isOnline()) c.artwork.save(song.thumbnail)
    }

    /** Covers for every download that doesn't have one kept yet; covers nothing uses any more go. */
    fun saveCovers() {
        c.scope.launch(Dispatchers.IO) {
            val songs = completedSongs()
            for (s in songs) runCatching { saveCover(s) }
            val library = (c.library.playlists.value + c.library.albums.value).map { c.covers.art(it) }
            if (songs.isNotEmpty()) runCatching { c.artwork.prune(songs.map { it.thumbnail } + c.offline.covers() + library) }
        }
    }

    private fun Download.info() = DownloadInfo(
        song = runCatching { InnerTube.json.decodeFromString(Song.serializer(), String(request.data)) }.getOrNull(),
        state = state,
        percent = percentDownloaded,
        bytes = bytesDownloaded,
        addedAt = startTimeMs,
    )

    fun isDownloaded(id: String) = _downloads.value[id]?.state == Download.STATE_COMPLETED

    /** #385: this song plays from a file on the phone, by the same matcher and settings playback uses. */
    fun playsFromPhone(song: Song): Boolean {
        val kinds = com.prism.music.data.local.OwnFiles.kinds(c.settings.current)
        return kinds.any && c.localLossless.match(song, lossless = kinds.lossless, lossy = kinds.lossy, note = false) != null
    }

    /** True when this song is only here as a lossless file on the phone (not downloaded by Prism). */
    fun isLosslessOnly(id: String) = id in _lossless.value && id !in _media.value

    fun download(song: Song, asIs: Boolean = false) {
        // A file on the phone is already offline. (Episodes from a feed don't download yet.)
        if (song.isLocal || song.isRss) return
        c.keptPlaylists.forgive(setOf(song.id, keptAs(song.id)) + _standIns.value.filterValues { it == song.id }.keys)
        // A lossless file that was taken out of Downloads just comes back.
        if (c.losslessSync.unhide(song.id)) return
        // (A music video already downloaded as its song counts as downloaded.)
        if (_downloads.value[keptAs(song.id)]?.state.let { it == Download.STATE_COMPLETED || it == Download.STATE_DOWNLOADING || it == Download.STATE_QUEUED }) return
        // A music video comes down as its song (#186) when it would play as its song: the audio, without the video's
        // intro and outro. When there's no song for it, the video's own sound, as before.
        if (!asIs && downloadsAsSong(song, c.settings.current)) {
            c.scope.launch {
                val audio = c.player.songFor(song)
                if (audio != null) {
                    com.prism.music.playback.AudioDiagnostics.log("download", "${song.id} is a music video: downloading its song ${audio.id} instead")
                    standIn(song.id, audio.id)
                }
                download(audio ?: song, asIs = true)
            }
            return
        }
        val request = DownloadRequest.Builder(song.id, android.net.Uri.parse("prism://audio/${song.id}"))
            .setCustomCacheKey(song.id)
            .setData(InnerTube.json.encodeToString(Song.serializer(), song).toByteArray())
            .build()
        c.scope.launch(Dispatchers.IO) {
            c.db.songs().upsert(song.toEntity(c.db.songs().get(song.id)))
            // Lyrics come down with the song (karaoke if any source has it), so they're there offline too.
            ensureLyrics(song, force = true)
            // ...and its loudness, so normalization works offline.
            if (!c.loudness.contains(song.id)) runCatching { c.ytm.playerExtras(song.id, c.streams.signatureTimestamp()).loudnessDb?.let { c.loudness.edit().putFloat(song.id, it.toFloat()).apply() } }
        }
        add(request)
        // Downloads show their progress in a notification: the first one the listener starts asks, if nothing has.
        if (appOnScreen()) c.askNotifications()
    }

    /** A list's download: everything on it, but for the songs marked to skip (#221). */
    fun downloadAll(songs: List<Song>) = PlaylistDownloads.wanted(songs, c.downloadSkips.ids.value, _standIns.value).forEach(::download)

    /**
     * Marks a song to skip in lists' downloads, or takes that back (#221). One still to come or failed stops there, so it
     * leaves Failed downloads and isn't tried again; one already here stays, since nothing asked for it to be deleted.
     */
    fun setSkipped(id: String, skip: Boolean) {
        c.downloadSkips.set(id, skip)
        if (!skip) return
        val here = _downloads.value[keptAs(id)] ?: return
        if (here.state != Download.STATE_COMPLETED) removeMany(listOf(id))
    }

    /** Every song with a download of any kind, whatever state it's in, from the download list itself; null if it can't be read. */
    fun knownIds(): Set<String>? = runCatching {
        buildSet {
            manager.downloadIndex.getDownloads().use { cursor -> while (cursor.moveToNext()) add(cursor.download.request.id) }
            addAll(_downloads.value.keys)
        }
    }.getOrNull()

    // ------------------------------------------------------------ Music videos

    /** The video saved for [songId], once all of it is here (what video mode plays offline). */
    fun downloadedVideoId(songId: String): String? = _videos.value[songId]?.takeIf { it.state == Download.STATE_COMPLETED }?.videoId

    /** Whether the video [videoId] is all here, whichever song it was saved for. */
    fun videoSaved(videoId: String): Boolean = _videos.value.values.any { it.videoId == videoId && it.state == Download.STATE_COMPLETED }

    /**
     * Saves the song's music video for offline: the song itself (so it's in Downloads), the video's picture
     * and, when the video isn't the song, the video's own audio, which its picture is timed to. The player's
     * video mode reads them from the download cache under the same keys it streams with.
     */
    fun downloadVideo(song: Song) {
        if (song.isLive) return
        download(song)
        if (_videos.value[song.id]?.state.let { it == Download.STATE_COMPLETED || it == Download.STATE_DOWNLOADING || it == Download.STATE_QUEUED }) return
        c.scope.launch {
            val videoId = if (song.isVideo) song.id else c.queue.counterparts[song.id]
                ?: runCatching { kotlinx.coroutines.withContext(Dispatchers.IO) { c.ytm.findMusicVideo(song) } }.getOrNull()?.also { c.queue.counterparts[song.id] = it }
            if (videoId == null) { c.player.reportError(tr(R.string.no_music_video_to_download_for)); return@launch }
            val data = InnerTube.json.encodeToString(VideoPart.serializer(), VideoPart(song, videoId)).toByteArray()
            val picture = DownloadRequest.Builder(VIDEO_PICTURE + song.id, android.net.Uri.parse("prism://video/$videoId"))
                .setCustomCacheKey("video:$videoId").setData(data).build()
            add(picture)
            if (videoId != song.id) {
                val audio = DownloadRequest.Builder(VIDEO_AUDIO + song.id, android.net.Uri.parse("prism://audio/$videoId"))
                    .setCustomCacheKey(videoId).setData(data).build()
                add(audio)
            }
        }
    }

    /** Takes back a saved video (the song stays downloaded). */
    fun removeVideo(songId: String) {
        _videoParts.value.keys.filter { partSongId(it) == songId }.forEach { manager.removeDownload(it) }
    }

    /** [byListener]: their choice, so a kept playlist doesn't fetch it back; Smart Downloads tidying up isn't that. */
    fun remove(asked: String, byListener: Boolean = true) {
        // A video row's download is its song's (#315).
        val id = keptAs(asked)
        if (isLosslessOnly(id)) { c.losslessSync.hide(listOf(id)); return }
        if (byListener) c.keptPlaylists.noteDeleted(setOf(asked, id))
        DownloadService.sendRemoveDownload(context, PrismDownloadService::class.java, id, false)
        removeVideo(id)
        c.scope.launch(Dispatchers.IO) { c.db.songs().setSmart(id, false); refreshSmartIds() }
    }

    /** Deletes many downloads at once, straight through the manager (no intent per song). */
    fun removeMany(asked: Collection<String>) {
        val ids = asked.map(::keptAs).distinct()
        c.keptPlaylists.noteDeleted(asked.toSet() + ids)
        c.losslessSync.hide(ids.filter(::isLosslessOnly))
        ids.forEach { manager.removeDownload(it); removeVideo(it) }
        c.scope.launch(Dispatchers.IO) { ids.forEach { c.db.songs().setSmart(it, false) }; refreshSmartIds() }
    }

    fun removeAll() {
        c.keptPlaylists.stopAll()
        val ids = _media.value.keys.toList()
        c.losslessSync.hide(_lossless.value.keys)
        manager.removeAllDownloads()
        c.scope.launch(Dispatchers.IO) { ids.forEach { c.db.songs().setSmart(it, false) }; refreshSmartIds() }
    }

    /** Smart Downloads' picks and the song cache, emptied (the songs you downloaded yourself stay). */
    fun clearSmartDownloads() {
        removeMany(_smartIds.value.filter { id -> _media.value[id] != null })
        c.clearSongCache()
    }

    // ------------------------------------------------------------ Lyrics for downloads


    private fun online(): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Makes sure a downloaded song has the best lyrics going. Songs without karaoke lyrics are
     * looked at again every few days (sources keep adding them); [force] skips that wait.
     */
    private suspend fun ensureLyrics(song: Song, force: Boolean = false) {
        val have = runCatching { c.lyrics.savedLevel(song) }.getOrDefault(0)
        _lyricLevels.update { it + (song.id to have) }
        if (have >= 3 || !online()) return
        val last = lyricTries.getLong(song.id, 0L)
        if (!force && System.currentTimeMillis() - last < 3 * 86_400_000L) return
        lyricGate.acquire()
        _lyricsWorking.update { it + 1 }
        try {
            val got = runCatching { c.lyrics.download(song) }.getOrNull()
            lyricTries.edit().putLong(song.id, System.currentTimeMillis()).apply()
            _lyricLevels.update { it + (song.id to maxOf(have, c.lyrics.level(got))) }
        } finally {
            _lyricsWorking.update { it - 1 }
            lyricGate.release()
        }
    }

    /** Fills in lyrics for every downloaded song that's missing them (or only has them line by line). */
    fun backfillLyrics(force: Boolean = false) {
        c.scope.launch(Dispatchers.IO) {
            // Same rule as the downloads themselves: no mobile data when they're set to Wi-Fi only.
            val metered = context.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered != false
            if (metered && c.settings.current.downloadWifiOnly && !force) {
                completedSongs().forEach { s -> _lyricLevels.update { it + (s.id to runCatching { c.lyrics.savedLevel(s) }.getOrDefault(0)) } }
                return@launch
            }
            for (song in completedSongs()) ensureLyrics(song, force)
        }
    }

    // ------------------------------------------------------------ Animated covers for downloads

    /**
     * Saves a downloaded song's animated cover, when covers are on and saved with downloads, and the network allows
     * it. True when it was saved or asked for (or nothing's needed); false when it waits for Wi-Fi.
     */
    private fun saveCanvas(song: Song): Boolean {
        val s = c.settings.current
        if (!s.canvasEnabled || !s.canvasWithDownloads || song.isLocal || c.canvas.saved.value.has(song.id, null)) return true
        val metered = context.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered != false
        if (metered && !s.canvasWithDownloadsCellular) return false
        c.canvasStore.saveWithDownload(song)
        return true
    }

    /** Saves the covers downloaded songs don't have yet (at launch, after the setting's turned on, back on Wi-Fi). */
    fun saveCanvases() {
        c.scope.launch(Dispatchers.Main) { for (song in completedSongs()) if (!saveCanvas(song)) break }
    }

    /** Newest first (#77: they came in the download index's own order, which the "Recently added" sort showed as is). */
    fun completedSongs(): List<Song> = _downloads.value.values
        .filter { it.state == Download.STATE_COMPLETED }.sortedByDescending { it.addedAt }.mapNotNull { it.song }

    val totalBytes: Long get() = _downloads.value.values.sumOf { it.bytes } + _videos.value.values.sumOf { it.bytes }
}

@OptIn(UnstableApi::class)
class PrismDownloadService : DownloadService(
    1001, DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL, DOWNLOAD_CHANNEL, R.string.downloads, 0,
) {
    override fun getDownloadManager(): DownloadManager = container.downloads.manager

    override fun getScheduler(): Scheduler? = null

    /**
     * How many songs are coming down, the one now and how far they've got, with Cancel (#149, R14: Media3's own said
     * neither how many nor offered a way to stop them). Cancel takes out only what hasn't finished.
     */
    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        // The songs: a music video's picture and sound come down as parts of their song.
        val active = downloads.filter {
            (it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED || it.state == Download.STATE_RESTARTING) &&
                !it.request.id.startsWith("vpic:") && !it.request.id.startsWith("vaud:")
        }
        val now = active.firstOrNull { it.state == Download.STATE_DOWNLOADING } ?: active.firstOrNull()
        val title = now?.let { d -> runCatching { InnerTube.json.decodeFromString(Song.serializer(), String(d.request.data)).title }.getOrNull() }
        val known = active.map { it.percentDownloaded }.filter { it >= 0f }
        val percent = if (known.isEmpty()) -1 else known.average().roundToInt()
        val waiting = when {
            notMetRequirements == 0 -> null
            notMetRequirements and androidx.media3.exoplayer.scheduler.Requirements.NETWORK_UNMETERED != 0 -> tr(R.string.waiting_for_wi_fi)
            else -> tr(R.string.waiting_for_a_connection)
        }
        val cancel = android.app.PendingIntent.getBroadcast(
            this, 0, Intent(this, CancelDownloadsReceiver::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            android.app.PendingIntent.getActivity(this, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE)
        }
        return androidx.core.app.NotificationCompat.Builder(this, DOWNLOAD_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(com.prism.music.trp(R.plurals.downloading_n_songs, active.size.coerceAtLeast(1)))
            .setContentText(waiting ?: title ?: tr(R.string.getting_ready))
            .setProgress(100, percent.coerceAtLeast(0), percent < 0 && waiting == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .apply { if (open != null) setContentIntent(open) }
            .addAction(0, tr(R.string.cancel), cancel)
            .build()
    }
}

/** The downloads notification's Cancel: every download that hasn't finished goes; finished ones stay. */
class CancelDownloadsReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: Intent) {
        context.container.downloads.cancelUnfinished()
    }
}

private val PREMIUM_ITAGS = com.prism.music.data.stream.StreamResolver.PREMIUM_ITAGS
/** A download on its way (or waiting to be): [Downloads.redownload] can start it over. */
private val UNFINISHED = setOf(Download.STATE_QUEUED, Download.STATE_DOWNLOADING, Download.STATE_STOPPED, Download.STATE_FAILED)
/** Below this a download isn't Premium's 256 kbps (normal quality is 128–160). */
private const val PREMIUM_KBPS = 200

/** The stop reason [DownloadRepository] uses for a moment to restart a stalled download. */
private const val STALL_STOP = 7

/** Sizes offered for Smart Downloads' space (its picks and the songs you've streamed), in GB. */
val SMART_DOWNLOAD_SIZES = listOf(0.25f, 0.5f, 1f, 2f, 3f, 5f, 7.5f, 10f, 15f, 20f, 30f, 50f)

fun formatGb(gb: Float): String = when {
    gb < 1f -> "${(gb * 1024).roundToInt()} MB"
    gb % 1f == 0f -> "${gb.toInt()} GB"
    else -> "$gb GB"
}

private fun kbps(q: AudioQuality) = when (q) { AudioQuality.HIGH -> 150; AudioQuality.NORMAL -> 128; AudioQuality.LOW -> 55 }

/** Rough size of a download at a quality; long mixes and missing durations count as a typical song. */
fun estimatedSongBytes(durationSec: Int, q: AudioQuality): Long = (durationSec.takeIf { it in 30..1200 } ?: 210) * kbps(q) * 125L

fun smartSongEstimate(gb: Float, q: AudioQuality): Long =
    (gb * 1_073_741_824.0 / estimatedSongBytes(210, q)).toLong()

