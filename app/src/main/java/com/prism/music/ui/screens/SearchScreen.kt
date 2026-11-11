package com.prism.music.ui.screens

import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.R
import com.prism.music.tr
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.data.friendlyMessage
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.unit.em
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.innertube.TopSearch
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.SongItem
import com.prism.music.ui.Load
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.ErrorState
import com.prism.music.ui.components.ItemCarousel
import com.prism.music.ui.components.LoadingState
import com.prism.music.ui.components.MoodTile
import com.prism.music.ui.components.SectionHeader
import com.prism.music.ui.components.SongRow
import com.prism.music.ui.components.playable
import com.prism.music.ui.components.pane
import com.prism.music.ui.components.rememberPlayMenu
import com.prism.music.ui.rememberLoad
import com.prism.music.ui.theme.GlassSurface
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun history(context: Context) = context.getSharedPreferences("search", Context.MODE_PRIVATE)

@Composable
fun SearchScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    var query by rememberSaveable { mutableStateOf("") }
    // What the results are for: follows the typing, a moment after it pauses.
    var live by rememberSaveable { mutableStateOf("") }
    val settings = LocalAppSettings.current
    // Search opens on the listener's first chip (#224).
    val firstChip = remember { chipOrder(settings.searchChips).first() }
    var filter by rememberSaveable { mutableStateOf(firstChip ?: SearchFilter.TOP) }
    // Where to look (#46): YouTube Music, or the music on this phone (your files and downloads). Offline, the phone.
    var onPhone by rememberSaveable { mutableStateOf(firstChip == null || !c.network.isOnline()) }
    var focused by remember { mutableStateOf(false) }
    val suggestions = remember { mutableStateListOf<String>() }
    // Beside the completions while typing (1.7.6): the artists, songs and albums YouTube Music offers, with pictures.
    val suggestedItems = remember { mutableStateListOf<BrowseItem>() }
    // What was opened from Search lately, by its picture (1.7.6).
    var opened by remember { mutableStateOf(SearchMemory.opened(context)) }
    val recents = remember {
        mutableStateListOf<String>().apply { addAll(history(context).getString("q", "")!!.split("\n").filter { it.isNotBlank() }) }
    }
    var top by remember { mutableStateOf<Load<TopSearch>?>(null) }
    var list by remember { mutableStateOf<Load<List<BrowseItem>>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var continuation by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Worked out once from the first letter, apart from the typing: the next letter would cancel it and start it over.
    val typingTaste = remember {
        lazy {
            c.scope.async {
                runCatching { c.taste.searchTaste() }.getOrDefault(com.prism.music.data.innertube.SearchRank.Taste.NONE)
            }
        }
    }

    fun saveQuery(q: String) {
        val v = q.trim()
        // #296: nothing searched is kept while history is paused.
        if (v.isBlank() || com.prism.music.data.HistoryPause.paused(c.settings.current.behavior)) return
        recents.remove(v); recents.add(0, v)
        while (recents.size > 12) recents.removeAt(recents.lastIndex)
        history(context).edit().putString("q", recents.joinToString("\n")).apply()
    }

    fun submit(q: String) {
        if (q.isBlank()) return
        query = q; live = q.trim()
        focus.clearFocus()
        saveQuery(q)
    }

    // Settings → Layout & navigation → Search → "Open the keyboard" (#71): an empty Search comes up ready to type.
    LaunchedEffect(Unit) {
        if (c.settings.current.searchKeyboard && query.isBlank()) {
            delay(250) // after the tab's own transition, or the keyboard can come up and drop again
            runCatching { focusRequester.requestFocus() }
        }
    }
    LaunchedEffect(query) {
        if (query.isBlank()) { suggestions.clear(); suggestedItems.clear(); live = ""; return@LaunchedEffect }
        // The listener's own artists and searches come first (from the phone, at once), then YouTube's completions.
        val taste = typingTaste.value.await()
        val mySearches = recents.toList()
        // Off the main thread (#355): this looks through every artist the listener knows, for each letter.
        val mine = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            com.prism.music.data.innertube.SearchRank.predict(query, taste, mySearches, limit = 2)
        }
        suggestions.clear(); suggestions.addAll(mine)
        delay(140)
        val (s, items) = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { c.ytm.searchSuggestionsWithItems(query) } }
            .getOrDefault(emptyList<String>() to emptyList())
        suggestions.clear()
        suggestions.addAll((mine + s).distinctBy { com.prism.music.data.innertube.SearchRank.norm(it) }.take(if (items.isEmpty()) 4 else 3))
        suggestedItems.clear(); suggestedItems.addAll(com.prism.music.data.ExplicitFilter.items(c.settings.current.behavior.explicitContent, items).take(3))
    }
    LaunchedEffect(query) {
        if (query.isBlank()) return@LaunchedEffect
        delay(320)
        live = query.trim()
    }
    LaunchedEffect(live, filter, onPhone) {
        if (live.isBlank()) { top = null; list = null; return@LaunchedEffect }
        if (onPhone) { loading = false; return@LaunchedEffect }
        loading = true
        // Earlier results stay on screen until these land, so nothing flashes.
        if (filter == SearchFilter.TOP) {
            if (top == null) top = Load.Loading
            // Leaned toward what this listener plays (built on the phone, never sent anywhere).
            val taste = runCatching { c.taste.searchTaste() }.getOrDefault(com.prism.music.data.innertube.SearchRank.Taste.NONE)
            // Read into results off the main thread (#132: typing on a Galaxy Note 8 waited on it).
            // A search cancelled by the next keystroke (or by leaving) isn't a failure: runCatching would have caught the
            // cancellation and shown "The coroutine scope left the composition" as the error.
            // #216: with "Hide them", explicit songs aren't among the results.
            val explicit = c.settings.current.behavior.explicitContent
            top = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { c.ytm.searchTop(live, taste) } }
                .fold({ Load.Ok(com.prism.music.data.ExplicitFilter.top(explicit, it)) }, { if (it is kotlinx.coroutines.CancellationException) throw it; Load.Err(it.friendlyMessage(tr(R.string.search_failed))) })
            // The top result is the likeliest tap: when it's a song, get it ready (on Wi-Fi) so it starts at once.
            ((top as? Load.Ok)?.value?.top as? SongItem)?.song?.let { song ->
                val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
                if (cm?.isActiveNetworkMetered == false && !song.isLive) c.scope.launch(kotlinx.coroutines.Dispatchers.IO) { c.warmUp(song.id) }
            }
        } else {
            if (list == null) list = Load.Loading
            list = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { c.ytm.search(live, filter) } }
                .fold({ continuation = it.continuation; Load.Ok(com.prism.music.data.ExplicitFilter.items(c.settings.current.behavior.explicitContent, it.items)) }, { if (it is kotlinx.coroutines.CancellationException) throw it; Load.Err(it.friendlyMessage(tr(R.string.search_failed))) })
        }
        loading = false
    }
    // A line of the lyrics typed into the box finds its song (1.7.6): asked beside the ordinary search, so the title
    // matches never wait for it. Lines read like lines (four words or more); anything shorter isn't asked.
    val lyricHits by produceState(emptyList<com.prism.music.data.lyrics.LyricSearch.Hit>(), live, onPhone) {
        value = emptyList()
        if (live.isBlank() || onPhone) return@produceState
        value = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { c.lyricSearch.find(live) } }.getOrDefault(emptyList())
    }

    val moods = rememberLoad("moods") { c.taste.rank(runCatching { c.ytm.moodsAndGenres() }.getOrDefault(emptyList())) }
    val onSong: (SongItem) -> Unit = { c.player.playSingle(it.song) }
    val open: (BrowseItem) -> Unit = { item ->
        saveQuery(live); focus.clearFocus()
        SearchMemory.remember(context, item); opened = SearchMemory.opened(context)
        nav.open(item, onSong)
    }
    // Search's results that are already yours (1.7.6): liked songs and the library's playlists, albums and artists.
    val inLibrary by androidx.compose.runtime.produceState(emptyList<BrowseItem>(), live, onPhone) {
        value = if (live.isBlank() || onPhone) emptyList() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val words = phoneNorm(live).split(' ').filter { it.isNotBlank() }
            // Titles in other scripts by their Latin spelling too (1.8.3, #363), once spelled (in the background).
            val spelled = c.titleSpellings.known.value
            c.titleSpellings.want(c.library.likedShown.value.map { it.title })
            fun has(vararg parts: String) = phoneNorm(parts.joinToString(" ") { p -> spelled[p]?.let { "$p $it" } ?: p }).let { hay -> words.isNotEmpty() && words.all { it in hay } }
            val songs = c.library.likedShown.value.filter { has(it.title, it.artistText) }.take(3).map { SongItem(it) }
            val lists = (c.prismLibrary.lists.value.map { it.item() } + c.library.playlists.value + c.library.albums.value + c.library.artists.value)
                .filter { has(it.title) }.distinctBy { it.id }.take(4)
            // The listener's tags (1.8.3), opening their songs.
            val tags = c.db.tags().summaries().filter { has(it.tag) }.take(3).map { tagItem(it) }
            songs + tags + lists
        }
    }
    // Your music files and downloads that match, a few of them in Top results (all of them under "On this phone").
    val phoneTracks by c.localMusic.tracks.collectAsState()
    val onPhoneHits by androidx.compose.runtime.produceState(emptyList<com.prism.music.data.model.Song>(), live, onPhone, phoneTracks) {
        value = if (live.isBlank() || onPhone) emptyList() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            (phoneMatches(live, phoneTracks.map { it.song }) + phoneMatches(live, c.downloads.completedSongs())).distinctBy { it.id }.take(3)
        }
    }

    // The keyboard put away (Back, its own button) means "show me the results": the field stops being typed in, so the
    // suggestions give way to them. Only once the keyboard has been up, so a hardware keyboard keeps the suggestions.
    // #395: not when a window of its own (a result's menu) took it away, which closed the typed list under the menu.
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val imeUp = WindowInsets.isImeVisible
    val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    var imeWasUp by remember { mutableStateOf(false) }
    LaunchedEffect(imeUp, focused, windowFocused) {
        val step = SearchKeyboard.step(focused, imeUp, imeWasUp, windowFocused)
        imeWasUp = step.imeWasUp
        if (step.showResults) focus.clearFocus()
    }

    val ui = com.prism.music.ui.theme.LocalUi.current
    Column(Modifier.fillMaxSize()) {
        // The title steps aside while you type, leaving the results more room.
        androidx.compose.animation.AnimatedVisibility(!focused && query.isBlank()) {
            com.prism.music.ui.components.ScreenHeader(tr(R.string.search), Modifier.padding(bottom = 2.dp))
        }
        if (focused || query.isNotBlank()) Spacer(Modifier.statusBarsPadding())
        GlassSurface(Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().height(54.dp), shape = ui.shape(27.dp), elevation = 6.dp, inPage = true) {
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(PrismIcons.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(tr(R.string.songs_albums_artists_lyrics), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit(query) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onFocusChanged { focused = it.isFocused }
                            // A hardware keyboard's Enter (a tablet's, a Chromebook's) searches as the on-screen key does;
                            // it used to leave the suggestions over the results and the keyboard up.
                            .onPreviewKeyEvent { e ->
                                val enter = e.key == androidx.compose.ui.input.key.Key.Enter || e.key == androidx.compose.ui.input.key.Key.NumPadEnter
                                if (enter && e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) submit(query)
                                enter
                            },
                    )
                }
                if (query.isNotEmpty()) IconButton(onClick = { query = ""; live = ""; top = null; list = null; focusRequester.requestFocus() }) {
                    Icon(PrismIcons.Close, tr(R.string.clear))
                } else IconButton(onClick = { nav.go(com.prism.music.ui.Routes.RECOGNIZE) }) {
                    // What's playing around you (song recognition).
                    Icon(PrismIcons.Recognize, tr(R.string.widget_recognize_empty), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (query.isBlank()) {
            SearchLanding(
                recents, moods, bottomPadding, opened,
                onPick = { submit(it) },
                onRemove = { r -> recents.remove(r); history(context).edit().putString("q", recents.joinToString("\n")).apply() },
                onForget = { o -> SearchMemory.forget(context, o); opened = SearchMemory.opened(context) },
                onOpen = { item -> SearchMemory.remember(context, item); opened = SearchMemory.opened(context); nav.open(item, onSong) },
                onMood = { nav.open(it, onSong) },
            )
            return@Column
        }

        // While typing (1.7.6, owner): only what's being typed, completed, and the artists, songs and albums it names, with
        // their pictures. The chips and results under them wait until the search is made, so the page isn't stacked
        // with three kinds of rows at once.
        if (focused) {
            LazyColumn(contentPadding = PaddingValues(top = 2.dp, bottom = bottomPadding + 16.dp)) {
                item("search-for") { SuggestionRow(PrismIcons.Search, tr(R.string.search_for, query.trim()), onFill = null, strong = true) { submit(query) } }
                // Each once (#330/#346: a row repeated crashed the list with "Key was already used").
                items(suggestions.filterNot { it.equals(query.trim(), true) }.distinct(), key = { "s:$it" }) { s -> SuggestionRow(PrismIcons.Search, s, onFill = { query = it }) { submit(s) } }
                // A tap opens or plays one straight away.
                items(suggestedItems.distinctBy { it.id }, key = { "i:" + it.id }) { item -> ResultRow(item) { saveQuery(query); open(item) } }
            }
            return@Column
        }

        // One row of chips: YouTube Music's kinds, and the music on this phone last (it was a row of its own).
        com.prism.music.ui.components.ChipRow(
            chipOrder(settings.searchChips), { if (it == null) onPhone else !onPhone && it == filter }, { it?.label ?: tr(R.string.on_this_phone) },
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 4.dp),
        ) { if (it == null) onPhone = true else { onPhone = false; filter = it } }
        Box(Modifier.fillMaxWidth().height(3.dp).padding(horizontal = 16.dp)) {
            if (loading) com.prism.music.ui.components.PrismProgressBar(null, Modifier.fillMaxWidth())
        }

        if (onPhone) PhoneResults(live, bottomPadding) { saveQuery(live); focus.clearFocus() }
        else if (filter == SearchFilter.TOP) {
            when (val r = top) {
                null, Load.Loading -> LoadingState()
                is Load.Err -> ErrorState(r.message)
                is Load.Ok -> TopResults(r.value, live, inLibrary, onPhoneHits, lyricHits, bottomPadding, onSeeAll = { filter = it }, onSeePhone = { onPhone = true }, open = open, onSongs = { songs, i ->
                    saveQuery(live); focus.clearFocus()
                    c.player.playQueue(songs, i, com.prism.music.playback.QueueSource(com.prism.music.playback.QueueKind.OTHER, tr(R.string.search)))
                }, onPlay = { item ->
                    saveQuery(live)
                    focus.clearFocus()
                    scope.launch { playTop(c, item) }
                })
            }
        } else when (val r = list) {
            null, Load.Loading -> LoadingState()
            is Load.Err -> ErrorState(r.message)
            is Load.Ok -> Box(Modifier.fillMaxSize()) {
                val resultsState = androidx.compose.foundation.lazy.rememberLazyListState()
                // Select many (1.8.3): the songs among the results.
                val resultSongs = remember(r.value) { r.value.filterIsInstance<SongItem>().map { it.song }.distinctBy { it.id } }
                com.prism.music.ui.components.SelectableSongs(resultSongs) {
                LazyColumn(state = resultsState, contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding + 16.dp)) {
                    items(r.value.distinctBy { it.id + it.title }, key = { it.id + it.title }) { item -> ResultRow(item) { open(item) } }
                    if (continuation != null) item {
                        LaunchedEffect(continuation) {
                            val token = continuation ?: return@LaunchedEffect
                            runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { c.ytm.search(live, filter, token) } }.onSuccess { more ->
                                continuation = more.continuation
                                list = Load.Ok(r.value + com.prism.music.data.ExplicitFilter.items(c.settings.current.behavior.explicitContent, more.items).filter { n -> r.value.none { it.id == n.id } })
                            }.onFailure { continuation = null }
                        }
                        LoadingState()
                    }
                }
                }
                // A long list of results (#376), which only grows as it's scrolled.
                com.prism.music.ui.components.FastScroller(
                    resultsState, Modifier.align(Alignment.CenterEnd).padding(bottom = bottomPadding),
                    description = tr(R.string.scroll_through_the_list), thumbOnly = true,
                ) { _ -> (resultsState.firstVisibleItemIndex + 1).toString() }
            }
        }
    }
}

/**
 * Search on this phone (#46): your music files and downloads whose title, artist or album has every word typed, the
 * best matches first (titles that start with it, then titles, artists and albums that have it). They play offline.
 */
@Composable
private fun PhoneResults(query: String, bottomPadding: Dp, onPlay: () -> Unit) {
    val c = LocalContainer.current
    val tracks by c.localMusic.tracks.collectAsState()
    val dls by c.downloadState.collectAsState()
    val current by c.player.currentSong.collectAsState()
    // Matched off the main thread: with a few thousand files and downloads, every letter typed went through all of
    // them while the screen waited (1.7.4). Null until the first answer for this query is in.
    val found by produceState<Pair<List<com.prism.music.data.model.Song>, List<com.prism.music.data.model.Song>>?>(null, query, tracks, dls) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            phoneMatches(query, tracks.map { it.song }) to phoneMatches(query, c.downloads.completedSongs())
        }
    }
    val (files, downloads) = found ?: return
    if (files.isEmpty() && downloads.isEmpty()) {
        com.prism.music.ui.components.EmptyState(
            PrismIcons.Phone, tr(R.string.nothing_on_this_phone_matches),
            if (tracks.isEmpty() && !c.localMusic.hasPermission()) tr(R.string.allow_access_to_your_music_files) else tr(R.string.your_music_files_and_downloads_are),
        )
        return
    }
    Box(Modifier.fillMaxSize()) {
        val resultsState = androidx.compose.foundation.lazy.rememberLazyListState()
        com.prism.music.ui.components.SelectableSongs(files + downloads) {
        LazyColumn(state = resultsState, contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding + 16.dp)) {
            listOf(tr(R.string.your_music_files) to files, tr(R.string.downloads) to downloads).forEachIndexed { g, (title, songs) ->
                if (songs.isEmpty()) return@forEachIndexed
                item(key = "h:$g") { SectionHeader(title, strapline = com.prism.music.data.model.songCount(songs.size)) }
                items(songs.size, key = { "$g:$it:" + songs[it].id }) { i ->
                    com.prism.music.ui.components.SwipeToQueue(songs[i]) {
                        SongRow(songs[i], playing = current?.id == songs[i].id) {
                            onPlay()
                            c.player.playQueue(songs, i, QueueSource(QueueKind.LIBRARY, tr(R.string.search_2, query)))
                        }
                    }
                }
            }
        }
        }
        com.prism.music.ui.components.FastScroller(
            resultsState, Modifier.align(Alignment.CenterEnd).padding(bottom = bottomPadding),
            description = tr(R.string.scroll_through_the_list), thumbOnly = true,
        ) { _ -> null }
    }
}

/**
 * [songs] (music files or downloads) with every typed word in their title, artist or album, the best first: titles
 * that start with it, then titles, artists and albums that have it.
 */
private fun phoneMatches(query: String, songs: List<com.prism.music.data.model.Song>): List<com.prism.music.data.model.Song> {
    val q = phoneNorm(query)
    val words = q.split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()
    fun rank(s: com.prism.music.data.model.Song): Int? {
        val title = phoneNorm(s.title)
        val artist = phoneNorm(s.artistText)
        val hay = "$title $artist ${phoneNorm(s.album?.title.orEmpty())}"
        if (!words.all { it in hay }) return null
        return when {
            title.startsWith(q) -> 0
            words.all { it in title } -> 1
            words.all { it in artist } -> 2
            else -> 3
        }
    }
    return songs.mapNotNull { s -> rank(s)?.let { it to s } }.sortedWith(compareBy({ it.first }, { it.second.title.lowercase() })).map { it.second }
}

/** Lower case, without accents or punctuation, so "Beyonce" finds "Beyoncé" and "ac dc" finds "AC/DC". */
internal fun phoneNorm(s: String): String = com.prism.music.data.LibrarySearch.norm(s)

/** The top card's play button: plays the song, the album or playlist from the start, or shuffles the artist. */
private suspend fun playTop(c: com.prism.music.AppContainer, item: BrowseItem) {
    runCatching {
        when (item) {
            is SongItem -> c.player.playSingle(item.song)
            is AlbumItem -> c.player.playQueue(c.ytm.album(item.id).songs, 0, QueueSource(QueueKind.ALBUM, item.title, originId = item.id, page = item))
            is PlaylistItem -> c.player.playQueue(c.ytm.playlist(item.id).songs, 0, QueueSource(QueueKind.PLAYLIST, item.title, originId = item.id, page = item))
            is ArtistItem -> {
                val a = c.ytm.artist(item.id)
                val list = a.shufflePlaylistId ?: a.radioPlaylistId ?: return
                val r = c.radio(a.shuffleVideoId ?: a.radioVideoId, list, a.shuffleParams ?: a.radioParams)
                c.player.playQueue(r.songs, 0, QueueSource(QueueKind.RADIO, a.name, originId = item.id))
            }
            else -> Unit
        }
    }
}

/** The best match up top, then songs, albums, artists, playlists and videos, best-matching first. */
@Composable
private fun TopResults(
    r: TopSearch, query: String, inLibrary: List<BrowseItem>, onPhoneHits: List<com.prism.music.data.model.Song>,
    lyricHits: List<com.prism.music.data.lyrics.LyricSearch.Hit>,
    bottomPadding: Dp, onSeeAll: (SearchFilter) -> Unit, onSeePhone: () -> Unit, open: (BrowseItem) -> Unit,
    onSongs: (List<com.prism.music.data.model.Song>, Int) -> Unit, onPlay: (BrowseItem) -> Unit,
) {
    // Lyric matches only when the search didn't find the song by its name: when the top card's title has half the words
    // typed or more ("the best you ever had" → "Tonight (Best You Ever Had)"), they were only clutter. Songs already
    // among the results don't show again.
    val q = com.prism.music.data.lyrics.LyricSearch.words(query)
    val titleFits = r.top?.let { t -> com.prism.music.data.lyrics.LyricSearch.coverage(q, com.prism.music.data.lyrics.LyricSearch.words(t.title)) >= 0.5f } == true
    val shownIds = (listOfNotNull(r.top?.id) + r.sections.flatMap { (_, items) -> items.map { it.id } }).toSet()
    val shownTitles = listOfNotNull(r.top?.title) + r.sections.flatMap { (_, items) -> items.map { it.title } }
    val hits = if (titleFits) emptyList() else com.prism.music.data.lyrics.LyricSearch.toShow(lyricHits, shownIds, shownTitles).distinctBy { it.song.id }
    if (r.isEmpty && inLibrary.isEmpty() && onPhoneHits.isEmpty() && hits.isEmpty()) { ErrorState(tr(R.string.no_results)); return }
    // A line typed nearly word for word: the song it's from comes first.
    val lyricsFirst = hits.firstOrNull()?.sure == true
    val hitSongs = hits.map { it.song }
    fun androidx.compose.foundation.lazy.LazyListScope.fromTheLyrics() {
        if (hits.isEmpty()) return
        item("lyr-h") { SectionHeader(tr(R.string.from_the_lyrics)) }
        items(hits, key = { "lyr:" + it.song.id }) { h -> LyricHitRow(h) { onSongs(hitSongs, hitSongs.indexOf(h.song)) } }
    }
    // Select many (1.8.3): every song row shown here is what "All" picks.
    val shownSongs = (hitSongs + inLibrary.filterIsInstance<SongItem>().map { it.song } + onPhoneHits +
        r.sections.filter { it.first == SearchFilter.SONGS }.flatMap { (_, items) -> items.filterIsInstance<SongItem>().map { it.song }.distinctBy { it.id }.take(4) })
        .distinctBy { it.id }
    com.prism.music.ui.components.SelectableSongs(shownSongs) {
    LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding + 16.dp)) {
        if (lyricsFirst) fromTheLyrics()
        r.top?.let { t -> item("top") { TopCard(t, { open(t) }, { onPlay(t) }) } }
        if (!lyricsFirst) fromTheLyrics()
        // Already yours (1.7.6): what in your library matches, before the rest.
        if (inLibrary.isNotEmpty()) {
            // All of it, with filters (1.8.3).
            item("lib-h") { val nav = com.prism.music.ui.LocalNavigator.current; SectionHeader(tr(R.string.in_your_library), onMore = { nav.go(com.prism.music.ui.Routes.librarySearch(query)) }) }
            items(inLibrary, key = { "lib:" + it.id }) { item -> ResultRow(item) { open(item) } }
        }
        if (onPhoneHits.isNotEmpty()) {
            item("phone-h") { SectionHeader(tr(R.string.on_this_phone), onMore = onSeePhone) }
            items(onPhoneHits, key = { "ph:" + it.id }) { s -> SongRow(s) { onSongs(onPhoneHits, onPhoneHits.indexOf(s)) } }
        }
        r.sections.forEach { (f, items) ->
            item("h:${f.name}") { SectionHeader(f.label, onMore = { onSeeAll(f) }) }
            when (f) {
                SearchFilter.SONGS -> {
                    val songs = items.filterIsInstance<SongItem>().map { it.song }.distinctBy { it.id }
                    items(songs.take(4), key = { "s:" + it.id }) { s ->
                        SongRow(s) { onSongs(songs, songs.indexOf(s)) }
                    }
                }
                else -> item("c:${f.name}") {
                    ItemCarousel(items, open, cardWidth = if (f == SearchFilter.VIDEOS || f == SearchFilter.LIVE) 150.dp else 140.dp)
                }
            }
        }
    }
    }
}

/** A song found by its lyrics: the row, and under it the line it has, as the song writes it. */
@Composable
private fun LyricHitRow(hit: com.prism.music.data.lyrics.LyricSearch.Hit, onClick: () -> Unit) {
    val current by LocalContainer.current.player.currentSong.collectAsState()
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        SongRow(hit.song, playing = current?.id == hit.song.id, onClick = onClick)
        Text(
            "“${hit.line}”",
            Modifier.padding(start = 78.dp, end = 20.dp, bottom = 6.dp).offset(y = (-4).dp),
            style = MaterialTheme.typography.bodySmall.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TopCard(item: BrowseItem, onClick: () -> Unit, onPlay: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val kind = when (item) {
        is ArtistItem -> tr(R.string.artist)
        is AlbumItem -> item.subtitle.substringBefore(" • ").takeIf { it in setOf("Album", "Single", "EP") }?.let { com.prism.music.data.model.kindName(it) } ?: tr(R.string.album)
        is PlaylistItem -> tr(R.string.playlist)
        is SongItem -> if (item.song.isLive) tr(R.string.live_2) else if (item.song.isVideo) tr(R.string.video) else tr(R.string.song)
        else -> ""
    }
    val detail = when (item) {
        is SongItem -> item.song.artistText
        is ArtistItem -> item.subtitle.removePrefix("Artist • ")
        else -> item.subtitle.split(" • ").drop(1).joinToString(" · ").ifBlank { item.subtitle }
    }
    val ui = com.prism.music.ui.theme.LocalUi.current
    // Long-pressed, the same menu as the rows under it: a song's actions, or an album's / playlist's / artist's play menu.
    val playMenu = rememberPlayMenu()
    var songMenu by remember { mutableStateOf(false) }
    if (songMenu && item is SongItem) com.prism.music.ui.components.SongActionsSheet(item.song) { songMenu = false }
    val longPress: (() -> Unit)? = if (item is SongItem) ({ songMenu = true }) else item.playable()?.let { p -> { playMenu(p) } }
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
            .pane(ui.card)
            .combinedClickable(onClick = onClick, onLongClick = longPress).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            LocalContainer.current.covers.art(item), Modifier.size(96.dp), when (item) { is ArtistItem -> ui.artistArt; is SongItem -> ui.songArt; else -> ui.albumArt }, size = 544,
            placeholderIcon = if (item is ArtistItem) PrismIcons.Person else PrismIcons.Album,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(tr(R.string.top_result), style = MaterialTheme.typography.labelSmall.copy(brush = com.prism.music.ui.theme.PrismTokens.spectrum(com.prism.music.ui.theme.LocalIsDark.current), letterSpacing = 0.1.em), fontWeight = FontWeight.Medium)
            Text(item.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(kind, detail).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // What this card started is playing: the button pauses and resumes it rather than starting it over (issue #5).
        val c = LocalContainer.current
        val source by c.queue.source.collectAsState()
        val current by c.player.currentSong.collectAsState()
        val isPlaying by c.player.isPlaying.collectAsState()
        val index by c.player.currentIndex.collectAsState()
        // A song card also covers its music video opening as the song (the queue's first item, seeded by the card).
        val mine = current != null && (
            source.originId == item.id || current?.id == item.id ||
                (item is SongItem && source.kind == QueueKind.SINGLE && source.seedId == item.id && index == 0)
            )
        val playing = mine && isPlaying
        // The play disc, edged in split light like the player's (one ring, no gap).
        Box(
            Modifier.size(50.dp).clip(CircleShape).background(scheme.onSurface)
                .border(1.5.dp, com.prism.music.ui.theme.PrismTokens.spectrumRing(), CircleShape).clickable { if (mine) c.player.togglePlay() else onPlay() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (playing) PrismIcons.Pause else PrismIcons.Play, if (playing) tr(R.string.widget_pause) else tr(R.string.widget_play), Modifier.size(22.dp), tint = scheme.surface)
        }
    }
}

@Composable
private fun SuggestionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onFill: ((String) -> Unit)?,
    strong: Boolean = false, onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp).height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(
            text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (strong) FontWeight.Medium else null,
        )
        if (onFill != null) IconButton(onClick = { onFill(text) }, Modifier.size(32.dp)) { Icon(PrismIcons.FillIn, tr(R.string.fill), Modifier.size(18.dp)) }
    }
}

@Composable
fun ResultRow(item: BrowseItem, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    if (item is SongItem) {
        SongRow(item.song, trailingInfo = if (item.song.isLive) tr(R.string.live_3) else if (item.song.isEpisode) tr(R.string.episode) else if (item.song.isVideo) tr(R.string.video) else null, onClick = onClick)
        return
    }
    val ui = com.prism.music.ui.theme.LocalUi.current
    val menu = rememberPlayMenu()
    val longPress = onLongClick ?: item.playable()?.let { p -> { menu(p) } }
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = longPress).padding(horizontal = 16.dp, vertical = ui.gap(7.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            LocalContainer.current.covers.art(item), Modifier.size(56.dp),
            when (item) { is ArtistItem -> ui.artistArt; is SongItem -> ui.songArt; else -> ui.albumArt }, size = 226,
            placeholderIcon = when (item) {
                is ArtistItem -> PrismIcons.Person
                is AlbumItem -> PrismIcons.Album
                else -> PrismIcons.QueueMusic
            },
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (item is com.prism.music.data.model.PodcastItem) listOf(tr(R.string.podcast), item.subtitle).filter { it.isNotBlank() }.joinToString(" • ")
                else item.subtitle.ifBlank { when (item) { is ArtistItem -> tr(R.string.artist); is AlbumItem -> tr(R.string.album); is PlaylistItem -> tr(R.string.playlist); else -> "" } },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Search's chips in the listener's order (#224): [saved] holds SearchFilter names and "PHONE" (null here, "On this
 * phone"); names it doesn't know are dropped and chips it doesn't name follow in Prism's order, so new ones still show.
 */
internal fun chipOrder(saved: List<String>): List<SearchFilter?> {
    val all: List<SearchFilter?> = SearchFilter.entries + null
    fun named(n: String) = if (n == PHONE_CHIP) null else SearchFilter.entries.firstOrNull { it.name == n }
    val picked = saved.filter { n -> n == PHONE_CHIP || SearchFilter.entries.any { it.name == n } }.distinct().map(::named)
    return picked + all.filter { it !in picked }
}

internal const val PHONE_CHIP = "PHONE"

internal fun chipName(f: SearchFilter?) = f?.name ?: PHONE_CHIP
