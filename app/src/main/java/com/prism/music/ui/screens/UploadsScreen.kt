package com.prism.music.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.data.Uploads
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.Releases
import com.prism.music.data.model.Song
import com.prism.music.data.model.songCount
import com.prism.music.data.opensOffline
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.tr
import com.prism.music.trp
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.EmptyState
import com.prism.music.ui.components.ErrorState
import com.prism.music.ui.components.ItemCard
import com.prism.music.ui.components.LoadingState
import com.prism.music.ui.components.PagerChips
import com.prism.music.ui.components.RoundAction
import com.prism.music.ui.components.SearchField
import com.prism.music.ui.components.SongRow
import com.prism.music.ui.components.SortBar
import com.prism.music.ui.components.SwipeToQueue
import com.prism.music.ui.components.pageTitleStyle
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.launch

private enum class UploadTab { SONGS, ALBUMS, ARTISTS }
private enum class UploadSongSort(val label: String) { LIBRARY(tr(R.string.library_order)), TITLE(tr(R.string.title)), ARTIST(tr(R.string.artist)), ALBUM(tr(R.string.album)), LENGTH(tr(R.string.length)) }
private enum class UploadAlbumSort(val label: String) { LIBRARY(tr(R.string.library_order)), TITLE(tr(R.string.title)), ARTIST(tr(R.string.artist)), YEAR(tr(R.string.newest_first)) }
private enum class UploadArtistSort(val label: String) { LIBRARY(tr(R.string.library_order)), NAME(tr(R.string.name)), MOST(tr(R.string.most_songs)) }

/** Every word typed turns up in [hay], as in Local files' search. */
internal fun matchesWords(query: String, hay: String): Boolean {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val text = hay.lowercase()
    return words.all { it in text }
}

/** An album card's artist: its subtitle's second part ("Album • Coldplay • 2014"), or its first when it names no kind. */
internal fun uploadAlbumArtist(subtitle: String): String {
    val parts = subtitle.split(" • ").map { it.trim() }
    return parts.firstOrNull { it.isNotBlank() && it !in com.prism.music.data.model.RELEASE_KINDS && it != "Playlist" && !(it.length == 4 && it.all(Char::isDigit)) }.orEmpty()
}

internal fun sortUploadedSongs(songs: List<Song>, by: String): List<Song> = when (by) {
    "TITLE" -> songs.sortedBy { it.title.lowercase() }
    "ARTIST" -> songs.sortedWith(compareBy<Song> { it.primaryArtist.lowercase() }.thenBy { it.album?.title?.lowercase() ?: "" }.thenBy { it.title.lowercase() })
    "ALBUM" -> songs.sortedWith(compareBy<Song> { it.album?.title?.lowercase() ?: "￿" }.thenBy { it.title.lowercase() })
    "LENGTH" -> songs.sortedByDescending { it.durationSec }
    else -> songs
}

internal fun sortUploadedAlbums(albums: List<AlbumItem>, by: String): List<AlbumItem> = when (by) {
    "TITLE" -> albums.sortedBy { it.title.lowercase() }
    "ARTIST" -> albums.sortedWith(compareBy<AlbumItem> { uploadAlbumArtist(it.subtitle).lowercase() }.thenBy { it.title.lowercase() })
    "YEAR" -> albums.sortedByDescending { Releases.yearOf(it) }
    else -> albums
}

/** How many songs an artist has: what their row says ("12 songs"), else how many of [songs] name them. */
internal fun uploadArtistCount(artist: ArtistItem, songs: List<Song>): Int =
    Regex("\\d+").find(artist.subtitle.replace(",", "").replace(".", ""))?.value?.toIntOrNull()
        ?: songs.count { s -> s.artists.any { it.id == artist.id || it.name.equals(artist.title, ignoreCase = true) } }

internal fun sortUploadedArtists(artists: List<ArtistItem>, songs: List<Song>, by: String): List<ArtistItem> = when (by) {
    "NAME" -> artists.sortedBy { it.title.lowercase().removePrefix("the ") }
    "MOST" -> artists.sortedByDescending { uploadArtistCount(it, songs) }
    else -> artists
}

/**
 * Library → Uploads (#332, #101; Reddit: "I want to be able to look for them by Album, Artist and such"): the music the
 * listener uploaded to YouTube Music, by song, album or artist, sorted and searched here. An album or artist opens as any
 * other does ([com.prism.music.data.innertube.YouTubeMusic] reads theirs). Offline, the lists as last kept; what can't
 * play is dimmed, as on album pages.
 */
@Composable
fun UploadsScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val settings = LocalAppSettings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by c.uploads.state.collectAsState()
    val lists by c.uploads.lists.collectAsState()
    LaunchedEffect(settings.isLoggedIn, settings.dataSyncId) { c.uploads.load() }

    var query by rememberSaveable { mutableStateOf("") }
    var songSort by remember { mutableStateOf(UploadSongSort.entries.firstOrNull { it.name == c.library.sortOf("uploads:songs") } ?: UploadSongSort.LIBRARY) }
    var albumSort by remember { mutableStateOf(UploadAlbumSort.entries.firstOrNull { it.name == c.library.sortOf("uploads:albums") } ?: UploadAlbumSort.LIBRARY) }
    var artistSort by remember { mutableStateOf(UploadArtistSort.entries.firstOrNull { it.name == c.library.sortOf("uploads:artists") } ?: UploadArtistSort.LIBRARY) }
    val songs = remember(lists.songs, songSort, query) {
        sortUploadedSongs(lists.songs, songSort.name).filter { s -> matchesWords(query, "${s.title} ${s.artistText} ${s.album?.title.orEmpty()}") }
    }
    val albums = remember(lists.albums, albumSort, query) { sortUploadedAlbums(lists.albums, albumSort.name).filter { matchesWords(query, "${it.title} ${it.subtitle}") } }
    val artists = remember(lists.artists, lists.songs, artistSort, query) { sortUploadedArtists(lists.artists, lists.songs, artistSort.name).filter { matchesWords(query, it.title) } }

    // Without a network only what's on the phone plays or opens; the rest is dimmed, as on album pages.
    val online by c.network.online.collectAsState()
    val dls by c.downloadState.collectAsState()
    val ready = remember(lists.songs, dls, online) { if (online) null else lists.songs.filter { c.availableOffline(it.id) }.map { it.id }.toSet() }
    val opens by produceState<Set<String>?>(null, lists, online) {
        value = if (online) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            (lists.albums + lists.artists).filter { c.opensOffline(it, lists.songs) }.map { it.id }.toSet()
        }
    }
    val source = QueueSource(QueueKind.LIBRARY, tr(R.string.uploads))
    val notHere = { Toast.makeText(context, tr(R.string.offline_not_on_phone), Toast.LENGTH_SHORT).show() }
    fun playable(list: List<Song>) = if (ready == null) list else list.filter { it.id in ready }
    fun play(list: List<Song>, shuffle: Boolean) {
        val q = playable(list)
        if (q.isEmpty()) { if (ready != null) notHere() } else c.player.playQueue(q, 0, source, shuffle = shuffle)
    }
    fun openItem(item: BrowseItem) {
        if (opens != null && item.id !in opens!!) notHere()
        else when (item) {
            is AlbumItem -> nav.go(Routes.album(item.id))
            is ArtistItem -> nav.go(Routes.artist(item.id))
            else -> {}
        }
    }

    // 1.8.3 (D49, Reddit): upload a file from the phone, and delete an upload (with a confirm). Both write to the
    // account, and only on a tap.
    val uploading by c.uploads.uploading.collectAsState()
    val pickFile = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        // In the app's scope: leaving the page doesn't cut an upload off halfway, and its result is still said.
        val app = context.applicationContext
        if (uri != null) c.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            c.uploads.upload(uri)
                .onSuccess { name -> Toast.makeText(app, tr(R.string.uploaded_s_it_shows_up_here, name), Toast.LENGTH_LONG).show() }
                .onFailure { e -> Toast.makeText(app, tr(R.string.couldn_t_upload_it_s, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show() }
        }
    }
    var deleting by remember { mutableStateOf<Song?>(null) }
    deleting?.let { s ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr(R.string.delete_s_from_youtube_music, s.title)) },
            text = { Text(tr(R.string.it_goes_from_your_uploads_for_good)) },
            confirmButton = {
                com.prism.music.ui.components.PrismTextButton(onClick = {
                    deleting = null
                    val app = context.applicationContext
                    c.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        c.uploads.delete(s)
                            .onSuccess { Toast.makeText(app, tr(R.string.deleted_from_your_uploads), Toast.LENGTH_SHORT).show() }
                            .onFailure { e -> Toast.makeText(app, tr(R.string.couldn_t_delete_it_s, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show() }
                    }
                }, modifier = Modifier.testTag("confirmDeleteUpload")) { Text(tr(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { com.prism.music.ui.components.PrismTextButton(onClick = { deleting = null }) { Text(tr(R.string.cancel)) } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(60.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundAction(PrismIcons.ArrowBack, tr(R.string.back)) { nav.back() }
            Spacer(Modifier.weight(1f))
            if (settings.isLoggedIn) {
                RoundAction(PrismIcons.Upload, tr(R.string.upload_a_song), Modifier.testTag("uploadSong")) {
                    if (uploading == null) pickFile.launch(Uploads.PICK_TYPES)
                }
                Spacer(Modifier.width(8.dp))
                RoundAction(PrismIcons.Sync, tr(R.string.refresh_uploads)) { scope.launch { c.uploads.load(force = true) } }
                if (lists.songs.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    RoundAction(PrismIcons.Shuffle, tr(R.string.shuffle)) { play(songs, shuffle = true) }
                    Spacer(Modifier.width(8.dp))
                    RoundAction(PrismIcons.Play, tr(R.string.widget_play)) { play(songs, shuffle = false) }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 10.dp)) {
            Text(tr(R.string.uploads), style = pageTitleStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (lists.songs.isNotEmpty()) Text(
                listOf(songCount(lists.songs.size), trp(R.plurals.n_albums, lists.albums.size)).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            uploading?.let { name ->
                Row(Modifier.padding(top = 6.dp).testTag("uploadingLine"), verticalAlignment = Alignment.CenterVertically) {
                    com.prism.music.ui.components.PrismSpinner(Modifier.size(14.dp), stroke = 1.5.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(tr(R.string.uploading_s, name), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        val empty = lists.songs.isEmpty() && lists.albums.isEmpty() && lists.artists.isEmpty()
        when {
            !settings.isLoggedIn -> EmptyState(PrismIcons.Upload, tr(R.string.uploads), tr(R.string.uploads_sign_in_body))
            empty && (state is Uploads.State.Idle || state is Uploads.State.Loading) -> LoadingState()
            empty && state is Uploads.State.Failed -> ErrorState(tr(R.string.uploads_couldn_t_load) + ": " + (state as Uploads.State.Failed).message) { scope.launch { c.uploads.load(force = true) } }
            empty -> EmptyState(PrismIcons.Upload, tr(R.string.uploads_none_title), tr(R.string.uploads_none_body))
            else -> {
                SearchField(query, tr(R.string.uploads_search), { query = it }, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp))
                val pager = rememberPagerState { UploadTab.entries.size }
                PagerChips(listOf(tr(R.string.songs), tr(R.string.albums), tr(R.string.artists)), pager)
                // Sideways swipes on a song queue it (SwipeToQueue), so the lists change from the chips, as in Local files.
                HorizontalPager(pager, Modifier.weight(1f), userScrollEnabled = false) { page ->
                    when (UploadTab.entries[page]) {
                        UploadTab.SONGS -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, top = 4.dp, end = 8.dp, bottom = bottomPadding + 16.dp)) {
                            item { SortBar(songCount(songs.size), UploadSongSort.entries, songSort, { it.label }) { songSort = it; c.library.setSort("uploads:songs", it.name) } }
                            if (songs.isEmpty()) item { NoUploadMatches(query) }
                            itemsIndexed(songs, key = { i, s -> "u" + s.id + ":" + i }) { _, s ->
                                SwipeToQueue(s) {
                                    SongRow(
                                        s, if (ready != null && s.id !in ready) Modifier.alpha(0.4f) else Modifier,
                                        extraActions = listOf(com.prism.music.ui.components.SheetItem(PrismIcons.Delete, tr(R.string.delete_from_your_uploads), danger = true) { deleting = s }),
                                    ) {
                                        if (ready != null && s.id !in ready) Toast.makeText(context, tr(R.string.you_re_offline_and_this_song), Toast.LENGTH_SHORT).show()
                                        else playable(songs).let { q -> c.player.playQueue(q, q.indexOfFirst { it.id == s.id }.coerceAtLeast(0), source) }
                                    }
                                }
                            }
                        }
                        UploadTab.ALBUMS -> LazyVerticalGrid(GridCells.Adaptive(160.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = bottomPadding + 16.dp)) {
                            item(span = { GridItemSpan(maxLineSpan) }) { SortBar(trp(R.plurals.n_albums, albums.size), UploadAlbumSort.entries, albumSort, { it.label }) { albumSort = it; c.library.setSort("uploads:albums", it.name) } }
                            if (albums.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { NoUploadMatches(query) }
                            items(albums, key = { "a" + it.id }) { a ->
                                ItemCard(a, if (opens != null && a.id !in opens!!) Modifier.alpha(0.4f) else Modifier, width = 400.dp) { openItem(a) }
                            }
                        }
                        UploadTab.ARTISTS -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding + 16.dp)) {
                            item { SortBar(trp(R.plurals.n_artists, artists.size), UploadArtistSort.entries, artistSort, { it.label }, Modifier.padding(horizontal = 8.dp)) { artistSort = it; c.library.setSort("uploads:artists", it.name) } }
                            if (artists.isEmpty()) item { NoUploadMatches(query) }
                            items(artists.size, key = { "r" + artists[it].id }) { i ->
                                androidx.compose.foundation.layout.Box(if (opens != null && artists[i].id !in opens!!) Modifier.alpha(0.4f) else Modifier) {
                                    ResultRow(artists[i]) { openItem(artists[i]) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoUploadMatches(query: String) {
    if (query.isBlank()) EmptyState(PrismIcons.Upload, tr(R.string.uploads_none_title), tr(R.string.uploads_none_body))
    else EmptyState(PrismIcons.Search, tr(R.string.no_matches), tr(R.string.nothing_in_matches, tr(R.string.uploads), query.trim()) + ".")
}
