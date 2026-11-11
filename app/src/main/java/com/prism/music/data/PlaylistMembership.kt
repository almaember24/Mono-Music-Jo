package com.prism.music.data

import com.prism.music.data.model.PlaylistItem
import java.util.concurrent.ConcurrentHashMap

/**
 * #246 (1.8.1): which of the listener's playlists already hold a song, for Add to playlist's marks. Only what the phone
 * already knows: Prism playlists' own songs, the copies of YouTube Music playlists kept for offline (any opened one), and
 * what was added from this phone since. Nothing is asked from YouTube for it, so a playlist never opened here has no mark.
 */
object PlaylistMembership {
    /** Songs added to a YouTube Music playlist from this phone since Prism started (its kept copy may not have them yet). */
    private val added = ConcurrentHashMap<String, MutableSet<String>>()

    /** One playlist, however its id is written ("VLPL…" opened from the library, "PL…" for adding). */
    internal fun key(id: String): String = id.removePrefix("VL")

    /** [ids] went into the playlist [playlistId]. */
    fun noteAdded(playlistId: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        added.getOrPut(key(playlistId)) { ConcurrentHashMap.newKeySet() }.addAll(ids)
    }

    /** Forgets this session's additions (signing out, switching accounts; tests). */
    fun forget() = added.clear()

    /**
     * How many of [ids] each playlist in [contents] (playlist id → its songs' ids) holds, with this session's additions;
     * playlists holding none are left out.
     */
    fun counts(ids: Collection<String>, contents: Map<String, Collection<String>>): Map<String, Int> {
        val wanted = ids.toSet()
        if (wanted.isEmpty()) return emptyMap()
        return contents.keys.mapNotNull { id ->
            val has = (contents[id].orEmpty().toSet() + added[key(id)].orEmpty()).count { it in wanted }
            if (has > 0) id to has else null
        }.toMap()
    }

    /**
     * The songs the phone knows each of [playlists] to hold: a Prism playlist's own, else the copy kept when it was last
     * opened ([OfflineLibrary.page]). Reads files: call off the main thread.
     */
    fun known(
        playlists: List<PlaylistItem>,
        prism: Map<String, List<String>>,
        kept: (String) -> List<String>?,
    ): Map<String, List<String>> = playlists.associate { p ->
        p.id to (prism[p.id] ?: kept(p.id) ?: kept(key(p.id)) ?: kept("VL" + key(p.id)) ?: emptyList())
    }
}
