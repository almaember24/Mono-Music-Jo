package com.prism.music.data

import android.content.Context
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.PlaylistItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The albums and playlists played lately, newest first (#196): Home's Recently played can list them instead of every
 * song. Kept on the phone ("recent_collections" prefs), the last [KEEP].
 */
class RecentCollections(context: Context) {
    @Serializable
    private data class Entry(val album: Boolean, val id: String, val title: String, val subtitle: String, val thumbnail: String?, val playlistId: String? = null)

    private val prefs = context.getSharedPreferences("recent_collections", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<BrowseItem>> = _items

    /** [item] was just played: it moves to the front. Only albums and (not mix) playlists count. */
    fun played(item: BrowseItem) {
        val entry = when (item) {
            is AlbumItem -> Entry(true, item.id, item.title, item.subtitle, item.thumbnail, item.playlistId)
            is PlaylistItem -> if (item.isMix) return else Entry(false, item.id, item.title, item.subtitle, item.thumbnail)
            else -> return
        }
        val next = (listOf(entry) + entries().filterNot { it.id == entry.id }).take(KEEP)
        prefs.edit().putString(KEY, json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Entry.serializer()), next)).apply()
        _items.value = next.map { it.toItem() }
    }

    private fun entries(): List<Entry> = runCatching {
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Entry.serializer()), prefs.getString(KEY, null) ?: "[]")
    }.getOrDefault(emptyList())

    private fun load(): List<BrowseItem> = entries().map { it.toItem() }

    private fun Entry.toItem(): BrowseItem =
        if (album) AlbumItem(id, title, subtitle, thumbnail, playlistId) else PlaylistItem(id, title, subtitle, thumbnail)

    private companion object {
        const val KEY = "items"
        const val KEEP = 20
    }
}
