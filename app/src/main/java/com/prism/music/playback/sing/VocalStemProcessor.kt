package com.prism.music.playback.sing

import android.os.Process
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.prism.music.playback.videoModeId
import java.nio.ByteBuffer
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Sing's processor: takes the song's separated vocals ([StemStore]) out of the music as it plays, as much as Sing's
 * level says (output = music − (1 − level) × vocals). Where they don't reach yet, the quick way's stand-ins (done in
 * seconds, 1.8.x) are taken out instead, so Sing takes effect at once and gets better as the separation goes.
 *
 * Subtracting only works when the vocals line up with the music to the sample, and two decodes of a file can differ
 * by a few milliseconds at the start. So the processor never assumes: a background thread keeps the last seconds of
 * what's playing and finds where they match the vocals best (cross-correlation over ±150 ms); only once they clearly
 * match does the subtraction glide in, and it glides out again if they stop matching. Which song and where in it
 * comes from the player (on every seek and new song, [onFlush]); across a gapless move to the next song, which the
 * player doesn't announce here, the processor moves along the playlist itself.
 */
@UnstableApi
class VocalStemProcessor(private val sing: Source) : BaseAudioProcessor() {
    /** What the processor needs from Sing: whether it's on, how much voice to leave, and the vocals. */
    interface Source {
        val onNow: Boolean
        val levelNow: Float
        val store: StemStore
        /** The quick way's stand-ins, used wherever [store]'s vocals don't reach yet (1.8.x). */
        val quickStore: StemStore? get() = store.quick
    }

    /** One store's vocals for the playing song, opened again every quarter second while they're missing or short. */
    private inner class Stems(private val store: () -> StemStore?) {
        var reader: StemStore.Reader? = null
        var readerFor: String? = null
        var retryIn = 0
        var short = false
        var buf = FloatArray(0)

        /** [frames] of [id]'s vocals from song frame [at] into [buf]; how many are real (0 with none). */
        fun read(id: String, at: Long, frames: Int): Int {
            if (readerFor == id && (reader == null || short)) retryIn -= frames
            if (readerFor != id || retryIn <= 0) {
                reader?.close()
                reader = store()?.reader(id)?.takeIf { it.meta.rate == rate }
                readerFor = id
                retryIn = rate / 4
            }
            if (buf.size < frames * 2) buf = FloatArray(frames * 2)
            val r = reader
            val real = r?.read(at, frames, buf) ?: run { java.util.Arrays.fill(buf, 0, frames * 2, 0f); 0 }
            short = real < frames
            return real
        }

        fun close() { reader?.close(); reader = null; readerFor = null }
    }

    private val full = Stems { sing.store }
    private val quick = Stems { sing.quickStore }
    /** How much of the song's own vocals (rather than the quick way's) is subtracted, gliding between the two. */
    private var blend = 1f

    /** The player's repeat and shuffle modes, for working out which song follows (set from the playback thread). */
    @Volatile var repeatMode = Player.REPEAT_MODE_OFF
    @Volatile var shuffle = false

    private var rate = 48_000
    private var channels = 2
    private var float = false

    private var timeline: Timeline? = null
    private var periodUid: Any? = null
    private var songId: String? = null
    private var periodFrames = -1L
    /** The song frame the next input sample is (in the song's own frames). */
    private var position = 0L
    private var inEnergy = 0.0
    private var outEnergy = 0.0
    private var energyFrames = 0L

    private var fade = 0f
    private var gain = 0f

    // What's been playing (mono), for the alignment thread.
    private val ringLock = Any()
    private var ring = FloatArray(0)
    private var ringEnd = 0L
    private var ringSong: String? = null
    private var ringFilled = 0

    @Volatile private var lag = 0L
    @Volatile private var matchedFor: String? = null
    /** A seek, a new song or a change of key: the alignment is checked again at once rather than in [RECHECK_MS]. */
    @Volatile private var recheck = false

    /** The offset the vocals are read at, and the song it was found for (null until they line up). */
    val alignment: Pair<String?, Long> get() = matchedFor to lag

    @Volatile private var alignerParked = false
    private val aligner = Thread({ alignLoop() }, "PrismSingAlign").apply { isDaemon = true; start() }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) return AudioProcessor.AudioFormat.NOT_SET
        rate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        float = enc == C.ENCODING_PCM_FLOAT
        synchronized(ringLock) { ring = FloatArray(rate * RING_SECONDS); ringFilled = 0 }
        return inputAudioFormat
    }

    /**
     * The player's timeline since the last flush: songs put in or taken out after the playing one (Play next, queue
     * edits) don't flush the processors, and the gapless move used to go by the timeline the last flush brought, so the
     * song that really came next was taken for the old next one (1.8.0 review). Period uids stay the same across it.
     */
    @Volatile private var newTimeline: Timeline? = null

    /** The player's timeline changed (from the player's listener; any thread). */
    fun timelineChanged(timeline: Timeline) {
        newTimeline = timeline
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        newTimeline = null
        val tl = streamMetadata.timeline
        val uid = streamMetadata.periodUid
        timeline = tl.takeIf { !it.isEmpty }
        periodUid = uid
        position = if (streamMetadata.positionOffsetUs == C.TIME_UNSET) 0 else streamMetadata.positionOffsetUs * rate / 1_000_000
        identify()
        fade = 0f
        synchronized(ringLock) { ringFilled = 0 }
        recheck = true
        Log.d(TAG, "flush: song=$songId at ${position * 1000 / rate} ms (period ${periodFrames * 1000 / rate.coerceAtLeast(1)} ms)")
    }

    override fun onReset() {
        full.close(); quick.close()
        timeline = null; periodUid = null; songId = null
    }

    /** Which song [periodUid] is, and how long. */
    private fun identify() {
        val tl = timeline
        val uid = periodUid
        songId = null
        periodFrames = -1
        if (tl == null || uid == null) return
        val period = Timeline.Period()
        val index = tl.getIndexOfPeriod(uid)
        if (index == C.INDEX_UNSET) return
        tl.getPeriod(index, period)
        val window = Timeline.Window()
        tl.getWindow(period.windowIndex, window)
        val item = window.mediaItem
        // A music video's sound isn't the song's: Sing leaves it alone.
        songId = item.mediaId.takeIf { it.isNotBlank() && item.videoModeId == null }
        periodFrames = if (period.durationUs == C.TIME_UNSET) -1 else period.durationUs * rate / 1_000_000
    }

    /** The song played through to the end without the player saying: on to the one that follows. */
    private fun advance() {
        val tl = timeline ?: return
        val uid = periodUid ?: return
        val index = tl.getIndexOfPeriod(uid)
        if (index == C.INDEX_UNSET) return
        val next = tl.getNextPeriodIndex(index, Timeline.Period(), Timeline.Window(), repeatMode, shuffle)
        position -= periodFrames
        if (next == C.INDEX_UNSET) { periodUid = null; songId = null; periodFrames = -1; return }
        periodUid = tl.getUidOfPeriod(next)
        identify()
        fade = 0f
        synchronized(ringLock) { ringFilled = 0 }
        recheck = true
        Log.d(TAG, "gapless move to $songId")
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val t0 = if (com.prism.music.playback.AudioDiagnostics.enabled) System.nanoTime() else 0L
        // The queue changed: the same song, in the timeline as it is now (what follows it, and how long it is).
        newTimeline?.let { tl ->
            newTimeline = null
            timeline = tl.takeIf { !it.isEmpty }
            identify()
        }
        val frameBytes = (if (float) 4 else 2) * channels
        var frames = bytes / frameBytes
        val total = frames
        val out = replaceOutputBuffer(bytes)
        while (frames > 0) {
            if (periodFrames > 0 && position >= periodFrames) advance()
            // Up to the end of this song, so the next one starts with its own vocals.
            val n = if (periodFrames > 0) minOf(frames.toLong(), (periodFrames - position).coerceAtLeast(1)).toInt() else frames
            processRun(inputBuffer, out, n)
            frames -= n
        }
        out.flip()
        if (t0 != 0L) com.prism.music.playback.AudioDiagnostics.processed("Sing", t0, total, rate)
    }

    private fun processRun(input: ByteBuffer, out: ByteBuffer, frames: Int) {
        val id = songId
        // Sing is off and nothing is being taken out (or gliding out): the audio goes straight through, untouched, and
        // only the place in the song is kept. It used to go through the whole sample loop for nothing.
        if (!sing.onNow && gain < 1e-5f && fade < 1e-4f) {
            gain = 0f; fade = 0f
            val bytes = frames * (if (float) 4 else 2) * channels
            val limit = input.limit()
            input.limit(input.position() + bytes)
            out.put(input)
            input.limit(limit)
            position += frames
            return
        }
        val on = sing.onNow && id != null
        if (alignerParked) java.util.concurrent.locks.LockSupport.unpark(aligner)
        // The vocals may not exist yet when the song starts (Sing was just switched on), or be started afresh in a new
        // file: while they're missing or short of where the song is, they're looked for again every quarter second.
        // Two sets (1.8.x): the song's own (the full or light way) and the quick way's stand-ins, done first in seconds;
        // both come from the same decode, so they line up alike, frame for frame.
        val shift = lag
        val realFull = if (on) full.read(id!!, position + shift, frames) else 0
        val realQuick = if (on) quick.read(id!!, position + shift, frames) else 0
        val haveAny = on && (full.reader != null || quick.reader != null)
        val matched = on && matchedFor == id
        // Subtract only where the vocals are written and known to line up. Mid-song, vocals not written yet (the
        // separation is behind) fade it out; in the song's last moments, what's there is used (the rest reads as 0).
        val nearEnd = periodFrames > 0 && position + frames + rate / 10 >= periodFrames
        fun reaches(real: Int) = real == frames || (nearEnd && real > 0)
        val ownHere = reaches(realFull)
        val target = if (matched && (ownHere || reaches(realQuick))) 1f else 0f
        // The song's own vocals wherever they reach, the quick way's elsewhere: the change glides over ~30 ms, at the
        // same place in the song (nothing is repeated or skipped; only what's subtracted changes).
        val blendTarget = if (ownHere) 1f else 0f
        val vf = full.buf
        val vq = quick.buf
        val level = sing.levelNow
        val glide = 1f - exp(-1f / (0.03f * rate))
        val record = haveAny
        // One buffer kept and reused (a new array for every buffer of audio was garbage for the collector to chase).
        if (record && monoBuf.size < frames) monoBuf = FloatArray(frames)
        val mono = if (record) monoBuf else null
        for (f in 0 until frames) {
            fade += (target - fade) * glide
            gain += ((1f - level) * fade - gain) * glide
            blend += (blendTarget - blend) * glide
            var sum = 0f
            for (ch in 0 until channels) {
                val x = if (float) input.float else input.short / 32768f
                sum += x
                val v = when {
                    !haveAny || gain < 1e-5f -> 0f
                    channels == 1 -> (blend * (vf[f * 2] + vf[f * 2 + 1]) + (1 - blend) * (vq[f * 2] + vq[f * 2 + 1])) * 0.5f
                    ch < 2 -> blend * vf[f * 2 + ch] + (1 - blend) * vq[f * 2 + ch]
                    else -> 0f
                }
                val y = x - gain * v
                if (float) out.putFloat(y) else out.putShort((y * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
                inEnergy += x * x; outEnergy += y * y
            }
            if (mono != null) mono[f] = sum / channels
        }
        energyFrames += frames
        if (energyFrames >= rate * 10L) {
            if (matched) Log.d(TAG, "last 10 s: ${"%.1f".format(10 * kotlin.math.log10(outEnergy / inEnergy.coerceAtLeast(1e-12)))} dB with the vocals taken out (gain ${"%.2f".format(gain)})")
            inEnergy = 0.0; outEnergy = 0.0; energyFrames = 0
        }
        if (mono != null) remember(id!!, mono, frames, position)
        position += frames
    }

    private var monoBuf = FloatArray(0)

    /** Keeps the first [count] of [mono] (song frames from [start]) for the alignment thread; a jump or another song starts afresh. */
    private fun remember(id: String, mono: FloatArray, count: Int, start: Long) = synchronized(ringLock) {
        if (ring.isEmpty() || start < 0) return@synchronized
        if (ringSong != id || ringEnd != start) ringFilled = 0
        ringSong = id
        for (i in 0 until count) ring[((start + i) % ring.size).toInt()] = mono[i]
        ringEnd = start + count
        ringFilled = minOf(ring.size, ringFilled + count)
    }

    // ------------------------------------------------------------------ Alignment

    private fun alignLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        var lastCheck = 0L
        var misses = 0
        while (true) {
            // Till the vocals line up, looked at more often: the first seconds of Sing are the ones that count.
            try { Thread.sleep(if (matchedFor == null) 250 else 400) } catch (_: InterruptedException) { return }
            // Nothing to line up with Sing off: asleep till it's on, rather than up four times a second all day (with
            // music paused, this was the busiest thread in Prism).
            if (!sing.onNow) {
                alignerParked = true
                while (!sing.onNow) {
                    java.util.concurrent.locks.LockSupport.parkNanos(PARK_NS)
                    if (Thread.currentThread().isInterrupted) return
                }
                alignerParked = false
                continue
            }
            val (id, music, end) = synchronized(ringLock) {
                if (ringSong == null || ringFilled < rate * ALIGN_SECONDS) return@synchronized Triple(null, null, 0L)
                val n = rate * ALIGN_SECONDS
                val copy = FloatArray(n)
                for (i in 0 until n) copy[i] = ring[((ringEnd - n + i) % ring.size).toInt()]
                Triple(ringSong, copy, ringEnd)
            }
            if (id == null || music == null) continue
            val locked = matchedFor == id
            val now = System.currentTimeMillis()
            if (locked && !recheck && now - lastCheck < RECHECK_MS) continue
            lastCheck = now
            recheck = false
            val result = runCatching { match(id, music, end - music.size) }.getOrNull() ?: continue
            if (result.second >= MIN_MATCH) {
                misses = 0
                if (!locked || lag != result.first) Log.i(TAG, "lined up $id: offset ${result.first} frames, match ${"%.2f".format(result.second)}")
                lag = result.first
                matchedFor = id
            } else if (result.second >= 0f && locked && ++misses >= 2) {
                Log.i(TAG, "$id no longer lines up (match ${"%.2f".format(result.second)}): vocals left in")
                matchedFor = null
            }
        }
    }

    /**
     * Where [music] (mono, starting at song frame [start]) best matches the vocals: the offset to read the vocals at,
     * and how strongly they match there (normalized cross-correlation; -1 when the vocals are too quiet to say).
     */
    private fun match(id: String, music: FloatArray, start: Long): Pair<Long, Float>? =
        // The song's own vocals where they reach this far, else the quick way's (they line up alike).
        match(sing.store, id, music, start) ?: sing.quickStore?.let { match(it, id, music, start) }

    private fun match(store: StemStore, id: String, music: FloatArray, start: Long): Pair<Long, Float>? {
        val r = store.reader(id) ?: return null
        r.use { reader ->
            val maxLag = rate * MAX_LAG_MS / 1000
            val span = music.size + 2 * maxLag
            val stereo = FloatArray(span * 2)
            if (reader.read(start - maxLag, span, stereo) < span - maxLag) return null
            val vocals = FloatArray(span) { (stereo[it * 2] + stereo[it * 2 + 1]) * 0.5f }
            var ve = 0.0; for (v in vocals) ve += v * v
            var me = 0.0; for (m in music) me += m * m
            if (ve / span < 1e-6 || me / music.size < 1e-6) return 0L to -1f
            var size = 1
            while (size < span) size = size shl 1
            val fft = Fft(size)
            val ar = DoubleArray(size); val ai = DoubleArray(size)
            val br = DoubleArray(size); val bi = DoubleArray(size)
            for (i in music.indices) ar[i] = music[i].toDouble()
            for (i in vocals.indices) br[i] = vocals[i].toDouble()
            fft.transform(ar, ai); fft.transform(br, bi)
            // conj(A)·B: c[L] = Σ a[n]·b[n+L]
            for (k in 0 until size) {
                val re = ar[k] * br[k] + ai[k] * bi[k]
                val im = ar[k] * bi[k] - ai[k] * br[k]
                ar[k] = re; ai[k] = im
            }
            fft.transform(ar, ai, inverse = true)
            var best = 0; var bestV = Double.NEGATIVE_INFINITY
            for (l in 0..2 * maxLag) if (ar[l] > bestV) { bestV = ar[l]; best = l }
            // The vocals' energy over the stretch that lined up.
            var ve2 = 0.0; for (i in best until best + music.size) ve2 += vocals[i] * vocals[i]
            val rho = (bestV / sqrt(me * ve2.coerceAtLeast(1e-12))).toFloat()
            return (best - maxLag).toLong() to rho
        }
    }

    companion object {
        private const val TAG = "PrismSing"
        private const val RING_SECONDS = 4
        /** How much of what's playing is matched against the vocals (2 s, 1.8.x: 3 s held the first effect back). */
        private const val ALIGN_SECONDS = 2
        private const val MAX_LAG_MS = 150
        /** How strongly the music must match the vocals to subtract them (the vocals are part of the music). */
        private const val MIN_MATCH = 0.2f
        private const val RECHECK_MS = 20_000L
        /** With Sing off the aligner sleeps this long at a time; switching Sing on wakes it at once. */
        private const val PARK_NS = 30_000_000_000L
    }
}
