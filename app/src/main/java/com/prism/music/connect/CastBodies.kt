package com.prism.music.connect

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** Something the media server hands a renderer: its type, its length, and its bytes from any point (for Range requests). */
interface CastBody {
    val mime: String
    val length: Long
    /** Decoded on the phone rather than the file itself (DLNA's "converted" flag). */
    val converted: Boolean
    /** Writes bytes [start]..[end] (inclusive) to [out]. Throws when the other side goes. */
    fun write(start: Long, end: Long, out: OutputStream)
}

/** A cover or other small thing, all in memory. */
class BytesBody(override val mime: String, private val bytes: ByteArray) : CastBody {
    override val length get() = bytes.size.toLong()
    override val converted get() = false
    override fun write(start: Long, end: Long, out: OutputStream) = out.write(bytes, start.toInt(), (end - start + 1).toInt())
}

/** A file as it is (an MP3 or FLAC on the phone), read from wherever [open] puts it. */
class FileBody(override val mime: String, override val length: Long, private val open: (Long) -> InputStream) : CastBody {
    override val converted get() = false
    override fun write(start: Long, end: Long, out: OutputStream) {
        open(start).use { input ->
            val buf = ByteArray(64 * 1024)
            var left = end - start + 1
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
            // A file shorter than it said: the rest as silence-free zeros, so the length promised holds.
            if (left > 0) writeZeros(out, left)
        }
    }
}

/** Decoded audio, 16-bit stereo, a piece at a time from wherever it was started. */
interface PcmFrames : Closeable {
    /** The next piece (interleaved left/right), or null at the end of the song. */
    fun read(): ShortArray?
}

/**
 * A song decoded on the phone as plain PCM, which every renderer plays: WAV (a 44-byte header, then little-endian
 * samples) or L16 (big-endian samples, no header, DLNA's own LPCM). 16-bit stereo at [rate]. Its length is fixed up front
 * from the song's length ([frames]), so any byte can be asked for: the decoder is started at that byte's frame. If
 * the decoder runs out early the rest is silence; anything past the length is left out.
 */
class PcmBody(
    val rate: Int,
    val frames: Long,
    val wav: Boolean,
    /** How WAV is named to the renderer (the spelling it listed: audio/wav, audio/x-wav…). */
    wavMime: String = "audio/wav",
    /** Decoded frames starting at the frame asked for. */
    private val from: (Long) -> PcmFrames,
) : CastBody {
    private val header = if (wav) Wav.header(rate, 2, frames * 4) else ByteArray(0)
    override val mime = if (wav) wavMime else "audio/L16;rate=$rate;channels=2"
    override val length = header.size + frames * 4
    override val converted get() = true

    override fun write(start: Long, end: Long, out: OutputStream) {
        var pos = start
        if (pos < header.size) {
            val until = minOf(end + 1, header.size.toLong())
            out.write(header, pos.toInt(), (until - pos).toInt())
            pos = until
        }
        if (pos > end) return
        val dataStart = pos - header.size
        var left = end - pos + 1
        // A range starting mid-frame: the frame's first bytes are left out.
        var skip = (dataStart % 4).toInt()
        val bytes = ByteArray(PIECE_BYTES)
        fun emit(n: Int) {
            val drop = minOf(skip, n)
            skip -= drop
            val count = minOf((n - drop).toLong(), left).toInt()
            if (count > 0) { out.write(bytes, drop, count); left -= count }
        }
        from(dataStart / 4).use { pcm ->
            while (left > 0) {
                val piece = pcm.read() ?: break
                var n = 0
                for (s in piece) {
                    val v = s.toInt()
                    if (wav) { bytes[n++] = v.toByte(); bytes[n++] = (v shr 8).toByte() } else { bytes[n++] = (v shr 8).toByte(); bytes[n++] = v.toByte() }
                    if (n == bytes.size) { emit(n); n = 0; if (left <= 0) break }
                }
                if (n > 0 && left > 0) emit(n)
            }
        }
        if (left > 0) writeZeros(out, left)
    }

    private companion object { const val PIECE_BYTES = 32 * 1024 }
}

object Wav {
    /** A canonical 44-byte PCM WAV header for [dataBytes] of 16-bit audio. */
    fun header(rate: Int, channels: Int, dataBytes: Long): ByteArray {
        val data = dataBytes.coerceAtMost(0xFFFFFFFFL - 36)
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + data).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(data.toInt())
        return b.array()
    }
}

internal fun writeZeros(out: OutputStream, count: Long) {
    val z = ByteArray(16 * 1024)
    var left = count
    while (left > 0) {
        val n = minOf(z.size.toLong(), left).toInt()
        out.write(z, 0, n)
        left -= n
    }
}
