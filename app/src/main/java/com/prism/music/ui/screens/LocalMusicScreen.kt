package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.trp
import com.prism.music.ui.components.PrismButton
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.data.local.LocalLossless
import com.prism.music.data.local.LocalMusic
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.LocalSection
import com.prism.music.data.model.songCount
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.EmptyState
import com.prism.music.ui.components.PagerChips
import com.prism.music.ui.components.PrismChip
import com.prism.music.ui.components.RoundAction
import com.prism.music.ui.components.SongRow
import com.prism.music.ui.components.SortBar
import com.prism.music.ui.components.SwipeToQueue
import com.prism.music.ui.components.pageTitleStyle
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.launch

/**
 * What Local files' lists can be ordered by (#35), and the two ways round each goes ([dirs]: the usual way first).
 * Artist and Album keep each album's songs in track order within them.
 */
private enum class SongKey(val label: String, val dirs: Pair<String, String> = AZ) {
    TITLE(tr(R.string.title)), ARTIST(tr(R.string.artist)), ALBUM(tr(R.string.album)), ADDED(tr(R.string.date_added), NEWEST), YEAR(tr(R.string.year), NEWEST),
    DURATION(tr(R.string.length), tr(R.string.longest_first) to tr(R.string.shortest_first)), SIZE(tr(R.string.file_size), tr(R.string.largest_first) to tr(R.string.smallest_first)),
}
private enum class AlbumKey(val label: String, val dirs: Pair<String, String> = AZ) {
    TITLE(tr(R.string.title)), ARTIST(tr(R.string.artist)), YEAR(tr(R.string.year), NEWEST), ADDED(tr(R.string.date_added), NEWEST), SONGS(tr(R.string.number_of_songs), MOST),
}
private enum class ArtistKey(val label: String, val dirs: Pair<String, String> = AZ) {
    NAME(tr(R.string.name)), SONGS(tr(R.string.number_of_songs), MOST), ALBUMS(tr(R.string.number_of_albums), MOST),
}
private val AZ = tr(R.string.a_to_z) to tr(R.string.z_to_a)
private val NEWEST = tr(R.string.newest_first) to tr(R.string.oldest_first)
private val MOST = tr(R.string.most_first) to tr(R.string.fewest_first)

/** One list's order: what by, and whether it's the other way round from usual ([SongKey.dirs]' second). */
private data class Order<K>(val key: K, val reversed: Boolean = false)

/**
 * Songs', albums' and artists' orders, kept in one setting: "songs:TITLE:0|albums:TITLE:0|artists:NAME:0". Before
 * #35 it held the songs' order alone ("TITLE", "ADDED", "ARTIST", "ALBUM"), which still reads.
 */
private data class LocalOrders(val songs: Order<SongKey>, val albums: Order<AlbumKey>, val artists: Order<ArtistKey>) {
    fun encode() = "songs:${songs.key}:${if (songs.reversed) 1 else 0}|albums:${albums.key}:${if (albums.reversed) 1 else 0}|" +
        "artists:${artists.key}:${if (artists.reversed) 1 else 0}"

    companion object {
        private inline fun <reified K : Enum<K>> part(v: String, name: String, def: K): Order<K> {
            val bits = v.split('|').firstOrNull { it.startsWith("$name:") }?.split(':') ?: return Order(def)
            val key = enumValues<K>().firstOrNull { it.name == bits.getOrNull(1) } ?: def
            return Order(key, bits.getOrNull(2) == "1")
        }

        fun parse(v: String): LocalOrders {
            if (!v.contains(':')) return LocalOrders(Order(SongKey.entries.firstOrNull { it.name == v } ?: SongKey.TITLE), Order(AlbumKey.TITLE), Order(ArtistKey.NAME))
            return LocalOrders(part(v, "songs", SongKey.TITLE), part(v, "albums", AlbumKey.TITLE), part(v, "artists", ArtistKey.NAME))
        }
    }
}

private fun <T> Comparator<T>.way(reversed: Boolean): Comparator<T> = if (reversed) this.reversed() else this

/** One album among the files (by Android's album id), its songs in track order. */
private class LocalAlbum(val id: Long, val title: String, val artist: String, val year: Int, val tracks: List<LocalMusic.Track>) {
    val art: String? get() = tracks.firstNotNullOfOrNull { it.song.thumbnail }
    /** When its newest file arrived on the phone. */
    val addedAt: Long get() = tracks.maxOf { it.addedAt }
}

private fun sorted(tracks: List<LocalMusic.Track>, o: Order<SongKey>): List<LocalMusic.Track> {
    val r = o.reversed
    val byTitle = compareBy<LocalMusic.Track> { it.song.title.lowercase() }
    val inAlbum = compareBy<LocalMusic.Track> { it.albumId }.thenBy { it.trackNumber }.thenBy { it.song.title.lowercase() }
    return tracks.sortedWith(
        when (o.key) {
            SongKey.TITLE -> byTitle.way(r)
            SongKey.ARTIST -> compareBy<LocalMusic.Track> { artistKey(it.song.primaryArtist) }.way(r).thenBy { it.song.album?.title?.lowercase() ?: "" }.then(inAlbum)
            SongKey.ALBUM -> compareBy<LocalMusic.Track> { it.song.album?.title?.lowercase() ?: "￿" }.way(r).then(inAlbum)
            SongKey.ADDED -> compareByDescending<LocalMusic.Track> { it.addedAt }.way(r).then(byTitle)
            SongKey.YEAR -> compareByDescending<LocalMusic.Track> { it.year }.way(r).then(inAlbum)
            SongKey.DURATION -> compareByDescending<LocalMusic.Track> { it.song.durationSec }.way(r).then(byTitle)
            SongKey.SIZE -> compareByDescending<LocalMusic.Track> { it.size }.way(r).then(byTitle)
        }
    )
}

private fun sortedAlbums(albums: List<LocalAlbum>, o: Order<AlbumKey>): List<LocalAlbum> {
    val r = o.reversed
    val unknownAlbum = tr(R.string.unknown_album)
    val unknownLast = compareBy<LocalAlbum> { it.title == unknownAlbum }
    val byTitle = compareBy<LocalAlbum> { it.title.lowercase() }
    return albums.sortedWith(
        when (o.key) {
            AlbumKey.TITLE -> unknownLast.then(byTitle.way(r))
            AlbumKey.ARTIST -> compareBy<LocalAlbum> { artistKey(it.artist) }.way(r).thenByDescending { it.year }.then(byTitle)
            AlbumKey.YEAR -> compareByDescending<LocalAlbum> { it.year }.way(r).then(byTitle)
            AlbumKey.ADDED -> compareByDescending<LocalAlbum> { it.addedAt }.way(r)
            AlbumKey.SONGS -> compareByDescending<LocalAlbum> { it.tracks.size }.way(r).then(byTitle)
        }
    )
}

private fun sortedArtists(artists: List<LocalArtist>, o: Order<ArtistKey>): List<LocalArtist> {
    val r = o.reversed
    val byName = compareBy<LocalArtist> { it.key == "￿" }.thenBy { it.name.lowercase().removePrefix("the ") }
    return artists.sortedWith(
        when (o.key) {
            ArtistKey.NAME -> compareBy<LocalArtist> { it.key == "￿" }.then(compareBy<LocalArtist> { it.name.lowercase().removePrefix("the ") }.way(r))
            ArtistKey.SONGS -> compareByDescending<LocalArtist> { it.tracks.size }.way(r).then(byName)
            ArtistKey.ALBUMS -> compareByDescending<LocalArtist> { it.albums.size }.way(r).then(byName)
        }
    )
}

/** How many of the newest files and albums "Recently added" and "Added albums" show. */
private const val ADDED_SONGS = 100
private const val ADDED_ALBUMS = 40

/** One artist among the files, with their albums. */
private class LocalArtist(val key: String, val name: String, val tracks: List<LocalMusic.Track>, val albums: List<LocalAlbum>) {
    val art: String? get() = albums.firstNotNullOfOrNull { it.art }
}

private fun artistKey(name: String) = name.trim().lowercase().ifBlank { "￿" }
private fun inTrackOrder(tracks: List<LocalMusic.Track>) =
    tracks.sortedWith(compareBy<LocalMusic.Track> { it.trackNumber.takeIf { n -> n > 0 } ?: Int.MAX_VALUE }.thenBy { it.song.title.lowercase() })

private fun albumsOf(tracks: List<LocalMusic.Track>): List<LocalAlbum> = tracks.groupBy { it.albumId }.map { (id, t) ->
    val artist = t.groupingBy { it.song.primaryArtist }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
    LocalAlbum(id, t.firstNotNullOfOrNull { it.song.album?.title?.takeIf(String::isNotBlank) } ?: tr(R.string.unknown_album), artist, t.maxOf { it.year }, inTrackOrder(t))
}.let { albums ->
    val unknown = tr(R.string.unknown_album)
    albums.sortedWith(compareBy<LocalAlbum> { it.title == unknown }.thenBy { it.title.lowercase() })
}

/**
 * Library → Local files: the phone's own music files ([LocalMusic]), playing from the files, by song, artist or album.
 * An artist or album opens in place, and Back closes it. Asks for access to the files first.
 */
@Composable
fun LocalMusicScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val tracks by c.localMusic.tracks.collectAsState()
    val scanned by c.localMusic.scanned.collectAsState()
    var allowed by remember { mutableStateOf(c.localMusic.hasPermission()) }
    val allow = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
        if (granted) {
            c.localMusic.start()
            // Lossless sync reads the same files.
            scope.launch { c.losslessSync.sync() }
        }
    }
    // Access given somewhere else (Android's settings, the lossless prompt) after Prism started: nothing has scanned
    // with it yet, so the page sat on its spinner until Prism was restarted.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) allowed = c.localMusic.hasPermission()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    androidx.compose.runtime.LaunchedEffect(allowed) { if (allowed && !c.localMusic.scanned.value) c.localMusic.start() }
    val settings = com.prism.music.ui.theme.LocalAppSettings.current
    val orders = remember(settings.localSort) { LocalOrders.parse(settings.localSort) }
    val sort = orders.songs
    val saveOrders: (LocalOrders) -> Unit = { c.settings.setLocalSort(it.encode()) }
    val sections = settings.localSections
    val pager = rememberPagerState { sections.size }
    var openArtist by rememberSaveable { mutableStateOf<String?>(null) }
    var openAlbum by rememberSaveable { mutableStateOf<Long?>(null) }
    var openRecent by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var choosingFolders by remember { mutableStateOf(false) }
    BackHandler(openArtist != null || openAlbum != null || openRecent) {
        // An album opened from an artist goes back to the artist.
        when {
            openAlbum != null -> openAlbum = null
            openArtist != null -> openArtist = null
            else -> openRecent = false
        }
    }
    val liked by c.library.likedIds.collectAsState()
    val recentIds by c.library.recentLocalIds.collectAsState()
    val dls by c.downloadState.collectAsState()

    val albums = remember(tracks) { albumsOf(tracks) }
    val artists = remember(tracks, albums) {
        tracks.groupBy { artistKey(it.song.primaryArtist) }.map { (key, t) ->
            val ids = t.map { it.albumId }.toSet()
            LocalArtist(key, t.first().song.primaryArtist.ifBlank { tr(R.string.unknown_artist) }, t, albums.filter { it.id in ids })
        }.sortedWith(compareBy<LocalArtist> { it.key == "￿" }.thenBy { it.name.lowercase().removePrefix("the ") })
    }
    val album = openAlbum?.let { id -> albums.firstOrNull { it.id == id } }
    val artist = openArtist?.let { k -> artists.firstOrNull { it.key == k } }

    // What each section lists (and what the Play / Shuffle buttons at the top play).
    val songsSorted = remember(tracks, sort) { sorted(tracks, sort).map { it.song } }
    val favourites = remember(tracks, liked, sort) { sorted(tracks.filter { it.song.id in liked }, sort).map { it.song } }
    val addedSongs = remember(tracks) { tracks.sortedByDescending { it.addedAt }.take(ADDED_SONGS).map { it.song } }
    val addedAlbums = remember(albums) { albums.sortedByDescending { it.addedAt }.take(ADDED_ALBUMS) }
    val downloads = remember(dls) { c.downloads.completedSongs() }
    val recent = remember(tracks, recentIds) {
        val byId = tracks.associateBy { it.song.id }
        recentIds.mapNotNull { byId[it]?.song }
    }
    // Search in Local files (#225): every word typed has to turn up in the title, the artists or the album, as on playlists.
    var query by rememberSaveable { mutableStateOf("") }
    val found = remember(tracks, sort, query) {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) emptyList()
        else sorted(tracks, sort).map { it.song }.filter { s -> "${s.title} ${s.artistText} ${s.album?.title.orEmpty()}".lowercase().let { hay -> words.all { it in hay } } }
    }
    val section = sections.getOrNull(pager.currentPage)
    fun playable(): Pair<List<Song>, String> = when {
        query.isNotBlank() && album == null && artist == null && !openRecent -> found to tr(R.string.local_files)
        album != null -> album.tracks.map { it.song } to album.title
        artist != null -> artist.albums.flatMap { a -> a.tracks.filter { t -> t in artist.tracks } }.map { it.song } to artist.name
        openRecent -> recent to tr(R.string.recently_played)
        else -> when (section) {
            LocalSection.FAVOURITES -> favourites to tr(R.string.favorites)
            LocalSection.ADDED_SONGS -> addedSongs to tr(R.string.recently_added)
            LocalSection.ADDED_ALBUMS -> addedAlbums.flatMap { a -> a.tracks.map { it.song } } to tr(R.string.added_albums)
            LocalSection.DOWNLOADS -> downloads to tr(R.string.downloads)
            else -> songsSorted to tr(R.string.local_files)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // As a tab of its own (Settings → Layout & navigation) the top level has nothing to go back to.
        val isTab = com.prism.music.data.prefs.NavTab.LOCAL in settings.navTabs
        val inside = openAlbum != null || openArtist != null || openRecent
        val title = album?.title ?: artist?.name ?: (if (openRecent) tr(R.string.recently_played) else tr(R.string.local_files))
        // With no Back, the title takes its place at the very top, beside the buttons (#156, as in 1.6.9); with Back,
        // the buttons are on a bar at the top and the title under them, as on Prism's other pages.
        val titleOnTop = isTab && !inside
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(60.dp).padding(start = if (titleOnTop) 20.dp else 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (titleOnTop) {
                Text(title, style = pageTitleStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
            }
            if (!isTab || inside) {
                RoundAction(PrismIcons.ArrowBack, tr(R.string.back)) {
                    when {
                        openAlbum != null -> openAlbum = null
                        openArtist != null -> openArtist = null
                        openRecent -> openRecent = false
                        else -> nav.back()
                    }
                }
            }
            if (!titleOnTop) Spacer(Modifier.weight(1f))
            // Recently played, Shuffle and Play at the top (#29), for whatever the page is showing.
            if (allowed && tracks.isNotEmpty()) {
                if (!inside) {
                    RoundAction(PrismIcons.History, tr(R.string.recently_played)) { openRecent = true }
                    Spacer(Modifier.width(8.dp))
                }
                RoundAction(PrismIcons.Shuffle, tr(R.string.shuffle)) {
                    val (list, title) = playable()
                    if (list.isNotEmpty()) c.player.playQueue(list, 0, source(title), shuffle = true)
                }
                Spacer(Modifier.width(8.dp))
                RoundAction(PrismIcons.Play, tr(R.string.widget_play)) {
                    val (list, title) = playable()
                    if (list.isNotEmpty()) c.player.playQueue(list, 0, source(title))
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 10.dp)) {
            if (!titleOnTop) Text(title, style = pageTitleStyle(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            // The count sits under the title (#29), where "Music files on this phone" was.
            val sub = when {
                album != null -> listOfNotNull(album.artist.ifBlank { null }, album.year.takeIf { it > 0 }?.toString(), songCount(album.tracks.size)).joinToString(" · ")
                artist != null -> listOf(songCount(artist.tracks.size), trp(R.plurals.n_albums, artist.albums.size)).joinToString(" · ")
                openRecent -> songCount(recent.size)
                allowed && tracks.isNotEmpty() -> listOf(songCount(tracks.size), trp(R.plurals.n_albums, albums.size)).joinToString(" · ")
                else -> null
            }
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        when {
            !allowed -> Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState(PrismIcons.Phone, tr(R.string.your_music_files), tr(R.string.play_the_songs_on_your_phone))
                PrismButton(onClick = { allow.launch(LocalLossless.permission) }) { Text(tr(R.string.allow_access_to_music_files)) }
            }
            tracks.isEmpty() -> Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (scanned) EmptyState(PrismIcons.Phone, tr(R.string.no_music_files_found), tr(R.string.songs_you_copy_onto_your_phone))
                else com.prism.music.ui.components.PrismSpinner(Modifier.padding(32.dp).size(30.dp))
                // Every folder left out: the way back can't be behind the chips, which aren't shown now.
                if (scanned && settings.behavior.localSkipFolders.isNotEmpty()) {
                    PrismButton(onClick = { choosingFolders = true }) { Text(tr(R.string.choose_folders)) }
                }
            }
            album != null -> TrackList(album.tracks.map { it.song }, album.title, bottomPadding, numbered = true)
            artist != null -> ArtistPage(artist, bottomPadding) { openAlbum = it.id }
            openRecent -> if (recent.isEmpty()) EmptyState(PrismIcons.History, tr(R.string.nothing_played_yet), tr(R.string.songs_from_your_phone_that_you))
                else TrackList(recent, tr(R.string.recently_played), bottomPadding)
            else -> {
                com.prism.music.ui.components.SearchField(
                    query, tr(R.string.search_in_local_files), { query = it },
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                )
                if (query.isNotBlank()) {
                    if (found.isEmpty()) EmptyState(PrismIcons.Search, tr(R.string.no_matches), tr(R.string.nothing_in_matches, tr(R.string.local_files), query.trim()) + ".")
                    else TrackList(found, tr(R.string.local_files), bottomPadding)
                } else {
                    SectionChips(sections, pager) { editing = true }
                    // Sideways swipes on a song queue it (SwipeToQueue), so the sections change from the chips.
                    HorizontalPager(pager, Modifier.weight(1f), userScrollEnabled = false) { page ->
                        when (sections.getOrNull(page)) {
                            LocalSection.SONGS -> SongsPage(songsSorted, sort, { saveOrders(orders.copy(songs = it)) }, tr(R.string.local_files), bottomPadding)
                            LocalSection.FAVOURITES -> if (favourites.isEmpty()) EmptyState(PrismIcons.Heart, tr(R.string.no_favorites_yet), tr(R.string.like_songs_from_your_phone_on))
                                else SongsPage(favourites, sort, { saveOrders(orders.copy(songs = it)) }, tr(R.string.favorites), bottomPadding)
                            LocalSection.ARTISTS -> ArtistsPage(sortedArtists(artists, orders.artists), bottomPadding, orders.artists, { saveOrders(orders.copy(artists = it)) }) { openArtist = it.key }
                            LocalSection.ALBUMS -> AlbumsPage(sortedAlbums(albums, orders.albums), bottomPadding, orders.albums, { saveOrders(orders.copy(albums = it)) }) { openAlbum = it.id }
                            LocalSection.ADDED_SONGS -> TrackList(addedSongs, tr(R.string.recently_added), bottomPadding)
                            LocalSection.ADDED_ALBUMS -> AlbumsPage(addedAlbums, bottomPadding) { openAlbum = it.id }
                            LocalSection.DOWNLOADS -> if (downloads.isEmpty()) EmptyState(PrismIcons.DownloadDone, tr(R.string.no_downloads_yet), tr(R.string.songs_you_download_in_prism_show))
                                else TrackList(downloads, tr(R.string.downloads), bottomPadding)
                            null -> {}
                        }
                    }
                }
            }
        }
    }
    if (editing) SectionsEditor(sections, onFolders = { choosingFolders = true }) { editing = false }
    if (choosingFolders) LocalFoldersSheet { choosingFolders = false }
}

/**
 * Which folders Local files lists (#207, #249): ticked ones show, and unticking a folder leaves it and everything below
 * it out. Only the list changes: the files stay where they are, and nothing else on the phone is touched.
 */
@Composable
fun LocalFoldersSheet(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val skips = com.prism.music.ui.theme.LocalAppSettings.current.behavior.localSkipFolders
    val counts by c.localMusic.folders.collectAsState()
    val rows = remember(counts, skips) { LocalMusic.folderRows(counts, skips) }
    com.prism.music.ui.components.PrismSheet(onDismiss = onDismiss) {
        Text(tr(R.string.folders_in_local_files), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 20.dp))
        Text(
            tr(R.string.untick_a_folder_to_leave_it_out), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
        )
        if (rows.isEmpty()) {
            Text(tr(R.string.no_music_files_found), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(20.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            itemsIndexed(rows, key = { _, r -> r.path }) { _, r ->
                Row(Modifier.fillMaxWidth().padding(start = 8.dp + 16.dp * r.depth.coerceAtMost(4), end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrismCheck(!r.skipped, enabled = !r.parentSkipped, label = r.name) { show ->
                        c.settings.setLocalSkipFolders(
                            // A folder given back brings back those below it that were only out because of it, not ones left out by name.
                            if (show) skips.filterNot { it.equals(r.path, ignoreCase = true) }.toSet() else skips + r.path,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            r.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (r.skipped) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(songCount(r.songs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** The section chips, then an Edit chip that opens [SectionsEditor]. */
@Composable
private fun SectionChips(sections: List<LocalSection>, pager: androidx.compose.foundation.pager.PagerState, onEdit: () -> Unit) {
    val scope = rememberCoroutineScope()
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(sections.size) { i ->
            PrismChip(pager.currentPage == i, { scope.launch { pager.animateScrollToPage(i) } }, sections[i].label)
        }
        item { PrismChip(false, onEdit, tr(R.string.edit), icon = PrismIcons.Customize) }
    }
}

/** Which sections Local files shows, and in what order (#29). At least one stays. */
@Composable
private fun SectionsEditor(sections: List<LocalSection>, onFolders: () -> Unit, onDismiss: () -> Unit) {
    val prefs = LocalContainer.current.settings
    com.prism.music.ui.components.PrismSheet(onDismiss = onDismiss) { close ->
        Text(tr(R.string.sections), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 20.dp))
        Text(
            tr(R.string.tick_the_sections_you_want_hold), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
        )
        @Composable
        fun SectionRow(s: LocalSection, i: Int, modifier: Modifier, grip: Modifier?, held: Boolean) {
            val on = s in sections
            Row(modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                PrismCheck(on, enabled = !on || sections.size > 1, label = s.label) { v -> prefs.setLocalSections(if (v) sections + s else sections - s) }
                Text(s.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                if (on && !held) {
                    androidx.compose.material3.IconButton(onClick = { prefs.setLocalSections(sections.toMutableList().apply { add(i - 1, removeAt(i)) }) }, enabled = i > 0) {
                        androidx.compose.material3.Icon(PrismIcons.ChevronUp, tr(R.string.move_up_2, s.label))
                    }
                    androidx.compose.material3.IconButton(onClick = { prefs.setLocalSections(sections.toMutableList().apply { add(i + 1, removeAt(i)) }) }, enabled = i < sections.lastIndex) {
                        androidx.compose.material3.Icon(PrismIcons.ChevronDown, tr(R.string.move_down_2, s.label))
                    }
                }
                if (grip != null) com.prism.music.ui.components.DragGrip(grip.padding(start = 4.dp), tr(R.string.drag, s.label))
            }
        }
        com.prism.music.ui.components.ReorderList(sections, { it }, { prefs.setLocalSections(it) }) { s, i, held, mod -> SectionRow(s, i, mod, Modifier.dragHandle(), held) }
        LocalSection.entries.filter { it !in sections }.forEach { s -> SectionRow(s, -1, Modifier, null, false) }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).clickable { close(onFolders) }.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Icon(PrismIcons.Folder, null)
            Spacer(Modifier.width(16.dp))
            Text(tr(R.string.choose_folders), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun source(title: String) = QueueSource(QueueKind.LIBRARY, title)

/** Songs that play as a list from the one tapped; swipe one to queue it. Play and Shuffle are at the top of the page. */
@Composable
private fun TrackList(songs: List<Song>, title: String, bottomPadding: Dp, numbered: Boolean = false) {
    val c = LocalContainer.current
    val current by c.player.currentSong.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding + 16.dp)) {
        itemsIndexed(songs, key = { i, s -> "t" + s.id + ":" + i }) { i, s ->
            SwipeToQueue(s) {
                SongRow(s, index = if (numbered) i + 1 else null, playing = current?.id == s.id) { c.player.playQueue(songs, i, source(title)) }
            }
        }
    }
}

/** Songs (or Favourites) in the chosen order; the sort is kept (Title unless changed). */
@Composable
private fun SongsPage(songs: List<Song>, sort: Order<SongKey>, onSort: (Order<SongKey>) -> Unit, title: String, bottomPadding: Dp) {
    val c = LocalContainer.current
    val current by c.player.currentSong.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 0.dp, bottom = bottomPadding + 16.dp)) {
        item {
            SortRow(songCount(songs.size), tr(R.string.sort_songs), SongKey.entries, sort, { it.label }, { it.dirs }, onSort)
        }
        itemsIndexed(songs, key = { _, s -> "l" + s.id }) { i, s ->
            SwipeToQueue(s) {
                SongRow(s, playing = current?.id == s.id) { c.player.playQueue(songs, i, source(title)) }
            }
        }
    }
}

@Composable
private fun ArtistsPage(artists: List<LocalArtist>, bottomPadding: Dp, sort: Order<ArtistKey>, onSort: (Order<ArtistKey>) -> Unit, onOpen: (LocalArtist) -> Unit) {
    val ui = LocalUi.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 0.dp, bottom = bottomPadding + 16.dp)) {
        item {
            SortRow(trp(R.plurals.n_artists, artists.size), tr(R.string.sort_artists), ArtistKey.entries, sort, { it.label }, { it.dirs }, onSort)
        }
        items(artists, key = { "a" + it.key }) { a ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(a) }.padding(horizontal = 16.dp, vertical = ui.gap(7.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(a.art, Modifier.size(56.dp), CircleShape, size = 226, placeholderIcon = PrismIcons.Person)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(songCount(a.tracks.size), trp(R.plurals.n_albums, a.albums.size)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Albums as tiles; with [sort], in the order chosen there (Added albums keeps its own, newest first). */
@Composable
private fun AlbumsPage(
    albums: List<LocalAlbum>, bottomPadding: Dp, sort: Order<AlbumKey>? = null, onSort: (Order<AlbumKey>) -> Unit = {}, onOpen: (LocalAlbum) -> Unit,
) {
    LazyVerticalGrid(
        GridCells.Adaptive(150.dp), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = if (sort != null) 0.dp else 4.dp, bottom = bottomPadding + 16.dp),
    ) {
        if (sort != null) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            SortRow(trp(R.plurals.n_albums, albums.size), tr(R.string.sort_albums), AlbumKey.entries, sort, { it.label }, { it.dirs }, onSort, inset = 4.dp)
        }
        items(albums, key = { "b" + it.id }) { a -> AlbumTile(a) { onOpen(a) } }
    }
}

/**
 * A list's count, and its order as a quiet pill (what it's by, and an arrow for which way round), which opens
 * [SortSheet] (#35).
 */
@Composable
private fun <K> SortRow(
    count: String, title: String, keys: List<K>, order: Order<K>, label: (K) -> String, dirs: (K) -> Pair<String, String>,
    onSort: (Order<K>) -> Unit, inset: Dp = 16.dp,
) {
    var open by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = inset, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(count, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Row(
            Modifier.clip(CircleShape).background(com.prism.music.ui.components.quietFill()).clickable(onClickLabel = title) { open = true }
                .padding(start = 12.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Icon(PrismIcons.Sort, null, Modifier.size(17.dp), tint = scheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(label(order.key), style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
            Spacer(Modifier.width(4.dp))
            androidx.compose.material3.Icon(
                if (order.reversed) PrismIcons.ChevronUp else PrismIcons.ChevronDown,
                if (order.reversed) dirs(order.key).second else dirs(order.key).first, Modifier.size(16.dp), tint = scheme.primary,
            )
        }
    }
    if (open) SortSheet(title, keys, order, label, dirs, onSort) { open = false }
}

/**
 * Choosing an order, in Prism's sheet: which way round at the top (in the order's own words: "A to Z", "Newest
 * first", "Longest first"…), then what to order by. Picking one applies it and closes; the way round applies at once.
 */
@Composable
private fun <K> SortSheet(
    title: String, keys: List<K>, order: Order<K>, label: (K) -> String, dirs: (K) -> Pair<String, String>,
    onSort: (Order<K>) -> Unit, onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var shown by remember { mutableStateOf(order) }
    com.prism.music.ui.components.PrismSheet(onDismiss = onDismiss) { close ->
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 20.dp))
        val (usual, other) = dirs(shown.key)
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrismChip(!shown.reversed, { shown = shown.copy(reversed = false); onSort(shown) }, usual, icon = PrismIcons.ChevronDown)
            PrismChip(shown.reversed, { shown = shown.copy(reversed = true); onSort(shown) }, other, icon = PrismIcons.ChevronUp)
        }
        keys.forEach { k ->
            val on = k == shown.key
            Row(
                Modifier.fillMaxWidth().clickable {
                    // A new order starts the usual way round; the one already chosen keeps its direction.
                    val next = if (on) shown else Order(k)
                    onSort(next)
                    close {}
                }.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label(k), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal, color = if (on) scheme.primary else scheme.onSurface,
                )
                if (on) androidx.compose.material3.Icon(PrismIcons.Check, null, tint = scheme.primary)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun AlbumTile(a: LocalAlbum, onClick: () -> Unit) {
    val ui = LocalUi.current
    Column(Modifier.padding(4.dp).clip(ui.tile).clickable(onClick = onClick).padding(4.dp)) {
        Artwork(a.art, Modifier.fillMaxWidth().aspectRatio(1f), ui.albumArt, size = 544, placeholderIcon = PrismIcons.Album)
        Spacer(Modifier.height(8.dp))
        Text(a.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(a.artist.ifBlank { null }, a.year.takeIf { it > 0 }?.toString()).joinToString(" · ").ifBlank { songCount(a.tracks.size) },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** An artist's songs, album by album, each album opening on its own. */
@Composable
private fun ArtistPage(artist: LocalArtist, bottomPadding: Dp, onOpenAlbum: (LocalAlbum) -> Unit) {
    val c = LocalContainer.current
    val current by c.player.currentSong.collectAsState()
    // Their songs on each album (an album can be shared with other artists).
    val byAlbum = remember(artist) {
        val mine = artist.tracks.map { it.song.id }.toSet()
        artist.albums.map { a -> a to a.tracks.filter { it.song.id in mine }.map { it.song } }.filter { it.second.isNotEmpty() }
    }
    val all = remember(byAlbum) { byAlbum.flatMap { it.second } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding + 16.dp)) {
        byAlbum.forEach { (album, songs) ->
            item(key = "h" + album.id) {
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenAlbum(album) }.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(album.art, Modifier.size(44.dp), LocalUi.current.albumArt, size = 226, placeholderIcon = PrismIcons.Album)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(album.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        album.year.takeIf { it > 0 }?.let { Text("$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    androidx.compose.material3.Icon(PrismIcons.ChevronRight, tr(R.string.open_album), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            itemsIndexed(songs, key = { i, s -> "s" + album.id + ":" + s.id + ":" + i }) { _, s ->
                SwipeToQueue(s) {
                    SongRow(s, playing = current?.id == s.id) { c.player.playQueue(all, all.indexOf(s), source(artist.name)) }
                }
            }
        }
    }
}
