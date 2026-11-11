package com.prism.music.ui.screens

import kotlinx.coroutines.flow.runningFold
import com.prism.music.R
import com.prism.music.tr
import com.prism.music.trp
import com.prism.music.ui.theme.lit
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.data.friendlyMessage
import com.prism.music.data.albumFromPhone
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import com.prism.music.download.forRow
import androidx.compose.material3.pulltorefresh.pullToRefresh
import coil3.compose.AsyncImage
import com.prism.music.data.ShareLinks
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.CollectionKind
import com.prism.music.data.model.CollectionPage
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.Song
import com.prism.music.data.model.formatDuration
import com.prism.music.data.model.hiRes
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.Load
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.ArtworkAccent
import com.prism.music.ui.components.ActionSheet
import com.prism.music.ui.components.pane
import com.prism.music.ui.components.EmptyState
import com.prism.music.ui.components.ErrorState
import com.prism.music.ui.components.FrostedBackdrop
import com.prism.music.ui.components.ItemCarousel
import com.prism.music.ui.components.ScrollEdge
import com.prism.music.ui.components.LoadingState
import com.prism.music.ui.components.SectionHeader
import com.prism.music.ui.components.ShareSheet
import com.prism.music.ui.components.ShareTarget
import com.prism.music.ui.components.SheetItem
import com.prism.music.ui.components.SongRow
import com.prism.music.ui.components.dissolveBottom
import com.prism.music.ui.components.frostFill
import com.prism.music.ui.components.rememberArtPalette
import com.prism.music.ui.components.rememberFrost
import com.prism.music.ui.rememberLoad
import com.prism.music.ui.theme.GlassSurface
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalIsDark
import com.prism.music.ui.theme.LocalUi
import kotlinx.coroutines.launch

/** [PRISM]: a playlist made in Prism and kept on the phone ([com.prism.music.data.PrismLibrary]). */
enum class CollectionType {
    ALBUM, PLAYLIST, LIKED, DOWNLOADS, MIX, PRISM,
    /** One of the listener's tags (1.8.3): its songs, newest tagged first; renamed or deleted from its ⋯. */
    TAG,
}

/** Names of artists YouTube Music lists as similar ("Fans might also like"), for classifying obscure artists. */
private suspend fun relatedArtists(c: com.prism.music.AppContainer, s: Song): List<String> {
    val id = s.artists.firstOrNull { it.id != null }?.id ?: return emptyList()
    val artist = runCatching { c.ytm.artist(id) }.getOrNull() ?: return emptyList()
    return artist.shelves.filter { it.title.contains("like", true) || it.title.contains("similar", true) || it.title.contains("related", true) }
        .flatMap { it.items }.filterIsInstance<com.prism.music.data.model.ArtistItem>().map { it.title }
}

enum class SortMode(val label: String) {
    DEFAULT(tr(R.string.original_order)), TITLE(tr(R.string.title)), ARTIST(tr(R.string.artist)), GENRE(tr(R.string.genre)), DURATION(tr(R.string.duration)), ALBUM(tr(R.string.album)),
    /** Downloads only: the original order (newest first) the other way round (#77). */
    OLDEST(tr(R.string.oldest_first)),
}

@Composable
fun CollectionScreen(type: CollectionType, id: String, initialGenre: String?, bottomPadding: Dp) {
    val c = LocalContainer.current
    val liked by c.library.likedShown.collectAsState()
    val downloads by c.downloadState.collectAsState()

    val remote = when (type) {
        CollectionType.ALBUM -> rememberLoad("album:$id") { orSaved(c, id) { LiveCollection(c.ytm.album(id), c.scope, null) } }
        // The first page shows straight away; the rest of a long playlist streams in behind it.
        CollectionType.PLAYLIST -> rememberLoad("playlist:$id") { orSaved(c, id) { LiveCollection(c.ytm.playlist(id), c.scope, c.ytm::playlistPage) } }
        // Prism's own mixes (Daily Mix, the time-of-day mix, Release Radar): kept on the phone.
        CollectionType.MIX -> rememberLoad("mix:$id") {
            // A holiday mix (1.8.0): kept for the season, else made now; its cover is Prism's own drawing.
            com.prism.music.data.seasonal.HolidayMixes.byId(id)?.let { def ->
                val mix = c.seasonal.mix(id) ?: throw IllegalStateException(
                    if (def.shownOn(c.seasonal.today())) tr(R.string.this_mix_isn_t_made_yet) else tr(R.string.holiday_mix_out_of_season),
                )
                val cover = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { HolidayCovers.file(c.app, def) }
                return@rememberLoad LiveCollection(CollectionPage(mix.id, CollectionKind.PLAYLIST, mix.title, mix.subtitle, thumbnail = cover ?: mix.covers.firstOrNull(), songs = mix.songs), c.scope, null)
            }
            // Daily Mixes are kept in the database; mixes from your music are made on the spot (again, if need be).
            val mix = c.forYou.mix(id)
                ?: c.yourMusic.mix(id)
                ?: (if (com.prism.music.data.foryou.YourMusic.isLibraryMix(id)) c.yourMusic.sections(!c.network.isOnline()).mixes.firstOrNull { it.id == id } else null)
                ?: throw IllegalStateException(tr(R.string.this_mix_isn_t_made_yet))
            LiveCollection(CollectionPage(mix.id, CollectionKind.PLAYLIST, mix.title, mix.subtitle, thumbnail = mix.covers.firstOrNull(), songs = mix.songs), c.scope, null)
        }
        else -> null
    }
    // Songs added to this playlist from elsewhere in Prism (a song's menu, the queue): loaded again, also when the page
    // was waiting in a tab's back stack (Reddit, 1.7.9: the added song "wasn't in the playlist").
    if (type == CollectionType.PLAYLIST) {
        val editedAt = c.library.playlistEdits.collectAsState().value[id.removePrefix("VL")]
        var seenEdit by rememberSaveable(id) { mutableStateOf(editedAt) }
        LaunchedEffect(editedAt) {
            if (editedAt != null && editedAt != seenEdit) { seenEdit = editedAt; remote?.reload(true) }
        }
    }
    // Opened from YouTube Music: kept (with all its songs, once they're in) to open offline later (#55).
    (remote?.state as? Load.Ok)?.value?.takeIf { !it.offline }?.let { live ->
        val more by live.loadingMore.collectAsState()
        LaunchedEffect(live, more) {
            if (!more) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.offline.savePage(id, live.page.copy(songs = live.songs.value)) }
        }
    }
    val prismLists by c.prismLibrary.lists.collectAsState()
    // A Spotify playlist's Prism playlist (1.8.1): kept in step with Spotify as it opens. Spotify itself is only built
    // when it's connected (most never connect it).
    if (type == CollectionType.PRISM) LaunchedEffect(id) { com.prism.music.data.spotify.Spotify.pageOpened(c.app, id) { c.spotify } }
    val page: Load<LiveCollection> = when (type) {
        CollectionType.PRISM -> {
            val list = prismLists.firstOrNull { it.id == id }
            val rules = list?.smart
            // A smart playlist (1.8.3): what its rules find, again whenever the library's songs, plays, tags or
            // measurements change; the last of them shows meanwhile.
            // Counted, not the changed tables' names: two changes to the same table in a row are equal sets, and the
            // state would skip the second.
            val changes by remember(rules != null) {
                if (rules != null) c.db.invalidationTracker.createFlow("songs", "song_traits", "song_tags", "play_events")
                    .runningFold(0) { n, _ -> n + 1 } else kotlinx.coroutines.flow.flowOf(0)
            }.collectAsState(0)
            val downloadsNow = if (rules != null) downloads.size else 0
            val found by androidx.compose.runtime.produceState<com.prism.music.data.SmartResult?>(null, rules, changes, downloadsNow) {
                if (rules != null) value = runCatching { c.smart.run(rules) }.onFailure { android.util.Log.w("PrismSmart", "smart playlist didn't run", it) }.getOrNull() ?: value
            }
            LaunchedEffect(found) { found?.let { c.prismLibrary.keepFound(id, it.songs) } }
            if (list == null) Load.Err(tr(R.string.this_playlist_isn_t_on_this))
            else if (rules != null) Load.Ok(remember(list.title, found) {
                LiveCollection(
                    CollectionPage(list.id, CollectionKind.PLAYLIST, list.title, tr(R.string.smart_playlist_updates_itself),
                        thumbnail = (found?.songs ?: list.songs).firstOrNull()?.thumbnail, songs = found?.songs ?: list.songs),
                    c.scope, null, unmeasured = found?.unmeasured ?: 0,
                )
            })
            else Load.Ok(remember(list) {
                LiveCollection(
                    CollectionPage(list.id, CollectionKind.PLAYLIST, list.title, tr(R.string.prism_playlist_on_this_phone), description = list.description.ifBlank { null },
                        thumbnail = list.songs.firstOrNull()?.thumbnail, songs = list.songs),
                    c.scope, null,
                )
            })
        }
        CollectionType.LIKED -> Load.Ok(remember(liked) {
            LiveCollection(CollectionPage("liked", CollectionKind.PLAYLIST, tr(R.string.liked_songs), com.prism.music.data.model.songCount(liked.size), thumbnail = null, songs = liked), c.scope, null)
        })
        CollectionType.DOWNLOADS -> {
            val songs = remember(downloads) { c.downloads.completedSongs() }
            Load.Ok(remember(songs) {
                LiveCollection(CollectionPage("downloads", CollectionKind.PLAYLIST, tr(R.string.downloads), tr(R.string.available_offline), thumbnail = null, songs = songs), c.scope, null)
            })
        }
        CollectionType.TAG -> {
            val tagged by remember(id) { c.tags.songs(id) }.collectAsState(null)
            when (val songs = tagged) {
                null -> Load.Loading
                else -> Load.Ok(remember(songs) {
                    LiveCollection(CollectionPage(TAG_PAGE + id, CollectionKind.PLAYLIST, id, tr(R.string.your_tag), thumbnail = songs.firstOrNull()?.thumbnail, songs = songs), c.scope, null)
                })
            }
        }
        else -> remote!!.state
    }

    when (page) {
        Load.Loading -> Box(Modifier.fillMaxSize().statusBarsPadding()) { LoadingState(Modifier.align(Alignment.Center)) }
        is Load.Err -> Box(Modifier.fillMaxSize().statusBarsPadding()) { ErrorState(page.message, Modifier.align(Alignment.Center)) { remote?.reload(false) } }
        is Load.Ok -> CollectionContent(type, page.value, initialGenre, bottomPadding) { remote?.reload(true) }
    }
}

/**
 * Offline, the copy kept from the last time the playlist or album was opened (#55); online, it's loaded as usual, and
 * the kept copy stands in only if that fails.
 */
private suspend fun orSaved(c: com.prism.music.AppContainer, id: String, load: suspend () -> LiveCollection): LiveCollection {
    // No copy of an album: its songs on the phone (1.8.2; an artist's page and Downloads list such albums).
    suspend fun saved() = c.offline.page(id)?.let { LiveCollection(it, c.scope, null, offline = true) }
        ?: c.albumFromPhone(id)?.let { LiveCollection(it, c.scope, null, offline = true, fromPhone = true) }
    if (!c.network.isOnline()) saved()?.let { return it }
    return try { load() } catch (e: Exception) { saved() ?: throw e }
}

/** A collection whose songs keep arriving (continuation pages load in the background). */
class LiveCollection(
    val page: CollectionPage,
    scope: kotlinx.coroutines.CoroutineScope,
    loadMore: (suspend (String) -> YouTubeMusic.PlaylistChunk)?,
    /** The copy kept on the phone, shown without a network. */
    val offline: Boolean = false,
    /** Not a kept copy: made from the album's songs on the phone. */
    val fromPhone: Boolean = false,
    /** A smart playlist's songs its Sound rules can't see yet (1.8.3). */
    val unmeasured: Int = 0,
) {
    val songs = kotlinx.coroutines.flow.MutableStateFlow(page.songs.distinctBy { it.id })
    /** Each song's slot in an owned playlist, for taking it out again. */
    val setVideoIds = kotlinx.coroutines.flow.MutableStateFlow(page.setVideoIds)
    val loadingMore = kotlinx.coroutines.flow.MutableStateFlow(loadMore != null && page.continuation != null)

    init {
        if (loadMore != null && page.continuation != null) scope.launch {
            var token = page.continuation
            while (token != null && songs.value.size < 5_000) {
                val chunk = runCatching { loadMore(token) }.getOrNull() ?: break
                if (chunk.songs.isEmpty()) break
                songs.value = (songs.value + chunk.songs).distinctBy { it.id }
                if (chunk.setVideoIds.isNotEmpty()) setVideoIds.value = setVideoIds.value + chunk.setVideoIds
                token = chunk.next
            }
            loadingMore.value = false
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CollectionContent(type: CollectionType, live: LiveCollection, initialGenre: String?, bottomPadding: Dp, reload: () -> Unit) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = LocalAppSettings.current
    val current by c.player.currentSong.collectAsState()
    val downloads by c.downloadState.collectAsState()
    val songs by live.songs.collectAsState()
    val loadingMore by live.loadingMore.collectAsState()
    val page = live.page.copy(songs = songs)
    // Plain map + a version counter: results land in batches, so the list re-sorts a handful
    // of times rather than once per song.
    val genres = remember(page.id) { HashMap<String, String>() }
    // Further genres a song clearly also belongs to (beyond its main one in [genres]).
    val extraGenres = remember(page.id) { HashMap<String, List<String>>() }
    val extrasChecked = remember(page.id) { HashSet<String>() }
    var genreVersion by remember(page.id) { mutableStateOf(0) }
    fun genresOf(id: String): List<String> = listOfNotNull(genres[id]) + extraGenres[id].orEmpty()
    var genreFilter by rememberSaveable(page.id) { mutableStateOf(initialGenre) }
    // Liked songs', Downloads' and each playlist's sort is kept, so leaving the page doesn't undo it.
    val sortList = when (type) {
        CollectionType.LIKED -> "liked"
        CollectionType.DOWNLOADS -> "downloads"
        CollectionType.PLAYLIST, CollectionType.PRISM, CollectionType.TAG -> "playlist:${page.id}"
        else -> null
    }
    var sort by remember(page.id) {
        mutableStateOf(sortList?.let(c.library::sortOf)?.let { n -> SortMode.entries.firstOrNull { it.name == n } } ?: SortMode.DEFAULT)
    }
    var groupByGenre by remember(page.id) { mutableStateOf(sortList?.let { c.library.sortOf("$it:genre") } != null) }
    fun pickSort(m: SortMode) {
        sort = m
        sortList?.let { c.library.setSort(it, m.name.takeIf { m != SortMode.DEFAULT }) }
    }
    fun toggleGenreGroups() {
        groupByGenre = !groupByGenre
        sortList?.let { c.library.setSort("$it:genre", if (groupByGenre) "on" else null) }
    }
    var sortMenu by remember { mutableStateOf(false) }
    var query by rememberSaveable(page.id) { mutableStateOf("") }
    // Sorting is there for any list of songs; genres (chips, each song's genre, Group by genre) only while the listener
    // wants them (1.7.9, Reddit: "some people want that same order of the playlist").
    val sortable = type != CollectionType.ALBUM && songs.size > 1
    val showGenres = sortable && settings.behavior.playlistGenres
    // #310 (1.8.1): Downloads can leave the album's name out from under each song.
    val showAlbums = type != CollectionType.DOWNLOADS || settings.view.downloadsAlbums
    LaunchedEffect(showGenres) { if (!showGenres) { genreFilter = null; if (groupByGenre) toggleGenreGroups() } }
    val showSearch = type != CollectionType.ALBUM || songs.size > 12

    // Starting a song waits on its stream (a few seconds through NewPipe), so on Wi-Fi the first track's is fetched
    // while the page is being looked at: Play then starts straight away.
    LaunchedEffect(page.id, songs.isNotEmpty()) {
        val first = songs.firstOrNull { !c.downloads.isDownloaded(it.id) } ?: return@LaunchedEffect
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        if (cm?.isActiveNetworkMetered != false || type == CollectionType.DOWNLOADS) return@LaunchedEffect
        kotlinx.coroutines.delay(600) // not for pages only passed through
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.warmUp(first.id) }
    }

    // Genres are detected quietly per artist, a few at a time; chips fill in as each batch lands.
    // Main genres come first, then any clear second genre an artist also has.
    LaunchedEffect(page.id, songs.size) {
        if (!showGenres) return@LaunchedEffect
        val todo = songs.filter { it.id !in genres }
        if (todo.isNotEmpty()) c.meta.resolveGenres(todo, related = { s -> relatedArtists(c, s) }) { batch ->
            genres.putAll(batch)
            genreVersion++
        }
        val extraTodo = songs.filter { it.id in genres && it.id !in extrasChecked }
        if (extraTodo.isEmpty()) return@LaunchedEffect
        c.meta.resolveExtraGenres(extraTodo, extraTodo.associate { it.id to genres.getValue(it.id) }) { batch ->
            extraGenres.putAll(batch)
            genreVersion++
        }
        extrasChecked += extraTodo.map { it.id }
    }

    val genreCounts = remember(songs, genreVersion) {
        songs.flatMap { genresOf(it.id) }.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }
    }
    // #216: "Hide them" leaves explicit songs out of the list (they're never played either way).
    val explicit = settings.behavior.explicitContent
    val visible = remember(songs, genreFilter, sort, query, explicit, if (genreFilter != null || sort == SortMode.GENRE || groupByGenre) genreVersion else 0) {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val filtered = page.songs.filter { s ->
            com.prism.music.data.ExplicitFilter.shows(explicit, s) &&
            (genreFilter == null || genreFilter in genresOf(s.id)) &&
                // Every word typed has to turn up in the title, the artists or the album.
                (words.isEmpty() || "${s.title} ${s.artistText} ${s.album?.title.orEmpty()}".lowercase().let { hay -> words.all { it in hay } })
        }
        when (sort) {
            SortMode.DEFAULT -> filtered
            SortMode.TITLE -> filtered.sortedBy { it.title.lowercase() }
            SortMode.ARTIST -> filtered.sortedBy { it.artistText.lowercase() }
            SortMode.GENRE -> filtered.sortedWith(compareBy<Song> { genres[it.id] ?: "￿" }.thenBy { it.artistText.lowercase() })
            SortMode.DURATION -> filtered.sortedBy { it.durationSec }
            SortMode.ALBUM -> filtered.sortedBy { it.album?.title?.lowercase() ?: "" }
            SortMode.OLDEST -> filtered.asReversed()
        }
    }
    // The page's own id goes with the queue: exclusions ("leave this playlist out of my taste") and Smart Shuffle's
    // "Add to this playlist" know which playlist is playing.
    val source = QueueSource(
        if (type == CollectionType.ALBUM) QueueKind.ALBUM else QueueKind.PLAYLIST, page.title + (genreFilter?.let { " · ${com.prism.music.data.meta.Genres.shown(it)}" } ?: ""),
        originId = page.playlistId ?: page.id.removePrefix("VL").takeIf { type == CollectionType.PLAYLIST || type == CollectionType.MIX } ?: page.id,
        page = when (type) {
            CollectionType.ALBUM -> com.prism.music.data.model.AlbumItem(page.id, page.title, page.subtitle, page.thumbnail, page.playlistId)
            CollectionType.PLAYLIST, CollectionType.PRISM -> com.prism.music.data.model.PlaylistItem(page.id, page.title, page.subtitle, page.thumbnail)
            else -> null
        },
    )
    // Offline (#55): only songs on the phone (downloaded, cached whole, or files) play; the rest show greyed out.
    val online by c.network.online.collectAsState()
    val ready = remember(songs, downloads, online) { if (online) null else songs.filter { c.availableOffline(it.id) }.map { it.id }.toSet() }
    val playable = remember(visible, ready) { if (ready == null) visible else visible.filter { it.id in ready } }
    val playFrom: (List<Song>, Song, QueueSource) -> Unit = { list, s, src ->
        if (ready != null && s.id !in ready) Toast.makeText(context, tr(R.string.you_re_offline_and_this_song), Toast.LENGTH_SHORT).show()
        else {
            val q = if (ready == null) list else list.filter { it.id in ready }
            c.player.playQueue(q, q.indexOfFirst { it.id == s.id }.coerceAtLeast(0), src)
        }
    }
    val dim: (Song) -> Modifier = { s -> if (ready != null && s.id !in ready) Modifier.alpha(0.4f) else Modifier }
    val listState = rememberLazyListState()
    // A video row downloaded as its song counts as downloaded (#315).
    val standIns by c.downloadStandIns.collectAsState()
    val downloadedCount = page.songs.count { downloads.forRow(it.id, standIns)?.state == Download.STATE_COMPLETED }
    val downloadingCount = page.songs.count { downloads.forRow(it.id, standIns)?.let { d -> d.state != Download.STATE_COMPLETED && d.state != Download.STATE_FAILED } == true }
    val totalSec = visible.sumOf { it.durationSec }
    val popular = remember(live.page) { if (type == CollectionType.ALBUM) live.page.popularIds else emptySet() }
    // Playlists (and Liked songs) can have a picture of the listener's choosing.
    val coverKey = when (type) { CollectionType.PLAYLIST, CollectionType.PRISM -> page.id; CollectionType.LIKED -> "liked"; CollectionType.DOWNLOADS -> "downloads"; else -> null }
    // An album can have one of the listener's choosing too (#305), from its ⋯ menu (its cover's tap stays as it was).
    val art = coverKey?.let { c.covers.art(it, page.thumbnail) }
        ?: (if (type == CollectionType.ALBUM) c.covers.albumArt(page.id, page.thumbnail) else null) ?: page.thumbnail
    // Prism's own lists without a picture take their colors from their newest song.
    val frostUrl = art ?: songs.firstOrNull()?.thumbnail
    val palette = rememberArtPalette(frostUrl)
    val frost = rememberFrost(frostUrl)
    val dark = LocalIsDark.current

    // The YouTube Music library bookmark, and an owned playlist's visibility (both change from here).
    var saved by remember(page.id) { mutableStateOf(live.page.savedToLibrary) }
    var privacy by remember(page.id) { mutableStateOf(live.page.privacy) }
    val savableId = when {
        !settings.isLoggedIn || saved == null -> null
        // What the page's own library button saves, as YouTube Music's app does (1.7.9.1's "Couldn't add to library").
        type == CollectionType.ALBUM -> live.page.libraryId ?: live.page.playlistId
        type == CollectionType.PLAYLIST && !live.page.owned -> live.page.id
        else -> null
    }
    val toggleSaved: () -> Unit = {
        val id = savableId
        val now = saved
        if (id != null && now != null) {
            saved = !now
            scope.launch {
                val done = if (type == CollectionType.ALBUM && now) c.library.removeAlbum(page.id, id)
                    else c.library.setSaved(id, !now, orElse = if (type == CollectionType.ALBUM) live.page.playlistId else null)
                if (!done) {
                    saved = now
                    Toast.makeText(context, tr(R.string.couldn_t_update_your_library), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    // Without an account, an album or a YouTube playlist is saved to the library on the phone instead (Library →
    // Albums / Playlists; playlists since 1.7.6, H7). Mixes change every time, so they're not kept.
    val savedAlbums by c.prismLibrary.albums.collectAsState()
    val savedPlaylists by c.prismLibrary.playlists.collectAsState()
    val localPlaylist = type == CollectionType.PLAYLIST && !settings.isLoggedIn && !com.prism.music.data.PrismLibrary.isMix(page.id)
    val localAlbum = (type == CollectionType.ALBUM || localPlaylist) && !settings.isLoggedIn
    val savedHere = if (localPlaylist) savedPlaylists.any { com.prism.music.data.PrismLibrary.samePlaylist(it.id, page.id) }
        else localAlbum && savedAlbums.any { it.id == page.id }
    val toggleSavedHere: () -> Unit = {
        if (localPlaylist) {
            if (savedHere) c.prismLibrary.unsavePlaylist(page.id)
            else c.prismLibrary.savePlaylist(
                com.prism.music.data.SavedPlaylist(
                    page.id, page.title,
                    page.subtitle.takeIf { it.isNotBlank() } ?: tr(R.string.playlist),
                    page.thumbnail, System.currentTimeMillis(),
                ),
            )
            Toast.makeText(context, if (savedHere) tr(R.string.removed_from_your_library) else tr(R.string.saved_to_your_library), Toast.LENGTH_SHORT).show()
        } else if (savedHere) {
            c.prismLibrary.unsaveAlbum(page.id)
            Toast.makeText(context, tr(R.string.removed_from_your_library), Toast.LENGTH_SHORT).show()
        } else {
            val by = page.artists.joinToString(", ") { it.name }.ifBlank { page.subtitle }
            c.prismLibrary.saveAlbum(
                com.prism.music.data.SavedAlbum(
                    page.id, page.title, listOfNotNull(page.releaseKind ?: tr(R.string.album), by.takeIf { it.isNotBlank() }, page.year?.toString()).joinToString(" • "),
                    page.thumbnail, page.playlistId, System.currentTimeMillis(),
                ),
            )
            Toast.makeText(context, tr(R.string.saved_to_your_library), Toast.LENGTH_SHORT).show()
        }
    }
    val radioId = when (type) {
        CollectionType.ALBUM -> live.page.playlistId
        CollectionType.PLAYLIST -> live.page.id
        else -> null
    }?.let { "RDAMPL$it" }
    val startRadio: () -> Unit = {
        radioId?.let { rid ->
            scope.launch {
                runCatching { c.radio(null, rid) }.onSuccess { r ->
                    c.player.playQueue(r.songs, 0, QueueSource(QueueKind.RADIO, tr(R.string.radio, page.title)))
                }.onFailure { Toast.makeText(context, tr(R.string.couldn_t_start_the_radio), Toast.LENGTH_SHORT).show() }
            }
        }
    }
    // Songs marked to skip don't stand between a list and counting as downloaded (#221).
    val skipped by c.downloadSkips.ids.collectAsState()
    val skippedCount = page.songs.count { com.prism.music.download.PlaylistDownloads.isSkipped(it.id, skipped, standIns) }
    val allDownloaded = com.prism.music.download.PlaylistDownloads.allDownloaded(page.songs, skipped, standIns) { downloads.forRow(it, standIns)?.state == Download.STATE_COMPLETED }
    // Kept downloaded (#298): whatever the open page has loaded comes down as it loads.
    val keepable = type == CollectionType.PRISM || (type == CollectionType.PLAYLIST && com.prism.music.download.PlaylistDownloads.canKeep(page.id))
    val keptNow = keepable && com.prism.music.download.PlaylistDownloads.keepKey(page.id) in settings.behavior.keptPlaylists
    LaunchedEffect(page.id, page.songs.size, keptNow) { if (keptNow) c.keptPlaylists.fromPage(page.id, page.songs) }
    // "Delete downloads": what's about to go, and Delete to go ahead.
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val toggleDownloads: () -> Unit = {
        if (allDownloaded) {
            // The same button downloads and removes, so removing asks first (#212: a whole playlist's downloads went
            // with one tap).
            val n = downloadedCount
            confirm = tr(R.string.delete_the_of_from_this_phone, com.prism.music.data.model.songCount(n), page.title) to {
                // Asked to take them off the phone, it shouldn't fetch them again.
                if (keptNow) c.keptPlaylists.set(page.id, false)
                c.downloads.removeMany(page.songs.map { it.id })
                Toast.makeText(context, trp(R.plurals.removed_n_downloads, n), Toast.LENGTH_SHORT).show()
            }
        } else if (skippedCount == page.songs.size) {
            Toast.makeText(context, tr(R.string.every_song_here_is_skipped), Toast.LENGTH_SHORT).show()
        } else {
            c.downloads.downloadAll(page.songs)
            val left = com.prism.music.download.PlaylistDownloads.wanted(page.songs, skipped, standIns).count { downloads.forRow(it.id, standIns)?.state != Download.STATE_COMPLETED }
            Toast.makeText(context, tr(R.string.downloading, com.prism.music.data.model.songCount(left)), Toast.LENGTH_SHORT).show()
        }
    }
    /** A playlist's or album's song can be left out when all of it downloads (#221). */
    fun skipAction(s: Song): List<SheetItem> {
        if (s.isLocal || s.isRss || (type != CollectionType.PLAYLIST && type != CollectionType.PRISM && type != CollectionType.ALBUM)) return emptyList()
        val out = com.prism.music.download.PlaylistDownloads.isSkipped(s.id, skipped, standIns)
        return listOf(SheetItem(if (out) PrismIcons.Download else PrismIcons.Block, if (out) tr(R.string.include_in_downloads) else tr(R.string.skip_in_downloads)) {
            // Taken back from the id it was marked by, the row's or its song's.
            if (out) { c.downloads.setSkipped(s.id, false); standIns[s.id]?.let { c.downloads.setSkipped(it, false) } }
            else c.downloads.setSkipped(s.id, true)
        })
    }

    // A smart playlist (1.8.3): its songs are what its rules find, so none is taken out or moved by hand.
    val smartRules = if (type == CollectionType.PRISM) c.prismLibrary.lists.collectAsState().value.firstOrNull { it.id == page.id }?.smart else null
    val measuring = if (smartRules?.usesSound == true) c.smartMeasure.progress.collectAsState().value else null
    // Owned playlists: a song can be taken out of it.
    val slots by live.setVideoIds.collectAsState()
    fun removeAction(s: Song): List<SheetItem> {
        if (smartRules != null) return emptyList()
        if (type == CollectionType.TAG) return listOf(SheetItem(PrismIcons.PlaylistRemove, tr(R.string.remove_this_tag)) {
            scope.launch { c.tags.remove(page.title, listOf(s.id)) }
        })
        if (type == CollectionType.PRISM) return listOf(SheetItem(PrismIcons.PlaylistRemove, tr(R.string.remove_from_this_playlist)) {
            c.prismLibrary.remove(page.id, s.id)
            Toast.makeText(context, tr(R.string.removed_from, page.title), Toast.LENGTH_SHORT).show()
        })
        val slot = slots[s.id]
        if (type != CollectionType.PLAYLIST || !live.page.owned || slot == null) return emptyList()
        return listOf(SheetItem(PrismIcons.PlaylistRemove, tr(R.string.remove_from_this_playlist)) {
            val before = live.songs.value
            live.songs.value = before.filterNot { it.id == s.id }
            scope.launch {
                runCatching { c.ytm.removeFromPlaylist(page.id, s.id, slot) }
                    .onSuccess { Toast.makeText(context, tr(R.string.removed_from, page.title), Toast.LENGTH_SHORT).show() }
                    .onFailure {
                        live.songs.value = before
                        Toast.makeText(context, tr(R.string.couldn_t_remove_it, it.friendlyMessage()), Toast.LENGTH_SHORT).show()
                    }
            }
        })
    }

    // Select many (1.8.3): picked songs out of an own playlist at once; a YouTube one is asked first (the bar does).
    val selectRemover = when {
        smartRules != null -> null
        type == CollectionType.TAG -> com.prism.music.ui.components.SelectionRemover(page.title, asks = false, label = tr(R.string.remove_this_tag)) { picked ->
            scope.launch { c.tags.remove(page.title, picked.map { it.id }) }
        }
        type == CollectionType.PRISM -> com.prism.music.ui.components.SelectionRemover(page.title, asks = false) { picked ->
            picked.forEach { c.prismLibrary.remove(page.id, it.id) }
            Toast.makeText(context, tr(R.string.removed_from, page.title), Toast.LENGTH_SHORT).show()
        }
        type == CollectionType.PLAYLIST && live.page.owned -> com.prism.music.ui.components.SelectionRemover(page.title, asks = true) { picked ->
            val gone = picked.filter { slots[it.id] != null }
            val before = live.songs.value
            live.songs.value = before.filterNot { s -> gone.any { it.id == s.id } }
            scope.launch {
                // One after another, as the song's own menu does it; any that fail come back.
                var error: Throwable? = null
                val failed = gone.filter { s ->
                    runCatching { c.ytm.removeFromPlaylist(page.id, s.id, slots[s.id]!!) }.onFailure { if (error == null) error = it }.isFailure
                }
                // Picked songs without the playlist's id for removing them (not loaded: offline, or a kept copy) weren't
                // removed either: said, not "Removed".
                val skipped = gone.size < picked.size
                if (failed.isEmpty() && !skipped) Toast.makeText(context, tr(R.string.removed_from, page.title), Toast.LENGTH_SHORT).show()
                else {
                    live.songs.value = before.filter { s -> gone.none { it.id == s.id } || failed.any { it.id == s.id } }
                    val why = error?.friendlyMessage() ?: tr(R.string.try_again_in_a_moment)
                    Toast.makeText(context, tr(R.string.couldn_t_remove_it, why), Toast.LENGTH_SHORT).show()
                }
            }
        }
        else -> null
    }

    confirm?.let { (text, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(tr(R.string.delete_downloads)) },
            text = { Text(text) },
            confirmButton = { PrismTextButton(onClick = { action(); confirm = null }) { Text(tr(R.string.delete)) } },
            dismissButton = { PrismTextButton(onClick = { confirm = null }) { Text(tr(R.string.cancel)) } },
        )
    }
    var coverSheet by remember { mutableStateOf(false) }
    if (coverSheet && coverKey != null) CoverSheet(coverKey, page.title, page.songs) { coverSheet = false }
    var albumCover by remember { mutableStateOf(false) }
    if (albumCover) com.prism.music.ui.components.CoverPickSheet(
        com.prism.music.ui.components.CoverFor.OfAlbum(page.id, page.title, page.thumbnail, c.covers.albumKey(page.id)),
    ) { albumCover = false }
    // Liked songs: made into (and kept in step with) a real YouTube Music playlist.
    var likedPlaylistSheet by remember { mutableStateOf(false) }
    val likedPlaylistId by c.likedPlaylist.playlistId.collectAsState()
    if (likedPlaylistSheet) LikedPlaylistSheet(art ?: songs.firstOrNull()?.thumbnail) { likedPlaylistSheet = false }
    // Rearranging an owned playlist's songs (#140): each drop goes to YouTube Music at once (one move each, in order) or
    // straight into a Prism playlist; the page shows it moved, and puts it back if YouTube refuses.
    var rearranging by remember(page.id) { mutableStateOf(false) }
    val selection = com.prism.music.ui.components.LocalSongSelection.current
    val canRearrange = (type == CollectionType.PLAYLIST && live.page.owned || type == CollectionType.PRISM && smartRules == null) && songs.size > 1
    val moves = remember(page.id) { kotlinx.coroutines.sync.Mutex() }
    val reorder = rememberLazyReorder(listState, songs)
    fun startRearranging() {
        when {
            loadingMore -> Toast.makeText(context, tr(R.string.wait_a_moment_the_rest_of), Toast.LENGTH_SHORT).show()
            // A song in it twice: which copy moved can't be told apart here.
            type == CollectionType.PLAYLIST && songs.distinctBy { it.id }.size != songs.size ->
                Toast.makeText(context, tr(R.string.this_playlist_has_a_song_in), Toast.LENGTH_LONG).show()
            else -> {
                if (sort != SortMode.DEFAULT) pickSort(SortMode.DEFAULT)
                if (groupByGenre) toggleGenreGroups()
                genreFilter = null; query = ""
                selection?.clear()
                rearranging = true
            }
        }
    }
    fun reorderTo(order: List<Song>) {
        val before = live.songs.value
        val moved = movedSong(before, order) ?: return
        live.songs.value = order
        if (type == CollectionType.PRISM) { c.prismLibrary.reorder(page.id, order); return }
        val at = order.indexOfFirst { it.id == moved.id }
        val successor = order.getOrNull(at + 1)
        val slot = slots[moved.id]
        val next = successor?.let { slots[it.id] }
        // YouTube places a song by the slot of the one after it (none: the end). Without both, the move would land
        // somewhere else on YouTube than on the page, so it isn't made.
        if (slot == null || successor != null && next == null) {
            live.songs.value = before
            Toast.makeText(context, tr(R.string.couldn_t_move_it_here_open), Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            moves.withLock {
                runCatching { c.ytm.movePlaylistItem(page.id, slot, next) }.onFailure {
                    // Only this move is undone: moves made after it went to YouTube in turn and stay.
                    live.songs.value = live.songs.value.toMutableList().apply {
                        val k = indexOfFirst { it.id == moved.id }
                        if (k >= 0) { removeAt(k); add(before.indexOfFirst { it.id == moved.id }.coerceIn(0, size), moved) }
                    }
                    Toast.makeText(context, tr(R.string.couldn_t_move_it, it.friendlyMessage()), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    var sharing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    // Duplicates found (1.8.3), shown in a sheet to take the extra copies out.
    var dupes by remember { mutableStateOf<List<com.prism.music.data.Duplicates.Group>?>(null) }
    var title by remember(page.id) { mutableStateOf(page.title) }
    // A smart playlist renamed in its rules' editor.
    LaunchedEffect(live.page.title) { title = live.page.title }
    var description by remember(page.id) { mutableStateOf(page.description) }
    var pageMenu by remember { mutableStateOf(false) }
    fun shareTarget(): ShareTarget = when (type) {
        CollectionType.ALBUM -> ShareTarget(title, page.artists.joinToString(", ") { it.name }.ifBlank { page.subtitle }, page.releaseKind ?: tr(R.string.album), art, ShareLinks.album(page.id))
        CollectionType.PLAYLIST -> ShareTarget(
            title, page.subtitle.removePrefix("Playlist • "), tr(R.string.playlist), art, ShareLinks.playlist(page.id),
            playlistId = page.id.takeIf { live.page.owned }, privacy = privacy.takeIf { live.page.owned },
        )
        CollectionType.LIKED -> ShareTarget(tr(R.string.liked_songs), com.prism.music.data.model.songCount(songs.size), tr(R.string.playlist), art, null, songs = songs)
        CollectionType.DOWNLOADS -> ShareTarget(tr(R.string.downloads), com.prism.music.data.model.songCount(songs.size), tr(R.string.playlist), art, null, songs = songs)
        CollectionType.MIX -> ShareTarget(title, page.subtitle, tr(R.string.mix_2), art, null, songs = songs)
        CollectionType.PRISM, CollectionType.TAG -> ShareTarget(title, com.prism.music.data.model.songCount(songs.size), tr(R.string.playlist), art, null, songs = songs)
    }

    // A wide landscape window (a tablet on its side) keeps the cover and its details beside the songs, as Apple Music
    // does on an iPad, instead of a header the width of the screen above them.
    val window = com.prism.music.ui.theme.LocalWindowLayout.current
    val sideBySide = window.width == com.prism.music.ui.theme.WidthClass.EXPANDED && window.landscape
    val hero = type != CollectionType.ALBUM && settings.playlistHeroCover && !sideBySide
    val config = LocalConfiguration.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var pageWidth by remember { mutableStateOf(config.screenWidthDp.dp) }
    val heroHeight = when {
        sideBySide -> statusTop + 160.dp
        // Never taller than most of the window (a phone on its side would otherwise get a hero taller than the screen).
        hero -> minOf(pageWidth / 1.08f, config.screenHeightDp.dp * 0.62f)
        else -> statusTop + 470.dp
    }
    val heroPx = with(LocalDensity.current) { heroHeight.toPx() }
    val heroScroll = { if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else heroPx }
    val shown = page.copy(title = title, description = description)
    // How far the bar has taken over from the header (0 at the top, 1 once the cover has scrolled away).
    val solid by remember(heroPx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else ((listState.firstVisibleItemScrollOffset - heroPx * 0.62f) / (heroPx * 0.18f)).coerceIn(0f, 1f)
        }
    }
    val density = LocalDensity.current
    var pageHeight by remember { mutableStateOf(0.dp) }
    // Search and chips go see-through on the frost (and keep the theme's color without it).
    val frosted = if (settings.frostedPages) frostFill(1.4f) else Color.Unspecified
    // Liked songs: pull down to bring in likes made elsewhere (#306), signed in (without an account they're all here).
    val likedRefresh = type == CollectionType.LIKED && settings.isLoggedIn
    val likesSyncing = c.library.syncState.collectAsState().value == com.prism.music.data.SyncState.SYNCING
    val pull = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()

    ArtworkAccent(palette) {
        Box(Modifier.fillMaxSize().onSizeChanged { with(density) { pageHeight = it.height.toDp(); pageWidth = it.width.toDp() } }) {
            FrostedBackdrop(frost, palette, heroHeight, heroScroll, parallax = if (hero) 0.5f else 0.35f, bleed = true)
            val header: @Composable () -> Unit = {
                if (hero) HeroHeader(type, shown, art, { if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset else 0 }, heroHeight)
                else Header(type, shown, art, coverKey) { coverSheet = true }
                HeaderDetails(
                    shown, centred = !hero, privacy = privacy,
                    saved = if (localAlbum) savedHere else saved.takeIf { savableId != null },
                    onSaved = if (localAlbum) toggleSavedHere else toggleSaved,
                )
            }
            val playRow: @Composable () -> Unit = {
                PlayShufflePills(
                    enabled = playable.isNotEmpty(),
                    onPlay = { c.player.playQueue(playable, 0, source) },
                    onShuffle = { c.player.playQueue(playable, 0, source, shuffle = true) },
                )
            }
            // Under Play and Shuffle: above them, a long description pushed Play behind the mini player.
            val about: @Composable () -> Unit = {
                shown.description?.takeIf { it.isNotBlank() }?.let { AboutBox(it, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)) }
            }
            val sideWidth = (pageWidth * 0.4f).coerceIn(320.dp, 460.dp)
            if (sideBySide) Column(
                Modifier.width(sideWidth).fillMaxHeight().verticalScroll(rememberScrollState())
                    .padding(top = statusTop + 40.dp, bottom = bottomPadding + 24.dp),
            ) {
                header()
                playRow()
                about()
            }
            com.prism.music.ui.components.SelectableSongs(visible, selectRemover) {
            LazyColumn(
                state = listState,
                modifier = (if (sideBySide) Modifier.padding(start = sideWidth) else Modifier)
                    .then(if (likedRefresh) Modifier.pullToRefresh(likesSyncing, pull, onRefresh = { c.library.syncInBackground() }) else Modifier),
                contentPadding = PaddingValues(top = if (sideBySide) statusTop + 60.dp else 0.dp, bottom = bottomPadding + 24.dp),
            ) {
                if (!sideBySide) item("header") { header() }
                if (type == CollectionType.DOWNLOADS) item { DownloadsInfo(page.songs) { text, action -> confirm = text to action } }
                if (type == CollectionType.DOWNLOADS) item("downloaded-collections") {
                    DownloadedCollections(visible, sort, query, filtered = genreFilter != null || query.isNotBlank())
                }
                if (ready != null && type != CollectionType.DOWNLOADS) item("offline") {
                    Text(
                        trp(R.plurals.offline_n_of_songs, songs.size, ready.size, songs.size) +
                            if (live.offline && !live.fromPhone) tr(R.string.as_it_was_when_you_last) else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
                    )
                }
                if (!sideBySide) item("play") { playRow() }
                // Songs a smart playlist's Sound rules can't see yet, and Measure now (1.8.3).
                if (smartRules != null && (live.unmeasured > 0 || measuring != null)) item("measure") {
                    SmartMeasureRow(smartRules, live.unmeasured, measuring)
                }
                // Rearranging (#140): the songs in the playlist's own order, to hold and move; nothing else in the way.
                if (rearranging) {
                    rearrangeSongs(reorder, onDone = { rearranging = false }, onReorder = ::reorderTo)
                    return@LazyColumn
                }
                if (!sideBySide) item("about") { about() }
                if (showSearch || sortable) item(key = "search") {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (showSearch) com.prism.music.ui.components.SearchField(
                            query, if (type == CollectionType.ALBUM) tr(R.string.search_in_album) else tr(R.string.search_in, title), { query = it }, Modifier.weight(1f),
                            container = frosted,
                        ) else Spacer(Modifier.weight(1f))
                        if (sortable) Box {
                            IconButton(onClick = { sortMenu = true }) {
                                Icon(
                                    PrismIcons.Sort, tr(R.string.sort),
                                    tint = if (sort != SortMode.DEFAULT || groupByGenre) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            DropdownMenu(sortMenu, { sortMenu = false }) {
                                SortMode.entries.filter { (it != SortMode.OLDEST || type in REVERSIBLE) && (it != SortMode.GENRE || showGenres) }.forEach { m ->
                                    DropdownMenuItem({ Text(sortLabel(m, type, page.owned)) }, { pickSort(m); sortMenu = false }, trailingIcon = { if (m == sort) Icon(PrismIcons.Check, null) })
                                }
                                HorizontalDivider()
                                if (showGenres) DropdownMenuItem({ Text(tr(R.string.group_by_genre)) }, { toggleGenreGroups(); sortMenu = false }, trailingIcon = { if (groupByGenre) Icon(PrismIcons.Check, null) })
                                DropdownMenuItem({ Text(tr(R.string.show_genres)) }, { c.settings.setPlaylistGenres(!showGenres); sortMenu = false }, trailingIcon = { if (showGenres) Icon(PrismIcons.Check, null) })
                                // #310: the album's name under each download, or just the artist.
                                if (type == CollectionType.DOWNLOADS) DropdownMenuItem(
                                    { Text(tr(R.string.show_album_names)) }, { c.settings.setDownloadsAlbums(!showAlbums); sortMenu = false },
                                    Modifier.testTag("downloadsAlbumsMenu"), trailingIcon = { if (showAlbums) Icon(PrismIcons.Check, null) },
                                )
                            }
                        }
                    }
                }
                if (showGenres && genreCounts.isNotEmpty()) item(key = "genres") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { com.prism.music.ui.components.PrismChip(genreFilter == null, { genreFilter = null }, tr(R.string.all), idle = frosted) }
                        items(genreCounts) { (g, n) ->
                            com.prism.music.ui.components.PrismChip(genreFilter == g, { genreFilter = if (genreFilter == g) null else g }, "${com.prism.music.data.meta.Genres.shown(g)} · $n", idle = frosted)
                        }
                    }
                }
                if (visible.isEmpty()) item {
                    if (query.isNotBlank()) EmptyState(PrismIcons.Search, tr(R.string.no_matches), tr(R.string.nothing_in_matches, title, query.trim()) + if (loadingMore) tr(R.string.yet_more_songs_are_still_loading) else ".")
                    else EmptyState(when (type) { CollectionType.LIKED -> PrismIcons.HeartFilled; CollectionType.DOWNLOADS -> PrismIcons.DownloadDone; else -> PrismIcons.Song }, genreFilter?.let { tr(R.string.no_songs, com.prism.music.data.meta.Genres.shown(it)) } ?: tr(R.string.nothing_here_yet),
                        when (type) {
                            CollectionType.LIKED -> tr(R.string.like_songs_from_the_player_and)
                            CollectionType.PRISM -> if (smartRules != null) tr(R.string.no_song_meets_these_rules) else tr(R.string.add_songs_from_any_song_s)
                            CollectionType.TAG -> tr(R.string.tag_songs_from_any_song_s)
                            else -> tr(R.string.songs_you_add_will_show_up)
                        })
                }
                if (groupByGenre && showGenres) {
                    // A song with a second genre shows under both.
                    visible.flatMap { s -> genresOf(s.id).ifEmpty { listOf(tr(R.string.sorting)) }.map { it to s } }
                        .groupBy({ it.first }, { it.second }).toList().sortedByDescending { it.second.size }.forEach { (g, songs) ->
                            item(key = "h:$g") {
                                Text(com.prism.music.data.meta.Genres.shown(g), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 4.dp))
                            }
                            itemsIndexed(songs, key = { i, s -> "g:$g:${s.id}:$i" }) { _, s ->
                                com.prism.music.ui.components.SwipeToQueue(s) {
                                    SongRow(s, dim(s), playing = current?.id == s.id, trailingInfo = null, extraActions = removeAction(s) + skipAction(s), showAlbum = showAlbums) {
                                        playFrom(songs, s, source.copy(title = "$title · $g"))
                                    }
                                }
                            }
                        }
                } else {
                    itemsIndexed(visible, key = { i, s -> "${s.id}:$i" }) { i, s ->
                        // Swipe right: play next; left: add to the queue.
                        com.prism.music.ui.components.SwipeToQueue(s) {
                            SongRow(
                                s,
                                dim(s),
                                index = if (type == CollectionType.ALBUM) i + 1 else null,
                                starred = s.id in popular,
                                playing = current?.id == s.id,
                                // #314: a song playing from one of your own files says so ("MP3 · 320 kbps, from your file").
                                trailingInfo = if (skippedCount > 0 && type != CollectionType.DOWNLOADS && com.prism.music.download.PlaylistDownloads.isSkipped(s.id, skipped, standIns)) tr(R.string.skipped_in_downloads)
                                else if (type == CollectionType.ALBUM) formatDuration(s.durationSec)
                                else if (type == CollectionType.DOWNLOADS && downloads[s.id]?.ownFile != null) tr(R.string.x_from_your_file, downloads[s.id]?.ownFile)
                                else genreVersion.let { genres[s.id] }?.takeIf { sort == SortMode.GENRE || genreFilter == null && showGenres }?.let { g -> listOfNotNull(s.album?.title?.takeIf { showAlbums }, com.prism.music.data.meta.Genres.shown(g)).joinToString(" • ") },
                                extraActions = removeAction(s) + skipAction(s),
                                showAlbum = showAlbums,
                            ) { playFrom(visible, s, source) }
                        }
                    }
                }
                item("footer") {
                    Text(
                        listOfNotNull(
                            page.year?.toString(),
                            trp(R.plurals.n_songs, visible.size) + (if (totalSec > 0) ", ${durationText(totalSec)}" else ""),
                            if (downloadedCount > 0) tr(R.string.downloaded_2, downloadedCount) else null,
                            if (skippedCount > 0 && type != CollectionType.DOWNLOADS) tr(R.string.n_skipped, skippedCount) else null,
                        ).joinToString(" · ") + if (loadingMore) tr(R.string.loading_more) else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
                    )
                }
                if (type == CollectionType.ALBUM) {
                    if (live.page.otherVersions.isNotEmpty()) item("versions") {
                        SectionHeader(tr(R.string.other_versions))
                        ItemCarousel(rememberTellApart(live.page.otherVersions), { nav.open(it) { s -> c.player.playSingle(s.song) } })
                    }
                    live.page.artists.firstOrNull { it.id != null }?.let { a -> item("moreby") { MoreBy(a.name, a.id!!, page.id) } }
                }
            }
            }

            if (likedRefresh) androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator(
                pull, likesSyncing, Modifier.align(Alignment.TopCenter).padding(top = statusTop + 60.dp),
            )

            // Long playlists get a slider on the right to jump through them (1.7.9, Reddit), saying which song it's at.
            if (!rearranging && !groupByGenre) com.prism.music.ui.components.FastScroller(
                listState, Modifier.align(Alignment.CenterEnd).padding(top = statusTop + 60.dp, bottom = bottomPadding),
                description = tr(R.string.scroll_through_the_songs),
            ) { f -> visible.takeIf { it.isNotEmpty() }?.let { v -> (f * (v.size - 1)).toInt().coerceIn(0, v.size - 1) + 1 }?.toString() }

            // Content melts away under the bar into the page's own frost.
            ScrollEdge({ solid }, statusTop + 60.dp + 34.dp, pageHeight) { m ->
                FrostedBackdrop(frost, palette, heroHeight, heroScroll, m, parallax = if (hero) 0.5f else 0.35f, bleed = true)
            }
            CollectionTopBar(
                title, { solid },
                downloadIcon = when {
                    type == CollectionType.DOWNLOADS -> null
                    allDownloaded -> PrismIcons.DownloadDone
                    else -> PrismIcons.Download
                },
                downloading = downloadingCount > 0,
                onDownload = toggleDownloads,
                onShare = { sharing = true },
                onMore = { pageMenu = true },
            )
        }

        // The page's ⋯ is the same sheet as a long press on its card (play, shuffle, play next, queue, DJ set), with
        // what only the page can do (radio, library, downloads, picture, edit…) in a group of its own.
        if (pageMenu) {
            val excludedLists by c.exclusions.playlists.collectAsState()
            val downloadsItems = if (type == CollectionType.DOWNLOADS) downloadsSheetItems { text, action -> confirm = text to action } else emptyList()
            val items = buildList {
                if (radioId != null) add(SheetItem(PrismIcons.Radio, tr(R.string.start_radio)) { startRadio() })
                if (savableId != null && saved != null) add(
                    SheetItem(if (saved == true) PrismIcons.LibraryAdded else PrismIcons.LibraryAdd, if (saved == true) tr(R.string.remove_from_library) else tr(R.string.add_to_library)) { toggleSaved() },
                )
                if (localAlbum) add(
                    SheetItem(if (savedHere) PrismIcons.LibraryAdded else PrismIcons.LibraryAdd, if (savedHere) tr(R.string.remove_from_library) else tr(R.string.add_to_library)) { toggleSavedHere() },
                )
                if (type != CollectionType.DOWNLOADS) add(
                    SheetItem(if (allDownloaded) PrismIcons.Delete else PrismIcons.Download, if (allDownloaded) tr(R.string.remove_downloads) else tr(R.string.download_all)) { toggleDownloads() },
                )
                if (keepable) add(
                    SheetItem(if (keptNow) PrismIcons.SyncOff else PrismIcons.Sync, if (keptNow) tr(R.string.stop_keeping_downloaded) else tr(R.string.keep_downloaded)) {
                        c.keptPlaylists.set(page.id, !keptNow)
                        Toast.makeText(context, if (keptNow) tr(R.string.new_songs_here_won_t_download) else tr(R.string.new_songs_here_will_download), Toast.LENGTH_SHORT).show()
                    },
                )
                live.page.artists.firstOrNull { it.id != null }?.let { a -> add(SheetItem(PrismIcons.Person, tr(R.string.go_to, a.name)) { nav.go(Routes.artist(a.id!!)) }) }
                if (type == CollectionType.LIKED) add(
                    SheetItem(PrismIcons.PlaylistAdd, if (likedPlaylistId != null) tr(R.string.playlist_kept_up_to_date) else tr(R.string.make_it_a_playlist)) { likedPlaylistSheet = true },
                )
                if (coverKey != null) add(SheetItem(PrismIcons.Photo, tr(R.string.change_picture)) { coverSheet = true })
                // #305: an album's own cover, kept on the phone, on its songs everywhere too.
                if (type == CollectionType.ALBUM) add(SheetItem(PrismIcons.Photo, tr(R.string.change_cover)) { albumCover = true })
                // A holiday mix: pinned to Home on its own, or kept as a Prism playlist (1.8.0).
                if (type == CollectionType.MIX) com.prism.music.data.seasonal.HolidayMixes.byId(page.id)?.let { addAll(holidayActions(c, context, it)) }
                if (smartRules != null) {
                    add(SheetItem(PrismIcons.Edit, tr(R.string.edit_rules)) { editing = true })
                    add(SheetItem(PrismIcons.PlaylistAdd, tr(R.string.save_a_copy_as_a_playlist)) {
                        val copy = c.prismLibrary.create(title, songs)
                        Toast.makeText(context, tr(R.string.saved_as_a_playlist, title), Toast.LENGTH_SHORT).show()
                        nav.go(com.prism.music.ui.Routes.playlist(copy))
                    })
                } else if ((type == CollectionType.PLAYLIST && live.page.owned) || type == CollectionType.PRISM) add(SheetItem(PrismIcons.Edit, tr(R.string.edit_details)) { editing = true })
                if (canRearrange) add(SheetItem(PrismIcons.SwapVertical, tr(R.string.rearrange_songs)) { startRearranging() })
                // A sleep or kids' playlist: plays as usual, but doesn't shape recommendations.
                if (type == CollectionType.PLAYLIST) {
                    val listId = page.playlistId ?: page.id.removePrefix("VL")
                    val out = excludedLists.any { it.id == listId }
                    add(SheetItem(PrismIcons.Block, if (out) tr(R.string.back_in_my_taste) else tr(R.string.leave_out_of_my_taste)) {
                        c.exclusions.setPlaylist(com.prism.music.data.foryou.Excluded(listId, title, art), !out)
                        Toast.makeText(context, if (out) tr(R.string.its_plays_count_towards_your_recommendations) else tr(R.string.its_plays_won_t_shape_your), Toast.LENGTH_SHORT).show()
                    })
                }
                addAll(downloadsItems)
                if (type == CollectionType.TAG) {
                    add(SheetItem(PrismIcons.Edit, tr(R.string.rename_tag)) { editing = true })
                    add(SheetItem(PrismIcons.Delete, tr(R.string.delete_tag), danger = true) { deleting = true })
                }
                // 1.8.3: the same song twice (a video and its song, two uploads), from a list one can take songs out of.
                if (selectRemover != null && type != CollectionType.TAG) add(SheetItem(PrismIcons.Copy, tr(R.string.find_duplicates)) {
                    val found = com.prism.music.data.Duplicates.find(songs)
                    if (found.isEmpty()) Toast.makeText(context, tr(R.string.no_song_is_here_twice), Toast.LENGTH_SHORT).show() else dupes = found
                })
                if ((type == CollectionType.PLAYLIST && live.page.owned) || type == CollectionType.PRISM) add(SheetItem(PrismIcons.Delete, tr(R.string.delete_playlist_2), danger = true) { deleting = true })
            }
            // The songs as the page shows them (filtered, sorted; offline only those on the phone).
            val queue = playable
            val target = com.prism.music.ui.components.Playable.Page(
                title, live.page.subtitle.ifBlank { com.prism.music.data.model.songCount(songs.size) }, art ?: songs.firstOrNull()?.thumbnail,
                album = type == CollectionType.ALBUM, share = shareTarget(),
            ) { queue to source }
            com.prism.music.ui.components.PlayActionsSheet(
                target, items,
                extraTitle = when (type) { CollectionType.ALBUM -> tr(R.string.this_album); CollectionType.DOWNLOADS -> tr(R.string.downloads); else -> tr(R.string.this_playlist) },
            ) { pageMenu = false }
        }

        dupes?.let { groups ->
            DuplicatesSheet(groups, youTube = type == CollectionType.PLAYLIST, onDismiss = { dupes = null }) { extras ->
                selectRemover?.remove?.invoke(extras)
                dupes = null
            }
        }

        if (editing && type == CollectionType.TAG) RenameTagDialog(page.title, onDismiss = { editing = false }) { to ->
            editing = false
            scope.launch {
                val name = c.tags.rename(page.title, to) ?: return@launch
                // The page is the tag's by name: the renamed one opens in its place.
                if (name != page.title) { nav.back(); nav.go(com.prism.music.ui.Routes.tag(name)) }
            }
        } else if (editing && smartRules != null) com.prism.music.ui.components.SmartEditorSheet(page.id) { editing = false }
        else if (editing && type == CollectionType.PRISM) EditPlaylistDialog(title, description.orEmpty(), null, onDismiss = { editing = false }) { t, d, _ ->
            editing = false
            c.prismLibrary.rename(page.id, t, d)
            title = t; description = d.ifBlank { null }
        } else if (editing) EditPlaylistDialog(title, description.orEmpty(), privacy ?: "PRIVATE", onDismiss = { editing = false }) { t, d, p ->
            editing = false
            scope.launch {
                runCatching {
                    c.ytm.editPlaylist(
                        page.id,
                        title = t.takeIf { it != title },
                        description = d.takeIf { it != description.orEmpty() },
                        privacy = p.takeIf { it != privacy },
                    )
                }.onSuccess {
                    title = t; description = d.ifBlank { null }; privacy = p
                    c.library.refreshCollections()
                    Toast.makeText(context, tr(R.string.saved_2), Toast.LENGTH_SHORT).show()
                }.onFailure { Toast.makeText(context, tr(R.string.couldn_t_save, it.friendlyMessage()), Toast.LENGTH_SHORT).show() }
            }
        }
    }
    if (deleting && type == CollectionType.TAG) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text(tr(R.string.delete_tag)) },
        text = { Text(tr(R.string.delete_the_tag_its_songs_stay, page.title)) },
        confirmButton = {
            PrismTextButton(onClick = {
                deleting = false
                scope.launch { c.tags.delete(page.title) }
                nav.back()
            }) { Text(tr(R.string.delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { PrismTextButton(onClick = { deleting = false }) { Text(tr(R.string.cancel)) } },
    ) else if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text(tr(R.string.delete_playlist)) },
        text = { Text(if (type == CollectionType.PRISM) tr(R.string.will_be_deleted_from_this_phone, title) else tr(R.string.will_be_deleted_from_your_youtube, title)) },
        confirmButton = {
            PrismTextButton(onClick = {
                deleting = false
                if (keptNow) c.keptPlaylists.set(page.id, false)
                if (type == CollectionType.PRISM) { c.prismLibrary.delete(page.id); nav.back(); return@PrismTextButton }
                scope.launch {
                    c.library.deletePlaylist(PlaylistItem(page.id, title, "", page.thumbnail))
                        .onSuccess { nav.back() }
                        .onFailure { Toast.makeText(context, tr(R.string.couldn_t_delete, title, it.friendlyMessage()), Toast.LENGTH_LONG).show() }
                }
            }) { Text(tr(R.string.delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { PrismTextButton(onClick = { deleting = false }) { Text(tr(R.string.cancel)) } },
    )
    if (sharing) ShareSheet(shareTarget()) { sharing = false }
}

private fun durationText(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return when {
        h > 0 -> tr(R.string.hr_min, h, m)
        else -> tr(R.string.min, m)
    }
}

/**
 * An album's "Other versions" with the editions that share a name told apart: YouTube lists them all as "After Hours
 * (Deluxe) · Album • The Weeknd", so those are looked up (a few at most) and show their song count, year and whether
 * they're explicit instead.
 */
@Composable
private fun rememberTellApart(items: List<com.prism.music.data.model.BrowseItem>): List<com.prism.music.data.model.BrowseItem> {
    val c = LocalContainer.current
    val repeated = remember(items) {
        items.filterIsInstance<com.prism.music.data.model.AlbumItem>().groupBy { it.title.lowercase() }.values.filter { it.size > 1 }.flatten().take(6)
    }
    val details by produceState(emptyMap<String, String>(), repeated) {
        if (repeated.isEmpty()) return@produceState
        value = kotlinx.coroutines.coroutineScope {
            repeated.map { a ->
                async {
                    runCatching { withContext(Dispatchers.IO) { c.ytm.album(a.id) } }.getOrNull()?.let { p ->
                        a.id to listOfNotNull(
                            com.prism.music.data.model.songCount(p.songs.size), p.year?.toString(), if (p.explicit) tr(R.string.explicit) else null,
                        ).joinToString(" • ")
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }
    }
    return remember(items, details) {
        items.map { item -> details[item.id]?.let { d -> (item as? com.prism.music.data.model.AlbumItem)?.copy(subtitle = d) } ?: item }
    }
}

/** Play and Shuffle side by side, as wide pills. */
@Composable
private fun PlayShufflePills(enabled: Boolean, onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Pill(PrismIcons.Play, tr(R.string.widget_play), primary = true, enabled = enabled, modifier = Modifier.weight(1f), onClick = onPlay)
        Pill(PrismIcons.Shuffle, tr(R.string.shuffle), primary = false, enabled = enabled, modifier = Modifier.weight(1f), onClick = onShuffle)
    }
}

@Composable
private fun Pill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, primary: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // Play: a solid pill in the text color, glowing in the spectrum's colors (or, picked in Icons & shapes, just its
    // outline); Shuffle: a pill of glass. Both in the buttons' shape.
    val outlined = primary && LocalAppSettings.current.shapes.playOutlined
    val filled = primary && !outlined
    val bg = if (filled) scheme.onSurface else if (outlined) Color.Transparent else frostFill(1.6f)
    val fg = if (filled) scheme.surface else scheme.onSurface
    val pill = com.prism.music.ui.theme.LocalUi.current.button
    Row(
        modifier.height(48.dp)
            .then(if (filled && enabled) Modifier.shadow(14.dp, pill, ambientColor = Color(0xFF848484).lit(), spotColor = Color(0xFF787878).lit()) else Modifier)
            .clip(pill).background(bg)
            .then(
                when {
                    outlined -> Modifier.border(1.5.dp, scheme.onSurface, pill)
                    !primary -> Modifier.border(1.dp, com.prism.music.ui.components.glassEdge(), pill)
                    else -> Modifier
                },
            )
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = fg)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, color = fg)
    }
}

/** The bar over the page: back, then the title once scrolled ([solid] goes 0 → 1); download, share and ⋯ on the right. */
@Composable
private fun CollectionTopBar(
    title: String,
    solid: () -> Float,
    downloadIcon: androidx.compose.ui.graphics.vector.ImageVector?,
    downloading: Boolean,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onMore: () -> Unit,
) {
    val nav = LocalNavigator.current
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(60.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            com.prism.music.ui.components.RoundAction(PrismIcons.ArrowBack, tr(R.string.back), glass = true) { nav.back() }
            Text(
                title, Modifier.weight(1f).padding(horizontal = 12.dp).graphicsLayer { alpha = solid() },
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (downloadIcon != null) {
                    if (downloading) com.prism.music.ui.components.FloatingDisc { ink ->
                        Box(Modifier.fillMaxSize().clickable(onClickLabel = tr(R.string.downloading_2), onClick = onDownload), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = ink, strokeWidth = 2.dp)
                        }
                    // Named for what a tap does (it removes once everything's downloaded, #212), not for the state it shows.
                    } else com.prism.music.ui.components.RoundAction(downloadIcon, if (downloadIcon == PrismIcons.DownloadDone) tr(R.string.remove_downloads) else tr(R.string.download_all), glass = true, onClick = onDownload)
                }
                com.prism.music.ui.components.RoundAction(PrismIcons.Share, tr(R.string.share), glass = true, onClick = onShare)
                com.prism.music.ui.components.RoundAction(PrismIcons.More, tr(R.string.more), glass = true, onClick = onMore)
            }
        }
    }
}

/** Centred cover, title and details, Apple Music style; the frosted backdrop behind is the cover itself. */
@Composable
private fun Header(type: CollectionType, page: CollectionPage, art: String?, coverKey: String?, onEditCover: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val cover = LocalUi.current.shape(16.dp)
    Column(
        Modifier.fillMaxWidth().statusBarsPadding().padding(top = 64.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.clip(cover).clickable(enabled = coverKey != null, onClickLabel = tr(R.string.change_picture), onClick = onEditCover)) {
            if (art != null) {
                Artwork(art, Modifier.size(248.dp).shadow(30.dp, cover), cover, size = 900)
            } else {
                Box(
                    Modifier.size(248.dp).shadow(30.dp, cover)
                        .background(com.prism.music.ui.theme.PrismTokens.spectrumRing(), cover),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(when (type) { CollectionType.DOWNLOADS -> PrismIcons.DownloadDone; CollectionType.LIKED -> PrismIcons.HeartFilled; else -> PrismIcons.QueueMusic }, null, Modifier.size(96.dp), tint = scheme.onPrimary)
                }
            }
            if (coverKey != null) GlassSurface(Modifier.align(Alignment.BottomEnd).padding(10.dp).size(40.dp), shape = RoundedCornerShape(50), onMedia = true, inPage = true) {
                Box(contentAlignment = Alignment.Center) { Icon(PrismIcons.Edit, tr(R.string.change_picture), Modifier.size(20.dp)) }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            page.title, style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Under the title: the artists (each opens their page) or the playlist's owner, a line of facts
 * (release type and year, explicit, visibility), the library bookmark, and the description.
 */
@Composable
private fun HeaderDetails(page: CollectionPage, centred: Boolean, privacy: String?, saved: Boolean?, onSaved: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val align = if (centred) TextAlign.Center else TextAlign.Start
    val nav = LocalNavigator.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = if (centred) 24.dp else 20.dp, vertical = 2.dp),
        horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        val linked = page.artists.filter { it.id != null }
        if (linked.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                page.artistThumbnail?.let {
                    Artwork(it, Modifier.size(22.dp), CircleShape, size = 120, placeholderIcon = PrismIcons.Person)
                    Spacer(Modifier.width(6.dp))
                }
                linked.forEachIndexed { i, a ->
                    if (i > 0) Text(if (i == linked.lastIndex) " & " else ", ", style = MaterialTheme.typography.titleSmall, color = scheme.primary)
                    Text(
                        a.name, style = MaterialTheme.typography.titleSmall, color = scheme.primary, fontWeight = FontWeight.Medium, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { nav.go(Routes.artist(a.id!!)) }.padding(horizontal = 2.dp, vertical = 2.dp),
                    )
                }
            }
        } else if (page.subtitle.isNotBlank()) Text(
            page.subtitle.removePrefix("Playlist • "), style = MaterialTheme.typography.titleSmall, color = scheme.primary,
            fontWeight = FontWeight.Medium, textAlign = align, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        val facts = buildList {
            if (page.kind == CollectionKind.ALBUM) {
                page.releaseKind?.let(::add)
                page.year?.let { add(it.toString()) }
                if (page.explicit) add(tr(R.string.explicit))
            } else if (page.secondSubtitle.isNotBlank()) add(page.secondSubtitle.replace(" • ", " · "))
        }
        val privacyLabel = when (privacy) { "PRIVATE" -> tr(R.string.private_text); "UNLISTED" -> tr(R.string.unlisted); "PUBLIC" -> tr(R.string.public_text); else -> null }
        if (facts.isNotEmpty() || privacyLabel != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            if (privacyLabel != null) {
                Icon(if (privacy == "PUBLIC") PrismIcons.Globe else if (privacy == "UNLISTED") PrismIcons.Link else PrismIcons.Lock, null, Modifier.size(14.dp), tint = scheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
            }
            Text(
                (listOfNotNull(privacyLabel) + facts).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant, textAlign = align,
            )
        }
        if (saved != null) {
            Row(
                Modifier.padding(top = 10.dp).clip(RoundedCornerShape(50)).background(frostFill(1.4f)).clickable(onClick = onSaved)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (saved) PrismIcons.LibraryAdded else PrismIcons.LibraryAdd, null, Modifier.size(18.dp), tint = scheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(if (saved) tr(R.string.in_your_library) else tr(R.string.add_to_library), style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
            }
        }
    }
}

/**
 * A playlist's picture edge to edge, like an artist page: it drifts behind the scroll and frosts
 * over into the page, with the title over its foot.
 */
@Composable
private fun HeroHeader(type: CollectionType, page: CollectionPage, art: String?, scroll: () -> Int, height: Dp) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().height(height)) {
        Box(Modifier.matchParentSize().clipToBounds().dissolveBottom(0.5f)) {
            if (art != null) AsyncImage(
                hiRes(art, 1440), null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { translationY = scroll() * 0.5f },
            ) else Box(
                // Liked songs and Downloads have no cover of their own: a soft wash of the split light over the page,
                // corner to corner, with their icon on a round of glass.
                Modifier.fillMaxSize().background(scheme.background).background(
                    Brush.linearGradient(com.prism.music.ui.theme.PrismTokens.spectrumColors.map { (_, c) -> c.copy(alpha = 0.42f) }),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(150.dp).clip(CircleShape).background(com.prism.music.ui.components.glassFill(1.6f))
                        .border(1.5.dp, com.prism.music.ui.theme.PrismTokens.spectrum, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(when (type) { CollectionType.DOWNLOADS -> PrismIcons.DownloadDone; CollectionType.LIKED -> PrismIcons.HeartFilled; else -> PrismIcons.QueueMusic }, null, Modifier.size(64.dp), tint = scheme.onSurface)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(130.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.32f), Color.Transparent))))
        Text(
            page.title,
            Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.Light,
                shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = if (scheme.background.luminance() < 0.5f) 0.35f else 0f), blurRadius = 18f),
            ),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Downloads' albums and playlists, as two rows of cards above the songs (nothing shows without any). Albums: any album
 * two or more downloaded songs come from, newest download first. Playlists: those kept on the phone
 * ([com.prism.music.data.OfflineLibrary], i.e. opened since 1.6.8) whose every song is downloaded, as their own page
 * marks them; a fully downloaded album kept there counts too.
 *
 * The songs' search, genre filter and sort apply to them too (Reddit 1.7: "the filters work for songs, can they for
 * albums?"): albums come from the songs left by the filters ([visible]), kept pages must match what's typed, and the
 * cards follow the songs' order by title, artist or age.
 */
@Composable
private fun DownloadedCollections(visible: List<Song>, sort: SortMode, query: String, filtered: Boolean) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val all by c.downloadState.collectAsState()
    // Only a finished download changes this (not every progress tick), so the rows aren't worked out again for those.
    val done = remember(all) { all.filterValues { it.state == Download.STATE_COMPLETED }.keys }
    val skipped by c.downloadSkips.ids.collectAsState()
    val standIns by c.downloadStandIns.collectAsState()
    val kept by produceState<List<Pair<String, CollectionPage>>?>(null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.offline.pages() }
    }
    val (albums, playlists) = remember(kept, done, visible, sort, query, filtered, skipped, standIns) {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        fun matches(p: CollectionPage) = words.isEmpty() ||
            "${p.title} ${p.artists.joinToString(" ") { it.name }}".lowercase().let { hay -> words.all { it in hay } }
        // A single's page is one song: that's already in the list below.
        val (keptAlbums, keptPlaylists) = kept.orEmpty()
            // Songs marked to skip don't keep a list out of here (#221).
            .filter { (_, p) -> p.songs.size >= 2 && com.prism.music.download.PlaylistDownloads.allDownloaded(p.songs, skipped, standIns) { it in done || standIns[it] in done } && matches(p) }
            .distinctBy { it.second.id }
            .partition { it.second.kind == CollectionKind.ALBUM }
        // One card per album name and artist: YouTube lists some albums twice (two editions, two ids), and the songs
        // can come from either. The card opens the edition most of them are from. With a filter on, only the songs it
        // left count (so a genre shows its albums).
        val pool = if (filtered) visible.filter { it.id in done } else c.downloads.completedSongs()
        val fromSongs = pool.filter { it.album?.id != null }
            .groupBy { it.album!!.title.lowercase() to it.primaryArtist.lowercase() }.values
            .filter { it.size >= 2 }
            .map { songs ->
                val id = songs.groupingBy { it.album!!.id!! }.eachCount().maxBy { it.value }.key
                AlbumItem(id, songs.first().album!!.title, listOf(songs.first().primaryArtist, tr(R.string.downloaded_2, songs.size)).joinToString(" • "), songs.first().thumbnail)
            }
        val albums = (fromSongs + keptAlbums.map { (id, p) ->
            AlbumItem(id, p.title, listOfNotNull(p.artists.firstOrNull()?.name, com.prism.music.data.model.songCount(p.songs.size)).joinToString(" • "), p.thumbnail)
        }).distinctBy { it.title.lowercase() to it.subtitle.substringBefore(" • ").lowercase() }
        val lists = keptPlaylists.map { (id, p) -> PlaylistItem(id, p.title, com.prism.music.data.model.songCount(p.songs.size), p.thumbnail) }
        fun <T : com.prism.music.data.model.BrowseItem> ordered(items: List<T>): List<T> = when (sort) {
            SortMode.TITLE, SortMode.ALBUM -> items.sortedBy { it.title.lowercase() }
            SortMode.ARTIST -> items.sortedBy { it.subtitle.substringBefore(" • ").lowercase() }
            SortMode.OLDEST -> items.asReversed()
            else -> items
        }
        ordered(albums) to ordered(lists)
    }
    if (albums.isEmpty() && playlists.isEmpty()) return
    Column {
        if (albums.isNotEmpty()) {
            SectionHeader(tr(R.string.albums))
            ItemCarousel(albums, { nav.go(Routes.album(it.id)) })
        }
        if (playlists.isNotEmpty()) {
            SectionHeader(tr(R.string.playlists))
            ItemCarousel(playlists, { nav.go(Routes.playlist(it.id)) })
        }
        SectionHeader(tr(R.string.songs))
    }
}

/** "More by …": the artist's other albums and singles, fetched once this part of the page is reached. */
@Composable
private fun MoreBy(name: String, artistId: String, albumId: String) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val items by produceState<List<AlbumItem>?>(null, artistId) {
        value = runCatching { c.ytm.artist(artistId) }.getOrNull()?.shelves.orEmpty()
            .filter { s -> s.title.equals("Albums", true) || s.title.contains("single", true) }
            .flatMap { it.items }.filterIsInstance<AlbumItem>().filter { it.id != albumId }.distinctBy { it.id }.take(14)
    }
    val list = items ?: return
    if (list.isEmpty()) return
    SectionHeader(tr(R.string.more_by, name), onMore = { nav.go(Routes.artist(artistId)) })
    ItemCarousel(list, { nav.go(Routes.album(it.id)) })
}

/** Rename an owned playlist, rewrite its description, or change who can open it. */
@Composable
private fun EditPlaylistDialog(title: String, description: String, privacy: String?, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var t by remember { mutableStateOf(title) }
    var d by remember { mutableStateOf(description) }
    var p by remember { mutableStateOf(privacy ?: "PRIVATE") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.edit_playlist)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                com.prism.music.ui.components.PrismTextField(t, { t = it.take(150) }, label = { Text(tr(R.string.name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                com.prism.music.ui.components.PrismTextField(d, { d = it.take(5000) }, label = { Text(tr(R.string.description)) }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
                // A Prism playlist (no [privacy]) is only on this phone: nobody else can open it anyway.
                if (privacy == null) return@Column
                Text(tr(R.string.who_can_open_it), style = MaterialTheme.typography.labelLarge)
                com.prism.music.ui.components.Segmented(listOf("PRIVATE", "UNLISTED", "PUBLIC"), p, { when (it) { "PRIVATE" -> tr(R.string.private_text); "UNLISTED" -> tr(R.string.unlisted); else -> tr(R.string.public_text) } }) { p = it }
                Text(
                    when (p) {
                        "PRIVATE" -> tr(R.string.only_you_can_see_it)
                        "UNLISTED" -> tr(R.string.anyone_with_the_link_can_open_2)
                        else -> tr(R.string.anyone_can_find_it_and_open)
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { PrismTextButton(onClick = { onSave(t.trim().ifBlank { title }, d.trim(), p) }) { Text(tr(R.string.save)) } },
        dismissButton = { PrismTextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) } },
    )
}

/** Choose a playlist's picture: a photo, or one of its songs' covers. Kept in Prism on this phone. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CoverSheet(key: String, title: String, songs: List<Song>, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var making by remember { mutableStateOf(false) }
    if (making) { CoverMakerSheet(key, title) { making = false; onDismiss() }; return }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            if (!c.covers.usePhoto(key, uri)) Toast.makeText(context, tr(R.string.couldn_t_use_that_picture), Toast.LENGTH_SHORT).show()
            onDismiss()
        }
    }
    val covers = remember(songs) { songs.mapNotNull { it.thumbnail }.distinctBy { hiRes(it, 120) }.take(30) }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 28.dp)) {
            Text(tr(R.string.playlist_picture), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.ListItem(
                headlineContent = { Text(tr(R.string.choose_a_photo)) },
                supportingContent = { Text(tr(R.string.from_your_phone_s_photos)) },
                leadingContent = { Icon(PrismIcons.Photo, null) },
                colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable {
                    picker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )
            // 1.7.6: a cover made here, from its name, two colors and a pattern.
            androidx.compose.material3.ListItem(
                headlineContent = { Text(tr(R.string.make_a_cover)) },
                supportingContent = { Text(tr(R.string.its_name_on_your_colors_and)) },
                leadingContent = { Icon(PrismIcons.Palette, null) },
                colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { making = true },
            )
            if (covers.isNotEmpty()) {
                Text(
                    tr(R.string.or_a_cover_from_this_playlist), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 8.dp),
                )
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(covers) { url ->
                        Artwork(
                            url, Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)).clickable { c.covers.useImage(key, hiRes(url, 1200) ?: url); onDismiss() },
                            RoundedCornerShape(12.dp), size = 226,
                        )
                    }
                }
            }
            if (c.covers.custom(key) != null) androidx.compose.material3.ListItem(
                headlineContent = { Text(tr(R.string.use_the_original_picture)) },
                leadingContent = { Icon(PrismIcons.History, null) },
                colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.padding(top = 8.dp).clickable { c.covers.reset(key); onDismiss() },
            )
            Text(
                tr(R.string.the_picture_shows_everywhere_in_prism),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp),
            )
        }
    }
}

/**
 * The one song a drop moved, [before] → [after] (a [com.prism.music.ui.components.ReorderList] drop moves one row):
 * the lists first differ at i and last at j, and either the song at i went down to j or the one at j came up to i.
 */
internal fun movedSong(before: List<Song>, after: List<Song>): Song? {
    if (before.size != after.size) return null
    val i = before.indices.firstOrNull { before[it].id != after[it].id } ?: return null
    val j = before.indices.last { before[it].id != after[it].id }
    return if (after[j].id == before[i].id) before[i] else before[j]
}

/**
 * An owned playlist's songs to hold and move (#140), straight in the page's own lazy list: only the rows on screen
 * exist, so a 1,100-song playlist opens as fast as a short one (laying them all out at once held the screen ~2 s).
 * Hold a row or drag its grip; it trades places with each row it's carried over, the page scrolls while it's held near
 * the top or bottom, and the drop is one move ([onReorder]).
 */
private class LazyReorder(val listState: androidx.compose.foundation.lazy.LazyListState) {
    /** How much of the list's top the page's bar covers, in px. */
    var topCover = 0f
    var order by mutableStateOf<List<Song>>(emptyList())
    var dragging by mutableStateOf<String?>(null)
    var offset by mutableStateOf(0f)
    private var before: List<Song> = emptyList()

    fun start(id: String) { before = order; dragging = id; offset = 0f }

    fun drag(dy: Float) {
        val id = dragging ?: return
        offset += dy
        val visible = listState.layoutInfo.visibleItemsInfo
        val cur = visible.firstOrNull { it.key == rowKey(id) } ?: return
        val center = cur.offset + offset + cur.size / 2f
        val over = visible.firstOrNull { it.key != cur.key && (it.key as? String)?.startsWith(ROW) == true && center.toInt() in it.offset until it.offset + it.size }
            ?: return
        val from = order.indexOfFirst { it.id == id }
        val to = order.indexOfFirst { rowKey(it.id) == over.key }
        if (from < 0 || to < 0) return
        order = order.toMutableList().apply { add(to, removeAt(from)) }
        // It now lays out in the other row's place: keep it under the finger.
        offset += cur.offset - over.offset
    }

    /** Nudges the page when the carried row is held near its top or bottom; returns how far it scrolled. */
    suspend fun edgeScroll(): Float {
        val id = dragging ?: return 0f
        val info = listState.layoutInfo
        val cur = info.visibleItemsInfo.firstOrNull { it.key == rowKey(id) } ?: return 0f
        val top = cur.offset + offset
        val bottom = top + cur.size
        val zone = cur.size * 1.5f
        // The list runs under the page's top bar and under the mini player and tab bar (its bottom padding): the zones
        // start where the rows can be seen.
        val seenTop = info.viewportStartOffset + topCover
        val seenBottom = info.viewportEndOffset - info.afterContentPadding
        val step = when {
            top < seenTop + zone -> -(seenTop + zone - top).coerceIn(2f, 24f)
            bottom > seenBottom - zone -> (bottom - (seenBottom - zone)).coerceIn(2f, 24f)
            else -> 0f
        }
        if (step == 0f) return 0f
        val moved = listState.scrollBy(step)
        offset += moved
        drag(0f)
        return moved
    }

    fun end(): List<Song>? {
        dragging = null; offset = 0f
        return order.takeIf { it != before }
    }

    companion object {
        const val ROW = "rearrange:"
        fun rowKey(id: String) = ROW + id
    }
}

@Composable
private fun rememberLazyReorder(listState: androidx.compose.foundation.lazy.LazyListState, songs: List<Song>): LazyReorder {
    val r = remember(listState) { LazyReorder(listState) }
    r.topCover = with(androidx.compose.ui.platform.LocalDensity.current) {
        (WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 60.dp).toPx()
    }
    // The playlist as it is, unless a row is being carried.
    LaunchedEffect(songs) { if (r.dragging == null) r.order = songs }
    if (r.dragging == null && r.order.isEmpty()) r.order = songs
    if (r.dragging != null) LaunchedEffect(r.dragging) {
        while (true) { androidx.compose.runtime.withFrameNanos { }; r.edgeScroll() }
    }
    return r
}

/** The rows of [rearrangeSongs]: light (no pictures), a number, the title and artist, and the grip. */
private fun androidx.compose.foundation.lazy.LazyListScope.rearrangeSongs(r: LazyReorder, onDone: () -> Unit, onReorder: (List<Song>) -> Unit) {
    item("rearrange-head") {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr(R.string.hold_a_song_or_drag_its), Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            com.prism.music.ui.components.PrismChip(true, onDone, tr(R.string.done), icon = PrismIcons.Check)
        }
    }
    itemsIndexed(r.order, key = { _, s -> LazyReorder.rowKey(s.id) }) { i, s ->
        val held = r.dragging == s.id
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
        fun Modifier.dragging(afterHold: Boolean) = pointerInput(s.id) {
            val start: (androidx.compose.ui.geometry.Offset) -> Unit = {
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                r.start(s.id)
            }
            val end: () -> Unit = { r.end()?.let(onReorder) }
            val move: (androidx.compose.ui.input.pointer.PointerInputChange, androidx.compose.ui.geometry.Offset) -> Unit = { change, amount -> change.consume(); r.drag(amount.y) }
            if (afterHold) detectDragGesturesAfterLongPress(start, end, end, move)
            else detectDragGestures(start, end, end, move)
        }
        Row(
            Modifier.fillMaxWidth()
                .then(if (held) Modifier else Modifier.animateItem())
                .zIndex(if (held) 1f else 0f)
                .graphicsLayer {
                    translationY = if (held) r.offset else 0f
                    shadowElevation = if (held) 12.dp.toPx() else 0f
                }
                .background(if (held) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
                .dragging(afterHold = true)
                .padding(start = 20.dp, end = 12.dp).height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${i + 1}", Modifier.width(36.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
            Column(Modifier.weight(1f)) {
                Text(s.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s.artistText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            com.prism.music.ui.components.DragGrip(Modifier.dragging(afterHold = false).padding(start = 8.dp), tr(R.string.drag, s.title))
        }
    }
}
/** Lists whose own order is the order songs came in, so the other way round means something (#141). */
private val REVERSIBLE = setOf(CollectionType.DOWNLOADS, CollectionType.LIKED, CollectionType.PLAYLIST, CollectionType.PRISM, CollectionType.TAG)

/** A tag's page id is this and the tag; a playlist item with such an id opens the tag (a search result). */
const val TAG_PAGE = "tag:"

/** A tag as a playlist item (Search's library results), opening its page. */
fun tagItem(t: com.prism.music.data.db.TagSummary) =
    PlaylistItem(TAG_PAGE + t.tag, t.tag, tr(R.string.tag_n_songs, com.prism.music.data.model.songCount(t.songs)), t.thumbnail)

/**
 * A smart playlist's songs its Sound rules can't see yet (1.8.3): how many, and Measure now (the scan Normalize and DJ
 * mode use, one song after another; songs not on the phone only on Wi-Fi); while it runs, how far it is.
 */
@Composable
private fun SmartMeasureRow(rules: com.prism.music.data.SmartRules, unmeasured: Int, progress: Pair<Int, Int>?) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 2.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (progress != null) tr(R.string.measuring_n_of_n, progress.first, progress.second) else trp(R.plurals.n_songs_aren_t_measured_yet, unmeasured, unmeasured),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
        )
        if (progress != null) PrismTextButton(onClick = { c.smartMeasure.stop() }) { Text(tr(R.string.stop)) }
        else PrismTextButton(onClick = {
            scope.launch {
                val ids = c.smart.unmeasuredIds(rules, com.prism.music.data.SmartMeasure.AT_ONCE)
                val streams = c.smartMeasure.start(ids)
                if (!streams) Toast.makeText(context, tr(R.string.on_wi_fi_songs_not_on_the_phone_are_measured_too), Toast.LENGTH_LONG).show()
            }
        }, modifier = Modifier.testTag("measureNow")) { Text(tr(R.string.measure_now)) }
    }
}

/** A tag's new name; the same name or an empty one does nothing. */
@Composable
private fun RenameTagDialog(tag: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(tag) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.rename_tag)) },
        text = {
            com.prism.music.ui.components.PrismTextField(
                name, { name = it.take(com.prism.music.data.TagStore.MAX_LENGTH) }, Modifier.fillMaxWidth().testTag("tagName"),
                label = { Text(tr(R.string.name)) }, singleLine = true,
            )
        },
        confirmButton = {
            PrismTextButton(onClick = { onRename(name) }, enabled = com.prism.music.data.TagStore.clean(name).let { it != null && it != tag }) { Text(tr(R.string.rename)) }
        },
        dismissButton = { PrismTextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) } },
    )
}

/**
 * [SortMode.OLDEST] is the list's own order reversed. Downloads and Liked songs start with the newest, so that's
 * "Oldest first"; your own playlists get new songs at the end, so it's "Newest first" (#141); anyone else's playlist
 * is in its maker's order, so it's just reversed.
 */
private fun sortLabel(m: SortMode, type: CollectionType, owned: Boolean = false) = when {
    m == SortMode.DEFAULT && type == CollectionType.DOWNLOADS -> tr(R.string.recently_added)
    m == SortMode.OLDEST && (type == CollectionType.PRISM || type == CollectionType.PLAYLIST && owned) -> tr(R.string.newest_first)
    m == SortMode.OLDEST && type == CollectionType.PLAYLIST -> tr(R.string.reverse_order)
    else -> m.label
}

private fun sizeText(bytes: Long): String =
    if (bytes >= 1_073_741_824L) tr(R.string.t_1f_gb).format(bytes / 1_073_741_824.0) else tr(R.string.t_0f_mb).format(bytes / 1_048_576.0)

/** Under the Downloads header: space used, saved lyrics, and anything still downloading. */
@Composable
private fun DownloadsInfo(songs: List<Song>, ask: (String, () -> Unit) -> Unit) {
    val c = LocalContainer.current
    val scheme = MaterialTheme.colorScheme
    val all by c.downloadState.collectAsState()
    val levels by c.downloads.lyricLevels.collectAsState()
    val working by c.downloads.lyricsWorking.collectAsState()
    val videos by c.videoState.collectAsState()
    val active = remember(all) { all.filter { it.value.state != Download.STATE_COMPLETED && it.value.state != Download.STATE_FAILED } }
    val bytes = remember(all) { all.values.filter { it.state == Download.STATE_COMPLETED }.sumOf { it.bytes } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(
            trp(R.plurals.n_songs_on_this_phone, songs.size, songs.size, sizeText(bytes)) +
                if (videos.isNotEmpty()) tr(R.string.with_videos, sizeText(bytes + videos.values.sumOf { it.bytes })) else "",
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
        )
        // Saved music videos take far more room than songs, so they get their own line.
        val savedVideos = remember(videos) { videos.values.filter { it.state == Download.STATE_COMPLETED } }
        val videosLoading = remember(videos) { videos.values.count { it.state == Download.STATE_QUEUED || it.state == Download.STATE_DOWNLOADING } }
        if (videos.isNotEmpty()) Text(
            trp(R.plurals.n_music_videos, savedVideos.size, savedVideos.size, sizeText(videos.values.sumOf { it.bytes })) +
                if (videosLoading > 0) tr(R.string.downloading_3, videosLoading) else "",
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
        )
        val lossless = remember(all) { all.count { it.value.lossless && it.value.ownFile == null } }
        if (lossless > 0) Text(
            tr(R.string.lossless_from_your_phone_s_music, lossless),
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
        )
        // #314: your own MP3/AAC/Ogg files playing in place of the stream.
        val ownFiles = remember(all) { all.count { it.value.ownFile != null } }
        if (ownFiles > 0) Text(
            tr(R.string.n_from_your_own_song_files, ownFiles),
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
        )
        val ids = songs.map { it.id }
        if (ids.any { it in levels }) {
            val withLyrics = ids.count { (levels[it] ?: 0) > 0 }
            val karaoke = ids.count { (levels[it] ?: 0) >= 3 }
            Text(
                tr(R.string.lyrics_for_karaoke, withLyrics, karaoke) + if (working > 0) tr(R.string.getting_more) else "",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
        }
        if (active.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr(R.string.downloading_4, active.size), style = MaterialTheme.typography.labelMedium, color = scheme.primary)
            PrismTextButton(onClick = { ask(trp(R.plurals.cancel_n_downloads, active.size)) { c.downloads.removeMany(active.keys) } }) { Text(tr(R.string.cancel)) }
        }
        val unfinished = remember(all) { all.values.filter { it.state != Download.STATE_COMPLETED } }
        if (unfinished.isNotEmpty()) DownloadQueueStatus(unfinished)
    }
}

/** How far one download has got, in words: a percentage, or why it isn't moving. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun downloadStateText(d: com.prism.music.download.DownloadInfo, waitingForNetwork: Boolean): String = when (d.state) {
    Download.STATE_FAILED -> tr(R.string.failed)
    Download.STATE_QUEUED -> if (waitingForNetwork) tr(R.string.waiting_for_wi_fi) else tr(R.string.queued)
    Download.STATE_STOPPED -> tr(R.string.paused)
    Download.STATE_RESTARTING -> tr(R.string.restarting)
    else -> if (d.percent >= 0) "${d.percent.toInt()}%" else tr(R.string.starting_2)
}

/**
 * Why unfinished downloads aren't moving, with what to do about it (#55): ones held back for Wi-Fi can go on mobile
 * data, and failed ones can be tried again (they're also retried by themselves when the phone is back online).
 */
@Composable
fun DownloadQueueStatus(unfinished: Collection<com.prism.music.download.DownloadInfo>) {
    val c = LocalContainer.current
    val scheme = MaterialTheme.colorScheme
    val waiting by c.downloads.waitingForNetwork.collectAsState()
    val failed = unfinished.count { it.state == Download.STATE_FAILED }
    val queued = unfinished.count { it.state == Download.STATE_QUEUED }
    if (waiting && queued > 0) Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr(R.string.waiting_for_wi_fi_2, queued), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        // It switches the setting off for good (Settings → Downloads & storage), so it says so.
        PrismTextButton(onClick = { c.settings.setDownloadWifiOnly(false) }) { Text(tr(R.string.turn_off_wi_fi_only)) }
    }
    var showFailed by remember { mutableStateOf(false) }
    if (failed > 0) Row(verticalAlignment = Alignment.CenterVertically) {
        Text(trp(R.plurals.n_downloads_failed, failed), style = MaterialTheme.typography.labelMedium, color = scheme.error)
        // Which ones, and why (#98).
        PrismTextButton(onClick = { showFailed = true }) { Text(tr(R.string.see_which)) }
        PrismTextButton(onClick = { c.downloads.retryFailed() }) { Text(tr(R.string.try_again)) }
    }
    if (showFailed) FailedDownloadsSheet { showFailed = false }
}

/**
 * The downloads that failed, each with why (GitHub #98), and a way to try each again or remove it. Songs that failed
 * before Prism kept the reason say so.
 */
@Composable
fun FailedDownloadsSheet(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val scheme = MaterialTheme.colorScheme
    val ui = LocalUi.current
    val all by c.downloadState.collectAsState()
    val why by c.downloads.failures.collectAsState()
    val failed = remember(all) { all.filter { it.value.state == Download.STATE_FAILED }.toList().sortedByDescending { it.second.addedAt } }
    // Everything tried again or removed: nothing left to show.
    LaunchedEffect(failed.isEmpty()) { if (failed.isEmpty()) onDismiss() }
    ActionSheet(art = null, onDismiss = onDismiss) { close ->
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)) {
            Text(trp(R.plurals.n_downloads_failed, failed.size), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Light)
            Text(
                tr(R.string.prism_also_tries_them_again_by),
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).pane(ui.card)) {
            failed.take(MAX_FAILED_ROWS).forEachIndexed { i, (id, d) ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 70.dp), color = scheme.onSurface.copy(alpha = 0.07f))
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(d.song?.thumbnail, Modifier.size(46.dp), ui.art, size = 226)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.song?.title ?: tr(R.string.unknown_song), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        d.song?.let { Text(it.artistText, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        Text(
                            why[id] ?: tr(R.string.failed_before_prism_kept_the_reason),
                            style = MaterialTheme.typography.bodySmall, color = scheme.error, maxLines = 3,
                        )
                    }
                    IconButton(onClick = { c.downloads.retryFailed(setOf(id)) }) { Icon(PrismIcons.Refresh, tr(R.string.try_again)) }
                    // #221: gone from here, and left out the next time a list it's on downloads.
                    IconButton(onClick = { c.downloads.setSkipped(id, true) }) { Icon(PrismIcons.Block, tr(R.string.skip_in_downloads)) }
                    IconButton(onClick = { c.downloads.removeMany(setOf(id)) }) { Icon(PrismIcons.Delete, tr(R.string.remove)) }
                }
            }
        }
        if (failed.size > MAX_FAILED_ROWS) Text(
            tr(R.string.and_more_2, failed.size - MAX_FAILED_ROWS),
            Modifier.padding(start = 28.dp, top = 8.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            PrismTextButton(onClick = { close { c.downloads.removeMany(failed.map { it.first }) } }) { Text(tr(R.string.remove_all)) }
            com.prism.music.ui.components.PrismButton(onClick = { close { c.downloads.retryFailed() } }) { Text(tr(R.string.try_all_again)) }
        }
    }
}

private const val MAX_FAILED_ROWS = 60

/** Downloads' ⋯ menu entries: lyrics and bulk deletes. */
@Composable
private fun downloadsSheetItems(ask: (String, () -> Unit) -> Unit): List<SheetItem> {
    val c = LocalContainer.current
    val all by c.downloadState.collectAsState()
    var smartIds by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(all.size) {
        smartIds = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.db.songs().smartDownloads().map { it.id }.toSet() }
    }
    val smart = all.filter { it.value.state == Download.STATE_COMPLETED && it.key in smartIds }.keys
    val videos by c.videoState.collectAsState()
    // With Premium quality on: downloads below it (from before it was on, or before 1.7.7.1 made it work) can come down
    // again in 256 kbps (#149).
    val settings = com.prism.music.ui.theme.LocalAppSettings.current
    var below by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(all.size, settings.premiumStream, settings.isLoggedIn) {
        below = if (!settings.premiumStream || !settings.isLoggedIn) emptyList()
        else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.downloads.belowPremium() }
    }
    val nav = LocalNavigator.current
    return buildList {
        // What's on the phone without having been downloaded (#159).
        add(SheetItem(PrismIcons.DownloadDone, tr(R.string.saved_while_playing)) { nav.go(Routes.SAVED) })
        add(SheetItem(PrismIcons.Lyrics, tr(R.string.get_missing_lyrics)) { c.downloads.backfillLyrics(force = true) })
        if (below.isNotEmpty()) add(SheetItem(PrismIcons.Download, tr(R.string.download_again_in_premium_quality, below.size)) {
            ask(
                tr(R.string.download_again_in_premium_quality_256_all, com.prism.music.data.model.songCount(below.size))
            ) { c.downloads.downloadAgain(below) }
        })
        if (smart.isNotEmpty()) add(SheetItem(PrismIcons.Delete, tr(R.string.delete_smart_downloads, smart.size), danger = true) {
            ask(tr(R.string.delete_the_smart_downloads_added_they, com.prism.music.data.model.songCount(smart.size))) { c.downloads.removeMany(smart) }
        })
        if (videos.isNotEmpty()) add(SheetItem(PrismIcons.Delete, tr(R.string.delete_music_videos, sizeText(videos.values.sumOf { it.bytes })), danger = true) {
            ask(tr(R.string.delete_the_saved_music_videos_the, videos.size)) { videos.keys.forEach(c.downloads::removeVideo) }
        })
        add(SheetItem(PrismIcons.Delete, tr(R.string.delete_all_downloads), danger = true) {
            val lossless = all.count { it.value.lossless && it.value.ownFile == null }
            val ownFiles = all.count { it.value.ownFile != null }
            ask(
                trp(R.plurals.delete_all_n_downloads, all.size, all.size, sizeText(c.downloads.totalBytes)) +
                    (if (lossless > 0) tr(R.string.your_lossless_files_stay_on_the, lossless) else "") +
                    if (ownFiles > 0) tr(R.string.your_own_song_files_stay, ownFiles) else ""
            ) { c.downloads.removeAll() }
        })
    }
}
