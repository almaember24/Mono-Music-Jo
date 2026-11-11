package com.prism.music.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.prism.music.data.stream.StreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Works out how far a music video's audio is shifted from the album version
 * (intros, skits, edits), so lyrics timed to the song line up with the video.
 *
 * It fetches the opening minutes of both (smallest audio stream), decodes them,
 * turns each into a 100 Hz fingerprint of energy rises in 16 frequency bands
 * (drum hits, bass notes and syllables land in different bands), and finds the
 * shift where the two fingerprints agree best.
 */
class VideoSync(
    private val context: Context,
    private val http: OkHttpClient,
    private val streams: StreamResolver,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("video_sync", Context.MODE_PRIVATE)
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val inFlight = ConcurrentHashMap<String, Deferred<Long?>>()
    /** Duration of one fingerprint frame (hop / sample rate), ~10 ms. */
    @Volatile private var frameMs = 10.0

    private fun key(songId: String, videoId: String) = "$songId|$videoId"

    /** The listener watched a music video just now. */
    fun noteWatched() = prefs.edit().putLong(WATCHED_AT, System.currentTimeMillis()).apply()

    /**
     * Whether the listener watches music videos, so songs are measured against theirs ahead of time. For someone who
     * doesn't, a measure for every song on Wi-Fi was minutes of audio fetched and decoded for a button never tapped
     * (A16: about as much work as the song's own scan). The first tap measures it then, for the lyrics; the measure takes
     * longer than the seconds in which [PlaybackService] will still move the video (#203), so that video starts at the
     * song's own time. Songs after it are measured ahead again.
     */
    fun watchesVideos(): Boolean = watchesLately(prefs.getLong(WATCHED_AT, 0L), System.currentTimeMillis())

    /** Forgets any result for this pair so the next request measures again. */
    fun forget(songId: String, videoId: String) {
        failed -= key(songId, videoId)
        prefs.edit().remove(key(songId, videoId)).remove("x:" + key(songId, videoId)).apply()
    }

    /** Video time minus song time, in ms, if already measured. */
    fun cached(songId: String, videoId: String): Long? =
        if (songId == videoId) 0L else prefs.getLong(key(songId, videoId), Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }

    /**
     * Measures (or recalls) the lag; null when the two can't be matched confidently.
     * One measurement per pair runs at a time, in [scope], so a caller giving up early doesn't cancel it.
     */
    suspend fun lagMs(songId: String, videoId: String): Long? {
        cached(songId, videoId)?.let { return it }
        val k = key(songId, videoId)
        if (k in failed) return null
        // A pair that didn't line up is left alone for a week, not measured again on every launch.
        if (System.currentTimeMillis() - prefs.getLong("x:$k", 0L) < UNMATCHED_RETRY_MS) { failed += k; return null }
        return inFlight.computeIfAbsent(k) {
            scope.async {
                val started = System.nanoTime()
                AudioDiagnostics.log("background", "video sync of $songId → $videoId started")
                try {
                    val lag = measure(songId, videoId)
                    // Only a real "these don't line up" is remembered; network errors can be retried.
                    if (lag == null) { failed += k; prefs.edit().putLong("x:$k", System.currentTimeMillis()).apply() }
                    else prefs.edit().putLong(k, lag).apply()
                    lag
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "sync $k failed", e)
                    null
                } finally {
                    AudioDiagnostics.log("background", "video sync of $songId ended after ${(System.nanoTime() - started) / 1_000_000} ms")
                    inFlight.remove(k)
                }
            }
        }.await()
    }

    private suspend fun measure(songId: String, videoId: String): Long? = coroutineScope {
        val (songSec, videoSec) = withContext(analysis) {
            runCatching { streams.analysisAudio(songId).durationSec }.getOrDefault(0) to runCatching { streams.analysisAudio(videoId, video = true).durationSec }.getOrDefault(0)
        }
        val seconds = videoSeconds(songSec, videoSec)
        val song = async(analysis) { fingerprintOf(songId, SONG_SECONDS) }
        val video = async(analysis) { fingerprintOf(videoId, seconds, video = true) }
        val s = song.await() ?: return@coroutineScope null
        val v = video.await() ?: return@coroutineScope null
        ensureActive()
        val lag = withContext(analysis) { align(s, v, frameMs) }
        val kept = lag?.takeIf { fits(it, songSec, videoSec) }
        Log.i(TAG, "sync $songId -> $videoId: ${lag}ms${if (lag != null && kept == null) " (doesn't fit their lengths)" else ""}")
        AudioDiagnostics.log(
            "background",
            "video sync of $songId → $videoId: " + when {
                lag == null -> "no match"
                kept == null -> "${lag / 1000.0} s, more than the video's ${videoSec - songSec} s difference in length: not used"
                else -> "the video is ${lag / 1000.0} s ${if (lag >= 0) "behind" else "ahead"}"
            },
        )
        kept
    }

    /**
     * Two background-priority threads for the downloading, decoding and matching: a long video is minutes of audio,
     * and on the shared pools it competed with playback and the UI.
     */
    private val analysis = java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "VideoSync")
    }.asCoroutineDispatcher()

    private fun fingerprintOf(id: String, seconds: Int, video: Boolean = false): Array<FloatArray>? {
        MixTempo.ease()
        val a = streams.analysisAudio(id, video)
        val want = if (a.durationSec > 0) (a.contentLength * (seconds + 5) / a.durationSec * 1.15).toLong() + 96_000 else 2_500_000
        val end = if (a.contentLength > 0) minOf(a.contentLength, want) - 1 else want
        val file = File(context.cacheDir, "sync_$id.part")
        try {
            http.newCall(Request.Builder().url(a.url).header("Range", "bytes=0-$end").build()).execute().use { r ->
                if (!r.isSuccessful) return null
                file.outputStream().use { out -> r.body.byteStream().copyTo(out) }
            }
            val pcm = decodePcm(file, seconds) ?: return null
            frameMs = (pcm.rate / 100) * 1000.0 / pcm.rate
            return bandFeatures(pcm.samples, pcm.rate, pcm.count)
        } finally {
            file.delete()
        }
    }

    private class Pcm(val samples: FloatArray, val count: Int, val rate: Int)

    /**
     * The first [seconds] of audio as mono PCM, decimated to roughly 11–12 kHz. One array for it (it used to be made
     * twice over and copied once more: 27 MB for a 190-second video).
     */
    private fun decodePcm(file: File, seconds: Int): Pcm? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(file.path)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!).also { codec = it }
            dec.configure(fmt, null, null, 0)
            dec.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            var factor = max(1, rate / 11025)
            var out = FloatArray(seconds * (rate / factor) + 1)
            var count = 0
            val mono = Downmix()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (count < out.size) {
                if (!inputDone) {
                    val i = dec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val size = runCatching { ex.readSampleData(dec.getInputBuffer(i)!!, 0) }.getOrDefault(-1)
                        if (size < 0) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                MixTempo.ease()
                val o = dec.dequeueOutputBuffer(info, 5_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = dec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    if (count == 0) {
                        factor = max(1, rate / 11025)
                        val size = seconds * (rate / factor) + 1
                        if (out.size != size) out = FloatArray(size)
                    }
                } else if (o >= 0) {
                    val buf = dec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val frames = info.size / (channels * (if (isFloat) 4 else 2))
                    mono.add(buf, frames, channels, isFloat, factor) { v -> if (count < out.size) out[count++] = v }
                    dec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (inputDone && o == MediaCodec.INFO_TRY_AGAIN_LATER && count > 0) {
                    // A truncated file can stall the decoder at its end.
                    break
                }
            }
            val sr = rate / factor
            if (count < sr * 30) return null
            if (DUMP) runCatching {
                val bb = java.nio.ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until count) bb.putFloat(out[i])
                File(context.getExternalFilesDir(null), "pcm_${file.nameWithoutExtension}_$sr.f32").writeBytes(bb.array())
            }
            return Pcm(out, count, sr)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }

    companion object {
        private const val TAG = "VideoSync"
        private const val DUMP = false
        /** Diagnostics hook (tests print it). */
        var log: (String) -> Unit = { Log.d(TAG, it) }
        /**
         * Whether a measured lag (video time minus song time) can be real for a song of [songSec] and a video of
         * [videoSec]: an intro can't be longer than what the video adds, nor a cut start longer than what it leaves out
         * (with [LENGTH_SLACK_MS] for fades and endings). GitHub #180: switching to the video jumped ~40 s ahead, a
         * match on a repeated chorus. Unknown lengths aren't held against it.
         */
        internal fun fits(lagMs: Long, songSec: Long, videoSec: Long): Boolean {
            if (songSec <= 0 || videoSec <= 0) return true
            val extra = (videoSec - songSec) * 1000
            return lagMs <= maxOf(0L, extra) + LENGTH_SLACK_MS && lagMs >= -(maxOf(0L, -extra) + LENGTH_SLACK_MS)
        }

        /** Watched a video in the last [WATCHED_LATELY_MS]. */
        internal fun watchesLately(watchedAt: Long, now: Long) = watchedAt > 0 && now - watchedAt < WATCHED_LATELY_MS
        private const val WATCHED_AT = "watched_at"
        private const val WATCHED_LATELY_MS = 14 * 86_400_000L

        private const val LENGTH_SLACK_MS = 8_000L
        const val SONG_SECONDS = 70
        const val VIDEO_SECONDS = 190
        private const val MAX_VIDEO_SECONDS = 540
        private const val UNMATCHED_RETRY_MS = 7 * 86_400_000L

        /**
         * How much of the video to listen to. 190 s finds intros up to ~2 min; a video much longer than its song
         * (Kendrick Lamar's "Alright" opens with minutes of film) can start the song later than that, so the window
         * grows by the extra length, up to 9 minutes.
         */
        fun videoSeconds(songSec: Long, videoSec: Long): Int {
            if (songSec <= 0 || videoSec <= 0) return VIDEO_SECONDS
            val extra = videoSec - songSec
            val cap = max(VIDEO_SECONDS.toLong(), minOf(videoSec + 5, MAX_VIDEO_SECONDS.toLong()))
            return (extra + SONG_SECONDS + 20).coerceIn(VIDEO_SECONDS.toLong(), cap).toInt()
        }
        private const val BANDS = 16
        /** FFT window of [bandFeatures], in samples: a frame's energy is that of the window starting at it. */
        const val WINDOW = 512

        private fun fft(re: FloatArray, im: FloatArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
            }
            var len = 2
            while (len <= n) {
                val ang = -2 * PI / len
                val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
                var i = 0
                while (i < n) {
                    var cr = 1f; var ci = 0f
                    for (k in 0 until len / 2) {
                        val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                        val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                        re[i + k + len / 2] = re[i + k] - ar; im[i + k + len / 2] = im[i + k] - ai
                        re[i + k] += ar; im[i + k] += ai
                        val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                    }
                    i += len
                }
                len = len shl 1
            }
        }

        /** Log energy in 16 log-spaced bands (80 Hz – 5 kHz), every 10 ms. Indexed [band][frame]. */
        fun bandFeatures(pcm: FloatArray, sr: Int, count: Int = pcm.size): Array<FloatArray> {
            val hop = sr / 100
            val frames = max(0, (count - WINDOW) / hop)
            val edges = IntArray(BANDS + 1) { b ->
                val f = 80.0 * (5000.0 / 80.0).pow(b.toDouble() / BANDS)
                (f * WINDOW / sr).toInt().coerceIn(1, WINDOW / 2)
            }
            val hann = FloatArray(WINDOW) { (0.5 - 0.5 * cos(2 * PI * it / (WINDOW - 1))).toFloat() }
            val outBands = Array(BANDS) { FloatArray(frames) }
            val re = FloatArray(WINDOW); val im = FloatArray(WINDOW)
            for (t in 0 until frames) {
                val off = t * hop
                for (i in 0 until WINDOW) { re[i] = pcm[off + i] * hann[i]; im[i] = 0f }
                fft(re, im)
                for (b in 0 until BANDS) {
                    var e = 0f
                    for (k in edges[b] until max(edges[b] + 1, edges[b + 1])) e += re[k] * re[k] + im[k] * im[k]
                    outBands[b][t] = ln(e + 1e-6f)
                }
            }
            return outBands
        }

        /** Rises in each band's energy (onsets), pooled over [pool] frames. */
        private fun onsets(bands: Array<FloatArray>, pool: Int): Array<FloatArray> = Array(bands.size) { b ->
            val x = bands[b]
            val n = x.size / pool
            FloatArray(n) { i ->
                var s = 0f
                for (j in i * pool until i * pool + pool) if (j >= 2) s += max(0f, x[j] - x[j - 2])
                s
            }
        }

        /** Multi-band normalised cross-correlation of x (song excerpt) at each start offset in y. */
        private fun ncc(x: Array<FloatArray>, y: Array<FloatArray>, lagFrom: Int = 0, lagTo: Int = Int.MAX_VALUE): FloatArray {
            val l = x[0].size
            val lags = y[0].size - l + 1
            if (lags <= 0) return FloatArray(0)
            val xc = Array(x.size) { b -> val m = x[b].average().toFloat(); FloatArray(l) { x[b][it] - m } }
            val sx = sqrt(xc.sumOf { r -> r.sumOf { (it * it).toDouble() } })
            val pre = Array(y.size) { b -> DoubleArray(y[b].size + 1).also { p -> for (i in y[b].indices) p[i + 1] = p[i] + y[b][i] } }
            val pre2 = Array(y.size) { b -> DoubleArray(y[b].size + 1).also { p -> for (i in y[b].indices) p[i + 1] = p[i] + y[b][i].toDouble() * y[b][i] } }
            val out = FloatArray(lags) { Float.NEGATIVE_INFINITY }
            for (k in max(0, lagFrom) until minOf(lags, lagTo)) {
                var dot = 0.0
                var varY = 0.0
                for (b in x.indices) {
                    val xb = xc[b]; val yb = y[b]
                    var d = 0.0
                    for (i in 0 until l) d += xb[i] * yb[k + i]
                    dot += d
                    val s = pre[b][k + l] - pre[b][k]
                    varY += (pre2[b][k + l] - pre2[b][k]) - s * s / l
                }
                out[k] = if (varY <= 1e-9 || sx <= 1e-9) 0f else (dot / (sx * sqrt(varY))).toFloat()
            }
            return out
        }

        /**
         * Each band's loudness relative to its own recent average (so mastering
         * and volume differences cancel out), pooled over [pool] frames and
         * scaled to unit variance per band.
         */
        private fun contrast(bands: Array<FloatArray>, pool: Int, windowFrames: Int): Array<FloatArray> = Array(bands.size) { b ->
            val x = bands[b]
            val n = x.size / pool
            val p = FloatArray(n) { i -> var s = 0f; for (j in i * pool until i * pool + pool) s += x[j]; s / pool }
            val w = max(1, windowFrames / pool)
            val pre = DoubleArray(n + 1).also { for (i in 0 until n) it[i + 1] = it[i] + p[i] }
            val out = FloatArray(n) { i ->
                val lo = max(0, i - w / 2); val hi = minOf(n, i + w / 2 + 1)
                (p[i] - (pre[hi] - pre[lo]) / (hi - lo)).toFloat()
            }
            val sd = sqrt(out.sumOf { (it * it).toDouble() } / max(1, n)).toFloat().takeIf { it > 1e-6f } ?: 1f
            for (i in out.indices) out[i] /= sd
            out
        }

        /**
         * Lag in ms (video minus song) that best places the song's 10–55 s
         * excerpt in the video, or null when no placement clearly wins.
         */
        fun align(song: Array<FloatArray>, video: Array<FloatArray>, frameMs: Double = 10.0): Long? {
            val frames = song[0].size
            val from = minOf(1000, frames / 5)
            val to = minOf(frames, from + 4500)
            if (to - from < 1500) return null
            // Coarse search at 50 ms…
            val pool = 5
            val cs = contrast(song, pool, 150).map { it.copyOfRange(from / pool, to / pool) }.toTypedArray()
            val coarse = ncc(cs, contrast(video, pool, 150))
            if (coarse.isEmpty()) return null
            var best = coarse.indices.maxBy { coarse[it] }
            var second = Float.NEGATIVE_INFINITY
            for (i in coarse.indices) if (abs(i - best) > 20 && coarse[i] > second) second = coarse[i]
            log("align coarse best=${coarse[best]} second=$second lag=${(best * pool - from) * 10}ms")
            if (coarse[best] < MIN_SCORE || coarse[best] - second < MIN_MARGIN) return null
            // …then refine to 10 ms around the winner.
            val fs = contrast(song, 1, 150).map { it.copyOfRange(from, to) }.toTypedArray()
            val fine = ncc(fs, contrast(video, 1, 150), best * pool - 8, best * pool + 9)
            best = fine.indices.maxBy { fine[it] }
            return ((best - from) * frameMs).roundToLong()
        }

        private const val MIN_SCORE = 0.35f
        private const val MIN_MARGIN = 0.08f
    }
}
