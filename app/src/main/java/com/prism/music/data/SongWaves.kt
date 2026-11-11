package com.prism.music.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlin.math.sqrt

/**
 * Songs' loudness outlines for the waveform progress bar (Settings → Player → Progress bar → Waveform, 1.7.4): [POINTS]
 * values a song, 0–1, made by the song scan ([com.prism.music.playback.MixTempo]) from the audio it already decodes, so
 * nothing extra is downloaded. Kept on the phone in files/waves (one byte a point; a few thousand songs is well under a
 * megabyte). A song not scanned yet has none, and its bar is a plain line until it is.
 */
class SongWaves(context: Context) {
    private val dir = File(context.filesDir, "waves").apply { mkdirs() }
    private val memory = object : LinkedHashMap<String, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > 64
    }
    private val _stamp = MutableStateFlow(0L)
    /** Bumped when an outline is saved, so a bar waiting for its song's can look again. */
    val stamp: StateFlow<Long> = _stamp

    private fun file(id: String) = File(dir, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".wav1")

    /** The outline of song [id], or null if it hasn't been scanned since waveforms came in. Reads a file: off the main thread. */
    fun get(id: String): FloatArray? {
        synchronized(memory) { memory[id] }?.let { return it }
        val bytes = runCatching { file(id).takeIf { it.isFile }?.readBytes() }.getOrNull() ?: return null
        if (bytes.size != POINTS) return null
        val wave = FloatArray(POINTS) { (bytes[it].toInt() and 0xFF) / 255f }
        synchronized(memory) { memory[id] = wave }
        return wave
    }

    fun has(id: String): Boolean = synchronized(memory) { id in memory } || file(id).isFile

    /** Forgets song [id]'s outline (another version of it plays now). */
    fun forget(id: String) {
        synchronized(memory) { memory.remove(id) }
        runCatching { file(id).delete() }
    }

    fun put(id: String, wave: FloatArray) {
        if (wave.size != POINTS) return
        runCatching { file(id).writeBytes(ByteArray(POINTS) { (wave[it].coerceIn(0f, 1f) * 255).toInt().toByte() }) }
        synchronized(memory) { memory[id] = wave }
        _stamp.value = System.nanoTime()
    }

    companion object {
        const val POINTS = 100

        /**
         * [pcm]'s outline: the loudness (RMS) of each of [POINTS] equal slices, on a gentle curve (square root) so quiet
         * passages still show, scaled so the loudest slice is 1.
         */
        fun outline(pcm: FloatArray, count: Int = pcm.size): FloatArray {
            val out = FloatArray(POINTS)
            if (count <= 0) return out
            for (i in 0 until POINTS) {
                val from = (count.toLong() * i / POINTS).toInt()
                val to = (count.toLong() * (i + 1) / POINTS).toInt().coerceAtLeast(from + 1).coerceAtMost(count)
                var sum = 0.0
                for (j in from until to) sum += pcm[j] * pcm[j]
                out[i] = sqrt(sqrt(sum / (to - from))).toFloat()
            }
            val top = out.max().takeIf { it > 0f } ?: return out
            for (i in out.indices) out[i] = out[i] / top
            return out
        }
    }
}
