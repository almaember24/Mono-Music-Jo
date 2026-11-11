package com.prism.music.data.foryou

import android.content.Context
import com.prism.music.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Something left out: an artist (by id where known, and name), a playlist or a song, with what to call it. */
@Serializable
data class Excluded(val id: String, val name: String, val thumbnail: String? = null)

/**
 * What the listener has left out (Settings → Library & Replay → Exclusions):
 * - **Artists not to play**: never in a radio, autoplay, Smart Shuffle, a mix or Smart Downloads (asked for by name,
 *   they still play).
 * - **Playlists and songs left out of your taste**: they play as usual, but their plays don't shape recommendations
 *   (a sleep or kids' playlist, a song played for someone else). Replay still counts them.
 * Kept on the phone (`exclusions` prefs).
 */
class Exclusions(context: Context) {
    private val prefs = context.getSharedPreferences("exclusions", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private fun load(key: String): List<Excluded> =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString<List<Excluded>>(it) }.getOrNull() } ?: emptyList()

    private val _artists = MutableStateFlow(load(ARTISTS))
    val artists: StateFlow<List<Excluded>> = _artists
    private val _playlists = MutableStateFlow(load(PLAYLISTS))
    val playlists: StateFlow<List<Excluded>> = _playlists
    private val _songs = MutableStateFlow(load(SONGS))
    val songs: StateFlow<List<Excluded>> = _songs

    /** Reads them again (after "Bring your Prism" merged a phone's in, 1.8.0). */
    fun reload() {
        _artists.value = load(ARTISTS)
        _playlists.value = load(PLAYLISTS)
        _songs.value = load(SONGS)
    }

    private fun save(key: String, flow: MutableStateFlow<List<Excluded>>, list: List<Excluded>) {
        flow.value = list
        prefs.edit().putString(key, json.encodeToString(list)).apply()
    }

    fun setArtist(e: Excluded, excluded: Boolean) = save(ARTISTS, _artists, _artists.value.filter { !same(it, e) } + listOfNotNull(e.takeIf { excluded }))
    fun setPlaylist(e: Excluded, excluded: Boolean) = save(PLAYLISTS, _playlists, _playlists.value.filter { it.id != e.id } + listOfNotNull(e.takeIf { excluded }))
    fun setSong(e: Excluded, excluded: Boolean) = save(SONGS, _songs, _songs.value.filter { it.id != e.id } + listOfNotNull(e.takeIf { excluded }))

    fun isArtistExcluded(id: String?, name: String?): Boolean = _artists.value.any { a ->
        (id != null && a.id == id) || (name != null && a.name.equals(name.trim(), ignoreCase = true))
    }
    fun isPlaylistExcluded(id: String?) = id != null && _playlists.value.any { it.id == id }
    fun isSongExcluded(id: String) = _songs.value.any { it.id == id }

    /** Not to be recommended or played by Prism's own picks: any of its artists is one the listener doesn't want. */
    fun blocks(song: Song): Boolean = song.artists.any { isArtistExcluded(it.id, it.name) }

    /** A play of [song] from the queue started from [originId] that shouldn't count towards taste. */
    fun outOfTaste(song: Song, originId: String?): Boolean = isSongExcluded(song.id) || isPlaylistExcluded(originId) || blocks(song)

    private fun same(a: Excluded, b: Excluded) = a.id == b.id || a.name.equals(b.name, ignoreCase = true)

    private companion object {
        const val ARTISTS = "artists"
        const val PLAYLISTS = "playlists"
        const val SONGS = "songs"
    }
}
