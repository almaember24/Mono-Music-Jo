package com.prism.music.playback.sing

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.IOException
import java.nio.ByteOrder

/**
 * A song file decoded a piece at a time with the phone's own decoder (as the player does), as stereo floats at the
 * file's own sample rate: mono is doubled, anything beyond two channels is left out. Pieces come about half a second
 * at a time; the decoder is kept fed with every input buffer it has free, and every finished output is taken at once.
 */
class PcmDecoder(context: Context, uri: Uri, /** Which of the file's audio tracks (the first by default). */ audioTrack: Int = 0) : AutoCloseable {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    /** The decoder in use (for the log and the tests); set as it opens, below. */
    var name: String = ""
        private set
    private var inputDone = false
    private var outputDone = false

    var sampleRate: Int
        private set
    private var channels: Int
    private var floatPcm = false
    /** The song's length in frames, from the file (0 when it doesn't say). */
    val totalFrames: Long

    private var pending = FloatArray(PIECE_FRAMES * 4)
    private var pendingFrames = 0

    init {
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).filter {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }.getOrNull(audioTrack) ?: throw IOException("No audio in the file")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        totalFrames = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) * sampleRate / 1_000_000 else 0L
        codec = open(format)
    }

    /**
     * A decoder for [format]: a software one first (1.8.1, Reddit: Sing on a Google TV Streamer stopped the music, which
     * sat "loading" till Prism was reinstalled). A TV's own audio decoder can be one of a kind; Sing held it for as long as
     * a song took to separate, and the player, needing one as it changed songs or left a video, had none.
     */
    private fun open(format: MediaFormat): MediaCodec {
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        for (name in decoderNames(mime)) {
            val codec = runCatching { MediaCodec.createByCodecName(name) }.getOrNull() ?: continue
            val ok = runCatching { codec.configure(format, null, null, 0); codec.start() }.isSuccess
            if (ok) { this.name = name; return codec }
            runCatching { codec.release() }
        }
        return MediaCodec.createDecoderByType(mime).also { it.configure(format, null, null, 0); it.start(); name = it.name }
    }

    @Volatile private var cancelled = false

    /** Stops a [read] in progress, from any thread: it ends with [Cancelled] (Sing let the song go). */
    fun cancel() { cancelled = true }

    /** [read] was stopped by [cancel]. */
    class Cancelled : IOException("Stopped")

    /** The song frame the first decoded sample is (after [seekTo], wherever the decoder could start: a sync sample). */
    var firstFrame = -1L
        private set

    /**
     * Starts decoding at or just before [us] (the sync sample before it), before the first [read]: Sing separates a
     * song from where it's playing first (1.8.x). [firstFrame] says exactly where it started.
     */
    fun seekTo(us: Long) {
        extractor.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    }

    /** The next decoded piece (interleaved stereo, ~0.5 s), or null at the end. */
    fun read(): FloatArray? {
        var lastProgress = System.nanoTime()
        while (!outputDone && pendingFrames < PIECE_FRAMES) {
            if (cancelled) throw Cancelled()
            // A decoder that takes nothing in and gives nothing out for this long has stopped: given up on, not waited
            // on forever (and held meanwhile).
            if (System.nanoTime() - lastProgress > STALL_NS) throw IOException("The decoder stopped answering")
            var progressed = false
            while (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(0)
                if (inIdx < 0) break
                val buf = codec.getInputBuffer(inIdx)!!
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) {
                    codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
                progressed = true
            }
            while (true) {
                val outIdx = codec.dequeueOutputBuffer(info, 0)
                if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                progressed = true
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    continue
                }
                if (outIdx < 0) continue
                take(outIdx)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { outputDone = true; break }
            }
            if (progressed) lastProgress = System.nanoTime()
            // Nothing to feed and nothing out yet: wait for the decoder (it hands a buffer back as soon as it has one).
            if (!progressed && !outputDone) {
                val outIdx = codec.dequeueOutputBuffer(info, 5_000)
                if (outIdx >= 0) {
                    lastProgress = System.nanoTime()
                    take(outIdx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
            }
        }
        if (pendingFrames == 0) return null
        val out = pending.copyOf(pendingFrames * 2)
        pendingFrames = 0
        return out
    }

    /** Converts one output buffer to stereo floats on the end of [pending]. */
    private fun take(outIdx: Int) {
        val out = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.nativeOrder())
        out.position(info.offset); out.limit(info.offset + info.size)
        val samples = if (floatPcm) info.size / 4 else info.size / 2
        val frames = samples / channels
        if (firstFrame < 0 && frames > 0) firstFrame = maxOf(0L, Math.round(info.presentationTimeUs * sampleRate / 1e6))
        if ((pendingFrames + frames) * 2 > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, (pendingFrames + frames) * 2))
        var at = pendingFrames * 2
        if (!floatPcm && channels == 2) {
            val s = out.asShortBuffer()
            for (i in 0 until frames * 2) pending[at + i] = s.get() / 32768f
        } else for (f in 0 until frames) {
            var l = 0f; var r = 0f
            for (ch in 0 until channels) {
                val v = if (floatPcm) out.float else out.short / 32768f
                if (ch == 0) l = v else if (ch == 1) r = v
            }
            pending[at] = l
            pending[at + 1] = if (channels == 1) l else r
            at += 2
        }
        pendingFrames += frames
        codec.releaseOutputBuffer(outIdx, false)
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
    }

    companion object {
        private const val PIECE_FRAMES = 24_000
        private const val STALL_NS = 10_000_000_000L

        /** The decoders for [mime] to try, software ones first ([softwareFirst]). */
        fun decoderNames(mime: String): List<String> {
            val all = runCatching { android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos }.getOrDefault(emptyArray())
                .filter { info -> !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
            return softwareFirst(all.map { info ->
                info.name to (if (android.os.Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly else isSoftwareName(info.name))
            })
        }

        /** [decoders] (name, software only?) in the order to try: software first, each group as the system listed it. */
        internal fun softwareFirst(decoders: List<Pair<String, Boolean>>): List<String> =
            decoders.filter { it.second || isSoftwareName(it.first) }.map { it.first } +
                decoders.filterNot { it.second || isSoftwareName(it.first) }.map { it.first }

        /** Android's own decoders, which run on the processor (no limit on how many). */
        internal fun isSoftwareName(name: String) = name.startsWith("c2.android.") || name.startsWith("OMX.google.")
    }
}
