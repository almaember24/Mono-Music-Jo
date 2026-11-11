package com.prism.music.data

import com.prism.music.R
import com.prism.music.tr
import android.content.Context
import com.prism.music.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Liked songs as a real YouTube Music playlist (one that can be shared, pinned, opened in other
 * apps), kept up to date by Prism: a song liked anywhere is added to it, an unliked one taken
 * out. Oldest like first, so new likes land at the end. Needs a signed-in account; the playlist
 * id lives in this phone's `liked_playlist` prefs.
 */
class LikedPlaylist(private val c: AppContainer) {
    private val prefs = c.app.getSharedPreferences("liked_playlist", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    /** The playlist kept in step with Liked songs, or null when that's off. */
    val playlistId = MutableStateFlow(prefs.getString("id", null))
    val title = MutableStateFlow(prefs.getString("title", null))
    val syncing = MutableStateFlow(false)
    val lastSynced = MutableStateFlow(prefs.getLong("synced_at", 0))
    val lastError = MutableStateFlow<String?>(null)

    /** Songs known to be in the playlist after the last sync, so a new like is one quick request. */
    private var mirrored: Set<String> = prefs.getStringSet("ids", emptySet())!!.toSet()

    init {
        // A few seconds after likes change (a burst of likes is one update).
        @OptIn(FlowPreview::class)
        c.scope.launch { c.library.likedIds.drop(1).debounce(4_000).collect { if (playlistId.value != null) sync() } }
    }

    /** Oldest like first (any just-liked song the ordered list hasn't caught up with goes last). */
    private fun likedOldestFirst(): List<String> {
        val ordered = c.library.liked.value.asReversed().map { it.id }
        // Files on the phone aren't on YouTube, so they can't go in a YouTube playlist.
        return (ordered + c.library.likedIds.value).distinct().filterNot { it.startsWith(com.prism.music.data.model.Song.LOCAL_PREFIX) }
    }

    /** Makes the playlist from every liked song and starts keeping it up to date. */
    suspend fun create(name: String, privacy: String): Result<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val ids = likedOldestFirst()
                // Made with the first few, then filled in batches: one huge create request can be refused.
                val id = c.ytm.createPlaylist(name, ids.take(50), tr(R.string.every_song_liked_in_prism_kept), privacy)
                    ?: throw java.io.IOException(tr(R.string.youtube_music_didn_t_make_the))
                if (ids.size > 50) c.ytm.addAllToPlaylist(id, ids.drop(50))
                save(id, name, ids.toSet())
                c.library.created(id, name, ids.size)
                id
            }.onFailure { lastError.value = it.friendlyMessage() }
        }
    }

    /** Stops updating the playlist; with [delete], deletes it from YouTube Music too. */
    suspend fun stop(delete: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val id = playlistId.value ?: return@withLock Result.success(Unit)
            runCatching { if (delete) c.ytm.deletePlaylist(id) }.onSuccess {
                forget()
                if (delete) c.library.refreshCollections()
            }
        }
    }

    /** Called when the playlist itself is deleted elsewhere in Prism. */
    fun forget() {
        playlistId.value = null
        title.value = null
        mirrored = emptySet()
        prefs.edit().clear().apply()
    }

    fun syncInBackground(full: Boolean = false) {
        if (playlistId.value != null) c.scope.launch { sync(full) }
    }

    /**
     * Brings the playlist in line with Liked songs. Usually only the likes since last time are
     * added; a [full] sync (at launch, or when songs were unliked) reads the playlist back first,
     * which also catches changes made to it outside Prism.
     */
    suspend fun sync(full: Boolean = false) = withContext(Dispatchers.IO) {
        if (!c.settings.current.isLoggedIn) return@withContext
        mutex.withLock {
            val id = playlistId.value ?: return@withLock
            val liked = likedOldestFirst()
            val likedSet = liked.toSet()
            // Likes not loaded yet look like "everything was unliked"; never empty the playlist on that.
            if (likedSet.isEmpty()) return@withLock
            syncing.value = true
            try {
                if (!full && (mirrored - likedSet).isEmpty()) {
                    val add = liked.filter { it !in mirrored }
                    if (add.isNotEmpty()) c.ytm.addAllToPlaylist(id, add)
                    save(id, title.value, mirrored + add)
                } else {
                    val slots = c.ytm.playlistSlots(id)
                    val add = liked.filter { it !in slots }
                    val remove = slots.filterKeys { it !in likedSet }.toList()
                    if (remove.isNotEmpty()) c.ytm.removeAllFromPlaylist(id, remove)
                    if (add.isNotEmpty()) c.ytm.addAllToPlaylist(id, add)
                    save(id, title.value, likedSet)
                }
                lastError.value = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError.value = e.friendlyMessage(tr(R.string.couldn_t_update_the_playlist))
            } finally {
                syncing.value = false
            }
        }
    }

    private fun save(id: String, name: String?, ids: Set<String>) {
        mirrored = ids
        val now = System.currentTimeMillis()
        playlistId.value = id
        title.value = name
        lastSynced.value = now
        prefs.edit().putString("id", id).putString("title", name).putStringSet("ids", ids).putLong("synced_at", now).apply()
    }
}
