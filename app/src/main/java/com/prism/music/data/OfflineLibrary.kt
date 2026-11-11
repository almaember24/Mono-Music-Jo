package com.prism.music.data

import android.content.Context
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.SearchRank
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.ArtistPage
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.CollectionKind
import com.prism.music.data.model.CollectionPage
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.PodcastItem
import com.prism.music.data.model.Shelf
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import kotlinx.serialization.Serializable
import java.io.File

/**
 * What Prism keeps so the library works without a network (#55): the account's playlists, albums and artists as last
 * synced, and the playlists, albums and artists it has opened, with their songs. Offline, Library lists them as usual,
 * and a playlist opens from its saved copy and plays its downloaded songs (the rest show, greyed out).
 */
class OfflineLibrary(context: Context) {
    private val dir = File(context.filesDir, "offline").apply { mkdirs() }
    private val pagesDir = File(dir, "pages").apply { mkdirs() }
    /** Artist pages as they were last opened (1.8.2), one file each. */
    private val artistsDir = File(dir, "artists").apply { mkdirs() }
    private val listsFile = File(dir, "library.json")
    private val uploadsFile = File(dir, "uploads.json")

    @Serializable
    private data class Item(
        val kind: String, val id: String, val title: String, val subtitle: String, val thumbnail: String? = null,
        val playlistId: String? = null, val isMix: Boolean = false, val seedVideoId: String? = null, val params: String? = null,
        /** On an artist's page, a shelf can hold songs and videos. */
        val song: Song? = null, val plays: Long? = null,
    )

    @Serializable
    private data class Lists(val playlists: List<Item> = emptyList(), val albums: List<Item> = emptyList(), val artists: List<Item> = emptyList())

    @Serializable
    private data class Page(
        val id: String, val album: Boolean, val title: String, val subtitle: String, val secondSubtitle: String = "",
        val description: String? = null, val thumbnail: String? = null, val songs: List<Song>, val playlistId: String? = null,
        val artists: List<ArtistRef> = emptyList(), val owned: Boolean = false, val privacy: String? = null,
        val explicit: Boolean = false, val artistThumbnail: String? = null,
    )

    @Serializable
    private data class KeptShelf(
        val title: String, val strapline: String? = null, val items: List<Item>,
        val grid: Boolean = false, val rows: Boolean = false, val stacked: Boolean = false,
    )

    @Serializable
    private data class KeptArtist(
        val id: String, val name: String, val thumbnail: String? = null, val description: String? = null, val subscribers: String? = null,
        val views: String? = null, val channelId: String? = null, val subscribed: Boolean = false, val shelves: List<KeptShelf>,
    )

    @Serializable
    private data class KeptUploads(val songs: List<Song> = emptyList(), val albums: List<Item> = emptyList(), val artists: List<Item> = emptyList())

    data class Library(val playlists: List<BrowseItem>, val albums: List<BrowseItem>, val artists: List<BrowseItem>)

    private fun BrowseItem.toItem(): Item? = when (this) {
        is PlaylistItem -> Item("playlist", id, title, subtitle, thumbnail, isMix = isMix, seedVideoId = seedVideoId, params = params)
        is AlbumItem -> Item("album", id, title, subtitle, thumbnail, playlistId = playlistId)
        is ArtistItem -> Item("artist", id, title, subtitle, thumbnail)
        is PodcastItem -> Item("podcast", id, title, subtitle, thumbnail)
        is SongItem -> Item("song", id, title, subtitle, thumbnail, song = song, plays = plays)
        else -> null
    }

    private fun Item.toBrowse(): BrowseItem? = when (kind) {
        "playlist" -> PlaylistItem(id, title, subtitle, thumbnail, isMix, seedVideoId, params)
        "album" -> AlbumItem(id, title, subtitle, thumbnail, playlistId)
        "artist" -> ArtistItem(id, title, subtitle, thumbnail)
        "podcast" -> PodcastItem(id, title, subtitle, thumbnail)
        "song" -> song?.let { SongItem(it, plays) }
        else -> null
    }

    /** The library as last synced (null before the first sync). */
    fun library(): Library? = runCatching {
        val l = InnerTube.json.decodeFromString(Lists.serializer(), listsFile.readText())
        Library(l.playlists.mapNotNull { it.toBrowse() }, l.albums.mapNotNull { it.toBrowse() }, l.artists.mapNotNull { it.toBrowse() })
    }.getOrNull()

    fun saveLibrary(playlists: List<BrowseItem>, albums: List<BrowseItem>, artists: List<BrowseItem>) {
        val l = Lists(playlists.mapNotNull { it.toItem() }, albums.mapNotNull { it.toItem() }, artists.mapNotNull { it.toItem() })
        runCatching { atomicWrite(listsFile, InnerTube.json.encodeToString(Lists.serializer(), l)) }
    }

    private fun pageFile(id: String) = File(pagesDir, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    /** Keeps a playlist or album (opened as [id]) with its songs, for opening offline. */
    fun savePage(id: String, page: CollectionPage) {
        if (page.songs.isEmpty()) return
        val p = Page(
            page.id, page.kind == CollectionKind.ALBUM, page.title, page.subtitle, page.secondSubtitle, page.description, page.thumbnail,
            page.songs, page.playlistId, page.artists, page.owned, page.privacy, page.explicit, page.artistThumbnail,
        )
        runCatching { atomicWrite(pageFile(id), InnerTube.json.encodeToString(Page.serializer(), p)) }
        trimPages()
    }

    /** The saved copy of the playlist or album opened as [id], if there is one. */
    fun page(id: String): CollectionPage? = runCatching {
        val p = InnerTube.json.decodeFromString(Page.serializer(), pageFile(id).readText())
        CollectionPage(
            id = p.id, kind = if (p.album) CollectionKind.ALBUM else CollectionKind.PLAYLIST, title = p.title, subtitle = p.subtitle,
            secondSubtitle = p.secondSubtitle, description = p.description, thumbnail = p.thumbnail, songs = p.songs,
            playlistId = p.playlistId, artists = p.artists, owned = p.owned, privacy = p.privacy, explicit = p.explicit,
            artistThumbnail = p.artistThumbnail,
        )
    }.getOrNull()

    /** Every kept playlist and album with the id it was opened as, last kept first. Reads files: call off the main thread. */
    fun pages(): List<Pair<String, CollectionPage>> = pagesDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .sortedByDescending { it.lastModified() }
        .mapNotNull { f -> f.name.removeSuffix(".json").let { id -> page(id)?.let { id to it } } }

    private fun artistFile(id: String) = File(artistsDir, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    private fun keptShelves(page: ArtistPage, itemsEach: Int) = page.shelves.mapNotNull { s ->
        val items = s.items.take(itemsEach).mapNotNull { it.toItem() }
        if (items.isEmpty()) null else KeptShelf(s.title, s.strapline, items, s.grid, s.rows, s.stacked)
    }

    /**
     * Keeps an artist's page as it was opened, for opening offline (1.8.2): their shelves ([MAX_ARTIST_ITEMS] items
     * each at most), biography and facts. What only the network can use (mixes, shuffle, "See all") stays out. A page
     * over [MAX_ARTIST_BYTES] keeps shorter shelves and no biography, and one still over it isn't kept. False when
     * nothing was kept.
     */
    fun saveArtist(page: ArtistPage): Boolean {
        val full = KeptArtist(
            page.id, page.name, page.thumbnail, page.description?.take(MAX_ARTIST_BIO), page.subscribers, page.views, page.channelId, page.subscribed,
            keptShelves(page, MAX_ARTIST_ITEMS),
        )
        if (full.shelves.isEmpty()) return false
        var text = InnerTube.json.encodeToString(KeptArtist.serializer(), full)
        if (text.length > MAX_ARTIST_BYTES) text = InnerTube.json.encodeToString(KeptArtist.serializer(), full.copy(description = null, shelves = keptShelves(page, TRIMMED_ARTIST_ITEMS)))
        if (text.length > MAX_ARTIST_BYTES) return false
        runCatching { atomicWrite(artistFile(page.id), text) }.getOrElse { return false }
        artistsDir.listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.lastModified() }?.drop(MAX_ARTISTS)?.forEach { it.delete() }
        return true
    }

    private fun KeptArtist.toPage() = ArtistPage(
        id = id, name = name, thumbnail = thumbnail, description = description, subscribers = subscribers,
        shelves = shelves.mapNotNull { s ->
            val items = s.items.mapNotNull { it.toBrowse() }
            if (items.isEmpty()) null else Shelf(s.title, s.strapline, items, grid = s.grid, rows = s.rows, stacked = s.stacked)
        },
        radioPlaylistId = null, shufflePlaylistId = null, views = views, channelId = channelId, subscribed = subscribed,
    )

    private fun readArtist(file: File): KeptArtist? = runCatching { InnerTube.json.decodeFromString(KeptArtist.serializer(), file.readText()) }.getOrNull()

    /** The kept copy of the artist whose page is [id], if there is one. */
    fun artist(id: String): ArtistPage? = readArtist(artistFile(id))?.toPage()

    fun hasArtist(id: String): Boolean = artistFile(id).exists()

    /**
     * The kept copy of the artist called [name], when the page was kept under another of their ids (YouTube has more
     * than one for many artists, and an artist a song names without a link has none). Reads every copy: for when the id
     * found nothing.
     */
    fun artistNamed(name: String): ArtistPage? {
        val want = SearchRank.norm(name).ifBlank { return null }
        return artistsDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedByDescending { it.lastModified() }
            .firstNotNullOfOrNull { f -> readArtist(f)?.takeIf { SearchRank.norm(it.name) == want } }?.toPage()
    }

    /** Keeps the account's uploads as last listed (1.8.2), so Library → Uploads opens offline. */
    fun saveUploads(songs: List<Song>, albums: List<BrowseItem>, artists: List<BrowseItem>) {
        val kept = KeptUploads(songs.take(MAX_UPLOADS), albums.take(MAX_UPLOADS).mapNotNull { it.toItem() }, artists.take(MAX_UPLOADS).mapNotNull { it.toItem() })
        runCatching { atomicWrite(uploadsFile, InnerTube.json.encodeToString(KeptUploads.serializer(), kept)) }
    }

    data class Uploads(val songs: List<Song>, val albums: List<AlbumItem>, val artists: List<ArtistItem>)

    fun uploads(): Uploads? = runCatching {
        val k = InnerTube.json.decodeFromString(KeptUploads.serializer(), uploadsFile.readText())
        // A list kept before repeats were weeded out mustn't crash its page offline.
        Uploads(k.songs.distinctBy { it.id }, k.albums.mapNotNull { it.toBrowse() as? AlbumItem }.distinctBy { it.id }, k.artists.mapNotNull { it.toBrowse() as? ArtistItem }.distinctBy { it.id })
    }.getOrNull()

    /** Forgets everything kept (after signing out). */
    fun clear() {
        listsFile.delete()
        uploadsFile.delete()
        pagesDir.listFiles()?.forEach { it.delete() }
        artistsDir.listFiles()?.forEach { it.delete() }
    }

    /** When the copy of [id] was saved (0 if there's none). */
    fun savedAt(id: String): Long = pageFile(id).lastModified()

    /** Every saved page's cover and songs' covers, so the covers they need are kept. */
    fun covers(): List<String?> = pagesDir.listFiles()?.flatMap { f ->
        runCatching { InnerTube.json.decodeFromString(Page.serializer(), f.readText()).let { listOf(it.thumbnail) } }.getOrDefault(emptyList())
    }.orEmpty() + artistsDir.listFiles()?.mapNotNull { f -> readArtist(f)?.thumbnail }.orEmpty() +
        (library()?.let { l -> (l.playlists + l.albums + l.artists).map { it.thumbnail } } ?: emptyList())

    /** The most recently saved pages stay; the rest go (each is a few hundred KB at most). */
    private fun trimPages() {
        val files = pagesDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_PAGES).forEach { it.delete() }
    }

    private fun atomicWrite(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    internal companion object {
        const val MAX_PAGES = 400
        /** Artist pages kept: the most recently opened. Their photos go with them ([covers]). */
        const val MAX_ARTISTS = 120
        const val MAX_ARTIST_BYTES = 256 * 1024
        const val MAX_ARTIST_ITEMS = 40
        const val TRIMMED_ARTIST_ITEMS = 12
        const val MAX_ARTIST_BIO = 6_000
        /** Songs (and albums, and artists) of the account's uploads kept for offline. */
        const val MAX_UPLOADS = 5_000
    }
}
