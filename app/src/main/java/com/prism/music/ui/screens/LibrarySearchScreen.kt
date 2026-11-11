package com.prism.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.data.LibEntry
import com.prism.music.data.LibFilter
import com.prism.music.data.LibKind
import com.prism.music.data.LibrarySearch
import com.prism.music.data.model.SongItem
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.tr
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.components.EmptyState
import com.prism.music.ui.components.PrismChip
import com.prism.music.ui.components.SongRow
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Searching your library (1.8.3): every song, playlist, tag, artist and album that's yours, narrowed to one kind and,
 * for songs, to liked, downloaded or a file on this phone. Titles in other scripts are found by their Latin spelling
 * too (#363), once spelled in the background.
 */
@Composable
fun LibrarySearchScreen(initial: String, bottomPadding: androidx.compose.ui.unit.Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    var query by rememberSaveable { mutableStateOf(initial) }
    var kind by rememberSaveable { mutableStateOf<LibKind?>(null) }
    var liked by rememberSaveable { mutableStateOf(false) }
    var downloaded by rememberSaveable { mutableStateOf(false) }
    var onPhone by rememberSaveable { mutableStateOf(false) }
    val filter = LibFilter(kind, liked, downloaded, onPhone)

    val likedSongs by c.library.likedShown.collectAsState()
    val downloads by c.downloadState.collectAsState()
    val files by c.localMusic.tracks.collectAsState()
    val prismLists by c.prismLibrary.lists.collectAsState()
    val playlists by c.library.playlists.collectAsState()
    val albums by c.library.albums.collectAsState()
    val artists by c.library.artists.collectAsState()
    val spellings by c.titleSpellings.known.collectAsState()
    // Everything that's the listener's, as entries; read again when any of it changes.
    val entries by produceState<List<LibEntry>?>(null, likedSongs, downloads, files, prismLists, playlists, albums, artists) {
        value = withContext(Dispatchers.Default) {
            val tagged = runCatching { c.db.tags().allWithSongs() }.getOrDefault(emptyList())
            val songs = LibrarySearch.songEntries(
                likedSongs, c.downloads.completedSongs(), files.map { it.song },
                prismLists.flatMap { it.songs } + tagged.map { it.song.toSong() },
            )
            val tags = runCatching { c.db.tags().summaries() }.getOrDefault(emptyList()).map { LibEntry(LibKind.TAG, tagItem(it), listOf(it.tag)) }
            val lists = (prismLists.map { it.item() } + playlists.filter { it.id != "LM" }).distinctBy { it.id }.map { LibEntry(LibKind.PLAYLIST, it, listOf(it.title)) }
            val people = artists.distinctBy { it.id }.map { LibEntry(LibKind.ARTIST, it, listOf(it.title)) }
            val records = albums.distinctBy { it.id }.map { LibEntry(LibKind.ALBUM, it, listOf(it.title) + albumNames(it.subtitle, tr(R.string.single))) }
            songs + tags + lists + people + records
        }
    }
    // Titles in other scripts get their Latin spelling, in the background, while this page is open.
    LaunchedEffect(entries) { entries?.let { e -> c.titleSpellings.want(e.flatMap { it.names }) } }
    val index = remember(entries, spellings) { entries?.let { LibrarySearch.Index(it, spellings) } }
    val found by produceState(emptyList<LibEntry>(), index, query, filter) {
        val i = index ?: return@produceState
        value = withContext(Dispatchers.Default) { LibrarySearch.find(i, query, filter) }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (initial.isBlank()) runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.statusBarsPadding().padding(start = 4.dp, end = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.back() }) { Icon(PrismIcons.ArrowBack,tr(R.string.back)) }
            com.prism.music.ui.components.SearchField(query, tr(R.string.search_your_library), { query = it }, Modifier.weight(1f).focusRequester(focus).testTag("librarySearchField"))
        }
        // One kind, or all of them.
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val kinds = listOf<LibKind?>(null) + LibKind.entries
            items(kinds.size) { i ->
                val k = kinds[i]
                PrismChip(kind == k, { kind = k }, kindLabel(k), Modifier.testTag("kind:${k?.name ?: "ALL"}"))
            }
        }
        // For songs: where they are.
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { PrismChip(liked, { liked = !liked }, tr(R.string.liked), Modifier.testTag("flag:liked"), icon = PrismIcons.HeartFilled.takeIf { liked }) }
            item { PrismChip(downloaded, { downloaded = !downloaded }, tr(R.string.downloaded), Modifier.testTag("flag:downloaded"), icon = PrismIcons.Check.takeIf { downloaded }) }
            item { PrismChip(onPhone, { onPhone = !onPhone }, tr(R.string.on_this_phone), Modifier.testTag("flag:onPhone"), icon = PrismIcons.Check.takeIf { onPhone }) }
        }
        val songs = remember(found) { found.mapNotNull { (it.item as? SongItem)?.song } }
        val source = QueueSource(QueueKind.OTHER, tr(R.string.your_library))
        LazyColumn(Modifier.fillMaxSize().testTag("libraryResults"), contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)) {
            if (entries != null && found.isEmpty() && (query.isNotBlank() || filter != LibFilter())) item("none") {
                EmptyState(PrismIcons.Search, tr(R.string.nothing_in_your_library_matches), tr(R.string.try_fewer_words_or_another_filter))
            }
            var last: LibKind? = null
            found.forEach { e ->
                if (e.kind != last && kind == null) {
                    val k = e.kind
                    item("h:${k.name}") {
                        Text(kindLabel(k), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 14.dp, bottom = 4.dp))
                    }
                }
                last = e.kind
                item("${e.kind.name}:${e.item.id}") {
                    val s = (e.item as? SongItem)?.song
                    if (s != null) SongRow(s) { c.player.playQueue(songs, songs.indexOfFirst { it.id == s.id }.coerceAtLeast(0), source) }
                    else ResultRow(e.item) { nav.open(e.item) { c.player.playSingle(it.song) } }
                }
            }
        }
    }
}

@Composable
private fun kindLabel(k: LibKind?): String = when (k) {
    null -> tr(R.string.all)
    LibKind.SONG -> tr(R.string.songs)
    LibKind.PLAYLIST -> tr(R.string.playlists)
    LibKind.TAG -> tr(R.string.tags)
    LibKind.ARTIST -> tr(R.string.artists)
    LibKind.ALBUM -> tr(R.string.albums)
}
