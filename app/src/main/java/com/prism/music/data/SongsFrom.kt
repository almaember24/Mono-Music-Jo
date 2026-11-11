package com.prism.music.data

import com.prism.music.data.model.Song
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * Library → Songs' "From" (1.8.3, G142, #382): liked songs, as always, and the songs of playlists the listener picks,
 * together and once each. Only a view: nothing is liked and nothing is written to the account.
 */
data class SongsFrom(val liked: Boolean = true, val playlists: List<String> = emptyList()) {
    /** Liked songs only: Songs as it was before 1.8.3. */
    val isDefault get() = liked && playlists.isEmpty()

    fun encode(): String = (if (liked) LIKED else "") + "|" + playlists.joinToString(",")

    companion object {
        private const val LIKED = "liked"
        const val KEY = "library:songs:from"

        fun decode(s: String?): SongsFrom {
            if (s.isNullOrBlank() || '|' !in s) return SongsFrom()
            val (a, b) = s.split('|', limit = 2)
            val lists = b.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            // Nothing chosen at all would be an empty tab with no way to tell why: liked songs then.
            return if (a != LIKED && lists.isEmpty()) SongsFrom() else SongsFrom(a == LIKED, lists)
        }

        /**
         * Liked songs first (newest first, as today), then each playlist's songs in the order they were picked and
         * in the playlist's own order; a song in several shows once, where it first comes.
         */
        fun merge(liked: List<Song>, lists: List<List<Song>>, from: SongsFrom): List<Song> {
            val seen = HashSet<String>()
            val out = ArrayList<Song>()
            if (from.liked) liked.forEach { if (seen.add(it.id)) out += it }
            lists.forEach { l -> l.forEach { if (seen.add(it.id)) out += it } }
            return out
        }
    }
}

/**
 * A playlist's songs, all of them (up to [MAX]), for views that put several playlists together. Kept for the session:
 * [refresh] forgets one when it's opened again or edited.
 */
class PlaylistSongs(private val load: suspend (id: String) -> List<Song>) {
    private val kept = ConcurrentHashMap<String, List<Song>>()

    suspend fun songs(id: String): List<Song>? = kept[id] ?: try {
        load(id).also { kept[id] = it }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    fun refresh(id: String) { kept.remove(id) }

    companion object {
        /** The most songs read of one playlist (as a queue takes). */
        const val MAX = 2000
    }
}
