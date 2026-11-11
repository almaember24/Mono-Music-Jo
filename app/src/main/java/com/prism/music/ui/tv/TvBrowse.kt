@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.prism.music.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import com.prism.music.R
import com.prism.music.tr
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.theme.PrismIcons

/** One row of cards under a title, remembering its column (search results, artist pages). */
@Composable
fun TvShelfRow(shelf: TvShelf, onItem: (Int) -> Unit, tag: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        TvRowTitle(shelf.title, Modifier.padding(start = 48.dp))
        LazyRow(
            Modifier.rememberedFocus().testTag(tag),
            contentPadding = TvRowPadding,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            itemsIndexed(shelf.items) { i, item ->
                TvCard(item.art, item.title, item.subtitle, { onItem(i) }, Modifier.tvItem("$tag/$i"), round = item.kind == TvItem.Kind.ARTIST)
            }
        }
    }
}

/**
 * Search (TV): the search line (OK opens the TV's keyboard, typing shows suggestions as you go) and the microphone
 * (the remote's own voice button works anywhere too). With nothing typed: recent searches. With results: the top
 * result beside the songs, then albums, artists, playlists and videos in rows. Focus opens on the search line.
 */
@Composable
fun TvSearchScreen(
    query: String,
    onType: () -> Unit,
    onVoice: () -> Unit,
    recent: List<String>,
    onRecent: (String) -> Unit,
    top: TvItem?,
    songs: List<TvSong>,
    shelves: List<TvShelf>,
    onItem: (TvItem) -> Unit,
    onSong: (TvSong) -> Unit,
    modifier: Modifier = Modifier,
    typing: Boolean = false,
    onQueryChange: (String) -> Unit = {},
    onSubmit: () -> Unit = {},
    onStopTyping: () -> Unit = {},
    /** Counts searches sent: after each, focus goes to the first result once there is one (not left on the box). */
    submitted: Int = 0,
) {
    val scheme = MaterialTheme.colorScheme
    val firstResult = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    var focusedFor by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val hasResults = top != null || songs.isNotEmpty()
    // The search line takes focus back as the keyboard goes (its entry, asked again for a moment until it has it): the
    // first result only takes it from there, so the two never pull it back and forth (on a slow TV they did).
    var lineFocused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(submitted, hasResults, typing) {
        if (submitted == 0 || submitted == focusedFor || !hasResults || typing) return@LaunchedEffect
        kotlinx.coroutines.withTimeoutOrNull(2_500) { androidx.compose.runtime.snapshotFlow { lineFocused }.first { it } }
        kotlinx.coroutines.delay(100)
        repeat(20) {
            // Not placed yet: it throws, or says no.
            val took = runCatching { firstResult.requestFocus() }.map { (it as? Boolean) ?: true }.getOrDefault(false)
            if (took) { focusedFor = submitted; return@LaunchedEffect }
            kotlinx.coroutines.delay(50)
        }
    }
    LazyColumn(
        modifier.fillMaxSize().testTag(TAG_SEARCH),
        contentPadding = PaddingValues(top = 27.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Row(Modifier.padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // Typing: the line becomes a text box with the TV's keyboard up; its Search key (or Back) ends typing.
                if (typing) TvSearchInput(query, onQueryChange, onSubmit, onStopTyping)
                else Surface(
                    onClick = onType,
                    modifier = Modifier.width(620.dp).height(60.dp).onFocusChanged { lineFocused = it.isFocused }.tvItem("$TAG_SEARCH/field", entry = true),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(30.dp)),
                    colors = ClickableSurfaceDefaults.colors(containerColor = TvGlassFill, focusedContainerColor = scheme.onSurface, contentColor = scheme.onSurface, focusedContentColor = scheme.surface),
                    border = ClickableSurfaceDefaults.border(focusedBorder = tvFocusBorder(RoundedCornerShape(30.dp), TV_RING_THIN)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(PrismIcons.Search, null, Modifier.size(24.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(query.ifEmpty { tr(R.string.tv_search_hint) }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                TvIconButton(PrismIcons.Mic, tr(R.string.tv_voice_search), onVoice, Modifier.tvItem("$TAG_SEARCH/voice"))
            }
        }
        if (query.isEmpty()) {
            // How to search with a remote: the line, or the microphone beside it.
            item {
                Column(Modifier.padding(horizontal = 48.dp).testTag("$TAG_SEARCH/how"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr(R.string.tv_search_how), style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant)
                    Text(tr(R.string.tv_search_voice_how), style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant)
                }
            }
            if (recent.isNotEmpty()) item {
                Column {
                    TvRowTitle(tr(R.string.recent_searches), Modifier.padding(start = 48.dp))
                    LazyRow(Modifier.rememberedFocus(), horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = TvRowPadding) {
                        itemsIndexed(recent) { i, r -> TvButton(r, { onRecent(r) }, Modifier.tvItem("$TAG_SEARCH/recent/$i"), icon = PrismIcons.History) }
                    }
                }
            }
        } else {
            item {
                Row(Modifier.padding(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    if (top != null) Column(Modifier.width(200.dp)) {
                        Text(tr(R.string.top_result), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = scheme.primary, modifier = Modifier.padding(bottom = 12.dp))
                        TvCard(top.art, top.title, top.subtitle, { onItem(top) }, Modifier.focusRequester(firstResult).tvItem("$TAG_SEARCH/top"), round = top.kind == TvItem.Kind.ARTIST, width = 200.dp)
                    }
                    Column(Modifier.weight(1f)) {
                        TvRowTitle(tr(R.string.songs))
                        Column(Modifier.rememberedFocus(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            songs.take(5).forEachIndexed { i, s ->
                                val first = if (top == null && i == 0) Modifier.focusRequester(firstResult) else Modifier
                                TvSongRow(s.title, s.artist, s.length, { onSong(s) }, first.tvItem("$TAG_SEARCH/song/$i"), art = s.art)
                            }
                        }
                    }
                }
            }
            itemsIndexed(shelves) { r, shelf -> TvShelfRow(shelf, { i -> onItem(shelf.items[i]) }, "$TAG_SEARCH/row$r") }
        }
    }
}

/** The search line while typing: a text box with the TV's keyboard, focused at once. */
@Composable
private fun TvSearchInput(query: String, onQueryChange: (String) -> Unit, onSubmit: () -> Unit, onStop: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() }; keyboard?.show() }
    androidx.activity.compose.BackHandler { onStop() }
    // Typing goes to the TV's keyboard, not through Prism: no ambient screen over a search being typed.
    HoldsAmbient()
    androidx.compose.foundation.text.BasicTextField(
        value = query,
        onValueChange = { Idle.touched(); onQueryChange(it) },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(color = scheme.onSurface),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(scheme.primary),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSubmit() }, onDone = { onSubmit() }),
        modifier = Modifier.width(620.dp).height(60.dp)
            .background(TvGlassFill, RoundedCornerShape(30.dp))
            .border(TV_RING_THIN, tvRing(), RoundedCornerShape(30.dp))
            .focusRequester(focus).testTag("$TAG_SEARCH/input"),
        decorationBox = { inner ->
            Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(PrismIcons.Search, null, Modifier.size(24.dp), tint = scheme.onSurface)
                Spacer(Modifier.width(14.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(tr(R.string.tv_search_hint), style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant, maxLines = 1)
                    inner()
                }
            }
        },
    )
}

/**
 * Library (TV): tabs along the top (playlists, albums, artists, liked songs, history), the chosen one's things in a
 * grid below. Moving along the tabs switches what's shown; Down enters the grid, which remembers its place per tab.
 */
@Composable
fun TvLibraryScreen(
    tabs: List<String>,
    tab: Int,
    onTab: (Int) -> Unit,
    items: List<TvItem>,
    onItem: (TvItem) -> Unit,
    modifier: Modifier = Modifier,
    /** What an empty tab says instead (why, and what to do: signed out, sign in with the phone). */
    empty: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxSize().padding(start = 48.dp, top = 27.dp).testTag(TAG_LIBRARY)) {
        val light = tvLight()
        TabRow(
            selectedTabIndex = tab, modifier = Modifier.testTag("$TAG_LIBRARY/tabs"),
            // The tab shown: the split light under it (as the phone's tabs).
            indicator = { positions, _ ->
                positions.getOrNull(tab)?.let { p ->
                    Box(
                        Modifier.fillMaxSize().wrapContentSize(Alignment.BottomStart)
                            .offset(x = p.left).width(p.right - p.left).height(3.dp).background(light, RoundedCornerShape(2.dp)),
                    )
                }
            },
        ) {
            tabs.forEachIndexed { i, t ->
                // Light on the dark page, the tab shown brightest (the split light under it); focused, white on the pill.
                Tab(
                    selected = i == tab, onFocus = { onTab(i) }, onClick = { onTab(i) }, modifier = Modifier.tvItem("$TAG_LIBRARY/tab/$i", entry = i == tab),
                    colors = androidx.tv.material3.TabDefaults.underlinedIndicatorTabColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant, selectedContentColor = MaterialTheme.colorScheme.onSurface,
                        focusedContentColor = MaterialTheme.colorScheme.onSurface, focusedSelectedContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Text(t, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        if (items.isEmpty() && empty != null) {
            Box(Modifier.fillMaxSize().padding(end = 48.dp, bottom = 48.dp).testTag("$TAG_LIBRARY/empty")) { empty() }
            return@Column
        }
        LazyVerticalGrid(
            GridCells.Adaptive(132.dp),
            Modifier.fillMaxSize().rememberedFocus().testTag("$TAG_LIBRARY/grid"),
            contentPadding = PaddingValues(end = 48.dp, top = 6.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            itemsIndexed(items) { i, item ->
                TvCard(item.art, item.title, item.subtitle, { onItem(item) }, Modifier.tvItem("$TAG_LIBRARY/grid/$i"), round = item.kind == TvItem.Kind.ARTIST, width = 132.dp)
            }
        }
    }
}

/**
 * An album or playlist (TV): its big cover, title and details on the left with Play and Shuffle; the songs in a list
 * on the right. Focus opens on Play; Right moves into the songs (the list remembers its place). View and play only:
 * editing playlists stays on the phone.
 */
@Composable
fun TvCollectionScreen(
    title: String,
    details: String,
    art: String?,
    songs: List<TvSong>,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onSong: (Int) -> Unit,
    modifier: Modifier = Modifier,
    playing: Int = -1,
    accent: Color? = null,
) {
    val scheme = MaterialTheme.colorScheme
    // Into the songs (Right, or Down from the buttons): the song last picked here, else the one playing, else the first,
    // never whichever row happens to be level with the buttons.
    var last by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(-1) }
    val target = collectionEntry(last, playing, songs.size)
    val into = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    val list = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (playing - 1).coerceIn(0, (songs.size - 1).coerceAtLeast(0)))
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // The row to go to may not be drawn yet (a long list, made before its songs came, opens at the top): it's scrolled
    // to first, then focused (Down from Play did nothing, the release check).
    val goIn: () -> Unit = goIn@{
        if (songs.isEmpty() || target < 0) return@goIn
        if (runCatching { into.requestFocus() }.getOrDefault(false)) return@goIn
        // On the main thread, where the list is laid out (see TvLyricsList's glide).
        scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
            list.scrollToItem(target)
            repeat(20) { if (runCatching { into.requestFocus() }.getOrDefault(false)) return@launch; kotlinx.coroutines.delay(16) }
        }
    }
    val down = Modifier
        .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown && songs.isNotEmpty()) { goIn(); true } else false }
        .focusProperties { if (songs.isNotEmpty()) down = into }
    Box(modifier.fillMaxSize()) {
    TvPictureBackdrop(art)
    Row(Modifier.fillMaxSize().padding(TvSafe).testTag(TAG_COLLECTION)) {
        Column(Modifier.width(300.dp)) {
            Artwork(art, Modifier.size(300.dp), shape = RoundedCornerShape(16.dp), size = 720)
            Spacer(Modifier.height(20.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold), color = scheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(details, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TvButton(tr(R.string.widget_play), onPlay, down.tvItem("$TAG_COLLECTION/play", entry = true), icon = PrismIcons.Play, primary = true)
                TvButton(tr(R.string.shuffle), onShuffle, down.tvItem("$TAG_COLLECTION/shuffle"), icon = PrismIcons.Shuffle)
            }
        }
        Spacer(Modifier.width(48.dp))
        LazyColumn(
            Modifier.weight(1f).focusProperties {
                onEnter = { if (byDpad() && songs.isNotEmpty() && !runCatching { into.requestFocus() }.getOrDefault(false)) { cancelFocusChange(); goIn() } }
            }.focusGroup().testTag("$TAG_COLLECTION/songs"),
            state = list, verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(songs) { i, s ->
                TvSongRow(
                    s.title, s.artist, s.length, { onSong(i) },
                    (if (i == target) Modifier.focusRequester(into) else Modifier)
                        .onFocusChanged { if (it.isFocused) last = i }.tvItem("$TAG_COLLECTION/song/$i"),
                    number = i + 1, playing = i == playing,
                )
            }
        }
    }
    }
}

/** Where focus enters an album's or playlist's songs: the one last picked, else the one playing, else the first. */
fun collectionEntry(last: Int, playing: Int, size: Int): Int = when {
    size <= 0 -> -1
    last in 0 until size -> last
    playing in 0 until size -> playing
    else -> 0
}

/**
 * An artist (TV): their picture and name with Play, Shuffle and Radio, then rows: popular songs, albums, singles, music
 * videos, artists fans also like. Focus opens on Play.
 */
@Composable
fun TvArtistScreen(
    name: String,
    details: String?,
    art: String?,
    shelves: List<TvShelf>,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onRadio: () -> Unit,
    onItem: (row: Int, index: Int) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.fillMaxSize()) {
    TvPictureBackdrop(art)
    LazyColumn(
        Modifier.fillMaxSize().testTag(TAG_ARTIST),
        contentPadding = PaddingValues(top = 27.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Row(Modifier.padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(art, Modifier.size(200.dp), shape = CircleShape, size = 544, placeholderIcon = PrismIcons.Person)
                Spacer(Modifier.width(36.dp))
                Column {
                    Text(name, style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.SemiBold), color = scheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (details != null) Text(details, style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        TvButton(tr(R.string.widget_play), onPlay, Modifier.tvItem("$TAG_ARTIST/play", entry = true), icon = PrismIcons.Play, primary = true)
                        TvButton(tr(R.string.shuffle), onShuffle, Modifier.tvItem("$TAG_ARTIST/shuffle"), icon = PrismIcons.Shuffle)
                        TvButton(tr(R.string.tv_radio), onRadio, Modifier.tvItem("$TAG_ARTIST/radio"), icon = PrismIcons.Radio)
                    }
                }
            }
        }
        itemsIndexed(shelves) { r, shelf -> TvShelfRow(shelf, { i -> onItem(r, i) }, "$TAG_ARTIST/row$r") }
    }
    }
}

/**
 * An empty Library tab: signed out, why it's empty and "Sign in with your phone" ([onSignIn]); signed in, that there's
 * nothing here yet.
 */
@Composable
fun TvLibraryEmpty(signedIn: Boolean, onSignIn: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.padding(top = 24.dp).width(640.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            tr(if (signedIn) R.string.nothing_here_yet else R.string.tv_library_signed_out),
            style = MaterialTheme.typography.titleLarge, color = scheme.onSurfaceVariant,
        )
        if (!signedIn) TvButton(tr(R.string.tv_sign_in_with_phone), onSignIn, Modifier.tvItem("$TAG_LIBRARY/signin"), icon = PrismIcons.Phone, primary = true)
    }
}

const val TAG_SEARCH = "tv_search"
const val TAG_LIBRARY = "tv_library"
const val TAG_COLLECTION = "tv_collection"
const val TAG_ARTIST = "tv_artist"
