package com.prism.music.data.recognize

import com.prism.music.R
import com.prism.music.tr
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.prism.music.AppContainer
import com.prism.music.container
import com.prism.music.data.Notifications
import com.prism.music.data.db.RecognitionEntity
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.playback.sing.Resampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Base64
import kotlin.math.sqrt

/**
 * Song recognition: listens through the microphone (up to 12 s, asking Shazam with what's heard at 4, 8 and 12 s and
 * stopping at the first match), then finds the song on YouTube Music. The sound never leaves the phone: only its
 * fingerprint ([ShazamSignature]) does. Offline, the fingerprint is kept and asked about once there's a network
 * ([RecognitionWorker]), with a notification for each song found.
 */
class Recognizer(private val c: AppContainer) {
    sealed interface State {
        data object Idle : State
        /** [heardMs] of up to [MAX_MS]; [level] 0..1, how loud it is (for the animation). */
        data class Listening(val heardMs: Long, val level: Float) : State
        data class Found(val recognition: RecognitionEntity, val song: Song?) : State
        data object NotFound : State
        /** Offline: kept, and asked about when there's a network. */
        data object Saved : State
        data object RateLimited : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private val shazam by lazy { Shazam(c.http) }
    private var job: Job? = null
    private var resumeAfter = false

    val history = c.db.recognitions().historyFlow()

    fun reset() { if (job?.isActive != true) _state.value = State.Idle }

    /** Starts listening ([pausePrism]: Prism's own playing pauses meanwhile, and resumes after). Needs RECORD_AUDIO. */
    fun start(pausePrism: Boolean) {
        if (job?.isActive == true) return
        resumeAfter = pausePrism && c.player.isPlaying.value
        if (resumeAfter) c.player.setPlaying(false)
        _state.value = State.Listening(0, 0f)
        job = c.scope.launch {
            try {
                _state.value = listen()
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = State.Idle
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "recognition failed", e)
                _state.value = State.Failed(e.message ?: tr(R.string.couldn_t_listen))
            } finally {
                if (resumeAfter) { resumeAfter = false; withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { c.player.setPlaying(true) } }
            }
        }
    }

    fun cancel() { job?.cancel() }

    @SuppressLint("MissingPermission")
    private suspend fun listen(): State = withContext(Dispatchers.IO) {
        val (rec, rate) = openRecorder() ?: return@withContext State.Failed(tr(R.string.the_microphone_isn_t_available))
        val all = ShortArray(rate * MAX_MS.toInt() / 1000)
        var have = 0
        val chunk = ShortArray(rate / 10)
        val tries = ArrayList<kotlinx.coroutines.Deferred<ShazamResult?>>()
        var nextTry = 0
        var offline = false
        var rateLimited = false
        try {
            rec.startRecording()
            while (isActive && have < all.size) {
                val n = rec.read(chunk, 0, minOf(chunk.size, all.size - have))
                if (n <= 0) { delay(10); continue }
                System.arraycopy(chunk, 0, all, have, n)
                have += n
                var sum = 0.0
                for (i in 0 until n) sum += chunk[i] * chunk[i].toDouble()
                val level = (sqrt(sum / n) / 6000).toFloat().coerceIn(0f, 1f)
                _state.value = State.Listening(have * 1000L / rate, level)
                // What's in by now goes to Shazam (in the background; listening goes on).
                if (nextTry < TRIES_MS.size && have * 1000L / rate >= TRIES_MS[nextTry]) {
                    nextTry++
                    if (c.network.online.value) {
                        val samples = to16k(all, have, rate)
                        tries += async { runCatching { shazam.recognize(ShazamSignature.of(samples)) }.onFailure { offline = true }.getOrNull() }
                    } else offline = true
                }
                tries.firstOrNull { it.isCompleted && it.getCompleted() is ShazamResult.Match }?.let { return@withContext found(it.getCompleted() as ShazamResult.Match) }
                if (tries.any { it.isCompleted && it.getCompleted() == ShazamResult.RateLimited }) { rateLimited = true; break }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        // Listening's over: the last answers.
        for (t in tries) {
            val r = t.await()
            if (r is ShazamResult.Match) return@withContext found(r)
            if (r == ShazamResult.RateLimited) rateLimited = true
        }
        when {
            rateLimited -> State.RateLimited
            offline && have >= rate * 3 -> {
                // Kept for later: only the fingerprint.
                val sig = ShazamSignature.of(to16k(all, have, rate))
                c.db.recognitions().insert(RecognitionEntity(at = System.currentTimeMillis(), status = "pending", signature = Base64.getEncoder().encodeToString(sig.encode())))
                RecognitionWorker.schedule(c.app)
                com.prism.music.widget.RecognizeWidgets.refresh(c.app)
                State.Saved
            }
            else -> State.NotFound
        }
    }

    private suspend fun found(m: ShazamResult.Match): State {
        val song = runCatching { youtubeSong(m) }.getOrNull()
        val row = RecognitionEntity(
            at = System.currentTimeMillis(), status = "found", title = m.title, artist = m.artist, album = m.album,
            cover = m.cover, shazamKey = m.key, videoId = song?.id,
        )
        val id = c.db.recognitions().insert(row)
        com.prism.music.widget.RecognizeWidgets.refresh(c.app)
        return State.Found(row.copy(id = id), song)
    }

    /** The song on YouTube Music: the songs search for "title artist", the one whose title and artist agree best. */
    suspend fun youtubeSong(m: ShazamResult.Match): Song? {
        val results = c.ytm.search("${m.title} ${m.artist}", SearchFilter.SONGS).items.filterIsInstance<SongItem>().map { it.song }.take(10)
        val title = norm(m.title)
        val artists = m.artist.split(",", "&", " feat. ", " ft. ", " x ").map { norm(it) }.filter { it.isNotEmpty() }
        fun score(s: Song): Int {
            var p = 0
            val t = norm(s.title)
            if (t == title) p += 3 else if (t.startsWith(title) || title.startsWith(t)) p += 2
            if (s.artists.any { a -> artists.any { norm(a.name) == it || norm(a.name).contains(it) || it.contains(norm(a.name)) } }) p += 3
            return p
        }
        return results.maxByOrNull { score(it) }?.takeIf { score(it) >= 3 } ?: results.firstOrNull()
    }

    /** Audio at 16 kHz, as the fingerprint wants it. */
    private fun to16k(all: ShortArray, have: Int, rate: Int): ShortArray {
        if (rate == ShazamSignature.RATE) return all.copyOf(have)
        val f = FloatArray(have) { all[it] / 32768f }
        val r = Resampler.convert(f, 1, rate, ShazamSignature.RATE)
        return ShortArray(r.size) { (r[it] * 32767).toInt().coerceIn(-32768, 32767).toShort() }
    }

    /** The microphone at 16 kHz mono, or at 48 / 44.1 kHz (brought down to 16 kHz) where 16 kHz isn't offered. */
    @SuppressLint("MissingPermission")
    private fun openRecorder(): Pair<AudioRecord, Int>? {
        for (rate in intArrayOf(16_000, 48_000, 44_100)) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val rec = runCatching {
                AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
            }.getOrNull() ?: continue
            if (rec.state == AudioRecord.STATE_INITIALIZED) return rec to rate
            rec.release()
        }
        return null
    }

    companion object {
        private const val TAG = "PrismRecognize"
        const val MAX_MS = 12_000L
        private val TRIES_MS = longArrayOf(4_000, 8_000, 12_000)

        fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase().replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "").substringBefore(" - "), java.text.Normalizer.Form.NFD)
            .filter { it.isLetterOrDigit() }
    }
}

/** Fingerprints kept while offline, asked about once there's a network; each song found gets a notification. */
class RecognitionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val shazam = Shazam(c.http)
        for (p in c.db.recognitions().pending()) {
            val sig = runCatching { ShazamSignature.decode(Base64.getDecoder().decode(p.signature ?: continue)) }.getOrNull()
            if (sig == null) { c.db.recognitions().delete(p.id); continue }
            val r = runCatching { shazam.recognize(sig) }.getOrElse { return Result.retry() }
            when (r) {
                is ShazamResult.Match -> {
                    val song = runCatching { c.recognizer.youtubeSong(r) }.getOrNull()
                    c.db.recognitions().put(p.copy(status = "found", title = r.title, artist = r.artist, album = r.album, cover = r.cover, shazamKey = r.key, videoId = song?.id, signature = null))
                    Notifications.post(
                        c.app, Notifications.RECOGNITION, (4200 + p.id % 1000).toInt(), tr(R.string.found_it_2, r.title), r.artist,
                        song?.let { "https://music.youtube.com/watch?v=${it.id}" } ?: "prism://recognized",
                    )
                }
                ShazamResult.NoMatch -> c.db.recognitions().put(p.copy(status = "none", signature = null))
                ShazamResult.RateLimited -> return Result.retry()
            }
            com.prism.music.widget.RecognizeWidgets.refresh(applicationContext)
            delay(2_000)
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "recognitions", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RecognitionWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
            )
        }
    }
}
