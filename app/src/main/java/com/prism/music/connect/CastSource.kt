package com.prism.music.connect

import com.prism.music.R
import com.prism.music.tr
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.nio.ByteOrder
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * A song's bytes as the player gets them (downloads, then the song cache, then the network), for the phone's own
 * decoder to read at any position. One connection is kept open and read on from; a jump elsewhere reopens it there.
 */
@OptIn(UnstableApi::class)
internal class SongBytes(private val factory: DataSource.Factory, private val id: String) : MediaDataSource() {
    private var source: DataSource? = null
    private var at = -1L
    private var size = -1L

    @Synchronized
    override fun getSize(): Long {
        if (size < 0 && source == null) runCatching { open(0) }
        return size
    }

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (size in 0..position) return -1
        if (source == null || position != at) open(position)
        val n = source!!.read(buffer, offset, length)
        if (n == C.RESULT_END_OF_INPUT) return -1
        at += n
        return n
    }

    private fun open(position: Long) {
        closeSource()
        val s = factory.createDataSource()
        val n = s.open(DataSpec.Builder().setUri("prism://audio/$id").setKey(id).setPosition(position).build())
        if (size < 0 && n != C.LENGTH_UNSET.toLong()) size = position + n
        source = s
        at = position
    }

    private fun closeSource() {
        runCatching { source?.close() }
        source = null
    }

    @Synchronized
    override fun close() = closeSource()
}

/** What [CastDecoder.probe] found out: the decoder's real output rate and the song's length in frames at that rate. */
data class CastProbe(val rate: Int, val frames: Long)

/**
 * Decodes a song for casting as 16-bit stereo, with the phone's own decoders. [setSource] points a fresh extractor at
 * the song (each Range request gets its own decoder). At most a few run at once; a TV opening more waits its turn.
 */
class CastDecoder(private val setSource: (MediaExtractor) -> Unit) {
    /** The output rate (HE-AAC decodes to twice the rate its track says, so it's read off a decoded piece) and length. */
    fun probe(fallbackMs: Long): CastProbe {
        Frames(setSource, 0, 0).use { f ->
            f.read() // the first decoded piece settles the output format
            val rate = f.rate
            val us = f.durationUs.takeIf { it > 0 } ?: (fallbackMs * 1000)
            if (rate <= 0 || us <= 0) throw IOException(tr(R.string.couldn_t_read_the_song_s))
            return CastProbe(rate, us * rate / 1_000_000)
        }
    }

    /** Frames from [startFrame] at [rate] (the probed rate). */
    fun from(startFrame: Long, rate: Int): PcmFrames {
        if (!permits.tryAcquire(20, TimeUnit.SECONDS)) throw IOException(tr(R.string.too_many_decoders_at_once))
        return try {
            object : PcmFrames {
                val f = Frames(setSource, startFrame, rate)
                override fun read() = f.read()
                override fun close() { f.close(); permits.release() }
            }
        } catch (e: Exception) {
            permits.release()
            throw e
        }
    }

    private class Frames(setSource: (MediaExtractor) -> Unit, private val startFrame: Long, private var expectRate: Int) : PcmFrames {
        private val extractor = MediaExtractor()
        private val codec: MediaCodec
        private val info = MediaCodec.BufferInfo()
        private var inputDone = false
        private var outputDone = false
        private var channels: Int
        private var floatPcm = false
        var rate: Int
            private set
        val durationUs: Long
        /** Until the first frame at or after [startFrame] has gone out, pieces are lined up by their timestamps. */
        private var aligned = startFrame <= 0

        init {
            try {
                setSource(extractor)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: throw IOException(tr(R.string.no_audio_in_the_song))
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
                codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                codec.configure(format, null, null, 0)
                codec.start()
                if (startFrame > 0 && expectRate > 0) {
                    // A little early: decoders need a moment of pre-roll after a jump (Opus 80 ms), and the timestamps
                    // then say where the frame asked for is.
                    val us = startFrame * 1_000_000 / expectRate
                    extractor.seekTo((us - PRE_ROLL_US).coerceAtLeast(0), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                }
            } catch (e: Exception) {
                runCatching { extractor.release() }
                throw e
            }
        }

        override fun read(): ShortArray? {
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIdx >= 0 -> {
                        val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val piece = take(outIdx)
                        if (end) outputDone = true
                        if (piece != null && piece.isNotEmpty()) return piece
                    }
                }
            }
            return null
        }

        private fun take(outIdx: Int): ShortArray? {
            val out = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.nativeOrder())
            out.position(info.offset); out.limit(info.offset + info.size)
            val ch = channels.coerceAtLeast(1)
            val frames = (if (floatPcm) info.size / 4 else info.size / 2) / ch
            var skip = 0
            if (!aligned) {
                val r = if (expectRate > 0) expectRate else rate
                val first = (info.presentationTimeUs * r + 500_000) / 1_000_000
                val wanted = startFrame - first
                if (wanted >= frames) { codec.releaseOutputBuffer(outIdx, false); return null }
                skip = wanted.coerceAtLeast(0).toInt()
                aligned = true
            }
            val pcm = ShortArray((frames - skip) * 2)
            var at = 0
            for (f in 0 until frames) {
                var l = 0; var rr = 0
                for (c in 0 until ch) {
                    val v = if (floatPcm) (out.float.coerceIn(-1f, 1f) * 32767f).toInt() else out.short.toInt()
                    if (c == 0) l = v else if (c == 1) rr = v
                }
                if (f < skip) continue
                pcm[at++] = l.toShort()
                pcm[at++] = (if (ch == 1) l else rr).toShort()
            }
            codec.releaseOutputBuffer(outIdx, false)
            return pcm
        }

        override fun close() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }
    }

    companion object {
        private const val PRE_ROLL_US = 150_000L
        private val permits = Semaphore(4)

        /** The decoder for a song file on the phone. */
        fun forUri(context: Context, uri: android.net.Uri) = CastDecoder { it.setDataSource(context, uri, null) }

        /** The decoder for a YouTube song, read through [factory] (downloads, song cache, network). */
        @OptIn(UnstableApi::class)
        fun forSong(factory: DataSource.Factory, id: String) = CastDecoder { it.setDataSource(SongBytes(factory, id)) }
    }
}
