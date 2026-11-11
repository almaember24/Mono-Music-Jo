package com.prism.music.playback.sing

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Songs' separated vocals, kept on the phone: 16-bit stereo PCM at the song's own sample rate (the rate the player
 * hands Sing's processor), written as the separation goes, so Sing can start before a song is done. Least recently
 * used ones go when the store is over its size.
 *
 * A song switched to Sing part way through is separated from where it's playing first (1.8.x): that part goes in a file
 * of its own ([pcmFrom], from song frame [Meta.from] on), the start of the song in the usual one ([pcm]) after it, up
 * to there. Read together, they're the song's vocals as one.
 */
class StemStore(root: File, /** The folder (1.8.x: the quick way's stand-ins have their own). */ name: String = "stems") {
    /**
     * [method]: the [Separation] the vocals were told apart by (the least good of the song's chunks; null: full).
     * [from]: the song frame the part done first starts at (0: done from the start, in one file).
     */
    @Serializable
    data class Meta(
        val rate: Int, val totalFrames: Long, val complete: Boolean = false, val version: Int = VERSION,
        val method: String? = null, val from: Long = 0,
    )

    val dir = File(root, name).apply { mkdirs() }

    /**
     * The quick way's stand-ins (1.8.x), done first so Sing takes effect at once, kept until the song's own vocals are
     * done. Cleared, measured and trimmed with this store, so Settings' room and its clear button count them too.
     */
    val quick: StemStore? = if (name == "stems") StemStore(root, "stems-quick") else null
    private val json = Json { ignoreUnknownKeys = true }

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
    fun pcm(id: String) = File(dir, "${safe(id)}.pcm")
    /** The part done first, from where the song was playing ([Meta.from]). */
    fun pcmFrom(id: String) = File(dir, "${safe(id)}$FROM")
    private fun metaFile(id: String) = File(dir, "${safe(id)}.json")

    fun meta(id: String): Meta? = runCatching { json.decodeFromString<Meta>(metaFile(id).readText()) }.getOrNull()?.takeIf { it.version == VERSION }

    fun writeMeta(id: String, meta: Meta) = metaFile(id).writeText(json.encodeToString(meta))

    /** Fully separated already. */
    fun complete(id: String) = meta(id)?.complete == true && pcm(id).isFile

    /** How far the vocals go so far, 0..1. */
    fun progress(id: String): Float {
        val m = meta(id) ?: return 0f
        if (m.complete) return 1f
        return ((pcm(id).length() + pcmFrom(id).length()) / BYTES_PER_FRAME).toFloat() / m.totalFrames.coerceAtLeast(1)
    }

    fun delete(id: String) { pcm(id).delete(); pcmFrom(id).delete(); metaFile(id).delete() }

    fun clear() { dir.listFiles()?.forEach { it.delete() }; quick?.clear() }

    fun bytes(): Long = (dir.listFiles()?.sumOf { it.length() } ?: 0L) + (quick?.bytes() ?: 0L)

    /** Keeps the store within [limit] bytes, oldest used first, never [keep] (the ones playing or coming up). */
    fun trim(limit: Long, keep: Set<String>) {
        fun songs(d: File?) = d?.listFiles { f -> f.name.endsWith(".pcm") && !f.name.endsWith(FROM) }.orEmpty().toList()
        val files = (songs(dir) + songs(quick?.dir)).sortedBy { it.lastModified() }
        var total = bytes()
        for (f in files) {
            if (total <= limit) break
            val id = f.nameWithoutExtension
            if (keep.any { safe(it) == id }) continue
            val part = File(f.parentFile, "$id$FROM")
            total -= f.length() + part.length()
            f.delete()
            part.delete()
            File(f.parentFile, "$id.json").delete()
        }
    }

    /** Opens [id]'s vocals for reading (while they're still being written, too), or null if there are none. */
    fun reader(id: String): Reader? {
        val m = meta(id) ?: return null
        val f = pcm(id)
        if (!f.isFile) return null
        f.setLastModified(System.currentTimeMillis())
        val part = pcmFrom(id).takeIf { m.from > 0 && it.isFile }
        return Reader(RandomAccessFile(f, "r").channel, part?.let { RandomAccessFile(it, "r").channel }, m)
    }

    /** A song's vocals: [channel] from its start, and [fromChannel] from [Meta.from] on when it was done in two parts. */
    class Reader(private val channel: FileChannel, private val fromChannel: FileChannel?, val meta: Meta) : AutoCloseable {
        private var bytes = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)

        private fun frames(c: FileChannel) = runCatching { c.size() / BYTES_PER_FRAME }.getOrDefault(0L)

        /**
         * [frames] stereo frames from [start] into [out] (interleaved floats). Frames before the start, or not
         * written yet, read as silence. Returns how many were real.
         */
        fun read(start: Long, frames: Int, out: FloatArray): Int {
            java.util.Arrays.fill(out, 0, frames * 2, 0f)
            val split = if (fromChannel != null) meta.from else Long.MAX_VALUE
            // The start of the song (up to where the other part begins), then the other part.
            var real = part(channel, 0L, 0L, minOf(frames(channel), split), start, frames, out)
            if (fromChannel != null) real += part(fromChannel, split, split, split + frames(fromChannel), start, frames, out)
            return real
        }

        /** The song frames [from, to) of the file that starts at song frame [fileStart], where they meet the window. */
        private fun part(c: FileChannel, fileStart: Long, from: Long, to: Long, start: Long, frames: Int, out: FloatArray): Int {
            val a = maxOf(start, from, 0L)
            val b = minOf(start + frames, to)
            if (b <= a) return 0
            val n = (b - a).toInt()
            val need = (n * BYTES_PER_FRAME).toInt()
            if (bytes.capacity() < need) bytes = ByteBuffer.allocate(need).order(ByteOrder.LITTLE_ENDIAN)
            bytes.clear(); bytes.limit(need)
            var pos = (a - fileStart) * BYTES_PER_FRAME
            while (bytes.hasRemaining()) {
                val r = c.read(bytes, pos)
                if (r <= 0) break
                pos += r
            }
            bytes.flip()
            var i = ((a - start) * 2).toInt()
            while (bytes.remaining() >= 2) { out[i] = bytes.short / 32768f; i++ }
            return n
        }

        override fun close() { runCatching { channel.close() }; runCatching { fromChannel?.close() } }
    }

    companion object {
        /** Bumped when the separation changes, so older vocals are made again. */
        const val VERSION = 1
        const val BYTES_PER_FRAME = 4L
        private const val FROM = ".from.pcm"
    }
}
