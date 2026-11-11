package com.prism.music.data

import com.prism.music.data.db.SongTraitsEntity
import com.prism.music.data.db.TraitsDao
import com.prism.music.playback.MusicKey
import com.prism.music.playback.Tempo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** What a song scan found ([com.prism.music.playback.MixTempo]), as it goes into the `song_traits` table (1.8.3). */
data class SongTraits(
    val id: String,
    val tempo: Tempo?,
    val key: MusicKey?,
    val lufs: Float?,
    val energy: Float?,
    /** From the whole song, or DJ mode's quick scan of three stretches. */
    val whole: Boolean,
    val at: Long = System.currentTimeMillis(),
) {
    fun entity() = SongTraitsEntity(
        songId = id,
        bpm = tempo?.bpm?.takeIf { it > 0 },
        bpmSure = tempo?.steady == true,
        key = key?.encode(),
        // Only a clear key is filterable (DJ mode goes by the same line); the guess stays in [key].
        camelot = key?.takeIf { it.sure }?.camelot,
        lufs = lufs?.takeIf { !it.isNaN() },
        energy = energy,
        whole = whole,
        measuredAt = at,
    )

    companion object {
        /** A new measurement replaces the one there unless that's of the whole song and this one isn't. */
        fun replaces(new: SongTraits, old: SongTraitsEntity?): Boolean = old == null || new.whole || !old.whole
    }
}

/** Where song scans put what they found; [TraitsStore] in the app, a list in tests. */
fun interface TraitsSink {
    fun record(t: SongTraits)
    fun forget(id: String) {}
}

/** The `song_traits` table, written in the background as songs are measured. */
class TraitsStore(private val dao: TraitsDao, private val scope: CoroutineScope) : TraitsSink {
    override fun record(t: SongTraits) {
        scope.launch(Dispatchers.IO) { runCatching { if (SongTraits.replaces(t, dao.get(t.id))) dao.put(t.entity()) } }
    }

    override fun forget(id: String) {
        scope.launch(Dispatchers.IO) { runCatching { dao.delete(id) } }
    }

    /**
     * Copies what the scans' own prefs hold into the table, for songs it has no row for: once after 1.8.3 arrives, and
     * again whenever the two disagree (a backup restored with its history but not its settings, or the other way). The
     * table never loses a newer row this way. [ids] are the songs measured; [read] what the prefs say of one.
     */
    suspend fun copyMissing(ids: Collection<String>, read: (String) -> SongTraits?): Int {
        if (ids.isEmpty()) return 0
        // Which songs have no row (not how many rows there are: a restored history can hold other songs than the prefs).
        val have = dao.ids().toHashSet()
        val missing = ids.filter { it !in have }
        var copied = 0
        for (chunk in missing.chunked(200)) {
            val rows = chunk.mapNotNull { id -> runCatching { read(id) }.getOrNull()?.entity() }
            dao.putMissing(rows)
            copied += rows.size
        }
        return copied
    }
}
