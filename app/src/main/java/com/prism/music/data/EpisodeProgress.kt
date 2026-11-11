package com.prism.music.data

import android.content.Context
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * How far into each podcast episode the listener got, kept on the phone (like the rest of Prism's history), so an
 * episode picks up where it was left and Podcasts can offer "Continue listening". An episode heard to within a
 * minute of its end counts as played and drops off the list.
 */
class EpisodeProgress(context: Context) {
    @Serializable
    data class Entry(val song: Song, val positionMs: Long, val durationMs: Long, val updatedAt: Long) {
        val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val leftMs: Long get() = (durationMs - positionMs).coerceAtLeast(0)
    }

    private val prefs = context.getSharedPreferences("episode_progress", Context.MODE_PRIVATE)
    private val playedPrefs = context.getSharedPreferences("episode_played", Context.MODE_PRIVATE)

    private val _inProgress = MutableStateFlow(load())
    /** Unfinished episodes, most recently heard first. */
    val inProgress: StateFlow<List<Entry>> = _inProgress
    private val _played = MutableStateFlow(playedPrefs.all.keys.toSet())
    /** Episodes heard to the end on this phone. */
    val played: StateFlow<Set<String>> = _played

    private fun load(): List<Entry> = prefs.all.values.mapNotNull { v ->
        (v as? String)?.let { runCatching { InnerTube.json.decodeFromString(Entry.serializer(), it) }.getOrNull() }
    }.sortedByDescending { it.updatedAt }

    /** Where to start [id] from (0 when it's new or was finished). */
    fun positionOf(id: String): Long = _inProgress.value.firstOrNull { it.song.id == id }?.positionMs ?: 0L

    /** Remembers [positionMs] into [song]; near the end, the episode is marked played instead. */
    fun save(song: Song, positionMs: Long, durationMs: Long) {
        if (!song.isEpisode || positionMs < 0) return
        if (durationMs > 0 && positionMs >= durationMs - FINISHED_WITHIN_MS) { markPlayed(song.id); return }
        // A few seconds in isn't worth coming back to.
        if (positionMs < MIN_KEEP_MS) return
        val entry = Entry(song, positionMs, durationMs, System.currentTimeMillis())
        prefs.edit().putString(song.id, InnerTube.json.encodeToString(Entry.serializer(), entry)).apply()
        _inProgress.value = (listOf(entry) + _inProgress.value.filter { it.song.id != song.id }).take(MAX_KEPT)
        if (_inProgress.value.size >= MAX_KEPT) {
            // The oldest fall off.
            val keep = _inProgress.value.map { it.song.id }.toSet()
            prefs.edit().apply { prefs.all.keys.filter { it !in keep }.forEach { remove(it) } }.apply()
        }
    }

    fun markPlayed(id: String) {
        prefs.edit().remove(id).apply()
        playedPrefs.edit().putLong(id, System.currentTimeMillis()).apply()
        _inProgress.value = _inProgress.value.filter { it.song.id != id }
        _played.value = _played.value + id
    }

    fun unmarkPlayed(id: String) {
        playedPrefs.edit().remove(id).apply()
        _played.value = _played.value - id
    }

    /** Takes an episode off "Continue listening" (it starts from the top next time). */
    fun forget(id: String) {
        prefs.edit().remove(id).apply()
        _inProgress.value = _inProgress.value.filter { it.song.id != id }
    }

    private companion object {
        const val FINISHED_WITHIN_MS = 60_000L
        const val MIN_KEEP_MS = 30_000L
        const val MAX_KEPT = 50
    }
}
