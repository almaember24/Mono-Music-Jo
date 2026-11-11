package com.prism.music.download

import android.content.Context
import android.util.Log
import androidx.media3.exoplayer.offline.Download
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.prism.music.container
import com.prism.music.data.db.PlayEventEntity
import com.prism.music.data.db.toEntity
import com.prism.music.data.model.Song
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.math.exp
import kotlin.math.ln

/**
 * Predicts how likely you are to play a song soon, from your own listening:
 * a small logistic model over recency-weighted plays, how often you finish
 * it (vs skip), whether you liked it, how much you play the artist, whether
 * you tend to play it at this time of day, and — for recommendations — how
 * strongly the songs it was recommended from are favourites.
 * Everything runs on the phone; nothing about your listening leaves it.
 */
class PlayLikelihood(events: List<PlayEventEntity>, songs: Map<String, Song>, private val liked: Set<String>, now: Long = System.currentTimeMillis()) {
    private class Stats(var decayed: Double = 0.0, var completionSum: Double = 0.0, var n: Int = 0, var last: Long = 0, var atThisHour: Int = 0)

    private val bySong = HashMap<String, Stats>()
    private val artistWeight = HashMap<String, Double>()
    private val maxArtist: Double
    private val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    init {
        val cal = Calendar.getInstance()
        for (e in events) {
            val ageDays = (now - e.timestamp) / 86_400_000.0
            val dur = (songs[e.songId]?.durationSec ?: 0) * 1000.0
            val completion = if (dur > 0) (e.playedMs / dur).coerceIn(0.0, 1.0) else 0.7
            // A play only counts as much as it was listened to: a skip barely counts at all.
            val w = exp(-ageDays / 14.0) * ((completion - 0.15) / 0.55).coerceIn(0.05, 1.0)
            val st = bySong.getOrPut(e.songId) { Stats() }
            st.decayed += w
            st.completionSum += completion
            st.n++
            if (e.timestamp > st.last) st.last = e.timestamp
            cal.timeInMillis = e.timestamp
            val h = cal.get(Calendar.HOUR_OF_DAY)
            if (kotlin.math.abs(h - hour).let { minOf(it, 24 - it) } <= 2) st.atThisHour++
            songs[e.songId]?.primaryArtist?.takeIf { it.isNotBlank() }?.let { artistWeight.merge(it.lowercase(), w, Double::plus) }
        }
        maxArtist = artistWeight.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    }

    /** How much the songs you play most are by this artist, 0..1. */
    fun artistAffinity(song: Song) = (artistWeight[song.primaryArtist.lowercase()] ?: 0.0) / maxArtist

    /** Probability-like score in 0..1. [recommendedFrom] is the seed song's score, for radio picks. */
    fun score(song: Song, recommendedFrom: Double = 0.0, rank: Int = 0): Double {
        val st = bySong[song.id]
        var z = -2.6
        if (st != null) {
            z += 1.5 * ln(1 + st.decayed)
            z += 2.2 * ((st.completionSum / st.n) - 0.55) // finishing it vs skipping it
            z += 0.6 * (st.atThisHour.toDouble() / st.n)
            val idleDays = (System.currentTimeMillis() - st.last) / 86_400_000.0
            if (idleDays > 60) z -= 0.8
        }
        if (song.id in liked) z += 1.1
        z += 1.7 * artistAffinity(song)
        if (recommendedFrom > 0) z += 1.4 * recommendedFrom * (1.0 - (rank / 60.0).coerceAtMost(0.8))
        return 1 / (1 + exp(-z))
    }
}

/**
 * Smart downloads, like YouTube Music's: songs you're most likely to play are
 * kept offline, ranked by [PlayLikelihood]. It fills its share of the space you allow
 * gradually — a slice each run (twice a day, on Wi-Fi unless mobile data is allowed),
 * never all at once — and slowly rotates out songs you've moved on from. The rest of
 * the space holds the songs you stream ([SmartCacheEvictor]).
 */
class SmartDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val s = c.settings.current
        if (!s.smartDownloads) return Result.success()
        val quality = s.audioQuality
        val now = System.currentTimeMillis()
        val downloads = c.downloads.downloads.value

        // Plays the listener left out of their taste don't count.
        val events = c.db.plays().tasteEventsSince(now - 120L * 86_400_000)
        val songIds = events.map { it.songId }.distinct()
        val known = songIds.chunked(500).flatMap { c.db.songs().getAll(it) }.associate { it.id to it.toSong() }.toMutableMap()
        val liked = c.db.songs().liked().map { it.toSong() }
        liked.forEach { known.putIfAbsent(it.id, it) }
        val model = PlayLikelihood(events, known, liked.map { it.id }.toSet(), now)

        // Candidates: what you play and like, plus recommendations seeded from your strongest favourites.
        val scored = HashMap<String, Pair<Song, Double>>()
        fun consider(song: Song, score: Double) {
            if (song.durationSec > 1200 || song.isVideo || song.isLocal || c.library.isDisliked(song.id) || c.exclusions.blocks(song)) return
            val prev = scored[song.id]
            if (prev == null || prev.second < score) scored[song.id] = song to score
        }
        known.values.forEach { consider(it, model.score(it)) }
        val seeds = scored.values.sortedByDescending { it.second }.take(12)
        for ((seed, seedScore) in seeds) {
            if (isStopped) break
            runCatching { c.ytm.next(seed.id, "RDAMVM${seed.id}") }.getOrNull()?.songs?.forEachIndexed { i, r ->
                consider(r, model.score(r, recommendedFrom = seedScore, rank = i))
            }
        }
        val ranked = scored.values.sortedByDescending { it.second }

        // The full target set for the space allowed (never more than what's free, less 1 GB).
        val sizeOf = { song: Song -> downloads[song.id]?.takeIf { it.state == Download.STATE_COMPLETED }?.bytes ?: estimatedSongBytes(song.durationSec, quality) }
        val free = applicationContext.filesDir.usableSpace - 1_073_741_824L
        val smartIds = c.db.songs().smartDownloads().map { it.id }.toSet()
        val smartBytes = smartIds.sumOf { downloads[it]?.bytes ?: 0L }
        // Picks get up to three quarters of Smart Downloads' space; the songs you stream (the song cache) have the rest.
        val budget = minOf((s.offlineGb * PICK_SHARE * 1_073_741_824.0).toLong(), free + smartBytes).coerceAtLeast(0)
        val target = LinkedHashMap<String, Pair<Song, Double>>()
        var total = 0L
        for ((song, score) in ranked) {
            val size = sizeOf(song)
            if (total + size > budget) continue
            target[song.id] = song to score
            total += size
        }

        // A run adds at most a slice — about an eighth of the space (150 MB – 1.5 GB), best first —
        // and only every ~10 hours (or right after the setting changes), so it fills over days.
        val prefs = applicationContext.getSharedPreferences("smart_downloads", Context.MODE_PRIVATE)
        val canAdd = inputData.getBoolean(KEY_FORCE, false) || now - prefs.getLong("last_added", 0) > 10 * 3_600_000L
        val slice = if (!canAdd) 0L else (budget / 8).coerceIn(150L * 1_048_576, 1_536L * 1_048_576)
        val have = { id: String -> downloads[id]?.state.let { it == Download.STATE_COMPLETED || it == Download.STATE_DOWNLOADING } }
        val toAdd = ArrayList<Song>()
        var added = 0L
        for ((song, _) in target.values) {
            if (have(song.id) || added >= slice) continue
            toAdd += song
            added += sizeOf(song)
        }

        // Queued smart downloads that didn't make this slice are cancelled (they'll come back
        // in a later run if they still rank); finished ones that fell out of the target rotate
        // out a few at a time.
        val addIds = toAdd.map { it.id }.toSet()
        var removed = 0
        var rotated = 0
        smartIds.forEach { id ->
            val st = downloads[id]?.state
            when {
                c.library.isDisliked(id) -> { c.downloads.remove(id, byListener = false); removed++ }
                st == Download.STATE_QUEUED && id !in addIds && canAdd -> { c.downloads.remove(id, byListener = false); removed++ }
                st == Download.STATE_QUEUED && id !in target -> { c.downloads.remove(id, byListener = false); removed++ }
                st == Download.STATE_COMPLETED && id !in target && rotated < 25 -> { c.downloads.remove(id, byListener = false); rotated++ }
            }
        }
        toAdd.forEach { song ->
            c.db.songs().upsert(song.toEntity(c.db.songs().get(song.id)).copy(smartDownload = true))
            c.downloads.download(song)
        }
        if (toAdd.isNotEmpty()) prefs.edit().putLong("last_added", now).apply()
        c.downloads.refreshSmartIds()
        Log.i("SmartDownloads", "target ${target.size} songs (${total / 1_048_576} MB of ${budget / 1_048_576} MB); adding ${toAdd.size} (${added / 1_048_576} MB), cancelled $removed, rotated out $rotated")
        return Result.success()
    }

    companion object {
        /** The share of Smart Downloads' space its picks may fill; the song cache keeps the rest. */
        const val PICK_SHARE = 0.75

        /** Wi-Fi only, unless Smart Downloads may use mobile data. */
        private fun constraints(cellular: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (cellular) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()

        /** [cellular]: whether it may run on mobile data (the setting, unless it's just been changed). */
        fun schedule(context: Context, enabled: Boolean, cellular: Boolean = context.container.settings.current.smartDownloadsCellular) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork("smart_downloads")
                return
            }
            val req = PeriodicWorkRequestBuilder<SmartDownloadWorker>(12, TimeUnit.HOURS).setConstraints(constraints(cellular)).build()
            wm.enqueueUniquePeriodicWork("smart_downloads", ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        private const val KEY_FORCE = "force"

        /** [force] lets this run add a slice even if one was added recently (e.g. the size setting changed). */
        fun runNow(context: Context, force: Boolean = true, cellular: Boolean = context.container.settings.current.smartDownloadsCellular) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "smart_downloads_now", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SmartDownloadWorker>()
                    .setConstraints(constraints(cellular))
                    .setInputData(androidx.work.workDataOf(KEY_FORCE to force))
                    .build(),
            )
        }
    }
}
