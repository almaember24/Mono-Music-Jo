package com.prism.music.data

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.Song
import com.prism.music.data.model.songCount
import com.prism.music.playback.together.fromPeer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/** A playlist kept on the phone, made in Prism without a YouTube Music account. */
@Serializable
data class PrismList(
    val id: String,
    val title: String,
    val description: String = "",
    val songs: List<Song> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
    /**
     * A smart playlist's rules (1.8.3): its songs are what they find, worked out again as it opens; [songs] keeps the
     * last of them for what plays it without opening it (Home, Android Auto, a tile's menu).
     */
    val smart: SmartRules? = null,
) {
    fun item() = PlaylistItem(
        id, title,
        if (smart != null) tr(R.string.smart_playlist_n, songCount(songs.size)) else tr(R.string.prism_playlist, songCount(songs.size)),
        songs.firstOrNull()?.thumbnail,
    )
}

/** An album saved to the library on the phone (the bookmark on an album page, without an account). */
@Serializable
data class SavedAlbum(
    val id: String,
    val title: String,
    val subtitle: String,
    val thumbnail: String? = null,
    val playlistId: String? = null,
    val savedAt: Long,
) {
    fun item() = AlbumItem(id, title, subtitle, thumbnail, playlistId)
}

/**
 * A YouTube playlist saved to the library on the phone, without an account (H7): a bookmark, opened live from YouTube
 * each time, so it follows the playlist's own changes. [id] is the playlist's browse id or plain id, as it was opened.
 */
@Serializable
data class SavedPlaylist(
    val id: String,
    val title: String,
    val subtitle: String,
    val thumbnail: String? = null,
    val savedAt: Long,
) {
    fun item() = PlaylistItem(id, title, subtitle, thumbnail)
}

/** A folder of playlists in Library (1.7.6): Prism's own, YouTube's and saved ones alike, by id. */
@Serializable
data class PlaylistFolder(val id: String, val name: String, val playlists: List<String> = emptyList(), val createdAt: Long)

@Serializable
private data class PrismLibraryFile(
    val lists: List<PrismList> = emptyList(),
    val albums: List<SavedAlbum> = emptyList(),
    val playlists: List<SavedPlaylist> = emptyList(),
    val folders: List<PlaylistFolder> = emptyList(),
)

/**
 * Playlists and saved albums kept on the phone, for listeners who don't sign in (and anyone who wants a list that's only
 * Prism's). One JSON file in `files/library/` (so it's in backups), written whole on every change, through a temporary file.
 * Playlists' ids start with [PREFIX], which is how the playlist page knows one.
 */
class PrismLibrary(private val dir: File) {
    private val file = File(dir, "prism.json")
    private val data = MutableStateFlow(read())
    private val _lists = MutableStateFlow(data.value.lists)
    private val _albums = MutableStateFlow(data.value.albums)
    private val _playlists = MutableStateFlow(data.value.playlists)
    private val _folders = MutableStateFlow(data.value.folders)

    /** Library's playlist folders, in the order they were made. */
    val folders: StateFlow<List<PlaylistFolder>> = _folders

    fun folderOf(playlistId: String): PlaylistFolder? = _folders.value.firstOrNull { f -> f.playlists.any { samePlaylist(it, playlistId) } }

    /** A new folder named [name], with [playlistId] in it if given; its id. */
    fun createFolder(name: String, playlistId: String? = null): String {
        val id = "folder:" + UUID.randomUUID().toString().replace("-", "").take(10)
        change { lib ->
            val out = lib.folders.map { f -> f.copy(playlists = f.playlists.filterNot { p -> playlistId != null && samePlaylist(p, playlistId) }) }
            lib.copy(folders = out + PlaylistFolder(id, name.trim().ifBlank { tr(R.string.folder) }, listOfNotNull(playlistId), System.currentTimeMillis()))
        }
        return id
    }

    fun renameFolder(id: String, name: String) = change { lib ->
        lib.copy(folders = lib.folders.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it })
    }

    /** The folder goes; its playlists go back to Library's list. */
    fun deleteFolder(id: String) = change { lib -> lib.copy(folders = lib.folders.filterNot { it.id == id }) }

    /** Puts the playlist in [folderId] (out of any other), or out of every folder when it's null. */
    fun moveToFolder(playlistId: String, folderId: String?) = change { lib ->
        lib.copy(folders = lib.folders.map { f ->
            val without = f.playlists.filterNot { samePlaylist(it, playlistId) }
            if (f.id == folderId) f.copy(playlists = without + playlistId) else f.copy(playlists = without)
        })
    }

    /** Newest change first. */
    val lists: StateFlow<List<PrismList>> = _lists
    /** Newest saved first. */
    val albums: StateFlow<List<SavedAlbum>> = _albums
    /** YouTube playlists saved on the phone, newest saved first. */
    val playlists: StateFlow<List<SavedPlaylist>> = _playlists

    fun list(id: String): PrismList? = _lists.value.firstOrNull { it.id == id }
    fun isSaved(albumId: String) = _albums.value.any { it.id == albumId }
    fun isPlaylistSaved(id: String) = _playlists.value.any { samePlaylist(it.id, id) }

    /** A new playlist named [title] (with [songs] in it); its id. */
    fun create(title: String, songs: List<Song> = emptyList(), description: String = ""): String {
        val now = System.currentTimeMillis()
        val id = PREFIX + UUID.randomUUID().toString().replace("-", "").take(12)
        change { it.copy(lists = listOf(PrismList(id, title.trim().ifBlank { tr(R.string.my_playlist) }, description, songs.distinctBy { s -> s.id }, now, now)) + it.lists) }
        return id
    }

    /** A new smart playlist (1.8.3); its id. Its songs come when it's first worked out. */
    fun createSmart(title: String, rules: SmartRules): String {
        val now = System.currentTimeMillis()
        val id = PREFIX + UUID.randomUUID().toString().replace("-", "").take(12)
        change { it.copy(lists = listOf(PrismList(id, title.trim().ifBlank { tr(R.string.smart_playlist) }, "", emptyList(), now, now, smart = rules)) + it.lists) }
        return id
    }

    /** A smart playlist's new name and rules. */
    fun setSmart(id: String, title: String, rules: SmartRules) = edit(id) { it.copy(title = title.trim().ifBlank { it.title }, smart = rules) }

    /**
     * What a smart playlist found, kept for what plays it unopened: neither its place among the playlists nor its
     * "changed" time moves (it's the same playlist), and nothing is written when it found the same.
     */
    fun keepFound(id: String, songs: List<Song>) {
        val l = _lists.value.firstOrNull { it.id == id } ?: return
        if (l.smart == null || l.songs.map { it.id } == songs.map { it.id }) return
        change { lib -> lib.copy(lists = lib.lists.map { if (it.id == id) it.copy(songs = songs) else it }) }
    }

    fun rename(id: String, title: String, description: String) = edit(id) { it.copy(title = title.trim().ifBlank { it.title }, description = description.trim()) }

    fun delete(id: String) = change { it.copy(lists = it.lists.filterNot { l -> l.id == id }) }

    /** Adds the songs not in it yet, at the end; how many were added. */
    fun add(id: String, songs: List<Song>): Int {
        // A smart playlist's songs are what its rules find.
        if (list(id)?.smart != null) return 0
        var added = 0
        edit(id) { l ->
            val have = l.songs.map { it.id }.toHashSet()
            val fresh = songs.filter { have.add(it.id) }
            added = fresh.size
            l.copy(songs = l.songs + fresh)
        }
        return added
    }

    fun remove(id: String, songId: String) = edit(id) { l -> l.copy(songs = l.songs.filterNot { it.id == songId }) }

    /** Puts the songs in this order (a reordered copy of the playlist's own songs). */
    fun reorder(id: String, songs: List<Song>) = edit(id) { l ->
        val ids = l.songs.map { it.id }.toSet()
        l.copy(songs = songs.filter { it.id in ids }.distinctBy { it.id })
    }

    /** Makes the playlist exactly [songs] (a copy brought up to date). */
    fun replace(id: String, songs: List<Song>) = edit(id) { l -> l.copy(songs = songs.distinctBy { it.id }) }

    fun saveAlbum(album: SavedAlbum) = change { it.copy(albums = listOf(album) + it.albums.filterNot { a -> a.id == album.id }) }
    fun unsaveAlbum(id: String) = change { it.copy(albums = it.albums.filterNot { a -> a.id == id }) }

    fun savePlaylist(p: SavedPlaylist) = change { it.copy(playlists = listOf(p) + it.playlists.filterNot { s -> samePlaylist(s.id, p.id) }) }
    fun unsavePlaylist(id: String) = change { it.copy(playlists = it.playlists.filterNot { s -> samePlaylist(s.id, id) }) }

    /**
     * The library for another of the listener's devices ("Bring your Prism", 1.8.0), in this file's own format: the
     * phone's files leave their playlists (their names never go).
     */
    fun forBringing(): String {
        val lib = data.value
        val out = lib.copy(lists = lib.lists.map { l -> l.copy(songs = l.songs.filterNot { it.isLocal }) })
        return InnerTube.json.encodeToString(PrismLibraryFile.serializer(), out)
    }

    /**
     * Another device's library ([forBringing]) merged into this one: playlists by id (one on both keeps the copy changed
     * last), saved albums and playlists added if missing, folders by id (their playlists joined). Nothing here goes.
     * Songs only from YouTube ([com.prism.music.playback.together.fromPeer]). How many playlists and albums came.
     */
    fun mergeFrom(text: String): Pair<Int, Int> {
        val theirs = runCatching { InnerTube.json.decodeFromString(PrismLibraryFile.serializer(), text) }.getOrNull() ?: return 0 to 0
        var lists = 0
        var albums = 0
        change { mine ->
            val byId = mine.lists.associateBy { it.id }.toMutableMap()
            for (l in theirs.lists.take(MAX_BROUGHT)) {
                if (!isPrismList(l.id) || l.id.length > 80) continue
                val have = byId[l.id]
                if (have != null && have.updatedAt >= l.updatedAt) continue
                val songs = l.songs.filterNot { it.isLocal }.mapNotNull { it.fromPeer() }.distinctBy { it.id }.take(MAX_SONGS)
                byId[l.id] = l.copy(title = l.title.take(200), description = l.description.take(2_000), songs = songs)
                if (have == null) lists++
            }
            val outAlbums = mine.albums.toMutableList()
            for (a in theirs.albums.take(MAX_BROUGHT)) if (a.id.length <= 80 && outAlbums.none { it.id == a.id }) {
                outAlbums += a.copy(title = a.title.take(300), subtitle = a.subtitle.take(300), thumbnail = a.thumbnail?.takeIf { it.startsWith("https://") })
                albums++
            }
            val outPlaylists = mine.playlists.toMutableList()
            for (p in theirs.playlists.take(MAX_BROUGHT)) if (p.id.length <= 120 && outPlaylists.none { samePlaylist(it.id, p.id) }) {
                outPlaylists += p.copy(title = p.title.take(300), subtitle = p.subtitle.take(300), thumbnail = p.thumbnail?.takeIf { it.startsWith("https://") })
            }
            // A playlist sits in one folder at most: one already in a folder here stays where it is.
            val placed = mine.folders.flatMapTo(HashSet()) { it.playlists }
            val outFolders = mine.folders.toMutableList()
            for (f in theirs.folders.take(MAX_BROUGHT)) {
                if (f.id.length > 80) continue
                val i = outFolders.indexOfFirst { it.id == f.id }
                val fresh = f.playlists.filter { p -> p.length <= 120 && placed.none { samePlaylist(it, p) } }
                placed += fresh
                if (i >= 0) outFolders[i] = outFolders[i].copy(playlists = outFolders[i].playlists + fresh)
                else outFolders += f.copy(name = f.name.take(100), playlists = fresh)
            }
            mine.copy(
                lists = byId.values.sortedByDescending { it.updatedAt },
                albums = outAlbums.sortedByDescending { it.savedAt },
                playlists = outPlaylists.sortedByDescending { it.savedAt },
                folders = outFolders,
            )
        }
        return lists to albums
    }

    private fun edit(id: String, f: (PrismList) -> PrismList) = change { lib ->
        val l = lib.lists.firstOrNull { it.id == id } ?: return@change lib
        val changed = f(l).copy(updatedAt = System.currentTimeMillis())
        lib.copy(lists = listOf(changed) + lib.lists.filterNot { it.id == id })
    }

    @Synchronized
    private fun change(f: (PrismLibraryFile) -> PrismLibraryFile) {
        data.update(f)
        _lists.value = data.value.lists
        _albums.value = data.value.albums
        _playlists.value = data.value.playlists
        _folders.value = data.value.folders
        write(data.value)
    }

    private fun read(): PrismLibraryFile = runCatching {
        if (!file.isFile) PrismLibraryFile()
        else InnerTube.json.decodeFromString(PrismLibraryFile.serializer(), file.readText())
    }.getOrElse { PrismLibraryFile() }

    private fun write(lib: PrismLibraryFile) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "prism.json.tmp")
            tmp.writeText(InnerTube.json.encodeToString(PrismLibraryFile.serializer(), lib))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    companion object {
        /** Prism playlists' ids start with this. */
        const val PREFIX = "prism:"
        /** The most playlists, albums or folders, and songs in one playlist, another device's library brings. */
        private const val MAX_BROUGHT = 5_000
        private const val MAX_SONGS = 20_000
        fun isPrismList(id: String) = id.startsWith(PREFIX)

        /** A playlist's browse id ("VLPL…") and its plain id ("PL…") are the same playlist. */
        fun samePlaylist(a: String, b: String) = a.removePrefix("VL") == b.removePrefix("VL")

        /**
         * A YouTube Music mix (made for you, different each time): not something to keep (H7). YouTube Music's own
         * featured playlists ("RDCLAK5uy_…") start the same way but stay as they are, so they can be kept.
         */
        fun isMix(id: String) = id.removePrefix("VL").let { it.startsWith("RD") && !it.startsWith("RDCLAK") }
    }
}
