package com.prism.music.data.imports

import com.prism.music.data.model.Song
import kotlinx.serialization.Serializable
import java.io.File

/**
 * A playlist being brought over: its songs by key, and the Prism playlist made for it once its songs were matched.
 * [uri]: Spotify's id for it when the download had one; a newer download finds it by that, else by its name.
 */
@Serializable
data class PlaylistJob(val name: String, val keys: List<String>, val prismId: String? = null, val added: Set<String> = emptySet(), val uri: String? = null) {
    /** [p] is this playlist in a newer download. */
    fun isSame(p: LibraryPlaylist): Boolean = if (uri != null && p.uri != null) uri == p.uri else name == p.name
}

/**
 * The library a data download brought (1.8.1), and how far bringing it got: songs are liked and put in playlists as
 * they're matched (a song picked later in "Needs a look" goes in then), albums saved and artists starred once found.
 * Kept in files/imports/library.json, so it carries on after a restart and is in backups with the history.
 */
@Serializable
data class LibraryJob(
    val liked: List<LibraryTrack> = emptyList(),
    val likedDone: Set<String> = emptySet(),
    val playlists: List<PlaylistJob> = emptyList(),
    val albums: List<LibraryAlbum> = emptyList(),
    val albumsDone: Set<String> = emptySet(),
    val artists: List<String> = emptyList(),
    val artistsDone: Set<String> = emptySet(),
) {
    /** Every song it waits on. */
    val keys: Set<String> get() = (liked.map { it.key } + playlists.flatMap { it.keys }).toSet()

    /** Albums and artists done, every playlist made (songs still to be found or picked may follow). */
    val settled: Boolean get() = albumsDone.size >= albums.size && artistsDone.size >= artists.size && playlists.all { it.prismId != null }

    /**
     * The same job with [lib] added (a newer download, or more of the same: nothing twice). A playlist brought before
     * (by its Spotify id, else its name) gets the download's new songs added to the same Prism playlist; songs gone from
     * it on Spotify stay in Prism's.
     */
    fun plus(lib: ParsedLibrary, chosen: LibraryChoice): LibraryJob = copy(
        liked = if (chosen.liked) (liked + lib.liked).distinctBy { it.key } else liked,
        playlists = if (chosen.playlists) mergePlaylists(lib.playlists) else playlists,
        albums = if (chosen.albums) (albums + lib.albums).distinctBy { albumKey(it) } else albums,
        artists = if (chosen.artists) (artists + lib.artists).distinctBy { ImportKeys.norm(it) } else artists,
    )

    private fun mergePlaylists(incoming: List<LibraryPlaylist>): List<PlaylistJob> {
        val out = playlists.toMutableList()
        // Each earlier playlist is one of the download's at most (Spotify lets two have one name).
        val taken = BooleanArray(playlists.size)
        for (p in incoming) {
            val keys = p.tracks.map { it.key }.distinct()
            val i = playlists.indices.firstOrNull { !taken[it] && out[it].isSame(p) }
            if (i == null) { out += PlaylistJob(p.name, keys, uri = p.uri); continue }
            taken[i] = true
            val old = out[i]
            val have = old.keys.toHashSet()
            out[i] = old.copy(name = p.name, keys = old.keys + keys.filter { have.add(it) }, uri = old.uri ?: p.uri)
        }
        return out
    }

    /** What [lib] would add to this job: new likes, new playlists, new songs of earlier ones, new albums and artists. */
    fun news(lib: ParsedLibrary): LibraryNews {
        val likedKeys = liked.map { it.key }.toHashSet() + likedDone
        val taken = BooleanArray(playlists.size)
        var newLists = 0
        var songs = 0
        var growing = 0
        for (p in lib.playlists) {
            val i = playlists.indices.firstOrNull { !taken[it] && playlists[it].isSame(p) }
            if (i == null) { newLists++; continue }
            taken[i] = true
            val have = playlists[i].keys.toHashSet() + playlists[i].added
            val more = p.tracks.map { it.key }.distinct().count { it !in have }
            if (more > 0) { songs += more; growing++ }
        }
        val albumKeys = albums.map { albumKey(it) }.toHashSet() + albumsDone
        val artistKeys = artists.map { ImportKeys.norm(it) }.toHashSet() + artistsDone
        return LibraryNews(
            liked = lib.liked.count { it.key !in likedKeys }, playlists = newLists, playlistSongs = songs, playlistsGrowing = growing,
            albums = lib.albums.count { albumKey(it) !in albumKeys }, artists = lib.artists.count { ImportKeys.norm(it) !in artistKeys },
        )
    }

    /**
     * What's kept when the history it came with is removed: what was already done (liked, added, saved, starred) and
     * which Prism playlist each one made, so bringing a download again later tops those up instead of making them
     * twice or liking again what the listener has since unliked. It waits on no song any more.
     */
    fun remembered(): LibraryJob = LibraryJob(
        likedDone = likedDone,
        playlists = playlists.filter { it.prismId != null }.map { it.copy(keys = emptyList()) },
        albumsDone = albumsDone, artistsDone = artistsDone,
    )

    /** What the listener chose to bring from a download's library. */
    data class LibraryChoice(val liked: Boolean = true, val playlists: Boolean = true, val albums: Boolean = true, val artists: Boolean = true)

    /** Where bringing the library happens (Prism's library, YouTube's search), apart so it can be tried without them. */
    interface Hands {
        /** The songs matched so far, of [keys] (a key's song, or none: not matched, or passed over). */
        suspend fun matched(keys: Collection<String>): Map<String, Song>
        /** Keys not looked up yet. */
        suspend fun pending(keys: Collection<String>): Set<String>
        suspend fun like(songs: List<Song>)
        fun createPlaylist(name: String, songs: List<Song>): String
        fun addToPlaylist(id: String, songs: List<Song>)
        fun playlistExists(id: String): Boolean
        /** Finds and saves an album; false when it wasn't found (it's not asked again). */
        suspend fun saveAlbum(album: LibraryAlbum): Boolean
        suspend fun starArtist(name: String): Boolean
    }

    /** Does what can be done now; returns the job as it stands after. */
    suspend fun apply(hands: Hands): LibraryJob {
        var job = this
        val all = keys
        val matched = hands.matched(all)
        val pending = hands.pending(all - matched.keys)
        // Liked Songs, in the download's order; one found later (or picked in "Needs a look") is liked then.
        val toLike = liked.filter { it.key !in likedDone && it.key in matched }
        if (toLike.isNotEmpty()) hands.like(toLike.mapNotNull { matched[it.key] })
        job = job.copy(likedDone = likedDone + toLike.map { it.key })
        // Playlists: made once every song has been looked up, with the ones found; later finds are added after.
        job = job.copy(playlists = job.playlists.map { p ->
            if (p.prismId == null) {
                if (p.keys.any { it in pending }) return@map p
                val found = p.keys.filter { it in matched }
                p.copy(prismId = hands.createPlaylist(p.name, found.mapNotNull { matched[it] }.distinctBy { it.id }), added = found.toSet())
            } else if (!hands.playlistExists(p.prismId)) {
                // The listener deleted it: left alone.
                p.copy(added = p.keys.toSet())
            } else {
                val more = p.keys.filter { it !in p.added && it in matched }
                if (more.isNotEmpty()) hands.addToPlaylist(p.prismId, more.mapNotNull { matched[it] })
                p.copy(added = p.added + more)
            }
        })
        val albumsLeft = job.albums.filter { albumKey(it) !in job.albumsDone }
        for (a in albumsLeft) {
            runCatching { hands.saveAlbum(a) }.onSuccess { job = job.copy(albumsDone = job.albumsDone + albumKey(a)) }
        }
        val artistsLeft = job.artists.filter { ImportKeys.norm(it) !in job.artistsDone }
        for (a in artistsLeft) {
            runCatching { hands.starArtist(a) }.onSuccess { job = job.copy(artistsDone = job.artistsDone + ImportKeys.norm(a)) }
        }
        return job
    }

    companion object {
        fun albumKey(a: LibraryAlbum) = ImportKeys.norm(a.title) + "|" + ImportKeys.norm(a.artist)

        fun load(file: File): LibraryJob =
            runCatching { ImportFiles.json.decodeFromString(serializer(), file.readText()) }.getOrNull() ?: LibraryJob()

        fun save(file: File, job: LibraryJob) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(ImportFiles.json.encodeToString(serializer(), job))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }
}
