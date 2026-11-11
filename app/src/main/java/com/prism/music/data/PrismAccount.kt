package com.prism.music.data

import com.prism.music.R
import com.prism.music.tr
import android.net.Uri
import com.prism.music.AppContainer
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.PlaylistItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * A Prism Account: who the listener is in Prism, kept only on this phone (and in their backups). There's no Prism
 * server, so it's not a sign-in anywhere: a name and a picture that Prism uses wherever it names you (Home's greeting,
 * Blend, Listen Together, handoff, the player's "Sam's Phone"), and the owner of everything Prism keeps on the phone
 * (likes, Prism playlists, saved albums, starred artists, Replay). Making one changes nothing that's already there.
 *
 * With YouTube Music signed in as well, [bringOver] copies that account's playlists, saved albums and artists into
 * Prism, so they stay on the phone after signing out (liked songs are on the phone already).
 */
class PrismAccount(private val c: AppContainer) {
    @Serializable
    data class Profile(
        val name: String,
        val createdAt: Long,
        /** Moves on when the picture changes (so pictures showing it reload); 0 when there's none. */
        val picture: Long = 0,
        /** One of Prism's own pictures ([com.prism.music.ui.theme.PrismAvatar] id), when no photo is picked. */
        val avatar: String? = null,
        /** YouTube Music playlist id → the Prism playlist it was copied into. */
        val brought: Map<String, String> = emptyMap(),
        val broughtAt: Long = 0,
    )

    private val dir = File(c.app.filesDir, "account")
    private val file = File(dir, "profile.json")
    val pictureFile = File(dir, "picture.jpg")

    private val _profile = MutableStateFlow(read())
    val profile: StateFlow<Profile?> = _profile.asStateFlow()

    private fun read(): Profile? = runCatching { InnerTube.json.decodeFromString(Profile.serializer(), file.readText()) }.getOrNull()

    private fun write(p: Profile?) {
        _profile.value = p
        runCatching {
            if (p == null) { file.delete(); return@runCatching }
            dir.mkdirs()
            val tmp = File(dir, "profile.json.tmp")
            tmp.writeText(InnerTube.json.encodeToString(Profile.serializer(), p))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    /** Re-reads the profile (after a backup is restored). */
    fun reload() { _profile.value = read() }

    fun create(name: String) {
        val clean = name.trim().take(40)
        if (clean.isBlank()) return
        write(_profile.value?.copy(name = clean) ?: Profile(clean, System.currentTimeMillis()))
    }

    fun rename(name: String) = create(name)

    suspend fun setPicture(uri: Uri): Boolean {
        val p = _profile.value ?: return false
        val ok = Pictures.saveSquare(c.app, uri, pictureFile, size = 720)
        if (ok) write(p.copy(picture = System.currentTimeMillis(), avatar = null))
        return ok
    }

    /** One of Prism's own pictures instead of a photo (the photo, if any, goes). */
    fun setAvatar(id: String) {
        val p = _profile.value ?: return
        pictureFile.delete()
        write(p.copy(picture = 0, avatar = id))
    }

    fun clearPicture() {
        pictureFile.delete()
        _profile.value?.let { write(it.copy(picture = 0, avatar = null)) }
    }

    /** Removes the name and picture only: the music, likes, playlists and history on the phone stay. */
    fun delete() {
        pictureFile.delete()
        write(null)
    }

    // ------------------------------------------------------------ YouTube Music library into Prism

    data class Brought(val playlists: Int, val albums: Int, val artists: Int, val failed: Int)

    /**
     * Copies the signed-in YouTube Music library into Prism: each playlist becomes a Prism playlist (copied again on a
     * later run, songs and all, rather than twice), saved albums are saved in Prism, and the library's artists are
     * starred here (on this phone only). The account itself isn't changed. [progress] gets "3 of 12 playlists…".
     */
    suspend fun bringOver(progress: (String) -> Unit = {}): Brought = withContext(Dispatchers.IO) {
        if (!c.settings.current.isLoggedIn) return@withContext Brought(0, 0, 0, 0)
        runCatching { c.library.sync() }
        val lists = c.library.playlists.value.filterIsInstance<PlaylistItem>()
            .filter { !it.isMix && !it.id.startsWith("LM") && !it.id.startsWith("SE") && !PrismLibrary.isPrismList(it.id) }
        val brought = (_profile.value?.brought ?: emptyMap()).toMutableMap()
        var copied = 0
        var failed = 0
        lists.forEachIndexed { i, p ->
            progress(tr(R.string.playlists_of, i + 1, lists.size))
            val page = runCatching { c.ytm.playlistAll(p.id) }.getOrNull()
            if (page == null) { failed++; return@forEachIndexed }
            val songs = page.songs.filterNot { it.isLive }
            val existing = brought[p.id]?.let { c.prismLibrary.list(it) }
            if (existing != null) {
                c.prismLibrary.rename(existing.id, p.title, existing.description)
                c.prismLibrary.replace(existing.id, songs)
            } else {
                brought[p.id] = c.prismLibrary.create(p.title, songs, tr(R.string.from_youtube_music))
            }
            copied++
        }
        progress(tr(R.string.albums_and_artists))
        val now = System.currentTimeMillis()
        val albums = c.library.albums.value.filterIsInstance<AlbumItem>()
        albums.forEach { a ->
            if (!c.prismLibrary.isSaved(a.id)) c.prismLibrary.saveAlbum(SavedAlbum(a.id, a.title, a.subtitle, a.thumbnail, a.playlistId, now))
        }
        val starred = c.artistPrefs.favorites.value.map { it.id }.toSet()
        // Only the artists the account follows (#182): the library's artist list also holds the artist of every
        // library song, which starred ~1,000 artists for one listener.
        val artists = runCatching { c.ytm.librarySubscriptions() }.getOrDefault(emptyList())
        artists.filter { it.id !in starred }.forEach { a -> c.artistPrefs.setFavorite(FavoriteArtist(a.id, a.title, a.thumbnail), true) }
        _profile.value?.let { write(it.copy(brought = brought, broughtAt = now)) }
        Brought(copied, albums.size, artists.size, failed)
    }
}
