package com.prism.music.download

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.prism.music.AppContainer
import com.prism.music.container
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.local.LocalLossless
import com.prism.music.data.local.LocalLossless.Companion.norm
import com.prism.music.data.local.OwnFiles
import com.prism.music.data.local.PlaySourceRule
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Lossless sync: lossless files that land on the phone (FLAC, WAV, AIFF — from a PC, a synced
 * folder, anywhere) are looked up on YouTube Music and show up in Downloads as songs, playing
 * from the file. New files are picked up as soon as Android indexes them, and every few hours
 * by [LosslessSyncWorker]. Only runs while lossless playback and the lossless add-on are on.
 * Removing one from Downloads only hides it; the file itself is never touched.
 * Since 1.8.1 (#314) "Use your own song files" adds MP3/AAC/Ogg files to the same sync, with or without lossless
 * playback ([OwnFiles]); a lossless file of the same song still comes first ([PlaySourceRule]).
 */
class LosslessSync(private val context: Context, private val c: AppContainer) {
    /** File uri -> the song it was matched to (JSON). */
    private val links = context.getSharedPreferences("lossless_links", Context.MODE_PRIVATE)
    /** File uri -> when a lookup last found nothing (tried again after a week). */
    private val misses = context.getSharedPreferences("lossless_misses", Context.MODE_PRIVATE)
    /** Song ids removed from Downloads by hand. */
    private val hidden = context.getSharedPreferences("lossless_hidden", Context.MODE_PRIVATE)

    private val _songs = MutableStateFlow<Map<String, Pair<Song, LocalLossless.Track>>>(emptyMap())
    /** Synced lossless songs, by video id, with the file each plays from. */
    val songs: StateFlow<Map<String, Pair<Song, LocalLossless.Track>>> = _songs
    private val _working = MutableStateFlow(false)
    /** True while files are being looked up. */
    val working: StateFlow<Boolean> = _working

    private val mutex = Mutex()
    private var pending: Job? = null

    /** Which files sync looks at now: lossless ones (lossless playback + its file access) and your own lossy ones (#314). */
    private fun kinds(): OwnFiles.Kinds = OwnFiles.kinds(c.settings.current)

    private fun enabled(): Boolean = kinds().any && c.localLossless.hasPermission()

    /**
     * For in-app tests: finds the song for a file instead of YouTube Music (tests have no account and no network), and
     * lets a sync look files up while offline. Null in the app.
     */
    @androidx.annotation.VisibleForTesting
    @Volatile var lookUpForTest: (suspend (LocalLossless.Track) -> Song?)? = null

    /** Syncs now and whenever the setting changes or Android adds or removes audio files. */
    fun start() {
        c.scope.launch {
            c.settings.flow.map { OwnFiles.kinds(it) }.distinctUntilChanged().collect { kinds ->
                // The look every few hours only while it's on: off, it used to wake Prism up for nothing.
                runCatching { if (kinds.any) LosslessSyncWorker.schedule(context) else LosslessSyncWorker.cancel(context) }
                // Your own files turned off go back to the stream at once: this rescan leaves them out of Downloads.
                sync()
            }
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (enabled()) syncSoon()
            }
        }
        runCatching { context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer) }
    }

    /** Copying an album fires a burst of changes: wait for it to settle, then sync once. */
    private fun syncSoon() {
        pending?.cancel()
        pending = c.scope.launch { delay(15_000); sync() }
    }

    /** Rescans the phone for lossless files and adds any new ones to Downloads. */
    suspend fun sync(lookUp: Boolean = true) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!enabled()) {
                c.localLossless.linked = emptyMap()
                _songs.value = emptyMap()
                return@withLock
            }
            val kinds = kinds()
            c.localLossless.scan(lossless = kinds.lossless, lossy = kinds.lossy)
            val tracks = c.localLossless.tracks
            val present = tracks.map { it.key }.toSet()
            // Files that are gone take their song out of Downloads. Only the kinds this scan looked for: your own files'
            // matches are kept while their switch is off, so turning it back on doesn't look them all up again.
            fun scanned(key: String) = if (OwnFiles.isOwnKey(key)) kinds.lossy else kinds.lossless
            links.all.keys.filter { it !in present && scanned(it) }.let { gone ->
                if (gone.isNotEmpty()) links.edit().apply { gone.forEach { remove(it) } }.apply()
            }
            misses.all.keys.filter { it !in present && scanned(it) }.let { gone ->
                if (gone.isNotEmpty()) misses.edit().apply { gone.forEach { remove(it) } }.apply()
            }
            publish(tracks)

            if (!lookUp || (lookUpForTest == null && !online())) return@withLock
            val now = System.currentTimeMillis()
            // Already matched, or found nothing lately (a week for lossless files; your own files, of which a phone may
            // hold thousands, only after [OwnFiles.MISS_RETRY_MS] or once the file changes, whose key then changes too).
            val todo = tracks.filter { t ->
                val key = t.key
                !links.contains(key) && now - misses.getLong(key, 0L) > (if (t.lossy) OwnFiles.MISS_RETRY_MS else 7 * 86_400_000L)
            }.take(MAX_LOOKUPS)
            if (todo.isEmpty()) return@withLock
            _working.value = true
            var found = 0
            try {
                for (t in todo) {
                    val song = runCatching { lookUp(t) }.getOrElse { e ->
                        Log.w("LosslessSync", "lookup failed for ${t.title}", e)
                        null
                    }
                    val key = t.key
                    if (song != null) {
                        links.edit().putString(key, InnerTube.json.encodeToString(Song.serializer(), song)).apply()
                        found++
                        // Shows up in Downloads as it's found, not all at the end.
                        if (found % 5 == 0) publish(tracks)
                    } else {
                        misses.edit().putLong(key, now).apply()
                    }
                    if (lookUpForTest == null) delay(400) // gentle on YouTube Music
                }
            } finally {
                _working.value = false
                publish(tracks)
            }
            Log.i("LosslessSync", "${tracks.count { !it.lossy }} lossless files, ${tracks.count { it.lossy }} of your own; looked up ${todo.size}, matched $found")
            Unit
        }
    }

    private fun publish(tracks: List<LocalLossless.Track>) {
        val byKey = tracks.associateBy { it.key }
        val out = LinkedHashMap<String, Pair<Song, LocalLossless.Track>>()
        for ((key, json) in links.all) {
            val track = byKey[key] ?: continue
            val song = runCatching { InnerTube.json.decodeFromString(Song.serializer(), json as String) }.getOrNull() ?: continue
            // Two files of one song: the lossless one wins over your own lossy one ([PlaySourceRule]); else the first.
            val had = out[song.id]
            if (had == null || PlaySourceRule.choose(listOf(had.second, track)) !== had.second) out[song.id] = song to track
        }
        c.localLossless.linked = out.mapValues { it.value.second.uri }
        _songs.value = out.filterKeys { !hidden.contains(it) }
    }

    /** The YouTube Music song a file is: same title, same artist, about the same length. */
    private suspend fun lookUp(t: LocalLossless.Track): Song? {
        lookUpForTest?.let { return it(t) }
        if (t.lossy) return lookUpOwn(t)
        val title = norm(t.title).ifBlank { return null }
        val artist = norm(t.artist.substringBefore(";").substringBefore(","))
        val results = c.ytm.search("${t.artist} ${t.title}".trim(), SearchFilter.SONGS).items
            .mapNotNull { (it as? SongItem)?.song }.filter { !it.isVideo }
        fun lengthOk(s: Song, slack: Int) = s.durationSec <= 0 || t.durationMs <= 0 || kotlin.math.abs(t.durationMs / 1000 - s.durationSec) <= slack
        fun titleOk(s: Song) = norm(s.title).let { it == title || it.contains(title) || title.contains(it) }
        fun artistOk(s: Song) = artist.isBlank() || s.artists.any { a -> norm(a.name).let { it.isNotBlank() && (it.contains(artist) || artist.contains(it)) } }
        return results.firstOrNull { titleOk(it) && artistOk(it) && lengthOk(it, 4) }
            ?: results.firstOrNull { titleOk(it) && lengthOk(it, 2) && t.durationMs > 0 && it.durationSec > 0 }
    }

    /**
     * The song one of your own files is (#314). Their tags are often worse than lossless files' ("03 - Artist - Title",
     * no artist), and a wrong file playing for a song is worse than the stream, so the match is strict
     * ([OwnFiles.sameSong]): searched by the cleaned-up tags, the same title, a shared artist and both lengths known.
     */
    private suspend fun lookUpOwn(t: LocalLossless.Track): Song? {
        val tags = OwnFiles.clean(t.title, t.artist)
        if (tags.artist.isBlank() || norm(tags.title).isBlank() || t.durationMs <= 0) return null
        val results = c.ytm.search("${tags.artist} ${tags.title}".trim(), SearchFilter.SONGS).items
            .mapNotNull { (it as? SongItem)?.song }
        return pickOwn(t, results)
    }

    fun isSynced(id: String) = _songs.value.containsKey(id)

    /** Takes a song out of Downloads; its file stays where it is. */
    fun hide(ids: Collection<String>) {
        val local = ids.filter { _songs.value.containsKey(it) }
        if (local.isEmpty()) return
        hidden.edit().apply { local.forEach { putBoolean(it, true) } }.apply()
        _songs.value = _songs.value - local.toSet()
    }

    /** Puts a hidden song back in Downloads. True if it has a lossless file to play from. */
    fun unhide(id: String): Boolean {
        if (!hidden.contains(id) || !c.localLossless.linked.containsKey(id)) return false
        hidden.edit().remove(id).apply()
        c.scope.launch { sync(lookUp = false) }
        return true
    }

    private fun online(): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    companion object {
        /** Lookups per sync; a big library fills in over a few runs. */
        private const val MAX_LOOKUPS = 300

        /** Of a search's songs, the one your file [t] is, strictly ([OwnFiles.sameSong]); videos never. */
        fun pickOwn(t: LocalLossless.Track, results: List<Song>): Song? =
            results.firstOrNull { !it.isVideo && OwnFiles.sameSong(t.title, t.artist, t.durationMs, it) }
    }
}

/** Looks for new lossless files every few hours, even when Prism hasn't been opened. */
class LosslessSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        applicationContext.container.losslessSync.sync()
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<LosslessSyncWorker>(3, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("lossless_sync", ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork("lossless_sync")
        }
    }
}
