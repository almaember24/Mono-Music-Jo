package com.prism.music.data

import com.prism.music.AppContainer
import com.prism.music.R
import com.prism.music.data.innertube.SearchRank
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.ArtistPage
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.CollectionKind
import com.prism.music.data.model.CollectionPage
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.Shelf
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Artists without a network (1.8.2; a listener asked, Reddit). A page opened online is kept the way albums and playlists
 * are ([OfflineLibrary.saveArtist]). Offline that copy opens; with no copy, a page is built from what's on the phone:
 * downloads, files, liked and played songs, the albums those come from, and the artist's photo if it's kept.
 */
class ArtistOpened(
    val page: ArtistPage,
    /** Not YouTube's answer of a moment ago: a copy kept earlier, or a page built from the phone. */
    val kept: Boolean = false,
    /** Built from what's on the phone, with no kept copy behind it. */
    val fromPhone: Boolean = false,
)

/** How big a kept artist's photo is saved: their page shows it the width of the phone. */
private const val ARTIST_PHOTO_PX = 1440

/**
 * An artist's page: from YouTube when there's a network; else (or when asking fails) the copy kept, or one made from the
 * phone. Throws what asking threw when there's nothing at all to show.
 */
suspend fun AppContainer.openArtist(id: String): ArtistOpened {
    if (!network.isOnline()) return artistOffline(id) ?: throw java.io.IOException(tr(R.string.you_re_offline_check_your_connection))
    return try {
        ArtistOpened(ytm.artist(id))
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        artistOffline(id) ?: throw e
    }
}

/** Keeps [page] and, unless data saver holds it back, the artist's photo. In the background: leaving the page doesn't stop it. */
fun AppContainer.keepArtist(page: ArtistPage) {
    scope.launch(Dispatchers.IO) {
        if (offline.saveArtist(page) && !dataSaving()) artwork.save(page.thumbnail, ARTIST_PHOTO_PX)
    }
}

/** The copy kept of artist [id], else a page made from the phone; null when Prism knows nothing about them. */
suspend fun AppContainer.artistOffline(id: String): ArtistOpened? = withContext(Dispatchers.IO) {
    offline.artist(id)?.let { return@withContext ArtistOpened(it, kept = true) }
    val name = artistName(id)
    name?.let { offline.artistNamed(it) }?.let { return@withContext ArtistOpened(it, kept = true) }
    if (name == null) return@withContext null
    val songs = artistSongsOnPhone(id, name)
    // Their real id when a song says it, so stars and "name:" pages agree with the rest of Prism.
    val realId = if (!id.startsWith(YouTubeMusic.ARTIST_BY_NAME)) id else songs.firstNotNullOfOrNull { s -> s.artists.firstOrNull { sameArtist(it, null, name) }?.id } ?: id
    ArtistOpened(ArtistFromPhone.page(realId, name, songs, artistPhoto(realId, name, songs)), kept = true, fromPhone = true)
}

/**
 * Every song by the artist Prism has: on the phone first (downloads, then files), then liked and played ones. Those that
 * can't play offline are at the end ([AppContainer.availableOffline] tells them apart).
 */
suspend fun AppContainer.artistSongsOnPhone(id: String, name: String): List<Song> = withContext(Dispatchers.IO) {
    val realId = id.takeUnless { it.startsWith(YouTubeMusic.ARTIST_BY_NAME) }
    fun by(s: Song) = s.artists.any { sameArtist(it, realId, name) }
    // A failed read leaves what's on the phone: the artist's page asks with nothing to catch it.
    val rows = readOrElse("$name's songs", emptyList()) {
        (realId?.let { db.songs().mineWithArtistId(it) }.orEmpty() + (if (name.isBlank()) emptyList() else db.songs().mineWithArtistName(name))).map { it.toSong() }
    }
    val all = (downloads.completedSongs() + localMusic.tracks.value.map { it.song } + rows).filter(::by).distinctBy { it.id }
    val (here, elsewhere) = all.partition { availableOffline(it.id) }
    here + elsewhere
}

/**
 * An album's page made from its songs on the phone, for an album with no kept copy (offline, from an artist's page or the
 * Downloads page, which used to end on an error). Null when none of its songs are here.
 */
suspend fun AppContainer.albumFromPhone(id: String): CollectionPage? = withContext(Dispatchers.IO) {
    val songs = (downloads.completedSongs().filter { it.album?.id == id } + db.songs().mineOnAlbum(id).map { it.toSong() }).distinctBy { it.id }
    val first = songs.firstOrNull() ?: return@withContext null
    val artists = first.artists
    CollectionPage(
        id = id, kind = CollectionKind.ALBUM, title = first.album?.title ?: return@withContext null,
        subtitle = artists.joinToString(", ") { it.name }, thumbnail = songs.firstNotNullOfOrNull { it.thumbnail },
        songs = songs, artists = artists,
    )
}

/** Whether tapping [item] goes anywhere without a network; [phone]: the page's artist's songs Prism has. Touches files: off the main thread. */
fun AppContainer.opensOffline(item: BrowseItem, phone: List<Song>): Boolean = when (item) {
    is SongItem -> availableOffline(item.id)
    is AlbumItem -> offline.savedAt(item.id) > 0 || phone.any { it.album?.id == item.id }
    is PlaylistItem -> !item.isMix && offline.savedAt(item.id) > 0
    is ArtistItem -> offline.hasArtist(item.id)
    else -> false
}

private suspend fun AppContainer.artistName(id: String): String? {
    if (id.startsWith(YouTubeMusic.ARTIST_BY_NAME)) return id.removePrefix(YouTubeMusic.ARTIST_BY_NAME)
    library.artists.value.firstOrNull { it.id == id }?.let { return it.title }
    artistPrefs.favorites.value.firstOrNull { it.id == id }?.let { return it.name }
    val rows = db.songs().mineWithArtistId(id, limit = 5).map { it.toSong() }
    return (downloads.completedSongs() + rows).firstNotNullOfOrNull { s -> s.artists.firstOrNull { it.id == id }?.name?.takeIf(String::isNotBlank) }
}

/** The artist's photo if it's on the phone (it shows without a network); else a cover of theirs, for the page's colors. */
private fun AppContainer.artistPhoto(id: String, name: String, songs: List<Song>): String? {
    val known = listOfNotNull(
        library.artists.value.firstOrNull { it.id == id }?.thumbnail,
        artistPrefs.favorites.value.firstOrNull { it.id == id }?.thumbnail,
        library.artistPhoto(library.artistThumbs.value, id, name),
    )
    return known.firstOrNull { artwork.has(it) } ?: songs.firstOrNull { artwork.has(it.thumbnail) }?.thumbnail ?: known.firstOrNull() ?: songs.firstNotNullOfOrNull { it.thumbnail }
}

private fun sameArtist(ref: ArtistRef, id: String?, name: String) =
    (id != null && ref.id == id) || (name.isNotBlank() && SearchRank.norm(ref.name) == SearchRank.norm(name))

/** The page itself, apart from the phone it's read from. */
internal object ArtistFromPhone {
    /** [songs] in the order they should list. Shelf titles are the ones the artist page sorts by, not shown as they are. */
    fun page(id: String, name: String, songs: List<Song>, photo: String?): ArtistPage {
        val (videos, tracks) = songs.partition { it.isVideo }
        val albums = LibraryRepository.albumsFrom(tracks)
        return ArtistPage(
            id = id, name = name, thumbnail = photo, description = null, subscribers = null,
            shelves = listOfNotNull(
                Shelf("Songs", items = tracks.map(::SongItem)).takeIf { tracks.isNotEmpty() },
                Shelf("Albums", items = albums).takeIf { albums.isNotEmpty() },
                Shelf("Videos", items = videos.map(::SongItem)).takeIf { videos.isNotEmpty() },
            ),
            radioPlaylistId = null, shufflePlaylistId = null,
        )
    }
}
